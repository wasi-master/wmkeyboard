//! The Swift `String` and `Character` behaviour PyMini leans on (port of PyText.kt).
//!
//! PyMini's strings are Swift strings: `count`, indexing, slicing and iteration
//! go through `Character`, an extended grapheme cluster. `==` and `<` compare by
//! canonical equivalence (NFC). Case mapping is per scalar, and
//! `String(format:)` is C's printf.

use std::borrow::Cow;
use std::cmp::Ordering;
use std::hash::{Hash, Hasher};

use unicode_normalization::{is_nfc_quick, IsNormalized, UnicodeNormalization};
use unicode_properties::{GeneralCategory as Gc, UnicodeGeneralCategory};
use unicode_segmentation::UnicodeSegmentation;

/// Every scalar is its own cluster: nothing combines below U+0300 except CR LF.
fn is_simple(s: &str) -> bool {
    if s.is_ascii() {
        return !s.as_bytes().contains(&b'\r');
    }
    s.chars().all(|c| (c as u32) < 0x300 && c != '\r')
}

/// Byte offsets of the grapheme clusters of [s], plus `s.len()` at the end.
pub(crate) fn boundaries(s: &str) -> Vec<u32> {
    let mut out = Vec::with_capacity(s.len() + 1);
    if s.is_ascii() && !s.as_bytes().contains(&b'\r') {
        out.extend(0..s.len() as u32);
    } else if is_simple(s) {
        out.extend(s.char_indices().map(|(i, _)| i as u32));
    } else {
        out.extend(s.grapheme_indices(true).map(|(i, _)| i as u32));
    }
    out.push(s.len() as u32);
    out
}

/// The grapheme clusters of [s]: what Swift's `Array(s)` gives.
pub(crate) fn graphemes(s: &str) -> Vec<&str> {
    if is_simple(s) {
        let mut out = Vec::with_capacity(s.len());
        let mut it = s.char_indices().peekable();
        while let Some((i, c)) = it.next() {
            out.push(&s[i..i + c.len_utf8()]);
        }
        return out;
    }
    s.graphemes(true).collect()
}

/// Swift's `s.count`.
pub(crate) fn grapheme_count(s: &str) -> usize {
    if s.is_ascii() && !s.as_bytes().contains(&b'\r') {
        return s.len();
    }
    if is_simple(s) {
        return s.chars().count();
    }
    s.graphemes(true).count()
}

/// Whether `s.count > max`, without segmenting a string that can't be that long.
pub(crate) fn longer_than(s: &str, max: usize) -> bool {
    s.len() > max && grapheme_count(s) > max
}

/// Length in UTF-16 code units, the unit Kotlin's `String.length` counts.
pub(crate) fn utf16_len(s: &str) -> usize {
    s.chars().map(char::len_utf16).sum()
}

/// The longest prefix of [s] that is at most [max] UTF-16 units, never splitting a scalar.
pub(crate) fn take_utf16(s: &str, max: usize) -> &str {
    let mut units = 0;
    for (i, c) in s.char_indices() {
        units += c.len_utf16();
        if units > max {
            return &s[..i];
        }
    }
    s
}

pub(crate) fn nfc(s: &str) -> Cow<'_, str> {
    if s.is_ascii() || is_nfc_quick(s.chars()) == IsNormalized::Yes {
        Cow::Borrowed(s)
    } else {
        Cow::Owned(s.nfc().collect())
    }
}

/// Swift `String ==`: canonical equivalence, so `é` equals `e` + U+0301.
pub(crate) fn str_eq(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    if a.is_ascii() && b.is_ascii() {
        return false;
    }
    nfc(a) == nfc(b)
}

/// Hash consistent with [str_eq].
pub(crate) fn str_hash<H: Hasher>(s: &str, h: &mut H) {
    nfc(s).hash(h)
}

/// Swift `String <`: the NFC forms compared scalar by scalar.
pub(crate) fn str_compare(a: &str, b: &str) -> Ordering {
    if a == b {
        return Ordering::Equal;
    }
    nfc(a).cmp(&nfc(b))
}

