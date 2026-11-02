//! What the strip offers and what a space commits: the typed path of the
//! Kotlin `SuggestionEngine` (core/prediction), on top of [`fuzzy`].
//!
//! [`Predictor::suggest`] ranks completions and corrections for the word
//! being typed, or the next word when nothing is; [`Predictor::decide`] says
//! whether a space should silently replace the word, offer a fix as a chip,
//! or leave it alone. The scores, gates and boosts are the phone's.
//!
//! Left on the phone: the phonetic layouts' strips, contacts and app names,
//! glide, touch positions, the per-field language mix, the elision and
//! hyphen readings and the model reranker. The user's lexicon, the
//! correction pair memory ([`CorrectionStats`]) and the fixes the user
//! taught ([`CorrectionMemory`]) are held in memory here; storing them is
//! the caller's business.

pub use crate::confidence::{self, Case, Confidence};
pub use crate::correction_memory::{CorrectionMemory, LearnedHabits};
pub use crate::correction_stats::{CorrectionStats, Penalty, UndoMemory};
use crate::freq_codec;
use crate::fuzzy::{self, EditHabits, ScoredCandidate, Tier, TouchModel, TouchScoring, WalkSource, Workspace};
pub use crate::lm::{Prefix as LmPrefix, Session as LmSession};
pub use crate::language_mix::{AppLanguageMix, FieldLanguageMix, Prior};
use crate::proximity::KeyProximity;
use crate::wmdict::{self, Dict, PackedTrie};
use crate::wmng::Pack;
use crate::word_key;
use crate::{apostrophes, correction_memory};
use std::cell::{OnceCell, RefCell};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

/// The sentence-start context token: what comes "before" a field's first
/// word or the word after a full stop.
pub const SENTENCE_START: &str = "\u{1}s";

pub fn is_sentinel(word: &str) -> bool {
    word.starts_with('\u{1}')
}

const USER_WORD_WEIGHT: f64 = 500.0;
const CUSTOM_WORD_WEIGHT: f64 = 100.0;
const SECONDARY_WORD_WEIGHT: f64 = 40.0;
/// How many one-edit words a known word keeps on the strip (`known_word_slips`).
const KNOWN_WORD_SLIPS: usize = 2;
/// Nats a known word's slip stays under it at best: ln 2, half as likely.
const KNOWN_WORD_SLIP_GAP: f64 = std::f64::consts::LN_2;
const SECONDARY_SCALE_SHARE: f64 = 0.5;
const REFERENCE_TOP_FREQUENCY: f64 = 10_000.0;
const SCALE_TOLERANCE: f64 = 1.01;
const WEIGHT_SPLIT: f64 = 0.8;
const SPLIT_AUTOCORRECT_MIN_LENGTH: usize = 5;
const SPLIT_PAIR_MIN_USES: i32 = 2;
pub const DEFAULT_AUTOCORRECT_CONFIDENCE: f64 = 4.0;
pub const MIN_AUTOCORRECT_CONFIDENCE: f64 = 1.5;
pub const MAX_AUTOCORRECT_CONFIDENCE: f64 = 10.0;
const SOLO_RUNNER_UP_SCORE: f64 = 1.0;
const ACCENT_SHADOW_RATIO: f64 = 20.0;
const TYPO_SHADOW_RATIO: f64 = 1_000.0;
const TYPO_SHADOW_MIN_RANK: usize = 50_000;
const MAX_COMPOUND_PARTS: usize = 4;
pub const OFFER_MARGIN_FRACTION: f64 = 0.35;
const PLAIN_WORD_LENGTH: f64 = 4.0;
const PLAIN_WORD_SPAN: f64 = 6.0;
const COMPLEXITY_SHAPE_WEIGHT: f64 = 0.7;
const CONTEXT_BIGRAM_BETA: f64 = 0.5;
const PACK_COUNT_SCALE: i32 = 50;
/// How many of the strip's head the context rerank reorders.
const RERANK_POOL: usize = 8;
const RANK_OFFSET_STEP: f64 = 1.0;
const JOIN_MAX_LENGTH: usize = 24;
const JOIN_MIN_FREQ: i32 = 50;
const JOIN_CONFIDENCE: f64 = 1.25;
const WALK_K: usize = 32;
const MAX_WORD_LENGTH: usize = 32;
const MAX_COUNT: i32 = 1_000_000;
const MAX_FOLLOWERS: usize = 32;

fn max_context_boost() -> f64 {
    4f64.ln()
}
fn seed_context_boost() -> f64 {
    1.5f64.ln()
}
fn pair_penalty() -> f64 {
    4f64.ln()
}
fn app_recency_boost() -> f64 {
    1.3f64.ln()
}
fn lang_mismatch_damp() -> f64 {
    3f64.ln()
}
fn casual_informal_boost() -> f64 {
    1.5f64.ln()
}
fn formal_informal_damp() -> f64 {
    2.5f64.ln()
}
/// A contraction leads the typed spelling in the strip by this much.
const CONTRACTION_LEAD: f64 = 7.0;
/// A declared contraction trails the typed word's count by this factor.
const DECLARED_TRAIL: f64 = 2.0;
/// ...and always stops just short of the typed word.
const DECLARED_BEHIND: f64 = 1e-3;
/// A confusable sibling must out-evidence the typed word this many times.
const ADVISE_DOMINANCE: f64 = 8.0;
/// What the context must say for a taught fix of a real word to fire.
const TAUGHT_DOMINANCE: f64 = 3.0;
const MIN_USER_COUNT: i32 = 3;
const MIN_CORPUS_COUNT: i32 = 25;
/// Share lead a mix language needs to be the field's language.
const DETECTED_MARGIN: f64 = 0.34;

/// How autocorrect treats a word read with several slips: the phone's
/// `SlipFixes`. Off reads no slips, Careful never applies a reading that
/// mixes slips with a real edit, Eager does.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SlipFixes {
    Off,
    #[default]
    Careful,
    Eager,
}

/// A tap per typed character, in key widths, `None` where unknown.
pub type Taps = Vec<Option<(f32, f32)>>;

pub use crate::rerank::{lm_blend_for, LM_BLEND, LM_BLEND_CAP, LM_BLEND_SLOPE, LM_CAP, LM_WEIGHT};
/// The least likely a next word may be, in log-probability, for the model
/// to offer it on an empty buffer.
pub const LM_NEXT_WORD_FLOOR: f32 = -8.0;

/// The register of the field being typed into: chat-speak rises in a
/// messaging app and sinks in email. Neutral changes nothing.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Register {
    #[default]
    Neutral,
    Casual,
    Formal,
}

/// The closed class the register acts on: chat initialisms, relaxed
/// spellings, shorthand and casual fillers.
pub const INFORMAL: &[&str] = &[
    "lol", "lmao", "rofl", "omg", "idk", "idc", "btw", "fyi", "imo", "imho", "tbh", "irl", "nvm",
    "brb", "afk", "smh", "ikr", "ffs", "wtf", "tf", "rn", "atm", "dm", "ily", "ngl", "fr", "bc",
    "cuz", "bff", "gg", "wp", "thx", "ty", "tysm", "np", "pls", "plz", "omw", "ttyl", "hbu", "wyd",
    "wbu", "lmk", "asap", "jk", "istg", "fomo", "yolo", "gonna", "wanna", "gotta", "kinda",
    "sorta", "outta", "lemme", "gimme", "dunno", "aint", "cmon", "gotcha", "betcha", "coulda",
    "shoulda", "woulda", "tryna", "finna", "bout", "til", "cos", "u", "ur", "r", "y", "k", "kk",
    "im", "ive", "dont", "cant", "wont", "didnt", "isnt", "hasnt", "havent", "wasnt", "couldnt",
    "shouldnt", "wouldnt", "yall", "yeah", "yep", "yup", "nah", "nope", "haha", "hahaha", "hehe",
    "huh", "hmm", "umm", "uhh", "ooh", "yay", "woo", "whoa", "damn", "dang", "welp", "meh", "bruh",
    "bro", "dude", "sup", "yo", "hey", "heya", "okay", "ok", "okey", "alright", "awesome", "cool",
    "sweet", "sick", "legit", "lowkey", "highkey", "deadass", "based", "cringe", "sus", "vibes",
    "vibe", "bestie", "fam", "squad", "goat", "slay", "stan", "simp", "flex", "salty", "shook",
    "snacc", "yeet", "oof", "rip",
];

fn informal(word: &str) -> bool {
    INFORMAL.contains(&word.to_lowercase().as_str())
}

/// The classic English confusables the word after can settle.
const CONFUSABLES: &[&[&str]] = &[
    &["their", "there", "they're"],
    &["your", "you're"],
    &["its", "it's"],
    &["whose", "who's"],
    &["were", "we're", "where"],
    &["then", "than"],
    &["lose", "loose"],
    &["affect", "effect"],
];

/// The rhythm of the word being typed, as a factor on the autocorrect gate:
/// the Kotlin `KeystrokeTiming`. Fast bursts correct more eagerly, slow
/// deliberate keys less. Callers pass timestamps from any monotonic clock.
#[derive(Clone, Debug, Default)]
pub struct KeystrokeTiming {
    last_ms: i64,
    gaps: u32,
    total_ms: i64,
}

impl KeystrokeTiming {
    const FAST_MS: f64 = 150.0;
    const SLOW_MS: f64 = 450.0;
    const MAX_GAP_MS: i64 = 2_000;
    const MIN_GAPS: u32 = 2;
    const MAX_SHIFT: f64 = 0.5;

    /// One composing keystroke at `now_ms`.
    pub fn keystroke(&mut self, now_ms: i64) {
        if self.last_ms != 0 {
            let gap = now_ms - self.last_ms;
            if (1..=Self::MAX_GAP_MS).contains(&gap) {
                self.gaps += 1;
                self.total_ms += gap;
            }
        }
        self.last_ms = now_ms;
    }

    /// A word boundary: the next word's rhythm starts fresh.
    pub fn reset(&mut self) {
        *self = KeystrokeTiming::default();
    }

    /// The factor at `strength` (0 off, to 1): exactly 1 with too few gaps.
    pub fn multiplier(&self, strength: f64) -> f64 {
        if strength <= 0.0 || self.gaps < Self::MIN_GAPS {
            return 1.0;
        }
        let mean = self.total_ms as f64 / self.gaps as f64;
        let t =
            ((mean - Self::FAST_MS) / (Self::SLOW_MS - Self::FAST_MS)).clamp(0.0, 1.0) * 2.0 - 1.0;
        1.0 + t * Self::MAX_SHIFT * strength.min(1.0)
    }
}
fn ln1p(v: f64) -> f64 {
    (1.0 + v).ln()
}

/// The key a word is filed under: the phone's `WordKey.of`.
pub fn key_of(word: &str) -> String {
    word_key::of_str(word)
}

/// A word list as a source: the dictionary, and the rank histogram the
/// corpus-typo check reads, built the first time it is asked.
pub struct WordList {
    dict: Dict,
    codes: OnceCell<Vec<u16>>,
}

impl WordList {
    pub fn open(bytes: Vec<u8>) -> Option<WordList> {
        Dict::open(bytes).map(|dict| WordList {
            dict,
            codes: OnceCell::new(),
        })
    }

    pub fn of<S: AsRef<str>>(entries: &[(S, i32)]) -> WordList {
        WordList::open(wmdict::write(&PackedTrie::of(entries))).expect("a written dictionary opens")
    }

    pub fn dict(&self) -> &Dict {
        &self.dict
    }

    pub fn contains(&self, word: &str) -> bool {
        self.dict.contains(word)
    }

    pub fn frequency_of(&self, word: &str) -> i32 {
        self.dict.frequency_of(word)
    }

    fn top(&self) -> i32 {
        self.dict.max_subtree(0)
    }

    /// 1 + how many words rank above `frequency`, by stored code.
    fn rank_of_frequency(&self, frequency: i32) -> usize {
        if frequency <= 0 {
            return 0;
        }
        let codes = self.codes.get_or_init(|| {
            let mut c: Vec<u16> = self
                .dict
                .entries()
                .into_iter()
                .map(|(_, f)| freq_codec::encode(f))
                .collect();
            c.sort_unstable();
            c
        });
        let code = freq_codec::encode(frequency);
        codes.len() - codes.partition_point(|c| *c <= code) + 1
    }
}

#[derive(Default, Clone)]
struct Followers {
    counts: HashMap<String, i32>,
}

impl Followers {
    fn bump(&mut self, next: &str, n: i32) {
        *self.counts.entry(next.to_string()).or_insert(0) += n;
        if self.counts.len() > MAX_FOLLOWERS {
            if let Some(low) = self
                .counts
                .iter()
                .min_by(|a, b| a.1.cmp(b.1).then_with(|| b.0.cmp(a.0)))
                .map(|(k, _)| k.clone())
            {
                self.counts.remove(&low);
            }
        }
    }

    fn ordered(&self, limit: usize) -> Vec<String> {
        let mut all: Vec<(&String, &i32)> = self.counts.iter().collect();
        all.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
        all.into_iter()
            .take(limit)
            .map(|(w, _)| w.clone())
            .collect()
    }

    fn count(&self, next: &str) -> i32 {
        self.counts.get(next).copied().unwrap_or(0)
    }
}

/// Whether `word` could be learned: the phone's gate
/// (`WordContext.isLearnableWord`), no longer than the lexicon keeps.
fn learnable(key: &str) -> bool {
    word_key::is_learnable_str(key) && key.encode_utf16().count() <= MAX_WORD_LENGTH
}

/// The user's own words and phrases: the Kotlin `UserLexicon`, in memory.
#[derive(Default)]
pub struct Lexicon {
    words: HashMap<String, i32>,
    by_hand: HashSet<String>,
    languages: HashMap<String, String>,
    cases: HashMap<String, String>,
    bigrams: HashMap<String, Followers>,
    trigrams: HashMap<(String, String), Followers>,
    skip1: HashMap<String, Followers>,
    skip2: HashMap<String, Followers>,
    trie: Option<WordList>,
    stale: bool,
}

impl Lexicon {
    pub fn new() -> Lexicon {
        Lexicon::default()
    }

    /// Counts `word` `count` more times, tagged with the language it was
    /// typed in (empty for every language). False when it is not a word.
    pub fn learn_word(&mut self, word: &str, count: i32, language: &str) -> bool {
        let key = key_of(word);
        if count <= 0 || !learnable(&key) {
            return false;
        }
        let next = (*self.words.get(&key).unwrap_or(&0) as i64 + count as i64).min(MAX_COUNT as i64)
            as i32;
        self.words.insert(key.clone(), next);
        if !language.is_empty() {
            self.languages.insert(key.clone(), language.to_string());
        }
        if word != key {
            self.cases.insert(key, word.to_string());
        }
        self.stale = true;
        true
    }

