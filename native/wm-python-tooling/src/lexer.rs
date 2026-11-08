//! A lossless, total lexer for the plugin editor.
//!
//! Built for text that is broken most of the time, because someone is typing
//! it. It never panics, it gives every UTF-16 unit to exactly one token, and an
//! unterminated construct stops where Python itself says the damage stops: a
//! one-quote string at the end of its line, a triple-quoted one at the end of
//! the file. An f-string is one token; the expressions inside it are lexed
//! again on demand by [`fstring_fields`] and [`lex_range`].

use crate::text::{code_point, eq_ascii, is_combining, slice, Span, Units};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum TokenKind {
    /// Spaces, tabs and form feeds, and the backslash of a line continuation.
    Whitespace = 0,
    /// One line break: `\n`, `\r\n` or `\r`. See [`CONTINUATION`].
    Newline = 1,
    Comment = 2,
    /// Any string literal, prefixes and quotes included. See the flags.
    String = 3,
    Number = 4,
    Name = 5,
    Keyword = 6,
    /// `match` or `case` opening a statement.
    SoftKeyword = 7,
    Operator = 8,
    /// A character that starts no Python token: `$`, `?`, a lone `!`.
    Unknown = 9,
}

impl TokenKind {
    pub fn code(self) -> i32 {
        self as u8 as i32
    }

    pub fn name(self) -> &'static str {
        match self {
            TokenKind::Whitespace => "whitespace",
            TokenKind::Newline => "newline",
            TokenKind::Comment => "comment",
            TokenKind::String => "string",
            TokenKind::Number => "number",
            TokenKind::Name => "name",
            TokenKind::Keyword => "keyword",
            TokenKind::SoftKeyword => "soft_keyword",
            TokenKind::Operator => "operator",
            TokenKind::Unknown => "unknown",
        }
    }
}

/// A string that reached the end of its line, or of the file, without closing.
pub const UNTERMINATED: u8 = 1;
/// An f-string.
pub const FSTRING: u8 = 2;
/// A triple-quoted string.
pub const TRIPLE: u8 = 4;
/// A raw string.
pub const RAW: u8 = 8;
/// A `b` or `u` prefixed string, which PyMini does not read.
pub const FOREIGN_PREFIX: u8 = 16;
/// A line break inside brackets or after a backslash: it ends no statement.
pub const CONTINUATION: u8 = 32;
/// A line break where brackets left open were given up on, because the next
/// line starts a statement. It ends the statement as if they had closed.
pub const RESET: u8 = 64;

/// Every Python 3 keyword. The ones PyMini does not run are still keywords,
/// so they colour as keywords and the parser can say they are not supported.
pub const KEYWORDS: &[&str] = &[
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
];

/// Keywords that only start a statement, never continue an expression. A line
/// inside open brackets that starts with one of these means the brackets were
/// left open by mistake.
const STATEMENT_ONLY: &[&str] = &[
    "def", "class", "return", "import", "from", "try", "except", "elif", "finally", "while", "with", "global",
    "nonlocal", "pass", "break", "continue", "raise", "del", "assert",
];

/// Operators, longest first within each starting character.
const OPERATORS: &[&str] = &[
    "**=", "//=", ">>=", "<<=", "...", "->", ":=", "**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=",
    "&=", "|=", "^=", "@=", "<<", ">>", "+", "-", "*", "/", "%", "<", ">", "=", "(", ")", "[", "]", "{", "}", ",", ":",
    ".", ";", "&", "|", "^", "~", "@",
];

/// Every token of one source, as parallel arrays: a 1,500-line plugin is
/// tens of thousands of tokens, re-lexed on every keystroke.
///
/// The tokens cover the source exactly: the first starts at its start, each one
/// ends where the next begins, and the last ends at its end.
#[derive(Clone, Debug)]
pub struct Tokens {
    pub src: Units,
    pub kinds: Vec<TokenKind>,
    pub starts: Vec<u32>,
    pub ends: Vec<u32>,
    pub flags: Vec<u8>,
}

impl Tokens {
    pub fn len(&self) -> usize {
        self.kinds.len()
    }

