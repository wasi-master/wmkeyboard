//! Port of PyInterpreter.kt: the tree-walking evaluator.
//!
//! Sandbox guarantees: no file, network or system access (`import` reaches only
//! the standard namespaces and host modules), a deterministic step budget over
//! loops, recursion and regular expressions alike, size caps on containers and
//! strings, a call-depth cap, and a cancel flag and deadline polled on the step
//! path. Those aborts are uncatchable.

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};

use super::builtins::EXCEPTION_KINDS;
use std::rc::{Rc, Weak};
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use std::sync::Arc;

use indexmap::{IndexMap, IndexSet};

use super::ast::*;
use super::builtins::BUILTIN_NAMES;
use super::error::{AbortKind, Exc, PyError, R};
use super::format::py_format;
use super::host::{Call, HostFunction, HostModule};
use super::modules::{self, Clock};
use super::text::{boundaries, grapheme_count, longer_than, str_compare};
use super::value::*;

/// Deeper than this and a native stack is at risk; PyMini has no limit.
pub const MAX_CALL_DEPTH: u32 = 300;

const PRINTF_TYPES: &[&str] = &["s", "d", "i", "f", "g", "e", "E", "x", "X", "o", "c"];

/// A lexical scope. Functions capture the one they were defined in.
pub(crate) struct Env {
    pub vars: RefCell<IndexMap<Rc<str>, Value>>,
    /// Names this scope declared `global`, so assigning them writes to the module.
    pub global_names: RefCell<HashSet<Rc<str>>>,
    /// Names this scope declared `nonlocal`, so assigning them writes to the enclosing function's.
    pub nonlocal_names: RefCell<HashSet<Rc<str>>>,
    pub parent: Option<Rc<Env>>,
    /// A class body: its names are not visible to the functions defined in it.
    pub class_scope: bool,
}

impl Env {
    pub fn new(parent: Option<Rc<Env>>) -> Rc<Env> {
        Rc::new(Env {
            vars: RefCell::new(IndexMap::new()),
            global_names: RefCell::new(HashSet::new()),
            nonlocal_names: RefCell::new(HashSet::new()),
            parent,
            class_scope: false,
        })
    }

    pub fn class_body(parent: Rc<Env>) -> Rc<Env> {
        Rc::new(Env {
            vars: RefCell::new(IndexMap::new()),
            global_names: RefCell::new(HashSet::new()),
            nonlocal_names: RefCell::new(HashSet::new()),
            parent: Some(parent),
            class_scope: true,
        })
    }

    pub fn root(self: &Rc<Env>) -> Rc<Env> {
        let mut e = self.clone();
        while let Some(p) = e.parent.clone() {
            e = p;
        }
        e
    }

    fn is_global(&self, name: &str) -> bool {
        let g = self.global_names.borrow();
        !g.is_empty() && g.contains(name)
    }

    pub fn get(self: &Rc<Env>, name: &str) -> Option<Value> {
        let mut e: &Rc<Env> = self;
        let mut own = true;
        loop {
            if e.is_global(name) {
                return e.root().vars.borrow().get(name).cloned();
            }
            // A class body's names are seen from the body itself, never from a method.
            if own || !e.class_scope {
                if let Some(v) = e.vars.borrow().get(name) {
                    return Some(v.clone());
                }
            }
            own = false;
            match &e.parent {
                Some(p) => e = p,
                None => return None,
            }
        }
    }

    pub fn define(self: &Rc<Env>, name: &Rc<str>, value: Value) {
        if self.is_global(name) {
            self.root().vars.borrow_mut().insert(name.clone(), value);
            return;
        }
        if self.is_nonlocal(name) {
            self.enclosing_with(name).vars.borrow_mut().insert(name.clone(), value);
            return;
        }
        self.vars.borrow_mut().insert(name.clone(), value);
    }

    fn is_nonlocal(&self, name: &str) -> bool {
        let n = self.nonlocal_names.borrow();
        !n.is_empty() && n.contains(name)
    }

    /// The nearest enclosing function scope that binds [name], or the nearest
    /// enclosing function scope when none does yet.
    fn enclosing_with(self: &Rc<Env>, name: &str) -> Rc<Env> {
        let mut fallback = None;
        let mut e = self.parent.clone();
        while let Some(scope) = e {
            if scope.parent.is_none() {
                break;
            }
            if !scope.class_scope {
                if scope.vars.borrow().contains_key(name) {
                    return scope;
                }
                if fallback.is_none() {
                    fallback = Some(scope.clone());
                }
            }
            e = scope.parent.clone();
        }
        fallback.unwrap_or_else(|| self.clone())
    }

    /// `del x`. Returns whether anything was bound.
    pub fn remove(self: &Rc<Env>, name: &str) -> bool {
        if self.is_global(name) {
            return self.root().vars.borrow_mut().shift_remove(name).is_some();
        }
        if self.vars.borrow_mut().shift_remove(name).is_some() {
            return true;
        }
        match &self.parent {
            Some(p) => p.remove(name),
            None => false,
        }
    }
}

/// How much a script may do before it's stopped.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    /// Work allowed per `load`, `run` or `call`: every statement, expression,
    /// loop turn, regex step and sort comparison costs one.
    pub steps: u64,
    /// The most items one list, set, dict, tuple or range may hold.
    pub container_items: usize,
    /// The longest string a script may build, in grapheme clusters.
    pub string_length: usize,
}

impl Limits {
    /// PyMini's own defaults (200,000 items, 1,000,000 characters), which the
    /// probe corpus was captured under.
    pub const PYMINI: Limits = Limits { steps: 2_000_000, container_items: 200_000, string_length: 1_000_000 };
}

/// Tighter than PyMini's own: sized for a keyboard action, not a desktop script.
impl Default for Limits {
    fn default() -> Self {
        Limits { steps: 2_000_000, container_items: 10_000, string_length: 100_000 }
    }
}

pub(crate) struct Interp {
    pub globals: Rc<Env>,
    pub max_steps: u64,
    pub steps: u64,
    pub current_line: u32,
    pub printed: Vec<String>,
    pub on_print: Option<Box<dyn FnMut(&str)>>,
    active_exception: Option<Rc<ExceptionValue>>,
    pub random_state: u64,
    pub enabled_modules: Vec<&'static str>,
    pub host_functions: HashMap<String, Rc<HostFunction>>,
    pub host_modules: HashMap<String, Rc<HostModule>>,
    pub max_container: usize,
    pub max_string: usize,
    pub(crate) call_depth: u32,
    cluster_cache: Option<(Rc<str>, Rc<[u32]>)>,
    pub cancel: Arc<AtomicBool>,
    #[cfg(not(target_arch = "wasm32"))]
    pub deadline: Option<std::time::Instant>,
    pub clock: Rc<dyn Clock>,
    /// Every scope a function closed over, so dropping the runtime can break
    /// the function ↔ scope reference cycles.
    pub(crate) closures: Vec<Weak<Env>>,
    /// Every class and instance made, for the same reason.
    pub(crate) classes: Vec<Weak<Class>>,
    pub(crate) instances: Vec<Weak<Instance>>,
    /// Exception classes by name, so `except` can follow their bases.
    pub(crate) exception_classes: HashMap<Rc<str>, Rc<Class>>,
    /// The instance the latest `raise` threw, with its kind and message.
    pub(crate) raised: Option<(Rc<str>, String, Value)>,
    active_instance: Option<Value>,
    /// The functions running now, innermost last, with their scopes: what `super()` reads.
    pub(crate) frames: Vec<(Rc<Function>, Rc<Env>)>,
    /// Modules written in Python that a script may import, by dotted name.
    pub(crate) source_modules: HashMap<String, Rc<str>>,
    /// Which of those are packages (`pkg/__init__.py`), for relative imports.
    pub(crate) packages: HashSet<String>,
    /// The ones loaded so far, by dotted name.
    pub(crate) module_envs: HashMap<String, Rc<Env>>,
    /// The module whose top level is running: `__main__`, or one being imported.
    pub(crate) current_module: Rc<str>,
    pub(crate) repr_depth: u32,
    pub(crate) plain_object: Option<Rc<Class>>,
}

impl Drop for Interp {
    fn drop(&mut self) {
        for w in self.closures.drain(..) {
            if let Some(env) = w.upgrade() {
                env.vars.borrow_mut().clear();
            }
        }
        for w in self.instances.drain(..) {
            if let Some(i) = w.upgrade() {
                i.attrs.borrow_mut().clear();
            }
        }
        for w in self.classes.drain(..) {
            if let Some(c) = w.upgrade() {
                c.attrs.borrow_mut().clear();
            }
        }
        for env in self.module_envs.values() {
            env.vars.borrow_mut().clear();
        }
        self.module_envs.clear();
        self.exception_classes.clear();
        self.frames.clear();
        self.raised = None;
        self.active_instance = None;
        self.globals.vars.borrow_mut().clear();
        self.host_functions.clear();
        self.host_modules.clear();
    }
}

pub(crate) fn err(message: impl Into<String>, kind: &str, line: u32) -> Exc {
    PyError::at(message, kind, Some(line)).into()
}

