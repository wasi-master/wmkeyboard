//! Whether a pure-Python package can run in WM Keyboard's plugins
//! ([analyse]), and tree shaking of the packages a plugin vendored
//! ([shake]). No network and no file system: `wmpy pip` (the CLI) and the
//! web editor (through wasm) fetch the files and hand them in as
//! `path -> source` maps.
//!
//! The verdicts lean on the interpreter in this build rather than on lists:
//! every `.py` file goes through `wm_python::interp::check` (the parser the
//! keyboard runs, which names any syntax it refuses), and whether a builtin,
//! a standard module, a module member or a class feature exists is asked of
//! a `wm_python::interp::Runtime` (see `probe.rs`). The tolerant parser of
//! `wm-python-tooling` gives the structure: imports, classes, names.

mod analyse;
mod probe;
mod program;
mod shake;
mod source;

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{json, Value as Json};

pub use analyse::analyse;
pub use shake::shake;

/// The keyboard's caps on a plugin archive (`PluginFile.kt` in core/plugins).
pub mod limits {
    /// One `.py` file.
    pub const MAX_SCRIPT_BYTES: usize = 256 * 1024;
    /// Entries in the `.wmplugin` archive, `plugin.json` included.
    pub const MAX_ENTRIES: usize = 64;
    /// The whole archive, uncompressed.
    pub const MAX_TOTAL_BYTES: usize = 4 * 1024 * 1024;
    /// Folders and file in a module's path: `a/b/c/d.py` at most.
    pub const MAX_MODULE_PARTS: usize = 4;
    /// One part of a module's path.
    pub const MAX_PART_CHARS: usize = 64;
}

/// Whether the keyboard would take [path] as a module file of a plugin:
/// identifiers between slashes, at most [limits::MAX_MODULE_PARTS] deep,
/// ending `.py`. A `.py` file that fails this makes the keyboard refuse the
/// whole plugin, so installers leave such files out.
pub fn is_module_path(path: &str) -> bool {
    let Some(stem) = path.strip_suffix(".py") else { return false };
    let parts: Vec<&str> = stem.split('/').collect();
    parts.len() <= limits::MAX_MODULE_PARTS && parts.iter().all(|p| is_part(p))
}

/// Whether Python could import a file at [path] at all (any depth).
pub fn is_importable_path(path: &str) -> bool {
    path.strip_suffix(".py").is_some_and(|stem| stem.split('/').all(is_part))
}

fn is_part(p: &str) -> bool {
    let mut chars = p.chars();
    !p.is_empty()
        && p.chars().count() <= limits::MAX_PART_CHARS
        && chars.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    Error,
    Warning,
    Info,
}

impl Severity {
    pub fn name(self) -> &'static str {
        match self {
            Severity::Error => "error",
            Severity::Warning => "warning",
            Severity::Info => "info",
        }
    }
}

/// One thing that would go wrong, or might.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Problem {
    pub severity: Severity,
    /// A short id: `syntax`, `missing-import`, `sandbox-import`, `c-extension`, ...
    pub code: &'static str,
    pub message: String,
    /// Relative to the package's files, as handed in.
    pub file: String,
    /// 1-based.
    pub line: Option<u32>,
    /// 1-based, in characters.
    pub col: Option<u32>,
    /// 1-based, exclusive, on the same line.
    pub end_col: Option<u32>,
    pub help: Option<String>,
}

impl Problem {
    /// `{"severity", "file", "line", "col", "code", "message"}`, with
    /// `endCol` and `help` when known.
    pub fn to_json(&self) -> Json {
        let mut o = json!({
            "severity": self.severity.name(), "file": self.file, "line": self.line, "col": self.col,
            "code": self.code, "message": self.message,
        });
        if let Some(e) = self.end_col {
            o["endCol"] = json!(e);
        }
        if let Some(h) = &self.help {
            o["help"] = json!(h);
        }
        o
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    /// Nothing found that would stop it.
    Ok,
    /// It loads, but some parts may fail or act differently.
    Warnings,
    /// It will not load, or not work, in a plugin.
    Incompatible,
}

impl Verdict {
    pub fn name(self) -> &'static str {
        match self {
            Verdict::Ok => "ok",
            Verdict::Warnings => "warnings",
            Verdict::Incompatible => "incompatible",
        }
    }
}

