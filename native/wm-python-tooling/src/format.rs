//! Tidies a script by changing whitespace only. Every edit replaces a run of
//! spaces, tabs or line breaks; no other character is touched, so a comment,
//! a string or a number comes out exactly as it went in.
//!
//! * Indentation becomes four spaces a level, tabs included. A line inside
//!   brackets keeps its place relative to the line its statement starts on; a
//!   comment line takes the level of the code it sits with.
//! * Trailing whitespace goes, and the file ends with one line break.
//! * Blank lines: two before and after a top-level `def` (counting a comment
//!   block written directly above it as part of it), one before a nested
//!   `def`, and otherwise at most two at the top level and one inside blocks.
//! * One space after a comma; one around `=` in a statement, augmented
//!   assignments, comparisons and binary arithmetic, but none around a keyword
//!   argument's or default's `=`, and nothing changed inside `[ ]`, where
//!   slices make spacing a matter of taste.
//!
//! A file whose indentation or brackets are broken only loses trailing
//! whitespace; one with a syntax error is re-indented but not re-spaced. Lines
//! inside a triple-quoted string are copied exactly. Formatting twice gives
//! the same text as formatting once.

use std::collections::HashMap;

use crate::document::Document;
use crate::lexer::{TokenKind, Tokens, CONTINUATION, UNTERMINATED};
use crate::structure::IndentIssue;
use crate::text::{is_blank, units_of};

pub fn format(source: &str) -> String {
    let doc = Document::new(source);
    let units = doc.units();
    if units.is_empty() {
        return String::new();
    }
    let out = Formatter::new(&doc).run();
    String::from_utf16_lossy(&out)
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum LineKind {
    Blank,
    /// Starts inside a string that began on an earlier line.
    InString,
    /// Starts logical line `.0`.
    Code(usize),
    Comment,
    /// Inside brackets or after a backslash.
    Continuation,
}

struct Line {
    start: usize,
    /// Before the line break.
    end: usize,
    kind: LineKind,
    /// Old width of the leading whitespace, and where it ends.
    width: u32,
    content: usize,
    /// The new indentation width, when it changes.
    indent: Option<u32>,
    /// The new depth for code and comment lines.
    depth: usize,
}

struct Formatter<'a> {
    doc: &'a Document,
    t: &'a Tokens,
    units: &'a [u16],
    lines: Vec<Line>,
    /// Edits: start, end, replacement. Non-overlapping once sorted.
    edits: Vec<(usize, usize, Vec<u16>)>,
    newline: Vec<u16>,
}

fn width_of(units: &[u16]) -> u32 {
    let mut w = 0;
    for &c in units {
        if c == b'\t' as u16 {
            w += 8 - w % 8;
        } else {
            w += 1;
        }
    }
    w
}