impl Interp {
    pub fn new(limits: Limits, modules: &[&str], clock: Rc<dyn Clock>) -> Interp {
        let enabled = modules::ALL_NAMES.iter().copied().filter(|m| modules.contains(m)).collect();
        Interp {
            globals: Env::new(None),
            max_steps: limits.steps,
            steps: 0,
            current_line: 0,
            printed: Vec::new(),
            on_print: None,
            active_exception: None,
            random_state: modules::random_seed(),
            enabled_modules: enabled,
            host_functions: HashMap::new(),
            host_modules: HashMap::new(),
            max_container: limits.container_items,
            max_string: limits.string_length,
            call_depth: 0,
            cluster_cache: None,
            cancel: Arc::new(AtomicBool::new(false)),
            #[cfg(not(target_arch = "wasm32"))]
            deadline: None,
            clock,
            closures: Vec::new(),
            classes: Vec::new(),
            instances: Vec::new(),
            exception_classes: HashMap::new(),
            raised: None,
            active_instance: None,
            frames: Vec::new(),
            source_modules: HashMap::new(),
            packages: HashSet::new(),
            module_envs: HashMap::new(),
            current_module: Rc::from("__main__"),
            repr_depth: 0,
            plain_object: None,
        }
    }

    /// The grapheme cluster boundaries of [s], which is what PyMini's `Array(s)` gives.
    pub fn chars(&mut self, s: &Rc<str>) -> Rc<[u32]> {
        if let Some((k, v)) = &self.cluster_cache {
            if Rc::ptr_eq(k, s) {
                return v.clone();
            }
        }
        let b: Rc<[u32]> = boundaries(s).into();
        // Short strings are cheap to split, and caching them would evict the
        // long text a loop keeps coming back to.
        if s.len() >= 64 {
            self.cluster_cache = Some((s.clone(), b.clone()));
        }
        b
    }

    // MARK: - Entry points

    pub fn run(&mut self, program: &[Stmt]) -> R<()> {
        let g = self.globals.clone();
        self.exec_block(program, &g)
    }

    pub fn call(&mut self, name: &str, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let v = match self.globals.get(name) {
            Some(v) => v,
            None => match self.host_functions.get(name) {
                Some(f) => Value::HostFunction(f.clone()),
                None => return Err(PyError::new(format!("name '{name}' is not defined"), "NameError").into()),
            },
        };
        self.call_value(&v, args, kwargs)
    }

    /// Call something already resolved: `sorted(key=...)`, `map`, `re.sub` with a replacer.
    pub fn invoke(&mut self, callee: &Value, args: Vec<Value>) -> R<Value> {
        self.call_value(callee, args, Vec::new())
    }

    pub fn reset_budget(&mut self) {
        self.steps = 0;
        self.call_depth = 0;
    }

    pub fn emit(&mut self, line: String) {
        if let Some(f) = self.on_print.as_mut() {
            f(&line);
        }
        self.printed.push(line);
    }

    // MARK: - Step budget

    pub fn tick(&mut self) -> Result<(), PyError> {
        self.steps += 1;
        if self.steps > self.max_steps {
            return Err(self.abort(AbortKind::Steps, "script exceeded its step budget (possible infinite loop)"));
        }
        if self.cancel.load(AtomicOrdering::Relaxed) {
            return Err(self.abort(AbortKind::Cancelled, "script was cancelled"));
        }
        #[cfg(not(target_arch = "wasm32"))]
        if self.steps & 31 == 0 {
            if let Some(d) = self.deadline {
                if std::time::Instant::now() > d {
                    return Err(self.abort(AbortKind::Deadline, "script ran out of time"));
                }
            }
        }
        Ok(())
    }

    pub fn abort(&self, kind: AbortKind, message: &str) -> PyError {
        PyError::abort(kind, message, Some(self.current_line))
    }

    pub fn error(&self, message: impl Into<String>, kind: &str) -> Exc {
        err(message, kind, self.current_line)
    }

    pub fn container_abort(&self, what: &str) -> Exc {
        self.abort(AbortKind::ContainerCap, &format!("{what} too large")).into()
    }

    pub fn string_abort(&self) -> Exc {
        self.abort(AbortKind::StringCap, "string too large").into()
    }

    // MARK: - Statements

    pub fn exec_block(&mut self, stmts: &[Stmt], env: &Rc<Env>) -> R<()> {
        for s in stmts {
            self.exec(s, env)?;
        }
        Ok(())
    }

    // Each statement and expression kind lives in its own non-inlined method,
    // so the frame `exec` and `eval` put on the native stack for every level of
    // Python nesting is a small dispatcher rather than the sum of every arm.

    fn exec(&mut self, stmt: &Stmt, env: &Rc<Env>) -> R<()> {
        self.tick()?;
        self.current_line = stmt.line;
        match &stmt.kind {
            StmtKind::Expr(e) => self.eval(e, env).map(|_| ()),
            StmtKind::Assign(targets, value) => self.exec_assign(targets, value, env),
            StmtKind::AugAssign(target, op, value) => self.exec_aug_assign(target, *op, value, env),
            StmtKind::If(branches, else_body) => self.exec_if(branches, else_body.as_deref(), env),
            StmtKind::While(cond, body, else_body) => self.exec_while(cond, body, else_body.as_deref(), env),
            StmtKind::For(target, iterable, body, else_body) => {
                self.exec_for(target, iterable, body, else_body.as_deref(), env)
            }
            StmtKind::FuncDef(def) => self.exec_def(def, env),
            StmtKind::ClassDef(def) => self.exec_class(def, env),
            StmtKind::With(items, body) => self.exec_with(items, body, env),
            StmtKind::AnnAssign(target, ann, value) => self.exec_ann_assign(target, ann.as_ref(), value.as_ref(), env),
            StmtKind::Assert(test, message) => self.exec_assert(test, message.as_ref(), env),
            StmtKind::Nonlocal(names) => {
                let mut n = env.nonlocal_names.borrow_mut();
                for name in names {
                    n.insert(name.clone());
                }
                Ok(())
            }
            StmtKind::Return(value) => Err(self.exec_return(value.as_ref(), env)),
            StmtKind::Break => Err(Exc::Break),
            StmtKind::Continue => Err(Exc::Continue),
            StmtKind::Pass => Ok(()),
            StmtKind::Try(t) => self.exec_try(t, env),
            StmtKind::Raise(value) => Err(self.exec_raise(value.as_ref(), env)),
            StmtKind::Import { module, alias, names } => self.exec_import(module, alias.as_ref(), names.as_deref(), env),
            StmtKind::Global(names) => {
                let mut g = env.global_names.borrow_mut();
                for n in names {
                    g.insert(n.clone());
                }
                Ok(())
            }
            StmtKind::Del(targets) => {
                for t in targets {
                    self.exec_delete(t, env)?;
                }
                Ok(())
            }
        }
    }

    #[inline(never)]
    fn exec_assign(&mut self, targets: &[Expr], value: &Expr, env: &Rc<Env>) -> R<()> {
        let v = self.eval(value, env)?;
        for t in targets {
            self.assign(t, v.clone(), env)?;
        }
        Ok(())
    }

    #[inline(never)]
    fn exec_aug_assign(&mut self, target: &Expr, op: BinOp, value: &Expr, env: &Rc<Env>) -> R<()> {
        let current = self.eval(target, env)?;
        let rhs = self.eval(value, env)?;
        // `list += iterable` extends in place, as Python does.
        if op == BinOp::Add {
            if let (Value::List(l), Some(items)) = (&current, sequence_items(&rhs)) {
                if l.borrow().len() + items.len() > self.max_container {
                    return Err(self.container_abort("list"));
                }
                l.borrow_mut().extend(items);
                return Ok(());
            }
        }
        let v = self.binary_op(op, &current, &rhs)?;
        self.assign(target, v, env)
    }

    #[inline(never)]
    fn exec_if(&mut self, branches: &[(Expr, Vec<Stmt>)], else_body: Option<&[Stmt]>, env: &Rc<Env>) -> R<()> {
        for (cond, body) in branches {
            let c = self.eval(cond, env)?;
            if self.truth(&c)? {
                return self.exec_block(body, env);
            }
        }
        match else_body {
            Some(b) => self.exec_block(b, env),
            None => Ok(()),
        }
    }

    #[inline(never)]
    fn exec_while(&mut self, cond: &Expr, body: &[Stmt], else_body: Option<&[Stmt]>, env: &Rc<Env>) -> R<()> {
        loop {
            let c = self.eval(cond, env)?;
            if !self.truth(&c)? {
                break;
            }
            self.tick()?;
            match self.exec_block(body, env) {
                Ok(()) | Err(Exc::Continue) => {}
                Err(Exc::Break) => return Ok(()),
                Err(e) => return Err(e),
            }
        }
        match else_body {
            Some(b) => self.exec_block(b, env),
            None => Ok(()),
        }
    }

    #[inline(never)]
    fn exec_for(&mut self, target: &Expr, iterable: &Expr, body: &[Stmt], else_body: Option<&[Stmt]>, env: &Rc<Env>) -> R<()> {
        let it = self.eval(iterable, env)?;
        let seq = self.iterate(&it)?;
        for item in seq {
            self.tick()?;
            self.assign(target, item, env)?;
            match self.exec_block(body, env) {
                Ok(()) | Err(Exc::Continue) => {}
                Err(Exc::Break) => return Ok(()),
                Err(e) => return Err(e),
            }
        }
        match else_body {
            Some(b) => self.exec_block(b, env),
            None => Ok(()),
        }
    }

