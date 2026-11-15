//! Nepali phonetic: roman letters in, Devanagari out, on the Indic engine's
//! North model, for Nepali the way it is typed in a chat ("k xa", "ramro
//! cha", "vayo") and the way Nepali spells it (issue #1340).
//!
//! The profile itself differs from Hindi's in four places, each read off the
//! romanized Nepali corpus (`ne_rom`) and the Nepali word list (`ne_full`):
//!
//! - **x is छ and v is भ.** "xa" outnumbers "chha" nine to one, "vayo" beats
//!   "bhayo" and "vanda" "bhanda"; jquery.ime's Nepali transliteration reads
//!   v the same way. w stays व.
//! - **A closing "a" is the inherent vowel**, not आ: छ, गर्न, किन, मात्र.
//!   Only मा, दा and था close long often enough to be the default (85, 73 and
//!   80 per cent of the list's words), which the hook says.
//! - **A closing "u" stays short** (गर्नु, छु, दाजु), and so does nothing
//!   else change length: हरू is the one long ू a chat spells "u", and the
//!   hook knows it.
//! - **A closing "ai" is ै** (सबै, धेरै, गर्दै), never Hindi's ाई.
//!
//! Nepali writes a nasal before a stop as a conjunct with the nasal of the
//! stop's own row (सङ्ख्या, पञ्च, भन्दा, सम्झना), not as Hindi's anusvara,
//! and any consonant joins a र after it (राम्रो, तिम्रो, मित्र).
//!
//! The hook's rules, in the order they run:
//!
//! 1. Postpositions and case endings glued to a word keep their own spelling,
//!    and the stem never joins them ("gharma" घरमा, "ramlai" रामलाई, "gharbata"
//!    घरबाट, "timisanga" तिमीसँग).
//! 2. The plural हरू, wherever it sits ("manchheharu", "haruko").
//! 3. A verb stem joins its ending: the present (गर्छ, हुन्छ, जान्छु), the
//!    negative (गर्दैन, सक्दिन), the progressive (गर्दै), the habitual past
//!    (गर्थ्यो), the infinitive and its honorifics (गर्नु, गर्नुहोस्,
//!    गर्नुहुन्छ). "ch" there is छ: "garcha" and "huncha" are how most people
//!    write गर्छ and हुन्छ. An ending that closes on न or स takes a halanta
//!    (गर्छन्, गर्नुस्).
//! 4. A nasal before a stop in the same word is a conjunct with that row's
//!    nasal; before a sibilant or ह it is the anusvara (संसार, सिंह).
//! 5. A closing "da", "tha" or "ma" is long (गर्दा, भन्दा, कथा).
//! 6. The verb forms that are a word of their own: छ, छन्, छु, छैन typed
//!    "cha", "chan"; होस्; the optative गरोस्.
//! 7. "gy" opening a word is ज्ञ (ज्ञान).

use std::sync::OnceLock;

use crate::indic::hooks::has_vowel;
use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const A: &str = "\u{905}";
const AA: &str = "\u{906}";
const I: &str = "\u{907}";
const II: &str = "\u{908}";
const U: &str = "\u{909}";
const UU: &str = "\u{90A}";
const E: &str = "\u{90F}";
const AI: &str = "\u{910}";
const O: &str = "\u{913}";
const AA_SIGN: &str = "\u{93E}";
const I_SIGN: &str = "\u{93F}";
const II_SIGN: &str = "\u{940}";
const U_SIGN: &str = "\u{941}";
const UU_SIGN: &str = "\u{942}";
const E_SIGN: &str = "\u{947}";
const AI_SIGN: &str = "\u{948}";
const O_SIGN: &str = "\u{94B}";
/// The ाई a postposition closes on (लाई).
const AAI_SIGN: &str = "\u{93E}\u{908}";
const AAI: &str = "\u{906}\u{908}";

const KA: &str = "\u{915}";
const KHA: &str = "\u{916}";
const GA: &str = "\u{917}";
const CA: &str = "\u{91A}";
const CHA: &str = "\u{91B}";
const JA: &str = "\u{91C}";
const NYA_JNA: &str = "\u{91C}\u{94D}\u{91E}";
const TTA: &str = "\u{91F}";
const DA: &str = "\u{926}";
const THA: &str = "\u{925}";
const NA: &str = "\u{928}";
const PA: &str = "\u{92A}";
const BA: &str = "\u{92C}";
const BHA: &str = "\u{92D}";
const MA: &str = "\u{92E}";
const LA: &str = "\u{932}";
const SA: &str = "\u{938}";
const VIRAMA: &str = "\u{94D}";
const ANUSVARA: &str = "\u{902}";
const CHANDRABINDU: &str = "\u{901}";

