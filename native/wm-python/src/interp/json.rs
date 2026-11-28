//! JSON for script values, behind the `json` namespace: begun as a port of
//! PyJson.kt, now reading and writing as CPython's json module does, with
//! its C accelerator's error messages. The encoder walks values directly so
//! dict order survives a round trip, and the decoder keeps object keys in
//! file order. The hooks (`default`, `object_hook`, `parse_float` and the
//! rest) are script functions the [Host] calls.

use super::value::DictMap;

use super::error::{AbortKind, PyError};
use super::text::{longer_than, str_compare};
use super::value::{format_double, payload_of, py_identical, HKey, Value};

type Res<T> = Result<T, PyError>;

/// Deepest nesting read or written, about where CPython's recursion limit
/// stops its json; past it, or with the native stack running low, a
/// RecursionError a script can catch.
const MAX_DEPTH: u32 = 1000;

/// What the encoder and decoder ask of the interpreter.
pub(crate) trait Host {
    /// Spend one step of the budget.
    fn charge(&mut self) -> Res<()>;
    /// Call a script function: `default`, a hook, a `parse_*` function.
    fn call(&mut self, f: &Value, args: Vec<Value>) -> Res<Value>;
    /// Whether there is native stack left for one more level of nesting.
    fn stack_room(&self) -> bool {
        true
    }
}

/// How `json.dumps` writes: CPython's JSONEncoder options.
pub(crate) struct EncodeOptions {
    /// What one level of nesting indents by; None writes everything on one line.
    pub indent: Option<String>,
    pub sort_keys: bool,
    /// Write every character past ASCII as a `\uXXXX` escape.
    pub ensure_ascii: bool,
    /// Between items, and between a key and its value.
    pub item_separator: String,
    pub key_separator: String,
    /// Leave out a key that isn't a str, int, float, bool or None, rather than fail.
    pub skipkeys: bool,
    /// Write NaN and the infinities as `NaN`, `Infinity` and `-Infinity`, rather than fail.
    pub allow_nan: bool,
    /// Refuse a list or dict that contains itself, with a ValueError.
    pub check_circular: bool,
    /// What a value JSON can't hold is first turned into; None fails with a TypeError.
    pub default: Option<Value>,
}

impl EncodeOptions {
    /// Python's defaults for an indent: `", "` on one line, `","` when indented.
    pub fn new(indent: Option<String>) -> EncodeOptions {
        let item_separator = if indent.is_some() { "," } else { ", " }.to_string();
        EncodeOptions {
            indent,
            sort_keys: false,
            ensure_ascii: false,
            item_separator,
            key_separator: ": ".into(),
            skipkeys: false,
            allow_nan: true,
            check_circular: true,
            default: None,
        }
    }
}

/// Encode [value].
pub(crate) fn encode(value: &Value, options: &EncodeOptions, limit: usize, host: &mut dyn Host) -> Res<String> {
    let mut w = Writer { out: String::new(), options, limit, host, markers: Vec::new() };
    w.write(value, 0)?;
    Ok(w.out)
}

/// A float as JSON writes it: its repr, or one of JavaScript's names for the three that aren't numbers.
fn float_text(f: f64, allow_nan: bool) -> Res<String> {
    if f.is_finite() {
        return Ok(format_double(f));
    }
    if !allow_nan {
        return Err(PyError::new(format!("Out of range float values are not JSON compliant: {}", format_double(f)), "ValueError"));
    }
    Ok(if f.is_nan() {
        "NaN"
    } else if f > 0.0 {
        "Infinity"
    } else {
        "-Infinity"
    }
    .into())
}

fn key_type(k: &HKey) -> String {
    k.to_value().type_name()
}

/// `sort_keys`: numbers by value, strings by code point; anything else mixed in is an error, as in Python.
fn key_order(a: &HKey, b: &HKey) -> Res<std::cmp::Ordering> {
    let number = |k: &HKey| match k {
        HKey::Bool(b) => Some(*b as i64 as f64),
        HKey::Int(i) => Some(*i as f64),
        HKey::Float(f) => Some(*f),
        _ => None,
    };
    match (a.base(), b.base()) {
        (HKey::Str(x), HKey::Str(y)) => Ok(str_compare(x, y)),
        (x, y) => match (number(x), number(y)) {
            (Some(x), Some(y)) => Ok(x.partial_cmp(&y).unwrap_or(std::cmp::Ordering::Equal)),
            _ => Err(PyError::new(
                format!("'<' not supported between instances of '{}' and '{}'", key_type(a), key_type(b)),
                "TypeError",
            )),
        },
    }
}

