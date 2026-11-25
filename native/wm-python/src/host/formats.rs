//! Native helpers for reading file formats, on `_wm`, for every API 5
//! plugin: `wm.preview` and `wm.formats` wrap them. They work on bytes the
//! plugin already holds and touch nothing outside the sandbox. Each is native
//! because the same loop in Python would spend a preview's whole budget on a
//! few megabytes.
//!
//! * `data_uri(bytes)`: `"data:" + base64`, built here so a picture is not
//!   capped by the interpreter's longest string.
//! * `decode_text(bytes, encoding=None)`: a byte-order mark first, then the
//!   named encoding; with none, UTF-8, UTF-16 when the zeros say so, else
//!   Windows-1252.
//! * `inflate(bytes, raw=False, max=…)`: gzip, zlib or (raw) deflate.
//! * `zip_list(bytes)` / `zip_read(bytes, name, max=…)`: a zip's central
//!   directory and one entry, stored or deflated.
//! * `xml(text, max_nodes=…)`: a forgiving parse into `{"tag", "attrs",
//!   "nodes"}` dicts, text nodes as strings among them.
//! * `csv(text, delimiter=None, max_rows=…)`: rows of strings, RFC 4180
//!   quoting, the delimiter guessed from the first line when not given.
//! * `unpack(fmt, bytes, offset=0)` / `calcsize(fmt)`: Python's `struct`
//!   for the codes `xcbB?hHiIlLqQefds`.

use base64::Engine;

use crate::interp::{Call, PyError, Value};

/// The most any one inflate or zip entry may grow to.
pub const MAX_INFLATED: usize = 64 << 20;
/// Entries `zip_list` reports.
pub const MAX_ZIP_ENTRIES: usize = 5000;
/// Nodes `xml` builds by default, and at most.
pub const DEFAULT_XML_NODES: usize = 20_000;
pub const MAX_XML_NODES: usize = 200_000;
const MAX_XML_DEPTH: usize = 256;
/// Rows `csv` returns at most.
pub const MAX_CSV_ROWS: usize = 100_000;

fn value_error(message: impl Into<String>) -> PyError {
    PyError::new(message, "ValueError")
}

fn bytes_of(call: &Call, i: usize, name: &str) -> Result<std::rc::Rc<[u8]>, PyError> {
    match call.value(i, Some(name)) {
        Some(Value::Bytes(b)) => Ok(b.clone()),
        Some(Value::Str(s)) => Ok(std::rc::Rc::from(s.as_bytes())),
        Some(other) => Err(PyError::new(format!("{}() needs bytes, not '{}'", call.name, other.type_name()), "TypeError")),
        None => Err(PyError::new(format!("{}() needs bytes", call.name), "TypeError")),
    }
}

fn limit_of(call: &Call, i: usize, default: usize, cap: usize) -> Result<usize, PyError> {
    match call.value(i, Some("max")) {
        None | Some(Value::None) => Ok(default),
        Some(Value::Int(n)) if *n > 0 => Ok((*n as usize).min(cap)),
        Some(_) => Err(value_error(format!("{}(): max must be a positive number of bytes", call.name))),
    }
}