    pub fn is_empty(&self) -> bool {
        self.kinds.is_empty()
    }

    pub fn kind(&self, i: usize) -> TokenKind {
        self.kinds[i]
    }

    pub fn start(&self, i: usize) -> u32 {
        self.starts[i]
    }

    pub fn end(&self, i: usize) -> u32 {
        self.ends[i]
    }

    pub fn span(&self, i: usize) -> Span {
        Span::new(self.starts[i], self.ends[i])
    }

    pub fn has(&self, i: usize, flag: u8) -> bool {
        self.flags[i] & flag != 0
    }

    pub fn text(&self, i: usize) -> String {
        slice(&self.src, self.span(i))
    }

    /// Whether token [i] is exactly the ASCII [word], compared in place.
    pub fn is(&self, i: usize, word: &str) -> bool {
        i < self.len() && eq_ascii(&self.src, self.span(i), word)
    }

    /// Whether token [i] is the operator [op].
    pub fn is_op(&self, i: usize, op: &str) -> bool {
        i < self.len() && self.kinds[i] == TokenKind::Operator && self.is(i, op)
    }

    pub fn is_keyword(&self, i: usize, word: &str) -> bool {
        i < self.len() && matches!(self.kinds[i], TokenKind::Keyword | TokenKind::SoftKeyword) && self.is(i, word)
    }

    /// A token the parser reads: not layout and not a comment.
    pub fn is_code(&self, i: usize) -> bool {
        !matches!(self.kinds[i], TokenKind::Whitespace | TokenKind::Newline | TokenKind::Comment)
    }

    /// The token containing [offset], or the last one at the very end. None for an empty source.
    pub fn index_at(&self, offset: u32) -> Option<usize> {
        if self.is_empty() {
            return None;
        }
        let first = self.starts[0];
        let last = *self.ends.last().unwrap();
        if offset < first || offset > last {
            return None;
        }
        if offset == last {
            return Some(self.len() - 1);
        }
        let i = self.ends.partition_point(|end| *end <= offset);
        (i < self.len()).then_some(i)
    }

    pub fn next_code(&self, i: usize) -> Option<usize> {
        (i + 1..self.len()).find(|j| self.is_code(*j))
    }

    pub fn prev_code(&self, i: usize) -> Option<usize> {
        (0..i).rev().find(|j| self.is_code(*j))
    }

    /// The code token that ends at or before [offset].
    pub fn code_before(&self, offset: u32) -> Option<usize> {
        if offset == 0 {
            return None;
        }
        let i = self.index_at(offset - 1)?;
        if self.is_code(i) && self.start(i) < offset {
            Some(i)
        } else {
            self.prev_code(i)
        }
    }

    /// The units of the whole source, for code that indexes it directly.
    pub fn units(&self) -> &[u16] {
        &self.src
    }
}

pub fn lex_str(text: &str) -> Tokens {
    lex(crate::text::units_of(text))
}

pub fn lex(src: Units) -> Tokens {
    let len = src.len() as u32;
    lex_range(src, 0, len)
}

/// Lexes `src[start..end]` alone, with offsets into the whole source. Used for
/// the expressions inside an f-string.
pub fn lex_range(src: Units, start: u32, end: u32) -> Tokens {
    let end = end.min(src.len() as u32);
    let start = start.min(end);
    let mut lexer = Lexer {
        src: src.clone(),
        end: end as usize,
        out: Tokens { src, kinds: Vec::new(), starts: Vec::new(), ends: Vec::new(), flags: Vec::new() },
        depth: 0,
        line_start: start as usize,
        backslash: false,
    };
    let reserve = (end - start) as usize / 3 + 8;
    lexer.out.kinds.reserve(reserve);
    lexer.out.starts.reserve(reserve);
    lexer.out.ends.reserve(reserve);
    lexer.out.flags.reserve(reserve);
    lexer.run(start as usize);
    let mut tokens = lexer.out;
    mark_soft_keywords(&mut tokens);
    tokens
}

