//! API 5: plugins written against the `wm` package (`prelude/*.py`, compiled
//! into the crate), which reaches the keyboard through one host module,
//! `_wm`. Everything a plugin asks the keyboard to do is a host call,
//! `_wm.call(op, args)`, checked here against the manifest's permissions
//! and then handed to the [`Bridge`] the front end attached. The keyboard
//! checks the user's grants and the field's state again before acting.
//!
//! # Bridge
//!
//! `bridge(op, args_json) -> reply_json`, synchronous. `args` is a JSON
//! object; bytes arrive as base64 strings. The reply is
//! `{"value": any}` or `{"error": {"kind": "PermissionError", "message": str, "needs": "network"?}}`.
//! `needs` names a permission the user has not granted; the session lists it
//! in the result's `needs` so the panel can offer to allow it.
//!
//! An op whose args carry `"async": token` answers later, as the event
//! `{"type": "result", "token": token, "value": v}` (or `"value": {"error": {...}}`).
//! With `"stream": true` too, pieces arrive first as
//! `{"type": "stream", "token": token, "value": v}`.
//!
//! With no bridge attached (tests, the CLI) a call is recorded in the
//! result's `calls`; an async one returns None and a blocking one raises
//! NotImplementedError.
//!
//! # Ops and the permission each needs
//!
//! | op | permission |
//! |---|---|
//! | `http.request` | network |
//! | `ai.ask` `ai.stream` `ai.available` | ai |
//! | `field.selection` `field.before` `field.after` `field.info` | field.read |
//! | `field.insert` `field.replace` `field.delete` `field.move` `field.action` `field.image` | field.write |
//! | `clipboard.get` / `clipboard.set` | clipboard.read / clipboard.write |
//! | `location.get` | location |
//! | `files.pick` `files.save` | files |
//! | `sensors.subscribe` `sensors.unsubscribe` `sensors.list` | sensors |
//! | `scan.qr` `scan.text` | scan |
//! | `device.vibrate` `device.flashlight` `device.speak` `device.battery` | device |
//! | `share.text` `share.open` | share |
//! | `notify.show` `notify.cancel` | notifications |
//! | `secrets.get` `secrets.set` `secrets.remove` | storage |
//! | `ui.toast` `ui.dialog` `plugin.close` `plugin.refresh` | none |
//!
//! `field.selection`, `field.before`, `field.after` and `clipboard.get` are
//! refused outside an event the user caused in the panel (a click, a change,
//! a submit, a choice, a tap, a key, a keyword or a header command), and
//! every `field.*` op outside a panel.

use std::cell::RefCell;
use std::rc::Rc;

use base64::Engine;
use serde_json::{json, Map, Value as Json};

use super::api::{reminder_cancel, reminder_list, reminder_set, timestamp, HostState, PluginInfo, MAX_INPUT, MAX_LOG_LINE};
use super::files::{Reminders, Storage};
use super::json_to_value;
use crate::interp::{take_utf16, Clock, HKey, Object, PyError, Runtime, Value};

/// `wm.api_version` for these plugins.
pub const API_VERSION: i64 = 5;

/// The front end's side of host calls: `(op, args_json) -> reply_json`.
pub type Bridge = Box<dyn FnMut(&str, &str) -> String>;
pub(crate) type SharedBridge = Rc<RefCell<Option<Bridge>>>;

/// The `wm` package: `(dotted name, source, is a package)`.
pub const PRELUDE: &[(&str, &str, bool)] = &[
    ("wm", include_str!("prelude/__init__.py"), true),
    ("wm._core", include_str!("prelude/_core.py"), false),
    ("wm._host", include_str!("prelude/_host.py"), false),
    ("wm._storage", include_str!("prelude/_storage.py"), false),
    ("wm.ai", include_str!("prelude/ai.py"), false),
    ("wm.clipboard", include_str!("prelude/clipboard.py"), false),
    ("wm.device", include_str!("prelude/device.py"), false),
    ("wm.field", include_str!("prelude/field.py"), false),
    ("wm.files", include_str!("prelude/files.py"), false),
    ("wm.http", include_str!("prelude/http.py"), false),
    ("wm.i18n", include_str!("prelude/i18n.py"), false),
    ("wm.location", include_str!("prelude/location.py"), false),
    ("wm.notify", include_str!("prelude/notify.py"), false),
    ("wm.plugin", include_str!("prelude/plugin.py"), false),
    ("wm.reminders", include_str!("prelude/reminders.py"), false),
    ("wm.scan", include_str!("prelude/scan.py"), false),
    ("wm.secrets", include_str!("prelude/secrets.py"), false),
    ("wm.sensors", include_str!("prelude/sensors.py"), false),
    ("wm.share", include_str!("prelude/share.py"), false),
    ("wm.ui", include_str!("prelude/ui.py"), false),
];

