//! [shake]: drop the parts of vendored packages a plugin never reaches.
//!
//! Two levels. Modules: a vendored file nothing imports (from the entry, the
//! plugin's own modules, and onwards through every import in what they
//! reach) never runs, so it goes. Symbols ([ShakeOptions::symbols]): inside
//! the vendored modules that stay, a top-level function, class or
//! assignment that nothing kept refers to goes too, and so does a top-level
//! import whose names nobody uses, when loading what it names has no effect.
//!
//! Only what provably changes nothing is removed. A top-level statement is a
//! candidate only when running it does nothing but bind its names: a `def`
//! whose defaults and decorators are plain values or known-pure standard
//! library calls, a class whose bases are names and whose body is such
//! defs and assignments, an assignment of such a value. Anything else runs
//! for its effects and stays, with every name it mentions. A name is "used"
//! when any kept code, or a string that is exactly that name, mentions it;
//! a module used as a value (passed, stored, `getattr`'d) keeps everything,
//! as does a module that reaches its own namespace (`globals()`, `exec`,
//! a module `__getattr__`) or is star-imported. Removed statements leave
//! their line breaks behind, so line numbers in tracebacks do not move.
//! If any shaken file would not parse, symbol shaking is abandoned.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};

use wm_python_tooling::ast::{Expr, ExprKind, Stmt, StmtKind};
use wm_python_tooling::text::Span;

use crate::analyse::class_header;
use crate::probe::{features, Probe, Support};
use crate::program::{prefixes, root_of, ImportKind, ModFile, Program};
use crate::{ShakeOptions, ShakeReport};

/// Builtins that only compute: calling one at a module's top level has no effect beyond its value.
const PURE_BUILTINS: &[&str] = &[
    "int", "float", "str", "bytes", "bool", "tuple", "list", "dict", "set", "frozenset", "object", "type", "range",
    "len", "sorted", "reversed", "zip", "enumerate", "map", "filter", "min", "max", "sum", "abs", "all", "any",
    "isinstance", "issubclass", "repr", "chr", "ord", "hash", "property", "staticmethod", "classmethod", "round",
    "divmod", "pow", "hex", "oct", "bin", "iter", "slice", "bytearray", "format", "callable",
];

/// Builtin types whose class methods only compute: `str.maketrans`, `dict.fromkeys`.
const PURE_TYPES: &[&str] = &["str", "bytes", "int", "float", "dict", "set", "frozenset", "tuple", "list", "bytearray"];

/// Standard library calls with no effect beyond their value; `*` for every name.
fn pure_stdlib(module: &str, name: &str) -> bool {
    let names: &[&str] = match module {
        "math" | "typing" | "itertools" | "base64" | "statistics" => return true,
        "re" => &["compile", "escape"],
        "collections" => &["namedtuple", "OrderedDict", "defaultdict", "deque", "Counter", "ChainMap"],
        "functools" => &["partial", "lru_cache", "cache", "wraps", "total_ordering", "cached_property", "cmp_to_key"],
        "dataclasses" => &["dataclass", "field", "make_dataclass"],
        "enum" => &["Enum", "IntEnum", "Flag", "IntFlag", "StrEnum", "auto", "unique"],
        "abc" => &["abstractmethod", "abstractproperty", "ABCMeta"],
        "datetime" => &["timedelta", "datetime", "date", "time", "timezone"],
        "string" => &["Template", "Formatter"],
        "textwrap" => &["dedent", "indent"],
        "uuid" => &["UUID"],
        "json" => &["dumps", "loads"],
        "html" => &["escape", "unescape"],
        _ => return false,
    };
    names.contains(&name)
}

/// Names that let a module reach its own namespace or load code by name.
const DYNAMIC: &[&str] = &["globals", "locals", "vars", "exec", "eval", "__import__", "importlib", "import_module"];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Def,
    Class,
    Assign,
    Import,
    Other,
}

/// One statement of a module's own body (decorators joined to what they decorate).
struct Top {
    span: Span,
    kind: Kind,
    binds: Vec<String>,
    /// Running it only binds its names.
    quiet: bool,
    /// It may be removed: quiet, and alone on its lines.
    removable: bool,
    /// Index into the file's imports, for an import statement.
    import: Option<usize>,
}

struct Info {
    top: Vec<Top>,
    by_name: HashMap<String, Vec<usize>>,
    /// Why the module keeps everything, when it does.
    keep_all: Option<String>,
    /// Names bound to modules of the program by its imports.
    modules: HashMap<String, Vec<String>>,
}

/// What a module-level expression may call without effect: the module's
/// top-level names and the standard modules its imports bind.
struct Scope<'a> {
    names: &'a HashMap<String, usize>,
    /// `import re` / `import re as r`: binding to module.
    plain: HashMap<String, String>,
    /// `from re import compile`: binding to (module, name).
    from: HashMap<String, (String, String)>,
    once: HashSet<String>,
}

