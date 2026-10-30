//! Phonetic typing for the languages with no engine of their own, all rules
//! and no language-specific hooks: roman letters in, native script out. Each
//! language is a line of a table, so a spelling here is a reasonable first
//! guess, not a study of the language.
//!
//! - [`INDIC`] goes through the Indic engine on its plain models.
//! - [`arabic_table`] is Urdu's table with a language's own letters over it,
//!   except Pashto and Sindhi, whose letters differ too far for that.
//! - Yiddish, Mongolian and Santali in Ol Chiki are alphabets, read by
//!   [`alphabet`]: longest roman match wins, nothing joins.
//! - Manipuri in Meetei Mayek is [`meetei`], its own syllable walk, because
//!   the script's closing letters are not what the Indic engine's virama does.

use std::collections::HashMap;
use std::sync::OnceLock;

use crate::arabic_script::{self, Table, Vowel};
use crate::indic::{self, Model, Profile};
use crate::indic_script::IndicScript;
use crate::urdu;

/// The Indic engine's plain languages: id, script, model. North is for the
/// Indo-Aryan languages, whose inherent vowel drops; Explicit is for the
/// Dravidian, Munda and Tibeto-Burman ones, whose typists write every vowel.
pub const INDIC: &[(&str, IndicScript, Model)] = &[
    ("ahr", IndicScript::Devanagari, Model::North),
    ("awa", IndicScript::Devanagari, Model::North),
    ("bfy", IndicScript::Devanagari, Model::North),
    ("bgc", IndicScript::Devanagari, Model::North),
    ("bgq-Deva", IndicScript::Devanagari, Model::North),
    ("bhb", IndicScript::Devanagari, Model::North),
    ("bho", IndicScript::Devanagari, Model::North),
    ("bjj", IndicScript::Devanagari, Model::North),
    ("bns", IndicScript::Devanagari, Model::North),
    ("brx", IndicScript::Devanagari, Model::Explicit),
    ("dhd", IndicScript::Devanagari, Model::North),
    ("doi", IndicScript::Devanagari, Model::North),
    ("gbm", IndicScript::Devanagari, Model::North),
    ("gdx", IndicScript::Devanagari, Model::North),
    ("gju-Deva", IndicScript::Devanagari, Model::North),
    ("gno", IndicScript::Devanagari, Model::Explicit),
    ("hlb", IndicScript::Devanagari, Model::North),
    ("hne", IndicScript::Devanagari, Model::North),
    ("hoj", IndicScript::Devanagari, Model::North),
    ("kfq", IndicScript::Devanagari, Model::Explicit),
    ("knn", IndicScript::Devanagari, Model::North),
    ("kok", IndicScript::Devanagari, Model::North),
    ("kru", IndicScript::Devanagari, Model::Explicit),
    ("ks-Deva", IndicScript::Devanagari, Model::North),
    ("lmn", IndicScript::Devanagari, Model::North),
    ("mag", IndicScript::Devanagari, Model::North),
    ("mai", IndicScript::Devanagari, Model::North),
    ("mjl", IndicScript::Devanagari, Model::North),
    ("mtr", IndicScript::Devanagari, Model::North),
    ("mup", IndicScript::Devanagari, Model::North),
    ("noe", IndicScript::Devanagari, Model::North),
    ("rkt-Deva", IndicScript::Devanagari, Model::North),
    ("rwr", IndicScript::Devanagari, Model::North),
    ("sck", IndicScript::Devanagari, Model::North),
    ("sd-Deva", IndicScript::Devanagari, Model::North),
    ("sjp", IndicScript::Devanagari, Model::North),
    ("swv", IndicScript::Devanagari, Model::North),
    ("vah", IndicScript::Devanagari, Model::North),
    ("wbr", IndicScript::Devanagari, Model::North),
    ("wry", IndicScript::Devanagari, Model::North),
    ("xnr", IndicScript::Devanagari, Model::North),
    ("bpy", IndicScript::Bengali, Model::North),
    ("brx-Beng", IndicScript::Bengali, Model::Explicit),
    ("ctg", IndicScript::Bengali, Model::North),
    ("grt-Beng", IndicScript::Bengali, Model::Explicit),
    ("mni-Beng", IndicScript::Bengali, Model::Explicit),
    ("sat-Beng", IndicScript::Bengali, Model::Explicit),
    ("trp", IndicScript::Bengali, Model::Explicit),
    ("unr", IndicScript::Bengali, Model::Explicit),
    ("bhb-Gujr", IndicScript::Gujarati, Model::North),
    ("hlb-Orya", IndicScript::Oriya, Model::North),
    ("kok-Knda", IndicScript::Kannada, Model::North),
    ("lmn-Knda", IndicScript::Kannada, Model::North),
    ("tcy", IndicScript::Kannada, Model::Explicit),
    ("gno-Telu", IndicScript::Telugu, Model::Explicit),
    ("lmn-Telu", IndicScript::Telugu, Model::North),
];

