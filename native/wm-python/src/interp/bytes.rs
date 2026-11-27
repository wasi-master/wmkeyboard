//! The minimal `bytes` type (an addition to PyMini): the encodings below,
//! with CPython's error handlers, and hex in and out.
//!
//! Encodings: UTF-8 (and `utf-8-sig`), UTF-16 and UTF-32 (with a byte order
//! mark, or `-le` / `-be` without), ASCII, Latin-1, Windows-1252 and IBM
//! code page 437. Strings here cannot hold a lone surrogate, so
//! `surrogatepass` and `surrogateescape` are `strict`: encoding is the same,
//! as no string here has a surrogate, and decoding valid data is the same,
//! while bytes CPython would keep as surrogates raise UnicodeDecodeError
//! rather than being quietly lost.

pub(crate) enum ByteError {
    UnknownEncoding(String),
    Errors(String),
    Decode(String),
    Encode(String),
    Hex(String),
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Encoding {
    Utf8,
    Utf8Sig,
    Utf16,
    Utf16Le,
    Utf16Be,
    Utf32,
    Utf32Le,
    Utf32Be,
    Ascii,
    Latin1,
    Cp1252,
    Cp437,
    UnicodeEscape,
    RawUnicodeEscape,
}

impl Encoding {
    /// The name `codecs.lookup(...).name` gives, as CPython's.
    pub(crate) fn name(self) -> &'static str {
        match self {
            Encoding::Utf8 => "utf-8",
            Encoding::Utf8Sig => "utf-8-sig",
            Encoding::Utf16 => "utf-16",
            Encoding::Utf16Le => "utf-16-le",
            Encoding::Utf16Be => "utf-16-be",
            Encoding::Utf32 => "utf-32",
            Encoding::Utf32Le => "utf-32-le",
            Encoding::Utf32Be => "utf-32-be",
            Encoding::Ascii => "ascii",
            Encoding::Latin1 => "iso8859-1",
            Encoding::Cp1252 => "cp1252",
            Encoding::Cp437 => "cp437",
            Encoding::UnicodeEscape => "unicode-escape",
            Encoding::RawUnicodeEscape => "raw-unicode-escape",
        }
    }

    /// The codec name error messages carry.
    fn label(self) -> &'static str {
        match self {
            Encoding::Latin1 => "latin-1",
            Encoding::Cp1252 | Encoding::Cp437 => "charmap",
            Encoding::UnicodeEscape => "unicodeescape",
            Encoding::RawUnicodeEscape => "rawunicodeescape",
            other => other.name(),
        }
    }
}