// MARK: - Character properties (Swift's, which read the first scalar)

fn first_scalar(g: &str) -> Option<char> {
    g.chars().next()
}

fn is_single_scalar(g: &str) -> bool {
    let mut it = g.chars();
    it.next().is_some() && it.next().is_none()
}

/// `Character.isLetter`: the first scalar is Alphabetic.
pub(crate) fn char_is_letter(g: &str) -> bool {
    first_scalar(g).is_some_and(char::is_alphabetic)
}

/// `Character.isNumber`: the first scalar has a numeric type (Nd, Nl, No).
pub(crate) fn char_is_number(g: &str) -> bool {
    first_scalar(g).is_some_and(char::is_numeric)
}

/// Unicode White_Space, which is what Swift checks.
pub(crate) fn scalar_is_whitespace(c: char) -> bool {
    matches!(c as u32,
        0x09..=0x0D | 0x20 | 0x85 | 0xA0 | 0x1680 | 0x2000..=0x200A | 0x2028 | 0x2029 | 0x202F | 0x205F | 0x3000)
}

/// `Character.isWhitespace`.
pub(crate) fn char_is_whitespace(g: &str) -> bool {
    first_scalar(g).is_some_and(scalar_is_whitespace)
}

/// `Character.isHexDigit`: ASCII and fullwidth hex digits.
pub(crate) fn char_is_hex_digit(g: &str) -> bool {
    if !is_single_scalar(g) {
        return false;
    }
    matches!(first_scalar(g).unwrap() as u32,
        0x30..=0x39 | 0x61..=0x66 | 0x41..=0x46 | 0xFF10..=0xFF19 | 0xFF21..=0xFF26 | 0xFF41..=0xFF46)
}

fn scalar_is_cased(c: char) -> bool {
    c.is_uppercase() || c.is_lowercase() || c.general_category() == Gc::TitlecaseLetter
}

/// `Character.isUppercase`.
pub(crate) fn char_is_uppercase(g: &str) -> bool {
    if is_single_scalar(g) && first_scalar(g).unwrap().is_uppercase() {
        return true;
    }
    swift_upper(g) == g && g.chars().any(scalar_is_cased)
}

/// `String.uppercased()`: each scalar's full mapping.
pub(crate) fn swift_upper(s: &str) -> String {
    s.to_uppercase()
}

/// `String.lowercased()`: each scalar's full mapping on its own, with no
/// Final_Sigma context rule.
pub(crate) fn swift_lower(s: &str) -> String {
    if s.is_ascii() {
        return s.to_ascii_lowercase();
    }
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        out.extend(c.to_lowercase());
    }
    out
}

// MARK: - C printf, which is what Swift's String(format:) is

fn is_negative(d: f64) -> bool {
    d < 0.0 || (d == 0.0 && d.is_sign_negative())
}

fn signed(d: f64, body: String) -> String {
    if is_negative(d) {
        format!("-{body}")
    } else {
        body
    }
}

/// `%.{prec}f`, rounding the exact binary value half to even.
pub(crate) fn c_fixed(d: f64, prec: usize) -> String {
    if d.is_nan() {
        return "nan".into();
    }
    if d.is_infinite() {
        return if d < 0.0 { "-inf".into() } else { "inf".into() };
    }
    signed(d, format!("{:.*}", prec, d.abs()))
}

/// Significant digits and decimal exponent of |d| rounded to [digits] figures.
fn rounded_digits(magnitude: f64, digits: usize) -> (String, i32) {
    let e = format!("{:.*e}", digits.saturating_sub(1), magnitude);
    let (mantissa, exponent) = e.split_once('e').unwrap();
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    (digits, exponent.parse().unwrap_or(0))
}

fn exponent_text(e: i32) -> String {
    let sign = if e < 0 { '-' } else { '+' };
    let a = e.unsigned_abs();
    if a < 10 {
        format!("e{sign}0{a}")
    } else {
        format!("e{sign}{a}")
    }
}

