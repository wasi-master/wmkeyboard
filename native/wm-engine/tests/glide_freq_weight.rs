//! `Tuning::freq_weight` (issue #966): the weight on `ln(1 + frequency)`,
//! which a language profile lowers where a list's useful words are rare.
//! At 1.0 a decode is the shipped one to the bit; below it the frequency
//! term shrinks by exactly that factor and the shape term stands.

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

/// The bundled English list as a trie, with each word's frequency.
fn english() -> (UserTrie, Vec<(String, i32)>) {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/dictionaries-src/en.txt")).unwrap();
    let words = wm_engine::wordlist::parse(&text);
    let mut trie = UserTrie::new();
    for (word, frequency) in &words {
        trie.insert(word, *frequency);
    }
    (trie, words)
}

fn decode(decoder: &mut Decoder, trie: &UserTrie, path: &[Point]) -> Vec<Candidate> {
    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0, scale: RankScale::RAW }];
    decoder.decode(path, &keymap(), KEY_W, &sources, 4, None, 0)
}

fn word_of(c: &Candidate) -> String {
    String::from_utf16_lossy(&c.word)
}

#[test]
fn a_weight_of_one_is_the_shipped_decode_to_the_bit() {
    let (trie, _) = english();
    let mut shipped = Decoder::new(Tuning::default());
    let mut explicit = Decoder::new(Tuning { freq_weight: 1.0, ..Tuning::default() });
    assert_eq!(Tuning::default().freq_weight, 1.0);
    for word in ["the", "keyboard", "people", "understanding"] {
        let path = swipe(word);
        let a = decode(&mut shipped, &trie, &path);
        let b = decode(&mut explicit, &trie, &path);
        assert_eq!(a, b, "{word}");
        assert_eq!(a.first().map(word_of).as_deref(), Some(word));
    }
}

#[test]
fn the_frequency_term_scales_by_the_weight_and_the_shape_term_does_not() {
    let (trie, words) = english();
    let frequency = |w: &str| words.iter().find(|(word, _)| word == w).map(|(_, f)| *f).expect("in the list");
    let mut full = Decoder::new(Tuning::default());
    let mut half = Decoder::new(Tuning { freq_weight: 0.5, ..Tuning::default() });
    for word in ["the", "keyboard", "people"] {
        let path = swipe(word);
        let a = decode(&mut full, &trie, &path);
        let b = decode(&mut half, &trie, &path);
        let top_a = a.iter().find(|c| word_of(c) == word).expect("read at 1.0");
        let top_b = b.iter().find(|c| word_of(c) == word).expect("read at 0.5");
        assert_eq!(top_a.shape_cost, top_b.shape_cost, "{word}: shape unchanged");
        let expected = 0.5 * (1.0 + frequency(word) as f64).ln();
        assert!((top_a.score - top_b.score - expected).abs() < 1e-6, "{word}: {} vs {expected}", top_a.score - top_b.score);
    }
}

#[test]
fn at_weight_zero_the_order_is_the_shape_order() {
    // With no frequency term and no shape channel (a learned-shape term of
    // its own) the score is the alignment's shape term alone, so the
    // candidates come back best-drawn first whatever the list says.
    let (trie, _) = english();
    let mut none = Decoder::new(Tuning { freq_weight: 0.0, shape_channel: 0.0, ..Tuning::default() });
    for word in ["god", "write", "the"] {
        let found = decode(&mut none, &trie, &swipe(word));
        assert!(found.len() > 1, "{word}");
        for pair in found.windows(2) {
            assert!(pair[0].shape_cost <= pair[1].shape_cost, "{word}: {} before {}", word_of(&pair[0]), word_of(&pair[1]));
        }
    }
}

#[test]
fn a_corpus_count_list_read_on_its_scale_decodes_as_the_bundled_list_does() {
    // The bundled list's numbers are `973.83 ln(count / 1000) + 1`: turned
    // back into corpus counts, the list a download carries. Read as stored,
    // its commonest short words outweigh a stroke ("pinned" read "one");
    // read on its own ranking scale it decodes as the bundled list does.
    let (bundled, words) = english();
    let mut corpus = UserTrie::new();
    let mut counts = Vec::new();
    for (word, f) in &words {
        let count = (1000.0 * ((*f as f64 - 1.0) / 973.83).exp()).round().max(1.0) as i32;
        counts.push(count);
        corpus.insert(word, count);
    }
    let scale = RankScale::for_counts(&counts, 10_000.0, 1.01);
    assert!(!scale.is_raw());
    let mut decoder = Decoder::new(Tuning::default());
    for word in ["pinned", "bigger", "keyboard", "people", "those"] {
        let path = swipe(word);
        let sources = [Source { walker: &corpus, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0, scale }];
        let scaled = decoder.decode(&path, &keymap(), KEY_W, &sources, 4, None, 0);
        let plain = decode(&mut decoder, &bundled, &path);
        assert_eq!(scaled.first().map(word_of), plain.first().map(word_of), "{word}");
        assert_eq!(scaled.first().map(word_of).as_deref(), Some(word));
    }
}
