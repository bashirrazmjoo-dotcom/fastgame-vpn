//! Protocol between the app (normal user) and the network service (SYSTEM).
//!
//! Frames: 4-byte little-endian length + JSON, at most [`MAX_FRAME`] bytes. One request, one reply per
//! connection round trip. The service never takes file paths, commands or raw sing-box configs from the
//! client: the only inputs are the session profile (a `vless://` link, validated and turned into a config
//! from fixed templates) and a few parameterless commands.

use std::io;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::task::JoinHandle;
use tokio::time::timeout;

use crate::engine::{EngineError, EngineState, VpnEngine, VpnProfile};

pub const PROTOCOL_VERSION: u32 = 2; // 2: profile carries the connect mode
pub const MAX_FRAME: usize = 64 * 1024;
/// The service tears the tunnel down when it hears nothing from the app for this long.
pub const WATCHDOG_SECONDS: u64 = 20;
const POLL_EVERY: Duration = Duration::from_secs(3);
const CALL_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum Request {
    Hello { version: u32 },
    Connect { profile: VpnProfile },
    Disconnect,
    Status,
    Diagnostics,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "reply", rename_all = "snake_case")]
pub enum Response {
    Hello { version: u32, service_version: String },
    Ok,
    Status { state: EngineState },
    Diagnostics { text: String },
    Error { message: String },
}

#[derive(Debug, thiserror::Error)]
pub enum IpcError {
    #[error("The VPN service is not running. Reinstall or restart the app.")]
    Unavailable(#[source] io::Error),
    #[error("Communication with the VPN service failed: {0}")]
    Io(#[from] io::Error),
    #[error("Bad message from the VPN service.")]
    Protocol,
    #[error("The app and the VPN service have different versions. Restart your computer or reinstall.")]
    VersionMismatch,
}

pub async fn write_frame<W: AsyncWrite + Unpin, T: Serialize>(w: &mut W, msg: &T) -> io::Result<()> {
    let body = serde_json::to_vec(msg).map_err(io::Error::other)?;
    if body.len() > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large"));
    }
    w.write_all(&(body.len() as u32).to_le_bytes()).await?;
    w.write_all(&body).await?;
    w.flush().await
}

/// `Ok(None)` = peer closed the connection cleanly before a new frame.
pub async fn read_frame<R: AsyncRead + Unpin, T: for<'de> Deserialize<'de>>(r: &mut R) -> io::Result<Option<T>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_le_bytes(len) as usize;
    if len == 0 || len > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad frame length"));
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body).await?;
    serde_json::from_slice(&body).map(Some).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

// --- server side -------------------------------------------------------------------------------------

/// Serves one client connection. `on_activity` is called for every request (watchdog feed).
pub async fn serve_connection<S>(mut stream: S, engine: Arc<dyn VpnEngine>, on_activity: impl Fn() + Send) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    // The first frame must be a matching Hello; anything else is dropped without an answer.
    match read_frame::<_, Request>(&mut stream).await? {
        Some(Request::Hello { version }) if version == PROTOCOL_VERSION => {
            let reply = Response::Hello { version: PROTOCOL_VERSION, service_version: env!("CARGO_PKG_VERSION").into() };
            write_frame(&mut stream, &reply).await?;
        }
        Some(Request::Hello { .. }) => {
            write_frame(&mut stream, &Response::Error { message: "version_mismatch".into() }).await?;
            return Ok(());
        }
        _ => return Ok(()),
    }
    while let Some(req) = read_frame::<_, Request>(&mut stream).await? {
        on_activity();
        let reply = match req {
            Request::Hello { .. } => Response::Error { message: "unexpected hello".into() },
            Request::Connect { profile } => match engine.connect(&profile).await {
                Ok(()) => Response::Ok,
                Err(e) => Response::Error { message: e.0 },
            },
            Request::Disconnect => {
                engine.disconnect().await;
                Response::Ok
            }
            Request::Status => Response::Status { state: engine.state() },
            Request::Diagnostics => Response::Diagnostics { text: engine.diagnostics().await },
        };
        write_frame(&mut stream, &reply).await?;
    }
    Ok(())
}

// --- client side -------------------------------------------------------------------------------------

pub trait Duplex: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Duplex for T {}

#[async_trait]
pub trait Connector: Send + Sync {
    async fn open(&self) -> io::Result<Box<dyn Duplex>>;
}

