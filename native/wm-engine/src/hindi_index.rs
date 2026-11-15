//! Reverse-phonetic lookup from romanized Hindi to dictionary words: what the
//! Bengali index is to Avro, and the half of Hindi phonetic typing that
//! knows Hindi. A port of `HindiPhoneticIndex.kt`.
//!
//! Both the typed spelling and every word of the list are folded to a key in
//! which aspiration, the dental/retroflex split, the virama, every inner
//! "a", vowel length and doubled consonants are gone, and words sharing a
//! key are siblings ranked by frequency. Each entry keeps a detail mask,
//! position for position with its key, of what the fold discarded, and a
//! sibling that contradicts what was typed has its frequency divided,
//! lopsidedly: typing a detail and not getting it is worse than leaving one
//! out and getting it anyway.
//!
//! Two habits are too common to treat as mismatches, so a word is filed
//! under a second key for each: ड़ typed as "r" as readily as "d", and
//! nasalisation left off altogether.

use std::collections::HashMap;

use crate::phonetic_index::PhoneticIndex;

const SCALE: i64 = 1024;
const MAX_HANDICAP: i64 = 1 << 20;
/// Typed a detail the word does not have: a deliberate request, refused.
const TYPED_NOT_FOUND: i64 = 20;
/// Left a detail out that the word has: ordinary casual spelling.
const FOUND_NOT_TYPED: i64 = 2;
const LONG_TYPED_SINGLE: i64 = 4;
/// A closing "a" that the word says and does not write.
const SPOKEN_FINAL_A: i64 = 2;
/// The word has आ where not even a single "a" was typed.
const LONG_NOT_HINTED: i64 = 8;
const SCHWA_IN_CONJUNCT: i64 = 3;
const NASAL_DROPPED: i64 = 3;

// One byte of detail per key char: an aspiration bit, and what followed the
// consonant (or what kind of vowel this is).
const DETAIL_BASE: u8 = b'0';
const ASPIRATED: u32 = 1;
const TAIL_MASK: u32 = 7 << 1;
const TAIL_NONE: u32 = 0;
const TAIL_SHORT: u32 = 1 << 1;
const TAIL_LONG: u32 = 2 << 1;
const TAIL_JOINED: u32 = 3 << 1;
const TAIL_DIPHTHONG: u32 = 4 << 1;
/// The consonant was written twice: पत्ता against पता.
const DOUBLED: u32 = 1 << 4;

const VIRAMA: char = '\u{94D}';
const NUKTA: char = '\u{93C}';
const ANUSVARA: char = '\u{902}';
const CANDRABINDU: char = '\u{901}';

/// Devanagari letters and signs, and the joiners: not its digits, not the danda.
fn is_word_char(c: char) -> bool {
    let u = c as u32;
    (0x0900..=0x0963).contains(&u)
        || (0x0970..=0x097F).contains(&u)
        || c == '\u{200D}'
        || c == '\u{200C}'
}

/// A canonical key and, byte for byte, the detail the fold threw away.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Folded {
    pub key: String,
    pub detail: Vec<u8>,
}

#[derive(Clone)]
enum Slot {
    /// `flap`: ड़ / ढ़, which is `d` under one key and `r` under the other.
    Consonant {
        key: char,
        aspirated: bool,
        flap: bool,
        joined: bool,
    },
    /// Any "a": inherent-made-explicit (roman only), अ, आ or ा.
    AVowel { long: bool },
    /// `independent`: a letter of its own, not a matra on the consonant before.
    Vowel {
        key: char,
        long: bool,
        diphthong: bool,
        independent: bool,
    },
    /// `droppable`: ँ anywhere, or ं closing the word.
    Nasal { droppable: bool },
}

impl Slot {
    fn consonant(key: char, aspirated: bool) -> Slot {
        Slot::Consonant {
            key,
            aspirated,
            flap: false,
            joined: false,
        }
    }

    fn vowel(key: char, long: bool, diphthong: bool, independent: bool) -> Slot {
        Slot::Vowel {
            key,
            long,
            diphthong,
            independent,
        }
    }

