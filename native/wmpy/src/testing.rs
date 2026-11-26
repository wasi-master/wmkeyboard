//! `wmpy test` and `wmpy render`: WM Keyboard's Python plugins, headless.
//!
//! ```text
//! wmpy test <plugin-dir> [-k text] [--json] [--junit file] [--watch]
//! wmpy render <plugin-dir> [--mock <file>]   load the plugin as a panel and print the first render's JSON
//! ```
//!
//! `wmpy test` runs `test.py` and every `tests/**/test_*.py`, each in a fresh
//! interpreter. A file's top level runs first; then each of its top-level
//! `def test_*():` functions runs on its own, in the order written, passing
//! unless it raises. A file without test functions is one test: its top
//! level. `-k` keeps the tests whose `file::name` contains the text.
//!
//! An API 5 plugin (`"apiVersion": 5` in plugin.json) loads with every other
//! `.py` file in its folder as a module. Its host calls are answered from the
//! mock file, `{"http.request": {"status": 200, "text": "..."}, ...}`: each
//! op's value is the reply, and a value that is only `{"error": {"kind",
//! "message"}}` raises that error; an op not in the file raises
//! NotImplementedError. An op's value may instead be a list of routes,
//! `[{"when": {"method": "GET", "url": "https://h/api/*"}, "reply": ...}]`,
//! the first whose `when` fits the call's args answering (strings exactly or
//! as a prefix before a trailing `*`, dicts key by key).
//!
//! A test file runs with the plugin host's `wm` and `ui` globals (a panel,
//! every permission, storage and reminders in memory), an API 5 plugin's other
//! `.py` files as modules it can import, and one extra module,
//! `plugin_test`, a global that `import plugin_test` also reaches:
//!
//! * `plugin_test.read(name)`: the UTF-8 text of a file inside the plugin folder
//! * `plugin_test.load(name, wm, ui)`: run that file as a module with the
//!   given `wm` and `ui` as its globals, and return its globals as a dict,
//!   whose functions stay callable (`m = plugin_test.load("main.py", wm, ui)`,
//!   then `m["transform"]("x")` or `m.transform("x")`)
//!
//! * `plugin_test.session(mock=None, settings=None)`: the plugin itself as the
//!   keyboard runs it (API 5 or not), with [mock] answering host calls as the
//!   mock file does. It returns a dict of functions, each giving back the
//!   result as a dict: `load()`, `render()`, `event(e)`, `transform(text, id)`,
//!   plus `storage()`, `calls()` (every host call so far, `{"op", "args"}`)
//!   and `settle()`, which answers each waiting `then=` call from the mock as
//!   its `result` event, round after round until none waits.
//! * `plugin_test.preview(id, file, mock=None, name=None, mime=None)` and
//!   `plugin_test.thumbnail(...)`: the plugin's `@plugin.preview(id)` or
//!   `@plugin.thumbnail(id)` run on [file] (a file name inside the plugin
//!   folder, or bytes) as the file manager runs it, each in a fresh session.
//!   The first `maxBytes` of the manifest's preview [id] (4 MB by default)
//!   are the file's data; `preview.read` answers from the rest. The result
//!   is a dict as `session()`'s calls return, `ui` for a preview and
//!   `value` (base64, or None) for a thumbnail.
//!
//! A test fails by raising. Its budget is generous (500M steps, 10 minutes)
//! because one run drives a whole corpus of cases.
//!
//! Output: what the tests print goes to stdout as they print it. Each
//! test's result, each failure with its code frame (and traceback, when the
//! interpreter gives one) and the summary go to stderr. A lone `test.py`
//! without test functions, run with stderr not a terminal, keeps the
//! runner's first output: its prints, and on failure one line,
//! `Kind: message (line N)`. `--json` prints the shared JSON shape instead,
//! with each test's output in it; `--junit` also writes JUnit XML for CI.

use std::cell::RefCell;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::{Duration, Instant, SystemTime};

use serde_json::json;
use wm_python::host::api::{self, PluginInfo};
use wm_python::host::files::{Reminders, Storage};
use wm_python::host::Session;
use wm_python::interp::{Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};

