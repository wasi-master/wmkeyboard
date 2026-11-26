//! Integers past 64 bits, as Python's are. An int that fits in an i64 is
//! `Value::Int`; only one that does not is `Value::Big`, so the common
//! arithmetic never leaves the machine word, and every result comes back
//! through [int_value], which puts a value that fits back into an i64.

use std::cell::Cell;
use std::rc::Rc;

use num_bigint::{BigInt, Sign};
use num_integer::Integer;
use num_traits::{FromPrimitive, One, Signed, ToPrimitive, Zero};

use super::ast::BinOp;
use super::value::{Object, Value};

/// The most bits an int may have: about 315,000 decimal digits. Python
/// itself would go on until memory ran out; a plugin stops here instead.
pub(crate) const MAX_BITS: u64 = 1 << 20;

/// CPython's default limit on converting an int to or from decimal text
/// (`sys.get_int_max_str_digits()`), which keeps that conversion quick.
pub(crate) const MAX_STR_DIGITS: usize = 4300;

thread_local! {
    /// The running script's limit, as `sys.set_int_max_str_digits` left it;
    /// 0 for none past [MAX_BITS]. The runtime sets it for each script it runs.
    static STR_DIGITS: Cell<usize> = const { Cell::new(MAX_STR_DIGITS) };
}

/// Put [n] in force as the digit limit, giving back the one it replaces.
pub(crate) fn swap_str_digits(n: usize) -> usize {
    STR_DIGITS.with(|c| c.replace(n))
}

fn str_digits() -> usize {
    STR_DIGITS.with(Cell::get)
}

/// [b] as a script's int: an i64 when it fits.
pub(crate) fn int_value(b: BigInt) -> Value {
    match b.to_i64() {
        Some(i) => Value::Int(i),
        None => Value::Big(Rc::new(b)),
    }
}

/// An int, bool or big int as a BigInt.
pub(crate) fn to_big(v: &Value) -> Option<BigInt> {
    match v {
        Value::Int(i) => Some(BigInt::from(*i)),
        Value::Bool(b) => Some(BigInt::from(*b as i64)),
        Value::Big(b) => Some((**b).clone()),
        _ => None,
    }
}

/// Whether [v] is an int of any size (or a bool).
pub(crate) fn is_int(v: &Value) -> bool {
    matches!(v, Value::Int(_) | Value::Bool(_) | Value::Big(_))
}

