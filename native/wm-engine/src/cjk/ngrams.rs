//! Word unigram and bigram counts for the conversion decoder: the phone's
//! `CjkNgrams`, read through JNI, and WMK Desktop's. The pack's TSV has two
//! fields for a unigram and three for a pair. Keys are 64-bit FNV-1a hashes in
//! sorted columns; the words are kept too, with each one's commonest followers,
//! for next-word suggestions.
//!
//! Like [`super::dict`], one flat byte layout whether just parsed or mapped
//! from the file an earlier parse wrote.

use super::dict::{Bytes, Stamp};
use crate::strict_math;
use std::collections::HashMap;
use std::io::{self, Write};
use std::sync::Arc;

const MAGIC: [u8; 4] = *b"WMCN";
const VERSION: u32 = 1;
const HEADER: usize = 56;

#[derive(Clone)]
pub struct CjkNgrams {
    bytes: Bytes,
    unis: usize,
    pairs: usize,
    words: usize,
    succs: usize,
    uni_counts: usize,
    bi_keys: usize,
    bi_counts: usize,
    word_at: usize,
    word_counts: usize,
    succ_start: usize,
    succ_words: usize,
    succ_counts: usize,
    text: usize,
    total: i64,
}

impl std::fmt::Debug for CjkNgrams {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "CjkNgrams({} words, {} pairs)", self.unis, self.pairs)
    }
}

impl Default for CjkNgrams {
    fn default() -> Self {
        Self::lay_out(&[], &[], &[], &[], 0, &[], &[], &[], &[], &[])
    }
}

/// Successors kept per word.
pub const SUCCESSORS: usize = 8;

fn log_backoff() -> f64 {
    strict_math::log(0.4)
}

