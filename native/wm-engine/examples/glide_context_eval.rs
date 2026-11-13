//! What the words before a swipe do to its reading, on real swipes drawn for
//! real sentences: the context model's blend and the n-gram step after it,
//! as `SuggestionEngine.finishGlide` runs them on a single stroke.
//!
//! cargo run --release --example glide_context_eval -- <records.jsonl> <qwerty.json> <word list> <seed bigrams>
//!
//! `BIGRAMS` and `TRIGRAMS` name the corpus pack's text files (`a b count`,
//! `a b c count`) and `MODEL` a `.wmlm`; without them the keyboard has only
//! the bundled seed pairs, which is a fresh install. One measurement a line
//! on stdin, `name=value` pairs:
//!
//!     pack=1 blend=0.7 cap=3 mode=rank
//!     pack=1 blend=0.7 cap=3 mode=score tri=0.3 bi=0.18 unseen=0.5
//!
//! `mode=rank` is the reorder the keyboard has run since 2026-08 (`rerank`:
//! a candidate's place in the list plus its evidence, never its score),
//! `mode=score` adds the phrase lattice's lift to the decoder's score
//! (`GlidePhraseLattice.lift`), `mode=none` stops after the blend. `gate=g`
//! with `mode=rank` only lets the reorder run when the decoder's top two sit
//! within `g` nats.
//!
//! Prints top-1 and top-4 over the strokes that have a word before them, the
//! same on the held-out half of the sessions, and how many leaders the
//! n-gram step changed for the better and for the worse.

use std::io::BufRead;
use std::sync::Arc;

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, Point, Source, Tuning};
use wm_engine::lm::{Lm, Prefix, Session};
use wm_engine::rerank::{self, Candidate as Ranked, Context, Word};
use wm_engine::user_lexicon::{UserLexicon, UserTrie};
use wm_engine::wmng::{self, NgramPackBuilder, Pack};

struct Record {
    word: String,
    held: bool,
    before: Vec<String>,
    width: f32,
    height: f32,
    points: Vec<Point>,
}

/// One candidate of a stroke's pool with everything a context step reads.
struct Entry {
    word: String,
    score: f64,
    lp: f32,
    tri: i32,
    bi: i32,
    seed: i32,
    recent: bool,
}

struct Pool {
    entries: Vec<Entry>,
    /// The word before is one the pack has followers for.
    back_known: bool,
    /// The word before is in no list, so the one before it vouches (#195).
    prev_unknown: bool,
}

fn number(v: &serde_json::Value, key: &str) -> f32 {
    v[key].as_f64().unwrap_or(0.0) as f32
}

fn keymap(layout: &[(i32, f32, f32)], width: f32, height: f32) -> KeyMap {
    let kw = width / 10.0;
    let mut order: Vec<usize> = (0..layout.len()).collect();
    order.sort_by_key(|&i| layout[i].0);
    KeyMap::new(
        layout.iter().map(|k| k.1 * width / kw).collect(),
        layout.iter().map(|k| k.2 * height / kw).collect(),
        order.iter().map(|&i| layout[i].0).collect(),
        order.iter().map(|&i| i as i32).collect(),
    )
}

fn word_of(text: &str) -> Word {
    Word { key: text.to_string(), bytes: text.as_bytes().to_vec() }
}

fn counts(path: Option<String>, parts: usize) -> Vec<(Vec<String>, i32)> {
    let Some(path) = path else { return Vec::new() };
    std::fs::read_to_string(path)
        .expect("n-gram file")
        .lines()
        .filter_map(|line| {
            let p: Vec<&str> = line.split_whitespace().collect();
            if p.len() != parts + 1 || line.starts_with('#') {
                return None;
            }
            Some((p[..parts].iter().map(|w| w.to_lowercase()).collect(), p[parts].parse().ok()?))
        })
        .collect()
}

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    None,
    Rank,
    Score,
}

