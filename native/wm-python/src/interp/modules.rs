//! Port of PyModules.kt: the standard namespaces a script can `import`: math,
//! random, time, json, re and sys. Each one only computes: `random` draws from
//! a seedable SplitMix64, `time` reads the host's clock, `re` spends the step
//! budget like any other loop. None can touch a file, the network or another
//! process.

use std::rc::Rc;

use super::datefmt::{civil, format_pattern};
use super::error::{Exc, PyError, R};
use super::eval::Interp;
use super::json;
use super::regex::{latin1_bytes, latin1_text, Regex, Subject, ASCII, BYTES, DOT_ALL, IGNORE_CASE, MULTILINE, UNICODE, VERBOSE};
use super::text::{char_is_number, graphemes, grapheme_count, java_hash, longer_than, parse_swift_int};
use super::value::*;

pub const NAMES: &[&str] = &["math", "random", "time", "json", "re", "sys"];

/// [NAMES] and the Rust half of the Python standard library modules
/// ([`super::stdlib`]), which a host turns on with
/// `Runtime::enable_python_stdlib`.
pub const ALL_NAMES: &[&str] = &["math", "random", "time", "json", "re", "sys", "_native", "_time", "cmath", "_mt"];

/// PyMini's version, and the line `sys.version` holds.
pub const VERSION: &str = "2.0.1";
pub const VERSION_LINE: &str = "pymini 2.0.1";

pub(crate) fn functions(module: &str) -> &'static [&'static str] {
    match module {
        "math" => super::mathmod::MATH_FUNCTIONS,
        "cmath" => super::mathmod::CMATH_FUNCTIONS,
        "_mt" => super::mt::FUNCTIONS,
        "random" => &["random", "randint", "randrange", "choice", "choices", "shuffle", "sample", "uniform", "seed", "getrandbits"],
        // `_time` is the same clock, under the `time` module written in Python.
        "time" | "_time" => &["time", "monotonic", "parts", "format", "sleep"],
        "json" => &["dumps", "loads", "dump", "load"],
        "re" => &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split", "escape", "compile"],
        "sys" => &[
            "getrecursionlimit",
            "setrecursionlimit",
            "audit",
            "addaudithook",
            "get_int_max_str_digits",
            "set_int_max_str_digits",
            "exit",
            "intern",
            "getdefaultencoding",
            "getfilesystemencoding",
            "exc_info",
            "_getframemodulename",
            "getsizeof",
            "_is_gil_enabled",
        ],
        "sys.stdout" | "sys.stderr" => &["write", "writelines", "flush", "isatty"],
        "sys.stdin" => &["read", "readline", "readlines", "isatty"],
        "_native" => super::stdlib::NATIVE_FUNCTIONS,
        _ => &[],
    }
}

pub(crate) fn constant_names(module: &str) -> &'static [&'static str] {
    match module {
        "math" => &["pi", "e", "tau", "inf", "nan"],
        "cmath" => &["pi", "e", "tau", "inf", "nan", "infj", "nanj"],
        "re" => &[
            "I", "IGNORECASE", "M", "MULTILINE", "S", "DOTALL", "X", "VERBOSE", "A", "ASCII", "U", "UNICODE", "NOFLAG", "Pattern", "Match",
            "error", "PatternError", "Scanner",
        ],
        "sys" => &[
            "version", "version_info", "hexversion", "maxsize", "maxunicode", "byteorder", "platform", "argv", "path",
            "modules", "stdout", "stderr", "stdin", "executable", "hash_info", "int_info", "float_info", "meta_path",
            "path_hooks", "path_importer_cache", "prefix", "base_prefix", "exec_prefix", "base_exec_prefix", "flags",
            "implementation",
        ],
        "json" => &["JSONEncoder", "JSONDecoder", "JSONDecodeError"],
        "sys.stdout" | "sys.stderr" | "sys.stdin" => &["encoding"],
        _ => &[],
    }
}

fn constant(module: &str, name: &str) -> Option<Value> {
    Some(match (module, name) {
        ("math" | "cmath", "pi") => Value::Float(std::f64::consts::PI),
        ("math" | "cmath", "e") => Value::Float(std::f64::consts::E),
        ("math" | "cmath", "tau") => Value::Float(std::f64::consts::TAU),
        ("math" | "cmath", "inf") => Value::Float(f64::INFINITY),
        ("math" | "cmath", "nan") => Value::Float(f64::NAN),
        ("cmath", "infj") => Value::Complex(0.0, f64::INFINITY),
        ("cmath", "nanj") => Value::Complex(0.0, f64::NAN),
        ("re", "I" | "IGNORECASE") => Value::Int(IGNORE_CASE),
        ("re", "M" | "MULTILINE") => Value::Int(MULTILINE),
        ("re", "S" | "DOTALL") => Value::Int(DOT_ALL),
        ("re", "X" | "VERBOSE") => Value::Int(VERBOSE),
        ("re", "A" | "ASCII") => Value::Int(ASCII),
        ("re", "U" | "UNICODE") => Value::Int(UNICODE),
        ("re", "NOFLAG") => Value::Int(0),
        ("re", "Pattern") => Value::Builtin(Rc::from("re.Pattern")),
        ("re", "Match") => Value::Builtin(Rc::from("re.Match")),
        // A bad pattern raises ValueError here, which re.error stands for.
        ("re", "error" | "PatternError") => Value::Builtin(Rc::from("ValueError")),
        ("sys", "version") => Value::str(VERSION_LINE),
        ("sys", "hexversion") => Value::Int(0x030D_00F0),
        ("sys", "maxsize") => Value::Int(i64::MAX),
        ("sys", "maxunicode") => Value::Int(0x10_FFFF),
        ("sys", "byteorder") => Value::str("little"),
        // Where plugins run: the keyboard, on Android.
        ("sys", "platform") => Value::str("android"),
        ("sys", "executable") => Value::str(""),
        // There is no installation on disk: no prefix to find files under.
        ("sys", "prefix" | "base_prefix" | "exec_prefix" | "base_exec_prefix") => Value::str(""),
        ("sys", "stdin") => Value::Module(Rc::from("sys.stdin")),
        ("sys.stdout" | "sys.stderr" | "sys.stdin", "encoding") => Value::str("utf-8"),
        _ => return None,
    })
}

fn name_of_json_member(name: &str) -> &'static str {
    match name {
        "JSONEncoder" => "JSONEncoder",
        "JSONDecoder" => "JSONDecoder",
        _ => "JSONDecodeError",
    }
}

/// Members of a native module written in Python, in _builtins.py: `re.Scanner`, json's classes.
pub(crate) fn python_member(module: &str, name: &str) -> Option<&'static str> {
    match (module, name) {
        ("re", "Scanner") => Some("_re_Scanner"),
        ("json", "JSONEncoder" | "JSONDecoder" | "JSONDecodeError") => Some(name_of_json_member(name)),
        _ => None,
    }
}