    /// A word the user added outright: established however rarely typed.
    pub fn add_by_hand(&mut self, word: &str, language: &str) -> bool {
        let added = self.learn_word(word, 1, language);
        if added {
            self.by_hand.insert(key_of(word));
        }
        added
    }

    pub fn forget(&mut self, word: &str) {
        let key = key_of(word);
        if self.words.remove(&key).is_some() {
            self.by_hand.remove(&key);
            self.languages.remove(&key);
            self.cases.remove(&key);
            self.stale = true;
        }
    }

    pub fn learn_bigram(&mut self, previous: &str, next: &str) {
        self.add_bigram(previous, next, 1);
    }

    pub fn learn_trigram(&mut self, prev2: &str, prev1: &str, next: &str) {
        self.add_trigram(prev2, prev1, next, 1);
    }

    /// `prev2` stood two words before `next`, anything between.
    pub fn learn_skip1gram(&mut self, prev2: &str, next: &str) {
        self.add_skip1gram(prev2, next, 1);
    }

    /// `prev3` stood three words before `next`.
    pub fn learn_skip2gram(&mut self, prev3: &str, next: &str) {
        self.add_skip2gram(prev3, next, 1);
    }

    /// The pair counted `count` more times, as a stored file reads back.
    pub fn add_bigram(&mut self, previous: &str, next: &str, count: i32) {
        let (p, n) = (key_of(previous), key_of(next));
        if fits(&p) && fits(&n) && count > 0 {
            self.bigrams.entry(p).or_default().bump(&n, count);
        }
    }

    pub fn add_trigram(&mut self, prev2: &str, prev1: &str, next: &str, count: i32) {
        let (a, b, c) = (key_of(prev2), key_of(prev1), key_of(next));
        if fits(&a) && fits(&b) && fits(&c) && count > 0 {
            self.trigrams.entry((a, b)).or_default().bump(&c, count);
        }
    }

    pub fn add_skip1gram(&mut self, prev2: &str, next: &str, count: i32) {
        let (p, n) = (key_of(prev2), key_of(next));
        if fits(&p) && fits(&n) && count > 0 {
            self.skip1.entry(p).or_default().bump(&n, count);
        }
    }

    pub fn add_skip2gram(&mut self, prev3: &str, next: &str, count: i32) {
        let (p, n) = (key_of(prev3), key_of(next));
        if fits(&p) && fits(&n) && count > 0 {
            self.skip2.entry(p).or_default().bump(&n, count);
        }
    }

    /// The spelling the user writes `key` in, from their case memory.
    pub fn set_case(&mut self, key: &str, spelling: &str) {
        let key = key_of(key);
        if key_of(spelling) == key && spelling != key {
            self.cases.insert(key, spelling.to_string());
        }
    }

    pub fn contains(&self, word: &str) -> bool {
        self.words.contains_key(&key_of(word))
    }

    pub fn frequency_of(&self, word: &str) -> i32 {
        self.words.get(&key_of(word)).copied().unwrap_or(0)
    }

    /// Seen often enough to be meant, or added by hand.
    pub fn is_established(&self, word: &str, min_count: i32) -> bool {
        let key = key_of(word);
        match self.words.get(&key) {
            None => false,
            Some(n) => min_count <= 1 || self.by_hand.contains(&key) || *n >= min_count,
        }
    }

    pub fn language_of(&self, word: &str) -> Option<&str> {
        self.languages.get(&key_of(word)).map(String::as_str)
    }

    /// The spelling the user writes `word` in, when it is not all lower case.
    pub fn display_of(&self, word: &str) -> Option<&str> {
        self.cases.get(&key_of(word)).map(String::as_str)
    }

    pub fn next_words(&self, previous: &str, limit: usize) -> Vec<String> {
        self.bigrams
            .get(&key_of(previous))
            .map(|f| f.ordered(limit))
            .unwrap_or_default()
    }

    pub fn next_words_after(&self, prev2: &str, prev1: &str, limit: usize) -> Vec<String> {
        self.trigrams
            .get(&(key_of(prev2), key_of(prev1)))
            .map(|f| f.ordered(limit))
            .unwrap_or_default()
    }

    pub fn skip1_followers(&self, prev2: &str, limit: usize) -> Vec<String> {
        self.skip1
            .get(&key_of(prev2))
            .map(|f| f.ordered(limit))
            .unwrap_or_default()
    }

    pub fn skip2_followers(&self, prev3: &str, limit: usize) -> Vec<String> {
        self.skip2
            .get(&key_of(prev3))
            .map(|f| f.ordered(limit))
            .unwrap_or_default()
    }

    pub fn skip1gram_count(&self, prev2: &str, next: &str) -> i32 {
        self.skip1
            .get(&key_of(prev2))
            .map_or(0, |f| f.count(&key_of(next)))
    }

    pub fn skip2gram_count(&self, prev3: &str, next: &str) -> i32 {
        self.skip2
            .get(&key_of(prev3))
            .map_or(0, |f| f.count(&key_of(next)))
    }

    pub fn bigram_count(&self, previous: &str, next: &str) -> i32 {
        self.bigrams
            .get(&key_of(previous))
            .map_or(0, |f| f.count(&key_of(next)))
    }

    pub fn trigram_count(&self, prev2: &str, prev1: &str, next: &str) -> i32 {
        self.trigrams
            .get(&(key_of(prev2), key_of(prev1)))
            .map_or(0, |f| f.count(&key_of(next)))
    }

    /// Every learned word and its count.
    pub fn words(&self) -> impl Iterator<Item = (&str, i32)> {
        self.words.iter().map(|(w, n)| (w.as_str(), *n))
    }

    /// Rebuilds the walk trie after learning; cheap for a personal list.
    fn refresh(&mut self) {
        if self.stale || (self.trie.is_none() && !self.words.is_empty()) {
            let entries: Vec<(&str, i32)> =
                self.words.iter().map(|(w, n)| (w.as_str(), *n)).collect();
            self.trie = if entries.is_empty() {
                None
            } else {
                Some(WordList::of(&entries))
            };
            self.stale = false;
        }
    }
}

fn fits(key: &str) -> bool {
    !key.is_empty() && key.chars().count() <= MAX_WORD_LENGTH
}

/// The bundled next-word pairs (`en_bigrams.txt`) that cold-start English.
#[derive(Default)]
pub struct SeedBigrams {
    pairs: HashMap<String, Vec<(String, i32)>>,
}

impl SeedBigrams {
    /// `previous next [frequency]` per line; `<s>` is the sentence start.
    pub fn parse(text: &str) -> SeedBigrams {
        let mut pairs: HashMap<String, Vec<(String, i32)>> = HashMap::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() < 2 {
                continue;
            }
            let frequency = parts.get(2).and_then(|f| f.parse().ok()).unwrap_or(1);
            let token = parts[0].to_lowercase();
            let previous = if token == "<s>" {
                SENTENCE_START.to_string()
            } else {
                token
            };
            pairs
                .entry(previous)
                .or_default()
                .push((parts[1].to_string(), frequency));
        }
        for list in pairs.values_mut() {
            list.sort_by_key(|e| std::cmp::Reverse(e.1));
        }
        SeedBigrams { pairs }
    }

    pub fn next_words(&self, previous: &str) -> Vec<String> {
        self.pairs
            .get(previous)
            .map(|l| l.iter().map(|(w, _)| w.clone()).collect())
            .unwrap_or_default()
    }

    pub fn follows(&self, previous: &str, next: &str) -> bool {
        self.pairs
            .get(previous)
            .is_some_and(|l| l.iter().any(|(w, _)| w == next))
    }

    /// The pair's frequency, 0 when it is not listed.
    pub fn count(&self, previous: &str, next: &str) -> i32 {
        self.pairs
            .get(previous)
            .and_then(|l| l.iter().find(|(w, _)| w == next))
            .map_or(0, |(_, f)| *f)
    }
}

/// What a space should do with the word before it. `apply` and `offer` are
/// never both set.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Decision {
    pub apply: Option<String>,
    pub offer: Option<String>,
    /// How far `apply` cleared the gate, 0..1.
    pub certainty: f64,
    /// How far `apply` strays from what was typed, 0..1.
    pub complexity: f64,
}

impl Decision {
    /// Near 1 for a fix nobody looks at twice; 0 when nothing was applied.
    pub fn obviousness(&self) -> f64 {
        self.certainty * (1.0 - self.complexity)
    }
}

/// The engine. Fields are the phone's settings and sources, set directly.
pub struct Predictor {
    /// The language's own word list.
    pub dictionary: Option<WordList>,
    /// A list the user imported for the language.
    pub custom: Option<WordList>,
    /// The secondary languages' lists, by language id.
    pub secondaries: Vec<(String, WordList)>,
    /// How much each secondary is typed, 0..1 (absent = 1).
    pub secondary_confidence: HashMap<String, f64>,
    pub lexicon: Lexicon,
    pub ngrams: Option<Pack>,
    pub seeds: SeedBigrams,
    /// The language is English: the seed pairs and verb forms apply.
    pub english_sources: bool,
    /// The language being typed, for damping learned words of another.
    pub primary_language: String,
    pub proximity: KeyProximity,
    /// Never suggested, never a correction target. Keys.
    pub blacklist: HashSet<String>,
    /// The language's offensive words, kept out the same way. Keys.
    pub offensive: HashSet<String>,
    /// The capitals the word list writes its words with (#481), as the
    /// phone's `DictionaryCapitals` trie: a shape per key.
    pub capitals: Option<Dict>,
    /// The user's rank steps per key (#99).
    pub rank_offsets: HashMap<String, i32>,
    /// Words recently typed in this app. Keys.
    pub context_words: HashSet<String>,
    /// Context rerank (the phone's `NgramReranker`, behind its Context
    /// rerank setting): the head of the strip reordered by the phrases
    /// that end in each candidate.
    pub context_rerank: bool,
    /// The words last committed in the field, for the rerank's recency.
    pub recent_words: Vec<String>,
    pub autocorrect_confidence: f64,
    pub adaptive_confidence: bool,
    pub autocorrect_splits: bool,
    pub skip_all_caps: bool,
    pub digit_slips: bool,
    pub learned_word_min_count: i32,
    pub stats: CorrectionStats,
    /// The fixes the user taught by hand.
    pub memory: CorrectionMemory,
    /// The slips the user's hand makes on this layout, from [`Self::memory`].
    pub habits: LearnedHabits,
    /// Where the field is: chat-speak's standing in the strip.
    pub register: Register,
    /// Fix missing apostrophes: the contraction readings and [`Self::elide`].
    pub apostrophe_fixes: bool,
    /// The language the field is being written in, from its words.
    pub field_mix: FieldLanguageMix,
    /// How far that detection may shift the mix; 0 is off. See
    /// [`crate::language_mix::FIELD_SHIFT_BALANCED`].
    pub field_detection_shift: f64,
    /// The layout's key centres, for the taps a word was typed with
    /// ([`Self::suggest_touch`], [`Self::decide_touch`]); none reads the
    /// typed letters alone.
    pub touch: Option<TouchModel>,
    pub slip_fixes: SlipFixes,
    /// The neural context model (`lm`), reading the words set by
    /// [`Self::set_lm_context`]; none leaves the n-gram stores alone in charge.
    pub lm: Option<RefCell<LmSession>>,
    pub lm_prefix: LmPrefix,
    pub lm_weight: f64,
    pub lm_cap: f64,
    /// Whether the model's likeliest next words join the empty-buffer strip.
    pub lm_next_words: bool,
    /// The model's say on the typed word's candidates in score space, before
    /// ranking: `lm_blend` engine nats per nat of its preference over the
    /// engine's own first choice, within `lm_blend_cap`. The engine's unit is
    /// the compressed frequency scale (`ln(1 + f)` of a log-scaled count), so
    /// one of its nats is worth several of the model's; 0 is off.
    pub lm_blend: f64,
    pub lm_blend_cap: f64,
    /// How the blend grows with the taps' spread; see [`lm_blend_for`].
    pub lm_blend_slope: f64,
    /// The learned autocorrect gate; none keeps the margin gate.
    pub confidence: Option<Confidence>,
    /// What the gate saw on the last decision with a candidate, and that
    /// candidate: the typing harness reads them to fit the gate.
    pub last_case: RefCell<Option<(Case, String)>>,
    taps: RefCell<Taps>,
    ws: RefCell<Workspace>,
}

impl Default for Predictor {
    fn default() -> Predictor {
        Predictor {
            dictionary: None,
            custom: None,
            secondaries: Vec::new(),
            secondary_confidence: HashMap::new(),
            lexicon: Lexicon::new(),
            ngrams: None,
            seeds: SeedBigrams::default(),
            english_sources: true,
            primary_language: String::new(),
            proximity: KeyProximity::qwerty(false),
            blacklist: HashSet::new(),
            offensive: HashSet::new(),
            capitals: None,
            rank_offsets: HashMap::new(),
            context_words: HashSet::new(),
            context_rerank: false,
            recent_words: Vec::new(),
            autocorrect_confidence: DEFAULT_AUTOCORRECT_CONFIDENCE,
            adaptive_confidence: true,
            autocorrect_splits: false,
            skip_all_caps: true,
            digit_slips: false,
            learned_word_min_count: 1,
            stats: CorrectionStats::default(),
            memory: CorrectionMemory::default(),
            habits: LearnedHabits::default(),
            register: Register::Neutral,
            apostrophe_fixes: true,
            field_mix: FieldLanguageMix::default(),
            field_detection_shift: 0.0,
            touch: None,
            slip_fixes: SlipFixes::Careful,
            lm: None,
            lm_prefix: LmPrefix::default(),
            // The rank-unit term is off: measured 2026-10-26 (typing harness,
            // 1,200 careful/fast/sloppy words), the score-space blend below at
            // 0.2 / 1.0 beat it on every profile (+0.8, +0.4, +1.8 points of
            // strip top-1 over the rank term at 0.25 / 1.5) and the two
            // together lost to the blend alone.
            lm_weight: 0.0,
            lm_cap: LM_CAP,
            lm_next_words: true,
            lm_blend: LM_BLEND,
            lm_blend_cap: LM_BLEND_CAP,
            lm_blend_slope: LM_BLEND_SLOPE,
            confidence: None,
            last_case: RefCell::new(None),
            taps: RefCell::new(Vec::new()),
            ws: RefCell::new(Workspace::new()),
        }
    }
}

