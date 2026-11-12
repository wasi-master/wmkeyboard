//! The sentence harness: whole sentences typed by a simulated hand, space bar
//! included, committed word by word the way the keyboard commits them, and
//! scored by what ends up in the field.
//!
//! ```text
//! cargo run --release --example sentence_eval -- <words.txt> <sentences> [model.wmlm]
//! ```
//!
//! `typing_eval` asks whether a single mistyped word is fixed. This asks the
//! question a user asks: after typing a sentence, how many words are wrong?
//! The hand is fitted to the TSI dataset (Google, CC-BY-4.0: 16 people,
//! 43,735 phone taps with their coordinates, autocorrect off):
//!
//! - letter taps scatter around their key with a per-person spread drawn from
//!   the 16 measured people (x 0.20-0.39 key widths, y 0.12-0.21 key heights),
//!   landing a little left and high on average;
//! - keys are 1.53 key widths tall (`ROW`), as on a phone, not square;
//! - the space bar is five keys wide under c..m, and its taps sit a fifth of a
//!   key high with a wide spread, so about one in ten lands on `n`, `b` or
//!   `v` and joins two words, as it did in the data (10.7%);
//! - about 1% of letters are never tapped, and smaller shares are typed
//!   twice, swapped with the next, or misspelled from memory (`MISSPELL`, a
//!   Birkbeck-format file of real misspellings).
//!
//! `HAND` scales the spread: 1.0 is the lab (TSI's people typing fast and
//! carefully, phone in both hands), 1.3 on the go, 1.6 sloppy; all three by
//! default. Words with an apostrophe are typed without it, since that key is
//! a layer away. The last word of a sentence ends with the full stop, which
//! corrects it only with `PUNCT=1` (the shipped default leaves it as typed).
//!
//! Environment: `SENTENCES` how many (2000), `SKIP` first sentence, `HAND`
//! comma list, `PACK=dir` with `en_bigrams.txt` / `en_trigrams.txt`, `SEEDS`,
//! `MISSPELL=file[,file...]`, `PUNCT`, `CONF=1` the learned gate, `SPLITS`
//! (1), `CONFIDENCE` the slider (4.0), `SHOW=n` to print n sentences with
//! what was typed and what the field holds, `ERRORS=file` every word the
//! field got wrong, with its kind.
//!
//! The sentence file is plain text, one sentence a line, or Tatoeba's
//! `id\tlang\ttext` export.

use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::sync::Arc;
use std::time::Instant;

use wm_engine::confidence::Confidence;
use wm_engine::decoder::{self, Geometry, Pairs, Params, Ranks, Source, Tap};
use wm_engine::fuzzy::TouchModel;
use wm_engine::lm::{Lm, Session};
use wm_engine::predict::{Predictor, SeedBigrams, WordList};
use wm_engine::proximity::KeyProximity;
use wm_engine::wmng::{self, NgramPackBuilder, Pack};
use wm_engine::wordlist;

/// Key height over key width on a phone keyboard (TSI: 206 / 135 px).
const ROW: f32 = 1.53;
/// The 16 TSI participants' letter spread: (x in key widths, y in key
/// heights, mean y offset in key heights).
const PEOPLE: [(f32, f32, f32); 16] = [
    (0.200, 0.133, -0.061),
    (0.226, 0.145, 0.024),
    (0.232, 0.160, -0.083),
    (0.236, 0.169, -0.048),
    (0.239, 0.150, -0.027),
    (0.240, 0.194, 0.027),
    (0.250, 0.115, -0.121),
    (0.255, 0.191, -0.082),
    (0.257, 0.193, 0.017),
    (0.257, 0.214, -0.013),
    (0.258, 0.158, -0.005),
    (0.262, 0.185, -0.036),
    (0.274, 0.160, -0.061),
    (0.280, 0.192, 0.032),
    (0.338, 0.211, -0.115),
    (0.385, 0.202, -0.028),
];
/// Mean x offset of a letter tap, key widths (TSI).
const X_BIAS: f32 = -0.062;
/// Space taps: centre of the bar, spread in key widths, height offset and
/// spread in key heights (TSI).
const SPACE_X: f32 = 4.5;
const SPACE_HALF_WIDTH: f32 = 2.5;
const SPACE_SD_X: f32 = 0.73;
const SPACE_DY: f32 = -0.21;
const SPACE_SD_Y: f32 = 0.235;
/// Per-letter rates of the errors that are not scatter.
const P_OMIT: f64 = 0.010;
const P_DOUBLE: f64 = 0.003;
const P_SWAP: f64 = 0.003;

struct Rng(u64);
impl Rng {
    fn uniform(&mut self) -> f64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }
    fn gaussian(&mut self) -> f64 {
        let (u1, u2) = (self.uniform().max(1e-12), self.uniform());
        (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
    }
    fn pick(&mut self, n: usize) -> usize {
        ((self.uniform() * n as f64) as usize).min(n.saturating_sub(1))
    }
}

fn env<T: std::str::FromStr>(name: &str, default: T) -> T {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn layout_rows(layout: &str) -> Vec<&'static str> {
    rows_of(layout).iter().map(|r| r.0).collect()
}

fn rows_of(layout: &str) -> [(&'static str, f32); 3] {
    match layout {
        "qwertz" => [("qwertzuiop", 0.0), ("asdfghjkl", 0.5), ("yxcvbnm", 1.5)],
        "azerty" => [("azertyuiop", 0.0), ("qsdfghjklm", 0.0), ("wxcvbn", 1.5)],
        _ => [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)],
    }
}

/// Letter centres in key widths, rows `ROW` apart, for `LAYOUT` (qwerty,
/// qwertz, azerty).
fn keys(layout: &str) -> Vec<(u16, f32, f32)> {
    let rows = rows_of(layout);
    let mut keys = Vec::new();
    for (row, (letters, offset)) in rows.iter().enumerate() {
        for (col, ch) in letters.chars().enumerate() {
            keys.push((ch as u16, offset + col as f32, row as f32 * ROW));
        }
    }
    keys
}

