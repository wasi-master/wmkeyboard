//! Definition, references, rename, outline and folding.

mod common;

use common::*;
use wm_python_tooling::lexer::lex_str;
use wm_python_tooling::navigate::*;
use wm_python_tooling::text::Span;
use wm_python_tooling::Document;

fn texts(source: &str, spans: &[Span]) -> Vec<String> {
    spans.iter().map(|s| utf16_slice(source, s.start, s.end)).collect()
}

const SOURCE: &str = "count = 0\n\n\ndef bump(by):\n    global count\n    count += by\n    step = by * 2\n    return step\n\n\ndef render():\n    return ui.label(str(count))\n";

#[test]
fn definition_and_references() {
    let doc = Document::new(SOURCE);
    let use_in_render = SOURCE.rfind("count").unwrap() as u32 + 2;
    let def = definition(&doc, use_in_render).unwrap();
    assert_eq!((def.start, def.end), (0, 5));
    let refs = references(&doc, use_in_render);
    assert_eq!(refs.len(), 4);
    assert!(texts(SOURCE, &refs).iter().all(|t| t == "count"));
    let step = SOURCE.rfind("step").unwrap() as u32;
    assert_eq!(definition(&doc, step).unwrap().start, SOURCE.find("step").unwrap() as u32);
    // The caret just after a name still finds it.
    assert!(definition(&doc, step + 4).is_some());
    assert!(definition(&doc, SOURCE.find("ui").unwrap() as u32).is_none());
    assert!(references(&doc, 1000).is_empty());
}

#[test]
fn rename_plans_and_problems() {
    let doc = Document::new(SOURCE);
    let step = SOURCE.find("step").unwrap() as u32;
    let plan = rename(&doc, step, "twice").unwrap();
    assert_eq!(plan.spans.len(), 2);
    assert_eq!(plan.problem, None);
    assert_eq!(rename(&doc, step, "").unwrap().problem, None);
    assert_eq!(rename(&doc, step, "2x").unwrap().problem, Some(RenameProblem::NotAName));
    assert_eq!(rename(&doc, step, "while").unwrap().problem, Some(RenameProblem::Keyword));
    assert_eq!(rename(&doc, step, "by").unwrap().problem, Some(RenameProblem::Taken));
    assert_eq!(rename(&doc, step, "count").unwrap().problem, Some(RenameProblem::Taken));
    assert_eq!(rename(&doc, step, "wm").unwrap().problem, Some(RenameProblem::Api));
    // The module's count is read through str(); renaming it to str would hide the builtin there.
    let count = 1;
    assert_eq!(rename(&doc, count, "str").unwrap().problem, Some(RenameProblem::Builtin));
    assert_eq!(rename(&doc, count, "total").unwrap().spans.len(), 4);
    let render = SOURCE.find("render").unwrap() as u32;
    assert_eq!(rename(&doc, render, "draw").unwrap().problem, Some(RenameProblem::Hook));
    let ui = SOURCE.find("ui.").unwrap() as u32;
    assert_eq!(rename(&doc, ui, "x").unwrap().problem, Some(RenameProblem::Api));
    let str_at = SOURCE.find("str(").unwrap() as u32;
    assert_eq!(rename(&doc, str_at, "x").unwrap().problem, Some(RenameProblem::Builtin));
    let doc = Document::new("import json\nx = json.dumps(1)\n");
    assert_eq!(rename(&doc, 8, "j").unwrap().problem, Some(RenameProblem::Api));
    let doc = Document::new("print(nothing)\n");
    assert!(rename(&doc, 8, "x").is_none());
    assert!(rename(&doc, 100, "x").is_none());
}

#[test]
fn outline_lists_functions_by_depth_and_top_level_variables() {
    let source = "A = 1\n\n\ndef outer():\n    f = lambda: 1\n\n    def inner():\n        pass\n    return inner\n";
    let doc = Document::new(source);
    let items: Vec<(String, &str, usize)> = outline(&doc).into_iter().map(|i| (i.name, i.kind, i.depth)).collect();
    assert_eq!(items, vec![("A".into(), "variable", 0), ("outer".into(), "function", 0), ("inner".into(), "function", 1)]);
}

#[test]
fn folds_from_tokens_match_the_tree_on_valid_files() {
    for name in FIXTURES {
        let source = fixture(name);
        let doc = Document::new(&source);
        assert_eq!(folds_from_tokens(&doc.tokens), folds_from_tree(&doc), "{name}");
        assert!(!folds_from_tokens(&doc.tokens).is_empty());
    }
}

#[test]
fn folds_cover_blocks_brackets_strings_and_comment_runs() {
    let source = "# one\n# two\ndef f():\n    x = [\n        1,\n    ]\n    s = '''\n    '''\n    return x\ny = 1\n";
    let doc = Document::new(source);
    let folds = texts(source, &folds_from_tokens(&doc.tokens));
    assert_eq!(folds[0], "# one\n# two");
    assert!(folds[1].starts_with("def f():") && folds[1].ends_with("return x"));
    assert!(folds.contains(&"[\n        1,\n    ]".to_string()));
    assert!(folds.contains(&"'''\n    '''".to_string()));
}

#[test]
fn folds_still_work_on_a_broken_file() {
    let source = "def f():\n    x = foo(1,\n    return x\n\nif y\n    z = 1\n\"\"\"\nnever closed\n";
    let doc = Document::new(source);
    assert!(!doc.parses());
    let folds = texts(source, &folds_from_tokens(&lex_str(source)));
    assert_eq!(folds[0], "def f():\n    x = foo(1,\n    return x");
    assert!(folds.iter().any(|f| f.starts_with("\"\"\"")));
}

#[test]
fn api_under_the_caret_or_around_it() {
    let at = |marked: &str| {
        let (source, caret) = caret(marked);
        api_at(&Document::new(&source), caret).map(|e| e.path)
    };
    assert_eq!(at("wm.storage.ge|t('a')"), Some("wm.storage.get"));
    assert_eq!(at("wm.stor|age.get('a')"), Some("wm.storage"));
    assert_eq!(at("x = ui.button(\"a\", |"), Some("ui.button"));
    assert_eq!(at("n = len(|"), Some("len"));
    assert_eq!(at("s = 'a'\ns.upp|er()"), Some("str.upper"));
    assert_eq!(at("xs.app|end(1)"), Some("list.append"));
    assert_eq!(at("import math as m\nm.sq|rt(2)"), Some("math.sqrt"));
    assert_eq!(at("from json import loads\nloa|ds('1')"), Some("json.loads"));
    assert_eq!(at("ui.label(f(|"), None);
    assert_eq!(at("nothing|"), None);
}
