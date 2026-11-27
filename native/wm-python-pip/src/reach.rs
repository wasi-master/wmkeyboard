//! Code that never runs in the keyboard, found before the checks so they
//! leave it alone, and how each module of a package gets loaded.
//!
//! Dead code: branches on the Python version or the platform that come out
//! the other way here (`if PY2:`, `if sys.platform == "win32":`,
//! `if sys.version_info >= (3, 14):`), and the `except ImportError:` fallback
//! of an import that works. A test may go through a name the module sets
//! (`PY2 = sys.version_info[0] == 2`) or imports from another module of the
//! package (`from ._compat import WIN`).
//!
//! Load modes: a module that only dead code imports never loads. One imported
//! only under `try: ... except ImportError:` can fail to import and nothing
//! notices. One imported only inside functions, or that nothing imports and
//! is private, a test or a command line, loads only when that code runs: what
//! fails in it fails that feature, not the package.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use wm_python_tooling::ast::{Expr, ExprKind, Stmt, StmtKind};
use wm_python_tooling::text::Span;

use crate::probe::Probe;
use crate::program::{resolve, ModFile, Program};

/// What the keyboard answers for the values branch tests read, asked of the
/// interpreter once.
#[derive(Clone, Debug)]
pub(crate) struct Host {
    pub version: Option<(i64, i64, i64)>,
    pub platform: Option<String>,
    pub os_name: Option<String>,
    /// `module.name` for the members packages test with `hasattr`, and whether each is there.
    pub members: HashMap<String, bool>,
}

/// What packages ask `hasattr` about to tell one Python from another.
const ASKED: &[(&str, &[&str])] = &[
    (
        "sys",
        &[
            "pypy_version_info", "getwindowsversion", "_getframe", "getrefcount", "implementation", "set_int_max_str_digits",
            "get_int_max_str_digits", "_is_gil_enabled", "getsizeof", "setrecursionlimit", "getrecursionlimit", "exception",
            "monitoring", "settrace", "gettrace", "setprofile", "is_finalizing", "orig_argv", "stdlib_module_names",
            "builtin_module_names", "flags", "float_info", "int_info", "hash_info", "abiflags", "frozen", "ps1", "base_prefix",
            "real_prefix", "gettotalrefcount", "audit", "addaudithook", "unraisablehook", "_current_frames", "activate_stack_trampoline",
        ],
    ),
    ("os", &["fork", "register_at_fork", "add_dll_directory", "getuid", "geteuid", "sched_getaffinity", "process_cpu_count", "cpu_count", "fspath", "PathLike", "getpid", "symlink", "O_BINARY"]),
    ("builtins", &["ExceptionGroup", "BaseExceptionGroup", "aiter", "anext", "breakpoint", "EncodingWarning"]),
    ("typing", &["Self", "TypeAlias", "ParamSpec", "TypeVarTuple", "Unpack", "override", "TypeIs", "ReadOnly", "NoDefault", "get_overloads", "dataclass_transform", "reveal_type", "LiteralString", "Never", "assert_never", "Required", "NotRequired", "Annotated", "TypeGuard"]),
];