/// Event types that count as the user acting in the panel.
pub const USER_EVENTS: &[&str] = &["click", "change", "submit", "select", "tap", "drag", "press", "item_action", "keyword", "command"];

/// Ops that read what the user typed elsewhere: only while answering them.
const USER_ONLY: &[&str] = &["field.selection", "field.before", "field.after", "clipboard.get"];

/// Exception kinds a bridge may raise by name; anything else is a RuntimeError.
const RAISABLE: &[&str] = &[
    "PermissionError",
    "TimeoutError",
    "ConnectionError",
    "ValueError",
    "TypeError",
    "OSError",
    "RuntimeError",
    "NotImplementedError",
    "FileNotFoundError",
    "KeyError",
];

/// Deepest JSON a call's arguments may nest.
const MAX_ARGS_DEPTH: usize = 32;

/// The permission [op] needs: `Some(None)` for none, `None` for no such op.
pub fn permission_for(op: &str) -> Option<Option<&'static str>> {
    let (group, name) = op.split_once('.').unwrap_or((op, ""));
    Some(match (group, name) {
        ("http", "request") => Some("network"),
        ("ai", "ask" | "stream" | "available") => Some("ai"),
        ("field", "selection" | "before" | "after" | "info") => Some("field.read"),
        ("field", "insert" | "replace" | "delete" | "move" | "action" | "image") => Some("field.write"),
        ("clipboard", "get") => Some("clipboard.read"),
        ("clipboard", "set") => Some("clipboard.write"),
        ("location", "get") => Some("location"),
        ("files", "pick" | "save") => Some("files"),
        ("sensors", "subscribe" | "unsubscribe" | "list") => Some("sensors"),
        ("scan", "qr" | "text") => Some("scan"),
        ("device", "vibrate" | "flashlight" | "speak" | "battery") => Some("device"),
        ("share", "text" | "open") => Some("share"),
        ("notify", "show" | "cancel") => Some("notifications"),
        ("secrets", "get" | "set" | "remove") => Some("storage"),
        ("ui", "toast" | "dialog") | ("plugin", "close" | "refresh") => None,
        _ => return None,
    })
}

/// What an API 5 session adds to the config.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Extras {
    /// The plugin's other modules: `("helpers.py", source)`, `("pkg/__init__.py", source)`.
    pub files: Vec<(String, String)>,
    /// The values on the plugin's settings page, by key.
    pub settings: Json,
    /// `{"locale", "theme", "surface"}`.
    pub context: Json,
    /// The plugin's translations for the user's language, `{"English": "translation"}`.
    pub strings: Json,
}

