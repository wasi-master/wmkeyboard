//! What the editor colours, and the few rules it applies as the user types.
//!
//! Everything here reads tokens only, never a parse: a plugin being typed is
//! broken Python most of the time, and colouring that went dark whenever the
//! file stopped parsing would be dark exactly while someone is typing.

use std::collections::HashSet;

use crate::api;
use crate::lexer::{lex, TokenKind, Tokens, FSTRING, UNTERMINATED};
use crate::structure::Structure;
use crate::text::{units_of, Units};

/// The kind codes [`tokens_packed`] writes.
pub mod kind {
    pub const WHITESPACE: i32 = 0;
    pub const NEWLINE: i32 = 1;
    pub const COMMENT: i32 = 2;
    pub const STRING: i32 = 3;
    pub const FSTRING: i32 = 4;
    pub const NUMBER: i32 = 5;
    pub const NAME: i32 = 6;
    pub const KEYWORD: i32 = 7;
    pub const SOFT_KEYWORD: i32 = 8;
    pub const OPERATOR: i32 = 9;
    pub const UNKNOWN: i32 = 10;
}

/// How a name is coloured.
pub mod style {
    pub const PLAIN: i32 = 0;
    /// A builtin the file does not redefine: `len`, `ValueError`.
    pub const BUILTIN: i32 = 1;
    /// `wm`, `ui`, an imported module, or a member of one.
    pub const API: i32 = 2;
    /// A `def` name, or a name being called.
    pub const FUNCTION: i32 = 3;
    /// A parameter, where it is declared and inside its function.
    pub const PARAMETER: i32 = 4;
    /// ALL_CAPS.
    pub const CONSTANT: i32 = 5;
    /// After a dot.
    pub const ATTRIBUTE: i32 = 6;
}

/// Every token as three ints: `word, start, end`, where
/// `word = kind | (unterminated ? 0x100 : 0) | (name_style << 12)`.
/// See [`kind`] and [`style`]. Offsets are UTF-16; the tokens cover the source.
pub fn tokens_packed(source: &str) -> Vec<i32> {
    pack(&lex(units_of(source)))
}

pub fn tokens_packed_units(units: Units) -> Vec<i32> {
    pack(&lex(units))
}

pub fn pack(t: &Tokens) -> Vec<i32> {
    let styles = name_styles(t);
    let mut out = Vec::with_capacity(t.len() * 3);
    for i in 0..t.len() {
        let k = match t.kind(i) {
            TokenKind::Whitespace => kind::WHITESPACE,
            TokenKind::Newline => kind::NEWLINE,
            TokenKind::Comment => kind::COMMENT,
            TokenKind::String if t.has(i, FSTRING) => kind::FSTRING,
            TokenKind::String => kind::STRING,
            TokenKind::Number => kind::NUMBER,
            TokenKind::Name => kind::NAME,
            TokenKind::Keyword => kind::KEYWORD,
            TokenKind::SoftKeyword => kind::SOFT_KEYWORD,
            TokenKind::Operator => kind::OPERATOR,
            TokenKind::Unknown => kind::UNKNOWN,
        };
        let unterminated = if t.has(i, UNTERMINATED) { 0x100 } else { 0 };
        out.push(k | unterminated | (styles[i] << 12));
        out.push(t.start(i) as i32);
        out.push(t.end(i) as i32);
    }
    out
}

