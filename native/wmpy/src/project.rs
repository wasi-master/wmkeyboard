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

/// What a plugin is told about where it runs: `plugin.locale`,
/// `plugin.theme` (and `plugin.dark`) and `plugin.surface`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Context {
    /// A language tag: `en`, `bn`, `pt-BR`.
    pub locale: String,
    /// `light` or `dark`.
    pub theme: String,
    /// `panel` or `float` (`action` for a selection action's run).
    pub surface: String,
}

impl Default for Context {
    fn default() -> Context {
        Context { locale: "en".into(), theme: "light".into(), surface: "panel".into() }
    }
}

impl Context {
    pub fn dark(&self) -> bool {
        self.theme == "dark"
    }

    /// Whether the locale's script runs right to left.
    pub fn rtl(&self) -> bool {
        let lang = self.locale.split(['-', '_']).next().unwrap_or("").to_ascii_lowercase();
        matches!(lang.as_str(), "ar" | "fa" | "he" | "iw" | "ur" | "ps" | "sd" | "ug" | "yi" | "dv" | "ckb" | "syr")
    }

    pub fn json(&self) -> serde_json::Value {
        serde_json::json!({"locale": self.locale, "theme": self.theme, "surface": self.surface})
    }
}

static CONTEXT: std::sync::OnceLock<std::sync::Mutex<Context>> = std::sync::OnceLock::new();

fn context_cell() -> &'static std::sync::Mutex<Context> {
    CONTEXT.get_or_init(|| std::sync::Mutex::new(Context::default()))
}

/// The context every session this run makes starts from: what `--locale`,
/// `--theme` and `--surface` (or wmpy.toml) said, else English, light, panel.
pub fn context() -> Context {
    context_cell().lock().map(|c| c.clone()).unwrap_or_default()
}

pub fn set_context(c: Context) {
    if let Ok(mut held) = context_cell().lock() {
        *held = c;
    }
}

/// `bn`, `pt-BR`, `es-419`, `zh-Hant`: a tag `locales/<tag>.json` may have,
/// as PluginLocales.TAG reads it.
pub fn is_locale_tag(tag: &str) -> bool {
    let (lang, region) = match tag.split_once('-') {
        Some((l, r)) => (l, Some(r)),
        None => (tag, None),
    };
    (2..=3).contains(&lang.len())
        && lang.chars().all(|c| c.is_ascii_lowercase())
        && region.is_none_or(|r| (2..=8).contains(&r.len()) && r.chars().all(|c| c.is_ascii_alphanumeric()))
}

/// The tags of the plugin's `locales/<tag>.json` files.
pub fn locale_tags(dir: &Path) -> Vec<String> {
    let Ok(read) = std::fs::read_dir(dir.join("locales")) else { return Vec::new() };
    let mut tags: Vec<String> = read
        .flatten()
        .filter_map(|e| e.file_name().to_str().and_then(|n| n.strip_suffix(".json")).map(String::from))
        .filter(|t| is_locale_tag(t))
        .collect();
    tags.sort();
    tags
}

/// The tag among [available] the keyboard would use for [locale], as
/// PluginLocales.pick chooses: the whole tag, then the language, then any
/// tag of that language. None when the plugin's English stands.
pub fn pick_locale(available: &[String], locale: &str) -> Option<String> {
    let lang = locale.split(['-', '_']).next().unwrap_or(locale);
    let whole = locale.replace('_', "-");
    available
        .iter()
        .find(|t| t.eq_ignore_ascii_case(&whole))
        .or_else(|| available.iter().find(|t| t.eq_ignore_ascii_case(lang)))
        .or_else(|| available.iter().find(|t| t.split('-').next().is_some_and(|l| l.eq_ignore_ascii_case(lang))))
        .cloned()
}

