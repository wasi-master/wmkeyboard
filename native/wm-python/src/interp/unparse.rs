//! An expression back as Python source, as `ast.unparse` writes it: what an
//! annotation reads as under `from __future__ import annotations`, and the
//! text kept for one that fails to evaluate. Covers what annotations are
//! made of; a lambda, comprehension or f-string comes out as `...`.

use super::ast::*;
use super::value::{py_repr, Value};

/// How tightly an operator binds, as CPython's ast._Precedence orders them.
fn precedence(e: &Expr) -> u8 {
    match e {
        Expr::Ternary(_) => 1,
        Expr::BoolOp(BoolKind::Or, ..) => 2,
        Expr::BoolOp(BoolKind::And, ..) => 3,
        Expr::Unary(UnOp::Not, _) => 4,
        Expr::Compare(..) => 5,
        Expr::Binary(op, ..) => match op {
            BinOp::BitOr => 6,
            BinOp::BitXor => 7,
            BinOp::BitAnd => 8,
            BinOp::LShift | BinOp::RShift => 9,
            BinOp::Add | BinOp::Sub => 10,
            BinOp::Mul | BinOp::Div | BinOp::FloorDiv | BinOp::Mod | BinOp::MatMul => 11,
            BinOp::Pow => 13,
        },
        Expr::Unary(..) => 12,
        _ => 14,
    }
}

fn binop(op: BinOp) -> &'static str {
    match op {
        BinOp::Add => "+",
        BinOp::Sub => "-",
        BinOp::Mul => "*",
        BinOp::Div => "/",
        BinOp::FloorDiv => "//",
        BinOp::Mod => "%",
        BinOp::Pow => "**",
        BinOp::BitAnd => "&",
        BinOp::BitOr => "|",
        BinOp::BitXor => "^",
        BinOp::LShift => "<<",
        BinOp::RShift => ">>",
        BinOp::MatMul => "@",
    }
}

fn cmpop(op: CmpOp) -> &'static str {
    match op {
        CmpOp::Eq => "==",
        CmpOp::Ne => "!=",
        CmpOp::Lt => "<",
        CmpOp::Le => "<=",
        CmpOp::Gt => ">",
        CmpOp::Ge => ">=",
        CmpOp::In => "in",
        CmpOp::NotIn => "not in",
        CmpOp::Is => "is",
        CmpOp::IsNot => "is not",
    }
}

/// [e] inside an operator of precedence [outer]: bracketed when it binds less tightly.
fn operand(e: &Expr, outer: u8, out: &mut String) {
    if precedence(e) < outer {
        out.push('(');
        write(e, out);
        out.push(')');
    } else {
        write(e, out);
    }
}

fn items(list: &[Expr], out: &mut String) {
    for (i, x) in list.iter().enumerate() {
        if i > 0 {
            out.push_str(", ");
        }
        write(x, out);
    }
}

