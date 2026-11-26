//! The arithmetic behind `math` and `cmath`, as CPython's
//! Modules/mathmodule.c and Modules/cmathmodule.c work it out.
//!
//! The functions CPython takes from the C library come from it here too:
//! Rust's f64 methods call the C library for most of them, but work out
//! asinh, acosh and atanh themselves and have no erf or erfc, so those are
//! declared below. On the same C library the results agree to the last bit.
//! Gamma and log-gamma are CPython's own Lanczos sums, ported line for line.
//! CPython's build fuses each `a*b + c` into one rounding (clang's default),
//! so the ports do too, with `mul_add`.

#[cfg(not(target_family = "wasm"))]
mod c {
    extern "C" {
        pub fn asinh(x: f64) -> f64;
        pub fn acosh(x: f64) -> f64;
        pub fn atanh(x: f64) -> f64;
        pub fn erf(x: f64) -> f64;
        pub fn erfc(x: f64) -> f64;
    }
}

// wasm has no C library: Rust's compiler-builtins supplies these, all but atanh.
#[cfg(target_family = "wasm")]
mod c {
    extern "C" {
        pub fn asinh(x: f64) -> f64;
        pub fn acosh(x: f64) -> f64;
        pub fn erf(x: f64) -> f64;
        pub fn erfc(x: f64) -> f64;
    }

    pub unsafe fn atanh(x: f64) -> f64 {
        x.atanh()
    }
}

pub(crate) fn asinh(x: f64) -> f64 {
    // SAFETY: a pure function of one double, in every C library.
    unsafe { c::asinh(x) }
}

pub(crate) fn acosh(x: f64) -> f64 {
    // SAFETY: as asinh.
    unsafe { c::acosh(x) }
}

pub(crate) fn atanh(x: f64) -> f64 {
    // SAFETY: as asinh.
    unsafe { c::atanh(x) }
}

pub(crate) fn erf(x: f64) -> f64 {
    // SAFETY: as asinh.
    unsafe { c::erf(x) }
}

pub(crate) fn erfc(x: f64) -> f64 {
    // SAFETY: as asinh.
    unsafe { c::erfc(x) }
}

/// What a C maths function reports beside its result: errno's EDOM or ERANGE.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum Errno {
    Ok,
    Domain,
    Range,
}

/// `x * 2**n`, rounded once, as C's scalbn (musl's, which steps through the
/// subnormals without rounding twice).
pub(crate) fn ldexp(x: f64, n: i64) -> f64 {
    let mut n = n.clamp(-1_000_000, 1_000_000) as i32;
    let mut y = x;
    let up = f64::from_bits(0x7fe0_0000_0000_0000); // 2**1023
    let down = f64::from_bits(0x0360_0000_0000_0000); // 2**-1022 * 2**53
    if n > 1023 {
        y *= up;
        n -= 1023;
        if n > 1023 {
            y *= up;
            n -= 1023;
            if n > 1023 {
                n = 1023;
            }
        }
    } else if n < -1022 {
        y *= down;
        n += 1022 - 53;
        if n < -1022 {
            y *= down;
            n += 1022 - 53;
            if n < -1022 {
                n = -1022;
            }
        }
    }
    y * f64::from_bits(((0x3ff + n) as u64) << 52)
}

/// `(m, e)` with `x == m * 2**e` and `0.5 <= |m| < 1`; zeros, infinities and
/// NaN come back as they are, with an exponent of 0.
pub(crate) fn frexp(x: f64) -> (f64, i32) {
    if x == 0.0 || !x.is_finite() {
        return (x, 0);
    }
    let (mut bits, mut shift) = (x.to_bits(), 0);
    if (bits >> 52) & 0x7ff == 0 {
        // Subnormal: scale into the normal range first.
        bits = (x * f64::from_bits(0x4350_0000_0000_0000)).to_bits(); // 2**54
        shift = -54;
    }
    let e = ((bits >> 52) & 0x7ff) as i32 - 1022;
    let m = f64::from_bits((bits & !(0x7ffu64 << 52)) | (1022u64 << 52));
    (m, e + shift)
}

/// The next double after [x] towards [y], as C's nextafter.
pub(crate) fn nextafter(x: f64, y: f64) -> f64 {
    if x.is_nan() || y.is_nan() {
        return x + y;
    }
    if x == y {
        return y;
    }
    if x == 0.0 {
        return f64::from_bits(1).copysign(y);
    }
    let bits = x.to_bits();
    let away = (y > x) == (x > 0.0);
    f64::from_bits(if away { bits + 1 } else { bits - 1 })
}

