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
    /// Print {"dir", "files"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

pub fn run(args: Args) -> u8 {
    let dir = if args.dir.is_file() { args.dir.parent().map(Path::to_path_buf).unwrap_or_default() } else { args.dir.clone() };
    let dir = if dir.as_os_str().is_empty() { PathBuf::from(".") } else { dir };
    if !dir.is_dir() {
        let mut d = crate::diag::Diag::error(format!("there is no folder {}", args.dir.display()));
        d.help = Some("give the plugin's folder".into());
        eprint!("{}", d.render(&|_| None, crate::diag::color_stderr()));
        return 2;
    }
    let api = crate::project::manifest(&dir).map(|m| m.api).unwrap_or(5);
    let out = if args.out.is_absolute() { args.out.clone() } else { dir.join(&args.out) };
    match write(&out, api) {
        Ok(written) => {
            if args.json {
                let files: Vec<String> = written.iter().map(|p| p.strip_prefix(&out).unwrap_or(p).to_string_lossy().replace('\\', "/")).collect();
                println!("{}", serde_json::json!({"dir": out.display().to_string(), "files": files}));
                return 0;
            }
            crate::note!("wrote {} stub files to {}", written.len(), out.display());
            if !dir.join("pyrightconfig.json").is_file() && !dir.join("pyproject.toml").is_file() {
                crate::note!("  point your checker at them: pyrightconfig.json with {{\"stubPath\": \"{}\"}}, or MYPYPATH for mypy", args.out.display());
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
        "mock: dict[str, Any] | None = None, settings: dict[str, Any] | None = None, locale: str | None = None, theme: str | None = None, surface: str | None = None",
        "Session",
        "The plugin as the keyboard runs it, with mock answering its host calls by op name, settings as its settings page's values, and locale, theme and surface as plugin.locale, plugin.theme and plugin.surface (the run's --locale, --theme and --surface when left out).",
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

/// A session and what its calls give back. Both read as attributes
/// (`s.load()`, `r.ok`) or as keys (`s["load"]()`, `r["ok"]`).
const SESSION: &str = r#"class Result:
    """What a session call gives back: r.ok, r.ui, r.error, or r["ok"] and so on."""
    ok: bool
    """Whether the call ran without an error."""
    ui: dict[str, Any] | None
    """The widget tree the panel draws."""
    error: dict[str, Any] | None
    """The plugin's error, with kind, message, file, line and traceback, when ok is false."""
    host: dict[str, Any]
    """Timers, header buttons and the then= calls still waiting."""
    log: list[str]
    """What the plugin printed."""
    value: Any
    """What a selection action or a provider gave back."""
    def __getitem__(self, key: str) -> Any: ...
    def get(self, key: str, default: Any = None) -> Any: ...

class Session:
    """A plugin session. Each call gives back a Result."""
    def load(self) -> Result:
        """Runs the file and draws the panel."""
        ...
    def render(self) -> Result:
        """Draws the panel again."""
        ...
    def event(self, event: dict[str, Any]) -> Result:
        """Sends one event, such as {"type": "click", "id": "button:Look up"}, then draws."""
        ...
    def transform(self, text: str, action_id: str = "") -> Result:
        """Runs a selection action, or a provider as provider:<tool>/<id>."""
        ...
    def storage(self) -> dict[str, Any]:
        """What the plugin saved."""
        ...
    def settle(self) -> Result:
        """Answers every waiting then= call from the mock, round after round, and gives back the last result, or the first that failed."""
        ...
    def calls(self) -> list[dict[str, Any]]:
        """Every host call so far, as {"op", "args"} dicts."""
        ...
    def __getitem__(self, key: str) -> Callable[..., Any]: ...
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
    let mut s = SESSION.replace("class Session:", "class _Session:").replace("class Result:", "class _Result:").replace("-> Result", "-> _Result");
    s.push_str("\nclass _PluginTest:\n");
    for (name, params, ret, doc) in PLUGIN_TEST {
        let ret = if *ret == "Session" { "_Session" } else { ret };
        s.push_str(&format!("    def {name}(self, {params}) -> {ret}:\n        \"\"\"{doc}\"\"\"\n        ...\n\n"));
    }
    s
}
