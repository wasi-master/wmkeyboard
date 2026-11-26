//! The `struct` module's packing, as CPython's: `@` (the default) uses this
//! machine's C sizes and alignment, `=`, `<`, `>` and `!` the standard
//! sizes with no padding. Errors come back as (kind, message): kind
//! "error" is `struct.error`, which `src/stdlib/struct.py` raises.

use std::ffi::{c_int, c_long};

use super::value::Value;

pub(crate) type StructError = (&'static str, String);

fn error(message: impl Into<String>) -> StructError {
    ("error", message.into())
}

struct Item {
    code: char,
    /// A repeat count; for `s` and `p` the length, for `x` the pad bytes.
    count: usize,
    offset: usize,
    /// Bytes one value takes.
    width: usize,
}

pub(crate) struct Layout {
    big: bool,
    items: Vec<Item>,
    pub size: usize,
}

impl Layout {
    /// How many values the format packs and unpacks.
    pub fn values(&self) -> usize {
        self.items.iter().map(|i| if i.code == 'x' { 0 } else if matches!(i.code, 's' | 'p') { 1 } else { i.count }).sum()
    }
}

/// (size, alignment) of a code, native or standard; None when the mode
/// has no such code.
fn width(code: char, native: bool) -> Option<(usize, usize)> {
    use std::mem::{align_of, size_of};
    Some(match code {
        'x' | 'c' | 'b' | 'B' | '?' | 's' | 'p' => (1, 1),
        'h' | 'H' => (2, if native { align_of::<i16>() } else { 1 }),
        'e' => (2, if native { 2 } else { 1 }),
        'i' | 'I' if native => (size_of::<c_int>(), align_of::<c_int>()),
        'l' | 'L' if native => (size_of::<c_long>(), align_of::<c_long>()),
        'i' | 'I' | 'l' | 'L' | 'f' if !native => (4, 1),
        'f' => (4, align_of::<f32>()),
        'q' | 'Q' => (8, if native { align_of::<i64>() } else { 1 }),
        'd' => (8, if native { align_of::<f64>() } else { 1 }),
        'n' | 'N' | 'P' if native => (size_of::<isize>(), align_of::<isize>()),
        _ => return None,
    })
}

pub(crate) fn layout(fmt: &str) -> Result<Layout, StructError> {
    let mut chars = fmt.chars().peekable();
    let (native, big) = match chars.peek() {
        Some('@') => {
            chars.next();
            (true, cfg!(target_endian = "big"))
        }
        Some('=') => {
            chars.next();
            (false, cfg!(target_endian = "big"))
        }
        Some('<') => {
            chars.next();
            (false, false)
        }
        Some('>' | '!') => {
            chars.next();
            (false, true)
        }
        _ => (true, cfg!(target_endian = "big")),
    };
    let mut items = Vec::new();
    let mut size = 0usize;
    while let Some(c) = chars.next() {
        if c.is_whitespace() {
            continue;
        }
        let mut code = c;
        let mut count: Option<usize> = None;
        if c.is_ascii_digit() {
            let mut n = c.to_digit(10).unwrap_or(0) as usize;
            loop {
                match chars.next() {
                    Some(d) if d.is_ascii_digit() => {
                        n = n.checked_mul(10).and_then(|n| n.checked_add(d.to_digit(10).unwrap_or(0) as usize)).ok_or_else(|| error("total struct size too long"))?;
                    }
                    Some(d) => {
                        code = d;
                        break;
                    }
                    None => return Err(error("repeat count given without format specifier")),
                }
            }
            count = Some(n);
        }
        let (w, align) = width(code, native).ok_or_else(|| error("bad char in struct format"))?;
        let count = count.unwrap_or(1);
        // A count of 0 still aligns: '0l' at the end pads to a long's alignment.
        if native && align > 1 && !matches!(code, 's' | 'p' | 'x') {
            size = size.div_ceil(align) * align;
        }
        let bytes = if matches!(code, 's' | 'p' | 'x') { count } else { w.checked_mul(count).ok_or_else(|| error("total struct size too long"))? };
        items.push(Item { code, count, offset: size, width: w });
        size = size.checked_add(bytes).ok_or_else(|| error("total struct size too long"))?;
    }
    if size > 1 << 26 {
        return Err(error("total struct size too long"));
    }
    Ok(Layout { big, items, size })
}

fn int_of(v: &Value) -> Result<i128, StructError> {
    match v {
        Value::Int(i) => Ok(i128::from(*i)),
        Value::Bool(b) => Ok(i128::from(*b)),
        // Past i128 it is out of every format's range, which the caller reports.
        Value::Big(b) => Ok(num_traits::ToPrimitive::to_i128(b.as_ref()).unwrap_or(if num_traits::Signed::is_negative(b.as_ref()) { i128::MIN } else { i128::MAX })),
        _ => Err(error("required argument is not an integer")),
    }
}

fn float_of(v: &Value) -> Result<f64, StructError> {
    match v {
        Value::Float(f) => Ok(*f),
        Value::Int(i) => Ok(*i as f64),
        Value::Bool(b) => Ok(f64::from(u8::from(*b))),
        Value::Big(b) => super::bigint::to_float(b).map_err(|_| error("required argument is not a float")),
        _ => Err(error("required argument is not a float")),
    }
}

/// IEEE 754 half precision, rounding to nearest even.
fn to_half(x: f64) -> Result<u16, StructError> {
    let sign: u16 = if x.is_sign_negative() { 0x8000 } else { 0 };
    if x.is_nan() {
        return Ok(sign | 0x7E00);
    }
    let a = x.abs();
    if a.is_infinite() {
        return Ok(sign | 0x7C00);
    }
    // Largest finite half is 65504; values that round past it overflow.
    if a >= 65520.0 {
        return Err(("OverflowError", "float too large to pack with e format".into()));
    }
    if a < 2f64.powi(-14) {
        // Subnormal: a multiple of 2^-24.
        let m = (a / 2f64.powi(-24)).round_ties_even() as u16;
        return Ok(sign | m);
    }
    let e = a.log2().floor() as i32;
    let mut e = e.clamp(-14, 15);
    let mut m = (a / 2f64.powi(e) - 1.0) * 1024.0;
    m = m.round_ties_even();
    if m >= 1024.0 {
        m = 0.0;
        e += 1;
    }
    Ok(sign | (((e + 15) as u16) << 10) | m as u16)
}

fn from_half(bits: u16) -> f64 {
    let sign = if bits & 0x8000 != 0 { -1.0 } else { 1.0 };
    let exp = i32::from((bits >> 10) & 0x1f);
    let frac = f64::from(bits & 0x3ff);
    sign * match exp {
        0 => frac * 2f64.powi(-24),
        31 if frac == 0.0 => f64::INFINITY,
        31 => f64::NAN,
        e => (1.0 + frac / 1024.0) * 2f64.powi(e - 15),
    }
}

fn range_error(code: char, lo: i128, hi: i128) -> StructError {
    error(format!("'{code}' format requires {lo} <= number <= {hi}"))
}

pub(crate) fn pack(l: &Layout, values: &[Value]) -> Result<Vec<u8>, StructError> {
    if values.len() != l.values() {
        return Err(error(format!("pack expected {} items for packing (got {})", l.values(), values.len())));
    }
    let mut out = vec![0u8; l.size];
    let mut next = values.iter();
    for item in &l.items {
        let at = item.offset;
        match item.code {
            'x' => continue,
            's' | 'p' => {
                let Some(Value::Bytes(b)) = next.next() else {
                    return Err(error(format!("argument for '{}' must be a bytes object", item.code)));
                };
                if item.code == 's' {
                    let n = b.len().min(item.count);
                    out[at..at + n].copy_from_slice(&b[..n]);
                } else if item.count > 0 {
                    let n = b.len().min(item.count - 1).min(255);
                    out[at] = n as u8;
                    out[at + 1..at + 1 + n].copy_from_slice(&b[..n]);
                }
                continue;
            }
            _ => {}
        }
        let size = item.width;
        for k in 0..item.count {
            let v = next.next().ok_or_else(|| error("pack expected more items"))?;
            let at = at + k * size;
            let mut put = |bytes: &[u8]| {
                let dst = &mut out[at..at + bytes.len()];
                dst.copy_from_slice(bytes);
                if l.big {
                    dst.reverse();
                }
            };
            match item.code {
                'c' => match v {
                    Value::Bytes(b) if b.len() == 1 => put(&b[..]),
                    _ => return Err(error("char format requires a bytes object of length 1")),
                },
                '?' => put(&[u8::from(v.truthy())]),
                'e' => put(&to_half(float_of(v)?)?.to_le_bytes()),
                'f' => put(&(float_of(v)? as f32).to_le_bytes()),
                'd' => put(&float_of(v)?.to_le_bytes()),
                code => {
                    let n = int_of(v)?;
                    let signed = code.is_ascii_lowercase();
                    let bits = (size * 8) as u32;
                    let (lo, hi) = if signed { (-(1i128 << (bits - 1)), (1i128 << (bits - 1)) - 1) } else { (0, (1i128 << bits) - 1) };
                    if n < lo || n > hi {
                        return Err(range_error(code, lo, hi));
                    }
                    put(&n.to_le_bytes()[..size]);
                }
            }
        }
    }
    Ok(out)
}

pub(crate) fn unpack(l: &Layout, data: &[u8]) -> Vec<Value> {
    let mut out = Vec::with_capacity(l.values());
    for item in &l.items {
        let at = item.offset;
        match item.code {
            'x' => continue,
            's' => {
                out.push(Value::bytes(&data[at..at + item.count]));
                continue;
            }
            'p' => {
                if item.count == 0 {
                    out.push(Value::bytes(&[]));
                } else {
                    let n = usize::from(data[at]).min(item.count - 1);
                    out.push(Value::bytes(&data[at + 1..at + 1 + n]));
                }
                continue;
            }
            _ => {}
        }
        let size = item.width;
        for k in 0..item.count {
            let at = at + k * size;
            let mut b = [0u8; 16];
            b[..size].copy_from_slice(&data[at..at + size]);
            if l.big {
                b[..size].reverse();
            }
            let v = match item.code {
                'c' => Value::bytes(&b[..1]),
                '?' => Value::Bool(b[0] != 0),
                'e' => Value::Float(from_half(u16::from_le_bytes([b[0], b[1]]))),
                'f' => Value::Float(f64::from(f32::from_le_bytes([b[0], b[1], b[2], b[3]]))),
                'd' => Value::Float(f64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]])),
                code => {
                    let raw = u128::from_le_bytes(b);
                    let bits = size * 8;
                    let n: i128 = if code.is_ascii_lowercase() && bits > 0 && raw >> (bits - 1) & 1 == 1 {
                        raw as i128 - (1i128 << bits)
                    } else {
                        raw as i128
                    };
                    super::bigint::int_value(num_bigint::BigInt::from(n))
                }
            };
            out.push(v);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_and_round_trips() {
        assert_eq!(layout("<4sIhd").unwrap().size, 18);
        assert_eq!(layout("=bil?qhP").err().map(|e| e.1), Some("bad char in struct format".into()));
        let l = layout(">HI2s").unwrap();
        let v = unpack(&l, &[0, 5, 0, 0, 1, 0, b'o', b'k']);
        assert_eq!(v[0].as_int(), Some(5));
        assert_eq!(v[1].as_int(), Some(256));
        let packed = pack(&l, &v).unwrap();
        assert_eq!(packed, vec![0, 5, 0, 0, 1, 0, b'o', b'k']);
        assert_eq!(to_half(1.5).unwrap(), 0x3E00);
        assert_eq!(to_half(65504.0).unwrap(), 0x7BFF);
        assert!(to_half(1e10).is_err());
    }
}
