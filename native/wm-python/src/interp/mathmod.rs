//! The `math` and `cmath` modules, with CPython's arguments, results and
//! errors: "math domain error" (ValueError) where a function has no answer,
//! "math range error" (OverflowError) where the answer is too big for a
//! float, each decided the way Modules/mathmodule.c and cmathmodule.c decide
//! it. The arithmetic itself is in [`super::maths`] and [`super::cmath`].

use std::rc::Rc;

use num_bigint::BigInt;

use super::ast::BinOp;
use super::error::R;
use super::eval::Interp;
use super::maths::{self, Errno};
use super::value::*;

/// The functions CPython gives a single positional argument (METH_O).
const ONE_ARGUMENT: &[&str] = &[
    "acos", "acosh", "asin", "asinh", "atan", "atanh", "cbrt", "ceil", "cos", "cosh", "degrees", "erf", "erfc", "exp", "exp2",
    "expm1", "fabs", "factorial", "floor", "frexp", "fsum", "gamma", "isfinite", "isinf", "isnan", "isqrt", "lgamma", "log10",
    "log1p", "log2", "modf", "radians", "sin", "sinh", "sqrt", "tan", "tanh", "trunc", "ulp",
];

pub(crate) const MATH_FUNCTIONS: &[&str] = &[
    "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "cbrt", "ceil", "comb", "copysign", "cos", "cosh", "degrees",
    "dist", "erf", "erfc", "exp", "exp2", "expm1", "fabs", "factorial", "floor", "fma", "fmod", "frexp", "fsum", "gamma", "gcd",
    "hypot", "isclose", "isfinite", "isinf", "isnan", "isqrt", "lcm", "ldexp", "lgamma", "log", "log10", "log1p", "log2", "modf",
    "nextafter", "perm", "pow", "prod", "radians", "remainder", "sin", "sinh", "sqrt", "sumprod", "tan", "tanh", "trunc", "ulp",
];

pub(crate) const CMATH_FUNCTIONS: &[&str] = &[
    "acos", "acosh", "asin", "asinh", "atan", "atanh", "cos", "cosh", "exp", "isclose", "isfinite", "isinf", "isnan", "log",
    "log10", "phase", "polar", "rect", "sin", "sinh", "sqrt", "tan", "tanh",
];

fn kwarg<'a>(kwargs: &'a [(Rc<str>, Value)], name: &str) -> Option<&'a Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v)
}

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

/// `_PyLong_Frexp`: a positive int too big for a float as `x * 2**e`, with x
/// in [0.5, 1) rounded half to even.
fn long_frexp(b: &BigInt) -> (f64, i64) {
    const KEEP: u64 = 55; // DBL_MANT_DIG + 2
    const CORRECTION: [i64; 8] = [0, -1, -2, 1, 0, -1, 2, 1];
    let mut bits = b.bits();
    let mut q: u64 = if bits <= KEEP {
        num_traits::ToPrimitive::to_u64(&(b << (KEEP - bits))).unwrap_or(0)
    } else {
        let shift = bits - KEEP;
        let top = num_traits::ToPrimitive::to_u64(&(b >> shift)).unwrap_or(0);
        let sticky = b.trailing_zeros().is_some_and(|z| z < shift);
        top | u64::from(sticky)
    };
    q = q.wrapping_add_signed(CORRECTION[(q & 7) as usize]);
    let mut dx = q as f64 / (1u64 << KEEP) as f64;
    if dx == 1.0 {
        dx = 0.5;
        bits += 1;
    }
    (dx, bits as i64)
}

