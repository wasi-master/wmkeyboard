//! Errors and problems as wmpy prints them: a headline, the place, and a
//! code frame under it, the way rustc prints, for every command to share.
//!
//! ```text
//! warning[name_typo]: “cunt” is not defined. Did you mean “count”?
//!   --> main.py:12:14
//!    |
//! 12 |     ui.title(cunt)
//!    |              ^^^^
//! ```
//!
//! Codes are the language service's (`name_typo`), the manifest checker's
//! (`unknown_permission`), or a Python exception's kind (`ValueError`).

use std::fmt::Write;

use serde_json::{json, Value as Json};
use wm_python::interp::PyError;
use wm_python_tooling::diagnostics::Diagnostic;
use wm_python_tooling::manifest::{Problem, DOCS};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    Error,
    Warning,
    Info,
}

impl Severity {
    pub fn word(self) -> &'static str {
        match self {
            Severity::Error => "error",
            Severity::Warning => "warning",
            Severity::Info => "note",
        }
    }
}

/// One frame of a traceback, outermost first and innermost last. [file] is
/// None for the modules that come with the keyboard (the standard library,
/// the `wm` package and its host side).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Frame {
    pub file: Option<String>,
    pub line: Option<u32>,
    pub function: Option<String>,
}

impl Frame {
    fn of(f: &wm_python::interp::Frame) -> Frame {
        Frame { file: f.file.clone(), line: f.line, function: Some(f.function.clone()) }
    }
}

/// One problem, wherever it came from: the checker, the manifest, or a run.
#[derive(Clone, Debug)]
pub struct Diag {
    pub severity: Severity,
    /// A short id such as `undefined-name` or `ValueError`.
    pub code: Option<String>,
    pub message: String,
    /// Relative to the plugin folder, such as `main.py` or `plugin.json`.
    pub file: Option<String>,
    /// 1-based.
    pub line: Option<u32>,
    /// 1-based, in characters.
    pub col: Option<u32>,
    /// 1-based, exclusive, on the same line.
    pub end_col: Option<u32>,
    pub traceback: Vec<Frame>,
    pub notes: Vec<String>,
    pub help: Option<String>,
}

impl Diag {
    pub fn new(severity: Severity, message: impl Into<String>) -> Diag {
        Diag { severity, code: None, message: message.into(), file: None, line: None, col: None, end_col: None, traceback: Vec::new(), notes: Vec::new(), help: None }
    }

    pub fn error(message: impl Into<String>) -> Diag {
        Diag::new(Severity::Error, message)
    }

    /// An error a plugin session reported: `{"kind", "message", "line",
    /// "exception", "file"?, "traceback"?: [{"file", "line", "function"}]}`.
    /// `file` and `traceback` are read when present; [default_file] is the
    /// file to blame when the session does not say (the entry script).
    pub fn from_session_error(err: &serde_json::Value, default_file: &str) -> Diag {
        let s = |k: &str| err.get(k).and_then(|v| v.as_str()).map(String::from);
        let mut d = Diag::error(s("message").unwrap_or_else(|| "the plugin failed".into()));
        d.code = s("exception").filter(|e| !e.is_empty());
        // The session writes "NameError: name 'x' ..." and also names the
        // kind; the headline shows the kind once, as the code.
        if let Some(rest) = d.code.as_deref().and_then(|c| d.message.strip_prefix(c)).and_then(|r| r.strip_prefix(": ")) {
            d.message = rest.to_string();
        }
        d.line = err.get("line").and_then(|v| v.as_u64()).map(|l| l as u32);
        d.col = err.get("col").and_then(|v| v.as_u64()).map(|c| c as u32);
        d.file = s("file");
        if let Some(frames) = err.get("traceback").and_then(|v| v.as_array()) {
            d.traceback = frames
                .iter()
                .map(|f| Frame {
                    file: f.get("file").and_then(|v| v.as_str()).map(String::from),
                    line: f.get("line").and_then(|v| v.as_u64()).map(|l| l as u32),
                    function: f.get("function").and_then(|v| v.as_str()).map(String::from),
                })
                .collect();
        }
        d.line_from_frame();
        if d.file.is_none() && !d.blame_own_frame() && d.traceback.is_empty() {
            d.file = d.line.map(|_| default_file.to_string());
        }
        d.help = hint(d.code.as_deref().unwrap_or(""), &d.message);
        d
    }

