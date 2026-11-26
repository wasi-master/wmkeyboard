//! Type stubs (`.pyi`) of the `wm` package, written from the API model
//! ([crate::api], [crate::package], [crate::keyboard]), so editors that are
//! not WM Keyboard's own (Pylance, pyright, mypy, PyCharm) know
//! `from wm import plugin, ui`: every module, function, class and constant
//! the model has, with its signature and its doc as the docstring.
//!
//! Types are what the model knows, which is little: most parameters and
//! returns are left open, and a few are filled in where the package
//! documents them (`http.get` returns a `Response`, `plugin.state` a `State`,
//! a provider's `tool` is one of the provider lists). The stubs are for
//! names, signatures and docs; the language service and the interpreter
//! check the rest.
//!
//! Pure: [wm_package] returns `(relative path, text)` pairs for the caller to
//! write, the CLI to `.wmpy/stubs/`, the language server or the web wherever
//! it keeps them.

use std::fmt::Write;

use crate::api::{self, ApiEntry, ApiKind};
use crate::keyboard::PROVIDER_TOOLS;
use crate::package::ROOT;

/// The first line of every stub, so nobody edits one by hand.
pub const HEADER: &str = "# Written by wmpy from WM Keyboard's plugin API model. Do not edit: run `wmpy stubs` again.";

/// Return types the package documents.
const RETURNS: &[(&str, &str)] = &[
    ("http.request", "Response"),
    ("http.get", "Response"),
    ("http.post", "Response"),
    ("http.put", "Response"),
    ("http.patch", "Response"),
    ("http.delete", "Response"),
    ("http.Response.raise_for_status", "Response"),
    ("plugin.state", "State"),
    ("timestamp", "int"),
    ("zone_offset", "int"),
    ("_", "str"),
    ("gettext", "str"),
    ("ngettext", "str"),
    ("i18n._", "str"),
    ("log", "None"),
];

/// Constant types the package documents.
const CONSTANTS: &[(&str, &str)] = &[
    ("api_version", "int"),
    ("plugin_id", "str"),
    ("plugin_version", "str"),
    ("storage", "dict[str, Any]"),
    ("plugin.id", "str"),
    ("plugin.version", "str"),
    ("plugin.api_version", "int"),
    ("plugin.permissions", "tuple[str, ...]"),
    ("plugin.locale", "str"),
    ("plugin.theme", "str"),
    ("plugin.surface", "str"),
    ("plugin.dark", "bool"),
    ("ui.COLOR_ROLES", "tuple[str, ...]"),
    ("http.Response.status_code", "int"),
    ("http.Response.ok", "bool"),
    ("http.Response.headers", "dict[str, str]"),
    ("http.Response.text", "str"),
    ("http.Response.content", "bytes"),
    ("http.Response.url", "str"),
    ("http.Response.reason", "str"),
];

/// Classes with a base the model does not record; an exception's is `Exception` otherwise.
const BASES: &[(&str, &str)] = &[("http.HTTPError", "OSError"), ("ui.Container", "Widget")];

/// Objects whose fields are the plugin's own: any attribute reads (and for
/// state, writes) without the checker knowing it in advance.
const OPEN_OBJECTS: &[&str] = &["plugin.State", "plugin.settings"];

/// `with ui.column():` and the other containers.
const CONTEXT_MANAGERS: &[&str] = &["ui.Container"];

/// Classes of the libraries that work in a `with` block.
const LIBRARY_CONTEXT_MANAGERS: &[&str] = &["Session", "Response", "Client"];

/// Every stub file of the `wm` package (`wm/__init__.pyi` and one per
/// module), and of the libraries the host gives plugins beside it
/// ([library_stubs]).
pub fn wm_package() -> Vec<(String, String)> {
    let mut out = package_stubs();
    out.extend(library_stubs());
    out
}

