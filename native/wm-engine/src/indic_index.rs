//! Reverse-phonetic lookup for the Indic-engine languages: the Hindi index's
//! fold, reached through Devanagari. A port of `IndicPhoneticIndex.kt`.
//!
//! Every word of the list is spelled in Devanagari (the blocks are parallel,
//! so that is a shift and a handful of script-specific signs) and filed by
//! the Hindi fold. A lookup answers in the list's own script. What a
//! language adds: `roman_prep`, its romanization's habits rewritten into
//! what the Hindi fold expects; `devanagari_prep`, the same done to the
//! list's words; `rerank_by`, the rules' own reading, for a list with no
//! frequencies.

use std::collections::HashMap;

use crate::hindi_index::HindiPhoneticIndex;
use crate::indic_profiles;
use crate::indic_script::IndicScript;
use crate::phonetic_index::{bounded_entries, PhoneticIndex, PHONETIC_INDEX_BUDGET};

/// A spelling rewrite, on either side of the fold.
type Prep = fn(&str) -> String;

/// How many of the fold's candidates the re-rank reorders.
const RERANK_DEPTH: usize = 30;

pub struct IndicPhoneticIndex {
    script: IndicScript,
    roman_prep: fn(&str) -> String,
    devanagari_prep: fn(&str) -> String,
    rerank_by: Option<fn(&str) -> String>,
    /// Devanagari spelling to the list's own word, for every word filed.
    native: HashMap<String, String>,
    inner: HindiPhoneticIndex,
    /// Every word at the same count: no frequency to rank by.
    flat: bool,
}

fn identity(s: &str) -> String {
    s.to_string()
}

impl IndicPhoneticIndex {
    pub fn new(
        entries: Vec<(String, i32)>,
        script: IndicScript,
        roman_prep: Option<fn(&str) -> String>,
        devanagari_prep: Option<fn(&str) -> String>,
        rerank_by: Option<fn(&str) -> String>,
    ) -> IndicPhoneticIndex {
        let roman_prep = roman_prep.unwrap_or(identity);
        let devanagari_prep = devanagari_prep.unwrap_or(identity);
        let kept = bounded_entries(entries, PHONETIC_INDEX_BUDGET);
        let flat = !kept.is_empty() && kept.iter().all(|e| e.1 == kept[0].1);
        let mut native: HashMap<String, String> = HashMap::with_capacity(kept.len());
        let mut converted: Vec<(String, i32)> = Vec::with_capacity(kept.len());
        for (word, frequency) in kept {
            let dev = devanagari_prep(&script.to_devanagari(&word));
            // Two spellings of one word can meet in Devanagari; the first,
            // commoner one answers.
            native.entry(dev.clone()).or_insert_with(|| word.clone());
            converted.push((dev, frequency));
        }
        IndicPhoneticIndex {
            script,
            roman_prep,
            devanagari_prep,
            rerank_by,
            native,
            inner: HindiPhoneticIndex::new(converted),
            flat,
        }
    }

    /// The index for one of the Indic-engine languages, with that language's
    /// preps and, for a flat list, its engine's reading as the tie-breaker.
    pub fn for_language(
        entries: Vec<(String, i32)>,
        language_id: &str,
    ) -> Option<IndicPhoneticIndex> {
        let engine = indic_profiles::for_language(language_id)?;
        let (roman_prep, devanagari_prep): (Prep, Prep) = match language_id {
            "ta" => (|s| indic_profiles::roman_prep("ta", s), identity),
            "te" => (|s| indic_profiles::roman_prep("te", s), identity),
            "kn" => (|s| indic_profiles::roman_prep("kn", s), identity),
            "ml" => (
                |s| indic_profiles::roman_prep("ml", s),
                |s| indic_profiles::devanagari_prep("ml", s),
            ),
            "ne" => (
                |s| indic_profiles::roman_prep("ne", s),
                |s| indic_profiles::devanagari_prep("ne", s),
            ),
            "sa" => (|s| indic_profiles::roman_prep("sa", s), identity),
            _ => (identity, identity),
        };
        let rerank_by: Option<fn(&str) -> String> = if indic_profiles::rerank_flat_list(language_id)
        {
            Some(crate::tamil::transliterate)
        } else {
            None
        };
        let mut index = IndicPhoneticIndex::new(
            entries,
            engine.profile.script,
            Some(roman_prep),
            Some(devanagari_prep),
            rerank_by,
        );
        index.inner = index.inner.with_typed_schwa(indic_profiles::typed_schwa(language_id));
        Some(index)
    }
}