pub fn shake(files: &BTreeMap<String, String>, entry: &str, vendored_roots: &[String], opts: &ShakeOptions) -> ShakeReport {
    let bytes_before: usize = files.values().map(|s| s.len()).sum();
    let roots: HashSet<&str> = vendored_roots.iter().map(String::as_str).collect();
    let program = Program::new(files, Some(entry));
    let vendored: Vec<bool> = program.files.iter().map(|f| f.name != "__main__" && roots.contains(root_of(&f.path).as_str())).collect();
    let mut notes = Vec::new();
    let mut probe = Probe::new();
    let skip_type_checking = features().type_checking_false;

    // ---- modules ------------------------------------------------------------
    let is_module = |n: &str| program.by_name.contains_key(n);
    let mut dynamic_roots: BTreeMap<String, String> = BTreeMap::new();
    let mut plugin_dynamic = None;
    // Only when the interpreter can load a module by a name made at run time;
    // otherwise an import statement is the one way a module loads.
    let dynamic_import = features().dynamic_import;
    for (i, f) in program.files.iter().enumerate().filter(|_| dynamic_import) {
        let all = Span::new(0, f.source.doc.len());
        if let Some(u) = f.source.names(all, &[]).into_iter().find(|u| !u.after_dot && !u.in_string && matches!(u.name.as_str(), "__import__" | "importlib" | "import_module")) {
            let (line, _) = f.source.place(u.offset);
            let at = format!("{}:{line}", f.path);
            if vendored[i] {
                dynamic_roots.entry(root_of(&f.path)).or_insert(at);
            } else {
                plugin_dynamic.get_or_insert(at);
            }
        }
    }
    let mut reached: HashSet<usize> = HashSet::new();
    let mut queue: VecDeque<usize> = (0..program.files.len()).filter(|i| !vendored[*i]).collect();
    if let Some(at) = &plugin_dynamic {
        notes.push(format!("{at} imports modules by name at run time, so every vendored file was kept"));
        queue.extend(0..program.files.len());
    }
    while let Some(i) = queue.pop_front() {
        if !reached.insert(i) {
            continue;
        }
        let f = &program.files[i];
        for imp in &f.imports {
            if imp.type_checking && skip_type_checking {
                continue;
            }
            for name in f.loads(imp, &is_module) {
                if let Some(&j) = program.by_name.get(&name) {
                    queue.push_back(j);
                }
            }
        }
        if vendored[i] {
            if let Some(at) = dynamic_roots.get(&root_of(&f.path)) {
                let root = root_of(&f.path);
                if !notes.iter().any(|n: &String| n.starts_with(&format!("'{root}'"))) {
                    notes.push(format!("'{root}' imports modules by name at run time ({at}), so all its files were kept"));
                }
                for (j, g) in program.files.iter().enumerate() {
                    if root_of(&g.path) == root && vendored[j] {
                        queue.push_back(j);
                    }
                }
            }
        }
    }
    for root in vendored_roots {
        let count = program.files.iter().enumerate().filter(|(i, f)| vendored[*i] && &root_of(&f.path) == root).count();
        let kept = program.files.iter().enumerate().filter(|(i, f)| vendored[*i] && reached.contains(i) && &root_of(&f.path) == root).count();
        if count > 0 && kept == 0 {
            notes.push(format!("'{root}' is not imported anywhere: all {count} of its files were left out"));
        }
    }

    let mut out = files.clone();
    let mut removed_files = Vec::new();
    let mut removed_symbols = Vec::new();
    let mut keep: HashSet<usize> = reached.clone();
    if opts.symbols && plugin_dynamic.is_none() {
        match symbols(&program, &vendored, &reached, &dynamic_roots, &mut probe, &mut notes) {
            Some(result) => {
                keep = result.loaded;
                // A package that imports by name keeps all its files once any of it loads.
                let dynamic: HashSet<String> = keep.iter().map(|i| root_of(&program.files[*i].path)).filter(|r| dynamic_roots.contains_key(r)).collect();
                keep.extend((0..program.files.len()).filter(|i| vendored[*i] && dynamic.contains(&root_of(&program.files[*i].path))));
                for (i, text) in result.rewritten {
                    out.insert(program.files[i].path.clone(), text);
                }
                removed_symbols = result.removed;
            }
            None => notes.push("symbol shaking was skipped: a shaken file would not parse, so only whole modules were left out".into()),
        }
    }
    for (i, f) in program.files.iter().enumerate() {
        if vendored[i] && !keep.contains(&i) {
            out.remove(&f.path);
            removed_files.push(f.path.clone());
        }
    }
    removed_files.sort();
    let bytes_after = out.values().map(|s| s.len()).sum();
    ShakeReport { files: out, removed_files, removed_symbols, bytes_before, bytes_after, notes }
}

// ---- symbols ----------------------------------------------------------------

struct Shaken {
    loaded: HashSet<usize>,
    rewritten: Vec<(usize, String)>,
    removed: Vec<(String, String)>,
}

fn names_in_targets(e: &Expr, out: &mut Vec<String>) -> bool {
    match &e.kind {
        ExprKind::Name(n) => {
            out.push(n.clone());
            true
        }
        ExprKind::Tuple(items) | ExprKind::List(items) => items.iter().all(|i| names_in_targets(i, out)),
        ExprKind::Starred(inner) => names_in_targets(inner, out),
        _ => false,
    }
}

