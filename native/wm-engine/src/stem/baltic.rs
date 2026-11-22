//! Latvian: a port of Apache Lucene's `LatvianStemmer` (Apache-2.0), a light
//! version of the algorithm in Kārlis Kreslins' thesis "A stemming algorithm
//! for Latvian": the longest noun or adjective ending the word's vowel count
//! allows, then the palatalized consonant put back.

use super::Word;

/// Suffix, the vowels the word must have more than, whether it palatalizes.
const AFFIXES: &[(&str, usize, bool)] = &[
    ("ajiem", 3, false),
    ("ajai", 3, false),
    ("ajam", 2, false),
    ("ajām", 2, false),
    ("ajos", 2, false),
    ("ajās", 2, false),
    ("iem", 2, true),
    ("ajā", 2, false),
    ("ais", 2, false),
    ("ai", 2, false),
    ("ei", 2, false),
    ("ām", 1, false),
    ("am", 1, false),
    ("ēm", 1, false),
    ("īm", 1, false),
    ("im", 1, false),
    ("um", 1, false),
    ("us", 1, true),
    ("as", 1, false),
    ("ās", 1, false),
    ("es", 1, false),
    ("os", 1, true),
    ("ij", 1, false),
    ("īs", 1, false),
    ("ēs", 1, false),
    ("is", 1, false),
    ("ie", 1, false),
    ("u", 1, true),
    ("a", 1, true),
    ("i", 1, true),
    ("e", 1, false),
    ("ā", 1, false),
    ("ē", 1, false),
    ("ī", 1, false),
    ("ū", 1, false),
    ("o", 1, false),
    ("s", 0, false),
    ("š", 0, false),
];

pub fn latvian(w: &mut Word) {
    let vowels = w.s[..w.len]
        .iter()
        .filter(|c| matches!(c, 'a' | 'e' | 'i' | 'o' | 'u' | 'ā' | 'ī' | 'ē' | 'ū'))
        .count();
    for &(affix, vc, palatalizes) in AFFIXES {
        let n = affix.chars().count();
        if vowels > vc && w.len >= n + 3 && w.ends(affix) {
            w.drop_end(n);
            if palatalizes {
                unpalatalize(w);
            }
            return;
        }
    }
}

fn unpalatalize(w: &mut Word) {
    // The first character of the ending just cut, still in the buffer.
    if w.s[w.len] == 'u' {
        if w.ends("kš") {
            w.len += 1;
            w.set_end(2, 's');
            return w.set_end(1, 't');
        }
        if w.ends("ņņ") {
            return w.replace_end(2, "nn");
        }
    }
    if w.ends_any(&["pj", "bj", "mj", "vj"]) {
        return w.drop_end(1);
    }
    for (from, to) in [("šņ", "sn"), ("žņ", "zn"), ("šļ", "sl"), ("žļ", "zl"), ("ļņ", "ln"), ("ļļ", "ll")] {
        if w.ends(from) {
            return w.replace_end(2, to);
        }
    }
    match w.at_end(1) {
        'č' => w.set_end(1, 'c'),
        'ļ' => w.set_end(1, 'l'),
        'ņ' => w.set_end(1, 'n'),
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    #[test]
    fn latvian() {
        // Lucene's TestLatvianStemmer, nouns of the first declension.
        for (word, want) in [
            ("tēvs", "tēv"),
            ("tēvi", "tēv"),
            ("tēva", "tēv"),
            ("tēvam", "tēv"),
            ("tēvu", "tēv"),
            ("tēvā", "tēv"),
            ("tēviem", "tēv"),
            ("tēvos", "tēv"),
            ("lācis", "lāc"),
            ("lāči", "lāc"),
            ("lāča", "lāc"),
            ("lāčiem", "lāc"),
            ("lāčos", "lāc"),
        ] {
            assert_eq!(stem(Lang::Latvian, word), want, "{word}");
        }
    }
}
