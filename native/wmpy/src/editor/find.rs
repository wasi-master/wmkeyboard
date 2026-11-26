//! Where the editors are: their programs, and the wmpy extension in each.
//!
//! Everything is looked up under `$HOME` (`%USERPROFILE%`, `%APPDATA%` and
//! `%LOCALAPPDATA%` on Windows) and on `PATH`, plus the system-wide install
//! folders (`/Applications`, `/opt`, `/snap/bin`, `Program Files`), which
//! `WMPY_EDITOR_NO_SYSTEM=1` leaves out so the tests see only their own fakes.

use std::path::{Path, PathBuf};

pub const VSCODE_ID: &str = "wasimaster.wmpy";
pub const JETBRAINS_ID: &str = "com.wasimaster.wmpy";
pub const LSP4IJ_ID: &str = "com.redhat.devtools.lsp4ij";
pub const ZED_ID: &str = "wmpy";

/// One of the VS Code family: a CLI that takes `--install-extension`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Code {
    Vscode,
    Insiders,
    VsCodium,
    Cursor,
    Windsurf,
}

impl Code {
    pub const ALL: [Code; 5] = [Code::Vscode, Code::Insiders, Code::VsCodium, Code::Cursor, Code::Windsurf];

    pub fn name(self) -> &'static str {
        match self {
            Code::Vscode => "VS Code",
            Code::Insiders => "VS Code Insiders",
            Code::VsCodium => "VSCodium",
            Code::Cursor => "Cursor",
            Code::Windsurf => "Windsurf",
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Code::Vscode => "vscode",
            Code::Insiders => "insiders",
            Code::VsCodium => "vscodium",
            Code::Cursor => "cursor",
            Code::Windsurf => "windsurf",
        }
    }

    /// The command on PATH.
    fn cli(self) -> &'static str {
        match self {
            Code::Vscode => "code",
            Code::Insiders => "code-insiders",
            Code::VsCodium => "codium",
            Code::Cursor => "cursor",
            Code::Windsurf => "windsurf",
        }
    }

    /// The folder under the home directory its extensions live in.
    fn extensions_dir(self) -> &'static str {
        match self {
            Code::Vscode => ".vscode/extensions",
            Code::Insiders => ".vscode-insiders/extensions",
            Code::VsCodium => ".vscode-oss/extensions",
            Code::Cursor => ".cursor/extensions",
            Code::Windsurf => ".windsurf/extensions",
        }
    }

    /// The CLI inside an app bundle or install folder, where PATH has none.
    fn bundled(self) -> Vec<PathBuf> {
        let mut out = Vec::new();
        if cfg!(target_os = "macos") {
            let (app, bin) = match self {
                Code::Vscode => ("Visual Studio Code.app", "code"),
                Code::Insiders => ("Visual Studio Code - Insiders.app", "code"),
                Code::VsCodium => ("VSCodium.app", "codium"),
                Code::Cursor => ("Cursor.app", "cursor"),
                Code::Windsurf => ("Windsurf.app", "windsurf"),
            };
            for apps in app_dirs() {
                out.push(apps.join(app).join("Contents/Resources/app/bin").join(bin));
            }
        } else if cfg!(windows) {
            let (folder, bin) = match self {
                Code::Vscode => ("Microsoft VS Code", "bin\\code.cmd"),
                Code::Insiders => ("Microsoft VS Code Insiders", "bin\\code-insiders.cmd"),
                Code::VsCodium => ("VSCodium", "bin\\codium.cmd"),
                Code::Cursor => ("cursor", "resources\\app\\bin\\cursor.cmd"),
                Code::Windsurf => ("Windsurf", "bin\\windsurf.cmd"),
            };
            if let Some(local) = env_dir("LOCALAPPDATA") {
                out.push(local.join("Programs").join(folder).join(bin));
            }
            if system() {
                if let Some(pf) = env_dir("ProgramFiles") {
                    out.push(pf.join(folder).join(bin));
                }
            }
        } else if system() {
            out.push(PathBuf::from("/snap/bin").join(self.cli()));
            out.push(PathBuf::from("/usr/share/code/bin").join(self.cli()));
        }
        out
    }

    pub fn program(self) -> Option<PathBuf> {
        on_path(self.cli()).or_else(|| self.bundled().into_iter().find(|p| p.is_file()))
    }

    /// The wmpy extension's version, read from the extensions folder.
    pub fn installed(self) -> Option<String> {
        let dir = home()?.join(self.extensions_dir());
        let prefix = format!("{VSCODE_ID}-");
        let mut best: Option<String> = None;
        for entry in std::fs::read_dir(dir).ok()?.flatten() {
            let name = entry.file_name().to_string_lossy().to_lowercase();
            let Some(rest) = name.strip_prefix(&prefix) else { continue };
            // `wasimaster.wmpy-0.1.0` or with a platform: `wasimaster.wmpy-0.1.0-darwin-arm64`.
            let version = rest.split('-').next().unwrap_or(rest).to_string();
            if entry.path().join("package.json").is_file() && best.as_ref().is_none_or(|b| newer(&version, b)) {
                best = Some(version);
            }
        }
        best
    }
}

