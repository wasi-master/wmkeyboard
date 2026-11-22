//! The editor's knowledge of the keyboard: suggestions filled from
//! plugin.json, signature help, hover, and the fixes diagnostics carry.

mod common;

use common::*;
use wm_python_tooling::api_json;
use wm_python_tooling::complete::complete;
use wm_python_tooling::diagnostics::{diagnose, Fix};
use wm_python_tooling::document::{Document, Host, SettingSpec};
use wm_python_tooling::signature::{hover_at, signature_at};

fn manifest() -> Host {
    api_json::host_of_manifest(&serde_json::json!({
        "apiVersion": 5,
        "permissions": ["network"],
        "hosts": ["api.example.com"],
        "providers": [{"tool": "search", "id": "ddg"}, {"tool": "translate"}],
        "actions": [{"id": "shout", "name": "Shout"}],
        "settings": [{"key": "units", "type": "choice", "label": "Units", "options": ["metric", "imperial"]}],
        "tool": {"keyword": "define"}
    }))
}

fn labels(marked: &str, explicit: bool) -> Vec<String> {
    let (source, at) = caret(marked);
    let doc = Document::new(&source);
    complete(&doc, at, explicit, &manifest()).map(|c| c.items.into_iter().map(|i| i.label).collect()).unwrap_or_default()
}

fn has(marked: &str, explicit: bool, wanted: &[&str]) {
    let found = labels(marked, explicit);
    for w in wanted {
        assert!(found.iter().any(|f| f == w), "{marked:?}: no {w} in {found:?}");
    }
}

const HEAD: &str = "from wm import plugin, ui, http\n\n\n";

#[test]
fn decorators_after_an_at_sign() {
    has(&format!("{HEAD}@|"), false, &["action", "provider", "keyword", "command", "every", "on_open"]);
    has(&format!("{HEAD}@plugin.|"), false, &["provider", "setting_changed"]);
}

#[test]
fn decorator_arguments_come_from_the_manifest() {
    let tools = labels(&format!("{HEAD}@plugin.provider(\"|\")"), false);
    assert_eq!(tools.first().map(String::as_str), Some("search"), "{tools:?}");
    assert!(tools.contains(&"grammar".to_string()));
    has(&format!("{HEAD}@plugin.provider(\"search\", \"|\")"), false, &["ddg"]);
    has(&format!("{HEAD}@plugin.action(\"|\")"), false, &["shout"]);
    has(&format!("{HEAD}@plugin.keyword(\"|\")"), false, &["define"]);
    has(&format!("{HEAD}@plugin.setting_changed(\"|\")"), false, &["units"]);
}

#[test]
fn package_calls_offer_their_arguments_and_values() {
    has(&format!("{HEAD}x = ui.label(\"a\", st|)"), false, &["style"]);
    has(&format!("{HEAD}x = ui.label(\"a\", style=\"|\")"), false, &["title", "caption"]);
    has(&format!("{HEAD}x = ui.label(\"a\", color=\"|\")"), false, &["danger", "muted"]);
    has(&format!("{HEAD}x = ui.button(\"Go\", icon=\"se|\")"), false, &["search", "settings"]);
    has(&format!("{HEAD}r = http.get(\"|\")"), false, &["https://api.example.com/"]);
    has(&format!("{HEAD}def go():\n    pass\n\n\nx = ui.button(\"Go\", on_click=|)"), false, &["go"]);
}

#[test]
fn a_providers_request_and_the_settings_are_known() {
    has(&format!("{HEAD}@plugin.provider(\"search\", \"ddg\")\ndef search(request):\n    q = request[\"|\"]"), false, &["query", "count", "page"]);
    has(&format!("{HEAD}@plugin.provider(\"search\", \"ddg\")\ndef search(request):\n    q = request.get(\"|\")"), false, &["query"]);
    has(&format!("{HEAD}x = plugin.settings.|"), false, &["units", "get"]);
}

#[test]
fn values_the_package_returns_have_members() {
    has(&format!("{HEAD}r = http.get(\"https://api.example.com/\")\nr.|"), false, &["status_code", "json", "raise_for_status", "text"]);
    has(&format!("{HEAD}state = plugin.state(count=0, city=\"\")\nstate.|"), false, &["count", "city", "update"]);
}

