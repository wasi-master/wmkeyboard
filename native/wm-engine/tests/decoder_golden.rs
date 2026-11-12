//! The whole-phrase reading on the shipped English lists, with the cases
//! users reported: what it must fix, and what it must leave alone.
//!
//! Taps sit on the centre of the key typed, except where a case says the
//! finger was low (`_` after a letter: the tap fell toward the space bar)
//! or wide (`<` / `>` after a letter: toward the key on that side). That is
//! the evidence a phone hands over; a centre tap on a wrong key is a slip
//! the reading has to argue past.

use wm_engine::decoder::{self, Geometry, NoContext, Params, Ranks, Source, Tap};
use wm_engine::predict::WordList;
use wm_engine::wordlist;

const ROW: f32 = 1.53;

fn geometry() -> Geometry {
    let mut keys = Vec::new();
    for (row, (letters, offset)) in [("qwertyuiop", 0.0f32), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)].iter().enumerate() {
        for (col, ch) in letters.chars().enumerate() {
            keys.push((ch as u16, offset + col as f32, row as f32 * ROW));
        }
    }
    Geometry { keys, space: None }.with_estimated_space()
}

fn taps(spec: &str, g: &Geometry) -> (String, Vec<Tap>) {
    let mut typed = String::new();
    let mut out: Vec<Tap> = Vec::new();
    for c in spec.chars() {
        match c {
            '_' | '<' | '>' => {
                let last = out.last_mut().expect("a mark after a letter");
                let (x, y) = last.at.unwrap();
                last.at = Some(match c {
                    '_' => (x, y + 0.6 * ROW),
                    '<' => (x - 0.45, y),
                    _ => (x + 0.45, y),
                });
            }
            _ => {
                let k = g.keys.iter().find(|k| k.0 == c as u16).expect("a letter key");
                typed.push(c);
                out.push(Tap { typed: c as u16, at: Some((k.1, k.2)) });
            }
        }
    }
    (typed, out)
}

fn lists() -> (WordList, WordList) {
    let read = |p: &str| WordList::of(&wordlist::parse(&std::fs::read_to_string(p).expect(p)));
    (read("../../app/dictionaries-src/en.txt"), read("../../app/dictionaries-src/en_aosp.txt"))
}

fn read(spec: &str, main: &WordList, aosp: &WordList, r1: &Ranks, r2: &Ranks) -> (String, Option<String>) {
    let g = geometry();
    let (typed, taps) = taps(spec, &g);
    let p1 = |f: i32| r1.prior(f);
    let p2 = |f: i32| r2.prior(f);
    let sources = [Source { walker: main.dict(), prior: &p1 }, Source { walker: aosp.dict(), prior: &p2 }];
    let d = decoder::decode(&taps, &g, &sources, &NoContext, &[], &Params::default(), &|_, _| false, 6);
    let v = decoder::verdict(&d, &typed, decoder::APPLY_MARGIN, decoder::KNOWN_MARGIN);
    (typed, v.apply)
}

#[test]
fn reported_cases() {
    let (main, aosp) = lists();
    let (r1, r2) = (Ranks::of(main.dict()), Ranks::of(aosp.dict()));
    let fixes = [
        // The email that started this: y for t, b for the space twice. With
        // both b taps dead centre on the b it takes the pairs before it
        // ("the same", "same person"), which this test does not load.
        ("y<heb_sameb_person", "the same person"),
        // #1105: letters the wrong way round.
        ("wnat", "want"),
        ("soemthing", "something"),
        ("somehting", "something"),
        // #1107: a letter for the space.
        ("ton_the", "to the"),
        ("dov_this", "do this"),
        // Wide taps, several to a word.
        ("wj<ar>", "what"),
        ("hotw>l", "hotel"),
        ("yi>u", "you"),
        // A letter left out, a letter doubled.
        ("abot", "about"),
        ("helllo", "hello"),
    ];
    let mut failed = Vec::new();
    for (spec, want) in fixes {
        let (typed, got) = read(spec, &main, &aosp, &r1, &r2);
        if got.as_deref() != Some(want) {
            failed.push(format!("{typed}: want {want}, got {got:?}"));
        }
    }
    let keeps = ["config", "inbox", "miui", "hello", "people", "zoom", "swamps", "catnip", "reliably"];
    for spec in keeps {
        let (typed, got) = read(spec, &main, &aosp, &r1, &r2);
        if let Some(g) = got {
            failed.push(format!("{typed}: must stay, got {g}"));
        }
    }
    assert!(failed.is_empty(), "{}", failed.join("\n"));
}
