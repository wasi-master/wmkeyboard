//! Where every name lives, worked out once after parsing, by Python's own
//! scoping rules: a name a function assigns anywhere in its body is that
//! function's variable (unless declared `global` or `nonlocal`), a name it
//! only reads comes from the nearest enclosing function that has it, then
//! from the module, then from the builtins; class bodies are skipped on the
//! way out. The interpreter then reads a function's variables by slot,
//! without looking a name up, and a variable read before it is assigned is
//! an UnboundLocalError, as in CPython.
//!
//! The same pass checks the places a `yield` may stand and marks the
//! statements a generator has to run a step at a time.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use super::ast::*;
use super::error::PyError;

type Res<T> = Result<T, PyError>;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Module,
    Class,
    Function,
    Comprehension,
}

struct Scope {
    kind: Kind,
    /// A function's or comprehension's variables, by slot.
    slots: HashMap<Rc<str>, u32>,
    globals: HashSet<Rc<str>>,
    nonlocals: HashSet<Rc<str>>,
}

impl Scope {
    fn new(kind: Kind) -> Scope {
        Scope { kind, slots: HashMap::new(), globals: HashSet::new(), nonlocals: HashSet::new() }
    }
}

/// What one scope's own code binds and declares, without looking inside
/// the functions, lambdas and classes it defines.
#[derive(Default)]
struct Bindings {
    bound: Vec<Rc<str>>,
    seen: HashSet<Rc<str>>,
    globals: Vec<Rc<str>>,
    nonlocals: Vec<Rc<str>>,
    yields: bool,
}

impl Bindings {
    fn bind(&mut self, n: &Rc<str>) {
        if self.seen.insert(n.clone()) {
            self.bound.push(n.clone());
        }
    }
}

/// Resolve a whole module: its top level, and everything defined in it.
pub(crate) fn resolve_module(program: &mut [Stmt]) -> Res<()> {
    let mut b = Bindings::default();
    collect_block(program, &mut b, 0);
    let mut r = Resolver { scopes: Vec::new(), line: program.first().map_or(1, |s| s.line), generator: false, dynamic: false };
    if let Some(n) = b.nonlocals.first() {
        return Err(r.syntax(format!("nonlocal declaration not allowed at module level ('{n}')")));
    }
    let mut module = Scope::new(Kind::Module);
    module.globals.extend(b.globals);
    r.scopes.push(module);
    r.block(program)
}

/// Resolve code for `exec` or `eval` that runs with a namespace of its own
/// over the module's: its names are looked up and bound by name, as in a
/// class body, then in the module.
pub(crate) fn resolve_dynamic(program: &mut [Stmt]) -> Res<()> {
    let mut b = Bindings::default();
    collect_block(program, &mut b, 0);
    let mut r = Resolver { scopes: Vec::new(), line: program.first().map_or(1, |s| s.line), generator: false, dynamic: true };
    if let Some(n) = b.nonlocals.first() {
        return Err(r.syntax(format!("nonlocal declaration not allowed at module level ('{n}')")));
    }
    r.scopes.push(Scope::new(Kind::Module));
    let mut namespace = Scope::new(Kind::Class);
    namespace.globals.extend(b.globals);
    r.scopes.push(namespace);
    r.block(program)
}

struct Resolver {
    scopes: Vec<Scope>,
    line: u32,
    /// The innermost function being resolved has a `yield`.
    generator: bool,
    /// Code for `exec` or `eval`, whose top level is a namespace dict.
    dynamic: bool,
}

impl Resolver {
    fn syntax(&self, message: impl Into<String>) -> PyError {
        PyError::at(message, "SyntaxError", Some(self.line))
    }

    fn top(&self) -> &Scope {
        self.scopes.last().unwrap()
    }

    // MARK: - looking names up

    /// Where a load of [id] in the innermost scope reads from.
    fn load(&self, id: &str) -> NameAt {
        let at = self.scopes.len() - 1;
        let s = self.top();
        if s.globals.contains(id) {
            return NameAt::Global;
        }
        if s.nonlocals.contains(id) {
            return self.outer_var(id, at).map_or(NameAt::Global, |(d, slot)| NameAt::Free(d, slot));
        }
        match s.kind {
            Kind::Module => NameAt::Global,
            Kind::Class => NameAt::Class(self.outer_var(id, at)),
            Kind::Function | Kind::Comprehension => match s.slots.get(id) {
                Some(slot) => NameAt::Local(*slot),
                None => self.outer_var(id, at).map_or(NameAt::Global, |(d, slot)| NameAt::Free(d, slot)),
            },
        }
    }

