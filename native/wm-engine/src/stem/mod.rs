//! Light stemmers for the keyboard's text searches: "running" finds "run",
//! "chats" finds "chat", "maisons" finds "maison".
//!
//! Search only needs two forms of one word to land on the same key, not a
//! linguistically right root, so these are the light suffix strippers search
//! engines ship rather than full Snowball stemmers: a few hundred bytes of
//! rules a language instead of 30 KB. Most are ports of Apache Lucene's
//! (Apache-2.0), several of those in turn after Jacques Savoy's UniNE CLEF
//! stemmers (BSD-3-Clause); the licences screen carries both notices. The
//! English, Greek and Galician ones are our own: Porter's step 1 (the
//! published algorithm), and plural and inflection tables of the same kind.
//!
//! | Module | Languages |
//! |---|---|
//! | [`english`] | en (and the fallback for other Latin-script text) |
//! | [`romance`] | fr, es, pt, it, gl |
//! | [`germanic`] | de, sv, nb / nn / no |
//! | [`slavic`] | ru, bg, cs |
//! | [`uralic`] | fi, hu |
//! | [`baltic`] | lv |
//! | [`greek`] | el |
//! | [`indic`] | hi, bn |
//! | [`indonesian`] | id, ms |
//! | [`arabic`] | ar, fa |
//!
//! A word is stemmed in the language of the text when its script is that
//! language's, and otherwise by its own script: English words in a Bengali
//! note go through the English stemmer, Cyrillic in an English one through
//! Russian. Nothing here runs on the key-press path, and nothing allocates
//! but the output: a word is stemmed in a fixed buffer on the stack.

pub mod arabic;
pub mod baltic;
pub mod english;
pub mod germanic;
pub mod greek;
pub mod indic;
pub mod indonesian;
pub mod romance;
pub mod slavic;
pub mod uralic;

use crate::unicode::{is_letter, is_mark};

/// The longest word stemmed; a longer one is kept whole (lowercased). Real
/// words are far shorter, and a fixed buffer keeps the stemmers allocation
/// free.
pub const MAX: usize = 48;

/// The stemmers, one per language.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lang {
    English,
    French,
    Spanish,
    Portuguese,
    Italian,
    Galician,
    German,
    Swedish,
    Norwegian,
    Russian,
    Bulgarian,
    Czech,
    Finnish,
    Hungarian,
    Latvian,
    Greek,
    Hindi,
    Bengali,
    Indonesian,
    Arabic,
    Persian,
}

/// The scripts a word can be in, as far as choosing a stemmer goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Script {
    Latin,
    Greek,
    Cyrillic,
    Arabic,
    Devanagari,
    Bengali,
    Other,
}

/// What the keyboard knows of a text's language: its tag (`en-GB`, `pt_BR`,
/// `bn`), possibly none or one without a stemmer.
#[derive(Clone, Copy, Debug)]
pub struct Context {
    lang: Option<Lang>,
    /// For a tag without a stemmer: the script its words are in, so they
    /// are left alone rather than run through another language's rules.
    unstemmed: Option<Script>,
}

impl Context {
    pub fn of_tag(tag: &str) -> Context {
        let primary = tag.split(['-', '_']).next().unwrap_or("").to_ascii_lowercase();
        let lang = match primary.as_str() {
            "en" => Some(Lang::English),
            "fr" => Some(Lang::French),
            "es" => Some(Lang::Spanish),
            "pt" => Some(Lang::Portuguese),
            "it" => Some(Lang::Italian),
            "gl" => Some(Lang::Galician),
            "de" => Some(Lang::German),
            "sv" => Some(Lang::Swedish),
            "nb" | "nn" | "no" => Some(Lang::Norwegian),
            "ru" => Some(Lang::Russian),
            "bg" => Some(Lang::Bulgarian),
            "cs" => Some(Lang::Czech),
            "fi" => Some(Lang::Finnish),
            "hu" => Some(Lang::Hungarian),
            "lv" => Some(Lang::Latvian),
            "el" => Some(Lang::Greek),
            "hi" => Some(Lang::Hindi),
            "bn" => Some(Lang::Bengali),
            "id" | "in" | "ms" => Some(Lang::Indonesian),
            "ar" => Some(Lang::Arabic),
            "fa" => Some(Lang::Persian),
            _ => None,
        };
        // Languages written in a script one of these stemmers covers, whose
        // own endings differ: their words in that script stay as typed.
        let unstemmed = match primary.as_str() {
            "uk" | "be" | "sr" | "mk" | "kk" | "ky" | "tg" | "mn" => Some(Script::Cyrillic),
            "ur" | "ps" | "ku" | "ckb" | "sd" | "ug" => Some(Script::Arabic),
            "mr" | "ne" | "sa" | "kok" | "mai" => Some(Script::Devanagari),
            "as" | "mni" => Some(Script::Bengali),
            _ => None,
        };
        Context { lang, unstemmed }
    }

