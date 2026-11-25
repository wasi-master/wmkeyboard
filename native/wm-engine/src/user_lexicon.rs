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

use crate::completer;
use crate::fuzzy::Walker;
use crate::sort;
use crate::strict_math;
use crate::word_key;
use serde_json::{Map, Value};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

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

struct TrieNode {
    /// Sorted by label.
    children: Vec<(u16, u32)>,
    frequency: i32,
    is_word: bool,
    /// Highest frequency anywhere under the node, itself included. Only ever
    /// raised between rebuilds, as in Kotlin, so it is an upper bound.
    max_subtree: i32,
}

impl TrieNode {
    fn new() -> TrieNode {
        TrieNode { children: Vec::new(), frequency: 0, is_word: false, max_subtree: 0 }
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
        UserTrie { nodes: vec![TrieNode::new()] }
    }

    fn descend_or_add(&mut self, word: impl IntoIterator<Item = u16>) -> u32 {
        let mut node = 0u32;
        for unit in word {
            let children = &self.nodes[node as usize].children;
            node = match children.binary_search_by_key(&unit, |&(l, _)| l) {
                Ok(i) => children[i].1,
                Err(i) => {
                    let id = self.nodes.len() as u32;
                    self.nodes[node as usize].children.insert(i, (unit, id));
                    self.nodes.push(TrieNode::new());
                    id
                }
            };
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
        for node in &self.nodes {
            out.child_start.push(out.labels.len() as i32);
            for &(label, child) in &node.children {
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
        let children = &self.nodes[node as usize].children;
        children.binary_search_by_key(&label, |&(l, _)| l).ok().map(|i| children[i].1)
    }
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>) {
        out.clear();
        out.extend_from_slice(&self.nodes[node as usize].children);
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
/// A small vector rather than a map with a sorted copy beside it: a head has
/// at most [`MAX_FOLLOWERS`] followers when learned, and the map's table plus
/// the copy came to most of the 8.6 MB this store held at startup on a
/// CPH2481 (heapprofd, 2026-11-26). Kept in order, the ranking needs no copy
/// and the follower that ranks last, the one dropped past the cap, is the
/// last entry.
#[derive(Clone, Default, Debug)]
struct Followers {
    entries: Vec<(Box<str>, i32)>,
}

impl Followers {
    /// `counts` as the file holds them, in ranking order. No cap: a file
    /// keeps what it was saved with, as the map did.
    fn from_counts<'a>(counts: impl Iterator<Item = (&'a String, i32)>) -> Followers {
        let mut f = Followers { entries: counts.map(|(k, v)| (k.as_str().into(), v)).collect() };
        f.entries.shrink_to_fit();
        f.rank();
        f
    }

    fn rank(&mut self) {
        sort::by(&mut self.entries, |a, b| b.1.cmp(&a.1).then_with(|| cmp_utf16(&a.0, &b.0)));
    }

    fn get(&self, next: &str) -> Option<i32> {
        self.entries.iter().find(|(k, _)| &**k == next).map(|&(_, c)| c)
    }

    /// Adds `count` to `next`'s, taking it in at 0 when it is new.
    fn add(&mut self, next: &str, count: i32) {
        match self.entries.iter_mut().find(|(k, _)| &**k == next) {
            Some((_, c)) => *c = c.wrapping_add(count),
            None => self.entries.push((next.into(), 0i32.wrapping_add(count))),
        }
    }

    fn bump(&mut self, next: &str) {
        self.add(next, 1);
        self.rank();
        if self.entries.len() > MAX_FOLLOWERS {
            self.drop_last();
        }
    }

    /// Drops the follower ranking last: the lowest count, then the word that
    /// sorts last.
    fn drop_last(&mut self) {
        self.entries.pop();
    }

    fn rename(&mut self, old: &str, new: &str) {
        let Some(moved) = self.remove(old) else { return };
        self.add(new, moved);
        self.rank();
    }

    fn absorb(&mut self, other: &Followers) {
        // The cap is applied after the sum, and the sums do not depend on
        // the order.
        for (next, count) in &other.entries {
            self.add(next, *count);
        }
        self.rank();
        self.entries.truncate(MAX_FOLLOWERS);
    }

    /// The first `limit` followers, best first.
    fn top(&self, limit: usize) -> Vec<String> {
        self.entries.iter().take(limit).map(|(k, _)| k.to_string()).collect()
    }

    fn total(&self) -> i32 {
        self.entries.iter().fold(0i32, |a, &(_, b)| a.wrapping_add(b))
    }

    /// Takes `next` out, returning its count.
    fn remove(&mut self, next: &str) -> Option<i32> {
        let at = self.entries.iter().position(|(k, _)| &**k == next)?;
        Some(self.entries.remove(at).1)
    }

    fn remove_all(&mut self, keys: &HashSet<String>) {
        self.entries.retain(|(k, _)| !keys.contains(&**k));
    }

    /// The counts in UTF-16 order of the words, as the file writes them.
    fn by_word(&self) -> Vec<(&str, i32)> {
        let mut all: Vec<(&str, i32)> = self.entries.iter().map(|(k, c)| (&**k, *c)).collect();
        sort::by(&mut all, |a, b| cmp_utf16(a.0, b.0));
        all
    }
}

// ---- The store ---------------------------------------------------------------

#[derive(Default)]
pub struct UserLexicon {
    trie: UserTrie,
    words: HashMap<String, i32>,
    bigrams: HashMap<String, Followers>,
    trigrams: HashMap<String, Followers>,
    skip1grams: HashMap<String, Followers>,
    skip2grams: HashMap<String, Followers>,
    word_gen: HashMap<String, i64>,
    word_langs: HashMap<String, String>,
    /// Words the user filed under a language by hand (#930), tagged or
    /// untagged: typing them under another language no longer moves them.
    lang_pinned: HashSet<String>,
    word_case: HashMap<String, String>,
    case_votes: HashMap<String, i32>,
    case_pinned: HashSet<String>,
    added_by_hand: HashSet<String>,
    word_born: HashMap<String, i64>,
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

    /// Counts the word with key `key` `count` more times. `vote` is its
    /// surface when the caller vouches for the capitals (`caseEvidence`) and
    /// it folds back to `key`. The caller has already refused unlearnable
    /// words. Returns whether it was taken.
    pub fn learn_word(&mut self, key: &str, vote: Option<&str>, count: i32, lang: &str, listed_in_lower_case: bool) -> bool {
        if key.is_empty() || utf16_len(key) > MAX_WORD_LENGTH || count <= 0 {
            return false;
        }
        if let Some(surface) = vote {
            self.vote_case(key, surface, 1, listed_in_lower_case);
        }
        let before = self.words.get(key).copied().unwrap_or(0);
        let merged = (before as i64 + count as i64).min(MAX_COUNT as i64) as i32;
        self.words.insert(key.to_string(), merged);
        self.reinforce(key, merged - before);
        self.word_gen.insert(key.to_string(), self.generation);
        self.word_born.entry(key.to_string()).or_insert(self.generation);
        if !lang.is_empty() && !self.lang_pinned.contains(key) {
            self.word_langs.insert(key.to_string(), lang.to_string());
        }
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn language_of(&self, key: &str) -> Option<&str> {
        self.word_langs.get(key).map(String::as_str)
    }

    /// Files a known word under `lang`, or under every language when `lang`
    /// is empty, from the personal dictionary screen (#930). The choice is
    /// pinned, so a later sighting under another language leaves it alone.
    /// Returns whether anything changed.
    pub fn set_language(&mut self, key: &str, lang: &str) -> bool {
        if !self.words.contains_key(key) {
            return false;
        }
        let moved = if lang.is_empty() {
            self.word_langs.remove(key).is_some()
        } else {
            self.word_langs.insert(key.to_string(), lang.to_string()).as_deref() != Some(lang)
        };
        let pinned = self.lang_pinned.insert(key.to_string());
        if !moved && !pinned {
            return false;
        }
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn is_language_pinned(&self, key: &str) -> bool {
        self.lang_pinned.contains(key)
    }

    pub fn display_of(&self, key: &str) -> Option<&str> {
        self.word_case.get(key).map(String::as_str)
    }

    /// One sighting of `surface` as the spelling of `key`, worth `weight`.
    fn vote_case(&mut self, key: &str, surface: &str, weight: i32, listed_in_lower_case: bool) {
        if weight <= 0 || self.case_pinned.contains(key) {
            return;
        }
        let shape = (surface != key).then(|| surface.to_string());
        let current = self.word_case.get(key).cloned();
        let standing = match self.case_votes.get(key) {
            Some(&v) => v,
            None if current.is_some() => 0,
            None if listed_in_lower_case => MAX_CASE_VOTES,
            None => self.lower_case_evidence(key),
        };
        if shape == current {
            self.case_votes.insert(key.to_string(), (standing + weight).min(MAX_CASE_VOTES));
            return;
        }
        let left = standing - weight;
        if left > 0 {
            self.case_votes.insert(key.to_string(), left);
            return;
        }
        self.word_case.remove(key);
        self.case_votes.remove(key);
        if let Some(shape) = shape {
            self.word_case.insert(key.to_string(), shape);
        }
        self.case_votes.insert(key.to_string(), (-left).clamp(1, MAX_CASE_VOTES));
    }

    fn lower_case_evidence(&self, key: &str) -> i32 {
        self.words.get(key).copied().unwrap_or(0).min(MAX_CASE_VOTES)
    }

    /// Settles `key`'s spelling outright; `surface` must fold to `key`.
    fn set_case(&mut self, key: &str, surface: &str) {
        if surface == key {
            self.word_case.remove(key);
            self.case_votes.remove(key);
        } else {
            self.word_case.insert(key.to_string(), surface.to_string());
            self.case_votes.insert(key.to_string(), MAX_CASE_VOTES);
        }
    }

    /// `addWord`. `surface` is the trimmed word's surface when the caller
    /// vouches for its capitals and it folds back to `key`; `pin_case` pins
    /// it then.
    pub fn add_word(&mut self, key: &str, case_evidence: bool, surface: Option<&str>, boost: i32, pin_case: bool) {
        if key.is_empty() || utf16_len(key) > MAX_WORD_LENGTH || boost <= 0 {
            return;
        }
        if case_evidence {
            if let Some(surface) = surface {
                self.set_case(key, surface);
            }
            if pin_case {
                self.case_pinned.insert(key.to_string());
            }
        }
        self.added_by_hand.insert(key.to_string());
        let before = self.words.get(key).copied().unwrap_or(0);
        let merged = (before as i64 + boost as i64).min(MAX_COUNT as i64) as i32;
        self.words.insert(key.to_string(), merged);
        self.reinforce(key, merged - before);
        self.word_gen.insert(key.to_string(), self.generation);
        self.word_born.entry(key.to_string()).or_insert(self.generation);
        self.mutations += 1;
        self.dirty = true;
    }

    pub fn is_added_by_hand(&self, key: &str) -> bool {
        self.added_by_hand.contains(key)
    }

    pub fn added_generation(&self, key: &str) -> Option<i64> {
        self.word_born.get(key).copied()
    }

    /// `rename`: `new_surface` is the trimmed replacement's surface, and
    /// `surface_folds` whether it folds back to `new_key` (only then does it
    /// settle the word's capitals).
    pub fn rename(&mut self, old_key: &str, new_key: &str, new_surface: &str, surface_folds: bool) -> bool {
        if new_key.is_empty() || utf16_len(new_key) > MAX_WORD_LENGTH {
            return false;
        }
        if old_key == new_key {
            let current = self.word_case.get(old_key).map(String::as_str).unwrap_or(old_key);
            if new_surface == current || !self.words.contains_key(old_key) {
                return false;
            }
            if surface_folds {
                self.set_case(old_key, new_surface);
            }
            self.case_pinned.insert(old_key.to_string());
            self.mutations += 1;
            self.dirty = true;
            return true;
        }
        let Some(count) = self.words.remove(old_key) else { return false };
        let existing = self.words.get(new_key).copied().unwrap_or(0);
        self.words.insert(new_key.to_string(), (existing as i64 + count as i64).min(MAX_COUNT as i64) as i32);
        let old_gen = self.word_gen.remove(old_key).unwrap_or(self.generation);
        let new_gen = old_gen.max(self.word_gen.get(new_key).copied().unwrap_or(0));
        self.word_gen.insert(new_key.to_string(), new_gen);
        let old_born = self.word_born.remove(old_key).unwrap_or(old_gen);
        let new_born = old_born.min(self.word_born.get(new_key).copied().unwrap_or(old_born));
        self.word_born.insert(new_key.to_string(), new_born);
        // A language the user chose travels with the word and wins over the
        // target's; a learned one only fills a gap a choice did not leave.
        let old_lang = self.word_langs.remove(old_key);
        if self.lang_pinned.remove(old_key) {
            match old_lang {
                Some(lang) => self.word_langs.insert(new_key.to_string(), lang),
                None => self.word_langs.remove(new_key),
            };
            self.lang_pinned.insert(new_key.to_string());
        } else if let Some(lang) = old_lang {
            if !self.lang_pinned.contains(new_key) {
                self.word_langs.entry(new_key.to_string()).or_insert(lang);
            }
        }
        self.word_case.remove(old_key);
        self.case_votes.remove(old_key);
        self.case_pinned.remove(old_key);
        if surface_folds {
            self.set_case(new_key, new_surface);
        }
        self.case_pinned.insert(new_key.to_string());
        self.added_by_hand.remove(old_key);
        self.added_by_hand.insert(new_key.to_string());
        for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
            if let Some(moved) = store.remove(old_key) {
                match store.get_mut(new_key) {
                    None => {
                        store.insert(new_key.to_string(), moved);
                    }
                    Some(target) => target.absorb(&moved),
                }
            }
            for followers in store.values_mut() {
                followers.rename(old_key, new_key);
            }
        }
        let sep = TRIGRAM_SEPARATOR.to_string();
        let mut contexts: Vec<String> = self
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
            match self.trigrams.get_mut(&rewritten) {
                None => {
                    self.trigrams.insert(rewritten, moved);
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
        if !self.words.contains_key(key) {
            return false;
        }
        self.words.insert(key.to_string(), count.clamp(1, MAX_COUNT));
        self.word_gen.insert(key.to_string(), self.generation);
        self.rebuild_trie();
        self.mutations += 1;
        self.dirty = true;
        true
    }

    pub fn is_case_pinned(&self, key: &str) -> bool {
        self.case_pinned.contains(key)
    }

    pub fn pin_case(&mut self, key: &str, pinned: bool) -> bool {
        if !self.words.contains_key(key) {
            return false;
        }
        let changed = if pinned { self.case_pinned.insert(key.to_string()) } else { self.case_pinned.remove(key) };
        if !changed {
            return false;
        }
        if self.word_case.contains_key(key) {
            self.case_votes.insert(key.to_string(), MAX_CASE_VOTES);
        }
        self.mutations += 1;
        self.dirty = true;
        true
    }

    fn learn_into(store: &mut HashMap<String, Followers>, head: &str, next: &str) {
        store.entry(head.to_string()).or_default().bump(next);
    }

    pub fn learn_bigram(&mut self, prev: &str, next: &str) {
        if !fits(prev) || !fits(next) {
            return;
        }
        Self::learn_into(&mut self.bigrams, prev, next);
        self.dirty = true;
    }

    pub fn next_words(&mut self, prev: &str, limit: usize) -> Vec<String> {
        self.bigrams.get_mut(prev).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn learn_trigram(&mut self, a: &str, b: &str, c: &str) {
        if !fits(a) || !fits(b) || !fits(c) {
            return;
        }
        Self::learn_into(&mut self.trigrams, &trigram_key(a, b), c);
        self.dirty = true;
    }

    pub fn next_words_after(&mut self, a: &str, b: &str, limit: usize) -> Vec<String> {
        self.trigrams.get_mut(&trigram_key(a, b)).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn trigram_count(&self, a: &str, b: &str, c: &str) -> i32 {
        self.trigrams.get(&trigram_key(a, b)).and_then(|f| f.get(c)).unwrap_or(0)
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
        self.skip1grams.get_mut(prev2).map_or_else(Vec::new, |f| f.top(limit))
    }

    pub fn skip2_followers(&mut self, prev3: &str, limit: usize) -> Vec<String> {
        self.skip2grams.get_mut(prev3).map_or_else(Vec::new, |f| f.top(limit))
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
            .map(|f| f.entries.iter().map(|(k, v)| (k.to_string(), *v)).collect())
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
        min_count <= 1 || self.added_by_hand.contains(key) || self.words.get(key).copied().unwrap_or(0) >= min_count
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
        let mut keys: Vec<&String> = self.words.keys().collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a, b));
        keys.into_iter()
            .map(|k| (self.word_case.get(k).cloned().unwrap_or_else(|| k.clone()), self.words[k]))
            .collect()
    }

    /// `forgetAll`, over keys.
    pub fn forget_all(&mut self, keys: &HashSet<String>) {
        if keys.is_empty() {
            return;
        }
        for key in keys {
            self.words.remove(key);
            self.word_gen.remove(key);
            self.word_born.remove(key);
            self.word_langs.remove(key);
            self.lang_pinned.remove(key);
            self.word_case.remove(key);
            self.case_votes.remove(key);
            self.case_pinned.remove(key);
            self.added_by_hand.remove(key);
            self.bigrams.remove(key);
            self.skip1grams.remove(key);
            self.skip2grams.remove(key);
        }
        for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
            for followers in store.values_mut() {
                followers.remove_all(keys);
            }
        }
        self.trigrams.retain(|context, _| !context.split(TRIGRAM_SEPARATOR).any(|part| keys.contains(part)));
        for followers in self.trigrams.values_mut() {
            followers.remove_all(keys);
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

    fn clear_maps(&mut self) {
        self.words.clear();
        self.bigrams.clear();
        self.trigrams.clear();
        self.skip1grams.clear();
        self.skip2grams.clear();
        self.word_gen.clear();
        self.word_born.clear();
        self.word_langs.clear();
        self.lang_pinned.clear();
        self.word_case.clear();
        self.case_votes.clear();
        self.case_pinned.clear();
        self.added_by_hand.clear();
    }

    fn rebuild_trie(&mut self) {
        let mut fresh = UserTrie::new();
        // Children are kept sorted and `insert` takes the larger frequency,
        // so the result does not depend on this order.
        for (word, &count) in &self.words {
            fresh.insert(word, count);
        }
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
            let score = |word: &str| -> f64 {
                let age = (generation - self.word_gen.get(word).copied().unwrap_or(generation)).max(0);
                let half_lives = age as f64 / HALF_LIFE_GENERATIONS;
                self.words.get(word).copied().unwrap_or(0) as f64 * strict_math::pow(2.0, -half_lives)
            };
            let sticky = |word: &str| {
                self.added_by_hand.contains(word) || self.words.get(word).copied().unwrap_or(0) >= STICKY_MIN_COUNT
            };
            let mut evictable: Vec<(bool, f64, String)> =
                self.words.keys().map(|w| (sticky(w), score(w), w.clone())).collect();
            sort::by(&mut evictable, |a, b| {
                a.0.cmp(&b.0).then_with(|| a.1.total_cmp(&b.1)).then_with(|| cmp_utf16(&a.2, &b.2))
            });
            let excess = self.words.len() - EVICT_TO;
            for (_, _, word) in evictable.into_iter().take(excess) {
                self.words.remove(&word);
                self.word_gen.remove(&word);
                self.word_born.remove(&word);
                self.word_langs.remove(&word);
                self.lang_pinned.remove(&word);
                self.word_case.remove(&word);
                self.case_votes.remove(&word);
                self.case_pinned.remove(&word);
                self.added_by_hand.remove(&word);
                for store in [&mut self.bigrams, &mut self.skip1grams, &mut self.skip2grams] {
                    store.remove(&word);
                    for followers in store.values_mut() {
                        followers.remove(&word);
                    }
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
                let mut heads: Vec<(i32, String)> = store.iter().map(|(k, f)| (f.total(), k.clone())).collect();
                sort::by(&mut heads, |a, b| a.0.cmp(&b.0).then_with(|| cmp_utf16(&a.1, &b.1)));
                let excess = store.len() - cap;
                for (_, head) in heads.into_iter().take(excess) {
                    store.remove(&head);
                }
            }
        }
    }

    // ---- The file --------------------------------------------------------------

    /// The store as the file holds it: the Kotlin `Snapshot`'s fields in its
    /// order, those still at their defaults left out (kotlinx writes no
    /// defaults), maps in key order.
    pub fn snapshot_json(&self) -> String {
        let mut out = Map::new();
        fn sorted<V: Clone>(map: &HashMap<String, V>) -> Vec<(&String, V)> {
            let mut all: Vec<(&String, V)> = map.iter().map(|(k, v)| (k, v.clone())).collect();
            sort::by(&mut all, |a, b| cmp_utf16(a.0, b.0));
            all
        }
        // Inserted one by one: collecting into a `Map` instantiates its sort
        // once per iterator type.
        fn object<V: Into<Value> + Clone>(map: &HashMap<String, V>) -> Value {
            let mut out = Map::new();
            for (k, v) in sorted(map) {
                out.insert(k.clone(), v.into());
            }
            Value::Object(out)
        }
        fn nested(map: &HashMap<String, Followers>) -> Value {
            let mut out = Map::new();
            for (k, f) in sorted(map) {
                let mut counts = Map::new();
                for (word, count) in f.by_word() {
                    counts.insert(word.to_string(), count.into());
                }
                out.insert(k.clone(), Value::Object(counts));
            }
            Value::Object(out)
        }
        fn set(set: &HashSet<String>) -> Value {
            let mut all: Vec<&String> = set.iter().collect();
            sort::by(&mut all, |a, b| cmp_utf16(a, b));
            Value::Array(all.into_iter().map(|s| Value::String(s.clone())).collect())
        }
        if !self.words.is_empty() {
            out.insert("words".into(), object(&self.words));
        }
        if !self.bigrams.is_empty() {
            out.insert("bigrams".into(), nested(&self.bigrams));
        }
        if self.generation != 0 {
            out.insert("generation".into(), self.generation.into());
        }
        if !self.word_gen.is_empty() {
            out.insert("wordGen".into(), object(&self.word_gen));
        }
        if !self.trigrams.is_empty() {
            out.insert("trigrams".into(), nested(&self.trigrams));
        }
        if !self.skip1grams.is_empty() {
            out.insert("skip1grams".into(), nested(&self.skip1grams));
        }
        if !self.skip2grams.is_empty() {
            out.insert("skip2grams".into(), nested(&self.skip2grams));
        }
        out.insert("skipSchema".into(), SKIP_SCHEMA.into());
        if !self.word_langs.is_empty() {
            out.insert("wordLang".into(), object(&self.word_langs));
        }
        if !self.word_case.is_empty() {
            out.insert("wordCase".into(), object(&self.word_case));
        }
        if !self.case_votes.is_empty() {
            out.insert("caseVotes".into(), object(&self.case_votes));
        }
        if !self.case_pinned.is_empty() {
            out.insert("casePinned".into(), set(&self.case_pinned));
        }
        if !self.added_by_hand.is_empty() {
            out.insert("addedByHand".into(), set(&self.added_by_hand));
        }
        if !self.word_born.is_empty() {
            out.insert("wordBorn".into(), object(&self.word_born));
        }
        if !self.lang_pinned.is_empty() {
            out.insert("langPinned".into(), set(&self.lang_pinned));
        }
        Value::Object(out).to_string()
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
        let Some(snapshot) = Snapshot::parse(json) else {
            return false;
        };
        self.load_snapshot(snapshot);
        true
    }

    fn load_snapshot(&mut self, snapshot: Snapshot) {
        let junk = |word: &str| {
            !word_key::is_learnable_str(word)
                && !snapshot.added_by_hand.contains(word)
                && snapshot.words.get(word).copied().unwrap_or(0) < STICKY_MIN_COUNT
        };
        for (word, &count) in &snapshot.words {
            self.words.insert(word.clone(), count);
        }
        let before = self.words.len();
        self.words.retain(|w, _| !junk(w));
        if self.words.len() != before {
            self.dirty = true;
        }
        let followers = |map: &HashMap<String, i32>| Followers::from_counts(map.iter().filter(|(k, _)| !junk(k)).map(|(k, v)| (k, *v)));
        for (prev, map) in &snapshot.bigrams {
            if junk(prev) {
                continue;
            }
            self.bigrams.insert(prev.clone(), followers(map));
        }
        for (context, map) in &snapshot.trigrams {
            if context.split(TRIGRAM_SEPARATOR).any(&junk) {
                continue;
            }
            self.trigrams.insert(context.clone(), followers(map));
        }
        let legacy = snapshot.skip_schema < SKIP_SCHEMA;
        let one_skip = if legacy { &snapshot.skip2grams } else { &snapshot.skip1grams };
        let two_skip = if legacy { &snapshot.legacy_skip3grams } else { &snapshot.skip2grams };
        if legacy && (!one_skip.is_empty() || !two_skip.is_empty()) {
            self.dirty = true;
        }
        for (prev, map) in one_skip {
            if junk(prev) {
                continue;
            }
            self.skip1grams.insert(prev.clone(), followers(map));
        }
        for (prev, map) in two_skip {
            if junk(prev) {
                continue;
            }
            self.skip2grams.insert(prev.clone(), followers(map));
        }
        self.generation = snapshot.generation;
        let words: Vec<String> = self.words.keys().cloned().collect();
        for word in words {
            let gen = snapshot.word_gen.get(&word).copied().unwrap_or(snapshot.generation);
            self.word_gen.insert(word.clone(), gen);
            self.word_born.insert(word.clone(), snapshot.word_born.get(&word).copied().unwrap_or(gen));
            if let Some(lang) = snapshot.word_lang.get(&word) {
                self.word_langs.insert(word.clone(), lang.clone());
            }
            if snapshot.lang_pinned.contains(&word) {
                self.lang_pinned.insert(word.clone());
            }
            let shape = snapshot
                .word_case
                .get(&word)
                .filter(|shape| **shape != word && word_key::of_str(shape) == word);
            match shape {
                Some(shape) => {
                    self.word_case.insert(word.clone(), shape.clone());
                    let votes = snapshot.case_votes.get(&word).copied().unwrap_or(1).clamp(1, MAX_CASE_VOTES);
                    self.case_votes.insert(word.clone(), votes);
                }
                None => {
                    if let Some(&votes) = snapshot.case_votes.get(&word) {
                        self.case_votes.insert(word.clone(), votes.clamp(1, MAX_CASE_VOTES));
                    }
                }
            }
            if snapshot.case_pinned.contains(&word) {
                self.case_pinned.insert(word.clone());
            }
            if snapshot.added_by_hand.contains(&word) {
                self.added_by_hand.insert(word.clone());
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

/// The parsed file, as kotlinx reads it into `Snapshot`.
#[derive(Default, Debug)]
struct Snapshot {
    words: HashMap<String, i32>,
    bigrams: HashMap<String, HashMap<String, i32>>,
    generation: i64,
    word_gen: HashMap<String, i64>,
    trigrams: HashMap<String, HashMap<String, i32>>,
    skip1grams: HashMap<String, HashMap<String, i32>>,
    skip2grams: HashMap<String, HashMap<String, i32>>,
    skip_schema: i64,
    legacy_skip3grams: HashMap<String, HashMap<String, i32>>,
    word_lang: HashMap<String, String>,
    word_case: HashMap<String, String>,
    case_votes: HashMap<String, i32>,
    case_pinned: HashSet<String>,
    added_by_hand: HashSet<String>,
    word_born: HashMap<String, i64>,
    lang_pinned: HashSet<String>,
}

impl Snapshot {
    /// Strict as kotlinx's default `Json` is about types: a value of the
    /// wrong type, a null, an Int field out of range or a fraction where an
    /// integer belongs fails the whole file. Unknown keys are ignored.
    fn parse(json: &str) -> Option<Snapshot> {
        let value: Value = match serde_json::from_str(json) {
            Ok(v) => v,
            // serde refuses an unpaired surrogate escape that kotlinx would
            // read into its String; read it as U+FFFD instead.
            Err(_) => serde_json::from_str(&repair_lone_surrogates(json)).ok()?,
        };
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
}

fn int(v: &Value) -> Option<i32> {
    i32::try_from(v.as_i64()?).ok()
}

fn int_map(v: &Value) -> Option<HashMap<String, i32>> {
    v.as_object()?.iter().map(|(k, v)| Some((k.clone(), int(v)?))).collect()
}

fn long_map(v: &Value) -> Option<HashMap<String, i64>> {
    v.as_object()?.iter().map(|(k, v)| Some((k.clone(), v.as_i64()?))).collect()
}

fn string_map(v: &Value) -> Option<HashMap<String, String>> {
    v.as_object()?.iter().map(|(k, v)| Some((k.clone(), v.as_str()?.to_string()))).collect()
}

fn nested(v: &Value) -> Option<HashMap<String, HashMap<String, i32>>> {
    v.as_object()?.iter().map(|(k, v)| Some((k.clone(), int_map(v)?))).collect()
}

fn string_set(v: &Value) -> Option<HashSet<String>> {
    v.as_array()?.iter().map(|v| v.as_str().map(str::to_string)).collect()
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
        let snapshot = Snapshot::parse(r#"{"words":{"x\ud83d":1,"😀":2}}"#).expect("repaired");
        assert!(snapshot.words.contains_key("x\u{FFFD}"));
        assert!(snapshot.words.contains_key("😀"));
    }

    #[test]
    fn a_load_folds_capitals_and_judges_words_itself() {
        // A capital spelling is kept only where it folds to its word, by the
        // runtime's fold: İ is i + U+0307, not i.
        let json = r#"{"words":{"istanbul":5,"i\u0307stanbul":4,"οδο\u03c3":2,"οδο\u03c2":2,"x\u00a9":3},"wordCase":{"istanbul":"İstanbul","i\u0307stanbul":"İstanbul","οδο\u03c3":"ΟΔΟΣ","οδο\u03c2":"ΟΔΟΣ"}}"#;
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
    }
}