use crate::diag::{color_stderr, folder_source, Diag};
use crate::mock::Mock;
use crate::project::{inside, manifest, modules, read_inside, session_config, Manifest, DEFAULT_PREVIEW_BYTES};

/// `wmpy test`: run the plugin's tests.
#[derive(clap::Args, Debug)]
pub struct TestArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Run only the tests whose file::name contains this text
    #[arg(short = 'k', value_name = "TEXT")]
    pub filter: Option<String>,
    /// Print {"ok", "passed", "failed", "durationMs", "tests"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
    /// Also write the results as JUnit XML to this file
    #[arg(long, value_name = "FILE")]
    pub junit: Option<PathBuf>,
    /// Run again whenever a file in the plugin folder changes
    #[arg(long)]
    pub watch: bool,
}

/// `wmpy render`: load the plugin as a panel and print the first draw's JSON.
#[derive(clap::Args, Debug)]
pub struct RenderArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// A JSON file answering host calls by op name
    #[arg(long)]
    pub mock: Option<PathBuf>,
}

pub fn run_test(args: TestArgs) -> u8 {
    let dir = if args.dir.is_file() { args.dir.parent().map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from(".")) } else { args.dir.clone() };
    let dir = if dir.as_os_str().is_empty() { PathBuf::from(".") } else { dir };
    if !dir.is_dir() {
        eprintln!("{}: no such folder", dir.display());
        return 2;
    }
    if args.watch {
        return watch(&dir, &args);
    }
    test(&dir, &args)
}

pub fn run_render(args: RenderArgs) -> u8 {
    render(&args.dir, args.mock.as_deref())
}

pub fn report(e: &PyError) {
    match e.line {
        Some(l) => eprintln!("{}: {} (line {l})", e.kind, e.message),
        None => eprintln!("{}: {}", e.kind, e.message),
    }
}

/// One test's outcome.
pub struct TestResult {
    pub file: String,
    /// The function, or `<module>` for a file's top level.
    pub name: String,
    pub ok: bool,
    pub duration: Duration,
    pub output: String,
    pub error: Option<Diag>,
}

impl TestResult {
    fn id(&self) -> String {
        if self.name == MODULE { self.file.clone() } else { format!("{}::{}", self.file, self.name) }
    }

    fn json(&self) -> serde_json::Value {
        json!({
            "file": self.file, "name": self.name, "ok": self.ok, "durationMs": self.duration.as_millis() as u64,
            "output": self.output, "error": self.error.as_ref().map(Diag::to_json),
        })
    }
}

/// A file's top level, as a test.
pub const MODULE: &str = "<module>";

/// How the results are shown.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Style {
    /// Each result, each failure's code frame, the summary.
    Rich,
    /// The first runner's: prints, and one line for an uncaught error.
    Legacy,
    /// Nothing until the JSON at the end.
    Json,
}

fn test(dir: &Path, args: &TestArgs) -> u8 {
    let files = crate::project::test_files(dir);
    if files.is_empty() {
        eprintln!("{}: no tests: add a test.py, or tests/test_*.py files", dir.join("test.py").display());
        return 2;
    }
    let started = Instant::now();
    let lone_script = files.len() == 1 && files[0] == "test.py" && args.filter.is_none() && test_names(&std::fs::read_to_string(dir.join("test.py")).unwrap_or_default()).is_empty();
    let style = if args.json {
        Style::Json
    } else if lone_script && !std::io::IsTerminal::is_terminal(&std::io::stderr()) {
        Style::Legacy
    } else {
        Style::Rich
    };
    let mut results: Vec<TestResult> = Vec::new();
    for file in &files {
        results.extend(run_file(dir, file, args.filter.as_deref(), style));
    }
    let elapsed = started.elapsed();
    let passed = results.iter().filter(|r| r.ok).count();
    let failed = results.len() - passed;
    if let Some(path) = &args.junit {
        if let Err(e) = std::fs::write(path, junit(&results, elapsed, dir)) {
            eprintln!("cannot write {}: {e}", path.display());
            return 2;
        }
    }
    match style {
        Style::Json => {
            println!(
                "{}",
                json!({
                    "ok": failed == 0 && !results.is_empty(), "passed": passed, "failed": failed, "durationMs": elapsed.as_millis() as u64,
                    "tests": results.iter().map(TestResult::json).collect::<Vec<_>>(),
                })
            );
        }
        Style::Rich => {
            if results.is_empty() {
                eprintln!("no test matches -k {}", args.filter.as_deref().unwrap_or(""));
            } else {
                eprintln!("{passed} passed, {failed} failed in {:.2} s", elapsed.as_secs_f64());
            }
        }
        Style::Legacy => {}
    }
    u8::from(failed > 0 || results.is_empty())
}