/// The languages only [`transliterate`] reads, as opposed to [`INDIC`]'s.
pub const OTHERS: &[&str] = &[
    "bcc", "bft", "bgp", "bgq", "brh", "dcc", "doi-Arab", "gju", "hnd", "hno", "ks", "mve", "phr", "ps", "scl", "sd",
    "skr", "yi", "mn", "sat", "mni",
];

/// The Indic engine for one of [`INDIC`]'s languages.
pub fn indic_engine(language_id: &str) -> Option<&'static indic::Engine> {
    static ENGINES: OnceLock<HashMap<&'static str, indic::Engine>> = OnceLock::new();
    ENGINES
        .get_or_init(|| {
            INDIC
                .iter()
                .map(|&(id, script, model)| {
                    let mut profile = Profile::new(id, script, model);
                    // ड़ and ढ़, which every one of these writes, and the ळ of
                    // the western ones. Capitals, as Gujarati's profile has them.
                    profile.extra_consonants =
                        vec![("L", "\u{933}"), ("R", "\u{921}\u{93C}"), ("Rh", "\u{922}\u{93C}")];
                    (id, indic::Engine::new(profile))
                })
                .collect()
        })
        .get(language_id)
}

/// Transliterates roman text in a language of [`OTHERS`]. Any other id passes
/// the input through.
pub fn transliterate(language_id: &str, input: &str) -> String {
    if let Some(engine) = arabic_engine(language_id) {
        return engine.transliterate(input);
    }
    match language_id {
        "yi" => alphabet(YIDDISH, input, yiddish_word),
        "mn" => alphabet(MONGOLIAN, input, |word, first_upper| capitalized(word, first_upper)),
        "sat" => alphabet(OL_CHIKI, input, |word, _| word),
        "mni" => meetei(input),
        _ => input.to_string(),
    }
}

/// Every reading worth offering, the likely one first.
pub fn variants(language_id: &str, input: &str) -> Vec<String> {
    match arabic_engine(language_id) {
        Some(engine) => engine.variants(input),
        None => vec![transliterate(language_id, input)],
    }
}

// ---- Arabic script ----------------------------------------------------------

fn arabic_engine(language_id: &str) -> Option<&'static arabic_script::Engine> {
    static ENGINES: OnceLock<HashMap<&'static str, arabic_script::Engine>> = OnceLock::new();
    ENGINES
        .get_or_init(|| {
            OTHERS
                .iter()
                .filter_map(|&id| arabic_table(id).map(|table| (id, arabic_script::Engine::new(table))))
                .collect()
        })
        .get(language_id)
}

/// Urdu's table with `letters` read ahead of its own, which wins a tie.
fn over_urdu(letters: &[(&'static str, &'static str)]) -> Table {
    let mut table = urdu::table();
    let mut consonants = letters.to_vec();
    consonants.append(&mut table.consonants);
    table.consonants = consonants;
    table
}

