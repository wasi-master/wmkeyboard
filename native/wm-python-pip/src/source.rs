//! One `.py` file read the way the analysis and the shaker both need it: the
//! tooling's tolerant tree for structure, its tokens for every name a stretch
//! of code mentions, and line and column numbers for problems.

use wm_python_tooling::ast::{Expr, ExprKind, Stmt};
use wm_python_tooling::lexer::{fstring_fields, lex_range, TokenKind, Tokens, FSTRING};
use wm_python_tooling::text::Span;
use wm_python_tooling::Document;

pub(crate) struct Source {
    pub doc: Document,
}

/// A name as code uses it, found in the tokens.
#[derive(Clone, Debug)]
pub(crate) struct NameUse {
    pub name: String,
    pub offset: u32,
    /// `.name`: an attribute, not the name itself.
    pub after_dot: bool,
    /// The attribute names that follow it: `a.b.c` gives `["b", "c"]` for `a`.
    pub chain: Vec<String>,
    /// `f(name=...)` or a parameter default: a keyword, not a read.
    pub keyword: bool,
    /// Inside a string literal that is exactly this name.
    pub in_string: bool,
    /// Bound here, not read: `name = ...`, `global name`, `del name`, `for name in`, `as name`.
    pub binding: bool,
    /// Only compared or tested: `name is None`, `not name`, `name == x`.
    pub compared: bool,
}

impl Source {
    pub fn new(text: &str) -> Source {
        Source { doc: Document::new(text) }
    }

    pub fn units(&self) -> &[u16] {
        self.doc.units()
    }

    pub fn body(&self) -> &[Stmt] {
        &self.doc.module.body
    }

    pub fn text(&self, span: Span) -> String {
        wm_python_tooling::text::slice(self.units(), span)
    }

    /// 1-based line and 1-based column, in characters, of [offset].
    pub fn place(&self, offset: u32) -> (u32, u32) {
        let line = self.doc.lines.line_of(offset);
        let start = self.doc.lines.start(line);
        let units = self.units();
        let end = (offset as usize).min(units.len());
        let start = (start as usize).min(end);
        let col = String::from_utf16_lossy(&units[start..end]).chars().count();
        (line as u32 + 1, col as u32 + 1)
    }

    /// Line, column and end column (on the same line) of [span].
    pub fn range(&self, span: Span) -> (u32, u32, u32) {
        let (line, col) = self.place(span.start);
        let (end_line, end_col) = self.place(span.end);
        let end_col = if end_line == line { end_col.max(col + 1) } else { col + 1 };
        (line, col, end_col)
    }

    /// Whether [span] has its lines to itself: only blanks before it on its
    /// first line, and only blanks or a comment after it on its last.
    pub fn whole_lines(&self, span: Span) -> bool {
        let units = self.units();
        let lines = &self.doc.lines;
        let first = lines.line_of(span.start);
        let line_start = lines.start(first) as usize;
        if units[line_start..(span.start as usize).min(units.len())].iter().any(|c| !wm_python_tooling::text::is_blank(*c)) {
            return false;
        }
        let t = &self.doc.tokens;
        let Some(mut i) = t.index_at(span.end) else { return true };
        if t.start(i) < span.end {
            i += 1;
        }
        while i < t.len() {
            match t.kind(i) {
                TokenKind::Whitespace | TokenKind::Comment => i += 1,
                TokenKind::Newline => return true,
                _ => return false,
            }
        }
        true
    }

    /// The span of the lines [span] covers, from the start of its first line
    /// to the end of its last (before the line break), comments included.
    pub fn line_span(&self, span: Span) -> Span {
        let lines = &self.doc.lines;
        let first = lines.line_of(span.start);
        let last = lines.line_of(span.end.saturating_sub(1).max(span.start));
        Span::new(lines.start(first), lines.end(self.units(), last))
    }

    /// Every name the code in [span] mentions, with what follows it, skipping
    /// the tokens inside [skip] (import statements, whose names are bindings).
    pub fn names(&self, span: Span, skip: &[Span]) -> Vec<NameUse> {
        let mut out = Vec::new();
        let t = &self.doc.tokens;
        let Some(first) = t.index_at(span.start) else { return out };
        let mut last = first;
        while last + 1 < t.len() && t.start(last + 1) < span.end {
            last += 1;
        }
        scan(t, first, last, span, skip, false, 0, &mut out);
        out
    }
}

