//! The `wm` and `ui` globals: everything a plugin can reach beyond its own
//! arithmetic. That is the whole list; there is no `wm.text`, no clipboard and
//! no network, and their absence is the security model.
//!
//! * `wm.api_version` (4), `wm.plugin_id`, `wm.plugin_version`
//! * `wm.log(*values)`: `str()` of each, joined by spaces, capped at 512
//! * `wm.timestamp(year, month, day, hour=12, minute=0, second=0)`: local time, lenient rollover
//! * `wm.ui.set_input(id, text)`: panel mode only, text capped at 8 KB, applied after the call
//! * `wm.storage.get(key, default=None)`, `.set(key, value)`, `.remove(key)`, `.keys()`: with `storage`
//! * `wm.reminders.set(id, seconds, text)`, `.cancel(id)`, `.list()`: with `reminders`
//! * `ui.column`, `row`, `label`, `output`, `button`, `toggle`, `input`, `date`,
//!   `spacer`, `divider`, `progress`, `tabs`, `page`: plain dicts, keyed as the
//!   Lua prelude's tables were.

use std::cell::RefCell;
use std::rc::Rc;

use super::files::{Reminders, Storage};
use crate::interp::{local_to_unix, take_utf16, Call, Clock, PyError, Runtime, Value};

/// `wm.api_version`.
pub const API_VERSION: i64 = 4;
/// One log line, after which the rest is dropped.
pub const MAX_LOG_LINE: usize = 512;
/// One `wm.ui.set_input` text.
pub const MAX_INPUT: usize = 8 * 1024;
/// Far past any real date field; keeps every sum exact.
const MAX_DATE_FIELD: f64 = 1_000_000.0;
const MILLIS: i64 = 1000;

/// Who the plugin is and what it may do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PluginInfo {
    pub id: String,
    pub version: String,
    pub permissions: Vec<String>,
    /// A panel session has `wm.ui`; a selection action's does not.
    pub panel: bool,
}

impl PluginInfo {
    pub fn has(&self, permission: &str) -> bool {
        self.permissions.iter().any(|p| p.trim() == permission)
    }
}

/// What the host functions collect during a call, and the files they use.
pub struct HostState {
    pub log: Vec<String>,
    pub inputs: Vec<(String, String)>,
    pub reminders_changed: bool,
    pub storage: Option<Storage>,
    pub reminders: Option<Reminders>,
    /// API 5: host calls made with no bridge attached, `{"op", "args"}`.
    pub calls: Vec<serde_json::Value>,
    /// API 5: permissions the keyboard said a call lacked a grant for.
    pub needs: Vec<String>,
    /// API 5: the call in flight answers something the user did in the panel.
    pub user_event: bool,
}

impl HostState {
    pub fn add_log(&mut self, line: &str) {
        self.log.push(take_utf16(line, MAX_LOG_LINE).to_string());
    }

    /// Clears what one call collected.
    pub fn begin_call(&mut self) {
        self.log.clear();
        self.inputs.clear();
        self.reminders_changed = false;
        self.calls.clear();
        self.needs.clear();
        self.user_event = false;
    }

    pub fn new(storage: Option<Storage>, reminders: Option<Reminders>) -> HostState {
        HostState {
            log: Vec::new(),
            inputs: Vec::new(),
            reminders_changed: false,
            storage,
            reminders,
            calls: Vec::new(),
            needs: Vec::new(),
            user_event: false,
        }
    }
}

