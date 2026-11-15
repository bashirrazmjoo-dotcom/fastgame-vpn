#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
mod pipe;

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use fg_app::app::{AppCore, Link, Snapshot};
use fg_app::machine;
use fg_app::store::FileStore;
use fg_core::api::{FallbackRoute, HttpApi};
use fg_core::backend::BackendImpl;
use fg_core::controller::{ConnectionController, Phase};
use fg_core::engine::VpnEngine;
use fg_core::ipc::PipeEngine;
use tauri::{Emitter, Manager, State, WindowEvent};
use tauri_plugin_opener::OpenerExt;

struct Core(Arc<AppCore>);

#[tauri::command]
async fn snapshot(core: State<'_, Core>) -> Result<Snapshot, ()> {
    Ok(core.0.snapshot())
}
#[tauri::command]
async fn refresh(core: State<'_, Core>) -> Result<(), ()> {
    core.0.refresh().await;
    Ok(())
}
#[tauri::command]
async fn connect(core: State<'_, Core>) -> Result<bool, ()> {
    Ok(core.0.connect())
}
#[tauri::command]
async fn disconnect(core: State<'_, Core>) -> Result<(), ()> {
    core.0.disconnect();
    Ok(())
}
#[tauri::command]
async fn dismiss_notice(core: State<'_, Core>) -> Result<(), ()> {
    core.0.dismiss_notice();
    Ok(())
}
#[tauri::command]
async fn select_server(core: State<'_, Core>, id: u32) -> Result<(), ()> {
    core.0.select_server(id);
    Ok(())
}
#[tauri::command]
async fn set_language(core: State<'_, Core>, lang: String) -> Result<(), ()> {
    core.0.set_language(&lang);
    Ok(())
}
#[tauri::command]
async fn set_theme(core: State<'_, Core>, theme: String) -> Result<(), ()> {
    core.0.set_theme(&theme);
    Ok(())
}
/// The page names a link; it never supplies a URL.
#[tauri::command]
async fn open_link(app: tauri::AppHandle, core: State<'_, Core>, link: String) -> Result<(), ()> {
    let link = match link.as_str() {
        "vip" => Link::Vip,
        "support" => Link::Support,
        "privacy" => Link::Privacy,
        "update" => Link::Update,
        _ => return Err(()),
    };
    if let Some(url) = core.0.link(link) {
        let _ = app.opener().open_url(url, None::<&str>);
    }
    Ok(())
}

fn data_dir() -> PathBuf {
    std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(std::env::temp_dir).join("FastGame")
}

fn build_core() -> Result<Arc<AppCore>, String> {
    let store = Arc::new(FileStore::open(data_dir().join("state.json")));
    let hardware_id = machine::hardware_id(machine::machine_guid().as_deref(), &store.install_id());
    let api = HttpApi::new(
        config::API_BASE_URL,
        Some(FallbackRoute {
            base_url: config::API_FALLBACK_URL.into(),
            host_header: config::API_FALLBACK_HOST.into(),
            cert_sha256_hex: config::API_FALLBACK_CERT_SHA256.into(),
        }),
    )
    .map_err(|e| e.to_string())?;
    let locale = store.get().language.unwrap_or_else(|| "fa".into());
    let backend = Arc::new(BackendImpl::new(api, store.clone(), hardware_id, config::version_code(), locale));
    let engine: Arc<dyn VpnEngine> = Arc::new(PipeEngine::new(Arc::new(pipe::PipeConnector)));
    let controller = ConnectionController::new(backend.clone(), engine);
    // Update prompt stays off until the backend has a per-platform version (see README).
    Ok(AppCore::new(backend, controller, store, config::version_code(), false))
}

/// The release build has no console, so a startup failure would otherwise be silent.
/// Write it to %APPDATA%\\FastGame\\crash.log and show it in a message box.
fn install_crash_report() {
    std::panic::set_hook(Box::new(|info| {
        let text = format!("{info}\n\n{}", std::backtrace::Backtrace::force_capture());
        let dir = data_dir();
        let _ = std::fs::create_dir_all(&dir);
        let _ = std::fs::write(dir.join("crash.log"), &text);
        #[cfg(windows)]
        {
            use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
            let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
            let short: String = format!("{info}").chars().take(600).collect();
            let msg = format!("FastGame could not start.\n\n{short}\n\nDetails: {}", dir.join("crash.log").display());
            unsafe { MessageBoxW(std::ptr::null_mut(), wide(&msg).as_ptr(), wide("FastGame").as_ptr(), MB_OK | MB_ICONERROR) };
        }
    }));
}

fn main() {
    install_crash_report();
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let core = tauri::async_runtime::block_on(async { build_core() })?;
            let mut rx = core.subscribe();
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                while rx.changed().await.is_ok() {
                    let snap = rx.borrow_and_update().clone();
                    let _ = handle.emit("snapshot", snap);
                }
            });
            app.manage(Core(core));
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window while connected: disconnect first so the tunnel and the session end cleanly.
            if let WindowEvent::CloseRequested { api, .. } = event {
                let core = window.state::<Core>().0.clone();
                if core.snapshot().connection.phase == Phase::Idle {
                    return;
                }
                api.prevent_close();
                let app = window.app_handle().clone();
                tauri::async_runtime::spawn(async move {
                    core.disconnect();
                    let mut rx = core.subscribe();
                    let _ = tokio::time::timeout(Duration::from_secs(8), rx.wait_for(|s| s.connection.phase == Phase::Idle)).await;
                    app.exit(0);
                });
            }
        })
        .invoke_handler(tauri::generate_handler![
            snapshot, refresh, connect, disconnect, dismiss_notice, select_server, set_language, set_theme, open_link
        ])
        .run(tauri::generate_context!())
        .expect("error while running FastGame");
}