struct Scales {
    active: f64,
    custom: f64,
    secondary: Vec<f64>,
}

fn scaled_weight(top: i32, weight: f64, target: f64) -> f64 {
    let effective = top as f64 * weight;
    if top <= 0 || effective <= target * SCALE_TOLERANCE {
        weight
    } else {
        target / top as f64
    }
}

fn scaled(frequency: i32, weight: f64) -> i32 {
    if frequency <= 0 || weight <= 0.0 {
        0
    } else {
        (frequency as f64 * weight).clamp(1.0, i32::MAX as f64) as i32
    }
}

fn by_score(a: &ScoredCandidate, b: &ScoredCandidate) -> Ordering {
    b.score
        .partial_cmp(&a.score)
        .unwrap_or(Ordering::Equal)
        .then_with(|| a.word.cmp(&b.word))
}

impl Predictor {
    pub fn new() -> Predictor {
        Predictor::default()
    }

    // ---- sources ----

    fn scales(&self) -> Scales {
        let active_top = self.dictionary.as_ref().map_or(0, WordList::top);
        let custom_top = self.custom.as_ref().map_or(0, WordList::top);
        let active = scaled_weight(active_top, 1.0, REFERENCE_TOP_FREQUENCY);
        let custom = scaled_weight(custom_top, CUSTOM_WORD_WEIGHT, REFERENCE_TOP_FREQUENCY);
        let primary_top = (active_top as f64 * active).max(custom_top as f64 * custom);
        let primary_top = if primary_top > 0.0 {
            primary_top
        } else {
            REFERENCE_TOP_FREQUENCY
        };
        let secondary = self
            .secondaries
            .iter()
            .map(|(lang, list)| {
                scaled_weight(
                    list.top(),
                    SECONDARY_WORD_WEIGHT,
                    primary_top * SECONDARY_SCALE_SHARE,
                ) * self.secondary_confidence.get(lang).copied().unwrap_or(1.0)
            })
            .collect();
        Scales {
            active,
            custom,
            secondary,
        }
    }

    /// Every dictionary-tier list with its multiplier.
    fn dictionary_tier(&self) -> Vec<(&WordList, f64)> {
        let s = self.scales();
        let factors = self.field_factors();
        let factor = |lang: &str| factors.get(lang).copied().unwrap_or(1.0);
        let primary = factor(&self.primary_language);
        let mut out = Vec::new();
        if let Some(d) = &self.dictionary {
            out.push((d, s.active * primary));
        }
        if let Some(c) = &self.custom {
            out.push((c, s.custom * primary));
        }
        for ((lang, list), w) in self.secondaries.iter().zip(s.secondary) {
            if w > 0.0 {
                out.push((list, w * factor(lang)));
            }
        }
        out
    }

    // ---- the field's language ----

    fn mix_languages(&self) -> Vec<&str> {
        let mut out = vec![self.primary_language.as_str()];
        out.extend(self.secondaries.iter().map(|(l, _)| l.as_str()));
        out.retain(|l| !l.is_empty());
        out
    }

    /// The languages of the mix that know `word`; empty without a mix, so a
    /// keyboard with one language records nothing.
    pub fn owning_languages(&self, word: &str) -> HashSet<String> {
        let mut out = HashSet::new();
        if self.secondaries.is_empty() {
            return out;
        }
        let key = key_of(word);
        if !self.primary_language.is_empty()
            && (self.dictionary.as_ref().is_some_and(|d| d.contains(&key))
                || self.custom.as_ref().is_some_and(|d| d.contains(&key)))
        {
            out.insert(self.primary_language.clone());
        }
        for (lang, list) in &self.secondaries {
            if list.contains(&key) {
                out.insert(lang.clone());
            }
        }
        if let Some(l) = self.lexicon.language_of(&key) {
            if !l.is_empty() {
                out.insert(l.to_string());
            }
        }
        out
    }

    /// A fresh field: the app's habit first, then the words already there,
    /// oldest first.
    pub fn seed_field(&mut self, words: &[&str], prior: Option<&Prior>) {
        self.field_mix.reset();
        if self.secondaries.is_empty() {
            return;
        }
        if let Some(p) = prior {
            self.field_mix.seed_prior(p);
        }
        for w in words {
            let owners = self.owning_languages(w);
            self.field_mix.record(&owners);
        }
    }

    /// One word committed in the field.
    pub fn record_field(&mut self, word: &str) {
        if self.secondaries.is_empty() {
            return;
        }
        let owners = self.owning_languages(word);
        self.field_mix.record(&owners);
    }

    /// One weight factor per mix language from the field's shares:
    /// `e^(shift·ramp·delta)` against its strongest rival; none while the
    /// field says nothing.
    fn field_factors(&self) -> HashMap<String, f64> {
        let mut out = HashMap::new();
        if self.field_detection_shift <= 0.0 {
            return out;
        }
        let Some(shares) = self.field_mix.shares() else {
            return out;
        };
        let langs = self.mix_languages();
        if langs.len() < 2 {
            return out;
        }
        for lang in &langs {
            let rival = langs
                .iter()
                .filter(|o| *o != lang)
                .map(|o| shares.share_of(o))
                .fold(0.0, f64::max);
            let delta = shares.share_of(lang) - rival;
            if delta != 0.0 {
                out.insert(
                    lang.to_string(),
                    (self.field_detection_shift * shares.ramp * delta).exp(),
                );
            }
        }
        out
    }

    /// The language the field is written in: the primary unless detection
    /// is on and another mix language clearly leads its words.
    pub fn detected_language(&self) -> &str {
        let active = self.primary_language.as_str();
        if active.is_empty() || self.field_detection_shift <= 0.0 {
            return active;
        }
        let Some(shares) = self.field_mix.shares() else {
            return active;
        };
        let mut top = active;
        let mut top_share = shares.share_of(active);
        for lang in self.mix_languages() {
            let share = shares.share_of(lang);
            if share > top_share {
                top = lang;
                top_share = share;
            }
        }
        if top_share - shares.share_of(active) >= DETECTED_MARGIN {
            top
        } else {
            active
        }
    }