/// Resolve `module.name`, or None when the namespace has no such member.
pub(crate) fn member(module: &str, name: &str) -> Option<Value> {
    if let Some(c) = constant(module, name) {
        return Some(c);
    }
    if !functions(module).contains(&name) {
        return None;
    }
    Some(Value::ModuleFunction(Rc::new((Rc::from(module), Rc::from(name)))))
}

/// Every exported name of a standard module, sorted.
pub fn exports(module: &str) -> Vec<&'static str> {
    let mut out: Vec<&'static str> = functions(module).iter().chain(constant_names(module)).copied().collect();
    out.sort();
    out
}

/// Where `time` gets its answers. The default reads the system clock and
/// treats local time as UTC; a host passes its own to get the user's zone, or
/// a fixed clock for tests. On wasm the front end must supply one.
pub trait Clock {
    /// Seconds since the Unix epoch.
    fn now(&self) -> f64;
    /// Seconds on a clock that never goes back.
    fn monotonic(&self) -> f64;
    /// The local zone's offset from UTC, in seconds, at the given instant.
    fn utc_offset(&self, _unix_seconds: i64) -> i32 {
        0
    }
}

/// The system clock, with a fixed UTC offset (0 by default).
#[derive(Clone, Copy, Debug, Default)]
pub struct SystemClock {
    pub offset_seconds: i32,
}

impl Clock for SystemClock {
    fn now(&self) -> f64 {
        #[cfg(not(target_arch = "wasm32"))]
        {
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
        }
        #[cfg(target_arch = "wasm32")]
        {
            0.0
        }
    }

    fn monotonic(&self) -> f64 {
        #[cfg(not(target_arch = "wasm32"))]
        {
            use std::sync::OnceLock;
            static START: OnceLock<std::time::Instant> = OnceLock::new();
            START.get_or_init(std::time::Instant::now).elapsed().as_secs_f64()
        }
        #[cfg(target_arch = "wasm32")]
        {
            0.0
        }
    }

    fn utc_offset(&self, _unix_seconds: i64) -> i32 {
        self.offset_seconds
    }
}

/// A fresh, non-zero random seed.
pub(crate) fn random_seed() -> u64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    loop {
        let mut h = RandomState::new().build_hasher();
        h.write_u64(0x9E37_79B9_7F4A_7C15);
        let s = h.finish();
        if s != 0 {
            return s;
        }
    }
}

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

/// The decoder's hooks from `json.loads` keywords: one left None, or the
/// built-in it stands in for (`parse_float=float`), is read natively.
pub(crate) fn json_decode_options(kwargs: &[(Rc<str>, Value)]) -> json::DecodeOptions {
    let hook = |key: &str, builtin: &str| {
        kwarg(kwargs, key).filter(|v| !v.is_none() && !matches!(v, Value::Builtin(b) if b.as_ref() == builtin)).cloned()
    };
    json::DecodeOptions {
        object_hook: hook("object_hook", ""),
        object_pairs_hook: hook("object_pairs_hook", ""),
        parse_float: hook("parse_float", "float"),
        parse_int: hook("parse_int", "int"),
        parse_constant: hook("parse_constant", ""),
        strict: kwarg(kwargs, "strict").is_none_or(Value::truthy),
    }
}

/// What the JSON encoder and decoder ask of the interpreter: steps, and calls to hooks.
struct JsonHost<'a>(&'a mut Interp);

impl json::Host for JsonHost<'_> {
    fn charge(&mut self) -> Result<(), PyError> {
        self.0.tick()
    }

    fn call(&mut self, f: &Value, args: Vec<Value>) -> Result<Value, PyError> {
        self.0.call_value(f, args, Vec::new()).map_err(|e| match e {
            Exc::Error(e) => *e,
            _ => PyError::new("a JSON hook returned out of a loop", "RuntimeError"),
        })
    }
}

/// What a compiled pattern answers to, as `pattern.search(text)`.
pub(crate) const PATTERN_METHODS: &[&str] = &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split"];

