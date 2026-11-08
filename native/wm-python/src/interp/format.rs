//! Port of PyFormat.kt: format specs for f-string placeholders,
//! `"{:...}".format(...)` and `format(value, spec)`, all one implementation.
//!
//! Grammar (Python's, minus locale-aware `n` and the `#` alternate form):
//!     [[fill]align][sign][0][width][grouping][.precision][type]

use super::builtins::{is_scalar, to_radix};
use super::error::PyError;
use super::text::{c_exp, c_fixed, c_general, char_is_number, grapheme_count, graphemes, parse_swift_int};
use super::value::{py_str, Value};

struct Spec {
    fill: String,
    align: Option<&'static str>,
    sign: &'static str,
    width: Option<usize>,
    grouping: Option<String>,
    precision: Option<usize>,
    ty: Option<String>,
}

fn align_of(s: &str) -> Option<&'static str> {
    match s {
        "<" => Some("<"),
        ">" => Some(">"),
        "^" => Some("^"),
        "=" => Some("="),
        _ => None,
    }
}

fn parse_spec(spec: &str) -> Result<Spec, PyError> {
    let chars = graphemes(spec);
    let mut s = Spec { fill: " ".into(), align: None, sign: "-", width: None, grouping: None, precision: None, ty: None };
    let mut i = 0;
    // fill+align, or a bare align.
    if chars.len() >= 2 && align_of(chars[1]).is_some() {
        s.fill = chars[0].to_string();
        s.align = align_of(chars[1]);
        i = 2;
    } else if !chars.is_empty() && align_of(chars[0]).is_some() {
        s.align = align_of(chars[0]);
        i = 1;
    }
    if i < chars.len() {
        s.sign = match chars[i] {
            "+" => "+",
            "-" => "-",
            " " => " ",
            _ => "",
        };
        if s.sign.is_empty() {
            s.sign = "-";
        } else {
            i += 1;
        }
    }
    if i < chars.len() && chars[i] == "#" {
        return Err(PyError::new("the '#' format option is not supported", "ValueError"));
    }
    if i < chars.len() && chars[i] == "0" {
        if s.align.is_none() {
            s.align = Some("=");
            s.fill = "0".into();
        }
        i += 1;
    }
    let mut digits = String::new();
    while i < chars.len() && char_is_number(chars[i]) {
        digits.push_str(chars[i]);
        i += 1;
    }
    if !digits.is_empty() {
        s.width = parse_swift_int(&digits, 10).map(|w| w.clamp(0, 10_000_000) as usize);
    }
    if i < chars.len() && (chars[i] == "," || chars[i] == "_") {
        s.grouping = Some(chars[i].to_string());
        i += 1;
    }
    if i < chars.len() && chars[i] == "." {
        i += 1;
        let mut p = String::new();
        while i < chars.len() && char_is_number(chars[i]) {
            p.push_str(chars[i]);
            i += 1;
        }
        s.precision = Some(
            parse_swift_int(&p, 10)
                .map(|v| v.clamp(0, 10_000) as usize)
                .ok_or_else(|| PyError::new("format precision needs a number", "ValueError"))?,
        );
    }
    if i < chars.len() {
        s.ty = Some(chars[i].to_string());
        i += 1;
    }
    if i != chars.len() {
        return Err(PyError::new(format!("invalid format spec '{spec}'"), "ValueError"));
    }
    Ok(s)
}

/// Apply a format spec to a value. An empty spec means `str(value)`.
pub(crate) fn py_format(value: &Value, spec: &str) -> Result<String, PyError> {
    if spec.is_empty() {
        return Ok(py_str(value));
    }
    let mut s = parse_spec(spec)?;
    match s.ty.as_deref() {
        Some("s") => Ok(padded(&py_str(value), &s, false, 0)),
        Some("d" | "b" | "o" | "x" | "X" | "c") => integer_formatted(value, &s),
        Some("f" | "F" | "e" | "E" | "g" | "G" | "%") => float_formatted(value, &s),
        None => match value {
            // No type: numbers format as numbers, everything else (bool too) as text.
            Value::Int(_) => {
                s.ty = Some("d".into());
                integer_formatted(value, &s)
            }
            Value::Float(_) => {
                s.ty = Some("g".into());
                float_formatted(value, &s)
            }
            _ => Ok(padded(&py_str(value), &s, false, 0)),
        },
        Some(t) => Err(PyError::new(format!("unknown format code '{t}'"), "ValueError")),
    }
}

