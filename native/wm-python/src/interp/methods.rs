//! Port of PyMethods.kt: everything reachable as `value.name(...)`: the str,
//! list, tuple, dict and set methods, the `re` match object, module calls like
//! `math.sqrt(2)`, and (additions) bytes and dict keys called as methods.

use std::rc::Rc;

use indexmap::IndexSet;

use super::ast::BinOp;
use super::bytes::{decode, encode, from_hex, to_hex};
use super::error::R;
use super::eval::{set_algebra, Interp};
use super::format::py_format;
use super::regex::Match;
use super::text::*;
use super::value::*;

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

/// Method names per built-in type, for taking a method as a value
/// (`on_submit=items.append`).
pub(crate) const STR_METHODS: &[&str] = &[
    "capitalize", "casefold", "center", "count", "encode", "endswith", "expandtabs", "find", "format", "index", "isalnum",
    "isalpha", "isascii", "isdecimal", "isdigit", "isidentifier", "islower", "isnumeric", "isprintable", "isspace",
    "istitle", "isupper", "join", "ljust", "lower", "lstrip", "partition", "removeprefix", "removesuffix", "replace",
    "rfind", "rindex", "rjust", "rpartition", "rsplit", "rstrip", "split", "splitlines", "startswith", "strip",
    "swapcase", "title", "translate", "upper", "zfill",
];
pub(crate) const LIST_METHODS: &[&str] = &["append", "extend", "insert", "remove", "pop", "clear", "index", "count", "sort", "reverse", "copy"];
pub(crate) const TUPLE_METHODS: &[&str] = &["index", "count"];
pub(crate) const SET_METHODS: &[&str] = &[
    "add", "remove", "discard", "pop", "clear", "union", "intersection", "difference", "symmetric_difference", "update",
    "issubset", "issuperset", "isdisjoint", "copy", "intersection_update", "difference_update",
];
pub(crate) const DICT_METHODS: &[&str] = &["keys", "values", "items", "get", "pop", "popitem", "setdefault", "update", "clear", "copy"];
pub(crate) const BYTES_METHODS: &[&str] = &[
    "decode", "hex", "find", "count", "startswith", "endswith", "split", "strip", "replace", "upper", "lower", "join", "index",
];
/// The builtin name a method taken as a value calls through.
pub(crate) const METHOD_PREFIX: &str = "__method__:";

/// Whether [obj] has a built-in method [name].
pub(crate) fn has_builtin_method(obj: &Value, name: &str) -> bool {
    let table = match obj {
        Value::Str(_) => STR_METHODS,
        Value::List(_) => LIST_METHODS,
        Value::Tuple(_) => TUPLE_METHODS,
        Value::Set(_) => SET_METHODS,
        Value::Dict(_) => DICT_METHODS,
        Value::Bytes(_) => BYTES_METHODS,
        _ => return false,
    };
    table.contains(&name)
}

fn is_callable(v: &Value) -> bool {
    matches!(
        v,
        Value::Function(_) | Value::Builtin(_) | Value::HostFunction(_) | Value::ModuleFunction(_) | Value::Class(_) | Value::Object(_) | Value::Instance(_)
    )
}

fn normalise(i: i64, count: usize) -> usize {
    let v = if i < 0 { i + count as i64 } else { i };
    v.clamp(0, count as i64) as usize
}