    /// An error the interpreter raised while running [file] (the file whose
    /// code was running at the top: a test, or the entry script), with a hint
    /// when the error is a common one. The error's own file and frames win
    /// when the runtime named its modules' files.
    pub fn from_py_error(e: &PyError, file: &str) -> Diag {
        let mut d = Diag::error(e.message.clone());
        d.code = Some(e.kind.clone()).filter(|k| !k.is_empty());
        d.line = e.line;
        d.file = e.file.clone();
        d.traceback = e.frames.iter().map(Frame::of).collect();
        d.line_from_frame();
        if d.file.is_none() && !d.blame_own_frame() {
            if !d.traceback.is_empty() {
                // Frames, none in a file of the plugin's: the line is in a
                // module that came with the keyboard, not in [file].
                d.line = None;
            }
            d.file = Some(file.to_string());
        }
        d.help = hint(&e.kind, &e.message);
        d
    }

    /// A session runs the entry as `main.py` whatever plugin.json calls it:
    /// give its frames the entry's name, unless the plugin has a real
    /// main.py of its own beside another entry.
    pub fn name_entry(&mut self, entry: &str, dir: &std::path::Path) {
        const SESSION_ENTRY: &str = "main.py";
        if entry == SESSION_ENTRY || dir.join(SESSION_ENTRY).exists() {
            return;
        }
        let rename = |f: &mut Option<String>| {
            if f.as_deref() == Some(SESSION_ENTRY) {
                *f = Some(entry.to_string());
            }
        };
        rename(&mut self.file);
        self.traceback.iter_mut().for_each(|f| rename(&mut f.file));
    }

    /// A native function (json.loads) raising leaves the error without a
    /// line; the innermost frame, the call to it, has one.
    fn line_from_frame(&mut self) {
        if self.line.is_some() || self.file.is_none() {
            return;
        }
        if let Some(last) = self.traceback.last().filter(|f| f.file == self.file) {
            self.line = last.line;
        }
    }

    /// An error raised inside a module that comes with the keyboard has no
    /// file: point at the innermost call in one of the plugin's own files
    /// instead, which is the line its author can change, and say where it
    /// came out. False when there is no such frame.
    fn blame_own_frame(&mut self) -> bool {
        let Some(own) = self.traceback.iter().rev().find(|f| f.file.is_some()).cloned() else { return false };
        self.file = own.file;
        self.line = own.line;
        self.col = None;
        self.end_col = None;
        if let Some(function) = self.traceback.last().filter(|f| f.file.is_none()).and_then(|f| f.function.as_deref()).filter(|f| *f != "<module>") {
            self.notes.push(format!("raised inside {function}(), in a module that comes with the keyboard"));
        }
        true
    }

    /// A problem the language service found in [file], whose text is [source].
    pub fn from_tooling(t: &Diagnostic, file: &str, source: &str) -> Diag {
        let severity = match t.severity() {
            wm_python_tooling::diagnostics::Severity::Error => Severity::Error,
            wm_python_tooling::diagnostics::Severity::Warning => Severity::Warning,
            wm_python_tooling::diagnostics::Severity::Info => Severity::Info,
        };
        // The model keeps the API 5 package under `wm5`; people write `wm`.
        let mut d = Diag::new(severity, t.text().replace("“wm5.", "“wm.").replace("“wm5”", "“wm”"));
        d.code = Some(t.code.name().to_string());
        d.file = Some(file.to_string());
        let (line, col, end_col) = place(source, t.span.start, t.span.end);
        d.line = Some(line);
        d.col = Some(col);
        d.end_col = end_col;
        if t.code.name() == "will_not_run" {
            d.help = Some(format!("the keyboard refuses to load a file with this error: {DOCS}/plugins/wm-package/#the-python-you-get"));
        } else if let Some(fix) = &t.fix {
            d.help = Some(fix_help(fix));
        }
        d
    }

