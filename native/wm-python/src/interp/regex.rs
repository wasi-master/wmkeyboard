//! Port of PyRegex.kt: a small backtracking engine behind the `re` namespace.
//!
//! Every step charges the interpreter's step budget (and so its cancel and
//! deadline checks), so a catastrophic pattern like `(a+)+b` runs out of steps
//! instead of hanging. A repeat whose body is one character, class or `.` runs
//! as a loop; anything else counts its depth and gives up past [MAX_DEPTH].
//!
//! Supported: literals, `.`, classes with ranges and negation, `\d \D \w \W \s
//! \S \b \B`, anchors, groups (capturing, `(?:…)`, `(?P<name>…)`), alternation,
//! `* + ? {m,n}` greedy and lazy, lookahead `(?=…)` `(?!…)`, fixed-width
//! lookbehind `(?<=…)` `(?<!…)`, backreferences `\1` and `(?P=name)`, and the
//! inline flags `(?i)`, `(?m)`, `(?s)`. Not supported: possessive quantifiers,
//! atomic groups, conditionals and `(?x)`. Subjects are matched as grapheme
//! clusters, so `m.start()` counts clusters, as PyMini's do.

use std::collections::HashMap;
use std::rc::Rc;

use super::error::{AbortKind, PyError};
use super::value::Value;
use super::text::{char_is_letter, char_is_number, char_is_whitespace, graphemes, parse_swift_int, str_compare, str_eq, swift_lower, swift_upper};

pub(crate) const IGNORE_CASE: i64 = 2;
pub(crate) const MULTILINE: i64 = 8;
pub(crate) const DOT_ALL: i64 = 16;
pub(crate) const UNICODE: i64 = 32;
pub(crate) const VERBOSE: i64 = 64;
pub(crate) const ASCII: i64 = 256;
/// Not one of Python's flags: marks a pattern made from bytes, which
/// searches bytes. Hidden from `pattern.flags`.
pub(crate) const BYTES: i64 = 1 << 40;

/// Longest subject this engine will scan: as long as a string may be. The
/// step budget, not the length, is what bounds a slow pattern.
pub(crate) const MAX_SUBJECT: usize = 10_000_000;
/// Deepest nested backtrack before giving up, which bounds the stack.
pub(crate) const MAX_DEPTH: u32 = 2_000;

type Res<T> = Result<T, PyError>;

fn case_eq(a: &str, b: &str) -> bool {
    str_eq(&swift_lower(a), &swift_lower(b))
}

fn verr(message: &str) -> PyError {
    PyError::new(message, "ValueError")
}

/// A subject as grapheme clusters, or bytes as one character each.
pub struct Subject {
    text: Rc<str>,
    b: Rc<[u32]>,
    /// Bytes read as Latin-1, one character a byte; what comes out of a
    /// match goes back to bytes.
    pub(crate) bytes: bool,
}

impl Subject {
    pub(crate) fn new(text: Rc<str>, b: Rc<[u32]>) -> Subject {
        Subject { text, b, bytes: false }
    }

    /// [data] to search with a bytes pattern.
    pub(crate) fn of_bytes(data: &[u8]) -> Subject {
        let text: Rc<str> = Rc::from(latin1_text(data));
        let mut b: Vec<u32> = text.char_indices().map(|(i, _)| i as u32).collect();
        b.push(text.len() as u32);
        Subject { text, b: b.into(), bytes: true }
    }

    /// A piece of the subject as the script sees it: bytes for a bytes subject.
    pub(crate) fn piece(&self, s: &str) -> Value {
        if self.bytes {
            Value::bytes(&latin1_bytes(s))
        } else {
            Value::str(s)
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.b.len() - 1
    }

    pub(crate) fn get(&self, i: usize) -> &str {
        &self.text[self.b[i] as usize..self.b[i + 1] as usize]
    }

    pub(crate) fn join(&self, from: usize, to: usize) -> &str {
        &self.text[self.b[from] as usize..self.b[to] as usize]
    }

    pub(crate) fn text(&self) -> &str {
        &self.text
    }
}

/// One successful match: the whole pattern's span plus each group's (end exclusive).
pub struct Match {
    pub(crate) subject: Rc<Subject>,
    /// Index 0 is the whole match; 1… are capturing groups (None = didn't take part).
    pub(crate) spans: Vec<Option<(usize, usize)>>,
    pub(crate) names: Rc<HashMap<String, usize>>,
}

impl Match {
    /// The whole matched text.
    pub fn value(&self) -> String {
        self.text(0).unwrap_or_default()
    }