/// What a tap at `p` types: a letter, or `' '` on the space bar. Keys are
/// rectangles one wide and `ROW` tall; a tap between them goes to the
/// nearest centre.
fn hit(keys: &[(u16, f32, f32)], p: (f32, f32)) -> char {
    let space_y = 3.0 * ROW;
    if (p.1 - space_y).abs() <= ROW / 2.0 && (p.0 - SPACE_X).abs() <= SPACE_HALF_WIDTH {
        return ' ';
    }
    if p.1 > space_y - ROW / 2.0 {
        // Beside the bar on the bottom row: comma, full stop and friends,
        // which the harness reads as a space (they end the word too).
        return ' ';
    }
    let mut best = (f32::MAX, 'a');
    for k in keys {
        let (dx, dy) = ((p.0 - k.1).abs(), (p.1 - k.2).abs());
        let inside = dx <= 0.5 && dy <= ROW / 2.0;
        let d = if inside { -1.0 + dx * dx + dy * dy } else { dx * dx + dy * dy };
        if d < best.0 {
            best = (d, char::from_u32(k.0 as u32).unwrap());
        }
    }
    best.1
}

/// A recorded keyboard (`REPLAY`): its letter keys as the decoder takes
/// them, every key with its rectangle for [`Board::hit`], and the space bar.
struct Board {
    keys: Vec<(u16, f32, f32)>,
    rects: Vec<(char, f32, f32, f32, f32)>,
    space: (f32, f32, f32, f32),
}

impl Board {
    /// What a tap at `p` typed on this keyboard: the key whose rectangle
    /// holds it, else the nearest centre; `' '` for the bar and for keys
    /// that end a word (full stop, comma).
    fn hit(&self, p: (f32, f32)) -> char {
        let (cx, cy, hw, hh) = self.space;
        if (p.0 - cx).abs() <= hw && (p.1 - cy).abs() <= hh {
            return ' ';
        }
        let mut best = (f32::MAX, ' ');
        for &(c, x, y, w, h) in &self.rects {
            let (dx, dy) = ((p.0 - x).abs(), (p.1 - y).abs());
            let d = if dx <= w / 2.0 && dy <= h / 2.0 { -1.0 + dx * dx + dy * dy } else { dx * dx + dy * dy };
            if d < best.0 {
                best = (d, c);
            }
        }
        let space_d = {
            let dx = ((p.0 - cx).abs() - hw).max(0.0);
            let dy = ((p.1 - cy).abs() - hh).max(0.0);
            dx * dx + dy * dy
        };
        if best.0 > space_d || !(best.1.is_alphabetic() || best.1 == '\'') {
            ' '
        } else {
            best.1
        }
    }
}

/// `keyboards.tsv` from `real_taps.py`: name, key, x, y, width, height.
fn boards_of(path: &str) -> HashMap<String, Board> {
    let mut out: HashMap<String, Board> = HashMap::new();
    for line in std::fs::read_to_string(path).expect("the keyboards file").lines() {
        let f: Vec<&str> = line.split('\t').collect();
        if f.len() < 6 {
            continue;
        }
        let v: Vec<f32> = f[2..6].iter().map(|x| x.parse().unwrap_or(0.0)).collect();
        let b = out.entry(f[0].to_string()).or_insert(Board { keys: Vec::new(), rects: Vec::new(), space: (0.0, 0.0, 0.0, 0.0) });
        if f[1] == "<sp>" {
            b.space = (v[0], v[1], v[2] / 2.0, v[3] / 2.0);
            continue;
        }
        let mut chars = f[1].chars();
        if let (Some(c), None) = (chars.next(), chars.next()) {
            let c = c.to_lowercase().next().unwrap_or(c);
            b.rects.push((c, v[0], v[1], v[2], v[3]));
            if c.is_alphabetic() || c == '\'' {
                b.keys.push((c as u16, v[0], v[1]));
            }
        }
    }
    out
}

struct Hand {
    sx: f32,
    sy: f32,
    dy: f32,
}

/// One keystroke: the character it typed and where the finger landed.
#[derive(Clone, Copy)]
struct Stroke {
    ch: char,
    at: (f32, f32),
}

fn tap_letter(rng: &mut Rng, keys: &[(u16, f32, f32)], hand: &Hand, ch: char) -> Stroke {
    let k = keys.iter().find(|k| k.0 == ch as u16).expect("a letter key");
    let at = (
        k.1 + X_BIAS + (rng.gaussian() as f32) * hand.sx,
        k.2 + (hand.dy + (rng.gaussian() as f32) * hand.sy) * ROW,
    );
    Stroke { ch: hit(keys, at), at }
}

fn tap_space(rng: &mut Rng, keys: &[(u16, f32, f32)], scale: f32) -> Stroke {
    let at = (
        SPACE_X + (rng.gaussian() as f32) * SPACE_SD_X * scale,
        3.0 * ROW + (SPACE_DY + (rng.gaussian() as f32) * SPACE_SD_Y * scale) * ROW,
    );
    Stroke { ch: hit(keys, at), at }
}

/// Real misspellings, Birkbeck format: `$correct` then one misspelling a
/// line. Only plain lower-case words, and only misspellings no list holds.
fn misspellings(paths: &str, listed: &HashSet<String>) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for path in paths.split(',').filter(|p| !p.is_empty()) {
        let text = std::fs::read_to_string(path).expect("a misspelling file");
        let mut correct = String::new();
        for line in text.lines() {
            let line = line.trim();
            if let Some(c) = line.strip_prefix('$') {
                correct = c.to_string();
                continue;
            }
            let ok = |w: &str| !w.is_empty() && w.chars().all(|c| c.is_ascii_lowercase());
            if ok(&correct) && ok(line) && line != correct && !listed.contains(line) {
                out.entry(correct.clone()).or_default().push(line.to_string());
            }
        }
    }
    for v in out.values_mut() {
        v.sort();
        v.dedup();
    }
    out
}

