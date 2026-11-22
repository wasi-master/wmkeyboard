//! The checks that need to know what names mean. Each is written to stay
//! silent on the demo plugins: a check that fires on the code the
//! documentation teaches from is a wrong check, and the fixture tests hold
//! every one of them to that.

use std::collections::{HashMap, HashSet};

use crate::analysis::{Analysis, ScopeKind, SymbolKind, Target};
use crate::api::{self, ApiKind};
use crate::ast::*;
use crate::diagnostics::{Code, Diagnostic};
use crate::document::{Document, Host};
use crate::keyboard::{self, Decorated};
use crate::text::{closest, Span};

/// Package modules a plugin uses by name once imported from `wm`.
const WM_MODULES: &[&str] = &[
    "plugin", "http", "ai", "field", "clipboard", "location", "files", "sensors", "scan", "device", "share", "notify",
    "secrets", "reminders", "storage", "calendar", "todo", "keyboard", "clock",
];

/// The permission a package name needs, beyond what its module's entry says.
fn permission_of(short: &str) -> Option<&'static str> {
    Some(match short {
        "storage" | "secrets" => "storage",
        "clipboard.get" | "clipboard.paste" => "clipboard.read",
        "clipboard.set" | "clipboard.copy" => "clipboard.write",
        "field.text_before" | "field.text_after" | "field.selection" | "field.info" => "field.read",
        "field.replace_selection" | "field.delete" | "field.insert" => "field.write",
        _ => {
            let module = short.split('.').next().unwrap_or(short);
            let path = format!("{}.{module}", crate::package::ROOT);
            return api::model().find(&path).and_then(|e| e.permission);
        }
    })
}

/// The host of an address: `api.example.com` for `https://api.example.com:8443/x?y`.
fn host_of(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host: String = rest.chars().take_while(|c| !matches!(c, '/' | ':' | '?' | '#')).collect();
    let host = host.rsplit('@').next().unwrap_or(&host).trim_end_matches('.').to_lowercase();
    (!host.is_empty() && !host.contains('{')).then_some(host)
}

pub fn run(doc: &Document, host: &Host, out: &mut Vec<Diagnostic>) {
    let mut c = Checks { doc, a: &doc.analysis, host, out };
    let clean = doc.parses();
    if clean {
        c.undefined();
        c.locals();
    }
    c.rebinding();
    c.imports();
    c.members();
    c.contract(clean);
    c.events();
    c.widgets();
    c.keyboard(clean);
    c.flow();
}

struct Checks<'a> {
    doc: &'a Document,
    a: &'a Analysis,
    host: &'a Host,
    out: &'a mut Vec<Diagnostic>,
}

/// Every expression in the module, outermost first, nested statements included.
fn all_exprs<'a>(stmts: &'a [Stmt], f: &mut dyn FnMut(&'a Expr)) {
    walk_stmts(stmts, &mut |stmt| {
        for e in stmt_exprs(stmt) {
            walk_expr(e, f);
        }
    });
}

pub(crate) fn str_value(e: &Expr) -> Option<&str> {
    match &e.kind {
        ExprKind::Str { value: Some(v), fstring: false, .. } => Some(v),
        _ => None,
    }
}

impl<'a> Checks<'a> {
    fn add(&mut self, code: Code, span: Span, args: &[&str]) {
        self.out.push(Diagnostic::new(code, span, args));
    }

    // ---- names -------------------------------------------------------------

    fn undefined(&mut self) {
        let a = self.a;
        for o in a.unresolved() {
            let name = crate::text::slice(self.doc.units(), o.span);
            let name = name.as_str();
            if api::REMOVED_BUILTINS.contains(&name) {
                self.add(Code::RemovedBuiltin, o.span, &[name]);
            } else if api::MISSING_BUILTINS.contains(&name) {
                self.add(Code::MissingBuiltin, o.span, &[name]);
            } else if api::MODULES.contains(&name) {
                self.add(Code::ModuleNotImported, o.span, &[name]);
            } else if WM_MODULES.contains(&name) && self.host.api != Some(4) {
                self.add(Code::WmNotImported, o.span, &[name]);
            } else {
                let mut known: Vec<String> = a.visible_at(o.span.start).iter().map(|s| s.name.clone()).collect();
                known.extend(api::API_GLOBALS.iter().map(|s| s.to_string()));
                for e in api::model().children("") {
                    if matches!(e.kind, ApiKind::Builtin | ApiKind::Exception) {
                        known.push(e.path.to_string());
                    }
                }
                match closest(name, known.iter().map(String::as_str)) {
                    Some(near) => self.add(Code::NameTypo, o.span, &[name, &near]),
                    None => self.add(Code::UndefinedName, o.span, &[name]),
                }
            }
        }
    }

