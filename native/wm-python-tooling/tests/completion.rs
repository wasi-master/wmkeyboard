//! Completion at a `|` caret marker.

mod common;

use common::*;
use wm_python_tooling::complete::{complete, Completions, ItemKind, MAX_ITEMS};
use wm_python_tooling::document::{Document, Host};

fn at(marked: &str, explicit: bool) -> Option<Completions> {
    at_with(marked, explicit, &Host::default())
}

fn at_with(marked: &str, explicit: bool, host: &Host) -> Option<Completions> {
    let (source, caret) = caret(marked);
    complete(&Document::new(&source), caret, explicit, host)
}

fn labels(marked: &str, explicit: bool) -> Vec<String> {
    at(marked, explicit).map(|c| c.items.into_iter().map(|i| i.label).collect()).unwrap_or_default()
}

fn has(marked: &str, explicit: bool, wanted: &[&str]) {
    let found = labels(marked, explicit);
    for w in wanted {
        assert!(found.iter().any(|l| l == w), "{marked:?}: no {w} in {found:?}");
    }
}

#[test]
fn members_of_the_host_api() {
    has("wm.|", false, &["log", "storage", "reminders", "ui", "timestamp", "plugin_id", "api_version"]);
    has("wm.storage.|", false, &["get", "set", "remove", "keys"]);
    has("wm.reminders.|", false, &["set", "cancel", "list"]);
    has("wm.ui.|", false, &["set_input"]);
    let c = at("x = ui.bu|", false).unwrap();
    let button = c.items.iter().find(|i| i.label == "button").unwrap();
    assert_eq!(button.insert, "button(\"\", \"\")");
    assert_eq!(button.caret, 8);
    assert_eq!(button.doc_path.as_deref(), Some("ui.button"));
    assert_eq!(utf16_slice("x = ui.bu", c.replace.start, c.replace.end), "bu");
    let storage = at("wm.st|", false).unwrap().items.into_iter().find(|i| i.label == "storage").unwrap();
    assert_eq!(storage.kind, ItemKind::Module);
}

#[test]
fn members_of_modules_follow_import_aliases() {
    has("import math\nmath.sq|", false, &["sqrt"]);
    has("import json as j\nj.|", false, &["dumps", "loads"]);
    has("import re\nre.|", false, &["search", "sub", "IGNORECASE"]);
    has("from time import |", false, &["time", "format", "parts"]);
    has("import |", false, &["math", "json", "re", "time", "random", "sys"]);
    assert!(labels("x.y.|", false).iter().all(|l| l != "sqrt"));
}

#[test]
fn methods_by_the_receivers_type_when_it_shows_and_a_union_when_not() {
    has("s = 'abc'\ns.up|", false, &["upper"]);
    let list = labels("xs = []\nxs.|", false);
    assert!(list.contains(&"append".to_string()) && !list.contains(&"upper".to_string()), "{list:?}");
    let dict = labels("d = {}\nd.|", false);
    assert!(dict.contains(&"items".to_string()) && !dict.contains(&"append".to_string()));
    has("parts = text.split(',')\nparts.|", false, &["append"]);
    has("'abc'.|", false, &["upper", "encode"]);
    has("bytes.|", false, &["fromhex"]);
    has("b = 'x'.encode()\nb.|", false, &["hex", "decode"]);
    let unknown = labels("def f(x):\n    x.|", false);
    for m in ["upper", "append", "items"] {
        assert!(unknown.contains(&m.to_string()), "{unknown:?}");
    }
    let m = at("s = ''\ns.rep|", false).unwrap().items.into_iter().find(|i| i.label == "replace").unwrap();
    assert_eq!((m.insert.as_str(), m.caret, m.kind), ("replace()", 8, ItemKind::Method));
    assert_eq!(m.detail.as_deref(), Some("replace(old, new, count=-1)"));
}

#[test]
fn event_fields_after_the_event_parameter() {
    has("def on_event(e):\n    e.|", false, &["type", "id", "value", "index", "get"]);
}

#[test]
fn keyword_arguments_inside_a_ui_call() {
    has("x = ui.button(\"a\", \"b\", st|)", false, &["style"]);
    has("x = ui.button(\"a\", \"b\", |)", true, &["style", "enabled"]);
    let given = labels("x = ui.label('a', style='title', |)", true);
    assert!(!given.contains(&"style".to_string()) && !given.contains(&"text".to_string()), "{given:?}");
    let style = at("x = ui.button('a', 'b', sty|)", false).unwrap().items.into_iter().find(|i| i.label == "style").unwrap();
    assert_eq!((style.insert.as_str(), style.caret), ("style=\"\"", 7));
    has("x = ui.column(ui.la|", false, &["label"]);
    has("x = ui.tabs('t', ui.p|", false, &["page"]);
    // Nothing until asked, at an empty argument.
    assert!(at("x = ui.label('a', |)", false).is_none());
}

