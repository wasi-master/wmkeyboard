//! The runs `wmpy budget`, `wmpy profile` and `wmpy mock record` share: the
//! plugin loaded on the dev host and driven through a scenario, or a click
//! on each button of its first draw, or its tests ([super::suite]), with
//! every session call's phases and every host call kept.
//!
//! A run never changes the plugin's own `.wmpy/`: storage, reminders,
//! secrets and settings start from a copy of it in a temporary folder.
//! `then=` answers are waited for after each event (20 s at most), and a
//! dialog is answered with its first button so the flow behind it runs too.

use std::cell::RefCell;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};

use crate::devhost::bridge::{Observer, Seen};
use crate::devhost::{DevHost, Extra, Options, Ran};
use crate::diag::Diag;
use crate::tui::layout::{self, Action, Local};

/// What drives the plugin after it loads.
#[derive(Clone, Debug)]
pub enum Plan {
    /// A click on each button the first draw shows, then each timer once.
    Clicks,
    Scenario(Scenario),
    /// The plugin's own tests, the ones `-k` keeps.
    Tests { filter: Option<String> },
}

/// A scenario file: a JSON list of events, or `{"events": [...], "mock":
/// {...}, "settings": {...}}` with mock replies (as `plugin_test.session`'s
/// `mock=`) answering host calls before the dev host does.
#[derive(Clone, Debug, Default)]
pub struct Scenario {
    pub events: Vec<Json>,
    pub mock: Option<Json>,
    /// Setting values for the run, as `--set key=value` gives them.
    pub settings: Vec<(String, String)>,
}

pub fn read_scenario(path: &Path) -> Result<Scenario, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let v: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
    parse_scenario(&v).map_err(|e| format!("{}: {e}", path.display()))
}

pub fn parse_scenario(v: &Json) -> Result<Scenario, String> {
    let (events, mock, settings) = match v {
        Json::Array(events) => (events.clone(), None, Vec::new()),
        Json::Object(o) => {
            let events = match o.get("events") {
                Some(Json::Array(e)) => e.clone(),
                None => Vec::new(),
                Some(_) => return Err("\"events\" is a list of events".into()),
            };
            let mock = match o.get("mock") {
                Some(m @ Json::Object(_)) => Some(m.clone()),
                None | Some(Json::Null) => None,
                Some(_) => return Err("\"mock\" is an object of replies by op name, as plugin_test.session(mock=) takes".into()),
            };
            let settings = match o.get("settings") {
                Some(Json::Object(s)) => s
                    .iter()
                    .map(|(k, v)| {
                        let text = match v {
                            Json::String(t) => t.clone(),
                            other => other.to_string(),
                        };
                        (k.clone(), text)
                    })
                    .collect(),
                None | Some(Json::Null) => Vec::new(),
                Some(_) => return Err("\"settings\" is an object of values by key".into()),
            };
            (events, mock, settings)
        }
        _ => return Err("a scenario is a list of events, or {\"events\": [...], \"mock\": {...}}".into()),
    };
    for (i, e) in events.iter().enumerate() {
        if !e.get("type").is_some_and(Json::is_string) {
            return Err(format!("event {} has no \"type\": an event looks like {{\"type\": \"click\", \"id\": \"button:Save\"}}", i + 1));
        }
    }
    Ok(Scenario { events, mock, settings })
}

