//! Hindi and Bengali: ports of Apache Lucene's `HindiStemmer` (A. Ramanathan
//! and D. Rao, "A Lightweight Stemmer for Hindi"), `HindiNormalizer` (after
//! L. Larkey et al., "Hindi CLIR in Thirty Days"), `BengaliStemmer` (after
//! Jacques Savoy's UniNE light stemmer) and the core of `BengaliNormalizer`,
//! all Apache-2.0.
//!
//! Lucene normalizes, then stems, so its long-vowel suffix rules never
//! fire on the normalized word. Here the suffix comes off the word as typed
//! and the stem is normalized after, which is what those rules mean.

use super::Word;

/// Longest first, each list a length Lucene tests (`len >` its own bound).
const HINDI: &[(usize, &[&str])] = &[
    (6, &["ाएंगी", "ाएंगे", "ाऊंगी", "ाऊंगा", "ाइयाँ", "ाइयों", "ाइयां"]),
    (
        5,
        &[
            "ाएगी", "ाएगा", "ाओगी", "ाओगे", "एंगी", "ेंगी", "एंगे", "ेंगे", "ूंगी", "ूंगा", "ातीं", "नाओं", "नाएं", "ताओं", "ताएं",
            "ियाँ", "ियों", "ियां",
        ],
    ),
    (
        4,
        &[
            "ाकर", "ाइए", "ाईं", "ाया", "ेगी", "ेगा", "ोगी", "ोगे", "ाने", "ाना", "ाते", "ाती", "ाता", "तीं", "ाओं", "ाएं", "ुओं",
            "ुएं", "ुआं",
        ],
    ),
    (
        3,
        &[
            "कर", "ाओ", "िए", "ाई", "ाए", "ने", "नी", "ना", "ते", "ीं", "ती", "ता", "ाँ", "ां", "ों", "ें",
        ],
    ),
    (2, &["ो", "े", "ू", "ु", "ी", "ि", "ा"]),
];

const BENGALI: &[(usize, &[&str])] = &[
    (9, &["িয়াছিলাম", "িতেছিলাম", "িতেছিলেন", "ইতেছিলেন", "িয়াছিলেন", "ইয়াছিলেন"]),
    (8, &["িতেছিলি", "িতেছিলে", "িয়াছিলা", "িয়াছিলে", "িতেছিলা", "িয়াছিলি", "য়েদেরকে"]),
    (7, &["িতেছিস", "িতেছেন", "িয়াছিস", "িয়াছেন", "েছিলাম", "েছিলেন", "েদেরকে"]),
    (
        6,
        &[
            "িতেছি", "িতেছা", "িতেছে", "ছিলাম", "ছিলেন", "িয়াছি", "িয়াছা", "িয়াছে", "েছিলে", "েছিলা", "য়েদের", "দেরকে",
        ],
    ),
    (
        5,
        &[
            "িলাম", "িলেন", "িতাম", "িতেন", "িবেন", "ছিলি", "ছিলে", "ছিলা", "তেছে", "িতেছ", "খানা", "খানি", "গুলো", "গুলি",
            "য়েরা", "েদের",
        ],
    ),
    (
        4,
        &[
            "লাম", "িলি", "ইলি", "িলে", "ইলে", "লেন", "িলা", "ইলা", "তাম", "িতি", "ইতি", "িতে", "ইতে", "তেন", "িতা", "িবা",
            "ইবা", "িবি", "ইবি", "বেন", "িবে", "ইবে", "ছেন", "য়োন", "য়ের", "েরা", "দের",
        ],
    ),
    (
        3,
        &[
            "িস", "েন", "লি", "লে", "লা", "তি", "তে", "তা", "বি", "বে", "বা", "ছি", "ছা", "ছে", "ুন", "ুক", "টা", "টি", "নি",
            "ের", "রা", "কে",
        ],
    ),
    (2, &["ি", "ী", "া", "ো", "ে", "ব", "ত"]),
];