    /// The stemmer for one word: the context's language when the word is in
    /// its script, else the word's script's own.
    fn stemmer_for(&self, script: Script) -> Option<Lang> {
        if let Some(lang) = self.lang {
            if script_of_lang(lang) == script {
                return Some(lang);
            }
        }
        if self.unstemmed == Some(script) {
            return None;
        }
        match script {
            Script::Latin => Some(Lang::English),
            Script::Greek => Some(Lang::Greek),
            Script::Cyrillic => Some(Lang::Russian),
            Script::Arabic => Some(Lang::Arabic),
            Script::Devanagari => Some(Lang::Hindi),
            Script::Bengali => Some(Lang::Bengali),
            Script::Other => None,
        }
    }
}

fn script_of_lang(lang: Lang) -> Script {
    match lang {
        Lang::Greek => Script::Greek,
        Lang::Russian | Lang::Bulgarian => Script::Cyrillic,
        Lang::Hindi => Script::Devanagari,
        Lang::Bengali => Script::Bengali,
        Lang::Arabic | Lang::Persian => Script::Arabic,
        _ => Script::Latin,
    }
}

fn script_of(c: char) -> Script {
    match c as u32 {
        0x41..=0x24F | 0x1E00..=0x1EFF => Script::Latin,
        0x370..=0x3FF | 0x1F00..=0x1FFF => Script::Greek,
        0x400..=0x52F => Script::Cyrillic,
        0x600..=0x6FF | 0x750..=0x77F | 0xFB50..=0xFDFF | 0xFE70..=0xFEFF => Script::Arabic,
        0x900..=0x97F => Script::Devanagari,
        0x980..=0x9FF => Script::Bengali,
        _ => Script::Other,
    }
}

/// One word being stemmed, in place: the Java `char[] s, int len` the
/// Lucene originals work on.
pub struct Word {
    pub s: [char; MAX],
    pub len: usize,
}

impl Word {
    pub fn new(word: &str) -> Option<Word> {
        let mut w = Word { s: ['\0'; MAX], len: 0 };
        for c in word.chars() {
            if w.len == MAX {
                return None;
            }
            w.s[w.len] = c;
            w.len += 1;
        }
        Some(w)
    }

    pub fn as_string(&self) -> String {
        self.s[..self.len].iter().collect()
    }

    /// Whether the word ends with `suffix`.
    pub fn ends(&self, suffix: &str) -> bool {
        ends_at(&self.s, self.len, suffix)
    }

    /// Whether the first `len` characters end with `suffix`.
    pub fn ends_at(&self, len: usize, suffix: &str) -> bool {
        ends_at(&self.s, len, suffix)
    }

    pub fn starts(&self, prefix: &str) -> bool {
        prefix.chars().enumerate().all(|(i, c)| i < self.len && self.s[i] == c)
    }

    /// The character `back` places from the end (1 = the last), or `'\0'`.
    pub fn at_end(&self, back: usize) -> char {
        if back == 0 || back > self.len {
            '\0'
        } else {
            self.s[self.len - back]
        }
    }

    /// Sets the character `back` places from the end.
    pub fn set_end(&mut self, back: usize, c: char) {
        if back >= 1 && back <= self.len {
            self.s[self.len - back] = c;
        }
    }

    /// Replaces the last `n` characters with `with`, if it fits.
    pub fn replace_end(&mut self, n: usize, with: &str) {
        let n = n.min(self.len);
        self.len -= n;
        for c in with.chars() {
            if self.len == MAX {
                break;
            }
            self.s[self.len] = c;
            self.len += 1;
        }
    }

