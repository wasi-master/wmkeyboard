//! A small, honest guess at the type of a value: only literals and calls whose
//! result is obvious. Anything else is unknown, and the callers then offer the
//! methods of every common type rather than the wrong ones.

use std::collections::HashMap;

use crate::analysis::{Analysis, SymbolKind, Target};
use crate::ast::*;
use crate::text::Span;

const STR_RESULTS: &[&str] = &[
    "upper", "lower", "casefold", "title", "capitalize", "swapcase", "strip", "lstrip", "rstrip", "replace", "join",
    "zfill", "ljust", "rjust", "center", "removeprefix", "removesuffix", "format", "hex", "decode",
];
const LIST_RESULTS: &[&str] = &["split", "rsplit", "splitlines", "keys", "values", "items", "findall", "finditer"];

/// The type of an expression, when it is plain to see.
pub fn type_of(e: &Expr) -> Option<&'static str> {
    match &e.kind {
        ExprKind::Str { .. } => Some("str"),
        ExprKind::List(_) => Some("list"),
        ExprKind::Dict(_) => Some("dict"),
        ExprKind::Set(_) => Some("set"),
        ExprKind::Tuple(_) => Some("tuple"),
        ExprKind::Comp { kind, .. } => Some(match kind {
            CompKind::Dict => "dict",
            CompKind::Set => "set",
            _ => "list",
        }),
        ExprKind::Binary { op: "+", left, right } | ExprKind::Binary { op: "%", left, right } => {
            let l = type_of(left);
            if l == Some("str") || type_of(right) == Some("str") {
                Some("str")
            } else if l == Some("list") {
                Some("list")
            } else {
                None
            }
        }
        ExprKind::Call { func, .. } => match &func.kind {
            ExprKind::Name(n) => match n.as_str() {
                "str" | "repr" | "chr" | "hex" | "bin" | "oct" | "format" => Some("str"),
                "list" | "sorted" | "reversed" | "range" | "map" | "filter" | "enumerate" | "zip" => Some("list"),
                "dict" => Some("dict"),
                "set" => Some("set"),
                "tuple" | "divmod" => Some("tuple"),
                "bytes" => Some("bytes"),
                _ => None,
            },
            ExprKind::Attribute { value, attr, .. } => {
                let base = match &value.kind {
                    ExprKind::Name(n) => Some(n.as_str()),
                    _ => None,
                };
                match (base, attr.as_str()) {
                    (Some("json"), "dumps") | (Some("time"), "format") | (Some("re"), "sub") | (Some("re"), "escape") => Some("str"),
                    (Some("re"), "split") | (Some("re"), "findall") => Some("list"),
                    (Some("time"), "parts") => Some("dict"),
                    (Some("re"), "search") | (Some("re"), "match") | (Some("re"), "fullmatch") => Some("Match"),
                    (Some("bytes"), "fromhex") => Some("bytes"),
                    (_, "encode") => Some("bytes"),
                    (_, a) if STR_RESULTS.contains(&a) && type_of(value) != Some("dict") => Some("str"),
                    (_, a) if LIST_RESULTS.contains(&a) => Some("list"),
                    (_, "copy") => type_of(value),
                    _ => None,
                }
            }
            _ => None,
        },
        _ => None,
    }
}

/// For each variable bound by `name = value` exactly once, the type of that value, by symbol.
pub fn symbol_types(module: &Module, analysis: &Analysis) -> HashMap<usize, &'static str> {
    let mut by_span: HashMap<Span, &'static str> = HashMap::new();
    walk_stmts(&module.body, &mut |stmt| {
        if let StmtKind::Assign { targets, value } = &stmt.kind {
            if let Some(ty) = type_of(value).or_else(|| package_type(analysis, value)) {
                for t in targets {
                    if let ExprKind::Name(_) = t.kind {
                        by_span.insert(t.span, ty);
                    }
                }
            }
        }
    });
    let mut out = HashMap::new();
    for s in &analysis.symbols {
        if s.kind != SymbolKind::Variable && s.kind != SymbolKind::LoopVariable {
            continue;
        }
        let Some(first) = by_span.get(&s.declaration) else { continue };
        let consistent = s.writes.iter().all(|w| by_span.get(w) == Some(first));
        if consistent {
            out.insert(s.id, *first);
        }
    }
    out
}

/// The type of the name read at [span], when its symbol has one.
pub fn type_at(analysis: &Analysis, types: &HashMap<usize, &'static str>, span: Span) -> Option<&'static str> {
    match analysis.occurrence_at(span.start)?.target {
        Target::Symbol(id) => types.get(&id).copied(),
        _ => None,
    }
}

/// The type a call into the `wm` package returns, as a model path:
/// `wm5.http.Response` for `http.get(url)`.
pub fn package_type(analysis: &Analysis, e: &Expr) -> Option<&'static str> {
    let ExprKind::Call { func, keywords, .. } = &e.kind else { return None };
    // A call given then= answers later and returns nothing now.
    if keywords.iter().any(|k| k.name == "then") {
        return None;
    }
    if let ExprKind::Attribute { value, attr, .. } = &func.kind {
        if attr == "raise_for_status" && package_type(analysis, value) == Some(RESPONSE) {
            return Some(RESPONSE);
        }
    }
    let path = crate::keyboard::api_path(analysis, func)?;
    Some(match crate::keyboard::short(&path) {
        "http.get" | "http.post" | "http.put" | "http.patch" | "http.delete" | "http.request" => RESPONSE,
        "plugin.state" => "wm5.plugin.State",
        "location.get" => "dict",
        "sensors.available" | "reminders.list" => "list",
        _ => return None,
    })
}

pub const RESPONSE: &str = "wm5.http.Response";
