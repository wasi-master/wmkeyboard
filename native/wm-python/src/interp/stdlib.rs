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
use super::text::{category_abbr, decimal_value, east_asian_width, longer_than, normalize_form};
use super::error::R;
use super::eval::Interp;
use super::value::*;
use std::rc::Rc;

/// A bundled module's deflated source, as build.rs wrote it.
macro_rules! packed {
    ($path:literal) => {
        include_bytes!(concat!(env!("OUT_DIR"), "/stdlib/", $path, ".deflate"))
    };
}

/// `(dotted name, deflated source, is a package)` for every module written in
/// Python. An empty source is an empty package.
pub(crate) const PYTHON_MODULES: &[(&str, &[u8], bool)] = &[
    ("abc", packed!("abc.py"), false),
    ("argparse", packed!("argparse.py"), false),
    ("array", packed!("array.py"), false),
    ("ast", packed!("ast.py"), false),
    ("asyncio", packed!("asyncio.py"), false),
    ("atexit", packed!("atexit.py"), false),
    ("base64", packed!("base64.py"), false),
    ("binascii", packed!("binascii.py"), false),
    ("bisect", packed!("bisect.py"), false),
    ("builtins", packed!("builtins.py"), false),
    ("calendar", packed!("calendar.py"), false),
    ("codecs", packed!("codecs.py"), false),
    ("_codecs", packed!("_codecs.py"), false),
    ("collections", packed!("collections.py"), true),
    ("collections.abc", packed!("collections_abc.py"), false),
    ("configparser", packed!("configparser.py"), false),
    ("contextlib", packed!("contextlib.py"), false),
    ("contextvars", packed!("contextvars.py"), false),
    ("copy", packed!("copy.py"), false),
    ("copyreg", packed!("copyreg.py"), false),
    ("_compat_pickle", packed!("_compat_pickle.py"), false),
    ("_random", packed!("_random.py"), false),
    ("csv", packed!("csv.py"), false),
    ("dataclasses", packed!("dataclasses.py"), false),
    ("datetime", packed!("datetime.py"), false),
    ("decimal", packed!("decimal.py"), false),
    ("difflib", packed!("difflib.py"), false),
    ("email", packed!("email/__init__.py"), true),
    ("email.charset", packed!("email/charset.py"), false),
    ("email.errors", packed!("email/errors.py"), false),
    ("email.header", packed!("email/header.py"), false),
    ("email.message", packed!("email/message.py"), false),
    ("email.mime", packed!("email/mime/__init__.py"), true),
    ("email.mime.application", packed!("email/mime/application.py"), false),
    ("email.mime.audio", packed!("email/mime/audio.py"), false),
    ("email.mime.base", packed!("email/mime/base.py"), false),
    ("email.mime.image", packed!("email/mime/image.py"), false),
    ("email.mime.message", packed!("email/mime/message.py"), false),
    ("email.mime.multipart", packed!("email/mime/multipart.py"), false),
    ("email.mime.nonmultipart", packed!("email/mime/nonmultipart.py"), false),
    ("email.mime.text", packed!("email/mime/text.py"), false),
    ("email.parser", packed!("email/parser.py"), false),
    ("email.policy", packed!("email/policy.py"), false),
    ("email.utils", packed!("email/utils.py"), false),
    ("encodings", b"", true),
    ("encodings.aliases", packed!("encodings_aliases.py"), false),
    ("enum", packed!("enum.py"), false),
    ("errno", packed!("errno.py"), false),
    ("fnmatch", packed!("fnmatch.py"), false),
    ("fractions", packed!("fractions.py"), false),
    ("functools", packed!("functools.py"), false),
    ("gettext", packed!("gettext.py"), false),
    ("gzip", packed!("gzip.py"), false),
    ("hashlib", packed!("hashlib.py"), false),
    ("heapq", packed!("heapq.py"), false),
    ("hmac", packed!("hmac.py"), false),
    ("html", packed!("html.py"), true),
    ("html.entities", packed!("html_entities.py"), false),
    ("importlib", packed!("importlib/__init__.py"), true),
    ("importlib.metadata", packed!("importlib/metadata.py"), false),
    ("importlib.resources", packed!("importlib/resources.py"), false),
    ("importlib.util", packed!("importlib/util.py"), false),
    ("inspect", packed!("inspect.py"), false),
    ("io", packed!("io.py"), false),
    ("ipaddress", packed!("ipaddress.py"), false),
    ("itertools", packed!("itertools.py"), false),
    ("keyword", packed!("keyword.py"), false),
    ("linecache", packed!("linecache.py"), false),
    ("locale", packed!("locale.py"), false),
    ("logging", packed!("logging.py"), false),
    ("mimetypes", packed!("mimetypes.py"), false),
    ("numbers", packed!("numbers.py"), false),
    ("operator", packed!("operator.py"), false),
    ("os", packed!("os.py"), true),
    ("os.path", packed!("os_path.py"), false),
    ("pathlib", packed!("pathlib.py"), false),
    ("pickle", packed!("pickle.py"), false),
    ("platform", packed!("platform.py"), false),
    ("pprint", packed!("pprint.py"), false),
    ("random", packed!("random.py"), false),
    ("queue", packed!("queue.py"), false),
    ("reprlib", packed!("reprlib.py"), false),
    ("secrets", packed!("secrets.py"), false),
    ("shlex", packed!("shlex.py"), false),
    ("shutil", packed!("shutil.py"), false),
    ("stat", packed!("stat.py"), false),
    ("statistics", packed!("statistics.py"), false),
    ("string", packed!("string.py"), false),
    ("struct", packed!("struct.py"), false),
    ("subprocess", packed!("subprocess.py"), false),
    ("textwrap", packed!("textwrap.py"), false),
    ("threading", packed!("threading.py"), false),
    ("_thread", packed!("_thread.py"), false),
    ("time", packed!("time.py"), false),
    ("token", packed!("token.py"), false),
    ("tokenize", packed!("tokenize.py"), false),
    ("traceback", packed!("traceback.py"), false),
    ("types", packed!("types.py"), false),
    ("_typing", packed!("_typing.py"), false),
    ("typing", packed!("typing.py"), false),
    ("unicodedata", packed!("unicodedata.py"), false),
    ("unittest", packed!("unittest/__init__.py"), true),
    ("unittest.mock", packed!("unittest/mock.py"), false),
    ("urllib", b"", true),
    ("urllib.parse", packed!("urllib_parse.py"), false),
    ("uuid", packed!("uuid.py"), false),
    ("warnings", packed!("warnings.py"), false),
    ("weakref", packed!("weakref.py"), false),
    ("xml", packed!("xml/__init__.py"), true),
    ("xml.etree", packed!("xml/etree/__init__.py"), true),
    ("xml.etree.ElementPath", packed!("xml/etree/ElementPath.py"), false),
    ("xml.etree.ElementTree", packed!("xml/etree/ElementTree.py"), false),
    ("xml.parsers", packed!("xml/parsers/__init__.py"), true),
    ("xml.parsers.expat", packed!("xml/parsers/expat.py"), false),
    ("xml.sax", packed!("xml/sax/__init__.py"), true),
    ("xml.sax._exceptions", packed!("xml/sax/_exceptions.py"), false),
    ("xml.sax.expatreader", packed!("xml/sax/expatreader.py"), false),
    ("xml.sax.handler", packed!("xml/sax/handler.py"), false),
    ("xml.sax.saxutils", packed!("xml/sax/saxutils.py"), false),
    ("xml.sax.xmlreader", packed!("xml/sax/xmlreader.py"), false),
    ("zlib", packed!("zlib.py"), false),
    ("zoneinfo", packed!("zoneinfo.py"), false),
    ("_markupbase", packed!("_markupbase.py"), false),
    ("colorsys", packed!("colorsys.py"), false),
    ("concurrent", packed!("concurrent/__init__.py"), true),
    ("concurrent.futures", packed!("concurrent/futures/__init__.py"), true),
    ("concurrent.futures._base", packed!("concurrent/futures/_base.py"), false),
    ("concurrent.futures.process", packed!("concurrent/futures/process.py"), false),
    ("concurrent.futures.thread", packed!("concurrent/futures/thread.py"), false),
    ("fcntl", packed!("fcntl.py"), false),
    ("gc", packed!("gc.py"), false),
    ("genericpath", packed!("genericpath.py"), false),
    ("getpass", packed!("getpass.py"), false),
    ("glob", packed!("glob.py"), false),
    ("html.parser", packed!("html_parser.py"), false),
    ("http", packed!("http/__init__.py"), true),
    ("http.client", packed!("http/client.py"), false),
    ("http.cookiejar", packed!("http/cookiejar.py"), false),
    ("http.cookies", packed!("http/cookies.py"), false),
    ("http.server", packed!("http/server.py"), false),
    ("mmap", packed!("mmap.py"), false),
    ("multiprocessing", packed!("multiprocessing/__init__.py"), true),
    ("multiprocessing.context", packed!("multiprocessing/context.py"), false),
    ("multiprocessing.pool", packed!("multiprocessing/pool.py"), false),
    ("multiprocessing.process", packed!("multiprocessing/process.py"), false),
    ("ntpath", packed!("ntpath.py"), false),
    ("optparse", packed!("optparse.py"), false),
    ("posixpath", packed!("posixpath.py"), false),
    ("select", packed!("select.py"), false),
    ("selectors", packed!("selectors.py"), false),
    ("signal", packed!("signal.py"), false),
    ("socket", packed!("socket.py"), false),
    ("socketserver", packed!("socketserver.py"), false),
    ("ssl", packed!("ssl.py"), false),
    ("sysconfig", packed!("sysconfig.py"), false),
    ("tarfile", packed!("tarfile.py"), false),
    ("tempfile", packed!("tempfile.py"), false),
    ("timeit", packed!("timeit.py"), false),
    ("webbrowser", packed!("webbrowser.py"), false),
    ("xml.dom", packed!("xml/dom/__init__.py"), true),
    ("xml.dom.NodeFilter", packed!("xml/dom/NodeFilter.py"), false),
    ("xml.dom.domreg", packed!("xml/dom/domreg.py"), false),
    ("xml.dom.expatbuilder", packed!("xml/dom/expatbuilder.py"), false),
    ("xml.dom.minicompat", packed!("xml/dom/minicompat.py"), false),
    ("xml.dom.minidom", packed!("xml/dom/minidom.py"), false),
    ("xml.dom.pulldom", packed!("xml/dom/pulldom.py"), false),
    ("xml.dom.xmlbuilder", packed!("xml/dom/xmlbuilder.py"), false),
    ("zipfile", packed!("zipfile/__init__.py"), true),
    ("zipfile._path", packed!("zipfile/_path/__init__.py"), true),
    ("zipfile._path.glob", packed!("zipfile/_path/glob.py"), false),
];