    /// Unused locals and parameters, and assignments that were meant for a module variable.
    fn locals(&mut self) {
        let a = self.a;
        let hooks: HashSet<usize> = api::CONTRACT.iter().filter_map(|h| a.top_function(h)).map(|f| f.scope).collect();
        for s in &a.symbols {
            let scope = &a.scopes[s.scope];
            if scope.kind != ScopeKind::Function || s.name.starts_with('_') {
                continue;
            }
            match s.kind {
                SymbolKind::Variable => {
                    if let Some(module) = a.module_symbol(&s.name) {
                        // Read before it is first assigned, counting `x = x + 1` and `x += 1`,
                        // whose read runs first though it is written after.
                        let lines = &self.doc.lines;
                        let declared_on = lines.line_of(s.declaration.start);
                        let read_first = s.reads.first().is_some_and(|r| lines.line_of(r.start) <= declared_on);
                        if module.kind == SymbolKind::Variable && (read_first || s.reads.is_empty()) {
                            self.add(Code::AccidentalGlobal, s.declaration, &[&s.name]);
                            continue;
                        }
                    }
                    if s.reads.is_empty() && !s.unpacked_only {
                        self.add(Code::UnusedLocal, s.declaration, &[&s.name]);
                    }
                }
                SymbolKind::Function | SymbolKind::ExceptVariable => {
                    if s.reads.is_empty() {
                        self.add(Code::UnusedLocal, s.declaration, &[&s.name]);
                    }
                }
                SymbolKind::Parameter => {
                    let function = scope.function.map(|f| &a.functions[f]);
                    let skip = hooks.contains(&s.scope) || function.is_some_and(|f| f.lambda);
                    if !skip && s.reads.is_empty() {
                        self.add(Code::UnusedParameter, s.declaration, &[&s.name]);
                    }
                }
                _ => {}
            }
        }
    }

    /// Assigning `wm` or `ui`, or hiding a builtin.
    fn rebinding(&mut self) {
        let a = self.a;
        for s in &a.symbols {
            // `from wm import ui` names the API 5 module; that is the point, not a clash.
            let from_wm = s.kind == SymbolKind::Import && s.import.as_ref().is_some_and(|(m, _)| m == "wm");
            if api::API_GLOBALS.contains(&s.name.as_str()) && s.kind != SymbolKind::Parameter && !from_wm {
                if s.scope == 0 {
                    self.add(Code::ReplacesApi, s.declaration, &[&s.name]);
                }
            } else if api::is_builtin(&s.name) && s.kind != SymbolKind::Import {
                self.add(Code::ShadowsBuiltin, s.declaration, &[&s.name]);
            }
        }
    }

    fn imports(&mut self) {
        let doc = self.doc;
        walk_stmts(&doc.module.body, &mut |stmt| match &stmt.kind {
            StmtKind::Import(names) => {
                for n in names {
                    let root = n.name.split('.').next().unwrap_or("");
                    if !api::MODULES.contains(&root) && !api::API_GLOBALS.contains(&root) && !self.importable(&n.name) {
                        self.out.push(Diagnostic::new(Code::ImportNotAvailable, n.span, &[&n.name]));
                    }
                }
            }
            StmtKind::ImportFrom { module, module_span, names, .. } => {
                if module.starts_with('.') || self.importable(module) {
                    return;
                }
                let package = module.starts_with("wm.") && api::import_path(module, None).starts_with(crate::package::ROOT);
                if !api::MODULES.contains(&module.as_str()) && !api::API_GLOBALS.contains(&module.as_str()) && !package {
                    self.out.push(Diagnostic::new(Code::ImportNotAvailable, *module_span, &[module]));
                    return;
                }
                for n in names {
                    // An API 5 package name: there, or a typo of one.
                    if module == "wm" || module.starts_with("wm.") {
                        let path = api::import_path(module, Some(&n.name));
                        if path.starts_with(crate::package::ROOT) || (module == "wm" && api::WM_PACKAGE.contains(&n.name.as_str())) {
                            continue;
                        }
                        if module != "wm" {
                            let base = api::import_path(module, None);
                            self.member(&base, &n.name, &path, n.span);
                            continue;
                        }
                    }
                    let full = format!("{module}.{}", n.name);
                    self.member(module, &n.name, &full, n.span);
                }
            }
            _ => {}
        });
    }

    /// A standard library module written in Python, or one of the plugin's own.
    fn importable(&self, module: &str) -> bool {
        api::LIBRARY.contains(&module) || self.host.modules.as_ref().is_some_and(|m| m.iter().any(|x| x == module))
    }

    // ---- members of the API ------------------------------------------------

