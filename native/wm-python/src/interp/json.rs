//! Port of PyJson.kt: JSON for script values, behind the `json` namespace.
//! The encoder walks values directly so dict order survives a round trip, and
//! the decoder keeps object keys in file order.

use super::value::DictMap;

use super::error::{AbortKind, PyError};
use super::text::{graphemes, longer_than, parse_swift_double, parse_swift_int, str_compare};
use super::builtins::is_scalar;
use super::value::{format_double, payload_of, HKey, Value};

type Res<T> = Result<T, PyError>;

/// Deepest nesting accepted either way, so a hostile value can't recurse the stack out.
const MAX_DEPTH: u32 = 100;

fn verr(m: &str) -> PyError {
    PyError::new(m, "ValueError")
}

/// How `json.dumps` writes: its `indent`, `sort_keys`, `ensure_ascii` and `separators`.
pub(crate) struct EncodeOptions {
    /// What one level of nesting indents by; None writes everything on one line.
    pub indent: Option<String>,
    pub sort_keys: bool,
    /// Write every character past ASCII as a `\uXXXX` escape.
    pub ensure_ascii: bool,
    /// Between items, and between a key and its value.
    pub item_separator: String,
    pub key_separator: String,
}

impl EncodeOptions {
    /// Python's defaults for an indent: `", "` on one line, `","` when indented.
    pub fn new(indent: Option<String>) -> EncodeOptions {
        let item_separator = if indent.is_some() { "," } else { ", " }.to_string();
        EncodeOptions { indent, sort_keys: false, ensure_ascii: false, item_separator, key_separator: ": ".into() }
    }
}

/// Encode [value].
pub(crate) fn encode(value: &Value, options: &EncodeOptions, limit: usize, charge: &mut dyn FnMut() -> Res<()>) -> Res<String> {
    let mut out = String::new();
    write(value, &mut out, options, 0, limit, charge)?;
    Ok(out)
}

/// `sort_keys`: numbers by value, strings by code point; anything else mixed in is an error, as in Python.
fn key_order(a: &HKey, b: &HKey) -> Res<std::cmp::Ordering> {
    let number = |k: &HKey| match k {
        HKey::Bool(b) => Some(*b as i64 as f64),
        HKey::Int(i) => Some(*i as f64),
        HKey::Float(f) => Some(*f),
        _ => None,
    };
    match (a, b) {
        (HKey::Str(x), HKey::Str(y)) => Ok(str_compare(x, y)),
        _ => match (number(a), number(b)) {
            (Some(x), Some(y)) => Ok(x.partial_cmp(&y).unwrap_or(std::cmp::Ordering::Equal)),
            _ => Err(PyError::new("'<' not supported between keys of different types", "TypeError")),
        },
    }
}

