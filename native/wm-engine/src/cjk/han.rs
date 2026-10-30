//! Han characters beyond the readings: OpenCC's Simplified → Traditional
//! conversion (the Kotlin `HanVariant` over `HanPhraseTable`) and the common
//! standard character levels (`HanLevels`).
//!
//! The character and regional tables, and the levels, are compiled in from
//! the Android app's `assets/dictionaries/`; the phrase table is the
//! downloadable `s2t_phrases` pack, which the caller parses and hands in.

use std::collections::HashMap;

macro_rules! asset {
    ($name:literal) => {
        include_str!(concat!(
            "../../../../app/src/main/assets/dictionaries/",
            $name
        ))
    };
}

/// One OpenCC text dictionary (`key<TAB>value value…`), for longest-prefix
/// matching: keys sorted, a match narrowed one character at a time.
#[derive(Clone, Debug, Default)]
pub struct HanPhraseTable {
    keys: Vec<Box<[char]>>,
    values: Vec<String>,
    alternates: HashMap<String, Vec<String>>,
}

impl HanPhraseTable {
    /// OpenCC's text format. The first value is the conversion; the rest stay
    /// reachable through [`HanPhraseTable::alternatives`]. Identity rows are
    /// kept: 皇后 → 皇后 is what stops the character table writing 皇後.
    pub fn parse(text: &str) -> HanPhraseTable {
        let mut rows: HashMap<String, String> = HashMap::new();
        let mut alternates = HashMap::new();
        for raw in text.lines() {
            if raw.is_empty() || raw.starts_with('#') {
                continue;
            }
            let Some((key, rest)) = raw.split_once('\t') else {
                continue;
            };
            let key = key.trim();
            let values: Vec<&str> = rest.trim().split(' ').filter(|v| !v.is_empty()).collect();
            if key.is_empty() || values.is_empty() || rows.contains_key(key) {
                continue;
            }
            rows.insert(key.to_string(), values[0].to_string());
            if values.len() > 1 {
                alternates.insert(
                    key.to_string(),
                    values[1..].iter().map(|v| v.to_string()).collect(),
                );
            }
        }
        let mut sorted: Vec<(String, String)> = rows.into_iter().collect();
        sorted.sort();
        HanPhraseTable {
            keys: sorted.iter().map(|(k, _)| k.chars().collect()).collect(),
            values: sorted.into_iter().map(|(_, v)| v).collect(),
            alternates,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    pub fn key_length(&self, entry: usize) -> usize {
        self.keys[entry].len()
    }

    pub fn value(&self, entry: usize) -> &str {
        &self.values[entry]
    }

    /// The value stored for exactly `key`.
    pub fn get(&self, key: &str) -> Option<&str> {
        let chars: Vec<char> = key.chars().collect();
        let entry = self.match_prefix(&chars, 0, chars.len())?;
        (self.key_length(entry) == chars.len()).then(|| self.value(entry))
    }

    /// The values OpenCC lists after the first for exactly `key`.
    pub fn alternatives(&self, key: &str) -> &[String] {
        self.alternates.get(key).map(Vec::as_slice).unwrap_or(&[])
    }

    /// The entry with the longest key `text` starts with at `start`, looking
    /// no further than `end`.
    pub fn match_prefix(&self, text: &[char], start: usize, end: usize) -> Option<usize> {
        let (mut lo, mut hi) = (0, self.keys.len());
        let mut depth = 0;
        let mut best = None;
        while start + depth < end {
            let c = text[start + depth];
            lo = self.bound(lo, hi, depth, c, false);
            hi = self.bound(lo, hi, depth, c, true);
            if lo >= hi {
                break;
            }
            depth += 1;
            if self.keys[lo].len() == depth {
                best = Some(lo);
            }
        }
        best
    }

    fn bound(&self, lo: usize, hi: usize, depth: usize, c: char, upper: bool) -> usize {
        let (mut l, mut h) = (lo, hi);
        while l < h {
            let mid = (l + h) / 2;
            // A key that ends before `depth` sorts first.
            let k = self.keys[mid].get(depth).map(|&k| k as i64).unwrap_or(-1);
            if k < c as i64 || (upper && k == c as i64) {
                l = mid + 1;
            } else {
                h = mid;
            }
        }
        l
    }
}

/// Which Traditional-using region's conventions to follow; persisted by name.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum HanRegion {
    #[default]
    Generic,
    Taiwan,
    TaiwanWording,
    HongKong,
}

impl HanRegion {
    /// The phone's persisted names (`GENERIC`, `TAIWAN`, …).
    pub fn parse(name: &str) -> HanRegion {
        match name {
            "TAIWAN" => HanRegion::Taiwan,
            "TAIWAN_WORDING" => HanRegion::TaiwanWording,
            "HONG_KONG" => HanRegion::HongKong,
            _ => HanRegion::Generic,
        }
    }
}

/// Simplified → Traditional for candidate output, by OpenCC's own matching:
/// segment by the phrase table, convert each segment (phrases, then
/// characters), then localise through the region's tables. Off, or with no
/// tables, it is the identity.
#[derive(Clone, Debug, Default)]
pub struct HanVariant {
    pub enabled: bool,
    pub region: HanRegion,
    pub s2t: HanPhraseTable,
    pub s2t_phrases: HanPhraseTable,
    pub tw_phrases: HanPhraseTable,
    pub tw_variants_phrases: HanPhraseTable,
    pub tw_variants: HanPhraseTable,
    pub hk_variants_phrases: HanPhraseTable,
    pub hk_variants: HanPhraseTable,
}

impl HanVariant {
    /// The bundled character and regional tables; the phrase pack is the
    /// caller's to add.
    pub fn bundled() -> HanVariant {
        HanVariant {
            enabled: false,
            region: HanRegion::Generic,
            s2t: HanPhraseTable::parse(asset!("s2t.txt")),
            s2t_phrases: HanPhraseTable::default(),
            tw_phrases: HanPhraseTable::parse(asset!("tw_phrases.txt")),
            tw_variants_phrases: HanPhraseTable::parse(asset!("tw_variants_phrases.txt")),
            tw_variants: HanPhraseTable::parse(asset!("tw_variants.txt")),
            hk_variants_phrases: HanPhraseTable::parse(asset!("hk_variants_phrases.txt")),
            hk_variants: HanPhraseTable::parse(asset!("hk_variants.txt")),
        }
    }