/// The key a letter is typed on: itself, or its bare letter when the
/// layout has no key for it (`é` on `e`); the hand leaves accents to the
/// keyboard, as most typists do.
fn key_of(c: char) -> char {
    let bare = wm_engine::accents::bare(c as u16);
    char::from_u32(bare as u32).unwrap_or(c)
}

/// A sentence as the harness types it: lower-case words of letters and
/// apostrophes, or nothing when the sentence holds anything else.
fn words_of(text: &str) -> Option<(Vec<String>, char)> {
    let text = text.trim();
    let end = text.chars().last()?;
    let end = if ".?!".contains(end) { end } else { '.' };
    let mut words = Vec::new();
    for raw in text.split_whitespace() {
        let w: String = raw
            .trim_matches(|c: char| ",.;:!?\"()".contains(c))
            .chars()
            .map(|c| if c == '\u{2019}' { '\'' } else { c })
            .collect();
        if w.is_empty() {
            continue;
        }
        if !w.chars().all(|c| c.is_alphabetic() || c == '\'') || w.starts_with('\'') || w.ends_with('\'') {
            return None;
        }
        words.push(w.to_lowercase());
    }
    (words.len() >= 3 && words.len() <= 20).then_some((words, end))
}

/// Word-level edit distance.
fn word_distance(a: &[String], b: &[String]) -> usize {
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    for i in 1..=a.len() {
        let mut cur = vec![i; b.len() + 1];
        for j in 1..=b.len() {
            let sub = prev[j - 1] + usize::from(a[i - 1] != b[j - 1]);
            cur[j] = sub.min(prev[j] + 1).min(cur[j - 1] + 1);
        }
        prev = cur;
    }
    prev[b.len()]
}

#[derive(Default)]
struct Tally {
    sentences: usize,
    words: usize,
    literal_errors: usize,
    errors: usize,
    clean_sentences: usize,
    // Per committed buffer, by what the hand did to it.
    buffers: HashMap<&'static str, (usize, usize)>,
    damaged: usize,
    intact: usize,
    decide_us: u128,
    decides: usize,
    rechecks: usize,
    recheck_fixed: usize,
    recheck_broke: usize,
}

/// The buffer committed last, for `RECHECK`: its taps, where its words
/// start in the field, and what was meant.
struct Last {
    taps: Vec<Tap>,
    start: usize,
    want: String,
}

impl Tally {
    fn add(&mut self, o: &Tally) {
        self.sentences += o.sentences;
        self.words += o.words;
        self.literal_errors += o.literal_errors;
        self.errors += o.errors;
        self.clean_sentences += o.clean_sentences;
        for (k, (n, fixed)) in &o.buffers {
            let e = self.buffers.entry(k).or_default();
            e.0 += n;
            e.1 += fixed;
        }
        self.damaged += o.damaged;
        self.intact += o.intact;
        self.decide_us += o.decide_us;
        self.decides += o.decides;
        self.rechecks += o.rechecks;
        self.recheck_fixed += o.recheck_fixed;
        self.recheck_broke += o.recheck_broke;
    }

    fn clone_counts(&self) -> Tally {
        let mut t = Tally::default();
        t.add(self);
        t
    }

    fn line(&self, label: &str) -> String {
        let w = self.words.max(1) as f64;
        let mut kinds: Vec<_> = self.buffers.iter().collect();
        kinds.sort_by_key(|(k, _)| *k);
        let kinds: Vec<String> = kinds
            .iter()
            .map(|(k, (n, fixed))| format!("{k} {}/{} ({:.0}%)", fixed, n, 100.0 * *fixed as f64 / (*n).max(1) as f64))
            .collect();
        format!(
            "{label}: WER typed {:.2}% -> field {:.2}% | sentences clean {:.1}% | correct words changed {:.2}% ({}/{}) | fixed: {} | recheck {} changed, {} fixed, {} broke | {:.0} us/decide",
            100.0 * self.literal_errors as f64 / w,
            100.0 * self.errors as f64 / w,
            100.0 * self.clean_sentences as f64 / self.sentences.max(1) as f64,
            100.0 * self.damaged as f64 / self.intact.max(1) as f64,
            self.damaged,
            self.intact,
            kinds.join(", "),
            self.rechecks,
            self.recheck_fixed,
            self.recheck_broke,
            self.decide_us as f64 / self.decides.max(1) as f64,
        )
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: sentence_eval <words.txt> <sentences> [model.wmlm]");
        std::process::exit(2);
    }
    let lang: String = env("LANG_ID", "en".to_string());
    let layout: String = env("LAYOUT", "qwerty".to_string());
    let entries = wordlist::parse(&std::fs::read_to_string(&args[1]).expect("the word list"));
    let listed: HashSet<String> = entries.iter().map(|(w, _)| w.clone()).collect();
    let count: usize = env("SENTENCES", 2000);
    let skip: usize = env("SKIP", 0);
    let raw = std::fs::read_to_string(&args[2]).expect("the sentences");
    let layout_keys = keys(&layout);
    let mut sentences: Vec<(Vec<String>, char)> = raw
        .lines()
        .filter_map(|line| {
            let mut parts = line.split('\t');
            let text = match (parts.next(), parts.next(), parts.next()) {
                (Some(_), Some(_), Some(t)) => t,
                _ => line,
            };
            words_of(text)
        })
        // Only sentences every letter of which some key types.
        .filter(|(ws, _)| {
            ws.iter().all(|w| w.chars().all(|c| c == '\'' || layout_keys.iter().any(|k| k.0 == key_of(c) as u16)))
        })
        .skip(skip)
        .take(count)
        .collect();
    sentences.dedup();
    eprintln!("{} sentences, {} words", sentences.len(), sentences.iter().map(|s| s.0.len()).sum::<usize>());