struct Lexer {
    src: Units,
    end: usize,
    out: Tokens,
    /// Brackets open, so line breaks continue the statement.
    depth: u32,
    line_start: usize,
    /// The last code-ish token was a continuation backslash.
    backslash: bool,
}

const fn u(c: char) -> u16 {
    c as u16
}

impl Lexer {
    fn at(&self, i: usize) -> u16 {
        if i < self.end {
            self.src[i]
        } else {
            0
        }
    }

    fn push(&mut self, kind: TokenKind, start: usize, end: usize, flags: u8) {
        if end <= start {
            return;
        }
        self.out.kinds.push(kind);
        self.out.starts.push(start as u32);
        self.out.ends.push(end as u32);
        self.out.flags.push(flags);
        if kind != TokenKind::Whitespace && kind != TokenKind::Comment {
            self.backslash = false;
        }
    }

    fn run(&mut self, from: usize) {
        let mut i = from;
        while i < self.end {
            let start = i;
            let c = self.src[i];
            if c == u(' ') || c == u('\t') || c == 0x0c {
                while i < self.end && (self.src[i] == u(' ') || self.src[i] == u('\t') || self.src[i] == 0x0c) {
                    i += 1;
                }
                self.push(TokenKind::Whitespace, start, i, 0);
            } else if c == u('\n') || c == u('\r') {
                i += if c == u('\r') && self.at(i + 1) == u('\n') { 2 } else { 1 };
                self.newline(start, i);
            } else if c == u('#') {
                while i < self.end && self.src[i] != u('\n') && self.src[i] != u('\r') {
                    i += 1;
                }
                self.push(TokenKind::Comment, start, i, 0);
            } else if c == u('\\') {
                let next = self.at(i + 1);
                if next == u('\n') || next == u('\r') {
                    self.push(TokenKind::Whitespace, start, i + 1, CONTINUATION);
                    self.backslash = true;
                } else {
                    self.push(TokenKind::Unknown, start, i + 1, 0);
                }
                i += 1;
            } else if let Some(prefix) = self.string_prefix(i) {
                i = self.string(i, prefix);
            } else if c == u('"') || c == u('\'') {
                i = self.string(i, 0);
            } else if is_digit(c) || (c == u('.') && is_digit(self.at(i + 1))) {
                i = self.number(i);
                self.push(TokenKind::Number, start, i, 0);
            } else if let Some(n) = self.name_start(i) {
                i += n;
                while i < self.end {
                    match self.name_continue(i) {
                        Some(n) => i += n,
                        None => break,
                    }
                }
                let kind = if is_keyword(&self.src[start..i]) { TokenKind::Keyword } else { TokenKind::Name };
                self.push(kind, start, i, 0);
            } else if let Some(n) = self.operator(i) {
                self.bracket(c);
                i += n;
                self.push(TokenKind::Operator, start, i, 0);
            } else {
                let (_, n) = code_point(&self.src[..self.end], i);
                i += n;
                self.push(TokenKind::Unknown, start, i, 0);
            }
        }
    }

    fn newline(&mut self, start: usize, end: usize) {
        let mut flags = 0;
        if self.backslash {
            flags |= CONTINUATION;
        } else if self.depth > 0 {
            if self.next_line_starts_statement(end) {
                self.depth = 0;
                flags |= RESET;
            } else {
                flags |= CONTINUATION;
            }
        }
        self.push(TokenKind::Newline, start, end, flags);
        self.backslash = false;
        self.line_start = end;
    }

    /// Whether the line after [from] starts with a keyword that only ever
    /// starts a statement, which no line inside brackets can.
    fn next_line_starts_statement(&self, from: usize) -> bool {
        let mut i = from;
        while i < self.end && (self.src[i] == u(' ') || self.src[i] == u('\t')) {
            i += 1;
        }
        let start = i;
        while i < self.end && (self.src[i] as u32) < 128 && (self.src[i] as u8).is_ascii_alphabetic() {
            i += 1;
        }
        if i == start || (i < self.end && is_name_char(self.src[i])) {
            return false;
        }
        let word = &self.src[start..i];
        STATEMENT_ONLY.iter().any(|w| eq_units(word, w))
    }

