//! Runs one VPN session end to end. Port of `ConnectionController.kt` of the Android app:
//!
//! 1. ask the server to start a session (it checks block state and quota and hands out the profile)
//! 2. bring up the tunnel through the [`VpnEngine`]
//! 3. tell the server the tunnel is up (charging of connected time starts here)
//! 4. heartbeat; the server is the only authority on time
//! 5. on any end (user, quota, block, network, engine drop) tear down the tunnel and close the session
//!
//! While the user has not pressed disconnect, the controller reconnects with back-off. It only stops on
//! "quota exhausted", "blocked", or after repeated failures that are not plain network loss.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio::time::{sleep, timeout, Instant};
use tokio_util::sync::CancellationToken;

use crate::api::{ApiError, CallError};
use crate::backend::Backend;
use crate::engine::{ConnectMode, EngineState, VpnEngine, VpnProfile};
use crate::model::{parse_iso_millis, ServerInfo, SessionState, SessionStatus};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Idle,
    Starting,
    Connecting,
    Connected,
    Disconnecting,
}

/// Why a connection ended or could not start. Shown once to the user.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Notice {
    QuotaExhausted { resets_at_ms: Option<i64> },
    Blocked,
    SessionEnded,
    ConnectionLost,
    NetworkLost,
    ServerUnavailable,
    EngineFailed { reason: String },
    ServiceError { message: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConnectionState {
    pub phase: Phase,
    pub server_id: Option<u32>,
    pub is_vip: bool,
    /// Seconds of free time left; `None` when unlimited (VIP) or unknown.
    pub remaining_seconds: Option<i64>,
    pub notice: Option<Notice>,
    /// How this connection was started, and the local proxy port when that is proxy mode.
    #[serde(default)]
    pub mode: ConnectMode,
    #[serde(default)]
    pub proxy_port: Option<u16>,
}

impl ConnectionState {
    pub fn idle() -> Self {
        Self { phase: Phase::Idle, server_id: None, is_vip: false, remaining_seconds: None, notice: None, mode: ConnectMode::Tun, proxy_port: None }
    }
}

const CONNECT_TIMEOUT: Duration = Duration::from_secs(90);
const NETWORK_GRACE: Duration = Duration::from_secs(180);
const MIN_HEARTBEAT_SECONDS: u32 = 5;
const MAX_FAILED_ATTEMPTS: u32 = 6;
const MAX_ENGINE_FAILURES: u32 = 2;

/// A currently free loopback port (the OS picks it; it is released again right away).
fn free_loopback_port() -> Option<u16> {
    std::net::TcpListener::bind(("127.0.0.1", 0)).ok().and_then(|l| l.local_addr().ok()).map(|a| a.port()).filter(|p| *p >= 1024)
}

fn backoff(failures: u32) -> Duration {
    Duration::from_millis(match failures {
        0 => 1_500,
        1 => 3_000,
        2 => 6_000,
        3 => 12_000,
        _ => 25_000,
    })
}

struct Anchor {
    remaining: Option<i64>,
    at: Instant,
}

struct Inner {
    backend: Arc<dyn Backend>,
    engine: Arc<dyn VpnEngine>,
    state: watch::Sender<ConnectionState>,
    anchor: Mutex<Anchor>,
}

pub struct ConnectionController {
    inner: Arc<Inner>,
    run: Mutex<Option<(JoinHandle<()>, CancellationToken)>>,
}

struct Outcome {
    notice: Notice,
    connected: bool,
}

struct Cancelled;

impl ConnectionController {
    pub fn new(backend: Arc<dyn Backend>, engine: Arc<dyn VpnEngine>) -> Self {
        let (state, _) = watch::channel(ConnectionState::idle());
        Self {
            inner: Arc::new(Inner { backend, engine, state, anchor: Mutex::new(Anchor { remaining: None, at: Instant::now() }) }),
            run: Mutex::new(None),
        }
    }

    pub fn subscribe(&self) -> watch::Receiver<ConnectionState> {
        self.inner.state.subscribe()
    }

    pub fn state(&self) -> ConnectionState {
        self.inner.state.borrow().clone()
    }

    /// Returns false if a session is already running. Whole-system mode.
    pub fn connect(&self, server: ServerInfo) -> bool {
        self.connect_with(server, ConnectMode::Tun)
    }

    /// Like `connect`, with the chosen mode. In proxy mode a free loopback port is picked here and
    /// kept for every retry of this connection, so the system proxy setting stays valid.
    pub fn connect_with(&self, server: ServerInfo, mode: ConnectMode) -> bool {
        let mut run = self.run.lock().unwrap();
        if matches!(&*run, Some((h, _)) if !h.is_finished()) {
            return false;
        }
        let proxy_port = if mode == ConnectMode::Proxy { free_loopback_port() } else { None };
        if mode == ConnectMode::Proxy && proxy_port.is_none() {
            self.inner.state.send_replace(ConnectionState { notice: Some(Notice::EngineFailed { reason: "No free local port for the proxy.".into() }), ..ConnectionState::idle() });
            return true;
        }
        self.inner.state.send_replace(ConnectionState { phase: Phase::Starting, server_id: Some(server.id), mode, proxy_port, ..ConnectionState::idle() });
        let cancel = CancellationToken::new();
        let inner = self.inner.clone();
        let token = cancel.clone();
        let handle = tokio::spawn(async move { inner.run_session(server, token, mode, proxy_port).await });
        *run = Some((handle, cancel));
        true
    }

    /// Returns immediately; the state becomes `Idle` once the tunnel is down and the session closed.
    pub fn disconnect(&self) {
        let run = self.run.lock().unwrap();
        if let Some((h, cancel)) = &*run {
            if !h.is_finished() {
                self.inner.state.send_modify(|s| s.phase = Phase::Disconnecting);
                cancel.cancel();
            }
        }
    }

    pub fn dismiss_notice(&self) {
        self.inner.state.send_modify(|s| {
            if s.phase == Phase::Idle {
                s.notice = None;
            }
        });
    }
}

impl Inner {
    async fn run_session(self: Arc<Self>, server: ServerInfo, cancel: CancellationToken, mode: ConnectMode, proxy_port: Option<u16>) {
        let mut failures = 0u32;
        let mut final_notice = None;
        loop {
            let Ok(outcome) = self.run_once(&server, &cancel, mode, proxy_port).await else { break };
            let notice = outcome.notice;
            if matches!(notice, Notice::QuotaExhausted { .. } | Notice::Blocked) {
                final_notice = Some(notice);
                break;
            }
            failures = if outcome.connected { 0 } else { failures + 1 };
            let offline = matches!(notice, Notice::NetworkLost);
            let limit = if matches!(notice, Notice::EngineFailed { .. } | Notice::ServiceError { .. }) { MAX_ENGINE_FAILURES } else { MAX_FAILED_ATTEMPTS };
            if !offline && failures >= limit {
                final_notice = Some(notice);
                break;
            }
            self.state.send_modify(|s| {
                s.phase = Phase::Starting;
                s.notice = None;
            });
            tokio::select! {
                _ = sleep(backoff(failures)) => {}
                _ = cancel.cancelled() => break,
            }
        }
        self.state.send_replace(ConnectionState { server_id: Some(server.id), notice: final_notice, ..ConnectionState::idle() });
    }

    /// One session: start, tunnel, heartbeat. `Err(Cancelled)` means the user pressed disconnect.
    /// Cleanup always runs, also on cancellation, and is never interrupted.
    async fn run_once(&self, server: &ServerInfo, cancel: &CancellationToken, mode: ConnectMode, proxy_port: Option<u16>) -> Result<Outcome, Cancelled> {
        let session_id: Mutex<Option<String>> = Mutex::new(None);
        let connected = Mutex::new(false);

        let result = tokio::select! {
            _ = cancel.cancelled() => Err(Cancelled),
            notice = self.attempt(server, &session_id, &connected, mode, proxy_port) => Ok(notice),
        };

        self.engine.disconnect().await;
        let id = session_id.lock().unwrap().take();
        if let Some(id) = id {
            let _ = self.backend.stop_session(&id).await; // best effort; the server also closes stale sessions
        }
        self.anchor(false, None);
        let connected = *connected.lock().unwrap();
        result.map(|notice| Outcome { notice, connected })
    }

    async fn attempt(&self, server: &ServerInfo, session_id: &Mutex<Option<String>>, connected: &Mutex<bool>, mode: ConnectMode, proxy_port: Option<u16>) -> Notice {
        let started = match self.backend.start_session(server.id).await {
            Ok(s) => s,
            Err(e) => return notice_for_call(e),
        };
        *session_id.lock().unwrap() = Some(started.session_id.clone());
        self.anchor(started.is_vip, started.remaining_seconds);
        self.state.send_modify(|s| s.phase = Phase::Connecting);

        let profile = VpnProfile::from_session(&server.name, &started).with_mode(mode, proxy_port);
        match timeout(CONNECT_TIMEOUT, self.engine.connect(&profile)).await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Notice::EngineFailed { reason: e.0 },
            Err(_) => {
                let diag = self.engine.diagnostics().await;
                let reason = if diag.trim().is_empty() { "Timed out while connecting.".to_string() } else { format!("Timed out while connecting.\n{diag}") };
                return Notice::EngineFailed { reason };
            }
        }

        match self.backend.confirm_connected(&started.session_id).await {
            Err(e) => notice_for_call(e),
            Ok(first) => match self.evaluate(&first) {
                Some(notice) => notice,
                None => {
                    *connected.lock().unwrap() = true;
                    self.state.send_modify(|s| s.phase = Phase::Connected);
                    self.monitor(&started.session_id, started.heartbeat_interval_seconds).await
                }
            },
        }
    }

    /// Heartbeat loop. Returns the reason the session has to end (never returns normally).
    async fn monitor(&self, session_id: &str, heartbeat_seconds: u32) -> Notice {
        let interval = heartbeat_seconds.max(MIN_HEARTBEAT_SECONDS);
        let mut since_beat = 0u32;
        let mut last_success = Instant::now();
        loop {
            sleep(Duration::from_secs(1)).await;
            since_beat += 1;

            match self.engine.state() {
                EngineState::Failed(reason) => return Notice::EngineFailed { reason },
                EngineState::Disconnected => return Notice::ConnectionLost,
                _ => {}
            }

            let left = self.tick();
            let exhausted_locally = matches!(left, Some(l) if l <= 0);
            if since_beat < interval && !exhausted_locally {
                continue;
            }
            since_beat = 0;
            match self.backend.heartbeat(session_id).await {
                Ok(result) => {
                    last_success = Instant::now();
                    if let Some(notice) = self.evaluate(&result) {
                        return notice;
                    }
                }
                Err(CallError::Api(e)) => return notice_for_api(&e),
                Err(CallError::Network(_)) => {
                    if last_success.elapsed() > NETWORK_GRACE {
                        return Notice::NetworkLost;
                    }
                }
            }
        }
    }

    /// `None` means the session is fine and the local countdown was re-anchored on the server value.
    fn evaluate(&self, r: &SessionState) -> Option<Notice> {
        match r.status {
            SessionStatus::Active => {
                self.anchor(r.is_vip, r.remaining_seconds);
                None
            }
            SessionStatus::QuotaExhausted => Some(Notice::QuotaExhausted { resets_at_ms: r.resets_at }),
            SessionStatus::Closed => Some(Notice::SessionEnded),
        }
    }

    fn anchor(&self, is_vip: bool, remaining: Option<i64>) {
        let remaining = if is_vip { None } else { remaining };
        *self.anchor.lock().unwrap() = Anchor { remaining, at: Instant::now() };
        self.state.send_modify(|s| {
            s.is_vip = is_vip;
            s.remaining_seconds = remaining;
        });
    }

    fn tick(&self) -> Option<i64> {
        let a = self.anchor.lock().unwrap();
        let base = a.remaining?;
        let left = (base - a.at.elapsed().as_secs() as i64).max(0);
        drop(a);
        self.state.send_modify(|s| s.remaining_seconds = Some(left));
        Some(left)
    }
}

