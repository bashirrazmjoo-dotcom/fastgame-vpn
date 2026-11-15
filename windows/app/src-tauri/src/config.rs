//! Build-time constants. The backup route values are the same as in the Android app
//! (android/app/build.gradle.kts: API_FALLBACK_*); the fingerprint is a public certificate hash.

pub const API_BASE_URL: &str = "https://fg.razmjou.com/";
pub const API_FALLBACK_URL: &str = "https://93.126.31.20:24443/";
pub const API_FALLBACK_HOST: &str = "fg.razmjou.com";
pub const API_FALLBACK_CERT_SHA256: &str = "435B7DB62EB5E4DFA969EE216F935E1E8FE21F62393D5C38ACA464610796912E";

/// Version code sent to the backend: major*10000 + minor*100 + patch of Cargo's version.
pub fn version_code() -> u32 {
    let mut p = env!("CARGO_PKG_VERSION").split('.').map(|x| x.parse::<u32>().unwrap_or(0));
    let (a, b, c) = (p.next().unwrap_or(0), p.next().unwrap_or(0), p.next().unwrap_or(0));
    (a * 10_000 + b * 100 + c).max(1)
}