/// `math.nextafter(x, y, steps=n)`: [steps] doubles along from [x] towards
/// [y], stopping at [y].
pub(crate) fn nextafter_steps(x: f64, y: f64, steps: u64) -> f64 {
    if steps == 0 || x.is_nan() {
        return x;
    }
    if y.is_nan() {
        return y;
    }
    let (ux, uy) = (x.to_bits(), y.to_bits());
    if ux == uy {
        return x;
    }
    let sign = 1u64 << 63;
    let (ax, ay) = (ux & !sign, uy & !sign);
    if (ux ^ uy) & sign != 0 {
        if ax + ay <= steps {
            y
        } else if ax < steps {
            f64::from_bits((uy & sign) | (steps - ax))
        } else {
            f64::from_bits(ux - steps)
        }
    } else if ax > ay {
        if ax - ay >= steps {
            f64::from_bits(ux - steps)
        } else {
            y
        }
    } else if ay - ax >= steps {
        f64::from_bits(ux + steps)
    } else {
        y
    }
}

/// `math.ulp(x)`: the gap from |x| to the next double away from zero.
pub(crate) fn ulp(x: f64) -> f64 {
    if x.is_nan() {
        return x;
    }
    let x = x.abs();
    if x.is_infinite() {
        return x;
    }
    let up = nextafter(x, f64::INFINITY);
    if up.is_infinite() {
        return x - nextafter(x, f64::NEG_INFINITY);
    }
    up - x
}

/// `math.modf(x)`: the fraction and the whole part, both with x's sign.
pub(crate) fn modf(x: f64) -> (f64, f64) {
    if x.is_infinite() {
        return (0f64.copysign(x), x);
    }
    if x.is_nan() {
        return (x, x);
    }
    let whole = x.trunc();
    ((x - whole).copysign(x), whole)
}

/// CPython's m_remainder: x minus the nearest multiple of y, halves to even.
pub(crate) fn remainder(x: f64, y: f64) -> f64 {
    if x.is_finite() && y.is_finite() {
        if y == 0.0 {
            return f64::NAN;
        }
        let (absx, absy) = (x.abs(), y.abs());
        let m = absx % absy;
        let c = absy - m;
        let r = if m < c {
            m
        } else if m > c {
            -c
        } else {
            (-2.0f64).mul_add((0.5 * (absx - m)) % absy, m)
        };
        return 1f64.copysign(x) * r;
    }
    if x.is_nan() {
        return x;
    }
    if y.is_nan() {
        return y;
    }
    if x.is_infinite() {
        return f64::NAN;
    }
    x
}

/// CPython's m_atan2: the C99 answers for infinities and zeros on every platform.
pub(crate) fn atan2(y: f64, x: f64) -> f64 {
    use std::f64::consts::PI;
    if x.is_nan() || y.is_nan() {
        return f64::NAN;
    }
    if y.is_infinite() {
        if x.is_infinite() {
            return if x.is_sign_positive() { (0.25 * PI).copysign(y) } else { (0.75 * PI).copysign(y) };
        }
        return (0.5 * PI).copysign(y);
    }
    if x.is_infinite() || y == 0.0 {
        return if x.is_sign_positive() { 0f64.copysign(y) } else { PI.copysign(y) };
    }
    y.atan2(x)
}

/// `math.pow(x, y)` and its errno, as math_pow_impl.
pub(crate) fn pow(x: f64, y: f64) -> (f64, Errno) {
    if !x.is_finite() || !y.is_finite() {
        let r = if x.is_nan() {
            if y == 0.0 {
                1.0
            } else {
                x
            }
        } else if y.is_nan() {
            if x == 1.0 {
                1.0
            } else {
                y
            }
        } else if x.is_infinite() {
            let odd_y = y.is_finite() && y.abs() % 2.0 == 1.0;
            if y > 0.0 {
                if odd_y {
                    x
                } else {
                    x.abs()
                }
            } else if y == 0.0 {
                1.0
            } else if odd_y {
                0f64.copysign(x)
            } else {
                0.0
            }
        } else if x.abs() == 1.0 {
            1.0
        } else if y > 0.0 && x.abs() > 1.0 {
            y
        } else if y < 0.0 && x.abs() < 1.0 {
            -y
        } else {
            0.0
        };
        return (r, Errno::Ok);
    }
    let r = x.powf(y);
    let errno = if r.is_nan() {
        Errno::Domain
    } else if r.is_infinite() {
        if x == 0.0 {
            Errno::Domain
        } else {
            Errno::Range
        }
    } else {
        Errno::Ok
    };
    (r, errno)
}

