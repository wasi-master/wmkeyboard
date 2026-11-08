//! The tree the tolerant parser builds. Every node keeps its span, and every
//! name keeps the span of its own token, so analysis never has to guess where
//! a name is.

use crate::text::Span;

#[derive(Clone, Debug)]
pub struct Expr {
    pub kind: ExprKind,
    pub span: Span,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CompKind {
    List,
    Set,
    Dict,
    Generator,
}

#[derive(Clone, Debug)]
pub enum ExprKind {
    Name(String),
    Number,
    /// One string literal, or several written side by side. `value` is the
    /// text when every part is a plain string whose escapes could be read.
    Str { value: Option<String>, fstring: bool, fields: Vec<Expr> },
    /// `True`, `False` or `None`.
    Const(&'static str),
    Attribute { value: Box<Expr>, attr: String, attr_span: Span },
    Subscript { value: Box<Expr>, index: Box<Expr> },
    Slice { lower: Option<Box<Expr>>, upper: Option<Box<Expr>>, step: Option<Box<Expr>> },
    /// `open` is the offset of `(`, `close` of `)` when there is one.
    Call { func: Box<Expr>, args: Vec<Expr>, keywords: Vec<Keyword>, open: u32, close: Option<u32> },
    Unary { op: &'static str, operand: Box<Expr> },
    Binary { op: &'static str, left: Box<Expr>, right: Box<Expr> },
    BoolOp { op: &'static str, left: Box<Expr>, right: Box<Expr> },
    Compare { left: Box<Expr>, ops: Vec<(&'static str, Expr)> },
    Ternary { body: Box<Expr>, test: Box<Expr>, orelse: Box<Expr> },
    Lambda { params: Box<Params>, body: Box<Expr> },
    List(Vec<Expr>),
    Tuple(Vec<Expr>),
    Set(Vec<Expr>),
    Dict(Vec<(Expr, Expr)>),
    Comp { kind: CompKind, elt: Box<Expr>, value: Option<Box<Expr>>, clauses: Vec<CompClause> },
    Starred(Box<Expr>),
    DoubleStarred(Box<Expr>),
    /// `name := value`, which PyMini does not have.
    Walrus { target: Box<Expr>, value: Box<Expr> },
    /// `await x` or `yield x`, which PyMini does not have.
    Prefixed { value: Option<Box<Expr>> },
    /// What the parser could not read.
    Error,
}

#[derive(Clone, Debug)]
pub struct Keyword {
    pub name: String,
    pub name_span: Span,
    pub value: Expr,
}

#[derive(Clone, Debug)]
pub struct Param {
    pub name: String,
    pub span: Span,
    pub default: Option<Expr>,
}

#[derive(Clone, Debug, Default)]
pub struct Params {
    pub params: Vec<Param>,
    /// `*args`.
    pub vararg: Option<Param>,
    /// `**kwargs`, which PyMini does not have.
    pub kwarg: Option<Param>,
}

impl Params {
    /// Parameters without a default value.
    pub fn required(&self) -> usize {
        self.params.iter().filter(|p| p.default.is_none()).count()
    }
}

#[derive(Clone, Debug)]
pub struct CompClause {
    pub target: Expr,
    pub iter: Expr,
    pub ifs: Vec<Expr>,
}

#[derive(Clone, Debug)]
pub struct Stmt {
    pub kind: StmtKind,
    pub span: Span,
}

#[derive(Clone, Debug)]
pub struct Handler {
    pub types: Vec<Expr>,
    pub name: Option<(String, Span)>,
    pub body: Vec<Stmt>,
    pub span: Span,
}

#[derive(Clone, Debug)]
pub struct ImportName {
    pub name: String,
    pub span: Span,
    pub asname: Option<(String, Span)>,
}

impl ImportName {
    /// The name the import binds, and where.
    pub fn bound(&self) -> (&str, Span) {
        match &self.asname {
            Some((name, span)) => (name, *span),
            None => (&self.name, self.span),
        }
    }
}

#[derive(Clone, Debug)]
pub enum StmtKind {
    Expr(Expr),
    Assign { targets: Vec<Expr>, value: Expr },
    AugAssign { target: Expr, op: &'static str, value: Expr },
    /// `x: int = 1`, which PyMini does not have.
    AnnAssign { target: Expr, annotation: Expr, value: Option<Expr> },
    /// An `elif` is an `If` alone in the `orelse` of the one before.
    If { test: Expr, body: Vec<Stmt>, orelse: Vec<Stmt>, elif: bool },
    While { test: Expr, body: Vec<Stmt>, orelse: Vec<Stmt> },
    For { target: Expr, iter: Expr, body: Vec<Stmt>, orelse: Vec<Stmt> },
    Try { body: Vec<Stmt>, handlers: Vec<Handler>, orelse: Vec<Stmt>, finalbody: Vec<Stmt> },
    Def { name: String, name_span: Span, params: Params, body: Vec<Stmt>, header: Span },
    Return(Option<Expr>),
    Break,
    Continue,
    Pass,
    Raise(Option<Expr>),
    /// `import a [as b]`, one or several.
    Import(Vec<ImportName>),
    ImportFrom { module: String, module_span: Span, names: Vec<ImportName>, star: bool },
    Global(Vec<(String, Span)>),
    Del(Vec<Expr>),
    /// A statement PyMini does not run (`class`, `with`, `match`, `assert`,
    /// `nonlocal`, a decorator), kept so the names in it still mean something.
    /// `binds` are names it assigns: a class name, a `with ... as` target.
    Unsupported { what: &'static str, exprs: Vec<Expr>, binds: Vec<Expr>, body: Vec<Stmt> },
    /// A line the parser could not read. Its names are kept for analysis:
    /// those before a top-level `=` (or between `for` and `in`) as names it
    /// assigns, the rest as names it reads. Names after a `.` are left out.
    Error { reads: Vec<(String, Span)>, binds: Vec<(String, Span)> },
}

#[derive(Clone, Debug)]
pub struct SyntaxError {
    pub span: Span,
    /// The text met, or empty at the end of a line.
    pub found: String,
    /// What would have been accepted there, in words: `an expression`, `':'`.
    pub expected: Option<String>,
    pub at_end: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Module {
    pub body: Vec<Stmt>,
    pub errors: Vec<SyntaxError>,
    /// Constructs PyMini does not run: where, and what to call them.
    pub unsupported: Vec<(Span, &'static str)>,
}

/// Calls [f] on every expression directly inside [expr], not on [expr] itself.
pub fn each_child<'a>(expr: &'a Expr, f: &mut dyn FnMut(&'a Expr)) {
    match &expr.kind {
        ExprKind::Str { fields, .. } => fields.iter().for_each(f),
        ExprKind::Attribute { value, .. } => f(value),
        ExprKind::Subscript { value, index } => {
            f(value);
            f(index);
        }
        ExprKind::Slice { lower, upper, step } => {
            for e in [lower, upper, step].into_iter().flatten() {
                f(e);
            }
        }
        ExprKind::Call { func, args, keywords, .. } => {
            f(func);
            args.iter().for_each(&mut *f);
            keywords.iter().for_each(|k| f(&k.value));
        }
        ExprKind::Unary { operand, .. } => f(operand),
        ExprKind::Binary { left, right, .. } | ExprKind::BoolOp { left, right, .. } => {
            f(left);
            f(right);
        }
        ExprKind::Compare { left, ops } => {
            f(left);
            ops.iter().for_each(|(_, e)| f(e));
        }
        ExprKind::Ternary { body, test, orelse } => {
            f(body);
            f(test);
            f(orelse);
        }
        ExprKind::Lambda { params, body } => {
            for p in &params.params {
                if let Some(d) = &p.default {
                    f(d);
                }
            }
            f(body);
        }
        ExprKind::List(items) | ExprKind::Tuple(items) | ExprKind::Set(items) => items.iter().for_each(f),
        ExprKind::Dict(pairs) => pairs.iter().for_each(|(k, v)| {
            f(k);
            f(v);
        }),
        ExprKind::Comp { elt, value, clauses, .. } => {
            f(elt);
            if let Some(v) = value {
                f(v);
            }
            for c in clauses {
                f(&c.target);
                f(&c.iter);
                c.ifs.iter().for_each(&mut *f);
            }
        }
        ExprKind::Starred(e) | ExprKind::DoubleStarred(e) => f(e),
        ExprKind::Walrus { target, value } => {
            f(target);
            f(value);
        }
        ExprKind::Prefixed { value } => {
            if let Some(v) = value {
                f(v);
            }
        }
        ExprKind::Name(_) | ExprKind::Number | ExprKind::Const(_) | ExprKind::Error => {}
    }
}

/// Calls [f] on [expr] and every expression inside it, outermost first.
pub fn walk_expr<'a>(expr: &'a Expr, f: &mut dyn FnMut(&'a Expr)) {
    f(expr);
    each_child(expr, &mut |child| walk_expr(child, f));
}

/// The expressions a statement holds directly (not those of nested statements).
pub fn stmt_exprs(stmt: &Stmt) -> Vec<&Expr> {
    match &stmt.kind {
        StmtKind::Expr(e) => vec![e],
        StmtKind::Assign { targets, value } => targets.iter().chain(std::iter::once(value)).collect(),
        StmtKind::AugAssign { target, value, .. } => vec![target, value],
        StmtKind::AnnAssign { target, annotation, value } => {
            let mut v = vec![target, annotation];
            v.extend(value.iter());
            v
        }
        StmtKind::If { test, .. } | StmtKind::While { test, .. } => vec![test],
        StmtKind::For { target, iter, .. } => vec![target, iter],
        StmtKind::Try { handlers, .. } => handlers.iter().flat_map(|h| h.types.iter()).collect(),
        StmtKind::Def { params, .. } => params.params.iter().filter_map(|p| p.default.as_ref()).collect(),
        StmtKind::Return(e) | StmtKind::Raise(e) => e.iter().collect(),
        StmtKind::Del(targets) => targets.iter().collect(),
        StmtKind::Unsupported { exprs, binds, .. } => exprs.iter().chain(binds.iter()).collect(),
        _ => Vec::new(),
    }
}

/// The blocks a statement holds directly.
pub fn stmt_blocks(stmt: &Stmt) -> Vec<&Vec<Stmt>> {
    match &stmt.kind {
        StmtKind::If { body, orelse, .. } | StmtKind::While { body, orelse, .. } | StmtKind::For { body, orelse, .. } => {
            vec![body, orelse]
        }
        StmtKind::Try { body, handlers, orelse, finalbody } => {
            let mut v = vec![body];
            v.extend(handlers.iter().map(|h| &h.body));
            v.push(orelse);
            v.push(finalbody);
            v
        }
        StmtKind::Def { body, .. } | StmtKind::Unsupported { body, .. } => vec![body],
        _ => Vec::new(),
    }
}

/// Calls [f] on every statement, nested ones included, in document order.
pub fn walk_stmts<'a>(stmts: &'a [Stmt], f: &mut dyn FnMut(&'a Stmt)) {
    for stmt in stmts {
        f(stmt);
        for block in stmt_blocks(stmt) {
            walk_stmts(block, f);
        }
    }
}