/// `%.{prec}e`.
pub(crate) fn c_exp(d: f64, prec: usize) -> String {
    if d.is_nan() {
        return "nan".into();
    }
    if d.is_infinite() {
        return if d < 0.0 { "-inf".into() } else { "inf".into() };
    }
    let magnitude = d.abs();
    if magnitude == 0.0 {
        let frac = if prec > 0 { format!(".{}", "0".repeat(prec)) } else { String::new() };
        return signed(d, format!("0{frac}e+00"));
    }
    let (digits, exponent) = rounded_digits(magnitude, prec + 1);
    let mantissa = if prec > 0 { format!("{}.{}", &digits[..1], &digits[1..]) } else { digits[..1].to_string() };
    signed(d, mantissa + &exponent_text(exponent))
}

/// `%.{prec}g`: fixed or exponent form, whichever C picks, trailing zeros dropped.
pub(crate) fn c_general(d: f64, prec: usize) -> String {
    if d.is_nan() {
        return "nan".into();
    }
    if d.is_infinite() {
        return if d < 0.0 { "-inf".into() } else { "inf".into() };
    }
    let p = if prec == 0 { 1 } else { prec };
    let magnitude = d.abs();
    if magnitude == 0.0 {
        return signed(d, "0".into());
    }
    let exponent = rounded_digits(magnitude, p).1;
    if (p as i32) > exponent && exponent >= -4 {
        signed(d, strip_zeros(&c_fixed(magnitude, (p as i32 - 1 - exponent) as usize)))
    } else {
        let e = c_exp(magnitude, p - 1);
        let at = e.find('e').unwrap();
        signed(d, strip_zeros(&e[..at]) + &e[at..])
    }
}

fn strip_zeros(s: &str) -> String {
    if !s.contains('.') {
        return s.to_string();
    }
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

/// Swift's `Double(text)`: optional sign, digits with an optional fraction and
/// exponent, or inf/infinity/nan. No whitespace, no `_`.
pub(crate) fn parse_swift_double(text: &str) -> Option<f64> {
    if text.is_empty() {
        return None;
    }
    let (negative, body) = match text.as_bytes()[0] {
        b'+' => (false, &text[1..]),
        b'-' => (true, &text[1..]),
        _ => (false, text),
    };
    match body.to_ascii_lowercase().as_str() {
        "inf" | "infinity" => return Some(if negative { f64::NEG_INFINITY } else { f64::INFINITY }),
        "nan" => return Some(f64::NAN),
        _ => {}
    }
    if !is_decimal(body) {
        return None;
    }
    let v: f64 = body.parse().ok()?;
    Some(if negative { -v } else { v })
}

/// `(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?`
fn is_decimal(s: &str) -> bool {
    let b = s.as_bytes();
    let mut i = 0;
    let digits = |i: &mut usize| {
        let start = *i;
        while *i < b.len() && b[*i].is_ascii_digit() {
            *i += 1;
        }
        *i - start
    };
    if digits(&mut i) > 0 {
        if i < b.len() && b[i] == b'.' {
            i += 1;
            digits(&mut i);
        }
    } else if i < b.len() && b[i] == b'.' {
        i += 1;
        if digits(&mut i) == 0 {
            return false;
        }
    } else {
        return false;
    }
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        i += 1;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            i += 1;
        }
        if digits(&mut i) == 0 {
            return false;
        }
    }
    i == b.len()
}

/// Swift's `Int(text, radix:)`: optional sign then digits, nothing else.
pub(crate) fn parse_swift_int(text: &str, radix: u32) -> Option<i64> {
    if !(2..=36).contains(&radix) || text.is_empty() {
        return None;
    }
    let (sign, digits) = match text.as_bytes()[0] {
        b'+' => ("", &text[1..]),
        b'-' => ("-", &text[1..]),
        _ => ("", text),
    };
    if digits.is_empty() || !digits.chars().all(|c| c.is_ascii() && c.is_digit(radix)) {
        return None;
    }
    i64::from_str_radix(&format!("{sign}{digits}"), radix).ok()
}

fn is_space_separator(c: char) -> bool {
    matches!(c as u32, 0x20 | 0xA0 | 0x1680 | 0x2000..=0x200A | 0x202F | 0x205F | 0x3000)
}

