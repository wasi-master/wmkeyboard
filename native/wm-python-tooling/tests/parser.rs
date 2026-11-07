//! The tolerant parser: real plugins, half-typed plugins, and hostile input.

mod common;

use common::*;
use wm_python_tooling::analysis::{SymbolKind, Target};
use wm_python_tooling::ast::*;
use wm_python_tooling::Document;

#[test]
fn the_demos_parse_cleanly() {
    for name in FIXTURES {
        let doc = Document::new(&fixture(name));
        assert!(doc.module.errors.is_empty(), "{name}: {:?}", doc.module.errors);
        assert!(doc.module.unsupported.is_empty(), "{name}: {:?}", doc.module.unsupported);
    }
}

#[test]
fn every_span_the_analysis_reports_holds_its_name() {
    for name in FIXTURES {
        let source = fixture(name);
        let doc = Document::new(&source);
        for s in &doc.analysis.symbols {
            for span in s.references() {
                assert_eq!(utf16_slice(&source, span.start, span.end), s.name, "{name}: {}", s.name);
            }
        }
        for f in &doc.analysis.functions {
            if let Some(n) = &f.name {
                assert_eq!(&utf16_slice(&source, f.name_span.start, f.name_span.end), n);
            }
            for p in &f.params {
                assert_eq!(utf16_slice(&source, p.span.start, p.span.end), p.name);
            }
        }
    }
}

#[test]
fn an_error_stays_on_its_own_line() {
    let doc = Document::new("x = = 1\ny = 2\ndef f(a):\n    return a\n");
    assert_eq!(doc.module.errors.len(), 1);
    assert!(doc.analysis.top_function("f").is_some());
    assert!(doc.analysis.module_symbol("y").is_some());
}

#[test]
fn half_typed_headers_still_own_their_bodies() {
    let doc = Document::new("def f(a, b\n    return a\n\nx = 1\n");
    let f = doc.analysis.top_function("f").expect("f is still a function");
    assert_eq!(f.params.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), vec!["a", "b"]);
    assert!(doc.analysis.module_symbol("x").is_some());
    let doc = Document::new("if x ==\n    y = 1\nz = 2\n");
    let StmtKind::If { body, .. } = &doc.module.body.iter().find(|s| matches!(s.kind, StmtKind::If { .. })).unwrap().kind else { panic!() };
    assert_eq!(body.len(), 1);
    // A line left open by a bracket ends where a statement keyword starts the next.
    let doc = Document::new("x = foo(\ndef g():\n    return 1\n");
    assert!(doc.analysis.top_function("g").is_some());
}

#[test]
fn every_prefix_of_a_plugin_parses_without_panicking_and_keeps_what_came_before() {
    let source = fixture("todo");
    let units: Vec<u16> = source.encode_utf16().collect();
    let mut cut = 0;
    while cut <= units.len() {
        let prefix = String::from_utf16_lossy(&units[..cut]);
        let doc = Document::new(&prefix);
        if prefix.contains("def load():\n") && prefix.contains("def save():") {
            assert!(doc.analysis.top_function("load").is_some(), "cut at {cut}");
        }
        cut += 97;
    }
}

#[test]
fn hostile_input_does_not_overflow_the_stack() {
    let inputs = [
        "(".repeat(20000),
        format!("x = {}a", "a + ".repeat(20000)),
        format!("x = {}1", "not ".repeat(5000)),
        format!("x = {}1", "- ".repeat(5000)),
        format!("x = a{}", ".b".repeat(5000)),
        format!("x = {}", "[".repeat(5000) + &"]".repeat(5000)),
        (0..300).map(|i| format!("{}if x:\n", " ".repeat(i))).collect::<String>(),
        format!("f'{}'", "{f'".repeat(2000)),
        format!("x = {}", "lambda: ".repeat(3000)),
        format!("x = a{}", " < a".repeat(5000)),
    ];
    for input in &inputs {
        let doc = Document::new(input);
        let _ = wm_python_tooling::diagnostics::diagnose(&doc, &Default::default());
        let _ = wm_python_tooling::format::format(input);
    }
}

#[test]
fn scopes_follow_python() {
    let source = "count = 0\nnames = []\n\n\ndef bump(by=count):\n    global count\n    count += by\n    total = [n for n in names if n]\n    f = lambda k: k + by\n    try:\n        pass\n    except ValueError as error:\n        print(error)\n    return total, f\n";
    let doc = Document::new(source);
    let a = &doc.analysis;
    let count = a.module_symbol("count").unwrap();
    // The global statement, the augmented assignment and the default all mean the module's count.
    assert_eq!(count.references().len(), 4, "{:?}", count.references());
    assert!(a.symbols.iter().filter(|s| s.name == "n").all(|s| s.kind == SymbolKind::ComprehensionVariable));
    assert!(a.module_symbol("n").is_none() && a.module_symbol("total").is_none());
    let error = a.symbols.iter().find(|s| s.name == "error").unwrap();
    assert_eq!((error.kind, error.reads.len()), (SymbolKind::ExceptVariable, 1));
    let k = a.symbols.iter().find(|s| s.name == "k").unwrap();
    assert_eq!(k.kind, SymbolKind::Parameter);
    let visible: Vec<&str> = a.visible_at(source.find("return").unwrap() as u32).iter().map(|s| s.name.as_str()).collect();
    for name in ["by", "total", "f", "error", "count", "names", "bump"] {
        assert!(visible.contains(&name), "{name} not visible: {visible:?}");
    }
    let print = a.occurrences.iter().find(|o| utf16_slice(source, o.span.start, o.span.end) == "print").unwrap();
    assert_eq!(print.target, Target::Builtin);
}

#[test]
fn names_inside_f_strings_are_reads() {
    let source = "name = 1\nx = f'hi {name!r:>{name}}'\n";
    let doc = Document::new(source);
    assert_eq!(doc.analysis.module_symbol("name").unwrap().reads.len(), 2);
    assert!(doc.module.errors.is_empty());
    let doc = Document::new("x = f'{}'\n");
    assert_eq!(doc.module.errors.len(), 1);
}