    #[inline(never)]
    fn exec_def(&mut self, def: &Rc<FuncDef>, env: &Rc<Env>) -> R<()> {
        let mut decorators = Vec::with_capacity(def.decorators.len());
        for d in &def.decorators {
            decorators.push(self.eval(d, env)?);
        }
        self.track_closure(env);
        let f = Function::new(def.name.clone(), FnBody::Def(def.clone()), env.clone());
        let mut value = Value::Function(Rc::new(f));
        for d in decorators.iter().rev() {
            value = self.call_value(d, vec![value], Vec::new())?;
        }
        env.define(&def.name, value);
        Ok(())
    }

    #[inline(never)]
    fn exec_return(&mut self, value: Option<&Expr>, env: &Rc<Env>) -> Exc {
        match value {
            Some(e) => match self.eval(e, env) {
                Ok(v) => Exc::Return(v),
                Err(x) => x,
            },
            None => Exc::Return(Value::None),
        }
    }

    fn track_closure(&mut self, env: &Rc<Env>) {
        if Rc::ptr_eq(env, &self.globals) {
            return;
        }
        if self.closures.len() >= 64 && self.closures.len().is_power_of_two() {
            self.closures.retain(|w| w.strong_count() > 0);
        }
        self.closures.push(Rc::downgrade(env));
    }

    #[inline(never)]
    fn exec_try(&mut self, stmt: &TryStmt, env: &Rc<Env>) -> R<()> {
        let mut pending: Option<Exc> = None;
        let body = match self.exec_block(&stmt.body, env) {
            Ok(()) => match &stmt.else_body {
                Some(e) => self.exec_block(e, env),
                None => Ok(()),
            },
            e => e,
        };
        match body {
            Ok(()) => {}
            Err(Exc::Error(error)) => {
                if error.is_abort() {
                    pending = Some(Exc::Error(error));
                } else {
                    let handler = stmt
                        .handlers
                        .iter()
                        .find(|h| h.types.is_empty() || h.types.iter().any(|t| self.exception_is(t, &error.kind)));
                    match handler {
                        Some(h) => {
                            let value = Rc::new(ExceptionValue { kind: error.kind.clone(), message: error.message.clone() });
                            let caught = self.caught_value(&error);
                            self.raised = None;
                            if let Some(b) = &h.binding {
                                env.define(b, caught.clone());
                            }
                            let saved = self.active_exception.replace(value);
                            let saved_instance = self.active_instance.replace(caught);
                            if let Err(e) = self.exec_block(&h.body, env) {
                                pending = Some(e);
                            }
                            self.active_exception = saved;
                            self.active_instance = saved_instance;
                        }
                        None => pending = Some(Exc::Error(error)),
                    }
                }
            }
            // return, break and continue pass straight through, but `finally` runs first.
            Err(flow) => pending = Some(flow),
        }
        if let Some(f) = &stmt.finally_body {
            self.exec_block(f, env)?;
        }
        match pending {
            Some(p) => Err(p),
            None => Ok(()),
        }
    }

    fn exec_raise(&mut self, e: Option<&Expr>, env: &Rc<Env>) -> Exc {
        let Some(e) = e else {
            if let Some(Value::Instance(inst)) = self.active_instance.clone() {
                return self.raise_instance(&inst);
            }
            return match &self.active_exception {
                Some(active) => err(active.message.clone(), &active.kind, self.current_line),
                None => self.error("no active exception to re-raise", "RuntimeError"),
            };
        };
        match self.eval(e, env) {
            Err(x) => x,
            Ok(Value::Exception(ex)) => err(ex.message.clone(), &ex.kind, self.current_line),
            Ok(Value::Str(s)) => err(s.to_string(), "Exception", self.current_line),
            Ok(Value::Instance(inst)) => self.raise_instance(&inst),
            Ok(Value::Class(c)) if c.exception_root.is_some() => match self.construct(&c, Vec::new(), Vec::new()) {
                Ok(Value::Instance(inst)) => self.raise_instance(&inst),
                Ok(_) => self.error("exceptions must derive from Exception", "TypeError"),
                Err(x) => x,
            },
            Ok(Value::Builtin(kind)) if EXCEPTION_KINDS.contains(&kind.as_ref()) => err("", &kind, self.current_line),
            Ok(v) => self.error(format!("exceptions must be an exception type, not '{}'", v.type_name()), "TypeError"),
        }
    }

    /// Every module name a script could import right now.
    pub fn importable_modules(&self) -> Vec<String> {
        let mut out: Vec<String> = self.enabled_modules.iter().filter(|s| !s.starts_with('_')).map(|s| s.to_string()).collect();
        out.extend(self.host_modules.keys().filter(|k| !k.contains('.') && !k.starts_with('_')).cloned());
        out.extend(self.source_modules.keys().filter(|k| !k.contains('.') && !k.starts_with('_')).cloned());
        out.sort();
        out.dedup();
        out
    }

    fn exec_import(
        &mut self,
        module: &Rc<str>,
        alias: Option<&Rc<str>>,
        names: Option<&[(Rc<str>, Option<Rc<str>>)]>,
        env: &Rc<Env>,
    ) -> R<()> {
        let module = self.resolve_module(module)?;
        self.ensure_module(&module)?;
        let Some(names) = names else {
            match alias {
                Some(a) => env.define(a, Value::Module(module.clone())),
                None => {
                    // `import a.b` binds `a`, through which `a.b` is reached.
                    let top: Rc<str> = Rc::from(module.split('.').next().unwrap_or(&module));
                    env.define(&top, Value::Module(top.clone()));
                }
            }
            return Ok(());
        };
        for (name, rename) in names {
            let value = match self.member(&module, name) {
                Some(v) => v,
                None => {
                    let sub = format!("{module}.{name}");
                    if !self.module_exists(&sub) {
                        return Err(self.error(format!("cannot import name '{name}' from '{module}'"), "ImportError"));
                    }
                    self.ensure_module(&sub)?;
                    Value::Module(Rc::from(sub))
                }
            };
            env.define(rename.as_ref().unwrap_or(name), value);
        }
        Ok(())
    }

    /// `module.name`, looking at the host's modules, then modules written in
    /// Python, then the standard ones.
    pub fn member(&self, module: &str, name: &str) -> Option<Value> {
        if let Some(m) = self.host_modules.get(module) {
            if let Some(v) = m.member(name) {
                return Some(v);
            }
            let sub = format!("{module}.{name}");
            if self.host_modules.contains_key(&sub) || self.module_envs.contains_key(&sub) {
                return Some(Value::Module(Rc::from(sub)));
            }
            return None;
        }
        if self.module_envs.contains_key(module) {
            return self.source_member(module, name);
        }
        modules::member(module, name)
    }

    fn exec_delete(&mut self, target: &Expr, env: &Rc<Env>) -> R<()> {
        match target {
            Expr::Name(n) => {
                if !env.remove(n) {
                    return Err(self.error(format!("name '{n}' is not defined"), "NameError"));
                }
            }
            Expr::Attribute(obj, name) => {
                let o = self.eval(obj, env)?;
                self.del_attr(&o, name)?;
            }
            Expr::Index(base, idx) => {
                let base = self.eval(base, env)?;
                let idx = self.eval(idx, env)?;
                match &base {
                    Value::Instance(_) => {
                        if self.dunder(&base, "__delitem__", vec![idx.clone()])?.is_none() {
                            return Err(self.error(format!("'{}' object does not support item deletion", base.type_name()), "TypeError"));
                        }
                    }
                    Value::List(l) => {
                        let n = l.borrow().len();
                        let i = self.list_index(&idx, n)?;
                        l.borrow_mut().remove(i);
                    }
                    Value::Dict(d) => {
                        let key = self.hashable(&idx)?;
                        if d.borrow_mut().shift_remove(&key).is_none() {
                            return Err(self.error(py_repr(&idx), "KeyError"));
                        }
                    }
                    _ => return Err(self.error(format!("cannot delete from '{}'", base.type_name()), "TypeError")),
                }
            }
            _ => return Err(self.error("cannot delete this expression", "SyntaxError")),
        }
        Ok(())
    }

    pub fn hashable(&self, v: &Value) -> R<HKey> {
        HKey::from_value(v).ok_or_else(|| self.error(format!("unhashable type: '{}'", v.type_name()), "TypeError"))
    }

    // MARK: - Assignment

