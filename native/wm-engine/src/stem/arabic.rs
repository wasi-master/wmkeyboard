//! Arabic and Persian: ports of Apache Lucene's `ArabicNormalizer` and
//! `ArabicStemmer` (after Larkey, Ballesteros and Connell, "Light Stemming
//! for Arabic Information Retrieval"), `PersianNormalizer` and
//! `PersianStemmer`, all Apache-2.0.

use super::Word;

const ALEF: char = '\u{0627}';
const BEH: char = '\u{0628}';
const TEH_MARBUTA: char = '\u{0629}';
const TEH: char = '\u{062A}';
const REH: char = '\u{0631}';
const FEH: char = '\u{0641}';
const KAF: char = '\u{0643}';
const LAM: char = '\u{0644}';
const NOON: char = '\u{0646}';
const HEH: char = '\u{0647}';
const WAW: char = '\u{0648}';
const YEH: char = '\u{064A}';

const AR_PREFIXES: &[&[char]] = &[
    &[ALEF, LAM],
    &[WAW, ALEF, LAM],
    &[BEH, ALEF, LAM],
    &[KAF, ALEF, LAM],
    &[FEH, ALEF, LAM],
    &[LAM, LAM],
    &[WAW],
];

const AR_SUFFIXES: &[&[char]] = &[
    &[HEH, ALEF],
    &[ALEF, NOON],
    &[ALEF, TEH],
    &[WAW, NOON],
    &[YEH, NOON],
    &[YEH, HEH],
    &[YEH, TEH_MARBUTA],
    &[HEH],
    &[TEH_MARBUTA],
    &[YEH],
];

const FA_SUFFIXES: &[&[char]] = &[
    &[ALEF, TEH],
    &[ALEF, NOON],
    &[TEH, REH, YEH, NOON],
    &[TEH, REH],
    &[YEH, YEH],
    &[YEH],
    &[HEH, ALEF],
];

fn ends_with(w: &Word, suffix: &[char]) -> bool {
    w.len >= suffix.len() && w.s[w.len - suffix.len()..w.len] == *suffix
}

fn arabic_normalize(w: &mut Word) {
    let mut i = 0;
    while i < w.len {
        match w.s[i] {
            '\u{0622}' | '\u{0623}' | '\u{0625}' => w.s[i] = ALEF,
            '\u{0649}' => w.s[i] = YEH,
            TEH_MARBUTA => w.s[i] = HEH,
            // Tatweel and the harakat.
            '\u{0640}' | '\u{064B}'..='\u{0652}' => {
                w.delete(i);
                continue;
            }
            _ => {}
        }
        i += 1;
    }
}

/// Lucene's suffix loop: every suffix in turn, each leaving two letters.
fn strip_suffixes(w: &mut Word, suffixes: &[&[char]]) {
    for suffix in suffixes {
        if w.len >= suffix.len() + 2 && ends_with(w, suffix) {
            w.drop_end(suffix.len());
        }
    }
}

pub fn arabic(w: &mut Word) {
    arabic_normalize(w);
    for prefix in AR_PREFIXES {
        let fits = if prefix.len() == 1 { w.len >= 4 } else { w.len >= prefix.len() + 2 };
        if fits && w.s[..prefix.len()] == **prefix {
            w.drop_start(prefix.len());
            break;
        }
    }
    strip_suffixes(w, AR_SUFFIXES);
}

pub fn persian(w: &mut Word) {
    arabic_normalize(w);
    let mut i = 0;
    while i < w.len {
        match w.s[i] {
            '\u{06CC}' | '\u{06D2}' => w.s[i] = YEH,
            '\u{06A9}' => w.s[i] = KAF,
            '\u{06C0}' | '\u{06C1}' => w.s[i] = HEH,
            '\u{0654}' => {
                w.delete(i);
                continue;
            }
            _ => {}
        }
        i += 1;
    }
    strip_suffixes(w, FA_SUFFIXES);
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    #[test]
    fn arabic() {
        // Lucene's TestArabicStemFilter.
        for (word, want) in [
            ("الحسن", "حسن"),
            ("والحسن", "حسن"),
            ("بالحسن", "حسن"),
            ("كالحسن", "حسن"),
            ("فالحسن", "حسن"),
            ("للاخر", "اخر"),
            ("وحسن", "حسن"),
            ("زوجها", "زوج"),
            ("ساهدان", "ساهد"),
            ("ساهدات", "ساهد"),
            ("ساهدون", "ساهد"),
            ("ساهدين", "ساهد"),
            ("ساهديه", "ساهد"),
            ("ساهدية", "ساهد"),
            ("ساهده", "ساهد"),
            ("ساهدة", "ساهد"),
            ("ساهدي", "ساهد"),
            ("وساهدون", "ساهد"),
            ("ساهدهات", "ساهد"),
            ("الو", "الو"),
            ("وا", "وا"),
        ] {
            assert_eq!(stem(Lang::Arabic, word), want, "{word}");
        }
    }

    #[test]
    fn persian() {
        // Lucene's TestPersianStemFilter.
        for (word, want) in [
            ("كتابها", "كتاب"),
            ("كتابات", "كتاب"),
            ("كتابان", "كتاب"),
            ("بزرگتر", "بزرگ"),
            ("بزرگترين", "بزرگ"),
            ("كتابي", "كتاب"),
        ] {
            assert_eq!(stem(Lang::Persian, word), want, "{word}");
        }
        // Farsi yeh and keheh fold onto the Arabic letters.
        assert_eq!(stem(Lang::Persian, "کتابی"), stem(Lang::Persian, "كتابي"));
    }
}
