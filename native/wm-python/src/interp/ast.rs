//! Port of PyAst.kt: expressions, statements and the operator enums the parser
//! produces and the interpreter walks.

use std::rc::Rc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BinOp {
    Add,
    Sub,
    Mul,
    Div,
    FloorDiv,
    Mod,
    Pow,
    BitAnd,
    BitOr,
    BitXor,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CmpOp {
    Eq,
    Ne,
    Lt,
    Le,
    Gt,
    Ge,
    In,
    NotIn,
    Is,
    IsNot,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum UnOp {
    Neg,
    Pos,
    Not,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BoolKind {
    And,
    Or,
}

/// How a value is converted before its format spec applies: f-string `!r` or `!s`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FConversion {
    Str,
    Repr,
}

/// One segment of an f-string: a literal run, or an embedded expression.
#[derive(Debug)]
pub(crate) enum FPart {
    Literal(String),
    Expression { expr: Expr, conversion: Option<FConversion>, spec: Option<Vec<FPart>> },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CompKind {
    List,
    Set,
    Dict,
}

/// One `for … in … if …` clause of a comprehension.
#[derive(Debug)]
pub(crate) struct CompClause {
    pub target: Expr,
    pub iterable: Expr,
    pub conditions: Vec<Expr>,
}

/// A parameter; its default is evaluated in the closure at call time.
#[derive(Debug)]
pub(crate) struct Param {
    pub name: Rc<str>,
    pub default: Option<Expr>,
}

#[derive(Debug)]
pub(crate) struct Comprehension {
    pub kind: CompKind,
    pub element: Expr,
    pub value: Option<Expr>,
    pub clauses: Vec<CompClause>,
}

/// A parameter list: `(a, b=1, *rest, c, d=2, **options)`.
#[derive(Debug, Default)]
pub(crate) struct Params {
    pub positional: Vec<Param>,
    pub variadic: Option<Rc<str>>,
    /// Parameters after `*` or `*rest`, which only a keyword can fill.
    pub keyword_only: Vec<Param>,
    pub kwargs: Option<Rc<str>>,
}

/// A `def`: shared between the statement and every function value it makes.
#[derive(Debug)]
pub(crate) struct FuncDef {
    pub name: Rc<str>,
    pub params: Params,
    pub body: Vec<Stmt>,
    /// `@decorator` lines, top first. Applied bottom up.
    pub decorators: Vec<Expr>,
}

/// A `lambda`.
#[derive(Debug)]
pub(crate) struct LambdaDef {
    pub params: Params,
    pub body: Expr,
}

/// A `class`.
#[derive(Debug)]
pub(crate) struct ClassDef {
    pub name: Rc<str>,
    pub bases: Vec<Expr>,
    pub body: Vec<Stmt>,
    pub decorators: Vec<Expr>,
}

/// Expression nodes.
#[derive(Debug)]
pub(crate) enum Expr {
    None,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(Rc<str>),
    Bytes(Rc<[u8]>),
    FString(Vec<FPart>),
    Name(Rc<str>),
    List(Vec<Expr>),
    Dict(Vec<(Expr, Expr)>),
    Set(Vec<Expr>),
    Unary(UnOp, Box<Expr>),
    Binary(BinOp, Box<Expr>, Box<Expr>),
    BoolOp(BoolKind, Box<Expr>, Box<Expr>),
    /// Chained comparison: `left op a op b …`.
    Compare(Box<Expr>, Vec<(CmpOp, Expr)>),
    Ternary(Box<(Expr, Expr, Expr)>),
    /// A keyword named `**` is a `**mapping` spread.
    Call(Box<Expr>, Vec<Expr>, Vec<(Rc<str>, Expr)>),
    Index(Box<Expr>, Box<Expr>),
    Slice(Box<(Expr, Option<Expr>, Option<Expr>, Option<Expr>)>),
    Attribute(Box<Expr>, Rc<str>),
    Tuple(Vec<Expr>),
    Lambda(Rc<LambdaDef>),
    Comprehension(Box<Comprehension>),
    /// `*seq` spread inside a call's argument list or a list, tuple or set
    /// display; inside a dict display, `**mapping` (with an `Expr::None` value).
    Starred(Box<Expr>),
}

/// One `except …:` clause. `types` empty means a bare `except:`.
#[derive(Debug)]
pub(crate) struct ExceptHandler {
    pub types: Vec<Rc<str>>,
    pub binding: Option<Rc<str>>,
    pub body: Vec<Stmt>,
}

#[derive(Debug)]
pub(crate) struct TryStmt {
    pub body: Vec<Stmt>,
    pub handlers: Vec<ExceptHandler>,
    pub else_body: Option<Vec<Stmt>>,
    pub finally_body: Option<Vec<Stmt>>,
}

/// Statement nodes.
#[derive(Debug)]
pub(crate) enum StmtKind {
    Expr(Expr),
    /// `a = b = value` → targets = [a, b].
    Assign(Vec<Expr>, Expr),
    AugAssign(Expr, BinOp, Expr),
    If(Vec<(Expr, Vec<Stmt>)>, Option<Vec<Stmt>>),
    While(Expr, Vec<Stmt>, Option<Vec<Stmt>>),
    For(Expr, Expr, Vec<Stmt>, Option<Vec<Stmt>>),
    FuncDef(Rc<FuncDef>),
    ClassDef(Rc<ClassDef>),
    /// `with a as x, b:` → [(a, Some(x)), (b, None)].
    With(Vec<(Expr, Option<Expr>)>, Vec<Stmt>),
    /// `x: int = 1`: the target, the annotation's plain name when it is one, and the value.
    AnnAssign(Expr, Option<Rc<str>>, Option<Expr>),
    Assert(Expr, Option<Expr>),
    Nonlocal(Vec<Rc<str>>),
    Return(Option<Expr>),
    Break,
    Continue,
    Pass,
    Try(Box<TryStmt>),
    Raise(Option<Expr>),
    /// `import math [as m]` / `from math import sqrt [as root], pi`.
    Import { module: Rc<str>, alias: Option<Rc<str>>, names: Option<Vec<(Rc<str>, Option<Rc<str>>)>> },
    Global(Vec<Rc<str>>),
    Del(Vec<Expr>),
}

/// A statement with the 1-based line it started on.
#[derive(Debug)]
pub(crate) struct Stmt {
    pub line: u32,
    pub kind: StmtKind,
}