impl Host {
    /// The answers for this build, worked out once per process.
    pub fn get() -> &'static Host {
        static HOST: OnceLock<Host> = OnceLock::new();
        HOST.get_or_init(|| Host::of(&mut Probe::new()))
    }

    fn of(probe: &mut Probe) -> Host {
        let mut test = |src: String| probe.source_runs(&src);
        let minor = (0..=40).find(|m| test(format!("import sys\nif sys.version_info[:2] != (3, {m}):\n    raise ValueError('v')\n")));
        let version = minor.and_then(|m| {
            (0..=40)
                .find(|u| test(format!("import sys\nif sys.version_info[:3] != (3, {m}, {u}):\n    raise ValueError('v')\n")))
                .map(|u| (3, m, u))
        });
        let platforms = ["android", "linux", "darwin", "win32", "cygwin", "emscripten", "wasi", "ios", "freebsd"];
        let platform = platforms
            .iter()
            .find(|p| test(format!("import sys\nif sys.platform != '{p}':\n    raise ValueError('p')\n")))
            .map(|p| p.to_string());
        let os_name = ["posix", "nt", "java"]
            .iter()
            .find(|n| test(format!("import os\nif os.name != '{n}':\n    raise ValueError('n')\n")))
            .map(|n| n.to_string());
        let mut members = HashMap::new();
        for (module, names) in ASKED {
            for name in *names {
                members.insert(format!("{module}.{name}"), probe.member_exists(module, name));
            }
        }
        Host { version, platform, os_name, members }
    }

    fn value(&self, qualified: &str) -> Option<Val> {
        let (major, minor, micro) = self.version.unwrap_or((-1, -1, -1));
        let known = self.version.is_some();
        Some(match qualified {
            "sys.version_info" if known => {
                Val::Tuple(vec![Val::Int(major), Val::Int(minor), Val::Int(micro), Val::Str("final".into()), Val::Int(0)])
            }
            "sys.version_info.major" if known => Val::Int(major),
            "sys.version_info.minor" if known => Val::Int(minor),
            "sys.version_info.micro" if known => Val::Int(micro),
            "sys.version_info.releaselevel" if known => Val::Str("final".into()),
            "sys.platform" => Val::Str(self.platform.clone()?),
            "os.name" => Val::Str(self.os_name.clone()?),
            "typing.TYPE_CHECKING" | "typing_extensions.TYPE_CHECKING" => Val::Bool(false),
            _ => return None,
        })
    }
}

/// A value a branch test can be worked out to.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Val {
    None,
    Bool(bool),
    Int(i64),
    Str(String),
    Tuple(Vec<Val>),
}

impl Val {
    fn truthy(&self) -> bool {
        match self {
            Val::None => false,
            Val::Bool(b) => *b,
            Val::Int(i) => *i != 0,
            Val::Str(s) => !s.is_empty(),
            Val::Tuple(t) => !t.is_empty(),
        }
    }

    fn number(&self) -> Option<i64> {
        match self {
            Val::Bool(b) => Some(*b as i64),
            Val::Int(i) => Some(*i),
            _ => None,
        }
    }

    fn compare(&self, other: &Val) -> Option<Ordering> {
        if let (Some(a), Some(b)) = (self.number(), other.number()) {
            return Some(a.cmp(&b));
        }
        match (self, other) {
            (Val::Str(a), Val::Str(b)) => Some(a.cmp(b)),
            (Val::Tuple(a), Val::Tuple(b)) => {
                for (x, y) in a.iter().zip(b) {
                    match x.compare(y)? {
                        Ordering::Equal => continue,
                        o => return Some(o),
                    }
                }
                Some(a.len().cmp(&b.len()))
            }
            (Val::None, Val::None) => Some(Ordering::Equal),
            _ => None,
        }
    }

    fn equals(&self, other: &Val) -> Option<bool> {
        match self.compare(other) {
            Some(o) => Some(o == Ordering::Equal),
            // Unlike kinds (a string and a number) are never equal.
            None => matches!((self, other), (Val::Str(_), Val::Int(_) | Val::Bool(_) | Val::Tuple(_) | Val::None) | (Val::Int(_) | Val::Bool(_) | Val::Tuple(_) | Val::None, Val::Str(_))).then_some(false),
        }
    }

    fn contains(&self, item: &Val) -> Option<bool> {
        match (self, item) {
            (Val::Str(s), Val::Str(i)) => Some(s.contains(i.as_str())),
            (Val::Tuple(t), _) => {
                let mut unknown = false;
                for x in t {
                    match x.equals(item) {
                        Some(true) => return Some(true),
                        Some(false) => {}
                        None => unknown = true,
                    }
                }
                (!unknown).then_some(false)
            }
            _ => None,
        }
    }
}

