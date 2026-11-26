//! The development host behind `wmpy run` and `wmpy preview`: one plugin
//! panel, run on a computer the way the keyboard runs it.
//!
//! * The session is wm-python's own [Session] with the app's step and time
//!   budgets (9M steps and 3 s to load, 6M and 2 s an event, 1.2M and
//!   0.5 s to draw; time waiting on the host does not count).
//! * Host calls go to [bridge]: https requests for real to the manifest's
//!   `hosts`, with the app's limits (60 a minute, 15 s, 1 MB); a pretend
//!   text field and clipboard; pretend answers for what only a phone has;
//!   and a `--mock` file in front of all of them.
//! * Storage, reminders, secrets and the settings the plugin wrote live in
//!   `<plugin>/.wmpy/` (storage.json and reminders.json in the app's own
//!   formats, which the session reads and writes; secrets.json and
//!   settings.json as plain JSON objects), so they outlast a reload.
//! * Timers (`@plugin.every`, `plugin.after`) and `then=` answers arrive as
//!   events from [DevHost::tick]; the folder is polled every 300 ms and a
//!   change reloads the plugin.
//!
//! A front end owns one [DevHost] on one thread and calls [DevHost::tick]
//! whenever [DevHost::next_wake] says, or more often.
//!
//! The measuring commands (`wmpy budget`, `profile`, `mock record`) open it
//! with an [Extra]: state in a copy, every session call kept in
//! [DevHost::ran], and an observer on every host call.

pub mod bridge;
pub mod http;
pub mod plugin;
pub mod pretend;

use std::cell::RefCell;
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::mpsc::{channel, Receiver};
use std::time::{Duration, Instant, SystemTime};

use serde_json::{json, Map, Value as Json};
use wm_python::host::Session;

use crate::diag::{folder_source, Diag};
use crate::mock::Mock;
use bridge::{CallRecord, HttpDone, World};
use plugin::{PluginFacts, Secrets, SettingValues};

/// How often the folder is checked for changes.
pub const WATCH_EVERY: Duration = Duration::from_millis(300);
/// PluginRuntime: the shortest repeating timer and the shortest one-off.
const MIN_REPEAT: Duration = Duration::from_millis(1000);
const MIN_ONCE: Duration = Duration::from_millis(50);
const MAX_CONSOLE: usize = 500;
const MAX_CALLS: usize = 300;
const MAX_SENT: usize = 50;

#[derive(Clone, Debug, Default)]
pub struct Options {
    pub dir: PathBuf,
    pub mock: Option<PathBuf>,
    /// `--set key=value`, in order.
    pub sets: Vec<(String, String)>,
    pub dark: bool,
    pub watch: bool,
}

/// What `wmpy budget`, `wmpy profile` and `wmpy mock record` add to a run;
/// `wmpy run` and `wmpy preview` open with none of it.
#[derive(Clone, Default)]
pub struct Extra {
    /// Where storage, reminders, secrets and settings live, instead of
    /// `<plugin>/.wmpy/`: a copy, so a measured run leaves the real ones alone.
    pub state: Option<PathBuf>,
    /// Keep every session call in [DevHost::ran].
    pub record: bool,
    /// Hears every host call, in every world the host makes.
    pub observer: Option<bridge::Observer>,
}

/// One session call, kept when [Extra::record] asks.
#[derive(Clone, Debug)]
pub struct Ran {
    /// The event, or `{"type": "load"}` for a load and its first draw.
    pub event: Json,
    /// What the session answered, with its `phases`.
    pub result: Json,
    /// The error wmpy made of it, with the budget's help, when it failed.
    pub error: Option<Diag>,
    pub started: Instant,
    pub ended: Instant,
}

/// `key=value` for `--set`.
pub fn parse_set(text: &str) -> Result<(String, String), String> {
    match text.split_once('=') {
        Some((k, v)) if !k.trim().is_empty() => Ok((k.trim().to_string(), v.to_string())),
        _ => Err(format!("'{text}' is not key=value")),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// print() and wm.log.
    Print,
    Error,
    /// What the host did: a toast, a field edit, a pretend answer.
    Note,
    /// The codec dropped or shortened part of the tree.
    Repair,
    /// The plugin was loaded again.
    Reload,
    /// An event the person sent from the Events pane.
    Sent,
}

impl Kind {
    pub fn word(self) -> &'static str {
        match self {
            Kind::Print => "print",
            Kind::Error => "error",
            Kind::Note => "note",
            Kind::Repair => "repair",
            Kind::Reload => "reload",
            Kind::Sent => "sent",
        }
    }
}

