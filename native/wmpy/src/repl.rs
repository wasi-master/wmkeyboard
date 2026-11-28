//! `wmpy repl`: wm-python line by line, with the `wm` package.
//!
//! ```text
//! wmpy repl [dir] [--mock file.json] [--no-history]
//! ```
//!
//! Inside a plugin folder (or given one) the prompt has the plugin's
//! manifest permissions and its other `.py` files as modules (`import
//! helpers`, and `import main` for the entry); outside one, every
//! permission is on and any https host can be reached. Host calls go to the
//! dev host ([crate::devhost]): real https, a pretend field and clipboard,
//! pretend answers for what only a phone has, `--mock` answers first. A
//! `then=` answer is waited for after each input (15 s at most).
//!
//! Input works as in CPython's prompt: a line ending in `:` or a compound
//! statement opens a block that a blank line ends, and open brackets,
//! strings and a trailing `\` continue on the next line. An expression's
//! value is echoed with `repr` and kept as `_`. Errors are shown with the
//! line typed. Budgets are those of a test (500 million steps and 10
//! minutes an input); Ctrl-C stops a runaway loop and keeps the session.
//!
//! The panel is the plugin as the keyboard runs it, in its own session:
//! `:render` draws it as text and `:event` sends it an event. History is
//! kept in the user's data folder (`wmpy/repl_history.txt`). With stdin not
//! a terminal, the input is read as a script and only results are printed.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::io::{IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};
use wm_python::host::api::PluginInfo;
use wm_python::host::files::{Quota, Reminders, Storage};
use wm_python::host::{json_to_value, v5};
use wm_python::interp::{AbortKind, Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};
use wm_python_tooling::document::{Document, Host};

use crate::devhost::bridge::{self, CallRecord, HttpDone, World};
use crate::devhost::plugin::{PluginFacts, Secrets, SettingValues};
use crate::devhost::{http, make_world, DevHost, Options};
use crate::diag::{color_stderr, Diag};
use crate::tui::layout::{self, Action, Local};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// A plugin folder; without one, the folder you are in if it holds plugin.json
    pub dir: Option<PathBuf>,
    /// A JSON file answering host calls by op name before the dev host does [default: wmpy.toml's run.mock]
    #[arg(long, value_name = "FILE")]
    pub mock: Option<PathBuf>,
    #[command(flatten)]
    pub look: crate::look::Look,
    /// Neither read nor write the history file
    #[arg(long)]
    pub no_history: bool,
}

/// What every input starts from, so completion knows these names too.
const PRELUDE: &str = "import wm\nfrom wm import plugin, ui\n";
/// The budget of one input, as a test's.
const STEPS: u64 = 500_000_000;
const DEADLINE: Duration = Duration::from_secs(600);
/// How long an input waits for `then=` answers.
const WAIT: Duration = Duration::from_secs(15);
const MAX_CALLS: usize = 300;
/// The panel's width, as `wmpy run` draws it.
const WIDTH: u16 = 52;

const HELP: &str = "\
:help              this list
:reset             a fresh interpreter, and the panel loaded again
:render            draw the plugin's panel as text (it loads on first use)
:event <json>      send the panel an event and draw it again: :event {\"type\": \"click\", \"id\": \"button:Add\"}
                   (a widget id alone is a click: :event button:Add)
:storage           what the prompt and the panel stored
:calls             the host calls the prompt and the panel made
:load <file>       run a file at the prompt, as if typed
:quit              leave (Ctrl-D does too)
An expression's value is printed and kept as _. Ctrl-C stops what is running.";

/// Exception kinds a host reply may raise by name.
const RAISABLE: &[&str] = &["PermissionError", "TimeoutError", "ConnectionError", "ValueError", "TypeError", "OSError", "RuntimeError", "NotImplementedError", "FileNotFoundError", "KeyError"];

pub fn run(args: Args) -> u8 {
    let dir = match &args.dir {
        Some(d) => match crate::project::plugin_dir(d) {
            Ok(d) => Some(d),
            Err(d) => {
                eprint!("{}", d.render(&|_| None, color_stderr()));
                return 2;
            }
        },
        None => Path::new("plugin.json").is_file().then(|| PathBuf::from(".")),
    };
    let config = match &dir {
        Some(d) => match crate::config::load_or_report(d) {
            Ok(c) => c,
            Err(code) => return code,
        },
        None => crate::config::Config::default(),
    };
    args.look.apply(dir.as_deref().unwrap_or(Path::new(".")), &config);
    let mock = args.mock.clone().or_else(|| dir.as_ref().and_then(|d| config.run.mock.as_ref().map(|m| crate::config::in_dir(d, m))));
    let tty = std::io::stdin().is_terminal();
    // Ctrl-C while code runs stops it; a second one before it stops ends wmpy.
    let cancel = Arc::new(AtomicBool::new(false));
    let _ = signal_hook::flag::register_conditional_shutdown(signal_hook::consts::SIGINT, 130, cancel.clone());
    let _ = signal_hook::flag::register(signal_hook::consts::SIGINT, cancel.clone());
    let mut repl = match Repl::new(dir, mock, cancel) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    if tty {
        interactive(&mut repl, !args.no_history)
    } else {
        let mut text = String::new();
        if let Err(e) = std::io::stdin().read_to_string(&mut text) {
            eprintln!("error: cannot read stdin: {e}");
            return 2;
        }
        for line in text.lines() {
            if repl.feed(line) == Flow::Quit {
                return repl.exit_code();
            }
        }
        repl.finish();
        repl.exit_code()
    }
}

fn interactive(repl: &mut Repl, keep_history: bool) -> u8 {
    use rustyline::error::ReadlineError;
    let config = rustyline::Config::builder().auto_add_history(false).build();
    let mut ed: rustyline::Editor<Helper, rustyline::history::DefaultHistory> = match rustyline::Editor::with_config(config) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("error: cannot read the terminal: {e}");
            return 2;
        }
    };
    ed.set_helper(Some(Helper { state: repl.completion.clone() }));
    let history = keep_history.then(history_file).flatten();
    if let Some(h) = &history {
        let _ = ed.load_history(h);
    }
    eprintln!("{}", repl.banner());
    loop {
        repl.update_completion();
        // At the prompt Ctrl-C is a key, not a signal: nothing is left to stop.
        repl.cancel.store(false, Ordering::SeqCst);
        let prompt = if repl.pending.is_empty() { ">>> " } else { "... " };
        match ed.readline(prompt) {
            Ok(line) => {
                if !line.trim().is_empty() {
                    let _ = ed.add_history_entry(line.as_str());
                }
                if repl.feed(&line) == Flow::Quit {
                    break;
                }
            }
            Err(ReadlineError::Interrupted) => {
                repl.pending.clear();
                eprintln!("KeyboardInterrupt");
            }
            Err(ReadlineError::Eof) => {
                repl.finish();
                break;
            }
            Err(e) => {
                eprintln!("error: {e}");
                break;
            }
        }
    }
    if let Some(h) = &history {
        if let Some(parent) = h.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = ed.save_history(h);
    }
    0
}

