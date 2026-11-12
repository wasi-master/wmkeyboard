//! The standard library modules this crate adds to PyMini's six. Most are
//! written in Python (`src/stdlib/*.py`, compiled into the crate) and run in
//! the same sandbox as the script that imports them, on its step budget.
//! What Python cannot do quickly or at all lives in `_native`: hashing,
//! base64, percent-encoding and turning a local date into a timestamp.
//!
//! A host turns them on with `Runtime::enable_python_stdlib`. Nothing here
//! reads a file, the network or the device.

use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig};
use base64::engine::{DecodePaddingMode, Engine};
use base64::alphabet;

use super::datefmt::local_to_unix;
use super::error::R;
use super::eval::Interp;
use super::value::*;

/// `(dotted name, source, is a package)` for every module written in Python.
pub(crate) const PYTHON_MODULES: &[(&str, &str, bool)] = &[
    ("abc", include_str!("../stdlib/abc.py"), false),
    ("base64", include_str!("../stdlib/base64.py"), false),
    ("collections", include_str!("../stdlib/collections.py"), false),
    ("copy", include_str!("../stdlib/copy.py"), false),
    ("dataclasses", include_str!("../stdlib/dataclasses.py"), false),
    ("datetime", include_str!("../stdlib/datetime.py"), false),
    ("enum", include_str!("../stdlib/enum.py"), false),
    ("functools", include_str!("../stdlib/functools.py"), false),
    ("hashlib", include_str!("../stdlib/hashlib.py"), false),
    ("hmac", include_str!("../stdlib/hmac.py"), false),
    ("html", include_str!("../stdlib/html.py"), false),
    ("itertools", include_str!("../stdlib/itertools.py"), false),
    ("statistics", include_str!("../stdlib/statistics.py"), false),
    ("string", include_str!("../stdlib/string.py"), false),
    ("textwrap", include_str!("../stdlib/textwrap.py"), false),
    ("typing", include_str!("../stdlib/typing.py"), false),
    ("urllib", "", true),
    ("urllib.parse", include_str!("../stdlib/urllib_parse.py"), false),
    ("uuid", include_str!("../stdlib/uuid.py"), false),
];

pub(crate) const NATIVE_FUNCTIONS: &[&str] = &["digest", "b64encode", "b64decode", "mktime", "quote", "unquote"];

fn b64(urlsafe: bool) -> GeneralPurpose {
    let config = GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent);
    GeneralPurpose::new(if urlsafe { &alphabet::URL_SAFE } else { &alphabet::STANDARD }, config)
}

impl Interp {
    fn bytes_arg(&self, args: &[Value], i: usize, func: &str) -> R<Vec<u8>> {
        match args.get(i) {
            Some(Value::Bytes(b)) => Ok(b.to_vec()),
            Some(Value::Str(s)) => Ok(s.as_bytes().to_vec()),
            Some(other) => Err(self.error(format!("{func}() needs bytes, not '{}'", other.type_name()), "TypeError")),
            None => Err(self.error(format!("{func}() needs bytes"), "TypeError")),
        }
    }

    #[inline(never)]
    pub(crate) fn native_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "digest" => {
                let Some(Value::Str(alg)) = args.first() else {
                    return Err(self.error("digest() needs an algorithm name", "TypeError"));
                };
                let data = self.bytes_arg(args, 1, "digest")?;
                // Hashing is linear in the input; charge it like a loop over the bytes.
                for _ in 0..data.len() / 64 {
                    self.tick()?;
                }
                use sha2::Digest;
                let out: Vec<u8> = match alg.as_ref() {
                    "sha256" => sha2::Sha256::digest(&data).to_vec(),
                    "sha224" => sha2::Sha224::digest(&data).to_vec(),
                    "sha384" => sha2::Sha384::digest(&data).to_vec(),
                    "sha512" => sha2::Sha512::digest(&data).to_vec(),
                    "sha1" => sha1::Sha1::digest(&data).to_vec(),
                    "md5" => md5::Md5::digest(&data).to_vec(),
                    other => return Err(self.error(format!("unsupported hash type {other}"), "ValueError")),
                };
                Ok(Value::bytes(&out))
            }
            "b64encode" => {
                let data = self.bytes_arg(args, 0, "b64encode")?;
                let urlsafe = args.get(1).is_some_and(Value::truthy);
                let out = b64(urlsafe).encode(data);
                if out.len() > self.max_string {
                    return Err(self.string_abort());
                }
                Ok(Value::bytes(out.as_bytes()))
            }
            "b64decode" => {
                let data = self.bytes_arg(args, 0, "b64decode")?;
                let urlsafe = args.get(1).is_some_and(Value::truthy);
                let cleaned: Vec<u8> = data.into_iter().filter(|b| !b.is_ascii_whitespace()).collect();
                match b64(urlsafe).decode(cleaned) {
                    Ok(b) => Ok(Value::bytes(&b)),
                    Err(e) => Err(self.error(format!("Incorrect base64: {e}"), "ValueError")),
                }
            }
            "mktime" => {
                let mut f = [0i64; 6];
                for (i, slot) in f.iter_mut().enumerate() {
                    *slot = args.get(i).and_then(Value::as_int).unwrap_or(if i < 3 { 1 } else { 0 });
                }
                Ok(Value::Int(local_to_unix(f[0], f[1], f[2], f[3], f[4], f[5], self.clock.as_ref())))
            }
            "quote" => {
                let Some(Value::Str(s)) = args.first() else {
                    return Err(self.error("quote() needs a string", "TypeError"));
                };
                let safe = match args.get(1) {
                    Some(Value::Str(x)) => x.to_string(),
                    _ => "/".to_string(),
                };
                let mut out = String::with_capacity(s.len());
                for &b in s.as_bytes() {
                    if b.is_ascii_alphanumeric() || b"_.-~".contains(&b) || (b.is_ascii() && safe.as_bytes().contains(&b)) {
                        out.push(b as char);
                    } else {
                        out.push_str(&format!("%{b:02X}"));
                    }
                }
                Ok(Value::str(out))
            }
            "unquote" => {
                let Some(Value::Str(s)) = args.first() else {
                    return Err(self.error("unquote() needs a string", "TypeError"));
                };
                let bytes = s.as_bytes();
                let mut out = Vec::with_capacity(bytes.len());
                let mut i = 0;
                while i < bytes.len() {
                    if bytes[i] == b'%' && i + 2 < bytes.len() {
                        let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok());
                        if let Some(v) = hex {
                            out.push(v);
                            i += 3;
                            continue;
                        }
                    }
                    out.push(bytes[i]);
                    i += 1;
                }
                Ok(Value::str(String::from_utf8_lossy(&out)))
            }
            _ => Err(self.error(format!("module '_native' has no attribute '{name}'"), "AttributeError")),
        }
    }
}
