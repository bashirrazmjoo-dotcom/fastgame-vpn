//! The tunnel engine as seen by the session controller. The real implementation lives in the Windows
//! service (sing-box + Wintun); tests use a fake.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::model::StartedSession;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", content = "reason", rename_all = "snake_case")]
pub enum EngineState {
    Disconnected,
    Connecting,
    Connected,
    Failed(String),
}

/// How the device is connected. `Tun` sends the whole system through the VPN adapter;
/// `Proxy` only starts a local proxy for the apps that follow the Windows proxy setting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConnectMode {
    #[default]
    Tun,
    Proxy,
}

/// Everything the engine needs for one session; comes straight from the backend's answer.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VpnProfile {
    pub name: String,
    pub kind: String,
    pub xray_link: Option<String>,
    pub ovpn_config: String,
    pub auth_username: Option<String>,
    pub auth_password: Option<String>,
    /// Absent in older callers: the whole system, as before.
    #[serde(default)]
    pub mode: ConnectMode,
    /// Loopback port of the local proxy (proxy mode only).
    #[serde(default)]
    pub proxy_port: Option<u16>,
}

impl VpnProfile {
    pub fn from_session(name: &str, s: &StartedSession) -> Self {
        Self {
            name: name.to_string(),
            kind: s.kind.clone(),
            xray_link: s.xray_link.clone(),
            ovpn_config: s.ovpn_config.clone(),
            auth_username: s.auth_username.clone(),
            auth_password: s.auth_password.clone(),
            mode: ConnectMode::Tun,
            proxy_port: None,
        }
    }

    pub fn with_mode(mut self, mode: ConnectMode, proxy_port: Option<u16>) -> Self {
        self.mode = mode;
        self.proxy_port = proxy_port;
        self
    }
}

#[derive(Debug, Clone, thiserror::Error)]
#[error("{0}")]
pub struct EngineError(pub String);

#[async_trait]
pub trait VpnEngine: Send + Sync {
    /// Returns once traffic flows through the tunnel, or fails.
    async fn connect(&self, profile: &VpnProfile) -> Result<(), EngineError>;
    /// Idempotent; always leaves the system with its normal routes and DNS.
    async fn disconnect(&self);
    fn state(&self) -> EngineState;
    /// Last lines of the engine's log, shown when connecting fails.
    async fn diagnostics(&self) -> String;
}
