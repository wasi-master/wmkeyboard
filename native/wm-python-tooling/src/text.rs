//! Source text as UTF-16 code units, and spans into it.
//!
//! Both consumers of this crate hold their text as UTF-16 (Kotlin strings on
//! Android, JavaScript strings on the web), so every offset the service reports
//! counts UTF-16 code units. Working on the code units directly, rather than on
//! a Rust `String`, makes that true by construction.

use std::sync::Arc;

/// A half-open range of UTF-16 offsets into a source.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Default)]
pub struct Span {
    pub start: u32,
    pub end: u32,
}

impl Span {
    pub fn new(start: u32, end: u32) -> Span {
        Span { start, end: end.max(start) }
    }

    pub fn len(&self) -> u32 {
        self.end - self.start
    }

    pub fn is_empty(&self) -> bool {
        self.end == self.start
    }

    /// Whether [offset] is inside, the end excluded.
    pub fn contains(&self, offset: u32) -> bool {
        offset >= self.start && offset < self.end
    }

    /// Whether [offset] is inside or exactly at the end.
    pub fn touches(&self, offset: u32) -> bool {
        offset >= self.start && offset <= self.end
    }

    pub fn cover(self, other: Span) -> Span {
        Span::new(self.start.min(other.start), self.end.max(other.end))
    }
}

/// The source, shared by the tokens, the tree and everything built on them.
pub type Units = Arc<[u16]>;

pub fn units_of(text: &str) -> Units {
    text.encode_utf16().collect::<Vec<u16>>().into()
}

/// The text of a span. Lossy only for a lone surrogate, which no editor sends.
pub fn slice(units: &[u16], span: Span) -> String {
    let start = (span.start as usize).min(units.len());
    let end = (span.end as usize).clamp(start, units.len());
    String::from_utf16_lossy(&units[start..end])
}

/// Whether the units of [span] are exactly the ASCII [word].
pub fn eq_ascii(units: &[u16], span: Span, word: &str) -> bool {
    let start = span.start as usize;
    let end = span.end as usize;
    if end > units.len() || end - start != word.len() {
        return false;
    }
    units[start..end].iter().zip(word.bytes()).all(|(a, b)| *a == b as u16)
}

/// Where each line starts. Lines break at `\n`, at `\r\n` and at a lone `\r`.
#[derive(Clone, Debug)]
pub struct Lines {
    starts: Vec<u32>,
    len: u32,
}

impl Lines {
    pub fn of(units: &[u16]) -> Lines {
        let mut starts = vec![0u32];
        let mut i = 0;
        while i < units.len() {
            let c = units[i];
            if c == b'\n' as u16 {
                starts.push(i as u32 + 1);
            } else if c == b'\r' as u16 {
                if i + 1 < units.len() && units[i + 1] == b'\n' as u16 {
                    i += 1;
                }
                starts.push(i as u32 + 1);
            }
            i += 1;
        }
        Lines { starts, len: units.len() as u32 }
    }

    pub fn count(&self) -> usize {
        self.starts.len()
    }

    /// The 0-based line holding [offset].
    pub fn line_of(&self, offset: u32) -> usize {
        match self.starts.binary_search(&offset) {
            Ok(i) => i,
            Err(i) => i.saturating_sub(1),
        }
    }

    pub fn start(&self, line: usize) -> u32 {
        self.starts.get(line).copied().unwrap_or(self.len)
    }

    /// The end of [line], before its line break.
    pub fn end(&self, units: &[u16], line: usize) -> u32 {
        let mut end = if line + 1 < self.starts.len() { self.starts[line + 1] } else { self.len };
        let start = self.start(line);
        while end > start && (units[end as usize - 1] == b'\n' as u16 || units[end as usize - 1] == b'\r' as u16) {
            end -= 1;
        }
        end
    }

    /// The span of a 1-based [line] without its leading and trailing blanks, or the empty span at its start.
    pub fn trimmed(&self, units: &[u16], line: usize) -> Span {
        if line == 0 || line > self.starts.len() {
            return Span::new(self.len, self.len);
        }
        let index = line - 1;
        let mut start = self.start(index);
        let mut end = self.end(units, index);
        while start < end && is_blank(units[start as usize]) {
            start += 1;
        }
        while end > start && is_blank(units[end as usize - 1]) {
            end -= 1;
        }
        Span::new(start, end)
    }
}

pub fn is_blank(c: u16) -> bool {
    c == b' ' as u16 || c == b'\t' as u16 || c == 0x0c
}

/// The code point at [i], and how many units it takes.
pub fn code_point(units: &[u16], i: usize) -> (u32, usize) {
    let c = units[i] as u32;
    if (0xD800..0xDC00).contains(&c) && i + 1 < units.len() {
        let d = units[i + 1] as u32;
        if (0xDC00..0xE000).contains(&d) {
            return (0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00), 2);
        }
    }
    (c, 1)
}

/// Whether [name] reads as a Python identifier.
pub fn is_identifier(name: &str) -> bool {
    let mut chars = name.chars();
    match chars.next() {
        Some(c) if c == '_' || c.is_alphabetic() => {}
        _ => return false,
    }
    chars.all(|c| c == '_' || c.is_alphanumeric() || is_combining(c as u32))
}

/// Marks and joiners that continue a name in scripts where they are not letters.
pub fn is_combining(cp: u32) -> bool {
    matches!(cp, 0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F | 0x200C | 0x200D)
}

/// Edits between two words, a swap of neighbours counting as one and case not counting at all.
pub fn edit_distance(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut d = vec![vec![0usize; b.len() + 1]; a.len() + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for j in 0..=b.len() {
        d[0][j] = j;
    }
    for i in 1..=a.len() {
        for j in 1..=b.len() {
            let same = a[i - 1].to_lowercase().eq(b[j - 1].to_lowercase());
            let cost = if same { 0 } else { 1 };
            let mut value = (d[i - 1][j] + 1).min(d[i][j - 1] + 1).min(d[i - 1][j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                value = value.min(d[i - 2][j - 2] + 1);
            }
            d[i][j] = value;
        }
    }
    d[a.len()][b.len()]
}

/// The candidate within typing distance of [word], or None. A short word must be nearer.
pub fn closest<'a, I: IntoIterator<Item = &'a str>>(word: &str, candidates: I) -> Option<String> {
    let length = word.chars().count();
    if length < 3 {
        return None;
    }
    let limit = if length <= 4 { 1 } else { 2 };
    let mut best: Option<&str> = None;
    let mut best_distance = limit + 1;
    for candidate in candidates {
        if candidate == word {
            continue;
        }
        let other = candidate.chars().count();
        if other.abs_diff(length) > limit {
            continue;
        }
        let distance = edit_distance(word, candidate);
        if distance < best_distance || (distance == best_distance && best.is_some_and(|b| candidate < b)) {
            best = Some(candidate);
            best_distance = distance;
        }
    }
    if best_distance <= limit {
        best.map(str::to_string)
    } else {
        None
    }
}