/// `requests/__init__.pyi`, `requests/<module>.pyi` and `httpx.pyi`: the
/// libraries as the host has them, `then=` and all. urllib.request has
/// none: the checker's own stubs for the standard library describe it, and
/// a `urllib` folder here would hide the rest of `urllib` from them.
pub fn library_stubs() -> Vec<(String, String)> {
    let model = api::model();
    let mut out = Vec::new();
    for module in crate::package::library_modules().filter(|m| !m.starts_with("urllib")) {
        let Some(entry) = model.find(module) else { continue };
        let mut text = header(entry.doc);
        let mut children: Vec<&ApiEntry> = model.children(module).into_iter().filter(|e| e.kind != ApiKind::Module).collect();
        // A class after the class it derives from.
        let mut ordered: Vec<&ApiEntry> = Vec::new();
        while !children.is_empty() {
            let before = children.len();
            let mut k = 0;
            while k < children.len() {
                let e = children[k];
                let waits = crate::package::library_base(e.path).is_some_and(|b| children.iter().any(|c| c.name() == b && c.path != e.path));
                if waits {
                    k += 1;
                } else {
                    ordered.push(children.remove(k));
                }
            }
            if children.len() == before {
                ordered.append(&mut children);
            }
        }
        for e in ordered {
            library_member(&mut text, module, e);
        }
        let path = match module.split_once('.') {
            Some((top, sub)) => format!("{top}/{sub}.pyi"),
            None if model.children(module).iter().any(|e| e.kind == ApiKind::Module) => format!("{module}/__init__.pyi"),
            None => format!("{module}.pyi"),
        };
        out.push((path, text));
    }
    out
}

/// One top-level name of library [module] in its stub.
fn library_member(out: &mut String, module: &str, e: &ApiEntry) {
    let model = api::model();
    match e.kind {
        ApiKind::Type | ApiKind::Exception => {
            let base = crate::package::library_base(e.path)
                .filter(|b| api::EXCEPTIONS.contains(b) || *b == "Exception" || model.find(&format!("{module}.{b}")).is_some())
                .unwrap_or(if e.kind == ApiKind::Exception { "Exception" } else { "" });
            let bases = if base.is_empty() { String::new() } else { format!("({base})") };
            let _ = writeln!(out, "class {}{bases}:", e.name());
            docstring(out, e.doc, 1);
            if let Some(sig) = e.signature {
                let params = sig.find('(').map(|at| &sig[at + 1..sig.len().saturating_sub(1)]).unwrap_or("");
                let mut list = vec!["self".to_string()];
                list.extend(split_params(params).iter().map(|p| param(p, "")));
                let _ = writeln!(out, "    def __init__({}) -> None: ...", list.join(", "));
            }
            if LIBRARY_CONTEXT_MANAGERS.contains(&e.name()) {
                let _ = writeln!(out, "    def __enter__(self) -> {}: ...", e.name());
                let _ = writeln!(out, "    def __exit__(self, *args) -> None: ...");
            }
            for m in model.children(e.path) {
                match m.kind {
                    ApiKind::Function | ApiKind::Method => {
                        let ret = library_return(module, e.name(), m.name());
                        library_function(out, "    ", m, ret, true);
                    }
                    _ => {
                        let _ = writeln!(out, "    {}: Any", m.name());
                        docstring(out, m.doc, 1);
                    }
                }
            }
            if !out.ends_with("\n\n") {
                out.push('\n');
            }
        }
        ApiKind::Function | ApiKind::Method | ApiKind::Builtin => {
            let ret = library_return(module, "", e.name());
            library_function(out, "", e, ret, false);
        }
        _ => {
            let _ = writeln!(out, "{}: Any", e.name());
            docstring(out, e.doc, 0);
            out.push('\n');
        }
    }
}

/// The class a library function returns, when [module] has it: `Response` for `requests.get`.
fn library_return(module: &str, class: &str, name: &str) -> Option<String> {
    let request = matches!(name, "get" | "post" | "put" | "patch" | "delete" | "head" | "options" | "request" | "send");
    let ty = match (class, name) {
        ("" | "Session" | "Client", _) if request => "Response",
        ("", "session") => "Session",
        ("", "urlopen") => "HTTPResponse",
        ("Response", "raise_for_status") if module == "httpx" => "Response",
        ("Request", "prepare") | ("Session", "prepare_request") => "PreparedRequest",
        _ => return None,
    };
    api::model().find(&format!("{module}.{ty}")).map(|_| ty.to_string())
}