impl Interp {
    #[inline(never)]
    /// The members of `sys` that are worked out each time they are read.
    pub(crate) fn sys_member(&self, name: &str) -> Option<Value> {
        Some(match name {
            "version_info" => Value::Tuple(self.version_info.clone()),
            "hash_info" | "int_info" | "float_info" | "flags" | "implementation" => Value::Tuple(self.struct_seqs.iter().find(|(n, _, _)| *n == name)?.2.clone()),
            "stdout" => self.stdout_target.clone().unwrap_or_else(|| Value::Module(Rc::from("sys.stdout"))),
            "stderr" => self.stderr_target.clone().unwrap_or_else(|| Value::Module(Rc::from("sys.stderr"))),
            // Containers a script changes in place: the same object each read.
            "argv" | "path" | "meta_path" | "path_hooks" | "path_importer_cache" | "modules" => {
                let key: &'static str = match name {
                    "argv" => "argv",
                    "path" => "path",
                    "meta_path" => "meta_path",
                    "path_hooks" => "path_hooks",
                    "path_importer_cache" => "path_importer_cache",
                    _ => "modules",
                };
                let mut values = self.sys_values.borrow_mut();
                values
                    .entry(key)
                    .or_insert_with(|| match key {
                        "argv" => Value::list(vec![Value::str("")]),
                        "path_importer_cache" => Value::dict::<&str>(Vec::new()),
                        "modules" => {
                            let mut names: Vec<&str> = self.module_envs.keys().map(String::as_str).collect();
                            names.push("sys");
                            names.sort_unstable();
                            let mut pairs: Vec<(&str, Value)> = names.into_iter().map(|n| (n, Value::Module(Rc::from(n)))).collect();
                            pairs.extend(self.object_modules.iter().map(|(k, v)| (k.as_str(), v.clone())));
                            Value::dict(pairs)
                        }
                        _ => Value::list(Vec::new()),
                    })
                    .clone()
            }
            _ => return None,
        })
    }

    fn sys_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        match name {
            "getrecursionlimit" => Ok(Value::Int(self.recursion_limit as i64)),
            // Nothing listens: a plugin has no audit hooks to run.
            "audit" | "addaudithook" => Ok(Value::None),
            "get_int_max_str_digits" => Ok(Value::Int(self.int_max_str_digits as i64)),
            "set_int_max_str_digits" => {
                let v = args.first().or_else(|| kwarg(kwargs, "maxdigits")).ok_or_else(|| {
                    self.error("set_int_max_str_digits() missing required argument 'maxdigits' (pos 1)", "TypeError")
                })?;
                let Some(n) = v.as_int() else {
                    return Err(self.error(format!("'{}' object cannot be interpreted as an integer", v.type_name()), "TypeError"));
                };
                if n != 0 && n < 640 {
                    return Err(self.error("maxdigits must be 0 or larger than 640", "ValueError"));
                }
                self.int_max_str_digits = n as usize;
                super::bigint::swap_str_digits(n as usize);
                Ok(Value::None)
            }
            "setrecursionlimit" => {
                let Some(n) = args.first().and_then(Value::as_int) else {
                    return Err(self.error("setrecursionlimit() needs an int", "TypeError"));
                };
                if n < 1 {
                    return Err(self.error("recursion limit must be greater or equal than 1", "ValueError"));
                }
                // The native stack still has the last word on how deep calls go.
                self.recursion_limit = n.min(100_000) as u32;
                Ok(Value::None)
            }
            "exit" => {
                let code = args.first().cloned().unwrap_or(Value::None);
                let message = match &code {
                    Value::None => String::new(),
                    other => py_str(other),
                };
                let args = if matches!(code, Value::None) { Vec::new() } else { vec![code] };
                let ex = ExceptionValue { kind: "SystemExit".into(), message, args, cause: None, context: None };
                Err(self.raise_exception(Rc::new(ex), None))
            }
            "intern" => args.first().cloned().ok_or_else(|| self.error("intern() needs a string", "TypeError")),
            "getdefaultencoding" | "getfilesystemencoding" => Ok(Value::str("utf-8")),
            "_is_gil_enabled" => Ok(Value::Bool(true)),
            "getsizeof" => {
                let Some(v) = args.first() else {
                    return Err(self.error("getsizeof() missing required argument 'object' (pos 1)", "TypeError"));
                };
                if let Value::Instance(_) = v {
                    if let Some(n) = self.dunder(v, "__sizeof__", Vec::new())? {
                        let Some(n) = n.as_int() else {
                            return Err(self.error("an integer is required", "TypeError"));
                        };
                        return Ok(Value::Int(n + 16));
                    }
                }
                Ok(Value::Int(size_of_value(v)))
            }
            "_getframemodulename" => {
                let depth = match (args.first(), kwargs.first()) {
                    (Some(v), _) | (None, Some((_, v))) => v.as_int().unwrap_or(0),
                    _ => 0,
                };
                Ok(self.frame_module_name(depth.max(0) as usize).map(Value::str).unwrap_or(Value::None))
            }
            "exc_info" => {
                let value = match (&self.active_instance, &self.active_exception) {
                    (Some(i), _) => i.clone(),
                    (None, Some(e)) => Value::Exception(e.clone()),
                    _ => return Ok(Value::tuple(vec![Value::None, Value::None, Value::None])),
                };
                Ok(Value::tuple(vec![self.type_of(&value), value, Value::None]))
            }
            _ => Err(self.error(format!("module 'sys' has no attribute '{name}'"), "AttributeError")),
        }
    }

    /// `sys.stdout.write(text)` and the like. Both output streams go where
    /// `print` does, a line at a time; there is no input to read.
    fn stream_call(&mut self, stream: &str, name: &str, args: &[Value]) -> R<Value> {
        match (stream, name) {
            ("stdout" | "stderr", "write") => {
                let Some(Value::Str(s)) = args.first() else {
                    return Err(self.error("write() argument must be str", "TypeError"));
                };
                self.write_out(s)?;
                Ok(Value::Int(grapheme_count(s) as i64))
            }
            ("stdout" | "stderr", "writelines") => {
                let lines = match args.first() {
                    Some(v) => self.iterate(v)?,
                    None => Vec::new(),
                };
                for l in lines {
                    let Value::Str(s) = l else {
                        return Err(self.error("writelines() argument must be a sequence of strings", "TypeError"));
                    };
                    self.write_out(&s)?;
                }
                Ok(Value::None)
            }
            (_, "flush") => Ok(Value::None),
            (_, "isatty") => Ok(Value::Bool(false)),
            ("stdin", "read" | "readline") => Ok(Value::str("")),
            ("stdin", "readlines") => Ok(Value::list(Vec::new())),
            _ => Err(self.error(format!("'sys.{stream}' has no attribute '{name}'"), "AttributeError")),
        }
    }

    /// Text onto the output, each finished line handed on as `print` hands them.
    pub(crate) fn write_out(&mut self, text: &str) -> R<()> {
        let mut buf = std::mem::take(&mut self.print_partial);
        buf.push_str(text);
        if let Some(last) = buf.rfind('\n') {
            let rest = buf.split_off(last + 1);
            buf.pop();
            for line in buf.split('\n') {
                self.emit(line.to_string());
            }
            buf = rest;
        }
        if longer_than(&buf, self.max_string) {
            return Err(self.string_abort());
        }
        self.print_partial = buf;
        Ok(())
    }

    pub(crate) fn call_module(&mut self, module: &str, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        self.tick()?;
        if let Some(f) = self.module_override(module, name) {
            return self.call_value(&f, args, kwargs);
        }
        if let Some(builtin) = python_member(module, name) {
            let f = self.python_builtin(builtin)?;
            return self.call_value(&f, args, kwargs);
        }
        if let Some(host) = self.host_modules.get(module).cloned() {
            if let Some(f) = host.functions.get(name).cloned() {
                return self.call_host(&f, args, kwargs);
            }
            if let Some(c) = host.constants.get(name).cloned() {
                return self.call_value(&c, args, kwargs);
            }
            return Err(self.error(format!("module '{module}' has no function '{name}'"), "AttributeError"));
        }
        if self.module_envs.contains_key(module) {
            let f = match self.source_member(module, name) {
                Some(f) => f,
                None => self
                    .module_getattr(module, name)?
                    .ok_or_else(|| self.error(format!("module '{module}' has no attribute '{name}'"), "AttributeError"))?,
            };
            return self.call_value(&f, args, kwargs);
        }
        if let Some(stream) = module.strip_prefix("sys.") {
            if self.enabled_modules.contains(&"sys") {
                return self.stream_call(stream, name, &args);
            }
        }
        if !self.enabled_modules.contains(&module) {
            return Err(self.error(format!("no module named '{module}'"), "ImportError"));
        }
        // Native code reads a subclass of a built-in type as its value.
        let args = plain_args(args);
        let kwargs: Vec<(Rc<str>, Value)> = kwargs.into_iter().map(|(k, v)| (k, plain_value(v))).collect();
        match module {
            "sys" => self.sys_call(name, &args, &kwargs),
            "math" if args.iter().any(|a| matches!(a, Value::Instance(_))) => self.math_with_instances(name, args, kwargs),
            "math" => self.math_call(name, &args, &kwargs),
            "cmath" => self.cmath_call(name, &args, &kwargs),
            "_mt" => self.mt_call(name, &args),
            "random" => self.random_call(name, &args),
            "time" | "_time" => self.time_call(name, &args),
            "json" => self.json_call(name, &args, &kwargs),
            "re" => self.regex_call(name, &args, &kwargs),
            "_native" => self.native_call(name, &args),
            _ => Err(self.error(format!("no module named '{module}'"), "ImportError")),
        }
    }

    // MARK: - random

    /// SplitMix64: small, fast, and reproducible from a seed.
    fn next_random_bits(&mut self) -> u64 {
        self.random_state = self.random_state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.random_state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn next_unit(&mut self) -> f64 {
        (self.next_random_bits() >> 11) as f64 * (1.0 / 9_007_199_254_740_992.0)
    }

    fn random_int(&mut self, below: i64) -> i64 {
        if below <= 0 {
            return 0;
        }
        (self.next_random_bits() % below as u64) as i64
    }

    /// [k] random bits as a non-negative int.
    fn random_bits(&mut self, k: u64) -> num_bigint::BigInt {
        let k = k as usize;
        let mut bytes = Vec::with_capacity(k.div_ceil(8) + 8);
        while bytes.len() * 8 < k {
            bytes.extend_from_slice(&self.next_random_bits().to_le_bytes());
        }
        bytes.truncate(k.div_ceil(8));
        if !k.is_multiple_of(8) {
            if let Some(last) = bytes.last_mut() {
                *last &= (1u8 << (k % 8)) - 1;
            }
        }
        num_bigint::BigInt::from_bytes_le(num_bigint::Sign::Plus, &bytes)
    }

    /// A random int in `0..n` for a positive [n] of any size.
    fn random_below(&mut self, n: &num_bigint::BigInt) -> num_bigint::BigInt {
        use num_traits::ToPrimitive;
        if let Some(small) = n.to_i64() {
            return num_bigint::BigInt::from(self.random_int(small));
        }
        // As CPython's _randbelow: draw as many bits as n has until one falls under it.
        let k = n.bits();
        loop {
            let r = self.random_bits(k);
            if &r < n {
                return r;
            }
        }
    }

    /// `randrange(start, stop, step)` for ints of any size; [shown] is how
    /// the arguments read in the error for an empty range.
    fn random_range(&mut self, start: &num_bigint::BigInt, stop: &num_bigint::BigInt, step: &num_bigint::BigInt, shown: Option<String>) -> R<Value> {
        use num_integer::Integer;
        use num_traits::{Signed, Zero};
        if step.is_zero() {
            return Err(self.error("zero step for randrange()", "ValueError"));
        }
        let width = stop - start;
        let one = num_bigint::BigInt::from(1);
        let n = if step.is_positive() { (width + step - &one).div_floor(step) } else { (width + step + &one).div_floor(step) };
        if !n.is_positive() {
            return Err(self.error(
                match shown {
                    Some(args) => format!("empty range in randrange({args})"),
                    None => "empty range for randrange()".to_string(),
                },
                "ValueError",
            ));
        }
        let r = self.random_below(&n);
        Ok(super::bigint::int_value(start + step * r))
    }

    #[inline(never)]
    fn random_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "random" => Ok(Value::Float(self.next_unit())),
            // `getrandbits(k)`: a k-bit int, of any size.
            "getrandbits" => {
                let k = args.first().and_then(Value::as_int).ok_or_else(|| self.error("getrandbits() needs an int", "TypeError"))?;
                if k < 0 {
                    return Err(self.error("number of bits must be non-negative", "ValueError"));
                }
                if k as u64 > super::bigint::MAX_BITS {
                    return Err(self.error("getrandbits() past 2**20 bits", "OverflowError"));
                }
                let r = self.random_bits(k as u64);
                Ok(super::bigint::int_value(r))
            }
            "seed" => {
                match args.first() {
                    None => self.random_state = random_seed(),
                    Some(v) => {
                        self.random_state = match v.as_int() {
                            Some(i) => (i | 1) as u64,
                            None => ((java_hash(&py_str(v)) as i64).wrapping_abs() | 1) as u64,
                        }
                    }
                }
                Ok(Value::None)
            }
            "uniform" => {
                let (Some(a), Some(b)) = (args.first().and_then(Value::as_float), args.get(1).and_then(Value::as_float)) else {
                    return Err(self.error("random.uniform() needs two numbers", "TypeError"));
                };
                Ok(Value::Float(a + (b - a) * self.next_unit()))
            }
            "randint" | "randrange" => {
                // Machine-word ranges, the usual kind, with no big-int arithmetic.
                let small: Option<Vec<i64>> = args.iter().map(|a| if let Value::Int(i) = a { Some(*i) } else { None }).collect();
                let quick = match (name, small.as_deref()) {
                    ("randint", Some(&[a, b])) => b.checked_sub(a).and_then(|w| w.checked_add(1)).filter(|n| *n > 0).map(|n| (a, n, 1)),
                    ("randrange", Some(&[stop])) => (stop > 0).then_some((0, stop, 1)),
                    ("randrange", Some(&[start, stop])) => stop.checked_sub(start).filter(|n| *n > 0).map(|n| (start, n, 1)),
                    ("randrange", Some(&[start, stop, step])) if step > 0 => {
                        stop.checked_sub(start).and_then(|w| w.checked_add(step - 1)).map(|w| w / step).filter(|n| *n > 0).map(|n| (start, n, step))
                    }
                    _ => None,
                };
                if let Some((start, n, step)) = quick {
                    let r = self.random_int(n);
                    if let Some(v) = r.checked_mul(step).and_then(|d| start.checked_add(d)) {
                        return Ok(Value::Int(v));
                    }
                }
                let mut ints = Vec::with_capacity(args.len());
                for a in args {
                    match super::bigint::to_big(a) {
                        Some(i) => ints.push(i),
                        None => return Err(self.error(format!("'{}' object cannot be interpreted as an integer", a.type_name()), "TypeError")),
                    }
                }
                let one = num_bigint::BigInt::from(1);
                if name == "randint" {
                    let [a, b] = &ints[..] else {
                        return Err(self.error(format!("Random.randint() takes 3 positional arguments but {} were given", args.len() + 1), "TypeError"));
                    };
                    let stop = b + &one;
                    return self.random_range(a, &stop, &one, Some(format!("{a}, {stop}")));
                }
                match &ints[..] {
                    [stop] => self.random_range(&num_bigint::BigInt::from(0), stop, &one, None),
                    [start, stop] => self.random_range(start, stop, &one, Some(format!("{start}, {stop}"))),
                    [start, stop, step] => self.random_range(start, stop, step, Some(format!("{start}, {stop}, {step}"))),
                    _ => Err(self.error(format!("Random.randrange() takes from 2 to 4 positional arguments but {} were given", args.len() + 1), "TypeError")),
                }
            }
            "choice" => {
                let items = match args.first() {
                    Some(a) => self.iterate(a)?,
                    None => Vec::new(),
                };
                if items.is_empty() {
                    return Err(self.error("cannot choose from an empty sequence", "IndexError"));
                }
                let i = self.random_int(items.len() as i64) as usize;
                Ok(items[i].clone())
            }
            "choices" => {
                let items = match args.first() {
                    Some(a) => self.iterate(a)?,
                    None => Vec::new(),
                };
                let k = if args.len() > 1 { args[1].as_int().unwrap_or(1) } else { 1 };
                if items.is_empty() {
                    return Err(self.error("cannot choose from an empty sequence", "IndexError"));
                }
                if k < 0 || k as usize > self.max_container {
                    return Err(self.container_abort("list"));
                }
                let mut out = Vec::new();
                for _ in 0..k {
                    self.tick()?;
                    let i = self.random_int(items.len() as i64) as usize;
                    out.push(items[i].clone());
                }
                Ok(Value::list(out))
            }
            "sample" => {
                let mut items = match args.first() {
                    Some(a) => self.iterate(a)?,
                    None => Vec::new(),
                };
                let k = if args.len() > 1 { args[1].as_int().unwrap_or(0) } else { 0 };
                if k < 0 || k as usize > items.len() {
                    return Err(self.error("sample larger than population", "ValueError"));
                }
                let mut out = Vec::new();
                for _ in 0..k {
                    self.tick()?;
                    let i = self.random_int(items.len() as i64) as usize;
                    out.push(items.remove(i));
                }
                Ok(Value::list(out))
            }
            "shuffle" => {
                let Some(Value::List(l)) = args.first() else {
                    return Err(self.error("random.shuffle() needs a list", "TypeError"));
                };
                let n = l.borrow().len();
                if n <= 1 {
                    return Ok(Value::None);
                }
                for i in (1..n).rev() {
                    self.tick()?;
                    let j = self.random_int(i as i64 + 1) as usize;
                    l.borrow_mut().swap(i, j);
                }
                Ok(Value::None)
            }
            _ => Err(self.error(format!("module 'random' has no attribute '{name}'"), "AttributeError")),
        }
    }

    // MARK: - time

    /// Milliseconds since the epoch for argument [i], or now.
    fn date_arg(&self, args: &[Value], i: usize) -> i64 {
        let seconds = args.get(i).and_then(Value::as_float).unwrap_or_else(|| self.clock.now());
        let ms = seconds * 1000.0;
        if ms.is_nan() {
            0
        } else {
            ms as i64
        }
    }

    #[inline(never)]
    fn time_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "time" => Ok(Value::Float((self.clock.now() * 1000.0).trunc() / 1000.0)),
            "monotonic" => Ok(Value::Float(self.clock.monotonic())),
            // The keyboard must keep answering keys: sleep(0), which waits for
            // nothing, returns, and a plugin waits with plugin.after() instead.
            "sleep" => {
                let secs = match args.first() {
                    Some(Value::Int(n)) => *n as f64,
                    Some(Value::Bool(b)) => *b as i64 as f64,
                    Some(Value::Float(f)) => *f,
                    Some(other) => return Err(self.error(format!("'{}' object cannot be interpreted as an integer or float", other.type_name()), "TypeError")),
                    None => return Err(self.error("sleep() takes exactly one argument (0 given)", "TypeError")),
                };
                if secs.is_nan() {
                    return Err(self.error("Invalid value NaN (not a number)", "ValueError"));
                }
                if secs < 0.0 {
                    return Err(self.error("sleep length must be non-negative", "ValueError"));
                }
                if secs > 0.0 {
                    return Err(self.error(
                        "time.sleep() can't wait in a plugin, as the keyboard would stop answering keys; call plugin.after(seconds, fn) instead",
                        "RuntimeError",
                    ));
                }
                Ok(Value::None)
            }
            "parts" => {
                let ms = self.date_arg(args, 0);
                let c = civil(ms, self.clock.as_ref());
                Ok(Value::dict(vec![
                    ("year", Value::Int(c.year)),
                    ("month", Value::Int(c.month as i64)),
                    ("day", Value::Int(c.day as i64)),
                    ("hour", Value::Int(c.hour as i64)),
                    ("minute", Value::Int(c.minute as i64)),
                    ("second", Value::Int(c.second as i64)),
                    // Monday is 0, as in Python's `weekday()`.
                    ("weekday", Value::Int(c.weekday as i64)),
                    ("yearday", Value::Int(c.yearday as i64)),
                ]))
            }
            "format" => {
                // A Unicode date pattern ("EEE d MMM yyyy"), not a C strftime string.
                let Some(Value::Str(pattern)) = args.first() else {
                    return Err(self.error("time.format() needs a pattern like \"yyyy-MM-dd\"", "TypeError"));
                };
                let ms = self.date_arg(args, 1);
                let c = civil(ms, self.clock.as_ref());
                match format_pattern(pattern, &c) {
                    Ok(s) => Ok(Value::str(s)),
                    Err(m) => Err(self.error(format!("invalid date pattern: {m}"), "ValueError")),
                }
            }
            _ => Err(self.error(format!("module 'time' has no attribute '{name}'"), "AttributeError")),
        }
    }

    // MARK: - json

    #[inline(never)]
    fn json_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        match name {
            // The file forms: what the file-like object's read() gives, and
            // the text written with its write().
            "load" => {
                let fp = args.first().ok_or_else(|| self.error("load() missing 1 required positional argument: 'fp'", "TypeError"))?;
                let read = self.attribute(fp, "read")?;
                let text = self.call_value(&read, Vec::new(), Vec::new())?;
                self.json_call("loads", &[text], kwargs)
            }
            "dump" => {
                let (Some(v), Some(fp)) = (args.first(), args.get(1)) else {
                    return Err(self.error("dump() missing 2 required positional arguments: 'obj' and 'fp'", "TypeError"));
                };
                let text = self.json_call("dumps", std::slice::from_ref(v), kwargs)?;
                let write = self.attribute(fp, "write")?;
                self.call_value(&write, vec![text], Vec::new())?;
                Ok(Value::None)
            }
            "dumps" => {
                let v = args.first().ok_or_else(|| self.error("json.dumps() needs a value", "TypeError"))?;
                // Not CPython's: the indent may come second, as PyMini took it.
                let indent = args.get(1).or_else(|| kwarg(kwargs, "indent"));
                const OWN: &[&str] = &["skipkeys", "ensure_ascii", "check_circular", "allow_nan", "cls", "indent", "separators", "default", "sort_keys"];
                let cls = kwarg(kwargs, "cls").filter(|c| !c.is_none()).cloned();
                if cls.is_none() && kwargs.iter().all(|(k, _)| OWN.contains(&k.as_ref())) {
                    return self.json_encode(v, indent, kwargs);
                }
                // A JSONEncoder subclass, or keywords for one: made with every
                // option, as CPython's dumps makes it, and asked to encode.
                let cls = match cls {
                    Some(c) => c,
                    None => self.python_builtin("JSONEncoder")?,
                };
                let option = |key: &str, default: Value| kwarg(kwargs, key).cloned().unwrap_or(default);
                let mut kw: Vec<(Rc<str>, Value)> = vec![
                    (Rc::from("skipkeys"), option("skipkeys", Value::Bool(false))),
                    (Rc::from("ensure_ascii"), option("ensure_ascii", Value::Bool(false))),
                    (Rc::from("check_circular"), option("check_circular", Value::Bool(true))),
                    (Rc::from("allow_nan"), option("allow_nan", Value::Bool(true))),
                    (Rc::from("indent"), indent.cloned().unwrap_or(Value::None)),
                    (Rc::from("separators"), option("separators", Value::None)),
                    (Rc::from("default"), option("default", Value::None)),
                    (Rc::from("sort_keys"), option("sort_keys", Value::Bool(false))),
                ];
                kw.extend(kwargs.iter().filter(|(k, _)| !OWN.contains(&k.as_ref())).cloned());
                let encoder = self.call_value(&cls, Vec::new(), kw)?;
                let encode = self.attribute(&encoder, "encode")?;
                self.call_value(&encode, vec![v.clone()], Vec::new())
            }
            "loads" => {
                let s = args.first().ok_or_else(|| self.error("loads() missing 1 required positional argument: 's'", "TypeError"))?;
                let text = self.json_text(s)?;
                const OWN: &[&str] = &["cls", "object_hook", "parse_float", "parse_int", "parse_constant", "object_pairs_hook"];
                let cls = kwarg(kwargs, "cls").filter(|c| !c.is_none()).cloned();
                if cls.is_none() && kwargs.iter().all(|(k, _)| OWN.contains(&k.as_ref())) {
                    let options = json_decode_options(kwargs);
                    return self.json_decode(&text, None, &options);
                }
                // A JSONDecoder subclass, or keywords for one: given the hooks
                // that were set, as CPython's loads does, and asked to decode.
                let cls = match cls {
                    Some(c) => c,
                    None => self.python_builtin("JSONDecoder")?,
                };
                let kw: Vec<(Rc<str>, Value)> = kwargs.iter().filter(|(k, v)| k.as_ref() != "cls" && !(OWN.contains(&k.as_ref()) && v.is_none())).cloned().collect();
                let decoder = self.call_value(&cls, Vec::new(), kw)?;
                let decode = self.attribute(&decoder, "decode")?;
                self.call_value(&decode, vec![Value::Str(text)], Vec::new())
            }
            _ => Err(self.error(format!("module 'json' has no attribute '{name}'"), "AttributeError")),
        }
    }

    /// `json.dumps` without a class: [kwargs] are JSONEncoder's options.
    pub(crate) fn json_encode(&mut self, v: &Value, indent: Option<&Value>, kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let indent = match indent {
            None | Some(Value::None) => None,
            Some(Value::Str(s)) => Some(s.to_string()),
            Some(other) => match other.as_int() {
                Some(n) => Some(" ".repeat(n.clamp(0, 64) as usize)),
                None => return Err(self.error("indent must be a number or a string", "TypeError")),
            },
        };
        let flag = |key: &str, default: bool| kwarg(kwargs, key).map_or(default, Value::truthy);
        let mut options = json::EncodeOptions::new(indent);
        options.sort_keys = flag("sort_keys", false);
        // Off unless asked: the keyboard's text is rarely ASCII, and JSON reads it either way.
        options.ensure_ascii = flag("ensure_ascii", false);
        options.skipkeys = flag("skipkeys", false);
        options.allow_nan = flag("allow_nan", true);
        options.check_circular = flag("check_circular", true);
        options.default = kwarg(kwargs, "default").filter(|d| !d.is_none()).cloned();
        if let Some(sep) = kwarg(kwargs, "separators").filter(|v| !v.is_none()) {
            match sequence_items(sep).as_deref() {
                Some([Value::Str(item), Value::Str(key)]) => {
                    options.item_separator = item.to_string();
                    options.key_separator = key.to_string();
                }
                _ => return Err(self.error("separators must be an (item, key) pair of strings", "TypeError")),
            }
        }
        let limit = self.max_string;
        let s = json::encode(v, &options, limit, &mut JsonHost(self))?;
        Ok(Value::str(s))
    }

    /// The text `json.loads` reads: a str, or bytes in the UTF its first
    /// bytes show, as CPython's json.detect_encoding tells them apart.
    fn json_text(&mut self, s: &Value) -> R<Rc<str>> {
        match s {
            Value::Str(s) => {
                if s.starts_with('\u{feff}') {
                    return Err(self.json_decode_error("Unexpected UTF-8 BOM (decode using utf-8-sig)", s, 0));
                }
                Ok(s.clone())
            }
            Value::Bytes(b) => {
                let encoding = match b.as_ref() {
                    [0, 0, 0xFE, 0xFF, ..] | [0xFF, 0xFE, 0, 0, ..] => "utf-32",
                    [0xFE, 0xFF, ..] | [0xFF, 0xFE, ..] => "utf-16",
                    [0xEF, 0xBB, 0xBF, ..] => "utf-8-sig",
                    [0, 0, _, _, ..] => "utf-32-be",
                    [0, _, _, _, ..] | [0, _] => "utf-16-be",
                    [_, 0, 0, 0, ..] => "utf-32-le",
                    [_, 0, _, _, ..] | [_, 0] => "utf-16-le",
                    _ => "utf-8",
                };
                match super::bytes::decode(b, encoding, "strict") {
                    Ok(text) => Ok(Rc::from(text)),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            other => Err(self.error(format!("the JSON object must be str, bytes or bytearray, not {}", other.type_name()), "TypeError")),
        }
    }

    /// Decode [text]: all of it (JSONDecoder.decode) when [at] is None, else
    /// one value from that character on, as `(value, end)` (raw_decode).
    pub(crate) fn json_decode(&mut self, text: &Rc<str>, at: Option<i64>, options: &json::DecodeOptions) -> R<Value> {
        let result = match at {
            None => json::decode(text, options, &mut JsonHost(self)).map(|v| (v, None)),
            Some(at) => {
                if at < 0 {
                    return Err(self.error("idx cannot be negative", "ValueError"));
                }
                // Indexes count characters as str's do, by grapheme cluster.
                let b = self.chars(text);
                let Some(&start) = b.get(at as usize) else {
                    return Err(self.json_decode_error_at("Expecting value", text, at));
                };
                json::decode_at(text, start as usize, options, &mut JsonHost(self)).map(|(v, end)| (v, Some(end)))
            }
        };
        match result {
            Ok((v, None)) => Ok(v),
            Ok((v, Some(end))) => Ok(Value::tuple(vec![v, Value::Int(grapheme_count(&text[..end]) as i64)])),
            Err(json::DecodeError::At(message, byte)) => Err(self.json_decode_error(message, text, byte)),
            Err(json::DecodeError::Other(e)) => Err(e.into()),
        }
    }

    fn json_decode_error(&mut self, message: &str, doc: &Rc<str>, byte: usize) -> Exc {
        let pos = grapheme_count(doc.get(..byte).unwrap_or(doc));
        self.json_decode_error_at(message, doc, pos as i64)
    }

    /// A json.JSONDecodeError (a ValueError) for [message] at character [pos] of [doc].
    fn json_decode_error_at(&mut self, message: &str, doc: &Rc<str>, pos: i64) -> Exc {
        let class = match self.python_builtin("JSONDecodeError") {
            Ok(c) => c,
            Err(e) => return e,
        };
        match self.call_value(&class, vec![Value::str(message), Value::Str(doc.clone()), Value::Int(pos)], Vec::new()) {
            Ok(Value::Instance(inst)) => self.raise_instance(&inst),
            Ok(_) => self.error(message, "ValueError"),
            Err(e) => e,
        }
    }

    // MARK: - re

    #[inline(never)]
    fn regex_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        if name == "escape" {
            return match args.first() {
                Some(Value::Str(s)) => Ok(Value::str(Regex::escape(s))),
                Some(Value::Bytes(b)) => Ok(Value::bytes(&latin1_bytes(&Regex::escape(&latin1_text(b))))),
                _ => Err(self.error("re.escape() needs a string", "TypeError")),
            };
        }
        // A pattern string, or a compiled pattern with its own flags.
        let (pattern, mut flags) = match args.first() {
            Some(Value::Str(p)) => (p.clone(), 0),
            // A bytes pattern matches bytes, with ASCII's classes.
            Some(Value::Bytes(p)) => (Rc::from(latin1_text(p)), BYTES),
            Some(Value::Object(o)) => match o.as_ref() {
                Object::Pattern { pattern, flags } => (pattern.clone(), *flags),
                _ => return Err(self.error(format!("re.{name}() needs a pattern string"), "TypeError")),
            },
            _ => return Err(self.error(format!("re.{name}() needs a pattern string"), "TypeError")),
        };
        // `sub`/`subn` take (pattern, repl, string, count, flags), `split`
        // (pattern, string, maxsplit, flags), `compile` (pattern, flags) and
        // the rest (pattern, string, flags).
        let flag_index = match name {
            "sub" | "subn" => 4,
            "split" => 3,
            "compile" => 1,
            _ => 2,
        };
        if let Some(f) = args.get(flag_index).or_else(|| kwarg(kwargs, "flags")).and_then(Value::as_int) {
            if flags & BYTES != 0 && f & UNICODE != 0 {
                return Err(self.error("cannot use UNICODE flag with a bytes pattern", "ValueError"));
            }
            flags |= f;
        }
        if name == "compile" {
            // Compiled now, so a bad pattern fails here rather than at its first use.
            let regex = Regex::compile(&pattern, flags)?;
            return Ok(Value::Object(Rc::new(Object::Pattern { pattern, flags: flags | regex.inline_flags() })));
        }
        let rest: Vec<Value> = args.iter().skip(1).take(flag_index - 1).cloned().collect();
        self.regex_apply(&pattern, flags, name, &rest, kwargs, 0, None)
    }

    /// `pattern.search(text, pos, endpos)` and the other methods of a compiled pattern.
    #[inline(never)]
    pub(crate) fn pattern_method(&mut self, pattern: &Rc<str>, flags: i64, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        match name {
            "groups" => return Ok(Value::Int(Regex::compile(pattern, flags)?.group_count as i64)),
            "groupindex" => {
                let regex = Regex::compile(pattern, flags)?;
                let mut names: Vec<(&String, &usize)> = regex.names.iter().collect();
                names.sort_by_key(|(_, i)| **i);
                return Ok(Value::dict(names.into_iter().map(|(n, i)| (n.as_str(), Value::Int(*i as i64))).collect()));
            }
            _ if !PATTERN_METHODS.contains(&name) => {
                return Err(self.error(format!("'re.Pattern' object has no attribute '{name}'"), "AttributeError"))
            }
            _ => {}
        }
        let position = |i: usize, key: &str| args.get(i).or_else(|| kwarg(&kwargs, key)).and_then(Value::as_int);
        let (pos, endpos) = match name {
            "sub" | "subn" | "split" => (None, None),
            _ => (position(1, "pos"), position(2, "endpos")),
        };
        let rest: Vec<Value> = match name {
            "sub" | "subn" | "split" => args.clone(),
            _ => args.iter().take(1).cloned().collect(),
        };
        self.regex_apply(pattern, flags, name, &rest, &kwargs, pos.unwrap_or(0).max(0) as usize, endpos.map(|e| e.max(0) as usize))
    }

    /// Run one `re` operation. [rest] is what follows the pattern: (string) for
    /// a search, (repl, string, count) for sub, (string, maxsplit) for split.
    /// [pos] and [endpos] narrow where a compiled pattern looks.
    #[allow(clippy::too_many_arguments)]
    fn regex_apply(
        &mut self,
        pattern: &str,
        flags: i64,
        name: &str,
        rest: &[Value],
        kwargs: &[(Rc<str>, Value)],
        pos: usize,
        endpos: Option<usize>,
    ) -> R<Value> {
        let regex = Regex::compile(pattern, flags)?;
        let is_sub = name == "sub" || name == "subn";
        let subject_index = if is_sub { 1 } else { 0 };
        let bytes = flags & BYTES != 0;
        let subject = match (rest.get(subject_index).or_else(|| kwarg(kwargs, "string")), bytes) {
            (Some(Value::Str(full_text)), false) => {
                let full_text = full_text.clone();
                let b = self.chars(&full_text);
                // `endpos` ends the subject early, as if the text stopped there.
                let subject_text: Rc<str> = match endpos {
                    Some(e) if e + 1 < b.len() => Rc::from(&full_text[..b[e] as usize]),
                    _ => full_text.clone(),
                };
                let b = self.chars(&subject_text);
                Rc::new(Subject::new(subject_text, b))
            }
            (Some(Value::Bytes(data)), true) => Rc::new(Subject::of_bytes(&data[..endpos.unwrap_or(data.len()).min(data.len())])),
            (Some(Value::Bytes(_)), false) => return Err(self.error("cannot use a string pattern on a bytes-like object", "TypeError")),
            (Some(Value::Str(_)), true) => return Err(self.error("cannot use a bytes pattern on a string-like object", "TypeError")),
            _ => return Err(self.error(format!("re.{name}() needs a string to search"), "TypeError")),
        };
        let pos = pos.min(subject.len());
        let limit_of = |v: Option<&Value>| -> usize {
            let l = v.and_then(Value::as_int).unwrap_or(0);
            if l > 0 {
                l.min(i32::MAX as i64) as usize
            } else {
                usize::MAX
            }
        };
        let match_value = |m: Option<super::regex::Match>| m.map(|m| Value::Match(Rc::new(m))).unwrap_or(Value::None);
        match name {
            "search" => Ok(match_value(regex.first_match(&subject, pos, false, false, &mut || self.tick())?)),
            "match" => Ok(match_value(regex.first_match(&subject, pos, true, false, &mut || self.tick())?)),
            "fullmatch" => Ok(match_value(regex.first_match(&subject, pos, true, true, &mut || self.tick())?)),
            "findall" => {
                // Python's shape: no groups → the whole match; one group → that group; several → a tuple.
                let all = regex.all_matches(&subject, pos, usize::MAX, &mut || self.tick())?;
                Ok(Value::list(
                    all.iter()
                        .map(|m| match regex.group_count {
                            0 => subject.piece(&m.value()),
                            1 => subject.piece(&m.text(1).unwrap_or_default()),
                            n => Value::tuple((1..=n).map(|g| subject.piece(&m.text(g).unwrap_or_default())).collect()),
                        })
                        .collect(),
                ))
            }
            "finditer" => {
                let all = regex.all_matches(&subject, pos, usize::MAX, &mut || self.tick())?;
                Ok(Value::list(all.into_iter().map(|m| Value::Match(Rc::new(m))).collect()))
            }
            "split" => {
                let all = regex.all_matches(&subject, 0, limit_of(rest.get(1).or_else(|| kwarg(kwargs, "maxsplit"))), &mut || self.tick())?;
                let mut out = Vec::new();
                let mut at = 0;
                for m in &all {
                    let Some((s, e)) = m.spans[0] else { continue };
                    if s == e {
                        continue;
                    }
                    out.push(subject.piece(subject.join(at, s)));
                    for g in 1..=regex.group_count {
                        out.push(m.text(g).map(|t| subject.piece(&t)).unwrap_or(Value::None));
                    }
                    at = e;
                }
                out.push(subject.piece(subject.join(at, subject.len())));
                Ok(Value::list(out))
            }
            "sub" | "subn" => {
                let replacement = rest.first().or_else(|| kwarg(kwargs, "repl")).cloned().unwrap_or(Value::None);
                let all = regex.all_matches(&subject, 0, limit_of(rest.get(2).or_else(|| kwarg(kwargs, "count"))), &mut || self.tick())?;
                let mut out = String::new();
                let mut at = 0;
                let mut count = 0;
                // CPython joins the pieces at the end: a wrong one is named by
                // its place among them.
                let mut pieces = 0;
                let wrong = |me: &Interp, item: usize, found: &Value| {
                    let want = if bytes { "a bytes-like object" } else { "str instance" };
                    me.error(format!("sequence item {item}: expected {want}, {} found", found.type_name()), "TypeError")
                };
                for m in all {
                    let Some((s, e)) = m.spans[0] else { continue };
                    out.push_str(subject.join(at, s));
                    if s > at {
                        pieces += 1;
                    }
                    match &replacement {
                        Value::Str(t) if !bytes => out.push_str(&expand_template(t, &m)),
                        Value::Bytes(t) if bytes => out.push_str(&expand_template(&latin1_text(t), &m)),
                        Value::Str(_) | Value::Bytes(_) => return Err(wrong(self, pieces, &replacement)),
                        other => {
                            let r = self.invoke(other, vec![Value::Match(Rc::new(m))])?;
                            match (&r, bytes) {
                                (Value::Bytes(b), true) => out.push_str(&latin1_text(b)),
                                (Value::Str(s), false) => out.push_str(s),
                                _ => return Err(wrong(self, pieces, &r)),
                            }
                        }
                    }
                    pieces += 1;
                    at = e;
                    count += 1;
                    if out.len() > self.max_string && grapheme_count(&out) > self.max_string {
                        return Err(self.string_abort());
                    }
                }
                out.push_str(subject.join(at, subject.len()));
                let out = subject.piece(&out);
                if name == "sub" {
                    Ok(out)
                } else {
                    Ok(Value::tuple(vec![out, Value::Int(count)]))
                }
            }
            _ => Err(self.error(format!("module 're' has no attribute '{name}'"), "AttributeError")),
        }
    }
}