/// One request on a fresh connection. Abandoning the future (cancellation) just drops the connection.
pub async fn call(connector: &dyn Connector, req: &Request) -> Result<Response, IpcError> {
    let mut stream = connector.open().await.map_err(IpcError::Unavailable)?;
    write_frame(&mut stream, &Request::Hello { version: PROTOCOL_VERSION }).await?;
    match read_frame::<_, Response>(&mut stream).await? {
        Some(Response::Hello { version, .. }) if version == PROTOCOL_VERSION => {}
        Some(Response::Error { .. }) => return Err(IpcError::VersionMismatch),
        _ => return Err(IpcError::Protocol),
    }
    write_frame(&mut stream, req).await?;
    timeout(CALL_TIMEOUT, read_frame::<_, Response>(&mut stream))
        .await
        .map_err(|_| IpcError::Io(io::ErrorKind::TimedOut.into()))??
        .ok_or(IpcError::Protocol)
}

/// [`VpnEngine`] implemented by talking to the service. While a session is active it polls the service
/// every few seconds; that doubles as the keep-alive the service's watchdog listens for.
pub struct PipeEngine {
    connector: Arc<dyn Connector>,
    state: Arc<Mutex<EngineState>>,
    poller: Mutex<Option<JoinHandle<()>>>,
}

impl PipeEngine {
    pub fn new(connector: Arc<dyn Connector>) -> Self {
        Self { connector, state: Arc::new(Mutex::new(EngineState::Disconnected)), poller: Mutex::new(None) }
    }

    fn set(&self, s: EngineState) {
        *self.state.lock().unwrap() = s;
    }

    fn stop_poller(&self) {
        if let Some(p) = self.poller.lock().unwrap().take() {
            p.abort();
        }
    }

    fn start_poller(&self) {
        self.stop_poller();
        let (connector, state) = (self.connector.clone(), self.state.clone());
        let handle = tokio::spawn(async move {
            let mut failures = 0u32;
            loop {
                tokio::time::sleep(POLL_EVERY).await;
                match call(&*connector, &Request::Status).await {
                    Ok(Response::Status { state: s }) => {
                        failures = 0;
                        // Do not let a poll that raced with connect() overwrite "Connecting".
                        let mut cur = state.lock().unwrap();
                        if *cur != EngineState::Connecting {
                            *cur = s;
                        }
                    }
                    _ => {
                        failures += 1;
                        if failures >= 3 {
                            *state.lock().unwrap() = EngineState::Failed("The VPN service stopped responding.".into());
                        }
                    }
                }
            }
        });
        *self.poller.lock().unwrap() = Some(handle);
    }
}

impl Drop for PipeEngine {
    fn drop(&mut self) {
        self.stop_poller();
    }
}

#[async_trait]
impl VpnEngine for PipeEngine {
    async fn connect(&self, profile: &VpnProfile) -> Result<(), EngineError> {
        self.set(EngineState::Connecting);
        self.start_poller();
        match call(&*self.connector, &Request::Connect { profile: profile.clone() }).await {
            Ok(Response::Ok) => {
                self.set(EngineState::Connected);
                Ok(())
            }
            Ok(Response::Error { message }) => {
                self.set(EngineState::Disconnected);
                Err(EngineError(message))
            }
            Ok(_) => Err(EngineError(IpcError::Protocol.to_string())),
            Err(e) => {
                self.set(EngineState::Disconnected);
                Err(EngineError(e.to_string()))
            }
        }
    }

    async fn disconnect(&self) {
        self.stop_poller();
        let _ = call(&*self.connector, &Request::Disconnect).await;
        self.set(EngineState::Disconnected);
    }

    fn state(&self) -> EngineState {
        self.state.lock().unwrap().clone()
    }