    pub(crate) fn assign(&mut self, target: &Expr, value: Value, env: &Rc<Env>) -> R<()> {
        match target {
            Expr::Name(n) => env.define(n, value),
            Expr::Attribute(obj, name) => {
                let o = self.eval(obj, env)?;
                self.set_attr(&o, name, value)?;
            }
            Expr::Tuple(items) => {
                let parts = self.iterate(&value)?;
                if parts.len() != items.len() {
                    return Err(self.error(
                        format!("cannot unpack {} values into {} targets", parts.len(), items.len()),
                        "ValueError",
                    ));
                }
                for (t, p) in items.iter().zip(parts) {
                    self.assign(t, p, env)?;
                }
            }
            Expr::Index(base, idx) => {
                let base = self.eval(base, env)?;
                let idx = self.eval(idx, env)?;
                match &base {
                    Value::List(l) => {
                        let n = l.borrow().len();
                        let i = self.list_index(&idx, n)?;
                        l.borrow_mut()[i] = value;
                    }
                    Value::Dict(d) => {
                        let key = self.hashable(&idx)?;
                        d.borrow_mut().insert(key, value);
                    }
                    Value::Instance(_) if self.dunder(&base, "__setitem__", vec![idx.clone(), value.clone()])?.is_some() => {}
                    _ => {
                        return Err(self.error(
                            format!("'{}' object does not support item assignment", base.type_name()),
                            "TypeError",
                        ))
                    }
                }
            }
            _ => return Err(self.error("cannot assign to this expression", "SyntaxError")),
        }
        Ok(())
    }

    // MARK: - Expressions

    pub fn eval(&mut self, expr: &Expr, env: &Rc<Env>) -> R<Value> {
        self.tick()?;
        match expr {
            Expr::None => Ok(Value::None),
            Expr::Bool(b) => Ok(Value::Bool(*b)),
            Expr::Int(i) => Ok(Value::Int(*i)),
            Expr::Float(f) => Ok(Value::Float(*f)),
            Expr::Str(s) => Ok(Value::Str(s.clone())),
            Expr::Bytes(b) => Ok(Value::Bytes(b.clone())),
            Expr::FString(parts) => self.eval_fstring(parts, env),
            Expr::Name(n) => self.eval_name(n, env),
            Expr::List(items) => self.eval_items(items, env).map(Value::list),
            Expr::Tuple(items) => self.eval_items(items, env).map(Value::tuple),
            Expr::Dict(pairs) => self.eval_dict(pairs, env),
            Expr::Set(items) => self.eval_set(items, env),
            Expr::Unary(op, operand) => self.eval_unary(*op, operand, env),
            Expr::Binary(op, l, r) => self.eval_binary(*op, l, r, env),
            Expr::BoolOp(kind, l, r) => self.eval_bool_op(*kind, l, r, env),
            Expr::Compare(left, pairs) => self.eval_compare(left, pairs, env),
            Expr::Ternary(t) => {
                let c = self.eval(&t.0, env)?;
                if self.truth(&c)? {
                    self.eval(&t.1, env)
                } else {
                    self.eval(&t.2, env)
                }
            }
            Expr::Index(base, idx) => self.eval_index(base, idx, env),
            Expr::Slice(s) => self.eval_slice(s, env),
            Expr::Attribute(obj, name) => self.eval_attribute(obj, name, env),
            Expr::Lambda(def) => Ok(self.eval_lambda(def, env)),
            Expr::Comprehension(c) => self.run_comprehension(c, env),
            Expr::Starred(_) => Err(self.error("'*' can only be used in a call's arguments", "SyntaxError")),
            Expr::Call(callee, args, kwargs) => self.eval_call(callee, args, kwargs, env),
        }
    }

    #[inline(never)]
    fn eval_fstring(&mut self, parts: &[FPart], env: &Rc<Env>) -> R<Value> {
        Ok(Value::str(self.interpolate(parts, env)?))
    }

    #[inline(never)]
    fn eval_name(&mut self, n: &Rc<str>, env: &Rc<Env>) -> R<Value> {
        if let Some(v) = env.get(n) {
            return Ok(v);
        }
        if let Some(f) = self.host_functions.get(n.as_ref()) {
            return Ok(Value::HostFunction(f.clone()));
        }
        if BUILTIN_NAMES.contains(&n.as_ref()) {
            return Ok(Value::Builtin(n.clone()));
        }
        Err(self.error(format!("name '{n}' is not defined"), "NameError"))
    }

    #[inline(never)]
    fn eval_items(&mut self, items: &[Expr], env: &Rc<Env>) -> R<Vec<Value>> {
        let mut out = Vec::with_capacity(items.len());
        for i in items {
            if let Expr::Starred(inner) = i {
                let v = self.eval(inner, env)?;
                out.extend(self.iterate(&v)?);
                if out.len() > self.max_container {
                    return Err(self.container_abort("list"));
                }
                continue;
            }
            out.push(self.eval(i, env)?);
        }
        Ok(out)
    }

    #[inline(never)]
    fn eval_dict(&mut self, pairs: &[(Expr, Expr)], env: &Rc<Env>) -> R<Value> {
        let mut d = IndexMap::new();
        for (k, v) in pairs {
            if let Expr::Starred(inner) = k {
                let m = self.eval(inner, env)?;
                for (hk, item) in self.mapping_pairs(&m)? {
                    d.insert(hk, item);
                }
                if d.len() > self.max_container {
                    return Err(self.container_abort("dict"));
                }
                continue;
            }
            let key = self.eval(k, env)?;
            let hk = self.hashable(&key)?;
            let v = self.eval(v, env)?;
            d.insert(hk, v);
        }
        Ok(Value::new_dict(d))
    }

    #[inline(never)]
    fn eval_set(&mut self, items: &[Expr], env: &Rc<Env>) -> R<Value> {
        let mut s = IndexSet::new();
        for v in self.eval_items(items, env)? {
            s.insert(self.hashable(&v)?);
        }
        Ok(Value::new_set(s))
    }

    #[inline(never)]
    fn eval_unary(&mut self, op: UnOp, operand: &Expr, env: &Rc<Env>) -> R<Value> {
        let v = self.eval(operand, env)?;
        self.unary_op(op, &v)
    }

    #[inline(never)]
    fn eval_binary(&mut self, op: BinOp, l: &Expr, r: &Expr, env: &Rc<Env>) -> R<Value> {
        let a = self.eval(l, env)?;
        let b = self.eval(r, env)?;
        self.binary_op(op, &a, &b)
    }

    #[inline(never)]
    fn eval_bool_op(&mut self, kind: BoolKind, l: &Expr, r: &Expr, env: &Rc<Env>) -> R<Value> {
        let lv = self.eval(l, env)?;
        let t = self.truth(&lv)?;
        match kind {
            BoolKind::And if t => self.eval(r, env),
            BoolKind::Or if !t => self.eval(r, env),
            _ => Ok(lv),
        }
    }

    #[inline(never)]
    fn eval_compare(&mut self, left: &Expr, pairs: &[(CmpOp, Expr)], env: &Rc<Env>) -> R<Value> {
        let mut left = self.eval(left, env)?;
        for (op, r) in pairs {
            let right = self.eval(r, env)?;
            if !self.compare_op(*op, &left, &right)? {
                return Ok(Value::Bool(false));
            }
            left = right;
        }
        Ok(Value::Bool(true))
    }

    #[inline(never)]
    fn eval_index(&mut self, base: &Expr, idx: &Expr, env: &Rc<Env>) -> R<Value> {
        let b = self.eval(base, env)?;
        let i = self.eval(idx, env)?;
        self.index(&b, &i)
    }

    #[inline(never)]
    fn eval_slice(&mut self, s: &(Expr, Option<Expr>, Option<Expr>, Option<Expr>), env: &Rc<Env>) -> R<Value> {
        let base = self.eval(&s.0, env)?;
        let mut parts = [None, None, None];
        for (slot, e) in parts.iter_mut().zip([&s.1, &s.2, &s.3]) {
            if let Some(e) = e {
                *slot = Some(self.eval(e, env)?);
            }
        }
        let [lo, hi, st] = parts;
        self.slice(&base, lo.as_ref(), hi.as_ref(), st.as_ref())
    }

    #[inline(never)]
    fn eval_attribute(&mut self, obj: &Expr, name: &str, env: &Rc<Env>) -> R<Value> {
        let o = self.eval(obj, env)?;
        self.attribute(&o, name)
    }

    #[inline(never)]
    fn eval_lambda(&mut self, def: &Rc<LambdaDef>, env: &Rc<Env>) -> Value {
        self.track_closure(env);
        Value::Function(Rc::new(Function::new(Rc::from("<lambda>"), FnBody::Lambda(def.clone(), self.current_line), env.clone())))
    }

    #[inline(never)]
    fn eval_call(&mut self, callee: &Expr, args: &[Expr], kwargs: &[(Rc<str>, Expr)], env: &Rc<Env>) -> R<Value> {
        let mut a = Vec::with_capacity(args.len());
        for arg in args {
            if let Expr::Starred(inner) = arg {
                let v = self.eval(inner, env)?;
                a.extend(self.iterate(&v)?);
            } else {
                a.push(self.eval(arg, env)?);
            }
        }
        let mut kw: Vec<(Rc<str>, Value)> = Vec::with_capacity(kwargs.len());
        for (k, v) in kwargs {
            let value = self.eval(v, env)?;
            if k.as_ref() == "**" {
                for (hk, item) in self.mapping_pairs(&value)? {
                    let HKey::Str(name) = hk else {
                        return Err(self.error("keywords must be strings", "TypeError"));
                    };
                    if kw.iter().any(|(n, _)| *n == name) {
                        return Err(self.error(format!("got multiple values for keyword argument '{name}'"), "TypeError"));
                    }
                    kw.push((name, item));
                }
                continue;
            }
            kw.push((k.clone(), value));
        }
        // `obj.method(...)` dispatches directly, with no bound-method value in between.
        if let Expr::Attribute(obj, name) = callee {
            let o = self.eval(obj, env)?;
            self.call_method(&o, name, a, kw)
        } else {
            let c = self.eval(callee, env)?;
            self.call_value(&c, a, kw)
        }
    }