    fn walk_sources(&self) -> Vec<WalkSource<'_>> {
        let mut out: Vec<WalkSource> = self
            .dictionary_tier()
            .into_iter()
            .map(|(list, w)| WalkSource {
                walker: list.dict(),
                log_weight: w.ln(),
                tier: Tier::Dictionary,
            })
            .collect();
        if let Some(t) = &self.lexicon.trie {
            out.push(WalkSource {
                walker: t.dict(),
                log_weight: USER_WORD_WEIGHT.ln(),
                tier: Tier::User,
            });
        }
        out
    }

    /// Whether any list the user cannot edit holds `word`.
    pub fn in_dictionaries(&self, word: &str) -> bool {
        let key = key_of(word);
        self.dictionary.as_ref().is_some_and(|d| d.contains(&key))
            || self.custom.as_ref().is_some_and(|d| d.contains(&key))
            || self.secondaries.iter().any(|(_, d)| d.contains(&key))
    }

    /// Known to any list or the lexicon.
    pub fn is_known_word(&self, word: &str) -> bool {
        self.in_dictionaries(word) || self.lexicon.contains(word)
    }

    fn dictionary_frequency_of(&self, word: &str) -> i32 {
        self.dictionary_tier()
            .into_iter()
            .map(|(list, w)| scaled(list.frequency_of(word), w))
            .max()
            .unwrap_or(0)
    }

    fn dictionary_score(&self, lower: &str) -> f64 {
        let mut best = f64::NEG_INFINITY;
        for (list, w) in self.dictionary_tier() {
            if let Some(node) = list.dict().node_for(lower) {
                if list.dict().is_word(node) {
                    best = best.max(w.ln() + ln1p(list.dict().frequency(node) as f64));
                }
            }
        }
        best
    }

    fn suppressed(&self, word: &str) -> bool {
        if self.blacklist.is_empty() && self.offensive.is_empty() {
            return false;
        }
        let key = key_of(word);
        self.blacklist.contains(&key) || self.offensive.contains(&key)
    }

    fn rank_offset(&self, word: &str) -> f64 {
        self.rank_offsets
            .get(&key_of(word))
            .map_or(0.0, |s| *s as f64 * RANK_OFFSET_STEP)
    }

    // ---- the walk ----

    fn ranked(&self, lower: &str, limit: usize) -> Vec<ScoredCandidate> {
        self.ranked_with(lower, limit, &self.habits)
    }

    fn ranked_with(
        &self,
        lower: &str,
        limit: usize,
        habits: &dyn EditHabits,
    ) -> Vec<ScoredCandidate> {
        let k = (limit * 2).max(WALK_K);
        let sources = self.walk_sources();
        let typed: Vec<u16> = lower.encode_utf16().collect();
        let taps = self.taps.borrow();
        let scoring = match &self.touch {
            Some(model) if taps.len() == typed.len() && taps.iter().any(Option::is_some) => {
                Some(TouchScoring::new(model, taps.clone()))
            }
            _ => None,
        };
        let walked = fuzzy::search_units(
            &sources,
            &typed,
            &self.proximity,
            k / 2,
            &mut self.ws.borrow_mut(),
            fuzzy::default_max_edits(typed.len()),
            scoring.as_ref(),
            habits,
            None,
        )
        .into_iter()
        .map(|c| ScoredCandidate {
            word: String::from_utf16_lossy(&c.word),
            score: c.score,
            edit_cost: c.edit_cost,
            edits: c.edits,
            completed_chars: c.completed_chars,
            tier: c.tier,
            dict_score: c.dict_score,
            user_score: c.user_score,
            accents: c.accents,
            slips: c.slips,
        })
        .collect();
        self.damp_mismatched_languages(walked)
    }

    /// [`Self::suggest`] for a word typed with `taps`, one per character.
    pub fn suggest_touch(&mut self, composing: &str, previous: &[&str], limit: usize, taps: &[Option<(f32, f32)>]) -> Vec<String> {
        *self.taps.borrow_mut() = taps.to_vec();
        let out = self.suggest(composing, previous, limit);
        self.taps.borrow_mut().clear();
        out
    }

    /// [`Self::decide_after`] for a word typed with `taps`, one per character.
    pub fn decide_touch(&mut self, word: &str, timing: f64, previous: Option<&str>, taps: &[Option<(f32, f32)>]) -> Decision {
        *self.taps.borrow_mut() = taps.to_vec();
        let out = self.decide_after(word, timing, previous);
        self.taps.borrow_mut().clear();
        out
    }

    /// The words before the caret, oldest first, for the context model: the
    /// sentence so far (the caller starts a new list at each sentence end).
    pub fn set_lm_context(&self, words: &[&str]) {
        if let Some(lm) = &self.lm {
            lm.borrow_mut().set_context(self.lm_prefix, words);
        }
    }

    /// The mean distance of the current word's taps to the nearest key
    /// centre, in key widths: how little the keys say. `None` without taps.
    fn tap_spread(&self) -> Option<f64> {
        let model = self.touch.as_ref()?;
        let taps = self.taps.borrow();
        let distances: Vec<f64> = taps.iter().filter_map(|t| t.and_then(|p| model.nearest_distance(p))).collect();
        if distances.is_empty() {
            None
        } else {
            Some(distances.iter().sum::<f64>() / distances.len() as f64)
        }
    }

    /// ln P(`word` next) from the context model, when it has one.
    /// ln P(`word` next) for a word the model lists, `None` otherwise: the
    /// per-keystroke question (blend, rerank, the gate's candidates).
    fn lm_logprob(&self, word: &str) -> Option<f32> {
        self.lm.as_ref().and_then(|lm| lm.borrow_mut().logprob_listed(word))
    }

    /// ln P(`word` next) with unknown words spelled in pieces: once per
    /// commit, for the typed word the gate judges.
    fn lm_logprob_pieces(&self, word: &str) -> Option<f32> {
        self.lm.as_ref().and_then(|lm| lm.borrow_mut().logprob(word))
    }

    /// The context model's rank for `word` here and the entropy of its
    /// next-word distribution; `None`s without a model.
    fn lm_gate_view(&self, word: &str) -> (Option<u32>, Option<f32>) {
        match &self.lm {
            Some(lm) => {
                let mut s = lm.borrow_mut();
                (s.rank_of(word), Some(s.entropy()))
            }
            None => (None, None),
        }
    }

    /// Learned-only words tagged with another language yield to this one's.
    fn damp_mismatched_languages(&self, ranked: Vec<ScoredCandidate>) -> Vec<ScoredCandidate> {
        let active = self.detected_language();
        if active.is_empty() {
            return ranked;
        }
        let mut changed = false;
        let mut out: Vec<ScoredCandidate> = ranked
            .into_iter()
            .map(|mut c| {
                let pure_user =
                    c.dict_score == f64::NEG_INFINITY && c.user_score != f64::NEG_INFINITY;
                if pure_user
                    && self
                        .lexicon
                        .language_of(&c.word)
                        .is_some_and(|t| t != active)
                {
                    c.score -= lang_mismatch_damp();
                    c.user_score -= lang_mismatch_damp();
                    changed = true;
                }
                c
            })
            .collect();
        if changed {
            out.sort_by(by_score);
        }
        out
    }

    /// A spelling the lists hold only as a corpus typo (#244).
    fn typo_shadowed(&self, lower: &str) -> bool {
        let mut typed = f64::NEG_INFINITY;
        let mut holders = Vec::new();
        for (list, w) in self.dictionary_tier() {
            if let Some(node) = list.dict().node_for(lower) {
                if list.dict().is_word(node) {
                    let f = list.dict().frequency(node);
                    typed = typed.max(w.ln() + ln1p(f as f64));
                    holders.push((list, f));
                }
            }
        }
        if holders.is_empty() {
            return false;
        }
        let fix = self
            .ranked(lower, fuzzy::AUTOCORRECT_K / 2)
            .into_iter()
            .take(fuzzy::AUTOCORRECT_K)
            .filter(|c| c.edits == 1 && c.completed_chars == 0 && !self.suppressed(&c.word))
            .map(|c| c.dict_score)
            .fold(f64::NEG_INFINITY, f64::max);
        if fix == f64::NEG_INFINITY || fix - typed < TYPO_SHADOW_RATIO.ln() {
            return false;
        }
        holders
            .iter()
            .all(|(list, f)| list.rank_of_frequency(*f) > TYPO_SHADOW_MIN_RANK)
    }

    /// A spelling far rarer than its own accented twin: `zle` for `źle`.
    fn accent_shadowed(&self, lower: &str) -> bool {
        let twin = self
            .ranked(lower, fuzzy::AUTOCORRECT_K / 2)
            .into_iter()
            .filter(|c| c.edits == 0 && c.completed_chars == 0 && c.accents > 0)
            .map(|c| c.dict_score + c.accents as f64 * fuzzy::cost_accent())
            .fold(f64::NEG_INFINITY, f64::max);
        if twin == f64::NEG_INFINITY {
            return false;
        }
        let typed = self.dictionary_score(lower);
        typed != f64::NEG_INFINITY && twin - typed >= ACCENT_SHADOW_RATIO.ln()
    }

    fn known_compound(&self, lower: &str) -> bool {
        if !lower.contains('-') {
            return false;
        }
        let parts: Vec<&str> = lower.split('-').collect();
        (2..=MAX_COMPOUND_PARTS).contains(&parts.len())
            && parts
                .iter()
                .all(|p| !p.is_empty() && (self.in_dictionaries(p) || self.lexicon.contains(p)))
    }

    // ---- the strip ----

    /// A known word's nearest slips, below it on the strip (#979): up to
    /// `KNOWN_WORD_SLIPS` one-edit words, best first, each at its own score
    /// but never above `KNOWN_WORD_SLIP_GAP` under the word typed. The phone's
    /// `SuggestionEngine.knownWordSlips`; autocorrect still leaves the word alone.
    fn known_word_slips(&self, lower: &str, walked: &[ScoredCandidate]) -> Vec<(String, f64)> {
        let Some(typed) = walked
            .iter()
            .find(|c| c.edits == 0 && c.completed_chars == 0 && c.word.to_lowercase() == lower)
            .map(|c| c.score)
        else {
            return Vec::new();
        };
        let ceiling = typed - KNOWN_WORD_SLIP_GAP;
        let key = key_of(lower);
        walked
            .iter()
            .filter(|c| c.edits == 1 && c.completed_chars == 0 && key_of(&c.word) != key)
            // The word with a hyphen in it is not a slip.
            .filter(|c| c.word.replace('-', "").to_lowercase() != lower)
            .filter(|c| !self.suppressed(&c.word))
            .take(KNOWN_WORD_SLIPS)
            .map(|c| (c.word.clone(), c.score.min(ceiling)))
            .collect()
    }

    /// The strip for `composing`, `previous` nearest first. Next words when
    /// nothing is being typed.
    pub fn suggest(&mut self, composing: &str, previous: &[&str], limit: usize) -> Vec<String> {
        self.lexicon.refresh();
        if composing.is_empty() {
            return self.next_words(previous, limit);
        }
        let lower = composing.to_lowercase();
        let known = (self.in_dictionaries(&lower) || self.lexicon.contains(&lower))
            && !self.typo_shadowed(&lower)
            || self.known_compound(&lower);
        let mut merged: HashMap<String, f64> = HashMap::new();
        let put = |merged: &mut HashMap<String, f64>, w: String, s: f64| {
            let e = merged.entry(w).or_insert(f64::NEG_INFINITY);
            *e = e.max(s);
        };
        let walked = self.ranked(&lower, limit);
        let mut slips: Vec<String> = Vec::new();
        if known {
            for (word, score) in self.known_word_slips(&lower, &walked) {
                slips.push(word.to_lowercase());
                put(&mut merged, word, score);
            }
        }
        for c in walked {
            if c.edits > 0 && known {
                continue;
            }
            put(&mut merged, c.word, c.score);
        }
        if !known && self.autocorrect_splits {
            for (text, score) in self.split_candidates(&lower) {
                put(&mut merged, text, score);
            }
        }
        if let Some((spelling, score, _)) = self.apostrophe_reading(&lower) {
            put(&mut merged, spelling, score);
        }
        // The context model in score space: each candidate the model knows
        // moves by its preference over the engine's current leader, so a
        // "by" the keys explain as well as "buy" gives way after "want to"
        // while a far-fetched candidate the model likes cannot climb over the
        // keys' evidence. Bounded, and silent when the leader is unknown.
        if self.lm_blend > 0.0 && self.lm.is_some() && merged.len() > 1 {
            let leader = merged.iter().max_by(|a, b| a.1.partial_cmp(b.1).unwrap_or(Ordering::Equal)).map(|(w, _)| w.clone());
            if let Some(reference) = leader.and_then(|w| self.lm_logprob(&w)) {
                let weight = lm_blend_for(self.lm_blend, self.lm_blend_slope, self.tap_spread());
                let shifts: Vec<(String, f64)> = merged
                    .keys()
                    .filter_map(|w| self.lm_logprob(w).map(|lp| (w.clone(), (weight * (lp - reference) as f64).clamp(-self.lm_blend_cap, self.lm_blend_cap))))
                    .collect();
                for (w, shift) in shifts {
                    if let Some(score) = merged.get_mut(&w) {
                        *score += shift;
                    }
                }
            }
        }
        if let Some(prev) = previous.first().map(|p| p.to_lowercase()) {
            let prev2 = previous.get(1).map(|p| p.to_lowercase());
            for (word, score) in merged.iter_mut() {
                let candidate = word.to_lowercase();
                let count = self.context_count(&prev, prev2.as_deref(), &candidate);
                let boost = if count > 0 {
                    context_boost(count)
                } else if self.english_sources && self.seeds.follows(&prev, &candidate) {
                    seed_context_boost()
                } else {
                    0.0
                };
                *score += boost;
            }
        }
        if !self.context_words.is_empty() {
            for (word, score) in merged.iter_mut() {
                if self.context_words.contains(&word.to_lowercase()) {
                    *score += app_recency_boost();
                }
            }
        }
        if self.register != Register::Neutral {
            let shift = if self.register == Register::Casual {
                casual_informal_boost()
            } else {
                -formal_informal_damp()
            };
            for (word, score) in merged.iter_mut() {
                if informal(word) {
                    *score += shift;
                }
            }
        }
        if !self.rank_offsets.is_empty() {
            for (word, score) in merged.iter_mut() {
                *score += self.rank_offset(word);
            }
        }
        let mut by_key: HashMap<String, (String, f64)> = HashMap::new();
        for (word, score) in merged {
            let key = key_of(&word);
            match by_key.get(&key) {
                Some((_, s)) if *s >= score => {}
                _ => {
                    by_key.insert(key, (word, score));
                }
            }
        }
        let mut ranked: Vec<(String, f64)> = by_key.into_values().collect();
        ranked.sort_by(|a, b| {
            b.1.partial_cmp(&a.1)
                .unwrap_or(Ordering::Equal)
                .then_with(|| a.0.cmp(&b.0))
        });
        let mut ranked: Vec<String> = ranked
            .into_iter()
            .map(|(w, _)| w)
            .filter(|w| !self.suppressed(w))
            .take(limit.max(RERANK_POOL))
            .collect();
        let head = if self.context_rerank {
            self.rerank(previous, &ranked[..ranked.len().min(RERANK_POOL)])
        } else {
            None
        };
        if let Some(head) = head {
            let rest: Vec<String> = ranked.into_iter().filter(|w| !head.contains(w)).collect();
            ranked = head.into_iter().chain(rest).collect();
        }
        // A slip stays behind the word typed, whatever context says of it.
        if let Some(at) = ranked.iter().position(|w| w.to_lowercase() == lower) {
            let (jumped, kept): (Vec<String>, Vec<String>) =
                ranked[..at].iter().cloned().partition(|w| slips.contains(&w.to_lowercase()));
            if !jumped.is_empty() {
                let tail = ranked[at + 1..].to_vec();
                let typed = ranked[at].clone();
                ranked = kept.into_iter().chain([typed]).chain(jumped).chain(tail).collect();
            }
        }
        ranked
            .into_iter()
            .take(limit)
            .map(|w| {
                if w.contains('@') {
                    w
                } else {
                    match_case(composing, &self.display_of(&w))
                }
            })
            .collect()
    }

    /// The phone's `NgramReranker`: each candidate's rank, less a step per
    /// place, plus the bounded evidence of the phrases it would end — the
    /// user's trigram, bigram and skip-grams, the pack's, the seed pairs,
    /// the field's recent words. None when no candidate has any.
    fn rerank(&self, previous: &[&str], candidates: &[String]) -> Option<Vec<String>> {
        let prev = previous.first()?.to_lowercase();
        if is_sentinel(&prev) || candidates.is_empty() {
            return None;
        }
        let prev2 = previous.get(1).map(|p| p.to_lowercase());
        let prev3 = previous.get(2).map(|p| p.to_lowercase());
        let recent: HashSet<String> = self.recent_words.iter().map(|w| w.to_lowercase()).collect();
        let lex = &self.lexicon;
        let pack_bigram =
            |a: &str, b: &str| self.ngrams.as_ref().map_or(0, |p| p.bigram_count(a, b));
        let pack_trigram = |a: &str, b: &str, c: &str| {
            self.ngrams.as_ref().map_or(0, |p| p.trigram_count(a, b, c))
        };
        let frequency = self
            .dictionary
            .as_ref()
            .map_or(0, |d| d.frequency_of(&prev));
        // An unknown word before: the word before it speaks instead.
        let skip = prev2
            .as_deref()
            .filter(|_| frequency == 0 && !lex.contains(&prev));
        let term = |weight: f64, count: i32, cap: f64| {
            (weight * (1.0 + count.max(0) as f64).ln()).min(cap)
        };
        // The context model's say: how much more (or less) it expects each
        // candidate than the engine's own first choice, in ranks, capped.
        // A word it does not know moves nothing, and neither does anything
        // when it does not know the leader.
        let lm_terms: Option<Vec<f64>> = self.lm.as_ref().and_then(|_| {
            let scores: Vec<Option<f32>> = candidates.iter().map(|w| self.lm_logprob(w)).collect();
            let reference = scores.first().copied().flatten()?;
            Some(
                scores
                    .iter()
                    .map(|s| match s {
                        Some(lp) => (self.lm_weight * (*lp - reference) as f64).clamp(-self.lm_cap, self.lm_cap),
                        None => 0.0,
                    })
                    .collect(),
            )
        });
        let mut any = lm_terms.as_ref().is_some_and(|t| t.iter().any(|v| *v != 0.0));
        let mut scored: Vec<(usize, &String, f64)> = candidates
            .iter()
            .enumerate()
            .map(|(i, word)| {
                let w = word.to_lowercase();
                let user3 = prev2
                    .as_deref()
                    .map_or(0, |p2| lex.trigram_count(p2, &prev, &w));
                let user2 = lex.bigram_count(&prev, &w);
                let skip1 = prev2.as_deref().map_or(0, |p2| lex.skip1gram_count(p2, &w));
                let skip2 = prev3.as_deref().map_or(0, |p3| lex.skip2gram_count(p3, &w));
                let pack3 = prev2.as_deref().map_or(0, |p2| pack_trigram(p2, &prev, &w));
                let pack2 = pack_bigram(&prev, &w);
                let seed = self.seeds.count(&prev, &w);
                let skip_user = skip.map_or(0, |s| lex.bigram_count(s, &w));
                let skip_pack = skip.map_or(0, |s| pack_bigram(s, &w));
                let evidence = term(1.2, user3, 2.5)
                    + term(0.8, user2, 2.0)
                    + term(0.5, skip1, 1.2)
                    + term(0.4, skip2, 1.1)
                    + term(0.6, pack3 / PACK_COUNT_SCALE, 1.5)
                    + term(0.45, pack2 / PACK_COUNT_SCALE, 1.2)
                    + term(0.4, seed, 1.5)
                    + term(0.5, skip_user, 1.2)
                    + term(0.3, skip_pack / PACK_COUNT_SCALE, 0.8)
                    + if recent.contains(&w) { 0.3 } else { 0.0 };
                if evidence > 0.0 {
                    any = true;
                }
                let lm = lm_terms.as_ref().map_or(0.0, |t| t[i]);
                (i, word, -(i as f64) + evidence + lm)
            })
            .collect();
        if !any {
            return None;
        }
        // Kotlin's sortedByDescending is stable: a tie keeps the rank order.
        scored.sort_by(|a, b| {
            b.2.partial_cmp(&a.2)
                .unwrap_or(Ordering::Equal)
                .then(a.0.cmp(&b.0))
        });
        Some(scored.into_iter().map(|(_, w, _)| w.clone()).collect())
    }

    /// The spelling a lower-case candidate is offered in.
    pub fn display_of(&self, word: &str) -> String {
        if word.is_empty() || key_of(word) != word {
            return word.to_string();
        }
        if let Some(own) = self.lexicon.display_of(word) {
            return own.to_string();
        }
        self.capitals
            .as_ref()
            .and_then(|c| list_spelling(c, word))
            .unwrap_or_else(|| word.to_string())
    }

    fn context_count(&self, prev: &str, prev2: Option<&str>, candidate: &str) -> i32 {
        let mut best = self.lexicon.bigram_count(prev, candidate);
        if let Some(p2) = prev2 {
            best = best.max(self.lexicon.trigram_count(p2, prev, candidate) * 2);
        }
        if let Some(pack) = &self.ngrams {
            best = best.max(pack.bigram_count(prev, candidate) / PACK_COUNT_SCALE);
            if let Some(p2) = prev2 {
                best = best.max(pack.trigram_count(p2, prev, candidate) * 2 / PACK_COUNT_SCALE);
            }
        }
        best
    }

    /// What follows `previous` (nearest first): the user's phrases, then the
    /// corpus pack, then the seed pairs.
    pub fn next_words(&self, previous: &[&str], limit: usize) -> Vec<String> {
        let Some(prev) = previous.first().map(|p| p.to_lowercase()) else {
            return Vec::new();
        };
        let prev2 = previous.get(1).map(|p| p.to_lowercase());
        let prev3 = previous.get(2).map(|p| p.to_lowercase());
        let mut ordered: Vec<String> = Vec::new();
        let mut add = |words: Vec<String>| {
            for w in words {
                if !ordered.contains(&w) {
                    ordered.push(w);
                }
            }
            ordered.len()
        };
        if let Some(p2) = &prev2 {
            add(self.lexicon.next_words_after(p2, &prev, limit));
        }
        add(self.lexicon.next_words(&prev, limit));
        if let Some(p2) = &prev2 {
            add(self.lexicon.skip1_followers(p2, limit));
        }
        if let Some(p3) = &prev3 {
            add(self.lexicon.skip2_followers(p3, limit));
        }
        // The context model's likeliest next words: after the user's own
        // habits, which know this user, before the corpus pack, which it
        // reads the same text as and reads further back.
        if self.lm_next_words {
            if let Some(lm) = &self.lm {
                add(lm.borrow_mut().top(limit, LM_NEXT_WORD_FLOOR).into_iter().map(|(w, _)| w).collect());
            }
        }
        if let Some(pack) = &self.ngrams {
            if let Some(p2) = &prev2 {
                add(pack.next_words_after(p2, &prev, limit));
            }
            add(pack.next_words(&prev, limit));
        }
        if self.english_sources {
            add(self.seeds.next_words(&prev));
        }
        // An unknown word before the caret is read through: "met Priya" still
        // offers what follows "met".
        if add(Vec::new()) < limit {
            if let Some(p2) = &prev2 {
                add(self.lexicon.next_words(p2, limit));
                if let Some(pack) = &self.ngrams {
                    add(pack.next_words(p2, limit));
                }
            }
        }
        let mut result: Vec<String> = ordered
            .into_iter()
            .filter(|w| !self.suppressed(w) && !is_sentinel(w))
            .take(limit)
            .collect();
        if self.register == Register::Formal {
            result.sort_by_key(|w| informal(w));
        }
        if !self.rank_offsets.is_empty() {
            result.sort_by_key(|w| -self.rank_offsets.get(&key_of(w)).copied().unwrap_or(0));
        }
        let mut keys = HashSet::new();
        result
            .into_iter()
            .map(|w| self.display_of(&w))
            .filter(|w| keys.insert(key_of(w)))
            .collect()
    }

    /// `previous` + `composing` as one word, when that word is far commoner
    /// than its rarer half: "some" "thing" -> "something".
    pub fn join_candidate(&self, previous: Option<&str>, composing: &str) -> Option<String> {
        let prev = previous?.to_lowercase();
        if is_sentinel(&prev) {
            return None;
        }
        let lower = composing.to_lowercase();
        if lower.chars().count() < 2 || prev.is_empty() {
            return None;
        }
        if !prev.chars().all(char::is_alphabetic) || !lower.chars().all(char::is_alphabetic) {
            return None;
        }
        let joined = format!("{prev}{lower}");
        if joined.chars().count() > JOIN_MAX_LENGTH || self.suppressed(&joined) {
            return None;
        }
        let freq = |w: &str| {
            self.dictionary_frequency_of(w).max(
                (self.lexicon.frequency_of(w) as f64 * USER_WORD_WEIGHT).min(i32::MAX as f64)
                    as i32,
            )
        };
        let joined_freq = freq(&joined);
        if joined_freq < JOIN_MIN_FREQ {
            return None;
        }
        let rarer = freq(&prev).min(freq(&lower));
        if joined_freq as f64 * JOIN_CONFIDENCE <= rarer as f64 {
            return None;
        }
        Some(joined)
    }

    /// Missing-space readings, scored by the rarer half.
    fn split_candidates(&self, word: &str) -> Vec<(String, f64)> {
        let chars: Vec<char> = word.chars().collect();
        if chars.len() < 4 || !chars.iter().all(|c| c.is_alphabetic()) {
            return Vec::new();
        }
        let freq = |part: &str| {
            let learned = if self
                .lexicon
                .is_established(part, self.learned_word_min_count)
            {
                (self.lexicon.frequency_of(part) as f64 * USER_WORD_WEIGHT).min(i32::MAX as f64)
                    as i32
            } else {
                0
            };
            self.dictionary_frequency_of(part).max(learned)
        };
        let mut out = Vec::new();
        for i in 1..chars.len() {
            let left: String = chars[..i].iter().collect();
            let left_freq = freq(&left);
            if left_freq <= 0 {
                continue;
            }
            // No split ends on one letter: that is a tokenised apostrophe.
            if chars.len() - i < 2 {
                continue;
            }
            let right: String = chars[i..].iter().collect();
            let right_freq = freq(&right);
            if right_freq > 0 {
                out.push((
                    format!("{left} {right}"),
                    ln1p(left_freq.min(right_freq) as f64 * WEIGHT_SPLIT),
                ));
            }
        }
        out
    }

    fn known_phrase(&self, left: &str, right: &str) -> bool {
        self.ngrams
            .as_ref()
            .is_some_and(|p| p.bigram_count(left, right) > 0)
            || (self.english_sources && self.seeds.follows(left, right))
            || self.lexicon.bigram_count(left, right) >= SPLIT_PAIR_MIN_USES
    }

    // ---- autocorrect ----

    /// The correction a space applies to `word`, if any.
    pub fn should_autocorrect(&mut self, word: &str) -> Option<String> {
        self.decide(word, 1.0).apply
    }

    /// What to do with `word`, which a space is about to commit.
    /// `timing` scales the gate: under 1 for fast bursts, over for slow.
    pub fn decide(&mut self, word: &str, timing: f64) -> Decision {
        self.decide_after(word, timing, None)
    }

    /// [`Self::decide`] with the word before it, which a fix the user taught
    /// for a spelling that is itself a word needs as a second witness.
    pub fn decide_after(&mut self, word: &str, timing: f64, previous: Option<&str>) -> Decision {
        self.lexicon.refresh();
        let lower = word.to_lowercase();
        if lower.chars().count() < 3 {
            return Decision::default();
        }
        if self.skip_all_caps && is_all_caps(word) {
            return Decision::default();
        }
        let ordinary = self.decide_ordinary(word, &lower, timing);
        self.with_taught_fix(word, &lower, previous, ordinary)
    }

    /// Lays what the user has taught about `lower` over the engine's own
    /// decision: fixed by hand twice is applied, once is offered, and a
    /// spelling that is a word gives way only after three fixes with the
    /// context agreeing.
    fn with_taught_fix(
        &self,
        word: &str,
        lower: &str,
        previous: Option<&str>,
        ordinary: Decision,
    ) -> Decision {
        let Some(taught) = self.memory.fix_for(lower) else {
            return ordinary;
        };
        let fixed = taught.fixed;
        if fixed == lower || fixed.split(' ').any(|p| self.suppressed(p)) {
            return ordinary;
        }
        if self.stats.penalty(lower, &fixed) != Penalty::None {
            return ordinary;
        }
        // A spelling on Never suggest is one the user does not mean,
        // whatever list holds it (#881): their fix is taught as for a typo.
        let known_typed = !self.blacklist.contains(&key_of(lower))
            && (self.in_dictionaries(lower)
                || self
                    .lexicon
                    .is_established(lower, self.learned_word_min_count));
        let applied = || {
            let edits = correction_memory::distance(lower, &fixed) as f64;
            let shape = (edits / 2.0).clamp(0.0, 1.0);
            let length = ((lower.encode_utf16().count() as f64 - PLAIN_WORD_LENGTH)
                / PLAIN_WORD_SPAN)
                .clamp(0.0, 1.0);
            Decision {
                apply: Some(match_case(word, &fixed)),
                certainty: taught.count as f64 / (taught.count as f64 + 1.0),
                complexity: COMPLEXITY_SHAPE_WEIGHT * shape
                    + (1.0 - COMPLEXITY_SHAPE_WEIGHT) * length,
                ..Decision::default()
            }
        };
        let offered = || Decision {
            offer: Some(match_case(word, &fixed)),
            ..Decision::default()
        };
        if known_typed {
            if taught.count < correction_memory::APPLY_AT || !self.precedes(previous, lower, &fixed)
            {
                return ordinary;
            }
            return if taught.count >= correction_memory::APPLY_KNOWN_AT {
                applied()
            } else {
                offered()
            };
        }
        if taught.count >= correction_memory::APPLY_AT {
            return applied();
        }
        if ordinary
            .apply
            .as_ref()
            .is_some_and(|a| a.eq_ignore_ascii_case(&fixed))
        {
            return ordinary;
        }
        offered()
    }

    /// The user undid `fixed` back to `typed`. `deliberate` is a backspace
    /// on the fix itself or the undo chip; otherwise only a word that is
    /// known is believed.
    pub fn reject_correction(&mut self, typed: &str, fixed: &str, deliberate: bool) {
        self.reject_correction_for(typed, fixed, deliberate, typed);
    }

    /// [`Self::reject_correction`] where the user rewrote the fix into a
    /// third word, `survivor`, rather than back into what was typed.
    pub fn reject_correction_for(
        &mut self,
        typed: &str,
        fixed: &str,
        deliberate: bool,
        survivor: &str,
    ) {
        if typed.is_empty() || fixed.is_empty() {
            return;
        }
        if !deliberate && self.stats.memory != UndoMemory::Strict && !self.is_known_word(survivor) {
            return;
        }
        self.stats.record_revert(typed, fixed, deliberate);
        self.memory.unteach(typed, fixed);
    }

    /// The user let the correction of `typed` into `fixed` stand.
    pub fn record_kept(&mut self, typed: &str, fixed: &str) {
        self.stats.record_kept(typed, fixed);
    }

    /// The apostrophe `word` was typed without, when the space should put it
    /// in: "thats" -> "that's". None for a spelling that is a word of its own
    /// (offered on the strip instead), or one whose repair was undone.
    pub fn elide(&self, word: &str) -> Option<String> {
        let (spelling, _, shadowed) = self.apostrophe_reading(&word.to_lowercase())?;
        if !shadowed {
            return None;
        }
        let out = match_case(word, &spelling);
        (out != word).then_some(out)
    }

    /// `lower` read as a contraction: the spelling, its strip score, and
    /// whether it is the one to commit.
    fn apostrophe_reading(&self, lower: &str) -> Option<(String, f64, bool)> {
        if !self.apostrophe_fixes || !apostrophes::serves_language(self.language_or_english()) {
            return None;
        }
        let language = self.language_or_english();
        let Some(fixed) = apostrophes::fix(lower, language) else {
            return self.declared_reading(lower, apostrophes::offer(lower, language));
        };
        if self.stats.penalty(lower, &fixed) != Penalty::None {
            return self.declared_reading(lower, Some(fixed));
        }
        let finite = |w: &str| {
            let s = self.dictionary_score(w);
            if s.is_finite() {
                s
            } else {
                0.0
            }
        };
        let scored = finite(&fixed.to_lowercase()).max(finite(lower));
        Some((fixed, scored + CONTRACTION_LEAD, true))
    }

    fn declared_reading(&self, lower: &str, fixed: Option<String>) -> Option<(String, f64, bool)> {
        let fixed = fixed?;
        let typed = self.dictionary_score(lower);
        let own = self.dictionary_score(&fixed.to_lowercase());
        let score = if typed == f64::NEG_INFINITY {
            own
        } else {
            own.max(typed - DECLARED_TRAIL.ln())
                .min(typed - DECLARED_BEHIND)
        };
        (score != f64::NEG_INFINITY).then_some((fixed, score, false))
    }

    fn language_or_english(&self) -> &str {
        if self.primary_language.is_empty() {
            "en"
        } else {
            &self.primary_language
        }
    }

    /// The word `previous` should have been, given that `follower` came
    /// next: "their going" -> "they're". For a chip, never applied.
    pub fn advise(&self, previous: &str, follower: &str) -> Option<String> {
        let p = previous.to_lowercase();
        let f = follower.to_lowercase();
        if is_sentinel(&p) || is_sentinel(&f) || f.is_empty() || !f.chars().all(char::is_alphabetic)
        {
            return None;
        }
        let set = CONFUSABLES.iter().find(|s| s.contains(&p.as_str()))?;
        let mut best: Option<(&str, f64)> = None;
        for alt in set.iter().copied().filter(|a| *a != p) {
            let ratio = self.dominance(&p, alt, &f);
            if best.is_none_or(|(_, r)| ratio > r) {
                best = Some((alt, ratio));
            }
        }
        best.filter(|(_, r)| *r >= ADVISE_DOMINANCE)
            .map(|(alt, _)| match_case(previous, alt))
    }

    /// Whether the word before says `fixed` was meant where `typed` stands.
    fn precedes(&self, previous: Option<&str>, typed: &str, fixed: &str) -> bool {
        let Some(p) = previous.map(str::to_lowercase) else {
            return false;
        };
        if p.is_empty() || is_sentinel(&p) {
            return false;
        }
        let t = typed.to_lowercase();
        let f = fixed.to_lowercase();
        let f = f.split(' ').next().unwrap_or("");
        if t.is_empty() || f.is_empty() {
            return false;
        }
        self.ratio_over_sources(|count| (count(&p, f), count(&p, &t))) >= TAUGHT_DOMINANCE
    }

    fn dominance(&self, prev: &str, alt: &str, follower: &str) -> f64 {
        self.ratio_over_sources(|count| (count(alt, follower), count(prev, follower)))
    }

    /// The best add-one ratio any one source gives, each source held to its
    /// own minimum: user counts and corpus counts never mix.
    fn ratio_over_sources(&self, pick: impl Fn(&dyn Fn(&str, &str) -> i32) -> (i32, i32)) -> f64 {
        let mut best = 0.0f64;
        let mut consider = |(win, lose): (i32, i32), min: i32| {
            if win >= min {
                best = best.max((win + 1) as f64 / (lose + 1) as f64);
            }
        };
        consider(
            pick(&|a: &str, b: &str| self.lexicon.bigram_count(a, b)),
            MIN_USER_COUNT,
        );
        if let Some(pack) = &self.ngrams {
            consider(
                pick(&|a: &str, b: &str| pack.bigram_count(a, b)),
                MIN_CORPUS_COUNT,
            );
        }
        if self.english_sources {
            consider(
                pick(&|a: &str, b: &str| self.seeds.count(a, b)),
                MIN_CORPUS_COUNT,
            );
        }
        best
    }

    fn decide_ordinary(&self, word: &str, lower: &str, timing: f64) -> Decision {
        let known = self.in_dictionaries(lower)
            || self
                .lexicon
                .is_established(lower, self.learned_word_min_count);
        if known && !self.accent_shadowed(lower) && !self.typo_shadowed(lower) {
            return Decision::default();
        }
        if !known && self.english_sources && is_verb_form(lower, &|w| self.in_dictionaries(w)) {
            return Decision::default();
        }
        if self.known_compound(lower) {
            return Decision::default();
        }
        let digits = lower.chars().filter(char::is_ascii_digit).count();
        if digits > 0 && (!self.digit_slips || digits > 1) {
            return Decision::default();
        }
        let insert = fuzzy::cost_insert_adjacent();
        let shadowed = known;
        let mixes = |c: &ScoredCandidate| c.slips > 0 && c.edits > c.slips;
        let mut shaped: Vec<ScoredCandidate> = self
            .ranked(lower, fuzzy::AUTOCORRECT_K / 2)
            .into_iter()
            .take(fuzzy::AUTOCORRECT_K)
            .filter(|c| {
                // Slips are the exception to one edit: any number of them
                // plus at most one real edit is a fix in place, and a mix of
                // the two applies only when eager (the phone's `SlipFixes`).
                let shape = if c.slips > 0 {
                    c.completed_chars == 0
                        && c.edits - c.slips <= 1
                        && (self.slip_fixes == SlipFixes::Eager || !mixes(c))
                } else {
                    match c.edits {
                        0 if c.accents > 0 => c.completed_chars == 0,
                        0 => c.completed_chars == 1 && c.word != lower,
                        1 => c.completed_chars == 0,
                        _ => false,
                    }
                };
                shape
                    && !self.suppressed(&c.word)
                    && (digits == 0 || digit_sub_shape(lower, &c.word))
            })
            .map(|mut c| {
                // A one-letter completion is the old insert-at-end edit.
                if c.edits == 0 && c.accents == 0 {
                    c.score -= insert;
                    c.edit_cost = insert;
                    c.edits = 1;
                    c.completed_chars = 0;
                    c.dict_score -= insert;
                    c.user_score -= insert;
                }
                c
            })
            .filter_map(|mut c| match self.stats.penalty(lower, &c.word) {
                Penalty::Blocked => None,
                Penalty::Penalized => {
                    c.score -= pair_penalty();
                    c.dict_score = f64::NEG_INFINITY;
                    c.user_score = f64::NEG_INFINITY;
                    Some(c)
                }
                Penalty::Probation => {
                    c.dict_score = f64::NEG_INFINITY;
                    c.user_score = f64::NEG_INFINITY;
                    Some(c)
                }
                Penalty::None => Some(c),
            })
            .collect();
        shaped.sort_by(by_score);
        // A reading that only puts accents back explains every keystroke.
        let accented: Vec<ScoredCandidate> = shaped
            .iter()
            .filter(|c| c.edits == 0 && c.accents > 0)
            .cloned()
            .collect();
        let candidates = if accented.is_empty() {
            shaped
        } else {
            accented
        };

        let mut best_dict: Option<&ScoredCandidate> = None;
        let mut best_user: Option<&ScoredCandidate> = None;
        for c in &candidates {
            if c.dict_score > best_dict.map_or(f64::NEG_INFINITY, |b| b.dict_score) {
                best_dict = Some(c);
            }
            if c.user_score > best_user.map_or(f64::NEG_INFINITY, |b| b.user_score) {
                best_user = Some(c);
            }
        }
        let two_sources = matches!((best_dict, best_user), (Some(d), Some(u)) if d.word == u.word);
        if self.confidence.is_none() {
            if let (Some(d), Some(u)) = (best_dict, best_user) {
                if d.word == u.word {
                    return Decision {
                        apply: Some(match_case(word, &d.word)),
                        certainty: 1.0,
                        complexity: complexity_of_candidate(lower, d),
                        ..Decision::default()
                    };
                }
            }
        }

        let multiplier = if self.adaptive_confidence {
            self.stats.multiplier()
        } else {
            1.0
        };
        let effective = (self.autocorrect_confidence * multiplier * timing)
            .clamp(MIN_AUTOCORRECT_CONFIDENCE, MAX_AUTOCORRECT_CONFIDENCE);
        let gate = effective.ln();
        let top = candidates.first();
        // An eager mixed reading never stands as the runner-up that blocks a
        // cleaner fix: it is the speculative one of the two.
        let runner_up = match top {
            Some(t) if !mixes(t) => candidates.iter().skip(1).find(|c| !mixes(c)),
            _ => candidates.get(1),
        };
        let margin = match top {
            None => f64::NEG_INFINITY,
            Some(t) => t.score - runner_up.map_or(SOLO_RUNNER_UP_SCORE, |c| c.score),
        };
        let top_penalty = top.map_or(Penalty::None, |t| self.stats.penalty(lower, &t.word));
        let penalized = top_penalty != Penalty::None;
        let probation = top_penalty == Penalty::Probation;
        // The learned gate, when there is one: one probability over
        // everything the margin gate and its shapes used to weigh one by one.
        *self.last_case.borrow_mut() = None;
        let probability = match (&self.confidence, top) {
            (model, Some(t)) => {
                let lm_scores: Vec<Option<f32>> = if self.lm.is_some() {
                    candidates.iter().map(|c| self.lm_logprob(&c.word)).collect()
                } else {
                    Vec::new()
                };
                let mut lm_sorted: Vec<f32> = lm_scores.iter().filter_map(|s| *s).collect();
                lm_sorted.sort_by(|a, b| b.total_cmp(a));
                let (lm_typed_rank, lm_entropy) = self.lm_gate_view(lower);
                let lm_typed = if self.lm.is_some() { self.lm_logprob(lower) } else { None };
                let lm_typed_pieces = if self.lm.is_some() && lm_typed.is_none() { self.lm_logprob_pieces(lower) } else { None };
                let case = Case {
                    margin,
                    top_score: t.score,
                    top_edit_cost: t.edit_cost,
                    top_edits: t.edits,
                    top_slips: t.slips,
                    top_accents: t.accents,
                    top_completed: t.completed_chars,
                    top_in_dictionary: t.dict_score.is_finite(),
                    top_in_lexicon: t.user_score.is_finite(),
                    two_sources,
                    mixes_slips: mixes(t),
                    typed_len: lower.chars().count(),
                    has_runner: runner_up.is_some(),
                    runner_edit_cost: runner_up.map_or(0.0, |c| c.edit_cost),
                    candidates: candidates.len(),
                    lm_top: lm_scores.first().copied().flatten(),
                    lm_best: lm_scores.iter().filter_map(|s| *s).fold(None, |m: Option<f32>, v| Some(m.map_or(v, |m| m.max(v)))),
                    timing: timing.max(1e-6).ln(),
                    typed_shadowed: shadowed,
                    lm_typed,
                    lm_typed_rank,
                    lm_entropy,
                    lm_second: lm_sorted.get(1).copied(),
                    lm_typed_pieces,
                };
                *self.last_case.borrow_mut() = Some((case, t.word.clone()));
                model.as_ref().map(|m| m.probability(&case))
            }
            _ => None,
        };
        let single = match top {
            None => None,
            Some(_) if probation => None,
            Some(_) if candidates.len() == 1 && penalized => None,
            Some(t) => match probability {
                Some(p) => (p >= confidence::apply_threshold(effective)).then_some(t),
                None => (margin >= gate).then_some(t),
            },
        };
        if let Some(t) = single {
            let certainty = match probability {
                Some(p) => {
                    let threshold = confidence::apply_threshold(effective);
                    ((p - threshold) / (1.0 - threshold)).clamp(0.0, 1.0)
                }
                None => certainty_of(margin, gate),
            };
            return Decision {
                apply: Some(match_case(word, &t.word)),
                certainty,
                complexity: complexity_of_candidate(lower, t),
                ..Decision::default()
            };
        }
        if let Some(split) = self.split_correction(lower, top.map(|t| t.score), effective) {
            return Decision {
                apply: Some(match_case(word, &split)),
                certainty: 1.0,
                complexity: 1.0,
                ..Decision::default()
            };
        }
        let may_offer = probation || !penalized;
        let clears_offer = match probability {
            Some(p) => p >= confidence::offer_threshold(effective),
            None => margin >= gate * OFFER_MARGIN_FRACTION,
        };
        let offer = top
            .filter(|_| may_offer && clears_offer)
            .map(|t| match_case(word, &t.word));
        Decision {
            offer,
            ..Decision::default()
        }
    }

    fn split_correction(
        &self,
        lower: &str,
        best_word: Option<f64>,
        effective: f64,
    ) -> Option<String> {
        if !self.autocorrect_splits
            || lower.chars().count() < SPLIT_AUTOCORRECT_MIN_LENGTH
            || self.lexicon.contains(lower)
        {
            return None;
        }
        let mut splits: Vec<(String, f64)> = self
            .split_candidates(lower)
            .into_iter()
            .filter(|(text, _)| {
                let halves: Vec<&str> = text.split(' ').collect();
                halves
                    .iter()
                    .all(|h| h.chars().count() >= 2 && !self.suppressed(h))
                    && self.known_phrase(halves[0], halves[1])
            })
            .collect();
        splits.sort_by(|a, b| {
            b.1.partial_cmp(&a.1)
                .unwrap_or(Ordering::Equal)
                .then_with(|| a.0.cmp(&b.0))
        });
        let best = splits.first()?;
        if self.stats.penalty(lower, &best.0) != Penalty::None {
            return None;
        }
        let rival = best_word
            .unwrap_or(f64::NEG_INFINITY)
            .max(splits.get(1).map_or(f64::NEG_INFINITY, |s| s.1))
            .max(SOLO_RUNNER_UP_SCORE);
        (best.1 - rival >= effective.ln()).then(|| best.0.clone())
    }
}

