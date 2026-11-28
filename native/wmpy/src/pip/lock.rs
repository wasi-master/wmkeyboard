//! The `wmpy.lock` file `wmpy pip` keeps in a plugin folder, and the web
//! editor writes too:
//!
//! ```text
//! {"version": 1, "packages": {"<normalized-name>": {"version": str, "roots": [str],
//!   "files": [str], "source": url, "sha256": str, "license": str|null,
//!   "requires": [str], "verdict": "ok"|"warnings"|"incompatible", "problems": int,
//!   "requested"?: str, "hashes"?: {"<file>": sha256}}}}
//! ```
//!
//! `requested` is what was asked for, as typed (`python-dateutil>=2.8,<3`),
//! on a package installed by name rather than only as a dependency: `wmpy
//! pip update` stays inside it. `hashes` is each file's SHA-256 as written,
//! which `wmpy pip verify` compares the folder against. Both are left out
//! of locks written before them, and of what the web editor writes.
//!
//! `roots` are the top-level module and package names the package adds,
//! `files` every file it put in the folder (licence files included), and
//! `requires` the normalized names of the packages it needs that are in
//! the lock too.

use std::collections::BTreeMap;
use std::path::Path;

use serde_json::{json, Value as Json};

pub const FILE: &str = "wmpy.lock";

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Entry {
    pub version: String,
    pub roots: Vec<String>,
    pub files: Vec<String>,
    pub source: String,
    pub sha256: String,
    pub license: Option<String>,
    pub requires: Vec<String>,
    pub verdict: String,
    pub problems: u64,
    pub requested: Option<String>,
    pub hashes: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Default)]
pub struct Lock {
    pub packages: BTreeMap<String, Entry>,
}

impl Lock {
    /// The lock in [dir]; empty when there is none, an error when it cannot be read.
    pub fn read(dir: &Path) -> Result<Lock, String> {
        let path = dir.join(FILE);
        let text = match std::fs::read_to_string(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Lock::default()),
            Err(e) => return Err(format!("{}: {e}", path.display())),
        };
        let v: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
        if v.get("version").and_then(Json::as_i64) != Some(1) {
            return Err(format!("{} is not a version 1 wmpy.lock", path.display()));
        }
        let strings = |e: &Json, k: &str| -> Vec<String> {
            e.get(k).and_then(Json::as_array).map(|a| a.iter().filter_map(Json::as_str).map(String::from).collect()).unwrap_or_default()
        };
        let mut packages = BTreeMap::new();
        if let Some(map) = v.get("packages").and_then(Json::as_object) {
            for (name, e) in map {
                let s = |k: &str| e.get(k).and_then(Json::as_str).unwrap_or("").to_string();
                packages.insert(
                    name.clone(),
                    Entry {
                        version: s("version"),
                        roots: strings(e, "roots"),
                        files: strings(e, "files"),
                        source: s("source"),
                        sha256: s("sha256"),
                        license: e.get("license").and_then(Json::as_str).map(String::from),
                        requires: strings(e, "requires"),
                        verdict: s("verdict"),
                        problems: e.get("problems").and_then(Json::as_u64).unwrap_or(0),
                        requested: e.get("requested").and_then(Json::as_str).filter(|r| !r.is_empty()).map(String::from),
                        hashes: e
                            .get("hashes")
                            .and_then(Json::as_object)
                            .map(|m| m.iter().filter_map(|(f, h)| h.as_str().map(|h| (f.clone(), h.to_string()))).collect())
                            .unwrap_or_default(),
                    },
                );
            }
        }
        Ok(Lock { packages })
    }

    pub fn to_json(&self) -> Json {
        let packages: serde_json::Map<String, Json> = self
            .packages
            .iter()
            .map(|(k, e)| {
                let mut v = json!({
                    "version": e.version, "roots": e.roots, "files": e.files, "source": e.source, "sha256": e.sha256,
                    "license": e.license, "requires": e.requires, "verdict": e.verdict, "problems": e.problems,
                });
                if let Some(r) = &e.requested {
                    v["requested"] = json!(r);
                }
                if !e.hashes.is_empty() {
                    v["hashes"] = json!(e.hashes);
                }
                (k.clone(), v)
            })
            .collect();
        json!({"version": 1, "packages": packages})
    }

    /// Writes the lock, or removes the file when nothing is vendored.
    pub fn write(&self, dir: &Path) -> Result<(), String> {
        let path = dir.join(FILE);
        if self.packages.is_empty() {
            return match std::fs::remove_file(&path) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(format!("{}: {e}", path.display())),
                _ => Ok(()),
            };
        }
        let text = serde_json::to_string_pretty(&self.to_json()).expect("json") + "\n";
        std::fs::write(&path, text).map_err(|e| format!("{}: {e}", path.display()))
    }

    /// The packages asked for by name: those with `requested`, and (as in a
    /// lock written before it) those nothing else needs.
    pub fn direct(&self) -> Vec<String> {
        self.packages.iter().filter(|(k, e)| e.requested.is_some() || self.dependents(k).is_empty()).map(|(k, _)| k.clone()).collect()
    }

    /// The packages that need [key].
    pub fn dependents(&self, key: &str) -> Vec<String> {
        self.packages.iter().filter(|(_, e)| e.requires.iter().any(|r| r == key)).map(|(k, _)| k.clone()).collect()
    }
}

/// Top-level module and package names that `wmpy pip` vendored into the
/// plugin in [dir], from its `wmpy.lock`; empty when there is none.
pub fn vendored_roots(dir: &Path) -> Vec<String> {
    let Ok(lock) = Lock::read(dir) else { return Vec::new() };
    let mut roots: Vec<String> = lock.packages.values().flat_map(|e| e.roots.iter().cloned()).collect();
    roots.sort();
    roots.dedup();
    roots
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let dir = std::env::temp_dir().join(format!("wmpy-lock-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut lock = Lock::default();
        lock.packages.insert(
            "python-dateutil".into(),
            Entry {
                version: "2.9.0".into(),
                roots: vec!["dateutil".into()],
                requires: vec!["six".into()],
                verdict: "warnings".into(),
                problems: 3,
                requested: Some("python-dateutil>=2.8,<3".into()),
                hashes: [("dateutil/__init__.py".to_string(), "ab".repeat(32))].into_iter().collect(),
                ..Default::default()
            },
        );
        lock.packages.insert("six".into(), Entry { version: "1.16.0".into(), roots: vec!["six".into()], verdict: "ok".into(), ..Default::default() });
        lock.write(&dir).unwrap();
        let back = Lock::read(&dir).unwrap();
        assert_eq!(back.packages, lock.packages);
        assert_eq!(vendored_roots(&dir), vec!["dateutil", "six"]);
        assert_eq!(back.dependents("six"), vec!["python-dateutil"]);
        assert_eq!(back.direct(), vec!["python-dateutil"]);
        let v: Json = serde_json::from_str(&std::fs::read_to_string(dir.join(FILE)).unwrap()).unwrap();
        assert_eq!(v["packages"]["six"]["license"], Json::Null);
        Lock::default().write(&dir).unwrap();
        assert!(!dir.join(FILE).exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
