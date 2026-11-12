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
use super::text::{grapheme_count, parse_swift_double, parse_swift_int, trim_whitespaces};
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
    "TimeoutError", "ConnectionError", "FileNotFoundError",
];

/// The only builtins that take keyword arguments.
pub(crate) const KWARG_BUILTINS: &[&str] = &["sorted", "min", "max", "round", "property"];

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
        if EXCEPTION_KINDS.contains(&name) {
            let message = args.iter().map(py_str).collect::<Vec<_>>().join(" ");
            return Ok(Value::Exception(Rc::new(ExceptionValue { kind: name.to_string(), message })));
        }
        if let Some(v) = self.class_builtin(name, &args, &kwargs)? {
            return Ok(v);
        }
        match name {
            "len" => {
                self.arity(name, &args, 1)?;
                Ok(Value::Int(self.length(&args[0])? as i64))
            }
            "str" => match args.first() {
                Some(v) => Ok(Value::str(self.to_str(v)?)),
                None => Ok(Value::str("")),
            },
            "repr" => {
                self.arity(name, &args, 1)?;
                Ok(Value::str(self.to_repr(&args[0])?))
            }
            "print" => {
                let mut parts = Vec::with_capacity(args.len());
                for a in &args {
                    parts.push(self.to_str(a)?);
                }
                self.emit(parts.join(" "));
                Ok(Value::None)
            }
            "int" => self.to_int(&args),
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
                let mut acc = args.get(1).cloned().unwrap_or(Value::Int(0));
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
            "dict" => self.dict_builtin(&args),
            "enumerate" => {
                let first = args.first().ok_or_else(|| self.error("enumerate() needs an iterable", "TypeError"))?;
                let start = args.get(1).and_then(Value::as_int).unwrap_or(0);
                let items = self.iterate(first)?;
                Ok(Value::list(
                    items.into_iter().enumerate().map(|(o, v)| Value::tuple(vec![Value::Int(start + o as i64), v])).collect(),
                ))
            }
            "zip" => {
                let mut seqs = Vec::new();
                for a in &args {
                    seqs.push(self.iterate(a)?);
                }
                let Some(shortest) = seqs.iter().map(Vec::len).min() else {
                    return Ok(Value::list(Vec::new()));
                };
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
            "bytes" => self.bytes_builtin(&args),
            _ => Err(self.error(format!("name '{name}' is not defined"), "NameError")),
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
            let i = match (a, args[1].as_int()) {
                (Value::Str(s), Some(base)) if (2..=36).contains(&base) => parse_swift_int(trim_whitespaces(s), base as u32),
                _ => None,
            };
            return i.map(Value::Int).ok_or_else(|| self.error("invalid literal for int() with that base", "ValueError"));
        }
        match a {
            Value::Int(_) | Value::Bool(_) => Ok(Value::Int(a.as_int().unwrap())),
            Value::Float(f) => Ok(Value::Int(self.to_long_exact(*f)?)),
            Value::Str(s) => {
                let t = trim_whitespaces(s);
                if let Some(i) = parse_swift_int(t, 10) {
                    return Ok(Value::Int(i));
                }
                // CPython refuses "3.5" here; PyMini reads it.
                let d = parse_swift_double(t)
                    .ok_or_else(|| self.error(format!("invalid literal for int(): {}", py_repr(a)), "ValueError"))?;
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

    fn dict_builtin(&mut self, args: &[Value]) -> R<Value> {
        let mut d = IndexMap::new();
        let Some(first) = args.first() else { return Ok(Value::new_dict(d)) };
        if let Value::Dict(src) = first {
            for (k, v) in src.borrow().iter() {
                d.insert(k.clone(), v.clone());
            }
            return Ok(Value::new_dict(d));
        }
        if let Value::Instance(i) = first {
            if i.class.lookup("keys").is_some() {
                for (k, v) in self.mapping_pairs(first)? {
                    d.insert(k, v);
                }
                return Ok(Value::new_dict(d));
            }
        }
        // dict([(k, v), ...]), the shape zip() and items() hand back.
        for pair in self.iterate(first)? {
            let items = sequence_items(&pair);
            let key = items.as_ref().filter(|i| i.len() == 2).and_then(|i| HKey::from_value(&i[0]));
            match (items, key) {
                (Some(items), Some(key)) => {
                    d.insert(key, items[1].clone());
                }
                _ => return Err(self.error("dict() needs (key, value) pairs", "ValueError")),
            }
        }
        Ok(Value::new_dict(d))
    }

    pub(crate) fn sorted_builtin(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        self.arity("sorted", args, 1)?;
        let mut items = self.iterate(&args[0])?;
        let reverse = kwarg(kwargs, "reverse").is_some_and(Value::truthy);
        self.insertion_sort(&mut items, kwarg(kwargs, "key").cloned())?;
        if reverse {
            items.reverse();
        }
        Ok(Value::list(items))
    }

    /// A stable insertion sort on the three-way order, which throws on mixed
    /// types. Quadratic, but every comparison is charged to the budget.
    pub(crate) fn insertion_sort(&mut self, items: &mut [Value], key: Option<Value>) -> R<()> {
        if items.len() <= 1 {
            return Ok(());
        }
        let mut keys = Vec::with_capacity(items.len());
        match &key {
            Some(k) => {
                for v in items.iter() {
                    self.tick()?;
                    keys.push(self.invoke(k, vec![v.clone()])?);
                }
            }
            None => keys.extend(items.iter().cloned()),
        }
        for i in 1..items.len() {
            let mut j = i;
            while j > 0 && self.order(&keys[j], &keys[j - 1])? < 0 {
                items.swap(j, j - 1);
                keys.swap(j, j - 1);
                j -= 1;
                self.tick()?;
            }
        }
        Ok(())
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