#[test]
fn values_inside_strings() {
    let on_event = "def render():\n    return ui.row(ui.button(\"ok\", \"OK\"), ui.toggle(\"dark\", \"Dark\"))\n\n\ndef on_event(e):\n";
    has(&format!("{on_event}    if e.type == \"|\""), false, &["click", "toggle", "input_changed", "tab_selected", "date_changed"]);
    has(&format!("{on_event}    if e[\"type\"] == \"cl|\""), false, &["click"]);
    has(&format!("{on_event}    kind = e.get(\"type\")\n    if kind == \"|\""), false, &["date_changed"]);
    let ids = labels(&format!("{on_event}    if e.id == \"|\""), false);
    assert_eq!(ids, vec!["ok".to_string(), "dark".to_string()], "{ids:?}");
    has(&format!("{on_event}    x = e[\"|\"]"), false, &["type", "id", "value", "index"]);
    has("x = ui.label('a', style=\"|\")", false, &["title", "body", "caption"]);
    has("x = ui.button('a', 'b', style=\"|\")", false, &["primary"]);
    has("wm.storage.set('items', '1')\nwm.storage.get(\"|\")", false, &["items"]);
    let host = Host { actions: Some(vec!["rot13".into(), "quote".into()]), ..Host::default() };
    let (source, c) = caret("def transform(text, action):\n    if action == \"|\":\n        return text\n");
    let found = complete(&Document::new(&source), c, false, &host).unwrap();
    assert_eq!(found.items.iter().map(|i| i.label.as_str()).collect::<Vec<_>>(), vec!["rot13", "quote"]);
    assert!(at("x = 'just text |'", true).is_none());
}

#[test]
fn hooks_are_offered_only_while_missing() {
    has("def |", false, &["render", "on_event", "transform"]);
    let after = labels("def render():\n    return ui.column()\n\n\ndef |", false);
    assert!(!after.contains(&"render".to_string()) && after.contains(&"on_event".to_string()));
    has("de|", false, &["def render()", "def on_event(e)", "def transform(text, action)", "def ..."]);
    let defined = labels("def render():\n    return ui.column()\n\n\nde|", false);
    assert!(!defined.contains(&"def render()".to_string()) && defined.contains(&"def on_event(e)".to_string()));
    let snippet = at("de|", false).unwrap().items.into_iter().find(|i| i.label == "def render()").unwrap();
    assert_eq!(snippet.insert, "def render():\n    return ui.column(\n        \n    )");
    assert_eq!(snippet.caret, "def render():\n    return ui.column(\n        ".len());
}

#[test]
fn snippets_take_the_lines_indentation_and_only_start_statements() {
    let s = at("def f():\n    whi|", false).unwrap().items.into_iter().find(|i| i.label == "while ...").unwrap();
    assert_eq!(s.insert, "while :\n        pass");
    assert!(!labels("x = whi|", false).contains(&"while ...".to_string()));
}

#[test]
fn names_rank_locals_first() {
    let found = labels("alphabet = 1\n\n\ndef f(alpha):\n    al|", false);
    assert_eq!(found.first().map(String::as_str), Some("alpha"), "{found:?}");
    assert!(found.contains(&"alphabet".to_string()));
    has("le|", false, &["len"]);
    has("Val|", false, &["ValueError"]);
    has("whi|", false, &["while"]);
    has("w|", false, &["wm"]);
    let len = at("le|", false).unwrap().items.into_iter().find(|i| i.label == "len").unwrap();
    assert_eq!((len.insert.as_str(), len.caret), ("len()", 4));
}

#[test]
fn quiet_where_nothing_fits() {
    assert!(at("# wm.|", true).is_none());
    assert!(at("x = |", false).is_none());
    assert!(at("x = |", true).is_some());
    assert!(at("x = 12|", true).is_none());
    let (source, _) = caret("x|");
    assert!(complete(&Document::new(&source), 99, true, &Host::default()).is_none());
}

#[test]
fn inside_an_f_string_field() {
    has("name = 'x'\ngreeting = f\"hi {na|}\"", false, &["name"]);
    has("greeting = f\"{wm.|}\"", false, &["plugin_id"]);
}

#[test]
fn works_in_a_broken_file() {
    has("def render():\n    return ui.column(\n        ui.|", false, &["label", "button"]);
    has("def f(count):\n    x = (count +\n    co|", false, &["count"]);
}

#[test]
fn never_more_than_the_cap() {
    let c = at("|", true).unwrap();
    assert!(c.items.len() <= MAX_ITEMS);
    assert_eq!(c.items.len(), MAX_ITEMS);
}

#[test]
fn carets_in_items_are_utf16() {
    let c = at("café = 1\nca|", false).unwrap();
    assert!(c.items.iter().any(|i| i.label == "café" && i.caret == 4));
}
