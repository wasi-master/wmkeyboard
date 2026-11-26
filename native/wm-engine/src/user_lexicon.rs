//! The user's personal vocabulary: the Kotlin `UserLexicon` (core/prediction),
//! rule for rule, and its file, `learning/user_lexicon.json`, read and
//! written in the same format.
//!
//! Words the user typed, with counts, the bigrams, trigrams and skip-grams
//! between them, the language each was typed in, the capitals they are
//! written with (a streaming vote per word), and the ages compaction decays
//! by. The phone and WMK Desktop learn the same way and read each other's
//! files.
//!
//! **Keys come in folded.** Every word is stored under a key: lower case,
//! then NFC ([`word_key`]). The caller folds (`WordKey.of` on the phone) and
//! passes keys, and passes surfaces (`WordKey.surface`) only where they fold
//! back to their key; a word the caller finds unlearnable never reaches
//! [`UserLexicon::learn_word`]. A load folds and judges on its own
//! ([`UserLexicon::load`]), with the same rules. Lengths are UTF-16 units and
//! strings order as UTF-16, as Kotlin's do.
//!
//! The walk trie ([`UserTrie`]) keeps exact counts and sorted children, as
//! the Kotlin `Trie` does, so the fuzzy walk reads it exactly as it read
//! that one.
//!
//! **Memory.** The store stays resident for the whole keyboard session, so
//! it is laid out to be small: everything known about a word is one
//! [`Word`] record under one key (it was a map per field, each with its own
//! copy of the key), a head's followers are one packed buffer
//! ([`Followers`]) rather than a string each, and the trie's nodes are
//! fixed-size records with no list of their own. A store at its caps (9,000
//! words, 9,835 heads, 141,626 followers, from a 400k-word simulated history)
//! loads in 2.89 MB and 29,101 blocks, where it took 9.33 MB and 229,214,
//! and the same store learned in a session holds 3.51 MB, not 12.77. The
//! file is read and written without a `serde_json::Value` tree: reading
//! that store's 2 MB file peaked at 12.9 MB over the store instead of 37.1,
//! writing it at 2.8 MB instead of 25.1.

use crate::completer;
use crate::fuzzy::Walker;
use crate::sort;
use crate::strict_math;
use crate::word_key;
use serde::de::{self, DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::Value;
use std::borrow::Cow;
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::fmt;

/// Longest word the store keeps, in UTF-16 units.
pub const MAX_WORD_LENGTH: usize = 32;
/// Ceiling on a word's count.
pub const MAX_COUNT: i32 = 1_000_000;
const MAX_WORDS: usize = 10_000;
/// Eviction target below the cap: 10% hysteresis.
const EVICT_TO: usize = 9_000;
const MAX_BIGRAM_PREVS: usize = 5_000;
const MAX_TRIGRAM_CONTEXTS: usize = 2_000;
/// Which naming the skip stores are written under (see [`Snapshot`]).
const SKIP_SCHEMA: i64 = 1;
const MAX_SKIP1_HEADS: usize = 2_000;
const MAX_SKIP2_HEADS: usize = 1_000;
const MAX_FOLLOWERS: usize = 32;
/// Trigram contexts are keyed `prev2 NUL prev1`.
pub const TRIGRAM_SEPARATOR: char = '\0';
const HALF_LIFE_GENERATIONS: f64 = 64.0;
/// addWord's boost before #164; still reads as "added by hand" in old files.
const STICKY_MIN_COUNT: i32 = 100;
/// Ceiling on a spelling's evidence.
const MAX_CASE_VOTES: i32 = 8;

/// Kotlin's `String.compareTo`: UTF-16 code units, which differs from
/// Rust's byte order between U+E000..U+FFFF and the supplementary planes.
pub fn cmp_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

/// Kotlin's `String.length`.
pub fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

pub fn trigram_key(prev2: &str, prev1: &str) -> String {
    let mut key = String::with_capacity(prev2.len() + prev1.len() + 1);
    key.push_str(prev2);
    key.push(TRIGRAM_SEPARATOR);
    key.push_str(prev1);
    key
}

fn fits(key: &str) -> bool {
    !key.is_empty() && utf16_len(key) <= MAX_WORD_LENGTH
}

// ---- The walk trie -----------------------------------------------------------

/// Ends a child list: the root, node 0, is nobody's child or sibling.
const NO_NODE: u32 = 0;

/// A node and the edge into it. Its children are a list, in label order,
/// through `first_child` and each child's `next_sibling`, so a node is 20
/// bytes and owns no allocation: a `Vec` of children per node was 40 bytes
/// and a block of its own (a 9,000-word store's 22,175 nodes: 1.06 MB to
/// 0.44 MB).
#[derive(Clone, Copy)]
struct TrieNode {
    first_child: u32,
    next_sibling: u32,
    frequency: i32,
    /// Highest frequency anywhere under the node, itself included. Only ever
    /// raised between rebuilds, as in Kotlin, so it is an upper bound.
    max_subtree: i32,
    label: u16,
    is_word: bool,
}

impl TrieNode {
    fn new(label: u16, next_sibling: u32) -> TrieNode {
        TrieNode { first_child: NO_NODE, next_sibling, frequency: 0, max_subtree: 0, label, is_word: false }
    }
}

/// The Kotlin `Trie`: exact counts, children in label order, node ids in
/// creation order.
pub struct UserTrie {
    nodes: Vec<TrieNode>,
}

impl Default for UserTrie {
    fn default() -> Self {
        UserTrie::new()
    }
}

impl UserTrie {
    pub fn new() -> UserTrie {
        UserTrie { nodes: vec![TrieNode::new(0, NO_NODE)] }
    }

    fn descend_or_add(&mut self, word: impl IntoIterator<Item = u16>) -> u32 {
        let mut node = 0u32;
        for unit in word {
            let mut before = NO_NODE;
            let mut at = self.nodes[node as usize].first_child;
            while at != NO_NODE && self.nodes[at as usize].label < unit {
                before = at;
                at = self.nodes[at as usize].next_sibling;
            }
            if at != NO_NODE && self.nodes[at as usize].label == unit {
                node = at;
                continue;
            }
            let id = self.nodes.len() as u32;
            self.nodes.push(TrieNode::new(unit, at));
            if before == NO_NODE {
                self.nodes[node as usize].first_child = id;
            } else {
                self.nodes[before as usize].next_sibling = id;
            }
            node = id;
        }
        node
    }

    /// `insert`: the larger of the stored and the given frequency.
    pub fn insert(&mut self, word: &str, frequency: i32) {
        let units: Vec<u16> = word.encode_utf16().collect();
        self.insert_units(&units, frequency);
    }

    /// [`UserTrie::insert`] for a word as UTF-16 units, lone surrogates
    /// included, as a Kotlin `Trie` holds them.
    pub fn insert_units(&mut self, word: &[u16], frequency: i32) {
        if word.is_empty() {
            return;
        }
        let node = self.descend_or_add(word.iter().copied()) as usize;
        self.nodes[node].is_word = true;
        self.nodes[node].frequency = self.nodes[node].frequency.max(frequency);
        let f = self.nodes[node].frequency;
        self.bump_max_subtree(word.iter().copied(), f);
    }

    /// `reinforce`: the frequency raised by `boost`, the word added if absent.
    pub fn reinforce(&mut self, word: &str, boost: i32) {
        if word.is_empty() {
            return;
        }
        let node = self.descend_or_add(word.encode_utf16()) as usize;
        self.nodes[node].is_word = true;
        self.nodes[node].frequency = self.nodes[node].frequency.wrapping_add(boost);
        let f = self.nodes[node].frequency;
        self.bump_max_subtree(word.encode_utf16(), f);
    }

    fn bump_max_subtree(&mut self, word: impl IntoIterator<Item = u16>, freq: i32) {
        let mut node = 0usize;
        self.nodes[0].max_subtree = self.nodes[0].max_subtree.max(freq);
        for unit in word {
            match self.child(node as u32, unit) {
                None => return,
                Some(next) => node = next as usize,
            }
            self.nodes[node].max_subtree = self.nodes[node].max_subtree.max(freq);
        }
    }

    fn node_for(&self, word: &[u16]) -> Option<u32> {
        let mut node = 0;
        for &unit in word {
            node = self.child(node, unit)?;
        }
        Some(node)
    }

    pub fn frequency_of_units(&self, word: &[u16]) -> i32 {
        self.node_for(word).map_or(0, |n| Walker::frequency(self, n))
    }

    pub fn frequency_of(&self, word: &str) -> i32 {
        self.frequency_of_units(&word.encode_utf16().collect::<Vec<u16>>())
    }

    pub fn contains(&self, word: &str) -> bool {
        self.frequency_of(word) > 0
    }

    /// `complete`, `TrieCompleter`'s order.
    pub fn complete_units(&self, prefix: &[u16], limit: usize) -> Vec<(Vec<u16>, i32)> {
        completer::complete(self, prefix, limit)
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    /// The children of `node`, in label order.
    fn kids(&self, node: u32) -> Kids<'_> {
        Kids { nodes: &self.nodes, at: self.nodes[node as usize].first_child }
    }

    /// The trie as flat arrays, for a walker on the other side of JNI:
    /// per node its first child edge, its frequency (0 unless a word), its
    /// max-subtree bound and whether it ends a word; per edge its label and
    /// target. Node `i`'s edges are `child_start[i]..child_start[i + 1]`.
    pub fn export(&self) -> TrieExport {
        let n = self.nodes.len();
        let mut out = TrieExport {
            child_start: Vec::with_capacity(n + 1),
            labels: Vec::with_capacity(n),
            targets: Vec::with_capacity(n),
            frequency: Vec::with_capacity(n),
            max_subtree: Vec::with_capacity(n),
            is_word: Vec::with_capacity(n),
        };
        for (i, node) in self.nodes.iter().enumerate() {
            out.child_start.push(out.labels.len() as i32);
            for (label, child) in self.kids(i as u32) {
                out.labels.push(label);
                out.targets.push(child as i32);
            }
            out.frequency.push(if node.is_word { node.frequency } else { 0 });
            out.max_subtree.push(node.max_subtree);
            out.is_word.push(node.is_word);
        }
        out.child_start.push(out.labels.len() as i32);
        out
    }
}

/// A node's children as `(label, child)`, in label order.
struct Kids<'a> {
    nodes: &'a [TrieNode],
    at: u32,
}

impl Iterator for Kids<'_> {
    type Item = (u16, u32);
    fn next(&mut self) -> Option<(u16, u32)> {
        if self.at == NO_NODE {
            return None;
        }
        let id = self.at;
        let node = &self.nodes[id as usize];
        self.at = node.next_sibling;
        Some((node.label, id))
    }
}

pub struct TrieExport {
    pub child_start: Vec<i32>,
    pub labels: Vec<u16>,
    pub targets: Vec<i32>,
    pub frequency: Vec<i32>,
    pub max_subtree: Vec<i32>,
    pub is_word: Vec<bool>,
}

