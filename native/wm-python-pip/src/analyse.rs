//! [analyse]: will a package's files run in a plugin?

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use wm_python_tooling::analysis::{Access, ScopeKind, Target};
use wm_python_tooling::ast::{walk_expr, walk_stmts, stmt_exprs, ExprKind, Stmt, StmtKind};
use wm_python_tooling::lexer::TokenKind;
use wm_python_tooling::text::Span;

use crate::probe::{features, Probe, Support};
use crate::program::{module_of, resolve, root_of, Guard, ImportKind, ModFile, Program};
use crate::reach::{self, Load};
use crate::source::attribute_chain;
use crate::{limits, AnalyseOptions, FileReport, Imports, Problem, Report, Severity, Verdict};

/// Problems shown for one file before the rest are summed up in a note.
const MAX_PER_FILE: usize = 60;
/// Places shown for one missing builtin in one file.
const MAX_PER_NAME: usize = 3;

/// CPython 3.12's builtins, for telling a missing builtin from a name the
/// package defines some way the tooling cannot follow. Whether the keyboard
/// has each one is asked of the interpreter.
const CPYTHON_BUILTINS: &[&str] = &[
    "abs", "aiter", "all", "anext", "any", "ascii", "bin", "bool", "breakpoint", "bytearray", "bytes", "callable",
    "chr", "classmethod", "compile", "complex", "copyright", "credits", "delattr", "dict", "dir", "divmod",
    "enumerate", "eval", "exec", "exit", "filter", "float", "format", "frozenset", "getattr", "globals", "hasattr",
    "hash", "help", "hex", "id", "input", "int", "isinstance", "issubclass", "iter", "len", "license", "list",
    "locals", "map", "max", "memoryview", "min", "next", "object", "oct", "open", "ord", "pow", "print", "property",
    "quit", "range", "repr", "reversed", "round", "set", "setattr", "slice", "sorted", "staticmethod", "str", "sum",
    "super", "tuple", "type", "vars", "zip", "__import__", "__build_class__", "__debug__", "NotImplemented",
    "Ellipsis", "BaseException", "BaseExceptionGroup", "Exception", "ExceptionGroup", "ArithmeticError",
    "AssertionError", "AttributeError", "BlockingIOError", "BrokenPipeError", "BufferError", "ChildProcessError",
    "ConnectionAbortedError", "ConnectionError", "ConnectionRefusedError", "ConnectionResetError", "EOFError",
    "EnvironmentError", "FileExistsError", "FileNotFoundError", "FloatingPointError", "GeneratorExit", "IOError",
    "ImportError", "IndentationError", "IndexError", "InterruptedError", "IsADirectoryError", "KeyError",
    "KeyboardInterrupt", "LookupError", "MemoryError", "ModuleNotFoundError", "NameError", "NotADirectoryError",
    "NotImplementedError", "OSError", "OverflowError", "PermissionError", "ProcessLookupError", "RecursionError",
    "ReferenceError", "RuntimeError", "StopAsyncIteration", "StopIteration", "SyntaxError", "SystemError",
    "SystemExit", "TabError", "TimeoutError", "TypeError", "UnboundLocalError", "UnicodeDecodeError",
    "UnicodeEncodeError", "UnicodeError", "UnicodeTranslateError", "ValueError", "ZeroDivisionError", "Warning",
    "BytesWarning", "DeprecationWarning", "EncodingWarning", "FutureWarning", "ImportWarning",
    "PendingDeprecationWarning", "ResourceWarning", "RuntimeWarning", "SyntaxWarning", "UnicodeWarning",
    "UserWarning",
];

/// Names every CPython module has.
const MODULE_DUNDERS: &[&str] = &[
    "__file__", "__spec__", "__loader__", "__package__", "__path__", "__builtins__", "__cached__", "__doc__",
    "__name__", "__annotations__", "__dict__",
];

/// Builtins taken out of plugins because they reach outside the script, and why.
fn sandbox_builtin(name: &str) -> Option<&'static str> {
    Some(match name {
        "open" => "plugins have no file system",
        "input" => "plugins cannot read the terminal",
        "eval" | "exec" | "compile" | "__import__" => "plugins cannot run code made at run time",
        "globals" | "locals" | "vars" => "plugins cannot reach a module's namespace as a dict",
        "breakpoint" | "exit" | "quit" | "help" | "copyright" | "credits" | "license" => "it belongs to the interactive prompt",
        "memoryview" => "plugins have no raw memory",
        "__file__" | "__spec__" | "__loader__" | "__cached__" | "__path__" => "plugin modules are not files on disk",
        _ => return None,
    })
}