/// The first rule whose length bound the word passes and whose suffix it
/// ends with.
fn strip(w: &mut Word, rules: &[(usize, &[&str])]) {
    for &(bound, suffixes) in rules {
        if w.len > bound {
            for s in suffixes {
                if w.ends(s) {
                    return w.drop_end(s.chars().count());
                }
            }
        }
    }
}

pub fn hindi(w: &mut Word) {
    // Nukta off first: "लड़कियाँ" and "लडकियाँ" are one spelling.
    remove_all(w, '\u{093C}');
    strip(w, HINDI);
    let mut i = 0;
    while i < w.len {
        let c = w.s[i];
        let mapped = match c {
            // Dead n before a consonant: the anusvara it is read as.
            '\u{0928}' if i + 1 < w.len && w.s[i + 1] == '\u{094D}' => {
                w.delete(i + 1);
                '\u{0902}'
            }
            '\u{0901}' => '\u{0902}',
            '\u{094D}' => {
                w.delete(i);
                continue;
            }
            '\u{0929}' => '\u{0928}',
            '\u{0931}' => '\u{0930}',
            '\u{0934}' => '\u{0933}',
            '\u{0958}' => '\u{0915}',
            '\u{0959}' => '\u{0916}',
            '\u{095A}' => '\u{0917}',
            '\u{095B}' => '\u{091C}',
            '\u{095C}' => '\u{0921}',
            '\u{095D}' => '\u{0922}',
            '\u{095E}' => '\u{092B}',
            '\u{095F}' => '\u{092F}',
            '\u{0945}' | '\u{0946}' | '\u{0948}' => '\u{0947}',
            '\u{0949}' | '\u{094A}' | '\u{094C}' => '\u{094B}',
            '\u{090D}' | '\u{090E}' | '\u{0910}' => '\u{090F}',
            '\u{0911}' | '\u{0912}' | '\u{0914}' => '\u{0913}',
            '\u{0972}' | '\u{0906}' => '\u{0905}',
            '\u{0908}' => '\u{0907}',
            '\u{090A}' => '\u{0909}',
            '\u{0960}' => '\u{090B}',
            '\u{0961}' => '\u{090C}',
            '\u{0940}' => '\u{093F}',
            '\u{0942}' => '\u{0941}',
            '\u{0944}' => '\u{0943}',
            '\u{0963}' => '\u{0962}',
            _ => c,
        };
        w.s[i] = mapped;
        i += 1;
    }
}

fn remove_all(w: &mut Word, mark: char) {
    let mut i = 0;
    while i < w.len {
        if w.s[i] == mark {
            w.delete(i);
        } else {
            i += 1;
        }
    }
}