#[derive(Clone, Copy)]
struct Config {
    pack: bool,
    blend: f64,
    cap: f64,
    mode: Mode,
    tri: f64,
    bi: f64,
    unseen: f64,
    gate: f64,
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let layout_json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&args[2]).expect("layout")).expect("layout json");
    let layout: Vec<(i32, f32, f32)> = layout_json["keys"]
        .as_array()
        .expect("keys")
        .iter()
        .filter_map(|k| Some((k["letter"].as_str()?.chars().next()? as i32, number(k, "cx"), number(k, "cy"))))
        .collect();
    let text = std::fs::read_to_string(&args[3]).expect("word list");
    let mut trie = UserTrie::new();
    let mut known = std::collections::HashSet::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        known.insert(word.to_lowercase());
        trie.insert(&word, frequency);
    }
    let seeds: std::collections::HashMap<(String, String), i32> =
        counts(Some(args[4].clone()), 2).into_iter().map(|(w, c)| ((w[0].clone(), w[1].clone()), c)).collect();
    let mut builder = NgramPackBuilder::new();
    for (w, c) in counts(std::env::var("BIGRAMS").ok(), 2) {
        builder.add_bigram(&w[0], &w[1], c);
    }
    for (w, c) in counts(std::env::var("TRIGRAMS").ok(), 3) {
        builder.add_trigram(&w[0], &w[1], &w[2], c);
    }
    let pack: Option<Pack> = if builder.is_empty() { None } else { Pack::open(wmng::write(&builder.build())) };
    let lm = std::env::var("MODEL").ok().map(|p| Arc::new(Lm::open(std::fs::read(p).expect("model")).expect("a .wmlm")));
    let limit = std::env::var("LIMIT").ok().and_then(|v| v.parse().ok()).unwrap_or(usize::MAX);
    let threads = std::env::var("THREADS").ok().and_then(|v| v.parse().ok()).unwrap_or(8usize);

    let file = std::io::BufReader::new(std::fs::File::open(&args[1]).expect("records"));
    let records: Vec<Record> = file
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| {
            let v: serde_json::Value = serde_json::from_str(&line).ok()?;
            let word = v["word"].as_str()?.to_lowercase();
            if word.chars().count() < 2 || !word.chars().all(char::is_alphabetic) || !known.contains(&word) {
                return None;
            }
            let (width, height) = (number(&v, "canvas_width"), number(&v, "canvas_height"));
            let points = v["data"]
                .as_array()?
                .iter()
                .map(|p| Point { x: number(p, "x") * width, y: number(p, "y") * height, t: p["t"].as_i64().unwrap_or(0) })
                .collect();
            let session = v["session"].as_str().unwrap_or("");
            let index = v["word_idx"].as_i64().unwrap_or(0).max(0) as usize;
            let before = v["sentence"]
                .as_str()
                .unwrap_or("")
                .split_whitespace()
                .take(index)
                .map(|w| w.chars().filter(|c| c.is_alphabetic() || *c == '\'').collect::<String>().to_lowercase())
                .filter(|w| !w.is_empty())
                .collect();
            let held = session.bytes().fold(0u32, |h, b| h.wrapping_mul(31).wrapping_add(b as u32)) % 2 == 1;
            Some(Record { word, held, before, width, height, points })
        })
        .filter(|r: &Record| !r.before.is_empty())
        .take(limit)
        .collect();
    eprintln!("{} strokes with a word before them, pack {}, model {}", records.len(), pack.is_some(), lm.is_some());

    // Every stroke's pool, read once: the decode, the model's say on each
    // candidate and the counts each n-gram step looks up.
    let chunk = records.len().div_ceil(threads).max(1);
    let pools: Vec<Pool> = std::thread::scope(|scope| {
        let handles: Vec<_> = records
            .chunks(chunk)
            .map(|part| {
                let (trie, layout, pack, seeds, lm) = (&trie, &layout, &pack, &seeds, &lm);
                scope.spawn(move || {
                    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
                    let mut decoder = Decoder::new(Tuning::default());
                    let mut session = lm.as_ref().map(|m| Session::new(m.clone()));
                    part.iter()
                        .map(|r| {
                            let keys = keymap(layout, r.width, r.height);
                            let kw = r.width / 10.0;
                            let mut out = decoder.decode(&r.points, &keys, kw, &sources, 8, None, 0);
                            // The keyboard widens a close call's pool when a model can still change it (#1016).
                            if session.is_some() && out.len() >= 2 && out[0].score - out[1].score < 1.0 {
                                out = decoder.decode(&r.points, &keys, kw, &sources, 16, None, 0);
                            }
                            let back1 = &r.before[r.before.len() - 1];
                            let back2 = r.before.len().checked_sub(2).map(|i| &r.before[i]);
                            if let Some(s) = session.as_mut() {
                                let ctx: Vec<&str> = r.before.iter().map(String::as_str).collect();
                                s.set_context(Prefix::default(), &ctx);
                            }
                            let recent: std::collections::HashSet<&String> = r.before.iter().rev().take(12).collect();
                            let entries = out
                                .iter()
                                .map(|c| {
                                    let word = String::from_utf16_lossy(&c.word).to_lowercase();
                                    let lp = session.as_mut().and_then(|s| s.logprob(&word)).unwrap_or(f32::NAN);
                                    let (tri, bi) = match pack {
                                        None => (0, 0),
                                        Some(p) => (back2.map_or(0, |b2| p.trigram_count(b2, back1, &word)), p.bigram_count(back1, &word)),
                                    };
                                    let seed = seeds.get(&(back1.clone(), word.clone())).copied().unwrap_or(0);
                                    let recent = recent.contains(&word);
                                    Entry { word, score: c.score, lp, tri, bi, seed, recent }
                                })
                                .collect();
                            Pool {
                                entries,
                                back_known: pack.as_ref().is_some_and(|p| !p.next_words(back1, 1).is_empty()),
                                prev_unknown: trie.frequency_of(back1) <= 0,
                            }
                        })
                        .collect::<Vec<Pool>>()
                })
            })
            .collect();
        handles.into_iter().flat_map(|h| h.join().unwrap()).collect()
    });

    let lexicon = UserLexicon::new();
    let no_packs: Vec<&Pack> = Vec::new();
    let with_pack: Vec<&Pack> = pack.iter().collect();

    // The words of a pool in the order one configuration leaves them.
    let read = |r: &Record, pool: &Pool, c: &Config, ngrams: bool| -> Vec<usize> {
        let n = pool.entries.len();
        let mut score: Vec<f64> = pool.entries.iter().map(|e| e.score).collect();
        if c.blend > 0.0 && n > 1 {
            if let Some(reference) = pool.entries.iter().take(16).map(|e| e.lp).find(|lp| lp.is_finite()) {
                for (i, e) in pool.entries.iter().enumerate().take(16) {
                    if e.lp.is_finite() {
                        score[i] += (c.blend * (e.lp - reference) as f64).clamp(-c.cap, c.cap);
                    }
                }
            }
        }
        let mut order: Vec<usize> = (0..n).collect();
        order.sort_by(|&a, &b| score[b].total_cmp(&score[a]));
        if !ngrams || c.mode == Mode::None || n < 2 {
            return order;
        }
        let back1 = &r.before[r.before.len() - 1];
        let back2 = r.before.len().checked_sub(2).map(|i| &r.before[i]);
        let back3 = r.before.len().checked_sub(3).map(|i| &r.before[i]);
        match c.mode {
            Mode::Rank => {
                if c.gate > 0.0 && score[order[0]] - score[order[1]] >= c.gate {
                    return order;
                }
                let prev = word_of(back1);
                let prev2 = back2.map(|w| word_of(w));
                let prev3 = back3.map(|w| word_of(w));
                let skip = if pool.prev_unknown { prev2.as_ref() } else { None };
                let context = Context { prev: &prev, prev2: prev2.as_ref(), prev3: prev3.as_ref(), skip };
                let candidates: Vec<Ranked> = order
                    .iter()
                    .map(|&i| {
                        let e = &pool.entries[i];
                        Ranked { word: word_of(&e.word), seed: e.seed, recent: e.recent, lm: f32::NAN }
                    })
                    .collect();
                let packs = if c.pack { &with_pack } else { &no_packs };
                match rerank::rerank(&lexicon, packs, &context, &candidates) {
                    None => order,
                    Some(moved) => moved.into_iter().map(|at| order[at]).collect(),
                }
            }
            _ => {
                for (i, e) in pool.entries.iter().enumerate() {
                    let (tri, bi) = if c.pack { (e.tri, e.bi.max(e.seed)) } else { (0, e.seed) };
                    score[i] += if tri > 0 {
                        c.tri * (tri as f64).ln_1p()
                    } else if bi > 0 {
                        c.bi * (bi as f64).ln_1p()
                    } else if c.pack && pool.back_known {
                        -c.unseen
                    } else {
                        0.0
                    };
                }
                order.sort_by(|&a, &b| score[b].total_cmp(&score[a]));
                order
            }
        }
    };

    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let line = line.trim().to_string();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut c = Config { pack: false, blend: 0.0, cap: 3.0, mode: Mode::None, tri: 0.3, bi: 0.18, unseen: 0.5, gate: 0.0 };
        for part in line.split_whitespace() {
            let (name, value) = part.split_once('=').expect("name=value");
            match name {
                "pack" => c.pack = value == "1",
                "blend" => c.blend = value.parse().expect("blend"),
                "cap" => c.cap = value.parse().expect("cap"),
                "tri" => c.tri = value.parse().expect("tri"),
                "bi" => c.bi = value.parse().expect("bi"),
                "unseen" => c.unseen = value.parse().expect("unseen"),
                "gate" => c.gate = value.parse().expect("gate"),
                "mode" => {
                    c.mode = match value {
                        "none" => Mode::None,
                        "rank" => Mode::Rank,
                        "score" => Mode::Score,
                        _ => panic!("mode=none|rank|score"),
                    }
                }
                _ => panic!("no setting {name}"),
            }
        }
        let (mut n, mut top1, mut top4, mut held_n, mut held1, mut fixed, mut broken) = (0usize, 0usize, 0usize, 0usize, 0usize, 0usize, 0usize);
        // Leaders the n-gram step took from the right word, by how far the
        // right word led before it: under 1 nat, 1 to 2, 2 to 4, 4 and up.
        let mut broken_by_lead = [0usize; 4];
        for (r, pool) in records.iter().zip(&pools) {
            let order = read(r, pool, &c, true);
            let plain = read(r, pool, &c, false);
            let hit = order.first().is_some_and(|&i| pool.entries[i].word == r.word);
            let was = plain.first().is_some_and(|&i| pool.entries[i].word == r.word);
            n += 1;
            top1 += hit as usize;
            top4 += order.iter().take(4).any(|&i| pool.entries[i].word == r.word) as usize;
            if r.held {
                held_n += 1;
                held1 += hit as usize;
            }
            fixed += (hit && !was) as usize;
            if was && !hit {
                broken += 1;
                let lead = pool.entries[plain[0]].score - pool.entries[order[0]].score;
                broken_by_lead[if lead < 1.0 { 0 } else if lead < 2.0 { 1 } else if lead < 4.0 { 2 } else { 3 }] += 1;
            }
        }
        let f = |a: usize, b: usize| a as f64 / b.max(1) as f64;
        println!(
            "{line:<62} top1 {:.4} top4 {:.4} held-out {:.4} | n-gram step: fixed {fixed} broke {broken} (decoder lead <1: {}, 1-2: {}, 2-4: {}, 4+: {})",
            f(top1, n),
            f(top4, n),
            f(held1, held_n),
            broken_by_lead[0],
            broken_by_lead[1],
            broken_by_lead[2],
            broken_by_lead[3],
        );
    }
}