/// CPython 3.12's standard library, top-level names, for wording: a module
/// in here that the keyboard lacks is "not in the keyboard's Python", one
/// outside it is a package to install.
const CPYTHON_STDLIB: &[&str] = &[
    "__future__", "_thread", "abc", "aifc", "argparse", "array", "ast", "asynchat", "asyncio", "asyncore", "atexit",
    "audioop", "base64", "bdb", "binascii", "bisect", "builtins", "bz2", "cProfile", "calendar", "cgi", "cgitb",
    "chunk", "cmath", "cmd", "code", "codecs", "codeop", "collections", "colorsys", "compileall", "concurrent",
    "configparser", "contextlib", "contextvars", "copy", "copyreg", "crypt", "csv", "ctypes", "curses",
    "dataclasses", "datetime", "dbm", "decimal", "difflib", "dis", "doctest", "email", "encodings", "ensurepip",
    "enum", "errno", "faulthandler", "fcntl", "filecmp", "fileinput", "fnmatch", "fractions", "ftplib",
    "functools", "gc", "getopt", "getpass", "gettext", "glob", "graphlib", "grp", "gzip", "hashlib", "heapq",
    "hmac", "html", "http", "idlelib", "imaplib", "imghdr", "importlib", "inspect", "io", "ipaddress", "itertools",
    "json", "keyword", "lib2to3", "linecache", "locale", "logging", "lzma", "mailbox", "mailcap", "marshal", "math",
    "mimetypes", "mmap", "modulefinder", "msilib", "msvcrt", "multiprocessing", "netrc", "nis", "nntplib", "nt",
    "ntpath", "nturl2path", "numbers", "opcode", "operator", "optparse", "os", "ossaudiodev", "pathlib", "pdb",
    "pickle", "pickletools", "pipes", "pkgutil", "platform", "plistlib", "poplib", "posix", "posixpath", "pprint",
    "profile", "pstats", "pty", "pwd", "py_compile", "pyclbr", "pydoc", "queue", "quopri", "random", "re",
    "readline", "reprlib", "resource", "rlcompleter", "runpy", "sched", "secrets", "select", "selectors",
    "shelve", "shlex", "shutil", "signal", "site", "smtplib", "sndhdr", "socket", "socketserver", "spwd",
    "sqlite3", "sre_compile", "sre_constants", "sre_parse", "ssl", "stat", "statistics", "string", "stringprep",
    "struct", "subprocess", "sunau", "symtable", "sys", "sysconfig", "syslog", "tabnanny", "tarfile", "telnetlib",
    "tempfile", "termios", "textwrap", "threading", "time", "timeit", "tkinter", "token", "tokenize", "tomllib",
    "trace", "traceback", "tracemalloc", "tty", "turtle", "types", "typing", "unicodedata", "unittest", "urllib",
    "uu", "uuid", "venv", "warnings", "wave", "weakref", "webbrowser", "winreg", "winsound", "wsgiref", "xdrlib",
    "xml", "xmlrpc", "zipapp", "zipfile", "zipimport", "zlib", "zoneinfo",
];

/// Modules plugins go without on purpose: why, and what to use instead.
fn sandbox_module(name: &str) -> Option<(&'static str, Option<&'static str>)> {
    let top = name.split('.').next().unwrap_or(name);
    let files = ("plugins have no file system", Some("wm.files asks the user for a file to open or a place to save one"));
    let network = ("plugins cannot open connections themselves", Some("use wm.http for web requests: from wm import http"));
    let threads = ("plugins run on one thread, with no other processes", None);
    let code = ("plugins cannot load code at run time or call into C", None);
    Some(match top {
        "os" | "io" | "pathlib" | "shutil" | "tempfile" | "glob" | "fileinput" | "filecmp" | "stat" | "mmap" | "fcntl" | "posix" | "nt"
        | "pwd" | "grp" | "sqlite3" | "dbm" | "shelve" | "linecache" | "zipfile" | "tarfile" | "posixpath" | "ntpath" => files,
        "socket" | "ssl" | "http" | "ftplib" | "smtplib" | "poplib" | "imaplib" | "socketserver" | "xmlrpc" | "telnetlib" | "nntplib"
        | "webbrowser" => network,
        "urllib" if name.starts_with("urllib.request") || name.starts_with("urllib.error") => network,
        "subprocess" | "multiprocessing" | "threading" | "_thread" | "concurrent" | "signal" | "asyncio" | "selectors" | "select"
        | "resource" | "pty" | "tty" | "termios" | "sched" | "queue" | "atexit" => threads,
        "importlib" | "imp" | "pkgutil" | "runpy" | "zipimport" | "code" | "codeop" | "ctypes" | "marshal" | "gc" | "inspect" | "dis"
        | "site" | "sysconfig" | "platform" | "faulthandler" | "tracemalloc" | "cffi" | "_cffi_backend" => code,
        _ => return None,
    })
}

/// Whether [top] is a library the keyboard gives plugins under its own name
/// (requests, httpx), read from the host's prelude. `urllib` is the
/// standard library's, with `urllib.request` and `urllib.error` added.
pub(crate) fn library_root(top: &str) -> bool {
    top != "urllib" && wm_python::host::v5::library_modules().any(|m| m.split('.').next() == Some(top))
}

/// Imports that mean compiled C code.
fn c_module(name: &str) -> bool {
    matches!(name.split('.').next().unwrap_or(name), "ctypes" | "cffi" | "_cffi_backend")
}

fn has_ext(path: &str, exts: &[&str]) -> bool {
    let lower = path.to_ascii_lowercase();
    exts.iter().any(|e| lower.ends_with(e)) || (exts.contains(&".so") && lower.contains(".so."))
}

/// Where an imported module comes from.
enum Origin {
    Stdlib,
    Wm,
    Internal,
    Available,
    Missing,
}

struct Ctx<'a> {
    program: &'a Program,
    /// The package's own top-level names.
    roots: HashSet<String>,
    available: HashSet<String>,
    probe: Probe,
    wm_modules: HashSet<&'static str>,
    /// The package's compiled modules, left out.
    compiled: HashSet<String>,
    /// The package's modules that make top-level names the analysis cannot
    /// list: they write to `globals()` (reading it, as `__dir__` does, makes
    /// nothing) or reach themselves through `sys.modules`.
    dynamic: HashSet<String>,
}

impl Ctx<'_> {
    fn classify(&mut self, name: &str) -> Origin {
        let top = name.split('.').next().unwrap_or(name);
        if top == "wm" {
            return if self.wm_modules.contains(name) { Origin::Wm } else { Origin::Missing };
        }
        if self.roots.contains(top) {
            // A namespace another package also puts modules in (`google`
            // for google-auth beside google-cloud-storage).
            return if self.program.exists(name) {
                Origin::Internal
            } else if self.available.contains(top) {
                Origin::Available
            } else {
                Origin::Missing
            };
        }
        // requests, httpx, urllib.request: the keyboard's own, beside `wm`.
        if self.wm_modules.contains(name) {
            return Origin::Wm;
        }
        if library_root(top) {
            return Origin::Missing;
        }
        if self.available.contains(top) {
            return Origin::Available;
        }
        if self.probe.module_exists(name) {
            return Origin::Stdlib;
        }
        Origin::Missing
    }
}

