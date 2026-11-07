//! What the tokens say about shape without parsing: logical lines and their
//! indentation, the blocks indentation opens, and which brackets pair.
//!
//! Exact on a file that parses. On one that does not, pairs are made where
//! they can be and what is left over is listed, so the diagnostics can point at
//! the bracket or the line that is wrong.

use crate::lexer::{TokenKind, Tokens, CONTINUATION, RESET};
use crate::text::Span;

/// One statement's worth of tokens: from the first code token after a line
/// break that ends a statement to the last code token before the next one.
#[derive(Clone, Debug)]
pub struct LogicalLine {
    /// Range into [`Structure::code`].
    pub code_start: usize,
    pub code_end: usize,
    /// The indentation of its first physical line, tabs to the next multiple of eight.
    pub indent: u32,
    /// The leading whitespace of its first physical line.
    pub indent_span: Span,
    pub has_tab: bool,
    pub has_space: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IndentIssue {
    /// The line is indented more than the block it is in, with no `:` before it.
    Unexpected,
    /// The line dedents to a width no enclosing block has.
    Inconsistent,
    /// A header ending with `:` is followed by nothing indented. Index of the header line.
    EmptyBlock,
    /// The indentation mixes tabs and spaces, or the file uses both.
    Mixed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BracketIssue {
    Unclosed(usize),
    Stray(usize),
    /// A closer, and the opener it does not match.
    Wrong(usize, usize),
}

#[derive(Clone, Debug)]
pub struct Structure {
    /// Every code token, in order.
    pub code: Vec<u32>,
    pub lines: Vec<LogicalLine>,
    /// For each token, the token its bracket pairs with, or -1.
    pub pairs: Vec<i32>,
    pub bracket_issues: Vec<BracketIssue>,
    /// (logical line, issue), in order.
    pub indent_issues: Vec<(usize, IndentIssue)>,
}

impl Structure {
    pub fn of(tokens: &Tokens) -> Structure {
        let mut code = Vec::with_capacity(tokens.len() / 2);
        let mut lines = Vec::new();
        let mut line_open: Option<usize> = None;
        let mut physical_start = 0usize;
        for i in 0..tokens.len() {
            let kind = tokens.kind(i);
            if kind == TokenKind::Newline {
                if !tokens.has(i, CONTINUATION) {
                    if let Some(start) = line_open.take() {
                        let mut line = indent_line(tokens, physical_start);
                        line.code_start = start;
                        line.code_end = code.len();
                        lines.push(line);
                    }
                }
                continue;
            }
            if tokens.is_code(i) {
                if line_open.is_none() {
                    line_open = Some(code.len());
                    physical_start = line_start_token(tokens, i);
                }
                code.push(i as u32);
            }
        }
        if let Some(start) = line_open {
            let mut line = indent_line(tokens, physical_start);
            line.code_start = start;
            line.code_end = code.len();
            lines.push(line);
        }
        let (pairs, bracket_issues) = pair_brackets(tokens);
        let mut structure = Structure { code, lines, pairs, bracket_issues, indent_issues: Vec::new() };
        structure.indent_issues = structure.indentation(tokens);
        structure
    }

    /// The token that is the first code token of logical line [line].
    pub fn first(&self, line: usize) -> usize {
        self.code[self.lines[line].code_start] as usize
    }

    pub fn last(&self, line: usize) -> usize {
        self.code[self.lines[line].code_end - 1] as usize
    }

    /// Whether logical line [line] ends with a `:` outside brackets: a block header.
    pub fn is_header(&self, tokens: &Tokens, line: usize) -> bool {
        let last = self.last(line);
        tokens.is_op(last, ":")
    }

    /// The logical line a token is on, by binary search over the code tokens.
    pub fn line_of_token(&self, token: usize) -> Option<usize> {
        let at = self.code.partition_point(|t| (*t as usize) < token);
        let idx = self.lines.partition_point(|l| l.code_start <= at);
        idx.checked_sub(1)
    }

    fn indentation(&self, tokens: &Tokens) -> Vec<(usize, IndentIssue)> {
        let mut issues = Vec::new();
        let mut stack: Vec<u32> = vec![0];
        let mut file_tabs: Option<bool> = None;
        for (index, line) in self.lines.iter().enumerate() {
            let width = line.indent;
            let expect = index > 0 && self.is_header(tokens, index - 1);
            let top = *stack.last().unwrap();
            if expect {
                if width > top {
                    stack.push(width);
                } else {
                    issues.push((index - 1, IndentIssue::EmptyBlock));
                }
            } else if width > top {
                issues.push((index, IndentIssue::Unexpected));
                stack.push(width);
            }
            if width < *stack.last().unwrap() {
                while stack.len() > 1 && width < *stack.last().unwrap() {
                    stack.pop();
                }
                if *stack.last().unwrap() != width {
                    issues.push((index, IndentIssue::Inconsistent));
                    stack.push(width);
                }
            }
            if line.has_tab && line.has_space {
                issues.push((index, IndentIssue::Mixed));
            } else if line.has_tab || line.has_space {
                match file_tabs {
                    None => file_tabs = Some(line.has_tab),
                    Some(tabs) if tabs != line.has_tab => issues.push((index, IndentIssue::Mixed)),
                    _ => {}
                }
            }
        }
        if let Some(last) = self.lines.len().checked_sub(1) {
            if self.is_header(tokens, last) {
                issues.push((last, IndentIssue::EmptyBlock));
            }
        }
        issues
    }
}

/// The first token of the physical line holding token [i].
fn line_start_token(tokens: &Tokens, i: usize) -> usize {
    let mut j = i;
    while j > 0 && tokens.kind(j - 1) != TokenKind::Newline {
        j -= 1;
    }
    j
}

fn indent_line(tokens: &Tokens, first: usize) -> LogicalLine {
    let start = tokens.start(first);
    let mut line = LogicalLine {
        code_start: 0,
        code_end: 0,
        indent: 0,
        indent_span: Span::new(start, start),
        has_tab: false,
        has_space: false,
    };
    if tokens.kind(first) == TokenKind::Whitespace && !tokens.has(first, CONTINUATION) {
        let units = &tokens.src[tokens.start(first) as usize..tokens.end(first) as usize];
        let mut width = 0u32;
        for &c in units {
            if c == b'\t' as u16 {
                width += 8 - width % 8;
                line.has_tab = true;
            } else if c == b' ' as u16 {
                width += 1;
                line.has_space = true;
            }
        }
        line.indent = width;
        line.indent_span = tokens.span(first);
    }
    line
}

/// Pairs brackets of the same kind. A closer that matches something deeper
/// in the stack closes it and leaves what was above it unclosed; one that
/// matches nothing is reported and skipped.
fn pair_brackets(tokens: &Tokens) -> (Vec<i32>, Vec<BracketIssue>) {
    let mut pairs = vec![-1i32; tokens.len()];
    let mut issues = Vec::new();
    let mut stack: Vec<usize> = Vec::new();
    for i in 0..tokens.len() {
        match tokens.kind(i) {
            TokenKind::Newline if tokens.has(i, RESET) => {
                for open in stack.drain(..) {
                    issues.push(BracketIssue::Unclosed(open));
                }
            }
            TokenKind::Operator if tokens.end(i) - tokens.start(i) == 1 => {
                let c = tokens.src[tokens.start(i) as usize] as u8;
                match c {
                    b'(' | b'[' | b'{' => stack.push(i),
                    b')' | b']' | b'}' => {
                        let want = match c {
                            b')' => b'(',
                            b']' => b'[',
                            _ => b'{',
                        };
                        let matches = |t: usize| tokens.src[tokens.start(t) as usize] as u8 == want;
                        match stack.last() {
                            None => issues.push(BracketIssue::Stray(i)),
                            Some(&top) if matches(top) => {
                                stack.pop();
                                pairs[top] = i as i32;
                                pairs[i] = top as i32;
                            }
                            Some(&top) => {
                                if let Some(deep) = stack.iter().rposition(|t| matches(*t)) {
                                    issues.push(BracketIssue::Wrong(i, top));
                                    let open = stack[deep];
                                    for left in stack.drain(deep + 1..) {
                                        if left != top {
                                            issues.push(BracketIssue::Unclosed(left));
                                        }
                                    }
                                    stack.pop();
                                    pairs[open] = i as i32;
                                    pairs[i] = open as i32;
                                } else {
                                    issues.push(BracketIssue::Wrong(i, top));
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }
    for open in stack {
        issues.push(BracketIssue::Unclosed(open));
    }
    (pairs, issues)
}