    /// The API path a chain of names means, when it starts at `wm`, `ui` or an imported module.
    fn path_of(&self, e: &Expr) -> Option<String> {
        match &e.kind {
            ExprKind::Name(n) => match self.a.occurrence_at(e.span.start).map(|o| o.target) {
                Some(Target::Api) => Some(n.clone()),
                Some(Target::Symbol(id)) => {
                    let s = &self.a.symbols[id];
                    match &s.import {
                        Some((module, None)) if api::MODULES.contains(&module.as_str()) => Some(module.clone()),
                        // The API 5 package, modelled from its own source.
                        Some((module, member)) if module == "wm" || module.starts_with("wm.") => {
                            let path = api::import_path(module, member.as_deref());
                            path.starts_with(crate::package::ROOT).then_some(path)
                        }
                        Some((module, Some(member))) if api::MODULES.contains(&module.as_str()) || module == "wm" => {
                            Some(format!("{module}.{member}"))
                        }
                        _ => None,
                    }
                }
                _ => None,
            },
            ExprKind::Attribute { value, attr, .. } => {
                let base = self.path_of(value)?;
                let full = format!("{base}.{attr}");
                api::model().find(&full).map(|_| full)
            }
            _ => None,
        }
    }

    fn members(&mut self) {
        let mut found: Vec<(String, String, String, Span)> = Vec::new();
        all_exprs(&self.doc.module.body, &mut |e| {
            if let ExprKind::Attribute { value, attr, attr_span } = &e.kind {
                if let Some(base) = self.path_of(value) {
                    let is_namespace = api::model().find(&base).is_some_and(|b| b.kind == ApiKind::Module);
                    if is_namespace {
                        found.push((base.clone(), attr.clone(), format!("{base}.{attr}"), *attr_span));
                    }
                }
            }
        });
        for (base, attr, full, span) in found {
            self.member(&base, &attr, &full, span);
        }
    }

    fn member(&mut self, base: &str, attr: &str, full: &str, span: Span) {
        let model = api::model();
        if model.find(full).is_some() {
            match full {
                "wm.storage" if self.host.storage == Some(false) => self.add(Code::StorageNotDeclared, span, &[]),
                "wm.reminders" if self.host.reminders == Some(false) => self.add(Code::RemindersNotDeclared, span, &[]),
                "wm.ui.set_input" if self.host.panel == Some(false) => self.add(Code::PanelOnly, span, &[full]),
                _ => {}
            }
            return;
        }
        if let Some(instead) = api::use_instead(full) {
            self.add(Code::UseInstead, span, &[full, instead]);
        } else if base == "wm" && api::NEVER.contains(&attr) {
            self.add(Code::NeverInApi, span, &[full]);
        } else {
            let children = model.children(base);
            match closest(attr, children.iter().map(|e| e.name())) {
                Some(near) => self.add(Code::MemberTypo, span, &[full, &format!("{base}.{near}")]),
                None => self.add(Code::UnknownMember, span, &[full]),
            }
        }
    }

    // ---- the plugin contract -----------------------------------------------

    fn contract(&mut self, clean: bool) {
        let a = self.a;
        let units = self.doc.units();
        let first_line = self.doc.lines.trimmed(units, 1);
        let first_line = if first_line.is_empty() { Span::new(0, self.doc.lines.end(units, 0)) } else { first_line };
        let defined = |name: &str| a.module_symbol(name).is_some();
        if clean && self.host.panel == Some(true) && !defined("render") {
            self.add(Code::MissingRender, first_line, &[]);
        }
        let has_actions = self.host.actions.as_ref().is_some_and(|v| !v.is_empty());
        // API 5 answers actions with @plugin.action; action_unanswered covers it.
        let package = self.host.api5() || keyboard::imports_wm(a);
        if clean && has_actions && !defined("transform") && !package {
            self.add(Code::MissingTransform, first_line, &[]);
        }
        if let Some(f) = a.top_function("render") {
            if f.required() > 0 {
                self.add(Code::RenderParameters, f.name_span, &[]);
            }
            // API 5 draws with `with ui.column():` blocks and returns nothing.
            let wm_package = a.symbols.iter().any(|s| s.import.as_ref().is_some_and(|(m, _)| m == "wm"));
            if !f.returns_value && !wm_package {
                self.add(Code::RenderReturnsNothing, f.name_span, &[]);
            }
        }
        if let Some(f) = a.top_function("on_event") {
            if !f.accepts(1) {
                self.add(Code::OnEventSignature, f.name_span, &[]);
            }
        }
        if let Some(f) = a.top_function("transform") {
            if !f.accepts(2) && !f.accepts(1) {
                self.add(Code::TransformSignature, f.name_span, &[]);
            }
            if self.host.selection == Some(false) {
                self.add(Code::SelectionNotDeclared, f.name_span, &[]);
            }
        }
    }

