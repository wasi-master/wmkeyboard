//! `Tuning::join_cost` (issue #966 §5.2): a compound the list lacks is read
//! as two listed words in a row, on the one stroke, the joined word scoring
//! on the rarer part less the charge. Infinite, the default, reads no
//! compounds and the decode is the plain one to the bit. `join_min_part`
//! and `join_part_penalty` guard it: every part at least that long, and
//! each part joined past charged on its own frequency.

use wm_engine::fuzzy::{RankScale, Tier};
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Candidate, Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
const KEY_W: f32 = 60.0;
const KEY_H: f32 = 84.0;

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

/// The word drawn centre to centre, a sample every eighth of a key.
fn swipe(word: &str) -> Vec<Point> {
    let chars: Vec<char> = word.chars().collect();
    let first = centre(chars[0]);
    let mut out = vec![Point { x: first.0, y: first.1, t: 0 }];
    for w in chars.windows(2) {
        let (from, to) = (centre(w[0]), centre(w[1]));
        let len = ((to.0 - from.0).powi(2) + (to.1 - from.1).powi(2)).sqrt();
        let steps = ((len / KEY_W) * 8.0).ceil().max(1.0) as usize;
        let t0 = out.last().map_or(0, |p| p.t);
        for s in 1..=steps {
            let f = s as f32 / steps as f32;
            out.push(Point { x: from.0 + (to.0 - from.0) * f, y: from.1 + (to.1 - from.1) * f, t: t0 + 4 * s as i64 });
        }
    }
    out
}

/// A list of German-shaped parts spelled on the Latin keys, no compound
/// among them: `haushalt`, `hausarzt` and `arbeitsplatz` are absent.
const WORDS: [(&str, i32); 8] = [
    ("haus", 5000),
    ("halt", 3000),
    ("arzt", 1200),
    ("arbeit", 4000),
    ("platz", 2500),
    ("hast", 6000),
    ("alt", 4500),
    ("hausaufgabe", 300),
];

fn list() -> UserTrie {
    let mut trie = UserTrie::new();
    for (word, frequency) in WORDS {
        trie.insert(word, frequency);
    }
    trie
}

fn frequency(word: &str) -> i32 {
    WORDS.iter().find(|(w, _)| *w == word).map(|(_, f)| *f).expect("in the list")
}

fn decode(decoder: &mut Decoder, trie: &UserTrie, path: &[Point]) -> Vec<Candidate> {
    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0, scale: RankScale::RAW }];
    decoder.decode(path, &keymap(), KEY_W, &sources, 4, None, 0)
}

fn word_of(c: &Candidate) -> String {
    String::from_utf16_lossy(&c.word)
}

fn joining(cost: f64) -> Decoder {
    Decoder::new(Tuning { join_cost: cost, ..Tuning::default() })
}

#[test]
fn off_by_default_and_a_compound_the_list_lacks_is_not_read() {
    assert!(Tuning::default().join_cost.is_infinite());
    let trie = list();
    let mut plain = Decoder::new(Tuning::default());
    for compound in ["haushalt", "hausarzt", "arbeitsplatz"] {
        let found = decode(&mut plain, &trie, &swipe(compound));
        assert!(found.iter().all(|c| WORDS.iter().any(|(w, _)| *w == word_of(c))), "{compound}: every reading is listed");
    }
}

#[test]
fn with_a_finite_charge_a_two_part_word_absent_from_the_list_is_read() {
    let trie = list();
    let mut joined = joining(2.0);
    for (compound, rarer) in [("haushalt", "halt"), ("hausarzt", "arzt")] {
        let found = decode(&mut joined, &trie, &swipe(compound));
        let top = found.first().expect("a reading");
        assert_eq!(word_of(top), compound, "{compound}: {:?}", found.iter().map(word_of).collect::<Vec<_>>());
        // Scored on the rarer part, the join charged once. `ln1p_count`
        // reads the trie's count to about 3e-5 nats, not `f64::ln`'s.
        let expected = (1.0 + frequency(rarer) as f64).ln() - Tuning::default().shape_weight * top.shape_cost - 2.0;
        assert!((top.score - expected).abs() < 1e-4, "{compound}: {} vs {expected}", top.score);
    }
}

#[test]
fn a_listed_word_still_reads_as_itself_with_joining_on() {
    let trie = list();
    let mut joined = joining(2.0);
    for word in ["haus", "arbeit", "hausaufgabe"] {
        let found = decode(&mut joined, &trie, &swipe(word));
        assert_eq!(found.first().map(word_of).as_deref(), Some(word));
    }
}

#[test]
fn an_infinite_charge_is_the_plain_decode_to_the_bit() {
    let trie = list();
    let mut plain = Decoder::new(Tuning::default());
    let mut explicit = joining(f64::INFINITY);
    for word in ["haus", "haushalt", "hausaufgabe", "arbeitsplatz"] {
        let path = swipe(word);
        assert_eq!(decode(&mut plain, &trie, &path), decode(&mut explicit, &trie, &path), "{word}");
    }
}

#[test]
fn a_four_letter_floor_keeps_a_three_letter_tail_from_joining() {
    let trie = list();
    let mut guarded = Decoder::new(Tuning { join_cost: 2.0, join_min_part: 4, ..Tuning::default() });
    let false_compound = decode(&mut guarded, &trie, &swipe("hastalt"));
    assert!(
        false_compound.iter().all(|c| word_of(c) != "hastalt"),
        "hastalt read: {:?}",
        false_compound.iter().map(word_of).collect::<Vec<_>>()
    );
    let compound = decode(&mut guarded, &trie, &swipe("haushalt"));
    assert_eq!(compound.first().map(word_of).as_deref(), Some("haushalt"));
}

#[test]
fn a_joined_part_pays_the_penalty_on_its_own_frequency() {
    let trie = list();
    let mut plain = joining(2.0);
    let mut penalised = Decoder::new(Tuning { join_cost: 2.0, join_part_penalty: 0.1, ..Tuning::default() });
    let path = swipe("haushalt");
    let a = decode(&mut plain, &trie, &path);
    let b = decode(&mut penalised, &trie, &path);
    let top_a = a.iter().find(|c| word_of(c) == "haushalt").expect("read without the penalty");
    let top_b = b.iter().find(|c| word_of(c) == "haushalt").expect("read with it");
    // `haus` is joined past and pays; `halt`, the last part, does not.
    let expected = 0.1 * (1.0 + frequency("haus") as f64).ln();
    assert!((top_a.score - top_b.score - expected).abs() < 1e-4, "{} vs {expected}", top_a.score - top_b.score);
}

#[test]
fn the_guard_at_its_defaults_is_the_unguarded_decode_to_the_bit() {
    let trie = list();
    let mut unguarded = joining(2.0);
    let mut explicit = Decoder::new(Tuning { join_cost: 2.0, join_min_part: 3, join_part_penalty: 0.0, ..Tuning::default() });
    for word in ["haushalt", "hausarzt", "hastalt", "arbeitsplatz"] {
        let path = swipe(word);
        assert_eq!(decode(&mut unguarded, &trie, &path), decode(&mut explicit, &trie, &path), "{word}");
    }
}
