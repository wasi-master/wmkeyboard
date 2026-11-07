//! The lexer gives every UTF-16 unit to exactly one token, whatever the text.

mod common;

use common::*;
use wm_python_tooling::lexer::*;

fn kinds(source: &str) -> Vec<(TokenKind, String)> {
    let t = lex_str(source);
    (0..t.len()).filter(|i| t.is_code(*i)).map(|i| (t.kind(i), t.text(i))).collect()
}

fn assert_covers(source: &str) {
    let t = lex_str(source);
    let len = source.encode_utf16().count() as u32;
    let mut at = 0;
    for i in 0..t.len() {
        assert_eq!(t.start(i), at, "gap or overlap before token {i} in {source:?}");
        assert!(t.end(i) > t.start(i), "empty token in {source:?}");
        at = t.end(i);
    }
    assert_eq!(at, len, "tokens stop short in {source:?}");
}

#[test]
fn every_unit_belongs_to_exactly_one_token() {
    let mut corpus: Vec<String> = FIXTURES.iter().map(|f| fixture(f)).collect();
    corpus.extend(
        [
            "", " ", "\n", "\r\n\r", "x = '😀'\n", "f'{a['b']}'", "f\"{x:>{w}}\"", "'''never", "'one\nline'", "\\", "a \\\nb",
            "$?!`", "নাম = 'কি'", "0x_ff 1__0 1e 1e+ .5 5. 3j 0b102", "r'\\'", "b'x' rb'y' Rb\"z\" u'v'", "\u{feff}x",
            "\u{d7ff}", "# comment\r", "((((", "))))", "f'{", "f'{x", "f'''{x\n}'''",
        ]
        .iter()
        .map(|s| s.to_string()),
    );
    let alphabet: Vec<char> = "ab_ 1.\n\r\t\"'{}()[]#\\:=+-*/%<>!@$fFrRbB😀ক\u{0301}".chars().collect();
    let mut rng = Lcg(42);
    for _ in 0..3000 {
        let len = rng.below(40);
        corpus.push((0..len).map(|_| alphabet[rng.below(alphabet.len())]).collect());
    }
    for source in &corpus {
        assert_covers(source);
    }
}

#[test]
fn offsets_count_utf16_units() {
    let t = lex_str("x = '😀'\ny");
    let s = (0..t.len()).find(|i| t.kind(*i) == TokenKind::String).unwrap();
    assert_eq!((t.start(s), t.end(s)), (4, 8));
    assert_eq!(t.start(t.len() - 1), 9);
}

#[test]
fn strings_and_their_flags() {
    let t = lex_str("a = 'open\nb = '''never");
    let strings: Vec<usize> = (0..t.len()).filter(|i| t.kind(*i) == TokenKind::String).collect();
    assert!(t.has(strings[0], UNTERMINATED) && !t.has(strings[0], TRIPLE));
    assert_eq!(t.text(strings[0]), "'open");
    assert!(t.has(strings[1], UNTERMINATED) && t.has(strings[1], TRIPLE));
    let t = lex_str("f'{d[\"k\"]}' rb'x' b'y' r'\\''");
    let strings: Vec<usize> = (0..t.len()).filter(|i| t.kind(*i) == TokenKind::String).collect();
    assert_eq!(strings.len(), 4);
    assert!(t.has(strings[0], FSTRING) && !t.has(strings[0], UNTERMINATED));
    assert!(t.has(strings[1], RAW) && t.has(strings[1], FOREIGN_PREFIX));
    assert!(t.has(strings[2], FOREIGN_PREFIX));
    assert_eq!(t.text(strings[3]), "r'\\''");
    assert_eq!(string_value(&t, strings[3]), Some("\\'".to_string()));
}

#[test]
fn fstring_fields_are_found_with_their_specs() {
    let source = "f'a {x!r} b {y:>{width}} {{lit}} {d[\"k\"]}'";
    let t = lex_str(source);
    let fields: Vec<String> = fstring_fields(&t, 0).iter().map(|s| utf16_slice(source, s.start, s.end)).collect();
    assert_eq!(fields, vec!["x", "y", "width", "d[\"k\"]"]);
}

#[test]
fn numbers_names_keywords_and_operators() {
    assert_eq!(
        kinds("if x2 == 0x1F and y**=1_000: pass"),
        vec![
            (TokenKind::Keyword, "if".into()),
            (TokenKind::Name, "x2".into()),
            (TokenKind::Operator, "==".into()),
            (TokenKind::Number, "0x1F".into()),
            (TokenKind::Keyword, "and".into()),
            (TokenKind::Name, "y".into()),
            (TokenKind::Operator, "**=".into()),
            (TokenKind::Number, "1_000".into()),
            (TokenKind::Operator, ":".into()),
            (TokenKind::Keyword, "pass".into()),
        ]
    );
    assert_eq!(kinds("নাম = 1")[0], (TokenKind::Name, "নাম".into()));
    assert_eq!(number_shape("1.5e-3"), (true, false));
    assert_eq!(number_shape("1__0"), (false, false));
    assert_eq!(number_shape("012"), (false, false));
    assert_eq!(number_shape("2j"), (true, true));
    assert_eq!(kinds("a ! b")[1].0, TokenKind::Unknown);
}

#[test]
fn match_and_case_are_keywords_only_where_they_open_a_statement() {
    let k = kinds("match x:\n    case 1:\n        pass\nmatch = 1\nmatch.x\n");
    assert_eq!(k[0].0, TokenKind::SoftKeyword);
    assert_eq!(k[3].0, TokenKind::SoftKeyword);
    assert!(k.iter().filter(|(_, s)| s == "match").skip(1).all(|(k, _)| *k == TokenKind::Name));
}

#[test]
fn line_breaks_inside_brackets_continue_and_a_statement_keyword_gives_them_up() {
    let t = lex_str("x = (1,\n2)\ny = f(\ndef g():\n");
    let breaks: Vec<u8> = (0..t.len()).filter(|i| t.kind(*i) == TokenKind::Newline).map(|i| t.flags[i]).collect();
    assert_eq!(breaks[0] & CONTINUATION, CONTINUATION);
    assert_eq!(breaks[1] & (CONTINUATION | RESET), 0);
    assert_eq!(breaks[2] & RESET, RESET);
    let t = lex_str("a = 1 + \\\n  2\n");
    let first_break = (0..t.len()).find(|i| t.kind(*i) == TokenKind::Newline).unwrap();
    assert!(t.has(first_break, CONTINUATION));
}

#[test]
fn index_at_finds_the_token_holding_an_offset() {
    let t = lex_str("ab cd");
    assert_eq!(t.index_at(0), Some(0));
    assert_eq!(t.index_at(1), Some(0));
    assert_eq!(t.index_at(2), Some(1));
    assert_eq!(t.index_at(5), Some(2));
    assert_eq!(t.index_at(6), None);
    assert_eq!(lex_str("").index_at(0), None);
}
