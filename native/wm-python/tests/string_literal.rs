//! Port of StringLiteralTest.kt (PyMini's tests/StringLiteralTests.swift); expected values unchanged.

mod common;
use common::*;

use wm_python::interp::check;

#[test]
fn triple_quoted_strings() {
    for quote in ["\"", "'"] {
        let delimiter = quote.repeat(3);
        let bodies = [
            "You are a text correction tool.\nThis is a multiline prompt.\nPlease return only the corrected text.\n".to_string(),
            "\n  indented\n\n# literal comment, brackets: ([{\n\tend".to_string(),
            format!("one {quote} and two {quote}{quote} quotes\nnext"),
            String::new(),
            "single line".to_string(),
            "Unicode: 🐍 café\n日本語".to_string(),
        ];
        for body in bodies {
            let source = format!("PROMPT = {delimiter}{body}{delimiter}\ndef transform(text):\n    return PROMPT\n");
            check(&source).unwrap();
            assert_eq!(body, output(&source));
        }
    }
}

#[test]
fn indentation_inside_triple_quotes_is_kept() {
    let source = "def transform(text):\n    prompt = \"\"\"first\nno indentation\n    last\"\"\"\n    return prompt + text\n";
    assert_eq!("first\nno indentation\n    last!", output_with(source, "!"));
}

#[test]
fn escapes_and_line_joins() {
    for quote in ["\"", "'"] {
        let delimiter = quote.repeat(3);
        let literal = format!("{delimiter}first\\\nsecond\\n\\t\\\\\\{quote}end{delimiter}");
        assert_eq!(format!("firstsecond\n\t\\{quote}end"), output(&format!("result = {literal}")));
    }
}

#[test]
fn multiline_f_strings() {
    for prefix in ["f", "F"] {
        let source = format!("def transform(text):\n    return {prefix}\"\"\"Hello {{text}}\n{{{{literal}}}}\"\"\"");
        assert_eq!("Hello world\n{literal}", output_with(&source, "world"));
    }
}

#[test]
fn unterminated_strings_report_the_opening_line() {
    for ending in ["", "\"", "\"\""] {
        let error = check(&format!("result = \"\"\"first\nlast{ending}")).unwrap_err();
        assert_eq!(Some(1), error.line);
        assert_eq!("unterminated string", error.message);
    }
    assert!(check("result = \"first\nlast\"").is_err());
}

#[test]
fn lines_after_a_multiline_string_stay_correct() {
    let error = check("result = \"\"\"first\nlast\"\"\"\n@").unwrap_err();
    assert_eq!(Some(3), error.line);
}

#[test]
fn newlines_are_normalised() {
    for newline in ["\r\n", "\r"] {
        assert_eq!("first\nlast", output(&format!("result = \"\"\"first{newline}last\"\"\"")));
    }
    assert_eq!("ordinary\nstring", output("result = 'ordinary\\nstring'"));
}