    /// Where a store to [id] in the innermost scope writes.
    fn store(&self, id: &str) -> NameAt {
        match self.load(id) {
            NameAt::Class(_) => NameAt::Class(None),
            other => other,
        }
    }

    /// The nearest function or comprehension outside scopes[at] whose
    /// variable [id] is: (scopes out, slot). None when it is a global.
    fn outer_var(&self, id: &str, at: usize) -> Option<(u32, u32)> {
        let mut depth = 0;
        for s in self.scopes[..at].iter().rev() {
            depth += 1;
            match s.kind {
                Kind::Module => return None,
                Kind::Class => continue,
                Kind::Function | Kind::Comprehension => {
                    if s.globals.contains(id) {
                        return None;
                    }
                    if s.nonlocals.contains(id) {
                        continue;
                    }
                    if let Some(slot) = s.slots.get(id) {
                        return Some((depth, *slot));
                    }
                }
            }
        }
        None
    }

    /// Where `name := value` binds inside a comprehension: in the scope the
    /// comprehension is written in, as PEP 572 says.
    fn walrus_store(&self, id: &str) -> NameAt {
        for (i, s) in self.scopes.iter().enumerate().rev() {
            // How many comprehension scopes lie between here and the innermost.
            let depth = (self.scopes.len() - 1 - i) as u32;
            if s.kind != Kind::Comprehension {
                return match s.kind {
                    Kind::Module => NameAt::Global,
                    Kind::Class => NameAt::Class(None),
                    _ if s.globals.contains(id) => NameAt::Global,
                    _ if s.nonlocals.contains(id) => {
                        self.outer_var(id, i).map_or(NameAt::Global, |(d, slot)| NameAt::Free(depth + d, slot))
                    }
                    _ => match s.slots.get(id) {
                        Some(slot) if depth == 0 => NameAt::Local(*slot),
                        Some(slot) => NameAt::Free(depth, *slot),
                        None => NameAt::Global,
                    },
                };
            }
        }
        NameAt::Global
    }

    fn set_load(&self, n: &mut Name) {
        n.at = self.load(&n.id);
    }

    fn set_store(&self, n: &mut Name) {
        n.at = self.store(&n.id);
    }

    // MARK: - scopes

    /// A function's (or lambda's) scope: parameters first, then every name
    /// the body binds.
    fn function_scope(&self, params: &Params, b: Bindings) -> Res<(Scope, Vec<Rc<str>>)> {
        let mut scope = Scope::new(Kind::Function);
        let mut names = params.names();
        for n in &b.globals {
            if names.contains(n) {
                return Err(self.syntax(format!("name '{n}' is parameter and global")));
            }
        }
        for n in &b.nonlocals {
            if names.contains(n) {
                return Err(self.syntax(format!("name '{n}' is parameter and nonlocal")));
            }
            if b.globals.contains(n) {
                return Err(self.syntax(format!("name '{n}' is nonlocal and global")));
            }
        }
        scope.globals.extend(b.globals.iter().cloned());
        scope.nonlocals.extend(b.nonlocals.iter().cloned());
        for n in b.bound {
            if !scope.globals.contains(&n) && !scope.nonlocals.contains(&n) && !names.contains(&n) {
                names.push(n);
            }
        }
        for (i, n) in names.iter().enumerate() {
            scope.slots.insert(n.clone(), i as u32);
        }
        Ok((scope, names))
    }

    /// Every `nonlocal` of the scope about to be pushed names a variable of an enclosing function.
    fn check_nonlocals(&self, scope: &Scope) -> Res<()> {
        for n in &scope.nonlocals {
            if self.outer_var_from_top(n).is_none() {
                return Err(self.syntax(format!("no binding for nonlocal '{n}' found")));
            }
        }
        Ok(())
    }

    fn outer_var_from_top(&self, id: &str) -> Option<(u32, u32)> {
        self.outer_var(id, self.scopes.len())
    }

    fn def(&mut self, def: &mut Rc<FuncDef>, bind: &mut Name) -> Res<()> {
        let d = Rc::get_mut(def).expect("a freshly parsed def has one owner");
        for e in &mut d.decorators {
            self.expr(e)?;
        }
        self.defaults(&mut d.params)?;
        self.set_store(bind);
        let mut b = Bindings::default();
        collect_block(&d.body, &mut b, 0);
        let generator = b.yields;
        let (scope, names) = self.function_scope(&d.params, b)?;
        self.check_nonlocals(&scope)?;
        self.scopes.push(scope);
        let saved = std::mem::replace(&mut self.generator, generator);
        let r = self.block(&mut d.body);
        self.generator = saved;
        self.scopes.pop();
        r?;
        d.frame = Frame { names, generator };
        Ok(())
    }

