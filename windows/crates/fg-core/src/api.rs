//! HTTP client of the FastGame backend (`https://fg.razmjou.com`), one function per endpoint.
//!
//! Like the Android app it tries a list of routes: the normal URL first, then a backup route straight
//! to the server's IP that accepts exactly one certificate (pinned by SHA-256) and sends the real Host
//! header. The route that worked last is tried first next time.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use reqwest::Method;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::model::*;

/// The service answered with an error. `code` is the API error code, e.g. `quota_exhausted`.
#[derive(Debug, Clone, thiserror::Error)]
#[error("{message}")]
pub struct ApiError {
    pub http_status: u16,
    pub code: String,
    pub message: String,
    pub details: Option<Value>,
}

/// The service could not be reached (offline, DNS, TLS, timeout...).
#[derive(Debug, Clone, thiserror::Error)]
#[error("network error: {0}")]
pub struct NetworkError(pub String);

#[derive(Debug, Clone, thiserror::Error)]
pub enum CallError {
    #[error(transparent)]
    Api(#[from] ApiError),
    #[error(transparent)]
    Network(#[from] NetworkError),
}

/// Raw API; authentication tokens are passed in by the caller.
#[async_trait]
pub trait FastGameApi: Send + Sync {
    async fn config(&self) -> Result<AppConfig, CallError>;
    async fn register(&self, hardware_id: &str, version_code: u32, locale: &str) -> Result<Registration, CallError>;
    async fn me(&self, token: &str) -> Result<Account, CallError>;
    async fn servers(&self, token: &str) -> Result<Vec<ServerInfo>, CallError>;
    async fn start_session(&self, token: &str, server_id: u32) -> Result<StartedSession, CallError>;
    async fn confirm_connected(&self, token: &str, session_id: &str) -> Result<SessionState, CallError>;
    async fn heartbeat(&self, token: &str, session_id: &str) -> Result<SessionState, CallError>;
    async fn stop_session(&self, token: &str, session_id: &str) -> Result<SessionState, CallError>;
}

struct Route {
    base_url: String,
    client: reqwest::Client,
    host_header: Option<String>,
}

pub struct HttpApi {
    routes: Vec<Route>,
    preferred: AtomicUsize,
}

pub struct FallbackRoute {
    pub base_url: String,
    pub host_header: String,
    pub cert_sha256_hex: String,
}

fn builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(25))
        .user_agent(concat!("FastGame-Windows/", env!("CARGO_PKG_VERSION")))
}

impl HttpApi {
    pub fn new(base_url: &str, fallback: Option<FallbackRoute>) -> Result<Self, NetworkError> {
        install_crypto_provider();
        let mut routes = vec![Route {
            base_url: base_url.trim_end_matches('/').to_string(),
            client: builder().use_rustls_tls().build().map_err(|e| NetworkError(e.to_string()))?,
            host_header: None,
        }];
        if let Some(f) = fallback {
            routes.push(Route {
                base_url: f.base_url.trim_end_matches('/').to_string(),
                client: pinned_client(&f.cert_sha256_hex)?,
                host_header: Some(f.host_header),
            });
        }
        Ok(Self { routes, preferred: AtomicUsize::new(0) })
    }

    async fn execute<T: DeserializeOwned>(
        &self,
        path: &str,
        token: Option<&str>,
        body: Option<Value>,
        post: bool,
    ) -> Result<T, CallError> {
        let start = self.preferred.load(Ordering::Relaxed);
        let mut failure: Option<NetworkError> = None;
        for step in 0..self.routes.len() {
            let index = (start + step) % self.routes.len();
            let route = &self.routes[index];
            let mut req = route
                .client
                .request(if post { Method::POST } else { Method::GET }, format!("{}{}", route.base_url, path))
                .header("Accept", "application/json");
            if let Some(h) = &route.host_header {
                req = req.header("Host", h);
            }
            if let Some(t) = token {
                req = req.bearer_auth(t);
            }
            if post {
                req = req.json(&body.clone().unwrap_or_else(|| json!({})));
            }
            let response = match req.send().await {
                Ok(r) => r,
                Err(e) => {
                    failure = Some(NetworkError(e.without_url().to_string()));
                    continue; // unreachable: try the next route
                }
            };
            self.preferred.store(index, Ordering::Relaxed);
            let status = response.status();
            let text = response.text().await.map_err(|e| NetworkError(e.without_url().to_string()))?;
            if status.is_success() {
                return serde_json::from_str(&text).map_err(|e| {
                    CallError::Api(ApiError {
                        http_status: status.as_u16(),
                        code: "bad_response".into(),
                        message: format!("Unexpected reply: {e}"),
                        details: None,
                    })
                });
            }
            return Err(parse_error(status.as_u16(), &text).into());
        }
        Err(failure.unwrap_or_else(|| NetworkError("no route".into())).into())
    }
}