#[derive(Clone, Debug)]
pub struct ConsoleEntry {
    pub kind: Kind,
    pub text: String,
    pub diag: Option<Diag>,
    /// Milliseconds since wmpy started.
    pub at: u64,
}

struct Timer {
    seconds: f64,
    repeat: bool,
    due: Instant,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Ready,
    Failed,
    /// `"panel": false`: nothing to draw.
    NoPanel,
}

pub struct DevHost {
    pub opts: Options,
    pub facts: PluginFacts,
    pub world: Rc<RefCell<World>>,
    session: Option<Session>,
    /// The session loaded far enough to take events.
    pub loaded: bool,
    pub status: Status,
    /// The last tree: `{"root": [...], "repairs": [...]}`.
    pub ui: Json,
    /// What the last API 5 draw reported: title, commands, back, timers.
    pub meta: Json,
    /// `wm.ui.set_input` writes, by input id.
    pub inputs: Map<String, Json>,
    pub console: VecDeque<ConsoleEntry>,
    pub calls: VecDeque<CallRecord>,
    pub sent: VecDeque<Json>,
    /// The last call's error, if it failed.
    pub error: Option<Diag>,
    /// The last call's phases, as the session reports them.
    pub usage: Vec<Json>,
    pub storage: Json,
    timers: HashMap<String, Timer>,
    http_rx: Receiver<HttpDone>,
    signature: Vec<(String, u128, u64)>,
    last_scan: Instant,
    started: Instant,
    last_repairs: Vec<Json>,
    version: u64,
    /// When each toast should go: kept here so expiry bumps the version.
    toast_check: Option<Instant>,
    /// Storage, reminders, secrets and settings: `<plugin>/.wmpy/` unless [Extra::state].
    state: PathBuf,
    observer: Option<bridge::Observer>,
    /// Every session call, when [Extra::record] asked for them.
    pub ran: Option<Vec<Ran>>,
}

impl DevHost {
    /// Read the plugin in [opts.dir] and load it. Err when wmpy cannot even
    /// start (no plugin.json, an unreadable mock file); a plugin that fails to
    /// load still opens, with the error shown.
    pub fn open(opts: Options) -> Result<DevHost, String> {
        DevHost::open_with(opts, Extra::default())
    }

    /// [open], with what a measuring command adds.
    pub fn open_with(opts: Options, extra: Extra) -> Result<DevHost, String> {
        let facts = PluginFacts::read(&opts.dir)?;
        let state = extra.state.clone().unwrap_or_else(|| opts.dir.join(".wmpy"));
        let (world, http_rx) = make_world(&opts, &facts, &state, extra.observer.clone())?;
        let mut host = DevHost {
            opts,
            facts,
            world,
            session: None,
            loaded: false,
            status: Status::Ready,
            ui: json!({"root": [], "repairs": []}),
            meta: Json::Null,
            inputs: Map::new(),
            console: VecDeque::new(),
            calls: VecDeque::new(),
            sent: VecDeque::new(),
            error: None,
            usage: Vec::new(),
            storage: json!({}),
            timers: HashMap::new(),
            http_rx,
            signature: Vec::new(),
            last_scan: Instant::now(),
            started: Instant::now(),
            last_repairs: Vec::new(),
            version: 1,
            toast_check: None,
            state,
            observer: extra.observer,
            ran: extra.record.then(Vec::new),
        };
        host.signature = signature(&host.opts.dir);
        host.start();
        Ok(host)
    }

    pub fn dir(&self) -> &Path {
        &self.opts.dir
    }

    /// Where storage, reminders, secrets and settings live.
    pub fn state_dir(&self) -> &Path {
        &self.state
    }

    /// Bumped on every change a front end should draw.
    pub fn version(&self) -> u64 {
        self.version
    }