impl Walker for UserTrie {
    fn child(&self, node: u32, label: u16) -> Option<u32> {
        for (l, child) in self.kids(node) {
            match l.cmp(&label) {
                Ordering::Less => {}
                Ordering::Equal => return Some(child),
                Ordering::Greater => return None,
            }
        }
        None
    }
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>) {
        out.clear();
        out.extend(self.kids(node));
    }
    fn is_word(&self, node: u32) -> bool {
        self.nodes[node as usize].is_word
    }
    fn frequency(&self, node: u32) -> i32 {
        let n = &self.nodes[node as usize];
        if n.is_word {
            n.frequency
        } else {
            0
        }
    }
    fn max_subtree(&self, node: u32) -> i32 {
        self.nodes[node as usize].max_subtree
    }
}

// ---- Followers ---------------------------------------------------------------

/// A word's followers and their counts, kept in the order they rank in:
/// count descending, then UTF-16 order (the Kotlin store's `ordered`).
///
/// One buffer per head, each follower in it as its count (zigzag LEB128),
/// its length in bytes (LEB128) and its UTF-8: 6.4 bytes a follower on
/// average. A vector of `(Box<str>, i32)` was 24 bytes and a block of its
/// own per follower; a store at the caps holds 141,626 followers (4.03 MB in
/// 151,461 blocks as those, 0.91 MB in 9,835 as this). A head has at most
/// [`MAX_FOLLOWERS`] followers when learned, so every change rewrites a
/// buffer of a few hundred bytes at most. Kept in order, the ranking needs
/// no copy and the follower that ranks last, the one dropped past the cap,
/// is the last entry.
#[derive(Clone, Default, Debug)]
struct Followers {
    bytes: Box<[u8]>,
}

/// One follower as [`Followers`] holds it: its word and count, and where its
/// bytes start and end.
#[derive(Clone, Copy)]
struct Entry<'a> {
    word: &'a [u8],
    count: i32,
    start: usize,
    end: usize,
}

impl<'a> Entry<'a> {
    fn text(&self) -> &'a str {
        // Only ever written from a `&str`.
        std::str::from_utf8(self.word).unwrap_or("")
    }
}

struct Entries<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Iterator for Entries<'a> {
    type Item = Entry<'a>;
    fn next(&mut self) -> Option<Entry<'a>> {
        if self.at >= self.bytes.len() {
            return None;
        }
        let start = self.at;
        let count = unzigzag(read_varint(self.bytes, &mut self.at));
        let len = read_varint(self.bytes, &mut self.at) as usize;
        let word = &self.bytes[self.at..self.at + len];
        self.at += len;
        Some(Entry { word, count, start, end: self.at })
    }
}

fn zigzag(v: i32) -> u32 {
    ((v as u32) << 1) ^ ((v >> 31) as u32)
}

fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}

fn varint_len(v: u32) -> usize {
    ((32 - (v | 1).leading_zeros()) as usize).div_ceil(7)
}

fn push_varint(out: &mut Vec<u8>, mut v: u32) {
    while v >= 0x80 {
        out.push(v as u8 | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

fn read_varint(bytes: &[u8], at: &mut usize) -> u32 {
    let mut v = 0u32;
    let mut shift = 0;
    loop {
        let b = bytes[*at];
        *at += 1;
        v |= u32::from(b & 0x7F) << shift;
        if b < 0x80 {
            return v;
        }
        shift += 7;
    }
}

fn entry_len(word: &str, count: i32) -> usize {
    varint_len(zigzag(count)) + varint_len(word.len() as u32) + word.len()
}

fn push_entry(out: &mut Vec<u8>, word: &str, count: i32) {
    push_varint(out, zigzag(count));
    push_varint(out, word.len() as u32);
    out.extend_from_slice(word.as_bytes());
}

/// Whether `a` ranks before `b`: the higher count, then UTF-16 order.
fn outranks(a: (&str, i32), b: (&str, i32)) -> bool {
    a.1 > b.1 || (a.1 == b.1 && cmp_utf16(a.0, b.0) == Ordering::Less)
}

fn rank(all: &mut [(&str, i32)]) {
    sort::by(all, |a, b| b.1.cmp(&a.1).then_with(|| cmp_utf16(a.0, b.0)));
}

impl Followers {
    /// `entries` in the order given, which is the ranking's.
    fn of(entries: &[(&str, i32)]) -> Followers {
        let mut out = Vec::with_capacity(entries.iter().map(|&(w, c)| entry_len(w, c)).sum());
        for &(word, count) in entries {
            push_entry(&mut out, word, count);
        }
        Followers { bytes: out.into_boxed_slice() }
    }

    /// `counts` as the file holds them, in ranking order. No cap: a file
    /// keeps what it was saved with, as the map did.
    fn from_counts<'a>(counts: impl Iterator<Item = (&'a str, i32)>) -> Followers {
        let mut all: Vec<(&str, i32)> = counts.collect();
        rank(&mut all);
        Followers::of(&all)
    }

    fn iter(&self) -> Entries<'_> {
        Entries { bytes: &self.bytes, at: 0 }
    }

    fn pairs(&self) -> Vec<(&str, i32)> {
        self.iter().map(|e| (e.text(), e.count)).collect()
    }

    fn len(&self) -> usize {
        self.iter().count()
    }

    fn find(&self, next: &str) -> Option<Entry<'_>> {
        self.iter().find(|e| e.word == next.as_bytes())
    }

    fn get(&self, next: &str) -> Option<i32> {
        self.find(next).map(|e| e.count)
    }

    /// Edits the buffer as a vector, handing back exactly what it holds.
    fn edit(&mut self, f: impl FnOnce(&mut Vec<u8>)) {
        let mut bytes = std::mem::take(&mut self.bytes).into_vec();
        f(&mut bytes);
        self.bytes = bytes.into_boxed_slice();
    }

    /// Takes `next` out, returning its count.
    fn remove(&mut self, next: &str) -> Option<i32> {
        let e = self.find(next)?;
        let (count, start, end) = (e.count, e.start, e.end);
        self.edit(|bytes| {
            bytes.drain(start..end);
        });
        Some(count)
    }

    /// Puts in `next`, which it does not hold, at `count`, where it ranks.
    fn put(&mut self, next: &str, count: i32) {
        let at = self.iter().find(|e| outranks((next, count), (e.text(), e.count))).map_or(self.bytes.len(), |e| e.start);
        let len = entry_len(next, count);
        self.edit(|bytes| {
            bytes.reserve_exact(len);
            push_entry(bytes, next, count);
            bytes[at..].rotate_right(len);
        });
    }

    fn bump(&mut self, next: &str) {
        let count = self.remove(next).map_or(1, |c| c.wrapping_add(1));
        self.put(next, count);
        if self.len() > MAX_FOLLOWERS {
            self.drop_last();
        }
    }

    /// Drops the follower ranking last: the lowest count, then the word that
    /// sorts last.
    fn drop_last(&mut self) {
        if let Some(start) = self.iter().last().map(|e| e.start) {
            self.edit(|bytes| bytes.truncate(start));
        }
    }

    fn rename(&mut self, old: &str, new: &str) {
        let Some(moved) = self.remove(old) else { return };
        let count = match self.remove(new) {
            Some(c) => c.wrapping_add(moved),
            None => moved,
        };
        self.put(new, count);
    }

    fn absorb(&mut self, other: &Followers) {
        // The cap is applied after the sum, and the sums do not depend on
        // the order.
        let merged = {
            let mut all = self.pairs();
            for (next, count) in other.pairs() {
                match all.iter_mut().find(|e| e.0 == next) {
                    Some(e) => e.1 = e.1.wrapping_add(count),
                    None => all.push((next, count)),
                }
            }
            rank(&mut all);
            all.truncate(MAX_FOLLOWERS);
            Followers::of(&all)
        };
        *self = merged;
    }

    /// The first `limit` followers, best first.
    fn top(&self, limit: usize) -> Vec<String> {
        self.iter().take(limit).map(|e| e.text().to_string()).collect()
    }

    fn total(&self) -> i32 {
        self.iter().fold(0i32, |a, e| a.wrapping_add(e.count))
    }

    /// Drops every follower `gone` names, keeping the others' order.
    fn remove_where(&mut self, gone: impl Fn(&str) -> bool) {
        if !self.iter().any(|e| gone(e.text())) {
            return;
        }
        let kept = {
            let kept: Vec<(&str, i32)> = self.pairs().into_iter().filter(|&(w, _)| !gone(w)).collect();
            Followers::of(&kept)
        };
        *self = kept;
    }

    /// The counts in the order of their words' bytes, which is the order
    /// the file writes an object's keys in (`serde_json`'s map is sorted).
    fn by_bytes(&self) -> Vec<(&str, i32)> {
        let mut all = self.pairs();
        sort::by(&mut all, |a, b| a.0.cmp(b.0));
        all
    }
}

// ---- The store ---------------------------------------------------------------

/// [`Word::flags`]: the language was filed by hand (#930).
const LANG_PINNED: u8 = 1;
/// The spelling was pinned by hand (#100).
const CASE_PINNED: u8 = 2;
/// Added by hand (#164).
const BY_HAND: u8 = 4;
/// [`Word::votes`] holds the word's case vote.
const HAS_VOTES: u8 = 8;

/// Everything the store knows about one word but its spelling
/// ([`UserLexicon::word_case`], which few words have): the Kotlin store's
/// `words`, `wordGen`, `wordBorn`, `wordLang`, `caseVotes`, `langPinned`,
/// `casePinned` and `addedByHand` for it, which were a map each.
#[derive(Clone, Copy, Debug)]
struct Word {
    /// The generation it was last counted in.
    gen: i64,
    /// The generation it was first counted in.
    born: i64,
    count: i32,
    /// The evidence for its spelling, when [`HAS_VOTES`] is set.
    votes: i32,
    /// 1 + the index of its language in [`UserLexicon::langs`]; 0 for none.
    lang: u32,
    flags: u8,
}

impl Word {
    fn new(generation: i64) -> Word {
        Word { gen: generation, born: generation, count: 0, votes: 0, lang: 0, flags: 0 }
    }

    fn is(&self, flag: u8) -> bool {
        self.flags & flag != 0
    }

    fn set(&mut self, flag: u8, on: bool) {
        if on {
            self.flags |= flag;
        } else {
            self.flags &= !flag;
        }
    }

    fn votes(&self) -> Option<i32> {
        self.is(HAS_VOTES).then_some(self.votes)
    }

    fn set_votes(&mut self, votes: Option<i32>) {
        self.set(HAS_VOTES, votes.is_some());
        self.votes = votes.unwrap_or(0);
    }
}

/// The language tags words are filed under, each held once.
fn intern(langs: &mut Vec<Box<str>>, lang: &str) -> u32 {
    match langs.iter().position(|l| &**l == lang) {
        Some(i) => i as u32 + 1,
        None => {
            langs.push(lang.into());
            langs.len() as u32
        }
    }
}

