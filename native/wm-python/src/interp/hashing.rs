//! CPython's hashes of numbers and tuples, so `hash(2)` is 2 and
//! `hash(Decimal(2)) == hash(2)` as `decimal` expects. A dict does not use
//! these: they are what `hash()` answers.

use num_bigint::BigInt;
use num_integer::Integer;
use num_traits::{Signed, ToPrimitive};

/// 2**61 - 1, the prime a number's hash is taken modulo.
pub(crate) const MODULUS: u64 = (1 << 61) - 1;
/// `hash(float("inf"))`.
pub(crate) const INF: i64 = 314_159;
/// `hash(None)`, fixed since Python 3.12.
pub(crate) const NONE: i64 = 4_238_894_112;

/// -1 is CPython's error value, so a hash of -1 is -2.
fn fix(h: i64) -> i64 {
    if h == -1 {
        -2
    } else {
        h
    }
}

pub(crate) fn of_int(n: i64) -> i64 {
    let h = (n.unsigned_abs() % MODULUS) as i64;
    fix(if n < 0 { -h } else { h })
}

pub(crate) fn of_big(b: &BigInt) -> i64 {
    let h = b.abs().mod_floor(&BigInt::from(MODULUS)).to_i64().unwrap_or(0);
    fix(if b.is_negative() { -h } else { h })
}

/// `_Py_HashDouble`: the float's value modulo the prime, worked out 28 bits
/// at a time from its mantissa.
pub(crate) fn of_float(x: f64) -> i64 {
    if x.is_infinite() {
        return if x > 0.0 { INF } else { -INF };
    }
    if x.is_nan() {
        return 0;
    }
    let (mut m, mut e) = frexp(x);
    let sign = if m < 0.0 {
        m = -m;
        -1
    } else {
        1
    };
    let mut h: u64 = 0;
    while m != 0.0 {
        h = ((h << 28) & MODULUS) | (h >> (61 - 28));
        m *= 268_435_456.0;
        e -= 28;
        let y = m as u64;
        m -= y as f64;
        h += y;
        if h >= MODULUS {
            h -= MODULUS;
        }
    }
    let e = if e >= 0 { e % 61 } else { 61 - 1 - ((-1 - e) % 61) } as u32;
    h = ((h << e) & MODULUS) | (h >> (61 - e));
    fix(h as i64 * sign)
}

/// [x] as a mantissa in [0.5, 1) and a power of two.
fn frexp(x: f64) -> (f64, i32) {
    if x == 0.0 {
        return (x, 0);
    }
    let bits = x.to_bits();
    let exp = ((bits >> 52) & 0x7ff) as i32;
    if exp == 0 {
        // Subnormal: scale into the normal range first.
        let (m, e) = frexp(x * 2f64.powi(54));
        return (m, e - 54);
    }
    let m = f64::from_bits((bits & !(0x7ff << 52)) | (1022 << 52));
    (m, exp - 1022)
}

/// CPython's tuple hash (xxHash's mixing) over its items' hashes.
pub(crate) fn of_tuple(lanes: &[i64]) -> i64 {
    const P1: u64 = 11_400_714_785_074_694_791;
    const P2: u64 = 14_029_467_366_897_019_727;
    const P5: u64 = 2_870_177_450_012_600_261;
    let mut acc = P5;
    for &lane in lanes {
        acc = acc.wrapping_add((lane as u64).wrapping_mul(P2));
        acc = acc.rotate_left(31);
        acc = acc.wrapping_mul(P1);
    }
    acc = acc.wrapping_add(lanes.len() as u64 ^ (P5 ^ 3_527_539));
    if acc == u64::MAX {
        return 1_546_275_796;
    }
    acc as i64
}
