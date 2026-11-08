//! Port of PyJson.kt: JSON for script values, behind the `json` namespace.
//! The encoder walks values directly so dict order survives a round trip, and
//! the decoder keeps object keys in file order.

use indexmap::IndexMap;

use super::error::{AbortKind, PyError};
use super::text::{graphemes, longer_than, parse_swift_double, parse_swift_int};
use super::builtins::is_scalar;
use super::value::{format_double, py_str, HKey, Value};

type Res<T> = Result<T, PyError>;

/// Deepest nesting accepted either way, so a hostile value can't recurse the stack out.
const MAX_DEPTH: u32 = 100;

fn verr(m: &str) -> PyError {
    PyError::new(m, "ValueError")
}

/// Encode [value]. A None [indent] uses Python's `", "` / `": "` separators; a number pretty-prints.
pub(crate) fn encode(value: &Value, indent: Option<i64>, limit: usize, charge: &mut dyn FnMut() -> Res<()>) -> Res<String> {
    let mut out = String::new();
    write(value, &mut out, indent, 0, limit, charge)?;
    Ok(out)
}

fn write(v: &Value, out: &mut String, indent: Option<i64>, depth: u32, limit: usize, charge: &mut dyn FnMut() -> Res<()>) -> Res<()> {
    charge()?;
    if longer_than(out, limit) {
        return Err(PyError::abort(AbortKind::StringCap, "string too large", None));
    }
    if depth > MAX_DEPTH * 2 {
        return Err(PyError::abort(AbortKind::Recursion, "maximum recursion depth exceeded", None));
    }
    let pad = indent.map(|i| " ".repeat((i * (depth as i64 + 1)).max(0) as usize)).unwrap_or_default();
    let close_pad = indent.map(|i| " ".repeat((i * depth as i64).max(0) as usize)).unwrap_or_default();
    let newline = if indent.is_none() { "" } else { "\n" };
    let separator = if indent.is_none() { ", " } else { ",\n" };
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
        Value::Float(f) => {
            out.push_str(&if f.is_finite() { format_double(*f) } else { "null".into() });
            return Ok(());
        }
        Value::Str(s) => {
            quoted(s, out);
            return Ok(());
        }
        Value::Dict(d) => {
            let pairs: Vec<(HKey, Value)> = d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
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
                quoted(&py_str(&k.to_value()), out);
                out.push_str(": ");
                write(value, out, indent, depth + 1, limit, charge)?;
            }
            out.push_str(newline);
            out.push_str(&close_pad);
            out.push('}');
            return Ok(());
        }
        Value::List(l) => l.borrow().clone(),
        Value::Tuple(t) => t.as_ref().clone(),
        Value::Set(s) => s.borrow().iter().map(HKey::to_value).collect(),
        other => return Err(PyError::new(format!("{} is not JSON serializable", other.type_name()), "TypeError")),
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
        write(item, out, indent, depth + 1, limit, charge)?;
    }
    out.push_str(newline);
    out.push_str(&close_pad);
    out.push(']');
    Ok(())
}

fn quoted(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
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
        let mut d = IndexMap::new();
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
        }
        parse_swift_double(&text).map(Value::Float).ok_or_else(|| verr("invalid number in JSON"))
    }
}