/// Install the helpers on `_wm`.
pub(crate) fn install(m: &mut crate::interp::HostModule) {
    m.define("data_uri", |call| {
        let data = bytes_of(call, 0, "data")?;
        let mut out = String::with_capacity(5 + data.len() * 4 / 3 + 4);
        out.push_str("data:");
        base64::engine::general_purpose::STANDARD.encode_string(&data, &mut out);
        Ok(Value::str(out))
    });
    m.define("decode_text", |call| {
        let data = bytes_of(call, 0, "data")?;
        let encoding = match call.value(1, Some("encoding")) {
            Some(Value::Str(s)) => Some(s.to_string()),
            _ => None,
        };
        decode_text(&data, encoding.as_deref()).map(Value::str)
    });
    m.define("inflate", |call| {
        let data = bytes_of(call, 0, "data")?;
        let raw = call.value(1, Some("raw")).is_some_and(Value::truthy);
        let max = limit_of(call, 2, 16 << 20, MAX_INFLATED)?;
        inflate(&data, raw, max).map(|b| Value::bytes(&b)).map_err(value_error)
    });
    m.define("zip_list", |call| {
        let data = bytes_of(call, 0, "data")?;
        let entries = zip_entries(&data).map_err(value_error)?;
        Ok(Value::list(
            entries
                .iter()
                .take(MAX_ZIP_ENTRIES)
                .map(|e| {
                    Value::dict(vec![
                        ("name", Value::str(&e.name)),
                        ("size", Value::Int(e.size as i64)),
                        ("compressed", Value::Int(e.compressed as i64)),
                        ("folder", Value::Bool(e.name.ends_with('/'))),
                        ("encrypted", Value::Bool(e.encrypted)),
                        ("modified", Value::Int(e.modified)),
                    ])
                })
                .collect(),
        ))
    });
    m.define("zip_read", |call| {
        let data = bytes_of(call, 0, "data")?;
        let name: String = call.arg(1, Some("name"))?;
        let max = limit_of(call, 2, 4 << 20, MAX_INFLATED)?;
        zip_read(&data, &name, max).map(|b| Value::bytes(&b)).map_err(value_error)
    });
    m.define("xml", |call| {
        let text: String = match call.value(0, Some("text")) {
            Some(Value::Bytes(b)) => decode_text(b, None)?,
            _ => call.arg(0, Some("text"))?,
        };
        let max = match call.value(1, Some("max_nodes")) {
            Some(Value::Int(n)) if *n > 0 => (*n as usize).min(MAX_XML_NODES),
            _ => DEFAULT_XML_NODES,
        };
        Ok(xml(&text, max))
    });
    m.define("csv", |call| {
        let text: String = match call.value(0, Some("text")) {
            Some(Value::Bytes(b)) => decode_text(b, None)?,
            _ => call.arg(0, Some("text"))?,
        };
        let delimiter = match call.value(1, Some("delimiter")) {
            Some(Value::Str(s)) => s.chars().next(),
            _ => None,
        };
        let max = match call.value(2, Some("max_rows")) {
            Some(Value::Int(n)) if *n > 0 => (*n as usize).min(MAX_CSV_ROWS),
            _ => MAX_CSV_ROWS,
        };
        let delimiter = delimiter.unwrap_or_else(|| sniff_delimiter(&text));
        let rows = csv(&text, delimiter, max);
        Ok(Value::list(rows.into_iter().map(|r| Value::list(r.into_iter().map(Value::str).collect())).collect()))
    });
    m.define("csv_delimiter", |call| {
        let text: String = call.arg(0, Some("text"))?;
        Ok(Value::str(sniff_delimiter(&text).to_string()))
    });
    m.define("unpack", |call| {
        let fmt: String = call.arg(0, Some("fmt"))?;
        let data = bytes_of(call, 1, "data")?;
        let offset: i64 = call.arg_or(2, Some("offset"), 0)?;
        if offset < 0 {
            return Err(value_error("unpack(): offset can't be negative"));
        }
        unpack(&fmt, &data, offset as usize).map(Value::tuple)
    });
    m.define("calcsize", |call| {
        let fmt: String = call.arg(0, Some("fmt"))?;
        Ok(Value::Int(parse_format(&fmt)?.1 as i64))
    });
}

// MARK: - text

