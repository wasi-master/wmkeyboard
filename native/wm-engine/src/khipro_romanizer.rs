//! Khipro run backwards: the keys that spell a Bangla word on the Khipro
//! layout, for decoding a swipe over its grid. A port of
//! `KhiproRomanizer.kt`.
//!
//! Nothing here is a table someone wrote. The pieces are read off Khipro
//! itself once: every key sequence of up to `MAX_KEYS` letters is converted
//! on its own, after a consonant and after a vowel, and what it adds is
//! recorded against the shortest sequence that adds it. A word is then cut
//! into those pieces left to right, keeping a few cheapest cuts, and a cut
//! counts only if Khipro turns its keys back into exactly the word. So a
//! word that comes back is right by construction, and any word of any list
//! can be spelled.
//!
//! Each edition has its own pieces, read off its own touchscreen spec:
//! Classic joins `hb` into হ্ব and Dark leaves it apart, so the same word is
//! a different spelling, and a stroke read against the other edition's
//! spellings lands on some other word.
//!
//! Letters and the slicer `/`, which a swipe over the Khipro grid passes
//! through (see [`KEYS`]). A word that needs any other key (the separator
//! `;`, the blinder) has no spelling here and is typed.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use crate::khipro::{self, Variant};
use crate::sort;

/// Longest key sequence read off Khipro for a single piece.
const MAX_KEYS: usize = 3;
/// Cheapest cuts kept per position; the rest are never converted back.
const BEAM: usize = 12;
/// Whole-word cuts converted back, shortest first, before a word is given up.
const FINAL_TRIES: usize = 32;
/// Key sequences kept per piece, shortest first. One is not enough: after ক,
/// `n` adds a separate ন, but after শ it joins into শ্ন, so শনি needs the
/// longer `on` the table would otherwise have thrown away.
const ALTERNATIVES: usize = 4;

/// The keys a swipe over the Khipro grid can pass through: the letters and
/// the slicer, which is how চন্দ্রবিন্দু and খণ্ড-ত are typed (`cand/` is চাঁদ).
pub const KEYS: &str = "abcdefghijklmnopqrstuvwxyz/";

/// Keys that end a consonant cluster on their own; no `o` goes before or after them.
const VOWEL_KEYS: &str = "aeiouwy";
const CONSONANT_KEY: &str = "k";
const VOWEL_KEY: &str = "a";
/// The keys of a reph, and the reph, and what the keys show before anything follows them.
const REPH_KEYS: &str = "rr";
const REPH: &str = "\u{9B0}\u{9CD}";
const PENDING_REPH: &str = "\u{9B0}\u{9B0}";

#[derive(Clone, Copy, PartialEq, Eq)]
enum Context {
    Start,
    Consonant,
    Vowel,
    Reph,
}

type Pieces = HashMap<String, Vec<String>>;

struct Tables {
    /// Bangla → keys, typed at the start of a word.
    start: Pieces,
    /// What keys add after a consonant (a vowel sign, a joined or separate consonant).
    after_consonant: Pieces,
    /// What keys add after a vowel.
    after_vowel: Pieces,
    /// What keys add after a reph (`rr`, র্), which joins onto the consonant that follows.
    after_reph: Pieces,
    /// Longest Bangla piece in any table, in characters.
    longest: usize,
}

fn convert(keys: &str, variant: Variant) -> String {
    khipro::convert(keys, variant)
}

/// The touchscreen spec of `variant`'s edition: a swipe is a touchscreen
/// stroke, and the editions spell differently (Classic joins `hb` into হ্ব,
/// so হবে is `hobe`; Dark leaves consonants apart until `/` joins them, so
/// হবে is `hbe`), which is why each has its own pieces.
fn edition(variant: Variant) -> Variant {
    Variant::of(variant.dark(), false)
}

/// The pieces of `variant`'s edition, read off its touchscreen spec in use:
/// built once, and again after a newer spec is installed
/// ([`khipro::generation`]).
fn tables(variant: Variant) -> Arc<Tables> {
    static TABLES: [RwLock<Option<(u64, Arc<Tables>)>>; 2] = [RwLock::new(None), RwLock::new(None)];
    let slot = &TABLES[variant.dark() as usize];
    let generation = khipro::generation();
    if let Some((built, tables)) = &*slot.read().unwrap_or_else(|poisoned| poisoned.into_inner()) {
        if *built == generation {
            return tables.clone();
        }
    }
    let tables = Arc::new(build(edition(variant)));
    *slot.write().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some((generation, tables.clone()));
    tables
}