#[derive(Default)]
pub struct UserLexicon {
    trie: UserTrie,
    words: HashMap<Box<str>, Word>,
    /// What [`Word::lang`] indexes.
    langs: Vec<Box<str>>,
    /// The spelling a word is written in, when it is not its key.
    word_case: HashMap<Box<str>, Box<str>>,
    bigrams: HashMap<Box<str>, Followers>,
    trigrams: HashMap<Box<str>, Followers>,
    skip1grams: HashMap<Box<str>, Followers>,
    skip2grams: HashMap<Box<str>, Followers>,
    generation: i64,
    dirty: bool,
    mutations: i64,
    /// Moves on every change to [`UserLexicon::trie`], for the caller's
    /// exported copy.
    trie_version: i64,
}

impl UserLexicon {
    pub fn new() -> UserLexicon {
        UserLexicon::default()
    }

    pub fn mutation_count(&self) -> i64 {
        self.mutations
    }

    pub fn trie_version(&self) -> i64 {
        self.trie_version
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    pub fn trie(&self) -> &UserTrie {
        &self.trie
    }

    fn reinforce(&mut self, key: &str, boost: i32) {
        self.trie.reinforce(key, boost);
        self.trie_version += 1;
    }

    /// `key`'s record, a fresh one (counted 0, born now) if it has none.
    fn word_or_new(&mut self, key: &str) -> &mut Word {
        let generation = self.generation;
        if !self.words.contains_key(key) {
            self.words.insert(key.into(), Word::new(generation));
        }
        self.words.get_mut(key).expect("just inserted")
    }

    fn count_of(&self, key: &str) -> Option<i32> {
        self.words.get(key).map(|w| w.count)
    }

    fn has(&self, key: &str, flag: u8) -> bool {
        self.words.get(key).is_some_and(|w| w.is(flag))
    }

    /// Counts the word with key `key` `count` more times. `vote` is its
    /// surface when the caller vouches for the capitals (`caseEvidence`) and
    /// it folds back to `key`. The caller has already refused unlearnable
    /// words. Returns whether it was taken.
    pub fn learn_word(&mut self, key: &str, vote: Option<&str>, count: i32, lang: &str, listed_in_lower_case: bool) -> bool {
        if key.is_empty() || utf16_len(key) > MAX_WORD_LENGTH || count <= 0 {
            return false;
        }
        // A new word's record comes first, counted 0, which is what its vote
        // reads as its lower-case evidence.
        self.word_or_new(key);
        if let Some(surface) = vote {
            self.vote_case(key, surface, 1, listed_in_lower_case);
        }
        let generation = self.generation;
        let lang = if lang.is_empty() || self.has(key, LANG_PINNED) { None } else { Some(intern(&mut self.langs, lang)) };
        let w = self.word_or_new(key);
        let before = w.count;
        let merged = (before as i64 + count as i64).min(MAX_COUNT as i64) as i32;
        w.count = merged;
        w.gen = generation;
        if let Some(lang) = lang {
            w.lang = lang;
        }
        self.reinforce(key, merged - before);
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn language_of(&self, key: &str) -> Option<&str> {
        let lang = self.words.get(key)?.lang;
        (lang != 0).then(|| &*self.langs[lang as usize - 1])
    }

    /// Files a known word under `lang`, or under every language when `lang`
    /// is empty, from the personal dictionary screen (#930). The choice is
    /// pinned, so a later sighting under another language leaves it alone.
    /// Returns whether anything changed.
    pub fn set_language(&mut self, key: &str, lang: &str) -> bool {
        if !self.words.contains_key(key) {
            return false;
        }
        let id = if lang.is_empty() { 0 } else { intern(&mut self.langs, lang) };
        let Some(w) = self.words.get_mut(key) else { return false };
        let moved = w.lang != id;
        w.lang = id;
        let pinned = !w.is(LANG_PINNED);
        w.set(LANG_PINNED, true);
        if !moved && !pinned {
            return false;
        }
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn is_language_pinned(&self, key: &str) -> bool {
        self.has(key, LANG_PINNED)
    }

    pub fn display_of(&self, key: &str) -> Option<&str> {
        self.word_case.get(key).map(|s| &**s)
    }

    /// One sighting of `surface` as the spelling of `key`, worth `weight`.
    /// `key` has a record.
    fn vote_case(&mut self, key: &str, surface: &str, weight: i32, listed_in_lower_case: bool) {
        let Some(w) = self.words.get(key).copied() else { return };
        if weight <= 0 || w.is(CASE_PINNED) {
            return;
        }
        let shape = (surface != key).then_some(surface);
        let current = self.word_case.get(key).map(|s| &**s);
        let same = shape == current;
        let standing = match w.votes() {
            Some(v) => v,
            None if current.is_some() => 0,
            None if listed_in_lower_case => MAX_CASE_VOTES,
            None => w.count.min(MAX_CASE_VOTES),
        };
        let votes = if same {
            (standing + weight).min(MAX_CASE_VOTES)
        } else {
            let left = standing - weight;
            if left > 0 {
                left
            } else {
                self.word_case.remove(key);
                if let Some(shape) = shape {
                    self.word_case.insert(key.into(), shape.into());
                }
                (-left).clamp(1, MAX_CASE_VOTES)
            }
        };
        if let Some(w) = self.words.get_mut(key) {
            w.set_votes(Some(votes));
        }
    }

    /// Settles `key`'s spelling outright; `surface` must fold to `key`.
    /// `key` has a record.
    fn set_case(&mut self, key: &str, surface: &str) {
        let votes = if surface == key {
            self.word_case.remove(key);
            None
        } else {
            self.word_case.insert(key.into(), surface.into());
            Some(MAX_CASE_VOTES)
        };
        if let Some(w) = self.words.get_mut(key) {
            w.set_votes(votes);
        }
    }

    /// `addWord`. `surface` is the trimmed word's surface when the caller
    /// vouches for its capitals and it folds back to `key`; `pin_case` pins
    /// it then.
    pub fn add_word(&mut self, key: &str, case_evidence: bool, surface: Option<&str>, boost: i32, pin_case: bool) {
        if key.is_empty() || utf16_len(key) > MAX_WORD_LENGTH || boost <= 0 {
            return;
        }
        self.word_or_new(key);
        if case_evidence {
            if let Some(surface) = surface {
                self.set_case(key, surface);
            }
        }
        let generation = self.generation;
        let w = self.word_or_new(key);
        if case_evidence && pin_case {
            w.set(CASE_PINNED, true);
        }
        w.set(BY_HAND, true);
        let before = w.count;
        let merged = (before as i64 + boost as i64).min(MAX_COUNT as i64) as i32;
        w.count = merged;
        w.gen = generation;
        self.reinforce(key, merged - before);
        self.mutations += 1;
        self.dirty = true;
    }

    pub fn is_added_by_hand(&self, key: &str) -> bool {
        self.has(key, BY_HAND)
    }

    pub fn added_generation(&self, key: &str) -> Option<i64> {
        self.words.get(key).map(|w| w.born)
    }

    /// `rename`: `new_surface` is the trimmed replacement's surface, and
    /// `surface_folds` whether it folds back to `new_key` (only then does it
    /// settle the word's capitals).
    pub fn rename(&mut self, old_key: &str, new_key: &str, new_surface: &str, surface_folds: bool) -> bool {
        if new_key.is_empty() || utf16_len(new_key) > MAX_WORD_LENGTH {
            return false;
        }
        if old_key == new_key {
            let current = self.word_case.get(old_key).map(|s| &**s).unwrap_or(old_key);
            if new_surface == current || !self.words.contains_key(old_key) {
                return false;
            }
            if surface_folds {
                self.set_case(old_key, new_surface);
            }
            if let Some(w) = self.words.get_mut(old_key) {
                w.set(CASE_PINNED, true);
            }
            self.mutations += 1;
            self.dirty = true;
            return true;
        }
        let Some(old) = self.words.remove(old_key) else { return false };
        self.word_case.remove(old_key);
        let existing = self.words.get(new_key).copied();
        let w = self.word_or_new(new_key);
        w.count = (existing.map_or(0, |e| e.count) as i64 + old.count as i64).min(MAX_COUNT as i64) as i32;
        w.gen = old.gen.max(existing.map_or(0, |e| e.gen));
        w.born = old.born.min(existing.map_or(old.born, |e| e.born));
        // A language the user chose travels with the word and wins over the
        // target's; a learned one only fills a gap a choice did not leave.
        if old.is(LANG_PINNED) {
            w.lang = old.lang;
            w.set(LANG_PINNED, true);
        } else if old.lang != 0 && !w.is(LANG_PINNED) && w.lang == 0 {
            w.lang = old.lang;
        }
        w.set(CASE_PINNED, true);
        w.set(BY_HAND, true);
        if surface_folds {
            self.set_case(new_key, new_surface);
        }
        for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
            if let Some(moved) = store.remove(old_key) {
                match store.get_mut(new_key) {
                    None => {
                        store.insert(new_key.into(), moved);
                    }
                    Some(target) => target.absorb(&moved),
                }
            }
            for followers in store.values_mut() {
                followers.rename(old_key, new_key);
            }
        }
        let sep = TRIGRAM_SEPARATOR.to_string();
        let mut contexts: Vec<Box<str>> = self
            .trigrams
            .keys()
            .filter(|key| key.split(TRIGRAM_SEPARATOR).any(|part| part == old_key))
            .cloned()
            .collect();
        sort::by(&mut contexts, |a, b| cmp_utf16(a, b));
        for context in contexts {
            let Some(moved) = self.trigrams.remove(&context) else { continue };
            let rewritten = context
                .split(TRIGRAM_SEPARATOR)
                .map(|part| if part == old_key { new_key } else { part })
                .collect::<Vec<_>>()
                .join(&sep);
            match self.trigrams.get_mut(rewritten.as_str()) {
                None => {
                    self.trigrams.insert(rewritten.into(), moved);
                }
                Some(target) => target.absorb(&moved),
            }
        }
        for followers in self.trigrams.values_mut() {
            followers.rename(old_key, new_key);
        }
        self.rebuild_trie();
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn set_count(&mut self, key: &str, count: i32) -> bool {
        let generation = self.generation;
        let Some(w) = self.words.get_mut(key) else { return false };
        w.count = count.clamp(1, MAX_COUNT);
        w.gen = generation;
        self.rebuild_trie();
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn is_case_pinned(&self, key: &str) -> bool {
        self.has(key, CASE_PINNED)
    }

    pub fn pin_case(&mut self, key: &str, pinned: bool) -> bool {
        let spelled = self.word_case.contains_key(key);
        let Some(w) = self.words.get_mut(key) else { return false };
        if w.is(CASE_PINNED) == pinned {
            return false;
        }
        w.set(CASE_PINNED, pinned);
        if spelled {
            w.set_votes(Some(MAX_CASE_VOTES));
        }
        self.mutations += 1;
        self.dirty = true;
        true
    }

    fn learn_into(store: &mut HashMap<Box<str>, Followers>, head: &str, next: &str) {
        match store.get_mut(head) {
            Some(followers) => followers.bump(next),
            None => {
                let mut followers = Followers::default();
                followers.bump(next);
                store.insert(head.into(), followers);
            }
        }
    }

    pub fn learn_bigram(&mut self, prev: &str, next: &str) {
        if !fits(prev) || !fits(next) {
            return;
        }
        Self::learn_into(&mut self.bigrams, prev, next);
        self.dirty = true;
    }

    pub fn next_words(&mut self, prev: &str, limit: usize) -> Vec<String> {
        self.bigrams.get(prev).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn learn_trigram(&mut self, a: &str, b: &str, c: &str) {
        if !fits(a) || !fits(b) || !fits(c) {
            return;
        }
        Self::learn_into(&mut self.trigrams, &trigram_key(a, b), c);
        self.dirty = true;
    }

    pub fn next_words_after(&mut self, a: &str, b: &str, limit: usize) -> Vec<String> {
        self.trigrams.get(trigram_key(a, b).as_str()).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn trigram_count(&self, a: &str, b: &str, c: &str) -> i32 {
        self.trigrams.get(trigram_key(a, b).as_str()).and_then(|f| f.get(c)).unwrap_or(0)
    }

    pub fn learn_skip1gram(&mut self, prev2: &str, next: &str) {
        if !fits(prev2) || !fits(next) {
            return;
        }
        Self::learn_into(&mut self.skip1grams, prev2, next);
        self.dirty = true;
    }

    pub fn skip1gram_count(&self, prev2: &str, next: &str) -> i32 {
        self.skip1grams.get(prev2).and_then(|f| f.get(next)).unwrap_or(0)
    }

    pub fn skip1_followers(&mut self, prev2: &str, limit: usize) -> Vec<String> {
        self.skip1grams.get(prev2).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn skip2_followers(&mut self, prev3: &str, limit: usize) -> Vec<String> {
        self.skip2grams.get(prev3).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn learn_skip2gram(&mut self, prev3: &str, next: &str) {
        if !fits(prev3) || !fits(next) {
            return;
        }
        Self::learn_into(&mut self.skip2grams, prev3, next);
        self.dirty = true;
    }

    pub fn skip2gram_count(&self, prev3: &str, next: &str) -> i32 {
        self.skip2grams.get(prev3).and_then(|f| f.get(next)).unwrap_or(0)
    }

    pub fn bigram_count(&self, prev: &str, next: &str) -> i32 {
        self.bigrams.get(prev).and_then(|f| f.get(next)).unwrap_or(0)
    }

    /// `followerCounts`: the pair counts after `prev`, in UTF-16 order.
    pub fn follower_counts(&self, prev: &str) -> Vec<(String, i32)> {
        let mut out: Vec<(String, i32)> = self
            .bigrams
            .get(prev)
            .map(|f| f.iter().map(|e| (e.text().to_string(), e.count)).collect())
            .unwrap_or_default();
        sort::by(&mut out, |a, b| cmp_utf16(&a.0, &b.0));
        out
    }

    pub fn contains(&self, key: &str) -> bool {
        self.trie.contains(key)
    }

    pub fn is_established(&self, key: &str, min_count: i32) -> bool {
        if !self.trie.contains(key) {
            return false;
        }
        min_count <= 1 || self.has(key, BY_HAND) || self.count_of(key).unwrap_or(0) >= min_count
    }

    pub fn frequency_of(&self, key: &str) -> i32 {
        self.trie.frequency_of(key)
    }

    pub fn word_count(&self) -> usize {
        self.words.len()
    }

    /// Every word in the spelling it is written in, with its count, in key
    /// order.
    pub fn all_words(&self) -> Vec<(String, i32)> {
        let mut keys: Vec<(&str, i32)> = self.words.iter().map(|(k, w)| (&**k, w.count)).collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a.0, b.0));
        keys.into_iter().map(|(k, count)| (self.display_of(k).unwrap_or(k).to_string(), count)).collect()
    }

    /// Drops everything about the words `gone` names but their pairs and
    /// triples as followers and contexts.
    fn drop_words(&mut self, gone: &[&str]) {
        for &key in gone {
            self.words.remove(key);
            self.word_case.remove(key);
            self.bigrams.remove(key);
            self.skip1grams.remove(key);
            self.skip2grams.remove(key);
        }
    }

    /// `forgetAll`, over keys.
    pub fn forget_all(&mut self, keys: &HashSet<String>) {
        if keys.is_empty() {
            return;
        }
        let gone: Vec<&str> = keys.iter().map(String::as_str).collect();
        self.drop_words(&gone);
        for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
            for followers in store.values_mut() {
                followers.remove_where(|w| keys.contains(w));
            }
        }
        self.trigrams.retain(|context, _| !context.split(TRIGRAM_SEPARATOR).any(|part| keys.contains(part)));
        for followers in self.trigrams.values_mut() {
            followers.remove_where(|w| keys.contains(w));
        }
        self.rebuild_trie();
        self.mutations += 1;
        self.dirty = true;
    }

    /// `clear`. `deleted` is whether the caller's delete of the file
    /// succeeded; if not, the store stays dirty so the next save overwrites
    /// it.
    pub fn clear(&mut self, deleted: bool) {
        self.clear_maps();
        self.rebuild_trie();
        self.mutations += 1;
        self.dirty = !deleted;
    }

    /// Everything learned dropped, the tables' room with it.
    fn clear_maps(&mut self) {
        self.words = HashMap::new();
        self.langs = Vec::new();
        self.word_case = HashMap::new();
        self.bigrams = HashMap::new();
        self.trigrams = HashMap::new();
        self.skip1grams = HashMap::new();
        self.skip2grams = HashMap::new();
    }

    fn rebuild_trie(&mut self) {
        let mut fresh = UserTrie::new();
        // Children are kept sorted and `insert` takes the larger frequency,
        // so the result does not depend on this order.
        for (word, w) in &self.words {
            fresh.insert(word, w.count);
        }
        // Growth leaves the node array at up to twice its length.
        fresh.nodes.shrink_to_fit();
        self.trie = fresh;
        self.trie_version += 1;
    }

    /// `save`'s first half: when there is anything to write, ages the store
    /// a generation, compacts it and returns the file's text; the caller
    /// writes it and calls [`UserLexicon::mark_unsaved`] if that failed.
    pub fn prepare_save(&mut self) -> Option<String> {
        if !self.dirty {
            return None;
        }
        self.generation += 1;
        self.compact_if_needed();
        self.dirty = false;
        Some(self.snapshot_json())
    }

    pub fn mark_unsaved(&mut self) {
        self.dirty = true;
    }

    fn compact_if_needed(&mut self) {
        if self.words.len() > MAX_WORDS {
            let generation = self.generation;
            let score = |w: &Word| -> f64 {
                let age = (generation - w.gen).max(0);
                let half_lives = age as f64 / HALF_LIFE_GENERATIONS;
                w.count as f64 * strict_math::pow(2.0, -half_lives)
            };
            let sticky = |w: &Word| w.is(BY_HAND) || w.count >= STICKY_MIN_COUNT;
            let mut evictable: Vec<(bool, f64, &str)> = self.words.iter().map(|(k, w)| (sticky(w), score(w), &**k)).collect();
            sort::by(&mut evictable, |a, b| {
                a.0.cmp(&b.0).then_with(|| a.1.total_cmp(&b.1)).then_with(|| cmp_utf16(a.2, b.2))
            });
            let excess = self.words.len() - EVICT_TO;
            let gone: Vec<Box<str>> = evictable.into_iter().take(excess).map(|(_, _, w)| w.into()).collect();
            let gone: Vec<&str> = gone.iter().map(|w| &**w).collect();
            // A word at a time, as Kotlin went, comes to the same: its pairs
            // as a head and as a follower go, the trigrams stay.
            self.drop_words(&gone);
            let set: HashSet<&str> = gone.iter().copied().collect();
            for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
                for followers in store.values_mut() {
                    followers.remove_where(|w| set.contains(w));
                }
            }
            self.rebuild_trie();
            self.mutations += 1;
        }
        for (store, cap) in [
            (&mut self.bigrams, MAX_BIGRAM_PREVS),
            (&mut self.trigrams, MAX_TRIGRAM_CONTEXTS),
            (&mut self.skip1grams, MAX_SKIP1_HEADS),
            (&mut self.skip2grams, MAX_SKIP2_HEADS),
        ] {
            if store.len() > cap {
                let mut heads: Vec<(i32, &str)> = store.iter().map(|(k, f)| (f.total(), &**k)).collect();
                sort::by(&mut heads, |a, b| a.0.cmp(&b.0).then_with(|| cmp_utf16(a.1, b.1)));
                let excess = store.len() - cap;
                let gone: Vec<Box<str>> = heads.into_iter().take(excess).map(|(_, head)| head.into()).collect();
                for head in gone {
                    store.remove(&head);
                }
            }
        }
    }

    // ---- The file --------------------------------------------------------------

    /// The store as the file holds it: the Kotlin `Snapshot`'s fields, those
    /// still at their defaults left out (kotlinx writes no defaults), as
    /// `serde_json` wrote them from a `Map`: compact, every object's keys in
    /// byte order (its map is a `BTreeMap`), the sets in UTF-16 order.
    ///
    /// Written straight to text: built as a `Value` first, a store at the
    /// caps took 25 MB to write a 2 MB file.
    pub fn snapshot_json(&self) -> String {
        let mut keys: Vec<(&str, &Word)> = self.words.iter().map(|(k, w)| (&**k, w)).collect();
        sort::by(&mut keys, |a, b| a.0.cmp(b.0));
        let set = |flag: u8| -> Vec<&str> {
            let mut all: Vec<&str> = keys.iter().filter(|(_, w)| w.is(flag)).map(|&(k, _)| k).collect();
            sort::by(&mut all, |a, b| cmp_utf16(a, b));
            all
        };
        let any = |flag: u8| keys.iter().any(|(_, w)| w.is(flag));
        let mut out = Json { out: Vec::new(), first: true };
        out.out.push(b'{');
        // The root's keys in byte order, as the map wrote them.
        if any(BY_HAND) {
            out.key("addedByHand");
            out.strings(&set(BY_HAND));
        }
        if !self.bigrams.is_empty() {
            out.key("bigrams");
            out.nested(&self.bigrams);
        }
        if any(CASE_PINNED) {
            out.key("casePinned");
            out.strings(&set(CASE_PINNED));
        }
        if any(HAS_VOTES) {
            out.key("caseVotes");
            out.object(keys.iter().filter_map(|&(k, w)| Some((k, i64::from(w.votes()?)))));
        }
        if self.generation != 0 {
            out.key("generation");
            out.int(self.generation);
        }
        if any(LANG_PINNED) {
            out.key("langPinned");
            out.strings(&set(LANG_PINNED));
        }
        if !self.skip1grams.is_empty() {
            out.key("skip1grams");
            out.nested(&self.skip1grams);
        }
        if !self.skip2grams.is_empty() {
            out.key("skip2grams");
            out.nested(&self.skip2grams);
        }
        out.key("skipSchema");
        out.int(SKIP_SCHEMA);
        if !self.trigrams.is_empty() {
            out.key("trigrams");
            out.nested(&self.trigrams);
        }
        if !keys.is_empty() {
            out.key("wordBorn");
            out.object(keys.iter().map(|&(k, w)| (k, w.born)));
        }
        if !self.word_case.is_empty() {
            let mut spelled: Vec<(&str, &str)> = self.word_case.iter().map(|(k, v)| (&**k, &**v)).collect();
            sort::by(&mut spelled, |a, b| a.0.cmp(b.0));
            out.key("wordCase");
            out.text_object(&spelled);
        }
        if !keys.is_empty() {
            out.key("wordGen");
            out.object(keys.iter().map(|&(k, w)| (k, w.gen)));
        }
        if any_lang(&keys) {
            let langs: Vec<(&str, &str)> =
                keys.iter().filter(|(_, w)| w.lang != 0).map(|&(k, w)| (k, &*self.langs[w.lang as usize - 1])).collect();
            out.key("wordLang");
            out.text_object(&langs);
        }
        if !keys.is_empty() {
            out.key("words");
            out.object(keys.iter().map(|&(k, w)| (k, i64::from(w.count))));
        }
        out.out.push(b'}');
        String::from_utf8(out.out).unwrap_or_default()
    }

    /// Reads `json` into this store as Kotlin's `load` did: junk dropped
    /// once (and the store marked dirty for it), legacy skip stores renamed,
    /// ages and capitals restored. False, with nothing loaded, when the text
    /// is not a snapshot this format allows.
    ///
    /// Junk is a word [`word_key::is_learnable`] refuses that was neither
    /// added by hand nor counted past the old by-hand boost; a remembered
    /// capital spelling is kept only if it still folds to its word. Both
    /// questions used to cross back to Kotlin between parsing and building,
    /// as two calls; they are the same rules here.
    pub fn load(&mut self, json: &str) -> bool {
        // Read in place, with no tree, unless the file has something that
        // takes the tree's rules to settle (see `Snapshot::read`).
        if let Some(snapshot) = Snapshot::read(json) {
            self.load_snapshot(snapshot);
            return true;
        }
        let Some(value) = parse_value(json) else {
            return false;
        };
        let Some(snapshot) = Snapshot::of_value(&value) else {
            return false;
        };
        self.load_snapshot(snapshot);
        true
    }

    fn load_snapshot(&mut self, snapshot: Snapshot<'_>) {
        let junk = |word: &str| {
            !word_key::is_learnable_str(word)
                && !snapshot.added_by_hand.contains(word)
                && snapshot.words.get(word).copied().unwrap_or(0) < STICKY_MIN_COUNT
        };
        self.words.reserve(snapshot.words.len());
        for (word, &count) in &snapshot.words {
            self.word_or_new(word).count = count;
        }
        let before = self.words.len();
        self.words.retain(|w, _| !junk(w));
        if self.words.len() != before {
            self.dirty = true;
            let words = &self.words;
            self.word_case.retain(|w, _| words.contains_key(w));
        }
        let followers = |entries: &[(Cow<str>, i32)]| {
            Followers::from_counts(entries.iter().filter(|(k, _)| !junk(k)).map(|(k, v)| (&**k, *v)))
        };
        self.bigrams.reserve(snapshot.bigrams.heads.len());
        for (prev, entries) in snapshot.bigrams.iter() {
            if junk(prev) {
                continue;
            }
            self.bigrams.insert(prev.into(), followers(entries));
        }
        self.trigrams.reserve(snapshot.trigrams.heads.len());
        for (context, entries) in snapshot.trigrams.iter() {
            if context.split(TRIGRAM_SEPARATOR).any(&junk) {
                continue;
            }
            self.trigrams.insert(context.into(), followers(entries));
        }
        let legacy = snapshot.skip_schema < SKIP_SCHEMA;
        let one_skip = if legacy { &snapshot.skip2grams } else { &snapshot.skip1grams };
        let two_skip = if legacy { &snapshot.legacy_skip3grams } else { &snapshot.skip2grams };
        if legacy && (!one_skip.is_empty() || !two_skip.is_empty()) {
            self.dirty = true;
        }
        self.skip1grams.reserve(one_skip.heads.len());
        for (prev, entries) in one_skip.iter() {
            if junk(prev) {
                continue;
            }
            self.skip1grams.insert(prev.into(), followers(entries));
        }
        self.skip2grams.reserve(two_skip.heads.len());
        for (prev, entries) in two_skip.iter() {
            if junk(prev) {
                continue;
            }
            self.skip2grams.insert(prev.into(), followers(entries));
        }
        self.generation = snapshot.generation;
        for (word, w) in self.words.iter_mut() {
            let word: &str = word;
            let gen = snapshot.word_gen.get(word).copied().unwrap_or(snapshot.generation);
            w.gen = gen;
            w.born = snapshot.word_born.get(word).copied().unwrap_or(gen);
            if let Some(lang) = snapshot.word_lang.get(word) {
                w.lang = intern(&mut self.langs, lang);
            }
            if snapshot.lang_pinned.contains(word) {
                w.set(LANG_PINNED, true);
            }
            let shape = snapshot.word_case.get(word).filter(|shape| ***shape != *word && word_key::of_str(shape) == word);
            match shape {
                Some(shape) => {
                    self.word_case.insert(word.into(), (&**shape).into());
                    let votes = snapshot.case_votes.get(word).copied().unwrap_or(1).clamp(1, MAX_CASE_VOTES);
                    w.set_votes(Some(votes));
                }
                None => {
                    if let Some(&votes) = snapshot.case_votes.get(word) {
                        w.set_votes(Some(votes.clamp(1, MAX_CASE_VOTES)));
                    }
                }
            }
            if snapshot.case_pinned.contains(word) {
                w.set(CASE_PINNED, true);
            }
            if snapshot.added_by_hand.contains(word) {
                w.set(BY_HAND, true);
            }
        }
        self.rebuild_trie();
    }

    /// `reload`: everything dropped, then `load` again (the caller runs the
    /// two halves of the load), with nothing left to save.
    pub fn reset_for_reload(&mut self) {
        self.clear_maps();
        self.rebuild_trie();
    }

    /// The end of `reload`: memory is the file again.
    pub fn finish_reload(&mut self) {
        self.mutations += 1;
        self.dirty = false;
    }
}

fn any_lang(keys: &[(&str, &Word)]) -> bool {
    keys.iter().any(|(_, w)| w.lang != 0)
}

// ---- Writing the file --------------------------------------------------------

/// Compact JSON as `serde_json` writes a `Value`, a field at a time.
struct Json {
    out: Vec<u8>,
    /// No field written yet at the root.
    first: bool,
}

impl Json {
    fn text(&mut self, s: &str) {
        // serde_json's own escaping, as the `Value` was written with.
        let _ = serde_json::to_writer(&mut self.out, s);
    }

    fn int(&mut self, n: i64) {
        let _ = serde_json::to_writer(&mut self.out, &n);
    }

    fn key(&mut self, name: &str) {
        if !self.first {
            self.out.push(b',');
        }
        self.first = false;
        self.text(name);
        self.out.push(b':');
    }

    /// `{"key":n,...}`, the keys already in byte order.
    fn object<'a>(&mut self, entries: impl Iterator<Item = (&'a str, i64)>) {
        self.out.push(b'{');
        for (i, (k, v)) in entries.enumerate() {
            if i > 0 {
                self.out.push(b',');
            }
            self.text(k);
            self.out.push(b':');
            self.int(v);
        }
        self.out.push(b'}');
    }

    fn text_object(&mut self, entries: &[(&str, &str)]) {
        self.out.push(b'{');
        for (i, &(k, v)) in entries.iter().enumerate() {
            if i > 0 {
                self.out.push(b',');
            }
            self.text(k);
            self.out.push(b':');
            self.text(v);
        }
        self.out.push(b'}');
    }

    fn strings(&mut self, items: &[&str]) {
        self.out.push(b'[');
        for (i, s) in items.iter().enumerate() {
            if i > 0 {
                self.out.push(b',');
            }
            self.text(s);
        }
        self.out.push(b']');
    }

    fn nested(&mut self, map: &HashMap<Box<str>, Followers>) {
        let mut heads: Vec<(&str, &Followers)> = map.iter().map(|(k, f)| (&**k, f)).collect();
        sort::by(&mut heads, |a, b| a.0.cmp(b.0));
        self.out.push(b'{');
        for (i, (head, followers)) in heads.into_iter().enumerate() {
            if i > 0 {
                self.out.push(b',');
            }
            self.text(head);
            self.out.push(b':');
            self.object(followers.by_bytes().into_iter().map(|(w, c)| (w, i64::from(c))));
        }
        self.out.push(b'}');
    }
}

// ---- Reading the file --------------------------------------------------------

/// A nested map of the file (`bigrams`, `trigrams`, the skip stores): each
/// head's followers a run of `entries`.
#[derive(Default, Debug)]
struct Nested<'a> {
    heads: Vec<(Cow<'a, str>, usize, usize)>,
    entries: Vec<(Cow<'a, str>, i32)>,
}

impl<'a> Nested<'a> {
    fn is_empty(&self) -> bool {
        self.heads.is_empty()
    }

    fn iter(&self) -> impl Iterator<Item = (&str, &[(Cow<'a, str>, i32)])> {
        self.heads.iter().map(|(head, from, to)| (&**head, &self.entries[*from..*to]))
    }
}

/// The parsed file, as kotlinx reads it into `Snapshot`. Its strings
/// borrow from what was parsed wherever they can.
#[derive(Default, Debug)]
struct Snapshot<'a> {
    words: HashMap<Cow<'a, str>, i32>,
    bigrams: Nested<'a>,
    generation: i64,
    word_gen: HashMap<Cow<'a, str>, i64>,
    trigrams: Nested<'a>,
    skip1grams: Nested<'a>,
    skip2grams: Nested<'a>,
    skip_schema: i64,
    legacy_skip3grams: Nested<'a>,
    word_lang: HashMap<Cow<'a, str>, Cow<'a, str>>,
    word_case: HashMap<Cow<'a, str>, Cow<'a, str>>,
    case_votes: HashMap<Cow<'a, str>, i32>,
    case_pinned: HashSet<Cow<'a, str>>,
    added_by_hand: HashSet<Cow<'a, str>>,
    word_born: HashMap<Cow<'a, str>, i64>,
    lang_pinned: HashSet<Cow<'a, str>>,
}

/// The file as a tree, the way the reader always took it: an unpaired
/// surrogate escape, which serde refuses and kotlinx would read into its
/// String, is read as U+FFFD.
fn parse_value(json: &str) -> Option<Value> {
    match serde_json::from_str(json) {
        Ok(v) => Some(v),
        Err(_) => serde_json::from_str(&repair_lone_surrogates(json)).ok(),
    }
}

impl<'a> Snapshot<'a> {
    /// Strict as kotlinx's default `Json` is about types: a value of the
    /// wrong type, a null, an Int field out of range or a fraction where an
    /// integer belongs fails the whole file. Unknown keys are ignored.
    fn of_value(value: &'a Value) -> Option<Snapshot<'a>> {
        let root = value.as_object()?;
        let mut s = Snapshot::default();
        for (key, v) in root {
            match key.as_str() {
                "words" => s.words = int_map(v)?,
                "bigrams" => s.bigrams = nested(v)?,
                "generation" => s.generation = v.as_i64()?,
                "wordGen" => s.word_gen = long_map(v)?,
                "trigrams" => s.trigrams = nested(v)?,
                "skip1grams" => s.skip1grams = nested(v)?,
                "skip2grams" => s.skip2grams = nested(v)?,
                "skipSchema" => s.skip_schema = int(v)? as i64,
                "skip3grams" => s.legacy_skip3grams = nested(v)?,
                "wordLang" => s.word_lang = string_map(v)?,
                "wordCase" => s.word_case = string_map(v)?,
                "caseVotes" => s.case_votes = int_map(v)?,
                "casePinned" => s.case_pinned = string_set(v)?,
                "addedByHand" => s.added_by_hand = string_set(v)?,
                "wordBorn" => s.word_born = long_map(v)?,
                "langPinned" => s.lang_pinned = string_set(v)?,
                _ => {}
            }
        }
        Some(s)
    }

    /// [`Snapshot::of_value`] read straight off the text, its strings
    /// borrowed from it: no tree, whose blocks were most of the 37 MB the
    /// old reader peaked at on a 2 MB file.
    ///
    /// Gives up, for the caller to take the tree's way, on anything whose
    /// outcome only the tree's rules settle: a malformed file (and the
    /// surrogate repair), a value of the wrong type, and a key written twice
    /// in one object, where the tree keeps the last. A file this keeps
    /// reads the same both ways: the same parser, the same checks on every
    /// value, the unknown fields' included.
    fn read(json: &'a str) -> Option<Snapshot<'a>> {
        let mut de = serde_json::Deserializer::from_str(json);
        let snapshot = (&mut de).deserialize_any(RootVisitor).ok()?;
        de.end().ok()?;
        Some(snapshot)
    }
}

fn int(v: &Value) -> Option<i32> {
    i32::try_from(v.as_i64()?).ok()
}

fn int_map(v: &Value) -> Option<HashMap<Cow<'_, str>, i32>> {
    v.as_object()?.iter().map(|(k, v)| Some((Cow::Borrowed(k.as_str()), int(v)?))).collect()
}

fn long_map(v: &Value) -> Option<HashMap<Cow<'_, str>, i64>> {
    v.as_object()?.iter().map(|(k, v)| Some((Cow::Borrowed(k.as_str()), v.as_i64()?))).collect()
}

fn string_map(v: &Value) -> Option<HashMap<Cow<'_, str>, Cow<'_, str>>> {
    v.as_object()?.iter().map(|(k, v)| Some((Cow::Borrowed(k.as_str()), Cow::Borrowed(v.as_str()?)))).collect()
}

fn nested(v: &Value) -> Option<Nested<'_>> {
    let mut out = Nested::default();
    for (head, inner) in v.as_object()? {
        let from = out.entries.len();
        for (k, v) in inner.as_object()? {
            out.entries.push((Cow::Borrowed(k.as_str()), int(v)?));
        }
        out.heads.push((Cow::Borrowed(head.as_str()), from, out.entries.len()));
    }
    Some(out)
}

fn string_set(v: &Value) -> Option<HashSet<Cow<'_, str>>> {
    v.as_array()?.iter().map(|v| v.as_str().map(Cow::Borrowed)).collect()
}

/// Gives up on the in-place read (see [`Snapshot::read`]).
fn give_up<E: de::Error>() -> E {
    E::custom("left to the tree")
}

/// A string, borrowed from the text unless it had escapes.
#[derive(Clone, Copy)]
struct Text;

impl<'de> Visitor<'de> for Text {
    type Value = Cow<'de, str>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a string")
    }
    fn visit_borrowed_str<E: de::Error>(self, v: &'de str) -> Result<Cow<'de, str>, E> {
        Ok(Cow::Borrowed(v))
    }
    fn visit_str<E: de::Error>(self, v: &str) -> Result<Cow<'de, str>, E> {
        Ok(Cow::Owned(v.to_owned()))
    }
    fn visit_string<E: de::Error>(self, v: String) -> Result<Cow<'de, str>, E> {
        Ok(Cow::Owned(v))
    }
}