impl Scope<'_> {
    fn callee(&self, f: &Expr, program: &Program, probe: &mut Probe) -> bool {
        let stdlib = |m: &str, probe: &mut Probe| !program.exists(m) && probe.module_exists(m);
        match &f.kind {
            ExprKind::Name(n) => {
                if let Some((m, orig)) = self.from.get(n) {
                    return self.once.contains(n) && stdlib(m, probe) && pure_stdlib(m, orig);
                }
                !self.names.contains_key(n) && PURE_BUILTINS.contains(&n.as_str())
            }
            ExprKind::Attribute { value, attr, .. } => match &value.kind {
                ExprKind::Name(x) => {
                    if let Some(m) = self.plain.get(x) {
                        return self.once.contains(x) && stdlib(m, probe) && pure_stdlib(m, attr);
                    }
                    !self.names.contains_key(x) && PURE_TYPES.contains(&x.as_str())
                }
                ExprKind::Str { .. } | ExprKind::Number | ExprKind::List(_) | ExprKind::Tuple(_) | ExprKind::Dict(_) | ExprKind::Set(_) => true,
                _ => false,
            },
            _ => false,
        }
    }

    fn all<'e>(&self, items: impl IntoIterator<Item = &'e Expr>, program: &Program, probe: &mut Probe) -> bool {
        items.into_iter().all(|x| self.pure(x, program, probe))
    }

    fn pure(&self, e: &Expr, program: &Program, probe: &mut Probe) -> bool {
        match &e.kind {
            ExprKind::Name(_) | ExprKind::Number | ExprKind::Const(_) => true,
            ExprKind::Str { fields, .. } => self.all(fields, program, probe),
            ExprKind::Attribute { value, .. } | ExprKind::Unary { operand: value, .. } => self.pure(value, program, probe),
            ExprKind::Starred(value) | ExprKind::DoubleStarred(value) => self.pure(value, program, probe),
            ExprKind::Subscript { value, index } => self.all([&**value, &**index], program, probe),
            ExprKind::Slice { lower, upper, step } => self.all([lower, upper, step].into_iter().flatten().map(|b| &**b), program, probe),
            ExprKind::Binary { left, right, .. } | ExprKind::BoolOp { left, right, .. } => self.all([&**left, &**right], program, probe),
            ExprKind::Compare { left, ops } => self.all(std::iter::once(&**left).chain(ops.iter().map(|(_, x)| x)), program, probe),
            ExprKind::Ternary { body, test, orelse } => self.all([&**body, &**test, &**orelse], program, probe),
            ExprKind::Lambda { params, .. } => self.all(params.params.iter().filter_map(|p| p.default.as_ref()), program, probe),
            ExprKind::List(items) | ExprKind::Tuple(items) | ExprKind::Set(items) => self.all(items, program, probe),
            ExprKind::Dict(pairs) => self.all(pairs.iter().flat_map(|(k, v)| [k, v]), program, probe),
            ExprKind::Comp { elt, value, clauses, .. } => {
                self.pure(elt, program, probe)
                    && value.as_ref().is_none_or(|v| self.pure(v, program, probe))
                    && clauses.iter().all(|c| self.pure(&c.target, program, probe) && self.pure(&c.iter, program, probe) && self.all(&c.ifs, program, probe))
            }
            ExprKind::Call { func, args, keywords, .. } => {
                self.callee(func, program, probe) && self.all(args.iter().chain(keywords.iter().map(|k| &k.value)), program, probe)
            }
            ExprKind::Walrus { .. } | ExprKind::Prefixed { .. } | ExprKind::Error => false,
        }
    }

    fn decorator(&self, e: &Expr, in_class: bool, program: &Program, probe: &mut Probe) -> bool {
        match &e.kind {
            ExprKind::Call { func, args, keywords, .. } => {
                self.callee(func, program, probe) && args.iter().chain(keywords.iter().map(|k| &k.value)).all(|x| self.pure(x, program, probe))
            }
            ExprKind::Attribute { value, attr, .. } if in_class && matches!(value.kind, ExprKind::Name(_)) && matches!(attr.as_str(), "setter" | "getter" | "deleter") => true,
            _ => self.callee(e, program, probe),
        }
    }

    /// Whether running [body] (a module's or a class's) only binds names.
    /// Returns the verdict for each statement, decorators joined to the next.
    fn quiet_block(&self, body: &[Stmt], in_class: bool, classes_ok: bool, file: &ModFile, program: &Program, probe: &mut Probe) -> bool {
        let mut decorators_quiet = true;
        let mut pending = false;
        for s in body {
            if let StmtKind::Unsupported { what: "decorators", exprs, .. } = &s.kind {
                pending = true;
                decorators_quiet &= exprs.iter().all(|e| self.decorator(e, in_class, program, probe));
                continue;
            }
            let q = self.quiet_stmt(s, in_class, classes_ok, file, program, probe);
            let decorable = matches!(s.kind, StmtKind::Def { .. }) || matches!(s.kind, StmtKind::Unsupported { what: "class", .. });
            if !(q && (!pending || (decorable && decorators_quiet))) {
                return false;
            }
            pending = false;
            decorators_quiet = true;
        }
        !pending
    }

    fn quiet_stmt(&self, s: &Stmt, in_class: bool, classes_ok: bool, file: &ModFile, program: &Program, probe: &mut Probe) -> bool {
        match &s.kind {
            StmtKind::Def { params, .. } => params.params.iter().filter_map(|p| p.default.as_ref()).all(|d| self.pure(d, program, probe)),
            StmtKind::Unsupported { what: "class", exprs, body, .. } => {
                if !classes_ok {
                    return false;
                }
                let (_, keywords) = class_header(file, s.span);
                keywords.is_empty()
                    && exprs.iter().all(|b| crate::source::attribute_chain(b).is_some())
                    && self.quiet_block(body, true, classes_ok, file, program, probe)
            }
            StmtKind::Assign { targets, value } => {
                let mut names = Vec::new();
                targets.iter().all(|t| names_in_targets(t, &mut names)) && self.pure(value, program, probe)
            }
            StmtKind::AnnAssign { target, value, .. } => {
                matches!(target.kind, ExprKind::Name(_)) && value.as_ref().is_none_or(|v| self.pure(v, program, probe))
            }
            StmtKind::Expr(e) => matches!(e.kind, ExprKind::Str { fstring: false, .. }) && in_class,
            StmtKind::Pass => true,
            _ => false,
        }
    }
}