/// Whether version [a] is later than [b], comparing dotted numbers.
pub fn newer(a: &str, b: &str) -> bool {
    let parse = |s: &str| s.split('.').map(|p| p.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    parse(a) > parse(b)
}

pub fn home() -> Option<PathBuf> {
    env_dir("HOME").or_else(|| env_dir("USERPROFILE"))
}

fn env_dir(name: &str) -> Option<PathBuf> {
    std::env::var_os(name).filter(|v| !v.is_empty()).map(PathBuf::from)
}

fn system() -> bool {
    std::env::var_os("WMPY_EDITOR_NO_SYSTEM").is_none_or(|v| v.is_empty() || v == "0")
}

fn app_dirs() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Some(h) = home() {
        out.push(h.join("Applications"));
    }
    if system() {
        out.push(PathBuf::from("/Applications"));
    }
    out
}

/// [name] on PATH, with the Windows endings tried.
pub fn on_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    let endings: Vec<String> = if cfg!(windows) {
        std::env::var("PATHEXT").unwrap_or_else(|_| ".EXE;.CMD;.BAT".into()).split(';').map(|e| e.to_lowercase()).collect()
    } else {
        vec![String::new()]
    };
    for dir in std::env::split_paths(&path) {
        for end in &endings {
            let candidate = dir.join(format!("{name}{end}"));
            if is_program(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

fn is_program(p: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        p.metadata().is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    }
    #[cfg(not(unix))]
    {
        p.is_file()
    }
}

/// Zed's data folder: where `extensions/installed/` is.
pub fn zed_data() -> Option<PathBuf> {
    if let Some(dir) = env_dir("WMPY_ZED_DATA_DIR") {
        return Some(dir);
    }
    if cfg!(target_os = "macos") {
        return home().map(|h| h.join("Library/Application Support/Zed"));
    }
    if cfg!(windows) {
        return env_dir("LOCALAPPDATA").map(|l| l.join("Zed"));
    }
    env_dir("XDG_DATA_HOME").or_else(|| home().map(|h| h.join(".local/share"))).map(|d| d.join("zed"))
}

/// The Zed program, if it is installed.
pub fn zed_program() -> Option<PathBuf> {
    for cli in ["zed", "zeditor", "zedit"] {
        if let Some(p) = on_path(cli) {
            return Some(p);
        }
    }
    let mut candidates: Vec<PathBuf> = app_dirs().into_iter().map(|d| d.join("Zed.app")).collect();
    if let Some(h) = home() {
        candidates.push(h.join(".local/zed.app/bin/zed"));
    }
    if let Some(local) = env_dir("LOCALAPPDATA") {
        candidates.push(local.join("Programs\\Zed\\Zed.exe"));
    }
    candidates.into_iter().find(|p| p.exists())
}

pub fn zed_extension_dir() -> Option<PathBuf> {
    zed_data().map(|d| d.join("extensions").join("installed").join(ZED_ID))
}

/// The wmpy Zed extension's version, and whether it is a dev extension (a link to a source folder).
pub fn zed_installed() -> Option<(String, bool)> {
    let dir = zed_extension_dir()?;
    let text = std::fs::read_to_string(dir.join("extension.toml")).ok()?;
    let version = text
        .lines()
        .find_map(|l| l.trim().strip_prefix("version").map(str::trim).and_then(|r| r.strip_prefix('=')).map(|v| v.trim().trim_matches('"').to_string()))
        .unwrap_or_default();
    let dev = std::fs::symlink_metadata(&dir).is_ok_and(|m| m.file_type().is_symlink());
    Some((version, dev))
}

/// A JetBrains IDE that runs Python: PyCharm, IntelliJ IDEA (with the Python plugin) or DataSpell.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JetBrains {
    /// `PyCharm 2025.2`.
    pub name: String,
    /// The folder plugins are installed into.
    pub plugins: PathBuf,
}

const PRODUCTS: [(&str, &str); 5] = [
    ("PyCharmCE", "PyCharm Community"),
    ("PyCharm", "PyCharm"),
    ("IdeaIC", "IntelliJ IDEA Community"),
    ("IntelliJIdea", "IntelliJ IDEA"),
    ("DataSpell", "DataSpell"),
];

/// The per-version folders the IDEs keep plugins in, newest first.
pub fn jetbrains_ides() -> Vec<JetBrains> {
    let Some(root) = jetbrains_root() else { return Vec::new() };
    let Ok(read) = std::fs::read_dir(&root) else { return Vec::new() };
    let mut out = Vec::new();
    for entry in read.flatten() {
        let folder = entry.file_name().to_string_lossy().to_string();
        let Some((prefix, product)) = PRODUCTS.iter().find(|(p, _)| {
            folder.strip_prefix(p).is_some_and(|rest| rest.chars().next().is_some_and(|c| c.is_ascii_digit()))
        }) else {
            continue;
        };
        let version = &folder[prefix.len()..];
        // Linux keeps plugins in the version folder itself, the others in its plugins/.
        let plugins = if cfg!(any(target_os = "macos", windows)) { entry.path().join("plugins") } else { entry.path() };
        out.push(JetBrains { name: format!("{product} {version}"), plugins });
    }
    out.sort_by(|a, b| b.name.cmp(&a.name));
    out
}

/// Where the per-version folders are: `~/Library/Application Support/JetBrains`,
/// `~/.local/share/JetBrains` or `%APPDATA%\JetBrains`.
fn jetbrains_root() -> Option<PathBuf> {
    if let Some(dir) = env_dir("WMPY_JETBRAINS_DIR") {
        return Some(dir);
    }
    if cfg!(target_os = "macos") {
        return home().map(|h| h.join("Library/Application Support/JetBrains"));
    }
    if cfg!(windows) {
        return env_dir("APPDATA").map(|a| a.join("JetBrains"));
    }
    env_dir("XDG_DATA_HOME").or_else(|| home().map(|h| h.join(".local/share"))).map(|d| d.join("JetBrains"))
}

/// The wmpy plugin's version in a plugins folder, from its jar's name (`lib/wmpy-0.1.0.jar`).
pub fn jetbrains_installed(plugins: &Path) -> Option<String> {
    let lib = plugins.join("wmpy").join("lib");
    let mut version = None;
    for entry in std::fs::read_dir(lib).ok()?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if let Some(v) = name.strip_prefix("wmpy-").and_then(|r| r.strip_suffix(".jar")) {
            version = Some(v.to_string());
        }
    }
    Some(version.unwrap_or_else(|| "?".into()))
}

