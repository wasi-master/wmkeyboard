//! A tolerant recursive-descent parser for the PyMini subset of Python.
//!
//! Blocks come from indentation, one logical line at a time, so an error never
//! spreads past its own line: the line becomes an [`StmtKind::Error`] that
//! keeps its names, and the next line parses as if nothing had happened. A
//! block header that does not parse still owns the lines indented under it.
//! Python that PyMini does not run (`class`, `with`, decorators, `**kwargs`
//! and the rest) is parsed as well as it can be and listed in
//! [`Module::unsupported`].
//!
//! Recursion is bounded: brackets nested past [`MAX_NESTING`] and blocks past
//! [`MAX_BLOCKS`] stop parsing deeper, and long operator chains are built as
//! balanced trees, so no input can overflow the stack.

use crate::ast::*;
use crate::lexer::{fstring_fields, lex_range, string_value, TokenKind, Tokens, FSTRING};
use crate::structure::{LogicalLine, Structure};
use crate::text::Span;

pub const MAX_NESTING: u32 = 40;
pub const MAX_BLOCKS: u32 = 60;
const MAX_TRAILERS: u32 = 400;
const BALANCE_AFTER: usize = 48;

type P<T> = Result<T, ()>;

pub fn parse(tokens: &Tokens, structure: &Structure) -> Module {
    let mut parser = Parser::new(tokens, structure, 0);
    let mut body = Vec::new();
    while parser.li < structure.lines.len() {
        parser.statement(&mut body, 0);
    }
    Module { body, errors: parser.errors, unsupported: parser.unsupported }
}

struct Parser<'a> {
    t: &'a Tokens,
    s: &'a Structure,
    li: usize,
    pos: usize,
    end: usize,
    failed: bool,
    last_end: u32,
    nesting: u32,
    base_nesting: u32,
    errors: Vec<SyntaxError>,
    unsupported: Vec<(Span, &'static str)>,
    /// Error statements for header lines that failed, flushed before the statement.
    loose: Vec<Stmt>,
}

const AUG_OPS: &[&str] = &["+=", "-=", "*=", "/=", "%=", "//=", "**="];
const AUG_UNSUPPORTED: &[&str] = &["&=", "|=", "^=", "<<=", ">>=", "@="];