pub(crate) fn encoding(name: &str) -> Result<Encoding, ByteError> {
    let n: String = name.trim().chars().map(|c| if c == '-' || c == ' ' { '_' } else { c.to_ascii_lowercase() }).collect();
    Ok(match n.as_str() {
        "utf_8" | "utf8" | "u8" | "utf" | "cp65001" => Encoding::Utf8,
        "utf_8_sig" | "utf8_sig" => Encoding::Utf8Sig,
        "utf_16" | "utf16" | "u16" => Encoding::Utf16,
        "utf_16_le" | "utf_16le" | "utf16le" | "utf16_le" => Encoding::Utf16Le,
        "utf_16_be" | "utf_16be" | "utf16be" | "utf16_be" => Encoding::Utf16Be,
        "utf_32" | "utf32" | "u32" => Encoding::Utf32,
        "utf_32_le" | "utf_32le" | "utf32le" | "utf32_le" => Encoding::Utf32Le,
        "utf_32_be" | "utf_32be" | "utf32be" | "utf32_be" => Encoding::Utf32Be,
        "ascii" | "us_ascii" | "646" | "us" | "ansi_x3.4_1968" => Encoding::Ascii,
        "latin_1" | "latin1" | "latin" | "l1" | "iso_8859_1" | "iso8859_1" | "iso8859" | "8859" | "cp819" | "iso_ir_100" => {
            Encoding::Latin1
        }
        "cp1252" | "windows_1252" | "1252" => Encoding::Cp1252,
        "cp437" | "ibm437" | "437" => Encoding::Cp437,
        "unicode_escape" | "unicodeescape" => Encoding::UnicodeEscape,
        "raw_unicode_escape" | "rawunicodeescape" => Encoding::RawUnicodeEscape,
        _ => return Err(ByteError::UnknownEncoding(name.to_string())),
    })
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Handler {
    Strict,
    Ignore,
    Replace,
    BackslashReplace,
    XmlCharRefReplace,
    NameReplace,
}

fn handler(errors: &str, encoding: bool) -> Result<Handler, ByteError> {
    Ok(match errors {
        "strict" | "surrogatepass" | "surrogateescape" => Handler::Strict,
        "ignore" => Handler::Ignore,
        "replace" => Handler::Replace,
        "backslashreplace" => Handler::BackslashReplace,
        "xmlcharrefreplace" if encoding => Handler::XmlCharRefReplace,
        "namereplace" if encoding => Handler::NameReplace,
        other => return Err(ByteError::Errors(other.to_string())),
    })
}

/// Windows-1252's 0x80-0x9F; the rest of its bytes are Latin-1.
const CP1252_HIGH: [Option<char>; 32] = [
    Some('\u{20AC}'), None, Some('\u{201A}'), Some('\u{0192}'), Some('\u{201E}'), Some('\u{2026}'), Some('\u{2020}'), Some('\u{2021}'),
    Some('\u{02C6}'), Some('\u{2030}'), Some('\u{0160}'), Some('\u{2039}'), Some('\u{0152}'), None, Some('\u{017D}'), None,
    None, Some('\u{2018}'), Some('\u{2019}'), Some('\u{201C}'), Some('\u{201D}'), Some('\u{2022}'), Some('\u{2013}'), Some('\u{2014}'),
    Some('\u{02DC}'), Some('\u{2122}'), Some('\u{0161}'), Some('\u{203A}'), Some('\u{0153}'), None, Some('\u{017E}'), Some('\u{0178}'),
];

/// IBM code page 437's 0x80-0xFF, the box-drawing set zip archives name files in; 0x00-0x7F are ASCII.
const CP437_HIGH: [char; 128] = [
    '\u{00C7}', '\u{00FC}', '\u{00E9}', '\u{00E2}', '\u{00E4}', '\u{00E0}', '\u{00E5}', '\u{00E7}',
    '\u{00EA}', '\u{00EB}', '\u{00E8}', '\u{00EF}', '\u{00EE}', '\u{00EC}', '\u{00C4}', '\u{00C5}',
    '\u{00C9}', '\u{00E6}', '\u{00C6}', '\u{00F4}', '\u{00F6}', '\u{00F2}', '\u{00FB}', '\u{00F9}',
    '\u{00FF}', '\u{00D6}', '\u{00DC}', '\u{00A2}', '\u{00A3}', '\u{00A5}', '\u{20A7}', '\u{0192}',
    '\u{00E1}', '\u{00ED}', '\u{00F3}', '\u{00FA}', '\u{00F1}', '\u{00D1}', '\u{00AA}', '\u{00BA}',
    '\u{00BF}', '\u{2310}', '\u{00AC}', '\u{00BD}', '\u{00BC}', '\u{00A1}', '\u{00AB}', '\u{00BB}',
    '\u{2591}', '\u{2592}', '\u{2593}', '\u{2502}', '\u{2524}', '\u{2561}', '\u{2562}', '\u{2556}',
    '\u{2555}', '\u{2563}', '\u{2551}', '\u{2557}', '\u{255D}', '\u{255C}', '\u{255B}', '\u{2510}',
    '\u{2514}', '\u{2534}', '\u{252C}', '\u{251C}', '\u{2500}', '\u{253C}', '\u{255E}', '\u{255F}',
    '\u{255A}', '\u{2554}', '\u{2569}', '\u{2566}', '\u{2560}', '\u{2550}', '\u{256C}', '\u{2567}',
    '\u{2568}', '\u{2564}', '\u{2565}', '\u{2559}', '\u{2558}', '\u{2552}', '\u{2553}', '\u{256B}',
    '\u{256A}', '\u{2518}', '\u{250C}', '\u{2588}', '\u{2584}', '\u{258C}', '\u{2590}', '\u{2580}',
    '\u{03B1}', '\u{00DF}', '\u{0393}', '\u{03C0}', '\u{03A3}', '\u{03C3}', '\u{00B5}', '\u{03C4}',
    '\u{03A6}', '\u{0398}', '\u{03A9}', '\u{03B4}', '\u{221E}', '\u{03C6}', '\u{03B5}', '\u{2229}',
    '\u{2261}', '\u{00B1}', '\u{2265}', '\u{2264}', '\u{2320}', '\u{2321}', '\u{00F7}', '\u{2248}',
    '\u{00B0}', '\u{2219}', '\u{00B7}', '\u{221A}', '\u{207F}', '\u{00B2}', '\u{25A0}', '\u{00A0}',
];

fn cp437_byte(c: char) -> Option<u8> {
    if (c as u32) < 0x80 {
        return Some(c as u8);
    }
    CP437_HIGH.iter().position(|&x| x == c).map(|i| 0x80 + i as u8)
}

fn cp1252_byte(c: char) -> Option<u8> {
    let u = c as u32;
    if u < 0x80 || (0xA0..0x100).contains(&u) {
        return Some(u as u8);
    }
    CP1252_HIGH.iter().position(|&x| x == Some(c)).map(|i| 0x80 + i as u8)
}

fn cp1252_char(b: u8) -> Option<char> {
    if (0x80..0xA0).contains(&b) {
        CP1252_HIGH[(b - 0x80) as usize]
    } else {
        Some(b as char)
    }
}

/// A character as CPython's error messages quote it: `'\xe9'`, `'€'`.
fn quoted(c: char) -> String {
    let u = c as u32;
    if u < 0x100 {
        format!("'\\x{u:02x}'")
    } else if u < 0x10000 {
        format!("'\\u{u:04x}'")
    } else {
        format!("'\\U{u:08x}'")
    }
}

fn backslashed(c: char) -> String {
    let u = c as u32;
    if u < 0x100 {
        format!("\\x{u:02x}")
    } else if u < 0x10000 {
        format!("\\u{u:04x}")
    } else {
        format!("\\U{u:08x}")
    }
}

/// [s] in the encoding [name], unencodable characters handled as [errors] says.
pub(crate) fn encode_with(s: &str, name: &str, errors: &str) -> Result<Vec<u8>, ByteError> {
    let enc = encoding(name)?;
    let one_byte = |c: char| -> Option<u8> {
        match enc {
            Encoding::Ascii => (c as u32) < 0x80,
            Encoding::Latin1 => (c as u32) < 0x100,
            Encoding::Cp437 => return cp437_byte(c),
            _ => return cp1252_byte(c),
        }
        .then_some(c as u8)
    };
    Ok(match enc {
        Encoding::Utf8 => s.as_bytes().to_vec(),
        Encoding::Utf8Sig => [&[0xEF, 0xBB, 0xBF][..], s.as_bytes()].concat(),
        Encoding::Utf16 => [0xFF, 0xFE].into_iter().chain(s.encode_utf16().flat_map(u16::to_le_bytes)).collect(),
        Encoding::Utf16Le => s.encode_utf16().flat_map(u16::to_le_bytes).collect(),
        Encoding::Utf16Be => s.encode_utf16().flat_map(u16::to_be_bytes).collect(),
        Encoding::Utf32 => [0xFF, 0xFE, 0, 0].into_iter().chain(s.chars().flat_map(|c| (c as u32).to_le_bytes())).collect(),
        Encoding::Utf32Le => s.chars().flat_map(|c| (c as u32).to_le_bytes()).collect(),
        Encoding::Utf32Be => s.chars().flat_map(|c| (c as u32).to_be_bytes()).collect(),
        Encoding::UnicodeEscape => {
            let mut out = String::with_capacity(s.len());
            for c in s.chars() {
                match c {
                    '\\' => out.push_str("\\\\"),
                    '\t' => out.push_str("\\t"),
                    '\n' => out.push_str("\\n"),
                    '\r' => out.push_str("\\r"),
                    ' '..='~' => out.push(c),
                    _ => out.push_str(&backslashed(c)),
                }
            }
            out.into_bytes()
        }
        Encoding::RawUnicodeEscape => {
            let mut out = Vec::with_capacity(s.len());
            for c in s.chars() {
                if (c as u32) < 0x100 {
                    out.push(c as u8);
                } else {
                    out.extend_from_slice(backslashed(c).as_bytes());
                }
            }
            out
        }
        Encoding::Ascii | Encoding::Latin1 | Encoding::Cp1252 | Encoding::Cp437 => {
            if let Some(b) = s.chars().map(one_byte).collect::<Option<Vec<u8>>>() {
                return Ok(b);
            }
            let how = handler(errors, true)?;
            let chars: Vec<char> = s.chars().collect();
            let mut out = Vec::with_capacity(chars.len());
            let mut i = 0;
            while i < chars.len() {
                if let Some(b) = one_byte(chars[i]) {
                    out.push(b);
                    i += 1;
                    continue;
                }
                let run = chars[i..].iter().take_while(|&&c| one_byte(c).is_none()).count();
                match how {
                    Handler::Strict => {
                        let reason = match enc {
                            Encoding::Ascii => "ordinal not in range(128)",
                            Encoding::Latin1 => "ordinal not in range(256)",
                            _ => "character maps to <undefined>",
                        };
                        // The charmap codec stops at the first; the others name the whole run.
                        let at = if run == 1 || matches!(enc, Encoding::Cp1252 | Encoding::Cp437) {
                            format!("character {} in position {i}", quoted(chars[i]))
                        } else {
                            format!("characters in position {i}-{}", i + run - 1)
                        };
                        return Err(ByteError::Encode(format!("'{}' codec can't encode {at}: {reason}", enc.label())));
                    }
                    Handler::Ignore => {}
                    Handler::Replace => out.extend(std::iter::repeat_n(b'?', run)),
                    _ => {
                        for &c in &chars[i..i + run] {
                            let text = match how {
                                Handler::XmlCharRefReplace => format!("&#{};", c as u32),
                                Handler::NameReplace => match super::charnames::name_of(c) {
                                    Some(n) => format!("\\N{{{n}}}"),
                                    None => backslashed(c),
                                },
                                _ => backslashed(c),
                            };
                            out.extend_from_slice(text.as_bytes());
                        }
                    }
                }
                i += run;
            }
            out
        }
    })
}

/// Decodes [b]; `errors` is one of CPython's handlers (strict, ignore,
/// replace, backslashreplace).
pub(crate) fn decode(b: &[u8], name: &str, errors: &str) -> Result<String, ByteError> {
    let enc = encoding(name)?;
    let how = handler(errors, false)?;
    let label = enc.label();
    // What to put in place of bytes [start, end) that do not decode.
    let bad = |out: &mut String, bytes: &[u8], start: usize, reason: &str| -> Result<(), ByteError> {
        match how {
            Handler::Strict => {
                let at = if bytes.len() == 1 {
                    format!("byte 0x{:02x} in position {start}", bytes[0])
                } else {
                    format!("bytes in position {start}-{}", start + bytes.len() - 1)
                };
                Err(ByteError::Decode(format!("'{label}' codec can't decode {at}: {reason}")))
            }
            Handler::Ignore => Ok(()),
            Handler::BackslashReplace => {
                for x in bytes {
                    out.push_str(&format!("\\x{x:02x}"));
                }
                Ok(())
            }
            _ => {
                out.push('\u{FFFD}');
                Ok(())
            }
        }
    };
    let mut out = String::with_capacity(b.len());
    match enc {
        Encoding::Utf8 | Encoding::Utf8Sig => {
            let body = if enc == Encoding::Utf8Sig { b.strip_prefix(&[0xEF, 0xBB, 0xBF][..]).unwrap_or(b) } else { b };
            if let Ok(s) = std::str::from_utf8(body) {
                return Ok(s.to_string());
            }
            let mut at = b.len() - body.len();
            for chunk in body.utf8_chunks() {
                out.push_str(chunk.valid());
                at += chunk.valid().len();
                let invalid = chunk.invalid();
                if invalid.is_empty() {
                    continue;
                }
                let lead = invalid[0];
                let reason = if at + invalid.len() == b.len() && utf8_width(lead) > invalid.len() {
                    "unexpected end of data"
                } else if utf8_width(lead) == 0 {
                    "invalid start byte"
                } else {
                    "invalid continuation byte"
                };
                // CPython reports the lead byte alone, then goes on from the next.
                if how == Handler::Strict {
                    let shown = if reason == "unexpected end of data" { invalid } else { &invalid[..1] };
                    bad(&mut out, shown, at, reason)?;
                }
                bad(&mut out, invalid, at, reason)?;
                at += invalid.len();
            }
        }
        Encoding::UnicodeEscape | Encoding::RawUnicodeEscape => return unescape(b, enc == Encoding::RawUnicodeEscape, &bad),
        Encoding::Ascii | Encoding::Latin1 | Encoding::Cp1252 | Encoding::Cp437 => {
            for (i, &x) in b.iter().enumerate() {
                let c = match enc {
                    Encoding::Ascii => (x < 0x80).then_some(x as char),
                    Encoding::Latin1 => Some(x as char),
                    Encoding::Cp437 => Some(if x < 0x80 { x as char } else { CP437_HIGH[(x - 0x80) as usize] }),
                    _ => cp1252_char(x),
                };
                match c {
                    Some(c) => out.push(c),
                    None => {
                        let reason = if enc == Encoding::Ascii { "ordinal not in range(128)" } else { "character maps to <undefined>" };
                        bad(&mut out, &b[i..i + 1], i, reason)?;
                    }
                }
            }
        }
        Encoding::Utf16 | Encoding::Utf16Le | Encoding::Utf16Be => {
            let (big, start) = match (enc, b) {
                (Encoding::Utf16Be, _) => (true, 0),
                (Encoding::Utf16, [0xFE, 0xFF, ..]) => (true, 2),
                (Encoding::Utf16, [0xFF, 0xFE, ..]) => (false, 2),
                _ => (false, 0),
            };
            let unit = |i: usize| if big { u16::from_be_bytes([b[i], b[i + 1]]) } else { u16::from_le_bytes([b[i], b[i + 1]]) };
            let mut i = start;
            while i + 1 < b.len() {
                let u = unit(i);
                if !(0xD800..0xE000).contains(&u) {
                    out.push(char::from_u32(u32::from(u)).unwrap_or('\u{FFFD}'));
                    i += 2;
                    continue;
                }
                if u < 0xDC00 && i + 3 < b.len() {
                    let low = unit(i + 2);
                    if (0xDC00..0xE000).contains(&low) {
                        let c = 0x10000 + ((u32::from(u) - 0xD800) << 10) + (u32::from(low) - 0xDC00);
                        out.push(char::from_u32(c).unwrap_or('\u{FFFD}'));
                        i += 4;
                        continue;
                    }
                }
                if u < 0xDC00 && i + 3 >= b.len() {
                    bad(&mut out, &b[i..], i, "unexpected end of data")?;
                    return Ok(out);
                }
                let reason = if u < 0xDC00 { "illegal UTF-16 surrogate" } else { "illegal encoding" };
                bad(&mut out, &b[i..i + 2], i, reason)?;
                i += 2;
            }
            if i < b.len() {
                bad(&mut out, &b[i..], i, "truncated data")?;
            }
        }
        Encoding::Utf32 | Encoding::Utf32Le | Encoding::Utf32Be => {
            let (big, start) = match (enc, b) {
                (Encoding::Utf32Be, _) => (true, 0),
                (Encoding::Utf32, [0, 0, 0xFE, 0xFF, ..]) => (true, 4),
                (Encoding::Utf32, [0xFF, 0xFE, 0, 0, ..]) => (false, 4),
                _ => (false, 0),
            };
            let mut i = start;
            while i + 3 < b.len() {
                let w = [b[i], b[i + 1], b[i + 2], b[i + 3]];
                let u = if big { u32::from_be_bytes(w) } else { u32::from_le_bytes(w) };
                match char::from_u32(u) {
                    Some(c) => out.push(c),
                    None => {
                        let reason = if (0xD800..0xE000).contains(&u) {
                            "code point in surrogate code point range(0xd800, 0xe000)"
                        } else {
                            "code point not in range(0x110000)"
                        };
                        bad(&mut out, &b[i..i + 4], i, reason)?;
                    }
                }
                i += 4;
            }
            if i < b.len() {
                bad(&mut out, &b[i..], i, "truncated data")?;
            }
        }
    }
    Ok(out)
}

/// The `unicode_escape` codec's decoding: Python's string escapes, over
/// bytes read as Latin-1; `raw` takes only `\\u` and `\\U`.
fn unescape(b: &[u8], raw: bool, bad: &dyn Fn(&mut String, &[u8], usize, &str) -> Result<(), ByteError>) -> Result<String, ByteError> {
    let mut out = String::with_capacity(b.len());
    let mut i = 0;
    let hex = |from: usize, n: usize| -> Option<u32> {
        let digits = b.get(from..from + n)?;
        if !digits.iter().all(u8::is_ascii_hexdigit) {
            return None;
        }
        u32::from_str_radix(std::str::from_utf8(digits).ok()?, 16).ok()
    };
    while i < b.len() {
        let x = b[i];
        if x != b'\\' || i + 1 >= b.len() {
            if x == b'\\' && !raw {
                bad(&mut out, &b[i..], i, "\\ at end of string")?;
                return Ok(out);
            }
            out.push(x as char);
            i += 1;
            continue;
        }
        let e = b[i + 1];
        if raw && e != b'u' && e != b'U' {
            // A backslash before anything else stays, and so does a doubled one.
            out.push('\\');
            if e == b'\\' {
                out.push('\\');
                i += 2;
            } else {
                i += 1;
            }
            continue;
        }
        let simple = match e {
            b'\n' => Some(None),
            b'\\' => Some(Some('\\')),
            b'\'' => Some(Some('\'')),
            b'"' => Some(Some('"')),
            b'a' => Some(Some('\u{7}')),
            b'b' => Some(Some('\u{8}')),
            b'f' => Some(Some('\u{c}')),
            b'n' => Some(Some('\n')),
            b'r' => Some(Some('\r')),
            b't' => Some(Some('\t')),
            b'v' => Some(Some('\u{b}')),
            _ => None,
        };
        if let Some(c) = simple {
            out.extend(c);
            i += 2;
            continue;
        }
        match e {
            b'0'..=b'7' => {
                let mut n = 0u32;
                let mut j = i + 1;
                while j < b.len() && j < i + 4 && (b'0'..=b'7').contains(&b[j]) {
                    n = n * 8 + u32::from(b[j] - b'0');
                    j += 1;
                }
                out.push(char::from_u32(n).unwrap_or('\u{FFFD}'));
                i = j;
            }
            b'x' | b'u' | b'U' => {
                let (n, what) = match e {
                    b'x' => (2, "truncated \\xXX escape"),
                    b'u' => (4, "truncated \\uXXXX escape"),
                    _ => (8, "truncated \\UXXXXXXXX escape"),
                };
                match hex(i + 2, n) {
                    Some(v) => match char::from_u32(v) {
                        Some(c) => {
                            out.push(c);
                            i += 2 + n;
                        }
                        None => {
                            bad(&mut out, &b[i..i + 2 + n], i, "illegal Unicode character")?;
                            i += 2 + n;
                        }
                    },
                    None => {
                        let end = (i + 2..(i + 2 + n).min(b.len())).find(|&j| !b[j].is_ascii_hexdigit()).unwrap_or((i + 2 + n).min(b.len()));
                        bad(&mut out, &b[i..end], i, what)?;
                        i = end;
                    }
                }
            }
            b'N' => {
                let close = if b.get(i + 2) == Some(&b'{') { b[i + 3..].iter().position(|&c| c == b'}').map(|p| i + 3 + p) } else { None };
                let named = close.and_then(|end| std::str::from_utf8(&b[i + 3..end]).ok().and_then(super::charnames::lookup).map(|c| (c, end)));
                match named {
                    Some((c, end)) => {
                        out.push(c);
                        i = end + 1;
                    }
                    None => {
                        let end = close.map_or(b.len(), |e| e + 1);
                        let what = if close.is_some() { "unknown Unicode character name" } else { "malformed \\N character escape" };
                        bad(&mut out, &b[i..end], i, what)?;
                        i = end;
                    }
                }
            }
            _ => {
                out.push('\\');
                i += 1;
            }
        }
    }
    Ok(out)
}

/// How many bytes a UTF-8 sequence starting with [lead] takes; 0 when it
/// cannot start one.
fn utf8_width(lead: u8) -> usize {
    match lead {
        0x00..=0x7F => 1,
        0xC2..=0xDF => 2,
        0xE0..=0xEF => 3,
        0xF0..=0xF4 => 4,
        _ => 0,
    }
}

pub(crate) fn to_hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// `bytes.fromhex`: pairs of hex digits, whitespace allowed between pairs.
pub(crate) fn from_hex(s: &str) -> Result<Vec<u8>, ByteError> {
    let chars: Vec<char> = s.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        if chars[i].is_whitespace() {
            i += 1;
            continue;
        }
        let hi = chars[i].to_digit(16).filter(|_| chars[i].is_ascii());
        let lo = chars.get(i + 1).and_then(|c| c.to_digit(16).filter(|_| c.is_ascii()));
        match (hi, lo) {
            (Some(h), Some(l)) => out.push((h * 16 + l) as u8),
            _ => {
                let pos = if hi.is_none() { i } else { i + 1 };
                return Err(ByteError::Hex(format!("non-hexadecimal number found in fromhex() arg at position {pos}")));
            }
        }
        i += 2;
    }
    Ok(out)
}
