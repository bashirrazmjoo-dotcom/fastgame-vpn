//! Builds the sing-box configuration for one VPN session.
//!
//! Pure function, covered by unit tests (and validated against a real `sing-box check` in CI).
//! The server host is resolved *before* the tunnel exists (by the caller), so the config never needs
//! DNS to reach the VPN server and the server address can be excluded from the tunnel routes.

use std::net::IpAddr;

use serde_json::{json, Map, Value};

use crate::link::VlessLink;

pub const TUN_NAME: &str = "FastGame";
pub const PROXY_TAG: &str = "proxy";

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ConfigError {
    #[error("Unsupported transport: {0}")]
    Transport(String),
    #[error("Unsupported security: {0}")]
    Security(String),
    #[error("The server link has no Reality key.")]
    NoRealityKey,
}

#[derive(Debug, Clone)]
pub struct TunOptions {
    /// Block traffic that would bypass the tunnel (sing-box `strict_route`): no leaks when the tunnel drops.
    pub strict_route: bool,
    pub mtu: u32,
    /// DNS resolvers reached *through* the tunnel.
    pub dns_server: IpAddr,
}

impl Default for TunOptions {
    fn default() -> Self {
        Self { strict_route: true, mtu: 1400, dns_server: IpAddr::from([1, 1, 1, 1]) }
    }
}

/// `server_ip` is the already-resolved address of the link's host (or the host itself if it is an IP).
pub fn build(link: &VlessLink, server_ip: IpAddr, opts: &TunOptions) -> Result<Value, ConfigError> {
    let exclude = match server_ip {
        IpAddr::V4(_) => format!("{server_ip}/32"),
        IpAddr::V6(_) => format!("{server_ip}/128"),
    };

    Ok(json!({
        "log": { "level": "info", "timestamp": true },
        "dns": {
            "servers": [
                // Every lookup goes through the VPN server (over TCP: UDP relaying through the proxy is not
                // guaranteed), so DNS never leaves on the physical network.
                { "type": "tcp", "tag": "dns-remote", "server": opts.dns_server.to_string(), "detour": PROXY_TAG }
            ],
            "final": "dns-remote",
            // The VPN server and the tunnel are IPv4-only for lookups; AAAA answers would make browsers
            // try IPv6 paths that do not exist and look "offline".
            "strategy": "ipv4_only"
        },
        "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "interface_name": TUN_NAME,
            "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
            "mtu": opts.mtu,
            "auto_route": true,
            "strict_route": opts.strict_route,
            // The VPN server itself must never be routed into the tunnel (route loop).
            "route_exclude_address": [exclude]
        }],
        "outbounds": [
            vless_outbound(link, server_ip)?,
            { "type": "direct", "tag": "direct" }
        ],
        "route": {
            "auto_detect_interface": true,
            "rules": [
                { "action": "sniff" },
                { "protocol": "dns", "action": "hijack-dns" }
            ],
            "final": PROXY_TAG
        }
    }))
}

fn vless_outbound(l: &VlessLink, server_ip: IpAddr) -> Result<Value, ConfigError> {
    let mut o = Map::new();
    o.insert("type".into(), json!("vless"));
    o.insert("tag".into(), json!(PROXY_TAG));
    o.insert("server".into(), json!(server_ip.to_string()));
    o.insert("server_port".into(), json!(l.port));
    o.insert("uuid".into(), json!(l.uuid));
    if let Some(flow) = l.param("flow") {
        o.insert("flow".into(), json!(flow));
    }
    if let Some(tls) = tls_settings(l)? {
        o.insert("tls".into(), tls);
    }
    if let Some(t) = transport(l)? {
        o.insert("transport".into(), t);
    }
    Ok(Value::Object(o))
}

fn transport(l: &VlessLink) -> Result<Option<Value>, ConfigError> {
    let host_header = l.param("host").or_else(|| l.param("sni")).unwrap_or(&l.host).to_string();
    Ok(match l.param("type").unwrap_or("tcp") {
        "tcp" => None,
        "ws" => Some(json!({
            "type": "ws",
            "path": l.param("path").unwrap_or("/"),
            "headers": { "Host": host_header }
        })),
        "grpc" => Some(json!({ "type": "grpc", "service_name": l.param("serviceName").unwrap_or("") })),
        "httpupgrade" => Some(json!({
            "type": "httpupgrade",
            "path": l.param("path").unwrap_or("/"),
            "host": host_header
        })),
        other => return Err(ConfigError::Transport(other.to_string())),
    })
}