/// The strings the keyboard hands an API 5 plugin for [locale]: the chosen
/// `locales/<tag>.json`, whole, with `@lang` naming it; Null when none fits
/// or the file is not a translation file.
pub fn strings_for(dir: &Path, locale: &str) -> serde_json::Value {
    let Some(tag) = pick_locale(&locale_tags(dir), locale) else { return serde_json::Value::Null };
    let Ok(text) = std::fs::read_to_string(dir.join("locales").join(format!("{tag}.json"))) else { return serde_json::Value::Null };
    match serde_json::from_str::<serde_json::Value>(&text) {
        Ok(serde_json::Value::Object(mut map)) => {
            map.insert("@lang".into(), serde_json::Value::String(tag));
            serde_json::Value::Object(map)
        }
        _ => serde_json::Value::Null,
    }
}

/// The session config the keyboard would build for the plugin in [dir], in
/// this run's [context].
pub fn session_config(dir: &Path, m: &Manifest, settings: serde_json::Value) -> serde_json::Value {
    session_config_in(dir, m, settings, &context())
}

/// [session_config] in [ctx].
pub fn session_config_in(dir: &Path, m: &Manifest, settings: serde_json::Value, ctx: &Context) -> serde_json::Value {
    let mut config = serde_json::json!({
        "id": m.id, "version": m.version, "permissions": m.permissions, "mode": if m.panel { "panel" } else { "action" },
        "apiVersion": m.api, "entry": m.entry, "files": modules(dir, &m.entry), "settings": settings,
        "context": ctx.json(),
    });
    if m.api >= 5 {
        let strings = strings_for(dir, &ctx.locale);
        if !strings.is_null() {
            config["strings"] = strings;
        }
    }
    config
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

/// Runs [once], then again each time a file in [dir] changes, until stopped.
pub fn watch(dir: &Path, mut once: impl FnMut()) -> ! {
    let snapshot = || -> Vec<(String, Option<std::time::SystemTime>, u64)> {
        files(dir)
            .into_iter()
            .filter(|f| !f.ends_with(".wmplugin"))
            .map(|f| {
                let meta = std::fs::metadata(dir.join(&f)).ok();
                (f, meta.as_ref().and_then(|m| m.modified().ok()), meta.map(|m| m.len()).unwrap_or(0))
            })
            .collect()
    };
    loop {
        let seen = snapshot();
        once();
        eprintln!("watching {} for changes (Ctrl-C to stop)", shown(dir));
        loop {
            std::thread::sleep(std::time::Duration::from_millis(300));
            let now = snapshot();
            if now != seen {
                let changed = now.iter().find(|n| !seen.contains(n)).or_else(|| seen.iter().find(|s| !now.contains(s))).map(|n| n.0.clone());
                eprintln!("\n-- {} changed --\n", changed.as_deref().unwrap_or("a file"));
                // Let an editor finish writing before reading.
                std::thread::sleep(std::time::Duration::from_millis(100));
                break;
            }
        }
    }
}

/// [dir] as a person reads it: `.` for the folder they are in.
pub fn shown(dir: &Path) -> String {
    let s = dir.display().to_string();
    if s.is_empty() { ".".into() } else { s }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locales_are_picked_as_the_keyboard_picks_them() {
        let tags: Vec<String> = ["bn", "pt", "pt-BR", "zh-Hant"].iter().map(|s| s.to_string()).collect();
        assert_eq!(pick_locale(&tags, "pt-BR").as_deref(), Some("pt-BR"));
        assert_eq!(pick_locale(&tags, "pt-PT").as_deref(), Some("pt"));
        assert_eq!(pick_locale(&tags, "bn-IN").as_deref(), Some("bn"));
        assert_eq!(pick_locale(&tags, "zh-TW").as_deref(), Some("zh-Hant"));
        assert_eq!(pick_locale(&tags, "en"), None);
        assert!(is_locale_tag("es-419") && is_locale_tag("bn") && !is_locale_tag("Bengali") && !is_locale_tag("b"));
        assert!(Context { locale: "ar-EG".into(), ..Context::default() }.rtl());
        assert!(!Context::default().rtl());
    }
}
