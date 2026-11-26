//! A plugin folder: its manifest, its files, its modules and tests, and the
//! session config the keyboard would build for it. Every command reads
//! plugins through here.

use std::path::{Component, Path, PathBuf};

use wm_python::interp::PyError;
use wm_python_tooling::manifest as codec;

use crate::diag::Diag;

/// What the runners need from plugin.json, read leniently: a test or a
/// render runs on a manifest `wmpy check` would refuse. The app's own rules
/// are [wm_python_tooling::manifest::check].
pub struct Manifest {
    pub id: String,
    /// `pluginVersion` (or, in files written before it, a text `version`).
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
    let version = match s("pluginVersion") {
        v if v.is_empty() => s("version"),
        v => v,
    };
    Ok(Manifest {
        id: s("id"),
        version,
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

/// Folders no command looks inside: version control, editors, caches.
fn skipped_dir(name: &str) -> bool {
    name.starts_with('.') || matches!(name, "__pycache__" | "node_modules" | "venv" | "target")
}

/// Every file under [dir], relative with `/`, sorted; dot folders,
/// `__pycache__`, `node_modules`, `venv` and `target` are not entered.
pub fn files(dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(read) = std::fs::read_dir(&d) else { continue };
        for item in read.flatten() {
            let path = item.path();
            let Ok(rel) = path.strip_prefix(dir) else { continue };
            let name = rel.to_string_lossy().replace('\\', "/");
            let Ok(kind) = item.file_type() else { continue };
            if kind.is_dir() {
                if !skipped_dir(&item.file_name().to_string_lossy()) {
                    stack.push(path);
                }
            } else if kind.is_file() || (kind.is_symlink() && path.is_file()) {
                out.push(name);
            }
        }
    }
    out.sort();
    out
}

/// Every `.py` file the keyboard loads as a module of the plugin in [dir]
/// (its other `.py` files, tests and dev files left out), as
/// `{"name.py": source, "pkg/mod.py": source}`.
pub fn modules(dir: &Path, entry: &str) -> serde_json::Map<String, serde_json::Value> {
    let mut out = serde_json::Map::new();
    for name in files(dir) {
        if !name.ends_with(".py") || name == entry || codec::is_dev_file(&name) {
            continue;
        }
        if let Ok(text) = std::fs::read_to_string(dir.join(&name)) {
            out.insert(name, serde_json::Value::String(text));
        }
    }
    out
}

/// The test files of the plugin in [dir]: `test.py`, then every
/// `tests/**/test_*.py`, sorted.
pub fn test_files(dir: &Path) -> Vec<String> {
    files(dir)
        .into_iter()
        .filter(|f| f == "test.py" || (f.starts_with("tests/") && f.rsplit('/').next().is_some_and(|n| n.starts_with("test_") && n.ends_with(".py"))))
        .collect()
}

/// The session config the keyboard would build for the plugin in [dir].
pub fn session_config(dir: &Path, m: &Manifest, settings: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "id": m.id, "version": m.version, "permissions": m.permissions, "mode": if m.panel { "panel" } else { "action" },
        "apiVersion": m.api, "entry": m.entry, "files": modules(dir, &m.entry), "settings": settings,
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

/// The plugin folder [given] names, or why it is not one. A path to a file
/// in the folder (plugin.json, main.py) names its folder.
pub fn plugin_dir(given: &Path) -> Result<PathBuf, Diag> {
    let dir = if given.is_file() { given.parent().map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from(".")) } else { given.to_path_buf() };
    let dir = if dir.as_os_str().is_empty() { PathBuf::from(".") } else { dir };
    if !dir.is_dir() {
        let mut d = Diag::error(format!("there is no folder {}", given.display()));
        d.help = Some("give the plugin's folder, the one that holds plugin.json".into());
        return Err(d);
    }
    if dir.join("plugin.json").is_file() {
        return Ok(dir);
    }
    let mut d = Diag::error(format!("{} has no plugin.json, so it is not a plugin folder", shown(&dir)));
    let nested: Vec<String> = std::fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().join("plugin.json").is_file())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    d.help = Some(if let Some(first) = nested.first() {
        let more = if nested.len() > 1 { format!(" (or {})", nested[1..].join(", ")) } else { String::new() };
        format!("the plugin is one folder down: try {}{more}", shown(&dir.join(first)))
    } else if dir.join("main.py").is_file() {
        format!("a plugin folder holds plugin.json beside main.py: {}", wm_python_tooling::manifest::DOCS.to_string() + "/plugins/wm-package/#the-manifest")
    } else {
        "make one with `wmpy new <folder>`".into()
    });
    Err(d)
}

/// [dir] as a person reads it: `.` for the folder they are in.
pub fn shown(dir: &Path) -> String {
    let s = dir.display().to_string();
    if s.is_empty() { ".".into() } else { s }
}
