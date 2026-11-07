//! Port of PyValue.kt: the runtime value model.
//!
//! Lists, dicts and sets are shared references so Python's mutation semantics
//! work; tuples are immutable and hash, so they can key a dict. A runtime and
//! its values belong to one thread at a time (`Rc`), which is why the plugin
//! [`crate::host::Session`] hands out JSON and never a `Value`.
//!
//! Strings follow PyMini: `len`, indexing, slicing and iteration count extended
//! grapheme clusters, and `==`/`<` use canonical equivalence (NFC). Ints are
//! 64-bit, but arithmetic runs through `f64` as PyMini's does, so a result past
//! 2^53 comes back as a float (`10**20` prints `1e+20`).

use std::cell::{Cell, RefCell};
use std::fmt;
use std::hash::{Hash, Hasher};
use std::rc::Rc;

use indexmap::{IndexMap, IndexSet};

use super::ast::{FuncDef, LambdaDef, Param};
use super::eval::Env;
use super::host::HostFunction;
use super::regex::Match;
use super::text::{c_fixed, c_general, str_eq, str_hash};

pub type ListRef = Rc<RefCell<Vec<Value>>>;
pub type DictRef = Rc<RefCell<IndexMap<HKey, Value>>>;
pub type SetRef = Rc<RefCell<IndexSet<HKey>>>;

/// A value inside a script.
#[derive(Clone)]
pub enum Value {
    None,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(Rc<str>),
    List(ListRef),
    /// Immutable and hashable: `(1, 2)`, the pairs `enumerate` hands back, dict keys.
    Tuple(Rc<Vec<Value>>),
    Set(SetRef),
    Dict(DictRef),
    /// A `def` or a `lambda`.
    Function(Rc<Function>),
    /// A named builtin like `len`.
    Builtin(Rc<str>),
    /// A namespace bound by `import`, or a host namespace like `wm.storage`.
    Module(Rc<str>),
    /// A namespace member referenced without calling it (`f = math.sqrt`).
    ModuleFunction(Rc<(Rc<str>, Rc<str>)>),
    /// A Rust function the host handed to the runtime.
    HostFunction(Rc<HostFunction>),
    /// A raised (or caught) exception.
    Exception(Rc<ExceptionValue>),
    /// A successful `re` match.
    Match(Rc<Match>),
    /// An immutable byte string (an addition to PyMini).
    Bytes(Rc<[u8]>),
}

/// A raised exception: there are no classes, so it is a type name and a message.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ExceptionValue {
    pub kind: String,
    pub message: String,
}

/// What a function value runs.
pub(crate) enum FnBody {
    Def(Rc<FuncDef>),
    /// A lambda remembers the line it was created on, which its body reports.
    Lambda(Rc<LambdaDef>, u32),
}

/// A function defined in a script, with the scope it closed over.
pub struct Function {
    pub(crate) name: Rc<str>,
    pub(crate) body: FnBody,
    pub(crate) closure: Rc<Env>,
}

impl Function {
    pub(crate) fn params(&self) -> &[Param] {
        match &self.body {
            FnBody::Def(d) => &d.params,
            FnBody::Lambda(l, _) => &l.params,
        }
    }

    pub(crate) fn variadic(&self) -> Option<&Rc<str>> {
        match &self.body {
            FnBody::Def(d) => d.variadic.as_ref(),
            FnBody::Lambda(l, _) => l.variadic.as_ref(),
        }
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    /// Parameter names in declaration order, not counting `*rest`.
    pub fn parameter_names(&self) -> Vec<String> {
        self.params().iter().map(|p| p.name.to_string()).collect()
    }

    /// Whether it takes `*rest`.
    pub fn is_variadic(&self) -> bool {
        self.variadic().is_some()
    }
}

/// The values that can be dict keys and set members. Like Swift's synthesized
/// Hashable, `1`, `1.0` and `True` are three different keys.
#[derive(Clone)]
pub enum HKey {
    None,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(Rc<str>),
    Tuple(Rc<Vec<HKey>>),
    Bytes(Rc<[u8]>),
}

impl PartialEq for HKey {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (HKey::None, HKey::None) => true,
            (HKey::Bool(a), HKey::Bool(b)) => a == b,
            (HKey::Int(a), HKey::Int(b)) => a == b,
            (HKey::Float(a), HKey::Float(b)) => a == b,
            (HKey::Str(a), HKey::Str(b)) => str_eq(a, b),
            (HKey::Tuple(a), HKey::Tuple(b)) => a == b,
            (HKey::Bytes(a), HKey::Bytes(b)) => a == b,
            _ => false,
        }
    }
}

