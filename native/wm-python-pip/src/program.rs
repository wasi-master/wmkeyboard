//! Files as the modules they import as, and the imports each one makes,
//! resolved the way the interpreter resolves them (`interp/classes.rs`,
//! `resolve_module` and `ensure_module`).

use std::collections::{BTreeMap, HashMap};

use wm_python_tooling::ast::{Expr, ExprKind, Stmt, StmtKind};
use wm_python_tooling::text::Span;

use crate::source::Source;

/// The dotted module name a file imports as, and whether it is a package's
/// `__init__.py`. None when its path is not a module name Python could import.
/// The keyboard's own reserved names are not checked here: see
/// `wm_python::host::v5::module_name`.
pub(crate) fn module_of(path: &str) -> Option<(String, bool)> {
    let stem = path.strip_suffix(".py")?;
    let (name, package) = match stem.strip_suffix("/__init__") {
        Some(p) => (p.replace('/', "."), true),
        None if stem == "__init__" => return None,
        None => (stem.replace('/', "."), false),
    };
    let valid = !name.is_empty()
        && name.split('.').all(|part| {
            let mut chars = part.chars();
            chars.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_') && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        });
    valid.then_some((name, package))
}

/// The top-level name a file belongs to: `pkg` for `pkg/a/b.py`, `six` for `six.py`.
pub(crate) fn root_of(path: &str) -> String {
    let first = path.split('/').next().unwrap_or(path);
    first.strip_suffix(".py").unwrap_or(first).to_string()
}

/// `a.b.c` and the packages above it: `["a", "a.b", "a.b.c"]`.
pub(crate) fn prefixes(name: &str) -> Vec<String> {
    let parts: Vec<&str> = name.split('.').collect();
    (1..=parts.len()).map(|i| parts[..i].join(".")).collect()
}

/// `.x` and `..x` against the module doing the importing, as the interpreter does.
pub(crate) fn resolve(raw: &str, current: &str, is_package: bool) -> Option<String> {
    if !raw.starts_with('.') {
        return Some(raw.to_string());
    }
    let dots = raw.chars().take_while(|c| *c == '.').count();
    let rest = &raw[dots..];
    let mut package: Vec<&str> = if current == "__main__" {
        Vec::new()
    } else if is_package {
        current.split('.').collect()
    } else {
        let mut parts: Vec<&str> = current.split('.').collect();
        parts.pop();
        parts
    };
    for _ in 1..dots {
        package.pop()?;
    }
    if !rest.is_empty() {
        package.push(rest);
    }
    (!package.is_empty()).then(|| package.join("."))
}

/// Which errors the `try` statements around a statement catch.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Guard {
    pub import_error: bool,
    pub attribute_error: bool,
    pub name_error: bool,
}

impl Guard {
    pub(crate) fn add(&mut self, handlers: &[wm_python_tooling::ast::Handler]) {
        for h in handlers {
            if h.types.is_empty() {
                *self = Guard { import_error: true, attribute_error: true, name_error: true };
                return;
            }
            for t in &h.types {
                let mut names = Vec::new();
                collect_names(t, &mut names);
                for n in names {
                    match n.as_str() {
                        "Exception" | "BaseException" => {
                            *self = Guard { import_error: true, attribute_error: true, name_error: true };
                            return;
                        }
                        "ImportError" | "ModuleNotFoundError" => self.import_error = true,
                        "AttributeError" => self.attribute_error = true,
                        "NameError" => self.name_error = true,
                        _ => {}
                    }
                }
            }
        }
    }
}

/// What `with suppress(ImportError):` (or `contextlib.suppress(...)`) among
/// a `with` statement's context expressions [exprs] catches, if it has one.
pub(crate) fn suppress_guard(exprs: &[Expr]) -> Option<Guard> {
    let mut found = None;
    for e in exprs {
        let ExprKind::Call { func, args, .. } = &e.kind else { continue };
        let named = match &func.kind {
            ExprKind::Name(n) => n == "suppress",
            ExprKind::Attribute { attr, .. } => attr == "suppress",
            _ => false,
        };
        if !named {
            continue;
        }
        let g: &mut Guard = found.get_or_insert_with(Guard::default);
        for a in args {
            let mut names = Vec::new();
            collect_names(a, &mut names);
            for n in names {
                match n.as_str() {
                    "Exception" | "BaseException" => *g = Guard { import_error: true, attribute_error: true, name_error: true },
                    "ImportError" | "ModuleNotFoundError" => g.import_error = true,
                    "AttributeError" => g.attribute_error = true,
                    "NameError" => g.name_error = true,
                    _ => {}
                }
            }
        }
    }
    found
}

impl Guard {
    /// Both: what either catches.
    pub(crate) fn or(self, other: Guard) -> Guard {
        Guard {
            import_error: self.import_error || other.import_error,
            attribute_error: self.attribute_error || other.attribute_error,
            name_error: self.name_error || other.name_error,
        }
    }
}

fn collect_names(e: &Expr, out: &mut Vec<String>) {
    match &e.kind {
        ExprKind::Name(n) => out.push(n.clone()),
        ExprKind::Attribute { attr, .. } => out.push(attr.clone()),
        ExprKind::Tuple(items) | ExprKind::List(items) => items.iter().for_each(|i| collect_names(i, out)),
        _ => {}
    }
}

/// One name an import binds: `name` (dotted for a plain import), its `as`, and where.
#[derive(Clone, Debug)]
pub(crate) struct Bound {
    pub name: String,
    pub alias: Option<String>,
    pub span: Span,
}

impl Bound {
    /// The name this binds in the importing module.
    pub fn binds(&self, plain: bool) -> String {
        match &self.alias {
            Some(a) => a.clone(),
            None if plain => self.name.split('.').next().unwrap_or(&self.name).to_string(),
            None => self.name.clone(),
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) enum ImportKind {
    /// `import a.b, c as d`
    Plain(Vec<Bound>),
    /// `from m import x, y as z`; [module] resolved to an absolute name, None
    /// when a relative import climbs past the top.
    From { raw: String, module: Option<String>, module_span: Span, names: Vec<Bound>, star: bool },
}

#[derive(Clone, Debug)]
pub(crate) struct Import {
    pub span: Span,
    pub kind: ImportKind,
    /// A statement of the module's own body, not nested in anything.
    pub top: bool,
    /// Index of the module-body statement that holds it.
    pub top_index: usize,
    /// Inside a function: runs only when the function is called.
    pub in_function: bool,
    pub guard: Guard,
    /// Under `if TYPE_CHECKING:`, which never runs.
    pub type_checking: bool,
    /// Under `if __name__ == "__main__":`, which never runs in an imported module.
    pub main_only: bool,
}

/// A `.py` file of the program, parsed.
pub(crate) struct ModFile {
    pub path: String,
    /// `__main__` for the entry script.
    pub name: String,
    pub source: Source,
    pub imports: Vec<Import>,
    pub import_spans: Vec<Span>,
}

impl ModFile {
    pub fn new(path: &str, name: String, package: bool, text: &str) -> ModFile {
        let source = Source::new(text);
        let mut imports = Vec::new();
        for (i, stmt) in source.body().iter().enumerate() {
            collect_imports(stmt, i, true, false, Guard::default(), (false, false), &name, package, &mut imports);
        }
        let import_spans = imports.iter().map(|i: &Import| i.span).collect();
        ModFile { path: path.to_string(), name, source, imports, import_spans }
    }

    /// Every module an import loads when it runs: the packages above the
    /// module named, and for `from m import x` the submodule `m.x` when
    /// [is_module] says there is one.
    pub fn loads(&self, import: &Import, is_module: &dyn Fn(&str) -> bool) -> Vec<String> {
        let mut out = Vec::new();
        match &import.kind {
            ImportKind::Plain(names) => {
                for b in names {
                    out.extend(prefixes(&b.name));
                }
            }
            ImportKind::From { module: Some(m), names, .. } => {
                out.extend(prefixes(m));
                for b in names {
                    let sub = format!("{m}.{}", b.name);
                    if is_module(&sub) {
                        out.push(sub);
                    }
                }
            }
            ImportKind::From { module: None, .. } => {}
        }
        out
    }
}

/// `__name__ == "__main__"`.
pub(crate) fn is_main_test(test: &Expr) -> bool {
    let ExprKind::Compare { left, ops } = &test.kind else { return false };
    let is_name = |e: &Expr| matches!(&e.kind, ExprKind::Name(n) if n == "__name__");
    let is_main = |e: &Expr| matches!(&e.kind, ExprKind::Str { value: Some(v), .. } if v == "__main__");
    ops.len() == 1 && ops[0].0 == "==" && ((is_name(left) && is_main(&ops[0].1)) || (is_main(left) && is_name(&ops[0].1)))
}

pub(crate) fn is_type_checking(test: &Expr) -> bool {
    match &test.kind {
        ExprKind::Name(n) => n == "TYPE_CHECKING",
        ExprKind::Attribute { attr, .. } => attr == "TYPE_CHECKING",
        _ => false,
    }
}

#[allow(clippy::too_many_arguments)]
fn collect_imports(
    stmt: &Stmt,
    top_index: usize,
    top: bool,
    in_function: bool,
    guard: Guard,
    flags: (bool, bool),
    current: &str,
    package: bool,
    out: &mut Vec<Import>,
) {
    let (type_checking, main_only) = flags;
    let push = |kind: ImportKind, out: &mut Vec<Import>| {
        out.push(Import { span: stmt.span, kind, top, top_index, in_function, guard, type_checking, main_only });
    };
    match &stmt.kind {
        StmtKind::Import(names) => {
            let names = names.iter().map(|n| Bound { name: n.name.clone(), alias: n.asname.as_ref().map(|a| a.0.clone()), span: n.span }).collect();
            push(ImportKind::Plain(names), out);
        }
        StmtKind::ImportFrom { module, module_span, names, star } => {
            let names = names.iter().map(|n| Bound { name: n.name.clone(), alias: n.asname.as_ref().map(|a| a.0.clone()), span: n.span }).collect();
            let resolved = resolve(module, current, package);
            push(ImportKind::From { raw: module.clone(), module: resolved, module_span: *module_span, names, star: *star }, out);
        }
        StmtKind::If { test, body, orelse, .. } => {
            let inner = (type_checking || is_type_checking(test), main_only || is_main_test(test));
            for s in body {
                collect_imports(s, top_index, false, in_function, guard, inner, current, package, out);
            }
            for s in orelse {
                collect_imports(s, top_index, false, in_function, guard, flags, current, package, out);
            }
        }
        StmtKind::Try { body, handlers, orelse, finalbody } => {
            let mut inner = guard;
            inner.add(handlers);
            for s in body {
                collect_imports(s, top_index, false, in_function, inner, flags, current, package, out);
            }
            for h in handlers {
                for s in &h.body {
                    collect_imports(s, top_index, false, in_function, guard, flags, current, package, out);
                }
            }
            for s in orelse.iter().chain(finalbody) {
                collect_imports(s, top_index, false, in_function, guard, flags, current, package, out);
            }
        }
        StmtKind::Def { body, .. } => {
            for s in body {
                collect_imports(s, top_index, false, true, guard, flags, current, package, out);
            }
        }
        StmtKind::Unsupported { what, exprs, body, .. } => {
            // A class body runs when the class is made; a nameless `def` is a function.
            let in_fn = in_function || *what == "def";
            // `with suppress(ImportError):` guards what is in it as `try` does.
            let guard = match suppress_guard(exprs) {
                Some(g) if what.contains("with") => guard.or(g),
                _ => guard,
            };
            for s in body {
                collect_imports(s, top_index, false, in_fn, guard, flags, current, package, out);
            }
        }
        StmtKind::While { body, orelse, .. } | StmtKind::For { body, orelse, .. } => {
            for s in body.iter().chain(orelse) {
                collect_imports(s, top_index, false, in_function, guard, flags, current, package, out);
            }
        }
        _ => {}
    }
}

/// The plugin as modules: every `.py` file that has a module name, by name.
pub(crate) struct Program {
    pub files: Vec<ModFile>,
    pub by_name: HashMap<String, usize>,
}

impl Program {
    /// [entry], when given, is the script the keyboard runs as `__main__`.
    pub fn new(files: &BTreeMap<String, String>, entry: Option<&str>) -> Program {
        let mut out = Vec::new();
        for (path, text) in files {
            if !path.ends_with(".py") {
                continue;
            }
            if Some(path.as_str()) == entry {
                out.push(ModFile::new(path, "__main__".into(), false, text));
            } else if let Some((name, package)) = module_of(path) {
                out.push(ModFile::new(path, name, package, text));
            }
        }
        let by_name = out.iter().enumerate().map(|(i, f)| (f.name.clone(), i)).collect();
        Program { files: out, by_name }
    }

    pub fn get(&self, name: &str) -> Option<&ModFile> {
        self.by_name.get(name).map(|i| &self.files[*i])
    }

    /// A module, or a folder of modules with no `__init__.py`.
    pub fn exists(&self, name: &str) -> bool {
        if self.by_name.contains_key(name) {
            return true;
        }
        let prefix = format!("{name}.");
        self.by_name.keys().any(|k| k.starts_with(&prefix))
    }
}
