//! Port of PyValue.kt: the runtime value model.
//!
//! Lists, dicts and sets are shared references so Python's mutation semantics
//! work; tuples are immutable and hash, so they can key a dict. A runtime and
//! its values belong to one thread at a time (`Rc`), which is why the plugin
//! [`crate::host::Session`] hands out JSON and never a `Value`.
//!
//! Strings follow PyMini: `len`, indexing, slicing and iteration count extended
//! grapheme clusters, and `==`/`<` use canonical equivalence (NFC). Ints are
//! exact at any size: an i64 while one fits, a `Big` past that (see bigint.rs).

use std::cell::{Cell, RefCell};
use std::fmt;
use std::hash::{Hash, Hasher};
use std::rc::Rc;

use super::ast::{FuncDef, LambdaDef, Params};
use super::eval::Env;
pub use super::fxhash::{DictMap, KeySet, NameMap};
use super::fxhash::name_map;
use super::host::HostFunction;
use super::regex::Match;
use super::text::{str_eq, str_hash};

pub type ListRef = Rc<RefCell<Vec<Value>>>;
pub type DictRef = Rc<RefCell<DictMap>>;
pub type SetRef = Rc<RefCell<KeySet>>;

/// A value inside a script.
#[derive(Clone)]
pub enum Value {
    None,
    Bool(bool),
    Int(i64),
    /// An int past 64 bits. Never one that fits in an i64: those are `Int`.
    Big(Rc<num_bigint::BigInt>),
    Float(f64),
    /// A complex number: its real and imaginary parts (see complex.rs).
    Complex(f64, f64),
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
    /// A class made by a `class` statement (an addition to PyMini).
    Class(Rc<Class>),
    /// An object made by calling a [Class].
    Instance(Rc<Instance>),
    /// A bound method, a `staticmethod`, `classmethod` or `property` wrapper,
    /// `super()`, or an iterator `iter()` made.
    Object(Rc<Object>),
}

/// A class: its name, its bases, and the attributes its body defined.
pub struct Class {
    pub name: Rc<str>,
    /// The classes it names as bases, in order.
    pub bases: Vec<Rc<Class>>,
    /// Where attributes are looked for after the class itself: its bases
    /// and theirs, in C3 order (Python's method resolution order).
    pub mro: Vec<Rc<Class>>,
    /// The built-in exception this class descends from, like `ValueError`,
    /// which makes its instances raisable. None for plain classes.
    pub exception_root: Option<Rc<str>>,
    pub attrs: RefCell<NameMap>,
    /// What decides its instances' attribute lookups, and when that was worked out.
    pub(crate) traits: Cell<Option<(u64, u8)>>,
    /// Its metaclass, when that is not plain `type`: what `type(C)` answers,
    /// where `C.name` looks after the class itself, and what `C()` calls.
    pub meta: Option<Rc<Class>>,
    /// It derives from `type`: a metaclass, whose instances are classes.
    pub is_type: bool,
    /// The built-in type it derives from (`dict`, `list`, `str`, `int`,
    /// `float`, `tuple`, `set` or `bytes`), whose value each instance
    /// carries as its [Instance::payload].
    pub builtin_base: Option<Rc<str>>,
}

impl Class {
    /// [name] on this class or the first class after it in its resolution order that has it.
    pub fn lookup(&self, name: &str) -> Option<Value> {
        if let Some(v) = self.attrs.borrow().get(name) {
            return Some(v.clone());
        }
        for k in &self.mro {
            if let Some(v) = k.attrs.borrow().get(name) {
                return Some(v.clone());
            }
        }
        None
    }

    /// The first base, as `__base__` reads it.
    pub fn base(&self) -> Option<&Rc<Class>> {
        self.bases.first()
    }

    /// Whether this class is [other] or descends from it.
    pub fn is_subclass_of(self: &Rc<Class>, other: &Rc<Class>) -> bool {
        Rc::ptr_eq(self, other) || self.mro.iter().any(|k| Rc::ptr_eq(k, other))
    }

    /// This class's name and every base's, in resolution order.
    pub fn lineage(&self) -> Vec<Rc<str>> {
        std::iter::once(self.name.clone()).chain(self.mro.iter().map(|k| k.name.clone())).collect()
    }

    /// Whether the class or a base defines [name] itself, rather than
    /// having it from its built-in base.
    pub fn defines(&self, name: &str) -> bool {
        self.lookup(name).is_some()
    }
}

/// The built-in types a class may derive from.
pub(crate) const SUBCLASSABLE: &[&str] = &["dict", "list", "str", "int", "float", "tuple", "set", "bytes"];

/// Whether a built-in base's value changes in place (so `__init__` fills
/// it) rather than being made whole by `__new__`.
pub(crate) fn mutable_base(base: &str) -> bool {
    matches!(base, "dict" | "list" | "set")
}

/// An instance of a [Class], with its own attributes.
pub struct Instance {
    pub class: Rc<Class>,
    /// The instance's attributes, until something asks for its `__dict__`.
    attrs: RefCell<NameMap>,
    /// Made the first time `__dict__` or `vars()` is asked for: from then on
    /// the attributes live in this dict, which the script holds too, so
    /// `self.__dict__[key] = value` sets an attribute.
    dict: std::cell::OnceCell<DictRef>,
    /// For an instance of a class deriving from a built-in type, the value
    /// of that type it is: the dict a `dict` subclass's items live in, the
    /// text of a `str` subclass.
    payload: std::cell::OnceCell<Value>,
}