/// The names each module sets to a value a test can use, by module name.
pub(crate) type Consts = HashMap<String, HashMap<String, Val>>;

/// Works out what [file]'s expressions say in the keyboard.
pub(crate) struct Eval<'a> {
    pub file: &'a ModFile,
    pub host: &'a Host,
    pub consts: &'a Consts,
    /// While the module's own constants are being worked out: what its names
    /// hold at this point. Afterwards, None, and [consts] has them.
    pub local: Option<&'a State>,
}

fn is_package(file: &ModFile) -> bool {
    file.path.ends_with("/__init__.py")
}

impl Eval<'_> {
    /// The dotted name [e] stands for when it reaches a module or a member of
    /// one: `sys.version_info`, `pkg._compat.WIN`.
    fn qualified(&self, e: &Expr) -> Option<String> {
        match &e.kind {
            ExprKind::Name(_) => {
                let symbol = self.file.source.doc.analysis.symbol_at(e.span.start)?;
                let (module, member) = symbol.import.as_ref()?;
                if !symbol.writes.is_empty() {
                    return None;
                }
                let module = resolve(module, &self.file.name, is_package(self.file))?;
                Some(match member {
                    Some(m) => format!("{module}.{m}"),
                    None => module,
                })
            }
            ExprKind::Attribute { value, attr, .. } => Some(format!("{}.{attr}", self.qualified(value)?)),
            _ => None,
        }
    }

    /// The value of a dotted name: the host's, or a constant of a package module.
    fn named(&self, qualified: &str) -> Option<Val> {
        if let Some(v) = self.host.value(qualified) {
            return Some(v);
        }
        let (module, name) = qualified.rsplit_once('.')?;
        self.consts.get(module)?.get(name).cloned()
    }

    pub fn eval(&self, e: &Expr) -> Option<Val> {
        match &e.kind {
            ExprKind::Const(c) => Some(match *c {
                "True" => Val::Bool(true),
                "False" => Val::Bool(false),
                _ => Val::None,
            }),
            ExprKind::Number => number(&self.file.source.text(e.span)),
            ExprKind::Str { value: Some(v), fstring: false, .. } => Some(Val::Str(v.clone())),
            ExprKind::Tuple(items) | ExprKind::List(items) => items.iter().map(|i| self.eval(i)).collect::<Option<Vec<_>>>().map(Val::Tuple),
            ExprKind::Name(n) => {
                if n == "TYPE_CHECKING" {
                    return Some(Val::Bool(false));
                }
                if let Some(q) = self.qualified(e) {
                    return self.named(&q);
                }
                // A name this module sets, read anywhere in it.
                let symbol = self.file.source.doc.analysis.symbol_at(e.span.start)?;
                if symbol.scope != 0 {
                    return None;
                }
                match self.local {
                    Some(state) => state.get(n).cloned().flatten(),
                    None => self.consts.get(&self.file.name)?.get(n).cloned(),
                }
            }
            ExprKind::Attribute { .. } => self.named(&self.qualified(e)?),
            ExprKind::Subscript { value, index } => {
                let Val::Tuple(items) = self.eval(value)? else { return None };
                match &index.kind {
                    ExprKind::Slice { lower, upper, step: None } => {
                        let len = items.len() as i64;
                        let bound = |b: &Option<Box<Expr>>, default: i64| -> Option<i64> {
                            match b {
                                None => Some(default),
                                Some(x) => {
                                    let i = self.eval(x)?.number()?;
                                    Some(if i < 0 { (len + i).max(0) } else { i.min(len) })
                                }
                            }
                        };
                        let (lo, hi) = (bound(lower, 0)?, bound(upper, len)?);
                        Some(Val::Tuple(if lo < hi { items[lo as usize..hi as usize].to_vec() } else { Vec::new() }))
                    }
                    _ => {
                        let i = self.eval(index)?.number()?;
                        let i = if i < 0 { items.len() as i64 + i } else { i };
                        items.get(usize::try_from(i).ok()?).cloned()
                    }
                }
            }
            ExprKind::Unary { op, operand } => {
                let v = self.eval(operand)?;
                match *op {
                    "not" => Some(Val::Bool(!v.truthy())),
                    "-" => Some(Val::Int(-v.number()?)),
                    _ => None,
                }
            }
            ExprKind::BoolOp { op, left, right } => {
                let l = self.eval(left);
                match (*op, l) {
                    ("and", Some(l)) if !l.truthy() => Some(l),
                    ("or", Some(l)) if l.truthy() => Some(l),
                    (_, Some(_)) => self.eval(right),
                    // Unknown on the left: only the truth of the whole can be known.
                    ("and", None) => self.eval(right).filter(|r| !r.truthy()).map(|_| Val::Bool(false)),
                    ("or", None) => self.eval(right).filter(Val::truthy).map(|_| Val::Bool(true)),
                    _ => None,
                }
            }
            ExprKind::Compare { left, ops } => {
                let mut current = self.eval(left)?;
                for (op, right) in ops {
                    let r = self.eval(right)?;
                    let ok = match *op {
                        "==" => current.equals(&r)?,
                        "!=" => !current.equals(&r)?,
                        "<" => current.compare(&r)? == Ordering::Less,
                        "<=" => current.compare(&r)? != Ordering::Greater,
                        ">" => current.compare(&r)? == Ordering::Greater,
                        ">=" => current.compare(&r)? != Ordering::Less,
                        "in" => r.contains(&current)?,
                        "not in" => !r.contains(&current)?,
                        "is" if matches!(r, Val::None | Val::Bool(_)) => current == r,
                        "is not" if matches!(r, Val::None | Val::Bool(_)) => current != r,
                        _ => return None,
                    };
                    if !ok {
                        return Some(Val::Bool(false));
                    }
                    current = r;
                }
                Some(Val::Bool(true))
            }
            // hasattr(sys, "pypy_version_info")
            ExprKind::Call { func, args, keywords, .. } if keywords.is_empty() && args.len() == 2 && matches!(&func.kind, ExprKind::Name(n) if n == "hasattr") => {
                let module = self.qualified(&args[0])?;
                let ExprKind::Str { value: Some(name), fstring: false, .. } = &args[1].kind else { return None };
                self.host.members.get(&format!("{module}.{name}")).map(|b| Val::Bool(*b))
            }
            ExprKind::Call { func, args, keywords, .. } if keywords.is_empty() && args.len() == 1 => {
                let ExprKind::Attribute { value, attr, .. } = &func.kind else { return None };
                let Val::Str(s) = self.eval(value)? else { return None };
                let prefixes = match self.eval(&args[0])? {
                    Val::Str(p) => vec![p],
                    Val::Tuple(t) => t.into_iter().map(|v| if let Val::Str(p) = v { Some(p) } else { None }).collect::<Option<Vec<_>>>()?,
                    _ => return None,
                };
                match attr.as_str() {
                    "startswith" => Some(Val::Bool(prefixes.iter().any(|p| s.starts_with(p.as_str())))),
                    "endswith" => Some(Val::Bool(prefixes.iter().any(|p| s.ends_with(p.as_str())))),
                    _ => None,
                }
            }
            _ => None,
        }
    }

    /// Whether [test] is true or false in the keyboard, when that can be known.
    pub fn truth(&self, test: &Expr) -> Option<bool> {
        self.eval(test).map(|v| v.truthy())
    }
}