/// Foundation's `trimmingCharacters(in: .whitespaces)`: tab and the Zs space separators.
pub(crate) fn trim_whitespaces(s: &str) -> &str {
    s.trim_matches(|c: char| c == '\t' || is_space_separator(c))
}

/// Unicode titlecase mapping of one scalar (full mapping where there is only an upper one, as for ß).
fn titlecase_mapping(c: char) -> String {
    let special = match c as u32 {
        0x01C4..=0x01C6 => Some(0x01C5),
        0x01C7..=0x01C9 => Some(0x01C8),
        0x01CA..=0x01CC => Some(0x01CB),
        0x01F1..=0x01F3 => Some(0x01F2),
        0x1F80..=0x1F87 => Some(c as u32 + 8),
        0x1F90..=0x1F97 => Some(c as u32 + 8),
        0x1FA0..=0x1FA7 => Some(c as u32 + 8),
        0x1FB3 => Some(0x1FBC),
        0x1FC3 => Some(0x1FCC),
        0x1FF3 => Some(0x1FFC),
        _ => None,
    };
    if let Some(t) = special.and_then(char::from_u32) {
        return t.to_string();
    }
    let upper: Vec<char> = c.to_uppercase().collect();
    if upper.len() <= 1 {
        return upper.into_iter().collect();
    }
    let mut out = String::new();
    out.push(upper[0]);
    for u in &upper[1..] {
        out.extend(u.to_lowercase());
    }
    out
}

/// CoreFoundation's case-ignorable subset: marks, format characters, modifiers, and the apostrophe.
fn cf_case_ignorable(c: char) -> bool {
    match c.general_category() {
        Gc::NonspacingMark | Gc::EnclosingMark | Gc::Format | Gc::ModifierLetter | Gc::ModifierSymbol => true,
        _ => c == '\'' || c == '\u{2019}',
    }
}

/// Foundation's `String.capitalized`, PyMini's `title()`.
pub(crate) fn foundation_capitalized(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut last_cased = false;
    for c in s.chars() {
        match c.general_category() {
            Gc::UppercaseLetter | Gc::TitlecaseLetter => {
                if last_cased {
                    out.extend(c.to_lowercase());
                } else {
                    out.push(c);
                }
                last_cased = true;
            }
            Gc::LowercaseLetter => {
                if last_cased {
                    out.push(c);
                } else {
                    out.push_str(&titlecase_mapping(c));
                }
                last_cased = true;
            }
            _ => {
                out.push(c);
                if !cf_case_ignorable(c) {
                    last_cased = false;
                }
            }
        }
    }
    out
}

/// Foundation's `folding(options: [.caseInsensitive, .diacriticInsensitive])`,
/// PyMini's `casefold()`: marks stripped, then case folded.
pub(crate) fn fold_case_and_diacritics(s: &str) -> String {
    let stripped: String = s.nfd().filter(|c| c.general_category() != Gc::NonspacingMark).collect();
    // Per scalar, like Swift: no Final_Sigma rule, so "ΟΔΟΣ" folds to "οδοσ".
    let folded = swift_lower(&stripped.to_uppercase());
    folded.nfc().collect()
}

/// What a plugin's displayed text keeps: no control, format, surrogate or
/// private-use characters (PluginManifestCodec.displayText).
pub(crate) fn display_text(raw: &str, max_utf16: usize) -> String {
    let mut out = String::new();
    let mut units = 0;
    for c in raw.trim().chars() {
        if matches!(c.general_category(), Gc::Control | Gc::Format | Gc::Surrogate | Gc::PrivateUse) {
            continue;
        }
        if units + c.len_utf16() > max_utf16 {
            break;
        }
        units += c.len_utf16();
        out.push(c);
    }
    out.trim().to_string()
}

/// Java's `String.hashCode`, over UTF-16 units.
pub(crate) fn java_hash(s: &str) -> i32 {
    let mut h: i32 = 0;
    for u in s.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(u as i32);
    }
    h
}
