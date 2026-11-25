//! Port of PyBuiltins.kt: the global functions a script can call without
//! importing anything. Anything that reaches outside the script (`open`,
//! `input`, `eval`, `__import__`) was never written, so those names don't resolve.

use std::rc::Rc;

use indexmap::{IndexMap, IndexSet};

use super::ast::BinOp;
use super::bytes::{encode, ByteError};
use super::error::{Exc, R};
use super::eval::Interp;
use super::format::py_format;
use super::text::{decimal_value, grapheme_count, longer_than, parse_swift_double, trim_whitespaces};
use super::value::*;

/// The exception names scripts can raise and catch.
pub(crate) const EXCEPTION_KINDS: &[&str] = &[
    "Exception",
    "ValueError",
    "TypeError",
    "KeyError",
    "IndexError",
    "NameError",
    "ZeroDivisionError",
    "AttributeError",
    "RuntimeError",
    "StopIteration",
    "UnicodeDecodeError",
    // Additions to PyMini.
    "AssertionError",
    "NotImplementedError",
    "LookupError",
    "ArithmeticError",
    "OverflowError",
    "ImportError",
    "UnicodeError",
    "OSError",
    "PermissionError",
    "TimeoutError",
    "ConnectionError",
    "FileNotFoundError",
];

/// Names that resolve to a builtin when nothing else is bound to them.
pub(crate) const BUILTIN_NAMES: &[&str] = &[
    "len", "str", "repr", "print", "int", "float", "bool", "abs", "round", "min", "max", "sum", "range", "sorted",
    "reversed", "list", "dict", "set", "tuple", "enumerate", "zip", "ord", "chr", "any", "all", "type", "map",
    "filter", "divmod", "pow", "hex", "bin", "oct", "format", "isinstance", "callable", "bytes", "Exception",
    "ValueError", "TypeError", "KeyError", "IndexError", "NameError", "ZeroDivisionError", "AttributeError",
    "RuntimeError", "StopIteration", "UnicodeDecodeError",
    // Additions to PyMini.
    "getattr", "setattr", "hasattr", "delattr", "vars", "iter", "next", "property", "staticmethod", "classmethod",
    "super", "object", "issubclass", "id", "hash", "NotImplemented", "AssertionError", "NotImplementedError",
    "LookupError", "ArithmeticError", "OverflowError", "ImportError", "UnicodeError", "OSError", "PermissionError",
    "TimeoutError", "ConnectionError", "FileNotFoundError", "slice", "ascii",
];

/// Whether [name] is one of [BUILTIN_NAMES], by a hash lookup rather than a
/// walk down the list: every builtin a script names goes through here.
pub(crate) fn is_builtin_name(name: &str) -> bool {
    static SET: std::sync::OnceLock<std::collections::HashSet<&'static str, super::fxhash::FxBuild>> = std::sync::OnceLock::new();
    SET.get_or_init(|| BUILTIN_NAMES.iter().copied().collect()).contains(name)
}

/// Builtins written in Python (`src/stdlib/_builtins.py`), loaded the first
/// time a script names one.
pub(crate) const PYTHON_BUILTINS: &[&str] = &["frozenset", "bytearray"];

/// The builtins that take keyword arguments.
pub(crate) const KWARG_BUILTINS: &[&str] = &[
    "sorted", "min", "max", "round", "property", "print", "enumerate", "dict", "sum", "int", "zip", "str", "bytes",
];

/// Swift's `Unicode.Scalar(i)` succeeds for these: any code point but a surrogate.
pub(crate) fn is_scalar(i: i64) -> bool {
    (0..=0x10FFFF).contains(&i) && !(0xD800..=0xDFFF).contains(&i)
}

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