    /// A group's text, or None when that group didn't take part in the match.
    pub fn text(&self, group: usize) -> Option<String> {
        let (s, e) = (*self.spans.get(group)?)?;
        Some(self.subject.join(s, e).to_string())
    }
}

enum ClassItem {
    Single(String),
    Range(String, String),
    Digit(bool),
    Word(bool),
    Space(bool),
    /// `\d`, `\w` and `\s` under `re.ASCII`.
    AsciiDigit(bool),
    AsciiWord(bool),
    AsciiSpace(bool),
}

struct Class {
    negated: bool,
    items: Vec<ClassItem>,
}

impl Class {
    fn matches(&self, c: &str, ignore_case: bool) -> bool {
        let in_range = |lo: &str, hi: &str, c: &str| str_compare(lo, c).is_le() && str_compare(c, hi).is_le();
        let mut hit = false;
        for item in &self.items {
            hit = match item {
                ClassItem::Single(s) => {
                    if ignore_case {
                        case_eq(s, c)
                    } else {
                        str_eq(s, c)
                    }
                }
                ClassItem::Range(lo, hi) => {
                    if ignore_case {
                        in_range(lo, hi, &swift_lower(c)) || in_range(lo, hi, &swift_upper(c))
                    } else {
                        in_range(lo, hi, c)
                    }
                }
                ClassItem::Digit(want) => char_is_number(c) == *want,
                ClassItem::Word(want) => (char_is_letter(c) || char_is_number(c) || c == "_") == *want,
                ClassItem::Space(want) => char_is_whitespace(c) == *want,
                ClassItem::AsciiDigit(want) => (c.len() == 1 && c.as_bytes()[0].is_ascii_digit()) == *want,
                ClassItem::AsciiWord(want) => (c.len() == 1 && (c.as_bytes()[0].is_ascii_alphanumeric() || c == "_")) == *want,
                ClassItem::AsciiSpace(want) => (c.len() == 1 && b" \t\n\r\x0b\x0c".contains(&c.as_bytes()[0])) == *want,
            };
            if hit {
                break;
            }
        }
        if self.negated {
            !hit
        } else {
            hit
        }
    }
}

enum Node {
    Empty,
    Char(String),
    AnyChar,
    CharClass(Class),
    Seq(Vec<Node>),
    Alt(Vec<Node>),
    Repeated(Box<Node>, usize, Option<usize>, bool),
    /// Index into the capture table, or None for `(?:…)`.
    Group(Option<usize>, Box<Node>),
    AnchorStart,
    AnchorEnd,
    WordBoundary(bool),
    /// `(?=…)`, `(?!…)` (ahead) and `(?<=…)`, `(?<!…)` (behind, [width]
    /// characters back). [negate] for the `!` forms.
    Look { behind: Option<usize>, negate: bool, node: Box<Node> },
    /// `\1` or `(?P=name)`: the text a group captured, again.
    Backref(usize),
}

/// How many characters [node] always matches, when that is fixed: what a lookbehind needs.
fn fixed_width(node: &Node) -> Option<usize> {
    match node {
        Node::Empty | Node::AnchorStart | Node::AnchorEnd | Node::WordBoundary(_) | Node::Look { .. } => Some(0),
        Node::Char(_) | Node::AnyChar | Node::CharClass(_) => Some(1),
        Node::Seq(items) => items.iter().map(fixed_width).sum(),
        Node::Alt(branches) => {
            let first = fixed_width(branches.first()?)?;
            branches.iter().all(|b| fixed_width(b) == Some(first)).then_some(first)
        }
        Node::Group(_, inner) => fixed_width(inner),
        Node::Repeated(body, min, max, _) if Some(*min) == *max => fixed_width(body).map(|w| w * min),
        Node::Repeated(..) | Node::Backref(_) => None,
    }
}

/// A compiled regular expression.
pub(crate) struct Regex {
    root: Node,
    pub group_count: usize,
    pub names: Rc<HashMap<String, usize>>,
    flags: i64,
    /// The flags the pattern set itself, `(?i)`.
    inline: i64,
}

impl Regex {
    pub fn compile(pattern: &str, flags: i64) -> Res<Regex> {
        let leading = leading_flags(pattern);
        let verbose_text;
        let pattern = if flags & VERBOSE != 0 || leading.contains('x') {
            verbose_text = strip_verbose(pattern);
            verbose_text.as_str()
        } else {
            pattern
        };
        let chars = graphemes(pattern);
        let ascii = flags & (ASCII | BYTES) != 0 || leading.contains('a');
        let mut p =
            RxParser { chars: &chars, pos: 0, group_count: 0, names: HashMap::new(), depth: 0, inline: 0, backrefs: Vec::new(), ascii };
        let root = p.parse_alternation()?;
        if p.pos < chars.len() {
            return Err(verr("unbalanced parenthesis in pattern"));
        }
        if p.backrefs.iter().any(|&i| i == 0 || i > p.group_count) {
            return Err(verr("invalid group reference in pattern"));
        }
        Ok(Regex { root, group_count: p.group_count, names: Rc::new(p.names), flags: flags | p.inline, inline: p.inline })
    }

