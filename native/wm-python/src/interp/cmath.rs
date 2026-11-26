//! `cmath`, as CPython's Modules/cmathmodule.c works it out: the same
//! formulas, the same care near overflow and underflow, and the same answers
//! for infinities and NaNs, which come from its tables of special values
//! (in [`tables`], read off CPython itself). Each function gives its result
//! and what C's errno would say: a domain error where the function has no
//! value, a range error where the value overflows.

use super::maths::{asinh as m_asinh, Errno};

/// A result and its errno.
pub(crate) type Out = ((f64, f64), Errno);

type Entry = (u64, u64, Errno);
type Table = [[Entry; 7]; 7];

const fn v(re: u64, im: u64) -> Entry {
    (re, im, Errno::Ok)
}

/// Both parts finite: the table is not consulted.
const U: Entry = (0x7ff8_0000_0000_0000, 0x7ff8_0000_0000_0000, Errno::Ok);
/// No value: a domain error.
const D: Entry = (0x7ff8_0000_0000_0000, 0x7ff8_0000_0000_0000, Errno::Domain);

const LARGE: f64 = f64::MAX / 4.0;
const LN2: f64 = std::f64::consts::LN_2;
const PI: f64 = std::f64::consts::PI;
/// 2 * (DBL_MANT_DIG / 2) + 1 and back: enough to make subnormals normal.
const SCALE_UP: i64 = 53;
const SCALE_DOWN: i64 = -27;

fn sqrt_large() -> f64 {
    LARGE.sqrt()
}

fn log_large() -> f64 {
    LARGE.ln()
}

fn sqrt_min() -> f64 {
    f64::MIN_POSITIVE.sqrt()
}

/// Which row or column of a table a part falls in.
fn class(d: f64) -> usize {
    if d.is_finite() {
        match (d != 0.0, d.is_sign_positive()) {
            (true, true) => 4,
            (true, false) => 1,
            (false, true) => 3,
            (false, false) => 2,
        }
    } else if d.is_nan() {
        6
    } else if d.is_sign_positive() {
        5
    } else {
        0
    }
}

/// The table's answer when a part of [z] is infinite or NaN.
fn special(z: (f64, f64), table: &Table) -> Option<Out> {
    if z.0.is_finite() && z.1.is_finite() {
        return None;
    }
    let (re, im, errno) = table[class(z.0)][class(z.1)];
    Some(((f64::from_bits(re), f64::from_bits(im)), errno))
}

fn neg(z: (f64, f64)) -> (f64, f64) {
    (-z.0, -z.1)
}

/// `_Py_c_abs`: hypot, infinite when a part is, a range error on overflow.
pub(crate) fn abs(z: (f64, f64)) -> (f64, Errno) {
    if !z.0.is_finite() || !z.1.is_finite() {
        if z.0.is_infinite() {
            return (z.0.abs(), Errno::Ok);
        }
        if z.1.is_infinite() {
            return (z.1.abs(), Errno::Ok);
        }
        return (f64::NAN, Errno::Ok);
    }
    let r = z.0.hypot(z.1);
    (r, if r.is_finite() { Errno::Ok } else { Errno::Range })
}

pub(crate) fn sqrt(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::SQRT) {
        return r;
    }
    if z.0 == 0.0 && z.1 == 0.0 {
        return ((0.0, z.1), Errno::Ok);
    }
    let (mut ax, ay) = (z.0.abs(), z.1.abs());
    let s = if ax < f64::MIN_POSITIVE && ay < f64::MIN_POSITIVE {
        ax = super::maths::ldexp(ax, SCALE_UP);
        super::maths::ldexp((ax + ax.hypot(super::maths::ldexp(ay, SCALE_UP))).sqrt(), SCALE_DOWN)
    } else {
        ax /= 8.0;
        2.0 * (ax + ax.hypot(ay / 8.0)).sqrt()
    };
    let d = ay / (2.0 * s);
    if z.0 >= 0.0 {
        ((s, d.copysign(z.1)), Errno::Ok)
    } else {
        ((d, s.copysign(z.1)), Errno::Ok)
    }
}