impl Interp {
    #[inline(never)]
    pub(crate) fn call_builtin(&mut self, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        if let Some(path) = name.strip_prefix(super::methods::UNBOUND_PREFIX) {
            let path = path.to_string();
            return self.call_type_member(&path, args, kwargs);
        }
        // Every exception name is capitalised, and no other builtin is.
        if name.as_bytes().first().is_some_and(u8::is_ascii_uppercase) && EXCEPTION_KINDS.contains(&name) {
            // What str(e) gives, as CPython has it: nothing, the one argument
            // (a KeyError's in quotes), or the tuple of them all.
            let message = match args.len() {
                0 => String::new(),
                1 if name == "KeyError" => self.to_repr(&args[0])?,
                1 => self.to_str(&args[0])?,
                _ => self.to_repr(&Value::tuple(args.clone()))?,
            };
            return Ok(Value::Exception(Rc::new(ExceptionValue { kind: name.to_string(), message, args, cause: None })));
        }
        // The common builtins first; the ones classes brought after.
        match name {
            "len" => {
                self.arity(name, &args, 1)?;
                Ok(Value::Int(self.length(&args[0])? as i64))
            }
            "str" => match (args.first().or_else(|| kwarg(&kwargs, "object")), args.get(1).or_else(|| kwarg(&kwargs, "encoding"))) {
                // str(data, "utf-8") decodes, as bytes.decode does.
                (Some(Value::Bytes(_)), Some(_)) => {
                    let mut rest: Vec<Value> = args.iter().skip(1).cloned().collect();
                    if rest.is_empty() {
                        rest.extend(kwarg(&kwargs, "encoding").cloned());
                    }
                    let errors = kwarg(&kwargs, "errors").cloned().map(|e| vec![(Rc::from("errors"), e)]).unwrap_or_default();
                    self.call_method(&args[0], "decode", rest, errors)
                }
                (Some(v), _) => Ok(Value::str(self.to_str(v)?)),
                (None, _) => Ok(Value::str("")),
            },
            "repr" => {
                self.arity(name, &args, 1)?;
                Ok(Value::str(self.to_repr(&args[0])?))
            }
            "ascii" => {
                self.arity(name, &args, 1)?;
                Ok(Value::str(ascii_escape(&self.to_repr(&args[0])?)))
            }
            "print" => {
                let text_kwarg = |me: &Interp, key: &str, default: &str| -> R<String> {
                    match kwarg(&kwargs, key) {
                        None | Some(Value::None) => Ok(default.to_string()),
                        Some(Value::Str(s)) => Ok(s.to_string()),
                        Some(other) => Err(me.error(format!("{key} must be None or a string, not {}", other.type_name()), "TypeError")),
                    }
                };
                let sep = text_kwarg(self, "sep", " ")?;
                let end = text_kwarg(self, "end", "\n")?;
                let mut parts = Vec::with_capacity(args.len());
                for a in &args {
                    parts.push(self.to_str(a)?);
                }
                // Each print is one line of output; `end` other than a line end
                // leaves the line open for the next print to carry on.
                let mut text = std::mem::take(&mut self.print_partial);
                text.push_str(&parts.join(&sep));
                if end == "\n" {
                    self.emit(text);
                } else {
                    text.push_str(&end);
                    match text.rfind('\n') {
                        Some(at) => {
                            self.print_partial = text.split_off(at + 1);
                            text.pop();
                            self.emit(text);
                        }
                        None => self.print_partial = text,
                    }
                    if longer_than(&self.print_partial, self.max_string) {
                        self.print_partial.clear();
                        return Err(self.string_abort());
                    }
                }
                Ok(Value::None)
            }
            "int" => {
                let mut all = args.clone();
                if let Some(b) = kwarg(&kwargs, "base") {
                    if all.is_empty() {
                        return Err(self.error("int() missing string argument", "TypeError"));
                    }
                    all.truncate(1);
                    all.push(b.clone());
                }
                self.to_int(&all)
            }
            "float" => self.to_float(&args),
            "bool" => Ok(Value::Bool(args.first().is_some_and(Value::truthy))),
            "abs" => {
                self.arity(name, &args, 1)?;
                if let Value::Int(i) = args[0] {
                    return Ok(Value::Int(i.wrapping_abs()));
                }
                let d = args[0].as_float().ok_or_else(|| self.error("bad operand type for abs()", "TypeError"))?;
                Ok(Value::Float(d.abs()))
            }
            "round" => self.round_builtin(&args, &kwargs),
            "min" => self.minmax(&args, &kwargs, false),
            "max" => self.minmax(&args, &kwargs, true),
            "sum" => {
                let first = args.first().ok_or_else(|| self.error("sum() needs an iterable", "TypeError"))?;
                let mut acc = args.get(1).or_else(|| kwarg(&kwargs, "start")).cloned().unwrap_or(Value::Int(0));
                for v in self.iterate(first)? {
                    acc = self.binary_op(BinOp::Add, &acc, &v)?;
                }
                Ok(acc)
            }
            "range" => self.range_builtin(&args),
            "sorted" => self.sorted_builtin(&args, &kwargs),
            "reversed" => {
                self.arity(name, &args, 1)?;
                let mut items = self.iterate(&args[0])?;
                items.reverse();
                Ok(Value::list(items))
            }
            "list" => Ok(Value::list(match args.first() {
                Some(a) => self.iterate(a)?,
                None => Vec::new(),
            })),
            "tuple" => Ok(Value::tuple(match args.first() {
                Some(a) => self.iterate(a)?,
                None => Vec::new(),
            })),
            "set" => {
                let mut s = IndexSet::new();
                if let Some(a) = args.first() {
                    for v in self.iterate(a)? {
                        s.insert(self.hashable(&v)?);
                    }
                }
                Ok(Value::new_set(s))
            }
            "dict" => self.dict_builtin(&args, &kwargs),
            "enumerate" => {
                let first = args.first().ok_or_else(|| self.error("enumerate() needs an iterable", "TypeError"))?;
                let start = args.get(1).or_else(|| kwarg(&kwargs, "start")).and_then(Value::as_int).unwrap_or(0);
                let items = self.iterate(first)?;
                Ok(Value::list(
                    items.into_iter().enumerate().map(|(o, v)| Value::tuple(vec![Value::Int(start + o as i64), v])).collect(),
                ))
            }
            "zip" => {
                // An iterator object (itertools.count(), anything with __next__)
                // gives only as many items as the shortest other argument has,
                // so zip(count(1), names) works on an endless one.
                let lazy = |v: &Value| matches!(v, Value::Instance(i) if i.class.lookup("__next__").is_some());
                let mut known: Vec<Option<Vec<Value>>> = Vec::new();
                for a in &args {
                    known.push(if lazy(a) { None } else { Some(self.iterate(a)?) });
                }
                let finite = known.iter().flatten().map(Vec::len).min();
                let mut seqs = Vec::new();
                for (a, k) in args.iter().zip(known) {
                    seqs.push(match (k, finite) {
                        (Some(items), _) => items,
                        (None, None) => self.iterate(a)?,
                        (None, Some(n)) => {
                            let mut items = Vec::with_capacity(n);
                            while items.len() < n {
                                self.tick()?;
                                match self.dunder(a, "__next__", Vec::new()) {
                                    Ok(Some(v)) => items.push(v),
                                    Ok(None) => break,
                                    Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" => break,
                                    Err(e) => return Err(e),
                                }
                            }
                            items
                        }
                    });
                }
                let Some(shortest) = seqs.iter().map(Vec::len).min() else {
                    return Ok(Value::list(Vec::new()));
                };
                if kwarg(&kwargs, "strict").is_some_and(Value::truthy) && seqs.iter().any(|s| s.len() != shortest) {
                    return Err(self.error("zip() arguments have different lengths", "ValueError"));
                }
                Ok(Value::list((0..shortest).map(|i| Value::tuple(seqs.iter().map(|s| s[i].clone()).collect())).collect()))
            }
            "map" => {
                if args.len() < 2 {
                    return Err(self.error("map() needs a function and an iterable", "TypeError"));
                }
                let mut seqs = Vec::new();
                for a in &args[1..] {
                    seqs.push(self.iterate(a)?);
                }
                let count = seqs.iter().map(Vec::len).min().unwrap_or(0);
                let mut out = Vec::new();
                for i in 0..count {
                    self.tick()?;
                    out.push(self.invoke(&args[0], seqs.iter().map(|s| s[i].clone()).collect())?);
                }
                Ok(Value::list(out))
            }
            "filter" => {
                if args.len() != 2 {
                    return Err(self.error("filter() needs a function and an iterable", "TypeError"));
                }
                let mut out = Vec::new();
                for v in self.iterate(&args[1])? {
                    self.tick()?;
                    // filter(None, xs) keeps the truthy items, as in Python.
                    let keep = if args[0].is_none() { v.truthy() } else { self.invoke(&args[0], vec![v.clone()])?.truthy() };
                    if keep {
                        out.push(v);
                    }
                }
                Ok(Value::list(out))
            }
            "divmod" => {
                self.arity(name, &args, 2)?;
                // One message for both halves, as CPython gives.
                if args[1].as_float() == Some(0.0) && args[0].as_float().is_some() {
                    let message = if matches!(args[0], Value::Float(_)) || matches!(args[1], Value::Float(_)) {
                        "float divmod()"
                    } else {
                        "integer division or modulo by zero"
                    };
                    return Err(self.error(message, "ZeroDivisionError"));
                }
                let q = self.binary_op(BinOp::FloorDiv, &args[0], &args[1])?;
                let r = self.binary_op(BinOp::Mod, &args[0], &args[1])?;
                Ok(Value::tuple(vec![q, r]))
            }
            "pow" => {
                if args.len() != 2 && args.len() != 3 {
                    return Err(self.error("pow() takes 2 or 3 arguments", "TypeError"));
                }
                let p = self.binary_op(BinOp::Pow, &args[0], &args[1])?;
                if args.len() != 3 {
                    return Ok(p);
                }
                self.binary_op(BinOp::Mod, &p, &args[2])
            }
            "hex" => self.radix_string(&args, 16, "0x"),
            "bin" => self.radix_string(&args, 2, "0b"),
            "oct" => self.radix_string(&args, 8, "0o"),
            "format" => {
                let v = args.first().ok_or_else(|| self.error("format() needs a value", "TypeError"))?;
                let spec = args.get(1).map(py_str).unwrap_or_default();
                if let Value::Instance(_) = v {
                    if let Some(Value::Str(s)) = self.dunder(v, "__format__", vec![Value::str(&spec)])? {
                        return Ok(Value::Str(s));
                    }
                    let text = Value::str(self.to_str(v)?);
                    return Ok(Value::str(py_format(&text, &spec)?));
                }
                Ok(Value::str(py_format(v, &spec)?))
            }
            "isinstance" => {
                self.arity(name, &args, 2)?;
                Ok(Value::Bool(self.is_instance(&args[0], &args[1])))
            }
            "callable" => {
                self.arity(name, &args, 1)?;
                Ok(Value::Bool(match &args[0] {
                    Value::Function(_) | Value::Builtin(_) | Value::ModuleFunction(_) | Value::HostFunction(_) | Value::Class(_) => true,
                    Value::Object(o) => matches!(o.as_ref(), Object::Bound(..) | Object::StaticMethod(_) | Object::ClassMethod(_)),
                    Value::Instance(i) => i.class.lookup("__call__").is_some(),
                    _ => false,
                }))
            }
            "ord" => {
                self.arity(name, &args, 1)?;
                match &args[0] {
                    Value::Str(s) if grapheme_count(s) == 1 => Ok(Value::Int(s.chars().next().unwrap() as i64)),
                    _ => Err(self.error("ord() expected a character", "TypeError")),
                }
            }
            "chr" => {
                self.arity(name, &args, 1)?;
                match args[0].as_int() {
                    Some(i) if is_scalar(i) => Ok(Value::str(char::from_u32(i as u32).unwrap().to_string())),
                    _ => Err(self.error("chr() arg not in range", "ValueError")),
                }
            }
            "any" => {
                self.arity(name, &args, 1)?;
                Ok(Value::Bool(self.iterate(&args[0])?.iter().any(Value::truthy)))
            }
            "all" => {
                self.arity(name, &args, 1)?;
                Ok(Value::Bool(self.iterate(&args[0])?.iter().all(Value::truthy)))
            }
            "type" => {
                self.arity(name, &args, 1)?;
                // PyMini answers with a name. An instance answers with its class,
                // which is what `type(self).__name__` and `type(x) is Point` need.
                match &args[0] {
                    Value::Instance(i) => Ok(Value::Class(i.class.clone())),
                    other => Ok(Value::str(other.type_name())),
                }
            }
            "bytes" => {
                let mut all = args.clone();
                if let (1, Some(enc)) = (all.len(), kwarg(&kwargs, "encoding")) {
                    all.push(enc.clone());
                }
                self.bytes_builtin(&all)
            }
            "slice" => {
                let (a, b, c) = match args.len() {
                    1 => (Value::None, args[0].clone(), Value::None),
                    2 => (args[0].clone(), args[1].clone(), Value::None),
                    3 => (args[0].clone(), args[1].clone(), args[2].clone()),
                    n => return Err(self.error(format!("slice expected 1 to 3 arguments, got {n}"), "TypeError")),
                };
                Ok(Value::Object(Rc::new(Object::Slice(a, b, c))))
            }
            _ => match self.class_builtin(name, &args, &kwargs)? {
                Some(v) => Ok(v),
                None => Err(self.error(format!("name '{name}' is not defined"), "NameError")),
            },
        }
    }