pub fn arabic_table(language_id: &str) -> Option<Table> {
    Some(match language_id {
        "bcc" | "bgp" | "bgq" | "brh" | "dcc" | "doi-Arab" | "gju" | "hnd" | "hno" | "mve" | "phr" => {
            over_urdu(&[])
        }
        // Balti and Shina: Urdu's letters with the sounds Urdu has none for.
        "bft" => over_urdu(&[("ts", "څ"), ("zh", "ژ")]),
        "scl" => over_urdu(&[("ts", "څ"), ("Sh", "ݜ"), ("Zh", "ݫ"), ("Ch", "ݘ"), ("N", "ݨ")]),
        // Saraiki's implosives, written doubled the way Urdu's typists double.
        "skr" => over_urdu(&[("bb", "ٻ"), ("jj", "ڄ"), ("dd", "ݙ"), ("gg", "ڳ"), ("N", "ݨ")]),
        // Kashmiri's ts and tsh.
        "ks" => over_urdu(&[("tsh", "ژھ"), ("ts", "ژ"), ("zh", "ژ")]),
        "ps" => pashto(),
        "sd" => sindhi(),
        _ => return None,
    })
}

fn pashto() -> Table {
    let mut table = Table::new(
        '\u{6CC}',
        Some('\u{6D0}'),
        vec![
            ("kh", "خ"),
            ("gh", "غ"),
            ("ch", "چ"),
            ("sh", "ش"),
            ("Sh", "ښ"),
            ("zh", "ژ"),
            ("Zh", "ږ"),
            ("ts", "څ"),
            ("dz", "ځ"),
            ("b", "ب"),
            ("p", "پ"),
            ("t", "ت"),
            ("T", "ټ"),
            ("s", "س"),
            ("j", "ج"),
            ("h", "ه"),
            ("d", "د"),
            ("D", "ډ"),
            ("z", "ز"),
            ("r", "ر"),
            ("R", "ړ"),
            ("f", "ف"),
            ("q", "ق"),
            ("k", "ک"),
            ("g", "ګ"),
            ("l", "ل"),
            ("m", "م"),
            ("n", "ن"),
            ("N", "ڼ"),
            ("v", "و"),
            ("w", "و"),
            ("y", "ی"),
            ("x", "ښ"),
            ("c", "ک"),
            ("'", "ع"),
        ],
        vec![
            Vowel::new("aa", "آ", "ا", "ا"),
            Vowel::new("A", "آ", "ا", "ا"),
            Vowel::new("a", "ا", "", "ه"),
            Vowel::new("ee", "ای", "ی", "ی"),
            Vowel::new("ii", "ای", "ی", "ی"),
            Vowel::new("ai", "ای", "ی", "ی"),
            Vowel::new("i", "ا", "", "ی"),
            Vowel::new("oo", "او", "و", "و"),
            Vowel::new("uu", "او", "و", "و"),
            Vowel::new("u", "ا", "", "و"),
            Vowel::new("au", "او", "و", "و"),
            Vowel::new("ou", "او", "و", "و"),
            Vowel::new("ey", "ای", "ی", "ې"),
            Vowel::new("e", "ای", "ې", "ې"),
            Vowel::new("o", "او", "و", "و"),
        ],
    );
    table.hamza_ye = '\u{626}';
    table
}