    let pack_bytes: Option<Vec<u8>> = std::env::var("PACK").ok().map(|dir| {
        let mut b = NgramPackBuilder::new();
        let read = |name: &str, parts: usize, cap: usize, f: &mut dyn FnMut(&[&str], i32)| {
            let text = std::fs::read_to_string(format!("{dir}/{name}")).expect(name);
            for line in text.lines().filter(|l| !l.starts_with('#')).take(cap) {
                let Some(cut) = line.rfind(' ') else { continue };
                let Ok(c) = line[cut + 1..].parse::<i32>() else { continue };
                let words: Vec<&str> = line[..cut].split_whitespace().collect();
                if words.len() == parts {
                    f(&words, c);
                }
            }
        };
        read(&format!("{lang}_bigrams.txt"), 2, 150_000, &mut |w, c| b.add_bigram(w[0], w[1], c));
        read(&format!("{lang}_trigrams.txt"), 3, 75_000, &mut |w, c| b.add_trigram(w[0], w[1], w[2], c));
        wmng::write(&b.build())
    });
    let seeds_path = std::env::var("SEEDS").unwrap_or_else(|_| {
        if lang == "en" { "../../app/src/main/assets/dictionaries/en_bigrams.txt".into() } else { String::new() }
    });
    let seeds_text = std::fs::read_to_string(&seeds_path).unwrap_or_default();
    let lm: Option<Arc<Lm>> = args
        .get(3)
        .map(|p| Arc::new(Lm::open(std::fs::read(p).expect("the model")).expect("a .wmlm")));
    let misspelled = std::env::var("MISSPELL").map(|p| misspellings(&p, &listed)).unwrap_or_default();
    let p_misspell: f64 = env("P_MISSPELL", 0.02);
    let punct_corrects = env::<i32>("PUNCT", 0) == 1;
    let show: usize = env("SHOW", 0);
    let mut errors_out = std::env::var("ERRORS").ok().map(|p| std::fs::File::create(p).expect("the errors file"));
    // `CASES_OUT=file`: every one-word buffer with its taps, for the tap
    // encoder's eval (`tools/swipe-ctc/taps_sentence_eval.py`): hand, kind,
    // meant, typed, `x,y;x,y...` in key widths, the decoder's best reading
    // (or the typed text when it reads best as typed), and what the field got.
    let mut cases_out = std::env::var("CASES_OUT").ok().map(|p| std::fs::File::create(p).expect("the cases file"));
    let hands: Vec<f32> = std::env::var("HAND")
        .unwrap_or_else(|_| "1.0,1.3,1.6".into())
        .split(',')
        .filter_map(|v| v.parse().ok())
        .collect();
    let keys = layout_keys.clone();

    let mut p = Predictor::new();
    p.dictionary = Some(WordList::of(&entries));
    p.english_sources = lang == "en";
    p.primary_language = lang.clone();
    p.context_rerank = true;
    p.proximity = if layout == "qwerty" { KeyProximity::qwerty(false) } else { KeyProximity::of_rows(&layout_rows(&layout)) };
    p.seeds = SeedBigrams::parse(&seeds_text);
    p.ngrams = pack_bytes.as_ref().and_then(|b| Pack::open(b.clone()));
    p.touch = Some(TouchModel::new(keys.clone(), TouchModel::SIGMA));
    p.apostrophe_fixes = true;
    p.autocorrect_confidence = env("CONFIDENCE", 4.0);
    p.autocorrect_splits = env::<i32>("SPLITS", 1) == 1;
    if env::<i32>("CONF", 0) == 1 {
        p.confidence = Some(Confidence::shipped());
    }
    if let Some(m) = &lm {
        p.lm = Some(std::cell::RefCell::new(Session::new(m.clone())));
    }

