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

use super::ast::*;
use super::builtins::is_builtin_name;
use super::error::{AbortKind, Exc, PyError, R};
use super::format::py_format;
use super::host::{Call, HostFunction, HostModule};
use super::modules::{self, Clock};
use super::text::{boundaries, grapheme_count, longer_than, str_compare};
use super::fxhash::FxBuild;
use super::generators::Iter;
use super::scope::Scope;
use super::value::*;

/// How deep calls may go: CPython's default recursion limit. PyMini has none.
pub const MAX_CALL_DEPTH: u32 = 1000;

/// The most native stack a script may use, measured from where it started.
/// Hosts give scripts 8 MB; what is left over covers the stretch between
/// two checks and the host's own frames. An unoptimised build's frames are
/// about twice the size, so it allows 24 MB, which its tests' 64 MB threads
/// give: 1,000 calls deep fits either way.
pub const STACK_BUDGET: usize = if cfg!(debug_assertions) { 24 << 20 } else { 6 << 20 };

/// How often, in steps, the cancel flag and the deadline are read.
const POLL_EVERY: u64 = 256;

const PRINTF_TYPES: &[&str] = &["s", "r", "a", "d", "i", "u", "f", "F", "g", "G", "e", "E", "x", "X", "o", "c"];

/// A scope at run time. A function call's (or a comprehension's) keeps its
/// variables in [slots], laid out by the resolver; a module's or a class
/// body's keeps its names in [vars]. Functions capture the scope they were
/// defined in, and a frame's [parent] is that captured scope, so a closure
/// reaches an enclosing function's variable a known number of scopes out.
pub(crate) struct Env {
    pub slots: RefCell<Vec<Option<Value>>>,
    pub vars: RefCell<Scope>,
    pub parent: Option<Rc<Env>>,
}

impl Env {
    /// A module's (no parent) or a class body's scope.
    pub fn new(parent: Option<Rc<Env>>) -> Rc<Env> {
        Rc::new(Env { slots: RefCell::new(Vec::new()), vars: RefCell::new(Scope::with_capacity(0)), parent })
    }

    /// A frame of [n] unbound slots, made fresh (one a generator keeps).
    pub fn frame_of(parent: Rc<Env>, n: usize) -> Rc<Env> {
        Rc::new(Env { slots: RefCell::new(vec![None; n]), vars: RefCell::new(Scope::with_capacity(0)), parent: Some(parent) })
    }

    /// The module this scope belongs to: the outermost one.
    #[inline]
    pub fn module(&self) -> &Env {
        let mut e = self;
        while let Some(p) = &e.parent {
            e = p;
        }
        e
    }

    /// The scope [depth] parents out.
    #[inline]
    fn up(&self, depth: u32) -> &Env {
        let mut e = self;
        for _ in 0..depth {
            match &e.parent {
                Some(p) => e = p,
                None => break,
            }
        }
        e
    }

    /// A module's or class body's name.
    pub fn get(&self, name: &str) -> Option<Value> {
        self.vars.borrow().get(name).cloned()
    }

    pub fn define(&self, name: &Rc<str>, value: Value) {
        self.vars.borrow_mut().insert(name.clone(), value);
    }

    /// `del name` in a module or class body. Returns whether it was bound.
    pub fn remove(&self, name: &str) -> bool {
        let old = self.vars.borrow_mut().shift_remove(name);
        old.is_some()
    }
}

