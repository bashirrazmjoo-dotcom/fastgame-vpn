//! Runs sing-box as a child process and reports its state.
//!
//! sing-box owns the Wintun adapter, the routes (`auto_route`) and DNS capture; when the process ends
//! the adapter disappears and Windows falls back to the normal routes, so killing it is a complete
//! teardown. The service never edits routes itself.

use std::collections::VecDeque;
use std::io;
use std::net::IpAddr;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use fg_core::engine::{EngineError, EngineState, VpnEngine, VpnProfile};
use fg_core::link::VlessLink;
use fg_core::singbox::{self, TunOptions};
use tokio::io::{AsyncBufReadExt, AsyncRead, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;

const LOG_LINES: usize = 40;
const READY_MARKER: &str = "sing-box started";

#[async_trait]
pub trait Resolver: Send + Sync {
    async fn resolve(&self, host: &str) -> io::Result<IpAddr>;
}

/// System resolver. Runs before the tunnel exists, so it is the only lookup that uses the normal network;
/// it only ever asks for the VPN server's own name.
pub struct SystemResolver;

#[async_trait]
impl Resolver for SystemResolver {
    async fn resolve(&self, host: &str) -> io::Result<IpAddr> {
        if let Ok(ip) = host.parse() {
            return Ok(ip);
        }
        let addrs: Vec<_> = timeout(Duration::from_secs(10), tokio::net::lookup_host((host, 443)))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "DNS lookup timed out"))??
            .collect();
        addrs
            .iter()
            .find(|a| a.is_ipv4())
            .or(addrs.first())
            .map(|a| a.ip())
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no address found"))
    }
}

struct Running {
    cancel: CancellationToken,
    task: JoinHandle<()>,
}

struct Shared {
    state: EngineState,
    logs: VecDeque<String>,
}

pub struct SingBoxEngine {
    binary: PathBuf,
    work_dir: PathBuf,
    resolver: Arc<dyn Resolver>,
    tun: TunOptions,
    ready_timeout: Duration,
    shared: Arc<Mutex<Shared>>,
    running: Mutex<Option<Running>>,
}

impl SingBoxEngine {
    pub fn new(binary: PathBuf, work_dir: PathBuf, resolver: Arc<dyn Resolver>) -> Self {
        Self {
            binary,
            work_dir,
            resolver,
            tun: TunOptions::default(),
            ready_timeout: Duration::from_secs(60),
            shared: Arc::new(Mutex::new(Shared { state: EngineState::Disconnected, logs: VecDeque::new() })),
            running: Mutex::new(None),
        }
    }

    pub fn with_ready_timeout(mut self, d: Duration) -> Self {
        self.ready_timeout = d;
        self
    }

    fn set_state(&self, s: EngineState) {
        self.shared.lock().unwrap().state = s;
    }

    async fn stop_running(&self) {
        let running = self.running.lock().unwrap().take();
        if let Some(r) = running {
            r.cancel.cancel();
            let _ = r.task.await;
        }
    }

    fn config_path(&self) -> PathBuf {
        self.work_dir.join("run-config.json")
    }

    fn tail(&self) -> String {
        self.shared.lock().unwrap().logs.iter().rev().take(8).rev().cloned().collect::<Vec<_>>().join("\n")
    }
}

fn push_log(shared: &Mutex<Shared>, line: String) {
    let mut s = shared.lock().unwrap();
    if s.logs.len() >= LOG_LINES {
        s.logs.pop_front();
    }
    s.logs.push_back(line);
}

async fn pump(reader: impl AsyncRead + Unpin, tx: mpsc::UnboundedSender<String>) {
    let mut lines = BufReader::new(reader).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let _ = tx.send(line);
    }
}