/// `\1`, `\g<1>` and `\g<name>` in a replacement string.
fn expand_template(template: &str, m: &super::regex::Match) -> String {
    let group = |i: i64| if i < 0 { None } else { m.text(i as usize) };
    let mut out = String::new();
    let chars = graphemes(template);
    let mut i = 0;
    while i < chars.len() {
        if chars[i] != "\\" || i + 1 >= chars.len() {
            out.push_str(chars[i]);
            i += 1;
            continue;
        }
        let next = chars[i + 1];
        if char_is_number(next) {
            out.push_str(&group(parse_swift_int(next, 10).unwrap_or(0)).unwrap_or_default());
            i += 2;
            continue;
        }
        if next == "g" && i + 2 < chars.len() && chars[i + 2] == "<" {
            let mut name = String::new();
            let mut j = i + 3;
            while j < chars.len() && chars[j] != ">" {
                name.push_str(chars[j]);
                j += 1;
            }
            if j < chars.len() {
                let index = parse_swift_int(&name, 10).or_else(|| m.names.get(&name).map(|i| *i as i64)).unwrap_or(-1);
                out.push_str(&group(index).unwrap_or_default());
                i = j + 1;
                continue;
            }
        }
        match next {
            "n" => out.push('\n'),
            "t" => out.push('\t'),
            "\\" => out.push('\\'),
            other => {
                out.push('\\');
                out.push_str(other);
            }
        }
        i += 2;
    }
    out
}

