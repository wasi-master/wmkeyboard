//! What can be typed at the caret.
//!
//! Read from tokens, because the script is broken while it is being typed: a
//! member list after `wm.` or the arguments of `ui.button(` must work in a
//! file that does not parse. Names come from the analysis, which the tolerant
//! parser builds for a broken file too, and an older analysis can be passed in
//! when the caller has one.

use std::collections::{HashMap, HashSet};

use crate::analysis::{Analysis, SymbolKind, Target};
use crate::ast::{walk_stmts, ExprKind, StmtKind};
use crate::api::{self, ApiEntry, ApiKind};
use crate::checks::event_context;
use crate::document::{Document, Host};
use crate::highlight::is_constant_name;
use crate::infer;
use crate::keyboard::{self, SigParam};
use crate::lexer::{fstring_fields, lex_range, string_value, TokenKind, Tokens, CONTINUATION, FSTRING, UNTERMINATED};
use crate::text::{slice, Span};

pub const MAX_ITEMS: usize = 50;
const MAX_BACK_TOKENS: usize = 3000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ItemKind {
    Variable,
    Parameter,
    Function,
    Method,
    Module,
    Constant,
    Field,
    Keyword,
    Snippet,
    Value,
}

impl ItemKind {
    pub fn name(self) -> &'static str {
        match self {
            ItemKind::Variable => "variable",
            ItemKind::Parameter => "parameter",
            ItemKind::Function => "function",
            ItemKind::Method => "method",
            ItemKind::Module => "module",
            ItemKind::Constant => "constant",
            ItemKind::Field => "field",
            ItemKind::Keyword => "keyword",
            ItemKind::Snippet => "snippet",
            ItemKind::Value => "value",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub label: String,
    pub kind: ItemKind,
    /// What replaces [`Completions::replace`].
    pub insert: String,
    /// Where the caret lands in [insert], in UTF-16 units.
    pub caret: usize,
    pub detail: Option<String>,
    /// The API path whose doc explains it.
    pub doc_path: Option<String>,
    /// Its doc, one paragraph.
    pub doc: Option<String>,
    /// Choosing it leaves a blank with suggestions of its own: `style="|"`.
    pub reopen: bool,
    /// Ranking: 0 local, 1 module name, 2 API and members, 3 keyword or value, 4 snippet.
    rank: u8,
}

impl Item {
    fn new(label: &str, kind: ItemKind, insert: &str, rank: u8) -> Item {
        Item::template(label, kind, &format!("{insert}|"), rank)
    }

    /// From a template with `|` where the caret goes.
    fn template(label: &str, kind: ItemKind, template: &str, rank: u8) -> Item {
        let at = template.find('|').unwrap_or(template.len());
        let insert = template.replacen('|', "", 1);
        let caret = template[..at].encode_utf16().count();
        Item { label: label.to_string(), kind, insert, caret, detail: None, doc_path: None, doc: None, reopen: false, rank }
    }

    fn detail(mut self, detail: Option<&str>) -> Item {
        self.detail = detail.map(str::to_string);
        self
    }

    fn doc(mut self, path: &str) -> Item {
        self.doc_path = Some(path.to_string());
        if self.doc.is_none() {
            self.doc = api::model().find(path).map(|e| e.doc.to_string()).filter(|d| !d.is_empty());
        }
        self
    }

    fn text(mut self, doc: &str) -> Item {
        self.doc = Some(doc.to_string()).filter(|d| !d.is_empty());
        self
    }

    fn ranked(mut self, rank: u8) -> Item {
        self.rank = rank;
        self
    }

    fn reopening(mut self) -> Item {
        self.reopen = true;
        self
    }
}

#[derive(Clone, Debug)]
pub struct Completions {
    /// What a chosen item replaces: the word at the caret, or nothing there.
    pub replace: Span,
    pub items: Vec<Item>,
}

const KEYWORDS: &[&str] = &[
    "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "def", "return", "pass", "break",
    "continue", "try", "except", "finally", "raise", "import", "from", "as", "global", "del", "lambda", "True",
    "False", "None",
];

/// Label, and a template where `{i}` is the line's indentation and `|` the caret.
const SNIPPETS: &[(&str, &str)] = &[
    ("def render()", "def render():\n{i}    return ui.column(\n{i}        |\n{i}    )"),
    ("def on_event(e)", "def on_event(e):\n{i}    if e.type == \"click\":\n{i}        |"),
    ("def transform(text, action)", "def transform(text, action):\n{i}    return |text"),
    ("def ...", "def |():\n{i}    pass"),
    ("if ...", "if |:\n{i}    pass"),
    ("if ... else", "if |:\n{i}    pass\n{i}else:\n{i}    pass"),
    ("for ... in", "for item in |:\n{i}    pass"),
    ("for i in range", "for i in range(|):\n{i}    pass"),
    ("while ...", "while |:\n{i}    pass"),
    ("try ... except", "try:\n{i}    |\n{i}except ValueError as error:\n{i}    pass"),
];

/// Snippets for a plugin written for the `wm` package; `{p}` is the name
/// `plugin` is imported as, `{u}` the name of `ui`.
const SNIPPETS5: &[(&str, &str)] = &[
    ("def render()", "def render():\n{i}    with {u}.column():\n{i}        {u}.label(\"|\")"),
    ("action ...", "@{p}.action(\"|\")\n{i}def action(text):\n{i}    return text"),
    ("keyword ...", "@{p}.keyword(\"|\")\n{i}def from_keyword(text):\n{i}    pass"),
    ("command ...", "@{p}.command(\"|\", icon=\"refresh\")\n{i}def command():\n{i}    {p}.refresh()"),
    ("every ...", "@{p}.every(minutes=|)\n{i}def tick():\n{i}    pass"),
    ("on_open ...", "@{p}.on_open\n{i}def opened():\n{i}    |"),
    ("setting_changed ...", "@{p}.setting_changed(\"|\")\n{i}def changed(value):\n{i}    pass"),
    ("state = plugin.state(...)", "state = {p}.state(|)"),
    ("def ...", "def |():\n{i}    pass"),
    ("if ...", "if |:\n{i}    pass"),
    ("if ... else", "if |:\n{i}    pass\n{i}else:\n{i}    pass"),
    ("for ... in", "for item in |:\n{i}    pass"),
    ("for i in range", "for i in range(|):\n{i}    pass"),
    ("while ...", "while |:\n{i}    pass"),
    ("try ... except", "try:\n{i}    |\n{i}except ValueError as error:\n{i}    pass"),
    ("try ... except http.HTTPError", "try:\n{i}    response = http.get(|)\n{i}    response.raise_for_status()\n{i}except http.HTTPError as error:\n{i}    pass"),
];

/// The decorators of `wm.plugin`, as typed after `@`: name, then the call with `|` for the caret.
const DECORATORS: &[(&str, &str)] = &[
    ("action", "action(\"|\")"),
    ("provider", "provider(\"|\")"),
    ("keyword", "keyword(\"|\")"),
    ("command", "command(\"|\")"),
    ("every", "every(minutes=|)"),
    ("on_open", "on_open|"),
    ("on_close", "on_close|"),
    ("setting_changed", "setting_changed(\"|\")"),
];

/// Completions at [caret]. [explicit] is true when the author asked for the
/// list: then an empty word lists everything that fits. While typing, a name
/// list waits for the first letter, and only `.`, a call's argument list and
/// a string with known values open one straight away.
pub fn complete(doc: &Document, caret: u32, explicit: bool, host: &Host) -> Option<Completions> {
    complete_with(doc, caret, explicit, host, None)
}

/// [complete], reading names from [analysis] instead of the document's own.
pub fn complete_with(doc: &Document, caret: u32, explicit: bool, host: &Host, analysis: Option<&Analysis>) -> Option<Completions> {
    if caret > doc.len() {
        return None;
    }
    let t = &doc.tokens;
    let analysis = analysis.unwrap_or(&doc.analysis);
    if caret > 0 {
        if let Some(i) = t.index_at(caret - 1) {
            let inside = caret > t.start(i) && (caret < t.end(i) || t.has(i, UNTERMINATED));
            match t.kind(i) {
                TokenKind::Comment if caret > t.start(i) => return None,
                TokenKind::String if inside => {
                    if t.has(i, FSTRING) {
                        for field in fstring_fields(t, i) {
                            if field.touches(caret) {
                                let sub = lex_range(t.src.clone(), field.start, field.end);
                                return Context::new(doc, &sub, analysis, caret, explicit, host).code();
                            }
                        }
                        return None;
                    }
                    return Context::new(doc, t, analysis, caret, explicit, host).in_string(i);
                }
                _ => {}
            }
        }
    }
    Context::new(doc, t, analysis, caret, explicit, host).code()
}

struct Context<'a> {
    doc: &'a Document,
    t: &'a Tokens,
    a: &'a Analysis,
    caret: u32,
    explicit: bool,
    host: &'a Host,
    word_start: u32,
    word_end: u32,
    prefix: String,
}