    fn bracket(&mut self, c: u16) {
        if c == u('(') || c == u('[') || c == u('{') {
            self.depth += 1;
        } else if c == u(')') || c == u(']') || c == u('}') {
            self.depth = self.depth.saturating_sub(1);
        }
    }

    /// How many prefix letters a string starting at [i] has, when one does.
    fn string_prefix(&self, i: usize) -> Option<usize> {
        let mut n = 0;
        while n < 3 && is_prefix_letter(self.at(i + n)) {
            n += 1;
        }
        if n == 0 || n > 2 {
            return None;
        }
        let quote = self.at(i + n);
        if quote != u('"') && quote != u('\'') {
            return None;
        }
        let a = (self.src[i] as u8).to_ascii_lowercase();
        let b = if n == 2 { (self.src[i + 1] as u8).to_ascii_lowercase() } else { 0 };
        let valid = match (a, b) {
            (_, 0) => true,
            (b'r', b'b') | (b'b', b'r') | (b'r', b'f') | (b'f', b'r') => true,
            _ => false,
        };
        valid.then_some(n)
    }

    fn string(&mut self, start: usize, prefix: usize) -> usize {
        let mut flags = 0;
        for k in 0..prefix {
            match (self.src[start + k] as u8).to_ascii_lowercase() {
                b'f' => flags |= FSTRING,
                b'r' => flags |= RAW,
                _ => flags |= FOREIGN_PREFIX,
            }
        }
        let open = start + prefix;
        let quote = self.src[open];
        let triple = self.at(open + 1) == quote && self.at(open + 2) == quote;
        if triple {
            flags |= TRIPLE;
        }
        let mut i = open + if triple { 3 } else { 1 };
        let fstring = flags & FSTRING != 0;
        loop {
            if i >= self.end {
                self.push(TokenKind::String, start, self.end, flags | UNTERMINATED);
                return self.end;
            }
            let c = self.src[i];
            if c == u('\\') {
                i += 1;
                if i < self.end && self.src[i] == u('\r') && self.at(i + 1) == u('\n') {
                    i += 2;
                } else if i < self.end {
                    i += 1;
                }
            } else if !triple && (c == u('\n') || c == u('\r')) {
                self.push(TokenKind::String, start, i, flags | UNTERMINATED);
                return i;
            } else if c == quote {
                if !triple {
                    self.push(TokenKind::String, start, i + 1, flags);
                    return i + 1;
                }
                if self.at(i + 1) == quote && self.at(i + 2) == quote {
                    self.push(TokenKind::String, start, i + 3, flags);
                    return i + 3;
                }
                i += 1;
            } else if fstring && c == u('{') {
                if self.at(i + 1) == u('{') {
                    i += 2;
                } else {
                    match self.placeholder_end(i + 1, triple) {
                        Some(next) => i = next,
                        None => {
                            // A line break inside a one-line f-string's field ends the string there.
                            let mut j = i;
                            while j < self.end && self.src[j] != u('\n') && self.src[j] != u('\r') {
                                j += 1;
                            }
                            self.push(TokenKind::String, start, j, flags | UNTERMINATED);
                            return j;
                        }
                    }
                }
            } else {
                i += 1;
            }
        }
    }

    /// Past the `}` closing the replacement field whose body starts at [from],
    /// minding nested brackets and quotes as PyMini does. None when a one-line
    /// string's field meets a line break or the field never closes.
    fn placeholder_end(&self, from: usize, triple: bool) -> Option<usize> {
        let mut depth = 1;
        let mut quote: u16 = 0;
        let mut i = from;
        while i < self.end {
            let c = self.src[i];
            if !triple && (c == u('\n') || c == u('\r')) {
                return None;
            }
            if quote != 0 {
                if c == u('\\') {
                    i += 2;
                    continue;
                }
                if c == quote {
                    quote = 0;
                }
            } else if c == u('"') || c == u('\'') {
                quote = c;
            } else if c == u('{') || c == u('[') || c == u('(') {
                depth += 1;
            } else if c == u(']') || c == u(')') {
                depth -= 1;
            } else if c == u('}') {
                depth -= 1;
                if depth <= 0 {
                    return Some(i + 1);
                }
            }
            i += 1;
        }
        None
    }