fn sindhi() -> Table {
    Table::new(
        '\u{64A}',
        None,
        vec![
            ("bh", "ڀ"),
            ("bb", "ٻ"),
            ("th", "ٿ"),
            ("Th", "ٺ"),
            ("ph", "ڦ"),
            ("jh", "جھ"),
            ("jj", "ڄ"),
            ("chh", "ڇ"),
            ("ch", "چ"),
            ("dh", "ڌ"),
            ("Dh", "ڍ"),
            ("dd", "ڏ"),
            ("kh", "ک"),
            ("Kh", "خ"),
            ("gh", "گھ"),
            ("Gh", "غ"),
            ("gg", "ڳ"),
            ("ng", "ڱ"),
            ("ny", "ڃ"),
            ("sh", "ش"),
            ("b", "ب"),
            ("p", "پ"),
            ("t", "ت"),
            ("T", "ٽ"),
            ("s", "س"),
            ("j", "ج"),
            ("h", "ه"),
            ("d", "د"),
            ("D", "ڊ"),
            ("z", "ز"),
            ("r", "ر"),
            ("R", "ڙ"),
            ("f", "ف"),
            ("q", "ق"),
            ("k", "ڪ"),
            ("g", "گ"),
            ("l", "ل"),
            ("m", "م"),
            ("n", "ن"),
            ("N", "ڻ"),
            ("v", "و"),
            ("w", "و"),
            ("y", "ي"),
            ("c", "ڪ"),
            ("'", "ع"),
        ],
        vec![
            Vowel::new("aa", "آ", "ا", "ا"),
            Vowel::new("A", "آ", "ا", "ا"),
            Vowel::new("a", "ا", "", "ا"),
            Vowel::new("ee", "اي", "ي", "ي"),
            Vowel::new("ii", "اي", "ي", "ي"),
            Vowel::new("ai", "اي", "ي", "ي"),
            Vowel::new("i", "ا", "", "ي"),
            Vowel::new("oo", "او", "و", "و"),
            Vowel::new("uu", "او", "و", "و"),
            Vowel::new("u", "ا", "", "و"),
            Vowel::new("au", "او", "و", "و"),
            Vowel::new("ou", "او", "و", "و"),
            Vowel::new("ey", "اي", "ي", "ي"),
            Vowel::new("e", "اي", "ي", "ي"),
            Vowel::new("o", "او", "و", "و"),
        ],
    )
}

// ---- Alphabets --------------------------------------------------------------

type Pairs = &'static [(&'static str, &'static str)];