    /// A manifest or archive problem; [manifest] is plugin.json's text, for the place.
    pub fn from_manifest(p: &Problem, manifest: &str) -> Diag {
        let severity = match p.severity {
            wm_python_tooling::diagnostics::Severity::Error => Severity::Error,
            wm_python_tooling::diagnostics::Severity::Warning => Severity::Warning,
            wm_python_tooling::diagnostics::Severity::Info => Severity::Info,
        };
        let mut d = Diag::new(severity, p.message.clone());
        d.code = Some(p.code.to_string());
        d.file = Some(p.file.clone());
        if let (Some(span), true) = (p.span, p.file == wm_python_tooling::manifest::MANIFEST) {
            let (line, col, end_col) = place(manifest, span.start, span.end);
            d.line = Some(line);
            d.col = Some(col);
            d.end_col = end_col;
        }
        if let Some(app) = &p.app_message {
            d.notes.push(format!("the app refuses it: {app}"));
        }
        d.help = p.help.clone();
        d
    }

    /// The shared JSON shape: `{"severity", "code", "message", "file", "line",
    /// "col", "endCol", "traceback": [{"file", "line", "function"}], "notes", "help"}`.
    pub fn to_json(&self) -> Json {
        json!({
            "severity": match self.severity { Severity::Error => "error", Severity::Warning => "warning", Severity::Info => "info" },
            "code": self.code, "message": self.message, "file": self.file, "line": self.line, "col": self.col, "endCol": self.end_col,
            "traceback": self.traceback.iter().map(|f| json!({"file": f.file, "line": f.line, "function": f.function})).collect::<Vec<_>>(),
            "notes": self.notes, "help": self.help,
        })
    }

    /// The text to print. [source] gives a file's text by its relative path,
    /// for the code frame; [color] adds ANSI colours.
    pub fn render(&self, source: &dyn Fn(&str) -> Option<String>, color: bool) -> String {
        let paint = |code: &str, text: &str| if color { format!("\x1b[{code}m{text}\x1b[0m") } else { text.to_string() };
        let sev_color = match self.severity {
            Severity::Error => "1;31",
            Severity::Warning => "1;33",
            Severity::Info => "1;36",
        };
        let mut out = String::new();
        let head = match &self.code {
            Some(code) => format!("{}[{code}]", self.severity.word()),
            None => self.severity.word().to_string(),
        };
        let _ = writeln!(out, "{}{} {}", paint(sev_color, &head), paint("1", ":"), paint("1", &self.message));
        if let Some(file) = &self.file {
            let place = match (self.line, self.col) {
                (Some(l), Some(c)) => format!("{file}:{l}:{c}"),
                (Some(l), None) => format!("{file}:{l}"),
                _ => file.clone(),
            };
            let line_text = self.line.and_then(|l| source(file).and_then(|t| t.lines().nth(l.saturating_sub(1) as usize).map(String::from)));
            let gutter = self.line.map(|l| l.to_string().len()).unwrap_or(1);
            let pad = " ".repeat(gutter);
            let _ = writeln!(out, "{pad}{} {place}", paint("1;34", "-->"));
            if let (Some(l), Some(text)) = (self.line, line_text) {
                let _ = writeln!(out, "{pad} {}", paint("1;34", "|"));
                let _ = writeln!(out, "{} {} {text}", paint("1;34", &l.to_string()), paint("1;34", "|"));
                if let Some(c) = self.col {
                    let start = c.saturating_sub(1) as usize;
                    let width = self.end_col.map(|e| (e as usize).saturating_sub(c as usize).max(1)).unwrap_or(1);
                    // Keep tabs in the indent so the carets line up under them.
                    let indent: String = text.chars().take(start).map(|ch| if ch == '\t' { '\t' } else { ' ' }).collect();
                    let _ = writeln!(out, "{pad} {} {indent}{}", paint("1;34", "|"), paint(sev_color, &"^".repeat(width)));
                }
            }
        }
        self.render_traceback(&mut out, source, &paint);
        for note in &self.notes {
            let _ = writeln!(out, "  {} note: {note}", paint("1;34", "="));
        }
        if let Some(help) = &self.help {
            let _ = writeln!(out, "  {} help: {help}", paint("1;34", "="));
        }
        out
    }