    fn is_consonant(&self) -> bool {
        matches!(self, Slot::Consonant { .. })
    }
}

fn fold(all: &[Slot], drop_nasals: bool, flap_as_r: bool) -> Folded {
    let slots: Vec<&Slot> = all
        .iter()
        .filter(|s| !(drop_nasals && matches!(s, Slot::Nasal { droppable: true })))
        .collect();
    let mut key = String::with_capacity(slots.len());
    let mut detail: Vec<u8> = Vec::with_capacity(slots.len());
    let tail = |detail: &mut Vec<u8>, bits: u32| {
        if let Some(last) = detail.last_mut() {
            let code = (*last - DETAIL_BASE) as u32;
            *last = DETAIL_BASE + ((code & !TAIL_MASK) | bits) as u8;
        }
    };
    let emit = |key: &mut String, detail: &mut Vec<u8>, k: char, bits: u32| {
        key.push(k);
        detail.push(DETAIL_BASE + bits as u8);
    };
    let mut i = 0;
    while i < slots.len() {
        let slot = slots[i];
        let next = slots.get(i + 1).copied();
        match slot {
            Slot::Consonant {
                key: k0,
                aspirated,
                flap,
                joined,
            } => {
                let mut k = if *flap && flap_as_r { 'r' } else { *k0 };
                if k == 'm' {
                    if let Some(Slot::Consonant { key: nk, .. }) = next {
                        if *nk == 'p' || *nk == 'b' {
                            k = 'n';
                        }
                    }
                }
                let bits = (if *aspirated { ASPIRATED } else { 0 })
                    | (if *joined { TAIL_JOINED } else { TAIL_NONE });
                if key.ends_with(k) && i > 0 && slots[i - 1].is_consonant() {
                    // A doubled consonant is one: keep whichever half was
                    // aspirated, and what follows the second.
                    let last = detail.last_mut().unwrap();
                    let before = (*last - DETAIL_BASE) as u32;
                    *last = DETAIL_BASE + ((before & ASPIRATED) | bits | DOUBLED) as u8;
                } else {
                    emit(&mut key, &mut detail, k, bits);
                }
            }
            Slot::Nasal { .. } => emit(&mut key, &mut detail, 'n', TAIL_NONE),
            Slot::AVowel { long } => {
                // An "a" running into an independent इ / उ is ऐ / औ.
                if let Some(Slot::Vowel {
                    key: nk,
                    independent: true,
                    ..
                }) = next
                {
                    if *nk == 'i' || *nk == 'u' {
                        emit(
                            &mut key,
                            &mut detail,
                            if *nk == 'i' { 'e' } else { 'o' },
                            TAIL_DIPHTHONG,
                        );
                        i += 2;
                        continue;
                    }
                }
                if key.is_empty() || i == slots.len() - 1 {
                    emit(
                        &mut key,
                        &mut detail,
                        'a',
                        if *long { TAIL_LONG } else { TAIL_NONE },
                    );
                } else {
                    tail(&mut detail, if *long { TAIL_LONG } else { TAIL_SHORT });
                }
            }
            Slot::Vowel {
                key: vk,
                long,
                diphthong,
                independent,
            } => {
                let glides = *independent
                    && (*vk == 'i' || *vk == 'u')
                    && i > 0
                    && slots[i - 1].is_consonant();
                if glides {
                    // कई: the inherent vowel running into ई.
                    emit(
                        &mut key,
                        &mut detail,
                        if *vk == 'i' { 'e' } else { 'o' },
                        TAIL_DIPHTHONG,
                    );
                } else if *diphthong {
                    emit(&mut key, &mut detail, *vk, TAIL_DIPHTHONG);
                } else {
                    emit(
                        &mut key,
                        &mut detail,
                        *vk,
                        if *long { TAIL_LONG } else { TAIL_NONE },
                    );
                }
            }
        }
        i += 1;
    }
    Folded { key, detail }
}