/// `if __name__ == "__main__":`, which never runs in a module that is imported.
fn is_main_guard(s: &Stmt) -> bool {
    let StmtKind::If { test, orelse, .. } = &s.kind else { return false };
    if !orelse.is_empty() {
        return false;
    }
    let ExprKind::Compare { left, ops } = &test.kind else { return false };
    let is_name = |e: &Expr| matches!(&e.kind, ExprKind::Name(n) if n == "__name__");
    let is_main = |e: &Expr| matches!(&e.kind, ExprKind::Str { value: Some(v), .. } if v == "__main__");
    ops.len() == 1 && ops[0].0 == "==" && ((is_name(left) && is_main(&ops[0].1)) || (is_main(left) && is_name(&ops[0].1)))
}

/// Whether the name at [offset] is a function's own (a parameter, a local):
/// such a name holds a module only if one was passed or stored, which is
/// already a use of the module as a value where that happened.
fn local_name(file: &ModFile, offset: u32) -> bool {
    file.source.doc.analysis.symbol_at(offset).is_some_and(|s| s.scope != 0)
}

fn is_docstring_or_pass(s: &Stmt) -> bool {
    matches!(s.kind, StmtKind::Pass) || matches!(&s.kind, StmtKind::Expr(e) if matches!(e.kind, ExprKind::Str { fstring: false, .. }))
}

fn info_of(file: &ModFile, vendored: bool, classes_ok: bool, program: &Program, probe: &mut Probe) -> Info {
    let analysis = &file.source.doc.analysis;
    let module_names = &analysis.scopes[0].names;
    let mut scope = Scope { names: module_names, plain: HashMap::new(), from: HashMap::new(), once: HashSet::new() };
    for (name, id) in module_names {
        if analysis.symbols[*id].writes.is_empty() {
            scope.once.insert(name.clone());
        }
    }
    for imp in file.imports.iter().filter(|i| i.top) {
        match &imp.kind {
            ImportKind::Plain(names) => {
                for b in names {
                    let module = if b.alias.is_some() { b.name.clone() } else { b.binds(true) };
                    scope.plain.insert(b.binds(true), module);
                }
            }
            ImportKind::From { module: Some(m), names, .. } => {
                for b in names {
                    scope.from.insert(b.binds(false), (m.clone(), b.name.clone()));
                }
            }
            _ => {}
        }
    }
    let mut top: Vec<Top> = Vec::new();
    let mut decorators: Vec<&Stmt> = Vec::new();
    let other = |span: Span| Top { span, kind: Kind::Other, binds: Vec::new(), quiet: false, removable: false, import: None };
    for (index, s) in file.source.body().iter().enumerate() {
        if let StmtKind::Unsupported { what: "decorators", .. } = &s.kind {
            decorators.push(s);
            continue;
        }
        let decorable = matches!(s.kind, StmtKind::Def { .. }) || matches!(s.kind, StmtKind::Unsupported { what: "class", .. });
        if !decorable {
            // Decorators over something that is not a def or class: keep them as they are.
            top.extend(decorators.drain(..).map(|d| other(d.span)));
        }
        let decorators_quiet = decorators.iter().all(|d| match &d.kind {
            StmtKind::Unsupported { exprs, .. } => exprs.iter().all(|e| scope.decorator(e, false, program, probe)),
            _ => false,
        });
        let start = decorators.first().map_or(s.span.start, |d| d.span.start);
        decorators.clear();
        let span = Span::new(start, s.span.end);
        let quiet_stmt = |scope: &Scope, probe: &mut Probe| scope.quiet_stmt(s, false, classes_ok, file, program, probe);
        let mut binds = Vec::new();
        let (kind, quiet, removable) = match &s.kind {
            StmtKind::Def { name, .. } => {
                binds.push(name.clone());
                let q = decorators_quiet && quiet_stmt(&scope, probe);
                (Kind::Def, q, q)
            }
            StmtKind::Unsupported { what: "class", binds: names, .. } => {
                binds.extend(names.iter().filter_map(|b| if let ExprKind::Name(n) = &b.kind { Some(n.clone()) } else { None }));
                let q = decorators_quiet && quiet_stmt(&scope, probe);
                (Kind::Class, q, q)
            }
            StmtKind::Assign { targets, .. } => {
                for t in targets {
                    names_in_targets(t, &mut binds);
                }
                let q = quiet_stmt(&scope, probe);
                (Kind::Assign, q, q)
            }
            StmtKind::AnnAssign { target, .. } => {
                names_in_targets(target, &mut binds);
                let q = quiet_stmt(&scope, probe);
                (Kind::Assign, q, q)
            }
            StmtKind::Import(_) | StmtKind::ImportFrom { .. } => (Kind::Import, false, false),
            // `if __name__ == "__main__":` never runs in an imported module.
            _ if vendored && is_main_guard(s) => (Kind::Other, true, true),
            _ => (Kind::Other, is_docstring_or_pass(s), false),
        };
        let import = file.imports.iter().position(|i| i.top && i.top_index == index);
        if let Some(k) = import {
            binds = match &file.imports[k].kind {
                ImportKind::Plain(names) => names.iter().map(|b| b.binds(true)).collect(),
                ImportKind::From { names, .. } => names.iter().map(|b| b.binds(false)).collect(),
            };
        }
        let removable = removable && file.source.whole_lines(span);
        top.push(Top { span, kind, binds, quiet, removable, import });
    }
    top.extend(decorators.drain(..).map(|d| other(d.span)));
    let mut by_name: HashMap<String, Vec<usize>> = HashMap::new();
    for (i, t) in top.iter().enumerate() {
        for b in &t.binds {
            by_name.entry(b.clone()).or_default().push(i);
        }
    }
    // Why everything stays.
    let mut keep_all = None;
    let all = Span::new(0, file.source.doc.len());
    for u in file.source.names(all, &file.import_spans) {
        if u.after_dot || u.in_string {
            continue;
        }
        // Only what this interpreter has: a missing builtin fails the same way shaken or not.
        let reaches = (DYNAMIC.contains(&u.name.as_str()) && probe.name_exists(&u.name))
            || (u.name == "sys" && u.chain.first().is_some_and(|c| c == "modules") && probe.member_exists("sys", "modules"));
        if reaches {
            let (line, _) = file.source.place(u.offset);
            let what = if u.name == "sys" { "sys.modules".to_string() } else { format!("{}()", u.name) };
            keep_all = Some(format!("it uses {what} (line {line})"));
            break;
        }
    }
    if keep_all.is_none() && features().module_getattr {
        for special in ["__getattr__", "__dir__"] {
            if by_name.contains_key(special) {
                keep_all = Some(format!("it defines a module {special}"));
            }
        }
    }
    if keep_all.is_none() && file.imports.iter().any(|i| matches!(i.kind, ImportKind::From { star: true, .. })) {
        keep_all = Some("it star-imports".into());
    }
    let mut modules: HashMap<String, Vec<String>> = HashMap::new();
    for imp in &file.imports {
        match &imp.kind {
            ImportKind::Plain(names) => {
                for b in names {
                    let target = if b.alias.is_some() { b.name.clone() } else { b.binds(true) };
                    if program.exists(&target) {
                        modules.entry(b.binds(true)).or_default().push(target);
                    }
                }
            }
            ImportKind::From { module: Some(m), names, .. } => {
                for b in names {
                    let sub = format!("{m}.{}", b.name);
                    if program.exists(&sub) {
                        modules.entry(b.binds(false)).or_default().push(sub);
                    }
                }
            }
            _ => {}
        }
    }
    Info { top, by_name, keep_all, modules }
}