impl Instance {
    pub fn new(class: Rc<Class>) -> Instance {
        Instance { class, attrs: RefCell::new(name_map(0)), dict: std::cell::OnceCell::new(), payload: std::cell::OnceCell::new() }
    }

    /// The built-in value this instance is, for a class deriving from a built-in type.
    #[inline]
    pub fn payload(&self) -> Option<&Value> {
        self.payload.get()
    }

    /// Give the instance its built-in value. Only the first one given counts.
    pub fn set_payload(&self, v: Value) {
        let _ = self.payload.set(v);
    }

    #[inline]
    pub fn get(&self, name: &str) -> Option<Value> {
        match self.dict.get() {
            None => self.attrs.borrow().get(name).cloned(),
            Some(d) => d.borrow().get(&StrKey(name)).cloned(),
        }
    }

    #[inline]
    pub fn has(&self, name: &str) -> bool {
        match self.dict.get() {
            None => self.attrs.borrow().contains_key(name),
            Some(d) => d.borrow().contains_key(&StrKey(name)),
        }
    }

    pub fn insert(&self, name: Rc<str>, value: Value) {
        match self.dict.get() {
            None => {
                self.attrs.borrow_mut().insert(name, value);
            }
            Some(d) => {
                d.borrow_mut().insert(HKey::Str(name), value);
            }
        }
    }

    pub fn remove(&self, name: &str) -> bool {
        match self.dict.get() {
            None => self.attrs.borrow_mut().shift_remove(name).is_some(),
            Some(d) => d.borrow_mut().shift_remove(&StrKey(name)).is_some(),
        }
    }

