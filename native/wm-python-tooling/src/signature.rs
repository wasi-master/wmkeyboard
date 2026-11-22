//! Signature help (the call being typed, with the argument the caret is in)
//! and hover (what the name under a pointer or the caret is).
//!
//! Both read tokens first, because the call being typed is unclosed and the
//! file around it does not parse, and names second, from the analysis.

use crate::analysis::{SymbolKind, Target};
use crate::api::{self, ApiEntry, ApiKind};
use crate::ast::*;
use crate::document::{Document, Host};
use crate::keyboard::{self, SigParam};
use crate::lexer::{string_value, TokenKind, CONTINUATION};
use crate::text::{slice, Span};

const MAX_BACK_TOKENS: usize = 3000;

/// The call around the caret.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Signature {
    /// `http.get(url, params=None, **options)`.
    pub label: String,
    /// Each parameter's place in [label], in UTF-16 units.
    pub params: Vec<(u32, u32)>,
    pub names: Vec<String>,
    /// The parameter the caret is in, when there is one.
    pub active: Option<usize>,
    pub doc: String,
    /// What the active parameter takes, in words, when the editor knows: `One of: title, body`.
    pub param_doc: Option<String>,
    /// The literal values the active parameter takes.
    pub values: Vec<String>,
    pub path: Option<String>,
}

/// What a name is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hover {
    pub span: Span,
    pub title: String,
    pub doc: String,
    /// `function`, `variable`, `parameter`, `module`, `setting`, `provider` or an API kind.
    pub kind: String,
    pub path: Option<String>,
}

fn utf16(text: &str, byte: usize) -> u32 {
    text[..byte.min(text.len())].encode_utf16().count() as u32
}

/// The names `a.b.c` ending at token [last], root first, with their tokens.
fn chain_ending(doc: &Document, last: usize) -> Vec<(String, usize)> {
    let t = &doc.tokens;
    let mut parts = vec![(t.text(last), last)];
    let mut at = last;
    while let Some(dot) = t.prev_code(at).filter(|d| t.is_op(*d, ".")) {
        match t.prev_code(dot).filter(|n| t.kind(*n) == TokenKind::Name) {
            Some(n) => {
                parts.push((t.text(n), n));
                at = n;
            }
            None => return Vec::new(),
        }
        if parts.len() > 32 {
            return Vec::new();
        }
    }
    parts.reverse();
    parts
}

fn target_of(doc: &Document, name: &str, span: Span) -> Target {
    let a = &doc.analysis;
    if let Some(o) = a.occurrence_at(span.start) {
        if o.span == span {
            return o.target;
        }
    }
    a.resolve(name, a.scope_at(span.start))
}

/// The model path a chain means, and the symbol at its root when it is the file's own.
fn resolve_chain(doc: &Document, chain: &[(String, usize)]) -> (Option<String>, Option<usize>) {
    let Some((root, token)) = chain.first() else { return (None, None) };
    let span = doc.tokens.span(*token);
    let a = &doc.analysis;
    let base = match target_of(doc, root, span) {
        Target::Api => root.clone(),
        Target::Builtin => root.clone(),
        Target::Symbol(id) => {
            let s = &a.symbols[id];
            match &s.import {
                Some((module, member)) => api::import_path(module, member.as_deref()),
                None => {
                    // A value of a type the package returns: `r` after `r = http.get(...)`.
                    let types = crate::infer::symbol_types(&doc.module, a);
                    match types.get(&id) {
                        Some(ty) if chain.len() > 1 => ty.to_string(),
                        _ => return (None, Some(id)),
                    }
                }
            }
        }
        Target::Unresolved => return (None, None),
    };
    let mut path = base;
    for (name, _) in &chain[1..] {
        path = format!("{path}.{name}");
    }
    (Some(path), None)
}

/// The `(` of the call holding [caret], when the caret is inside one.
fn open_paren(doc: &Document, caret: u32) -> Option<usize> {
    let t = &doc.tokens;
    let mut k = match t.code_before(caret) {
        Some(k) => k + 1,
        None => return None,
    };
    let mut depth = 0;
    let mut steps = 0;
    while k > 0 {
        k -= 1;
        steps += 1;
        if steps > MAX_BACK_TOKENS {
            return None;
        }
        match t.kind(k) {
            TokenKind::Newline if !t.has(k, CONTINUATION) => return None,
            TokenKind::Operator => match t.text(k).as_str() {
                ")" | "]" | "}" => {
                    // A closer at the caret itself is still inside its call.
                    if t.start(k) < caret {
                        depth += 1;
                    }
                }
                "(" | "[" | "{" => {
                    if depth == 0 {
                        return t.is(k, "(").then_some(k);
                    }
                    depth -= 1;
                }
                _ => {}
            },
            _ => {}
        }
    }
    None
}