/// `math.fmod(x, y)` and its errno.
pub(crate) fn fmod(x: f64, y: f64) -> (f64, Errno) {
    if y.is_infinite() && x.is_finite() {
        return (x, Errno::Ok);
    }
    let r = x % y;
    let bad = r.is_nan() && !x.is_nan() && !y.is_nan();
    (r, if bad { Errno::Domain } else { Errno::Ok })
}

// MARK: - gamma

// CPython's constants, digit for digit: each rounds to the double CPython uses.
#[allow(clippy::excessive_precision)]
const LANCZOS_G: f64 = 6.024_680_040_776_729_583_740_234_375;
#[allow(clippy::excessive_precision)]
const LANCZOS_G_MINUS_HALF: f64 = 5.524_680_040_776_729_583_740_234_375;
#[allow(clippy::excessive_precision)]
const LANCZOS_NUM: [f64; 13] = [
    23_531_376_880.410_759_688_572_007_674_451_636_754_734_846_804_940,
    42_919_803_642.649_098_768_957_899_047_001_988_850_926_355_848_959,
    35_711_959_237.355_668_049_440_185_451_547_166_705_960_488_635_843,
    17_921_034_426.037_209_699_919_755_754_458_931_112_671_403_265_390,
    6_039_542_586.352_028_005_064_291_644_307_297_921_069_938_842_070_8,
    1_439_720_407.311_721_673_663_223_072_794_912_393_971_548_578_677_2,
    248_874_557.862_054_156_511_460_386_413_229_423_216_321_251_278_01,
    31_426_415.585_400_194_380_614_231_628_318_205_362_874_684_987_640,
    2_876_370.628_935_372_441_225_409_051_620_849_613_599_114_537_876_8,
    186_056.265_395_223_495_040_294_989_716_045_699_282_207_842_363_28,
    8_071.672_002_365_816_210_638_002_902_272_250_613_821_851_632_502_4,
    210.824_277_751_579_345_872_509_733_920_713_362_711_669_695_802_91,
    2.506_628_274_631_000_270_164_908_177_133_837_338_626_431_079_340_8,
];
const LANCZOS_DEN: [f64; 13] = [
    0.0, 39_916_800.0, 120_543_840.0, 150_917_976.0, 105_258_076.0, 45_995_730.0, 13_339_535.0, 2_637_558.0, 357_423.0,
    32_670.0, 1_925.0, 66.0, 1.0,
];
/// Gamma of 1 through 23: (n-1)!, each exact in a double.
const GAMMA_INTEGRAL: [f64; 23] = [
    1.0,
    1.0,
    2.0,
    6.0,
    24.0,
    120.0,
    720.0,
    5040.0,
    40320.0,
    362880.0,
    3628800.0,
    39916800.0,
    479001600.0,
    6227020800.0,
    87178291200.0,
    1307674368000.0,
    20922789888000.0,
    355687428096000.0,
    6402373705728000.0,
    121645100408832000.0,
    2432902008176640000.0,
    51090942171709440000.0,
    1124000727777607680000.0,
];
#[allow(clippy::excessive_precision)]
const LOG_PI: f64 = 1.144_729_885_849_400_174_143_427_351_353_058_711_647;

fn lanczos_sum(x: f64) -> f64 {
    let (mut num, mut den) = (0.0f64, 0.0f64);
    if x < 5.0 {
        for i in (0..13).rev() {
            num = num.mul_add(x, LANCZOS_NUM[i]);
            den = den.mul_add(x, LANCZOS_DEN[i]);
        }
    } else {
        for i in 0..13 {
            num = num / x + LANCZOS_NUM[i];
            den = den / x + LANCZOS_DEN[i];
        }
    }
    num / den
}

/// sin(pi * x), exact at the whole and half numbers.
fn sinpi(x: f64) -> f64 {
    use std::f64::consts::PI;
    let y = x.abs() % 2.0;
    let n = (2.0 * y).round() as i32;
    let r = match n {
        0 => (PI * y).sin(),
        1 => (PI * (y - 0.5)).cos(),
        2 => (PI * (1.0 - y)).sin(),
        3 => -(PI * (y - 1.5)).cos(),
        _ => (PI * (y - 2.0)).sin(),
    };
    1f64.copysign(x) * r
}