    /// Build an f-string: convert (`!r`), then format with a spec that can interpolate too.
    #[inline(never)]
    fn interpolate(&mut self, parts: &[FPart], env: &Rc<Env>) -> R<String> {
        let mut out = String::new();
        for p in parts {
            match p {
                FPart::Literal(t) => out.push_str(t),
                FPart::Expression { expr, conversion, spec } => {
                    let value = self.eval(expr, env)?;
                    let converted = match (conversion, &value) {
                        (Some(FConversion::Repr), _) => Value::str(self.to_repr(&value)?),
                        (_, Value::Instance(_)) if spec.is_some() => match self.dunder(&value, "__format__", vec![Value::str("")])? {
                            Some(_) => value,
                            None => Value::str(self.to_str(&value)?),
                        },
                        _ => value,
                    };
                    match spec {
                        Some(s) => {
                            let spec = self.interpolate(s, env)?;
                            if let Value::Instance(_) = converted {
                                match self.dunder(&converted, "__format__", vec![Value::str(&spec)])? {
                                    Some(Value::Str(f)) => out.push_str(&f),
                                    _ => return Err(self.error("__format__ must return a str", "TypeError")),
                                }
                            } else {
                                out.push_str(&py_format(&converted, &spec)?);
                            }
                        }
                        None => out.push_str(&self.to_str(&converted)?),
                    }
                }
            }
            if longer_than(&out, self.max_string) {
                return Err(self.string_abort());
            }
        }
        Ok(out)
    }

    /// Comprehensions get their own scope, so the loop variable doesn't leak (the Python 3 rule).
    #[inline(never)]
    fn run_comprehension(&mut self, c: &Comprehension, env: &Rc<Env>) -> R<Value> {
        let scope = Env::new(Some(env.clone()));
        let mut acc = CompAcc { list: Vec::new(), dict: IndexMap::new(), set: IndexSet::new() };
        self.comp_step(c, 0, &scope, &mut acc)?;
        Ok(match c.kind {
            CompKind::List => Value::list(acc.list),
            CompKind::Set => Value::new_set(acc.set),
            CompKind::Dict => Value::new_dict(acc.dict),
        })
    }

    #[inline(never)]
    fn comp_step(&mut self, c: &Comprehension, i: usize, scope: &Rc<Env>, acc: &mut CompAcc) -> R<()> {
        self.tick()?;
        if i >= c.clauses.len() {
            match c.kind {
                CompKind::List => {
                    let v = self.eval(&c.element, scope)?;
                    acc.list.push(v);
                    if acc.list.len() > self.max_container {
                        return Err(self.container_abort("list"));
                    }
                }
                CompKind::Set => {
                    let v = self.eval(&c.element, scope)?;
                    acc.set.insert(self.hashable(&v)?);
                    if acc.set.len() > self.max_container {
                        return Err(self.container_abort("set"));
                    }
                }
                CompKind::Dict => {
                    let k = self.eval(&c.element, scope)?;
                    let h = self.hashable(&k)?;
                    let v = self.eval(c.value.as_ref().unwrap_or(&c.element), scope)?;
                    acc.dict.insert(h, v);
                    if acc.dict.len() > self.max_container {
                        return Err(self.container_abort("dict"));
                    }
                }
            }
            return Ok(());
        }
        let clause = &c.clauses[i];
        let it = self.eval(&clause.iterable, scope)?;
        for item in self.iterate(&it)? {
            self.tick()?;
            self.assign(&clause.target, item, scope)?;
            let mut keep = true;
            for cond in &clause.conditions {
                if !keep {
                    break;
                }
                let c = self.eval(cond, scope)?;
                keep = self.truth(&c)?;
            }
            if keep {
                self.comp_step(c, i + 1, scope, acc)?;
            }
        }
        Ok(())
    }

    // MARK: - Attributes