    pub(crate) fn arity(&self, name: &str, args: &[Value], n: usize) -> R<()> {
        if args.len() != n {
            return Err(self.error(format!("{name}() takes {n} argument(s) but {} were given", args.len()), "TypeError"));
        }
        Ok(())
    }

    pub(crate) fn length(&mut self, v: &Value) -> R<usize> {
        Ok(match v {
            // A short string is counted in place; a long one through the
            // cache, so a loop asking `len(text)` again does not walk it again.
            Value::Str(s) if s.len() < 64 => grapheme_count(s),
            Value::Str(s) => self.chars(s).len() - 1,
            Value::List(l) => l.borrow().len(),
            Value::Tuple(t) => t.len(),
            Value::Set(s) => s.borrow().len(),
            Value::Dict(d) => d.borrow().len(),
            Value::Bytes(b) => b.len(),
            Value::Class(c) => {
                let c = c.clone();
                self.iterate_class(&c)?.len()
            }
            Value::Instance(_) => match self.dunder(v, "__len__", Vec::new())? {
                Some(n) => match n.as_int() {
                    Some(n) if n >= 0 => n as usize,
                    _ => return Err(self.error("__len__ should return an integer >= 0", "ValueError")),
                },
                None => return Err(self.error(format!("object of type '{}' has no len()", v.type_name()), "TypeError")),
            },
            _ => return Err(self.error(format!("object of type '{}' has no len()", v.type_name()), "TypeError")),
        })
    }

