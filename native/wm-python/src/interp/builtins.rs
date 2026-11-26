//! Port of PyBuiltins.kt: the global functions a script can call without
//! importing anything. Anything that reaches outside the script (`open`,
//! `input`, `__import__`) was never written, so those names don't resolve;
//! `eval` and `exec` run code in the same interpreter, with the same reach.

use std::rc::Rc;

use super::ast::BinOp;
use super::bytes::{encode_with, ByteError};
use super::error::{Exc, R};
use super::eval::Interp;
use super::format::py_format;
use super::generators::{iterator_object, Iter};
use super::text::{decimal_value, grapheme_count, longer_than, parse_swift_double, trim_python_space};
use super::bigint::{int_value, to_big};
use super::value::*;
use num_traits::Signed;

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
    "UnboundLocalError",
    "GeneratorExit",
    "StopAsyncIteration",
    "BaseException",
    "SystemExit",
    "KeyboardInterrupt",
    "RecursionError",
    "ModuleNotFoundError",
    "UnicodeEncodeError",
    "EOFError",
    "FloatingPointError",
    "SyntaxError",
    "IndentationError",
    "FileExistsError",
    "IsADirectoryError",
    "NotADirectoryError",
    "BlockingIOError",
    "ConnectionRefusedError",
    "ConnectionResetError",
    "ConnectionAbortedError",
    "BrokenPipeError",
    "Warning",
    "UserWarning",
    "DeprecationWarning",
    "PendingDeprecationWarning",
    "RuntimeWarning",
    "SyntaxWarning",
    "FutureWarning",
    "ImportWarning",
    "UnicodeWarning",
    "BytesWarning",
    "ResourceWarning",
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
    "TimeoutError", "ConnectionError", "FileNotFoundError", "slice", "ascii", "UnboundLocalError", "GeneratorExit",
    "eval", "exec", "Ellipsis", "globals", "locals", "dir", "__import__",
];

/// The builtins that are types: `type(x)` answers with one, and `str()` of
/// one reads `<class 'int'>`.
pub(crate) fn is_type_name(name: &str) -> bool {
    matches!(
        name,
        "int" | "float" | "str" | "bool" | "list" | "dict" | "set" | "tuple" | "bytes" | "object" | "type" | "range"
            | "slice" | "NoneType" | "function" | "builtin_function_or_method" | "module" | "method" | "generator"
            | "map" | "filter" | "zip" | "enumerate" | "reversed" | "property" | "staticmethod" | "classmethod"
            | "super" | "re.Match" | "re.Pattern" | "ellipsis" | "NotImplementedType" | "dict_keys" | "dict_values"
            | "dict_items" | "coroutine" | "async_generator"
    ) || name.ends_with("_iterator")
        || EXCEPTION_KINDS.contains(&name)
}

/// Whether [name] is one of [BUILTIN_NAMES], by a hash lookup rather than a
/// walk down the list: every builtin a script names goes through here.
pub(crate) fn is_builtin_name(name: &str) -> bool {
    static SET: std::sync::OnceLock<std::collections::HashSet<&'static str, super::fxhash::FxBuild>> = std::sync::OnceLock::new();
    SET.get_or_init(|| BUILTIN_NAMES.iter().chain(EXCEPTION_KINDS).copied().collect()).contains(name)
}

/// Builtins written in Python (`src/stdlib/_builtins.py`), loaded the first
/// time a script names one.
pub(crate) const PYTHON_BUILTINS: &[&str] =
    &["frozenset", "bytearray", "memoryview", "__type_alias__", "GenericAlias", "aiter", "anext", "__union__", "__iter_call__"];

/// The builtins that take keyword arguments.
pub(crate) const KWARG_BUILTINS: &[&str] = &[
    "sorted", "min", "max", "round", "property", "print", "enumerate", "dict", "sum", "int", "zip", "str", "bytes",
    "__import__", "__base_init__", "__object_new__",
];

/// The special methods a numeric builtin asks an instance for.
fn numeric_hooks(name: &str) -> &'static [&'static str] {
    match name {
        "int" => &["__int__", "__index__", "__trunc__"],
        "float" => &["__float__", "__index__"],
        "bytes" => &["__bytes__", "__index__", "__iter__"],
        "abs" => &["__abs__"],
        "round" => &["__round__"],
        "divmod" => &["__divmod__", "__rdivmod__"],
        "pow" => &["__pow__", "__rpow__"],
        "hex" | "oct" | "bin" | "chr" | "range" => &["__index__"],
        _ => &[],
    }
}