    // The decoder, when asked for: the same list and pairs, read whole.
    let use_decoder = env::<i32>("DECODER", 0) == 1;
    let union = env::<i32>("UNION", 0) == 1;
    let dec_list = WordList::of(&entries);
    let ranks = Ranks::of(dec_list.dict());
    let dec_prior = |f: i32| ranks.prior(f);
    // `LIST2=file`: a second list searched beside the first, each word on
    // its own list's Zipf prior (a word in both takes the better).
    let list2: Option<WordList> = std::env::var("LIST2")
        .ok()
        .map(|path| WordList::of(&wordlist::parse(&std::fs::read_to_string(path).expect("LIST2"))));
    let ranks2 = list2.as_ref().map(|l| Ranks::of(l.dict())).unwrap_or_default();
    let prior2 = |f: i32| ranks2.prior(f);
    // `LEX=n`: a user lexicon learned from the n sentences after the test
    // set, each word at the count the user typed it, searched beside the
    // lists on the learned prior with a word typed once at `LEX_RANK`.
    let lex_n: usize = env("LEX", 0);
    let lex_rank: f64 = env("LEX_RANK", decoder::LEARNED_RANK_ONCE);
    let lexicon: Option<WordList> = (lex_n > 0).then(|| {
        let mut counts: HashMap<String, i32> = HashMap::new();
        raw.lines()
            .filter_map(|line| {
                let mut parts = line.split('\t');
                let text = match (parts.next(), parts.next(), parts.next()) {
                    (Some(_), Some(_), Some(t)) => t,
                    _ => line,
                };
                words_of(text)
            })
            .skip(skip + count)
            .take(lex_n)
            .for_each(|(ws, _)| {
                for w in ws {
                    *counts.entry(w.replace('\'', "")).or_default() += 1;
                }
            });
        // `LEX_NOISE=p`: that share of the lexicon's words also learned once
        // misspelled, one letter swapped for its neighbour on the row, the
        // way a typo the keyboard let through ends up learned.
        let noise: f64 = env("LEX_NOISE", 0.0);
        if noise > 0.0 {
            let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
            let mut rng = Rng(0x9e37_79b9_7f4a_7c15);
            let words: Vec<String> = counts.keys().cloned().collect();
            for w in words {
                if w.len() < 3 || rng.uniform() >= noise {
                    continue;
                }
                let mut chars: Vec<char> = w.chars().collect();
                let i = rng.pick(chars.len());
                if let Some(row) = rows.iter().find(|r| r.contains(chars[i])) {
                    let r: Vec<char> = row.chars().collect();
                    let at = r.iter().position(|&c| c == chars[i]).unwrap();
                    chars[i] = if at + 1 < r.len() { r[at + 1] } else { r[at - 1] };
                    let typo: String = chars.into_iter().collect();
                    if !listed.contains(&typo) {
                        counts.entry(typo).or_insert(1);
                    }
                }
            }
        }
        let entries: Vec<(String, i32)> = counts.into_iter().collect();
        eprintln!("lexicon: {} words", entries.len());
        WordList::of(&entries)
    });
    let list_size = ranks.size().max(ranks2.size());
    let lex_prior = |f: i32| decoder::zipf((lex_rank / f.max(1) as f64).max(1.0) as usize, list_size);
    let lm_w: f64 = env("LM_W", decoder::MODEL_WEIGHT);
    let dec_pack = pack_bytes.as_ref().and_then(|b| Pack::open(b.clone()));
    let dec_seeds = SeedBigrams::parse(&seeds_text);
    let bigram = |a: &str, b: &str| dec_pack.as_ref().map_or(0, |p| p.bigram_count(a, b)).max(dec_seeds.count(a, b));
    let trigram = |a: &str, b: &str, c: &str| dec_pack.as_ref().map_or(0, |p| p.trigram_count(a, b, c));
    let dec_context = Pairs {
        bigram: &bigram,
        trigram: &trigram,
        alpha: env("CTX_ALPHA", decoder::PAIR_ALPHA),
        tri: env("CTX_TRI", decoder::TRIPLE_ALPHA),
        cap: env("CTX_CAP", decoder::PAIR_CAP),
    };
    let mut dec_params = Params::default();
    dec_params.sigma_x = env("SIGMA_X", dec_params.sigma_x);
    dec_params.sigma_y = env("SIGMA_Y", dec_params.sigma_y);
    dec_params.ln_unknown_word = env::<f64>("UNKNOWN", dec_params.ln_unknown_word.exp()).ln();
    dec_params.ln_space_omit = env::<f64>("SPACE_OMIT", dec_params.ln_space_omit.exp()).ln();
    dec_params.ln_extra_word = env::<f64>("EXTRA_WORD", dec_params.ln_extra_word.exp()).ln();
    dec_params.beam = env("BEAM", dec_params.beam);
    dec_params.ln_far = env::<f64>("FAR", dec_params.ln_far.exp()).ln();
    dec_params.ln_swap = env::<f64>("SWAP", dec_params.ln_swap.exp()).ln();
    dec_params.ln_omit = env::<f64>("OMIT", dec_params.ln_omit.exp()).ln();
    dec_params.ln_insert = env::<f64>("INSERT", dec_params.ln_insert.exp()).ln();
    dec_params.ln_double = env::<f64>("DOUBLE", dec_params.ln_double.exp()).ln();
    dec_params.ln_unknown_letter = env::<f64>("UNKNOWN_LETTER", dec_params.ln_unknown_letter.exp()).ln();
    let recheck_on = env::<i32>("RECHECK", 0) == 1;
    let recheck_margin: f64 = env("RECHECK_MARGIN", decoder::RECHECK_MARGIN);
    let recheck_known: f64 = env("RECHECK_KNOWN", decoder::RECHECK_KNOWN_MARGIN);
    let apply_margin: f64 = env("MARGIN", decoder::APPLY_MARGIN);
    let known_margin: f64 = env("KNOWN_MARGIN", decoder::KNOWN_MARGIN);
    let accent_margin: f64 = env("ACCENT_MARGIN", decoder::ACCENT_MARGIN);
    let geometry = Geometry { keys: keys.clone(), space: Some((SPACE_X, 3.0 * ROW, SPACE_HALF_WIDTH, ROW / 2.0)) };
    let proximity = if layout == "qwerty" { KeyProximity::qwerty(false) } else { KeyProximity::of_rows(&layout_rows(&layout)) };
    let adjacent = |a: u16, b: u16| proximity.are_adjacent(a, b);

    // `PROBE=word,word` prints the decoder's readings of each word tapped
    // dead centre, with the priors it used, and stops.
    if let Ok(probe) = std::env::var("PROBE") {
        for w in probe.split(',') {
            let dtaps: Vec<Tap> = w
                .chars()
                .map(|c| {
                    let k = keys.iter().find(|k| k.0 == c as u16).unwrap();
                    Tap { typed: c as u16, at: Some((k.1, k.2)) }
                })
                .collect();
            let src = [Source { walker: dec_list.dict(), prior: &dec_prior }];
            let d = decoder::decode(&dtaps, &geometry, &src, &dec_context, &[], &dec_params, &adjacent, 6);
            println!("{w}: literal {:.2} listed {}", d.literal, d.literal_listed);
            for r in &d.readings {
                let f = dec_list.frequency_of(&r.text);
                println!("  {:<24} {:>8.2}  freq {f} rank {} prior {:.2}", r.text, r.score, ranks.rank(f), dec_prior(f));
            }
        }
        return;
    }

