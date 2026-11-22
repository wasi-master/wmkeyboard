//! Greek: our own light stemmer. Accents and the final sigma folded, then
//! the longest common noun, adjective or verb ending that leaves a stem of
//! three letters: "λόγος", "λόγου", "λόγους" and "γράφω", "γράφεις",
//! "γράφουμε" meet. Lucene's `GreekStemmer` (after Ntais) adds a few
//! hundred exception words on top; search does not need them.

use super::Word;

/// Longest first, so "ουσαμε" is tried before "αμε" and "ε".
const ENDINGS: &[&str] = &[
    "ουδεσ", "ουδων", "αδεσ", "αδων", "ηδεσ", "ηδων", "εδεσ", "εδων",
    "ουσαμε", "ουσατε", "ουμαστε", "ιομαστε", "ιονται", "ησαμε", "ησατε", "ομαστε", "ουνται", "ουσαν", "ουσεσ",
    "μενοσ", "μενων", "μενεσ", "μενου", "μενοι", "οντασ", "ωντασ", "ιεμαι", "ονται", "ησαν",
    "ησεσ", "ουσε", "ουσα", "ουμε", "ουνε", "εσαι", "εται", "ομαι", "μενη", "μενο", "μενα", "ησε",
    "ησα", "ουν", "ετε", "ατε", "αμε", "ανε", "εισ", "ιων", "ιου", "ιεσ", "ιασ", "εων", "εωσ", "εια", "ειο",
    "ουσ", "οισ", "ων", "ου", "οσ", "οι", "ον", "ασ", "εσ", "ησ", "ει", "ια", "ιο", "α", "ε", "η", "ι", "ο", "ω",
    "υ",
];

fn fold(c: char) -> char {
    match c {
        'ά' => 'α',
        'έ' => 'ε',
        'ή' => 'η',
        'ί' | 'ϊ' | 'ΐ' => 'ι',
        'ό' => 'ο',
        'ύ' | 'ϋ' | 'ΰ' => 'υ',
        'ώ' => 'ω',
        'ς' => 'σ',
        _ => c,
    }
}

pub fn stem(w: &mut Word) {
    w.map(fold);
    // The neuter -μα nouns: "πρόβλημα", "προβλήματα", "προβλήματος" keep
    // the μ the singular ends in.
    for ending in ["ματων", "ματοσ", "ματα"] {
        let n = ending.chars().count();
        if w.len >= n + 3 && w.ends(ending) {
            return w.drop_end(n - 1);
        }
    }
    for ending in ENDINGS {
        let n = ending.chars().count();
        if w.len >= n + 3 && w.ends(ending) {
            return w.drop_end(n);
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    fn same(words: &[&str]) {
        let first = stem(Lang::Greek, words[0]);
        for w in words {
            assert_eq!(stem(Lang::Greek, w), first, "{w} vs {}", words[0]);
        }
    }

    #[test]
    fn forms_meet() {
        same(&["λόγος", "λόγου", "λόγο", "λόγοι", "λόγων", "λόγους"]);
        same(&["γράφω", "γράφεις", "γράφει", "γράφουμε", "γράφετε", "γράφουν"]);
        same(&["καλός", "καλή", "καλό", "καλοί", "καλές"]);
        same(&["πρόβλημα", "προβλήματα", "προβλήματος", "προβλημάτων"]);
        same(&["παιδί", "παιδιά", "παιδιού"]);
        // Lucene's TestGreekStemmer groups the light rules also meet on.
        same(&["άνθρωπος", "ανθρώπου", "άνθρωπο", "άνθρωπε", "άνθρωποι", "ανθρώπων", "ανθρώπους"]);
        same(&["πελάτης", "πελάτη", "πελάτες", "πελατών"]);
        same(&["ελέφαντας", "ελέφαντα", "ελέφαντες", "ελεφάντων"]);
        same(&["μπαμπάς", "μπαμπά", "μπαμπάδες", "μπαμπάδων"]);
        same(&["μπακάλης", "μπακάλη", "μπακάληδες", "μπακάληδων"]);
        same(&["καφές", "καφέ", "καφέδες", "καφέδων"]);
        same(&["παππούς", "παππού", "παππούδες", "παππούδων"]);
        assert_eq!(stem(Lang::Greek, "λόγος"), "λογ");
    }

    #[test]
    fn short_words_keep_three_letters() {
        assert_eq!(stem(Lang::Greek, "και"), "και");
        assert_eq!(stem(Lang::Greek, "γάτα"), "γατ");
    }
}