/// Case endings and postpositions a stem never joins: their first consonant
/// starts a new syllable ("gharma" is घरमा, not घर्मा).
const ENDINGS: &[&str] = &[
    "bata", "bhanda", "bhitra", "dekhi", "haru", "harubata", "haruka", "haruki", "haruko", "harulai", "harule",
    "haruma", "jasta", "jasti", "jasto", "ka", "ki", "ko", "lagi", "lai", "le", "ma", "mathi", "muni", "pachhi",
    "pachi", "paxi", "samma", "sanga", "sangai", "tira", "vanda",
];

/// One letter of a spelled ending: a consonant, a vowel sign, or a sign that
/// stands where a typed letter was (the ँ of सँग, typed "n").
#[derive(Clone, Copy)]
enum Spell {
    C(&'static str),
    V(&'static str),
    S(&'static str),
}

use Spell::{C, S, V};

/// Rule 1: the endings whose spelling the rules would get wrong, token for token.
const SPELLED_ENDINGS: &[(&str, &[Spell])] = &[
    ("ma", &[C(MA), V(AA_SIGN)]),
    ("ka", &[C(KA), V(AA_SIGN)]),
    ("lai", &[C(LA), V(AAI_SIGN)]),
    ("bata", &[C(BA), V(AA_SIGN), C(TTA), V("")]),
    ("dekhi", &[C(DA), V(E_SIGN), C(KHA), V(I_SIGN)]),
    ("lagi", &[C(LA), V(AA_SIGN), C(GA), V(I_SIGN)]),
    ("pachhi", &[C(PA), V(""), C(CHA), V(I_SIGN)]),
    ("pachi", &[C(PA), V(""), C(CHA), V(I_SIGN)]),
    ("paxi", &[C(PA), V(""), C(CHA), V(I_SIGN)]),
    ("sanga", &[C(SA), V(""), S(CHANDRABINDU), C(GA), V("")]),
    ("sangai", &[C(SA), V(""), S(CHANDRABINDU), C(GA), V(AI_SIGN)]),
];

/// Rule 3: what may follow a verb stem's last consonant, "ch" and "x" written
/// "chh". Chains are listed whole, so the stem joins only when everything
/// after it is verb ending; the joins inside a chain ("hunchha" in
/// "garnuhunchha") match on their own.
const VERB_SUFFIXES: &[&str] = &[
    // Present.
    "chha", "chhu", "chhau", "chhas", "chhe", "chhin", "chhan", "chhyau", "chhaun",
    // Negative present.
    "daina", "dainan", "dainau", "dainas", "dina", "dinan", "dinau", "dinas",
    // Progressive, and the formal present.
    "dai", "da", "do", "di", "dachha", "dachhu", "dachhan", "dachhau", "dachhin", "dachhe",
    // Habitual past.
    "thyo", "the", "thin", "thyau", "thyaun", "this",
    // Infinitive, participles and the honorific forms built on them.
    "nu", "ne", "na", "nus", "nuhos", "nuhola", "nuhune", "nuhuna", "nuhunchha", "nuhunchhau", "nuhunna",
    "nuhunthyo", "nuparchha", "nuparne", "nuparyo", "nupardaina",
];

/// Rule 3: the endings that close on a consonant written with a halanta.
const HALANTA_SUFFIXES: &[&str] = &[
    "chhan", "chhin", "chhas", "thin", "this", "dainan", "dinan", "dainas", "dinas", "nus", "nuhos", "dachhan",
    "dachhin",
];

/// Rule 6: the verb forms that are words of their own, "ch" read as छ.
const STANDALONE: &[&str] = &[
    "chha", "chhan", "chhu", "chhau", "chhin", "chhas", "chhe", "chhaina", "chhainan", "chhaun", "chhyau",
];

/// Rule 6: of those, the ones closing on a halanta.
const STANDALONE_HALANTA: &[&str] = &["chhan", "chhin", "chhas", "chhainan"];

pub fn profile() -> Profile {
    let mut p = Profile::new("ne", IndicScript::Devanagari, Model::North);
    p.override_ = vec![("x", CHA), ("v", BHA), ("z", JA)];
    // A closing "a" and "u" stay short: the base rules carry a long twin
    // for them, and these replace them without one.
    p.extra_vowels = vec![("a", A, ""), ("u", U, U_SIGN)];
    p.anusvara = false;
    p.join_r = true;
    p.closing_aai = false;
    p.endings = ENDINGS.to_vec();
    p.word_hook = Some(hook);
    p
}

pub fn engine() -> &'static Engine {
    static ENGINE: OnceLock<Engine> = OnceLock::new();
    ENGINE.get_or_init(|| Engine::new(profile()))
}

pub fn transliterate(input: &str) -> String {
    engine().transliterate(input)
}

pub fn variants(input: &str) -> Vec<String> {
    engine().variants(input)
}

/// A vowel sign's independent form.
fn vowel_full(sign: &str) -> &'static str {
    match sign {
        "" => A,
        AA_SIGN => AA,
        I_SIGN => I,
        II_SIGN => II,
        U_SIGN => U,
        UU_SIGN => UU,
        E_SIGN => E,
        AI_SIGN => AI,
        O_SIGN => O,
        AAI_SIGN => AAI,
        _ => panic!("no independent form for sign {sign:?}"),
    }
}

fn vowel(m: &str, sign: &str) -> Rule {
    Rule::vowel(m, vowel_full(sign), sign)
}

/// The tokens as typed, lowercased, with every spelling of छ written "chh".
fn suffix_key(tokens: &[Rule]) -> String {
    tokens
        .iter()
        .map(|t| {
            let m = t.m.to_lowercase();
            if t.is_consonant() && (m == "x" || (m == "ch" && t.full == CA)) {
                "chh".to_string()
            } else {
                m
            }
        })
        .collect()
}

/// Makes every च typed "ch" in `tokens` a छ.
fn chh(tokens: &mut [Rule]) {
    for t in tokens {
        if t.is_consonant() && t.full == CA && t.m.to_lowercase() == "ch" {
            *t = Rule::consonant(&t.m.clone(), CHA);
        }
    }
}

/// Where a recognised ending starts, if the word closes on one after a stem.
fn ending_start(tokens: &[Rule]) -> Option<usize> {
    let n = tokens.len();
    let mut best: Option<usize> = None;
    let mut spelled = String::new();
    for from in (1..n).rev() {
        spelled.insert_str(0, &tokens[from].m.to_lowercase());
        if spelled.len() > 8 {
            break;
        }
        // A consonant doubled across the boundary is one word's ("samma"
        // सम्म), not a stem and its "ma".
        let doubled = tokens[from - 1].is_consonant() && tokens[from - 1].full == tokens[from].full;
        if ENDINGS.contains(&spelled.as_str()) && has_vowel(&tokens[..from]) && !doubled {
            best = Some(from);
        }
    }
    best
}

/// The nasal of the row a stop belongs to, or None for anything that is not a stop.
fn row_nasal(stop: &str) -> Option<&'static str> {
    let c = stop.chars().next()?;
    Some(match c {
        'क' | 'ख' | 'ग' | 'घ' => "\u{919}",
        'च' | 'छ' | 'ज' | 'झ' => "\u{91E}",
        'ट' | 'ठ' | 'ड' | 'ढ' => "\u{923}",
        'त' | 'थ' | 'द' | 'ध' => NA,
        'प' | 'फ' | 'ब' | 'भ' => MA,
        _ => return None,
    })
}

