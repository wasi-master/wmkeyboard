//! The Indic-engine languages by id, and what each one's word-list index
//! needs beyond the shared Hindi fold. A port of `IndicProfiles.kt`.

use crate::indic::Engine;
use crate::{
    gujarati, kannada, malayalam, marathi, minor_phonetic, nepali, odia, punjabi, sanskrit, tamil, telugu,
};

/// Every language's language id, in the order the Kotlin registry lists them.
pub const LANGUAGE_IDS: [&str; 10] = ["mr", "gu", "pa", "or", "ta", "te", "kn", "ml", "ne", "sa"];

/// The engine for `language_id`, if it is one of the Indic-engine languages.
pub fn for_language(language_id: &str) -> Option<&'static Engine> {
    Some(match language_id {
        "mr" => marathi::engine(),
        "gu" => gujarati::engine(),
        "pa" => punjabi::engine(),
        "or" => odia::engine(),
        "ta" => tamil::engine(),
        "te" => telugu::engine(),
        "kn" => kannada::engine(),
        "ml" => malayalam::engine(),
        "ne" => nepali::engine(),
        "sa" => sanskrit::engine(),
        _ => return minor_phonetic::indic_engine(language_id),
    })
}

/// The language's romanization rewritten into what the Hindi fold reads,
/// before an index lookup: the Dravidian four write "th" and "dh" for the
/// plain dentals; Telugu, Kannada and Malayalam close a word on the
/// anusvara where roman writes "m"; Tamil writes no voiced stops; Malayalam
/// folds its nasals and voicing on both sides. Nepali writes छ "x" and भ "v",
/// and says व as b, so "w" is a b too. Sanskrit's Harvard-Kyoto capitals are
/// letters the fold would otherwise lowercase into others: R is ऋ, G and J the
/// nasals, z श.
pub fn roman_prep(language_id: &str, input: &str) -> String {
    match language_id {
        "ne" => nepali_prep(input),
        "sa" => sanskrit_prep(input),
        "ta" => tamil_prep(&dravidian_dentals(input)),
        "te" | "kn" => closing_m(&dravidian_dentals(input)),
        // In this order: "nth" has to be read as ന്ത before "th" becomes "t".
        "ml" => unvoiced(&closing_m(&dravidian_dentals(&malayalam_nasals(input)))),
        _ => input.to_string(),
    }
}

/// The list's words, in Devanagari, rewritten the same way as `roman_prep`
/// rewrites what is typed: Malayalam's voiced stops onto the unvoiced ones;
/// Nepali's व onto ब, and the joiners its lists write in र्‍य dropped, which
/// the fold would otherwise refuse the word for.
pub fn devanagari_prep(language_id: &str, dev: &str) -> String {
    match language_id {
        "ne" => dev
            .chars()
            .filter(|&c| c != '\u{200C}' && c != '\u{200D}')
            .map(|c| if c == 'व' { 'ब' } else { c })
            .collect(),
        "ml" => dev
            .chars()
            .map(|c| match c {
                'ग' => 'क',
                'घ' => 'ख',
                'द' => 'त',
                'ध' => 'थ',
                'ड' => 'ट',
                'ढ' => 'ठ',
                'ब' => 'प',
                'भ' => 'फ',
                c => c,
            })
            .collect(),
        _ => dev.to_string(),
    }
}

/// Whether the language's romanization spells the inherent vowel it does
/// not write at the end of a word: Nepali "xa" छ and "huncha" हुन्छ,
/// Sanskrit "rAma" राम. The index then reads a typed closing "a" as that
/// vowel too, for every word rather than only the conjunct-final ones Hindi
/// says it after.
pub fn typed_schwa(language_id: &str) -> bool {
    matches!(language_id, "ne" | "sa")
}

/// Whether the index should break ties by closeness to what the rules
/// themselves spell: for a list with no frequencies to break them (Tamil's).
pub fn rerank_flat_list(language_id: &str) -> bool {
    language_id == "ta"
}

fn nepali_prep(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 4);
    for c in input.chars() {
        match c {
            'x' | 'X' => out.push_str("chh"),
            'v' | 'V' => out.push_str("bh"),
            'w' | 'W' => out.push('b'),
            c => out.push(c),
        }
    }
    out
}