pub(crate) fn acos(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::ACOS) {
        return r;
    }
    let r = if z.0.abs() > LARGE || z.1.abs() > LARGE {
        let re = z.1.abs().atan2(z.0);
        let l = (z.0 / 2.0).hypot(z.1 / 2.0).ln() + LN2 * 2.0;
        let im = if z.0 < 0.0 { -l.copysign(z.1) } else { l.copysign(-z.1) };
        (re, im)
    } else {
        let s1 = sqrt((1.0 - z.0, -z.1)).0;
        let s2 = sqrt((1.0 + z.0, z.1)).0;
        (2.0 * s1.0.atan2(s2.0), m_asinh(s2.0.mul_add(s1.1, -(s2.1 * s1.0))))
    };
    (r, Errno::Ok)
}

pub(crate) fn acosh(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::ACOSH) {
        return r;
    }
    let r = if z.0.abs() > LARGE || z.1.abs() > LARGE {
        ((z.0 / 2.0).hypot(z.1 / 2.0).ln() + LN2 * 2.0, z.1.atan2(z.0))
    } else {
        let s1 = sqrt((z.0 - 1.0, z.1)).0;
        let s2 = sqrt((z.0 + 1.0, z.1)).0;
        (m_asinh(s1.0.mul_add(s2.0, s1.1 * s2.1)), 2.0 * s1.1.atan2(s2.0))
    };
    (r, Errno::Ok)
}

pub(crate) fn asin(z: (f64, f64)) -> Out {
    let (s, errno) = asinh((-z.1, z.0));
    ((s.1, -s.0), errno)
}

pub(crate) fn asinh(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::ASINH) {
        return r;
    }
    let r = if z.0.abs() > LARGE || z.1.abs() > LARGE {
        let l = (z.0 / 2.0).hypot(z.1 / 2.0).ln() + LN2 * 2.0;
        let re = if z.1 >= 0.0 { l.copysign(z.0) } else { -l.copysign(-z.0) };
        (re, z.1.atan2(z.0.abs()))
    } else {
        let s1 = sqrt((1.0 + z.1, -z.0)).0;
        let s2 = sqrt((1.0 - z.1, z.0)).0;
        (m_asinh(s1.0.mul_add(s2.1, -(s2.0 * s1.1))), z.1.atan2(s1.0.mul_add(s2.0, -(s1.1 * s2.1))))
    };
    (r, Errno::Ok)
}

pub(crate) fn atan(z: (f64, f64)) -> Out {
    let (s, errno) = atanh((-z.1, z.0));
    ((s.1, -s.0), errno)
}

pub(crate) fn atanh(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::ATANH) {
        return r;
    }
    if z.0 < 0.0 {
        let (r, errno) = atanh(neg(z));
        return (neg(r), errno);
    }
    let ay = z.1.abs();
    if z.0 > sqrt_large() || ay > sqrt_large() {
        let h = (z.0 / 2.0).hypot(z.1 / 2.0);
        return ((z.0 / 4.0 / h / h, -(PI / 2.0).copysign(-z.1)), Errno::Ok);
    }
    if z.0 == 1.0 && ay < sqrt_min() {
        if ay == 0.0 {
            return ((f64::INFINITY, z.1), Errno::Domain);
        }
        let re = -(ay.sqrt() / ay.hypot(2.0).sqrt()).ln();
        return ((re, (2.0f64.atan2(-ay) / 2.0).copysign(z.1)), Errno::Ok);
    }
    let one_minus = 1.0 - z.0;
    let re = (4.0 * z.0 / one_minus.mul_add(one_minus, ay * ay)).ln_1p() / 4.0;
    let im = -(-2.0 * z.1).atan2(one_minus.mul_add(1.0 + z.0, -(ay * ay))) / 2.0;
    ((re, im), Errno::Ok)
}

pub(crate) fn cos(z: (f64, f64)) -> Out {
    cosh((-z.1, z.0))
}