/// `wmpy/repl_history.txt` in the user's data folder.
fn history_file() -> Option<PathBuf> {
    let env = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
    let base = if cfg!(target_os = "macos") {
        env("HOME").map(|h| h.join("Library/Application Support"))
    } else if cfg!(windows) {
        env("APPDATA")
    } else {
        env("XDG_DATA_HOME").or_else(|| env("HOME").map(|h| h.join(".local/share")))
    }?;
    Some(base.join("wmpy").join("repl_history.txt"))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Flow {
    Go,
    Quit,
}

/// What tab completion reads: the code run so far, the block being typed,
/// and what the interpreter holds now (its globals, each module's names),
/// which the language service cannot see in a plugin's own modules.
#[derive(Default)]
struct Completion {
    base: String,
    host: Host,
    commands: bool,
    globals: Vec<String>,
    members: Vec<(String, Vec<String>)>,
}

struct Helper {
    state: Rc<RefCell<Completion>>,
}

impl rustyline::completion::Completer for Helper {
    type Candidate = rustyline::completion::Pair;

    fn complete(&self, line: &str, pos: usize, _ctx: &rustyline::Context<'_>) -> rustyline::Result<(usize, Vec<rustyline::completion::Pair>)> {
        let state = self.state.borrow();
        Ok(complete_line(&state, line, pos))
    }
}

impl rustyline::hint::Hinter for Helper {
    type Hint = String;
}
impl rustyline::highlight::Highlighter for Helper {}
impl rustyline::validate::Validator for Helper {}
impl rustyline::Helper for Helper {}

const COMMANDS: &[&str] = &[":help", ":reset", ":render", ":event ", ":storage", ":calls", ":load ", ":quit"];

/// Candidates for the word at [pos] of [line]: commands at the start of an
/// input, else the language service's suggestions.
fn complete_line(state: &Completion, line: &str, pos: usize) -> (usize, Vec<rustyline::completion::Pair>) {
    use rustyline::completion::Pair;
    let before = &line[..pos];
    if state.commands && before.trim_start().starts_with(':') && !before.contains(' ') {
        let start = line.len() - line.trim_start().len();
        let typed = before.trim_start();
        let pairs = COMMANDS.iter().filter(|c| c.starts_with(typed)).map(|c| Pair { display: c.trim().to_string(), replacement: c.to_string() }).collect();
        return (start, pairs);
    }
    let text = format!("{}{line}", state.base);
    let base_units = state.base.encode_utf16().count() as u32;
    let caret = base_units + before.encode_utf16().count() as u32;
    let doc = Document::new(&text);
    let Some(found) = wm_python_tooling::complete::complete(&doc, caret, true, &state.host) else { return live(state, before) };
    // The span is in UTF-16 units of the whole text; the line's bytes are wanted.
    let start_units = found.replace.start.saturating_sub(base_units);
    let mut units = 0u32;
    let mut start = line.len().min(pos);
    for (i, c) in line.char_indices() {
        if units >= start_units {
            start = i;
            break;
        }
        units += c.len_utf16() as u32;
    }
    if units < start_units {
        start = pos;
    }
    let mut pairs: Vec<Pair> = found
        .items
        .iter()
        .filter(|i| i.kind != wm_python_tooling::complete::ItemKind::Snippet && !i.insert.contains('\n'))
        .map(|i| Pair { display: i.label.clone(), replacement: i.insert.clone() })
        .collect();
    let (live_start, more) = live(state, before);
    if pairs.is_empty() {
        return (live_start, more);
    }
    if live_start == start.min(pos) {
        for p in more {
            if !pairs.iter().any(|x| x.replacement == p.replacement) {
                pairs.push(p);
            }
        }
    }
    (start.min(pos), pairs)
}

/// Names the interpreter holds that fit the word before the caret: a
/// global, or `module.name`.
fn live(state: &Completion, before: &str) -> (usize, Vec<rustyline::completion::Pair>) {
    use rustyline::completion::Pair;
    let word_start = before.char_indices().rev().take_while(|(_, c)| c.is_alphanumeric() || *c == '_' || *c == '.').last().map_or(before.len(), |(i, _)| i);
    let word = &before[word_start..];
    let (names, prefix): (Vec<&String>, &str) = match word.rsplit_once('.') {
        Some((owner, prefix)) => match state.members.iter().find(|(m, _)| m == owner) {
            Some((_, names)) => (names.iter().collect(), prefix),
            None => return (before.len(), Vec::new()),
        },
        None => (state.globals.iter().collect(), word),
    };
    let pairs = names
        .into_iter()
        .filter(|n| n.starts_with(prefix) && (prefix.starts_with('_') || !n.starts_with('_')))
        .map(|n| Pair { display: n.clone(), replacement: n.clone() })
        .collect();
    (before.len() - prefix.len(), pairs)
}

/// What the lines typed so far still need: more lines (inside brackets, a
/// string, after a `\`), and whether they make a block a blank line ends.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Scan {
    pub open: bool,
    pub block: bool,
}