    /// The body of a top-level `def` of this name.
    fn hook_body(&self, name: &str) -> Option<(&'a [Stmt], &'a Params)> {
        self.doc.module.body.iter().find_map(|s| match &s.kind {
            StmtKind::Def { name: n, body, params, .. } if n == name => Some((body.as_slice(), params)),
            _ => None,
        })
    }

    /// `e.type == "click"`, `e["id"] == "add"`, and the same through a local
    /// holding `e["type"]`, inside on_event; `action == "rot13"` inside transform.
    fn events(&mut self) {
        let ids = self.widget_ids();
        if let (Some((body, _)), Some((event, aliases))) = (self.hook_body("on_event"), event_context(&self.doc.module)) {
            {
                let mut found = Vec::new();
                compared_literals(body, &mut |subject, literal, span| {
                    let field = event_field(subject, &event).or_else(|| match &subject.kind {
                        ExprKind::Name(n) => aliases.get(n).copied(),
                        _ => None,
                    });
                    if let Some(field) = field {
                        found.push((field, literal.to_string(), span));
                    }
                });
                for (field, value, span) in found {
                    if field == "type" {
                        if !api::EVENT_TYPES.contains(&value.as_str()) {
                            match closest(&value, api::EVENT_TYPES.iter().copied()) {
                                Some(near) => self.add(Code::EventTypeTypo, span, &[&value, &near]),
                                None => self.add(Code::UnknownEventType, span, &[&value]),
                            }
                        }
                    } else if let Some(ids) = &ids {
                        if !ids.contains(&value) {
                            self.add(Code::UnknownEventId, span, &[&value]);
                        }
                    }
                }
            }
        }
        if let (Some((body, params)), Some(actions)) = (self.hook_body("transform"), self.host.actions.clone()) {
            if let Some(action) = params.params.get(1) {
                let action = action.name.clone();
                let mut found = Vec::new();
                compared_literals(body, &mut |subject, literal, span| {
                    if matches!(&subject.kind, ExprKind::Name(n) if *n == action) {
                        found.push((literal.to_string(), span));
                    }
                });
                for (value, span) in found {
                    if !actions.contains(&value) {
                        match closest(&value, actions.iter().map(String::as_str)) {
                            Some(near) => self.add(Code::ActionTypo, span, &[&value, &near]),
                            None => self.add(Code::UnknownAction, span, &[&value]),
                        }
                    }
                }
            }
        }
    }