    // One sentence through the keyboard: split the strokes on what actually
    // hit the bar, commit each buffer the way a space commits it, and score
    // what the field holds. `owner` says which word each stroke was meant
    // for, when known; `touch` replaces the old rules' touch model (a
    // recorded keyboard's own keys).
    #[allow(clippy::too_many_arguments)]
    let mut type_sentence = |words: &[String],
                             strokes: &[Stroke],
                             owner: Option<&[usize]>,
                             kinds: &[&'static str],
                             geometry: &Geometry,
                             touch: Option<TouchModel>,
                             t: &mut Tally,
                             show_it: bool,
                             label: &str,
                             commits: &mut Vec<(String, Vec<(f32, f32)>)>,
                             sigma: Option<(f64, f64)>| {
        if let Some(tm) = touch {
            p.touch = Some(tm);
        }
        let params_here = {
            let mut q = dec_params.clone();
            if let Some((sx, sy)) = sigma {
                q.sigma_x = sx;
                q.sigma_y = sy;
            }
            q
        };
        // The keyboard: split the strokes on what actually hit the bar,
        // and commit each buffer the way a space commits it.
        let mut field: Vec<String> = Vec::new();
        let mut literal: Vec<String> = Vec::new();
        let mut buf = String::new();
        let mut taps: Vec<Option<(f32, f32)>> = Vec::new();
        let mut owners: Vec<Option<usize>> = Vec::new();
        let mut typed_view = String::new();
        let mut prior_buffer: Option<Last> = None;
        let n = strokes.len();
        for (k, s) in strokes.iter().enumerate() {
            let last = k + 1 == n;
            if s.ch != ' ' {
                buf.push(s.ch);
                taps.push(Some(s.at));
                owners.push(owner.map(|o| o[k]));
                typed_view.push(s.ch);
            }
            if (s.ch == ' ' || last) && !buf.is_empty() {
                let at_end = last && s.ch != ' ';
                literal.extend(buf.split(' ').map(str::to_string));
                let context: Vec<&str> = field.iter().map(String::as_str).collect();
                p.set_lm_context(&context);
                let previous = field.last().map(String::as_str).unwrap_or(wm_engine::predict::SENTENCE_START);
                let corrects = !at_end || punct_corrects;
                let started = Instant::now();
                let dtaps: Vec<Tap> = buf
                    .encode_utf16()
                    .zip(taps.iter())
                    .map(|(c, at)| Tap { typed: c, at: *at })
                    .collect();
                let mut dec_top = String::new();
                let out = if let Some(fixed) = p.elide(&buf) {
                    fixed
                } else if corrects && use_decoder {
                    let mut src = vec![Source { walker: dec_list.dict(), prior: &dec_prior }];
                    if let Some(l2) = &list2 {
                        src.push(Source { walker: l2.dict(), prior: &prior2 });
                    }
                    if let Some(lx) = &lexicon {
                        src.push(Source { walker: lx.dict(), prior: &lex_prior });
                    }
                    let mut d = decoder::decode(&dtaps, &geometry, &src, &dec_context, &context, &params_here, &adjacent, 6);
                    if let Some(lm) = &p.lm {
                        let mut lm = lm.borrow_mut();
                        decoder::blend(&mut d, &buf, lm_w, |text| {
                            let mut ctx: Vec<&str> = context.clone();
                            let mut total = 0.0;
                            for w in text.split(' ') {
                                lm.set_context(p.lm_prefix, &ctx);
                                total += lm.logprob(w)? as f64;
                                ctx.push(w);
                            }
                            Some(total)
                        });
                    }
                    dec_top = match d.best() {
                        Some(r) if r.score > d.literal => r.text.clone(),
                        _ => buf.clone(),
                    };
                    match decoder::verdict_with(&d, &buf, apply_margin, known_margin, accent_margin).apply {
                        Some(text) => p.elide(&text).unwrap_or(text),
                        // `UNION=1`: the old rules get their turn when the
                        // decoder leaves the word alone, as on the phone.
                        None if union => p.decide_touch(&buf, 1.0, Some(previous), &taps).apply.unwrap_or_else(|| buf.clone()),
                        None => buf.clone(),
                    }
                } else if corrects {
                    let d = p.decide_touch(&buf, 1.0, Some(previous), &taps);
                    d.apply.unwrap_or_else(|| buf.clone())
                } else {
                    buf.clone()
                };
                t.decide_us += started.elapsed().as_micros();
                t.decides += 1;
                // What happened to this buffer, for the per-kind tally.
                // Real taps are not aligned to the words meant unless the
                // sentence split into as many buffers as it has words.
                let aligned = owners.iter().all(Option::is_some);
                let owners_u: Vec<usize> = owners.iter().flatten().copied().collect();
                let first = owners_u.first().copied().unwrap_or(0);
                let spans = owners_u.iter().collect::<HashSet<_>>().len();
                let intended: Vec<String> = {
                    let mut seen = Vec::new();
                    for &o in &owners_u {
                        if seen.last() != Some(&o) {
                            seen.push(o);
                        }
                    }
                    seen.iter().map(|&o| words[o].clone()).collect()
                };
                let want = intended.join(" ");
                let kind: &'static str = if !aligned {
                    "unaligned"
                } else if spans > 1 {
                    "joined"
                } else if words[first].contains('\'') && buf == words[first].replace('\'', "") {
                    "apostrophe"
                } else if buf == words[first] {
                    "clean"
                } else if buf == words[first].chars().map(key_of).collect::<String>() {
                    "accent"
                } else if kinds[first] != "clean" {
                    kinds[first]
                } else {
                    "slipped"
                };
                let got_it = out.to_lowercase() == want;
                if kind == "unaligned" {
                } else if kind == "clean" {
                    t.intact += 1;
                    if !got_it {
                        t.damaged += 1;
                    }
                } else {
                    let e = t.buffers.entry(kind).or_default();
                    e.0 += 1;
                    if got_it {
                        e.1 += 1;
                    }
                }
                if let (Some(f), 1, false) = (cases_out.as_mut(), spans, kind == "apostrophe") {
                    let pts: Vec<String> =
                        taps.iter().flatten().map(|(x, y)| format!("{x:.3},{y:.3}")).collect();
                    writeln!(f, "{label}\t{kind}\t{want}\t{buf}\t{}\t{dec_top}\t{out}", pts.join(";")).unwrap();
                }
                if !got_it && aligned {
                    if let Some(f) = errors_out.as_mut() {
                        writeln!(f, "{label}\t{kind}\t{want}\t{buf}\t{out}").unwrap();
                    }
                }
                let start = field.len();
                field.extend(out.split(' ').map(str::to_lowercase));
                commits.push((out.to_lowercase(), taps.iter().flatten().copied().collect()));
                // `RECHECK=1`: the buffer before this one, read again
                // with this one's words after it.
                if let (true, Some(prev)) = (recheck_on, prior_buffer.take()) {
                    let started = Instant::now();
                    let before: Vec<&str> = field[..prev.start].iter().map(String::as_str).collect();
                    let after: Vec<&str> = field[start..].iter().map(String::as_str).collect();
                    let committed = field[prev.start..start].join(" ");
                    let mut src = vec![Source { walker: dec_list.dict(), prior: &dec_prior }];
                    if let Some(l2) = &list2 {
                        src.push(Source { walker: l2.dict(), prior: &prior2 });
                    }
                    if let Some(lx) = &lexicon {
                        src.push(Source { walker: lx.dict(), prior: &lex_prior });
                    }
                    let found = {
                        let mut lm_guard = p.lm.as_ref().map(|l| l.borrow_mut());
                        let prefix = p.lm_prefix;
                        let mut seq = |text: &str| -> Option<f64> {
                            let lm = lm_guard.as_mut()?;
                            let mut ctx: Vec<&str> = before.clone();
                            let mut total = 0.0;
                            for w in text.split(' ') {
                                lm.set_context(prefix, &ctx);
                                total += lm.logprob(w)? as f64;
                                ctx.push(w);
                            }
                            Some(total)
                        };
                        let has_lm = p.lm.is_some();
                        decoder::recheck(
                            &prev.taps, &geometry, &src, &dec_context, &before, &committed, &after, &params_here, &adjacent,
                            if has_lm { Some((lm_w, &mut seq)) } else { None },
                            recheck_margin, recheck_known,
                        )
                    };
                    t.decide_us += started.elapsed().as_micros();
                    if let Some(text) = found.apply {
                        let text = p.elide(&text).unwrap_or(text).to_lowercase();
                        let was_right = committed == prev.want;
                        let now_right = text == prev.want;
                        t.rechecks += 1;
                        if now_right && !was_right {
                            t.recheck_fixed += 1;
                        } else if was_right && !now_right {
                            t.recheck_broke += 1;
                            if let Some(f) = errors_out.as_mut() {
                                writeln!(f, "{label}\trecheck-broke\t{}\t{committed}\t{text}", prev.want).unwrap();
                            }
                        }
                        let new_words: Vec<String> = text.split(' ').map(str::to_string).collect();
                        let shift = new_words.len() as isize - (start - prev.start) as isize;
                        field.splice(prev.start..start, new_words);
                        let start = (start as isize + shift) as usize;
                        prior_buffer = Some(Last { taps: dtaps.clone(), start, want: want.clone() });
                    } else {
                        prior_buffer = Some(Last { taps: dtaps.clone(), start, want: want.clone() });
                    }
                } else {
                    prior_buffer = Some(Last { taps: dtaps.clone(), start, want: want.clone() });
                }
                buf.clear();
                taps.clear();
                owners.clear();
            }
            if s.ch == ' ' {
                typed_view.push(' ');
            }
        }
        let reference: Vec<String> = words.to_vec();
        let lit_err = word_distance(&literal, &reference.iter().map(|w| w.replace('\'', "")).collect::<Vec<_>>());
        let err = word_distance(&field, &reference);
        t.sentences += 1;
        t.words += reference.len();
        t.literal_errors += lit_err;
        t.errors += err;
        if err == 0 {
            t.clean_sentences += 1;
        }
        if show_it {
            println!("  meant: {}\n  typed: {typed_view}\n  field: {}\n", reference.join(" "), field.join(" "));
        }
    };