impl<'de> DeserializeSeed<'de> for Text {
    type Value = Cow<'de, str>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Cow<'de, str>, D::Error> {
        // `any`, as the tree reads a value; a map key reads as a string
        // either way.
        d.deserialize_any(self)
    }
}

/// An integer `Value::as_i64` takes: no fraction, no exponent, in range.
#[derive(Clone, Copy)]
struct Long;

impl<'de> Visitor<'de> for Long {
    type Value = i64;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an integer")
    }
    fn visit_i64<E: de::Error>(self, v: i64) -> Result<i64, E> {
        Ok(v)
    }
    fn visit_u64<E: de::Error>(self, v: u64) -> Result<i64, E> {
        i64::try_from(v).map_err(|_| give_up())
    }
}

impl<'de> DeserializeSeed<'de> for Long {
    type Value = i64;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<i64, D::Error> {
        d.deserialize_any(self)
    }
}

/// An `Int` field: an integer in i32's range.
#[derive(Clone, Copy)]
struct Int;

impl<'de> DeserializeSeed<'de> for Int {
    type Value = i32;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<i32, D::Error> {
        i32::try_from(d.deserialize_any(Long)?).map_err(|_| give_up())
    }
}

/// Any value, checked as the tree would check it and dropped: an unknown
/// field.
struct Skip;

