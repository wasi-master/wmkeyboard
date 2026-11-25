//! The names a scope binds. Most scopes are a function call's: a few
//! parameters and locals, made and dropped once per call. Those live in a
//! short list, one allocation, searched by address first (the lexer gives
//! each identifier one shared string) and then by text. Past [SMALL] names,
//! as a module's globals soon are, the list becomes a hash map.

use std::rc::Rc;

use super::fxhash::{name_map, NameMap};
use super::value::Value;

/// The most names kept as a list.
const SMALL: usize = 12;

pub(crate) enum Scope {
    Small(Vec<(Rc<str>, Value)>),
    Large(NameMap),
}

#[inline]
fn same(k: &str, name: &str) -> bool {
    (k.as_ptr() == name.as_ptr() && k.len() == name.len()) || k == name
}

impl Scope {
    pub fn with_capacity(n: usize) -> Scope {
        if n <= SMALL {
            Scope::Small(Vec::with_capacity(n))
        } else {
            Scope::Large(name_map(n))
        }
    }

    #[inline]
    pub fn get(&self, name: &str) -> Option<&Value> {
        match self {
            Scope::Small(v) => v.iter().find(|(k, _)| same(k, name)).map(|(_, v)| v),
            Scope::Large(m) => m.get(name),
        }
    }

    pub fn contains_key(&self, name: &str) -> bool {
        self.get(name).is_some()
    }

    /// Bind [name], keeping its place when it was bound already.
    #[inline]
    pub fn insert(&mut self, name: Rc<str>, value: Value) {
        match self {
            Scope::Small(v) => {
                if let Some(slot) = v.iter_mut().find(|(k, _)| same(k, &name)) {
                    slot.1 = value;
                } else if v.len() < SMALL {
                    v.push((name, value));
                } else {
                    let mut m = name_map(SMALL * 2);
                    m.extend(std::mem::take(v));
                    m.insert(name, value);
                    *self = Scope::Large(m);
                }
            }
            Scope::Large(m) => {
                m.insert(name, value);
            }
        }
    }

    /// Unbind [name], keeping the others in order.
    pub fn shift_remove(&mut self, name: &str) -> Option<Value> {
        match self {
            Scope::Small(v) => {
                let at = v.iter().position(|(k, _)| same(k, name))?;
                Some(v.remove(at).1)
            }
            Scope::Large(m) => m.shift_remove(name),
        }
    }

    pub fn len(&self) -> usize {
        match self {
            Scope::Small(v) => v.len(),
            Scope::Large(m) => m.len(),
        }
    }

    pub fn clear(&mut self) {
        match self {
            Scope::Small(v) => v.clear(),
            Scope::Large(m) => m.clear(),
        }
    }

    /// Every binding, in order, leaving the scope empty.
    pub fn take_all(&mut self) -> Vec<(Rc<str>, Value)> {
        match self {
            Scope::Small(v) => std::mem::take(v),
            Scope::Large(m) => m.drain(..).collect(),
        }
    }

    /// Every binding, in order.
    pub fn pairs(&self) -> Vec<(Rc<str>, Value)> {
        match self {
            Scope::Small(v) => v.clone(),
            Scope::Large(m) => m.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
        }
    }
}