/// `tokens[i]` and the one after it as one consonant, a conjunct.
fn merge(tokens: &mut Vec<Rule>, i: usize, first_full: &str) {
    let second = tokens.remove(i + 1);
    let m = format!("{}{}", tokens[i].m, second.m);
    tokens[i] = Rule::consonant(&m, &format!("{first_full}{VIRAMA}{}", second.full));
}

fn hook(_engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let mut t = tokens;

    // 7. "gy" opening a word is ज्ञ.
    if t.len() >= 2 && t[0].m == "g" && t[1].m == "y" {
        t.remove(1);
        t[0] = Rule::consonant("gy", NYA_JNA);
    }

    // 6. The verb forms that are a word of their own.
    let whole = suffix_key(&t);
    if STANDALONE.contains(&whole.as_str()) {
        chh(&mut t);
        if STANDALONE_HALANTA.contains(&whole.as_str()) {
            t.push(Rule::sign("_", VIRAMA));
        }
        return t;
    }
    if whole == "hos" {
        t.push(Rule::sign("_", VIRAMA));
        return t;
    }

    // 1. Endings keep their own spelling.
    let mut ending = ending_start(&t);
    if let Some(from) = ending {
        // The stem's closing "i" is read as it would be at the end of the
        // word: तिमीलाई, केटीको.
        if t[from - 1].is_vowel() && t[from - 1].m == "i" {
            t[from - 1] = vowel("i", II_SIGN);
        }
        let typed = suffix_key(&t[from..]);
        if let Some(&(_, spell)) = SPELLED_ENDINGS.iter().find(|(e, s)| *e == typed && s.len() == t.len() - from) {
            for (k, s) in spell.iter().enumerate() {
                let m = t[from + k].m.clone();
                t[from + k] = match *s {
                    C(full) => Rule::consonant(&m, full),
                    V(sign) => vowel(&m, sign),
                    S(full) => Rule::sign(&m, full),
                };
            }
        }
    }

    // 2. The plural हरू.
    let mut i = 1;
    while i + 3 < t.len() {
        if t[i].m.to_lowercase() == "h"
            && t[i + 1].m == "a"
            && t[i + 2].m.to_lowercase() == "r"
            && t[i + 3].m.to_lowercase() == "u"
            && has_vowel(&t[..i])
        {
            t[i + 3] = vowel(&t[i + 3].m.clone(), UU_SIGN);
        }
        i += 1;
    }

    // 3. A verb stem joins its ending.
    let limit = ending.unwrap_or(t.len());
    let mut joins: Vec<usize> = Vec::new();
    let mut halanta = false;
    for i in 2..limit {
        if !(t[i - 1].is_consonant() && t[i].is_consonant()) || !has_vowel(&t[..i - 1]) {
            continue;
        }
        let key = suffix_key(&t[i..limit]);
        if VERB_SUFFIXES.contains(&key.as_str()) {
            joins.push(i - 1);
            chh(&mut t[i..limit]);
            if limit == t.len() && HALANTA_SUFFIXES.contains(&key.as_str()) {
                halanta = true;
            }
        }
    }
    for &i in joins.iter().rev() {
        let first = t[i].full.clone();
        merge(&mut t, i, &first);
    }
    ending = ending.map(|e| e - joins.len());

    // 4. A nasal before a stop takes its row's nasal; before a sibilant or ह
    //    it is the anusvara.
    let mut i = 1;
    while i + 1 < ending.unwrap_or(t.len()) {
        let nasal = t[i].full.as_str();
        let typed = t[i].m.to_lowercase();
        let after_vowel = t[i - 1].is_vowel();
        let next = t[i + 1].clone();
        if after_vowel && next.is_consonant() && (typed == "n" || typed == "m") && (nasal == NA || nasal == MA) {
            if next.full == nasal {
                // Doubled: the engine joins it as it is.
            } else if let Some(row) = row_nasal(&next.full) {
                let first = if typed == "n" { row } else { MA };
                merge(&mut t, i, first);
                if let Some(e) = ending.as_mut() {
                    *e -= 1;
                }
            } else if typed == "n" && matches!(next.full.as_str(), "स" | "श" | "ष" | "ह") {
                t[i] = Rule::sign(&t[i].m.clone(), ANUSVARA);
            }
        }
        i += 1;
    }

    // 5. A closing "da", "tha" or "ma" is long.
    let n = t.len();
    if !halanta && n >= 3 && t[n - 1].is_schwa() && t[n - 2].is_consonant() && has_vowel(&t[..n - 2]) {
        let last = t[n - 2].full.chars().last().map(String::from).unwrap_or_default();
        let doubled = t[n - 3].is_consonant() && t[n - 3].full == t[n - 2].full;
        if last == DA || last == THA || (last == MA && !doubled) {
            t[n - 1] = vowel(&t[n - 1].m.clone(), AA_SIGN);
        }
    }

    // 6, the optative: a closing "os" after another vowel is -ओस्.
    let n = t.len();
    if !halanta
        && n >= 3
        && t[n - 1].m == "s"
        && t[n - 2].m == "o"
        && t[..n - 2].iter().filter(|r| r.is_vowel()).count() >= 1
    {
        halanta = true;
    }

    if halanta && t.last().is_some_and(Rule::is_consonant) {
        t.push(Rule::sign("_", VIRAMA));
    }
    t
}