impl Eq for HKey {}

impl Hash for HKey {
    fn hash<H: Hasher>(&self, h: &mut H) {
        match self {
            HKey::None => 0u8.hash(h),
            HKey::Bool(b) => {
                1u8.hash(h);
                b.hash(h)
            }
            HKey::Int(i) => {
                2u8.hash(h);
                i.hash(h)
            }
            HKey::Float(f) => {
                3u8.hash(h);
                if *f == 0.0 { 0u64 } else { f.to_bits() }.hash(h)
            }
            HKey::Str(s) => {
                4u8.hash(h);
                str_hash(s, h)
            }
            HKey::Tuple(t) => {
                5u8.hash(h);
                t.len().hash(h);
                for k in t.iter() {
                    k.hash(h);
                }
            }
            HKey::Bytes(b) => {
                6u8.hash(h);
                b.hash(h)
            }
        }
    }
}

impl HKey {
    /// The hashable form of [v], or None for lists, dicts, sets and functions.
    pub fn from_value(v: &Value) -> Option<HKey> {
        Some(match v {
            Value::None => HKey::None,
            Value::Bool(b) => HKey::Bool(*b),
            Value::Int(i) => HKey::Int(*i),
            Value::Float(f) => HKey::Float(*f),
            Value::Str(s) => HKey::Str(s.clone()),
            Value::Bytes(b) => HKey::Bytes(b.clone()),
            Value::Tuple(items) => {
                let mut parts = Vec::with_capacity(items.len());
                for item in items.iter() {
                    parts.push(HKey::from_value(item)?);
                }
                HKey::Tuple(Rc::new(parts))
            }
            _ => return None,
        })
    }

    pub fn to_value(&self) -> Value {
        match self {
            HKey::None => Value::None,
            HKey::Bool(b) => Value::Bool(*b),
            HKey::Int(i) => Value::Int(*i),
            HKey::Float(f) => Value::Float(*f),
            HKey::Str(s) => Value::Str(s.clone()),
            HKey::Bytes(b) => Value::Bytes(b.clone()),
            HKey::Tuple(t) => Value::Tuple(Rc::new(t.iter().map(HKey::to_value).collect())),
        }
    }

    pub fn str(s: &str) -> HKey {
        HKey::Str(Rc::from(s))
    }
}

// MARK: - Constructors and readers

impl Value {
    pub fn str(s: impl AsRef<str>) -> Value {
        Value::Str(Rc::from(s.as_ref()))
    }

    pub fn list(items: Vec<Value>) -> Value {
        Value::List(Rc::new(RefCell::new(items)))
    }

    pub fn tuple(items: Vec<Value>) -> Value {
        Value::Tuple(Rc::new(items))
    }

    pub fn bytes(b: &[u8]) -> Value {
        Value::Bytes(Rc::from(b))
    }

    pub fn new_dict(map: IndexMap<HKey, Value>) -> Value {
        Value::Dict(Rc::new(RefCell::new(map)))
    }

    /// A dict with string keys, in the given order.
    pub fn dict<K: AsRef<str>>(pairs: Vec<(K, Value)>) -> Value {
        let mut m = IndexMap::new();
        for (k, v) in pairs {
            m.insert(HKey::str(k.as_ref()), v);
        }
        Value::new_dict(m)
    }

    pub fn new_set(set: IndexSet<HKey>) -> Value {
        Value::Set(Rc::new(RefCell::new(set)))
    }

