//! Port of PyMethods.kt: everything reachable as `value.name(...)`: the str,
//! list, tuple, dict and set methods, the `re` match object, module calls like
//! `math.sqrt(2)`, and (additions) bytes and dict keys called as methods.

use std::rc::Rc;

use super::ast::BinOp;
use super::bytes::{decode, encode, from_hex, to_hex};
use super::error::R;
use super::eval::{set_algebra, Interp};
use super::regex::Match;
use super::text::*;
use super::value::*;
use unicode_properties::UnicodeGeneralCategory;

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

/// Method names per built-in type, for taking a method as a value
/// (`on_submit=items.append`).
pub(crate) const STR_METHODS: &[&str] = &[
    "capitalize", "casefold", "center", "count", "encode", "endswith", "expandtabs", "find", "format", "format_map", "index", "isalnum",
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
pub(crate) const INT_METHODS: &[&str] = &["to_bytes", "bit_length", "bit_count", "conjugate", "as_integer_ratio", "is_integer"];
pub(crate) const FLOAT_METHODS: &[&str] = &["is_integer", "as_integer_ratio", "conjugate", "hex"];
pub(crate) const DICT_METHODS: &[&str] = &["keys", "values", "items", "get", "pop", "popitem", "setdefault", "update", "clear", "copy"];
pub(crate) const BYTES_METHODS: &[&str] = &[
    "decode", "hex", "find", "count", "startswith", "endswith", "split", "strip", "replace", "upper", "lower", "join", "index",
    "rfind", "rindex", "lstrip", "rstrip",
];
/// The builtin name a method taken as a value calls through.
pub(crate) const METHOD_PREFIX: &str = "__method__:";
/// The builtin name of a method taken from its type, `str.lower`, which takes
/// the value as its first argument; or a static one, `str.maketrans`.
pub(crate) const UNBOUND_PREFIX: &str = "__unbound__:";

/// Methods called on the type itself rather than on a value.
const TYPE_STATICS: &[(&str, &str)] = &[("str", "maketrans"), ("dict", "fromkeys"), ("int", "from_bytes"), ("bytes", "fromhex")];

/// Whether `ty.name` is a method of a built-in type, taken from the type
/// (`str.lower`, `list.append`) or a static one (`dict.fromkeys`).
pub(crate) fn type_member(ty: &str, name: &str) -> bool {
    if TYPE_STATICS.contains(&(ty, name)) {
        return true;
    }
    let table: &[&str] = match ty {
        "str" => STR_METHODS,
        "list" => LIST_METHODS,
        "tuple" => TUPLE_METHODS,
        "set" => SET_METHODS,
        "dict" => DICT_METHODS,
        "bytes" => BYTES_METHODS,
        "int" => INT_METHODS,
        "float" => FLOAT_METHODS,
        _ => return false,
    };
    table.contains(&name)
}

/// Whether [obj] has a built-in method [name].
pub(crate) fn has_builtin_method(obj: &Value, name: &str) -> bool {
    let table = match obj {
        Value::Str(_) => STR_METHODS,
        Value::List(_) => LIST_METHODS,
        Value::Tuple(_) => TUPLE_METHODS,
        Value::Set(_) => SET_METHODS,
        Value::Dict(_) => DICT_METHODS,
        Value::Bytes(_) => BYTES_METHODS,
        Value::Int(_) | Value::Bool(_) => INT_METHODS,
        Value::Float(_) => FLOAT_METHODS,
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
                let entry = d.borrow().get(&StrKey(name)).cloned();
                match entry {
                    Some(v) if !(DICT_METHODS.contains(&name) && !is_callable(&v)) => self.call_value(&v, args, kwargs),
                    _ => self.dict_method(d, name, &args, &kwargs),
                }
            }
            Value::Match(m) => self.match_method(m, name, &args),
            Value::Module(m) => {
                let m = m.clone();
                self.call_module(&m, name, args, kwargs)
            }
            Value::Bytes(b) => self.bytes_method(b, name, &args, &kwargs),
            Value::Builtin(b) if type_member(b, name) => self.call_type_member(&format!("{b}.{name}"), args, kwargs),
            Value::Builtin(b) if b.as_ref() == "int" && name == "from_bytes" => self.int_from_bytes(&args, &kwargs),
            Value::Int(_) | Value::Bool(_) if INT_METHODS.contains(&name) => self.int_method(obj, name, &args, &kwargs),
            Value::Float(f) if FLOAT_METHODS.contains(&name) => self.float_method(*f, name),
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
                        let mut args = args;
                        args.insert(0, obj.clone());
                        return self.call_function(&f, args, kwargs);
                    }
                }
                let m = self.attribute(obj, name)?;
                self.call_value(&m, args, kwargs)
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Pattern { .. }) => {
                let Object::Pattern { pattern, flags } = o.as_ref() else { unreachable!() };
                let (pattern, flags) = (pattern.clone(), *flags);
                self.pattern_method(&pattern, flags, name, args, kwargs)
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Generator(_)) => {
                let o = o.clone();
                self.generator_method(&o, name, &args)
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Iterator(_)) && matches!(name, "__next__" | "__iter__") => {
                if name == "__iter__" {
                    return Ok(obj.clone());
                }
                let o = o.clone();
                match self.object_next(&o)? {
                    Some(v) => Ok(v),
                    None => Err(self.error("", "StopIteration")),
                }
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) && matches!(name, "index" | "count") => {
                let Object::Range(a, b, c) = o.as_ref() else { unreachable!() };
                let (a, c) = (*a, *c);
                let n = range_len(a, *b, c);
                let hit = match args.first() {
                    Some(v @ (Value::Int(_) | Value::Bool(_))) => {
                        let off = v.as_int().unwrap_or(0) as i128 - a as i128;
                        (off % c as i128 == 0 && (0..n as i128).contains(&(off / c as i128))).then_some(off / c as i128)
                    }
                    _ => None,
                };
                match (name, hit) {
                    ("count", h) => Ok(Value::Int(h.is_some() as i64)),
                    (_, Some(i)) => Ok(Value::Int(i as i64)),
                    _ => Err(self.error(format!("{} is not in range", args.first().map(py_repr).unwrap_or_default()), "ValueError")),
                }
            }
            Value::Object(o) if matches!(o.as_ref(), Object::DictView(..)) && name == "isdisjoint" => {
                let mine = self.iterate(obj)?;
                let other = match args.first() {
                    Some(v) => self.iterate(v)?,
                    None => return Err(self.error("isdisjoint() takes exactly one argument", "TypeError")),
                };
                for x in &mine {
                    for y in &other {
                        if self.eq(x, y)? {
                            return Ok(Value::Bool(false));
                        }
                    }
                }
                Ok(Value::Bool(true))
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Slice(..)) && name == "indices" => {
                let Object::Slice(a, b, c) = o.as_ref() else { unreachable!() };
                self.slice_indices_method(a, b, c, &args)
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

    /// `str.lower("X")`, `dict.fromkeys(keys)`: a method reached through its type.
    pub(crate) fn call_type_member(&mut self, path: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let (ty, name) = path.split_once('.').unwrap_or((path, ""));
        match (ty, name) {
            ("str", "maketrans") => return self.maketrans(&args),
            ("dict", "fromkeys") => {
                let keys = args.first().ok_or_else(|| self.error("fromkeys expected at least 1 argument", "TypeError"))?;
                let value = args.get(1).cloned().unwrap_or(Value::None);
                let mut d = DictMap::default();
                for k in self.iterate(keys)? {
                    d.insert(self.hashable(&k)?, value.clone());
                }
                return Ok(Value::new_dict(d));
            }
            ("int", "from_bytes") => return self.int_from_bytes(&args, &kwargs),
            ("bytes", "fromhex") => {
                let s = self.arg_str(&args, 0, "fromhex")?;
                return match from_hex(&s) {
                    Ok(v) => Ok(Value::bytes(&v)),
                    Err(e) => Err(self.byte_error(e)),
                };
            }
            _ => {}
        }
        let Some(receiver) = args.first().cloned() else {
            return Err(self.error(format!("unbound method {ty}.{name}() needs an argument"), "TypeError"));
        };
        let fits = match ty {
            "int" => matches!(receiver, Value::Int(_) | Value::Bool(_)),
            _ => receiver.static_type_name() == ty,
        };
        if !fits {
            return Err(self.error(
                format!("descriptor '{name}' for '{ty}' objects doesn't apply to a '{}' object", receiver.type_name()),
                "TypeError",
            ));
        }
        // `dict.get(d, k)` is the dict method even when d has a key "get".
        if let Value::Dict(d) = &receiver {
            return self.dict_method(d, name, &args[1..], &kwargs);
        }
        self.call_method(&receiver, name, args[1..].to_vec(), kwargs)
    }

    /// `str.maketrans(x, y=None, z=None)`: a table for `str.translate`, from a
    /// dict, or from two strings of equal length and a string of characters to drop.
    fn maketrans(&mut self, args: &[Value]) -> R<Value> {
        let mut table = DictMap::default();
        match args {
            [Value::Dict(d)] => {
                for (k, v) in d.borrow().iter() {
                    let key = match k.to_value() {
                        Value::Str(s) if s.chars().count() == 1 => s.chars().next().unwrap() as i64,
                        Value::Int(i) => i,
                        _ => return Err(self.error("keys in a translate table must be strings of length 1 or integers", "ValueError")),
                    };
                    table.insert(HKey::Int(key), v.clone());
                }
            }
            [Value::Str(x), Value::Str(y), rest @ ..] => {
                if x.chars().count() != y.chars().count() {
                    return Err(self.error("the first two maketrans arguments must have equal length", "ValueError"));
                }
                for (a, b) in x.chars().zip(y.chars()) {
                    table.insert(HKey::Int(a as i64), Value::Int(b as i64));
                }
                match rest {
                    [] => {}
                    [Value::Str(z)] => {
                        for c in z.chars() {
                            table.insert(HKey::Int(c as i64), Value::None);
                        }
                    }
                    _ => return Err(self.error("maketrans() third argument must be a string", "TypeError")),
                }
            }
            _ => return Err(self.error("maketrans() takes a dict, or two strings and an optional third", "TypeError")),
        }
        Ok(Value::new_dict(table))
    }

    /// `slice.indices(length)`: (start, stop, step) clamped to a sequence of that length.
    fn slice_indices_method(&mut self, a: &Value, b: &Value, c: &Value, args: &[Value]) -> R<Value> {
        let n = args.first().and_then(Value::as_int).ok_or_else(|| self.error("indices() needs a length", "TypeError"))?;
        if n < 0 {
            return Err(self.error("length should not be negative", "ValueError"));
        }
        let bound = |v: &Value| -> R<Option<i64>> {
            match v {
                Value::None => Ok(None),
                other => other.as_int().map(Some).ok_or_else(|| self.error("slice indices must be integers or None", "TypeError")),
            }
        };
        let step = bound(c)?.unwrap_or(1);
        if step == 0 {
            return Err(self.error("slice step cannot be zero", "ValueError"));
        }
        let (low, high) = if step > 0 { (0, n) } else { (-1, n - 1) };
        let clamp = |v: Option<i64>, default: i64| match v {
            None => default,
            Some(i) if i < 0 => (i + n).max(low),
            Some(i) => i.min(high),
        };
        let start = clamp(bound(a)?, if step > 0 { 0 } else { n - 1 });
        let stop = clamp(bound(b)?, if step > 0 { n } else { -1 });
        Ok(Value::tuple(vec![Value::Int(start), Value::Int(stop), Value::Int(step)]))
    }

    fn arg_str(&self, args: &[Value], i: usize, method: &str) -> R<Rc<str>> {
        match args.get(i) {
            Some(Value::Str(s)) => Ok(s.clone()),
            _ => Err(self.error(format!("{method}() expects a string argument"), "TypeError")),
        }
    }

    /// The grapheme clusters of [s], as slices of it.
    fn clusters_of<'a>(&mut self, s: &'a Rc<str>) -> Vec<&'a str> {
        let b = self.chars(s);
        (0..b.len() - 1).map(|i| &s[b[i] as usize..b[i + 1] as usize]).collect()
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
                st(swift_upper(cs[0]) + &swift_lower(&cs[1..].concat()))
            }
            "swapcase" => {
                let cs = self.clusters_of(s);
                st(cs.iter().map(|c| if char_is_uppercase(c) { swift_lower(c) } else { swift_upper(c) }).collect())
            }
            "strip" => Ok(self.trim(s, &args, true, true)),
            "lstrip" => Ok(self.trim(s, &args, true, false)),
            "rstrip" => Ok(self.trim(s, &args, false, true)),
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
                // Python's line ends: "\r\n" (one cluster here), "\n", "\r" and the
                // rarer ones. No empty line after a final line end.
                let keep = args.first().or_else(|| kwarg(&kwargs, "keepends")).is_some_and(Value::truthy);
                let mut out = Vec::new();
                let mut cur = String::new();
                for c in self.clusters_of(s) {
                    let ends = matches!(
                        c,
                        "\n" | "\r" | "\r\n" | "\u{0b}" | "\u{0c}" | "\u{1c}" | "\u{1d}" | "\u{1e}" | "\u{85}" | "\u{2028}" | "\u{2029}"
                    );
                    if ends {
                        if keep {
                            cur.push_str(c);
                        }
                        out.push(Value::str(std::mem::take(&mut cur)));
                    } else {
                        cur.push_str(c);
                    }
                }
                if !cur.is_empty() {
                    out.push(Value::str(cur));
                }
                Ok(Value::list(out))
            }
            "join" => {
                let it = args.first().ok_or_else(|| self.error("join() needs an iterable", "TypeError"))?;
                // A list or tuple is joined where it lies, not copied first.
                let joined = match it {
                    Value::List(l) => join_strs(s, &l.borrow()),
                    Value::Tuple(t) => join_strs(s, t),
                    other => {
                        let items = self.iterate(other)?;
                        join_strs(s, &items)
                    }
                };
                match joined {
                    Ok(text) => st(text),
                    Err(ty) => Err(self.error(format!("join() requires str items, found '{ty}'"), "TypeError")),
                }
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
                match cs.first().copied() {
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
            "format_map" => {
                let Some(Value::Dict(d)) = args.first() else {
                    return Err(self.error("format_map() needs a dict", "TypeError"));
                };
                let named: Vec<(Rc<str>, Value)> =
                    d.borrow().iter().filter_map(|(k, v)| match k { HKey::Str(k) => Some((k.clone(), v.clone())), _ => None }).collect();
                st(self.format_method(s, &[], &named)?)
            }
            "translate" => {
                let table = args.first().ok_or_else(|| self.error("translate() needs a table", "TypeError"))?.clone();
                let mut out = String::with_capacity(s.len());
                for c in s.chars() {
                    let key = Value::Int(c as i64);
                    let mapped = match &table {
                        Value::Dict(d) => d.borrow().get(&HKey::Int(c as i64)).cloned(),
                        Value::Instance(_) => match self.dunder(&table, "__getitem__", vec![key]) {
                            Ok(v) => v,
                            Err(super::error::Exc::Error(e)) if !e.is_abort() && matches!(e.kind.as_str(), "KeyError" | "IndexError" | "LookupError") => None,
                            Err(e) => return Err(e),
                        },
                        _ => return Err(self.error("translate() table must be a dict", "TypeError")),
                    };
                    match mapped {
                        None => out.push(c),
                        Some(Value::None) => {}
                        Some(Value::Str(r)) => out.push_str(&r),
                        Some(Value::Int(i)) if super::builtins::is_scalar(i) => out.push(char::from_u32(i as u32).unwrap()),
                        Some(_) => return Err(self.error("character mapping must return an integer, None or a string", "TypeError")),
                    }
                    if out.len() > self.max_string * 4 {
                        return Err(self.string_abort());
                    }
                }
                st(out)
            }
            "expandtabs" => {
                let size = args.first().or_else(|| kwarg(&kwargs, "tabsize")).and_then(Value::as_int).unwrap_or(8).max(0) as usize;
                let mut out = String::new();
                let mut column = 0usize;
                for c in self.clusters_of(s) {
                    match c {
                        "\t" => {
                            if size > 0 {
                                let pad = size - column % size;
                                out.push_str(&" ".repeat(pad));
                                column += pad;
                            }
                        }
                        "\n" | "\r" | "\r\n" => {
                            out.push_str(c);
                            column = 0;
                        }
                        _ => {
                            out.push_str(c);
                            column += 1;
                        }
                    }
                    if column > self.max_string {
                        return Err(self.string_abort());
                    }
                }
                st(out)
            }
            "isascii" => Ok(Value::Bool(s.is_ascii())),
            "isdecimal" => Ok(Value::Bool(!s.is_empty() && s.chars().all(scalar_is_decimal))),
            "isprintable" => Ok(Value::Bool(s.chars().all(scalar_is_printable))),
            "isidentifier" => {
                let mut chars = s.chars();
                let ok = match chars.next() {
                    Some(c) if c == '_' || c.is_alphabetic() => chars.all(|c| c == '_' || c.is_alphanumeric() || matches!(c.general_category_group(), unicode_properties::GeneralCategoryGroup::Mark)),
                    _ => false,
                };
                Ok(Value::Bool(ok))
            }
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

    fn trim(&mut self, s: &Rc<str>, args: &[Value], left: bool, right: bool) -> Value {
        // ASCII text cut by ASCII characters (by default space, tab and line
        // feed: there is no carriage return in it) trims byte by byte.
        let custom = match args.first() {
            Some(Value::Str(c)) => Some(c.clone()),
            _ => None,
        };
        if super::eval::plain_ascii(s) && custom.as_deref().is_none_or(super::eval::plain_ascii) {
            let cut = |b: u8| match &custom {
                Some(c) => c.as_bytes().contains(&b),
                None => matches!(b, b' ' | b'\t' | b'\n'),
            };
            let bytes = s.as_bytes();
            let (mut from, mut to) = (0, bytes.len());
            if left {
                while from < to && cut(bytes[from]) {
                    from += 1;
                }
            }
            if right {
                while to > from && cut(bytes[to - 1]) {
                    to -= 1;
                }
            }
            return if (from, to) == (0, bytes.len()) { Value::Str(s.clone()) } else { Value::str(&s[from..to]) };
        }
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
        Value::str(cs[from..to].concat())
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
            if limit < 0 {
                return Ok(Value::list(s.split(sep.as_ref()).map(Value::str).collect()));
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
        // No separator: split on runs of whitespace, dropping empties. With a
        // limit, what is left after the last split stays whole, as Python
        // keeps it: leading whitespace dropped, trailing kept.
        let b = self.chars(s);
        let n = b.len() - 1;
        let ws = |i: usize| char_is_whitespace(&s[b[i] as usize..b[i + 1] as usize]);
        let full = |ranges: &Vec<(usize, usize)>| limit >= 0 && ranges.len() as i64 == limit;
        // Cluster index ranges of the pieces.
        let mut ranges: Vec<(usize, usize)> = Vec::new();
        if from_end {
            let mut j = n;
            loop {
                while j > 0 && ws(j - 1) {
                    j -= 1;
                }
                if j == 0 {
                    break;
                }
                if full(&ranges) {
                    ranges.push((0, j));
                    break;
                }
                let end = j;
                while j > 0 && !ws(j - 1) {
                    j -= 1;
                }
                ranges.push((j, end));
            }
            ranges.reverse();
        } else {
            let mut i = 0;
            loop {
                while i < n && ws(i) {
                    i += 1;
                }
                if i >= n {
                    break;
                }
                if full(&ranges) {
                    ranges.push((i, n));
                    break;
                }
                let start = i;
                while i < n && !ws(i) {
                    i += 1;
                }
                ranges.push((start, i));
            }
        }
        Ok(Value::list(ranges.into_iter().map(|(a, e)| Value::str(&s[b[a] as usize..b[e] as usize])).collect()))
    }

    /// `str.format`: positional `{}` and `{0}`, named `{name}`, and the f-string spec grammar.
    fn format_method(&mut self, s: &Rc<str>, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<String> {
        // None: no field yet; Some(true): `{}` fields; Some(false): `{0}` fields.
        let mut numbering: Option<bool> = None;
        let mut auto = 0;
        self.format_fields(s, args, kwargs, &mut numbering, &mut auto, 0)
    }

    /// `str.format`'s template [s]: `{field!conversion:spec}` replaced, `{{`
    /// and `}}` kept as braces. A spec may hold fields of its own, one level deep.
    fn format_fields(
        &mut self,
        s: &str,
        args: &[Value],
        kwargs: &[(Rc<str>, Value)],
        numbering: &mut Option<bool>,
        auto: &mut usize,
        depth: u32,
    ) -> R<String> {
        if depth > 2 {
            return Err(self.error("Max string recursion exceeded", "ValueError"));
        }
        let mut out = String::new();
        let mut rest = s;
        while let Some(at) = rest.find(['{', '}']) {
            out.push_str(&rest[..at]);
            let brace = rest.as_bytes()[at];
            let after = &rest[at + 1..];
            if after.as_bytes().first() == Some(&brace) {
                out.push(brace as char);
                rest = &after[1..];
                continue;
            }
            if brace == b'}' {
                return Err(self.error("Single '}' encountered in format string", "ValueError"));
            }
            // The field runs to its matching close brace.
            let mut level = 1;
            let mut end = None;
            for (k, b) in after.bytes().enumerate() {
                match b {
                    b'{' => level += 1,
                    b'}' => {
                        level -= 1;
                        if level == 0 {
                            end = Some(k);
                            break;
                        }
                    }
                    _ => {}
                }
            }
            let Some(end) = end else {
                return Err(self.error(if level > 1 { "unexpected '{' in field name" } else { "expected '}' before end of string" }, "ValueError"));
            };
            let inner = &after[..end];
            rest = &after[end + 1..];
            // field[!conversion][:spec]; the field part never holds ':' or '!'
            // outside an index's brackets.
            let mut cut = inner.len();
            let mut in_index = false;
            for (k, ch) in inner.char_indices() {
                match ch {
                    '[' => in_index = true,
                    ']' => in_index = false,
                    ':' | '!' if !in_index => {
                        cut = k;
                        break;
                    }
                    _ => {}
                }
            }
            let field = &inner[..cut];
            let mut tail = &inner[cut..];
            let mut conversion = None;
            if let Some(t) = tail.strip_prefix('!') {
                let mut chars = t.chars();
                conversion = chars.next();
                tail = chars.as_str();
                if !matches!(conversion, Some('r' | 's' | 'a')) || !(tail.is_empty() || tail.starts_with(':')) {
                    return Err(self.error("expected ':' after conversion specifier", "ValueError"));
                }
            }
            let spec_template = tail.strip_prefix(':').unwrap_or("");
            let value = self.format_field_value(field, args, kwargs, numbering, auto)?;
            let value = match conversion {
                Some('r') => Value::str(self.to_repr(&value)?),
                Some('a') => Value::str(ascii_escape(&self.to_repr(&value)?)),
                Some(_) => Value::str(self.to_str(&value)?),
                None => value,
            };
            let spec = if spec_template.contains('{') {
                self.format_fields(spec_template, args, kwargs, numbering, auto, depth + 1)?
            } else {
                spec_template.to_string()
            };
            match self.call_builtin("format", &[value, Value::str(spec)], &[])? {
                Value::Str(t) => out.push_str(&t),
                other => out.push_str(&py_str(&other)),
            }
            if longer_than(&out, self.max_string) {
                return Err(self.string_abort());
            }
        }
        out.push_str(rest);
        Ok(out)
    }

    /// The value a `str.format` field names: `0`, `name`, or nothing for the
    /// next positional one, then any `.attr` and `[key]` after it.
    fn format_field_value(
        &mut self,
        field: &str,
        args: &[Value],
        kwargs: &[(Rc<str>, Value)],
        numbering: &mut Option<bool>,
        auto: &mut usize,
    ) -> R<Value> {
        let head_end = field.find(['.', '[']).unwrap_or(field.len());
        let head = &field[..head_end];
        let mut value = if head.is_empty() {
            if *numbering == Some(false) {
                return Err(self.error("cannot switch from manual field specification to automatic field numbering", "ValueError"));
            }
            *numbering = Some(true);
            let at = *auto;
            *auto += 1;
            args.get(at).cloned().ok_or_else(|| {
                self.error(format!("Replacement index {at} out of range for positional args tuple"), "IndexError")
            })?
        } else if head.bytes().all(|b| b.is_ascii_digit()) {
            if *numbering == Some(true) {
                return Err(self.error("cannot switch from automatic field numbering to manual field specification", "ValueError"));
            }
            *numbering = Some(false);
            let at: usize = head.parse().unwrap_or(usize::MAX);
            args.get(at).cloned().ok_or_else(|| {
                self.error(format!("Replacement index {at} out of range for positional args tuple"), "IndexError")
            })?
        } else {
            kwarg(kwargs, head).cloned().ok_or_else(|| self.error(format!("'{head}'"), "KeyError"))?
        };
        let mut rest = &field[head_end..];
        while !rest.is_empty() {
            if let Some(r) = rest.strip_prefix('.') {
                let end = r.find(['.', '[']).unwrap_or(r.len());
                if end == 0 {
                    return Err(self.error("Empty attribute in format string", "ValueError"));
                }
                value = self.attribute(&value, &r[..end])?;
                rest = &r[end..];
            } else if let Some(r) = rest.strip_prefix('[') {
                let Some(end) = r.find(']') else {
                    return Err(self.error("Missing ']' in format string", "ValueError"));
                };
                let key = &r[..end];
                let index = match key.parse::<i64>() {
                    Ok(n) if key.bytes().all(|b| b.is_ascii_digit()) => Value::Int(n),
                    _ => Value::str(key),
                };
                value = self.index(&value, &index)?;
                rest = &r[end + 1..];
            } else {
                return Err(self.error("Only '.' or '[' may follow ']' in format field specifier", "ValueError"));
            }
        }
        Ok(value)
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
                let reverse = kwarg(&kwargs, "reverse").is_some_and(Value::truthy);
                self.sort_values(&mut items, kwarg(&kwargs, "key").filter(|k| !k.is_none()).cloned(), reverse)?;
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
        let other = |me: &mut Interp, i: usize| -> R<KeySet> {
            let Some(a) = args.get(i) else {
                return Err(me.error(format!("{name}() needs a set"), "TypeError"));
            };
            if let Value::Set(o) = a {
                return Ok(o.borrow().clone());
            }
            let mut built = KeySet::default();
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
                    return Err(self.key_error(&args[0]));
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
    fn dict_method(&mut self, d: &DictRef, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let key = |me: &mut Interp| -> R<HKey> {
            match args.first() {
                Some(k) => me.hashable(k),
                None => Err(me.error(format!("{name}() needs a key"), "TypeError")),
            }
        };
        match name {
            "keys" => Ok(Value::Object(Rc::new(Object::DictView(ViewKind::Keys, d.clone())))),
            "values" => Ok(Value::Object(Rc::new(Object::DictView(ViewKind::Values, d.clone())))),
            "items" => Ok(Value::Object(Rc::new(Object::DictView(ViewKind::Items, d.clone())))),
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
                    None => Err(self.key_error(&args[0])),
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
                // update(mapping or pairs, **more): every key of each, later ones winning.
                let mut pairs: Vec<(HKey, Value)> = match args.first() {
                    None => Vec::new(),
                    Some(Value::Dict(src)) => src.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
                    Some(first @ Value::Instance(i)) if i.class.lookup("keys").is_some() => self.mapping_pairs(first)?,
                    Some(first) => self.pairs_of(first, "update()")?,
                };
                pairs.extend(kwargs.iter().map(|(k, v)| (HKey::Str(k.clone()), v.clone())));
                let mut m = d.borrow_mut();
                for (k, v) in pairs {
                    m.insert(k, v);
                }
                if m.len() > self.max_container {
                    drop(m);
                    return Err(self.container_abort("dict"));
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
            "join" => {
                let it = args.first().ok_or_else(|| self.error("join() needs an iterable", "TypeError"))?;
                let mut out: Vec<u8> = Vec::new();
                for (i, v) in self.iterate(it)?.into_iter().enumerate() {
                    if i > 0 {
                        out.extend_from_slice(b);
                    }
                    match v {
                        Value::Bytes(p) => out.extend_from_slice(&p),
                        other => {
                            return Err(self.error(
                                format!("join() requires bytes items, found '{}'", other.type_name()),
                                "TypeError",
                            ))
                        }
                    }
                }
                Ok(Value::Bytes(Rc::from(out)))
            }
            "startswith" | "endswith" => {
                let sub = self.arg_bytes(args, 0, name)?;
                Ok(Value::Bool(if name == "startswith" { b.starts_with(&sub) } else { b.ends_with(&sub) }))
            }
            "find" | "index" | "rfind" | "rindex" => {
                let sub = self.arg_bytes(args, 0, name)?;
                // find(sub, start, end), with negative bounds counted from the end.
                let len = b.len() as i64;
                let bound = |v: Option<&Value>, default: i64| match v.and_then(Value::as_int) {
                    Some(i) if i < 0 => (len + i).max(0),
                    Some(i) => i.min(len),
                    None => default,
                };
                let start = bound(args.get(1), 0) as usize;
                let end = (bound(args.get(2), len) as usize).max(start);
                let found = if name.starts_with('r') { rfind_bytes(&b[start..end], &sub) } else { find_bytes(&b[start..end], &sub) };
                match found {
                    Some(i) => Ok(Value::Int((start + i) as i64)),
                    None if name.ends_with("find") => Ok(Value::Int(-1)),
                    None => Err(self.error("subsection not found", "ValueError")),
                }
            }
            "count" => {
                let sub = self.arg_bytes(args, 0, name)?;
                if sub.is_empty() {
                    return Ok(Value::Int(b.len() as i64 + 1));
                }
                let (mut n, mut at) = (0i64, 0usize);
                while let Some(i) = find_bytes(&b[at..], &sub) {
                    n += 1;
                    at += i + sub.len();
                }
                Ok(Value::Int(n))
            }
            "replace" => {
                let old = self.arg_bytes(args, 0, name)?;
                let new = self.arg_bytes(args, 1, name)?;
                if old.is_empty() {
                    return Ok(Value::Bytes(b.clone()));
                }
                let mut out = Vec::with_capacity(b.len());
                let mut at = 0usize;
                while let Some(i) = find_bytes(&b[at..], &old) {
                    out.extend_from_slice(&b[at..at + i]);
                    out.extend_from_slice(&new);
                    at += i + old.len();
                }
                out.extend_from_slice(&b[at..]);
                Ok(Value::Bytes(Rc::from(out)))
            }
            "split" => {
                let pieces: Vec<Vec<u8>> = match args.first().or_else(|| kwarg(kwargs, "sep")) {
                    None | Some(Value::None) => b.split(|c| c.is_ascii_whitespace()).filter(|p| !p.is_empty()).map(<[u8]>::to_vec).collect(),
                    Some(_) => {
                        let sep = self.arg_bytes(args, 0, name)?;
                        if sep.is_empty() {
                            return Err(self.error("empty separator", "ValueError"));
                        }
                        let (mut out, mut at) = (Vec::new(), 0usize);
                        while let Some(i) = find_bytes(&b[at..], &sep) {
                            out.push(b[at..at + i].to_vec());
                            at += i + sep.len();
                        }
                        out.push(b[at..].to_vec());
                        out
                    }
                };
                Ok(Value::list(pieces.into_iter().map(|p| Value::Bytes(Rc::from(p))).collect()))
            }
            "strip" | "lstrip" | "rstrip" => {
                let chars: Vec<u8> = match args.first() {
                    None | Some(Value::None) => b" \t\n\r\x0b\x0c".to_vec(),
                    Some(_) => self.arg_bytes(args, 0, name)?,
                };
                let start = if name == "rstrip" { 0 } else { b.iter().position(|c| !chars.contains(c)).unwrap_or(b.len()) };
                let end = if name == "lstrip" { b.len() } else { b.iter().rposition(|c| !chars.contains(c)).map_or(start, |i| i + 1) };
                Ok(Value::Bytes(Rc::from(&b[start..end.max(start)])))
            }
            "upper" => Ok(Value::Bytes(Rc::from(b.to_ascii_uppercase()))),
            "lower" => Ok(Value::Bytes(Rc::from(b.to_ascii_lowercase()))),
            _ => Err(self.error(format!("'bytes' object has no method '{name}'"), "AttributeError")),
        }
    }

    /// `int.from_bytes(data, byteorder="big", signed=False)`, up to 8 bytes.
    fn int_from_bytes(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let data = self.arg_bytes(args, 0, "from_bytes")?;
        let little = byte_order(self, args.get(1).or_else(|| kwarg(kwargs, "byteorder")))?;
        let signed = args.get(2).or_else(|| kwarg(kwargs, "signed")).is_some_and(Value::truthy);
        let mut bytes = data;
        if little {
            bytes.reverse();
        }
        // Leading zero (or, signed, 0xFF sign) bytes past the eighth change nothing.
        while bytes.len() > 8 && (bytes[0] == 0 || (signed && bytes[0] == 0xFF && bytes[1] >= 0x80)) {
            bytes.remove(0);
        }
        if bytes.len() > 8 || (!signed && bytes.len() == 8 && bytes[0] >= 0x80) {
            return Err(self.error("int too large: this interpreter's integers are 64-bit", "OverflowError"));
        }
        let negative = signed && bytes.first().is_some_and(|b| *b >= 0x80);
        let mut v: i64 = if negative { -1 } else { 0 };
        for b in bytes {
            v = (v << 8) | b as i64;
        }
        Ok(Value::Int(v))
    }

    /// `n.to_bytes(length, byteorder="big", signed=False)` and `n.bit_length()`.
    fn int_method(&mut self, target: &Value, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let n = target.as_int().unwrap_or(0);
        match name {
            "bit_length" => return Ok(Value::Int(64 - n.unsigned_abs().leading_zeros() as i64)),
            "bit_count" => return Ok(Value::Int(n.unsigned_abs().count_ones() as i64)),
            "conjugate" => return Ok(Value::Int(n)),
            "as_integer_ratio" => return Ok(Value::tuple(vec![Value::Int(n), Value::Int(1)])),
            "is_integer" => return Ok(Value::Bool(true)),
            _ => {}
        }
        let length = args.first().or_else(|| kwarg(kwargs, "length")).and_then(Value::as_int).unwrap_or(1);
        let little = byte_order(self, args.get(1).or_else(|| kwarg(kwargs, "byteorder")))?;
        let signed = args.get(2).or_else(|| kwarg(kwargs, "signed")).is_some_and(Value::truthy);
        if !(0..=4096).contains(&length) {
            return Err(self.error("to_bytes() length must be 0 to 4096", "ValueError"));
        }
        if n < 0 && !signed {
            return Err(self.error("can't convert negative int to unsigned", "OverflowError"));
        }
        let bits = if signed { 65 - (if n < 0 { !n } else { n }).leading_zeros() as i64 } else { 64 - (n as u64).leading_zeros() as i64 };
        if bits > length * 8 {
            return Err(self.error("int too big to convert", "OverflowError"));
        }
        let mut out: Vec<u8> = (0..length).map(|i| if i < 8 { (n >> (8 * i)) as u8 } else if n < 0 { 0xFF } else { 0 }).collect();
        if !little {
            out.reverse();
        }
        Ok(Value::bytes(&out))
    }

    fn float_method(&mut self, f: f64, name: &str) -> R<Value> {
        match name {
            "is_integer" => Ok(Value::Bool(f.is_finite() && f.fract() == 0.0)),
            "conjugate" => Ok(Value::Float(f)),
            "as_integer_ratio" => {
                if f.is_nan() {
                    return Err(self.error("cannot convert NaN to integer ratio", "ValueError"));
                }
                if f.is_infinite() {
                    return Err(self.error("cannot convert Infinity to integer ratio", "OverflowError"));
                }
                // f = mantissa * 2^exp exactly; reduce while both stay whole.
                let bits = f.to_bits();
                let sign = if bits >> 63 == 1 { -1i128 } else { 1 };
                let exp = ((bits >> 52) & 0x7FF) as i32;
                let frac = (bits & ((1u64 << 52) - 1)) as i128;
                let (mut m, mut e) = if exp == 0 { (frac, -1074) } else { (frac | (1i128 << 52), exp - 1075) };
                if m == 0 {
                    return Ok(Value::tuple(vec![Value::Int(0), Value::Int(1)]));
                }
                while m & 1 == 0 && e < 0 {
                    m >>= 1;
                    e += 1;
                }
                let (num, den) = if e >= 0 { (m.checked_shl(e as u32).unwrap_or(i128::MAX), 1i128) } else { (m, 1i128 << (-e).min(126)) };
                match (i64::try_from(sign * num), i64::try_from(den)) {
                    (Ok(a), Ok(b)) if e > -63 => Ok(Value::tuple(vec![Value::Int(a), Value::Int(b)])),
                    _ => Err(self.error("as_integer_ratio() result does not fit in 64 bits here", "OverflowError")),
                }
            }
            _ => {
                // hex(): [-]0x1.<13 hex digits>p<exp>
                if f.is_nan() {
                    return Ok(Value::str("nan"));
                }
                if f.is_infinite() {
                    return Ok(Value::str(if f > 0.0 { "inf" } else { "-inf" }));
                }
                let bits = f.to_bits();
                let neg = if bits >> 63 == 1 { "-" } else { "" };
                let exp = ((bits >> 52) & 0x7FF) as i64;
                let frac = bits & ((1u64 << 52) - 1);
                if exp == 0 && frac == 0 {
                    return Ok(Value::str(format!("{neg}0x0.0p+0")));
                }
                let (lead, e) = if exp == 0 { (0, -1022) } else { (1, exp - 1023) };
                let sign = if e < 0 { "-" } else { "+" };
                Ok(Value::str(format!("{neg}0x{lead}.{frac:013x}p{sign}{}", e.abs())))
            }
        }
    }

    fn arg_bytes(&self, args: &[Value], i: usize, method: &str) -> R<Vec<u8>> {
        match args.get(i) {
            Some(Value::Bytes(x)) => Ok(x.to_vec()),
            Some(other) => Err(self.error(format!("{method}() argument must be bytes, not '{}'", other.type_name()), "TypeError")),
            None => Err(self.error(format!("{method}() is missing an argument"), "TypeError")),
        }
    }
}

/// `byteorder`: "big" (the default) or "little"; true for little.
fn byte_order(interp: &Interp, v: Option<&Value>) -> R<bool> {
    match v {
        None => Ok(false),
        Some(Value::Str(s)) if s.as_ref() == "big" => Ok(false),
        Some(Value::Str(s)) if s.as_ref() == "little" => Ok(true),
        Some(_) => Err(interp.error("byteorder must be either 'little' or 'big'", "ValueError")),
    }
}

/// Where [needle] last occurs in [hay].
fn rfind_bytes(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(hay.len());
    }
    if needle.len() > hay.len() {
        return None;
    }
    hay.windows(needle.len()).rposition(|w| w == needle)
}

/// Where [needle] first occurs in [hay].
fn find_bytes(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(0);
    }
    if needle.len() > hay.len() {
        return None;
    }
    hay.windows(needle.len()).position(|w| w == needle)
}

/// [items] joined by [sep], or the type name of the first item that is not a str.
fn join_strs(sep: &str, items: &[Value]) -> Result<String, String> {
    let mut size = sep.len() * items.len().saturating_sub(1);
    for v in items {
        match v {
            Value::Str(p) => size += p.len(),
            other => return Err(other.type_name()),
        }
    }
    let mut out = String::with_capacity(size);
    for (i, v) in items.iter().enumerate() {
        if i > 0 {
            out.push_str(sep);
        }
        if let Value::Str(p) = v {
            out.push_str(p);
        }
    }
    Ok(out)
}
