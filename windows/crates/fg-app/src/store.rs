//! Small JSON file in the user's profile (`%APPDATA%\FastGame\state.json`): device token, user code and
//! the user's language/theme choice. Nothing in it grants access beyond this device's own account.

use std::path::PathBuf;
use std::sync::Mutex;

use fg_core::backend::Store;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct Stored {
    #[serde(default)]
    pub device_token: Option<String>,
    #[serde(default)]
    pub user_code: Option<String>,
    /// Random id used only when the machine has no `MachineGuid`.
    #[serde(default)]
    pub install_id: Option<String>,
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub theme: Option<String>,
    #[serde(default)]
    pub last_server_id: Option<u32>,
    /// "tun" (whole system, the default) or "proxy" (Windows system proxy).
    #[serde(default)]
    pub mode: Option<String>,
}

pub struct FileStore {
    path: PathBuf,
    data: Mutex<Stored>,
}

impl FileStore {
    pub fn open(path: PathBuf) -> Self {
        let data = std::fs::read(&path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        Self { path, data: Mutex::new(data) }
    }

    pub fn get(&self) -> Stored {
        self.data.lock().unwrap().clone()
    }

    pub fn update(&self, f: impl FnOnce(&mut Stored)) {
        let mut d = self.data.lock().unwrap();
        f(&mut d);
        self.save(&d);
    }

    /// Write to a temp file and rename, so a crash never leaves a half-written file.
    fn save(&self, d: &Stored) {
        if let Some(dir) = self.path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let tmp = self.path.with_extension("json.tmp");
        if std::fs::write(&tmp, serde_json::to_vec_pretty(d).unwrap()).is_ok() {
            let _ = std::fs::rename(&tmp, &self.path);
        }
    }

    pub fn install_id(&self) -> String {
        if let Some(id) = self.get().install_id {
            return id;
        }
        let id = uuid::Uuid::new_v4().to_string();
        self.update(|d| d.install_id = Some(id.clone()));
        id
    }
}

impl Store for FileStore {
    fn device_token(&self) -> Option<String> {
        self.get().device_token
    }
    fn set_device_token(&self, token: Option<String>) {
        self.update(|d| d.device_token = token);
    }
    fn set_user_code(&self, code: &str) {
        self.update(|d| d.user_code = Some(code.to_string()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("fg-store-{}-{}", std::process::id(), name));
        let _ = std::fs::remove_dir_all(&d);
        d.join("state.json")
    }

    #[test]
    fn persists_across_opens_and_creates_the_folder() {
        let p = temp_path("persist");
        let s = FileStore::open(p.clone());
        s.set_device_token(Some("tok".into()));
        s.set_user_code("ABCD2345");
        s.update(|d| d.language = Some("fa".into()));
        let again = FileStore::open(p).get();
        assert_eq!(again.device_token.as_deref(), Some("tok"));
        assert_eq!(again.user_code.as_deref(), Some("ABCD2345"));
        assert_eq!(again.language.as_deref(), Some("fa"));
    }

    #[test]
    fn corrupt_file_starts_fresh_and_install_id_is_stable() {
        let p = temp_path("corrupt");
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(&p, b"{not json").unwrap();
        let s = FileStore::open(p.clone());
        assert_eq!(s.get(), Stored::default());
        let id = s.install_id();
        assert_eq!(id, FileStore::open(p).install_id());
    }

    #[test]
    fn clearing_the_token_is_saved() {
        let p = temp_path("clear");
        let s = FileStore::open(p.clone());
        s.set_device_token(Some("a".into()));
        s.set_device_token(None);
        assert_eq!(FileStore::open(p).device_token(), None);
    }
}