/// Install `wm` and `ui` as globals of [rt], `print` included, and return the
/// state the host functions write to.
pub fn install(rt: &mut Runtime, info: &PluginInfo, storage: Option<Storage>, reminders: Option<Reminders>, clock: Rc<dyn Clock>) -> Rc<RefCell<HostState>> {
    let state = Rc::new(RefCell::new(HostState::new(
        if info.has("storage") { storage } else { None },
        if info.has("reminders") { reminders } else { None },
    )));

    let s = state.clone();
    rt.set_on_print(Some(Box::new(move |line| s.borrow_mut().add_log(line))));

    let (has_storage, has_reminders) = (state.borrow().storage.is_some(), state.borrow().reminders.is_some());
    let s = state.clone();
    let c = clock.clone();
    let info2 = info.clone();
    rt.define_module("wm", move |m| {
        m.set("api_version", API_VERSION);
        m.set("plugin_id", info2.id.as_str());
        m.set("plugin_version", info2.version.as_str());
        if info2.panel {
            m.set("ui", Value::Module(Rc::from("wm.ui")));
        }
        if has_storage {
            m.set("storage", Value::Module(Rc::from("wm.storage")));
        }
        if has_reminders {
            m.set("reminders", Value::Module(Rc::from("wm.reminders")));
        }
        let s1 = s.clone();
        m.define("log", move |call| {
            let line = call.args.iter().map(|v| v.to_str()).collect::<Vec<_>>().join(" ");
            s1.borrow_mut().add_log(&line);
            Ok(Value::None)
        });
        let c1 = c.clone();
        m.define("timestamp", move |call| timestamp(call, c1.as_ref()));
    });

    if info.panel {
        let s = state.clone();
        rt.define_module("wm.ui", move |m| {
            m.define("set_input", move |call| {
                let id: String = call.arg(0, Some("id"))?;
                let text: String = call.arg(1, Some("text"))?;
                if !id.is_empty() {
                    s.borrow_mut().inputs.push((id, take_utf16(&text, MAX_INPUT).to_string()));
                }
                Ok(Value::None)
            });
        });
    }

    if has_storage {
        let s = state.clone();
        rt.define_module("wm.storage", move |m| {
            let s1 = s.clone();
            m.define("get", move |call| {
                let key: String = call.arg(0, Some("key"))?;
                let default = call.value(1, Some("default")).cloned().unwrap_or(Value::None);
                let st = s1.borrow();
                Ok(st.storage.as_ref().and_then(|x| x.get(&key)).map(Value::from).unwrap_or(default))
            });
            let s2 = s.clone();
            m.define("set", move |call| {
                let key: String = call.arg(0, Some("key"))?;
                let value: String = call.arg(1, Some("value"))?;
                let mut st = s2.borrow_mut();
                match st.storage.as_mut().map(|x| x.set(&key, &value)) {
                    Some(Err(reason)) => Err(PyError::new(reason, "ValueError")),
                    _ => Ok(Value::None),
                }
            });
            let s3 = s.clone();
            m.define("remove", move |call| {
                let key: String = call.arg(0, Some("key"))?;
                if let Some(x) = s3.borrow_mut().storage.as_mut() {
                    x.remove(&key);
                }
                Ok(Value::None)
            });
            let s4 = s.clone();
            m.define("keys", move |_| Ok(Value::from(s4.borrow().storage.as_ref().map(|x| x.keys()).unwrap_or_default())));
        });
    }

    if has_reminders {
        let s = state.clone();
        let c = clock.clone();
        rt.define_module("wm.reminders", move |m| {
            let s1 = s.clone();
            let c1 = c.clone();
            m.define("set", move |call| reminder_set(&s1, c1.as_ref(), call));
            let s2 = s.clone();
            m.define("cancel", move |call| reminder_cancel(&s2, call));
            let s3 = s.clone();
            m.define("list", move |_| Ok(reminder_list(&s3)));
        });
    }

    rt.define_module("ui", install_ui);
    rt.set("wm", Some(Value::Module(Rc::from("wm"))));
    rt.set("ui", Some(Value::Module(Rc::from("ui"))));
    state
}

/// `reminders.set(id, seconds, text)`.
pub(crate) fn reminder_set(state: &Rc<RefCell<HostState>>, clock: &dyn Clock, call: &mut Call) -> Result<Value, PyError> {
    let id: String = call.arg(0, Some("id"))?;
    let time = call.value(1, Some("seconds")).cloned().unwrap_or(Value::None);
    let text: String = call.arg(2, Some("text"))?;
    let seconds = match time {
        Value::Int(i) => i as f64,
        Value::Float(f) if f.is_finite() => f,
        _ => {
            return Err(PyError::new(
                "the time must be a number of seconds, as time.time() or wm.timestamp() gives",
                "ValueError",
            ))
        }
    };
    let now = (clock.now() * MILLIS as f64) as i64;
    let mut st = state.borrow_mut();
    let result = st.reminders.as_mut().map(|r| r.set(&id, seconds_to_millis(seconds), &text, now));
    match result {
        Some(Err(reason)) => Err(PyError::new(reason, "ValueError")),
        _ => {
            st.reminders_changed = true;
            Ok(Value::None)
        }
    }
}