#[cfg(test)]
mod tests {
    use super::{transliterate as t, variants};

    #[test]
    fn plain_words() {
        assert_eq!("नमस्ते", t("namaste"));
        assert_eq!("कस्तो", t("kasto"));
        assert_eq!("थियो", t("thiyo"));
        assert_eq!("गरेर", t("garera"));
        assert_eq!("गरेको", t("gareko"));
        assert_eq!("भएको", t("bhaeko"));
        assert_eq!("नेपाल", t("nepaal"));
    }

    #[test]
    fn x_is_chh_and_v_is_bh() {
        assert_eq!("छ", t("xa"));
        assert_eq!("भयो", t("vayo"));
        assert_eq!("भयो", t("bhayo"));
        assert_eq!("वन", t("wan"));
    }

    #[test]
    fn closing_vowels_stay_short() {
        assert_eq!("किन", t("kina"));
        assert_eq!("मात्र", t("maatra"));
        assert_eq!("गर्नु", t("garnu"));
        assert_eq!("दाजु", t("daaju"));
        assert_eq!("सबै", t("sabai"));
        assert_eq!("धेरै", t("dherai"));
        // A closing "i" is still read long.
        assert_eq!("तिमी", t("timi"));
    }

    #[test]
    fn r_joins_the_consonant_before_it() {
        assert_eq!("राम्रो", t("raamro"));
        assert_eq!("तिम्रो", t("timro"));
        assert_eq!("मित्र", t("mitra"));
    }