/// CPython's m_tgamma.
pub(crate) fn gamma(x: f64) -> (f64, Errno) {
    use std::f64::consts::PI;
    if !x.is_finite() {
        if x.is_nan() || x > 0.0 {
            return (x, Errno::Ok);
        }
        return (f64::NAN, Errno::Domain);
    }
    if x == 0.0 {
        return (f64::INFINITY.copysign(x), Errno::Domain);
    }
    if x == x.floor() {
        if x < 0.0 {
            return (f64::NAN, Errno::Domain);
        }
        if x <= 23.0 {
            return (GAMMA_INTEGRAL[x as usize - 1], Errno::Ok);
        }
    }
    let absx = x.abs();
    if absx < 1e-20 {
        let r = 1.0 / x;
        return (r, if r.is_infinite() { Errno::Range } else { Errno::Ok });
    }
    if absx > 200.0 {
        if x < 0.0 {
            return (0.0 / sinpi(x), Errno::Ok);
        }
        return (f64::INFINITY, Errno::Range);
    }
    let y = absx + LANCZOS_G_MINUS_HALF;
    let z = if absx > LANCZOS_G_MINUS_HALF {
        let q = y - absx;
        q - LANCZOS_G_MINUS_HALF
    } else {
        let q = y - LANCZOS_G_MINUS_HALF;
        q - absx
    };
    let z = z * LANCZOS_G / y;
    let mut r;
    if x < 0.0 {
        r = -PI / sinpi(absx) / absx * y.exp() / lanczos_sum(absx);
        r = (-z).mul_add(r, r);
        if absx < 140.0 {
            r /= y.powf(absx - 0.5);
        } else {
            let sqrtpow = y.powf(absx / 2.0 - 0.25);
            r /= sqrtpow;
            r /= sqrtpow;
        }
    } else {
        r = lanczos_sum(absx) / y.exp();
        r = z.mul_add(r, r);
        if absx < 140.0 {
            r *= y.powf(absx - 0.5);
        } else {
            let sqrtpow = y.powf(absx / 2.0 - 0.25);
            r *= sqrtpow;
            r *= sqrtpow;
        }
    }
    (r, if r.is_infinite() { Errno::Range } else { Errno::Ok })
}

/// CPython's m_lgamma: the log of |gamma(x)|.
pub(crate) fn lgamma(x: f64) -> (f64, Errno) {
    if !x.is_finite() {
        if x.is_nan() {
            return (x, Errno::Ok);
        }
        return (f64::INFINITY, Errno::Ok);
    }
    if x == x.floor() && x <= 2.0 {
        if x <= 0.0 {
            return (f64::INFINITY, Errno::Domain);
        }
        return (0.0, Errno::Ok);
    }
    let absx = x.abs();
    if absx < 1e-20 {
        return (-absx.ln(), Errno::Ok);
    }
    let mut r = lanczos_sum(absx).ln() - LANCZOS_G;
    r = (absx - 0.5).mul_add((absx + LANCZOS_G - 0.5).ln() - 1.0, r);
    if x < 0.0 {
        r = LOG_PI - sinpi(absx).abs().ln() - absx.ln() - r;
    }
    (r, if r.is_infinite() { Errno::Range } else { Errno::Ok })
}

// MARK: - sums and norms

/// A double and the rounding error it leaves behind.
#[derive(Clone, Copy)]
struct Double2 {
    hi: f64,
    lo: f64,
}

fn fast_two_sum(a: f64, b: f64) -> Double2 {
    let x = a + b;
    Double2 { hi: x, lo: (a - x) + b }
}

fn two_sum(a: f64, b: f64) -> Double2 {
    let x = a + b;
    let z = x - a;
    Double2 { hi: x, lo: (a - (x - z)) + (b - z) }
}

fn two_prod(x: f64, y: f64) -> Double2 {
    let z = x * y;
    Double2 { hi: z, lo: x.mul_add(y, -z) }
}