    /// The calls that led to the error, as Python lists them, in the plugin's
    /// own files only: the frames of the modules that come with the keyboard
    /// (the `wm` host calling `render`, a standard module raising) are not
    /// its author's to read. Nothing when the one frame is the headline's.
    /// A call repeated in a row (runaway recursion) shows once with a count.
    fn render_traceback(&self, out: &mut String, source: &dyn Fn(&str) -> Option<String>, paint: &dyn Fn(&str, &str) -> String) {
        let own: Vec<&Frame> = self.traceback.iter().filter(|f| f.file.is_some()).collect();
        let at_headline = |f: &Frame| f.file == self.file && f.line == self.line;
        if own.is_empty() || (own.len() == 1 && at_headline(own[0])) {
            return;
        }
        let _ = writeln!(out, "{}", paint("1", "traceback (most recent call last):"));
        if self.traceback.len() >= wm_python::interp::MAX_FRAMES {
            let _ = writeln!(out, "  … the calls before these are left out");
        }
        let mut texts: std::collections::HashMap<&str, Option<String>> = std::collections::HashMap::new();
        let mut i = 0;
        while i < own.len() {
            let f = own[i];
            let repeats = own[i..].iter().take_while(|g| **g == f).count();
            let file = f.file.as_deref().unwrap_or("");
            let place = f.line.map_or_else(|| file.to_string(), |l| format!("{file}:{l}"));
            let _ = writeln!(out, "  {place} in {}", f.function.as_deref().unwrap_or("<module>"));
            let last = i + repeats == own.len();
            if !(last && at_headline(f)) {
                let text = texts.entry(file).or_insert_with(|| source(file));
                if let Some(code) = f.line.and_then(|l| text.as_deref()?.lines().nth(l.saturating_sub(1) as usize)).map(str::trim).filter(|t| !t.is_empty()) {
                    let _ = writeln!(out, "      {code}");
                }
            }
            if repeats > 1 {
                let _ = writeln!(out, "  … the same call {} more time{}", repeats - 1, if repeats == 2 { "" } else { "s" });
            }
            i += repeats;
        }
    }
}

/// Whether stderr is a terminal that takes colour (and NO_COLOR is unset).
pub fn color_stderr() -> bool {
    use std::io::IsTerminal;
    std::env::var_os("NO_COLOR").is_none() && std::io::stderr().is_terminal()
}

/// A source lookup over a plugin folder, for [Diag::render].
pub fn folder_source(dir: &std::path::Path) -> impl Fn(&str) -> Option<String> + '_ {
    move |name: &str| crate::project::inside(dir, name).ok().and_then(|p| std::fs::read_to_string(p).ok())
}

/// One line on what to do about a common error, linking the docs.
pub fn hint(kind: &str, message: &str) -> Option<String> {
    let quoted = |after: &str| -> Option<String> {
        let rest = &message[message.find(after)? + after.len()..];
        let rest = rest.trim_start_matches(['\'', '"', '“', ' ']);
        let end = rest.find(['\'', '"', '”']).unwrap_or(rest.len());
        Some(rest[..end].to_string()).filter(|s| !s.is_empty())
    };
    let help = format!("{DOCS}/plugins/help/#error-messages");
    if message.contains("persist=True) needs the 'storage' permission") {
        return Some(format!("add \"storage\" to the permissions in plugin.json: {help}"));
    }
    if kind == "PermissionError" {
        if let Some(p) = quoted("needs the ") {
            return Some(format!("add \"{p}\" to the permissions in plugin.json: {help}"));
        }
        if message.contains("not allowed to connect to") {
            let host = message.rsplit(' ').next().unwrap_or("").trim_end_matches('.');
            return Some(format!("add \"{host}\" to \"hosts\" in plugin.json: {DOCS}/plugins/permissions/#hosts"));
        }
        if message.contains("has not turned on") {
            return Some(format!("the editors and the tests treat permissions as granted; on the phone the user turns it on: {help}"));
        }
        return Some(help);
    }
    if kind == "NotImplementedError" {
        if let Some(op) = message.strip_prefix("no mock reply for ") {
            return Some(format!("give the session an answer for it: plugin_test.session(mock={{\"{op}\": ...}}): {DOCS}/plugins/guides/testing/#write-tests"));
        }
        if message.starts_with("no mock route for ") {
            return Some(format!("no \"when\" in the mock's routes fits this call: {DOCS}/plugins/guides/testing/#a-different-answer-for-each-address"));
        }
    }
    if kind == "SystemExit" || message.contains("too much work") || message.contains("took too long") {
        return Some(format!("a loop ran too long, or the work belongs in smaller pieces: {DOCS}/plugins/guides/testing/#reading-an-error"));
    }
    if kind == "ImportError" || kind == "ModuleNotFoundError" {
        return Some(format!("plugins import the bundled modules, the wm package and their own .py files: {DOCS}/plugins/wm-package/#the-python-you-get"));
    }
    if kind == "ConnectionError" && message.contains("Data saver") {
        return Some(help);
    }
    None
}

