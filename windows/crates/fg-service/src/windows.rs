//! Windows-only plumbing: the service control manager entry, the named pipe and who may use it.
//!
//! Security model of the pipe `\\.\pipe\FastGameVPN` (created by the service, running as SYSTEM):
//!  * it is created as the *first instance*, so no other process can squat the name before the service;
//!  * remote clients are rejected;
//!  * the DACL allows SYSTEM and Administrators full access and interactive (logged-on) users read/write;
//!  * every connection is then checked again: the client process must be the installed `FastGame.exe`
//!    (the image path is under the admin-writable install folder, so an ordinary user program cannot
//!    pretend to be it). Everything else is closed without an answer.

use std::ffi::{c_void, OsString};
use std::io;
use std::os::windows::io::AsRawHandle;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use fg_core::engine::VpnEngine;
use fg_core::ipc::{serve_connection, WATCHDOG_SECONDS};
use tokio::net::windows::named_pipe::ServerOptions;
use tokio::sync::Notify;
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};
use windows_sys::Win32::Foundation::{CloseHandle, LocalFree, HANDLE};
use windows_sys::Win32::Security::Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1};
use windows_sys::Win32::Security::SECURITY_ATTRIBUTES;
use windows_sys::Win32::System::Pipes::GetNamedPipeClientProcessId;
use windows_sys::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION};

use crate::engine::{SingBoxEngine, SystemResolver};
use crate::watchdog::Watchdog;

pub const SERVICE_NAME: &str = "FastGameVPN";
pub const PIPE_NAME: &str = r"\\.\pipe\FastGameVPN";
const APP_EXE: &str = "FastGame.exe";
/// SYSTEM and Administrators: full; Interactive users: generic read + write.
const PIPE_SDDL: &str = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)";

fn install_dir() -> PathBuf {
    std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)).unwrap_or_default()
}

/// SYSTEM's own profile folder: readable by SYSTEM and Administrators only, which is where the
/// short-lived engine configuration (it contains the account's UUID) is written.
fn work_dir() -> PathBuf {
    let root = std::env::var_os("SystemRoot").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(r"C:\Windows"));
    root.join(r"System32\config\systemprofile\AppData\Local\FastGame")
}

fn make_engine() -> Arc<dyn VpnEngine> {
    Arc::new(SingBoxEngine::new(install_dir().join("sing-box.exe"), work_dir(), Arc::new(SystemResolver)))
}

struct SecurityDescriptor(*mut c_void);

impl SecurityDescriptor {
    fn from_sddl(sddl: &str) -> io::Result<Self> {
        let wide: Vec<u16> = sddl.encode_utf16().chain(std::iter::once(0)).collect();
        let mut sd: *mut c_void = std::ptr::null_mut();
        // SAFETY: `wide` is NUL-terminated and outlives the call; `sd` receives a LocalAlloc'ed descriptor.
        let ok = unsafe { ConvertStringSecurityDescriptorToSecurityDescriptorW(wide.as_ptr(), SDDL_REVISION_1, &mut sd, std::ptr::null_mut()) };
        if ok == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(Self(sd))
    }
}

impl Drop for SecurityDescriptor {
    fn drop(&mut self) {
        // SAFETY: allocated by the Convert... call above.
        unsafe { LocalFree(self.0) };
    }
}

// The descriptor is immutable after creation.
unsafe impl Send for SecurityDescriptor {}
unsafe impl Sync for SecurityDescriptor {}

fn client_image_path(pipe_handle: HANDLE) -> io::Result<PathBuf> {
    let mut pid = 0u32;
    // SAFETY: valid pipe handle owned by the server object; `pid` is a valid out pointer.
    if unsafe { GetNamedPipeClientProcessId(pipe_handle, &mut pid) } == 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: plain query call; the handle is closed below on every path.
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if process.is_null() {
        return Err(io::Error::last_os_error());
    }
    let mut buf = vec![0u16; 1024];
    let mut len = buf.len() as u32;
    // SAFETY: `buf` has room for `len` UTF-16 units.
    let ok = unsafe { QueryFullProcessImageNameW(process, 0, buf.as_mut_ptr(), &mut len) };
    let err = io::Error::last_os_error();
    // SAFETY: handle from OpenProcess.
    unsafe { CloseHandle(process) };
    if ok == 0 {
        return Err(err);
    }
    Ok(PathBuf::from(OsString::from(String::from_utf16_lossy(&buf[..len as usize]))))
}