fn number(text: &str) -> Option<Val> {
    let t = text.replace('_', "").to_ascii_lowercase();
    let v = if let Some(h) = t.strip_prefix("0x") {
        i64::from_str_radix(h, 16).ok()?
    } else if let Some(o) = t.strip_prefix("0o") {
        i64::from_str_radix(o, 8).ok()?
    } else if let Some(b) = t.strip_prefix("0b") {
        i64::from_str_radix(b, 2).ok()?
    } else {
        t.parse::<i64>().ok()?
    };
    Some(Val::Int(v))
}

/// The span from the first statement of [block] to the end of the last.
fn block_span(block: &[Stmt]) -> Option<Span> {
    Some(Span::new(block.first()?.span.start, block.last()?.span.end))
}

/// Whether a `try`'s body gets through in the keyboard: `Some(true)` when
/// it only imports what is there, `Some(false)` when an import in it fails
/// and an `except ImportError:` takes over, `None` when that cannot be known.
pub(crate) type TryOutcome<'a> = dyn FnMut(&ModFile, &Stmt) -> Option<bool> + 'a;

/// What each name holds so far at a module's top level: a value, or `None`
/// once it could hold more than one.
pub(crate) type State = HashMap<String, Option<Val>>;

/// The constants each module sets at its top level, along the branches the
/// keyboard takes. A name set differently by branches that cannot be worked
/// out keeps what they agree on (two false values stay false); one set in a
/// loop, or in a `try` whose outcome is unknown, is not a constant.
pub(crate) fn constants(program: &Program, host: &Host, outcome: &mut TryOutcome) -> Consts {
    let mut consts: Consts = HashMap::new();
    // Each round can read what the round before found in other modules.
    for _ in 0..4 {
        let mut next: Consts = HashMap::new();
        for file in &program.files {
            let eval = Eval { file, host, consts: &consts, local: None };
            let mut state = State::new();
            collect_consts(&eval, file.source.body(), &mut state, outcome);
            next.insert(file.name.clone(), state.into_iter().filter_map(|(k, v)| Some((k, v?))).collect());
        }
        if next == consts {
            break;
        }
        consts = next;
    }
    consts
}