impl<'de> Visitor<'de> for Skip {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a value")
    }
    fn visit_bool<E: de::Error>(self, _: bool) -> Result<(), E> {
        Ok(())
    }
    fn visit_i64<E: de::Error>(self, _: i64) -> Result<(), E> {
        Ok(())
    }
    fn visit_u64<E: de::Error>(self, _: u64) -> Result<(), E> {
        Ok(())
    }
    fn visit_f64<E: de::Error>(self, _: f64) -> Result<(), E> {
        Ok(())
    }
    fn visit_str<E: de::Error>(self, _: &str) -> Result<(), E> {
        Ok(())
    }
    fn visit_unit<E: de::Error>(self) -> Result<(), E> {
        Ok(())
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
        while seq.next_element_seed(Skip)?.is_some() {}
        Ok(())
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<(), A::Error> {
        while map.next_key_seed(Skip)?.is_some() {
            map.next_value_seed(Skip)?;
        }
        Ok(())
    }
}

impl<'de> DeserializeSeed<'de> for Skip {
    type Value = ();
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_any(self)
    }
}

/// An object of `V`s, keyed by string; a key twice gives up.
struct MapOf<V>(V);

impl<'de, V: DeserializeSeed<'de> + Copy> Visitor<'de> for MapOf<V> {
    type Value = HashMap<Cow<'de, str>, V::Value>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an object")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
        let mut out = HashMap::new();
        while let Some(key) = map.next_key_seed(Text)? {
            let value = map.next_value_seed(self.0)?;
            if out.insert(key, value).is_some() {
                return Err(give_up());
            }
        }
        Ok(out)
    }
}