/// A file name in the archive as the module name it imports as, or None
/// when it is not a module a plugin may add.
pub fn module_name(file: &str) -> Option<(String, bool)> {
    let stem = file.strip_suffix(".py")?;
    let (name, package) = match stem.strip_suffix("/__init__") {
        Some(p) => (p.replace('/', "."), true),
        None => (stem.replace('/', "."), false),
    };
    let valid = !name.is_empty()
        && name.split('.').all(|part| {
            let mut chars = part.chars();
            chars.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_') && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        });
    let top = name.split('.').next().unwrap_or("");
    let reserved = top == "wm" || top == "_wm" || top == "_native" || top == "main" || Runtime::python_stdlib_names().iter().any(|s| s.split('.').next() == Some(top));
    (valid && !reserved).then_some((name, package))
}

/// Give [rt] the standard library, the `wm` package, the plugin's own
/// modules and `_wm`, and return the state the host functions write to.
pub(crate) fn install(
    rt: &mut Runtime,
    info: &PluginInfo,
    extras: &Extras,
    storage: Option<Storage>,
    reminders: Option<Reminders>,
    clock: Rc<dyn Clock>,
    bridge: SharedBridge,
) -> Rc<RefCell<HostState>> {
    rt.enable_python_stdlib();
    for (name, source, package) in PRELUDE {
        rt.add_source_module(name, source, *package);
    }
    for (file, source) in &extras.files {
        if let Some((name, package)) = module_name(file) {
            rt.add_source_module(&name, source, package);
        }
    }

    let quota = storage.as_ref().map(|s| s.quota().max_total).unwrap_or(0);
    let state = Rc::new(RefCell::new(HostState::new(
        if info.has("storage") { storage } else { None },
        if info.has("reminders") { reminders } else { None },
    )));
    let s = state.clone();
    rt.set_on_print(Some(Box::new(move |line| s.borrow_mut().add_log(line))));

    let info = info.clone();
    let settings = json_to_value(&extras.settings);
    let settings = if matches!(settings, Value::Dict(_)) { settings } else { Value::dict(Vec::<(&str, Value)>::new()) };
    let context = json_to_value(&extras.context);
    let context = if matches!(context, Value::Dict(_)) { context } else { Value::dict(Vec::<(&str, Value)>::new()) };
    let strings = json_to_value(&extras.strings);
    let strings = if matches!(strings, Value::Dict(_)) { strings } else { Value::dict(Vec::<(&str, Value)>::new()) };
    let state2 = state.clone();
    rt.define_module("_wm", move |m| {
        let state = state2;
        m.set("api_version", API_VERSION);
        m.set("plugin_id", info.id.as_str());
        m.set("plugin_version", info.version.as_str());
        m.set("panel", Value::Bool(info.panel));
        m.set("permissions", Value::list(info.permissions.iter().map(|p| Value::str(p.trim())).collect()));
        m.set("settings", settings);
        m.set("context", context);
        m.set("strings", strings);
        m.set("storage_quota", Value::Int(quota as i64));

        let s = state.clone();
        m.define("log", move |call| {
            let line = call.args.iter().map(|v| v.to_str()).collect::<Vec<_>>().join(" ");
            s.borrow_mut().add_log(take_utf16(&line, MAX_LOG_LINE));
            Ok(Value::None)
        });
        let c = clock.clone();
        m.define("timestamp", move |call| timestamp(call, c.as_ref()));
        let (s, panel) = (state.clone(), info.panel);
        m.define("set_input", move |call| {
            let id: String = call.arg(0, Some("id"))?;
            let text: String = call.arg(1, Some("text"))?;
            if panel && !id.is_empty() {
                s.borrow_mut().inputs.push((id, take_utf16(&text, MAX_INPUT).to_string()));
            }
            Ok(Value::None)
        });
        m.define("arity", |call| Ok(Value::Int(arity(call.value(0, None).unwrap_or(&Value::None)))));
        m.define("b64", |call| match call.value(0, None) {
            Some(Value::Bytes(b)) => Ok(Value::str(base64::engine::general_purpose::STANDARD.encode(b))),
            Some(Value::Str(s)) => Ok(Value::str(base64::engine::general_purpose::STANDARD.encode(s.as_bytes()))),
            _ => Err(PyError::new("b64() needs bytes", "TypeError")),
        });

        // Storage, as strings; `wm.storage` keeps JSON in them.
        let no_storage = || PyError::new("this plugin did not declare the 'storage' permission", "PermissionError");
        let s = state.clone();
        m.define("storage_get", move |call| {
            let key: String = call.arg(0, Some("key"))?;
            let st = s.borrow();
            let store = st.storage.as_ref().ok_or_else(no_storage)?;
            Ok(store.get(&key).map(Value::from).unwrap_or(Value::None))
        });
        let s = state.clone();
        m.define("storage_set", move |call| {
            let key: String = call.arg(0, Some("key"))?;
            let value: String = call.arg(1, Some("value"))?;
            let mut st = s.borrow_mut();
            let store = st.storage.as_mut().ok_or_else(no_storage)?;
            store.set(&key, &value).map_err(|reason| PyError::new(reason, "ValueError"))?;
            Ok(Value::None)
        });
        let s = state.clone();
        m.define("storage_remove", move |call| {
            let key: String = call.arg(0, Some("key"))?;
            let mut st = s.borrow_mut();
            st.storage.as_mut().ok_or_else(no_storage)?.remove(&key);
            Ok(Value::None)
        });
        let s = state.clone();
        m.define("storage_keys", move |_| {
            let st = s.borrow();
            Ok(Value::from(st.storage.as_ref().ok_or_else(no_storage)?.keys()))
        });
        let s = state.clone();
        m.define("storage_used", move |_| {
            let st = s.borrow();
            Ok(Value::Int(st.storage.as_ref().ok_or_else(no_storage)?.used_bytes() as i64))
        });

        let no_reminders = || PyError::new("this plugin did not declare the 'reminders' permission", "PermissionError");
        let (s, c) = (state.clone(), clock.clone());
        m.define("reminders_set", move |call| {
            if s.borrow().reminders.is_none() {
                return Err(no_reminders());
            }
            reminder_set(&s, c.as_ref(), call)
        });
        let s = state.clone();
        m.define("reminders_cancel", move |call| {
            if s.borrow().reminders.is_none() {
                return Err(no_reminders());
            }
            reminder_cancel(&s, call)
        });
        let s = state.clone();
        m.define("reminders_list", move |_| {
            if s.borrow().reminders.is_none() {
                return Err(no_reminders());
            }
            Ok(reminder_list(&s))
        });

        let s = state.clone();
        m.define("call", move |call| host_call(&s, &info, &bridge, call));
    });
    state
}

/// How many positional arguments [f] takes; -1 when it takes any number or
/// cannot be told.
fn arity(f: &Value) -> i64 {
    match f {
        Value::Function(func) if func.is_variadic() => -1,
        Value::Function(func) => func.parameter_names().len() as i64,
        Value::Object(o) => match o.as_ref() {
            Object::Bound(_, inner) => match arity(inner) {
                -1 => -1,
                n => (n - 1).max(0),
            },
            Object::StaticMethod(inner) => arity(inner),
            _ => -1,
        },
        _ => -1,
    }
}

fn host_call(state: &Rc<RefCell<HostState>>, info: &PluginInfo, bridge: &SharedBridge, call: &mut crate::interp::Call) -> Result<Value, PyError> {
    let op: String = call.arg(0, Some("op"))?;
    let args = call.value(1, Some("args")).cloned().unwrap_or(Value::None);
    let Some(needed) = permission_for(&op) else {
        return Err(PyError::new(format!("the keyboard has no '{op}'"), "ValueError"));
    };
    if let Some(p) = needed {
        if !info.has(p) {
            return Err(PyError::new(format!("{op} needs the '{p}' permission, which this plugin's manifest does not list"), "PermissionError"));
        }
    }
    if op.starts_with("field.") && !info.panel {
        return Err(PyError::new(format!("{op} works only from the plugin's panel"), "PermissionError"));
    }
    if USER_ONLY.contains(&op.as_str()) && !state.borrow().user_event {
        return Err(PyError::new(
            format!("{op} works only while the plugin answers something the user did in its panel"),
            "PermissionError",
        ));
    }
    let args = match to_json(&args, 0) {
        Json::Object(o) => Json::Object(o),
        Json::Null => Json::Object(Map::new()),
        _ => return Err(PyError::new("host call arguments must be a dict", "TypeError")),
    };
    let is_async = args.get("async").is_some_and(|t| t.is_string());
    let reply = {
        let mut b = bridge.borrow_mut();
        b.as_mut().map(|f| f(&op, &args.to_string()))
    };
    let Some(reply) = reply else {
        state.borrow_mut().calls.push(json!({"op": op, "args": args}));
        return if is_async { Ok(Value::None) } else { Err(PyError::new(format!("{op} is not available here"), "NotImplementedError")) };
    };
    let reply: Json = serde_json::from_str(&reply).unwrap_or_else(|_| json!({"error": {"kind": "RuntimeError", "message": "the keyboard sent a reply it could not read"}}));
    if let Some(err) = reply.get("error") {
        let kind = err.get("kind").and_then(Json::as_str).unwrap_or("RuntimeError");
        let kind = if RAISABLE.contains(&kind) { kind } else { "RuntimeError" };
        let message = err.get("message").and_then(Json::as_str).unwrap_or("").to_string();
        if let Some(need) = err.get("needs").and_then(Json::as_str) {
            let mut st = state.borrow_mut();
            if !st.needs.iter().any(|n| n == need) {
                st.needs.push(need.to_string());
            }
        }
        return Err(PyError::new(message, kind));
    }
    Ok(reply.get("value").map(json_to_value).unwrap_or(Value::None))
}

/// A script value as JSON: bytes as base64, dict keys as strings, anything
/// with no JSON form (a function, a class) as null.
pub fn to_json(v: &Value, depth: usize) -> Json {
    if depth > MAX_ARGS_DEPTH {
        return Json::Null;
    }
    match v {
        Value::None => Json::Null,
        Value::Bool(b) => Json::Bool(*b),
        Value::Int(i) => json!(i),
        Value::Float(f) if f.is_finite() => json!(f),
        Value::Float(_) => Json::Null,
        Value::Str(s) => Json::String(s.to_string()),
        Value::Bytes(b) => Json::String(base64::engine::general_purpose::STANDARD.encode(b)),
        Value::List(l) => Json::Array(l.borrow().iter().map(|x| to_json(x, depth + 1)).collect()),
        Value::Tuple(t) => Json::Array(t.iter().map(|x| to_json(x, depth + 1)).collect()),
        Value::Set(s) => Json::Array(s.borrow().iter().map(|k| to_json(&k.to_value(), depth + 1)).collect()),
        Value::Dict(d) => {
            let mut out = Map::new();
            for (k, x) in d.borrow().iter() {
                let key = match k {
                    HKey::Str(s) => s.to_string(),
                    other => other.to_value().to_str(),
                };
                out.insert(key, to_json(x, depth + 1));
            }
            Json::Object(out)
        }
        _ => Json::Null,
    }
}