fn library_function(out: &mut String, pad: &str, e: &ApiEntry, ret: Option<String>, method: bool) {
    let sig = e.signature.unwrap_or("");
    let params = match sig.find('(') {
        Some(at) if sig.ends_with(')') => &sig[at + 1..sig.len() - 1],
        _ => "*args, **kwargs",
    };
    let mut list: Vec<String> = Vec::new();
    if method {
        list.push("self".into());
    }
    list.extend(split_params(params).iter().map(|p| param(p, "")));
    let ret = ret.map(|r| format!(" -> {r}")).unwrap_or_default();
    let _ = writeln!(out, "{pad}def {}({}){ret}:", e.name(), list.join(", "));
    docstring(out, e.doc, if method { 2 } else { 1 });
    let _ = writeln!(out, "{pad}    ...");
    out.push('\n');
}

/// The `wm` package's stub files.
fn package_stubs() -> Vec<(String, String)> {
    let model = api::model();
    let mut out = Vec::new();
    let modules: Vec<&ApiEntry> = model.children(ROOT).into_iter().filter(|e| e.kind == ApiKind::Module).collect();
    let root = model.find(ROOT);
    let mut init = header(root.map(|e| e.doc).unwrap_or(""));
    let names: Vec<&str> = modules.iter().map(|m| m.name()).collect();
    if !names.is_empty() {
        let _ = writeln!(init, "from . import {}", names.iter().map(|n| format!("{n} as {n}")).collect::<Vec<_>>().join(", "));
        init.push('\n');
    }
    members(&mut init, ROOT, 0, &|e| e.kind != ApiKind::Module);
    out.push(("wm/__init__.pyi".to_string(), init));
    for m in modules {
        let mut text = header(m.doc);
        members(&mut text, m.path, 0, &|_| true);
        out.push((format!("wm/{}.pyi", m.name()), text));
    }
    out
}

/// Declarations of plugin API 4's `wm` and `ui` globals, for a
/// `__builtins__.pyi`: each namespace as a class, and the global as an
/// instance of it.
pub fn api4_globals() -> String {
    let mut out = String::new();
    for global in api::API_GLOBALS {
        namespace_class(&mut out, global, &format!("_Api4_{global}"));
        let _ = writeln!(out, "{global}: _Api4_{global}");
        let doc = api::model().find(global).map(|e| e.doc).unwrap_or("");
        docstring(&mut out, doc, 0);
        out.push('\n');
    }
    out
}

fn header(doc: &str) -> String {
    let mut s = String::new();
    let _ = writeln!(s, "{HEADER}");
    docstring(&mut s, doc, 0);
    s.push('\n');
    s.push_str("from typing import Any, Literal\n\n");
    s
}

/// Writes every entry directly under [parent] that [keep] allows.
fn members(out: &mut String, parent: &str, depth: usize, keep: &dyn Fn(&ApiEntry) -> bool) {
    let model = api::model();
    let pad = "    ".repeat(depth);
    let short = |path: &str| path.strip_prefix(ROOT).map(|p| p.trim_start_matches('.').to_string()).unwrap_or_else(|| path.to_string());
    for e in model.children(parent) {
        if !keep(e) {
            continue;
        }
        let key = short(e.path);
        let children = model.children(e.path);
        match e.kind {
            ApiKind::Type | ApiKind::Exception => {
                let fallback = if e.kind == ApiKind::Exception { "Exception" } else { "" };
                let base = BASES.iter().find(|(k, _)| *k == key).map(|(_, b)| *b).unwrap_or(fallback);
                class(out, &pad, e.name(), base, e, &key, depth);
            }
            ApiKind::Constant if !children.is_empty() => {
                // An object with members of its own: a class for its type, then the name.
                let type_name = format!("_{}", camel(e.name()));
                class(out, &pad, &type_name, "", e, &key, depth);
                let _ = writeln!(out, "{pad}{}: {type_name}", e.name());
                docstring(out, e.doc, depth);
                out.push('\n');
            }
            ApiKind::Constant => {
                let ty = CONSTANTS.iter().find(|(k, _)| *k == key).map(|(_, t)| *t).unwrap_or("Any");
                let _ = writeln!(out, "{pad}{}: {ty}", e.name());
                docstring(out, e.doc, depth);
                out.push('\n');
            }
            ApiKind::Function | ApiKind::Method | ApiKind::Builtin => {
                function(out, &pad, e, &key, depth, depth > 0);
            }
            ApiKind::Module => {}
        }
    }
}