fn collect_consts(base: &Eval, body: &[Stmt], state: &mut State, outcome: &mut TryOutcome) {
    // An evaluator that reads the module's names as they stand now.
    fn at<'c>(base: &Eval<'c>, state: &'c State) -> Eval<'c> {
        Eval { file: base.file, host: base.host, consts: base.consts, local: Some(state) }
    }
    for s in body {
        match &s.kind {
            StmtKind::Assign { targets, value } => {
                let v = at(base, state).eval(value);
                for t in targets {
                    if let ExprKind::Name(n) = &t.kind {
                        state.insert(n.clone(), v.clone());
                    }
                }
            }
            StmtKind::AnnAssign { target, value: Some(value), .. } => {
                if let ExprKind::Name(n) = &target.kind {
                    let v = at(base, state).eval(value);
                    state.insert(n.clone(), v);
                }
            }
            StmtKind::AugAssign { target, .. } => {
                if let ExprKind::Name(n) = &target.kind {
                    state.insert(n.clone(), None);
                }
            }
            StmtKind::If { test, body, orelse, .. } => {
                let truth = at(base, state).truth(test);
                match truth {
                    Some(true) => collect_consts(base, body, state, outcome),
                    Some(false) => collect_consts(base, orelse, state, outcome),
                    None => {
                        let mut a = state.clone();
                        refine(&mut a, test, true);
                        collect_consts(base, body, &mut a, outcome);
                        let mut b = state.clone();
                        refine(&mut b, test, false);
                        collect_consts(base, orelse, &mut b, outcome);
                        *state = merge(a, b);
                    }
                }
            }
            StmtKind::Try { body, handlers, orelse, finalbody } => match outcome(base.file, s) {
                Some(true) => {
                    collect_consts(base, body, state, outcome);
                    collect_consts(base, orelse, state, outcome);
                    collect_consts(base, finalbody, state, outcome);
                }
                Some(false) if handlers.len() == 1 => {
                    collect_consts(base, &handlers[0].body, state, outcome);
                    collect_consts(base, finalbody, state, outcome);
                }
                _ => spoil(std::slice::from_ref(s), state),
            },
            StmtKind::For { .. } | StmtKind::While { .. } => spoil(std::slice::from_ref(s), state),
            _ => {}
        }
    }
}