    pub fn drop_end(&mut self, n: usize) {
        self.len -= n.min(self.len);
    }

    /// Removes the character at `i`.
    pub fn delete(&mut self, i: usize) {
        if i < self.len {
            self.s.copy_within(i + 1..self.len, i);
            self.len -= 1;
        }
    }

    /// Removes the first `n` characters.
    pub fn drop_start(&mut self, n: usize) {
        let n = n.min(self.len);
        self.s.copy_within(n..self.len, 0);
        self.len -= n;
    }

    /// Maps every character through `f`.
    pub fn map(&mut self, f: impl Fn(char) -> char) {
        for c in &mut self.s[..self.len] {
            *c = f(*c);
        }
    }

    /// The first of `suffixes` the word ends with.
    pub fn ends_any(&self, suffixes: &[&str]) -> bool {
        suffixes.iter().any(|s| self.ends(s))
    }
}

pub fn ends_at(s: &[char], len: usize, suffix: &str) -> bool {
    let mut i = len;
    for c in suffix.chars().rev() {
        if i == 0 || s[i - 1] != c {
            return false;
        }
        i -= 1;
    }
    true
}

/// Runs `lang`'s stemmer over `word`, which is already lowercase.
pub fn stem_in_place(lang: Lang, w: &mut Word) {
    match lang {
        Lang::English => english::stem(w),
        Lang::French => romance::french(w),
        Lang::Spanish => romance::spanish(w),
        Lang::Portuguese => romance::portuguese(w),
        Lang::Italian => romance::italian(w),
        Lang::Galician => romance::galician(w),
        Lang::German => germanic::german(w),
        Lang::Swedish => germanic::swedish(w),
        Lang::Norwegian => germanic::norwegian(w),
        Lang::Russian => slavic::russian(w),
        Lang::Bulgarian => slavic::bulgarian(w),
        Lang::Czech => slavic::czech(w),
        Lang::Finnish => uralic::finnish(w),
        Lang::Hungarian => uralic::hungarian(w),
        Lang::Latvian => baltic::latvian(w),
        Lang::Greek => greek::stem(w),
        Lang::Hindi => indic::hindi(w),
        Lang::Bengali => indic::bengali(w),
        Lang::Indonesian => indonesian::stem(w),
        Lang::Arabic => arabic::arabic(w),
        Lang::Persian => arabic::persian(w),
    }
}

/// One word's stem in `lang`, lowercased first; for tests and the desktop.
pub fn stem(lang: Lang, word: &str) -> String {
    let lower = word.to_lowercase();
    match Word::new(&lower) {
        Some(mut w) => {
            stem_in_place(lang, &mut w);
            w.as_string()
        }
        None => lower,
    }
}

/// Whether `c` continues a word: letters, digits, combining marks and the
/// zero-width joiners Persian and the Indic scripts write inside words.
fn is_word_char(c: char) -> bool {
    is_letter(c) || is_mark(c) || c.is_numeric() || c == '\u{200C}' || c == '\u{200D}'
        // Arabic harakat and the Arabic-script marks `is_mark` leaves out.
        || matches!(c as u32, 0x0610..=0x061A | 0x064B..=0x065F | 0x0670 | 0x06D6..=0x06ED)
}

/// The words of `text`, each lowercased and stemmed in `ctx`, joined by
/// single spaces into `out` (UTF-16, cleared first). Digits and words in a
/// script without a stemmer come through lowercased only. Search matches a
/// query against a text by comparing the two outputs word for word.
pub fn stem_text_into(ctx: &Context, text: &[u16], out: &mut Vec<u16>) {
    out.clear();
    let mut w = Word { s: ['\0'; MAX], len: 0 };
    // Where the word being read starts in `text`, and whether it ran past
    // the buffer (then it is copied out lowercased, whole, from there).
    let mut start = 0;
    let mut overflow = false;
    let mut script = Script::Other;
    let mut pos = 0;
    let mut chars = char::decode_utf16(text.iter().copied()).peekable();
    loop {
        let next = chars.next().map(|c| c.unwrap_or('\u{FFFD}'));
        match next {
            Some(c) if is_word_char(c) => {
                if w.len == 0 && !overflow {
                    start = pos;
                    script = Script::Other;
                }
                if script == Script::Other && is_letter(c) {
                    script = script_of(c);
                }
                for lower in c.to_lowercase() {
                    if w.len < MAX {
                        w.s[w.len] = lower;
                        w.len += 1;
                    } else {
                        overflow = true;
                    }
                }
                pos += c.len_utf16();
            }
            _ => {
                if w.len > 0 {
                    if !out.is_empty() {
                        out.push(b' ' as u16);
                    }
                    if overflow {
                        for c in char::decode_utf16(text[start..pos].iter().copied()) {
                            for lower in c.unwrap_or('\u{FFFD}').to_lowercase() {
                                push_char(out, lower);
                            }
                        }
                    } else {
                        stem_word(ctx, script, &mut w);
                        for &c in &w.s[..w.len] {
                            push_char(out, c);
                        }
                    }
                    w.len = 0;
                    overflow = false;
                }
                match next {
                    Some(c) => pos += c.len_utf16(),
                    None => return,
                }
            }
        }
    }
}

