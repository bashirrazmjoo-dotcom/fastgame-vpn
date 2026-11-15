//! DTOs of the FastGame backend API (`backend/app/schemas/`). Field names are the wire names.

use chrono::{DateTime, NaiveDateTime};
use serde::{Deserialize, Deserializer};

/// The API sends ISO-8601 timestamps with or without a zone offset (naive ones are UTC).
fn de_time_opt<'de, D: Deserializer<'de>>(d: D) -> Result<Option<i64>, D::Error> {
    let s: Option<String> = Option::deserialize(d)?;
    let Some(s) = s.filter(|s| !s.is_empty()) else { return Ok(None) };
    parse_iso_millis(&s).map(Some).ok_or_else(|| serde::de::Error::custom(format!("bad timestamp: {s}")))
}

pub fn parse_iso_millis(s: &str) -> Option<i64> {
    if let Ok(t) = DateTime::parse_from_rfc3339(s) {
        return Some(t.timestamp_millis());
    }
    NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M:%S%.f").ok().map(|n| n.and_utc().timestamp_millis())
}

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub heartbeat_interval_seconds: u32,
    pub support_url: String,
    pub min_supported_version_code: u32,
    #[serde(default)]
    pub latest_version_code: u32,
    #[serde(default)]
    pub update_url: String,
    pub privacy_policy_url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Registration {
    pub user_code: String,
    pub device_token: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Quota {
    pub total_seconds: i64,
    pub used_seconds: i64,
    pub remaining_seconds: i64,
    #[serde(default, deserialize_with = "de_time_opt")]
    pub resets_at: Option<i64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Account {
    pub user_code: String,
    pub is_vip: bool,
    #[serde(default, deserialize_with = "de_time_opt")]
    pub vip_expires_at: Option<i64>,
    pub vip_lifetime: bool,
    pub is_blocked: bool,
    pub quota: Quota,
    pub vip_purchase_url: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct ServerInfo {
    pub id: u32,
    #[serde(default = "openvpn")]
    pub kind: String,
    pub name: String,
    pub country_code: String,
    pub country_name_en: String,
    pub country_name_fa: String,
}

fn openvpn() -> String {
    "openvpn".into()
}

#[derive(Debug, Clone, Deserialize)]
pub struct StartedSession {
    pub session_id: String,
    pub server_id: u32,
    pub heartbeat_interval_seconds: u32,
    pub is_vip: bool,
    /// `None` = unlimited (VIP).
    pub remaining_seconds: Option<i64>,
    #[serde(default, deserialize_with = "de_time_opt")]
    pub resets_at: Option<i64>,
    #[serde(default = "openvpn")]
    pub kind: String,
    #[serde(default)]
    pub ovpn_config: String,
    #[serde(default)]
    pub xray_link: Option<String>,
    #[serde(default)]
    pub auth_username: Option<String>,
    #[serde(default)]
    pub auth_password: Option<String>,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SessionStatus {
    Active,
    QuotaExhausted,
    Closed,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SessionState {
    pub status: SessionStatus,
    pub is_vip: bool,
    pub remaining_seconds: Option<i64>,
    #[serde(default, deserialize_with = "de_time_opt")]
    pub resets_at: Option<i64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_timestamps_with_and_without_offset() {
        assert_eq!(parse_iso_millis("2026-11-14T10:00:00+00:00"), Some(1_794_650_400_000));
        assert_eq!(parse_iso_millis("2026-11-14T10:00:00Z"), Some(1_794_650_400_000));
        assert_eq!(parse_iso_millis("2026-11-14T10:00:00"), Some(1_794_650_400_000));
        assert_eq!(parse_iso_millis("2026-11-14T10:00:00.250000"), Some(1_794_650_400_250));
        assert_eq!(parse_iso_millis("garbage"), None);
    }

    #[test]
    fn started_session_for_xray() {
        let s: StartedSession = serde_json::from_str(
            r#"{"session_id":"abc","server_id":3,"heartbeat_interval_seconds":30,"is_vip":false,
                "remaining_seconds":7200,"resets_at":"2026-11-15T10:00:00","kind":"xray","ovpn_config":"",
                "xray_link":"vless://x","auth_username":null,"auth_password":null}"#,
        )
        .unwrap();
        assert_eq!(s.kind, "xray");
        assert_eq!(s.remaining_seconds, Some(7200));
        assert!(s.resets_at.is_some());
        assert_eq!(s.xray_link.as_deref(), Some("vless://x"));
    }

    #[test]
    fn vip_session_has_no_remaining_time() {
        let s: SessionState =
            serde_json::from_str(r#"{"status":"quota_exhausted","is_vip":false,"remaining_seconds":0,"resets_at":null}"#).unwrap();
        assert_eq!(s.status, SessionStatus::QuotaExhausted);
        let v: SessionState = serde_json::from_str(r#"{"status":"active","is_vip":true,"remaining_seconds":null,"resets_at":null}"#).unwrap();
        assert_eq!(v.remaining_seconds, None);
    }

    #[test]
    fn server_kind_defaults_to_openvpn() {
        let s: ServerInfo =
            serde_json::from_str(r#"{"id":1,"name":"DE 1","country_code":"DE","country_name_en":"Germany","country_name_fa":"آلمان"}"#).unwrap();
        assert_eq!(s.kind, "openvpn");
    }
}