    fn regional(&self) -> Option<Vec<&HanPhraseTable>> {
        match self.region {
            HanRegion::Generic => None,
            HanRegion::Taiwan => Some(vec![&self.tw_variants_phrases, &self.tw_variants]),
            HanRegion::TaiwanWording => Some(vec![
                &self.tw_phrases,
                &self.tw_variants_phrases,
                &self.tw_variants,
            ]),
            HanRegion::HongKong => Some(vec![&self.hk_variants_phrases, &self.hk_variants]),
        }
    }

    pub fn to_traditional(&self, text: &str) -> String {
        if !self.enabled || text.is_empty() || (self.s2t_phrases.is_empty() && self.s2t.is_empty())
        {
            return text.to_string();
        }
        let chars: Vec<char> = text.chars().collect();
        let standard = [&self.s2t_phrases, &self.s2t];
        let regional = self.regional();
        let mut out = String::with_capacity(text.len() + 4);
        let (mut run, mut i) = (0, 0);
        while i < chars.len() {
            let Some(entry) = self.s2t_phrases.match_prefix(&chars, i, chars.len()) else {
                i += 1;
                continue;
            };
            if run < i {
                segment(&chars[run..i], &standard, regional.as_deref(), &mut out);
            }
            let end = i + self.s2t_phrases.key_length(entry);
            segment(&chars[i..end], &standard, regional.as_deref(), &mut out);
            i = end;
            run = end;
        }
        if run < chars.len() {
            segment(&chars[run..], &standard, regional.as_deref(), &mut out);
        }
        out
    }

    /// Every Traditional form a candidate can be written in, the conversion
    /// first: a lone character also offers the character table's other
    /// values (发 → 發, then 髮), each through the region's tables.
    pub fn traditional_forms(&self, text: &str) -> Vec<String> {
        let primary = self.to_traditional(text);
        if !self.enabled || text.chars().count() != 1 {
            return vec![primary];
        }
        let alternatives = self.s2t.alternatives(text);
        if alternatives.is_empty() {
            return vec![primary];
        }
        let regional = self.regional();
        let mut forms = vec![primary];
        for alternative in alternatives {
            let form = match &regional {
                None => alternative.clone(),
                Some(group) => {
                    let chars: Vec<char> = alternative.chars().collect();
                    let mut s = String::new();
                    convert(&chars, group, &mut s);
                    s
                }
            };
            if !forms.contains(&form) {
                forms.push(form);
            }
        }
        forms
    }
}

fn segment(
    text: &[char],
    standard: &[&HanPhraseTable],
    regional: Option<&[&HanPhraseTable]>,
    out: &mut String,
) {
    match regional {
        None => convert(text, standard, out),
        Some(group) => {
            let mut traditional = String::new();
            convert(text, standard, &mut traditional);
            let chars: Vec<char> = traditional.chars().collect();
            convert(&chars, group, out);
        }
    }
}

/// OpenCC's `Conversion::Convert` with a short-circuit table group: the first
/// table with any match at a position wins.
fn convert(text: &[char], group: &[&HanPhraseTable], out: &mut String) {
    let mut i = 0;
    while i < text.len() {
        let hit = group
            .iter()
            .find_map(|t| t.match_prefix(text, i, text.len()).map(|e| (t, e)));
        match hit {
            Some((t, e)) => {
                out.push_str(t.value(e));
                i += t.key_length(e);
            }
            None => {
                out.push(text[i]);
                i += 1;
            }
        }
    }
}

/// The 通用规范汉字表 (2013) levels: what a character costs beyond its
/// corpus frequency, so debris like 琌 sinks below the characters people write.
#[derive(Clone, Debug, Default)]
pub struct HanLevels {
    levels: HashMap<char, u8>,
}

const LEVEL_2: f64 = -0.4;
const LEVEL_3: f64 = -1.2;
const OUTSIDE: f64 = -2.5;

impl HanLevels {
    /// `level<TAB>characters` per line; a character listed twice keeps its
    /// lower level.
    pub fn parse(text: &str) -> HanLevels {
        let mut levels = HashMap::new();
        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((level, chars)) = line.split_once('\t') else {
                continue;
            };
            let Ok(level) = level.parse::<u8>() else {
                continue;
            };
            if !(1..=3).contains(&level) {
                continue;
            }
            for c in chars.chars() {
                let e = levels.entry(c).or_insert(level);
                *e = (*e).min(level);
            }
        }
        HanLevels { levels }
    }