fn write(v: &Value, out: &mut String, options: &EncodeOptions, depth: u32, limit: usize, charge: &mut dyn FnMut() -> Res<()>) -> Res<()> {
    charge()?;
    if longer_than(out, limit) {
        return Err(PyError::abort(AbortKind::StringCap, "string too large", None));
    }
    if depth > MAX_DEPTH * 2 {
        return Err(PyError::abort(AbortKind::Recursion, "maximum recursion depth exceeded", None));
    }
    let indent = options.indent.as_deref();
    let pad = indent.map(|i| i.repeat(depth as usize + 1)).unwrap_or_default();
    let close_pad = indent.map(|i| i.repeat(depth as usize)).unwrap_or_default();
    let newline = if indent.is_none() { "" } else { "\n" };
    let separator = format!("{}{newline}", options.item_separator);
    let separator = separator.as_str();
    // A subclass of a built-in type writes as its value.
    if let Some(p) = payload_of(v) {
        return write(p, out, options, depth, limit, charge);
    }
    let items: Vec<Value> = match v {
        Value::None => {
            out.push_str("null");
            return Ok(());
        }
        Value::Bool(b) => {
            out.push_str(if *b { "true" } else { "false" });
            return Ok(());
        }
        Value::Int(i) => {
            out.push_str(&i.to_string());
            return Ok(());
        }
        Value::Big(b) => {
            out.push_str(&super::bigint::to_decimal(b).map_err(|(m, k)| PyError::new(m, k))?);
            return Ok(());
        }
        Value::Float(f) => {
            out.push_str(&if f.is_finite() { format_double(*f) } else { "null".into() });
            return Ok(());
        }
        Value::Str(s) => {
            quoted(s, out, options.ensure_ascii);
            return Ok(());
        }
        Value::Dict(d) => {
            let mut pairs: Vec<(HKey, Value)> = d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
            if options.sort_keys {
                let mut failed = None;
                pairs.sort_by(|(a, _), (b, _)| match key_order(a, b) {
                    Ok(o) => o,
                    Err(e) => {
                        failed.get_or_insert(e);
                        std::cmp::Ordering::Equal
                    }
                });
                if let Some(e) = failed {
                    return Err(e);
                }
            }
            if pairs.is_empty() {
                out.push_str("{}");
                return Ok(());
            }
            out.push('{');
            out.push_str(newline);
            for (i, (k, value)) in pairs.iter().enumerate() {
                if i > 0 {
                    out.push_str(separator);
                }
                out.push_str(&pad);
                quoted(&key_text(k)?, out, options.ensure_ascii);
                out.push_str(&options.key_separator);
                write(value, out, options, depth + 1, limit, charge)?;
            }
            out.push_str(newline);
            out.push_str(&close_pad);
            out.push('}');
            return Ok(());
        }
        Value::List(l) => l.borrow().clone(),
        Value::Tuple(t) => t.as_ref().clone(),
        Value::Set(s) => s.borrow().iter().map(HKey::to_value).collect(),
        other => return Err(PyError::new(format!("Object of type {} is not JSON serializable", other.type_name()), "TypeError")),
    };
    if items.is_empty() {
        out.push_str("[]");
        return Ok(());
    }
    out.push('[');
    out.push_str(newline);
    for (i, item) in items.iter().enumerate() {
        if i > 0 {
            out.push_str(separator);
        }
        out.push_str(&pad);
        write(item, out, options, depth + 1, limit, charge)?;
    }
    out.push_str(newline);
    out.push_str(&close_pad);
    out.push(']');
    Ok(())
}

/// A dict key as JSON writes it: strings as they are, numbers, booleans and
/// None as JSON would spell them.
fn key_text(k: &HKey) -> Res<String> {
    Ok(match k.base() {
        HKey::Str(s) => s.to_string(),
        HKey::Bool(b) => if *b { "true" } else { "false" }.to_string(),
        HKey::None => "null".to_string(),
        HKey::Int(i) => i.to_string(),
        HKey::Float(f) => format_double(*f),
        other => return Err(PyError::new(format!("keys must be str, int, float, bool or None, not {}", other.to_value().type_name()), "TypeError")),
    })
}

fn quoted(s: &str, out: &mut String, ascii: bool) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            '\u{08}' => out.push_str("\\b"),
            '\u{0c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c if ascii && !c.is_ascii() => {
                let mut units = [0u16; 2];
                for u in c.encode_utf16(&mut units) {
                    out.push_str(&format!("\\u{:04x}", u));
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Parse [text]. Object keys keep the file's own order.
pub(crate) fn decode(text: &str, charge: &mut dyn FnMut() -> Res<()>) -> Res<Value> {
    let chars = graphemes(text);
    let mut p = Parser { chars: &chars, i: 0, depth: 0, charge };
    p.skip_space();
    let v = p.value()?;
    p.skip_space();
    if p.i < chars.len() {
        return Err(verr("could not parse JSON"));
    }
    Ok(v)
}

/// A tiny recursive-descent reader over grapheme clusters, as PyMini's is.
struct Parser<'a, 'c> {
    chars: &'a [&'a str],
    i: usize,
    depth: u32,
    charge: &'c mut dyn FnMut() -> Res<()>,
}

impl Parser<'_, '_> {
    fn at(&self) -> Option<&str> {
        self.chars.get(self.i).copied()
    }

    fn skip_space(&mut self) {
        while matches!(self.at(), Some(" " | "\n" | "\t" | "\r")) {
            self.i += 1;
        }
    }

    fn value(&mut self) -> Res<Value> {
        (self.charge)()?;
        match self.at() {
            None => Err(verr("unexpected end of JSON")),
            Some("{") => self.nested(Self::obj),
            Some("[") => self.nested(Self::array),
            Some("\"") => Ok(Value::str(self.string()?)),
            Some("t") => self.literal("true").map(|_| Value::Bool(true)),
            Some("f") => self.literal("false").map(|_| Value::Bool(false)),
            Some("n") => self.literal("null").map(|_| Value::None),
            _ => self.number(),
        }
    }

