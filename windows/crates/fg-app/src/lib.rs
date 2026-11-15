//! UI-independent application core. The Tauri shell only forwards commands to [`app::AppCore`] and
//! pushes its snapshots to the page, so everything here is tested without a window.

pub mod app;
pub mod machine;
pub mod store;
