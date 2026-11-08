//! Errors and the interpreter's non-local control flow.

use std::fmt;

use super::value::Value;

/// Why the sandbox itself stopped a script. Aborts are uncatchable: `except`
/// never sees them, because catching them is how a runaway script would keep going.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AbortKind {
    /// The step budget ran out.
    Steps,
    /// The wall-clock deadline passed.
    Deadline,
    /// The host's cancel flag was set.
    Cancelled,
    /// Call depth (300), regex or JSON nesting, or parser nesting past its cap.
    Recursion,
    /// A list, set, dict, tuple or range grew past the container cap.
    ContainerCap,
    /// A string grew past the string cap.
    StringCap,
}

impl AbortKind {
    /// The lowercase wire name used in JSON results.
    pub fn wire(self) -> &'static str {
        match self {
            AbortKind::Steps => "steps",
            AbortKind::Deadline => "deadline",
            AbortKind::Cancelled => "cancelled",
            AbortKind::Recursion => "recursion",
            AbortKind::ContainerCap => "container",
            AbortKind::StringCap => "string",
        }
    }
}

/// A syntax or runtime error, with a 1-based line when one is known.
///
/// [kind] is the exception name an `except` clause matches. A host function
/// can return one: `PyError::new("need a URL", "ValueError")` arrives in the
/// script as a ValueError it can handle.
#[derive(Clone, Debug, PartialEq)]
pub struct PyError {
    /// The message alone, without kind or line.
    pub message: String,
    /// The Python exception kind, e.g. "ValueError". PyMini's default is "RuntimeError".
    pub kind: String,
    pub line: Option<u32>,
    /// Set for the sandbox's own stops; those report kind "SystemExit".
    pub abort: Option<AbortKind>,
}

impl PyError {
    pub fn new(message: impl Into<String>, kind: impl Into<String>) -> Self {
        PyError { message: message.into(), kind: kind.into(), line: None, abort: None }
    }

    pub(crate) fn at(message: impl Into<String>, kind: &str, line: Option<u32>) -> Self {
        PyError { message: message.into(), kind: kind.to_string(), line, abort: None }
    }

    /// PyMini's default kind, RuntimeError, which prints without a prefix.
    pub(crate) fn perr(message: impl Into<String>, line: u32) -> Self {
        PyError::at(message, "RuntimeError", Some(line))
    }

    pub(crate) fn abort(kind: AbortKind, message: impl Into<String>, line: Option<u32>) -> Self {
        PyError { message: message.into(), kind: "SystemExit".into(), line, abort: Some(kind) }
    }

    pub fn is_abort(&self) -> bool {
        self.abort.is_some()
    }

    /// One line for a person, e.g. `Line 3: NameError: name 'foo' is not defined`.
    pub fn description(&self) -> String {
        let body = if self.abort.is_some() || self.kind == "RuntimeError" {
            self.message.clone()
        } else {
            format!("{}: {}", self.kind, self.message)
        };
        match self.line {
            Some(l) => format!("Line {l}: {body}"),
            None => body,
        }
    }

    pub(crate) fn with_line(mut self, line: u32) -> Self {
        self.line = Some(line);
        self
    }
}

impl fmt::Display for PyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.description())
    }
}

impl std::error::Error for PyError {}

/// Everything that unwinds the evaluator: errors, and `return`/`break`/`continue`.
pub(crate) enum Exc {
    Error(Box<PyError>),
    Break,
    Continue,
    Return(Value),
}

impl From<PyError> for Exc {
    fn from(e: PyError) -> Self {
        Exc::Error(Box::new(e))
    }
}

pub(crate) type R<T> = Result<T, Exc>;