/// Capitals past the eleventh letter are not kept.
const MASK_BITS: usize = 11;
const ALL_CAPS: i32 = (1 << MASK_BITS) - 1;

/// `key` as a capitals trie spells it, if it has a capital there.
pub fn list_spelling(capitals: &Dict, key: &str) -> Option<String> {
    let shape = capitals.frequency_of(key);
    if shape <= 0 {
        return None;
    }
    if shape == ALL_CAPS {
        return Some(key.to_uppercase());
    }
    Some(
        key.chars()
            .enumerate()
            .map(|(i, c)| {
                if i < MASK_BITS && shape & (1 << i) != 0 {
                    c.to_uppercase().next().unwrap_or(c)
                } else {
                    c
                }
            })
            .collect(),
    )
}

fn context_boost(count: i32) -> f64 {
    if count > 0 {
        ln1p(CONTEXT_BIGRAM_BETA * ln1p(count as f64)).min(max_context_boost())
    } else {
        0.0
    }
}

fn certainty_of(margin: f64, gate: f64) -> f64 {
    let surplus = (margin - gate).max(0.0);
    surplus / (surplus + gate)
}

/// [`complexity_of`] with a candidate's slips counted: each is half a far
/// substitution, since the edit cost never sees them.
fn complexity_of_candidate(lower: &str, c: &ScoredCandidate) -> f64 {
    complexity_of(lower, c.edit_cost + c.slips as f64 * fuzzy::cost_sub_far() / 2.0)
}

