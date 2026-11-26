//! Port of PyParser.kt: a recursive-descent parser from tokens to statements.
//!
//! Additions to PyMini: `class`, decorators, `with`, `nonlocal`, `assert`,
//! keyword-only parameters and `**kwargs`, `*` and `**` spreads in calls and
//! displays, type annotations (parsed and ignored), dotted and relative
//! imports, dotted exception names, `raise … from …`, starred targets
//! (`first, *rest = items`), generators (`yield`, `yield from`, generator
//! expressions), `:=`, `match` and classes with several bases. Expression nesting
//! is capped at [MAX_NESTING], because a native stack overflow cannot be
//! caught the way the JVM's can.

use std::rc::Rc;

use super::ast::*;
use super::error::PyError;
use super::lexer::{tokenize, FRawPart, Tok, Token};

/// Deepest expression nesting the parser follows. CPython stops at 200 brackets.
pub(crate) const MAX_NESTING: u32 = 100;

/// Parse a complete module, with every name resolved to where it lives.
pub(crate) fn parse(source: &str) -> Result<Vec<Stmt>, PyError> {
    let tokens = tokenize(source)?;
    let mut program = Parser { tokens, i: 0, depth: 0, temps: 0 }.parse_program()?;
    super::resolve::resolve_module(&mut program)?;
    Ok(program)
}

/// Parse code for `exec` or `eval`: resolved as a module's own code, or
/// with [namespace] as code running over a namespace of its own.
pub(crate) fn parse_dynamic(source: &str, namespace: bool) -> Result<Vec<Stmt>, PyError> {
    let tokens = tokenize(source)?;
    let mut program = Parser { tokens, i: 0, depth: 0, temps: 0 }.parse_program()?;
    if namespace {
        super::resolve::resolve_dynamic(&mut program)?;
    } else {
        super::resolve::resolve_module(&mut program)?;
    }
    Ok(program)
}

/// Parse a standalone expression (f-string placeholders).
fn parse_expression(source: &str, depth: u32) -> Result<Expr, PyError> {
    let tokens = tokenize(source)?;
    let mut p = Parser { tokens, i: 0, depth, temps: 0 };
    p.skip_newlines();
    p.parse_expr_or_tuple()
}

/// Whether [source] is one literal as `ast.literal_eval` takes it:
/// constants, tuples, lists, sets and dicts of them, a sign before a
/// number, and `set()`. A syntax error is the outer error; the inner one
/// names the first node that is not a literal (`ast.Name`).
pub(crate) fn check_literal(source: &str) -> Result<Result<(), &'static str>, PyError> {
    fn literal(e: &Expr) -> Result<(), &'static str> {
        match e {
            Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Big(_) | Expr::Imag(_) | Expr::Float(_) | Expr::Str(_) | Expr::Bytes(_) | Expr::Invalid(..) => Ok(()),
            Expr::Tuple(items) | Expr::List(items) | Expr::Set(items) => items.iter().try_for_each(literal),
            Expr::Dict(pairs) => pairs.iter().try_for_each(|(k, v)| literal(k).and_then(|_| literal(v))),
            Expr::Unary(UnOp::Neg | UnOp::Pos, x) if matches!(**x, Expr::Int(_) | Expr::Big(_) | Expr::Imag(_) | Expr::Float(_) | Expr::Bool(_) | Expr::Invalid(..)) => Ok(()),
            Expr::Call(f, args, kwargs) if args.is_empty() && kwargs.is_empty() && matches!(&**f, Expr::Name(n) if &*n.id == "set") => Ok(()),
            Expr::Name(_) => Err("ast.Name"),
            Expr::Call(..) => Err("ast.Call"),
            // As CPython: `a + 1` names the operand that is not a number.
            Expr::Binary(BinOp::Add | BinOp::Sub, l, r) => {
                let number = |e: &Expr| match e {
                    Expr::Int(_) | Expr::Big(_) | Expr::Imag(_) | Expr::Float(_) | Expr::Bool(_) | Expr::Invalid(..) => true,
                    Expr::Unary(UnOp::Neg | UnOp::Pos, x) => matches!(**x, Expr::Int(_) | Expr::Big(_) | Expr::Imag(_) | Expr::Float(_) | Expr::Bool(_) | Expr::Invalid(..)),
                    _ => false,
                };
                if !number(l) {
                    literal(l).and(Err("ast.BinOp"))
                } else if !number(r) {
                    literal(r).and(Err("ast.BinOp"))
                } else {
                    Err("ast.BinOp")
                }
            }
            Expr::Binary(..) => Err("ast.BinOp"),
            Expr::Unary(..) => Err("ast.UnaryOp"),
            Expr::BoolOp(..) => Err("ast.BoolOp"),
            Expr::Compare(..) => Err("ast.Compare"),
            Expr::Attribute(..) => Err("ast.Attribute"),
            Expr::Index(..) => Err("ast.Subscript"),
            Expr::FString(_) => Err("ast.JoinedStr"),
            Expr::Lambda(_) => Err("ast.Lambda"),
            Expr::Ternary(_) => Err("ast.IfExp"),
            Expr::Starred(_) => Err("ast.Starred"),
            Expr::Comprehension(c) => Err(match c.kind {
                CompKind::List => "ast.ListComp",
                CompKind::Set => "ast.SetComp",
                CompKind::Dict => "ast.DictComp",
                CompKind::Gen => "ast.GeneratorExp",
            }),
            _ => Err("ast.expr"),
        }
    }
    let tokens = tokenize(source)?;
    let mut p = Parser { tokens, i: 0, depth: 0, temps: 0 };
    p.skip_newlines();
    let e = p.parse_expr_or_tuple()?;
    p.skip_newlines();
    if !matches!(p.kind(), Tok::Eof) {
        return Err(PyError::perr("invalid syntax", p.line()));
    }
    Ok(literal(&e))
}

fn aug_op(s: &str) -> Option<BinOp> {
    Some(match s {
        "+=" => BinOp::Add,
        "-=" => BinOp::Sub,
        "*=" => BinOp::Mul,
        "/=" => BinOp::Div,
        "%=" => BinOp::Mod,
        "//=" => BinOp::FloorDiv,
        "**=" => BinOp::Pow,
        "&=" => BinOp::BitAnd,
        "|=" => BinOp::BitOr,
        "^=" => BinOp::BitXor,
        "<<=" => BinOp::LShift,
        ">>=" => BinOp::RShift,
        "@=" => BinOp::MatMul,
        _ => return None,
    })
}

fn string_parts(parts: &[FRawPart], depth: u32) -> Result<Vec<FPart>, PyError> {
    parts
        .iter()
        .map(|part| match part {
            FRawPart::Lit(t) => Ok(FPart::Literal(t.clone())),
            FRawPart::Expr { source, conv, spec } => {
                let expr = parse_expression(source, depth + 1)?;
                let conversion = match conv.as_deref() {
                    Some("r") => Some(FConversion::Repr),
                    Some("a") => Some(FConversion::Ascii),
                    Some("s") => Some(FConversion::Str),
                    _ => None,
                };
                let spec = match spec {
                    Some(s) => Some(string_parts(s, depth)?),
                    None => None,
                };
                Ok(FPart::Expression { expr, conversion, spec })
            }
        })
        .collect()
}

struct Parser {
    tokens: Vec<Token>,
    i: usize,
    depth: u32,
    /// How many `yield`s were moved out of an expression into a temporary, for its name.
    temps: u32,
}

/// Moves each `yield` inside [e] (not [e] itself) into a statement of its
/// own before it, `$yieldN = yield …`, leaving the temporary in its place:
/// a generator pauses only at a statement. `return (yield)` becomes
/// `$yield0 = yield` then `return $yield0`.
fn hoist_yields(e: &mut Expr, out: &mut Vec<Stmt>, line: u32, temps: &mut u32) {
    each_child_mut(e, &mut |child| hoist_yield(child, out, line, temps));
}

fn hoist_yield(e: &mut Expr, out: &mut Vec<Stmt>, line: u32, temps: &mut u32) {
    if matches!(e, Expr::Yield(_) | Expr::YieldFrom(_)) {
        hoist_yields(e, out, line, temps);
        let name: Rc<str> = Rc::from(format!("$yield{temps}"));
        *temps += 1;
        let y = std::mem::replace(e, Expr::Name(Name::new(name.clone())));
        out.push(Stmt { line, yields: false, kind: StmtKind::Assign(vec![Expr::Name(Name::new(name))], y) });
        return;
    }
    hoist_yields(e, out, line, temps);
}

