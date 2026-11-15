# FastGame VPN for Windows

Full-system VPN for Windows 10/11 (x64): all traffic, DNS and IPv6 go through a virtual adapter
(Wintun, created by sing-box). Same behaviour and the same backend API as the Android app
(`../android`, `../backend`). Licensed under GPL v2 like the rest of the repository.

## How it works

```
FastGame.exe (Tauri: Rust + web UI, normal user)          fastgame-service.exe (Windows service, SYSTEM)
  account, servers, session, heartbeat, auto-reconnect  ->  named pipe  ->  sing-box.exe (TUN + auto_route + DNS hijack)
  (talks to https://fg.razmjou.com)                          one fixed command set, validated input
```

| Crate | What it is |
|---|---|
| `crates/fg-core` | Backend API client (with pinned backup route), session controller (port of Android's `ConnectionController`), `vless://` parser, sing-box config builder, pipe protocol |
| `crates/fg-service` | The service: runs sing-box, named pipe with ACL + client check, watchdog |
| `crates/fg-app` | UI-independent app logic (device id, cache, commands) |
| `app/` | Tauri shell and the web page (`app/ui`), Persian/English |
| `installer/` | Inno Setup script (installs the service, WebView2 if missing) |

Security notes:
* Time is counted by the server only; the app just shows an estimate and re-syncs on every heartbeat.
* The service never accepts paths, commands or raw configs. It receives the session's `vless://` link and
  builds the sing-box config from fixed templates (`fg-core/src/singbox.rs`).
* The pipe is created as first instance, rejects remote clients, allows SYSTEM/Administrators/interactive users,
  and each connection is checked: the client process must be the installed `FastGame.exe`.
* The engine config (contains the account UUID) is written to SYSTEM's profile folder and deleted as soon as sing-box has started.
* If the app stops talking to the service for 20 s (crash/kill), the service closes the tunnel.
* `strict_route` is on: while the tunnel is up, traffic cannot bypass it (no leaks when it drops).

## Build and test

```
cd windows
cargo test                       # all logic tests (any OS); service code is also compiled/tested on Windows CI
cargo build --release -p fg-service
cargo build --release -p fastgame --features custom-protocol    # on Windows
```
CI (`.github/workflows/windows.yml`): tests on Linux (plus `sing-box check` of generated configs), then on
Windows builds everything and the installer (`FastGame-Setup-<version>.exe`, uploaded as artifact; a tag
`windows-vX.Y.Z` publishes it to Releases).

## Open points
* **Update prompt is off**: `/v1/config` has one `latest_version_code` shared with Android. Needs a per-platform value in the backend.
* **Not signed yet** (SmartScreen will warn). The workflow is ready for a signing step once a certificate exists.
* **OpenVPN servers** are listed but disabled (phase 2).
* `sing-box.exe` is the unmodified official build (about 80 MB); a slimmer custom build is a later optimisation.