/// 1-based line, column and end column (in characters, the end on the same
/// line or None) of the UTF-16 span [start]..[end] in [text].
pub fn place(text: &str, start: u32, end: u32) -> (u32, u32, Option<u32>) {
    let (mut line, mut col) = (1u32, 1u32);
    let mut at = 0u32;
    let mut start_place = None;
    for c in text.chars() {
        if start_place.is_none() && at >= start {
            start_place = Some((line, col));
        }
        if at >= end {
            break;
        }
        if start_place.is_some() && c == '\n' {
            // The span goes on past this line: mark to its end.
            let (l, s) = start_place.unwrap_or((line, col));
            return (l, s, Some(col.max(s + 1)));
        }
        at += c.len_utf16() as u32;
        if c == '\n' {
            line += 1;
            col = 1;
        } else {
            col += 1;
        }
    }
    let (l, s) = start_place.unwrap_or((line, col));
    let e = if end > start { Some(col.max(s + 1)) } else { None };
    (l, s, e)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_session_error_with_a_file_and_traceback_shows_both() {
        let err = serde_json::json!({
            "kind": "ValueError", "exception": "ValueError", "message": "bad fare", "line": 2, "file": "fares.py",
            "traceback": [{"file": "main.py", "line": 9, "function": "render"}, {"file": "fares.py", "line": 2, "function": "total"}],
        });
        let d = Diag::from_session_error(&err, "main.py");
        assert_eq!((d.file.as_deref(), d.line), (Some("fares.py"), Some(2)));
        let text = d.render(&|f| (f == "fares.py").then(|| "def total(xs):\n    raise ValueError('bad fare')\n".to_string()), false);
        assert!(text.contains("--> fares.py:2") && text.contains("raise ValueError('bad fare')"), "{text}");
        assert!(text.contains("traceback (most recent call last):\n  main.py:9 in render\n  fares.py:2 in total"), "{text}");
        let j = d.to_json();
        assert_eq!(j["traceback"][1]["function"], "total");
        assert_eq!(j["severity"], "error");
        assert_eq!(j["code"], "ValueError");
    }

    #[test]
    fn an_error_raised_in_a_bundled_module_points_at_the_plugins_call() {
        // requests' raise_for_status raised: its frame has no file, and the
        // session's line is the prelude's.
        let err = serde_json::json!({
            "kind": "runtime", "exception": "HTTPError", "message": "HTTPError: 404 Client Error", "line": 120, "file": null,
            "traceback": [
                {"file": null, "line": 40, "function": "_render"},
                {"file": "main.py", "line": 5, "function": "render"},
                {"file": null, "line": 120, "function": "Response.raise_for_status"},
            ],
        });
        let d = Diag::from_session_error(&err, "main.py");
        assert_eq!((d.file.as_deref(), d.line, d.message.as_str()), (Some("main.py"), Some(5), "404 Client Error"));
        assert!(d.notes.iter().any(|n| n.contains("Response.raise_for_status()")), "{:?}", d.notes);
        let text = d.render(&|_| Some("import requests\n\n\ndef render():\n    requests.get(URL).raise_for_status()\n".into()), false);
        assert!(text.contains("--> main.py:5") && !text.contains("traceback"), "one frame of the plugin's, the headline's: {text}");
        assert!(!text.contains("_render"), "the host's frames stay out: {text}");
    }

    #[test]
    fn a_test_failure_names_the_module_it_came_from_and_folds_recursion() {
        use wm_python::interp::Frame as F;
        let frame = |file: &str, line: u32, function: &str| F { file: Some(file.into()), line: Some(line), function: function.into() };
        let mut e = PyError::new("n went below zero", "ValueError");
        e.file = Some("helpers.py".into());
        e.line = Some(3);
        e.frames = vec![frame("test_count.py", 4, "test_down")];
        e.frames.extend(std::iter::repeat_n(frame("helpers.py", 4, "down"), 5));
        e.frames.push(frame("helpers.py", 3, "down"));
        let d = Diag::from_py_error(&e, "test_count.py");
        assert_eq!((d.file.as_deref(), d.line), (Some("helpers.py"), Some(3)));
        let helpers = "def down(n):\n    if n < 0:\n        raise ValueError('n went below zero')\n    return down(n - 1)\n";
        let tests = "import helpers\n\ndef test_down():\n    helpers.down(4)\n";
        let text = d.render(&|f| match f { "helpers.py" => Some(helpers.into()), "test_count.py" => Some(tests.into()), _ => None }, false);
        let want = "traceback (most recent call last):\n  test_count.py:4 in test_down\n      helpers.down(4)\n  helpers.py:4 in down\n      return down(n - 1)\n  … the same call 4 more times\n  helpers.py:3 in down\n";
        assert!(text.contains(want), "{text}");
    }

    #[test]
    fn an_error_without_frames_keeps_the_file_it_was_run_as() {
        let mut e = PyError::new("expected ':'", "SyntaxError");
        e.line = Some(2);
        let d = Diag::from_py_error(&e, "test_x.py");
        assert_eq!((d.file.as_deref(), d.line), (Some("test_x.py"), Some(2)));
    }

    #[test]
    fn places_count_characters() {
        let text = "a = 1\nb = “x”\n";
        // “x” starts at UTF-16 offset 10 and is 3 units long.
        assert_eq!(place(text, 10, 13), (2, 5, Some(8)));
        assert_eq!(place(text, 0, 0), (1, 1, None));
        assert_eq!(place(text, 4, 100), (1, 5, Some(6)));
    }

    #[test]
    fn hints_for_common_errors() {
        assert!(hint("PermissionError", "http.request needs the 'network' permission, which this plugin's manifest does not list").unwrap().contains("add \"network\""));
        assert!(hint("PermissionError", "this plugin is not allowed to connect to example.com").unwrap().contains("add \"example.com\" to \"hosts\""));
        assert!(hint("NotImplementedError", "no mock reply for ai.ask").unwrap().contains("mock={\"ai.ask\": ...}"));
        assert!(hint("ValueError", "case 7 gave 8").is_none());
    }
}

/// What a fix does, in words.
pub fn fix_help(fix: &wm_python_tooling::diagnostics::Fix) -> String {
    use wm_python_tooling::diagnostics::Fix;
    match fix {
        Fix::Replace(t) => format!("change it to “{t}”"),
        Fix::Insert(_, t) => {
            let line = t.trim().lines().next().unwrap_or("").to_string();
            if line.starts_with("import ") || line.starts_with("from ") {
                format!("add “{line}”")
            } else {
                format!("add a stub: {line}")
            }
        }
        Fix::Permission(p) => format!("add the “{p}” permission to plugin.json"),
        Fix::Host(h) => format!("add “{h}” to plugin.json's hosts"),
        Fix::Provider(tool, id) => format!("declare the {tool} provider “{id}” in plugin.json"),
        Fix::Action(a) => format!("list the action “{a}” in plugin.json"),
        Fix::Keyword(k) => format!("set plugin.json's tool.keyword to “{k}”"),
        Fix::Setting(s) => format!("add the setting “{s}” to plugin.json"),
    }
}