    fn lambda(&mut self, def: &mut Rc<LambdaDef>) -> Res<()> {
        let d = Rc::get_mut(def).expect("a freshly parsed lambda has one owner");
        self.defaults(&mut d.params)?;
        if contains_yield(&d.body) {
            return Err(self.syntax("'yield' inside a lambda is not supported"));
        }
        let mut b = Bindings::default();
        collect_expr(&d.body, &mut b, 0);
        let (scope, names) = self.function_scope(&d.params, b)?;
        self.scopes.push(scope);
        let saved = std::mem::replace(&mut self.generator, false);
        let r = self.expr(&mut d.body);
        self.generator = saved;
        self.scopes.pop();
        r?;
        d.frame = Frame { names, generator: false };
        Ok(())
    }

    fn defaults(&mut self, params: &mut Params) -> Res<()> {
        for p in params.positional.iter_mut().chain(params.keyword_only.iter_mut()) {
            if let Some(e) = &mut p.default {
                self.expr(e)?;
            }
        }
        Ok(())
    }

    fn class(&mut self, def: &mut Rc<ClassDef>, bind: &mut Name) -> Res<()> {
        let d = Rc::get_mut(def).expect("a freshly parsed class has one owner");
        for e in d.bases.iter_mut().chain(d.decorators.iter_mut()).chain(d.keywords.iter_mut().map(|(_, e)| e)) {
            self.expr(e)?;
        }
        self.set_store(bind);
        let mut b = Bindings::default();
        collect_block(&d.body, &mut b, 0);
        if b.yields {
            return Err(self.syntax("'yield' outside function"));
        }
        let mut scope = Scope::new(Kind::Class);
        scope.globals.extend(b.globals);
        scope.nonlocals.extend(b.nonlocals);
        self.check_nonlocals(&scope)?;
        self.scopes.push(scope);
        let saved = std::mem::replace(&mut self.generator, false);
        let r = self.block(&mut d.body);
        self.generator = saved;
        self.scopes.pop();
        r
    }

    fn comprehension(&mut self, c: &mut Comprehension) -> Res<()> {
        let Some((first, rest)) = c.clauses.split_first_mut() else { return Ok(()) };
        // The first iterable is evaluated where the comprehension is written.
        self.expr(&mut first.iterable)?;
        let mut names: Vec<Rc<str>> = Vec::new();
        for clause in std::iter::once(&*first).chain(rest.iter()) {
            target_names(&clause.target, &mut names);
        }
        let mut scope = Scope::new(Kind::Comprehension);
        for (i, n) in names.iter().enumerate() {
            scope.slots.insert(n.clone(), i as u32);
        }
        self.scopes.push(scope);
        let r = self.comprehension_body(c);
        self.scopes.pop();
        r?;
        c.frame = Frame { names, generator: false };
        Ok(())
    }

    fn comprehension_body(&mut self, c: &mut Comprehension) -> Res<()> {
        for (i, clause) in c.clauses.iter_mut().enumerate() {
            if i > 0 {
                self.expr(&mut clause.iterable)?;
            }
            self.target(&mut clause.target)?;
            for cond in &mut clause.conditions {
                self.expr(cond)?;
            }
        }
        self.expr(&mut c.element)?;
        if let Some(v) = &mut c.value {
            self.expr(v)?;
        }
        Ok(())
    }

    // MARK: - statements

    fn block(&mut self, stmts: &mut [Stmt]) -> Res<()> {
        for s in stmts {
            self.stmt(s)?;
        }
        Ok(())
    }