/// [data] as text. See the module docs for the order things are tried in.
pub fn decode_text(data: &[u8], encoding: Option<&str>) -> Result<String, PyError> {
    if let Some(rest) = data.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        return Ok(String::from_utf8_lossy(rest).into_owned());
    }
    if let Some(rest) = data.strip_prefix(&[0xFF, 0xFE]) {
        return Ok(utf16(rest, false));
    }
    if let Some(rest) = data.strip_prefix(&[0xFE, 0xFF]) {
        return Ok(utf16(rest, true));
    }
    let name = encoding.map(|e| e.trim().to_ascii_lowercase().replace('_', "-"));
    match name.as_deref() {
        Some("utf-8" | "utf8") => Ok(String::from_utf8_lossy(data).into_owned()),
        Some("utf-16" | "utf-16le" | "utf16") => Ok(utf16(data, false)),
        Some("utf-16be") => Ok(utf16(data, true)),
        Some("latin-1" | "latin1" | "iso-8859-1" | "iso8859-1") => Ok(data.iter().map(|&b| b as char).collect()),
        Some("cp1252" | "windows-1252" | "ascii" | "us-ascii") => Ok(cp1252(data)),
        Some(other) => Err(value_error(format!("decode_text(): unknown encoding '{other}'"))),
        None => {
            if let Ok(s) = std::str::from_utf8(data) {
                return Ok(s.to_string());
            }
            // A cut-off last character still counts as UTF-8.
            if let Err(e) = std::str::from_utf8(data) {
                if e.error_len().is_none() && e.valid_up_to() + 4 > data.len() {
                    return Ok(String::from_utf8_lossy(&data[..e.valid_up_to()]).into_owned());
                }
            }
            let sample = &data[..data.len().min(4096)];
            let zeros_odd = sample.iter().skip(1).step_by(2).filter(|&&b| b == 0).count();
            let zeros_even = sample.iter().step_by(2).filter(|&&b| b == 0).count();
            let half = sample.len() / 2;
            if half > 4 && zeros_odd * 3 > half {
                return Ok(utf16(data, false));
            }
            if half > 4 && zeros_even * 3 > half {
                return Ok(utf16(data, true));
            }
            Ok(cp1252(data))
        }
    }
}

fn utf16(data: &[u8], big_endian: bool) -> String {
    let units = data.chunks_exact(2).map(|p| if big_endian { u16::from_be_bytes([p[0], p[1]]) } else { u16::from_le_bytes([p[0], p[1]]) });
    char::decode_utf16(units).map(|c| c.unwrap_or('\u{FFFD}')).collect()
}

/// Windows-1252: Latin-1 with printable characters in 0x80..0xA0.
fn cp1252(data: &[u8]) -> String {
    const HIGH: [char; 32] = [
        '€', '\u{81}', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '\u{8D}', 'Ž', '\u{8F}', '\u{90}', '‘', '’', '“', '”', '•', '–', '—', '˜', '™',
        'š', '›', 'œ', '\u{9D}', 'ž', 'Ÿ',
    ];
    data.iter().map(|&b| if (0x80..0xA0).contains(&b) { HIGH[(b - 0x80) as usize] } else { b as char }).collect()
}

// MARK: - deflate

/// gzip, zlib or deflate: a gzip or zlib header is recognised unless [raw].
pub fn inflate(data: &[u8], raw: bool, max: usize) -> Result<Vec<u8>, String> {
    use miniz_oxide::inflate::{decompress_to_vec_with_limit, decompress_to_vec_zlib_with_limit};
    let fail = |e: miniz_oxide::inflate::DecompressError| match e.status {
        miniz_oxide::inflate::TINFLStatus::HasMoreOutput => format!("inflate(): the data grows past {max} bytes"),
        _ => "inflate(): the data is not deflate, zlib or gzip, or it is cut short".to_string(),
    };
    if !raw && data.len() >= 10 && data[0] == 0x1f && data[1] == 0x8b {
        let body = gzip_body(data).ok_or("inflate(): a broken gzip header")?;
        return decompress_to_vec_with_limit(body, max).map_err(fail);
    }
    // A zlib header: CMF 0x78 and a header checksum divisible by 31.
    if !raw && data.len() >= 2 && data[0] & 0x0f == 8 && (u16::from(data[0]) << 8 | u16::from(data[1])) % 31 == 0 {
        return decompress_to_vec_zlib_with_limit(data, max).map_err(fail);
    }
    decompress_to_vec_with_limit(data, max).map_err(fail)
}

fn gzip_body(data: &[u8]) -> Option<&[u8]> {
    let flags = data[3];
    let mut at = 10usize;
    if flags & 0x04 != 0 {
        let len = u16::from_le_bytes([*data.get(at)?, *data.get(at + 1)?]) as usize;
        at += 2 + len;
    }
    for bit in [0x08u8, 0x10] {
        if flags & bit != 0 {
            let end = data.get(at..)?.iter().position(|&b| b == 0)?;
            at += end + 1;
        }
    }
    if flags & 0x02 != 0 {
        at += 2;
    }
    data.get(at..)
}

// MARK: - zip

pub struct ZipEntry {
    pub name: String,
    pub size: u64,
    pub compressed: u64,
    method: u16,
    pub encrypted: bool,
    local: u64,
    /// Seconds since 1970 from the DOS date, read as UTC.
    pub modified: i64,
}

