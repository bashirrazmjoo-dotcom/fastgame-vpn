//! Self-update from the rolling `windows-latest` GitHub release.
//!
//! The release notes carry `commit <sha>` and `sha256: <hex>` (written by CI). The app compares the
//! commit with the one it was built from, downloads `FastGame-Setup.exe` from the fixed release URL
//! and checks its SHA-256 before anything is started.

use std::path::PathBuf;
use std::time::Duration;

use serde_json::Value;
use sha2::{Digest, Sha256};

pub const RELEASE_API: &str = "https://api.github.com/repos/bashirrazmjoo-dotcom/fastgame-vpn/releases/tags/windows-latest";
const DOWNLOAD_PREFIX: &str = "https://github.com/bashirrazmjoo-dotcom/fastgame-vpn/releases/download/windows-latest/";
pub const ASSET: &str = "FastGame-Setup.exe";
const MAX_BYTES: usize = 200 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReleaseInfo {
    pub commit: String,
    pub sha256: String,
    pub url: String,
}

/// Hex run (`min..=max` chars) that follows `marker` in `text`, lowercased.
fn hex_after(text: &str, marker: &str, min: usize, max: usize) -> Option<String> {
    let rest = &text[text.find(marker)? + marker.len()..];
    let rest = rest.trim_start_matches(|c: char| c == ' ' || c == '`' || c == ':');
    let hex: String = rest.chars().take_while(|c| c.is_ascii_hexdigit()).collect();
    (hex.len() >= min && hex.len() <= max).then(|| hex.to_lowercase())
}

pub fn parse_release(v: &Value) -> Option<ReleaseInfo> {
    let body = v.get("body")?.as_str()?;
    let commit = hex_after(body, "commit ", 7, 40)?;
    let sha256 = hex_after(body, "sha256:", 64, 64)?;
    let url = v
        .get("assets")?
        .as_array()?
        .iter()
        .find(|a| a.get("name").and_then(Value::as_str) == Some(ASSET))?
        .get("browser_download_url")?
        .as_str()?
        .to_string();
    url.starts_with(DOWNLOAD_PREFIX).then_some(ReleaseInfo { commit, sha256, url })
}

/// A development build (no commit baked in) never offers updates.
pub fn is_update(current: &str, latest: &str) -> bool {
    let (c, l) = (current.trim().to_lowercase(), latest.trim().to_lowercase());
    if c.len() < 7 || l.len() < 7 || !c.chars().all(|x| x.is_ascii_hexdigit()) {
        return false;
    }
    let n = c.len().min(l.len());
    c[..n] != l[..n]
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent("FastGame-Windows")
        .timeout(Duration::from_secs(300))
        .connect_timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())
}

pub async fn fetch_latest() -> Result<ReleaseInfo, String> {
    let res = client()?.get(RELEASE_API).header("Accept", "application/vnd.github+json").send().await.map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(format!("HTTP {}", res.status().as_u16()));
    }
    let v: Value = res.json().await.map_err(|e| e.to_string())?;
    parse_release(&v).ok_or_else(|| "unexpected release format".to_string())
}

/// Downloads the installer into the temp folder and verifies its hash.
pub async fn download(info: &ReleaseInfo) -> Result<PathBuf, String> {
    if !info.url.starts_with(DOWNLOAD_PREFIX) {
        return Err("untrusted download address".into());
    }
    let res = client()?.get(&info.url).send().await.map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(format!("HTTP {}", res.status().as_u16()));
    }
    let bytes = res.bytes().await.map_err(|e| e.to_string())?;
    if bytes.is_empty() || bytes.len() > MAX_BYTES {
        return Err("unexpected download size".into());
    }
    if sha256_hex(&bytes) != info.sha256 {
        return Err("checksum mismatch".into());
    }
    let path = std::env::temp_dir().join(format!("FastGame-Setup-{}.exe", &info.commit));
    std::fs::write(&path, &bytes).map_err(|e| e.to_string())?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const SHA: &str = "99185d75d314a07dbba4a130c8ad986cbeb629a07927975636b341ca61f991df";

    fn release(body: &str, url: &str) -> Value {
        json!({ "body": body, "assets": [{ "name": ASSET, "browser_download_url": url }] })
    }

    #[test]
    fn parses_ci_release_notes() {
        let url = format!("{DOWNLOAD_PREFIX}{ASSET}");
        let r = parse_release(&release(&format!("Automatic build of the latest main commit 59ea8c5. Unsigned.\n\nsha256: {SHA}"), &url)).unwrap();
        assert_eq!(r, ReleaseInfo { commit: "59ea8c5".into(), sha256: SHA.into(), url });
    }

    #[test]
    fn rejects_foreign_urls_and_missing_hash() {
        let body = format!("commit 59ea8c5\nsha256: {SHA}");
        assert!(parse_release(&release(&body, "https://evil.example/FastGame-Setup.exe")).is_none());
        let ok = format!("{DOWNLOAD_PREFIX}{ASSET}");
        assert!(parse_release(&release("commit 59ea8c5", &ok)).is_none());
        assert!(parse_release(&release(&format!("sha256: {SHA}"), &ok)).is_none());
        assert!(parse_release(&json!({ "body": body, "assets": [] })).is_none());
    }

    #[test]
    fn update_detection() {
        assert!(is_update("59ea8c5d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7", "3f816d6"));
        assert!(!is_update("59ea8c5d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7", "59ea8c5"));
        assert!(!is_update("dev", "3f816d6"), "dev builds never prompt");
        assert!(!is_update("", "3f816d6"));
    }

    #[test]
    fn hash_helper() {
        assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
