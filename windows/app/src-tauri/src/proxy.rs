//! Windows system-proxy plumbing for "proxy mode".
//!
//! The logic (backup, restore, never saving our own setting as the old one) lives in
//! `fg_app::sysproxy` and is tested there. This file only holds the registry access and the
//! processes that make sure the setting is undone: the app itself, a small detached guard that
//! waits for the app to end (also when it is killed), and a RunOnce entry for a reboot.

use std::path::PathBuf;

use fg_app::sysproxy::{self, Registry, Saved};

pub fn backup_path() -> PathBuf {
    crate::data_dir().join("proxy-backup.json")
}

/// Undoes our proxy setting if it is still in place. Safe to call at any time.
pub fn restore_now() {
    if let Err(e) = sysproxy::restore(&WinRegistry, &backup_path()) {
        tracing::warn!("could not restore the Windows proxy: {e}");
    }
}

fn exe_command(args: &str) -> String {
    let exe = std::env::current_exe().map(|p| p.display().to_string()).unwrap_or_default();
    format!("\"{exe}\" {args}")
}

pub fn apply(port: u16) -> std::io::Result<()> {
    sysproxy::apply(&WinRegistry, &backup_path(), port, &exe_command("--restore-proxy"))
}

/// Starts the detached process that restores the proxy when this app's process ends.
pub fn spawn_guard() {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        if let Ok(exe) = std::env::current_exe() {
            let _ = std::process::Command::new(exe)
                .arg("--proxy-guard")
                .arg(std::process::id().to_string())
                .creation_flags(DETACHED_PROCESS | CREATE_NO_WINDOW)
                .spawn();
        }
    }
}

/// Handles `--restore-proxy` and `--proxy-guard <pid>`. `true` means the process should exit now.
pub fn handle_cli(args: &[String]) -> bool {
    match args.get(1).map(String::as_str) {
        Some("--restore-proxy") => {
            restore_now();
            true
        }
        Some("--proxy-guard") => {
            if let Some(pid) = args.get(2).and_then(|p| p.parse::<u32>().ok()) {
                wait_for_exit(pid);
            }
            restore_now();
            true
        }
        _ => false,
    }
}

#[cfg(windows)]
fn wait_for_exit(pid: u32) {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
    unsafe {
        let h = OpenProcess(PROCESS_SYNCHRONIZE, 0, pid);
        if h.is_null() {
            return; // already gone
        }
        WaitForSingleObject(h, INFINITE);
        CloseHandle(h);
    }
}

#[cfg(not(windows))]
fn wait_for_exit(_: u32) {}

pub struct WinRegistry;

#[cfg(windows)]
mod imp {
    use super::*;
    use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
    use winreg::RegKey;

    const SETTINGS: &str = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    const RUN_ONCE: &str = r"Software\Microsoft\Windows\CurrentVersion\RunOnce";
    const RUN_ONCE_NAME: &str = "FastGameRestoreProxy";

    fn settings(write: bool) -> std::io::Result<RegKey> {
        let flags = if write { KEY_READ | KEY_WRITE } else { KEY_READ };
        RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(SETTINGS, flags)
    }

    fn put_str(k: &RegKey, name: &str, v: &Option<String>) -> std::io::Result<()> {
        match v {
            Some(s) => k.set_value(name, s),
            None => match k.delete_value(name) {
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
                r => r,
            },
        }
    }

    fn notify() {
        use windows_sys::Win32::Networking::WinInet::InternetSetOptionW;
        // 39 = settings changed, 37 = refresh: running programs re-read the proxy.
        unsafe {
            InternetSetOptionW(std::ptr::null(), 39, std::ptr::null(), 0);
            InternetSetOptionW(std::ptr::null(), 37, std::ptr::null(), 0);
        }
    }

    impl Registry for WinRegistry {
        fn read(&self) -> std::io::Result<Saved> {
            let k = settings(false)?;
            Ok(Saved {
                enable: k.get_value::<u32, _>("ProxyEnable").ok(),
                server: k.get_value::<String, _>("ProxyServer").ok(),
                bypass: k.get_value::<String, _>("ProxyOverride").ok(),
                pac: k.get_value::<String, _>("AutoConfigURL").ok(),
            })
        }

        fn write(&self, v: &Saved) -> std::io::Result<()> {
            let k = settings(true)?;
            match v.enable {
                Some(n) => k.set_value("ProxyEnable", &n)?,
                None => {
                    let _ = k.delete_value("ProxyEnable");
                }
            }
            put_str(&k, "ProxyServer", &v.server)?;
            put_str(&k, "ProxyOverride", &v.bypass)?;
            put_str(&k, "AutoConfigURL", &v.pac)?;
            notify();
            Ok(())
        }

        fn set_run_once(&self, command: Option<&str>) -> std::io::Result<()> {
            let (k, _) = RegKey::predef(HKEY_CURRENT_USER).create_subkey(RUN_ONCE)?;
            match command {
                Some(c) => k.set_value(RUN_ONCE_NAME, &c),
                None => match k.delete_value(RUN_ONCE_NAME) {
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
                    r => r,
                },
            }
        }
    }
}

#[cfg(not(windows))]
impl Registry for WinRegistry {
    fn read(&self) -> std::io::Result<Saved> {
        Ok(Saved::default())
    }
    fn write(&self, _: &Saved) -> std::io::Result<()> {
        Ok(())
    }
    fn set_run_once(&self, _: Option<&str>) -> std::io::Result<()> {
        Ok(())
    }
}
