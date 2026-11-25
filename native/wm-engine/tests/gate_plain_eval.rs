//! What the decoder's gate does with one-edit typos of listed words and with
//! held-out words typed right, with and without [`decoder::Gate::plain_slip`].
//!
//! `cargo test --release --test gate_plain_eval -- --ignored --nocapture`,
//! `SD=` for the taps' scatter in key widths (0.27 by default), `GC=` for the
//! slider (4). The held-out words are one in eight plain words past rank
//! 1,500 of en.txt, taken out of both lists; most are inflections the AOSP
//! list holds on the phone, so the damage counts here are pessimistic.

use std::collections::HashSet;
use wm_engine::decoder::{self, Geometry, NoContext, Params, Ranks, Source, Tap};
use wm_engine::predict::WordList;
use wm_engine::wordlist;

const ROW: f32 = 1.53;
const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];

fn geometry() -> Geometry {
    let mut keys = Vec::new();
    for (row, (letters, offset)) in ROWS.iter().enumerate() {
        for (col, ch) in letters.chars().enumerate() {
            keys.push((ch as u16, offset + col as f32, row as f32 * ROW));
        }
    }
    Geometry { keys, space: None }.with_estimated_space()
}

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / (1u64 << 53) as f64
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn normal(&mut self) -> f64 {
        let (a, b) = (self.unit().max(1e-12), self.unit());
        (-2.0 * a.ln()).sqrt() * (2.0 * std::f64::consts::PI * b).cos()
    }
}

fn center(g: &Geometry, c: char) -> (f32, f32) {
    let k = g.keys.iter().find(|k| k.0 == c as u16).unwrap();
    (k.1, k.2)
}

/// Taps aimed at each typed letter's centre, scattered `sd` key widths.
fn taps(g: &Geometry, typed: &str, sd: f64, rng: &mut Rng) -> Vec<Tap> {
    typed
        .chars()
        .map(|c| {
            let (x, y) = center(g, c);
            Tap { typed: c as u16, at: Some((x + (rng.normal() * sd) as f32, y + (rng.normal() * sd * ROW as f64) as f32)) }
        })
        .collect()
}

fn neighbours(g: &Geometry, c: char) -> Vec<char> {
    let (x, y) = center(g, c);
    g.keys
        .iter()
        .filter(|k| k.0 != c as u16 && ((k.1 - x).powi(2) + ((k.2 - y) / ROW).powi(2)).sqrt() < 1.2)
        .map(|k| char::from_u32(k.0 as u32).unwrap())
        .collect()
}

/// One edit: drop a letter, hit a neighbour, add a neighbour, or swap two.
fn typo(g: &Geometry, w: &str, rng: &mut Rng) -> String {
    let mut c: Vec<char> = w.chars().collect();
    let i = rng.below(c.len());
    match rng.below(4) {
        0 => {
            c.remove(i);
        }
        1 => {
            let n = neighbours(g, c[i]);
            c[i] = n[rng.below(n.len())];
        }
        2 => {
            let n = neighbours(g, c[i]);
            c.insert(i + 1, n[rng.below(n.len())]);
        }
        _ => {
            if i + 1 < c.len() {
                c.swap(i, i + 1)
            } else {
                c.swap(i - 1, i)
            }
        }
    }
    c.into_iter().collect()
}

fn plain(w: &str) -> bool {
    w.len() >= 3 && w.chars().all(|c| c.is_ascii_lowercase())
}

/// (the gate alone, the shipped verdict) for one buffer.
fn judge(d: &decoder::Decoded, typed: &str, conf: f64) -> (Option<String>, Option<String>) {
    let shipped = decoder::Gate::verdict(d, typed, conf).apply;
    let gate = decoder::Gate::features(d, typed).and_then(|(i, f)| {
        (decoder::Gate::probability(&f) >= wm_engine::confidence::apply_threshold(conf)).then(|| d.readings[i].text.clone())
    });
    (gate, shipped)
}

#[test]
#[ignore]
fn gate_plain_eval() {
    let read = |p: &str| wordlist::parse(&std::fs::read_to_string(p).expect(p));
    let main_all = read("../../app/dictionaries-src/en.txt");
    let aosp_all = read("../../app/dictionaries-src/en_aosp.txt");
    let g = geometry();
    let mut rng = Rng(0x9e3779b97f4a7c15);
    // Hold out one in eight plain words past rank 1500 from both lists.
    let mut held: Vec<String> = Vec::new();
    for (i, (w, _)) in main_all.iter().enumerate() {
        if i > 1500 && plain(w) && w.len() >= 4 && rng.below(8) == 0 {
            held.push(w.clone());
        }
    }
    let held_set: HashSet<&str> = held.iter().map(|s| s.as_str()).collect();
    let main: Vec<(String, i32)> = main_all.iter().filter(|(w, _)| !held_set.contains(w.as_str())).cloned().collect();
    let aosp: Vec<(String, i32)> = aosp_all.iter().filter(|(w, _)| !held_set.contains(w.as_str())).cloned().collect();
    let all: HashSet<&str> = main_all.iter().chain(aosp_all.iter()).map(|(w, _)| w.as_str()).collect();
    let (ml, al) = (WordList::of(&main), WordList::of(&aosp));
    let (r1, r2) = (Ranks::of(ml.dict()), Ranks::of(al.dict()));
    let p1 = |f: i32| r1.prior(f);
    let p2 = |f: i32| r2.prior(f);
    let sources = [Source { walker: ml.dict(), prior: &p1 }, Source { walker: al.dict(), prior: &p2 }];
    let sd: f64 = std::env::var("SD").ok().and_then(|s| s.parse().ok()).unwrap_or(0.27);
    let conf: f64 = std::env::var("GC").ok().and_then(|s| s.parse().ok()).unwrap_or(4.0);
    let decode = |tp: &[Tap]| decoder::decode(tp, &g, &sources, &NoContext, &[], &Params::default(), &|_, _| false, 6);

    // Typos of listed words, every third of the first 8,000.
    let (mut n, mut fixed, mut wrong) = (0, [0; 2], [0; 2]);
    for (i, (w, _)) in main.iter().enumerate().take(8000) {
        if i % 3 != 0 || !plain(w) || w.len() < 4 {
            continue;
        }
        let t = typo(&g, w, &mut rng);
        if all.contains(t.as_str()) {
            continue;
        }
        n += 1;
        let (gate, shipped) = judge(&decode(&taps(&g, &t, sd, &mut rng)), &t, conf);
        for (j, r) in [gate, shipped].iter().enumerate() {
            match r.as_deref() {
                Some(x) if x == w => fixed[j] += 1,
                Some(_) => wrong[j] += 1,
                None => {}
            }
        }
    }
    println!("typos n={n} sd={sd} conf={conf}: gate fixed {} wrong {}; shipped fixed {} wrong {}", fixed[0], wrong[0], fixed[1], wrong[1]);

    // Held-out words typed right.
    let mut changed = [0; 2];
    for w in &held {
        let (gate, shipped) = judge(&decode(&taps(&g, w, sd, &mut rng)), w, conf);
        changed[0] += usize::from(gate.is_some());
        changed[1] += usize::from(shipped.is_some());
    }
    println!("held-out n={}: gate changed {}; shipped changed {}", held.len(), changed[0], changed[1]);
}