    /// The longest run that could be a number, so `1e` or `3abc` is one
    /// malformed number for a diagnostic to name rather than a number and a name.
    fn number(&self, start: usize) -> usize {
        let mut i = start;
        let radix = self.at(start) == u('0') && matches!(self.at(start + 1) as u8, b'x' | b'X' | b'o' | b'O' | b'b' | b'B');
        if radix {
            i += 2;
        }
        while i < self.end {
            let c = self.src[i];
            if !radix && (c == u('e') || c == u('E')) && (self.at(i + 1) == u('+') || self.at(i + 1) == u('-')) {
                i += 2;
            } else if (c as u32) < 128 && ((c as u8).is_ascii_alphanumeric() || c == u('_')) {
                i += 1;
            } else if c == u('.') && !radix && self.at(i + 1) != u('.') && !is_name_start_ascii(self.at(i + 1)) {
                i += 1;
            } else {
                break;
            }
        }
        i
    }

    fn name_start(&self, i: usize) -> Option<usize> {
        let c = self.src[i];
        if c < 128 {
            return is_name_start_ascii(c).then_some(1);
        }
        let (cp, n) = code_point(&self.src[..self.end], i);
        char::from_u32(cp).filter(|ch| ch.is_alphabetic()).map(|_| n)
    }

    fn name_continue(&self, i: usize) -> Option<usize> {
        let c = self.src[i];
        if c < 128 {
            return is_name_char(c).then_some(1);
        }
        let (cp, n) = code_point(&self.src[..self.end], i);
        let ok = char::from_u32(cp).is_some_and(|ch| ch.is_alphanumeric()) || is_combining(cp);
        ok.then_some(n)
    }

    fn operator(&self, i: usize) -> Option<usize> {
        let first = self.src[i];
        if first >= 128 {
            return None;
        }
        for op in OPERATORS {
            let bytes = op.as_bytes();
            if bytes[0] as u16 != first {
                continue;
            }
            if (1..bytes.len()).all(|k| self.at(i + k) == bytes[k] as u16) {
                return Some(bytes.len());
            }
        }
        None
    }
}

/// `match` and `case` are keywords only where they open a statement whose line ends with `:`.
fn mark_soft_keywords(tokens: &mut Tokens) {
    let mut line_first: Option<usize> = None;
    let mut last_code: Option<usize> = None;
    let count = tokens.len();
    for i in 0..=count {
        let ends_line = i == count || (tokens.kinds[i] == TokenKind::Newline && tokens.flags[i] & CONTINUATION == 0);
        if ends_line {
            if let (Some(first), Some(last)) = (line_first, last_code) {
                if tokens.kinds[first] == TokenKind::Name
                    && (tokens.is(first, "match") || tokens.is(first, "case"))
                    && last > first
                    && tokens.is_op(last, ":")
                {
                    let next = tokens.next_code(first).unwrap_or(last);
                    let blocked = tokens.kinds[next] == TokenKind::Operator
                        && [":", "=", ".", ",", ")", "+=", "-=", "*=", "/="].iter().any(|op| tokens.is(next, op));
                    if !blocked {
                        tokens.kinds[first] = TokenKind::SoftKeyword;
                    }
                }
            }
            line_first = None;
            last_code = None;
            continue;
        }
        if tokens.is_code(i) {
            if line_first.is_none() {
                line_first = Some(i);
            }
            last_code = Some(i);
        }
    }
}