/// Analyses a package: [files] maps each path (relative to the plugin
/// folder, such as `humanize/__init__.py`) to its text. Files that are not
/// `.py` may map to anything, `""` included: only their names are read.
pub fn analyse(files: &BTreeMap<String, String>, opts: &AnalyseOptions) -> Report {
    let program = Program::new(files, None);
    let roots: BTreeSet<String> = files.keys().filter(|p| p.ends_with(".py") && module_of(p).is_some()).map(|p| root_of(p)).collect();
    let mut ctx = Ctx {
        program: &program,
        roots: roots.iter().cloned().collect(),
        available: opts.available.iter().map(|s| s.split('.').next().unwrap_or(s).to_string()).collect(),
        probe: Probe::new(),
        wm_modules: wm_python::host::v5::PRELUDE.iter().map(|(n, _, _)| *n).collect(),
        compiled: opts.compiled.iter().cloned().collect(),
        dynamic: program
            .files
            .iter()
            .filter(|f| {
                let text = &files[&f.path];
                text.contains("globals()[") || text.contains("globals().update") || text.contains("globals().setdefault") || text.contains("sys.modules[__name__]")
            })
            .map(|f| f.name.clone())
            .collect(),
    };
    // What never runs in the keyboard, and how each module gets loaded.
    let host = reach::Host::get();
    let consts = reach::constants(&program, host, &mut |file, s| try_outcome(&mut ctx, file, s));
    let dead: Vec<Vec<Span>> = program
        .files
        .iter()
        .map(|file| {
            let eval = reach::Eval { file, host, consts: &consts, local: None };
            reach::dead_regions(&eval, &mut |f, s| try_outcome(&mut ctx, f, s))
        })
        .collect();
    let modes = reach::load_modes(&program, &dead);
    let mut imports = Imports::default();
    let mut reports = Vec::new();
    for (path, text) in files {
        let mut problems = Vec::new();
        let mut module = None;
        if has_ext(path, &[".so", ".pyd", ".dylib", ".dll"]) {
            problems.push(file_problem(path, Severity::Error, "c-extension", "this is compiled code, and plugins cannot load it".into(), None));
        } else if has_ext(path, &[".c", ".cc", ".cpp", ".cxx", ".h", ".pyx", ".pxd"]) {
            problems.push(file_problem(
                path,
                Severity::Warning,
                "c-source",
                "this is the source of a C extension; if the package needs it built, that part will not work".into(),
                None,
            ));
        } else if path.ends_with(".py") {
            if let Some(i) = program.files.iter().position(|f| &f.path == path) {
                let file = &program.files[i];
                module = Some(file.name.clone());
                check_file(&mut ctx, file, text, &dead[i], &mut problems, &mut imports);
                settle_by_load(path, modes[i], &mut problems);
            } else {
                problems.push(file_problem(
                    path,
                    Severity::Error,
                    "module-name",
                    "this path is not a module name, and the keyboard refuses a plugin holding such a .py file".into(),
                    Some("leave the file out: Python could not import it either".into()),
                ));
            }
        }
        if problems.len() > MAX_PER_FILE {
            let rest = problems.len() - MAX_PER_FILE;
            problems.truncate(MAX_PER_FILE);
            problems.push(file_problem(path, Severity::Info, "more", format!("{rest} more problems in this file are not shown"), None));
        }
        reports.push(FileReport { path: path.clone(), module, bytes: text.len(), problems });
    }
    let all: Vec<&Problem> = reports.iter().flat_map(|r| r.problems.iter()).collect();
    let verdict = if all.iter().any(|p| p.severity == Severity::Error) {
        Verdict::Incompatible
    } else if all.iter().any(|p| p.severity == Severity::Warning) {
        Verdict::Warnings
    } else {
        Verdict::Ok
    };
    Report { verdict, files: reports, imports, roots: roots.into_iter().collect() }
}

fn file_problem(path: &str, severity: Severity, code: &'static str, message: String, help: Option<String>) -> Problem {
    Problem { severity, code, message, file: path.to_string(), line: None, col: None, end_col: None, help }
}

/// Problems with the file itself, which the keyboard checks whether or not
/// anything loads it.
const ARCHIVE_CODES: &[&str] = &["too-deep", "module-name", "reserved-name", "file-too-big", "shadowed"];
/// Of [ARCHIVE_CODES], those `wmpy pack` gets past by leaving the file out
/// when the plugin's code never reaches it: they matter only as much as
/// the module is needed.
const PACKED_ONLY_IF_USED: &[&str] = &["too-deep", "file-too-big"];

/// A module loaded only when some code runs makes that code fail, not the
/// package; one that never loads in the keyboard fails nothing.
fn settle_by_load(path: &str, load: Load, problems: &mut Vec<Problem>) {
    if load == Load::Direct {
        return settle_direct(path, problems);
    }
    let (to, note) = match load {
        Load::Eager | Load::Direct => return,
        Load::Lazy => (Severity::Warning, "the package loads without this module: only the code that imports it needs it, so what fails here fails that code"),
        Load::Caught => (
            Severity::Warning,
            "this module is only imported under `except ImportError:`, so the package goes on without it when an import in it fails",
        ),
        Load::Never => (Severity::Info, "nothing loads this module in the keyboard: only code for other systems or Python versions imports it"),
    };
    let mut moved = false;
    for p in problems.iter_mut() {
        if PACKED_ONLY_IF_USED.contains(&p.code) {
            if p.severity < to {
                p.severity = to;
                p.help = Some("`wmpy pack` leaves the file out when the plugin's code never reaches it; if it does, the keyboard refuses the plugin".into());
                moved = true;
            }
            continue;
        }
        if ARCHIVE_CODES.contains(&p.code) {
            continue;
        }
        // An import that fails raises ImportError, which the importer catches.
        let target = if load == Load::Caught && matches!(p.code, "missing-import" | "c-extension" | "sandbox-import") { Severity::Info } else { to };
        if p.severity < target {
            p.severity = target;
            moved = true;
        }
    }
    if moved {
        problems.insert(0, file_problem(path, Severity::Info, "loaded-later", note.into(), None));
    }
}