#[async_trait]
impl VpnEngine for SingBoxEngine {
    async fn connect(&self, profile: &VpnProfile) -> Result<(), EngineError> {
        self.stop_running().await;
        self.shared.lock().unwrap().logs.clear();
        self.set_state(EngineState::Connecting);
        let fail = |msg: String| {
            self.set_state(EngineState::Disconnected);
            EngineError(msg)
        };

        if profile.kind != "xray" {
            return Err(fail("This server type is not supported yet.".into()));
        }
        let link = VlessLink::parse(profile.xray_link.as_deref().unwrap_or("")).map_err(|e| fail(e.to_string()))?;
        let ip = self.resolver.resolve(&link.host).await.map_err(|e| fail(format!("Could not find the server: {e}")))?;
        let config = singbox::build(&link, ip, &self.tun).map_err(|e| fail(e.to_string()))?;

        tokio::fs::create_dir_all(&self.work_dir).await.map_err(|e| fail(format!("Cannot prepare the working folder: {e}")))?;
        let path = self.config_path();
        tokio::fs::write(&path, serde_json::to_vec(&config).unwrap()).await.map_err(|e| fail(format!("Cannot write the configuration: {e}")))?;

        let mut cmd = Command::new(&self.binary);
        cmd.arg("run").arg("-c").arg(&path).arg("--disable-color").current_dir(&self.work_dir);
        cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true);
        #[cfg(windows)]
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                let _ = tokio::fs::remove_file(&path).await;
                return Err(fail(format!("Cannot start the VPN engine: {e}")));
            }
        };

        let (tx, mut rx) = mpsc::unbounded_channel::<String>();
        tokio::spawn(pump(child.stdout.take().unwrap(), tx.clone()));
        tokio::spawn(pump(child.stderr.take().unwrap(), tx));

        let cancel = CancellationToken::new();
        let (ready_tx, ready_rx) = oneshot::channel::<()>();
        let shared = self.shared.clone();
        let token = cancel.clone();
        let task = tokio::spawn(async move {
            let mut ready_tx = Some(ready_tx);
            let mut exited = None;
            loop {
                tokio::select! {
                    _ = token.cancelled() => {
                        let _ = child.kill().await;
                        shared.lock().unwrap().state = EngineState::Disconnected;
                        return;
                    }
                    status = child.wait(), if exited.is_none() => {
                        exited = Some(status);
                        // Let the pipes drain: the last log lines usually say why it ended.
                        while let Ok(Some(line)) = timeout(Duration::from_millis(300), rx.recv()).await {
                            push_log(&shared, line);
                        }
                        let code = match exited.take().unwrap() { Ok(s) => s.to_string(), Err(e) => e.to_string() };
                        let mut s = shared.lock().unwrap();
                        s.state = EngineState::Failed(format!("The VPN engine stopped ({code})."));
                        return; // dropping ready_tx tells connect() that it never became ready
                    }
                    line = rx.recv() => {
                        let Some(line) = line else { continue };
                        if line.contains(READY_MARKER) {
                            if let Some(t) = ready_tx.take() { let _ = t.send(()); }
                        }
                        push_log(&shared, line);
                    }
                }
            }
        });
        *self.running.lock().unwrap() = Some(Running { cancel: cancel.clone(), task });

        let outcome = timeout(self.ready_timeout, ready_rx).await;
        // The config holds the account UUID: it must not stay on disk, whatever happened.
        let _ = tokio::fs::remove_file(&path).await;
        match outcome {
            Ok(Ok(())) => {
                // The process may have been stopped (disconnect()) while we waited.
                if self.running.lock().unwrap().is_none() {
                    return Err(fail("Cancelled.".into()));
                }
                self.set_state(EngineState::Connected);
                Ok(())
            }
            Ok(Err(_)) => {
                self.stop_running().await;
                let tail = self.tail();
                Err(fail(if tail.is_empty() { "The VPN engine did not start.".into() } else { tail }))
            }
            Err(_) => {
                self.stop_running().await;
                let tail = self.tail();
                Err(fail(format!("Timed out while starting the VPN engine.\n{tail}").trim().to_string()))
            }
        }
    }

    async fn disconnect(&self) {
        self.stop_running().await;
        let _ = tokio::fs::remove_file(self.config_path()).await;
        self.set_state(EngineState::Disconnected);
    }

    fn state(&self) -> EngineState {
        self.shared.lock().unwrap().state.clone()
    }

    async fn diagnostics(&self) -> String {
        self.tail()
    }
}