/// `reminders.cancel(id)`: True when there was one.
pub(crate) fn reminder_cancel(state: &Rc<RefCell<HostState>>, call: &mut Call) -> Result<Value, PyError> {
    let id: String = call.arg(0, Some("id"))?;
    let mut st = state.borrow_mut();
    let removed = st.reminders.as_mut().is_some_and(|r| r.cancel(&id));
    if removed {
        st.reminders_changed = true;
    }
    Ok(Value::Bool(removed))
}

/// `reminders.list()`: `[{"id", "time", "text"}]`, soonest first.
pub(crate) fn reminder_list(state: &Rc<RefCell<HostState>>) -> Value {
    let st = state.borrow();
    let list = st.reminders.as_ref().map(|r| r.list()).unwrap_or_default();
    Value::list(
        list.into_iter()
            .map(|r| {
                Value::dict(vec![
                    ("id", Value::from(r.id)),
                    ("time", Value::Int(r.at.div_euclid(MILLIS))),
                    ("text", Value::from(r.text)),
                ])
            })
            .collect(),
    )
}

/// Whole seconds to milliseconds, saturating rather than wrapping: a far-off time is refused, not moved.
fn seconds_to_millis(seconds: f64) -> i64 {
    let whole = seconds.floor();
    let limit = (i64::MAX / MILLIS) as f64;
    if whole >= limit {
        i64::MAX
    } else if whole <= -limit {
        i64::MIN
    } else {
        whole as i64 * MILLIS
    }
}

/// `wm.timestamp(year, month, day, hour=12, minute=0, second=0)`: that moment in
/// the phone's time zone. A field past its range rolls into the next one, so
/// `day + 1` on the last day of a month is the first of the next.
pub(crate) fn timestamp(call: &mut Call, clock: &dyn Clock) -> Result<Value, PyError> {
    let names = ["year", "month", "day", "hour", "minute", "second"];
    let defaults = [None, None, None, Some(12), Some(0), Some(0)];
    let mut fields = [0i64; 6];
    for (i, name) in names.iter().enumerate() {
        let v = match call.value(i, Some(name)) {
            None | Some(Value::None) => match defaults[i] {
                Some(d) => {
                    fields[i] = d;
                    continue;
                }
                None => {
                    return Err(PyError::new(format!("timestamp() missing required argument '{name}'"), "TypeError"));
                }
            },
            Some(v) => v.clone(),
        };
        let n = match v {
            Value::Int(i) => i as f64,
            Value::Float(f) => f,
            other => {
                return Err(PyError::new(
                    format!("timestamp() argument '{name}' must be a number, not {}", other.type_name()),
                    "TypeError",
                ))
            }
        };
        if !n.is_finite() || n.abs() > MAX_DATE_FIELD {
            return Err(PyError::new(format!("field '{name}' is out of range"), "ValueError"));
        }
        fields[i] = n.trunc() as i64;
    }
    let [y, mo, d, h, mi, s] = fields;
    Ok(Value::Int(local_to_unix(y, mo, d, h, mi, s, clock)))
}

/// Bind a builder's arguments like a Python signature: positional, then by
/// name; a missing optional is its default.
pub(crate) fn bind(call: &Call, params: &[(&str, Option<Value>)]) -> Result<Vec<Value>, PyError> {
    if call.args.len() > params.len() {
        return Err(PyError::new(
            format!("{}() takes {} arguments but {} were given", call.name, params.len(), call.args.len()),
            "TypeError",
        ));
    }
    for (k, _) in &call.kwargs {
        if !params.iter().any(|(n, _)| *n == k.as_ref()) {
            return Err(PyError::new(format!("{}() got an unexpected keyword argument '{k}'", call.name), "TypeError"));
        }
    }
    let mut out = Vec::with_capacity(params.len());
    for (i, (name, default)) in params.iter().enumerate() {
        match call.value(i, Some(name)) {
            Some(v) => out.push(v.clone()),
            None => match default {
                Some(d) => out.push(d.clone()),
                None => return Err(PyError::new(format!("{}() missing required argument '{name}'", call.name), "TypeError")),
            },
        }
    }
    Ok(out)
}

/// `*children`, or one list of them.
fn rest(call: &Call, from: usize) -> Value {
    let args = &call.args[from.min(call.args.len())..];
    if args.len() == 1 {
        if let Some(items) = args[0].as_items() {
            return Value::list(items);
        }
    }
    Value::list(args.to_vec())
}