    /// The ui builder a call makes, when its callee is `ui.<name>`.
    fn ui_call<'e>(&self, e: &'e Expr) -> Option<(&'static api::UiShape, &'e [Expr], &'e [Keyword], Span)> {
        let ExprKind::Call { func, args, keywords, .. } = &e.kind else { return None };
        let ExprKind::Attribute { value, attr, .. } = &func.kind else { return None };
        let ExprKind::Name(root) = &value.kind else { return None };
        if root != "ui" || self.a.occurrence_at(value.span.start).map(|o| o.target) != Some(Target::Api) {
            return None;
        }
        let shape = api::model().ui_shape(attr)?;
        Some((shape, args, keywords, func.span))
    }

    /// Every literal widget id, or None when any id is built while the plugin runs.
    fn widget_ids(&self) -> Option<HashSet<String>> {
        let mut ids = HashSet::new();
        let mut dynamic = false;
        all_exprs(&self.doc.module.body, &mut |e| {
            if let Some((shape, args, keywords, _)) = self.ui_call(e) {
                if let Some(id) = id_arg(shape, args, keywords) {
                    match str_value(id) {
                        Some(v) => {
                            ids.insert(v.to_string());
                        }
                        None => dynamic = true,
                    }
                } else if args.iter().any(|a| matches!(a.kind, ExprKind::Starred(_))) {
                    dynamic = true;
                }
            }
        });
        (!dynamic).then_some(ids)
    }

    fn widgets(&mut self) {
        let mut calls = Vec::new();
        all_exprs(&self.doc.module.body, &mut |e| {
            if let Some(call) = self.ui_call(e) {
                calls.push(call);
            }
        });
        let mut seen_ids: HashSet<String> = HashSet::new();
        for (shape, args, keywords, callee) in calls {
            let builder = shape.name;
            for k in keywords {
                if shape.param(&k.name).is_none() {
                    match closest(&k.name, shape.params.iter().map(|p| p.name)) {
                        Some(near) => self.add(Code::UiArgumentTypo, k.name_span, &[&k.name, &near]),
                        None => self.add(Code::UnknownUiArgument, k.name_span, &[&k.name, builder]),
                    }
                }
            }
            let starred = args.iter().any(|a| matches!(a.kind, ExprKind::Starred(_) | ExprKind::DoubleStarred(_)));
            if !starred {
                if !shape.children && args.len() > shape.params.len() {
                    let max = shape.params.len().to_string();
                    self.add(Code::TooManyUiArguments, callee, &[builder, &max]);
                }
                let missing: Vec<&str> = shape
                    .params
                    .iter()
                    .enumerate()
                    .filter(|(i, p)| p.required && *i >= args.len() && !keywords.iter().any(|k| k.name == p.name))
                    .map(|(_, p)| p.name)
                    .collect();
                if !missing.is_empty() {
                    self.add(Code::UiMissingArgument, callee, &[builder, &missing.join(", ")]);
                }
                if builder == "tabs" && args.len() > api::MAX_TABS + 1 {
                    self.add(Code::TooManyTabs, callee, &[]);
                }
            }
            for (i, p) in shape.params.iter().enumerate() {
                if p.values.is_empty() {
                    continue;
                }
                let given = keywords.iter().find(|k| k.name == p.name).map(|k| &k.value).or_else(|| (!shape.children).then(|| args.get(i)).flatten());
                let Some(given) = given else { continue };
                let Some(value) = str_value(given) else { continue };
                if !p.values.contains(&value) {
                    match closest(value, p.values.iter().copied()) {
                        Some(near) => self.add(Code::StyleTypo, given.span, &[value, &near]),
                        None => self.add(Code::UnknownStyle, given.span, &[value, builder]),
                    }
                }
            }
            if let Some(id) = id_arg(shape, args, keywords) {
                if let Some(v) = str_value(id) {
                    if shape.has_id && !seen_ids.insert(v.to_string()) {
                        self.add(Code::DuplicateId, id.span, &[v]);
                    }
                }
            }
        }
    }

    // ---- the keyboard ------------------------------------------------------

    fn keyboard(&mut self, clean: bool) {
        let a = self.a;
        let h = self.host;
        let decorated = keyboard::decorated(&self.doc.module, a);
        self.permissions();
        self.package_calls();
        for d in &decorated {
            self.decorator(d);
        }
        self.settings_used();
        let package = h.api5() || keyboard::imports_wm(a);
        if !clean || !package {
            return;
        }
        let units = self.doc.units();
        let first_line = self.doc.lines.trimmed(units, 1);
        let anchor = if first_line.is_empty() { Span::new(0, self.doc.lines.end(units, 0)) } else { first_line };
        let answered: HashSet<(String, String)> = decorated
            .iter()
            .filter(|d| d.what == "plugin.provider")
            .filter_map(|d| d.provider().map(|(t, i, _)| (t, i)))
            .collect();
        for (tool, id) in h.providers.iter().flatten() {
            if keyboard::provider_tool(tool).is_some() && !answered.contains(&(tool.clone(), id.clone())) {
                self.add(Code::ProviderUnanswered, anchor, &[tool, id]);
            }
        }
        if a.module_symbol("transform").is_none() {
            let actions: HashSet<&str> = decorated.iter().filter(|d| d.what == "plugin.action").filter_map(|d| d.literal(0, "id").map(|(v, _)| v)).collect();
            for id in h.actions.iter().flatten() {
                if !actions.contains(id.as_str()) {
                    self.add(Code::ActionUnanswered, anchor, &[id]);
                }
            }
        }
    }

    /// Package names whose permission plugin.json does not declare.
    fn permissions(&mut self) {
        let h = self.host;
        if h.permissions.is_none() {
            return;
        }
        let mut found: Vec<(String, &'static str, Span)> = Vec::new();
        let mut seen: HashSet<&'static str> = HashSet::new();
        let missing = |perm: &str| h.has_permission(perm) == Some(false);
        walk_stmts(&self.doc.module.body, &mut |stmt| {
            if let StmtKind::ImportFrom { module, module_span, names, .. } = &stmt.kind {
                if module == "wm" {
                    for n in names {
                        if let Some(p) = permission_of(&n.name).filter(|p| missing(p)) {
                            found.push((n.name.clone(), p, n.span));
                        }
                    }
                } else if let Some(rest) = module.strip_prefix("wm.") {
                    if let Some(p) = permission_of(rest).filter(|p| missing(p)) {
                        found.push((rest.to_string(), p, *module_span));
                    }
                }
            }
        });
        // Calls that need more than their module: clipboard.get, field.text_before, plugin.state(persist=True).
        all_exprs(&self.doc.module.body, &mut |e| match &e.kind {
            ExprKind::Attribute { attr_span, .. } => {
                if let Some(path) = keyboard::api_path(self.a, e) {
                    let short = keyboard::short(&path);
                    if short.contains('.') && (short.starts_with("clipboard.") || short.starts_with("field.")) {
                        if let Some(p) = permission_of(short).filter(|p| missing(p)) {
                            found.push((short.to_string(), p, *attr_span));
                        }
                    }
                }
            }
            ExprKind::Call { func, keywords, .. } => {
                let persist = keywords.iter().any(|k| k.name == "persist" && matches!(k.value.kind, ExprKind::Const("True")));
                if persist && keyboard::api_path(self.a, func).is_some_and(|p| keyboard::short(&p) == "plugin.state") && missing("storage") {
                    found.push(("plugin.state(persist=True)".into(), "storage", func.span));
                }
            }
            _ => {}
        });
        for (name, perm, span) in found {
            // One mark per permission for a module; every call for the narrower ones.
            if name.contains('.') || name.contains('(') || seen.insert(perm) {
                self.add(Code::PermissionMissing, span, &[&name, perm]);
            }
        }
    }

    /// Calls into the package: arguments it does not take, values it does not
    /// know, and addresses on hosts plugin.json does not list.
    fn package_calls(&mut self) {
        let mut found: Vec<(Code, Span, Vec<String>)> = Vec::new();
        let h = self.host;
        all_exprs(&self.doc.module.body, &mut |e| {
            let ExprKind::Call { func, args, keywords, .. } = &e.kind else { return };
            let Some(path) = keyboard::api_path(self.a, func) else { return };
            if !path.starts_with(crate::package::ROOT) {
                return;
            }
            let short = keyboard::short(&path).to_string();
            let Some(sig) = api::model().find(&path).and_then(|x| x.signature) else { return };
            let params = keyboard::sig_params(sig);
            // http.get(url, **options) hands its options to http.request: check them against it.
            let mut accepted = params.clone();
            if short.starts_with("http.") && short != "http.request" && keyboard::takes_any_keyword(&params) {
                if let Some(request) = api::model().find(&format!("{}.http.request", crate::package::ROOT)).and_then(|x| x.signature) {
                    accepted.retain(|p| p.star != 2);
                    accepted.extend(keyboard::sig_params(request).into_iter().filter(|p| p.name != "method"));
                }
            }
            if !keyboard::takes_any_keyword(&accepted) {
                let names: Vec<&str> = accepted.iter().filter(|p| p.star == 0).map(|p| p.name.as_str()).collect();
                for k in keywords {
                    if !names.contains(&k.name.as_str()) {
                        match closest(&k.name, names.iter().copied()) {
                            Some(near) => found.push((Code::ArgumentTypo, k.name_span, vec![k.name.clone(), near])),
                            None => found.push((Code::UnknownArgument, k.name_span, vec![k.name.clone(), short.clone()])),
                        }
                    }
                }
            }
            // Literal values of parameters that take a few.
            let positional: Vec<&keyboard::SigParam> = params.iter().take_while(|p| p.star == 0).collect();
            for p in params.iter().filter(|p| p.star == 0) {
                let Some(values) = keyboard::values_for(&path, &p.name) else { continue };
                if keyboard::is_open_set(values) || (short == "plugin.provider" && p.name == "tool") {
                    continue;
                }
                let given = keywords.iter().find(|k| k.name == p.name).map(|k| &k.value).or_else(|| {
                    let i = positional.iter().position(|x| x.name == p.name)?;
                    args.get(i)
                });
                let Some(given) = given else { continue };
                let Some(value) = str_value(given) else { continue };
                if !values.contains(&value) {
                    match closest(value, values.iter().copied()) {
                        Some(near) => found.push((Code::ValueTypo, given.span, vec![value.into(), near])),
                        None => found.push((Code::UnknownValue, given.span, vec![value.into(), values.join(", ")])),
                    }
                }
            }
            // An address the plugin may not reach.
            if short.starts_with("http.") && h.hosts.is_some() {
                let index = if short == "http.request" { 1 } else { 0 };
                let url = keywords.iter().find(|k| k.name == "url").map(|k| &k.value).or_else(|| args.get(index));
                if let Some(url) = url {
                    let text = str_value(url).map(str::to_string).or_else(|| self.constant_str(url));
                    if let Some(host) = text.as_deref().and_then(host_of) {
                        if h.allows_host(&host) == Some(false) {
                            found.push((Code::HostMissing, url.span, vec![host]));
                        }
                    }
                }
            }
        });
        for (code, span, args) in found {
            let args: Vec<&str> = args.iter().map(String::as_str).collect();
            self.add(code, span, &args);
        }
    }

    /// The string a module-level name is bound to once and never again: `FORECAST` for `FORECAST = "https://..."`.
    fn constant_str(&self, e: &Expr) -> Option<String> {
        let ExprKind::Name(_) = &e.kind else { return None };
        let Target::Symbol(id) = self.a.occurrence_at(e.span.start)?.target else { return None };
        let s = &self.a.symbols[id];
        if s.scope != 0 || !s.writes.is_empty() || s.kind != SymbolKind::Variable {
            return None;
        }
        self.doc.module.body.iter().find_map(|stmt| match &stmt.kind {
            StmtKind::Assign { targets, value } if targets.len() == 1 && targets[0].span == s.declaration => str_value(value).map(str::to_string),
            _ => None,
        })
    }

    fn decorator(&mut self, d: &Decorated) {
        let h = self.host;
        match d.what.as_str() {
            "plugin.provider" => {
                let Some((tool, id, span)) = d.provider() else { return };
                if keyboard::provider_tool(&tool).is_none() {
                    match closest(&tool, keyboard::provider_tool_names()) {
                        Some(near) => self.add(Code::ProviderToolTypo, span, &[&tool, &near]),
                        None => self.add(Code::UnknownProviderTool, span, &[&tool]),
                    }
                } else if let Some(declared) = &h.providers {
                    if !declared.iter().any(|(t, i)| *t == tool && *i == id) {
                        let at = d.literal(1, "id").map(|(_, s)| span.cover(s)).unwrap_or(span);
                        self.add(Code::ProviderNotDeclared, at, &[&tool, &id]);
                    }
                }
            }
            "plugin.action" => {
                let (Some((id, span)), Some(actions)) = (d.literal(0, "id"), &h.actions) else { return };
                if !actions.iter().any(|a| a == id) {
                    match closest(id, actions.iter().map(String::as_str)) {
                        Some(near) => self.add(Code::ActionTypo, span, &[id, &near]),
                        None => self.add(Code::UnknownAction, span, &[id]),
                    }
                }
            }
            "plugin.keyword" => {
                let (Some((word, span)), Some(keyword)) = (d.literal(0, "word"), &h.keyword) else { return };
                if keyword != word {
                    self.add(Code::KeywordNotDeclared, span, &[word]);
                }
            }
            "plugin.setting_changed" => {
                if let Some((key, span)) = d.literal(0, "key") {
                    let key = key.to_string();
                    self.setting(&key, span);
                }
            }
            _ => {}
        }
    }

    /// `plugin.settings.units`, `plugin.settings["units"]` and `plugin.settings.get("units")`.
    fn settings_used(&mut self) {
        if self.host.settings.is_none() {
            return;
        }
        let mut found: Vec<(String, Span)> = Vec::new();
        let is_settings = |e: &Expr| keyboard::api_path(self.a, e).is_some_and(|p| keyboard::short(&p) == "plugin.settings");
        all_exprs(&self.doc.module.body, &mut |e| match &e.kind {
            ExprKind::Attribute { value, attr, attr_span } if is_settings(value) && !matches!(attr.as_str(), "get" | "set" | "reset") => {
                found.push((attr.clone(), *attr_span));
            }
            ExprKind::Subscript { value, index } if is_settings(value) => {
                if let Some(v) = str_value(index) {
                    found.push((v.to_string(), index.span));
                }
            }
            ExprKind::Call { func, args, .. } => {
                if let ExprKind::Attribute { value, attr, .. } = &func.kind {
                    if is_settings(value) && matches!(attr.as_str(), "get" | "set" | "reset") {
                        if let Some(first) = args.first() {
                            if let Some(v) = str_value(first) {
                                found.push((v.to_string(), first.span));
                            }
                        }
                    }
                }
            }
            _ => {}
        });
        for (key, span) in found {
            self.setting(&key, span);
        }
    }

    fn setting(&mut self, key: &str, span: Span) {
        let Some(settings) = &self.host.settings else { return };
        if settings.iter().any(|s| s.key == key) {
            return;
        }
        match closest(key, settings.iter().map(|s| s.key.as_str())) {
            Some(near) => self.add(Code::SettingTypo, span, &[key, &near]),
            None => self.add(Code::UnknownSetting, span, &[key]),
        }
    }

    // ---- flow --------------------------------------------------------------

    fn flow(&mut self) {
        let units = self.doc.units();
        let mut found = Vec::new();
        walk_stmts(&self.doc.module.body, &mut |stmt| {
            if let StmtKind::While { test, body, .. } = &stmt.kind {
                let forever = match &test.kind {
                    ExprKind::Const("True") => true,
                    ExprKind::Number => {
                        let text = crate::text::slice(units, test.span);
                        !text.chars().all(|c| c == '0' || c == '.' || c == '_')
                    }
                    _ => false,
                };
                if forever && !exits(body, true) {
                    found.push((Code::LoopNeverEnds, Span::new(stmt.span.start, test.span.end)));
                }
            }
        });
        unreachable(&self.doc.module.body, &mut found);
        for block in blocks_of(&self.doc.module.body) {
            unreachable(block, &mut found);
        }
        for (code, span) in found {
            self.add(code, span, &[]);
        }
    }
}

