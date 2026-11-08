//! No input makes any entry point panic, on a stack as small as a JNI thread's.

mod common;

use common::*;
use wm_python_tooling::api_json;

fn exercise(source: &str, rng: &mut Lcg) {
    let len = source.encode_utf16().count() as i64;
    let host = r#"{"storage":false,"reminders":true,"selection":false,"panel":true,"actions":["a"],"verdict":{"message":"x","kind":"SyntaxError","line":3}}"#;
    let _ = api_json::analyse(source, host);
    let _ = api_json::analyse(source, "{}");
    let formatted = api_json::format(source);
    assert_eq!(api_json::format(&formatted), formatted, "not idempotent on {source:?}");
    let _ = api_json::tokens(source);
    for _ in 0..4 {
        let caret = rng.below(len as usize + 2) as i64 - 1;
        let _ = api_json::complete(source, caret, true, host);
        let _ = api_json::complete(source, caret, false, "{}");
        let _ = api_json::definition(source, caret);
        let _ = api_json::references(source, caret);
        let _ = api_json::rename(source, caret, "renamed");
        let _ = api_json::rename(source, caret, "");
        let _ = api_json::api_at(source, caret);
        let _ = api_json::in_inert(source, caret);
    }
    let _ = api_json::opens_block(source);
}

fn mutate(base: &str, rng: &mut Lcg) -> String {
    let mut chars: Vec<char> = base.chars().collect();
    let pieces = ["(", ")", "[", "]", "{", "}", ":", "'", "\"", "\"\"\"", "f'{", "}", "\n", "\t", "    ", "#", "\\", "def ", "if ", "lambda", "=", ".", ",", "*", "**", "😀", "ক", "\r", "@", "global x\n"];
    for _ in 0..1 + rng.below(6) {
        if chars.is_empty() {
            break;
        }
        let at = rng.below(chars.len());
        match rng.below(3) {
            0 => {
                let end = (at + rng.below(30)).min(chars.len());
                chars.drain(at..end);
            }
            1 => {
                let piece = pieces[rng.below(pieces.len())];
                for (k, c) in piece.chars().enumerate() {
                    chars.insert(at + k, c);
                }
            }
            _ => chars.truncate(at),
        }
    }
    chars.into_iter().collect()
}

#[test]
fn no_input_panics_any_entry_point() {
    std::thread::Builder::new()
        .stack_size(1 << 20)
        .spawn(|| {
            let mut rng = Lcg(2024);
            let fixtures: Vec<String> = FIXTURES.iter().map(|f| fixture(f)).collect();
            for f in &fixtures {
                exercise(f, &mut rng);
            }
            for _ in 0..120 {
                let base = &fixtures[rng.below(fixtures.len())];
                let source = mutate(base, &mut rng);
                exercise(&source, &mut rng);
            }
            for source in ["", "\n", "(", "f'{", "'''", "def", "def f(", "if:", "@", "\\", "x = 1 if", "lambda", "import", "from . import", "class", "with", "match x:\n case", "try:\n", "except:\n", "*", "**", "x[", "x[:", "x.", "😀", "\u{feff}", "\r\r\r"] {
                exercise(source, &mut rng);
            }
            let deep = ["(".repeat(5000), format!("x = {}a", "a + ".repeat(5000)), format!("x = {}", "not ".repeat(3000)), (0..200).map(|i| format!("{}if x:\n", " ".repeat(i))).collect()];
            for source in &deep {
                let _ = api_json::analyse(source, "{}");
                let _ = api_json::format(source);
                let _ = api_json::complete(source, 10, true, "{}");
            }
        })
        .unwrap()
        .join()
        .unwrap();
}