#[test]
fn snippets_for_the_package() {
    has(&format!("{HEAD}prov|"), false, &["provider search ddg", "provider translate"]);
    has(&format!("{HEAD}act|"), false, &["action ..."]);
    // An API 4 script is offered no package snippets.
    let old = Document::new("prov");
    let host = Host { api: Some(4), ..Host::default() };
    let found = complete(&old, 4, false, &host).map(|c| c.items.into_iter().map(|i| i.label).collect::<Vec<_>>()).unwrap_or_default();
    assert!(!found.iter().any(|l| l.starts_with("provider")), "{found:?}");
}

#[test]
fn signature_help_follows_the_argument() {
    let (source, at) = caret(&format!("{HEAD}r = http.get(|"));
    let s = signature_at(&Document::new(&source), at, &manifest()).expect("a signature");
    assert!(s.label.starts_with("http.get("), "{}", s.label);
    assert_eq!(s.active, Some(0));
    assert!(s.param_doc.unwrap_or_default().contains("api.example.com"));

    let (source, at) = caret(&format!("{HEAD}x = ui.label(\"a\", style=|"));
    let s = signature_at(&Document::new(&source), at, &manifest()).expect("a signature");
    assert_eq!(s.names[s.active.unwrap()], "style");
    assert!(s.values.contains(&"caption".to_string()));

    let (source, at) = caret("def add(a, b=2):\n    return a + b\n\n\nadd(1, |");
    let s = signature_at(&Document::new(&source), at, &Host::default()).expect("a signature");
    assert_eq!(s.label, "add(a, b=2)");
    assert_eq!(s.active, Some(1));
}

#[test]
fn hover_names_the_request_a_provider_gets() {
    let source = format!("{HEAD}@plugin.provider(\"search\", \"ddg\")\ndef search(request):\n    return request\n");
    let at = source.rfind("request").unwrap() as u32 + 1;
    let h = hover_at(&Document::new(&source), at, &manifest()).expect("a hover");
    assert!(h.title.contains("Web search"), "{}", h.title);
    assert!(h.doc.contains("query"), "{}", h.doc);
    let at = source.find("http").unwrap() as u32 + 1;
    let h = hover_at(&Document::new(&source), at, &manifest()).expect("a hover");
    assert_eq!(h.kind, "module");
}

#[test]
fn diagnostics_carry_fixes() {
    let fix_of = |source: &str, host: &Host, code: &str| {
        let doc = Document::new(source);
        diagnose(&doc, host).diagnostics.into_iter().find(|d| d.code.name() == code).and_then(|d| d.fix)
    };
    let host = manifest();
    assert_eq!(fix_of("from wm import ui\n\nx = plugin.id\n", &host, "wm_not_imported"), Some(Fix::Insert(17, ", plugin".into())));
    assert_eq!(
        fix_of(&format!("{HEAD}r = http.get(\"https://other.org/\")\n"), &host, "host_missing"),
        Some(Fix::Host("other.org".into()))
    );
    assert_eq!(fix_of(&format!("{HEAD}x = ui.label(\"a\", style=\"titel\")\n"), &host, "value_typo"), Some(Fix::Replace("\"title\"".into())));
    match fix_of(&format!("{HEAD}x = plugin.id\n"), &host, "provider_unanswered") {
        Some(Fix::Insert(_, text)) => assert!(text.contains("@plugin.provider(\"search\", \"ddg\")"), "{text}"),
        other => panic!("{other:?}"),
    }
    let no_network = Host { permissions: Some(vec![]), ..manifest() };
    assert_eq!(fix_of(HEAD, &no_network, "permission_missing"), Some(Fix::Permission("network".into())));
    // Applying a fix earns a clean file.
    let settings = Host { settings: Some(vec![SettingSpec { key: "units".into(), kind: "choice".into(), ..Default::default() }]), ..manifest() };
    assert!(fix_of(&format!("{HEAD}x = plugin.settings.unit\n"), &settings, "setting_typo") == Some(Fix::Replace("units".into())));
}

#[test]
fn the_manifest_reads_whole() {
    let h = manifest();
    assert_eq!(h.providers, Some(vec![("search".into(), "ddg".into()), ("translate".into(), "translate".into())]));
    assert_eq!(h.allows_host("api.example.com"), Some(true));
    assert_eq!(h.allows_host("example.com"), Some(false));
    assert_eq!(h.keyword.as_deref(), Some("define"));
    assert!(h.api5());
}
