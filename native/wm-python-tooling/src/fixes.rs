//! The one obvious change for a diagnostic, where there is one: the name a
//! typo was meant to be, the import a module needs, the permission, host,
//! provider, action, keyword or setting plugin.json is missing, or a stub
//! for a provider plugin.json declares and the script does not answer.
//!
//! The editors draw a Fix button from it. A text change they apply to the
//! script; a manifest change they apply to plugin.json.

use crate::ast::*;
use crate::diagnostics::{Code, Diagnostic, Fix};
use crate::document::Document;
use crate::keyboard;
use crate::text::slice;

pub fn attach(doc: &Document, out: &mut [Diagnostic]) {
    for d in out.iter_mut() {
        if d.fix.is_none() {
            d.fix = fix_for(doc, d);
        }
    }
}

fn arg(d: &Diagnostic, i: usize) -> String {
    d.args.get(i).cloned().unwrap_or_default()
}

/// [near], in the quotes the span is written in when it is a string.
fn like_span(doc: &Document, d: &Diagnostic, near: &str) -> String {
    let text = slice(doc.units(), d.span);
    let quote = text.chars().find(|c| *c == '"' || *c == '\'');
    match quote {
        Some(q) if text.ends_with(q) => {
            let prefix: String = text.chars().take_while(|c| *c != q).collect();
            format!("{prefix}{q}{near}{q}")
        }
        _ => near.to_string(),
    }
}

fn fix_for(doc: &Document, d: &Diagnostic) -> Option<Fix> {
    Some(match d.code {
        Code::NameTypo | Code::UiArgumentTypo | Code::ArgumentTypo => Fix::Replace(arg(d, 1)),
        Code::MemberTypo => Fix::Replace(arg(d, 1).rsplit('.').next().unwrap_or("").to_string()),
        Code::EventTypeTypo | Code::StyleTypo | Code::ActionTypo | Code::ProviderToolTypo | Code::ValueTypo | Code::SettingTypo => {
            Fix::Replace(like_span(doc, d, &arg(d, 1)))
        }
        Code::ModuleNotImported => {
            let at = import_offset(doc);
            Fix::Insert(at.0, format!("{}import {}\n", at.1, arg(d, 0)))
        }
        Code::WmNotImported => wm_import(doc, &arg(d, 0)),
        Code::StorageNotDeclared => Fix::Permission("storage".into()),
        Code::RemindersNotDeclared => Fix::Permission("reminders".into()),
        Code::SelectionNotDeclared => Fix::Permission("selection".into()),
        Code::PermissionMissing => Fix::Permission(arg(d, 1)),
        Code::HostMissing => Fix::Host(arg(d, 0)),
        Code::ProviderNotDeclared => Fix::Provider(arg(d, 0), arg(d, 1)),
        Code::UnknownAction => Fix::Action(arg(d, 0)),
        Code::KeywordNotDeclared => Fix::Keyword(arg(d, 0)),
        Code::UnknownSetting => Fix::Setting(arg(d, 0)),
        Code::ProviderUnanswered => {
            let plugin = keyboard::local_name(&doc.analysis, "plugin").unwrap_or_else(|| "plugin".into());
            let stub = keyboard::provider_stub(&plugin, &arg(d, 0), &arg(d, 1), "");
            append(doc, &stub)
        }
        Code::ActionUnanswered => {
            let plugin = keyboard::local_name(&doc.analysis, "plugin").unwrap_or_else(|| "plugin".into());
            let id = arg(d, 0);
            let name = keyboard::stub_name(&id, &id);
            append(doc, &format!("@{plugin}.action(\"{id}\")\ndef {name}(text):\n    return text"))
        }
        _ => return None,
    })
}

/// [text] as new top-level code at the end of the file, two blank lines before it.
fn append(doc: &Document, text: &str) -> Fix {
    let units = doc.units();
    let mut end = units.len();
    while end > 0 && matches!(units[end - 1], 0x20 | 0x09 | 0x0A | 0x0D) {
        end -= 1;
    }
    // Inserted after the last code and before the file's own last newline.
    if end == 0 {
        return Fix::Insert(0, format!("{text}\n"));
    }
    Fix::Insert(end as u32, format!("\n\n\n{text}"))
}

/// Where an import goes: after the file's docstring and its other imports,
/// and the newline to write first when that is the end of a file without one.
fn import_offset(doc: &Document) -> (u32, &'static str) {
    let body = &doc.module.body;
    let mut last = None;
    for (i, stmt) in body.iter().enumerate() {
        match &stmt.kind {
            StmtKind::Expr(Expr { kind: ExprKind::Str { .. }, .. }) if i == 0 => last = Some(stmt.span.end),
            StmtKind::Import(_) | StmtKind::ImportFrom { .. } => last = Some(stmt.span.end),
            _ => break,
        }
    }
    let Some(end) = last else { return (0, "") };
    let line = doc.lines.line_of(end);
    if line + 1 < doc.lines.count() {
        (doc.lines.start(line + 1), "")
    } else {
        (doc.len(), "\n")
    }
}

/// `, name` at the end of the file's `from wm import ...`, or a new line for one.
fn wm_import(doc: &Document, name: &str) -> Fix {
    for stmt in &doc.module.body {
        if let StmtKind::ImportFrom { module, names, star: false, .. } = &stmt.kind {
            if module == "wm" {
                if let Some(last) = names.last() {
                    let end = last.asname.as_ref().map(|(_, s)| s.end).unwrap_or(last.span.end);
                    return Fix::Insert(end, format!(", {name}"));
                }
            }
        }
    }
    let (at, lead) = import_offset(doc);
    Fix::Insert(at, format!("{lead}from wm import {name}\n"))
}