    async fn diagnostics(&self) -> String {
        match call(&*self.connector, &Request::Diagnostics).await {
            Ok(Response::Diagnostics { text }) => text,
            _ => String::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};
    use tokio::io::duplex;

    struct FakeService {
        st: Mutex<EngineState>,
        activity: AtomicU32,
    }

    #[async_trait]
    impl VpnEngine for FakeService {
        async fn connect(&self, p: &VpnProfile) -> Result<(), EngineError> {
            if p.xray_link.is_none() {
                return Err(EngineError("no link".into()));
            }
            *self.st.lock().unwrap() = EngineState::Connected;
            Ok(())
        }
        async fn disconnect(&self) {
            *self.st.lock().unwrap() = EngineState::Disconnected;
        }
        fn state(&self) -> EngineState {
            self.st.lock().unwrap().clone()
        }
        async fn diagnostics(&self) -> String {
            "log".into()
        }
    }

    struct LoopbackConnector(Arc<FakeService>);

    #[async_trait]
    impl Connector for LoopbackConnector {
        async fn open(&self) -> io::Result<Box<dyn Duplex>> {
            let (client, server) = duplex(8192);
            let svc = self.0.clone();
            tokio::spawn(async move {
                let engine: Arc<dyn VpnEngine> = svc.clone();
                let _ = serve_connection(server, engine, || {
                    svc.activity.fetch_add(1, Ordering::SeqCst);
                })
                .await;
            });
            Ok(Box::new(client))
        }
    }

    fn profile(link: Option<&str>) -> VpnProfile {
        VpnProfile {
            name: "x".into(),
            kind: "xray".into(),
            xray_link: link.map(String::from),
            ovpn_config: String::new(),
            auth_username: None,
            auth_password: None,
            mode: Default::default(),
            proxy_port: None,
        }
    }

    fn setup() -> (Arc<FakeService>, PipeEngine) {
        let svc = Arc::new(FakeService { st: Mutex::new(EngineState::Disconnected), activity: AtomicU32::new(0) });
        (svc.clone(), PipeEngine::new(Arc::new(LoopbackConnector(svc))))
    }

    #[tokio::test(start_paused = true)]
    async fn connect_status_disconnect_roundtrip() {
        let (svc, engine) = setup();
        engine.connect(&profile(Some("vless://x"))).await.unwrap();
        assert_eq!(svc.state(), EngineState::Connected);
        assert_eq!(engine.state(), EngineState::Connected);
        assert_eq!(engine.diagnostics().await, "log");
        engine.disconnect().await;
        assert_eq!(svc.state(), EngineState::Disconnected);
        assert_eq!(engine.state(), EngineState::Disconnected);
    }

    #[tokio::test(start_paused = true)]
    async fn service_errors_reach_the_app() {
        let (_svc, engine) = setup();
        let err = engine.connect(&profile(None)).await.unwrap_err();
        assert_eq!(err.0, "no link");
        assert_eq!(engine.state(), EngineState::Disconnected);
    }

    #[tokio::test(start_paused = true)]
    async fn poller_feeds_the_watchdog_and_sees_tunnel_drops() {
        let (svc, engine) = setup();
        engine.connect(&profile(Some("vless://x"))).await.unwrap();
        let before = svc.activity.load(Ordering::SeqCst);
        tokio::time::sleep(Duration::from_secs(10)).await;
        assert!(svc.activity.load(Ordering::SeqCst) >= before + 3, "status polls keep the watchdog alive");
        *svc.st.lock().unwrap() = EngineState::Failed("sing-box exited".into());
        tokio::time::sleep(Duration::from_secs(4)).await;
        assert_eq!(engine.state(), EngineState::Failed("sing-box exited".into()));
        engine.disconnect().await;
    }

    #[tokio::test]
    async fn rejects_wrong_version_and_missing_hello() {
        let svc = Arc::new(FakeService { st: Mutex::new(EngineState::Disconnected), activity: AtomicU32::new(0) });
        // wrong version
        let (mut c, s) = duplex(1024);
        let e: Arc<dyn VpnEngine> = svc.clone();
        let t = tokio::spawn(serve_connection(s, e, || {}));
        write_frame(&mut c, &Request::Hello { version: 99 }).await.unwrap();
        let r: Response = read_frame(&mut c).await.unwrap().unwrap();
        assert!(matches!(r, Response::Error { .. }));
        t.await.unwrap().unwrap();
        // a command before hello gets no answer, and is never executed
        let (mut c, s) = duplex(1024);
        let e: Arc<dyn VpnEngine> = svc.clone();
        let t = tokio::spawn(serve_connection(s, e, || {}));
        write_frame(&mut c, &Request::Connect { profile: profile(Some("vless://x")) }).await.unwrap();
        t.await.unwrap().unwrap();
        assert_eq!(svc.state(), EngineState::Disconnected);
    }

    #[tokio::test]
    async fn oversized_and_garbage_frames_are_rejected() {
        let (mut c, mut s) = duplex(1024);
        c.write_all(&(MAX_FRAME as u32 + 1).to_le_bytes()).await.unwrap();
        assert!(read_frame::<_, Request>(&mut s).await.is_err());
        let (mut c, mut s) = duplex(1024);
        c.write_all(&5u32.to_le_bytes()).await.unwrap();
        c.write_all(b"nojson").await.unwrap_err_or_ok();
        assert!(read_frame::<_, Request>(&mut s).await.is_err());
        let (mut c, mut s) = duplex(1024);
        c.write_all(&0u32.to_le_bytes()).await.unwrap();
        assert!(read_frame::<_, Request>(&mut s).await.is_err());
    }

    trait IgnoreResult {
        fn unwrap_err_or_ok(self);
    }
    impl<T, E> IgnoreResult for Result<T, E> {
        fn unwrap_err_or_ok(self) {}
    }
}