    fn radix_string(&self, args: &[Value], radix: u32, prefix: &str) -> R<Value> {
        let i = args.first().and_then(Value::as_int).ok_or_else(|| self.error("expected an integer", "TypeError"))?;
        let body = to_radix(i.unsigned_abs(), radix);
        Ok(Value::str(format!("{}{prefix}{body}", if i < 0 { "-" } else { "" })))
    }

    /// Swift's `Int(d)` traps past the i64 range; here that's a catchable error instead.
    pub(crate) fn to_long_exact(&self, d: f64) -> R<i64> {
        if d.is_nan() || d >= 9.223372036854775807E18 || d < -9.223372036854775808E18 {
            return Err(self.error(format!("cannot convert float {} to integer", format_double(d)), "OverflowError"));
        }
        Ok(d as i64)
    }

    fn to_int(&self, args: &[Value]) -> R<Value> {
        let Some(a) = args.first() else { return Ok(Value::Int(0)) };
        if args.len() > 1 {
            let base = args[1].as_int().ok_or_else(|| self.error("int() base must be an integer", "TypeError"))?;
            if base != 0 && !(2..=36).contains(&base) {
                return Err(self.error("int() base must be >= 2 and <= 36, or 0", "ValueError"));
            }
            let Value::Str(s) = a else {
                return Err(self.error("int() can't convert non-string with explicit base", "TypeError"));
            };
            return parse_int_literal(trim_whitespaces(s), base as u32)
                .map(Value::Int)
                .ok_or_else(|| self.error(format!("invalid literal for int() with base {base}: {}", py_repr(a)), "ValueError"));
        }
        match a {
            Value::Int(_) | Value::Bool(_) => Ok(Value::Int(a.as_int().unwrap())),
            Value::Float(f) => Ok(Value::Int(self.to_long_exact(*f)?)),
            Value::Str(s) => {
                let t = trim_whitespaces(s);
                if let Some(i) = parse_int_literal(t, 10) {
                    return Ok(Value::Int(i));
                }
                // CPython refuses "3.5" here; PyMini reads it.
                let d = parse_swift_double(t)
                    .ok_or_else(|| self.error(format!("invalid literal for int() with base 10: {}", py_repr(a)), "ValueError"))?;
                Ok(Value::Int(self.to_long_exact(d)?))
            }
            _ => Err(self.error("int() argument must be a string or a number", "TypeError")),
        }
    }