    fn stmt(&mut self, s: &mut Stmt) -> Res<()> {
        self.line = s.line;
        self.check_yields(s)?;
        match &mut s.kind {
            StmtKind::Expr(e) => self.expr(e)?,
            StmtKind::Assign(targets, value) => {
                self.expr(value)?;
                for t in targets {
                    self.target(t)?;
                }
            }
            StmtKind::AugAssign(target, _, value) => {
                self.expr(value)?;
                self.target(target)?;
            }
            StmtKind::If(branches, else_body) => {
                for (cond, body) in branches {
                    self.expr(cond)?;
                    self.block(body)?;
                }
                if let Some(b) = else_body {
                    self.block(b)?;
                }
            }
            StmtKind::While(cond, body, else_body) => {
                self.expr(cond)?;
                self.block(body)?;
                if let Some(b) = else_body {
                    self.block(b)?;
                }
            }
            StmtKind::For(target, iterable, body, else_body) => {
                self.expr(iterable)?;
                self.target(target)?;
                self.block(body)?;
                if let Some(b) = else_body {
                    self.block(b)?;
                }
            }
            StmtKind::FuncDef(def, bind) => self.def(def, bind)?,
            StmtKind::ClassDef(def, bind) => self.class(def, bind)?,
            StmtKind::With(items, body) => {
                for (manager, target) in items {
                    self.expr(manager)?;
                    if let Some(t) = target {
                        self.target(t)?;
                    }
                }
                self.block(body)?;
            }
            StmtKind::AnnAssign(target, _, value) => {
                if let Some(v) = value {
                    self.expr(v)?;
                }
                self.target(target)?;
            }
            StmtKind::Assert(test, message) => {
                self.expr(test)?;
                if let Some(m) = message {
                    self.expr(m)?;
                }
            }
            StmtKind::Return(value) => {
                if let Some(v) = value {
                    self.expr(v)?;
                }
            }
            StmtKind::Try(t) => {
                self.block(&mut t.body)?;
                for h in &mut t.handlers {
                    if let Some(b) = &mut h.binding {
                        self.set_store(b);
                    }
                    self.block(&mut h.body)?;
                }
                if let Some(b) = &mut t.else_body {
                    self.block(b)?;
                }
                if let Some(b) = &mut t.finally_body {
                    self.block(b)?;
                }
            }
            StmtKind::Raise(value, cause) => {
                if let Some(v) = value {
                    self.expr(v)?;
                }
                if let Some(c) = cause {
                    self.expr(c)?;
                }
            }
            StmtKind::Import { target, names, .. } => {
                if let Some(t) = target {
                    self.set_store(t);
                }
                for (_, n) in names.iter_mut().flatten() {
                    self.set_store(n);
                }
            }
            StmtKind::ImportStar(_, at) => {
                // Which names it binds is known only when it runs, so only a
                // module (or an `exec` namespace) may hold one.
                let top = self.scopes.len() == if self.dynamic { 2 } else { 1 };
                if !top {
                    return Err(self.syntax("import * only allowed at module level"));
                }
                at.at = if self.dynamic { NameAt::Class(None) } else { NameAt::Global };
            }
            StmtKind::Del(targets) => {
                for t in targets {
                    self.target(t)?;
                }
            }
            StmtKind::Match(m) => {
                self.expr(&mut m.subject)?;
                for case in &mut m.cases {
                    self.pattern(&mut case.pattern)?;
                    if let Some(g) = &mut case.guard {
                        self.expr(g)?;
                    }
                    self.block(&mut case.body)?;
                }
            }
            StmtKind::Nonlocal(_) | StmtKind::Global(_) | StmtKind::Break | StmtKind::Continue | StmtKind::Pass => {}
        }
        // A compound statement runs a step at a time when one of its own
        // statements does.
        s.yields = s.yields
            || match &s.kind {
                StmtKind::If(branches, else_body) => {
                    branches.iter().any(|(_, b)| any_yields(b)) || else_body.as_deref().is_some_and(any_yields)
                }
                StmtKind::While(_, body, else_body) | StmtKind::For(_, _, body, else_body) => {
                    any_yields(body) || else_body.as_deref().is_some_and(any_yields)
                }
                StmtKind::With(_, body) => any_yields(body),
                StmtKind::Try(t) => {
                    any_yields(&t.body)
                        || t.handlers.iter().any(|h| any_yields(&h.body))
                        || t.else_body.as_deref().is_some_and(any_yields)
                        || t.finally_body.as_deref().is_some_and(any_yields)
                }
                StmtKind::Match(m) => m.cases.iter().any(|c| any_yields(&c.body)),
                _ => false,
            };
        Ok(())
    }

    /// A `yield` stands as a statement of its own or on the right of `=`;
    /// anywhere else in an expression is more than a generator here can pause in.
    fn check_yields(&self, s: &mut Stmt) -> Res<()> {
        let own_yield = |e: &Expr| matches!(e, Expr::Yield(_) | Expr::YieldFrom(_));
        let operand_yields = |e: &Expr| match e {
            Expr::Yield(Some(v)) | Expr::YieldFrom(v) => contains_yield(v),
            _ => false,
        };
        let (statement_yield, stray) = match &s.kind {
            StmtKind::Expr(e) if own_yield(e) => (true, operand_yields(e)),
            StmtKind::Assign(targets, value) if own_yield(value) => (true, operand_yields(value) || targets.iter().any(contains_yield)),
            _ => (false, header_exprs(&s.kind).into_iter().any(contains_yield)),
        };
        if (statement_yield || stray) && !self.generator {
            return Err(self.syntax("'yield' outside function"));
        }
        if stray {
            return Err(self.syntax(
                "a 'yield' here sits inside another expression; write it as a statement of its own or as 'x = yield value'",
            ));
        }
        s.yields = statement_yield;
        Ok(())
    }

