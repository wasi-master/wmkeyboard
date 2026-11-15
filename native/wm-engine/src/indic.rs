//! Phonetic typing for the Indic languages that do not have an engine of
//! their own: roman letters in, native script out (Marathi, Gujarati,
//! Punjabi, Odia, Tamil, Telugu, Kannada and Malayalam), one [`Profile`]
//! each. A port of `IndicPhonetic.kt`.
//!
//! Every word is spelled in Devanagari first (extended with the letters
//! Devanagari encodes for the Dravidian languages: ऎ ऒ ॆ ॊ ऩ ऱ ऴ ळ) and then
//! moved into the target script by [`IndicScript::from_devanagari`], because
//! the Unicode blocks are laid out in parallel. What differs between the
//! languages is the profile, and above all its [`Model`]:
//!
//! - **North** (Marathi, Gujarati, Punjabi) is Hindi's model, rule for rule:
//!   the inherent vowel is dropped in speech, a final vowel is long, a nasal
//!   before its stop is an anusvara, and a cluster joins only where the table
//!   says so, never across a grammatical ending.
//! - **Explicit** (Odia and the Dravidian four) types every vowel. A
//!   consonant directly followed by a consonant is a conjunct, and a
//!   word-final consonant takes a virama where the profile asks.
//!
//! Only runs of roman letters are read; everything else passes through. The
//! rules guess where the spelling is undecided, and with a word list
//! installed the guess hardly matters. [`Engine::variants`] is for the user
//! with no list.

use std::collections::HashMap;

use crate::indic_script::IndicScript;
use crate::sort;

pub const VIRAMA: char = '\u{94D}';
pub const ANUSVARA: char = '\u{902}';
pub const VISARGA: char = '\u{903}';
pub const AA: &str = "आ";
pub const AA_SIGN: &str = "\u{93E}";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Consonant,
    Vowel,
    Sign,
}

/// One token of a word: a consonant, or a vowel with its independent form
/// and its sign. Hooks build and compare these by value, which is what the
/// Kotlin original's identity checks come down to.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Rule {
    /// The roman token this rule fires on.
    pub m: String,
    /// A consonant, or a vowel's independent form.
    pub full: String,
    /// The vowel-sign form used after a consonant; `None` means not a vowel.
    /// Empty for the inherent "a", which has none.
    pub sign: Option<String>,
    pub kind: Kind,
    /// What a short vowel becomes at the end of a word, as that rule's match.
    pub long: Option<String>,
}

impl Rule {
    pub fn consonant(m: &str, full: &str) -> Rule {
        Rule {
            m: m.to_string(),
            full: full.to_string(),
            sign: None,
            kind: Kind::Consonant,
            long: None,
        }
    }

    pub fn vowel(m: &str, full: &str, sign: &str) -> Rule {
        Rule {
            m: m.to_string(),
            full: full.to_string(),
            sign: Some(sign.to_string()),
            kind: Kind::Vowel,
            long: None,
        }
    }

    pub fn vowel_long(m: &str, full: &str, sign: &str, long: &str) -> Rule {
        Rule {
            long: Some(long.to_string()),
            ..Rule::vowel(m, full, sign)
        }
    }

    pub fn sign(m: &str, full: &str) -> Rule {
        Rule {
            m: m.to_string(),
            full: full.to_string(),
            sign: None,
            kind: Kind::Sign,
            long: None,
        }
    }

    pub fn is_schwa(&self) -> bool {
        self.sign.as_deref() == Some("")
    }

    pub fn is_vowel(&self) -> bool {
        self.kind == Kind::Vowel
    }