    pub fn len(&self) -> usize {
        match self.dict.get() {
            None => self.attrs.borrow().len(),
            Some(d) => d.borrow().len(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The attributes with string names, in the order they were set.
    pub fn pairs(&self) -> Vec<(Rc<str>, Value)> {
        match self.dict.get() {
            None => self.attrs.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            Some(d) => d.borrow().iter().filter_map(|(k, v)| if let HKey::Str(s) = k { Some((s.clone(), v.clone())) } else { None }).collect(),
        }
    }

    pub fn keys(&self) -> Vec<Rc<str>> {
        self.pairs().into_iter().map(|(k, _)| k).collect()
    }

    pub fn clear(&self) {
        self.attrs.borrow_mut().clear();
        if let Some(d) = self.dict.get() {
            d.borrow_mut().clear();
        }
    }

    /// The instance's `__dict__`: one dict, the same each time.
    pub fn dict(&self) -> DictRef {
        self.dict
            .get_or_init(|| {
                let mut m = DictMap::default();
                for (k, v) in self.attrs.borrow_mut().drain(..) {
                    m.insert(HKey::Str(k), v);
                }
                Rc::new(RefCell::new(m))
            })
            .clone()
    }
}

/// The built-in value [v] is when it is an instance of a class deriving
/// from a built-in type.
#[inline]
pub(crate) fn payload_of(v: &Value) -> Option<&Value> {
    match v {
        Value::Instance(i) => i.payload(),
        _ => None,
    }
}

/// [v] as native code sees it: an instance of a built-in type's subclass
/// is its built-in value, as a C function in CPython reads one.
#[inline]
pub(crate) fn plain_value(v: Value) -> Value {
    match &v {
        Value::Instance(i) => match i.payload() {
            Some(p) => p.clone(),
            None => v,
        },
        _ => v,
    }
}

/// Every argument as native code sees it, by [plain_value].
pub(crate) fn plain_args(args: Vec<Value>) -> Vec<Value> {
    if !args.iter().any(|a| payload_of(a).is_some()) {
        return args;
    }
    args.into_iter().map(plain_value).collect()
}

/// The callable and descriptor shapes classes need.
pub enum Object {
    /// A function with its first argument, `self` or the class, already bound.
    Bound(Value, Value),
    StaticMethod(Value),
    ClassMethod(Value),
    Property { get: Value, set: Value },
    /// `slice(start, stop, step)`, what `obj[1:3]` hands an object's `__getitem__`.
    Slice(Value, Value, Value),
    /// What `re.compile` returns: the pattern and its flags, compiled again
    /// for each use.
    Pattern { pattern: Rc<str>, flags: i64 },
    /// `super()`: attribute lookups start above [class] and bind to [receiver].
    Super { class: Rc<Class>, receiver: Value },
    /// What `iter()`, `map()`, `zip()` and the like return.
    Iterator(super::generators::IterObject),
    /// A generator, made by calling a function with `yield` in it or by `(x for x in xs)`.
    Generator(super::generators::Generator),
    /// What calling an `async def` makes, run when awaited.
    Coroutine(super::generators::Coroutine),
    /// An `async def` with `yield` in it: its name and the generator under it.
    AsyncGen(Rc<str>, Value),
    /// `range(start, stop, step)`, which counts without holding its numbers.
    Range(i64, i64, i64),
    /// A range past the i64 range (see bigint.rs).
    BigRange(Rc<super::bigint::BigRange>),
    /// `d.keys()`, `d.values()` or `d.items()`: a live view of the dict.
    DictView(ViewKind, DictRef),
    /// The state of a Mersenne Twister, which `random.Random` keeps (mt.rs).
    /// Boxed: its 2.5 KB of state would otherwise size every Object.
    Mt(Box<RefCell<super::mt::Mt>>),
}

/// Which of a dict's views.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ViewKind {
    Keys,
    Values,
    Items,
}

impl ViewKind {
    pub fn type_name(self) -> &'static str {
        match self {
            ViewKind::Keys => "dict_keys",
            ViewKind::Values => "dict_values",
            ViewKind::Items => "dict_items",
        }
    }
}

/// What a dict view shows right now.
pub(crate) fn view_items(kind: ViewKind, d: &DictRef) -> Vec<Value> {
    let d = d.borrow();
    match kind {
        ViewKind::Keys => d.keys().map(HKey::to_value).collect(),
        ViewKind::Values => d.values().cloned().collect(),
        ViewKind::Items => d.iter().map(|(k, v)| Value::tuple(vec![k.to_value(), v.clone()])).collect(),
    }
}

/// A set, a keys view or an items view, as the keys a set of it would hold;
/// None for anything else, or an items view with an unhashable value.
pub(crate) fn as_key_set(v: &Value) -> Option<KeySet> {
    match v {
        Value::Set(s) => Some(s.borrow().clone()),
        Value::Object(o) => match o.as_ref() {
            Object::DictView(ViewKind::Keys, d) => Some(d.borrow().keys().cloned().collect()),
            Object::DictView(ViewKind::Items, d) => {
                let mut out = KeySet::default();
                for (k, v) in d.borrow().iter() {
                    out.insert(HKey::from_value(&v.clone()).map(|hv| HKey::Tuple(Rc::new(vec![k.clone(), hv])))?);
                }
                Some(out)
            }
            _ => None,
        },
        _ => None,
    }
}

/// Whether [v] is a keys or items view, which behave as sets.
pub(crate) fn is_set_view(v: &Value) -> bool {
    matches!(v, Value::Object(o) if matches!(o.as_ref(), Object::DictView(ViewKind::Keys | ViewKind::Items, _)))
}

/// How many numbers `range(start, stop, step)` counts.
pub(crate) fn range_len(start: i64, stop: i64, step: i64) -> i64 {
    let (start, stop, step) = (start as i128, stop as i128, step as i128);
    let n = if step > 0 { (stop - start + step - 1) / step } else { (start - stop - step - 1) / -step };
    n.clamp(0, i64::MAX as i128) as i64
}

/// A raised built-in exception: its kind, the message `str(e)` gives, and the
/// arguments it was made with (`e.args`).
#[derive(Clone)]
pub struct ExceptionValue {
    pub kind: String,
    pub message: String,
    /// `ValueError("a", 1).args`. The interpreter's own errors carry their
    /// message here, or nothing when it is empty, as CPython's do.
    pub args: Vec<Value>,
    /// What `raise … from cause` named, read back as `__cause__`.
    pub cause: Option<Value>,
    /// The exception being handled when this one was raised, `__context__`.
    pub context: Option<Value>,
}

impl ExceptionValue {
    /// One the interpreter raised with only a message.
    pub fn new(kind: impl Into<String>, message: impl Into<String>) -> ExceptionValue {
        let message = message.into();
        let args = if message.is_empty() { Vec::new() } else { vec![Value::str(&message)] };
        ExceptionValue { kind: kind.into(), message, args, cause: None, context: None }
    }
}

impl PartialEq for ExceptionValue {
    fn eq(&self, other: &Self) -> bool {
        self.kind == other.kind && self.message == other.message
    }
}

impl Eq for ExceptionValue {}

impl Hash for ExceptionValue {
    fn hash<H: Hasher>(&self, h: &mut H) {
        self.kind.hash(h);
        self.message.hash(h);
    }
}

impl fmt::Debug for ExceptionValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}({:?})", self.kind, self.message)
    }
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
    /// The class whose body defined it, which is where `super()` starts.
    pub(crate) owner: RefCell<Option<std::rc::Weak<Class>>>,
    /// Default values, evaluated once when the `def` or `lambda` ran, as
    /// Python does: one per positional parameter, then one per keyword-only
    /// one, None where there is no default. So `lambda i=i: i` in a loop
    /// keeps each turn's `i`.
    pub(crate) defaults: Vec<Option<Value>>,
    /// Attributes set on the function, `f.cache = {}` or the `__name__`
    /// `functools.wraps` copies over.
    pub(crate) attrs: RefCell<NameMap>,
}

impl Function {
    pub(crate) fn new(name: Rc<str>, body: FnBody, closure: Rc<Env>, defaults: Vec<Option<Value>>) -> Function {
        Function { name, body, closure, owner: RefCell::new(None), defaults, attrs: RefCell::new(name_map(0)) }
    }

    /// The name its repr shows: a `__qualname__` set on it, or `C.m` for a
    /// method defined in a class body, or its own.
    pub fn qualified_name(&self) -> Rc<str> {
        if let Some(Value::Str(s)) = self.attrs.borrow().get("__qualname__") {
            return s.clone();
        }
        match self.owner.borrow().as_ref().and_then(|w| w.upgrade()) {
            Some(class) => Rc::from(format!("{}.{}", class.name, self.display_name())),
            None => self.display_name(),
        }
    }

    /// The name it shows: a `__qualname__` or `__name__` set on it, or the one it was defined with.
    pub fn display_name(&self) -> Rc<str> {
        let attrs = self.attrs.borrow();
        for key in ["__qualname__", "__name__"] {
            if let Some(Value::Str(s)) = attrs.get(key) {
                return s.clone();
            }
        }
        self.name.clone()
    }