    // MARK: - expressions

    /// An assignment target: names are stores, anything inside them loads.
    fn target(&mut self, t: &mut Expr) -> Res<()> {
        match t {
            Expr::Name(n) => self.set_store(n),
            Expr::Tuple(items) | Expr::List(items) => {
                for i in items {
                    self.target(i)?;
                }
            }
            Expr::Starred(inner) => self.target(inner)?,
            other => self.expr(other)?,
        }
        Ok(())
    }

    fn expr(&mut self, e: &mut Expr) -> Res<()> {
        match e {
            Expr::Name(n) => self.set_load(n),
            Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Float(_) | Expr::Str(_) | Expr::Bytes(_) => {}
            Expr::FString(parts) => self.fparts(parts)?,
            Expr::List(items) | Expr::Tuple(items) | Expr::Set(items) => {
                for i in items {
                    self.expr(i)?;
                }
            }
            Expr::Dict(pairs) => {
                for (k, v) in pairs {
                    self.expr(k)?;
                    self.expr(v)?;
                }
            }
            Expr::Unary(_, x) | Expr::Starred(x) | Expr::YieldFrom(x) => self.expr(x)?,
            Expr::Yield(x) => {
                if let Some(x) = x {
                    self.expr(x)?;
                }
            }
            Expr::Binary(_, a, b) | Expr::BoolOp(_, a, b) | Expr::Index(a, b) => {
                self.expr(a)?;
                self.expr(b)?;
            }
            Expr::Compare(left, pairs) => {
                self.expr(left)?;
                for (_, r) in pairs {
                    self.expr(r)?;
                }
            }
            Expr::Ternary(t) => {
                self.expr(&mut t.0)?;
                self.expr(&mut t.1)?;
                self.expr(&mut t.2)?;
            }
            Expr::Call(callee, args, kwargs) => {
                self.expr(callee)?;
                for a in args {
                    self.expr(a)?;
                }
                for (_, v) in kwargs {
                    self.expr(v)?;
                }
            }
            Expr::Slice(s) => {
                self.expr(&mut s.0)?;
                for part in [&mut s.1, &mut s.2, &mut s.3].into_iter().flatten() {
                    self.expr(part)?;
                }
            }
            Expr::Attribute(obj, _) => self.expr(obj)?,
            Expr::Lambda(def) => self.lambda(def)?,
            Expr::Comprehension(c) => {
                if comprehension_yields(c) {
                    return Err(self.syntax("'yield' inside a comprehension"));
                }
                self.comprehension(Rc::get_mut(c).expect("a freshly parsed comprehension has one owner"))?
            }
            Expr::Walrus(w) => {
                self.expr(&mut w.1)?;
                w.0.at = if self.top().kind == Kind::Comprehension { self.walrus_store(&w.0.id) } else { self.store(&w.0.id) };
            }
        }
        Ok(())
    }

    fn fparts(&mut self, parts: &mut [FPart]) -> Res<()> {
        for p in parts {
            if let FPart::Expression { expr, spec, .. } = p {
                self.expr(expr)?;
                if let Some(s) = spec {
                    self.fparts(s)?;
                }
            }
        }
        Ok(())
    }

    fn pattern(&mut self, p: &mut Pattern) -> Res<()> {
        match p {
            Pattern::Wildcard => {}
            Pattern::Capture(n) => self.set_store(n),
            Pattern::Value(e) => self.expr(e)?,
            Pattern::Sequence(items, star) => {
                for i in items {
                    self.pattern(i)?;
                }
                if let Some((_, Some(n))) = star {
                    self.set_store(n);
                }
            }
            Pattern::Mapping(pairs, rest) => {
                for (k, v) in pairs {
                    self.expr(k)?;
                    self.pattern(v)?;
                }
                if let Some(n) = rest {
                    self.set_store(n);
                }
            }
            Pattern::Class(cls, positional, keywords) => {
                self.expr(cls)?;
                for i in positional {
                    self.pattern(i)?;
                }
                for (_, v) in keywords {
                    self.pattern(v)?;
                }
            }
            Pattern::Or(alts) => {
                for a in alts {
                    self.pattern(a)?;
                }
            }
            Pattern::As(inner, n) => {
                self.pattern(inner)?;
                self.set_store(n);
            }
        }
        Ok(())
    }
}

