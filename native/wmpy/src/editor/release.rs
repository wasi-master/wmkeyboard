//! The editor packages from the wmpy GitHub releases, checked against the
//! `.sha256` file beside each.
//!
//! The release that matches this wmpy's version is tried first, then the
//! latest. `WMPY_RELEASE_BASE` points at another folder of release files: a
//! URL, or a local folder (for the tests and for offline installs).

use std::time::Duration;

use sha2::{Digest, Sha256};

const RELEASES: &str = "https://github.com/wasi-master/wmpy/releases";
const MAX_ASSET: u64 = 200 << 20;

/// The bytes of [asset], after its SHA-256 matched.
pub fn fetch(asset: &str) -> Result<Vec<u8>, String> {
    if let Ok(base) = std::env::var("WMPY_RELEASE_BASE") {
        if !base.is_empty() {
            return fetch_from(base.trim_end_matches('/'), asset);
        }
    }
    let tagged = format!("{RELEASES}/download/v{}", env!("CARGO_PKG_VERSION"));
    match fetch_from(&tagged, asset) {
        Err(e) if e.contains("404") => fetch_from(&format!("{RELEASES}/latest/download"), asset),
        other => other,
    }
}

/// The bytes of [asset] in release `v`[version], after its SHA-256 matched.
pub fn fetch_version(version: &str, asset: &str) -> Result<Vec<u8>, String> {
    if let Ok(base) = std::env::var("WMPY_RELEASE_BASE") {
        if !base.is_empty() {
            return fetch_from(base.trim_end_matches('/'), asset);
        }
    }
    fetch_from(&format!("{RELEASES}/download/v{version}"), asset)
}

fn fetch_from(base: &str, asset: &str) -> Result<Vec<u8>, String> {
    let bytes = get(&format!("{base}/{asset}"))?;
    let sums = get(&format!("{base}/{asset}.sha256"))?;
    let want = sha256_in(&String::from_utf8_lossy(&sums), asset).ok_or_else(|| format!("{asset}.sha256 holds no SHA-256"))?;
    let got = sha256_hex(&bytes);
    if got != want {
        return Err(format!("{asset} does not match its SHA-256 (expected {want}, got {got})"));
    }
    Ok(bytes)
}

fn get(url: &str) -> Result<Vec<u8>, String> {
    if let Some(path) = url.strip_prefix("file://").or_else(|| (!url.contains("://")).then_some(url)) {
        return std::fs::read(path).map_err(|e| format!("{path}: {e} (404)"));
    }
    let agent = ureq::Agent::new_with_config(
        ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(300)))
            .https_only(true)
            .max_redirects(5)
            .http_status_as_error(false)
            .user_agent(concat!("wmpy/", env!("CARGO_PKG_VERSION")))
            .build(),
    );
    let mut res = agent.get(url).call().map_err(|e| format!("could not reach {url}: {e}"))?;
    let status = res.status().as_u16();
    if status != 200 {
        return Err(format!("{url}: HTTP {status}"));
    }
    res.body_mut().with_config().limit(MAX_ASSET).read_to_vec().map_err(|e| format!("could not read {url}: {e}"))
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// The digest for [asset] in a `.sha256` file: `<hex>  <name>` lines, or the hex alone.
pub fn sha256_in(text: &str, asset: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        let hex = parts.next()?;
        let name = parts.next().map(|n| n.trim_start_matches('*'));
        (hex.len() == 64 && hex.chars().all(|c| c.is_ascii_hexdigit()) && name.is_none_or(|n| n == asset)).then(|| hex.to_lowercase())
    })
}

/// Unpacks a zip into [dest], which must not exist yet. Entries that would
/// land outside it are refused.
pub fn unzip(bytes: &[u8], dest: &std::path::Path) -> Result<(), String> {
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|e| format!("not a zip file: {e}"))?;
    archive.extract(dest).map_err(|e| format!("unpacking into {}: {e}", dest.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_files_are_read_as_sha256sum_writes_them() {
        let hex = "ab".repeat(32);
        assert_eq!(sha256_in(&format!("{hex}  wmpy.vsix\n"), "wmpy.vsix"), Some(hex.clone()));
        assert_eq!(sha256_in(&format!("{}\n", hex.to_uppercase()), "x"), Some(hex.clone()));
        assert_eq!(sha256_in(&format!("{hex}  other.zip\n"), "wmpy.vsix"), None);
        assert_eq!(sha256_in("nothing", "x"), None);
        assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