/// What can go wrong: a message and the exception kind.
pub(crate) type Fail = (String, &'static str);

fn too_big() -> Fail {
    ("integer result is too large (more than 2**20 bits)".to_string(), "OverflowError")
}

fn bits(b: &BigInt) -> u64 {
    b.bits()
}

/// [op] on two ints of any size, with Python's results. None for true
/// division, which gives a float.
pub(crate) fn binary(op: BinOp, a: &BigInt, b: &BigInt) -> Result<Option<Value>, Fail> {
    Ok(Some(match op {
        BinOp::Add => int_value(a + b),
        BinOp::Sub => int_value(a - b),
        BinOp::Mul => {
            if bits(a) + bits(b) > MAX_BITS {
                return Err(too_big());
            }
            int_value(a * b)
        }
        BinOp::FloorDiv => {
            if b.is_zero() {
                return Err(("integer division or modulo by zero".into(), "ZeroDivisionError"));
            }
            int_value(a.div_floor(b))
        }
        BinOp::Mod => {
            if b.is_zero() {
                return Err(("integer modulo by zero".into(), "ZeroDivisionError"));
            }
            int_value(a.mod_floor(b))
        }
        BinOp::Pow => {
            if b.is_negative() {
                return Ok(None);
            }
            pow(a, b)?
        }
        BinOp::BitAnd => int_value(a & b),
        BinOp::BitOr => int_value(a | b),
        BinOp::BitXor => int_value(a ^ b),
        BinOp::LShift => {
            if b.is_negative() {
                return Err(("negative shift count".into(), "ValueError"));
            }
            if a.is_zero() {
                return Ok(Some(Value::Int(0)));
            }
            let n = b.to_u64().filter(|n| bits(a) + n <= MAX_BITS).ok_or_else(too_big)?;
            int_value(a << n)
        }
        BinOp::RShift => {
            if b.is_negative() {
                return Err(("negative shift count".into(), "ValueError"));
            }
            match b.to_u64().filter(|n| *n < bits(a) + 1) {
                // Python's shift floors: -1 >> n is -1.
                Some(n) => int_value(a >> n),
                None => Value::Int(if a.is_negative() { -1 } else { 0 }),
            }
        }
        BinOp::Div | BinOp::MatMul => return Ok(None),
    }))
}

/// `a ** b` for a non-negative exponent, refused when the result would pass [MAX_BITS].
pub(crate) fn pow(a: &BigInt, b: &BigInt) -> Result<Value, Fail> {
    if a.is_zero() || a.is_one() || b.is_zero() {
        return Ok(Value::Int(if b.is_zero() || a.is_one() { 1 } else { 0 }));
    }
    if a == &BigInt::from(-1) {
        return Ok(Value::Int(if b.is_even() { 1 } else { -1 }));
    }
    let e = b.to_u64().ok_or_else(too_big)?;
    if (bits(a).saturating_sub(1)).saturating_mul(e) > MAX_BITS {
        return Err(too_big());
    }
    let e = u32::try_from(e).map_err(|_| too_big())?;
    Ok(int_value(num_traits::pow(a.clone(), e as usize)))
}

/// `pow(a, b, m)`: modular exponentiation, as Python's three-argument pow.
pub(crate) fn modpow(a: &BigInt, b: &BigInt, m: &BigInt) -> Result<Value, Fail> {
    if m.is_zero() {
        return Err(("pow() 3rd argument cannot be 0".into(), "ValueError"));
    }
    let r = if b.is_negative() {
        // The inverse of a modulo m, raised to -b.
        let inv = inverse(a, m).ok_or_else(|| ("base is not invertible for the given modulus".to_string(), "ValueError"))?;
        inv.modpow(&(-b), &m.abs())
    } else {
        a.modpow(b, &m.abs())
    };
    // Python's result takes the modulus's sign.
    let r = r.mod_floor(m);
    Ok(int_value(r))
}

fn inverse(a: &BigInt, m: &BigInt) -> Option<BigInt> {
    let m = m.abs();
    let e = a.mod_floor(&m).extended_gcd(&m);
    if !e.gcd.is_one() {
        return None;
    }
    Some(e.x.mod_floor(&m))
}

/// `int / int` as Python gives it: the float nearest the true quotient.
pub(crate) fn true_div(a: &BigInt, b: &BigInt) -> Result<f64, Fail> {
    if b.is_zero() {
        return Err(("division by zero".into(), "ZeroDivisionError"));
    }
    if let (Some(x), Some(y)) = (exact_f64(a), exact_f64(b)) {
        return Ok(x / y);
    }
    // Scale both to fit in a float, keeping 64 bits of the quotient.
    let shift = (bits(a) as i64 - bits(b) as i64) - 64;
    let (n, d) = if shift > 0 { (a.clone(), b << shift as u64) } else { (a << (-shift) as u64, b.clone()) };
    let q = n.div_floor(&d);
    let f = q.to_f64().unwrap_or(f64::INFINITY) * 2f64.powi(shift.clamp(-2000, 2000) as i32);
    if f.is_infinite() {
        return Err(("integer division result too large for a float".into(), "OverflowError"));
    }
    Ok(f)
}

/// [b] as a float when it is one exactly.
fn exact_f64(b: &BigInt) -> Option<f64> {
    let f = b.to_f64()?;
    (f.is_finite() && BigInt::from_f64(f).as_ref() == Some(b)).then_some(f)
}

/// `float(n)`, refused past the float range as Python refuses it.
pub(crate) fn to_float(b: &BigInt) -> Result<f64, Fail> {
    match b.to_f64() {
        Some(f) if f.is_finite() => Ok(f),
        _ => Err(("int too large to convert to float".into(), "OverflowError")),
    }
}

/// `int(f)` for a whole float past the i64 range.
pub(crate) fn from_float(f: f64) -> Result<Value, Fail> {
    if f.is_nan() {
        return Err(("cannot convert float NaN to integer".into(), "ValueError"));
    }
    if f.is_infinite() {
        return Err(("cannot convert float infinity to integer".into(), "OverflowError"));
    }
    Ok(int_value(BigInt::from_f64(f.trunc()).unwrap_or_default()))
}

/// How [b] and the float [f] order, exactly: a float is a whole number
/// times a power of two, so this needs no rounding.
pub(crate) fn cmp_float(b: &BigInt, f: f64) -> Option<std::cmp::Ordering> {
    use std::cmp::Ordering;
    if f.is_nan() {
        return None;
    }
    if f.is_infinite() {
        return Some(if f > 0.0 { Ordering::Less } else { Ordering::Greater });
    }
    let whole = BigInt::from_f64(f.floor())?;
    Some(match b.cmp(&whole) {
        Ordering::Equal if f.fract() != 0.0 => Ordering::Less,
        o => o,
    })
}

/// Decimal text, held to the digit limit.
pub(crate) fn to_decimal(b: &BigInt) -> Result<String, Fail> {
    let limit = str_digits();
    let refused = || (format!("Exceeds the limit ({limit} digits) for integer string conversion; {RAISE_IT}"), "ValueError");
    // A cheap bound on the digit count before the real work.
    if limit > 0 && bits(b) as f64 * std::f64::consts::LOG10_2 > limit as f64 + 1.0 {
        return Err(refused());
    }
    let s = b.to_string();
    if limit > 0 && s.trim_start_matches('-').len() > limit {
        return Err(refused());
    }
    Ok(s)
}

const RAISE_IT: &str = "use sys.set_int_max_str_digits() to increase the limit";

/// The digits of |b| in base 2, 8 or 16, lowercase.
pub(crate) fn magnitude_radix(b: &BigInt, radix: u32) -> String {
    b.magnitude().to_str_radix(radix)
}

/// Digits (no sign, no `_`, no prefix) in [radix] as an int of any size;
/// None for something that is not one.
pub(crate) fn parse_digits(digits: &str, radix: u32, negative: bool) -> Result<Option<Value>, Fail> {
    let limit = str_digits();
    if radix == 10 && limit > 0 && digits.len() > limit {
        let n = digits.len();
        return Err((format!("Exceeds the limit ({limit} digits) for integer string conversion: value has {n} digits; {RAISE_IT}"), "ValueError"));
    }
    let Some(mut b) = BigInt::parse_bytes(digits.as_bytes(), radix) else { return Ok(None) };
    if negative {
        b = -b;
    }
    Ok(Some(int_value(b)))
}

/// `n.to_bytes(length, byteorder, signed=...)` for an int of any size.
pub(crate) fn to_bytes(b: &BigInt, length: usize, little: bool, signed: bool) -> Result<Vec<u8>, Fail> {
    if b.is_negative() && !signed {
        return Err(("can't convert negative int to unsigned".into(), "OverflowError"));
    }
    let mut out = if signed { b.to_signed_bytes_be() } else { b.to_bytes_be().1 };
    if out.len() > length {
        // A positive number may carry a leading zero byte it does not need.
        let fits = signed && out.len() == length + 1 && out[0] == 0 && length > 0 && out[1] & 0x80 == 0;
        if !fits || out.len() > length + 1 {
            return Err(("int too big to convert".into(), "OverflowError"));
        }
        out.remove(0);
    }
    let fill = if b.is_negative() { 0xff } else { 0 };
    let mut full = vec![fill; length - out.len()];
    full.extend(out);
    if little {
        full.reverse();
    }
    Ok(full)
}

/// `int.from_bytes(data, byteorder, signed=...)` for any length.
pub(crate) fn from_bytes(data: &[u8], little: bool, signed: bool) -> Value {
    let b = match (little, signed) {
        (false, false) => BigInt::from_bytes_be(Sign::Plus, data),
        (true, false) => BigInt::from_bytes_le(Sign::Plus, data),
        (false, true) => BigInt::from_signed_bytes_be(data),
        (true, true) => BigInt::from_signed_bytes_le(data),
    };
    int_value(b)
}

/// The number of one bits in |b|, `n.bit_count()`.
pub(crate) fn bit_count(b: &BigInt) -> u64 {
    b.magnitude().count_ones()
}

/// `math.isqrt(n)`.
pub(crate) fn isqrt(b: &BigInt) -> Value {
    int_value(b.sqrt())
}

/// `math.gcd` of two ints of any size.
pub(crate) fn gcd(a: &BigInt, b: &BigInt) -> BigInt {
    a.gcd(b)
}

/// The product of [factors], refused past [MAX_BITS].
pub(crate) fn product(factors: impl Iterator<Item = BigInt>) -> Result<Value, Fail> {
    let mut acc = BigInt::one();
    for f in factors {
        if bits(&acc) + bits(&f) > MAX_BITS {
            return Err(too_big());
        }
        acc *= f;
    }
    Ok(int_value(acc))
}

/// `range(start, stop, step)` whose numbers or length pass the i64 range:
/// IPv6 networks, say. One that fits is `Object::Range`.
pub struct BigRange {
    pub(crate) start: BigInt,
    pub(crate) stop: BigInt,
    pub(crate) step: BigInt,
}

impl BigRange {
    /// How many numbers it counts.
    pub(crate) fn len(&self) -> BigInt {
        let one = BigInt::one();
        let n = if self.step.is_positive() {
            (&self.stop - &self.start + &self.step - &one).div_floor(&self.step)
        } else {
            (&self.start - &self.stop - &self.step - &one).div_floor(&-&self.step)
        };
        n.max(BigInt::zero())
    }

    /// The [i]th number, for an [i] already in `0..len`.
    pub(crate) fn at(&self, i: &BigInt) -> BigInt {
        &self.start + i * &self.step
    }

    /// Where [x] falls in it, if it is one of its numbers.
    pub(crate) fn position(&self, x: &BigInt) -> Option<BigInt> {
        let (q, r) = (x - &self.start).div_mod_floor(&self.step);
        (r.is_zero() && !q.is_negative() && q < self.len()).then_some(q)
    }
}

/// A range value: `Object::Range` when its numbers and its length fit an
/// i64, `Object::BigRange` when not.
pub(crate) fn range_value(start: BigInt, stop: BigInt, step: BigInt) -> Value {
    let r = BigRange { start, stop, step };
    if let (Some(a), Some(b), Some(c), Some(_)) = (r.start.to_i64(), r.stop.to_i64(), r.step.to_i64(), r.len().to_i64()) {
        return Value::Object(Rc::new(Object::Range(a, b, c)));
    }
    Value::Object(Rc::new(Object::BigRange(Rc::new(r))))
}

/// Any range's start, stop and step at full size.
pub(crate) fn range_parts(o: &Object) -> Option<BigRange> {
    match o {
        Object::Range(a, b, c) => Some(BigRange { start: BigInt::from(*a), stop: BigInt::from(*b), step: BigInt::from(*c) }),
        Object::BigRange(r) => Some(BigRange { start: r.start.clone(), stop: r.stop.clone(), step: r.step.clone() }),
        _ => None,
    }
}