enum Work {
    Load(String),
    Ref(String, String),
    All(String, String),
    Keep(usize, usize),
    Use(usize, usize, usize),
}

struct State<'a> {
    program: &'a Program,
    vendored: &'a [bool],
    reached: &'a HashSet<usize>,
    infos: Vec<Option<Info>>,
    loaded: HashSet<usize>,
    /// Modules that keep everything, and why ("" for the plugin's own).
    all: HashMap<usize, String>,
    kept: HashSet<(usize, usize)>,
    /// `(file, statement, name)`: an import's names that are used.
    used: HashSet<(usize, usize, usize)>,
    /// Names asked of a module before it loaded.
    pending: HashMap<usize, Vec<String>>,
    pinned: HashSet<String>,
    quiet_memo: HashMap<String, bool>,
    work: VecDeque<Work>,
}

impl<'a> State<'a> {
    fn info(&self, i: usize) -> &Info {
        self.infos[i].as_ref().expect("info of a reached file")
    }

    fn file(&self, name: &str) -> Option<usize> {
        self.program.by_name.get(name).copied().filter(|i| self.reached.contains(i))
    }

    /// Whether loading [name] (and the packages above it) only binds names.
    fn quiet_module(&mut self, name: &str, probe: &mut Probe) -> bool {
        prefixes(name).iter().all(|p| self.quiet_one(p, probe))
    }

    fn quiet_one(&mut self, name: &str, probe: &mut Probe) -> bool {
        if let Some(v) = self.quiet_memo.get(name) {
            return *v;
        }
        let program: &'a Program = self.program;
        let Some(i) = self.file(name) else {
            // A folder with no __init__.py, the standard library or wm: nothing of the plugin's runs.
            let v = program.exists(name) || name == "wm" || name.starts_with("wm.") || probe.module_exists(name);
            self.quiet_memo.insert(name.to_string(), v);
            return v;
        };
        if !self.vendored[i] {
            self.quiet_memo.insert(name.to_string(), false);
            return false;
        }
        // Assume yes while looking, so an import cycle does not decide itself.
        self.quiet_memo.insert(name.to_string(), true);
        let file = &program.files[i];
        let mut v = self.info(i).keep_all.is_none();
        let mut targets = Vec::new();
        for t in &self.info(i).top {
            match t.import {
                Some(k) => {
                    let imp = &file.imports[k];
                    if matches!(imp.kind, ImportKind::From { module: None, .. } | ImportKind::From { star: true, .. }) {
                        v = false;
                    }
                    targets.extend(file.loads(imp, &|n| program.by_name.contains_key(n)));
                }
                None if !t.quiet => v = false,
                None => {}
            }
        }
        if v {
            v = targets.iter().all(|t| self.quiet_module(t, probe));
        }
        self.quiet_memo.insert(name.to_string(), v);
        v
    }