/// A public module the package never imports itself: what it breaks on is
/// its own, but a package it alone imports, one the package does not
/// depend on, makes it an add-on for code that has that package
/// (`urllib3.contrib.pyopenssl` for pyOpenSSL), and a file `wmpy pack` can
/// leave out stops nothing that does not import it.
fn settle_direct(path: &str, problems: &mut Vec<Problem>) {
    let mut moved = false;
    for p in problems.iter_mut() {
        let add_on = p.code == "missing-import" && p.help.as_deref().is_some_and(|h| h.starts_with("wmpy pip install"));
        if p.severity == Severity::Error && (add_on || PACKED_ONLY_IF_USED.contains(&p.code)) {
            p.severity = Severity::Warning;
            if !add_on {
                p.help = Some("`wmpy pack` leaves the file out when the plugin's code never reaches it; if it does, the keyboard refuses the plugin".into());
            }
            moved = true;
        }
    }
    if moved {
        problems.insert(
            0,
            file_problem(
                path,
                Severity::Info,
                "loaded-later",
                "nothing in the package imports this module: it loads only if the plugin imports it, and what fails here fails only then".into(),
                None,
            ),
        );
    }
}

/// The names a module's `__getattr__` answers, when it spells them all out
/// (`if name == "upstr":`, `if name in ("a", "b"):`) and raises for the
/// rest, as one kept for deprecated names does. None when it may answer
/// anything.
fn getattr_names(file: &ModFile) -> Option<Vec<String>> {
    fn find(body: &[Stmt]) -> Option<&[Stmt]> {
        body.iter().rev().find_map(|s| match &s.kind {
            StmtKind::Def { name, body, .. } if name == "__getattr__" => Some(body.as_slice()),
            StmtKind::If { body, orelse, .. } => find(orelse).or_else(|| find(body)),
            _ => None,
        })
    }
    fn literals(e: &wm_python_tooling::ast::Expr, out: &mut Vec<String>) -> Option<()> {
        match &e.kind {
            ExprKind::Str { value: Some(v), fstring: false, .. } => out.push(v.clone()),
            ExprKind::Tuple(items) | ExprKind::List(items) | ExprKind::Set(items) => {
                for i in items {
                    literals(i, out)?;
                }
            }
            _ => return None,
        }
        Some(())
    }
    fn walk(body: &[Stmt], out: &mut Vec<String>) -> Option<()> {
        for (i, s) in body.iter().enumerate() {
            match &s.kind {
                StmtKind::Expr(e) if i == 0 && matches!(e.kind, ExprKind::Str { .. }) => {}
                StmtKind::If { test, orelse, .. } => {
                    let ExprKind::Compare { left, ops } = &test.kind else { return None };
                    if !matches!(left.kind, ExprKind::Name(_)) || ops.len() != 1 || !matches!(ops[0].0, "==" | "in") {
                        return None;
                    }
                    literals(&ops[0].1, out)?;
                    walk(orelse, out)?;
                }
                StmtKind::Raise(_) => {}
                _ => return None,
            }
        }
        Some(())
    }
    let body = find(file.source.body())?;
    let mut out = Vec::new();
    walk(body, &mut out)?;
    // It has to end by refusing what it does not name.
    matches!(body.last()?.kind, StmtKind::Raise(_)).then_some(out)
}

/// Whether the body of the `try` [s] gets through in the keyboard
/// ([reach::TryOutcome]): `Some(true)` when what it imports is all there (or
/// the builtins it reads are), `Some(false)` when one of them is missing and
/// a handler catches that, `None` when it cannot be known.
fn try_outcome(ctx: &mut Ctx, file: &ModFile, s: &Stmt) -> Option<bool> {
    let StmtKind::Try { body, handlers, .. } = &s.kind else { return None };
    if body.is_empty() || handlers.is_empty() {
        return None;
    }
    fn names(e: &wm_python_tooling::ast::Expr, out: &mut Vec<String>) {
        match &e.kind {
            ExprKind::Name(n) => out.push(n.clone()),
            ExprKind::Attribute { attr, .. } => out.push(attr.clone()),
            ExprKind::Tuple(items) | ExprKind::List(items) => items.iter().for_each(|i| names(i, out)),
            _ => out.push(String::new()),
        }
    }
    let mut caught = Vec::new();
    for h in handlers {
        if h.types.is_empty() {
            return None;
        }
        h.types.iter().for_each(|t| names(t, &mut caught));
    }
    if !caught.iter().all(|n| matches!(n.as_str(), "ImportError" | "ModuleNotFoundError" | "NameError")) {
        return None;
    }
    let imports_caught = caught.iter().any(|n| n != "NameError");
    let names_caught = caught.iter().any(|n| n == "NameError");
    let failed = |caught: bool| if caught { Some(false) } else { None };
    let package = file.path.ends_with("/__init__.py");
    for stmt in body {
        match &stmt.kind {
            StmtKind::Pass | StmtKind::Assign { .. } | StmtKind::AnnAssign { .. } => {}
            StmtKind::Expr(e) => match &e.kind {
                ExprKind::Name(n) if names_caught => {
                    if !ctx.probe.name_exists(n) {
                        return Some(false);
                    }
                }
                _ => return None,
            },
            StmtKind::Import(list) => {
                if list.iter().any(|n| matches!(ctx.classify(&n.name), Origin::Missing)) {
                    return failed(imports_caught);
                }
            }
            StmtKind::ImportFrom { module, names: list, star, .. } => {
                let m = resolve(module, &file.name, package)?;
                if m == "__future__" {
                    return None;
                }
                if matches!(ctx.classify(&m), Origin::Missing) || (!*star && !list.iter().all(|n| name_importable(ctx, &m, &n.name))) {
                    return failed(imports_caught);
                }
            }
            _ => return None,
        }
    }
    Some(true)
}