/// Swift's `Unicode.Scalar(i)` succeeds for these: any code point but a surrogate.
pub(crate) fn is_scalar(i: i64) -> bool {
    (0..=0x10FFFF).contains(&i) && !(0xD800..=0xDFFF).contains(&i)
}

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

impl Interp {
    #[inline(never)]
    /// A builtin called with these arguments, which it only reads: a call
    /// site can hand them over from the stack, with nothing allocated.
    pub(crate) fn call_builtin(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        if let Some(path) = name.strip_prefix(super::methods::UNBOUND_PREFIX) {
            let path = path.to_string();
            return self.call_type_member(&path, args.to_vec(), kwargs.to_vec());
        }
        // Every exception name is capitalised, and no other builtin is.
        if name.as_bytes().first().is_some_and(u8::is_ascii_uppercase) && EXCEPTION_KINDS.contains(&name) {
            // What str(e) gives, as CPython has it: nothing, the one argument
            // (a KeyError's in quotes), or the tuple of them all.
            let message = match args.len() {
                0 => String::new(),
                1 if name == "KeyError" => self.to_repr(&args[0])?,
                1 => self.to_str(&args[0])?,
                _ => self.to_repr(&Value::tuple(args.to_vec()))?,
            };
            return Ok(Value::Exception(Rc::new(ExceptionValue { kind: name.to_string(), message, args: args.to_vec(), cause: None, context: None })));
        }
        // A subclass of a built-in type passed to a numeric builtin is its
        // value, unless its class answers for the builtin itself.
        if matches!(name, "int" | "float" | "bytes" | "abs" | "round" | "hex" | "oct" | "bin" | "chr" | "divmod" | "pow" | "range" | "ord")
            && args.iter().any(|a| payload_of(a).is_some())
        {
            let hooks = numeric_hooks(name);
            let plain: Vec<Value> = args
                .iter()
                .map(|a| match a {
                    Value::Instance(i) if i.payload().is_some() && !hooks.iter().any(|h| i.class.defines(h)) => i.payload().cloned().unwrap(),
                    other => other.clone(),
                })
                .collect();
            let kwargs: Vec<(Rc<str>, Value)> = kwargs.iter().map(|(k, v)| (k.clone(), plain_value(v.clone()))).collect();
            if plain.iter().all(|a| payload_of(a).is_none()) {
                return self.call_builtin(name, &plain, &kwargs);
            }
        }
        // An instance passed to a numeric builtin answers through its dunders.
        if matches!(name, "int" | "float" | "bool" | "bytes" | "abs" | "round" | "hex" | "oct" | "bin" | "chr" | "divmod" | "pow" | "range")
            && args.iter().any(|a| matches!(a, Value::Instance(_)))
        {
            if let Some(v) = self.number_protocol(name, args, kwargs)? {
                return Ok(v);
            }
        }
        // The common builtins first; the ones classes brought after.
        match name {
            "len" => {
                self.arity(name, args, 1)?;
                Ok(Value::Int(self.length(&args[0])? as i64))
            }
            "str" => match (args.first().or_else(|| kwarg(kwargs, "object")), args.get(1).or_else(|| kwarg(kwargs, "encoding"))) {
                // str(data, "utf-8") decodes, as bytes.decode does.
                (Some(Value::Bytes(_)), Some(_)) => {
                    let mut rest: Vec<Value> = args.iter().skip(1).cloned().collect();
                    if rest.is_empty() {
                        rest.extend(kwarg(kwargs, "encoding").cloned());
                    }
                    let errors = kwarg(kwargs, "errors").cloned().map(|e| vec![(Rc::from("errors"), e)]).unwrap_or_default();
                    self.call_method(&args[0], "decode", rest, errors)
                }
                (Some(v), _) => Ok(Value::str(self.to_str(v)?)),
                (None, _) => Ok(Value::str("")),
            },
            "repr" => {
                self.arity(name, args, 1)?;
                Ok(Value::str(self.to_repr(&args[0])?))
            }
            "__import__" => {
                // `__import__(name, globals, locals, fromlist, level)`: the top
                // package, or with a fromlist the module named itself.
                let Some(Value::Str(module)) = args.first().or_else(|| kwarg(kwargs, "name")) else {
                    return Err(self.error("__import__() argument 1 must be str", "TypeError"));
                };
                let level = args.get(4).or_else(|| kwarg(kwargs, "level")).and_then(Value::as_int).unwrap_or(0);
                let dotted: Rc<str> = if level > 0 { Rc::from(format!("{}{module}", ".".repeat(level as usize))) } else { module.clone() };
                let resolved = self.resolve_module(&dotted)?;
                self.ensure_module(&resolved)?;
                let fromlist = args.get(3).or_else(|| kwarg(kwargs, "fromlist")).is_some_and(Value::truthy);
                if fromlist || level > 0 {
                    return Ok(Value::Module(resolved));
                }
                Ok(Value::Module(Rc::from(resolved.split('.').next().unwrap_or(&resolved))))
            }
            "ascii" => {
                self.arity(name, args, 1)?;
                Ok(Value::str(ascii_escape(&self.to_repr(&args[0])?)))
            }
            "print" => {
                let text_kwarg = |me: &Interp, key: &str, default: &str| -> R<String> {
                    match kwarg(kwargs, key) {
                        None | Some(Value::None) => Ok(default.to_string()),
                        Some(Value::Str(s)) => Ok(s.to_string()),
                        Some(other) => Err(me.error(format!("{key} must be None or a string, not {}", other.type_name()), "TypeError")),
                    }
                };
                let sep = text_kwarg(self, "sep", " ")?;
                let end = text_kwarg(self, "end", "\n")?;
                let mut parts = Vec::with_capacity(args.len());
                for a in args {
                    parts.push(self.to_str(a)?);
                }
                // `file=` anything but the standard streams (or `sys.stdout` set
                // to something else): its `write` gets the text.
                let file = match kwarg(kwargs, "file") {
                    None | Some(Value::None) => self.stdout_target.clone(),
                    Some(Value::Module(m)) if m.as_ref() == "sys.stdout" || m.as_ref() == "sys.stderr" => None,
                    Some(f) => Some(f.clone()),
                };
                match file {
                    None => {}
                    Some(f) => {
                        let write = self.attribute(&f, "write")?;
                        let text = parts.join(&sep) + &end;
                        self.call_value(&write, vec![Value::str(text)], Vec::new())?;
                        return Ok(Value::None);
                    }
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
                let mut all = args.to_vec();
                if let Some(b) = kwarg(kwargs, "base") {
                    if all.is_empty() {
                        return Err(self.error("int() missing string argument", "TypeError"));
                    }
                    all.truncate(1);
                    all.push(b.clone());
                }
                self.to_int(&all)
            }
            "float" => self.to_float(args),
            "bool" => Ok(Value::Bool(args.first().is_some_and(Value::truthy))),
            "abs" => {
                self.arity(name, args, 1)?;
                if let Value::Int(i) = args[0] {
                    return Ok(i.checked_abs().map_or_else(|| int_value(num_bigint::BigInt::from(i).abs()), Value::Int));
                }
                if let Value::Big(b) = &args[0] {
                    return Ok(int_value(b.abs()));
                }
                let d = args[0].as_float().ok_or_else(|| self.error("bad operand type for abs()", "TypeError"))?;
                Ok(Value::Float(d.abs()))
            }
            "round" => self.round_builtin(args, kwargs),
            "min" => self.minmax(args, kwargs, false),
            "max" => self.minmax(args, kwargs, true),
            "sum" => {
                let first = args.first().ok_or_else(|| self.error("sum() needs an iterable", "TypeError"))?;
                let mut acc = args.get(1).or_else(|| kwarg(kwargs, "start")).cloned().unwrap_or(Value::Int(0));
                let mut it = self.iter_of(first)?;
                // Floats add with Neumaier's compensation, as CPython 3.12 does:
                // sum([0.1] * 10) is 1.0.
                let mut lost = 0.0f64;
                while let Some(v) = self.next_item(&mut it)? {
                    self.tick()?;
                    if let Value::Float(s) = acc {
                        let x = match v {
                            Value::Float(x) => Some(x),
                            Value::Int(i) => Some(i as f64),
                            _ => None,
                        };
                        if let Some(x) = x {
                            let t = s + x;
                            if t.is_finite() {
                                lost += if s.abs() >= x.abs() { (s - t) + x } else { (x - t) + s };
                            }
                            acc = Value::Float(t);
                            continue;
                        }
                    }
                    if lost != 0.0 {
                        if let Value::Float(s) = acc {
                            acc = Value::Float(s + lost);
                        }
                        lost = 0.0;
                    }
                    acc = self.binary_op(BinOp::Add, &acc, &v)?;
                }
                if let Value::Float(s) = acc {
                    if lost != 0.0 && lost.is_finite() {
                        acc = Value::Float(s + lost);
                    }
                }
                Ok(acc)
            }
            "range" => self.range_builtin(args),
            "sorted" => self.sorted_builtin(args, kwargs),
            "reversed" => {
                self.arity(name, args, 1)?;
                if let Value::Object(o) = &args[0] {
                    if let Object::Range(a, b, c) = o.as_ref() {
                        let n = range_len(*a, *b, *c);
                        let last = a + (n - 1) * c;
                        let it = Iter::Range { next: (n > 0).then_some(last), stop: a - c, step: -c };
                        return Ok(iterator_object("range_iterator", it));
                    }
                    if let Object::BigRange(r) = o.as_ref() {
                        let n = r.len();
                        let last = if num_traits::Signed::is_positive(&n) { r.at(&(n - 1)) } else { r.start.clone() };
                        let it = Iter::BigRange(Box::new((last, &r.start - &r.step, -&r.step)));
                        return Ok(iterator_object("longrange_iterator", it));
                    }
                }
                if let Some(r) = self.dunder(&args[0], "__reversed__", Vec::new())? {
                    return Ok(r);
                }
                let view = matches!(&args[0], Value::Object(o) if matches!(o.as_ref(), Object::DictView(..)));
                if !view && !matches!(&args[0], Value::List(_) | Value::Tuple(_) | Value::Str(_) | Value::Bytes(_) | Value::Instance(_) | Value::Dict(_)) {
                    return Err(self.error(format!("'{}' object is not reversible", args[0].type_name()), "TypeError"));
                }
                let mut items = self.iterate(&args[0])?;
                items.reverse();
                Ok(iterator_object("reversed", Iter::Items(items.into_iter())))
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
                let mut s = KeySet::default();
                if let Some(a) = args.first() {
                    for v in self.iterate(a)? {
                        s.insert(self.hashable(&v)?);
                    }
                }
                Ok(Value::new_set(s))
            }
            "dict" => self.dict_builtin(args, kwargs),
            "enumerate" => {
                let first = args.first().ok_or_else(|| self.error("enumerate() needs an iterable", "TypeError"))?;
                let start = args.get(1).or_else(|| kwarg(kwargs, "start")).and_then(Value::as_int).unwrap_or(0);
                let it = self.iter_of(first)?;
                Ok(iterator_object("enumerate", Iter::Enumerate(Box::new(it), start)))
            }
            "zip" => {
                let mut sources = Vec::with_capacity(args.len());
                for a in args {
                    sources.push(self.iter_of(a)?);
                }
                let strict = kwarg(kwargs, "strict").is_some_and(Value::truthy);
                Ok(iterator_object("zip", if sources.is_empty() { Iter::Empty } else { Iter::Zip(sources, strict) }))
            }
            "map" => {
                if args.len() < 2 {
                    return Err(self.error("map() must have at least two arguments.", "TypeError"));
                }
                let mut sources = Vec::with_capacity(args.len() - 1);
                for a in &args[1..] {
                    sources.push(self.iter_of(a)?);
                }
                Ok(iterator_object("map", Iter::Map(Box::new((args[0].clone(), sources)))))
            }
            "filter" => {
                if args.len() != 2 {
                    return Err(self.error(format!("filter expected 2 arguments, got {}", args.len()), "TypeError"));
                }
                let it = self.iter_of(&args[1])?;
                Ok(iterator_object("filter", Iter::Filter(Box::new((args[0].clone(), it)))))
            }
            "divmod" => {
                self.arity(name, args, 2)?;
                // One message for both halves, as CPython gives.
                if args[1].as_float() == Some(0.0) && args[0].as_float().is_some() {
                    let message = if matches!(&args[0], Value::Float(_)) || matches!(&args[1], Value::Float(_)) {
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
                // pow(a, b, m) on ints works modulo m all the way, as Python's does.
                if args.len() == 3 {
                    if let (Some(a), Some(b), Some(m)) = (to_big(&args[0]), to_big(&args[1]), to_big(&args[2])) {
                        return super::bigint::modpow(&a, &b, &m).map_err(|(m, k)| self.error(m, k));
                    }
                }
                let p = self.binary_op(BinOp::Pow, &args[0], &args[1])?;
                if args.len() != 3 {
                    return Ok(p);
                }
                self.binary_op(BinOp::Mod, &p, &args[2])
            }
            "hex" => self.radix_string(args, 16, "0x"),
            "bin" => self.radix_string(args, 2, "0b"),
            "oct" => self.radix_string(args, 8, "0o"),
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
                // A list of objects shows each through its own `__repr__`.
                if holds_instance(v) {
                    let text = Value::str(self.to_str(v)?);
                    return Ok(Value::str(py_format(&text, &spec)?));
                }
                Ok(Value::str(py_format(v, &spec)?))
            }
            "isinstance" => {
                self.arity(name, args, 2)?;
                if self.is_instance(&args[0], &args[1]) {
                    return Ok(Value::Bool(true));
                }
                // As CPython, an object whose class says otherwise through
                // `__class__` (a `Mock(spec=Thing)`) counts as that class.
                if let Value::Instance(inst) = &args[0] {
                    if inst.class.lookup("__class__").is_some() {
                        let claimed = self.attribute(&args[0], "__class__")?;
                        if !matches!(&claimed, Value::Class(c) if Rc::ptr_eq(c, &inst.class)) && self.type_matches(&claimed, &args[1]) {
                            return Ok(Value::Bool(true));
                        }
                    }
                }
                Ok(Value::Bool(self.instance_hook(&args[0], &args[1])?))
            }
            "callable" => {
                self.arity(name, args, 1)?;
                Ok(Value::Bool(match &args[0] {
                    Value::Builtin(n) if matches!(n.as_ref(), "Ellipsis" | "NotImplemented") => false,
                    Value::Function(_) | Value::Builtin(_) | Value::ModuleFunction(_) | Value::HostFunction(_) | Value::Class(_) => true,
                    Value::Object(o) => matches!(o.as_ref(), Object::Bound(..) | Object::StaticMethod(_) | Object::ClassMethod(_)),
                    Value::Instance(i) => i.class.lookup("__call__").is_some(),
                    _ => false,
                }))
            }
            "ord" => {
                self.arity(name, args, 1)?;
                match &args[0] {
                    Value::Str(s) if grapheme_count(s) == 1 => Ok(Value::Int(s.chars().next().unwrap() as i64)),
                    _ => Err(self.error("ord() expected a character", "TypeError")),
                }
            }
            "chr" => {
                self.arity(name, args, 1)?;
                match args[0].as_int() {
                    Some(i) if is_scalar(i) => Ok(Value::str(char::from_u32(i as u32).unwrap().to_string())),
                    _ => Err(self.error("chr() arg not in range", "ValueError")),
                }
            }
            // Both stop at the first item that settles the answer.
            "any" | "all" => {
                self.arity(name, args, 1)?;
                let want = name == "any";
                let mut it = self.iter_of(&args[0])?;
                while let Some(v) = self.next_item(&mut it)? {
                    if self.truth(&v)? == want {
                        return Ok(Value::Bool(want));
                    }
                }
                Ok(Value::Bool(!want))
            }
            "type" => match args.len() {
                1 => Ok(self.type_of(&args[0])),
                3 => self.type_new(None, args, kwargs),
                _ => Err(self.error("type() takes 1 or 3 arguments", "TypeError")),
            },
            "bytes" => {
                let mut all = args.to_vec();
                if let (1, Some(enc)) = (all.len(), kwarg(kwargs, "encoding")) {
                    all.push(enc.clone());
                }
                self.bytes_builtin(&all)
            }
            // Called from somewhere that knows no scope: in the main module.
            "eval" | "exec" => self.eval_exec(name, args, None),
            "globals" | "locals" => {
                let g = self.globals.clone();
                self.scope_builtin(name, &g)
            }
            "dir" => match args.first() {
                None => {
                    let g = self.globals.clone();
                    self.scope_builtin(name, &g)
                }
                Some(v) => {
                    let v = v.clone();
                    self.dir_of(&v)
                }
            },
            "slice" => {
                let (a, b, c) = match args.len() {
                    1 => (Value::None, args[0].clone(), Value::None),
                    2 => (args[0].clone(), args[1].clone(), Value::None),
                    3 => (args[0].clone(), args[1].clone(), args[2].clone()),
                    n => return Err(self.error(format!("slice expected 1 to 3 arguments, got {n}"), "TypeError")),
                };
                Ok(Value::Object(Rc::new(Object::Slice(a, b, c))))
            }
            _ => match self.class_builtin(name, args, kwargs)? {
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
            Value::Object(o) if matches!(o.as_ref(), Object::DictView(..)) => {
                let Object::DictView(_, d) = o.as_ref() else { unreachable!() };
                d.borrow().len()
            }
            Value::Class(c) => {
                let c = c.clone();
                if let Some(f) = self.meta_method(&c, "__len__") {
                    let n = self.call_value(&f, Vec::new(), Vec::new())?;
                    return match n.as_int() {
                        Some(n) if n >= 0 => Ok(n as usize),
                        _ => Err(self.error("__len__() should return an int >= 0", "TypeError")),
                    };
                }
                self.iterate_class(&c)?.len()
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) => {
                let Object::Range(a, b, c) = o.as_ref() else { unreachable!() };
                range_len(*a, *b, *c) as usize
            }
            Value::Object(o) if matches!(o.as_ref(), Object::BigRange(_)) => {
                let Object::BigRange(r) = o.as_ref() else { unreachable!() };
                num_traits::ToPrimitive::to_i64(&r.len())
                    .ok_or_else(|| self.error("Python int too large to convert to C ssize_t", "OverflowError"))? as usize
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
        if let Some(Value::Big(b)) = args.first() {
            let sign = if b.is_negative() { "-" } else { "" };
            return Ok(Value::str(format!("{sign}{prefix}{}", super::bigint::magnitude_radix(b, radix))));
        }
        let i = args.first().and_then(Value::as_int).ok_or_else(|| self.error("expected an integer", "TypeError"))?;
        let body = to_radix(i.unsigned_abs(), radix);
        Ok(Value::str(format!("{}{prefix}{body}", if i < 0 { "-" } else { "" })))
    }

    /// `int(d)` for a float: the whole number it truncates to, of any size.
    pub(crate) fn float_to_int(&self, d: f64) -> R<Value> {
        // 2**63: every float below it in size fits an i64.
        const LIMIT: f64 = 9_223_372_036_854_775_808.0;
        if (-LIMIT..LIMIT).contains(&d) {
            return Ok(Value::Int(d as i64));
        }
        super::bigint::from_float(d).map_err(|(m, k)| self.error(m, k))
    }

    fn to_int(&self, args: &[Value]) -> R<Value> {
        let Some(a) = args.first() else { return Ok(Value::Int(0)) };
        // `int(b"ff", 16)`: bytes read as ASCII digits, as a str would be.
        if let Value::Bytes(b) = a {
            let text = Value::str(String::from_utf8_lossy(b));
            let mut rest = vec![text];
            rest.extend(args[1..].iter().cloned());
            return self.to_int(&rest).map_err(|e| {
                if args.len() > 1 {
                    e
                } else {
                    self.error(format!("invalid literal for int() with base 10: {}", py_repr(a)), "ValueError")
                }
            });
        }
        if args.len() > 1 {
            let base = args[1].as_int().ok_or_else(|| self.error("int() base must be an integer", "TypeError"))?;
            if base != 0 && !(2..=36).contains(&base) {
                return Err(self.error("int() base must be >= 2 and <= 36, or 0", "ValueError"));
            }
            let Value::Str(s) = a else {
                return Err(self.error("int() can't convert non-string with explicit base", "TypeError"));
            };
            return parse_int_value(trim_python_space(s), base as u32)
                .map_err(|(m, k)| self.error(m, k))?
                .ok_or_else(|| self.error(format!("invalid literal for int() with base {base}: {}", py_repr(a)), "ValueError"));
        }
        match a {
            Value::Int(_) | Value::Bool(_) => Ok(Value::Int(a.as_int().unwrap())),
            Value::Big(_) => Ok(a.clone()),
            Value::Float(f) => self.float_to_int(*f),
            Value::Str(s) => {
                let t = trim_python_space(s);
                if let Some(v) = parse_int_value(t, 10).map_err(|(m, k)| self.error(m, k))? {
                    return Ok(v);
                }
                // CPython refuses "3.5" here; PyMini reads it.
                let d = parse_swift_double(t)
                    .ok_or_else(|| self.error(format!("invalid literal for int() with base 10: {}", py_repr(a)), "ValueError"))?;
                self.float_to_int(d)
            }
            _ => Err(self.error("int() argument must be a string or a number", "TypeError")),
        }
    }

    fn to_float(&self, args: &[Value]) -> R<Value> {
        let Some(a) = args.first() else { return Ok(Value::Float(0.0)) };
        if let Value::Big(b) = a {
            return super::bigint::to_float(b).map(Value::Float).map_err(|(m, k)| self.error(m, k));
        }
        if let Some(d) = a.as_float() {
            return Ok(Value::Float(d));
        }
        let text = match a {
            Value::Str(s) => Some(s.clone()),
            // `float(b"1.5")`: bytes read as ASCII digits, as int's are.
            Value::Bytes(b) => Some(Rc::from(String::from_utf8_lossy(b).as_ref())),
            _ => None,
        };
        if let Some(s) = text {
            if let Some(d) = parse_swift_double(trim_python_space(&s)) {
                return Ok(Value::Float(d));
            }
        }
        Err(self.error(format!("could not convert string to float: {}", py_repr(a)), "ValueError"))
    }

    fn round_builtin(&self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let ndigits = args.get(1).or_else(|| kwarg(kwargs, "ndigits")).filter(|v| !v.is_none());
        // An int rounds to an int: itself, or to tens, hundreds and so on, halves to even.
        if let Some(n) = args.first().and_then(to_big) {
            let digits = ndigits.and_then(Value::as_int).unwrap_or(0);
            if digits >= 0 {
                return Ok(int_value(n));
            }
            let m = num_traits::pow(num_bigint::BigInt::from(10), (-digits).min(400_000) as usize);
            let (q, r) = num_integer::Integer::div_mod_floor(&n, &m);
            let twice = &r * 2;
            let up = twice > m || (twice == m && num_integer::Integer::is_odd(&q));
            return Ok(int_value((if up { q + 1 } else { q }) * m));
        }
        let d = args.first().and_then(Value::as_float).ok_or_else(|| self.error("round() needs a number", "TypeError"))?;
        let digits = match ndigits {
            None => return self.float_to_int(d.round_ties_even()).and_then(|v| {
                if d.is_infinite() {
                    Err(self.error("cannot convert float infinity to integer", "OverflowError"))
                } else {
                    Ok(v)
                }
            }),
            Some(v) => v.as_int().unwrap_or(0),
        };
        let m = 10f64.powf(digits as f64);
        // Only the one-argument form returns an int. `round(x, 0)` is still a float.
        Ok(Value::Float((d * m).round_ties_even() / m))
    }

    fn minmax(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)], want_max: bool) -> R<Value> {
        if args.is_empty() {
            return Err(self.error(format!("{} expected at least 1 argument, got 0", if want_max { "max" } else { "min" }), "TypeError"));
        }
        // One iterable, or the arguments themselves.
        let mut items = if args.len() == 1 { Some(self.iter_of(&args[0])?) } else { None };
        let mut at = 0;
        let key = kwarg(kwargs, "key").filter(|k| !k.is_none()).cloned();
        let Some(first) = self.minmax_next(&mut items, args, &mut at)? else {
            if let Some(d) = kwarg(kwargs, "default") {
                return Ok(d.clone());
            }
            return Err(self.error(format!("{}() iterable argument is empty", if want_max { "max" } else { "min" }), "ValueError"));
        };
        let mut best = first;
        let mut best_key = match &key {
            Some(k) => self.invoke(k, vec![best.clone()])?,
            None => best.clone(),
        };
        while let Some(v) = self.minmax_next(&mut items, args, &mut at)? {
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

    fn minmax_next(&mut self, items: &mut Option<Iter>, args: &[Value], at: &mut usize) -> R<Option<Value>> {
        match items {
            Some(it) => self.next_item(it),
            None => {
                *at += 1;
                Ok(args.get(*at - 1).cloned())
            }
        }
    }

    /// `type(v)`: an instance's class, or the builtin type of anything else.
    pub(crate) fn type_of(&self, v: &Value) -> Value {
        match v {
            Value::Instance(i) => Value::Class(i.class.clone()),
            Value::Class(c) => c.meta.clone().map(Value::Class).unwrap_or_else(|| Value::Builtin(Rc::from("type"))),
            Value::Builtin(n) if n.as_ref() == "Ellipsis" => Value::Builtin(Rc::from("ellipsis")),
            Value::Builtin(n) if is_type_name(n) => Value::Builtin(Rc::from("type")),
            Value::Builtin(n) if n.as_ref() == "NotImplemented" => Value::Builtin(Rc::from("NotImplementedType")),
            Value::Builtin(n) if !is_type_name(n) && !n.starts_with(super::methods::UNBOUND_PREFIX) => {
                Value::Builtin(Rc::from("builtin_function_or_method"))
            }
            Value::HostFunction(_) | Value::ModuleFunction(_) => Value::Builtin(Rc::from("builtin_function_or_method")),
            other => Value::Builtin(Rc::from(other.type_name())),
        }
    }

    fn range_builtin(&self, args: &[Value]) -> R<Value> {
        if args.iter().any(|a| matches!(a, Value::Big(_))) {
            return self.big_range(args);
        }
        let mut nums = Vec::new();
        for a in args {
            nums.push(a.as_int().ok_or_else(|| {
                self.error(format!("'{}' object cannot be interpreted as an integer", a.type_name()), "TypeError")
            })?);
        }
        let (start, stop, step) = match nums.len() {
            1 => (0, nums[0], 1),
            2 => (nums[0], nums[1], 1),
            3 => (nums[0], nums[1], nums[2]),
            0 => return Err(self.error("range expected at least 1 argument, got 0", "TypeError")),
            n => return Err(self.error(format!("range expected at most 3 arguments, got {n}"), "TypeError")),
        };
        if step == 0 {
            return Err(self.error("range() arg 3 must not be zero", "ValueError"));
        }
        // A length past i64, `range(-2**63, 2**63)`, needs the big form.
        if range_len(start, stop, step) == i64::MAX {
            return self.big_range(args);
        }
        Ok(Value::Object(Rc::new(Object::Range(start, stop, step))))
    }

    /// `range(...)` with an argument past the i64 range.
    fn big_range(&self, args: &[Value]) -> R<Value> {
        let mut nums = Vec::new();
        for a in args {
            nums.push(to_big(a).ok_or_else(|| self.error(format!("'{}' object cannot be interpreted as an integer", a.type_name()), "TypeError"))?);
        }
        let one = num_bigint::BigInt::from(1);
        let (start, stop, step) = match nums.len() {
            1 => (num_bigint::BigInt::from(0), nums.remove(0), one),
            2 => (nums.remove(0), nums.remove(0), one),
            3 => (nums.remove(0), nums.remove(0), nums.remove(0)),
            n => return Err(self.error(format!("range expected at most 3 arguments, got {n}"), "TypeError")),
        };
        if num_traits::Zero::is_zero(&step) {
            return Err(self.error("range() arg 3 must not be zero", "ValueError"));
        }
        Ok(super::bigint::range_value(start, stop, step))
    }

    /// `dict()`, `dict(mapping)`, `dict(pairs)`, each with `key=value` keywords on top.
    fn dict_builtin(&mut self, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let mut d = DictMap::default();
        if let Some(first) = args.first() {
            let pairs = match first {
                Value::Dict(src) => src.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
                Value::Instance(i) if super::methods::is_mapping(i) => self.mapping_pairs(first)?,
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
        self.sort_values(&mut items, kwarg(kwargs, "key").filter(|k| !k.is_none()).cloned(), reverse)?;
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
            self.source_modules.insert(MODULE.to_string(), super::stdlib::ModuleSource::Packed(super::stdlib::BUILTINS_SOURCE));
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
                let errors = args.get(2).and_then(Value::as_str).unwrap_or("strict");
                match encode_with(s, enc, errors) {
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
            ByteError::Encode(message) => self.error(message, "UnicodeEncodeError"),
            ByteError::Errors(name) if name == "surrogateescape" => {
                self.error(format!("error handler '{name}' is not supported: strings here cannot hold lone surrogates"), "LookupError")
            }
            ByteError::Errors(name) => self.error(format!("unknown error handler name '{name}'"), "LookupError"),
        }
    }
}

/// [parse_int_literal] for an int of any size.
pub(crate) fn parse_int_value(text: &str, base: u32) -> Result<Option<Value>, super::bigint::Fail> {
    if let Some(i) = parse_int_literal(text, base) {
        return Ok(Some(Value::Int(i)));
    }
    let Some((negative, radix, clean)) = int_literal_digits(text, base) else { return Ok(None) };
    super::bigint::parse_digits(&clean, radix, negative)
}

/// An integer literal as `int(text, base)` reads it: a sign, `_` between
/// digits, and with base 0 (or the matching base) a `0x`, `0o` or `0b` prefix.
pub(crate) fn parse_int_literal(text: &str, base: u32) -> Option<i64> {
    let (negative, radix, clean) = int_literal_digits(text, base)?;
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

/// The sign, radix and bare digits of an integer literal, checked.
fn int_literal_digits(text: &str, base: u32) -> Option<(bool, u32, String)> {
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
    Some((negative, radix, clean))
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
