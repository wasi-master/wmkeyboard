//! Port of PyAst.kt: expressions, statements and the operator enums the parser
//! produces and the interpreter walks.

use std::cell::Cell;
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
    LShift,
    RShift,
    /// `@`, which only classes with `__matmul__` answer.
    MatMul,
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
    Invert,
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
    /// `!a`: the repr with everything past ASCII escaped.
    Ascii,
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
    /// `(x for x in xs)`: a generator, which runs a step each time it is asked for an item.
    Gen,
}

/// Where a name lives, as the resolver worked out from the code around it
/// (Python's own rules: a name assigned anywhere in a function is that
/// function's, unless declared `global` or `nonlocal`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NameAt {
    /// A variable of the running function: its slot in the frame.
    Local(u32),
    /// A variable of an enclosing function, (scopes out, slot): what a
    /// closure reads, and what a `nonlocal` writes.
    Free(u32, u32),
    /// The module's globals, then the builtins.
    Global,
    /// In a class body: the class's own names, then an enclosing function's
    /// variable when the name is one, then the globals.
    Class(Option<(u32, u32)>),
}

/// A name as an expression, an assignment target or a binding.
#[derive(Debug)]
pub(crate) struct Name {
    pub id: Rc<str>,
    pub at: NameAt,
    /// Where in the module's names a global was found last: a guess, checked
    /// against the name before it is used.
    pub hint: Cell<u32>,
}

impl Name {
    pub fn new(id: Rc<str>) -> Name {
        Name { id, at: NameAt::Global, hint: Cell::new(u32::MAX) }
    }
}

/// The variables of a function, lambda or comprehension, as the resolver
/// laid them out: slot i holds names[i], parameters first.
#[derive(Debug, Default)]
pub(crate) struct Frame {
    pub names: Vec<Rc<str>>,
    /// A `yield` in the body: calling it makes a generator.
    pub generator: bool,
    /// An `async def`: calling it makes a coroutine (an async generator
    /// with `yield` too), which runs when awaited.
    pub coroutine: bool,
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
    pub frame: Frame,
}

/// A parameter list: `(a, b=1, *rest, c, d=2, **options)`.
#[derive(Debug, Default)]
pub(crate) struct Params {
    pub positional: Vec<Param>,
    /// How many of [positional] come before a `/`: no keyword can fill them.
    pub posonly: usize,
    pub variadic: Option<Rc<str>>,
    /// Parameters after `*` or `*rest`, which only a keyword can fill.
    pub keyword_only: Vec<Param>,
    pub kwargs: Option<Rc<str>>,
}

impl Params {
    /// Only plain positional parameters (with or without defaults): a call
    /// can fill their slots in order.
    pub fn simple(&self) -> bool {
        self.variadic.is_none() && self.kwargs.is_none() && self.keyword_only.is_empty()
    }

    /// Every parameter's name, in slot order: positional, `*rest`,
    /// keyword-only, `**options`.
    pub fn names(&self) -> Vec<Rc<str>> {
        let mut out: Vec<Rc<str>> = self.positional.iter().map(|p| p.name.clone()).collect();
        out.extend(self.variadic.clone());
        out.extend(self.keyword_only.iter().map(|p| p.name.clone()));
        out.extend(self.kwargs.clone());
        out
    }
}

/// A `def`: shared between the statement and every function value it makes.
#[derive(Debug)]
pub(crate) struct FuncDef {
    pub name: Rc<str>,
    pub params: Params,
    pub body: Vec<Stmt>,
    /// `@decorator` lines, top first. Applied bottom up.
    pub decorators: Vec<Expr>,
    pub frame: Frame,
    /// Parameter (and `return`) annotations, evaluated in the module the
    /// first time `__annotations__` is read. The resolver does not see them:
    /// one that it would have to is kept as its text.
    pub annotations: Vec<(Rc<str>, Expr)>,
}

/// A `lambda`.
#[derive(Debug)]
pub(crate) struct LambdaDef {
    pub params: Params,
    pub body: Expr,
    pub frame: Frame,
}

/// A `class`.
#[derive(Debug)]
pub(crate) struct ClassDef {
    pub name: Rc<str>,
    pub bases: Vec<Expr>,
    /// `metaclass=M` and the keywords handed to `__init_subclass__`; `**d` as "**".
    pub keywords: Vec<(Rc<str>, Expr)>,
    pub body: Vec<Stmt>,
    pub decorators: Vec<Expr>,
}

