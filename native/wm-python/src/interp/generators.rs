//! Lazy iteration and generators.
//!
//! [Iter] is what a `for` loop, a comprehension or a consuming builtin
//! pulls items from, one at a time: a counted `range`, items already in
//! hand, an iterator object (`iter()`, `map`, `zip`, `enumerate`, …), an
//! object with `__next__`, or a generator. Nothing is built up front, so a
//! loop over a generator that never ends stops at its `break`.
//!
//! A generator function's body runs a statement at a time. Its position is
//! a stack of [Level]s, one per block it is inside (the body, an `if`
//! branch, a loop body, a `try` clause, a `with` body, a `case`), each with
//! the state of the statement it is running there (a `for` loop's items, a
//! `try`'s pending exception). A statement with no `yield` in it runs as it
//! would anywhere else; `yield` pauses the generator at its own statement,
//! which is why a `yield` stands as a statement or on the right of `=`
//! (the resolver checks). A generator expression keeps a stack of clause
//! iterators instead.

use std::cell::RefCell;
use std::rc::Rc;

use super::ast::*;
use super::error::{Exc, PyError, R};
use super::eval::{Env, Interp};
use super::value::*;

/// Something a loop pulls items from.
pub(crate) enum Iter {
    /// Counting, as `range` does.
    Range { next: Option<i64>, stop: i64, step: i64 },
    Items(std::vec::IntoIter<Value>),
    /// A generator or an iterator object: anyone holding it shares its progress.
    Object(Rc<Object>),
    /// An object with `__next__`.
    Next(Value),
    Map(Box<(Value, Vec<Iter>)>),
    Filter(Box<(Value, Iter)>),
    /// The sources, and whether they must run out together (`strict=True`).
    Zip(Vec<Iter>, bool),
    Enumerate(Box<Iter>, i64),
    /// Finished, or lent out while its iterator object runs.
    Empty,
}

/// What `iter()`, `map()`, `filter()`, `zip()`, `enumerate()` and
/// `reversed()` hand back: an iterator, named for what made it.
pub struct IterObject {
    pub(crate) kind: &'static str,
    pub(crate) state: RefCell<Iter>,
}

pub(crate) fn iterator_object(kind: &'static str, it: Iter) -> Value {
    Value::Object(Rc::new(Object::Iterator(IterObject { kind, state: RefCell::new(it) })))
}

/// A generator: what calling a function with `yield` in it, or writing
/// `(x for x in xs)`, makes.
pub struct Generator {
    pub(crate) name: Rc<str>,
    pub(crate) state: RefCell<GenState>,
}

pub(crate) enum GenState {
    Ready(Box<GenBody>),
    Running,
    Done,
}

/// A coroutine: what calling an `async def` makes, or what an async
/// generator's `__anext__`, `asend`, `athrow` and `aclose` hand back. It runs
/// when awaited, to its end, as nothing else is waiting to run meanwhile.
pub struct Coroutine {
    pub(crate) name: Rc<str>,
    pub(crate) state: RefCell<CoState>,
}

pub(crate) enum CoState {
    /// The function and its frame, its arguments bound.
    Pending(Rc<Function>, Rc<Env>),
    /// An async generator's next item, or the one after sending a value.
    Next(Value),
    Send(Value, Value),
    Throw(Value, Value),
    Close(Value),
    Running,
    Done,
}

pub(crate) fn coroutine(name: Rc<str>, state: CoState) -> Value {
    Value::Object(Rc::new(Object::Coroutine(Coroutine { name, state: RefCell::new(state) })))
}

pub(crate) enum GenBody {
    Function(FnGen),
    Comprehension(CompGen),
}

/// How a generator is resumed.
pub(crate) enum Resume {
    Send(Value),
    Throw(Exc),
}

/// What a generator did when resumed: paused at a `yield`, or finished with
/// its return value.
pub(crate) enum Step {
    Yield(Value),
    Return(Value),
}

/// What a `yield from` source did.
enum Delegated {
    Yield(Value),
    Done(Value),
}

pub(crate) struct FnGen {
    def: Rc<FuncDef>,
    func: Rc<Function>,
    env: Rc<Env>,
    levels: Vec<Level>,
    started: bool,
    /// The source of a `yield from` that is handing out items.
    delegate: Option<Iter>,
    /// The exception an `except` block of the generator handles, kept while it is paused.
    exception: (Option<Rc<ExceptionValue>>, Option<Value>),
}

pub(crate) struct CompGen {
    comp: Rc<Comprehension>,
    env: Rc<Env>,
    /// One iterator per clause entered, outermost first.
    iters: Vec<Iter>,
}

/// One block the generator is inside: the statement it is at, and that
/// statement's state when it is a compound one being run.
struct Level {
    next: usize,
    inner: Inner,
}

enum Inner {
    None,
    /// Running branch k of an `if` (usize::MAX: the `else`).
    If(usize),
    While,
    WhileElse,
    For(Iter),
    ForElse,
    Try { phase: Phase, pending: Option<Exc>, saved: Option<Handled> },
    /// The managers entered, in order.
    With(Vec<Value>),
    Match(usize),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Body,
    Handler(usize),
    Else,
    Finally,
}

