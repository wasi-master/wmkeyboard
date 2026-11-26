//! The development host's answers to API 5 host calls: what a computer can
//! do for real (https requests to the manifest's hosts), a pretend text
//! field and clipboard the person can see and edit, and pretend answers of
//! the right shape for what only a phone has. A `--mock` file answers any
//! op first, as `wmpy test` answers it.
//!
//! The interpreter has already refused anything the manifest does not
//! declare, and the reads only a user's own action may make. As in the
//! keyboard's own editor, the risky permissions a plugin declares count as
//! granted.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};

use super::http::{self, Failure, Reach, MAX_REQUESTS_PER_MINUTE};
use super::plugin::{stored_text, typed, PluginFacts, Secrets, SettingValues, MAX_SECRET, MAX_SETTING_TEXT};
use super::pretend::{self, Task};
use crate::mock::Mock;

/// PluginHost.MAX_PENDING: calls waiting for an answer at once.
pub const MAX_PENDING: usize = 16;
/// PluginKeyboardServices' caps.
const MAX_INSERT: usize = 64 * 1024;
const MAX_DELETE: i64 = 10_000;
const MAX_READ: i64 = 10_000;
const MAX_CLIP: usize = 64 * 1024;
const MAX_TOAST: usize = 200;
const MAX_DIALOG_BUTTONS: usize = 3;
const MAX_SUBSCRIPTIONS: usize = 8;
/// How long a toast stays on the pretend panel.
pub const TOAST_FOR: Duration = Duration::from_millis(3500);

/// The pretend text field: what the plugin's `field.*` calls read and change.
#[derive(Clone, Debug, Default)]
pub struct Field {
    pub text: Vec<char>,
    /// In characters.
    pub cursor: usize,
    /// A selection, `(start, end)` in characters, `start < end`.
    pub selection: Option<(usize, usize)>,
}

impl Field {
    pub fn string(&self) -> String {
        self.text.iter().collect()
    }

    pub fn set(&mut self, text: &str, cursor: usize, selection: Option<(usize, usize)>) {
        self.text = text.chars().collect();
        self.cursor = cursor.min(self.text.len());
        self.selection = selection.filter(|(a, b)| a < b && *b <= self.text.len());
    }

    /// commitText: the selection replaced, or [t] put at the cursor.
    pub fn insert(&mut self, t: &str) {
        let add: Vec<char> = t.chars().take(MAX_INSERT).collect();
        let (from, to) = self.selection.take().unwrap_or((self.cursor, self.cursor));
        self.text.splice(from..to, add.iter().copied());
        self.cursor = from + add.len();
    }

    pub fn delete(&mut self, before: usize, after: usize) {
        if let Some((a, b)) = self.selection.take() {
            self.cursor = a;
            self.text.drain(a..b);
            return;
        }
        let from = self.cursor.saturating_sub(before);
        let to = (self.cursor + after).min(self.text.len());
        self.text.drain(from..to);
        self.cursor = from;
    }

    pub fn move_by(&mut self, by: i64) {
        self.selection = None;
        self.cursor = (self.cursor as i64 + by).clamp(0, self.text.len() as i64) as usize;
    }

    pub fn before(&self, n: usize) -> String {
        self.text[self.cursor.saturating_sub(n)..self.cursor].iter().collect()
    }

    pub fn after(&self, n: usize) -> String {
        self.text[self.cursor..(self.cursor + n).min(self.text.len())].iter().collect()
    }

    pub fn selected(&self) -> String {
        self.selection.map(|(a, b)| self.text[a..b].iter().collect()).unwrap_or_default()
    }

    /// `before|after`, for the console.
    pub fn shown(&self) -> String {
        let before: String = self.text[..self.cursor].iter().collect();
        let after: String = self.text[self.cursor..].iter().collect();
        format!("“{before}|{after}”")
    }
}

