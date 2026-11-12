//! The marks a stroke can carry, and the guess at a word it has not
//! finished, at the shipped tuning (issue #1250).
//!
//! The real-swipe fit of 2026-10-22 cut `shape_weight` to a fifth. A pause
//! and a loop are charged in alignment units, so they shrank with it, and
//! the look-ahead charge is in nats, so it did not: a stop on the u could
//! no longer lift `four` over `for`, and a guess at the rest of a long word
//! almost never stood clear of the words the stroke already spelled. These
//! hold the three weights that were moved back to where each does its job.

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Candidate, Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
const KEY_W: f32 = 60.0;
const KEY_H: f32 = 84.0;
/// Milliseconds between touch samples, and samples per key width of travel.
const SAMPLE_MS: i64 = 8;
const PER_KEY: f32 = 6.0;

fn centre(c: char) -> (f32, f32) {
    for (row, (letters, offset)) in ROWS.iter().enumerate() {
        if let Some(i) = letters.find(c) {
            return ((offset + i as f32 + 0.5) * KEY_W, (row as f32 + 0.5) * KEY_H);
        }
    }
    unreachable!()
}

fn keymap() -> KeyMap {
    let mut letters: Vec<char> = ROWS.iter().flat_map(|(l, _)| l.chars()).collect();
    letters.sort();
    let xs = letters.iter().map(|&c| centre(c).0 / KEY_W).collect();
    let ys = letters.iter().map(|&c| centre(c).1 / KEY_W).collect();
    let cps = letters.iter().map(|&c| c as i32).collect();
    KeyMap::new(xs, ys, cps, (0..letters.len() as i32).collect())
}

/// What the finger does on one letter on its way through.
#[derive(Clone, Copy, PartialEq)]
enum Mark {
    /// Stops there for this many milliseconds.
    Hold(i64),
    /// Goes once around a circle drawn through the key.
    Circle,
}

/// `word` drawn centre to centre at an even pace, with `mark` on the letter
/// at `at`, cut at `keep` of its length.
fn swipe(word: &str, mark: Option<(usize, Mark)>, keep: f32) -> Vec<Point> {
    let chars: Vec<char> = word.chars().collect();
    let mut out: Vec<Point> = Vec::new();
    let mut t = 0i64;
    let mut push = |out: &mut Vec<Point>, x: f32, y: f32, t: &mut i64| {
        out.push(Point { x, y, t: *t });
        *t += SAMPLE_MS;
    };
    let first = centre(chars[0]);
    push(&mut out, first.0, first.1, &mut t);
    for (i, w) in chars.windows(2).enumerate() {
        let (from, to) = (centre(w[0]), centre(w[1]));
        if from != to {
            let len = ((to.0 - from.0).powi(2) + (to.1 - from.1).powi(2)).sqrt();
            let steps = ((len / KEY_W) * PER_KEY).ceil().max(1.0) as usize;
            for s in 1..=steps {
                let f = s as f32 / steps as f32;
                push(&mut out, from.0 + (to.0 - from.0) * f, from.1 + (to.1 - from.1) * f, &mut t);
            }
        }
        match mark {
            Some((at, Mark::Hold(ms))) if at == i + 1 && from != to => {
                t += ms;
                push(&mut out, to.0 + 0.5, to.1 + 0.5, &mut t);
            }
            Some((at, Mark::Circle)) if at == i + 1 && from != to => {
                let r = 0.3 * KEY_W;
                for s in 1..=16 {
                    let a = s as f32 / 16.0 * std::f32::consts::TAU;
                    push(&mut out, to.0 + r * a.sin(), to.1 - r + r * a.cos(), &mut t);
                }
            }
            _ => {}
        }
    }
    if keep < 1.0 {
        let mut arc = vec![0f32];
        for w in out.windows(2) {
            arc.push(arc[arc.len() - 1] + ((w[1].x - w[0].x).powi(2) + (w[1].y - w[0].y).powi(2)).sqrt());
        }
        let stop = arc[arc.len() - 1] * keep;
        out = out.into_iter().zip(arc).filter(|(_, s)| *s <= stop).map(|(p, _)| p).collect();
    }
    out
}

fn english() -> UserTrie {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/dictionaries-src/en.txt")).unwrap();
    let mut trie = UserTrie::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        trie.insert(&word, frequency);
    }
    trie
}

fn decode(tuning: Tuning, trie: &UserTrie, path: &[Point], look_ahead: usize) -> Vec<Candidate> {
    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
    Decoder::new(tuning).decode(path, &keymap(), KEY_W, &sources, 8, None, look_ahead)
}