    fn at(&self) -> u64 {
        self.started.elapsed().as_millis() as u64
    }

    pub fn log(&mut self, kind: Kind, text: impl Into<String>) {
        let at = self.at();
        self.console.push_back(ConsoleEntry { kind, text: text.into(), diag: None, at });
        while self.console.len() > MAX_CONSOLE {
            self.console.pop_front();
        }
        self.version += 1;
    }

    fn log_diag(&mut self, d: Diag) {
        let at = self.at();
        let text = d.render(&folder_source(&self.opts.dir), false);
        self.console.push_back(ConsoleEntry { kind: Kind::Error, text: text.trim_end().to_string(), diag: Some(d.clone()), at });
        while self.console.len() > MAX_CONSOLE {
            self.console.pop_front();
        }
        self.error = Some(d);
        self.version += 1;
    }

    pub fn clear_console(&mut self) {
        self.console.clear();
        self.version += 1;
    }

    /// A new session on the plugin's current files.
    fn start(&mut self) {
        self.loaded = false;
        self.error = None;
        self.timers.clear();
        if !self.facts.panel {
            self.status = Status::NoPanel;
            self.ui = json!({"root": [], "repairs": []});
            self.meta = Json::Null;
            self.log(Kind::Note, "This plugin has no panel (\"panel\": false in plugin.json). Its selection actions, previews and providers run in `wmpy test`.");
            self.refresh_storage();
            return;
        }
        let dir = self.opts.dir.clone();
        let entry = match crate::project::manifest(&dir) {
            Ok(m) => m.entry,
            Err(e) => return self.fail(Diag::error(e)),
        };
        let source = match crate::project::inside(&dir, &entry).and_then(|p| std::fs::read_to_string(p).map_err(|e| e.to_string())) {
            Ok(s) => s,
            Err(e) => {
                let mut d = Diag::error(format!("cannot read the entry script: {e}"));
                d.file = Some(entry.clone());
                return self.fail(d);
            }
        };
        let m = match crate::project::manifest(&dir) {
            Ok(m) => m,
            Err(e) => return self.fail(Diag::error(e)),
        };
        let settings = self.world.borrow().settings.json(&self.facts);
        let mut config = crate::project::session_config(&dir, &m, settings);
        let state = self.state.clone();
        config["storagePath"] = Json::String(state.join("storage.json").to_string_lossy().into_owned());
        config["remindersPath"] = Json::String(state.join("reminders.json").to_string_lossy().into_owned());
        config["context"]["theme"] = Json::String(if self.opts.dark { "dark" } else { "light" }.into());
        let mut session = match Session::new(&config.to_string()) {
            Ok(s) => s,
            Err(e) => return self.fail(Diag::error(e)),
        };
        session.set_bridge(Some(bridge::bridge(self.world.clone())));
        let started = Instant::now();
        let result = session.load(&source);
        let ended = Instant::now();
        self.session = Some(session);
        let ok = self.absorb(&result);
        self.keep(json!({"type": "load"}), &result, ok, started, ended);
        let parsed: Json = serde_json::from_str(&result).unwrap_or(Json::Null);
        // A failed draw after a good load still takes events, as in the app.
        let drew = parsed["phases"].as_array().is_some_and(|p| p.iter().any(|x| x["phase"] == "render"));
        self.loaded = ok || drew || parsed.pointer("/error/kind") == Some(&json!("no_render"));
        self.status = if ok { Status::Ready } else { Status::Failed };
    }

    fn fail(&mut self, d: Diag) {
        self.status = Status::Failed;
        self.log_diag(d);
        self.refresh_storage();
    }

