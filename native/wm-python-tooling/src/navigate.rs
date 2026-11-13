//! From a name to the places that mean it, renaming them together, the
//! outline, fold regions, and the API entry under the caret.

use crate::analysis::{Analysis, ScopeKind, SymbolKind, Target};
use crate::api::{self, ApiEntry, ApiKind};
use crate::ast::*;
use crate::document::Document;
use crate::infer;
use crate::lexer::{is_keyword_str, TokenKind, Tokens, CONTINUATION};
use crate::structure::Structure;
use crate::text::{is_identifier, slice, Span};

/// Where the name at [caret] is first bound.
pub fn definition(doc: &Document, caret: u32) -> Option<Span> {
    doc.analysis.symbol_at(caret).map(|s| s.declaration)
}

/// Every place the name at [caret] means the same thing, its binding included, in order.
pub fn references(doc: &Document, caret: u32) -> Vec<Span> {
    match doc.analysis.occurrence_at(caret) {
        Some(o) => match o.target {
            Target::Symbol(id) => doc.analysis.symbols[id].references(),
            _ => Vec::new(),
        },
        None => Vec::new(),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RenameProblem {
    NotAName,
    Keyword,
    /// The name is a builtin, or the new name would hide one this file uses.
    Builtin,
    /// Another name of that spelling is seen where a use sits, or would be captured.
    Taken,
    /// `wm`, `ui`, or a module imported by its own name.
    Api,
    /// `render`, `on_event` or `transform`, which the keyboard calls by name.
    Hook,
}

impl RenameProblem {
    pub fn name(self) -> &'static str {
        match self {
            RenameProblem::NotAName => "not_a_name",
            RenameProblem::Keyword => "keyword",
            RenameProblem::Builtin => "builtin",
            RenameProblem::Taken => "taken",
            RenameProblem::Api => "api",
            RenameProblem::Hook => "hook",
        }
    }
}

#[derive(Clone, Debug)]
pub struct RenamePlan {
    pub spans: Vec<Span>,
    pub problem: Option<RenameProblem>,
}

/// What renaming the name at [caret] to [new_name] would rewrite, and why it
/// cannot, if it cannot. None when there is no name there to rename. An empty
/// [new_name] asks only for the plan.
pub fn rename(doc: &Document, caret: u32, new_name: &str) -> Option<RenamePlan> {
    let a = &doc.analysis;
    let units = doc.units();
    let o = a.occurrence_at(caret)?;
    let old = slice(units, o.span);
    let same_name = |target: Target| -> Vec<Span> {
        a.occurrences.iter().filter(|x| x.target == target && slice(units, x.span) == old).map(|x| x.span).collect()
    };
    let (spans, symbol) = match o.target {
        Target::Symbol(id) => (a.symbols[id].references(), Some(&a.symbols[id])),
        Target::Api => return Some(RenamePlan { spans: same_name(Target::Api), problem: Some(RenameProblem::Api) }),
        Target::Builtin => return Some(RenamePlan { spans: same_name(Target::Builtin), problem: Some(RenameProblem::Builtin) }),
        Target::Unresolved => return None,
    };
    let symbol = symbol.unwrap();
    if symbol.scope == 0 && api::CONTRACT.contains(&symbol.name.as_str()) && symbol.kind == SymbolKind::Function {
        return Some(RenamePlan { spans, problem: Some(RenameProblem::Hook) });
    }
    if let Some((module, None)) = &symbol.import {
        if *module == symbol.name {
            return Some(RenamePlan { spans, problem: Some(RenameProblem::Api) });
        }
    }
    if new_name.is_empty() || new_name == symbol.name {
        return Some(RenamePlan { spans, problem: None });
    }
    let problem = rename_problem(doc, a, symbol.id, &spans, new_name);
    Some(RenamePlan { spans, problem })
}

fn rename_problem(doc: &Document, a: &Analysis, id: usize, spans: &[Span], new_name: &str) -> Option<RenameProblem> {
    if !is_identifier(new_name) {
        return Some(RenameProblem::NotAName);
    }
    if is_keyword_str(new_name) {
        return Some(RenameProblem::Keyword);
    }
    if api::API_GLOBALS.contains(&new_name) {
        return Some(RenameProblem::Api);
    }
    let symbol = &a.symbols[id];
    // At every use, the new name must not already mean something else.
    for span in spans {
        let scope = a.occurrences.iter().find(|o| o.span == *span).map_or(0, |o| o.scope);
        match a.resolve(new_name, scope) {
            Target::Symbol(other) if other != id => return Some(RenameProblem::Taken),
            Target::Builtin if a.occurrences.iter().any(|o| o.target == Target::Builtin && slice(doc.units(), o.span) == new_name) => {
                return Some(RenameProblem::Builtin)
            }
            _ => {}
        }
    }
    // No use of the new name inside the symbol's reach may become this symbol.
    for o in &a.occurrences {
        if slice(doc.units(), o.span) != new_name {
            continue;
        }
        if let Target::Symbol(other) = o.target {
            if other == id {
                continue;
            }
        }
        if within(a, o.scope, symbol.scope, new_name) {
            return Some(match o.target {
                Target::Builtin => RenameProblem::Builtin,
                _ => RenameProblem::Taken,
            });
        }
    }
    None
}

/// Whether a name read in [scope] would resolve through [home] once [home] binds it.
fn within(a: &Analysis, scope: usize, home: usize, name: &str) -> bool {
    let mut at = Some(scope);
    while let Some(s) = at {
        if s == home {
            return true;
        }
        if a.scopes[s].names.contains_key(name) || a.scopes[s].globals.contains(name) {
            return false;
        }
        at = a.scopes[s].parent;
    }
    false
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OutlineItem {
    pub name: String,
    /// `function` or `variable`.
    pub kind: &'static str,
    pub depth: usize,
    pub span: Span,
    pub name_span: Span,
}

/// Named functions with their nesting, and the variables assigned at the top
/// of the file, in document order.
pub fn outline(doc: &Document) -> Vec<OutlineItem> {
    let a = &doc.analysis;
    let mut out = Vec::new();
    for f in &a.functions {
        if f.lambda {
            continue;
        }
        let Some(name) = &f.name else { continue };
        out.push(OutlineItem { name: name.clone(), kind: "function", depth: a.depth_of(f.id), span: f.span, name_span: f.name_span });
    }
    for s in &a.symbols {
        if s.scope != 0 || s.kind != SymbolKind::Variable {
            continue;
        }
        let span = doc
            .module
            .body
            .iter()
            .find(|st| st.span.contains(s.declaration.start))
            .map_or(s.declaration, |st| st.span);
        out.push(OutlineItem { name: s.name.clone(), kind: "variable", depth: 0, span, name_span: s.declaration });
    }
    out.sort_by_key(|i| (i.span.start, i.name_span.start));
    out
}

/// The stretches the editor can fold, each from its start to the end of its
/// last token: indented blocks from their header, brackets and strings that
/// run over several lines, and runs of comment lines. From tokens alone, so
/// they are there while the file is broken.
pub fn folds_from_tokens(t: &Tokens) -> Vec<Span> {
    let s = Structure::of(t);
    let mut out = Vec::new();
    for k in 0..s.lines.len() {
        if !s.is_header(t, k) {
            continue;
        }
        let indent = s.lines[k].indent;
        let mut last = k;
        while last + 1 < s.lines.len() && s.lines[last + 1].indent > indent {
            last += 1;
        }
        if last > k {
            out.push(Span::new(t.start(s.first(k)), t.end(s.last(last))));
        }
    }
    shared_folds(t, &s, &mut out);
    finish_folds(t, out)
}

/// [folds_from_tokens], with the blocks taken from the parsed tree instead of
/// from indentation. The two agree on a file that parses.
pub fn folds_from_tree(doc: &Document) -> Vec<Span> {
    let t = &doc.tokens;
    let s = &doc.structure;
    let mut out = Vec::new();
    walk_stmts(&doc.module.body, &mut |stmt| {
        let mut blocks: Vec<&Vec<Stmt>> = stmt_blocks(stmt);
        if let StmtKind::If { orelse, .. } = &stmt.kind {
            // An elif is its own statement, folded on its own.
            if orelse.len() == 1 && matches!(orelse[0].kind, StmtKind::If { elif: true, .. }) {
                blocks.retain(|b| !std::ptr::eq(*b, orelse));
            }
        }
        for block in blocks {
            let (Some(first), Some(last)) = (block.first(), block.last()) else { continue };
            let Some(token) = t.index_at(first.span.start) else { continue };
            let Some(line) = s.line_of_token(token) else { continue };
            if line == 0 {
                continue;
            }
            let header = line - 1;
            // An inline body (`if x: y`) shares its header's line and folds nothing.
            if s.first(line) != token {
                continue;
            }
            out.push(Span::new(t.start(s.first(header)), last.span.end));
        }
    });
    shared_folds(t, s, &mut out);
    finish_folds(t, out)
}

fn shared_folds(t: &Tokens, s: &Structure, out: &mut Vec<Span>) {
    for (open, close) in s.pairs.iter().enumerate() {
        if *close > open as i32 {
            out.push(Span::new(t.start(open), t.end(*close as usize)));
        }
    }
    let mut run: Option<(u32, u32, usize)> = None;
    for i in 0..t.len() {
        match t.kind(i) {
            TokenKind::String => out.push(t.span(i)),
            TokenKind::Comment => {
                let alone = (0..i).rev().take_while(|k| t.kind(*k) != TokenKind::Newline).all(|k| t.kind(k) == TokenKind::Whitespace);
                if !alone {
                    continue;
                }
                run = match run {
                    Some((start, _, count)) => Some((start, t.end(i), count + 1)),
                    None => Some((t.start(i), t.end(i), 1)),
                };
            }
            TokenKind::Newline => {
                // A comment run ends at a blank line or a line of code.
                let next_is_comment = (i + 1..t.len()).find(|k| t.kind(*k) != TokenKind::Whitespace).is_some_and(|k| t.kind(k) == TokenKind::Comment);
                if !next_is_comment {
                    if let Some((start, end, count)) = run.take() {
                        if count >= 2 {
                            out.push(Span::new(start, end));
                        }
                    }
                }
            }
            TokenKind::Whitespace => {}
            _ => run = None,
        }
    }
    if let Some((start, end, count)) = run {
        if count >= 2 {
            out.push(Span::new(start, end));
        }
    }
}

fn finish_folds(t: &Tokens, mut out: Vec<Span>) -> Vec<Span> {
    let units = t.units();
    out.retain(|span| units[span.start as usize..span.end as usize].iter().any(|c| *c == b'\n' as u16 || *c == b'\r' as u16));
    out.sort_by(|a, b| a.start.cmp(&b.start).then(b.end.cmp(&a.end)));
    out.dedup();
    out
}

/// The API entry at [caret]: the name it is on (`wm.storage.get`, `math.sqrt`,
/// `len`, a method like `s.upper`), or else the API function whose call the
/// caret is inside.
pub fn api_at(doc: &Document, caret: u32) -> Option<&'static ApiEntry> {
    let t = &doc.tokens;
    if let Some(name) = name_at(t, caret) {
        if let Some(e) = entry_ending_at(doc, name) {
            return Some(e);
        }
    }
    enclosing_call(doc, caret)
}

fn name_at(t: &Tokens, caret: u32) -> Option<usize> {
    if let Some(i) = t.index_at(caret) {
        if t.kind(i) == TokenKind::Name && t.start(i) <= caret {
            return Some(i);
        }
    }
    let i = t.index_at(caret.checked_sub(1)?)?;
    (t.kind(i) == TokenKind::Name).then_some(i)
}

fn entry_ending_at(doc: &Document, last: usize) -> Option<&'static ApiEntry> {
    let t = &doc.tokens;
    let a = &doc.analysis;
    let model = api::model();
    let mut parts = vec![last];
    let mut at = last;
    while let Some(dot) = t.prev_code(at).filter(|d| t.is_op(*d, ".")) {
        let Some(prev) = t.prev_code(dot) else { break };
        if t.kind(prev) != TokenKind::Name {
            // `"x".upper`, `(a).b`: a method on something that is not a name.
            let ty = match t.kind(prev) {
                TokenKind::String => Some("str"),
                _ => None,
            };
            return if parts.len() == 1 { api::method(ty, &t.text(last)) } else { None };
        }
        parts.push(prev);
        at = prev;
        if parts.len() > 32 {
            return None;
        }
    }
    parts.reverse();
    let root = parts[0];
    let root_name = t.text(root);
    let target = match a.occurrence_at(t.start(root)) {
        Some(o) if o.span == t.span(root) => o.target,
        _ => a.resolve(&root_name, a.scope_at(t.start(root))),
    };
    let base = match target {
        Target::Api => Some(root_name.clone()),
        Target::Builtin if parts.len() == 1 => return model.find(&root_name),
        Target::Builtin if root_name == "bytes" => Some("bytes".to_string()),
        Target::Symbol(id) => a.symbols[id].import.as_ref().map(|(module, member)| api::import_path(module, member.as_deref())),
        _ => None,
    };
    if let Some(base) = base {
        let mut path = base;
        for p in &parts[1..] {
            path = format!("{path}.{}", t.text(*p));
        }
        return model.find(&path);
    }
    if parts.len() == 2 {
        let types = infer::symbol_types(&doc.module, a);
        let ty = infer::type_at(a, &types, t.span(root));
        return api::method(ty, &t.text(last));
    }
    None
}

