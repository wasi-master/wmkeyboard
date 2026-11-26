//! A plugin folder: its manifest, its modules and the session config the
//! keyboard would build for it. Every command reads plugins through here.

use std::path::{Component, Path, PathBuf};

use wm_python::interp::PyError;

pub struct Manifest {
    pub id: String,
    pub version: String,
    pub entry: String,
    pub permissions: Vec<String>,
    pub api: i64,
    /// False for a plugin made of selection actions alone (`"panel": false`).
    pub panel: bool,
    /// Each `previews` entry's id and `maxBytes`.
    pub previews: Vec<(String, usize)>,
}

/// A preview's `maxBytes` when the manifest gives none.
pub const DEFAULT_PREVIEW_BYTES: usize = 4 << 20;

pub fn manifest(dir: &Path) -> Result<Manifest, String> {
    let path = dir.join("plugin.json");
    let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
    let entry = s("entry");
    Ok(Manifest {
        id: s("id"),
        version: s("version"),
        entry: if entry.is_empty() { "main.py".into() } else { entry },
        permissions: v
            .get("permissions")
            .and_then(|p| p.as_array())
            .map(|a| a.iter().filter_map(|x| x.as_str()).map(String::from).collect())
            .unwrap_or_default(),
        api: v.get("apiVersion").and_then(|x| x.as_i64()).unwrap_or(4),
        panel: v.get("panel").and_then(|x| x.as_bool()).unwrap_or(true),
        previews: v
            .get("previews")
            .and_then(|p| p.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|p| {
                        let id = p.get("id")?.as_str()?.to_string();
                        let max = p.get("maxBytes").and_then(|m| m.as_u64()).map_or(DEFAULT_PREVIEW_BYTES, |m| m as usize);
                        Some((id, max))
                    })
                    .collect()
            })
            .unwrap_or_default(),
    })
}

/// Every `.py` file under [dir] but the entry and the test, as
/// `{"name.py": source, "pkg/mod.py": source}`.
pub fn modules(dir: &Path, entry: &str) -> serde_json::Map<String, serde_json::Value> {
    let mut out = serde_json::Map::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(read) = std::fs::read_dir(&d) else { continue };
        for item in read.flatten() {
            let path = item.path();
            let Ok(rel) = path.strip_prefix(dir) else { continue };
            let name = rel.to_string_lossy().replace('\\', "/");
            if name.starts_with('.') || name.starts_with("tests") {
                continue;
            }
            if path.is_dir() {
                stack.push(path);
            } else if name.ends_with(".py") && name != entry && name != "test.py" {
                if let Ok(text) = std::fs::read_to_string(&path) {
                    out.insert(name, serde_json::Value::String(text));
                }
            }
        }
    }
    out
}

/// The session config the keyboard would build for the plugin in [dir].
pub fn session_config(dir: &Path, m: &Manifest, settings: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "id": m.id, "version": m.version, "permissions": m.permissions, "mode": if m.panel { "panel" } else { "action" },
        "apiVersion": m.api, "files": modules(dir, &m.entry), "settings": settings,
        "context": {"locale": "en", "theme": "light", "surface": "panel"},
    })
}

/// A file inside [dir], or why not: absolute paths and `..` are refused, and
/// so is anything a symlink leads out of the folder.
pub fn inside(dir: &Path, name: &str) -> Result<PathBuf, String> {
    let rel = Path::new(name);
    if name.is_empty() || rel.components().any(|c| !matches!(c, Component::Normal(_))) {
        return Err(format!("'{name}' is not a file inside the plugin folder"));
    }
    let root = dir.canonicalize().map_err(|e| e.to_string())?;
    let path = root.join(rel).canonicalize().map_err(|_| format!("no file '{name}' in the plugin folder"))?;
    if !path.starts_with(&root) {
        return Err(format!("'{name}' is not a file inside the plugin folder"));
    }
    Ok(path)
}

pub fn read_inside(dir: &Path, name: &str) -> Result<String, PyError> {
    let path = inside(dir, name).map_err(|m| PyError::new(m, "ValueError"))?;
    std::fs::read_to_string(&path).map_err(|e| PyError::new(format!("cannot read '{name}': {e}"), "ValueError"))
}

