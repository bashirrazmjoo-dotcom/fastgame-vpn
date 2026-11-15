//! Parsing of the `vless://uuid@host:port?query#name` link the backend hands out
//! (`xray_link`). Mirrors `XrayLink.kt` of the Android app.

use std::collections::BTreeMap;

use url::Url;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum LinkError {
    #[error("Unsupported server link.")]
    UnsupportedScheme,
    #[error("The server link is invalid.")]
    Invalid,
    #[error("The server link is incomplete.")]
    Incomplete,
}

/// A parsed VLESS link. `params` holds the decoded query (`type`, `security`, `sni`, `pbk`, ...).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VlessLink {
    pub uuid: String,
    /// Host as written in the link: a domain name or an IP literal (no brackets for IPv6).
    pub host: String,
    pub port: u16,
    pub params: BTreeMap<String, String>,
}

impl VlessLink {
    pub fn parse(link: &str) -> Result<Self, LinkError> {
        let link = link.trim();
        if !link.starts_with("vless://") {
            return Err(LinkError::UnsupportedScheme);
        }
        let url = Url::parse(link).map_err(|_| LinkError::Invalid)?;

        let host = url.host_str().unwrap_or("").trim_matches(|c| c == '[' || c == ']').to_string();
        let user = percent_decode(url.username());
        let port = url.port().filter(|p| *p != 0);
        let (Some(port), false, false) = (port, host.is_empty(), user.is_empty()) else {
            return Err(LinkError::Incomplete);
        };
        // sing-box and Xray both need a real UUID; failing here gives a clear message early.
        if uuid::Uuid::parse_str(&user).is_err() {
            return Err(LinkError::Invalid);
        }

        let params = url
            .query_pairs()
            .filter(|(k, _)| !k.is_empty())
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        Ok(Self { uuid: user, host, port, params })
    }

    pub fn param(&self, key: &str) -> Option<&str> {
        self.params.get(key).map(String::as_str).filter(|v| !v.is_empty())
    }
}

fn percent_decode(s: &str) -> String {
    percent_encoding::percent_decode_str(s).decode_utf8_lossy().into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "11111111-2222-3333-4444-555555555555";

    #[test]
    fn parses_reality_link() {
        let l = VlessLink::parse(&format!(
            "vless://{ID}@example.org:443?type=tcp&security=reality&sni=www.example.com&pbk=KEY&sid=ab12&fp=chrome&flow=xtls-rprx-vision#Germany"
        ))
        .unwrap();
        assert_eq!(l.uuid, ID);
        assert_eq!(l.host, "example.org");
        assert_eq!(l.port, 443);
        assert_eq!(l.param("security"), Some("reality"));
        assert_eq!(l.param("pbk"), Some("KEY"));
        assert_eq!(l.param("flow"), Some("xtls-rprx-vision"));
    }

    #[test]
    fn decodes_query_values() {
        let l = VlessLink::parse(&format!("vless://{ID}@1.2.3.4:8443?type=ws&path=%2Fa%2Fb&host=h.example")).unwrap();
        assert_eq!(l.param("path"), Some("/a/b"));
    }

    #[test]
    fn accepts_ipv6_literal_without_brackets() {
        let l = VlessLink::parse(&format!("vless://{ID}@[2001:db8::1]:443?type=tcp")).unwrap();
        assert_eq!(l.host, "2001:db8::1");
    }

    #[test]
    fn rejects_other_schemes_and_incomplete_links() {
        assert_eq!(VlessLink::parse("vmess://abc"), Err(LinkError::UnsupportedScheme));
        assert_eq!(VlessLink::parse(&format!("vless://{ID}@example.org?type=tcp")), Err(LinkError::Incomplete));
        assert_eq!(VlessLink::parse("vless://@example.org:443"), Err(LinkError::Incomplete));
        assert_eq!(VlessLink::parse("vless://not-a-uuid@example.org:443"), Err(LinkError::Invalid));
    }

    #[test]
    fn trims_whitespace() {
        assert!(VlessLink::parse(&format!("  vless://{ID}@example.org:443  \n")).is_ok());
    }
}