fn tls_settings(l: &VlessLink) -> Result<Option<Value>, ConfigError> {
    let sni = l.param("sni").unwrap_or(&l.host).to_string();
    match l.param("security").unwrap_or("none") {
        "none" => Ok(None),
        "tls" => {
            let mut tls = json!({ "enabled": true, "server_name": sni, "insecure": false });
            if let Some(fp) = l.param("fp") {
                tls["utls"] = json!({ "enabled": true, "fingerprint": fp });
            }
            if let Some(alpn) = l.param("alpn") {
                tls["alpn"] = json!(alpn.split(',').map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>());
            }
            Ok(Some(tls))
        }
        "reality" => {
            let pbk = l.param("pbk").ok_or(ConfigError::NoRealityKey)?;
            Ok(Some(json!({
                "enabled": true,
                "server_name": sni,
                "utls": { "enabled": true, "fingerprint": l.param("fp").unwrap_or("chrome") },
                "reality": { "enabled": true, "public_key": pbk, "short_id": l.param("sid").unwrap_or("") }
            })))
        }
        other => Err(ConfigError::Security(other.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "11111111-2222-3333-4444-555555555555";
    const IP: &str = "203.0.113.7";

    fn link(q: &str) -> VlessLink {
        VlessLink::parse(&format!("vless://{ID}@vpn.example.org:443?{q}")).unwrap()
    }

    fn cfg(q: &str) -> Value {
        build(&link(q), IP.parse().unwrap(), &TunOptions::default()).unwrap()
    }

    #[test]
    fn reality_outbound_uses_resolved_ip_and_keeps_sni() {
        let c = cfg("type=tcp&security=reality&sni=www.example.com&pbk=PUB&sid=ab12&fp=firefox&flow=xtls-rprx-vision");
        let o = &c["outbounds"][0];
        assert_eq!(o["server"], IP);
        assert_eq!(o["server_port"], 443);
        assert_eq!(o["flow"], "xtls-rprx-vision");
        assert_eq!(o["tls"]["server_name"], "www.example.com");
        assert_eq!(o["tls"]["reality"]["public_key"], "PUB");
        assert_eq!(o["tls"]["reality"]["short_id"], "ab12");
        assert_eq!(o["tls"]["utls"]["fingerprint"], "firefox");
        assert!(o.get("transport").is_none());
    }

    #[test]
    fn server_is_excluded_from_tunnel_routes() {
        let c = cfg("type=tcp");
        assert_eq!(c["inbounds"][0]["route_exclude_address"][0], "203.0.113.7/32");
        let v6 = build(&link("type=tcp"), "2001:db8::5".parse().unwrap(), &TunOptions::default()).unwrap();
        assert_eq!(v6["inbounds"][0]["route_exclude_address"][0], "2001:db8::5/128");
    }

    #[test]
    fn full_tunnel_with_dns_and_ipv6_protection() {
        let c = cfg("type=tcp");
        let tun = &c["inbounds"][0];
        assert_eq!(tun["auto_route"], true);
        assert_eq!(tun["strict_route"], true);
        // An IPv6 address on the adapter is what makes the OS send IPv6 into the tunnel instead of around it.
        assert!(tun["address"].as_array().unwrap().iter().any(|a| a.as_str().unwrap().contains(':')));
        assert_eq!(c["route"]["final"], PROXY_TAG);
        assert_eq!(c["dns"]["servers"][0]["detour"], PROXY_TAG);
        assert!(c["route"]["rules"].as_array().unwrap().iter().any(|r| r["action"] == "hijack-dns"));
    }

    #[test]
    fn strict_route_can_be_turned_off() {
        let o = TunOptions { strict_route: false, ..Default::default() };
        let c = build(&link("type=tcp"), IP.parse().unwrap(), &o).unwrap();
        assert_eq!(c["inbounds"][0]["strict_route"], false);
    }

    #[test]
    fn websocket_and_tls() {
        let c = cfg("type=ws&path=%2Fws&host=cdn.example.org&security=tls&sni=cdn.example.org&fp=chrome&alpn=h2,http/1.1");
        let o = &c["outbounds"][0];
        assert_eq!(o["transport"]["type"], "ws");
        assert_eq!(o["transport"]["path"], "/ws");
        assert_eq!(o["transport"]["headers"]["Host"], "cdn.example.org");
        assert_eq!(o["tls"]["alpn"], json!(["h2", "http/1.1"]));
        assert_eq!(o["tls"]["insecure"], false);
    }

    #[test]
    fn grpc_and_httpupgrade() {
        assert_eq!(cfg("type=grpc&serviceName=svc")["outbounds"][0]["transport"]["service_name"], "svc");
        assert_eq!(cfg("type=httpupgrade&path=/u")["outbounds"][0]["transport"]["type"], "httpupgrade");
    }

    #[test]
    fn rejects_unsupported_transport_security_and_missing_key() {
        let ip: IpAddr = IP.parse().unwrap();
        let o = TunOptions::default();
        assert_eq!(build(&link("type=kcp"), ip, &o).unwrap_err(), ConfigError::Transport("kcp".into()));
        assert_eq!(build(&link("security=xtls"), ip, &o).unwrap_err(), ConfigError::Security("xtls".into()));
        assert_eq!(build(&link("security=reality"), ip, &o).unwrap_err(), ConfigError::NoRealityKey);
    }

    #[test]
    fn plain_tcp_has_no_tls() {
        assert!(cfg("type=tcp&security=none")["outbounds"][0].get("tls").is_none());
    }
}