fn hash(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for c in s.encode_utf16() {
        h ^= c as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

fn pair_hash(a: &str, b: &str) -> u64 {
    let mut h = hash(a);
    h = h.wrapping_mul(0x100_0000_01b3);
    for c in b.encode_utf16() {
        h ^= c as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

fn sorted_merge(mut v: Vec<(u64, i32)>) -> (Vec<u64>, Vec<i32>) {
    // Stable: a key listed twice keeps its last count.
    v.sort_by_key(|e| e.0);
    let mut keys: Vec<u64> = Vec::with_capacity(v.len());
    let mut counts: Vec<i32> = Vec::with_capacity(v.len());
    for (k, c) in v {
        if keys.last() == Some(&k) {
            *counts.last_mut().unwrap() = c;
        } else {
            keys.push(k);
            counts.push(c);
        }
    }
    (keys, counts)
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap_or([0; 4]))
}

fn read_u64(bytes: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap_or([0; 8]))
}

impl CjkNgrams {
    pub fn is_empty(&self) -> bool {
        self.unis == 0 && self.pairs == 0
    }

    pub fn parse(text: &str) -> CjkNgrams {
        let mut uni = Vec::new();
        let mut bi = Vec::new();
        let mut total = 0i64;
        let mut word_list: Vec<String> = Vec::new();
        let mut word_count: Vec<i32> = Vec::new();
        let mut index: HashMap<String, u32> = HashMap::new();
        let mut pairs: Vec<(u32, u32, i32)> = Vec::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let f: Vec<&str> = line.split('\t').collect();
            match f.len() {
                2 => {
                    let Ok(count) = f[1].trim().parse::<i32>() else {
                        continue;
                    };
                    if count <= 0 || f[0].is_empty() {
                        continue;
                    }
                    uni.push((hash(f[0]), count));
                    total += count as i64;
                    if !index.contains_key(f[0]) {
                        index.insert(f[0].to_string(), word_list.len() as u32);
                        word_list.push(f[0].to_string());
                        word_count.push(count);
                    }
                }
                3 => {
                    let Ok(count) = f[2].trim().parse::<i32>() else {
                        continue;
                    };
                    if count <= 0 || f[0].is_empty() || f[1].is_empty() {
                        continue;
                    }
                    bi.push((pair_hash(f[0], f[1]), count));
                    if let (Some(&a), Some(&b)) = (index.get(f[0]), index.get(f[1])) {
                        pairs.push((a, b, count));
                    }
                }
                _ => {}
            }
        }
        if uni.is_empty() && bi.is_empty() {
            return CjkNgrams::default();
        }
        let (uni_keys, uni_counts) = sorted_merge(uni);
        let (bi_keys, bi_counts) = sorted_merge(bi);
        // Words sorted, each with its commonest followers.
        let n = word_list.len();
        let mut order: Vec<u32> = (0..n as u32).collect();
        order.sort_by(|&a, &b| word_list[a as usize].cmp(&word_list[b as usize]));
        let mut rank = vec![0u32; n];
        for (sorted, &orig) in order.iter().enumerate() {
            rank[orig as usize] = sorted as u32;
        }
        let words: Vec<String> = order
            .iter()
            .map(|&o| word_list[o as usize].clone())
            .collect();
        let word_counts: Vec<i32> = order.iter().map(|&o| word_count[o as usize]).collect();
        let mut ranked: Vec<(u32, i32, u32)> = pairs
            .into_iter()
            .map(|(a, b, c)| (rank[a as usize], c, rank[b as usize]))
            .collect();
        ranked.sort_by(|x, y| x.0.cmp(&y.0).then(y.1.cmp(&x.1)).then(x.2.cmp(&y.2)));
        let mut succ_start = vec![0u32; n + 1];
        let mut succ_words = Vec::new();
        let mut succ_counts = Vec::new();
        let mut i = 0;
        for w in 0..n as u32 {
            succ_start[w as usize] = succ_words.len() as u32;
            let mut taken = 0;
            while i < ranked.len() && ranked[i].0 == w {
                if taken < SUCCESSORS {
                    succ_words.push(ranked[i].2);
                    succ_counts.push(ranked[i].1);
                    taken += 1;
                }
                i += 1;
            }
        }
        succ_start[n] = succ_words.len() as u32;
        let word_refs: Vec<&str> = words.iter().map(String::as_str).collect();
        Self::lay_out(
            &uni_keys,
            &uni_counts,
            &bi_keys,
            &bi_counts,
            total,
            &word_refs,
            &word_counts,
            &succ_start,
            &succ_words,
            &succ_counts,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn lay_out(
        uni_keys: &[u64],
        uni_counts: &[i32],
        bi_keys: &[u64],
        bi_counts: &[i32],
        total: i64,
        words: &[&str],
        word_counts: &[i32],
        succ_start: &[u32],
        succ_words: &[u32],
        succ_counts: &[i32],
    ) -> CjkNgrams {
        let text_len: usize = words.iter().map(|w| w.len()).sum();
        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        for n in [uni_keys.len(), bi_keys.len(), words.len(), succ_words.len(), text_len] {
            out.extend_from_slice(&(n as u32).to_le_bytes());
        }
        out.extend_from_slice(&[0; 4]);
        out.extend_from_slice(&total.to_le_bytes());
        out.extend_from_slice(&[0; 16]); // the stamp, set when written to a file
        uni_keys.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        uni_counts.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        bi_keys.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        bi_counts.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        let mut at = 0u32;
        for w in words {
            out.extend_from_slice(&at.to_le_bytes());
            at += w.len() as u32;
        }
        out.extend_from_slice(&at.to_le_bytes());
        word_counts.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        if succ_start.is_empty() {
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        succ_start.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        succ_words.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        succ_counts.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
        words.iter().for_each(|w| out.extend_from_slice(w.as_bytes()));
        Self::from_bytes(Arc::new(out)).unwrap_or_else(|| unreachable!("a table just laid out reads"))
    }

    /// A table over bytes [`write_to`](Self::write_to) wrote, or `None` when
    /// they are not one this build reads, or are cut short.
    pub fn from_bytes(bytes: Bytes) -> Option<CjkNgrams> {
        let b: &[u8] = (*bytes).as_ref();
        if b.len() < HEADER || b[..4] != MAGIC || read_u32(b, 4) != VERSION {
            return None;
        }
        let unis = read_u32(b, 8) as usize;
        let pairs = read_u32(b, 12) as usize;
        let words = read_u32(b, 16) as usize;
        let succs = read_u32(b, 20) as usize;
        let text_len = read_u32(b, 24) as usize;
        let uni_counts = HEADER + unis * 8;
        let bi_keys = uni_counts + unis * 4;
        let bi_counts = bi_keys + pairs * 8;
        let word_at = bi_counts + pairs * 4;
        let word_counts = word_at + (words + 1) * 4;
        let succ_start = word_counts + words * 4;
        let succ_words = succ_start + (words + 1) * 4;
        let succ_counts = succ_words + succs * 4;
        let text = succ_counts + succs * 4;
        if b.len() != text + text_len || read_u32(b, word_at + words * 4) as usize != text_len {
            return None;
        }
        let total = i64::from_le_bytes(b[32..40].try_into().ok()?);
        Some(CjkNgrams {
            unis,
            pairs,
            words,
            succs,
            uni_counts,
            bi_keys,
            bi_counts,
            word_at,
            word_counts,
            succ_start,
            succ_words,
            succ_counts,
            text,
            total,
            bytes,
        })
    }

    /// What the bytes at hand were built from; zero when not read from a file.
    pub fn stamp_of(bytes: &[u8]) -> Stamp {
        if bytes.len() < HEADER {
            return Stamp::default();
        }
        Stamp {
            length: read_u64(bytes, 40),
            modified_ms: read_u64(bytes, 48) as i64,
        }
    }

    /// The table as the bytes [`from_bytes`](Self::from_bytes) reads, marked as
    /// built from `stamp`.
    pub fn write_to(&self, out: &mut impl Write, stamp: Stamp) -> io::Result<()> {
        let b = self.raw();
        out.write_all(&b[..40])?;
        out.write_all(&stamp.length.to_le_bytes())?;
        out.write_all(&stamp.modified_ms.to_le_bytes())?;
        out.write_all(&b[HEADER..])
    }

    fn raw(&self) -> &[u8] {
        (*self.bytes).as_ref()
    }

    /// The count under `key` in a sorted key column and its parallel counts.
    fn lookup(&self, keys: usize, counts: usize, n: usize, key: u64) -> i32 {
        let b = self.raw();
        let (mut lo, mut hi) = (0, n);
        while lo < hi {
            let mid = (lo + hi) / 2;
            match read_u64(b, keys + mid * 8).cmp(&key) {
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
                std::cmp::Ordering::Equal => return read_u32(b, counts + mid * 4) as i32,
            }
        }
        0
    }

    fn word(&self, i: usize) -> &str {
        let b = self.raw();
        let a = read_u32(b, self.word_at + i * 4) as usize;
        let z = read_u32(b, self.word_at + (i + 1) * 4) as usize;
        b.get(self.text + a..self.text + z)
            .and_then(|w| std::str::from_utf8(w).ok())
            .unwrap_or("")
    }

    /// Where `word` is in the sorted words, or where it would go.
    fn word_index(&self, word: &str) -> Result<usize, usize> {
        let (mut lo, mut hi) = (0, self.words);
        while lo < hi {
            let mid = (lo + hi) / 2;
            match self.word(mid).cmp(word) {
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
                std::cmp::Ordering::Equal => return Ok(mid),
            }
        }
        Err(lo)
    }

    pub fn unigram(&self, word: &str) -> i32 {
        self.lookup(HEADER, self.uni_counts, self.unis, hash(word))
    }

    pub fn bigram(&self, previous: &str, word: &str) -> i32 {
        self.lookup(self.bi_keys, self.bi_counts, self.pairs, pair_hash(previous, word))
    }

    /// The words seen most often after `word`, commonest first.
    pub fn successors(&self, word: &str) -> Vec<(String, i32)> {
        let Ok(i) = self.word_index(word) else {
            return Vec::new();
        };
        let b = self.raw();
        let start = read_u32(b, self.succ_start + i * 4) as usize;
        let end = read_u32(b, self.succ_start + (i + 1) * 4) as usize;
        (start..end.min(self.succs))
            .map(|k| {
                let next = read_u32(b, self.succ_words + k * 4) as usize;
                (self.word(next).to_string(), read_u32(b, self.succ_counts + k * 4) as i32)
            })
            .collect()
    }

    /// Longer words that start with `prefix`, commonest first.
    pub fn completions(&self, prefix: &str, limit: usize) -> Vec<(String, i32)> {
        if prefix.is_empty() || self.words == 0 {
            return Vec::new();
        }
        let mut i = match self.word_index(prefix) {
            Ok(i) => i + 1,
            Err(i) => i,
        };
        let b = self.raw();
        let mut found = Vec::new();
        while i < self.words && self.word(i).starts_with(prefix) {
            found.push((self.word(i).to_string(), read_u32(b, self.word_counts + i * 4) as i32));
            i += 1;
        }
        found.sort_by_key(|f| std::cmp::Reverse(f.1));
        found.truncate(limit);
        found
    }

    /// `ln P(word | previous)` by stupid backoff; with no pack, the
    /// conversion table's own frequency (a unigram model).
    pub fn log_probability(
        &self,
        previous: Option<&str>,
        word: &str,
        pack_freq: i32,
        pack_total: i64,
    ) -> f64 {
        self.log_probability_mixed(previous, word, pack_freq, pack_total, 0.0)
    }

    /// [`log_probability`](Self::log_probability), interpolated when `lambda`
    /// is above 0 (libpinyin's form), so a benchmark can compare the two.
    pub fn log_probability_mixed(
        &self,
        previous: Option<&str>,
        word: &str,
        pack_freq: i32,
        pack_total: i64,
        lambda: f64,
    ) -> f64 {
        use strict_math::log;
        if self.is_empty() {
            return log(pack_freq as f64 + 1.0) - log(pack_total as f64 + 1.0);
        }
        if lambda > 0.0 {
            let unigram = (self.unigram(word) as f64 + 1.0) / (self.total as f64 + 1.0);
            let mut conditional = 0.0;
            if let Some(prev) = previous {
                let pair = self.bigram(prev, word);
                let prior = if pair > 0 { self.unigram(prev) } else { 0 };
                if prior > 0 {
                    conditional = pair as f64 / prior as f64;
                }
            }
            return log(lambda * conditional.min(1.0) + (1.0 - lambda) * unigram);
        }
        if let Some(prev) = previous {
            let pair = self.bigram(prev, word);
            if pair > 0 {
                let prior = self.unigram(prev);
                if prior > 0 {
                    return log(pair as f64) - log(prior as f64);
                }
            }
        }
        log_backoff() + log(self.unigram(word) as f64 + 1.0) - log(self.total as f64 + 1.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_followers_and_backoff() {
        let n = CjkNgrams::parse("我\t100\n喜欢\t50\n无聊\t5\n无\t10\n我\t喜欢\t30\n");
        assert_eq!(100, n.unigram("我"));
        assert_eq!(30, n.bigram("我", "喜欢"));
        assert_eq!(vec![("喜欢".to_string(), 30)], n.successors("我"));
        assert_eq!(vec![("无聊".to_string(), 5)], n.completions("无", 4));
        assert!(
            n.log_probability(Some("我"), "喜欢", 0, 0) > n.log_probability(None, "喜欢", 0, 0)
        );
        assert!(CjkNgrams::default()
            .log_probability(None, "x", 9, 99)
            .is_finite());
    }

    #[test]
    fn a_table_reads_the_same_from_the_bytes_it_wrote() {
        let n = CjkNgrams::parse("我\t100\n喜欢\t50\n我\t喜欢\t30\n");
        let stamp = Stamp { length: 9, modified_ms: 8 };
        let mut file = Vec::new();
        n.write_to(&mut file, stamp).unwrap();
        assert_eq!(stamp, CjkNgrams::stamp_of(&file));
        let back = CjkNgrams::from_bytes(Arc::new(file.clone())).unwrap();
        assert_eq!(100, back.unigram("我"));
        assert_eq!(30, back.bigram("我", "喜欢"));
        assert_eq!(vec![("喜欢".to_string(), 30)], back.successors("我"));
        assert!(CjkNgrams::from_bytes(Arc::new(file[..file.len() - 1].to_vec())).is_none());
        assert!(CjkNgrams::default().is_empty());
    }
}