/// Every block nested anywhere in [stmts].
fn blocks_of(stmts: &[Stmt]) -> Vec<&Vec<Stmt>> {
    let mut out = Vec::new();
    walk_stmts(stmts, &mut |s| out.extend(stmt_blocks(s)));
    out
}

fn unreachable(block: &[Stmt], found: &mut Vec<(Code, Span)>) {
    if let Some(k) = block.iter().position(|s| matches!(s.kind, StmtKind::Return(_) | StmtKind::Raise(_) | StmtKind::Break | StmtKind::Continue)) {
        if k + 1 < block.len() {
            found.push((Code::Unreachable, block[k + 1].span.cover(block[block.len() - 1].span)));
        }
    }
}

/// Whether anything in [body] leaves the loop it belongs to: a `break` not
/// inside an inner loop, or a `return` or `raise` anywhere outside inner functions.
fn exits(body: &[Stmt], own_loop: bool) -> bool {
    body.iter().any(|s| match &s.kind {
        StmtKind::Break => own_loop,
        StmtKind::Return(_) | StmtKind::Raise(_) => true,
        StmtKind::Def { .. } => false,
        StmtKind::While { body, orelse, .. } | StmtKind::For { body, orelse, .. } => exits(body, false) || exits(orelse, own_loop),
        _ => stmt_blocks(s).iter().any(|b| exits(b, own_loop)),
    })
}