impl<'de, V: DeserializeSeed<'de> + Copy> DeserializeSeed<'de> for MapOf<V> {
    type Value = HashMap<Cow<'de, str>, V::Value>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_any(self)
    }
}

/// An array of strings, read into a set.
struct SetOf;

impl<'de> Visitor<'de> for SetOf {
    type Value = HashSet<Cow<'de, str>>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an array")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
        let mut out = HashSet::new();
        while let Some(s) = seq.next_element_seed(Text)? {
            out.insert(s);
        }
        Ok(out)
    }
}

impl<'de> DeserializeSeed<'de> for SetOf {
    type Value = HashSet<Cow<'de, str>>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_any(self)
    }
}

/// A nested map: an object of objects of `Int`s.
struct NestedOf;

impl<'de> Visitor<'de> for NestedOf {
    type Value = Nested<'de>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an object")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Nested<'de>, A::Error> {
        let mut out = Nested::default();
        while let Some(head) = map.next_key_seed(Text)? {
            let from = out.entries.len();
            map.next_value_seed(Inner(&mut out.entries))?;
            if twice(out.entries[from..].iter().map(|(k, _)| &**k)) {
                return Err(give_up());
            }
            out.heads.push((head, from, out.entries.len()));
        }
        if twice(out.heads.iter().map(|(h, _, _)| &**h)) {
            return Err(give_up());
        }
        Ok(out)
    }
}

impl<'de> DeserializeSeed<'de> for NestedOf {
    type Value = Nested<'de>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<Nested<'de>, D::Error> {
        d.deserialize_any(self)
    }
}

/// One head's followers, onto the end of the nested map's run.
struct Inner<'x, 'de>(&'x mut Vec<(Cow<'de, str>, i32)>);

impl<'de> Visitor<'de> for Inner<'_, 'de> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an object")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<(), A::Error> {
        while let Some(key) = map.next_key_seed(Text)? {
            let count = map.next_value_seed(Int)?;
            self.0.push((key, count));
        }
        Ok(())
    }
}

impl<'de> DeserializeSeed<'de> for Inner<'_, 'de> {
    type Value = ();
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_any(self)
    }
}

/// Whether any string comes twice. Files this store writes have their
/// keys in byte order, which settles it in one pass.
fn twice<'s>(keys: impl Iterator<Item = &'s str> + Clone) -> bool {
    let mut sorted = true;
    let mut last: Option<&str> = None;
    for k in keys.clone() {
        if last.is_some_and(|l| l >= k) {
            sorted = false;
            break;
        }
        last = Some(k);
    }
    if sorted {
        return false;
    }
    let mut all: Vec<&str> = keys.collect();
    sort::by(&mut all, |a, b| a.cmp(b));
    all.windows(2).any(|w| w[0] == w[1])
}

/// Which root field a key names, as a bit, for seeing it twice.
fn field_bit(key: &str) -> u32 {
    const FIELDS: [&str; 16] = [
        "words",
        "bigrams",
        "generation",
        "wordGen",
        "trigrams",
        "skip1grams",
        "skip2grams",
        "skipSchema",
        "skip3grams",
        "wordLang",
        "wordCase",
        "caseVotes",
        "casePinned",
        "addedByHand",
        "wordBorn",
        "langPinned",
    ];
    FIELDS.iter().position(|f| *f == key).map_or(0, |i| 1 << i)
}

struct RootVisitor;