/// The block levels[depth] runs, found from the function's body down.
fn block_of<'a>(body: &'a [Stmt], levels: &[Level], depth: usize) -> &'a [Stmt] {
    let mut block = body;
    for lvl in &levels[..depth] {
        let Some(stmt) = block.get(lvl.next) else { return &[] };
        block = child_block(stmt, &lvl.inner);
    }
    block
}

fn child_block<'a>(stmt: &'a Stmt, inner: &Inner) -> &'a [Stmt] {
    match (&stmt.kind, inner) {
        (StmtKind::If(branches, else_body), Inner::If(k)) => match branches.get(*k) {
            Some((_, b)) => b,
            None => else_body.as_deref().unwrap_or(&[]),
        },
        (StmtKind::While(_, body, _), Inner::While) | (StmtKind::For(_, _, body, _), Inner::For(_)) => body,
        (StmtKind::While(_, _, Some(e)), Inner::WhileElse) | (StmtKind::For(_, _, _, Some(e)), Inner::ForElse) => e,
        (StmtKind::Try(t), Inner::Try { phase, .. }) => match phase {
            Phase::Body => &t.body,
            Phase::Handler(i) => &t.handlers[*i].body,
            Phase::Else => t.else_body.as_deref().unwrap_or(&[]),
            Phase::Finally => t.finally_body.as_deref().unwrap_or(&[]),
        },
        (StmtKind::With(_, body), Inner::With(_)) => body,
        (StmtKind::Match(m), Inner::Match(k)) => &m.cases[*k].body,
        _ => &[],
    }
}

/// Step into the block of the statement at levels[depth].
fn enter(levels: &mut Vec<Level>, depth: usize, inner: Inner) {
    levels[depth].inner = inner;
    levels.push(Level { next: 0, inner: Inner::None });
}

/// The statement at levels[depth] is finished: on to the next.
fn done(levels: &mut [Level], depth: usize) {
    levels[depth].inner = Inner::None;
    levels[depth].next += 1;
}

impl Interp {
    // MARK: - lazy iteration

    /// An iterator over [v], pulling items as asked.
    pub(crate) fn iter_of(&mut self, v: &Value) -> R<Iter> {
        Ok(match v {
            Value::Object(o) => match o.as_ref() {
                Object::Generator(_) | Object::Iterator(_) => Iter::Object(o.clone()),
                Object::Range(start, stop, step) => Iter::Range { next: Some(*start), stop: *stop, step: *step },
                Object::DictView(k, d) => Iter::Items(view_items(*k, d).into_iter()),
                _ => Iter::Items(self.iterate(v)?.into_iter()),
            },
            Value::List(l) => Iter::Items(l.borrow().clone().into_iter()),
            Value::Instance(_) => self.instance_iter(v)?,
            _ => Iter::Items(self.iterate(v)?.into_iter()),
        })
    }

    /// `for x in obj`: what `__iter__` returns, `__next__` on the object
    /// itself, or `__getitem__` with `__len__`.
    pub(crate) fn instance_iter(&mut self, v: &Value) -> R<Iter> {
        if let Some(it) = self.dunder(v, "__iter__", Vec::new())? {
            return match &it {
                Value::Instance(a) => {
                    if a.class.lookup("__next__").is_none() {
                        if let Value::Instance(b) = v {
                            if Rc::ptr_eq(a, b) {
                                return Err(self.error("__iter__ returned the object itself, which has no __next__", "TypeError"));
                            }
                        }
                        return Err(self.error(format!("iter() returned non-iterator of type '{}'", it.type_name()), "TypeError"));
                    }
                    Ok(Iter::Next(it))
                }
                other => self.iter_of(other),
            };
        }
        if let Value::Instance(inst) = v {
            if inst.class.lookup("__next__").is_some() {
                return Ok(Iter::Next(v.clone()));
            }
        }
        if let Some(n) = self.dunder(v, "__len__", Vec::new())? {
            let n = n.as_int().unwrap_or(0).max(0);
            if n as usize > self.max_container {
                return Err(self.container_abort("object"));
            }
            let mut out = Vec::new();
            for i in 0..n {
                if let Some(item) = self.dunder(v, "__getitem__", vec![Value::Int(i)])? {
                    out.push(item);
                }
            }
            return Ok(Iter::Items(out.into_iter()));
        }
        Err(self.error(format!("'{}' object is not iterable", v.type_name()), "TypeError"))
    }