const COMPOUND: &[&str] = &["if", "elif", "else", "for", "while", "def", "class", "try", "except", "finally", "with", "async", "match", "case"];

pub fn scan(src: &str) -> Scan {
    let c: Vec<char> = src.chars().collect();
    let mut i = 0;
    let mut depth: i32 = 0;
    let mut block = false;
    let mut line_start = true;
    let mut last: Option<char> = None;
    let mut string: Option<(char, bool)> = None;
    let mut continued = false;
    while i < c.len() {
        let ch = c[i];
        continued = false;
        if let Some((q, triple)) = string {
            if ch == '\\' {
                i += 2;
                continue;
            }
            if ch == q && (!triple || (c.get(i + 1) == Some(&q) && c.get(i + 2) == Some(&q))) {
                string = None;
                i += if triple { 3 } else { 1 };
                last = Some(q);
                continue;
            }
            if ch == '\n' && !triple {
                string = None;
                continue;
            }
            i += 1;
            continue;
        }
        match ch {
            '#' => {
                while i < c.len() && c[i] != '\n' {
                    i += 1;
                }
                continue;
            }
            '\'' | '"' => {
                let triple = c.get(i + 1) == Some(&ch) && c.get(i + 2) == Some(&ch);
                string = Some((ch, triple));
                line_start = false;
                i += if triple { 3 } else { 1 };
                continue;
            }
            '\\' if c.get(i + 1) == Some(&'\n') || i + 1 == c.len() => {
                continued = true;
                i += 2;
                continue;
            }
            '(' | '[' | '{' => depth += 1,
            ')' | ']' | '}' => depth -= 1,
            '\n' => {
                if depth <= 0 {
                    if last == Some(':') {
                        block = true;
                    }
                    last = None;
                    line_start = true;
                }
                i += 1;
                continue;
            }
            ' ' | '\t' | '\r' => {
                i += 1;
                continue;
            }
            _ => {}
        }
        if line_start && depth <= 0 {
            if ch == '@' {
                block = true;
            } else if ch.is_alphabetic() || ch == '_' {
                let word: String = c[i..].iter().take_while(|x| x.is_alphanumeric() || **x == '_').collect();
                if COMPOUND.contains(&word.as_str()) {
                    block = true;
                }
            }
        }
        line_start = false;
        last = Some(ch);
        i += 1;
    }
    if depth <= 0 && string.is_none() && last == Some(':') {
        block = true;
    }
    Scan { open: depth > 0 || string.is_some_and(|(_, triple)| triple) || continued, block }
}

/// A line at the margin that carries on the block before it.
fn continues_block(trimmed: &str) -> bool {
    ["else", "elif", "except", "finally", "case"].iter().any(|k| trimmed.strip_prefix(k).is_some_and(|rest| rest.starts_with([':', ' ', '(', '\t'])))
}

/// The plugin the prompt belongs to, or none.
struct Plugin {
    dir: PathBuf,
    manifest: crate::project::Manifest,
}

pub struct Repl {
    plugin: Option<Plugin>,
    facts: PluginFacts,
    mock: Option<PathBuf>,
    rt: Runtime,
    world: Rc<RefCell<World>>,
    http_rx: Receiver<HttpDone>,
    /// The prompt's state folder outside a plugin, removed on exit.
    scratch: Option<crate::budget::drive::TempDir>,
    pub cancel: Arc<AtomicBool>,
    pending: Vec<String>,
    /// The code that ran, for completion.
    ran: String,
    calls: VecDeque<CallRecord>,
    panel: Option<DevHost>,
    /// How many of the panel's console lines were shown.
    panel_seen: usize,
    completion: Rc<RefCell<Completion>>,
    /// Something failed; a script fed on stdin exits with 1.
    failed: bool,
}