fn integer_formatted(value: &Value, spec: &Spec) -> Result<String, PyError> {
    let ty = spec.ty.as_deref().unwrap_or("d");
    let Some(i) = value.as_int() else {
        if value.as_float().is_some() {
            return Err(PyError::new(format!("cannot format a float with '{ty}'"), "ValueError"));
        }
        return Err(PyError::new(format!("cannot format '{}' as a number", value.type_name()), "TypeError"));
    };
    if ty == "c" {
        if !is_scalar(i) {
            return Err(PyError::new("chr() arg not in range", "ValueError"));
        }
        return Ok(padded(&char::from_u32(i as u32).unwrap().to_string(), spec, false, 0));
    }
    let magnitude = i.unsigned_abs();
    let mut digits = match ty {
        "b" => to_radix(magnitude, 2),
        "o" => to_radix(magnitude, 8),
        "x" => to_radix(magnitude, 16),
        "X" => to_radix(magnitude, 16).to_uppercase(),
        _ => magnitude.to_string(),
    };
    if let Some(g) = &spec.grouping {
        digits = grouped(&digits, g);
    }
    let prefix = sign_prefix(i < 0, spec);
    Ok(padded(&format!("{prefix}{digits}"), spec, true, prefix.len()))
}

fn float_formatted(value: &Value, spec: &Spec) -> Result<String, PyError> {
    let mut d = value
        .as_float()
        .ok_or_else(|| PyError::new(format!("cannot format '{}' as a number", value.type_name()), "TypeError"))?;
    let negative = d < 0.0 || (d == 0.0 && d.is_sign_negative());
    let ty = spec.ty.as_deref().unwrap_or("g");
    if ty == "%" {
        d *= 100.0;
    }
    let magnitude = d.abs();
    let mut body = if magnitude.is_nan() {
        "nan".to_string()
    } else if magnitude.is_infinite() {
        "inf".to_string()
    } else {
        let mut b = match ty {
            "e" | "E" => {
                let e = c_exp(magnitude, spec.precision.unwrap_or(6));
                if ty == "E" {
                    e.to_uppercase()
                } else {
                    e
                }
            }
            "g" | "G" => {
                let g = c_general(magnitude, spec.precision.unwrap_or(6).max(1));
                if ty == "G" {
                    g.to_uppercase()
                } else {
                    g
                }
            }
            _ => c_fixed(magnitude, spec.precision.unwrap_or(6)),
        };
        if let Some(g) = &spec.grouping {
            b = match b.find('.') {
                None => grouped(&b, g),
                Some(dot) => grouped(&b[..dot], g) + &b[dot..],
            };
        }
        b
    };
    if ty == "%" {
        body.push('%');
    }
    let prefix = sign_prefix(negative, spec);
    Ok(padded(&format!("{prefix}{body}"), spec, true, prefix.len()))
}

fn sign_prefix(negative: bool, spec: &Spec) -> &'static str {
    if negative {
        return "-";
    }
    match spec.sign {
        "+" => "+",
        " " => " ",
        _ => "",
    }
}

/// Insert [separator] every three characters from the right.
fn grouped(digits: &str, separator: &str) -> String {
    let chars: Vec<char> = digits.chars().collect();
    if chars.len() <= 3 {
        return digits.to_string();
    }
    let mut out = String::new();
    for (i, c) in chars.iter().enumerate() {
        let from_right = chars.len() - i;
        if i > 0 && from_right % 3 == 0 {
            out.push_str(separator);
        }
        out.push(*c);
    }
    out
}

/// Pad to the spec's width. Numbers default to right alignment, everything
/// else to left; `=` keeps the sign in front and pads after it.
fn padded(s: &str, spec: &Spec, numeric: bool, sign_len: usize) -> String {
    let Some(width) = spec.width else { return s.to_string() };
    let count = grapheme_count(s);
    if count >= width {
        return s.to_string();
    }
    let padding = width - count;
    let align = spec.align.unwrap_or(if numeric { ">" } else { "<" });
    match align {
        ">" => spec.fill.repeat(padding) + s,
        "^" => {
            let left = padding / 2;
            spec.fill.repeat(left) + s + &spec.fill.repeat(padding - left)
        }
        "=" => format!("{}{}{}", &s[..sign_len], spec.fill.repeat(padding), &s[sign_len..]),
        _ => s.to_string() + &spec.fill.repeat(padding),
    }
}