fn is_identifier(word: &str) -> bool {
    let mut chars = word.chars();
    chars.next().is_some_and(|c| c.is_alphabetic() || c == '_') && chars.all(|c| c.is_alphanumeric() || c == '_')
}

/// The text inside a string literal's quotes, when it has no escapes or prefix games.
fn plain_string_body(text: &str) -> Option<&str> {
    let t = text.trim_start_matches(|c: char| c.is_ascii_alphabetic());
    for q in ["\"\"\"", "'''", "\"", "'"] {
        if let Some(inner) = t.strip_prefix(q).and_then(|r| r.strip_suffix(q)) {
            return Some(inner);
        }
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn scan(t: &Tokens, first: usize, last: usize, span: Span, skip: &[Span], in_string: bool, depth: u32, out: &mut Vec<NameUse>) {
    let code: Vec<usize> = (first..=last.min(t.len().saturating_sub(1))).filter(|&i| t.is_code(i)).collect();
    for (k, &i) in code.iter().enumerate() {
        let start = t.start(i);
        if start < span.start || start >= span.end || skip.iter().any(|s| s.contains(start)) {
            continue;
        }
        match t.kind(i) {
            TokenKind::Name | TokenKind::SoftKeyword => {
                let name = t.text(i);
                let prev = k.checked_sub(1).map(|p| code[p]);
                let after_dot = prev.is_some_and(|p| t.is_op(p, "."));
                let next = code.get(k + 1).copied();
                let keyword = next.is_some_and(|n| t.is_op(n, "=")) && prev.is_some_and(|p| t.is_op(p, "(") || t.is_op(p, ","));
                // `global a, b`: walk back over the list to its keyword.
                let mut back = k;
                while back >= 2 && t.is_op(code[back - 1], ",") && t.kind(code[back - 2]) == TokenKind::Name {
                    back -= 2;
                }
                let declared = back >= 1 && (t.is_keyword(code[back - 1], "global") || t.is_keyword(code[back - 1], "nonlocal") || t.is_keyword(code[back - 1], "del"));
                let binding = (next.is_some_and(|n| t.is_op(n, "=")) && !keyword)
                    || declared
                    || prev.is_some_and(|p| t.is_keyword(p, "for") || t.is_keyword(p, "as"));
                let mut chain = Vec::new();
                let mut j = k + 1;
                while j + 1 < code.len() && t.is_op(code[j], ".") && t.kind(code[j + 1]) == TokenKind::Name {
                    chain.push(t.text(code[j + 1]));
                    j += 2;
                }
                let compares = |j: usize| t.is_keyword(j, "is") || t.is_keyword(j, "not") || t.is_op(j, "==") || t.is_op(j, "!=");
                let compared = chain.is_empty() && (next.is_some_and(compares) || prev.is_some_and(compares));
                out.push(NameUse { name, offset: start, after_dot, chain, keyword, in_string, binding, compared });
            }
            TokenKind::String => {
                if t.has(i, FSTRING) {
                    if depth < 8 {
                        for field in fstring_fields(t, i) {
                            let inner = lex_range(t.src.clone(), field.start, field.end);
                            if !inner.is_empty() {
                                scan(&inner, 0, inner.len() - 1, field, &[], in_string, depth + 1, out);
                            }
                        }
                    }
                } else if let Some(body) = plain_string_body(&t.text(i)) {
                    // `getattr(m, "name")`, `globals()["name"]`: a string that is
                    // exactly a name may be one.
                    if is_identifier(body) {
                        out.push(NameUse {
                            name: body.to_string(),
                            offset: start,
                            after_dot: false,
                            chain: Vec::new(),
                            keyword: false,
                            in_string: true,
                            binding: false,
                            compared: false,
                        });
                    }
                }
            }
            _ => {}
        }
    }
}

/// `a.b.c` as `("a", ["b", "c"], span of a)`.
pub(crate) fn attribute_chain(e: &Expr) -> Option<(String, Vec<String>, Span)> {
    match &e.kind {
        ExprKind::Name(n) => Some((n.clone(), Vec::new(), e.span)),
        ExprKind::Attribute { value, attr, .. } => {
            let (base, mut chain, span) = attribute_chain(value)?;
            chain.push(attr.clone());
            Some((base, chain, span))
        }
        _ => None,
    }
}