fn consonant_of(c: char) -> Option<(char, bool)> {
    Some(match c {
        'क' => ('k', false),
        'ख' => ('k', true),
        'ग' => ('g', false),
        'घ' => ('g', true),
        'च' => ('c', false),
        'छ' => ('c', true),
        'ज' => ('j', false),
        'झ' => ('j', true),
        'ट' | 'त' => ('t', false),
        'ठ' | 'थ' => ('t', true),
        'ड' | 'द' => ('d', false),
        'ढ' | 'ध' => ('d', true),
        'ण' | 'न' | 'ङ' | 'ञ' | '\u{929}' => ('n', false),
        'प' => ('p', false),
        'फ' => ('p', true),
        'ब' => ('b', false),
        'भ' => ('b', true),
        'म' => ('m', false),
        'य' => ('y', false),
        'र' | '\u{931}' => ('r', false),
        'ल' | 'ळ' | '\u{934}' => ('l', false),
        'व' => ('v', false),
        'श' | 'ष' | 'स' => ('s', false),
        'ह' => ('h', false),
        _ => return None,
    })
}

/// Precomposed nukta letters (U+0958–U+095F), should a list carry them.
fn precomposed_nukta(c: char) -> Option<&'static str> {
    Some(match c {
        '\u{958}' => "क",
        '\u{959}' => "ख",
        '\u{95A}' => "ग",
        '\u{95B}' => "ज",
        '\u{95C}' => "ड\u{93C}",
        '\u{95D}' => "ढ\u{93C}",
        '\u{95E}' => "फ",
        '\u{95F}' => "य",
        _ => return None,
    })
}

fn devanagari_slots(raw: &str) -> Vec<Slot> {
    let word: Vec<char> = if raw.chars().any(|c| precomposed_nukta(c).is_some()) {
        raw.chars()
            .flat_map(|c| match precomposed_nukta(c) {
                Some(s) => s.chars().collect::<Vec<_>>(),
                None => vec![c],
            })
            .collect()
    } else {
        raw.chars().collect()
    };
    let mut slots: Vec<Slot> = Vec::with_capacity(word.len());
    let mut i = 0;
    while i < word.len() {
        let c = word[i];
        if let Some((key, aspirated)) = consonant_of(c) {
            let flap = (c == 'ड' || c == 'ढ') && word.get(i + 1) == Some(&NUKTA);
            // ज्ञ is said, and so typed, "gy".
            if c == 'ज' && word.get(i + 1) == Some(&VIRAMA) && word.get(i + 2) == Some(&'ञ') {
                slots.push(Slot::Consonant {
                    key: 'g',
                    aspirated: false,
                    flap: false,
                    joined: true,
                });
                slots.push(Slot::consonant('y', false));
                i += 3;
                continue;
            }
            slots.push(Slot::Consonant {
                key,
                aspirated,
                flap,
                joined: false,
            });
        } else {
            match c {
                VIRAMA => {
                    if let Some(Slot::Consonant { joined, .. }) = slots.last_mut() {
                        *joined = true;
                    }
                }
                'अ' => slots.push(Slot::AVowel { long: false }),
                'आ' | '\u{93E}' => slots.push(Slot::AVowel { long: true }),
                'इ' | 'ई' => slots.push(Slot::vowel('i', c == 'ई', false, true)),
                '\u{93F}' | '\u{940}' => slots.push(Slot::vowel('i', c == '\u{940}', false, false)),
                'उ' | 'ऊ' => slots.push(Slot::vowel('u', c == 'ऊ', false, true)),
                '\u{941}' | '\u{942}' => slots.push(Slot::vowel('u', c == '\u{942}', false, false)),
                'ए' | '\u{947}' => slots.push(Slot::vowel('e', false, false, c == 'ए')),
                '\u{90E}' | '\u{946}' => slots.push(Slot::vowel('e', false, false, c == '\u{90E}')),
                '\u{912}' | '\u{94A}' => slots.push(Slot::vowel('o', false, false, c == '\u{912}')),
                'ऐ' | '\u{948}' => slots.push(Slot::vowel('e', false, true, c == 'ऐ')),
                'ओ' | '\u{94B}' => slots.push(Slot::vowel('o', false, false, c == 'ओ')),
                'औ' | '\u{94C}' => slots.push(Slot::vowel('o', false, true, c == 'औ')),
                'ऋ' | '\u{943}' => {
                    slots.push(Slot::consonant('r', false));
                    slots.push(Slot::vowel('i', false, false, false));
                }
                ANUSVARA => slots.push(Slot::Nasal { droppable: false }),
                CANDRABINDU => slots.push(Slot::Nasal { droppable: true }),
                // nukta, visarga, ZWJ / ZWNJ and anything unmapped fold away
                _ => {}
            }
        }
        i += 1;
    }
    // An anusvara that closes the word is nasalisation, not a consonant, and
    // goes missing from casual spelling as readily as ँ does.
    if let Some(Slot::Nasal { droppable }) = slots.last_mut() {
        *droppable = true;
    }
    slots
}

