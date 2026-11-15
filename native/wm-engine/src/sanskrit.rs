//! Sanskrit phonetic: roman letters in, Devanagari out, on the Indic engine's
//! Explicit model, since Sanskrit is spelled with every vowel it says and a
//! word that closes on a consonant writes its halanta (भगवान्, संस्कृतम्).
//!
//! The letters are the ones the two schemes Sanskrit is typed in agree on,
//! ITRANS and Harvard-Kyoto, both of which only need the letter keys:
//!
//! - long vowels doubled or capital: "aa"/"A", "ii"/"I", "uu"/"U";
//! - ऋ as "R", "Ri" or "RRi", ॠ as "RR" or "RRI", ऌ as "lR" or "LLi";
//! - the retroflex row capital ("T", "Th", "D", "Dh", "N"), ष as "Sh" or
//!   "S", श as "sh" or "z", ङ as "G", ञ as "J";
//! - च as "c" or "ch", छ as "chh" or "Ch"; क्ष as "x" or "kSh", ज्ञ as "jn"
//!   or "GY";
//! - the anusvara "M", the visarga "H", and "om" alone ॐ.
//!
//! A nasal typed before a stop of its own row is written as the anusvara, the
//! way most Sanskrit is printed today (संगीत, शंकर); typing "G" or "J"
//! spells the row's nasal out (सङ्गीत).

use std::sync::OnceLock;

use crate::indic::{Engine, Model, Profile, Rule};
use crate::indic_script::IndicScript;

const OM: &str = "\u{950}";

pub fn profile() -> Profile {
    let mut p = Profile::new("sa", IndicScript::Devanagari, Model::Explicit);
    p.final_virama = true;
    p.extra_vowels = vec![
        ("R", "\u{90B}", "\u{943}"),
        ("Ri", "\u{90B}", "\u{943}"),
        ("RR", "\u{960}", "\u{944}"),
        ("RRI", "\u{960}", "\u{944}"),
        ("lR", "\u{90C}", "\u{962}"),
        ("LLi", "\u{90C}", "\u{962}"),
        ("lRR", "\u{961}", "\u{963}"),
        ("LLI", "\u{961}", "\u{963}"),
    ];
    p.extra_consonants = vec![
        ("G", "\u{919}"),
        ("J", "\u{91E}"),
        ("S", "\u{937}"),
        ("Ch", "\u{91B}"),
        ("jn", "\u{91C}\u{94D}\u{91E}"),
        ("GY", "\u{91C}\u{94D}\u{91E}"),
        ("L", "\u{933}"),
    ];
    p.override_ = vec![("z", "\u{936}"), ("x", "\u{915}\u{94D}\u{937}")];
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

/// "om" (or "OM", "aum") alone is the sacred syllable, written ॐ.
fn hook(_engine: &Engine, tokens: Vec<Rule>) -> Vec<Rule> {
    let typed: String = tokens.iter().flat_map(|t| t.m.chars()).flat_map(char::to_lowercase).collect();
    if typed == "om" || typed == "aum" {
        return vec![Rule::sign(&typed, OM)];
    }
    tokens
}

#[cfg(test)]
mod tests {
    use super::transliterate as t;

    #[test]
    fn vowels_long_and_short() {
        assert_eq!("राम", t("rAma"));
        assert_eq!("राम", t("raama"));
        assert_eq!("गीता", t("gItA"));
        assert_eq!("गीता", t("geetaa"));
        assert_eq!("विद्या", t("vidyA"));
    }

    #[test]
    fn a_closing_consonant_takes_a_halanta() {
        assert_eq!("भगवान्", t("bhagavAn"));
        assert_eq!("संस्कृतम्", t("saMskRtam"));
        assert_eq!("ज्ञानम्", t("jnAnam"));
    }

    #[test]
    fn vocalic_r_and_the_retroflex_row() {
        assert_eq!("कृष्ण", t("kRShNa"));
        assert_eq!("कृष्ण", t("kRiShNa"));
        assert_eq!("धर्मक्षेत्रे", t("dharmakShetre"));
        assert_eq!("धर्मक्षेत्रे", t("dharmaxetre"));
    }

    #[test]
    fn harvard_kyoto_letters() {
        assert_eq!("शिवाय", t("zivAya"));
        assert_eq!("विष्णु", t("viSNu"));
        assert_eq!("सङ्गीत", t("saGgIta"));
    }

    #[test]
    fn signs_and_the_nasal_before_a_stop() {
        assert_eq!("रामः", t("rAmaH"));
        assert_eq!("संगीतम्", t("sangItam"));
        assert_eq!("ॐ", t("om"));
        assert_eq!("ॐ नमः शिवाय", t("om namaH shivAya"));
    }
}
