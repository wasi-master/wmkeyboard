//! `wmpy profile`: which functions take the time.
//!
//! ```text
//! wmpy profile [dir] [--scenario file] [--tests] [-k text] [--mock file] [--top N] [--json]
//! ```
//!
//! The interpreter has no profiler, so wmpy copies the plugin to a
//! temporary folder with every `def` wrapped by a recorder
//! ([instrument]), then drives the copy as `wmpy budget` does: a scenario,
//! the tests, or a click on each button of the first draw. The report lists
//! the functions that took the most time, with their calls and the average,
//! and each kind of phase's steps from the session results.
//!
//! Time is wall time around each call, the calls it makes included, and
//! includes the recorder's own work. Steps are per phase, not per function:
//! the interpreter has no per-function step counter yet. Errors point at
//! the plugin's own files and lines.

pub mod instrument;

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use serde_json::{json, Value as Json};

use crate::budget::drive::{self, HostCall, Outcome, Setup, TempDir};
use crate::budget::{count, plan_of, suite, time};
use crate::diag::{color_stderr, folder_source, Diag};
use instrument::{Instrumented, Site, MODULE};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// A JSON list of events to run instead of the default clicks, or
    /// {"events": [...], "mock": {...}, "settings": {...}}
    #[arg(long, value_name = "FILE", conflicts_with = "tests")]
    pub scenario: Option<PathBuf>,
    /// Run the plugin's tests instead
    #[arg(long)]
    pub tests: bool,
    /// Run only the tests whose file::name contains this text (implies --tests)
    #[arg(short = 'k', value_name = "TEXT")]
    pub filter: Option<String>,
    /// A JSON file answering host calls by op name before the dev host does
    #[arg(long, value_name = "FILE")]
    pub mock: Option<PathBuf>,
    /// How many functions to list
    #[arg(long, default_value_t = 20)]
    pub top: usize,
    /// Print the report as JSON
    #[arg(long)]
    pub json: bool,
}

/// One function's totals.
#[derive(Clone, Debug, Default)]
struct Totals {
    calls: u64,
    seconds: f64,
}

