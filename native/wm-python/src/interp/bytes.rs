//! The minimal `bytes` type (an addition to PyMini): encodings UTF-8,
//! UTF-16-LE and UTF-16-BE, hex in and out.

pub(crate) enum ByteError {
    UnknownEncoding(String),
    Errors(String),
    Decode(String),
    Hex(String),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Encoding {
    Utf8,
    Utf16Le,
    Utf16Be,
}

fn encoding(name: &str) -> Result<Encoding, ByteError> {
    let n = name.trim().to_ascii_lowercase().replace('_', "-");
    Ok(match n.as_str() {
        "utf-8" | "utf8" => Encoding::Utf8,
        "utf-16-le" | "utf-16le" | "utf16le" => Encoding::Utf16Le,
        "utf-16-be" | "utf-16be" | "utf16be" => Encoding::Utf16Be,
        _ => return Err(ByteError::UnknownEncoding(name.to_string())),
    })
}

pub(crate) fn encode(s: &str, name: &str) -> Result<Vec<u8>, ByteError> {
    Ok(match encoding(name)? {
        Encoding::Utf8 => s.as_bytes().to_vec(),
        Encoding::Utf16Le => s.encode_utf16().flat_map(u16::to_le_bytes).collect(),
        Encoding::Utf16Be => s.encode_utf16().flat_map(u16::to_be_bytes).collect(),
    })
}

/// `errors` is "strict" (a UnicodeDecodeError) or "replace" (U+FFFD).
pub(crate) fn decode(b: &[u8], name: &str, errors: &str) -> Result<String, ByteError> {
    let enc = encoding(name)?;
    let strict = match errors {
        "strict" => true,
        "replace" => false,
        other => return Err(ByteError::Errors(other.to_string())),
    };
    match enc {
        Encoding::Utf8 => match std::str::from_utf8(b) {
            Ok(s) => Ok(s.to_string()),
            Err(e) if strict => Err(ByteError::Decode(format!(
                "'utf-8' codec can't decode byte 0x{:02x} in position {}: invalid utf-8",
                b[e.valid_up_to()],
                e.valid_up_to()
            ))),
            Err(_) => Ok(String::from_utf8_lossy(b).into_owned()),
        },
        Encoding::Utf16Le | Encoding::Utf16Be => {
            let label = if enc == Encoding::Utf16Le { "utf-16-le" } else { "utf-16-be" };
            let units: Vec<u16> = b
                .chunks_exact(2)
                .map(|c| if enc == Encoding::Utf16Le { u16::from_le_bytes([c[0], c[1]]) } else { u16::from_be_bytes([c[0], c[1]]) })
                .collect();
            let mut out = String::new();
            for (i, r) in char::decode_utf16(units.iter().copied()).enumerate() {
                match r {
                    Ok(c) => out.push(c),
                    Err(_) if strict => {
                        return Err(ByteError::Decode(format!("'{label}' codec can't decode bytes near position {}: illegal UTF-16 surrogate", i * 2)))
                    }
                    Err(_) => out.push('\u{FFFD}'),
                }
            }
            if b.len() % 2 == 1 {
                if strict {
                    return Err(ByteError::Decode(format!(
                        "'{label}' codec can't decode byte 0x{:02x} in position {}: truncated data",
                        b[b.len() - 1],
                        b.len() - 1
                    )));
                }
                out.push('\u{FFFD}');
            }
            Ok(out)
        }
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