// The tests run a shell script that stands in for sing-box, so they need a Unix shell.
#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    struct FixedResolver(IpAddr);
    #[async_trait]
    impl Resolver for FixedResolver {
        async fn resolve(&self, _h: &str) -> io::Result<IpAddr> {
            Ok(self.0)
        }
    }

    const LINK: &str = "vless://11111111-2222-3333-4444-555555555555@vpn.example.org:443?type=tcp&security=reality&sni=www.example.com&pbk=KEY&sid=ab&fp=chrome";

    fn profile() -> VpnProfile {
        VpnProfile { name: "x".into(), kind: "xray".into(), xray_link: Some(LINK.into()), ovpn_config: String::new(), auth_username: None, auth_password: None }
    }

    /// A shell script standing in for sing-box. `body` runs after the config was copied to `seen.json`.
    fn engine(body: &str) -> (SingBoxEngine, PathBuf) {
        let dir = std::env::temp_dir().join(format!("fg-engine-test-{}-{}", std::process::id(), rand_suffix()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("fake-sing-box.sh");
        std::fs::write(&bin, format!("#!/bin/sh\ncp \"$3\" \"{}/seen.json\"\n{body}\n", dir.display())).unwrap();
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        let e = SingBoxEngine::new(bin, dir.join("work"), Arc::new(FixedResolver("203.0.113.7".parse().unwrap())))
            .with_ready_timeout(Duration::from_secs(5));
        (e, dir)
    }

    fn rand_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    }

    #[tokio::test]
    async fn starts_waits_for_ready_and_removes_the_config() {
        let (e, dir) = engine("echo 'INFO[0000] sing-box started (0.01s)'\nexec sleep 30");
        e.connect(&profile()).await.unwrap();
        assert_eq!(e.state(), EngineState::Connected);
        // Config was valid JSON pointing at the resolved IP, and is gone from disk now.
        let seen: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join("seen.json")).unwrap()).unwrap();
        assert_eq!(seen["outbounds"][0]["server"], "203.0.113.7");
        assert!(!e.config_path().exists(), "config with the account UUID must not stay on disk");
        e.disconnect().await;
        assert_eq!(e.state(), EngineState::Disconnected);
    }

    #[tokio::test]
    async fn reports_why_the_engine_failed_to_start() {
        let (e, _d) = engine("echo 'FATAL start service: configure tun interface: access denied' >&2\nexit 1");
        let err = e.connect(&profile()).await.unwrap_err();
        assert!(err.0.contains("access denied"), "{}", err.0);
        assert_eq!(e.state(), EngineState::Disconnected);
        assert!(!e.config_path().exists());
    }

    #[tokio::test]
    async fn notices_when_the_engine_dies_later() {
        let (e, _d) = engine("echo 'INFO sing-box started (0.01s)'\nsleep 1\nexit 3");
        e.connect(&profile()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(2000)).await;
        assert!(matches!(e.state(), EngineState::Failed(_)), "{:?}", e.state());
        e.disconnect().await;
        assert_eq!(e.state(), EngineState::Disconnected);
    }

    #[tokio::test]
    async fn times_out_when_never_ready_and_kills_the_process() {
        let (e, dir) = engine("echo booting\nexec sleep 30");
        let e = e.with_ready_timeout(Duration::from_millis(400));
        let err = e.connect(&profile()).await.unwrap_err();
        assert!(err.0.contains("Timed out"), "{}", err.0);
        let _ = dir;
        assert_eq!(e.state(), EngineState::Disconnected);
    }

    #[tokio::test]
    async fn disconnect_cancels_a_connect_in_progress() {
        let (e, _d) = engine("echo booting\nexec sleep 30");
        let e = Arc::new(e);
        let e2 = e.clone();
        let p = profile();
        let connecting = tokio::spawn(async move { e2.connect(&p).await });
        tokio::time::sleep(Duration::from_millis(300)).await;
        e.disconnect().await;
        let r = tokio::time::timeout(Duration::from_secs(3), connecting).await.expect("connect returns promptly").unwrap();
        assert!(r.is_err());
        assert_eq!(e.state(), EngineState::Disconnected);
    }

    #[tokio::test]
    async fn rejects_bad_profiles_without_starting_anything() {
        let (e, dir) = engine("exit 0");
        let mut p = profile();
        p.kind = "openvpn".into();
        assert!(e.connect(&p).await.is_err());
        let mut p = profile();
        p.xray_link = Some("vmess://nope".into());
        assert!(e.connect(&p).await.is_err());
        assert!(!dir.join("seen.json").exists(), "the engine binary was never run");
    }

    #[tokio::test]
    async fn second_connect_replaces_the_first_session() {
        let (e, _d) = engine("echo 'INFO sing-box started'\nexec sleep 30");
        e.connect(&profile()).await.unwrap();
        e.connect(&profile()).await.unwrap();
        assert_eq!(e.state(), EngineState::Connected);
        e.disconnect().await;
    }
}