pub fn run(args: Args) -> u8 {
    let dir = match crate::project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            eprint!("{}", d.render(&|_| None, color_stderr()));
            return 2;
        }
    };
    let plan = match plan_of(args.scenario.as_deref(), args.tests || args.filter.is_some(), args.filter.clone()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let copy = match instrumented_copy(&dir) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    // Calls a test makes to the plugin's modules directly, in its own interpreter.
    let sink: Rc<RefCell<Vec<Json>>> = Rc::new(RefCell::new(Vec::new()));
    let sink2 = sink.clone();
    let prepare = move |rt: &mut wm_python::interp::Runtime| {
        let rows = sink2.clone();
        rt.define_module("_wmpy_sink", move |m| {
            m.define("add", move |call| {
                let v = call.value(0, Some("rows")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(Json::Null);
                rows.borrow_mut().push(v);
                Ok(wm_python::interp::Value::None)
            });
        });
        rt.add_source_module(MODULE, &instrument::recorder(true), false);
    };
    let setup = Setup {
        dir: copy.dir.path(),
        home: &dir,
        plan: &plan,
        mock: args.mock.as_deref(),
        settle_for: Duration::from_secs(20),
        backend: suite::Backend::Mock,
        prepare: Some(&prepare),
    };
    let mut outcome = match drive::run(&setup) {
        Ok(o) => o,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    remap(&mut outcome, &copy.files);
    let mut rows: Vec<Json> = outcome
        .calls
        .iter()
        .filter_map(|c| match c {
            HostCall::Answered { op, args, .. } if op == "plugin.refresh" => args.get("wmpyProfile").cloned(),
            _ => None,
        })
        .collect();
    rows.extend(sink.borrow().iter().cloned());
    let totals = totals(&rows);
    let functions = ranked(&copy.sites, &totals);
    if args.json {
        println!("{}", json_report(&outcome, &functions, args.top));
    } else {
        print!("{}", text_report(&outcome, &functions, args.top, &dir, color_stderr() && std::io::IsTerminal::is_terminal(&std::io::stdout())));
    }
    u8::from(!outcome.loaded)
}

/// The plugin's copy with its modules instrumented, and each file's line map.
struct Copy {
    dir: TempDir,
    files: HashMap<String, Instrumented>,
    sites: Vec<Site>,
}

fn instrumented_copy(dir: &Path) -> Result<Copy, String> {
    let m = crate::project::manifest(dir)?;
    if m.api < 5 {
        return Err("wmpy profile needs an API 5 plugin (\"apiVersion\": 5 in plugin.json)".into());
    }
    let tmp = TempDir::new("profile")?;
    let mut files = HashMap::new();
    let mut sites = Vec::new();
    let mut next = 0;
    for name in crate::project::files(dir) {
        let from = dir.join(&name);
        let to = tmp.path().join(&name);
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let module = name.ends_with(".py") && (name == m.entry || !wm_python_tooling::manifest::is_dev_file(&name));
        if module {
            let source = std::fs::read_to_string(&from).map_err(|e| format!("{name}: {e}"))?;
            let done = instrument::instrument(&name, &source, &mut next);
            std::fs::write(&to, &done.source).map_err(|e| e.to_string())?;
            sites.extend(done.sites.iter().cloned());
            files.insert(name, done);
        } else {
            std::fs::copy(&from, &to).map_err(|e| format!("{name}: {e}"))?;
        }
    }
    std::fs::write(tmp.path().join(format!("{MODULE}.py")), instrument::recorder(false)).map_err(|e| e.to_string())?;
    Ok(Copy { dir: tmp, files, sites })
}

/// Errors from the copy, pointed back at the plugin's own lines.
fn remap(o: &mut Outcome, files: &HashMap<String, Instrumented>) {
    let entry = o.entry.clone();
    let fix = |d: &mut Diag| {
        let file = d.file.clone().unwrap_or_else(|| entry.clone());
        if let (Some(i), Some(line)) = (files.get(&file), d.line) {
            d.line = Some(i.original(line));
        }
        for f in &mut d.traceback {
            let file = f.file.clone().unwrap_or_else(|| entry.clone());
            if let (Some(i), Some(line)) = (files.get(&file), f.line) {
                f.line = Some(i.original(line));
            }
        }
    };
    for s in &mut o.steps {
        if let Some(d) = s.error.as_mut() {
            fix(d);
        }
    }
    for (_, d) in &mut o.failures {
        fix(d);
    }
}

/// Calls and seconds by key, from the recorder's rows `[[key, calls, seconds], ...]`.
fn totals(batches: &[Json]) -> HashMap<usize, Totals> {
    let mut out: HashMap<usize, Totals> = HashMap::new();
    for batch in batches {
        for row in batch.as_array().into_iter().flatten() {
            let (Some(key), Some(calls)) = (row[0].as_u64(), row[1].as_u64()) else { continue };
            let t = out.entry(key as usize).or_default();
            t.calls += calls;
            t.seconds += row[2].as_f64().unwrap_or(0.0);
        }
    }
    out
}

/// Every function that ran, most time first.
fn ranked(sites: &[Site], totals: &HashMap<usize, Totals>) -> Vec<(Site, Totals)> {
    let mut out: Vec<(Site, Totals)> = sites.iter().filter_map(|s| totals.get(&s.key).map(|t| (s.clone(), t.clone()))).collect();
    out.sort_by(|a, b| b.1.seconds.total_cmp(&a.1.seconds).then(b.1.calls.cmp(&a.1.calls)));
    out
}

const TIME_NOTE: &str = "time is wall time inside each function, the calls it makes included, and includes the profiler's own work";
const STEPS_NOTE: &str = "steps are per phase, not per function: the interpreter has no per-function step counter yet";

fn text_report(o: &Outcome, functions: &[(Site, Totals)], top: usize, dir: &Path, color: bool) -> String {
    let paint = |code: &str, t: &str| if color { format!("\x1b[{code}m{t}\x1b[0m") } else { t.to_string() };
    let mut out = format!("wmpy profile: {}, driven by {}\n\n", crate::project::shown(dir), o.what);
    if functions.is_empty() {
        out.push_str("no function of the plugin ran\n");
    } else {
        let width = functions.iter().take(top).map(|(s, _)| s.name.chars().count()).max().unwrap_or(8).clamp(8, 40);
        let place_width = functions.iter().take(top).map(|(s, _)| s.file.len() + s.line.to_string().len() + 1).max().unwrap_or(5).max(5);
        let header = format!("{:<width$}  {:<place_width$}  {:>7}  {:>10}  {:>10}", "function", "where", "calls", "total", "average");
        out.push_str(&paint("1", &header));
        out.push('\n');
        for (s, t) in functions.iter().take(top) {
            let place = format!("{}:{}", s.file, s.line);
            let avg = t.seconds * 1e6 / t.calls.max(1) as f64;
            out.push_str(&format!("{:<width$}  {:<place_width$}  {:>7}  {:>10}  {:>10}\n", s.name, place, t.calls, time(t.seconds * 1e6), time(avg)));
        }
        if functions.len() > top {
            out.push_str(&format!("... {} more (--top {} lists them)\n", functions.len() - top, functions.len()));
        }
    }
    out.push_str(&format!("  = note: {TIME_NOTE}\n"));
    out.push_str(&format!("  = note: {STEPS_NOTE}\n\n"));
    let kinds = phase_totals(o);
    out.push_str(&paint("1", &format!("{:<10} {:>5}  {:>11}  {:>10}  {:>10}", "phase", "runs", "steps total", "steps max", "time total")));
    out.push('\n');
    for (name, runs, total, max, micros) in &kinds {
        out.push_str(&format!("{name:<10} {runs:>5}  {:>11}  {:>10}  {:>10}\n", count(*total), count(*max), time(*micros as f64)));
    }
    let source = folder_source(dir);
    if !o.loaded {
        out.push_str("\nthe plugin did not load:\n");
    }
    for s in o.steps.iter().filter(|s| s.error.is_some()).take(3) {
        if let Some(d) = &s.error {
            out.push_str(&format!("\nin {}:\n{}", s.label, d.render(&source, color)));
        }
    }
    for (id, d) in o.failures.iter().take(3) {
        out.push_str(&format!("\nFAIL {id}\n{}", d.render(&source, color)));
    }
    for n in &o.notes {
        out.push_str(&format!("  = note: {n}\n"));
    }
    out
}

/// `(phase, runs, steps total, steps max, micros total)`, in the budget's order.
fn phase_totals(o: &Outcome) -> Vec<(String, usize, u64, u64, u64)> {
    let mut out: Vec<(String, usize, u64, u64, u64)> = Vec::new();
    for p in o.steps.iter().flat_map(|s| s.phases.iter()) {
        match out.iter_mut().find(|k| k.0 == p.kind) {
            Some(k) => {
                k.1 += 1;
                k.2 += p.steps;
                k.3 = k.3.max(p.steps);
                k.4 += p.micros;
            }
            None => out.push((p.kind.clone(), 1, p.steps, p.steps, p.micros)),
        }
    }
    const ORDER: &[&str] = &["load", "event", "render", "action", "preview", "thumbnail"];
    out.sort_by_key(|k| ORDER.iter().position(|o| *o == k.0).unwrap_or(ORDER.len()));
    out
}

fn json_report(o: &Outcome, functions: &[(Site, Totals)], top: usize) -> Json {
    json!({
        "ok": o.loaded, "drivenBy": o.what,
        "functions": functions.iter().take(top).map(|(s, t)| json!({
            "name": s.name, "file": s.file, "line": s.line, "calls": t.calls,
            "seconds": t.seconds, "averageSeconds": t.seconds / t.calls.max(1) as f64,
        })).collect::<Vec<_>>(),
        "phases": phase_totals(o).into_iter().map(|(name, runs, total, max, micros)| json!({
            "phase": name, "runs": runs, "steps": total, "stepsMax": max, "micros": micros,
        })).collect::<Vec<_>>(),
        "errors": o.steps.iter().filter_map(|s| s.error.as_ref().map(|d| json!({"call": s.label, "error": d.to_json()}))).collect::<Vec<_>>(),
        "failures": o.failures.iter().map(|(id, d)| json!({"test": id, "error": d.to_json()})).collect::<Vec<_>>(),
        "notes": [TIME_NOTE, STEPS_NOTE],
        "runNotes": o.notes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_add_up_by_function() {
        let sites = vec![
            Site { key: 0, name: "render".into(), file: "main.py".into(), line: 3 },
            Site { key: 1, name: "slow".into(), file: "main.py".into(), line: 9 },
            Site { key: 2, name: "never".into(), file: "main.py".into(), line: 12 },
        ];
        let rows = vec![json!([[0, 1, 0.01], [1, 2, 0.5]]), json!([[1, 1, 0.25]])];
        let ranked = ranked(&sites, &totals(&rows));
        assert_eq!(ranked.len(), 2);
        assert_eq!((ranked[0].0.name.as_str(), ranked[0].1.calls), ("slow", 3));
        assert!((ranked[0].1.seconds - 0.75).abs() < 1e-9);
    }
}