impl Repl {
    /// [cancel] stops what runs when set; Ctrl-C sets it.
    pub fn new(dir: Option<PathBuf>, mock: Option<PathBuf>, cancel: Arc<AtomicBool>) -> Result<Repl, String> {
        let plugin = match dir {
            Some(d) => Some(Plugin { manifest: crate::project::manifest(&d)?, dir: d }),
            None => None,
        };
        let (facts, scratch) = match &plugin {
            Some(p) => (PluginFacts::read(&p.dir)?, None),
            None => (loose_facts(), Some(crate::budget::drive::TempDir::new("repl")?)),
        };
        let (rt, world, http_rx) = build(plugin.as_ref(), &facts, mock.as_deref(), scratch.as_ref().map(|s| s.path()), cancel.clone())?;
        let mut host = match &plugin {
            Some(p) => std::fs::read_to_string(p.dir.join("plugin.json"))
                .ok()
                .and_then(|t| serde_json::from_str::<Json>(&t).ok())
                .map(|m| wm_python_tooling::api_json::host_of_manifest(&m))
                .unwrap_or_default(),
            None => Host { api: Some(5), ..Host::default() },
        };
        if let Some(p) = &plugin {
            let mut names: Vec<String> = crate::project::modules(&p.dir, &p.manifest.entry).keys().filter_map(|f| v5::module_name(f).map(|(n, _)| n)).collect();
            names.push("main".into());
            host.modules = Some(names);
        }
        let completion = Rc::new(RefCell::new(Completion { base: PRELUDE.into(), host, commands: true, ..Completion::default() }));
        Ok(Repl { plugin, facts, mock, rt, world, http_rx, scratch, cancel, pending: Vec::new(), ran: String::new(), calls: VecDeque::new(), panel: None, panel_seen: 0, completion, failed: false })
    }

    fn banner(&self) -> String {
        let place = match &self.plugin {
            Some(p) => format!(
                "plugin {} ({}): its modules import, with its permissions",
                if self.facts.name.is_empty() { &self.facts.id } else { &self.facts.name },
                crate::project::shown(&p.dir)
            ),
            None => "no plugin folder: every permission is on".into(),
        };
        format!("wmpy repl, wm-python {} (plugin API 5): {place}\n`wm`, `plugin` and `ui` are imported. :help lists the commands, Ctrl-D leaves.", wm_python::interp::VERSION)
    }

    fn exit_code(&self) -> u8 {
        u8::from(self.failed)
    }

    fn update_completion(&mut self) {
        let (globals, members) = self.live_names();
        let mut c = self.completion.borrow_mut();
        c.globals = globals;
        c.members = members;
        let mut base = format!("{PRELUDE}{}", self.ran);
        for l in &self.pending {
            base.push_str(l);
            base.push('\n');
        }
        c.base = base;
        c.commands = self.pending.is_empty();
    }

    /// The interpreter's globals, and the names of each module among them.
    fn live_names(&mut self) -> (Vec<String>, Vec<(String, Vec<String>)>) {
        let names = |v: Result<Value, PyError>| v.ok().and_then(|v| v.as_items()).unwrap_or_default().iter().filter_map(|x| x.as_str().map(String::from)).collect::<Vec<_>>();
        let globals = names(self.rt.run("sorted(globals())"));
        let mut members = Vec::new();
        for g in &globals {
            if let Some(Value::Module(_)) = self.rt.get(g) {
                members.push((g.clone(), names(self.rt.run(&format!("dir({g})")))));
            }
        }
        (globals, members)
    }

    /// One line of input.
    pub fn feed(&mut self, line: &str) -> Flow {
        let trimmed = line.trim();
        if self.pending.is_empty() {
            if trimmed.is_empty() {
                return Flow::Go;
            }
            if let Some(cmd) = trimmed.strip_prefix(':') {
                return self.command(cmd.trim());
            }
        } else {
            let sc = scan(&self.pending.join("\n"));
            if !sc.open && sc.block {
                if trimmed.is_empty() {
                    self.run_pending();
                    return Flow::Go;
                }
                // A line at the margin ends the block, as a script reads it,
                // unless it is what a decorator or an `if` is waiting for.
                let decorated = self.pending.last().is_some_and(|l| l.trim_start().starts_with('@'));
                if !line.starts_with([' ', '\t']) && !continues_block(trimmed) && !decorated {
                    self.run_pending();
                    return self.feed(line);
                }
            }
        }
        self.pending.push(line.to_string());
        let sc = scan(&self.pending.join("\n"));
        if !(sc.open || sc.block) {
            self.run_pending();
        }
        Flow::Go
    }

    /// The end of the input: what is still pending runs.
    pub fn finish(&mut self) {
        if !self.pending.is_empty() {
            self.run_pending();
        }
    }

    fn run_pending(&mut self) {
        let src = self.pending.join("\n") + "\n";
        self.pending.clear();
        self.execute(&src, "<input>");
    }

    fn execute(&mut self, src: &str, label: &str) {
        // What is typed (or :load-ed) runs as the script: its frames name it.
        self.rt.set_module_file("__main__", label);
        self.rt.set_deadline(Some(Instant::now() + DEADLINE));
        let result = self.rt.run(src);
        if wm_python::interp::check(src).is_ok() {
            self.ran.push_str(src);
        }
        match result {
            Ok(v) if !v.is_none() => {
                self.rt.set("_", Some(v.clone()));
                let text = self.repr(&v);
                println!("{text}");
            }
            Ok(_) => {}
            Err(e) => self.show_error(&e, src, label),
        }
        self.pump(true);
        let _ = std::io::stdout().flush();
    }