pub(crate) fn cosh(z: (f64, f64)) -> Out {
    if !z.0.is_finite() || !z.1.is_finite() {
        let r = if z.0.is_infinite() && z.1.is_finite() && z.1 != 0.0 {
            let (c, s) = (z.1.cos(), z.1.sin());
            if z.0 > 0.0 {
                (f64::INFINITY.copysign(c), f64::INFINITY.copysign(s))
            } else {
                (f64::INFINITY.copysign(c), -f64::INFINITY.copysign(s))
            }
        } else {
            special(z, &tables::COSH).map(|o| o.0).unwrap_or((f64::NAN, f64::NAN))
        };
        let errno = if z.1.is_infinite() && !z.0.is_nan() { Errno::Domain } else { Errno::Ok };
        return (r, errno);
    }
    let r = if z.0.abs() > log_large() {
        let x = z.0 - 1f64.copysign(z.0);
        (z.1.cos() * x.cosh() * std::f64::consts::E, z.1.sin() * x.sinh() * std::f64::consts::E)
    } else {
        (z.1.cos() * z.0.cosh(), z.1.sin() * z.0.sinh())
    };
    overflowed(r)
}

pub(crate) fn sin(z: (f64, f64)) -> Out {
    let (s, errno) = sinh((-z.1, z.0));
    ((s.1, -s.0), errno)
}

pub(crate) fn sinh(z: (f64, f64)) -> Out {
    if !z.0.is_finite() || !z.1.is_finite() {
        let r = if z.0.is_infinite() && z.1.is_finite() && z.1 != 0.0 {
            let (c, s) = (z.1.cos(), z.1.sin());
            if z.0 > 0.0 {
                (f64::INFINITY.copysign(c), f64::INFINITY.copysign(s))
            } else {
                (-f64::INFINITY.copysign(c), f64::INFINITY.copysign(s))
            }
        } else {
            special(z, &tables::SINH).map(|o| o.0).unwrap_or((f64::NAN, f64::NAN))
        };
        let errno = if z.1.is_infinite() && !z.0.is_nan() { Errno::Domain } else { Errno::Ok };
        return (r, errno);
    }
    let r = if z.0.abs() > log_large() {
        let x = z.0 - 1f64.copysign(z.0);
        (z.1.cos() * x.sinh() * std::f64::consts::E, z.1.sin() * x.cosh() * std::f64::consts::E)
    } else {
        (z.1.cos() * z.0.sinh(), z.1.sin() * z.0.cosh())
    };
    overflowed(r)
}

pub(crate) fn tan(z: (f64, f64)) -> Out {
    let (s, errno) = tanh((-z.1, z.0));
    ((s.1, -s.0), errno)
}

pub(crate) fn tanh(z: (f64, f64)) -> Out {
    if !z.0.is_finite() || !z.1.is_finite() {
        let r = if z.0.is_infinite() && z.1.is_finite() && z.1 != 0.0 {
            let im = 0f64.copysign(2.0 * z.1.sin() * z.1.cos());
            (if z.0 > 0.0 { 1.0 } else { -1.0 }, im)
        } else {
            special(z, &tables::TANH).map(|o| o.0).unwrap_or((f64::NAN, f64::NAN))
        };
        let errno = if z.1.is_infinite() && z.0.is_finite() { Errno::Domain } else { Errno::Ok };
        return (r, errno);
    }
    if z.0.abs() > log_large() {
        let im = 4.0 * z.1.sin() * z.1.cos() * (-2.0 * z.0.abs()).exp();
        return ((1f64.copysign(z.0), im), Errno::Ok);
    }
    let tx = z.0.tanh();
    let ty = z.1.tan();
    let cx = 1.0 / z.0.cosh();
    let txty = tx * ty;
    let denom = txty.mul_add(txty, 1.0);
    ((tx * ty.mul_add(ty, 1.0) / denom, ((ty / denom) * cx) * cx), Errno::Ok)
}

pub(crate) fn exp(z: (f64, f64)) -> Out {
    if !z.0.is_finite() || !z.1.is_finite() {
        let r = if z.0.is_infinite() && z.1.is_finite() && z.1 != 0.0 {
            let (c, s) = (z.1.cos(), z.1.sin());
            if z.0 > 0.0 {
                (f64::INFINITY.copysign(c), f64::INFINITY.copysign(s))
            } else {
                (0f64.copysign(c), 0f64.copysign(s))
            }
        } else {
            special(z, &tables::EXP).map(|o| o.0).unwrap_or((f64::NAN, f64::NAN))
        };
        let errno = if z.1.is_infinite() && (z.0.is_finite() || (z.0.is_infinite() && z.0 > 0.0)) {
            Errno::Domain
        } else {
            Errno::Ok
        };
        return (r, errno);
    }
    let r = if z.0 > log_large() {
        let l = (z.0 - 1.0).exp();
        (l * z.1.cos() * std::f64::consts::E, l * z.1.sin() * std::f64::consts::E)
    } else {
        let l = z.0.exp();
        (l * z.1.cos(), l * z.1.sin())
    };
    overflowed(r)
}

