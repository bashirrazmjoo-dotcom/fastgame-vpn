//! What the rest of the app talks to: the API without any token handling.
//! Registers the device on first use, stores its token, and transparently registers again
//! (same hardware id gives the same account) if the server says the token is no longer valid.

use async_trait::async_trait;
use tokio::sync::Mutex;

use crate::api::{CallError, FastGameApi};
use crate::model::*;

#[async_trait]
pub trait Backend: Send + Sync {
    async fn config(&self) -> Result<AppConfig, CallError>;
    async fn account(&self) -> Result<Account, CallError>;
    async fn servers(&self) -> Result<Vec<ServerInfo>, CallError>;
    async fn start_session(&self, server_id: u32) -> Result<StartedSession, CallError>;
    async fn confirm_connected(&self, session_id: &str) -> Result<SessionState, CallError>;
    async fn heartbeat(&self, session_id: &str) -> Result<SessionState, CallError>;
    async fn stop_session(&self, session_id: &str) -> Result<SessionState, CallError>;
}

/// Persistent per-user storage of the device identity.
pub trait Store: Send + Sync {
    fn device_token(&self) -> Option<String>;
    fn set_device_token(&self, token: Option<String>);
    fn set_user_code(&self, code: &str);
}

impl<T: Store + ?Sized> Store for std::sync::Arc<T> {
    fn device_token(&self) -> Option<String> {
        (**self).device_token()
    }
    fn set_device_token(&self, token: Option<String>) {
        (**self).set_device_token(token)
    }
    fn set_user_code(&self, code: &str) {
        (**self).set_user_code(code)
    }
}

pub struct BackendImpl<A: FastGameApi, S: Store> {
    api: A,
    store: S,
    hardware_id: String,
    version_code: u32,
    locale: String,
    register_lock: Mutex<()>,
}

impl<A: FastGameApi, S: Store> BackendImpl<A, S> {
    pub fn new(api: A, store: S, hardware_id: String, version_code: u32, locale: String) -> Self {
        Self { api, store, hardware_id, version_code, locale, register_lock: Mutex::new(()) }
    }

    async fn register(&self) -> Result<String, CallError> {
        let r = self.api.register(&self.hardware_id, self.version_code, &self.locale).await?;
        self.store.set_device_token(Some(r.device_token.clone()));
        self.store.set_user_code(&r.user_code);
        Ok(r.device_token)
    }

    async fn ensure_token(&self) -> Result<String, CallError> {
        if let Some(t) = self.store.device_token() {
            return Ok(t);
        }
        let _guard = self.register_lock.lock().await;
        match self.store.device_token() {
            Some(t) => Ok(t),
            None => self.register().await,
        }
    }

    async fn authed<T, F, Fut>(&self, call: F) -> Result<T, CallError>
    where
        F: Fn(String) -> Fut,
        Fut: std::future::Future<Output = Result<T, CallError>>,
    {
        let token = self.ensure_token().await?;
        match call(token).await {
            Err(CallError::Api(e)) if e.http_status == 401 => {}
            other => return other,
        }
        self.store.set_device_token(None);
        let token = self.ensure_token().await?;
        call(token).await
    }
}

#[async_trait]
impl<A: FastGameApi, S: Store> Backend for BackendImpl<A, S> {
    async fn config(&self) -> Result<AppConfig, CallError> {
        self.api.config().await
    }
    async fn account(&self) -> Result<Account, CallError> {
        self.authed(|t| async move { self.api.me(&t).await }).await
    }
    async fn servers(&self) -> Result<Vec<ServerInfo>, CallError> {
        self.authed(|t| async move { self.api.servers(&t).await }).await
    }
    async fn start_session(&self, server_id: u32) -> Result<StartedSession, CallError> {
        self.authed(|t| async move { self.api.start_session(&t, server_id).await }).await
    }
    async fn confirm_connected(&self, session_id: &str) -> Result<SessionState, CallError> {
        self.authed(|t| async move { self.api.confirm_connected(&t, session_id).await }).await
    }
    async fn heartbeat(&self, session_id: &str) -> Result<SessionState, CallError> {
        self.authed(|t| async move { self.api.heartbeat(&t, session_id).await }).await
    }
    async fn stop_session(&self, session_id: &str) -> Result<SessionState, CallError> {
        self.authed(|t| async move { self.api.stop_session(&t, session_id).await }).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::ApiError;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::Mutex as StdMutex;

    #[derive(Default)]
    struct MemStore(StdMutex<(Option<String>, Option<String>)>);
    impl Store for MemStore {
        fn device_token(&self) -> Option<String> {
            self.0.lock().unwrap().0.clone()
        }
        fn set_device_token(&self, t: Option<String>) {
            self.0.lock().unwrap().0 = t;
        }
        fn set_user_code(&self, c: &str) {
            self.0.lock().unwrap().1 = Some(c.to_string());
        }
    }

    /// Accepts only the token of the most recent registration.
    struct FakeApi {
        registrations: AtomicU32,
        valid: StdMutex<String>,
    }

    fn unauthorized() -> CallError {
        CallError::Api(ApiError { http_status: 401, code: "invalid_token".into(), message: "x".into(), details: None })
    }

    #[async_trait]
    impl FastGameApi for FakeApi {
        async fn config(&self) -> Result<AppConfig, CallError> {
            unimplemented!()
        }
        async fn register(&self, _h: &str, _v: u32, _l: &str) -> Result<Registration, CallError> {
            let n = self.registrations.fetch_add(1, Ordering::SeqCst) + 1;
            let token = format!("token-{n}");
            *self.valid.lock().unwrap() = token.clone();
            Ok(Registration { user_code: "ABCD-2345".into(), device_token: token })
        }
        async fn me(&self, _t: &str) -> Result<Account, CallError> {
            unimplemented!()
        }
        async fn servers(&self, token: &str) -> Result<Vec<ServerInfo>, CallError> {
            if *self.valid.lock().unwrap() == token { Ok(vec![]) } else { Err(unauthorized()) }
        }
        async fn start_session(&self, _t: &str, _s: u32) -> Result<StartedSession, CallError> {
            unimplemented!()
        }
        async fn confirm_connected(&self, _t: &str, _s: &str) -> Result<SessionState, CallError> {
            unimplemented!()
        }
        async fn heartbeat(&self, _t: &str, _s: &str) -> Result<SessionState, CallError> {
            unimplemented!()
        }
        async fn stop_session(&self, _t: &str, _s: &str) -> Result<SessionState, CallError> {
            unimplemented!()
        }
    }

    fn backend(valid: &str) -> BackendImpl<FakeApi, MemStore> {
        BackendImpl::new(
            FakeApi { registrations: AtomicU32::new(0), valid: StdMutex::new(valid.into()) },
            MemStore::default(),
            "win-test".into(),
            1,
            "fa".into(),
        )
    }

    #[tokio::test]
    async fn registers_once_on_first_use() {
        let b = backend("");
        b.servers().await.unwrap();
        b.servers().await.unwrap();
        assert_eq!(b.api.registrations.load(Ordering::SeqCst), 1);
        assert_eq!(b.store.device_token().as_deref(), Some("token-1"));
    }

    #[tokio::test]
    async fn registers_again_after_401_and_retries_once() {
        let b = backend("something-else");
        b.store.set_device_token(Some("stale".into()));
        b.servers().await.unwrap();
        assert_eq!(b.api.registrations.load(Ordering::SeqCst), 1);
        assert_eq!(b.store.device_token().as_deref(), Some("token-1"));
    }
}