/// `frozenset` and `bytearray`, loaded when a script first names one: always
/// there, unlike the modules above, which a host turns on.
pub(crate) const BUILTINS_SOURCE: &[u8] = packed!("_builtins.py");

/// A module's source: text a host or plugin gave, or one of the bundled
/// modules, inflated when it is first imported.
#[derive(Clone)]
pub(crate) enum ModuleSource {
    Text(Rc<str>),
    Packed(&'static [u8]),
}

impl ModuleSource {
    pub(crate) fn text(&self) -> Rc<str> {
        match self {
            ModuleSource::Text(t) => t.clone(),
            ModuleSource::Packed(b) if b.is_empty() => Rc::from(""),
            ModuleSource::Packed(b) => {
                let raw = miniz_oxide::inflate::decompress_to_vec(b).expect("a bundled module inflates");
                Rc::from(String::from_utf8(raw).expect("a bundled module is UTF-8"))
            }
        }
    }
}

pub(crate) const NATIVE_FUNCTIONS: &[&str] = &[
    "digest", "b64encode", "b64decode", "mktime", "quote", "unquote", "normalize", "is_normalized", "category",
    "combining", "decimal", "east_asian_width", "mirrored", "char_name", "char_lookup",
    "iscoroutinefunction", "struct_calcsize", "struct_pack", "struct_unpack", "crc32", "adler32", "deflate_raw", "inflate",
    "urandom", "literal_check", "encoding_name",
];

/// [n] bytes from a generator keyed by the operating system's randomness
/// (std's per-thread hash keys), apart from `random`'s seedable one.
/// `os.urandom` and `secrets`: SHA-256 over a secret seed and a running
/// counter. The seed is drawn once, from the hash keys the standard library
/// takes from the system's random source.
fn random_bytes(n: usize) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    use std::cell::{Cell, OnceCell};
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    thread_local! {
        static SEED: OnceCell<[u8; 32]> = const { OnceCell::new() };
        static COUNTER: Cell<u64> = const { Cell::new(0) };
    }
    let seed = SEED.with(|s| {
        *s.get_or_init(|| {
            let mut seed = Sha256::new();
            for i in 0..8u64 {
                let mut h = RandomState::new().build_hasher();
                h.write_u64(i);
                seed.update(h.finish().to_le_bytes());
            }
            seed.finalize().into()
        })
    });
    let mut out = Vec::with_capacity(n + 32);
    while out.len() < n {
        let counter = COUNTER.with(|c| {
            let v = c.get();
            c.set(v.wrapping_add(1));
            v
        });
        let mut block = Sha256::new();
        block.update(seed);
        block.update(counter.to_le_bytes());
        out.extend_from_slice(&block.finalize());
    }
    out.truncate(n);
    out
}