    /// Attribute access outside a call: module members, the data attributes
    /// exceptions and matches have, and (an addition) a dict's string keys.
    pub fn attribute(&mut self, obj: &Value, name: &str) -> R<Value> {
        match obj {
            Value::Instance(inst) => match self.instance_attr(inst, name)? {
                Some(v) => Ok(v),
                None => Err(self.error(format!("'{}' object has no attribute '{name}'", inst.class.name), "AttributeError")),
            },
            Value::Class(c) => {
                self.class_attr(c, name).ok_or_else(|| self.error(format!("type object '{}' has no attribute '{name}'", c.name), "AttributeError"))
            }
            Value::Object(o) => match o.as_ref() {
                Object::Super { class, receiver } => {
                    let (class, receiver) = (class.clone(), receiver.clone());
                    match self.super_attr(&class, &receiver, name)? {
                        Some(v) => Ok(v),
                        None => Err(self.error(format!("'super' object has no attribute '{name}'"), "AttributeError")),
                    }
                }
                Object::Property { get, set } => match name {
                    "setter" => Ok(Value::Object(Rc::new(Object::Bound(obj.clone(), Value::Builtin(Rc::from(super::classes::HIDDEN_PROPERTY_SETTER)))))),
                    "getter" => Ok(Value::Object(Rc::new(Object::Bound(obj.clone(), Value::Builtin(Rc::from(super::classes::HIDDEN_PROPERTY_GETTER)))))),
                    "fget" => Ok(get.clone()),
                    "fset" => Ok(set.clone()),
                    _ => Err(self.error(format!("'property' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::Bound(receiver, f) => match name {
                    "__self__" => Ok(receiver.clone()),
                    "__func__" => Ok(f.clone()),
                    "__name__" => match f {
                        Value::Function(f) => Ok(Value::Str(f.name.clone())),
                        other => Ok(Value::str(other.to_str())),
                    },
                    _ => Err(self.error(format!("'method' object has no attribute '{name}'"), "AttributeError")),
                },
                _ => Err(self.error(format!("'{}' object has no attribute '{name}'", obj.type_name()), "AttributeError")),
            },
            Value::Function(f) => match name {
                "__name__" | "__qualname__" => Ok(Value::Str(f.name.clone())),
                "__doc__" => Ok(Value::None),
                _ => Err(self.error(format!("'function' object has no attribute '{name}'"), "AttributeError")),
            },
            Value::Builtin(b) if b.as_ref() == "object" => {
                let hidden = match name {
                    "__setattr__" => super::classes::HIDDEN_OBJECT_SETATTR,
                    "__getattribute__" | "__getattr__" => super::classes::HIDDEN_OBJECT_GETATTR,
                    "__init__" => super::classes::HIDDEN_BASE_INIT,
                    "__new__" => super::classes::HIDDEN_OBJECT_NEW,
                    "__str__" => super::classes::HIDDEN_DEFAULT_STR,
                    "__repr__" => super::classes::HIDDEN_DEFAULT_REPR,
                    _ => return Err(self.error(format!("type object 'object' has no attribute '{name}'"), "AttributeError")),
                };
                Ok(Value::Builtin(Rc::from(hidden)))
            }
            Value::Builtin(b) if name == "__name__" => Ok(Value::Str(b.clone())),
            Value::Module(m) => self
                .member(m, name)
                .ok_or_else(|| self.error(format!("module '{m}' has no attribute '{name}'"), "AttributeError")),
            Value::Exception(e) => match name {
                "args" => Ok(Value::tuple(vec![Value::str(&e.message)])),
                "message" => Ok(Value::str(&e.message)),
                _ => Err(self.error(format!("exception has no attribute '{name}'"), "AttributeError")),
            },
            Value::Match(m) => match name {
                "string" => Ok(Value::str(m.subject.text())),
                "lastindex" => Ok(Value::Int(m.spans.len().saturating_sub(1) as i64)),
                _ => Err(self.error(format!("Match has no attribute '{name}'"), "AttributeError")),
            },
            Value::Dict(d) => {
                if let Some(v) = d.borrow().get(&HKey::str(name)) {
                    return Ok(v.clone());
                }
                if super::methods::has_builtin_method(obj, name) {
                    return Ok(self.method_value(obj, name));
                }
                Err(self.error(format!("'dict' object has no attribute '{name}'"), "AttributeError"))
            }
            // PyMini's type() answers with a name, so `type(x).__name__` reads it back.
            Value::Str(s) if name == "__name__" => Ok(Value::Str(s.clone())),
            _ if super::methods::has_builtin_method(obj, name) => Ok(self.method_value(obj, name)),
            _ => Err(self.error(format!("'{}' object has no attribute '{name}'", obj.type_name()), "AttributeError")),
        }
    }

    /// `items.append` without calling it: a bound method of a built-in type.
    fn method_value(&self, obj: &Value, name: &str) -> Value {
        let method = Value::Builtin(Rc::from(format!("{}{name}", super::methods::METHOD_PREFIX)));
        Value::Object(Rc::new(Object::Bound(obj.clone(), method)))
    }

    // MARK: - Calls

    #[inline(never)]
    pub fn call_value(&mut self, callee: &Value, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        match callee {
            Value::Function(f) => self.call_function(f, args, kwargs),
            Value::Builtin(name) => {
                if !kwargs.is_empty() && !super::builtins::KWARG_BUILTINS.contains(&name.as_ref()) {
                    return Err(self.error(format!("{name}() takes no keyword arguments"), "TypeError"));
                }
                self.call_builtin(name, args, kwargs)
            }
            Value::ModuleFunction(mf) => {
                let (m, n) = (mf.0.clone(), mf.1.clone());
                self.call_module(&m, &n, args, kwargs)
            }
            Value::HostFunction(f) => self.call_host(f, args, kwargs),
            Value::Class(c) => {
                let c = c.clone();
                self.construct(&c, args, kwargs)
            }
            Value::Object(o) => match o.as_ref() {
                Object::Bound(receiver, Value::Builtin(m)) if m.starts_with(super::methods::METHOD_PREFIX) => {
                    let name = m[super::methods::METHOD_PREFIX.len()..].to_string();
                    let receiver = receiver.clone();
                    self.call_method(&receiver, &name, args, kwargs)
                }
                Object::Bound(receiver, f) => {
                    let mut all = Vec::with_capacity(args.len() + 1);
                    all.push(receiver.clone());
                    all.extend(args);
                    let f = f.clone();
                    self.call_value(&f, all, kwargs)
                }
                Object::StaticMethod(f) | Object::ClassMethod(f) => {
                    let f = f.clone();
                    self.call_value(&f, args, kwargs)
                }
                _ => Err(self.error(format!("'{}' object is not callable", callee.type_name()), "TypeError")),
            },
            Value::Instance(inst) => match inst.class.lookup("__call__") {
                Some(m) => {
                    let bound = self.bind(m, callee.clone(), &inst.class);
                    self.call_value(&bound, args, kwargs)
                }
                None => Err(self.error(format!("'{}' object is not callable", inst.class.name), "TypeError")),
            },
            _ => Err(self.error(format!("'{}' object is not callable", callee.type_name()), "TypeError")),
        }
    }

    /// Hand a call to the host. A PyError keeps its kind; time the host spends
    /// does not count against the deadline.
    #[inline(never)]
    pub fn call_host(&mut self, f: &Rc<HostFunction>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        self.tick()?;
        let line = self.current_line;
        #[cfg(not(target_arch = "wasm32"))]
        let started = self.deadline.map(|_| std::time::Instant::now());
        let f = f.clone();
        let result = {
            let mut call = Call { name: &f.name, args, kwargs, line, interp: self };
            (f.body)(&mut call)
        };
        #[cfg(not(target_arch = "wasm32"))]
        if let (Some(s), Some(d)) = (started, self.deadline) {
            self.deadline = Some(d + s.elapsed());
        }
        self.current_line = line;
        match result {
            Ok(v) => Ok(v),
            Err(e) => {
                let e = if e.line.is_none() { e.with_line(line) } else { e };
                Err(e.into())
            }
        }
    }

    #[inline(never)]
    pub fn call_function(&mut self, f: &Rc<Function>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let env = Env::new(Some(f.closure.clone()));
        let sig = f.signature();
        let params = &sig.positional;
        if sig.variadic.is_none() && args.len() > params.len() {
            return Err(self.error(
                format!("{}() takes {} arguments but {} were given", f.name, params.len(), args.len()),
                "TypeError",
            ));
        }
        let mut bound: HashSet<Rc<str>> = HashSet::new();
        let nargs = args.len();
        let mut args = args.into_iter();
        for p in params.iter().take(nargs) {
            env.define(&p.name, args.next().unwrap());
            bound.insert(p.name.clone());
        }
        if let Some(v) = &sig.variadic {
            env.define(v, Value::tuple(args.collect()));
        }
        let mut extra: IndexMap<HKey, Value> = IndexMap::new();
        for (k, v) in kwargs {
            if params.iter().any(|p| p.name == k) || sig.keyword_only.iter().any(|p| p.name == k) {
                if bound.contains(&k) {
                    return Err(self.error(format!("{}() got multiple values for argument '{k}'", f.name), "TypeError"));
                }
                env.define(&k, v);
                bound.insert(k);
            } else if sig.kwargs.is_some() {
                extra.insert(HKey::Str(k), v);
            } else {
                return Err(self.error(format!("{}() got an unexpected keyword argument '{k}'", f.name), "TypeError"));
            }
        }
        for p in params.iter().chain(sig.keyword_only.iter()) {
            if bound.contains(&p.name) {
                continue;
            }
            let Some(def) = &p.default else {
                return Err(self.error(format!("{}() missing required argument '{}'", f.name, p.name), "TypeError"));
            };
            let v = self.eval(def, &f.closure)?;
            env.define(&p.name, v);
        }
        if let Some(k) = &sig.kwargs {
            env.define(k, Value::new_dict(extra));
        }
        if self.call_depth >= MAX_CALL_DEPTH {
            return Err(self.abort(AbortKind::Recursion, "maximum recursion depth exceeded").into());
        }
        self.call_depth += 1;
        self.frames.push((f.clone(), env.clone()));
        let caller_line = self.current_line;
        let result = match &f.body {
            FnBody::Def(d) => self.exec_block(&d.body, &env),
            FnBody::Lambda(l, line) => match self.tick() {
                Err(e) => Err(e.into()),
                Ok(()) => {
                    self.current_line = *line;
                    match self.eval(&l.body, &env) {
                        Ok(v) => Err(Exc::Return(v)),
                        Err(e) => Err(e),
                    }
                }
            },
        };
        self.call_depth -= 1;
        self.frames.pop();
        if result.is_ok() || matches!(result, Err(Exc::Return(_))) {
            // Back in the caller: an error later in the same line is reported there.
            self.current_line = caller_line;
        }
        match result {
            Ok(()) => Ok(Value::None),
            Err(Exc::Return(v)) => Ok(v),
            Err(e) => Err(e),
        }
    }

    // MARK: - Operators

    fn unary_op(&mut self, op: UnOp, v: &Value) -> R<Value> {
        if let Value::Instance(_) = v {
            let name = match op {
                UnOp::Not => return Ok(Value::Bool(!self.truth(v)?)),
                UnOp::Neg => "__neg__",
                UnOp::Pos => "__pos__",
            };
            return match self.dunder(v, name, Vec::new())? {
                Some(r) => Ok(r),
                None => Err(self.error(format!("bad operand type for unary operator: '{}'", v.type_name()), "TypeError")),
            };
        }
        Ok(match op {
            UnOp::Not => Value::Bool(!v.truthy()),
            UnOp::Pos => {
                if v.as_float().is_some() {
                    v.clone()
                } else {
                    return Err(self.error(format!("bad operand type for unary +: '{}'", v.type_name()), "TypeError"));
                }
            }
            UnOp::Neg => match v {
                Value::Int(i) => Value::Int(i.wrapping_neg()),
                Value::Bool(b) => Value::Int(if *b { -1 } else { 0 }),
                Value::Float(f) => Value::Float(-f),
                _ => return Err(self.error(format!("bad operand type for unary -: '{}'", v.type_name()), "TypeError")),
            },
        })
    }

    pub fn binary_op(&mut self, op: BinOp, a: &Value, b: &Value) -> R<Value> {
        if matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_)) {
            return self.instance_binary(op, a, b);
        }
        // Sequences and sets first, then numbers.
        if op == BinOp::Add {
            match (a, b) {
                (Value::Str(x), Value::Str(y)) => {
                    let r = format!("{x}{y}");
                    if longer_than(&r, self.max_string) {
                        return Err(self.string_abort());
                    }
                    return Ok(Value::str(r));
                }
                (Value::List(x), Value::List(y)) => {
                    let mut out = x.borrow().clone();
                    out.extend(y.borrow().iter().cloned());
                    if out.len() > self.max_container {
                        return Err(self.container_abort("list"));
                    }
                    return Ok(Value::list(out));
                }
                (Value::Tuple(x), Value::Tuple(y)) => {
                    let mut out = x.as_ref().clone();
                    out.extend(y.iter().cloned());
                    return Ok(Value::tuple(out));
                }
                (Value::Bytes(x), Value::Bytes(y)) => {
                    if x.len() + y.len() > self.max_string {
                        return Err(self.string_abort());
                    }
                    let mut out = x.to_vec();
                    out.extend_from_slice(y);
                    return Ok(Value::bytes(&out));
                }
                _ => {}
            }
        }
        if op == BinOp::Mul {
            if let Some(v) = self.repeat_seq(a, b)? {
                return Ok(v);
            }
            if let Some(v) = self.repeat_seq(b, a)? {
                return Ok(v);
            }
        }
        if let (Value::Set(x), Value::Set(y)) = (a, b) {
            if let Some(v) = set_algebra(op, &x.borrow(), &y.borrow()) {
                return Ok(v);
            }
        }
        if op == BinOp::Sub {
            if let Value::Set(_) = a {
                return Err(self.error(
                    format!("unsupported operand type(s) for -: 'set' and '{}'", b.type_name()),
                    "TypeError",
                ));
            }
        }
        if op == BinOp::Mod {
            if let Value::Str(s) = a {
                return Ok(Value::str(self.printf_style(s, b)?));
            }
        }
        if matches!(op, BinOp::BitAnd | BinOp::BitOr | BinOp::BitXor) {
            let (Some(x), Some(y)) = (a.as_int(), b.as_int()) else {
                return Err(self.error(
                    format!(
                        "unsupported operand type(s) for {}: '{}' and '{}'",
                        op_symbol(op),
                        a.type_name(),
                        b.type_name()
                    ),
                    "TypeError",
                ));
            };
            return Ok(Value::Int(match op {
                BinOp::BitAnd => x & y,
                BinOp::BitOr => x | y,
                _ => x ^ y,
            }));
        }
        let (Some(x), Some(y)) = (a.as_float(), b.as_float()) else {
            return Err(self.error(
                format!("unsupported operand type(s) for {}: '{}' and '{}'", op_symbol(op), a.type_name(), b.type_name()),
                "TypeError",
            ));
        };
        let both_int = a.as_int().is_some() && b.as_int().is_some();
        Ok(match op {
            BinOp::Add => numeric(x + y, both_int),
            BinOp::Sub => numeric(x - y, both_int),
            BinOp::Mul => numeric(x * y, both_int),
            BinOp::Div => {
                if y == 0.0 {
                    return Err(self.error("division by zero", "ZeroDivisionError"));
                }
                Value::Float(x / y)
            }
            BinOp::FloorDiv => {
                if y == 0.0 {
                    return Err(self.error("integer division or modulo by zero", "ZeroDivisionError"));
                }
                numeric((x / y).floor(), both_int)
            }
            BinOp::Mod => {
                if y == 0.0 {
                    return Err(self.error("integer division or modulo by zero", "ZeroDivisionError"));
                }
                // Python's modulo takes the sign of the divisor.
                numeric(x - (x / y).floor() * y, both_int)
            }
            BinOp::Pow => numeric(x.powf(y), both_int && y >= 0.0),
            _ => return Err(self.error(format!("unsupported operand type(s) for {}", op_symbol(op)), "TypeError")),
        })
    }

    /// `"%s scored %d" % (name, score)`.
    fn printf_style(&mut self, format: &Rc<str>, rhs: &Value) -> R<String> {
        let args: Vec<Value> = match rhs {
            Value::Tuple(t) => t.as_ref().clone(),
            other => vec![other.clone()],
        };
        let b = self.chars(format);
        let chars: Vec<&str> = (0..b.len() - 1).map(|i| &format[b[i] as usize..b[i + 1] as usize]).collect();
        let mut out = String::new();
        let mut next = 0;
        let mut i = 0;
        while i < chars.len() {
            if chars[i] != "%" {
                out.push_str(chars[i]);
                i += 1;
                continue;
            }
            i += 1;
            if i >= chars.len() {
                return Err(self.error("incomplete format", "ValueError"));
            }
            if chars[i] == "%" {
                out.push('%');
                i += 1;
                continue;
            }
            let mut spec = String::new();
            while i < chars.len() && !PRINTF_TYPES.contains(&chars[i]) {
                spec.push_str(chars[i]);
                i += 1;
            }
            if i >= chars.len() {
                return Err(self.error("incomplete format", "ValueError"));
            }
            let ty = chars[i];
            i += 1;
            if next >= args.len() {
                return Err(self.error("not enough arguments for format string", "TypeError"));
            }
            let value = &args[next];
            next += 1;
            spec.push_str(if ty == "i" { "d" } else { ty });
            out.push_str(&py_format(value, &spec)?);
        }
        Ok(out)
    }

    /// `seq * n` for str, list and tuple when the other side is an int or bool.
    fn repeat_seq(&mut self, seq: &Value, n: &Value) -> R<Option<Value>> {
        let Some(count) = n.as_int() else { return Ok(None) };
        let times = count.max(0) as u64;
        Ok(Some(match seq {
            Value::Str(s) => {
                if s.is_empty() || times == 0 {
                    return Ok(Some(Value::str("")));
                }
                if (grapheme_count(s) as u64).saturating_mul(times) > self.max_string as u64 {
                    return Err(self.string_abort());
                }
                Value::str(s.repeat(times as usize))
            }
            Value::List(l) => {
                let items = l.borrow().clone();
                if items.is_empty() {
                    return Ok(Some(Value::list(Vec::new())));
                }
                if (items.len() as u64).saturating_mul(times) > self.max_container as u64 {
                    return Err(self.container_abort("list"));
                }
                let mut out = Vec::with_capacity(items.len() * times as usize);
                for _ in 0..times {
                    out.extend(items.iter().cloned());
                }
                Value::list(out)
            }
            Value::Tuple(t) => {
                if t.is_empty() {
                    return Ok(Some(Value::tuple(Vec::new())));
                }
                if (t.len() as u64).saturating_mul(times) > self.max_container as u64 {
                    return Err(self.container_abort("tuple"));
                }
                let mut out = Vec::with_capacity(t.len() * times as usize);
                for _ in 0..times {
                    out.extend(t.iter().cloned());
                }
                Value::tuple(out)
            }
            _ => return Ok(None),
        }))
    }

    fn compare_op(&mut self, op: CmpOp, a: &Value, b: &Value) -> R<bool> {
        let instances = matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_));
        if instances {
            match op {
                CmpOp::Lt | CmpOp::Le | CmpOp::Gt | CmpOp::Ge => return self.instance_compare(op, a, b),
                CmpOp::Ne => {
                    if let Some(r) = self.dunder(a, "__ne__", vec![b.clone()])? {
                        return self.truth(&r);
                    }
                    return Ok(!self.eq(a, b)?);
                }
                _ => {}
            }
        }
        Ok(match op {
            CmpOp::Eq => self.eq(a, b)?,
            CmpOp::Ne => !py_equals(a, b),
            CmpOp::Is => py_identical(a, b),
            CmpOp::IsNot => !py_identical(a, b),
            CmpOp::In => self.contains(b, a)?,
            CmpOp::NotIn => !self.contains(b, a)?,
            CmpOp::Lt => self.order(a, b)? < 0,
            CmpOp::Le => self.order(a, b)? <= 0,
            CmpOp::Gt => self.order(a, b)? > 0,
            CmpOp::Ge => self.order(a, b)? >= 0,
        })
    }

    /// Three-way ordering for numbers, strings and sequences (lexicographic).
    pub fn order(&mut self, a: &Value, b: &Value) -> R<i32> {
        if matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_)) {
            if self.instance_compare(CmpOp::Lt, a, b)? {
                return Ok(-1);
            }
            return Ok(if self.instance_compare(CmpOp::Lt, b, a)? { 1 } else { 0 });
        }
        if let (Some(x), Some(y)) = (a.as_float(), b.as_float()) {
            return Ok(if x < y {
                -1
            } else if x > y {
                1
            } else {
                0
            });
        }
        if let (Value::Str(x), Value::Str(y)) = (a, b) {
            return Ok(str_compare(x, y) as i32);
        }
        let seq = |v: &Value| matches!(v, Value::Str(_) | Value::List(_) | Value::Tuple(_));
        if let (Some(xs), Some(ys)) = (sequence_items(a), sequence_items(b)) {
            if seq(a) && seq(b) {
                for (x, y) in xs.iter().zip(ys.iter()) {
                    let c = self.order(x, y)?;
                    if c != 0 {
                        return Ok(c);
                    }
                }
                return Ok((xs.len() as i64 - ys.len() as i64).signum() as i32);
            }
        }
        Err(self.error(format!("'<' not supported between '{}' and '{}'", a.type_name(), b.type_name()), "TypeError"))
    }

    fn contains(&mut self, container: &Value, item: &Value) -> R<bool> {
        if let Value::Class(c) = container {
            let c = c.clone();
            for v in self.iterate_class(&c)? {
                if self.eq(&v, item)? {
                    return Ok(true);
                }
            }
            return Ok(false);
        }
        if let Value::Instance(_) = container {
            if let Some(r) = self.dunder(container, "__contains__", vec![item.clone()])? {
                return self.truth(&r);
            }
            for v in self.iterate_instance(container)? {
                if self.eq(&v, item)? {
                    return Ok(true);
                }
            }
            return Ok(false);
        }
        if let Value::Instance(_) = item {
            if let Some(items) = sequence_items(container) {
                for v in items {
                    if self.eq(&v, item)? {
                        return Ok(true);
                    }
                }
                return Ok(false);
            }
        }
        Ok(match container {
            Value::Str(s) => {
                let Value::Str(i) = item else {
                    return Err(self.error("'in <string>' requires string as left operand", "TypeError"));
                };
                i.is_empty() || s.contains(i.as_ref())
            }
            Value::List(l) => l.borrow().clone().iter().any(|v| py_equals(v, item)),
            Value::Tuple(t) => t.iter().any(|v| py_equals(v, item)),
            Value::Set(s) => HKey::from_value(item).is_some_and(|k| s.borrow().contains(&k)),
            Value::Dict(d) => HKey::from_value(item).is_some_and(|k| d.borrow().contains_key(&k)),
            Value::Bytes(b) => match item {
                Value::Bytes(needle) => needle.is_empty() || b.windows(needle.len()).any(|w| w == needle.as_ref()),
                other => match other.as_int() {
                    Some(i) if (0..=255).contains(&i) => b.contains(&(i as u8)),
                    Some(_) => return Err(self.error("byte must be in range(0, 256)", "ValueError")),
                    None => {
                        return Err(self.error(
                            format!("a bytes-like object is required, not '{}'", other.type_name()),
                            "TypeError",
                        ))
                    }
                },
            },
            _ => {
                return Err(self.error(
                    format!("argument of type '{}' is not iterable", container.type_name()),
                    "TypeError",
                ))
            }
        })
    }

    // MARK: - Indexing, slicing, iteration

    pub fn iterate(&mut self, value: &Value) -> R<Vec<Value>> {
        Ok(match value {
            Value::Str(s) => {
                let b = self.chars(s);
                (0..b.len() - 1).map(|i| Value::str(&s[b[i] as usize..b[i + 1] as usize])).collect()
            }
            Value::List(l) => l.borrow().clone(),
            Value::Tuple(t) => t.as_ref().clone(),
            Value::Set(s) => s.borrow().iter().map(HKey::to_value).collect(),
            Value::Dict(d) => d.borrow().keys().map(HKey::to_value).collect(),
            Value::Bytes(b) => b.iter().map(|x| Value::Int(*x as i64)).collect(),
            Value::Instance(_) => return self.iterate_instance(value),
            Value::Class(c) => {
                let c = c.clone();
                return self.iterate_class(&c);
            }
            Value::Object(o) => match o.as_ref() {
                Object::Iterator(items) => items.borrow_mut().drain(..).collect(),
                _ => return Err(self.error(format!("'{}' object is not iterable", value.type_name()), "TypeError")),
            },
            _ => return Err(self.error(format!("'{}' object is not iterable", value.type_name()), "TypeError")),
        })
    }

    pub fn list_index(&self, idx: &Value, count: usize) -> R<usize> {
        let raw = idx.as_int().ok_or_else(|| self.error("indices must be integers", "TypeError"))?;
        let i = if raw < 0 { raw + count as i64 } else { raw };
        if i < 0 || i >= count as i64 {
            return Err(self.error("index out of range", "IndexError"));
        }
        Ok(i as usize)
    }

    fn index(&mut self, base: &Value, idx: &Value) -> R<Value> {
        match base {
            Value::Str(s) => {
                let b = self.chars(s);
                let i = self.list_index(idx, b.len() - 1)?;
                Ok(Value::str(&s[b[i] as usize..b[i + 1] as usize]))
            }
            Value::List(l) => {
                let n = l.borrow().len();
                let i = self.list_index(idx, n)?;
                Ok(l.borrow()[i].clone())
            }
            Value::Tuple(t) => {
                let i = self.list_index(idx, t.len())?;
                Ok(t[i].clone())
            }
            Value::Bytes(b) => {
                let i = self.list_index(idx, b.len())?;
                Ok(Value::Int(b[i] as i64))
            }
            Value::Dict(d) => {
                let key = self.hashable(idx)?;
                let v = d.borrow().get(&key).cloned();
                v.ok_or_else(|| self.error(py_repr(idx), "KeyError"))
            }
            Value::Match(m) => {
                let text = idx.as_int().and_then(|i| if i < 0 { None } else { m.text(i as usize) });
                text.map(Value::str).ok_or_else(|| self.error("no such group", "IndexError"))
            }
            Value::Class(c) => {
                let c = c.clone();
                self.class_getitem(&c, idx)
            }
            Value::Instance(_) => match self.dunder(base, "__getitem__", vec![idx.clone()])? {
                Some(v) => Ok(v),
                None => Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
            },
            _ => Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
        }
    }

    fn slice(&mut self, base: &Value, lo: Option<&Value>, hi: Option<&Value>, st: Option<&Value>) -> R<Value> {
        let step = st.and_then(Value::as_int).unwrap_or(1);
        if step == 0 {
            return Err(self.error("slice step cannot be zero", "ValueError"));
        }
        let l = lo.and_then(Value::as_int);
        let h = hi.and_then(Value::as_int);
        let indices = |n: usize| slice_indices(n as i64, l, h, step);
        Ok(match base {
            Value::Str(s) => {
                let b = self.chars(s);
                let idx = indices(b.len() - 1);
                let mut out = String::new();
                for i in idx {
                    out.push_str(&s[b[i] as usize..b[i + 1] as usize]);
                }
                Value::str(out)
            }
            Value::List(list) => {
                let items = list.borrow();
                Value::list(indices(items.len()).into_iter().map(|i| items[i].clone()).collect())
            }
            Value::Tuple(t) => Value::tuple(indices(t.len()).into_iter().map(|i| t[i].clone()).collect()),
            Value::Bytes(b) => Value::bytes(&indices(b.len()).into_iter().map(|i| b[i]).collect::<Vec<u8>>()),
            _ => return Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
        })
    }

    /// Run [source] as a module of its own, with [names] defined first, and
    /// hand back its globals. Shares this call's budget.
    pub(crate) fn exec_module(&mut self, program: &[Stmt], names: Vec<(Rc<str>, Value)>) -> R<Rc<Env>> {
        let env = Env::new(None);
        for (k, v) in names {
            env.define(&k, v);
        }
        self.closures.push(Rc::downgrade(&env));
        let saved = self.current_line;
        self.exec_block(program, &env)?;
        self.current_line = saved;
        Ok(env)
    }
}