/// The replacement fields of the f-string token [index]: for each, the span
/// of its expression (before any `!conversion` or `:spec`), and the fields
/// nested in its spec.
pub fn fstring_fields(tokens: &Tokens, index: usize) -> Vec<Span> {
    let mut out = Vec::new();
    if !tokens.has(index, FSTRING) {
        return out;
    }
    let src = &tokens.src;
    let start = tokens.start(index) as usize;
    let end = tokens.end(index) as usize;
    let mut i = start;
    while i < end && src[i] != u('"') && src[i] != u('\'') {
        i += 1;
    }
    if i >= end {
        return out;
    }
    let quote = src[i];
    let triple = i + 2 < end && src[i + 1] == quote && src[i + 2] == quote;
    i += if triple { 3 } else { 1 };
    collect_fields(src, i, end, &mut out, 0);
    out
}

fn collect_fields(src: &[u16], from: usize, end: usize, out: &mut Vec<Span>, nesting: u32) {
    let mut i = from;
    while i < end {
        let c = src[i];
        if c == u('\\') {
            i += 2;
            continue;
        }
        if c == u('{') {
            if i + 1 < end && src[i + 1] == u('{') {
                i += 2;
                continue;
            }
            // Scan the field: its expression ends at a top-level `!x` or `:`.
            let body = i + 1;
            let mut depth = 1;
            let mut quote = 0u16;
            let mut j = body;
            let mut expr_end: Option<usize> = None;
            let mut spec_start: Option<usize> = None;
            while j < end {
                let d = src[j];
                if quote != 0 {
                    if d == u('\\') {
                        j += 2;
                        continue;
                    }
                    if d == quote {
                        quote = 0;
                    }
                } else if d == u('"') || d == u('\'') {
                    quote = d;
                } else if d == u('{') || d == u('[') || d == u('(') {
                    depth += 1;
                } else if d == u(']') || d == u(')') {
                    depth -= 1;
                } else if d == u('}') {
                    depth -= 1;
                    if depth <= 0 {
                        break;
                    }
                } else if depth == 1 && d == u('!') && j + 1 < end && src[j + 1] != u('=') && expr_end.is_none() {
                    expr_end = Some(j);
                } else if depth == 1 && d == u(':') && spec_start.is_none() {
                    if expr_end.is_none() {
                        expr_end = Some(j);
                    }
                    spec_start = Some(j + 1);
                    break;
                }
                j += 1;
            }
            let close = if spec_start.is_some() { find_field_close(src, j, end) } else { j };
            let expr_end = expr_end.unwrap_or(close.min(end));
            out.push(Span::new(body as u32, expr_end.max(body) as u32));
            if let (Some(spec), true) = (spec_start, nesting < 2) {
                collect_fields(src, spec, close.min(end), out, nesting + 1);
            }
            i = close + 1;
            continue;
        }
        i += 1;
    }
}

/// The `}` that closes a field whose spec starts before [from].
fn find_field_close(src: &[u16], from: usize, end: usize) -> usize {
    let mut depth = 1;
    let mut i = from;
    while i < end {
        if src[i] == u('{') {
            depth += 1;
        } else if src[i] == u('}') {
            depth -= 1;
            if depth == 0 {
                return i;
            }
        }
        i += 1;
    }
    end
}

/// The value of a plain string token: prefixes and quotes taken off and the
/// common escapes read. None for an f-string, an unterminated string, or a
/// string with an escape too unusual to read here.
pub fn string_value(tokens: &Tokens, index: usize) -> Option<String> {
    if tokens.kind(index) != TokenKind::String || tokens.has(index, UNTERMINATED | FSTRING) {
        return None;
    }
    let raw = tokens.has(index, RAW);
    let src = &tokens.src;
    let mut i = tokens.start(index) as usize;
    let end = tokens.end(index) as usize;
    while i < end && src[i] != u('"') && src[i] != u('\'') {
        i += 1;
    }
    let quotes = if tokens.has(index, TRIPLE) { 3 } else { 1 };
    let body = &src[(i + quotes).min(end)..end.saturating_sub(quotes).max(i + quotes).min(end)];
    let mut out: Vec<u16> = Vec::with_capacity(body.len());
    let mut k = 0;
    while k < body.len() {
        let c = body[k];
        if c == u('\\') && !raw && k + 1 < body.len() {
            let e = body[k + 1];
            let mapped = match e as u8 {
                b'n' => u('\n'),
                b't' => u('\t'),
                b'r' => u('\r'),
                b'0' => 0,
                b'\\' => u('\\'),
                b'\'' => u('\''),
                b'"' => u('"'),
                b'\n' => {
                    k += 2;
                    continue;
                }
                _ => return None,
            };
            out.push(mapped);
            k += 2;
            continue;
        }
        out.push(c);
        k += 1;
    }
    Some(String::from_utf16_lossy(&out))
}