fn any_yields(stmts: &[Stmt]) -> bool {
    stmts.iter().any(|s| s.yields)
}

/// The expressions a statement evaluates itself, not counting the statements it holds.
fn header_exprs(kind: &StmtKind) -> Vec<&Expr> {
    match kind {
        StmtKind::Expr(e) => vec![e],
        StmtKind::Assign(targets, value) => targets.iter().chain(std::iter::once(value)).collect(),
        StmtKind::AugAssign(t, _, v) => vec![t, v],
        StmtKind::If(branches, _) => branches.iter().map(|(c, _)| c).collect(),
        StmtKind::While(c, ..) => vec![c],
        StmtKind::For(t, it, ..) => vec![t, it],
        StmtKind::With(items, _) => items.iter().flat_map(|(m, t)| std::iter::once(m).chain(t.iter())).collect(),
        StmtKind::AnnAssign(t, _, v) => std::iter::once(t).chain(v.iter()).collect(),
        StmtKind::Assert(t, m) => std::iter::once(t).chain(m.iter()).collect(),
        StmtKind::Return(v) => v.iter().collect(),
        StmtKind::Raise(v, c) => v.iter().chain(c.iter()).collect(),
        StmtKind::Del(t) => t.iter().collect(),
        StmtKind::Match(m) => std::iter::once(&m.subject).chain(m.cases.iter().filter_map(|c| c.guard.as_ref())).collect(),
        StmtKind::FuncDef(d, _) => {
            d.decorators.iter().chain(d.params.positional.iter().chain(d.params.keyword_only.iter()).filter_map(|p| p.default.as_ref())).collect()
        }
        StmtKind::ClassDef(d, _) => d.bases.iter().chain(d.decorators.iter()).chain(d.keywords.iter().map(|(_, e)| e)).collect(),
        _ => Vec::new(),
    }
}

/// Whether [e] has a `yield` in it, outside any lambda.
fn contains_yield(e: &Expr) -> bool {
    match e {
        Expr::Yield(_) | Expr::YieldFrom(_) => true,
        Expr::Name(_) | Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Float(_) | Expr::Str(_) | Expr::Bytes(_) | Expr::Lambda(_) => {
            false
        }
        Expr::FString(parts) => fparts_yield(parts),
        Expr::List(items) | Expr::Tuple(items) | Expr::Set(items) => items.iter().any(contains_yield),
        Expr::Dict(pairs) => pairs.iter().any(|(k, v)| contains_yield(k) || contains_yield(v)),
        Expr::Unary(_, x) | Expr::Starred(x) => contains_yield(x),
        Expr::Binary(_, a, b) | Expr::BoolOp(_, a, b) | Expr::Index(a, b) => contains_yield(a) || contains_yield(b),
        Expr::Compare(l, pairs) => contains_yield(l) || pairs.iter().any(|(_, r)| contains_yield(r)),
        Expr::Ternary(t) => contains_yield(&t.0) || contains_yield(&t.1) || contains_yield(&t.2),
        Expr::Call(c, args, kwargs) => contains_yield(c) || args.iter().any(contains_yield) || kwargs.iter().any(|(_, v)| contains_yield(v)),
        Expr::Slice(s) => contains_yield(&s.0) || [&s.1, &s.2, &s.3].into_iter().flatten().any(contains_yield),
        Expr::Attribute(o, _) => contains_yield(o),
        Expr::Comprehension(c) => comprehension_yields(c),
        Expr::Walrus(w) => contains_yield(&w.1),
    }
}

fn fparts_yield(parts: &[FPart]) -> bool {
    parts.iter().any(|p| match p {
        FPart::Literal(_) => false,
        FPart::Expression { expr, spec, .. } => contains_yield(expr) || spec.as_deref().is_some_and(fparts_yield),
    })
}

fn comprehension_yields(c: &Comprehension) -> bool {
    contains_yield(&c.element)
        || c.value.as_ref().is_some_and(contains_yield)
        || c.clauses.iter().any(|cl| contains_yield(&cl.iterable) || cl.conditions.iter().any(contains_yield))
}

/// The names an assignment target binds, in order.
fn target_names(t: &Expr, out: &mut Vec<Rc<str>>) {
    match t {
        Expr::Name(n) => {
            if !out.contains(&n.id) {
                out.push(n.id.clone());
            }
        }
        Expr::Tuple(items) | Expr::List(items) => {
            for i in items {
                target_names(i, out);
            }
        }
        Expr::Starred(inner) => target_names(inner, out),
        _ => {}
    }
}