const ASPIRABLE: &str = "kgcjtdpb";

fn roman_slots(input: &str) -> Vec<Slot> {
    let chars: Vec<char> = input.chars().collect();
    let mut slots: Vec<Slot> = Vec::with_capacity(chars.len());
    let mut i = 0;
    while i < chars.len() {
        let raw = chars[i];
        let c = raw.to_ascii_lowercase();
        let next = chars.get(i + 1).map(|n| n.to_ascii_lowercase());
        let after_a = matches!(slots.last(), Some(Slot::AVowel { .. }));
        match c {
            _ if raw == 'M' => slots.push(Slot::Nasal { droppable: false }),
            _ if raw == 'H' => {}
            'a' => {
                let mut j = i;
                while j < chars.len() && chars[j].eq_ignore_ascii_case(&'a') {
                    j += 1;
                }
                slots.push(Slot::AVowel {
                    long: j - i > 1 || raw == 'A',
                });
                i = j;
                continue;
            }
            'i' => {
                let doubled = next == Some('i');
                // Straight after an "a" it is the second half of "ai".
                slots.push(Slot::vowel('i', doubled || raw == 'I', false, after_a));
                if doubled {
                    i += 1;
                }
            }
            'u' => {
                let doubled = next == Some('u');
                slots.push(Slot::vowel('u', doubled || raw == 'U', false, after_a));
                if doubled {
                    i += 1;
                }
            }
            'e' => match next {
                Some('e') => {
                    slots.push(Slot::vowel('i', true, false, false));
                    i += 1;
                }
                // "mein" is में: the "i" is how the vowel is spelled, not a second one.
                Some('i') => {
                    slots.push(Slot::vowel('e', false, false, false));
                    i += 1;
                }
                _ => slots.push(Slot::vowel('e', false, false, false)),
            },
            'o' => match next {
                Some('o') => {
                    slots.push(Slot::vowel('u', true, false, false));
                    i += 1;
                }
                Some('u') => {
                    slots.push(Slot::vowel('o', false, true, false));
                    i += 1;
                }
                _ => slots.push(Slot::vowel('o', false, false, false)),
            },
            'c' if next == Some('h') => {
                // "ch" is च and "chh" is छ; plenty of people write छ as "ch"
                // too, which the lopsided handicap already forgives.
                let aspirated = chars.get(i + 2).map(|n| n.to_ascii_lowercase()) == Some('h');
                slots.push(Slot::consonant('c', aspirated));
                i += if aspirated { 3 } else { 2 };
                continue;
            }
            's' if next == Some('h') => {
                slots.push(Slot::consonant('s', false));
                i += 1;
            }
            _ if next == Some('h') && ASPIRABLE.contains(c) => {
                slots.push(Slot::consonant(c, true));
                i += 1;
            }
            'f' => slots.push(Slot::consonant('p', true)),
            'q' => slots.push(Slot::consonant('k', false)),
            'z' => slots.push(Slot::consonant('j', false)),
            'w' => slots.push(Slot::consonant('v', false)),
            'x' => {
                slots.push(Slot::consonant('k', false));
                slots.push(Slot::consonant('s', false));
            }
            'a'..='z' => slots.push(Slot::consonant(c, false)),
            // digits, punctuation and other scripts fold away
            _ => {}
        }
        i += 1;
    }
    slots
}