struct CompAcc {
    list: Vec<Value>,
    dict: IndexMap<HKey, Value>,
    set: IndexSet<HKey>,
}

/// Which `except` clause names catch which kinds: the parents scripts actually lean on.
pub(crate) fn exception_matches(clause: &str, kind: &str) -> bool {
    if clause == "Exception" || clause == kind {
        return true;
    }
    match clause {
        "LookupError" => kind == "KeyError" || kind == "IndexError",
        "ArithmeticError" => kind == "ZeroDivisionError" || kind == "OverflowError",
        "ValueError" | "UnicodeError" => kind == "UnicodeDecodeError",
        "OSError" => matches!(kind, "PermissionError" | "TimeoutError" | "ConnectionError" | "FileNotFoundError"),
        "RuntimeError" => kind == "NotImplementedError",
        _ => false,
    }
}

fn numeric(d: f64, as_int: bool) -> Value {
    if as_int && d == d.round_ties_even() && d.abs() < 9.007e15 {
        return Value::Int(d as i64);
    }
    Value::Float(d)
}

fn op_symbol(op: BinOp) -> &'static str {
    match op {
        BinOp::Add => "+",
        BinOp::Sub => "-",
        BinOp::Mul => "*",
        BinOp::Div => "/",
        BinOp::FloorDiv => "//",
        BinOp::Mod => "%",
        BinOp::Pow => "**",
        BinOp::BitAnd => "&",
        BinOp::BitOr => "|",
        BinOp::BitXor => "^",
    }
}

