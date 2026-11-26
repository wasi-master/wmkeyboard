//! Port of PyModules.kt: the standard namespaces a script can `import`: math,
//! random, time, json, re and sys. Each one only computes: `random` draws from
//! a seedable SplitMix64, `time` reads the host's clock, `re` spends the step
//! budget like any other loop. None can touch a file, the network or another
//! process.

use std::rc::Rc;

use super::ast::BinOp;
use super::datefmt::{civil, format_pattern};
use super::error::R;
use super::eval::Interp;
use super::json;
use super::regex::{Regex, Subject, ASCII, DOT_ALL, IGNORE_CASE, MULTILINE, UNICODE, VERBOSE};
use super::text::{char_is_number, graphemes, grapheme_count, java_hash, longer_than, parse_swift_int};
use super::value::*;

pub const NAMES: &[&str] = &["math", "random", "time", "json", "re", "sys"];

/// [NAMES] and the Rust half of the Python standard library modules
/// ([`super::stdlib`]), which a host turns on with
/// `Runtime::enable_python_stdlib`.
pub const ALL_NAMES: &[&str] = &["math", "random", "time", "json", "re", "sys", "_native", "_time"];

/// PyMini's version, and the line `sys.version` holds.
pub const VERSION: &str = "2.0.1";
pub const VERSION_LINE: &str = "pymini 2.0.1";

pub(crate) fn functions(module: &str) -> &'static [&'static str] {
    match module {
        "math" => &[
            "sqrt", "floor", "ceil", "trunc", "fabs", "pow", "exp", "log", "log2", "log10", "sin", "cos", "tan", "asin",
            "acos", "atan", "atan2", "degrees", "radians", "hypot", "gcd", "lcm", "factorial", "isnan", "isinf",
            "isfinite", "copysign", "fmod", "comb", "prod", "isclose", "isqrt", "perm", "fsum", "dist", "sinh", "cosh",
            "tanh", "asinh", "acosh", "atanh", "log1p", "expm1", "modf", "cbrt", "exp2", "remainder", "ldexp", "frexp",
        ],
        "random" => &["random", "randint", "randrange", "choice", "choices", "shuffle", "sample", "uniform", "seed"],
        // `_time` is the same clock, under the `time` module written in Python.
        "time" | "_time" => &["time", "monotonic", "parts", "format"],
        "json" => &["dumps", "loads"],
        "re" => &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split", "escape", "compile"],
        "sys" => &["getrecursionlimit", "setrecursionlimit", "exit", "intern", "getdefaultencoding", "getfilesystemencoding", "exc_info"],
        "sys.stdout" | "sys.stderr" => &["write", "writelines", "flush", "isatty"],
        "sys.stdin" => &["read", "readline", "readlines", "isatty"],
        "_native" => super::stdlib::NATIVE_FUNCTIONS,
        _ => &[],
    }
}

pub(crate) fn constant_names(module: &str) -> &'static [&'static str] {
    match module {
        "math" => &["pi", "e", "tau", "inf", "nan"],
        "re" => &[
            "I", "IGNORECASE", "M", "MULTILINE", "S", "DOTALL", "X", "VERBOSE", "A", "ASCII", "U", "UNICODE", "NOFLAG", "Pattern", "Match",
            "error", "PatternError",
        ],
        "sys" => &[
            "version", "version_info", "hexversion", "maxsize", "maxunicode", "byteorder", "platform", "argv", "path",
            "modules", "stdout", "stderr", "stdin", "executable",
        ],
        "sys.stdout" | "sys.stderr" | "sys.stdin" => &["encoding"],
        _ => &[],
    }
}