/// The plain exception names an `except` expression is made of (`A`,
/// `mod.A`, `(A, mod.B)`), each by its last part; None for anything else.
fn exception_names(e: &Expr) -> Option<Vec<Rc<str>>> {
    fn dotted(e: &Expr) -> Option<Rc<str>> {
        match e {
            Expr::Name(n) => Some(n.id.clone()),
            Expr::Attribute(base, n) => dotted(base).map(|_| n.clone()),
            _ => None,
        }
    }
    match e {
        Expr::Tuple(items) => items.iter().map(dotted).collect(),
        other => dotted(other).map(|n| vec![n]),
    }
}

type P<T> = Result<T, PyError>;

impl Parser {
    fn kind(&self) -> &Tok {
        &self.tokens[self.i].kind
    }

    fn line(&self) -> u32 {
        self.tokens[self.i].line
    }

    fn peek_kind(&self, n: usize) -> &Tok {
        self.tokens.get(self.i + n).map(|t| &t.kind).unwrap_or(&Tok::Eof)
    }

    fn advance(&mut self) -> Token {
        let t = self.tokens[self.i].clone();
        if self.i < self.tokens.len() - 1 {
            self.i += 1;
        }
        t
    }

    fn is_op(&self, s: &str) -> bool {
        matches!(self.kind(), Tok::Op(o) if *o == s)
    }

    fn is_keyword(&self, s: &str) -> bool {
        matches!(self.kind(), Tok::Keyword(k) if *k == s)
    }

    fn is_eof(&self) -> bool {
        *self.kind() == Tok::Eof
    }

    fn match_op(&mut self, s: &str) -> bool {
        if self.is_op(s) {
            self.advance();
            return true;
        }
        false
    }

    fn match_keyword(&mut self, s: &str) -> bool {
        if self.is_keyword(s) {
            self.advance();
            return true;
        }
        false
    }

    fn expect_op(&mut self, s: &str) -> P<()> {
        if !self.match_op(s) {
            return Err(PyError::perr(format!("expected '{s}'"), self.line()));
        }
        Ok(())
    }

    fn expect_name(&mut self, what: &str) -> P<Rc<str>> {
        match self.kind() {
            Tok::Name(n) => {
                let n = n.clone();
                self.advance();
                Ok(n)
            }
            _ => Err(PyError::perr(format!("expected {what}"), self.line())),
        }
    }

    fn skip_newlines(&mut self) {
        while *self.kind() == Tok::Newline {
            self.advance();
        }
    }

    fn at_statement_end(&self) -> bool {
        matches!(self.kind(), Tok::Newline | Tok::Eof | Tok::Dedent)
    }

    fn expect_statement_end(&mut self) -> P<()> {
        // A `;` is left for [parse_statement_line], which reads what follows it.
        if self.is_op(";") {
            return Ok(());
        }
        if self.at_statement_end() {
            if *self.kind() == Tok::Newline {
                self.advance();
            }
            return Ok(());
        }
        Err(PyError::perr("unexpected token after statement", self.line()))
    }

