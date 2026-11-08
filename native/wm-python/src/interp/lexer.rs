//! Port of PyLexer.kt. Turns source text into tokens, with Python's significant
//! indentation (synthetic INDENT, DEDENT and NEWLINE tokens) and f-strings split
//! into their pieces. Newlines inside (), [] and {} are ignored, and `#` starts
//! a comment. Like PyMini's lexer it walks grapheme clusters.

use std::rc::Rc;

use super::error::PyError;
use super::text::{char_is_hex_digit, char_is_letter, char_is_number, graphemes, parse_swift_double, parse_swift_int, trim_whitespaces};

/// One segment of an f-string as the lexer sees it; the expression stays raw source.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum FRawPart {
    Lit(String),
    Expr { source: String, conv: Option<String>, spec: Option<Vec<FRawPart>> },
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Tok {
    Int(i64),
    Float(f64),
    Str(String),
    FString(Vec<FRawPart>),
    Name(Rc<str>),
    Keyword(&'static str),
    Op(&'static str),
    Newline,
    Indent,
    Dedent,
    Eof,
}

/// A token plus its 1-based source line.
#[derive(Clone, Debug)]
pub(crate) struct Token {
    pub kind: Tok,
    pub line: u32,
}

const KEYWORDS: &[&str] = &[
    "if", "elif", "else", "while", "for", "in", "def", "return", "break", "continue", "pass", "True", "False", "None",
    "and", "or", "not", "lambda", "try", "except", "finally", "raise", "import", "from", "as", "is", "del", "global",
];

// Longest first, so `**=` and `//=` beat `**` and `//`.
const MULTI_OPS: &[&str] = &["**=", "//=", "**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%="];
const SINGLE_OPS: &[&str] =
    &["+", "-", "*", "/", "%", "<", ">", "=", "(", ")", ",", ":", ".", "[", "]", "{", "}", "&", "|", "^", "~", ";"];

fn keyword(word: &str) -> Option<&'static str> {
    KEYWORDS.iter().find(|k| **k == word).copied()
}

fn op(s: &str) -> Option<&'static str> {
    MULTI_OPS.iter().chain(SINGLE_OPS.iter()).find(|k| **k == s).copied()
}

fn perr(message: impl Into<String>, line: u32) -> PyError {
    PyError::perr(message, line)
}

/// Scan the whole source. Always ends with `Eof`.
pub(crate) fn tokenize(source: &str) -> Result<Vec<Token>, PyError> {
    let normalised = source.replace("\r\n", "\n").replace('\r', "\n");
    let chars = graphemes(&normalised);
    let mut lexer = Lexer {
        chars: &chars,
        pos: 0,
        line: 1,
        indent_stack: vec![0],
        bracket_depth: 0,
        tokens: Vec::new(),
        at_line_start: true,
    };
    lexer.run()?;
    Ok(lexer.tokens)
}

struct Lexer<'a> {
    chars: &'a [&'a str],
    pos: usize,
    line: u32,
    indent_stack: Vec<usize>,
    bracket_depth: usize,
    tokens: Vec<Token>,
    at_line_start: bool,
}

/// Split one placeholder's raw text into expression, `!conversion` and `:spec`.
fn placeholder(body: &str, line: u32) -> Result<FRawPart, PyError> {
    let chars = graphemes(body);
    let mut depth: i64 = 0;
    let mut quote: Option<&str> = None;
    let mut conv: Option<String> = None;
    let mut expr_end = chars.len();
    let mut spec_start: Option<usize> = None;
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if let Some(q) = quote {
            if c == "\\" {
                i += 2;
                continue;
            }
            if c == q {
                quote = None;
            }
        } else if c == "\"" || c == "'" {
            quote = Some(c);
        } else if c == "{" || c == "[" || c == "(" {
            depth += 1;
        } else if c == "}" || c == "]" || c == ")" {
            depth -= 1;
        } else if depth == 0 && c == "!" && i + 1 < chars.len() && chars[i + 1] != "=" {
            // `!r` or `!s`, but only when it ends the expression.
            let next = chars[i + 1];
            let after = if i + 2 < chars.len() { chars[i + 2] } else { ":" };
            if ((next == "r" || next == "s" || next == "a") && after == ":") || i + 2 == chars.len() {
                conv = Some(next.to_string());
                expr_end = expr_end.min(i);
                i += 2;
                continue;
            }
        } else if depth == 0 && c == ":" {
            expr_end = expr_end.min(i);
            spec_start = Some(i + 1);
            break;
        }
        i += 1;
    }
    let source = trim_whitespaces(&chars[..expr_end.min(chars.len())].concat()).to_string();
    if source.is_empty() {
        return Err(perr("empty expression in f-string", line));
    }
    let spec = match spec_start {
        Some(s) => Some(spec_parts(&chars[s.min(chars.len())..].concat(), line)?),
        None => None,
    };
    Ok(FRawPart::Expr { source, conv, spec })
}