/// `type` or `id` when [e] reads that field of the event named [event]:
/// `e.type`, `e["type"]` or `e.get("type")`.
pub(crate) fn event_field(e: &Expr, event: &str) -> Option<&'static str> {
    let field = |name: &str| match name {
        "type" => Some("type"),
        "id" => Some("id"),
        _ => None,
    };
    let is_event = |v: &Expr| matches!(&v.kind, ExprKind::Name(n) if n == event);
    match &e.kind {
        ExprKind::Attribute { value, attr, .. } if is_event(value) => field(attr),
        ExprKind::Subscript { value, index } if is_event(value) => str_value(index).and_then(field),
        ExprKind::Call { func, args, .. } => match &func.kind {
            ExprKind::Attribute { value, attr, .. } if attr == "get" && is_event(value) => args.first().and_then(str_value).and_then(field),
            _ => None,
        },
        _ => None,
    }
}

/// Calls [f] with each side compared with `==`, `!=`, `in` or `not in` to a
/// string literal (or a tuple, list or set of them), and the literal's span.
fn compared_literals(body: &[Stmt], f: &mut dyn FnMut(&Expr, &str, Span)) {
    all_exprs(body, &mut |e| {
        let ExprKind::Compare { left, ops } = &e.kind else { return };
        let mut prev: &Expr = left;
        for (op, right) in ops {
            match *op {
                "==" | "!=" => {
                    if let Some(v) = str_value(right) {
                        f(prev, v, right.span);
                    } else if let Some(v) = str_value(prev) {
                        f(right, v, prev.span);
                    }
                }
                "in" | "not in" => {
                    if let ExprKind::Tuple(items) | ExprKind::List(items) | ExprKind::Set(items) = &right.kind {
                        for item in items {
                            if let Some(v) = str_value(item) {
                                f(prev, v, item.span);
                            }
                        }
                    }
                }
                _ => {}
            }
            prev = right;
        }
    });
}