fn no_kwargs(call: &Call, allowed: &[&str]) -> Result<(), PyError> {
    for (k, _) in &call.kwargs {
        if !allowed.contains(&k.as_ref()) {
            return Err(PyError::new(format!("{}() got an unexpected keyword argument '{k}'", call.name), "TypeError"));
        }
    }
    Ok(())
}

fn widget(ty: &str, fields: Vec<(&str, Value)>) -> Value {
    let mut pairs = vec![("type", Value::str(ty))];
    pairs.extend(fields);
    Value::dict(pairs)
}

fn install_ui(m: &mut crate::interp::HostModule) {
    let none = || Some(Value::None);
    for ty in ["column", "row"] {
        m.define(ty, move |call| {
            no_kwargs(call, &[])?;
            Ok(widget(ty, vec![("children", rest(call, 0))]))
        });
    }
    m.define("label", move |call| {
        let a = bind(call, &[("text", None), ("style", none())])?;
        Ok(widget("label", vec![("text", a[0].clone()), ("style", a[1].clone())]))
    });
    m.define("output", move |call| {
        let a = bind(
            call,
            &[
                ("text", None),
                ("id", none()),
                ("mono", Some(Value::Bool(false))),
                ("insertable", Some(Value::Bool(true))),
                ("copyable", Some(Value::Bool(true))),
            ],
        )?;
        Ok(widget(
            "output",
            vec![("id", a[1].clone()), ("text", a[0].clone()), ("mono", a[2].clone()), ("insertable", a[3].clone()), ("copyable", a[4].clone())],
        ))
    });
    m.define("button", move |call| {
        let a = bind(call, &[("id", None), ("text", None), ("style", none()), ("enabled", Some(Value::Bool(true)))])?;
        Ok(widget("button", vec![("id", a[0].clone()), ("text", a[1].clone()), ("style", a[2].clone()), ("enabled", a[3].clone())]))
    });
    m.define("toggle", move |call| {
        let a = bind(call, &[("id", None), ("label", None), ("checked", Some(Value::Bool(false)))])?;
        Ok(widget("toggle", vec![("id", a[0].clone()), ("label", a[1].clone()), ("checked", a[2].clone())]))
    });
    m.define("input", move |call| {
        let a = bind(call, &[("id", None), ("label", none()), ("placeholder", none())])?;
        Ok(widget("input", vec![("id", a[0].clone()), ("label", a[1].clone()), ("placeholder", a[2].clone())]))
    });
    m.define("date", move |call| {
        let a = bind(
            call,
            &[("id", None), ("label", none()), ("value", none()), ("time", Some(Value::Bool(false))), ("step", Some(Value::Int(15)))],
        )?;
        Ok(widget(
            "date",
            vec![("id", a[0].clone()), ("label", a[1].clone()), ("value", a[2].clone()), ("time", a[3].clone()), ("step", a[4].clone())],
        ))
    });
    m.define("spacer", move |call| {
        let a = bind(call, &[("height", Some(Value::Int(8)))])?;
        Ok(widget("spacer", vec![("height", a[0].clone())]))
    });
    m.define("divider", move |call| {
        bind(call, &[])?;
        Ok(widget("divider", vec![]))
    });
    m.define("progress", move |call| {
        bind(call, &[])?;
        Ok(widget("progress", vec![]))
    });
    m.define("tabs", move |call| {
        no_kwargs(call, &["id"])?;
        let (id, from) = match call.value(0, Some("id")) {
            Some(v) if call.kwargs.iter().any(|(k, _)| k.as_ref() == "id") => (v.clone(), 0),
            Some(v) => (v.clone(), 1),
            None => return Err(PyError::new("tabs() missing required argument 'id'", "TypeError")),
        };
        Ok(widget("tabs", vec![("id", id), ("pages", rest(call, from))]))
    });
    m.define("page", move |call| {
        no_kwargs(call, &["title"])?;
        let (title, from) = match call.value(0, Some("title")) {
            Some(v) if call.kwargs.iter().any(|(k, _)| k.as_ref() == "title") => (v.clone(), 0),
            Some(v) => (v.clone(), 1),
            None => return Err(PyError::new("page() missing required argument 'title'", "TypeError")),
        };
        Ok(Value::dict(vec![("title", title), ("children", rest(call, from))]))
    });
}