fn sanskrit_prep(input: &str) -> String {
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() + 4);
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        match c {
            'R' if next == Some('R') => {
                out.push_str("rii");
                i += 2;
                continue;
            }
            'R' => {
                out.push_str("ri");
                // "Ri" and "RRi" spell the same ऋ.
                if next == Some('i') {
                    i += 1;
                }
            }
            'l' if next == Some('R') => {
                out.push_str("li");
                i += 2;
                continue;
            }
            'G' | 'J' => out.push('n'),
            'z' => out.push_str("sh"),
            c => out.push(c),
        }
        i += 1;
    }
    out
}

fn dravidian_dentals(input: &str) -> String {
    input.replace("th", "t").replace("dh", "d")
}

fn closing_m(input: &str) -> String {
    match input.strip_suffix('m') {
        Some(stem) => format!("{stem}M"),
        None => input.to_string(),
    }
}

/// "zh" → l, "nj" → n, "ng" → n, and "nt" not followed by "h" → "nr".
fn malayalam_nasals(input: &str) -> String {
    let folded = input
        .replace("zh", "l")
        .replace("nj", "n")
        .replace("ng", "n");
    let chars: Vec<char> = folded.chars().collect();
    let mut out = String::with_capacity(folded.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == 'n' && chars.get(i + 1) == Some(&'t') && chars.get(i + 2) != Some(&'h') {
            out.push_str("nr");
            i += 2;
        } else {
            out.push(chars[i]);
            i += 1;
        }
    }
    out
}

fn unvoiced(input: &str) -> String {
    input.replace('d', "t").replace('g', "k").replace('b', "p")
}

fn tamil_prep(input: &str) -> String {
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() + 2);
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        match c {
            'z' if next == Some('h') => {
                out.push('l');
                i += 2;
                continue;
            }
            'n' if next == Some('j') => {
                out.push('n');
                i += 2;
                continue;
            }
            's' if next != Some('h') => out.push_str("ch"),
            'g' => out.push('k'),
            'd' => out.push('t'),
            'b' => out.push('p'),
            c => out.push(c),
        }
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_language_has_an_engine() {
        for id in LANGUAGE_IDS {
            assert!(for_language(id).is_some(), "{id}");
            assert_eq!(id, for_language(id).unwrap().profile.language_id);
        }
        assert!(for_language("hi").is_none());
    }

    #[test]
    fn roman_prep_rewrites_what_the_hindi_fold_cannot_read() {
        assert_eq!("anta", roman_prep("ta", "andha"));
        assert_eq!("chollu", roman_prep("ta", "sollu"));
        assert_eq!("manaM", roman_prep("te", "manam"));
        assert_eq!("entu", roman_prep("ml", "enthu"));
        assert_eq!("enre", roman_prep("ml", "ente"));
        assert_eq!("karna", roman_prep("hi", "karna"));
        assert_eq!("bhayo", roman_prep("ne", "vayo"));
        assert_eq!("hunchha", roman_prep("ne", "hunxa"));
        assert_eq!("dhanyabad", roman_prep("ne", "dhanyawad"));
        assert_eq!("kriShNa", roman_prep("sa", "kRShNa"));
        assert_eq!("kriShNa", roman_prep("sa", "kRiShNa"));
        assert_eq!("shiva", roman_prep("sa", "ziva"));
    }

    #[test]
    fn nepali_devanagari_prep_drops_joiners_and_reads_va_as_ba() {
        assert_eq!("गर्यो", devanagari_prep("ne", "गर्\u{200D}यो"));
        assert_eq!("धन्यबाद", devanagari_prep("ne", "धन्यवाद"));
        assert_eq!("धन्यवाद", devanagari_prep("hi", "धन्यवाद"));
    }

    #[test]
    fn devanagari_prep_folds_malayalam_voicing_only() {
        assert_eq!("कत", devanagari_prep("ml", "गद"));
        assert_eq!("गद", devanagari_prep("ta", "गद"));
        assert!(rerank_flat_list("ta"));
        assert!(!rerank_flat_list("ml"));
    }
}