/// Whether LSP4IJ, which the plugin needs, is in a plugins folder.
pub fn lsp4ij_installed(plugins: &Path) -> bool {
    std::fs::read_dir(plugins)
        .map(|r| r.flatten().any(|e| e.file_name().to_string_lossy().to_lowercase().starts_with("lsp4ij")))
        .unwrap_or(false)
}

/// The launchers that take `installPlugins`: Toolbox's scripts, then app
/// bundles and install folders. Deduplicated by product.
pub fn jetbrains_launchers() -> Vec<(String, PathBuf)> {
    let mut out: Vec<(String, PathBuf)> = Vec::new();
    let mut add = |label: &str, p: PathBuf| {
        if is_program(&p) && !out.iter().any(|(l, _)| l == label) {
            out.push((label.to_string(), p));
        }
    };
    let scripts = jetbrains_root().map(|r| r.join("Toolbox").join("scripts"));
    let scripts = if cfg!(windows) { env_dir("LOCALAPPDATA").map(|l| l.join("JetBrains\\Toolbox\\scripts")) } else { scripts };
    let ext = if cfg!(windows) { ".cmd" } else { "" };
    for (script, label) in [("pycharm", "PyCharm"), ("idea", "IntelliJ IDEA"), ("dataspell", "DataSpell")] {
        if let Some(dir) = &scripts {
            add(label, dir.join(format!("{script}{ext}")));
        }
        if let Some(p) = on_path(script) {
            add(label, p);
        }
    }
    if cfg!(target_os = "macos") {
        for apps in app_dirs() {
            let Ok(read) = std::fs::read_dir(&apps) else { continue };
            let mut found: Vec<PathBuf> = read.flatten().map(|e| e.path()).collect();
            found.sort();
            for app in found {
                let n = app.file_name().unwrap_or_default().to_string_lossy().to_string();
                for (start, bin, label) in [("PyCharm", "pycharm", "PyCharm"), ("IntelliJ IDEA", "idea", "IntelliJ IDEA"), ("DataSpell", "dataspell", "DataSpell")] {
                    if n.starts_with(start) && n.ends_with(".app") {
                        add(label, app.join("Contents/MacOS").join(bin));
                    }
                }
            }
        }
    } else if !cfg!(windows) && system() {
        for (bin, label) in [("pycharm-professional", "PyCharm"), ("pycharm-community", "PyCharm"), ("pycharm", "PyCharm"), ("intellij-idea-ultimate", "IntelliJ IDEA"), ("intellij-idea-community", "IntelliJ IDEA")] {
            add(label, PathBuf::from("/snap/bin").join(bin));
        }
        if let Ok(read) = std::fs::read_dir("/opt") {
            for e in read.flatten() {
                add("PyCharm", e.path().join("bin/pycharm.sh"));
                add("IntelliJ IDEA", e.path().join("bin/idea.sh"));
            }
        }
    } else if cfg!(windows) && system() {
        if let Some(pf) = env_dir("ProgramFiles") {
            if let Ok(read) = std::fs::read_dir(pf.join("JetBrains")) {
                for e in read.flatten() {
                    add("PyCharm", e.path().join("bin\\pycharm64.exe"));
                    add("IntelliJ IDEA", e.path().join("bin\\idea64.exe"));
                }
            }
        }
    }
    out
}

/// [p] with the home folder written as `~`, for printing.
pub fn tilde(p: &Path) -> String {
    match home() {
        Some(h) => match p.strip_prefix(&h) {
            Ok(rest) => format!("~/{}", rest.display()),
            Err(_) => p.display().to_string(),
        },
        None => p.display().to_string(),
    }
}
