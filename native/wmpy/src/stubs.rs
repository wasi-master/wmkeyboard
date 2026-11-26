//! `wmpy stubs`: type stubs of the `wm` package for editors and checkers
//! that are not WM Keyboard's own (Pylance, pyright, mypy, PyCharm).
//!
//! Writes, under `.wmpy/stubs/` of the plugin folder by default:
//!
//! * `wm/__init__.pyi` and `wm/<module>.pyi`, from the language service's
//!   API model ([wm_python_tooling::stubs]);
//! * `plugin_test.pyi`, the module `wmpy test` gives test files;
//! * `__builtins__.pyi`, the names a test file has without an import
//!   (`plugin_test`), and for an API 4 plugin its `wm` and `ui` globals.
//!   Pyright and Pylance read it from the root of `stubPath`.
//!
//! `wmpy new` writes a pyrightconfig.json whose `stubPath` is this folder.

use std::path::{Path, PathBuf};

use wm_python_tooling::stubs::{api4_globals, wm_package, HEADER};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Where to write them, relative to the plugin folder
    #[arg(long, default_value = ".wmpy/stubs")]
    pub out: PathBuf,
}

pub fn run(args: Args) -> u8 {
    let dir = if args.dir.is_file() { args.dir.parent().map(Path::to_path_buf).unwrap_or_default() } else { args.dir.clone() };
    let dir = if dir.as_os_str().is_empty() { PathBuf::from(".") } else { dir };
    if !dir.is_dir() {
        eprintln!("{}: no such folder", args.dir.display());
        return 2;
    }
    let api = crate::project::manifest(&dir).map(|m| m.api).unwrap_or(5);
    let out = if args.out.is_absolute() { args.out.clone() } else { dir.join(&args.out) };
    match write(&out, api) {
        Ok(written) => {
            eprintln!("wrote {} stub files to {}", written.len(), out.display());
            if !dir.join("pyrightconfig.json").is_file() && !dir.join("pyproject.toml").is_file() {
                eprintln!("  point your checker at them: pyrightconfig.json with {{\"stubPath\": \"{}\"}}, or MYPYPATH for mypy", args.out.display());
            }
            0
        }
        Err(e) => {
            eprintln!("cannot write the stubs to {}: {e}", out.display());
            2
        }
    }
}

/// Every stub file, `(path under the stubs folder, text)`, for a plugin at API level [api].
pub fn files(api: i64) -> Vec<(String, String)> {
    let mut out = wm_package();
    out.push(("plugin_test.pyi".into(), plugin_test_module()));
    let mut builtins = format!("{HEADER}\n\"\"\"Names a plugin's files have without importing them.\"\"\"\n\nfrom typing import Any, Callable, TypedDict\n\n");
    builtins.push_str(&plugin_test_class());
    builtins.push_str("plugin_test: _PluginTest\n\"\"\"The test module `wmpy test` gives test files: the plugin run as the keyboard runs it.\"\"\"\n\n");
    if api < 5 {
        builtins.push_str(&api4_globals());
    }
    out.push(("__builtins__.pyi".into(), builtins));
    out
}

/// Writes [files] into [out], removing stubs an earlier run wrote that no longer exist.
pub fn write(out: &Path, api: i64) -> std::io::Result<Vec<PathBuf>> {
    let files = files(api);
    // Earlier stubs of ours that are gone from the model.
    let wm = out.join("wm");
    if let Ok(read) = std::fs::read_dir(&wm) {
        for item in read.flatten() {
            let path = item.path();
            let ours = path.extension().is_some_and(|e| e == "pyi") && std::fs::read_to_string(&path).is_ok_and(|t| t.starts_with(HEADER));
            let name = format!("wm/{}", item.file_name().to_string_lossy());
            if ours && !files.iter().any(|(p, _)| *p == name) {
                let _ = std::fs::remove_file(&path);
            }
        }
    }
    let mut written = Vec::new();
    for (path, text) in files {
        let target = out.join(&path);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&target, text)?;
        written.push(target);
    }
    Ok(written)
}

/// `(name, parameters, return type, doc)` of each `plugin_test` function.
const PLUGIN_TEST: &[(&str, &str, &str, &str)] = &[
    ("read", "name: str", "str", "The UTF-8 text of a file inside the plugin folder."),
    (
        "load",
        "name: str, wm: Any = None, ui: Any = None",
        "Any",
        "Runs that file as a module with the given wm and ui as its globals, and returns its globals as a dict whose functions stay callable: m[\"transform\"](\"x\") or m.transform(\"x\").",
    ),
    (
        "session",
        "mock: dict[str, Any] | None = None, settings: dict[str, Any] | None = None",
        "Session",
        "The plugin as the keyboard runs it, with mock answering its host calls by op name, and settings as its settings page's values.",
    ),
    (
        "preview",
        "id: str, file: str | bytes, mock: dict[str, Any] | None = None, name: str | None = None, mime: str | None = None",
        "dict[str, Any]",
        "Runs the plugin's @plugin.preview(id) on file (a file inside the plugin folder, or bytes) as the file manager runs it; the result's ui is what it drew.",
    ),
    (
        "thumbnail",
        "id: str, file: str | bytes, mock: dict[str, Any] | None = None, name: str | None = None, mime: str | None = None",
        "dict[str, Any]",
        "Runs the plugin's @plugin.thumbnail(id) on file; the result's value is the picture as base64, or None.",
    ),
];

/// The functions a session dict holds.
const SESSION: &str = r#"class Session(TypedDict):
    """A plugin session: each function gives back the result as a dict with ok, ui, error and host."""
    load: Callable[[], dict[str, Any]]
    """Runs the file and draws the panel."""
    render: Callable[[], dict[str, Any]]
    """Draws the panel again."""
    event: Callable[[dict[str, Any]], dict[str, Any]]
    """Sends one event, such as {"type": "click", "id": "button:Look up"}, then draws."""
    transform: Callable[..., dict[str, Any]]
    """transform(text, id): runs a selection action, or a provider as provider:<tool>/<id>."""
    storage: Callable[[], dict[str, Any]]
    """What the plugin saved."""
    settle: Callable[[], dict[str, Any]]
    """Answers every waiting then= call from the mock, round after round, and gives back the last result, or the first that failed."""
    calls: Callable[[], list[dict[str, Any]]]
    """Every host call so far, as {"op", "args"} dicts."""
"#;

fn plugin_test_module() -> String {
    let mut s = format!("{HEADER}\n\"\"\"The test module `wmpy test` gives test files: the plugin run as the keyboard runs it, with host calls answered from a mock.\"\"\"\n\nfrom typing import Any, Callable, TypedDict\n\n");
    s.push_str(SESSION);
    s.push('\n');
    for (name, params, ret, doc) in PLUGIN_TEST {
        s.push_str(&format!("def {name}({params}) -> {ret}:\n    \"\"\"{doc}\"\"\"\n    ...\n\n"));
    }
    s
}

fn plugin_test_class() -> String {
    let mut s = SESSION.replace("class Session(", "class _Session(");
    s.push_str("\nclass _PluginTest:\n");
    for (name, params, ret, doc) in PLUGIN_TEST {
        let ret = if *ret == "Session" { "_Session" } else { ret };
        s.push_str(&format!("    def {name}(self, {params}) -> {ret}:\n        \"\"\"{doc}\"\"\"\n        ...\n\n"));
    }
    s
}
