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
use super::regex::{Regex, Subject, DOT_ALL, IGNORE_CASE, MULTILINE};
use super::text::{char_is_number, graphemes, grapheme_count, java_hash, parse_swift_int};
use super::value::*;

pub const NAMES: &[&str] = &["math", "random", "time", "json", "re", "sys"];

/// PyMini's version, and the line `sys.version` holds.
pub const VERSION: &str = "2.0.1";
pub const VERSION_LINE: &str = "pymini 2.0.1";

pub(crate) fn functions(module: &str) -> &'static [&'static str] {
    match module {
        "math" => &[
            "sqrt", "floor", "ceil", "trunc", "fabs", "pow", "exp", "log", "log2", "log10", "sin", "cos", "tan", "asin",
            "acos", "atan", "atan2", "degrees", "radians", "hypot", "gcd", "lcm", "factorial", "isnan", "isinf",
            "isfinite", "copysign", "fmod", "comb", "prod",
        ],
        "random" => &["random", "randint", "randrange", "choice", "choices", "shuffle", "sample", "uniform", "seed"],
        "time" => &["time", "monotonic", "parts", "format"],
        "json" => &["dumps", "loads"],
        "re" => &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split", "escape"],
        _ => &[],
    }
}

pub(crate) fn constant_names(module: &str) -> &'static [&'static str] {
    match module {
        "math" => &["pi", "e", "tau", "inf", "nan"],
        "re" => &["I", "IGNORECASE", "M", "MULTILINE", "S", "DOTALL"],
        "sys" => &["version"],
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
        ("sys", "version") => Value::str(VERSION_LINE),
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
    pub(crate) fn call_module(&mut self, module: &str, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        self.tick()?;
        if let Some(host) = self.host_modules.get(module).cloned() {
            let f = host
                .functions
                .get(name)
                .cloned()
                .ok_or_else(|| self.error(format!("module '{module}' has no function '{name}'"), "AttributeError"))?;
            return self.call_host(&f, args, kwargs);
        }
        if !self.enabled_modules.contains(&module) {
            return Err(self.error(format!("no module named '{module}'"), "ImportError"));
        }
        match module {
            "math" => self.math_call(name, &args),
            "random" => self.random_call(name, &args),
            "time" => self.time_call(name, &args),
            "json" => self.json_call(name, &args, &kwargs),
            "re" => self.regex_call(name, &args, &kwargs),
            _ => Err(self.error(format!("no module named '{module}'"), "ImportError")),
        }
    }

    // MARK: - math

    #[inline(never)]
    fn math_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
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
                if n > 20 {
                    return Err(self.error("factorial() argument too large", "ValueError"));
                }
                Ok(Value::Int((2..=n).product::<i64>().max(1)))
            }
            "comb" => {
                let n = integer(self, 0)?;
                let k = integer(self, 1)?;
                if n < 0 || k < 0 {
                    return Err(self.error("comb() needs non-negative values", "ValueError"));
                }
                if k > n {
                    return Ok(Value::Int(0));
                }
                let mut r: i64 = 1;
                for i in 0..k.min(n - k) {
                    self.tick()?;
                    r = r.checked_mul(n - i).ok_or_else(|| overflow(self))? / (i + 1);
                }
                Ok(Value::Int(r))
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
                let indent = args.get(1).or_else(|| kwarg(kwargs, "indent")).and_then(Value::as_int);
                let s = json::encode(v, indent, limit, &mut || self.tick())?;
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
        let Some(Value::Str(pattern)) = args.first() else {
            return Err(self.error(format!("re.{name}() needs a pattern string"), "TypeError"));
        };
        // `sub`/`subn` take (pattern, repl, string); everything else (pattern, string).
        let is_sub = name == "sub" || name == "subn";
        let subject_index = if is_sub { 2 } else { 1 };
        let Some(Value::Str(subject_text)) = args.get(subject_index) else {
            return Err(self.error(format!("re.{name}() needs a string to search"), "TypeError"));
        };
        let flag_index = subject_index + if is_sub || name == "split" { 2 } else { 1 };
        let mut flags = 0;
        if let Some(f) = args.get(flag_index).and_then(Value::as_int) {
            flags = f;
        }
        if let Some(f) = kwarg(kwargs, "flags").and_then(Value::as_int) {
            flags = f;
        }
        let regex = Regex::compile(pattern, flags)?;
        let b = self.chars(subject_text);
        let subject = Rc::new(Subject::new(subject_text.clone(), b));
        let limit_arg = |i: usize| -> usize {
            let l = args.get(i).and_then(Value::as_int).unwrap_or(0);
            if l > 0 {
                l.min(i32::MAX as i64) as usize
            } else {
                usize::MAX
            }
        };
        let match_value = |m: Option<super::regex::Match>| m.map(|m| Value::Match(Rc::new(m))).unwrap_or(Value::None);
        match name {
            "search" => Ok(match_value(regex.first_match(&subject, 0, false, false, &mut || self.tick())?)),
            "match" => Ok(match_value(regex.first_match(&subject, 0, true, false, &mut || self.tick())?)),
            "fullmatch" => Ok(match_value(regex.first_match(&subject, 0, true, true, &mut || self.tick())?)),
            "findall" => {
                // Python's shape: no groups → the whole match; one group → that group; several → a tuple.
                let all = regex.all_matches(&subject, usize::MAX, &mut || self.tick())?;
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
                let all = regex.all_matches(&subject, usize::MAX, &mut || self.tick())?;
                Ok(Value::list(all.into_iter().map(|m| Value::Match(Rc::new(m))).collect()))
            }
            "split" => {
                let all = regex.all_matches(&subject, limit_arg(2), &mut || self.tick())?;
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
                let replacement = args[1].clone();
                let all = regex.all_matches(&subject, limit_arg(3), &mut || self.tick())?;
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