    fn nested(&mut self, f: fn(&mut Self) -> Res<Value>) -> Res<Value> {
        self.depth += 1;
        if self.depth >= MAX_DEPTH {
            return Err(PyError::abort(AbortKind::Recursion, "JSON nested too deeply", None));
        }
        let r = f(self);
        self.depth -= 1;
        r
    }

    fn literal(&mut self, word: &str) -> Res<()> {
        for c in word.chars() {
            match self.at() {
                Some(x) if x.len() == 1 && x.starts_with(c) => self.i += 1,
                _ => return Err(verr("invalid JSON literal")),
            }
        }
        Ok(())
    }

    fn obj(&mut self) -> Res<Value> {
        self.i += 1;
        let mut d = DictMap::default();
        self.skip_space();
        if self.at() == Some("}") {
            self.i += 1;
            return Ok(Value::new_dict(d));
        }
        loop {
            self.skip_space();
            let key = self.string()?;
            self.skip_space();
            if self.at() != Some(":") {
                return Err(verr("expected ':' in JSON"));
            }
            self.i += 1;
            self.skip_space();
            let v = self.value()?;
            d.insert(HKey::str(&key), v);
            self.skip_space();
            match self.at() {
                None => return Err(verr("unexpected end of JSON")),
                Some(",") => self.i += 1,
                Some("}") => {
                    self.i += 1;
                    return Ok(Value::new_dict(d));
                }
                _ => return Err(verr("expected ',' or '}' in JSON")),
            }
        }
    }

    fn array(&mut self) -> Res<Value> {
        self.i += 1;
        let mut items = Vec::new();
        self.skip_space();
        if self.at() == Some("]") {
            self.i += 1;
            return Ok(Value::list(items));
        }
        loop {
            self.skip_space();
            items.push(self.value()?);
            self.skip_space();
            match self.at() {
                None => return Err(verr("unexpected end of JSON")),
                Some(",") => self.i += 1,
                Some("]") => {
                    self.i += 1;
                    return Ok(Value::list(items));
                }
                _ => return Err(verr("expected ',' or ']' in JSON")),
            }
        }
    }

    fn string(&mut self) -> Res<String> {
        if self.at() != Some("\"") {
            return Err(verr("expected a JSON string"));
        }
        self.i += 1;
        let mut out = String::new();
        while let Some(c) = self.at() {
            if c == "\"" {
                self.i += 1;
                return Ok(out);
            }
            if c == "\\" {
                self.i += 1;
                let Some(e) = self.at() else { break };
                match e {
                    "n" => out.push('\n'),
                    "t" => out.push('\t'),
                    "r" => out.push('\r'),
                    "b" => out.push('\u{0008}'),
                    "f" => out.push('\u{000C}'),
                    "u" => {
                        let start = self.i + 1;
                        let code = if start + 4 <= self.chars.len() {
                            parse_swift_int(&self.chars[start..start + 4].concat(), 16)
                        } else {
                            None
                        };
                        // Like PyMini, a lone surrogate (half of a 😀 pair) is refused.
                        match code {
                            Some(c) if is_scalar(c) => out.push(char::from_u32(c as u32).unwrap()),
                            _ => return Err(verr("invalid \\u escape in JSON")),
                        }
                        self.i += 4;
                    }
                    other => out.push_str(other),
                }
                self.i += 1;
                continue;
            }
            out.push_str(c);
            self.i += 1;
        }
        Err(verr("unterminated JSON string"))
    }

    fn number(&mut self) -> Res<Value> {
        let mut text = String::new();
        let mut is_double = false;
        while let Some(c) = self.at() {
            if c.len() == 1 && "0123456789+-.eE".contains(c) {
                if c == "." || c == "e" || c == "E" {
                    is_double = true;
                }
                text.push_str(c);
                self.i += 1;
            } else {
                break;
            }
        }
        if !is_double {
            if let Some(i) = parse_swift_int(&text, 10) {
                return Ok(Value::Int(i));
            }
            // A whole number past 64 bits is an int, as Python's json reads it.
            let (negative, digits) = match text.strip_prefix('-') {
                Some(d) => (true, d),
                None => (false, text.as_str()),
            };
            if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
                match super::bigint::parse_digits(digits, 10, negative) {
                    Ok(Some(v)) => return Ok(v),
                    Ok(None) => {}
                    Err((m, k)) => return Err(PyError::new(m, k)),
                }
            }
        }
        parse_swift_double(&text).map(Value::Float).ok_or_else(|| verr("invalid number in JSON"))
    }
}