    /// How its frame is laid out.
    pub(crate) fn frame_info(&self) -> &super::ast::Frame {
        match &self.body {
            FnBody::Def(d) => &d.frame,
            FnBody::Lambda(l, _) => &l.frame,
        }
    }

    pub(crate) fn signature(&self) -> &Params {
        match &self.body {
            FnBody::Def(d) => &d.params,
            FnBody::Lambda(l, _) => &l.params,
        }
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    /// Positional parameter names in declaration order, not counting `*rest`,
    /// keyword-only parameters or `**options`.
    pub fn parameter_names(&self) -> Vec<String> {
        self.signature().positional.iter().map(|p| p.name.to_string()).collect()
    }

    /// Whether it takes `*rest`.
    pub fn is_variadic(&self) -> bool {
        self.signature().variadic.is_some()
    }
}

/// The values that can be dict keys and set members. As in Python, numbers
/// that are equal are one key: `1`, `1.0` and `True` all find the same entry,
/// which keeps the first one written.
#[derive(Clone)]
pub enum HKey {
    None,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(Rc<str>),
    Tuple(Rc<Vec<HKey>>),
    Bytes(Rc<[u8]>),
    /// A class, instance or function, which hash and compare by identity.
    Ref(Value),
    /// An instance whose class defines `__hash__`: the hash it gave, and
    /// [canon], the first instance seen with that hash that `__eq__` found
    /// equal to it. Equal instances share one canon, so they are one key.
    Obj { hash: i64, canon: Value, value: Value },
    /// A builtin such as `int` or `len`, by name.
    Builtin(Rc<str>),
    /// An int past 64 bits.
    Big(Rc<num_bigint::BigInt>),
    /// A complex number. With no imaginary part it is the key its real
    /// part is, as `{1: "x"}[1+0j]` finds the entry.
    Complex(f64, f64),
    /// An instance of a `str`, `int`, `float`, `tuple` or `bytes`
    /// subclass: it hashes and compares as the key of its built-in value,
    /// and is the instance again when read back.
    Sub(Rc<(HKey, Value)>),
}

/// A number key as an integer when it is one exactly, so `1`, `1.0` and
/// `True` hash and compare alike.
fn whole(k: &HKey) -> Option<i64> {
    match k {
        HKey::Bool(b) => Some(*b as i64),
        HKey::Int(i) => Some(*i),
        HKey::Float(f) if f.fract() == 0.0 && *f >= i64::MIN as f64 && *f < -(i64::MIN as f64) => Some(*f as i64),
        _ => None,
    }
}

fn is_number(k: &HKey) -> bool {
    matches!(k, HKey::Bool(_) | HKey::Int(_) | HKey::Float(_) | HKey::Big(_) | HKey::Complex(..))
}

impl HKey {
    /// The key this one hashes and compares as: itself, or a subclass
    /// instance's built-in key.
    #[inline]
    pub fn base(&self) -> &HKey {
        match self {
            HKey::Sub(p) => p.0.base(),
            k => k,
        }
    }

    fn ref_address(v: &Value) -> usize {
        match v {
            Value::Class(c) => Rc::as_ptr(c) as *const u8 as usize,
            Value::Instance(i) => Rc::as_ptr(i) as *const u8 as usize,
            Value::Function(f) => Rc::as_ptr(f) as *const u8 as usize,
            Value::Object(o) => Rc::as_ptr(o) as *const u8 as usize,
            _ => 0,
        }
    }
}

impl PartialEq for HKey {
    fn eq(&self, other: &Self) -> bool {
        let (this, other) = (self.base(), other.base());
        if is_number(this) && is_number(other) {
            match (this, other) {
                (HKey::Complex(a, b), HKey::Complex(c, d)) => return a == c && b == d,
                // With no imaginary part, the real part's key.
                (HKey::Complex(re, im), x) | (x, HKey::Complex(re, im)) => return *im == 0.0 && HKey::Float(*re) == *x,
                _ => {}
            }
            // A big int equals only a big int or a float of the same value.
            match (this, other) {
                (HKey::Big(a), HKey::Big(b)) => return a == b,
                (HKey::Big(a), HKey::Float(f)) | (HKey::Float(f), HKey::Big(a)) => {
                    return super::bigint::cmp_float(a, *f) == Some(std::cmp::Ordering::Equal)
                }
                (HKey::Big(_), _) | (_, HKey::Big(_)) => return false,
                _ => {}
            }
            return match (whole(this), whole(other)) {
                (Some(a), Some(b)) => a == b,
                (None, None) => matches!((this, other), (HKey::Float(a), HKey::Float(b)) if a == b),
                _ => false,
            };
        }
        match (this, other) {
            (HKey::None, HKey::None) => true,
            (HKey::Str(a), HKey::Str(b)) => str_eq(a, b),
            (HKey::Tuple(a), HKey::Tuple(b)) => a == b,
            (HKey::Bytes(a), HKey::Bytes(b)) => a == b,
            (HKey::Ref(a), HKey::Ref(b)) => HKey::ref_address(a) == HKey::ref_address(b),
            (HKey::Obj { hash: a, canon: x, .. }, HKey::Obj { hash: b, canon: y, .. }) => {
                a == b && HKey::ref_address(x) == HKey::ref_address(y)
            }
            (HKey::Builtin(a), HKey::Builtin(b)) => a == b,
            _ => false,
        }
    }
}

impl Eq for HKey {}

impl Hash for HKey {
    fn hash<H: Hasher>(&self, h: &mut H) {
        match self {
            HKey::Sub(p) => p.0.hash(h),
            // A float of the same value hashes as this does, so they are one key.
            HKey::Big(b) => match num_traits::ToPrimitive::to_f64(b.as_ref()).filter(|f| f.is_finite() && super::bigint::cmp_float(b, *f) == Some(std::cmp::Ordering::Equal)) {
                Some(f) => {
                    3u8.hash(h);
                    f.to_bits().hash(h)
                }
                None => {
                    10u8.hash(h);
                    b.to_signed_bytes_le().hash(h)
                }
            },
            HKey::None => 0u8.hash(h),
            HKey::Complex(re, im) if *im == 0.0 => HKey::Float(*re).hash(h),
            HKey::Complex(re, im) => {
                11u8.hash(h);
                // +0.0 and -0.0 are equal, so they hash alike.
                (re + 0.0).to_bits().hash(h);
                (im + 0.0).to_bits().hash(h);
            }
            HKey::Bool(_) | HKey::Int(_) | HKey::Float(_) => match whole(self) {
                Some(i) => {
                    2u8.hash(h);
                    i.hash(h)
                }
                None => {
                    3u8.hash(h);
                    if let HKey::Float(f) = self {
                        f.to_bits().hash(h)
                    }
                }
            },
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
            HKey::Ref(v) => {
                7u8.hash(h);
                HKey::ref_address(v).hash(h)
            }
            HKey::Obj { hash, .. } => {
                8u8.hash(h);
                hash.hash(h)
            }
            HKey::Builtin(n) => {
                9u8.hash(h);
                n.hash(h)
            }
        }
    }
}

/// A string to look a key up by without making an [HKey] of it: it hashes
/// and compares as `HKey::Str` does. What a dict's method and attribute
/// lookups use, which happen on every `d.get(...)`.
pub(crate) struct StrKey<'a>(pub &'a str);