/// `sys.getsizeof(v)` as 64-bit CPython 3.13 answers for the common types:
/// the object's own bytes, not what it refers to.
fn size_of_value(v: &Value) -> i64 {
    let n = |x: usize| x as i64;
    match v {
        Value::None => 16,
        Value::Bool(_) => 28,
        Value::Int(i) => {
            let digits = if *i == 0 { 0 } else { (64 - i.unsigned_abs().leading_zeros() as i64 + 29) / 30 };
            24 + 4 * digits.max(1)
        }
        Value::Big(b) => 24 + 4 * ((b.bits() as i64 + 29) / 30),
        Value::Float(_) => 24,
        Value::Complex(..) => 32,
        Value::Str(s) => {
            let widest = s.chars().map(|c| c as u32).max().unwrap_or(0);
            let count = n(s.chars().count());
            match widest {
                0..=0x7f => 41 + count,
                0x80..=0xff => 57 + count,
                0x100..=0xffff => 58 + 2 * count,
                _ => 60 + 4 * count,
            }
        }
        Value::Bytes(b) => 33 + n(b.len()),
        Value::List(l) => 56 + 8 * n(l.borrow().len()),
        Value::Tuple(t) => 40 + 8 * n(t.len()),
        Value::Dict(d) => {
            let len = d.borrow().len();
            if len == 0 { 64 } else { 184 + 8 * n(len.next_power_of_two().max(8)) }
        }
        Value::Set(s) => {
            let len = s.borrow().len();
            216 + if len > 4 { 8 * n((len * 2).next_power_of_two()) } else { 0 }
        }
        Value::Instance(_) => 48,
        _ => 56,
    }
}