    /// The flags `(?i)`, `(?m)` or `(?s)` in the pattern turned on.
    pub fn inline_flags(&self) -> i64 {
        self.inline
    }

    /// `re.escape`: backslash every character that could be syntax.
    pub fn escape(s: &str) -> String {
        let mut out = String::new();
        for c in graphemes(s) {
            if !(char_is_letter(c) || char_is_number(c) || c == "_") {
                out.push('\\');
            }
            out.push_str(c);
        }
        out
    }

    fn ignore_case(&self) -> bool {
        self.flags & IGNORE_CASE != 0
    }

    fn multiline(&self) -> bool {
        self.flags & MULTILINE != 0
    }

    fn dot_all(&self) -> bool {
        self.flags & DOT_ALL != 0
    }

    /// The first match at or after [start]. [anchored] pins it to [start]
    /// (`re.match`); [whole] also requires it to reach the end (`re.fullmatch`).
    pub fn first_match(
        &self,
        subject: &Rc<Subject>,
        start: usize,
        anchored: bool,
        whole: bool,
        tick: &mut dyn FnMut() -> Res<()>,
    ) -> Res<Option<Match>> {
        if subject.len() > MAX_SUBJECT {
            return Err(PyError::abort(
                AbortKind::StringCap,
                format!("text too long for a regular expression ({} characters)", subject.len()),
                None,
            ));
        }
        let mut at = start;
        loop {
            let mut m = Matcher { subject, regex: self, tick: &mut *tick, captures: vec![None; self.group_count + 1], depth: 0 };
            if let Some(spans) = m.run(at, whole)? {
                return Ok(Some(Match { subject: subject.clone(), spans, names: self.names.clone() }));
            }
            if anchored {
                return Ok(None);
            }
            at += 1;
            if at > subject.len() {
                return Ok(None);
            }
        }
    }

    /// Every non-overlapping match from [from]; a zero-width match advances one character.
    pub fn all_matches(&self, subject: &Rc<Subject>, from: usize, limit: usize, tick: &mut dyn FnMut() -> Res<()>) -> Res<Vec<Match>> {
        let mut out = Vec::new();
        let mut at = from;
        while at <= subject.len() && out.len() < limit {
            let Some(m) = self.first_match(subject, at, false, false, tick)? else { break };
            let (s, e) = m.spans[0].unwrap_or((at, at));
            at = if s == e { s + 1 } else { e };
            out.push(m);
        }
        Ok(out)
    }
}

// MARK: - Pattern parsing

struct RxParser<'a> {
    chars: &'a [&'a str],
    pos: usize,
    group_count: usize,
    names: HashMap<String, usize>,
    depth: u32,
    /// Flags set by `(?i)` and the like.
    inline: i64,
    /// Every group number a backreference names, checked once the groups are counted.
    backrefs: Vec<usize>,
    /// `re.ASCII`: `\d`, `\w` and `\s` match ASCII only.
    ascii: bool,
}

