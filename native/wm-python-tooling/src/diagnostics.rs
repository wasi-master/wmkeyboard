//! Everything wrong with a script, as the plugin editor shows it.
//!
//! Each problem is a [`Code`] with a fixed severity, a span and up to two
//! arguments. The words live with the consumer (the app has one string
//! resource per code); [`Code::message`] is the English, with `{0}` and `{1}`
//! for the arguments, for the web editor and for tests.
//!
//! Lexical, indentation and bracket problems come from the tokens; syntax
//! errors and unsupported Python from the parser; the rest from the analysis,
//! in `checks.rs`. Checks that depend on what names mean stay quiet while the
//! file has a syntax error, because a half-typed line makes names look unused
//! or undefined that are neither.

use crate::checks;
use crate::document::{Document, Host};
use crate::lexer::{number_shape, TokenKind, FOREIGN_PREFIX, TRIPLE, UNTERMINATED};
use crate::structure::{BracketIssue, IndentIssue};
use crate::text::Span;

pub const MAX_DIAGNOSTICS: usize = 200;
const MAX_SHOWN: usize = 40;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Severity {
    Error,
    Warning,
    Info,
}

impl Severity {
    pub fn name(self) -> &'static str {
        match self {
            Severity::Error => "error",
            Severity::Warning => "warning",
            Severity::Info => "info",
        }
    }
}

macro_rules! codes {
    ($($variant:ident = $name:literal, $severity:ident, $message:literal;)*) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        pub enum Code { $($variant,)* }

        impl Code {
            pub const ALL: &'static [Code] = &[$(Code::$variant,)*];

            /// The snake_case name the app keys its string resources on.
            pub fn name(self) -> &'static str {
                match self { $(Code::$variant => $name,)* }
            }

            pub fn severity(self) -> Severity {
                match self { $(Code::$variant => Severity::$severity,)* }
            }

            /// The English message, `{0}` and `{1}` standing for the arguments.
            pub fn message(self) -> &'static str {
                match self { $(Code::$variant => $message,)* }
            }
        }
    };
}