/// The top-level `def test_*(` functions of [source], in order.
fn test_names(source: &str) -> Vec<String> {
    source
        .lines()
        .filter_map(|l| l.strip_prefix("def "))
        .filter_map(|rest| {
            let name: String = rest.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            (name.starts_with("test_") && rest[name.len()..].trim_start().starts_with('(')).then_some(name)
        })
        .collect()
}

/// Runs one test file: its top level, then each selected test function.
fn run_file(dir: &Path, file: &str, filter: Option<&str>, style: Style) -> Vec<TestResult> {
    let source = match std::fs::read_to_string(dir.join(file)) {
        Ok(s) => s,
        Err(e) => {
            let mut d = Diag::error(format!("cannot read {file}: {e}"));
            d.file = Some(file.to_string());
            return vec![finished(TestResult { file: file.into(), name: MODULE.into(), ok: false, duration: Duration::ZERO, output: String::new(), error: Some(d) }, dir, style)];
        }
    };
    let names = test_names(&source);
    let whole_file = filter.is_none_or(|k| file.contains(k));
    let selected: Vec<String> = names.iter().filter(|n| whole_file || filter.is_some_and(|k| format!("{file}::{n}").contains(k))).cloned().collect();
    if selected.is_empty() && !(names.is_empty() && whole_file) {
        return Vec::new();
    }
    let output = Rc::new(RefCell::new(String::new()));
    let started = Instant::now();
    let mut rt = match runtime(dir, output.clone(), style != Style::Json) {
        Ok(rt) => rt,
        Err(e) => {
            let r = TestResult { file: file.into(), name: MODULE.into(), ok: false, duration: started.elapsed(), output: take(&output), error: Some(Diag::from_py_error(&e, file)) };
            return vec![finished_or_legacy(r, &e, dir, style)];
        }
    };
    // The test file runs as the script: its errors and frames name it.
    rt.set_module_file("__main__", file);
    rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
    if let Err(e) = rt.load(&source) {
        let r = TestResult { file: file.into(), name: MODULE.into(), ok: false, duration: started.elapsed(), output: take(&output), error: Some(Diag::from_py_error(&e, file)) };
        return vec![finished_or_legacy(r, &e, dir, style)];
    }
    if names.is_empty() {
        let r = TestResult { file: file.into(), name: MODULE.into(), ok: true, duration: started.elapsed(), output: take(&output), error: None };
        return vec![finished(r, dir, style)];
    }
    // The top level's output goes with the first test.
    let mut carried = take(&output);
    let mut out = Vec::new();
    for name in selected {
        if !rt.has_function(&name) {
            continue;
        }
        let started = Instant::now();
        rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
        let result = rt.call(&name, Vec::new());
        let mut text = std::mem::take(&mut carried);
        text.push_str(&take(&output));
        let r = match result {
            Ok(_) => TestResult { file: file.into(), name, ok: true, duration: started.elapsed(), output: text, error: None },
            Err(e) => TestResult { file: file.into(), name, ok: false, duration: started.elapsed(), output: text, error: Some(Diag::from_py_error(&e, file)) },
        };
        out.push(finished(r, dir, style));
    }
    out
}

fn take(buffer: &Rc<RefCell<String>>) -> String {
    std::mem::take(&mut *buffer.borrow_mut())
}

/// Shows a result as it comes in, and hands it on.
fn finished(r: TestResult, dir: &Path, style: Style) -> TestResult {
    if style == Style::Rich {
        let color = color_stderr();
        let paint = |code: &str, text: &str| if color { format!("\x1b[{code}m{text}\x1b[0m") } else { text.to_string() };
        let mark = if r.ok { paint("32", "ok  ") } else { paint("1;31", "FAIL") };
        eprintln!("{mark} {} ({} ms)", r.id(), r.duration.as_millis());
        if let Some(d) = &r.error {
            eprint!("{}", d.render(&folder_source(dir), color));
            eprintln!();
        }
    }
    r
}

