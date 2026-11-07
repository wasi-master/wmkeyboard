//! The plugin host: one [`Session`] per running plugin, driven entirely by
//! JSON so the JNI, wasm and CLI front ends stay thin.
//!
//! # Config (`Session::new`)
//!
//! ```text
//! { "id": "com.example.todo", "version": "3.0.0", "permissions": ["storage", "reminders"],
//!   "mode": "panel" | "action",
//!   "storagePath": "/…/storage.json" | null,      // null or absent: in memory
//!   "remindersPath": "/…/reminders.json" | null,
//!   "limits": { "load": {"steps": 3000000, "millis": 3000}, "event": {…}, "render": {…}, "action": {…} } }
//! ```
//!
//! Every limit is optional; defaults are load 3M/3000 ms, event 2M/2000,
//! render 400k/500, action 3M/3000. Container (10,000 items) and string
//! (100,000 characters) caps are fixed.
//!
//! # Result (every call)
//!
//! ```text
//! { "ok": bool,
//!   "ui": {"root": [...], "repairs": [...]},       // render, event, and a panel load
//!   "value": string | null,                        // transform: null when it returned None; other values str()'d
//!   "error": {"kind": "compile"|"runtime"|"abort"|"no_render"|"no_transform"|"internal",
//!             "message": str, "line": int|null,
//!             "abort": "steps"|"deadline"|"cancelled"|"recursion"|"container"|"string"|null,
//!             "exception": str|null},             // absent when ok
//!   "log": [str, ...],                             // print() and wm.log lines, in order, each ≤ 512
//!   "inputs": [[id, text], ...],                   // wm.ui.set_input writes, panel only
//!   "remindersChanged": bool,
//!   "phases": [{"phase": "load"|"event"|"render"|"action", "steps": int, "stepsLimit": int,
//!               "elapsedMicros": int, "millisLimit": int}, ...] }
//! ```
//!
//! `error.message` is what a person reads: `ValueError: bad input`, or just
//! the text for a RuntimeError or an abort. `error.exception` is the Python
//! kind (`"ValueError"`; `"SystemExit"` for aborts). The widget JSON is
//! described in [`codec`].
//!
//! # Calls
//!
//! * `load(source)`: panel mode runs the top level (phase `load`), then
//!   renders (phase `render`). A plugin with no `render()` gets
//!   `no_render`. Action mode only checks that the source parses and keeps
//!   it; nothing runs until `transform`.
//! * `render()`: phase `render`.
//! * `event(json)`: `{"type", "id", "value"?, "index"?}` becomes the dict
//!   `on_event(e)` receives (when defined), then a render: phases
//!   `[event, render]`.
//! * `transform(text, action_id)`: calls `transform(text, action_id)`, or
//!   `transform(text)` when it takes fewer than two parameters. In action
//!   mode every call starts a fresh interpreter and loading the source shares
//!   the one `action` allowance with the call. Missing → `no_transform`.
//! * `cancel_handle()`: an `Arc<AtomicBool>` to hold outside any lock; set it
//!   from any thread to stop the call in flight. Each call clears it when it
//!   starts.
//!
//! Scripts run on the calling thread, which needs a large stack (8 MB is
//! safe for 300 nested calls in debug builds; release needs about 2 MB).

pub mod api;
pub mod codec;
pub mod files;

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use serde_json::{json, Value as Json};

use crate::interp::{check, Clock, Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};
use api::{HostState, PluginInfo};
use files::{Reminders, Storage};

/// One phase's allowance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PhaseLimit {
    pub steps: u64,
    pub millis: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PhaseLimits {
    pub load: PhaseLimit,
    pub event: PhaseLimit,
    pub render: PhaseLimit,
    pub action: PhaseLimit,
}

impl Default for PhaseLimits {
    fn default() -> Self {
        PhaseLimits {
            load: PhaseLimit { steps: 3_000_000, millis: 3_000 },
            event: PhaseLimit { steps: 2_000_000, millis: 2_000 },
            render: PhaseLimit { steps: 400_000, millis: 500 },
            action: PhaseLimit { steps: 3_000_000, millis: 3_000 },
        }
    }
}

/// A session's configuration, as `Session::new` reads it from JSON.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SessionConfig {
    pub info: PluginInfo,
    pub storage_path: Option<PathBuf>,
    pub reminders_path: Option<PathBuf>,
    pub limits: PhaseLimits,
}