impl Interp {
    /// A real number as math's functions take one: a float, an int, or an
    /// object with `__float__` or `__index__`.
    pub(crate) fn real_number(&mut self, v: &Value) -> R<f64> {
        match v {
            Value::Float(f) => Ok(*f),
            Value::Int(i) => Ok(*i as f64),
            Value::Bool(b) => Ok(f64::from(u8::from(*b))),
            Value::Big(b) => super::bigint::to_float(b).map_err(|(m, k)| self.error(m, k)),
            Value::Instance(_) => match self.dunder(v, "__float__", Vec::new())? {
                Some(Value::Float(f)) => Ok(f),
                Some(other) => {
                    Err(self.error(format!("{}.__float__ returned non-float (type {})", v.type_name(), other.type_name()), "TypeError"))
                }
                None => match self.index_value_of_instance(v)? {
                    Some(n) => self.real_number(&n),
                    None => Err(self.error(format!("must be real number, not {}", v.type_name()), "TypeError")),
                },
            },
            other => Err(self.error(format!("must be real number, not {}", other.type_name()), "TypeError")),
        }
    }

    /// [v] as an int, or the TypeError CPython raises for anything else.
    fn integer_arg(&mut self, v: &Value) -> R<BigInt> {
        if let Some(b) = super::bigint::to_big(v) {
            return Ok(b);
        }
        if let Some(n) = self.index_value_of_instance(v)? {
            if let Some(b) = super::bigint::to_big(&n) {
                return Ok(b);
            }
        }
        Err(self.error(format!("'{}' object cannot be interpreted as an integer", v.type_name()), "TypeError"))
    }

    /// The keyword errors CPython's math functions raise: only isclose,
    /// prod and nextafter take any.
    fn math_keywords(&self, module: &str, name: &str, kwargs: &[(Rc<str>, Value)]) -> R<()> {
        let allowed: &[&str] = match name {
            "isclose" => &["rel_tol", "abs_tol"],
            "prod" if module == "math" => &["start"],
            "nextafter" if module == "math" => &["steps"],
            _ if kwargs.is_empty() => return Ok(()),
            _ => return Err(self.error(format!("{module}.{name}() takes no keyword arguments"), "TypeError")),
        };
        match kwargs.iter().find(|(k, _)| !allowed.contains(&k.as_ref())) {
            Some((k, _)) => Err(self.error(format!("{name}() got an unexpected keyword argument '{k}'"), "TypeError")),
            None => Ok(()),
        }
    }

    /// The arity errors CPython's math functions raise, word for word.
    fn math_arity(&self, module: &str, name: &str, n: usize) -> R<()> {
        let exactly_one = if module == "math" {
            ONE_ARGUMENT.contains(&name)
        } else {
            !matches!(name, "rect" | "log" | "isclose")
        };
        let message = if exactly_one {
            (n != 1).then(|| format!("{module}.{name}() takes exactly one argument ({n} given)"))
        } else {
            match name {
                "atan2" | "copysign" | "pow" | "fmod" | "ldexp" | "remainder" | "dist" | "comb" | "sumprod" | "rect" if n != 2 => {
                    Some(format!("{name} expected 2 arguments, got {n}"))
                }
                "fma" if n != 3 => Some(format!("fma expected 3 arguments, got {n}")),
                "log" | "perm" if n < 1 => Some(format!("{name} expected at least 1 argument, got {n}")),
                "log" | "perm" if n > 2 => Some(format!("{name} expected at most 2 arguments, got {n}")),
                "isclose" if n == 0 => Some("isclose() missing required argument 'a' (pos 1)".to_string()),
                "isclose" if n == 1 => Some("isclose() missing required argument 'b' (pos 2)".to_string()),
                "isclose" if n > 2 => Some(format!("isclose() takes exactly 2 positional arguments ({n} given)")),
                "nextafter" if n != 2 => Some(format!("nextafter() takes exactly 2 positional arguments ({n} given)")),
                "prod" if n != 1 => Some(format!("prod() takes exactly 1 positional argument ({n} given)")),
                _ => None,
            }
        };
        match message {
            Some(m) => Err(self.error(m, "TypeError")),
            None => Ok(()),
        }
    }