codes! {
    // ---- the text ----------------------------------------------------------
    UnknownCharacter = "unknown_character", Error, "Python cannot read “{0}” here.";
    UnclosedString = "unclosed_string", Error, "This string is never closed before the end of the line.";
    UnclosedTripleString = "unclosed_triple_string", Error, "This triple-quoted string is never closed.";
    BadNumber = "bad_number", Error, "“{0}” is not a number Python can read.";
    UnclosedBracket = "unclosed_bracket", Error, "This “{0}” is never closed.";
    StrayCloser = "stray_closer", Error, "This “{0}” closes nothing.";
    WrongCloser = "wrong_closer", Error, "“{0}” does not close what is open here; “{1}” would.";
    UnexpectedIndent = "unexpected_indent", Error, "This line is indented further than the block it is in.";
    InconsistentDedent = "inconsistent_dedent", Error, "This line's indentation matches no block around it.";
    EmptyBlock = "empty_block", Error, "Nothing is indented under this “{0}”. Indent the next line, or write pass.";
    MixedIndentation = "mixed_indentation", Warning, "This indentation mixes tabs and spaces.";
    // ---- syntax ------------------------------------------------------------
    SyntaxExpected = "syntax_expected", Error, "“{0}” is not expected here. Expected {1}.";
    SyntaxAtEnd = "syntax_at_end", Error, "This line ends too soon. Expected {0}.";
    Unsupported = "unsupported", Error, "WM Keyboard plugins cannot use {0}.";
    WillNotRun = "will_not_run", Error, "This plugin will not run. {1}: {0}";
    // ---- names -------------------------------------------------------------
    UndefinedName = "undefined_name", Warning, "“{0}” is not defined.";
    NameTypo = "name_typo", Warning, "“{0}” is not defined. Did you mean “{1}”?";
    ModuleNotImported = "module_not_imported", Warning, "“{0}” is a module. Add “import {0}” at the top first.";
    RemovedBuiltin = "removed_builtin", Error, "“{0}” is not available to plugins: it could reach outside the plugin.";
    MissingBuiltin = "missing_builtin", Warning, "“{0}” is not one of the builtins plugins have.";
    ImportNotAvailable = "import_not_available", Error, "There is no module “{0}” here. Plugins can import math, json, re, time, random and sys.";
    UnusedLocal = "unused_local", Info, "“{0}” is assigned but never read.";
    UnusedParameter = "unused_parameter", Info, "The parameter “{0}” is never read.";
    AccidentalGlobal = "accidental_global", Warning, "This makes a new local “{0}” instead of changing the module's. Add “global {0}” at the top of the function.";
    ReplacesApi = "replaces_api", Warning, "This replaces “{0}”, the plugin API, for the whole plugin.";
    ShadowsBuiltin = "shadows_builtin", Info, "This hides the builtin “{0}” here.";
    // ---- the API -----------------------------------------------------------
    UnknownMember = "unknown_member", Warning, "“{0}” does not exist.";
    MemberTypo = "member_typo", Warning, "“{0}” does not exist. Did you mean “{1}”?";
    UseInstead = "use_instead", Warning, "“{0}” does not exist here. Use “{1}”.";
    NeverInApi = "never_in_api", Error, "“{0}” does not exist: plugins cannot see keystrokes or the file system.";
    StorageNotDeclared = "storage_not_declared", Warning, "wm.storage needs the storage permission in plugin.json.";
    RemindersNotDeclared = "reminders_not_declared", Warning, "wm.reminders needs the reminders permission in plugin.json.";
    SelectionNotDeclared = "selection_not_declared", Warning, "transform() runs selection actions, which need the selection permission in plugin.json.";
    PanelOnly = "panel_only", Warning, "“{0}” only works in a plugin with a panel.";
    // ---- the plugin contract -----------------------------------------------
    MissingRender = "missing_render", Error, "There is no render(). A plugin with a panel needs one.";
    MissingTransform = "missing_transform", Error, "plugin.json lists actions, but there is no transform(text, action) to run them.";
    RenderParameters = "render_parameters", Info, "render() is called with no arguments.";
    RenderReturnsNothing = "render_returns_nothing", Warning, "render() never returns a widget, so the panel stays empty.";
    OnEventSignature = "on_event_signature", Warning, "on_event is called with one argument, the event.";
    TransformSignature = "transform_signature", Warning, "transform is called with the text and the action's id.";
    UnknownEventType = "unknown_event_type", Warning, "No event has the type “{0}”.";
    EventTypeTypo = "event_type_typo", Warning, "No event has the type “{0}”. Did you mean “{1}”?";
    UnknownEventId = "unknown_event_id", Info, "No widget has the id “{0}”.";
    UnknownAction = "unknown_action", Warning, "plugin.json lists no action “{0}”.";
    ActionTypo = "action_typo", Warning, "plugin.json lists no action “{0}”. Did you mean “{1}”?";
    // ---- widgets -----------------------------------------------------------
    UnknownUiArgument = "unknown_ui_argument", Warning, "ui.{1} has no argument “{0}”.";
    UiArgumentTypo = "ui_argument_typo", Warning, "There is no argument “{0}” here. Did you mean “{1}”?";
    UiMissingArgument = "ui_missing_argument", Warning, "ui.{0} needs {1}.";
    TooManyUiArguments = "too_many_ui_arguments", Warning, "ui.{0} takes at most {1} arguments by position.";
    UnknownStyle = "unknown_style", Warning, "“{0}” is not a style of ui.{1}.";
    StyleTypo = "style_typo", Warning, "“{0}” is not a style. Did you mean “{1}”?";
    DuplicateId = "duplicate_id", Info, "Another widget already has the id “{0}”.";
    TooManyTabs = "too_many_tabs", Warning, "A tab strip shows at most 8 pages.";
    // ---- flow --------------------------------------------------------------
    LoopNeverEnds = "loop_never_ends", Warning, "This loop never ends: nothing in it breaks out or returns.";
    Unreachable = "unreachable", Warning, "This code never runs: it comes after a return, raise, break or continue.";
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Diagnostic {
    pub code: Code,
    pub span: Span,
    pub args: Vec<String>,
}

impl Diagnostic {
    pub fn new(code: Code, span: Span, args: &[&str]) -> Diagnostic {
        Diagnostic { code, span, args: args.iter().map(|s| s.to_string()).collect() }
    }

    pub fn severity(&self) -> Severity {
        self.code.severity()
    }

    /// The English message with its arguments filled in.
    pub fn text(&self) -> String {
        let mut text = self.code.message().to_string();
        for (i, arg) in self.args.iter().enumerate() {
            text = text.replace(&format!("{{{i}}}"), arg);
        }
        text
    }
}

/// The result of [`diagnose`]: the problems, and whether the interpreter's
/// verdict was shown (it is left out when the tooling already marks an error
/// on that line).
pub struct Report {
    pub diagnostics: Vec<Diagnostic>,
    pub verdict_used: bool,
}

pub fn diagnose(doc: &Document, host: &Host) -> Report {
    let mut out = Vec::new();
    lexical(doc, &mut out);
    structural(doc, &mut out);
    syntax(doc, &mut out);
    for (span, what) in &doc.module.unsupported {
        out.push(Diagnostic::new(Code::Unsupported, *span, &[what]));
    }
    checks::run(doc, host, &mut out);
    let verdict_used = verdict(doc, host, &mut out);
    let mut seen = std::collections::HashSet::new();
    out.retain(|d| seen.insert(d.clone()));
    out.sort_by_key(|d| (d.span.start, d.severity(), d.span.end));
    out.truncate(MAX_DIAGNOSTICS);
    Report { diagnostics: out, verdict_used }
}

fn lexical(doc: &Document, out: &mut Vec<Diagnostic>) {
    let t = &doc.tokens;
    let mut i = 0;
    while i < t.len() {
        let start = t.start(i);
        match t.kind(i) {
            TokenKind::Unknown => {
                let mut last = i;
                while last + 1 < t.len() && t.kind(last + 1) == TokenKind::Unknown {
                    last += 1;
                }
                let span = Span::new(start, t.end(last));
                let text: String = crate::text::slice(t.units(), span).chars().take(MAX_SHOWN).collect();
                out.push(Diagnostic::new(Code::UnknownCharacter, span, &[&text]));
                i = last;
            }
            TokenKind::String => {
                if t.has(i, UNTERMINATED) {
                    // Only the opening quotes are marked: the rest of the file is not the problem.
                    let units = t.units();
                    let mut q = start as usize;
                    while q < t.end(i) as usize && units[q] != b'"' as u16 && units[q] != b'\'' as u16 {
                        q += 1;
                    }
                    let quotes = if t.has(i, TRIPLE) { 3 } else { 1 };
                    let span = Span::new(start, (q as u32 + quotes).min(t.end(i)));
                    let code = if t.has(i, TRIPLE) { Code::UnclosedTripleString } else { Code::UnclosedString };
                    out.push(Diagnostic::new(code, span, &[]));
                }
                // Bytes literals run; only u"" strings are refused.
                let first = t.units()[start as usize] as u8;
                if t.has(i, FOREIGN_PREFIX) && (first == b'u' || first == b'U') {
                    out.push(Diagnostic::new(Code::Unsupported, Span::new(start, start + 1), &["u\"\" strings"]));
                }
            }
            TokenKind::Number => {
                let text = t.text(i);
                let (valid, complex) = number_shape(&text);
                if !valid {
                    let shown: String = text.chars().take(MAX_SHOWN).collect();
                    out.push(Diagnostic::new(Code::BadNumber, t.span(i), &[&shown]));
                } else if complex {
                    out.push(Diagnostic::new(Code::Unsupported, t.span(i), &["complex numbers"]));
                }
            }
            _ => {}
        }
        i += 1;
    }
}

fn structural(doc: &Document, out: &mut Vec<Diagnostic>) {
    let t = &doc.tokens;
    let s = &doc.structure;
    for issue in &s.bracket_issues {
        match *issue {
            BracketIssue::Unclosed(i) => out.push(Diagnostic::new(Code::UnclosedBracket, t.span(i), &[&t.text(i)])),
            BracketIssue::Stray(i) => out.push(Diagnostic::new(Code::StrayCloser, t.span(i), &[&t.text(i)])),
            BracketIssue::Wrong(closer, opener) => {
                let want = match t.text(opener).as_str() {
                    "(" => ")",
                    "[" => "]",
                    _ => "}",
                };
                out.push(Diagnostic::new(Code::WrongCloser, t.span(closer), &[&t.text(closer), want]));
            }
        }
    }
    for (line, issue) in &s.indent_issues {
        let first = s.first(*line);
        let l = &s.lines[*line];
        match issue {
            IndentIssue::Unexpected => out.push(Diagnostic::new(Code::UnexpectedIndent, t.span(first), &[])),
            IndentIssue::Inconsistent => out.push(Diagnostic::new(Code::InconsistentDedent, t.span(first), &[])),
            IndentIssue::EmptyBlock => {
                let span = t.span(first);
                out.push(Diagnostic::new(Code::EmptyBlock, span, &[&t.text(first)]));
            }
            IndentIssue::Mixed => {
                let span = if l.indent_span.is_empty() { t.span(first) } else { l.indent_span };
                out.push(Diagnostic::new(Code::MixedIndentation, span, &[]));
            }
        }
    }
}

fn syntax(doc: &Document, out: &mut Vec<Diagnostic>) {
    // A line already marked with a text or bracket error needs no second error from the parser.
    let marked: std::collections::HashSet<usize> = out
        .iter()
        .filter(|d| d.severity() == Severity::Error)
        .map(|d| doc.lines.line_of(d.span.start))
        .collect();
    let units = doc.units();
    for e in &doc.module.errors {
        if marked.contains(&doc.lines.line_of(e.span.start)) {
            continue;
        }
        let expected = e.expected.clone().unwrap_or_else(|| "<statement>".into());
        if e.at_end {
            out.push(Diagnostic::new(Code::SyntaxAtEnd, e.span, &[&expected]));
        } else {
            let found = if e.found.is_empty() { crate::text::slice(units, e.span) } else { e.found.clone() };
            out.push(Diagnostic::new(Code::SyntaxExpected, e.span, &[&found, &expected]));
        }
    }
}

/// Places the interpreter's verdict on its line, unless an error is already there.
fn verdict(doc: &Document, host: &Host, out: &mut Vec<Diagnostic>) -> bool {
    let Some(v) = &host.verdict else { return false };
    let units = doc.units();
    let span = match v.line {
        Some(line) if line >= 1 && line <= doc.lines.count() => doc.lines.trimmed(units, line),
        _ => doc.lines.trimmed(units, 1),
    };
    let line = doc.lines.line_of(span.start);
    let taken = out.iter().any(|d| d.severity() == Severity::Error && doc.lines.line_of(d.span.start) == line);
    if taken {
        return false;
    }
    let kind = if v.kind.is_empty() { "Error" } else { v.kind.as_str() };
    out.push(Diagnostic::new(Code::WillNotRun, span, &[&v.message, kind]));
    true
}