    /// Load the plugin again from its files, keeping what it stored. [why]
    /// goes in the console.
    pub fn reload(&mut self, why: &str) {
        if self.loaded && self.facts.api >= 5 {
            // @plugin.on_close hooks, as when the panel closes.
            if let Some(s) = self.session.as_mut() {
                let r = s.event(r#"{"type":"close"}"#);
                self.absorb_quietly(&r);
            }
        }
        self.session = None;
        match PluginFacts::read(&self.opts.dir) {
            Ok(f) => self.facts = f,
            Err(e) => {
                self.log(Kind::Reload, format!("reloaded ({why})"));
                return self.fail(Diag::error(e));
            }
        }
        // Answers still on their way belonged to the old session.
        match make_world(&self.opts, &self.facts, &self.state, self.observer.clone()) {
            Ok((world, rx)) => {
                let keep = self.world.borrow();
                let (field, clipboard) = (keep.field.clone(), keep.clipboard.clone());
                drop(keep);
                {
                    let mut w = world.borrow_mut();
                    w.field = field;
                    w.clipboard = clipboard;
                }
                self.world = world;
                self.http_rx = rx;
            }
            Err(e) => {
                self.log(Kind::Reload, format!("reloaded ({why})"));
                return self.fail(Diag::error(e));
            }
        }
        self.inputs.clear();
        self.last_repairs.clear();
        self.log(Kind::Reload, format!("reloaded ({why})"));
        self.start();
    }

    pub fn set_dark(&mut self, dark: bool) {
        if self.opts.dark != dark {
            self.opts.dark = dark;
            self.reload(if dark { "dark theme" } else { "light theme" });
        }
    }

    /// Hand one event to the plugin, as a tap or a typed value would. False
    /// when the call failed.
    pub fn send(&mut self, event: Json) -> bool {
        if !event.get("type").is_some_and(Json::is_string) {
            self.log(Kind::Error, "an event is a JSON object with a \"type\", such as {\"type\": \"click\", \"id\": \"button:Save\"}");
            return false;
        }
        self.sent.push_back(event.clone());
        while self.sent.len() > MAX_SENT {
            self.sent.pop_front();
        }
        self.deliver(&event)
    }

    fn deliver(&mut self, event: &Json) -> bool {
        if !self.loaded {
            return false;
        }
        let Some(session) = self.session.as_mut() else { return false };
        let started = Instant::now();
        let result = session.event(&event.to_string());
        let ended = Instant::now();
        let ok = self.absorb(&result);
        self.keep(event.clone(), &result, ok, started, ended);
        if ok && self.status == Status::Failed {
            self.status = Status::Ready;
        }
        ok
    }

    /// One session call into [DevHost::ran], when recording.
    fn keep(&mut self, event: Json, result: &str, ok: bool, started: Instant, ended: Instant) {
        if self.ran.is_none() {
            return;
        }
        let result: Json = serde_json::from_str(result).unwrap_or(Json::Null);
        let error = if ok { None } else { self.error.clone() };
        if let Some(ran) = self.ran.as_mut() {
            ran.push(Ran { event, result, error, started, ended });
        }
    }

    /// The output widget's Insert: text into the pretend field.
    pub fn insert(&mut self, text: &str) {
        let shown = {
            let mut w = self.world.borrow_mut();
            w.field.insert(text);
            w.field.shown()
        };
        self.log(Kind::Note, format!("[field] insert: {shown}"));
    }

    /// The output widget's Copy: text onto the pretend clipboard.
    pub fn copy(&mut self, text: &str) {
        self.world.borrow_mut().clipboard = text.to_string();
        self.log(Kind::Note, format!("[clipboard] {}", text.chars().take(80).collect::<String>()));
    }

    pub fn set_field(&mut self, text: &str, cursor: usize, selection: Option<(usize, usize)>) {
        self.world.borrow_mut().field.set(text, cursor, selection);
        self.version += 1;
    }

    pub fn set_clipboard(&mut self, text: &str) {
        self.world.borrow_mut().clipboard = text.to_string();
        self.version += 1;
    }

    /// The front dialog's answer: a button index, or None when dismissed.
    pub fn answer_dialog(&mut self, index: Option<usize>) {
        self.world.borrow_mut().answer_dialog(index);
        self.drain_world();
        self.tick();
    }

    /// The dialog the plugin is waiting on, if any.
    pub fn dialog(&self) -> Option<bridge::Dialog> {
        self.world.borrow().dialogs.front().cloned()
    }

    /// Toasts still showing.
    pub fn toasts(&self) -> Vec<String> {
        let now = Instant::now();
        self.world.borrow().toasts.iter().filter(|(_, until)| *until > now).map(|(t, _)| t.clone()).collect()
    }

    /// Delete one stored key, as the app's storage inspector does.
    pub fn storage_remove(&mut self, key: &str) {
        if let Some(s) = self.session.as_mut() {
            s.storage_remove(key);
        }
        self.refresh_storage();
        self.version += 1;
    }

    pub fn storage_set(&mut self, key: &str, value: &str) -> Result<(), String> {
        let r = match self.session.as_mut() {
            Some(s) => s.storage_set(key, value),
            None => Err("the plugin is not running".into()),
        };
        self.refresh_storage();
        self.version += 1;
        r
    }

    fn refresh_storage(&mut self) {
        self.storage = match &self.session {
            Some(s) => serde_json::from_str(&s.storage_json()).unwrap_or(json!({})),
            None => {
                let path = self.state.join("storage.json");
                std::fs::read_to_string(path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(json!({}))
            }
        };
    }

    /// Everything the result of one session call says: log lines, the tree,
    /// timers, the error. True when the call succeeded.
    fn absorb(&mut self, result: &str) -> bool {
        let r: Json = serde_json::from_str(result).unwrap_or_else(|_| json!({"ok": false, "error": {"kind": "internal", "message": "the session sent a result wmpy could not read"}}));
        for line in r["log"].as_array().into_iter().flatten() {
            if let Some(t) = line.as_str() {
                self.log(Kind::Print, t);
            }
        }
        self.drain_world();
        if let Some(inputs) = r["inputs"].as_array() {
            for pair in inputs {
                if let (Some(id), Some(text)) = (pair[0].as_str(), pair[1].as_str()) {
                    self.inputs.insert(id.to_string(), Json::String(text.to_string()));
                }
            }
        }
        if let Some(ui) = r.get("ui").filter(|u| u.is_object()) {
            self.ui = ui.clone();
            let repairs = ui["repairs"].as_array().cloned().unwrap_or_default();
            if repairs != self.last_repairs {
                for rep in &repairs {
                    let code = rep["code"].as_str().unwrap_or("");
                    let arg = rep["arg"].as_str().map(|a| format!(" ({a})")).unwrap_or_default();
                    self.log(Kind::Repair, format!("the keyboard fixed the tree: {}{arg}", repair_words(code)));
                }
                self.last_repairs = repairs;
            }
        }
        if let Some(host) = r.get("host").filter(|h| h.is_object()) {
            self.meta = host.clone();
            self.sync_timers();
        }
        for need in r["needs"].as_array().into_iter().flatten() {
            self.log(Kind::Note, format!("[permission] the plugin needs '{}'", need.as_str().unwrap_or("")));
        }
        if let Some(phases) = r["phases"].as_array() {
            if !phases.is_empty() {
                self.usage = phases.clone();
            }
        }
        self.refresh_storage();
        self.version += 1;
        let ok = r["ok"].as_bool().unwrap_or(false);
        if ok {
            self.error = None;
        } else if let Some(e) = r.get("error") {
            let d = self.diag_of(e, &r);
            self.log_diag(d);
        }
        ok
    }

    /// A close event's result: its log only.
    fn absorb_quietly(&mut self, result: &str) {
        let r: Json = serde_json::from_str(result).unwrap_or(Json::Null);
        for line in r["log"].as_array().into_iter().flatten() {
            if let Some(t) = line.as_str() {
                self.log(Kind::Print, t);
            }
        }
        self.drain_world();
    }

    fn diag_of(&self, e: &Json, result: &Json) -> Diag {
        let entry = crate::project::manifest(&self.opts.dir).map(|m| m.entry).unwrap_or_else(|_| "main.py".into());
        let mut d = self.diag_of_kind(e, result, &entry);
        d.name_entry(&entry, &self.opts.dir);
        d
    }

    fn diag_of_kind(&self, e: &Json, result: &Json, entry: &str) -> Diag {
        let kind = e["kind"].as_str().unwrap_or("");
        let phase = result["phases"].as_array().and_then(|p| p.last()).and_then(|p| p["phase"].as_str()).unwrap_or("").to_string();
        match kind {
            "no_render" => {
                let mut d = Diag::error("the plugin has no render() function, so its panel has nothing to draw");
                d.code = Some("no_render".into());
                d.help = Some("define render(), or set \"panel\": false in plugin.json for a plugin of selection actions only".into());
                d
            }
            "internal" => {
                let mut d = Diag::error(e["message"].as_str().unwrap_or("the session failed"));
                d.code = Some("internal".into());
                d
            }
            "abort" => {
                let mut d = Diag::from_session_error(e, entry);
                let abort = e["abort"].as_str().unwrap_or("");
                d.code = Some(abort.to_string());
                let (steps, millis) = match phase.as_str() {
                    "load" => ("9 million", "3 seconds"),
                    "event" => ("6 million", "2 seconds"),
                    _ => ("1.2 million", "half a second"),
                };
                d.help = match abort {
                    "steps" => Some(format!("too much work: the {phase} step budget is {steps} steps (about one line or one turn of a loop each). Look for a loop that never ends, or work that belongs in a smaller piece.")),
                    "deadline" => Some(format!("too slow: the {phase} part of a run has {millis}. Time spent waiting for http or ai does not count.")),
                    "recursion" => Some("too deep: a function called itself too many times".into()),
                    "container" | "string" => Some("too big: a list, dict or string grew past the keyboard's limit".into()),
                    _ => None,
                };
                if matches!(abort, "steps" | "deadline") {
                    d.notes.push("the keyboard switches off a plugin that is stopped twice, until the user turns it on again".into());
                }
                d
            }
            _ => Diag::from_session_error(e, entry),
        }
    }

    /// Notes and calls the bridge collected, into the console and Calls pane.
    fn drain_world(&mut self) {
        let (notes, calls) = {
            let mut w = self.world.borrow_mut();
            (std::mem::take(&mut w.notes), std::mem::take(&mut w.calls))
        };
        for n in notes {
            self.log(Kind::Note, n);
        }
        if !calls.is_empty() {
            self.calls.extend(calls);
            while self.calls.len() > MAX_CALLS {
                self.calls.pop_front();
            }
            self.version += 1;
        }
    }

    /// Runs the plugin's timers the way PluginRuntime does: only those the
    /// last draw still lists, each from when it was first seen.
    fn sync_timers(&mut self) {
        let wanted: Vec<(String, f64, bool)> = self.meta["timers"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|t| Some((t["id"].as_str()?.to_string(), t["seconds"].as_f64().unwrap_or(1.0), t["repeat"].as_bool().unwrap_or(false))))
            .collect();
        self.timers.retain(|id, t| wanted.iter().any(|(w, s, r)| w == id && *s == t.seconds && *r == t.repeat));
        for (id, seconds, repeat) in wanted {
            self.timers.entry(id).or_insert_with(|| Timer { seconds, repeat, due: Instant::now() + period(seconds, repeat) });
        }
    }

    /// Deliver whatever is due: answers, stream pieces, sensor readings,
    /// timers; and reload when the folder changed. True when anything did.
    pub fn tick(&mut self) -> bool {
        let before = self.version;
        let now = Instant::now();
        while let Ok(done) = self.http_rx.try_recv() {
            self.world.borrow_mut().http_done(done);
        }
        // Sensor readings, as stream pieces.
        {
            let mut w = self.world.borrow_mut();
            let mut pieces = Vec::new();
            for s in w.sensors.iter_mut() {
                if s.next <= now {
                    s.n += 1;
                    s.next = now + s.period;
                    pieces.push(json!({"type": "stream", "token": s.token, "value": pretend::reading(&s.kind, s.started.elapsed().as_secs_f64(), s.n)}));
                }
            }
            for p in pieces {
                w.later.push(bridge::Later { at: now, event: p, ends: false });
            }
        }
        // Everything due, oldest first. Each delivery may queue more.
        for _ in 0..64 {
            let next = {
                let mut w = self.world.borrow_mut();
                let due = w.later.iter().enumerate().filter(|(_, l)| l.at <= now).min_by_key(|(_, l)| l.at).map(|(i, _)| i);
                due.map(|i| {
                    let l = w.later.remove(i);
                    if l.ends {
                        w.pending = w.pending.saturating_sub(1);
                    }
                    l
                })
            };
            let Some(l) = next else { break };
            self.deliver(&l.event);
        }
        let due: Vec<String> = self.timers.iter().filter(|(_, t)| t.due <= now).map(|(id, _)| id.clone()).collect();
        for id in due {
            if let Some(t) = self.timers.get_mut(&id) {
                if t.repeat {
                    t.due = now + period(t.seconds, true);
                } else {
                    self.timers.remove(&id);
                }
            }
            self.deliver(&json!({"type": "timer", "id": id}));
        }
        self.drain_world();
        // A toast going away is a change to draw.
        let toast_due = self.world.borrow().toasts.iter().map(|(_, u)| *u).filter(|u| *u > now).min();
        if let Some(check) = self.toast_check {
            if check <= now {
                self.version += 1;
                self.toast_check = None;
            }
        }
        if self.toast_check.is_none() {
            self.toast_check = toast_due;
        }
        self.world.borrow_mut().toasts.retain(|(_, u)| *u > now);
        if self.opts.watch && now.duration_since(self.last_scan) >= WATCH_EVERY {
            self.last_scan = now;
            let sig = signature(&self.opts.dir);
            if sig != self.signature {
                let what = changed_file(&self.signature, &sig);
                self.signature = sig;
                self.reload(&format!("{what} changed"));
            }
        }
        self.version != before
    }

    /// When [tick] next has something to do.
    pub fn next_wake(&self) -> Duration {
        let now = Instant::now();
        let mut next = if self.opts.watch { self.last_scan + WATCH_EVERY } else { now + Duration::from_secs(1) };
        let w = self.world.borrow();
        for t in w.later.iter().map(|l| l.at).chain(w.sensors.iter().map(|s| s.next)).chain(self.timers.values().map(|t| t.due)).chain(self.toast_check) {
            next = next.min(t);
        }
        if w.pending > 0 {
            // A request thread may answer at any moment.
            next = next.min(now + Duration::from_millis(30));
        }
        next.saturating_duration_since(now)
    }

    /// The state a front end draws, as one JSON object (the preview's
    /// server-sent events carry it).
    pub fn snapshot(&self) -> Json {
        let w = self.world.borrow();
        let console: Vec<Json> = self
            .console
            .iter()
            .rev()
            .take(300)
            .rev()
            .map(|c| json!({"kind": c.kind.word(), "text": c.text, "at": c.at, "diag": c.diag.as_ref().map(diag_json)}))
            .collect();
        let calls: Vec<Json> = self.calls.iter().rev().take(200).rev().map(|c| json!({"op": c.op, "args": c.args, "reply": c.reply, "source": c.source, "millis": c.millis})).collect();
        let dialog = w.dialogs.front().map(|d| json!({"title": d.title, "text": d.text, "buttons": d.buttons}));
        let now = Instant::now();
        let toasts: Vec<&String> = w.toasts.iter().filter(|(_, u)| *u > now).map(|(t, _)| t).collect();
        json!({
            "version": self.version,
            "plugin": {"id": self.facts.id, "name": self.facts.name, "version": self.facts.version, "api": self.facts.api, "panel": self.facts.panel, "dir": self.opts.dir.to_string_lossy()},
            "theme": if self.opts.dark { "dark" } else { "light" },
            "status": match self.status { Status::Ready => "ready", Status::Failed => "failed", Status::NoPanel => "no_panel" },
            "ui": self.ui,
            "host": self.meta,
            "inputs": self.inputs,
            "console": console,
            "storage": self.storage,
            "secrets": w.secrets.names(),
            "field": {"text": w.field.string(), "cursor": w.field.cursor, "selection": w.field.selection.map(|(a, b)| json!([a, b]))},
            "clipboard": w.clipboard,
            "calls": calls,
            "sent": self.sent,
            "error": self.error.as_ref().map(diag_json),
            "toasts": toasts,
            "dialog": dialog,
            "usage": self.usage,
            "closed": w.closed,
        })
    }
}

fn period(seconds: f64, repeat: bool) -> Duration {
    let d = Duration::from_secs_f64(seconds.max(0.0));
    d.max(if repeat { MIN_REPEAT } else { MIN_ONCE })
}

/// The world a session's host calls go to, its storage and secrets in
/// [state], and where `then=` requests send their answers.
pub fn make_world(opts: &Options, facts: &PluginFacts, state: &Path, observer: Option<bridge::Observer>) -> Result<(Rc<RefCell<World>>, Receiver<HttpDone>), String> {
    let _ = std::fs::create_dir_all(state);
    let mock = match &opts.mock {
        None => None,
        Some(path) => {
            let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let v: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
            if !v.is_object() {
                return Err(format!("{}: a mock file is a JSON object of replies by op name", path.display()));
            }
            Some(Mock::new(v))
        }
    };
    for (key, _) in &opts.sets {
        if facts.spec(key).is_none() {
            return Err(format!("--set {key}: plugin.json declares no setting '{key}'"));
        }
    }
    let secret_sets = opts.sets.iter().filter(|(k, _)| facts.spec(k).is_some_and(|s| s.kind == "secret")).cloned().collect();
    let plain_sets: Vec<(String, String)> = opts.sets.iter().filter(|(k, _)| facts.spec(k).is_some_and(|s| s.kind != "secret")).cloned().collect();
    let settings = SettingValues::load(state.join("settings.json"), &plain_sets);
    let secrets = Secrets::new(state.join("secrets.json"), secret_sets);
    let typed_hosts = settings.typed_hosts(facts);
    let mut allowed = facts.hosts.clone();
    allowed.extend(typed_hosts.iter().filter(|h| !plugin::is_local_host(h)).cloned());
    let local = typed_hosts.iter().filter(|h| plugin::is_local_host(h)).cloned().collect();
    let reach = http::Reach { allowed, local, network_local: facts.has("network.local") };
    let (tx, rx) = channel();
    let mut world = World::new(facts.clone(), reach, mock, secrets, settings, tx);
    world.observer = observer;
    Ok((Rc::new(RefCell::new(world)), rx))
}

/// Every file a change to which should reload the plugin, with its time and size.
fn signature(dir: &Path) -> Vec<(String, u128, u64)> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(read) = std::fs::read_dir(&d) else { continue };
        for item in read.flatten() {
            let name = item.file_name().to_string_lossy().into_owned();
            if name.starts_with('.') || name == "__pycache__" || name == "node_modules" || name == "target" || name.ends_with(".wmplugin") {
                continue;
            }
            let path = item.path();
            let Ok(meta) = item.metadata() else { continue };
            if meta.is_dir() {
                stack.push(path);
            } else if out.len() < 4000 {
                let rel = path.strip_prefix(dir).unwrap_or(&path).to_string_lossy().replace('\\', "/");
                let mtime = meta.modified().ok().and_then(|t| t.duration_since(SystemTime::UNIX_EPOCH).ok()).map_or(0, |d| d.as_nanos());
                out.push((rel, mtime, meta.len()));
            }
        }
    }
    out.sort();
    out
}