/// How the arguments before [caret] stand: the positional index, and the keyword being given.
fn argument_at(doc: &Document, open: usize, caret: u32) -> (usize, Option<String>) {
    let t = &doc.tokens;
    let mut depth = 0;
    let mut index = 0;
    let mut arg_first: Option<usize> = None;
    let mut at = t.next_code(open);
    while let Some(i) = at {
        if t.start(i) >= caret {
            break;
        }
        if t.kind(i) == TokenKind::Operator {
            match t.text(i).as_str() {
                "(" | "[" | "{" => depth += 1,
                ")" | "]" | "}" => {
                    if depth == 0 {
                        break;
                    }
                    depth -= 1;
                }
                "," if depth == 0 => {
                    index += 1;
                    arg_first = None;
                    at = t.next_code(i);
                    continue;
                }
                _ => {}
            }
        }
        if arg_first.is_none() {
            arg_first = Some(i);
        }
        at = t.next_code(i);
    }
    let keyword = arg_first.and_then(|f| {
        let eq = t.next_code(f).filter(|e| t.is_op(*e, "=") && t.start(*e) < caret)?;
        (t.kind(f) == TokenKind::Name && eq > f).then(|| t.text(f))
    });
    (index, keyword)
}

/// Which of [params] argument [index] (or [keyword]) fills.
fn active_param(params: &[SigParam], index: usize, keyword: Option<&str>) -> Option<usize> {
    if let Some(k) = keyword {
        return params.iter().position(|p| p.star == 0 && p.name == k).or_else(|| params.iter().position(|p| p.star == 2));
    }
    let mut positional = 0;
    for (i, p) in params.iter().enumerate() {
        match p.star {
            1 => return Some(i),
            2 => return None,
            _ => {
                if positional == index {
                    return Some(i);
                }
                positional += 1;
            }
        }
    }
    None
}

/// The signature of the file's own top-level function [id], with its defaults as written.
fn local_signature(doc: &Document, id: usize) -> Option<(String, String)> {
    let s = &doc.analysis.symbols[id];
    if s.kind != SymbolKind::Function {
        return None;
    }
    let mut found = None;
    walk_stmts(&doc.module.body, &mut |stmt| {
        if let StmtKind::Def { name_span, params, body, .. } = &stmt.kind {
            if *name_span == s.declaration && found.is_none() {
                found = Some((params.clone(), docstring(body)));
            }
        }
    });
    let (params, doc_text) = found?;
    let units = doc.units();
    let mut parts: Vec<String> = params
        .params
        .iter()
        .map(|p| match &p.default {
            Some(d) => format!("{}={}", p.name, slice(units, d.span)),
            None => p.name.clone(),
        })
        .collect();
    if let Some(v) = &params.vararg {
        parts.push(format!("*{}", v.name));
    }
    Some((format!("{}({})", s.name, parts.join(", ")), doc_text))
}

/// The first paragraph of the docstring that opens [body].
fn docstring(body: &[Stmt]) -> String {
    match body.first().map(|s| &s.kind) {
        Some(StmtKind::Expr(Expr { kind: ExprKind::Str { value: Some(v), fstring: false, .. }, .. })) => {
            v.split("\n\n").next().unwrap_or("").split_whitespace().collect::<Vec<_>>().join(" ")
        }
        _ => String::new(),
    }
}

/// A short prefix to show before an API signature: `http.` for `wm5.http.get`.
fn shown_parent(e: &ApiEntry) -> String {
    let parent = keyboard::short(e.parent());
    if parent.is_empty() || parent == crate::package::ROOT || e.kind == ApiKind::Builtin || e.kind == ApiKind::Method {
        return String::new();
    }
    // A method of a returned object reads better on its value: `response.json()`.
    let last = parent.rsplit('.').next().unwrap_or(parent);
    if last.chars().next().is_some_and(|c| c.is_ascii_uppercase()) {
        return format!("{}.", last.to_lowercase());
    }
    format!("{parent}.")
}

