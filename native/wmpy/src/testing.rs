//! `wmpy test` and `wmpy render`: WM Keyboard's Python plugins, headless.
//!
//! ```text
//! wmpy test <plugin-dir> [-k text] [-j N] [-x] [--json] [--junit file] [--watch] [--locale tag] [--theme t] [--surface s]
//! wmpy render <plugin-dir> [--mock <file>] [--set key=value]... [--locale tag] [--theme t] [--surface s]
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
//! * `plugin_test.session(mock=None, settings=None, locale=None, theme=None,
//!   surface=None)`: the plugin itself as the keyboard runs it (API 5 or
//!   not), with [mock] answering host calls as the mock file does, in the
//!   run's `--locale`/`--theme`/`--surface` unless the call names others. It
//!   returns an object of functions (`s.load()`, or `s["load"]()`), each
//!   giving back the result (`r.ok`, `r.ui`, `r.error`): `load()`,
//!   `render()`, `event(e)`, `transform(text, id)`, plus `storage()`,
//!   `calls()` (every host call so far, `{"op", "args"}`) and `settle()`,
//!   which answers each waiting `then=` call from the mock as its `result`
//!   event, round after round until none waits. When a test fails on a
//!   result whose call failed (`assert r.ok, r.error`), the report shows the
//!   plugin's error, in its own file with its traceback.
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
//! `-j N` runs N test files at once, each in its own interpreter; their
//! prints then come with each result rather than as they happen. `-x`
//! stops at the first failure.
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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use serde_json::json;
use wm_python::host::api::{self, PluginInfo};
use wm_python::host::files::{Reminders, Storage};
use wm_python::host::Session;
use wm_python::interp::{Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};

use crate::diag::{color_stderr, folder_source, Diag};
use crate::mock::Mock;
use crate::project::{inside, manifest, modules, read_inside, session_config, session_config_in, Context, Manifest, DEFAULT_PREVIEW_BYTES};

/// `wmpy test`: run the plugin's tests.
#[derive(clap::Args, Debug, Clone)]
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
    /// Run this many test files at once [default: 1, or wmpy.toml's test.jobs; 0 is one per processor]
    #[arg(short = 'j', long, value_name = "N")]
    pub jobs: Option<usize>,
    /// Stop at the first failing test
    #[arg(short = 'x', long)]
    pub fail_fast: bool,
    #[command(flatten)]
    pub look: crate::look::Look,
}

/// `wmpy render`: load the plugin as a panel and print the first draw's JSON.
#[derive(clap::Args, Debug)]
pub struct RenderArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// A JSON file answering host calls by op name [default: wmpy.toml's run.mock]
    #[arg(long)]
    pub mock: Option<PathBuf>,
    /// A setting's value for this draw, over plugin.json's default (repeatable)
    #[arg(long = "set", value_name = "KEY=VALUE", value_parser = crate::devhost::parse_set)]
    pub sets: Vec<(String, String)>,
    /// Indent the JSON
    #[arg(long)]
    pub pretty: bool,
    /// Accepted for scripts that pass it to every command: render always prints JSON
    #[arg(long, hide = true)]
    pub json: bool,
    #[command(flatten)]
    pub look: crate::look::Look,
}

pub fn run_test(mut args: TestArgs) -> u8 {
    let dir = if args.dir.is_file() { args.dir.parent().map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from(".")) } else { args.dir.clone() };
    let dir = if dir.as_os_str().is_empty() { PathBuf::from(".") } else { dir };
    if !dir.is_dir() {
        let mut d = Diag::error(format!("there is no folder {}", args.dir.display()));
        d.help = Some("give the plugin's folder, the one that holds plugin.json and test.py".into());
        if args.json {
            println!("{}", json!({"ok": false, "passed": 0, "failed": 0, "durationMs": 0, "tests": [], "error": d.to_json()}));
        } else {
            eprint!("{}", d.render(&|_| None, color_stderr()));
        }
        return 2;
    }
    let config = match crate::config::load_or_report(&dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    args.look.apply(&dir, &config);
    if args.jobs.is_none() {
        args.jobs = config.test.jobs;
    }
    if !args.fail_fast {
        args.fail_fast = config.test.fail_fast.unwrap_or(false);
    }
    if args.watch {
        return watch(&dir, &args);
    }
    test(&dir, &args)
}