/// Whether `from [module] import [name]` finds [name]; [module] exists.
fn name_importable(ctx: &mut Ctx, module: &str, name: &str) -> bool {
    let sub = format!("{module}.{name}");
    if MODULE_DUNDERS_IMPORTABLE.contains(&name) {
        return true;
    }
    match ctx.classify(module) {
        Origin::Internal => ctx.program.exists(&sub) || module_has(ctx, module, name, &mut HashSet::new()),
        Origin::Stdlib => ctx.probe.module_exists(&sub) || ctx.probe.member_exists(module, name),
        Origin::Missing => false,
        _ => true,
    }
}

/// Whether the package's own [module] ends up with a top-level [name]:
/// bound there, taken in by a star import (`from .base import *`), or made
/// some way the analysis cannot follow (a module `__getattr__`, writes to
/// `globals()`, a star import from a module it cannot read).
fn module_has(ctx: &mut Ctx, module: &str, name: &str, seen: &mut HashSet<String>) -> bool {
    if !seen.insert(module.to_string()) {
        return false;
    }
    let program = ctx.program;
    let Some(target) = program.get(module) else { return false };
    let scope = &target.source.doc.analysis.scopes[0];
    let answers = scope.names.contains_key("__getattr__") && getattr_names(target).is_none_or(|names| names.iter().any(|n| n == name));
    if scope.names.contains_key(name) || answers || ctx.dynamic.contains(module) {
        return true;
    }
    let package = target.path.ends_with("/__init__.py");
    for imp in &target.imports {
        let ImportKind::From { raw, star: true, .. } = &imp.kind else { continue };
        if imp.in_function || imp.type_checking {
            continue;
        }
        let Some(from) = resolve(raw, &target.name, package) else { continue };
        let found = match ctx.classify(&from) {
            Origin::Internal => module_has(ctx, &from, name, seen),
            Origin::Stdlib => ctx.probe.member_exists(&from, name),
            Origin::Missing => false,
            Origin::Wm | Origin::Available => true,
        };
        if found {
            return true;
        }
    }
    false
}

/// Names every module has that `from m import x` can take.
const MODULE_DUNDERS_IMPORTABLE: &[&str] = &["__doc__", "__name__", "__package__"];

fn at(file: &ModFile, span: Span, severity: Severity, code: &'static str, message: String, help: Option<String>) -> Problem {
    let (line, col, end_col) = file.source.range(span);
    Problem { severity, code, message, file: file.path.clone(), line: Some(line), col: Some(col), end_col: Some(end_col), help }
}

/// How bad a failure is, from where it happens: at import time it stops the
/// package loading, in a function only when that runs, and under a `try`
/// that catches it not at all.
fn severity_for(in_function: bool, caught: bool) -> Severity {
    if caught {
        Severity::Info
    } else if in_function {
        Severity::Warning
    } else {
        Severity::Error
    }
}

fn check_file(ctx: &mut Ctx, file: &ModFile, text: &str, dead: &[Span], out: &mut Vec<Problem>, imports: &mut Imports) {
    let path = &file.path;
    // ---- what the keyboard does with the file itself ------------------------
    let parts = path.trim_end_matches(".py").split('/').count();
    if parts > limits::MAX_MODULE_PARTS && path.ends_with("/__init__.py") && text.trim().is_empty() {
        // A folder of data files (tzdata's zones): `wmpy pip install` leaves the empty file out.
        out.push(file_problem(
            path,
            Severity::Info,
            "too-deep",
            format!("this empty file is more than {} folders deep, so installing leaves it out", limits::MAX_MODULE_PARTS - 1),
            None,
        ));
        return;
    } else if parts > limits::MAX_MODULE_PARTS {
        out.push(file_problem(
            path,
            Severity::Error,
            "too-deep",
            format!("the keyboard refuses a plugin with a .py file more than {} folders deep", limits::MAX_MODULE_PARTS - 1),
            None,
        ));
    } else if path.trim_end_matches(".py").split('/').any(|p| p.chars().count() > limits::MAX_PART_CHARS) {
        out.push(file_problem(path, Severity::Error, "module-name", format!("a folder or file name here is over {} characters", limits::MAX_PART_CHARS), None));
    } else if wm_python::host::v5::module_name(path).is_none() {
        let top = root_of(path);
        if wm_python::interp::Runtime::python_stdlib_names().iter().any(|s| s.split('.').next() == Some(top.as_str())) {
            out.push(file_problem(
                path,
                Severity::Warning,
                "shadowed",
                format!("the keyboard has its own '{top}' module, so it uses that and never loads this file"),
                None,
            ));
        } else {
            out.push(file_problem(path, Severity::Error, "reserved-name", format!("the keyboard keeps the module name '{top}' for itself"), None));
        }
    }
    if text.len() > limits::MAX_SCRIPT_BYTES {
        out.push(file_problem(
            path,
            Severity::Error,
            "file-too-big",
            format!("{} KB: the keyboard refuses a plugin with a .py file over {} KB", text.len() / 1024, limits::MAX_SCRIPT_BYTES / 1024),
            None,
        ));
    }
    // ---- the interpreter's own parser ---------------------------------------
    let mut syntax_line = None;
    if let Err(e) = wm_python::interp::check(text) {
        syntax_line = e.line;
        let mut p = file_problem(path, Severity::Error, "syntax", format!("the keyboard's Python cannot run this file: {}: {}", e.kind, e.message), None);
        if let Some(line) = e.line {
            p.line = Some(line);
            let span = file.source.doc.lines.trimmed(file.source.units(), line as usize);
            let (_, col, end_col) = file.source.range(span);
            p.col = Some(col);
            p.end_col = Some(end_col);
        }
        out.push(p);
    }
    check_imports(ctx, file, dead, out, imports);
    check_names(ctx, file, dead, out);
    check_attributes(ctx, file, dead, out);
    check_features(file, dead, syntax_line, out);
}

