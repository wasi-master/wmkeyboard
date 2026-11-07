//! Port of PyRegex.kt: a small backtracking engine behind the `re` namespace.
//!
//! Every step charges the interpreter's step budget (and so its cancel and
//! deadline checks), so a catastrophic pattern like `(a+)+b` runs out of steps
//! instead of hanging. A repeat whose body is one character, class or `.` runs
//! as a loop; anything else counts its depth and gives up past [MAX_DEPTH].
//!
//! Supported: literals, `.`, classes with ranges and negation, `\d \D \w \W \s
//! \S \b \B`, anchors, groups (capturing, `(?:…)`, `(?P<name>…)`), alternation,
//! and `* + ? {m,n}` greedy and lazy. Not supported: backreferences,
//! lookaround, possessive quantifiers. Subjects are matched as grapheme
//! clusters, so `m.start()` counts clusters, as PyMini's do.

use std::collections::HashMap;
use std::rc::Rc;

use super::error::{AbortKind, PyError};
use super::text::{char_is_letter, char_is_number, char_is_whitespace, graphemes, parse_swift_int, str_compare, str_eq, swift_lower, swift_upper};

pub(crate) const IGNORE_CASE: i64 = 2;
pub(crate) const MULTILINE: i64 = 8;
pub(crate) const DOT_ALL: i64 = 16;

/// Longest subject this engine will scan.
pub(crate) const MAX_SUBJECT: usize = 40_000;
/// Deepest nested backtrack before giving up, which bounds the stack.
pub(crate) const MAX_DEPTH: u32 = 2_000;

type Res<T> = Result<T, PyError>;

fn case_eq(a: &str, b: &str) -> bool {
    str_eq(&swift_lower(a), &swift_lower(b))
}

fn verr(message: &str) -> PyError {
    PyError::new(message, "ValueError")
}

/// A subject as grapheme clusters.
pub struct Subject {
    text: Rc<str>,
    b: Rc<[u32]>,
}

impl Subject {
    pub(crate) fn new(text: Rc<str>, b: Rc<[u32]>) -> Subject {
        Subject { text, b }
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
}

/// A compiled regular expression.
pub(crate) struct Regex {
    root: Node,
    pub group_count: usize,
    pub names: Rc<HashMap<String, usize>>,
    flags: i64,
}

impl Regex {
    pub fn compile(pattern: &str, flags: i64) -> Res<Regex> {
        let chars = graphemes(pattern);
        let mut p = RxParser { chars: &chars, pos: 0, group_count: 0, names: HashMap::new(), depth: 0 };
        let root = p.parse_alternation()?;
        if p.pos < chars.len() {
            return Err(verr("unbalanced parenthesis in pattern"));
        }
        Ok(Regex { root, group_count: p.group_count, names: Rc::new(p.names), flags })
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

    /// Every non-overlapping match; a zero-width match advances one character.
    pub fn all_matches(&self, subject: &Rc<Subject>, limit: usize, tick: &mut dyn FnMut() -> Res<()>) -> Res<Vec<Match>> {
        let mut out = Vec::new();
        let mut at = 0;
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
}

impl RxParser<'_> {
    fn peek(&self) -> Option<&str> {
        self.chars.get(self.pos).copied()
    }

    fn parse_alternation(&mut self) -> Res<Node> {
        self.depth += 1;
        if self.depth > 200 {
            return Err(PyError::abort(AbortKind::Recursion, "regular expression nested too deeply to finish", None));
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
                    if self.peek() == Some(":") {
                        self.pos += 1;
                    } else if self.peek() == Some("P") || self.peek() == Some("<") {
                        if self.peek() == Some("P") {
                            self.pos += 1;
                        }
                        if self.peek() != Some("<") {
                            return Err(verr("lookaround is not supported by PyMini"));
                        }
                        self.pos += 1;
                        let mut name = String::new();
                        while let Some(n) = self.peek() {
                            if n == ">" {
                                break;
                            }
                            name.push_str(n);
                            self.pos += 1;
                        }
                        if self.peek() != Some(">") {
                            return Err(verr("unterminated group name"));
                        }
                        self.pos += 1;
                        self.group_count += 1;
                        index = Some(self.group_count);
                        self.names.insert(name, self.group_count);
                    } else {
                        return Err(verr("this group syntax is not supported by PyMini"));
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
                let class = |item: ClassItem| Node::CharClass(Class { negated: false, items: vec![item] });
                Ok(match e.as_str() {
                    "d" => class(ClassItem::Digit(true)),
                    "D" => class(ClassItem::Digit(false)),
                    "w" => class(ClassItem::Word(true)),
                    "W" => class(ClassItem::Word(false)),
                    "s" => class(ClassItem::Space(true)),
                    "S" => class(ClassItem::Space(false)),
                    "b" => Node::WordBoundary(true),
                    "B" => Node::WordBoundary(false),
                    "A" => Node::AnchorStart,
                    "Z" | "z" => Node::AnchorEnd,
                    "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" => {
                        return Err(verr("backreferences are not supported by PyMini"))
                    }
                    other => Node::Char(escape_char(other)),
                })
            }
            other => {
                let other = other.to_string();
                self.pos += 1;
                Ok(Node::Char(other))
            }
        }
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
                match e.as_str() {
                    "d" => {
                        cls.items.push(ClassItem::Digit(true));
                        continue;
                    }
                    "D" => {
                        cls.items.push(ClassItem::Digit(false));
                        continue;
                    }
                    "w" => {
                        cls.items.push(ClassItem::Word(true));
                        continue;
                    }
                    "W" => {
                        cls.items.push(ClassItem::Word(false));
                        continue;
                    }
                    "s" => {
                        cls.items.push(ClassItem::Space(true));
                        continue;
                    }
                    "S" => {
                        cls.items.push(ClassItem::Space(false));
                        continue;
                    }
                    other => escape_char(other),
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
                    if let Some(e) = self.peek() {
                        hi = escape_char(e);
                        self.pos += 1;
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
            return Err(PyError::abort(AbortKind::Recursion, "regular expression nested too deeply to finish", None));
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