impl<'de> Visitor<'de> for RootVisitor {
    type Value = Snapshot<'de>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a lexicon")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Snapshot<'de>, A::Error> {
        let mut s = Snapshot::default();
        let mut seen = 0u32;
        while let Some(key) = map.next_key_seed(Text)? {
            let bit = field_bit(&key);
            if seen & bit != 0 {
                return Err(give_up());
            }
            seen |= bit;
            match &*key {
                "words" => s.words = map.next_value_seed(MapOf(Int))?,
                "bigrams" => s.bigrams = map.next_value_seed(NestedOf)?,
                "generation" => s.generation = map.next_value_seed(Long)?,
                "wordGen" => s.word_gen = map.next_value_seed(MapOf(Long))?,
                "trigrams" => s.trigrams = map.next_value_seed(NestedOf)?,
                "skip1grams" => s.skip1grams = map.next_value_seed(NestedOf)?,
                "skip2grams" => s.skip2grams = map.next_value_seed(NestedOf)?,
                "skipSchema" => s.skip_schema = map.next_value_seed(Int)? as i64,
                "skip3grams" => s.legacy_skip3grams = map.next_value_seed(NestedOf)?,
                "wordLang" => s.word_lang = map.next_value_seed(MapOf(Text))?,
                "wordCase" => s.word_case = map.next_value_seed(MapOf(Text))?,
                "caseVotes" => s.case_votes = map.next_value_seed(MapOf(Int))?,
                "casePinned" => s.case_pinned = map.next_value_seed(SetOf)?,
                "addedByHand" => s.added_by_hand = map.next_value_seed(SetOf)?,
                "wordBorn" => s.word_born = map.next_value_seed(MapOf(Long))?,
                "langPinned" => s.lang_pinned = map.next_value_seed(SetOf)?,
                _ => map.next_value_seed(Skip)?,
            }
        }
        Ok(s)
    }
}