#[derive(Clone, Debug)]
pub struct Dialog {
    pub token: Option<String>,
    pub title: String,
    pub text: Option<String>,
    pub buttons: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct Sensor {
    pub token: String,
    pub kind: String,
    pub period: Duration,
    pub next: Instant,
    pub started: Instant,
    pub n: u64,
}

/// One host call, for the Calls pane.
#[derive(Clone, Debug)]
pub struct CallRecord {
    pub op: String,
    pub args: Json,
    /// What came back, in a line.
    pub reply: String,
    /// "real", "pretend", "mock", "refused", or "result" for an answer that came later.
    pub source: &'static str,
    pub millis: u64,
}

/// An answer that arrives later, as the event the session receives.
#[derive(Clone, Debug)]
pub struct Later {
    pub at: Instant,
    pub event: Json,
    /// A `result` (which ends a pending call), rather than a `stream` piece.
    pub ends: bool,
}

/// A request a thread finished: its token and the result's value.
pub struct HttpDone {
    pub token: String,
    pub value: Json,
    pub millis: u64,
}

/// One host call as an [Observer] sees it: `wmpy budget` takes the time
/// spent outside the script from these, and `wmpy mock record` writes them
/// down. Args are whole, not shortened as the Calls pane shows them.
pub enum Seen<'a> {
    /// A call answered at once, or refused (a `then=` call too): the reply,
    /// `{"value": v}` or `{"error": {"kind", "message"}}`.
    Answered { op: &'a str, args: &'a Json, reply: &'a Json, source: &'static str, took: Duration },
    /// A `then=` call under way: its answer comes as [Seen::Later] with the
    /// same token, which may be seen just before this when it is known at once.
    Started { op: &'a str, args: &'a Json, token: &'a str, source: &'static str, took: Duration },
    /// The answer to a `then=` call: the value of its `result` event.
    Later { token: &'a str, value: &'a Json },
}

/// Hears every host call; it must not touch the [World] it is given to.
pub type Observer = Rc<dyn Fn(&Seen)>;

/// Everything the bridge reads and changes, shared with the host.
pub struct World {
    pub facts: PluginFacts,
    pub reach: Reach,
    pub mock: Option<Rc<Mock>>,
    pub field: Field,
    pub clipboard: String,
    pub toasts: Vec<(String, Instant)>,
    pub dialogs: VecDeque<Dialog>,
    pub later: Vec<Later>,
    pub sensors: Vec<Sensor>,
    /// Console lines the host writes: `[toast] ...`, `[field] ...`.
    pub notes: Vec<String>,
    pub calls: Vec<CallRecord>,
    pub secrets: Secrets,
    pub settings: SettingValues,
    pub tasks: Vec<Task>,
    next_task: i64,
    next_event: i64,
    requests: VecDeque<Instant>,
    /// Async calls started and not yet answered.
    pub pending: usize,
    pub http_tx: Sender<HttpDone>,
    /// `plugin.close()` was called.
    pub closed: bool,
    /// Hears every call and answer, when a command set one.
    pub observer: Option<Observer>,
}

impl World {
    pub fn new(facts: PluginFacts, reach: Reach, mock: Option<Rc<Mock>>, secrets: Secrets, settings: SettingValues, http_tx: Sender<HttpDone>) -> World {
        World {
            facts,
            reach,
            mock,
            field: Field::default(),
            clipboard: "Text the user copied".into(),
            toasts: Vec::new(),
            dialogs: VecDeque::new(),
            later: Vec::new(),
            sensors: Vec::new(),
            notes: Vec::new(),
            calls: Vec::new(),
            secrets,
            settings,
            tasks: pretend::tasks(),
            next_task: 5,
            next_event: 5000,
            requests: VecDeque::new(),
            pending: 0,
            http_tx,
            closed: false,
            observer: None,
        }
    }

    pub fn note(&mut self, text: impl Into<String>) {
        self.notes.push(text.into());
    }

    fn later(&mut self, token: &str, value: Json, after: Duration) {
        if let Some(observer) = self.observer.clone() {
            observer(&Seen::Later { token, value: &value });
        }
        self.later.push(Later { at: Instant::now() + after, event: json!({"type": "result", "token": token, "value": value}), ends: true });
    }

    fn piece(&mut self, token: &str, value: Json, after: Duration) {
        self.later.push(Later { at: Instant::now() + after, event: json!({"type": "stream", "token": token, "value": value}), ends: false });
    }

    /// Answer a dialog: the button at [index], or None when dismissed.
    pub fn answer_dialog(&mut self, index: Option<usize>) {
        let Some(d) = self.dialogs.pop_front() else { return };
        let label = index.and_then(|i| d.buttons.get(i).cloned());
        self.note(format!("[dialog] {}", label.as_deref().unwrap_or("dismissed")));
        if let Some(token) = d.token {
            self.later(&token, label.map(Json::String).unwrap_or(Json::Null), Duration::ZERO);
        }
    }