/// CPython's vector_norm: the Euclidean length of [vec] (each part already
/// made non-negative, [max] the largest), correctly rounded nearly always.
pub(crate) fn vector_norm(vec: &mut [f64], max: f64, found_nan: bool) -> f64 {
    if max.is_infinite() {
        return max;
    }
    if found_nan {
        return f64::NAN;
    }
    if max == 0.0 || vec.len() <= 1 {
        return max;
    }
    let (_, max_e) = frexp(max);
    if max_e < -1023 {
        for v in vec.iter_mut() {
            *v /= f64::MIN_POSITIVE;
        }
        return f64::MIN_POSITIVE * vector_norm(vec, max / f64::MIN_POSITIVE, found_nan);
    }
    let scale = ldexp(1.0, -i64::from(max_e));
    let (mut csum, mut frac1, mut frac2) = (1.0f64, 0.0f64, 0.0f64);
    for &v in vec.iter() {
        let x = v * scale;
        let pr = two_prod(x, x);
        let sm = fast_two_sum(csum, pr.hi);
        csum = sm.hi;
        frac1 += pr.lo;
        frac2 += sm.lo;
    }
    let mut h = (csum - 1.0 + (frac1 + frac2)).sqrt();
    let pr = two_prod(-h, h);
    let sm = fast_two_sum(csum, pr.hi);
    csum = sm.hi;
    frac1 += pr.lo;
    frac2 += sm.lo;
    let x = csum - 1.0 + (frac1 + frac2);
    h += x / (2.0 * h);
    h / scale
}

/// math.fsum's running state: Shewchuk's exact partial sums, and the
/// infinities and NaNs kept apart.
#[derive(Default)]
pub(crate) struct Fsum {
    partials: Vec<f64>,
    special: f64,
    inf: f64,
}

impl Fsum {
    /// Add [x]; Err for an overflow in between.
    pub(crate) fn add(&mut self, x: f64) -> Result<(), ()> {
        let xsave = x;
        let mut x = x;
        let mut i = 0;
        for j in 0..self.partials.len() {
            let mut y = self.partials[j];
            if x.abs() < y.abs() {
                std::mem::swap(&mut x, &mut y);
            }
            let hi = x + y;
            let lo = y - (hi - x);
            if lo != 0.0 {
                self.partials[i] = lo;
                i += 1;
            }
            x = hi;
        }
        self.partials.truncate(i);
        if x != 0.0 {
            if !x.is_finite() {
                if xsave.is_finite() {
                    return Err(());
                }
                if xsave.is_infinite() {
                    self.inf += xsave;
                }
                self.special += xsave;
                self.partials.clear();
            } else {
                self.partials.push(x);
            }
        }
        Ok(())
    }

    /// The correctly rounded total; Err for `-inf + inf`.
    pub(crate) fn total(&self) -> Result<f64, ()> {
        if self.special != 0.0 {
            if self.inf.is_nan() {
                return Err(());
            }
            return Ok(self.special);
        }
        let p = &self.partials;
        let mut n = p.len();
        let mut hi = 0.0;
        if n > 0 {
            n -= 1;
            hi = p[n];
            let mut lo = 0.0;
            while n > 0 {
                let x = hi;
                n -= 1;
                let y = p[n];
                hi = x + y;
                lo = y - (hi - x);
                if lo != 0.0 {
                    break;
                }
            }
            if n > 0 && ((lo < 0.0 && p[n - 1] < 0.0) || (lo > 0.0 && p[n - 1] > 0.0)) {
                let y = lo * 2.0;
                let x = hi + y;
                if y == x - hi {
                    hi = x;
                }
            }
        }
        Ok(hi)
    }
}

/// math.sumprod's float total: three doubles of a running dot product.
#[derive(Clone, Copy, Default)]
pub(crate) struct Triple {
    hi: f64,
    lo: f64,
    tiny: f64,
}

impl Triple {
    /// The total after adding x * y.
    pub(crate) fn fma(self, x: f64, y: f64) -> Triple {
        let pr = two_prod(x, y);
        let sm = two_sum(self.hi, pr.hi);
        let r1 = two_sum(self.lo, pr.lo);
        let r2 = two_sum(r1.hi, sm.lo);
        Triple { hi: sm.hi, lo: r2.hi, tiny: self.tiny + r1.lo + r2.lo }
    }

    pub(crate) fn is_finite(&self) -> bool {
        self.hi.is_finite()
    }

    pub(crate) fn to_f64(self) -> f64 {
        let last = two_sum(self.lo, self.hi);
        self.tiny + last.lo + last.hi
    }
}
