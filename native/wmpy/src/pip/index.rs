//! PyPI, read through its JSON API (`https://pypi.org/pypi/<name>/json`)
//! and downloaded from `files.pythonhosted.org`, nowhere else. Every file is
//! checked against the sha256 PyPI lists before it is used, and kept in the
//! user's cache folder by that hash, so a second install needs no network.
//! The project JSON is cached too and used when the network is down.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::Value as Json;
use sha2::{Digest, Sha256};

use super::version::{normalize, Specifier, Version};

/// The largest file wmpy downloads.
const MAX_DOWNLOAD: u64 = 64 << 20;
/// A project's JSON can be large for projects with thousands of files.
const MAX_JSON: u64 = 32 << 20;

/// One file of a release.
#[derive(Clone, Debug)]
pub struct FileInfo {
    pub filename: String,
    pub url: String,
    pub sha256: String,
    /// `bdist_wheel` or `sdist`.
    pub packagetype: String,
    pub requires_python: Option<String>,
    pub yanked: bool,
    pub size: u64,
}

impl FileInfo {
    fn from_json(v: &Json) -> Option<FileInfo> {
        Some(FileInfo {
            filename: v.get("filename")?.as_str()?.to_string(),
            url: v.get("url")?.as_str()?.to_string(),
            sha256: v.get("digests")?.get("sha256")?.as_str()?.to_ascii_lowercase(),
            packagetype: v.get("packagetype").and_then(Json::as_str).unwrap_or("").to_string(),
            requires_python: v.get("requires_python").and_then(Json::as_str).map(String::from).filter(|s| !s.trim().is_empty()),
            yanked: v.get("yanked").and_then(Json::as_bool).unwrap_or(false),
            size: v.get("size").and_then(Json::as_u64).unwrap_or(0),
        })
    }

    pub fn is_wheel(&self) -> bool {
        self.filename.ends_with(".whl")
    }

    pub fn is_sdist(&self) -> bool {
        self.packagetype == "sdist" || self.filename.ends_with(".tar.gz") || self.filename.ends_with(".tgz") || (self.filename.ends_with(".zip") && !self.is_wheel())
    }

    /// A wheel that runs on any Python 3 on any platform: `py3-none-any`,
    /// `py2.py3-none-any`, and the like.
    pub fn is_pure_wheel(&self) -> bool {
        let Some(stem) = self.filename.strip_suffix(".whl") else { return false };
        let parts: Vec<&str> = stem.split('-').collect();
        if parts.len() < 5 {
            return false;
        }
        let n = parts.len();
        let (py, abi, platform) = (parts[n - 3], parts[n - 2], parts[n - 1]);
        let py_ok = py.split('.').any(|t| t == "py3" || (t.starts_with("py3") && t[3..].chars().all(|c| c.is_ascii_digit())));
        py_ok && abi.split('.').any(|a| a == "none") && platform.split('.').any(|p| p == "any")
    }

    /// Whether the file's `Requires-Python` lets CPython 3.12 install it.
    pub fn fits_python(&self) -> bool {
        let Some(rp) = &self.requires_python else { return true };
        let py = Version::parse("3.12.0").expect("version");
        Specifier::parse(rp).map(|s| s.contains(&py)).unwrap_or(true)
    }
}

/// A project as PyPI lists it.
pub struct Project {
    /// The name as the project spells it.
    pub name: String,
    pub releases: Vec<(Version, String, Vec<FileInfo>)>,
    /// When the JSON came from the cache because the network failed.
    pub stale: bool,
}

pub struct Index {
    agent: ureq::Agent,
    cache: Option<PathBuf>,
}

/// The cache folder: `~/Library/Caches/wmpy` on macOS, `$XDG_CACHE_HOME/wmpy`
/// (or `~/.cache/wmpy`) on Linux, `%LOCALAPPDATA%\wmpy\cache` on Windows.
/// `WMPY_CACHE_DIR` overrides it.
pub fn cache_dir() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("WMPY_CACHE_DIR").filter(|p| !p.is_empty()) {
        return Some(PathBuf::from(p));
    }
    if cfg!(windows) {
        return std::env::var_os("LOCALAPPDATA").map(|p| PathBuf::from(p).join("wmpy").join("cache"));
    }
    let home = std::env::var_os("HOME").map(PathBuf::from);
    if cfg!(target_os = "macos") {
        return home.map(|h| h.join("Library").join("Caches").join("wmpy"));
    }
    match std::env::var_os("XDG_CACHE_HOME").filter(|p| !p.is_empty()) {
        Some(x) => Some(PathBuf::from(x).join("wmpy")),
        None => home.map(|h| h.join(".cache").join("wmpy")),
    }
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// Whether [url] is a file on PyPI's file host, the only place downloads come from.
fn allowed(url: &str) -> bool {
    url.strip_prefix("https://files.pythonhosted.org/").is_some()
}

