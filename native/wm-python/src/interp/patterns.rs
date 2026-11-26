//! `match`: a value against `case` patterns, as Python 3.10 has them, with
//! each pattern binding the names it captures.

use std::rc::Rc;

use super::ast::*;
use super::error::{Exc, R};
use super::eval::{Env, Interp};
use super::value::*;

/// The builtin types whose class pattern takes one positional sub-pattern
/// and matches it against the value itself: `case int(n):`.
const SELF_MATCHING: &[&str] = &["bool", "bytes", "dict", "float", "int", "list", "set", "str", "tuple"];

impl Interp {
    #[inline(never)]
    pub(crate) fn exec_match(&mut self, m: &MatchStmt, env: &Rc<Env>) -> R<()> {
        let subject = self.eval(&m.subject, env)?;
        for case in &m.cases {
            if self.case_matches(case, &subject, env)? {
                return self.exec_block(&case.body, env);
            }
        }
        Ok(())
    }

    /// Whether [case] takes [subject]: its pattern matches and its guard holds.
    pub(crate) fn case_matches(&mut self, case: &MatchCase, subject: &Value, env: &Rc<Env>) -> R<bool> {
        self.tick()?;
        if !self.pattern_matches(&case.pattern, subject, env)? {
            return Ok(false);
        }
        match &case.guard {
            Some(g) => {
                let v = self.eval(g, env)?;
                self.truth(&v)
            }
            None => Ok(true),
        }
    }

    fn pattern_matches(&mut self, p: &Pattern, v: &Value, env: &Rc<Env>) -> R<bool> {
        match p {
            Pattern::Wildcard => Ok(true),
            Pattern::Capture(n) => {
                self.store_name(n, v.clone(), env);
                Ok(true)
            }
            Pattern::Value(e) => {
                let want = self.eval(e, env)?;
                Ok(match e {
                    // None, True and False match by identity.
                    Expr::None | Expr::Bool(_) => py_identical(v, &want),
                    _ => self.eq(v, &want)?,
                })
            }
            Pattern::Sequence(items, star) => {
                // A list, tuple or range; never a string, bytes or a dict.
                let seq = match v {
                    Value::List(l) => l.borrow().clone(),
                    Value::Tuple(t) => t.as_ref().clone(),
                    Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) => self.iterate(v)?,
                    _ => return Ok(false),
                };
                let Some((at, name)) = star else {
                    if seq.len() != items.len() {
                        return Ok(false);
                    }
                    for (p, x) in items.iter().zip(&seq) {
                        if !self.pattern_matches(p, x, env)? {
                            return Ok(false);
                        }
                    }
                    return Ok(true);
                };
                if seq.len() < items.len() {
                    return Ok(false);
                }
                let after = items.len() - at;
                for (p, x) in items[..*at].iter().zip(&seq) {
                    if !self.pattern_matches(p, x, env)? {
                        return Ok(false);
                    }
                }
                for (p, x) in items[*at..].iter().zip(&seq[seq.len() - after..]) {
                    if !self.pattern_matches(p, x, env)? {
                        return Ok(false);
                    }
                }
                if let Some(n) = name {
                    self.store_name(n, Value::list(seq[*at..seq.len() - after].to_vec()), env);
                }
                Ok(true)
            }
            Pattern::Mapping(pairs, rest) => {
                let Value::Dict(d) = v else { return Ok(false) };
                let mut used = Vec::with_capacity(pairs.len());
                for (k, p) in pairs {
                    let key = self.eval(k, env)?;
                    let hk = self.hashable(&key)?;
                    let item = d.borrow().get(&hk).cloned();
                    let Some(item) = item else { return Ok(false) };
                    if !self.pattern_matches(p, &item, env)? {
                        return Ok(false);
                    }
                    used.push(hk);
                }
                if let Some(n) = rest {
                    let left: DictMap =
                        d.borrow().iter().filter(|(k, _)| !used.contains(k)).map(|(k, v)| (k.clone(), v.clone())).collect();
                    self.store_name(n, Value::new_dict(left), env);
                }
                Ok(true)
            }
            Pattern::Class(cls, positional, keywords) => {
                let c = self.eval(cls, env)?;
                if !matches!(c, Value::Class(_) | Value::Builtin(_)) {
                    return Err(self.error("called match pattern must be a class", "TypeError"));
                }
                if !self.is_instance(v, &c) {
                    return Ok(false);
                }
                if !positional.is_empty() {
                    match &c {
                        Value::Builtin(name) if SELF_MATCHING.contains(&name.as_ref()) => {
                            if positional.len() > 1 {
                                return Err(self.error(
                                    format!("{name}() accepts 1 positional sub-pattern ({} given)", positional.len()),
                                    "TypeError",
                                ));
                            }
                            if !self.pattern_matches(&positional[0], v, env)? {
                                return Ok(false);
                            }
                        }
                        Value::Class(k) => {
                            let names: Vec<Value> = match k.lookup("__match_args__") {
                                Some(Value::Tuple(t)) => t.as_ref().clone(),
                                Some(Value::List(l)) => l.borrow().clone(),
                                _ => Vec::new(),
                            };
                            if positional.len() > names.len() {
                                let accepts = match names.len() {
                                    1 => "1 positional sub-pattern".to_string(),
                                    n => format!("{n} positional sub-patterns"),
                                };
                                return Err(self.error(format!("{}() accepts {accepts} ({} given)", k.name, positional.len()), "TypeError"));
                            }
                            for (p, name) in positional.iter().zip(&names) {
                                let Value::Str(attr) = name else {
                                    return Err(self.error("__match_args__ elements must be strings", "TypeError"));
                                };
                                let Some(x) = self.match_attribute(v, attr)? else { return Ok(false) };
                                if !self.pattern_matches(p, &x, env)? {
                                    return Ok(false);
                                }
                            }
                        }
                        _ => return Err(self.error("this class takes no positional sub-patterns", "TypeError")),
                    }
                }
                for (name, p) in keywords {
                    let Some(x) = self.match_attribute(v, name)? else { return Ok(false) };
                    if !self.pattern_matches(p, &x, env)? {
                        return Ok(false);
                    }
                }
                Ok(true)
            }
            Pattern::Or(alts) => {
                for a in alts {
                    if self.pattern_matches(a, v, env)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            Pattern::As(inner, n) => {
                if !self.pattern_matches(inner, v, env)? {
                    return Ok(false);
                }
                self.store_name(n, v.clone(), env);
                Ok(true)
            }
        }
    }

    /// `v.name` for a class pattern; None when there is no such attribute.
    fn match_attribute(&mut self, v: &Value, name: &str) -> R<Option<Value>> {
        match self.attribute(v, name) {
            Ok(x) => Ok(Some(x)),
            Err(Exc::Error(e)) if !e.is_abort() && e.kind == "AttributeError" => Ok(None),
            Err(e) => Err(e),
        }
    }
}