pub fn signature_at(doc: &Document, caret: u32, host: &Host) -> Option<Signature> {
    if caret > doc.len() {
        return None;
    }
    let t = &doc.tokens;
    if let Some(i) = t.index_at(caret.saturating_sub(1)) {
        if t.kind(i) == TokenKind::Comment && caret > t.start(i) {
            return None;
        }
    }
    let open = open_paren(doc, caret)?;
    let callee = t.prev_code(open).filter(|n| t.kind(*n) == TokenKind::Name)?;
    let chain = chain_ending(doc, callee);
    if chain.is_empty() {
        return None;
    }
    let (path, local) = resolve_chain(doc, &chain);
    let (label, doc_text, path) = if let Some(id) = local {
        let (sig, d) = local_signature(doc, id)?;
        (sig, d, None)
    } else {
        let path = path?;
        let e = api::model().find(&path)?;
        let sig = e.signature?;
        (format!("{}{sig}", shown_parent(e)), e.doc.to_string(), Some(path))
    };
    let params = keyboard::sig_params(&label);
    let (index, keyword) = argument_at(doc, open, caret);
    let active = active_param(&params, index, keyword.as_deref());
    let mut values: Vec<String> = Vec::new();
    let mut param_doc = None;
    if let (Some(a), Some(path)) = (active, path.as_deref()) {
        let name = &params[a].name;
        if let Some(v) = keyboard::values_for(path, name) {
            values = v.iter().map(|s| s.to_string()).collect();
            if !keyboard::is_open_set(v) {
                param_doc = Some(format!("One of: {}", v.join(", ")));
            } else {
                param_doc = Some("A Material Symbols name, such as \"search\".".to_string());
            }
        }
        match (keyboard::short(path), name.as_str()) {
            ("plugin.provider", "tool") => {
                values = keyboard::provider_tool_names().map(str::to_string).collect();
                param_doc = Some(format!("The provider list: {}", values.join(", ")));
            }
            ("plugin.provider", "id") => {
                let ids: Vec<String> = host.providers.iter().flatten().map(|(_, id)| id.clone()).collect();
                param_doc = Some(if ids.is_empty() { "The id from plugin.json's providers; the tool's name when left out.".into() } else { format!("plugin.json's providers: {}", ids.join(", ")) });
            }
            ("plugin.action", "id") => {
                let ids = host.actions.clone().unwrap_or_default();
                param_doc = Some(if ids.is_empty() { "The id plugin.json's actions list.".into() } else { format!("plugin.json's actions: {}", ids.join(", ")) });
            }
            ("plugin.setting_changed", "key") | ("plugin.settings.get", "name") | ("plugin.settings.set", "key") | ("plugin.settings.reset", "key") => {
                let keys: Vec<String> = host.settings.iter().flatten().map(|s| s.key.clone()).collect();
                if !keys.is_empty() {
                    param_doc = Some(format!("plugin.json's settings: {}", keys.join(", ")));
                }
            }
            (p, "url") if p.starts_with("http.") => {
                let hosts = host.hosts.clone().unwrap_or_default();
                param_doc = Some(if hosts.is_empty() { "An https address on one of plugin.json's hosts.".into() } else { format!("An https address on: {}", hosts.join(", ")) });
            }
            (_, n) if param_doc.is_none() && keyboard::is_callback(n) => {
                param_doc = Some("A function the keyboard calls later; name it without brackets.".into());
            }
            _ => {}
        }
    }
    let spans = params.iter().map(|p| (utf16(&label, p.start), utf16(&label, p.end))).collect();
    let names = params.iter().map(|p| p.name.clone()).collect();
    Some(Signature { label, params: spans, names, active, doc: doc_text, param_doc, values, path })
}

// ---- hover -------------------------------------------------------------------

fn entry_title(e: &ApiEntry) -> String {
    match e.signature {
        Some(sig) => format!("{}{sig}", shown_parent(e)),
        None => keyboard::short(e.path).to_string(),
    }
}

/// The provider function [name] answers, when it is decorated as one.
fn provider_of(doc: &Document, def_name_span: Span) -> Option<(String, String)> {
    keyboard::decorated(&doc.module, &doc.analysis)
        .into_iter()
        .find(|d| d.what == "plugin.provider" && d.def_name_span == def_name_span)
        .and_then(|d| d.provider().map(|(tool, id, _)| (tool, id)))
}