/// What taking a branch says about a bare name its test reads: `if X:` means
/// X was true inside, false in the `else`.
fn refine(state: &mut State, test: &Expr, truth: bool) {
    match &test.kind {
        ExprKind::Name(n) => {
            if !matches!(state.get(n), Some(Some(_))) {
                state.insert(n.clone(), Some(Val::Bool(truth)));
            }
        }
        ExprKind::Unary { op: "not", operand } => refine(state, operand, !truth),
        _ => {}
    }
}

/// Two branches joined: a name keeps a value both give, or a truth both agree on.
fn merge(a: State, b: State) -> State {
    let keys: HashSet<String> = a.keys().chain(b.keys()).cloned().collect();
    keys.into_iter()
        .map(|k| {
            let v = match (a.get(&k), b.get(&k)) {
                (Some(Some(x)), Some(Some(y))) if x == y => Some(x.clone()),
                (Some(Some(x)), Some(Some(y))) if x.truthy() == y.truthy() => Some(Val::Bool(x.truthy())),
                _ => None,
            };
            (k, v)
        })
        .collect()
}

/// Every name [body] assigns, nested blocks included, now holds anything.
fn spoil(body: &[Stmt], state: &mut State) {
    wm_python_tooling::ast::walk_stmts(body, &mut |s| {
        let targets: Vec<&Expr> = match &s.kind {
            StmtKind::Assign { targets, .. } => targets.iter().collect(),
            StmtKind::AnnAssign { target, .. } | StmtKind::AugAssign { target, .. } | StmtKind::For { target, .. } => vec![target],
            _ => Vec::new(),
        };
        for t in targets {
            if let ExprKind::Name(n) = &t.kind {
                state.insert(n.clone(), None);
            }
        }
    });
}

/// The blocks of [file] that never run in the keyboard: the branches it does
/// not take, and the handlers of a `try` whose body gets through ([TryOutcome]).
pub(crate) fn dead_regions(eval: &Eval, outcome: &mut TryOutcome) -> Vec<Span> {
    let mut out = Vec::new();
    let mut fallback_dead = |s: &Stmt| outcome(eval.file, s) == Some(true);
    walk_dead(eval, eval.file.source.body(), &mut fallback_dead, &mut out);
    out
}

fn walk_dead(eval: &Eval, body: &[Stmt], fallback_dead: &mut dyn FnMut(&Stmt) -> bool, out: &mut Vec<Span>) {
    for s in body {
        match &s.kind {
            StmtKind::If { test, body, orelse, .. } => match eval.truth(test) {
                Some(true) => {
                    out.extend(block_span(orelse));
                    walk_dead(eval, body, fallback_dead, out);
                }
                Some(false) => {
                    out.extend(block_span(body));
                    walk_dead(eval, orelse, fallback_dead, out);
                }
                None => {
                    walk_dead(eval, body, fallback_dead, out);
                    walk_dead(eval, orelse, fallback_dead, out);
                }
            },
            StmtKind::Try { body, handlers, orelse, finalbody } => {
                walk_dead(eval, body, fallback_dead, out);
                if fallback_dead(s) {
                    out.extend(handlers.iter().filter_map(|h| block_span(&h.body)));
                } else {
                    for h in handlers {
                        walk_dead(eval, &h.body, fallback_dead, out);
                    }
                }
                walk_dead(eval, orelse, fallback_dead, out);
                walk_dead(eval, finalbody, fallback_dead, out);
            }
            _ => {
                for b in wm_python_tooling::ast::stmt_blocks(s) {
                    walk_dead(eval, b, fallback_dead, out);
                }
            }
        }
    }
}

/// How a module of the package comes to be loaded.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum Load {
    /// Only code that never runs in the keyboard imports it.
    Never,
    /// Only under `try: ... except ImportError:`, so an import error in it is caught.
    Caught,
    /// Only when code that needs it runs.
    Lazy,
    /// When the package loads.
    Eager,
}

