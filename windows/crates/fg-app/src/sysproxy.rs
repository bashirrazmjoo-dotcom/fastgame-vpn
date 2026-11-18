//! The Windows system proxy, used only in proxy mode.
//!
//! The previous settings are saved to a backup file before ours are written, and put back
//! afterwards. The backup file doubles as the marker "our proxy is currently set", so a stale
//! setting left by a crash can always be undone (at the next start, by the guard process, or by the
//! RunOnce entry after a reboot).
//!
//! Registry access sits behind [`Registry`]; this module holds the logic and is tested with a fake.

use std::path::Path;

use serde::{Deserialize, Serialize};

/// Hosts that never go through the proxy: local names and private networks.
pub const BYPASS: &str = "<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*";

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Saved {
    pub enable: Option<u32>,
    pub server: Option<String>,
    pub bypass: Option<String>,
    /// Automatic configuration script; it would override a manual proxy, so it is paused too.
    pub pac: Option<String>,
}

pub trait Registry {
    fn read(&self) -> std::io::Result<Saved>;
    /// Writes all four values (`None` removes the value) and tells running programs about the change.
    fn write(&self, v: &Saved) -> std::io::Result<()>;
    /// Registers (`Some`) or removes (`None`) the one-shot "restore after next sign-in" command.
    fn set_run_once(&self, command: Option<&str>) -> std::io::Result<()>;
}

pub fn server_for(port: u16) -> String {
    format!("127.0.0.1:{port}")
}

/// True while our proxy settings are in place (a backup exists).
pub fn is_active(backup: &Path) -> bool {
    backup.exists()
}

/// Points Windows at the local proxy. Keeps the first backup if one is already there
/// (re-applying after a reconnect must not save *our own* setting as the "previous" one).
pub fn apply(reg: &dyn Registry, backup: &Path, port: u16, run_once_command: &str) -> std::io::Result<()> {
    if !backup.exists() {
        let before = reg.read()?;
        if let Some(dir) = backup.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(backup, serde_json::to_vec(&before).map_err(std::io::Error::other)?)?;
    }
    reg.set_run_once(Some(run_once_command))?;
    reg.write(&Saved { enable: Some(1), server: Some(server_for(port)), bypass: Some(BYPASS.into()), pac: None })
}

/// Puts the saved settings back. Does nothing when we have not changed anything.
pub fn restore(reg: &dyn Registry, backup: &Path) -> std::io::Result<()> {
    let Ok(bytes) = std::fs::read(backup) else { return Ok(()) };
    // An unreadable backup: switch the proxy off rather than leave a dead one behind.
    let saved: Saved = serde_json::from_slice(&bytes).unwrap_or(Saved { enable: Some(0), ..Default::default() });
    reg.write(&saved)?;
    let _ = reg.set_run_once(None);
    std::fs::remove_file(backup)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[derive(Default)]
    struct Fake {
        v: RefCell<Saved>,
        run_once: RefCell<Option<String>>,
    }

    impl Registry for Fake {
        fn read(&self) -> std::io::Result<Saved> {
            Ok(self.v.borrow().clone())
        }
        fn write(&self, v: &Saved) -> std::io::Result<()> {
            *self.v.borrow_mut() = v.clone();
            Ok(())
        }
        fn set_run_once(&self, c: Option<&str>) -> std::io::Result<()> {
            *self.run_once.borrow_mut() = c.map(String::from);
            Ok(())
        }
    }

    fn backup(name: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("fg-sysproxy-{name}-{}", std::process::id())).join("proxy-backup.json");
        let _ = std::fs::remove_file(&p);
        p
    }

    fn user_settings() -> Saved {
        Saved { enable: Some(1), server: Some("corp.example:3128".into()), bypass: Some("*.corp".into()), pac: Some("http://wpad/x.pac".into()) }
    }

    #[test]
    fn apply_then_restore_gives_back_exactly_the_previous_settings() {
        let (reg, b) = (Fake::default(), backup("roundtrip"));
        *reg.v.borrow_mut() = user_settings();
        apply(&reg, &b, 18080, "cmd").unwrap();
        let on = reg.v.borrow().clone();
        assert_eq!((on.enable, on.server.as_deref(), on.pac), (Some(1), Some("127.0.0.1:18080"), None));
        assert!(on.bypass.unwrap().contains("<local>"));
        assert!(is_active(&b) && reg.run_once.borrow().is_some());
        restore(&reg, &b).unwrap();
        assert_eq!(*reg.v.borrow(), user_settings());
        assert!(!is_active(&b) && reg.run_once.borrow().is_none());
    }

    #[test]
    fn reapplying_keeps_the_original_backup() {
        let (reg, b) = (Fake::default(), backup("reapply"));
        *reg.v.borrow_mut() = user_settings();
        apply(&reg, &b, 18080, "cmd").unwrap();
        apply(&reg, &b, 18081, "cmd").unwrap(); // reconnect
        assert_eq!(reg.v.borrow().server.as_deref(), Some("127.0.0.1:18081"));
        restore(&reg, &b).unwrap();
        assert_eq!(*reg.v.borrow(), user_settings(), "our own setting must never become the 'previous' one");
    }

    #[test]
    fn restore_without_backup_changes_nothing() {
        let (reg, b) = (Fake::default(), backup("none"));
        *reg.v.borrow_mut() = user_settings();
        restore(&reg, &b).unwrap();
        assert_eq!(*reg.v.borrow(), user_settings());
    }

    #[test]
    fn corrupt_backup_switches_the_proxy_off() {
        let (reg, b) = (Fake::default(), backup("corrupt"));
        std::fs::create_dir_all(b.parent().unwrap()).unwrap();
        std::fs::write(&b, b"not json").unwrap();
        *reg.v.borrow_mut() = Saved { enable: Some(1), server: Some("127.0.0.1:1".into()), ..Default::default() };
        restore(&reg, &b).unwrap();
        assert_eq!(reg.v.borrow().enable, Some(0));
        assert!(!b.exists());
    }
}
