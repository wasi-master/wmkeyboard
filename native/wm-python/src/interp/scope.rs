//! The names a module or a class body binds, looked up by name (a
//! function's variables live in slots instead; see `resolve.rs`). A short
//! list, searched by address first (the lexer gives each identifier one
//! shared string) and then by text; past [SMALL] names, as a module's
//! globals soon are, a hash map. A name in the code carries a hint of where
//! it was found last, which a lookup tries before searching.

use std::cell::Cell;
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

    /// [get], trying the position [hint] remembers first and remembering
    /// where the name was found.
    #[inline]
    pub fn get_hinted(&self, name: &str, hint: &Cell<u32>) -> Option<&Value> {
        let at = hint.get() as usize;
        match self {
            Scope::Small(v) => {
                if let Some((k, value)) = v.get(at) {
                    if same(k, name) {
                        return Some(value);
                    }
                }
                let i = v.iter().position(|(k, _)| same(k, name))?;
                hint.set(i as u32);
                Some(&v[i].1)
            }
            Scope::Large(m) => {
                if let Some((k, value)) = m.get_index(at) {
                    if same(k, name) {
                        return Some(value);
                    }
                }
                let (i, _, value) = m.get_full(name)?;
                hint.set(i as u32);
                Some(value)
            }
        }
    }

    /// [insert], through the position [hint] remembers when it still holds the name.
    #[inline]
    pub fn insert_hinted(&mut self, name: &Rc<str>, value: Value, hint: &Cell<u32>) -> Option<Value> {
        let at = hint.get() as usize;
        let slot = match self {
            Scope::Small(v) => v.get_mut(at).filter(|(k, _)| same(k, name)).map(|(_, v)| v),
            Scope::Large(m) => m.get_index_mut(at).filter(|(k, _)| same(k, name)).map(|(_, v)| v),
        };
        if let Some(slot) = slot {
            return Some(std::mem::replace(slot, value));
        }
        self.insert(name.clone(), value);
        None
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