fn b64(urlsafe: bool) -> GeneralPurpose {
    let config = GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent);
    GeneralPurpose::new(if urlsafe { &alphabet::URL_SAFE } else { &alphabet::STANDARD }, config)
}

impl Interp {
    fn struct_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        let fmt = args.first().and_then(Value::as_str).ok_or_else(|| self.error("Struct() argument 1 must be a str or bytes object", "TypeError"))?;
        let layout = super::structs::layout(fmt).map_err(|(kind, m)| self.error(m, if kind == "error" { "ValueError" } else { kind }))?;
        match name {
            "struct_calcsize" => Ok(Value::Int(layout.size as i64)),
            "struct_pack" => {
                let values = args.get(1).and_then(Value::as_items).unwrap_or_default();
                match super::structs::pack(&layout, &values) {
                    Ok(b) => Ok(Value::bytes(&b)),
                    Err((kind, m)) => Err(self.error(m, if kind == "error" { "ValueError" } else { kind })),
                }
            }
            _ => {
                let data = self.bytes_arg(args, 1, "unpack")?;
                let mut offset = args.get(2).and_then(Value::as_int).unwrap_or(0);
                let exact = args.get(3).is_some_and(Value::truthy);
                let size = layout.size;
                if exact && data.len() != size {
                    return Err(self.error(format!("unpack requires a buffer of {size} bytes"), "ValueError"));
                }
                if offset < 0 {
                    if offset + (data.len() as i64) < 0 {
                        return Err(self.error(format!("offset {offset} out of range for {}-byte buffer", data.len()), "ValueError"));
                    }
                    offset += data.len() as i64;
                }
                let offset = offset as usize;
                if offset + size > data.len() {
                    return Err(self.error(
                        format!(
                            "unpack_from requires a buffer of at least {} bytes for unpacking {size} bytes at offset {offset} (actual buffer size is {})",
                            offset + size,
                            data.len()
                        ),
                        "ValueError",
                    ));
                }
                Ok(Value::tuple(super::structs::unpack(&layout, &data[offset..offset + size])))
            }
        }
    }

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
            "normalize" | "is_normalized" => {
                let (Some(Value::Str(form)), Some(Value::Str(s))) = (args.first(), args.get(1)) else {
                    return Err(self.error(format!("{name}() argument 2 must be str"), "TypeError"));
                };
                let Some(out) = normalize_form(form, s) else {
                    return Err(self.error("invalid normalization form", "ValueError"));
                };
                if name == "is_normalized" {
                    return Ok(Value::Bool(out == s.as_ref()));
                }
                if longer_than(&out, self.max_string) {
                    return Err(self.string_abort());
                }
                Ok(Value::str(out))
            }
            "struct_calcsize" | "struct_pack" | "struct_unpack" => self.struct_call(name, args),
            "crc32" | "adler32" => {
                let data = self.bytes_arg(args, 0, name)?;
                for _ in 0..data.len() / 256 {
                    self.tick()?;
                }
                let start = args.get(1).and_then(Value::as_int).map(|v| v as u32);
                Ok(Value::Int(i64::from(if name == "crc32" {
                    super::compress::crc32(&data, start.unwrap_or(0))
                } else {
                    super::compress::adler32(&data, start.unwrap_or(1))
                })))
            }
            "deflate_raw" => {
                let data = self.bytes_arg(args, 0, "compress")?;
                let level = args.get(1).and_then(Value::as_int).unwrap_or(-1);
                if !(-1..=9).contains(&level) {
                    return Err(self.error("Bad compression level", "ValueError"));
                }
                for _ in 0..data.len() / 64 {
                    self.tick()?;
                }
                let last = args.get(2).is_none_or(Value::truthy);
                Ok(Value::bytes(&super::compress::deflate_raw(&data, level as i32, last)))
            }
            "inflate" => {
                let data = self.bytes_arg(args, 0, "decompress")?;
                let wbits = args.get(1).and_then(Value::as_int).unwrap_or(15);
                for _ in 0..data.len() / 64 {
                    self.tick()?;
                }
                match super::compress::inflate_wrapped(&data, wbits, self.max_string) {
                    Ok(r) => {
                        for _ in 0..r.data.len() / 256 {
                            self.tick()?;
                        }
                        Ok(Value::tuple(vec![Value::bytes(&r.data), Value::Int(r.consumed as i64), Value::Bool(r.finished)]))
                    }
                    Err(message) => Err(self.error(message, "ValueError")),
                }
            }
            "urandom" => {
                let n = args.first().and_then(Value::as_int).ok_or_else(|| self.error("urandom() needs an int", "TypeError"))?;
                if n < 0 {
                    return Err(self.error("negative argument not allowed", "ValueError"));
                }
                if n as usize > self.max_string {
                    return Err(self.string_abort());
                }
                Ok(Value::bytes(&random_bytes(n as usize)))
            }
            "literal_check" => {
                let source = args.first().and_then(Value::as_str).ok_or_else(|| self.error("literal_eval() needs a string", "TypeError"))?;
                match super::parser::check_literal(source) {
                    Ok(Ok(())) => Ok(Value::None),
                    Ok(Err(node)) => Err(self.error(format!("malformed node or string on line 1: <{node} object>"), "ValueError")),
                    Err(e) => Err(self.error(e.message.clone(), "SyntaxError")),
                }
            }
            "encoding_name" => {
                let n = args.first().and_then(Value::as_str).ok_or_else(|| self.error("lookup() argument must be str", "TypeError"))?;
                match super::bytes::encoding(n) {
                    Ok(e) => Ok(Value::str(e.name())),
                    Err(e) => Err(self.byte_error(e)),
                }
            }
            "iscoroutinefunction" => Ok(Value::Bool(matches!(args.first(), Some(Value::Function(f)) if f.frame_info().coroutine))),
            "char_lookup" => {
                let name = args.first().and_then(Value::as_str).ok_or_else(|| self.error("lookup() argument must be str", "TypeError"))?;
                match super::charnames::lookup(name) {
                    Some(c) => Ok(Value::str(&c.to_string())),
                    None => Err(self.error(format!("undefined character name '{name}'"), "KeyError")),
                }
            }
            "category" | "combining" | "decimal" | "east_asian_width" | "mirrored" | "char_name" => {
                let c = match args.first() {
                    Some(Value::Str(s)) if s.chars().count() == 1 => s.chars().next().unwrap(),
                    Some(other) => {
                        return Err(self.error(
                            format!("{name}() argument must be a unicode character, not {}", other.type_name()),
                            "TypeError",
                        ))
                    }
                    None => return Err(self.error(format!("{name}() takes exactly one argument"), "TypeError")),
                };
                Ok(match name {
                    "category" => Value::str(category_abbr(c)),
                    "combining" => Value::Int(unicode_normalization::char::canonical_combining_class(c) as i64),
                    "decimal" => decimal_value(c).map(|d| Value::Int(d as i64)).unwrap_or(Value::None),
                    "east_asian_width" => Value::str(east_asian_width(c)),
                    "char_name" => super::charnames::name_of(c).map(|n| Value::str(&n)).unwrap_or(Value::None),
                    _ => Value::Int(matches!(c, '(' | ')' | '<' | '>' | '[' | ']' | '{' | '}' | '«' | '»') as i64),
                })
            }
            _ => Err(self.error(format!("module '_native' has no attribute '{name}'"), "AttributeError")),
        }
    }
}