/// Builds `variant`'s pieces now, off the thread that will first need them.
pub fn warm(variant: Variant) {
    tables(variant);
}

/// Reads the pieces off one edition's touchscreen rules. About 55,000 conversions, once.
fn build(variant: Variant) -> Tables {
    let mut start: Pieces = HashMap::new();
    let mut after_consonant: Pieces = HashMap::new();
    let mut after_vowel: Pieces = HashMap::new();
    let mut after_reph: Pieces = HashMap::new();
    let consonant_carrier = convert(CONSONANT_KEY, variant);
    let vowel_carrier = convert(VOWEL_KEY, variant);
    // `rr` shows as রর until a consonant follows and turns it into a reph, so
    // a reph is recorded by what it becomes, and what follows one is read
    // after a vowel and a reph that has already joined.
    let reph_carrier = format!("{vowel_carrier}{REPH}");
    // Sequences come shortest first, so the first few recorded are the shortest.
    fn record(map: &mut Pieces, added: &str, keys: &str) {
        let piece = if keys.ends_with(REPH_KEYS) && added.ends_with(PENDING_REPH) {
            format!("{}{REPH}", &added[..added.len() - PENDING_REPH.len()])
        } else {
            added.to_string()
        };
        if piece.is_empty() {
            return;
        }
        let held = map.entry(piece).or_default();
        if held.len() < ALTERNATIVES {
            held.push(keys.to_string());
        }
    }
    for keys in sequences() {
        record(&mut start, &convert(&keys, variant), &keys);
        let out = convert(&format!("{CONSONANT_KEY}{keys}"), variant);
        if let Some(rest) = out.strip_prefix(consonant_carrier.as_str()) {
            record(&mut after_consonant, rest, &keys);
        }
        let out = convert(&format!("{VOWEL_KEY}{keys}"), variant);
        if let Some(rest) = out.strip_prefix(vowel_carrier.as_str()) {
            record(&mut after_vowel, rest, &keys);
        }
        let out = convert(&format!("{VOWEL_KEY}{REPH_KEYS}{keys}"), variant);
        if let Some(rest) = out.strip_prefix(reph_carrier.as_str()) {
            record(&mut after_reph, rest, &keys);
        }
    }
    let longest_in = |map: &Pieces| map.keys().map(|k| k.chars().count()).max().unwrap_or(1);
    let longest = [&start, &after_consonant, &after_vowel, &after_reph]
        .into_iter()
        .map(longest_in)
        .max()
        .unwrap();
    Tables {
        start,
        after_consonant,
        after_vowel,
        after_reph,
        longest,
    }
}

/// Every sequence of 1 to `MAX_KEYS` keys: the letters-only ones first,
/// shortest first, then the ones with the slicer. A piece keeps its first
/// `ALTERNATIVES`, so one a letter spelling reaches never fills its list
/// with slicer detours, and one only the slicer reaches still gets it.
fn sequences() -> Vec<String> {
    let letters: Vec<char> = KEYS.chars().filter(|&c| c != '/').collect();
    let all: Vec<char> = KEYS.chars().collect();
    let mut out = Vec::new();
    for (alphabet, slicer_only) in [(&letters, false), (&all, true)] {
        let mut layer: Vec<String> = vec![String::new()];
        for _ in 0..MAX_KEYS {
            layer = layer
                .iter()
                .flat_map(|prefix| alphabet.iter().map(move |&c| format!("{prefix}{c}")))
                .collect();
            out.extend(
                layer
                    .iter()
                    .filter(|s| !slicer_only || s.contains('/'))
                    .cloned(),
            );
        }
    }
    out
}

#[derive(Clone)]
struct Cut {
    keys: String,
    context: Context,
}