/// The expression given as a widget's id.
fn id_arg<'e>(shape: &api::UiShape, args: &'e [Expr], keywords: &'e [Keyword]) -> Option<&'e Expr> {
    if let Some(k) = keywords.iter().find(|k| k.name == "id") {
        return Some(&k.value);
    }
    let index = shape.params.iter().position(|p| p.name == "id")?;
    let arg = args.get(index)?;
    (!matches!(arg.kind, ExprKind::Starred(_))).then_some(arg)
}

/// The event parameter of a top-level `on_event`, and the locals in it that
/// hold one field of the event (`kind = e["type"]`), by name.
pub(crate) fn event_context(module: &Module) -> Option<(String, HashMap<String, &'static str>)> {
    let (body, params) = module.body.iter().find_map(|s| match &s.kind {
        StmtKind::Def { name, body, params, .. } if name == "on_event" => Some((body, params)),
        _ => None,
    })?;
    let event = params.params.first()?.name.clone();
    let mut aliases: HashMap<String, &'static str> = HashMap::new();
    let mut spoiled: HashSet<String> = HashSet::new();
    walk_stmts(body, &mut |stmt| {
        if let StmtKind::Assign { targets, value } = &stmt.kind {
            for t in targets {
                if let ExprKind::Name(n) = &t.kind {
                    match (targets.len(), event_field(value, &event)) {
                        (1, Some(field)) => {
                            aliases.insert(n.clone(), field);
                        }
                        _ => {
                            spoiled.insert(n.clone());
                        }
                    }
                }
            }
        }
    });
    aliases.retain(|n, _| !spoiled.contains(n));
    Some((event, aliases))
}