impl<'a> Parser<'a> {
    fn new(t: &'a Tokens, s: &'a Structure, nesting: u32) -> Parser<'a> {
        Parser {
            t,
            s,
            li: 0,
            pos: 0,
            end: 0,
            failed: false,
            last_end: 0,
            nesting,
            base_nesting: nesting,
            errors: Vec::new(),
            unsupported: Vec::new(),
            loose: Vec::new(),
        }
    }

    // ---- the cursor ------------------------------------------------------

    fn begin_line(&mut self) {
        let line: &LogicalLine = &self.s.lines[self.li];
        self.pos = line.code_start;
        self.end = line.code_end;
        self.failed = false;
        self.nesting = self.base_nesting;
        self.last_end = self.t.start(self.s.code[self.pos] as usize);
    }

    fn cur(&self) -> Option<usize> {
        (self.pos < self.end).then(|| self.s.code[self.pos] as usize)
    }

    fn peek(&self, n: usize) -> Option<usize> {
        (self.pos + n < self.end).then(|| self.s.code[self.pos + n] as usize)
    }

    fn at_end(&self) -> bool {
        self.pos >= self.end
    }

    fn start_here(&self) -> u32 {
        self.cur().map(|t| self.t.start(t)).unwrap_or(self.last_end)
    }

    fn advance(&mut self) -> usize {
        let t = self.s.code[self.pos] as usize;
        self.last_end = self.t.end(t);
        self.pos += 1;
        t
    }

    fn is_op(&self, op: &str) -> bool {
        self.cur().is_some_and(|t| self.t.is_op(t, op))
    }

    fn is_kw(&self, word: &str) -> bool {
        self.cur().is_some_and(|t| self.t.is_keyword(t, word))
    }

    fn peek_op(&self, n: usize, op: &str) -> bool {
        self.peek(n).is_some_and(|t| self.t.is_op(t, op))
    }

    fn eat_op(&mut self, op: &str) -> bool {
        if self.is_op(op) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn eat_kw(&mut self, word: &str) -> bool {
        if self.is_kw(word) {
            self.advance();
            true
        } else {
            false
        }
    }

    fn is_name(&self) -> bool {
        self.cur().is_some_and(|t| self.t.kind(t) == TokenKind::Name)
    }

    fn span_from(&self, start: u32) -> Span {
        Span::new(start, self.last_end.max(start))
    }

    /// Records the first error of the line, at the token met or at the line's end.
    fn fail<T>(&mut self, expected: &str) -> P<T> {
        if !self.failed {
            self.failed = true;
            let error = match self.cur() {
                Some(t) => SyntaxError {
                    span: self.t.span(t),
                    found: self.t.text(t).chars().take(40).collect(),
                    expected: Some(expected.to_string()),
                    at_end: false,
                },
                None => {
                    let last = self.s.code[self.end.saturating_sub(1).max(self.s.lines.get(self.li).map_or(0, |l| l.code_start))] as usize;
                    SyntaxError { span: self.t.span(last), found: String::new(), expected: Some(expected.to_string()), at_end: true }
                }
            };
            self.errors.push(error);
        }
        Err(())
    }

    fn expect_op(&mut self, op: &str) -> P<()> {
        if self.eat_op(op) {
            Ok(())
        } else {
            self.fail(&format!("'{op}'"))
        }
    }

    fn name(&mut self) -> P<(String, Span)> {
        if self.is_name() {
            let t = self.advance();
            Ok((self.t.text(t), self.t.span(t)))
        } else {
            self.fail("<name>")
        }
    }

    fn unsupported(&mut self, span: Span, what: &'static str) {
        self.unsupported.push((span, what));
    }

    fn enter(&mut self) -> P<()> {
        self.nesting += 1;
        if self.nesting > MAX_NESTING {
            return self.fail("<less nesting>");
        }
        Ok(())
    }

    fn leave(&mut self) {
        self.nesting = self.nesting.saturating_sub(1);
    }

    // ---- lines and blocks ------------------------------------------------

    fn statement(&mut self, out: &mut Vec<Stmt>, depth: u32) {
        self.begin_line();
        let first = self.cur().unwrap();
        let kind = self.t.kind(first);
        let word = if matches!(kind, TokenKind::Keyword | TokenKind::SoftKeyword) { self.t.text(first) } else { String::new() };
        let stmt = match word.as_str() {
            "if" => Some(self.if_stmt(depth)),
            "while" => Some(self.while_stmt(depth)),
            "for" => Some(self.for_stmt(depth)),
            "try" => Some(self.try_stmt(depth)),
            "def" => Some(self.def_stmt(depth)),
            "class" => Some(self.class_stmt(depth)),
            "with" => Some(self.with_stmt(depth)),
            "async" => {
                let span = self.t.span(first);
                self.unsupported(span, "async");
                self.advance();
                if self.is_kw("def") {
                    Some(self.def_stmt(depth))
                } else if self.is_kw("for") {
                    Some(self.for_stmt(depth))
                } else if self.is_kw("with") {
                    Some(self.with_stmt(depth))
                } else {
                    self.simple_line(out);
                    None
                }
            }
            "match" if kind == TokenKind::SoftKeyword => Some(self.match_stmt(depth)),
            "elif" | "else" | "except" | "finally" | "case" => {
                self.orphan_clause(out, depth);
                None
            }
            _ if self.t.is_op(first, "@") => Some(self.decorator()),
            _ => {
                self.simple_line(out);
                None
            }
        };
        out.append(&mut self.loose);
        if let Some(stmt) = stmt {
            out.push(stmt);
        }
    }

    /// The statements of the lines indented more than [parent].
    fn suite(&mut self, parent: u32, depth: u32) -> Vec<Stmt> {
        let mut out = Vec::new();
        while self.li < self.s.lines.len() && self.s.lines[self.li].indent > parent {
            self.statement(&mut out, depth);
        }
        out
    }

    /// The `:` and the body of a header whose line is [li]: inline after the
    /// colon, or the lines indented under it. A header that failed still owns
    /// the lines indented under it.
    fn block(&mut self, depth: u32) -> Vec<Stmt> {
        let indent = self.s.lines[self.li].indent;
        let mut body = Vec::new();
        if !self.failed {
            if self.eat_op(":") {
                if !self.at_end() {
                    self.simple_statements(&mut body);
                    self.flush_line();
                    self.li += 1;
                    return body;
                }
            } else {
                let _ = self.fail::<()>("':'");
            }
        }
        self.flush_line();
        self.li += 1;
        if depth < MAX_BLOCKS {
            // The header's own error statement belongs before the header, not in its body.
            let pending = std::mem::take(&mut self.loose);
            body = self.suite(indent, depth + 1);
            self.loose = pending;
        }
        body
    }

    /// When the current line failed, keeps its names as an error statement.
    fn flush_line(&mut self) {
        if self.failed {
            let line = &self.s.lines[self.li];
            let stmt = self.error_stmt(line.code_start, line.code_end);
            self.loose.push(stmt);
            self.failed = false;
        }
    }

    fn error_stmt(&self, from: usize, to: usize) -> Stmt {
        let mut reads = Vec::new();
        let mut binds = Vec::new();
        let mut depth = 0i32;
        let mut equals_at: Option<usize> = None;
        let starts_for = from < to && self.t.is_keyword(self.s.code[from] as usize, "for");
        for k in from..to {
            let t = self.s.code[k] as usize;
            if self.t.kind(t) == TokenKind::Operator {
                match self.t.text(t).as_str() {
                    "(" | "[" | "{" => depth += 1,
                    ")" | "]" | "}" => depth -= 1,
                    "=" if depth == 0 && equals_at.is_none() => equals_at = Some(k),
                    _ => {}
                }
            }
        }
        let in_at = if starts_for { (from..to).find(|k| self.t.is_keyword(self.s.code[*k] as usize, "in")) } else { None };
        let bind_end = equals_at.or(in_at);
        for k in from..to {
            let t = self.s.code[k] as usize;
            if self.t.kind(t) != TokenKind::Name {
                continue;
            }
            if k > from && self.t.is_op(self.s.code[k - 1] as usize, ".") {
                continue;
            }
            let item = (self.t.text(t), self.t.span(t));
            if bind_end.is_some_and(|b| k < b) {
                binds.push(item);
            } else {
                reads.push(item);
            }
        }
        let start = self.t.start(self.s.code[from] as usize);
        let end = self.t.end(self.s.code[to - 1] as usize);
        Stmt { kind: StmtKind::Error { reads, binds }, span: Span::new(start, end) }
    }

    fn simple_line(&mut self, out: &mut Vec<Stmt>) {
        self.simple_statements(out);
        self.li += 1;
    }

    fn simple_statements(&mut self, out: &mut Vec<Stmt>) {
        loop {
            let from = self.pos;
            match self.simple_statement() {
                Ok(stmt) => out.push(stmt),
                Err(()) => {
                    out.push(self.error_stmt(from, self.end));
                    self.failed = false;
                    self.pos = self.end;
                    return;
                }
            }
            if self.eat_op(";") {
                if self.at_end() {
                    return;
                }
                continue;
            }
            if !self.at_end() {
                let from = self.pos;
                let _ = self.fail::<()>("<end of line>");
                out.push(self.error_stmt(from, self.end));
                self.failed = false;
                self.pos = self.end;
            }
            return;
        }
    }

    fn clause_follows(&self, indent: u32, word: &str) -> bool {
        self.li < self.s.lines.len()
            && self.s.lines[self.li].indent == indent
            && self.t.is_keyword(self.s.first(self.li), word)
    }

    // ---- compound statements ---------------------------------------------

    fn if_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        let elif = self.is_kw("elif");
        self.advance();
        let indent = self.s.lines[self.li].indent;
        let test = self.test_or_error();
        let body = self.block(depth);
        let mut orelse = Vec::new();
        if self.clause_follows(indent, "elif") {
            self.begin_line();
            let mut nested = Vec::new();
            let stmt = self.if_stmt(depth);
            nested.append(&mut self.loose);
            nested.push(stmt);
            orelse = nested;
        } else if self.clause_follows(indent, "else") {
            self.begin_line();
            self.advance();
            orelse = self.block(depth);
        }
        Stmt { kind: StmtKind::If { test, body, orelse, elif }, span: self.span_from(start) }
    }

    fn while_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        self.advance();
        let indent = self.s.lines[self.li].indent;
        let test = self.test_or_error();
        let body = self.block(depth);
        let orelse = self.else_clause(indent, depth);
        Stmt { kind: StmtKind::While { test, body, orelse }, span: self.span_from(start) }
    }

    fn else_clause(&mut self, indent: u32, depth: u32) -> Vec<Stmt> {
        if self.clause_follows(indent, "else") {
            self.begin_line();
            self.advance();
            self.block(depth)
        } else {
            Vec::new()
        }
    }

    fn for_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        self.advance();
        let indent = self.s.lines[self.li].indent;
        let target = match self.targets() {
            Ok(t) => t,
            Err(()) => self.error_expr(),
        };
        let iter = if self.failed {
            self.error_expr()
        } else if self.eat_kw("in") {
            match self.expr_list(true) {
                Ok(e) => e,
                Err(()) => self.error_expr(),
            }
        } else {
            let _ = self.fail::<()>("'in'");
            self.error_expr()
        };
        let body = self.block(depth);
        let orelse = self.else_clause(indent, depth);
        Stmt { kind: StmtKind::For { target, iter, body, orelse }, span: self.span_from(start) }
    }

    fn try_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        let try_span = self.t.span(self.advance());
        let indent = self.s.lines[self.li].indent;
        let body = self.block(depth);
        let mut handlers = Vec::new();
        while self.clause_follows(indent, "except") {
            self.begin_line();
            let h_start = self.start_here();
            self.advance();
            let mut types = Vec::new();
            let mut name = None;
            if !self.is_op(":") {
                if self.is_op("*") {
                    let s = self.t.span(self.advance());
                    self.unsupported(s, "except*");
                }
                match self.test() {
                    Ok(e) => {
                        match &e.kind {
                            ExprKind::Tuple(items) => {
                                for item in items {
                                    self.check_exception_type(item);
                                }
                                types.extend(items.iter().cloned());
                            }
                            _ => {
                                self.check_exception_type(&e);
                                types.push(e);
                            }
                        }
                        if self.eat_kw("as") {
                            name = self.name().ok();
                        } else if self.is_op(",") {
                            let _ = self.fail::<()>("'as'");
                        }
                    }
                    Err(()) => {}
                }
            }
            let hbody = self.block(depth);
            handlers.push(Handler { types, name, body: hbody, span: self.span_from(h_start) });
        }
        let orelse = self.else_clause(indent, depth);
        let mut finalbody = Vec::new();
        if self.clause_follows(indent, "finally") {
            self.begin_line();
            self.advance();
            finalbody = self.block(depth);
        }
        if handlers.is_empty() && finalbody.is_empty() {
            self.errors.push(SyntaxError {
                span: try_span,
                found: "try".into(),
                expected: Some("'except' or 'finally'".into()),
                at_end: false,
            });
        }
        Stmt { kind: StmtKind::Try { body, handlers, orelse, finalbody }, span: self.span_from(start) }
    }

    fn check_exception_type(&mut self, e: &Expr) {
        if !matches!(e.kind, ExprKind::Name(_)) {
            self.unsupported(e.span, "exception types other than plain names");
        }
    }

    fn def_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        self.advance();
        let (name, name_span) = match self.name() {
            Ok(n) => n,
            Err(()) => (String::new(), Span::new(self.last_end, self.last_end)),
        };
        let mut params = Params::default();
        if !self.failed {
            if self.eat_op("(") {
                params = self.params(")", true);
                if !self.failed {
                    let _ = self.expect_op(")");
                }
            } else {
                let _ = self.fail::<()>("'('");
            }
        }
        if !self.failed && self.is_op("->") {
            let s = self.t.span(self.advance());
            let _ = self.test();
            self.unsupported(self.span_from(s.start), "annotations");
        }
        let header_end = if !self.failed && self.is_op(":") { self.t.end(self.cur().unwrap()) } else { self.last_end };
        let header = Span::new(start, header_end);
        let body = self.block(depth);
        if name.is_empty() {
            let kind = StmtKind::Unsupported { what: "def", exprs: Vec::new(), binds: Vec::new(), body };
            return Stmt { kind, span: self.span_from(start) };
        }
        Stmt { kind: StmtKind::Def { name, name_span, params, body, header }, span: self.span_from(start) }
    }

    /// Parameters up to [close]: `a, b=1, *rest`. Never fails outright: on an
    /// error it keeps what it read, so a half-typed header still declares them.
    fn params(&mut self, close: &str, annotations: bool) -> Params {
        let mut params = Params::default();
        while !self.at_end() && !self.is_op(close) {
            let star_start = self.start_here();
            if self.eat_op("**") {
                match self.name() {
                    Ok((n, s)) => {
                        self.unsupported(self.span_from(star_start), "**kwargs");
                        params.kwarg = Some(Param { name: n, span: s, default: None });
                    }
                    Err(()) => break,
                }
            } else if self.eat_op("*") {
                if self.is_name() {
                    let (n, s) = self.name().unwrap();
                    params.vararg = Some(Param { name: n, span: s, default: None });
                } else {
                    self.unsupported(self.span_from(star_start), "keyword-only parameters");
                }
            } else if self.eat_op("/") {
                self.unsupported(self.span_from(star_start), "positional-only parameters");
            } else {
                let (n, s) = match self.name() {
                    Ok(x) => x,
                    Err(()) => break,
                };
                if annotations && self.is_op(":") {
                    let a = self.t.start(self.advance());
                    if self.test().is_err() {
                        params.params.push(Param { name: n, span: s, default: None });
                        break;
                    }
                    self.unsupported(self.span_from(a), "annotations");
                }
                let mut default = None;
                if self.eat_op("=") {
                    match self.test() {
                        Ok(e) => default = Some(e),
                        Err(()) => {
                            params.params.push(Param { name: n, span: s, default: Some(self.error_expr()) });
                            break;
                        }
                    }
                }
                params.params.push(Param { name: n, span: s, default });
            }
            if !self.eat_op(",") {
                break;
            }
        }
        params
    }

    fn class_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        let kw = self.t.span(self.advance());
        self.unsupported(kw, "class");
        let mut binds = Vec::new();
        let mut exprs = Vec::new();
        if let Ok((n, s)) = self.name() {
            binds.push(Expr { kind: ExprKind::Name(n), span: s });
            if self.eat_op("(") {
                while !self.at_end() && !self.is_op(")") {
                    match self.argument() {
                        Ok(Arg::Positional(e)) => exprs.push(e),
                        Ok(Arg::Keyword(k)) => exprs.push(k.value),
                        Err(()) => break,
                    }
                    if !self.eat_op(",") {
                        break;
                    }
                }
                if !self.failed {
                    let _ = self.expect_op(")");
                }
            }
        }
        let body = self.block(depth);
        Stmt { kind: StmtKind::Unsupported { what: "class", exprs, binds, body }, span: self.span_from(start) }
    }

    fn with_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        let kw = self.t.span(self.advance());
        self.unsupported(kw, "with");
        let mut exprs = Vec::new();
        let mut binds = Vec::new();
        loop {
            match self.test() {
                Ok(e) => exprs.push(e),
                Err(()) => break,
            }
            if self.eat_kw("as") {
                match self.target_atom() {
                    Ok(t) => binds.push(t),
                    Err(()) => break,
                }
            }
            if !self.eat_op(",") {
                break;
            }
        }
        let body = self.block(depth);
        Stmt { kind: StmtKind::Unsupported { what: "with", exprs, binds, body }, span: self.span_from(start) }
    }

    fn match_stmt(&mut self, depth: u32) -> Stmt {
        let start = self.start_here();
        let kw = self.t.span(self.advance());
        self.unsupported(kw, "match");
        let mut exprs = Vec::new();
        if let Ok(e) = self.expr_list(true) {
            exprs.push(e);
        }
        let body = self.block(depth);
        Stmt { kind: StmtKind::Unsupported { what: "match", exprs, binds: Vec::new(), body }, span: self.span_from(start) }
    }

    /// `elif`, `else`, `except`, `finally` or `case` with nothing to belong
    /// to. A `case` inside a `match` is expected; the others are errors. The
    /// body is parsed all the same, so its names still mean something.
    fn orphan_clause(&mut self, out: &mut Vec<Stmt>, depth: u32) {
        let first = self.cur().unwrap();
        let is_case = self.t.is_keyword(first, "case");
        if !is_case {
            let _ = self.fail::<()>("<statement>");
            self.failed = false;
        }
        // Skip the header to its last `:`.
        let colon = (self.pos..self.end).rev().find(|k| self.t.is_op(self.s.code[*k] as usize, ":"));
        match colon {
            Some(k) => {
                self.pos = k;
                let body = self.block(depth);
                out.extend(body);
            }
            None => {
                self.li += 1;
                let indent = self.s.lines[self.li - 1].indent;
                if depth < MAX_BLOCKS {
                    let body = self.suite(indent, depth + 1);
                    out.extend(body);
                }
            }
        }
    }

    fn decorator(&mut self) -> Stmt {
        let start = self.start_here();
        self.advance();
        let mut exprs = Vec::new();
        if let Ok(e) = self.test() {
            exprs.push(e);
        }
        self.unsupported(self.span_from(start), "decorators");
        let span = self.span_from(start);
        self.flush_line();
        self.li += 1;
        Stmt { kind: StmtKind::Unsupported { what: "decorators", exprs, binds: Vec::new(), body: Vec::new() }, span }
    }

    // ---- simple statements -------------------------------------------------

    fn simple_statement(&mut self) -> P<Stmt> {
        let start = self.start_here();
        let first = self.cur().unwrap();
        let word = if self.t.kind(first) == TokenKind::Keyword { self.t.text(first) } else { String::new() };
        let kind = match word.as_str() {
            "pass" => {
                self.advance();
                StmtKind::Pass
            }
            "break" => {
                self.advance();
                StmtKind::Break
            }
            "continue" => {
                self.advance();
                StmtKind::Continue
            }
            "return" => {
                self.advance();
                if self.at_end() || self.is_op(";") {
                    StmtKind::Return(None)
                } else {
                    StmtKind::Return(Some(self.expr_list(true)?))
                }
            }
            "raise" => {
                self.advance();
                if self.at_end() || self.is_op(";") {
                    StmtKind::Raise(None)
                } else {
                    let e = self.test()?;
                    if self.is_kw("from") {
                        let s = self.t.start(self.advance());
                        self.test()?;
                        self.unsupported(self.span_from(s), "raise ... from");
                    }
                    StmtKind::Raise(Some(e))
                }
            }
            "global" | "nonlocal" => {
                self.advance();
                let mut names = Vec::new();
                loop {
                    names.push(self.name()?);
                    if !self.eat_op(",") {
                        break;
                    }
                }
                if word == "nonlocal" {
                    self.unsupported(self.span_from(start), "nonlocal");
                    let exprs = names.into_iter().map(|(n, s)| Expr { kind: ExprKind::Name(n), span: s }).collect();
                    StmtKind::Unsupported { what: "nonlocal", exprs, binds: Vec::new(), body: Vec::new() }
                } else {
                    StmtKind::Global(names)
                }
            }
            "del" => {
                self.advance();
                let mut targets = Vec::new();
                loop {
                    targets.push(self.bitor()?);
                    if !self.eat_op(",") || self.at_end() {
                        break;
                    }
                }
                StmtKind::Del(targets)
            }
            "import" => {
                self.advance();
                let mut names = Vec::new();
                loop {
                    let (name, span) = self.dotted()?;
                    let asname = if self.eat_kw("as") { Some(self.name()?) } else { None };
                    names.push(ImportName { name, span, asname });
                    if !self.eat_op(",") {
                        break;
                    }
                }
                if names.len() > 1 {
                    self.unsupported(self.span_from(start), "importing several modules in one statement");
                }
                StmtKind::Import(names)
            }
            "from" => {
                self.advance();
                let mut dots = String::new();
                while self.is_op(".") || self.is_op("...") {
                    dots.push_str(&self.t.text(self.advance()));
                }
                let (module, module_span) = if dots.is_empty() || self.is_name() {
                    let (m, s) = self.dotted()?;
                    (format!("{dots}{m}"), s)
                } else {
                    (dots, self.span_from(start))
                };
                if !self.eat_kw("import") {
                    return self.fail("'import'");
                }
                let mut names = Vec::new();
                let mut star = false;
                if self.is_op("*") {
                    let s = self.t.span(self.advance());
                    self.unsupported(s, "import *");
                    star = true;
                } else {
                    let bracketed = self.eat_op("(");
                    loop {
                        if bracketed && self.is_op(")") {
                            break;
                        }
                        let (name, span) = self.name()?;
                        let asname = if self.eat_kw("as") { Some(self.name()?) } else { None };
                        names.push(ImportName { name, span, asname });
                        if !self.eat_op(",") {
                            break;
                        }
                    }
                    if bracketed {
                        self.expect_op(")")?;
                    }
                }
                StmtKind::ImportFrom { module, module_span, names, star }
            }
            "assert" => {
                let kw = self.t.span(self.advance());
                self.unsupported(kw, "assert");
                let mut exprs = vec![self.test()?];
                if self.eat_op(",") {
                    exprs.push(self.test()?);
                }
                StmtKind::Unsupported { what: "assert", exprs, binds: Vec::new(), body: Vec::new() }
            }
            _ => return self.expression_statement(start),
        };
        Ok(Stmt { kind, span: self.span_from(start) })
    }

    fn dotted(&mut self) -> P<(String, Span)> {
        let (mut name, span) = self.name()?;
        let mut span = span;
        while self.is_op(".") {
            self.advance();
            let (part, s) = self.name()?;
            name.push('.');
            name.push_str(&part);
            span = span.cover(s);
        }
        Ok((name, span))
    }

    fn expression_statement(&mut self, start: u32) -> P<Stmt> {
        let first = self.expr_list(true)?;
        if let Some(t) = self.cur() {
            let op = self.t.text(t);
            if self.t.kind(t) == TokenKind::Operator {
                if let Some(op) = AUG_OPS.iter().find(|o| **o == op) {
                    self.advance();
                    let value = self.expr_list(true)?;
                    return Ok(Stmt { kind: StmtKind::AugAssign { target: first, op, value }, span: self.span_from(start) });
                }
                if let Some(op) = AUG_UNSUPPORTED.iter().find(|o| **o == op) {
                    let s = self.t.span(self.advance());
                    self.unsupported(s, op_name(op));
                    let value = self.expr_list(true)?;
                    return Ok(Stmt { kind: StmtKind::AugAssign { target: first, op, value }, span: self.span_from(start) });
                }
                if op == ":" {
                    let a = self.t.start(self.advance());
                    let annotation = self.test()?;
                    self.unsupported(self.span_from(a), "annotations");
                    let value = if self.eat_op("=") { Some(self.expr_list(true)?) } else { None };
                    return Ok(Stmt { kind: StmtKind::AnnAssign { target: first, annotation, value }, span: self.span_from(start) });
                }
            }
        }
        if self.is_op("=") {
            let mut parts = vec![first];
            while self.eat_op("=") {
                parts.push(self.expr_list(true)?);
            }
            let value = parts.pop().unwrap();
            for target in &parts {
                self.check_target(target);
            }
            return Ok(Stmt { kind: StmtKind::Assign { targets: parts, value }, span: self.span_from(start) });
        }
        self.check_no_star(&first);
        Ok(Stmt { kind: StmtKind::Expr(first), span: self.span_from(start) })
    }

    fn check_target(&mut self, target: &Expr) {
        match &target.kind {
            ExprKind::Tuple(items) | ExprKind::List(items) => {
                for item in items {
                    if let ExprKind::Starred(_) = item.kind {
                        self.unsupported(item.span, "starred assignment");
                    } else {
                        self.check_target(item);
                    }
                }
            }
            ExprKind::Starred(_) => self.unsupported(target.span, "starred assignment"),
            _ => {}
        }
    }

    fn check_no_star(&mut self, e: &Expr) {
        match &e.kind {
            ExprKind::Tuple(items) => {
                for item in items {
                    if let ExprKind::Starred(_) = item.kind {
                        self.unsupported(item.span, "* unpacking outside a call");
                    }
                }
            }
            ExprKind::Starred(_) => self.unsupported(e.span, "* unpacking outside a call"),
            _ => {}
        }
    }

    // ---- targets ---------------------------------------------------------

    /// A `for` target: names, attributes, subscripts, brackets of them, commas.
    fn targets(&mut self) -> P<Expr> {
        let start = self.start_here();
        let first = self.target_atom()?;
        if !self.is_op(",") {
            return Ok(first);
        }
        let mut items = vec![first];
        while self.eat_op(",") {
            if self.is_kw("in") || self.at_end() {
                break;
            }
            items.push(self.target_atom()?);
        }
        let tuple = Expr { kind: ExprKind::Tuple(items), span: self.span_from(start) };
        self.check_target(&tuple);
        Ok(tuple)
    }

    fn target_atom(&mut self) -> P<Expr> {
        let start = self.start_here();
        if self.eat_op("*") {
            let inner = self.target_atom()?;
            let e = Expr { kind: ExprKind::Starred(Box::new(inner)), span: self.span_from(start) };
            self.unsupported(e.span, "starred assignment");
            return Ok(e);
        }
        self.postfix()
    }

    // ---- expressions -----------------------------------------------------

    fn error_expr(&self) -> Expr {
        Expr { kind: ExprKind::Error, span: Span::new(self.last_end, self.last_end) }
    }

    fn test_or_error(&mut self) -> Expr {
        match self.test() {
            Ok(e) => e,
            Err(()) => self.error_expr(),
        }
    }

    /// Expressions separated by commas, a tuple when there is a comma. With
    /// [star], items may be `*x`.
    fn expr_list(&mut self, star: bool) -> P<Expr> {
        let start = self.start_here();
        let first = self.list_item(star)?;
        if !self.is_op(",") {
            return Ok(first);
        }
        let mut items = vec![first];
        while self.eat_op(",") {
            if self.at_end() || self.is_op("=") || self.is_op(";") || self.is_op(":") || self.is_op(")") || self.is_op("]") || self.is_op("}") || self.is_aug() {
                break;
            }
            items.push(self.list_item(star)?);
        }
        Ok(Expr { kind: ExprKind::Tuple(items), span: self.span_from(start) })
    }

    fn is_aug(&self) -> bool {
        self.cur().is_some_and(|t| {
            self.t.kind(t) == TokenKind::Operator && {
                let s = self.t.text(t);
                AUG_OPS.contains(&s.as_str()) || AUG_UNSUPPORTED.contains(&s.as_str())
            }
        })
    }

    fn list_item(&mut self, star: bool) -> P<Expr> {
        if star && self.is_op("*") {
            let start = self.t.start(self.advance());
            let inner = self.bitor()?;
            return Ok(Expr { kind: ExprKind::Starred(Box::new(inner)), span: self.span_from(start) });
        }
        self.test()
    }

    /// `lambda`, a conditional expression, or `name := value`.
    fn test(&mut self) -> P<Expr> {
        self.enter()?;
        let result = self.test_inner();
        self.leave();
        result
    }

    fn test_inner(&mut self) -> P<Expr> {
        if self.is_kw("lambda") {
            return self.lambda();
        }
        if self.is_name() && self.peek_op(1, ":=") {
            let start = self.start_here();
            let (n, s) = self.name()?;
            self.advance();
            let value = self.test()?;
            let span = self.span_from(start);
            self.unsupported(span, ":=");
            let target = Expr { kind: ExprKind::Name(n), span: s };
            return Ok(Expr { kind: ExprKind::Walrus { target: Box::new(target), value: Box::new(value) }, span });
        }
        let start = self.start_here();
        let body = self.or_test()?;
        if self.is_kw("if") {
            self.advance();
            let test = self.or_test()?;
            if !self.eat_kw("else") {
                return self.fail("'else'");
            }
            let orelse = self.test()?;
            return Ok(Expr {
                kind: ExprKind::Ternary { body: Box::new(body), test: Box::new(test), orelse: Box::new(orelse) },
                span: self.span_from(start),
            });
        }
        Ok(body)
    }

    fn lambda(&mut self) -> P<Expr> {
        let start = self.start_here();
        self.advance();
        let params = self.params(":", false);
        if self.failed {
            return Err(());
        }
        self.expect_op(":")?;
        let body = self.test()?;
        Ok(Expr { kind: ExprKind::Lambda { params: Box::new(params), body: Box::new(body) }, span: self.span_from(start) })
    }

    /// A left-associative chain: operands joined by any of [ops].
    fn chain(&mut self, ops: &[&'static str], keyword: bool, next: fn(&mut Self) -> P<Expr>, bool_op: bool) -> P<Expr> {
        let first = next(self)?;
        let mut items = vec![first];
        let mut joins: Vec<&'static str> = Vec::new();
        loop {
            let found = ops.iter().find(|op| if keyword { self.is_kw(op) } else { self.is_op(op) });
            let Some(op) = found else { break };
            let at = self.t.span(self.advance());
            if let Some(name) = unsupported_operator(op) {
                self.unsupported(at, name);
            }
            joins.push(op);
            items.push(next(self)?);
        }
        Ok(build_chain(items, joins, bool_op))
    }

    fn or_test(&mut self) -> P<Expr> {
        self.chain(&["or"], true, Self::and_test, true)
    }

    fn and_test(&mut self) -> P<Expr> {
        self.chain(&["and"], true, Self::not_test, true)
    }

    fn not_test(&mut self) -> P<Expr> {
        if self.is_kw("not") {
            let start = self.t.start(self.advance());
            self.enter()?;
            let operand = self.not_test();
            self.leave();
            let operand = operand?;
            return Ok(Expr { kind: ExprKind::Unary { op: "not", operand: Box::new(operand) }, span: self.span_from(start) });
        }
        self.comparison()
    }

    fn comparison(&mut self) -> P<Expr> {
        let start = self.start_here();
        let left = self.bitor()?;
        let mut ops: Vec<(&'static str, Expr)> = Vec::new();
        loop {
            let op: &'static str = if let Some(t) = self.cur() {
                if self.t.kind(t) == TokenKind::Operator {
                    match self.t.text(t).as_str() {
                        "==" => "==",
                        "!=" => "!=",
                        "<" => "<",
                        "<=" => "<=",
                        ">" => ">",
                        ">=" => ">=",
                        _ => break,
                    }
                } else if self.t.is_keyword(t, "in") {
                    "in"
                } else if self.t.is_keyword(t, "is") {
                    if self.peek(1).is_some_and(|n| self.t.is_keyword(n, "not")) {
                        self.advance();
                        "is not"
                    } else {
                        "is"
                    }
                } else if self.t.is_keyword(t, "not") && self.peek(1).is_some_and(|n| self.t.is_keyword(n, "in")) {
                    self.advance();
                    "not in"
                } else {
                    break;
                }
            } else {
                break;
            };
            self.advance();
            ops.push((op, self.bitor()?));
            if ops.len() > BALANCE_AFTER * 4 {
                return self.fail("<shorter comparison>");
            }
        }
        if ops.is_empty() {
            return Ok(left);
        }
        Ok(Expr { kind: ExprKind::Compare { left: Box::new(left), ops }, span: self.span_from(start) })
    }

    fn bitor(&mut self) -> P<Expr> {
        self.chain(&["|"], false, Self::bitxor, false)
    }

    fn bitxor(&mut self) -> P<Expr> {
        self.chain(&["^"], false, Self::bitand, false)
    }

    fn bitand(&mut self) -> P<Expr> {
        self.chain(&["&"], false, Self::shift, false)
    }

    fn shift(&mut self) -> P<Expr> {
        self.chain(&["<<", ">>"], false, Self::arith, false)
    }

    fn arith(&mut self) -> P<Expr> {
        self.chain(&["+", "-"], false, Self::term, false)
    }

    fn term(&mut self) -> P<Expr> {
        self.chain(&["*", "/", "//", "%", "@"], false, Self::factor, false)
    }

    fn factor(&mut self) -> P<Expr> {
        let op: Option<&'static str> = if self.is_op("-") {
            Some("-")
        } else if self.is_op("+") {
            Some("+")
        } else if self.is_op("~") {
            Some("~")
        } else {
            None
        };
        if let Some(op) = op {
            let at = self.t.span(self.advance());
            if op == "~" {
                self.unsupported(at, "~");
            }
            self.enter()?;
            let operand = self.factor();
            self.leave();
            let operand = operand?;
            return Ok(Expr { kind: ExprKind::Unary { op, operand: Box::new(operand) }, span: self.span_from(at.start) });
        }
        self.power()
    }

    fn power(&mut self) -> P<Expr> {
        let start = self.start_here();
        if self.is_kw("await") {
            let at = self.t.span(self.advance());
            self.unsupported(at, "await");
            let inner = self.power()?;
            return Ok(Expr { kind: ExprKind::Prefixed { value: Some(Box::new(inner)) }, span: self.span_from(start) });
        }
        let base = self.postfix()?;
        if self.eat_op("**") {
            self.enter()?;
            let exponent = self.factor();
            self.leave();
            let exponent = exponent?;
            return Ok(Expr {
                kind: ExprKind::Binary { op: "**", left: Box::new(base), right: Box::new(exponent) },
                span: self.span_from(start),
            });
        }
        Ok(base)
    }

    fn postfix(&mut self) -> P<Expr> {
        let start = self.start_here();
        let mut e = self.atom()?;
        let mut trailers = 0;
        loop {
            if self.is_op("(") {
                e = self.call(e, start)?;
            } else if self.is_op("[") {
                e = self.subscript(e, start)?;
            } else if self.is_op(".") {
                self.advance();
                let (attr, attr_span) = self.name()?;
                e = Expr { kind: ExprKind::Attribute { value: Box::new(e), attr, attr_span }, span: self.span_from(start) };
            } else {
                break;
            }
            trailers += 1;
            if trailers > MAX_TRAILERS {
                return self.fail("<shorter expression>");
            }
        }
        Ok(e)
    }

    fn call(&mut self, func: Expr, start: u32) -> P<Expr> {
        let open = self.t.start(self.advance());
        self.enter()?;
        let mut args = Vec::new();
        let mut keywords = Vec::new();
        while !self.at_end() && !self.is_op(")") {
            match self.argument() {
                Ok(Arg::Positional(e)) => args.push(e),
                Ok(Arg::Keyword(k)) => keywords.push(k),
                Err(()) => {
                    self.leave();
                    return Err(());
                }
            }
            if !self.eat_op(",") {
                break;
            }
        }
        self.leave();
        let close = if self.is_op(")") { Some(self.t.start(self.advance())) } else { return self.fail("')'") };
        Ok(Expr { kind: ExprKind::Call { func: Box::new(func), args, keywords, open, close }, span: self.span_from(start) })
    }

    fn argument(&mut self) -> P<Arg> {
        let start = self.start_here();
        if self.eat_op("**") {
            let inner = self.test()?;
            let span = self.span_from(start);
            self.unsupported(span, "** unpacking");
            return Ok(Arg::Positional(Expr { kind: ExprKind::DoubleStarred(Box::new(inner)), span }));
        }
        if self.eat_op("*") {
            let inner = self.test()?;
            return Ok(Arg::Positional(Expr { kind: ExprKind::Starred(Box::new(inner)), span: self.span_from(start) }));
        }
        if self.is_name() && self.peek_op(1, "=") {
            let (name, name_span) = self.name()?;
            self.advance();
            let value = self.test()?;
            return Ok(Arg::Keyword(Keyword { name, name_span, value }));
        }
        let first = self.test()?;
        if self.is_kw("for") {
            let clauses = self.comp_clauses()?;
            return Ok(Arg::Positional(Expr {
                kind: ExprKind::Comp { kind: CompKind::Generator, elt: Box::new(first), value: None, clauses },
                span: self.span_from(start),
            }));
        }
        Ok(Arg::Positional(first))
    }

    fn subscript(&mut self, value: Expr, start: u32) -> P<Expr> {
        self.advance();
        self.enter()?;
        let result = self.subscript_inner();
        self.leave();
        let index = result?;
        self.expect_op("]")?;
        Ok(Expr { kind: ExprKind::Subscript { value: Box::new(value), index: Box::new(index) }, span: self.span_from(start) })
    }

    fn subscript_inner(&mut self) -> P<Expr> {
        let start = self.start_here();
        let lower = if self.is_op(":") { None } else { Some(self.expr_list(false)?) };
        if !self.is_op(":") {
            return match lower {
                Some(e) => Ok(e),
                None => self.fail("<expression>"),
            };
        }
        self.advance();
        let upper = if self.is_op(":") || self.is_op("]") { None } else { Some(Box::new(self.test()?)) };
        let mut step = None;
        if self.eat_op(":") && !self.is_op("]") {
            step = Some(Box::new(self.test()?));
        }
        Ok(Expr { kind: ExprKind::Slice { lower: lower.map(Box::new), upper, step }, span: self.span_from(start) })
    }

    fn comp_clauses(&mut self) -> P<Vec<CompClause>> {
        let mut clauses = Vec::new();
        while self.is_kw("for") || self.is_kw("async") {
            if self.is_kw("async") {
                let s = self.t.span(self.advance());
                self.unsupported(s, "async");
            }
            self.advance();
            let target = self.targets()?;
            if !self.eat_kw("in") {
                return self.fail("'in'");
            }
            let iter = self.or_test()?;
            let mut ifs = Vec::new();
            while self.is_kw("if") {
                self.advance();
                ifs.push(self.or_test()?);
            }
            clauses.push(CompClause { target, iter, ifs });
        }
        Ok(clauses)
    }

    fn atom(&mut self) -> P<Expr> {
        let Some(t) = self.cur() else { return self.fail("<expression>") };
        let start = self.t.start(t);
        match self.t.kind(t) {
            TokenKind::Name | TokenKind::SoftKeyword => {
                self.advance();
                Ok(Expr { kind: ExprKind::Name(self.t.text(t)), span: self.t.span(t) })
            }
            TokenKind::Number => {
                self.advance();
                Ok(Expr { kind: ExprKind::Number, span: self.t.span(t) })
            }
            TokenKind::String => self.strings(),
            TokenKind::Keyword => {
                let word = self.t.text(t);
                match word.as_str() {
                    "True" | "False" | "None" => {
                        self.advance();
                        let c = match word.as_str() {
                            "True" => "True",
                            "False" => "False",
                            _ => "None",
                        };
                        Ok(Expr { kind: ExprKind::Const(c), span: self.t.span(t) })
                    }
                    "lambda" => self.lambda(),
                    "yield" | "await" => {
                        let at = self.t.span(self.advance());
                        self.unsupported(at, if word == "yield" { "yield" } else { "await" });
                        if self.is_kw("from") {
                            self.advance();
                        }
                        let value = if self.at_end() || self.is_op(")") || self.is_op(";") {
                            None
                        } else {
                            Some(Box::new(self.expr_list(true)?))
                        };
                        Ok(Expr { kind: ExprKind::Prefixed { value }, span: self.span_from(start) })
                    }
                    _ => self.fail("<expression>"),
                }
            }
            TokenKind::Operator => {
                let op = self.t.text(t);
                match op.as_str() {
                    "(" => self.parens(),
                    "[" => self.list(),
                    "{" => self.braces(),
                    "..." => {
                        self.advance();
                        self.unsupported(self.t.span(t), "...");
                        Ok(Expr { kind: ExprKind::Const("Ellipsis"), span: self.t.span(t) })
                    }
                    _ => self.fail("<expression>"),
                }
            }
            _ => self.fail("<expression>"),
        }
    }

    fn strings(&mut self) -> P<Expr> {
        let start = self.start_here();
        let mut value = Some(String::new());
        let mut fstring = false;
        let mut fields = Vec::new();
        while let Some(t) = self.cur() {
            if self.t.kind(t) != TokenKind::String {
                break;
            }
            self.advance();
            if self.t.has(t, FSTRING) {
                fstring = true;
                value = None;
                for field in fstring_fields(self.t, t) {
                    if let Some(e) = self.field(field) {
                        fields.push(e);
                    }
                }
            } else {
                match (string_value(self.t, t), value.as_mut()) {
                    (Some(v), Some(acc)) => acc.push_str(&v),
                    _ => value = None,
                }
            }
        }
        Ok(Expr { kind: ExprKind::Str { value, fstring, fields }, span: self.span_from(start) })
    }

    /// The expression of one f-string replacement field, lexed and parsed on its own.
    fn field(&mut self, span: Span) -> Option<Expr> {
        let sub = lex_range(self.t.src.clone(), span.start, span.end);
        let code: Vec<u32> = (0..sub.len()).filter(|i| sub.is_code(*i)).map(|i| i as u32).collect();
        if code.is_empty() {
            self.errors.push(SyntaxError {
                span: Span::new(span.start.saturating_sub(1), span.end + 1),
                found: String::new(),
                expected: Some("<expression>".into()),
                at_end: false,
            });
            return None;
        }
        let n = code.len();
        let structure = Structure {
            code,
            lines: vec![LogicalLine {
                code_start: 0,
                code_end: n,
                indent: 0,
                indent_span: Span::new(span.start, span.start),
                has_tab: false,
                has_space: false,
            }],
            pairs: Vec::new(),
            bracket_issues: Vec::new(),
            indent_issues: Vec::new(),
        };
        let mut parser = Parser::new(&sub, &structure, self.nesting + 4);
        parser.begin_line();
        let result = parser.expr_list(true);
        if result.is_ok() && !parser.at_end() {
            let _ = parser.fail::<()>("'}'");
        }
        self.errors.append(&mut parser.errors);
        self.unsupported.append(&mut parser.unsupported);
        result.ok()
    }

    fn parens(&mut self) -> P<Expr> {
        let start = self.t.start(self.advance());
        self.enter()?;
        let result = self.parens_inner(start);
        self.leave();
        result
    }

    fn parens_inner(&mut self, start: u32) -> P<Expr> {
        if self.eat_op(")") {
            return Ok(Expr { kind: ExprKind::Tuple(Vec::new()), span: self.span_from(start) });
        }
        if self.is_kw("yield") {
            let e = self.atom()?;
            self.expect_op(")")?;
            return Ok(e);
        }
        let first = self.list_item(true)?;
        if self.is_kw("for") || self.is_kw("async") {
            let clauses = self.comp_clauses()?;
            self.expect_op(")")?;
            return Ok(Expr {
                kind: ExprKind::Comp { kind: CompKind::Generator, elt: Box::new(first), value: None, clauses },
                span: self.span_from(start),
            });
        }
        if self.is_op(",") {
            let mut items = vec![first];
            while self.eat_op(",") {
                if self.is_op(")") {
                    break;
                }
                items.push(self.list_item(true)?);
            }
            self.expect_op(")")?;
            let e = Expr { kind: ExprKind::Tuple(items), span: self.span_from(start) };
            self.check_no_star(&e);
            return Ok(e);
        }
        self.expect_op(")")?;
        if let ExprKind::Starred(_) = first.kind {
            self.unsupported(first.span, "* unpacking outside a call");
        }
        // Keep the brackets in the span, so `(a + b).x` reads from `(`.
        Ok(Expr { kind: first.kind, span: self.span_from(start) })
    }

    fn list(&mut self) -> P<Expr> {
        let start = self.t.start(self.advance());
        self.enter()?;
        let result = self.list_inner(start);
        self.leave();
        result
    }

    fn list_inner(&mut self, start: u32) -> P<Expr> {
        if self.eat_op("]") {
            return Ok(Expr { kind: ExprKind::List(Vec::new()), span: self.span_from(start) });
        }
        let first = self.list_item(true)?;
        if self.is_kw("for") || self.is_kw("async") {
            let clauses = self.comp_clauses()?;
            self.expect_op("]")?;
            return Ok(Expr {
                kind: ExprKind::Comp { kind: CompKind::List, elt: Box::new(first), value: None, clauses },
                span: self.span_from(start),
            });
        }
        let mut items = vec![first];
        while self.eat_op(",") {
            if self.is_op("]") {
                break;
            }
            items.push(self.list_item(true)?);
        }
        self.expect_op("]")?;
        let e = Expr { kind: ExprKind::List(items), span: self.span_from(start) };
        if let ExprKind::List(items) = &e.kind {
            for item in items {
                if let ExprKind::Starred(_) = item.kind {
                    self.unsupported(item.span, "* unpacking outside a call");
                }
            }
        }
        Ok(e)
    }

    fn braces(&mut self) -> P<Expr> {
        let start = self.t.start(self.advance());
        self.enter()?;
        let result = self.braces_inner(start);
        self.leave();
        result
    }

    fn braces_inner(&mut self, start: u32) -> P<Expr> {
        if self.eat_op("}") {
            return Ok(Expr { kind: ExprKind::Dict(Vec::new()), span: self.span_from(start) });
        }
        if self.is_op("**") {
            return self.dict_items(start, Vec::new());
        }
        let first = self.list_item(true)?;
        if self.eat_op(":") {
            let value = self.test()?;
            if self.is_kw("for") || self.is_kw("async") {
                let clauses = self.comp_clauses()?;
                self.expect_op("}")?;
                return Ok(Expr {
                    kind: ExprKind::Comp { kind: CompKind::Dict, elt: Box::new(first), value: Some(Box::new(value)), clauses },
                    span: self.span_from(start),
                });
            }
            let pairs = vec![(first, value)];
            if self.eat_op("}") {
                return Ok(Expr { kind: ExprKind::Dict(pairs), span: self.span_from(start) });
            }
            self.expect_op(",")?;
            return self.dict_items(start, pairs);
        }
        if self.is_kw("for") || self.is_kw("async") {
            let clauses = self.comp_clauses()?;
            self.expect_op("}")?;
            return Ok(Expr {
                kind: ExprKind::Comp { kind: CompKind::Set, elt: Box::new(first), value: None, clauses },
                span: self.span_from(start),
            });
        }
        let mut items = vec![first];
        while self.eat_op(",") {
            if self.is_op("}") {
                break;
            }
            items.push(self.list_item(true)?);
        }
        self.expect_op("}")?;
        for item in &items {
            if let ExprKind::Starred(_) = item.kind {
                self.unsupported(item.span, "* unpacking outside a call");
            }
        }
        Ok(Expr { kind: ExprKind::Set(items), span: self.span_from(start) })
    }

    fn dict_items(&mut self, start: u32, mut pairs: Vec<(Expr, Expr)>) -> P<Expr> {
        while !self.is_op("}") && !self.at_end() {
            if self.is_op("**") {
                let s = self.t.start(self.advance());
                let inner = self.bitor()?;
                let span = self.span_from(s);
                self.unsupported(span, "** unpacking");
                pairs.push((Expr { kind: ExprKind::DoubleStarred(Box::new(inner)), span }, Expr { kind: ExprKind::Error, span }));
            } else {
                let key = self.test()?;
                self.expect_op(":")?;
                let value = self.test()?;
                pairs.push((key, value));
            }
            if !self.eat_op(",") {
                break;
            }
        }
        self.expect_op("}")?;
        Ok(Expr { kind: ExprKind::Dict(pairs), span: self.span_from(start) })
    }
}