    /// The text of a str.
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::Str(s) => Some(s),
            _ => None,
        }
    }

    /// An int, or a bool as 1/0. Floats are None.
    pub fn as_int(&self) -> Option<i64> {
        match self {
            Value::Bool(b) => Some(*b as i64),
            Value::Int(i) => Some(*i),
            _ => None,
        }
    }

    /// An int or float (or bool) as f64.
    pub fn as_float(&self) -> Option<f64> {
        match self {
            Value::Bool(b) => Some(if *b { 1.0 } else { 0.0 }),
            Value::Int(i) => Some(*i as f64),
            Value::Float(f) => Some(*f),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn is_none(&self) -> bool {
        matches!(self, Value::None)
    }

    /// The items of a list or tuple.
    pub fn as_items(&self) -> Option<Vec<Value>> {
        match self {
            Value::List(l) => Some(l.borrow().clone()),
            Value::Tuple(t) => Some(t.as_ref().clone()),
            _ => None,
        }
    }

    /// A dict's value under a string key.
    pub fn get_key(&self, key: &str) -> Option<Value> {
        match self {
            Value::Dict(d) => d.borrow().get(&HKey::str(key)).cloned(),
            _ => None,
        }
    }

    /// The Python type name, as `type()` and error messages say it.
    pub fn type_name(&self) -> String {
        match self {
            Value::Exception(e) => e.kind.clone(),
            other => other.static_type_name().to_string(),
        }
    }

    pub(crate) fn static_type_name(&self) -> &'static str {
        match self {
            Value::None => "NoneType",
            Value::Bool(_) => "bool",
            Value::Int(_) => "int",
            Value::Float(_) => "float",
            Value::Str(_) => "str",
            Value::List(_) => "list",
            Value::Tuple(_) => "tuple",
            Value::Set(_) => "set",
            Value::Dict(_) => "dict",
            Value::Function(_) | Value::Builtin(_) | Value::ModuleFunction(_) | Value::HostFunction(_) => "function",
            Value::Module(_) => "module",
            Value::Exception(_) => "Exception",
            Value::Match(_) => "Match",
            Value::Bytes(_) => "bytes",
        }
    }

    pub fn truthy(&self) -> bool {
        match self {
            Value::None => false,
            Value::Bool(b) => *b,
            Value::Int(i) => *i != 0,
            Value::Float(f) => *f != 0.0,
            Value::Str(s) => !s.is_empty(),
            Value::List(l) => !l.borrow().is_empty(),
            Value::Tuple(t) => !t.is_empty(),
            Value::Set(s) => !s.borrow().is_empty(),
            Value::Dict(d) => !d.borrow().is_empty(),
            Value::Bytes(b) => !b.is_empty(),
            _ => true,
        }
    }

    /// Python `str()`.
    pub fn to_str(&self) -> String {
        py_str(self)
    }

    /// Python `repr()`.
    pub fn repr(&self) -> String {
        py_repr(self)
    }
}

impl fmt::Debug for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&py_repr(self))
    }
}

impl fmt::Display for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&py_str(self))
    }
}

/// Python `==`, so `Int(1) == Float(1.0)`.
impl PartialEq for Value {
    fn eq(&self, other: &Self) -> bool {
        py_equals(self, other)
    }
}

impl From<&str> for Value {
    fn from(s: &str) -> Value {
        Value::str(s)
    }
}
impl From<String> for Value {
    fn from(s: String) -> Value {
        Value::Str(Rc::from(s))
    }
}
impl From<i64> for Value {
    fn from(i: i64) -> Value {
        Value::Int(i)
    }
}
impl From<i32> for Value {
    fn from(i: i32) -> Value {
        Value::Int(i as i64)
    }
}
impl From<usize> for Value {
    fn from(i: usize) -> Value {
        Value::Int(i as i64)
    }
}
impl From<f64> for Value {
    fn from(f: f64) -> Value {
        Value::Float(f)
    }
}
impl From<bool> for Value {
    fn from(b: bool) -> Value {
        Value::Bool(b)
    }
}
impl From<()> for Value {
    fn from(_: ()) -> Value {
        Value::None
    }
}
impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(o: Option<T>) -> Value {
        o.map(Into::into).unwrap_or(Value::None)
    }
}
impl<T: Into<Value>> From<Vec<T>> for Value {
    fn from(v: Vec<T>) -> Value {
        Value::list(v.into_iter().map(Into::into).collect())
    }
}
/// A map with string keys becomes a dict with sorted keys, so the same map
/// always builds the same dict.
impl<V: Into<Value>> From<std::collections::BTreeMap<String, V>> for Value {
    fn from(m: std::collections::BTreeMap<String, V>) -> Value {
        let mut pairs: Vec<(String, Value)> = m.into_iter().map(|(k, v)| (k, v.into())).collect();
        pairs.sort_by(|a, b| super::text::str_compare(&a.0, &b.0));
        Value::dict(pairs)
    }
}
impl<V: Into<Value>> From<std::collections::HashMap<String, V>> for Value {
    fn from(m: std::collections::HashMap<String, V>) -> Value {
        let mut pairs: Vec<(String, Value)> = m.into_iter().map(|(k, v)| (k, v.into())).collect();
        pairs.sort_by(|a, b| super::text::str_compare(&a.0, &b.0));
        Value::dict(pairs)
    }
}