    // `REPLAY=file`: recorded taps instead of the simulated hand, from
    // `tools/autocorrect/real_taps.py` (Vertanen & Kristensson's noisy typing
    // set and TSI), each sentence on its own keyboard's geometry. Which rows:
    // `REPLAY_SET=source:set,...` (vertanen:test). One line per study, and
    // the sentences that tapped no space at all apart from the rest.
    if let Ok(path) = std::env::var("REPLAY") {
        let boards = boards_of(&std::env::var("REPLAY_KEYBOARDS").unwrap_or_else(|_| path.replace("replay.tsv", "keyboards.tsv")));
        let sets: String = env("REPLAY_SET", "vertanen:test".to_string());
        let skip_studies: String = env("REPLAY_SKIP", String::new());
        let mut studies: Vec<(String, Tally)> = Vec::new();
        let mut spaced = Tally::default();
        let mut unspaced = Tally::default();
        let text = std::fs::read_to_string(&path).expect("the replay file");
        // `PERSONAL=1`: each person's own key offsets (and, with
        // `PERSONAL_SPREAD=1`, their own spread), learned as they type from
        // the words the keyboard committed, never from what was meant.
        // `HALF=1` scores only the second half of each person's sentences,
        // so both arms are compared where the learner has had its first half.
        let personal = env::<i32>("PERSONAL", 0) == 1;
        let personal_spread = env::<i32>("PERSONAL_SPREAD", 0) == 1;
        let half = env::<i32>("HALF", 0) == 1;
        let k_global: f32 = env("K_GLOBAL", wm_engine::decoder::TouchOffsets::K_GLOBAL);
        let k_key: f32 = env("K_KEY", wm_engine::decoder::TouchOffsets::K_KEY);
        let k_spread: f64 = env("K_SPREAD", wm_engine::decoder::TouchOffsets::K_SPREAD);
        let wanted = |f: &[&str]| -> bool {
            f.len() >= 7
                && sets.split(',').any(|w| w == format!("{}:{}", f[0], f[1]))
                && !skip_studies.split(',').any(|x| !x.is_empty() && f[4].starts_with(x))
        };
        let mut per_person: HashMap<String, usize> = HashMap::new();
        for line in text.lines() {
            let f: Vec<&str> = line.split('\t').collect();
            if wanted(&f) {
                *per_person.entry(f[2].to_string()).or_default() += 1;
            }
        }
        let mut seen_person: HashMap<String, usize> = HashMap::new();
        let mut learners: HashMap<String, wm_engine::decoder::TouchOffsets> = HashMap::new();
        let mut shown = 0;
        for line in text.lines() {
            let f: Vec<&str> = line.split('\t').collect();
            if !wanted(&f) {
                continue;
            }
            let study = f[4];
            let index = {
                let e = seen_person.entry(f[2].to_string()).or_default();
                *e += 1;
                *e - 1
            };
            let scored = !half || index >= per_person[f[2]] / 2;
            let Some((words, _)) = words_of(f[5]) else { continue };
            let Some(board) = boards.get(f[3]) else { continue };
            let strokes: Vec<Stroke> = f[6]
                .split(';')
                .filter_map(|pt| {
                    let (x, y) = pt.split_once(',')?;
                    let at = (x.parse().ok()?, y.parse().ok()?);
                    Some(Stroke { ch: board.hit(at), at })
                })
                .collect();
            // Aligned when the bar splits the taps into one buffer a word.
            let mut owner = Vec::with_capacity(strokes.len());
            let mut buffers = 0usize;
            let mut open = false;
            for s in &strokes {
                if s.ch == ' ' {
                    open = false;
                    owner.push(buffers);
                } else {
                    if !open {
                        buffers += 1;
                        open = true;
                    }
                    owner.push(buffers - 1);
                }
            }
            let owner: Vec<usize> = owner.into_iter().map(|o| o.min(words.len().saturating_sub(1))).collect();
            let aligned = buffers == words.len();
            let kinds = vec!["clean"; words.len()];
            let learner = learners.entry(format!("{}|{}", f[2], f[3])).or_default();
            let keys_now = if personal { learner.shifted(&board.keys, k_global, k_key) } else { board.keys.clone() };
            let sigma = (personal && personal_spread).then(|| {
                let (fx, fy) = learner.spread_factors(k_spread);
                (dec_params.sigma_x * fx, dec_params.sigma_y * fy)
            });
            let geometry = Geometry { keys: keys_now, space: Some(board.space) };
            let touch = Some(TouchModel::new(board.keys.clone(), TouchModel::SIGMA));
            let mut t = Tally::default();
            let mut commits = Vec::new();
            type_sentence(
                &words, &strokes, aligned.then_some(&owner[..]), &kinds, &geometry, touch, &mut t, shown < show,
                &format!("{study}|{}", f[3]), &mut commits, sigma,
            );
            shown += 1;
            if personal {
                for (word, taps) in &commits {
                    learner.learn(word, taps, &board.keys);
                }
            }
            if !scored {
                continue;
            }
            let no_space = !strokes.iter().any(|s| s.ch == ' ');
            let label = if no_space { format!("{study} (no spaces)") } else { study.to_string() };
            match studies.iter_mut().find(|(l, _)| *l == label) {
                Some((_, acc)) => acc.add(&t),
                None => studies.push((label, t.clone_counts())),
            }
            if no_space { unspaced.add(&t) } else { spaced.add(&t) }
        }
        studies.sort_by(|a, b| a.0.cmp(&b.0));
        for (label, t) in &studies {
            println!("{}", t.line(label));
        }
        println!("{}", spaced.line("ALL with spaces"));
        println!("{}", unspaced.line("ALL without spaces"));
        return;
    }

