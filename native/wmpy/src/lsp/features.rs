//! One file's answers: completion, hover, signature help, symbols, folds,
//! semantic tokens, formatting, code lenses and code actions, each read from
//! wm-python-tooling and written as LSP JSON.

use lsp_types::{Position, Range, Uri};
use serde_json::{json, Value};
use wm_python_tooling::complete::{self, ItemKind};
use wm_python_tooling::diagnostics::{Code, Diagnostic, Fix, Severity};
use wm_python_tooling::document::{Document, Host};
use wm_python_tooling::highlight::{self, kind, style};
use wm_python_tooling::{api, format as formatter, navigate, signature};

use super::text::{byte_of_utf16, utf16_of_byte, LineIndex};

/// What the client can take, from its initialize capabilities.
#[derive(Clone, Copy, Debug, Default)]
pub struct Caps {
    pub snippets: bool,
    pub markdown: bool,
    /// VS Code, which knows `editor.action.triggerSuggest`.
    pub vscode: bool,
    pub watch_dynamic: bool,
}

/// One Python file, read for a request.
pub struct File<'a> {
    pub text: &'a str,
    pub doc: &'a Document,
    pub index: LineIndex,
    pub host: Host,
}

impl<'a> File<'a> {
    pub fn new(text: &'a str, doc: &'a Document, host: Host) -> File<'a> {
        File { text, doc, index: LineIndex::of_units(doc.units()), host }
    }

    pub fn offset(&self, p: Position) -> u32 {
        self.index.offset(p)
    }

    pub fn range(&self, start: u32, end: u32) -> Value {
        range_json(self.index.range(start, end))
    }
}

pub fn range_json(r: Range) -> Value {
    serde_json::to_value(r).unwrap_or(Value::Null)
}

fn markup(caps: Caps, text: String) -> Value {
    json!({"kind": if caps.markdown { "markdown" } else { "plaintext" }, "value": text})
}

/// The Markdown for an API entry: its signature, its doc and its permission.
pub fn entry_markdown(path: &str) -> Option<String> {
    let e = api::model().find(path)?;
    let mut out = format!("```python\n{}{}\n```", e.path, e.signature.unwrap_or(""));
    if !e.doc.is_empty() {
        out.push_str("\n\n");
        out.push_str(e.doc);
    }
    if let Some(p) = e.permission {
        out.push_str(&format!("\n\nNeeds the `{p}` permission in plugin.json."));
    }
    Some(out)
}

// ---- diagnostics -------------------------------------------------------------

pub fn diagnostic_json(file_index: &LineIndex, d: &Diagnostic) -> Value {
    let severity = match d.severity() {
        Severity::Error => 1,
        Severity::Warning => 2,
        Severity::Info => 3,
    };
    let mut out = json!({
        "range": range_json(file_index.range(d.span.start, d.span.end)),
        "severity": severity,
        "code": d.code.name(),
        "source": "wmpy",
        "message": d.text(),
    });
    if matches!(d.code, Code::UnusedLocal | Code::UnusedParameter | Code::Unreachable) {
        out["tags"] = json!([1]);
    }
    out
}

// ---- completion ----------------------------------------------------------------

fn item_kind(k: ItemKind) -> u32 {
    match k {
        ItemKind::Variable | ItemKind::Parameter => 6,
        ItemKind::Function => 3,
        ItemKind::Method => 2,
        ItemKind::Module => 9,
        ItemKind::Constant => 21,
        ItemKind::Field => 5,
        ItemKind::Keyword => 14,
        ItemKind::Snippet => 15,
        ItemKind::Value => 12,
    }
}

fn snippet_escape(text: &str) -> String {
    text.replace('\\', "\\\\").replace('$', "\\$").replace('}', "\\}")
}

/// The completion list at [pos]. [explicit] when the user asked for it.
pub fn completion(f: &File, pos: Position, explicit: bool, caps: Caps) -> Value {
    let caret = f.offset(pos);
    let Some(c) = complete::complete(f.doc, caret, explicit, &f.host) else { return json!({"isIncomplete": false, "items": []}) };
    let range = f.range(c.replace.start, c.replace.end);
    let items: Vec<Value> = c
        .items
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let caret_byte = byte_of_utf16(&item.insert, item.caret as u32);
            let at_end = caret_byte >= item.insert.len();
            let (new_text, format) = if !at_end && caps.snippets {
                (format!("{}$0{}", snippet_escape(&item.insert[..caret_byte]), snippet_escape(&item.insert[caret_byte..])), 2)
            } else {
                (item.insert.clone(), 1)
            };
            let mut out = json!({
                "label": item.label,
                "kind": item_kind(item.kind),
                "sortText": format!("{i:04}"),
                "filterText": item.label,
                "textEdit": {"range": range, "newText": new_text},
                "insertTextFormat": format,
            });
            if let Some(d) = &item.detail {
                out["detail"] = json!(d);
            }
            if item.insert.contains('\n') {
                // The tooling writes the indentation itself.
                out["insertTextMode"] = json!(1);
            }
            if item.reopen && caps.vscode {
                out["command"] = json!({"title": "Suggest", "command": "editor.action.triggerSuggest"});
            }
            if item.doc_path.is_some() || item.doc.is_some() {
                out["data"] = json!({"docPath": item.doc_path, "doc": item.doc});
            }
            out
        })
        .collect();
    json!({"isIncomplete": false, "items": items})
}

