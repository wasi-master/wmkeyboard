//! plugin.json as text: a tree with byte spans, for edits that keep the
//! author's layout, and what the caret is in while the file is being typed.

/// One JSON value and where it is, in bytes.
#[derive(Clone, Debug)]
pub struct Node {
    pub kind: Kind,
    pub start: usize,
    pub end: usize,
}

#[derive(Clone, Debug)]
pub enum Kind {
    Object(Vec<Member>),
    Array(Vec<Node>),
    Str(String),
    Other,
}

#[derive(Clone, Debug)]
pub struct Member {
    pub key: String,
    pub key_start: usize,
    pub value: Node,
}

impl Node {
    pub fn get(&self, key: &str) -> Option<&Member> {
        match &self.kind {
            Kind::Object(m) => m.iter().find(|m| m.key == key),
            _ => None,
        }
    }
}

/// The tree of [text], or None when it is not valid JSON.
pub fn parse(text: &str) -> Option<Node> {
    let mut p = Parser { b: text.as_bytes(), at: 0 };
    p.ws();
    let node = p.value(0)?;
    p.ws();
    (p.at == p.b.len()).then_some(node)
}

struct Parser<'a> {
    b: &'a [u8],
    at: usize,
}

impl Parser<'_> {
    fn ws(&mut self) {
        while self.at < self.b.len() && matches!(self.b[self.at], b' ' | b'\t' | b'\n' | b'\r') {
            self.at += 1;
        }
    }

    fn value(&mut self, depth: usize) -> Option<Node> {
        if depth > 64 {
            return None;
        }
        let start = self.at;
        match *self.b.get(self.at)? {
            b'{' => {
                self.at += 1;
                let mut members = Vec::new();
                self.ws();
                if self.b.get(self.at) == Some(&b'}') {
                    self.at += 1;
                    return Some(Node { kind: Kind::Object(members), start, end: self.at });
                }
                loop {
                    self.ws();
                    let key_start = self.at;
                    let key = self.string()?;
                    self.ws();
                    if self.b.get(self.at) != Some(&b':') {
                        return None;
                    }
                    self.at += 1;
                    self.ws();
                    let value = self.value(depth + 1)?;
                    members.push(Member { key, key_start, value });
                    self.ws();
                    match self.b.get(self.at)? {
                        b',' => self.at += 1,
                        b'}' => {
                            self.at += 1;
                            return Some(Node { kind: Kind::Object(members), start, end: self.at });
                        }
                        _ => return None,
                    }
                }
            }
            b'[' => {
                self.at += 1;
                let mut items = Vec::new();
                self.ws();
                if self.b.get(self.at) == Some(&b']') {
                    self.at += 1;
                    return Some(Node { kind: Kind::Array(items), start, end: self.at });
                }
                loop {
                    self.ws();
                    items.push(self.value(depth + 1)?);
                    self.ws();
                    match self.b.get(self.at)? {
                        b',' => self.at += 1,
                        b']' => {
                            self.at += 1;
                            return Some(Node { kind: Kind::Array(items), start, end: self.at });
                        }
                        _ => return None,
                    }
                }
            }
            b'"' => {
                let s = self.string()?;
                Some(Node { kind: Kind::Str(s), start, end: self.at })
            }
            _ => {
                while self.at < self.b.len() && !matches!(self.b[self.at], b',' | b'}' | b']' | b' ' | b'\t' | b'\n' | b'\r') {
                    self.at += 1;
                }
                (self.at > start).then_some(Node { kind: Kind::Other, start, end: self.at })
            }
        }
    }

    fn string(&mut self) -> Option<String> {
        if self.b.get(self.at) != Some(&b'"') {
            return None;
        }
        let start = self.at;
        self.at += 1;
        while self.at < self.b.len() {
            match self.b[self.at] {
                b'\\' => self.at += 2,
                b'"' => {
                    self.at += 1;
                    let raw = std::str::from_utf8(&self.b[start..self.at]).ok()?;
                    return serde_json::from_str(raw).ok();
                }
                _ => self.at += 1,
            }
        }
        None
    }
}

