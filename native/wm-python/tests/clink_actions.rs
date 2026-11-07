//! Port of ClinkActionsTest.kt: real Clink keyboard actions (`.clinkext` JSON
//! whose `source` defines `transform(text)`). Expected outputs were checked
//! against PyMini 2.0.1 itself.

use wm_python::interp::{Runtime, Value};

fn transform(fixture: &str, input: &str) -> String {
    let path = format!("{}/tests/fixtures/clink/{fixture}.clinkext", env!("CARGO_MANIFEST_DIR"));
    let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let mut py = Runtime::default();
    py.load(json["source"].as_str().unwrap()).unwrap();
    py.call("transform", vec![Value::str(input)]).unwrap().as_str().unwrap().to_string()
}

#[test]
fn reverse_keeps_grapheme_clusters_whole() {
    assert_eq!("👍🏽 olléh", transform("reverse", "héllo 👍🏽"));
    // A decomposed é (e + U+0301) and a skin-toned emoji each stay one character.
    assert_eq!("👍🏽 olle\u{301}h", transform("reverse", "he\u{301}llo 👍🏽"));
}

#[test]
fn shrug_keeps_its_backslash() {
    assert_eq!("¯\\_(ツ)_/¯", transform("shrug", ""));
}

#[test]
fn snake_case() {
    assert_eq!("hello_world", transform("snake-case", "  Hello World "));
}

#[test]
fn title_case() {
    assert_eq!("Hello World", transform("title-case", "hello world"));
}

#[test]
fn uppercase_uses_full_case_mapping() {
    assert_eq!("STRASSE", transform("uppercase", "straße"));
}

#[test]
fn word_count() {
    assert_eq!("3 words", transform("word-count", "one two  three"));
}

#[test]
fn fix_period_spaces() {
    assert_eq!("word word end.", transform("fix-period-spaces", "word.word end."));
}

#[test]
fn fix_punctuation_spaces() {
    assert_eq!("a b c", transform("fix-punctuation-spaces", "a,b.c"));
}