fn parse_error(status: u16, text: &str) -> ApiError {
    let fallback = || ApiError { http_status: status, code: format!("http_{status}"), message: format!("HTTP {status}"), details: None };
    let Ok(v) = serde_json::from_str::<Value>(text) else { return fallback() };
    let Some(e) = v.get("error") else { return fallback() };
    ApiError {
        http_status: status,
        code: e.get("code").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| format!("http_{status}")),
        message: e.get("message").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| format!("HTTP {status}")),
        details: e.get("details").filter(|d| !d.is_null()).cloned(),
    }
}

#[async_trait]
impl FastGameApi for HttpApi {
    async fn config(&self) -> Result<AppConfig, CallError> {
        self.execute("/v1/config", None, None, false).await
    }
    async fn register(&self, hardware_id: &str, version_code: u32, locale: &str) -> Result<Registration, CallError> {
        let locale: String = locale.chars().take(8).collect();
        let body = json!({ "hardware_id": hardware_id, "app_version_code": version_code, "locale": locale });
        self.execute("/v1/devices/register", None, Some(body), true).await
    }
    async fn me(&self, token: &str) -> Result<Account, CallError> {
        self.execute("/v1/me", Some(token), None, false).await
    }
    async fn servers(&self, token: &str) -> Result<Vec<ServerInfo>, CallError> {
        self.execute("/v1/servers", Some(token), None, false).await
    }
    async fn start_session(&self, token: &str, server_id: u32) -> Result<StartedSession, CallError> {
        self.execute("/v1/sessions", Some(token), Some(json!({ "server_id": server_id })), true).await
    }
    async fn confirm_connected(&self, token: &str, session_id: &str) -> Result<SessionState, CallError> {
        self.execute(&format!("/v1/sessions/{session_id}/connected"), Some(token), None, true).await
    }
    async fn heartbeat(&self, token: &str, session_id: &str) -> Result<SessionState, CallError> {
        self.execute(&format!("/v1/sessions/{session_id}/heartbeat"), Some(token), None, true).await
    }
    async fn stop_session(&self, token: &str, session_id: &str) -> Result<SessionState, CallError> {
        self.execute(&format!("/v1/sessions/{session_id}/stop"), Some(token), None, true).await
    }
}

// --- certificate pinning for the backup route ------------------------------------------------------