/// Folds a Devanagari word to its canonical phonetic key.
pub fn fold_devanagari(word: &str) -> String {
    fold(&devanagari_slots(word), false, false).key
}

/// Folds romanized Hindi typing to the same canonical key.
pub fn fold_roman(input: &str) -> Folded {
    fold(&roman_slots(input), false, false)
}

struct Entry {
    word: String,
    frequency: i32,
    detail: Vec<u8>,
    /// Filed here with its nasalisation dropped, which costs it a little.
    nasal_dropped: bool,
}

pub struct HindiPhoneticIndex {
    by_key: HashMap<String, Vec<Entry>>,
    freq_by_word: HashMap<String, i32>,
    max_frequency: i32,
    /// Whether a typed closing "a" may be the inherent vowel of any word,
    /// not only of one closing on य, र or व: how Nepali and Sanskrit are
    /// typed ("xa" छ, "rAma" राम). See [`HindiPhoneticIndex::with_typed_schwa`].
    typed_schwa: bool,
}

impl HindiPhoneticIndex {
    pub fn new(entries: Vec<(String, i32)>) -> HindiPhoneticIndex {
        let mut by_key: HashMap<String, Vec<Entry>> = HashMap::new();
        let mut freq_by_word: HashMap<String, i32> = HashMap::new();
        for (word, frequency) in entries {
            // Scraped lists carry tokens that are not words (है। with its
            // danda attached), and the fold would file them under the real
            // word's key.
            if !word.chars().all(is_word_char) {
                continue;
            }
            let slots = devanagari_slots(&word);
            if slots.is_empty() {
                continue;
            }
            let mut seen: Vec<String> = Vec::with_capacity(4);
            for drop_nasals in [false, true] {
                for flap_as_r in [false, true] {
                    let folded = fold(&slots, drop_nasals, flap_as_r);
                    if folded.key.is_empty() || seen.contains(&folded.key) {
                        continue;
                    }
                    seen.push(folded.key.clone());
                    by_key.entry(folded.key).or_default().push(Entry {
                        word: word.clone(),
                        frequency,
                        detail: folded.detail,
                        nasal_dropped: drop_nasals,
                    });
                }
            }
            let f = freq_by_word.entry(word).or_insert(frequency);
            *f = (*f).max(frequency);
        }
        for list in by_key.values_mut() {
            list.sort_by_key(|e| std::cmp::Reverse(e.frequency));
        }
        let max_frequency = freq_by_word.values().copied().max().unwrap_or(0);
        HindiPhoneticIndex {
            by_key,
            freq_by_word,
            max_frequency,
            typed_schwa: false,
        }
    }

    /// The same index, reading a typed closing "a" as the inherent vowel of
    /// any word when `on`: a language whose romanization spells the vowel it
    /// does not write. Such a word still ranks below one whose key has the
    /// "a" in it, by the spoken-final-a handicap.
    pub fn with_typed_schwa(mut self, on: bool) -> HindiPhoneticIndex {
        self.typed_schwa = on;
        self
    }