impl Index {
    pub fn new() -> Index {
        let config = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(120)))
            .https_only(true)
            .max_redirects(3)
            .http_status_as_error(false)
            .user_agent(concat!("wmpy/", env!("CARGO_PKG_VERSION")))
            .build();
        Index { agent: ureq::Agent::new_with_config(config), cache: cache_dir() }
    }

    fn get(&self, url: &str, limit: u64) -> Result<(u16, Vec<u8>), String> {
        let mut res = self.agent.get(url).call().map_err(|e| format!("could not reach {url}: {e}"))?;
        let status = res.status().as_u16();
        let body = res.body_mut().with_config().limit(limit).read_to_vec().map_err(|e| format!("could not read {url}: {e}"))?;
        Ok((status, body))
    }

    /// Every release of [name] with its files.
    pub fn project(&self, name: &str) -> Result<Project, String> {
        let key = normalize(name);
        if key.is_empty() || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
            return Err(format!("'{name}' is not a package name"));
        }
        let url = format!("https://pypi.org/pypi/{key}/json");
        let cached = self.cache.as_ref().map(|c| c.join("json").join(format!("{key}.json")));
        let (text, stale) = match self.get(&url, MAX_JSON) {
            Ok((200, body)) => {
                if let Some(path) = &cached {
                    let _ = std::fs::create_dir_all(path.parent().expect("parent"));
                    let _ = std::fs::write(path, &body);
                }
                (body, false)
            }
            Ok((404, _)) => return Err(format!("PyPI has no package named '{name}'")),
            Ok((status, _)) => return Err(format!("PyPI answered {status} for '{name}'")),
            Err(e) => match cached.as_ref().and_then(|p| std::fs::read(p).ok()) {
                Some(body) => (body, true),
                None => return Err(e),
            },
        };
        let v: Json = serde_json::from_slice(&text).map_err(|e| format!("PyPI's answer for '{name}' is not JSON: {e}"))?;
        let display = v.pointer("/info/name").and_then(Json::as_str).unwrap_or(name).to_string();
        let mut releases = Vec::new();
        match v.get("releases").and_then(Json::as_object) {
            Some(map) => {
                for (raw, files) in map {
                    let Some(version) = Version::parse(raw) else { continue };
                    let files: Vec<FileInfo> = files.as_array().map(|a| a.iter().filter_map(FileInfo::from_json).collect()).unwrap_or_default();
                    releases.push((version, raw.clone(), files));
                }
            }
            // The deprecated `releases` key gone: the latest release alone.
            None => {
                let raw = v.pointer("/info/version").and_then(Json::as_str).unwrap_or("").to_string();
                if let Some(version) = Version::parse(&raw) {
                    let files = v.get("urls").and_then(Json::as_array).map(|a| a.iter().filter_map(FileInfo::from_json).collect()).unwrap_or_default();
                    releases.push((version, raw, files));
                }
            }
        }
        releases.sort_by(|a, b| b.0.cmp(&a.0));
        Ok(Project { name: display, releases, stale })
    }

    /// The bytes of [file], from the cache or PyPI, checked against its sha256.
    pub fn download(&self, file: &FileInfo) -> Result<Vec<u8>, String> {
        if file.sha256.len() != 64 || !file.sha256.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(format!("PyPI lists no sha256 for {}", file.filename));
        }
        let safe: String = file.filename.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '_' }).collect();
        let cached = self.cache.as_ref().map(|c| c.join("files").join(format!("{}-{safe}", &file.sha256[..16])));
        if let Some(bytes) = cached.as_ref().and_then(|p| std::fs::read(p).ok()) {
            if sha256_hex(&bytes) == file.sha256 {
                return Ok(bytes);
            }
        }
        if !allowed(&file.url) {
            return Err(format!("{} is not on files.pythonhosted.org, so wmpy does not fetch it", file.url));
        }
        if file.size > MAX_DOWNLOAD {
            return Err(format!("{} is {} MB, over the {} MB wmpy downloads", file.filename, file.size >> 20, MAX_DOWNLOAD >> 20));
        }
        let (status, bytes) = self.get(&file.url, MAX_DOWNLOAD)?;
        if status != 200 {
            return Err(format!("files.pythonhosted.org answered {status} for {}", file.filename));
        }
        let got = sha256_hex(&bytes);
        if got != file.sha256 {
            return Err(format!("{} does not match the sha256 PyPI lists (got {got}, want {}); nothing was installed", file.filename, file.sha256));
        }
        if let Some(path) = &cached {
            let _ = std::fs::create_dir_all(path.parent().expect("parent"));
            let _ = std::fs::write(path, &bytes);
        }
        Ok(bytes)
    }
}

/// Reads a local wheel or sdist for `wmpy pip check <file>`.
pub fn read_local(path: &Path) -> Result<(FileInfo, Vec<u8>), String> {
    let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let filename = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let info = FileInfo {
        packagetype: if filename.ends_with(".whl") { "bdist_wheel".into() } else { "sdist".into() },
        url: path.display().to_string(),
        sha256: sha256_hex(&bytes),
        requires_python: None,
        yanked: false,
        size: bytes.len() as u64,
        filename,
    };
    Ok((info, bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(name: &str) -> FileInfo {
        FileInfo { filename: name.into(), url: String::new(), sha256: String::new(), packagetype: String::new(), requires_python: None, yanked: false, size: 0 }
    }

    #[test]
    fn pure_wheels_are_told_apart() {
        assert!(file("six-1.16.0-py2.py3-none-any.whl").is_pure_wheel());
        assert!(file("humanize-4.9.0-py3-none-any.whl").is_pure_wheel());
        assert!(!file("numpy-2.1.0-cp312-cp312-macosx_14_0_arm64.whl").is_pure_wheel());
        assert!(!file("thing-1.0-py2-none-any.whl").is_pure_wheel());
        assert!(!file("speed-1.0-cp312-abi3-any.whl").is_pure_wheel());
        let mut f = file("x-1.0-py3-none-any.whl");
        f.requires_python = Some(">=3.8".into());
        assert!(f.fits_python());
        f.requires_python = Some(">=3.13".into());
        assert!(!f.fits_python());
        assert!(allowed("https://files.pythonhosted.org/packages/aa/x.whl") && !allowed("https://evil.example/files.pythonhosted.org/x"));
    }
}
