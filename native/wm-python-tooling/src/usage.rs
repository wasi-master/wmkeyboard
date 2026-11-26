//! What a plugin's code uses that plugin.json has to allow, and what
//! plugin.json allows that nothing uses: each permission with the calls that
//! need it, and each website with the addresses that reach it. `wmpy
//! permissions` prints it; the language server and the MCP server can read
//! the same [Report].
//!
//! A name's permission comes from the API model (a module's entry, so a
//! module the model learns is covered the day it is modelled), from the calls
//! that need more than their module (`clipboard.get`, `field.text_before`,
//! `storage`, `plugin.state(persist=True)`, the host's own table in
//! wm-python's `host/v5.rs`), and from what the keyboard asks of plugin.json:
//! `@plugin.action` and `"actions"` need `selection`, `@plugin.preview` and
//! `"previews"` need `previews`, and a host setting is what `network.local`
//! widens.
//!
//! Addresses are read where the code writes them: a string literal, an
//! f-string's text up to its first `{`, the left side of a `+`, `.format()`
//! on a literal, or a module-level name bound once to one of those. A request
//! whose address is built any other way is counted as made at run time, and
//! then a host nothing names is reported as unknown rather than unused.

use std::collections::{BTreeMap, HashSet};

use serde_json::{json, Value};

use crate::analysis::{SymbolKind, Target};
use crate::api;
use crate::ast::*;
use crate::document::Document;
use crate::keyboard;
use crate::manifest as codec;
use crate::text::{slice, Span};

/// One Python file of the plugin.
#[derive(Clone, Copy, Debug)]
pub struct Source<'a> {
    /// Relative to the plugin folder, with `/`: `main.py`, `pkg/util.py`.
    pub name: &'a str,
    pub text: &'a str,
    /// The entry script, whose `transform` the keyboard calls.
    pub entry: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UseKind {
    /// `from wm import http`: imported, which needs nothing yet.
    Import,
    /// A name or call that needs the permission when it runs.
    Code,
    /// Something plugin.json lists: `"actions"` needs selection.
    Manifest,
}

impl UseKind {
    pub fn name(self) -> &'static str {
        match self {
            UseKind::Import => "import",
            UseKind::Code => "code",
            UseKind::Manifest => "manifest",
        }
    }
}

/// One place that needs a permission.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Use {
    pub kind: UseKind,
    /// What needs it, as written: `http.get`, `from wm import storage`, `@plugin.action`.
    pub what: String,
    /// `main.py`, or `plugin.json` for a manifest use.
    pub file: String,
    /// 1-based.
    pub line: usize,
    /// UTF-16 offsets into the file; empty for a manifest use.
    pub span: Span,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    /// Declared, and something uses it.
    Needed,
    /// Imported, and nothing calls it.
    ImportedOnly,
    /// Declared, and nothing uses it: least privilege says remove it.
    Unused,
    /// Used, and plugin.json does not declare it: the call raises PermissionError.
    Missing,
    /// Declared, and the app does not know it.
    Unknown,
}

impl Verdict {
    pub fn name(self) -> &'static str {
        match self {
            Verdict::Needed => "needed",
            Verdict::ImportedOnly => "imported_only",
            Verdict::Unused => "unused",
            Verdict::Missing => "missing",
            Verdict::Unknown => "unknown",
        }
    }
}

/// One permission: declared or used, or both.
#[derive(Clone, Debug)]
pub struct PermissionUse {
    pub name: String,
    pub declared: bool,
    /// The line of plugin.json that declares it.
    pub declared_line: Option<usize>,
    /// Whether the app knows the permission at all.
    pub known: bool,
    /// Off after install until the user turns it on.
    pub risky: bool,
    pub since_api: i64,
    pub uses: Vec<Use>,
    pub verdict: Verdict,
}

/// An address in the code.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Address {
    /// `api.example.com`, lowercase.
    pub host: String,
    /// The address as far as the code writes it out.
    pub url: String,
    /// Passed to a call that needs network (`http.get`), rather than only written in the code.
    pub requested: bool,
    /// The call it is passed to, `http.get`, or empty.
    pub call: String,
    pub file: String,
    /// 1-based.
    pub line: usize,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HostVerdict {
    /// A request goes to it.
    Used,
    /// An address in the code names it, though not straight in a request.
    Mentioned,
    /// Nothing names it, but some requests build their address at run time.
    Unknown,
    /// Nothing reaches it: least privilege says remove it.
    Unused,
    /// Another entry already covers it: `api.example.org` under `*.example.org`.
    Covered(String),
}

