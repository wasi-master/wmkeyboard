//! Port of PyParser.kt: a recursive-descent parser from tokens to statements.
//! Python features this interpreter doesn't have (yield, async, match) fail
//! with a message that says so, not a confusing syntax error.
//!
//! Additions to PyMini: `class`, decorators, `with`, `nonlocal`, `assert`,
//! keyword-only parameters and `**kwargs`, `*` and `**` spreads in calls and
//! displays, type annotations (parsed and ignored), dotted and relative
//! imports, dotted exception names, and `raise … from …`. Expression nesting
//! is capped at [MAX_NESTING], because a native stack overflow cannot be
//! caught the way the JVM's can.

use std::rc::Rc;

use super::ast::*;
use super::error::PyError;
use super::lexer::{tokenize, FRawPart, Tok, Token};

/// Deepest expression nesting the parser follows. CPython stops at 200 brackets.
pub(crate) const MAX_NESTING: u32 = 100;

const UNSUPPORTED: &[&str] = &["yield", "async", "await", "match"];

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
        if self.is_op("@") {
            return self.parse_decorated();
        }
        if let Tok::Keyword(k) = self.kind() {
            match *k {
                "if" => return self.parse_if(),
                "while" => return self.parse_while(),
                "for" => return self.parse_for(),
                "def" => return self.parse_def(Vec::new()),
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
            return self.parse_def(decorators);
        }
        if self.is_keyword("class") {
            return self.parse_class(decorators);
        }
        Err(PyError::perr("a decorator must be followed by 'def' or 'class'", self.line()))
    }

    fn parse_def(&mut self, decorators: Vec<Expr>) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("function name")?;
        let params = self.parse_parameters(true)?;
        if self.match_op("->") {
            // A return annotation: read, and thrown away.
            self.parse_expr()?;
        }
        let body = self.parse_block()?;
        Ok(Stmt { line, kind: StmtKind::FuncDef(Rc::new(FuncDef { name, params, body, decorators })) })
    }

    fn parse_class(&mut self, decorators: Vec<Expr>) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let name = self.expect_name("class name")?;
        let mut bases = Vec::new();
        if self.match_op("(") {
            while !self.is_op(")") {
                if let (Tok::Name(_), Tok::Op("=")) = (self.kind().clone(), self.peek_kind(1).clone()) {
                    return Err(PyError::perr("class keywords (metaclass=…) are not supported", self.line()));
                }
                bases.push(self.parse_expr()?);
                if !self.match_op(",") {
                    break;
                }
            }
            self.expect_op(")")?;
        }
        if bases.len() > 1 {
            return Err(PyError::perr("a class can have one base class, not several", line));
        }
        let body = self.parse_block()?;
        Ok(Stmt { line, kind: StmtKind::ClassDef(Rc::new(ClassDef { name, bases, body, decorators })) })
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
        Ok(Stmt { line, kind: StmtKind::With(items, body) })
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
        Ok(Stmt { line, kind: StmtKind::Assert(test, message) })
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
        Ok(Stmt { line, kind: StmtKind::Nonlocal(names) })
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
                // Positional-only marker: every parameter here can already be passed by position.
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
                        types.push(self.expect_exception_name()?);
                        if !self.match_op(",") {
                            break;
                        }
                    }
                    self.expect_op(")")?;
                } else {
                    types.push(self.expect_exception_name()?);
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

    /// An exception name in an `except` clause: `ValueError`, or a dotted
    /// `http.HTTPError`, which matches by its last part.
    fn expect_exception_name(&mut self) -> P<Rc<str>> {
        let mut name = self.expect_name("exception type")?;
        while self.match_op(".") {
            name = self.expect_name("exception type")?;
        }
        Ok(name)
    }

    fn parse_raise(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        if self.at_statement_end() || self.is_op(";") {
            self.expect_statement_end()?;
            return Ok(Stmt { line, kind: StmtKind::Raise(None) });
        }
        let e = self.parse_expr()?;
        if self.match_keyword("from") {
            // The cause is evaluated by Python and kept on the exception; here it is read and dropped.
            self.parse_expr()?;
        }
        self.expect_statement_end()?;
        Ok(Stmt { line, kind: StmtKind::Raise(Some(e)) })
    }

    /// `a`, `a.b.c`, or with leading dots a module relative to the importing one.
    fn parse_module_name(&mut self, relative: bool) -> P<Rc<str>> {
        let mut out = String::new();
        if relative {
            while self.is_op(".") {
                self.advance();
                out.push('.');
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
            imports.push(Stmt { line, kind: StmtKind::Import { module, alias, names: None } });
            if !self.match_op(",") {
                break;
            }
        }
        self.expect_statement_end()?;
        if imports.len() == 1 {
            return Ok(imports.pop().unwrap());
        }
        // `import a, b` is one statement holding several imports.
        Ok(Stmt { line, kind: StmtKind::If(vec![(Expr::Bool(true), imports)], None) })
    }

    fn parse_from_import(&mut self) -> P<Stmt> {
        let line = self.line();
        self.advance();
        let module = self.parse_module_name(true)?;
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
        // An annotated name, `x: int` or `x: int = 1`. The annotation is kept
        // only as a plain name, for `__annotations__`; it is never evaluated.
        if self.is_op(":") && matches!(first, Expr::Name(_) | Expr::Attribute(..) | Expr::Index(..)) {
            self.advance();
            let annotation = self.parse_expr()?;
            let name = match annotation {
                Expr::Name(n) => Some(n),
                Expr::Str(s) => Some(s),
                Expr::Attribute(_, n) => Some(n),
                Expr::Index(base, _) => match *base {
                    Expr::Name(n) | Expr::Attribute(_, n) => Some(n),
                    _ => None,
                },
                _ => None,
            };
            let value = if self.match_op("=") { Some(self.parse_expr_or_tuple()?) } else { None };
            self.expect_statement_end()?;
            return Ok(Stmt { line, kind: StmtKind::AnnAssign(first, name, value) });
        }
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
        let params = self.parse_parameters(false)?;
        self.expect_op(":")?;
        let body = self.parse_expr()?;
        Ok(Expr::Lambda(Rc::new(LambdaDef { params, body })))
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
        let first = self.parse_display_item()?;
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
                return Ok(Expr::Comprehension(Box::new(Comprehension {
                    kind: CompKind::Dict,
                    element: first,
                    value: Some(first_value),
                    clauses,
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
            items.push(self.parse_display_item()?);
        }
        self.expect_op("}")?;
        Ok(Expr::Set(items))
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
