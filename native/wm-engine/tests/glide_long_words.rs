//! Long words (issue #966 §5.3): the walk spells up to `MAX_WORD_LENGTH`
//! letters, 32 since the issue, and a stroke past `LONG_STROKE` is sampled
//! finer the longer it runs. A 26-letter compound could not be read at all
//! at the old limit of 24.

use wm_engine::fuzzy::{RankScale, Tier};
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::workspace::{
    samples_for, LETTER_ARC, LONG_SAMPLES, LONG_STROKE, SAMPLES_PER_LETTER, SAMPLE_POINTS, SHORT_SAMPLES,
};
use wm_engine::glide::{Candidate, Decoder, Point, Source, Tuning, MAX_WORD_LENGTH};
use wm_engine::user_lexicon::UserTrie;

const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
const KEY_W: f32 = 60.0;
const KEY_H: f32 = 84.0;

/// "Qualitätsmanagementsystem" as a QWERTY stroke spells it: 26 letters.
const TWENTY_SIX: &str = "qualitaetsmanagementsystem";
/// 32 letters, the new limit exactly.
const THIRTY_TWO: &str = "elektroenzephalographieaufnahmen";
/// 33 letters: one past the limit, and still unreadable.
const THIRTY_THREE: &str = "elektroenzephalographieaufnahmens";

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

/// The stroke's length in key widths, as the decoder measures it.
fn arc(path: &[Point]) -> f32 {
    path.windows(2).map(|w| ((w[1].x - w[0].x).powi(2) + (w[1].y - w[0].y).powi(2)).sqrt()).sum::<f32>() / KEY_W
}

/// The bundled English list as a trie, with the long words added.
fn english_with_long_words() -> UserTrie {
    let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/dictionaries-src/en.txt")).unwrap();
    let mut trie = UserTrie::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        trie.insert(&word, frequency);
    }
    for word in [TWENTY_SIX, THIRTY_TWO, THIRTY_THREE] {
        trie.insert(word, 50);
    }
    trie
}

fn decode(decoder: &mut Decoder, trie: &UserTrie, path: &[Point]) -> Vec<Candidate> {
    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0, scale: RankScale::RAW }];
    decoder.decode(path, &keymap(), KEY_W, &sources, 4, None, 0)
}

fn word_of(c: &Candidate) -> String {
    String::from_utf16_lossy(&c.word)
}

fn units(word: &str) -> Vec<u16> {
    word.encode_utf16().collect()
}

#[test]
fn the_sample_count_scales_with_the_arc_past_the_long_stroke_and_not_before() {
    assert_eq!(samples_for(0.5), SHORT_SAMPLES);
    assert_eq!(samples_for(LONG_STROKE - 0.01), SHORT_SAMPLES);
    assert_eq!(samples_for(LONG_STROKE), LONG_SAMPLES);
    assert_eq!(samples_for(LONG_STROKE + LETTER_ARC - 0.01), LONG_SAMPLES);
    assert_eq!(samples_for(LONG_STROKE + LETTER_ARC), LONG_SAMPLES + SAMPLES_PER_LETTER);
    assert_eq!(samples_for(LONG_STROKE + 4.0 * LETTER_ARC), LONG_SAMPLES + 4 * SAMPLES_PER_LETTER);
    assert_eq!(samples_for(1000.0), SAMPLE_POINTS);
    assert!(SAMPLE_POINTS > LONG_SAMPLES);
}

#[test]
fn a_twenty_six_letter_word_decodes() {
    assert_eq!(TWENTY_SIX.chars().count(), 26);
    assert!(MAX_WORD_LENGTH >= 26);
    let trie = english_with_long_words();
    let mut decoder = Decoder::new(Tuning::default());
    let path = swipe(TWENTY_SIX);
    let found = decode(&mut decoder, &trie, &path);
    assert_eq!(found.first().map(word_of).as_deref(), Some(TWENTY_SIX), "{:?}", found.iter().map(word_of).collect::<Vec<_>>());
    // The stroke is far past the long-stroke threshold and is sampled finer
    // than the old ceiling of 64, which `align` reports.
    let aligned = decoder.align(&units(TWENTY_SIX), &path, &keymap(), KEY_W).expect("alignable");
    assert_eq!(aligned.sample_count as usize, samples_for(arc(&path)));
    assert!(aligned.sample_count as usize > LONG_SAMPLES, "{}", aligned.sample_count);
    assert_eq!(aligned.keys.len(), 26);
}

#[test]
fn the_limit_is_thirty_two_letters() {
    assert_eq!(THIRTY_TWO.chars().count(), 32);
    assert_eq!(THIRTY_THREE.chars().count(), 33);
    assert_eq!(MAX_WORD_LENGTH, 32);
    let trie = english_with_long_words();
    let mut decoder = Decoder::new(Tuning::default());
    let found = decode(&mut decoder, &trie, &swipe(THIRTY_TWO));
    assert_eq!(found.first().map(word_of).as_deref(), Some(THIRTY_TWO), "{:?}", found.iter().map(word_of).collect::<Vec<_>>());
    // One letter past the limit is never spelled: the walk stops expanding
    // a prefix at `MAX_WORD_LENGTH` letters.
    let found = decode(&mut decoder, &trie, &swipe(THIRTY_THREE));
    assert!(found.iter().all(|c| word_of(c) != THIRTY_THREE), "{:?}", found.iter().map(word_of).collect::<Vec<_>>());
}

#[test]
fn short_strokes_keep_their_old_sampling() {
    // Every stroke under `LONG_STROKE` takes 48 samples as it always did,
    // and one just over it takes 64: what keeps the eval floors standing.
    let mut decoder = Decoder::new(Tuning::default());
    for (word, samples) in [("the", SHORT_SAMPLES), ("keyboard", SHORT_SAMPLES), ("understanding", SHORT_SAMPLES)] {
        let path = swipe(word);
        assert!(arc(&path) < LONG_STROKE, "{word}: {}", arc(&path));
        let aligned = decoder.align(&units(word), &path, &keymap(), KEY_W).expect("alignable");
        assert_eq!(aligned.sample_count as usize, samples, "{word}");
    }
    let path = swipe("internationalization");
    let a = arc(&path);
    assert!(a >= LONG_STROKE, "{a}");
    let aligned = decoder.align(&units("internationalization"), &path, &keymap(), KEY_W).expect("alignable");
    assert_eq!(aligned.sample_count as usize, samples_for(a));
    assert!(aligned.sample_count as usize >= LONG_SAMPLES);
}