// MARK: - what a scope binds

fn collect_block(stmts: &[Stmt], b: &mut Bindings, comp: u32) {
    for s in stmts {
        collect_stmt(s, b, comp);
    }
}

fn collect_stmt(s: &Stmt, b: &mut Bindings, comp: u32) {
    match &s.kind {
        StmtKind::Expr(e) => collect_expr(e, b, comp),
        StmtKind::Assign(targets, value) => {
            for t in targets {
                collect_target(t, b);
            }
            collect_expr(value, b, comp);
        }
        StmtKind::AugAssign(t, _, v) => {
            collect_target(t, b);
            collect_expr(v, b, comp);
        }
        StmtKind::If(branches, else_body) => {
            for (c, body) in branches {
                collect_expr(c, b, comp);
                collect_block(body, b, comp);
            }
            if let Some(e) = else_body {
                collect_block(e, b, comp);
            }
        }
        StmtKind::While(c, body, else_body) => {
            collect_expr(c, b, comp);
            collect_block(body, b, comp);
            if let Some(e) = else_body {
                collect_block(e, b, comp);
            }
        }
        StmtKind::For(t, it, body, else_body) => {
            collect_target(t, b);
            collect_expr(it, b, comp);
            collect_block(body, b, comp);
            if let Some(e) = else_body {
                collect_block(e, b, comp);
            }
        }
        StmtKind::FuncDef(d, bind) => {
            b.bind(&bind.id);
            for e in &d.decorators {
                collect_expr(e, b, comp);
            }
            for p in d.params.positional.iter().chain(d.params.keyword_only.iter()) {
                if let Some(e) = &p.default {
                    collect_expr(e, b, comp);
                }
            }
        }
        StmtKind::ClassDef(d, bind) => {
            b.bind(&bind.id);
            for e in d.bases.iter().chain(d.decorators.iter()).chain(d.keywords.iter().map(|(_, e)| e)) {
                collect_expr(e, b, comp);
            }
        }
        StmtKind::With(items, body) => {
            for (m, t) in items {
                collect_expr(m, b, comp);
                if let Some(t) = t {
                    collect_target(t, b);
                }
            }
            collect_block(body, b, comp);
        }
        StmtKind::AnnAssign(t, _, v) => {
            collect_target(t, b);
            if let Some(v) = v {
                collect_expr(v, b, comp);
            }
        }
        StmtKind::Assert(t, m) => {
            collect_expr(t, b, comp);
            if let Some(m) = m {
                collect_expr(m, b, comp);
            }
        }
        StmtKind::Nonlocal(names) => b.nonlocals.extend(names.iter().cloned()),
        StmtKind::Global(names) => b.globals.extend(names.iter().cloned()),
        StmtKind::Return(v) => {
            if let Some(v) = v {
                collect_expr(v, b, comp);
            }
        }
        StmtKind::Try(t) => {
            collect_block(&t.body, b, comp);
            for h in &t.handlers {
                if let Some(n) = &h.binding {
                    b.bind(&n.id);
                }
                collect_block(&h.body, b, comp);
            }
            if let Some(e) = &t.else_body {
                collect_block(e, b, comp);
            }
            if let Some(f) = &t.finally_body {
                collect_block(f, b, comp);
            }
        }
        StmtKind::Raise(v, c) => {
            for e in v.iter().chain(c.iter()) {
                collect_expr(e, b, comp);
            }
        }
        StmtKind::ImportStar(..) => {}
        StmtKind::Import { target, names, .. } => {
            if let Some(t) = target {
                b.bind(&t.id);
            }
            for (_, n) in names.iter().flatten() {
                b.bind(&n.id);
            }
        }
        StmtKind::Del(targets) => {
            for t in targets {
                collect_target(t, b);
            }
        }
        StmtKind::Match(m) => {
            collect_expr(&m.subject, b, comp);
            for case in &m.cases {
                collect_pattern(&case.pattern, b);
                if let Some(g) = &case.guard {
                    collect_expr(g, b, comp);
                }
                collect_block(&case.body, b, comp);
            }
        }
        StmtKind::Break | StmtKind::Continue | StmtKind::Pass => {}
    }
}

fn collect_target(t: &Expr, b: &mut Bindings) {
    match t {
        Expr::Name(n) => b.bind(&n.id),
        Expr::Tuple(items) | Expr::List(items) => {
            for i in items {
                collect_target(i, b);
            }
        }
        Expr::Starred(inner) => collect_target(inner, b),
        other => collect_expr(other, b, 0),
    }
}