fn complexity_of(lower: &str, edit_cost: f64) -> f64 {
    let shape = (edit_cost / fuzzy::cost_sub_far()).clamp(0.0, 1.0);
    let length = ((lower.encode_utf16().count() as f64 - PLAIN_WORD_LENGTH) / PLAIN_WORD_SPAN)
        .clamp(0.0, 1.0);
    COMPLEXITY_SHAPE_WEIGHT * shape + (1.0 - COMPLEXITY_SHAPE_WEIGHT) * length
}

/// `candidate` is `typed` with its one digit swapped for a letter.
fn digit_sub_shape(typed: &str, candidate: &str) -> bool {
    let t: Vec<char> = typed.chars().collect();
    let c: Vec<char> = candidate.chars().collect();
    t.len() == c.len()
        && t.iter().zip(&c).all(|(t, c)| {
            if t.is_ascii_digit() {
                !c.is_ascii_digit()
            } else {
                t == c
            }
        })
}

fn letters_all_upper(word: &str) -> bool {
    word.chars().all(|c| !c.is_alphabetic() || c.is_uppercase())
}

/// More than one letter long, and in capitals.
pub fn is_all_caps(word: &str) -> bool {
    word.chars().count() > 1 && word.chars().any(char::is_alphabetic) && letters_all_upper(word)
}

fn capitalize_first(word: &str) -> String {
    let mut chars = word.chars();
    match chars.next() {
        None => String::new(),
        Some(first) => first.to_uppercase().chain(chars).collect(),
    }
}

/// `suggestion` in the capitals `typed` was written in.
pub fn match_case(typed: &str, suggestion: &str) -> String {
    if typed.chars().count() > 1 && letters_all_upper(typed) {
        suggestion.to_uppercase()
    } else if typed.chars().next().is_some_and(char::is_uppercase) {
        capitalize_first(suggestion)
    } else {
        suggestion.to_string()
    }
}

// ---- English verb forms (#395), the Kotlin `Inflections` ----

const MIN_STEM: usize = 3;
const VOWELS: &[u8] = b"aeiouy";
const IRREGULAR: &[&str] = &[
    "arise",
    "awake",
    "bear",
    "beat",
    "become",
    "begin",
    "bend",
    "bet",
    "bind",
    "bite",
    "bleed",
    "blow",
    "break",
    "breed",
    "bring",
    "build",
    "burst",
    "buy",
    "cast",
    "catch",
    "choose",
    "cling",
    "come",
    "cost",
    "creep",
    "cut",
    "deal",
    "dig",
    "draw",
    "drink",
    "drive",
    "eat",
    "fall",
    "feed",
    "feel",
    "fight",
    "find",
    "flee",
    "fling",
    "fly",
    "forbid",
    "forget",
    "forgive",
    "freeze",
    "get",
    "give",
    "grind",
    "grow",
    "hang",
    "have",
    "hear",
    "hide",
    "hit",
    "hold",
    "hurt",
    "keep",
    "kneel",
    "know",
    "lay",
    "lead",
    "leave",
    "lend",
    "let",
    "lie",
    "light",
    "lose",
    "make",
    "mean",
    "meet",
    "mistake",
    "overcome",
    "pay",
    "put",
    "quit",
    "read",
    "ride",
    "ring",
    "rise",
    "run",
    "say",
    "see",
    "seek",
    "sell",
    "send",
    "set",
    "shake",
    "shed",
    "shine",
    "shoot",
    "shrink",
    "shut",
    "sing",
    "sink",
    "sit",
    "slay",
    "sleep",
    "slide",
    "sling",
    "slit",
    "speak",
    "speed",
    "spend",
    "spin",
    "spit",
    "split",
    "spread",
    "spring",
    "stand",
    "steal",
    "stick",
    "sting",
    "stink",
    "stride",
    "strike",
    "string",
    "strive",
    "swear",
    "sweep",
    "swim",
    "swing",
    "take",
    "teach",
    "tear",
    "tell",
    "think",
    "throw",
    "thrust",
    "tread",
    "understand",
    "undertake",
    "undo",
    "upset",
    "wake",
    "wear",
    "weave",
    "weep",
    "win",
    "wind",
    "withdraw",
    "wring",
    "write",
];

fn vowel(b: u8) -> bool {
    VOWELS.contains(&b)
}