/// The style of every token, 0 for anything that is not a name.
pub fn name_styles(t: &Tokens) -> Vec<i32> {
    let mut styles = vec![style::PLAIN; t.len()];
    let structure = Structure::of(t);
    // Names the file binds at the top level, which are not builtins any more, and modules it imports.
    let mut defined: HashSet<String> = HashSet::new();
    let mut modules: HashSet<String> = HashSet::new();
    for line in 0..structure.lines.len() {
        let first = structure.first(line);
        let next = t.next_code(first);
        if t.kind(first) == TokenKind::Name && next.is_some_and(|n| t.is_op(n, "=")) {
            defined.insert(t.text(first));
        }
        if t.is_keyword(first, "def") {
            if let Some(n) = next.filter(|n| t.kind(*n) == TokenKind::Name) {
                defined.insert(t.text(n));
            }
        }
        if t.is_keyword(first, "import") {
            let mut k = structure.lines[line].code_start + 1;
            let end = structure.lines[line].code_end;
            while k < end {
                let tok = structure.code[k] as usize;
                if t.kind(tok) == TokenKind::Name {
                    let after = if k + 1 < end { Some(structure.code[k + 1] as usize) } else { None };
                    if after.is_some_and(|a| t.is_keyword(a, "as")) {
                        if k + 2 < end {
                            modules.insert(t.text(structure.code[k + 2] as usize));
                        }
                        k += 3;
                        continue;
                    }
                    if k == structure.lines[line].code_start + 1 || t.is_op(structure.code[k - 1] as usize, ",") {
                        modules.insert(t.text(tok));
                    }
                }
                k += 1;
            }
        }
    }
    // Parameters: declared in a def header, and read in the lines indented under it.
    for line in 0..structure.lines.len() {
        let first = structure.first(line);
        if !t.is_keyword(first, "def") {
            continue;
        }
        let range = structure.lines[line].code_start..structure.lines[line].code_end;
        let mut params: HashSet<String> = HashSet::new();
        let mut depth = 0;
        let mut expect_name = false;
        for k in range {
            let tok = structure.code[k] as usize;
            if t.kind(tok) == TokenKind::Operator {
                match t.text(tok).as_str() {
                    "(" => {
                        depth += 1;
                        expect_name = depth == 1;
                    }
                    ")" => depth -= 1,
                    "," if depth == 1 => expect_name = true,
                    "*" | "**" if depth == 1 => {}
                    _ => expect_name = false,
                }
            } else if t.kind(tok) == TokenKind::Name && depth == 1 && expect_name {
                params.insert(t.text(tok));
                styles[tok] = style::PARAMETER;
                expect_name = false;
            } else {
                expect_name = false;
            }
        }
        if params.is_empty() {
            continue;
        }
        let indent = structure.lines[line].indent;
        let mut body = line + 1;
        while body < structure.lines.len() && structure.lines[body].indent > indent {
            for k in structure.lines[body].code_start..structure.lines[body].code_end {
                let tok = structure.code[k] as usize;
                let after_dot = k > 0 && t.is_op(structure.code[k - 1] as usize, ".");
                if t.kind(tok) == TokenKind::Name && !after_dot && params.contains(&t.text(tok)) {
                    styles[tok] = style::PARAMETER;
                }
            }
            body += 1;
        }
    }
    for i in 0..t.len() {
        if t.kind(i) != TokenKind::Name || styles[i] == style::PARAMETER {
            continue;
        }
        let prev = t.prev_code(i);
        let next = t.next_code(i);
        let text = t.text(i);
        styles[i] = if prev.is_some_and(|p| t.is_op(p, ".")) {
            if chain_root(t, i).is_some_and(|r| api::API_GLOBALS.contains(&r.as_str()) || modules.contains(&r)) {
                style::API
            } else {
                style::ATTRIBUTE
            }
        } else if prev.is_some_and(|p| t.is_keyword(p, "def")) {
            style::FUNCTION
        } else if (api::API_GLOBALS.contains(&text.as_str()) && !defined.contains(&text)) || modules.contains(&text) {
            style::API
        } else if api::is_builtin(&text) && !defined.contains(&text) {
            style::BUILTIN
        } else if next.is_some_and(|n| t.is_op(n, "(")) {
            style::FUNCTION
        } else if is_constant_name(&text) {
            style::CONSTANT
        } else {
            style::PLAIN
        };
    }
    styles
}

/// The first name of the dotted chain that token [i] ends.
fn chain_root(t: &Tokens, i: usize) -> Option<String> {
    let mut at = i;
    let mut steps = 0;
    loop {
        let Some(dot) = t.prev_code(at).filter(|d| t.is_op(*d, ".")) else { break };
        let Some(name) = t.prev_code(dot).filter(|n| t.kind(*n) == TokenKind::Name) else { return None };
        at = name;
        steps += 1;
        if steps > 64 {
            return None;
        }
    }
    (at != i).then(|| t.text(at))
}

pub fn is_constant_name(name: &str) -> bool {
    name.chars().count() > 1 && name.chars().any(|c| c.is_uppercase()) && !name.chars().any(|c| c.is_lowercase())
}

/// Keywords that, typed at the start of a line, belong one level out.
pub const DEDENT_KEYWORDS: &[&str] = &["elif", "else", "except", "finally"];
pub const BRACKET_PAIRS: &[(char, char)] = &[('(', ')'), ('[', ']'), ('{', '}')];
pub const LINE_COMMENT: &str = "#";

/// Whether a caret at [offset] is inside a string or a comment, judged from
/// the text before it alone (nothing after the caret can change the answer).
pub fn in_inert(source: &str, offset: usize) -> bool {
    let units: Vec<u16> = source.encode_utf16().collect();
    in_inert_units(&units, offset)
}

pub fn in_inert_units(units: &[u16], offset: usize) -> bool {
    let at = offset.min(units.len());
    if at == 0 {
        return false;
    }
    let t = lex(Units::from(&units[..at]));
    let Some(last) = t.len().checked_sub(1) else { return false };
    match t.kind(last) {
        TokenKind::Comment => true,
        TokenKind::String => t.has(last, UNTERMINATED),
        _ => false,
    }
}

/// Whether [line] ends a block header: its last code token is a `:` outside brackets.
pub fn opens_block(line: &str) -> bool {
    let t = lex(units_of(line));
    let mut depth = 0i32;
    let mut last_colon = false;
    for i in 0..t.len() {
        if !t.is_code(i) {
            continue;
        }
        last_colon = false;
        if t.kind(i) == TokenKind::Operator {
            match t.text(i).as_str() {
                "(" | "[" | "{" => depth += 1,
                ")" | "]" | "}" => depth -= 1,
                ":" => last_colon = depth <= 0,
                _ => {}
            }
        } else if t.kind(i) == TokenKind::String && t.has(i, UNTERMINATED) {
            return false;
        }
    }
    last_colon
}