/// [item] with its documentation filled in from what [completion] left in `data`.
pub fn completion_resolve(mut item: Value, caps: Caps) -> Value {
    let data = item.get("data").cloned().unwrap_or(Value::Null);
    let path = data.get("docPath").and_then(Value::as_str);
    let doc = data.get("doc").and_then(Value::as_str);
    let text = path.and_then(entry_markdown).or_else(|| doc.map(str::to_string));
    if let Some(text) = text {
        item["documentation"] = markup(caps, text);
    }
    item
}

// ---- hover and signature help ------------------------------------------------

pub fn hover(f: &File, pos: Position, caps: Caps) -> Value {
    let at = f.offset(pos);
    let Some(h) = signature::hover_at(f.doc, at, &f.host) else { return Value::Null };
    let mut text = format!("```python\n{}\n```", h.title);
    if !h.doc.is_empty() {
        text.push_str("\n\n");
        text.push_str(&h.doc);
    }
    if let Some(p) = h.path.as_deref().and_then(|p| api::model().find(p)).and_then(|e| e.permission) {
        text.push_str(&format!("\n\nNeeds the `{p}` permission in plugin.json."));
    }
    json!({"contents": markup(caps, text), "range": f.range(h.span.start, h.span.end)})
}

pub fn signature_help(f: &File, pos: Position, caps: Caps) -> Value {
    let at = f.offset(pos);
    let Some(s) = signature::signature_at(f.doc, at, &f.host) else { return Value::Null };
    let params: Vec<Value> = s
        .params
        .iter()
        .enumerate()
        .map(|(i, (a, b))| {
            let mut p = json!({"label": [a, b]});
            if Some(i) == s.active {
                let mut words = s.param_doc.clone().unwrap_or_default();
                if !s.values.is_empty() && words.is_empty() {
                    words = format!("One of: {}", s.values.join(", "));
                }
                if !words.is_empty() {
                    p["documentation"] = markup(caps, words);
                }
            }
            p
        })
        .collect();
    let mut sig = json!({"label": s.label, "parameters": params});
    if !s.doc.is_empty() {
        sig["documentation"] = markup(caps, s.doc.clone());
    }
    if let Some(a) = s.active {
        sig["activeParameter"] = json!(a);
    }
    json!({"signatures": [sig], "activeSignature": 0, "activeParameter": s.active})
}

// ---- the file's shape ----------------------------------------------------------

pub fn document_symbols(f: &File) -> Value {
    // (depth, symbol) pairs folded into a tree by depth.
    let mut stack: Vec<(usize, Value)> = Vec::new();
    let mut top: Vec<Value> = Vec::new();
    fn close(stack: &mut Vec<(usize, Value)>, top: &mut Vec<Value>, depth: usize) {
        while stack.last().is_some_and(|(d, _)| *d >= depth) {
            let (_, done) = stack.pop().expect("checked");
            match stack.last_mut() {
                Some((_, parent)) => parent["children"].as_array_mut().expect("array").push(done),
                None => top.push(done),
            }
        }
    }
    for o in navigate::outline(f.doc) {
        close(&mut stack, &mut top, o.depth);
        let symbol = json!({
            "name": o.name,
            "kind": if o.kind == "function" { 12 } else { 13 },
            "range": f.range(o.span.start, o.span.end),
            "selectionRange": f.range(o.name_span.start, o.name_span.end),
            "children": [],
        });
        stack.push((o.depth, symbol));
    }
    close(&mut stack, &mut top, 0);
    Value::Array(top)
}

pub fn folding_ranges(f: &File) -> Value {
    let mut out = Vec::new();
    for span in navigate::folds_from_tokens(&f.doc.tokens) {
        let start = f.index.position(span.start).line;
        let end = f.index.position(span.end).line;
        if end > start {
            out.push(json!({"startLine": start, "endLine": end}));
        }
    }
    Value::Array(out)
}

pub const TOKEN_TYPES: &[&str] = &["namespace", "class", "function", "parameter", "variable", "property"];
pub const TOKEN_MODIFIERS: &[&str] = &["defaultLibrary", "readonly"];