fn collect_pattern(p: &Pattern, b: &mut Bindings) {
    match p {
        Pattern::Wildcard | Pattern::Value(_) => {}
        Pattern::Capture(n) => b.bind(&n.id),
        Pattern::Sequence(items, star) => {
            for i in items {
                collect_pattern(i, b);
            }
            if let Some((_, Some(n))) = star {
                b.bind(&n.id);
            }
        }
        Pattern::Mapping(pairs, rest) => {
            for (_, v) in pairs {
                collect_pattern(v, b);
            }
            if let Some(n) = rest {
                b.bind(&n.id);
            }
        }
        Pattern::Class(_, positional, keywords) => {
            for i in positional {
                collect_pattern(i, b);
            }
            for (_, v) in keywords {
                collect_pattern(v, b);
            }
        }
        Pattern::Or(alts) => {
            for a in alts {
                collect_pattern(a, b);
            }
        }
        Pattern::As(inner, n) => {
            collect_pattern(inner, b);
            b.bind(&n.id);
        }
    }
}

/// An expression binds only through `:=` (also from inside a
/// comprehension, whose own loop variables are its own), and makes its
/// function a generator with `yield`.
fn collect_expr(e: &Expr, b: &mut Bindings, comp: u32) {
    match e {
        Expr::Walrus(w) => {
            b.bind(&w.0.id);
            collect_expr(&w.1, b, comp);
        }
        Expr::Yield(v) => {
            if comp == 0 {
                b.yields = true;
            }
            if let Some(v) = v {
                collect_expr(v, b, comp);
            }
        }
        Expr::YieldFrom(v) => {
            if comp == 0 {
                b.yields = true;
            }
            collect_expr(v, b, comp);
        }
        Expr::Name(_) | Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Float(_) | Expr::Str(_) | Expr::Bytes(_) => {}
        Expr::FString(parts) => collect_fparts(parts, b, comp),
        Expr::List(items) | Expr::Tuple(items) | Expr::Set(items) => {
            for i in items {
                collect_expr(i, b, comp);
            }
        }
        Expr::Dict(pairs) => {
            for (k, v) in pairs {
                collect_expr(k, b, comp);
                collect_expr(v, b, comp);
            }
        }
        Expr::Unary(_, x) | Expr::Starred(x) => collect_expr(x, b, comp),
        Expr::Binary(_, x, y) | Expr::BoolOp(_, x, y) | Expr::Index(x, y) => {
            collect_expr(x, b, comp);
            collect_expr(y, b, comp);
        }
        Expr::Compare(l, pairs) => {
            collect_expr(l, b, comp);
            for (_, r) in pairs {
                collect_expr(r, b, comp);
            }
        }
        Expr::Ternary(t) => {
            collect_expr(&t.0, b, comp);
            collect_expr(&t.1, b, comp);
            collect_expr(&t.2, b, comp);
        }
        Expr::Call(c, args, kwargs) => {
            collect_expr(c, b, comp);
            for a in args {
                collect_expr(a, b, comp);
            }
            for (_, v) in kwargs {
                collect_expr(v, b, comp);
            }
        }
        Expr::Slice(s) => {
            collect_expr(&s.0, b, comp);
            for part in [&s.1, &s.2, &s.3].into_iter().flatten() {
                collect_expr(part, b, comp);
            }
        }
        Expr::Attribute(o, _) => collect_expr(o, b, comp),
        // A lambda's defaults are evaluated here; its body is its own scope.
        Expr::Lambda(def) => {
            for p in def.params.positional.iter().chain(def.params.keyword_only.iter()) {
                if let Some(d) = &p.default {
                    collect_expr(d, b, comp);
                }
            }
        }
        Expr::Comprehension(c) => {
            for (i, clause) in c.clauses.iter().enumerate() {
                collect_expr(&clause.iterable, b, if i == 0 { comp } else { comp + 1 });
                for cond in &clause.conditions {
                    collect_expr(cond, b, comp + 1);
                }
            }
            collect_expr(&c.element, b, comp + 1);
            if let Some(v) = &c.value {
                collect_expr(v, b, comp + 1);
            }
        }
    }
}

fn collect_fparts(parts: &[FPart], b: &mut Bindings, comp: u32) {
    for p in parts {
        if let FPart::Expression { expr, spec, .. } = p {
            collect_expr(expr, b, comp);
            if let Some(s) = spec {
                collect_fparts(s, b, comp);
            }
        }
    }
}