    /// A request the plugin started with `then=` came back.
    pub fn http_done(&mut self, done: HttpDone) {
        let reply = reply_line(&done.value);
        self.calls.push(CallRecord { op: "http.request".into(), args: json!({"token": done.token}), reply, source: "result", millis: done.millis });
        self.later(&done.token, done.value, Duration::ZERO);
    }
}

/// What one op does: answered now, answered later, or under way some other way.
enum Outcome {
    Now(Result<Json, Failure>),
    /// For an async call: the answer, delivered after a pause (the web editor's pacing).
    After(Duration, Result<Json, Failure>),
    /// Refused before anything was sent: raised at once even for `then=`.
    Refused(Failure),
    /// An async call that answers by itself (a request thread, a dialog, a sensor).
    Started,
}

/// The bridge a session calls.
pub fn bridge(world: Rc<RefCell<World>>) -> wm_python::host::Bridge {
    Box::new(move |op: &str, args: &str| {
        let args: Json = serde_json::from_str(args).unwrap_or(Json::Object(Default::default()));
        call(&world, op, args).to_string()
    })
}

fn ok(v: Json) -> Result<Json, Failure> {
    Ok(v)
}

fn err(kind: &'static str, message: impl Into<String>) -> Result<Json, Failure> {
    Err((kind, message.into()))
}

/// One host call, recorded for the Calls pane.
pub fn call(world: &Rc<RefCell<World>>, op: &str, args: Json) -> Json {
    let started = Instant::now();
    let token = args.get("async").and_then(Json::as_str).map(String::from);
    let mut w = world.borrow_mut();
    if token.is_some() && w.pending >= MAX_PENDING {
        let e = ("RuntimeError", format!("too many calls waiting for an answer: at most {MAX_PENDING}"));
        return finish(&mut w, op, args, started, "refused", Err(e));
    }
    // A mock file answers first; a route list that has no route for these
    // args leaves the call to the host.
    let mocked = w.mock.as_ref().filter(|m| m.answers.get(op).is_some()).map(|m| m.reply(op, &args)).filter(|r| {
        !r.pointer("/error/message").and_then(Json::as_str).is_some_and(|m| m.starts_with("no mock route"))
    });
    let (outcome, source) = match mocked {
        Some(reply) => {
            let r = match reply.get("error") {
                Some(e) => Err((raisable(e.get("kind").and_then(Json::as_str).unwrap_or("RuntimeError")), e.get("message").and_then(Json::as_str).unwrap_or("").to_string())),
                None => Ok(reply.get("value").cloned().unwrap_or(Json::Null)),
            };
            (Outcome::After(Duration::ZERO, r), "mock")
        }
        None => {
            let real = op == "http.request";
            (run(&mut w, op, &args, token.as_deref()), if real { "real" } else { "pretend" })
        }
    };
    let (source, result) = match (outcome, token.as_deref()) {
        (Outcome::Refused(e), _) => ("refused", Err(e)),
        (Outcome::Now(r) | Outcome::After(_, r), None) => (source, r),
        (Outcome::Started, None) => (source, Ok(Json::Null)),
        (outcome @ (Outcome::Now(_) | Outcome::After(..)), Some(t)) => {
            let (delay, r) = match outcome {
                Outcome::After(d, r) => (d, r),
                Outcome::Now(r) => (Duration::ZERO, r),
                _ => unreachable!(),
            };
            w.pending += 1;
            let value = match &r {
                Ok(v) => v.clone(),
                Err((kind, message)) => json!({"error": {"kind": kind, "message": message}}),
            };
            w.later(t, value, delay);
            (source, Ok(Json::Null))
        }
        (Outcome::Started, Some(_)) => {
            w.pending += 1;
            (source, Ok(Json::Null))
        }
    };
    finish(&mut w, op, args, started, source, result)
}

