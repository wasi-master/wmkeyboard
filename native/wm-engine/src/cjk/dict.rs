//! A reading-to-word table for a conversion IME (Pinyin to Hanzi, kana to
//! kanji): the phone's `ConversionDictionary`, read through JNI, and WMK
//! Desktop's. Each pack line is `reading<TAB>word<TAB>freq`; a reading may
//! repeat across lines.
//!
//! The table is one flat byte layout whether it was parsed just now or mapped
//! from the file an earlier parse wrote: rows sorted by reading, offset
//! columns, then two text arenas. Parsing `ja_kana` takes half a second on a
//! phone and mapping the result takes about a hundredth of that, with nothing
//! on any heap. A lookup is a binary search for the first row with the
//! reading, then a walk over its contiguous run;
//! [`ConversionDictionary::prefix_range`] narrows a range by a longer prefix
//! with two more binary searches, which is what lets the decoder prune a
//! reading no word starts with.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::io::{self, Write};
use std::ops::Range;
use std::sync::Arc;

const MAGIC: [u8; 4] = *b"WMCD";
const VERSION: u32 = 1;
const HEADER: usize = 48;

/// Where a table's bytes live: a `Vec`, or a mapped file.
pub type Bytes = Arc<dyn AsRef<[u8]> + Send + Sync>;

/// What a table was built from, kept in its file so a changed pack is noticed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Stamp {
    pub length: u64,
    pub modified_ms: i64,
}

#[derive(Clone)]
pub struct ConversionDictionary {
    bytes: Bytes,
    rows: usize,
    word_at: usize,
    freqs: usize,
    units: usize,
    readings: usize,
    words: usize,
    /// Every row's frequency summed: the denominator for log P(word).
    pub total_freq: i64,
}

impl std::fmt::Debug for ConversionDictionary {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ConversionDictionary({} rows)", self.rows)
    }
}

impl Default for ConversionDictionary {
    fn default() -> Self {
        Self::build(Vec::new())
    }
}

/// One row to merge in through [`ConversionDictionary::plus`].
pub struct Row {
    pub reading: String,
    pub word: String,
    pub frequency: i32,
    /// Lifts the frequency of a word the table already lists.
    pub raises: bool,
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap_or([0; 4]))
}

impl ConversionDictionary {
    pub fn len(&self) -> usize {
        self.rows
    }

    pub fn is_empty(&self) -> bool {
        self.rows == 0
    }

    /// Whether the two share their bytes: [`plus`](Self::plus) with nothing new.
    pub fn same_table(&self, other: &ConversionDictionary) -> bool {
        Arc::ptr_eq(&self.bytes, &other.bytes)
    }