fn le16(d: &[u8], at: usize) -> Option<u16> {
    d.get(at..at + 2).map(|b| u16::from_le_bytes([b[0], b[1]]))
}

fn le32(d: &[u8], at: usize) -> Option<u32> {
    d.get(at..at + 4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
}

fn le64(d: &[u8], at: usize) -> Option<u64> {
    d.get(at..at + 8).map(|b| u64::from_le_bytes(b.try_into().unwrap_or([0; 8])))
}

/// The zip's central directory, in its own order.
pub fn zip_entries(data: &[u8]) -> Result<Vec<ZipEntry>, String> {
    const NOT_A_ZIP: &str = "this is not a zip, or only part of one";
    let floor = data.len().saturating_sub(22 + 65_535);
    let eocd = (floor..data.len().saturating_sub(21)).rev().find(|&i| le32(data, i) == Some(0x0605_4b50)).ok_or(NOT_A_ZIP)?;
    let mut count = le16(data, eocd + 10).ok_or(NOT_A_ZIP)? as u64;
    let mut start = le32(data, eocd + 16).ok_or(NOT_A_ZIP)? as u64;
    // Zip64: the locator sits just before the end record.
    if (count == 0xFFFF || start == 0xFFFF_FFFF) && eocd >= 20 && le32(data, eocd - 20) == Some(0x0706_4b50) {
        let at = le64(data, eocd - 12).ok_or(NOT_A_ZIP)? as usize;
        if le32(data, at) == Some(0x0606_4b50) {
            count = le64(data, at + 32).ok_or(NOT_A_ZIP)?;
            start = le64(data, at + 48).ok_or(NOT_A_ZIP)?;
        }
    }
    let mut out = Vec::new();
    let mut at = usize::try_from(start).map_err(|_| NOT_A_ZIP)?;
    for _ in 0..count.min(100_000) {
        if le32(data, at) != Some(0x0201_4b50) {
            break;
        }
        let flags = le16(data, at + 8).ok_or(NOT_A_ZIP)?;
        let method = le16(data, at + 10).ok_or(NOT_A_ZIP)?;
        let time = le16(data, at + 12).ok_or(NOT_A_ZIP)?;
        let date = le16(data, at + 14).ok_or(NOT_A_ZIP)?;
        let mut compressed = le32(data, at + 20).ok_or(NOT_A_ZIP)? as u64;
        let mut size = le32(data, at + 24).ok_or(NOT_A_ZIP)? as u64;
        let name_len = le16(data, at + 28).ok_or(NOT_A_ZIP)? as usize;
        let extra_len = le16(data, at + 30).ok_or(NOT_A_ZIP)? as usize;
        let comment_len = le16(data, at + 32).ok_or(NOT_A_ZIP)? as usize;
        let mut local = le32(data, at + 42).ok_or(NOT_A_ZIP)? as u64;
        let raw_name = data.get(at + 46..at + 46 + name_len).ok_or(NOT_A_ZIP)?;
        // Bit 11: the name is UTF-8; otherwise code page 437, near enough as 1252.
        let name = if flags & 0x0800 != 0 { String::from_utf8_lossy(raw_name).into_owned() } else { std::str::from_utf8(raw_name).map(String::from).unwrap_or_else(|_| cp1252(raw_name)) };
        let extra = data.get(at + 46 + name_len..at + 46 + name_len + extra_len).unwrap_or(&[]);
        let mut e = 0;
        while e + 4 <= extra.len() {
            let id = le16(extra, e).unwrap_or(0);
            let len = le16(extra, e + 2).unwrap_or(0) as usize;
            if id == 0x0001 {
                let mut f = e + 4;
                for slot in [&mut size, &mut compressed, &mut local] {
                    if *slot == 0xFFFF_FFFF {
                        if let Some(v) = le64(extra, f) {
                            *slot = v;
                            f += 8;
                        }
                    }
                }
            }
            e += 4 + len;
        }
        out.push(ZipEntry { name, size, compressed, method, encrypted: flags & 1 != 0, local, modified: dos_time(date, time) });
        at += 46 + name_len + extra_len + comment_len;
    }
    if out.is_empty() && count > 0 {
        return Err(NOT_A_ZIP.into());
    }
    Ok(out)
}

fn dos_time(date: u16, time: u16) -> i64 {
    let year = 1980 + i64::from(date >> 9);
    let month = i64::from((date >> 5) & 0x0f).clamp(1, 12);
    let day = i64::from(date & 0x1f).max(1);
    let (h, mi, s) = (i64::from(time >> 11), i64::from((time >> 5) & 0x3f), i64::from(time & 0x1f) * 2);
    // Days from civil (Howard Hinnant's algorithm).
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    days * 86_400 + h * 3600 + mi * 60 + s
}

/// The bytes of entry [name], up to [max] once inflated.
pub fn zip_read(data: &[u8], name: &str, max: usize) -> Result<Vec<u8>, String> {
    let entries = zip_entries(data)?;
    let entry = entries
        .iter()
        .find(|e| e.name == name)
        .or_else(|| entries.iter().find(|e| e.name.eq_ignore_ascii_case(name)))
        .ok_or_else(|| format!("the zip has no '{name}'"))?;
    if entry.encrypted {
        return Err(format!("'{name}' is encrypted"));
    }
    let at = usize::try_from(entry.local).map_err(|_| "a broken zip entry".to_string())?;
    if le32(data, at) != Some(0x0403_4b50) {
        return Err("a broken zip entry".into());
    }
    let name_len = le16(data, at + 26).unwrap_or(0) as usize;
    let extra_len = le16(data, at + 28).unwrap_or(0) as usize;
    let begin = at + 30 + name_len + extra_len;
    let end = begin.saturating_add(usize::try_from(entry.compressed).unwrap_or(usize::MAX)).min(data.len());
    let body = data.get(begin..end).ok_or("the zip is cut short")?;
    match entry.method {
        0 => {
            if body.len() > max {
                Err(format!("'{name}' is bigger than {max} bytes"))
            } else {
                Ok(body.to_vec())
            }
        }
        8 => inflate(body, true, max).map_err(|e| e.replace("inflate()", &format!("'{name}'"))),
        m => Err(format!("'{name}' uses compression method {m}, which only stored and deflated entries are read in")),
    }
}

// MARK: - xml

struct XmlBuilder {
    /// Open elements: (tag, attrs, nodes).
    stack: Vec<(String, Vec<(String, Value)>, Vec<Value>)>,
    nodes: usize,
    max: usize,
}

impl XmlBuilder {
    fn element(tag: String, attrs: Vec<(String, Value)>, nodes: Vec<Value>) -> Value {
        let attrs = Value::dict(attrs);
        Value::dict(vec![("tag", Value::str(tag)), ("attrs", attrs), ("nodes", Value::list(nodes))])
    }

    fn push_node(&mut self, v: Value) {
        if let Some(top) = self.stack.last_mut() {
            top.2.push(v);
        }
    }

    fn close_top(&mut self) {
        if self.stack.len() > 1 {
            let (tag, attrs, nodes) = self.stack.pop().unwrap_or_default();
            let v = XmlBuilder::element(tag, attrs, nodes);
            self.push_node(v);
        }
    }
}

/// A forgiving XML (and XHTML) parse: the document's root element, or a
/// `#document` element holding everything when there are several.
pub fn xml(text: &str, max: usize) -> Value {
    let mut b = XmlBuilder { stack: vec![("#document".into(), Vec::new(), Vec::new())], nodes: 0, max };
    let s = text;
    let mut i = 0usize;
    let bytes = s.as_bytes();
    while i < bytes.len() && b.nodes < b.max {
        if bytes[i] != b'<' {
            let end = s[i..].find('<').map_or(s.len(), |n| i + n);
            let t = unescape(&s[i..end]);
            if !t.trim().is_empty() || (b.stack.len() > 1 && !t.is_empty()) {
                b.nodes += 1;
                b.push_node(Value::str(t));
            }
            i = end;
            continue;
        }
        let rest = &s[i..];
        if rest.starts_with("<!--") {
            i += rest.find("-->").map_or(rest.len(), |n| n + 3);
        } else if let Some(body) = rest.strip_prefix("<![CDATA[") {
            let end = body.find("]]>").unwrap_or(body.len());
            b.nodes += 1;
            b.push_node(Value::str(&body[..end]));
            i += 9 + end + 3.min(body.len() - end);
        } else if rest.starts_with("<?") {
            i += rest.find("?>").map_or(rest.len(), |n| n + 2);
        } else if rest.starts_with("<!") {
            // A DOCTYPE, with any internal subset in brackets.
            let mut depth = 0i32;
            let mut j = 2;
            let rb = rest.as_bytes();
            while j < rb.len() {
                match rb[j] {
                    b'[' => depth += 1,
                    b']' => depth -= 1,
                    b'>' if depth <= 0 => break,
                    _ => {}
                }
                j += 1;
            }
            i += (j + 1).min(rest.len());
        } else if let Some(close) = rest.strip_prefix("</") {
            let end = close.find('>').unwrap_or(close.len());
            let tag = close[..end].trim();
            if let Some(pos) = b.stack.iter().rposition(|(t, _, _)| t == tag) {
                if pos > 0 {
                    while b.stack.len() > pos {
                        b.close_top();
                    }
                }
            }
            i += 2 + (end + 1).min(close.len());
        } else {
            let (tag, attrs, self_closing, used) = open_tag(rest);
            i += used;
            if tag.is_empty() {
                b.push_node(Value::str("<"));
                continue;
            }
            b.nodes += 1;
            if self_closing || b.stack.len() > MAX_XML_DEPTH {
                b.push_node(XmlBuilder::element(tag, attrs, Vec::new()));
            } else {
                b.stack.push((tag, attrs, Vec::new()));
            }
        }
    }
    while b.stack.len() > 1 {
        b.close_top();
    }
    let (_, _, mut nodes) = b.stack.pop().unwrap_or_default();
    let elements: Vec<usize> = nodes.iter().enumerate().filter(|(_, n)| matches!(n, Value::Dict(_))).map(|(i, _)| i).collect();
    if elements.len() == 1 && nodes.iter().all(|n| matches!(n, Value::Dict(_)) || n.as_str().is_some_and(|t| t.trim().is_empty())) {
        return nodes.swap_remove(elements[0]);
    }
    XmlBuilder::element("#document".into(), Vec::new(), nodes)
}

/// `<tag a="1" b='2' c>`: the tag, its attributes, whether it closed itself,
/// and the bytes read.
fn open_tag(s: &str) -> (String, Vec<(String, Value)>, bool, usize) {
    let b = s.as_bytes();
    let mut i = 1;
    let name_end = |i: usize| {
        let mut j = i;
        while j < b.len() && !b[j].is_ascii_whitespace() && b[j] != b'>' && b[j] != b'/' && b[j] != b'=' {
            j += 1;
        }
        j
    };
    let end = name_end(i);
    let tag = s[i..end].to_string();
    if tag.is_empty() || !s[i..].starts_with(|c: char| c.is_alphabetic() || c == '_' || c == ':') {
        return (String::new(), Vec::new(), false, 1);
    }
    i = end;
    let mut attrs = Vec::new();
    loop {
        while i < b.len() && b[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            return (tag, attrs, false, i);
        }
        if b[i] == b'>' {
            return (tag, attrs, false, i + 1);
        }
        if b[i] == b'/' {
            let close = s[i..].find('>').map_or(s.len(), |n| i + n + 1);
            return (tag, attrs, true, close);
        }
        let key_end = name_end(i);
        if key_end == i {
            i += 1;
            continue;
        }
        let key = s[i..key_end].to_string();
        i = key_end;
        while i < b.len() && b[i].is_ascii_whitespace() {
            i += 1;
        }
        if i < b.len() && b[i] == b'=' {
            i += 1;
            while i < b.len() && b[i].is_ascii_whitespace() {
                i += 1;
            }
            let value = if i < b.len() && (b[i] == b'"' || b[i] == b'\'') {
                let q = b[i] as char;
                let close = s[i + 1..].find(q).map_or(s.len(), |n| i + 1 + n);
                let v = unescape(&s[i + 1..close]);
                i = (close + 1).min(s.len());
                v
            } else {
                let close = name_end(i);
                let v = unescape(&s[i..close]);
                i = close;
                v
            };
            attrs.push((key, Value::str(value)));
        } else {
            attrs.push((key, Value::str("")));
        }
    }
}

/// The five XML entities, numeric references and a few common HTML ones.
fn unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(at) = rest.find('&') {
        out.push_str(&rest[..at]);
        rest = &rest[at..];
        let Some(semi) = rest[..rest.len().min(12)].find(';') else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let name = &rest[1..semi];
        let ch = match name {
            "lt" => Some('<'),
            "gt" => Some('>'),
            "amp" => Some('&'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some('\u{a0}'),
            "copy" => Some('©'),
            "reg" => Some('®'),
            "mdash" => Some('—'),
            "ndash" => Some('–'),
            "hellip" => Some('…'),
            "lsquo" => Some('‘'),
            "rsquo" => Some('’'),
            "ldquo" => Some('“'),
            "rdquo" => Some('”'),
            _ => name
                .strip_prefix("#x")
                .or_else(|| name.strip_prefix("#X"))
                .and_then(|h| u32::from_str_radix(h, 16).ok())
                .or_else(|| name.strip_prefix('#').and_then(|d| d.parse().ok()))
                .and_then(char::from_u32),
        };
        match ch {
            Some(c) => {
                out.push(c);
                rest = &rest[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

// MARK: - csv

/// The delimiter the first line uses most: comma, semicolon, tab or bar.
pub fn sniff_delimiter(text: &str) -> char {
    let line = text.lines().next().unwrap_or("");
    let mut best = (',', 0usize);
    for c in [',', ';', '\t', '|'] {
        let n = line.matches(c).count();
        if n > best.1 {
            best = (c, n);
        }
    }
    best.0
}

/// Rows of fields, quotes as RFC 4180 has them; blank lines are skipped.
pub fn csv(text: &str, delimiter: char, max_rows: usize) -> Vec<Vec<String>> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let mut rows = Vec::new();
    let mut row: Vec<String> = Vec::new();
    let mut field = String::new();
    let mut quoted = false;
    let mut chars = text.chars().peekable();
    let mut fresh = true;
    while let Some(c) = chars.next() {
        if quoted {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    field.push('"');
                    chars.next();
                } else {
                    quoted = false;
                }
            } else {
                field.push(c);
            }
            continue;
        }
        match c {
            '"' if field.is_empty() => quoted = true,
            '\r' if chars.peek() == Some(&'\n') => {}
            '\n' | '\r' => {
                if !(fresh && row.is_empty() && field.is_empty()) {
                    row.push(std::mem::take(&mut field));
                    rows.push(std::mem::take(&mut row));
                    if rows.len() >= max_rows {
                        return rows;
                    }
                }
                fresh = true;
                continue;
            }
            c if c == delimiter => row.push(std::mem::take(&mut field)),
            c => field.push(c),
        }
        fresh = false;
    }
    if !fresh || !row.is_empty() || !field.is_empty() {
        row.push(field);
        rows.push(row);
    }
    rows
}

// MARK: - struct

#[derive(Clone, Copy)]
enum Order {
    Little,
    Big,
}

/// The codes with their repeat counts, and the bytes they take.
fn parse_format(fmt: &str) -> Result<(Order, usize, Vec<(char, usize)>), PyError> {
    let mut chars = fmt.trim().chars().peekable();
    let order = match chars.peek() {
        Some('<' | '=' | '@') => {
            chars.next();
            Order::Little
        }
        Some('>' | '!') => {
            chars.next();
            Order::Big
        }
        _ => Order::Little,
    };
    let mut codes = Vec::new();
    let mut size = 0usize;
    let mut count = String::new();
    for c in chars {
        if c.is_ascii_digit() {
            count.push(c);
            continue;
        }
        if c.is_whitespace() {
            continue;
        }
        let n: usize = if count.is_empty() { 1 } else { count.parse().map_err(|_| value_error("unpack(): a count too big"))? };
        count.clear();
        let width = match c {
            'x' | 'c' | 'b' | 'B' | '?' | 's' => 1,
            'h' | 'H' | 'e' => 2,
            'i' | 'I' | 'l' | 'L' | 'f' => 4,
            'q' | 'Q' | 'd' => 8,
            _ => return Err(value_error(format!("unpack(): no format code '{c}'"))),
        };
        size = size.saturating_add(width * n);
        codes.push((c, n));
    }
    if size > 1 << 24 {
        return Err(value_error("unpack(): the format reads more than 16 MB"));
    }
    Ok((order, size, codes))
}

fn unpack(fmt: &str, data: &[u8], offset: usize) -> Result<Vec<Value>, PyError> {
    let (order, size, codes) = parse_format(fmt)?;
    let need = offset.saturating_add(size);
    if need > data.len() {
        return Err(value_error(format!("unpack(): needs {size} bytes at offset {offset}, the data has {}", data.len())));
    }
    let mut at = offset;
    let mut out = Vec::new();
    for (c, n) in codes {
        if c == 's' {
            out.push(Value::bytes(&data[at..at + n]));
            at += n;
            continue;
        }
        if c == 'x' {
            at += n;
            continue;
        }
        for _ in 0..n {
            let take = |w: usize| -> [u8; 8] {
                let mut b = [0u8; 8];
                b[..w].copy_from_slice(&data[at..at + w]);
                if let Order::Big = order {
                    b[..w].reverse();
                }
                b
            };
            let v = match c {
                'c' => Value::bytes(&data[at..at + 1]),
                'b' => Value::Int(i64::from(data[at] as i8)),
                'B' => Value::Int(i64::from(data[at])),
                '?' => Value::Bool(data[at] != 0),
                'h' => Value::Int(i64::from(i16::from_le_bytes([take(2)[0], take(2)[1]]))),
                'H' => Value::Int(i64::from(u16::from_le_bytes([take(2)[0], take(2)[1]]))),
                'e' => Value::Float(half(u16::from_le_bytes([take(2)[0], take(2)[1]]))),
                'i' | 'l' => {
                    let b = take(4);
                    Value::Int(i64::from(i32::from_le_bytes([b[0], b[1], b[2], b[3]])))
                }
                'I' | 'L' => {
                    let b = take(4);
                    Value::Int(i64::from(u32::from_le_bytes([b[0], b[1], b[2], b[3]])))
                }
                'f' => {
                    let b = take(4);
                    Value::Float(f64::from(f32::from_le_bytes([b[0], b[1], b[2], b[3]])))
                }
                'q' => Value::Int(i64::from_le_bytes(take(8))),
                // Past i64 the interpreter has no int: such a value wraps.
                'Q' => Value::Int(u64::from_le_bytes(take(8)) as i64),
                'd' => Value::Float(f64::from_le_bytes(take(8))),
                _ => Value::None,
            };
            at += match c {
                'c' | 'b' | 'B' | '?' => 1,
                'h' | 'H' | 'e' => 2,
                'i' | 'I' | 'l' | 'L' | 'f' => 4,
                _ => 8,
            };
            out.push(v);
        }
    }
    Ok(out)
}

/// IEEE 754 half precision.
fn half(bits: u16) -> f64 {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csv_quotes_and_lines() {
        let rows = csv("a,b\n\"x, y\",\"he said \"\"hi\"\"\"\r\n\n3,4", ',', 100);
        assert_eq!(rows, vec![vec!["a", "b"], vec!["x, y", "he said \"hi\""], vec!["3", "4"]]);
        assert_eq!(sniff_delimiter("a;b;c\n1;2;3"), ';');
    }

    #[test]
    fn struct_codes() {
        let v = unpack(">HI2s", &[0, 5, 0, 0, 1, 0, b'o', b'k'], 0).unwrap();
        assert_eq!(v[0].as_int(), Some(5));
        assert_eq!(v[1].as_int(), Some(256));
        assert_eq!(parse_format("<4sIhd").unwrap().1, 18);
    }

    #[test]
    fn text_encodings() {
        assert_eq!(decode_text(&[0xFF, 0xFE, b'h', 0, b'i', 0], None).unwrap(), "hi");
        assert_eq!(decode_text(&[0x93, b'x', 0x94], None).unwrap(), "“x”");
        assert_eq!(decode_text("héllo".as_bytes(), None).unwrap(), "héllo");
    }

    #[test]
    fn dos_dates() {
        // 2024-03-01 12:30:10
        let date = ((2024 - 1980) << 9 | 3 << 5 | 1) as u16;
        let time = (12 << 11 | 30 << 5 | 5) as u16;
        assert_eq!(dos_time(date, time), 1_709_296_210);
    }
}