/// The fewest letters that Khipro turns into `word`, or `None` when no cut
/// of it converts back exactly (a word that needs a non-letter key, mostly).
pub fn romanize(word: &str, variant: Variant) -> Option<String> {
    cut(&precomposed(word), edition(variant)).map(|c| c.keys)
}

fn cut(word: &str, variant: Variant) -> Option<Cut> {
    if word.is_empty() {
        return None;
    }
    let t = tables(variant);
    let chars: Vec<char> = word.chars().collect();
    let n = chars.len();
    // cuts[i]: the cheapest few ways of typing word[0, i).
    let mut cuts: Vec<Option<Vec<Cut>>> = vec![None; n + 1];
    cuts[0] = Some(vec![Cut {
        keys: String::new(),
        context: Context::Start,
    }]);
    for i in 0..n {
        let Some(here) = cuts[i].take() else { continue };
        let typed: String = chars[..i].iter().collect();
        let kept = prune(here, &typed, variant);
        cuts[i] = Some(kept.clone());
        for c in &kept {
            let maps: Vec<&Pieces> = match c.context {
                Context::Start => vec![&t.start],
                Context::Consonant => vec![&t.after_consonant],
                Context::Vowel => vec![&t.after_vowel, &t.start],
                Context::Reph => vec![&t.after_reph],
            };
            for length in (1..=t.longest.min(n - i)).rev() {
                let piece: String = chars[i..i + length].iter().collect();
                for map in &maps {
                    let Some(options) = map.get(&piece) else {
                        continue;
                    };
                    let next = cuts[i + length].get_or_insert_with(Vec::new);
                    for keys in options {
                        next.push(Cut {
                            keys: format!("{}{keys}", c.keys),
                            context: context_after(&piece),
                        });
                    }
                }
            }
        }
    }
    let done = cuts[n].take()?;
    let mut candidates = distinct_by_keys(done);
    sort::by_key(&mut candidates, |c| c.keys.len());
    candidates
        .into_iter()
        .take(FINAL_TRIES)
        .find(|c| convert(&c.keys, variant) == word)
}

fn distinct_by_keys(cuts: Vec<Cut>) -> Vec<Cut> {
    let mut seen = std::collections::HashSet::new();
    cuts.into_iter()
        .filter(|c| seen.insert(c.keys.clone()))
        .collect()
}

/// The `BEAM` cuts of a prefix worth extending, shortest first. Where there
/// are more, the ones Khipro already turns into exactly `typed` go first:
/// shortness alone keeps joined spellings (`durbl`) that read as some other
/// word and pushes the right one out.
fn prune(cuts: Vec<Cut>, typed: &str, variant: Variant) -> Vec<Cut> {
    let mut distinct = distinct_by_keys(cuts);
    sort::by_key(&mut distinct, |c| c.keys.len());
    if distinct.len() <= BEAM {
        return distinct;
    }
    let mut exact: Vec<Cut> = Vec::with_capacity(BEAM);
    let mut rest: Vec<Cut> = Vec::new();
    for c in distinct {
        if exact.len() < BEAM && convert(&c.keys, variant) == typed {
            exact.push(c);
        } else {
            rest.push(c);
        }
    }
    exact.extend(rest);
    exact.truncate(BEAM);
    exact
}

/// Every way of spelling `text` worth decoding a swipe against: the fewest
/// letters, and the same with the inherent `o` written out between
/// consonants where Khipro still reads the word (`ekhn` and `ekhon` for
/// এখন), since people swipe the vowel they say. Empty when there is none.
pub fn spellings(text: &str, variant: Variant) -> Vec<String> {
    let variant = edition(variant);
    let word = precomposed(text);
    let Some(best) = cut(&word, variant) else {
        return Vec::new();
    };
    let shortest = best.keys;
    let bytes: Vec<char> = shortest.chars().collect();
    // Between two consonant keys an `o` is the inherent vowel; kept wherever
    // Khipro still reads the same word, which it does not where the two
    // would have joined or where they are one letter (`kh`).
    let mut spelled = shortest.clone();
    for at in (1..bytes.len()).rev() {
        if VOWEL_KEYS.contains(bytes[at - 1]) || VOWEL_KEYS.contains(bytes[at]) {
            continue;
        }
        if bytes[at - 1] == '/' || bytes[at] == '/' {
            continue;
        }
        let tried = format!("{}o{}", &spelled[..at], &spelled[at..]);
        if convert(&tried, variant) == word {
            spelled = tried;
        }
    }
    if spelled != shortest {
        vec![shortest, spelled]
    } else {
        vec![shortest]
    }
}