fn notice_for_call(e: CallError) -> Notice {
    match e {
        CallError::Api(e) => notice_for_api(&e),
        CallError::Network(_) => Notice::NetworkLost,
    }
}

fn notice_for_api(e: &ApiError) -> Notice {
    match e.code.as_str() {
        "quota_exhausted" => Notice::QuotaExhausted {
            resets_at_ms: e.details.as_ref().and_then(|d| d.get("resets_at")).and_then(|v| v.as_str()).and_then(parse_iso_millis),
        },
        "device_blocked" => Notice::Blocked,
        "session_closed" => Notice::SessionEnded,
        "server_unavailable" => Notice::ServerUnavailable,
        _ => Notice::ServiceError { message: e.message.clone() },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::NetworkError;
    use crate::engine::EngineError;
    use crate::model::{Account, AppConfig, StartedSession};
    use async_trait::async_trait;
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicU32, Ordering};

    type Reply<T> = Result<T, CallError>;

    fn api_err(code: &str) -> CallError {
        CallError::Api(ApiError { http_status: 403, code: code.into(), message: code.into(), details: None })
    }
    fn net_err() -> CallError {
        CallError::Network(NetworkError("offline".into()))
    }
    fn state(status: SessionStatus, remaining: Option<i64>) -> SessionState {
        SessionState { status, is_vip: remaining.is_none(), remaining_seconds: remaining, resets_at: None }
    }
    fn started(n: u32, remaining: Option<i64>) -> StartedSession {
        StartedSession {
            session_id: format!("s{n}"),
            server_id: 1,
            heartbeat_interval_seconds: 30,
            is_vip: remaining.is_none(),
            remaining_seconds: remaining,
            resets_at: None,
            kind: "xray".into(),
            ovpn_config: String::new(),
            xray_link: Some("vless://x".into()),
            auth_username: None,
            auth_password: None,
        }
    }

    #[derive(Default)]
    struct FakeBackend {
        starts: AtomicU32,
        stops: Mutex<Vec<String>>,
        start_replies: Mutex<VecDeque<Reply<StartedSession>>>,
        heartbeats: Mutex<VecDeque<Reply<SessionState>>>,
        heartbeat_count: AtomicU32,
        vip: std::sync::atomic::AtomicBool,
    }

    #[async_trait]
    impl Backend for FakeBackend {
        async fn config(&self) -> Reply<AppConfig> {
            unimplemented!()
        }
        async fn account(&self) -> Reply<Account> {
            unimplemented!()
        }
        async fn servers(&self) -> Reply<Vec<ServerInfo>> {
            unimplemented!()
        }
        async fn start_session(&self, _id: u32) -> Reply<StartedSession> {
            let n = self.starts.fetch_add(1, Ordering::SeqCst) + 1;
            self.start_replies.lock().unwrap().pop_front().unwrap_or_else(|| Ok(started(n, Some(7200))))
        }
        async fn confirm_connected(&self, _s: &str) -> Reply<SessionState> {
            Ok(state(SessionStatus::Active, if self.vip.load(Ordering::SeqCst) { None } else { Some(7200) }))
        }
        async fn heartbeat(&self, _s: &str) -> Reply<SessionState> {
            self.heartbeat_count.fetch_add(1, Ordering::SeqCst);
            let remaining = if self.vip.load(Ordering::SeqCst) { None } else { Some(7000) };
            self.heartbeats.lock().unwrap().pop_front().unwrap_or_else(|| Ok(state(SessionStatus::Active, remaining)))
        }
        async fn stop_session(&self, s: &str) -> Reply<SessionState> {
            self.stops.lock().unwrap().push(s.to_string());
            Ok(state(SessionStatus::Closed, Some(0)))
        }
    }

    struct FakeEngine {
        st: Mutex<EngineState>,
        connects: AtomicU32,
        disconnects: AtomicU32,
        connect_results: Mutex<VecDeque<Result<(), EngineError>>>,
        hang: bool,
    }
    impl FakeEngine {
        fn new() -> Self {
            Self { st: Mutex::new(EngineState::Disconnected), connects: 0.into(), disconnects: 0.into(), connect_results: Default::default(), hang: false }
        }
        fn set(&self, s: EngineState) {
            *self.st.lock().unwrap() = s;
        }
    }
    #[async_trait]
    impl VpnEngine for FakeEngine {
        async fn connect(&self, _p: &VpnProfile) -> Result<(), EngineError> {
            self.connects.fetch_add(1, Ordering::SeqCst);
            if self.hang {
                std::future::pending::<()>().await;
            }
            let r = self.connect_results.lock().unwrap().pop_front().unwrap_or(Ok(()));
            if r.is_ok() {
                self.set(EngineState::Connected);
            }
            r
        }
        async fn disconnect(&self) {
            self.disconnects.fetch_add(1, Ordering::SeqCst);
            self.set(EngineState::Disconnected);
        }
        fn state(&self) -> EngineState {
            self.st.lock().unwrap().clone()
        }
        async fn diagnostics(&self) -> String {
            "engine log".into()
        }
    }

    fn server() -> ServerInfo {
        ServerInfo { id: 1, kind: "xray".into(), name: "DE".into(), country_code: "DE".into(), country_name_en: "Germany".into(), country_name_fa: "آلمان".into() }
    }

    fn setup(engine: FakeEngine) -> (Arc<FakeBackend>, Arc<FakeEngine>, ConnectionController) {
        let b = Arc::new(FakeBackend::default());
        let e = Arc::new(engine);
        let c = ConnectionController::new(b.clone(), e.clone());
        (b, e, c)
    }

    async fn wait_phase(c: &ConnectionController, want: Phase) {
        let mut rx = c.subscribe();
        rx.wait_for(|s| s.phase == want).await.unwrap();
    }

    #[tokio::test(start_paused = true)]
    async fn connects_beats_and_disconnects_cleanly() {
        let (b, e, c) = setup(FakeEngine::new());
        assert!(c.connect(server()));
        assert!(!c.connect(server()), "only one session at a time");
        wait_phase(&c, Phase::Connected).await;
        assert_eq!(c.state().remaining_seconds, Some(7200));

        sleep(Duration::from_millis(95_500)).await;
        assert_eq!(b.heartbeat_count.load(Ordering::SeqCst), 3); // 30, 60, 90
        assert_eq!(c.state().remaining_seconds, Some(7000 - 5)); // anchored on server value, counts down locally

        c.disconnect();
        wait_phase(&c, Phase::Idle).await;
        assert_eq!(*b.stops.lock().unwrap(), vec!["s1"]);
        assert_eq!(e.disconnects.load(Ordering::SeqCst), 1);
        assert_eq!(c.state().notice, None, "a user disconnect is not an error");
        assert_eq!(b.starts.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn vip_has_no_countdown() {
        let (b, _e, c) = setup(FakeEngine::new());
        b.vip.store(true, Ordering::SeqCst);
        b.start_replies.lock().unwrap().push_back(Ok(started(1, None)));
        c.connect(server());
        wait_phase(&c, Phase::Connected).await;
        sleep(Duration::from_secs(65)).await; // two heartbeats later it is still unlimited
        assert!(c.state().is_vip);
        assert_eq!(c.state().remaining_seconds, None);
        c.disconnect();
    }

    #[tokio::test(start_paused = true)]
    async fn server_ending_the_session_for_quota_stops_without_retry() {
        let (b, e, c) = setup(FakeEngine::new());
        b.heartbeats.lock().unwrap().push_back(Ok(state(SessionStatus::QuotaExhausted, Some(0))));
        c.connect(server());
        wait_phase(&c, Phase::Connected).await;
        wait_phase(&c, Phase::Idle).await;
        assert!(matches!(c.state().notice, Some(Notice::QuotaExhausted { .. })));
        assert_eq!(b.starts.load(Ordering::SeqCst), 1, "no reconnect after quota");
        assert_eq!(e.disconnects.load(Ordering::SeqCst), 1);
        assert_eq!(*b.stops.lock().unwrap(), vec!["s1"]);
    }

    #[tokio::test(start_paused = true)]
    async fn blocked_device_stops_immediately() {
        let (b, e, c) = setup(FakeEngine::new());
        b.start_replies.lock().unwrap().push_back(Err(api_err("device_blocked")));
        c.connect(server());
        wait_phase(&c, Phase::Idle).await;
        assert_eq!(c.state().notice, Some(Notice::Blocked));
        assert_eq!(e.connects.load(Ordering::SeqCst), 0);
    }

    #[tokio::test(start_paused = true)]
    async fn tunnel_drop_reconnects_with_a_new_session() {
        let (b, e, c) = setup(FakeEngine::new());
        c.connect(server());
        wait_phase(&c, Phase::Connected).await;
        e.set(EngineState::Disconnected); // tunnel died
        let mut rx = c.subscribe();
        rx.wait_for(|s| s.phase == Phase::Starting).await.unwrap();
        // The new session comes up after the back-off.
        sleep(Duration::from_secs(5)).await;
        wait_phase(&c, Phase::Connected).await;
        assert_eq!(b.starts.load(Ordering::SeqCst), 2);
        assert_eq!(b.stops.lock().unwrap()[0], "s1", "old session closed before starting a new one");
        assert_eq!(c.state().notice, None);
        c.disconnect();
    }

    #[tokio::test(start_paused = true)]
    async fn repeated_engine_failures_give_up_with_a_notice() {
        let engine = FakeEngine::new();
        for _ in 0..5 {
            engine.connect_results.lock().unwrap().push_back(Err(EngineError("wintun missing".into())));
        }
        let (b, _e, c) = setup(engine);
        c.connect(server());
        wait_phase(&c, Phase::Idle).await;
        assert_eq!(c.state().notice, Some(Notice::EngineFailed { reason: "wintun missing".into() }));
        assert_eq!(b.starts.load(Ordering::SeqCst), 2);
    }

    #[tokio::test(start_paused = true)]
    async fn engine_connect_timeout_reports_diagnostics() {
        let mut engine = FakeEngine::new();
        engine.hang = true;
        let (_b, _e, c) = setup(engine);
        c.connect(server());
        let mut rx = c.subscribe();
        rx.wait_for(|s| s.phase == Phase::Starting).await.unwrap(); // after the first timed-out attempt
        c.disconnect();
        wait_phase(&c, Phase::Idle).await;
    }

    #[tokio::test(start_paused = true)]
    async fn short_network_loss_is_tolerated_long_loss_retries_forever() {
        let (b, _e, c) = setup(FakeEngine::new());
        {
            let mut hb = b.heartbeats.lock().unwrap();
            for _ in 0..3 {
                hb.push_back(Err(net_err())); // 90 s offline: inside the 180 s grace
            }
        }
        c.connect(server());
        wait_phase(&c, Phase::Connected).await;
        sleep(Duration::from_secs(150)).await;
        assert_eq!(c.state().phase, Phase::Connected, "still connected after a short outage");
        assert_eq!(b.starts.load(Ordering::SeqCst), 1);

        // Now the network goes away for good: the session ends, and the controller keeps trying.
        {
            let mut hb = b.heartbeats.lock().unwrap();
            for _ in 0..50 {
                hb.push_back(Err(net_err()));
            }
        }
        {
            let mut starts = b.start_replies.lock().unwrap();
            for _ in 0..50 {
                starts.push_back(Err(net_err()));
            }
        }
        sleep(Duration::from_secs(600)).await;
        assert_ne!(c.state().phase, Phase::Idle, "plain network loss never gives up by itself");
        assert!(b.starts.load(Ordering::SeqCst) > 3);
        c.disconnect();
        wait_phase(&c, Phase::Idle).await;
        assert_eq!(c.state().notice, None);
    }

    #[tokio::test(start_paused = true)]
    async fn local_countdown_triggers_an_early_heartbeat_at_zero() {
        let (b, _e, c) = setup(FakeEngine::new());
        b.start_replies.lock().unwrap().push_back(Ok(started(1, Some(3))));
        b.heartbeats.lock().unwrap().push_back(Ok(state(SessionStatus::QuotaExhausted, Some(0))));
        c.connect(server());
        wait_phase(&c, Phase::Idle).await;
        assert!(matches!(c.state().notice, Some(Notice::QuotaExhausted { .. })));
        assert_eq!(b.heartbeat_count.load(Ordering::SeqCst), 1, "asked the server as soon as the estimate hit zero, not after 30 s");
    }

    #[test]
    fn quota_error_carries_reset_time() {
        let e = ApiError { http_status: 403, code: "quota_exhausted".into(), message: "x".into(), details: Some(serde_json::json!({"resets_at": "2026-11-15T10:00:00"})) };
        assert_eq!(notice_for_api(&e), Notice::QuotaExhausted { resets_at_ms: Some(1_794_736_800_000) });
    }
}