// MARK: - str, repr, equality

/// Nesting past which repr and equality give up: a list that holds itself
/// would otherwise recurse until the native stack ran out.
const MAX_NESTING: u32 = 200;

thread_local! {
    static DEPTH: Cell<u32> = const { Cell::new(0) };
}

fn nested<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    let d = DEPTH.with(|c| c.get());
    if d >= MAX_NESTING {
        return fallback;
    }
    DEPTH.with(|c| c.set(d + 1));
    let r = f();
    DEPTH.with(|c| c.set(d));
    r
}

/// `str(value)`
pub(crate) fn py_str(v: &Value) -> String {
    match v {
        Value::None => "None".into(),
        Value::Bool(b) => if *b { "True" } else { "False" }.into(),
        Value::Int(i) => i.to_string(),
        Value::Float(f) => format_double(*f),
        Value::Str(s) => s.to_string(),
        Value::List(_) | Value::Dict(_) | Value::Tuple(_) | Value::Set(_) | Value::Bytes(_) => py_repr(v),
        Value::Function(f) => format!("<function {}>", f.name),
        Value::Builtin(n) => format!("<builtin {n}>"),
        Value::HostFunction(f) => format!("<builtin {}>", f.name),
        Value::Module(n) => format!("<module '{n}'>"),
        Value::ModuleFunction(mf) => format!("<builtin {}.{}>", mf.0, mf.1),
        Value::Exception(e) => e.message.clone(),
        Value::Match(m) => format!("<Match {}>", str_repr(&m.value())),
    }
}

pub(crate) fn str_repr(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('\'');
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\'' => out.push_str("\\'"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            c => out.push(c),
        }
    }
    out.push('\'');
    out
}

pub(crate) fn bytes_repr(b: &[u8]) -> String {
    let quote = if b.contains(&b'\'') && !b.contains(&b'"') { b'"' } else { b'\'' };
    let mut out = String::with_capacity(b.len() + 3);
    out.push('b');
    out.push(quote as char);
    for &x in b {
        match x {
            b'\\' => out.push_str("\\\\"),
            b'\t' => out.push_str("\\t"),
            b'\n' => out.push_str("\\n"),
            b'\r' => out.push_str("\\r"),
            _ if x == quote => {
                out.push('\\');
                out.push(x as char);
            }
            0x20..=0x7e => out.push(x as char),
            _ => out.push_str(&format!("\\x{x:02x}")),
        }
    }
    out.push(quote as char);
    out
}

/// `repr(value)`: strings get quotes, containers repr their items.
pub(crate) fn py_repr(v: &Value) -> String {
    match v {
        Value::Str(s) => str_repr(s),
        Value::Bytes(b) => bytes_repr(b),
        Value::List(l) => nested("[...]".into(), || {
            let items = l.borrow().clone();
            format!("[{}]", items.iter().map(py_repr).collect::<Vec<_>>().join(", "))
        }),
        Value::Tuple(t) => nested("(...)".into(), || {
            // Keep Python's trailing comma on a one-item tuple so the repr round-trips.
            if t.len() == 1 {
                format!("({},)", py_repr(&t[0]))
            } else {
                format!("({})", t.iter().map(py_repr).collect::<Vec<_>>().join(", "))
            }
        }),
        Value::Set(s) => {
            let s = s.borrow();
            if s.is_empty() {
                "set()".into()
            } else {
                format!("{{{}}}", s.iter().map(|k| py_repr(&k.to_value())).collect::<Vec<_>>().join(", "))
            }
        }
        Value::Dict(d) => nested("{...}".into(), || {
            let pairs: Vec<(HKey, Value)> = d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
            format!(
                "{{{}}}",
                pairs.iter().map(|(k, v)| format!("{}: {}", py_repr(&k.to_value()), py_repr(v))).collect::<Vec<_>>().join(", ")
            )
        }),
        Value::Exception(e) => format!("{}({})", e.kind, str_repr(&e.message)),
        other => py_str(other),
    }
}