fn constant(module: &str, name: &str) -> Option<Value> {
    Some(match (module, name) {
        ("math", "pi") => Value::Float(std::f64::consts::PI),
        ("math", "e") => Value::Float(std::f64::consts::E),
        ("math", "tau") => Value::Float(std::f64::consts::PI * 2.0),
        ("math", "inf") => Value::Float(f64::INFINITY),
        ("math", "nan") => Value::Float(f64::NAN),
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
        ("sys", "hexversion") => Value::Int(0x030C_00F0),
        ("sys", "maxsize") => Value::Int(i64::MAX),
        ("sys", "maxunicode") => Value::Int(0x10_FFFF),
        ("sys", "byteorder") => Value::str("little"),
        // Where plugins run: the keyboard, on Android.
        ("sys", "platform") => Value::str("android"),
        ("sys", "executable") => Value::str(""),
        ("sys", "stdin") => Value::Module(Rc::from("sys.stdin")),
        ("sys.stdout" | "sys.stderr" | "sys.stdin", "encoding") => Value::str("utf-8"),
        _ => return None,
    })
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

/// What a compiled pattern answers to, as `pattern.search(text)`.
pub(crate) const PATTERN_METHODS: &[&str] = &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split"];

/// The integer square root: the largest r with r * r <= n.
fn isqrt(n: u64) -> u64 {
    let mut r = (n as f64).sqrt() as u64;
    while r > 0 && r.checked_mul(r).is_none_or(|sq| sq > n) {
        r -= 1;
    }
    while (r + 1).checked_mul(r + 1).is_some_and(|sq| sq <= n) {
        r += 1;
    }
    r
}

fn gcd(a: i64, b: i64) -> i64 {
    let (mut x, mut y) = (a, b);
    while y != 0 {
        let t = x % y;
        x = y;
        y = t;
    }
    x.abs()
}

/// C's log2: exact on powers of two, where log(x)/log(2) can miss by an ulp.
fn log2(x: f64) -> f64 {
    if x > 0.0 && x.is_finite() {
        let bits = x.to_bits();
        let mantissa = bits & ((1u64 << 52) - 1);
        let exp = ((bits >> 52) & 0x7ff) as i64;
        if mantissa == 0 && exp != 0 {
            return (exp - 1023) as f64;
        }
    }
    x.ln() / 2f64.ln()
}

impl Interp {
    #[inline(never)]
    /// The members of `sys` that are worked out each time they are read.
    pub(crate) fn sys_member(&self, name: &str) -> Option<Value> {
        Some(match name {
            "version_info" => Value::Tuple(self.version_info.clone()),
            "stdout" => self.stdout_target.clone().unwrap_or_else(|| Value::Module(Rc::from("sys.stdout"))),
            "stderr" => self.stderr_target.clone().unwrap_or_else(|| Value::Module(Rc::from("sys.stderr"))),
            "argv" => Value::list(vec![Value::str("")]),
            "path" => Value::list(Vec::new()),
            "modules" => {
                let mut names: Vec<&str> = self.module_envs.keys().map(String::as_str).collect();
                names.push("sys");
                names.sort_unstable();
                Value::dict(names.into_iter().map(|n| (n, Value::Module(Rc::from(n)))).collect())
            }
            _ => return None,
        })
    }

    fn sys_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "getrecursionlimit" => Ok(Value::Int(self.recursion_limit as i64)),
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
            let f = self
                .source_member(module, name)
                .ok_or_else(|| self.error(format!("module '{module}' has no attribute '{name}'"), "AttributeError"))?;
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
        match module {
            "sys" => self.sys_call(name, &args),
            "math" if args.iter().any(|a| matches!(a, Value::Instance(_))) => self.math_with_instances(name, args, kwargs),
            "math" => self.math_call(name, &args, &kwargs),
            "random" => self.random_call(name, &args),
            "time" | "_time" => self.time_call(name, &args),
            "json" => self.json_call(name, &args, &kwargs),
            "re" => self.regex_call(name, &args, &kwargs),
            "_native" => self.native_call(name, &args),
            _ => Err(self.error(format!("no module named '{module}'"), "ImportError")),
        }
    }

    // MARK: - math

    #[inline(never)]
    /// `math.floor(x)` and the like for an instance: its `__floor__`,
    /// `__ceil__` or `__trunc__`, else the number its `__float__` (or, for
    /// the integer functions, `__index__`) gives.
    fn math_with_instances(&mut self, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let hook = match name {
            "floor" => Some("__floor__"),
            "ceil" => Some("__ceil__"),
            "trunc" => Some("__trunc__"),
            _ => None,
        };
        if let (Some(hook), Some(first)) = (hook, args.first()) {
            if let Some(r) = self.dunder(first, hook, Vec::new())? {
                return Ok(r);
            }
        }
        // These take iterables, which an instance may be.
        if matches!(name, "prod" | "fsum" | "sumprod" | "dist") {
            return self.math_call(name, &args, &kwargs);
        }
        let integer = matches!(name, "isqrt" | "factorial" | "gcd" | "lcm" | "comb" | "perm");
        let mut numbers = Vec::with_capacity(args.len());
        for a in &args {
            if !matches!(a, Value::Instance(_)) {
                numbers.push(a.clone());
                continue;
            }
            let n = if integer {
                self.index_of_instance(a)?.map(Value::Int)
            } else {
                match self.dunder(a, "__float__", Vec::new())? {
                    Some(f @ Value::Float(_)) => Some(f),
                    Some(other) => {
                        return Err(self.error(format!("{}.__float__ returned non-float (type {})", a.type_name(), other.type_name()), "TypeError"));
                    }
                    None => self.index_of_instance(a)?.map(Value::Int),
                }
            };
            match n {
                Some(n) => numbers.push(n),
                None if integer => {
                    return Err(self.error(format!("'{}' object cannot be interpreted as an integer", a.type_name()), "TypeError"));
                }
                None => return Err(self.error(format!("must be real number, not {}", a.type_name()), "TypeError")),
            }
        }
        self.math_call(name, &numbers, &kwargs)
    }

    fn math_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        let number = |me: &Interp, i: usize| -> R<f64> {
            args.get(i).and_then(Value::as_float).ok_or_else(|| me.error(format!("math.{name}() needs a number"), "TypeError"))
        };
        let integer = |me: &Interp, i: usize| -> R<i64> {
            args.get(i).and_then(Value::as_int).ok_or_else(|| me.error(format!("math.{name}() needs an integer"), "TypeError"))
        };
        let overflow = |me: &Interp| me.error(format!("integer overflow in math.{name}()"), "OverflowError");
        let f = |v: f64| Ok(Value::Float(v));
        match name {
            "sqrt" => {
                let x = number(self, 0)?;
                if x.is_nan() || x < 0.0 {
                    return Err(self.error("math domain error", "ValueError"));
                }
                f(x.sqrt())
            }
            "floor" => Ok(Value::Int(self.to_long_exact(number(self, 0)?.floor())?)),
            "ceil" => Ok(Value::Int(self.to_long_exact(number(self, 0)?.ceil())?)),
            "trunc" => Ok(Value::Int(self.to_long_exact(number(self, 0)?.trunc())?)),
            "fabs" => f(number(self, 0)?.abs()),
            "pow" => f(number(self, 0)?.powf(number(self, 1)?)),
            "exp" => f(number(self, 0)?.exp()),
            "log" => {
                let x = number(self, 0)?;
                if x.is_nan() || x <= 0.0 {
                    return Err(self.error("math domain error", "ValueError"));
                }
                if args.len() > 1 {
                    f(x.ln() / number(self, 1)?.ln())
                } else {
                    f(x.ln())
                }
            }
            "log2" => f(log2(number(self, 0)?)),
            "log10" => f(number(self, 0)?.log10()),
            "sin" => f(number(self, 0)?.sin()),
            "cos" => f(number(self, 0)?.cos()),
            "tan" => f(number(self, 0)?.tan()),
            "asin" => f(number(self, 0)?.asin()),
            "acos" => f(number(self, 0)?.acos()),
            "atan" => f(number(self, 0)?.atan()),
            "atan2" => f(number(self, 0)?.atan2(number(self, 1)?)),
            "degrees" => f(number(self, 0)? * 180.0 / std::f64::consts::PI),
            "radians" => f(number(self, 0)? * std::f64::consts::PI / 180.0),
            "hypot" => {
                let mut total = 0.0;
                for i in 0..args.len() {
                    total += number(self, i)?.powf(2.0);
                }
                f(total.sqrt())
            }
            "gcd" => {
                let mut g = integer(self, 0)?.wrapping_abs();
                for i in 1..args.len().max(1) {
                    g = gcd(g, integer(self, i)?.wrapping_abs());
                }
                Ok(Value::Int(g))
            }
            "lcm" => {
                let mut l = integer(self, 0)?.wrapping_abs();
                for i in 1..args.len().max(1) {
                    let b = integer(self, i)?.wrapping_abs();
                    if l == 0 || b == 0 {
                        return Ok(Value::Int(0));
                    }
                    let g = gcd(l, b);
                    l = (l / g).checked_mul(b).ok_or_else(|| overflow(self))?;
                }
                Ok(Value::Int(l))
            }
            "factorial" => {
                let n = integer(self, 0)?;
                if n < 0 {
                    return Err(self.error("factorial() not defined for negative values", "ValueError"));
                }
                if n <= 20 {
                    return Ok(Value::Int((2..=n).product::<i64>().max(1)));
                }
                // Past 64 bits there is no big integer: the answer goes on as a float.
                if n > 170 {
                    return Err(self.error("factorial() result too large for a float", "OverflowError"));
                }
                Ok(Value::Float((2..=n).fold(1.0f64, |acc, i| acc * i as f64)))
            }
            "comb" | "perm" => {
                let n = integer(self, 0)?;
                let k = if name == "perm" && args.len() < 2 { n } else { integer(self, 1)? };
                if n < 0 || k < 0 {
                    return Err(self.error(format!("{name}() needs non-negative values"), "ValueError"));
                }
                if k > n {
                    return Ok(Value::Int(0));
                }
                let steps = if name == "comb" { k.min(n - k) } else { k };
                let mut exact: Option<i64> = Some(1);
                let mut approx: f64 = 1.0;
                for i in 0..steps {
                    self.tick()?;
                    if name == "comb" {
                        exact = exact.and_then(|r| r.checked_mul(n - i)).map(|r| r / (i + 1));
                        approx = approx * (n - i) as f64 / (i + 1) as f64;
                    } else {
                        exact = exact.and_then(|r| r.checked_mul(n - i));
                        approx *= (n - i) as f64;
                    }
                }
                // Past 64 bits the answer goes on as a float, as other integer results do.
                Ok(match exact {
                    Some(r) => Value::Int(r),
                    None if approx.is_finite() => Value::Float(approx.round()),
                    None => return Err(overflow(self)),
                })
            }
            "isclose" => {
                let (a, b) = (number(self, 0)?, number(self, 1)?);
                let rel = kwarg(kwargs, "rel_tol").and_then(Value::as_float).unwrap_or(1e-9);
                let abs = kwarg(kwargs, "abs_tol").and_then(Value::as_float).unwrap_or(0.0);
                if rel < 0.0 || abs < 0.0 {
                    return Err(self.error("tolerances must be non-negative", "ValueError"));
                }
                if a == b {
                    return Ok(Value::Bool(true));
                }
                if a.is_infinite() || b.is_infinite() {
                    return Ok(Value::Bool(false));
                }
                let diff = (a - b).abs();
                Ok(Value::Bool(diff <= (rel * b.abs()).max(rel * a.abs()) || diff <= abs))
            }
            "isqrt" => {
                let n = integer(self, 0)?;
                if n < 0 {
                    return Err(self.error("isqrt() argument must be nonnegative", "ValueError"));
                }
                Ok(Value::Int(isqrt(n as u64) as i64))
            }
            "fsum" => {
                // Neumaier's compensated sum: exact enough that 0.1 * 10 gives 1.0.
                let items = match args.first() {
                    Some(a) => self.iterate(a)?,
                    None => Vec::new(),
                };
                let (mut sum, mut c) = (0.0f64, 0.0f64);
                for v in items {
                    let x = v.as_float().ok_or_else(|| self.error("fsum() needs numbers", "TypeError"))?;
                    let t = sum + x;
                    c += if sum.abs() >= x.abs() { (sum - t) + x } else { (x - t) + sum };
                    sum = t;
                }
                f(sum + c)
            }
            "dist" => {
                let (Some(p), Some(q)) = (args.first(), args.get(1)) else {
                    return Err(self.error("dist() needs two points", "TypeError"));
                };
                let (p, q) = (self.iterate(p)?, self.iterate(q)?);
                if p.len() != q.len() {
                    return Err(self.error("both points must have the same number of dimensions", "ValueError"));
                }
                let mut total = 0.0;
                for (a, b) in p.iter().zip(q.iter()) {
                    let (a, b) = (a.as_float(), b.as_float());
                    let (Some(a), Some(b)) = (a, b) else {
                        return Err(self.error("dist() needs numbers", "TypeError"));
                    };
                    total += (a - b) * (a - b);
                }
                f(total.sqrt())
            }
            "sinh" => f(number(self, 0)?.sinh()),
            "cosh" => f(number(self, 0)?.cosh()),
            "tanh" => f(number(self, 0)?.tanh()),
            "asinh" => f(number(self, 0)?.asinh()),
            "acosh" => f(number(self, 0)?.acosh()),
            "atanh" => f(number(self, 0)?.atanh()),
            "log1p" => f(number(self, 0)?.ln_1p()),
            "expm1" => f(number(self, 0)?.exp_m1()),
            "cbrt" => f(number(self, 0)?.cbrt()),
            "exp2" => f(number(self, 0)?.exp2()),
            "modf" => {
                let x = number(self, 0)?;
                Ok(Value::tuple(vec![Value::Float(x.fract()), Value::Float(x.trunc())]))
            }
            "remainder" => {
                let (x, y) = (number(self, 0)?, number(self, 1)?);
                if y == 0.0 {
                    return Err(self.error("math domain error", "ValueError"));
                }
                f(x - (x / y).round_ties_even() * y)
            }
            "ldexp" => f(number(self, 0)? * 2f64.powi(integer(self, 1)?.clamp(-2000, 2000) as i32)),
            "frexp" => {
                let x = number(self, 0)?;
                if x == 0.0 || !x.is_finite() {
                    return Ok(Value::tuple(vec![Value::Float(x), Value::Int(0)]));
                }
                let e = x.abs().log2().floor() as i32 + 1;
                Ok(Value::tuple(vec![Value::Float(x / 2f64.powi(e)), Value::Int(e as i64)]))
            }
            "prod" => {
                let mut acc = Value::Int(1);
                let items = match args.first() {
                    Some(a) => self.iterate(a)?,
                    None => Vec::new(),
                };
                for v in items {
                    acc = self.binary_op(BinOp::Mul, &acc, &v)?;
                }
                Ok(acc)
            }
            "isnan" => Ok(Value::Bool(number(self, 0)?.is_nan())),
            "isinf" => Ok(Value::Bool(number(self, 0)?.is_infinite())),
            "isfinite" => Ok(Value::Bool(number(self, 0)?.is_finite())),
            "copysign" => f(number(self, 0)?.copysign(number(self, 1)?)),
            "fmod" => f(number(self, 0)? % number(self, 1)?),
            _ => Err(self.error(format!("module 'math' has no attribute '{name}'"), "AttributeError")),
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

    #[inline(never)]
    fn random_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "random" => Ok(Value::Float(self.next_unit())),
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
            "randint" => {
                let a = args.first().and_then(Value::as_int);
                let b = args.get(1).and_then(Value::as_int);
                match (a, b) {
                    (Some(a), Some(b)) if b >= a => {
                        let r = self.random_int(b.wrapping_sub(a).wrapping_add(1));
                        Ok(Value::Int(a.wrapping_add(r)))
                    }
                    _ => Err(self.error("random.randint() needs a <= b", "ValueError")),
                }
            }
            "randrange" => {
                let values: Vec<i64> = args.iter().filter_map(Value::as_int).collect();
                if values.is_empty() {
                    return Err(self.error("random.randrange() needs an integer", "TypeError"));
                }
                let start = if values.len() > 1 { values[0] } else { 0 };
                let stop = if values.len() > 1 { values[1] } else { values[0] };
                let step = if values.len() > 2 { values[2] } else { 1 };
                if step == 0 || stop <= start {
                    return Err(self.error("empty range for randrange()", "ValueError"));
                }
                let count = (stop - start + step - 1) / step;
                let r = self.random_int(count);
                Ok(Value::Int(start + r * step))
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
        let limit = self.max_string;
        match name {
            "dumps" => {
                let v = args.first().ok_or_else(|| self.error("json.dumps() needs a value", "TypeError"))?;
                let indent = match args.get(1).or_else(|| kwarg(kwargs, "indent")) {
                    None | Some(Value::None) => None,
                    Some(Value::Str(s)) => Some(s.to_string()),
                    Some(other) => match other.as_int() {
                        Some(n) => Some(" ".repeat(n.clamp(0, 64) as usize)),
                        None => return Err(self.error("indent must be a number or a string", "TypeError")),
                    },
                };
                let mut options = json::EncodeOptions::new(indent);
                options.sort_keys = kwarg(kwargs, "sort_keys").is_some_and(Value::truthy);
                // Off unless asked: the keyboard's text is rarely ASCII, and JSON reads it either way.
                options.ensure_ascii = kwarg(kwargs, "ensure_ascii").is_some_and(Value::truthy);
                if let Some(sep) = kwarg(kwargs, "separators").filter(|v| !v.is_none()) {
                    match sequence_items(sep).as_deref() {
                        Some([Value::Str(item), Value::Str(key)]) => {
                            options.item_separator = item.to_string();
                            options.key_separator = key.to_string();
                        }
                        _ => return Err(self.error("separators must be an (item, key) pair of strings", "TypeError")),
                    }
                }
                let s = json::encode(v, &options, limit, &mut || self.tick())?;
                Ok(Value::str(s))
            }
            "loads" => {
                let Some(Value::Str(s)) = args.first() else {
                    return Err(self.error("json.loads() needs a string", "TypeError"));
                };
                Ok(json::decode(s, &mut || self.tick())?)
            }
            _ => Err(self.error(format!("module 'json' has no attribute '{name}'"), "AttributeError")),
        }
    }

    // MARK: - re

    #[inline(never)]
    fn regex_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        if name == "escape" {
            let Some(Value::Str(s)) = args.first() else {
                return Err(self.error("re.escape() needs a string", "TypeError"));
            };
            return Ok(Value::str(Regex::escape(s)));
        }
        // A pattern string, or a compiled pattern with its own flags.
        let (pattern, mut flags) = match args.first() {
            Some(Value::Str(p)) => (p.clone(), 0),
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
        let Some(Value::Str(full_text)) = rest.get(subject_index).or_else(|| kwarg(kwargs, "string")) else {
            return Err(self.error(format!("re.{name}() needs a string to search"), "TypeError"));
        };
        let full_text = full_text.clone();
        let b = self.chars(&full_text);
        // `endpos` ends the subject early, as if the text stopped there.
        let subject_text: Rc<str> = match endpos {
            Some(e) if e + 1 < b.len() => Rc::from(&full_text[..b[e] as usize]),
            _ => full_text.clone(),
        };
        let b = self.chars(&subject_text);
        let subject = Rc::new(Subject::new(subject_text.clone(), b));
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
                            0 => Value::str(m.value()),
                            1 => Value::str(m.text(1).unwrap_or_default()),
                            n => Value::tuple((1..=n).map(|g| Value::str(m.text(g).unwrap_or_default())).collect()),
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
                    out.push(Value::str(subject.join(at, s)));
                    for g in 1..=regex.group_count {
                        out.push(m.text(g).map(Value::str).unwrap_or(Value::None));
                    }
                    at = e;
                }
                out.push(Value::str(subject.join(at, subject.len())));
                Ok(Value::list(out))
            }
            "sub" | "subn" => {
                let replacement = rest.first().or_else(|| kwarg(kwargs, "repl")).cloned().unwrap_or(Value::None);
                let all = regex.all_matches(&subject, 0, limit_of(rest.get(2).or_else(|| kwarg(kwargs, "count"))), &mut || self.tick())?;
                let mut out = String::new();
                let mut at = 0;
                let mut count = 0;
                for m in all {
                    let Some((s, e)) = m.spans[0] else { continue };
                    out.push_str(subject.join(at, s));
                    match &replacement {
                        Value::Str(t) => out.push_str(&expand_template(t, &m)),
                        other => {
                            let r = self.invoke(other, vec![Value::Match(Rc::new(m))])?;
                            out.push_str(&py_str(&r));
                        }
                    }
                    at = e;
                    count += 1;
                    if out.len() > self.max_string && grapheme_count(&out) > self.max_string {
                        return Err(self.string_abort());
                    }
                }
                out.push_str(subject.join(at, subject.len()));
                if name == "sub" {
                    Ok(Value::str(out))
                } else {
                    Ok(Value::tuple(vec![Value::str(out), Value::Int(count)]))
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