/// A range error when a part of [r] came out infinite.
fn overflowed(r: (f64, f64)) -> Out {
    (r, if r.0.is_infinite() || r.1.is_infinite() { Errno::Range } else { Errno::Ok })
}

/// c_log: the log of |z| near 1 taken with log1p, subnormals scaled first.
fn c_log(z: (f64, f64)) -> Out {
    if let Some(r) = special(z, &tables::LOG) {
        return r;
    }
    let (ax, ay) = (z.0.abs(), z.1.abs());
    let re = if ax > LARGE || ay > LARGE {
        (ax / 2.0).hypot(ay / 2.0).ln() + LN2
    } else if ax < f64::MIN_POSITIVE && ay < f64::MIN_POSITIVE {
        if ax > 0.0 || ay > 0.0 {
            let up = |x: f64| super::maths::ldexp(x, 53);
            up(ax).hypot(up(ay)).ln() - 53.0 * LN2
        } else {
            return ((f64::NEG_INFINITY, z.1.atan2(z.0)), Errno::Domain);
        }
    } else {
        let h = ax.hypot(ay);
        if (0.71..=1.73).contains(&h) {
            let (am, an) = if ax > ay { (ax, ay) } else { (ay, ax) };
            (am - 1.0).mul_add(am + 1.0, an * an).ln_1p() / 2.0
        } else {
            h.ln()
        }
    };
    ((re, z.1.atan2(z.0)), Errno::Ok)
}

/// `_Py_c_quot` as cmath.log uses it: a domain error for zero.
fn quot(a: (f64, f64), b: (f64, f64), errno: Errno) -> Out {
    let (abs_re, abs_im) = (b.0.abs(), b.1.abs());
    if abs_re >= abs_im {
        if abs_re == 0.0 {
            return ((0.0, 0.0), Errno::Domain);
        }
        let ratio = b.1 / b.0;
        let denom = b.1.mul_add(ratio, b.0);
        ((a.1.mul_add(ratio, a.0) / denom, (-a.0).mul_add(ratio, a.1) / denom), errno)
    } else if abs_im >= abs_re {
        let ratio = b.0 / b.1;
        let denom = b.0.mul_add(ratio, b.1);
        ((a.0.mul_add(ratio, a.1) / denom, a.1.mul_add(ratio, -a.0) / denom), errno)
    } else {
        ((f64::NAN, f64::NAN), errno)
    }
}

/// `cmath.log(z, base)`. As in CPython, the errno that counts is the last
/// one set: the base's log, then the division.
pub(crate) fn log(z: (f64, f64), base: Option<(f64, f64)>) -> Out {
    let x = c_log(z);
    match base {
        None => x,
        Some(b) => {
            let (y, errno) = c_log(b);
            quot(x.0, y, errno)
        }
    }
}

pub(crate) fn log10(z: (f64, f64)) -> Out {
    let ((re, im), errno) = c_log(z);
    ((re / std::f64::consts::LN_10, im / std::f64::consts::LN_10), errno)
}

/// `cmath.rect(r, phi)`: the complex number with that length and angle.
pub(crate) fn rect(r: f64, phi: f64) -> Out {
    if !r.is_finite() || !phi.is_finite() {
        let z = if r.is_infinite() && phi.is_finite() && phi != 0.0 {
            let (c, s) = (phi.cos(), phi.sin());
            if r > 0.0 {
                (f64::INFINITY.copysign(c), f64::INFINITY.copysign(s))
            } else {
                (-f64::INFINITY.copysign(c), -f64::INFINITY.copysign(s))
            }
        } else {
            special((r, phi), &tables::RECT).map(|o| o.0).unwrap_or((f64::NAN, f64::NAN))
        };
        let errno = if r != 0.0 && !r.is_nan() && phi.is_infinite() { Errno::Domain } else { Errno::Ok };
        return (z, errno);
    }
    if phi == 0.0 {
        return ((r, r * phi), Errno::Ok);
    }
    ((r * phi.cos(), r * phi.sin()), Errno::Ok)
}