/// Print an f64 the way Python does: whole numbers keep their `.0`, and
/// everything else gets the shortest `%g` form that reads back as the same value.
pub(crate) fn format_double(d: f64) -> String {
    if d.is_nan() {
        return "nan".into();
    }
    if d.is_infinite() {
        return if d < 0.0 { "-inf".into() } else { "inf".into() };
    }
    if d == d.round_ties_even() && d.abs() < 1e16 {
        return c_fixed(d, 1);
    }
    for p in 1..=17 {
        let s = c_general(d, p);
        if s.parse::<f64>().ok() == Some(d) {
            return s;
        }
    }
    c_general(d, 17)
}

/// Python `==`. Numbers compare across types, so 1 == 1.0 == True.
pub(crate) fn py_equals(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::None, Value::None) => true,
        (Value::Str(x), Value::Str(y)) => str_eq(x, y),
        (Value::Bytes(x), Value::Bytes(y)) => x == y,
        (Value::List(x), Value::List(y)) => {
            if Rc::ptr_eq(x, y) {
                return true;
            }
            let (x, y) = (x.borrow().clone(), y.borrow().clone());
            x.len() == y.len() && nested(false, || x.iter().zip(y.iter()).all(|(p, q)| py_equals(p, q)))
        }
        (Value::Tuple(x), Value::Tuple(y)) => {
            x.len() == y.len() && nested(false, || x.iter().zip(y.iter()).all(|(p, q)| py_equals(p, q)))
        }
        (Value::Set(x), Value::Set(y)) => {
            let (x, y) = (x.borrow(), y.borrow());
            x.len() == y.len() && x.iter().all(|k| y.contains(k))
        }
        (Value::Dict(x), Value::Dict(y)) => {
            if Rc::ptr_eq(x, y) {
                return true;
            }
            let (x, y) = (x.borrow().clone(), y.borrow().clone());
            if x.len() != y.len() {
                return false;
            }
            nested(false, || x.iter().all(|(k, v)| y.get(k).is_some_and(|w| py_equals(v, w))))
        }
        (Value::Exception(x), Value::Exception(y)) => x == y,
        (Value::HostFunction(x), Value::HostFunction(y)) => Rc::ptr_eq(x, y),
        _ => match (a.as_float(), b.as_float()) {
            (Some(x), Some(y)) => x == y,
            _ => false,
        },
    }
}

/// `is`. Containers compare by identity; scalars fall back to equality.
pub(crate) fn py_identical(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::List(x), Value::List(y)) => Rc::ptr_eq(x, y),
        (Value::Dict(x), Value::Dict(y)) => Rc::ptr_eq(x, y),
        (Value::Set(x), Value::Set(y)) => Rc::ptr_eq(x, y),
        (Value::Function(x), Value::Function(y)) => Rc::ptr_eq(x, y),
        (Value::List(_) | Value::Dict(_) | Value::Set(_), _) | (_, Value::List(_) | Value::Dict(_) | Value::Set(_)) => {
            false
        }
        _ => py_equals(a, b),
    }
}

/// The items of a list, tuple or set, without charging the step budget.
pub(crate) fn sequence_items(v: &Value) -> Option<Vec<Value>> {
    match v {
        Value::List(l) => Some(l.borrow().clone()),
        Value::Tuple(t) => Some(t.as_ref().clone()),
        Value::Set(s) => Some(s.borrow().iter().map(HKey::to_value).collect()),
        _ => None,
    }
}
