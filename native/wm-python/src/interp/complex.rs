//! Complex numbers, worked out as CPython's Objects/complexobject.c does:
//! the same division, the same powers (exact repeated squaring for small
//! whole exponents), the same repr, string syntax and hash. CPython's own
//! build fuses each `a*b + c` into one rounding (clang's default on ARM), so
//! these do too, with `mul_add`: products and quotients come out to the bit.

use super::ast::BinOp;
use super::value::format_double;

/// What can go wrong: a message and the exception kind.
pub(crate) type Fail = (String, &'static str);

/// One part as complex's repr writes it: a float's repr without a `.0`.
pub(crate) fn part(d: f64) -> String {
    let s = format_double(d);
    match s.strip_suffix(".0") {
        Some(t) => t.to_string(),
        None => s,
    }
}

/// `repr(z)`: `1j` when the real part is +0.0, `(1+2j)` otherwise.
pub(crate) fn repr(re: f64, im: f64) -> String {
    if re == 0.0 && re.is_sign_positive() {
        return format!("{}j", part(im));
    }
    format!("({}{}j)", part(re), signed(im))
}

/// [im] with its sign always written, `+nan` included, as Py_DTSF_SIGN does.
fn signed(im: f64) -> String {
    if im.is_nan() {
        return "+nan".into();
    }
    let s = part(im);
    if s.starts_with('-') {
        s
    } else {
        format!("+{s}")
    }
}

/// `hash(z)`: the real part's hash plus 1000003 times the imaginary part's.
pub(crate) fn hash(re: f64, im: f64) -> i64 {
    let (hr, hi) = (super::hashing::of_float(re) as u64, super::hashing::of_float(im) as u64);
    let h = hr.wrapping_add(1_000_003u64.wrapping_mul(hi)) as i64;
    if h == -1 {
        -2
    } else {
        h
    }
}

fn prod(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    (a.0.mul_add(b.0, -(a.1 * b.1)), a.0.mul_add(b.1, a.1 * b.0))
}

/// `_Py_c_quot`: Smith's division, scaled by the larger part of [b].
fn quot(a: (f64, f64), b: (f64, f64)) -> Option<(f64, f64)> {
    let (abs_re, abs_im) = (b.0.abs(), b.1.abs());
    if abs_re >= abs_im {
        if abs_re == 0.0 {
            return None;
        }
        let ratio = b.1 / b.0;
        let denom = b.1.mul_add(ratio, b.0);
        Some((a.1.mul_add(ratio, a.0) / denom, (-a.0).mul_add(ratio, a.1) / denom))
    } else if abs_im >= abs_re {
        let ratio = b.0 / b.1;
        let denom = b.0.mul_add(ratio, b.1);
        Some((a.0.mul_add(ratio, a.1) / denom, a.1.mul_add(ratio, -a.0) / denom))
    } else {
        // A part of [b] is NaN.
        Some((f64::NAN, f64::NAN))
    }
}

/// `c_powu`: [x] to a positive whole power by repeated squaring.
fn powu(x: (f64, f64), n: u64) -> (f64, f64) {
    let (mut r, mut p) = ((1.0, 0.0), x);
    let mut mask = 1u64;
    while mask > 0 && n >= mask {
        if n & mask != 0 {
            r = prod(r, p);
        }
        mask <<= 1;
        p = prod(p, p);
    }
    r
}

/// `_Py_c_pow` for any exponent; None for zero to a negative or complex power.
fn pow_general(a: (f64, f64), b: (f64, f64)) -> Option<(f64, f64)> {
    if b.0 == 0.0 && b.1 == 0.0 {
        return Some((1.0, 0.0));
    }
    if a.0 == 0.0 && a.1 == 0.0 {
        if b.1 != 0.0 || b.0 < 0.0 {
            return None;
        }
        return Some((0.0, 0.0));
    }
    let vabs = a.0.hypot(a.1);
    let mut len = vabs.powf(b.0);
    let at = a.1.atan2(a.0);
    let mut phase = at * b.0;
    if b.1 != 0.0 {
        len *= (-(at * b.1)).exp();
        phase = b.1.mul_add(vabs.ln(), phase);
    }
    Some((len * phase.cos(), len * phase.sin()))
}

/// `a ** b`, as complex_pow: whole exponents up to 100 multiply exactly.
pub(crate) fn pow(a: (f64, f64), b: (f64, f64)) -> Result<(f64, f64), Fail> {
    let zero = || ("0.0 to a negative or complex power".to_string(), "ZeroDivisionError");
    let r = if b.1 == 0.0 && b.0 == b.0.floor() && b.0.abs() <= 100.0 {
        let n = b.0 as i64;
        if n >= 0 {
            powu(a, n as u64)
        } else {
            quot((1.0, 0.0), powu(a, n.unsigned_abs())).ok_or_else(zero)?
        }
    } else {
        pow_general(a, b).ok_or_else(zero)?
    };
    let finite = |z: (f64, f64)| z.0.is_finite() && z.1.is_finite();
    if !finite(r) && finite(a) && finite(b) {
        return Err(("complex exponentiation".into(), "OverflowError"));
    }
    Ok(r)
}

/// [op] on two complex numbers; None for an operator complex lacks.
pub(crate) fn binary(op: BinOp, a: (f64, f64), b: (f64, f64)) -> Result<Option<(f64, f64)>, Fail> {
    Ok(Some(match op {
        BinOp::Add => (a.0 + b.0, a.1 + b.1),
        BinOp::Sub => (a.0 - b.0, a.1 - b.1),
        BinOp::Mul => prod(a, b),
        BinOp::Div => quot(a, b).ok_or_else(|| ("complex division by zero".to_string(), "ZeroDivisionError"))?,
        BinOp::Pow => pow(a, b)?,
        _ => return Ok(None),
    }))
}

/// `complex(text)`: `1+2j`, `-j`, `(3.5e2-1j)`, `inf+nanj`, a bare real or
/// a bare imaginary number, with spaces around and underscores in digits.
pub(crate) fn parse(text: &str) -> Option<(f64, f64)> {
    let space = |c: char| super::text::scalar_is_whitespace(c);
    let mut s = text.trim_matches(space);
    if let Some(inner) = s.strip_prefix('(') {
        s = inner.strip_suffix(')')?.trim_matches(space);
    }
    let b = s.as_bytes();
    let mut at = 0;
    let first = number(s, &mut at);
    let result = match first {
        Some(x) => match b.get(at) {
            Some(b'+' | b'-') => {
                let sign = if b[at] == b'-' { -1.0 } else { 1.0 };
                at += 1;
                // `1+j`: a sign alone stands for one.
                let y = number_unsigned(s, &mut at).unwrap_or(1.0);
                if !matches!(b.get(at), Some(b'j' | b'J')) {
                    return None;
                }
                at += 1;
                (x, sign * y)
            }
            Some(b'j' | b'J') => {
                at += 1;
                (0.0, x)
            }
            _ => (x, 0.0),
        },
        None => {
            let sign = match b.get(at) {
                Some(b'-') => {
                    at += 1;
                    -1.0
                }
                Some(b'+') => {
                    at += 1;
                    1.0
                }
                _ => 1.0,
            };
            if !matches!(b.get(at), Some(b'j' | b'J')) {
                return None;
            }
            at += 1;
            (0.0, sign)
        }
    };
    (at == b.len()).then_some(result)
}

/// A float with an optional sign at [at], moving past it.
fn number(s: &str, at: &mut usize) -> Option<f64> {
    let start = *at;
    let sign = match s.as_bytes().get(*at) {
        Some(b'-') => {
            *at += 1;
            -1.0
        }
        Some(b'+') => {
            *at += 1;
            1.0
        }
        _ => 1.0,
    };
    match number_unsigned(s, at) {
        Some(v) => Some(sign * v),
        None => {
            *at = start;
            None
        }
    }
}

/// An unsigned float at [at]: digits with an optional point, exponent and
/// single underscores between digits, or inf, infinity or nan.
fn number_unsigned(s: &str, at: &mut usize) -> Option<f64> {
    let b = s.as_bytes();
    let rest = &s[*at..];
    let lower = rest.to_ascii_lowercase();
    for (word, v) in [("infinity", f64::INFINITY), ("inf", f64::INFINITY), ("nan", f64::NAN)] {
        if lower.starts_with(word) {
            *at += word.len();
            return Some(v);
        }
    }
    let start = *at;
    let mut text = String::new();
    let mut digits = 0;
    let read_digits = |at: &mut usize, text: &mut String| -> Option<usize> {
        let mut n = 0;
        while let Some(&c) = b.get(*at) {
            if c.is_ascii_digit() {
                text.push(c as char);
                n += 1;
                *at += 1;
            } else if c == b'_' && n > 0 && b.get(*at + 1).is_some_and(u8::is_ascii_digit) {
                *at += 1;
            } else {
                break;
            }
        }
        Some(n)
    };
    digits += read_digits(at, &mut text)?;
    if b.get(*at) == Some(&b'.') {
        text.push('.');
        *at += 1;
        digits += read_digits(at, &mut text)?;
    }
    if digits == 0 {
        *at = start;
        return None;
    }
    if matches!(b.get(*at), Some(b'e' | b'E')) {
        let mark = *at;
        let mut exp = String::from("e");
        *at += 1;
        if let Some(&c @ (b'+' | b'-')) = b.get(*at) {
            exp.push(c as char);
            *at += 1;
        }
        if read_digits(at, &mut exp)? == 0 {
            *at = mark;
        } else {
            text.push_str(&exp);
        }
    }
    text.parse::<f64>().ok()
}