// ---- where the caret is ----------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Seg {
    Key(String),
    Index(usize),
}

/// What is being typed at the caret.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Context {
    /// The keys and indexes from the top to the innermost container.
    pub path: Vec<Seg>,
    /// A key of that object is being typed (else a value).
    pub key: bool,
    /// For a value of an object: its key.
    pub value_of: Option<String>,
    /// Where the open string starts (its quote), when the caret is inside one.
    pub string_start: Option<usize>,
    /// What the string holds so far.
    pub prefix: String,
}

enum Frame {
    Object { key: Option<String>, want_key: bool },
    Array { index: usize },
}

/// The context at byte [caret] of [text], read leniently: the file is half typed.
pub fn context_at(text: &str, caret: usize) -> Option<Context> {
    let b = &text.as_bytes()[..caret.min(text.len())];
    let mut stack: Vec<Frame> = Vec::new();
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'{' => stack.push(Frame::Object { key: None, want_key: true }),
            b'[' => stack.push(Frame::Array { index: 0 }),
            b'}' | b']' => {
                stack.pop();
            }
            b':' => {
                if let Some(Frame::Object { want_key, .. }) = stack.last_mut() {
                    *want_key = false;
                }
            }
            b',' => match stack.last_mut() {
                Some(Frame::Object { key, want_key }) => {
                    *key = None;
                    *want_key = true;
                }
                Some(Frame::Array { index }) => *index += 1,
                None => {}
            },
            b'"' => {
                let start = i;
                let mut j = i + 1;
                let mut closed = false;
                while j < b.len() {
                    match b[j] {
                        b'\\' => j += 2,
                        b'"' => {
                            closed = true;
                            break;
                        }
                        _ => j += 1,
                    }
                }
                if !closed {
                    let prefix = String::from_utf8_lossy(&b[start + 1..]).to_string();
                    return Some(finish(&stack, Some(start), prefix));
                }
                let s: String = serde_json::from_str(std::str::from_utf8(&b[start..=j]).ok()?).unwrap_or_default();
                if let Some(Frame::Object { key, want_key: true }) = stack.last_mut() {
                    *key = Some(s);
                }
                i = j;
            }
            _ => {}
        }
        i += 1;
    }
    // A bare word being typed: true, a number.
    let word_start = b.iter().rposition(|c| !c.is_ascii_alphanumeric()).map_or(0, |p| p + 1);
    let prefix = String::from_utf8_lossy(&b[word_start..]).to_string();
    (!stack.is_empty()).then(|| finish(&stack, None, prefix))
}

fn finish(stack: &[Frame], string_start: Option<usize>, prefix: String) -> Context {
    let mut path = Vec::new();
    for f in &stack[..stack.len().saturating_sub(1)] {
        match f {
            Frame::Object { key, .. } => path.push(Seg::Key(key.clone().unwrap_or_default())),
            Frame::Array { index } => path.push(Seg::Index(*index)),
        }
    }
    let (key, value_of) = match stack.last() {
        Some(Frame::Object { key, want_key }) => (*want_key, if *want_key { None } else { key.clone() }),
        _ => (false, None),
    };
    Context { path, key, value_of, string_start, prefix }
}

// ---- edits that keep the layout ---------------------------------------------

/// A text edit in bytes: replace `start..end` with `text`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Edit {
    pub start: usize,
    pub end: usize,
    pub text: String,
}

/// The indentation of the line [at] is on.
fn indent_at(text: &str, at: usize) -> String {
    let line_start = text[..at].rfind('\n').map_or(0, |p| p + 1);
    text[line_start..].chars().take_while(|c| *c == ' ' || *c == '\t').collect()
}