fn enclosing_call(doc: &Document, caret: u32) -> Option<&'static ApiEntry> {
    let t = &doc.tokens;
    let mut k = t.code_before(caret)? + 1;
    let mut depth = 0;
    let mut steps = 0;
    while k > 0 {
        k -= 1;
        steps += 1;
        if steps > 3000 {
            return None;
        }
        match t.kind(k) {
            TokenKind::Newline if !t.has(k, CONTINUATION) => return None,
            TokenKind::Operator => match t.text(k).as_str() {
                ")" | "]" | "}" => depth += 1,
                "(" | "[" | "{" => {
                    if depth > 0 {
                        depth -= 1;
                        continue;
                    }
                    if !t.is(k, "(") {
                        return None;
                    }
                    let callee = t.prev_code(k).filter(|c| t.kind(*c) == TokenKind::Name)?;
                    return entry_ending_at(doc, callee).filter(|e| {
                        matches!(e.kind, ApiKind::Function | ApiKind::Builtin | ApiKind::Method | ApiKind::Exception)
                    });
                }
                _ => {}
            },
            _ => {}
        }
    }
    None
}

/// Whether [scope] is a function's own scope (for callers that care).
pub fn is_function_scope(a: &Analysis, scope: usize) -> bool {
    matches!(a.scopes[scope].kind, ScopeKind::Function | ScopeKind::Lambda)
}
