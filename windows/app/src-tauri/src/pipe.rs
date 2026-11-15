//! Connection to the network service's named pipe (Windows).

use std::io;
use std::time::Duration;

use async_trait::async_trait;
use fg_core::ipc::{Connector, Duplex};

pub struct PipeConnector;

#[cfg(windows)]
#[async_trait]
impl Connector for PipeConnector {
    async fn open(&self) -> io::Result<Box<dyn Duplex>> {
        use tokio::net::windows::named_pipe::ClientOptions;
        const ERROR_PIPE_BUSY: i32 = 231;
        let mut tries = 0;
        loop {
            match ClientOptions::new().open(r"\\.\pipe\FastGameVPN") {
                Ok(c) => return Ok(Box::new(c)),
                // All instances are in use for a moment: wait and retry.
                Err(e) if e.raw_os_error() == Some(ERROR_PIPE_BUSY) && tries < 20 => {
                    tries += 1;
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
                Err(e) => return Err(e),
            }
        }
    }
}

#[cfg(not(windows))]
#[async_trait]
impl Connector for PipeConnector {
    async fn open(&self) -> io::Result<Box<dyn Duplex>> {
        let _ = Duration::ZERO;
        Err(io::Error::new(io::ErrorKind::Unsupported, "Windows only"))
    }
}