/// Names coloured by what they are: the plugin API, builtins, functions,
/// parameters, constants and attributes. The editor's grammar colours the rest.
pub fn semantic_tokens(f: &File) -> Value {
    let packed = highlight::pack(&f.doc.tokens);
    let units = f.doc.units();
    let triples: Vec<(i32, u32, u32)> = packed.chunks(3).map(|c| (c[0], c[1] as u32, c[2] as u32)).collect();
    let text_of = |s: u32, e: u32| String::from_utf16_lossy(&units[s as usize..(e as usize).min(units.len())]);
    let mut data: Vec<u32> = Vec::new();
    let (mut last_line, mut last_start) = (0u32, 0u32);
    for (i, &(word, start, end)) in triples.iter().enumerate() {
        if word & 0xff != kind::NAME {
            continue;
        }
        let name_style = word >> 12;
        let next = triples[i + 1..].iter().find(|(w, _, _)| !matches!(w & 0xff, kind::WHITESPACE | kind::NEWLINE | kind::COMMENT));
        let called = next.is_some_and(|(w, s, e)| w & 0xff == kind::OPERATOR && text_of(*s, *e) == "(");
        let after_dot = triples[..i]
            .iter()
            .rev()
            .find(|(w, _, _)| !matches!(w & 0xff, kind::WHITESPACE | kind::NEWLINE | kind::COMMENT))
            .is_some_and(|(w, s, e)| w & 0xff == kind::OPERATOR && text_of(*s, *e) == ".");
        let name = text_of(start, end);
        let (ty, mods) = match name_style {
            style::API if after_dot && called => (2, 1),
            style::API if after_dot => (5, 1),
            style::API => (0, 1),
            style::BUILTIN if name.ends_with("Error") || name.ends_with("Exception") => (1, 1),
            style::BUILTIN if called => (2, 1),
            style::BUILTIN => (4, 1),
            style::FUNCTION => (2, 0),
            style::PARAMETER => (3, 0),
            style::CONSTANT => (4, 2),
            style::ATTRIBUTE if called => (2, 0),
            style::ATTRIBUTE => (5, 0),
            _ => continue,
        };
        let p = f.index.position(start);
        let delta_line = p.line - last_line;
        let delta_start = if delta_line == 0 { p.character - last_start } else { p.character };
        data.extend([delta_line, delta_start, end - start, ty, mods]);
        last_line = p.line;
        last_start = p.character;
    }
    json!({"data": data})
}

pub fn formatting(f: &File) -> Value {
    let out = formatter::format(f.text);
    if out == f.text || out.is_empty() && !f.text.trim().is_empty() {
        return json!([]);
    }
    json!([{"range": range_json(super::text::whole(f.text)), "newText": out}])
}

// ---- code lenses ---------------------------------------------------------------

/// "Preview" over `def render`, "Run tests" at the top of a test file and
/// over each `def test_*`.
pub fn code_lenses(f: &File, folder_uri: &str, is_test: bool) -> Value {
    let mut out = Vec::new();
    let at_line = |span_start: u32| {
        let line = f.index.position(span_start).line;
        json!({"start": {"line": line, "character": 0}, "end": {"line": line, "character": 0}})
    };
    if !is_test {
        if let Some(render) = f.doc.analysis.top_function("render") {
            out.push(json!({"range": at_line(render.span.start), "command": {"title": "▶ Preview", "command": "wmpy.preview", "arguments": [folder_uri]}}));
        }
        return Value::Array(out);
    }
    out.push(json!({"range": at_line(0), "command": {"title": "▶ Run tests", "command": "wmpy.test", "arguments": [folder_uri]}}));
    for func in &f.doc.analysis.functions {
        let Some(name) = func.name.as_deref() else { continue };
        if func.top_level && name.starts_with("test_") {
            out.push(json!({"range": at_line(func.span.start), "command": {"title": "▶ Run test", "command": "wmpy.test", "arguments": [folder_uri, name]}}));
        }
    }
    Value::Array(out)
}

// ---- code actions --------------------------------------------------------------

/// plugin.json beside the file, for fixes that change it.
pub struct ManifestFile<'a> {
    pub uri: &'a Uri,
    pub text: &'a str,
}

fn capitalised(text: &str) -> String {
    let mut c = text.chars();
    match c.next() {
        Some(first) => first.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// A quick fix for each problem in [diagnostics] that touches [range] and has one.
pub fn code_actions(f: &File, uri: &Uri, range: Range, diagnostics: &[Diagnostic], manifest: Option<ManifestFile>) -> Value {
    let (start, end) = (f.offset(range.start), f.offset(range.end));
    let mut out = Vec::new();
    for d in diagnostics {
        let Some(fix) = &d.fix else { continue };
        let touches = d.span.start <= end && start <= d.span.end;
        if !touches {
            continue;
        }
        let edit = match fix {
            Fix::Replace(text) => json!({"changes": {uri.as_str(): [{"range": f.range(d.span.start, d.span.end), "newText": text}]}}),
            Fix::Insert(at, text) => json!({"changes": {uri.as_str(): [{"range": f.range(*at, *at), "newText": text}]}}),
            _ => {
                let Some(m) = &manifest else { continue };
                let Some(edits) = super::manifest::fix_edits(m.text, fix) else { continue };
                let index = LineIndex::new(m.text);
                let edits: Vec<Value> = edits
                    .iter()
                    .map(|e| json!({"range": range_json(index.range(utf16_of_byte(m.text, e.start), utf16_of_byte(m.text, e.end))), "newText": e.text}))
                    .collect();
                json!({"changes": {m.uri.as_str(): edits}})
            }
        };
        out.push(json!({
            "title": capitalised(&super::plugin::fix_help(fix)),
            "kind": "quickfix",
            "diagnostics": [diagnostic_json(&f.index, d)],
            "isPreferred": true,
            "edit": edit,
        }));
    }
    Value::Array(out)
}