    #[test]
    fn verb_stems_join_their_endings() {
        assert_eq!("गर्छ", t("garchha"));
        assert_eq!("गर्छ", t("garcha"));
        assert_eq!("गर्छ", t("garxa"));
        assert_eq!("हुन्छ", t("hunchha"));
        assert_eq!("हुन्छ", t("huncha"));
        assert_eq!("हुन्छ", t("hunxa"));
        assert_eq!("जान्छु", t("jaanchhu"));
        assert_eq!("बस्छ", t("baschha"));
        assert_eq!("लेख्छ", t("lekhchha"));
        assert_eq!("सक्दिन", t("sakdina"));
        assert_eq!("गर्दैन", t("gardaina"));
        assert_eq!("गर्दै", t("gardai"));
        assert_eq!("गर्दा", t("garda"));
        assert_eq!("गर्थ्यो", t("garthyo"));
        assert_eq!("बस्नु", t("basnu"));
        assert_eq!("गर्नुहुन्छ", t("garnuhunchha"));
    }

    #[test]
    fn endings_on_n_or_s_take_a_halanta() {
        assert_eq!("गर्छन्", t("garchhan"));
        assert_eq!("गर्छन्", t("garxan"));
        assert_eq!("गर्नुहोस्", t("garnuhos"));
        assert_eq!("गर्नुस्", t("garnus"));
        assert_eq!("गर्दैनन्", t("gardainan"));
        assert_eq!("गरोस्", t("garos"));
    }

    #[test]
    fn verb_forms_standing_alone() {
        assert_eq!("छ", t("cha"));
        assert_eq!("छन्", t("chan"));
        assert_eq!("छन्", t("xan"));
        assert_eq!("छु", t("chu"));
        assert_eq!("छैन", t("chaina"));
        assert_eq!("छैन", t("xaina"));
        assert_eq!("होस्", t("hos"));
    }

    #[test]
    fn endings_keep_their_spelling() {
        assert_eq!("घरमा", t("gharma"));
        assert_eq!("नेपालको", t("nepaalko"));
        assert_eq!("रामलाई", t("raamlai"));
        assert_eq!("घरबाट", t("gharbata"));
        assert_eq!("तिमीसँग", t("timisanga"));
        assert_eq!("तिमीलाई", t("timilai"));
        assert_eq!("गरेका", t("gareka"));
        assert_eq!("कसको", t("kasko"));
    }

    #[test]
    fn plural_haru_is_long() {
        assert_eq!("मन्छेहरू", t("manchheharu"));
        assert_eq!("केटाहरूको", t("keTaaharuko"));
        assert_eq!("केटीहरू", t("keTiharu"));
    }

    #[test]
    fn nasals_before_a_stop_take_their_row() {
        assert_eq!("भन्दा", t("bhanda"));
        assert_eq!("भन्दा", t("vanda"));
        assert_eq!("सङ्केत", t("sanket"));
        assert_eq!("पञ्चायत", t("panchaayat"));
        assert_eq!("घण्टा", t("ghanTaa"));
        assert_eq!("सम्झना", t("samjhanaa"));
        assert_eq!("संसार", t("sansaar"));
    }

    #[test]
    fn closing_da_tha_ma_are_long() {
        assert_eq!("कथा", t("katha"));
        assert_eq!("सम्म", t("samma"));
    }

    #[test]
    fn gy_opening_a_word_is_jna() {
        assert_eq!("ज्ञान", t("gyaan"));
    }

    #[test]
    fn variants_offer_other_readings() {
        let v = variants("bata");
        assert_eq!("बत", v[0]);
        assert!(v.contains(&"बाट".to_string()) || v.contains(&"बात".to_string()), "{v:?}");
    }
}
