//! The entry points the JNI and wasm wrappers call. Text in, JSON text (or a
//! packed int array) out. Every offset is a UTF-16 code unit, every span is
//! half-open, and no input makes any of these panic.
//!
//! The last two documents built are kept, so the app asking for diagnostics,
//! then completion, then the API under the caret of one text parses it once.
//!
//! # Shapes
//!
//! `host` (input to [`analyse`] and [`complete`]); every field optional, and
//! `"{}"` skips the checks that depend on the manifest:
//! ```json
//! {"storage": true, "reminders": false, "selection": false, "panel": true,
//!  "actions": ["rot13"], "modules": ["helpers"],
//!  "verdict": {"message": "...", "kind": "SyntaxError", "line": 3} | null}
//! ```
//!
//! [`analyse`]:
//! ```json
//! {"diagnostics": [{"code": "undefined_name", "severity": "error"|"warning"|"info",
//!                   "start": 0, "end": 3, "args": ["x"]}],
//!  "outline": [{"name": "render", "kind": "function"|"variable", "depth": 0,
//!               "start": 0, "end": 40, "nameStart": 4, "nameEnd": 10}],
//!  "folds": [[0, 40]],
//!  "verdictUsed": true}
//! ```
//! `args` holds zero to two strings, filling `{0}` and `{1}` of the code's message.
//!
//! [`complete`]: `null`, or
//! ```json
//! {"replaceStart": 10, "replaceEnd": 12,
//!  "items": [{"label": "get", "kind": "variable"|"parameter"|"function"|"method"|"module"|
//!             "constant"|"field"|"keyword"|"snippet"|"value",
//!             "insert": "get()", "caret": 4, "detail": "get(key, default=None)" | null,
//!             "docPath": "wm.storage.get" | null}]}
//! ```
//! `caret` is a UTF-16 offset into `insert`.
//!
//! [`definition`]: `null` or `{"start": n, "end": n}`.
//! [`references`]: `[[start, end], ...]`, empty when there are none.
//! [`rename`]: `null` when nothing renamable is at the caret, else
//! `{"spans": [[s, e], ...], "problem": null | "not_a_name" | "keyword" | "builtin" | "taken" | "api" | "hook"}`.
//! An empty `new_name` asks for the plan only.
//! [`api_at`]: `null` or an entry, below.
//! [`api_entries`]:
//! ```json
//! {"entries": [{"path": "wm.storage.get", "parent": "wm.storage", "name": "get",
//!               "kind": "module"|"function"|"method"|"constant"|"builtin"|"exception"|"type",
//!               "signature": "get(key, default=None)" | null, "doc": "...", "permission": "storage" | null}],
//!  "eventTypes": ["click", "toggle", "input_changed", "tab_selected", "date_changed"],
//!  "hooks": ["render", "on_event", "transform"]}
//! ```
//! [`tokens`]: triples `word, start, end`; see [`crate::highlight::pack`].
//! [`diagnostic_codes`]: `[{"code", "severity", "message"}]`, every code there is.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value};

use crate::api::{self, ApiEntry};
use crate::complete;
use crate::diagnostics::{diagnose, Code, Fix};
use crate::document::{Document, Host, SettingSpec, Verdict};
use crate::format as formatter;
use crate::highlight;
use crate::navigate;
use crate::text::Span;

thread_local! {
    static RECENT: RefCell<Vec<(String, Rc<Document>)>> = const { RefCell::new(Vec::new()) };
}

/// The document for [source], from the cache when it was built lately.
pub fn document(source: &str) -> Rc<Document> {
    if let Some(found) = RECENT.with(|r| r.borrow().iter().find(|(s, _)| s == source).map(|(_, d)| d.clone())) {
        return found;
    }
    let built = Rc::new(Document::new(source));
    RECENT.with(|r| {
        let mut r = r.borrow_mut();
        r.retain(|(s, _)| s != source);
        r.insert(0, (source.to_string(), built.clone()));
        r.truncate(2);
    });
    built
}

pub fn parse_host(host_json: &str) -> Host {
    let value: Value = serde_json::from_str(host_json).unwrap_or(Value::Null);
    let mut host = value.get("manifest").filter(|m| m.is_object()).map(host_of_manifest).unwrap_or_default();
    let flag = |name: &str| value.get(name).and_then(Value::as_bool);
    let strings = |name: &str| -> Option<Vec<String>> {
        value.get(name).and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
    };
    // Fields given on their own win over what the manifest says.
    host.storage = flag("storage").or(host.storage);
    host.reminders = flag("reminders").or(host.reminders);
    host.selection = flag("selection").or(host.selection);
    host.panel = flag("panel").or(host.panel);
    host.actions = strings("actions").or(host.actions);
    host.modules = strings("modules").or(host.modules);
    host.verdict = value.get("verdict").filter(|v| v.is_object()).map(|v| Verdict {
        message: v.get("message").and_then(Value::as_str).unwrap_or("").to_string(),
        kind: v.get("kind").and_then(Value::as_str).unwrap_or("").to_string(),
        line: v.get("line").and_then(Value::as_u64).map(|l| l as usize),
    });
    host
}