/// Accepts exactly one certificate, identified by the SHA-256 of its DER encoding. The pinned
/// certificate is the identity check, so host names are not verified (the route is an IP address).
#[derive(Debug)]
struct PinnedVerifier {
    expected: Vec<u8>,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if Sha256::digest(end_entity.as_ref()).as_slice() == self.expected.as_slice() {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("unexpected server certificate".into()))
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// reqwest is built without a bundled crypto provider; install `ring` once per process.
fn install_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

fn pinned_client(cert_sha256_hex: &str) -> Result<reqwest::Client, NetworkError> {
    let cleaned: String = cert_sha256_hex.chars().filter(|c| *c != ':').collect::<String>().to_lowercase();
    let expected = hex::decode(&cleaned).map_err(|_| NetworkError("bad certificate fingerprint".into()))?;
    if expected.len() != 32 {
        return Err(NetworkError("bad certificate fingerprint".into()));
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| NetworkError(e.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedVerifier { expected, provider }))
        .with_no_client_auth();
    builder().use_preconfigured_tls(config).build().map_err(|e| NetworkError(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio_rustls::TlsAcceptor;

    /// A one-certificate HTTPS server answering every request with `body`. Returns (port, cert sha256 hex).
    async fn tls_server(body: &'static str) -> (u16, String) {
        install_crypto_provider();
        let cert = rcgen::generate_simple_self_signed(vec!["backup.test".to_string()]).unwrap();
        let der = cert.cert.der().clone();
        let fingerprint = hex::encode(Sha256::digest(der.as_ref()));
        let key = rustls::pki_types::PrivateKeyDer::try_from(cert.key_pair.serialize_der()).unwrap();
        let config = rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(vec![der], key).unwrap();
        let acceptor = TlsAcceptor::from(Arc::new(config));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let Ok((tcp, _)) = listener.accept().await else { return };
                let acceptor = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(mut tls) = acceptor.accept(tcp).await else { return };
                    let mut buf = vec![0u8; 4096];
                    let mut seen = Vec::new();
                    while !seen.windows(4).any(|w| w == b"\r\n\r\n") {
                        match tls.read(&mut buf).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => seen.extend_from_slice(&buf[..n]),
                        }
                    }
                    let head = String::from_utf8_lossy(&seen).to_lowercase();
                    // Echo the Host header the client sent so the test can check it.
                    let host = head.lines().find_map(|l| l.strip_prefix("host: ")).unwrap_or("").trim().to_string();
                    let payload = body.replace("HOST", &host);
                    let resp = format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", payload.len(), payload);
                    let _ = tls.write_all(resp.as_bytes()).await;
                    let _ = tls.shutdown().await;
                });
            }
        });
        (port, fingerprint)
    }

    fn fallback(port: u16, fp: &str) -> FallbackRoute {
        FallbackRoute { base_url: format!("https://127.0.0.1:{port}/"), host_header: "fg.example".into(), cert_sha256_hex: fp.to_string() }
    }

    const CONFIG_JSON: &str = r#"{"heartbeat_interval_seconds":30,"support_url":"https://t.me/x","min_supported_version_code":1,"latest_version_code":0,"update_url":"","privacy_policy_url":"HOST"}"#;

    #[tokio::test]
    async fn backup_route_is_used_when_main_route_is_down_and_sends_real_host() {
        let (port, fp) = tls_server(CONFIG_JSON).await;
        // Main route points at a closed port.
        let api = HttpApi::new("https://127.0.0.1:1/", Some(fallback(port, &fp))).unwrap();
        let cfg = api.config().await.unwrap();
        assert_eq!(cfg.heartbeat_interval_seconds, 30);
        assert_eq!(cfg.privacy_policy_url, "fg.example"); // the server saw our Host header
    }

    #[tokio::test]
    async fn pinned_route_rejects_a_different_certificate() {
        let (port, _fp) = tls_server(CONFIG_JSON).await;
        let wrong = "00".repeat(32);
        let api = HttpApi::new("https://127.0.0.1:1/", Some(fallback(port, &wrong))).unwrap();
        assert!(matches!(api.config().await, Err(CallError::Network(_))));
    }

    #[test]
    fn parses_api_error_body() {
        let e = parse_error(403, r#"{"error":{"code":"quota_exhausted","message":"used up","details":{"resets_at":"2026-11-15T00:00:00"}}}"#);
        assert_eq!(e.code, "quota_exhausted");
        assert_eq!(e.http_status, 403);
        assert_eq!(e.details.unwrap()["resets_at"], "2026-11-15T00:00:00");
    }

    #[test]
    fn unknown_error_body_falls_back_to_http_status() {
        let e = parse_error(502, "<html>bad gateway</html>");
        assert_eq!(e.code, "http_502");
        let e = parse_error(500, r#"{"detail":"x"}"#);
        assert_eq!(e.code, "http_500");
    }

    #[test]
    fn pinned_client_rejects_bad_fingerprints() {
        assert!(pinned_client("zz").is_err());
        assert!(pinned_client("abcd").is_err());
        let ok = "43:5B:7D:B6:2E:B5:E4:DF:A9:69:EE:21:6F:93:5E:1E:8F:E2:1F:62:39:3D:5C:38:AC:A4:64:61:07:96:91:2E";
        assert!(pinned_client(ok).is_ok());
    }
}