fn finish(w: &mut World, op: &str, args: Json, started: Instant, source: &'static str, result: Result<Json, Failure>) -> Json {
    if let Some(observer) = w.observer.clone() {
        let took = started.elapsed();
        match (&result, args.get("async").and_then(Json::as_str)) {
            (Ok(_), Some(token)) => observer(&Seen::Started { op, args: &args, token, source, took }),
            (outcome, _) => {
                let reply = match outcome {
                    Ok(v) => json!({"value": v}),
                    Err((kind, message)) => json!({"error": {"kind": kind, "message": message}}),
                };
                observer(&Seen::Answered { op, args: &args, reply: &reply, source, took });
            }
        }
    }
    let reply = match &result {
        Ok(v) if args.get("async").is_some() => format!("then= … {}", if v.is_null() { "waiting" } else { "" }).trim().to_string(),
        Ok(v) => reply_line(v),
        Err((kind, message)) => format!("{kind}: {message}"),
    };
    w.calls.push(CallRecord { op: op.to_string(), args: shorten(&args), reply, source, millis: started.elapsed().as_millis() as u64 });
    match result {
        Ok(v) => json!({"value": v}),
        Err((kind, message)) => json!({"error": {"kind": kind, "message": message}}),
    }
}

fn raisable(kind: &str) -> &'static str {
    const KINDS: &[&str] = &["PermissionError", "TimeoutError", "ConnectionError", "ValueError", "TypeError", "OSError", "RuntimeError", "NotImplementedError", "FileNotFoundError", "KeyError"];
    KINDS.iter().find(|k| **k == kind).copied().unwrap_or("RuntimeError")
}

/// A reply in one line for the Calls pane.
pub fn reply_line(v: &Json) -> String {
    if let Some(e) = v.get("error").filter(|e| e.is_object()) {
        return format!("{}: {}", e.get("kind").and_then(Json::as_str).unwrap_or("Error"), e.get("message").and_then(Json::as_str).unwrap_or(""));
    }
    if let (Some(status), Some(url)) = (v.get("status").and_then(Json::as_u64), v.get("url").and_then(Json::as_str)) {
        let size = v.get("text").and_then(Json::as_str).map_or(0, str::len) + v.get("content").and_then(Json::as_str).map_or(0, |c| c.len() * 3 / 4);
        return format!("{status} {url} ({size} bytes)");
    }
    crate::mock::summary(&shorten(v))
}

/// [v] with long strings cut, for showing.
pub fn shorten(v: &Json) -> Json {
    match v {
        Json::String(s) if s.chars().count() > 120 => Json::String(format!("{}… ({} characters)", s.chars().take(100).collect::<String>(), s.chars().count())),
        Json::Array(a) => Json::Array(a.iter().take(50).map(shorten).collect()),
        Json::Object(o) => Json::Object(o.iter().map(|(k, x)| (k.clone(), shorten(x))).collect()),
        other => other.clone(),
    }
}

fn s(args: &Json, k: &str) -> String {
    match args.get(k) {
        Some(Json::String(t)) => t.clone(),
        Some(Json::Null) | None => String::new(),
        Some(other) => other.to_string(),
    }
}

fn int(args: &Json, k: &str, default: i64) -> i64 {
    args.get(k).and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64))).unwrap_or(default)
}

fn cut(text: &str, max: usize) -> String {
    text.chars().take(max).collect()
}

fn now_seconds() -> f64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

fn ms(n: u64) -> Duration {
    Duration::from_millis(n)
}