fn finished_or_legacy(r: TestResult, e: &PyError, dir: &Path, style: Style) -> TestResult {
    if style == Style::Legacy {
        report(e);
        if let Some(help) = r.error.as_ref().and_then(|d| d.help.as_ref()) {
            eprintln!("  = help: {help}");
        }
        return r;
    }
    finished(r, dir, style)
}

/// A fresh interpreter for one test file of the plugin in [dir], printing
/// into [output] (and to stdout when [echo]).
fn runtime(dir: &Path, output: Rc<RefCell<String>>, echo: bool) -> Result<Runtime, PyError> {
    let m = manifest(dir).unwrap_or(Manifest {
        id: String::new(),
        version: String::new(),
        entry: "main.py".into(),
        permissions: Vec::new(),
        api: 4,
        panel: true,
        previews: Vec::new(),
    });
    let manifest_for_sessions = manifest(dir).ok();
    let mut rt = Runtime::new(Limits { steps: 500_000_000, container_items: 200_000, string_length: 1_000_000 }, STANDARD_MODULES);
    let info = PluginInfo {
        id: m.id,
        version: m.version,
        permissions: vec!["storage".into(), "reminders".into()],
        panel: true,
    };
    if m.api >= 5 {
        // An API 5 test runs where the plugin does: the standard library, the
        // `wm` package, and the plugin's modules, its entry included, so the
        // test can `import helpers` or `import main` and call them directly.
        let mut files: Vec<(String, String)> = modules(dir, &m.entry)
            .into_iter()
            .filter_map(|(file, text)| text.as_str().map(|t| (file, t.to_string())))
            .collect();
        files.sort();
        wm_python::host::v5::install_for_tests(&mut rt, &info, files, Some(Storage::new(None)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
        // The entry is never a module inside the plugin (the keyboard runs it
        // as the script), but a test may import it to reach its functions.
        if let (Some(stem), Ok(text)) = (m.entry.strip_suffix(".py"), std::fs::read_to_string(dir.join(&m.entry))) {
            if !stem.contains('/') {
                rt.add_source_module(stem, &text, false);
                rt.set_module_file(stem, &m.entry);
            }
        }
        // `wm` and `ui` as globals too, as the API 4 runner gave them, so a
        // test can hand them to `plugin_test.load(name, wm, ui)`.
        rt.load("import wm\nfrom wm import ui\n")?;
    } else {
        api::install(&mut rt, &info, Some(Storage::new(None)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
    }
    // The test's output goes straight to stdout, wm.log and print alike.
    rt.set_on_print(Some(Box::new(move |line| {
        if echo {
            println!("{line}");
        }
        let mut o = output.borrow_mut();
        o.push_str(line);
        o.push('\n');
    })));
    let (d1, d2, d3) = (dir.to_path_buf(), dir.to_path_buf(), dir.to_path_buf());
    let (d4, d5) = (dir.to_path_buf(), dir.to_path_buf());
    let (m4, m5) = (manifest(dir).ok(), manifest(dir).ok());
    rt.define_module("plugin_test", move |module| {
        module.define("session", move |call| {
            let Some(m) = manifest_for_sessions.as_ref() else {
                return Err(PyError::new("session() needs a plugin.json", "ValueError"));
            };
            let mock = call.value(0, Some("mock")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(serde_json::Value::Null);
            let settings = call.value(1, Some("settings")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(serde_json::Value::Null);
            let source = read_inside(&d3, &m.entry)?;
            let config = session_config(&d3, m, settings);
            let mut session = Session::new(&config.to_string()).map_err(|e| PyError::new(e, "ValueError"))?;
            let mock = (!mock.is_null()).then(|| Mock::new(mock));
            if let Some(mock) = &mock {
                session.set_bridge(Some(mock.bridge()));
            }
            Ok(session_value(session, source, mock))
        });
        module.define("preview", move |call| preview_call(call, &d4, m4.as_ref(), false));
        module.define("thumbnail", move |call| preview_call(call, &d5, m5.as_ref(), true));
        module.define("read", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            read_inside(&d1, &name).map(Value::from)
        });
        module.define("load", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            let wm = call.value(1, Some("wm")).cloned().unwrap_or(Value::None);
            let ui = call.value(2, Some("ui")).cloned().unwrap_or(Value::None);
            let source = read_inside(&d2, &name)?;
            call.run_module(&source, vec![("wm".into(), wm), ("ui".into(), ui)])
        });
    });
    rt.set("plugin_test", Some(Value::Module(Rc::from("plugin_test"))));
    Ok(rt)
}

/// The results as JUnit XML, one suite a file.
fn junit(results: &[TestResult], elapsed: Duration, dir: &Path) -> String {
    let esc = |s: &str| {
        s.chars()
            .filter(|c| !c.is_control() || matches!(c, '\n' | '\t' | '\r'))
            .collect::<String>()
            .replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;")
    };
    let failures = results.iter().filter(|r| !r.ok).count();
    let mut out = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    out.push_str(&format!("<testsuites name=\"wmpy test\" tests=\"{}\" failures=\"{failures}\" time=\"{:.3}\">\n", results.len(), elapsed.as_secs_f64()));
    let mut files: Vec<&str> = results.iter().map(|r| r.file.as_str()).collect();
    files.dedup();
    let source = folder_source(dir);
    for file in files {
        let suite: Vec<&TestResult> = results.iter().filter(|r| r.file == file).collect();
        let time: f64 = suite.iter().map(|r| r.duration.as_secs_f64()).sum();
        out.push_str(&format!(
            "  <testsuite name=\"{}\" tests=\"{}\" failures=\"{}\" time=\"{time:.3}\">\n",
            esc(file),
            suite.len(),
            suite.iter().filter(|r| !r.ok).count()
        ));
        for r in suite {
            out.push_str(&format!("    <testcase classname=\"{}\" name=\"{}\" time=\"{:.3}\"", esc(file), esc(&r.name), r.duration.as_secs_f64()));
            if r.ok && r.output.is_empty() {
                out.push_str("/>\n");
                continue;
            }
            out.push_str(">\n");
            if let Some(d) = &r.error {
                out.push_str(&format!(
                    "      <failure type=\"{}\" message=\"{}\">{}</failure>\n",
                    esc(d.code.as_deref().unwrap_or("Error")),
                    esc(&d.message),
                    esc(&d.render(&source, false))
                ));
            }
            if !r.output.is_empty() {
                out.push_str(&format!("      <system-out>{}</system-out>\n", esc(&r.output)));
            }
            out.push_str("    </testcase>\n");
        }
        out.push_str("  </testsuite>\n");
    }
    out.push_str("</testsuites>\n");
    out
}

/// Runs the tests, then again each time a file in [dir] changes, until stopped.
fn watch(dir: &Path, args: &TestArgs) -> u8 {
    let snapshot = || -> Vec<(String, Option<SystemTime>, u64)> {
        crate::project::files(dir)
            .into_iter()
            .filter(|f| !f.ends_with(".wmplugin"))
            .map(|f| {
                let meta = std::fs::metadata(dir.join(&f)).ok();
                (f, meta.as_ref().and_then(|m| m.modified().ok()), meta.map(|m| m.len()).unwrap_or(0))
            })
            .collect()
    };
    let once = TestArgs { dir: dir.to_path_buf(), filter: args.filter.clone(), json: args.json, junit: args.junit.clone(), watch: false };
    loop {
        let seen = snapshot();
        test(dir, &once);
        eprintln!("watching {} for changes (Ctrl-C to stop)", crate::project::shown(dir));
        loop {
            std::thread::sleep(Duration::from_millis(300));
            let now = snapshot();
            if now != seen {
                let changed = now.iter().find(|n| !seen.contains(n)).or_else(|| seen.iter().find(|s| !now.contains(s))).map(|n| n.0.clone());
                eprintln!("\n-- {} changed, running again --\n", changed.as_deref().unwrap_or("a file"));
                // Let an editor finish writing before reading.
                std::thread::sleep(Duration::from_millis(100));
                break;
            }
        }
    }
}

/// `plugin_test.preview(id, file, mock=None, name=None, mime=None)`, and
/// `thumbnail` with the same arguments.
fn preview_call(call: &mut wm_python::interp::Call, dir: &Path, m: Option<&Manifest>, thumb: bool) -> Result<Value, PyError> {
    let Some(m) = m else {
        return Err(PyError::new("preview() needs a plugin.json", "ValueError"));
    };
    let id: String = call.arg(0, Some("id"))?;
    let (data, file_name) = match call.value(1, Some("file")) {
        Some(Value::Bytes(b)) => (b.to_vec(), "file".to_string()),
        Some(Value::Str(name)) => {
            let path = inside(dir, name).map_err(|e| PyError::new(e, "ValueError"))?;
            let data = std::fs::read(&path).map_err(|e| PyError::new(format!("cannot read '{name}': {e}"), "ValueError"))?;
            let base = name.rsplit('/').next().unwrap_or(name).to_string();
            (data, base)
        }
        _ => return Err(PyError::new("preview() needs a file name inside the plugin folder, or bytes", "TypeError")),
    };
    let mock = call.value(2, Some("mock")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(serde_json::Value::Null);
    let name = match call.value(3, Some("name")) {
        Some(Value::Str(n)) => n.to_string(),
        _ => file_name,
    };
    let mime = match call.value(4, Some("mime")) {
        Some(Value::Str(t)) => t.to_string(),
        _ => "application/octet-stream".to_string(),
    };
    let max = m.previews.iter().find(|(p, _)| *p == id).map_or(DEFAULT_PREVIEW_BYTES, |(_, b)| *b);
    let head = data[..data.len().min(max)].to_vec();
    let extension = name.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default();
    let request = serde_json::json!({
        "id": id, "name": name, "extension": extension, "mime": mime, "size": data.len(),
        "modified": null, "truncated": data.len() > max,
    });
    let source = read_inside(dir, &m.entry)?;
    let mut config = session_config(dir, m, serde_json::Value::Null);
    config["mode"] = serde_json::Value::from("action");
    let mut session = Session::new(&config.to_string()).map_err(|e| PyError::new(e, "ValueError"))?;
    let _ = session.load(&source);
    // preview.read answers from the whole file; everything else from the mock.
    let mock = (!mock.is_null()).then(|| Mock::new(mock));
    let whole = Rc::new(data);
    let mut answer = mock.as_ref().map(|m| m.bridge());
    session.set_bridge(Some(Box::new(move |op: &str, args: &str| {
        if op == "preview.read" {
            let a: serde_json::Value = serde_json::from_str(args).unwrap_or_default();
            let offset = a["offset"].as_u64().unwrap_or(0) as usize;
            let length = (a["length"].as_u64().unwrap_or(0) as usize).min(1 << 20);
            let from = offset.min(whole.len());
            let to = offset.saturating_add(length).min(whole.len());
            use base64::Engine;
            let text = base64::engine::general_purpose::STANDARD.encode(&whole[from..to]);
            return serde_json::json!({"value": text}).to_string();
        }
        match answer.as_mut() {
            Some(f) => f(op, args),
            None => serde_json::json!({"error": {"kind": "NotImplementedError", "message": format!("no mock reply for {op}")}}).to_string(),
        }
    })));
    let result = if thumb { session.thumbnail(&request.to_string(), &head) } else { session.preview(&request.to_string(), &head) };
    let json: serde_json::Value = serde_json::from_str(&result).map_err(|e| PyError::new(e.to_string(), "ValueError"))?;
    Ok(wm_python::host::json_to_value(&json))
}

/// Most rounds [settle](session_value) answers before giving up: a plugin
/// whose every answer starts another call would otherwise never stop.
const MAX_SETTLE_ROUNDS: usize = 64;

/// A session as the dict `plugin_test.session()` returns.
fn session_value(session: Session, source: String, mock: Option<Rc<Mock>>) -> Value {
    use wm_python::interp::HostFunction;
    let s = Rc::new(RefCell::new(session));
    let decode = |text: String| -> Result<Value, PyError> {
        let json: serde_json::Value = serde_json::from_str(&text).map_err(|e| PyError::new(e.to_string(), "ValueError"))?;
        Ok(wm_python::host::json_to_value(&json))
    };
    let (s6, m6, m7) = (s.clone(), mock.clone(), mock);
    // Answer every waiting then= call from the mock, round after round,
    // since an answer's callback may start the next call.
    let settle = HostFunction::new("settle", move |_| {
        let Some(mock) = m6.as_ref() else {
            return Err(PyError::new("settle() needs a session made with mock=", "ValueError"));
        };
        // The last answer's result, unless one along the way failed: that
        // one, so an error in an early callback is not hidden by later ones.
        let mut last = None;
        let mut first_failure = None;
        for _ in 0..MAX_SETTLE_ROUNDS {
            let waiting = std::mem::take(&mut *mock.waiting.borrow_mut());
            if waiting.is_empty() {
                return match first_failure.or(last) {
                    Some(text) => decode(text),
                    None => decode(s6.borrow_mut().render()),
                };
            }
            for (token, op, args) in waiting {
                let reply = mock.reply(&op, &args);
                let value = match reply.get("error") {
                    Some(_) => reply,
                    None => reply.get("value").cloned().unwrap_or(serde_json::Value::Null),
                };
                let event = serde_json::json!({"type": "result", "token": token, "value": value});
                let result = s6.borrow_mut().event(&event.to_string());
                let failed = serde_json::from_str::<serde_json::Value>(&result).ok().is_some_and(|v| v["ok"] == serde_json::Value::Bool(false));
                if failed && first_failure.is_none() {
                    first_failure = Some(result.clone());
                }
                last = Some(result);
            }
        }
        Err(PyError::new(format!("settle(): calls still waiting after {MAX_SETTLE_ROUNDS} rounds"), "RuntimeError"))
    });
    let calls = HostFunction::new("calls", move |_| {
        let list = m7.as_ref().map(|m| m.calls.borrow().clone()).unwrap_or_default();
        Ok(wm_python::host::json_to_value(&serde_json::Value::Array(list)))
    });
    let (s1, s2, s3, s4, s5) = (s.clone(), s.clone(), s.clone(), s.clone(), s.clone());
    let load = HostFunction::new("load", move |_| decode(s1.borrow_mut().load(&source)));
    let render = HostFunction::new("render", move |_| decode(s2.borrow_mut().render()));
    let event = HostFunction::new("event", move |call| {
        let e = call.value(0, Some("event")).cloned().unwrap_or(Value::None);
        let text = wm_python::host::v5::to_json(&e, 0).to_string();
        decode(s3.borrow_mut().event(&text))
    });
    let transform = HostFunction::new("transform", move |call| {
        let text: String = call.arg(0, Some("text"))?;
        let id: String = call.arg_or(1, Some("action_id"), String::new())?;
        decode(s4.borrow_mut().transform(&text, &id))
    });
    let storage = HostFunction::new("storage", move |_| decode(s5.borrow().storage_json()));
    Value::dict(vec![
        ("load", Value::HostFunction(load)),
        ("render", Value::HostFunction(render)),
        ("event", Value::HostFunction(event)),
        ("transform", Value::HostFunction(transform)),
        ("storage", Value::HostFunction(storage)),
        ("settle", Value::HostFunction(settle)),
        ("calls", Value::HostFunction(calls)),
    ])
}

fn render(dir: &Path, mock: Option<&Path>) -> u8 {
    let m = match manifest(dir) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    let source = match inside(dir, &m.entry).and_then(|p| std::fs::read_to_string(p).map_err(|e| e.to_string())) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{}: {e}", m.entry);
            return 2;
        }
    };
    let config = session_config(dir, &m, serde_json::Value::Null);
    let mut session = match Session::new(&config.to_string()) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    if let Some(path) = mock {
        let parsed = std::fs::read_to_string(path).map_err(|e| e.to_string()).and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string()));
        match parsed {
            Ok(v) => session.set_bridge(Some(Mock::new(v).bridge())),
            Err(e) => {
                eprintln!("{}: {e}", path.display());
                return 2;
            }
        }
    }
    let result = session.load(&source);
    println!("{result}");
    let ok = serde_json::from_str::<serde_json::Value>(&result).ok().and_then(|v| v["ok"].as_bool()).unwrap_or(false);
    if ok {
        0
    } else {
        1
    }
}