    /// `repr(v)`, through the interpreter for an instance with its own `__repr__`.
    fn repr(&mut self, v: &Value) -> String {
        if !matches!(v, Value::Instance(_)) {
            return v.repr();
        }
        self.rt.set("__wmpy_value__", Some(v.clone()));
        let r = self.rt.run("repr(__wmpy_value__)");
        self.rt.set("__wmpy_value__", None);
        match r {
            Ok(Value::Str(s)) => s.to_string(),
            _ => v.repr(),
        }
    }

    fn show_error(&mut self, e: &PyError, src: &str, label: &str) {
        self.failed = true;
        if e.abort == Some(AbortKind::Cancelled) {
            self.cancel.store(false, Ordering::SeqCst);
            eprintln!("KeyboardInterrupt: stopped");
            return;
        }
        let mut d = Diag::from_py_error(e, label);
        match e.abort {
            Some(AbortKind::Steps) => d.help = Some(format!("an input may run {} million steps; Ctrl-C stops a loop sooner", STEPS / 1_000_000)),
            Some(AbortKind::Deadline) => d.help = Some(format!("an input may run {} minutes; Ctrl-C stops it sooner", DEADLINE.as_secs() / 60)),
            _ => {}
        }
        let text = src.to_string();
        let dir = self.plugin.as_ref().map(|p| p.dir.clone());
        let lookup = move |f: &str| {
            if f == label {
                return Some(text.clone());
            }
            dir.as_deref().and_then(|d| crate::project::inside(d, f).ok()).and_then(|p| std::fs::read_to_string(p).ok())
        };
        eprint!("{}", d.render(&lookup, color_stderr()));
    }