fn changed_file(old: &[(String, u128, u64)], new: &[(String, u128, u64)]) -> String {
    for n in new {
        if !old.contains(n) {
            return n.0.clone();
        }
    }
    for o in old {
        if !new.iter().any(|n| n.0 == o.0) {
            return o.0.clone();
        }
    }
    "a file".into()
}

fn repair_words(code: &str) -> &'static str {
    match code {
        "NOT_A_WIDGET" => "something that is not a widget was left out",
        "TOO_DEEP" => "widgets nested too deep were left out",
        "TOO_MANY_WIDGETS" => "widgets past the limit were left out",
        "NO_TYPE" => "a widget with no type was left out",
        "UNKNOWN_TYPE" => "a widget of an unknown type was left out",
        "TABS_NO_PAGES" => "tabs with no pages were left out",
        "TOO_MANY_TABS" => "tabs past the limit were left out",
        "TEXT_BUDGET" => "text past the tree's limit was left out",
        "TEXT_SHORTENED" => "a text too long was shortened",
        "BAD_IMAGE" => "an image with an address the keyboard does not load was left out",
        _ => "part of the tree was left out",
    }
}

/// A [Diag] in the shape every wmpy tool shares.
pub fn diag_json(d: &Diag) -> Json {
    d.to_json()
}

#[cfg(test)]
pub(crate) mod tests;