fn class(out: &mut String, pad: &str, name: &str, base: &str, e: &ApiEntry, key: &str, depth: usize) {
    let bases = if base.is_empty() { String::new() } else { format!("({base})") };
    let _ = writeln!(out, "{pad}class {name}{bases}:");
    docstring(out, e.doc, depth + 1);
    let inner = "    ".repeat(depth + 1);
    if OPEN_OBJECTS.contains(&key) {
        let _ = writeln!(out, "{inner}def __getattr__(self, name: str) -> Any: ...");
        if key == "plugin.State" {
            let _ = writeln!(out, "{inner}def __setattr__(self, name: str, value: Any) -> None: ...");
            let _ = writeln!(out, "{inner}def __getitem__(self, name: str) -> Any: ...");
            let _ = writeln!(out, "{inner}def __setitem__(self, name: str, value: Any) -> None: ...");
            let _ = writeln!(out, "{inner}def __contains__(self, name: str) -> bool: ...");
        }
    }
    if CONTEXT_MANAGERS.contains(&key) {
        let _ = writeln!(out, "{inner}def __enter__(self) -> {name}: ...");
        let _ = writeln!(out, "{inner}def __exit__(self, *args) -> None: ...");
    }
    members(out, e.path, depth + 1, &|_| true);
    if !out.ends_with("\n\n") {
        out.push('\n');
    }
}

fn function(out: &mut String, pad: &str, e: &ApiEntry, key: &str, depth: usize, method: bool) {
    let sig = e.signature.unwrap_or("");
    let (name, params) = match sig.find('(') {
        Some(at) if sig.ends_with(')') => (&sig[..at], &sig[at + 1..sig.len() - 1]),
        _ => (e.name(), "*args"),
    };
    let name = if name.trim().is_empty() || name.trim() != e.name() { e.name() } else { name.trim() };
    let mut list: Vec<String> = Vec::new();
    if method {
        list.push("self".into());
    }
    for p in split_params(params) {
        list.push(param(&p, key));
    }
    let ret = RETURNS.iter().find(|(k, _)| *k == key).map(|(_, r)| format!(" -> {r}")).unwrap_or_default();
    let _ = writeln!(out, "{pad}def {name}({}){ret}:", list.join(", "));
    docstring(out, e.doc, depth + 1);
    let _ = writeln!(out, "{pad}    ...");
    out.push('\n');
}

/// One parameter for a stub: its default kept when it is a plain literal, `...` otherwise.
fn param(p: &str, key: &str) -> String {
    let p = p.trim();
    let (name, default) = match p.split_once('=') {
        Some((n, d)) => (n.trim(), Some(d.trim())),
        None => (p, None),
    };
    // Annotations in the source signature are dropped: the model's types are the source's names.
    let name = name.split(':').next().unwrap_or(name).trim();
    let annotation = if key == "plugin.provider" && name == "tool" {
        let tools: Vec<String> = PROVIDER_TOOLS.iter().map(|t| format!("\"{}\"", t.name)).collect();
        format!(": Literal[{}]", tools.join(", "))
    } else {
        String::new()
    };
    match default {
        None => format!("{name}{annotation}"),
        Some(d) => {
            let simple = matches!(d, "None" | "True" | "False")
                || d.parse::<f64>().is_ok()
                || (d.len() <= 40 && ((d.starts_with('"') && d.ends_with('"') && d[1..d.len() - 1].chars().all(|c| c != '"' && c != '\\')) || (d.starts_with('\'') && d.ends_with('\'') && d[1..d.len() - 1].chars().all(|c| c != '\'' && c != '\\'))));
            let space = if annotation.is_empty() { "" } else { " " };
            if simple {
                format!("{name}{annotation}{space}={space}{d}")
            } else {
                format!("{name}{annotation}{space}={space}...")
            }
        }
    }
}