    /// Whether the top-level import [t] of file [i] may go when none of its names are used.
    fn droppable(&mut self, i: usize, t: usize, probe: &mut Probe) -> bool {
        let program: &'a Program = self.program;
        let file = &program.files[i];
        let top = &self.info(i).top[t];
        if !file.source.whole_lines(top.span) {
            return false;
        }
        let imp = &file.imports[top.import.expect("import")];
        let known = |m: &str, probe: &mut Probe| program.exists(m) || m == "wm" || m.starts_with("wm.") || probe.module_exists(m);
        match &imp.kind {
            ImportKind::From { module: None, .. } | ImportKind::From { star: true, .. } => return false,
            ImportKind::From { module: Some(m), .. } => {
                if !known(m, probe) {
                    return false;
                }
            }
            ImportKind::Plain(names) => {
                if !names.iter().all(|b| known(&b.name, probe)) {
                    return false;
                }
            }
        }
        let loads = file.loads(imp, &|n| program.by_name.contains_key(n));
        loads.iter().all(|m| !self.pinned.contains(m)) && loads.iter().all(|m| self.quiet_module(m, probe))
    }

    fn run(&mut self, probe: &mut Probe) {
        let program: &'a Program = self.program;
        while let Some(w) = self.work.pop_front() {
            match w {
                Work::Load(name) => {
                    for p in prefixes(&name) {
                        if let Some(i) = self.file(&p) {
                            if self.loaded.insert(i) {
                                self.on_load(i, probe);
                            }
                        }
                    }
                }
                Work::Ref(module, name) => {
                    let Some(i) = self.file(&module) else { continue };
                    if !self.vendored[i] || self.all.contains_key(&i) {
                        continue;
                    }
                    if !self.loaded.contains(&i) {
                        self.pending.entry(i).or_default().push(name);
                        continue;
                    }
                    let Some(stmts) = self.info(i).by_name.get(&name).cloned() else { continue };
                    for t in stmts {
                        if let Some(k) = self.info(i).top[t].import {
                            let imp = &program.files[i].imports[k];
                            let (plain, names) = match &imp.kind {
                                ImportKind::Plain(n) => (true, n),
                                ImportKind::From { names, .. } => (false, names),
                            };
                            for (j, b) in names.iter().enumerate() {
                                if b.binds(plain) == name {
                                    self.work.push_back(Work::Use(i, t, j));
                                }
                            }
                        }
                        self.work.push_back(Work::Keep(i, t));
                    }
                }
                Work::All(module, why) => {
                    let Some(i) = self.file(&module) else { continue };
                    if !self.vendored[i] || self.all.contains_key(&i) {
                        continue;
                    }
                    self.all.insert(i, why);
                    if self.loaded.contains(&i) {
                        self.keep_everything(i);
                    }
                }
                Work::Keep(i, t) => {
                    if self.kept.insert((i, t)) {
                        self.keep(i, t, probe);
                    }
                }
                Work::Use(i, t, j) => {
                    if !self.used.insert((i, t, j)) {
                        continue;
                    }
                    self.work.push_back(Work::Keep(i, t));
                    let k = self.info(i).top[t].import.expect("import");
                    if let ImportKind::From { module: Some(m), names, .. } = &program.files[i].imports[k].kind {
                        let sub = format!("{m}.{}", names[j].name);
                        if program.by_name.contains_key(&sub) {
                            self.work.push_back(Work::Load(sub));
                        }
                        self.work.push_back(Work::Ref(m.clone(), names[j].name.clone()));
                    }
                }
            }
        }
    }

    fn on_load(&mut self, i: usize, probe: &mut Probe) {
        if !self.vendored[i] {
            self.all.insert(i, String::new());
        }
        if let Some(why) = self.info(i).keep_all.clone() {
            self.all.entry(i).or_insert(why);
        }
        if self.all.contains_key(&i) {
            self.keep_everything(i);
            return;
        }
        for t in 0..self.info(i).top.len() {
            let (kind, removable) = (self.info(i).top[t].kind, self.info(i).top[t].removable);
            let keep = match kind {
                Kind::Import => !self.droppable(i, t, probe),
                _ => !removable,
            };
            if keep {
                self.work.push_back(Work::Keep(i, t));
            }
        }
        if let Some(names) = self.pending.remove(&i) {
            let module = self.program.files[i].name.clone();
            for n in names {
                self.work.push_back(Work::Ref(module.clone(), n));
            }
        }
    }

    fn keep_everything(&mut self, i: usize) {
        let program: &'a Program = self.program;
        for t in 0..self.info(i).top.len() {
            self.work.push_back(Work::Keep(i, t));
            if let Some(k) = self.info(i).top[t].import {
                let n = match &program.files[i].imports[k].kind {
                    ImportKind::Plain(n) => n.len(),
                    ImportKind::From { names, .. } => names.len(),
                };
                for j in 0..n {
                    self.work.push_back(Work::Use(i, t, j));
                }
            }
        }
    }