pub fn run_render(args: RenderArgs) -> u8 {
    let config = match crate::config::load_or_report(&args.dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    args.look.apply(&args.dir, &config);
    let mock = args.mock.clone().or_else(|| config.run.mock.as_ref().map(|m| crate::config::in_dir(&args.dir, m)));
    let mut sets: Vec<(String, String)> = config.run.settings.iter().filter(|(k, _)| !args.sets.iter().any(|(s, _)| s == k)).cloned().collect();
    sets.extend(args.sets.iter().cloned());
    render(&args.dir, mock.as_deref(), &sets, args.pretty)
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
    let jobs = match args.jobs {
        Some(0) => std::thread::available_parallelism().map_or(1, |n| n.get()),
        Some(n) => n,
        None => 1,
    }
    .min(files.len().max(1));
    let stop = Arc::new(AtomicBool::new(false));
    let opts = RunOpts { filter: args.filter.clone(), fail_fast: args.fail_fast, stop: stop.clone() };
    let mut results: Vec<TestResult> = Vec::new();
    if jobs <= 1 {
        for file in &files {
            if stop.load(Ordering::Relaxed) {
                break;
            }
            results.extend(run_file(dir, file, &opts, style));
        }
    } else {
        results = run_parallel(dir, &files, &opts, style, jobs);
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
                let stopped = if stop.load(Ordering::Relaxed) { " (stopped at the first failure, -x)" } else { "" };
                eprintln!("{passed} passed, {failed} failed in {:.2} s{stopped}", elapsed.as_secs_f64());
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

/// What every test file of a run shares.
#[derive(Clone)]
struct RunOpts {
    filter: Option<String>,
    fail_fast: bool,
    /// Set at the first failure under `-x`: no more tests start.
    stop: Arc<AtomicBool>,
}

impl RunOpts {
    fn failed(&self, r: &TestResult) {
        if !r.ok && self.fail_fast {
            self.stop.store(true, Ordering::Relaxed);
        }
    }
}

/// The test files on [jobs] threads, each in its own interpreter, the
/// results in the order of [files] and shown as each file finishes in turn.
fn run_parallel(dir: &Path, files: &[String], opts: &RunOpts, style: Style, jobs: usize) -> Vec<TestResult> {
    let next = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let (tx, rx) = std::sync::mpsc::channel::<(usize, Vec<TestResult>)>();
    let mut workers = Vec::new();
    for _ in 0..jobs {
        let (next, tx, files, dir, opts) = (next.clone(), tx.clone(), files.to_vec(), dir.to_path_buf(), opts.clone());
        let worker = std::thread::Builder::new().stack_size(256 << 20).spawn(move || loop {
            let i = next.fetch_add(1, Ordering::Relaxed);
            if i >= files.len() || opts.stop.load(Ordering::Relaxed) {
                return;
            }
            // Collected quietly: the main thread shows them in order.
            let out = run_file(&dir, &files[i], &opts, Style::Json);
            if tx.send((i, out)).is_err() {
                return;
            }
        });
        if let Ok(w) = worker {
            workers.push(w);
        }
    }
    drop(tx);
    let mut done: std::collections::BTreeMap<usize, Vec<TestResult>> = std::collections::BTreeMap::new();
    let mut shown = 0;
    let mut out = Vec::new();
    for (i, results) in rx {
        done.insert(i, results);
        while let Some(results) = done.remove(&shown) {
            for r in results {
                if style == Style::Rich {
                    if !r.output.is_empty() {
                        print!("{}", r.output);
                    }
                    show(&r, dir);
                }
                out.push(r);
            }
            shown += 1;
        }
    }
    for w in workers {
        let _ = w.join();
    }
    // Files after one that never ran (a stop under -x) are left out.
    for (_, results) in done {
        out.extend(results);
    }
    out
}

/// Runs one test file: its top level, then each selected test function.
fn run_file(dir: &Path, file: &str, opts: &RunOpts, style: Style) -> Vec<TestResult> {
    let filter = opts.filter.as_deref();
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
    let (mut rt, last_failure) = match runtime(dir, output.clone(), style != Style::Json) {
        Ok(rt) => rt,
        Err(e) => {
            let r = TestResult { file: file.into(), name: MODULE.into(), ok: false, duration: started.elapsed(), output: take(&output), error: Some(Diag::from_py_error(&e, file)) };
            opts.failed(&r);
            return vec![finished_or_legacy(r, &e, dir, style)];
        }
    };
    // The test file runs as the script: its errors and frames name it.
    rt.set_module_file("__main__", file);
    rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
    if let Err(e) = rt.load(&source) {
        let error = failure_diag(&e, file, &source, &last_failure);
        let r = TestResult { file: file.into(), name: MODULE.into(), ok: false, duration: started.elapsed(), output: take(&output), error: Some(error) };
        opts.failed(&r);
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
        if opts.stop.load(Ordering::Relaxed) {
            break;
        }
        let started = Instant::now();
        rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
        *last_failure.borrow_mut() = None;
        let result = rt.call(&name, Vec::new());
        let mut text = std::mem::take(&mut carried);
        text.push_str(&take(&output));
        let r = match result {
            Ok(_) => TestResult { file: file.into(), name, ok: true, duration: started.elapsed(), output: text, error: None },
            Err(e) => TestResult { file: file.into(), name, ok: false, duration: started.elapsed(), output: text, error: Some(failure_diag(&e, file, &source, &last_failure)) },
        };
        opts.failed(&r);
        out.push(finished(r, dir, style));
    }
    out
}

/// The last session call of a test that failed: its error as the session
/// gave it, and the plugin's entry, which the error blames when it names no file.
type LastFailure = Rc<RefCell<Option<(serde_json::Value, String)>>>;

/// What a failed test reports. When it failed on a session result whose
/// call failed (`assert r.ok, r.error`), the plugin's error leads, in the
/// plugin's own file with its traceback, and the assert follows as a note;
/// a test that went on past the failed call and broke on what was missing
/// gets the plugin's error as a note.
fn failure_diag(e: &PyError, file: &str, source: &str, last: &LastFailure) -> Diag {
    let own = Diag::from_py_error(e, file);
    let Some((err, entry)) = last.borrow().clone() else { return own };
    let session = Diag::from_session_error(&err, &entry);
    let at = own.line.map(|l| format!("{}:{l}", own.file.as_deref().unwrap_or(file))).unwrap_or_else(|| file.to_string());
    let line_text = own.line.and_then(|l| source.lines().nth(l.saturating_sub(1) as usize)).map(str::trim).filter(|t| !t.is_empty());
    let raw_message = err.get("message").and_then(|m| m.as_str()).unwrap_or("");
    let about_it = e.kind == "AssertionError"
        && (e.message.trim_start().starts_with("{'kind'")
            || e.message.trim().is_empty()
            || (!session.message.is_empty() && (e.message.contains(&session.message) || e.message == raw_message)));
    if about_it {
        let mut d = session;
        let assert = match line_text {
            Some(t) => format!("the test stopped at {at}: {t}"),
            None => format!("the test stopped at {at}"),
        };
        d.notes.insert(0, assert);
        return d;
    }
    let mut d = own;
    let place = match (&session.file, session.line) {
        (Some(f), Some(l)) => format!(" ({f}:{l})"),
        (Some(f), None) => format!(" ({f})"),
        _ => String::new(),
    };
    let kind = session.code.as_deref().map(|k| format!("{k}: ")).unwrap_or_default();
    d.notes.push(format!("the plugin's last call failed first: {kind}{}{place}", session.message));
    d
}

fn take(buffer: &Rc<RefCell<String>>) -> String {
    std::mem::take(&mut *buffer.borrow_mut())
}

/// Shows a result as it comes in, and hands it on.
fn finished(r: TestResult, dir: &Path, style: Style) -> TestResult {
    if style == Style::Rich {
        show(&r, dir);
    }
    r
}

/// One result's line, and its failure with the code frame. `--quiet` keeps
/// only the failures.
fn show(r: &TestResult, dir: &Path) {
    if r.ok && crate::output::quiet() {
        return;
    }
    let color = color_stderr();
    let paint = |code: &str, text: &str| if color { format!("\x1b[{code}m{text}\x1b[0m") } else { text.to_string() };
    let mark = if r.ok { paint("32", "ok  ") } else { paint("1;31", "FAIL") };
    eprintln!("{mark} {} ({} ms)", r.id(), r.duration.as_millis());
    if let Some(d) = &r.error {
        eprint!("{}", d.render(&folder_source(dir), color));
        eprintln!();
    }
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
/// into [output] (and to stdout when [echo]), and where its sessions put
/// the last call that failed.
fn runtime(dir: &Path, output: Rc<RefCell<String>>, echo: bool) -> Result<(Runtime, LastFailure), PyError> {
    let last_failure: LastFailure = Rc::new(RefCell::new(None));
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
    let failures = last_failure.clone();
    rt.define_module("plugin_test", move |module| {
        module.define("session", move |call| {
            let Some(m) = manifest_for_sessions.as_ref() else {
                return Err(PyError::new("session() needs a plugin.json", "ValueError"));
            };
            let mock = call.value(0, Some("mock")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(serde_json::Value::Null);
            let settings = call.value(1, Some("settings")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(serde_json::Value::Null);
            let ctx = context_arg(call, crate::project::context())?;
            let source = read_inside(&d3, &m.entry)?;
            let config = session_config_in(&d3, m, settings, &ctx);
            let mut session = Session::new(&config.to_string()).map_err(|e| PyError::new(e, "ValueError"))?;
            let mock = (!mock.is_null()).then(|| Mock::new(mock));
            if let Some(mock) = &mock {
                session.set_bridge(Some(mock.bridge()));
            }
            Ok(session_value(session, source, mock, failures.clone(), m.entry.clone()))
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
    Ok((rt, last_failure))
}

/// `locale=`, `theme=` and `surface=` of a `session()` call, over [base].
fn context_arg(call: &mut wm_python::interp::Call, base: Context) -> Result<Context, PyError> {
    let mut ctx = base;
    let text = |call: &mut wm_python::interp::Call, at: usize, name: &str| -> Option<String> {
        match call.value(at, Some(name)) {
            Some(Value::Str(s)) => Some(s.to_string()),
            _ => None,
        }
    };
    if let Some(l) = text(call, 2, "locale") {
        if !crate::project::is_locale_tag(&l) {
            return Err(PyError::new(format!("session(locale={l:?}): give a language tag such as \"bn\" or \"pt-BR\""), "ValueError"));
        }
        ctx.locale = l;
    }
    if let Some(t) = text(call, 3, "theme") {
        if t != "light" && t != "dark" {
            return Err(PyError::new(format!("session(theme={t:?}): the theme is \"light\" or \"dark\""), "ValueError"));
        }
        ctx.theme = t;
    }
    if let Some(s) = text(call, 4, "surface") {
        if !matches!(s.as_str(), "panel" | "float" | "action") {
            return Err(PyError::new(format!("session(surface={s:?}): the surface is \"panel\", \"float\" or \"action\""), "ValueError"));
        }
        ctx.surface = s;
    }
    Ok(ctx)
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
    let once = TestArgs { dir: dir.to_path_buf(), watch: false, ..args.clone() };
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

/// A session as the dict `plugin_test.session()` returns. Each result whose
/// call failed goes into [failures], for the report when the test then fails.
fn session_value(session: Session, source: String, mock: Option<Rc<Mock>>, failures: LastFailure, entry: String) -> Value {
    use wm_python::interp::HostFunction;
    let s = Rc::new(RefCell::new(session));
    let decode = move |text: String| -> Result<Value, PyError> {
        let json: serde_json::Value = serde_json::from_str(&text).map_err(|e| PyError::new(e.to_string(), "ValueError"))?;
        if json.get("ok") == Some(&serde_json::Value::Bool(false)) {
            if let Some(err) = json.get("error").filter(|e| e.is_object()) {
                *failures.borrow_mut() = Some((err.clone(), entry.clone()));
            }
        }
        Ok(wm_python::host::json_to_value(&json))
    };
    let (d1, d2, d3, d4, d5, d6) = (decode.clone(), decode.clone(), decode.clone(), decode.clone(), decode.clone(), decode);
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
                    Some(text) => d6(text),
                    None => d6(s6.borrow_mut().render()),
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
    let load = HostFunction::new("load", move |_| d1(s1.borrow_mut().load(&source)));
    let render = HostFunction::new("render", move |_| d2(s2.borrow_mut().render()));
    let event = HostFunction::new("event", move |call| {
        let e = call.value(0, Some("event")).cloned().unwrap_or(Value::None);
        let text = wm_python::host::v5::to_json(&e, 0).to_string();
        d3(s3.borrow_mut().event(&text))
    });
    let transform = HostFunction::new("transform", move |call| {
        let text: String = call.arg(0, Some("text"))?;
        let id: String = call.arg_or(1, Some("action_id"), String::new())?;
        d4(s4.borrow_mut().transform(&text, &id))
    });
    let storage = HostFunction::new("storage", move |_| d5(s5.borrow().storage_json()));
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

fn render(dir: &Path, mock: Option<&Path>, sets: &[(String, String)], pretty: bool) -> u8 {
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
    // Settings as the settings page would hold them: plugin.json's defaults, then each --set.
    let settings = match crate::devhost::plugin::PluginFacts::read(dir) {
        Ok(facts) => {
            if let Some((k, _)) = sets.iter().find(|(k, _)| facts.spec(k).is_none()) {
                eprintln!("error: --set {k}: plugin.json declares no setting '{k}'");
                return 2;
            }
            let none = std::env::temp_dir().join(format!("wmpy-render-{}-none.json", std::process::id()));
            crate::devhost::plugin::SettingValues::load(none, sets).json(&facts)
        }
        Err(_) => serde_json::Value::Null,
    };
    let config = session_config(dir, &m, settings);
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
    match serde_json::from_str::<serde_json::Value>(&result) {
        Ok(v) if pretty => println!("{}", serde_json::to_string_pretty(&v).unwrap_or(result.clone())),
        _ => println!("{result}"),
    }
    let ok = serde_json::from_str::<serde_json::Value>(&result).ok().and_then(|v| v["ok"].as_bool()).unwrap_or(false);
    if ok {
        0
    } else {
        1
    }
}