    fn enter(&mut self) -> P<()> {
        self.depth += 1;
        if self.depth > MAX_NESTING {
            return Err(PyError::perr("expression nested too deeply", self.line()));
        }
        Ok(())
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    // MARK: - Program and blocks

    fn parse_program(&mut self) -> P<Vec<Stmt>> {
        let mut stmts = Vec::new();
        self.skip_newlines();
        while !self.is_eof() {
            stmts.extend(self.parse_statement_line()?);
            self.skip_newlines();
        }
        Ok(stmts)
    }

    /// One source line, which may hold several `;`-separated simple statements.
    fn parse_statement_line(&mut self) -> P<Vec<Stmt>> {
        let mut out = vec![self.parse_statement()?];
        while self.is_op(";") {
            self.advance();
            if self.at_statement_end() {
                if *self.kind() == Tok::Newline {
                    self.advance();
                }
                break;
            }
            out.push(self.parse_statement()?);
        }
        Ok(out)
    }

    fn parse_block(&mut self) -> P<Vec<Stmt>> {
        self.expect_op(":")?;
        if *self.kind() == Tok::Newline {
            self.advance();
            if *self.kind() != Tok::Indent {
                return Err(PyError::perr("expected an indented block", self.line()));
            }
            self.advance();
            self.enter()?;
            let mut stmts = Vec::new();
            self.skip_newlines();
            while *self.kind() != Tok::Dedent && !self.is_eof() {
                stmts.extend(self.parse_statement_line()?);
                self.skip_newlines();
            }
            self.leave();
            if *self.kind() == Tok::Dedent {
                self.advance();
            }
            if stmts.is_empty() {
                return Err(PyError::perr("expected an indented block", self.line()));
            }
            return Ok(stmts);
        }
        // Inline block, `if x: return 1`.
        self.enter()?;
        let r = self.parse_statement_line();
        self.leave();
        r
    }

    /// An `else:` block, when the next line opens one.
    fn parse_optional_else(&mut self) -> P<Option<Vec<Stmt>>> {
        self.skip_newlines();
        if !self.is_keyword("else") {
            return Ok(None);
        }
        self.advance();
        Ok(Some(self.parse_block()?))
    }

    fn simple(&mut self, kind: StmtKind) -> P<Stmt> {
        let line = self.line();
        self.advance();
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind })
    }

    fn parse_statement(&mut self) -> P<Stmt> {
        if self.is_op("@") {
            return self.parse_decorated();
        }
        if let Tok::Keyword(k) = self.kind() {
            match *k {
                "if" => return self.parse_if(),
                "while" => return self.parse_while(),
                "for" => return self.parse_for(),
                "def" => return self.parse_def(Vec::new(), false),
                "async" => return self.parse_async(Vec::new()),
                "class" => return self.parse_class(Vec::new()),
                "with" => return self.parse_with(),
                "assert" => return self.parse_assert(),
                "nonlocal" => return self.parse_nonlocal(),
                "return" => return self.parse_return(),
                "try" => return self.parse_try(),
                "raise" => return self.parse_raise(),
                "import" => return self.parse_import(),
                "from" => return self.parse_from_import(),
                "global" => return self.parse_global(),
                "del" => return self.parse_del(),
                "break" => return self.simple(StmtKind::Break),
                "continue" => return self.simple(StmtKind::Continue),
                "pass" => return self.simple(StmtKind::Pass),
                _ => {}
            }
        }
        if let Tok::Name(n) = self.kind().clone() {
            if n.as_ref() == "match" {
                if let Some(stmt) = self.try_parse_match()? {
                    return Ok(stmt);
                }
            }
            if n.as_ref() == "type" && matches!(self.peek_kind(1), Tok::Name(_)) && matches!(self.peek_kind(2), Tok::Op("=") | Tok::Op("[")) {
                return self.parse_type_alias();
            }
        }
        self.parse_simple_statement()
    }

    fn parse_if(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut branches = Vec::new();
        let mut cond = self.parse_expr()?;
        // `if (yield from check()):` pauses before the test, once.
        let mut before = Vec::new();
        hoist_yield(&mut cond, &mut before, line, &mut self.temps);
        let body = self.parse_block()?;
        branches.push((cond, body));
        self.skip_newlines();
        while self.is_keyword("elif") {
            self.advance();
            let c = self.parse_expr()?;
            let b = self.parse_block()?;
            branches.push((c, b));
            self.skip_newlines();
        }
        let else_body = self.parse_optional_else()?;
        Ok(with_before(before, Stmt { line, yields: false, kind: StmtKind::If(branches, else_body) }))
    }

    fn parse_while(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let cond = self.parse_expr()?;
        let body = self.parse_block()?;
        let else_body = self.parse_optional_else()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::While(cond, body, else_body) })
    }

    fn parse_for(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let target = self.parse_target_list()?;
        if !self.match_keyword("in") {
            return Err(PyError::perr("expected 'in' in for-loop", self.line()));
        }
        let iterable = self.parse_expr_or_tuple()?;
        let body = self.parse_block()?;
        let else_body = self.parse_optional_else()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::For(target, iterable, body, else_body) })
    }

    /// A comma-separated assignment or loop target: a name or a tuple.
    fn parse_target_list(&mut self) -> P<Expr> {
        let mut items = vec![self.parse_target_atom()?];
        let mut saw_comma = false;
        while self.is_op(",") {
            self.advance();
            saw_comma = true;
            if self.is_keyword("in") || self.is_op("=") {
                break;
            }
            items.push(self.parse_target_atom()?);
        }
        Ok(if saw_comma { Expr::Tuple(items) } else { items.pop().unwrap() })
    }

    /// A single target: a name, an index, a `*rest`, or a bracketed nested target.
    fn parse_target_atom(&mut self) -> P<Expr> {
        if self.match_op("*") {
            return Ok(Expr::Starred(Box::new(self.parse_target_atom()?)));
        }
        if self.is_op("(") || self.is_op("[") {
            let close = if self.is_op("(") { ")" } else { "]" };
            self.advance();
            self.enter()?;
            let mut items = Vec::new();
            while !self.is_op(close) {
                items.push(self.parse_target_atom()?);
                if !self.match_op(",") {
                    break;
                }
            }
            self.leave();
            self.expect_op(close)?;
            return Ok(Expr::Tuple(items));
        }
        self.parse_postfix()
    }

    /// `@decorator` lines, then the `def` or `class` they decorate.
    fn parse_decorated(&mut self) -> P<Stmt> {
        let mut decorators = Vec::new();
        while self.match_op("@") {
            decorators.push(self.parse_expr()?);
            if *self.kind() != Tok::Newline {
                return Err(PyError::perr("expected a new line after a decorator", self.line()));
            }
            self.skip_newlines();
        }
        if self.is_keyword("def") {
            return self.parse_def(decorators, false);
        }
        if self.is_keyword("class") {
            return self.parse_class(decorators);
        }
        if self.is_keyword("async") {
            return self.parse_async(decorators);
        }
        Err(PyError::perr("a decorator must be followed by 'def' or 'class'", self.line()))
    }

    /// `async def`, `async with` or `async for`.
    fn parse_async(&mut self, decorators: Vec<Expr>) -> P<Stmt> {
        self.advance();
        if self.is_keyword("def") {
            return self.parse_def(decorators, true);
        }
        if !decorators.is_empty() {
            return Err(PyError::perr("a decorator must be followed by 'def' or 'class'", self.line()));
        }
        if self.is_keyword("with") {
            return self.parse_async_with();
        }
        if self.is_keyword("for") {
            return self.parse_async_for();
        }
        Err(PyError::perr("expected 'def', 'with' or 'for' after 'async'", self.line()))
    }

    /// A fresh name only this parse uses, `$aN`.
    fn temp(&mut self, what: &str) -> Rc<str> {
        let id = Rc::from(format!("${what}{}", self.temps));
        self.temps += 1;
        id
    }

    /// `async with a as x, b: body`, written out as Python spells it:
    /// `__aenter__` awaited before the body, `__aexit__` awaited after it
    /// with the exception that left it, which it may swallow.
    fn parse_async_with(&mut self) -> P<Stmt> {
        let line = self.line();
        let Stmt { kind: StmtKind::With(items, body), .. } = self.parse_with()? else { unreachable!() };
        let mut body = body;
        for (manager, target) in items.into_iter().rev() {
            body = vec![self.async_with_item(manager, target, body, line)];
        }
        Ok(body.pop().unwrap())
    }

    fn async_with_item(&mut self, manager: Expr, target: Option<Expr>, body: Vec<Stmt>, line: u32) -> Stmt {
        let mgr = self.temp("amgr");
        let ok = self.temp("aok");
        let err = self.temp("aerr");
        let name = |id: &Rc<str>| Expr::Name(Name::new(id.clone()));
        let stmt = |kind: StmtKind| Stmt { line, yields: false, kind };
        let call = |obj: Expr, method: &str, args: Vec<Expr>| Expr::Call(Box::new(Expr::Attribute(Box::new(obj), Rc::from(method))), args, Vec::new());
        let enter = Expr::Await(Box::new(call(name(&mgr), "__aenter__", Vec::new())));
        let mut out = vec![stmt(StmtKind::Assign(vec![name(&mgr)], manager)), stmt(StmtKind::Assign(vec![name(&ok)], Expr::Bool(true)))];
        out.push(match target {
            Some(t) => stmt(StmtKind::Assign(vec![t], enter)),
            None => stmt(StmtKind::Expr(enter)),
        });
        let type_of = Expr::Call(Box::new(Expr::Name(Name::new(Rc::from("type")))), vec![name(&err)], Vec::new());
        let swallowed = Expr::Await(Box::new(call(name(&mgr), "__aexit__", vec![type_of, name(&err), Expr::None])));
        let handler = ExceptHandler {
            types: vec![Rc::from("BaseException")],
            exprs: Vec::new(),
            binding: Some(Name::new(err.clone())),
            body: vec![
                stmt(StmtKind::Assign(vec![name(&ok)], Expr::Bool(false))),
                stmt(StmtKind::If(vec![(Expr::Unary(UnOp::Not, Box::new(swallowed)), vec![stmt(StmtKind::Raise(None, None))])], None)),
            ],
        };
        let inner = stmt(StmtKind::Try(Box::new(TryStmt { body, handlers: vec![handler], else_body: None, finally_body: None })));
        let exit = Expr::Await(Box::new(call(name(&mgr), "__aexit__", vec![Expr::None, Expr::None, Expr::None])));
        let finally = vec![stmt(StmtKind::If(vec![(name(&ok), vec![stmt(StmtKind::Expr(exit))])], None))];
        out.push(stmt(StmtKind::Try(Box::new(TryStmt { body: vec![inner], handlers: Vec::new(), else_body: None, finally_body: Some(finally) }))));
        let last = out.pop().unwrap();
        with_before(out, last)
    }

    /// `async for x in items: body`, written out: `__aiter__` once, then
    /// `__anext__` awaited for each item until StopAsyncIteration.
    fn parse_async_for(&mut self) -> P<Stmt> {
        let line = self.line();
        let Stmt { kind: StmtKind::For(target, iterable, body, else_body), .. } = self.parse_for()? else { unreachable!() };
        let it = self.temp("ait");
        let running = self.temp("arun");
        let item = self.temp("aitem");
        let name = |id: &Rc<str>| Expr::Name(Name::new(id.clone()));
        let stmt = |kind: StmtKind| Stmt { line, yields: false, kind };
        let aiter = Expr::Call(Box::new(Expr::Attribute(Box::new(iterable), Rc::from("__aiter__"))), Vec::new(), Vec::new());
        let anext = Expr::Await(Box::new(Expr::Call(Box::new(Expr::Attribute(Box::new(name(&it)), Rc::from("__anext__"))), Vec::new(), Vec::new())));
        let mut each = vec![stmt(StmtKind::Assign(vec![target], name(&item)))];
        each.extend(body);
        let step = TryStmt {
            body: vec![stmt(StmtKind::Assign(vec![name(&item)], anext))],
            handlers: vec![ExceptHandler {
                types: vec![Rc::from("StopAsyncIteration")],
                exprs: Vec::new(),
                binding: None,
                body: vec![stmt(StmtKind::Assign(vec![name(&running)], Expr::Bool(false)))],
            }],
            else_body: Some(each),
            finally_body: None,
        };
        let out = vec![stmt(StmtKind::Assign(vec![name(&it)], aiter)), stmt(StmtKind::Assign(vec![name(&running)], Expr::Bool(true)))];
        let lp = stmt(StmtKind::While(name(&running), vec![stmt(StmtKind::Try(Box::new(step)))], else_body));
        Ok(with_before(out, lp))
    }

    /// `type Name[T] = value`: `Name` holds a TypeAliasType whose value is
    /// worked out when first asked for, so it may name what comes later.
    fn parse_type_alias(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("type alias name")?;
        self.skip_type_params()?;
        self.expect_op("=")?;
        let value = self.parse_expr()?;
        self.expect_statement_end()?;
        let thunk = Expr::Lambda(Rc::new(LambdaDef { params: Params::default(), body: value, frame: Frame::default() }));
        let make = Expr::Call(Box::new(Expr::Name(Name::new(Rc::from("__type_alias__")))), vec![Expr::Str(name.clone()), thunk], Vec::new());
        Ok(Stmt { line, yields: false, kind: StmtKind::Assign(vec![Expr::Name(Name::new(name))], make) })
    }

    /// PEP 695 type parameters, `[T, *Ts, **P]` after a def, class or type
    /// name: read and thrown away, as annotations are.
    fn skip_type_params(&mut self) -> P<()> {
        if !self.is_op("[") {
            return Ok(());
        }
        let mut depth = 0;
        loop {
            match self.kind() {
                Tok::Op("[") | Tok::Op("(") | Tok::Op("{") => depth += 1,
                Tok::Op("]") | Tok::Op(")") | Tok::Op("}") => depth -= 1,
                Tok::Newline | Tok::Eof => return Err(PyError::perr("expected ']'", self.line())),
                _ => {}
            }
            self.advance();
            if depth == 0 {
                return Ok(());
            }
        }
    }

    fn parse_def(&mut self, decorators: Vec<Expr>, is_async: bool) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("function name")?;
        self.skip_type_params()?;
        let params = self.parse_parameters(true)?;
        if self.match_op("->") {
            // A return annotation: read, and thrown away.
            self.parse_expr()?;
        }
        let body = self.parse_block()?;
        let frame = Frame { coroutine: is_async, ..Frame::default() };
        let def = FuncDef { name: name.clone(), params, body, decorators, frame };
        Ok(Stmt { line, yields: false, kind: StmtKind::FuncDef(Rc::new(def), Name::new(name)) })
    }

    fn parse_class(&mut self, decorators: Vec<Expr>) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("class name")?;
        self.skip_type_params()?;
        let mut bases = Vec::new();
        let mut keywords = Vec::new();
        if self.match_op("(") {
            while !self.is_op(")") {
                if let (Tok::Name(k), Tok::Op("=")) = (self.kind().clone(), self.peek_kind(1).clone()) {
                    self.advance();
                    self.advance();
                    keywords.push((k, self.parse_expr()?));
                } else if self.match_op("**") {
                    // `**options`: spread into the keywords, under the name "**".
                    keywords.push((Rc::from("**"), self.parse_expr()?));
                } else if !keywords.is_empty() {
                    return Err(PyError::perr("positional argument follows keyword argument", self.line()));
                } else {
                    bases.push(self.parse_expr()?);
                }
                if !self.match_op(",") {
                    break;
                }
            }
            self.expect_op(")")?;
        }
        let body = self.parse_block()?;
        let def = ClassDef { name: name.clone(), bases, keywords, body, decorators };
        Ok(Stmt { line, yields: false, kind: StmtKind::ClassDef(Rc::new(def), Name::new(name)) })
    }

    fn parse_with(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let bracketed = self.is_op("(") && self.bracketed_with_items();
        if bracketed {
            self.advance();
        }
        let mut items = Vec::new();
        loop {
            let manager = self.parse_expr()?;
            let target = if self.match_keyword("as") { Some(self.parse_target_atom()?) } else { None };
            items.push((manager, target));
            if !self.match_op(",") || (bracketed && self.is_op(")")) {
                break;
            }
        }
        if bracketed {
            self.expect_op(")")?;
        }
        let body = self.parse_block()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::With(items, body) })
    }

    /// Whether `with (` opens a bracketed list of items, `with (a as x, b):`,
    /// rather than an expression that starts with a bracket.
    fn bracketed_with_items(&self) -> bool {
        let mut depth = 0i32;
        let mut i = self.i;
        while let Some(t) = self.tokens.get(i) {
            match &t.kind {
                Tok::Op("(") | Tok::Op("[") | Tok::Op("{") => depth += 1,
                Tok::Op(")") | Tok::Op("]") | Tok::Op("}") => {
                    depth -= 1;
                    if depth == 0 {
                        return matches!(self.tokens.get(i + 1).map(|t| &t.kind), Some(Tok::Op(":")));
                    }
                }
                Tok::Keyword("as") if depth == 1 => return true,
                Tok::Newline | Tok::Eof => return false,
                _ => {}
            }
            i += 1;
        }
        false
    }

    fn parse_assert(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let test = self.parse_expr()?;
        let message = if self.match_op(",") { Some(self.parse_expr()?) } else { None };
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Assert(test, message) })
    }

    fn parse_nonlocal(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut names = Vec::new();
        loop {
            names.push(self.expect_name("name")?);
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Nonlocal(names) })
    }

    /// `(a, b: int = 1, *rest, c, **options)`, shared by `def` and `lambda`
    /// (which has no brackets and no annotations).
    fn parse_parameters(&mut self, parenthesised: bool) -> P<Params> {
        if parenthesised {
            self.expect_op("(")?;
        }
        let mut params = Params::default();
        let mut after_star = false;
        let mut seen = std::collections::HashSet::new();
        while !(if parenthesised { self.is_op(")") } else { self.is_op(":") }) {
            if self.match_op("**") {
                let name = self.expect_name("parameter name")?;
                self.skip_annotation(parenthesised)?;
                if !seen.insert(name.clone()) {
                    return Err(PyError::perr(format!("duplicate parameter '{name}'"), self.line()));
                }
                params.kwargs = Some(name);
                self.match_op(",");
                break;
            }
            if self.match_op("*") {
                if after_star {
                    return Err(PyError::perr("only one '*' parameter is allowed", self.line()));
                }
                after_star = true;
                if let Tok::Name(_) = self.kind() {
                    let name = self.expect_name("parameter name")?;
                    self.skip_annotation(parenthesised)?;
                    if !seen.insert(name.clone()) {
                        return Err(PyError::perr(format!("duplicate parameter '{name}'"), self.line()));
                    }
                    params.variadic = Some(name);
                }
                if !self.match_op(",") {
                    break;
                }
                continue;
            }
            if self.match_op("/") {
                // Everything before it can be passed by position only.
                params.posonly = params.positional.len();
                if !self.match_op(",") {
                    break;
                }
                continue;
            }
            let name = self.expect_name("parameter name")?;
            self.skip_annotation(parenthesised)?;
            if !seen.insert(name.clone()) {
                return Err(PyError::perr(format!("duplicate parameter '{name}'"), self.line()));
            }
            let default = if self.match_op("=") { Some(self.parse_expr()?) } else { None };
            if after_star {
                params.keyword_only.push(Param { name, default });
            } else {
                if default.is_none() && params.positional.iter().any(|p| p.default.is_some()) {
                    return Err(PyError::perr("a parameter without a default follows one with a default", self.line()));
                }
                params.positional.push(Param { name, default });
            }
            if !self.match_op(",") {
                break;
            }
        }
        if parenthesised {
            self.expect_op(")")?;
        }
        Ok(params)
    }

    /// `: annotation` after a parameter name, inside brackets. Read and thrown away.
    fn skip_annotation(&mut self, parenthesised: bool) -> P<()> {
        if parenthesised && self.match_op(":") {
            self.parse_expr()?;
        }
        Ok(())
    }

    fn parse_return(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        if self.at_statement_end() || self.is_op(";") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::Return(None) });
        }
        let mut e = self.parse_expr_or_tuple()?;
        self.expect_statement_end()?;
        let mut before = Vec::new();
        hoist_yield(&mut e, &mut before, line, &mut self.temps);
        Ok(with_before(before, Stmt { line, yields: false, kind: StmtKind::Return(Some(e)) }))
    }

    fn parse_try(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let body = self.parse_block()?;
        let mut handlers = Vec::new();
        self.skip_newlines();
        while self.is_keyword("except") {
            self.advance();
            let mut types = Vec::new();
            let mut binding = None;
            let mut exprs = Vec::new();
            if !self.is_op(":") {
                let e = self.parse_expr()?;
                match exception_names(&e) {
                    Some(names) => {
                        let builtin = names.iter().all(|n| super::builtins::EXCEPTION_KINDS.contains(&n.as_ref()))
                            && !matches!(e, Expr::Attribute(..));
                        types.extend(names);
                        if !builtin {
                            exprs.push((e, true));
                        }
                    }
                    None => exprs.push((e, false)),
                }
                if self.match_keyword("as") {
                    binding = Some(Name::new(self.expect_name("name")?));
                }
            }
            let body = self.parse_block()?;
            handlers.push(ExceptHandler { types, exprs, binding, body });
            self.skip_newlines();
        }
        let else_body = self.parse_optional_else()?;
        self.skip_newlines();
        let mut finally_body = None;
        if self.is_keyword("finally") {
            self.advance();
            finally_body = Some(self.parse_block()?);
        }
        if handlers.is_empty() && finally_body.is_none() {
            return Err(PyError::perr("try needs an 'except' or 'finally' block", line));
        }
        Ok(Stmt { line, yields: false, kind: StmtKind::Try(Box::new(TryStmt { body, handlers, else_body, finally_body })) })
    }

    fn parse_raise(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        if self.at_statement_end() || self.is_op(";") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::Raise(None, None) });
        }
        let e = self.parse_expr()?;
        // The cause is kept on the exception, as `__cause__`.
        let cause = if self.match_keyword("from") { Some(self.parse_expr()?) } else { None };
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Raise(Some(e), cause) })
    }

    /// `a`, `a.b.c`, or with leading dots a module relative to the importing one.
    fn parse_module_name(&mut self, relative: bool) -> P<Rc<str>> {
        let mut out = String::new();
        if relative {
            loop {
                if self.match_op(".") {
                    out.push('.');
                } else if self.match_op("...") {
                    out.push_str("...");
                } else {
                    break;
                }
            }
            if !out.is_empty() && self.is_keyword("import") {
                return Ok(Rc::from(out));
            }
        }
        out.push_str(&self.expect_name("module name")?);
        while self.match_op(".") {
            out.push('.');
            out.push_str(&self.expect_name("module name")?);
        }
        Ok(Rc::from(out))
    }

    fn parse_import(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut imports = Vec::new();
        loop {
            let module = self.parse_module_name(false)?;
            let alias = if self.match_keyword("as") { Some(self.expect_name("name")?) } else { None };
            let aliased = alias.is_some();
            // `import a.b` binds `a`, through which `a.b` is reached.
            let bound = alias.unwrap_or_else(|| Rc::from(module.split('.').next().unwrap_or(&module)));
            let kind = StmtKind::Import { module, target: Some(Name::new(bound)), aliased, names: None };
            imports.push(Stmt { line, yields: false, kind });
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_statement_end()?;
        if imports.len() == 1 {
            return Ok(imports.pop().unwrap());
        }
        // `import a, b` is one statement holding several imports.
        Ok(Stmt { line, yields: false, kind: StmtKind::If(vec![(Expr::Bool(true), imports)], None) })
    }

    fn parse_from_import(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let module = self.parse_module_name(true)?;
        if !self.match_keyword("import") {
            return Err(PyError::perr("expected 'import'", self.line()));
        }
        if self.match_op("*") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::ImportStar(module, Name::new(Rc::from("*"))) });
        }
        let mut names = Vec::new();
        let bracketed = self.match_op("(");
        loop {
            let n = self.expect_name("name")?;
            let alias = if self.match_keyword("as") { Some(self.expect_name("name")?) } else { None };
            let bound = alias.unwrap_or_else(|| n.clone());
            names.push((n, Name::new(bound)));
            if !(self.match_op(",") && !(bracketed && self.is_op(")"))) {
                break;
            }
        }
        if bracketed {
            self.expect_op(")")?;
        }
        self.expect_statement_end()?;
        // Every feature `__future__` names is on already: the import changes nothing.
        if module.as_ref() == "__future__" {
            if let Some((n, _)) = names.iter().find(|(n, _)| !FUTURE_FEATURES.contains(&n.as_ref())) {
                return Err(PyError::perr(format!("future feature {n} is not defined"), line));
            }
            return Ok(Stmt { line, yields: false, kind: StmtKind::Pass });
        }
        Ok(Stmt { line, yields: false, kind: StmtKind::Import { module, target: None, aliased: false, names: Some(names) } })
    }

    fn parse_global(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut names = Vec::new();
        loop {
            names.push(self.expect_name("name")?);
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Global(names) })
    }

    fn parse_del(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut targets = Vec::new();
        loop {
            targets.push(self.parse_postfix()?);
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Del(targets) })
    }

    /// A simple statement, with any `yield` inside one of its expressions
    /// moved to a statement of its own just before it.
    fn parse_simple_statement(&mut self) -> P<Stmt> {
        let line = self.line();
        let mut stmt = self.parse_simple_statement_inner()?;
        let mut before = Vec::new();
        match &mut stmt.kind {
            StmtKind::Expr(e) | StmtKind::Assign(_, e) => {
                if matches!(e, Expr::Yield(_) | Expr::YieldFrom(_)) {
                    hoist_yields(e, &mut before, line, &mut self.temps);
                } else {
                    hoist_yield(e, &mut before, line, &mut self.temps);
                }
            }
            StmtKind::AugAssign(_, _, e) => hoist_yield(e, &mut before, line, &mut self.temps),
            _ => {}
        }
        if let StmtKind::Assign(targets, _) = &mut stmt.kind {
            for t in targets {
                hoist_yields(t, &mut before, line, &mut self.temps);
            }
        }
        Ok(with_before(before, stmt))
    }

    fn parse_simple_statement_inner(&mut self) -> P<Stmt> {
        let line = self.line();
        if self.is_keyword("yield") {
            let e = self.parse_yield()?;
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::Expr(e) });
        }
        let first = self.parse_expr_or_tuple()?;
        // An annotated name, `x: int` or `x: int = 1`. The annotation is kept
        // only as a plain name, for `__annotations__`; it is never evaluated.
        if self.is_op(":") && matches!(first, Expr::Name(_) | Expr::Attribute(..) | Expr::Index(..)) {
            self.advance();
            let annotation = self.parse_expr()?;
            let name = match annotation {
                Expr::Name(n) => Some(n.id),
                Expr::Str(s) => Some(s),
                Expr::Attribute(_, n) => Some(n),
                Expr::Index(base, _) => match *base {
                    Expr::Name(n) => Some(n.id),
                    Expr::Attribute(_, n) => Some(n),
                    _ => None,
                },
                _ => None,
            };
            let value = if self.match_op("=") { Some(self.parse_expr_or_tuple()?) } else { None };
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::AnnAssign(first, name, value) });
        }
        // Augmented assignment.
        if let Tok::Op(o) = self.kind() {
            if let Some(binop) = aug_op(o) {
                self.advance();
                let value = self.parse_value_or_yield()?;
                self.expect_statement_end()?;
                return Ok(Stmt { line, yields: false, kind: StmtKind::AugAssign(first, binop, value) });
            }
        }
        // Plain or chained assignment: every part before the final `=` is a target.
        if self.is_op("=") {
            let mut parts = vec![first];
            while self.match_op("=") {
                parts.push(self.parse_value_or_yield()?);
            }
            let value = parts.pop().unwrap();
            self.expect_statement_end()?;
            return Ok(Stmt { line, yields: false, kind: StmtKind::Assign(parts, value) });
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, yields: false, kind: StmtKind::Expr(first) })
    }

    /// An expression that may be a bare comma tuple (`a, b = …`, `return a, b`),
    /// whose items may be starred (`first, *rest = …`, `return *head, last`).
    fn parse_expr_or_tuple(&mut self) -> P<Expr> {
        let first = self.parse_tuple_item()?;
        if self.is_op(",") {
            let mut items = vec![first];
            while self.match_op(",") {
                if self.is_op("=")
                    || matches!(self.kind(), Tok::Newline | Tok::Eof | Tok::Dedent | Tok::Op(";") | Tok::Op(":") | Tok::Op(")") | Tok::Op("]") | Tok::Op("}"))
                {
                    break;
                }
                items.push(self.parse_tuple_item()?);
            }
            return Ok(Expr::Tuple(items));
        }
        Ok(first)
    }

    /// The right of `=`: an expression, or a `yield`.
    fn parse_value_or_yield(&mut self) -> P<Expr> {
        if self.is_keyword("yield") {
            return self.parse_yield();
        }
        self.parse_expr_or_tuple()
    }

    /// `yield`, `yield value`, `yield a, b` or `yield from iterable`.
    fn parse_yield(&mut self) -> P<Expr> {
        self.advance();
        if self.match_keyword("from") {
            return Ok(Expr::YieldFrom(Box::new(self.parse_expr()?)));
        }
        if self.at_statement_end() || self.is_op(")") || self.is_op(";") || self.is_op("=") {
            return Ok(Expr::Yield(None));
        }
        Ok(Expr::Yield(Some(Box::new(self.parse_expr_or_tuple()?))))
    }

    fn parse_tuple_item(&mut self) -> P<Expr> {
        if self.match_op("*") {
            return Ok(Expr::Starred(Box::new(self.parse_or()?)));
        }
        self.parse_expr()
    }

    // MARK: - Expressions, by precedence

    fn parse_expr(&mut self) -> P<Expr> {
        self.enter()?;
        let r = if self.is_keyword("lambda") { self.parse_lambda() } else { self.parse_named() };
        self.leave();
        r
    }

    /// An expression, or `name := value`.
    fn parse_named(&mut self) -> P<Expr> {
        let e = self.parse_ternary()?;
        if self.is_op(":=") {
            let Expr::Name(n) = e else {
                return Err(PyError::perr("cannot use assignment expressions with this expression", self.line()));
            };
            self.advance();
            let value = self.parse_expr()?;
            return Ok(Expr::Walrus(Box::new((Name::new(n.id), value))));
        }
        Ok(e)
    }

    fn parse_lambda(&mut self) -> P<Expr> {
        self.advance();
        let params = self.parse_parameters(false)?;
        self.expect_op(":")?;
        let body = self.parse_expr()?;
        Ok(Expr::Lambda(Rc::new(LambdaDef { params, body, frame: Frame::default() })))
    }

    fn parse_ternary(&mut self) -> P<Expr> {
        let value = self.parse_or()?;
        if self.is_keyword("if") {
            self.advance();
            let cond = self.parse_or()?;
            if !self.match_keyword("else") {
                return Err(PyError::perr("expected 'else' in conditional expression", self.line()));
            }
            let or_else = self.parse_expr()?;
            return Ok(Expr::Ternary(Box::new((cond, value, or_else))));
        }
        Ok(value)
    }

    fn parse_or(&mut self) -> P<Expr> {
        let mut left = self.parse_and()?;
        while self.match_keyword("or") {
            left = Expr::BoolOp(BoolKind::Or, Box::new(left), Box::new(self.parse_and()?));
        }
        Ok(left)
    }

    fn parse_and(&mut self) -> P<Expr> {
        let mut left = self.parse_not()?;
        while self.match_keyword("and") {
            left = Expr::BoolOp(BoolKind::And, Box::new(left), Box::new(self.parse_not()?));
        }
        Ok(left)
    }

    fn parse_not(&mut self) -> P<Expr> {
        if self.match_keyword("not") {
            self.enter()?;
            let inner = self.parse_not();
            self.leave();
            return Ok(Expr::Unary(UnOp::Not, Box::new(inner?)));
        }
        self.parse_comparison()
    }

    fn parse_comparison(&mut self) -> P<Expr> {
        let left = self.parse_bit_or()?;
        let mut pairs = Vec::new();
        loop {
            let mut op = None;
            if let Tok::Op(o) = self.kind() {
                op = match *o {
                    "==" => Some(CmpOp::Eq),
                    "!=" => Some(CmpOp::Ne),
                    "<" => Some(CmpOp::Lt),
                    "<=" => Some(CmpOp::Le),
                    ">" => Some(CmpOp::Gt),
                    ">=" => Some(CmpOp::Ge),
                    _ => None,
                };
                if op.is_some() {
                    self.advance();
                }
            } else if self.is_keyword("in") {
                self.advance();
                op = Some(CmpOp::In);
            } else if self.is_keyword("is") {
                self.advance();
                op = Some(if self.match_keyword("not") { CmpOp::IsNot } else { CmpOp::Is });
            } else if self.is_keyword("not") && *self.peek_kind(1) == Tok::Keyword("in") {
                self.advance();
                self.advance();
                op = Some(CmpOp::NotIn);
            }
            let Some(op) = op else { break };
            pairs.push((op, self.parse_bit_or()?));
        }
        if pairs.is_empty() {
            return Ok(left);
        }
        Ok(Expr::Compare(Box::new(left), pairs))
    }

    // `|` then `^` then `&`, at Python's precedence.
    fn parse_bit_or(&mut self) -> P<Expr> {
        let mut left = self.parse_bit_xor()?;
        while self.match_op("|") {
            left = Expr::Binary(BinOp::BitOr, Box::new(left), Box::new(self.parse_bit_xor()?));
        }
        Ok(left)
    }

    fn parse_bit_xor(&mut self) -> P<Expr> {
        let mut left = self.parse_bit_and()?;
        while self.match_op("^") {
            left = Expr::Binary(BinOp::BitXor, Box::new(left), Box::new(self.parse_bit_and()?));
        }
        Ok(left)
    }

    fn parse_bit_and(&mut self) -> P<Expr> {
        let mut left = self.parse_shift()?;
        while self.match_op("&") {
            left = Expr::Binary(BinOp::BitAnd, Box::new(left), Box::new(self.parse_shift()?));
        }
        Ok(left)
    }

    // `<<` and `>>`, between `&` and `+` as in Python.
    fn parse_shift(&mut self) -> P<Expr> {
        let mut left = self.parse_add()?;
        loop {
            let op = if self.match_op("<<") {
                BinOp::LShift
            } else if self.match_op(">>") {
                BinOp::RShift
            } else {
                break;
            };
            left = Expr::Binary(op, Box::new(left), Box::new(self.parse_add()?));
        }
        Ok(left)
    }

    fn parse_add(&mut self) -> P<Expr> {
        let mut left = self.parse_mul()?;
        loop {
            left = if self.match_op("+") {
                Expr::Binary(BinOp::Add, Box::new(left), Box::new(self.parse_mul()?))
            } else if self.match_op("-") {
                Expr::Binary(BinOp::Sub, Box::new(left), Box::new(self.parse_mul()?))
            } else {
                break;
            };
        }
        Ok(left)
    }

    fn parse_mul(&mut self) -> P<Expr> {
        let mut left = self.parse_factor()?;
        loop {
            let op = if self.match_op("*") {
                BinOp::Mul
            } else if self.match_op("/") {
                BinOp::Div
            } else if self.match_op("//") {
                BinOp::FloorDiv
            } else if self.match_op("%") {
                BinOp::Mod
            } else if self.match_op("@") {
                BinOp::MatMul
            } else {
                break;
            };
            left = Expr::Binary(op, Box::new(left), Box::new(self.parse_factor()?));
        }
        Ok(left)
    }

    fn parse_factor(&mut self) -> P<Expr> {
        let op = if self.match_op("-") {
            UnOp::Neg
        } else if self.match_op("+") {
            UnOp::Pos
        } else if self.match_op("~") {
            UnOp::Invert
        } else {
            return self.parse_power();
        };
        self.enter()?;
        let inner = self.parse_factor();
        self.leave();
        Ok(Expr::Unary(op, Box::new(inner?)))
    }

    fn parse_power(&mut self) -> P<Expr> {
        let base = if self.match_keyword("await") {
            self.enter()?;
            let inner = self.parse_postfix();
            self.leave();
            Expr::Await(Box::new(inner?))
        } else {
            self.parse_postfix()?
        };
        // Right-associative; the exponent may carry its own unary sign.
        if self.match_op("**") {
            self.enter()?;
            let exp = self.parse_factor();
            self.leave();
            return Ok(Expr::Binary(BinOp::Pow, Box::new(base), Box::new(exp?)));
        }
        Ok(base)
    }

    fn parse_postfix(&mut self) -> P<Expr> {
        let mut e = self.parse_atom()?;
        loop {
            e = if self.is_op("(") {
                self.parse_call(e)?
            } else if self.is_op("[") {
                self.parse_subscript(e)?
            } else if self.match_op(".") {
                Expr::Attribute(Box::new(e), self.expect_name("attribute name")?)
            } else {
                break;
            };
        }
        Ok(e)
    }

    fn parse_call(&mut self, callee: Expr) -> P<Expr> {
        self.advance();
        let mut args = Vec::new();
        let mut kwargs = Vec::new();
        while !self.is_op(")") {
            if self.match_op("**") {
                kwargs.push((Rc::from("**"), self.parse_expr()?));
            } else if self.match_op("*") {
                args.push(Expr::Starred(Box::new(self.parse_expr()?)));
            } else if let (Tok::Name(n), Tok::Op("=")) = (self.kind().clone(), self.peek_kind(1).clone()) {
                // kwarg: name = expr (not ==, which lexes as one token)
                self.advance();
                self.advance();
                kwargs.push((n, self.parse_expr()?));
            } else {
                let first = self.parse_expr()?;
                // A bare generator expression argument: `sum(x * 2 for x in xs)`.
                if self.is_keyword("for") {
                    let clauses = self.parse_comprehension_clauses()?;
                    args.push(Expr::Comprehension(Rc::new(Comprehension {
                        kind: CompKind::Gen,
                        element: first,
                        value: None,
                        clauses,
                        frame: Frame::default(),
                    })));
                } else {
                    args.push(first);
                }
            }
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_op(")")?;
        Ok(Expr::Call(Box::new(callee), args, kwargs))
    }

    /// `x[i]`, `x[a:b:c]`, and with commas a tuple of indexes and slices:
    /// `grid[1:3, ::2]` hands `__getitem__` `(slice(1, 3), slice(None, None, 2))`.
    fn parse_subscript(&mut self, base: Expr) -> P<Expr> {
        self.advance();
        let first = self.parse_subscript_item()?;
        if !self.is_op(",") {
            self.expect_op("]")?;
            return Ok(match first {
                SubItem::Index(i) => Expr::Index(Box::new(base), Box::new(i)),
                SubItem::Slice(l, u, s) => Expr::Slice(Box::new((base, l, u, s))),
            });
        }
        let mut items = vec![first.into_expr()];
        while self.match_op(",") {
            if self.is_op("]") {
                break;
            }
            items.push(self.parse_subscript_item()?.into_expr());
        }
        self.expect_op("]")?;
        Ok(Expr::Index(Box::new(base), Box::new(Expr::Tuple(items))))
    }

    fn parse_subscript_item(&mut self) -> P<SubItem> {
        let lower = if self.is_op(":") { None } else { Some(self.parse_tuple_item()?) };
        if !self.is_op(":") {
            return match lower {
                Some(l) => Ok(SubItem::Index(l)),
                None => Err(PyError::perr("invalid subscript", self.line())),
            };
        }
        self.advance();
        let mut upper = None;
        let mut step = None;
        if !self.is_op(":") && !self.is_op("]") && !self.is_op(",") {
            upper = Some(self.parse_expr()?);
        }
        if self.match_op(":") && !self.is_op("]") && !self.is_op(",") {
            step = Some(self.parse_expr()?);
        }
        Ok(SubItem::Slice(lower, upper, step))
    }

    /// The `for … in … [if …]` chain shared by every comprehension form.
    fn parse_comprehension_clauses(&mut self) -> P<Vec<CompClause>> {
        let mut clauses = Vec::new();
        while self.match_keyword("for") {
            let target = self.parse_target_list()?;
            if !self.match_keyword("in") {
                return Err(PyError::perr("expected 'in' in comprehension", self.line()));
            }
            // The iterable stops before a trailing `if`, which filters here.
            let iterable = self.parse_or()?;
            let mut conditions = Vec::new();
            while self.is_keyword("if") {
                self.advance();
                conditions.push(self.parse_or()?);
            }
            clauses.push(CompClause { target, iterable, conditions });
        }
        if clauses.is_empty() {
            return Err(PyError::perr("expected 'for' in comprehension", self.line()));
        }
        Ok(clauses)
    }

    fn parse_atom(&mut self) -> P<Expr> {
        match self.kind().clone() {
            Tok::Int(v) => {
                self.advance();
                Ok(Expr::Int(v))
            }
            Tok::Big(v) => {
                self.advance();
                Ok(Expr::Big(v))
            }
            Tok::Imag(v) => {
                self.advance();
                Ok(Expr::Imag(v))
            }
            Tok::Float(v) => {
                self.advance();
                Ok(Expr::Float(v))
            }
            Tok::Str(_) | Tok::FString(_) | Tok::Invalid(..) => self.parse_strings(),
            Tok::Bytes(_) => {
                let mut joined = Vec::new();
                while let Tok::Bytes(b) = self.kind() {
                    joined.extend_from_slice(b);
                    self.advance();
                }
                Ok(Expr::Bytes(Rc::from(joined)))
            }
            Tok::Keyword("True") => {
                self.advance();
                Ok(Expr::Bool(true))
            }
            Tok::Keyword("False") => {
                self.advance();
                Ok(Expr::Bool(false))
            }
            Tok::Keyword("None") => {
                self.advance();
                Ok(Expr::None)
            }
            Tok::Keyword("lambda") => self.parse_lambda(),
            Tok::Name(n) => {
                self.advance();
                Ok(Expr::Name(Name::new(n)))
            }
            Tok::Op("...") => {
                self.advance();
                Ok(Expr::Name(Name::new(Rc::from("Ellipsis"))))
            }
            Tok::Op("(") => self.nested(Self::parse_group_or_tuple),
            Tok::Op("[") => self.nested(Self::parse_list_literal),
            Tok::Op("{") => self.nested(Self::parse_brace_literal),
            _ => Err(PyError::perr("unexpected token in expression", self.line())),
        }
    }

    /// Adjacent string literals, which concatenate as in Python, f-strings
    /// among them: `f"{n} items " "in all"` is one f-string.
    fn parse_strings(&mut self) -> P<Expr> {
        let mut plain = String::new();
        let mut parts: Option<Vec<FPart>> = None;
        let mut invalid = None;
        loop {
            match self.kind().clone() {
                Tok::Str(s) => match &mut parts {
                    Some(p) => p.push(FPart::Literal(s)),
                    None => plain.push_str(&s),
                },
                Tok::FString(raw) => {
                    let p = parts.get_or_insert_with(|| if plain.is_empty() { Vec::new() } else { vec![FPart::Literal(std::mem::take(&mut plain))] });
                    p.extend(string_parts(&raw, self.depth)?);
                }
                Tok::Invalid(kind, message) => {
                    invalid.get_or_insert((kind, message));
                }
                _ => break,
            }
            self.advance();
        }
        if let Some((kind, message)) = invalid {
            return Ok(Expr::Invalid(kind, Rc::from(message)));
        }
        Ok(match parts {
            Some(p) => Expr::FString(p),
            None => Expr::Str(Rc::from(plain)),
        })
    }

    fn nested(&mut self, f: fn(&mut Self) -> P<Expr>) -> P<Expr> {
        self.enter()?;
        let r = f(self);
        self.leave();
        r
    }

    fn parse_group_or_tuple(&mut self) -> P<Expr> {
        self.advance();
        if self.is_op(")") {
            self.advance();
            return Ok(Expr::Tuple(Vec::new()));
        }
        if self.is_keyword("yield") {
            let y = self.parse_yield()?;
            self.expect_op(")")?;
            return Ok(y);
        }
        let first = self.parse_display_item()?;
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op(")")?;
            return Ok(Expr::Comprehension(Rc::new(Comprehension {
                kind: CompKind::Gen,
                element: first,
                value: None,
                clauses,
                frame: Frame::default(),
            })));
        }
        if self.is_op(",") {
            let mut items = vec![first];
            while self.match_op(",") {
                if self.is_op(")") {
                    break;
                }
                items.push(self.parse_display_item()?);
            }
            self.expect_op(")")?;
            return Ok(Expr::Tuple(items));
        }
        self.expect_op(")")?;
        if let Expr::Starred(_) = first {
            return Err(PyError::perr("cannot use starred expression here", self.line()));
        }
        Ok(first)
    }

    fn parse_list_literal(&mut self) -> P<Expr> {
        self.advance();
        if self.is_op("]") {
            self.advance();
            return Ok(Expr::List(Vec::new()));
        }
        let first = self.parse_display_item()?;
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op("]")?;
            return Ok(Expr::Comprehension(Rc::new(Comprehension {
                kind: CompKind::List,
                element: first,
                value: None,
                clauses,
                frame: Frame::default(),
            })));
        }
        let mut items = vec![first];
        while self.match_op(",") {
            if self.is_op("]") {
                break;
            }
            items.push(self.parse_display_item()?);
        }
        self.expect_op("]")?;
        Ok(Expr::List(items))
    }

    /// One item of a list, tuple or set display: an expression, or `*iterable`.
    fn parse_display_item(&mut self) -> P<Expr> {
        if self.match_op("*") {
            return Ok(Expr::Starred(Box::new(self.parse_or()?)));
        }
        self.parse_expr()
    }

    /// `{}` and `{a: b}` are dicts, `{a, b}` is a set, and either can be a comprehension.
    fn parse_brace_literal(&mut self) -> P<Expr> {
        self.advance();
        if self.is_op("}") {
            self.advance();
            return Ok(Expr::Dict(Vec::new()));
        }
        if self.is_op("**") {
            return self.parse_dict_rest(Vec::new());
        }
        let first = self.parse_display_item()?;
        if !matches!(first, Expr::Starred(_)) && self.match_op(":") {
            let first_value = self.parse_expr()?;
            if self.is_keyword("for") {
                let clauses = self.parse_comprehension_clauses()?;
                self.expect_op("}")?;
                return Ok(Expr::Comprehension(Rc::new(Comprehension {
                    kind: CompKind::Dict,
                    element: first,
                    value: Some(first_value),
                    clauses,
                    frame: Frame::default(),
                })));
            }
            if self.match_op(",") {
                return self.parse_dict_rest(vec![(first, first_value)]);
            }
            self.expect_op("}")?;
            return Ok(Expr::Dict(vec![(first, first_value)]));
        }
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op("}")?;
            return Ok(Expr::Comprehension(Rc::new(Comprehension {
                kind: CompKind::Set,
                element: first,
                value: None,
                clauses,
                frame: Frame::default(),
            })));
        }
        let mut items = vec![first];
        while self.match_op(",") {
            if self.is_op("}") {
                break;
            }
            items.push(self.parse_display_item()?);
        }
        self.expect_op("}")?;
        Ok(Expr::Set(items))
    }

    // MARK: - match

    /// `match subject:` and its `case` blocks. `match` is a keyword only
    /// there: anything else starting with the name (`match = 1`,
    /// `match(x)`) comes back None and parses as an ordinary statement.
    fn try_parse_match(&mut self) -> P<Option<Stmt>> {
        let (start, depth, line) = (self.i, self.depth, self.line());
        if matches!(self.peek_kind(1), Tok::Op("=") | Tok::Op(".") | Tok::Op(",") | Tok::Op(":") | Tok::Newline | Tok::Eof) {
            return Ok(None);
        }
        self.advance();
        let subject = self.parse_expr_or_tuple();
        let opens_block = self.is_op(":")
            && matches!(self.peek_kind(1), Tok::Newline)
            && matches!(self.peek_kind(2), Tok::Indent)
            && matches!(self.peek_kind(3), Tok::Name(n) if n.as_ref() == "case");
        let Ok(subject) = subject.and_then(|s| if opens_block { Ok(s) } else { Err(PyError::perr("", line)) }) else {
            self.i = start;
            self.depth = depth;
            return Ok(None);
        };
        self.advance();
        self.advance();
        self.advance();
        self.enter()?;
        let mut cases = Vec::new();
        self.skip_newlines();
        while *self.kind() != Tok::Dedent && !self.is_eof() {
            if !matches!(self.kind(), Tok::Name(n) if n.as_ref() == "case") {
                return Err(PyError::perr("expected 'case' in a match block", self.line()));
            }
            self.advance();
            let pattern = self.parse_case_patterns()?;
            let guard = if self.match_keyword("if") { Some(self.parse_expr()?) } else { None };
            let body = self.parse_block()?;
            cases.push(MatchCase { pattern, guard, body });
            self.skip_newlines();
        }
        self.leave();
        if *self.kind() == Tok::Dedent {
            self.advance();
        }
        Ok(Some(Stmt { line, yields: false, kind: StmtKind::Match(Box::new(MatchStmt { subject, cases })) }))
    }

    /// What follows `case`: a pattern, or several separated by commas,
    /// which match a sequence.
    fn parse_case_patterns(&mut self) -> P<Pattern> {
        let first = self.parse_pattern_item()?;
        if !self.is_op(",") {
            return match first {
                PatItem::Pat(p) => Ok(p),
                PatItem::Star(_) => Err(PyError::perr("a starred pattern belongs in a sequence", self.line())),
            };
        }
        let mut items = vec![first];
        while self.match_op(",") {
            if self.is_op(":") || self.is_keyword("if") {
                break;
            }
            items.push(self.parse_pattern_item()?);
        }
        self.sequence_pattern(items)
    }

    fn parse_pattern_item(&mut self) -> P<PatItem> {
        if self.match_op("*") {
            let name = self.expect_name("name after '*'")?;
            return Ok(PatItem::Star(if name.as_ref() == "_" { None } else { Some(Name::new(name)) }));
        }
        Ok(PatItem::Pat(self.parse_pattern()?))
    }

    fn sequence_pattern(&self, items: Vec<PatItem>) -> P<Pattern> {
        let mut pats = Vec::with_capacity(items.len());
        let mut star = None;
        for item in items {
            match item {
                PatItem::Pat(p) => pats.push(p),
                PatItem::Star(n) => {
                    if star.is_some() {
                        return Err(PyError::perr("multiple starred names in sequence pattern", self.line()));
                    }
                    star = Some((pats.len(), n));
                }
            }
        }
        Ok(Pattern::Sequence(pats, star))
    }

    /// `p | q | …`, optionally `as name`.
    fn parse_pattern(&mut self) -> P<Pattern> {
        self.enter()?;
        let r = self.parse_or_pattern();
        self.leave();
        let mut p = r?;
        if self.match_keyword("as") {
            let name = self.expect_name("name after 'as'")?;
            p = Pattern::As(Box::new(p), Name::new(name));
        }
        Ok(p)
    }

    fn parse_or_pattern(&mut self) -> P<Pattern> {
        let mut alts = vec![self.parse_closed_pattern()?];
        while self.match_op("|") {
            alts.push(self.parse_closed_pattern()?);
        }
        Ok(if alts.len() == 1 { alts.pop().unwrap() } else { Pattern::Or(alts) })
    }

    fn parse_closed_pattern(&mut self) -> P<Pattern> {
        match self.kind().clone() {
            Tok::Op("(") => {
                self.advance();
                if self.match_op(")") {
                    return Ok(Pattern::Sequence(Vec::new(), None));
                }
                let first = self.parse_pattern_item()?;
                if self.is_op(",") {
                    let mut items = vec![first];
                    while self.match_op(",") {
                        if self.is_op(")") {
                            break;
                        }
                        items.push(self.parse_pattern_item()?);
                    }
                    self.expect_op(")")?;
                    return self.sequence_pattern(items);
                }
                self.expect_op(")")?;
                match first {
                    PatItem::Pat(p) => Ok(p),
                    PatItem::Star(_) => Err(PyError::perr("a starred pattern belongs in a sequence", self.line())),
                }
            }
            Tok::Op("[") => {
                self.advance();
                let mut items = Vec::new();
                while !self.is_op("]") {
                    items.push(self.parse_pattern_item()?);
                    if !self.match_op(",") {
                        break;
                    }
                }
                self.expect_op("]")?;
                self.sequence_pattern(items)
            }
            Tok::Op("{") => {
                self.advance();
                let mut pairs = Vec::new();
                let mut rest = None;
                while !self.is_op("}") {
                    if self.match_op("**") {
                        rest = Some(Name::new(self.expect_name("name after '**'")?));
                        self.match_op(",");
                        break;
                    }
                    let key = self.parse_pattern_value()?;
                    self.expect_op(":")?;
                    let value = self.parse_pattern()?;
                    pairs.push((key, value));
                    if !self.match_op(",") {
                        break;
                    }
                }
                self.expect_op("}")?;
                Ok(Pattern::Mapping(pairs, rest))
            }
            Tok::Name(n) => {
                if n.as_ref() == "_" && !matches!(self.peek_kind(1), Tok::Op(".") | Tok::Op("(")) {
                    self.advance();
                    return Ok(Pattern::Wildcard);
                }
                let dotted = matches!(self.peek_kind(1), Tok::Op("."));
                let target = self.parse_pattern_value()?;
                if self.match_op("(") {
                    let mut positional = Vec::new();
                    let mut keywords = Vec::new();
                    while !self.is_op(")") {
                        if let (Tok::Name(k), Tok::Op("=")) = (self.kind().clone(), self.peek_kind(1).clone()) {
                            self.advance();
                            self.advance();
                            keywords.push((k, self.parse_pattern()?));
                        } else {
                            if !keywords.is_empty() {
                                return Err(PyError::perr("positional patterns follow keyword patterns", self.line()));
                            }
                            positional.push(self.parse_pattern()?);
                        }
                        if !self.match_op(",") {
                            break;
                        }
                    }
                    self.expect_op(")")?;
                    return Ok(Pattern::Class(target, positional, keywords));
                }
                if dotted {
                    return Ok(Pattern::Value(target));
                }
                match target {
                    Expr::Name(n) => Ok(Pattern::Capture(n)),
                    other => Ok(Pattern::Value(other)),
                }
            }
            _ => Ok(Pattern::Value(self.parse_pattern_value()?)),
        }
    }

    /// A literal (a number, maybe signed, a string, None, True, False) or a
    /// dotted name, as `case` and mapping keys take them.
    fn parse_pattern_value(&mut self) -> P<Expr> {
        match self.kind().clone() {
            Tok::Name(n) => {
                self.advance();
                let mut e = Expr::Name(Name::new(n));
                while self.match_op(".") {
                    e = Expr::Attribute(Box::new(e), self.expect_name("attribute name")?);
                }
                Ok(e)
            }
            Tok::Op("-") => {
                self.advance();
                Ok(Expr::Unary(UnOp::Neg, Box::new(self.parse_atom()?)))
            }
            Tok::Int(_) | Tok::Big(_) | Tok::Float(_) | Tok::Str(_) | Tok::Bytes(_) | Tok::Keyword("None") | Tok::Keyword("True") | Tok::Keyword("False") => {
                self.parse_atom()
            }
            _ => Err(PyError::perr("expected a pattern", self.line())),
        }
    }

    /// The rest of a dict display after [pairs], where a `**mapping` item is
    /// kept as `(Starred(mapping), None)`.
    fn parse_dict_rest(&mut self, mut pairs: Vec<(Expr, Expr)>) -> P<Expr> {
        loop {
            if self.is_op("}") {
                break;
            }
            if self.match_op("**") {
                pairs.push((Expr::Starred(Box::new(self.parse_or()?)), Expr::None));
            } else {
                let k = self.parse_expr()?;
                self.expect_op(":")?;
                pairs.push((k, self.parse_expr()?));
            }
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_op("}")?;
        Ok(Expr::Dict(pairs))
    }
}