/// `|` union, `&` intersection, `-` difference, `^` symmetric difference.
pub(crate) fn set_algebra(op: BinOp, a: &IndexSet<HKey>, b: &IndexSet<HKey>) -> Option<Value> {
    let mut out = IndexSet::new();
    match op {
        BinOp::BitOr => {
            out.extend(a.iter().cloned());
            out.extend(b.iter().cloned());
        }
        BinOp::BitAnd => out.extend(a.iter().filter(|v| b.contains(*v)).cloned()),
        BinOp::Sub => out.extend(a.iter().filter(|v| !b.contains(*v)).cloned()),
        BinOp::BitXor => {
            out.extend(a.iter().filter(|v| !b.contains(*v)).cloned());
            out.extend(b.iter().filter(|v| !a.contains(*v)).cloned());
        }
        _ => return None,
    }
    Some(Value::new_set(out))
}

/// The indices a Python slice picks out of [n] items.
pub(crate) fn slice_indices(n: i64, l: Option<i64>, h: Option<i64>, step: i64) -> Vec<usize> {
    let (start, stop) = if step > 0 {
        (
            l.map(|i| if i < 0 { (i + n).max(0) } else { i.min(n) }).unwrap_or(0),
            h.map(|i| if i < 0 { (i + n).max(0) } else { i.min(n) }).unwrap_or(n),
        )
    } else {
        (
            l.map(|i| if i < 0 { i + n } else { i.min(n - 1) }).unwrap_or(n - 1),
            h.map(|i| if i < 0 { i + n } else { i }).unwrap_or(-1),
        )
    };
    let mut out = Vec::new();
    let mut i = start;
    if step > 0 {
        while i < stop {
            if i >= 0 && i < n {
                out.push(i as usize);
            }
            i = i.saturating_add(step);
        }
    } else {
        while i > stop {
            if i >= 0 && i < n {
                out.push(i as usize);
            }
            i = i.saturating_add(step);
        }
    }
    out
}