    /// The next item, or None when [it] has run out.
    pub(crate) fn next_item(&mut self, it: &mut Iter) -> R<Option<Value>> {
        match it {
            Iter::Range { next, stop, step } => {
                let Some(i) = *next else { return Ok(None) };
                if (*step > 0 && i < *stop) || (*step < 0 && i > *stop) {
                    // Past i64 the count stops.
                    *next = i.checked_add(*step);
                    Ok(Some(Value::Int(i)))
                } else {
                    *next = None;
                    Ok(None)
                }
            }
            Iter::Items(items) => Ok(items.next()),
            Iter::Object(o) => {
                let o = o.clone();
                self.object_next(&o)
            }
            Iter::Next(v) => {
                self.tick()?;
                let v = v.clone();
                match self.dunder(&v, "__next__", Vec::new()) {
                    Ok(Some(x)) => Ok(Some(x)),
                    Ok(None) => Err(self.error(format!("'{}' object is not an iterator", v.type_name()), "TypeError")),
                    Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" => {
                        *it = Iter::Empty;
                        Ok(None)
                    }
                    Err(e) => Err(e),
                }
            }
            Iter::Map(m) => {
                let mut args = Vec::with_capacity(m.1.len());
                for src in m.1.iter_mut() {
                    match self.next_item(src)? {
                        Some(v) => args.push(v),
                        None => return Ok(None),
                    }
                }
                self.tick()?;
                let f = m.0.clone();
                self.invoke(&f, args).map(Some)
            }
            Iter::Filter(f) => loop {
                let Some(v) = self.next_item(&mut f.1)? else { return Ok(None) };
                self.tick()?;
                // filter(None, xs) keeps the truthy items.
                let keep = if f.0.is_none() {
                    self.truth(&v)?
                } else {
                    let func = f.0.clone();
                    let r = self.invoke(&func, vec![v.clone()])?;
                    self.truth(&r)?
                };
                if keep {
                    return Ok(Some(v));
                }
            },
            Iter::Zip(sources, strict) => {
                let mut row = Vec::with_capacity(sources.len());
                for (i, src) in sources.iter_mut().enumerate() {
                    match self.next_item(src)? {
                        Some(v) => row.push(v),
                        None => {
                            if *strict {
                                if i > 0 {
                                    let message = format!("zip() argument {} is shorter than argument{} 1", i + 1, if i > 1 { "s" } else { "" });
                                    return Err(self.error(message.replace("arguments 1", &format!("arguments 1-{i}")), "ValueError"));
                                }
                                for (j, rest) in sources.iter_mut().enumerate().skip(1) {
                                    if self.next_item(rest)?.is_some() {
                                        let message = format!("zip() argument {} is longer than argument{} 1", j + 1, if j > 1 { "s" } else { "" });
                                        return Err(self.error(message.replace("arguments 1", &format!("arguments 1-{j}")), "ValueError"));
                                    }
                                }
                            }
                            return Ok(None);
                        }
                    }
                }
                Ok(Some(Value::tuple(row)))
            }
            Iter::Enumerate(src, n) => match self.next_item(src)? {
                Some(v) => {
                    let i = *n;
                    *n += 1;
                    Ok(Some(Value::tuple(vec![Value::Int(i), v])))
                }
                None => Ok(None),
            },
            Iter::Empty => Ok(None),
        }
    }

    /// The next item of a generator or an iterator object.
    pub(crate) fn object_next(&mut self, o: &Rc<Object>) -> R<Option<Value>> {
        match o.as_ref() {
            Object::Generator(_) => match self.gen_resume(o, Resume::Send(Value::None))? {
                Step::Yield(v) => Ok(Some(v)),
                Step::Return(_) => Ok(None),
            },
            Object::Iterator(io) => {
                let mut it = std::mem::replace(&mut *io.state.borrow_mut(), Iter::Empty);
                let r = self.next_item(&mut it);
                if !matches!(r, Ok(None)) {
                    *io.state.borrow_mut() = it;
                }
                r
            }
            _ => Err(self.error(format!("'{}' object is not an iterator", Value::Object(o.clone()).type_name()), "TypeError")),
        }
    }

    /// Every item left in [it], charged a step each and held to the container cap.
    pub(crate) fn collect_iter(&mut self, it: &mut Iter, what: &str) -> R<Vec<Value>> {
        let mut out = Vec::new();
        while let Some(v) = self.next_item(it)? {
            out.push(v);
            if out.len() > self.max_container {
                return Err(self.container_abort(what));
            }
        }
        Ok(out)
    }

    // MARK: - making generators

    /// What calling a generator function gives: a generator over [frame],
    /// which runs nothing until asked for its first item.
    pub(crate) fn function_generator(&mut self, f: &Rc<Function>, frame: Rc<Env>) -> Value {
        let FnBody::Def(def) = &f.body else { unreachable!("only a def can hold yield") };
        let body = FnGen {
            def: def.clone(),
            func: f.clone(),
            env: frame,
            levels: vec![Level { next: 0, inner: Inner::None }],
            started: false,
            delegate: None,
            exception: (None, None),
        };
        generator(f.display_name(), GenBody::Function(body))
    }

    /// `(x for x in xs)`: the first iterable is evaluated now, the rest as items are asked for.
    pub(crate) fn comprehension_generator(&mut self, c: &Rc<Comprehension>, env: &Rc<Env>) -> R<Value> {
        let first = self.loop_items(&c.clauses[0].iterable, env)?;
        let scope = Env::frame_of(env.clone(), c.frame.names.len());
        Ok(generator(Rc::from("<genexpr>"), GenBody::Comprehension(CompGen { comp: c.clone(), env: scope, iters: vec![first] })))
    }

    // MARK: - running generators

    /// Run a generator until it pauses or finishes.
    pub(crate) fn gen_resume(&mut self, o: &Rc<Object>, input: Resume) -> R<Step> {
        let Object::Generator(g) = o.as_ref() else {
            return Err(self.error("not a generator", "TypeError"));
        };
        let state = std::mem::replace(&mut *g.state.borrow_mut(), GenState::Running);
        let mut body = match state {
            GenState::Ready(b) => b,
            GenState::Running => return Err(self.error("generator already executing", "ValueError")),
            GenState::Done => {
                *g.state.borrow_mut() = GenState::Done;
                return match input {
                    Resume::Send(_) => Ok(Step::Return(Value::None)),
                    Resume::Throw(e) => Err(e),
                };
            }
        };
        let r = match body.as_mut() {
            GenBody::Function(f) => self.fn_resume(f, input),
            GenBody::Comprehension(c) => self.comp_resume(c, input),
        };
        *g.state.borrow_mut() = match &r {
            Ok(Step::Yield(_)) => GenState::Ready(body),
            _ => GenState::Done,
        };
        r
    }

    fn comp_resume(&mut self, g: &mut CompGen, input: Resume) -> R<Step> {
        if let Resume::Throw(e) = input {
            return Err(e);
        }
        let comp = g.comp.clone();
        loop {
            let k = g.iters.len();
            let Some(top) = g.iters.last_mut() else { return Ok(Step::Return(Value::None)) };
            let Some(item) = self.next_item(top)? else {
                g.iters.pop();
                continue;
            };
            self.tick()?;
            let clause = &comp.clauses[k - 1];
            self.assign(&clause.target, item, &g.env)?;
            if !self.conditions_hold(&clause.conditions, &g.env)? {
                continue;
            }
            if k < comp.clauses.len() {
                let it = self.loop_items(&comp.clauses[k].iterable, &g.env)?;
                g.iters.push(it);
                continue;
            }
            return Ok(Step::Yield(self.eval(&comp.element, &g.env)?));
        }
    }

    fn fn_resume(&mut self, g: &mut FnGen, input: Resume) -> R<Step> {
        self.check_depth()?;
        self.call_depth += 1;
        self.frames.push((g.func.clone(), g.env.clone()));
        let caller_line = self.current_line;
        // The generator's own `except` state while it runs; the caller's back after.
        let caller_exception = std::mem::replace(&mut self.active_exception, g.exception.0.take());
        let caller_instance = std::mem::replace(&mut self.active_instance, g.exception.1.take());
        let r = self.fn_run(g, input);
        g.exception = (
            std::mem::replace(&mut self.active_exception, caller_exception),
            std::mem::replace(&mut self.active_instance, caller_instance),
        );
        self.frames.pop();
        self.call_depth -= 1;
        if r.is_ok() {
            self.current_line = caller_line;
        }
        r
    }

    fn fn_run(&mut self, g: &mut FnGen, input: Resume) -> R<Step> {
        let def = g.def.clone();
        let env = g.env.clone();
        if !g.started {
            g.started = true;
            match input {
                Resume::Send(v) if !v.is_none() => {
                    return Err(self.error("can't send non-None value to a just-started generator", "TypeError"));
                }
                Resume::Throw(e) => return Err(e),
                Resume::Send(_) => {}
            }
        } else {
            // Paused at a yield statement, which now goes on (or raises).
            let r = match input {
                Resume::Throw(e) => {
                    g.delegate = None;
                    Err(e)
                }
                Resume::Send(v) => match g.delegate.take() {
                    Some(mut d) => match self.delegate_step(&mut d, v) {
                        Ok(Delegated::Yield(y)) => {
                            g.delegate = Some(d);
                            return Ok(Step::Yield(y));
                        }
                        Ok(Delegated::Done(result)) => self.finish_yield(g, &def, &env, result),
                        Err(e) => Err(e),
                    },
                    None => self.finish_yield(g, &def, &env, v),
                },
            };
            if let Err(e) = r {
                match self.gen_raise(g, &def, &env, e) {
                    Ok(()) => {}
                    Err(Exc::Return(v)) => return Ok(Step::Return(v)),
                    Err(x) => return Err(x),
                }
            }
        }
        self.gen_loop(g, &def, &env)
    }

    /// The yield statement the generator paused at takes [value] (assigning
    /// it, for `x = yield …`), and the generator moves past it.
    fn finish_yield(&mut self, g: &mut FnGen, def: &Rc<FuncDef>, env: &Rc<Env>, value: Value) -> R<()> {
        let depth = g.levels.len() - 1;
        let block = block_of(&def.body, &g.levels, depth);
        if let Some(stmt) = block.get(g.levels[depth].next) {
            if let StmtKind::Assign(targets, _) = &stmt.kind {
                self.current_line = stmt.line;
                for t in targets {
                    self.assign(t, value.clone(), env)?;
                }
            }
        }
        g.levels[depth].next += 1;
        Ok(())
    }

    fn delegate_step(&mut self, it: &mut Iter, sent: Value) -> R<Delegated> {
        if let Iter::Object(o) = it {
            if let Object::Generator(_) = o.as_ref() {
                let o = o.clone();
                return Ok(match self.gen_resume(&o, Resume::Send(sent))? {
                    Step::Yield(v) => Delegated::Yield(v),
                    Step::Return(v) => Delegated::Done(v),
                });
            }
        }
        Ok(match self.next_item(it)? {
            Some(v) => Delegated::Yield(v),
            None => Delegated::Done(Value::None),
        })
    }

    /// Run statements until a `yield` pauses the generator or the body ends.
    fn gen_loop(&mut self, g: &mut FnGen, def: &Rc<FuncDef>, env: &Rc<Env>) -> R<Step> {
        loop {
            let depth = g.levels.len() - 1;
            let block = block_of(&def.body, &g.levels, depth);
            let at = g.levels[depth].next;
            let r = if at >= block.len() {
                if depth == 0 {
                    return Ok(Step::Return(Value::None));
                }
                g.levels.pop();
                self.block_done(g, def, env)
            } else {
                let stmt = &block[at];
                if !stmt.yields {
                    let r = self.exec(stmt, env);
                    if r.is_ok() {
                        g.levels[depth].next += 1;
                    }
                    r
                } else {
                    self.tick()?;
                    self.current_line = stmt.line;
                    match self.start_statement(g, stmt, depth, env) {
                        Ok(Some(v)) => return Ok(Step::Yield(v)),
                        Ok(None) => Ok(()),
                        Err(e) => Err(e),
                    }
                }
            };
            if let Err(e) = r {
                match self.gen_raise(g, def, env, e) {
                    Ok(()) => {}
                    Err(Exc::Return(v)) => return Ok(Step::Return(v)),
                    Err(x) => return Err(x),
                }
            }
        }
    }

    /// Start the statement at levels[depth], one with a `yield` in it or
    /// around one. Some(value) when it pauses the generator.
    fn start_statement(&mut self, g: &mut FnGen, stmt: &Stmt, depth: usize, env: &Rc<Env>) -> R<Option<Value>> {
        match &stmt.kind {
            StmtKind::Expr(Expr::Yield(v)) | StmtKind::Assign(_, Expr::Yield(v)) => {
                let value = match v {
                    Some(e) => self.eval(e, env)?,
                    None => Value::None,
                };
                Ok(Some(value))
            }
            StmtKind::Expr(Expr::YieldFrom(e)) | StmtKind::Assign(_, Expr::YieldFrom(e)) => {
                let source = self.eval(e, env)?;
                let mut it = self.iter_of(&source)?;
                match self.delegate_step(&mut it, Value::None)? {
                    Delegated::Yield(v) => {
                        g.delegate = Some(it);
                        Ok(Some(v))
                    }
                    Delegated::Done(result) => {
                        if let StmtKind::Assign(targets, _) = &stmt.kind {
                            for t in targets {
                                self.assign(t, result.clone(), env)?;
                            }
                        }
                        g.levels[depth].next += 1;
                        Ok(None)
                    }
                }
            }
            StmtKind::If(branches, else_body) => {
                for (k, (cond, _)) in branches.iter().enumerate() {
                    if self.holds(cond, env)? {
                        enter(&mut g.levels, depth, Inner::If(k));
                        return Ok(None);
                    }
                }
                if else_body.is_some() {
                    enter(&mut g.levels, depth, Inner::If(usize::MAX));
                } else {
                    done(&mut g.levels, depth);
                }
                Ok(None)
            }
            StmtKind::While(cond, _, else_body) => {
                self.while_test(g, depth, cond, else_body.is_some(), env)?;
                Ok(None)
            }
            StmtKind::For(target, iterable, _, else_body) => {
                let it = self.loop_items(iterable, env)?;
                self.for_next(g, depth, target, it, else_body.is_some(), env)?;
                Ok(None)
            }
            StmtKind::Try(_) => {
                enter(&mut g.levels, depth, Inner::Try { phase: Phase::Body, pending: None, saved: None });
                Ok(None)
            }
            StmtKind::With(items, _) => {
                let mut managers = Vec::with_capacity(items.len());
                for (m, target) in items {
                    let manager = self.eval(m, env)?;
                    let entered = self.call_method(&manager, "__enter__", Vec::new(), Vec::new())?;
                    managers.push(manager);
                    if let Some(t) = target {
                        self.assign(t, entered, env)?;
                    }
                }
                enter(&mut g.levels, depth, Inner::With(managers));
                Ok(None)
            }
            StmtKind::Match(m) => {
                let subject = self.eval(&m.subject, env)?;
                for (k, case) in m.cases.iter().enumerate() {
                    if self.case_matches(case, &subject, env)? {
                        enter(&mut g.levels, depth, Inner::Match(k));
                        return Ok(None);
                    }
                }
                done(&mut g.levels, depth);
                Ok(None)
            }
            _ => Err(self.error("this statement cannot pause a generator", "SyntaxError")),
        }
    }

    fn while_test(&mut self, g: &mut FnGen, depth: usize, cond: &Expr, has_else: bool, env: &Rc<Env>) -> R<()> {
        if self.holds(cond, env)? {
            self.tick()?;
            enter(&mut g.levels, depth, Inner::While);
        } else if has_else {
            enter(&mut g.levels, depth, Inner::WhileElse);
        } else {
            done(&mut g.levels, depth);
        }
        Ok(())
    }

    fn for_next(&mut self, g: &mut FnGen, depth: usize, target: &Expr, mut it: Iter, has_else: bool, env: &Rc<Env>) -> R<()> {
        match self.next_item(&mut it)? {
            Some(item) => {
                self.tick()?;
                self.assign(target, item, env)?;
                enter(&mut g.levels, depth, Inner::For(it));
            }
            None if has_else => enter(&mut g.levels, depth, Inner::ForElse),
            None => done(&mut g.levels, depth),
        }
        Ok(())
    }

    /// The block inside the statement at the top level ran to its end.
    fn block_done(&mut self, g: &mut FnGen, def: &Rc<FuncDef>, env: &Rc<Env>) -> R<()> {
        let depth = g.levels.len() - 1;
        let block = block_of(&def.body, &g.levels, depth);
        let stmt = &block[g.levels[depth].next];
        let inner = std::mem::replace(&mut g.levels[depth].inner, Inner::None);
        match (inner, &stmt.kind) {
            (Inner::While, StmtKind::While(cond, _, else_body)) => self.while_test(g, depth, cond, else_body.is_some(), env),
            (Inner::For(it), StmtKind::For(target, _, _, else_body)) => self.for_next(g, depth, target, it, else_body.is_some(), env),
            (Inner::Try { phase, pending, saved }, StmtKind::Try(t)) => {
                let finally = t.finally_body.is_some();
                match phase {
                    Phase::Body if t.else_body.is_some() => {
                        enter(&mut g.levels, depth, Inner::Try { phase: Phase::Else, pending: None, saved: None })
                    }
                    Phase::Handler(i) => {
                        self.leave_handler(&t.handlers[i], saved, env);
                        if finally {
                            enter(&mut g.levels, depth, Inner::Try { phase: Phase::Finally, pending: None, saved: None });
                        } else {
                            done(&mut g.levels, depth);
                        }
                    }
                    Phase::Body | Phase::Else if finally => {
                        enter(&mut g.levels, depth, Inner::Try { phase: Phase::Finally, pending: None, saved: None })
                    }
                    Phase::Finally => {
                        if let Some(p) = pending {
                            return Err(p);
                        }
                        done(&mut g.levels, depth);
                    }
                    _ => done(&mut g.levels, depth),
                }
                Ok(())
            }
            (Inner::With(managers), _) => {
                for m in managers.iter().rev() {
                    self.call_method(m, "__exit__", vec![Value::None, Value::None, Value::None], Vec::new())?;
                }
                done(&mut g.levels, depth);
                Ok(())
            }
            _ => {
                done(&mut g.levels, depth);
                Ok(())
            }
        }
    }

    fn leave_handler(&mut self, h: &ExceptHandler, saved: Option<Handled>, env: &Rc<Env>) {
        if let Some((e, i, line)) = saved {
            self.active_exception = e;
            self.active_instance = i;
            self.active_line = line;
        }
        if let Some(b) = &h.binding {
            self.unbind(b, env);
        }
    }

    /// The statement at the top level raised [exc] (an error, or `break`,
    /// `continue` or `return` leaving it): unwind through the blocks around
    /// it until a loop, `try` or `with` takes it. Ok when one did and the
    /// generator goes on; otherwise what leaves the generator (`return`'s
    /// value as `Exc::Return`).
    fn gen_raise(&mut self, g: &mut FnGen, def: &Rc<FuncDef>, env: &Rc<Env>, mut exc: Exc) -> R<()> {
        if let Exc::Error(e) = &exc {
            if e.is_abort() {
                return Err(exc);
            }
        }
        loop {
            g.levels.pop();
            let Some(depth) = g.levels.len().checked_sub(1) else {
                return Err(match exc {
                    Exc::Break => self.error("'break' outside loop", "SyntaxError"),
                    Exc::Continue => self.error("'continue' not properly in loop", "SyntaxError"),
                    other => other,
                });
            };
            let block = block_of(&def.body, &g.levels, depth);
            let stmt = &block[g.levels[depth].next];
            let inner = std::mem::replace(&mut g.levels[depth].inner, Inner::None);
            match inner {
                Inner::While | Inner::For(_) if matches!(exc, Exc::Break) => {
                    done(&mut g.levels, depth);
                    return Ok(());
                }
                Inner::While | Inner::For(_) if matches!(exc, Exc::Continue) => {
                    g.levels[depth].inner = inner;
                    match self.block_done(g, def, env) {
                        Ok(()) => return Ok(()),
                        Err(e) => {
                            exc = e;
                            continue;
                        }
                    }
                }
                Inner::Try { phase, pending: _, saved } => {
                    let StmtKind::Try(t) = &stmt.kind else { continue };
                    if let (Phase::Body, Exc::Error(e)) = (phase, &exc) {
                        if !e.is_abort() {
                            let kind = e.kind.clone();
                            match self.handler_for(&t.handlers, &kind, env) {
                                Ok(Some(i)) => {
                                    let Exc::Error(e) = exc else { unreachable!() };
                                    let saved = self.enter_handler(&t.handlers[i], &e, env);
                                    enter(&mut g.levels, depth, Inner::Try { phase: Phase::Handler(i), pending: None, saved: Some(saved) });
                                    return Ok(());
                                }
                                Ok(None) => {}
                                Err(e2) => exc = e2,
                            }
                        }
                    }
                    if let Phase::Handler(i) = phase {
                        self.leave_handler(&t.handlers[i], saved, env);
                    }
                    if phase != Phase::Finally && t.finally_body.is_some() {
                        enter(&mut g.levels, depth, Inner::Try { phase: Phase::Finally, pending: Some(exc), saved: None });
                        return Ok(());
                    }
                }
                Inner::With(managers) => {
                    let mut swallowed = false;
                    let mut args = match &exc {
                        Exc::Error(e) if !e.is_abort() => {
                            let value = self.caught_value(e);
                            vec![self.type_of(&value), value, Value::None]
                        }
                        _ => vec![Value::None, Value::None, Value::None],
                    };
                    for m in managers.iter().rev() {
                        let r = match self.call_method(m, "__exit__", args.clone(), Vec::new()) {
                            Ok(r) => r,
                            Err(e) => {
                                exc = e;
                                swallowed = false;
                                break;
                            }
                        };
                        if !args[0].is_none() && self.truth(&r)? {
                            swallowed = true;
                            args = vec![Value::None, Value::None, Value::None];
                        }
                    }
                    if swallowed {
                        done(&mut g.levels, depth);
                        return Ok(());
                    }
                }
                _ => {}
            }
        }
    }

    /// Bind what an `except` clause caught and make it the active exception;
    /// the state before, for when the handler ends.
    pub(crate) fn enter_handler(&mut self, h: &ExceptHandler, error: &PyError, env: &Rc<Env>) -> Handled {
        let caught = self.caught_value(error);
        let value = match &caught {
            Value::Exception(e) => e.clone(),
            _ => Rc::new(ExceptionValue::new(error.kind.clone(), error.message.clone())),
        };
        self.raised = None;
        if let Some(b) = &h.binding {
            self.store_name(b, caught.clone(), env);
        }
        let saved_exception = self.active_exception.replace(value);
        let saved_instance = self.active_instance.replace(caught);
        let saved_line = std::mem::replace(&mut self.active_line, error.line);
        (saved_exception, saved_instance, saved_line)
    }

    // MARK: - coroutines

    /// `await value`: a coroutine runs to its end; anything else with an
    /// `__await__` has the iterator that gives run until it returns.
    pub(crate) fn await_value(&mut self, v: Value) -> R<Value> {
        let it = match &v {
            Value::Object(o) if matches!(o.as_ref(), Object::Coroutine(_) | Object::Generator(_)) => v.clone(),
            _ => match self.dunder(&v, "__await__", Vec::new())? {
                Some(it) => it,
                None => return Err(self.error(format!("object {} can't be used in 'await' expression", v.type_name()), "TypeError")),
            },
        };
        if let Value::Object(o) = &it {
            match o.as_ref() {
                Object::Coroutine(_) => return self.coroutine_run(o),
                Object::Generator(_) => loop {
                    // What it yields would go to an event loop; there is none to wait for.
                    if let Step::Return(v) = self.gen_resume(o, Resume::Send(Value::None))? {
                        return Ok(v);
                    }
                },
                _ => {}
            }
        }
        loop {
            match self.dunder(&it, "__next__", Vec::new()) {
                Ok(Some(_)) => {}
                Ok(None) => return Err(self.error(format!("__await__() returned non-iterator of type '{}'", it.type_name()), "TypeError")),
                Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" => {
                    let caught = self.caught_value(&e);
                    return Ok(self.attribute(&caught, "value").unwrap_or(Value::None));
                }
                Err(e) => return Err(e),
            }
        }
    }

    /// Runs a coroutine to its end: its value, or what it raised.
    pub(crate) fn coroutine_run(&mut self, o: &Rc<Object>) -> R<Value> {
        let Object::Coroutine(c) = o.as_ref() else { unreachable!() };
        let busy = match &*c.state.borrow() {
            CoState::Running => Some("coroutine is being awaited already"),
            CoState::Done => Some("cannot reuse already awaited coroutine"),
            _ => None,
        };
        if let Some(message) = busy {
            return Err(self.error(message, "RuntimeError"));
        }
        let state = std::mem::replace(&mut *c.state.borrow_mut(), CoState::Running);
        let gen_step = |this: &mut Interp, gen: &Value, method: &str, args: &[Value]| -> R<Value> {
            let Value::Object(g) = gen else { return Ok(Value::None) };
            match this.generator_method(g, method, args) {
                // An async generator that ends says so with StopAsyncIteration.
                Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" && method != "close" => Err(this.error("", "StopAsyncIteration")),
                r => r,
            }
        };
        let result = match state {
            CoState::Pending(f, frame) => self.run_body(&f, frame),
            CoState::Next(g) => gen_step(self, &g, "__next__", &[]),
            CoState::Send(g, v) => gen_step(self, &g, "send", &[v]),
            CoState::Throw(g, v) => gen_step(self, &g, "throw", &[v]),
            CoState::Close(g) => gen_step(self, &g, "close", &[]),
            CoState::Running | CoState::Done => unreachable!(),
        };
        *c.state.borrow_mut() = CoState::Done;
        result
    }

    /// The methods of a coroutine and of an async generator.
    pub(crate) fn coroutine_method(&mut self, o: &Rc<Object>, name: &str, args: &[Value]) -> R<Value> {
        match (o.as_ref(), name) {
            (Object::Coroutine(_), "send" | "__next__") => {
                let v = self.coroutine_run(o)?;
                Err(self.stop_iteration(v))
            }
            (Object::Coroutine(c), "throw") => {
                *c.state.borrow_mut() = CoState::Done;
                match args.first() {
                    Some(v) => Err(self.exception_from(v)),
                    None => Err(self.error("throw expected at least 1 argument, got 0", "TypeError")),
                }
            }
            (Object::Coroutine(c), "close") => {
                *c.state.borrow_mut() = CoState::Done;
                Ok(Value::None)
            }
            (Object::Coroutine(_), "__await__") | (Object::AsyncGen(..), "__aiter__") => Ok(Value::Object(o.clone())),
            (Object::AsyncGen(n, g), "__anext__") => Ok(coroutine(n.clone(), CoState::Next(g.clone()))),
            (Object::AsyncGen(n, g), "asend") => Ok(coroutine(n.clone(), CoState::Send(g.clone(), args.first().cloned().unwrap_or(Value::None)))),
            (Object::AsyncGen(n, g), "athrow") => match args.first() {
                Some(v) => Ok(coroutine(n.clone(), CoState::Throw(g.clone(), v.clone()))),
                None => Err(self.error("athrow expected at least 1 argument, got 0", "TypeError")),
            },
            (Object::AsyncGen(n, g), "aclose") => Ok(coroutine(n.clone(), CoState::Close(g.clone()))),
            (Object::AsyncGen(..), _) => Err(self.error(format!("'async_generator' object has no attribute '{name}'"), "AttributeError")),
            _ => Err(self.error(format!("'coroutine' object has no attribute '{name}'"), "AttributeError")),
        }
    }

    // MARK: - generator methods

    /// `gen.send(v)`, `gen.throw(e)`, `gen.close()`, `next(gen)`.
    pub(crate) fn generator_method(&mut self, o: &Rc<Object>, name: &str, args: &[Value]) -> R<Value> {
        match name {
            "__next__" | "send" => {
                let input = if name == "send" { args.first().cloned().unwrap_or(Value::None) } else { Value::None };
                match self.gen_resume(o, Resume::Send(input))? {
                    Step::Yield(v) => Ok(v),
                    Step::Return(v) => Err(self.stop_iteration(v)),
                }
            }
            "throw" => {
                let exc = match args.first() {
                    Some(v) => self.exception_from(v),
                    None => self.error("throw expected at least 1 argument, got 0", "TypeError"),
                };
                match self.gen_resume(o, Resume::Throw(exc))? {
                    Step::Yield(v) => Ok(v),
                    Step::Return(v) => Err(self.stop_iteration(v)),
                }
            }
            "close" => {
                let Object::Generator(g) = o.as_ref() else { return Ok(Value::None) };
                let started = matches!(&*g.state.borrow(), GenState::Ready(b) if matches!(b.as_ref(), GenBody::Function(f) if f.started));
                if !started {
                    *g.state.borrow_mut() = GenState::Done;
                    return Ok(Value::None);
                }
                match self.gen_resume(o, Resume::Throw(self.error("", "GeneratorExit"))) {
                    Ok(Step::Yield(_)) => Err(self.error("generator ignored GeneratorExit", "RuntimeError")),
                    Ok(Step::Return(_)) => Ok(Value::None),
                    Err(Exc::Error(e)) if !e.is_abort() && (e.kind == "GeneratorExit" || e.kind == "StopIteration") => Ok(Value::None),
                    Err(e) => Err(e),
                }
            }
            "__iter__" => Ok(Value::Object(o.clone())),
            _ => Err(self.error(format!("'generator' object has no attribute '{name}'"), "AttributeError")),
        }
    }

    /// `StopIteration`, carrying a generator's return value.
    pub(crate) fn stop_iteration(&mut self, value: Value) -> Exc {
        if value.is_none() {
            return self.error("", "StopIteration");
        }
        let message = self.to_str(&value).unwrap_or_default();
        let ex = ExceptionValue { kind: "StopIteration".into(), message, args: vec![value], cause: None, context: None };
        let ex = Rc::new(ex);
        self.raised = Some((Rc::from("StopIteration"), ex.message.clone(), Value::Exception(ex.clone())));
        super::eval::err(ex.message.clone(), "StopIteration", self.current_line)
    }

    /// An exception to raise from what a script handed over: an exception
    /// value or instance, or a class or builtin exception name to make one of.
    fn exception_from(&mut self, v: &Value) -> Exc {
        match v {
            Value::Exception(e) => {
                self.raised = Some((Rc::from(e.kind.as_str()), e.message.clone(), v.clone()));
                super::eval::err(e.message.clone(), &e.kind, self.current_line)
            }
            Value::Instance(i) => {
                let i = i.clone();
                self.raise_instance(&i)
            }
            Value::Class(c) => match self.construct(&c.clone(), Vec::new(), Vec::new()) {
                Ok(Value::Instance(i)) => self.raise_instance(&i),
                Ok(_) => self.error("exceptions must derive from Exception", "TypeError"),
                Err(e) => e,
            },
            Value::Builtin(kind) => self.error("", kind),
            other => self.error(format!("exceptions must be an exception type, not '{}'", other.type_name()), "TypeError"),
        }
    }
}

fn generator(name: Rc<str>, body: GenBody) -> Value {
    Value::Object(Rc::new(Object::Generator(Generator { name, state: RefCell::new(GenState::Ready(Box::new(body))) })))
}

/// What a handler hid while it ran: the exception being handled before it,
/// as a value and an instance, and the line it was raised on.
pub(crate) type Handled = (Option<Rc<ExceptionValue>>, Option<Value>, Option<u32>);