pub fn bengali(w: &mut Word) {
    // The nukta letters as one code point each, as the suffix tables write
    // them: য + ় is য়.
    let mut i = 1;
    while i < w.len {
        if w.s[i] == '\u{09BC}' {
            let composed = match w.s[i - 1] {
                '\u{09AF}' => Some('\u{09DF}'),
                '\u{09A1}' => Some('\u{09DC}'),
                '\u{09A2}' => Some('\u{09DD}'),
                _ => None,
            };
            if let Some(c) = composed {
                w.s[i - 1] = c;
                w.delete(i);
                continue;
            }
        }
        i += 1;
    }
    strip(w, BENGALI);
    let mut i = 0;
    while i < w.len {
        match w.s[i] {
            '\u{0981}' => {
                w.delete(i);
                continue;
            }
            // Ya-phala: ্য after a consonant.
            '\u{09CD}' if i + 1 < w.len && w.s[i + 1] == '\u{09AF}' && i > 0 => {
                w.delete(i + 1);
                w.delete(i);
                continue;
            }
            '\u{0983}' if i == w.len - 1 && w.len > 3 => {
                w.delete(i);
                continue;
            }
            '\u{09C0}' => w.s[i] = '\u{09BF}',
            '\u{09C2}' => w.s[i] = '\u{09C1}',
            '\u{0999}' => w.s[i] = '\u{0982}',
            '\u{09B6}' | '\u{09B7}' => w.s[i] = '\u{09B8}',
            '\u{09A3}' => w.s[i] = '\u{09A8}',
            '\u{09DC}' | '\u{09DD}' => w.s[i] = '\u{09B0}',
            '\u{09CE}' => w.s[i] = '\u{09A4}',
            _ => {}
        }
        i += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stem::{stem, Lang};

    fn same(lang: Lang, words: &[&str]) {
        let first = stem(lang, words[0]);
        for w in words {
            assert_eq!(stem(lang, w), first, "{lang:?} {w} vs {}", words[0]);
        }
    }

    fn suffix(rules: &[(usize, &[&str])], word: &str) -> String {
        let mut w = Word::new(word).unwrap();
        strip(&mut w, rules);
        w.as_string()
    }

    #[test]
    fn hindi_suffixes() {
        // Lucene's TestHindiStemmer, which runs the stemmer alone.
        for (word, want) in [
            ("लडका", "लडक"),
            ("लडके", "लडक"),
            ("लडकों", "लडक"),
            ("गुरु", "गुर"),
            ("गुरुओं", "गुर"),
            ("दोस्त", "दोस्त"),
            ("दोस्तों", "दोस्त"),
            ("लडकी", "लडक"),
            ("लडकियों", "लडक"),
            ("किताब", "किताब"),
            ("किताबें", "किताब"),
            ("किताबों", "किताब"),
            ("आध्यापीका", "आध्यापीक"),
            ("आध्यापीकाएं", "आध्यापीक"),
            ("आध्यापीकाओं", "आध्यापीक"),
            ("खाना", "खा"),
            ("खाता", "खा"),
            ("खाती", "खा"),
            ("खा", "खा"),
            ("कठिनाइयां", "कठिन"),
            ("कठिन", "कठिन"),
        ] {
            assert_eq!(suffix(HINDI, word), want, "{word}");
        }
    }

    #[test]
    fn hindi() {
        same(Lang::Hindi, &["लडका", "लडके", "लडकों"]);
        same(Lang::Hindi, &["लडकी", "लडकियों", "लड़कियाँ", "लडकियां"]);
        same(Lang::Hindi, &["अच्छा", "अच्छी", "अच्छे", "अच्छों"]);
        same(Lang::Hindi, &["जाएंगे", "जाएंगी"]);
        same(Lang::Hindi, &["दोस्त", "दोस्तों"]);
        // The stem comes out normalized: long vowels short, virama gone.
        assert_eq!(stem(Lang::Hindi, "आध्यापीकाएं"), "अधयापिक");
    }

    #[test]
    fn bengali_suffixes() {
        // Lucene's TestBengaliStemmer, which runs the stemmer alone.
        for (word, want) in [
            ("করেছিলাম", "কর"),
            ("করিতেছিলে", "কর"),
            ("খাইতাম", "খাই"),
            ("যাইবে", "যা"),
            ("করছিলাম", "কর"),
            ("করছিলে", "কর"),
            ("করতাম", "কর"),
            ("যাব", "যা"),
            ("যাবে", "যা"),
            ("করি", "কর"),
            ("করো", "কর"),
            ("মেয়েরা", "মে"),
            ("মেয়েদেরকে", "মে"),
            ("মেয়েদের", "মে"),
            ("একটি", "এক"),
            ("মানুষগুলি", "মানুষ"),
        ] {
            assert_eq!(suffix(BENGALI, word), want, "{word}");
        }
    }

    #[test]
    fn bengali() {
        same(Lang::Bengali, &["বই", "বইগুলো", "বইগুলি", "বইটি", "বইখানা"]);
        same(Lang::Bengali, &["মানুষ", "মানুষগুলি", "মানুষের"]);
        same(Lang::Bengali, &["মেয়েরা", "মেয়েদের", "মেয়েদেরকে"]);
        // য় typed as য + nukta is the same word, and ষ, শ fold onto স.
        same(Lang::Bengali, &["মেয়েরা", "মে\u{09AF}\u{09BC}েরা"]);
        assert_eq!(stem(Lang::Bengali, "মানুষগুলি"), "মানুস");
    }
}