impl Hash for StrKey<'_> {
    fn hash<H: Hasher>(&self, h: &mut H) {
        4u8.hash(h);
        str_hash(self.0, h)
    }
}

impl indexmap::Equivalent<HKey> for StrKey<'_> {
    fn equivalent(&self, key: &HKey) -> bool {
        matches!(key.base(), HKey::Str(s) if str_eq(s, self.0))
    }
}

impl HKey {
    /// The hashable form of [v], or None for lists, dicts, sets and functions.
    pub fn from_value(v: &Value) -> Option<HKey> {
        Some(match v {
            Value::None => HKey::None,
            Value::Bool(b) => HKey::Bool(*b),
            Value::Int(i) => HKey::Int(*i),
            Value::Big(b) => HKey::Big(b.clone()),
            Value::Float(f) => HKey::Float(*f),
            Value::Complex(re, im) => HKey::Complex(*re, *im),
            Value::Str(s) => HKey::Str(s.clone()),
            Value::Bytes(b) => HKey::Bytes(b.clone()),
            Value::Tuple(items) => {
                let mut parts = Vec::with_capacity(items.len());
                for item in items.iter() {
                    parts.push(HKey::from_value(item)?);
                }
                HKey::Tuple(Rc::new(parts))
            }
            Value::Class(_) | Value::Instance(_) | Value::Function(_) | Value::Object(_) => HKey::Ref(v.clone()),
            Value::Builtin(n) => HKey::Builtin(n.clone()),
            _ => return None,
        })
    }

    pub fn to_value(&self) -> Value {
        match self {
            HKey::None => Value::None,
            HKey::Bool(b) => Value::Bool(*b),
            HKey::Int(i) => Value::Int(*i),
            HKey::Big(b) => Value::Big(b.clone()),
            HKey::Float(f) => Value::Float(*f),
            HKey::Complex(re, im) => Value::Complex(*re, *im),
            HKey::Str(s) => Value::Str(s.clone()),
            HKey::Bytes(b) => Value::Bytes(b.clone()),
            HKey::Tuple(t) => Value::Tuple(Rc::new(t.iter().map(HKey::to_value).collect())),
            HKey::Ref(v) => v.clone(),
            HKey::Obj { value, .. } => value.clone(),
            HKey::Builtin(n) => Value::Builtin(n.clone()),
            HKey::Sub(p) => p.1.clone(),
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

    pub fn new_dict(map: DictMap) -> Value {
        Value::Dict(Rc::new(RefCell::new(map)))
    }

    /// A dict with string keys, in the given order.
    pub fn dict<K: AsRef<str>>(pairs: Vec<(K, Value)>) -> Value {
        let mut m = DictMap::default();
        for (k, v) in pairs {
            m.insert(HKey::str(k.as_ref()), v);
        }
        Value::new_dict(m)
    }

    pub fn new_set(set: KeySet) -> Value {
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
            Value::Big(b) => num_traits::ToPrimitive::to_f64(b.as_ref()),
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
            Value::Dict(d) => d.borrow().get(&StrKey(key)).cloned(),
            _ => None,
        }
    }

    /// The Python type name, as `type()` and error messages say it.
    pub fn type_name(&self) -> String {
        match self {
            Value::Exception(e) => e.kind.clone(),
            Value::Instance(i) => i.class.name.to_string(),
            other => other.static_type_name().to_string(),
        }
    }

    pub(crate) fn static_type_name(&self) -> &'static str {
        match self {
            Value::None => "NoneType",
            Value::Bool(_) => "bool",
            Value::Int(_) | Value::Big(_) => "int",
            Value::Float(_) => "float",
            Value::Complex(..) => "complex",
            Value::Str(_) => "str",
            Value::List(_) => "list",
            Value::Tuple(_) => "tuple",
            Value::Set(_) => "set",
            Value::Dict(_) => "dict",
            Value::Function(_) | Value::Builtin(_) | Value::ModuleFunction(_) | Value::HostFunction(_) => "function",
            Value::Module(_) => "module",
            Value::Exception(_) => "Exception",
            Value::Match(_) => "re.Match",
            Value::Bytes(_) => "bytes",
            Value::Class(_) => "type",
            Value::Instance(_) => "object",
            Value::Object(o) => match o.as_ref() {
                Object::Bound(..) => "method",
                Object::StaticMethod(_) => "staticmethod",
                Object::ClassMethod(_) => "classmethod",
                Object::Property { .. } => "property",
                Object::Super { .. } => "super",
                Object::Iterator(i) => i.kind,
                Object::Generator(_) => "generator",
                Object::Coroutine(_) => "coroutine",
                Object::AsyncGen(..) => "async_generator",
                Object::Range(..) | Object::BigRange(_) => "range",
                Object::DictView(k, _) => k.type_name(),
                Object::Slice(..) => "slice",
                Object::Pattern { .. } => "re.Pattern",
                Object::Mt(_) => "_mt.state",
            },
        }
    }