/// How many finished frames are kept for the next calls to reuse.
const SPARE_FRAMES: usize = 32;

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
    /// The step at which [tick] next looks past its counter: at the budget,
    /// or sooner to poll the cancel flag and the deadline.
    poll_at: u64,
    pub current_line: u32,
    pub printed: Vec<String>,
    pub on_print: Option<Box<dyn FnMut(&str)>>,
    pub(crate) active_exception: Option<Rc<ExceptionValue>>,
    pub random_state: u64,
    pub enabled_modules: Vec<&'static str>,
    pub host_functions: HashMap<String, Rc<HostFunction>, FxBuild>,
    pub host_modules: HashMap<String, Rc<HostModule>>,
    pub max_container: usize,
    pub max_string: usize,
    pub(crate) call_depth: u32,
    cluster_cache: Option<(Rc<str>, Rc<[u32]>)>,
    /// The dict `globals()` gave for a module: names set through it are
    /// found when the module's own variables do not have them.
    globals_dicts: Vec<(std::rc::Weak<Env>, DictRef)>,
    /// How deep `==` is inside containers that hold instances.
    pub(crate) eq_depth: u32,
    /// The file each module came from, as the host named it: what a traceback shows.
    pub(crate) module_files: HashMap<String, String>,
    /// Attributes set on a module written in Rust or by the host
    /// (`json.dumps = fake`, `time.time = fake`): they win over its own.
    module_overrides: HashMap<(Rc<str>, Rc<str>), Value>,
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
    pub(crate) active_instance: Option<Value>,
    /// Where the exception being handled was raised and the calls it came
    /// out of, which re-raising it keeps, as Python's traceback does.
    pub(crate) active_trace: Option<Rc<Trace>>,
    /// The functions running now, innermost last, with their scopes: what `super()` reads.
    pub(crate) frames: Vec<(Rc<Function>, Rc<Env>)>,
    /// Modules written in Python that a script may import, by dotted name.
    pub(crate) source_modules: HashMap<String, super::stdlib::ModuleSource>,
    /// Which of those are packages (`pkg/__init__.py`), for relative imports.
    pub(crate) packages: HashSet<String>,
    /// `sys.modules`, `sys.meta_path`, `sys.path` and the other `sys`
    /// containers a script changes in place: made on first read, then kept.
    pub(crate) sys_values: RefCell<HashMap<&'static str, Value>>,
    /// Modules that are objects of their own rather than a scope: what a
    /// `sys.meta_path` loader made, or what a script put in `sys.modules`.
    pub(crate) object_modules: HashMap<String, Value>,
    /// `__notes__` of the interpreter's own exceptions, which are values that
    /// cannot change: what `add_note` gave each, by the exception.
    pub(crate) exception_notes: Vec<(Weak<ExceptionValue>, Value)>,
    /// The ones loaded so far, by dotted name.
    pub(crate) module_envs: HashMap<String, Rc<Env>>,
    /// The module whose top level is running: `__main__`, or one being imported.
    pub(crate) current_module: Rc<str>,
    /// The modules whose top level is running, each with how many function
    /// frames were open when it started: where it sits among [frames].
    pub(crate) module_runs: Vec<(usize, Rc<str>)>,
    pub(crate) repr_depth: u32,
    pub(crate) plain_object: Option<Rc<Class>>,
    /// Instances whose class defines `__hash__`, by the hash they gave: the
    /// first of each set of equal ones stands for all of them as a key.
    pub(crate) hash_buckets: HashMap<i64, Vec<Weak<Instance>>>,
    /// What `print(..., end="")` wrote that no line end has closed yet.
    pub(crate) print_partial: String,
    /// What `sys.setrecursionlimit` set: how deep calls may nest, under the native stack's own limit.
    pub(crate) recursion_limit: u32,
    /// What `sys.set_int_max_str_digits` set: the most digits an int's
    /// decimal text may have, 0 for no limit.
    pub(crate) int_max_str_digits: usize,
    /// What `sys.stdout` and `sys.stderr` were set to, when not the output itself.
    pub(crate) stdout_target: Option<Value>,
    pub(crate) stderr_target: Option<Value>,
    /// `sys.version_info`, one tuple so its fields can be read by name.
    pub(crate) version_info: Rc<Vec<Value>>,
    /// `sys.hash_info`, `sys.int_info` and `sys.float_info`, read by name as
    /// version_info is.
    pub(crate) struct_seqs: Rc<[(&'static str, &'static [&'static str], Rc<Vec<Value>>)]>,
    /// What [ascii_char] hands out, made on first use.
    ascii_chars: Vec<Value>,
    /// Frames of finished calls that nothing kept, for the next calls.
    spare: Vec<Rc<Env>>,
    /// The native stack's address when the running script started, 0 when not known.
    pub(crate) stack_base: usize,
    /// Moves on whenever a class gains or loses a property or a dunder, so
    /// each class's cached lookup traits know to be worked out again.
    pub(crate) class_epoch: u64,
}

impl Drop for Interp {
    fn drop(&mut self) {
        for w in self.closures.drain(..) {
            if let Some(env) = w.upgrade() {
                let slots = std::mem::take(&mut *env.slots.borrow_mut());
                drop(slots);
                env.vars.borrow_mut().clear();
            }
        }
        self.spare.clear();
        for w in self.instances.drain(..) {
            if let Some(i) = w.upgrade() {
                i.clear();
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
        self.module_overrides.clear();
        self.globals_dicts.clear();
        self.eq_depth = 0;
        self.module_envs.insert("__main__".to_string(), self.globals.clone());
        self.hash_buckets.clear();
        self.exception_classes.clear();
        self.frames.clear();
        self.raised = None;
        self.active_instance = None;
        self.active_trace = None;
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
        let globals = main_globals();
        let mut module_envs = HashMap::new();
        // The script itself, as `import __main__` and `sys.modules["__main__"]` reach it.
        module_envs.insert("__main__".to_string(), globals.clone());
        // sys.version_info, which sys.implementation.version is too.
        let version_info = Rc::new(vec![Value::Int(3), Value::Int(13), Value::Int(0), Value::str("final"), Value::Int(0)]);
        Interp {
            globals,
            max_steps: limits.steps,
            steps: 0,
            poll_at: 0,
            current_line: 0,
            printed: Vec::new(),
            on_print: None,
            active_exception: None,
            random_state: modules::random_seed(),
            enabled_modules: enabled,
            host_functions: HashMap::default(),
            host_modules: HashMap::new(),
            max_container: limits.container_items,
            max_string: limits.string_length,
            call_depth: 0,
            cluster_cache: None,
            globals_dicts: Vec::new(),
            eq_depth: 0,
            module_files: HashMap::new(),
            module_overrides: HashMap::new(),
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
            active_trace: None,
            frames: Vec::new(),
            source_modules: HashMap::new(),
            packages: HashSet::new(),
            sys_values: RefCell::new(HashMap::new()),
            object_modules: HashMap::new(),
            exception_notes: Vec::new(),
            module_envs,
            current_module: Rc::from("__main__"),
            module_runs: Vec::new(),
            repr_depth: 0,
            plain_object: None,
            hash_buckets: HashMap::new(),
            print_partial: String::new(),
            recursion_limit: MAX_CALL_DEPTH,
            int_max_str_digits: super::bigint::MAX_STR_DIGITS,
            stdout_target: None,
            stderr_target: None,
            version_info: version_info.clone(),
            struct_seqs: Rc::new([
                (
                    "hash_info",
                    &["width", "modulus", "inf", "nan", "imag", "algorithm", "hash_bits", "seed_bits", "cutoff"][..],
                    Rc::new(vec![
                        Value::Int(64),
                        Value::Int(super::hashing::MODULUS as i64),
                        Value::Int(super::hashing::INF),
                        Value::Int(0),
                        Value::Int(1_000_003),
                        Value::str("siphash13"),
                        Value::Int(64),
                        Value::Int(128),
                        Value::Int(0),
                    ]),
                ),
                (
                    "int_info",
                    &["bits_per_digit", "sizeof_digit", "default_max_str_digits", "str_digits_check_threshold"][..],
                    Rc::new(vec![Value::Int(30), Value::Int(4), Value::Int(4300), Value::Int(640)]),
                ),
                (
                    "float_info",
                    &["max", "max_exp", "max_10_exp", "min", "min_exp", "min_10_exp", "dig", "mant_dig", "epsilon", "radix", "rounds"][..],
                    Rc::new(vec![
                        Value::Float(f64::MAX),
                        Value::Int(1024),
                        Value::Int(308),
                        Value::Float(f64::MIN_POSITIVE),
                        Value::Int(-1021),
                        Value::Int(-307),
                        Value::Int(15),
                        Value::Int(53),
                        Value::Float(f64::EPSILON),
                        Value::Int(2),
                        Value::Int(1),
                    ]),
                ),
                (
                    // What CPython runs with by default: no -O, -I, -X dev or -X utf8.
                    "flags",
                    &[
                        "debug", "inspect", "interactive", "optimize", "dont_write_bytecode", "no_user_site", "no_site",
                        "ignore_environment", "verbose", "bytes_warning", "quiet", "hash_randomization", "isolated", "dev_mode",
                        "utf8_mode", "warn_default_encoding", "safe_path", "int_max_str_digits",
                    ][..],
                    Rc::new(
                        [0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 1]
                            .into_iter()
                            .map(Value::Int)
                            .chain([Value::Bool(false), Value::Int(1), Value::Int(0), Value::Bool(true), Value::Int(4300)])
                            .collect(),
                    ),
                ),
                (
                    // cache_tag None: nothing is compiled to bytecode files.
                    "implementation",
                    &["name", "cache_tag", "version", "hexversion", "_multiarch"][..],
                    Rc::new(vec![
                        Value::str("cpython"),
                        Value::None,
                        // sys.version_info itself, so `.major` reads on it.
                        Value::Tuple(version_info),
                        Value::Int(0x030D_00F0),
                        Value::str("aarch64-linux-android"),
                    ]),
                ),
            ]),
            ascii_chars: Vec::new(),
            spare: Vec::new(),
            stack_base: 0,
            class_epoch: 1,
        }
    }

    /// A one-character string, shared: iterating or indexing ASCII text
    /// hands out these rather than allocating a string per character.
    pub(crate) fn ascii_char(&mut self, b: u8) -> Value {
        if self.ascii_chars.is_empty() {
            self.ascii_chars = (0u8..128).map(|c| Value::str((c as char).encode_utf8(&mut [0; 4]))).collect();
        }
        self.ascii_chars[b as usize & 0x7F].clone()
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
        self.poll_at = 0;
        self.call_depth = 0;
    }

    /// A new step budget, which the next step checks against.
    pub fn set_max_steps(&mut self, steps: u64) {
        self.max_steps = steps;
        self.poll_at = 0;
    }

    pub fn emit(&mut self, line: String) {
        if let Some(f) = self.on_print.as_mut() {
            f(&line);
        }
        self.printed.push(line);
    }

    /// Write out a line `print(..., end="")` left open.
    pub fn flush_print(&mut self) {
        if !self.print_partial.is_empty() {
            let line = std::mem::take(&mut self.print_partial);
            self.emit(line);
        }
    }

    // MARK: - Step budget

    /// One step. Every statement and expression takes one, so this is the
    /// hottest call there is: a counter compare, with the budget, the cancel
    /// flag and the deadline looked at only every [POLL_EVERY] steps.
    #[inline(always)]
    pub fn tick(&mut self) -> Result<(), PyError> {
        self.steps += 1;
        if self.steps < self.poll_at {
            return Ok(());
        }
        self.poll()
    }

    #[cold]
    #[inline(never)]
    fn poll(&mut self) -> Result<(), PyError> {
        if self.steps > self.max_steps {
            return Err(self.abort(AbortKind::Steps, "script exceeded its step budget (possible infinite loop)"));
        }
        if self.cancel.load(AtomicOrdering::Relaxed) {
            return Err(self.abort(AbortKind::Cancelled, "script was cancelled"));
        }
        #[cfg(not(target_arch = "wasm32"))]
        if let Some(d) = self.deadline {
            if std::time::Instant::now() > d {
                return Err(self.abort(AbortKind::Deadline, "script ran out of time"));
            }
        }
        // Past the budget by exactly one step is where it stops.
        self.poll_at = (self.steps + POLL_EVERY).min(self.max_steps.saturating_add(1));
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

    pub(crate) fn exec(&mut self, stmt: &Stmt, env: &Rc<Env>) -> R<()> {
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
            StmtKind::FuncDef(def, bind) => self.exec_def(def, bind, env),
            StmtKind::ClassDef(def, bind) => self.exec_class(def, bind, env),
            StmtKind::With(items, body) => self.exec_with(items, body, env),
            StmtKind::AnnAssign(target, ann, value) => self.exec_ann_assign(target, ann, value.as_ref(), env),
            StmtKind::Assert(test, message) => self.exec_assert(test, message.as_ref(), env),
            // The resolver has already sent each declared name where it belongs.
            StmtKind::Nonlocal(_) | StmtKind::Global(_) => Ok(()),
            StmtKind::Return(value) => Err(self.exec_return(value.as_ref(), env)),
            StmtKind::Break => Err(Exc::Break),
            StmtKind::Continue => Err(Exc::Continue),
            StmtKind::Pass => Ok(()),
            StmtKind::Try(t) => self.exec_try(t, env),
            StmtKind::Raise(value, cause) => Err(self.exec_raise(value.as_ref(), cause.as_ref(), env)),
            StmtKind::ImportStar(module, at) => self.exec_import_star(module, at, env),
            StmtKind::Import { module, target, aliased, names } => {
                self.exec_import(module, target.as_ref(), *aliased, names.as_deref(), env)
            }
            StmtKind::Match(m) => self.exec_match(m, env),
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
        // Numbers, the usual `i += 1`, change nothing in place.
        if let (Value::Int(x), Value::Int(y)) = (&current, &rhs) {
            if let Some(v) = int_quick(op, *x, *y) {
                return self.assign(target, Value::Int(v), env);
            }
        }
        if matches!(current, Value::Int(_) | Value::Float(_) | Value::Bool(_) | Value::Str(_)) {
            let v = self.binary_op(op, &current, &rhs)?;
            return self.assign(target, v, env);
        }
        // `list += iterable` extends in place, as Python does.
        if op == BinOp::Add && matches!(current, Value::List(_)) {
            if let (Value::List(l), Some(items)) = (&current, sequence_items(&rhs)) {
                if l.borrow().len() + items.len() > self.max_container {
                    return Err(self.container_abort("list"));
                }
                l.borrow_mut().extend(items);
                return Ok(());
            }
        }
        // `d |= other` updates the dict in place, and `s |= t` (with `&=`, `-=`
        // and `^=`) changes the set itself, so every name for it sees the change.
        if let (Value::Dict(d), BinOp::BitOr) = (&current, op) {
            let pairs = match &rhs {
                Value::Dict(_) => self.mapping_pairs(&rhs)?,
                Value::Instance(i) if super::methods::is_mapping(i) => self.mapping_pairs(&rhs)?,
                other => self.pairs_of(other, "|=")?,
            };
            let mut m = d.borrow_mut();
            for (k, v) in pairs {
                m.insert(k, v);
            }
            if m.len() > self.max_container {
                drop(m);
                return Err(self.container_abort("dict"));
            }
            return Ok(());
        }
        if let (Value::Set(a), Value::Set(b)) = (&current, &rhs) {
            // Worked out first, so no borrow of either set is held when [a] changes.
            let result = set_algebra(op, &a.borrow(), &b.borrow());
            if let Some(Value::Set(result)) = result {
                let items = std::mem::take(&mut *result.borrow_mut());
                if items.len() > self.max_container {
                    return Err(self.container_abort("set"));
                }
                *a.borrow_mut() = items;
                return Ok(());
            }
        }
        // `x += y` on an object asks its `__iadd__` first, then `+`.
        if let Value::Instance(_) = current {
            let name = super::classes::inplace_method(op);
            if let Some(v) = self.dunder(&current, name, vec![rhs.clone()])? {
                if !matches!(&v, Value::Builtin(n) if n.as_ref() == "NotImplemented") {
                    return self.assign(target, v, env);
                }
            }
        }
        let v = self.binary_op(op, &current, &rhs)?;
        self.assign(target, v, env)
    }

    #[inline(never)]
    fn exec_if(&mut self, branches: &[(Expr, Vec<Stmt>)], else_body: Option<&[Stmt]>, env: &Rc<Env>) -> R<()> {
        for (cond, body) in branches {
            if self.holds(cond, env)? {
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
        // The test runs on the loop's own line, as CPython reports it.
        let line = self.current_line;
        loop {
            self.current_line = line;
            if !self.holds(cond, env)? {
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
        let mut items = self.loop_items(iterable, env)?;
        let line = self.current_line;
        while let Some(item) = {
            self.current_line = line;
            self.next_item(&mut items)?
        } {
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
    fn exec_def(&mut self, def: &Rc<FuncDef>, bind: &Name, env: &Rc<Env>) -> R<()> {
        let mut decorators = Vec::with_capacity(def.decorators.len());
        for d in &def.decorators {
            decorators.push(self.eval(d, env)?);
        }
        let defaults = self.eval_defaults(&def.params, env)?;
        self.track_closure(env);
        let f = Function::new(def.name.clone(), FnBody::Def(def.clone()), env.clone(), defaults);
        let mut value = Value::Function(Rc::new(f));
        for d in decorators.iter().rev() {
            value = self.call_value(d, vec![value], Vec::new())?;
        }
        self.store_name(bind, value, env);
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
        let body = self.exec_block(&stmt.body, env);
        match body {
            // `else` runs outside the handlers: what it raises they never see.
            Ok(()) => {
                if let Some(e) = &stmt.else_body {
                    if let Err(x) = self.exec_block(e, env) {
                        pending = Some(x);
                    }
                }
            }
            Err(Exc::Error(error)) => {
                if error.is_abort() {
                    pending = Some(Exc::Error(error));
                } else {
                    match self.handler_for(&stmt.handlers, &error.kind, env) {
                        Err(e) => pending = Some(e),
                        Ok(Some(i)) => {
                            let h = &stmt.handlers[i];
                            let caught = self.caught_value(&error);
                            let value = match &caught {
                                Value::Exception(e) => e.clone(),
                                _ => Rc::new(ExceptionValue::new(error.kind.clone(), error.message.clone())),
                            };
                            self.raised = None;
                            if let Some(b) = &h.binding {
                                self.store_name(b, caught.clone(), env);
                            }
                            let saved = self.active_exception.replace(value);
                            let saved_instance = self.active_instance.replace(caught);
                            let saved_trace = self.active_trace.replace(Trace::of(&error));
                            if let Err(e) = self.exec_block(&h.body, env) {
                                pending = Some(e);
                            }
                            self.active_exception = saved;
                            self.active_instance = saved_instance;
                            self.active_trace = saved_trace;
                            // As in Python, `as e` is unbound when the handler ends.
                            if let Some(b) = &h.binding {
                                self.unbind(b, env);
                            }
                        }
                        Ok(None) => pending = Some(Exc::Error(error)),
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

    fn exec_raise(&mut self, e: Option<&Expr>, cause: Option<&Expr>, env: &Rc<Env>) -> Exc {
        let Some(e) = e else {
            let raised = if let Some(Value::Instance(inst)) = self.active_instance.clone() {
                self.raise_instance(&inst)
            } else {
                match self.active_exception.clone() {
                    Some(active) => self.raise_exception(active, None),
                    None => return self.error("no active exception to re-raise", "RuntimeError"),
                }
            };
            // Re-raised where it was first raised, with the calls it came out of.
            return match (raised, &self.active_trace) {
                (Exc::Error(mut e), Some(t)) if !e.is_abort() => {
                    t.restore(&mut e);
                    Exc::Error(e)
                }
                (other, _) => other,
            };
        };
        let value = match self.eval(e, env) {
            Ok(v) => v,
            Err(x) => return x,
        };
        // `raise e` with the exception being handled: its traceback goes on,
        // through this line.
        if let (Some(active), Some(t)) = (&self.active_instance, &self.active_trace) {
            if cause.is_none() && py_identical(&value, active) {
                let t = t.clone();
                let raised = self.exec_raise_value(value, None, env);
                return match raised {
                    Exc::Error(mut e) if !e.is_abort() => {
                        let line = self.current_line;
                        t.restore(&mut e);
                        let (function, module) = self.running_function(env);
                        self.note_frame(&mut e, &function, &module, line);
                        Exc::Error(e)
                    }
                    other => other,
                };
            }
        }
        self.exec_raise_value(value, cause, env)
    }

    fn exec_raise_value(&mut self, value: Value, cause: Option<&Expr>, env: &Rc<Env>) -> Exc {
        // `raise … from cause`: the cause is kept on the exception as `__cause__`.
        let cause = match cause {
            Some(c) => match self.eval(c, env) {
                Ok(v) => Some(v),
                Err(x) => return x,
            },
            None => None,
        };
        // Raised while another is being handled: that one is its `__context__`.
        let context = match (&self.active_instance, &self.active_exception) {
            (Some(i), _) => Some(i.clone()),
            (None, Some(e)) => Some(Value::Exception(e.clone())),
            _ => None,
        };
        match value {
            Value::Exception(ex) => {
                let ex = match context {
                    Some(c) if ex.context.is_none() && !py_identical(&c, &Value::Exception(ex.clone())) => {
                        let mut copy = (*ex).clone();
                        copy.context = Some(c);
                        Rc::new(copy)
                    }
                    _ => ex,
                };
                self.raise_exception(ex, cause)
            }
            Value::Str(s) => err(s.to_string(), "Exception", self.current_line),
            Value::Instance(inst) => {
                if inst.class.exception_root.is_some() {
                    if let Some(c) = cause {
                        inst.insert(Rc::from("__cause__"), c);
                    }
                    if let Some(c) = context {
                        if !inst.has("__context__") && !matches!(&c, Value::Instance(o) if Rc::ptr_eq(o, &inst)) {
                            inst.insert(Rc::from("__context__"), c);
                        }
                    }
                }
                self.raise_instance(&inst)
            }
            Value::Class(c) if c.exception_root.is_some() => match self.construct(&c, Vec::new(), Vec::new()) {
                Ok(Value::Instance(inst)) => {
                    if let Some(c) = cause {
                        inst.insert(Rc::from("__cause__"), c);
                    }
                    if let Some(c) = context {
                        inst.insert(Rc::from("__context__"), c);
                    }
                    self.raise_instance(&inst)
                }
                Ok(_) => self.error("exceptions must derive from Exception", "TypeError"),
                Err(x) => x,
            },
            Value::Builtin(kind) if EXCEPTION_KINDS.contains(&kind.as_ref()) => {
                let mut ex = ExceptionValue::new(kind.as_ref(), "");
                ex.context = context;
                self.raise_exception(Rc::new(ex), cause)
            }
            v => self.error(format!("exceptions must be an exception type, not '{}'", v.type_name()), "TypeError"),
        }
    }

    /// Unwind with a built-in exception value, remembered so `except … as e`
    /// gets it back with its args and cause.
    pub(crate) fn raise_exception(&mut self, ex: Rc<ExceptionValue>, cause: Option<Value>) -> Exc {
        let ex = match cause {
            Some(c) => {
                let mut copy = (*ex).clone();
                copy.cause = Some(c);
                Rc::new(copy)
            }
            None => ex,
        };
        self.raised = Some((Rc::from(ex.kind.as_str()), ex.message.clone(), Value::Exception(ex.clone())));
        err(ex.message.clone(), &ex.kind, self.current_line)
    }

    /// A KeyError for [key]: `str(e)` is the key's repr and `e.args` holds the key, as in Python.
    pub(crate) fn key_error(&mut self, key: &Value) -> Exc {
        let message = match self.to_repr(key) {
            Ok(m) => m,
            Err(e) => return e,
        };
        let ex = ExceptionValue { kind: "KeyError".into(), message, args: vec![key.clone()], cause: None, context: None };
        self.raise_exception(Rc::new(ex), None)
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
        target: Option<&Name>,
        aliased: bool,
        names: Option<&[(Rc<str>, Name)]>,
        env: &Rc<Env>,
    ) -> R<()> {
        let module = self.resolve_module(module, self.package_of_env(env))?;
        self.ensure_module(&module)?;
        let Some(names) = names else {
            if let Some(t) = target {
                // `import a.b` binds `a`, through which `a.b` is reached.
                let bound = if aliased { module.as_ref() } else { module.split('.').next().unwrap_or(&module) };
                let value = self.module_value(bound);
                self.store_name(t, value, env);
            }
            return Ok(());
        };
        let object = self.object_modules.get(module.as_ref()).cloned();
        for (name, bind) in names {
            let found = match &object {
                Some(o) => self.optional_attribute(o, name)?,
                None => match self.member(&module, name) {
                    None if modules::python_member(&module, name).is_some() => {
                        Some(self.python_builtin(modules::python_member(&module, name).unwrap_or_default())?)
                    }
                    None => match self.module_getattr(&module, name) {
                        Ok(found) => found,
                        Err(Exc::Error(e)) if !e.is_abort() && e.kind == "AttributeError" => None,
                        Err(e) => return Err(e),
                    },
                    found => found,
                },
            };
            let value = match found {
                Some(v) => v,
                None => {
                    let sub = format!("{module}.{name}");
                    if self.module_exists(&sub) || self.object_modules.contains_key(&sub) {
                        self.ensure_module(&sub)?;
                    } else if !self.import_from_meta_path(&sub)? {
                        return Err(self.error(format!("cannot import name '{name}' from '{module}'"), "ImportError"));
                    }
                    self.module_value(&sub)
                }
            };
            self.store_name(bind, value, env);
        }
        Ok(())
    }

    /// `from module import *`: the names in its `__all__`, or else every
    /// name not starting with an underscore.
    fn exec_import_star(&mut self, module: &Rc<str>, at: &Name, env: &Rc<Env>) -> R<()> {
        let module = self.resolve_module(module, self.package_of_env(env))?;
        self.ensure_module(&module)?;
        if let Some(object) = self.object_modules.get(module.as_ref()).cloned() {
            let listed = match self.optional_attribute(&object, "__all__")? {
                Some(all) => self.iterate(&all)?,
                None => match self.dir_of(&object)? {
                    Value::List(l) => l.borrow().iter().filter(|v| !v.as_str().is_some_and(|s| s.starts_with('_'))).cloned().collect(),
                    _ => Vec::new(),
                },
            };
            for n in listed {
                let Value::Str(n) = n else { continue };
                let v = self.attribute(&object, &n)?;
                let mut bind = Name::new(n);
                bind.at = at.at;
                self.store_name(&bind, v, env);
            }
            return Ok(());
        }
        let names: Vec<Rc<str>> = if let Some(m) = self.host_modules.get(module.as_ref()) {
            m.names().into_iter().filter(|n| !n.starts_with('_')).map(Rc::from).collect()
        } else if let Some(menv) = self.module_envs.get(module.as_ref()).cloned() {
            let all = menv.vars.borrow().get("__all__").cloned();
            match all {
                Some(listed) => {
                    let mut out = Vec::new();
                    for v in self.iterate(&listed)? {
                        match v {
                            Value::Str(s) => out.push(s),
                            other => {
                                return Err(self.error(
                                    format!("Item in {module}.__all__ must be str, not {}", other.type_name()),
                                    "TypeError",
                                ))
                            }
                        }
                    }
                    out
                }
                None => menv.vars.borrow().pairs().into_iter().map(|(k, _)| k).filter(|k| !k.starts_with('_')).collect(),
            }
        } else {
            modules::exports(&module).into_iter().map(Rc::from).collect()
        };
        for n in names {
            let v = match self.member(&module, &n) {
                Some(v) => v,
                None => match self.module_getattr(&module, &n)? {
                    Some(v) => v,
                    None => return Err(self.error(format!("module '{module}' has no attribute '{n}'"), "AttributeError")),
                },
            };
            let mut bind = Name::new(n);
            bind.at = at.at;
            self.store_name(&bind, v, env);
        }
        Ok(())
    }

    /// PEP 562: a module written in Python answers a name it lacks through
    /// its own `__getattr__` (typing's `ContextManager`, a lazy package's
    /// submodules). None when the module has no such function.
    pub(crate) fn module_getattr(&mut self, module: &str, name: &str) -> R<Option<Value>> {
        let hook = match self.module_envs.get(module) {
            Some(env) => env.vars.borrow().get("__getattr__").cloned(),
            None => None,
        };
        match hook {
            Some(f) => self.call_value(&f, vec![Value::str(name)], Vec::new()).map(Some),
            None => Ok(None),
        }
    }

    /// `module.name`, looking at the host's modules, then modules written in
    /// Python, then the standard ones.
    pub fn member(&self, module: &str, name: &str) -> Option<Value> {
        if let Some(v) = self.module_override(module, name) {
            return Some(v);
        }
        if module == "sys" {
            if let Some(v) = self.sys_member(name) {
                return Some(v);
            }
        }
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
            Expr::Name(n) => self.delete_name(n, env)?,
            Expr::Attribute(obj, name) => {
                let o = self.eval(obj, env)?;
                self.del_attr(&o, name)?;
            }
            Expr::Index(base, idx) => {
                let base = self.eval(base, env)?;
                let idx = self.eval(idx, env)?;
                self.delete_item(&base, &idx)?;
            }
            Expr::Slice(sl) => {
                let base = self.eval(&sl.0, env)?;
                let idx = self.slice_value(&sl.1, &sl.2, &sl.3, env)?;
                self.delete_item(&base, &idx)?;
            }
            _ => return Err(self.error("cannot delete this expression", "SyntaxError")),
        }
        Ok(())
    }

    /// `del base[idx]`, where [idx] may be a slice object.
    pub(crate) fn delete_item(&mut self, base: &Value, idx: &Value) -> R<()> {
        match base {
            Value::Instance(_) => {
                if self.dunder(base, "__delitem__", vec![idx.clone()])?.is_none() {
                    return Err(self.error(format!("'{}' object does not support item deletion", base.type_name()), "TypeError"));
                }
            }
            Value::List(l) => {
                if let Value::Object(o) = idx {
                    if let Object::Slice(a, b, c) = o.as_ref() {
                        let n = l.borrow().len();
                        let picked = self.slice_positions(n, a, b, c)?;
                        let mut items = l.borrow_mut();
                        for i in picked.into_iter().rev() {
                            items.remove(i);
                        }
                        return Ok(());
                    }
                }
                let n = l.borrow().len();
                let i = self.list_index(idx, n)?;
                l.borrow_mut().remove(i);
            }
            Value::Dict(d) => {
                let key = self.hashable(idx)?;
                if d.borrow_mut().shift_remove(&key).is_none() {
                    return Err(self.key_error(idx));
                }
            }
            _ => return Err(self.error(format!("cannot delete from '{}'", base.type_name()), "TypeError")),
        }
        Ok(())
    }

    /// The key [v] is under in a dict or set. An instance whose class defines
    /// `__hash__` hashes by it, and equal instances (by `__eq__`) are one key.
    pub fn hashable(&mut self, v: &Value) -> R<HKey> {
        match v {
            Value::Instance(inst) => self.instance_key(inst, v),
            Value::Tuple(items) if items.iter().any(|i| matches!(i, Value::Instance(_) | Value::Tuple(_))) => {
                let mut parts = Vec::with_capacity(items.len());
                for i in items.iter() {
                    parts.push(self.hashable(i)?);
                }
                Ok(HKey::Tuple(Rc::new(parts)))
            }
            _ => HKey::from_value(v).ok_or_else(|| self.error(format!("unhashable type: '{}'", v.type_name()), "TypeError")),
        }
    }

    fn instance_key(&mut self, inst: &Rc<Instance>, v: &Value) -> R<HKey> {
        let Some(method) = inst.class.lookup("__hash__") else {
            // A str, int or tuple subclass is the key its value is, read back as itself.
            if let Some(p) = inst.payload() {
                if matches!(p, Value::List(_) | Value::Dict(_) | Value::Set(_)) {
                    return Err(self.error(format!("unhashable type: '{}'", inst.class.name), "TypeError"));
                }
                let p = p.clone();
                let key = self.hashable(&p)?;
                return Ok(HKey::Sub(Rc::new((key, v.clone()))));
            }
            return Ok(HKey::Ref(v.clone()));
        };
        if method.is_none() {
            return Err(self.error(format!("unhashable type: '{}'", inst.class.name), "TypeError"));
        }
        let bound = self.special_bound(method, v, &inst.class)?;
        let hash = match self.call_value(&bound, Vec::new(), Vec::new())? {
            Value::Int(i) => i,
            Value::Bool(b) => b as i64,
            _ => return Err(self.error("__hash__ method should return an integer", "TypeError")),
        };
        let mut candidates = self.hash_buckets.get(&hash).cloned().unwrap_or_default();
        candidates.retain(|w| w.strong_count() > 0);
        for w in &candidates {
            let Some(other) = w.upgrade() else { continue };
            self.tick()?;
            let other = Value::Instance(other);
            if py_identical(&other, v) || self.eq(&other, v)? {
                return Ok(HKey::Obj { hash, canon: other, value: v.clone() });
            }
        }
        candidates.push(Rc::downgrade(inst));
        self.hash_buckets.insert(hash, candidates);
        Ok(HKey::Obj { hash, canon: v.clone(), value: v.clone() })
    }

    /// `hash(v)`: an instance's own `__hash__` answer; for numbers and
    /// tuples CPython's own hash, so `hash(2)` is 2; a hash of its key else.
    pub(crate) fn hash_of(&mut self, v: &Value) -> R<i64> {
        use std::hash::{Hash, Hasher};
        match v {
            Value::Int(i) => return Ok(super::hashing::of_int(*i)),
            Value::Bool(b) => return Ok(*b as i64),
            Value::Float(f) => return Ok(super::hashing::of_float(*f)),
            Value::Complex(re, im) => return Ok(super::complex::hash(*re, *im)),
            Value::Big(b) => return Ok(super::hashing::of_big(b)),
            Value::None => return Ok(super::hashing::NONE),
            Value::Tuple(items) => {
                let mut lanes = Vec::with_capacity(items.len());
                for item in items.iter() {
                    lanes.push(self.hash_of(item)?);
                }
                return Ok(super::hashing::of_tuple(&lanes));
            }
            _ => {}
        }
        match self.hashable(v)? {
            HKey::Obj { hash, .. } => Ok(hash),
            // A subclass of a built-in type hashes as its value does.
            HKey::Sub(p) => {
                let plain = p.0.to_value();
                self.hash_of(&plain)
            }
            key => {
                let mut h = std::collections::hash_map::DefaultHasher::new();
                key.hash(&mut h);
                Ok((h.finish() >> 1) as i64)
            }
        }
    }

    /// The (key, value) pairs of an iterable of pairs, for `dict(pairs)` and `update(pairs)`.
    pub(crate) fn pairs_of(&mut self, v: &Value, what: &str) -> R<Vec<(HKey, Value)>> {
        let mut out = Vec::new();
        for pair in self.iterate(v)? {
            let items = match sequence_items(&pair) {
                Some(items) if items.len() == 2 => items,
                _ => return Err(self.error(format!("{what} needs (key, value) pairs"), "ValueError")),
            };
            let key = self.hashable(&items[0])?;
            out.push((key, items[1].clone()));
        }
        Ok(out)
    }

    // MARK: - Assignment

    pub(crate) fn assign(&mut self, target: &Expr, value: Value, env: &Rc<Env>) -> R<()> {
        match target {
            Expr::Name(n) => self.store_name(n, value, env),
            Expr::Attribute(obj, name) => {
                let o = self.eval(obj, env)?;
                self.set_attr(&o, name, value)?;
            }
            Expr::Tuple(items) | Expr::List(items) => {
                let parts = self.iterate(&value)?;
                let starred = items.iter().filter(|t| matches!(t, Expr::Starred(_))).count();
                if starred > 1 {
                    return Err(self.error("multiple starred expressions in assignment", "SyntaxError"));
                }
                if starred == 0 {
                    if parts.len() != items.len() {
                        return Err(self.error(
                            format!("cannot unpack {} values into {} targets", parts.len(), items.len()),
                            "ValueError",
                        ));
                    }
                    for (t, p) in items.iter().zip(parts) {
                        self.assign(t, p, env)?;
                    }
                    return Ok(());
                }
                // `first, *rest = items`: the starred target takes what the others leave, as a list.
                let at = items.iter().position(|t| matches!(t, Expr::Starred(_))).unwrap();
                let after = items.len() - at - 1;
                if parts.len() < items.len() - 1 {
                    return Err(self.error(
                        format!("not enough values to unpack (expected at least {}, got {})", items.len() - 1, parts.len()),
                        "ValueError",
                    ));
                }
                let mut parts = parts;
                let tail = parts.split_off(parts.len() - after);
                let middle = parts.split_off(at);
                for (t, p) in items[..at].iter().zip(parts) {
                    self.assign(t, p, env)?;
                }
                if let Expr::Starred(inner) = &items[at] {
                    self.assign(inner, Value::list(middle), env)?;
                }
                for (t, p) in items[at + 1..].iter().zip(tail) {
                    self.assign(t, p, env)?;
                }
            }
            Expr::Index(base, idx) => {
                let base = self.eval(base, env)?;
                let idx = self.eval(idx, env)?;
                self.set_item(&base, &idx, value)?;
            }
            Expr::Slice(sl) => {
                let base = self.eval(&sl.0, env)?;
                let idx = self.slice_value(&sl.1, &sl.2, &sl.3, env)?;
                self.set_item(&base, &idx, value)?;
            }
            Expr::Starred(_) => return Err(self.error("starred assignment target must be in a list or tuple", "SyntaxError")),
            _ => return Err(self.error("cannot assign to this expression", "SyntaxError")),
        }
        Ok(())
    }

    /// `base[idx] = value`, where [idx] may be a slice object.
    pub(crate) fn set_item(&mut self, base: &Value, idx: &Value, value: Value) -> R<()> {
        match base {
            Value::List(l) => {
                if let Value::Object(o) = idx {
                    if let Object::Slice(a, b, c) = o.as_ref() {
                        return self.assign_list_slice(l, a, b, c, value);
                    }
                }
                let n = l.borrow().len();
                let i = self.seq_index(idx, n, "list assignment")?;
                l.borrow_mut()[i] = value;
            }
            Value::Dict(d) => {
                let key = self.hashable(idx)?;
                d.borrow_mut().insert(key, value);
            }
            Value::Instance(_) if self.dunder(base, "__setitem__", vec![idx.clone(), value.clone()])?.is_some() => {}
            _ => {
                return Err(self.error(format!("'{}' object does not support item assignment", base.type_name()), "TypeError"))
            }
        }
        Ok(())
    }

    /// `items[a:b] = values` replaces that run, growing or shrinking the list;
    /// with a step, `items[::2] = values` needs exactly as many values as places.
    fn assign_list_slice(&mut self, l: &ListRef, a: &Value, b: &Value, c: &Value, value: Value) -> R<()> {
        let new_items = self.iterate(&value)?;
        let n = l.borrow().len();
        let step = self.slice_step(c)?;
        if step == 1 {
            let (lo, hi) = (self.slice_bound(a)?, self.slice_bound(b)?);
            let clamp = |i: Option<i64>, default: i64| match i {
                None => default,
                Some(i) if i < 0 => (i + n as i64).max(0),
                Some(i) => i.min(n as i64),
            };
            let start = clamp(lo, 0) as usize;
            let stop = (clamp(hi, n as i64) as usize).max(start);
            if n - (stop - start) + new_items.len() > self.max_container {
                return Err(self.container_abort("list"));
            }
            l.borrow_mut().splice(start..stop, new_items);
            return Ok(());
        }
        let picked = self.slice_positions(n, a, b, c)?;
        if picked.len() != new_items.len() {
            return Err(self.error(
                format!("attempt to assign sequence of size {} to extended slice of size {}", new_items.len(), picked.len()),
                "ValueError",
            ));
        }
        let mut items = l.borrow_mut();
        for (i, v) in picked.into_iter().zip(new_items) {
            items[i] = v;
        }
        Ok(())
    }

    /// `base[lo:hi:step]` evaluated into a slice object.
    fn slice_value(&mut self, lo: &Option<Expr>, hi: &Option<Expr>, st: &Option<Expr>, env: &Rc<Env>) -> R<Value> {
        let mut parts = [Value::None, Value::None, Value::None];
        for (slot, e) in parts.iter_mut().zip([lo, hi, st]) {
            if let Some(e) = e {
                *slot = self.eval(e, env)?;
            }
        }
        let [a, b, c] = parts;
        Ok(Value::Object(Rc::new(Object::Slice(a, b, c))))
    }

    fn slice_bound(&self, v: &Value) -> R<Option<i64>> {
        match v {
            Value::None => Ok(None),
            other => match other.as_int() {
                Some(i) => Ok(Some(i)),
                None => Err(self.error("slice indices must be integers or None", "TypeError")),
            },
        }
    }

    fn slice_step(&self, v: &Value) -> R<i64> {
        let step = self.slice_bound(v)?.unwrap_or(1);
        if step == 0 {
            return Err(self.error("slice step cannot be zero", "ValueError"));
        }
        Ok(step)
    }

    /// The positions a slice picks out of [n] items, in slice order.
    pub(crate) fn slice_positions(&self, n: usize, a: &Value, b: &Value, c: &Value) -> R<Vec<usize>> {
        let step = self.slice_step(c)?;
        Ok(slice_indices(n as i64, self.slice_bound(a)?, self.slice_bound(b)?, step))
    }

    // MARK: - Expressions

    pub fn eval(&mut self, expr: &Expr, env: &Rc<Env>) -> R<Value> {
        self.tick()?;
        match expr {
            Expr::None => Ok(Value::None),
            Expr::Bool(b) => Ok(Value::Bool(*b)),
            Expr::Int(i) => Ok(Value::Int(*i)),
            Expr::Big(b) => Ok(Value::Big(b.clone())),
            Expr::Imag(v) => Ok(Value::Complex(0.0, *v)),
            Expr::Float(f) => Ok(Value::Float(*f)),
            Expr::Str(s) => Ok(Value::Str(s.clone())),
            Expr::Bytes(b) => Ok(Value::Bytes(b.clone())),
            Expr::FString(parts) => self.eval_fstring(parts, env),
            Expr::Name(n) => {
                // A function's own local, the commonest name, without a call.
                if let NameAt::Local(slot) = n.at {
                    if let Some(Some(v)) = env.slots.borrow().get(slot as usize) {
                        return Ok(v.clone());
                    }
                }
                self.load_name(n, env)
            }
            Expr::List(items) => self.eval_items(items, env).map(Value::list),
            Expr::Tuple(items) => self.eval_items(items, env).map(Value::tuple),
            Expr::Dict(pairs) => self.eval_dict(pairs, env),
            Expr::Set(items) => self.eval_set(items, env),
            Expr::Unary(op, operand) => self.eval_unary(*op, operand, env),
            Expr::Binary(op, l, r) => self.eval_binary(*op, l, r, env),
            Expr::BoolOp(kind, l, r) => self.eval_bool_op(*kind, l, r, env),
            Expr::Compare(left, pairs) => self.eval_compare(left, pairs, env),
            Expr::Ternary(t) => {
                if self.holds(&t.0, env)? {
                    self.eval(&t.1, env)
                } else {
                    self.eval(&t.2, env)
                }
            }
            Expr::Index(base, idx) => self.eval_index(base, idx, env),
            Expr::Slice(s) => self.eval_slice(s, env),
            Expr::Attribute(obj, name) => self.eval_attribute(obj, name, env),
            Expr::Lambda(def) => self.eval_lambda(def, env),
            Expr::Comprehension(c) => self.run_comprehension(c, env),
            Expr::Starred(_) => Err(self.error("'*' can only be used in a call's arguments", "SyntaxError")),
            Expr::Call(callee, args, kwargs) => self.eval_call(callee, args, kwargs, env),
            Expr::Walrus(w) => {
                let v = self.eval(&w.1, env)?;
                self.store_name(&w.0, v.clone(), env);
                Ok(v)
            }
            // A generator's own statements run these; the resolver lets them stand nowhere else.
            Expr::Yield(_) | Expr::YieldFrom(_) => Err(self.error("'yield' outside a generator", "SyntaxError")),
            Expr::Invalid(kind, message) => Err(self.invalid_literal(kind, message)),
            Expr::Await(e) => self.eval_await(e, env),
        }
    }

    /// Out of line, so `eval` (on the stack once per nested call) stays small.
    #[inline(never)]
    fn eval_await(&mut self, e: &Expr, env: &Rc<Env>) -> R<Value> {
        let v = self.eval(e, env)?;
        self.await_value(v)
    }

    #[inline(never)]
    fn invalid_literal(&mut self, kind: &str, message: &str) -> Exc {
        self.error(message.to_string(), kind)
    }

    #[inline(never)]
    fn eval_fstring(&mut self, parts: &[FPart], env: &Rc<Env>) -> R<Value> {
        Ok(Value::str(self.interpolate(parts, env)?))
    }

    // MARK: - Names

    /// A name's value, from where the resolver said it lives.
    #[inline]
    pub(crate) fn load_name(&mut self, n: &Name, env: &Rc<Env>) -> R<Value> {
        match n.at {
            NameAt::Local(slot) => {
                if let Some(Some(v)) = env.slots.borrow().get(slot as usize) {
                    return Ok(v.clone());
                }
                Err(self.unbound(n, false))
            }
            NameAt::Free(depth, slot) => {
                if let Some(Some(v)) = env.up(depth).slots.borrow().get(slot as usize) {
                    return Ok(v.clone());
                }
                Err(self.unbound(n, true))
            }
            NameAt::Global => self.load_global(n, env),
            NameAt::Class(free) => {
                if let Some(v) = env.vars.borrow().get(&n.id) {
                    return Ok(v.clone());
                }
                match free {
                    Some((depth, slot)) => match env.up(depth).slots.borrow().get(slot as usize) {
                        Some(Some(v)) => Ok(v.clone()),
                        _ => Err(self.unbound(n, true)),
                    },
                    None => self.load_global(n, env),
                }
            }
        }
    }

    #[cold]
    fn unbound(&self, n: &Name, free: bool) -> Exc {
        if free {
            return self.error(
                format!("cannot access free variable '{}' where it is not associated with a value in enclosing scope", n.id),
                "NameError",
            );
        }
        self.error(format!("cannot access local variable '{}' where it is not associated with a value", n.id), "UnboundLocalError")
    }

    /// The module's global [n], or else the builtin of that name.
    #[inline(never)]
    fn load_global(&mut self, n: &Name, env: &Rc<Env>) -> R<Value> {
        if let Some(v) = env.module().vars.borrow().get_hinted(&n.id, &n.hint) {
            return Ok(v.clone());
        }
        if let Some(v) = self.globals_dict_get(env.module(), &n.id) {
            return Ok(v);
        }
        self.load_builtin(&n.id)
    }

    /// What a script set [module].[name] to, for a module not written in Python.
    pub(crate) fn module_override(&self, module: &str, name: &str) -> Option<Value> {
        if self.module_overrides.is_empty() {
            return None;
        }
        self.module_overrides.get(&(Rc::from(module), Rc::from(name))).cloned()
    }

    pub(crate) fn set_module_override(&mut self, module: &Rc<str>, name: &Rc<str>, value: Option<Value>) -> bool {
        let key = (module.clone(), name.clone());
        // Putting back the module's own function (what a patch does when it
        // ends) drops the override, which would otherwise call itself.
        let own = |v: &Value| matches!(v, Value::ModuleFunction(mf) if mf.0.as_ref() == module.as_ref() && mf.1.as_ref() == name.as_ref());
        let value = value.filter(|v| !own(v) && !matches!(v, Value::Builtin(b) if module.as_ref() == "builtins" && b == name));
        if value.is_none() && !self.module_overrides.contains_key(&key) {
            return false;
        }
        match value {
            Some(v) => {
                self.module_overrides.insert(key, v);
                true
            }
            None => self.module_overrides.remove(&key).is_some(),
        }
    }

    /// The one `globals()` dict of the module scope [root], brought up to date.
    pub(crate) fn globals_dict_of(&mut self, root: &Rc<Env>) -> DictRef {
        self.globals_dicts.retain(|(w, _)| w.strong_count() > 0);
        let dict = match self.globals_dicts.iter().find(|(w, _)| std::ptr::eq(w.as_ptr(), Rc::as_ptr(root))) {
            Some((_, d)) => d.clone(),
            None => {
                let d: DictRef = Rc::new(RefCell::new(DictMap::default()));
                self.globals_dicts.push((Rc::downgrade(root), d.clone()));
                d
            }
        };
        let module = root.vars.borrow().pairs();
        {
            let mut map = dict.borrow_mut();
            for (k, v) in module {
                map.insert(HKey::str(&k), v);
            }
        }
        dict
    }

    /// `module.__dict__`: the module's globals for one written in Python,
    /// a snapshot of its members for any other.
    fn module_dict(&mut self, m: &Rc<str>) -> R<Value> {
        if let Some(env) = self.module_envs.get(m.as_ref()).cloned() {
            return Ok(Value::Dict(self.globals_dict_of(&env)));
        }
        let names = match self.dir_of(&Value::Module(m.clone()))? {
            Value::List(l) => l.borrow().clone(),
            _ => Vec::new(),
        };
        let mut pairs = Vec::with_capacity(names.len());
        for n in names {
            if let Value::Str(n) = n {
                if let Some(v) = self.member(m, &n) {
                    pairs.push((n, v));
                }
            }
        }
        Ok(Value::dict(pairs))
    }

    /// `__spec__` of a module written in Rust: origin "built-in", as CPython
    /// gives its built-in modules, made once and kept.
    fn native_spec(&mut self, m: &Rc<str>) -> R<Value> {
        let class = self.python_builtin("ModuleSpec")?;
        // json is a package in CPython.
        let kwargs = vec![(Rc::from("origin"), Value::str("built-in")), (Rc::from("is_package"), Value::Bool(m.as_ref() == "json"))];
        let saved = self.current_line;
        let spec = self.call_value(&class, vec![Value::Str(m.clone()), Value::None], kwargs)?;
        self.current_line = saved;
        self.module_overrides.insert((m.clone(), Rc::from("__spec__")), spec.clone());
        Ok(spec)
    }

    /// The `__notes__` list `add_note` made for a built-in exception, if it did.
    pub(crate) fn notes_of(&self, e: &Rc<ExceptionValue>) -> Option<Value> {
        self.exception_notes.iter().find(|(w, _)| std::ptr::eq(w.as_ptr(), Rc::as_ptr(e))).map(|(_, n)| n.clone())
    }

    /// [name] as set through `globals()` of the module [module], if it was.
    pub(crate) fn globals_dict_get(&self, module: &Env, name: &str) -> Option<Value> {
        if self.globals_dicts.is_empty() {
            return None;
        }
        let found = self.globals_dicts.iter().find(|(w, _)| std::ptr::eq(w.as_ptr(), module) && w.strong_count() > 0);
        found.and_then(|(_, d)| d.borrow().get(&StrKey(name)).cloned())
    }

    fn load_builtin(&mut self, n: &Rc<str>) -> R<Value> {
        // `builtins.open = fake`, as `unittest.mock.patch("builtins.open")` does.
        if let Some(v) = self.module_override("builtins", n) {
            return Ok(v);
        }
        if let Some(f) = self.host_functions.get(n.as_ref()) {
            return Ok(Value::HostFunction(f.clone()));
        }
        // Old names Python keeps for OSError.
        if matches!(n.as_ref(), "IOError" | "EnvironmentError") {
            return Ok(Value::Builtin(Rc::from("OSError")));
        }
        // True as CPython runs without -O: asserts are checked.
        if n.as_ref() == "__debug__" {
            return Ok(Value::Bool(true));
        }
        if is_builtin_name(n) {
            return Ok(Value::Builtin(n.clone()));
        }
        if super::builtins::PYTHON_BUILTINS.contains(&n.as_ref()) {
            return self.python_builtin(n);
        }
        Err(self.error(format!("name '{n}' is not defined"), "NameError"))
    }

    /// Bind [n] to [value] where the resolver said it lives.
    #[inline]
    pub(crate) fn store_name(&mut self, n: &Name, value: Value, env: &Rc<Env>) {
        let old = match n.at {
            NameAt::Local(slot) => env.slots.borrow_mut().get_mut(slot as usize).and_then(|s| s.replace(value)),
            NameAt::Free(depth, slot) => env.up(depth).slots.borrow_mut().get_mut(slot as usize).and_then(|s| s.replace(value)),
            NameAt::Global => env.module().vars.borrow_mut().insert_hinted(&n.id, value, &n.hint),
            NameAt::Class(_) => {
                env.vars.borrow_mut().insert(n.id.clone(), value);
                None
            }
        };
        // Dropped once no scope is borrowed.
        drop(old);
    }

    /// Unbind [n], saying whether it was bound.
    pub(crate) fn unbind(&mut self, n: &Name, env: &Rc<Env>) -> bool {
        let old = match n.at {
            NameAt::Local(slot) => env.slots.borrow_mut().get_mut(slot as usize).and_then(Option::take),
            NameAt::Free(depth, slot) => env.up(depth).slots.borrow_mut().get_mut(slot as usize).and_then(Option::take),
            NameAt::Global => env.module().vars.borrow_mut().shift_remove(&n.id),
            NameAt::Class(_) => env.vars.borrow_mut().shift_remove(&n.id),
        };
        old.is_some()
    }

    /// `del name`.
    fn delete_name(&mut self, n: &Name, env: &Rc<Env>) -> R<()> {
        if self.unbind(n, env) {
            return Ok(());
        }
        Err(match n.at {
            NameAt::Local(_) => self.unbound(n, false),
            NameAt::Free(..) => self.unbound(n, true),
            _ => self.error(format!("name '{}' is not defined", n.id), "NameError"),
        })
    }

    #[inline(never)]
    pub(crate) fn eval_items(&mut self, items: &[Expr], env: &Rc<Env>) -> R<Vec<Value>> {
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
        let mut d = DictMap::default();
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
        let mut s = KeySet::default();
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
        if let (Value::Int(x), Value::Int(y)) = (&a, &b) {
            if let Some(v) = int_quick(op, *x, *y) {
                return Ok(Value::Int(v));
            }
        }
        self.binary_op(op, &a, &b)
    }

    /// Whether [e] holds, as `if`, `while` and comprehension filters ask: a
    /// lone comparison answers without a bool value in between.
    #[inline]
    pub(crate) fn holds(&mut self, e: &Expr, env: &Rc<Env>) -> R<bool> {
        if let Expr::Compare(left, pairs) = e {
            if let [(op, r)] = pairs.as_slice() {
                self.tick()?;
                let a = self.eval(left, env)?;
                let b = self.eval(r, env)?;
                return self.compare_pair(*op, &a, &b);
            }
        }
        match self.eval(e, env)? {
            Value::Bool(b) => Ok(b),
            c => self.truth(&c),
        }
    }

    /// One comparison, two ints straight away.
    #[inline]
    fn compare_pair(&mut self, op: CmpOp, a: &Value, b: &Value) -> R<bool> {
        if let (Value::Int(x), Value::Int(y)) = (a, b) {
            match op {
                CmpOp::Lt => return Ok(x < y),
                CmpOp::Le => return Ok(x <= y),
                CmpOp::Gt => return Ok(x > y),
                CmpOp::Ge => return Ok(x >= y),
                CmpOp::Eq => return Ok(x == y),
                CmpOp::Ne => return Ok(x != y),
                _ => {}
            }
        }
        self.compare_op(op, a, b)
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
        if let [(op, r)] = pairs {
            let a = self.eval(left, env)?;
            let b = self.eval(r, env)?;
            return self.compare_pair(*op, &a, &b).map(Value::Bool);
        }
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
        if let Value::Instance(_) = base {
            let idx = self.slice_value(&s.1, &s.2, &s.3, env)?;
            return self.index(&base, &idx);
        }
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
    fn eval_lambda(&mut self, def: &Rc<LambdaDef>, env: &Rc<Env>) -> R<Value> {
        let defaults = self.eval_defaults(&def.params, env)?;
        self.track_closure(env);
        Ok(Value::Function(Rc::new(Function::new(
            Rc::from("<lambda>"),
            FnBody::Lambda(def.clone(), self.current_line),
            env.clone(),
            defaults,
        ))))
    }

    /// Each parameter's default, evaluated now in [env]: positional ones,
    /// then keyword-only ones, None where there is none.
    fn eval_defaults(&mut self, params: &Params, env: &Rc<Env>) -> R<Vec<Option<Value>>> {
        let mut out = Vec::with_capacity(params.positional.len() + params.keyword_only.len());
        for p in params.positional.iter().chain(params.keyword_only.iter()) {
            out.push(match &p.default {
                Some(e) => Some(self.eval(e, env)?),
                None => None,
            });
        }
        Ok(out)
    }

    #[inline(never)]
    fn eval_call(&mut self, callee: &Expr, args: &[Expr], kwargs: &[(Rc<str>, Expr)], env: &Rc<Env>) -> R<Value> {
        // Python evaluates what is called first, then the arguments.
        let plain = kwargs.is_empty() && !args.iter().any(|a| matches!(a, Expr::Starred(_)));
        if let Expr::Attribute(obj, name) = callee {
            let o = self.eval(obj, env)?;
            // `obj.method(...)` dispatches directly, with no bound-method value in between.
            if plain {
                if let Value::Instance(inst) = &o {
                    if let Some(f) = plain_method(inst, name) {
                        return self.call_fast(&f, Some(o.clone()), args, env);
                    }
                }
            }
            let (a, kw) = self.eval_args(args, kwargs, env)?;
            return self.call_method(&o, name, a, kw);
        }
        let c = self.eval(callee, env)?;
        if plain {
            match &c {
                Value::Function(f) if f.signature().simple() => return self.call_fast(f, None, args, env),
                // `eval` and `exec` see the scope they are called from.
                Value::Builtin(name) if matches!(name.as_ref(), "eval" | "exec") => {
                    let (a, _) = self.eval_args(args, kwargs, env)?;
                    return self.eval_exec(name, &a, Some(env));
                }
                // So do `globals()`, `locals()`, `vars()` and `dir()` with nothing to look at.
                Value::Builtin(name) if args.is_empty() && matches!(name.as_ref(), "globals" | "locals" | "vars" | "dir") => {
                    return self.scope_builtin(name, env);
                }
                // A builtin reads its arguments where they lie: no list is made for them.
                Value::Builtin(name) if args.len() <= 4 => {
                    let mut buf = [Value::None, Value::None, Value::None, Value::None];
                    for (slot, a) in buf.iter_mut().zip(args) {
                        *slot = self.eval(a, env)?;
                    }
                    return self.call_builtin(name, &buf[..args.len()], &[]);
                }
                _ => {}
            }
        }
        let (a, kw) = self.eval_args(args, kwargs, env)?;
        self.call_value(&c, a, kw)
    }

    /// `globals()`, `locals()` (and `vars()`), and `dir()` of the scope [env].
    /// `locals()` is a copy; `globals()` is one dict per module, brought up
    /// to date each call, and a name set in it is a global from then on.
    pub(crate) fn scope_builtin(&mut self, name: &str, env: &Rc<Env>) -> R<Value> {
        if name == "globals" {
            let mut root = env.clone();
            while let Some(p) = root.parent.clone() {
                root = p;
            }
            return Ok(Value::Dict(self.globals_dict_of(&root)));
        }
        let module = env.module().vars.borrow().pairs();
        let pairs: Vec<(Rc<str>, Value)> = match name {
            "globals" => module,
            _ => {
                // A function's own frame: its slots, named by the function that made it.
                let frame = self.frames.last().filter(|(_, e)| Rc::ptr_eq(e, env)).map(|(f, _)| f.clone());
                match frame {
                    Some(f) => {
                        let slots = env.slots.borrow();
                        f.frame_info()
                            .names
                            .iter()
                            .zip(slots.iter())
                            .filter_map(|(n, v)| v.clone().map(|v| (n.clone(), v)))
                            .collect()
                    }
                    None if env.parent.is_some() => env.vars.borrow().pairs(),
                    None => module,
                }
            }
        };
        if name == "dir" {
            let mut names: Vec<Value> = pairs.into_iter().map(|(k, _)| Value::Str(k)).collect();
            names.sort_by_key(py_str);
            return Ok(Value::list(names));
        }
        Ok(Value::dict(pairs))
    }

    /// A call's arguments, with `*seq` and `**mapping` spread.
    fn eval_args(&mut self, args: &[Expr], kwargs: &[(Rc<str>, Expr)], env: &Rc<Env>) -> R<(Vec<Value>, Keywords)> {
        // One spare slot: a method call puts its receiver in front.
        let mut a = Vec::with_capacity(args.len() + 1);
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
                    let HKey::Str(name) = hk.base().clone() else {
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
        Ok((a, kw))
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
                        (Some(FConversion::Ascii), _) => Value::str(ascii_escape(&self.to_repr(&value)?)),
                        // An object with no `__format__` formats as its str().
                        (_, Value::Instance(i)) if spec.is_some() && i.payload().is_none() && !i.class.defines("__format__") => {
                            Value::str(self.to_str(&value)?)
                        }
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
                        // A string or an int goes in as it is, with no string made for it.
                        None => match &converted {
                            Value::Str(s) => out.push_str(s),
                            Value::Int(i) => {
                                use std::fmt::Write;
                                let _ = write!(out, "{i}");
                            }
                            other => out.push_str(&self.to_str(other)?),
                        },
                    }
                }
            }
            if longer_than(&out, self.max_string) {
                return Err(self.string_abort());
            }
        }
        Ok(out)
    }

    /// Comprehensions get their own scope, so the loop variable doesn't leak
    /// (the Python 3 rule). The first iterable is evaluated where the
    /// comprehension is written, the rest inside it.
    #[inline(never)]
    fn run_comprehension(&mut self, c: &Rc<Comprehension>, env: &Rc<Env>) -> R<Value> {
        if c.kind == CompKind::Gen {
            return self.comprehension_generator(c, env);
        }
        let first = self.loop_items(&c.clauses[0].iterable, env)?;
        let scope = self.frame(env, c.frame.names.len());
        let mut acc = CompAcc { list: Vec::new(), dict: DictMap::default(), set: KeySet::default() };
        let r = self.comp_step(c, 0, Some(first), &scope, &mut acc);
        self.recycle(scope);
        r?;
        Ok(match c.kind {
            CompKind::List | CompKind::Gen => Value::list(acc.list),
            CompKind::Set => Value::new_set(acc.set),
            CompKind::Dict => Value::new_dict(acc.dict),
        })
    }

    #[inline(never)]
    fn comp_step(&mut self, c: &Comprehension, i: usize, items: Option<Iter>, scope: &Rc<Env>, acc: &mut CompAcc) -> R<()> {
        self.tick()?;
        if i >= c.clauses.len() {
            match c.kind {
                CompKind::List | CompKind::Gen => {
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
        let mut items = match items {
            Some(it) => it,
            None => self.loop_items(&clause.iterable, scope)?,
        };
        while let Some(item) = self.next_item(&mut items)? {
            self.tick()?;
            self.assign(&clause.target, item, scope)?;
            if self.conditions_hold(&clause.conditions, scope)? {
                self.comp_step(c, i + 1, None, scope, acc)?;
            }
        }
        Ok(())
    }

    /// A comprehension clause's `if` filters, in order, stopping at the first false one.
    pub(crate) fn conditions_hold(&mut self, conditions: &[Expr], scope: &Rc<Env>) -> R<bool> {
        for cond in conditions {
            if !self.holds(cond, scope)? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    // MARK: - Attributes

    /// Attribute access outside a call: module members, the data attributes
    /// exceptions and matches have, and (an addition) a dict's string keys.
    pub fn attribute(&mut self, obj: &Value, name: &str) -> R<Value> {
        // `f.__call__` of a function or method: calling it calls `f`.
        if name == "__call__" {
            let plain = match obj {
                Value::Function(_) | Value::ModuleFunction(_) | Value::HostFunction(_) => true,
                Value::Builtin(n) => !matches!(n.as_ref(), "Ellipsis" | "NotImplemented"),
                Value::Object(o) => matches!(o.as_ref(), Object::Bound(..) | Object::StaticMethod(_) | Object::ClassMethod(_)),
                _ => false,
            };
            if plain {
                return Ok(obj.clone());
            }
        }
        // Every object knows its type; an instance's may be a property.
        if name == "__class__" && !matches!(obj, Value::Instance(_)) {
            return Ok(self.type_of(obj));
        }
        match obj {
            Value::Instance(inst) => match self.instance_attr(inst, name)? {
                Some(v) => Ok(v),
                None => Err(self.error(format!("'{}' object has no attribute '{name}'", inst.class.name), "AttributeError")),
            },
            Value::Class(c) => {
                if name == "__dict__" && c.lookup("__dict__").is_none() {
                    let plain = self.class_attr(c, "__dict__").unwrap_or(Value::None);
                    return self.class_dict_proxy(plain);
                }
                if let Some(v) = self.class_attr(c, name) {
                    // `Cls.attr` on a descriptor: its `__get__(None, Cls)`.
                    if let Value::Instance(di) = &v {
                        if let Some(g) = di.class.lookup("__get__") {
                            let bound = self.bind(g, v.clone(), &di.class.clone());
                            return self.call_value(&bound, vec![Value::None, obj.clone()], Vec::new());
                        }
                    }
                    return Ok(v);
                }
                // Then the metaclass's, bound to the class.
                if let Some(m) = &c.meta {
                    if let Some(v) = m.lookup(name) {
                        if let Value::Object(o) = &v {
                            if let Object::Property { get, .. } = o.as_ref() {
                                let get = get.clone();
                                return self.call_value(&get, vec![obj.clone()], Vec::new());
                            }
                        }
                        return Ok(self.bind(v, obj.clone(), m));
                    }
                }
                // `Cls.__call__`: calling it makes an instance.
                if name == "__call__" {
                    return Ok(obj.clone());
                }
                Err(self.error(format!("type object '{}' has no attribute '{name}'", c.name), "AttributeError"))
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
                    "fdel" => Ok(Value::None),
                    "__doc__" => {
                        let g = get.clone();
                        Ok(self.optional_attribute(&g, "__doc__")?.unwrap_or(Value::None))
                    }
                    "__isabstractmethod__" => {
                        let g = get.clone();
                        Ok(self.optional_attribute(&g, "__isabstractmethod__")?.unwrap_or(Value::Bool(false)))
                    }
                    _ => Err(self.error(format!("'property' object has no attribute '{name}'"), "AttributeError")),
                },
                // `cm.__func__` and what the function it wraps answers, as
                // CPython 3.10 on lets a classmethod or staticmethod read.
                Object::ClassMethod(f) | Object::StaticMethod(f) => match name {
                    "__func__" | "__wrapped__" => Ok(f.clone()),
                    "__isabstractmethod__" => {
                        let f = f.clone();
                        Ok(self.optional_attribute(&f, "__isabstractmethod__")?.unwrap_or(Value::Bool(false)))
                    }
                    "__name__" | "__qualname__" | "__doc__" | "__module__" | "__annotations__" => {
                        let f = f.clone();
                        self.attribute(&f, name)
                    }
                    _ => Err(self.error(format!("'{}' object has no attribute '{name}'", obj.type_name()), "AttributeError")),
                },
                Object::Bound(receiver, f) => match name {
                    "__self__" => Ok(receiver.clone()),
                    "__func__" => Ok(f.clone()),
                    "__name__" => match f {
                        Value::Function(f) => Ok(Value::Str(f.display_name())),
                        other => Ok(Value::str(other.to_str())),
                    },
                    _ => match f {
                        // A method reads its function's attributes, as Python's does.
                        Value::Function(_) => {
                            let f = f.clone();
                            match self.attribute(&f, name) {
                                Err(Exc::Error(e)) if e.kind == "AttributeError" && !e.is_abort() => {
                                    Err(self.error(format!("'method' object has no attribute '{name}'"), "AttributeError"))
                                }
                                other => other,
                            }
                        }
                        _ => Err(self.error(format!("'method' object has no attribute '{name}'"), "AttributeError")),
                    },
                },
                Object::Slice(a, b, c) => match name {
                    "start" => Ok(a.clone()),
                    "stop" => Ok(b.clone()),
                    "step" => Ok(c.clone()),
                    _ if name == "indices" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'slice' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::Range(a, b, c) => match name {
                    "start" => Ok(Value::Int(*a)),
                    "stop" => Ok(Value::Int(*b)),
                    "step" => Ok(Value::Int(*c)),
                    "index" | "count" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'range' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::BigRange(r) => match name {
                    "start" => Ok(super::bigint::int_value(r.start.clone())),
                    "stop" => Ok(super::bigint::int_value(r.stop.clone())),
                    "step" => Ok(super::bigint::int_value(r.step.clone())),
                    "index" | "count" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'range' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::Coroutine(c) => match name {
                    "__name__" | "__qualname__" => Ok(Value::Str(c.name.clone())),
                    "cr_running" => Ok(Value::Bool(matches!(*c.state.borrow(), super::generators::CoState::Running))),
                    "send" | "throw" | "close" | "__await__" | "__next__" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'coroutine' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::AsyncGen(n, _) => match name {
                    "__name__" | "__qualname__" => Ok(Value::Str(n.clone())),
                    "__aiter__" | "__anext__" | "asend" | "athrow" | "aclose" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'async_generator' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::Generator(g) => match name {
                    "__name__" | "__qualname__" => Ok(Value::Str(g.name.clone())),
                    "send" | "throw" | "close" | "__next__" | "__iter__" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'generator' object has no attribute '{name}'"), "AttributeError")),
                },
                Object::Iterator(i) => match name {
                    "__next__" | "__iter__" => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'{}' object has no attribute '{name}'", i.kind), "AttributeError")),
                },
                Object::Pattern { pattern, flags } => match name {
                    "pattern" if flags & super::regex::BYTES != 0 => Ok(Value::bytes(&super::regex::latin1_bytes(pattern))),
                    "pattern" => Ok(Value::Str(pattern.clone())),
                    // Python's: a str pattern has UNICODE on unless ASCII is.
                    "flags" if flags & super::regex::BYTES != 0 => Ok(Value::Int(flags & !super::regex::BYTES)),
                    "flags" if flags & super::regex::ASCII == 0 => Ok(Value::Int(flags | super::regex::UNICODE)),
                    "flags" => Ok(Value::Int(*flags)),
                    "groups" | "groupindex" => self.call_method(obj, name, Vec::new(), Vec::new()),
                    _ if super::modules::PATTERN_METHODS.contains(&name) => Ok(self.method_value(obj, name)),
                    _ => Err(self.error(format!("'re.Pattern' object has no attribute '{name}'"), "AttributeError")),
                },
                _ => Err(self.error(format!("'{}' object has no attribute '{name}'", obj.type_name()), "AttributeError")),
            },
            Value::Function(f) => {
                if let Some(v) = f.attr(name) {
                    return Ok(v);
                }
                match name {
                    "__name__" => Ok(Value::Str(f.name.clone())),
                    "__qualname__" => Ok(Value::str(call_name(f))),
                    "__get__" => Ok(Value::Object(Rc::new(Object::Bound(
                        obj.clone(),
                        Value::Builtin(Rc::from(super::classes::HIDDEN_FUNCTION_GET)),
                    )))),
                    "__doc__" => Ok(match &f.body {
                        FnBody::Def(d) => docstring(&d.body),
                        FnBody::Lambda(..) => Value::None,
                    }),
                    // The `__name__` of the module whose top level the function closes over.
                    "__module__" => Ok(f.closure.module().vars.borrow().get("__name__").cloned().unwrap_or(Value::None)),
                    "__defaults__" => {
                        let n = f.signature().positional.len();
                        let given: Vec<Value> = f.defaults[..n.min(f.defaults.len())].iter().flatten().cloned().collect();
                        Ok(if given.is_empty() { Value::None } else { Value::tuple(given) })
                    }
                    "__kwdefaults__" => {
                        let sig = f.signature();
                        let n = sig.positional.len();
                        let pairs: Vec<(Rc<str>, Value)> = sig
                            .keyword_only
                            .iter()
                            .zip(f.defaults.iter().skip(n))
                            .filter_map(|(p, d)| d.clone().map(|d| (p.name.clone(), d)))
                            .collect();
                        Ok(if pairs.is_empty() { Value::None } else { Value::dict(pairs) })
                    }
                    "__annotations__" => self.function_annotations(f),
                    // The module's globals, which get_type_hints reads string annotations in.
                    "__globals__" => {
                        let mut root = f.closure.clone();
                        while let Some(p) = root.parent.clone() {
                            root = p;
                        }
                        Ok(Value::Dict(self.globals_dict_of(&root)))
                    }
                    "__closure__" => Ok(Value::None),
                    "__type_params__" => Ok(Value::tuple(Vec::new())),
                    // What inspect reads: the parameters and how the function runs.
                    "__code__" => {
                        let sig = f.signature();
                        let info = f.frame_info();
                        let mut flags = 0i64;
                        if sig.variadic.is_some() {
                            flags |= 0x04;
                        }
                        if sig.kwargs.is_some() {
                            flags |= 0x08;
                        }
                        flags |= match (info.generator, info.coroutine) {
                            (true, true) => 0x200,
                            (true, false) => 0x20,
                            (false, true) => 0x80,
                            _ => 0,
                        };
                        let mut varnames: Vec<Value> = sig.positional.iter().chain(sig.keyword_only.iter()).map(|p| Value::Str(p.name.clone())).collect();
                        varnames.extend(sig.variadic.iter().chain(sig.kwargs.iter()).map(|n| Value::Str(n.clone())));
                        for n in &info.names {
                            if !varnames.iter().any(|v| v.as_str() == Some(n)) {
                                varnames.push(Value::Str(n.clone()));
                            }
                        }
                        // CO_OPTIMIZED | CO_NEWLOCALS, as every function's code has.
                        flags |= 0x03;
                        let first_line = match &f.body {
                            FnBody::Def(d) => d.body.first().map(|s| s.line.saturating_sub(1).max(1)).unwrap_or(1),
                            FnBody::Lambda(_, line) => *line,
                        };
                        let file = self.module_files.get(&function_module(f)).cloned().unwrap_or_else(|| "<plugin>".to_string());
                        let count = varnames.len() as i64;
                        let class = self.python_builtin("code")?;
                        let Value::Class(class) = class else { return Ok(Value::None) };
                        let code = Rc::new(Instance::new(class));
                        for (k, v) in [
                            ("co_name", Value::Str(f.display_name())),
                            ("co_qualname", Value::str(call_name(f))),
                            ("co_argcount", Value::Int(sig.positional.len() as i64)),
                            ("co_posonlyargcount", Value::Int(sig.posonly as i64)),
                            ("co_kwonlyargcount", Value::Int(sig.keyword_only.len() as i64)),
                            ("co_varnames", Value::tuple(varnames)),
                            ("co_nlocals", Value::Int(count)),
                            ("co_freevars", Value::tuple(Vec::new())),
                            ("co_cellvars", Value::tuple(Vec::new())),
                            ("co_names", Value::tuple(Vec::new())),
                            ("co_consts", Value::tuple(Vec::new())),
                            ("co_flags", Value::Int(flags)),
                            ("co_filename", Value::str(&file)),
                            ("co_firstlineno", Value::Int(i64::from(first_line))),
                        ] {
                            code.insert(Rc::from(k), v);
                        }
                        self.track_instance(&code);
                        Ok(Value::Instance(code))
                    }
                    "__dict__" => Ok(Value::Dict(f.attr_dict())),
                    _ => Err(self.error(format!("'function' object has no attribute '{name}'"), "AttributeError")),
                }
            }
            Value::Builtin(b) if super::methods::type_member(b, name) => {
                Ok(Value::Builtin(Rc::from(format!("{}{b}.{name}", super::methods::UNBOUND_PREFIX))))
            }
            Value::Builtin(b) if b.as_ref() == "type" && matches!(name, "__new__" | "__call__" | "__init__") => {
                Ok(Value::Builtin(Rc::from(match name {
                    "__new__" => super::classes::HIDDEN_TYPE_NEW,
                    "__call__" => super::classes::HIDDEN_TYPE_CALL,
                    _ => super::classes::HIDDEN_TYPE_INIT,
                })))
            }
            // `Exception.__init__(self, message)` from a subclass's own `__init__`.
            Value::Builtin(b)
                if matches!(name, "__init__" | "__new__" | "__str__" | "__repr__" | "__setattr__" | "__delattr__" | "__getattribute__" | "__init_subclass__")
                    && super::builtins::EXCEPTION_KINDS.contains(&b.as_ref()) =>
            {
                Ok(Value::Builtin(Rc::from(match name {
                    "__init_subclass__" => super::classes::HIDDEN_OBJECT_INIT_SUBCLASS,
                    "__setattr__" => super::classes::HIDDEN_OBJECT_SETATTR,
                    "__delattr__" => super::classes::HIDDEN_OBJECT_DELATTR,
                    "__getattribute__" => super::classes::HIDDEN_OBJECT_GETATTR,
                    "__init__" => super::classes::HIDDEN_BASE_INIT,
                    "__new__" => super::classes::HIDDEN_OBJECT_NEW,
                    "__str__" => super::classes::HIDDEN_DEFAULT_STR,
                    _ => super::classes::HIDDEN_DEFAULT_REPR,
                })))
            }
            Value::Builtin(b) if b.as_ref() == "object" && name != "__dict__" => {
                let hidden = match name {
                    "__init_subclass__" => super::classes::HIDDEN_OBJECT_INIT_SUBCLASS,
                    "__setattr__" => super::classes::HIDDEN_OBJECT_SETATTR,
                    "__delattr__" => super::classes::HIDDEN_OBJECT_DELATTR,
                    "__eq__" => super::classes::HIDDEN_OBJECT_EQ,
                    "__ne__" => super::classes::HIDDEN_OBJECT_NE,
                    "__hash__" => super::classes::HIDDEN_OBJECT_HASH,
                    "__getattribute__" | "__getattr__" => super::classes::HIDDEN_OBJECT_GETATTR,
                    "__init__" => super::classes::HIDDEN_BASE_INIT,
                    "__new__" => super::classes::HIDDEN_OBJECT_NEW,
                    "__str__" => super::classes::HIDDEN_DEFAULT_STR,
                    "__repr__" => super::classes::HIDDEN_DEFAULT_REPR,
                    _ if super::classes::pickling_dunder(name).is_some() => super::classes::pickling_dunder(name).unwrap(),
                    "__base__" => return Ok(Value::None),
                    "__bases__" => return Ok(Value::tuple(Vec::new())),
                    "__mro__" => return Ok(Value::tuple(vec![obj.clone()])),
                    "__name__" | "__qualname__" => return Ok(Value::str("object")),
                    "__module__" => return Ok(Value::str("builtins")),
                    _ => return Err(self.error(format!("type object 'object' has no attribute '{name}'"), "AttributeError")),
                };
                Ok(Value::Builtin(Rc::from(hidden)))
            }
            // `object.__setattr__.__get__(x)`: a builtin function binds as a method.
            Value::Builtin(b) if name == "__get__" && !super::builtins::is_type_name(b) && b.as_ref() != "object" => {
                Ok(Value::Object(Rc::new(Object::Bound(obj.clone(), Value::Builtin(Rc::from(super::classes::HIDDEN_FUNCTION_GET))))))
            }
            // `int.__dict__`: what `dir(int)` lists, each name to what it reads.
            // Its own methods only: `__reduce_ex__` is object's, not list's.
            Value::Builtin(b) if name == "__subclasses__" && (b.as_ref() == "object" || super::builtins::is_type_name(b)) => {
                Ok(Value::Object(Rc::new(Object::Bound(obj.clone(), Value::Builtin(Rc::from(super::classes::HIDDEN_SUBCLASSES))))))
            }
            Value::Builtin(b) if name == "__dict__" && (b.as_ref() == "object" || super::builtins::is_type_name(b)) => {
                let names: Vec<Rc<str>> = if b.as_ref() == "object" {
                    match self.dir_of(obj)? {
                        Value::List(l) => l.borrow().iter().filter_map(|v| v.as_str().map(Rc::from)).collect(),
                        _ => Vec::new(),
                    }
                } else {
                    let mut own: Vec<Rc<str>> = super::methods::type_members(b).map(Rc::from).collect();
                    own.sort();
                    own.dedup();
                    own
                };
                let mut pairs = Vec::with_capacity(names.len());
                for n in names {
                    if matches!(n.as_ref(), "__dict__" | "__mro__" | "__bases__" | "__base__" | "__class__") {
                        continue;
                    }
                    if let Ok(v) = self.attribute(obj, &n) {
                        pairs.push((n, v));
                    }
                }
                self.class_dict_proxy(Value::dict(pairs))
            }
            // `bool.__mro__` is (bool, int, object); `KeyError.__bases__` is (LookupError,).
            Value::Builtin(b)
                if matches!(name, "__mro__" | "__bases__" | "__base__")
                    && b.as_ref() != "object"
                    && super::builtins::is_type_name(b) =>
            {
                let mut chain = vec![obj.clone()];
                let mut at: &str = b;
                loop {
                    let parent = if super::builtins::EXCEPTION_KINDS.contains(&at) {
                        exception_parent(at)
                    } else if at == "bool" {
                        Some("int")
                    } else {
                        None
                    };
                    match parent {
                        Some(p) => {
                            chain.push(Value::Builtin(Rc::from(p)));
                            at = p;
                        }
                        None => break,
                    }
                }
                chain.push(Value::Builtin(Rc::from("object")));
                Ok(match name {
                    "__mro__" => Value::tuple(chain),
                    "__bases__" => Value::tuple(vec![chain[1].clone()]),
                    _ => chain[1].clone(),
                })
            }
            // `re.Pattern` lives in `re`; `len` and `list` in builtins.
            Value::Builtin(b) if name == "__module__" && !b.starts_with(super::methods::UNBOUND_PREFIX) => {
                Ok(Value::str(b.rsplit_once('.').map_or("builtins", |(m, _)| m)))
            }
            Value::Builtin(b) if matches!(name, "__name__" | "__qualname__") => match b.strip_prefix(super::methods::UNBOUND_PREFIX) {
                Some(path) if name == "__qualname__" => Ok(Value::str(path)),
                Some(path) => Ok(Value::str(path.rsplit('.').next().unwrap_or(path))),
                None => Ok(Value::str(b.rsplit('.').next().unwrap_or(b))),
            },
            Value::Module(m) if name == "__dict__" => self.module_dict(m),
            Value::Module(m) => match self.member(m, name) {
                Some(v) => Ok(v),
                None if name == "__name__" => Ok(Value::Str(m.clone())),
                None if name == "__spec__" => self.native_spec(m),
                None if modules::python_member(m, name).is_some() => self.python_builtin(modules::python_member(m, name).unwrap_or_default()),
                // A submodule of a native module, once imported: `json.decoder`.
                None if self.module_envs.contains_key(format!("{m}.{name}").as_str()) => Ok(Value::Module(Rc::from(format!("{m}.{name}")))),
                None => match self.module_getattr(m, name)? {
                    Some(v) => Ok(v),
                    None => Err(self.error(format!("module '{m}' has no attribute '{name}'"), "AttributeError")),
                },
            },
            // `math.sqrt.__name__` and the like.
            Value::ModuleFunction(mf) if matches!(name, "__name__" | "__qualname__") => Ok(Value::Str(mf.1.clone())),
            Value::ModuleFunction(mf) if name == "__module__" => Ok(Value::Str(mf.0.clone())),
            // `sys.version_info.major` and the rest of its fields.
            Value::Tuple(t) if self.struct_seqs.iter().any(|(_, _, v)| Rc::ptr_eq(t, v)) => {
                let (seq, fields, values) = self.struct_seqs.iter().find(|(_, _, v)| Rc::ptr_eq(t, v)).unwrap();
                match fields.iter().position(|f| *f == name) {
                    Some(i) => Ok(values[i].clone()),
                    None => Err(self.error(format!("'sys.{seq}' object has no attribute '{name}'"), "AttributeError")),
                }
            }
            Value::Tuple(t) if Rc::ptr_eq(t, &self.version_info) => {
                let at = match name {
                    "major" => 0,
                    "minor" => 1,
                    "micro" => 2,
                    "releaselevel" => 3,
                    "serial" => 4,
                    _ => return Err(self.error(format!("'sys.version_info' object has no attribute '{name}'"), "AttributeError")),
                };
                Ok(t[at].clone())
            }
            Value::Exception(e) => match name {
                // An OSError keeps (errno, strerror) as its args, the file names apart.
                "args" if (3..=5).contains(&e.args.len()) && !matches!(e.args[2], Value::None) && self.exception_is("OSError", &e.kind) => {
                    Ok(Value::tuple(e.args[..2].to_vec()))
                }
                "args" => Ok(Value::tuple(e.args.clone())),
                "message" => Ok(Value::str(&e.message)),
                // What StopIteration carries back from a generator's `return`, and SystemExit's status.
                "value" if e.kind == "StopIteration" => Ok(e.args.first().cloned().unwrap_or(Value::None)),
                "code" if e.kind == "SystemExit" => Ok(match e.args.len() {
                    0 => Value::None,
                    1 => e.args[0].clone(),
                    _ => Value::tuple(e.args.clone()),
                }),
                "errno" | "strerror" | "filename" | "filename2" if self.exception_is("OSError", &e.kind) => {
                    let at = match name {
                        "errno" => 0,
                        "strerror" => 1,
                        "filename" => 2,
                        _ => 4,
                    };
                    Ok(if (2..=5).contains(&e.args.len()) { e.args.get(at).cloned().unwrap_or(Value::None) } else { Value::None })
                }
                // An ImportError's module, read back off the import's message.
                "name" | "path" | "msg" if self.exception_is("ImportError", &e.kind) => Ok(match name {
                    "name" => import_error_name(&e.message),
                    "msg" => Value::str(&e.message),
                    _ => Value::None,
                }),
                "__cause__" => Ok(e.cause.clone().unwrap_or(Value::None)),
                "__context__" => Ok(e.context.clone().unwrap_or(Value::None)),
                "__traceback__" => Ok(Value::None),
                "__notes__" => match self.notes_of(e) {
                    Some(n) => Ok(n),
                    None => Err(self.error(format!("'{}' object has no attribute '__notes__'", e.kind), "AttributeError")),
                },
                _ => Err(self.error(format!("'{}' object has no attribute '{name}'", e.kind), "AttributeError")),
            },
            Value::Match(m) => match name {
                "string" => Ok(m.subject.piece(m.subject.text())),
                "lastindex" => Ok(Value::Int(m.spans.len().saturating_sub(1) as i64)),
                _ => Err(self.error(format!("Match has no attribute '{name}'"), "AttributeError")),
            },
            Value::Dict(d) => {
                if let Some(v) = d.borrow().get(&StrKey(name)) {
                    return Ok(v.clone());
                }
                if super::methods::has_builtin_method(obj, name) {
                    return Ok(self.method_value(obj, name));
                }
                Err(self.error(format!("'dict' object has no attribute '{name}'"), "AttributeError"))
            }
            // Numbers' parts, as `numbers.Rational` and `numbers.Complex` name them.
            Value::Int(n) if matches!(name, "real" | "imag" | "numerator" | "denominator") => {
                Ok(Value::Int(match name {
                    "imag" => 0,
                    "denominator" => 1,
                    _ => *n,
                }))
            }
            Value::Big(b) if matches!(name, "real" | "imag" | "numerator" | "denominator") => Ok(match name {
                "imag" => Value::Int(0),
                "denominator" => Value::Int(1),
                _ => Value::Big(b.clone()),
            }),
            Value::Bool(b) if matches!(name, "real" | "imag" | "numerator" | "denominator") => {
                Ok(Value::Int(match name {
                    "imag" => 0,
                    "denominator" => 1,
                    _ => *b as i64,
                }))
            }
            Value::Float(f) if matches!(name, "real" | "imag") => Ok(Value::Float(if name == "real" { *f } else { 0.0 })),
            Value::Complex(re, im) if matches!(name, "real" | "imag") => Ok(Value::Float(if name == "real" { *re } else { *im })),
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
                if !kwargs.is_empty()
                    && !super::builtins::KWARG_BUILTINS.contains(&name.as_ref())
                    && !name.starts_with(super::methods::UNBOUND_PREFIX)
                {
                    return Err(self.error(format!("{name}() takes no keyword arguments"), "TypeError"));
                }
                self.call_builtin(name, &args, &kwargs)
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
                    let bound = self.special_bound(m, callee, &inst.class)?;
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
        // The host reads a subclass of a built-in type as its value.
        let args = plain_args(args);
        let kwargs = kwargs.into_iter().map(|(k, v)| (k, plain_value(v))).collect();
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

    // MARK: - Frames

    /// A frame for a call, with [n] unbound slots: a spare one when there is
    /// one, so a call that leaves nothing behind allocates nothing.
    #[inline]
    pub(crate) fn frame(&mut self, parent: &Rc<Env>, n: usize) -> Rc<Env> {
        if let Some(mut e) = self.spare.pop() {
            if let Some(m) = Rc::get_mut(&mut e) {
                m.parent = Some(parent.clone());
                m.slots.get_mut().resize(n, None);
                return e;
            }
        }
        Rc::new(Env { slots: RefCell::new(vec![None; n]), vars: RefCell::new(Scope::with_capacity(0)), parent: Some(parent.clone()) })
    }

    /// Hand a finished frame back for reuse, when nothing kept hold of it
    /// (no closure, no generator, nothing tracking it).
    #[inline]
    pub(crate) fn recycle(&mut self, mut env: Rc<Env>) {
        if self.spare.len() >= SPARE_FRAMES {
            return;
        }
        let Some(m) = Rc::get_mut(&mut env) else { return };
        let parent = m.parent.take();
        m.slots.get_mut().clear();
        drop(parent);
        self.spare.push(env);
    }

    // MARK: - Calls

    /// `f(a, b)` and `obj.method(a)` for a function of plain positional
    /// parameters: each argument is evaluated straight into its slot.
    fn call_fast(&mut self, f: &Rc<Function>, receiver: Option<Value>, args: &[Expr], env: &Rc<Env>) -> R<Value> {
        let params = &f.signature().positional;
        let given = args.len() + receiver.is_some() as usize;
        if given > params.len() {
            return Err(self.too_many(f, given, 0));
        }
        let mut frame = self.frame(&f.closure, f.frame_info().names.len());
        {
            let slots = Rc::get_mut(&mut frame).expect("a new frame has one owner").slots.get_mut();
            let mut i = 0;
            if let Some(r) = receiver {
                slots[0] = Some(r);
                i = 1;
            }
            for a in args {
                slots[i] = Some(self.eval(a, env)?);
                i += 1;
            }
            if given < params.len() {
                for (j, slot) in slots.iter_mut().enumerate().take(params.len()).skip(given) {
                    *slot = f.defaults.get(j).cloned().flatten();
                }
                if slots[given..params.len()].iter().any(Option::is_none) {
                    return Err(self.missing(f, slots));
                }
            }
        }
        self.run_frame(f, frame)
    }

    #[inline(never)]
    pub fn call_function(&mut self, f: &Rc<Function>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let frame = self.bind_arguments(f, args, kwargs)?;
        self.run_frame(f, frame)
    }

    /// A frame for calling [f] with these arguments, each in its parameter's slot.
    fn bind_arguments(&mut self, f: &Rc<Function>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Rc<Env>> {
        let sig = f.signature();
        let params = &sig.positional;
        let p = params.len();
        if sig.variadic.is_none() && args.len() > p {
            let keyword_only = kwargs.iter().filter(|(k, _)| sig.keyword_only.iter().any(|q| q.name == *k)).count();
            return Err(self.too_many(f, args.len(), keyword_only));
        }
        let mut frame = self.frame(&f.closure, f.frame_info().names.len());
        let slots = Rc::get_mut(&mut frame).expect("a new frame has one owner").slots.get_mut();
        // Positional arguments fill the first parameters: those are bound,
        // and a keyword naming one of them again is an error.
        let positional = args.len().min(p);
        let mut args = args.into_iter();
        for slot in slots.iter_mut().take(positional) {
            *slot = args.next();
        }
        let mut next = p;
        if sig.variadic.is_some() {
            slots[next] = Some(Value::tuple(args.collect()));
            next += 1;
        }
        let keyword_base = next;
        let mut extra: DictMap = DictMap::default();
        for (k, v) in kwargs {
            // A keyword never fills a parameter before `/`; with **kwargs it lands there instead.
            let posonly = params[..sig.posonly].iter().any(|q| q.name == k);
            let at = match params.iter().position(|q| q.name == k).filter(|i| *i >= sig.posonly) {
                Some(i) => Some(i),
                None => sig.keyword_only.iter().position(|q| q.name == k).map(|j| keyword_base + j),
            };
            match at {
                Some(i) => {
                    if slots[i].is_some() {
                        return Err(self.error(format!("{}() got multiple values for argument '{k}'", call_name(f)), "TypeError"));
                    }
                    slots[i] = Some(v);
                }
                None if sig.kwargs.is_some() => {
                    extra.insert(HKey::Str(k), v);
                }
                None if posonly => {
                    return Err(self.error(
                        format!("{}() got some positional-only arguments passed as keyword arguments: '{k}'", call_name(f)),
                        "TypeError",
                    ));
                }
                None => {
                    return Err(self.error(format!("{}() got an unexpected keyword argument '{k}'", call_name(f)), "TypeError"));
                }
            }
        }
        // Defaults fill what is left: one per positional parameter, then one per keyword-only one.
        for (i, slot) in slots.iter_mut().enumerate().take(p).skip(positional) {
            if slot.is_none() {
                *slot = f.defaults.get(i).cloned().flatten();
            }
        }
        for j in 0..sig.keyword_only.len() {
            if slots[keyword_base + j].is_none() {
                slots[keyword_base + j] = f.defaults.get(p + j).cloned().flatten();
            }
        }
        if slots[..p].iter().any(Option::is_none) || slots[keyword_base..keyword_base + sig.keyword_only.len()].iter().any(Option::is_none) {
            return Err(self.missing(f, slots));
        }
        if sig.kwargs.is_some() {
            slots[keyword_base + sig.keyword_only.len()] = Some(Value::new_dict(extra));
        }
        Ok(frame)
    }

    /// CPython's words: "takes 2 positional arguments but 3 were given",
    /// "takes from 1 to 2 …" when some have defaults.
    #[cold]
    fn too_many(&self, f: &Function, given: usize, keyword_only: usize) -> Exc {
        let max = f.signature().positional.len();
        let min = (0..max).filter(|&i| f.defaults.get(i).is_none_or(Option::is_none)).count();
        let takes = if min == max { plural(max, "positional argument") } else { format!("from {min} to {max} positional arguments") };
        let was = if given + keyword_only == 1 { "was" } else { "were" };
        // With keyword-only arguments passed as well, CPython counts both.
        let given = if keyword_only == 0 {
            given.to_string()
        } else {
            format!("{} (and {})", plural(given, "positional argument"), plural(keyword_only, "keyword-only argument"))
        };
        self.error(format!("{}() takes {takes} but {given} {was} given", call_name(f)), "TypeError")
    }

    /// Every missing argument named at once, positional ones first, as CPython does.
    #[cold]
    fn missing(&self, f: &Function, slots: &[Option<Value>]) -> Exc {
        let sig = f.signature();
        let p = sig.positional.len();
        let keyword_base = p + sig.variadic.is_some() as usize;
        let missing: Vec<&str> = sig.positional.iter().enumerate().filter(|(i, _)| slots[*i].is_none()).map(|(_, q)| q.name.as_ref()).collect();
        let missing_keyword: Vec<&str> = sig
            .keyword_only
            .iter()
            .enumerate()
            .filter(|(j, _)| slots[keyword_base + j].is_none())
            .map(|(_, q)| q.name.as_ref())
            .collect();
        let (names, kind) = if missing.is_empty() { (missing_keyword, "keyword-only") } else { (missing, "positional") };
        let what = plural(names.len(), &format!("required {kind} argument"));
        self.error(format!("{}() missing {what}: {}", call_name(f), name_list(&names)), "TypeError")
    }

    /// Room for one more call: under the depth cap, and with native stack to spare.
    #[inline]
    pub(crate) fn check_depth(&self) -> R<()> {
        let marker = 0u8;
        let here = std::hint::black_box(&marker) as *const u8 as usize;
        if self.call_depth >= self.recursion_limit || (self.stack_base != 0 && self.stack_base.saturating_sub(here) > STACK_BUDGET) {
            return Err(self.abort(AbortKind::Recursion, "maximum recursion depth exceeded").into());
        }
        Ok(())
    }

    /// Run [f]'s body in [frame], its arguments already bound. A generator
    /// function hands back a generator instead, which runs the body as it is asked.
    pub(crate) fn run_frame(&mut self, f: &Rc<Function>, frame: Rc<Env>) -> R<Value> {
        let info = f.frame_info();
        if info.coroutine {
            return Ok(self.make_coroutine(f, frame));
        }
        if info.generator {
            return Ok(self.function_generator(f, frame));
        }
        self.run_body(f, frame)
    }

    /// An `async def` called: a coroutine, or with `yield` an async generator.
    #[inline(never)]
    fn make_coroutine(&mut self, f: &Rc<Function>, frame: Rc<Env>) -> Value {
        let name = f.display_name();
        if f.frame_info().generator {
            let g = self.function_generator(f, frame);
            return Value::Object(Rc::new(Object::AsyncGen(name, g)));
        }
        super::generators::coroutine(name, super::generators::CoState::Pending(f.clone(), frame))
    }

    /// Run [f]'s body in [frame] now: what a call does, and what awaiting a
    /// coroutine does. Inlined into each, so a call costs one native frame.
    #[inline(always)]
    pub(crate) fn run_body(&mut self, f: &Rc<Function>, frame: Rc<Env>) -> R<Value> {
        self.check_depth()?;
        self.call_depth += 1;
        self.frames.push((f.clone(), frame.clone()));
        let caller_line = self.current_line;
        let mut result = match &f.body {
            FnBody::Def(d) => self.exec_block(&d.body, &frame),
            FnBody::Lambda(l, line) => match self.tick() {
                Err(e) => Err(e.into()),
                Ok(()) => {
                    self.current_line = *line;
                    match self.eval(&l.body, &frame) {
                        Ok(v) => Err(Exc::Return(v)),
                        Err(e) => Err(e),
                    }
                }
            },
        };
        self.call_depth -= 1;
        self.frames.pop();
        if let Err(Exc::Error(e)) = &mut result {
            let module = function_module(f);
            self.note_frame(e, &f.display_name(), &module, caller_line);
        }
        self.recycle(frame);
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

    /// [v] as a complex number's parts when it is a number: an int, bool,
    /// float or complex. An int too big for a float is refused, as CPython does.
    fn complex_operand(&self, v: &Value) -> R<Option<(f64, f64)>> {
        Ok(match v {
            Value::Complex(re, im) => Some((*re, *im)),
            Value::Int(i) => Some((*i as f64, 0.0)),
            Value::Bool(b) => Some((*b as i64 as f64, 0.0)),
            Value::Float(f) => Some((*f, 0.0)),
            Value::Big(b) => Some((super::bigint::to_float(b).map_err(|(m, k)| self.error(m, k))?, 0.0)),
            _ => None,
        })
    }

    pub(crate) fn unary_op(&mut self, op: UnOp, v: &Value) -> R<Value> {
        if let Value::Instance(_) = v {
            let name = match op {
                UnOp::Not => return Ok(Value::Bool(!self.truth(v)?)),
                UnOp::Neg => "__neg__",
                UnOp::Pos => "__pos__",
                UnOp::Invert => "__invert__",
            };
            return match self.dunder(v, name, Vec::new())? {
                Some(r) => Ok(r),
                None => Err(self.error(format!("bad operand type for unary operator: '{}'", v.type_name()), "TypeError")),
            };
        }
        Ok(match op {
            UnOp::Not => Value::Bool(!v.truthy()),
            UnOp::Invert => match v {
                Value::Big(b) => super::bigint::int_value(!b.as_ref()),
                _ => match v.as_int() {
                    Some(i) => Value::Int(!i),
                    None => return Err(self.error(format!("bad operand type for unary ~: '{}'", v.type_name()), "TypeError")),
                },
            },
            UnOp::Pos => {
                if v.as_float().is_some() || matches!(v, Value::Complex(..)) {
                    v.clone()
                } else {
                    return Err(self.error(format!("bad operand type for unary +: '{}'", v.type_name()), "TypeError"));
                }
            }
            UnOp::Neg => match v {
                Value::Int(i) => i.checked_neg().map_or_else(|| super::bigint::int_value(-num_bigint::BigInt::from(*i)), Value::Int),
                Value::Big(b) => super::bigint::int_value(-b.as_ref()),
                Value::Bool(b) => Value::Int(if *b { -1 } else { 0 }),
                Value::Float(f) => Value::Float(-f),
                Value::Complex(re, im) => Value::Complex(-re, -im),
                _ => return Err(self.error(format!("bad operand type for unary -: '{}'", v.type_name()), "TypeError")),
            },
        })
    }

    pub fn binary_op(&mut self, op: BinOp, a: &Value, b: &Value) -> R<Value> {
        // Two ints or two floats, the bulk of all arithmetic, go straight to
        // the answer; the general path below gives the same one.
        match (a, b) {
            (Value::Int(x), Value::Int(y)) => match op {
                BinOp::BitAnd => return Ok(Value::Int(x & y)),
                BinOp::BitOr => return Ok(Value::Int(x | y)),
                BinOp::BitXor => return Ok(Value::Int(x ^ y)),
                BinOp::Div if *y != 0 => return Ok(Value::Float(*x as f64 / *y as f64)),
                BinOp::Add | BinOp::Sub | BinOp::Mul | BinOp::FloorDiv | BinOp::Mod | BinOp::Pow => {
                    if let Some(v) = self.int_op(op, *x, *y)? {
                        return Ok(v);
                    }
                }
                _ => {}
            },
            (Value::Float(x), Value::Float(y)) => match op {
                BinOp::Add => return Ok(Value::Float(x + y)),
                BinOp::Sub => return Ok(Value::Float(x - y)),
                BinOp::Mul => return Ok(Value::Float(x * y)),
                BinOp::Div if *y != 0.0 => return Ok(Value::Float(x / y)),
                _ => {}
            },
            _ => {}
        }
        if matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_)) {
            return self.instance_binary(op, a, b);
        }
        if matches!(a, Value::Complex(..)) || matches!(b, Value::Complex(..)) {
            if let (Some(x), Some(y)) = (self.complex_operand(a)?, self.complex_operand(b)?) {
                return match super::complex::binary(op, x, y) {
                    Ok(Some((re, im))) => Ok(Value::Complex(re, im)),
                    Ok(None) => Err(self.error(
                        format!("unsupported operand type(s) for {}: '{}' and '{}'", op_symbol(op), a.type_name(), b.type_name()),
                        "TypeError",
                    )),
                    Err((m, k)) => Err(self.error(m, k)),
                };
            }
        }
        if super::bigint::is_int(a) && super::bigint::is_int(b) {
            if let Some(v) = self.int_binary(op, a, b)? {
                return Ok(v);
            }
        }
        // Keys and items views combine like sets, with any iterable on the other side.
        if matches!(op, BinOp::BitAnd | BinOp::BitOr | BinOp::Sub | BinOp::BitXor) && (is_set_view(a) || is_set_view(b)) {
            let mut sides = Vec::with_capacity(2);
            for v in [a, b] {
                let set = match as_key_set(v) {
                    Some(s) => s,
                    None => {
                        let mut s = KeySet::default();
                        for x in self.iterate(v)? {
                            s.insert(self.hashable(&x)?);
                        }
                        s
                    }
                };
                sides.push(set);
            }
            if let Some(v) = set_algebra(op, &sides[0], &sides[1]) {
                return Ok(v);
            }
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
        // `a | b` on dicts: a new dict with b's entries over a's.
        if let (BinOp::BitOr, Value::Dict(x), Value::Dict(y)) = (op, a, b) {
            let mut merged = x.borrow().clone();
            for (k, v) in y.borrow().iter() {
                merged.insert(k.clone(), v.clone());
            }
            if merged.len() > self.max_container {
                return Err(self.container_abort("dict"));
            }
            return Ok(Value::new_dict(merged));
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
        // `int | str`, `list[int] | None`: a union of types.
        if op == BinOp::BitOr && type_like(a) && type_like(b) {
            let union = self.python_builtin("__union__")?;
            return self.call_value(&union, vec![a.clone(), b.clone()], Vec::new());
        }
        // Two ints have had their bit operation above; anything else has none.
        if matches!(op, BinOp::BitAnd | BinOp::BitOr | BinOp::BitXor | BinOp::LShift | BinOp::RShift) {
            return Err(self.error(
                format!("unsupported operand type(s) for {}: '{}' and '{}'", op_symbol(op), a.type_name(), b.type_name()),
                "TypeError",
            ));
        }
        if op == BinOp::MatMul {
            return Err(self.error(
                format!("unsupported operand type(s) for @: '{}' and '{}'", a.type_name(), b.type_name()),
                "TypeError",
            ));
        }
        // What is left of two ints is division and negative powers, which
        // give floats; int_op passes those on to the float arithmetic below.
        if let (Some(x), Some(y)) = (a.as_int(), b.as_int()) {
            if let Some(v) = self.int_op(op, x, y)? {
                return Ok(v);
            }
        }
        let (Some(x), Some(y)) = (a.as_float(), b.as_float()) else {
            if let (BinOp::Add, Value::Str(_) | Value::List(_) | Value::Tuple(_) | Value::Bytes(_)) = (op, a) {
                return Err(self.error(
                    format!("can only concatenate {} (not \"{}\") to {}", a.type_name(), b.type_name(), a.type_name()),
                    "TypeError",
                ));
            }
            return Err(self.error(
                format!("unsupported operand type(s) for {}: '{}' and '{}'", op_symbol(op), a.type_name(), b.type_name()),
                "TypeError",
            ));
        };
        let both_int = a.as_int().is_some() && b.as_int().is_some();
        // CPython says which kind of division it was.
        let by_zero = |int: &'static str, float: &'static str| if both_int { int } else { float };
        Ok(match op {
            BinOp::Add => numeric(x + y, both_int),
            BinOp::Sub => numeric(x - y, both_int),
            BinOp::Mul => numeric(x * y, both_int),
            BinOp::Div => {
                if y == 0.0 {
                    return Err(self.error(by_zero("division by zero", "float division by zero"), "ZeroDivisionError"));
                }
                Value::Float(x / y)
            }
            BinOp::FloorDiv => {
                if y == 0.0 {
                    let message = by_zero("integer division or modulo by zero", "float floor division by zero");
                    return Err(self.error(message, "ZeroDivisionError"));
                }
                numeric((x / y).floor(), both_int)
            }
            BinOp::Mod => {
                if y == 0.0 {
                    return Err(self.error(by_zero("integer modulo by zero", "float modulo by zero"), "ZeroDivisionError"));
                }
                // Python's modulo takes the sign of the divisor.
                numeric(x - (x / y).floor() * y, both_int)
            }
            BinOp::Pow if x == 0.0 && y < 0.0 => {
                return Err(self.error("0.0 cannot be raised to a negative power", "ZeroDivisionError"));
            }
            BinOp::Pow => numeric(x.powf(y), both_int && y >= 0.0),
            _ => return Err(self.error(format!("unsupported operand type(s) for {}", op_symbol(op)), "TypeError")),
        })
    }

    /// [op] on two ints of any size (bools too), exactly, as Python gives
    /// it: an i64 while the answer fits, a big int past that. None for what
    /// the float path answers: true division of i64s, and a negative power.
    fn int_binary(&mut self, op: BinOp, a: &Value, b: &Value) -> R<Option<Value>> {
        use super::bigint;
        if let (Some(x), Some(y)) = (a.as_int(), b.as_int()) {
            match op {
                // `True & False` stays a bool, as in Python.
                BinOp::BitAnd | BinOp::BitOr | BinOp::BitXor if matches!((a, b), (Value::Bool(_), Value::Bool(_))) => {
                    let (p, q) = (x != 0, y != 0);
                    return Ok(Some(Value::Bool(match op {
                        BinOp::BitAnd => p & q,
                        BinOp::BitOr => p | q,
                        _ => p ^ q,
                    })));
                }
                BinOp::BitAnd => return Ok(Some(Value::Int(x & y))),
                BinOp::BitOr => return Ok(Some(Value::Int(x | y))),
                BinOp::BitXor => return Ok(Some(Value::Int(x ^ y))),
                BinOp::RShift if y >= 0 => return Ok(Some(Value::Int(if y >= 64 { if x < 0 { -1 } else { 0 } } else { x >> y }))),
                BinOp::LShift if (0..63).contains(&y) => {
                    if let Some(v) = x.checked_mul(1i64 << y) {
                        return Ok(Some(Value::Int(v)));
                    }
                }
                BinOp::Div | BinOp::MatMul => return Ok(None),
                BinOp::Pow if y < 0 => return Ok(None),
                BinOp::LShift | BinOp::RShift => {}
                _ => {
                    if let Some(v) = self.int_op(op, x, y)? {
                        return Ok(Some(v));
                    }
                }
            }
        }
        let (Some(x), Some(y)) = (bigint::to_big(a), bigint::to_big(b)) else { return Ok(None) };
        if op == BinOp::Div {
            return match bigint::true_div(&x, &y) {
                Ok(f) => Ok(Some(Value::Float(f))),
                Err((m, k)) => Err(self.error(m, k)),
            };
        }
        match bigint::binary(op, &x, &y) {
            Ok(v) => Ok(v),
            Err((m, k)) => Err(self.error(m, k)),
        }
    }

    /// How two numbers of which one is a big int order, exactly; None
    /// when either is not a number, Some(None) against NaN.
    pub(crate) fn big_order(&self, a: &Value, b: &Value) -> Option<Option<std::cmp::Ordering>> {
        use super::bigint::{cmp_float, to_big};
        if !matches!(a, Value::Big(_)) && !matches!(b, Value::Big(_)) {
            return None;
        }
        match (a, b) {
            (Value::Float(f), _) => to_big(b).map(|y| cmp_float(&y, *f).map(|o| o.reverse())),
            (_, Value::Float(f)) => to_big(a).map(|x| cmp_float(&x, *f)),
            _ => Some(Some(to_big(a)?.cmp(&to_big(b)?))),
        }
    }

    /// [op] on two integers with Python's results, or None when the answer
    /// leaves 64 bits and the big-int path should give it instead.
    fn int_op(&mut self, op: BinOp, x: i64, y: i64) -> R<Option<Value>> {
        let zero = || "integer division or modulo by zero";
        Ok(match op {
            BinOp::Add => x.checked_add(y).map(Value::Int),
            BinOp::Sub => x.checked_sub(y).map(Value::Int),
            BinOp::Mul => x.checked_mul(y).map(Value::Int),
            BinOp::FloorDiv => {
                if y == 0 {
                    return Err(self.error(zero(), "ZeroDivisionError"));
                }
                x.checked_div(y).map(|q| Value::Int(if (x % y != 0) && ((x < 0) != (y < 0)) { q - 1 } else { q }))
            }
            BinOp::Mod => {
                if y == 0 {
                    return Err(self.error("integer modulo by zero", "ZeroDivisionError"));
                }
                // Python's modulo takes the sign of the divisor.
                x.checked_rem(y).map(|r| Value::Int(if r != 0 && ((r < 0) != (y < 0)) { r + y } else { r }))
            }
            BinOp::Pow if y >= 0 => u32::try_from(y).ok().and_then(|e| x.checked_pow(e)).map(Value::Int),
            _ => None,
        })
    }

    /// `"%s scored %d" % (name, score)`.
    pub(crate) fn printf_style(&mut self, format: &Rc<str>, rhs: &Value) -> R<String> {
        let args: Vec<Value> = match rhs {
            Value::Tuple(t) => t.as_ref().clone(),
            other => vec![other.clone()],
        };
        let b = self.chars(format);
        let chars: Vec<&str> = (0..b.len() - 1).map(|i| &format[b[i] as usize..b[i + 1] as usize]).collect();
        let mut out = String::new();
        let mut next = 0;
        let mut used_mapping = false;
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
            // `%(name)s`: the value under that key of a mapping on the right.
            let mut keyed = None;
            if chars[i] == "(" {
                let mut depth = 1;
                let mut key = String::new();
                i += 1;
                while i < chars.len() {
                    match chars[i] {
                        "(" => depth += 1,
                        ")" => {
                            depth -= 1;
                            if depth == 0 {
                                break;
                            }
                        }
                        _ => {}
                    }
                    key.push_str(chars[i]);
                    i += 1;
                }
                if i >= chars.len() {
                    return Err(self.error("incomplete format key", "ValueError"));
                }
                i += 1;
                if matches!(rhs, Value::Tuple(_)) || !matches!(rhs, Value::Dict(_) | Value::Instance(_)) {
                    return Err(self.error("format requires a mapping", "TypeError"));
                }
                keyed = Some(self.index(rhs, &Value::str(key))?);
            }
            let mut spec = String::new();
            while i < chars.len() && !PRINTF_TYPES.contains(&chars[i]) {
                if chars[i] == "*" {
                    // `%*d`, `%.*f`: the width or precision is the next argument;
                    // a negative width left-aligns, as CPython's does.
                    if keyed.is_some() || next >= args.len() {
                        return Err(self.error("not enough arguments for format string", "TypeError"));
                    }
                    let Some(n) = args[next].as_int() else {
                        return Err(self.error("* wants int", "TypeError"));
                    };
                    next += 1;
                    if n < 0 && !spec.contains('.') {
                        spec.push('-');
                        spec.push_str(&n.unsigned_abs().to_string());
                    } else {
                        spec.push_str(&n.max(0).to_string());
                    }
                    i += 1;
                    continue;
                }
                spec.push_str(chars[i]);
                i += 1;
            }
            if i >= chars.len() {
                return Err(self.error("incomplete format", "ValueError"));
            }
            let ty = chars[i];
            i += 1;
            let value = match keyed {
                Some(v) => {
                    used_mapping = true;
                    v
                }
                None => {
                    if next >= args.len() {
                        return Err(self.error("not enough arguments for format string", "TypeError"));
                    }
                    next += 1;
                    args[next - 1].clone()
                }
            };
            // `%s` asks an object's __str__, `%r` and `%a` its __repr__.
            let value = match ty {
                "s" => Value::str(self.to_str(&value)?),
                "r" => Value::str(self.to_repr(&value)?),
                "a" => Value::str(ascii_escape(&self.to_repr(&value)?)),
                _ => plain_value(value),
            };
            spec.push_str(match ty {
                "i" | "u" => "d",
                "r" | "a" => "s",
                other => other,
            });
            out.push_str(&py_format(&value, &spec)?);
        }
        // Left over: an error, unless the right side was a mapping (which may
        // hold keys the format never names) or a tuple fully used.
        let mapping = matches!(rhs, Value::Dict(_)) || (matches!(rhs, Value::Instance(_)) && (used_mapping || next == 0));
        if next < args.len() && !mapping {
            return Err(self.error("not all arguments converted during string formatting", "TypeError"));
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
            Value::Bytes(b) => {
                if (b.len() as u64).saturating_mul(times) > self.max_string as u64 {
                    return Err(self.string_abort());
                }
                Value::bytes(&b.repeat(times as usize))
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

    pub(crate) fn compare_op(&mut self, op: CmpOp, a: &Value, b: &Value) -> R<bool> {
        // Numbers compare directly: ints exactly across the whole 64-bit
        // range, and anything against NaN is false, as in Python.
        if let (Some(x), Some(y)) = (a.as_int(), b.as_int()) {
            match op {
                CmpOp::Eq => return Ok(x == y),
                CmpOp::Ne => return Ok(x != y),
                CmpOp::Lt => return Ok(x < y),
                CmpOp::Le => return Ok(x <= y),
                CmpOp::Gt => return Ok(x > y),
                CmpOp::Ge => return Ok(x >= y),
                _ => {}
            }
        } else if let (Value::Int(_) | Value::Float(_) | Value::Bool(_), Value::Int(_) | Value::Float(_) | Value::Bool(_)) = (a, b) {
            let (x, y) = (a.as_float().unwrap_or(0.0), b.as_float().unwrap_or(0.0));
            match op {
                CmpOp::Eq => return Ok(x == y),
                CmpOp::Ne => return Ok(x != y),
                CmpOp::Lt => return Ok(x < y),
                CmpOp::Le => return Ok(x <= y),
                CmpOp::Gt => return Ok(x > y),
                CmpOp::Ge => return Ok(x >= y),
                _ => {}
            }
        }
        if let Some(order) = self.big_order(a, b) {
            use std::cmp::Ordering::*;
            let r = match (op, order) {
                (CmpOp::Ne, None) => Some(true),
                (CmpOp::Eq | CmpOp::Lt | CmpOp::Le | CmpOp::Gt | CmpOp::Ge, None) => Some(false),
                (CmpOp::Eq, Some(o)) => Some(o == Equal),
                (CmpOp::Ne, Some(o)) => Some(o != Equal),
                (CmpOp::Lt, Some(o)) => Some(o == Less),
                (CmpOp::Le, Some(o)) => Some(o != Greater),
                (CmpOp::Gt, Some(o)) => Some(o == Greater),
                (CmpOp::Ge, Some(o)) => Some(o != Less),
                _ => None,
            };
            if let Some(r) = r {
                return Ok(r);
            }
        }
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
        // Sets order by inclusion: `a <= b` is a subset, `a < b` a proper one.
        if let (Value::Set(x), Value::Set(y), CmpOp::Lt | CmpOp::Le | CmpOp::Gt | CmpOp::Ge) = (a, b, op) {
            let (x, y) = (x.borrow(), y.borrow());
            let subset = |p: &KeySet, q: &KeySet| p.iter().all(|k| q.contains(k));
            return Ok(match op {
                CmpOp::Le => subset(&x, &y),
                CmpOp::Lt => x.len() < y.len() && subset(&x, &y),
                CmpOp::Ge => subset(&y, &x),
                _ => y.len() < x.len() && subset(&y, &x),
            });
        }
        Ok(match op {
            CmpOp::Eq => self.eq(a, b)?,
            CmpOp::Ne => !self.eq(a, b)?,
            CmpOp::Is => py_identical(a, b),
            CmpOp::IsNot => !py_identical(a, b),
            CmpOp::In => self.contains(b, a)?,
            CmpOp::NotIn => !self.contains(b, a)?,
            CmpOp::Lt => self.order_as(a, b, "<")? < 0,
            CmpOp::Le => self.order_as(a, b, "<=")? <= 0,
            CmpOp::Gt => self.order_as(a, b, ">")? > 0,
            CmpOp::Ge => self.order_as(a, b, ">=")? >= 0,
        })
    }

    /// Three-way ordering for numbers, strings and sequences (lexicographic).
    pub fn order(&mut self, a: &Value, b: &Value) -> R<i32> {
        self.order_as(a, b, "<")
    }

    /// [order], naming [symbol] when the two cannot be ordered.
    pub(crate) fn order_as(&mut self, a: &Value, b: &Value, symbol: &str) -> R<i32> {
        if matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_)) {
            if self.instance_compare(CmpOp::Lt, a, b)? {
                return Ok(-1);
            }
            return Ok(if self.instance_compare(CmpOp::Lt, b, a)? { 1 } else { 0 });
        }
        if let (Some(x), Some(y)) = (a.as_int(), b.as_int()) {
            return Ok(x.cmp(&y) as i32);
        }
        if let Some(o) = self.big_order(a, b) {
            return Ok(o.map_or(0, |o| o as i32));
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
                    let c = self.order_as(x, y, symbol)?;
                    if c != 0 {
                        return Ok(c);
                    }
                }
                return Ok((xs.len() as i64 - ys.len() as i64).signum() as i32);
            }
        }
        Err(self.error(format!("'{symbol}' not supported between instances of '{}' and '{}'", a.type_name(), b.type_name()), "TypeError"))
    }

    pub(crate) fn contains(&mut self, container: &Value, item: &Value) -> R<bool> {
        if let Value::Class(c) = container {
            let c = c.clone();
            if let Some(f) = self.meta_method(&c, "__contains__") {
                let r = self.call_value(&f, vec![item.clone()], Vec::new())?;
                return self.truth(&r);
            }
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
        // `S("a") in "abc"`: a str subclass is the str it is.
        if let (Some(p), Value::Str(_) | Value::Bytes(_)) = (payload_of(item), container) {
            let p = p.clone();
            return self.contains(container, &p);
        }
        if matches!(item, Value::Instance(_)) || meta_eq_class(item) {
            if let Some(items) = sequence_items(container) {
                for v in items {
                    if self.eq(&v, item)? {
                        return Ok(true);
                    }
                }
                return Ok(false);
            }
        }
        if let Value::Object(o) = container {
            match o.as_ref() {
                Object::DictView(ViewKind::Keys, d) => {
                    let k = self.hashable(item)?;
                    return Ok(d.borrow().contains_key(&k));
                }
                Object::DictView(ViewKind::Items, d) => {
                    let Value::Tuple(pair) = item else { return Ok(false) };
                    let [k, v] = pair.as_slice() else { return Ok(false) };
                    let k = self.hashable(k)?;
                    let found = d.borrow().get(&k).cloned();
                    return match found {
                        Some(w) => Ok(py_identical(&w, v) || self.eq(&w, v)?),
                        None => Ok(false),
                    };
                }
                Object::DictView(ViewKind::Values, d) => {
                    let values: Vec<Value> = d.borrow().values().cloned().collect();
                    for v in &values {
                        if py_identical(v, item) || self.eq(v, item)? {
                            return Ok(true);
                        }
                    }
                    return Ok(false);
                }
                Object::Range(a, b, c) => {
                    // Worked out, not counted: `10**12 in range(10**15)` is instant.
                    return Ok(match item {
                        Value::Int(_) | Value::Bool(_) => {
                            let x = item.as_int().unwrap_or(0);
                            let n = range_len(*a, *b, *c);
                            let off = x as i128 - *a as i128;
                            off % *c as i128 == 0 && (0..n as i128).contains(&(off / *c as i128))
                        }
                        Value::Float(f) => f.fract() == 0.0 && self.contains(container, &Value::Int(*f as i64))?,
                        _ => false,
                    });
                }
                Object::BigRange(r) => {
                    let x = match item {
                        Value::Float(f) if f.is_finite() && f.fract() == 0.0 => <num_bigint::BigInt as num_traits::FromPrimitive>::from_f64(*f),
                        other => super::bigint::to_big(other),
                    };
                    return Ok(x.is_some_and(|x| r.position(&x).is_some()));
                }
                Object::Generator(_) | Object::Iterator(_) => {
                    // Consumes the iterator up to the first match, as Python does.
                    let mut it = Iter::Object(o.clone());
                    while let Some(v) = self.next_item(&mut it)? {
                        if self.eq(&v, item)? {
                            return Ok(true);
                        }
                    }
                    return Ok(false);
                }
                _ => {}
            }
        }
        Ok(match container {
            Value::Str(s) => {
                let Value::Str(i) = item else {
                    return Err(self.error("'in <string>' requires string as left operand", "TypeError"));
                };
                i.is_empty() || s.contains(i.as_ref())
            }
            Value::List(l) => {
                let items = l.borrow().clone();
                self.position_eq(&items, item, 0)?.is_some()
            }
            Value::Tuple(t) => self.position_eq(t, item, 0)?.is_some(),
            Value::Set(_) | Value::Dict(_) => {
                // An unhashable item is in no set or dict.
                let key = match item {
                    Value::Instance(_) | Value::Tuple(_) => Some(self.hashable(item)?),
                    other => HKey::from_value(other),
                };
                match (container, key) {
                    (Value::Set(s), Some(k)) => s.borrow().contains(&k),
                    (Value::Dict(d), Some(k)) => d.borrow().contains_key(&k),
                    _ => false,
                }
            }
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
            Value::Str(s) if plain_ascii(s) => s.bytes().map(|b| self.ascii_char(b)).collect(),
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
                Object::Iterator(_) | Object::Generator(_) => {
                    let mut it = Iter::Object(o.clone());
                    return self.collect_iter(&mut it, "iterator");
                }
                Object::DictView(k, d) => return Ok(view_items(*k, d)),
                Object::Range(a, b, c) => {
                    if range_len(*a, *b, *c) as u64 > self.max_container as u64 {
                        return Err(self.container_abort("range"));
                    }
                    let mut it = Iter::Range { next: Some(*a), stop: *b, step: *c };
                    return self.collect_iter(&mut it, "range");
                }
                Object::BigRange(r) => {
                    if r.len() > num_bigint::BigInt::from(self.max_container) {
                        return Err(self.container_abort("range"));
                    }
                    let mut it = Iter::BigRange(Box::new((r.start.clone(), r.stop.clone(), r.step.clone())));
                    return self.collect_iter(&mut it, "range");
                }
                _ => return Err(self.error(format!("'{}' object is not iterable", value.type_name()), "TypeError")),
            },
            _ => return Err(self.error(format!("'{}' object is not iterable", value.type_name()), "TypeError")),
        })
    }

    /// What a `for` loop or a comprehension clause walks over [iterable],
    /// item by item: `range(...)` written in place counts without making a
    /// range object, a list or tuple nothing else holds is not copied, and a
    /// generator or iterator runs only as far as the loop goes.
    pub(crate) fn loop_items(&mut self, iterable: &Expr, env: &Rc<Env>) -> R<Iter> {
        if let Some(it) = self.loop_range(iterable, env)? {
            return Ok(it);
        }
        let it = self.eval(iterable, env)?;
        Ok(match it {
            Value::List(l) => Iter::Items(match Rc::try_unwrap(l) {
                Ok(cell) => cell.into_inner(),
                Err(l) => l.borrow().clone(),
            }.into_iter()),
            Value::Tuple(t) => Iter::Items(Rc::try_unwrap(t).unwrap_or_else(|t| t.as_ref().clone()).into_iter()),
            other => self.iter_of(&other)?,
        })
    }

    /// What a loop over `range(...)` counts, when [iterable] is a call of
    /// the builtin range, charged the steps evaluating the call would be.
    fn loop_range(&mut self, iterable: &Expr, env: &Rc<Env>) -> R<Option<Iter>> {
        let Expr::Call(callee, args, kwargs) = iterable else { return Ok(None) };
        let Expr::Name(n) = callee.as_ref() else { return Ok(None) };
        if n.id.as_ref() != "range" || !kwargs.is_empty() || !(1..=3).contains(&args.len()) || args.iter().any(|a| matches!(a, Expr::Starred(_))) {
            return Ok(None);
        }
        match self.load_name(n, env)? {
            Value::Builtin(b) if b.as_ref() == "range" => {}
            _ => return Ok(None),
        }
        self.tick()?;
        self.tick()?;
        let mut values = Vec::with_capacity(args.len());
        for a in args {
            values.push(self.eval(a, env)?);
        }
        let mut nums = [0i64; 3];
        for (slot, v) in nums.iter_mut().zip(&values) {
            match v {
                Value::Int(i) => *slot = *i,
                Value::Bool(b) => *slot = *b as i64,
                // Big ints and `__index__`: the range builtin sorts them out.
                _ => {
                    let r = self.call_builtin("range", &values, &[])?;
                    return self.iter_of(&r).map(Some);
                }
            }
        }
        let (start, stop, step) = match args.len() {
            1 => (0, nums[0], 1),
            2 => (nums[0], nums[1], 1),
            _ => (nums[0], nums[1], nums[2]),
        };
        if step == 0 {
            return Err(self.error("range() arg 3 must not be zero", "ValueError"));
        }
        Ok(Some(Iter::Range { next: Some(start), stop, step }))
    }

    pub fn list_index(&self, idx: &Value, count: usize) -> R<usize> {
        let raw = idx.as_int().ok_or_else(|| self.error("indices must be integers", "TypeError"))?;
        let i = if raw < 0 { raw + count as i64 } else { raw };
        if i < 0 || i >= count as i64 {
            return Err(self.error("index out of range", "IndexError"));
        }
        Ok(i as usize)
    }

    /// [list_index] with CPython's message, which names the type: "list index out of range".
    fn seq_index(&self, idx: &Value, count: usize, what: &str) -> R<usize> {
        match self.list_index(idx, count) {
            Err(Exc::Error(e)) if e.kind == "IndexError" => Err(self.error(format!("{what} index out of range"), "IndexError")),
            other => other,
        }
    }

    pub(crate) fn index(&mut self, base: &Value, idx: &Value) -> R<Value> {
        // `items[slice(1, 3)]` is `items[1:3]`.
        if let (Value::Object(o), false) = (idx, matches!(base, Value::Instance(_) | Value::Dict(_))) {
            if let Object::Slice(a, b, c) = o.as_ref() {
                return self.slice(base, Some(a), Some(b), Some(c));
            }
        }
        // `items[n]` for an object with `__index__`.
        if let (Value::Instance(_), false) = (idx, matches!(base, Value::Instance(_) | Value::Dict(_))) {
            if let Some(i) = self.index_of_instance(idx)? {
                return self.index(base, &Value::Int(i));
            }
        }
        match base {
            Value::Str(s) if s.len() < 64 && plain_ascii(s) => {
                let i = self.seq_index(idx, s.len(), "string")?;
                Ok(self.ascii_char(s.as_bytes()[i]))
            }
            Value::Str(s) => {
                let b = self.chars(s);
                let i = self.seq_index(idx, b.len() - 1, "string")?;
                Ok(Value::str(&s[b[i] as usize..b[i + 1] as usize]))
            }
            Value::List(l) => {
                let n = l.borrow().len();
                let i = self.seq_index(idx, n, "list")?;
                Ok(l.borrow()[i].clone())
            }
            Value::Tuple(t) => {
                let i = self.seq_index(idx, t.len(), "tuple")?;
                Ok(t[i].clone())
            }
            Value::Bytes(b) => {
                let i = self.list_index(idx, b.len())?;
                Ok(Value::Int(b[i] as i64))
            }
            Value::Object(o) if matches!(o.as_ref(), Object::BigRange(_)) => {
                let Object::BigRange(r) = o.as_ref() else { unreachable!() };
                let n = r.len();
                let i = super::bigint::to_big(idx).ok_or_else(|| self.error("range indices must be integers or slices", "TypeError"))?;
                let i = if num_traits::Signed::is_negative(&i) { i + &n } else { i };
                if num_traits::Signed::is_negative(&i) || i >= n {
                    return Err(self.error("range object index out of range", "IndexError"));
                }
                Ok(super::bigint::int_value(r.at(&i)))
            }
            Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) => {
                let Object::Range(a, b, c) = o.as_ref() else { unreachable!() };
                let n = range_len(*a, *b, *c);
                if matches!(idx, Value::Big(_)) {
                    return Err(self.error("range object index out of range", "IndexError"));
                }
                let i = idx.as_int().ok_or_else(|| self.error("range indices must be integers or slices", "TypeError"))?;
                let i = if i < 0 { i + n } else { i };
                if i < 0 || i >= n {
                    return Err(self.error("range object index out of range", "IndexError"));
                }
                Ok(Value::Int(a + i * c))
            }
            Value::Dict(d) => {
                let key = self.hashable(idx)?;
                let v = d.borrow().get(&key).cloned();
                match v {
                    Some(v) => Ok(v),
                    None => Err(self.key_error(idx)),
                }
            }
            Value::Match(m) => {
                let group = match idx {
                    Value::Str(name) => m.names.get(name.as_ref()).map(|i| *i as i64),
                    other => other.as_int(),
                };
                // A group the pattern has but the match skipped is None, as m.group() gives.
                match group.filter(|i| *i >= 0 && (*i as usize) < m.spans.len().max(1)) {
                    Some(i) => Ok(m.text(i as usize).map(|t| m.subject.piece(&t)).unwrap_or(Value::None)),
                    None => Err(self.error("no such group", "IndexError")),
                }
            }
            Value::Class(c) => {
                let c = c.clone();
                self.class_getitem(&c, idx)
            }
            // `list[int]`, `dict[str, int]`: a GenericAlias, as in Python 3.9 on.
            Value::Builtin(n) if super::builtins::is_type_name(n) => {
                let alias = self.python_builtin("GenericAlias")?;
                self.call_value(&alias, vec![base.clone(), idx.clone()], Vec::new())
            }
            Value::Instance(_) => match self.dunder(base, "__getitem__", vec![idx.clone()])? {
                Some(v) => Ok(v),
                None => Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
            },
            _ => Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
        }
    }

    /// A slice bound: None, an int, or an object's `__index__`.
    fn slice_arg(&mut self, v: Option<&Value>) -> R<Option<i64>> {
        match v {
            None | Some(Value::None) => Ok(None),
            Some(Value::Int(i)) => Ok(Some(*i)),
            Some(Value::Bool(b)) => Ok(Some(*b as i64)),
            Some(other) => match self.index_of_instance(other)? {
                Some(i) => Ok(Some(i)),
                None => Err(self.error("slice indices must be integers or None or have an __index__ method", "TypeError")),
            },
        }
    }

    fn slice(&mut self, base: &Value, lo: Option<&Value>, hi: Option<&Value>, st: Option<&Value>) -> R<Value> {
        let step = self.slice_arg(st)?.unwrap_or(1);
        if step == 0 {
            return Err(self.error("slice step cannot be zero", "ValueError"));
        }
        let l = self.slice_arg(lo)?;
        let h = self.slice_arg(hi)?;
        let indices = |n: usize| slice_indices(n as i64, l, h, step);
        Ok(match base {
            Value::Str(s) if plain_ascii(s) => {
                let bytes = s.as_bytes();
                let picked: Vec<u8> = indices(bytes.len()).into_iter().map(|i| bytes[i]).collect();
                // ASCII bytes are their own characters.
                Value::str(std::str::from_utf8(&picked).unwrap_or_default())
            }
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
            // A slice of a range is a range.
            Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) => {
                let Object::Range(a, b, c) = o.as_ref() else { unreachable!() };
                let n = range_len(*a, *b, *c);
                let clamp = |v: i64, lo: i64, hi: i64| v.clamp(lo, hi);
                let (lo, hi) = if step > 0 { (0, n) } else { (-1, n - 1) };
                let pos = |v: Option<i64>, default: i64| match v {
                    None => default,
                    Some(v) if v < 0 => clamp(v + n, lo, hi),
                    Some(v) => clamp(v, lo, hi),
                };
                let start = pos(l, if step > 0 { 0 } else { n - 1 });
                let stop = pos(h, if step > 0 { n } else { -1 });
                Value::Object(Rc::new(Object::Range(a + start * c, a + stop * c, c * step)))
            }
            Value::Object(o) if matches!(o.as_ref(), Object::BigRange(_)) => {
                use num_bigint::BigInt;
                let Object::BigRange(r) = o.as_ref() else { unreachable!() };
                let n = r.len();
                let (lo, hi) = if step > 0 { (BigInt::from(0), n.clone()) } else { (BigInt::from(-1), &n - 1) };
                let pos = |v: Option<i64>, default: BigInt| match v {
                    None => default,
                    Some(v) if v < 0 => (BigInt::from(v) + &n).clamp(lo.clone(), hi.clone()),
                    Some(v) => BigInt::from(v).clamp(lo.clone(), hi.clone()),
                };
                let start = pos(l, if step > 0 { BigInt::from(0) } else { &n - 1 });
                let stop = pos(h, if step > 0 { n.clone() } else { BigInt::from(-1) });
                super::bigint::range_value(r.at(&start), r.at(&stop), &r.step * step)
            }
            _ => return Err(self.error(format!("'{}' object is not subscriptable", base.type_name()), "TypeError")),
        })
    }

    /// `eval(source[, globals[, locals]])` and `exec(...)`: run code in this
    /// interpreter, on the same budget and with the same reach. With no
    /// dicts it sees the caller's module and, inside a function, a copy of
    /// the function's variables (what it binds there stays in that copy, as
    /// in CPython); with them it runs over those dicts and writes back to them.
    pub(crate) fn eval_exec(&mut self, name: &str, args: &[Value], env: Option<&Rc<Env>>) -> R<Value> {
        // A code object compile() made runs its source: eval() of one made
        // for "exec" runs the statements and gives None, as CPython's does.
        if let Some(code @ Value::Instance(i)) = args.first() {
            if i.class.name.as_ref() == "code" {
                if let Some(Value::Str(source)) = self.optional_attribute(code, "_source")? {
                    let mode = self.optional_attribute(code, "_mode")?;
                    let run = if mode.as_ref().and_then(Value::as_str) == Some("eval") { name } else { "exec" };
                    let mut rest = args.to_vec();
                    rest[0] = Value::Str(source);
                    let out = self.eval_exec(run, &rest, env)?;
                    return Ok(if name == "eval" { out } else { Value::None });
                }
            }
        }
        let source: Rc<str> = match args.first() {
            Some(Value::Str(s)) => s.clone(),
            Some(Value::Bytes(b)) => Rc::from(String::from_utf8_lossy(b).as_ref()),
            Some(other) => return Err(self.error(format!("{name}() arg 1 must be a string, bytes or code object, not {}", other.type_name()), "TypeError")),
            None => return Err(self.error(format!("{name} expected at least 1 argument, got 0"), "TypeError")),
        };
        let dict_arg = |i: usize, what: &str| -> R<Option<DictRef>> {
            match args.get(i) {
                None | Some(Value::None) => Ok(None),
                Some(Value::Dict(d)) => Ok(Some(d.clone())),
                Some(other) => Err(err(format!("{name}() {what} must be a dict, not {}", other.type_name()), "TypeError", self.current_line)),
            }
        };
        let globals = dict_arg(1, "globals")?;
        let locals = dict_arg(2, "locals")?;
        let caller = env.cloned().unwrap_or_else(|| self.globals.clone());
        let in_function = caller.parent.is_some();
        let namespace = locals.is_some() || (globals.is_none() && in_function);
        let program = super::parser::parse_dynamic(&source, namespace).map_err(syntax_error)?;
        if name == "eval" && !matches!(program.as_slice(), [Stmt { kind: StmtKind::Expr(_), .. }]) {
            return Err(self.error("invalid syntax: eval() takes an expression", "SyntaxError"));
        }
        // The module it runs in: one made from `globals`, or the caller's.
        let module = match &globals {
            Some(d) => {
                let m = Env::new(None);
                for (k, v) in d.borrow().iter() {
                    if let HKey::Str(k) = k {
                        m.define(k, v.clone());
                    }
                }
                self.closures.push(Rc::downgrade(&m));
                m
            }
            None => {
                let mut m = caller.clone();
                while let Some(p) = m.parent.clone() {
                    m = p;
                }
                m
            }
        };
        let run_in = if namespace {
            let ns = Env::new(Some(module.clone()));
            match &locals {
                Some(d) => {
                    for (k, v) in d.borrow().iter() {
                        if let HKey::Str(k) = k {
                            ns.define(k, v.clone());
                        }
                    }
                }
                None => {
                    // The calling function's variables, by name.
                    if let Some((f, frame)) = self.frames.last() {
                        if Rc::ptr_eq(frame, &caller) {
                            let names = f.frame_info().names.clone();
                            let slots = caller.slots.borrow().clone();
                            for (k, v) in names.iter().zip(slots) {
                                if let Some(v) = v {
                                    ns.define(k, v);
                                }
                            }
                        }
                    }
                }
            }
            self.closures.push(Rc::downgrade(&ns));
            ns
        } else {
            module.clone()
        };
        let saved_line = self.current_line;
        let result = match program.as_slice() {
            [Stmt { kind: StmtKind::Expr(e), line, .. }] if name == "eval" => {
                self.current_line = *line;
                self.eval(e, &run_in)
            }
            _ => self.exec_block(&program, &run_in).map(|_| Value::None),
        };
        self.current_line = saved_line;
        if let Some(d) = &globals {
            let mut d = d.borrow_mut();
            for (k, v) in module.vars.borrow().pairs() {
                d.insert(HKey::Str(k), v);
            }
        }
        if let Some(d) = &locals {
            let mut d = d.borrow_mut();
            for (k, v) in run_in.vars.borrow().pairs() {
                d.insert(HKey::Str(k), v);
            }
        }
        match result {
            Err(Exc::Return(_)) => Err(self.error("'return' outside function", "SyntaxError")),
            other => other,
        }
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
    dict: DictMap,
    set: KeySet,
}

/// The built-in exception a built-in exception derives from, as CPython's
/// hierarchy has it; None for BaseException. A kind this table does not
/// name (one a host raises) is an Exception, or a Warning by its name.
pub(crate) fn exception_parent(kind: &str) -> Option<&'static str> {
    Some(match kind {
        "BaseException" => return None,
        "SystemExit" | "KeyboardInterrupt" | "GeneratorExit" | "Exception" | "BaseExceptionGroup" => "BaseException",
        "FloatingPointError" | "OverflowError" | "ZeroDivisionError" => "ArithmeticError",
        "ModuleNotFoundError" => "ImportError",
        "IndexError" | "KeyError" => "LookupError",
        "UnboundLocalError" => "NameError",
        "BlockingIOError" | "ChildProcessError" | "ConnectionError" | "FileExistsError" | "FileNotFoundError" | "InterruptedError"
        | "IsADirectoryError" | "NotADirectoryError" | "PermissionError" | "ProcessLookupError" | "TimeoutError" => "OSError",
        "BrokenPipeError" | "ConnectionAbortedError" | "ConnectionRefusedError" | "ConnectionResetError" => "ConnectionError",
        "NotImplementedError" | "PythonFinalizationError" | "RecursionError" => "RuntimeError",
        "IndentationError" => "SyntaxError",
        "TabError" => "IndentationError",
        "UnicodeError" => "ValueError",
        "UnicodeDecodeError" | "UnicodeEncodeError" | "UnicodeTranslateError" => "UnicodeError",
        "Warning" => "Exception",
        _ if kind.ends_with("Warning") => "Warning",
        _ => "Exception",
    })
}

/// The module an import error names: `no module named 'x'` is about x.
pub(crate) fn import_error_name(message: &str) -> Value {
    message
        .strip_prefix("no module named '")
        .or_else(|| message.strip_prefix("No module named '"))
        .and_then(|rest| rest.split_once('\''))
        .map_or(Value::None, |(name, _)| Value::str(name))
}

/// A parse error in source a script hands over (eval, exec, compile) is a
/// SyntaxError it can catch; the parser's own errors carry the default kind.
pub(crate) fn syntax_error(mut e: PyError) -> Exc {
    if e.abort.is_none() && e.kind == "RuntimeError" {
        e.kind = "SyntaxError".into();
    }
    Exc::from(e)
}

/// Whether `except clause` catches an exception of built-in [kind]: [kind]
/// is [clause] or derives from it.
pub(crate) fn exception_matches(clause: &str, kind: &str) -> bool {
    let mut k = kind;
    loop {
        if k == clause {
            return true;
        }
        match exception_parent(k) {
            Some(p) => k = p,
            None => return false,
        }
    }
}

/// The function `inst.name(...)` calls, when it is a plain one found on the
/// class (not shadowed by an instance attribute) with only positional parameters.
#[inline]
fn plain_method(inst: &Rc<Instance>, name: &str) -> Option<Rc<Function>> {
    if inst.has(name) {
        return None;
    }
    match inst.class.lookup(name) {
        Some(Value::Function(f)) if f.signature().simple() => Some(f),
        _ => None,
    }
}

/// The `__name__` of the module whose top level [f] closes over.
impl Interp {
    /// `sys._getframemodulename(depth)`: the module of the call [depth]
    /// frames out from the one asking, counting a module's top level as a
    /// frame, as CPython does; the main program is the outermost.
    pub(crate) fn frame_module_name(&self, depth: usize) -> Option<String> {
        let mut stack: Vec<String> = Vec::new();
        let n = self.frames.len();
        let mut run = self.module_runs.len();
        for at in (0..=n).rev() {
            if at < n {
                stack.push(function_module(&self.frames[at].0));
            }
            while run > 0 && self.module_runs[run - 1].0 == at {
                stack.push(self.module_runs[run - 1].1.to_string());
                run -= 1;
            }
            if stack.len() > depth {
                return stack.into_iter().nth(depth);
            }
        }
        let main = match self.globals.vars.borrow().get("__name__") {
            Some(Value::Str(s)) => s.to_string(),
            _ => "__main__".to_string(),
        };
        stack.push(main);
        stack.into_iter().nth(depth)
    }
}

pub(crate) fn function_module(f: &Function) -> String {
    match f.closure.module().vars.borrow().get("__name__") {
        Some(Value::Str(s)) => s.to_string(),
        _ => "__main__".to_string(),
    }
}

impl Interp {
    /// Add the frame of [function] in [module] to an error leaving it: on the
    /// line it was raised for the first frame, else on the line the call it
    /// came out of was made. [caller_line] is where this call was made.
    pub(crate) fn note_frame(&self, e: &mut PyError, function: &str, module: &str, caller_line: u32) {
        let line = if e.frames.is_empty() && e.caller_line.is_none() {
            e.line.or(Some(self.current_line))
        } else {
            e.caller_line.or(Some(self.current_line))
        }
        .filter(|l| *l > 0);
        let file = self.module_files.get(module).cloned();
        if e.frames.is_empty() && e.file.is_none() {
            e.file = file.clone();
            // An error from native code has no line yet: the frame's is where it was raised.
            if e.line.is_none() {
                e.line = line;
            }
        }
        if e.frames.len() < super::error::MAX_FRAMES {
            e.frames.insert(0, super::error::Frame { file, line, function: function.to_string() });
        }
        e.caller_line = Some(caller_line);
    }

    /// The function a statement in [env] runs in, and its module: `<module>`
    /// at a module's top level.
    pub(crate) fn running_function(&self, env: &Rc<Env>) -> (String, String) {
        let module = |e: &Env| match e.module().vars.borrow().get("__name__") {
            Some(Value::Str(s)) => s.to_string(),
            _ => "__main__".to_string(),
        };
        if env.parent.is_none() {
            return ("<module>".to_string(), module(env));
        }
        match self.frames.last() {
            Some((f, _)) => (f.display_name().to_string(), function_module(f)),
            None => ("<module>".to_string(), module(env)),
        }
    }
}

/// Where an error an `except` caught came from, kept while its handler runs
/// so re-raising it keeps its traceback.
#[derive(Debug)]
pub(crate) struct Trace {
    line: Option<u32>,
    file: Option<String>,
    frames: Vec<super::error::Frame>,
    caller_line: Option<u32>,
}

impl Trace {
    pub(crate) fn of(e: &PyError) -> Rc<Trace> {
        Rc::new(Trace { line: e.line, file: e.file.clone(), frames: e.frames.clone(), caller_line: e.caller_line })
    }

    /// Give [e], raised again, the place and calls the caught error had.
    pub(crate) fn restore(&self, e: &mut PyError) {
        if self.line.is_some() {
            e.line = self.line;
        }
        e.file = self.file.clone();
        e.frames = self.frames.clone();
        e.caller_line = self.caller_line;
    }
}

/// How a call error names [f]: `C.m` for a method defined in a class body,
/// as CPython's qualified name does, or its own name.
fn call_name(f: &Function) -> String {
    if let Some(Value::Str(q)) = f.attr("__qualname__") {
        return q.to_string();
    }
    match f.owner.borrow().as_ref().and_then(|w| w.upgrade()) {
        Some(class) => format!("{}.{}", class.qualname(), f.name),
        None => f.name.to_string(),
    }
}

/// "1 positional argument", "2 positional arguments".
fn plural(n: usize, noun: &str) -> String {
    format!("{n} {noun}{}", if n == 1 { "" } else { "s" })
}

/// 'a'; 'a' and 'b'; 'a', 'b', and 'c': how CPython lists missing arguments.
fn name_list(names: &[&str]) -> String {
    let quoted: Vec<String> = names.iter().map(|n| format!("'{n}'")).collect();
    match quoted.len() {
        0 | 1 => quoted.concat(),
        2 => format!("{} and {}", quoted[0], quoted[1]),
        n => format!("{}, and {}", quoted[..n - 1].join(", "), quoted[n - 1]),
    }
}

/// ASCII with no carriage return: each byte is one grapheme cluster
/// (`\r\n` is one cluster of two bytes).
pub(crate) fn plain_ascii(s: &str) -> bool {
    s.is_ascii() && !s.as_bytes().contains(&b'\r')
}

fn numeric(d: f64, as_int: bool) -> Value {
    if as_int && d == d.round_ties_even() && d.abs() < 9.007e15 {
        return Value::Int(d as i64);
    }
    Value::Float(d)
}

/// A type, or None, as the operand of a `|` that makes a union.
fn type_like(v: &Value) -> bool {
    match v {
        Value::Builtin(n) => super::builtins::is_type_name(n),
        Value::Class(_) | Value::None => true,
        _ => false,
    }
}

pub(crate) fn op_symbol(op: BinOp) -> &'static str {
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
        BinOp::LShift => "<<",
        BinOp::RShift => ">>",
        BinOp::MatMul => "@",
    }
}

/// `|` union, `&` intersection, `-` difference, `^` symmetric difference.
pub(crate) fn set_algebra(op: BinOp, a: &KeySet, b: &KeySet) -> Option<Value> {
    let mut out = KeySet::default();
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

/// `x op y` for two ints when it fits in 64 bits and cannot raise: the
/// arithmetic loops do most. None sends the pair down the general path.
#[inline]
fn int_quick(op: BinOp, x: i64, y: i64) -> Option<i64> {
    match op {
        BinOp::Add => x.checked_add(y),
        BinOp::Sub => x.checked_sub(y),
        BinOp::Mul => x.checked_mul(y),
        // Python's `%` takes the divisor's sign: for a positive divisor that
        // is the Euclidean remainder.
        BinOp::Mod if y > 0 => Some(x.rem_euclid(y)),
        BinOp::FloorDiv if y > 0 => Some(x.div_euclid(y)),
        BinOp::BitAnd => Some(x & y),
        BinOp::BitOr => Some(x | y),
        BinOp::BitXor => Some(x ^ y),
        _ => None,
    }
}

/// The main script's module scope, `__name__` already `"__main__"`.
fn main_globals() -> Rc<Env> {
    let env = Env::new(None);
    env.define(&Rc::from("__name__"), Value::str("__main__"));
    // A script run as a file has neither, as in CPython.
    env.define(&Rc::from("__spec__"), Value::None);
    env.define(&Rc::from("__package__"), Value::None);
    env
}

/// The docstring a body opens with, or None.
pub(crate) fn docstring(body: &[Stmt]) -> Value {
    match body.first().map(|s| &s.kind) {
        Some(StmtKind::Expr(Expr::Str(s))) => Value::Str(s.clone()),
        _ => Value::None,
    }
}

/// A call's keyword arguments, in the order written.
pub(crate) type Keywords = Vec<(Rc<str>, Value)>;