/// [params] split at the commas outside brackets and strings.
fn split_params(params: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    let mut cur = String::new();
    let mut escaped = false;
    for c in params.chars() {
        if let Some(q) = quote {
            cur.push(c);
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == q {
                quote = None;
            }
            continue;
        }
        match c {
            '"' | '\'' => {
                quote = Some(c);
                cur.push(c);
            }
            '(' | '[' | '{' => {
                depth += 1;
                cur.push(c);
            }
            ')' | ']' | '}' => {
                depth -= 1;
                cur.push(c);
            }
            ',' if depth == 0 => {
                if !cur.trim().is_empty() {
                    out.push(cur.trim().to_string());
                }
                cur.clear();
            }
            _ => cur.push(c),
        }
    }
    if !cur.trim().is_empty() {
        out.push(cur.trim().to_string());
    }
    out
}

/// A class for the namespace [path] of the API 4 globals, its members as
/// methods and attributes, nested namespaces as classes of their own.
fn namespace_class(out: &mut String, path: &str, name: &str) {
    let model = api::model();
    let children = model.children(path);
    for c in &children {
        if c.kind == ApiKind::Module {
            namespace_class(out, c.path, &format!("{name}_{}", c.name()));
        }
    }
    let _ = writeln!(out, "class {name}:");
    docstring(out, model.find(path).map(|e| e.doc).unwrap_or(""), 1);
    for c in &children {
        match c.kind {
            ApiKind::Module => {
                let _ = writeln!(out, "    {}: {name}_{}", c.name(), c.name());
                docstring(out, c.doc, 1);
            }
            ApiKind::Constant => {
                let ty = match c.name() {
                    "api_version" => "int",
                    "plugin_id" | "plugin_version" => "str",
                    _ => "Any",
                };
                let _ = writeln!(out, "    {}: {ty}", c.name());
                docstring(out, c.doc, 1);
            }
            _ => function(out, "    ", c, c.path, 1, true),
        }
    }
    out.push('\n');
}

fn camel(name: &str) -> String {
    let mut out = String::new();
    let mut up = true;
    for c in name.chars() {
        if c == '_' {
            up = true;
        } else if up {
            out.extend(c.to_uppercase());
            up = false;
        } else {
            out.push(c);
        }
    }
    out
}

/// A triple-quoted docstring at [depth], or nothing for an empty doc.
fn docstring(out: &mut String, doc: &str, depth: usize) {
    let doc = doc.trim();
    if doc.is_empty() {
        return;
    }
    let pad = "    ".repeat(depth);
    let escaped = doc.replace('\\', "\\\\").replace("\"\"\"", "\\\"\\\"\\\"");
    let escaped = if escaped.ends_with('"') { format!("{escaped} ") } else { escaped };
    let _ = writeln!(out, "{pad}\"\"\"{}\"\"\"", wrap(&escaped, 88usize.saturating_sub(pad.len() + 6), &pad));
}