/// Whether a number token's text is a number Python reads, and whether it is
/// complex (`2j`), which PyMini does not have.
pub fn number_shape(text: &str) -> (bool, bool) {
    let bytes = text.as_bytes();
    let complex = bytes.last().is_some_and(|b| *b == b'j' || *b == b'J');
    let body = if complex { &text[..text.len() - 1] } else { text };
    (valid_number(body), complex)
}

fn valid_number(text: &str) -> bool {
    let b = text.as_bytes();
    if b.is_empty() {
        return false;
    }
    if b.len() > 2 && b[0] == b'0' && b[1].is_ascii_alphabetic() {
        let digits = &text[2..];
        let ok: fn(u8) -> bool = match b[1] {
            b'x' | b'X' => |c| c.is_ascii_hexdigit(),
            b'o' | b'O' => |c| (b'0'..=b'7').contains(&c),
            b'b' | b'B' => |c| c == b'0' || c == b'1',
            _ => return false,
        };
        return grouped(digits, ok);
    }
    // digits [. digits] [e [+-] digits], each run of digits possibly grouped with `_`.
    let (mantissa, exponent) = match text.find(['e', 'E']) {
        Some(at) => (&text[..at], Some(&text[at + 1..])),
        None => (text, None),
    };
    let mut parts = mantissa.splitn(2, '.');
    let whole = parts.next().unwrap_or("");
    let fraction = parts.next();
    let digit: fn(u8) -> bool = |c| c.is_ascii_digit();
    let whole_ok = whole.is_empty() || grouped(whole, digit);
    let fraction_ok = match fraction {
        Some(f) => f.is_empty() || grouped(f, digit),
        None => true,
    };
    if whole.is_empty() && fraction.is_none_or(str::is_empty) {
        return false;
    }
    // Python refuses `012`, a leading zero on a whole number.
    if fraction.is_none() && exponent.is_none() && whole.len() > 1 && whole.starts_with('0') && whole.bytes().any(|c| c != b'0' && c != b'_') {
        return false;
    }
    let exponent_ok = match exponent {
        Some(e) => {
            let e = e.strip_prefix(['+', '-']).unwrap_or(e);
            grouped(e, digit)
        }
        None => true,
    };
    whole_ok && fraction_ok && exponent_ok
}

fn grouped(text: &str, ok: fn(u8) -> bool) -> bool {
    !text.is_empty()
        && !text.starts_with('_')
        && !text.ends_with('_')
        && !text.contains("__")
        && text.bytes().all(|c| c == b'_' || ok(c))
}

fn is_digit(c: u16) -> bool {
    (u('0')..=u('9')).contains(&c)
}

fn is_name_start_ascii(c: u16) -> bool {
    c < 128 && ((c as u8).is_ascii_alphabetic() || c == u('_'))
}

fn is_name_char(c: u16) -> bool {
    c < 128 && ((c as u8).is_ascii_alphanumeric() || c == u('_'))
}

fn is_prefix_letter(c: u16) -> bool {
    matches!(c as u8, b'r' | b'R' | b'b' | b'B' | b'f' | b'F' | b'u' | b'U') && c < 128
}

fn eq_units(units: &[u16], word: &str) -> bool {
    units.len() == word.len() && units.iter().zip(word.bytes()).all(|(a, b)| *a == b as u16)
}

pub fn is_keyword(units: &[u16]) -> bool {
    if units.len() < 2 || units.len() > 8 {
        return false;
    }
    KEYWORDS.iter().any(|k| eq_units(units, k))
}

pub fn is_keyword_str(word: &str) -> bool {
    KEYWORDS.contains(&word)
}