    /// Words a closing "a" was typed for that the key has no closing "a" in:
    /// "mitra" for मित्र, "satya" for सत्य. Only a conjunct closing on य, र
    /// or व is like that.
    fn spoken_final_a(&self, typed: &Folded) -> Vec<&Entry> {
        let key = &typed.key;
        let shortest = if self.typed_schwa { 2 } else { 3 };
        if key.len() < shortest || !key.ends_with('a') {
            return Vec::new();
        }
        if ((typed.detail[typed.detail.len() - 1] - DETAIL_BASE) as u32) & TAIL_MASK == TAIL_LONG {
            return Vec::new();
        }
        self.by_key
            .get(&key[..key.len() - 1])
            .map(|bucket| {
                bucket
                    .iter()
                    .filter(|entry| {
                        if self.typed_schwa {
                            return true;
                        }
                        let chars: Vec<char> = entry.word.chars().collect();
                        chars.len() >= 3
                            && chars[chars.len() - 2] == VIRAMA
                            && matches!(chars[chars.len() - 1], 'य' | 'र' | 'व')
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    fn handicap(entry: &Entry, typed: &[u8]) -> i64 {
        let mut divisor = if entry.nasal_dropped {
            NASAL_DROPPED
        } else {
            1
        };
        // Same key means same length, so the two masks line up.
        for i in 0..typed.len() {
            divisor *= mismatch(
                (typed[i] - DETAIL_BASE) as u32,
                (entry.detail[i] - DETAIL_BASE) as u32,
                i == typed.len() - 1,
            );
            if divisor > MAX_HANDICAP {
                return MAX_HANDICAP;
            }
        }
        divisor
    }
}

fn mismatch(typed: u32, word: u32, last: bool) -> i64 {
    let mut divisor = 1i64;
    let typed_aspirated = typed & ASPIRATED != 0;
    let word_aspirated = word & ASPIRATED != 0;
    if typed_aspirated != word_aspirated {
        divisor *= if typed_aspirated {
            TYPED_NOT_FOUND
        } else {
            FOUND_NOT_TYPED
        };
    }
    let typed_doubled = typed & DOUBLED != 0;
    if typed_doubled != (word & DOUBLED != 0) {
        divisor *= if typed_doubled {
            TYPED_NOT_FOUND
        } else {
            FOUND_NOT_TYPED
        };
    }
    let t = typed & TAIL_MASK;
    let w = word & TAIL_MASK;
    if t != w {
        divisor *= if t == TAIL_LONG || t == TAIL_DIPHTHONG {
            // A long vowel or a diphthong that was spelled out and is not there.
            TYPED_NOT_FOUND
        } else if w == TAIL_LONG && last {
            // Length left off the last vowel of a word is how everyone writes "ladki".
            1
        } else if w == TAIL_LONG && t == TAIL_NONE {
            LONG_NOT_HINTED
        } else if w == TAIL_LONG && t == TAIL_SHORT {
            // One "a" typed, आ found: "namak" is नमक before it is नामक.
            LONG_TYPED_SINGLE
        } else if w == TAIL_LONG || w == TAIL_DIPHTHONG {
            FOUND_NOT_TYPED
        } else if t == TAIL_SHORT && w == TAIL_JOINED {
            // An "a" typed where the word has a conjunct: "dharam" for धर्म.
            SCHWA_IN_CONJUNCT
        } else {
            1
        };
    }
    divisor
}

impl PhoneticIndex for HindiPhoneticIndex {
    fn lookup(&self, input: &str) -> Vec<String> {
        let typed = fold_roman(input);
        let bucket: &[Entry] = self
            .by_key
            .get(&typed.key)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        let spoken = self.spoken_final_a(&typed);
        if spoken.is_empty() {
            if bucket.len() <= 1 {
                return bucket.iter().map(|e| e.word.clone()).collect();
            }
            let mut sorted: Vec<&Entry> = bucket.iter().collect();
            sorted.sort_by_key(|e| {
                std::cmp::Reverse(e.frequency as i64 * SCALE / Self::handicap(e, &typed.detail))
            });
            return sorted.into_iter().map(|e| e.word.clone()).collect();
        }
        let bare = &typed.detail[..typed.detail.len() - 1];
        let mut scored: Vec<(&str, i64)> = bucket
            .iter()
            .map(|e| {
                (
                    e.word.as_str(),
                    e.frequency as i64 * SCALE / Self::handicap(e, &typed.detail),
                )
            })
            .chain(spoken.iter().map(|e| {
                (
                    e.word.as_str(),
                    e.frequency as i64 * SCALE / (Self::handicap(e, bare) * SPOKEN_FINAL_A),
                )
            }))
            .collect();
        scored.sort_by_key(|&(_, score)| std::cmp::Reverse(score));
        let mut out: Vec<String> = Vec::new();
        for (word, _) in scored {
            if !out.iter().any(|w| w == word) {
                out.push(word.to_string());
            }
        }
        out
    }

    fn frequency_of(&self, word: &str) -> i32 {
        self.freq_by_word.get(word).copied().unwrap_or(0)
    }

    fn match_strength(&self, input: &str) -> i32 {
        let typed = fold_roman(input);
        let direct = self
            .by_key
            .get(&typed.key)
            .and_then(|b| {
                b.iter()
                    .map(|e| e.frequency as i64 / Self::handicap(e, &typed.detail))
                    .max()
            })
            .unwrap_or(0);
        let spoken = self.spoken_final_a(&typed);
        if spoken.is_empty() {
            return direct as i32;
        }
        let bare = &typed.detail[..typed.detail.len() - 1];
        let best = spoken
            .iter()
            .map(|e| e.frequency as i64 / (Self::handicap(e, bare) * SPOKEN_FINAL_A))
            .max()
            .unwrap_or(0);
        direct.max(best) as i32
    }

    fn max_frequency(&self) -> i32 {
        self.max_frequency
    }

    fn is_empty(&self) -> bool {
        self.by_key.is_empty()
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `HindiPhoneticIndexTest.kt`.

    use super::*;

    // ल + ड + nukta + क + ा, the decomposed spelling word lists carry.
    const LADKA: &str = "ल\u{921}\u{93C}का";
    const ZINDAGI: &str = "\u{91C}\u{93C}िंदगी";

    fn entries(pairs: &[(&str, i32)]) -> Vec<(String, i32)> {
        pairs.iter().map(|(w, f)| (w.to_string(), *f)).collect()
    }

    fn index() -> HindiPhoneticIndex {
        HindiPhoneticIndex::new(entries(&[
            ("करना", 900),
            ("कम", 800),
            ("काम", 700),
            ("नहीं", 5000),
            ("हाँ", 600),
            ("है", 9000),
            ("हैं", 4000),
            ("में", 8000),
            ("मैं", 3000),
            (LADKA, 300),
            ("अच्छा", 1200),
            ("ठीक", 900),
            ("तक", 1000),
            ("थक", 100),
            ("कई", 700),
            ("भाई", 800),
            ("और", 9000),
            ("कौन", 1500),
            ("कहाँ", 900),
            ("कहा", 2000),
            ("हिंदी", 500),
            ("हिन्दी", 400),
            (ZINDAGI, 300),
            ("जिंदगी", 200),
            ("पानी", 700),
            ("पता", 900),
            ("पत्ता", 100),
            ("ज्ञान", 200),
            ("कृपा", 100),
            ("लंबा", 100),
            ("जाओ", 300),
            ("कर", 5000),
            ("रहा", 3000),
            ("कुछ", 2500),
        ]))
    }

    fn top(input: &str) -> Option<String> {
        index().lookup(input).into_iter().next()
    }

    fn s(v: &str) -> Option<String> {
        Some(v.to_string())
    }

    #[test]
    fn a_dropped_schwa_and_a_conjunct_look_alike() {
        assert_eq!(s("करना"), top("karna"));
        assert_eq!("krna", fold_devanagari("करना"));
        assert_eq!("krna", fold_roman("karna").key);
    }

    #[test]
    fn a_lone_a_finds_both_lengths_and_a_doubled_one_asks_for_long() {
        assert_eq!(vec!["कम", "काम"], index().lookup("kam"));
        assert_eq!(s("काम"), top("kaam"));
        assert_eq!(s("पानी"), top("pani"));
    }

    #[test]
    fn nasalisation_nobody_types() {
        assert_eq!(s("नहीं"), top("nahi"));
        assert_eq!(s("नहीं"), top("nahin"));
        assert_eq!(s("हाँ"), top("ha"));
        assert_eq!(s("हाँ"), top("haan"));
        assert_eq!(s("कहा"), top("kaha"));
        assert_eq!(s("कहाँ"), top("kahan"));
    }

    #[test]
    fn the_diphthong_tells_main_from_mein() {
        assert_eq!(s("में"), top("mein"));
        assert_eq!(s("में"), top("me"));
        assert_eq!(s("मैं"), top("main"));
        assert_eq!(s("मैं"), top("mai"));
    }

    #[test]
    fn an_a_gliding_into_an_independent_vowel() {
        assert_eq!(s("कई"), top("kai"));
        assert_eq!(s("भाई"), top("bhai"));
        assert_eq!(s("और"), top("aur"));
        assert_eq!(s("और"), top("or"));
        assert_eq!(s("कौन"), top("kaun"));
        assert_eq!(s("कौन"), top("kon"));
        assert_eq!(s("जाओ"), top("jao"));
    }

    #[test]
    fn the_flap_is_typed_as_d_or_r() {
        assert_eq!(s(LADKA), top("ladka"));
        assert_eq!(s(LADKA), top("larka"));
    }

    #[test]
    fn retroflex_and_dental_are_one_key() {
        assert_eq!(s("ठीक"), top("theek"));
        assert_eq!(s("ठीक"), top("thik"));
    }

    #[test]
    fn aspiration_typed_is_a_request() {
        assert_eq!(s("थक"), top("thak"));
        assert_eq!(s("तक"), top("tak"));
    }

    #[test]
    fn a_doubled_consonant_is_one_key_and_still_counts() {
        for spelling in ["accha", "acha", "achha"] {
            assert_eq!(s("अच्छा"), top(spelling), "{spelling}");
        }
        assert_eq!(s("पता"), top("pata"));
        assert_eq!(s("पत्ता"), top("patta"));
    }

    #[test]
    fn spellings_of_one_word_are_siblings() {
        let set = |v: Vec<String>| v.into_iter().collect::<std::collections::HashSet<_>>();
        assert_eq!(
            set(vec!["हिंदी".into(), "हिन्दी".into()]),
            set(index().lookup("hindi"))
        );
        assert_eq!(
            set(vec![ZINDAGI.into(), "जिंदगी".into()]),
            set(index().lookup("zindagi"))
        );
        assert_eq!(
            set(vec![ZINDAGI.into(), "जिंदगी".into()]),
            set(index().lookup("jindagi"))
        );
    }

    #[test]
    fn chat_spelling_with_no_vowels_at_all() {
        assert_eq!(s("कर"), top("kr"));
        assert_eq!(s("रहा"), top("rha"));
        assert_eq!(s("कुछ"), top("kuch"));
    }

    #[test]
    fn letters_that_are_said_differently_than_written() {
        assert_eq!(s("ज्ञान"), top("gyan"));
        assert_eq!(s("कृपा"), top("kripa"));
        assert_eq!(s("लंबा"), top("lamba"));
    }

    #[test]
    fn a_closing_a_that_is_said_and_not_written() {
        let tatsama = HindiPhoneticIndex::new(entries(&[
            ("मित्र", 900),
            ("मित्रा", 100),
            ("सत्य", 500),
            ("खर्च", 900),
            ("खर्चा", 200),
            ("कम", 900),
        ]));
        assert_eq!("मित्र", tatsama.lookup("mitra")[0]);
        assert_eq!("सत्य", tatsama.lookup("satya")[0]);
        assert_eq!(vec!["खर्चा"], tatsama.lookup("kharcha"));
        assert!(tatsama.lookup("kama").is_empty());
        assert_eq!(vec!["मित्रा"], tatsama.lookup("mitraa"));
    }

    #[test]
    fn tokens_that_are_not_words_are_left_out() {
        let scraped = HindiPhoneticIndex::new(entries(&[
            ("है।", 9000),
            ("है", 7000),
            ("2024", 50),
            ("OK", 40),
        ]));
        assert_eq!(vec!["है"], scraped.lookup("hai"));
        assert_eq!(0, scraped.frequency_of("है।"));
    }

    #[test]
    fn frequencies_and_emptiness() {
        let idx = index();
        assert_eq!(900, idx.frequency_of("करना"));
        assert_eq!(0, idx.frequency_of("कर्ना"));
        assert!(!idx.is_empty());
        assert!(HindiPhoneticIndex::new(Vec::new()).is_empty());
        assert!(idx.lookup("xyzzy").is_empty());
        assert_eq!(9000, idx.max_frequency());
        assert!(idx.match_strength("karna") > 0);
    }
}