/// A format spec can interpolate too, as in `{value:>{width}}`.
fn spec_parts(spec: &str, line: u32) -> Result<Vec<FRawPart>, PyError> {
    let mut parts = Vec::new();
    let mut lit = String::new();
    let chars = graphemes(spec);
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == "{" {
            if !lit.is_empty() {
                parts.push(FRawPart::Lit(std::mem::take(&mut lit)));
            }
            let mut depth = 1;
            let mut inner = String::new();
            i += 1;
            while i < chars.len() && depth > 0 {
                if chars[i] == "{" {
                    depth += 1;
                } else if chars[i] == "}" {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
                inner.push_str(chars[i]);
                i += 1;
            }
            if i >= chars.len() {
                return Err(perr("unterminated format spec", line));
            }
            i += 1;
            parts.push(placeholder(&inner, line)?);
            continue;
        }
        lit.push_str(chars[i]);
        i += 1;
    }
    if !lit.is_empty() {
        parts.push(FRawPart::Lit(lit));
    }
    Ok(parts)
}

impl<'a> Lexer<'a> {
    fn peek(&self, n: usize) -> Option<&'a str> {
        self.chars.get(self.pos + n).copied()
    }

    fn push(&mut self, kind: Tok, line: u32) {
        self.tokens.push(Token { kind, line });
    }

    fn run(&mut self) -> Result<(), PyError> {
        while self.pos < self.chars.len() {
            if self.at_line_start && self.bracket_depth == 0 {
                self.handle_line_start()?;
                if self.pos >= self.chars.len() {
                    break;
                }
            }
            let c = self.chars[self.pos];
            if c == "\n" {
                self.pos += 1;
                self.line += 1;
                if self.bracket_depth == 0 {
                    self.emit_newline();
                    self.at_line_start = true;
                }
                continue;
            }
            if c == " " || c == "\t" {
                self.pos += 1;
                continue;
            }
            if c == "#" {
                while self.pos < self.chars.len() && self.chars[self.pos] != "\n" {
                    self.pos += 1;
                }
                continue;
            }
            // A backslash at end of line joins the next physical line.
            if c == "\\" && self.peek(1) == Some("\n") {
                self.pos += 2;
                self.line += 1;
                continue;
            }
            if let Some((is_f, raw)) = self.string_prefix() {
                if is_f {
                    self.lex_fstring(raw)?;
                } else {
                    self.lex_string(raw)?;
                }
                continue;
            }
            if c == "\"" || c == "'" {
                self.lex_string(false)?;
                continue;
            }
            if char_is_number(c) || (c == "." && self.peek(1).is_some_and(char_is_number)) {
                self.lex_number()?;
                continue;
            }
            if char_is_letter(c) || c == "_" {
                self.lex_identifier();
                continue;
            }
            self.lex_operator()?;
        }
        // Close out any open indentation, then EOF.
        if self.tokens.last().is_some_and(|t| t.kind != Tok::Newline) {
            self.emit_newline();
        }
        while self.indent_stack.len() > 1 {
            self.indent_stack.pop();
            self.push(Tok::Dedent, self.line);
        }
        self.push(Tok::Eof, self.line);
        Ok(())
    }

    /// Recognise a `f"…"` / `r'…'` / `rf"…"` prefix and consume it: (fstring, raw).
    fn string_prefix(&mut self) -> Option<(bool, bool)> {
        let mut n = 0;
        let mut is_f = false;
        let mut is_r = false;
        while let Some(c) = self.peek(n) {
            if !matches!(c, "f" | "F" | "r" | "R") || n >= 2 {
                break;
            }
            if c == "f" || c == "F" {
                if is_f {
                    return None;
                }
                is_f = true;
            } else {
                if is_r {
                    return None;
                }
                is_r = true;
            }
            n += 1;
        }
        let quote = self.peek(n);
        if n == 0 || (quote != Some("\"") && quote != Some("'")) {
            return None;
        }
        self.pos += n;
        Some((is_f, is_r))
    }

    fn handle_line_start(&mut self) -> Result<(), PyError> {
        let mut width = 0;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c == " " {
                width += 1;
                self.pos += 1;
            } else if c == "\t" {
                width += 8 - (width % 8);
                self.pos += 1;
            } else {
                break;
            }
        }
        // Blank or comment-only line: not significant for indentation.
        if self.pos >= self.chars.len() || self.chars[self.pos] == "\n" || self.chars[self.pos] == "#" {
            self.at_line_start = false;
            return Ok(());
        }
        self.at_line_start = false;
        let current = *self.indent_stack.last().unwrap();
        if width > current {
            self.indent_stack.push(width);
            self.push(Tok::Indent, self.line);
        } else if width < current {
            while !self.indent_stack.is_empty() && width < *self.indent_stack.last().unwrap() {
                self.indent_stack.pop();
                self.push(Tok::Dedent, self.line);
            }
            if self.indent_stack.last() != Some(&width) {
                return Err(perr("inconsistent indentation", self.line));
            }
        }
        Ok(())
    }

    fn emit_newline(&mut self) {
        // Collapse runs of blank lines into a single logical NEWLINE.
        match self.tokens.last() {
            None => {}
            Some(t) if t.kind == Tok::Newline || t.kind == Tok::Indent => {}
            Some(_) => self.push(Tok::Newline, self.line),
        }
    }

    fn lex_number(&mut self) -> Result<(), PyError> {
        // Base-prefixed integers: 0x1f, 0o17, 0b1010.
        if self.chars[self.pos] == "0" {
            if let Some(p) = self.peek(1) {
                if matches!(p, "x" | "X" | "o" | "O" | "b" | "B") {
                    let radix = match p {
                        "x" | "X" => 16,
                        "o" | "O" => 8,
                        _ => 2,
                    };
                    self.pos += 2;
                    let mut digits = String::new();
                    while self.pos < self.chars.len()
                        && (char_is_hex_digit(self.chars[self.pos]) || self.chars[self.pos] == "_")
                    {
                        if self.chars[self.pos] != "_" {
                            digits.push_str(self.chars[self.pos]);
                        }
                        self.pos += 1;
                    }
                    let v = parse_swift_int(&digits, radix).ok_or_else(|| perr("invalid number", self.line))?;
                    self.push(Tok::Int(v), self.line);
                    return Ok(());
                }
            }
        }
        let mut s = String::new();
        let mut is_double = false;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if char_is_number(c) {
                s.push_str(c);
                self.pos += 1;
            } else if c == "_" {
                self.pos += 1;
            } else if c == "." {
                if is_double {
                    break;
                }
                is_double = true;
                s.push_str(c);
                self.pos += 1;
            } else if c == "e" || c == "E" {
                // Only an exponent when a digit or sign follows.
                let next = self.peek(1);
                let signed = next == Some("+") || next == Some("-");
                let ok = next.is_some_and(char_is_number) || (signed && self.peek(2).is_some_and(char_is_number));
                if !ok {
                    break;
                }
                is_double = true;
                s.push_str(c);
                self.pos += 1;
                if signed {
                    s.push_str(self.chars[self.pos]);
                    self.pos += 1;
                }
            } else {
                break;
            }
        }
        if is_double {
            let d = parse_swift_double(&s).ok_or_else(|| perr(format!("invalid number '{s}'"), self.line))?;
            self.push(Tok::Float(d), self.line);
        } else if let Some(i) = parse_swift_int(&s, 10) {
            self.push(Tok::Int(i), self.line);
        } else {
            let d = parse_swift_double(&s).ok_or_else(|| perr(format!("invalid number '{s}'"), self.line))?;
            self.push(Tok::Float(d), self.line);
        }
        Ok(())
    }

    /// Consume a single quote or a matching triple-quote delimiter: (quote, length).
    fn open_string(&mut self) -> (&'a str, usize) {
        let quote = self.chars[self.pos];
        let length = if self.peek(1) == Some(quote) && self.peek(2) == Some(quote) { 3 } else { 1 };
        self.pos += length;
        (quote, length)
    }

    fn closes_string(&self, quote: &str, length: usize) -> bool {
        self.chars[self.pos] == quote && (length == 1 || (self.peek(1) == Some(quote) && self.peek(2) == Some(quote)))
    }

    /// A backslash followed by a newline joins lines without adding text. In a raw
    /// string the backslash and the character after it are both kept.
    fn scan_escape(&mut self, raw: bool) -> String {
        self.pos += 1;
        if self.pos >= self.chars.len() {
            return if raw { "\\".into() } else { String::new() };
        }
        let c = self.chars[self.pos];
        self.pos += 1;
        if c == "\n" {
            self.line += 1;
            return if raw { "\\\n".into() } else { String::new() };
        }
        if raw {
            return format!("\\{c}");
        }
        // Unrecognised escapes keep the backslash, as Python does, so ¯\_(ツ)_/¯ survives.
        match c {
            "n" => "\n".into(),
            "t" => "\t".into(),
            "r" => "\r".into(),
            "0" => "\0".into(),
            "\\" => "\\".into(),
            "'" => "'".into(),
            "\"" => "\"".into(),
            _ => format!("\\{c}"),
        }
    }

    fn lex_string(&mut self, raw: bool) -> Result<(), PyError> {
        let start_line = self.line;
        let (quote, length) = self.open_string();
        let mut s = String::new();
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c == "\\" {
                s.push_str(&self.scan_escape(raw));
            } else if self.closes_string(quote, length) {
                self.pos += length;
                self.push(Tok::Str(s), start_line);
                return Ok(());
            } else {
                if c == "\n" {
                    if length != 3 {
                        return Err(perr("unterminated string", start_line));
                    }
                    self.line += 1;
                }
                s.push_str(c);
                self.pos += 1;
            }
        }
        Err(perr("unterminated string", start_line))
    }

    /// Lex `f"..."` into literal runs and `{expr!conv:spec}` placeholders.
    fn lex_fstring(&mut self, raw: bool) -> Result<(), PyError> {
        let start_line = self.line;
        let (quote, length) = self.open_string();
        let mut parts = Vec::new();
        let mut lit = String::new();
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c == "\\" {
                lit.push_str(&self.scan_escape(raw));
                continue;
            }
            if self.closes_string(quote, length) {
                self.pos += length;
                if !lit.is_empty() {
                    parts.push(FRawPart::Lit(lit));
                }
                self.push(Tok::FString(parts), start_line);
                return Ok(());
            }
            if c == "{" {
                if self.peek(1) == Some("{") {
                    lit.push('{');
                    self.pos += 2;
                    continue;
                }
                if !lit.is_empty() {
                    parts.push(FRawPart::Lit(std::mem::take(&mut lit)));
                }
                self.pos += 1;
                let body = self.scan_placeholder()?;
                parts.push(placeholder(&body, self.line)?);
                continue;
            }
            if c == "}" {
                if self.peek(1) == Some("}") {
                    lit.push('}');
                    self.pos += 2;
                    continue;
                }
                return Err(perr("single '}' in f-string", self.line));
            }
            if c == "\n" {
                if length != 3 {
                    return Err(perr("unterminated string", start_line));
                }
                self.line += 1;
            }
            lit.push_str(c);
            self.pos += 1;
        }
        Err(perr("unterminated string", start_line))
    }

    /// Read the raw text of one `{...}` placeholder, minding nesting and quotes.
    fn scan_placeholder(&mut self) -> Result<String, PyError> {
        let mut src = String::new();
        let mut depth = 1;
        let mut quote: Option<&str> = None;
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if c == "\n" {
                self.line += 1;
            }
            if let Some(q) = quote {
                if c == "\\" {
                    src.push_str(c);
                    self.pos += 1;
                    if self.pos < self.chars.len() {
                        src.push_str(self.chars[self.pos]);
                        self.pos += 1;
                    }
                    continue;
                }
                if c == q {
                    quote = None;
                }
            } else if c == "\"" || c == "'" {
                quote = Some(c);
            } else if c == "{" || c == "[" || c == "(" {
                depth += 1;
            } else if c == "]" || c == ")" {
                depth -= 1;
            } else if c == "}" {
                depth -= 1;
                if depth == 0 {
                    self.pos += 1;
                    return Ok(src);
                }
            }
            src.push_str(c);
            self.pos += 1;
        }
        Err(perr("unterminated f-string expression", self.line))
    }

    fn lex_identifier(&mut self) {
        let mut s = String::new();
        while self.pos < self.chars.len() {
            let c = self.chars[self.pos];
            if char_is_letter(c) || char_is_number(c) || c == "_" {
                s.push_str(c);
                self.pos += 1;
            } else {
                break;
            }
        }
        let kind = match keyword(&s) {
            Some(k) => Tok::Keyword(k),
            None => Tok::Name(Rc::from(s)),
        };
        self.push(kind, self.line);
    }

    fn lex_operator(&mut self) -> Result<(), PyError> {
        for width in [3, 2] {
            if self.pos + width > self.chars.len() {
                continue;
            }
            let slice = self.chars[self.pos..self.pos + width].concat();
            if MULTI_OPS.contains(&slice.as_str()) {
                self.push(Tok::Op(op(&slice).unwrap()), self.line);
                self.pos += width;
                return Ok(());
            }
        }
        let c = self.chars[self.pos];
        let Some(o) = SINGLE_OPS.iter().find(|k| **k == c).copied() else {
            return Err(perr(format!("unexpected character '{c}'"), self.line));
        };
        match c {
            "(" | "[" | "{" => self.bracket_depth += 1,
            ")" | "]" | "}" => self.bracket_depth = self.bracket_depth.saturating_sub(1),
            _ => {}
        }
        self.push(Tok::Op(o), self.line);
        self.pos += 1;
        Ok(())
    }
}