/// Whether `lower` is the -s, -ed or -ing form of a verb `known` holds in
/// its other forms.
pub fn is_verb_form(lower: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let b = lower.as_bytes();
    let n = b.len();
    if n <= MIN_STEM || !b.iter().all(u8::is_ascii_lowercase) {
        return false;
    }
    match b[n - 1] {
        b's' => b[n - 2] != b's' && third_person(lower, known),
        b'd' => n > MIN_STEM + 1 && b[n - 2] == b'e' && past(lower, known),
        b'g' => n > MIN_STEM + 2 && lower.ends_with("ing") && participle(lower, known),
        _ => false,
    }
}

fn last(s: &str) -> u8 {
    *s.as_bytes().last().unwrap_or(&0)
}

fn has_third_person(stem: &str, known: &dyn Fn(&str) -> bool) -> bool {
    known(&format!("{stem}s"))
        || known(&format!("{stem}es"))
        || (last(stem) == b'y' && known(&format!("{}ies", &stem[..stem.len() - 1])))
}

fn has_past(stem: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let l = last(stem);
    known(&format!("{stem}ed"))
        || (l == b'e' && known(&format!("{stem}d")))
        || (l == b'y' && known(&format!("{}ied", &stem[..stem.len() - 1])))
        || (!vowel(l) && known(&format!("{stem}{}ed", l as char)))
}

fn has_participle(stem: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let l = last(stem);
    known(&format!("{stem}ing"))
        || (l == b'e' && known(&format!("{}ing", &stem[..stem.len() - 1])))
        || (!vowel(l) && known(&format!("{stem}{}ing", l as char)))
}

fn must_double(stem: &str) -> bool {
    let s = stem.as_bytes();
    let k = s.len();
    if k < 3 {
        return false;
    }
    let l = s[k - 1];
    if vowel(l) || l == b'w' || l == b'x' || !vowel(s[k - 2]) || vowel(s[k - 3]) {
        return false;
    }
    !s[..k - 2].iter().any(|c| vowel(*c))
}

fn undoubled(stem: &str) -> Option<&str> {
    let s = stem.as_bytes();
    let k = s.len();
    (k > MIN_STEM && s[k - 1] == s[k - 2] && !vowel(s[k - 1])).then(|| &stem[..k - 1])
}

fn ends_consonant_y(stem: &str) -> bool {
    let s = stem.as_bytes();
    s.len() >= 2 && s[s.len() - 1] == b'y' && !vowel(s[s.len() - 2])
}

fn drops_e(stem: &str) -> bool {
    let s = stem.as_bytes();
    s.len() >= 2 && s[s.len() - 1] == b'e' && !b"eoy".contains(&s[s.len() - 2])
}

fn takes_es(stem: &str) -> bool {
    stem.len() >= MIN_STEM
        && (matches!(last(stem), b's' | b'x' | b'z' | b'o')
            || stem.ends_with("ch")
            || stem.ends_with("sh"))
}

fn third_person(lower: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let n = lower.len();
    let fits = |stem: &str| {
        stem.len() >= MIN_STEM
            && known(stem)
            && !has_third_person(stem, known)
            && (has_past(stem, known) || has_participle(stem, known))
    };
    let s = &lower[..n - 1];
    if !takes_es(s) && !ends_consonant_y(s) && fits(s) {
        return true;
    }
    if lower.as_bytes()[n - 2] != b'e' {
        return false;
    }
    let es = &lower[..n - 2];
    if takes_es(es) && fits(es) {
        return true;
    }
    lower.as_bytes()[n - 3] == b'i' && fits(&format!("{}y", &lower[..n - 3]))
}

fn past(lower: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let n = lower.len();
    let fits = |stem: &str, doubled: bool| {
        stem.len() >= MIN_STEM
            && known(stem)
            && !IRREGULAR.contains(&stem)
            && (doubled || !must_double(stem))
            && !has_past(stem, known)
            && has_participle(stem, known)
    };
    let ed = &lower[..n - 2];
    if last(ed) != b'e' && !ends_consonant_y(ed) && fits(ed, false) {
        return true;
    }
    if fits(&lower[..n - 1], false) {
        return true;
    }
    if undoubled(ed).is_some_and(|s| fits(s, true)) {
        return true;
    }
    last(ed) == b'i' && fits(&format!("{}y", &ed[..ed.len() - 1]), false)
}

