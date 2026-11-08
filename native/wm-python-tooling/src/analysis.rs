//! What the names in a script mean, by Python's rules: a name assigned
//! anywhere in a function is local to all of it unless a `global` statement
//! says otherwise, functions see the names of the functions around them, and
//! module names are seen everywhere. Comprehensions and lambdas have scopes
//! of their own; a comprehension's first iterable is read outside it.
//!
//! Built from the tolerant tree, so it exists for a half-typed file too: an
//! unreadable line still contributes the names it assigns and reads.

use std::collections::{HashMap, HashSet};

use crate::api;
use crate::ast::*;
use crate::text::Span;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ScopeKind {
    Module,
    Function,
    Lambda,
    Comprehension,
}

#[derive(Clone, Debug)]
pub struct Scope {
    pub kind: ScopeKind,
    pub parent: Option<usize>,
    pub span: Span,
    /// The function this scope is, for a function or lambda scope.
    pub function: Option<usize>,
    pub names: HashMap<String, usize>,
    /// Names its `global` statements hand to the module.
    pub globals: HashSet<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SymbolKind {
    Variable,
    Function,
    Parameter,
    Import,
    LoopVariable,
    ExceptVariable,
    ComprehensionVariable,
    /// A `class`, which PyMini does not run.
    Class,
}

impl SymbolKind {
    pub fn name(self) -> &'static str {
        match self {
            SymbolKind::Variable => "variable",
            SymbolKind::Function => "function",
            SymbolKind::Parameter => "parameter",
            SymbolKind::Import => "import",
            SymbolKind::LoopVariable => "loop_variable",
            SymbolKind::ExceptVariable => "except_variable",
            SymbolKind::ComprehensionVariable => "comprehension_variable",
            SymbolKind::Class => "class",
        }
    }
}

#[derive(Clone, Debug)]
pub struct Symbol {
    pub id: usize,
    pub name: String,
    pub kind: SymbolKind,
    pub scope: usize,
    /// Where it is first bound.
    pub declaration: Span,
    pub reads: Vec<Span>,
    /// Bindings after the first.
    pub writes: Vec<Span>,
    /// `global` statements that name it.
    pub mentions: Vec<Span>,
    /// Only ever bound as one of several names unpacked from a tuple.
    pub unpacked_only: bool,
    /// For an import: the module, and the member for `from m import x`.
    pub import: Option<(String, Option<String>)>,
}