    fn to_float(&self, args: &[Value]) -> R<Value> {
        let Some(a) = args.first() else { return Ok(Value::Float(0.0)) };
        if let Some(d) = a.as_float() {
            return Ok(Value::Float(d));
        }
        if let Value::Str(s) = a {
            if let Some(d) = parse_swift_double(trim_whitespaces(s)) {
                return Ok(Value::Float(d));
            }
        }
        Err(self.error(format!("could not convert to float: {}", py_repr(a)), "ValueError"))
    }

    fn round_builtin(&self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let d = args.first().and_then(Value::as_float).ok_or_else(|| self.error("round() needs a number", "TypeError"))?;
        let digits = match args.get(1).or_else(|| kwarg(kwargs, "ndigits")) {
            None => return Ok(Value::Int(self.to_long_exact(d.round_ties_even())?)),
            Some(v) => v.as_int().unwrap_or(0),
        };
        let m = 10f64.powf(digits as f64);
        // Only the one-argument form returns an int. `round(x, 0)` is still a float.
        Ok(Value::Float((d * m).round_ties_even() / m))
    }

    fn minmax(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)], want_max: bool) -> R<Value> {
        let items = if args.len() == 1 { self.iterate(&args[0])? } else { args.to_vec() };
        let key = kwarg(kwargs, "key").cloned();
        if items.is_empty() {
            if let Some(d) = kwarg(kwargs, "default") {
                return Ok(d.clone());
            }
            return Err(self.error(format!("{}() arg is an empty sequence", if want_max { "max" } else { "min" }), "ValueError"));
        }
        let mut best = items[0].clone();
        let mut best_key = match &key {
            Some(k) => self.invoke(k, vec![best.clone()])?,
            None => best.clone(),
        };
        for v in items.into_iter().skip(1) {
            self.tick()?;
            let k = match &key {
                Some(f) => self.invoke(f, vec![v.clone()])?,
                None => v.clone(),
            };
            let c = self.order(&k, &best_key)?;
            if (want_max && c > 0) || (!want_max && c < 0) {
                best = v;
                best_key = k;
            }
        }
        Ok(best)
    }

    fn range_builtin(&self, args: &[Value]) -> R<Value> {
        let mut nums = Vec::new();
        for a in args {
            nums.push(a.as_int().ok_or_else(|| self.error("range() argument must be an integer", "TypeError"))?);
        }
        let (start, stop, step) = match nums.len() {
            1 => (0, nums[0], 1),
            2 => (nums[0], nums[1], 1),
            3 => (nums[0], nums[1], nums[2]),
            _ => return Err(self.error("range() takes 1 to 3 arguments", "TypeError")),
        };
        if step == 0 {
            return Err(self.error("range() step must not be zero", "ValueError"));
        }
        let mut out = Vec::new();
        let mut i = start;
        while (step > 0 && i < stop) || (step < 0 && i > stop) {
            out.push(Value::Int(i));
            if out.len() > self.max_container {
                return Err(self.container_abort("range"));
            }
            i = match i.checked_add(step) {
                Some(n) => n,
                None => break,
            };
        }
        Ok(Value::list(out))
    }

    /// `dict()`, `dict(mapping)`, `dict(pairs)`, each with `key=value` keywords on top.
    fn dict_builtin(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let mut d = IndexMap::new();
        if let Some(first) = args.first() {
            let pairs = match first {
                Value::Dict(src) => src.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
                Value::Instance(i) if i.class.lookup("keys").is_some() => self.mapping_pairs(first)?,
                // dict([(k, v), ...]), the shape zip() and items() hand back.
                other => self.pairs_of(other, "dict()")?,
            };
            for (k, v) in pairs {
                d.insert(k, v);
            }
        }
        for (k, v) in kwargs {
            d.insert(HKey::Str(k.clone()), v.clone());
        }
        if d.len() > self.max_container {
            return Err(self.container_abort("dict"));
        }
        Ok(Value::new_dict(d))
    }

    pub(crate) fn sorted_builtin(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        self.arity("sorted", args, 1)?;
        let mut items = self.iterate(&args[0])?;
        let reverse = kwarg(kwargs, "reverse").is_some_and(Value::truthy);
        self.sort_values(&mut items, kwarg(kwargs, "key").cloned(), reverse)?;
        Ok(Value::list(items))
    }

    /// Python's sort: stable, `reverse` keeping equal items in their order, and
    /// an error for things that do not compare. A bottom-up merge sort, so
    /// n log n comparisons at most (n for a list already in order), each
    /// charged to the step budget.
    pub(crate) fn sort_values(&mut self, items: &mut Vec<Value>, key: Option<Value>, reverse: bool) -> R<()> {
        let n = items.len();
        if n <= 1 {
            return Ok(());
        }
        // Reversed, sorted, reversed back: what CPython does for reverse=True,
        // so equal items come out in the order they went in.
        if reverse {
            items.reverse();
        }
        let keys: Vec<Value> = match &key {
            Some(k) => {
                let mut keys = Vec::with_capacity(n);
                for v in items.iter() {
                    self.tick()?;
                    keys.push(self.invoke(k, vec![v.clone()])?);
                }
                keys
            }
            None => items.clone(),
        };
        let mut order: Vec<usize> = (0..n).collect();
        let mut scratch: Vec<usize> = vec![0; n];
        let mut width = 1;
        while width < n {
            let mut lo = 0;
            while lo < n {
                let mid = (lo + width).min(n);
                let hi = (lo + 2 * width).min(n);
                if mid >= hi {
                    scratch[lo..hi].copy_from_slice(&order[lo..hi]);
                    lo = hi;
                    continue;
                }
                // Already in order across the seam: nothing to merge.
                self.tick()?;
                if self.order(&keys[order[mid]], &keys[order[mid - 1]])? >= 0 {
                    scratch[lo..hi].copy_from_slice(&order[lo..hi]);
                    lo = hi;
                    continue;
                }
                let (mut i, mut j, mut k) = (lo, mid, lo);
                while i < mid && j < hi {
                    self.tick()?;
                    // Take from the right only when strictly smaller: that keeps it stable.
                    if self.order(&keys[order[j]], &keys[order[i]])? < 0 {
                        scratch[k] = order[j];
                        j += 1;
                    } else {
                        scratch[k] = order[i];
                        i += 1;
                    }
                    k += 1;
                }
                scratch[k..k + (mid - i)].copy_from_slice(&order[i..mid]);
                k += mid - i;
                scratch[k..k + (hi - j)].copy_from_slice(&order[j..hi]);
                lo = hi;
            }
            std::mem::swap(&mut order, &mut scratch);
            width *= 2;
        }
        let sorted: Vec<Value> = order.iter().map(|&i| items[i].clone()).collect();
        *items = sorted;
        if reverse {
            items.reverse();
        }
        Ok(())
    }

    /// `frozenset` and `bytearray`, written in Python: the first use loads them.
    pub(crate) fn python_builtin(&mut self, name: &str) -> R<Value> {
        const MODULE: &str = "_builtins";
        if !self.source_modules.contains_key(MODULE) {
            self.source_modules.insert(MODULE.to_string(), Rc::from(super::stdlib::BUILTINS_SOURCE));
        }
        let saved = self.current_line;
        self.ensure_module(MODULE)?;
        self.current_line = saved;
        self.member(MODULE, name).ok_or_else(|| self.error(format!("name '{name}' is not defined"), "NameError"))
    }

    fn bytes_builtin(&mut self, args: &[Value]) -> R<Value> {
        let Some(first) = args.first() else { return Ok(Value::bytes(&[])) };
        match first {
            Value::Bytes(b) => Ok(Value::Bytes(b.clone())),
            Value::Str(s) => {
                let Some(enc) = args.get(1) else {
                    return Err(self.error("string argument without an encoding", "TypeError"));
                };
                let enc = enc.as_str().ok_or_else(|| self.error("bytes() argument 'encoding' must be str", "TypeError"))?;
                match encode(s, enc) {
                    Ok(b) => Ok(Value::bytes(&b)),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            Value::Int(n) => {
                if *n < 0 {
                    return Err(self.error("negative count", "ValueError"));
                }
                if *n as usize > self.max_string {
                    return Err(self.string_abort());
                }
                Ok(Value::bytes(&vec![0u8; *n as usize]))
            }
            other => {
                let mut out = Vec::new();
                for v in self.iterate(other)? {
                    match v.as_int() {
                        Some(i) if (0..=255).contains(&i) => out.push(i as u8),
                        Some(_) => return Err(self.error("bytes must be in range(0, 256)", "ValueError")),
                        None => {
                            return Err(self.error(
                                format!("'{}' object cannot be interpreted as an integer", v.type_name()),
                                "TypeError",
                            ))
                        }
                    }
                }
                Ok(Value::bytes(&out))
            }
        }
    }

    pub(crate) fn byte_error(&self, e: ByteError) -> Exc {
        match e {
            ByteError::UnknownEncoding(name) => self.error(format!("unknown encoding: {name}"), "LookupError"),
            ByteError::Decode(message) => self.error(message, "UnicodeDecodeError"),
            ByteError::Hex(message) => self.error(message, "ValueError"),
            ByteError::Errors(name) => self.error(format!("unknown error handler name '{name}'"), "LookupError"),
        }
    }
}

/// An integer literal as `int(text, base)` reads it: a sign, `_` between
/// digits, and with base 0 (or the matching base) a `0x`, `0o` or `0b` prefix.
pub(crate) fn parse_int_literal(text: &str, base: u32) -> Option<i64> {
    let (negative, body) = match text.as_bytes().first() {
        Some(b'-') => (true, &text[1..]),
        Some(b'+') => (false, &text[1..]),
        _ => (false, text),
    };
    let lower = body.to_ascii_lowercase();
    let prefix = ["0x", "0o", "0b"].iter().zip([16u32, 8, 2]).find(|(p, _)| lower.starts_with(**p)).map(|(_, r)| r);
    let (radix, digits) = match (base, prefix) {
        (0, Some(r)) => (r, &body[2..]),
        (b, Some(r)) if b == r => (r, &body[2..]),
        (0, None) => {
            // Base 0 refuses a leading zero, as a Python literal does: 010 is an error, 00 is 0.
            if body.starts_with('0') && body.chars().any(|c| c != '0' && c != '_') {
                return None;
            }
            (10, body)
        }
        (b, _) => (b, body),
    };
    // One `_` may follow a prefix (0x_ff); otherwise `_` only between digits.
    let digits = if radix != base || base == 0 { digits.strip_prefix('_').filter(|_| prefix.is_some()).unwrap_or(digits) } else { digits };
    if digits.is_empty() || digits.starts_with('_') || digits.ends_with('_') || digits.contains("__") {
        return None;
    }
    let mut clean: String = digits.chars().filter(|c| *c != '_').collect();
    if radix == 10 && !clean.is_ascii() {
        // Digits from other scripts (٣, ৩), as Python reads them.
        clean = clean.chars().map(|c| decimal_value(c).and_then(|d| char::from_digit(d, 10))).collect::<Option<String>>()?;
    }
    if !clean.chars().all(|c| c.is_digit(radix)) {
        return None;
    }
    let magnitude = u64::from_str_radix(&clean, radix).ok()?;
    if negative {
        if magnitude == 1 << 63 {
            return Some(i64::MIN);
        }
        i64::try_from(magnitude).ok().map(|v| -v)
    } else {
        i64::try_from(magnitude).ok()
    }
}

pub(crate) fn to_radix(mut n: u64, radix: u32) -> String {
    if n == 0 {
        return "0".into();
    }
    let mut digits = Vec::new();
    while n > 0 {
        digits.push(std::char::from_digit((n % radix as u64) as u32, radix).unwrap());
        n /= radix as u64;
    }
    digits.iter().rev().collect()
}
