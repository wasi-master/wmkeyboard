//! A model's vocabulary, read in place from the model file.
//!
//! The `.wmlm` and `.wmpt` readers used to copy every word out of the mapped
//! file into a `HashMap<String, u32>`, and the language model into a
//! `Vec<String>` as well: 3.4 MB and 5.1 MB of native heap on a CPH2481 for
//! vocabularies of 24,576 and 49,152 words (heapprofd, 2026-11-26), for text
//! the mapping already holds. This keeps where each entry lies and the ids
//! sorted by text, some twelve bytes a word, and answers a lookup with a
//! binary search over the file's own bytes.
//!
//! Lookups answer as the maps did: a word spelled twice is its first id (the
//! maps kept the first insert), a piece spelled twice its last (they kept
//! the last).

use crate::lm::{PIECE_CONT, PIECE_START};
use crate::sort;

pub(crate) struct Vocab {
    /// Each entry's text as start and end offsets in the model's bytes, by id.
    spans: Vec<(u32, u32)>,
    /// Whole words, by text then id.
    words: Vec<u32>,
    /// Pieces that start a word, by their text after [`PIECE_START`], then id.
    start: Vec<u32>,
    /// Pieces inside a word, by their text after [`PIECE_CONT`], then id.
    cont: Vec<u32>,
}

impl Vocab {
    /// Reads `count` entries, each a little-endian u16 length and that many
    /// bytes of UTF-8, starting at `at`. `other` is asked about each entry that
    /// is not a piece and answers true for one that is not a word either (a
    /// language token), which is then left out of the word lookups. Returns
    /// the vocabulary and where the entries end; `None` when they run off the
    /// end or one is not UTF-8.
    pub(crate) fn read(b: &[u8], mut at: usize, count: usize, mut other: impl FnMut(u32, &str) -> bool) -> Option<(Vocab, usize)> {
        let mut spans = Vec::with_capacity(count);
        let (mut words, mut start, mut cont) = (Vec::with_capacity(count), Vec::new(), Vec::new());
        for i in 0..count {
            let len = u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?) as usize;
            at += 2;
            let text = std::str::from_utf8(b.get(at..at + len)?).ok()?;
            let id = i as u32;
            spans.push((u32::try_from(at).ok()?, u32::try_from(at + len).ok()?));
            at += len;
            if text.starts_with(PIECE_START) {
                start.push(id);
            } else if text.starts_with(PIECE_CONT) {
                cont.push(id);
            } else if !other(id, text) {
                words.push(id);
            }
        }
        words.shrink_to_fit();
        let mut vocab = Vocab { spans, words, start, cont };
        vocab.sort_tables(b);
        Some((vocab, at))
    }

    fn sort_tables(&mut self, b: &[u8]) {
        let spans = &self.spans;
        for (table, skip) in [(&mut self.words, 0), (&mut self.start, PIECE_START.len()), (&mut self.cont, PIECE_CONT.len())] {
            let key = |id: &u32| key_of(spans, b, *id, skip);
            sort::by(table, |x, y| key(x).cmp(key(y)).then(x.cmp(y)));
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.spans.len()
    }

    /// The text of entry `id`, markers included.
    pub(crate) fn text<'a>(&self, b: &'a [u8], id: u32) -> Option<&'a str> {
        let &(s, e) = self.spans.get(id as usize)?;
        // SAFETY: every entry was checked to be UTF-8 by `read`, and `b` is
        // the same bytes it read.
        Some(unsafe { std::str::from_utf8_unchecked(&b[s as usize..e as usize]) })
    }

    /// Whether entry `id` is a subword piece rather than a word.
    pub(crate) fn is_piece(&self, b: &[u8], id: u32) -> bool {
        self.text(b, id).is_some_and(|t| t.starts_with(PIECE_START) || t.starts_with(PIECE_CONT))
    }

    /// The id of the word spelled exactly `key`.
    pub(crate) fn word_id(&self, b: &[u8], key: &str) -> Option<u32> {
        self.find(b, &self.words, 0, key.as_bytes(), false)
    }

    /// The id of the piece `rest`: one that starts a word when `first`, else
    /// one inside a word.
    pub(crate) fn piece_id(&self, b: &[u8], first: bool, rest: &str) -> Option<u32> {
        if first {
            self.find(b, &self.start, PIECE_START.len(), rest.as_bytes(), true)
        } else {
            self.find(b, &self.cont, PIECE_CONT.len(), rest.as_bytes(), true)
        }
    }

    pub(crate) fn has_pieces(&self) -> bool {
        !self.start.is_empty()
    }

    /// `key` in `table`, comparing each entry's text after `skip` bytes:
    /// the first id spelled so, or the last when `last`.
    fn find(&self, b: &[u8], table: &[u32], skip: usize, key: &[u8], last: bool) -> Option<u32> {
        let at = |id: u32| key_of(&self.spans, b, id, skip);
        let lo = table.partition_point(|&id| at(id) < key);
        let run = table[lo..].partition_point(|&id| at(id) == key);
        match run {
            0 => None,
            _ if last => Some(table[lo + run - 1]),
            _ => Some(table[lo]),
        }
    }
}

fn key_of<'a>(spans: &[(u32, u32)], b: &'a [u8], id: u32, skip: usize) -> &'a [u8] {
    let (s, e) = spans[id as usize];
    &b[s as usize + skip..e as usize]
}
