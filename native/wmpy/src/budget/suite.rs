//! The plugin's tests run with every session call measured: `--tests` for
//! `wmpy budget`, `wmpy profile` and `wmpy mock record`.
//!
//! Tests run as `wmpy test` runs them (each file in a fresh interpreter, its
//! top level, then each `def test_*():`), with the same `plugin_test`
//! module. `wmpy test`'s runner keeps its sessions to itself, so this one
//! has its own `plugin_test`, whose sessions also report each call's phases
//! and host calls. With [Backend::Host] the dev host answers every call for
//! real and a test's `mock=` is not used: what `wmpy mock record` needs.

use std::cell::RefCell;
use std::path::Path;
use std::rc::Rc;
use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};
use wm_python::host::api::{self, PluginInfo};
use wm_python::host::files::{Reminders, Storage};
use wm_python::host::{json_to_value, Bridge, Session};
use wm_python::interp::{HostFunction, Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};

use super::drive::{collector, diag_of, event_label, phases_of, Calls, HostCall, Outcome, Step};
use crate::devhost::bridge::{self, HttpDone, World};
use crate::devhost::plugin::PluginFacts;
use crate::devhost::{make_world, Options};
use crate::diag::Diag;
use crate::mock::Mock;
use crate::project::{inside, manifest, modules, read_inside, session_config, Manifest, DEFAULT_PREVIEW_BYTES};

/// Where a test's sessions send their host calls.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Backend {
    /// The test's own `mock=`, as `wmpy test` answers.
    #[default]
    Mock,
    /// The dev host, for real.
    Host,
}

/// What every session of a run writes to.
struct Rec {
    steps: Vec<Step>,
    calls: Calls,
    /// The test running now, for the labels.
    test: String,
    entry: String,
}

type Shared = Rc<RefCell<Rec>>;

/// Runs the tests of the plugin in [dir] (those `-k` [filter] keeps).
pub fn run(
    dir: &Path,
    filter: Option<&str>,
    backend: &Backend,
    state: &Path,
    prepare: Option<&dyn Fn(&mut Runtime)>,
    settle_for: Duration,
) -> Result<Outcome, String> {
    let files = crate::project::test_files(dir);
    if files.is_empty() {
        return Err(format!("{}: no tests: add a test.py, or tests/test_*.py files", dir.join("test.py").display()));
    }
    let m = manifest(dir)?;
    let calls: Calls = Rc::new(RefCell::new(Vec::new()));
    let rec: Shared = Rc::new(RefCell::new(Rec { steps: Vec::new(), calls: calls.clone(), test: String::new(), entry: m.entry.clone() }));
    let ctx = Ctx { dir: dir.to_path_buf(), m: Rc::new(m), backend: *backend, state: state.to_path_buf(), rec: rec.clone(), settle_for };
    let mut out = Outcome { loaded: true, ..Outcome::default() };
    let mut count = 0;
    for file in &files {
        let source = std::fs::read_to_string(dir.join(file)).map_err(|e| format!("{file}: {e}"))?;
        let names = test_names(&source);
        let whole = filter.is_none_or(|k| file.contains(k));
        let selected: Vec<String> = names.iter().filter(|n| whole || filter.is_some_and(|k| format!("{file}::{n}").contains(k))).cloned().collect();
        if selected.is_empty() && !(names.is_empty() && whole) {
            continue;
        }
        rec.borrow_mut().test = file.clone();
        let mut rt = runtime(&ctx)?;
        if let Some(p) = prepare {
            p(&mut rt);
        }
        rt.set_module_file("__main__", file);
        rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
        if let Err(e) = rt.load(&source) {
            count += 1;
            out.failures.push((file.clone(), Diag::from_py_error(&e, file)));
            continue;
        }
        if names.is_empty() {
            count += 1;
            out.passed += 1;
            continue;
        }
        for name in selected {
            if !rt.has_function(&name) {
                continue;
            }
            let id = format!("{file}::{name}");
            rec.borrow_mut().test = id.clone();
            rt.set_deadline(Some(Instant::now() + Duration::from_secs(600)));
            count += 1;
            match rt.call(&name, Vec::new()) {
                Ok(_) => out.passed += 1,
                Err(e) => out.failures.push((id, Diag::from_py_error(&e, file))),
            }
        }
    }
    if count == 0 {
        return Err(format!("no test matches -k {}", filter.unwrap_or("")));
    }
    out.what = format!("{count} test{}", if count == 1 { "" } else { "s" });
    out.steps = std::mem::take(&mut rec.borrow_mut().steps);
    out.calls = calls.borrow().clone();
    Ok(out)
}