/// What a plugin.json says, as the checks and suggestions read it.
pub fn host_of_manifest(m: &Value) -> Host {
    let text = |v: &Value, key: &str| v.get(key).and_then(Value::as_str).map(|s| s.trim().to_string());
    let list = |key: &str| m.get(key).and_then(Value::as_array).cloned().unwrap_or_default();
    let api = m.get("apiVersion").and_then(Value::as_i64).unwrap_or(5);
    let permissions: Vec<String> = list("permissions").iter().filter_map(|p| p.as_str().map(|s| s.trim().to_string())).collect();
    let has = |p: &str| permissions.iter().any(|x| x == p);
    let hosts: Vec<String> = list("hosts")
        .iter()
        .filter_map(|h| h.as_str())
        .map(|h| h.trim().trim_start_matches("https://").trim_end_matches(['/', '.']).to_lowercase())
        .filter(|h| !h.is_empty())
        .collect();
    let actions: Vec<String> = list("actions").iter().filter_map(|a| text(a, "id")).filter(|s| !s.is_empty()).collect();
    let providers: Vec<(String, String)> = list("providers")
        .iter()
        .filter_map(|p| {
            let tool = text(p, "tool")?;
            let id = text(p, "id").filter(|s| !s.is_empty()).unwrap_or_else(|| tool.clone());
            (!tool.is_empty()).then_some((tool, id))
        })
        .collect();
    let settings: Vec<SettingSpec> = list("settings")
        .iter()
        .filter_map(|s| {
            let key = text(s, "key").filter(|k| !k.is_empty())?;
            Some(SettingSpec {
                key,
                kind: text(s, "type").filter(|t| !t.is_empty()).unwrap_or_else(|| "text".into()),
                label: text(s, "label").unwrap_or_default(),
                options: s
                    .get("options")
                    .and_then(Value::as_array)
                    .map(|o| o.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                    .unwrap_or_default(),
            })
        })
        .collect();
    let keyword = m.get("tool").and_then(|t| text(t, "keyword")).unwrap_or_default();
    Host {
        storage: Some(has("storage")),
        reminders: Some(has("reminders")),
        selection: Some(has("selection")),
        panel: Some(m.get("panel").and_then(Value::as_bool).unwrap_or(true)),
        actions: Some(actions),
        modules: None,
        verdict: None,
        api: Some(api),
        permissions: Some(permissions),
        hosts: Some(hosts),
        providers: Some(providers),
        settings: Some(settings),
        keyword: Some(keyword),
    }
}

/// `{"kind": "replace", "text"}`, `{"kind": "insert", "at", "text"}`,
/// `{"kind": "permission" | "host" | "action" | "keyword" | "setting", "value"}`
/// or `{"kind": "provider", "tool", "id"}`.
pub fn fix_json(fix: &Fix) -> Value {
    match fix {
        Fix::Replace(text) => json!({ "kind": "replace", "text": text }),
        Fix::Insert(at, text) => json!({ "kind": "insert", "at": at, "text": text }),
        Fix::Permission(v) => json!({ "kind": "permission", "value": v }),
        Fix::Host(v) => json!({ "kind": "host", "value": v }),
        Fix::Provider(tool, id) => json!({ "kind": "provider", "tool": tool, "id": id }),
        Fix::Action(v) => json!({ "kind": "action", "value": v }),
        Fix::Keyword(v) => json!({ "kind": "keyword", "value": v }),
        Fix::Setting(v) => json!({ "kind": "setting", "value": v }),
    }
}

fn pair(span: Span) -> Value {
    json!([span.start, span.end])
}

pub fn analyse(source: &str, host_json: &str) -> String {
    analyse_value(source, &parse_host(host_json)).to_string()
}

pub fn analyse_value(source: &str, host: &Host) -> Value {
    let doc = document(source);
    let report = diagnose(&doc, host);
    let diagnostics: Vec<Value> = report
        .diagnostics
        .iter()
        .map(|d| {
            json!({
                "code": d.code.name(),
                "severity": d.severity().name(),
                "start": d.span.start,
                "end": d.span.end,
                "args": d.args,
                "fix": d.fix.as_ref().map(fix_json),
            })
        })
        .collect();
    let outline: Vec<Value> = navigate::outline(&doc)
        .iter()
        .map(|o| {
            json!({
                "name": o.name, "kind": o.kind, "depth": o.depth,
                "start": o.span.start, "end": o.span.end,
                "nameStart": o.name_span.start, "nameEnd": o.name_span.end,
            })
        })
        .collect();
    let folds: Vec<Value> = navigate::folds_from_tokens(&doc.tokens).into_iter().map(pair).collect();
    json!({ "diagnostics": diagnostics, "outline": outline, "folds": folds, "verdictUsed": report.verdict_used })
}

pub fn complete(source: &str, caret: i64, explicit: bool, host_json: &str) -> String {
    if caret < 0 {
        return "null".into();
    }
    let doc = document(source);
    let host = parse_host(host_json);
    match complete::complete(&doc, caret.min(u32::MAX as i64) as u32, explicit, &host) {
        None => "null".into(),
        Some(c) => {
            let items: Vec<Value> = c
                .items
                .iter()
                .map(|i| {
                    json!({
                        "label": i.label, "kind": i.kind.name(), "insert": i.insert, "caret": i.caret,
                        "detail": i.detail, "docPath": i.doc_path, "doc": i.doc, "reopen": i.reopen,
                    })
                })
                .collect();
            json!({ "replaceStart": c.replace.start, "replaceEnd": c.replace.end, "items": items }).to_string()
        }
    }
}

pub fn format(source: &str) -> String {
    formatter::format(source)
}

pub fn definition(source: &str, caret: i64) -> String {
    if caret < 0 {
        return "null".into();
    }
    let doc = document(source);
    match navigate::definition(&doc, caret as u32) {
        Some(s) => json!({ "start": s.start, "end": s.end }).to_string(),
        None => "null".into(),
    }
}

pub fn references(source: &str, caret: i64) -> String {
    if caret < 0 {
        return "[]".into();
    }
    let doc = document(source);
    Value::Array(navigate::references(&doc, caret as u32).into_iter().map(pair).collect()).to_string()
}

pub fn rename(source: &str, caret: i64, new_name: &str) -> String {
    if caret < 0 {
        return "null".into();
    }
    let doc = document(source);
    match navigate::rename(&doc, caret as u32, new_name) {
        None => "null".into(),
        Some(plan) => {
            let spans: Vec<Value> = plan.spans.into_iter().map(pair).collect();
            json!({ "spans": spans, "problem": plan.problem.map(|p| p.name()) }).to_string()
        }
    }
}

fn entry_json(e: &ApiEntry) -> Value {
    json!({
        "path": e.path, "parent": e.parent(), "name": e.name(), "kind": e.kind.name(),
        "signature": e.signature, "doc": e.doc, "permission": e.permission,
    })
}

pub fn api_at(source: &str, caret: i64) -> String {
    if caret < 0 {
        return "null".into();
    }
    let doc = document(source);
    match navigate::api_at(&doc, caret as u32) {
        Some(e) => entry_json(e).to_string(),
        None => "null".into(),
    }
}

/// Signature help at [caret]: `null`, or
/// `{"label", "params": [[s, e]], "names": [...], "active": n | null, "doc", "paramDoc", "values": [...], "path"}`.
pub fn signature(source: &str, caret: i64, host_json: &str) -> String {
    if caret < 0 {
        return "null".into();
    }
    let doc = document(source);
    let host = parse_host(host_json);
    match crate::signature::signature_at(&doc, caret as u32, &host) {
        Some(s) => json!({
            "label": s.label,
            "params": s.params.iter().map(|(a, b)| json!([a, b])).collect::<Vec<_>>(),
            "names": s.names,
            "active": s.active,
            "doc": s.doc,
            "paramDoc": s.param_doc,
            "values": s.values,
            "path": s.path,
        })
        .to_string(),
        None => "null".into(),
    }
}

/// What the name at [offset] is: `null`, or `{"start", "end", "title", "doc", "kind", "path"}`.
pub fn hover(source: &str, offset: i64, host_json: &str) -> String {
    if offset < 0 {
        return "null".into();
    }
    let doc = document(source);
    let host = parse_host(host_json);
    match crate::signature::hover_at(&doc, offset as u32, &host) {
        Some(h) => json!({ "start": h.span.start, "end": h.span.end, "title": h.title, "doc": h.doc, "kind": h.kind, "path": h.path }).to_string(),
        None => "null".into(),
    }
}

/// The provider lists a plugin can answer, for the editor's Try pane and forms:
/// `[{"tool", "label", "request": [[key, doc]], "sample", "reply"}]`.
pub fn provider_tools() -> String {
    let tools: Vec<Value> = crate::keyboard::PROVIDER_TOOLS
        .iter()
        .map(|t| {
            json!({
                "tool": t.name,
                "label": t.label,
                "request": t.request.iter().map(|(k, d)| json!([k, d])).collect::<Vec<_>>(),
                "sample": t.sample,
                "reply": t.reply,
            })
        })
        .collect();
    Value::Array(tools).to_string()
}

pub fn api_entries() -> String {
    let entries: Vec<Value> = api::model().entries.iter().map(entry_json).collect();
    json!({ "entries": entries, "eventTypes": api::EVENT_TYPES, "hooks": api::CONTRACT }).to_string()
}

pub fn tokens(source: &str) -> Vec<i32> {
    highlight::tokens_packed(source)
}

pub fn in_inert(source: &str, offset: i64) -> bool {
    offset > 0 && highlight::in_inert(source, offset as usize)
}

pub fn opens_block(line: &str) -> bool {
    highlight::opens_block(line)
}

/// Every diagnostic code with its severity and English message.
pub fn diagnostic_codes() -> String {
    let codes: Vec<Value> = Code::ALL
        .iter()
        .map(|c| json!({ "code": c.name(), "severity": c.severity().name(), "message": c.message() }))
        .collect();
    Value::Array(codes).to_string()
}