struct Writer<'a> {
    out: String,
    options: &'a EncodeOptions,
    limit: usize,
    host: &'a mut dyn Host,
    /// The lists, dicts and `default` arguments being written, outermost first.
    markers: Vec<Value>,
}

impl Writer<'_> {
    fn enter(&mut self, v: &Value) -> Res<()> {
        if self.options.check_circular {
            if self.markers.iter().any(|m| py_identical(m, v)) {
                return Err(PyError::new("Circular reference detected", "ValueError"));
            }
            self.markers.push(v.clone());
        }
        Ok(())
    }

    fn leave(&mut self) {
        if self.options.check_circular {
            self.markers.pop();
        }
    }

    fn newline(&mut self, depth: u32) {
        if let Some(indent) = &self.options.indent {
            self.out.push('\n');
            for _ in 0..depth {
                self.out.push_str(indent);
            }
        }
    }

    fn write(&mut self, v: &Value, depth: u32) -> Res<()> {
        self.host.charge()?;
        if longer_than(&self.out, self.limit) {
            return Err(PyError::abort(AbortKind::StringCap, "string too large", None));
        }
        if depth > MAX_DEPTH || !self.host.stack_room() {
            return Err(PyError::new("maximum recursion depth exceeded while encoding a JSON object", "RecursionError"));
        }
        // A subclass of a built-in type writes as its value.
        if let Some(p) = payload_of(v) {
            return self.write(p, depth);
        }
        let ascii = self.options.ensure_ascii;
        let items: Vec<Value> = match v {
            Value::None => {
                self.out.push_str("null");
                return Ok(());
            }
            Value::Bool(b) => {
                self.out.push_str(if *b { "true" } else { "false" });
                return Ok(());
            }
            Value::Int(i) => {
                self.out.push_str(&i.to_string());
                return Ok(());
            }
            Value::Big(b) => {
                self.out.push_str(&super::bigint::to_decimal(b).map_err(|(m, k)| PyError::new(m, k))?);
                return Ok(());
            }
            Value::Float(f) => {
                let text = float_text(*f, self.options.allow_nan)?;
                self.out.push_str(&text);
                return Ok(());
            }
            Value::Str(s) => {
                quoted(s, &mut self.out, ascii);
                return Ok(());
            }
            Value::Dict(d) => {
                let mut pairs: Vec<(HKey, Value)> = d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                if self.options.sort_keys {
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
                    self.out.push_str("{}");
                    return Ok(());
                }
                self.enter(v)?;
                self.out.push('{');
                let mut first = true;
                for (k, value) in &pairs {
                    let Some(key) = key_text(k, self.options)? else { continue };
                    if !first {
                        self.out.push_str(&self.options.item_separator);
                    }
                    first = false;
                    self.newline(depth + 1);
                    quoted(&key, &mut self.out, ascii);
                    self.out.push_str(&self.options.key_separator);
                    self.write(value, depth + 1)?;
                }
                if !first {
                    self.newline(depth);
                }
                self.out.push('}');
                self.leave();
                return Ok(());
            }
            Value::List(l) => l.borrow().clone(),
            Value::Tuple(t) => t.as_ref().clone(),
            // Not in CPython, which refuses a set: PyMini wrote one as a list,
            // and still does when no `default` says otherwise.
            Value::Set(s) if self.options.default.is_none() => s.borrow().iter().map(HKey::to_value).collect(),
            other => {
                let Some(default) = self.options.default.clone() else {
                    return Err(PyError::new(format!("Object of type {} is not JSON serializable", other.type_name()), "TypeError"));
                };
                self.enter(other)?;
                let replacement = self.host.call(&default, vec![other.clone()])?;
                self.write(&replacement, depth)?;
                self.leave();
                return Ok(());
            }
        };
        if items.is_empty() {
            self.out.push_str("[]");
            return Ok(());
        }
        self.enter(v)?;
        self.out.push('[');
        for (i, item) in items.iter().enumerate() {
            if i > 0 {
                self.out.push_str(&self.options.item_separator);
            }
            self.newline(depth + 1);
            self.write(item, depth + 1)?;
        }
        self.newline(depth);
        self.out.push(']');
        self.leave();
        Ok(())
    }
}