/// `\uD8xx` or `\uDCxx` escapes that are not half of a pair, as `�`.
fn repair_lone_surrogates(json: &str) -> String {
    let bytes = json.as_bytes();
    let mut out = String::with_capacity(json.len());
    let mut i = 0;
    let hex = |at: usize| -> Option<u16> {
        let s = json.get(at..at + 4)?;
        u16::from_str_radix(s, 16).ok()
    };
    while i < bytes.len() {
        if bytes[i] == b'\\' && i + 1 < bytes.len() {
            if bytes[i + 1] == b'u' {
                if let Some(unit) = hex(i + 2) {
                    if (0xD800..0xDC00).contains(&unit) {
                        let pair = json.get(i + 6..i + 8) == Some("\\u") && hex(i + 8).is_some_and(|u| (0xDC00..0xE000).contains(&u));
                        if pair {
                            out.push_str(&json[i..i + 12]);
                            i += 12;
                        } else {
                            out.push_str("\\uFFFD");
                            i += 6;
                        }
                        continue;
                    }
                    if (0xDC00..0xE000).contains(&unit) {
                        out.push_str("\\uFFFD");
                        i += 6;
                        continue;
                    }
                }
            }
            // Any other escape is copied whole, so `\\u` is not read as one.
            out.push_str(&json[i..i + 2]);
            i += 2;
            continue;
        }
        let ch = json[i..].chars().next().unwrap_or('\u{FFFD}');
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn load(json: &str) -> UserLexicon {
        let mut lexicon = UserLexicon::new();
        assert!(lexicon.load(json), "parses");
        lexicon
    }

    #[test]
    fn learns_counts_and_round_trips_through_the_file() {
        let mut lex = UserLexicon::new();
        assert!(lex.learn_word("boston", Some("Boston"), 1, "en", false));
        assert!(lex.learn_word("boston", Some("Boston"), 2, "", false));
        lex.learn_bigram("in", "boston");
        lex.learn_trigram("live", "in", "boston");
        lex.add_word("wmk", true, Some("WMK"), 1, true);
        assert_eq!(3, lex.frequency_of("boston"));
        assert_eq!(Some("Boston"), lex.display_of("boston"));
        assert_eq!(Some("en"), lex.language_of("boston"));
        let json = lex.prepare_save().expect("dirty");
        assert!(lex.prepare_save().is_none());
        let back = load(&json);
        assert_eq!(3, back.frequency_of("boston"));
        assert_eq!(1, back.bigram_count("in", "boston"));
        assert_eq!(1, back.trigram_count("live", "in", "boston"));
        assert!(back.is_added_by_hand("wmk"));
        assert!(back.is_case_pinned("wmk"));
        assert_eq!(Some("WMK"), back.display_of("wmk"));
        assert_eq!(lex.all_words(), back.all_words());
    }

    #[test]
    fn a_language_set_by_hand_stays_put_and_round_trips() {
        let mut lex = UserLexicon::new();
        lex.learn_word("boston", None, 1, "en", false);
        lex.learn_word("adda", None, 1, "bn", false);
        lex.add_word("wmk", false, None, 1, false);
        assert!(!lex.set_language("unknown", "en"));
        // Untagged by hand: typing it under English again does not re-tag it.
        assert!(lex.set_language("boston", ""));
        lex.learn_word("boston", None, 1, "en", false);
        assert_eq!(None, lex.language_of("boston"));
        assert!(lex.set_language("wmk", "bn"));
        assert!(!lex.set_language("wmk", "bn"), "already there");
        lex.learn_word("wmk", None, 1, "en", false);
        assert_eq!(Some("bn"), lex.language_of("wmk"));
        // A word nobody filed still follows the language it is typed in.
        lex.learn_word("adda", None, 1, "hi", false);
        assert_eq!(Some("hi"), lex.language_of("adda"));
        let back = load(&lex.prepare_save().expect("dirty"));
        assert_eq!(None, back.language_of("boston"));
        assert!(back.is_language_pinned("boston"));
        assert_eq!(Some("bn"), back.language_of("wmk"));
        assert!(back.is_language_pinned("wmk"));
        assert_eq!(Some("hi"), back.language_of("adda"));
        assert!(!back.is_language_pinned("adda"));
    }

    #[test]
    fn a_file_from_before_language_pins_keeps_its_tags_unpinned() {
        let lex = load(r#"{"words":{"adda":2,"wmk":1},"wordLang":{"adda":"bn"},"addedByHand":["wmk"]}"#);
        assert_eq!(Some("bn"), lex.language_of("adda"));
        assert_eq!(None, lex.language_of("wmk"));
        assert!(!lex.is_language_pinned("adda"));
        assert!(!lex.is_dirty());
    }

    #[test]
    fn a_respelling_carries_a_chosen_language_and_forgetting_drops_it() {
        let mut lex = UserLexicon::new();
        lex.learn_word("colour", None, 1, "en", false);
        lex.learn_word("color", None, 1, "en", false);
        lex.set_language("colour", "");
        assert!(lex.rename("colour", "color", "color", true));
        assert_eq!(None, lex.language_of("color"));
        assert!(lex.is_language_pinned("color"));
        lex.forget_all(&HashSet::from(["color".to_string()]));
        assert!(!lex.is_language_pinned("color"));
        // A learned tag fills in where the target has none.
        lex.learn_word("nosh", None, 1, "en", false);
        lex.add_word("noshe", false, None, 1, false);
        assert!(lex.rename("nosh", "noshe", "noshe", true));
        assert_eq!(Some("en"), lex.language_of("noshe"));
        assert!(!lex.is_language_pinned("noshe"));
    }

    #[test]
    fn a_full_follower_set_drops_the_last_ranked() {
        let mut lex = UserLexicon::new();
        for i in 0..32 {
            lex.learn_bigram("a", &format!("w{i:02}"));
        }
        lex.learn_bigram("a", "w00");
        lex.learn_bigram("a", "zz");
        // 33 followers: the lowest count (1), and of those the last word.
        assert_eq!(0, lex.bigram_count("a", "zz"));
        assert_eq!(2, lex.bigram_count("a", "w00"));
        assert_eq!(32, lex.next_words("a", 100).len());
    }

    #[test]
    fn junk_is_dropped_on_load_and_legacy_skip_names_are_read() {
        let json = r#"{"words":{"ok":3,"bad!":2,"kept!":150},"bigrams":{"ok":{"bad!":1,"ok":2}},"skip2grams":{"ok":{"ok":4}},"skipSchema":0,"generation":7}"#;
        let lex = load(json);
        assert!(lex.is_dirty());
        assert_eq!(0, lex.frequency_of("bad!"));
        assert_eq!(150, lex.frequency_of("kept!"));
        assert_eq!(0, lex.bigram_count("ok", "bad!"));
        assert_eq!(2, lex.bigram_count("ok", "ok"));
        assert_eq!(4, lex.skip1gram_count("ok", "ok"));
    }

    #[test]
    fn a_wrong_type_fails_the_whole_file_and_a_lone_surrogate_is_repaired() {
        for broken in [r#"{"words":{"a":1.5}}"#, r#"{"words":{"a":null}}"#, r#"{"words":{"a":3000000000}}"#] {
            let mut lex = UserLexicon::new();
            assert!(!lex.load(broken), "{broken}");
            assert_eq!(0, lex.word_count());
        }
        assert!(UserLexicon::new().load(r#"{"words":{"a":1},"unknown":[1,2]}"#));
        let value = parse_value(r#"{"words":{"x\ud83d":1,"😀":2}}"#).expect("repaired");
        let snapshot = Snapshot::of_value(&value).expect("a snapshot");
        assert!(snapshot.words.contains_key("x\u{FFFD}"));
        assert!(snapshot.words.contains_key("😀"));
    }

    #[test]
    fn a_load_folds_capitals_and_judges_words_itself() {
        // A capital spelling is kept only where it folds to its word, by the
        // runtime's fold: İ is i + U+0307, not i.
        let json = r#"{"words":{"istanbul":5,"i̇stanbul":4,"οδοσ":2,"οδος":2,"x©":3},"wordCase":{"istanbul":"İstanbul","i̇stanbul":"İstanbul","οδοσ":"ΟΔΟΣ","οδος":"ΟΔΟΣ"}}"#;
        let lex = load(json);
        assert_eq!(None, lex.display_of("istanbul"));
        assert_eq!(Some("İstanbul"), lex.display_of("i\u{307}stanbul"));
        // ΟΔΟΣ folds with a final sigma.
        assert_eq!(None, lex.display_of("οδο\u{3c3}"));
        assert_eq!(Some("ΟΔΟΣ"), lex.display_of("οδο\u{3c2}"));
        // ends on a symbol: junk, dropped once.
        assert_eq!(0, lex.frequency_of("x\u{a9}"));
        assert!(lex.is_dirty());
    }

    #[test]
    fn the_trie_keeps_exact_counts_and_label_order() {
        let mut trie = UserTrie::new();
        trie.reinforce("ba", 3000);
        trie.reinforce("ab", 1);
        trie.insert("aa", 7);
        let mut out = Vec::new();
        trie.children_into(0, &mut out);
        assert_eq!(vec![b'a' as u16, b'b' as u16], out.iter().map(|c| c.0).collect::<Vec<_>>());
        assert_eq!(3000, trie.frequency_of("ba"));
        assert_eq!(3000, trie.max_subtree(0));
        let done = trie.complete_units(&[b'a' as u16], 5);
        assert_eq!(vec![("aa".encode_utf16().collect::<Vec<u16>>(), 7), ("ab".encode_utf16().collect(), 1)], done);
        // Node ids in creation order, each child list in label order.
        let t = trie.export();
        assert_eq!(vec![0, 2, 3, 3, 5, 5, 5], t.child_start);
        assert_eq!(vec![b'a' as u16, b'b' as u16, b'a' as u16, b'a' as u16, b'b' as u16], t.labels);
        assert_eq!(vec![3, 1, 2, 5, 4], t.targets);
    }

    /// The Kotlin-era follower list, as a plain vector re-sorted on every
    /// change: what [`Followers`] has to match.
    #[derive(Default)]
    struct Model(Vec<(String, i32)>);

    impl Model {
        fn rank(&mut self) {
            sort::by(&mut self.0, |a, b| b.1.cmp(&a.1).then_with(|| cmp_utf16(&a.0, &b.0)));
        }
        fn add(&mut self, next: &str, count: i32) {
            match self.0.iter_mut().find(|(k, _)| k == next) {
                Some((_, c)) => *c = c.wrapping_add(count),
                None => self.0.push((next.to_string(), 0i32.wrapping_add(count))),
            }
        }
        fn bump(&mut self, next: &str) {
            self.add(next, 1);
            self.rank();
            if self.0.len() > MAX_FOLLOWERS {
                self.0.pop();
            }
        }
        fn remove(&mut self, next: &str) -> Option<i32> {
            let at = self.0.iter().position(|(k, _)| k == next)?;
            Some(self.0.remove(at).1)
        }
        fn rename(&mut self, old: &str, new: &str) {
            let Some(moved) = self.remove(old) else { return };
            self.add(new, moved);
            self.rank();
        }
        fn absorb(&mut self, other: &Model) {
            for (next, count) in &other.0 {
                self.add(next, *count);
            }
            self.rank();
            self.0.truncate(MAX_FOLLOWERS);
        }
    }

    #[test]
    fn packed_followers_match_the_vector_they_replace() {
        let words = ["a", "b", "é", "😀", "\u{e000}", "zz", "aa", "ab", "longer-word", "x"];
        let mut seed = 7u64;
        let mut next = move |n: usize| {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((seed >> 33) % n as u64) as usize
        };
        for round in 0..200 {
            let mut f = Followers::default();
            let mut m = Model::default();
            // A file's list: any length, any counts, negative ones too.
            if round % 3 == 0 {
                let n = next(40);
                let mut file: Vec<(String, i32)> = Vec::new();
                for i in 0..n {
                    let w = format!("{}{i}", words[next(words.len())]);
                    let c = [1, 2, 3, 64, 200, -5, i32::MAX, i32::MIN][next(8)];
                    file.push((w, c));
                }
                f = Followers::from_counts(file.iter().map(|(w, c)| (w.as_str(), *c)));
                m.0 = file;
                m.rank();
            }
            for _ in 0..300 {
                let w = words[next(words.len())];
                match next(10) {
                    0 => {
                        assert_eq!(m.remove(w), f.remove(w));
                    }
                    1 => {
                        let to = words[next(words.len())];
                        m.rename(w, to);
                        f.rename(w, to);
                    }
                    2 => {
                        let mut om = Model::default();
                        for _ in 0..next(40) {
                            om.bump(words[next(words.len())]);
                        }
                        let of = Followers::of(&om.0.iter().map(|(w, c)| (w.as_str(), *c)).collect::<Vec<_>>());
                        m.absorb(&om);
                        f.absorb(&of);
                    }
                    3 => {
                        m.0.retain(|(k, _)| k != w);
                        f.remove_where(|k| k == w);
                    }
                    _ => {
                        m.bump(w);
                        f.bump(w);
                    }
                }
                let got: Vec<(String, i32)> = f.pairs().into_iter().map(|(w, c)| (w.to_string(), c)).collect();
                assert_eq!(m.0, got);
                assert_eq!(f.bytes.len(), f.iter().map(|e| entry_len(e.text(), e.count)).sum::<usize>());
                for (k, c) in &m.0 {
                    assert_eq!(Some(*c), f.get(k));
                }
            }
        }
    }

    /// The writer the file had: a `Value` built and printed.
    fn snapshot_by_value(lex: &UserLexicon) -> String {
        use serde_json::Map;
        fn texts<'a>(all: impl Iterator<Item = &'a str>) -> Value {
            let mut all: Vec<&str> = all.collect();
            sort::by(&mut all, |a, b| cmp_utf16(a, b));
            Value::Array(all.into_iter().map(|s| Value::String(s.to_string())).collect())
        }
        fn nested(map: &HashMap<Box<str>, Followers>) -> Value {
            let mut out = Map::new();
            for (k, f) in map {
                let mut counts = Map::new();
                for (w, c) in f.pairs() {
                    counts.insert(w.to_string(), c.into());
                }
                out.insert(k.to_string(), Value::Object(counts));
            }
            Value::Object(out)
        }
        let mut out = Map::new();
        let words = |f: &dyn Fn(&Word) -> Option<Value>| -> Value {
            let mut m = Map::new();
            for (k, w) in &lex.words {
                if let Some(v) = f(w) {
                    m.insert(k.to_string(), v);
                }
            }
            Value::Object(m)
        };
        let flagged = |flag: u8| lex.words.iter().filter(move |(_, w)| w.is(flag)).map(|(k, _)| &**k);
        if !lex.words.is_empty() {
            out.insert("words".into(), words(&|w| Some(w.count.into())));
            out.insert("wordGen".into(), words(&|w| Some(w.gen.into())));
            out.insert("wordBorn".into(), words(&|w| Some(w.born.into())));
        }
        if !lex.bigrams.is_empty() {
            out.insert("bigrams".into(), nested(&lex.bigrams));
        }
        if lex.generation != 0 {
            out.insert("generation".into(), lex.generation.into());
        }
        for (name, map) in [("trigrams", &lex.trigrams), ("skip1grams", &lex.skip1grams), ("skip2grams", &lex.skip2grams)] {
            if !map.is_empty() {
                out.insert(name.into(), nested(map));
            }
        }
        out.insert("skipSchema".into(), SKIP_SCHEMA.into());
        if lex.words.values().any(|w| w.lang != 0) {
            out.insert(
                "wordLang".into(),
                words(&|w| (w.lang != 0).then(|| Value::String(lex.langs[w.lang as usize - 1].to_string()))),
            );
        }
        if !lex.word_case.is_empty() {
            let mut m = Map::new();
            for (k, v) in &lex.word_case {
                m.insert(k.to_string(), Value::String(v.to_string()));
            }
            out.insert("wordCase".into(), Value::Object(m));
        }
        if lex.words.values().any(|w| w.is(HAS_VOTES)) {
            out.insert("caseVotes".into(), words(&|w| w.votes().map(Value::from)));
        }
        for (name, flag) in [("casePinned", CASE_PINNED), ("addedByHand", BY_HAND), ("langPinned", LANG_PINNED)] {
            if lex.words.values().any(|w| w.is(flag)) {
                out.insert(name.into(), texts(flagged(flag)));
            }
        }
        Value::Object(out).to_string()
    }

    fn varied() -> UserLexicon {
        let mut lex = UserLexicon::new();
        let words = ["boston", "naïve", "😀x", "\u{e000}y", "quote\"d", "back\\slash", "tab\tbed", "a", "b", "日本"];
        for (i, w) in words.iter().enumerate() {
            let surface = w.to_uppercase();
            lex.learn_word(w, (i % 2 == 0).then_some(surface.as_str()), 1 + i as i32, ["en", "bn", ""][i % 3], i % 4 == 0);
        }
        for a in &words {
            for b in &words[..4] {
                lex.learn_bigram(a, b);
                lex.learn_trigram(a, b, a);
                lex.learn_skip1gram(b, a);
                lex.learn_skip2gram(a, a);
            }
        }
        lex.add_word("wmk", true, Some("WMK"), 3, true);
        lex.set_language("naïve", "fr");
        lex.set_language("a", "");
        lex.pin_case("b", true);
        lex.forget_all(&HashSet::from(["b".to_string()]));
        lex
    }

    #[test]
    fn the_file_is_written_as_the_value_wrote_it() {
        let lex = varied();
        assert_eq!(snapshot_by_value(&lex), lex.snapshot_json());
        let empty = UserLexicon::new();
        assert_eq!(snapshot_by_value(&empty), empty.snapshot_json());
        assert_eq!(r#"{"skipSchema":1}"#, empty.snapshot_json());
    }

    #[test]
    fn the_file_reads_the_same_in_place_and_as_a_tree() {
        let json = varied().snapshot_json();
        let direct = Snapshot::read(&json).expect("read in place");
        let value = parse_value(&json).expect("parses");
        let tree = Snapshot::of_value(&value).expect("a snapshot");
        assert_eq!(direct.words.len(), tree.words.len());
        let mut a = UserLexicon::new();
        a.load_snapshot(direct);
        let mut b = UserLexicon::new();
        b.load_snapshot(tree);
        // Junk (`quote"d`, `tab\tbed`, ...) is dropped on the way in, both ways.
        assert_eq!(a.snapshot_json(), b.snapshot_json());
        assert!(a.is_dirty() && b.is_dirty());
        let again = a.snapshot_json();
        assert_eq!(again, load(&again).snapshot_json());
    }

    #[test]
    fn what_only_the_tree_settles_is_left_to_it() {
        // A key twice: the tree keeps the last, wrong types and all.
        let cases = [
            (r#"{"words":{"a":1.5},"words":{"a":4}}"#, Some(4)),
            (r#"{"words":{"a":1,"a":2}}"#, Some(2)),
            (r#"{"words":{"a":2},"bigrams":{"a":{"a":1,"a":3}}}"#, Some(2)),
            (r#"{"words":{"a":2},"bigrams":{"a":{"a":1},"a":{"a":5}}}"#, Some(2)),
            (r#"{"words":{"a":2},"x":1e400}"#, None),
            (r#"{"words":{"a":2},"x":"\ud800"}"#, Some(2)),
            (r#"{"words":{"a":-0}}"#, None),
            (r#"[1]"#, None),
        ];
        for (json, count) in cases {
            assert!(Snapshot::read(json).is_none(), "{json}");
            let mut lex = UserLexicon::new();
            assert_eq!(count.is_some(), lex.load(json), "{json}");
            if let Some(count) = count {
                assert_eq!(count, lex.frequency_of("a"), "{json}");
            }
        }
        let lex = load(r#"{"words":{"a":2},"bigrams":{"a":{"a":1,"a":3}}}"#);
        assert_eq!(3, lex.bigram_count("a", "a"));
        let lex = load(r#"{"words":{"a":2},"bigrams":{"a":{"a":1},"a":{"a":5}}}"#);
        assert_eq!(5, lex.bigram_count("a", "a"));
        // Out of byte order, as Kotlin's maps wrote them, but no key twice.
        assert!(Snapshot::read(r#"{"words":{"b":1,"a":2},"bigrams":{"b":{"b":1,"a":2},"a":{"a":1}}}"#).is_some());
    }
}
