//! Shared logic of the FastGame VPN Windows app. Everything here is platform independent and
//! unit-tested on any OS; Windows-only pieces (service, named pipe security) live in `fg-service`
//! and the app shell.

pub mod link;
pub mod singbox;
pub mod api;
pub mod model;
pub mod backend;
pub mod controller;
pub mod engine;
pub mod ipc;