/// The top-level `def test_*(` functions of [source], in order (as `wmpy test` finds them).
pub fn test_names(source: &str) -> Vec<String> {
    source
        .lines()
        .filter_map(|l| l.strip_prefix("def "))
        .filter_map(|rest| {
            let name: String = rest.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            (name.starts_with("test_") && rest[name.len()..].trim_start().starts_with('(')).then_some(name)
        })
        .collect()
}

#[derive(Clone)]
struct Ctx {
    dir: std::path::PathBuf,
    m: Rc<Manifest>,
    backend: Backend,
    state: std::path::PathBuf,
    rec: Shared,
    settle_for: Duration,
}

/// A fresh interpreter for one test file, as `wmpy test` makes it; what the
/// test prints is not shown.
fn runtime(ctx: &Ctx) -> Result<Runtime, String> {
    let m = &ctx.m;
    let mut rt = Runtime::new(Limits { steps: 500_000_000, container_items: 200_000, string_length: 1_000_000 }, STANDARD_MODULES);
    let info = PluginInfo { id: m.id.clone(), version: m.version.clone(), permissions: vec!["storage".into(), "reminders".into()], panel: true };
    if m.api >= 5 {
        let mut files: Vec<(String, String)> = modules(&ctx.dir, &m.entry).into_iter().filter_map(|(f, t)| t.as_str().map(|t| (f, t.to_string()))).collect();
        files.sort();
        wm_python::host::v5::install_for_tests(&mut rt, &info, files, Some(Storage::new(None)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
        if let (Some(stem), Ok(text)) = (m.entry.strip_suffix(".py"), std::fs::read_to_string(ctx.dir.join(&m.entry))) {
            if !stem.contains('/') {
                rt.add_source_module(stem, &text, false);
                rt.set_module_file(stem, &m.entry);
            }
        }
        rt.load("import wm\nfrom wm import ui\n").map_err(|e| e.message)?;
    } else {
        api::install(&mut rt, &info, Some(Storage::new(None)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
    }
    rt.set_on_print(Some(Box::new(|_| {})));
    let (c1, c2, c3, c4, c5) = (ctx.clone(), ctx.clone(), ctx.clone(), ctx.clone(), ctx.clone());
    rt.define_module("plugin_test", move |module| {
        module.define("session", move |call| {
            let mock = call.value(0, Some("mock")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(Json::Null);
            let settings = call.value(1, Some("settings")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(Json::Null);
            session_value(&c1, mock, settings)
        });
        module.define("preview", move |call| preview_call(call, &c2, false));
        module.define("thumbnail", move |call| preview_call(call, &c3, true));
        module.define("read", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            read_inside(&c4.dir, &name).map(Value::from)
        });
        module.define("load", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            let wm = call.value(1, Some("wm")).cloned().unwrap_or(Value::None);
            let ui = call.value(2, Some("ui")).cloned().unwrap_or(Value::None);
            let source = read_inside(&c5.dir, &name)?;
            call.run_module(&source, vec![("wm".into(), wm), ("ui".into(), ui)])
        });
    });
    rt.set("plugin_test", Some(Value::Module(Rc::from("plugin_test"))));
    Ok(rt)
}

/// The dev host's world for one session ([Backend::Host]).
struct HostSide {
    world: Rc<RefCell<World>>,
    rx: Receiver<HttpDone>,
}

/// The bridge a test's session gets: [inner] (the mock, or the dev host)
/// behind a recorder; `wmpy profile`'s recorder's own calls answered here.
fn recording_bridge(rec: &Shared, mut inner: Option<Bridge>, seen: Rc<RefCell<Vec<Json>>>, own_record: bool) -> Bridge {
    let calls = rec.borrow().calls.clone();
    Box::new(move |op: &str, args: &str| {
        let parsed: Json = serde_json::from_str(args).unwrap_or(Json::Null);
        let started = Instant::now();
        let token = parsed.get("async").and_then(Json::as_str).map(String::from);
        let profile = op == "plugin.refresh" && parsed.get("wmpyProfile").is_some();
        let reply = if profile {
            json!({"value": null}).to_string()
        } else {
            if !profile {
                seen.borrow_mut().push(json!({"op": op, "args": parsed}));
            }
            match inner.as_mut() {
                Some(f) => f(op, args),
                None if token.is_some() => json!({"value": null}).to_string(),
                None => json!({"error": {"kind": "NotImplementedError", "message": format!("{op} is not available here")}}).to_string(),
            }
        };
        // The dev host's observer already saw its own calls.
        if own_record || profile {
            let took = started.elapsed();
            let at = Instant::now();
            let call = match &token {
                Some(t) if !profile => HostCall::Started { op: op.into(), args: parsed, token: t.clone(), source: "mock", at, took },
                _ => HostCall::Answered { op: op.into(), args: parsed, reply: serde_json::from_str(&reply).unwrap_or(Json::Null), source: "mock", at, took },
            };
            calls.borrow_mut().push(call);
        }
        reply
    })
}

/// One session call, measured into the record; its result for the test.
fn measured(rec: &Shared, what: &str, widget: Option<String>, f: impl FnOnce() -> String) -> Result<Value, PyError> {
    let started = Instant::now();
    let text = f();
    let ended = Instant::now();
    let result: Json = serde_json::from_str(&text).map_err(|e| PyError::new(e.to_string(), "ValueError"))?;
    let mut r = rec.borrow_mut();
    let spans: Vec<(Instant, Duration)> = r.calls.borrow().iter().filter_map(HostCall::span).filter(|(at, _)| *at >= started && *at <= ended).collect();
    let error = match result.get("error") {
        Some(e) if result["ok"] == json!(false) => Some(diag_of(e, &result, &r.entry)),
        _ => None,
    };
    let label = format!("{}: {what}", r.test);
    r.steps.push(Step { label, widget, phases: phases_of(&result, started, &spans), error, abort: super::drive::abort_of(&result) });
    Ok(json_to_value(&result))
}

/// A session as the dict `plugin_test.session()` returns, measured.
fn session_value(ctx: &Ctx, mock: Json, settings: Json) -> Result<Value, PyError> {
    let source = read_inside(&ctx.dir, &ctx.m.entry)?;
    let config = session_config(&ctx.dir, &ctx.m, settings);
    let mut session = Session::new(&config.to_string()).map_err(|e| PyError::new(e, "ValueError"))?;
    let seen: Rc<RefCell<Vec<Json>>> = Rc::new(RefCell::new(Vec::new()));
    let mut mock_side = None;
    let mut host_side = None;
    match ctx.backend {
        Backend::Mock => {
            let mock = (!mock.is_null()).then(|| Mock::new(mock));
            let inner = mock.as_ref().map(|m| m.bridge());
            session.set_bridge(Some(recording_bridge(&ctx.rec, inner, seen.clone(), true)));
            mock_side = mock;
        }
        Backend::Host => {
            let facts = PluginFacts::read(&ctx.dir).map_err(|e| PyError::new(e, "ValueError"))?;
            let opts = Options { dir: ctx.dir.clone(), ..Options::default() };
            let observer = collector(&ctx.rec.borrow().calls);
            let (world, rx) = make_world(&opts, &facts, &ctx.state, Some(observer)).map_err(|e| PyError::new(e, "ValueError"))?;
            let inner = bridge::bridge(world.clone());
            session.set_bridge(Some(recording_bridge(&ctx.rec, Some(inner), seen.clone(), false)));
            host_side = Some(Rc::new(HostSide { world, rx }));
        }
    }
    let s = Rc::new(RefCell::new(session));
    let rec = ctx.rec.clone();
    let settle_for = ctx.settle_for;
    let (s1, s2, s3, s4, s5, s6) = (s.clone(), s.clone(), s.clone(), s.clone(), s.clone(), s.clone());
    let (r1, r2, r3, r4, r6) = (rec.clone(), rec.clone(), rec.clone(), rec.clone(), rec.clone());
    let load = HostFunction::new("load", move |_| measured(&r1, "load", None, || s1.borrow_mut().load(&source)));
    let render = HostFunction::new("render", move |_| measured(&r2, "render", None, || s2.borrow_mut().render()));
    let event = HostFunction::new("event", move |call| {
        let e = call.value(0, Some("event")).cloned().unwrap_or(Value::None);
        let json = wm_python::host::v5::to_json(&e, 0);
        let widget = json["id"].as_str().map(String::from);
        measured(&r3, &format!("event {}", event_label(&json)), widget, || s3.borrow_mut().event(&json.to_string()))
    });
    let transform = HostFunction::new("transform", move |call| {
        let text: String = call.arg(0, Some("text"))?;
        let id: String = call.arg_or(1, Some("action_id"), String::new())?;
        measured(&r4, &format!("action {id}"), None, || s4.borrow_mut().transform(&text, &id))
    });
    let storage = HostFunction::new("storage", move |_| {
        let text = s5.borrow().storage_json();
        let json: Json = serde_json::from_str(&text).unwrap_or(json!({}));
        Ok(json_to_value(&json))
    });
    let settle = HostFunction::new("settle", move |_| match (&mock_side, &host_side) {
        (Some(mock), _) => settle_mock(&r6, &s6, mock),
        (None, Some(host)) => settle_host(&r6, &s6, host, settle_for),
        (None, None) => Err(PyError::new("settle() needs a session made with mock=", "ValueError")),
    });
    let seen2 = seen.clone();
    let calls = HostFunction::new("calls", move |_| Ok(json_to_value(&Json::Array(seen2.borrow().clone()))));
    Ok(Value::dict(vec![
        ("load", Value::HostFunction(load)),
        ("render", Value::HostFunction(render)),
        ("event", Value::HostFunction(event)),
        ("transform", Value::HostFunction(transform)),
        ("storage", Value::HostFunction(storage)),
        ("settle", Value::HostFunction(settle)),
        ("calls", Value::HostFunction(calls)),
    ]))
}

const MAX_SETTLE_ROUNDS: usize = 64;

/// `settle()` with a mock: each waiting `then=` call answered from it, round after round.
fn settle_mock(rec: &Shared, s: &Rc<RefCell<Session>>, mock: &Rc<Mock>) -> Result<Value, PyError> {
    let mut last = None;
    let mut first_failure = None;
    for _ in 0..MAX_SETTLE_ROUNDS {
        let waiting = std::mem::take(&mut *mock.waiting.borrow_mut());
        if waiting.is_empty() {
            return match first_failure.or(last) {
                Some(v) => Ok(v),
                None => measured(rec, "render", None, || s.borrow_mut().render()),
            };
        }
        for (token, op, args) in waiting {
            let reply = mock.reply(&op, &args);
            let value = match reply.get("error") {
                Some(_) => reply,
                None => reply.get("value").cloned().unwrap_or(Json::Null),
            };
            rec.borrow().calls.borrow_mut().push(HostCall::Later { token: token.clone(), value: value.clone() });
            let event = json!({"type": "result", "token": token, "value": value});
            let v = measured(rec, &format!("event result {token}"), None, || s.borrow_mut().event(&event.to_string()))?;
            let failed = matches!(&v, Value::Dict(d) if d.borrow().get(&wm_python::interp::HKey::str("ok")).is_some_and(|ok| ok.as_bool() == Some(false)));
            if failed && first_failure.is_none() {
                first_failure = Some(v.clone());
            }
            last = Some(v);
        }
    }
    Err(PyError::new(format!("settle(): calls still waiting after {MAX_SETTLE_ROUNDS} rounds"), "RuntimeError"))
}

/// `settle()` on the dev host: every answer delivered as it arrives, until none waits.
fn settle_host(rec: &Shared, s: &Rc<RefCell<Session>>, host: &Rc<HostSide>, limit: Duration) -> Result<Value, PyError> {
    let until = Instant::now() + limit;
    let mut last = None;
    loop {
        while let Ok(done) = host.rx.try_recv() {
            host.world.borrow_mut().http_done(done);
        }
        if !host.world.borrow().dialogs.is_empty() {
            host.world.borrow_mut().answer_dialog(Some(0));
        }
        let now = Instant::now();
        let due: Vec<Json> = {
            let mut w = host.world.borrow_mut();
            let mut due = Vec::new();
            let mut i = 0;
            while i < w.later.len() {
                if w.later[i].at <= now {
                    let l = w.later.remove(i);
                    if l.ends {
                        w.pending = w.pending.saturating_sub(1);
                    }
                    due.push(l.event);
                } else {
                    i += 1;
                }
            }
            due
        };
        for event in &due {
            let label = format!("event {}", event_label(event));
            last = Some(measured(rec, &label, None, || s.borrow_mut().event(&event.to_string()))?);
        }
        let busy = {
            let w = host.world.borrow();
            w.pending > w.sensors.len() || !w.later.is_empty()
        };
        if !busy || Instant::now() >= until {
            return match last {
                Some(v) => Ok(v),
                None => measured(rec, "render", None, || s.borrow_mut().render()),
            };
        }
        if due.is_empty() {
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}

/// `plugin_test.preview(id, file, mock=None, name=None, mime=None)` and `thumbnail`, measured.
fn preview_call(call: &mut wm_python::interp::Call, ctx: &Ctx, thumb: bool) -> Result<Value, PyError> {
    let m = &ctx.m;
    let id: String = call.arg(0, Some("id"))?;
    let (data, file_name) = match call.value(1, Some("file")) {
        Some(Value::Bytes(b)) => (b.to_vec(), "file".to_string()),
        Some(Value::Str(name)) => {
            let path = inside(&ctx.dir, name).map_err(|e| PyError::new(e, "ValueError"))?;
            let data = std::fs::read(&path).map_err(|e| PyError::new(format!("cannot read '{name}': {e}"), "ValueError"))?;
            (data, name.rsplit('/').next().unwrap_or(name).to_string())
        }
        _ => return Err(PyError::new("preview() needs a file name inside the plugin folder, or bytes", "TypeError")),
    };
    let mock = call.value(2, Some("mock")).map(|v| wm_python::host::v5::to_json(v, 0)).unwrap_or(Json::Null);
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
    let request = json!({"id": id, "name": name, "extension": extension, "mime": mime, "size": data.len(), "modified": null, "truncated": data.len() > max});
    let source = read_inside(&ctx.dir, &m.entry)?;
    let mut config = session_config(&ctx.dir, m, Json::Null);
    config["mode"] = Json::from("action");
    let mut session = Session::new(&config.to_string()).map_err(|e| PyError::new(e, "ValueError"))?;
    let _ = session.load(&source);
    let mock = (!mock.is_null()).then(|| Mock::new(mock));
    let whole = Rc::new(data);
    let inner = recording_bridge(&ctx.rec, mock.as_ref().map(|m| m.bridge()), Rc::new(RefCell::new(Vec::new())), true);
    let mut inner = Some(inner);
    session.set_bridge(Some(Box::new(move |op: &str, args: &str| {
        if op == "preview.read" {
            let a: Json = serde_json::from_str(args).unwrap_or_default();
            let offset = a["offset"].as_u64().unwrap_or(0) as usize;
            let length = (a["length"].as_u64().unwrap_or(0) as usize).min(1 << 20);
            let from = offset.min(whole.len());
            let to = offset.saturating_add(length).min(whole.len());
            use base64::Engine;
            return json!({"value": base64::engine::general_purpose::STANDARD.encode(&whole[from..to])}).to_string();
        }
        match inner.as_mut() {
            Some(f) => f(op, args),
            None => json!({"value": null}).to_string(),
        }
    })));
    let what = format!("{} {id}", if thumb { "thumbnail" } else { "preview" });
    measured(&ctx.rec, &what, None, || if thumb { session.thumbnail(&request.to_string(), &head) } else { session.preview(&request.to_string(), &head) })
}