    pub fn truthy(&self) -> bool {
        match self {
            Value::None => false,
            Value::Bool(b) => *b,
            Value::Int(i) => *i != 0,
            Value::Float(f) => *f != 0.0,
            Value::Complex(re, im) => *re != 0.0 || *im != 0.0,
            Value::Str(s) => !s.is_empty(),
            Value::List(l) => !l.borrow().is_empty(),
            Value::Tuple(t) => !t.is_empty(),
            Value::Set(s) => !s.borrow().is_empty(),
            Value::Dict(d) => !d.borrow().is_empty(),
            Value::Bytes(b) => !b.is_empty(),
            Value::Object(o) => match o.as_ref() {
                Object::Range(a, b, c) => range_len(*a, *b, *c) > 0,
                Object::BigRange(r) => num_traits::Signed::is_positive(&r.len()),
                Object::DictView(_, d) => !d.borrow().is_empty(),
                _ => true,
            },
            Value::Instance(i) => i.payload().is_none_or(Value::truthy),
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
        Value::Big(b) => b.to_string(),
        Value::Float(f) => format_double(*f),
        Value::Complex(re, im) => super::complex::repr(*re, *im),
        Value::Str(s) => s.to_string(),
        Value::List(_) | Value::Dict(_) | Value::Tuple(_) | Value::Set(_) | Value::Bytes(_) => py_repr(v),
        Value::Function(f) => format!("<function {}>", f.qualified_name()),
        Value::Builtin(n) if matches!(n.as_ref(), "Ellipsis" | "NotImplemented") => n.to_string(),
        Value::Builtin(n) => match n.strip_prefix(super::methods::UNBOUND_PREFIX).and_then(|m| m.split_once('.')) {
            Some((ty, method)) => format!("<method '{method}' of '{ty}' objects>"),
            None if super::builtins::is_type_name(n) => format!("<class '{n}'>"),
            None => format!("<built-in function {n}>"),
        },
        Value::HostFunction(f) => format!("<builtin {}>", f.name),
        Value::Module(n) => format!("<module '{n}'>"),
        Value::ModuleFunction(mf) => format!("<built-in function {}>", mf.1),
        Value::Exception(e) => e.message.clone(),
        Value::Match(m) => {
            let (s, e) = m.spans.first().copied().flatten().unwrap_or((0, 0));
            format!("<re.Match object; span=({s}, {e}), match={}>", py_repr(&m.subject.piece(&m.value())))
        }
        Value::Class(c) => match c.attrs.borrow().get("__module__") {
            Some(Value::Str(m)) if m.as_ref() != "builtins" => format!("<class '{m}.{}'>", c.name),
            _ => format!("<class '{}'>", c.name),
        },
        Value::Instance(i) if i.payload().is_some() => py_str(i.payload().unwrap()),
        Value::Instance(i) => match i.get("args") {
            Some(Value::Tuple(args)) if i.class.exception_root.is_some() => match args.len() {
                0 => String::new(),
                1 => py_str(&args[0]),
                _ => py_repr(&Value::Tuple(args.clone())),
            },
            _ => format!("<{} object>", i.class.name),
        },
        Value::Object(o) => match o.as_ref() {
            Object::Bound(_, f) => format!("<bound method {}>", py_str(f).trim_start_matches("<function ").trim_end_matches('>')),
            Object::Slice(a, b, c) => format!("slice({}, {}, {})", py_repr(a), py_repr(b), py_repr(c)),
            Object::Range(a, b, c) if *c == 1 => format!("range({a}, {b})"),
            Object::Range(a, b, c) => format!("range({a}, {b}, {c})"),
            Object::BigRange(r) if num_traits::One::is_one(&r.step) => format!("range({}, {})", r.start, r.stop),
            Object::BigRange(r) => format!("range({}, {}, {})", r.start, r.stop, r.step),
            Object::DictView(k, d) => format!("{}({})", k.type_name(), py_repr(&Value::list(view_items(*k, d)))),
            Object::Generator(g) => format!("<generator object {}>", g.name),
            Object::Coroutine(c) => format!("<coroutine object {}>", c.name),
            Object::AsyncGen(n, _) => format!("<async_generator object {n}>"),
            Object::Pattern { pattern, flags } => {
                let names: Vec<&str> = [(2, "re.IGNORECASE"), (8, "re.MULTILINE"), (16, "re.DOTALL")]
                    .iter()
                    .filter(|(bit, _)| flags & bit != 0)
                    .map(|(_, n)| *n)
                    .collect();
                let shown = if flags & super::regex::BYTES != 0 {
                    py_repr(&Value::bytes(&super::regex::latin1_bytes(pattern)))
                } else {
                    str_repr(pattern)
                };
                if names.is_empty() {
                    format!("re.compile({shown})")
                } else {
                    format!("re.compile({shown}, {})", names.join("|"))
                }
            }
            _ => format!("<{} object>", v.static_type_name()),
        },
    }
}

/// Python's repr of a string: single quotes, or double ones when the text
/// has a single quote and no double quote.
pub(crate) fn str_repr(s: &str) -> String {
    let quote = if s.contains('\'') && !s.contains('"') { '"' } else { '\'' };
    let mut out = String::with_capacity(s.len() + 2);
    out.push(quote);
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            c if c == quote => {
                out.push('\\');
                out.push(c);
            }
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            // Other control characters as Python writes them.
            c if (c as u32) < 0x20 || (0x7F..0xA0).contains(&(c as u32)) => out.push_str(&format!("\\x{:02x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push(quote);
    out
}

/// `ascii()`: a repr with every character past ASCII written as `\xNN`,
/// `\uNNNN` or `\UNNNNNNNN`.
pub(crate) fn ascii_escape(repr: &str) -> String {
    let mut out = String::with_capacity(repr.len());
    for c in repr.chars() {
        match c as u32 {
            0..=0x7F => out.push(c),
            n @ 0x80..=0xFF => out.push_str(&format!("\\x{n:02x}")),
            n @ 0x100..=0xFFFF => out.push_str(&format!("\\u{n:04x}")),
            n => out.push_str(&format!("\\U{n:08x}")),
        }
    }
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
        Value::Exception(e) => format!("{}({})", e.kind, e.args.iter().map(py_repr).collect::<Vec<_>>().join(", ")),
        Value::Instance(i) if i.payload().is_some() => py_repr(i.payload().unwrap()),
        Value::Instance(i) if i.class.exception_root.is_some() => {
            let args = match i.get("args") {
                Some(Value::Tuple(a)) => a.iter().map(py_repr).collect::<Vec<_>>().join(", "),
                _ => String::new(),
            };
            format!("{}({args})", i.class.name)
        }
        other => py_str(other),
    }
}

/// Print an f64 the way Python's repr does: the fewest digits that read back
/// as the same value, laid out as float_repr_style "short" lays them out:
/// plain positions while the decimal point falls between 10**-4 and 10**16,
/// an exponent of at least two digits past that, and `.0` on whole numbers.
pub(crate) fn format_double(d: f64) -> String {
    if d.is_nan() {
        return "nan".into();
    }
    if d.is_infinite() {
        return if d < 0.0 { "-inf".into() } else { "inf".into() };
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    // Rust's `{:e}` finds how few digits round-trip, as dtoa's mode 0 does.
    // Where two such strings are equally near, dtoa takes the even one: the
    // exactly rounded string of that length (half to even), when it reads back.
    let sign = if d < 0.0 { "-" } else { "" };
    let split = |sci: String| -> (String, i32) {
        let (mantissa, exp) = sci.split_once('e').unwrap_or((&sci, "0"));
        (mantissa.chars().filter(|c| *c != '.').collect(), exp.parse().unwrap_or(0))
    };
    let (mut digits, mut exp) = split(format!("{:e}", d.abs()));
    let exact = format!("{:.*e}", digits.len() - 1, d.abs());
    if exact.parse::<f64>().ok() == Some(d.abs()) {
        (digits, exp) = split(exact);
    }
    let decpt = exp + 1;
    let n = digits.len() as i32;
    if -4 < decpt && decpt <= 16 {
        if decpt <= 0 {
            format!("{sign}0.{}{digits}", "0".repeat((-decpt) as usize))
        } else if decpt >= n {
            format!("{sign}{digits}{}.0", "0".repeat((decpt - n) as usize))
        } else {
            format!("{sign}{}.{}", &digits[..decpt as usize], &digits[decpt as usize..])
        }
    } else {
        let rest = if n > 1 { format!(".{}", &digits[1..]) } else { String::new() };
        let esign = if exp < 0 { '-' } else { '+' };
        format!("{sign}{}{rest}e{esign}{:02}", &digits[..1], exp.abs())
    }
}

/// Whether a container holds an instance anywhere inside it, so `==` on it
/// has to ask that instance's `__eq__`. A huge or cyclic container answers
/// yes once it has looked at enough items: the slow path is right too.
pub(crate) fn holds_instance(v: &Value) -> bool {
    fn walk(v: &Value, budget: &mut u32) -> bool {
        if *budget == 0 {
            return true;
        }
        *budget -= 1;
        match v {
            Value::Instance(_) => true,
            Value::Class(_) => meta_eq_class(v),
            Value::List(l) => l.borrow().iter().any(|x| walk(x, budget)),
            Value::Tuple(t) => t.iter().any(|x| walk(x, budget)),
            Value::Dict(d) => d.borrow().values().any(|x| walk(x, budget)),
            _ => false,
        }
    }
    match v {
        Value::Instance(_) => true,
        Value::Class(_) => meta_eq_class(v),
        Value::List(_) | Value::Tuple(_) | Value::Dict(_) => walk(v, &mut 4096),
        _ => false,
    }
}

/// A class whose metaclass has an `__eq__` of its own (typing_extensions'
/// Protocol equals typing's): `==` and `in` have to ask it.
pub(crate) fn meta_eq_class(v: &Value) -> bool {
    matches!(v, Value::Class(c) if c.meta.as_ref().is_some_and(|m| m.lookup("__eq__").is_some()))
}

/// Python `==`. Numbers compare across types, so 1 == 1.0 == True.
pub(crate) fn py_equals(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::None, Value::None) => true,
        (Value::Complex(a, b), Value::Complex(c, d)) => a == c && b == d,
        (Value::Complex(re, im), x @ (Value::Int(_) | Value::Bool(_) | Value::Float(_) | Value::Big(_)))
        | (x @ (Value::Int(_) | Value::Bool(_) | Value::Float(_) | Value::Big(_)), Value::Complex(re, im)) => {
            *im == 0.0 && py_equals(&Value::Float(*re), x)
        }
        (Value::Big(x), Value::Big(y)) => x == y,
        (Value::Big(x), Value::Float(f)) | (Value::Float(f), Value::Big(x)) => {
            super::bigint::cmp_float(x, *f) == Some(std::cmp::Ordering::Equal)
        }
        // A big int is never an i64's value.
        (Value::Big(_), Value::Int(_) | Value::Bool(_)) | (Value::Int(_) | Value::Bool(_), Value::Big(_)) => false,
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
        (Value::Function(x), Value::Function(y)) => Rc::ptr_eq(x, y),
        (Value::Class(x), Value::Class(y)) => Rc::ptr_eq(x, y),
        // One module per name: `sys.modules["json"] is json`.
        (Value::Module(x), Value::Module(y)) => x == y,
        (Value::ModuleFunction(x), Value::ModuleFunction(y)) => x == y,
        (Value::Instance(x), Value::Instance(y)) if Rc::ptr_eq(x, y) => true,
        // A subclass of a built-in type with no `__eq__` of its own compares as its value.
        (Value::Instance(x), _) if x.payload().is_some() && !x.class.defines("__eq__") => py_equals(x.payload().unwrap(), b),
        (_, Value::Instance(y)) if y.payload().is_some() && !y.class.defines("__eq__") => py_equals(a, y.payload().unwrap()),
        (Value::Instance(_), Value::Instance(_)) => false,
        // A keys or items view equals the set of what it shows.
        (x, y) if is_set_view(x) || is_set_view(y) => match (as_key_set(x), as_key_set(y)) {
            (Some(p), Some(q)) => p.len() == q.len() && p.iter().all(|k| q.contains(k)),
            _ => false,
        },
        (Value::Object(x), Value::Object(y)) => {
            if let (Object::Range(a1, b1, c1), Object::Range(a2, b2, c2)) = (x.as_ref(), y.as_ref()) {
                // Equal when they count the same numbers.
                let (n1, n2) = (range_len(*a1, *b1, *c1), range_len(*a2, *b2, *c2));
                return n1 == n2 && (n1 == 0 || (a1 == a2 && (n1 == 1 || c1 == c2)));
            }
            if let (Some(p), Some(q)) = (super::bigint::range_parts(x), super::bigint::range_parts(y)) {
                let (n1, n2) = (p.len(), q.len());
                let one = num_bigint::BigInt::from(1);
                return n1 == n2 && (num_traits::Zero::is_zero(&n1) || (p.start == q.start && (n1 == one || p.step == q.step)));
            }
            Rc::ptr_eq(x, y)
        }
        (Value::Builtin(x), Value::Builtin(y)) => x == y,
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
        (Value::Class(x), Value::Class(y)) => Rc::ptr_eq(x, y),
        (Value::Instance(x), Value::Instance(y)) => Rc::ptr_eq(x, y),
        (Value::Object(x), Value::Object(y)) => Rc::ptr_eq(x, y),
        (Value::List(_) | Value::Dict(_) | Value::Set(_), _) | (_, Value::List(_) | Value::Dict(_) | Value::Set(_)) => {
            false
        }
        // `1 is True` and `1 is 1.0` are False: equal values of different types.
        (Value::Bool(x), Value::Bool(y)) => x == y,
        (Value::Bool(_), _) | (_, Value::Bool(_)) => false,
        (Value::Int(x), Value::Int(y)) => x == y,
        (Value::Int(_), Value::Float(_)) | (Value::Float(_), Value::Int(_)) => false,
        _ => py_equals(a, b),
    }
}

/// The items of a list, tuple or set, without charging the step budget.
pub(crate) fn sequence_items(v: &Value) -> Option<Vec<Value>> {
    match v {
        Value::List(l) => Some(l.borrow().clone()),
        Value::Tuple(t) => Some(t.as_ref().clone()),
        Value::Set(s) => Some(s.borrow().iter().map(HKey::to_value).collect()),
        Value::Instance(i) if !i.class.defines("__iter__") => i.payload().and_then(sequence_items),
        _ => None,
    }
}