impl Symbol {
    /// The declaration and every use, in document order, each once.
    pub fn references(&self) -> Vec<Span> {
        let mut all: Vec<Span> = std::iter::once(self.declaration)
            .chain(self.reads.iter().copied())
            .chain(self.writes.iter().copied())
            .chain(self.mentions.iter().copied())
            .collect();
        all.sort();
        all.dedup();
        all
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Target {
    Symbol(usize),
    Builtin,
    /// `wm` or `ui`.
    Api,
    Unresolved,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Access {
    Read,
    Write,
    /// The first binding.
    Declare,
    /// A name in a `global` statement.
    Mention,
}

#[derive(Clone, Debug)]
pub struct Occurrence {
    pub span: Span,
    pub scope: usize,
    pub access: Access,
    pub target: Target,
    /// Read as an exception type in `except`.
    pub except_type: bool,
}

#[derive(Clone, Debug)]
pub struct ParamInfo {
    pub name: String,
    pub span: Span,
    pub has_default: bool,
}

#[derive(Clone, Debug)]
pub struct Function {
    pub id: usize,
    pub name: Option<String>,
    pub name_span: Span,
    pub span: Span,
    pub params: Vec<ParamInfo>,
    pub vararg: Option<ParamInfo>,
    pub parent: Option<usize>,
    pub scope: usize,
    pub lambda: bool,
    /// Whether a `return` directly in it gives back a value.
    pub returns_value: bool,
    /// Written at the top of the file, not inside anything.
    pub top_level: bool,
}

impl Function {
    pub fn required(&self) -> usize {
        self.params.iter().filter(|p| !p.has_default).count()
    }

    /// Whether it can be called with exactly [n] positional arguments.
    pub fn accepts(&self, n: usize) -> bool {
        self.required() <= n && (self.params.len() >= n || self.vararg.is_some())
    }
}

#[derive(Clone, Debug, Default)]
pub struct Analysis {
    pub scopes: Vec<Scope>,
    pub symbols: Vec<Symbol>,
    /// Every name occurrence, sorted by start.
    pub occurrences: Vec<Occurrence>,
    pub functions: Vec<Function>,
}

impl Analysis {
    pub fn of(module: &Module, source_len: u32) -> Analysis {
        let mut b = Builder::default();
        b.scopes.push(Scope {
            kind: ScopeKind::Module,
            parent: None,
            span: Span::new(0, source_len),
            function: None,
            names: HashMap::new(),
            globals: HashSet::new(),
        });
        b.stmts(&module.body, 0, None);
        b.finish()
    }

    /// The occurrence whose name contains [offset], or ends exactly at it.
    pub fn occurrence_at(&self, offset: u32) -> Option<&Occurrence> {
        let i = self.occurrences.partition_point(|o| o.span.end < offset);
        let mut best: Option<&Occurrence> = None;
        for o in &self.occurrences[i..] {
            if o.span.start > offset {
                break;
            }
            if o.span.contains(offset) {
                return Some(o);
            }
            if o.span.end == offset {
                best = Some(o);
            }
        }
        best
    }

    pub fn symbol_at(&self, offset: u32) -> Option<&Symbol> {
        match self.occurrence_at(offset)?.target {
            Target::Symbol(id) => self.symbols.get(id),
            _ => None,
        }
    }

    /// The innermost scope whose span holds [offset].
    pub fn scope_at(&self, offset: u32) -> usize {
        let mut best = 0;
        for (i, s) in self.scopes.iter().enumerate().skip(1) {
            if s.span.touches(offset) && s.span.start >= self.scopes[best].span.start {
                best = i;
            }
        }
        best
    }

    /// The symbols seen at [offset], the innermost of each name.
    pub fn visible_at(&self, offset: u32) -> Vec<&Symbol> {
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        let mut scope = Some(self.scope_at(offset));
        while let Some(s) = scope {
            let mut ids: Vec<usize> = self.scopes[s].names.values().copied().collect();
            ids.sort();
            for id in ids {
                let symbol = &self.symbols[id];
                if seen.insert(symbol.name.clone()) {
                    out.push(symbol);
                }
            }
            scope = self.scopes[s].parent;
        }
        out
    }

    /// What [name] means in [scope].
    pub fn resolve(&self, name: &str, scope: usize) -> Target {
        resolve_in(&self.scopes, name, scope)
    }

    /// The innermost function [offset] is inside.
    pub fn function_at(&self, offset: u32) -> Option<&Function> {
        self.functions.iter().filter(|f| f.span.contains(offset)).max_by_key(|f| f.span.start)
    }

    /// A function written at the top of the file with this name.
    pub fn top_function(&self, name: &str) -> Option<&Function> {
        self.functions.iter().find(|f| f.top_level && f.name.as_deref() == Some(name))
    }

    pub fn module_symbol(&self, name: &str) -> Option<&Symbol> {
        self.scopes[0].names.get(name).map(|id| &self.symbols[*id])
    }

    /// Every read of a name that nothing defines.
    pub fn unresolved(&self) -> impl Iterator<Item = &Occurrence> {
        self.occurrences.iter().filter(|o| o.target == Target::Unresolved && o.access == Access::Read)
    }

    /// The depth of a function among named functions, lambdas not counted.
    pub fn depth_of(&self, function: usize) -> usize {
        let mut depth = 0;
        let mut at = self.functions[function].parent;
        while let Some(p) = at {
            if !self.functions[p].lambda {
                depth += 1;
            }
            at = self.functions[p].parent;
        }
        depth
    }
}

fn resolve_in(scopes: &[Scope], name: &str, scope: usize) -> Target {
    let mut at = Some(scope);
    while let Some(s) = at {
        let sc = &scopes[s];
        if sc.globals.contains(name) {
            return match scopes[0].names.get(name) {
                Some(id) => Target::Symbol(*id),
                None => outside(name),
            };
        }
        if let Some(id) = sc.names.get(name) {
            return Target::Symbol(*id);
        }
        at = sc.parent;
    }
    outside(name)
}

fn outside(name: &str) -> Target {
    if api::API_GLOBALS.contains(&name) {
        Target::Api
    } else if api::is_builtin(name) {
        Target::Builtin
    } else {
        Target::Unresolved
    }
}

#[derive(Default)]
struct Builder {
    scopes: Vec<Scope>,
    symbols: Vec<Symbol>,
    functions: Vec<Function>,
    /// Reads and mentions, resolved once every binding is known.
    pending: Vec<(String, Span, usize, Access, bool)>,
    bound: Vec<Occurrence>,
}

impl Builder {
    fn finish(mut self) -> Analysis {
        let mut occurrences = std::mem::take(&mut self.bound);
        for (name, span, scope, access, except_type) in std::mem::take(&mut self.pending) {
            let mut target = resolve_in(&self.scopes, &name, scope);
            if except_type && target == Target::Unresolved && api::EXCEPTIONS.contains(&name.as_str()) {
                target = Target::Builtin;
            }
            if let Target::Symbol(id) = target {
                let symbol = &mut self.symbols[id];
                match access {
                    Access::Mention => symbol.mentions.push(span),
                    _ => symbol.reads.push(span),
                }
            }
            occurrences.push(Occurrence { span, scope, access, target, except_type });
        }
        occurrences.sort_by_key(|o| (o.span.start, o.span.end));
        occurrences.dedup_by(|a, b| a.span == b.span && a.target == b.target);
        for s in &mut self.symbols {
            s.reads.sort();
            s.reads.dedup();
            s.writes.sort();
            s.writes.dedup();
        }
        Analysis { scopes: self.scopes, symbols: self.symbols, occurrences, functions: self.functions }
    }

    fn new_scope(&mut self, kind: ScopeKind, parent: usize, span: Span, function: Option<usize>) -> usize {
        self.scopes.push(Scope { kind, parent: Some(parent), span, function, names: HashMap::new(), globals: HashSet::new() });
        self.scopes.len() - 1
    }

    /// The scope a binding in [scope] lands in: the module for a name its function declared global.
    fn owner(&self, name: &str, scope: usize) -> usize {
        let mut at = scope;
        // A comprehension binds its own targets; anything else it binds (a walrus) goes outward.
        if self.scopes[at].globals.contains(name) {
            return 0;
        }
        while self.scopes[at].kind == ScopeKind::Comprehension {
            match self.scopes[at].parent {
                Some(p) => at = p,
                None => break,
            }
        }
        if self.scopes[at].globals.contains(name) {
            0
        } else {
            at
        }
    }

    fn bind(&mut self, name: &str, span: Span, scope: usize, kind: SymbolKind, unpacked: bool) -> usize {
        let owner = if kind == SymbolKind::ComprehensionVariable { scope } else { self.owner(name, scope) };
        if let Some(&id) = self.scopes[owner].names.get(name) {
            let symbol = &mut self.symbols[id];
            if symbol.declaration != span {
                symbol.writes.push(span);
            }
            if !unpacked {
                symbol.unpacked_only = false;
            }
            self.bound.push(Occurrence { span, scope, access: Access::Write, target: Target::Symbol(id), except_type: false });
            return id;
        }
        let id = self.symbols.len();
        self.symbols.push(Symbol {
            id,
            name: name.to_string(),
            kind,
            scope: owner,
            declaration: span,
            reads: Vec::new(),
            writes: Vec::new(),
            mentions: Vec::new(),
            unpacked_only: unpacked,
            import: None,
        });
        self.scopes[owner].names.insert(name.to_string(), id);
        self.bound.push(Occurrence { span, scope, access: Access::Declare, target: Target::Symbol(id), except_type: false });
        id
    }

    fn read(&mut self, name: &str, span: Span, scope: usize) {
        self.pending.push((name.to_string(), span, scope, Access::Read, false));
    }

    // ---- statements ------------------------------------------------------

    fn stmts(&mut self, stmts: &[Stmt], scope: usize, function: Option<usize>) {
        for stmt in stmts {
            self.stmt(stmt, scope, function);
        }
    }

    fn stmt(&mut self, stmt: &Stmt, scope: usize, function: Option<usize>) {
        match &stmt.kind {
            StmtKind::Expr(e) => self.expr(e, scope, function),
            StmtKind::Assign { targets, value } => {
                self.expr(value, scope, function);
                for t in targets {
                    self.target(t, scope, function, SymbolKind::Variable, false);
                }
            }
            StmtKind::AugAssign { target, value, .. } => {
                self.expr(value, scope, function);
                if let ExprKind::Name(n) = &target.kind {
                    self.read(n, target.span, scope);
                }
                self.target(target, scope, function, SymbolKind::Variable, false);
            }
            StmtKind::AnnAssign { target, annotation, value } => {
                self.expr(annotation, scope, function);
                if let Some(v) = value {
                    self.expr(v, scope, function);
                }
                self.target(target, scope, function, SymbolKind::Variable, false);
            }
            StmtKind::If { test, body, orelse, .. } | StmtKind::While { test, body, orelse } => {
                self.expr(test, scope, function);
                self.stmts(body, scope, function);
                self.stmts(orelse, scope, function);
            }
            StmtKind::For { target, iter, body, orelse } => {
                self.expr(iter, scope, function);
                self.target(target, scope, function, SymbolKind::LoopVariable, false);
                self.stmts(body, scope, function);
                self.stmts(orelse, scope, function);
            }
            StmtKind::Try { body, handlers, orelse, finalbody } => {
                self.stmts(body, scope, function);
                for h in handlers {
                    for t in &h.types {
                        if let ExprKind::Name(n) = &t.kind {
                            self.pending.push((n.clone(), t.span, scope, Access::Read, true));
                        } else {
                            self.expr(t, scope, function);
                        }
                    }
                    if let Some((n, s)) = &h.name {
                        self.bind(n, *s, scope, SymbolKind::ExceptVariable, false);
                    }
                    self.stmts(&h.body, scope, function);
                }
                self.stmts(orelse, scope, function);
                self.stmts(finalbody, scope, function);
            }
            StmtKind::Def { name, name_span, params, body, .. } => {
                self.bind(name, *name_span, scope, SymbolKind::Function, false);
                self.function(Some((name.clone(), *name_span)), params, stmt.span, scope, function, false, |b, inner, f| {
                    b.stmts(body, inner, Some(f));
                }, body);
            }
            StmtKind::Return(value) => {
                if let Some(v) = value {
                    if let Some(f) = function {
                        self.functions[f].returns_value = true;
                    }
                    self.expr(v, scope, function);
                }
            }
            StmtKind::Raise(value) => {
                if let Some(v) = value {
                    self.expr(v, scope, function);
                }
            }
            StmtKind::Import(names) => {
                for n in names {
                    let (bound, span) = n.bound();
                    let bound = bound.split('.').next().unwrap_or(bound).to_string();
                    let id = self.bind(&bound, span, scope, SymbolKind::Import, false);
                    let module = if n.asname.is_some() { n.name.clone() } else { bound.clone() };
                    self.symbols[id].import.get_or_insert((module, None));
                }
            }
            StmtKind::ImportFrom { module, names, .. } => {
                for n in names {
                    let (bound, span) = n.bound();
                    let id = self.bind(bound, span, scope, SymbolKind::Import, false);
                    self.symbols[id].import.get_or_insert((module.clone(), Some(n.name.clone())));
                }
            }
            StmtKind::Global(names) => {
                for (n, s) in names {
                    self.pending.push((n.clone(), *s, scope, Access::Mention, false));
                }
            }
            StmtKind::Del(targets) => {
                for t in targets {
                    match &t.kind {
                        ExprKind::Name(n) => {
                            self.read(n, t.span, scope);
                        }
                        _ => self.expr(t, scope, function),
                    }
                }
            }
            StmtKind::Unsupported { what, exprs, binds, body } => {
                for e in exprs {
                    self.expr(e, scope, function);
                }
                let kind = if *what == "class" { SymbolKind::Class } else { SymbolKind::Variable };
                for b in binds {
                    self.target(b, scope, function, kind, false);
                }
                self.stmts(body, scope, function);
            }
            StmtKind::Error { reads, binds } => {
                for (n, s) in binds {
                    self.bind(n, *s, scope, SymbolKind::Variable, false);
                }
                for (n, s) in reads {
                    self.read(n, *s, scope);
                }
            }
            StmtKind::Break | StmtKind::Continue | StmtKind::Pass => {}
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn function(
        &mut self,
        name: Option<(String, Span)>,
        params: &Params,
        span: Span,
        scope: usize,
        parent: Option<usize>,
        lambda: bool,
        body: impl FnOnce(&mut Builder, usize, usize),
        stmts_for_globals: &[Stmt],
    ) {
        for p in &params.params {
            if let Some(d) = &p.default {
                self.expr(d, scope, parent);
            }
        }
        let id = self.functions.len();
        let info = |p: &Param| ParamInfo { name: p.name.clone(), span: p.span, has_default: p.default.is_some() };
        let (fname, name_span) = match name {
            Some((n, s)) => (Some(n), s),
            None => (None, Span::new(span.start, span.start + 6)),
        };
        self.functions.push(Function {
            id,
            name: fname,
            name_span,
            span,
            params: params.params.iter().map(info).collect(),
            vararg: params.vararg.as_ref().map(info),
            parent,
            scope: 0,
            lambda,
            returns_value: false,
            top_level: scope == 0 && !lambda,
        });
        let inner = self.new_scope(if lambda { ScopeKind::Lambda } else { ScopeKind::Function }, scope, span, Some(id));
        self.functions[id].scope = inner;
        let mut globals = HashSet::new();
        collect_globals(stmts_for_globals, &mut globals);
        self.scopes[inner].globals = globals;
        for p in params.params.iter().chain(params.vararg.iter()).chain(params.kwarg.iter()) {
            self.bind(&p.name, p.span, inner, SymbolKind::Parameter, false);
        }
        body(self, inner, id);
    }

    /// Binds the names an assignment target assigns, and reads the rest.
    fn target(&mut self, t: &Expr, scope: usize, function: Option<usize>, kind: SymbolKind, unpacked: bool) {
        match &t.kind {
            ExprKind::Name(n) => {
                self.bind(n, t.span, scope, kind, unpacked);
            }
            ExprKind::Tuple(items) | ExprKind::List(items) => {
                let several = items.len() > 1;
                for item in items {
                    self.target(item, scope, function, kind, unpacked || several);
                }
            }
            ExprKind::Starred(inner) => self.target(inner, scope, function, kind, unpacked),
            _ => self.expr(t, scope, function),
        }
    }

    // ---- expressions -----------------------------------------------------

    fn expr(&mut self, e: &Expr, scope: usize, function: Option<usize>) {
        match &e.kind {
            ExprKind::Name(n) => self.read(n, e.span, scope),
            ExprKind::Lambda { params, body } => {
                let body = body.as_ref();
                self.function(None, params, e.span, scope, function, true, |b, inner, f| {
                    b.expr(body, inner, Some(f));
                }, &[]);
            }
            ExprKind::Comp { elt, value, clauses, .. } => {
                let Some(first) = clauses.first() else { return };
                self.expr(&first.iter, scope, function);
                let inner = self.new_scope(ScopeKind::Comprehension, scope, e.span, None);
                for (k, c) in clauses.iter().enumerate() {
                    if k > 0 {
                        self.expr(&c.iter, inner, function);
                    }
                    self.target(&c.target, inner, function, SymbolKind::ComprehensionVariable, false);
                    for cond in &c.ifs {
                        self.expr(cond, inner, function);
                    }
                }
                self.expr(elt, inner, function);
                if let Some(v) = value {
                    self.expr(v, inner, function);
                }
            }
            ExprKind::Walrus { target, value } => {
                self.expr(value, scope, function);
                self.target(target, scope, function, SymbolKind::Variable, false);
            }
            _ => each_child(e, &mut |child| self.expr(child, scope, function)),
        }
    }
}

/// The names `global` statements in a function body declare, nested functions left out.
fn collect_globals(stmts: &[Stmt], out: &mut HashSet<String>) {
    for stmt in stmts {
        match &stmt.kind {
            StmtKind::Global(names) => {
                for (n, _) in names {
                    out.insert(n.clone());
                }
            }
            StmtKind::Def { .. } => {}
            _ => {
                for block in stmt_blocks(stmt) {
                    collect_globals(block, out);
                }
            }
        }
    }
}
