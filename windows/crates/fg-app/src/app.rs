//! What the page shows and what it can ask for.

use std::sync::{Arc, Mutex};

use fg_core::api::CallError;
use fg_core::backend::Backend;
use fg_core::controller::{ConnectionController, ConnectionState, Phase};
use fg_core::engine::ConnectMode;
use fg_core::model::{Account, AppConfig, ServerInfo};
use serde::Serialize;
use tokio::sync::watch;

use crate::store::FileStore;

pub const SUPPORT_URL: &str = "https://t.me/fastgamevpn_bot";
pub const PRIVACY_URL: &str = "https://fg.razmjou.com/privacy";
pub const VIP_URL_BASE: &str = "https://app.razmjou.com/?page=vip&code=";

/// A link the page may ask the shell to open. The page never passes URLs, only these names.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Link {
    Vip,
    Support,
    Privacy,
    Update,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct AccountView {
    pub user_code: String,
    pub is_vip: bool,
    pub vip_lifetime: bool,
    pub vip_expires_at_ms: Option<i64>,
    pub is_blocked: bool,
    pub remaining_seconds: i64,
    pub resets_at_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ServerView {
    pub id: u32,
    pub kind: String,
    pub name: String,
    pub country_code: String,
    pub country_name_en: String,
    pub country_name_fa: String,
    /// False for server types this version of the app cannot connect to yet.
    pub supported: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Snapshot {
    pub connection: ConnectionState,
    pub account: Option<AccountView>,
    pub servers: Vec<ServerView>,
    pub selected_server_id: Option<u32>,
    pub update_available: bool,
    pub loading: bool,
    /// Set when the account/server list could not be loaded (offline, service down).
    pub load_error: Option<String>,
    pub language: String,
    pub theme: String,
    /// "tun" = the whole system goes through the VPN adapter (default); "proxy" = Windows system proxy.
    pub mode: String,
}

#[derive(Default)]
struct Data {
    config: Option<AppConfig>,
    account: Option<Account>,
    servers: Vec<ServerInfo>,
    selected: Option<u32>,
    loading: bool,
    load_error: Option<String>,
}

pub struct AppCore {
    backend: Arc<dyn Backend>,
    controller: ConnectionController,
    store: Arc<FileStore>,
    version_code: u32,
    /// Windows needs its own version numbers; until the backend returns a per-platform value the
    /// update prompt stays off so the Android build number can never trigger it by mistake.
    update_check_enabled: bool,
    data: Mutex<Data>,
    out: watch::Sender<Snapshot>,
}

impl AppCore {
    pub fn new(backend: Arc<dyn Backend>, controller: ConnectionController, store: Arc<FileStore>, version_code: u32, update_check_enabled: bool) -> Arc<Self> {
        let selected = store.get().last_server_id;
        let initial = Snapshot {
            connection: controller.state(),
            account: None,
            servers: vec![],
            selected_server_id: selected,
            update_available: false,
            loading: true,
            load_error: None,
            language: store.get().language.unwrap_or_else(|| "fa".into()),
            theme: store.get().theme.unwrap_or_else(|| "dark".into()),
            mode: store.get().mode.filter(|m| m == "proxy").unwrap_or_else(|| "tun".into()),
        };
        let (out, _) = watch::channel(initial);
        let core = Arc::new(Self { backend, controller, store, version_code, update_check_enabled, data: Mutex::new(Data { selected, loading: true, ..Default::default() }), out });
        // Forward connection changes into snapshots.
        let weak = Arc::downgrade(&core);
        let mut rx = core.controller.subscribe();
        tokio::spawn(async move {
            while rx.changed().await.is_ok() {
                let Some(core) = weak.upgrade() else { return };
                core.publish();
            }
        });
        core
    }

    pub fn subscribe(&self) -> watch::Receiver<Snapshot> {
        self.out.subscribe()
    }

    pub fn snapshot(&self) -> Snapshot {
        self.out.borrow().clone()
    }

    fn publish(&self) {
        let d = self.data.lock().unwrap();
        let stored = self.store.get();
        let update_available = self.update_check_enabled
            && d.config.as_ref().is_some_and(|c| c.latest_version_code > self.version_code && !c.update_url.trim().is_empty());
        let snap = Snapshot {
            connection: self.controller.state(),
            account: d.account.as_ref().map(|a| AccountView {
                user_code: a.user_code.clone(),
                is_vip: a.is_vip,
                vip_lifetime: a.vip_lifetime,
                vip_expires_at_ms: a.vip_expires_at,
                is_blocked: a.is_blocked,
                remaining_seconds: a.quota.remaining_seconds,
                resets_at_ms: a.quota.resets_at,
            }),
            servers: d
                .servers
                .iter()
                .map(|s| ServerView {
                    id: s.id,
                    kind: s.kind.clone(),
                    name: s.name.clone(),
                    country_code: s.country_code.clone(),
                    country_name_en: s.country_name_en.clone(),
                    country_name_fa: s.country_name_fa.clone(),
                    supported: s.kind == "xray",
                })
                .collect(),
            selected_server_id: d.selected,
            update_available,
            loading: d.loading,
            load_error: d.load_error.clone(),
            language: stored.language.unwrap_or_else(|| "fa".into()),
            theme: stored.theme.unwrap_or_else(|| "dark".into()),
            mode: stored.mode.filter(|m| m == "proxy").unwrap_or_else(|| "tun".into()),
        };
        drop(d);
        self.out.send_replace(snap);
    }

    /// Loads config, account and servers (registering the device on first use).
    pub async fn refresh(&self) {
        {
            self.data.lock().unwrap().loading = true;
        }
        self.publish();
        let (config, account, servers) = tokio::join!(self.backend.config(), self.backend.account(), self.backend.servers());
        {
            let mut d = self.data.lock().unwrap();
            d.loading = false;
            let mut error = None;
            match config {
                Ok(c) => d.config = Some(c),
                Err(e) => error = Some(describe(&e)),
            }
            match account {
                Ok(a) => d.account = Some(a),
                Err(e) => error = Some(describe(&e)),
            }
            match servers {
                Ok(s) => {
                    // Keep the saved choice if it still exists, else the first connectable server.
                    let keep = d.selected.filter(|id| s.iter().any(|x| x.id == *id && x.kind == "xray"));
                    d.selected = keep.or_else(|| s.iter().find(|x| x.kind == "xray").map(|x| x.id));
                    d.servers = s;
                }
                Err(e) => error = Some(describe(&e)),
            }
            d.load_error = error;
        }
        self.publish();
    }

    pub fn select_server(&self, id: u32) {
        {
            let mut d = self.data.lock().unwrap();
            if !d.servers.iter().any(|s| s.id == id && s.kind == "xray") {
                return;
            }
            d.selected = Some(id);
        }
        self.store.update(|s| s.last_server_id = Some(id));
        self.publish();
    }

    /// Chooses a server. While a connection is running it is moved to the new server:
    /// the old session is closed first (one session per device), then a new one starts.
    pub async fn switch_server(&self, id: u32) {
        let before = self.snapshot();
        self.select_server(id);
        let running = before.connection.phase != Phase::Idle;
        if !running || self.snapshot().selected_server_id == before.selected_server_id {
            return;
        }
        self.restart().await;
    }

    /// Closes the running connection and starts a new one with the current choices.
    async fn restart(&self) {
        self.disconnect();
        let mut rx = self.subscribe();
        let _ = tokio::time::timeout(std::time::Duration::from_secs(20), rx.wait_for(|s| s.connection.phase == Phase::Idle)).await;
        // The state turns Idle just before the session task ends; give it a moment.
        for _ in 0..60 {
            if self.connect() {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    }

    fn mode(&self) -> ConnectMode {
        if self.store.get().mode.as_deref() == Some("proxy") { ConnectMode::Proxy } else { ConnectMode::Tun }
    }

    /// "tun" or "proxy". While connected, the connection restarts in the new mode.
    pub async fn set_mode(&self, mode: &str) {
        if !matches!(mode, "tun" | "proxy") || self.snapshot().mode == mode {
            return;
        }
        let running = self.snapshot().connection.phase != Phase::Idle;
        self.store.update(|s| s.mode = Some(mode.to_string()));
        self.publish();
        if running {
            self.restart().await;
        }
    }

    /// Returns false when nothing could be started (no server chosen, blocked, already running).
    pub fn connect(&self) -> bool {
        let server = {
            let d = self.data.lock().unwrap();
            d.selected.and_then(|id| d.servers.iter().find(|s| s.id == id).cloned())
        };
        match server {
            Some(s) => self.controller.connect_with(s, self.mode()),
            None => false,
        }
    }

    pub fn disconnect(&self) {
        self.controller.disconnect();
    }

    pub fn dismiss_notice(&self) {
        self.controller.dismiss_notice();
    }

    pub fn set_language(&self, lang: &str) {
        if matches!(lang, "fa" | "en") {
            self.store.update(|s| s.language = Some(lang.to_string()));
            self.publish();
        }
    }

    pub fn set_theme(&self, theme: &str) {
        if matches!(theme, "dark" | "light") {
            self.store.update(|s| s.theme = Some(theme.to_string()));
            self.publish();
        }
    }

    /// The URL for one of the fixed buttons, or `None` if it is not available.
    pub fn link(&self, link: Link) -> Option<String> {
        let d = self.data.lock().unwrap();
        match link {
            Link::Privacy => Some(PRIVACY_URL.into()),
            Link::Support => Some(SUPPORT_URL.into()),
            Link::Vip => {
                let code = d.account.as_ref().map(|a| a.user_code.clone()).or_else(|| self.store.get().user_code)?;
                Some(format!("{VIP_URL_BASE}{code}"))
            }
            Link::Update => d.config.as_ref().map(|c| c.update_url.clone()).filter(|u| u.starts_with("https://")),
        }
    }
}

fn describe(e: &CallError) -> String {
    match e {
        CallError::Api(a) => a.message.clone(),
        CallError::Network(_) => "network".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use fg_core::api::{ApiError, NetworkError};
    use fg_core::engine::{EngineError, EngineState, VpnEngine, VpnProfile};
    use fg_core::model::{Quota, SessionState, SessionStatus, StartedSession};

    struct FakeBackend {
        servers: Vec<ServerInfo>,
        offline: bool,
        latest: u32,
    }

    fn server(id: u32, kind: &str) -> ServerInfo {
        ServerInfo { id, kind: kind.into(), name: format!("S{id}"), country_code: "DE".into(), country_name_en: "Germany".into(), country_name_fa: "آلمان".into() }
    }

    #[async_trait]
    impl Backend for FakeBackend {
        async fn config(&self) -> Result<AppConfig, CallError> {
            if self.offline {
                return Err(CallError::Network(NetworkError("x".into())));
            }
            Ok(AppConfig { heartbeat_interval_seconds: 30, support_url: "https://t.me/other".into(), min_supported_version_code: 1, latest_version_code: self.latest, update_url: "https://example.org/dl".into(), privacy_policy_url: "https://p".into() })
        }
        async fn account(&self) -> Result<Account, CallError> {
            if self.offline {
                return Err(CallError::Network(NetworkError("x".into())));
            }
            Ok(Account { user_code: "ABCD2345".into(), is_vip: false, vip_expires_at: None, vip_lifetime: false, is_blocked: false, quota: Quota { total_seconds: 7200, used_seconds: 0, remaining_seconds: 7200, resets_at: None }, vip_purchase_url: String::new() })
        }
        async fn servers(&self) -> Result<Vec<ServerInfo>, CallError> {
            if self.offline {
                return Err(CallError::Api(ApiError { http_status: 500, code: "x".into(), message: "boom".into(), details: None }));
            }
            Ok(self.servers.clone())
        }
        async fn start_session(&self, _id: u32) -> Result<StartedSession, CallError> {
            Err(CallError::Network(NetworkError("x".into())))
        }
        async fn confirm_connected(&self, _s: &str) -> Result<SessionState, CallError> {
            Ok(SessionState { status: SessionStatus::Active, is_vip: false, remaining_seconds: Some(1), resets_at: None })
        }
        async fn heartbeat(&self, _s: &str) -> Result<SessionState, CallError> {
            unimplemented!()
        }
        async fn stop_session(&self, _s: &str) -> Result<SessionState, CallError> {
            unimplemented!()
        }
    }

    struct NoEngine;
    #[async_trait]
    impl VpnEngine for NoEngine {
        async fn connect(&self, _p: &VpnProfile) -> Result<(), EngineError> {
            Ok(())
        }
        async fn disconnect(&self) {}
        fn state(&self) -> EngineState {
            EngineState::Disconnected
        }
        async fn diagnostics(&self) -> String {
            String::new()
        }
    }

    fn core(servers: Vec<ServerInfo>, offline: bool, latest: u32, update_check: bool, tag: &str) -> Arc<AppCore> {
        let path = std::env::temp_dir().join(format!("fg-app-{}-{tag}", std::process::id())).join("state.json");
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
        let backend: Arc<dyn Backend> = Arc::new(FakeBackend { servers, offline, latest });
        let controller = ConnectionController::new(backend.clone(), Arc::new(NoEngine));
        AppCore::new(backend, controller, Arc::new(FileStore::open(path)), 1, update_check)
    }

    #[tokio::test(start_paused = true)]
    async fn switching_server_while_running_reconnects_to_the_new_one() {
        let c = core(vec![server(7, "xray"), server(9, "xray")], false, 0, true, "switch");
        c.refresh().await;
        assert!(c.connect());
        tokio::time::sleep(std::time::Duration::from_millis(10)).await; // the snapshot follows the controller asynchronously
        assert_eq!(c.snapshot().connection.server_id, Some(7));
        c.switch_server(9).await;
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        let s = c.snapshot();
        assert_eq!(s.selected_server_id, Some(9));
        assert_eq!(s.connection.server_id, Some(9));
        assert_ne!(s.connection.phase, Phase::Idle, "a new connection is running");
        // Choosing the same server again changes nothing.
        c.switch_server(9).await;
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        assert_eq!(c.snapshot().connection.server_id, Some(9));
    }

    #[tokio::test]
    async fn mode_defaults_to_whole_system_and_is_validated() {
        let c = core(vec![server(7, "xray")], false, 0, true, "mode");
        assert_eq!(c.snapshot().mode, "tun");
        c.set_mode("socks").await;
        assert_eq!(c.snapshot().mode, "tun");
        c.set_mode("proxy").await;
        assert_eq!(c.snapshot().mode, "proxy");
        c.set_mode("tun").await;
        assert_eq!(c.snapshot().mode, "tun");
    }

    #[tokio::test(start_paused = true)]
    async fn proxy_mode_connection_reports_its_port_and_changing_mode_reconnects() {
        let c = core(vec![server(7, "xray")], false, 0, true, "mode-run");
        c.refresh().await;
        c.set_mode("proxy").await;
        assert!(c.connect());
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        let s = c.snapshot().connection;
        assert_eq!(s.mode, ConnectMode::Proxy);
        assert!(s.proxy_port.is_some_and(|p| p >= 1024));
        c.set_mode("tun").await; // restarts in whole-system mode
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        let s = c.snapshot().connection;
        assert_eq!((s.mode, s.proxy_port), (ConnectMode::Tun, None));
        assert_ne!(s.phase, Phase::Idle);
    }

    #[tokio::test]
    async fn switching_server_while_idle_only_selects() {
        let c = core(vec![server(7, "xray"), server(9, "xray")], false, 0, true, "switch-idle");
        c.refresh().await;
        c.switch_server(9).await;
        assert_eq!(c.snapshot().selected_server_id, Some(9));
        assert_eq!(c.snapshot().connection.phase, Phase::Idle);
    }

    #[tokio::test]
    async fn refresh_picks_the_first_connectable_server_and_marks_others() {
        let c = core(vec![server(5, "openvpn"), server(7, "xray"), server(9, "xray")], false, 0, true, "pick");
        assert!(c.snapshot().loading);
        c.refresh().await;
        let s = c.snapshot();
        assert!(!s.loading && s.load_error.is_none());
        assert_eq!(s.selected_server_id, Some(7));
        assert_eq!(s.servers.iter().map(|x| x.supported).collect::<Vec<_>>(), vec![false, true, true]);
        assert_eq!(s.servers.iter().map(|x| x.id).collect::<Vec<_>>(), vec![5, 7, 9], "backend order (sort order) is kept");
        assert_eq!(s.account.unwrap().remaining_seconds, 7200);
    }

    #[tokio::test]
    async fn selection_is_remembered_and_unsupported_servers_cannot_be_selected() {
        let c = core(vec![server(5, "openvpn"), server(7, "xray"), server(9, "xray")], false, 0, true, "sel");
        c.refresh().await;
        c.select_server(5);
        assert_eq!(c.snapshot().selected_server_id, Some(7));
        c.select_server(9);
        assert_eq!(c.snapshot().selected_server_id, Some(9));
        c.refresh().await;
        assert_eq!(c.snapshot().selected_server_id, Some(9), "refresh keeps the user's choice");
    }

    #[tokio::test]
    async fn offline_reports_an_error_instead_of_failing() {
        let c = core(vec![], true, 0, true, "off");
        c.refresh().await;
        let s = c.snapshot();
        assert!(s.load_error.is_some() && s.account.is_none() && !s.loading);
        assert!(!c.connect(), "nothing to connect to");
    }

    #[tokio::test]
    async fn update_prompt_only_when_enabled_and_newer() {
        let on = core(vec![server(1, "xray")], false, 5, true, "u1");
        on.refresh().await;
        assert!(on.snapshot().update_available);
        let off = core(vec![server(1, "xray")], false, 5, false, "u2");
        off.refresh().await;
        assert!(!off.snapshot().update_available, "Android's version number must not trigger the Windows prompt");
        let same = core(vec![server(1, "xray")], false, 1, true, "u3");
        same.refresh().await;
        assert!(!same.snapshot().update_available);
    }

    #[tokio::test]
    async fn links_are_fixed_and_vip_link_carries_the_user_code() {
        let c = core(vec![server(1, "xray")], false, 0, true, "links");
        assert_eq!(c.link(Link::Vip), None, "no code before the first load");
        c.refresh().await;
        assert_eq!(c.link(Link::Vip).as_deref(), Some("https://app.razmjou.com/?page=vip&code=ABCD2345"));
        assert_eq!(c.link(Link::Support).as_deref(), Some("https://t.me/fastgamevpn_bot"));
        assert_eq!(c.link(Link::Privacy).as_deref(), Some("https://fg.razmjou.com/privacy"));
        assert_eq!(c.link(Link::Update).as_deref(), Some("https://example.org/dl"));
    }

    #[tokio::test]
    async fn language_and_theme_are_validated() {
        let c = core(vec![], false, 0, true, "lang");
        c.set_language("xx");
        c.set_theme("neon");
        assert_eq!((c.snapshot().language.as_str(), c.snapshot().theme.as_str()), ("fa", "dark"));
        c.set_language("en");
        c.set_theme("light");
        assert_eq!((c.snapshot().language.as_str(), c.snapshot().theme.as_str()), ("en", "light"));
    }
}