fn is_word(c: u16) -> bool {
    if c < 128 {
        (c as u8).is_ascii_alphanumeric() || c == b'_' as u16
    } else {
        char::from_u32(c as u32).is_none_or(|ch| ch.is_alphanumeric())
    }
}

impl<'a> Context<'a> {
    fn new(doc: &'a Document, t: &'a Tokens, a: &'a Analysis, caret: u32, explicit: bool, host: &'a Host) -> Context<'a> {
        let units = doc.units();
        let mut start = caret as usize;
        while start > 0 && is_word(units[start - 1]) {
            start -= 1;
        }
        let mut end = caret as usize;
        while end < units.len() && is_word(units[end]) {
            end += 1;
        }
        let prefix = String::from_utf16_lossy(&units[start..caret as usize]);
        Context { doc, t, a, caret, explicit, host, word_start: start as u32, word_end: end as u32, prefix }
    }

    fn text(&self, i: usize) -> String {
        self.t.text(i)
    }

    // ---- code ------------------------------------------------------------

    fn code(&self) -> Option<Completions> {
        if self.prefix.chars().next().is_some_and(|c| c.is_ascii_digit()) {
            return None;
        }
        let t = self.t;
        let before = t.code_before(self.word_start);
        if let Some(b) = before {
            if t.is_op(b, "@") && self.line_first(b) == Some(b) {
                return self.decorators();
            }
            if t.is_op(b, ".") {
                return self.members(b);
            }
            if t.is_keyword(b, "def") {
                return self.finish(self.contract_names(), false);
            }
            if t.is_keyword(b, "import") {
                return self.after_import(b);
            }
            if t.is_keyword(b, "from") && self.line_first(b) == Some(b) {
                return self.finish(self.importable_modules(), false);
            }
            if t.is_keyword(b, "except") {
                let items = api::EXCEPTIONS.iter().map(|e| Item::new(e, ItemKind::Function, e, 2).doc(e)).collect();
                return self.finish(items, false);
            }
            if t.is_op(b, "(") || t.is_op(b, ",") {
                if let Some(found) = self.ui_arguments(b) {
                    return Some(found);
                }
                if let Some(found) = self.call_arguments(b) {
                    return Some(found);
                }
            }
            if t.is_op(b, "=") {
                if let Some(found) = self.callback_value(b) {
                    return Some(found);
                }
            }
        }
        if self.prefix.is_empty() && !self.explicit {
            return None;
        }
        let statement_start = before.is_none_or(|b| self.ends_statement(b));
        let items = self.names(statement_start);
        self.finish(items, true)
    }

    /// Whether a line break that ends a statement comes after token [b].
    fn ends_statement(&self, b: usize) -> bool {
        let t = self.t;
        if t.is_op(b, ";") || t.is_op(b, ":") && self.colon_ends_header(b) {
            return true;
        }
        (b + 1..t.len())
            .take_while(|k| t.start(*k) < self.word_start)
            .any(|k| t.kind(k) == TokenKind::Newline && !t.has(k, CONTINUATION))
    }

    fn colon_ends_header(&self, b: usize) -> bool {
        // `if x: |` starts a statement; `{"a": |` and `lambda x: |` do not.
        let mut depth = 0;
        let mut at = b;
        let mut steps = 0;
        while let Some(p) = self.t.prev_code(at) {
            if self.t.kind(p) == TokenKind::Newline {
                break;
            }
            match self.text(p).as_str() {
                ")" | "]" | "}" => depth += 1,
                "(" | "[" | "{" => {
                    if depth == 0 {
                        return false;
                    }
                    depth -= 1;
                }
                "lambda" if depth == 0 => return false,
                _ => {}
            }
            if self.line_first(p) == Some(p) {
                return true;
            }
            at = p;
            steps += 1;
            if steps > MAX_BACK_TOKENS {
                break;
            }
        }
        true
    }

    /// The first code token of the logical line holding [i].
    fn line_first(&self, i: usize) -> Option<usize> {
        let t = self.t;
        let mut first = i;
        let mut k = i;
        while k > 0 {
            k -= 1;
            if t.kind(k) == TokenKind::Newline && !t.has(k, CONTINUATION) {
                break;
            }
            if t.is_code(k) {
                first = k;
            }
        }
        Some(first)
    }

    fn after_import(&self, b: usize) -> Option<Completions> {
        let t = self.t;
        let first = self.line_first(b)?;
        if t.is_keyword(first, "from") {
            // The whole dotted name between `from` and `import`.
            let mut module = String::new();
            let mut at = t.next_code(first);
            while let Some(k) = at {
                if t.is_keyword(k, "import") {
                    break;
                }
                module.push_str(&self.text(k));
                at = t.next_code(k);
            }
            // `from wm import` offers the API 5 package; `from wm.ui import` its module.
            let module = if module == "wm" { crate::package::ROOT.to_string() } else { api::import_path(&module, None) };
            let items = api::model()
                .children(&module)
                .into_iter()
                .filter(|e| e.kind != ApiKind::Module || module == "wm" || module == crate::package::ROOT)
                .map(|e| self.api_item(e))
                .collect();
            return self.finish(items, false);
        }
        self.finish(self.importable_modules(), false)
    }

    /// `wm`, the six modules, and the standard library modules API 5 plugins may import.
    fn importable_modules(&self) -> Vec<Item> {
        let mut items = vec![Item::new("wm", ItemKind::Module, "wm", 1).doc(crate::package::ROOT)];
        items.extend(api::MODULES.iter().map(|m| Item::new(m, ItemKind::Module, m, 2).doc(m)));
        if self.api5() {
            items.extend(api::LIBRARY.iter().map(|m| Item::new(m, ItemKind::Module, m, 3)));
        }
        for m in self.host.modules.iter().flatten() {
            items.push(Item::new(m, ItemKind::Module, m, 1).detail(Some("your module")));
        }
        items
    }

    /// Whether the plugin is written for the `wm` package.
    fn api5(&self) -> bool {
        self.host.api5() || keyboard::imports_wm(self.a)
    }

    fn plugin_name(&self) -> String {
        keyboard::local_name(self.a, "plugin").unwrap_or_else(|| "plugin".into())
    }

    fn ui_name(&self) -> String {
        keyboard::local_name(self.a, "ui").unwrap_or_else(|| "ui".into())
    }

    /// After `@` at the start of a line: the package's decorators.
    fn decorators(&self) -> Option<Completions> {
        let plugin = self.plugin_name();
        let items = DECORATORS
            .iter()
            .map(|(name, template)| {
                let path = format!("{}.plugin.{name}", crate::package::ROOT);
                let e = api::model().find(&path);
                let mut item = Item::template(name, ItemKind::Function, &format!("{plugin}.{template}"), 2).doc(&path);
                if let Some(e) = e {
                    item = item.detail(e.signature);
                }
                if template.contains("(\"") {
                    item = item.reopening();
                }
                item.ranked(decorator_rank(name))
            })
            .collect();
        self.finish(items, true)
    }

    fn contract_names(&self) -> Vec<Item> {
        let mut out = Vec::new();
        for (name, template) in [("render", "render():|"), ("on_event", "on_event(e):|"), ("transform", "transform(text, action):|")] {
            if self.a.module_symbol(name).is_none_or(|s| self.being_typed(s.declaration)) {
                out.push(Item::template(name, ItemKind::Function, template, 2).doc(name));
            }
        }
        out
    }

    fn being_typed(&self, span: Span) -> bool {
        self.word_end > self.word_start && span.start == self.word_start && span.end == self.word_end
    }

    // ---- members ---------------------------------------------------------

    /// The names of `a.b.c` before a dot, root first, each with its token.
    fn chain_before(&self, dot: usize) -> Option<Vec<(String, usize)>> {
        let t = self.t;
        let mut parts = Vec::new();
        let mut at = t.prev_code(dot)?;
        loop {
            if t.kind(at) != TokenKind::Name {
                return if parts.is_empty() { None } else { Some(parts.into_iter().rev().collect()) };
            }
            parts.push((self.text(at), at));
            match t.prev_code(at) {
                Some(d) if t.is_op(d, ".") => match t.prev_code(d) {
                    Some(n) => at = n,
                    None => break,
                },
                _ => break,
            }
            if parts.len() > 32 {
                return None;
            }
        }
        if t.prev_code(at).is_some_and(|d| t.is_op(d, ".")) {
            return None;
        }
        Some(parts.into_iter().rev().collect())
    }

    /// The API path a chain means: `wm.storage` for `wm.storage`, `json` for `j` after `import json as j`.
    fn api_path(&self, chain: &[(String, usize)]) -> Option<String> {
        let (root, token) = chain.first()?;
        let span = self.t.span(*token);
        let base = match self.resolve(root, span) {
            Target::Api => root.clone(),
            Target::Symbol(id) => {
                let s = &self.a.symbols[id];
                match &s.import {
                    Some((module, member)) => api::import_path(module, member.as_deref()),
                    None => return None,
                }
            }
            _ => return None,
        };
        let mut path = base;
        for (name, _) in &chain[1..] {
            path = format!("{path}.{name}");
        }
        Some(path)
    }

    /// What [name] means at [span]: from the occurrence there, or by scope when the analysis is older than the text.
    fn resolve(&self, name: &str, span: Span) -> Target {
        if let Some(o) = self.a.occurrence_at(span.start) {
            if o.span == span {
                return o.target;
            }
        }
        self.a.resolve(name, self.a.scope_at(span.start))
    }

    fn members(&self, dot: usize) -> Option<Completions> {
        let t = self.t;
        let model = api::model();
        let mut items = Vec::new();
        let receiver = t.prev_code(dot);
        let mut known_type: Option<&'static str> = None;
        let chain = self.chain_before(dot);
        if let Some(chain) = &chain {
            if let Some(path) = self.api_path(chain) {
                let short = keyboard::short(&path);
                // `@plugin.` at the start of a line: the decorators, called.
                if short == "plugin" && self.after_at(dot) {
                    let mut items = Vec::new();
                    for (name, template) in DECORATORS {
                        let p = format!("{path}.{name}");
                        let mut item = Item::template(name, ItemKind::Function, template, 0).doc(&p);
                        if let Some(e) = model.find(&p) {
                            item = item.detail(e.signature);
                        }
                        if template.contains("(\"") {
                            item = item.reopening();
                        }
                        items.push(item.ranked(decorator_rank(name)));
                    }
                    return self.finish(items, true);
                }
                if short == "plugin.settings" {
                    for s in self.host.settings.iter().flatten() {
                        let detail = if s.label.is_empty() { s.kind.clone() } else { format!("{} · {}", s.kind, s.label) };
                        items.push(Item::new(&s.key, ItemKind::Field, &s.key, 0).detail(Some(&detail)).text(&setting_doc(s)));
                    }
                }
                let children = model.children(&path);
                if model.find(&path).is_some_and(|e| e.kind == ApiKind::Module) || !children.is_empty() {
                    for e in children {
                        items.push(self.api_item(e));
                    }
                    return self.finish(items, false);
                }
                return if items.is_empty() { None } else { self.finish(items, false) };
            }
            let root = &chain[0].0;
            if chain.len() == 1 && root == "bytes" && self.resolve(root, t.span(chain[0].1)) == Target::Builtin {
                let e = model.find("bytes.fromhex").unwrap();
                return self.finish(vec![self.api_item(e)], false);
            }
            if chain.len() == 1 {
                if let Some((event, _)) = event_context(&self.doc.module) {
                    let in_on_event = self.a.top_function("on_event").is_some_and(|f| f.span.touches(self.caret));
                    if *root == event && in_on_event {
                        for (name, ty) in api::EVENT_FIELDS {
                            items.push(Item::new(name, ItemKind::Field, name, 0).detail(Some(ty)));
                        }
                        known_type = Some("dict");
                    }
                }
                if known_type.is_none() {
                    let types = infer::symbol_types(&self.doc.module, self.a);
                    known_type = infer::type_at(self.a, &types, t.span(chain[0].1));
                }
                if known_type == Some("wm5.plugin.State") {
                    for name in self.state_fields(t.span(chain[0].1)) {
                        items.push(Item::new(&name, ItemKind::Field, &name, 0).detail(Some("state field")));
                    }
                }
            }
        } else if let Some(r) = receiver {
            known_type = match t.kind(r) {
                TokenKind::String => Some("str"),
                TokenKind::Operator if t.is(r, "]") || t.is(r, "}") => {
                    let open = self.doc.structure.pairs.get(r).copied().unwrap_or(-1);
                    let literal = open >= 0 && t.prev_code(open as usize).is_none_or(|p| {
                        !(t.kind(p) == TokenKind::Name || t.is_op(p, ")") || t.is_op(p, "]") || t.kind(p) == TokenKind::String)
                    });
                    match (literal, t.is(r, "]")) {
                        (true, true) => Some("list"),
                        (true, false) => Some("dict"),
                        _ => None,
                    }
                }
                _ => None,
            };
        }
        let types: Vec<&str> = match known_type {
            Some(ty) => vec![ty],
            None => vec!["str", "list", "dict"],
        };
        for ty in types {
            for e in model.children(ty) {
                if e.path == "bytes.fromhex" {
                    continue;
                }
                items.push(self.api_item(e));
            }
        }
        if let Some(chain) = &chain {
            for name in self.fields_used(chain) {
                items.push(Item::new(&name, ItemKind::Field, &name, 2));
            }
        }
        self.finish(items, false)
    }

    /// Attribute names the file writes after the same chain: `state.count` for `state.`.
    fn fields_used(&self, chain: &[(String, usize)]) -> Vec<String> {
        let t = self.t;
        let names: Vec<&str> = chain.iter().map(|(n, _)| n.as_str()).collect();
        let last = *names.last().unwrap();
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        for i in 0..t.len() {
            if t.kind(i) != TokenKind::Name || !t.is(i, last) {
                continue;
            }
            let Some(dot) = t.next_code(i).filter(|d| t.is_op(*d, ".")) else { continue };
            let Some(field) = t.next_code(dot).filter(|f| t.kind(*f) == TokenKind::Name) else { continue };
            if self.being_typed(t.span(field)) {
                continue;
            }
            if self.chain_before(dot).is_some_and(|c| c.iter().map(|(n, _)| n.as_str()).eq(names.iter().copied())) {
                let text = self.text(field);
                if api::method(None, &text).is_none() && seen.insert(text.clone()) {
                    out.push(text);
                }
            }
        }
        out
    }

    /// Whether the line holding [dot] starts with `@`.
    fn after_at(&self, dot: usize) -> bool {
        self.line_first(dot).is_some_and(|f| self.t.is_op(f, "@"))
    }

    /// The fields a `plugin.state(...)` call bound to the name at [span] starts with.
    fn state_fields(&self, span: Span) -> Vec<String> {
        let Some(Target::Symbol(id)) = self.a.occurrence_at(span.start).map(|o| o.target) else { return Vec::new() };
        let declaration = self.a.symbols[id].declaration;
        let mut out = Vec::new();
        walk_stmts(&self.doc.module.body, &mut |stmt| {
            if let StmtKind::Assign { targets, value } = &stmt.kind {
                if targets.iter().any(|t| t.span == declaration) {
                    if let ExprKind::Call { keywords, .. } = &value.kind {
                        for k in keywords {
                            if k.name != "persist" && k.name != "key" {
                                out.push(k.name.clone());
                            }
                        }
                    }
                }
            }
        });
        out
    }

    /// The names `a.b.c` ending at name token [last], root first.
    fn chain_at(&self, last: usize) -> Option<Vec<(String, usize)>> {
        let t = self.t;
        if t.kind(last) != TokenKind::Name {
            return None;
        }
        match t.next_code(last) {
            Some(d) if t.is_op(d, ".") => self.chain_before(d),
            _ => {
                // The chain before a `(`: walk back from the name itself.
                let mut parts = vec![(self.text(last), last)];
                let mut at = last;
                while let Some(dot) = t.prev_code(at).filter(|d| t.is_op(*d, ".")) {
                    let n = t.prev_code(dot).filter(|n| t.kind(*n) == TokenKind::Name)?;
                    parts.push((self.text(n), n));
                    at = n;
                    if parts.len() > 32 {
                        return None;
                    }
                }
                parts.reverse();
                Some(parts)
            }
        }
    }

    /// The API function a call opening at [open] calls, with its parameters.
    fn api_call(&self, open: usize) -> Option<(String, Vec<SigParam>)> {
        let callee = self.t.prev_code(open)?;
        let chain = self.chain_at(callee)?;
        let path = self.api_path(&chain)?;
        let e = api::model().find(&path)?;
        let params = keyboard::sig_params(e.signature?);
        Some((path, params))
    }

    /// Which argument [token] is in the call opening at [open]: its position, or the keyword naming it.
    fn argument_of(&self, open: usize, token: usize) -> (usize, Option<String>) {
        let t = self.t;
        let mut depth = 0;
        let mut index = 0;
        let mut first: Option<usize> = None;
        let mut at = t.next_code(open);
        while let Some(i) = at {
            if i >= token {
                break;
            }
            if t.kind(i) == TokenKind::Operator {
                match self.text(i).as_str() {
                    "(" | "[" | "{" => depth += 1,
                    ")" | "]" | "}" => depth -= 1,
                    "," if depth == 0 => {
                        index += 1;
                        first = None;
                        at = t.next_code(i);
                        continue;
                    }
                    _ => {}
                }
            }
            if first.is_none() {
                first = Some(i);
            }
            at = t.next_code(i);
        }
        let keyword = first.filter(|f| t.kind(*f) == TokenKind::Name && t.next_code(*f).is_some_and(|e| t.is_op(e, "="))).map(|f| self.text(f));
        (index, keyword)
    }

    /// The parameter an argument fills.
    fn param_of<'p>(params: &'p [SigParam], index: usize, keyword: Option<&str>) -> Option<&'p SigParam> {
        if let Some(k) = keyword {
            return params.iter().find(|p| p.star == 0 && p.name == k);
        }
        let mut positional = 0;
        for p in params {
            match p.star {
                1 => return Some(p),
                2 => return None,
                _ => {
                    if positional == index {
                        return Some(p);
                    }
                    positional += 1;
                }
            }
        }
        None
    }

    /// At an argument of a call into the API: the keyword arguments not yet given.
    fn call_arguments(&self, before: usize) -> Option<Completions> {
        let t = self.t;
        let open = if t.is_op(before, "(") { before } else { self.open_call(t.prev_code(before)?)? };
        let (path, params) = self.api_call(open)?;
        if self.prefix.is_empty() && !self.explicit {
            return None;
        }
        let (index, _) = self.argument_of(open, t.next_code(before).unwrap_or(before + 1).max(before + 1));
        let given = self.keywords_given(open);
        let doc = path.clone();
        let mut items = Vec::new();
        let mut positional = 0;
        let mut after_star = false;
        for p in &params {
            if p.star == 1 {
                after_star = true;
                continue;
            }
            if p.star == 2 || given.contains(&p.name) || p.name == "self" {
                continue;
            }
            // A parameter already filled by position is not offered again.
            let filled = !after_star && positional < index;
            if !after_star {
                positional += 1;
            }
            if filled {
                continue;
            }
            let values = keyboard::values_for(&path, &p.name);
            let quoted = values.is_some() || p.default.as_deref().is_some_and(|d| d.starts_with('"') || d.starts_with('\''));
            let (template, reopen) = if keyboard::is_callback(&p.name) {
                (format!("{}=|", p.name), true)
            } else if quoted {
                (format!("{}=\"|\"", p.name), values.is_some())
            } else {
                (format!("{}=|", p.name), false)
            };
            let detail = p.default.as_ref().map(|d| format!("= {d}"));
            let mut item = Item::template(&p.name, ItemKind::Field, &template, 0).detail(detail.as_deref()).doc(&doc);
            if reopen {
                item = item.reopening();
            }
            items.push(item);
        }
        items.extend(self.names(false));
        self.finish(items, true)
    }

    /// The keyword arguments the call at [open] names already.
    fn keywords_given(&self, open: usize) -> HashSet<String> {
        let t = self.t;
        let mut out = HashSet::new();
        let close = self.doc.structure.pairs.get(open).copied().unwrap_or(-1);
        let mut depth = 0;
        let mut at = t.next_code(open);
        while let Some(i) = at {
            if close >= 0 && i as i32 >= close {
                break;
            }
            match self.text(i).as_str() {
                "(" | "[" | "{" if t.kind(i) == TokenKind::Operator => depth += 1,
                ")" | "]" | "}" if t.kind(i) == TokenKind::Operator => {
                    if depth == 0 {
                        break;
                    }
                    depth -= 1;
                }
                _ => {}
            }
            if depth == 0 && t.kind(i) == TokenKind::Name && t.start(i) != self.word_start && t.next_code(i).is_some_and(|e| t.is_op(e, "=")) {
                out.insert(self.text(i));
            }
            at = t.next_code(i);
        }
        out
    }

    /// After `on_click=`: the file's own functions, by name, which the keyboard calls later.
    fn callback_value(&self, eq: usize) -> Option<Completions> {
        let t = self.t;
        let key = t.prev_code(eq).filter(|k| t.kind(*k) == TokenKind::Name)?;
        let name = self.text(key);
        if !keyboard::is_callback(&name) {
            return None;
        }
        let open = self.open_call(key)?;
        self.api_call(open)?;
        let mut items = Vec::new();
        for s in self.a.visible_at(self.caret) {
            if s.kind == SymbolKind::Function && !self.being_typed(s.declaration) {
                items.push(Item::new(&s.name, ItemKind::Function, &s.name, 0).detail(Some("function")));
            }
        }
        items.push(Item::template("lambda", ItemKind::Snippet, "lambda: |", 3));
        if self.prefix.is_empty() && !self.explicit && items.len() <= 1 {
            return None;
        }
        self.finish(items, true)
    }

    /// The provider list whose request the name at [token] holds: the first
    /// parameter of a function under `@plugin.provider(tool)`.
    fn request_tool(&self, token: usize) -> Option<&'static keyboard::ProviderTool> {
        let span = self.t.span(token);
        let id = match self.resolve(&self.text(token), span) {
            Target::Symbol(id) => id,
            _ => return None,
        };
        let s = &self.a.symbols[id];
        if s.kind != SymbolKind::Parameter {
            return None;
        }
        let f = self.a.functions.iter().find(|f| f.params.first().is_some_and(|p| p.span == s.declaration))?;
        let d = keyboard::decorated(&self.doc.module, self.a).into_iter().find(|d| d.what == "plugin.provider" && d.def_name_span == f.name_span)?;
        let (tool, _, _) = d.provider()?;
        keyboard::provider_tool(&tool)
    }

    /// Whether the name at [token] is `plugin.settings` (or an alias of it).
    fn is_settings(&self, token: usize) -> bool {
        self.chain_at(token).and_then(|c| self.api_path(&c)).is_some_and(|p| keyboard::short(&p) == "plugin.settings")
    }

    /// The strings an argument of an API call takes, by what the call is.
    fn argument_values(&self, s: usize) -> Option<Vec<(String, Option<String>)>> {
        let open = self.open_call(s)?;
        let (path, params) = self.api_call(open)?;
        let (index, keyword) = self.argument_of(open, s);
        let param = Self::param_of(&params, index, keyword.as_deref())?;
        let h = self.host;
        let plain = |v: Vec<String>| Some(v.into_iter().map(|x| (x, None)).collect());
        match (keyboard::short(&path), param.name.as_str()) {
            ("plugin.provider", "tool") => {
                // The tools plugin.json declares come first.
                let mut out: Vec<(String, Option<String>)> = Vec::new();
                for (tool, _) in h.providers.iter().flatten() {
                    if !out.iter().any(|(t, _)| t == tool) {
                        out.push((tool.clone(), Some("in plugin.json".into())));
                    }
                }
                for t in keyboard::PROVIDER_TOOLS {
                    if !out.iter().any(|(x, _)| x == t.name) {
                        out.push((t.name.to_string(), Some(t.label.to_string())));
                    }
                }
                Some(out)
            }
            ("plugin.provider", "id") => {
                let tool = self.first_literal(open);
                plain(h.providers.iter().flatten().filter(|(t, _)| tool.as_deref().is_none_or(|x| x == t)).map(|(_, id)| id.clone()).collect())
            }
            ("plugin.action", "id") => plain(h.actions.clone().unwrap_or_default()),
            ("plugin.keyword", "word") => plain(h.keyword.iter().filter(|k| !k.is_empty()).cloned().collect()),
            ("plugin.setting_changed", "key") | ("plugin.settings.get", "name") | ("plugin.settings.set", "key") | ("plugin.settings.reset", "key") => {
                Some(h.settings.iter().flatten().map(|s| (s.key.clone(), Some(s.kind.clone()))).collect())
            }
            ("secrets.get" | "secrets.set" | "secrets.remove", "name") => {
                plain(h.settings.iter().flatten().filter(|s| s.kind == "secret").map(|s| s.key.clone()).collect())
            }
            (p, "url") if p.starts_with("http.") => plain(
                h.hosts.iter().flatten().map(|host| format!("https://{}/", host.trim_start_matches("*."))).collect(),
            ),
            _ => {
                let values = keyboard::values_for(&path, &param.name)?;
                plain(values.iter().map(|v| v.to_string()).collect())
            }
        }
    }

    /// The first argument of the call at [open], when it is a plain string.
    fn first_literal(&self, open: usize) -> Option<String> {
        let first = self.t.next_code(open).filter(|f| self.t.kind(*f) == TokenKind::String)?;
        string_value(self.t, first)
    }

    // ---- ui arguments --------------------------------------------------

    /// The open `(` whose call holds [token], or None when a `[` or `{` is nearer.
    fn open_call(&self, token: usize) -> Option<usize> {
        let t = self.t;
        let mut depth = 0;
        let mut k = token + 1;
        let mut steps = 0;
        while k > 0 {
            k -= 1;
            steps += 1;
            if steps > MAX_BACK_TOKENS {
                return None;
            }
            match t.kind(k) {
                TokenKind::Newline if !t.has(k, CONTINUATION) => return None,
                TokenKind::Operator => match self.text(k).as_str() {
                    ")" | "]" | "}" => depth += 1,
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

    /// The builder of a `ui.<name>(` call opening at [open].
    fn ui_builder(&self, open: usize) -> Option<&'static api::UiShape> {
        let t = self.t;
        let name = t.prev_code(open).filter(|n| t.kind(*n) == TokenKind::Name)?;
        let dot = t.prev_code(name).filter(|d| t.is_op(*d, "."))?;
        let root = t.prev_code(dot).filter(|r| t.is(*r, "ui"))?;
        if t.prev_code(root).is_some_and(|p| t.is_op(p, ".")) {
            return None;
        }
        if self.resolve("ui", t.span(root)) != Target::Api {
            return None;
        }
        api::model().ui_shape(&self.text(name))
    }

    /// At an argument of `ui.button(...)`: the keywords not yet given, then the widgets a container takes.
    fn ui_arguments(&self, before: usize) -> Option<Completions> {
        let t = self.t;
        let open = if t.is_op(before, "(") { before } else { self.open_call(t.prev_code(before)?)? };
        let shape = self.ui_builder(open)?;
        if self.prefix.is_empty() && !self.explicit {
            return None;
        }
        let (positional, given) = self.arguments_given(open);
        let mut items = Vec::new();
        let doc = format!("ui.{}", shape.name);
        for (i, p) in shape.params.iter().enumerate() {
            if given.contains(p.name) || (!shape.children && i < positional) {
                continue;
            }
            let template = match p.name {
                "mono" | "insertable" | "copyable" | "enabled" | "checked" | "time" => format!("{}=|", p.name),
                "height" | "step" | "value" => format!("{}=|", p.name),
                _ => format!("{}=\"|\"", p.name),
            };
            items.push(Item::template(p.name, ItemKind::Field, &template, 0).detail(Some("argument")).doc(&doc));
        }
        if shape.children {
            for child in &api::model().ui {
                if (child.name == "page") != (shape.name == "tabs") {
                    continue;
                }
                let path = format!("ui.{}", child.name);
                let e = api::model().find(&path).unwrap();
                items.push(Item::template(child.name, ItemKind::Function, &format!("ui.{}", builder_template(child)), 2).detail(e.signature).doc(&path));
            }
        }
        if !self.prefix.is_empty() || self.explicit {
            items.extend(self.names(false));
        }
        self.finish(items, true)
    }

    /// How many positional arguments come before the caret in the call at [open], and which keywords are given.
    fn arguments_given(&self, open: usize) -> (usize, HashSet<&'static str>) {
        let t = self.t;
        let mut depth = 0;
        let mut positional = 0;
        let mut given = HashSet::new();
        let mut at = t.next_code(open);
        let mut arg_start = true;
        let close = self.doc.structure.pairs.get(open).copied().unwrap_or(-1);
        while let Some(i) = at {
            if close >= 0 && i as i32 >= close {
                break;
            }
            if t.kind(i) == TokenKind::Operator {
                match self.text(i).as_str() {
                    "(" | "[" | "{" => depth += 1,
                    ")" | "]" | "}" => {
                        if depth == 0 {
                            break;
                        }
                        depth -= 1;
                    }
                    "," if depth == 0 => {
                        arg_start = true;
                        at = t.next_code(i);
                        continue;
                    }
                    _ => {}
                }
            }
            if depth == 0 && arg_start && t.start(i) != self.word_start {
                let is_kw = t.kind(i) == TokenKind::Name && t.next_code(i).is_some_and(|n| t.is_op(n, "="));
                if is_kw {
                    if let Some(p) = api::model().ui.iter().flat_map(|s| s.params.iter()).find(|p| t.is(i, p.name)) {
                        given.insert(p.name);
                    }
                } else if t.start(i) < self.word_start {
                    positional += 1;
                }
            }
            arg_start = false;
            at = t.next_code(i);
        }
        (positional, given)
    }

    // ---- strings ---------------------------------------------------------

    /// Inside a string: an event type or widget id it is compared with, a
    /// style, an action id, an event field, or a storage key.
    fn in_string(&self, s: usize) -> Option<Completions> {
        let t = self.t;
        let units = self.doc.units();
        let mut body = t.start(s) as usize;
        while body < t.end(s) as usize && units[body] != b'"' as u16 && units[body] != b'\'' as u16 {
            body += 1;
        }
        let quotes = if t.has(s, crate::lexer::TRIPLE) { 3 } else { 1 };
        let start = (body + quotes) as u32;
        if self.caret < start {
            return None;
        }
        let typed = slice(units, Span::new(start, self.caret));
        let mut end = self.caret;
        let limit = if t.has(s, UNTERMINATED) { t.end(s) } else { t.end(s).saturating_sub(quotes as u32) };
        while end < limit && is_word(units[end as usize]) {
            end += 1;
        }
        let values: Vec<(String, Option<String>)> = match self.keyboard_values(s) {
            Some(v) => v,
            None => self.string_values(s).into_iter().map(|v| (v, None)).collect(),
        };
        let mut seen = HashSet::new();
        let items: Vec<Item> = values
            .into_iter()
            .filter(|(v, _)| match_class(v, &typed).is_some() && *v != typed && seen.insert(v.clone()))
            .map(|(v, detail)| Item::new(&v, ItemKind::Value, &v, 3).detail(detail.as_deref()))
            .collect();
        if items.is_empty() {
            return None;
        }
        let mut items = items;
        items.truncate(MAX_ITEMS);
        Some(Completions { replace: Span::new(start, end), items })
    }

    /// Strings the keyboard knows for this place: an argument of a package
    /// call, a provider request's keys, a setting's key.
    fn keyboard_values(&self, s: usize) -> Option<Vec<(String, Option<String>)>> {
        let t = self.t;
        let p = t.prev_code(s)?;
        if t.is_op(p, "[") {
            let name = t.prev_code(p).filter(|n| t.kind(*n) == TokenKind::Name)?;
            if let Some(tool) = self.request_tool(name) {
                return Some(tool.request.iter().map(|(k, d)| (k.to_string(), Some(d.to_string()))).collect());
            }
            if self.is_settings(name) {
                return Some(self.host.settings.iter().flatten().map(|s| (s.key.clone(), Some(s.kind.clone()))).collect());
            }
            return None;
        }
        if t.is_op(p, "(") || t.is_op(p, ",") || t.is_op(p, "=") {
            // `request.get("` on a provider's request.
            if t.is_op(p, "(") {
                let get = t.prev_code(p).filter(|g| t.is(*g, "get"));
                let root = get.and_then(|g| t.prev_code(g)).filter(|d| t.is_op(*d, ".")).and_then(|d| t.prev_code(d));
                if let Some(root) = root.filter(|r| t.kind(*r) == TokenKind::Name) {
                    if let Some(tool) = self.request_tool(root) {
                        return Some(tool.request.iter().map(|(k, d)| (k.to_string(), Some(d.to_string()))).collect());
                    }
                }
            }
            if t.is_op(p, "=") && self.open_call(s).and_then(|o| self.ui_builder(o)).is_some() {
                return None;
            }
            return self.argument_values(s);
        }
        None
    }

    fn string_values(&self, s: usize) -> Vec<String> {
        let t = self.t;
        let Some(p) = t.prev_code(s) else { return Vec::new() };
        let event = event_context(&self.doc.module);
        if t.is_op(p, "==") || t.is_op(p, "!=") {
            let Some(subject) = t.prev_code(p) else { return Vec::new() };
            match self.compared_field(subject, event.as_ref()) {
                Some("type") => return api::EVENT_TYPES.iter().map(|s| s.to_string()).collect(),
                Some("id") => return self.widget_ids(),
                Some("action") => return self.host.actions.clone().unwrap_or_default(),
                _ => {}
            }
            return Vec::new();
        }
        if t.is_op(p, "=") {
            let key = t.prev_code(p).filter(|k| t.kind(*k) == TokenKind::Name);
            if let Some(key) = key {
                if let Some(open) = self.open_call(key) {
                    if let Some(shape) = self.ui_builder(open) {
                        if let Some(param) = shape.param(&self.text(key)) {
                            return param.values.iter().map(|s| s.to_string()).collect();
                        }
                    }
                }
            }
            return Vec::new();
        }
        if t.is_op(p, "[") {
            if let (Some(name), Some((ev, _))) = (t.prev_code(p), event.as_ref()) {
                if t.is(name, ev) {
                    return api::EVENT_FIELDS.iter().map(|(n, _)| n.to_string()).collect();
                }
            }
            return Vec::new();
        }
        if t.is_op(p, "(") {
            let name = t.prev_code(p);
            let dot = name.and_then(|n| t.prev_code(n)).filter(|d| t.is_op(*d, "."));
            let root = dot.and_then(|d| t.prev_code(d));
            if let (Some(name), Some(root)) = (name, root) {
                if t.is(name, "get") && event.as_ref().is_some_and(|(ev, _)| t.is(root, ev)) {
                    return api::EVENT_FIELDS.iter().map(|(n, _)| n.to_string()).collect();
                }
                let storage = t.prev_code(root).filter(|d| t.is_op(*d, ".")).and_then(|d| t.prev_code(d)).is_some_and(|w| t.is(w, "wm"));
                if storage && t.is(root, "storage") {
                    return self.storage_keys();
                }
            }
        }
        Vec::new()
    }

    /// `type`, `id` or `action`, when the tokens ending at [subject] read that field.
    fn compared_field(&self, subject: usize, event: Option<&(String, HashMap<String, &'static str>)>) -> Option<&'static str> {
        let t = self.t;
        if t.kind(subject) == TokenKind::Name {
            let name = self.text(subject);
            let prev = t.prev_code(subject);
            if prev.is_some_and(|d| t.is_op(d, ".")) {
                let root = prev.and_then(|d| t.prev_code(d))?;
                let (ev, _) = event?;
                if t.is(root, ev) {
                    return match name.as_str() {
                        "type" => Some("type"),
                        "id" => Some("id"),
                        _ => None,
                    };
                }
                return None;
            }
            if let Some((_, aliases)) = event {
                if let Some(field) = aliases.get(&name) {
                    return Some(field);
                }
            }
            if let Some(f) = self.a.top_function("transform") {
                if f.span.contains(t.start(subject)) {
                    if f.params.get(1).is_some_and(|p| p.name == name) {
                        return Some("action");
                    }
                }
            }
            return None;
        }
        if t.is_op(subject, "]") || t.is_op(subject, ")") {
            let key = t.prev_code(subject).filter(|k| t.kind(*k) == TokenKind::String)?;
            let value = string_value(t, key)?;
            let open = t.prev_code(key)?;
            let (ev, _) = event?;
            let matches_event = if t.is_op(subject, "]") {
                t.is_op(open, "[") && t.prev_code(open).is_some_and(|r| t.is(r, ev))
            } else {
                t.is_op(open, "(")
                    && t.prev_code(open).is_some_and(|g| t.is(g, "get"))
                    && t.prev_code(open).and_then(|g| t.prev_code(g)).and_then(|d| t.prev_code(d)).is_some_and(|r| t.is(r, ev))
            };
            if matches_event {
                return match value.as_str() {
                    "type" => Some("type"),
                    "id" => Some("id"),
                    _ => None,
                };
            }
        }
        None
    }

    /// Ids given to ui builders as plain strings, in the order written.
    fn widget_ids(&self) -> Vec<String> {
        let t = &self.doc.tokens;
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        for i in 0..t.len() {
            if !t.is_op(i, "(") {
                continue;
            }
            let Some(name) = t.prev_code(i) else { continue };
            let Some(shape) = api::model().ui_shape(&t.text(name)) else { continue };
            let is_ui = t.prev_code(name).filter(|d| t.is_op(*d, ".")).and_then(|d| t.prev_code(d)).is_some_and(|r| t.is(r, "ui"));
            if !is_ui {
                continue;
            }
            let first = t.next_code(i);
            let id_token = if shape.has_id {
                first
            } else {
                // `output(text, id="x")`
                let mut k = first;
                let mut found = None;
                while let Some(j) = k {
                    if t.is_op(j, ")") {
                        break;
                    }
                    if t.is(j, "id") && t.next_code(j).is_some_and(|e| t.is_op(e, "=")) {
                        found = t.next_code(j).and_then(|e| t.next_code(e));
                        break;
                    }
                    k = t.next_code(j);
                }
                found
            };
            if let Some(v) = id_token.filter(|v| t.kind(*v) == TokenKind::String).and_then(|v| string_value(t, v)) {
                if !self.being_typed_string(id_token.unwrap()) && seen.insert(v.clone()) {
                    out.push(v);
                }
            }
        }
        out
    }

    fn being_typed_string(&self, token: usize) -> bool {
        self.t.span(token).touches(self.caret) && std::ptr::eq(self.t, &self.doc.tokens)
    }

    fn storage_keys(&self) -> Vec<String> {
        let t = &self.doc.tokens;
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        for i in 0..t.len() {
            if !t.is(i, "storage") {
                continue;
            }
            let Some(dot) = t.next_code(i).filter(|d| t.is_op(*d, ".")) else { continue };
            let Some(open) = t.next_code(dot).and_then(|m| t.next_code(m)).filter(|o| t.is_op(*o, "(")) else { continue };
            let Some(key) = t.next_code(open).filter(|k| t.kind(*k) == TokenKind::String) else { continue };
            if self.being_typed_string(key) {
                continue;
            }
            if let Some(v) = string_value(t, key) {
                if seen.insert(v.clone()) {
                    out.push(v);
                }
            }
        }
        out
    }

    // ---- names -----------------------------------------------------------

    fn names(&self, statement_start: bool) -> Vec<Item> {
        let mut items = Vec::new();
        let model = api::model();
        let mut locals = HashSet::new();
        for s in self.a.visible_at(self.caret) {
            if self.being_typed(s.declaration) && s.reads.is_empty() && s.writes.is_empty() {
                continue;
            }
            let module_level = s.scope == 0;
            let (kind, rank) = match s.kind {
                SymbolKind::Parameter => (ItemKind::Parameter, 0),
                SymbolKind::Function | SymbolKind::Class => (ItemKind::Function, if module_level { 1 } else { 0 }),
                SymbolKind::Import => (ItemKind::Module, 1),
                _ if is_constant_name(&s.name) => (ItemKind::Constant, if module_level { 1 } else { 0 }),
                _ => (ItemKind::Variable, if module_level { 1 } else { 0 }),
            };
            if rank == 0 {
                locals.insert(s.name.clone());
            }
            let insert = if kind == ItemKind::Function { format!("{}(|)", s.name) } else { format!("{}|", s.name) };
            let mut item = Item::template(&s.name, kind, &insert, rank);
            if let Some((module, member)) = &s.import {
                let path = api::import_path(module, member.as_deref());
                if let Some(e) = model.find(&path) {
                    item = item.detail(e.signature).doc(e.path);
                }
            }
            items.push(item);
        }
        for g in api::API_GLOBALS {
            if !locals.contains(*g) {
                items.push(Item::new(g, ItemKind::Module, g, 2).doc(g));
            }
        }
        for keyword in KEYWORDS {
            items.push(Item::new(keyword, ItemKind::Keyword, keyword, 3));
        }
        for e in model.children("") {
            if matches!(e.kind, ApiKind::Builtin | ApiKind::Exception) {
                items.push(self.api_item(e));
            }
        }
        if statement_start && !self.prefix.is_empty() && self.api5() {
            let indent = self.indent();
            let (plugin, ui) = (self.plugin_name(), self.ui_name());
            let top = indent.is_empty();
            for (label, template) in SNIPPETS5 {
                let keyboard_only = template.contains("{p}") || label.starts_with("def render");
                if keyboard_only && !top {
                    continue;
                }
                if label.starts_with("def render") && self.a.module_symbol("render").is_some_and(|s| !self.being_typed(s.declaration)) {
                    continue;
                }
                let first = label.split(' ').next().unwrap_or(label);
                if !first.to_lowercase().starts_with(&self.prefix.to_lowercase()) {
                    continue;
                }
                let text = template.replace("{i}", &indent).replace("{p}", &plugin).replace("{u}", &ui);
                items.push(Item::template(label, ItemKind::Snippet, &text, 4));
            }
            if top && "provider".starts_with(&self.prefix.to_lowercase()) {
                items.extend(self.provider_snippets(&plugin));
            }
        } else if statement_start && !self.prefix.is_empty() {
            let indent = self.indent();
            for (label, template) in SNIPPETS {
                let hook = ["render", "on_event", "transform"].into_iter().find(|h| label.starts_with(&format!("def {h}(")));
                if let Some(h) = hook {
                    if self.a.module_symbol(h).is_some_and(|s| !self.being_typed(s.declaration)) {
                        continue;
                    }
                }
                let first = label.split(' ').next().unwrap_or(label);
                if !first.to_lowercase().starts_with(&self.prefix.to_lowercase()) {
                    continue;
                }
                let text = template.replace("{i}", &indent);
                items.push(Item::template(label, ItemKind::Snippet, &text, 4));
            }
        }
        items
    }

    /// A stub for each provider plugin.json declares and nothing answers yet,
    /// then one for every other list.
    fn provider_snippets(&self, plugin: &str) -> Vec<Item> {
        let answered: HashSet<(String, String)> = keyboard::decorated(&self.doc.module, self.a)
            .iter()
            .filter(|d| d.what == "plugin.provider")
            .filter_map(|d| d.provider().map(|(t, i, _)| (t, i)))
            .collect();
        let mut wanted: Vec<(String, String)> = self.host.providers.iter().flatten().filter(|p| !answered.contains(*p)).cloned().collect();
        let declared = wanted.len();
        for t in keyboard::PROVIDER_TOOLS {
            if !wanted.iter().any(|(tool, _)| tool == t.name) && !answered.iter().any(|(tool, _)| tool == t.name) {
                wanted.push((t.name.to_string(), t.name.to_string()));
            }
        }
        wanted
            .into_iter()
            .enumerate()
            .map(|(n, (tool, id))| {
                let label = if id == tool { format!("provider {tool}") } else { format!("provider {tool} {id}") };
                let text = format!("{}|", keyboard::provider_stub(plugin, &tool, &id, ""));
                let detail = keyboard::provider_tool(&tool).map(|t| t.label);
                // What plugin.json declares and nothing answers yet comes first.
                let detail = if n < declared { Some("in plugin.json") } else { detail };
                Item::template(&label, ItemKind::Snippet, &text, if n < declared { 3 } else { 4 }).detail(detail)
            })
            .collect()
    }

    fn indent(&self) -> String {
        let units = self.doc.units();
        let mut start = self.word_start as usize;
        while start > 0 && units[start - 1] != b'\n' as u16 && units[start - 1] != b'\r' as u16 {
            start -= 1;
        }
        let mut end = start;
        while end < self.word_start as usize && (units[end] == b' ' as u16 || units[end] == b'\t' as u16) {
            end += 1;
        }
        String::from_utf16_lossy(&units[start..end])
    }

    fn api_item(&self, e: &ApiEntry) -> Item {
        let name = e.name();
        let (kind, template) = match e.kind {
            ApiKind::Module => (ItemKind::Module, format!("{name}|")),
            ApiKind::Constant => (ItemKind::Constant, format!("{name}|")),
            ApiKind::Type => (ItemKind::Module, format!("{name}|")),
            ApiKind::Function | ApiKind::Builtin | ApiKind::Exception | ApiKind::Method => {
                let kind = if e.kind == ApiKind::Method { ItemKind::Method } else { ItemKind::Function };
                let template = match (e.parent(), api::model().ui_shape(name)) {
                    ("ui", Some(shape)) => builder_template(shape),
                    _ if e.signature.is_some_and(|s| s.ends_with("()")) => format!("{name}()|"),
                    _ if e.kind == ApiKind::Exception && e.signature.is_none() => format!("{name}|"),
                    _ => format!("{name}(|)"),
                };
                (kind, template)
            }
        };
        Item::template(name, kind, &template, 2).detail(e.signature).doc(e.path)
    }

    // ---- ranking ---------------------------------------------------------

    fn finish(&self, items: Vec<Item>, ranked: bool) -> Option<Completions> {
        let frequency = self.frequency();
        let mut best: HashMap<String, (Item, u32)> = HashMap::new();
        let mut order: Vec<String> = Vec::new();
        for item in items {
            let class = if item.kind == ItemKind::Snippet { Some(0) } else { match_class(&item.label, &self.prefix) };
            let Some(class) = class else { continue };
            if item.label == self.prefix && item.insert == self.prefix {
                continue;
            }
            let score = class * 10 + if ranked { item.rank as u32 } else { 0 };
            match best.get(&item.label) {
                Some((_, held)) if *held <= score => {}
                Some(_) => {
                    best.insert(item.label.clone(), (item, score));
                }
                None => {
                    order.push(item.label.clone());
                    best.insert(item.label.clone(), (item, score));
                }
            }
        }
        if best.is_empty() {
            return None;
        }
        let mut sorted: Vec<(Item, u32)> = order.into_iter().filter_map(|l| best.remove(&l)).collect();
        sorted.sort_by(|(a, sa), (b, sb)| {
            sa.cmp(sb)
                .then_with(|| frequency.get(&b.label).unwrap_or(&0).cmp(frequency.get(&a.label).unwrap_or(&0)))
                .then_with(|| a.label.len().cmp(&b.label.len()))
                .then_with(|| a.label.cmp(&b.label))
        });
        let items: Vec<Item> = sorted.into_iter().take(MAX_ITEMS).map(|(i, _)| i).collect();
        Some(Completions { replace: Span::new(self.word_start, self.word_end), items })
    }

    fn frequency(&self) -> HashMap<String, u32> {
        let t = &self.doc.tokens;
        let mut counts = HashMap::new();
        for i in 0..t.len() {
            if t.kind(i) == TokenKind::Name && !self.being_typed(t.span(i)) {
                *counts.entry(t.text(i)).or_insert(0) += 1;
            }
        }
        counts
    }
}

/// 0 for a prefix in the same case, 1 in any case, 2 for the letters in order, None for no match.
fn match_class(label: &str, typed: &str) -> Option<u32> {
    if typed.is_empty() || label.starts_with(typed) {
        return Some(0);
    }
    if label.to_lowercase().starts_with(&typed.to_lowercase()) {
        return Some(1);
    }
    let mut chars = label.chars();
    let first = typed.chars().next()?;
    if !chars.next().is_some_and(|c| c.to_lowercase().eq(first.to_lowercase())) {
        return None;
    }
    let mut want = typed.chars().skip(1).peekable();
    for c in chars {
        if let Some(w) = want.peek() {
            if c.to_lowercase().eq(w.to_lowercase()) {
                want.next();
            }
        }
    }
    want.peek().is_none().then_some(2)
}

/// `button("|", "")`: the builder with a gap for each required argument, the caret in the first.
pub fn builder_template(shape: &api::UiShape) -> String {
    let required: Vec<&api::UiParam> = shape.params.iter().filter(|p| p.required).collect();
    if required.is_empty() {
        return if shape.children { format!("{}(|)", shape.name) } else { format!("{}()|", shape.name) };
    }
    if shape.name == "output" {
        return "output(|)".to_string();
    }
    let gaps: Vec<&str> = required.iter().enumerate().map(|(i, _)| if i == 0 { "\"|\"" } else { "\"\"" }).collect();
    format!("{}({})", shape.name, gaps.join(", "))
}

fn setting_doc(s: &crate::document::SettingSpec) -> String {
    let mut text = if s.label.is_empty() { format!("Your {} setting.", s.kind) } else { format!("{} ({} setting)", s.label, s.kind) };
    if !s.options.is_empty() {
        text.push_str(&format!(". Options: {}", s.options.join(", ")));
    }
    text
}

/// The decorators a plugin is built from come before the timers and hooks.
fn decorator_rank(name: &str) -> u8 {
    match name {
        "action" | "provider" | "keyword" => 0,
        "command" | "every" | "on_open" => 1,
        _ => 2,
    }
}
