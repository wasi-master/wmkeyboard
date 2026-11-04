//! The typing harness: real sentences typed by a scattered finger, through
//! the Rust `Predictor`, with and without the neural context model and the
//! learned gate.
//!
//! ```text
//! cargo run --release --example typing_eval -- app/dictionaries-src/en.txt heldout.tsv [model.wmlm]
//! ```
//!
//! `heldout.tsv` is `tools/wmlm/prep.py`'s held-out split (`register field
//! lang words...` per line), sentences the model never trained on. For every
//! word of every sentence (letters only, two or more), with the words before
//! it as context:
//!
//! - the empty buffer: whether the word is among the strip's next words;
//! - the word typed by a finger that scatters around each key (three
//!   profiles, careful to sloppy): whether the strip's first word is the one
//!   meant, whether autocorrect fires and gets it right, and whether a word
//!   held out of the list and typed right is rewritten;
//! - keystroke savings: how many letters of the word could have been left
//!   untyped, the strip's first word being taken as soon as it is the word.
//!
//! Environment: `CASES` words per profile (3000), `SCATTER` (careful, fast,
//! sloppy or all), `MODES` as a comma list of `base`, `lm`, `conf`, `lm+conf`
//! (base and lm by default), `LMW`/`LMCAP` the model's rerank weight and
//! cap, `LMBLEND`/`LMBCAP`/`LMBSLOPE` its score-space blend, cap and tap-spread slope, `PREFIX=neutral` to hide the register and field from the model,
//! `DUMP=rows.csv` to write the gate's feature rows for
//! `tools/wmlm/train_confidence.py`, `SAVINGS=0` to skip the savings pass,
//! `SKIP=n` to start at the n-th sentence (so a run measures sentences a fit
//! never read), `CASES_OUT=cases.tsv` to write every mistyped case (intended,
//! typed, the taps, the scatter, the strip's first word) for the tap encoder
//! harness in `tools/swipe-ctc/taps_eval.py`.

use std::collections::HashSet;
use std::io::Write;
use std::sync::Arc;
use std::time::Instant;

use wm_engine::confidence::{Confidence, FEATURES};
use wm_engine::fuzzy::TouchModel;
use wm_engine::lm::{Lm, Prefix, Session};
use wm_engine::predict::{Predictor, SeedBigrams, WordList};
use wm_engine::wmng::{self, NgramPackBuilder, Pack};
use wm_engine::wordlist;

struct Sentence {
    prefix: Prefix,
    words: Vec<String>,
}

fn qwertz_keys() -> Vec<(u16, f32, f32)> {
    let mut keys = Vec::new();
    for (row, (letters, offset)) in [("qwertzuiopü", 0.0f32), ("asdfghjklöä", 0.25), ("yxcvbnm", 0.75)].iter().enumerate() {
        for (col, ch) in letters.chars().enumerate() {
            keys.push((ch as u16, offset + col as f32, row as f32));
        }
    }
    // ß sits on the dash key, right of the top row.
    keys.push(('ß' as u16, 0.95, 0.0));
    keys
}

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
}