/// The special values, row by the real part's class and column by the
/// imaginary part's: -inf, negative, -0.0, +0.0, positive, +inf, NaN.
/// Generated by asking CPython 3.13 for each function's answer at each pair.
#[rustfmt::skip]
mod tables {
    use super::{v, Table, D, U};

    pub(super) const ACOS: Table = [
            [v(0x4002d97c7f3321d2, 0x7ff0000000000000), v(0x400921fb54442d18, 0x7ff0000000000000), v(0x400921fb54442d18, 0x7ff0000000000000), v(0x400921fb54442d18, 0xfff0000000000000), v(0x400921fb54442d18, 0xfff0000000000000), v(0x4002d97c7f3321d2, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff0000000000000)],
            [v(0x3ff921fb54442d18, 0x7ff0000000000000), U, U, U, U, v(0x3ff921fb54442d18, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x3ff921fb54442d18, 0x7ff0000000000000), U, U, U, U, v(0x3ff921fb54442d18, 0xfff0000000000000), v(0x3ff921fb54442d18, 0x7ff8000000000000)],
            [v(0x3ff921fb54442d18, 0x7ff0000000000000), U, U, U, U, v(0x3ff921fb54442d18, 0xfff0000000000000), v(0x3ff921fb54442d18, 0x7ff8000000000000)],
            [v(0x3ff921fb54442d18, 0x7ff0000000000000), U, U, U, U, v(0x3ff921fb54442d18, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x3fe921fb54442d18, 0x7ff0000000000000), v(0x0000000000000000, 0x7ff0000000000000), v(0x0000000000000000, 0x7ff0000000000000), v(0x0000000000000000, 0xfff0000000000000), v(0x0000000000000000, 0xfff0000000000000), v(0x3fe921fb54442d18, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff0000000000000)],
            [v(0x7ff8000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const ACOSH: Table = [
            [v(0x7ff0000000000000, 0xc002d97c7f3321d2), v(0x7ff0000000000000, 0xc00921fb54442d18), v(0x7ff0000000000000, 0xc00921fb54442d18), v(0x7ff0000000000000, 0x400921fb54442d18), v(0x7ff0000000000000, 0x400921fb54442d18), v(0x7ff0000000000000, 0x4002d97c7f3321d2), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbfe921fb54442d18), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x3fe921fb54442d18), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const ASINH: Table = [
            [v(0xfff0000000000000, 0xbfe921fb54442d18), v(0xfff0000000000000, 0x8000000000000000), v(0xfff0000000000000, 0x8000000000000000), v(0xfff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x3fe921fb54442d18), v(0xfff0000000000000, 0x7ff8000000000000)],
            [v(0xfff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0xfff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0xfff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0xfff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbfe921fb54442d18), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x3fe921fb54442d18), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x8000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const ATANH: Table = [
            [v(0x8000000000000000, 0xbff921fb54442d18), v(0x8000000000000000, 0xbff921fb54442d18), v(0x8000000000000000, 0xbff921fb54442d18), v(0x8000000000000000, 0x3ff921fb54442d18), v(0x8000000000000000, 0x3ff921fb54442d18), v(0x8000000000000000, 0x3ff921fb54442d18), v(0x8000000000000000, 0x7ff8000000000000)],
            [v(0x8000000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x8000000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x8000000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x8000000000000000, 0x3ff921fb54442d18), v(0x8000000000000000, 0x7ff8000000000000)],
            [v(0x0000000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x0000000000000000, 0x3ff921fb54442d18), v(0x0000000000000000, 0x7ff8000000000000)],
            [v(0x0000000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x0000000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x0000000000000000, 0xbff921fb54442d18), v(0x0000000000000000, 0xbff921fb54442d18), v(0x0000000000000000, 0xbff921fb54442d18), v(0x0000000000000000, 0x3ff921fb54442d18), v(0x0000000000000000, 0x3ff921fb54442d18), v(0x0000000000000000, 0x3ff921fb54442d18), v(0x0000000000000000, 0x7ff8000000000000)],
            [v(0x0000000000000000, 0xbff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x0000000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const COSH: Table = [
            [D, v(0xfff0000000000000, 0x7ff0000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0xfff0000000000000, 0xfff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x0000000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x0000000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, v(0xfff0000000000000, 0xfff0000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x7ff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const EXP: Table = [
            [v(0x0000000000000000, 0x0000000000000000), v(0x8000000000000000, 0x8000000000000000), v(0x0000000000000000, 0x8000000000000000), v(0x0000000000000000, 0x0000000000000000), v(0x8000000000000000, 0x0000000000000000), v(0x0000000000000000, 0x0000000000000000), v(0x0000000000000000, 0x0000000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, v(0xfff0000000000000, 0xfff0000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x7ff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x8000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const LOG: Table = [
            [v(0x7ff0000000000000, 0xc002d97c7f3321d2), v(0x7ff0000000000000, 0xc00921fb54442d18), v(0x7ff0000000000000, 0xc00921fb54442d18), v(0x7ff0000000000000, 0x400921fb54442d18), v(0x7ff0000000000000, 0x400921fb54442d18), v(0x7ff0000000000000, 0x4002d97c7f3321d2), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbff921fb54442d18), U, U, U, U, v(0x7ff0000000000000, 0x3ff921fb54442d18), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xbfe921fb54442d18), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x3fe921fb54442d18), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff0000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const SINH: Table = [
            [D, v(0x7ff0000000000000, 0xfff0000000000000), v(0xfff0000000000000, 0x8000000000000000), v(0xfff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x7ff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x0000000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x0000000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, v(0xfff0000000000000, 0xfff0000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x7ff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x8000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const SQRT: Table = [
            [v(0x7ff0000000000000, 0xfff0000000000000), v(0x0000000000000000, 0xfff0000000000000), v(0x0000000000000000, 0xfff0000000000000), v(0x0000000000000000, 0x7ff0000000000000), v(0x0000000000000000, 0x7ff0000000000000), v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff0000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), U, U, U, U, v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), U, U, U, U, v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), U, U, U, U, v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), U, U, U, U, v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff0000000000000, 0xfff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff0000000000000, 0x7ff0000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const TANH: Table = [
            [v(0xbff0000000000000, 0x0000000000000000), v(0xbff0000000000000, 0x0000000000000000), v(0xbff0000000000000, 0x8000000000000000), v(0xbff0000000000000, 0x0000000000000000), v(0xbff0000000000000, 0x8000000000000000), v(0xbff0000000000000, 0x0000000000000000), v(0xbff0000000000000, 0x0000000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x3ff0000000000000, 0x0000000000000000), v(0x3ff0000000000000, 0x0000000000000000), v(0x3ff0000000000000, 0x8000000000000000), v(0x3ff0000000000000, 0x0000000000000000), v(0x3ff0000000000000, 0x8000000000000000), v(0x3ff0000000000000, 0x0000000000000000), v(0x3ff0000000000000, 0x0000000000000000)],
            [v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x8000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];

    pub(super) const RECT: Table = [
            [D, v(0x7ff0000000000000, 0x7ff0000000000000), v(0xfff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0xfff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [v(0x0000000000000000, 0x0000000000000000), U, U, U, U, v(0x0000000000000000, 0x0000000000000000), v(0x0000000000000000, 0x0000000000000000)],
            [v(0x0000000000000000, 0x0000000000000000), U, U, U, U, v(0x0000000000000000, 0x0000000000000000), v(0x0000000000000000, 0x0000000000000000)],
            [D, U, U, U, U, D, v(0x7ff8000000000000, 0x7ff8000000000000)],
            [D, v(0xfff0000000000000, 0xfff0000000000000), v(0x7ff0000000000000, 0x8000000000000000), v(0x7ff0000000000000, 0x0000000000000000), v(0xfff0000000000000, 0x7ff0000000000000), D, v(0x7ff0000000000000, 0x7ff8000000000000)],
            [v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x0000000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000), v(0x7ff8000000000000, 0x7ff8000000000000)],
    ];
}
