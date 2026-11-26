//! Errors and problems as wmpy prints them: a headline, the place, and a
//! code frame under it, the way rustc prints, for every command to share.
//!
//! ```text
//! error[undefined-name]: name 'cunt' is not defined
//!   --> main.py:12:9
//!    |
//! 12 |     ui.title(cunt)
//!    |              ^^^^
//!    = help: did you mean 'count'?
//! ```

use std::fmt::Write;

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

/// One frame of a traceback, innermost last.
#[derive(Clone, Debug, Default)]
pub struct Frame {
    pub file: Option<String>,
    pub line: Option<u32>,
    pub function: Option<String>,
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
        d.line = err.get("line").and_then(|v| v.as_u64()).map(|l| l as u32);
        d.col = err.get("col").and_then(|v| v.as_u64()).map(|c| c as u32);
        d.file = s("file").or_else(|| d.line.map(|_| default_file.to_string()));
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
        d
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
        if self.traceback.len() > 1 {
            let _ = writeln!(out, "{}", paint("1", "traceback (most recent call last):"));
            for f in &self.traceback {
                let place = match (&f.file, f.line) {
                    (Some(file), Some(l)) => format!("{file}:{l}"),
                    (Some(file), None) => file.clone(),
                    (None, Some(l)) => format!("line {l}"),
                    (None, None) => "?".into(),
                };
                let _ = writeln!(out, "  {place} in {}", f.function.as_deref().unwrap_or("<module>"));
            }
        }
        for note in &self.notes {
            let _ = writeln!(out, "  {} note: {note}", paint("1;34", "="));
        }
        if let Some(help) = &self.help {
            let _ = writeln!(out, "  {} help: {help}", paint("1;34", "="));
        }
        out
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