    /// Delivers what the dev host answered (`then=` calls), waiting up to
    /// [WAIT] while an answer is due when [wait]; prints the host's notes.
    fn pump(&mut self, wait: bool) {
        let until = Instant::now() + WAIT;
        loop {
            while let Ok(done) = self.http_rx.try_recv() {
                self.world.borrow_mut().http_done(done);
            }
            if let Some(title) = self.world.borrow().dialogs.front().map(|d| d.title.clone()) {
                self.note(&format!("[dialog] “{title}” answered with its first button"));
                self.world.borrow_mut().answer_dialog(Some(0));
            }
            let now = Instant::now();
            let due: Vec<Json> = {
                let mut w = self.world.borrow_mut();
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
            for event in due {
                self.rt.set_deadline(Some(Instant::now() + DEADLINE));
                if let Err(e) = self.rt.call_member("wm._host", "dispatch", vec![json_to_value(&event)]) {
                    self.show_error(&e, "", "<callback>");
                }
            }
            self.drain();
            let busy = {
                let w = self.world.borrow();
                w.pending > w.sensors.len() || !w.later.is_empty()
            };
            if !busy || !wait || Instant::now() >= until {
                return;
            }
            if self.cancel.swap(false, Ordering::SeqCst) {
                eprintln!("KeyboardInterrupt: stopped waiting for answers");
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn note(&self, text: &str) {
        let color = color_stderr();
        if color {
            eprintln!("\x1b[2m{text}\x1b[0m");
        } else {
            eprintln!("{text}");
        }
    }

    /// The host's notes printed, its calls kept for `:calls`.
    fn drain(&mut self) {
        let (notes, calls) = {
            let mut w = self.world.borrow_mut();
            (std::mem::take(&mut w.notes), std::mem::take(&mut w.calls))
        };
        for n in notes {
            self.note(&n);
        }
        self.calls.extend(calls);
        while self.calls.len() > MAX_CALLS {
            self.calls.pop_front();
        }
    }

    fn command(&mut self, cmd: &str) -> Flow {
        let (name, rest) = cmd.split_once(char::is_whitespace).map(|(n, r)| (n, r.trim())).unwrap_or((cmd, ""));
        match name {
            "help" | "h" | "?" => println!("{HELP}"),
            "quit" | "q" | "exit" => return Flow::Quit,
            "reset" => self.reset(),
            "render" => self.render(),
            "event" => self.event(rest),
            "storage" => self.storage(),
            "calls" => self.show_calls(),
            "load" => self.load_file(rest),
            _ => {
                eprintln!("no command :{name}; :help lists them");
                self.failed = true;
            }
        }
        Flow::Go
    }

    fn reset(&mut self) {
        let scratch = self.scratch.as_ref().map(|s| s.path().to_path_buf());
        match build(self.plugin.as_ref(), &self.facts, self.mock.as_deref(), scratch.as_deref(), self.cancel.clone()) {
            Ok((rt, world, rx)) => {
                self.rt = rt;
                self.world = world;
                self.http_rx = rx;
                self.ran.clear();
                self.calls.clear();
                if let Some(p) = self.panel.as_mut() {
                    p.reload(":reset");
                }
                println!("a fresh interpreter{}", if self.panel.is_some() { ", and the panel loaded again" } else { "" });
            }
            Err(e) => {
                eprintln!("error: {e}");
                self.failed = true;
            }
        }
    }

    /// The panel, loaded on first use.
    fn panel(&mut self) -> Option<&mut DevHost> {
        let Some(p) = &self.plugin else {
            eprintln!("there is no panel outside a plugin folder: run wmpy repl in one, or give its path");
            self.failed = true;
            return None;
        };
        if self.panel.is_none() {
            let opts = Options { dir: p.dir.clone(), mock: self.mock.clone(), sets: Vec::new(), dark: crate::project::context().dark(), watch: true };
            match DevHost::open(opts) {
                Ok(h) => self.panel = Some(h),
                Err(e) => {
                    eprintln!("error: {e}");
                    self.failed = true;
                    return None;
                }
            }
        }
        self.panel.as_mut()
    }

    fn render(&mut self) {
        let Some(host) = self.panel() else { return };
        let mut notes = Vec::new();
        crate::budget::drive::settle(host, WAIT, &mut notes);
        let text = panel_text(host);
        let failed = host.status == crate::devhost::Status::Failed;
        self.panel_console(notes);
        print!("{text}");
        if failed {
            self.failed = true;
        }
    }

    /// What the panel printed and the host noted since last shown: prints
    /// on stdout, the rest on stderr.
    fn panel_console(&mut self, notes: Vec<String>) {
        let Some(host) = self.panel.as_ref() else { return };
        let start = self.panel_seen.min(host.console.len());
        let lines: Vec<(crate::devhost::Kind, String)> = host.console.iter().skip(start).map(|c| (c.kind, c.text.clone())).collect();
        self.panel_seen = host.console.len();
        for (kind, text) in lines {
            match kind {
                crate::devhost::Kind::Print => println!("{text}"),
                crate::devhost::Kind::Error => eprintln!("{text}"),
                _ => self.note(&text),
            }
        }
        for n in notes {
            self.note(&n);
        }
    }

    fn event(&mut self, rest: &str) {
        let event = match serde_json::from_str::<Json>(rest) {
            Ok(e @ Json::Object(_)) => e,
            _ if !rest.is_empty() && !rest.contains(char::is_whitespace) && !rest.starts_with(['{', '[', '"']) => json!({"type": "click", "id": rest}),
            _ => {
                eprintln!("an event is a JSON object, such as :event {{\"type\": \"click\", \"id\": \"button:Add\"}}, or a widget id to click");
                self.failed = true;
                return;
            }
        };
        let Some(host) = self.panel() else { return };
        let mut notes = Vec::new();
        crate::budget::drive::settle(host, WAIT, &mut notes);
        let ok = host.send(event);
        crate::budget::drive::settle(host, WAIT, &mut notes);
        let text = panel_text(host);
        self.panel_console(notes);
        print!("{text}");
        if !ok {
            self.failed = true;
        }
    }

    fn storage(&mut self) {
        match self.rt.call_member("_wm", "storage_keys", Vec::new()) {
            Ok(keys) => {
                let keys = keys.as_items().unwrap_or_default();
                println!("the prompt's storage{}", if keys.is_empty() { ": empty" } else { ":" });
                for k in keys {
                    let value = self.rt.call_member("_wm", "storage_get", vec![k.clone()]).map(|v| v.to_str()).unwrap_or_default();
                    println!("  {} = {value}", k.to_str());
                }
            }
            Err(e) => println!("the prompt's storage: {}", e.message),
        }
        if let Some(host) = self.panel.as_mut() {
            host.tick();
            let o = host.storage.as_object().cloned().unwrap_or_default();
            println!("the panel's storage, in {}{}", host.state_dir().join("storage.json").display(), if o.is_empty() { ": empty" } else { ":" });
            for (k, v) in o {
                println!("  {k} = {}", v.as_str().map(String::from).unwrap_or_else(|| v.to_string()));
            }
        }
    }

    fn show_calls(&mut self) {
        self.drain();
        let line = |c: &CallRecord| format!("  {} {} → {} ({}, {} ms)", c.op, crate::mock::summary(&c.args), c.reply, c.source, c.millis);
        let last = |calls: &VecDeque<CallRecord>| calls.iter().skip(calls.len().saturating_sub(50)).map(line).collect::<Vec<_>>();
        println!("the prompt's host calls{}", if self.calls.is_empty() { ": none" } else { ":" });
        for l in last(&self.calls) {
            println!("{l}");
        }
        if let Some(host) = self.panel.as_mut() {
            host.tick();
            println!("the panel's host calls{}", if host.calls.is_empty() { ": none" } else { ":" });
            for l in last(&host.calls) {
                println!("{l}");
            }
        }
    }

    fn load_file(&mut self, path: &str) {
        if path.is_empty() {
            eprintln!(":load needs a file, such as :load scratch.py");
            self.failed = true;
            return;
        }
        match std::fs::read_to_string(path) {
            Ok(src) => self.execute(&src, path),
            Err(e) => {
                eprintln!("cannot read {path}: {e}");
                self.failed = true;
            }
        }
    }
}

impl Drop for Repl {
    fn drop(&mut self) {
        // The scratch state goes with the session.
        let _ = self.scratch.take();
    }
}

/// The panel as text: its title, its tree at a phone's width, and what a
/// click reaches. Its errors are in its console.
fn panel_text(host: &DevHost) -> String {
    let mut out = String::new();
    let title = host.meta["title"].as_str().filter(|t| !t.is_empty()).unwrap_or(&host.facts.name).to_string();
    out.push_str(&format!("── {title} {}\n", "─".repeat((WIDTH as usize).saturating_sub(title.chars().count() + 4))));
    if host.status == crate::devhost::Status::NoPanel {
        out.push_str("this plugin has no panel (\"panel\": false in plugin.json)\n");
        return out;
    }
    let root = host.ui["root"].as_array().cloned().unwrap_or_default();
    let v5 = host.facts.api >= 5;
    let (buf, targets, height) = layout::render(&root, WIDTH, &Local::default(), None, false, false, v5);
    for y in 0..height {
        let mut row = String::new();
        for x in 0..buf.area.width {
            row.push_str(buf[(x, y)].symbol());
        }
        out.push_str(row.trim_end());
        out.push('\n');
    }
    let clicks: Vec<String> = targets
        .iter()
        .filter_map(|t| match &t.action {
            Action::Click(id) if !id.is_empty() => Some(id.clone()),
            _ => None,
        })
        .collect();
    out.push_str(&format!("{}\n", "─".repeat(WIDTH as usize)));
    if !clicks.is_empty() {
        out.push_str(&format!("clicks: {}\n", clicks.join(", ")));
    }
    out
}

/// The facts of the prompt outside a plugin: every permission, no hosts (any is let through).
fn loose_facts() -> PluginFacts {
    PluginFacts {
        id: "wmpy.repl".into(),
        name: "wmpy repl".into(),
        version: "0".into(),
        api: 5,
        panel: true,
        permissions: wm_python_tooling::manifest::PERMISSIONS.iter().map(|p| p.wire.to_string()).collect(),
        hosts: Vec::new(),
        settings: Vec::new(),
    }
}

/// A prelude module whose calls to the keyboard go to the prompt's host instead.
fn rerouted(source: &str) -> String {
    source.replacen("import _wm\n", "import _wm\nimport _wmpy_repl\n", 1).replace("_wm.call(", "_wmpy_repl.call(").replace("_wm.log(", "_wmpy_repl.log(")
}

type Built = (Runtime, Rc<RefCell<World>>, Receiver<HttpDone>);

/// The prompt's interpreter and the world its host calls go to.
fn build(plugin: Option<&Plugin>, facts: &PluginFacts, mock: Option<&Path>, scratch: Option<&Path>, cancel: Arc<AtomicBool>) -> Result<Built, String> {
    let (world, rx) = match (plugin, scratch) {
        (Some(p), _) => {
            let opts = Options { dir: p.dir.clone(), mock: mock.map(Path::to_path_buf), ..Options::default() };
            make_world(&opts, facts, &p.dir.join(".wmpy"), None)?
        }
        (None, Some(state)) => {
            let mock = match mock {
                Some(path) => {
                    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
                    Some(crate::mock::Mock::new(serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?))
                }
                None => None,
            };
            let (tx, rx) = std::sync::mpsc::channel();
            let settings = SettingValues::load(state.join("settings.json"), &[]);
            let secrets = Secrets::new(state.join("secrets.json"), Default::default());
            let reach = http::Reach { allowed: Vec::new(), local: Vec::new(), network_local: true };
            (Rc::new(RefCell::new(World::new(facts.clone(), reach, mock, secrets, settings, tx))), rx)
        }
        (None, None) => return Err("the prompt needs a state folder".into()),
    };
    let mut rt = Runtime::new(Limits { steps: STEPS, container_items: 200_000, string_length: 1_000_000 }, STANDARD_MODULES);
    rt.set_cancel_handle(cancel);
    let info = PluginInfo { id: facts.id.clone(), version: facts.version.clone(), permissions: facts.permissions.clone(), panel: true };
    let mut files: Vec<(String, String)> = match plugin {
        Some(p) => crate::project::modules(&p.dir, &p.manifest.entry).into_iter().filter_map(|(f, t)| t.as_str().map(|t| (f, t.to_string()))).collect(),
        None => Vec::new(),
    };
    files.sort();
    v5::install_for_tests(&mut rt, &info, files, Some(Storage::with_quota(None, Quota::V5)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
    if let Some(p) = plugin {
        // The entry imports as `main`, as in a test.
        if let (Some(stem), Ok(text)) = (p.manifest.entry.strip_suffix(".py"), std::fs::read_to_string(p.dir.join(&p.manifest.entry))) {
            if !stem.contains('/') {
                rt.add_source_module(stem, &text, false);
                rt.set_module_file(stem, &p.manifest.entry);
            }
        }
    }
    for (name, source, package) in v5::PRELUDE {
        if source.contains("_wm.call(") || source.contains("_wm.log(") {
            rt.add_source_module(name, &rerouted(source), *package);
        }
    }
    let w = world.clone();
    let permissions = facts.permissions.clone();
    let any_host = plugin.is_none();
    rt.define_module("_wmpy_repl", move |m| {
        m.define("call", move |call| host_call(&w, &permissions, any_host, call));
        m.define("log", |call| {
            let line = call.args.iter().map(|v| v.to_str()).collect::<Vec<_>>().join(" ");
            println!("{line}");
            Ok(Value::None)
        });
    });
    rt.set_on_print(Some(Box::new(|line| {
        println!("{line}");
        let _ = std::io::stdout().flush();
    })));
    let settings = world.borrow().settings.json(facts);
    rt.set("__wmpy_settings__", Some(json_to_value(&settings)));
    let setup = format!(
        "{PRELUDE}import _wm\n_wm.settings.update(__wmpy_settings__)\n_wm.context.update({})\ndel _wm\ndel __wmpy_settings__\n",
        crate::project::context().json()
    );
    rt.load(&setup).map_err(|e| format!("the wm package did not load: {}: {}", e.kind, e.message))?;
    Ok((rt, world, rx))
}

/// `_wm.call(op, args)` at the prompt: the manifest's permission checked,
/// then the dev host answers.
fn host_call(world: &Rc<RefCell<World>>, permissions: &[String], any_host: bool, call: &mut wm_python::interp::Call) -> Result<Value, PyError> {
    let op: String = call.arg(0, Some("op"))?;
    let args = call.value(1, Some("args")).cloned().unwrap_or(Value::None);
    match v5::permission_for(&op) {
        None => return Err(PyError::new(format!("the keyboard has no '{op}'"), "ValueError")),
        Some(Some(p)) if !permissions.iter().any(|x| x.trim() == p) => {
            return Err(PyError::new(format!("{op} needs the '{p}' permission, which this plugin's manifest does not list"), "PermissionError"));
        }
        _ => {}
    }
    let args = match v5::to_json(&args, 0) {
        o @ Json::Object(_) => o,
        Json::Null => json!({}),
        _ => return Err(PyError::new("host call arguments must be a dict", "TypeError")),
    };
    if any_host && op == "http.request" {
        // Outside a plugin there are no manifest hosts: let the one asked for through.
        if let Some(host) = args["url"].as_str().and_then(url_host) {
            let mut w = world.borrow_mut();
            if !w.reach.allowed.contains(&host) {
                w.reach.allowed.push(host);
            }
        }
    }
    let reply = bridge::call(world, &op, args);
    if let Some(err) = reply.get("error") {
        let kind = err.get("kind").and_then(Json::as_str).unwrap_or("RuntimeError");
        let kind = if RAISABLE.contains(&kind) { kind } else { "RuntimeError" };
        return Err(PyError::new(err.get("message").and_then(Json::as_str).unwrap_or(""), kind));
    }
    Ok(reply.get("value").map(json_to_value).unwrap_or(Value::None))
}

/// The host of an http(s) address, lowercased.
fn url_host(url: &str) -> Option<String> {
    let rest = url.trim().split_once("://")?.1;
    let authority = rest.split(['/', '?', '#']).next()?;
    let host = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
    let host = host.split(':').next()?.trim_end_matches('.').to_ascii_lowercase();
    (!host.is_empty()).then_some(host)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_brackets_and_strings_ask_for_more() {
        assert_eq!(scan("x = 1"), Scan { open: false, block: false });
        assert_eq!(scan("def f():"), Scan { open: false, block: true });
        assert_eq!(scan("for i in range(3): print(i)"), Scan { open: false, block: true });
        assert_eq!(scan("x = [1,"), Scan { open: true, block: false });
        assert_eq!(scan("x = {\n  'a': 1,\n}"), Scan { open: false, block: false });
        assert_eq!(scan("s = \"\"\"one"), Scan { open: true, block: false });
        assert_eq!(scan("s = 'a:'  # what: next"), Scan { open: false, block: false });
        assert_eq!(scan("x = 1 + \\"), Scan { open: true, block: false });
        assert_eq!(scan("@plugin.action('x')"), Scan { open: false, block: true });
        assert_eq!(scan("d = {1: 2}"), Scan { open: false, block: false });
        assert!(continues_block("else:"));
        assert!(continues_block("except ValueError as e:"));
        assert!(!continues_block("elsewhere = 1"));
    }

    #[test]
    fn the_prelude_reroutes_every_keyboard_call() {
        let mut n = 0;
        for (_, source, _) in v5::PRELUDE {
            if source.contains("_wm.call(") {
                n += 1;
                let r = rerouted(source);
                assert!(!r.contains("_wm.call("), "{r}");
                assert!(r.contains("import _wmpy_repl"), "{r}");
            }
        }
        assert!(n >= 2, "the prelude no longer calls _wm.call where the prompt expects it");
        assert_eq!(url_host("https://Api.Example.com:443/x?y"), Some("api.example.com".into()));
    }

    #[test]
    fn completion_finds_members_and_commands() {
        let state = Completion { base: PRELUDE.into(), host: Host { api: Some(5), ..Host::default() }, commands: true, ..Completion::default() };
        let (start, pairs) = complete_line(&state, "ui.lab", 6);
        assert_eq!(start, 3);
        assert!(pairs.iter().any(|p| p.replacement.starts_with("label")), "{:?}", pairs.iter().map(|p| &p.display).collect::<Vec<_>>());
        let (start, pairs) = complete_line(&state, ":re", 3);
        assert_eq!(start, 0);
        let names: Vec<&str> = pairs.iter().map(|p| p.display.as_str()).collect();
        assert_eq!(names, vec![":reset", ":render"]);
        let state = Completion { members: vec![("helpers".into(), vec!["double".into(), "_hidden".into()])], globals: vec!["helpers".into(), "x".into()], ..state };
        let (start, pairs) = complete_line(&state, "y = helpers.dou", 15);
        assert_eq!((start, pairs.iter().map(|p| p.replacement.as_str()).collect::<Vec<_>>()), (12, vec!["double"]));
    }
}