    for &scale in &hands {
        let mut rng = Rng(0x2545_f491_4f6c_dd1d ^ ((scale * 1000.0) as u64));
        let mut t = Tally::default();
        for (si, (words, _end)) in sentences.iter().enumerate() {
            let person = PEOPLE[rng.pick(PEOPLE.len())];
            let hand = Hand { sx: person.0 * scale, sy: person.1 * scale, dy: person.2 };
            // What the hand meant, word by word (a misspelling replaces the
            // word before any finger touches it), and the strokes it made.
            let mut strokes: Vec<Stroke> = Vec::new();
            // For each stroke, which reference word it belongs to.
            let mut owner: Vec<usize> = Vec::new();
            let mut kinds: Vec<&'static str> = vec!["clean"; words.len()];
            for (wi, word) in words.iter().enumerate() {
                if wi > 0 {
                    let s = tap_space(&mut rng, &keys, scale);
                    strokes.push(s);
                    owner.push(wi);
                }
                let mut meant: Vec<char> = word.chars().filter(|c| *c != '\'').map(key_of).collect();
                if let Some(options) = misspelled.get(word.as_str()) {
                    if rng.uniform() < p_misspell * 4.0 {
                        meant = options[rng.pick(options.len())].chars().collect();
                        kinds[wi] = "misspelled";
                    }
                }
                let mut i = 0;
                while i < meant.len() {
                    let roll = rng.uniform();
                    if roll < P_OMIT && meant.len() > 3 {
                        kinds[wi] = "omitted";
                        i += 1;
                        continue;
                    }
                    if roll < P_OMIT + P_SWAP && i + 1 < meant.len() {
                        kinds[wi] = "swapped";
                        meant.swap(i, i + 1);
                    }
                    let s = tap_letter(&mut rng, &keys, &hand, meant[i]);
                    strokes.push(s);
                    owner.push(wi);
                    if roll >= P_OMIT + P_SWAP && roll < P_OMIT + P_SWAP + P_DOUBLE {
                        kinds[wi] = "doubled";
                        let s = tap_letter(&mut rng, &keys, &hand, meant[i]);
                        strokes.push(s);
                        owner.push(wi);
                    }
                    i += 1;
                }
            }
            type_sentence(words, &strokes, Some(&owner), &kinds, &geometry, None, &mut t, si < show, &format!("{scale}"), &mut Vec::new(), None);
        }
        println!("{}", t.line(&format!("hand x{scale:.1}")));
    }
}
