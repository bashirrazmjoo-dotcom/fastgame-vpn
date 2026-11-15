//! The network service. Platform independent pieces (engine supervision, name resolution, watchdog)
//! are tested on any OS; the Windows service plumbing is in [`windows`].

pub mod engine;
pub mod watchdog;

#[cfg(windows)]
pub mod windows;