fn push_char(out: &mut Vec<u16>, c: char) {
    let mut buf = [0u16; 2];
    out.extend_from_slice(c.encode_utf16(&mut buf));
}

/// One word, already lowercased: the joiners dropped (they steer shaping,
/// not spelling, and one typist writes them where another does not), then
/// the stemmer its script calls for.
fn stem_word(ctx: &Context, script: Script, w: &mut Word) {
    let mut j = 0;
    for i in 0..w.len {
        let c = w.s[i];
        if c != '\u{200C}' && c != '\u{200D}' {
            w.s[j] = c;
            j += 1;
        }
    }
    w.len = j;
    if let Some(lang) = ctx.stemmer_for(script) {
        stem_in_place(lang, w);
    }
}

/// [`stem_text_into`] over a `&str`, for tests and the desktop.
pub fn stem_text(tag: &str, text: &str) -> String {
    let units: Vec<u16> = text.encode_utf16().collect();
    let mut out = Vec::new();
    stem_text_into(&Context::of_tag(tag), &units, &mut out);
    String::from_utf16_lossy(&out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_is_split_lowercased_and_stemmed() {
        assert_eq!(stem_text("en", "Running late, two Chats!"), "run late two chat");
        assert_eq!(stem_text("en-US", "  "), "");
        assert_eq!(stem_text("en", "call 911 now"), "call 911 now");
    }

    #[test]
    fn query_and_text_meet_on_the_same_stem() {
        for (query, text) in [("running", "run"), ("chats", "chat"), ("studies", "study"), ("hoping", "hope")] {
            assert_eq!(stem_text("en", query), stem_text("en", text), "{query} / {text}");
        }
        assert_eq!(stem_text("fr", "maisons"), stem_text("fr", "maison"));
        assert_eq!(stem_text("de", "Häuser"), stem_text("de", "Hauser"));
    }

    #[test]
    fn a_word_outside_the_languages_script_uses_its_own() {
        // English words in Bengali text, Bengali words in English text.
        assert_eq!(stem_text("bn", "running"), "run");
        assert_eq!(stem_text("en", "বইগুলো"), stem_text("bn", "বইগুলো"));
        assert_eq!(stem_text("en", "книгами"), stem_text("ru", "книгами"));
        // A language with its own endings in a covered script is left alone.
        assert_eq!(stem_text("uk", "книгами"), "книгами");
        assert_eq!(stem_text("ur", "کتابیں"), "کتابیں");
        // A Latin-script language without a stemmer falls back to English.
        assert_eq!(stem_text("nl", "books"), "book");
    }

    #[test]
    fn joiners_are_dropped_and_long_words_kept_whole() {
        assert_eq!(stem_text("fa", "می\u{200C}خواهم"), stem_text("fa", "میخواهم"));
        let long = "a".repeat(MAX + 5) + "s";
        assert_eq!(stem_text("en", &long), long);
    }

    #[test]
    fn every_tag_resolves() {
        for (tag, lang) in [
            ("en_GB", Some(Lang::English)),
            ("pt-BR", Some(Lang::Portuguese)),
            ("nb", Some(Lang::Norwegian)),
            ("in", Some(Lang::Indonesian)),
            ("ca", None),
            ("", None),
        ] {
            assert_eq!(Context::of_tag(tag).lang, lang, "{tag}");
        }
    }
}
