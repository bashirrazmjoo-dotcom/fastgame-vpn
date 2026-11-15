//! The id that identifies this PC to the backend (`hardware_id`).
//!
//! Based on the Windows `MachineGuid`, so reinstalling the app on the same Windows keeps the same
//! account (and the free-time quota cannot be reset by reinstalling). Only a SHA-256 of it leaves
//! the machine, mixed with an app-specific constant.

use sha2::{Digest, Sha256};

pub fn hardware_id(machine_guid: Option<&str>, fallback_random: &str) -> String {
    let basis = match machine_guid.map(str::trim).filter(|g| !g.is_empty()) {
        Some(g) => format!("machine:{}", g.to_lowercase()),
        None => format!("random:{fallback_random}"),
    };
    let digest = Sha256::digest(format!("fastgame-windows-v1|{basis}").as_bytes());
    format!("win-{}", &hex::encode(digest)[..40])
}

#[cfg(windows)]
pub fn machine_guid() -> Option<String> {
    use winreg::{enums::*, RegKey};
    RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(r"SOFTWARE\Microsoft\Cryptography", KEY_READ | KEY_WOW64_64KEY)
        .ok()?
        .get_value::<String, _>("MachineGuid")
        .ok()
}

#[cfg(not(windows))]
pub fn machine_guid() -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_per_machine_and_in_the_backends_length_range() {
        let a = hardware_id(Some("AAAA-BBBB"), "r1");
        assert_eq!(a, hardware_id(Some("aaaa-bbbb "), "r2"), "case, spaces and the random fallback do not matter when a MachineGuid exists");
        assert_ne!(a, hardware_id(Some("cccc"), "r1"));
        assert!(a.starts_with("win-") && (8..=128).contains(&a.len()));
        assert!(!a.contains("aaaa"), "the raw guid is never sent");
    }

    #[test]
    fn falls_back_to_the_stored_random_id() {
        assert_eq!(hardware_id(None, "x"), hardware_id(Some(""), "x"));
        assert_ne!(hardware_id(None, "x"), hardware_id(None, "y"));
    }
}