/// One part of a subscript: an index, or `lower:upper:step`.
enum SubItem {
    Index(Expr),
    Slice(Option<Expr>, Option<Expr>, Option<Expr>),
}

impl SubItem {
    /// As an item of an index tuple: a `slice(...)` call for a slice.
    fn into_expr(self) -> Expr {
        match self {
            SubItem::Index(e) => e,
            SubItem::Slice(l, u, s) => {
                let part = |e: Option<Expr>| e.unwrap_or(Expr::None);
                Expr::Call(Box::new(Expr::Name(Name::new(Rc::from("slice")))), vec![part(l), part(u), part(s)], Vec::new())
            }
        }
    }
}

/// [stmt], or [before] then [stmt] as one statement when there are any.
fn with_before(mut before: Vec<Stmt>, stmt: Stmt) -> Stmt {
    if before.is_empty() {
        return stmt;
    }
    let line = stmt.line;
    before.push(stmt);
    Stmt { line, yields: false, kind: StmtKind::If(vec![(Expr::Bool(true), before)], None) }
}

/// One item of a sequence pattern while it is read.
enum PatItem {
    Pat(Pattern),
    Star(Option<Name>),
}

/// The features `from __future__ import …` may name.
const FUTURE_FEATURES: &[&str] = &[
    "annotations", "division", "absolute_import", "print_function", "unicode_literals", "generator_stop",
    "nested_scopes", "generators", "with_statement",
];