fn word_of(c: &Candidate) -> String {
    String::from_utf16_lossy(&c.word)
}

/// How far `word` leads `rival` in nats; negative when it trails.
fn lead(found: &[Candidate], word: &str, rival: &str) -> f64 {
    let score = |w: &str| found.iter().find(|c| word_of(c) == w).map(|c| c.score).unwrap_or(f64::NEG_INFINITY);
    score(word) - score(rival)
}

#[test]
fn a_stop_on_a_letter_settles_two_words_drawn_alike() {
    let trie = english();
    // The u sits on the line from o to r, the r on the line from h to e, and
    // a doubled d is one visit: each pair is one stroke, and the commoner
    // word has it until a stop says otherwise.
    for (word, rival, letter) in [("four", "for", 2), ("three", "the", 2), ("odd", "of", 1)] {
        let plain = decode(Tuning::default(), &trie, &swipe(word, None, 1.0), 0);
        assert_eq!(word_of(&plain[0]), rival, "{word} unmarked");
        let held = decode(Tuning::default(), &trie, &swipe(word, Some((letter, Mark::Hold(150))), 1.0), 0);
        assert_eq!(word_of(&held[0]), word, "{word} with a stop on letter {letter}");
    }
}

#[test]
fn the_fitted_charge_left_a_stop_too_weak_to_settle_them() {
    // What 0.6.4 to 0.6.6 shipped: the stop is read, and `for` still leads.
    let trie = english();
    let fitted = Tuning { unclaimed_dwell: 0.9, ..Tuning::default() };
    let held = decode(fitted, &trie, &swipe("four", Some((2, Mark::Hold(150))), 1.0), 0);
    assert_eq!(word_of(&held[0]), "for");
}

#[test]
fn a_stop_is_worth_what_the_strength_says() {
    // The setting behind "Pause strength": at the top of its range a stop
    // leads by more than the context model can ever push back with (3 nats).
    let trie = english();
    let path = swipe("four", Some((2, Mark::Hold(150))), 1.0);
    let shipped = lead(&decode(Tuning::default(), &trie, &path, 0), "four", "for");
    let strong = lead(&decode(Tuning { unclaimed_dwell: 10.0, ..Tuning::default() }, &trie, &path, 0), "four", "for");
    assert!(shipped > 0.3, "shipped lead {shipped}");
    assert!(strong > 3.0, "strong lead {strong}");
}

#[test]
fn a_circle_doubles_a_letter_by_a_clear_margin() {
    let trie = english();
    for (word, rival, letter) in [("too", "to", 1), ("off", "of", 1), ("good", "god", 1)] {
        let plain = decode(Tuning::default(), &trie, &swipe(word, None, 1.0), 0);
        assert_eq!(word_of(&plain[0]), rival, "{word} unmarked");
        let looped = decode(Tuning::default(), &trie, &swipe(word, Some((letter, Mark::Circle)), 1.0), 0);
        assert_eq!(word_of(&looped[0]), word, "{word} looped");
        let by = lead(&looped, word, rival);
        assert!(by > 1.0, "{word} leads {rival} by {by}");
    }
}

#[test]
fn a_long_word_most_of_the_way_drawn_is_guessed() {
    // `GlideGuessGate.admit` at the "Eager" margin: a guess stands when it
    // beats the best word the stroke spells by a nat.
    let trie = english();
    for word in ["beautiful", "understand"] {
        let path = swipe(word, None, 0.7);
        let clears = |tuning: Tuning| -> bool {
            let found = decode(tuning, &trie, &path, 8);
            let best_read = found.iter().filter(|c| c.ahead == 0).map(|c| c.score).fold(f64::NEG_INFINITY, f64::max);
            found.iter().any(|c| c.ahead > 0 && word_of(c) == word && c.score - best_read >= 1.0)
        };
        assert!(clears(Tuning::default()), "{word} at the shipped charge");
        // The charge the fit left behind: the same guess, a nat or more short.
        assert!(!clears(Tuning { look_ahead_cost: 1.4, ..Tuning::default() }), "{word} at 1.4");
    }
}

#[test]
fn a_word_drawn_to_its_end_is_not_overtaken_by_a_longer_one() {
    let trie = english();
    for word in ["thing", "that", "exam", "safe"] {
        let found = decode(Tuning::default(), &trie, &swipe(word, None, 1.0), 8);
        assert_eq!(word_of(&found[0]), word);
        assert_eq!(found[0].ahead, 0, "{word} is read, not guessed");
    }
}