fn env<T: std::str::FromStr>(name: &str, default: T) -> T {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

#[derive(Default, Clone)]
struct Tally {
    n: usize,
    top1: usize,
    top3: usize,
    fired: usize,
    right: usize,
    new_words: usize,
    new_rewritten: usize,
    next_n: usize,
    next_top1: usize,
    next_top3: usize,
    savings_words: usize,
    savings_chars: usize,
    savings_saved: usize,
    suggest_us: u128,
    decide_us: u128,
    context_us: u128,
}

impl Tally {
    fn line(&self, label: &str) -> String {
        let n = self.n.max(1) as f64;
        format!(
            "{label:>8}: strip top1 {:.4} top3 {:.4} | autocorrect precision {:.4} recall {:.4} fires {:.4} | new words rewritten {:.4} ({}) | next word top1 {:.4} top3 {:.4} | keystrokes saved {:.4} | {:.0}/{:.0}/{:.0} us suggest/decide/context",
            self.top1 as f64 / n,
            self.top3 as f64 / n,
            if self.fired == 0 { 1.0 } else { self.right as f64 / self.fired as f64 },
            self.right as f64 / n,
            self.fired as f64 / n,
            self.new_rewritten as f64 / self.new_words.max(1) as f64,
            self.new_words,
            self.next_top1 as f64 / self.next_n.max(1) as f64,
            self.next_top3 as f64 / self.next_n.max(1) as f64,
            self.savings_saved as f64 / self.savings_chars.max(1) as f64,
            self.suggest_us as f64 / n,
            self.decide_us as f64 / n,
            self.context_us as f64 / self.next_n.max(1) as f64,
        )
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: typing_eval <en.txt> <heldout.tsv> [model.wmlm]");
        std::process::exit(2);
    }
    let entries = wordlist::parse(&std::fs::read_to_string(&args[1]).expect("the word list"));
    let mut by_count = entries.clone();
    by_count.sort_by(|a, b| b.1.cmp(&a.1));
    let held: HashSet<String> = by_count
        .iter()
        .enumerate()
        .filter(|(rank, (w, _))| (3_000..60_000).contains(rank) && rank % 7 == 0 && w.chars().count() >= 4 && w.chars().all(|c| "abcdefghijklmnopqrstuvwxyzäöüß".contains(c)))
        .map(|(_, (w, _))| w.clone())
        .collect();
    let listed: HashSet<String> = entries.iter().map(|(w, _)| w.clone()).collect();
    let kept: Vec<(String, i32)> = entries.iter().filter(|(w, _)| !held.contains(w)).cloned().collect();

    let sentences: Vec<Sentence> = std::fs::read_to_string(&args[2])
        .expect("the held-out sentences")
        .lines()
        .filter_map(|line| {
            let mut parts = line.splitn(4, '\t');
            let register: u8 = parts.next()?.parse().ok()?;
            let field: u8 = parts.next()?.parse().ok()?;
            let lang: u8 = parts.next()?.parse().ok()?;
            let words: Vec<String> = parts.next()?.split_whitespace().map(str::to_string).collect();
            (words.len() >= 2).then_some(Sentence { prefix: Prefix { register, field, lang }, words })
        })
        .collect();
    // The corpus pack and the bundled seed pairs, so the base has the
    // context the phone's engine has: `PACK=dir` holds en_bigrams.txt and
    // en_trigrams.txt (gunzipped from the data repo), `SEEDS=file` the
    // asset (the app's by default).
    let pack_bytes: Option<Vec<u8>> = std::env::var("PACK").ok().map(|dir| {
        let mut b = NgramPackBuilder::new();
        let read = |name: &str, parts: usize, cap: usize, f: &mut dyn FnMut(&[&str], i32)| {
            let text = std::fs::read_to_string(format!("{dir}/{name}")).expect(name);
            let mut taken = 0;
            for line in text.lines() {
                if taken >= cap {
                    break;
                }
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue;
                }
                let Some(cut) = line.rfind(' ') else { continue };
                let Ok(count) = line[cut + 1..].parse::<i32>() else { continue };
                let words: Vec<&str> = line[..cut].split_whitespace().collect();
                if words.len() != parts {
                    continue;
                }
                f(&words, count);
                taken += 1;
            }
        };
        read("en_bigrams.txt", 2, 150_000, &mut |w, c| b.add_bigram(w[0], w[1], c));
        read("en_trigrams.txt", 3, 75_000, &mut |w, c| b.add_trigram(w[0], w[1], w[2], c));
        wmng::write(&b.build())
    });
    let seeds_path = std::env::var("SEEDS").unwrap_or_else(|_| "../../app/src/main/assets/dictionaries/de_bigrams.txt".into());
    let seeds_text = std::fs::read_to_string(&seeds_path).unwrap_or_default();
    let lm: Option<Arc<Lm>> = args.get(3).map(|p| Arc::new(Lm::open(std::fs::read(p).expect("the model file")).expect("a .wmlm the reader knows")));
    if let Some(lm) = &lm {
        eprintln!("model {} d{} L{} vocab {}", lm.name, lm.d, lm.layers, lm.vocab_size());
    }
    let cases: usize = env("CASES", 3000);
    let scatter = std::env::var("SCATTER").unwrap_or_else(|_| "all".into());
    let modes: Vec<String> = std::env::var("MODES").unwrap_or_else(|_| if lm.is_some() { "base,lm".into() } else { "base".into() }).split(',').map(str::to_string).collect();
    let lm_weight: f64 = env("LMW", wm_engine::predict::LM_WEIGHT);
    let lm_cap: f64 = env("LMCAP", wm_engine::predict::LM_CAP);
    let lm_blend: f64 = env("LMBLEND", wm_engine::predict::LM_BLEND);
    let lm_blend_cap: f64 = env("LMBCAP", wm_engine::predict::LM_BLEND_CAP);
    let lm_blend_slope: f64 = env("LMBSLOPE", wm_engine::predict::LM_BLEND_SLOPE);
    let neutral = std::env::var("PREFIX").is_ok_and(|v| v == "neutral");
    let savings_pass: i32 = env("SAVINGS", 1);
    let skip: usize = env("SKIP", 0);
    let dump = std::env::var("DUMP").ok();
    let mut cases_out = std::env::var("CASES_OUT").ok().map(|p| std::fs::File::create(p).expect("the cases file"));
    let mut dump_file = dump.as_ref().map(|p| std::fs::File::create(p).expect("the dump file"));
    if let Some(f) = dump_file.as_mut() {
        let names: Vec<String> = (0..FEATURES).map(|i| format!("f{i}")).collect();
        writeln!(f, "mode,scatter,kind,label,{}", names.join(",")).unwrap();
    }
    // Optional per-case dump. `item_id` is assigned in input order at the
    // moment `t.n` increments, so it is stable across modes (the same
    // sentence-yielding-the-case aligns across models). Metrics the existing
    // loop does not compute for a case are written as -1.
    let items_path = std::env::var("ITEMS").ok();
    let collect_items = items_path.is_some();
    // (mode, profile_idx, item_id, nextword_top1, nextword_top3, strip_top1)
    let mut items: Vec<(String, usize, usize, i32, i32, i32)> = Vec::new();
    let keys = qwertz_keys();
    let centre = |ch: char| keys.iter().find(|k| k.0 == ch as u16).map(|k| (k.1, k.2));
    let nearest = |p: (f32, f32)| {
        keys.iter()
            .min_by(|a, b| {
                let da = (a.1 - p.0).powi(2) + (a.2 - p.1).powi(2);
                let db = (b.1 - p.0).powi(2) + (b.2 - p.1).powi(2);
                da.total_cmp(&db)
            })
            .map(|k| k.0 as u8 as char)
            .unwrap()
    };
    let profiles: Vec<(&str, f64, f64)> = [("careful", 0.25, 0.3), ("fast", 0.35, 0.45), ("sloppy", 0.45, 0.55)]
        .into_iter()
        .filter(|(name, _, _)| scatter == "all" || scatter == *name)
        .collect();

    for mode in &modes {
        let use_lm = mode.contains("lm");
        let use_conf = mode.contains("conf");
        if use_lm && lm.is_none() {
            eprintln!("mode {mode} needs a model");
            continue;
        }
        let build = |dict: &[(String, i32)]| {
            let mut p = Predictor::new();
            p.dictionary = Some(WordList::of(dict));
            p.english_sources = false;
            p.primary_language = "de".into();
            p.context_rerank = true;
            p.seeds = SeedBigrams::parse(&seeds_text);
            p.ngrams = pack_bytes.as_ref().and_then(|b| Pack::open(b.clone()));
            p.touch = Some(TouchModel::new(keys.clone(), TouchModel::SIGMA));
            p.lm_weight = lm_weight;
            p.lm_cap = lm_cap;
            p.lm_blend = lm_blend;
            p.lm_blend_cap = lm_blend_cap;
            p.lm_blend_slope = lm_blend_slope;
            if use_lm {
                p.lm = lm.as_ref().map(|m| std::cell::RefCell::new(Session::new(m.clone())));
            }
            if use_conf {
                p.confidence = Some(Confidence::shipped());
            }
            p
        };
        let mut full = build(&entries);
        let mut holed = build(&kept);
        for (profile_idx, (name, sx, sy)) in profiles.iter().enumerate() {
            let mut rng = Rng(0x9e37_79b9_7f4a_7c15 ^ ((*sx * 1000.0) as u64));
            let mut t = Tally::default();
            let mut sentence_index = skip;
            let want_new = cases / 5;
            'outer: while (t.n < cases || t.new_words < want_new) && sentence_index < sentences.len() * 2 {
                let s = &sentences[sentence_index % sentences.len()];
                sentence_index += 1;
                let prefix = if neutral { Prefix { lang: s.prefix.lang, ..Prefix::default() } } else { s.prefix };
                full.lm_prefix = prefix;
                holed.lm_prefix = prefix;
                for i in 0..s.words.len() {
                    let word = &s.words[i];
                    let n_chars = word.chars().count();
                    if n_chars < 2 || !word.chars().all(|c| c.is_lowercase() && centre(c).is_some()) {
                        continue;
                    }
                    let context: Vec<&str> = s.words[..i].iter().map(String::as_str).collect();
                    // Nearest first, as the engine takes them.
                    let mut previous: Vec<&str> = context.iter().rev().take(3).copied().collect();
                    if previous.is_empty() {
                        previous.push(wm_engine::predict::SENTENCE_START);
                    }
                    let started = Instant::now();
                    full.set_lm_context(&context);
                    holed.set_lm_context(&context);
                    t.context_us += started.elapsed().as_micros();
                    // Per-case metrics captured *in input order* before any
                    // mode-specific logic, so `item_id` is stable across
                    // models. -1 means "this metric was not computed for the
                    // current iteration of this word in this mode". The
                    // `mut` (not the initial value) is what carries the type
                    // and the lifetime into the loops that overwrite them.
                    #[allow(unused_assignments)]
                    let (mut next1, mut next3, mut strip1) = (-1i32, -1i32, -1i32);
                    // Next word on an empty buffer (while cases are still wanted;
                    // the tail of the scan only looks for new-word rows).
                    if t.n < cases {
                        let next = full.suggest("", &previous, 3);
                        t.next_n += 1;
                        next1 = next.first().is_some_and(|w| w == word) as i32;
                        next3 = next.iter().any(|w| w == word) as i32;
                        if next1 == 1 { t.next_top1 += 1; }
                        if next3 == 1 { t.next_top3 += 1; }
                    }
                    if n_chars < 3 {
                        continue;
                    }
                    // Typed by the finger.
                    let mut taps = Vec::with_capacity(n_chars);
                    let mut typed = String::with_capacity(word.len());
                    for ch in word.chars() {
                        let (x, y) = centre(ch).unwrap();
                        let p = (x + (rng.gaussian() * sx) as f32, y + (rng.gaussian() * sy) as f32);
                        taps.push(Some(p));
                        typed.push(nearest(p));
                    }
                    if &typed == word {
                        if held.contains(word) {
                            t.new_words += 1;
                            let d = holed.decide_touch(&typed, 1.0, previous.first().copied(), &taps);
                            if d.apply.is_some() {
                                t.new_rewritten += 1;
                            }
                            if let (Some(f), Some((case, top))) = (dump_file.as_mut(), holed.last_case.borrow().as_ref()) {
                                let label = if top == word { 1 } else { 0 };
                                let feats: Vec<String> = case.features().iter().map(|v| format!("{v}")).collect();
                                writeln!(f, "{mode},{name},new,{label},{}", feats.join(",")).unwrap();
                            }
                        }
                        if savings_pass > 0 && t.n < cases && listed.contains(word) && t.n % 4 == 0 {
                            t.savings_words += 1;
                            t.savings_chars += n_chars;
                            for (p, (byte, _)) in word.char_indices().enumerate().skip(1) {
                                let strip = full.suggest_touch(&word[..byte], &previous, 3, &taps[..p]);
                                if strip.first().is_some_and(|w| w == word) {
                                    t.savings_saved += n_chars - p;
                                    break;
                                }
                            }
                        }
                        continue;
                    }
                    if !listed.contains(word) || t.n >= cases {
                        continue;
                    }
                    t.n += 1;
                    let started = Instant::now();
                    let strip = full.suggest_touch(&typed, &previous, 5, &taps);
                    t.suggest_us += started.elapsed().as_micros();
                    strip1 = strip.first().is_some_and(|w| w == word) as i32;
                    if strip1 == 1 { t.top1 += 1; }
                    if strip.iter().take(3).any(|w| w == word) {
                        t.top3 += 1;
                    }
                    if let Some(f) = cases_out.as_mut() {
                        let points: Vec<String> = taps.iter().map(|p| p.map_or(String::new(), |(x, y)| format!("{x:.4},{y:.4}"))).collect();
                        writeln!(f, "{name}\t{word}\t{typed}\t{}\t{}", points.join(";"), strip.first().cloned().unwrap_or_default()).unwrap();
                    }
                    let started = Instant::now();
                    let d = full.decide_touch(&typed, 1.0, previous.first().copied(), &taps);
                    t.decide_us += started.elapsed().as_micros();
                    if let Some(fix) = &d.apply {
                        t.fired += 1;
                        if fix == word {
                            t.right += 1;
                        }
                    }
                    if let (Some(f), Some((case, top))) = (dump_file.as_mut(), full.last_case.borrow().as_ref()) {
                        let label = if top == word { 1 } else { 0 };
                        let feats: Vec<String> = case.features().iter().map(|v| format!("{v}")).collect();
                        writeln!(f, "{mode},{name},typo,{label},{}", feats.join(",")).unwrap();
                    }
                    if collect_items {
                        // `profile_idx * cases + (t.n - 1)` is the canonical
                        // item id within this mode: the input case at the
                        // moment of `t.n += 1`, the same across models.
                        items.push((mode.clone(), profile_idx, profile_idx * cases + (t.n - 1), next1, next3, strip1));
                    }
                    if t.n >= cases && t.new_words >= want_new {
                        break 'outer;
                    }
                }
            }
            println!("{mode:>8} {}", t.line(name));
        }
    }
    if let Some(path) = items_path.as_deref() {
        // Pivot (mode, item_id, ...) -> wide TSV with one row per item_id.
        // Strip column order is the user's MODES order in env_modes; if a
        // mode did not record a metric for an item_id, write -1.
        items.sort_by_key(|r| (r.0.clone(), r.1, r.2));
        let modes_used: Vec<String> = {
            let mut seen = std::collections::HashSet::new();
            let mut out = Vec::new();
            for (m, _, _, _, _, _) in &items {
                if seen.insert(m.clone()) {
                    out.push(m.clone());
                }
            }
            out
        };
        let mut per_item: std::collections::BTreeMap<usize, Vec<(String, i32, i32, i32)>> = std::collections::BTreeMap::new();
        let mut by_mode: std::collections::HashMap<(String, usize), (i32, i32, i32)> = std::collections::HashMap::new();
        for (m, _pi, id, n1, n3, s1) in &items {
            by_mode.insert((m.clone(), *id), (*n1, *n3, *s1));
            per_item.entry(*id).or_insert_with(Vec::new).push((m.clone(), *n1, *n3, *s1));
        }
        let mut header = String::from("item_id");
        for m in &modes_used {
            header.push('\t');
            header.push_str(&format!("{m}_nextword_top1\t{m}_nextword_top3\t{m}_strip_top1"));
        }
        let mut f = match std::fs::File::create(path) {
            Ok(f) => f,
            Err(e) => {
                eprintln!("ITEMS={path}: cannot create dump file: {e}");
                std::process::exit(1);
            }
        };
        if let Err(e) = writeln!(f, "{header}") {
            eprintln!("ITEMS={path}: write header failed: {e}");
            std::process::exit(1);
        }
        for (id, _rows) in per_item.iter() {
            let mut line = format!("{id}");
            for m in &modes_used {
                let (n1, n3, s1) = by_mode.get(&(m.clone(), *id)).copied().unwrap_or((-1, -1, -1));
                line.push_str(&format!("\t{n1}\t{n3}\t{s1}"));
            }
            if let Err(e) = writeln!(f, "{line}") {
                eprintln!("ITEMS={path}: write row failed: {e}");
                std::process::exit(1);
            }
        }
        if let Err(e) = f.flush() {
            eprintln!("ITEMS={path}: flush failed: {e}");
            std::process::exit(1);
        }
    }
}