impl RxParser<'_> {
    /// The class `\d`, `\D`, `\w`, `\W`, `\s` or `\S` stands for.
    fn shorthand(&self, e: &str) -> Option<ClassItem> {
        Some(match (e, self.ascii) {
            ("d", false) => ClassItem::Digit(true),
            ("D", false) => ClassItem::Digit(false),
            ("w", false) => ClassItem::Word(true),
            ("W", false) => ClassItem::Word(false),
            ("s", false) => ClassItem::Space(true),
            ("S", false) => ClassItem::Space(false),
            ("d", true) => ClassItem::AsciiDigit(true),
            ("D", true) => ClassItem::AsciiDigit(false),
            ("w", true) => ClassItem::AsciiWord(true),
            ("W", true) => ClassItem::AsciiWord(false),
            ("s", true) => ClassItem::AsciiSpace(true),
            ("S", true) => ClassItem::AsciiSpace(false),
            _ => return None,
        })
    }

    fn peek(&self) -> Option<&str> {
        self.chars.get(self.pos).copied()
    }

    fn parse_alternation(&mut self) -> Res<Node> {
        self.depth += 1;
        if self.depth > 200 {
            return Err(PyError::new("maximum recursion depth exceeded in a regular expression", "RecursionError"));
        }
        let mut branches = vec![self.parse_sequence()?];
        while self.peek() == Some("|") {
            self.pos += 1;
            branches.push(self.parse_sequence()?);
        }
        self.depth -= 1;
        Ok(if branches.len() == 1 { branches.pop().unwrap() } else { Node::Alt(branches) })
    }

    fn parse_sequence(&mut self) -> Res<Node> {
        let mut items = Vec::new();
        while let Some(c) = self.peek() {
            if c == "|" || c == ")" {
                break;
            }
            items.push(self.parse_quantified()?);
        }
        Ok(match items.len() {
            0 => Node::Empty,
            1 => items.pop().unwrap(),
            _ => Node::Seq(items),
        })
    }

    fn parse_quantified(&mut self) -> Res<Node> {
        let atom = self.parse_atom()?;
        let Some(c) = self.peek() else { return Ok(atom) };
        let (min, max) = match c {
            "*" => {
                self.pos += 1;
                (0, None)
            }
            "+" => {
                self.pos += 1;
                (1, None)
            }
            "?" => {
                self.pos += 1;
                (0, Some(1))
            }
            // `{` is only a quantifier when it reads as one; otherwise it's a literal.
            "{" => match self.parse_brace_quantifier() {
                Some(q) => q,
                None => return Ok(atom),
            },
            _ => return Ok(atom),
        };
        let mut greedy = true;
        if self.peek() == Some("?") {
            greedy = false;
            self.pos += 1;
        } else if self.peek() == Some("+") {
            return Err(verr("possessive quantifiers are not supported"));
        }
        Ok(Node::Repeated(Box::new(atom), min, max, greedy))
    }

    fn parse_brace_quantifier(&mut self) -> Option<(usize, Option<usize>)> {
        let save = self.pos;
        self.pos += 1;
        let mut lo = String::new();
        while let Some(c) = self.peek().filter(|c| char_is_number(c)) {
            lo.push_str(c);
            self.pos += 1;
        }
        let mut hi: Option<String> = None;
        if self.peek() == Some(",") {
            self.pos += 1;
            let mut h = String::new();
            while let Some(c) = self.peek().filter(|c| char_is_number(c)) {
                h.push_str(c);
                self.pos += 1;
            }
            hi = Some(h);
        }
        if self.peek() != Some("}") || (lo.is_empty() && hi.is_none()) {
            self.pos = save;
            return None;
        }
        self.pos += 1;
        let num = |s: &str| parse_swift_int(s, 10).map(|v| v.clamp(0, i32::MAX as i64) as usize);
        let min = num(&lo).unwrap_or(0);
        match hi {
            Some(h) => Some((min, if h.is_empty() { None } else { num(&h) })),
            None => Some((min, Some(min))),
        }
    }

    fn parse_atom(&mut self) -> Res<Node> {
        let Some(c) = self.peek() else { return Ok(Node::Empty) };
        match c {
            "(" => {
                self.pos += 1;
                let mut index = None;
                if self.peek() == Some("?") {
                    self.pos += 1;
                    let next = self.peek().map(str::to_string);
                    let after = self.chars.get(self.pos + 1).copied();
                    match (next.as_deref(), after) {
                        (Some(":"), _) => self.pos += 1,
                        // Lookahead and lookbehind.
                        (Some("=" | "!"), _) | (Some("<"), Some("=" | "!")) => {
                            let behind = next.as_deref() == Some("<");
                            if behind {
                                self.pos += 1;
                            }
                            let negate = self.peek() == Some("!");
                            self.pos += 1;
                            let inner = self.parse_alternation()?;
                            if self.peek() != Some(")") {
                                return Err(verr("missing ')' in pattern"));
                            }
                            self.pos += 1;
                            let behind = if behind {
                                Some(fixed_width(&inner).ok_or_else(|| verr("look-behind requires fixed-width pattern"))?)
                            } else {
                                None
                            };
                            return Ok(Node::Look { behind, negate, node: Box::new(inner) });
                        }
                        // `(?P=name)`: the named group's text again.
                        (Some("P"), Some("=")) => {
                            self.pos += 2;
                            let name = self.read_until(")")?;
                            let index = *self.names.get(&name).ok_or_else(|| verr(&format!("unknown group name '{name}'")))?;
                            return Ok(Node::Backref(index));
                        }
                        (Some("P" | "<"), _) => {
                            if self.peek() == Some("P") {
                                self.pos += 1;
                            }
                            if self.peek() != Some("<") {
                                return Err(verr("this group syntax is not supported"));
                            }
                            self.pos += 1;
                            let name = self.read_until(">")?;
                            if name.is_empty() {
                                return Err(verr("missing group name"));
                            }
                            self.group_count += 1;
                            index = Some(self.group_count);
                            self.names.insert(name, self.group_count);
                        }
                        // Inline flags, `(?i)`, `(?ms)`: on for the whole pattern, as in Python.
                        (Some("i" | "m" | "s" | "a" | "u" | "L" | "x"), _) => {
                            while let Some(f) = self.peek() {
                                match f {
                                    "i" => self.inline |= IGNORE_CASE,
                                    "m" => self.inline |= MULTILINE,
                                    "s" => self.inline |= DOT_ALL,
                                    // Worked out before parsing: see `leading_flags`.
                                    "a" | "u" | "L" | "x" => {}
                                    ")" => break,
                                    ":" | "-" => return Err(verr("scoped inline flags like (?i:...) are not supported; put (?i) at the start")),
                                    _ => return Err(verr(&format!("unknown flag '{f}' in pattern"))),
                                }
                                self.pos += 1;
                            }
                            if self.peek() != Some(")") {
                                return Err(verr("missing ')' in pattern"));
                            }
                            self.pos += 1;
                            return Ok(Node::Empty);
                        }
                        _ => return Err(verr("this group syntax is not supported")),
                    }
                } else {
                    self.group_count += 1;
                    index = Some(self.group_count);
                }
                let inner = self.parse_alternation()?;
                if self.peek() != Some(")") {
                    return Err(verr("missing ')' in pattern"));
                }
                self.pos += 1;
                Ok(Node::Group(index, Box::new(inner)))
            }
            "[" => Ok(Node::CharClass(self.parse_class()?)),
            "." => {
                self.pos += 1;
                Ok(Node::AnyChar)
            }
            "^" => {
                self.pos += 1;
                Ok(Node::AnchorStart)
            }
            "$" => {
                self.pos += 1;
                Ok(Node::AnchorEnd)
            }
            "\\" => {
                self.pos += 1;
                let e = self.peek().ok_or_else(|| verr("pattern ends with a backslash"))?.to_string();
                self.pos += 1;
                if let Some(item) = self.shorthand(&e) {
                    return Ok(Node::CharClass(Class { negated: false, items: vec![item] }));
                }
                Ok(match e.as_str() {
                    "b" => Node::WordBoundary(true),
                    "B" => Node::WordBoundary(false),
                    "A" => Node::AnchorStart,
                    "Z" | "z" => Node::AnchorEnd,
                    // `\101`: three octal digits are a character, not a group.
                    "1" | "2" | "3" | "4" | "5" | "6" | "7" if self.octal_follows(2) => Node::Char(self.octal(&e)),
                    "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" => {
                        // Up to two digits, as Python reads `\12`.
                        let mut n = e.parse::<usize>().unwrap_or(0);
                        if let Some(d) = self.peek().filter(|d| d.len() == 1 && d.as_bytes()[0].is_ascii_digit()) {
                            n = n * 10 + d.parse::<usize>().unwrap_or(0);
                            self.pos += 1;
                        }
                        self.backrefs.push(n);
                        Node::Backref(n)
                    }
                    other => Node::Char(self.escaped(other)?),
                })
            }
            other => {
                let other = other.to_string();
                self.pos += 1;
                Ok(Node::Char(other))
            }
        }
    }

    /// The text up to [end], past it.
    fn read_until(&mut self, end: &str) -> Res<String> {
        let mut out = String::new();
        while let Some(c) = self.peek() {
            if c == end {
                self.pos += 1;
                return Ok(out);
            }
            out.push_str(c);
            self.pos += 1;
        }
        Err(verr("unterminated group name"))
    }

    /// The character an escape names, past its backslash and [e]: `\xhh`,
    /// `\uhhhh`, `\Uhhhhhhhh`, `\N{NAME}`, `\0` with up to two more octal
    /// digits, or one of the single letters.
    fn escaped(&mut self, e: &str) -> Res<String> {
        let digits = |me: &mut Self, n: usize, what: &str| -> Res<String> {
            let mut code = 0u32;
            let mut read = what.to_string();
            for _ in 0..n {
                let d = me.peek().filter(|d| d.len() == 1).and_then(|d| d.chars().next()?.to_digit(16));
                let Some(d) = d else { return Err(verr(&format!("incomplete escape \\{read}"))) };
                read.push_str(me.chars[me.pos]);
                code = code * 16 + d;
                me.pos += 1;
            }
            char::from_u32(code).map(String::from).ok_or_else(|| verr(&format!("bad escape \\{read}")))
        };
        match e {
            "x" => digits(self, 2, "x"),
            "u" => digits(self, 4, "u"),
            "U" => digits(self, 8, "U"),
            "N" if self.peek() == Some("{") => {
                self.pos += 1;
                let name = self.read_until("}").map_err(|_| verr("missing }, unterminated name"))?;
                super::charnames::lookup(&name).map(String::from).ok_or_else(|| verr(&format!("undefined character name '{name}'")))
            }
            "0" => {
                let mut code = 0u32;
                for _ in 0..2 {
                    match self.peek().filter(|d| d.len() == 1).and_then(|d| d.chars().next()?.to_digit(8)) {
                        Some(d) => {
                            code = code * 8 + d;
                            self.pos += 1;
                        }
                        None => break,
                    }
                }
                Ok(char::from_u32(code).map(String::from).unwrap_or_default())
            }
            other => Ok(escape_char(other)),
        }
    }

    /// Whether the next [n] characters are octal digits.
    fn octal_follows(&self, n: usize) -> bool {
        (0..n).all(|k| self.chars.get(self.pos + k).is_some_and(|d| d.len() == 1 && matches!(d.as_bytes()[0], b'0'..=b'7')))
    }

    /// An octal escape whose first digit [first] is read: up to two more digits.
    fn octal(&mut self, first: &str) -> String {
        let mut code = first.chars().next().and_then(|c| c.to_digit(8)).unwrap_or(0);
        for _ in 0..2 {
            if !self.octal_follows(1) {
                break;
            }
            code = code * 8 + self.chars[self.pos].chars().next().and_then(|c| c.to_digit(8)).unwrap_or(0);
            self.pos += 1;
        }
        char::from_u32(code).map(String::from).unwrap_or_default()
    }

    fn parse_class(&mut self) -> Res<Class> {
        self.pos += 1;
        let mut cls = Class { negated: false, items: Vec::new() };
        if self.peek() == Some("^") {
            cls.negated = true;
            self.pos += 1;
        }
        if self.peek() == Some("]") {
            cls.items.push(ClassItem::Single("]".into()));
            self.pos += 1;
        }
        while let Some(c) = self.peek() {
            if c == "]" {
                break;
            }
            let c = c.to_string();
            self.pos += 1;
            let lo = if c == "\\" {
                let e = self.peek().ok_or_else(|| verr("pattern ends with a backslash"))?.to_string();
                self.pos += 1;
                if let Some(item) = self.shorthand(&e) {
                    cls.items.push(item);
                    continue;
                }
                if matches!(e.as_str(), "1" | "2" | "3" | "4" | "5" | "6" | "7") {
                    self.octal(&e)
                } else {
                    self.escaped(&e)?
                }
            } else {
                c
            };
            // A `-` that isn't last opens a range.
            if self.peek() == Some("-") && self.pos + 1 < self.chars.len() && self.chars[self.pos + 1] != "]" {
                self.pos += 1;
                let mut hi = self.chars[self.pos].to_string();
                self.pos += 1;
                if hi == "\\" {
                    if let Some(e) = self.peek().map(str::to_string) {
                        self.pos += 1;
                        hi = if matches!(e.as_str(), "1" | "2" | "3" | "4" | "5" | "6" | "7") { self.octal(&e) } else { self.escaped(&e)? };
                    }
                }
                if str_compare(&lo, &hi).is_gt() {
                    return Err(verr("bad character range in pattern"));
                }
                cls.items.push(ClassItem::Range(lo, hi));
            } else {
                cls.items.push(ClassItem::Single(lo));
            }
        }
        if self.peek() != Some("]") {
            return Err(verr("unterminated character class"));
        }
        self.pos += 1;
        Ok(cls)
    }
}