fn edit_distance(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut previous: Vec<usize> = (0..=b.len()).collect();
    for (i, &ac) in a.iter().enumerate() {
        let mut current = vec![0usize; b.len() + 1];
        current[0] = i + 1;
        for (j, &bc) in b.iter().enumerate() {
            let cost = usize::from(ac != bc);
            current[j + 1] = (previous[j + 1] + 1)
                .min(current[j] + 1)
                .min(previous[j] + cost);
        }
        previous = current;
    }
    previous[b.len()]
}

impl PhoneticIndex for IndicPhoneticIndex {
    fn lookup(&self, input: &str) -> Vec<String> {
        let mut found: Vec<String> = Vec::new();
        for dev in self.inner.lookup(&(self.roman_prep)(input)) {
            if let Some(word) = self.native.get(&dev) {
                if !found.contains(word) {
                    found.push(word.clone());
                }
            }
        }
        let Some(rules) = self.rerank_by else {
            return found;
        };
        if !self.flat || found.len() < 2 {
            return found;
        }
        let reference = rules(input);
        let depth = RERANK_DEPTH.min(found.len());
        let mut head: Vec<(usize, String)> = found[..depth].iter().cloned().enumerate().collect();
        head.sort_by_key(|(index, word)| (edit_distance(word, &reference), *index));
        head.into_iter()
            .map(|(_, w)| w)
            .chain(found[depth..].iter().cloned())
            .collect()
    }

    fn frequency_of(&self, word: &str) -> i32 {
        self.inner
            .frequency_of(&(self.devanagari_prep)(&self.script.to_devanagari(word)))
    }

    fn match_strength(&self, input: &str) -> i32 {
        self.inner.match_strength(&(self.roman_prep)(input))
    }

    fn max_frequency(&self) -> i32 {
        self.inner.max_frequency()
    }

    fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entries(pairs: &[(&str, i32)]) -> Vec<(String, i32)> {
        pairs.iter().map(|(w, f)| (w.to_string(), *f)).collect()
    }

    #[test]
    fn answers_in_the_lists_own_script() {
        let marathi =
            IndicPhoneticIndex::for_language(entries(&[("करणार", 50), ("करना", 10)]), "mr")
                .unwrap();
        assert_eq!("करणार", marathi.lookup("karnar")[0]);
        assert_eq!(50, marathi.frequency_of("करणार"));
        let telugu =
            IndicPhoneticIndex::for_language(entries(&[("\u{C28}\u{C47}\u{C28}\u{C41}", 9)]), "te")
                .unwrap();
        assert_eq!(vec!["\u{C28}\u{C47}\u{C28}\u{C41}"], telugu.lookup("nenu"));
        assert!(!telugu.is_empty());
        assert!(IndicPhoneticIndex::for_language(Vec::new(), "hi").is_none());
    }

    #[test]
    fn a_flat_tamil_list_is_reranked_by_the_rules() {
        // Two siblings at the same count: the one nearer the rules' spelling leads.
        let tamil = IndicPhoneticIndex::for_language(
            entries(&[
                ("\u{B9E}\u{BBE}\u{BA8}", 1),
                ("\u{BA8}\u{BBE}\u{BA9}\u{BCD}", 1),
            ]),
            "ta",
        )
        .unwrap();
        assert_eq!("\u{BA8}\u{BBE}\u{BA9}\u{BCD}", tamil.lookup("naan")[0]);
        assert_eq!(1, edit_distance("abc", "abd"));
        assert_eq!(3, edit_distance("", "abc"));
    }
}