/// What the checks pass over: code that never runs in the keyboard, and
/// annotations, which the keyboard's Python reads and ignores.
fn never_runs(file: &ModFile, dead: &[Span]) -> Vec<Span> {
    let mut out = dead.to_vec();
    walk_stmts(file.source.body(), &mut |s| {
        if let StmtKind::AnnAssign { annotation, .. } = &s.kind {
            out.push(annotation.span);
        }
    });
    out
}

fn missing_import(ctx: &Ctx, file: &ModFile, name: &str, span: Span, severity: Severity) -> Problem {
    let top = name.split('.').next().unwrap_or(name);
    if ctx.compiled.contains(name) {
        return at(file, span, severity, "c-extension", format!("'{name}' is compiled code, which plugins cannot load, so installing leaves it out"), None);
    }
    if ctx.roots.contains(top) {
        return at(file, span, severity, "missing-import", format!("the package has no module '{name}'"), None);
    }
    if top == "wm" {
        return at(file, span, severity, "missing-import", format!("the wm package has no module '{name}'"), None);
    }
    if library_root(top) {
        return at(file, span, severity, "missing-import", format!("the keyboard's built-in {top} has no module '{name}'"), None);
    }
    if c_module(name) {
        return at(file, span, severity, "c-extension", format!("'{name}' loads compiled C code, which plugins cannot run"), None);
    }
    if let Some((why, help)) = sandbox_module(name) {
        return at(file, span, severity, "sandbox-import", format!("'{name}' is not available to plugins: {why}"), help.map(String::from));
    }
    if top == "__future__" {
        return at(
            file,
            span,
            severity,
            "missing-import",
            "the keyboard's Python has no '__future__' module".into(),
            Some("`from __future__ import annotations` can be deleted: the keyboard's Python reads annotations and ignores them".into()),
        );
    }
    if CPYTHON_STDLIB.contains(&top) {
        return at(file, span, severity, "missing-import", format!("the keyboard's Python has no '{name}' module"), None);
    }
    at(
        file,
        span,
        severity,
        "missing-import",
        format!("'{top}' is not installed in this plugin"),
        Some(format!("wmpy pip install {}", top.replace('_', "-"))),
    )
}

/// Files [name] under where it comes from; false when nothing provides it.
fn record(ctx: &mut Ctx, name: &str, imports: &mut Imports) -> bool {
    let set = match ctx.classify(name) {
        Origin::Stdlib => &mut imports.stdlib,
        Origin::Wm => &mut imports.wm,
        Origin::Internal => &mut imports.internal,
        Origin::Available => &mut imports.available,
        Origin::Missing => {
            imports.missing.insert(name.to_string());
            return false;
        }
    };
    set.insert(name.to_string());
    true
}

fn check_imports(ctx: &mut Ctx, file: &ModFile, dead: &[Span], out: &mut Vec<Problem>, imports: &mut Imports) {
    for imp in &file.imports {
        if imp.type_checking || imp.main_only || dead.iter().any(|d| d.contains(imp.span.start)) {
            continue;
        }
        let severity = severity_for(imp.in_function, imp.guard.import_error);
        match &imp.kind {
            ImportKind::Plain(names) => {
                for b in names {
                    if !record(ctx, &b.name, imports) {
                        out.push(missing_import(ctx, file, &b.name, b.span, severity));
                    }
                }
            }
            ImportKind::From { module: None, raw, module_span, .. } => {
                out.push(at(file, *module_span, severity, "missing-import", format!("'{raw}' climbs above the top of the package"), None));
            }
            // The interpreter may take `from __future__ import x` as a
            // statement of its own while `import __future__` still fails.
            ImportKind::From { module: Some(m), module_span, names, .. } if m.as_str() == "__future__" => {
                let list: Vec<&str> = names.iter().map(|b| b.name.as_str()).collect();
                if !ctx.probe.source_runs(&format!("from __future__ import {}\n", list.join(", "))) {
                    out.push(missing_import(ctx, file, m, *module_span, severity));
                }
            }
            ImportKind::From { module: Some(m), module_span, names, .. } => {
                if !record(ctx, m, imports) {
                    out.push(missing_import(ctx, file, m, *module_span, severity));
                    continue;
                }
                for b in names {
                    let sub = format!("{m}.{}", b.name);
                    let origin = ctx.classify(m);
                    if matches!(origin, Origin::Internal) {
                        if ctx.program.exists(&sub) {
                            imports.internal.insert(sub);
                            continue;
                        }
                        if ctx.program.get(m).is_none() {
                            out.push(at(file, b.span, severity, "missing-import", format!("the package has no module '{sub}'"), None));
                        } else if !MODULE_DUNDERS_IMPORTABLE.contains(&b.name.as_str()) && !module_has(ctx, m, &b.name, &mut HashSet::new()) {
                            out.push(at(file, b.span, severity, "missing-name", format!("'{m}' has no '{}'", b.name), None));
                        }
                    } else if matches!(origin, Origin::Stdlib) {
                        if ctx.probe.module_exists(&sub) {
                            imports.stdlib.insert(sub);
                        } else if !ctx.probe.member_exists(m, &b.name) {
                            out.push(at(file, b.span, severity, "missing-name", format!("the keyboard's '{m}' has no '{}'", b.name), None));
                        }
                    }
                }
            }
        }
    }
}

/// Spans at the top level (outside functions) covered by a `try` body, with
/// what it catches, and the bodies of `if TYPE_CHECKING:`, which never run.
fn top_regions(body: &[Stmt], guards: &mut Vec<(Span, Guard)>, skipped: &mut Vec<Span>) {
    for s in body {
        match &s.kind {
            StmtKind::Try { body, handlers, orelse, finalbody } => {
                let mut g = Guard::default();
                g.add(handlers);
                if let (Some(first), Some(last)) = (body.first(), body.last()) {
                    guards.push((Span::new(first.span.start, last.span.end), g));
                }
                top_regions(body, guards, skipped);
                for h in handlers {
                    top_regions(&h.body, guards, skipped);
                }
                top_regions(orelse, guards, skipped);
                top_regions(finalbody, guards, skipped);
            }
            StmtKind::If { test, body, orelse, .. } => {
                if crate::program::is_type_checking(test) || crate::program::is_main_test(test) {
                    if let (Some(first), Some(last)) = (body.first(), body.last()) {
                        skipped.push(Span::new(first.span.start, last.span.end));
                    }
                } else {
                    top_regions(body, guards, skipped);
                }
                top_regions(orelse, guards, skipped);
            }
            StmtKind::For { body, orelse, .. } | StmtKind::While { body, orelse, .. } => {
                top_regions(body, guards, skipped);
                top_regions(orelse, guards, skipped);
            }
            StmtKind::Unsupported { what, body, .. } if *what != "def" => top_regions(body, guards, skipped),
            _ => {}
        }
    }
}