/// What [analyse] is told about the package's surroundings.
#[derive(Clone, Debug, Default)]
pub struct AnalyseOptions {
    /// The package's name, for messages.
    pub package: String,
    /// Top-level module and package names that will sit beside this package
    /// in the plugin: its dependencies, other vendored packages, the plugin's
    /// own modules. Imports of them are not reported as missing.
    pub available: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct FileReport {
    pub path: String,
    /// The module it imports as, when it is one.
    pub module: Option<String>,
    pub bytes: usize,
    pub problems: Vec<Problem>,
}

/// Every module the package imports, sorted, by where it comes from.
#[derive(Clone, Debug, Default)]
pub struct Imports {
    /// The interpreter's own modules: `math`, `re`, `collections`, ...
    pub stdlib: BTreeSet<String>,
    /// The keyboard's `wm` package.
    pub wm: BTreeSet<String>,
    /// The package's own modules.
    pub internal: BTreeSet<String>,
    /// Names in [AnalyseOptions::available].
    pub available: BTreeSet<String>,
    /// Nothing provides them.
    pub missing: BTreeSet<String>,
}

#[derive(Clone, Debug)]
pub struct Report {
    pub verdict: Verdict,
    pub files: Vec<FileReport>,
    pub imports: Imports,
    /// The package's top-level module and package names.
    pub roots: Vec<String>,
}

impl Report {
    pub fn problems(&self) -> impl Iterator<Item = &Problem> {
        self.files.iter().flat_map(|f| f.problems.iter())
    }

    /// Errors and warnings; notes are not counted.
    pub fn problem_count(&self) -> usize {
        self.problems().filter(|p| p.severity != Severity::Info).count()
    }

    /// `{"verdict", "problems": [...], "imports": {"stdlib", "internal",
    /// "missing", "wm", "available"}, "roots", "files": [{"path", "module",
    /// "bytes", "problems"}]}`.
    pub fn to_json(&self) -> Json {
        let set = |s: &BTreeSet<String>| json!(s.iter().collect::<Vec<_>>());
        json!({
            "verdict": self.verdict.name(),
            "problems": self.problems().map(Problem::to_json).collect::<Vec<_>>(),
            "imports": {
                "stdlib": set(&self.imports.stdlib), "internal": set(&self.imports.internal),
                "missing": set(&self.imports.missing), "wm": set(&self.imports.wm), "available": set(&self.imports.available),
            },
            "roots": self.roots,
            "files": self.files.iter().map(|f| json!({
                "path": f.path, "module": f.module, "bytes": f.bytes,
                "problems": f.problems.iter().map(Problem::to_json).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
        })
    }
}

/// What [shake] may remove.
#[derive(Clone, Debug, Default)]
pub struct ShakeOptions {
    /// Also drop unused top-level functions and classes inside kept modules,
    /// not only whole modules nothing imports.
    pub symbols: bool,
}

/// What [shake] kept and removed.
#[derive(Clone, Debug, Default)]
pub struct ShakeReport {
    /// Every file after shaking, path to source; the plugin's own files unchanged.
    pub files: BTreeMap<String, String>,
    pub removed_files: Vec<String>,
    /// `(file, name)` of each top-level definition removed.
    pub removed_symbols: Vec<(String, String)>,
    pub bytes_before: usize,
    pub bytes_after: usize,
    /// Why something was kept that looked unused (dynamic imports, getattr, ...).
    pub notes: Vec<String>,
}

impl ShakeReport {
    /// `{"files", "removedFiles", "removedSymbols": [[file, name]],
    /// "bytesBefore", "bytesAfter", "notes"}`.
    pub fn to_json(&self) -> Json {
        json!({
            "files": self.files,
            "removedFiles": self.removed_files,
            "removedSymbols": self.removed_symbols.iter().map(|(f, n)| json!([f, n])).collect::<Vec<_>>(),
            "bytesBefore": self.bytes_before,
            "bytesAfter": self.bytes_after,
            "notes": self.notes,
        })
    }
}