/// A host call, as the observer saw it.
#[derive(Clone, Debug)]
pub enum HostCall {
    Answered { op: String, args: Json, reply: Json, source: &'static str, at: Instant, took: Duration },
    Started { op: String, args: Json, token: String, source: &'static str, at: Instant, took: Duration },
    Later { token: String, value: Json },
}

impl HostCall {
    /// When it ended and how long it took, for a call that ran the host's own code.
    pub fn span(&self) -> Option<(Instant, Duration)> {
        match self {
            HostCall::Answered { at, took, .. } | HostCall::Started { at, took, .. } => Some((*at, *took)),
            HostCall::Later { .. } => None,
        }
    }
}

pub type Calls = Rc<RefCell<Vec<HostCall>>>;

/// An observer that keeps every call in [calls].
pub fn collector(calls: &Calls) -> Observer {
    let calls = calls.clone();
    Rc::new(move |seen: &Seen| {
        let at = Instant::now();
        let call = match seen {
            Seen::Answered { op, args, reply, source, took } => {
                HostCall::Answered { op: op.to_string(), args: (*args).clone(), reply: (*reply).clone(), source, at, took: *took }
            }
            Seen::Started { op, args, token, source, took } => {
                HostCall::Started { op: op.to_string(), args: (*args).clone(), token: token.to_string(), source, at, took: *took }
            }
            Seen::Later { token, value } => HostCall::Later { token: token.to_string(), value: (*value).clone() },
        };
        calls.borrow_mut().push(call);
    })
}

/// One phase of one session call. [micros] is the script's own time: what
/// the session measured less the time spent in host calls, which the
/// keyboard's deadline does not count either.
#[derive(Clone, Debug)]
pub struct Phase {
    pub kind: String,
    pub steps: u64,
    pub steps_limit: u64,
    pub micros: u64,
    pub host_micros: u64,
    pub millis_limit: u64,
}

/// One session call: a load, an event, a draw, an action or a preview.
#[derive(Clone, Debug)]
pub struct Step {
    /// `load`, `click button:Add`, `result http:1`, or for a test
    /// `test.py::test_add: event click button:Add`.
    pub label: String,
    /// The event's widget id, when it has one.
    pub widget: Option<String>,
    pub phases: Vec<Phase>,
    pub error: Option<Diag>,
    /// `steps`, `deadline`, ... when the keyboard stopped it.
    pub abort: Option<String>,
}

/// Everything one run found.
#[derive(Default)]
pub struct Outcome {
    pub steps: Vec<Step>,
    pub calls: Vec<HostCall>,
    /// What wmpy did along the way: skipped clicks, answered dialogs, waits given up.
    pub notes: Vec<String>,
    /// What drove the plugin, in words.
    pub what: String,
    /// The plugin loaded far enough to take events (always true for tests).
    pub loaded: bool,
    /// Tests: how many passed, and each failure.
    pub passed: usize,
    pub failures: Vec<(String, Diag)>,
    /// The secrets the state held when the run ended, for redaction.
    pub secrets: Vec<String>,
    pub entry: String,
}

/// How a run is set up.
pub struct Setup<'a> {
    /// The folder the plugin runs from: the plugin's own, or `wmpy profile`'s copy.
    pub dir: &'a Path,
    /// The plugin's own folder, whose `.wmpy/` the state starts from.
    pub home: &'a Path,
    pub plan: &'a Plan,
    /// `--mock`: answers before the dev host's.
    pub mock: Option<&'a Path>,
    /// How long to wait for `then=` answers after each event.
    pub settle_for: Duration,
    /// Where the tests' sessions send host calls.
    pub backend: super::suite::Backend,
    /// Run in each test file's interpreter before its code (`wmpy profile`'s recorder).
    pub prepare: Option<&'a dyn Fn(&mut wm_python::interp::Runtime)>,
}

/// A temporary folder, gone when dropped.
pub struct TempDir(pub PathBuf);

impl TempDir {
    pub fn new(what: &str) -> Result<TempDir, String> {
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let path = std::env::temp_dir().join(format!("wmpy-{what}-{}-{nanos}", std::process::id()));
        std::fs::create_dir_all(&path).map_err(|e| format!("cannot make {}: {e}", path.display()))?;
        Ok(TempDir(path))
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// The files of a `.wmpy/` state folder that a run reads and writes.
const STATE_FILES: &[&str] = &["storage.json", "reminders.json", "settings.json", "secrets.json"];

/// A temporary state folder starting from [home]'s `.wmpy/`.
pub fn state_copy(home: &Path) -> Result<TempDir, String> {
    let tmp = TempDir::new("state")?;
    for f in STATE_FILES {
        let from = home.join(".wmpy").join(f);
        if from.is_file() {
            std::fs::copy(&from, tmp.path().join(f)).map_err(|e| format!("cannot copy {}: {e}", from.display()))?;
        }
    }
    Ok(tmp)
}

/// Every secret value the state folder holds.
fn secret_values(state: &Path) -> Vec<String> {
    let text = std::fs::read_to_string(state.join("secrets.json")).unwrap_or_default();
    match serde_json::from_str::<Json>(&text) {
        Ok(Json::Object(o)) => o.values().filter_map(Json::as_str).map(String::from).collect(),
        _ => Vec::new(),
    }
}

/// The mock answers of `--mock` and the scenario's, the scenario's winning.
fn merged_mock(file: Option<&Path>, scenario: Option<&Json>) -> Result<Option<Json>, String> {
    let mut out = match file {
        Some(path) => {
            let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let v: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
            if !v.is_object() {
                return Err(format!("{}: a mock file is a JSON object of replies by op name", path.display()));
            }
            Some(v)
        }
        None => None,
    };
    if let Some(Json::Object(s)) = scenario {
        let base = out.get_or_insert_with(|| json!({}));
        for (k, v) in s {
            base[k] = v.clone();
        }
    }
    Ok(out)
}

/// Runs the plugin as [setup] says. Err when wmpy cannot (no plugin.json,
/// an unreadable mock); a plugin that fails is an [Outcome] saying so.
pub fn run(setup: &Setup) -> Result<Outcome, String> {
    let entry = crate::project::manifest(setup.dir).map(|m| m.entry)?;
    if let Plan::Tests { filter } = setup.plan {
        let state = state_copy(setup.home)?;
        let mut out = super::suite::run(setup.dir, filter.as_deref(), &setup.backend, state.path(), setup.prepare, setup.settle_for)?;
        out.secrets = secret_values(state.path());
        out.entry = entry;
        return Ok(out);
    }
    let state = state_copy(setup.home)?;
    let scenario = match setup.plan {
        Plan::Scenario(s) => Some(s),
        _ => None,
    };
    let mock_path = match merged_mock(setup.mock, scenario.and_then(|s| s.mock.as_ref()))? {
        Some(m) => {
            let path = state.path().join("wmpy-mock.json");
            std::fs::write(&path, m.to_string()).map_err(|e| e.to_string())?;
            Some(path)
        }
        None => None,
    };
    let opts = Options {
        dir: setup.dir.to_path_buf(),
        mock: mock_path,
        sets: scenario.map(|s| s.settings.clone()).unwrap_or_default(),
        dark: crate::project::context().dark(),
        watch: false,
    };
    let calls: Calls = Rc::new(RefCell::new(Vec::new()));
    let extra = Extra { state: Some(state.path().to_path_buf()), record: true, observer: Some(collector(&calls)) };
    let mut host = DevHost::open_with(opts, extra)?;
    let mut out = Outcome { entry, ..Outcome::default() };
    settle(&mut host, setup.settle_for, &mut out.notes);
    out.loaded = host.loaded;
    if host.loaded {
        match setup.plan {
            Plan::Clicks => clicks(&mut host, setup.settle_for, &mut out),
            Plan::Scenario(s) => {
                out.what = format!("the scenario's {} events", s.events.len());
                for e in &s.events {
                    host.send(e.clone());
                    settle(&mut host, setup.settle_for, &mut out.notes);
                }
            }
            Plan::Tests { .. } => unreachable!(),
        }
    } else {
        out.what = "the load".into();
    }
    let calls_seen = calls.borrow().clone();
    out.steps = host.ran.take().unwrap_or_default().into_iter().map(|r| step_of(&r, &calls_seen)).collect();
    out.calls = calls_seen;
    out.secrets = secret_values(state.path());
    Ok(out)
}

/// A click on each button of the first draw, then each timer once.
fn clicks(host: &mut DevHost, settle_for: Duration, out: &mut Outcome) {
    let v5 = host.facts.api >= 5;
    let ids = click_ids(&host.ui, v5);
    let timers: Vec<String> = host.meta["timers"].as_array().into_iter().flatten().filter_map(|t| t["id"].as_str().map(String::from)).collect();
    out.what = match (ids.len(), timers.len()) {
        (0, 0) => "the load and first draw only: the panel shows no buttons".into(),
        (n, 0) => format!("a click on each of the {n} buttons the first draw shows"),
        (n, t) => format!("a click on each of the {n} buttons the first draw shows, then each of its {t} timers once"),
    };
    for id in &ids {
        if !click_ids(&host.ui, v5).contains(id) {
            // An earlier click opened a page: go back once and look again.
            if host.meta["canBack"] == json!(true) {
                host.send(json!({"type": "back"}));
                settle(host, settle_for, &mut out.notes);
            }
            if !click_ids(&host.ui, v5).contains(id) {
                out.notes.push(format!("skipped {id}: the earlier clicks took it off the panel"));
                continue;
            }
        }
        host.send(json!({"type": "click", "id": id}));
        settle(host, settle_for, &mut out.notes);
    }
    for t in timers {
        host.send(json!({"type": "timer", "id": t}));
        settle(host, settle_for, &mut out.notes);
    }
}

/// The ids a click reaches on [ui]'s tree, in the order drawn: buttons,
/// clickable cards, items and images.
pub fn click_ids(ui: &Json, v5: bool) -> Vec<String> {
    let root = ui["root"].as_array().cloned().unwrap_or_default();
    let (_, targets, _) = layout::render(&root, 52, &Local::default(), None, false, false, v5);
    let mut out: Vec<String> = Vec::new();
    for t in targets {
        if let Action::Click(id) = t.action {
            if !id.is_empty() && !out.contains(&id) {
                out.push(id);
            }
        }
    }
    out
}

/// Ticks until no `then=` answer is waiting (a sensor's stream aside), at
/// most [limit]; a dialog gets its first button.
pub fn settle(host: &mut DevHost, limit: Duration, notes: &mut Vec<String>) {
    let until = Instant::now() + limit;
    loop {
        if let Some(d) = host.dialog() {
            notes.push(format!("answered the dialog “{}” with its first button, {}", d.title, d.buttons.first().cloned().unwrap_or_default()));
            host.answer_dialog(Some(0));
        }
        host.tick();
        let busy = {
            let w = host.world.borrow();
            w.pending > w.sensors.len() || w.later.iter().any(|l| l.ends)
        };
        if !busy && host.dialog().is_none() {
            return;
        }
        if Instant::now() >= until {
            notes.push(format!("stopped waiting for answers after {} s", limit.as_secs()));
            return;
        }
        std::thread::sleep(host.next_wake().clamp(Duration::from_millis(2), Duration::from_millis(20)));
    }
}

/// An event in a few words: `click button:Add`, `result http:1`, `load`.
pub fn event_label(e: &Json) -> String {
    let kind = e["type"].as_str().unwrap_or("event");
    let what = e["id"].as_str().or_else(|| e["token"].as_str()).unwrap_or("");
    if what.is_empty() { kind.to_string() } else { format!("{kind} {what}") }
}

fn step_of(r: &Ran, calls: &[HostCall]) -> Step {
    let label = event_label(&r.event);
    let widget = r.event["id"].as_str().map(String::from);
    let spans: Vec<(Instant, Duration)> = calls.iter().filter_map(HostCall::span).filter(|(at, _)| *at >= r.started && *at <= r.ended).collect();
    Step { label, widget, phases: phases_of(&r.result, r.started, &spans), error: r.error.clone(), abort: abort_of(&r.result) }
}

pub fn abort_of(result: &Json) -> Option<String> {
    result.pointer("/error/abort").and_then(Json::as_str).map(String::from)
}

/// The phases of one result, each less the host calls that ended inside it.
/// [spans] are `(ended, took)`; the phases ran one after the other from [started].
pub fn phases_of(result: &Json, started: Instant, spans: &[(Instant, Duration)]) -> Vec<Phase> {
    let mut out = Vec::new();
    let mut from = started;
    for p in result["phases"].as_array().into_iter().flatten() {
        let elapsed = p["elapsedMicros"].as_u64().unwrap_or(0);
        let to = from + Duration::from_micros(elapsed);
        let host: u64 = spans
            .iter()
            .filter(|(at, _)| *at >= from && *at <= to + Duration::from_millis(1))
            .map(|(_, took)| took.as_micros() as u64)
            .sum::<u64>()
            .min(elapsed);
        out.push(Phase {
            kind: p["phase"].as_str().unwrap_or("?").to_string(),
            steps: p["steps"].as_u64().unwrap_or(0),
            steps_limit: p["stepsLimit"].as_u64().unwrap_or(0),
            micros: elapsed - host,
            host_micros: host,
            millis_limit: p["millisLimit"].as_u64().unwrap_or(0),
        });
        from = to;
    }
    out
}

/// The diag for a failed session result, with the budget's help, as the
/// dev host words it, for runs without a dev host (the tests).
pub fn diag_of(e: &Json, result: &Json, entry: &str) -> Diag {
    let mut d = Diag::from_session_error(e, entry);
    if e["kind"] == "abort" {
        let abort = e["abort"].as_str().unwrap_or("");
        let phase = result["phases"].as_array().and_then(|p| p.last()).and_then(|p| p["phase"].as_str()).unwrap_or("").to_string();
        d.code = Some(abort.to_string());
        d.help = match abort {
            "steps" => Some(format!("too much work: the {phase} part of a run ran out of steps. Look for a loop that never ends, or work that belongs in a smaller piece.")),
            "deadline" => Some(format!("too slow: the {phase} part of a run ran out of time. Time spent waiting for http or ai does not count.")),
            "recursion" => Some("too deep: a function called itself too many times".into()),
            "container" | "string" => Some("too big: a list, dict or string grew past the keyboard's limit".into()),
            _ => None,
        };
    } else if e["kind"] == "no_render" {
        d = Diag::error("the plugin has no render() function, so its panel has nothing to draw");
        d.code = Some("no_render".into());
    }
    d
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scenarios_are_lists_or_objects_with_a_mock() {
        let s = parse_scenario(&json!([{"type": "click", "id": "button:A"}])).unwrap();
        assert_eq!(s.events.len(), 1);
        let s = parse_scenario(&json!({"events": [{"type": "click", "id": "b"}], "mock": {"ai.ask": {"text": "x"}}, "settings": {"n": 3, "on": true}})).unwrap();
        assert!(s.mock.is_some());
        assert_eq!(s.settings, vec![("n".to_string(), "3".to_string()), ("on".to_string(), "true".to_string())]);
        assert!(parse_scenario(&json!([{"id": "b"}])).unwrap_err().contains("no \"type\""));
        assert!(parse_scenario(&json!("x")).is_err());
    }

    #[test]
    fn host_time_comes_off_the_phase_it_ended_in() {
        let t0 = Instant::now();
        let result = json!({"phases": [
            {"phase": "event", "steps": 10, "stepsLimit": 100, "elapsedMicros": 300_000, "millisLimit": 2000},
            {"phase": "render", "steps": 5, "stepsLimit": 50, "elapsedMicros": 10_000, "millisLimit": 500},
        ]});
        let spans = [(t0 + Duration::from_millis(250), Duration::from_millis(200))];
        let p = phases_of(&result, t0, &spans);
        assert_eq!((p[0].micros, p[0].host_micros), (100_000, 200_000));
        assert_eq!((p[1].micros, p[1].host_micros), (10_000, 0));
    }

    #[test]
    fn click_ids_are_the_buttons_in_drawing_order() {
        let ui = json!({"root": [
            {"type": "label", "text": "x"},
            {"type": "row", "children": [
                {"type": "button", "id": "button:A", "text": "A", "enabled": true},
                {"type": "button", "id": "button:Off", "text": "Off", "enabled": false},
            ]},
            {"type": "button", "id": "button:B", "text": "B", "enabled": true},
        ]});
        assert_eq!(click_ids(&ui, true), vec!["button:A".to_string(), "button:B".to_string()]);
    }
}