enum Arg {
    Positional(Expr),
    Keyword(Keyword),
}

fn unsupported_operator(op: &str) -> Option<&'static str> {
    match op {
        "<<" | ">>" => Some("bit shifts"),
        "@" => Some("@"),
        _ => None,
    }
}

fn op_name(op: &str) -> &'static str {
    match op {
        "&=" => "&=",
        "|=" => "|=",
        "^=" => "^=",
        "<<=" => "bit shifts",
        ">>=" => "bit shifts",
        _ => "@",
    }
}

/// Joins a chain left to right, or as a balanced tree when it is long enough
/// that a left-leaning one would be deep. Spans cover what each node joins.
fn build_chain(mut items: Vec<Expr>, joins: Vec<&'static str>, bool_op: bool) -> Expr {
    let make = |op: &'static str, left: Expr, right: Expr| {
        let span = left.span.cover(right.span);
        let kind = if bool_op {
            ExprKind::BoolOp { op, left: Box::new(left), right: Box::new(right) }
        } else {
            ExprKind::Binary { op, left: Box::new(left), right: Box::new(right) }
        };
        Expr { kind, span }
    };
    if joins.is_empty() {
        return items.pop().unwrap();
    }
    if items.len() <= BALANCE_AFTER {
        let mut iter = items.into_iter();
        let mut acc = iter.next().unwrap();
        for (op, right) in joins.into_iter().zip(iter) {
            acc = make(op, acc, right);
        }
        return acc;
    }
    // Pairwise, round after round, until one is left. Which operator joins
    // which pair no longer matters to anything that reads the tree.
    let op = joins[0];
    let mut level: Vec<Expr> = items;
    while level.len() > 1 {
        let mut next = Vec::with_capacity(level.len() / 2 + 1);
        let mut iter = level.into_iter();
        while let Some(left) = iter.next() {
            match iter.next() {
                Some(right) => next.push(make(op, left, right)),
                None => next.push(left),
            }
        }
        level = next;
    }
    level.pop().unwrap()
}