    /// A float result and what the C function said about it, as is_error
    /// turns that into an exception (an underflow is no error).
    fn math_result(&self, (r, errno): (f64, Errno)) -> R<Value> {
        match errno {
            Errno::Ok => Ok(Value::Float(r)),
            Errno::Domain => Err(self.error("math domain error", "ValueError")),
            Errno::Range if r.abs() < 1.5 => Ok(Value::Float(r)),
            Errno::Range => Err(self.error("math range error", "OverflowError")),
        }
    }

    /// math_1: [func] of a float, where a NaN from a number is a domain
    /// error and an infinity from a finite number a range error, or a domain
    /// error for the functions that cannot overflow.
    fn math_one(&self, x: f64, func: fn(f64) -> f64, can_overflow: bool) -> R<Value> {
        let r = func(x);
        if r.is_nan() && !x.is_nan() {
            return Err(self.error("math domain error", "ValueError"));
        }
        if r.is_infinite() && x.is_finite() {
            return Err(if can_overflow {
                self.error("math range error", "OverflowError")
            } else {
                self.error("math domain error", "ValueError")
            });
        }
        Ok(Value::Float(r))
    }

    /// loghelper: the log of an int of any size, or of a float through math_1.
    fn log_of(&mut self, v: &Value, func: fn(f64) -> f64) -> R<f64> {
        if let Some(b) = super::bigint::to_big(v) {
            if !num_traits::Signed::is_positive(&b) {
                return Err(self.error("math domain error", "ValueError"));
            }
            return Ok(match num_traits::ToPrimitive::to_f64(&b).filter(|f| f.is_finite()) {
                Some(x) => func(x),
                None => {
                    let (x, e) = long_frexp(&b);
                    func(2.0).mul_add(e as f64, func(x))
                }
            });
        }
        // m_log and math_1: zero, the negatives and -inf have no log.
        let x = self.real_number(v)?;
        if x <= 0.0 {
            return Err(self.error("math domain error", "ValueError"));
        }
        Ok(func(x))
    }