fn is_our_app(image: &Path) -> bool {
    let expected = install_dir().join(APP_EXE);
    image.to_string_lossy().eq_ignore_ascii_case(&expected.to_string_lossy())
}

async fn serve(engine: Arc<dyn VpnEngine>, shutdown: Arc<Notify>) -> io::Result<()> {
    let sd = SecurityDescriptor::from_sddl(PIPE_SDDL)?;
    let mut attrs = SECURITY_ATTRIBUTES { nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32, lpSecurityDescriptor: sd.0, bInheritHandle: 0 };

    let dog = Watchdog::new();
    tokio::spawn(dog.clone().run(engine.clone(), Duration::from_secs(WATCHDOG_SECONDS)));

    let mut first = true;
    loop {
        // SAFETY: `attrs` points at a valid descriptor that lives for the whole loop.
        let server = unsafe {
            ServerOptions::new()
                .first_pipe_instance(first)
                .reject_remote_clients(true)
                .create_with_security_attributes_raw(PIPE_NAME, &mut attrs as *mut _ as *mut c_void)?
        };
        first = false;
        tokio::select! {
            r = server.connect() => r?,
            _ = shutdown.notified() => return Ok(()),
        }
        match client_image_path(server.as_raw_handle() as HANDLE) {
            Ok(p) if is_our_app(&p) => {}
            other => {
                tracing::warn!("rejected a pipe client: {:?}", other.map(|p| p.display().to_string()));
                continue; // dropping `server` closes the connection
            }
        }
        let (engine, dog) = (engine.clone(), dog.clone());
        tokio::spawn(async move {
            dog.feed();
            if let Err(e) = serve_connection(server, engine, move || dog.feed()).await {
                tracing::debug!("connection ended: {e}");
            }
        });
    }
}

fn init_logging() {
    let dir = work_dir();
    let _ = std::fs::create_dir_all(&dir);
    if let Ok(file) = std::fs::OpenOptions::new().create(true).append(true).open(dir.join("service.log")) {
        let _ = tracing_subscriber::fmt().with_ansi(false).with_writer(std::sync::Mutex::new(file)).try_init();
    }
}

define_windows_service!(ffi_service_main, service_main);

pub fn run_as_service() -> Result<(), windows_service::Error> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main)
}

fn service_main(_args: Vec<OsString>) {
    init_logging();
    if let Err(e) = run_service() {
        tracing::error!("service failed: {e}");
    }
}

fn run_service() -> Result<(), Box<dyn std::error::Error>> {
    let shutdown = Arc::new(Notify::new());
    let stop = shutdown.clone();
    let handler = move |event| match event {
        ServiceControl::Stop | ServiceControl::Shutdown => {
            stop.notify_one();
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        _ => ServiceControlHandlerResult::NotImplemented,
    };
    let status_handle = service_control_handler::register(SERVICE_NAME, handler)?;
    let status = |state, accept, wait: u32| ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: state,
        controls_accepted: accept,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::from_secs(wait as u64),
        process_id: None,
    };
    status_handle.set_service_status(status(ServiceState::Running, ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN, 0))?;

    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?;
    let engine = make_engine();
    let result = runtime.block_on(async {
        let r = serve(engine.clone(), shutdown).await;
        engine.disconnect().await; // never leave a tunnel behind when the service stops
        r
    });

    status_handle.set_service_status(status(ServiceState::Stopped, ServiceControlAccept::empty(), 0))?;
    Ok(result?)
}

/// `fastgame-service.exe --console`: run in the foreground from an elevated prompt (diagnostics).
pub fn run_console() -> io::Result<()> {
    init_logging();
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build()?;
    runtime.block_on(async {
        let shutdown = Arc::new(Notify::new());
        let stop = shutdown.clone();
        tokio::spawn(async move {
            let _ = tokio::signal::ctrl_c().await;
            stop.notify_one();
        });
        let engine = make_engine();
        let r = serve(engine.clone(), shutdown).await;
        engine.disconnect().await;
        r
    })
}
