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
                "ascii" => Some("str"),
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
                    (Some("re"), "compile") => Some("Pattern"),
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

/// For each variable bound by `name = value` (or `with value as name:`)
/// exactly once, the type of that value, by symbol.
pub fn symbol_types(module: &Module, analysis: &Analysis) -> HashMap<usize, &'static str> {
    let first = types_with(module, analysis, &HashMap::new());
    // Once more, for values made by a typed one: `r = s.get(url)` after `s = requests.Session()`.
    if first.values().any(|t| matches!(*t, SESSION | CLIENT)) {
        return types_with(module, analysis, &first);
    }
    first
}

fn types_with(module: &Module, analysis: &Analysis, known: &HashMap<usize, &'static str>) -> HashMap<usize, &'static str> {
    let mut by_span: HashMap<Span, &'static str> = HashMap::new();
    let of = |value: &Expr| type_of(value).or_else(|| package_type(analysis, value)).or_else(|| method_type(analysis, known, value));
    walk_stmts(&module.body, &mut |stmt| match &stmt.kind {
        StmtKind::Assign { targets, value } => {
            if let Some(ty) = of(value) {
                for t in targets {
                    if let ExprKind::Name(_) = t.kind {
                        by_span.insert(t.span, ty);
                    }
                }
            }
        }
        // `with requests.Session() as s:`
        StmtKind::Unsupported { what: "with", exprs, binds, .. } if exprs.len() == 1 && binds.len() == 1 => {
            if let (Some(ty), ExprKind::Name(_)) = (of(&exprs[0]), &binds[0].kind) {
                by_span.insert(binds[0].span, ty);
            }
        }
        _ => {}
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

/// The type a method of a typed variable returns: a Response for `s.get(url)`.
fn method_type(analysis: &Analysis, known: &HashMap<usize, &'static str>, e: &Expr) -> Option<&'static str> {
    let ExprKind::Call { func, keywords, .. } = &e.kind else { return None };
    let ExprKind::Attribute { value, attr, .. } = &func.kind else { return None };
    if keywords.iter().any(|k| k.name == "then") {
        return None;
    }
    let receiver = type_at(analysis, known, value.span)?;
    method_result(receiver, attr)
}

/// What [method] of a value of [receiver] returns, when the libraries say.
pub fn method_result(receiver: &str, method: &str) -> Option<&'static str> {
    let request = matches!(method, "get" | "post" | "put" | "patch" | "delete" | "head" | "options" | "request" | "send");
    match receiver {
        SESSION if request => Some(REQUESTS_RESPONSE),
        CLIENT if request => Some(HTTPX_RESPONSE),
        HTTPX_RESPONSE if method == "raise_for_status" => Some(HTTPX_RESPONSE),
        _ => None,
    }
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
        // `requests.Session().get(url)`, `httpx.get(url).raise_for_status()`.
        if let ExprKind::Call { .. } = &value.kind {
            if let Some(ty) = package_type(analysis, value).and_then(|t| method_result(t, attr)) {
                return Some(ty);
            }
        }
    }
    let path = crate::keyboard::api_path(analysis, func)?;
    Some(match crate::keyboard::short(&path) {
        "http.get" | "http.post" | "http.put" | "http.patch" | "http.delete" | "http.request" => RESPONSE,
        "plugin.state" => "wm5.plugin.State",
        "location.get" => "dict",
        "sensors.available" | "reminders.list" => "list",
        "requests.get" | "requests.post" | "requests.put" | "requests.patch" | "requests.delete" | "requests.head" | "requests.options"
        | "requests.request" | "requests.api.get" | "requests.api.post" | "requests.api.put" | "requests.api.patch"
        | "requests.api.delete" | "requests.api.head" | "requests.api.options" | "requests.api.request" => REQUESTS_RESPONSE,
        "requests.Session" | "requests.session" | "requests.sessions.Session" | "requests.sessions.session" => SESSION,
        "httpx.get" | "httpx.post" | "httpx.put" | "httpx.patch" | "httpx.delete" | "httpx.head" | "httpx.options" | "httpx.request" => HTTPX_RESPONSE,
        "httpx.Client" => CLIENT,
        "urllib.request.urlopen" => "urllib.request.HTTPResponse",
        "urllib.request.Request" => "urllib.request.Request",
        _ => return None,
    })
}

pub const RESPONSE: &str = "wm5.http.Response";
pub const REQUESTS_RESPONSE: &str = "requests.Response";
pub const HTTPX_RESPONSE: &str = "httpx.Response";
/// A `requests.Session()`.
pub const SESSION: &str = "requests.Session";
/// An `httpx.Client()`.
pub const CLIENT: &str = "httpx.Client";