/// [text] wrapped at [width], continuation lines indented with [pad].
fn wrap(text: &str, width: usize, pad: &str) -> String {
    let width = width.max(40);
    let mut out = String::new();
    let mut line = 0usize;
    for word in text.split(' ') {
        if line > 0 && line + 1 + word.chars().count() > width {
            out.push('\n');
            out.push_str(pad);
            line = 0;
        } else if line > 0 {
            out.push(' ');
            line += 1;
        }
        out.push_str(word);
        line += word.chars().count();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::document::Document;

    fn files() -> Vec<(String, String)> {
        wm_package()
    }

    #[test]
    fn every_stub_parses() {
        let mut all = files();
        all.push(("__builtins__.pyi".into(), format!("from typing import Any\n\n{}", api4_globals())));
        for (path, text) in &all {
            let doc = Document::new(text);
            let errors: Vec<String> = doc.module.errors.iter().map(|e| format!("{:?} at {}", e.expected, doc.lines.line_of(e.span.start) + 1)).collect();
            assert!(errors.is_empty(), "{path} does not parse: {errors:?}\n{text}");
        }
    }

    #[test]
    fn every_api_entry_appears() {
        let model = api::model();
        let files = files();
        let text_of = |file: &str| files.iter().find(|(p, _)| p == file).map(|(_, t)| t.as_str()).unwrap_or_else(|| panic!("no {file}"));
        let mut missing = Vec::new();
        for e in &model.entries {
            let Some(rest) = e.path.strip_prefix(ROOT) else { continue };
            let rest = rest.trim_start_matches('.');
            if rest.is_empty() {
                continue;
            }
            let parts: Vec<&str> = rest.split('.').collect();
            let module_level = model.find(&format!("{ROOT}.{}", parts[0])).is_some_and(|m| m.kind == ApiKind::Module);
            let file = if module_level { format!("wm/{}.pyi", parts[0]) } else { "wm/__init__.pyi".to_string() };
            if module_level && parts.len() == 1 {
                // The module itself: its file, and its re-export from wm.
                let _ = text_of(&file);
                if !text_of("wm/__init__.pyi").contains(&format!("{0} as {0}", parts[0])) {
                    missing.push(e.path.to_string());
                }
                continue;
            }
            let text = text_of(&file);
            let name = e.name();
            let declared = [format!("def {name}("), format!("class {name}"), format!("{name}: "), format!("class _{}", camel(name))];
            if !declared.iter().any(|d| text.contains(d.as_str())) {
                missing.push(e.path.to_string());
            }
        }
        assert!(missing.is_empty(), "not in the stubs: {missing:?}");
    }

    #[test]
    fn signatures_docs_and_types() {
        let files = files();
        let get = |file: &str| files.iter().find(|(p, _)| p == file).map(|(_, t)| t.clone()).unwrap();
        let http = get("wm/http.pyi");
        assert!(http.starts_with(HEADER));
        assert!(http.contains("def get(url, params=None, **options) -> Response:"), "{http}");
        assert!(http.contains("class HTTPError(OSError):"));
        assert!(http.contains("    def json(self):"));
        let plugin = get("wm/plugin.pyi");
        assert!(plugin.contains(") -> State:"));
        assert!(plugin.contains("def __getattr__(self, name: str) -> Any: ..."));
        assert!(plugin.contains("tool: Literal[\"search\", \"translate\""), "{plugin}");
        let ui = get("wm/ui.pyi");
        assert!(ui.contains("def __enter__(self)"));
        assert!(ui.contains("\"\"\"Widgets stacked top to bottom."));
        let init = get("wm/__init__.pyi");
        assert!(init.contains("from . import "));
        assert!(init.contains("storage: dict[str, Any]"));
        let api4 = api4_globals();
        assert!(api4.contains("wm: _Api4_wm") && api4.contains("ui: _Api4_ui") && api4.contains("storage: _Api4_wm_storage"), "{api4}");
    }

    #[test]
    fn params() {
        assert_eq!(split_params("a, b=(1, 2), c=\"x,y\", *rest, **kw"), vec!["a", "b=(1, 2)", "c=\"x,y\"", "*rest", "**kw"]);
        assert_eq!(param("b=(1, 2)", ""), "b=...");
        assert_eq!(param("style=\"dropdown\"", ""), "style=\"dropdown\"");
        assert_eq!(param("timeout=15", ""), "timeout=15");
        assert_eq!(param("step=1", ""), "step=1");
    }
}