fn escape_char(c: &str) -> String {
    match c {
        "n" => "\n",
        "t" => "\t",
        "r" => "\r",
        "f" => "\u{000C}",
        "v" => "\u{000B}",
        "0" => "\0",
        other => other,
    }
    .to_string()
}

// MARK: - Matching

struct Matcher<'a> {
    subject: &'a Subject,
    regex: &'a Regex,
    tick: &'a mut dyn FnMut() -> Res<()>,
    captures: Vec<Option<(usize, usize)>>,
    depth: u32,
}

type Cont<'c, 'a> = &'c mut dyn FnMut(&mut Matcher<'a>, usize) -> Res<bool>;

impl<'a> Matcher<'a> {
    fn run(&mut self, start: usize, whole: bool) -> Res<Option<Vec<Option<(usize, usize)>>>> {
        let mut result = None;
        let len = self.subject.len();
        let regex = self.regex;
        let matched = self.matches(&regex.root, start, &mut |me, end| {
            if whole && end != len {
                return Ok(false);
            }
            me.captures[0] = Some((start, end));
            result = Some(me.captures.clone());
            Ok(true)
        })?;
        Ok(if matched { result } else { None })
    }

    /// Whether [node] matches at [pos], handing each candidate end to [cont].
    /// Returning false from [cont] asks for the next alternative: that's the backtracking.
    fn matches(&mut self, node: &'a Node, pos: usize, cont: Cont<'_, 'a>) -> Res<bool> {
        (self.tick)()?;
        self.depth += 1;
        let r = self.matches_inner(node, pos, cont);
        self.depth -= 1;
        r
    }