impl HostVerdict {
    pub fn name(&self) -> &'static str {
        match self {
            HostVerdict::Used => "used",
            HostVerdict::Mentioned => "mentioned",
            HostVerdict::Unknown => "unknown",
            HostVerdict::Unused => "unused",
            HostVerdict::Covered(_) => "covered",
        }
    }
}

/// One entry of plugin.json's hosts.
#[derive(Clone, Debug)]
pub struct DeclaredHost {
    /// As the app normalises it: `api.example.com`, `*.example.org`.
    pub entry: String,
    pub line: Option<usize>,
    /// The addresses it lets through.
    pub uses: Vec<Address>,
    pub verdict: HostVerdict,
}

/// A host the code names that no entry covers.
#[derive(Clone, Debug)]
pub struct UndeclaredHost {
    pub host: String,
    pub uses: Vec<Address>,
}

#[derive(Clone, Debug, Default)]
pub struct Hosts {
    pub declared: Vec<DeclaredHost>,
    /// Requested and not covered: refused at run time.
    pub missing: Vec<UndeclaredHost>,
    /// Written in the code, not passed straight to a request, and not covered.
    pub unlisted: Vec<UndeclaredHost>,
    /// Requests whose address the code builds at run time.
    pub dynamic: Vec<Use>,
    /// The key of a `"host"` setting: with one, any server the user types is allowed.
    pub host_setting: Option<String>,
}

/// What [analyse] found.
#[derive(Clone, Debug, Default)]
pub struct Report {
    /// The permissions plugin.json declares or the code uses, in the app's order, unknown ones last.
    pub permissions: Vec<PermissionUse>,
    pub hosts: Hosts,
}

impl Report {
    /// Permissions and hosts the plugin needs and plugin.json leaves out.
    pub fn errors(&self) -> usize {
        self.permissions.iter().filter(|p| p.verdict == Verdict::Missing).count() + self.hosts.missing.len()
    }

    /// What plugin.json could drop: unused permissions, permissions only imported, unused or covered hosts.
    pub fn suggestions(&self) -> usize {
        let p = self.permissions.iter().filter(|p| matches!(p.verdict, Verdict::Unused) || (p.verdict == Verdict::ImportedOnly && p.declared)).count();
        let h = self.hosts.declared.iter().filter(|h| matches!(h.verdict, HostVerdict::Unused | HostVerdict::Covered(_))).count();
        p + h
    }