/// Reads each run of ASCII letters by longest match, an exact-case match
/// before a lowercase one, then hands the word to `finish` with whether it
/// opened on a capital. Everything else passes through.
fn alphabet(pairs: Pairs, input: &str, finish: impl Fn(String, bool) -> String) -> String {
    let chars: Vec<char> = input.chars().collect();
    let longest = pairs.iter().map(|(m, _)| m.chars().count()).max().unwrap_or(1);
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < chars.len() {
        if !chars[i].is_ascii_alphabetic() {
            out.push(chars[i]);
            i += 1;
            continue;
        }
        let first_upper = chars[i].is_ascii_uppercase();
        let mut word = String::new();
        while i < chars.len() && chars[i].is_ascii_alphabetic() {
            let mut matched = false;
            for len in (1..=longest.min(chars.len() - i)).rev() {
                let piece: String = chars[i..i + len].iter().collect();
                let hit = pairs
                    .iter()
                    .find(|(m, _)| *m == piece)
                    .or_else(|| pairs.iter().find(|(m, _)| *m == piece.to_ascii_lowercase()));
                if let Some((_, value)) = hit {
                    word.push_str(value);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if !matched {
                i += 1;
            }
        }
        out.push_str(&finish(word, first_upper));
    }
    out
}

fn capitalized(word: String, first_upper: bool) -> String {
    if !first_upper {
        return word;
    }
    let mut chars = word.chars();
    match chars.next() {
        Some(c) => c.to_uppercase().chain(chars).collect(),
        None => word,
    }
}

/// YIVO's romanization, with the digraphs written out.
const YIDDISH: Pairs = &[
    ("tsh", "טש"),
    ("zsh", "זש"),
    ("dzh", "דזש"),
    ("sh", "ש"),
    ("zh", "זש"),
    ("kh", "כ"),
    ("ts", "צ"),
    ("ay", "ײַ"),
    ("ey", "ײ"),
    ("oy", "ױ"),
    ("a", "אַ"),
    ("b", "ב"),
    ("c", "ק"),
    ("d", "ד"),
    ("e", "ע"),
    ("f", "פֿ"),
    ("g", "ג"),
    ("h", "ה"),
    ("i", "י"),
    ("j", "י"),
    ("k", "ק"),
    ("l", "ל"),
    ("m", "מ"),
    ("n", "נ"),
    ("o", "אָ"),
    ("p", "פּ"),
    ("q", "ק"),
    ("r", "ר"),
    ("s", "ס"),
    ("t", "ט"),
    ("u", "ו"),
    ("v", "װ"),
    ("w", "װ"),
    ("x", "קס"),
    ("y", "י"),
    ("z", "ז"),
];

/// A word cannot open on these without a silent alef, and five letters have a
/// form of their own at its end.
fn yiddish_word(word: String, _first_upper: bool) -> String {
    let mut word = match word.chars().next() {
        Some('ו' | 'י' | 'ױ' | 'ײ') => format!("א{word}"),
        _ => word,
    };
    let finals = [("כ", "ך"), ("מ", "ם"), ("נ", "ן"), ("צ", "ץ"), ("פֿ", "ף")];
    for (plain, last) in finals {
        if let Some(stem) = word.strip_suffix(plain) {
            word = format!("{stem}{last}");
            break;
        }
    }
    word
}

/// The way Mongolian chat writes Cyrillic: q is ө and v is ү.
const MONGOLIAN: Pairs = &[
    ("ya", "я"),
    ("yu", "ю"),
    ("ye", "е"),
    ("yo", "ё"),
    ("ts", "ц"),
    ("ch", "ч"),
    ("sh", "ш"),
    ("kh", "х"),
    ("oe", "ө"),
    ("ue", "ү"),
    ("ii", "ий"),
    ("ai", "ай"),
    ("oi", "ой"),
    ("ui", "уй"),
    ("a", "а"),
    ("b", "б"),
    ("c", "ц"),
    ("d", "д"),
    ("e", "э"),
    ("f", "ф"),
    ("g", "г"),
    ("h", "х"),
    ("i", "и"),
    ("j", "ж"),
    ("k", "к"),
    ("l", "л"),
    ("m", "м"),
    ("n", "н"),
    ("o", "о"),
    ("p", "п"),
    ("q", "ө"),
    ("r", "р"),
    ("s", "с"),
    ("t", "т"),
    ("u", "у"),
    ("v", "ү"),
    ("w", "в"),
    ("x", "кс"),
    ("y", "ы"),
    ("z", "з"),
];

/// Santali's Ol Chiki. Capitals are the retroflexes, as on the Indic layouts;
/// an aspirate is its stop and the ᱷ.
const OL_CHIKI: Pairs = &[
    ("kh", "ᱠᱷ"),
    ("gh", "ᱜᱷ"),
    ("chh", "ᱪᱷ"),
    ("ch", "ᱪ"),
    ("jh", "ᱡᱷ"),
    ("th", "ᱛᱷ"),
    ("Th", "ᱴᱷ"),
    ("dh", "ᱫᱷ"),
    ("Dh", "ᱰᱷ"),
    ("ph", "ᱯᱷ"),
    ("bh", "ᱵᱷ"),
    ("ng", "ᱝ"),
    ("ny", "ᱧ"),
    ("oo", "ᱳ"),
    ("a", "ᱟ"),
    ("b", "ᱵ"),
    ("c", "ᱪ"),
    ("d", "ᱫ"),
    ("D", "ᱰ"),
    ("e", "ᱮ"),
    ("g", "ᱜ"),
    ("h", "ᱦ"),
    ("i", "ᱤ"),
    ("j", "ᱡ"),
    ("k", "ᱠ"),
    ("l", "ᱞ"),
    ("m", "ᱢ"),
    ("n", "ᱱ"),
    ("N", "ᱬ"),
    ("o", "ᱚ"),
    ("p", "ᱯ"),
    ("r", "ᱨ"),
    ("R", "ᱲ"),
    ("s", "ᱥ"),
    ("t", "ᱛ"),
    ("T", "ᱴ"),
    ("u", "ᱩ"),
    ("v", "ᱶ"),
    ("w", "ᱣ"),
    ("y", "ᱭ"),
];

// ---- Meetei Mayek -----------------------------------------------------------

const MEETEI_CONSONANTS: &[(&str, char)] = &[
    ("kh", 'ꯈ'),
    ("gh", 'ꯘ'),
    ("ng", 'ꯉ'),
    ("ch", 'ꯆ'),
    ("jh", 'ꯓ'),
    ("th", 'ꯊ'),
    ("dh", 'ꯙ'),
    ("ph", 'ꯐ'),
    ("bh", 'ꯚ'),
    ("sh", 'ꯁ'),
    ("k", 'ꯀ'),
    ("g", 'ꯒ'),
    ("c", 'ꯆ'),
    ("j", 'ꯖ'),
    ("t", 'ꯇ'),
    ("d", 'ꯗ'),
    ("n", 'ꯅ'),
    ("p", 'ꯄ'),
    ("f", 'ꯐ'),
    ("b", 'ꯕ'),
    ("m", 'ꯃ'),
    ("y", 'ꯌ'),
    ("r", 'ꯔ'),
    ("l", 'ꯂ'),
    ("w", 'ꯋ'),
    ("v", 'ꯋ'),
    ("s", 'ꯁ'),
    ("h", 'ꯍ'),
];

/// Roman vowel, its form opening a syllable, its sign after a consonant.
const MEETEI_VOWELS: &[(&str, &str, &str)] = &[
    ("aa", "ꯑꯥ", "\u{ABE5}"),
    ("ai", "ꯑꯩ", "\u{ABE9}"),
    ("ei", "ꯑꯩ", "\u{ABE9}"),
    ("ou", "ꯑꯧ", "\u{ABE7}"),
    ("au", "ꯑꯧ", "\u{ABE7}"),
    ("ee", "ꯏ", "\u{ABE4}"),
    ("oo", "ꯎ", "\u{ABE8}"),
    ("a", "ꯑ", ""),
    ("i", "ꯏ", "\u{ABE4}"),
    ("u", "ꯎ", "\u{ABE8}"),
    ("e", "ꯑꯦ", "\u{ABE6}"),
    ("o", "ꯑꯣ", "\u{ABE3}"),
];

const APUN: char = '\u{ABED}';

/// The letters that close a syllable with no vowel, which are their own forms.
fn lonsum(c: char) -> Option<char> {
    Some(match c {
        'ꯀ' => 'ꯛ',
        'ꯂ' => 'ꯜ',
        'ꯃ' => 'ꯝ',
        'ꯄ' => 'ꯞ',
        'ꯅ' => 'ꯟ',
        'ꯇ' => 'ꯠ',
        'ꯉ' => 'ꯡ',
        _ => return None,
    })
}

fn meetei(input: &str) -> String {
    let chars: Vec<char> = input.to_lowercase().chars().collect();
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    let read = |table: &[&'static str], at: usize| -> Option<&'static str> {
        table.iter().copied().find(|m| {
            let m: Vec<char> = m.chars().collect();
            at + m.len() <= chars.len() && chars[at..at + m.len()] == m[..]
        })
    };
    let consonants: Vec<&'static str> = MEETEI_CONSONANTS.iter().map(|c| c.0).collect();
    let vowels: Vec<&'static str> = MEETEI_VOWELS.iter().map(|v| v.0).collect();
    while i < chars.len() {
        if let Some(m) = read(&consonants, i) {
            let letter = MEETEI_CONSONANTS.iter().find(|c| c.0 == m).unwrap().1;
            i += m.chars().count();
            if let Some(v) = read(&vowels, i) {
                let sign = MEETEI_VOWELS.iter().find(|x| x.0 == v).unwrap().2;
                out.push(letter);
                out.push_str(sign);
                i += v.chars().count();
            } else {
                let at_end = i >= chars.len() || !chars[i].is_ascii_alphabetic();
                match lonsum(letter) {
                    Some(close) => out.push(close),
                    None if at_end => out.push(letter),
                    None => {
                        out.push(letter);
                        out.push(APUN);
                    }
                }
            }
        } else if let Some(v) = read(&vowels, i) {
            out.push_str(MEETEI_VOWELS.iter().find(|x| x.0 == v).unwrap().1);
            i += v.chars().count();
        } else {
            // Only an unread ASCII letter is dropped; anything else is typed text.
            if !chars[i].is_ascii_alphabetic() {
                out.push(chars[i]);
            }
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn indic(id: &str, input: &str) -> String {
        indic_engine(id).unwrap().transliterate(input)
    }

    #[test]
    fn every_indic_language_has_its_engine_and_script() {
        for &(id, script, _) in INDIC {
            let engine = indic_engine(id).expect(id);
            assert_eq!(id, engine.profile.language_id);
            let out = engine.transliterate("kamal");
            assert!(out.chars().all(|c| script.is_native(c)), "{id}: {out}");
        }
    }

    #[test]
    fn the_scripts_come_out_right() {
        assert_eq!("कमल", indic("mai", "kamal"));
        assert_eq!("কমল", indic("ctg", "kamal"));
        assert_eq!("કમલ", indic("bhb-Gujr", "kamal"));
        assert_eq!("କମଲ", indic("hlb-Orya", "kamal"));
        assert_eq!("ಕಮಲ", indic("kok-Knda", "kamal"));
        assert_eq!("కమల", indic("lmn-Telu", "kamal"));
    }

    #[test]
    fn every_other_language_writes_its_script() {
        for &id in OTHERS {
            let out = transliterate(id, "salam");
            assert!(!out.is_empty() && !out.chars().any(|c| c.is_ascii_alphabetic()), "{id}: {out}");
        }
    }

    #[test]
    fn arabic_languages_use_their_own_letters() {
        assert_eq!("سلام", transliterate("skr", "salaam"));
        assert!(transliterate("ps", "paxto").contains('ښ'));
        assert!(transliterate("ps", "ghaTa").contains('ټ'));
        assert!(transliterate("sd", "kam").contains('ڪ'));
        assert!(transliterate("sd", "ddar").contains('ڏ'));
        assert!(transliterate("skr", "ddar").contains('ݙ'));
        assert!(transliterate("ks", "tsar").contains('ژ'));
    }

    #[test]
    fn yiddish_takes_its_alef_and_final_forms() {
        assert_eq!("מאַן", transliterate("yi", "man"));
        assert_eq!("אױג", transliterate("yi", "oyg"));
        assert_eq!("שלום", transliterate("yi", "shlum"));
    }

    #[test]
    fn mongolian_reads_chat_spelling_and_keeps_a_capital() {
        assert_eq!("сайн", transliterate("mn", "sain"));
        assert_eq!("Монгол", transliterate("mn", "Mongol"));
        assert_eq!("өдөр", transliterate("mn", "qdqr"));
    }

    #[test]
    fn ol_chiki_is_letter_for_letter() {
        assert_eq!("ᱥᱟᱱᱛᱟᱞᱤ", transliterate("sat", "santali"));
    }

    #[test]
    fn meetei_closes_syllables_with_its_own_letters() {
        assert_eq!("ꯃꯅꯤꯄꯨꯔ", transliterate("mni", "manipur"));
        assert_eq!("ꯑꯃꯃꯥ", transliterate("mni", "amamaa"));
        assert_eq!("ꯀꯟꯕ", transliterate("mni", "kanba"));
    }

    #[test]
    fn text_that_is_not_letters_passes_through() {
        assert_eq!("۲۰, ", transliterate("zz", "۲۰, "));
        assert_eq!("סאַ 12", transliterate("yi", "sa 12"));
    }
}