impl<'a> Formatter<'a> {
    fn new(doc: &'a Document) -> Formatter<'a> {
        let units = doc.units();
        let newline = {
            let mut nl = units_of("\n").to_vec();
            if let Some(i) = units.iter().position(|c| *c == b'\n' as u16 || *c == b'\r' as u16) {
                if units[i] == b'\r' as u16 {
                    nl = if units.get(i + 1) == Some(&(b'\n' as u16)) { units_of("\r\n").to_vec() } else { units_of("\r").to_vec() };
                }
            }
            nl
        };
        Formatter { doc, t: &doc.tokens, units, lines: Vec::new(), edits: Vec::new(), newline }
    }

    fn run(mut self) -> Vec<u16> {
        self.classify();
        let s = &self.doc.structure;
        let broken = !s.bracket_issues.is_empty()
            || s.indent_issues.iter().any(|(_, i)| matches!(i, IndentIssue::Unexpected | IndentIssue::Inconsistent));
        let parses = self.doc.parses() && !broken;
        if !broken {
            self.depths();
            self.indent_edits();
        }
        self.trailing_edits();
        if parses {
            self.blank_lines();
            self.spacing();
        }
        self.apply()
    }

    fn classify(&mut self) {
        let t = self.t;
        let units = self.units;
        let mut first_of: HashMap<usize, usize> = HashMap::new();
        for k in 0..self.doc.structure.lines.len() {
            first_of.insert(self.doc.structure.first(k), k);
        }
        let mut start = 0;
        while start <= units.len() {
            let mut end = start;
            while end < units.len() && units[end] != b'\n' as u16 && units[end] != b'\r' as u16 {
                end += 1;
            }
            let next = if end < units.len() {
                if units[end] == b'\r' as u16 && units.get(end + 1) == Some(&(b'\n' as u16)) { end + 2 } else { end + 1 }
            } else {
                end
            };
            let mut content = start;
            while content < end && is_blank(units[content]) {
                content += 1;
            }
            let token = t.index_at(start as u32);
            let in_string = token.is_some_and(|i| (t.start(i) as usize) < start && t.kind(i) == TokenKind::String);
            let kind = if in_string {
                LineKind::InString
            } else if content == end {
                LineKind::Blank
            } else {
                let first = t.index_at(content as u32).unwrap_or(0);
                match t.kind(first) {
                    TokenKind::Comment => LineKind::Comment,
                    _ => match first_of.get(&first) {
                        Some(k) => LineKind::Code(*k),
                        None => LineKind::Continuation,
                    },
                }
            };
            self.lines.push(Line {
                start,
                end,
                kind,
                width: width_of(&units[start..content]),
                content,
                indent: None,
                depth: 0,
            });
            if next == end {
                break;
            }
            start = next;
        }
    }

    /// The block depth of each logical line from its old indentation, and of each comment line.
    fn depths(&mut self) {
        let s = &self.doc.structure;
        let t = self.t;
        let mut stack: Vec<u32> = vec![0];
        let mut depth_of_logical = vec![0usize; s.lines.len()];
        for (k, line) in s.lines.iter().enumerate() {
            let w = line.indent;
            if w > *stack.last().unwrap() {
                stack.push(w);
            }
            while stack.len() > 1 && w < *stack.last().unwrap() {
                stack.pop();
            }
            depth_of_logical[k] = stack.len() - 1;
        }
        // Comments: the deepest open level whose old width they reach.
        let mut open: Vec<u32> = vec![0];
        let code_widths: Vec<u32> = s.lines.iter().map(|l| l.indent).collect();
        let mut current: Option<usize> = None;
        for i in 0..self.lines.len() {
            match self.lines[i].kind {
                LineKind::Code(k) => {
                    current = Some(k);
                    let w = code_widths[k];
                    if w > *open.last().unwrap() {
                        open.push(w);
                    }
                    while open.len() > 1 && w < *open.last().unwrap() {
                        open.pop();
                    }
                    self.lines[i].depth = depth_of_logical[k];
                }
                LineKind::Comment => {
                    let mut levels = open.clone();
                    if let Some(k) = current {
                        let header = s.is_header(t, k);
                        if let Some(next) = code_widths.get(k + 1) {
                            if header && *next > *levels.last().unwrap() {
                                levels.push(*next);
                            }
                        }
                    }
                    let w = self.lines[i].width;
                    let depth = levels.iter().rposition(|lw| *lw <= w).unwrap_or(0);
                    self.lines[i].depth = depth;
                }
                _ => {}
            }
        }
    }

    fn indent_edits(&mut self) {
        let mut logical_old = 0u32;
        let mut logical_new = 0u32;
        for i in 0..self.lines.len() {
            let line = &self.lines[i];
            let new = match line.kind {
                LineKind::Code(_) => {
                    logical_old = line.width;
                    logical_new = line.depth as u32 * 4;
                    logical_new
                }
                LineKind::Comment => line.depth as u32 * 4,
                LineKind::Continuation => (line.width + logical_new).saturating_sub(logical_old),
                LineKind::Blank | LineKind::InString => continue,
            };
            let (start, content) = (line.start, line.content);
            let old = &self.units[start..content];
            let same = old.len() == new as usize && old.iter().all(|c| *c == b' ' as u16);
            self.lines[i].indent = Some(new);
            if !same {
                self.edits.push((start, content, vec![b' ' as u16; new as usize]));
            }
        }
    }

    fn trailing_edits(&mut self) {
        let t = self.t;
        for line in &self.lines {
            if line.kind == LineKind::Blank || line.end == line.start {
                continue;
            }
            // A line ending inside a string keeps its spaces: they are part of the string.
            if let Some(i) = t.index_at(line.end.saturating_sub(1) as u32) {
                if t.kind(i) == TokenKind::String && (t.end(i) as usize > line.end || t.has(i, UNTERMINATED)) {
                    continue;
                }
            }
            let mut cut = line.end;
            while cut > line.content && is_blank(self.units[cut - 1]) {
                cut -= 1;
            }
            if cut < line.end && cut >= line.content {
                self.edits.push((cut, line.end, Vec::new()));
            }
        }
    }

    // ---- blank lines -----------------------------------------------------

    fn is_def(&self, i: usize) -> bool {
        match self.lines[i].kind {
            LineKind::Code(k) => {
                let first = self.doc.structure.first(k);
                self.t.is_keyword(first, "def") || self.t.is_keyword(first, "async") || self.t.is_keyword(first, "class")
            }
            _ => false,
        }
    }

    fn blank_lines(&mut self) {
        let n = self.lines.len();
        // The number of blank lines wanted before line i, when a rule asks for an exact number.
        let mut wanted: Vec<Option<usize>> = vec![None; n];
        let content: Vec<bool> = self.lines.iter().map(|l| l.kind != LineKind::Blank).collect();
        let prev_content = |i: usize| (0..i).rev().find(|j| content[*j]);
        for i in 0..n {
            if !self.is_def(i) {
                continue;
            }
            let depth = self.lines[i].depth;
            // The comment block written directly above it belongs to it.
            let mut top = i;
            while top > 0 && self.lines[top - 1].kind == LineKind::Comment && self.lines[top - 1].depth == depth {
                top -= 1;
            }
            let before = prev_content(top);
            match before {
                None => wanted[top] = Some(0),
                Some(p) => {
                    let after_header = matches!(self.lines[p].kind, LineKind::Code(k) if self.doc.structure.is_header(self.t, k))
                        && self.lines[p].depth < depth;
                    let decorated = matches!(self.lines[p].kind, LineKind::Code(k) if self.t.is_op(self.doc.structure.first(k), "@"));
                    wanted[top] = Some(if decorated || after_header { 0 } else if depth == 0 { 2 } else { 1 });
                }
            }
            if depth == 0 {
                // The first top-level line after the def's body.
                if let Some(after) = (i + 1..n).find(|j| content[*j] && self.lines[*j].kind != LineKind::InString && self.lines[*j].kind != LineKind::Continuation && self.lines[*j].depth == 0) {
                    wanted[after].get_or_insert(2);
                }
            }
        }
        if let Some(first) = (0..n).find(|j| content[*j]) {
            wanted[first] = Some(0);
        }
        // Apply: replace each run of blank lines before a content line.
        let mut i = 0;
        while i < n {
            if !content[i] {
                i += 1;
                continue;
            }
            let run_start = (0..i).rev().take_while(|j| !content[*j]).last();
            let have = run_start.map_or(0, |s| i - s);
            let cap = if self.lines[i].depth == 0 && matches!(self.lines[i].kind, LineKind::Code(_) | LineKind::Comment) { 2 } else { 1 };
            let want = match wanted[i] {
                Some(w) => w,
                None => have.min(cap),
            };
            if want != have {
                let from = run_start.map_or(self.lines[i].start, |s| self.lines[s].start);
                let to = self.lines[i].start;
                // Blank lines' own whitespace edits fall inside the run; drop them.
                self.edits.retain(|(s, e, _)| !(*s >= from && *e <= to && *s < to));
                let mut text = Vec::new();
                for _ in 0..want {
                    text.extend_from_slice(&self.newline);
                }
                self.edits.push((from, to, text));
            }
            i += 1;
        }
        // The end of the file: no blank lines, one line break.
        let last = (0..n).rev().find(|j| content[*j]);
        let ends_in_string = (0..self.t.len()).last().is_some_and(|k| self.t.kind(k) == TokenKind::String && self.t.has(k, UNTERMINATED));
        if let (Some(last), false) = (last, ends_in_string) {
            let from = self.lines[last].end;
            if from < self.units.len() || self.units.is_empty() || from == self.units.len() {
                self.edits.retain(|(s, _, _)| *s < from);
                self.edits.push((from, self.units.len(), self.newline.clone()));
            }
        }
    }

    // ---- spacing ---------------------------------------------------------

    fn spacing(&mut self) {
        let t = self.t;
        let mut stack: Vec<u8> = Vec::new();
        let mut lambdas: Vec<usize> = Vec::new();
        let mut prev: Option<usize> = None;
        for i in 0..t.len() {
            if !t.is_code(i) {
                if t.kind(i) == TokenKind::Newline && !t.has(i, CONTINUATION) {
                    prev = None;
                    stack.clear();
                    lambdas.clear();
                }
                continue;
            }
            let text = t.text(i);
            let innermost = stack.last().copied();
            if let Some(p) = prev {
                let p_text = t.text(p);
                let wanted = self.gap(p, &p_text, i, &text, innermost, &lambdas, stack.len());
                if let Some(w) = wanted {
                    self.set_gap(p, i, w);
                }
            }
            if t.kind(i) == TokenKind::Operator {
                match text.as_str() {
                    "(" => stack.push(b'('),
                    "[" => stack.push(b'['),
                    "{" => stack.push(b'{'),
                    ")" | "]" | "}" => {
                        stack.pop();
                    }
                    ":" if lambdas.last() == Some(&stack.len()) => {
                        lambdas.pop();
                    }
                    _ => {}
                }
            } else if t.is_keyword(i, "lambda") {
                lambdas.push(stack.len());
            }
            prev = Some(i);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn gap(&self, p: usize, p_text: &str, i: usize, text: &str, innermost: Option<u8>, lambdas: &[usize], depth: usize) -> Option<usize> {
        let t = self.t;
        let is_op = |k: usize| t.kind(k) == TokenKind::Operator;
        if is_op(i) && text == "," {
            return Some(0);
        }
        if is_op(p) && p_text == "," {
            return if is_op(i) && matches!(text, ")" | "]" | "}") { None } else { Some(1) };
        }
        let in_lambda_header = lambdas.last() == Some(&depth);
        let spaced = |op: &str, operator_token: usize| -> Option<usize> {
            match op {
                "=" => Some(if depth == 0 && !in_lambda_header { 1 } else { 0 }),
                "+=" | "-=" | "*=" | "/=" | "//=" | "%=" | "**=" | "&=" | "|=" | "^=" | ">>=" | "<<=" | "@=" => Some(1),
                "==" | "!=" | "<" | ">" | "<=" | ">=" => Some(1),
                "+" | "-" | "*" | "/" | "//" | "%" => {
                    if innermost == Some(b'[') || !self.binary(operator_token) {
                        None
                    } else {
                        Some(1)
                    }
                }
                _ => None,
            }
        };
        if is_op(i) {
            if let Some(w) = spaced(text, i) {
                return Some(w);
            }
        }
        if is_op(p) {
            if let Some(w) = spaced(p_text, p) {
                return Some(w);
            }
        }
        None
    }

    /// Whether the arithmetic operator at [op] joins two operands rather than signing one.
    fn binary(&self, op: usize) -> bool {
        let t = self.t;
        let Some(before) = t.prev_code(op) else { return false };
        match t.kind(before) {
            TokenKind::Name | TokenKind::Number | TokenKind::String => true,
            TokenKind::Keyword => t.is(before, "True") || t.is(before, "False") || t.is(before, "None"),
            TokenKind::Operator => t.is(before, ")") || t.is(before, "]") || t.is(before, "}"),
            _ => false,
        }
    }

    /// Sets the whitespace between adjacent code tokens on one line to [width] spaces.
    fn set_gap(&mut self, a: usize, b: usize, width: usize) {
        let t = self.t;
        let from = t.end(a) as usize;
        let to = t.start(b) as usize;
        for k in a + 1..b {
            if t.kind(k) != TokenKind::Whitespace || t.has(k, CONTINUATION) {
                return;
            }
        }
        let gap = &self.units[from..to];
        if gap.len() == width && gap.iter().all(|c| *c == b' ' as u16) {
            return;
        }
        self.edits.push((from, to, vec![b' ' as u16; width]));
    }

    fn apply(mut self) -> Vec<u16> {
        self.edits.sort_by_key(|(s, e, _)| (*s, *e));
        let mut out = Vec::with_capacity(self.units.len() + 64);
        let mut at = 0;
        for (s, e, text) in &self.edits {
            if *s < at {
                continue;
            }
            out.extend_from_slice(&self.units[at..*s]);
            out.extend_from_slice(text);
            at = *e;
        }
        out.extend_from_slice(&self.units[at.min(self.units.len())..]);
        out
    }
}