impl Interp {
    #[inline(never)]
    pub(crate) fn call_method(&mut self, obj: &Value, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        self.tick()?;
        match obj {
            Value::Str(s) => self.string_method(s, name, args, kwargs),
            Value::List(l) => self.list_method(l, name, args, kwargs),
            Value::Tuple(t) => self.tuple_method(t, name, &args),
            Value::Set(s) => self.set_method(s, name, &args),
            Value::Dict(d) => {
                // A dict answers its string keys first, so `wm.storage.get(...)`
                // works on a hand-built stand-in and `e.type` on an event.
                // A key holding data does not hide the method of the same name:
                // `fields.items()` on a dict with an "items" key.
                let entry = d.borrow().get(&HKey::str(name)).cloned();
                match entry {
                    Some(v) if !(DICT_METHODS.contains(&name) && !is_callable(&v)) => self.call_value(&v, args, kwargs),
                    _ => self.dict_method(d, name, &args),
                }
            }
            Value::Match(m) => self.match_method(m, name, &args),
            Value::Module(m) => {
                let m = m.clone();
                self.call_module(&m, name, args, kwargs)
            }
            Value::Bytes(b) => self.bytes_method(b, name, &args, &kwargs),
            Value::Builtin(b) if b.as_ref() == "bytes" && name == "fromhex" => {
                let s = self.arg_str(&args, 0, "fromhex")?;
                match from_hex(&s) {
                    Ok(v) => Ok(Value::bytes(&v)),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            Value::Instance(inst) => {
                // The common case, `self.method(...)`, without making a bound method.
                let own = inst.attrs.borrow().get(name).cloned();
                if own.is_none() {
                    if let Some(Value::Function(f)) = inst.class.lookup(name) {
                        let mut all = Vec::with_capacity(args.len() + 1);
                        all.push(obj.clone());
                        all.extend(args);
                        return self.call_function(&f, all, kwargs);
                    }
                }
                let m = self.attribute(obj, name)?;
                self.call_value(&m, args, kwargs)
            }
            Value::Class(_) | Value::Object(_) | Value::Function(_) => {
                let m = self.attribute(obj, name)?;
                self.call_value(&m, args, kwargs)
            }
            Value::Builtin(b) if b.as_ref() == "object" => {
                let m = self.attribute(obj, name)?;
                self.call_value(&m, args, kwargs)
            }
            Value::Exception(e) => match name {
                "with_traceback" => Ok(obj.clone()),
                _ => Err(self.error(format!("'{}' object has no method '{name}'", e.kind), "AttributeError")),
            },
            _ => Err(self.error(format!("'{}' object has no method '{name}'", obj.type_name()), "AttributeError")),
        }
    }

    fn arg_str(&self, args: &[Value], i: usize, method: &str) -> R<Rc<str>> {
        match args.get(i) {
            Some(Value::Str(s)) => Ok(s.clone()),
            _ => Err(self.error(format!("{method}() expects a string argument"), "TypeError")),
        }
    }

    fn clusters_of(&mut self, s: &Rc<str>) -> Vec<String> {
        let b = self.chars(s);
        (0..b.len() - 1).map(|i| s[b[i] as usize..b[i + 1] as usize].to_string()).collect()
    }

    // MARK: - str

    #[inline(never)]
    fn string_method(&mut self, s: &Rc<str>, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let st = |v: String| Ok(Value::str(v));
        match name {
            "upper" => st(swift_upper(s)),
            "lower" => st(swift_lower(s)),
            "casefold" => st(fold_case_and_diacritics(s)),
            "title" => st(foundation_capitalized(s)),
            "capitalize" => {
                let cs = self.clusters_of(s);
                if cs.is_empty() {
                    return st(String::new());
                }
                st(swift_upper(&cs[0]) + &swift_lower(&cs[1..].concat()))
            }
            "swapcase" => {
                let cs = self.clusters_of(s);
                st(cs.iter().map(|c| if char_is_uppercase(c) { swift_lower(c) } else { swift_upper(c) }).collect())
            }
            "strip" => st(self.trim(s, &args, true, true)),
            "lstrip" => st(self.trim(s, &args, true, false)),
            "rstrip" => st(self.trim(s, &args, false, true)),
            "replace" => {
                let from = self.arg_str(&args, 0, "replace")?;
                let to = self.arg_str(&args, 1, "replace")?;
                if from.is_empty() {
                    return Ok(Value::Str(s.clone()));
                }
                match args.get(2).and_then(Value::as_int) {
                    None => st(s.replace(from.as_ref(), &to)),
                    Some(limit) if limit <= 0 => Ok(Value::Str(s.clone())),
                    Some(limit) => st(s.replacen(from.as_ref(), &to, limit as usize)),
                }
            }
            "split" => self.split_method(s, &args, false),
            "rsplit" => self.split_method(s, &args, true),
            "splitlines" => {
                // Swift splits on the "\n" Character, so a "\r\n" cluster stays whole.
                let mut out = Vec::new();
                let mut cur = String::new();
                for c in self.clusters_of(s) {
                    if c == "\n" {
                        out.push(Value::str(std::mem::take(&mut cur)));
                    } else {
                        cur.push_str(&c);
                    }
                }
                out.push(Value::str(cur));
                Ok(Value::list(out))
            }
            "join" => {
                let it = args.first().ok_or_else(|| self.error("join() needs an iterable", "TypeError"))?;
                let mut parts = Vec::new();
                for v in self.iterate(it)? {
                    match v {
                        Value::Str(p) => parts.push(p),
                        other => {
                            return Err(self.error(
                                format!("join() requires str items, found '{}'", other.type_name()),
                                "TypeError",
                            ))
                        }
                    }
                }
                let joined = parts.iter().map(|p| p.as_ref()).collect::<Vec<_>>().join(s);
                st(joined)
            }
            "startswith" => Ok(Value::Bool(self.prefix_test(s, &args, false)?)),
            "endswith" => Ok(Value::Bool(self.prefix_test(s, &args, true)?)),
            "find" => Ok(Value::Int(self.search(s, &args, false, "find")?)),
            "rfind" => Ok(Value::Int(self.search(s, &args, true, "rfind")?)),
            "index" | "rindex" => {
                let i = self.search(s, &args, name == "rindex", name)?;
                if i < 0 {
                    return Err(self.error("substring not found", "ValueError"));
                }
                Ok(Value::Int(i))
            }
            "count" => {
                let sub = self.arg_str(&args, 0, "count")?;
                if sub.is_empty() {
                    let n = self.chars(s).len();
                    return Ok(Value::Int(n as i64));
                }
                Ok(Value::Int(s.matches(sub.as_ref()).count() as i64))
            }
            "partition" | "rpartition" => {
                let sep = self.arg_str(&args, 0, name)?;
                let at = if sep.is_empty() {
                    None
                } else if name == "partition" {
                    s.find(sep.as_ref())
                } else {
                    s.rfind(sep.as_ref())
                };
                let Some(at) = at else {
                    return Ok(if name == "partition" {
                        Value::tuple(vec![Value::Str(s.clone()), Value::str(""), Value::str("")])
                    } else {
                        Value::tuple(vec![Value::str(""), Value::str(""), Value::Str(s.clone())])
                    });
                };
                Ok(Value::tuple(vec![Value::str(&s[..at]), Value::Str(sep.clone()), Value::str(&s[at + sep.len()..])]))
            }
            "ljust" | "rjust" | "center" | "zfill"
                if args.first().and_then(Value::as_int).is_some_and(|w| w > self.max_string as i64) =>
            {
                Err(self.string_abort())
            }
            "zfill" => {
                let width = args.first().and_then(Value::as_int).unwrap_or(0);
                let cs = self.clusters_of(s);
                if cs.len() as i64 >= width {
                    return Ok(Value::Str(s.clone()));
                }
                let pad = "0".repeat((width - cs.len() as i64) as usize);
                match cs.first().map(String::as_str) {
                    Some(f @ ("-" | "+")) => st(format!("{f}{pad}{}", cs[1..].concat())),
                    _ => st(format!("{pad}{s}")),
                }
            }
            "ljust" => st(self.pad(s, &args, '<')),
            "rjust" => st(self.pad(s, &args, '>')),
            "center" => st(self.pad(s, &args, '^')),
            "isdigit" | "isnumeric" => {
                let cs = self.clusters_of(s);
                Ok(Value::Bool(!cs.is_empty() && cs.iter().all(|c| char_is_number(c))))
            }
            "isalpha" => {
                let cs = self.clusters_of(s);
                Ok(Value::Bool(!cs.is_empty() && cs.iter().all(|c| char_is_letter(c))))
            }
            "isalnum" => {
                let cs = self.clusters_of(s);
                Ok(Value::Bool(!cs.is_empty() && cs.iter().all(|c| char_is_letter(c) || char_is_number(c))))
            }
            "isspace" => {
                let cs = self.clusters_of(s);
                Ok(Value::Bool(!cs.is_empty() && cs.iter().all(|c| char_is_whitespace(c))))
            }
            "isupper" => {
                let any = self.clusters_of(s).iter().any(|c| char_is_letter(c));
                Ok(Value::Bool(any && str_eq(s, &swift_upper(s))))
            }
            "islower" => {
                let any = self.clusters_of(s).iter().any(|c| char_is_letter(c));
                Ok(Value::Bool(any && str_eq(s, &swift_lower(s))))
            }
            "istitle" => Ok(Value::Bool(!s.is_empty() && str_eq(s, &foundation_capitalized(s)))),
            "removeprefix" => {
                let p = self.arg_str(&args, 0, "removeprefix")?;
                st(s.strip_prefix(p.as_ref()).unwrap_or(s).to_string())
            }
            "removesuffix" => {
                let p = self.arg_str(&args, 0, "removesuffix")?;
                st(s.strip_suffix(p.as_ref()).unwrap_or(s).to_string())
            }
            "format" => st(self.format_method(s, &args, &kwargs)?),
            "encode" => {
                let enc = match args.first().or_else(|| kwarg(&kwargs, "encoding")) {
                    None => Rc::from("utf-8"),
                    Some(Value::Str(e)) => e.clone(),
                    Some(_) => return Err(self.error("encode() argument 'encoding' must be str", "TypeError")),
                };
                match encode(s, &enc) {
                    Ok(b) => Ok(Value::bytes(&b)),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            _ => Err(self.error(format!("'str' object has no method '{name}'"), "AttributeError")),
        }
    }

    fn prefix_test(&self, s: &str, args: &[Value], suffix: bool) -> R<bool> {
        let first = args.first().ok_or_else(|| self.error("expected a string argument", "TypeError"))?;
        // `startswith` accepts a tuple of prefixes as well as one string.
        let candidates: Vec<Value> = match first {
            Value::Tuple(t) => t.as_ref().clone(),
            other => vec![other.clone()],
        };
        for c in candidates {
            let Value::Str(p) = c else {
                return Err(self.error("expected a string argument", "TypeError"));
            };
            if if suffix { s.ends_with(p.as_ref()) } else { s.starts_with(p.as_ref()) } {
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn search(&mut self, s: &Rc<str>, args: &[Value], last: bool, method: &str) -> R<i64> {
        let sub = self.arg_str(args, 0, method)?;
        let b = self.chars(s);
        let count = b.len() - 1;
        let lower = if args.len() > 1 { normalise(args[1].as_int().unwrap_or(0), count) } else { 0 };
        let upper = if args.len() > 2 { normalise(args[2].as_int().unwrap_or(count as i64), count) } else { count };
        if lower > upper {
            return Ok(-1);
        }
        let window = &s[b[lower] as usize..b[upper] as usize];
        if sub.is_empty() {
            return Ok(lower as i64);
        }
        let r = if last { window.rfind(sub.as_ref()) } else { window.find(sub.as_ref()) };
        match r {
            None => Ok(-1),
            Some(r) => Ok((lower + grapheme_count(&window[..r])) as i64),
        }
    }

    fn trim(&mut self, s: &Rc<str>, args: &[Value], left: bool, right: bool) -> String {
        let cut: Vec<String> = match args.first() {
            Some(Value::Str(c)) => graphemes(c).into_iter().map(String::from).collect(),
            _ => vec![" ".into(), "\t".into(), "\n".into(), "\r".into()],
        };
        let b = self.chars(s);
        let cs: Vec<&str> = (0..b.len() - 1).map(|i| &s[b[i] as usize..b[i + 1] as usize]).collect();
        let mut from = 0;
        let mut to = cs.len();
        if left {
            while from < to && cut.iter().any(|c| c == cs[from]) {
                from += 1;
            }
        }
        if right {
            while to > from && cut.iter().any(|c| c == cs[to - 1]) {
                to -= 1;
            }
        }
        cs[from..to].concat()
    }

    fn pad(&mut self, s: &Rc<str>, args: &[Value], align: char) -> String {
        let width = args.first().and_then(Value::as_int).unwrap_or(0);
        let mut fill = " ".to_string();
        if let Some(Value::Str(f)) = args.get(1) {
            if let Some(g) = graphemes(f).first() {
                fill = g.to_string();
            }
        }
        let count = (self.chars(s).len() - 1) as i64;
        if count >= width {
            return s.to_string();
        }
        let padding = (width - count) as usize;
        match align {
            '>' => fill.repeat(padding) + s,
            '^' => {
                let l = padding / 2;
                fill.repeat(l) + s + &fill.repeat(padding - l)
            }
            _ => s.to_string() + &fill.repeat(padding),
        }
    }

    fn split_method(&mut self, s: &Rc<str>, args: &[Value], from_end: bool) -> R<Value> {
        let limit = if args.len() > 1 { args[1].as_int().unwrap_or(-1) } else { -1 };
        if let Some(Value::Str(sep)) = args.first() {
            if sep.is_empty() {
                return Err(self.error("empty separator", "ValueError"));
            }
            let mut parts: Vec<String> = s.split(sep.as_ref()).map(String::from).collect();
            if limit >= 0 && parts.len() as i64 > limit + 1 {
                // Re-join the parts beyond the limit, from whichever end asked.
                let n = limit as usize;
                parts = if from_end {
                    let mut out = vec![parts[..parts.len() - n].join(sep)];
                    out.extend(parts[parts.len() - n..].iter().cloned());
                    out
                } else {
                    let mut out = parts[..n].to_vec();
                    out.push(parts[n..].join(sep));
                    out
                };
            }
            return Ok(Value::list(parts.into_iter().map(Value::from).collect()));
        }
        // No separator: split on runs of whitespace, dropping empties.
        let mut parts = Vec::new();
        let mut cur = String::new();
        for c in self.clusters_of(s) {
            if char_is_whitespace(&c) {
                if !cur.is_empty() {
                    parts.push(std::mem::take(&mut cur));
                }
            } else {
                cur.push_str(&c);
            }
        }
        if !cur.is_empty() {
            parts.push(cur);
        }
        if limit >= 0 && parts.len() as i64 > limit + 1 {
            parts.truncate((limit + 1) as usize);
        }
        Ok(Value::list(parts.into_iter().map(Value::from).collect()))
    }

    /// `str.format`: positional `{}` and `{0}`, named `{name}`, and the f-string spec grammar.
    fn format_method(&mut self, s: &Rc<str>, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<String> {
        let mut out = String::new();
        let mut auto = 0;
        let cs = self.clusters_of(s);
        let mut i = 0;
        while i < cs.len() {
            let c = cs[i].as_str();
            if c == "{" {
                if i + 1 < cs.len() && cs[i + 1] == "{" {
                    out.push('{');
                    i += 2;
                    continue;
                }
                let mut inner = String::new();
                i += 1;
                let mut depth = 1;
                while i < cs.len() {
                    if cs[i] == "{" {
                        depth += 1;
                    }
                    if cs[i] == "}" {
                        depth -= 1;
                        if depth == 0 {
                            break;
                        }
                    }
                    inner.push_str(&cs[i]);
                    i += 1;
                }
                if i >= cs.len() {
                    return Err(self.error("unmatched '{' in format string", "ValueError"));
                }
                i += 1;
                let (field, spec) = match inner.find(':') {
                    Some(c) => (&inner[..c], &inner[c + 1..]),
                    None => (inner.as_str(), ""),
                };
                let key = trim_whitespaces(field);
                let value = if key.is_empty() {
                    if auto >= args.len() {
                        return Err(self.error("format index out of range", "IndexError"));
                    }
                    auto += 1;
                    args[auto - 1].clone()
                } else if let Some(n) = parse_swift_int(key, 10) {
                    if n < 0 || n as usize >= args.len() {
                        return Err(self.error("format index out of range", "IndexError"));
                    }
                    args[n as usize].clone()
                } else {
                    kwarg(kwargs, key).cloned().ok_or_else(|| self.error(format!("'{key}'"), "KeyError"))?
                };
                out.push_str(&py_format(&value, spec)?);
            } else if c == "}" {
                if i + 1 < cs.len() && cs[i + 1] == "}" {
                    out.push('}');
                    i += 2;
                    continue;
                }
                out.push('}');
                i += 1;
            } else {
                out.push_str(c);
                i += 1;
            }
        }
        Ok(out)
    }

    // MARK: - list

    #[inline(never)]
    fn list_method(&mut self, l: &ListRef, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        match name {
            "append" => {
                self.arity("append", &args, 1)?;
                if l.borrow().len() + 1 > self.max_container {
                    return Err(self.container_abort("list"));
                }
                l.borrow_mut().push(args.into_iter().next().unwrap());
                Ok(Value::None)
            }
            "extend" => {
                let it = args.first().ok_or_else(|| self.error("extend() needs an iterable", "TypeError"))?;
                let items = self.iterate(it)?;
                l.borrow_mut().extend(items);
                if l.borrow().len() > self.max_container {
                    return Err(self.container_abort("list"));
                }
                Ok(Value::None)
            }
            "pop" => {
                let n = l.borrow().len();
                if n == 0 {
                    return Err(self.error("pop from empty list", "IndexError"));
                }
                let idx = args.first().and_then(Value::as_int).unwrap_or(n as i64 - 1);
                let i = if idx < 0 { idx + n as i64 } else { idx };
                if i < 0 || i >= n as i64 {
                    return Err(self.error("pop index out of range", "IndexError"));
                }
                Ok(l.borrow_mut().remove(i as usize))
            }
            "insert" => {
                self.arity("insert", &args, 2)?;
                let n = l.borrow().len() as i64;
                let raw = args[0].as_int().unwrap_or(0);
                let i = (if raw < 0 { raw + n } else { raw }).clamp(0, n);
                l.borrow_mut().insert(i as usize, args[1].clone());
                Ok(Value::None)
            }
            "remove" => {
                self.arity("remove", &args, 1)?;
                let i = l.borrow().iter().position(|v| py_equals(v, &args[0]));
                match i {
                    Some(i) => {
                        l.borrow_mut().remove(i);
                        Ok(Value::None)
                    }
                    None => Err(self.error("list.remove(x): x not in list", "ValueError")),
                }
            }
            "index" => {
                let needle = args.first().ok_or_else(|| self.error("index() needs a value", "TypeError"))?;
                let items = l.borrow().clone();
                let from = if args.len() > 1 { normalise(args[1].as_int().unwrap_or(0), items.len()) } else { 0 };
                for (i, v) in items.iter().enumerate().skip(from) {
                    if py_equals(v, needle) {
                        return Ok(Value::Int(i as i64));
                    }
                }
                Err(self.error(format!("{} is not in list", py_repr(needle)), "ValueError"))
            }
            "count" => {
                self.arity("count", &args, 1)?;
                Ok(Value::Int(l.borrow().iter().filter(|v| py_equals(v, &args[0])).count() as i64))
            }
            "sort" => {
                let mut items = l.borrow().clone();
                self.insertion_sort(&mut items, kwarg(&kwargs, "key").cloned())?;
                if kwarg(&kwargs, "reverse").is_some_and(Value::truthy) {
                    items.reverse();
                }
                *l.borrow_mut() = items;
                Ok(Value::None)
            }
            "reverse" => {
                l.borrow_mut().reverse();
                Ok(Value::None)
            }
            "clear" => {
                l.borrow_mut().clear();
                Ok(Value::None)
            }
            "copy" => Ok(Value::list(l.borrow().clone())),
            _ => Err(self.error(format!("'list' object has no method '{name}'"), "AttributeError")),
        }
    }

    fn tuple_method(&mut self, t: &Rc<Vec<Value>>, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "count" => {
                self.arity("count", args, 1)?;
                Ok(Value::Int(t.iter().filter(|v| py_equals(v, &args[0])).count() as i64))
            }
            "index" => {
                self.arity("index", args, 1)?;
                match t.iter().position(|v| py_equals(v, &args[0])) {
                    Some(i) => Ok(Value::Int(i as i64)),
                    None => Err(self.error(format!("{} is not in tuple", py_repr(&args[0])), "ValueError")),
                }
            }
            _ => Err(self.error(format!("'tuple' object has no method '{name}'"), "AttributeError")),
        }
    }

    // MARK: - set

    #[inline(never)]
    fn set_method(&mut self, s: &SetRef, name: &str, args: &[Value]) -> R<Value> {
        let other = |me: &mut Interp, i: usize| -> R<IndexSet<HKey>> {
            let Some(a) = args.get(i) else {
                return Err(me.error(format!("{name}() needs a set"), "TypeError"));
            };
            if let Value::Set(o) = a {
                return Ok(o.borrow().clone());
            }
            let mut built = IndexSet::new();
            for v in me.iterate(a)? {
                built.insert(me.hashable(&v)?);
            }
            Ok(built)
        };
        match name {
            "add" => {
                self.arity("add", args, 1)?;
                if s.borrow().len() + 1 > self.max_container {
                    return Err(self.container_abort("set"));
                }
                let k = self.hashable(&args[0])?;
                s.borrow_mut().insert(k);
                Ok(Value::None)
            }
            "discard" => {
                self.arity("discard", args, 1)?;
                let k = self.hashable(&args[0])?;
                s.borrow_mut().shift_remove(&k);
                Ok(Value::None)
            }
            "remove" => {
                self.arity("remove", args, 1)?;
                let k = self.hashable(&args[0])?;
                if !s.borrow_mut().shift_remove(&k) {
                    return Err(self.error(py_repr(&args[0]), "KeyError"));
                }
                Ok(Value::None)
            }
            "pop" => {
                let first = s.borrow_mut().shift_remove_index(0);
                first.map(|k| k.to_value()).ok_or_else(|| self.error("pop from an empty set", "KeyError"))
            }
            "clear" => {
                s.borrow_mut().clear();
                Ok(Value::None)
            }
            "copy" => Ok(Value::new_set(s.borrow().clone())),
            "update" => {
                let o = other(self, 0)?;
                s.borrow_mut().extend(o);
                Ok(Value::None)
            }
            "union" | "intersection" | "difference" | "symmetric_difference" => {
                let o = other(self, 0)?;
                let op = match name {
                    "union" => BinOp::BitOr,
                    "intersection" => BinOp::BitAnd,
                    "difference" => BinOp::Sub,
                    _ => BinOp::BitXor,
                };
                Ok(set_algebra(op, &s.borrow(), &o).unwrap())
            }
            "issubset" => {
                let o = other(self, 0)?;
                Ok(Value::Bool(s.borrow().iter().all(|k| o.contains(k))))
            }
            "issuperset" => {
                let o = other(self, 0)?;
                Ok(Value::Bool(o.iter().all(|k| s.borrow().contains(k))))
            }
            "isdisjoint" => {
                let o = other(self, 0)?;
                Ok(Value::Bool(!s.borrow().iter().any(|k| o.contains(k))))
            }
            _ => Err(self.error(format!("'set' object has no method '{name}'"), "AttributeError")),
        }
    }

    // MARK: - dict

    #[inline(never)]
    fn dict_method(&mut self, d: &DictRef, name: &str, args: &[Value]) -> R<Value> {
        let key = |me: &Interp| -> R<HKey> {
            args.first().and_then(HKey::from_value).ok_or_else(|| me.error("unhashable key", "TypeError"))
        };
        match name {
            "keys" => Ok(Value::list(d.borrow().keys().map(HKey::to_value).collect())),
            "values" => Ok(Value::list(d.borrow().values().cloned().collect())),
            "items" => Ok(Value::list(d.borrow().iter().map(|(k, v)| Value::tuple(vec![k.to_value(), v.clone()])).collect())),
            "get" => {
                let k = key(self)?;
                let v = d.borrow().get(&k).cloned();
                Ok(v.unwrap_or_else(|| args.get(1).cloned().unwrap_or(Value::None)))
            }
            "pop" => {
                let k = key(self)?;
                let v = d.borrow_mut().shift_remove(&k);
                match v {
                    Some(v) => Ok(v),
                    None if args.len() > 1 => Ok(args[1].clone()),
                    None => Err(self.error(py_repr(&args[0]), "KeyError")),
                }
            }
            "popitem" => {
                let last = d.borrow_mut().pop();
                match last {
                    Some((k, v)) => Ok(Value::tuple(vec![k.to_value(), v])),
                    None => Err(self.error("popitem(): dictionary is empty", "KeyError")),
                }
            }
            "setdefault" => {
                let k = key(self)?;
                let existing = d.borrow().get(&k).cloned();
                match existing {
                    Some(v) => Ok(v),
                    None => {
                        let def = args.get(1).cloned().unwrap_or(Value::None);
                        d.borrow_mut().insert(k, def.clone());
                        Ok(def)
                    }
                }
            }
            "update" => {
                let first = args.first().ok_or_else(|| self.error("update() expects a dict", "TypeError"))?;
                if let Value::Dict(src) = first {
                    let pairs: Vec<(HKey, Value)> = src.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                    let mut m = d.borrow_mut();
                    for (k, v) in pairs {
                        m.insert(k, v);
                    }
                } else {
                    for pair in self.iterate(first)? {
                        let items = sequence_items(&pair);
                        let k = items.as_ref().filter(|i| i.len() == 2).and_then(|i| HKey::from_value(&i[0]));
                        match (items, k) {
                            (Some(items), Some(k)) => {
                                d.borrow_mut().insert(k, items[1].clone());
                            }
                            _ => return Err(self.error("update() needs (key, value) pairs", "ValueError")),
                        }
                    }
                }
                Ok(Value::None)
            }
            "clear" => {
                d.borrow_mut().clear();
                Ok(Value::None)
            }
            "copy" => Ok(Value::new_dict(d.borrow().clone())),
            _ => Err(self.error(format!("'dict' object has no method '{name}'"), "AttributeError")),
        }
    }

    // MARK: - re match

    #[inline(never)]
    fn match_method(&mut self, m: &Rc<Match>, name: &str, args: &[Value]) -> R<Value> {
        let group_index = |me: &Interp, v: &Value| -> R<i64> {
            if let Some(i) = v.as_int() {
                return Ok(i);
            }
            if let Value::Str(s) = v {
                if let Some(i) = m.names.get(s.as_ref()) {
                    return Ok(*i as i64);
                }
            }
            Err(me.error("no such group", "IndexError"))
        };
        let group_value = |i: i64| -> Value {
            if i < 0 {
                return Value::None;
            }
            m.text(i as usize).map(Value::str).unwrap_or(Value::None)
        };
        match name {
            "group" => {
                if args.is_empty() {
                    return Ok(Value::str(m.value()));
                }
                if args.len() == 1 {
                    let i = group_index(self, &args[0])?;
                    if i < 0 || i as usize >= m.spans.len() {
                        return Err(self.error("no such group", "IndexError"));
                    }
                    return Ok(group_value(i));
                }
                let mut out = Vec::new();
                for a in args {
                    out.push(group_value(group_index(self, a)?));
                }
                Ok(Value::tuple(out))
            }
            "groups" => {
                let fallback = args.first().cloned().unwrap_or(Value::None);
                Ok(Value::tuple(
                    (1..m.spans.len().max(1)).map(|i| m.text(i).map(Value::str).unwrap_or_else(|| fallback.clone())).collect(),
                ))
            }
            "groupdict" => {
                let mut names: Vec<(&String, &usize)> = m.names.iter().collect();
                names.sort_by_key(|(_, i)| **i);
                Ok(Value::dict(names.into_iter().map(|(n, i)| (n.as_str(), group_value(*i as i64))).collect()))
            }
            "start" | "end" | "span" => {
                let i = if args.is_empty() { 0 } else { group_index(self, &args[0])? };
                let span = if i >= 0 && (i as usize) < m.spans.len() { m.spans[i as usize] } else { None };
                let Some((start, end)) = span else {
                    return Ok(if name == "span" { Value::tuple(vec![Value::Int(-1), Value::Int(-1)]) } else { Value::Int(-1) });
                };
                Ok(match name {
                    "start" => Value::Int(start as i64),
                    "end" => Value::Int(end as i64),
                    _ => Value::tuple(vec![Value::Int(start as i64), Value::Int(end as i64)]),
                })
            }
            _ => Err(self.error(format!("'Match' object has no method '{name}'"), "AttributeError")),
        }
    }

    // MARK: - bytes

    #[inline(never)]
    fn bytes_method(&mut self, b: &Rc<[u8]>, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        match name {
            "hex" => Ok(Value::str(to_hex(b))),
            "decode" => {
                let enc = match args.first().or_else(|| kwarg(kwargs, "encoding")) {
                    None => "utf-8".to_string(),
                    Some(Value::Str(e)) => e.to_string(),
                    Some(_) => return Err(self.error("decode() argument 'encoding' must be str", "TypeError")),
                };
                let errors = match args.get(1).or_else(|| kwarg(kwargs, "errors")) {
                    None => "strict".to_string(),
                    Some(Value::Str(e)) => e.to_string(),
                    Some(_) => return Err(self.error("decode() argument 'errors' must be str", "TypeError")),
                };
                match decode(b, &enc, &errors) {
                    Ok(s) => Ok(Value::str(s)),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            _ => Err(self.error(format!("'bytes' object has no method '{name}'"), "AttributeError")),
        }
    }
}