/// Khipro writes য় ড় ঢ় as one character each; a list kept in NFC writes them
/// as the letter and a dot. Compared in Khipro's form.
fn precomposed(word: &str) -> String {
    if !word.contains('\u{9BC}') {
        return word.to_string();
    }
    word.replace("\u{9A1}\u{9BC}", "\u{9DC}")
        .replace("\u{9A2}\u{9BC}", "\u{9DD}")
        .replace("\u{9AF}\u{9BC}", "\u{9DF}")
}

fn context_after(piece: &str) -> Context {
    if piece.ends_with(REPH) {
        return Context::Reph;
    }
    let last = piece.chars().last().unwrap();
    if ('\u{995}'..='\u{9B9}').contains(&last)
        || ('\u{9DC}'..='\u{9DF}').contains(&last)
        || last == '\u{9BC}'
        || last == '\u{9CD}'
    {
        Context::Consonant
    } else {
        Context::Vowel
    }
}

#[cfg(test)]
mod tests {
    //! Mirrors `KhiproRomanizerTest.kt`: every spelling it gives must convert
    //! back to the word.

    use super::{precomposed, spellings, KEYS};
    use crate::khipro::{self, Variant};

    #[test]
    fn every_spelling_converts_back_to_the_word() {
        let words = [
            "আমি",
            "এখন",
            "আপনি",
            "করছি",
            "সম\u{9DF}",
            "কোথা\u{9DF}",
            "বাংলাদেশ",
            "ক্ষিপ্র",
            "দুর্বল",
            "বর্ষা",
            "পার্টি",
            "শনি",
            "হাসপাতাল",
            "ধন্যবাদ",
            "বন্ধুদের",
            "চাঁদ",
            "হঠাৎ",
            "দাঁ\u{9DC}ি\u{9DF}ে",
            "উৎসব",
        ];
        for word in words {
            let spellings = spellings(word, Variant::Touchscreen);
            assert!(!spellings.is_empty(), "{word} has no spelling");
            for spelling in spellings {
                assert!(
                    spelling.chars().all(|c| KEYS.contains(c)),
                    "{spelling} has a key a swipe cannot reach"
                );
                assert_eq!(
                    precomposed(word),
                    precomposed(&khipro::convert(&spelling, Variant::Touchscreen)),
                    "{spelling}"
                );
            }
        }
    }

    #[test]
    fn the_inherent_vowel_may_be_written_out() {
        assert_eq!(vec!["ekhn", "ekhon"], spellings("এখন", Variant::Touchscreen));
    }

    #[test]
    fn dark_spells_with_its_own_rules() {
        // Classic joins `hb` into হ্ব and so needs the `o`; Dark leaves the
        // two apart and joins only on `/`. Reading Dark's swipes against
        // Classic's spellings turned `hbe` into হচ্ছে (`hccfe`).
        let dark = Variant::DarkTouchscreen;
        assert_eq!(vec!["hobe"], spellings("হবে", Variant::Touchscreen));
        assert_eq!("hbe", spellings("হবে", dark)[0]);
        assert_eq!("hccfe", spellings("হচ্ছে", Variant::Touchscreen)[0]);
        assert_eq!("hccf/e", spellings("হচ্ছে", dark)[0]);
        for word in ["হবে", "হচ্ছে", "লাগবে", "ক্ষিপ্র", "দুর্বল", "ধন্যবাদ", "চাঁদ", "উৎসব", "রাষ্ট্র"] {
            let spellings = spellings(word, dark);
            assert!(!spellings.is_empty(), "{word} has no Dark spelling");
            for spelling in spellings {
                assert_eq!(precomposed(word), precomposed(&khipro::convert(&spelling, dark)), "{spelling}");
            }
        }
    }

    #[test]
    fn the_slicer_is_a_key_of_the_spelling() {
        assert!(spellings("চাঁদ", Variant::Touchscreen).iter().all(|s| s.contains('/')));
    }
}