/// A dict key as JSON writes it: strings as they are, numbers, booleans and
/// None as JSON would spell them. None for a key `skipkeys` leaves out.
fn key_text(k: &HKey, options: &EncodeOptions) -> Res<Option<String>> {
    Ok(Some(match k.base() {
        HKey::Str(s) => s.to_string(),
        HKey::Bool(b) => if *b { "true" } else { "false" }.to_string(),
        HKey::None => "null".to_string(),
        HKey::Int(i) => i.to_string(),
        HKey::Float(f) => float_text(*f, options.allow_nan)?,
        _ if options.skipkeys => return Ok(None),
        _ => return Err(PyError::new(format!("keys must be str, int, float, bool or None, not {}", key_type(k)), "TypeError")),
    }))
}

/// [s] as a JSON string, quotes and all.
pub(crate) fn quoted(s: &str, out: &mut String, ascii: bool) {
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

/// How `json.loads` reads: CPython's JSONDecoder options. A hook left None
/// is the built-in behaviour, done without calling into the script.
#[derive(Default)]
pub(crate) struct DecodeOptions {
    pub object_hook: Option<Value>,
    pub object_pairs_hook: Option<Value>,
    pub parse_float: Option<Value>,
    pub parse_int: Option<Value>,
    pub parse_constant: Option<Value>,
    /// Refuse control characters inside strings, as JSON does.
    pub strict: bool,
}

/// Why decoding stopped: a JSONDecodeError's message and the byte offset it
/// points at, or an error of its own (from a hook, or the sandbox).
pub(crate) enum DecodeError {
    At(&'static str, usize),
    Other(PyError),
}

impl From<PyError> for DecodeError {
    fn from(e: PyError) -> Self {
        DecodeError::Other(e)
    }
}

type DRes<T> = Result<T, DecodeError>;

fn is_space(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | b'\r')
}

/// Past the JSON whitespace at byte [i] of [text].
pub(crate) fn skip_space(text: &str, mut i: usize) -> usize {
    let b = text.as_bytes();
    while i < b.len() && is_space(b[i]) {
        i += 1;
    }
    i
}

/// One value from byte [start] of [text], as JSONDecoder.raw_decode reads
/// it, and the byte offset just past it.
pub(crate) fn decode_at(text: &str, start: usize, options: &DecodeOptions, host: &mut dyn Host) -> DRes<(Value, usize)> {
    let mut p = Parser { text, b: text.as_bytes(), depth: 0, options, host };
    let (v, end) = p.value(start)?;
    Ok((v, end))
}

/// The whole of [text] as one value, as JSONDecoder.decode reads it:
/// whitespace either side, and nothing else after.
pub(crate) fn decode(text: &str, options: &DecodeOptions, host: &mut dyn Host) -> DRes<Value> {
    let (v, end) = decode_at(text, skip_space(text, 0), options, host)?;
    let end = skip_space(text, end);
    if end != text.len() {
        return Err(DecodeError::At("Extra data", end));
    }
    Ok(v)
}

/// A recursive-descent reader over the bytes of the text: everything JSON
/// spells with is ASCII, and a string's other characters are copied whole.
struct Parser<'a, 'h> {
    text: &'a str,
    b: &'a [u8],
    depth: u32,
    options: &'a DecodeOptions,
    host: &'h mut dyn Host,
}

impl Parser<'_, '_> {
    fn starts(&self, i: usize, word: &str) -> bool {
        self.b[i..].starts_with(word.as_bytes())
    }

    /// The value at byte [i]: "Expecting value" there when there is none.
    fn value(&mut self, i: usize) -> DRes<(Value, usize)> {
        self.host.charge()?;
        let Some(&c) = self.b.get(i) else { return Err(DecodeError::At("Expecting value", i)) };
        match c {
            b'"' => {
                let (s, end) = self.string(i + 1)?;
                Ok((Value::str(s), end))
            }
            b'{' => self.nested(i + 1, Self::object),
            b'[' => self.nested(i + 1, Self::array),
            b'n' if self.starts(i, "null") => Ok((Value::None, i + 4)),
            b't' if self.starts(i, "true") => Ok((Value::Bool(true), i + 4)),
            b'f' if self.starts(i, "false") => Ok((Value::Bool(false), i + 5)),
            b'N' if self.starts(i, "NaN") => Ok((self.constant("NaN", f64::NAN)?, i + 3)),
            b'I' if self.starts(i, "Infinity") => Ok((self.constant("Infinity", f64::INFINITY)?, i + 8)),
            b'-' if self.starts(i, "-Infinity") => Ok((self.constant("-Infinity", f64::NEG_INFINITY)?, i + 9)),
            _ => self.number(i),
        }
    }

    fn constant(&mut self, name: &str, value: f64) -> Res<Value> {
        match &self.options.parse_constant {
            Some(f) => self.host.call(f, vec![Value::str(name)]),
            None => Ok(Value::Float(value)),
        }
    }

    fn nested(&mut self, i: usize, f: fn(&mut Self, usize) -> DRes<(Value, usize)>) -> DRes<(Value, usize)> {
        self.depth += 1;
        if self.depth >= MAX_DEPTH || !self.host.stack_room() {
            self.depth -= 1;
            return Err(PyError::new("maximum recursion depth exceeded while decoding a JSON document", "RecursionError").into());
        }
        let r = f(self, i);
        self.depth -= 1;
        r
    }

    fn at(&self, i: usize) -> Option<u8> {
        self.b.get(i).copied()
    }

    fn object(&mut self, i: usize) -> DRes<(Value, usize)> {
        let mut pairs: Vec<(String, Value)> = Vec::new();
        let mut i = skip_space(self.text, i);
        if self.at(i) != Some(b'}') {
            loop {
                if self.at(i) != Some(b'"') {
                    return Err(DecodeError::At("Expecting property name enclosed in double quotes", i));
                }
                let (key, end) = self.string(i + 1)?;
                i = skip_space(self.text, end);
                if self.at(i) != Some(b':') {
                    return Err(DecodeError::At("Expecting ':' delimiter", i));
                }
                i = skip_space(self.text, i + 1);
                let (v, end) = self.value(i)?;
                pairs.push((key, v));
                i = skip_space(self.text, end);
                if self.at(i) == Some(b'}') {
                    break;
                }
                if self.at(i) != Some(b',') {
                    return Err(DecodeError::At("Expecting ',' delimiter", i));
                }
                let comma = i;
                i = skip_space(self.text, i + 1);
                if self.at(i) == Some(b'}') {
                    return Err(DecodeError::At("Illegal trailing comma before end of object", comma));
                }
            }
        }
        let end = i + 1;
        if let Some(hook) = self.options.object_pairs_hook.clone() {
            let list = pairs.into_iter().map(|(k, v)| Value::tuple(vec![Value::str(k), v])).collect();
            return Ok((self.host.call(&hook, vec![Value::list(list)])?, end));
        }
        let mut d = DictMap::default();
        for (k, v) in pairs {
            d.insert(HKey::str(&k), v);
        }
        let dict = Value::new_dict(d);
        if let Some(hook) = self.options.object_hook.clone() {
            return Ok((self.host.call(&hook, vec![dict])?, end));
        }
        Ok((dict, end))
    }

    fn array(&mut self, i: usize) -> DRes<(Value, usize)> {
        let mut items = Vec::new();
        let mut i = skip_space(self.text, i);
        if self.at(i) != Some(b']') {
            loop {
                let (v, end) = self.value(i)?;
                items.push(v);
                i = skip_space(self.text, end);
                if self.at(i) == Some(b']') {
                    break;
                }
                if self.at(i) != Some(b',') {
                    return Err(DecodeError::At("Expecting ',' delimiter", i));
                }
                let comma = i;
                i = skip_space(self.text, i + 1);
                if self.at(i) == Some(b']') {
                    return Err(DecodeError::At("Illegal trailing comma before end of array", comma));
                }
            }
        }
        Ok((Value::list(items), i + 1))
    }

    /// Four hex digits at byte [i], as a UTF-16 code unit.
    fn hex4(&self, i: usize) -> Option<u32> {
        let digits = self.b.get(i..i + 4)?;
        let mut n = 0;
        for &d in digits {
            n = n * 16 + (d as char).to_digit(16)?;
        }
        Some(n)
    }

    /// The string whose opening quote is just before byte [begin], and the
    /// byte offset past its closing quote.
    fn string(&mut self, begin: usize) -> DRes<(String, usize)> {
        let mut out = String::new();
        let mut i = begin;
        loop {
            let run = i;
            while i < self.b.len() && self.b[i] != b'"' && self.b[i] != b'\\' && (self.b[i] >= 0x20 || !self.options.strict) {
                i += 1;
            }
            out.push_str(&self.text[run..i]);
            let Some(c) = self.at(i) else { return Err(DecodeError::At("Unterminated string starting at", begin - 1)) };
            match c {
                b'"' => return Ok((out, i + 1)),
                b'\\' => {}
                _ => return Err(DecodeError::At("Invalid control character at", i)),
            }
            let Some(e) = self.at(i + 1) else { return Err(DecodeError::At("Unterminated string starting at", begin - 1)) };
            let simple = match e {
                b'"' => '"',
                b'\\' => '\\',
                b'/' => '/',
                b'b' => '\u{08}',
                b'f' => '\u{0c}',
                b'n' => '\n',
                b'r' => '\r',
                b't' => '\t',
                b'u' => {
                    let Some(mut code) = self.hex4(i + 2) else { return Err(DecodeError::At("Invalid \\uXXXX escape", i + 1)) };
                    i += 6;
                    // A pair of surrogates, `😀`, is one character.
                    if (0xD800..0xDC00).contains(&code) && self.starts(i, "\\u") {
                        if let Some(low) = self.hex4(i + 2).filter(|l| (0xDC00..0xE000).contains(l)) {
                            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                            i += 6;
                        }
                    }
                    // A lone surrogate, which CPython keeps but a Rust string
                    // can't hold, reads as the replacement character.
                    out.push(char::from_u32(code).unwrap_or('\u{FFFD}'));
                    continue;
                }
                _ => return Err(DecodeError::At("Invalid \\escape", i)),
            };
            out.push(simple);
            i += 2;
        }
    }

    fn digits(&self, mut i: usize) -> usize {
        while self.at(i).is_some_and(|d| d.is_ascii_digit()) {
            i += 1;
        }
        i
    }

    /// A number at byte [start], as `-?(0|[1-9]\d*)(\.\d+)?([eE][-+]?\d+)?` matches it.
    fn number(&mut self, start: usize) -> DRes<(Value, usize)> {
        let mut i = start;
        if self.at(i) == Some(b'-') {
            i += 1;
        }
        match self.at(i) {
            Some(b'0') => i += 1,
            Some(b'1'..=b'9') => i = self.digits(i + 1),
            _ => return Err(DecodeError::At("Expecting value", start)),
        }
        let mut is_float = false;
        if self.at(i) == Some(b'.') && self.at(i + 1).is_some_and(|d| d.is_ascii_digit()) {
            is_float = true;
            i = self.digits(i + 2);
        }
        if matches!(self.at(i), Some(b'e' | b'E')) {
            let mut j = i + 1;
            if matches!(self.at(j), Some(b'+' | b'-')) {
                j += 1;
            }
            let end = self.digits(j);
            if end > j {
                is_float = true;
                i = end;
            }
        }
        let text = &self.text[start..i];
        let hook = if is_float { &self.options.parse_float } else { &self.options.parse_int };
        if let Some(f) = hook.clone() {
            return Ok((self.host.call(&f, vec![Value::str(text)])?, i));
        }
        if is_float {
            // Rust's parse rounds as Python's float() does; past f64's range it is inf.
            let f: f64 = text.parse().map_err(|_| PyError::new(format!("could not convert string to float: '{text}'"), "ValueError"))?;
            return Ok((Value::Float(f), i));
        }
        if let Ok(n) = text.parse::<i64>() {
            return Ok((Value::Int(n), i));
        }
        // A whole number past 64 bits is an int, as Python's json reads it.
        let (negative, digits) = match text.strip_prefix('-') {
            Some(d) => (true, d),
            None => (false, text),
        };
        match super::bigint::parse_digits(digits, 10, negative) {
            Ok(Some(v)) => Ok((v, i)),
            Ok(None) => Err(DecodeError::At("Expecting value", start)),
            Err((m, k)) => Err(PyError::new(m, k).into()),
        }
    }
}