    /// Keep statement [t] of file [i]: what it loads, and every name its code mentions.
    fn keep(&mut self, i: usize, t: usize, probe: &mut Probe) {
        let program: &'a Program = self.program;
        let file = &program.files[i];
        let (span, import) = (self.info(i).top[t].span, self.info(i).top[t].import);
        if let Some(k) = import {
            match &file.imports[k].kind {
                ImportKind::Plain(names) => {
                    for b in names {
                        self.work.push_back(Work::Load(b.name.clone()));
                    }
                }
                ImportKind::From { module: Some(m), names, star, .. } => {
                    self.work.push_back(Work::Load(m.clone()));
                    if *star {
                        self.work.push_back(Work::All(m.clone(), format!("{} star-imports it", file.path)));
                    }
                    // A submodule whose loading does something stays loaded where it was.
                    for (j, b) in names.iter().enumerate() {
                        let sub = format!("{m}.{}", b.name);
                        if program.by_name.contains_key(&sub) && (self.pinned.contains(&sub) || !self.quiet_module(&sub, probe)) {
                            self.work.push_back(Work::Use(i, t, j));
                        }
                    }
                }
                ImportKind::From { module: None, .. } => {}
            }
            return;
        }
        let module = file.name.clone();
        for u in file.source.names(span, &file.import_spans) {
            if u.after_dot || u.keyword {
                continue;
            }
            self.work.push_back(Work::Ref(module.clone(), u.name.clone()));
            if u.in_string || u.binding || u.compared || local_name(file, u.offset) {
                continue;
            }
            let Some(targets) = self.info(i).modules.get(&u.name).cloned() else { continue };
            for target in targets {
                let (line, _) = file.source.place(u.offset);
                if u.chain.is_empty() {
                    self.work.push_back(Work::All(target, format!("{}:{line} uses the module as a value", file.path)));
                    continue;
                }
                let mut current = target;
                for attr in &u.chain {
                    if matches!(attr.as_str(), "__dict__" | "__getattr__" | "__dir__") {
                        self.work.push_back(Work::All(current.clone(), format!("{}:{line} reads its {attr}", file.path)));
                        break;
                    }
                    self.work.push_back(Work::Ref(current.clone(), attr.clone()));
                    let sub = format!("{current}.{attr}");
                    if !program.exists(&sub) {
                        break;
                    }
                    current = sub;
                }
            }
        }
        // Imports inside the statement (in a function, a try, an if) run as they are.
        for imp in file.imports.iter().filter(|x| !x.top && span.start <= x.span.start && x.span.end <= span.end) {
            match &imp.kind {
                ImportKind::Plain(names) => {
                    for b in names {
                        self.work.push_back(Work::Load(b.name.clone()));
                    }
                }
                ImportKind::From { module: Some(m), names, star, .. } => {
                    self.work.push_back(Work::Load(m.clone()));
                    if *star {
                        self.work.push_back(Work::All(m.clone(), format!("{} star-imports it", file.path)));
                    }
                    for b in names {
                        let sub = format!("{m}.{}", b.name);
                        if program.by_name.contains_key(&sub) {
                            self.work.push_back(Work::Load(sub));
                        }
                        self.work.push_back(Work::Ref(m.clone(), b.name.clone()));
                    }
                }
                ImportKind::From { module: None, .. } => {}
            }
        }
    }
}