    pub fn is_consonant(&self) -> bool {
        self.kind == Kind::Consonant
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Model {
    North,
    Explicit,
}

/// A language's own token rewrite, run after tokenizing, with the engine
/// for [`Engine::rule`].
pub type Hook = fn(&Engine, Vec<Rule>) -> Vec<Rule>;

/// One language's phonetic rules: which script it writes, which reading
/// model it follows, and how its letters and endings differ from Hindi's.
pub struct Profile {
    pub language_id: &'static str,
    pub script: IndicScript,
    pub model: Model,
    pub dravidian_vowels: bool,
    /// Spellings added to, or replacing, the base table.
    pub extra_consonants: Vec<(&'static str, &'static str)>,
    /// Vowel rules added to, or replacing, the base ones: (match, full, sign).
    pub extra_vowels: Vec<(&'static str, &'static str, &'static str)>,
    pub drop_consonants: Vec<&'static str>,
    /// Base consonants respelled.
    pub override_: Vec<(&'static str, &'static str)>,
    /// Explicit: a word-final consonant takes a virama.
    pub final_virama: bool,
    /// A nasal before its stop is written ं.
    pub anusvara: bool,
    /// North: a word-final short vowel is read long.
    pub long_final: bool,
    /// North: grammatical endings a stem never joins.
    pub endings: Vec<&'static str>,
    /// North: endings that still join after a sibilant.
    pub soft_endings: Vec<&'static str>,
    /// Tamil's dental/alveolar n.
    pub tamil_n: bool,
    /// North: any consonant but र itself joins a र after it ("ramro" राम्रो),
    /// not only the dentals and श Hindi joins.
    pub join_r: bool,
    /// North: a closing "ai" after another vowel is the noun's ाई ("safai"
    /// सफाई) rather than ै. Nepali closes on ै ("sabai" सबै, "gardai" गर्दै).
    pub closing_aai: bool,
    pub word_hook: Option<Hook>,
}

impl Profile {
    pub fn new(language_id: &'static str, script: IndicScript, model: Model) -> Profile {
        Profile {
            language_id,
            script,
            model,
            dravidian_vowels: false,
            extra_consonants: Vec::new(),
            extra_vowels: Vec::new(),
            drop_consonants: Vec::new(),
            override_: Vec::new(),
            final_virama: false,
            anusvara: true,
            long_final: true,
            endings: Vec::new(),
            soft_endings: Vec::new(),
            tamil_n: false,
            join_r: false,
            closing_aai: true,
            word_hook: None,
        }
    }
}

/// How the undecided parts of a spelling are read.
#[derive(Clone, Copy)]
struct Reading {
    guards: bool,
    join_all: bool,
    join_none: bool,
    long_final: bool,
    long_last: bool,
    /// Explicit only: the closing inherent "a" read long (Tamil "amma" அம்மா).
    long_final_a: bool,
}

const LIKELY: Reading = Reading {
    guards: true,
    join_all: false,
    join_none: false,
    long_final: true,
    long_last: false,
    long_final_a: false,
};
const LONG_LAST: Reading = Reading {
    long_last: true,
    ..LIKELY
};
const TIGHT: Reading = Reading {
    guards: false,
    join_all: true,
    ..LIKELY
};
const LOOSE: Reading = Reading {
    join_none: true,
    ..LIKELY
};
const SHORT_FINAL: Reading = Reading {
    long_final: false,
    ..LIKELY
};
const LONG_FINAL_A: Reading = Reading {
    long_final_a: true,
    ..LIKELY
};

/// Hindi's consonant table, which every profile starts from.
const BASE_CONSONANTS: &[(&str, &str)] = &[
    ("kh", "ख"),
    ("gh", "घ"),
    ("chh", "छ"),
    ("ch", "च"),
    ("jh", "झ"),
    ("Th", "ठ"),
    ("Dh", "ढ"),
    ("th", "थ"),
    ("dh", "ध"),
    ("ph", "फ"),
    ("bh", "भ"),
    ("sh", "श"),
    ("Sh", "ष"),
    ("ksh", "क्ष"),
    ("x", "क्स"),
    ("k", "क"),
    ("g", "ग"),
    ("c", "च"),
    ("j", "ज"),
    ("T", "ट"),
    ("D", "ड"),
    ("N", "ण"),
    ("t", "त"),
    ("d", "द"),
    ("n", "न"),
    ("p", "प"),
    ("f", "फ"),
    ("b", "ब"),
    ("m", "म"),
    ("y", "य"),
    ("r", "र"),
    ("l", "ल"),
    ("v", "व"),
    ("w", "व"),
    ("s", "स"),
    ("h", "ह"),
    ("z", "ज\u{93C}"),
    ("q", "क"),
];

const AFTER_N: &[&str] = &[
    "क",
    "ख",
    "ग",
    "घ",
    "च",
    "छ",
    "ज",
    "झ",
    "ट",
    "ठ",
    "ड",
    "ढ",
    "त",
    "थ",
    "द",
    "ध",
    "स",
    "श",
    "ष",
    "ज\u{93C}",
];
const AFTER_M: &[&str] = &["प", "फ", "ब", "भ"];
const SIBILANTS: &[&str] = &["स", "श", "ष"];
const BEFORE_R: &[&str] = &["त", "थ", "द", "ध", "श"];
const BEFORE_V: &[&str] = &["स", "श", "त", "द", "ध", "ज"];
const AFTER_S: &[&str] = &["त", "थ", "ट", "ठ", "प", "क", "म"];

fn aspirate(c: &str) -> Option<&'static str> {
    Some(match c {
        "क" => "ख",
        "ग" => "घ",
        "च" => "छ",
        "ज" => "झ",
        "ट" => "ठ",
        "ड" => "ढ",
        "त" => "थ",
        "द" => "ध",
        "प" => "फ",
        "ब" => "भ",
        _ => return None,
    })
}

pub struct Engine {
    pub profile: Profile,
    rules: Vec<Rule>,
    by_first: HashMap<char, Vec<usize>>,
    long_a: Rule,
    diphthong_ai: Rule,
}

impl Engine {
    pub fn new(profile: Profile) -> Engine {
        let rules = build_rules(&profile);
        let mut by_first: HashMap<char, Vec<usize>> = HashMap::new();
        for (i, rule) in rules.iter().enumerate() {
            by_first
                .entry(rule.m.chars().next().unwrap())
                .or_default()
                .push(i);
        }
        let long_a = rules.iter().find(|r| r.m == "aa").unwrap().clone();
        let diphthong_ai = rules.iter().find(|r| r.m == "ai").unwrap().clone();
        Engine {
            profile,
            rules,
            by_first,
            long_a,
            diphthong_ai,
        }
    }

    fn north(&self) -> bool {
        self.profile.model == Model::North
    }

    /// This engine's rule for exactly `m`, the one tokenizing would have
    /// produced. What a hook swaps a token for when it means an ordinary
    /// rule ("aa", "i"), so it never builds a second rule the walk would not
    /// recognise as the same vowel.
    pub fn rule(&self, m: &str) -> Rule {
        self.rules
            .iter()
            .find(|r| r.m == m)
            .unwrap_or_else(|| panic!("no Indic rule for {m}"))
            .clone()
    }

    fn exact_rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        self.by_first
            .get(&input[at])?
            .iter()
            .map(|&i| &self.rules[i])
            .find(|rule| {
                rule.m
                    .chars()
                    .enumerate()
                    .all(|(k, c)| input.get(at + k) == Some(&c))
            })
    }

    /// `exact_rule_at`, falling back to the lowercase reading of a capital
    /// that spells nothing of its own.
    fn rule_at(&self, input: &[char], at: usize) -> Option<&Rule> {
        if let Some(rule) = self.exact_rule_at(input, at) {
            return Some(rule);
        }
        if !input[at].is_ascii_uppercase() {
            return None;
        }
        let lowered: Vec<char> = input[at..].iter().flat_map(|c| c.to_lowercase()).collect();
        self.exact_rule_at(&lowered, 0)
    }

    /// Transliterates roman text, one word or a sentence, into the script.
    pub fn transliterate(&self, input: &str) -> String {
        self.render(input, LIKELY)
    }

    /// Every reading of `input` worth offering, the likely one first. North
    /// offers Hindi's: the last inner "a" long, every cluster joined, none
    /// joined, the final vowel short. Explicit offers the closing "a" long
    /// and the last inner "a" long.
    pub fn variants(&self, input: &str) -> Vec<String> {
        let readings: &[Reading] = if self.north() {
            &[LIKELY, LONG_LAST, TIGHT, LOOSE, SHORT_FINAL]
        } else {
            &[LIKELY, LONG_FINAL_A, LONG_LAST]
        };
        let mut out: Vec<String> = Vec::new();
        for &reading in readings {
            let rendered = self.render(input, reading);
            if !out.contains(&rendered) {
                out.push(rendered);
            }
        }
        out
    }

    fn render(&self, input: &str, reading: Reading) -> String {
        let input: Vec<char> = input.chars().collect();
        let mut dev = String::with_capacity(input.len() * 3);
        let mut i = 0;
        while i < input.len() {
            if input[i].is_ascii_alphabetic() {
                let mut j = i;
                while j < input.len() && input[j].is_ascii_alphabetic() {
                    j += 1;
                }
                self.word(&input[i..j], reading, &mut dev);
                i = j;
            } else {
                dev.push(input[i]);
                i += 1;
            }
        }
        self.profile.script.from_devanagari(&dev)
    }

    fn tokenize(&self, word: &[char]) -> Vec<Rule> {
        let mut tokens = Vec::with_capacity(word.len());
        let mut i = 0;
        while i < word.len() {
            let rule = match self.rule_at(word, i) {
                Some(rule) => rule.clone(),
                None => Rule::consonant(&word[i].to_string(), &word[i].to_string()),
            };
            i += rule.m.chars().count();
            tokens.push(rule);
        }
        match self.profile.word_hook {
            Some(hook) => hook(self, tokens),
            None => tokens,
        }
    }

    fn word(&self, word: &[char], reading: Reading, out: &mut String) {
        let tokens = self.tokenize(word);
        let n = tokens.len();
        let start = out.len();
        let north = self.north();
        let last_inner = if reading.long_last && n >= 2 {
            (1..n - 1).rev().find(|&i| tokens[i].is_schwa())
        } else {
            None
        };
        let mut prev: Option<Kind> = None;
        for (index, token) in tokens.iter().enumerate() {
            let last = index == n - 1;
            match token.kind {
                Kind::Vowel => {
                    if north
                        && last
                        && self.profile.closing_aai
                        && *token == self.diphthong_ai
                        && tokens[..index].iter().any(Rule::is_vowel)
                    {
                        // The ाई that closes a noun: "safai" सफाई, but "hai" है.
                        out.push_str(if prev == Some(Kind::Consonant) {
                            AA_SIGN
                        } else {
                            AA
                        });
                        out.push('ई');
                        prev = Some(Kind::Vowel);
                        continue;
                    }
                    let next = tokens.get(index + 1);
                    let long_of = |t: &Rule| t.long.as_deref().map(|m| self.rule(m));
                    let chosen: Rule =
                        if north && last && reading.long_final && self.profile.long_final {
                            long_of(token).unwrap_or_else(|| token.clone())
                        } else if !north && last && token.is_schwa() && reading.long_final_a {
                            long_of(token).unwrap_or_else(|| token.clone())
                        } else if Some(index) == last_inner {
                            self.long_a.clone()
                        } else if north
                            && token.is_schwa()
                            && next.is_some_and(|next| {
                                let c = next.m.chars().next().unwrap().to_ascii_lowercase();
                                c == 'o' || c == 'u'
                            })
                        {
                            // An "a" before "o" or "u" is long: "jao" जाओ.
                            self.long_a.clone()
                        } else {
                            token.clone()
                        };
                    out.push_str(if prev == Some(Kind::Consonant) {
                        chosen.sign.as_deref().unwrap_or("")
                    } else {
                        &chosen.full
                    });
                    prev = Some(Kind::Vowel);
                }
                Kind::Consonant => {
                    if self.nasalises(&tokens, index, reading) {
                        out.push(ANUSVARA);
                        prev = Some(Kind::Sign);
                        continue;
                    }
                    if prev == Some(Kind::Consonant) {
                        let join = if north {
                            self.joins(&tokens, index, reading)
                        } else {
                            !reading.join_none
                        };
                        if join {
                            out.push(VIRAMA);
                        }
                    }
                    let mut full = token.full.as_str();
                    if self.profile.tamil_n && full == "न" {
                        // Tamil: dental ந opens a word and sits before த;
                        // alveolar ன everywhere else.
                        let opening = out.len() == start;
                        let before_dental = tokens.get(index + 1).is_some_and(|t| t.full == "त");
                        if !opening && !before_dental {
                            full = "ऩ";
                        }
                    }
                    out.push_str(full);
                    prev = Some(Kind::Consonant);
                }
                Kind::Sign => {
                    out.push_str(&token.full);
                    prev = Some(Kind::Sign);
                }
            }
        }
        if !north && self.profile.final_virama && prev == Some(Kind::Consonant) {
            out.push(VIRAMA);
        }
    }

    /// Whether the nasal at `index` is written as an anusvara.
    fn nasalises(&self, tokens: &[Rule], index: usize, reading: Reading) -> bool {
        if !self.profile.anusvara || reading.join_none {
            return false;
        }
        let Some(next) = tokens.get(index + 1) else {
            return false;
        };
        if next.kind != Kind::Consonant {
            return false;
        }
        if index == 0 || tokens[index - 1].kind != Kind::Vowel {
            return false;
        }
        let homorganic = match tokens[index].full.as_str() {
            "न" => AFTER_N.contains(&next.full.as_str()),
            "म" => AFTER_M.contains(&next.full.as_str()),
            _ => false,
        };
        homorganic && !(self.north() && reading.guards && self.guarded(tokens, index + 1))
    }

    /// Whether the rest of the word from `index` is an ending its stem stays clear of.
    fn guarded(&self, tokens: &[Rule], index: usize) -> bool {
        if !tokens[..index].iter().any(Rule::is_vowel) {
            return false;
        }
        let rest: String = tokens[index..]
            .iter()
            .flat_map(|t| t.m.chars())
            .flat_map(|c| c.to_lowercase())
            .collect();
        if self.profile.endings.contains(&rest.as_str()) {
            return true;
        }
        self.profile.soft_endings.contains(&rest.as_str())
            && !SIBILANTS.contains(&tokens[index - 1].full.as_str())
    }

    /// North: whether the consonant at `index` forms a conjunct with the one before it.
    fn joins(&self, tokens: &[Rule], index: usize, reading: Reading) -> bool {
        let before = tokens[index - 1].full.as_str();
        let here = tokens[index].full.as_str();
        if tokens[..index].iter().all(Rule::is_consonant) {
            return true;
        }
        if reading.join_none {
            return false;
        }
        let doubled = before == here || aspirate(before) == Some(here);
        if doubled && here != "न" && here != "स" {
            return true;
        }
        if reading.guards && self.guarded(tokens, index) {
            return false;
        }
        if reading.join_all {
            return true;
        }
        let last = index == tokens.len() - 1;
        let closing = last || (index == tokens.len() - 2 && tokens[index + 1].kind == Kind::Vowel);
        if doubled || here == "य" {
            true
        } else if here == "र" {
            if self.profile.join_r {
                before != "र"
            } else {
                BEFORE_R.contains(&before)
            }
        } else if here == "व" {
            BEFORE_V.contains(&before)
        } else if SIBILANTS.contains(&before) {
            AFTER_S.contains(&here)
        } else if before == "र" {
            closing
        } else if here == "ह" {
            before == "न" || before == "म"
        } else {
            last
        }
    }
}

#[allow(clippy::vec_init_then_push)]
fn build_rules(profile: &Profile) -> Vec<Rule> {
    let mut out: Vec<Rule> = Vec::new();
    out.push(Rule::vowel("aa", AA, AA_SIGN));
    out.push(Rule::vowel("A", AA, AA_SIGN));
    out.push(Rule::vowel_long("a", "अ", "", "aa"));
    out.push(Rule::vowel("ee", "ई", "\u{940}"));
    out.push(Rule::vowel("ii", "ई", "\u{940}"));
    out.push(Rule::vowel("I", "ई", "\u{940}"));
    out.push(Rule::vowel_long("i", "इ", "\u{93F}", "ee"));
    out.push(Rule::vowel("oo", "ऊ", "\u{942}"));
    out.push(Rule::vowel("uu", "ऊ", "\u{942}"));
    out.push(Rule::vowel("U", "ऊ", "\u{942}"));
    out.push(Rule::vowel_long("u", "उ", "\u{941}", "oo"));
    out.push(Rule::vowel("ai", "ऐ", "\u{948}"));
    out.push(Rule::vowel("au", "औ", "\u{94C}"));
    out.push(Rule::vowel("ou", "औ", "\u{94C}"));
    out.push(Rule::vowel("RRi", "ऋ", "\u{943}"));
    out.push(Rule::vowel("Ru", "ऋ", "\u{943}"));
    if profile.dravidian_vowels {
        // Short e and o beside the long ones: one letter is short, the
        // capital (or "ae") is long, as Baraha and Lekhini write them.
        out.push(Rule::vowel("e", "\u{90E}", "\u{946}"));
        out.push(Rule::vowel("E", "ए", "\u{947}"));
        out.push(Rule::vowel("ae", "ए", "\u{947}"));
        out.push(Rule::vowel("o", "\u{912}", "\u{94A}"));
        out.push(Rule::vowel("O", "ओ", "\u{94B}"));
    } else {
        out.push(Rule::vowel("e", "ए", "\u{947}"));
        out.push(Rule::vowel("o", "ओ", "\u{94B}"));
    }
    for &(m, full, sign) in &profile.extra_vowels {
        out.push(Rule::vowel(m, full, sign));
    }
    // Insertion-ordered, an existing key keeping its place, as LinkedHashMap does.
    let mut consonants: Vec<(&str, &str)> = Vec::new();
    let mut put =
        |m: &'static str, dev: &'static str| match consonants.iter_mut().find(|(k, _)| *k == m) {
            Some(entry) => entry.1 = dev,
            None => consonants.push((m, dev)),
        };
    for &(m, dev) in BASE_CONSONANTS {
        if profile.drop_consonants.contains(&m) {
            continue;
        }
        let dev = profile
            .override_
            .iter()
            .find(|(k, _)| *k == m)
            .map_or(dev, |(_, v)| *v);
        put(m, dev);
    }
    for &(m, dev) in &profile.extra_consonants {
        put(m, dev);
    }
    for (m, dev) in consonants {
        out.push(Rule::consonant(m, dev));
    }
    out.push(Rule::sign("M", &ANUSVARA.to_string()));
    out.push(Rule::sign("H", &VISARGA.to_string()));
    // A vowel rule a profile overrides wins over the base one of the same
    // spelling: the later entry, kept by distinct over the reversed list.
    let mut distinct: Vec<Rule> = Vec::with_capacity(out.len());
    for rule in out.into_iter().rev() {
        if !distinct.iter().any(|r| r.m == rule.m) {
            distinct.push(rule);
        }
    }
    sort::by_key(&mut distinct, |rule| std::cmp::Reverse(rule.m.chars().count()));
    distinct
}

/// Helpers the language hooks share.
pub mod hooks {
    use super::Rule;

    pub fn has_vowel(tokens: &[Rule]) -> bool {
        tokens.iter().any(Rule::is_vowel)
    }

    /// The roman spelling of `tokens` from `from` to the end, lowercased.
    pub fn rest_lower(tokens: &[Rule], from: usize) -> String {
        tokens
            .iter()
            .skip(from)
            .flat_map(|t| t.m.chars())
            .flat_map(|c| c.to_lowercase())
            .collect()
    }

    /// The roman spelling of `tokens` from `from` to the end, as typed.
    pub fn rest(tokens: &[Rule], from: usize) -> String {
        tokens.iter().skip(from).flat_map(|t| t.m.chars()).collect()
    }

    /// Whether the tokens end in exactly these roman spellings.
    pub fn ends_with(tokens: &[Rule], matches: &[&str]) -> bool {
        if tokens.len() < matches.len() {
            return false;
        }
        let from = tokens.len() - matches.len();
        matches
            .iter()
            .enumerate()
            .all(|(i, m)| tokens[from + i].m == *m)
    }
}
