//! Closes the tunnel when the app stops talking to the service (crash, kill, logoff). The server-side
//! session ends by itself when heartbeats stop; this makes sure the machine does not stay routed through
//! a tunnel nobody supervises.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::time::Instant;

use fg_core::engine::{EngineState, VpnEngine};

#[derive(Clone)]
pub struct Watchdog {
    start: Instant,
    last_seen_ms: Arc<AtomicU64>,
}

impl Default for Watchdog {
    fn default() -> Self {
        Self::new()
    }
}

impl Watchdog {
    pub fn new() -> Self {
        Self { start: Instant::now(), last_seen_ms: Arc::new(AtomicU64::new(0)) }
    }

    pub fn feed(&self) {
        self.last_seen_ms.store(self.start.elapsed().as_millis() as u64, Ordering::Relaxed);
    }

    pub fn silence(&self) -> Duration {
        Duration::from_millis((self.start.elapsed().as_millis() as u64).saturating_sub(self.last_seen_ms.load(Ordering::Relaxed)))
    }

    /// Runs forever; checks every second.
    pub async fn run(self, engine: Arc<dyn VpnEngine>, limit: Duration) {
        loop {
            tokio::time::sleep(Duration::from_secs(1)).await;
            let active = !matches!(engine.state(), EngineState::Disconnected);
            if active && self.silence() > limit {
                tracing::warn!("app is silent, closing the tunnel");
                engine.disconnect().await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use fg_core::engine::{EngineError, VpnProfile};
    use std::sync::Mutex;

    struct E(Mutex<EngineState>);
    #[async_trait]
    impl VpnEngine for E {
        async fn connect(&self, _p: &VpnProfile) -> Result<(), EngineError> {
            Ok(())
        }
        async fn disconnect(&self) {
            *self.0.lock().unwrap() = EngineState::Disconnected;
        }
        fn state(&self) -> EngineState {
            self.0.lock().unwrap().clone()
        }
        async fn diagnostics(&self) -> String {
            String::new()
        }
    }

    #[tokio::test(start_paused = true)]
    async fn closes_the_tunnel_after_silence_but_not_while_fed() {
        let engine = Arc::new(E(Mutex::new(EngineState::Connected)));
        let dog = Watchdog::new();
        dog.feed();
        tokio::spawn(dog.clone().run(engine.clone(), Duration::from_secs(20)));
        for _ in 0..6 {
            tokio::time::sleep(Duration::from_secs(5)).await;
            dog.feed();
        }
        assert_eq!(engine.state(), EngineState::Connected);
        tokio::time::sleep(Duration::from_secs(25)).await;
        assert_eq!(engine.state(), EngineState::Disconnected);
    }
}