fn check_names(ctx: &mut Ctx, file: &ModFile, dead: &[Span], out: &mut Vec<Problem>) {
    let a = &file.source.doc.analysis;
    let mut guards = Vec::new();
    let mut skipped = never_runs(file, dead);
    top_regions(file.source.body(), &mut guards, &mut skipped);
    let mut shown: HashMap<String, usize> = HashMap::new();
    let mut hidden: BTreeMap<String, usize> = BTreeMap::new();
    for o in &a.occurrences {
        if o.access != Access::Read || !matches!(o.target, Target::Unresolved | Target::Builtin) {
            continue;
        }
        let name = file.source.text(o.span);
        if !CPYTHON_BUILTINS.contains(&name.as_str()) && !MODULE_DUNDERS.contains(&name.as_str()) {
            continue;
        }
        if skipped.iter().any(|s| s.contains(o.span.start)) {
            continue;
        }
        // A builtin that is there but refuses when called (`open`, `input`):
        // naming it is fine, calling it fails.
        let refuses = ctx.probe.name_exists(&name) && sandbox_builtin(&name).is_some() && ctx.probe.refuses(&name);
        if ctx.probe.name_exists(&name) && !(refuses && is_called(file, o.span)) {
            continue;
        }
        let mut in_function = false;
        let mut scope = Some(o.scope);
        while let Some(s) = scope {
            if matches!(a.scopes[s].kind, ScopeKind::Function | ScopeKind::Lambda) {
                in_function = true;
            }
            scope = a.scopes[s].parent;
        }
        let caught = !in_function && guards.iter().any(|(span, g)| span.contains(o.span.start) && g.name_error);
        // An `except X:` clause is passed over by name; it fails only if an exception reaches it.
        let severity = if o.except_type { Severity::Info } else { severity_for(in_function, caught) };
        let count = shown.entry(name.clone()).or_default();
        if *count >= MAX_PER_NAME {
            *hidden.entry(name).or_default() += 1;
            continue;
        }
        *count += 1;
        let p = match sandbox_builtin(&name) {
            Some(why) if refuses => at(file, o.span, severity, "sandbox-builtin", format!("'{name}' raises an error in plugins: {why}"), None),
            Some(why) => at(file, o.span, severity, "sandbox-builtin", format!("'{name}' is not available to plugins: {why}"), None),
            None => at(file, o.span, severity, "missing-builtin", format!("the keyboard's Python has no '{name}'"), None),
        };
        out.push(p);
    }
    for (name, n) in hidden {
        out.push(file_problem(&file.path, Severity::Info, "more", format!("'{name}' is used {n} more times in this file"), None));
    }
}

/// Whether the name at [span] is called right there: `open(...)`.
fn is_called(file: &ModFile, span: Span) -> bool {
    let units = file.source.units();
    let mut i = span.end as usize;
    while i < units.len() && (units[i] == b' ' as u16 || units[i] == b'\t' as u16) {
        i += 1;
    }
    i < units.len() && units[i] == b'(' as u16
}

fn check_attributes(ctx: &mut Ctx, file: &ModFile, dead: &[Span], out: &mut Vec<Problem>) {
    let a = &file.source.doc.analysis;
    let mut guards = Vec::new();
    let mut skipped = never_runs(file, dead);
    top_regions(file.source.body(), &mut guards, &mut skipped);
    let mut found = Vec::new();
    walk_stmts(file.source.body(), &mut |s| {
        for e in stmt_exprs(s) {
            walk_expr(e, &mut |x| {
                if let ExprKind::Attribute { attr_span, .. } = &x.kind {
                    if let Some(chain) = attribute_chain(x) {
                        found.push((chain, *attr_span));
                    }
                }
            });
        }
    });
    let mut seen = HashSet::new();
    for ((_, chain, base_span), attr_span) in found {
        if skipped.iter().any(|s| s.contains(base_span.start)) {
            continue;
        }
        let Some(symbol) = a.symbol_at(base_span.start) else { continue };
        let Some((module, member)) = &symbol.import else { continue };
        if !symbol.writes.is_empty() || module.starts_with('.') {
            continue;
        }
        let mut current = match member {
            None => module.clone(),
            Some(m) => format!("{module}.{m}"),
        };
        if !matches!(ctx.classify(&current), Origin::Stdlib) || !ctx.probe.module_exists(&current) {
            continue;
        }
        for (i, attr) in chain.iter().enumerate() {
            let sub = format!("{current}.{attr}");
            // The keyboard's own modules beside `wm`, such as urllib.request.
            if ctx.wm_modules.contains(sub.as_str()) {
                break;
            }
            if ctx.probe.module_exists(&sub) {
                current = sub;
                continue;
            }
            if ctx.probe.member_exists(&current, attr) || i + 1 != chain.len() || !seen.insert((current.clone(), attr.clone(), attr_span)) {
                break;
            }
            let scope_in_function = {
                let mut s = Some(a.scope_at(attr_span.start));
                let mut f = false;
                while let Some(x) = s {
                    if matches!(a.scopes[x].kind, ScopeKind::Function | ScopeKind::Lambda) {
                        f = true;
                    }
                    s = a.scopes[x].parent;
                }
                f
            };
            let caught = !scope_in_function && guards.iter().any(|(span, g)| span.contains(attr_span.start) && g.attribute_error);
            let severity = severity_for(scope_in_function, caught);
            let help = (current == "sys").then(|| "the keyboard's sys has only sys.version".to_string());
            out.push(at(file, attr_span, severity, "missing-attribute", format!("the keyboard's '{current}' has no '{attr}'"), help));
            break;
        }
    }
}

