//! Port of PyParser.kt: a recursive-descent parser from tokens to statements.
//! Python features PyMini doesn't have (class, with, yield, decorators, …) fail
//! with a message that says so, not a confusing syntax error.
//!
//! One addition: expression nesting is capped at [MAX_NESTING], because a
//! native stack overflow cannot be caught the way the JVM's can.

use std::rc::Rc;

use super::ast::*;
use super::error::PyError;
use super::lexer::{tokenize, FRawPart, Tok, Token};

/// Deepest expression nesting the parser follows. CPython stops at 200 brackets.
pub(crate) const MAX_NESTING: u32 = 100;

const UNSUPPORTED: &[&str] = &["class", "with", "nonlocal", "assert", "yield", "async", "await", "match"];

/// Parse a complete module.
pub(crate) fn parse(source: &str) -> Result<Vec<Stmt>, PyError> {
    let tokens = tokenize(source)?;
    Parser { tokens, i: 0, depth: 0 }.parse_program()
}

/// Parse a standalone expression (f-string placeholders).
fn parse_expression(source: &str, depth: u32) -> Result<Expr, PyError> {
    let tokens = tokenize(source)?;
    let mut p = Parser { tokens, i: 0, depth };
    p.skip_newlines();
    p.parse_expr_or_tuple()
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
                    Some("r") | Some("a") => Some(FConversion::Repr),
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
        if self.is_op(";") {
            self.advance();
            if *self.kind() == Tok::Newline {
                self.advance();
            }
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
        Ok(Stmt { line, kind })
    }

    fn parse_statement(&mut self) -> P<Stmt> {
        if let Tok::Keyword(k) = self.kind() {
            match *k {
                "if" => return self.parse_if(),
                "while" => return self.parse_while(),
                "for" => return self.parse_for(),
                "def" => return self.parse_def(),
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
        if let Tok::Name(n) = self.kind() {
            if UNSUPPORTED.contains(&n.as_ref()) {
                return Err(PyError::perr(format!("'{n}' is not supported by PyMini"), self.line()));
            }
        }
        self.parse_simple_statement()
    }

    fn parse_if(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let mut branches = Vec::new();
        let cond = self.parse_expr()?;
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
        Ok(Stmt { line, kind: StmtKind::If(branches, else_body) })
    }

    fn parse_while(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let cond = self.parse_expr()?;
        let body = self.parse_block()?;
        let else_body = self.parse_optional_else()?;
        Ok(Stmt { line, kind: StmtKind::While(cond, body, else_body) })
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
        Ok(Stmt { line, kind: StmtKind::For(target, iterable, body, else_body) })
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

    /// A single target: a name, an index, or a bracketed nested target.
    fn parse_target_atom(&mut self) -> P<Expr> {
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

    fn parse_def(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("function name")?;
        let (params, variadic) = self.parse_parameters(true)?;
        let body = self.parse_block()?;
        Ok(Stmt { line, kind: StmtKind::FuncDef(Rc::new(FuncDef { name, params, variadic, body })) })
    }

    /// `(a, b=1, *rest)`, shared by `def` and `lambda` (which has no brackets).
    fn parse_parameters(&mut self, parenthesised: bool) -> P<(Vec<Param>, Option<Rc<str>>)> {
        if parenthesised {
            self.expect_op("(")?;
        }
        let mut params = Vec::new();
        let mut variadic = None;
        while !(if parenthesised { self.is_op(")") } else { self.is_op(":") }) {
            if self.match_op("*") {
                variadic = Some(self.expect_name("parameter name")?);
                if !self.match_op(",") {
                    break;
                }
                continue;
            }
            if self.is_op("**") {
                return Err(PyError::perr("**kwargs is not supported by PyMini", self.line()));
            }
            let name = self.expect_name("parameter name")?;
            let default = if self.match_op("=") { Some(self.parse_expr()?) } else { None };
            params.push(Param { name, default });
            if !self.match_op(",") {
                break;
            }
        }
        if parenthesised {
            self.expect_op(")")?;
        }
        Ok((params, variadic))
    }

    fn parse_return(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        if self.at_statement_end() || self.is_op(";") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, kind: StmtKind::Return(None) });
        }
        let e = self.parse_expr_or_tuple()?;
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Return(Some(e)) })
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
            if !self.is_op(":") {
                if self.match_op("(") {
                    while !self.is_op(")") {
                        types.push(self.expect_name("exception type")?);
                        if !self.match_op(",") {
                            break;
                        }
                    }
                    self.expect_op(")")?;
                } else {
                    types.push(self.expect_name("exception type")?);
                }
                if self.match_keyword("as") {
                    binding = Some(self.expect_name("name")?);
                }
            }
            let body = self.parse_block()?;
            handlers.push(ExceptHandler { types, binding, body });
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
        Ok(Stmt { line, kind: StmtKind::Try(Box::new(TryStmt { body, handlers, else_body, finally_body })) })
    }

    fn parse_raise(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        if self.at_statement_end() || self.is_op(";") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, kind: StmtKind::Raise(None) });
        }
        let e = self.parse_expr()?;
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Raise(Some(e)) })
    }

    fn parse_import(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let module = self.expect_name("module name")?;
        let alias = if self.match_keyword("as") { Some(self.expect_name("name")?) } else { None };
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Import { module, alias, names: None } })
    }

    fn parse_from_import(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let module = self.expect_name("module name")?;
        if !self.match_keyword("import") {
            return Err(PyError::perr("expected 'import'", self.line()));
        }
        if self.match_op("*") {
            return Err(PyError::perr("'from ... import *' is not supported by PyMini", line));
        }
        let mut names = Vec::new();
        let bracketed = self.match_op("(");
        loop {
            let n = self.expect_name("name")?;
            let alias = if self.match_keyword("as") { Some(self.expect_name("name")?) } else { None };
            names.push((n, alias));
            if !(self.match_op(",") && !(bracketed && self.is_op(")"))) {
                break;
            }
        }
        if bracketed {
            self.expect_op(")")?;
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Import { module, alias: None, names: Some(names) } })
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
        Ok(Stmt { line, kind: StmtKind::Global(names) })
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
        Ok(Stmt { line, kind: StmtKind::Del(targets) })
    }

    fn parse_simple_statement(&mut self) -> P<Stmt> {
        let line = self.line();
        let first = self.parse_expr_or_tuple()?;
        // Augmented assignment.
        if let Tok::Op(o) = self.kind() {
            if let Some(binop) = aug_op(o) {
                self.advance();
                let value = self.parse_expr_or_tuple()?;
                self.expect_statement_end()?;
                return Ok(Stmt { line, kind: StmtKind::AugAssign(first, binop, value) });
            }
        }
        // Plain or chained assignment: every part before the final `=` is a target.
        if self.is_op("=") {
            let mut parts = vec![first];
            while self.match_op("=") {
                parts.push(self.parse_expr_or_tuple()?);
            }
            let value = parts.pop().unwrap();
            self.expect_statement_end()?;
            return Ok(Stmt { line, kind: StmtKind::Assign(parts, value) });
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Expr(first) })
    }

    /// An expression that may be a bare comma tuple (`a, b = …`, `return a, b`).
    fn parse_expr_or_tuple(&mut self) -> P<Expr> {
        let first = self.parse_expr()?;
        if self.is_op(",") {
            let mut items = vec![first];
            while self.match_op(",") {
                if self.is_op("=") || matches!(self.kind(), Tok::Newline | Tok::Eof | Tok::Dedent) || self.is_op(";") {
                    break;
                }
                items.push(self.parse_expr()?);
            }
            return Ok(Expr::Tuple(items));
        }
        Ok(first)
    }

    // MARK: - Expressions, by precedence

    fn parse_expr(&mut self) -> P<Expr> {
        self.enter()?;
        let r = if self.is_keyword("lambda") { self.parse_lambda() } else { self.parse_ternary() };
        self.leave();
        r
    }

    fn parse_lambda(&mut self) -> P<Expr> {
        self.advance();
        let (params, variadic) = self.parse_parameters(false)?;
        self.expect_op(":")?;
        let body = self.parse_expr()?;
        Ok(Expr::Lambda(Rc::new(LambdaDef { params, variadic, body })))
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
        let mut left = self.parse_add()?;
        while self.match_op("&") {
            left = Expr::Binary(BinOp::BitAnd, Box::new(left), Box::new(self.parse_add()?));
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
        } else {
            return self.parse_power();
        };
        self.enter()?;
        let inner = self.parse_factor();
        self.leave();
        Ok(Expr::Unary(op, Box::new(inner?)))
    }

    fn parse_power(&mut self) -> P<Expr> {
        let base = self.parse_postfix()?;
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
            if self.match_op("*") {
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
                    args.push(Expr::Comprehension(Box::new(Comprehension {
                        kind: CompKind::List,
                        element: first,
                        value: None,
                        clauses,
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

    fn parse_subscript(&mut self, base: Expr) -> P<Expr> {
        self.advance();
        let mut lower = None;
        let mut is_slice = false;
        if !self.is_op(":") {
            lower = Some(self.parse_expr_or_tuple()?);
        }
        let mut upper = None;
        let mut step = None;
        if self.is_op(":") {
            is_slice = true;
            self.advance();
            if !self.is_op(":") && !self.is_op("]") {
                upper = Some(self.parse_expr()?);
            }
            if self.is_op(":") {
                self.advance();
                if !self.is_op("]") {
                    step = Some(self.parse_expr()?);
                }
            }
        }
        self.expect_op("]")?;
        if is_slice {
            return Ok(Expr::Slice(Box::new((base, lower, upper, step))));
        }
        match lower {
            Some(l) => Ok(Expr::Index(Box::new(base), Box::new(l))),
            None => Err(PyError::perr("invalid subscript", self.line())),
        }
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
            Tok::Float(v) => {
                self.advance();
                Ok(Expr::Float(v))
            }
            Tok::Str(_) => {
                // Adjacent string literals concatenate, as in Python.
                let mut joined = String::new();
                while let Tok::Str(s) = self.kind() {
                    joined.push_str(s);
                    self.advance();
                }
                Ok(Expr::Str(Rc::from(joined)))
            }
            Tok::FString(parts) => {
                self.advance();
                Ok(Expr::FString(string_parts(&parts, self.depth)?))
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
                Ok(Expr::Name(n))
            }
            Tok::Op("(") => self.nested(Self::parse_group_or_tuple),
            Tok::Op("[") => self.nested(Self::parse_list_literal),
            Tok::Op("{") => self.nested(Self::parse_brace_literal),
            _ => Err(PyError::perr("unexpected token in expression", self.line())),
        }
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
        let first = self.parse_expr()?;
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op(")")?;
            return Ok(Expr::Comprehension(Box::new(Comprehension {
                kind: CompKind::List,
                element: first,
                value: None,
                clauses,
            })));
        }
        if self.is_op(",") {
            let mut items = vec![first];
            while self.match_op(",") {
                if self.is_op(")") {
                    break;
                }
                items.push(self.parse_expr()?);
            }
            self.expect_op(")")?;
            return Ok(Expr::Tuple(items));
        }
        self.expect_op(")")?;
        Ok(first)
    }

    fn parse_list_literal(&mut self) -> P<Expr> {
        self.advance();
        if self.is_op("]") {
            self.advance();
            return Ok(Expr::List(Vec::new()));
        }
        let first = self.parse_expr()?;
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op("]")?;
            return Ok(Expr::Comprehension(Box::new(Comprehension {
                kind: CompKind::List,
                element: first,
                value: None,
                clauses,
            })));
        }
        let mut items = vec![first];
        while self.match_op(",") {
            if self.is_op("]") {
                break;
            }
            items.push(self.parse_expr()?);
        }
        self.expect_op("]")?;
        Ok(Expr::List(items))
    }

    /// `{}` and `{a: b}` are dicts, `{a, b}` is a set, and either can be a comprehension.
    fn parse_brace_literal(&mut self) -> P<Expr> {
        self.advance();
        if self.is_op("}") {
            self.advance();
            return Ok(Expr::Dict(Vec::new()));
        }
        let first = self.parse_expr()?;
        if self.match_op(":") {
            let first_value = self.parse_expr()?;
            if self.is_keyword("for") {
                let clauses = self.parse_comprehension_clauses()?;
                self.expect_op("}")?;
                return Ok(Expr::Comprehension(Box::new(Comprehension {
                    kind: CompKind::Dict,
                    element: first,
                    value: Some(first_value),
                    clauses,
                })));
            }
            let mut pairs = vec![(first, first_value)];
            while self.match_op(",") {
                if self.is_op("}") {
                    break;
                }
                let k = self.parse_expr()?;
                self.expect_op(":")?;
                pairs.push((k, self.parse_expr()?));
            }
            self.expect_op("}")?;
            return Ok(Expr::Dict(pairs));
        }
        if self.is_keyword("for") {
            let clauses = self.parse_comprehension_clauses()?;
            self.expect_op("}")?;
            return Ok(Expr::Comprehension(Box::new(Comprehension {
                kind: CompKind::Set,
                element: first,
                value: None,
                clauses,
            })));
        }
        let mut items = vec![first];
        while self.match_op(",") {
            if self.is_op("}") {
                break;
            }
            items.push(self.parse_expr()?);
        }
        self.expect_op("}")?;
        Ok(Expr::Set(items))
    }
}
