//! Positions, ranges and URIs. The server speaks UTF-16 positions, the
//! tooling's own unit, so a tooling offset and an LSP character count agree.

use std::path::{Path, PathBuf};
use std::str::FromStr;

use lsp_types::{Position, Range, Uri};

/// Where each line of a text starts, in UTF-16 units. Lines break at `\n`,
/// `\r\n` and a lone `\r`, as both LSP and the tooling count them.
pub struct LineIndex {
    starts: Vec<u32>,
    /// The end of each line before its break.
    ends: Vec<u32>,
    len: u32,
}

impl LineIndex {
    pub fn new(text: &str) -> LineIndex {
        let units: Vec<u16> = text.encode_utf16().collect();
        LineIndex::of_units(&units)
    }

    pub fn of_units(units: &[u16]) -> LineIndex {
        let mut starts = vec![0u32];
        let mut ends = Vec::new();
        let mut i = 0;
        while i < units.len() {
            let c = units[i];
            if c == b'\n' as u16 || c == b'\r' as u16 {
                ends.push(i as u32);
                if c == b'\r' as u16 && i + 1 < units.len() && units[i + 1] == b'\n' as u16 {
                    i += 1;
                }
                starts.push(i as u32 + 1);
            }
            i += 1;
        }
        ends.push(units.len() as u32);
        LineIndex { starts, ends, len: units.len() as u32 }
    }

    pub fn position(&self, offset: u32) -> Position {
        let offset = offset.min(self.len);
        let line = match self.starts.binary_search(&offset) {
            Ok(i) => i,
            Err(i) => i.saturating_sub(1),
        };
        // An offset inside a \r\n pair counts as the end of its line.
        let character = offset.min(self.ends[line]) - self.starts[line];
        Position { line: line as u32, character }
    }

    pub fn offset(&self, p: Position) -> u32 {
        let line = p.line as usize;
        if line >= self.starts.len() {
            return self.len;
        }
        (self.starts[line] + p.character).min(self.ends[line])
    }

    pub fn range(&self, start: u32, end: u32) -> Range {
        Range { start: self.position(start), end: self.position(end.max(start)) }
    }

    pub fn line_start(&self, line: usize) -> u32 {
        self.starts.get(line).copied().unwrap_or(self.len)
    }
}

/// The UTF-8 byte index of a UTF-16 offset into [text].
pub fn byte_of_utf16(text: &str, offset: u32) -> usize {
    let mut units = 0u32;
    for (i, c) in text.char_indices() {
        if units >= offset {
            return i;
        }
        units += c.len_utf16() as u32;
    }
    text.len()
}

/// The UTF-16 offset of a UTF-8 byte index into [text].
pub fn utf16_of_byte(text: &str, byte: usize) -> u32 {
    let byte = byte.min(text.len());
    let mut cut = byte;
    while !text.is_char_boundary(cut) {
        cut -= 1;
    }
    text[..cut].encode_utf16().count() as u32
}

/// [text] with [range] replaced by [new], for incremental sync.
pub fn apply_change(text: &mut String, range: Range, new: &str) {
    let index = LineIndex::new(text);
    let start = byte_of_utf16(text, index.offset(range.start));
    let end = byte_of_utf16(text, index.offset(range.end)).max(start);
    text.replace_range(start..end, new);
}

/// The whole of [text] as a range.
pub fn whole(text: &str) -> Range {
    let index = LineIndex::new(text);
    index.range(0, index.len)
}

// ---- URIs ------------------------------------------------------------------

/// The file a `file:` URI names.
pub fn path_of_uri_str(uri: &str) -> Option<PathBuf> {
    let rest = uri.strip_prefix("file://")?;
    // An authority, when there is one, is "localhost" or a UNC host.
    let i = rest.find('/')?;
    let (host, path) = (&rest[..i], &rest[i..]);
    let decoded = percent_decode(path)?;
    if cfg!(windows) {
        // "/C:/x" is C:/x; a host makes it a UNC path.
        let trimmed = decoded.strip_prefix('/').filter(|p| p.as_bytes().get(1) == Some(&b':')).unwrap_or(&decoded);
        let text = if !host.is_empty() && host != "localhost" { format!("//{host}{decoded}") } else { trimmed.to_string() };
        return Some(PathBuf::from(text.replace('/', "\\")));
    }
    if !host.is_empty() && host != "localhost" {
        return None;
    }
    Some(PathBuf::from(decoded))
}

/// A `file:` URI for [path], as VS Code writes one.
pub fn path_to_uri(path: &Path) -> Uri {
    let text = path.to_string_lossy().replace('\\', "/");
    let mut out = String::from("file://");
    if !text.starts_with('/') {
        out.push('/');
    }
    for b in text.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    Uri::from_str(&out).unwrap_or_else(|_| Uri::from_str("file:///").expect("a valid uri"))
}

fn percent_decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn positions_round_trip_through_utf16() {
        let text = "a = 'é😀'\r\nb = 2\n";
        let index = LineIndex::new(text);
        let p = index.position(9);
        assert_eq!((p.line, p.character), (0, 9));
        assert_eq!(index.offset(Position { line: 1, character: 1 }), 12);
        assert_eq!(index.position(10), Position { line: 0, character: 9 });
    }

    #[test]
    fn incremental_change_lands_in_utf16() {
        let mut text = String::from("x = '😀'\ny = 1\n");
        apply_change(&mut text, Range { start: Position { line: 0, character: 7 }, end: Position { line: 0, character: 8 } }, "\"");
        assert_eq!(text, "x = '😀\"\ny = 1\n");
    }

    #[cfg(unix)]
    #[test]
    fn uris_and_paths() {
        let p = Path::new("/tmp/my plugin/main.py");
        let uri = path_to_uri(p);
        assert_eq!(uri.as_str(), "file:///tmp/my%20plugin/main.py");
        assert_eq!(path_of_uri_str(uri.as_str()).unwrap(), p);
    }
}