    /// Parses the pack's TSV; malformed lines are skipped.
    pub fn parse(text: &str) -> ConversionDictionary {
        let mut rows: Vec<(&str, &str, i32)> = Vec::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.splitn(3, '\t');
            let reading = parts.next().unwrap_or("").trim();
            let word = parts.next().unwrap_or("").trim();
            if reading.is_empty() || word.is_empty() {
                continue;
            }
            let freq = parts
                .next()
                .and_then(|f| f.trim().parse().ok())
                .unwrap_or(0);
            rows.push((reading, word, freq));
        }
        Self::build(rows)
    }

    fn build(mut rows: Vec<(&str, &str, i32)>) -> ConversionDictionary {
        // Stable: rows of one reading keep the order the pack gave them.
        rows.sort_by(|a, b| a.0.cmp(b.0));
        let n = rows.len();
        let rlen: usize = rows.iter().map(|r| r.0.len()).sum();
        let wlen: usize = rows.iter().map(|r| r.1.len()).sum();
        let mut out = Vec::with_capacity(HEADER + (n + 1) * 8 + n * 6 + rlen + wlen);
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        out.extend_from_slice(&(n as u32).to_le_bytes());
        out.extend_from_slice(&(rlen as u32).to_le_bytes());
        out.extend_from_slice(&(wlen as u32).to_le_bytes());
        out.extend_from_slice(&[0; 4]);
        let total: i64 = rows.iter().map(|r| r.2 as i64).sum();
        out.extend_from_slice(&total.to_le_bytes());
        out.extend_from_slice(&[0; 16]); // the stamp, set when written to a file
        let mut at = 0u32;
        for r in &rows {
            out.extend_from_slice(&at.to_le_bytes());
            at += r.0.len() as u32;
        }
        out.extend_from_slice(&at.to_le_bytes());
        at = 0;
        for r in &rows {
            out.extend_from_slice(&at.to_le_bytes());
            at += r.1.len() as u32;
        }
        out.extend_from_slice(&at.to_le_bytes());
        for r in &rows {
            out.extend_from_slice(&r.2.to_le_bytes());
        }
        for r in &rows {
            let units = r.1.encode_utf16().count().min(u16::MAX as usize) as u16;
            out.extend_from_slice(&units.to_le_bytes());
        }
        for r in &rows {
            out.extend_from_slice(r.0.as_bytes());
        }
        for r in &rows {
            out.extend_from_slice(r.1.as_bytes());
        }
        Self::from_bytes(Arc::new(out)).unwrap_or_else(|| unreachable!("a table just laid out reads"))
    }

    /// A table over bytes [`write_to`](Self::write_to) wrote, or `None` when
    /// they are not one this build reads, or are cut short.
    pub fn from_bytes(bytes: Bytes) -> Option<ConversionDictionary> {
        let b: &[u8] = (*bytes).as_ref();
        if b.len() < HEADER || b[..4] != MAGIC || read_u32(b, 4) != VERSION {
            return None;
        }
        let rows = read_u32(b, 8) as usize;
        let rlen = read_u32(b, 12) as usize;
        let wlen = read_u32(b, 16) as usize;
        let word_at = HEADER + (rows + 1) * 4;
        let freqs = word_at + (rows + 1) * 4;
        let units = freqs + rows * 4;
        let readings = units + rows * 2;
        let words = readings + rlen;
        if b.len() != words + wlen
            || read_u32(b, HEADER + rows * 4) as usize != rlen
            || read_u32(b, word_at + rows * 4) as usize != wlen
        {
            return None;
        }
        let total_freq = i64::from_le_bytes(b[24..32].try_into().ok()?);
        Some(ConversionDictionary {
            rows,
            word_at,
            freqs,
            units,
            readings,
            words,
            total_freq,
            bytes,
        })
    }

    /// What the bytes at hand were built from; zero when not read from a file.
    pub fn stamp_of(bytes: &[u8]) -> Stamp {
        if bytes.len() < HEADER {
            return Stamp::default();
        }
        Stamp {
            length: u64::from_le_bytes(bytes[32..40].try_into().unwrap_or([0; 8])),
            modified_ms: i64::from_le_bytes(bytes[40..48].try_into().unwrap_or([0; 8])),
        }
    }

    /// The table as the bytes [`from_bytes`](Self::from_bytes) reads, marked as
    /// built from `stamp`.
    pub fn write_to(&self, out: &mut impl Write, stamp: Stamp) -> io::Result<()> {
        let b = self.raw();
        out.write_all(&b[..32])?;
        out.write_all(&stamp.length.to_le_bytes())?;
        out.write_all(&stamp.modified_ms.to_le_bytes())?;
        out.write_all(&b[HEADER..])
    }

    fn raw(&self) -> &[u8] {
        (*self.bytes).as_ref()
    }

    fn reading_bytes(&self, row: usize) -> &[u8] {
        let b = self.raw();
        let a = read_u32(b, HEADER + row * 4) as usize;
        let z = read_u32(b, HEADER + (row + 1) * 4) as usize;
        b.get(self.readings + a..self.readings + z).unwrap_or(&[])
    }

    fn word_bytes(&self, row: usize) -> &[u8] {
        let b = self.raw();
        let a = read_u32(b, self.word_at + row * 4) as usize;
        let z = read_u32(b, self.word_at + (row + 1) * 4) as usize;
        b.get(self.words + a..self.words + z).unwrap_or(&[])
    }

    pub fn reading(&self, row: usize) -> &str {
        std::str::from_utf8(self.reading_bytes(row)).unwrap_or("")
    }

    pub fn word(&self, row: usize) -> &str {
        std::str::from_utf8(self.word_bytes(row)).unwrap_or("")
    }

    /// UTF-16 units in the word at `row`, without cutting it, as the phone
    /// counts them.
    pub fn word_length(&self, row: usize) -> usize {
        let b = self.raw();
        let at = self.units + row * 2;
        u16::from_le_bytes([b[at], b[at + 1]]) as usize
    }

    pub fn frequency(&self, row: usize) -> i32 {
        read_u32(self.raw(), self.freqs + row * 4) as i32
    }

    fn lower_bound(&self, key: &[u8]) -> usize {
        let (mut lo, mut hi) = (0, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            if self.reading_bytes(mid) < key {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        lo
    }

    /// The rows whose reading is exactly `reading`.
    pub fn rows_for(&self, reading: &str) -> Range<usize> {
        if reading.is_empty() || self.is_empty() {
            return 0..0;
        }
        let key = reading.as_bytes();
        let lo = self.lower_bound(key);
        let mut hi = lo;
        while hi < self.len() && self.reading_bytes(hi) == key {
            hi += 1;
        }
        lo..hi
    }

    /// `row`'s reading against `prefix`, over the prefix's length only.
    fn compare_prefix(&self, row: usize, prefix: &str) -> Ordering {
        let r = self.reading_bytes(row);
        let p = prefix.as_bytes();
        let n = r.len().min(p.len());
        match r[..n].cmp(&p[..n]) {
            Ordering::Equal if r.len() < p.len() => Ordering::Less,
            Ordering::Equal => Ordering::Equal,
            other => other,
        }
    }

    /// The rows within `within` whose reading starts with `prefix`.
    pub fn prefix_range(&self, prefix: &str, within: Range<usize>) -> Range<usize> {
        let lo = within.start.min(self.len());
        let hi = within.end.min(self.len());
        if lo >= hi {
            return 0..0;
        }
        if prefix.is_empty() {
            return lo..hi;
        }
        let bound = |inclusive: bool, mut a: usize, mut b: usize| {
            while a < b {
                let mid = (a + b) / 2;
                let c = self.compare_prefix(mid, prefix);
                let go = if inclusive {
                    c != Ordering::Greater
                } else {
                    c == Ordering::Less
                };
                if go {
                    a = mid + 1;
                } else {
                    b = mid;
                }
            }
            a
        };
        let start = bound(false, lo, hi);
        let end = bound(true, start, hi);
        start..end
    }

    /// Words for `reading` exactly, commonest first.
    pub fn exact(&self, reading: &str) -> Vec<String> {
        let mut rows: Vec<usize> = self.rows_for(reading).collect();
        rows.sort_by_key(|&r| std::cmp::Reverse(self.frequency(r)));
        let mut seen = HashSet::new();
        rows.into_iter()
            .map(|r| self.word(r).to_string())
            .filter(|w| seen.insert(w.clone()))
            .collect()
    }

    /// The exact match first, then the longest prefixes', up to `limit`.
    pub fn candidates(&self, reading: &str, limit: usize) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        let mut p = reading.to_string();
        while !p.is_empty() && out.len() < limit {
            for w in self.exact(&p) {
                if !out.contains(&w) {
                    out.push(w);
                }
            }
            p.pop();
        }
        out.truncate(limit);
        out
    }

    /// The commonest frequency among `reading`'s words of `length` UTF-16 units.
    pub fn best_frequency(&self, reading: &str, length: usize) -> i32 {
        self.rows_for(reading)
            .filter(|&r| self.word_length(r) == length)
            .map(|r| self.frequency(r))
            .max()
            .unwrap_or(0)
            .max(0)
    }

    /// The frequency below which `quantile` of the rows fall.
    pub fn frequency_quantile(&self, quantile: f64) -> i32 {
        if self.is_empty() {
            return 0;
        }
        let mut f: Vec<i32> = (0..self.rows).map(|r| self.frequency(r)).collect();
        f.sort_unstable();
        f[((f.len() - 1) as f64 * quantile.clamp(0.0, 1.0)) as usize]
    }

    /// This table with `extra` merged in (a user's imported words). A word
    /// already listed keeps its frequency unless the row raises it. New rows
    /// stay in the order they came, so equal frequencies rank the same every time.
    pub fn plus(&self, extra: &[Row]) -> ConversionDictionary {
        if extra.is_empty() {
            return self.clone();
        }
        let mut raised: HashMap<usize, i32> = HashMap::new();
        let mut fresh: Vec<(&str, &str, i32)> = Vec::new();
        let mut known: HashMap<(&str, &str), usize> = HashMap::new();
        for r in extra {
            if r.reading.is_empty() || r.word.is_empty() {
                continue;
            }
            if let Some(held) = self
                .rows_for(&r.reading)
                .find(|&row| self.word(row) == r.word)
            {
                if r.raises && r.frequency > raised.get(&held).copied().unwrap_or(self.frequency(held)) {
                    raised.insert(held, r.frequency);
                }
                continue;
            }
            let key = (r.reading.as_str(), r.word.as_str());
            match known.get(&key) {
                Some(&at) => fresh[at].2 = fresh[at].2.max(r.frequency),
                None => {
                    known.insert(key, fresh.len());
                    fresh.push((key.0, key.1, r.frequency));
                }
            }
        }
        if fresh.is_empty() && raised.is_empty() {
            return self.clone();
        }
        let mut rows: Vec<(&str, &str, i32)> = (0..self.len())
            .map(|i| {
                (
                    self.reading(i),
                    self.word(i),
                    raised.get(&i).copied().unwrap_or(self.frequency(i)),
                )
            })
            .collect();
        rows.extend(fresh);
        Self::build(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lookups_and_prefix_ranges() {
        let d = ConversionDictionary::parse(
            "ni\t你\t900\nni\t尼\t100\nnihao\t你好\t800\nhao\t好\t700\n# x\nbad\n",
        );
        assert_eq!(vec!["你", "尼"], d.exact("ni"));
        assert_eq!(4, d.len());
        let r = d.prefix_range("ni", 0..d.len());
        assert_eq!(3, r.len());
        assert_eq!(1, d.prefix_range("nih", r).len());
        assert!(d.prefix_range("nx", 0..d.len()).is_empty());
        assert_eq!(vec!["你好", "你", "尼"], d.candidates("nihao", 5));
        let more = d.plus(&[Row {
            reading: "ni".into(),
            word: "泥".into(),
            frequency: 50,
            raises: false,
        }]);
        assert_eq!(vec!["你", "尼", "泥"], more.exact("ni"));
        assert_eq!(2, d.word_length(d.rows_for("nihao").start));
    }

    #[test]
    fn a_table_reads_the_same_from_the_bytes_it_wrote() {
        let d = ConversionDictionary::parse("zh\t中\t9\nai\t爱\t5\nai\t𠮷\t2\n");
        let stamp = Stamp { length: 77, modified_ms: -3 };
        let mut file = Vec::new();
        d.write_to(&mut file, stamp).unwrap();
        assert_eq!(stamp, ConversionDictionary::stamp_of(&file));
        let back = ConversionDictionary::from_bytes(Arc::new(file.clone())).unwrap();
        assert_eq!(16, back.total_freq);
        assert_eq!(vec!["爱", "𠮷"], back.exact("ai"));
        assert_eq!(2, back.word_length(1));
        // Cut short, or some other file, is not a table.
        assert!(ConversionDictionary::from_bytes(Arc::new(file[..file.len() - 1].to_vec())).is_none());
        assert!(ConversionDictionary::from_bytes(Arc::new(vec![0u8; 64])).is_none());
        assert!(ConversionDictionary::default().is_empty());
    }
}