    /// A table from parallel code points and levels, as the phone holds it.
    pub fn from_points(points: &[i32], levels: &[i8]) -> HanLevels {
        let levels = points
            .iter()
            .zip(levels)
            .filter_map(|(&p, &l)| Some((char::from_u32(p as u32)?, l as u8)))
            .collect();
        HanLevels { levels }
    }

    /// The bundled table.
    pub fn bundled() -> HanLevels {
        HanLevels::parse(asset!("han_levels.txt"))
    }

    pub fn is_empty(&self) -> bool {
        self.levels.is_empty()
    }

    /// 1, 2 or 3; 0 for a character the table does not list.
    pub fn level_of(&self, c: char) -> u8 {
        self.levels.get(&c).copied().unwrap_or(0)
    }

    /// A single character pays its level in full; a longer word only for a
    /// character outside the table altogether.
    pub fn penalty(&self, word: &str) -> f64 {
        if self.is_empty() || word.is_empty() {
            return 0.0;
        }
        let single = word.chars().count() == 1;
        let mut worst: f64 = 0.0;
        for c in word.chars() {
            if !is_ideographic(c) {
                continue;
            }
            let cost = match self.level_of(c) {
                1 => 0.0,
                2 if single => LEVEL_2,
                3 if single => LEVEL_3,
                2 | 3 => 0.0,
                _ => OUTSIDE,
            };
            worst = worst.min(cost);
        }
        worst
    }
}

/// Java's `Character.isIdeographic`: the CJK unified and compatibility
/// ideographs, their extensions, and 〆〇 and the Hangzhou numerals.
pub fn is_ideographic(c: char) -> bool {
    matches!(c as u32,
        0x3006 | 0x3007 | 0x3021..=0x3029 | 0x3038..=0x303A
        | 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF
        | 0x16FE4 | 0x17000..=0x18CFF | 0x18D00..=0x18D08 | 0x1B170..=0x1B2FB
        | 0x20000..=0x2FFFF | 0x30000..=0x3FFFF)
}

/// Whether `c` is in the Han script, as `Character.UnicodeScript.HAN` has it.
pub fn is_han(c: char) -> bool {
    matches!(c as u32,
        0x2E80..=0x2E99 | 0x2E9B..=0x2EF3 | 0x2F00..=0x2FD5 | 0x3005 | 0x3007
        | 0x3021..=0x3029 | 0x3038..=0x303B | 0x3400..=0x4DBF | 0x4E00..=0x9FFF
        | 0xF900..=0xFA6D | 0xFA70..=0xFAD9 | 0x16FE2 | 0x16FE3 | 0x16FF0 | 0x16FF1
        | 0x20000..=0x2A6DF | 0x2A700..=0x2EBEF | 0x2EBF0..=0x2EE5D | 0x2F800..=0x2FA1D
        | 0x30000..=0x323AF)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_by_phrase_then_character_then_region() {
        let mut v = HanVariant::bundled();
        assert_eq!("头发", v.to_traditional("头发"));
        v.enabled = true;
        // Characters alone: 发 takes OpenCC's first value.
        assert_eq!("頭發", v.to_traditional("头发"));
        v.s2t_phrases = HanPhraseTable::parse("头发\t頭髮\n");
        assert_eq!("頭髮", v.to_traditional("头发"));
        let forms = v.traditional_forms("发");
        assert_eq!("發", forms[0]);
        assert!(forms.contains(&"髮".to_string()));
        v.region = HanRegion::Taiwan;
        assert_eq!("為", v.to_traditional("为"));
    }

    #[test]
    fn levels_cost_rare_characters() {
        let l = HanLevels::bundled();
        assert_eq!(1, l.level_of('一'));
        assert_eq!(0.0, l.penalty("一"));
        assert!(l.penalty("琌") < l.penalty("琵"));
        assert_eq!(0.0, l.penalty("琵琶"));
    }
}