fn symbols(
    program: &Program,
    vendored: &[bool],
    reached: &HashSet<usize>,
    dynamic_roots: &BTreeMap<String, String>,
    probe: &mut Probe,
    notes: &mut Vec<String>,
) -> Option<Shaken> {
    // Classes stay when any class could run code as others are made.
    let mut classes_ok = true;
    for &i in reached {
        let f = &program.files[i];
        let all = Span::new(0, f.source.doc.len());
        let f_ = features();
        let hooks = |n: &str| (n == "__init_subclass__" && f_.init_subclass) || (matches!(n, "with_metaclass" | "add_metaclass") && f_.metaclass != Support::Fails);
        if f.source.names(all, &[]).iter().any(|u| hooks(&u.name)) {
            classes_ok = false;
            notes.push(format!("classes were kept: {} hooks into class creation", f.path));
            break;
        }
        let mut metaclass = false;
        wm_python_tooling::ast::walk_stmts(f.source.body(), &mut |s| {
            if let StmtKind::Unsupported { what: "class", .. } = &s.kind {
                if !class_header(f, s.span).1.is_empty() {
                    metaclass = true;
                }
            }
        });
        // A class keyword the interpreter refuses never runs a metaclass.
        if metaclass && f_.metaclass != Support::Fails {
            classes_ok = false;
            notes.push(format!("classes were kept: {} makes a class with keyword arguments", f.path));
            break;
        }
    }
    let mut infos: Vec<Option<Info>> = Vec::with_capacity(program.files.len());
    for (i, f) in program.files.iter().enumerate() {
        infos.push(reached.contains(&i).then(|| info_of(f, vendored[i], classes_ok, program, probe)));
    }
    for (root, at) in dynamic_roots {
        for (i, f) in program.files.iter().enumerate() {
            if &root_of(&f.path) == root {
                if let Some(info) = infos[i].as_mut() {
                    info.keep_all.get_or_insert(format!("'{root}' imports modules by name at run time ({at})"));
                }
            }
        }
    }
    // Submodules reached as attributes (`pkg.sub.f()`) stay loaded by whatever loads them.
    let mut pinned = HashSet::new();
    for &i in reached {
        let f = &program.files[i];
        let info = infos[i].as_ref().expect("info");
        let all = Span::new(0, f.source.doc.len());
        for u in f.source.names(all, &f.import_spans) {
            if u.after_dot || u.in_string || u.binding || u.compared || local_name(f, u.offset) {
                continue;
            }
            let Some(targets) = info.modules.get(&u.name) else { continue };
            for target in targets {
                let mut current = target.clone();
                for attr in &u.chain {
                    let sub = format!("{current}.{attr}");
                    if !program.exists(&sub) {
                        break;
                    }
                    pinned.insert(sub.clone());
                    current = sub;
                }
            }
        }
    }
    let mut state = State {
        program,
        vendored,
        reached,
        infos,
        loaded: HashSet::new(),
        all: HashMap::new(),
        kept: HashSet::new(),
        used: HashSet::new(),
        pending: HashMap::new(),
        pinned,
        quiet_memo: HashMap::new(),
        work: VecDeque::new(),
    };
    for (i, f) in program.files.iter().enumerate() {
        if !vendored[i] && reached.contains(&i) {
            if f.name == "__main__" {
                // The entry is not imported by name: load it directly.
                state.loaded.insert(i);
                state.on_load(i, probe);
            } else {
                state.work.push_back(Work::Load(f.name.clone()));
            }
        }
    }
    loop {
        state.run(probe);
        // A kept `from m import a, b` none of whose names is used must still name one that exists.
        let mut forced = false;
        let mut kept: Vec<(usize, usize)> = state.kept.iter().copied().collect();
        kept.sort();
        for (i, t) in kept {
            if !vendored[i] || state.all.contains_key(&i) {
                continue;
            }
            let top = &state.infos[i].as_ref().expect("info").top[t];
            let Some(k) = top.import else { continue };
            if let ImportKind::From { names, star: false, .. } = &program.files[i].imports[k].kind {
                if !names.is_empty() && !(0..names.len()).any(|j| state.used.contains(&(i, t, j))) {
                    state.work.push_back(Work::Use(i, t, 0));
                    forced = true;
                }
            }
        }
        if !forced {
            break;
        }
    }
    let mut reasons: BTreeSet<String> = BTreeSet::new();
    for (i, why) in &state.all {
        if vendored[*i] && !why.is_empty() {
            reasons.insert(format!("{} keeps everything: {why}", program.files[*i].path));
        }
    }
    notes.extend(reasons);

    // ---- rewrite ------------------------------------------------------------
    let mut rewritten = Vec::new();
    let mut removed = Vec::new();
    let mut loaded: Vec<usize> = state.loaded.iter().copied().collect();
    loaded.sort();
    for &i in &loaded {
        if !vendored[i] || state.all.contains_key(&i) {
            continue;
        }
        let file = &program.files[i];
        let info = state.infos[i].as_ref().expect("info");
        let units = file.source.units();
        let mut edits: Vec<(Span, String)> = Vec::new();
        for (t, top) in info.top.iter().enumerate() {
            if state.kept.contains(&(i, t)) {
                let Some(k) = top.import else { continue };
                let ImportKind::From { raw, names, star: false, .. } = &file.imports[k].kind else { continue };
                let keep: Vec<String> = names
                    .iter()
                    .enumerate()
                    .filter(|(j, _)| state.used.contains(&(i, t, *j)))
                    .map(|(_, b)| match &b.alias {
                        Some(a) => format!("{} as {a}", b.name),
                        None => b.name.clone(),
                    })
                    .collect();
                if keep.len() == names.len() || keep.is_empty() || !file.source.whole_lines(top.span) {
                    continue;
                }
                let old = file.source.text(top.span);
                let breaks = old.matches('\n').count();
                let text = if breaks > 0 {
                    format!("from {raw} import ({}{})", keep.join(", "), "\n".repeat(breaks))
                } else {
                    format!("from {raw} import {}", keep.join(", "))
                };
                edits.push((top.span, text));
            } else {
                if !top.removable && top.kind != Kind::Import {
                    continue;
                }
                let span = file.source.line_span(top.span);
                let breaks = file.source.text(span).matches('\n').count();
                edits.push((span, "\n".repeat(breaks)));
                if matches!(top.kind, Kind::Def | Kind::Class | Kind::Assign) {
                    for b in &top.binds {
                        removed.push((file.path.clone(), b.clone()));
                    }
                }
            }
        }
        if edits.is_empty() {
            continue;
        }
        edits.sort_by_key(|(s, _)| s.start);
        let mut text: Vec<u16> = Vec::with_capacity(units.len());
        let mut at = 0usize;
        for (span, new) in &edits {
            let (s, e) = (span.start as usize, span.end as usize);
            if s < at {
                return None;
            }
            text.extend_from_slice(&units[at..s]);
            text.extend(new.encode_utf16());
            at = e;
        }
        text.extend_from_slice(&units[at..]);
        let new = String::from_utf16_lossy(&text);
        let original = String::from_utf16_lossy(units);
        if wm_python::interp::check(&original).is_ok() && wm_python::interp::check(&new).is_err() {
            return None;
        }
        rewritten.push((i, new));
    }
    removed.sort();
    Some(Shaken { loaded: state.loaded, rewritten, removed })
}