/// Expression nodes.
#[derive(Debug)]
pub(crate) enum Expr {
    None,
    Bool(bool),
    Int(i64),
    /// An int literal past 64 bits.
    /// An imaginary literal, `2j`.
    Imag(f64),
    Big(Rc<num_bigint::BigInt>),
    Float(f64),
    Str(Rc<str>),
    Bytes(Rc<[u8]>),
    FString(Vec<FPart>),
    Name(Name),
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
    Comprehension(Rc<Comprehension>),
    /// `*seq` spread inside a call's argument list or a list, tuple or set
    /// display; inside a dict display, `**mapping` (with an `Expr::None` value).
    Starred(Box<Expr>),
    /// `(name := value)`.
    Walrus(Box<(Name, Expr)>),
    /// `yield value`, which only a generator's own statements run.
    Yield(Option<Box<Expr>>),
    /// `yield from iterable`.
    YieldFrom(Box<Expr>),
    /// `await value`: runs a coroutine to its end there and then, as
    /// nothing else is waiting to run.
    Await(Box<Expr>),
    /// A literal this interpreter reads but cannot make (an int past 64
    /// bits, a complex number, an unknown `\N{name}`): evaluating it raises
    /// (kind, message), so the module around it still loads.
    Invalid(&'static str, Rc<str>),
}

/// Calls [f] on each expression directly inside [e], not inside a lambda's
/// or a comprehension's own body.
pub(crate) fn each_child_mut(e: &mut Expr, f: &mut dyn FnMut(&mut Expr)) {
    fn fparts(parts: &mut [FPart], f: &mut dyn FnMut(&mut Expr)) {
        for p in parts {
            if let FPart::Expression { expr, spec, .. } = p {
                f(expr);
                if let Some(s) = spec {
                    fparts(s, f);
                }
            }
        }
    }
    match e {
        Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Big(_) | Expr::Imag(_) | Expr::Float(_) | Expr::Str(_) | Expr::Bytes(_) | Expr::Name(_) => {}
        Expr::Invalid(..) | Expr::Lambda(_) | Expr::Comprehension(_) | Expr::Yield(None) => {}
        Expr::FString(parts) => fparts(parts, f),
        Expr::List(items) | Expr::Tuple(items) | Expr::Set(items) => items.iter_mut().for_each(f),
        Expr::Dict(pairs) => {
            for (k, v) in pairs {
                f(k);
                f(v);
            }
        }
        Expr::Unary(_, x) | Expr::Starred(x) | Expr::YieldFrom(x) | Expr::Yield(Some(x)) | Expr::Attribute(x, _) | Expr::Await(x) => f(x),
        Expr::Binary(_, a, b) | Expr::BoolOp(_, a, b) | Expr::Index(a, b) => {
            f(a);
            f(b);
        }
        Expr::Compare(left, pairs) => {
            f(left);
            for (_, x) in pairs {
                f(x);
            }
        }
        Expr::Ternary(t) => {
            f(&mut t.0);
            f(&mut t.1);
            f(&mut t.2);
        }
        Expr::Call(callee, args, kwargs) => {
            f(callee);
            args.iter_mut().for_each(&mut *f);
            for (_, v) in kwargs {
                f(v);
            }
        }
        Expr::Slice(s) => {
            f(&mut s.0);
            for x in [&mut s.1, &mut s.2, &mut s.3].into_iter().flatten() {
                f(x);
            }
        }
        Expr::Walrus(w) => f(&mut w.1),
    }
}

/// One `except …:` clause. `types` empty means a bare `except:`.
#[derive(Debug)]
pub(crate) struct ExceptHandler {
    pub types: Vec<Rc<str>>,
    /// Types given by any other expression (`except errors():`, `except
    /// (A,) + more:`), evaluated when an exception reaches the clause; and,
    /// marked true, the names above that are not builtin exceptions, whose
    /// value is tried when the name does not match (`except re.error:`,
    /// `except RETRYABLE:` holding a tuple), an error finding it ignored.
    pub exprs: Vec<(Expr, bool)>,
    pub binding: Option<Name>,
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
    /// The definition, and the name it is bound to.
    FuncDef(Rc<FuncDef>, Name),
    ClassDef(Rc<ClassDef>, Name),
    /// `with a as x, b:` → [(a, Some(x)), (b, None)].
    With(Vec<(Expr, Option<Expr>)>, Vec<Stmt>),
    /// `x: int = 1`: the target, the annotation's plain name when it is one, and the value.
    /// `target: annotation = value`.
    AnnAssign(Expr, Expr, Option<Expr>),
    Assert(Expr, Option<Expr>),
    Nonlocal(Vec<Rc<str>>),
    Return(Option<Expr>),
    Break,
    Continue,
    Pass,
    Try(Box<TryStmt>),
    /// `raise value from cause`.
    Raise(Option<Expr>, Option<Expr>),
    /// `import a.b [as m]` binds [target] (to `a`, or with an alias to
    /// `a.b`); `from a import b [as c], d` binds each of [names].
    Import { module: Rc<str>, target: Option<Name>, aliased: bool, names: Option<Vec<(Rc<str>, Name)>> },
    /// `from module import *`; the name says where its bindings go.
    ImportStar(Rc<str>, Name),
    /// `match subject:` and its cases.
    Match(Box<MatchStmt>),
    Global(Vec<Rc<str>>),
    Del(Vec<Expr>),
}

/// One `case pattern [if guard]:` of a `match`.
#[derive(Debug)]
pub(crate) struct MatchCase {
    pub pattern: Pattern,
    pub guard: Option<Expr>,
    pub body: Vec<Stmt>,
}

#[derive(Debug)]
pub(crate) struct MatchStmt {
    pub subject: Expr,
    pub cases: Vec<MatchCase>,
}

/// A `case` pattern.
#[derive(Debug)]
pub(crate) enum Pattern {
    /// `_`.
    Wildcard,
    /// A bare name, which takes the value.
    Capture(Name),
    /// A literal or a dotted name, compared with `==` (`None`, `True` and
    /// `False` with `is`).
    Value(Expr),
    /// `[a, b, *rest]` or `(a, b)`: the index of the starred one, with its name (None for `*_`).
    Sequence(Vec<Pattern>, Option<(usize, Option<Name>)>),
    /// `{"key": p, **rest}`.
    Mapping(Vec<(Expr, Pattern)>, Option<Name>),
    /// `Point(x, y=0)`.
    Class(Expr, Vec<Pattern>, Vec<(Rc<str>, Pattern)>),
    /// `a | b`.
    Or(Vec<Pattern>),
    /// `pattern as name`.
    As(Box<Pattern>, Name),
}

/// A statement with the 1-based line it started on.
#[derive(Debug)]
pub(crate) struct Stmt {
    pub line: u32,
    /// A `yield` somewhere in it (outside any function or lambda it defines):
    /// a generator runs it a step at a time.
    pub yields: bool,
    pub kind: StmtKind,
}