fn run(w: &mut World, op: &str, args: &Json, token: Option<&str>) -> Outcome {
    use Outcome::*;
    match op {
        "http.request" => {
            // Refusals happen before anything is sent, then= or not.
            let req = match http::prepare(args, &w.facts, &w.reach) {
                Ok(r) => r,
                Err(e) => return Refused(e),
            };
            let now = Instant::now();
            while w.requests.front().is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60)) {
                w.requests.pop_front();
            }
            if w.requests.len() >= MAX_REQUESTS_PER_MINUTE {
                return Refused(("ConnectionError", format!("too many requests: at most {MAX_REQUESTS_PER_MINUTE} a minute")));
            }
            w.requests.push_back(now);
            match token {
                None => Now(http::send(&req)),
                Some(t) => {
                    let (tx, t) = (w.http_tx.clone(), t.to_string());
                    std::thread::spawn(move || {
                        let started = Instant::now();
                        let value = match http::send(&req) {
                            Ok(v) => v,
                            Err((kind, message)) => json!({"error": {"kind": kind, "message": message}}),
                        };
                        let _ = tx.send(HttpDone { token: t, value, millis: started.elapsed().as_millis() as u64 });
                    });
                    Started
                }
            }
        }
        "ui.toast" => {
            let text = cut(&s(args, "text"), MAX_TOAST);
            w.note(format!("[toast] {text}"));
            w.toasts.push((text, Instant::now() + TOAST_FOR));
            Now(ok(Json::Null))
        }
        "ui.dialog" => {
            let mut buttons: Vec<String> = args
                .get("buttons")
                .and_then(Json::as_array)
                .map(|b| b.iter().filter_map(Json::as_str).map(|t| cut(t.trim(), 40)).filter(|t| !t.is_empty()).take(MAX_DIALOG_BUTTONS).collect())
                .unwrap_or_default();
            if buttons.is_empty() {
                buttons.push("OK".into());
            }
            let text = args.get("text").and_then(Json::as_str).map(|t| cut(t, 2000));
            let title = cut(s(args, "title").trim(), 100);
            w.note(format!("[dialog] {title}"));
            w.dialogs.push_back(Dialog { token: token.map(String::from), title, text, buttons });
            if token.is_some() {
                Started
            } else {
                Now(ok(Json::Null))
            }
        }
        "field.insert" | "field.replace" => {
            w.field.insert(&s(args, "text"));
            let shown = w.field.shown();
            w.note(format!("[field] {}: {shown}", if op == "field.insert" { "insert" } else { "replace" }));
            Now(ok(Json::Null))
        }
        "field.delete" => {
            let before = int(args, "before", 1).clamp(0, MAX_DELETE) as usize;
            let after = int(args, "after", 0).clamp(0, MAX_DELETE) as usize;
            w.field.delete(before, after);
            let shown = w.field.shown();
            w.note(format!("[field] delete: {shown}"));
            Now(ok(Json::Null))
        }
        "field.move" => {
            w.field.move_by(int(args, "by", 0));
            let shown = w.field.shown();
            w.note(format!("[field] move: {shown}"));
            Now(ok(Json::Null))
        }
        "field.action" => {
            w.note("[field] the editor's action key (send, search, go)");
            Now(ok(Json::Null))
        }
        "field.selection" => Now(ok(Json::String(w.field.selected()))),
        "field.before" => Now(ok(Json::String(w.field.before(int(args, "n", 1000).clamp(0, MAX_READ) as usize)))),
        "field.after" => Now(ok(Json::String(w.field.after(int(args, "n", 1000).clamp(0, MAX_READ) as usize)))),
        "field.info" => Now(ok(json!({"kind": "text", "app": "wmpy", "hint": "", "multiline": true, "action": "none"}))),
        "field.image" => {
            w.note(format!("[field] a picture ({})", s(args, "mime")));
            Now(ok(Json::Null))
        }
        "clipboard.get" => Now(ok(Json::String(w.clipboard.clone()))),
        "clipboard.set" => {
            w.clipboard = cut(&s(args, "text"), MAX_CLIP);
            w.note(format!("[clipboard] {}", cut(&w.clipboard.clone(), 80)));
            Now(ok(Json::Null))
        }
        "device.vibrate" => {
            w.note(format!("[vibrate] {} ms", int(args, "ms", 30).clamp(1, 1000)));
            Now(ok(Json::Null))
        }
        "device.speak" => {
            w.note(format!("[speak] {}", cut(&s(args, "text"), 200)));
            Now(ok(Json::Null))
        }
        "device.battery" => Now(ok(json!({"level": 76, "charging": false}))),
        "device.flashlight" => {
            let on = args.get("on").is_some_and(|v| v.as_bool() == Some(true) || v.as_str() == Some("true"));
            w.note(format!("[flashlight] {}", if on { "on" } else { "off" }));
            Now(ok(Json::Null))
        }
        "share.text" => {
            w.note(format!("[share] {}", cut(&s(args, "text"), 200)));
            Now(ok(Json::Null))
        }
        "share.open" => {
            let url = s(args, "url");
            if !url.starts_with("https://") {
                return Now(err("ValueError", "only https links can be opened"));
            }
            w.note(format!("[share] would open {url} in the browser the user picks"));
            Now(ok(Json::Null))
        }
        "share.visit" => {
            let url = s(args, "url");
            if !(url.starts_with("https://") || url.starts_with("http://")) || url.contains('@') {
                return Now(err("ValueError", "only http and https links can be opened"));
            }
            w.note(format!("[share] would open {url}"));
            Now(ok(Json::Null))
        }
        "notify.show" => {
            let title = s(args, "title");
            w.note(format!("[notification] {}{}", if title.is_empty() { String::new() } else { format!("{title}: ") }, cut(&s(args, "text"), 1000)));
            Now(ok(Json::Null))
        }
        "notify.cancel" | "plugin.refresh" => Now(ok(Json::Null)),
        "plugin.close" => {
            w.closed = true;
            w.note("[closed the panel]");
            Now(ok(Json::Null))
        }
        "plugin.open_settings" => {
            w.note("[opened the plugin's settings page; wmpy reads settings from plugin.json, .wmpy/settings.json and --set]");
            Now(ok(Json::Null))
        }
        "keyboard.use" => {
            let tag = s(args, "language").trim().to_string();
            if tag.is_empty() {
                return Now(err("ValueError", "keyboard.use_language needs a language tag, such as \"ja\""));
            }
            w.note(format!("[keyboard] {tag} keys while the panel is open"));
            Now(ok(Json::String(tag)))
        }
        "keyboard.restore" => {
            w.note("[keyboard] back to your own layout");
            Now(ok(Json::Null))
        }
        "clock.alarm" => {
            let Some(at) = args.get("at").and_then(Json::as_f64) else {
                return Now(err("ValueError", "at must be a number of seconds"));
            };
            let now = now_seconds();
            if at < now + 60.0 {
                return Now(err("ValueError", "an alarm must be at least a minute away"));
            }
            if at > now + 86400.0 {
                return Now(err("ValueError", "an alarm rings within the next 24 hours; use reminders for later"));
            }
            w.note(format!("[clock] alarm in {} minutes{} (the phone adds it to its Clock)", ((at - now) / 60.0).round(), label(args)));
            Now(ok(Json::Null))
        }
        "clock.timer" => {
            let seconds = args.get("seconds").and_then(Json::as_f64).unwrap_or(f64::NAN);
            if !(1.0..=86400.0).contains(&seconds) {
                return Now(err("ValueError", "a timer runs from 1 second to 24 hours"));
            }
            w.note(format!("[clock] {} s timer{} (the phone starts it in its Clock)", seconds.round(), label(args)));
            Now(ok(Json::Null))
        }
        "time.zone" => {
            let zone = s(args, "zone");
            match zone.as_str() {
                "" | "UTC" | "Etc/UTC" | "GMT" => Now(ok(json!({"zone": zone, "rows": [[-62_135_596_800i64, 0]]}))),
                _ => Now(err("NotImplementedError", format!("wmpy has no time zone data for {}; answer time.zone in a --mock file", cut(&zone, 64)))),
            }
        }
        "sensors.list" => Now(ok(json!(pretend::SENSORS))),
        "sensors.subscribe" => {
            let Some(t) = token else { return Now(err("ValueError", "sensors.subscribe needs a callback")) };
            let kind = s(args, "kind");
            if !pretend::SENSORS.contains(&kind.as_str()) {
                return Now(err("ValueError", format!("no \"{kind}\" sensor; try {}", pretend::SENSORS.join(", "))));
            }
            if w.sensors.len() >= MAX_SUBSCRIPTIONS {
                return Now(err("RuntimeError", format!("at most {MAX_SUBSCRIPTIONS} sensors at once")));
            }
            let hz = args.get("hz").and_then(Json::as_f64).unwrap_or(5.0).clamp(1.0, 30.0);
            let period = Duration::from_secs_f64(1.0 / hz);
            w.note(format!("[sensors] pretend {kind} readings, {} a second", hz.round()));
            let now = Instant::now();
            w.sensors.push(Sensor { token: t.to_string(), kind, period, next: now + period, started: now, n: 0 });
            Started
        }
        "sensors.unsubscribe" => {
            let t = s(args, "token");
            let before = w.sensors.len();
            w.sensors.retain(|x| x.token != t);
            if w.sensors.len() < before {
                // The subscription's own call ends here.
                w.later(&t, Json::Null, Duration::ZERO);
            }
            Now(ok(Json::Null))
        }
        "ai.available" => Now(ok(Json::Bool(true))),
        "ai.ask" | "ai.stream" => {
            let text = pretend::ai_reply(&s(args, "prompt"));
            w.note("[ai] pretend reply (wmpy has no AI model; answer ai.ask in a --mock file)");
            let streaming = args.get("stream").is_some_and(|v| v.as_bool() == Some(true));
            match token {
                Some(t) if op == "ai.stream" || streaming => {
                    let words: Vec<String> = text.split_inclusive(' ').map(String::from).collect();
                    for (i, word) in words.iter().enumerate() {
                        w.piece(t, Json::String(word.clone()), ms(120 + i as u64 * 45));
                    }
                    w.later(t, json!({"text": text}), ms(160 + words.len() as u64 * 45));
                    Started
                }
                _ => After(ms(600), ok(json!({"text": text}))),
            }
        }
        "location.get" => {
            let fine = s(args, "accuracy") == "fine";
            w.note(format!("[location] pretend place: {}, {}", pretend::PLACE.0, pretend::PLACE.1));
            After(ms(400), ok(json!({"lat": pretend::PLACE.0, "lon": pretend::PLACE.1, "accuracy": if fine { 15 } else { 1500 }, "time": now_seconds()})))
        }
        "calendar.events" => {
            let start = args.get("start").and_then(Json::as_f64).unwrap_or(0.0);
            let end = args.get("end").and_then(Json::as_f64).unwrap_or(0.0);
            if end - start > 62.0 * 86400.0 {
                return Now(err("ValueError", "ask for at most 62 days of events at a time"));
            }
            let events = pretend::events(start, end);
            w.note(format!("[calendar] {} pretend events", events.len()));
            After(ms(200), ok(Json::Array(events)))
        }
        "calendar.calendars" => Now(ok(json!([{"id": 1, "name": "Personal", "account": "you@example.com", "primary": true}]))),
        "calendar.add" => {
            w.note(format!("[calendar] would add “{}” to the phone's calendar", s(args, "title")));
            let id = w.next_event;
            w.next_event += 1;
            Now(ok(json!(id)))
        }
        "todo.list" => {
            let with_done = args.get("done").and_then(Json::as_bool) != Some(false);
            Now(ok(Json::Array(pretend::todo_tree(&w.tasks, with_done))))
        }
        "todo.add" => {
            let text = s(args, "text").trim().to_string();
            if text.is_empty() {
                return Now(err("ValueError", "a task needs text"));
            }
            let parent_id = args.get("parent").and_then(Json::as_i64);
            let parent = match parent_id {
                None => None,
                Some(p) => match w.tasks.iter().find(|t| t.id == p) {
                    Some(t) => Some(t.parent.unwrap_or(t.id)),
                    None => return Now(err("ValueError", format!("no task {p}"))),
                },
            };
            let task = Task { id: w.next_task, text: text.clone(), done: false, due: args.get("due").and_then(Json::as_f64), all_day: args.get("all_day").and_then(Json::as_bool) == Some(true), parent };
            w.next_task += 1;
            w.note(format!("[todo] added “{text}” to the pretend list"));
            let shape = json!({"id": task.id, "text": task.text, "done": false, "due": task.due, "all_day": task.all_day, "steps": []});
            w.tasks.push(task);
            Now(ok(shape))
        }
        "todo.update" => {
            let id = args.get("id").and_then(Json::as_i64).unwrap_or(-1);
            let Some(i) = w.tasks.iter().position(|t| t.id == id) else { return Now(ok(Json::Null)) };
            if let Some(t) = args.get("text").and_then(Json::as_str).map(str::trim).filter(|t| !t.is_empty()) {
                w.tasks[i].text = t.to_string();
            }
            if let Some(done) = args.get("done").and_then(Json::as_bool) {
                w.tasks[i].done = done;
                if w.tasks[i].parent.is_none() && done {
                    for c in w.tasks.iter_mut().filter(|c| c.parent == Some(id)) {
                        c.done = true;
                    }
                }
            }
            if args.get("due").is_some() {
                w.tasks[i].due = args.get("due").and_then(Json::as_f64);
                w.tasks[i].all_day = args.get("all_day").and_then(Json::as_bool) == Some(true);
            }
            let tree = pretend::todo_tree(&w.tasks, true);
            Now(ok(tree.into_iter().find(|t| t["id"] == json!(id)).unwrap_or(Json::Null)))
        }
        "todo.remove" => {
            let id = args.get("id").and_then(Json::as_i64).unwrap_or(-1);
            let before = w.tasks.len();
            w.tasks.retain(|t| t.id != id && t.parent != Some(id));
            Now(ok(Json::Bool(w.tasks.len() < before)))
        }
        "files.pick" => match pretend::file(&s(args, "kind")) {
            None => After(Duration::ZERO, err("NotImplementedError", "wmpy has no pretend video; answer files.pick in a --mock file")),
            Some(f) => {
                w.note(format!("[files] the user \"picked\" {}", f["name"].as_str().unwrap_or("")));
                After(ms(500), ok(f))
            }
        },
        "files.photo" => {
            w.note("[files] pretend photo from the camera");
            After(ms(700), ok(pretend::photo()))
        }
        "files.save" => {
            let size = args.get("data").and_then(Json::as_str).map_or(0, |d| d.len() * 3 / 4);
            w.note(format!("[files] would save {} ({size} bytes); the user picks where on the phone", s(args, "name")));
            After(ms(300), ok(Json::Null))
        }
        "scan.qr" => {
            w.note("[scan] pretend QR code");
            After(ms(700), ok(json!("https://wmkeyboard.app")))
        }
        "scan.text" => {
            w.note("[scan] pretend text from the camera");
            After(ms(900), ok(json!("Open 9 am to 6 pm, closed on Fridays")))
        }
        "secrets.get" => Now(ok(w.secrets.get(&s(args, "name")).map(Json::String).unwrap_or(Json::Null))),
        "secrets.set" => Now(w.secrets.set(&s(args, "name"), &s(args, "value")).map(|_| Json::Null).map_err(|m| ("ValueError", m))),
        "secrets.remove" => {
            w.secrets.remove(&s(args, "name"));
            Now(ok(Json::Null))
        }
        "settings.set" => Now(set_setting(w, args)),
        _ => Now(err("NotImplementedError", format!("“{op}” is not available in wmpy; answer it in a --mock file"))),
    }
}