    pub fn json(&self) -> Value {
        let use_json = |u: &Use| json!({"kind": u.kind.name(), "what": u.what, "file": u.file, "line": u.line});
        let address_json =
            |a: &Address| json!({"host": a.host, "url": a.url, "requested": a.requested, "call": a.call, "file": a.file, "line": a.line});
        let undeclared = |h: &UndeclaredHost| json!({"host": h.host, "uses": h.uses.iter().map(address_json).collect::<Vec<_>>()});
        json!({
            "ok": self.errors() == 0,
            "errors": self.errors(),
            "suggestions": self.suggestions(),
            "permissions": self.permissions.iter().map(|p| json!({
                "name": p.name, "declared": p.declared, "declaredLine": p.declared_line, "known": p.known,
                "tier": if !p.known { "unknown" } else if p.risky { "risky" } else { "listed" },
                "sinceApi": p.since_api, "verdict": p.verdict.name(),
                "uses": p.uses.iter().map(use_json).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
            "hosts": {
                "declared": self.hosts.declared.iter().map(|h| json!({
                    "entry": h.entry, "line": h.line, "verdict": h.verdict.name(),
                    "coveredBy": match &h.verdict { HostVerdict::Covered(by) => Some(by.as_str()), _ => None },
                    "uses": h.uses.iter().map(address_json).collect::<Vec<_>>(),
                })).collect::<Vec<_>>(),
                "missing": self.hosts.missing.iter().map(undeclared).collect::<Vec<_>>(),
                "unlisted": self.hosts.unlisted.iter().map(undeclared).collect::<Vec<_>>(),
                "dynamic": self.hosts.dynamic.iter().map(use_json).collect::<Vec<_>>(),
                "hostSetting": self.hosts.host_setting,
            },
        })
    }
}

/// The permission an API path needs: `network` for `wm5.http.get`.
pub fn permission_of(path: &str) -> Option<&'static str> {
    let short = keyboard::short(path);
    if path.starts_with(crate::package::ROOT) {
        let head = |p: &str| short == p || short.starts_with(&format!("{p}."));
        if head("storage") || head("secrets") {
            return Some("storage");
        }
        if let Some(name) = short.strip_prefix("field.") {
            let name = name.split('.').next().unwrap_or(name);
            match name {
                "selection" | "text_before" | "text_after" | "info" => return Some("field.read"),
                "insert" | "replace_selection" | "delete" | "move_cursor" | "send" | "insert_image" => return Some("field.write"),
                _ => {}
            }
        }
        if let Some(name) = short.strip_prefix("clipboard.") {
            match name.split('.').next().unwrap_or(name) {
                "get" | "paste" => return Some("clipboard.read"),
                "set" | "copy" => return Some("clipboard.write"),
                _ => {}
            }
        }
    }
    // The entry, else the nearest module above it that needs one.
    let model = api::model();
    let mut at = path;
    loop {
        if let Some(p) = model.find(at).and_then(|e| e.permission) {
            return Some(p);
        }
        at = &at[..at.rfind('.')?];
    }
}

/// The host of an address: `api.example.com` for `https://api.example.com:8443/x?y`.
pub fn host_of(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host: String = rest.chars().take_while(|c| !matches!(c, '/' | ':' | '?' | '#')).collect();
    let host = host.rsplit('@').next().unwrap_or(&host).trim_end_matches('.').to_lowercase();
    let ok = !host.is_empty() && host.chars().all(|c| c.is_alphanumeric() || matches!(c, '.' | '-' | '_')) && host.contains(|c: char| c.is_alphanumeric());
    ok.then_some(host)
}

/// Whether the hosts entry [entry] lets [host] through, as `PluginHttp` decides.
pub fn covers(entry: &str, host: &str) -> bool {
    let entry = entry.trim().to_lowercase();
    let host = host.trim_end_matches('.').to_lowercase();
    match entry.strip_prefix("*.") {
        Some(base) => host == base || host.ends_with(&format!(".{base}")),
        None => host == entry,
    }
}

/// What the plugin in [manifest] (plugin.json's text) and [files] uses, and what it declares.
pub fn analyse(manifest: &str, files: &[Source]) -> Report {
    let mut uses: Vec<(&'static str, Use)> = Vec::new();
    let mut addresses: Vec<Address> = Vec::new();
    let mut dynamic: Vec<Use> = Vec::new();
    for f in files {
        Scan::run(f, &mut uses, &mut addresses, &mut dynamic);
    }
    addresses.sort_by_key(|a| (files.iter().position(|f| f.name == a.file), a.line, a.span.start));
    let m = codec::check(manifest).manifest;
    let line_of_key = |key: &str| key_line(manifest, key);
    let mut declared: Vec<String> = Vec::new();
    let mut entries: Vec<String> = Vec::new();
    let mut host_setting = None;
    if let Some(m) = &m {
        for p in &m.permissions {
            let p = p.trim().to_string();
            if !p.is_empty() && !declared.contains(&p) {
                declared.push(p);
            }
        }
        entries = m.clean_hosts().into_iter().filter(|h| !h.is_empty()).collect();
        host_setting = m.settings.iter().find(|s| s.kind.trim() == "host").map(|s| s.key.trim().to_string());
        let manifest_use = |what: String, key: &str| Use { kind: UseKind::Manifest, what, file: codec::MANIFEST.into(), line: line_of_key(key).unwrap_or(1), span: Span::default() };
        if !m.actions.is_empty() {
            uses.push(("selection", manifest_use(format!("\"actions\" lists {}", count(m.actions.len(), "action", "actions")), "actions")));
        }
        if !m.previews.is_empty() {
            uses.push(("previews", manifest_use(format!("\"previews\" lists {}", count(m.previews.len(), "file type", "file types")), "previews")));
        }
        if let (Some(key), true) = (&host_setting, declared.iter().any(|d| d == "network.local")) {
            uses.push(("network.local", manifest_use(format!("the host setting \"{key}\""), "settings")));
        }
    }

    // ---- permissions ----
    let mut by_name: BTreeMap<&str, Vec<Use>> = BTreeMap::new();
    for (p, u) in uses {
        let list = by_name.entry(p).or_default();
        if !list.contains(&u) {
            list.push(u);
        }
    }
    // plugin.json first, then each file in the order given, top to bottom.
    let order = |u: &Use| (files.iter().position(|f| f.name == u.file).map_or(0, |i| i + 1), u.line, u.span.start);
    for list in by_name.values_mut() {
        list.sort_by_key(order);
    }
    dynamic.sort_by_key(order);
    let mut permissions: Vec<PermissionUse> = Vec::new();
    for p in codec::PERMISSIONS {
        permissions.extend(permission_use(manifest, &declared, p.wire, Some(p), by_name.remove(p.wire).unwrap_or_default()));
    }
    // A name the model gives that the app's table does not have: shown, never dropped.
    for (name, list) in std::mem::take(&mut by_name) {
        permissions.extend(permission_use(manifest, &declared, name, None, list));
    }
    for d in &declared {
        if codec::permission(d).is_none() && !permissions.iter().any(|p| p.name == *d) {
            permissions.extend(permission_use(manifest, &declared, d, None, Vec::new()));
        }
    }

    // ---- hosts ----
    let anything_covers = |host: &str| host_setting.is_some() || entries.iter().any(|e| covers(e, host));
    let mut declared_hosts = Vec::new();
    for entry in &entries {
        let hits: Vec<Address> = addresses.iter().filter(|a| covers(entry, &a.host)).cloned().collect();
        let bare = entry.strip_prefix("*.").unwrap_or(entry);
        let wider = entries.iter().find(|other| *other != entry && other.starts_with("*.") && covers(other, bare));
        let verdict = if let Some(other) = wider {
            HostVerdict::Covered(other.clone())
        } else if hits.iter().any(|a| a.requested) {
            HostVerdict::Used
        } else if !hits.is_empty() {
            HostVerdict::Mentioned
        } else if !dynamic.is_empty() {
            HostVerdict::Unknown
        } else {
            HostVerdict::Unused
        };
        declared_hosts.push(DeclaredHost { entry: entry.clone(), line: value_line(manifest, "hosts", entry), uses: hits, verdict });
    }
    let group = |requested: bool| -> Vec<UndeclaredHost> {
        let mut out: Vec<UndeclaredHost> = Vec::new();
        for a in addresses.iter().filter(|a| a.requested == requested && !anything_covers(&a.host)) {
            match out.iter_mut().find(|h| h.host == a.host) {
                Some(h) => h.uses.push(a.clone()),
                None => out.push(UndeclaredHost { host: a.host.clone(), uses: vec![a.clone()] }),
            }
        }
        out
    };
    let missing = group(true);
    let unlisted: Vec<UndeclaredHost> = group(false).into_iter().filter(|h| !missing.iter().any(|m| m.host == h.host)).collect();
    Report { permissions, hosts: Hosts { declared: declared_hosts, missing, unlisted, dynamic, host_setting } }
}

/// The row for [name], or None when plugin.json does not declare it and nothing uses it.
fn permission_use(manifest: &str, declared: &[String], name: &str, known: Option<&codec::Permission>, uses: Vec<Use>) -> Option<PermissionUse> {
    let is_declared = declared.iter().any(|d| d == name);
    if !is_declared && uses.is_empty() {
        return None;
    }
    let only_imports = !uses.is_empty() && uses.iter().all(|u| u.kind == UseKind::Import);
    let verdict = match (known, is_declared) {
        (None, _) => Verdict::Unknown,
        _ if only_imports => Verdict::ImportedOnly,
        (_, true) if uses.is_empty() => Verdict::Unused,
        (_, true) => Verdict::Needed,
        (_, false) => Verdict::Missing,
    };
    Some(PermissionUse {
        name: name.to_string(),
        declared: is_declared,
        declared_line: if is_declared { value_line(manifest, "permissions", name) } else { None },
        known: known.is_some(),
        risky: known.is_some_and(|p| p.risky),
        since_api: known.map_or(0, |p| p.since_api),
        uses,
        verdict,
    })
}

fn count(n: usize, one: &str, many: &str) -> String {
    format!("{n} {}", if n == 1 { one } else { many })
}

/// The byte offset of `"key":` in JSON [text].
fn key_at(text: &str, key: &str) -> Option<usize> {
    let needle = format!("\"{key}\"");
    let mut from = 0;
    while let Some(i) = text[from..].find(&needle) {
        let at = from + i;
        if text[at + needle.len()..].trim_start().starts_with(':') {
            return Some(at);
        }
        from = at + needle.len();
    }
    None
}

fn line_at(text: &str, at: usize) -> usize {
    text[..at].matches('\n').count() + 1
}

/// The 1-based line of `"key":` in JSON [text].
fn key_line(text: &str, key: &str) -> Option<usize> {
    key_at(text, key).map(|at| line_at(text, at))
}

/// The 1-based line of the string [value] in the array under `"key":`.
fn value_line(text: &str, key: &str, value: &str) -> Option<usize> {
    let start = key_at(text, key)?;
    let quoted = serde_json::to_string(value).ok()?.to_lowercase();
    let rest = &text[start..];
    let end = rest.find(']').unwrap_or(rest.len());
    let at = rest[..end].to_lowercase().find(&quoted)?;
    Some(line_at(text, start + at))
}

/// One file's walk.
struct Scan<'a> {
    file: &'a Source<'a>,
    doc: Document,
}

impl<'a> Scan<'a> {
    fn run(file: &'a Source<'a>, uses: &mut Vec<(&'static str, Use)>, addresses: &mut Vec<Address>, dynamic: &mut Vec<Use>) {
        let s = Scan { file, doc: Document::new(file.text) };
        s.imports(uses);
        let mut requested: HashSet<Span> = HashSet::new();
        for stmt in &s.doc.module.body {
            s.stmt(stmt, uses, addresses, dynamic, &mut requested);
        }
        s.decorators(uses);
        // Addresses written in the code but not passed to a request.
        walk_stmts(&s.doc.module.body, &mut |stmt| {
            // A docstring's links are for people.
            if let StmtKind::Expr(Expr { kind: ExprKind::Str { .. }, .. }) = &stmt.kind {
                return;
            }
            for e in stmt_exprs(stmt) {
                walk_expr(e, &mut |x| {
                    if !matches!(x.kind, ExprKind::Str { .. }) || requested.contains(&x.span) {
                        return;
                    }
                    if let Some((url, span)) = s.literal_url(x) {
                        if let Some(host) = host_of(&url) {
                            addresses.push(s.address(host, url, false, "", span));
                        }
                    }
                });
            }
        });
    }

    fn line(&self, span: Span) -> usize {
        self.doc.lines.line_of(span.start) + 1
    }

    fn code_use(&self, kind: UseKind, what: String, span: Span) -> Use {
        Use { kind, what, file: self.file.name.to_string(), line: self.line(span), span }
    }

    fn address(&self, host: String, url: String, requested: bool, call: &str, span: Span) -> Address {
        Address { host, url, requested, call: call.to_string(), file: self.file.name.to_string(), line: self.line(span), span }
    }

    fn imports(&self, uses: &mut Vec<(&'static str, Use)>) {
        let known = |module: &str, path: &str| module == "wm" || module.starts_with("wm.") || api::model().find(path).is_some();
        walk_stmts(&self.doc.module.body, &mut |stmt| match &stmt.kind {
            StmtKind::ImportFrom { module, names, .. } if !module.starts_with('.') => {
                for n in names {
                    let path = api::import_path(module, Some(&n.name));
                    if let Some(p) = Some(&path).filter(|p| known(module, p)).and_then(|p| permission_of(p)) {
                        uses.push((p, self.code_use(UseKind::Import, format!("from {module} import {}", n.name), n.span)));
                    }
                }
            }
            StmtKind::Import(names) => {
                for n in names {
                    let path = api::import_path(&n.name, None);
                    if let Some(p) = Some(&path).filter(|p| known(&n.name, p)).and_then(|p| permission_of(p)) {
                        uses.push((p, self.code_use(UseKind::Import, format!("import {}", n.name), n.span)));
                    }
                }
            }
            _ => {}
        });
    }

    fn decorators(&self, uses: &mut Vec<(&'static str, Use)>) {
        for d in keyboard::decorated(&self.doc.module, &self.doc.analysis) {
            let perm = match d.what.as_str() {
                "plugin.action" => "selection",
                "plugin.preview" | "plugin.thumbnail" => "previews",
                _ => continue,
            };
            let what = match d.literal(0, "id") {
                Some((id, _)) => format!("@{}(\"{id}\")", d.what),
                None => format!("@{}", d.what),
            };
            uses.push((perm, self.code_use(UseKind::Code, what, d.span)));
        }
        if self.file.entry {
            if let Some(f) = self.doc.analysis.top_function("transform") {
                uses.push(("selection", self.code_use(UseKind::Code, "def transform".into(), f.name_span)));
            }
        }
    }

    fn stmt(&self, stmt: &Stmt, uses: &mut Vec<(&'static str, Use)>, addresses: &mut Vec<Address>, dynamic: &mut Vec<Use>, requested: &mut HashSet<Span>) {
        if matches!(stmt.kind, StmtKind::Import(_) | StmtKind::ImportFrom { .. }) {
            return;
        }
        for e in stmt_exprs(stmt) {
            self.expr(e, uses, addresses, dynamic, requested);
        }
        for block in stmt_blocks(stmt) {
            for s in block {
                self.stmt(s, uses, addresses, dynamic, requested);
            }
        }
    }

    fn expr(&self, e: &Expr, uses: &mut Vec<(&'static str, Use)>, addresses: &mut Vec<Address>, dynamic: &mut Vec<Use>, requested: &mut HashSet<Span>) {
        let a = &self.doc.analysis;
        match &e.kind {
            ExprKind::Name(_) | ExprKind::Attribute { .. } => {
                if let Some(path) = keyboard::api_path(a, e) {
                    if let Some(p) = permission_of(&path) {
                        uses.push((p, self.code_use(UseKind::Code, keyboard::short(&path).to_string(), e.span)));
                        return;
                    }
                }
            }
            ExprKind::Call { func, args, keywords, .. } => {
                if let Some(path) = keyboard::api_path(a, func) {
                    let short = keyboard::short(&path).to_string();
                    let persist = keywords.iter().any(|k| k.name == "persist" && matches!(k.value.kind, ExprKind::Const("True")));
                    if short == "plugin.state" && persist {
                        uses.push(("storage", self.code_use(UseKind::Code, "plugin.state(persist=True)".into(), func.span)));
                    }
                    if permission_of(&path) == Some("network") {
                        self.request(&path, &short, args, keywords, e.span, addresses, dynamic, requested);
                    }
                }
            }
            _ => {}
        }
        each_child(e, &mut |child| self.expr(child, uses, addresses, dynamic, requested));
    }

    /// The address a network call is given, by the position its signature gives `url`.
    #[allow(clippy::too_many_arguments)]
    fn request(&self, path: &str, short: &str, args: &[Expr], keywords: &[Keyword], span: Span, addresses: &mut Vec<Address>, dynamic: &mut Vec<Use>, requested: &mut HashSet<Span>) {
        let Some(sig) = api::model().find(path).and_then(|e| e.signature) else { return };
        let params = keyboard::sig_params(sig);
        let Some(index) = params.iter().take_while(|p| p.star == 0).position(|p| p.name == "url") else { return };
        let given = keywords.iter().find(|k| k.name == "url").map(|k| &k.value).or_else(|| args.get(index));
        match given.and_then(|u| self.url_of(u, 0)) {
            Some((url, at)) if host_of(&url).is_some() => {
                requested.insert(at);
                addresses.push(self.address(host_of(&url).unwrap_or_default(), url, true, short, at));
            }
            _ => dynamic.push(self.code_use(UseKind::Code, short.to_string(), given.map_or(span, |g| g.span))),
        }
    }

    /// A string literal's address, or an f-string's text up to its first field.
    fn literal_url(&self, e: &Expr) -> Option<(String, Span)> {
        match &e.kind {
            ExprKind::Str { value: Some(v), fstring: false, .. } => Some((v.clone(), e.span)),
            ExprKind::Str { fstring: true, .. } => {
                let text = slice(self.doc.units(), e.span);
                let body = text.trim_start_matches(|c: char| c.is_ascii_alphabetic());
                let body = body.trim_start_matches(['"', '\'']);
                let end = body.find(['{', '"', '\'']).unwrap_or(body.len());
                Some((body[..end].to_string(), e.span))
            }
            _ => None,
        }
    }

    /// The address [e] holds as far as the code writes it out, and the literal that holds it.
    fn url_of(&self, e: &Expr, depth: usize) -> Option<(String, Span)> {
        if depth > 4 {
            return None;
        }
        match &e.kind {
            ExprKind::Str { .. } => self.literal_url(e),
            ExprKind::Binary { op: "+", left, .. } => self.url_of(left, depth + 1),
            ExprKind::Call { func, .. } => match &func.kind {
                ExprKind::Attribute { value, attr, .. } if attr == "format" => self.url_of(value, depth + 1),
                _ => None,
            },
            ExprKind::Name(_) => {
                let a = &self.doc.analysis;
                let Target::Symbol(id) = a.occurrence_at(e.span.start)?.target else { return None };
                let s = &a.symbols[id];
                if s.scope != 0 || !s.writes.is_empty() || s.kind != SymbolKind::Variable {
                    return None;
                }
                self.doc.module.body.iter().find_map(|stmt| match &stmt.kind {
                    StmtKind::Assign { targets, value } if targets.len() == 1 && targets[0].span == s.declaration => self.url_of(value, depth + 1),
                    _ => None,
                })
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin", "id": "com.example.w", "name": "W", "pluginVersion": "1", "apiVersion": 5,
  "permissions": ["network", "storage", "clipboard.write"],
  "hosts": ["api.open-meteo.com", "*.example.org", "cdn.example.org", "unused.example.com"]
}
"#;

    fn report(manifest: &str, main: &str) -> Report {
        analyse(manifest, &[Source { name: "main.py", text: main, entry: true }])
    }

    fn verdict(r: &Report, name: &str) -> Option<Verdict> {
        r.permissions.iter().find(|p| p.name == name).map(|p| p.verdict)
    }

    #[test]
    fn permissions_needed_unused_missing_and_imported_only() {
        let main = "from wm import ui, http, clipboard, location\n\nAPI = \"https://api.open-meteo.com/v1\"\n\n\ndef render():\n    http.get(API + \"/forecast\")\n    ui.label(clipboard.get())\n";
        let r = report(MANIFEST, main);
        assert_eq!(verdict(&r, "network"), Some(Verdict::Needed));
        assert_eq!(verdict(&r, "storage"), Some(Verdict::Unused));
        assert_eq!(verdict(&r, "clipboard.write"), Some(Verdict::Unused));
        assert_eq!(verdict(&r, "clipboard.read"), Some(Verdict::Missing));
        assert_eq!(verdict(&r, "location"), Some(Verdict::ImportedOnly));
        let read = r.permissions.iter().find(|p| p.name == "clipboard.read").unwrap();
        assert!(read.risky && !read.declared);
        assert_eq!((read.uses[0].what.as_str(), read.uses[0].line), ("clipboard.get", 8));
        let net = r.permissions.iter().find(|p| p.name == "network").unwrap();
        assert_eq!(net.declared_line, Some(3));
        assert_eq!(r.errors(), 1);
    }

    #[test]
    fn hosts_used_covered_unused_and_missing() {
        let main = "from wm import http\n\n\ndef render():\n    http.get(\"https://api.open-meteo.com/v1/forecast\")\n    http.get(f\"https://cdn.example.org/{x}\")\n    http.post(url=\"https://other.net/x\")\n";
        let r = report(MANIFEST, main);
        let v = |e: &str| r.hosts.declared.iter().find(|h| h.entry == e).map(|h| h.verdict.clone());
        assert_eq!(v("api.open-meteo.com"), Some(HostVerdict::Used));
        assert_eq!(v("*.example.org"), Some(HostVerdict::Used));
        assert_eq!(v("cdn.example.org"), Some(HostVerdict::Covered("*.example.org".into())));
        assert_eq!(v("unused.example.com"), Some(HostVerdict::Unused));
        assert_eq!(r.hosts.missing.len(), 1);
        assert_eq!((r.hosts.missing[0].host.as_str(), r.hosts.missing[0].uses[0].line), ("other.net", 7));
        assert!(r.hosts.dynamic.is_empty());
    }

    #[test]
    fn an_address_made_at_run_time_makes_unused_hosts_unknown() {
        let main = "from wm import http\n\n\ndef render(city):\n    http.get(base_for(city))\n";
        let r = report(MANIFEST, main);
        assert_eq!(r.hosts.dynamic.len(), 1);
        assert!(r.hosts.declared.iter().any(|h| h.entry == "unused.example.com" && h.verdict == HostVerdict::Unknown));
    }

    #[test]
    fn manifest_and_decorators_need_selection_and_previews() {
        let manifest = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "A", "pluginVersion": "1", "apiVersion": 5, "panel": false,
  "permissions": ["selection"],
  "actions": [{"id": "up", "name": "Up"}]}"#;
        let main = "from wm import plugin\n\n\n@plugin.action(\"up\")\ndef up(text):\n    return text.upper()\n\n\n@plugin.preview(\"csv\")\ndef csv(file):\n    pass\n";
        let r = report(manifest, main);
        let sel = r.permissions.iter().find(|p| p.name == "selection").unwrap();
        assert_eq!(sel.verdict, Verdict::Needed);
        assert!(sel.uses.iter().any(|u| u.kind == UseKind::Manifest && u.file == "plugin.json"));
        assert!(sel.uses.iter().any(|u| u.what == "@plugin.action(\"up\")" && u.line == 4));
        assert_eq!(verdict(&r, "previews"), Some(Verdict::Missing));
    }

    #[test]
    fn storage_and_persisted_state() {
        let manifest = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "A", "pluginVersion": "1", "apiVersion": 5}"#;
        let main = "from wm import plugin, storage, ui\n\nstate = plugin.state(n=0, persist=True)\n\n\ndef render():\n    storage[\"k\"] = 1\n    ui.label(\"x\")\n";
        let r = report(manifest, main);
        let s = r.permissions.iter().find(|p| p.name == "storage").unwrap();
        assert_eq!(s.verdict, Verdict::Missing);
        assert!(s.uses.iter().any(|u| u.what == "plugin.state(persist=True)"));
        assert!(s.uses.iter().any(|u| u.what == "storage" && u.kind == UseKind::Code));
    }

    #[test]
    fn unknown_permissions_and_host_settings() {
        let manifest = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "A", "pluginVersion": "1", "apiVersion": 5,
  "permissions": ["network", "network.local", "netwrok"],
  "settings": [{"key": "server", "type": "host", "label": "Server"}]}"#;
        let main = "from wm import http\n\n\ndef render():\n    http.get(\"https://anything.example/x\")\n";
        let r = report(manifest, main);
        assert_eq!(verdict(&r, "netwrok"), Some(Verdict::Unknown));
        assert_eq!(verdict(&r, "network.local"), Some(Verdict::Needed));
        assert!(r.hosts.missing.is_empty(), "a host setting allows any server");
        assert_eq!(r.hosts.host_setting.as_deref(), Some("server"));
    }

    #[test]
    fn hosts_of_addresses() {
        assert_eq!(host_of("https://API.example.com:8443/x?y").as_deref(), Some("api.example.com"));
        assert_eq!(host_of("https://user@h.example.org/"), Some("h.example.org".into()));
        assert_eq!(host_of("https://"), None);
        assert_eq!(host_of("ftp://x"), None);
        assert!(covers("*.example.org", "example.org") && covers("*.example.org", "a.b.example.org") && !covers("*.example.org", "badexample.org"));
    }
}