/// Module path parts that mark a part of a package nobody imports by itself
/// in a plugin: its tests and its command line.
const ASIDE: &[&str] = &["test", "tests", "testing", "conftest", "cli", "__main__", "benchmarks", "examples", "scripts", "docs"];

/// Modules only Windows or macOS has: a module that needs one of them when
/// it loads is for that system.
const OTHER_SYSTEMS: &[&str] = &[
    "winreg", "_winreg", "msvcrt", "_winapi", "nt", "winsound", "_overlapped", "win32api", "win32con", "win32file",
    "pywintypes", "pythoncom", "_scproxy", "objc", "Foundation", "AppKit",
];

/// The [Load] of each file of [program], by index. [dead] is each file's dead regions.
pub(crate) fn load_modes(program: &Program, dead: &[Vec<Span>]) -> Vec<Load> {
    let n = program.files.len();
    // (importer, imported, the import is in a function, it is under `except ImportError`)
    let mut edges: Vec<(usize, usize, bool, bool)> = Vec::new();
    let mut imported = vec![false; n];
    // For another system: needs one of OTHER_SYSTEMS, or a module that is, the moment it loads.
    let mut other_system = vec![false; n];
    let mut needs: Vec<(usize, usize)> = Vec::new();
    for (i, file) in program.files.iter().enumerate() {
        for imp in &file.imports {
            let loads_all = file.loads(imp, &|m| program.exists(m));
            let never = imp.type_checking || imp.main_only || dead[i].iter().any(|d| d.contains(imp.span.start));
            let lazy = imp.in_function || imp.guard.import_error;
            let (in_function, caught) = (imp.in_function, imp.guard.import_error);
            if !never && !lazy && loads_all.iter().any(|m| OTHER_SYSTEMS.contains(&m.as_str())) {
                other_system[i] = true;
            }
            let loads: Vec<usize> = loads_all.iter().filter_map(|m| program.by_name.get(m).copied()).filter(|&j| j != i).collect();
            for j in loads {
                imported[j] = true;
                if !never {
                    edges.push((i, j, in_function, caught));
                    if !lazy {
                        needs.push((i, j));
                    }
                }
            }
        }
    }
    loop {
        let mut changed = false;
        for &(from, to) in &needs {
            if other_system[to] && !other_system[from] {
                other_system[from] = true;
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    let public_root = program.files.iter().any(|f| !f.name.contains('.') && !f.name.starts_with('_'));
    let mut mode: Vec<Load> = program
        .files
        .iter()
        .enumerate()
        .map(|(i, f)| {
            let parts: Vec<&str> = f.name.split('.').collect();
            if parts.last() == Some(&"__main__") {
                Load::Never
            } else if parts.len() == 1 && f.name.starts_with('_') && !imported[i] && public_root {
                // A private shim beside the package (PyYAML's `_yaml`), for old code that imported it by that name.
                Load::Lazy
            } else if parts.len() == 1 {
                Load::Eager
            } else if imported[i] {
                Load::Never
            } else if parts.iter().skip(1).any(|p| p.starts_with('_') || ASIDE.contains(p)) {
                Load::Lazy
            } else {
                Load::Eager
            }
        })
        .collect();
    let fixed: Vec<bool> = program.files.iter().enumerate().map(|(i, f)| f.name.ends_with(".__main__") || f.name == "__main__" || other_system[i]).collect();
    for (i, m) in mode.iter_mut().enumerate() {
        if other_system[i] {
            *m = Load::Never;
        }
    }
    loop {
        let mut changed = false;
        for &(from, to, in_function, caught) in &edges {
            if fixed[to] {
                continue;
            }
            let reach = match (mode[from], in_function, caught) {
                (Load::Never, _, _) => Load::Never,
                // Called later: no `try` around the import's module load is there then.
                (_, true, _) => Load::Lazy,
                (from, false, true) => from.min(Load::Caught),
                (from, false, false) => from,
            };
            if reach > mode[to] {
                mode[to] = reach;
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    mode
}