fn write(e: &Expr, out: &mut String) {
    match e {
        Expr::None => out.push_str("None"),
        Expr::Bool(b) => out.push_str(if *b { "True" } else { "False" }),
        Expr::Int(n) => out.push_str(&n.to_string()),
        Expr::Big(n) => out.push_str(&n.to_string()),
        Expr::Float(f) => out.push_str(&py_repr(&Value::Float(*f))),
        Expr::Imag(f) => {
            out.push_str(&py_repr(&Value::Float(*f)));
            out.push('j');
        }
        Expr::Str(s) => out.push_str(&py_repr(&Value::Str(s.clone()))),
        Expr::Bytes(b) => out.push_str(&py_repr(&Value::bytes(b))),
        // `...` reads as the name Ellipsis; written back, it is `...`.
        Expr::Name(n) if n.id.as_ref() == "Ellipsis" => out.push_str("..."),
        Expr::Name(n) => out.push_str(&n.id),
        Expr::Attribute(x, name) => {
            operand(x, 14, out);
            out.push('.');
            out.push_str(name);
        }
        Expr::Index(x, key) => {
            operand(x, 14, out);
            out.push('[');
            match key.as_ref() {
                Expr::Tuple(parts) if !parts.is_empty() => items(parts, out),
                other => write(other, out),
            }
            out.push(']');
        }
        Expr::Slice(parts) => {
            let (base, lower, upper, step) = parts.as_ref();
            operand(base, 14, out);
            out.push('[');
            if let Some(l) = lower {
                write(l, out);
            }
            out.push(':');
            if let Some(u) = upper {
                write(u, out);
            }
            if let Some(s) = step {
                out.push(':');
                write(s, out);
            }
            out.push(']');
        }
        Expr::Tuple(parts) => {
            out.push('(');
            items(parts, out);
            if parts.len() == 1 {
                out.push(',');
            }
            out.push(')');
        }
        Expr::List(parts) => {
            out.push('[');
            items(parts, out);
            out.push(']');
        }
        Expr::Set(parts) => {
            out.push('{');
            items(parts, out);
            out.push('}');
        }
        Expr::Dict(pairs) => {
            out.push('{');
            for (i, (k, v)) in pairs.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                if let Expr::Starred(m) = k {
                    out.push_str("**");
                    write(m, out);
                } else {
                    write(k, out);
                    out.push_str(": ");
                    write(v, out);
                }
            }
            out.push('}');
        }
        Expr::Starred(x) => {
            out.push('*');
            operand(x, 10, out);
        }
        Expr::Unary(op, x) => {
            out.push_str(match op {
                UnOp::Neg => "-",
                UnOp::Pos => "+",
                UnOp::Not => "not ",
                UnOp::Invert => "~",
            });
            operand(x, precedence(e), out);
        }
        Expr::Binary(op, a, b) => {
            let p = precedence(e);
            // `**` groups to the right, the others to the left.
            let (left, right) = if *op == BinOp::Pow { (p + 1, p) } else { (p, p + 1) };
            operand(a, left, out);
            out.push(' ');
            out.push_str(binop(*op));
            out.push(' ');
            operand(b, right, out);
        }
        Expr::BoolOp(kind, a, b) => {
            let p = precedence(e);
            operand(a, p, out);
            out.push_str(if *kind == BoolKind::And { " and " } else { " or " });
            operand(b, p + 1, out);
        }
        Expr::Compare(first, rest) => {
            operand(first, 6, out);
            for (op, x) in rest {
                out.push(' ');
                out.push_str(cmpop(*op));
                out.push(' ');
                operand(x, 6, out);
            }
        }
        Expr::Ternary(parts) => {
            let (test, yes, no) = parts.as_ref();
            operand(yes, 2, out);
            out.push_str(" if ");
            operand(test, 2, out);
            out.push_str(" else ");
            operand(no, 1, out);
        }
        Expr::Call(f, args, kwargs) => {
            operand(f, 14, out);
            out.push('(');
            items(args, out);
            for (i, (k, v)) in kwargs.iter().enumerate() {
                if i > 0 || !args.is_empty() {
                    out.push_str(", ");
                }
                if k.as_ref() == "**" {
                    out.push_str("**");
                } else {
                    out.push_str(k);
                    out.push('=');
                }
                write(v, out);
            }
            out.push(')');
        }
        Expr::Await(x) => {
            out.push_str("await ");
            operand(x, 12, out);
        }
        Expr::Walrus(pair) => {
            let (name, value) = pair.as_ref();
            out.push('(');
            out.push_str(&name.id);
            out.push_str(" := ");
            write(value, out);
            out.push(')');
        }
        Expr::Invalid(_, text) => out.push_str(text),
        Expr::FString(_) | Expr::Lambda(_) | Expr::Comprehension(_) | Expr::Yield(_) | Expr::YieldFrom(_) => out.push_str("..."),
    }
}

/// [e] as `ast.unparse` gives it.
pub(crate) fn unparse(e: &Expr) -> String {
    let mut out = String::new();
    write(e, &mut out);
    out
}

/// Whether [e] can be evaluated where it stands without the resolver having
/// seen it: names, attributes, subscripts and constants, nothing that makes
/// a scope or binds a name. Function annotations are kept only when so.
pub(crate) fn is_plain(e: &Expr) -> bool {
    match e {
        Expr::None | Expr::Bool(_) | Expr::Int(_) | Expr::Big(_) | Expr::Float(_) | Expr::Imag(_) | Expr::Str(_) | Expr::Bytes(_) | Expr::Name(_) => true,
        Expr::Attribute(x, _) | Expr::Starred(x) | Expr::Unary(_, x) => is_plain(x),
        Expr::Index(a, b) | Expr::Binary(_, a, b) | Expr::BoolOp(_, a, b) => is_plain(a) && is_plain(b),
        Expr::Tuple(xs) | Expr::List(xs) | Expr::Set(xs) => xs.iter().all(is_plain),
        Expr::Dict(pairs) => pairs.iter().all(|(k, v)| is_plain(k) && is_plain(v)),
        Expr::Slice(parts) => {
            let (base, a, b, c) = parts.as_ref();
            is_plain(base) && [a, b, c].iter().all(|x| x.as_ref().is_none_or(is_plain))
        }
        Expr::Compare(first, rest) => is_plain(first) && rest.iter().all(|(_, x)| is_plain(x)),
        Expr::Ternary(parts) => is_plain(&parts.0) && is_plain(&parts.1) && is_plain(&parts.2),
        Expr::Call(f, args, kwargs) => is_plain(f) && args.iter().all(is_plain) && kwargs.iter().all(|(_, v)| is_plain(v)),
        _ => false,
    }
}