    #[inline(never)]
    /// `math.floor(x)` and the like for an instance: its `__floor__`,
    /// `__ceil__` or `__trunc__`, else the number its `__float__` (or, for
    /// the integer functions, `__index__`) gives.
    pub(crate) fn math_with_instances(&mut self, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
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
            if name == "trunc" {
                return Err(self.error(format!("type {} doesn't define __trunc__ method", first.type_name()), "TypeError"));
            }
        }
        // These take iterables, which an instance may be, or work on the objects themselves.
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
            if integer {
                let n = self.integer_arg(a)?;
                numbers.push(super::bigint::int_value(n));
            } else {
                numbers.push(Value::Float(self.real_number(a)?));
            }
        }
        self.math_call(name, &numbers, &kwargs)
    }

    #[inline(never)]
    pub(crate) fn math_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        if !MATH_FUNCTIONS.contains(&name) {
            return Err(self.error(format!("module 'math' has no attribute '{name}'"), "AttributeError"));
        }
        // isclose(a=..., b=...): its two numbers may be named.
        let (named_args, named_kwargs): (Vec<Value>, Vec<(Rc<str>, Value)>);
        let (args, kwargs) = if name == "isclose" && kwargs.iter().any(|(k, _)| matches!(k.as_ref(), "a" | "b")) {
            let mut all = args.to_vec();
            all.extend(["a", "b"].iter().filter_map(|key| kwarg(kwargs, key).cloned()));
            named_args = all;
            named_kwargs = kwargs.iter().filter(|(k, _)| !matches!(k.as_ref(), "a" | "b")).cloned().collect();
            (named_args.as_slice(), named_kwargs.as_slice())
        } else {
            (args, kwargs)
        };
        self.math_keywords("math", name, kwargs)?;
        self.math_arity("math", name, args.len())?;
        let f = |v: f64| Ok(Value::Float(v));
        // floor, ceil and trunc of an int are the int; of a float, an int of any size.
        if matches!(name, "floor" | "ceil" | "trunc") {
            if let Some(v) = args.first().filter(|v| super::bigint::is_int(v)) {
                return Ok(super::bigint::to_big(v).map(super::bigint::int_value).unwrap_or(Value::Int(0)));
            }
            let x = match &args[0] {
                Value::Float(x) => *x,
                other if name == "trunc" => {
                    return Err(self.error(format!("type {} doesn't define __trunc__ method", other.type_name()), "TypeError"));
                }
                other => self.real_number(other)?,
            };
            let whole = match name {
                "floor" => x.floor(),
                "ceil" => x.ceil(),
                _ => x.trunc(),
            };
            return self.float_to_int(whole);
        }
        match name {
            "log" | "log2" | "log10" => {
                let func: fn(f64) -> f64 = match name {
                    "log2" => f64::log2,
                    "log10" => f64::log10,
                    _ => f64::ln,
                };
                let num = self.log_of(&args[0], func)?;
                if let Some(base) = args.get(1) {
                    let den = self.log_of(base, func)?;
                    if den == 0.0 {
                        return Err(self.error("float division by zero", "ZeroDivisionError"));
                    }
                    return f(num / den);
                }
                f(num)
            }
            "isqrt" | "factorial" => {
                let n = self.integer_arg(&args[0])?;
                if num_traits::Signed::is_negative(&n) {
                    return Err(self.error(
                        if name == "isqrt" { "isqrt() argument must be nonnegative" } else { "factorial() not defined for negative values" },
                        "ValueError",
                    ));
                }
                if name == "isqrt" {
                    return Ok(match num_traits::ToPrimitive::to_u64(&n) {
                        Some(small) => Value::Int(isqrt(small) as i64),
                        None => super::bigint::isqrt(&n),
                    });
                }
                let n = num_traits::ToPrimitive::to_i64(&n)
                    .ok_or_else(|| self.error("factorial() argument should not exceed 9223372036854775807", "OverflowError"))?;
                if n <= 20 {
                    return Ok(Value::Int((2..=n).product::<i64>().max(1)));
                }
                if n > 100_000 {
                    return Err(self.error("factorial() argument should not exceed 100000 here", "OverflowError"));
                }
                for _ in 0..n / 64 {
                    self.tick()?;
                }
                super::bigint::product((2..=n).map(BigInt::from)).map_err(|(m, k)| self.error(m, k))
            }
            "gcd" | "lcm" => {
                let mut acc = BigInt::from(if name == "gcd" { 0 } else { 1 });
                for a in args {
                    let b = num_traits::Signed::abs(&self.integer_arg(a)?);
                    if name == "gcd" {
                        acc = super::bigint::gcd(&acc, &b);
                    } else if num_traits::Zero::is_zero(&acc) || num_traits::Zero::is_zero(&b) {
                        acc = BigInt::from(0);
                    } else {
                        let g = super::bigint::gcd(&acc, &b);
                        let l = super::bigint::product([acc / g, b].into_iter()).map_err(|(m, k)| self.error(m, k))?;
                        acc = super::bigint::to_big(&l).unwrap_or_default();
                    }
                }
                Ok(super::bigint::int_value(acc))
            }
            "comb" | "perm" => self.comb_perm(name, args),
            "isclose" => {
                let (a, b) = (self.real_number(&args[0])?, self.real_number(&args[1])?);
                let (rel, abs) = self.tolerances(kwargs)?;
                if a == b {
                    return Ok(Value::Bool(true));
                }
                if a.is_infinite() || b.is_infinite() {
                    return Ok(Value::Bool(false));
                }
                let diff = (a - b).abs();
                Ok(Value::Bool(diff <= (rel * b).abs() || diff <= (rel * a).abs() || diff <= abs))
            }
            "fsum" => {
                let items = self.iterate(&args[0])?;
                let mut sum = maths::Fsum::default();
                for v in &items {
                    let x = self.real_number(v)?;
                    if sum.add(x).is_err() {
                        return Err(self.error("intermediate overflow in fsum", "OverflowError"));
                    }
                }
                match sum.total() {
                    Ok(t) => f(t),
                    Err(()) => Err(self.error("-inf + inf in fsum", "ValueError")),
                }
            }
            "hypot" | "dist" => {
                let mut parts = Vec::new();
                if name == "hypot" {
                    for a in args {
                        parts.push(self.real_number(a)?.abs());
                    }
                } else {
                    let (p, q) = (self.iterate(&args[0])?, self.iterate(&args[1])?);
                    if p.len() != q.len() {
                        return Err(self.error("both points must have the same number of dimensions", "ValueError"));
                    }
                    for (a, b) in p.iter().zip(q.iter()) {
                        let (a, b) = (self.real_number(a)?, self.real_number(b)?);
                        parts.push((a - b).abs());
                    }
                }
                let max = parts.iter().copied().fold(0.0f64, |m, x| if x > m { x } else { m });
                let found_nan = parts.iter().any(|x| x.is_nan());
                f(maths::vector_norm(&mut parts, max, found_nan))
            }
            "prod" => {
                let mut acc = kwarg(kwargs, "start").cloned().unwrap_or(Value::Int(1));
                for v in self.iterate(&args[0])? {
                    acc = self.binary_op(BinOp::Mul, &acc, &v)?;
                }
                Ok(acc)
            }
            "sumprod" => self.sumprod(&args[0], &args[1]),
            "ldexp" => {
                let x = self.real_number(&args[0])?;
                let Some(e) = super::bigint::to_big(&args[1]) else {
                    return Err(self.error("Expected an int as second argument to ldexp.", "TypeError"));
                };
                if x == 0.0 || !x.is_finite() {
                    return f(x);
                }
                let e = num_traits::ToPrimitive::to_i64(&e).unwrap_or(if num_traits::Signed::is_negative(&e) { i64::MIN } else { i64::MAX });
                let r = maths::ldexp(x, e);
                if r.is_infinite() {
                    return Err(self.error("math range error", "OverflowError"));
                }
                f(r)
            }
            "frexp" => {
                let (m, e) = maths::frexp(self.real_number(&args[0])?);
                Ok(Value::tuple(vec![Value::Float(m), Value::Int(i64::from(e))]))
            }
            "modf" => {
                let (frac, whole) = maths::modf(self.real_number(&args[0])?);
                Ok(Value::tuple(vec![Value::Float(frac), Value::Float(whole)]))
            }
            "nextafter" => {
                let (x, y) = (self.real_number(&args[0])?, self.real_number(&args[1])?);
                let steps = match kwarg(kwargs, "steps") {
                    None | Some(Value::None) => return f(maths::nextafter(x, y)),
                    Some(s) => self.integer_arg(s)?,
                };
                if num_traits::Signed::is_negative(&steps) {
                    return Err(self.error("steps must be a non-negative integer", "ValueError"));
                }
                f(maths::nextafter_steps(x, y, num_traits::ToPrimitive::to_u64(&steps).unwrap_or(u64::MAX)))
            }
            "ulp" => f(maths::ulp(self.real_number(&args[0])?)),
            "fma" => {
                let (x, y, z) = (self.real_number(&args[0])?, self.real_number(&args[1])?, self.real_number(&args[2])?);
                let r = x.mul_add(y, z);
                if r.is_nan() && !x.is_nan() && !y.is_nan() && !z.is_nan() {
                    return Err(self.error("invalid operation in fma", "ValueError"));
                }
                if r.is_infinite() && x.is_finite() && y.is_finite() && z.is_finite() {
                    return Err(self.error("overflow in fma", "OverflowError"));
                }
                f(r)
            }
            "isnan" => Ok(Value::Bool(self.real_number(&args[0])?.is_nan())),
            "isinf" => Ok(Value::Bool(self.real_number(&args[0])?.is_infinite())),
            "isfinite" => Ok(Value::Bool(self.real_number(&args[0])?.is_finite())),
            "degrees" => f(self.real_number(&args[0])? * (180.0 / std::f64::consts::PI)),
            "radians" => f(self.real_number(&args[0])? * (std::f64::consts::PI / 180.0)),
            "gamma" => {
                let x = self.real_number(&args[0])?;
                self.math_result(maths::gamma(x))
            }
            "lgamma" => {
                let x = self.real_number(&args[0])?;
                self.math_result(maths::lgamma(x))
            }
            // math_2: a NaN from numbers is a domain error, an infinity from finite numbers a range error.
            "atan2" | "copysign" | "remainder" | "pow" | "fmod" => {
                let (x, y) = (self.real_number(&args[0])?, self.real_number(&args[1])?);
                match name {
                    "pow" => return self.math_result(maths::pow(x, y)),
                    "fmod" => return self.math_result(maths::fmod(x, y)),
                    _ => {}
                }
                let r = match name {
                    "atan2" => maths::atan2(x, y),
                    "copysign" => x.copysign(y),
                    _ => maths::remainder(x, y),
                };
                let errno = if r.is_nan() && !x.is_nan() && !y.is_nan() {
                    Errno::Domain
                } else if r.is_infinite() && x.is_finite() && y.is_finite() {
                    Errno::Range
                } else {
                    Errno::Ok
                };
                self.math_result((r, errno))
            }
            _ => {
                let x = self.real_number(&args[0])?;
                let (func, can_overflow): (fn(f64) -> f64, bool) = match name {
                    "sqrt" => (f64::sqrt, false),
                    "exp" => (f64::exp, true),
                    "exp2" => (f64::exp2, true),
                    "expm1" => (f64::exp_m1, true),
                    "log1p" => (f64::ln_1p, false),
                    "sin" => (f64::sin, false),
                    "cos" => (f64::cos, false),
                    "tan" => (f64::tan, false),
                    "asin" => (f64::asin, false),
                    "acos" => (f64::acos, false),
                    "atan" => (f64::atan, false),
                    "sinh" => (f64::sinh, true),
                    "cosh" => (f64::cosh, true),
                    "tanh" => (f64::tanh, false),
                    "asinh" => (maths::asinh, false),
                    "acosh" => (maths::acosh, false),
                    "atanh" => (maths::atanh, false),
                    "erf" => (maths::erf, false),
                    "erfc" => (maths::erfc, false),
                    "cbrt" => (f64::cbrt, false),
                    _ => (f64::abs, false),
                };
                self.math_one(x, func, can_overflow)
            }
        }
    }

    /// isclose's keyword-only tolerances, refused when negative.
    fn tolerances(&mut self, kwargs: &[(Rc<str>, Value)]) -> R<(f64, f64)> {
        let mut rel = 1e-9;
        let mut abs = 0.0;
        for (k, v) in kwargs {
            match k.as_ref() {
                "rel_tol" => rel = self.real_number(v)?,
                "abs_tol" => abs = self.real_number(v)?,
                other => return Err(self.error(format!("isclose() got an unexpected keyword argument '{other}'"), "TypeError")),
            }
        }
        if rel < 0.0 || abs < 0.0 {
            return Err(self.error("tolerances must be non-negative", "ValueError"));
        }
        Ok((rel, abs))
    }

    /// `math.comb(n, k)` and `math.perm(n, k=None)` for ints of any size.
    fn comb_perm(&mut self, name: &str, args: &[Value]) -> R<Value> {
        let n = self.integer_arg(&args[0])?;
        let k = match args.get(1) {
            None | Some(Value::None) if name == "perm" => n.clone(),
            Some(k) => self.integer_arg(k)?,
            None => BigInt::from(0),
        };
        if num_traits::Signed::is_negative(&n) {
            return Err(self.error("n must be a non-negative integer", "ValueError"));
        }
        if num_traits::Signed::is_negative(&k) {
            return Err(self.error("k must be a non-negative integer", "ValueError"));
        }
        if k > n {
            return Ok(Value::Int(0));
        }
        let steps = if name == "comb" { (&n - &k).min(k) } else { k };
        let Some(steps) = num_traits::ToPrimitive::to_i64(&steps) else {
            let what = if name == "comb" { "min(n - k, k)" } else { "k" };
            return Err(self.error(format!("{what} must not exceed 9223372036854775807"), "OverflowError"));
        };
        let mut r = BigInt::from(1);
        for i in 0..steps {
            self.tick()?;
            r *= &n - i;
            if name == "comb" {
                r /= i + 1;
            }
            if r.bits() > super::bigint::MAX_BITS {
                return Err(self.error(format!("integer overflow in math.{name}()"), "OverflowError"));
            }
        }
        Ok(super::bigint::int_value(r))
    }

    /// `math.sumprod(p, q)` as CPython adds it up: exact while every pair is
    /// two ints that fit a machine word, in triple-double precision while
    /// pairs are floats (or a float and an int), and by `+` and `*` after.
    fn sumprod(&mut self, p: &Value, q: &Value) -> R<Value> {
        let (p, q) = (self.iterate(p)?, self.iterate(q)?);
        let n = p.len().min(q.len());
        let mut total = Value::Int(0);
        let (mut int_on, mut flt_on) = (true, true);
        let mut int_total: Option<i64> = None;
        let mut flt_total: Option<maths::Triple> = None;
        let double = |v: &Value| match v {
            Value::Int(i) => Some(*i as f64),
            Value::Bool(b) => Some(f64::from(u8::from(*b))),
            Value::Big(b) => super::bigint::to_float(b).ok(),
            _ => None,
        };
        for i in 0..=n {
            let finished = i == n;
            if finished && p.len() != q.len() {
                return Err(self.error("Inputs are not the same length", "ValueError"));
            }
            if int_on {
                if !finished {
                    if let (Value::Int(a), Value::Int(b)) = (&p[i], &q[i]) {
                        if let Some(s) = a.checked_mul(*b).and_then(|t| int_total.unwrap_or(0).checked_add(t)) {
                            int_total = Some(s);
                            continue;
                        }
                    }
                }
                int_on = false;
                if let Some(t) = int_total.take() {
                    total = self.binary_op(BinOp::Add, &total, &Value::Int(t))?;
                }
            }
            if flt_on {
                if !finished {
                    let pair = match (&p[i], &q[i]) {
                        (Value::Float(a), Value::Float(b)) => Some((*a, *b)),
                        (Value::Float(a), other) => double(other).map(|b| (*a, b)),
                        (other, Value::Float(b)) => double(other).map(|a| (a, *b)),
                        _ => None,
                    };
                    if let Some((a, b)) = pair {
                        let t = flt_total.unwrap_or_default().fma(a, b);
                        if t.is_finite() {
                            flt_total = Some(t);
                            continue;
                        }
                    }
                }
                flt_on = false;
                if let Some(t) = flt_total.take() {
                    total = self.binary_op(BinOp::Add, &total, &Value::Float(t.to_f64()))?;
                }
            }
            if finished {
                break;
            }
            let term = self.binary_op(BinOp::Mul, &p[i], &q[i])?;
            total = self.binary_op(BinOp::Add, &total, &term)?;
        }
        Ok(total)
    }

    // MARK: - cmath

    /// [v] as cmath's functions take one: a complex, an object with
    /// `__complex__`, or a real number.
    fn complex_arg(&mut self, v: &Value) -> R<(f64, f64)> {
        match v {
            Value::Complex(re, im) => Ok((*re, *im)),
            Value::Instance(_) => match self.dunder(v, "__complex__", Vec::new())? {
                Some(Value::Complex(re, im)) => Ok((re, im)),
                Some(other) => Err(self.error(
                    format!("__complex__ returned non-complex (type {})", other.type_name()),
                    "TypeError",
                )),
                None => Ok((self.real_number(v)?, 0.0)),
            },
            other => Ok((self.real_number(other)?, 0.0)),
        }
    }

    fn cmath_result(&self, r: super::cmath::Out) -> R<Value> {
        match r.1 {
            Errno::Ok => Ok(Value::Complex(r.0 .0, r.0 .1)),
            Errno::Domain => Err(self.error("math domain error", "ValueError")),
            Errno::Range => Err(self.error("math range error", "OverflowError")),
        }
    }

    #[inline(never)]
    pub(crate) fn cmath_call(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        use super::cmath;
        if !CMATH_FUNCTIONS.contains(&name) {
            return Err(self.error(format!("module 'cmath' has no attribute '{name}'"), "AttributeError"));
        }
        self.math_keywords("cmath", name, kwargs)?;
        self.math_arity("cmath", name, args.len())?;
        match name {
            "rect" => {
                let (r, phi) = (self.real_number(&args[0])?, self.real_number(&args[1])?);
                self.cmath_result(cmath::rect(r, phi))
            }
            "isclose" => {
                let (a, b) = (self.complex_arg(&args[0])?, self.complex_arg(&args[1])?);
                let (rel, abs) = self.tolerances(kwargs)?;
                if a == b {
                    return Ok(Value::Bool(true));
                }
                if a.0.is_infinite() || a.1.is_infinite() || b.0.is_infinite() || b.1.is_infinite() {
                    return Ok(Value::Bool(false));
                }
                let diff = cmath::abs((a.0 - b.0, a.1 - b.1)).0;
                Ok(Value::Bool(diff <= rel * cmath::abs(b).0 || diff <= rel * cmath::abs(a).0 || diff <= abs))
            }
            "log" => {
                let z = self.complex_arg(&args[0])?;
                let base = match args.get(1) {
                    Some(b) => Some(self.complex_arg(b)?),
                    None => None,
                };
                self.cmath_result(cmath::log(z, base))
            }
            _ => {
                let z = self.complex_arg(&args[0])?;
                match name {
                    "phase" => Ok(Value::Float(z.1.atan2(z.0))),
                    "polar" => {
                        let (r, errno) = cmath::abs(z);
                        if errno != Errno::Ok {
                            return Err(self.error("math range error", "OverflowError"));
                        }
                        Ok(Value::tuple(vec![Value::Float(r), Value::Float(z.1.atan2(z.0))]))
                    }
                    "isfinite" => Ok(Value::Bool(z.0.is_finite() && z.1.is_finite())),
                    "isinf" => Ok(Value::Bool(z.0.is_infinite() || z.1.is_infinite())),
                    "isnan" => Ok(Value::Bool(z.0.is_nan() || z.1.is_nan())),
                    _ => {
                        let func: fn((f64, f64)) -> cmath::Out = match name {
                            "sqrt" => cmath::sqrt,
                            "exp" => cmath::exp,
                            "log10" => cmath::log10,
                            "sin" => cmath::sin,
                            "cos" => cmath::cos,
                            "tan" => cmath::tan,
                            "asin" => cmath::asin,
                            "acos" => cmath::acos,
                            "atan" => cmath::atan,
                            "sinh" => cmath::sinh,
                            "cosh" => cmath::cosh,
                            "tanh" => cmath::tanh,
                            "asinh" => cmath::asinh,
                            "acosh" => cmath::acosh,
                            _ => cmath::atanh,
                        };
                        self.cmath_result(func(z))
                    }
                }
            }
        }
    }
}