/// One indentation step as the file writes it.
fn step(text: &str) -> String {
    for line in text.lines().skip(1) {
        let indent: String = line.chars().take_while(|c| *c == ' ' || *c == '\t').collect();
        if !indent.is_empty() {
            return indent;
        }
    }
    "  ".into()
}

/// Add [item] (JSON text) to the end of [array].
pub fn append(text: &str, array: &Node, item: &str) -> Edit {
    let Kind::Array(items) = &array.kind else { return Edit { start: array.start, end: array.end, text: format!("[{item}]") } };
    match items.last() {
        None => Edit { start: array.start, end: array.end, text: format!("[{item}]") },
        Some(last) => {
            let multiline = text[array.start..array.end].contains('\n');
            let sep = if multiline { format!(",\n{}", indent_at(text, last.start)) } else { ", ".to_string() };
            Edit { start: last.end, end: last.end, text: format!("{sep}{item}") }
        }
    }
}

/// Add `"key": value` to the end of [object].
pub fn add_member(text: &str, object: &Node, key: &str, value: &str) -> Edit {
    let Kind::Object(members) = &object.kind else { return Edit { start: object.start, end: object.end, text: format!("{{\"{key}\": {value}}}") } };
    let quoted = serde_json::to_string(key).unwrap_or_default();
    match members.last() {
        None => {
            let indent = indent_at(text, object.start);
            Edit { start: object.start, end: object.end, text: format!("{{\n{indent}{}{quoted}: {value}\n{indent}}}", step(text)) }
        }
        Some(last) => {
            let multiline = text[object.start..object.end].contains('\n');
            let sep = if multiline { format!(",\n{}", indent_at(text, last.key_start)) } else { ", ".to_string() };
            Edit { start: last.value.end, end: last.value.end, text: format!("{sep}{quoted}: {value}") }
        }
    }
}

/// Set `key` of [object] to [value]: replace its value, or add the member.
pub fn set_member(text: &str, object: &Node, key: &str, value: &str) -> Edit {
    match object.get(key) {
        Some(m) => Edit { start: m.value.start, end: m.value.end, text: value.to_string() },
        None => add_member(text, object, key, value),
    }
}

/// [text] with [edits] made, applied from the last.
pub fn apply(text: &str, mut edits: Vec<Edit>) -> String {
    edits.sort_by_key(|e| std::cmp::Reverse(e.start));
    let mut out = text.to_string();
    for e in edits {
        out.replace_range(e.start..e.end, &e.text);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appends_in_the_file_layout() {
        let text = "{\n  \"id\": \"a.b.c\",\n  \"permissions\": [\"storage\"]\n}\n";
        let tree = parse(text).unwrap();
        let p = &tree.get("permissions").unwrap().value;
        let out = apply(text, vec![append(text, p, "\"network\"")]);
        assert_eq!(out, "{\n  \"id\": \"a.b.c\",\n  \"permissions\": [\"storage\", \"network\"]\n}\n");
        let out = apply(text, vec![add_member(text, &tree, "hosts", "[\"api.example.com\"]")]);
        assert!(out.contains("[\"storage\"],\n  \"hosts\": [\"api.example.com\"]\n}"), "{out}");
    }

    #[test]
    fn context_while_typing() {
        let text = "{\n  \"permissions\": [\"sto";
        let c = context_at(text, text.len()).unwrap();
        assert_eq!(c.path, vec![Seg::Key("permissions".into())]);
        assert!(!c.key);
        assert_eq!(c.prefix, "sto");
        let text = "{\n  \"tool\": {\"ic";
        let c = context_at(text, text.len()).unwrap();
        assert_eq!(c.path, vec![Seg::Key("tool".into())]);
        assert!(c.key);
        let text = "{\"settings\": [{\"key\": \"u\", \"type\": \"";
        let c = context_at(text, text.len()).unwrap();
        assert_eq!(c.path, vec![Seg::Key("settings".into()), Seg::Index(0)]);
        assert_eq!(c.value_of.as_deref(), Some("type"));
    }
}