    fn matches_inner(&mut self, node: &'a Node, pos: usize, cont: Cont<'_, 'a>) -> Res<bool> {
        if self.depth >= MAX_DEPTH {
            return Err(PyError::new("maximum recursion depth exceeded in a regular expression", "RecursionError"));
        }
        let n = self.subject.len();
        match node {
            Node::Empty => cont(self, pos),
            Node::Char(c) => {
                if pos < n && self.equal(self.subject.get(pos), c) {
                    cont(self, pos + 1)
                } else {
                    Ok(false)
                }
            }
            Node::AnyChar => {
                if pos >= n || (!self.regex.dot_all() && self.subject.get(pos) == "\n") {
                    Ok(false)
                } else {
                    cont(self, pos + 1)
                }
            }
            Node::CharClass(cls) => {
                if pos < n && cls.matches(self.subject.get(pos), self.regex.ignore_case()) {
                    cont(self, pos + 1)
                } else {
                    Ok(false)
                }
            }
            Node::AnchorStart => {
                if pos == 0 || (self.regex.multiline() && self.subject.get(pos - 1) == "\n") {
                    cont(self, pos)
                } else {
                    Ok(false)
                }
            }
            Node::AnchorEnd => {
                if pos == n || (self.regex.multiline() && self.subject.get(pos) == "\n") {
                    cont(self, pos)
                } else {
                    Ok(false)
                }
            }
            Node::WordBoundary(want) => {
                let before = pos > 0 && is_word(self.subject.get(pos - 1));
                let after = pos < n && is_word(self.subject.get(pos));
                if (before != after) == *want {
                    cont(self, pos)
                } else {
                    Ok(false)
                }
            }
            Node::Seq(items) => self.match_seq(items, 0, pos, cont),
            Node::Alt(branches) => {
                for b in branches {
                    if self.matches(b, pos, &mut *cont)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            Node::Group(None, inner) => self.matches(inner, pos, cont),
            Node::Group(Some(index), inner) => {
                let index = *index;
                let saved = self.captures[index];
                let mut restored = false;
                let ok = self.matches(inner, pos, &mut |me, end| {
                    me.captures[index] = Some((pos, end));
                    if cont(me, end)? {
                        Ok(true)
                    } else {
                        me.captures[index] = saved;
                        restored = true;
                        Ok(false)
                    }
                })?;
                if !ok && !restored {
                    self.captures[index] = saved;
                }
                Ok(ok)
            }
            Node::Repeated(body, min, max, greedy) => {
                if matches!(body.as_ref(), Node::Char(_) | Node::AnyChar | Node::CharClass(_)) {
                    self.match_simple_repeat(body, *min, *max, *greedy, pos, cont)
                } else {
                    self.match_repeat(body, *min, *max, *greedy, 0, pos, cont)
                }
            }
            Node::Look { behind, negate, node } => {
                // Where the inner pattern must match: here, or a fixed width back.
                let start = match behind {
                    None => Some(pos),
                    Some(w) => pos.checked_sub(*w),
                };
                let saved = self.captures.clone();
                let found = match start {
                    None => false,
                    Some(start) => self.matches(node, start, &mut |_, end| Ok(behind.is_none() || end == pos))?,
                };
                // A negative lookaround keeps nothing it captured; a positive one keeps its groups.
                if *negate {
                    self.captures = saved.clone();
                }
                if found != *negate && cont(self, pos)? {
                    return Ok(true);
                }
                self.captures = saved;
                Ok(false)
            }
            Node::Backref(index) => {
                let Some((s, e)) = self.captures.get(*index).copied().flatten() else { return Ok(false) };
                let len = e - s;
                if pos + len > n {
                    return Ok(false);
                }
                for i in 0..len {
                    if !self.equal(self.subject.get(pos + i), self.subject.get(s + i)) {
                        return Ok(false);
                    }
                }
                cont(self, pos + len)
            }
        }
    }

    fn match_seq(&mut self, items: &'a [Node], i: usize, pos: usize, cont: Cont<'_, 'a>) -> Res<bool> {
        if i == items.len() {
            return cont(self, pos);
        }
        self.matches(&items[i], pos, &mut |me, next| me.match_seq(items, i + 1, next, &mut *cont))
    }

    fn consumes_one(&self, node: &Node, pos: usize) -> bool {
        if pos >= self.subject.len() {
            return false;
        }
        let c = self.subject.get(pos);
        match node {
            Node::Char(x) => self.equal(c, x),
            Node::AnyChar => self.regex.dot_all() || c != "\n",
            Node::CharClass(cls) => cls.matches(c, self.regex.ignore_case()),
            _ => false,
        }
    }

    fn match_simple_repeat(
        &mut self,
        node: &Node,
        min: usize,
        max: Option<usize>,
        greedy: bool,
        pos: usize,
        cont: Cont<'_, 'a>,
    ) -> Res<bool> {
        let ceiling = max.unwrap_or(usize::MAX);
        if greedy {
            let mut count = 0;
            let mut at = pos;
            while count < ceiling && self.consumes_one(node, at) {
                (self.tick)()?;
                at += 1;
                count += 1;
            }
            while count >= min {
                if cont(self, at)? {
                    return Ok(true);
                }
                if count == 0 {
                    break;
                }
                at -= 1;
                count -= 1;
                (self.tick)()?;
            }
            return Ok(false);
        }
        let mut count = 0;
        let mut at = pos;
        loop {
            (self.tick)()?;
            if count >= min && cont(self, at)? {
                return Ok(true);
            }
            if count >= ceiling || !self.consumes_one(node, at) {
                return Ok(false);
            }
            at += 1;
            count += 1;
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn match_repeat(
        &mut self,
        node: &'a Node,
        min: usize,
        max: Option<usize>,
        greedy: bool,
        count: usize,
        pos: usize,
        cont: Cont<'_, 'a>,
    ) -> Res<bool> {
        (self.tick)()?;
        let ceiling = max.unwrap_or(usize::MAX);
        if greedy {
            if count < ceiling {
                let more = self.matches(node, pos, &mut |me, next| {
                    // A body that matched nothing would repeat for ever.
                    if next == pos {
                        Ok(false)
                    } else {
                        me.match_repeat(node, min, max, greedy, count + 1, next, &mut *cont)
                    }
                })?;
                if more {
                    return Ok(true);
                }
            }
            return if count >= min { cont(self, pos) } else { Ok(false) };
        }
        if count >= min && cont(self, pos)? {
            return Ok(true);
        }
        if count >= ceiling {
            return Ok(false);
        }
        self.matches(node, pos, &mut |me, next| {
            if next == pos {
                Ok(false)
            } else {
                me.match_repeat(node, min, max, greedy, count + 1, next, &mut *cont)
            }
        })
    }

    fn equal(&self, a: &str, b: &str) -> bool {
        if self.regex.ignore_case() {
            case_eq(a, b)
        } else {
            str_eq(a, b)
        }
    }
}

fn is_word(c: &str) -> bool {
    char_is_letter(c) || char_is_number(c) || c == "_"
}

/// The letters of a `(?aimsux)` group at the very start of [pattern], the
/// ones that change how the rest is read.
fn leading_flags(pattern: &str) -> &str {
    let Some(rest) = pattern.strip_prefix("(?") else { return "" };
    let end = rest.find(|c: char| !c.is_ascii_alphabetic()).unwrap_or(rest.len());
    if rest[end..].starts_with(')') {
        &rest[..end]
    } else {
        ""
    }
}

/// [pattern] as `re.VERBOSE` reads it: whitespace and `#` comments dropped,
/// except inside a character class or after a backslash.
fn strip_verbose(pattern: &str) -> String {
    let mut out = String::with_capacity(pattern.len());
    let mut chars = pattern.chars().peekable();
    let mut in_class = false;
    // Inside a class, a `]` straight after `[` or `[^` is a literal.
    let mut class_start = false;
    while let Some(c) = chars.next() {
        if c == '\\' {
            out.push(c);
            if let Some(n) = chars.next() {
                out.push(n);
            }
            class_start = false;
            continue;
        }
        if in_class {
            out.push(c);
            if c == ']' && !class_start {
                in_class = false;
            }
            class_start = class_start && c == '^';
            continue;
        }
        match c {
            '[' => {
                in_class = true;
                class_start = true;
                out.push(c);
            }
            '#' => {
                for n in chars.by_ref() {
                    if n == '\n' {
                        break;
                    }
                }
            }
            c if c.is_whitespace() => {}
            c => out.push(c),
        }
    }
    out
}

/// Bytes as text, a character each (Latin-1), for a bytes pattern or subject.
pub(crate) fn latin1_text(data: &[u8]) -> String {
    data.iter().map(|&b| b as char).collect()
}

/// [latin1_text] undone. Every character came from a byte.
pub(crate) fn latin1_bytes(s: &str) -> Vec<u8> {
    s.chars().map(|c| c as u32 as u8).collect()
}