fn label(args: &Json) -> String {
    let l = s(args, "label");
    if l.trim().is_empty() {
        String::new()
    } else {
        format!(": {}", cut(l.trim(), 100))
    }
}

/// PluginHost.setSetting: the plugin writes one of its own rows.
fn set_setting(w: &mut World, args: &Json) -> Result<Json, Failure> {
    let key = s(args, "key");
    let Some(spec) = w.facts.spec(&key).cloned() else {
        return err("ValueError", format!("the manifest declares no setting '{key}'"));
    };
    if spec.kind == "host" {
        return err("PermissionError", "a plugin can't change its own host settings: only the user types a server address");
    }
    let value = args.get("value").cloned().unwrap_or(Json::Null);
    let text = if value.is_null() {
        None
    } else {
        match stored_text(&spec, &value) {
            Some(t) => Some(t),
            None => {
                let why = match spec.kind.as_str() {
                    "switch" => format!("setting '{key}' is a switch: give True or False"),
                    "number" => format!("setting '{key}' is a number"),
                    "choice" => format!("setting '{key}' is one of {}", spec.options.iter().map(|o| format!("'{o}'")).collect::<Vec<_>>().join(", ")),
                    _ => format!("setting '{key}' takes text"),
                };
                return err("ValueError", why);
            }
        }
    };
    if spec.kind == "secret" {
        match &text {
            Some(t) if t.encode_utf16().count() > MAX_SECRET => return err("ValueError", format!("a secret can't be longer than {MAX_SECRET} characters")),
            Some(t) => w.secrets.set(&key, t).map_err(|m| ("ValueError", m))?,
            None => w.secrets.remove(&key),
        }
        w.note(format!("[settings] {key} = {}", if text.is_some() { "(saved)" } else { "(removed)" }));
        return ok(typed(&spec, ""));
    }
    if text.as_ref().is_some_and(|t| t.encode_utf16().count() > MAX_SETTING_TEXT) {
        return err("ValueError", format!("a setting can't be longer than {MAX_SETTING_TEXT} characters"));
    }
    w.note(format!("[settings] {key} = {}", text.clone().unwrap_or_else(|| format!("{} (default)", spec.default))));
    w.settings.set(&key, text.clone());
    ok(typed(&spec, &text.unwrap_or(spec.default.clone())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pretend_field_edits_like_an_input_connection() {
        let mut f = Field::default();
        f.insert("hello world");
        f.move_by(-6);
        f.insert(",");
        assert_eq!(f.string(), "hello, world");
        assert_eq!(f.before(3), "lo,");
        assert_eq!(f.after(3), " wo");
        f.delete(1, 1);
        assert_eq!(f.string(), "helloworld");
        f.set("abc", 1, Some((1, 3)));
        assert_eq!(f.selected(), "bc");
        f.insert("X");
        assert_eq!((f.string().as_str(), f.cursor), ("aX", 2));
    }
}