pub fn hover_at(doc: &Document, offset: u32, host: &Host) -> Option<Hover> {
    let t = &doc.tokens;
    let i = t.index_at(offset).filter(|i| t.kind(*i) == TokenKind::Name || t.kind(*i) == TokenKind::String)?;
    let span = t.span(i);
    if t.kind(i) == TokenKind::String {
        return hover_string(doc, i, host);
    }
    let chain = chain_ending(doc, i);
    if chain.is_empty() {
        return None;
    }
    // `plugin.settings.units`: a setting from the manifest.
    if chain.len() >= 2 {
        let (base, _) = resolve_chain(doc, &chain[..chain.len() - 1]);
        if base.as_deref().map(keyboard::short) == Some("plugin.settings") {
            let key = &chain[chain.len() - 1].0;
            if let Some(s) = host.setting(key) {
                return Some(Hover { span, title: format!("setting {key}"), doc: setting_doc(s), kind: "setting".into(), path: None });
            }
        }
    }
    let (path, local) = resolve_chain(doc, &chain);
    if let Some(path) = path {
        let e = api::model().find(&path)?;
        let doc_text = match e.permission {
            Some(p) => format!("{} Needs the {p} permission.", e.doc),
            None => e.doc.to_string(),
        };
        return Some(Hover { span, title: entry_title(e), doc: doc_text, kind: e.kind.name().into(), path: Some(path) });
    }
    let id = local?;
    let a = &doc.analysis;
    let s = &a.symbols[id];
    match s.kind {
        SymbolKind::Function => {
            let (sig, d) = local_signature(doc, id).unwrap_or_else(|| (format!("{}()", s.name), String::new()));
            let line = doc.lines.line_of(s.declaration.start) + 1;
            let mut text = if d.is_empty() { format!("Defined on line {line}.") } else { d };
            if let Some((tool, pid)) = provider_of(doc, s.declaration) {
                let label = keyboard::provider_tool(&tool).map(|t| t.label).unwrap_or(tool.as_str());
                text = format!("Answers the {label} list as “{pid}”. {text}");
            }
            Some(Hover { span, title: format!("def {sig}"), doc: text, kind: "function".into(), path: None })
        }
        SymbolKind::Parameter => {
            // The request a provider function gets.
            let f = a.functions.iter().find(|f| f.params.iter().any(|p| p.span == s.declaration));
            if let Some(f) = f {
                if let Some((tool, _)) = provider_of(doc, f.name_span) {
                    if let Some(tool) = keyboard::provider_tool(&tool) {
                        let keys: Vec<String> = tool.request.iter().map(|(k, d)| format!("{k}: {d}")).collect();
                        return Some(Hover {
                            span,
                            title: format!("{}: the {} request", s.name, tool.label),
                            doc: format!("{}. Return {}.", keys.join("; "), tool.reply),
                            kind: "parameter".into(),
                            path: None,
                        });
                    }
                }
            }
            Some(Hover { span, title: format!("parameter {}", s.name), doc: String::new(), kind: "parameter".into(), path: None })
        }
        _ => {
            let types = crate::infer::symbol_types(&doc.module, a);
            let ty = types.get(&id).map(|t| keyboard::short(t).to_string());
            let line = doc.lines.line_of(s.declaration.start) + 1;
            let title = match &ty {
                Some(ty) => format!("{}: {ty}", s.name),
                None => s.name.clone(),
            };
            Some(Hover { span, title, doc: format!("{} first set on line {line}.", s.kind.name()), kind: "variable".into(), path: None })
        }
    }
}

fn setting_doc(s: &crate::document::SettingSpec) -> String {
    let mut text = if s.label.is_empty() { format!("A {} setting.", s.kind) } else { format!("{} ({})", s.label, s.kind) };
    if !s.options.is_empty() {
        text.push_str(&format!(". Options: {}", s.options.join(", ")));
    }
    text
}

/// A string that names something: a provider list in `@plugin.provider("search")`, a setting key.
fn hover_string(doc: &Document, i: usize, host: &Host) -> Option<Hover> {
    let t = &doc.tokens;
    let value = string_value(t, i)?;
    let span = t.span(i);
    let open = t.prev_code(i).filter(|o| t.is_op(*o, "("))?;
    let callee = t.prev_code(open).filter(|n| t.kind(*n) == TokenKind::Name)?;
    let (path, _) = resolve_chain(doc, &chain_ending(doc, callee));
    match path.as_deref().map(keyboard::short) {
        Some("plugin.provider") => {
            let tool = keyboard::provider_tool(&value)?;
            let keys: Vec<&str> = tool.request.iter().map(|(k, _)| *k).collect();
            Some(Hover {
                span,
                title: format!("{} provider list", tool.label),
                doc: format!("The request holds {}. Return {}.", keys.join(", "), tool.reply),
                kind: "provider".into(),
                path: None,
            })
        }
        Some("plugin.setting_changed") | Some("plugin.settings.get") => {
            let s = host.setting(&value)?;
            Some(Hover { span, title: format!("setting {value}"), doc: setting_doc(s), kind: "setting".into(), path: None })
        }
        _ => None,
    }
}