impl SessionConfig {
    pub fn from_json(text: &str) -> Result<SessionConfig, String> {
        let v: Json = serde_json::from_str(text).map_err(|e| format!("config is not JSON: {e}"))?;
        let s = |k: &str| v.get(k).and_then(Json::as_str).unwrap_or("").to_string();
        let mode = v.get("mode").and_then(Json::as_str).unwrap_or("panel");
        if mode != "panel" && mode != "action" {
            return Err(format!("unknown mode '{mode}'"));
        }
        let permissions = v
            .get("permissions")
            .and_then(Json::as_array)
            .map(|a| a.iter().filter_map(Json::as_str).map(String::from).collect())
            .unwrap_or_default();
        let path = |k: &str| v.get(k).and_then(Json::as_str).filter(|p| !p.is_empty()).map(PathBuf::from);
        let mut limits = PhaseLimits::default();
        if let Some(l) = v.get("limits") {
            for (name, slot) in [
                ("load", &mut limits.load),
                ("event", &mut limits.event),
                ("render", &mut limits.render),
                ("action", &mut limits.action),
            ] {
                if let Some(p) = l.get(name) {
                    if let Some(steps) = p.get("steps").and_then(Json::as_u64) {
                        slot.steps = steps;
                    }
                    if let Some(millis) = p.get("millis").and_then(Json::as_u64) {
                        slot.millis = millis;
                    }
                }
            }
        }
        Ok(SessionConfig {
            info: PluginInfo { id: s("id"), version: s("version"), permissions, panel: mode == "panel" },
            storage_path: path("storagePath"),
            reminders_path: path("remindersPath"),
            limits,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    Load,
    Event,
    Render,
    Action,
}

impl Phase {
    fn name(self) -> &'static str {
        match self {
            Phase::Load => "load",
            Phase::Event => "event",
            Phase::Render => "render",
            Phase::Action => "action",
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct Usage {
    phase: Phase,
    steps: u64,
    steps_limit: u64,
    elapsed_micros: u64,
    millis_limit: u64,
}

impl Usage {
    fn json(&self) -> Json {
        json!({
            "phase": self.phase.name(), "steps": self.steps, "stepsLimit": self.steps_limit,
            "elapsedMicros": self.elapsed_micros, "millisLimit": self.millis_limit,
        })
    }
}

/// What went wrong, in the result's terms.
enum Failure {
    Compile(PyError),
    Script(PyError),
    NoRender,
    NoTransform,
    Internal(String),
}

impl Failure {
    fn json(&self) -> Json {
        let py = |kind: &str, e: &PyError| {
            let body = if e.abort.is_some() || e.kind == "RuntimeError" {
                e.message.clone()
            } else {
                format!("{}: {}", e.kind, e.message)
            };
            json!({"kind": kind, "message": body, "line": e.line, "abort": e.abort.map(|a| a.wire()), "exception": e.kind})
        };
        let plain = |kind: &str, message: &str| json!({"kind": kind, "message": message, "line": null, "abort": null, "exception": null});
        match self {
            Failure::Compile(e) => py("compile", e),
            Failure::Script(e) if e.is_abort() => py("abort", e),
            Failure::Script(e) => py("runtime", e),
            Failure::NoRender => plain("no_render", ""),
            Failure::NoTransform => plain("no_transform", ""),
            Failure::Internal(m) => plain("internal", m),
        }
    }
}

/// A running plugin. See the module docs for the JSON contract.
pub struct Session {
    config: SessionConfig,
    clock: Rc<dyn Clock>,
    cancel: Arc<AtomicBool>,
    rt: Option<Runtime>,
    state: Option<Rc<RefCell<HostState>>>,
    /// The files outlive any one interpreter (action mode starts a fresh one per call).
    files: Rc<RefCell<(Option<Storage>, Option<Reminders>)>>,
    source: Option<String>,
    phases: Vec<Usage>,
}

// SAFETY: every `Rc` a session holds (the runtime's values, the host state its
// closures share, the clock) was created by the session and never leaves it:
// every method takes and returns plain data (strings, the cancel `Arc`), and
// the clock arrives as a `Send` box. Moving the whole graph to another thread
// is therefore sound; a session is never used from two threads at once
// because every method takes `&mut self`.
unsafe impl Send for Session {}

impl Session {
    /// A session reading the system clock in UTC.
    pub fn new(config_json: &str) -> Result<Session, String> {
        Session::with_clock(config_json, Box::new(SystemClock::default()))
    }

    /// A session whose `time` module and reminders read [clock] (the user's zone, or a fixed clock in tests).
    pub fn with_clock(config_json: &str, clock: Box<dyn Clock + Send>) -> Result<Session, String> {
        let config = SessionConfig::from_json(config_json)?;
        let storage = Storage::new(config.storage_path.clone());
        let reminders = Reminders::new(config.reminders_path.clone());
        Ok(Session {
            config,
            clock: Rc::from(clock as Box<dyn Clock>),
            cancel: Arc::new(AtomicBool::new(false)),
            rt: None,
            state: None,
            files: Rc::new(RefCell::new((Some(storage), Some(reminders)))),
            source: None,
            phases: Vec::new(),
        })
    }

    pub fn config(&self) -> &SessionConfig {
        &self.config
    }

    /// Set from any thread to stop the call in flight.
    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        self.cancel.clone()
    }

    /// The latest phase's usage: `{"steps", "steps_limit", "elapsed_ms", "millis_limit"}`.
    pub fn usage(&self) -> String {
        match self.phases.last() {
            Some(u) => json!({
                "steps": u.steps, "steps_limit": u.steps_limit,
                "elapsed_ms": u.elapsed_micros / 1000, "millis_limit": u.millis_limit,
            }),
            None => json!({"steps": 0, "steps_limit": 0, "elapsed_ms": 0, "millis_limit": 0}),
        }
        .to_string()
    }

    fn fresh_runtime(&mut self) {
        // Give the files back from the previous interpreter's host state.
        self.reclaim_files();
        let mut rt = Runtime::with_clock(Limits::default(), STANDARD_MODULES, self.clock.clone());
        rt.set_cancel_handle(self.cancel.clone());
        let (storage, reminders) = {
            let mut f = self.files.borrow_mut();
            (f.0.take(), f.1.take())
        };
        let state = api::install(&mut rt, &self.config.info, storage, reminders, self.clock.clone());
        self.rt = Some(rt);
        self.state = Some(state);
    }

    fn reclaim_files(&mut self) {
        if let Some(state) = self.state.take() {
            let mut st = state.borrow_mut();
            let mut f = self.files.borrow_mut();
            if let Some(s) = st.storage.take() {
                f.0 = Some(s);
            }
            if let Some(r) = st.reminders.take() {
                f.1 = Some(r);
            }
        }
        self.rt = None;
    }

    fn begin(&mut self) {
        self.cancel.store(false, Ordering::SeqCst);
        self.phases.clear();
        if let Some(s) = &self.state {
            s.borrow_mut().begin_call();
        }
    }

    /// Run [f] under [limit] as one phase.
    fn phase<T>(&mut self, phase: Phase, limit: PhaseLimit, f: impl FnOnce(&mut Runtime) -> Result<T, PyError>) -> Result<T, PyError> {
        let started = self.clock.monotonic();
        let rt = self.rt.as_mut().expect("runtime");
        rt.set_step_limit(limit.steps);
        #[cfg(not(target_arch = "wasm32"))]
        rt.set_deadline(Some(std::time::Instant::now() + std::time::Duration::from_millis(limit.millis)));
        let r = f(rt);
        #[cfg(not(target_arch = "wasm32"))]
        rt.set_deadline(None);
        let steps = rt.steps_used().min(limit.steps);
        let elapsed = ((self.clock.monotonic() - started).max(0.0) * 1e6) as u64;
        self.phases.push(Usage { phase, steps, steps_limit: limit.steps, elapsed_micros: elapsed, millis_limit: limit.millis });
        r
    }

    fn result(&self, outcome: Result<Option<Json>, Failure>, value: Option<Option<String>>) -> String {
        let mut out = serde_json::Map::new();
        out.insert("ok".into(), Json::Bool(outcome.is_ok()));
        match outcome {
            Ok(Some(ui)) => {
                out.insert("ui".into(), ui);
            }
            Ok(None) => {}
            Err(f) => {
                out.insert("error".into(), f.json());
            }
        }
        if let Some(v) = value {
            out.insert("value".into(), v.map(Json::String).unwrap_or(Json::Null));
        }
        let (log, inputs, changed) = match &self.state {
            Some(s) => {
                let s = s.borrow();
                (s.log.clone(), s.inputs.clone(), s.reminders_changed)
            }
            None => (Vec::new(), Vec::new(), false),
        };
        out.insert("log".into(), json!(log));
        out.insert("inputs".into(), Json::Array(inputs.into_iter().map(|(i, t)| json!([i, t])).collect()));
        out.insert("remindersChanged".into(), Json::Bool(changed));
        out.insert("phases".into(), Json::Array(self.phases.iter().map(Usage::json).collect()));
        Json::Object(out).to_string()
    }

    /// Load the plugin's source. See the module docs.
    pub fn load(&mut self, source: &str) -> String {
        self.begin();
        if let Err(e) = check(source) {
            let e = PyError { message: e.message, kind: e.kind, line: e.line, abort: None };
            return self.result(Err(Failure::Compile(e)), None);
        }
        self.source = Some(source.to_string());
        if !self.config.info.panel {
            self.reclaim_files();
            return self.result(Ok(None), None);
        }
        self.fresh_runtime();
        let limit = self.config.limits.load;
        if let Err(e) = self.phase(Phase::Load, limit, |rt| rt.load(source)) {
            return self.result(Err(Failure::Script(e)), None);
        }
        let outcome = self.render_phase();
        self.result(outcome, None)
    }

    fn render_phase(&mut self) -> Result<Option<Json>, Failure> {
        let has_render = self.rt.as_ref().is_some_and(|rt| rt.has_function("render"));
        if !has_render {
            return Err(Failure::NoRender);
        }
        let limit = self.config.limits.render;
        let v = self.phase(Phase::Render, limit, |rt| rt.call("render", Vec::new())).map_err(Failure::Script)?;
        Ok(Some(codec::encode(&v)))
    }

    /// Render the panel again.
    pub fn render(&mut self) -> String {
        self.begin();
        if self.rt.is_none() || !self.config.info.panel {
            return self.result(Err(Failure::Internal("render() needs a loaded panel session".into())), None);
        }
        let outcome = self.render_phase();
        self.result(outcome, None)
    }

    /// Hand a user action to `on_event`, then render.
    pub fn event(&mut self, event_json: &str) -> String {
        self.begin();
        if self.rt.is_none() || !self.config.info.panel {
            return self.result(Err(Failure::Internal("event() needs a loaded panel session".into())), None);
        }
        let event = match event_dict(event_json) {
            Ok(e) => e,
            Err(m) => return self.result(Err(Failure::Internal(m)), None),
        };
        let limit = self.config.limits.event;
        let has_handler = self.rt.as_ref().is_some_and(|rt| rt.has_function("on_event"));
        let r = self.phase(Phase::Event, limit, |rt| if has_handler { rt.call("on_event", vec![event]).map(|_| ()) } else { Ok(()) });
        if let Err(e) = r {
            return self.result(Err(Failure::Script(e)), None);
        }
        let outcome = self.render_phase();
        self.result(outcome, None)
    }

    /// Run the plugin's `transform` on a selection.
    pub fn transform(&mut self, text: &str, action_id: &str) -> String {
        self.begin();
        let Some(source) = self.source.clone() else {
            return self.result(Err(Failure::Internal("transform() before load()".into())), Some(None));
        };
        let limit = self.config.limits.action;
        let fresh = !self.config.info.panel;
        if fresh {
            self.fresh_runtime();
        } else if self.rt.is_none() {
            return self.result(Err(Failure::Internal("transform() needs a loaded session".into())), Some(None));
        }
        let (text, action_id) = (text.to_string(), action_id.to_string());
        let mut missing = false;
        let mut load_steps = 0;
        let r = self.phase(Phase::Action, limit, |rt| {
            if fresh {
                rt.load(&source)?;
                load_steps = rt.steps_used();
                // Loading and the call share one allowance.
                let left = limit.steps.saturating_sub(rt.steps_used());
                rt.set_step_limit(left);
            }
            let Some(params) = rt.parameters("transform") else {
                missing = true;
                return Ok(Value::None);
            };
            let args = if params.len() >= 2 { vec![Value::str(&text), Value::str(&action_id)] } else { vec![Value::str(&text)] };
            rt.call("transform", args)
        });
        // The load's steps count too: the phase saw only the call's.
        if let Some(u) = self.phases.last_mut() {
            u.steps = (u.steps + load_steps).min(limit.steps);
        }
        match r {
            Err(e) => self.result(Err(Failure::Script(e)), Some(None)),
            Ok(_) if missing => self.result(Err(Failure::NoTransform), Some(None)),
            Ok(Value::None) => self.result(Ok(None), Some(None)),
            Ok(v) => {
                let s = v.as_str().map(String::from).unwrap_or_else(|| v.to_str());
                self.result(Ok(None), Some(Some(s)))
            }
        }
    }
}

/// `{"type", "id", "value"?, "index"?}` as the dict `on_event` receives.
fn event_dict(text: &str) -> Result<Value, String> {
    let v: Json = serde_json::from_str(text).map_err(|e| format!("event is not JSON: {e}"))?;
    let Json::Object(o) = v else { return Err("event is not a JSON object".into()) };
    let mut pairs = Vec::new();
    for key in ["type", "id", "value", "index"] {
        if let Some(x) = o.get(key) {
            pairs.push((key, json_to_value(x)));
        }
    }
    if !o.get("type").is_some_and(Json::is_string) {
        return Err("event has no type".into());
    }
    Ok(Value::dict(pairs))
}

fn json_to_value(v: &Json) -> Value {
    match v {
        Json::Null => Value::None,
        Json::Bool(b) => Value::Bool(*b),
        Json::Number(n) => match n.as_i64() {
            Some(i) => Value::Int(i),
            None => Value::Float(n.as_f64().unwrap_or(0.0)),
        },
        Json::String(s) => Value::str(s),
        Json::Array(a) => Value::list(a.iter().map(json_to_value).collect()),
        Json::Object(o) => Value::dict(o.iter().map(|(k, v)| (k.as_str(), json_to_value(v))).collect()),
    }
}