/// `class C(A, B, metaclass=M):` as the positional bases and the keyword names.
pub(crate) fn class_header(file: &ModFile, span: Span) -> (Vec<String>, Vec<String>) {
    let t = &file.source.doc.tokens;
    let mut bases = Vec::new();
    let mut keywords = Vec::new();
    let Some(mut i) = t.index_at(span.start) else { return (bases, keywords) };
    // To the `(` after the name, unless the header ends first.
    while i < t.len() && t.start(i) < span.end {
        if t.is_op(i, "(") {
            break;
        }
        if t.is_op(i, ":") {
            return (bases, keywords);
        }
        i += 1;
    }
    let mut depth = 0;
    let mut entry = String::new();
    let mut entry_keyword: Option<String> = None;
    let mut last_name: Option<String> = None;
    while i < t.len() && t.start(i) < span.end {
        if !t.is_code(i) {
            i += 1;
            continue;
        }
        let text = t.text(i);
        if t.kind(i) == TokenKind::Operator && matches!(text.as_str(), "(" | "[" | "{") {
            depth += 1;
            if depth == 1 {
                i += 1;
                continue;
            }
        }
        if t.kind(i) == TokenKind::Operator && matches!(text.as_str(), ")" | "]" | "}") {
            depth -= 1;
            if depth == 0 {
                break;
            }
        }
        if depth == 1 && t.is_op(i, ",") {
            match entry_keyword.take() {
                Some(k) => keywords.push(k),
                None if !entry.trim().is_empty() => bases.push(entry.trim().to_string()),
                None => {}
            }
            entry.clear();
            last_name = None;
        } else if depth == 1 && t.is_op(i, "=") && entry_keyword.is_none() {
            entry_keyword = last_name.clone().or(Some(String::new()));
        } else {
            if t.kind(i) == TokenKind::Name {
                last_name = Some(text.clone());
            }
            entry.push_str(&text);
        }
        i += 1;
    }
    match entry_keyword {
        Some(k) => keywords.push(k),
        None if !entry.trim().is_empty() => bases.push(entry.trim().to_string()),
        None => {}
    }
    (bases, keywords)
}

/// Python the parser takes but the interpreter may not run as CPython does,
/// each checked against the interpreter in [features].
fn check_features(file: &ModFile, dead: &[Span], syntax_line: Option<u32>, out: &mut Vec<Problem>) {
    let f = features();
    fn classes(body: &[Stmt], in_function: bool, out: &mut Vec<(Span, bool)>) {
        for s in body {
            match &s.kind {
                StmtKind::Unsupported { what: "class", body, .. } => {
                    out.push((s.span, in_function));
                    classes(body, in_function, out);
                }
                StmtKind::Def { body, .. } => classes(body, true, out),
                _ => {
                    for b in wm_python_tooling::ast::stmt_blocks(s) {
                        classes(b, in_function, out);
                    }
                }
            }
        }
    }
    let mut found = Vec::new();
    classes(file.source.body(), false, &mut found);
    for (span, in_function) in found {
        // A header the parser already refused needs no second problem.
        if syntax_line == Some(file.source.place(span.start).0) || dead.iter().any(|d| d.contains(span.start)) {
            continue;
        }
        let (bases, keywords) = class_header(file, span);
        let header = Span::new(span.start, file.source.doc.lines.end(file.source.units(), file.source.doc.lines.line_of(span.start)));
        let real: Vec<&String> = bases.iter().filter(|b| b.as_str() != "object" && !b.starts_with('*')).collect();
        if real.len() > 1 && f.multiple_inheritance != Support::Yes {
            let how = if f.multiple_inheritance == Support::Fails { "raises an error" } else { "does not follow Python's rules" };
            let severity = if f.multiple_inheritance == Support::Fails { severity_for(in_function, false) } else { Severity::Warning };
            out.push(at(
                file,
                header,
                severity,
                "multiple-inheritance",
                format!("a class with {} base classes: the keyboard's Python {how} for more than one", real.len()),
                None,
            ));
        }
        if keywords.iter().any(|k| k == "metaclass") && f.metaclass != Support::Yes {
            let how = if f.metaclass == Support::Fails { "raises an error" } else { "ignores it" };
            let severity = if f.metaclass == Support::Fails { severity_for(in_function, false) } else { Severity::Warning };
            out.push(at(file, header, severity, "metaclass", format!("a metaclass: the keyboard's Python {how}"), None));
        }
    }
    if f.big_ints != Support::Yes {
        let t = &file.source.doc.tokens;
        for i in 0..t.len() {
            if t.kind(i) != TokenKind::Number {
                continue;
            }
            let text = t.text(i).replace('_', "").to_ascii_lowercase();
            let value = if let Some(h) = text.strip_prefix("0x") {
                u128::from_str_radix(h, 16).ok()
            } else if let Some(o) = text.strip_prefix("0o") {
                u128::from_str_radix(o, 8).ok()
            } else if let Some(b) = text.strip_prefix("0b") {
                u128::from_str_radix(b, 2).ok()
            } else if text.chars().all(|c| c.is_ascii_digit()) {
                text.parse::<u128>().ok().or(Some(u128::MAX))
            } else {
                None
            };
            // A literal the parser already refused needs no second problem.
            if value.is_some_and(|v| v > i64::MAX as u128) && syntax_line != Some(file.source.place(t.start(i)).0) {
                out.push(at(
                    file,
                    t.span(i),
                    Severity::Warning,
                    "big-int",
                    "this number does not fit in 64 bits, and the keyboard's Python has no big integers".into(),
                    Some("past 64 bits a result becomes a float".into()),
                ));
            }
        }
    }
}