fn participle(lower: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let n = lower.len();
    let fits = |stem: &str, doubled: bool| {
        stem.len() >= MIN_STEM
            && known(stem)
            && (doubled || !must_double(stem))
            && !has_participle(stem, known)
            && has_past(stem, known)
    };
    let ing = &lower[..n - 3];
    if !drops_e(ing) && fits(ing, false) {
        return true;
    }
    if !vowel(last(ing)) && fits(&format!("{ing}e"), false) {
        return true;
    }
    undoubled(ing).is_some_and(|s| fits(s, true))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list(entries: &[(&str, i32)]) -> Option<WordList> {
        Some(WordList::of(entries))
    }

    /// SuggestionEngineTest's engine: six English words.
    fn engine() -> Predictor {
        Predictor {
            dictionary: list(&[
                ("the", 100),
                ("they", 90),
                ("them", 80),
                ("hello", 70),
                ("help", 60),
                ("world", 50),
            ]),
            ..Predictor::default()
        }
    }

    fn with(entries: &[(&str, i32)]) -> Predictor {
        Predictor {
            dictionary: list(entries),
            ..Predictor::default()
        }
    }

    fn near_miss() -> Predictor {
        with(&[("test", 100), ("tear", 60)])
    }

    fn split_engine() -> Predictor {
        let mut e = engine();
        e.autocorrect_splits = true;
        for _ in 0..2 {
            e.lexicon.learn_bigram("the", "world");
        }
        e
    }

    #[test]
    fn prefix_completion_and_capitals() {
        let mut e = engine();
        let s = e.suggest("th", &[], 5);
        assert_eq!("the", s[0]);
        assert!(s.contains(&"they".to_string()));
        assert_eq!("The", e.suggest("Th", &[], 5)[0]);
    }

    #[test]
    fn typo_correction_reaches_the_strip() {
        let s = engine().suggest("helo", &[], 5);
        assert!(s.contains(&"hello".to_string()) || s.contains(&"help".to_string()));
    }

    #[test]
    fn autocorrect_fires_only_when_confident() {
        assert_eq!(Some("world".into()), engine().should_autocorrect("wprld"));
        assert_eq!(None, engine().should_autocorrect("hello"));
        assert_eq!(None, engine().should_autocorrect("xy"));
        assert_eq!(None, engine().should_autocorrect("helo"));
    }

    #[test]
    fn timing_scales_the_gate() {
        let mut e = with(&[("hello", 70)]);
        assert_eq!(Some("hello".into()), e.decide("hallo", 1.0).apply);
        assert_eq!(Some("hello".into()), e.decide("hallo", 0.5).apply);
        assert_eq!(None, e.decide("hallo", 2.5).apply);
    }

    #[test]
    fn the_blacklist_hides_and_blocks() {
        let mut e = engine();
        e.blacklist.insert("they".into());
        let s = e.suggest("th", &[], 5);
        assert!(
            !s.contains(&"they".to_string())
                && s.contains(&"the".to_string())
                && s.contains(&"them".to_string())
        );
        assert!(!e.suggest("Th", &[], 5).contains(&"They".to_string()));
        let mut e = engine();
        e.blacklist.insert("world".into());
        assert_eq!(None, e.should_autocorrect("wprld"));
    }

    #[test]
    fn rank_offsets_lift_and_sink() {
        let mut e = engine();
        e.rank_offsets.insert("them".into(), 3);
        assert_eq!("them", e.suggest("th", &[], 5)[0]);
        e.rank_offsets.insert("them".into(), 0);
        e.rank_offsets.insert("the".into(), -3);
        assert_ne!("the", e.suggest("th", &[], 5)[0]);
    }

    #[test]
    fn a_rejected_correction_never_fires_again() {
        let mut e = engine();
        assert_eq!(Some("world".into()), e.should_autocorrect("wprld"));
        e.reject_correction("wprld", "world", true);
        assert_eq!(None, e.should_autocorrect("wprld"));
        assert_eq!(None, e.should_autocorrect("Wprld"));
        assert_eq!(Some("hello".into()), e.should_autocorrect("hallo"));
    }

    #[test]
    fn an_indirect_undo_of_a_non_word_is_not_believed() {
        let mut e = engine();
        e.reject_correction("teh", "the", false);
        assert_eq!(Penalty::None, e.stats.penalty("teh", "the"));
        e.reject_correction("them", "the", false);
        assert_eq!(Penalty::Penalized, e.stats.penalty("them", "the"));
    }

    #[test]
    fn rejecting_one_pair_leaves_the_others() {
        let mut e = with(&[("cat", 100), ("cut", 100)]);
        assert_eq!(Some("cat".into()), e.should_autocorrect("cst"));
        e.reject_correction("cst", "cat", true);
        assert_eq!(Some("cut".into()), e.should_autocorrect("cst"));
    }

    #[test]
    fn adaptive_confidence_tightens_the_gate() {
        let mut e = near_miss();
        e.autocorrect_confidence = 1.5;
        assert_eq!(Some("test".into()), e.should_autocorrect("tesr"));
        e.stats.confidence_multiplier = Some(4.0);
        assert_eq!(None, e.should_autocorrect("tesr"));
        e.adaptive_confidence = false;
        assert_eq!(Some("test".into()), e.should_autocorrect("tesr"));
    }

    #[test]
    fn short_of_the_bar_is_offered() {
        let mut e = near_miss();
        e.autocorrect_confidence = 3.0;
        let d = e.decide("tesr", 1.0);
        assert_eq!((None, Some("test".into())), (d.apply, d.offer));
        assert_eq!(Some("Test".into()), e.decide("Tesr", 1.0).offer);
        assert_eq!(Decision::default(), e.decide("test", 1.0));
        e.reject_correction("tesr", "test", true);
        assert_eq!(None, e.decide("tesr", 1.0).offer);
        let mut e = near_miss();
        e.autocorrect_confidence = 1.5;
        let d = e.decide("tesr", 1.0);
        assert_eq!((Some("test".into()), None), (d.apply, d.offer));
        e.autocorrect_confidence = 10.0;
        assert_eq!(None, e.decide("tesr", 1.0).offer);
    }

    #[test]
    fn both_sources_naming_a_fix_is_certain() {
        let mut e = with(&[("world", 50)]);
        e.lexicon.learn_word("world", 5, "");
        let d = e.decide("wprld", 1.0);
        assert_eq!(Some("world".into()), d.apply);
        assert_eq!(1.0, d.certainty);
        assert!(d.obviousness() > 0.0);
    }

    #[test]
    fn a_scraped_correction_is_less_certain() {
        let mut close = near_miss();
        close.autocorrect_confidence = 1.5;
        let mut clear = with(&[("test", 100), ("tear", 5)]);
        clear.autocorrect_confidence = 1.5;
        let (a, b) = (close.decide("tesr", 1.0), clear.decide("tesr", 1.0));
        assert_eq!(
            (Some("test".into()), Some("test".into())),
            (a.apply.clone(), b.apply.clone())
        );
        assert!(b.certainty > a.certainty);
    }

    #[test]
    fn the_same_slip_in_a_long_word_is_less_obvious() {
        let mut e = with(&[("cat", 100), ("accommodation", 100)]);
        let short = e.decide("cst", 1.0);
        let long = e.decide("accommodatiin", 1.0);
        assert_eq!(Some("cat".into()), short.apply);
        assert_eq!(Some("accommodation".into()), long.apply);
        assert!(long.complexity > short.complexity && long.obviousness() < short.obviousness());
    }

    #[test]
    fn all_caps_is_left_alone_when_asked() {
        assert_eq!(None, engine().should_autocorrect("WPRLD"));
        assert_eq!(Some("World".into()), engine().should_autocorrect("Wprld"));
        let mut e = engine();
        e.skip_all_caps = false;
        assert_eq!(Some("WORLD".into()), e.should_autocorrect("WPRLD"));
    }

    #[test]
    fn adjacent_slips_win_and_close_calls_stay_quiet() {
        assert_eq!(
            Some("cat".into()),
            with(&[("cat", 100), ("cut", 100)]).should_autocorrect("cst")
        );
        assert_eq!(
            None,
            with(&[("test", 100), ("tear", 95)]).should_autocorrect("tesr")
        );
        let mut e = with(&[("cat", 100), ("car", 90)]);
        e.lexicon.learn_word("cat", 1, "");
        assert_eq!(Some("cat".into()), e.should_autocorrect("caz"));
    }

    #[test]
    fn splits() {
        let mut e = with(&[("of", 9000), ("the", 10000), ("a", 9500), ("lot", 800)]);
        e.autocorrect_splits = true;
        assert_eq!("of the", e.suggest("ofthe", &[], 5)[0]);
        assert!(e.suggest("alot", &[], 5).contains(&"a lot".to_string()));
        assert_eq!("Of the", e.suggest("Ofthe", &[], 5)[0]);

        assert_eq!(None, engine().should_autocorrect("theworld"));
        let mut e = split_engine();
        assert_eq!(Some("the world".into()), e.should_autocorrect("theworld"));
        assert_eq!(Some("The world".into()), e.should_autocorrect("Theworld"));
        let d = e.decide("theworld", 1.0);
        assert_eq!((1.0, 0.0), (d.complexity, d.obviousness()));
        e.reject_correction("theworld", "the world", true);
        assert_eq!(None, e.should_autocorrect("theworld"));

        let mut e = engine();
        e.autocorrect_splits = true;
        assert_eq!(None, e.should_autocorrect("theworld"));
        assert!(e
            .suggest("theworld", &[], 5)
            .contains(&"the world".to_string()));
        e.lexicon.learn_bigram("the", "world");
        assert_eq!(None, e.should_autocorrect("theworld"));
        e.lexicon.learn_bigram("the", "world");
        assert_eq!(Some("the world".into()), e.should_autocorrect("theworld"));
        assert_eq!(Some("world".into()), e.should_autocorrect("wprld"));
        assert_eq!(None, e.should_autocorrect("hello"));

        let mut e = split_engine();
        e.lexicon.learn_word("theworld", 1, "");
        assert_eq!(None, e.should_autocorrect("theworld"));
    }

    #[test]
    fn a_split_never_ends_on_one_letter() {
        let mut e = with(&[
            ("that", 10_203_742),
            ("s", 110_199),
            ("a", 14_484_562),
            ("lot", 200_000),
        ]);
        e.autocorrect_splits = true;
        assert!(!e.suggest("thats", &[], 5).contains(&"that s".to_string()));
        assert_ne!(Some("that s".into()), e.should_autocorrect("thats"));
        assert!(e.suggest("alot", &[], 5).contains(&"a lot".to_string()));
    }

    #[test]
    fn digit_slips() {
        let mut e = engine();
        e.digit_slips = true;
        e.proximity = KeyProximity::qwerty(true);
        assert_eq!(Some("help".into()), e.should_autocorrect("h3lp"));
        assert_eq!(None, e.should_autocorrect("help3"));
        assert_eq!(None, e.should_autocorrect("h3lp4"));
        e.digit_slips = false;
        assert_eq!(None, e.should_autocorrect("h3lp"));
    }

    #[test]
    fn learned_words_personalize_and_appear_at_once() {
        let mut e = with(&[("test", 10)]);
        e.lexicon.learn_word("tezos", 1, "");
        e.lexicon.learn_word("tezos", 1, "");
        assert_eq!("tezos", e.suggest("te", &[], 5)[0]);
        let mut e = Predictor::new();
        assert!(e.suggest("wa", &[], 5).is_empty());
        e.lexicon.learn_word("wasi", 5, "");
        assert!(e.suggest("wa", &[], 5).contains(&"wasi".to_string()));
    }

    #[test]
    fn mismatched_language_tags_damp_learned_words() {
        let mut e = Predictor::new();
        e.lexicon.learn_word("wasi", 10, "bn_rom");
        e.lexicon.learn_word("wash", 5, "en");
        e.primary_language = "en".into();
        assert_eq!("wash", e.suggest("was", &[], 5)[0]);
        assert!(e.suggest("was", &[], 5).contains(&"wasi".to_string()));
        e.primary_language = "bn_rom".into();
        assert_eq!("wasi", e.suggest("was", &[], 5)[0]);
        let mut e = Predictor::new();
        e.lexicon.learn_word("wasi", 10, "");
        e.lexicon.learn_word("wash", 5, "en");
        e.primary_language = "en".into();
        assert_eq!("wasi", e.suggest("was", &[], 5)[0]);
    }

    #[test]
    fn next_words() {
        let mut e = Predictor::new();
        e.lexicon.learn_bigram("good", "morning");
        e.lexicon.learn_bigram("good", "morning");
        e.lexicon.learn_bigram("good", "night");
        assert_eq!(
            vec!["morning", "night"],
            e.suggest("", &["good"], 5)[..2].to_vec()
        );

        let mut e = Predictor::new();
        for _ in 0..3 {
            e.lexicon.learn_bigram("met", "yesterday");
        }
        assert!(e.suggest("", &["priya"], 5).is_empty());
        assert!(e
            .suggest("", &["priya", "met"], 5)
            .contains(&"yesterday".to_string()));

        let mut e = Predictor::new();
        e.lexicon.learn_bigram("someone", "else");
        for _ in 0..2 {
            e.lexicon.learn_skip1gram("can", "help");
        }
        for _ in 0..3 {
            e.lexicon.learn_skip2gram("how", "please");
        }
        assert_eq!(
            vec!["else", "help", "please"],
            e.suggest("", &["someone", "can", "how"], 5)
        );
        assert_eq!(vec!["else"], e.suggest("", &["someone"], 5));

        let mut e = Predictor::new();
        for _ in 0..5 {
            e.lexicon.learn_bigram("was", "here");
        }
        e.lexicon.learn_bigram("was", "going");
        e.lexicon.learn_trigram("i", "was", "going");
        assert_eq!("here", e.suggest("", &["was"], 5)[0]);
        assert_eq!("going", e.suggest("", &["was", "i"], 5)[0]);
    }

    #[test]
    fn seeds_cold_start_below_learned_pairs() {
        let mut e = Predictor {
            seeds: SeedBigrams::parse("good morning 100\ngood night 90\nthank you 100\n"),
            ..Predictor::default()
        };
        assert_eq!(vec!["morning", "night"], e.suggest("", &["good"], 5));
        assert_eq!(vec!["you"], e.suggest("", &["Thank"], 5));
        e.lexicon.learn_bigram("good", "game");
        assert_eq!(
            vec!["game", "morning", "night"],
            e.suggest("", &["good"], 5)
        );
        let s = SeedBigrams::parse("<s> i 10\n");
        assert_eq!(vec!["i"], s.next_words(SENTENCE_START));
    }

    #[test]
    fn context_lifts_completions_within_a_cap() {
        let mut e = with(&[("world", 90), ("words", 100)]);
        for _ in 0..5 {
            e.lexicon.learn_bigram("hello", "world");
        }
        assert_eq!("world", e.suggest("wor", &["hello"], 5)[0]);
        assert_eq!("words", e.suggest("wor", &[], 5)[0]);
        let mut e = with(&[("world", 100), ("words", 500)]);
        for _ in 0..10_000 {
            e.lexicon.learn_bigram("hello", "world");
        }
        assert_eq!("words", e.suggest("wor", &["hello"], 5)[0]);
    }

    #[test]
    fn context_rerank_lifts_the_word_the_phrase_wants() {
        let mut e = with(&[("world", 100), ("words", 500)]);
        // "hello <anything> world": a skip-gram, which only the rerank reads.
        e.lexicon.add_skip1gram("hello", "world", 20);
        assert_eq!("words", e.suggest("wor", &["big", "hello"], 5)[0]);
        e.context_rerank = true;
        assert_eq!("world", e.suggest("wor", &["big", "hello"], 5)[0]);
        // No evidence, no reorder; and never at a sentence start.
        assert_eq!("words", e.suggest("wor", &["big", "goodbye"], 5)[0]);
        assert_eq!("words", e.suggest("wor", &[SENTENCE_START, "hello"], 5)[0]);
    }

    #[test]
    fn app_recency_nudges_a_near_tie() {
        let mut e = with(&[("world", 95), ("words", 100)]);
        assert_eq!("words", e.suggest("wor", &[], 5)[0]);
        e.context_words.insert("world".into());
        assert_eq!("world", e.suggest("wor", &[], 5)[0]);
        let mut e = with(&[("world", 50), ("words", 100)]);
        e.context_words.insert("world".into());
        assert_eq!("words", e.suggest("wor", &[], 5)[0]);
    }

    #[test]
    fn join_candidate_guards() {
        let mut e = with(&[
            ("some", 5000),
            ("thing", 3000),
            ("something", 8000),
            ("a", 90000),
            ("nd", 60),
            ("and", 50000),
        ]);
        assert_eq!(
            Some("something".into()),
            e.join_candidate(Some("some"), "thing")
        );
        assert_eq!(None, e.join_candidate(None, "thing"));
        assert_eq!(None, e.join_candidate(Some(SENTENCE_START), "thing"));
        assert_eq!(None, e.join_candidate(Some("some"), "t"));
        assert_eq!(None, e.join_candidate(Some("some"), "thing1"));
        assert_eq!(None, e.join_candidate(Some("thing"), "some"));
        e.blacklist.insert("something".into());
        assert_eq!(None, e.join_candidate(Some("some"), "thing"));
    }

    #[test]
    fn secondary_lists_suggest_and_protect() {
        let mut e = engine();
        e.english_sources = false;
        e.secondaries
            .push(("es".into(), WordList::of(&[("gato", 1), ("gata", 1)])));
        assert!(e.suggest("gat", &[], 5).contains(&"gato".to_string()));
        let mut e = engine();
        e.secondaries
            .push(("es".into(), WordList::of(&[("worl", 1)])));
        assert_eq!(None, e.should_autocorrect("worl"));
        assert_eq!(Some("world".into()), engine().should_autocorrect("worl"));
    }

    #[test]
    fn list_capitals_and_case_memory_spell_the_strip() {
        let mut e = with(&[("haus", 100), ("hauses", 10)]);
        e.capitals = Dict::open(wmdict::write(&PackedTrie::of(&[("haus", 1)])));
        assert_eq!("Haus", e.suggest("hau", &[], 5)[0]);
        e.lexicon.learn_word("hauses", 1, "");
        e.lexicon.set_case("hauses", "HAUSES");
        assert!(e.suggest("hause", &[], 5).contains(&"HAUSES".to_string()));
        e.offensive.insert("haus".into());
        assert!(!e.suggest("hau", &[], 5).contains(&"Haus".to_string()));
    }

    #[test]
    fn stored_pairs_load_with_their_counts() {
        let mut e = Predictor::new();
        e.lexicon.add_bigram("good", "night", 3);
        e.lexicon.add_bigram("good", "morning", 2);
        e.lexicon.add_trigram("very", "good", "morning", 2);
        assert_eq!(vec!["night", "morning"], e.suggest("", &["good"], 5));
        assert_eq!("morning", e.suggest("", &["good", "very"], 5)[0]);
    }

    #[test]
    fn verb_forms_the_list_left_out_are_words() {
        let words = [
            "correct",
            "corrected",
            "fix",
            "fixed",
            "stop",
            "stopped",
            "the",
            "admit",
            "admitted",
            "admited",
        ];
        let known = |w: &str| words.contains(&w);
        assert!(is_verb_form("corrects", &known));
        assert!(is_verb_form("correcting", &known));
        assert!(is_verb_form("fixes", &known));
        assert!(!is_verb_form("fixs", &known));
        assert!(!is_verb_form("thes", &known));
        assert!(!is_verb_form("stoped", &known));
        assert!(is_verb_form("stopping", &known));
    }

    #[test]
    fn chat_speak_rises_in_chats_and_sinks_in_email() {
        let mut e = with(&[("lol", 50), ("lollipop", 60), ("lots", 55)]);
        assert_ne!("lol", e.suggest("lo", &[], 3)[0]);
        e.register = Register::Casual;
        assert_eq!("lol", e.suggest("lo", &[], 3)[0]);
        e.register = Register::Formal;
        assert_eq!("lol", e.suggest("lo", &[], 3)[2]);
    }

    #[test]
    fn a_fix_taught_twice_is_made_and_once_is_offered() {
        let mut e = with(&[("the", 100), ("thy", 1)]);
        e.memory
            .teach("thw", "thy", "qwerty", correction_memory::Kind::PairOnly);
        let d = e.decide("thw", 1.0);
        assert_eq!((None, Some("thy".into())), (d.apply, d.offer));
        e.memory
            .teach("thw", "thy", "qwerty", correction_memory::Kind::PairOnly);
        let d = e.decide("Thw", 1.0);
        assert_eq!(Some("Thy".into()), d.apply);
        assert!(d.certainty < 1.0);
        e.reject_correction("thw", "thy", true);
        assert_ne!(Some("thy".into()), e.decide("thw", 1.0).apply);
        assert_eq!(None, e.memory.fix_for("thw").filter(|t| t.count >= 2));
    }

    #[test]
    fn a_taught_fix_of_a_real_word_needs_the_context() {
        let mut e = with(&[("form", 50), ("from", 100), ("came", 80)]);
        for _ in 0..3 {
            e.memory
                .teach("form", "from", "qwerty", correction_memory::Kind::PairOnly);
        }
        assert_eq!(None, e.decide_after("form", 1.0, Some("came")).apply);
        for _ in 0..3 {
            e.lexicon.learn_bigram("came", "from");
        }
        assert_eq!(
            Some("from".into()),
            e.decide_after("form", 1.0, Some("came")).apply
        );
        assert_eq!(None, e.decide("form", 1.0).apply);
    }

    #[test]
    fn missing_apostrophes() {
        let mut e = with(&[("that's", 80), ("thats", 90), ("its", 100), ("it's", 90)]);
        e.primary_language = "en".into();
        assert_eq!(Some("that's".into()), e.elide("thats"));
        assert_eq!(Some("That's".into()), e.elide("Thats"));
        assert_eq!("that's", e.suggest("thats", &[], 3)[0]);
        assert_eq!(None, e.elide("its"), "a word of its own is only offered");
        let strip = e.suggest("its", &[], 3);
        assert_eq!(vec!["its", "it's"], strip[..2].to_vec());
        e.reject_correction("thats", "that's", true);
        assert_eq!(None, e.elide("thats"));
        e.apostrophe_fixes = false;
        assert_eq!(None, e.elide("dont"));
        e.apostrophe_fixes = true;
        e.primary_language = "fr".into();
        assert_eq!(None, e.elide("dont"));
    }

    #[test]
    fn the_word_after_settles_a_confusable() {
        let mut e = with(&[("their", 100), ("they're", 50)]);
        assert_eq!(None, e.advise("their", "going"));
        for _ in 0..3 {
            e.lexicon.learn_bigram("they're", "going");
        }
        assert_eq!(None, e.advise("their", "going"), "4 to 1 is not enough");
        for _ in 0..4 {
            e.lexicon.learn_bigram("they're", "going");
        }
        assert_eq!(Some("They're".into()), e.advise("Their", "going"));
        assert_eq!(None, e.advise("hello", "going"));
    }

    #[test]
    fn typing_rhythm() {
        let mut t = KeystrokeTiming::default();
        assert_eq!(1.0, t.multiplier(1.0));
        for at in [1000, 1080, 1160, 1240] {
            t.keystroke(at);
        }
        assert_eq!(0.5, t.multiplier(1.0));
        assert_eq!(1.0, t.multiplier(0.0));
        t.reset();
        for at in [1000, 1600, 2200] {
            t.keystroke(at);
        }
        assert_eq!(1.25, t.multiplier(0.5));
        t.reset();
        for at in [1000, 5000, 5100] {
            t.keystroke(at);
        }
        assert_eq!(1.0, t.multiplier(1.0), "a pause is not rhythm");
    }

    #[test]
    fn the_field_leans_towards_the_language_it_is_written_in() {
        let mut e = with(&[("tomake", 1), ("tomato", 3)]);
        e.primary_language = "en".into();
        e.secondaries.push((
            "bn_rom".into(),
            list(&[("tomake", 50), ("ami", 80), ("bhalo", 60)]).unwrap(),
        ));
        e.field_detection_shift = crate::language_mix::FIELD_SHIFT_AGGRESSIVE;
        assert_eq!("en", e.detected_language());
        let before = e.suggest("toma", &[], 2);
        e.seed_field(&["ami", "bhalo", "ami"], None);
        assert_eq!("bn_rom", e.detected_language());
        let after = e.suggest("toma", &[], 2);
        assert_eq!("tomake", after[0], "{before:?} -> {after:?}");
        e.field_detection_shift = 0.0;
        assert_eq!("en", e.detected_language());
    }
}
