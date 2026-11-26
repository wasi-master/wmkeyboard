//! Additions to PyMini that give scripts classes: `class` statements,
//! instances and their attributes, methods, `property`, `staticmethod`,
//! `classmethod`, `super()`, operator methods (`__add__`, `__eq__`, `__len__`,
//! `__iter__` and the rest), exceptions defined as classes, `with`, and modules
//! written in Python (a plugin's own files, and the Python half of the
//! standard library).
//!
//! Nothing here reaches outside the interpreter. A class is a namespace of
//! values a script made; an instance is a dict of attributes with a class
//! beside it. The sandbox is still exactly as wide as the host functions.

use std::cell::RefCell;
use std::rc::Rc;

use super::ast::*;
use super::builtins::EXCEPTION_KINDS;
use super::error::{Exc, PyError, R};
use super::eval::{exception_matches, Env, Interp};
use super::fxhash::{name_map, NameMap};
use super::generators::iterator_object;
use super::parser::parse;
use super::value::*;

/// Nesting past which `repr` gives up, as the plain repr does.
const REPR_NESTING: u32 = 200;

/// Built-ins scripts cannot name, reached only through `super()`, `object`
/// or a property's `.setter`.
pub(crate) const HIDDEN_BASE_INIT: &str = "__base_init__";
pub(crate) const HIDDEN_OBJECT_SETATTR: &str = "__object_setattr__";
pub(crate) const HIDDEN_OBJECT_GETATTR: &str = "__object_getattr__";
pub(crate) const HIDDEN_DEFAULT_STR: &str = "__default_str__";
pub(crate) const HIDDEN_DEFAULT_REPR: &str = "__default_repr__";
pub(crate) const HIDDEN_PROPERTY_SETTER: &str = "__property_setter__";
pub(crate) const HIDDEN_PROPERTY_GETTER: &str = "__property_getter__";
pub(crate) const HIDDEN_OBJECT_NEW: &str = "__object_new__";
pub(crate) const HIDDEN_OBJECT_INIT_SUBCLASS: &str = "__object_init_subclass__";
pub(crate) const HIDDEN_TYPE_NEW: &str = "__type_new__";
pub(crate) const HIDDEN_TYPE_CALL: &str = "__type_call__";
pub(crate) const HIDDEN_TYPE_INIT: &str = "__type_init__";

/// The forward and reflected method names for a binary operator.
fn operator_methods(op: BinOp) -> (&'static str, &'static str) {
    match op {
        BinOp::Add => ("__add__", "__radd__"),
        BinOp::Sub => ("__sub__", "__rsub__"),
        BinOp::Mul => ("__mul__", "__rmul__"),
        BinOp::Div => ("__truediv__", "__rtruediv__"),
        BinOp::FloorDiv => ("__floordiv__", "__rfloordiv__"),
        BinOp::Mod => ("__mod__", "__rmod__"),
        BinOp::Pow => ("__pow__", "__rpow__"),
        BinOp::BitAnd => ("__and__", "__rand__"),
        BinOp::BitOr => ("__or__", "__ror__"),
        BinOp::BitXor => ("__xor__", "__rxor__"),
        BinOp::LShift => ("__lshift__", "__rlshift__"),
        BinOp::RShift => ("__rshift__", "__rrshift__"),
    }
}

fn is_not_implemented(v: &Value) -> bool {
    matches!(v, Value::Builtin(n) if n.as_ref() == "NotImplemented")
}

fn hidden(name: &str) -> Value {
    Value::Builtin(Rc::from(name))
}

fn object(o: Object) -> Value {
    Value::Object(Rc::new(o))
}

impl Interp {
    // MARK: - class statements

    #[inline(never)]
    pub(crate) fn exec_class(&mut self, def: &Rc<ClassDef>, bind: &Name, env: &Rc<Env>) -> R<()> {
        let mut decorators = Vec::with_capacity(def.decorators.len());
        for d in &def.decorators {
            decorators.push(self.eval(d, env)?);
        }
        let mut bases = Vec::with_capacity(def.bases.len());
        for b in &def.bases {
            bases.push(self.eval(b, env)?);
        }
        let mut kwargs: Vec<(Rc<str>, Value)> = Vec::new();
        for (k, e) in &def.keywords {
            let v = self.eval(e, env)?;
            if k.as_ref() == "**" {
                for (key, item) in self.mapping_pairs(&v)? {
                    let HKey::Str(key) = key else {
                        return Err(self.error("keywords must be strings", "TypeError"));
                    };
                    kwargs.push((key, item));
                }
            } else {
                kwargs.push((k.clone(), v));
            }
        }
        let explicit = kwargs.iter().position(|(k, _)| k.as_ref() == "metaclass").map(|i| kwargs.remove(i).1);
        let scope = Env::new(Some(env.clone()));
        scope.define(&Rc::from("__qualname__"), Value::Str(def.name.clone()));
        if let Value::Str(doc) = super::eval::docstring(&def.body) {
            scope.define(&Rc::from("__doc__"), Value::Str(doc));
        }
        self.exec_block(&def.body, &scope)?;
        let mut attrs = name_map(0);
        for (k, v) in scope.vars.borrow_mut().take_all() {
            attrs.insert(k, v);
        }
        // The metaclass named, or else the most derived of the bases' own.
        let meta = match explicit {
            Some(Value::Builtin(n)) if n.as_ref() == "type" => None,
            Some(m) => Some(m),
            None => self.derived_meta(None, &bases)?.map(Value::Class),
        };
        let mut value = match meta {
            None => self.make_class_with(def.name.clone(), &bases, attrs, None, kwargs)?,
            // `M(name, bases, namespace, **kwargs)`: the metaclass (or any callable) makes the class.
            Some(m) => {
                let ns = Value::dict(attrs.into_iter().collect());
                let args = vec![Value::Str(def.name.clone()), Value::tuple(bases), ns];
                self.call_value(&m, args, kwargs)?
            }
        };
        for d in decorators.iter().rev() {
            value = self.call_value(d, vec![value], Vec::new())?;
        }
        self.store_name(bind, value, env);
        Ok(())
    }

    /// The metaclass a class with these bases gets: [given] or the bases'
    /// own, whichever derives from all the others. None for plain `type`.
    pub(crate) fn derived_meta(&self, given: Option<Rc<Class>>, bases: &[Value]) -> R<Option<Rc<Class>>> {
        let mut winner = given;
        for b in bases {
            let Value::Class(c) = b else { continue };
            let Some(m) = &c.meta else { continue };
            winner = match winner {
                None => Some(m.clone()),
                Some(w) if w.is_subclass_of(m) => Some(w),
                Some(w) if m.is_subclass_of(&w) => Some(m.clone()),
                Some(_) => {
                    return Err(self.error(
                        "metaclass conflict: the metaclass of a derived class must be a (non-strict) subclass of the metaclasses of all its bases",
                        "TypeError",
                    ))
                }
            };
        }
        Ok(winner)
    }

    /// A class made by `type.__new__(meta, name, bases, attrs, **kwargs)`:
    /// a `class` statement, `type(name, bases, attrs)`, a metaclass's `super().__new__`.
    pub(crate) fn make_class_with(
        &mut self,
        name: Rc<str>,
        bases: &[Value],
        attrs: NameMap,
        meta: Option<Rc<Class>>,
        kwargs: Vec<(Rc<str>, Value)>,
    ) -> R<Value> {
        let mut classes = Vec::with_capacity(bases.len());
        let mut root: Option<Rc<str>> = None;
        let mut is_type = false;
        for b in bases {
            match b {
                Value::Class(c) => {
                    if root.is_none() {
                        root = c.exception_root.clone();
                    }
                    is_type |= c.is_type;
                    classes.push(c.clone());
                }
                Value::Builtin(n) if n.as_ref() == "object" => {}
                Value::Builtin(n) if n.as_ref() == "type" => is_type = true,
                Value::Builtin(n) if EXCEPTION_KINDS.contains(&n.as_ref()) => {
                    if root.is_none() {
                        root = Some(n.clone());
                    }
                }
                Value::Builtin(n) => {
                    return Err(self.error(
                        format!("a class cannot derive from the built-in '{n}'; keep one as an attribute instead"),
                        "TypeError",
                    ))
                }
                other => {
                    return Err(self.error(format!("a class can only derive from a class, not '{}'", other.type_name()), "TypeError"))
                }
            }
        }
        for (i, c) in classes.iter().enumerate() {
            if classes[..i].iter().any(|d| Rc::ptr_eq(c, d)) {
                return Err(self.error(format!("duplicate base class {}", c.name), "TypeError"));
            }
        }
        let Some(mro) = c3_order(&classes) else {
            let names: Vec<&str> = classes.iter().map(|c| c.name.as_ref()).collect();
            return Err(self.error(
                format!("Cannot create a consistent method resolution order (MRO) for bases {}", names.join(", ")),
                "TypeError",
            ));
        };
        if attrs.len() > self.max_container {
            return Err(self.container_abort("class"));
        }
        let meta = self.derived_meta(meta, bases)?;
        let class = Rc::new(Class {
            name,
            bases: classes,
            mro,
            exception_root: root,
            attrs: RefCell::new(attrs),
            traits: Default::default(),
            meta,
            is_type,
        });
        for v in class.attrs.borrow().values() {
            own(v, &class);
        }
        self.track_class(&class);
        if class.exception_root.is_some() {
            self.exception_classes.insert(class.name.clone(), class.clone());
        }
        // The nearest base's `__init_subclass__` hears about each class made from it, as a classmethod.
        let hook = class.mro.iter().find_map(|b| b.attrs.borrow().get("__init_subclass__").cloned());
        if let Some(hook) = hook {
            let f = match &hook {
                Value::Object(o) => match o.as_ref() {
                    Object::ClassMethod(f) | Object::StaticMethod(f) => f.clone(),
                    _ => hook.clone(),
                },
                _ => hook.clone(),
            };
            self.call_value(&f, vec![Value::Class(class.clone())], kwargs)?;
        } else if !kwargs.is_empty() {
            return Err(self.error(format!("{}.__init_subclass__() takes no keyword arguments", class.name), "TypeError"));
        }
        Ok(Value::Class(class))
    }

    /// `type.__new__(meta, name, bases, attrs, **kwargs)`, and `type(name, bases, attrs)` with no meta.
    pub(crate) fn type_new(&mut self, meta: Option<Rc<Class>>, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Value> {
        if args.len() != 3 {
            return Err(self.error("type.__new__() takes exactly 3 arguments (name, bases, dict)", "TypeError"));
        }
        let Value::Str(class_name) = &args[0] else {
            return Err(self.error("type.__new__() argument 1 must be str", "TypeError"));
        };
        let bases = match &args[1] {
            Value::Tuple(t) => t.as_ref().clone(),
            _ => return Err(self.error("type.__new__() argument 2 must be tuple", "TypeError")),
        };
        let Value::Dict(d) = &args[2] else {
            return Err(self.error("type.__new__() argument 3 must be dict", "TypeError"));
        };
        let mut attrs = name_map(d.borrow().len());
        for (k, v) in d.borrow().iter() {
            let HKey::Str(k) = k else {
                return Err(self.error("type() attribute names must be strings", "TypeError"));
            };
            attrs.insert(k.clone(), v.clone());
        }
        self.make_class_with(class_name.clone(), &bases, attrs, meta, kwargs.to_vec())
    }

    /// [name] on [c]'s metaclass, bound to [c]: how a class answers
    /// `len(C)`, `for x in C`, `C[k]` and the like.
    pub(crate) fn meta_method(&self, c: &Rc<Class>, name: &str) -> Option<Value> {
        let m = c.meta.as_ref()?;
        let v = m.lookup(name)?;
        Some(self.bind(v, Value::Class(c.clone()), m))
    }

    /// `x: int = 1`. The value is assigned like any other; a plain name also
    /// goes into its scope's `__annotations__`, which is what `dataclass` reads.
    #[inline(never)]
    pub(crate) fn exec_ann_assign(&mut self, target: &Expr, annotation: Option<&Rc<str>>, value: Option<&Expr>, env: &Rc<Env>) -> R<()> {
        if let Some(v) = value {
            let v = self.eval(v, env)?;
            self.assign(target, v, env)?;
        }
        // Only a module or a class body keeps annotations, as in Python.
        let kept = |n: &Name| matches!(n.at, NameAt::Class(_)) || (n.at == NameAt::Global && env.parent.is_none());
        if let Some(n) = match target {
            Expr::Name(n) if kept(n) => Some(&n.id),
            _ => None,
        } {
            let key: Rc<str> = Rc::from("__annotations__");
            let existing = env.vars.borrow().get(&key).cloned();
            let dict = match existing {
                Some(Value::Dict(d)) => d,
                _ => {
                    let d = Rc::new(RefCell::new(DictMap::default()));
                    env.vars.borrow_mut().insert(key, Value::Dict(d.clone()));
                    d
                }
            };
            let ann = annotation.map(|a| Value::Str(a.clone())).unwrap_or(Value::None);
            dict.borrow_mut().insert(HKey::Str(n.clone()), ann);
        }
        Ok(())
    }

    #[inline(never)]
    pub(crate) fn exec_assert(&mut self, test: &Expr, message: Option<&Expr>, env: &Rc<Env>) -> R<()> {
        let v = self.eval(test, env)?;
        if self.truth(&v)? {
            return Ok(());
        }
        let text = match message {
            Some(m) => {
                let m = self.eval(m, env)?;
                self.to_str(&m)?
            }
            None => String::new(),
        };
        Err(self.error(text, "AssertionError"))
    }

    /// `with a as x, b:`: each manager's `__enter__` in order, the body, then
    /// each `__exit__` in reverse. An `__exit__` that returns something true
    /// swallows the error. The sandbox's own stops are never offered to it.
    #[inline(never)]
    pub(crate) fn exec_with(&mut self, items: &[(Expr, Option<Expr>)], body: &[Stmt], env: &Rc<Env>) -> R<()> {
        let Some(((manager, target), rest)) = items.split_first() else {
            return self.exec_block(body, env);
        };
        let m = self.eval(manager, env)?;
        let entered = self.call_method(&m, "__enter__", Vec::new(), Vec::new())?;
        if let Some(t) = target {
            self.assign(t, entered, env)?;
        }
        let result = self.exec_with(rest, body, env);
        match result {
            Err(Exc::Error(e)) if !e.is_abort() => {
                let value = self.caught_value(&e);
                let kind = self.type_of(&value);
                let swallow = self.call_method(&m, "__exit__", vec![kind, value, Value::None], Vec::new())?;
                if self.truth(&swallow)? {
                    Ok(())
                } else {
                    Err(Exc::Error(e))
                }
            }
            Err(Exc::Error(e)) => Err(Exc::Error(e)),
            other => {
                self.call_method(&m, "__exit__", vec![Value::None, Value::None, Value::None], Vec::new())?;
                other
            }
        }
    }

    // MARK: - tracking, so dropping the runtime frees every cycle

    fn track_class(&mut self, class: &Rc<Class>) {
        if self.classes.len() >= 64 && self.classes.len().is_power_of_two() {
            self.classes.retain(|w| w.strong_count() > 0);
        }
        self.classes.push(Rc::downgrade(class));
    }

    fn track_instance(&mut self, inst: &Rc<Instance>) {
        if self.instances.len() >= 64 && self.instances.len().is_power_of_two() {
            self.instances.retain(|w| w.strong_count() > 0);
        }
        self.instances.push(Rc::downgrade(inst));
    }

    // MARK: - making instances

    #[inline(never)]
    pub(crate) fn construct(&mut self, class: &Rc<Class>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        // A metaclass's `__call__` decides what calling its classes does.
        if class.meta.is_some() {
            if let Some(call) = self.meta_method(class, "__call__") {
                return self.call_value(&call, args, kwargs);
            }
        }
        self.construct_plain(class, args, kwargs)
    }

    /// `M(name, bases, namespace)` for a metaclass M: its `__new__` (type's
    /// unless it has one) makes the class, then its `__init__` runs on it.
    fn construct_class(&mut self, meta: &Rc<Class>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        let made = match meta.lookup("__new__") {
            Some(new) => {
                let f = match &new {
                    Value::Object(o) => match o.as_ref() {
                        Object::StaticMethod(f) | Object::ClassMethod(f) => f.clone(),
                        _ => new.clone(),
                    },
                    _ => new.clone(),
                };
                let mut all = Vec::with_capacity(args.len() + 1);
                all.push(Value::Class(meta.clone()));
                all.extend(args.iter().cloned());
                self.call_value(&f, all, kwargs.clone())?
            }
            None => self.type_new(Some(meta.clone()), &args, &kwargs)?,
        };
        if let Value::Class(c) = &made {
            if c.meta.as_ref().is_some_and(|m| m.is_subclass_of(meta)) {
                if let Some(init) = meta.lookup("__init__") {
                    let bound = self.bind(init, made.clone(), meta);
                    self.call_value(&bound, args, kwargs)?;
                }
            }
        }
        Ok(made)
    }

    /// Calling [class] as `type.__call__` does: `__new__`, then `__init__`.
    pub(crate) fn construct_plain(&mut self, class: &Rc<Class>, args: Vec<Value>, kwargs: Vec<(Rc<str>, Value)>) -> R<Value> {
        if class.is_type {
            return self.construct_class(class, args, kwargs);
        }
        if let Some(Value::Set(pending)) = class.lookup("__abstractmethods__") {
            let mut names: Vec<String> = pending.borrow().iter().map(|k| py_str(&k.to_value())).collect();
            if !names.is_empty() {
                names.sort();
                return Err(self.error(
                    format!("Can't instantiate abstract class {} with abstract methods {}", class.name, names.join(", ")),
                    "TypeError",
                ));
            }
        }
        // `__new__`, implicitly a staticmethod taking the class. What it returns
        // is the result; `__init__` runs on it only when it is an instance of this class.
        if let Some(new) = class.lookup("__new__") {
            let f = match &new {
                Value::Object(o) => match o.as_ref() {
                    Object::StaticMethod(f) | Object::ClassMethod(f) => f.clone(),
                    _ => new.clone(),
                },
                _ => new.clone(),
            };
            let mut all = Vec::with_capacity(args.len() + 1);
            all.push(Value::Class(class.clone()));
            all.extend(args.iter().cloned());
            let made = self.call_value(&f, all, kwargs.clone())?;
            if let Value::Instance(inst) = &made {
                if inst.class.is_subclass_of(class) {
                    if let Some(init) = class.lookup("__init__") {
                        let bound = self.bind(init, made.clone(), class);
                        self.call_value(&bound, args, kwargs)?;
                    }
                }
            }
            return Ok(made);
        }
        let inst = Rc::new(Instance { class: class.clone(), attrs: RefCell::new(name_map(0)) });
        self.track_instance(&inst);
        let value = Value::Instance(inst.clone());
        if class.exception_root.is_some() {
            inst.attrs.borrow_mut().insert(Rc::from("args"), Value::tuple(args.clone()));
        }
        match class.lookup("__init__") {
            Some(init) => {
                let bound = self.bind(init, value.clone(), class);
                self.call_value(&bound, args, kwargs)?;
            }
            None => {
                if class.exception_root.is_none() && (!args.is_empty() || !kwargs.is_empty()) {
                    return Err(self.error(format!("{}() takes no arguments", class.name), "TypeError"));
                }
            }
        }
        Ok(value)
    }

    /// What a class attribute becomes when read through [receiver]: functions
    /// bind, a `staticmethod` unwraps, a `classmethod` binds to the class.
    pub(crate) fn bind(&self, v: Value, receiver: Value, class: &Rc<Class>) -> Value {
        match &v {
            Value::Function(_) => object(Object::Bound(receiver, v)),
            Value::Builtin(n) if n.starts_with("__") && n.ends_with("__") => object(Object::Bound(receiver, v)),
            Value::Object(o) => match o.as_ref() {
                Object::StaticMethod(f) => f.clone(),
                Object::ClassMethod(f) => object(Object::Bound(Value::Class(class.clone()), f.clone())),
                _ => v,
            },
            _ => v,
        }
    }

    // MARK: - attributes

    /// `inst.name`: a property, then the instance's own attributes, then the
    /// class's (bound), then `__getattr__`.
    #[inline(never)]
    pub(crate) fn instance_attr(&mut self, inst: &Rc<Instance>, name: &str) -> R<Option<Value>> {
        // No property anywhere in the class: the instance's own attribute
        // wins without asking the class first.
        if self.class_traits(&inst.class) & HAS_PROPERTY == 0 {
            if let Some(v) = inst.attrs.borrow().get(name) {
                return Ok(Some(v.clone()));
            }
        }
        let from_class = inst.class.lookup(name);
        if let Some(Value::Object(o)) = &from_class {
            if let Object::Property { get, .. } = o.as_ref() {
                let get = get.clone();
                return self.call_value(&get, vec![Value::Instance(inst.clone())], Vec::new()).map(Some);
            }
        }
        let own = inst.attrs.borrow().get(name).cloned();
        if own.is_some() {
            return Ok(own);
        }
        if let Some(v) = from_class {
            return Ok(Some(self.bind(v, Value::Instance(inst.clone()), &inst.class)));
        }
        match name {
            "__class__" => return Ok(Some(Value::Class(inst.class.clone()))),
            "__dict__" => {
                let pairs: Vec<(Rc<str>, Value)> = inst.attrs.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                return Ok(Some(Value::dict(pairs)));
            }
            "__str__" => return Ok(Some(object(Object::Bound(Value::Instance(inst.clone()), hidden(HIDDEN_DEFAULT_STR))))),
            "__repr__" => return Ok(Some(object(Object::Bound(Value::Instance(inst.clone()), hidden(HIDDEN_DEFAULT_REPR))))),
            // An exception's cause and context are None until `raise … from` sets one.
            "__cause__" | "__context__" | "__traceback__" if inst.class.exception_root.is_some() => return Ok(Some(Value::None)),
            _ => {}
        }
        if let Some(getattr) = inst.class.lookup("__getattr__") {
            let bound = self.bind(getattr, Value::Instance(inst.clone()), &inst.class);
            return self.call_value(&bound, vec![Value::str(name)], Vec::new()).map(Some);
        }
        Ok(None)
    }

    /// `Cls.name`.
    pub(crate) fn class_attr(&self, class: &Rc<Class>, name: &str) -> Option<Value> {
        if let Some(v) = class.lookup(name) {
            return Some(match &v {
                Value::Object(o) => match o.as_ref() {
                    Object::StaticMethod(f) => f.clone(),
                    Object::ClassMethod(f) => object(Object::Bound(Value::Class(class.clone()), f.clone())),
                    _ => v,
                },
                _ => v,
            });
        }
        Some(match name {
            "__name__" | "__qualname__" => Value::Str(class.name.clone()),
            "__doc__" => Value::None,
            "__base__" => class.base().cloned().map(Value::Class).unwrap_or(Value::Builtin(Rc::from("object"))),
            "__bases__" => {
                if class.bases.is_empty() {
                    Value::tuple(vec![Value::Builtin(Rc::from("object"))])
                } else {
                    Value::tuple(class.bases.iter().cloned().map(Value::Class).collect())
                }
            }
            "__mro__" => {
                let mut out: Vec<Value> = std::iter::once(class.clone()).chain(class.mro.iter().cloned()).map(Value::Class).collect();
                out.push(Value::Builtin(Rc::from("object")));
                Value::tuple(out)
            }
            "__dict__" => {
                let pairs: Vec<(Rc<str>, Value)> = class.attrs.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                Value::dict(pairs)
            }
            _ => return None,
        })
    }

    /// `super().name`: looked up in the receiver's resolution order after
    /// the class that defined the running method, and bound to the same `self`.
    pub(crate) fn super_attr(&mut self, class: &Rc<Class>, receiver: &Value, name: &str) -> R<Option<Value>> {
        let order: Vec<Rc<Class>> = match receiver {
            Value::Instance(i) => std::iter::once(i.class.clone()).chain(i.class.mro.iter().cloned()).collect(),
            Value::Class(c) => std::iter::once(c.clone()).chain(c.mro.iter().cloned()).collect(),
            _ => Vec::new(),
        };
        let after: Vec<Rc<Class>> = match order.iter().position(|k| Rc::ptr_eq(k, class)) {
            Some(at) => order[at + 1..].to_vec(),
            None => class.mro.clone(),
        };
        for k in after {
            if let Some(v) = k.attrs.borrow().get(name).cloned() {
                if let Value::Object(o) = &v {
                    if let Object::Property { get, .. } = o.as_ref() {
                        let get = get.clone();
                        return self.call_value(&get, vec![receiver.clone()], Vec::new()).map(Some);
                    }
                }
                let owner = match receiver {
                    Value::Instance(i) => i.class.clone(),
                    Value::Class(c) => c.clone(),
                    _ => k.clone(),
                };
                return Ok(Some(self.bind(v, receiver.clone(), &owner)));
            }
        }
        let bound = |f: &str| Some(object(Object::Bound(receiver.clone(), hidden(f))));
        // Past every class: `type`'s methods for a metaclass, else `object`'s.
        let is_type = class.is_type;
        Ok(match name {
            // A static method: the class comes as the first argument.
            "__new__" => Some(hidden(if is_type { HIDDEN_TYPE_NEW } else { HIDDEN_OBJECT_NEW })),
            "__call__" if is_type => bound(HIDDEN_TYPE_CALL),
            "__init__" if is_type => bound(HIDDEN_TYPE_INIT),
            "__init_subclass__" => bound(HIDDEN_OBJECT_INIT_SUBCLASS),
            "__init__" => bound(HIDDEN_BASE_INIT),
            "__setattr__" => bound(HIDDEN_OBJECT_SETATTR),
            "__getattr__" | "__getattribute__" => bound(HIDDEN_OBJECT_GETATTR),
            "__str__" => bound(HIDDEN_DEFAULT_STR),
            "__repr__" => bound(HIDDEN_DEFAULT_REPR),
            _ => None,
        })
    }

    /// `obj.name = value`.
    #[inline(never)]
    pub(crate) fn set_attr(&mut self, obj: &Value, name: &Rc<str>, value: Value) -> R<()> {
        match obj {
            Value::Instance(inst) => {
                if self.class_traits(&inst.class) & (HAS_PROPERTY | HAS_SETATTR) == 0 {
                    return self.raw_set(inst, name.clone(), value);
                }
                if let Some(Value::Object(o)) = inst.class.lookup(name) {
                    if let Object::Property { set, .. } = o.as_ref() {
                        if set.is_none() {
                            return Err(self.error(format!("property '{name}' has no setter"), "AttributeError"));
                        }
                        let set = set.clone();
                        self.call_value(&set, vec![obj.clone(), value], Vec::new())?;
                        return Ok(());
                    }
                }
                if let Some(setattr) = inst.class.lookup("__setattr__") {
                    let bound = self.bind(setattr, obj.clone(), &inst.class);
                    self.call_value(&bound, vec![Value::Str(name.clone()), value], Vec::new())?;
                    return Ok(());
                }
                self.raw_set(inst, name.clone(), value)
            }
            Value::Class(c) => {
                if shapes_lookup(name, Some(&value)) || c.attrs.borrow().get(name.as_ref()).is_some_and(|old| shapes_lookup(name, Some(old))) {
                    self.class_epoch += 1;
                }
                own(&value, c);
                c.attrs.borrow_mut().insert(name.clone(), value);
                Ok(())
            }
            // Functions take attributes too: `f.calls = 0`, or what functools.wraps copies.
            Value::Function(f) => {
                let mut attrs = f.attrs.borrow_mut();
                if attrs.len() >= self.max_container && !attrs.contains_key(name) {
                    drop(attrs);
                    return Err(self.container_abort("function"));
                }
                attrs.insert(name.clone(), value);
                Ok(())
            }
            // `sys.stdout = buffer` sends print's output to it until put back.
            Value::Module(m) if m.as_ref() == "sys" && matches!(name.as_ref(), "stdout" | "stderr") => {
                let target = match &value {
                    Value::Module(s) if s.as_ref() == "sys.stdout" || s.as_ref() == "sys.stderr" => None,
                    _ => Some(value),
                };
                if name.as_ref() == "stdout" {
                    self.stdout_target = target;
                } else {
                    self.stderr_target = target;
                }
                Ok(())
            }
            // A module written in Python takes new globals, as `mod.x = 1` gives it.
            Value::Module(m) if self.module_envs.contains_key(m.as_ref()) => {
                let env = self.module_envs[m.as_ref()].clone();
                env.vars.borrow_mut().insert(name.clone(), value);
                Ok(())
            }
            other => Err(self.error(
                format!("cannot set attribute '{name}' on '{}'; only class instances take new attributes", other.type_name()),
                "AttributeError",
            )),
        }
    }

    pub(crate) fn raw_set(&mut self, inst: &Rc<Instance>, name: Rc<str>, value: Value) -> R<()> {
        let mut attrs = inst.attrs.borrow_mut();
        if attrs.len() >= self.max_container && !attrs.contains_key(&name) {
            drop(attrs);
            return Err(self.container_abort("object"));
        }
        attrs.insert(name, value);
        Ok(())
    }

    /// `del obj.name`.
    pub(crate) fn del_attr(&mut self, obj: &Value, name: &str) -> R<()> {
        let removed = match obj {
            Value::Instance(inst) => {
                if let Some(delattr) = inst.class.lookup("__delattr__") {
                    let bound = self.bind(delattr, obj.clone(), &inst.class);
                    self.call_value(&bound, vec![Value::str(name)], Vec::new())?;
                    return Ok(());
                }
                inst.attrs.borrow_mut().shift_remove(name).is_some()
            }
            Value::Class(c) => {
                let old = c.attrs.borrow_mut().shift_remove(name);
                if old.as_ref().is_some_and(|v| shapes_lookup(name, Some(v))) {
                    self.class_epoch += 1;
                }
                old.is_some()
            }
            Value::Function(f) => f.attrs.borrow_mut().shift_remove(name).is_some(),
            _ => false,
        };
        if removed {
            Ok(())
        } else {
            Err(self.error(format!("'{}' object has no attribute '{name}'", obj.type_name()), "AttributeError"))
        }
    }

    // MARK: - operator methods

    /// Call [name] on [v] when it is an instance whose class defines it.
    pub(crate) fn dunder(&mut self, v: &Value, name: &str, args: Vec<Value>) -> R<Option<Value>> {
        if let Value::Instance(inst) = v {
            if let Some(m) = inst.class.lookup(name) {
                let bound = self.bind(m, v.clone(), &inst.class);
                return self.call_value(&bound, args, Vec::new()).map(Some);
            }
        }
        Ok(None)
    }

    /// `a op b` when either side is an instance.
    pub(crate) fn instance_binary(&mut self, op: BinOp, a: &Value, b: &Value) -> R<Value> {
        let (forward, reflected) = operator_methods(op);
        if let Some(v) = self.dunder(a, forward, vec![b.clone()])? {
            if !is_not_implemented(&v) {
                return Ok(v);
            }
        }
        if let Some(v) = self.dunder(b, reflected, vec![a.clone()])? {
            if !is_not_implemented(&v) {
                return Ok(v);
            }
        }
        Err(self.error(
            format!("unsupported operand type(s) for {forward}: '{}' and '{}'", a.type_name(), b.type_name()),
            "TypeError",
        ))
    }

    /// Python truth, asking `__bool__` and then `__len__` of an instance.
    pub(crate) fn truth(&mut self, v: &Value) -> R<bool> {
        if let Value::Instance(_) = v {
            if let Some(r) = self.dunder(v, "__bool__", Vec::new())? {
                return Ok(r.truthy());
            }
            if let Some(r) = self.dunder(v, "__len__", Vec::new())? {
                return Ok(r.as_int().unwrap_or(0) != 0);
            }
            return Ok(true);
        }
        Ok(v.truthy())
    }

    /// `==`, asking `__eq__` when either side is an instance.
    pub(crate) fn eq(&mut self, a: &Value, b: &Value) -> R<bool> {
        if matches!(a, Value::Instance(_)) || matches!(b, Value::Instance(_)) {
            for (x, y) in [(a, b), (b, a)] {
                if let Some(r) = self.dunder(x, "__eq__", vec![y.clone()])? {
                    if !is_not_implemented(&r) {
                        return self.truth(&r);
                    }
                }
            }
            return Ok(py_identical(a, b));
        }
        Ok(py_equals(a, b))
    }

    /// `<`, `<=`, `>`, `>=` when either side is an instance, with Python's reflection.
    pub(crate) fn instance_compare(&mut self, op: CmpOp, a: &Value, b: &Value) -> R<bool> {
        let (forward, reflected) = match op {
            CmpOp::Lt => ("__lt__", "__gt__"),
            CmpOp::Le => ("__le__", "__ge__"),
            CmpOp::Gt => ("__gt__", "__lt__"),
            _ => ("__ge__", "__le__"),
        };
        if let Some(r) = self.dunder(a, forward, vec![b.clone()])? {
            if !is_not_implemented(&r) {
                return self.truth(&r);
            }
        }
        if let Some(r) = self.dunder(b, reflected, vec![a.clone()])? {
            if !is_not_implemented(&r) {
                return self.truth(&r);
            }
        }
        Err(self.error(format!("'{forward}' not supported between '{}' and '{}'", a.type_name(), b.type_name()), "TypeError"))
    }

    /// `Cls[key]`: the class's `__class_getitem__`, implicitly a classmethod.
    pub(crate) fn class_getitem(&mut self, c: &Rc<Class>, key: &Value) -> R<Value> {
        if let Some(f) = self.meta_method(c, "__getitem__") {
            return self.call_value(&f, vec![key.clone()], Vec::new());
        }
        let Some(hook) = c.lookup("__class_getitem__") else {
            return Err(self.error(format!("type '{}' is not subscriptable", c.name), "TypeError"));
        };
        let f = match &hook {
            Value::Object(o) => match o.as_ref() {
                Object::ClassMethod(f) | Object::StaticMethod(f) => f.clone(),
                _ => hook.clone(),
            },
            _ => hook.clone(),
        };
        self.call_value(&f, vec![Value::Class(c.clone()), key.clone()], Vec::new())
    }

    /// `for member in SomeEnum`: a class that keeps its members in
    /// `_member_map_` (what the `enum` module does) iterates over them.
    pub(crate) fn iterate_class(&mut self, c: &Rc<Class>) -> R<Vec<Value>> {
        if let Some(f) = self.meta_method(c, "__iter__") {
            let it = self.call_value(&f, Vec::new(), Vec::new())?;
            return self.iterate(&it);
        }
        match c.lookup("_member_map_") {
            Some(Value::Dict(d)) => Ok(d.borrow().values().cloned().collect()),
            _ => Err(self.error(format!("type object '{}' is not iterable", c.name), "TypeError")),
        }
    }

    /// Every item `for x in inst` would see, held to the container cap.
    pub(crate) fn iterate_instance(&mut self, v: &Value) -> R<Vec<Value>> {
        let mut it = self.instance_iter(v)?;
        self.collect_iter(&mut it, "iterator")
    }

    /// The (key, value) pairs of a dict, or of an object with `keys()` and
    /// `__getitem__`: what `**mapping` and `dict(mapping)` read.
    pub(crate) fn mapping_pairs(&mut self, v: &Value) -> R<Vec<(HKey, Value)>> {
        match v {
            Value::Dict(d) => Ok(d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect()),
            Value::Instance(_) => {
                let keys = self.call_method(v, "keys", Vec::new(), Vec::new())?;
                let mut out = Vec::new();
                for k in self.iterate(&keys)? {
                    let item = self.dunder(v, "__getitem__", vec![k.clone()])?.unwrap_or(Value::None);
                    out.push((self.hashable(&k)?, item));
                }
                Ok(out)
            }
            other => Err(self.error(format!("'{}' object is not a mapping", other.type_name()), "TypeError")),
        }
    }

    // MARK: - str and repr

    /// `str(v)`, asking `__str__` (then `__repr__`) of instances.
    pub(crate) fn to_str(&mut self, v: &Value) -> R<String> {
        match v {
            Value::Instance(inst) => {
                for name in ["__str__", "__repr__"] {
                    if let Some(m) = inst.class.lookup(name) {
                        let bound = self.bind(m, v.clone(), &inst.class);
                        return match self.call_value(&bound, Vec::new(), Vec::new())? {
                            Value::Str(s) => Ok(s.to_string()),
                            other => Err(self.error(format!("{name} returned non-string (type {})", other.type_name()), "TypeError")),
                        };
                    }
                }
                Ok(py_str(v))
            }
            Value::List(_) | Value::Tuple(_) | Value::Dict(_) | Value::Set(_) => self.to_repr(v),
            Value::Object(o) if matches!(o.as_ref(), Object::DictView(..)) => self.to_repr(v),
            Value::Class(c) if c.meta.is_some() => {
                for name in ["__str__", "__repr__"] {
                    if let Some(m) = self.meta_method(c, name) {
                        return match self.call_value(&m, Vec::new(), Vec::new())? {
                            Value::Str(s) => Ok(s.to_string()),
                            other => Err(self.error(format!("{name} returned non-string (type {})", other.type_name()), "TypeError")),
                        };
                    }
                }
                Ok(py_str(v))
            }
            _ => Ok(py_str(v)),
        }
    }

    /// `repr(v)`, asking `__repr__` of instances, down through containers.
    pub(crate) fn to_repr(&mut self, v: &Value) -> R<String> {
        self.repr_depth += 1;
        let r = if self.repr_depth >= REPR_NESTING { Ok(None) } else { self.repr_inner(v).map(Some) };
        self.repr_depth -= 1;
        Ok(match r? {
            Some(s) => s,
            None => match v {
                Value::List(_) => "[...]".into(),
                Value::Tuple(_) => "(...)".into(),
                Value::Dict(_) => "{...}".into(),
                other => py_repr(other),
            },
        })
    }

    fn repr_inner(&mut self, v: &Value) -> R<String> {
        Ok(match v {
            Value::Object(o) if matches!(o.as_ref(), Object::DictView(..)) => {
                let Object::DictView(k, d) = o.as_ref() else { unreachable!() };
                format!("{}({})", k.type_name(), self.to_repr(&Value::list(view_items(*k, d)))?)
            }
            Value::Class(c) if c.meta.is_some() => match self.meta_method(c, "__repr__") {
                Some(m) => match self.call_value(&m, Vec::new(), Vec::new())? {
                    Value::Str(s) => s.to_string(),
                    other => return Err(self.error(format!("__repr__ returned non-string (type {})", other.type_name()), "TypeError")),
                },
                None => py_repr(v),
            },
            Value::Instance(inst) => match inst.class.lookup("__repr__") {
                Some(m) => {
                    let bound = self.bind(m, v.clone(), &inst.class);
                    match self.call_value(&bound, Vec::new(), Vec::new())? {
                        Value::Str(s) => s.to_string(),
                        other => return Err(self.error(format!("__repr__ returned non-string (type {})", other.type_name()), "TypeError")),
                    }
                }
                None => py_repr(v),
            },
            Value::List(l) => {
                let items = l.borrow().clone();
                let mut parts = Vec::with_capacity(items.len());
                for i in &items {
                    parts.push(self.to_repr(i)?);
                }
                format!("[{}]", parts.join(", "))
            }
            Value::Tuple(t) => {
                let mut parts = Vec::with_capacity(t.len());
                for i in t.iter() {
                    parts.push(self.to_repr(i)?);
                }
                if parts.len() == 1 {
                    format!("({},)", parts[0])
                } else {
                    format!("({})", parts.join(", "))
                }
            }
            Value::Dict(d) => {
                let pairs: Vec<(HKey, Value)> = d.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                let mut parts = Vec::with_capacity(pairs.len());
                for (k, v) in &pairs {
                    parts.push(format!("{}: {}", self.to_repr(&k.to_value())?, self.to_repr(v)?));
                }
                format!("{{{}}}", parts.join(", "))
            }
            Value::Set(s) => {
                let items: Vec<Value> = s.borrow().iter().map(HKey::to_value).collect();
                if items.is_empty() {
                    "set()".into()
                } else {
                    let mut parts = Vec::with_capacity(items.len());
                    for i in &items {
                        parts.push(self.to_repr(i)?);
                    }
                    format!("{{{}}}", parts.join(", "))
                }
            }
            other => py_repr(other),
        })
    }

    // MARK: - exceptions as classes

    /// Whether `except [clause]` catches an error of [kind], counting the
    /// script's own exception classes and their bases.
    pub(crate) fn exception_is(&self, clause: &str, kind: &str) -> bool {
        if exception_matches(clause, kind) {
            return true;
        }
        if let Some(c) = self.exception_classes.get(kind) {
            if c.lineage().iter().any(|n| n.as_ref() == clause) {
                return true;
            }
            if let Some(root) = &c.exception_root {
                return exception_matches(clause, root);
            }
        }
        false
    }

    /// `raise inst`: remember the instance so `except … as e` hands back the
    /// same object, and unwind with its class name and message.
    pub(crate) fn raise_instance(&mut self, inst: &Rc<Instance>) -> Exc {
        if inst.class.exception_root.is_none() {
            return self.error("exceptions must derive from Exception", "TypeError");
        }
        let value = Value::Instance(inst.clone());
        let message = match self.to_str(&value) {
            Ok(m) => m,
            Err(e) => return e,
        };
        self.raised = Some((inst.class.name.clone(), message.clone(), value));
        self.error(message, &inst.class.name)
    }

    /// What `except … as e` binds for [error]: the instance that was raised,
    /// when it was one, or a built-in exception value.
    pub(crate) fn caught_value(&mut self, error: &PyError) -> Value {
        if let Some((kind, message, value)) = &self.raised {
            if kind.as_ref() == error.kind && *message == error.message {
                return value.clone();
            }
        }
        if let Some(class) = self.exception_classes.get(error.kind.as_str()).cloned() {
            // Raised by name from the host (a PermissionError, an HTTPError):
            // make it an instance of the class the script can match on.
            let inst = Rc::new(Instance { class, attrs: RefCell::new(name_map(0)) });
            inst.attrs.borrow_mut().insert(Rc::from("args"), Value::tuple(vec![Value::str(&error.message)]));
            self.track_instance(&inst);
            return Value::Instance(inst);
        }
        Value::Exception(Rc::new(ExceptionValue::new(error.kind.clone(), error.message.clone())))
    }

    // MARK: - modules written in Python

    /// Whether [name] names a module a script could import.
    pub(crate) fn module_exists(&self, name: &str) -> bool {
        self.enabled_modules.contains(&name)
            || self.host_modules.contains_key(name)
            || self.source_modules.contains_key(name)
            || self.module_envs.contains_key(name)
            || self.is_package_prefix(name)
    }

    fn is_package_prefix(&self, name: &str) -> bool {
        let prefix = format!("{name}.");
        self.source_modules.keys().any(|k| k.starts_with(&prefix)) || self.host_modules.keys().any(|k| k.starts_with(&prefix))
    }

    /// `.x` and `..x` resolved against the module doing the importing.
    pub(crate) fn resolve_module(&self, name: &str) -> R<Rc<str>> {
        if !name.starts_with('.') {
            return Ok(Rc::from(name));
        }
        let dots = name.chars().take_while(|c| *c == '.').count();
        let rest = &name[dots..];
        let current = self.current_module.as_ref();
        let mut package: Vec<&str> = if current == "__main__" {
            Vec::new()
        } else if self.packages.contains(current) {
            current.split('.').collect()
        } else {
            let mut parts: Vec<&str> = current.split('.').collect();
            parts.pop();
            parts
        };
        for _ in 1..dots {
            if package.pop().is_none() {
                return Err(self.error("attempted relative import beyond the top", "ImportError"));
            }
        }
        if !rest.is_empty() {
            package.push(rest);
        }
        if package.is_empty() {
            return Err(self.error("a relative import needs a module name here", "ImportError"));
        }
        Ok(Rc::from(package.join(".")))
    }

    /// Load [name] and each package above it, running Python sources once.
    pub(crate) fn ensure_module(&mut self, name: &str) -> R<()> {
        let parts: Vec<&str> = name.split('.').collect();
        for i in 1..=parts.len() {
            let prefix = parts[..i].join(".");
            if !self.module_exists(&prefix) {
                return Err(self.error(
                    format!("no module named '{prefix}' (available: {})", self.importable_modules().join(", ")),
                    "ImportError",
                ));
            }
            if self.module_envs.contains_key(&prefix) {
                continue;
            }
            if let Some(source) = self.source_modules.get(&prefix).cloned() {
                self.load_source(&prefix, &source)?;
            } else if !self.host_modules.contains_key(&prefix) && !self.enabled_modules.contains(&prefix.as_str()) {
                // A folder of modules with no __init__.py: an empty namespace.
                let env = Env::new(None);
                env.define(&Rc::from("__name__"), Value::str(&prefix));
                self.module_envs.insert(prefix, env);
            }
        }
        Ok(())
    }

    fn load_source(&mut self, name: &str, source: &str) -> R<()> {
        let program = parse(source).map_err(|e| {
            let message = format!("in module '{name}': {}", e.message);
            PyError::at(message, &e.kind, e.line)
        })?;
        let env = Env::new(None);
        env.define(&Rc::from("__name__"), Value::str(name));
        // Registered before it runs, so a cycle sees the half-made module rather than looping.
        self.module_envs.insert(name.to_string(), env.clone());
        self.closures.push(Rc::downgrade(&env));
        let saved_module = std::mem::replace(&mut self.current_module, Rc::from(name));
        let saved_line = self.current_line;
        let r = self.exec_block(&program, &env);
        self.current_module = saved_module;
        self.current_line = saved_line;
        if let Err(e) = r {
            self.module_envs.remove(name);
            return Err(e);
        }
        Ok(())
    }

    /// A member of a module written in Python, or a submodule already loaded.
    pub(crate) fn source_member(&self, module: &str, name: &str) -> Option<Value> {
        if let Some(env) = self.module_envs.get(module) {
            if let Some(v) = env.vars.borrow().get(name) {
                return Some(v.clone());
            }
        }
        let sub = format!("{module}.{name}");
        if self.module_envs.contains_key(&sub) || self.host_modules.contains_key(&sub) {
            return Some(Value::Module(Rc::from(sub)));
        }
        None
    }

    // MARK: - built-ins that classes brought

    /// The built-ins added with classes. None when [name] is not one of them.
    #[inline(never)]
    pub(crate) fn class_builtin(&mut self, name: &str, args: &[Value], kwargs: &[(Rc<str>, Value)]) -> R<Option<Value>> {
        let arg = |i: usize| args.get(i).cloned();
        Ok(Some(match name {
            "getattr" => {
                if args.len() < 2 || args.len() > 3 {
                    return Err(self.error("getattr() takes 2 or 3 arguments", "TypeError"));
                }
                let attr = self.attr_name(&args[1], "getattr")?;
                match self.attribute(&args[0], &attr) {
                    Ok(v) => v,
                    Err(Exc::Error(e)) if args.len() == 3 && !e.is_abort() && e.kind == "AttributeError" => args[2].clone(),
                    Err(e) => return Err(e),
                }
            }
            "hasattr" => {
                self.arity(name, args, 2)?;
                let attr = self.attr_name(&args[1], "hasattr")?;
                match self.attribute(&args[0], &attr) {
                    Ok(_) => Value::Bool(true),
                    Err(Exc::Error(e)) if !e.is_abort() && e.kind == "AttributeError" => Value::Bool(false),
                    Err(e) => return Err(e),
                }
            }
            "setattr" => {
                self.arity(name, args, 3)?;
                let attr = self.attr_name(&args[1], "setattr")?;
                self.set_attr(&args[0], &Rc::from(attr.as_str()), args[2].clone())?;
                Value::None
            }
            "delattr" => {
                self.arity(name, args, 2)?;
                let attr = self.attr_name(&args[1], "delattr")?;
                self.del_attr(&args[0], &attr)?;
                Value::None
            }
            "vars" => {
                self.arity(name, args, 1)?;
                match &args[0] {
                    Value::Instance(i) => {
                        let pairs: Vec<(Rc<str>, Value)> = i.attrs.borrow().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
                        Value::dict(pairs)
                    }
                    Value::Class(c) => self.class_attr(c, "__dict__").unwrap_or(Value::None),
                    other => return Err(self.error(format!("vars() argument must have __dict__, not '{}'", other.type_name()), "TypeError")),
                }
            }
            "iter" => {
                self.arity(name, args, 1)?;
                match &args[0] {
                    Value::Instance(_) => match self.dunder(&args[0], "__iter__", Vec::new())? {
                        Some(it @ Value::Instance(_)) => it,
                        Some(Value::Object(o)) if matches!(o.as_ref(), Object::Iterator(_) | Object::Generator(_)) => Value::Object(o),
                        Some(other) => {
                            let it = self.iter_of(&other)?;
                            iterator_object("iterator", it)
                        }
                        None => {
                            let it = self.instance_iter(&args[0])?;
                            iterator_object("iterator", it)
                        }
                    },
                    Value::Object(o) if matches!(o.as_ref(), Object::Iterator(_) | Object::Generator(_)) => args[0].clone(),
                    other => {
                        let kind = match other {
                            Value::List(_) => "list_iterator",
                            Value::Tuple(_) => "tuple_iterator",
                            Value::Str(_) => "str_ascii_iterator",
                            Value::Dict(_) => "dict_keyiterator",
                            Value::Set(_) => "set_iterator",
                            Value::Object(o) if matches!(o.as_ref(), Object::Range(..)) => "range_iterator",
                            _ => "iterator",
                        };
                        let it = self.iter_of(other)?;
                        iterator_object(kind, it)
                    }
                }
            }
            "next" => {
                if args.is_empty() || args.len() > 2 {
                    return Err(self.error("next() takes 1 or 2 arguments", "TypeError"));
                }
                let item = match &args[0] {
                    Value::Object(o) => match o.as_ref() {
                        Object::Generator(_) => match self.generator_method(o, "__next__", &[]) {
                            Ok(v) => Some(v),
                            Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" && args.len() == 2 => None,
                            Err(e) => return Err(e),
                        },
                        Object::Iterator(_) => {
                            let o = o.clone();
                            self.object_next(&o)?
                        }
                        _ => return Err(self.error(format!("'{}' object is not an iterator", args[0].type_name()), "TypeError")),
                    },
                    Value::Instance(_) => match self.dunder(&args[0], "__next__", Vec::new()) {
                        Ok(Some(v)) => Some(v),
                        Ok(None) => return Err(self.error(format!("'{}' object is not an iterator", args[0].type_name()), "TypeError")),
                        Err(Exc::Error(e)) if !e.is_abort() && e.kind == "StopIteration" && args.len() == 2 => None,
                        Err(e) => return Err(e),
                    },
                    other => return Err(self.error(format!("'{}' object is not an iterator", other.type_name()), "TypeError")),
                };
                match item {
                    Some(v) => v,
                    None => match arg(1) {
                        Some(d) => d,
                        None => return Err(self.error("", "StopIteration")),
                    },
                }
            }
            "property" => {
                let get = arg(0).or_else(|| kwarg(kwargs, "fget")).unwrap_or(Value::None);
                let set = arg(1).or_else(|| kwarg(kwargs, "fset")).unwrap_or(Value::None);
                object(Object::Property { get, set })
            }
            "staticmethod" => {
                self.arity(name, args, 1)?;
                object(Object::StaticMethod(args[0].clone()))
            }
            "classmethod" => {
                self.arity(name, args, 1)?;
                object(Object::ClassMethod(args[0].clone()))
            }
            "super" => self.make_super(args)?,
            "object" => {
                self.arity(name, args, 0)?;
                let class = self.object_class();
                self.construct(&class, Vec::new(), Vec::new())?
            }
            "issubclass" => {
                self.arity(name, args, 2)?;
                Value::Bool(self.subclass_check(&args[0], &args[1])?)
            }
            "id" => {
                self.arity(name, args, 1)?;
                Value::Int(match HKey::from_value(&args[0]) {
                    Some(HKey::Ref(_)) | None => identity(&args[0]),
                    Some(k) => {
                        use std::hash::{Hash, Hasher};
                        let mut h = std::collections::hash_map::DefaultHasher::new();
                        k.hash(&mut h);
                        (h.finish() >> 1) as i64
                    }
                })
            }
            "hash" => {
                self.arity(name, args, 1)?;
                Value::Int(self.hash_of(&args[0])?)
            }
            HIDDEN_BASE_INIT => {
                if let Some(Value::Instance(inst)) = args.first() {
                    if inst.class.exception_root.is_some() {
                        inst.attrs.borrow_mut().insert(Rc::from("args"), Value::tuple(args[1..].to_vec()));
                    }
                }
                Value::None
            }
            HIDDEN_OBJECT_SETATTR => {
                let (Some(Value::Instance(inst)), Some(attr), Some(value)) = (arg(0), arg(1), arg(2)) else {
                    return Err(self.error("__setattr__ needs an object, a name and a value", "TypeError"));
                };
                let attr = self.attr_name(&attr, "__setattr__")?;
                self.raw_set(&inst, Rc::from(attr.as_str()), value)?;
                Value::None
            }
            HIDDEN_OBJECT_GETATTR => {
                let (Some(Value::Instance(inst)), Some(attr)) = (arg(0), arg(1)) else {
                    return Err(self.error("__getattribute__ needs an object and a name", "TypeError"));
                };
                let attr = self.attr_name(&attr, "__getattribute__")?;
                let own = inst.attrs.borrow().get(attr.as_str()).cloned();
                match own.or_else(|| inst.class.lookup(&attr).map(|v| self.bind(v, Value::Instance(inst.clone()), &inst.class))) {
                    Some(v) => v,
                    None => return Err(self.error(format!("'{}' object has no attribute '{attr}'", inst.class.name), "AttributeError")),
                }
            }
            HIDDEN_TYPE_NEW => {
                let meta = match arg(0) {
                    Some(Value::Class(m)) if m.is_type => Some(m),
                    Some(Value::Builtin(n)) if n.as_ref() == "type" => None,
                    _ => return Err(self.error("type.__new__(X): X is not a type object", "TypeError")),
                };
                return self.type_new(meta, &args[1..], kwargs).map(Some);
            }
            HIDDEN_TYPE_CALL => {
                let Some(Value::Class(c)) = arg(0) else {
                    return Err(self.error("type.__call__(X): X is not a class", "TypeError"));
                };
                return self.construct_plain(&c, args[1..].to_vec(), kwargs.to_vec()).map(Some);
            }
            HIDDEN_TYPE_INIT => Value::None,
            HIDDEN_OBJECT_INIT_SUBCLASS => {
                if !kwargs.is_empty() {
                    let who = match arg(0) {
                        Some(Value::Class(c)) => c.name.to_string(),
                        _ => "object".to_string(),
                    };
                    return Err(self.error(format!("{who}.__init_subclass__() takes no keyword arguments"), "TypeError"));
                }
                Value::None
            }
            HIDDEN_OBJECT_NEW => {
                // `object.__new__(cls)`: an instance with no attributes and no __init__ run.
                let Some(Value::Class(c)) = arg(0) else {
                    return Err(self.error("object.__new__(X): X is not a class", "TypeError"));
                };
                let inst = Rc::new(Instance { class: c.clone(), attrs: RefCell::new(name_map(0)) });
                self.track_instance(&inst);
                Value::Instance(inst)
            }
            HIDDEN_DEFAULT_STR => Value::str(py_str(&arg(0).unwrap_or(Value::None))),
            HIDDEN_DEFAULT_REPR => Value::str(py_repr(&arg(0).unwrap_or(Value::None))),
            HIDDEN_PROPERTY_SETTER | HIDDEN_PROPERTY_GETTER => {
                let (Some(Value::Object(p)), Some(f)) = (arg(0), arg(1)) else {
                    return Err(self.error("setter() needs a function", "TypeError"));
                };
                let Object::Property { get, set } = p.as_ref() else {
                    return Err(self.error("setter() belongs to a property", "TypeError"));
                };
                if name == HIDDEN_PROPERTY_SETTER {
                    object(Object::Property { get: get.clone(), set: f })
                } else {
                    object(Object::Property { get: f, set: set.clone() })
                }
            }
            _ => return Ok(None),
        }))
    }

    fn attr_name(&self, v: &Value, func: &str) -> R<String> {
        match v {
            Value::Str(s) => Ok(s.to_string()),
            other => Err(self.error(format!("{func}(): attribute name must be string, not '{}'", other.type_name()), "TypeError")),
        }
    }

    /// `super()` inside a method, or `super(Class, obj)`.
    fn make_super(&mut self, args: &[Value]) -> R<Value> {
        if args.len() == 2 {
            let Value::Class(c) = &args[0] else {
                return Err(self.error("super() argument 1 must be a class", "TypeError"));
            };
            return Ok(object(Object::Super { class: c.clone(), receiver: args[1].clone() }));
        }
        if !args.is_empty() {
            return Err(self.error("super() takes no arguments, or a class and an object", "TypeError"));
        }
        let Some((f, env)) = self.frames.last().cloned() else {
            return Err(self.error("super(): no arguments outside a method", "RuntimeError"));
        };
        let owner = f.owner.borrow().as_ref().and_then(|w| w.upgrade());
        let Some(class) = owner else {
            return Err(self.error("super() works only inside a method defined in a class body", "RuntimeError"));
        };
        // The method's first parameter, which is its first slot.
        let receiver = if f.signature().positional.is_empty() { None } else { env.slots.borrow().first().cloned().flatten() };
        let Some(receiver) = receiver else {
            return Err(self.error("super(): the method has no 'self' argument", "RuntimeError"));
        };
        Ok(object(Object::Super { class, receiver }))
    }

    fn object_class(&mut self) -> Rc<Class> {
        if let Some(c) = &self.plain_object {
            return c.clone();
        }
        let c = Rc::new(Class {
            name: Rc::from("object"),
            bases: Vec::new(),
            mro: Vec::new(),
            exception_root: None,
            attrs: RefCell::new(name_map(0)),
            traits: Default::default(),
            meta: None,
            is_type: false,
        });
        self.plain_object = Some(c.clone());
        c
    }

    /// `isinstance(v, ty)` and `except`-style matching, for classes and the built-in types.
    pub(crate) fn is_instance(&self, value: &Value, ty: &Value) -> bool {
        match ty {
            Value::Tuple(t) => t.iter().any(|x| self.is_instance(value, x)),
            Value::Class(c) => match value {
                Value::Instance(i) => i.class.is_subclass_of(c),
                Value::Class(k) => k.meta.as_ref().is_some_and(|m| m.is_subclass_of(c)),
                _ => false,
            },
            Value::Builtin(name) => match name.as_ref() {
                "str" => matches!(value, Value::Str(_)),
                "int" => matches!(value, Value::Int(_) | Value::Bool(_)),
                "float" => matches!(value, Value::Float(_)),
                "bool" => matches!(value, Value::Bool(_)),
                "list" => matches!(value, Value::List(_)),
                "tuple" => matches!(value, Value::Tuple(_)),
                "dict" => matches!(value, Value::Dict(_)),
                "set" => matches!(value, Value::Set(_)),
                "bytes" => matches!(value, Value::Bytes(_)),
                "slice" => matches!(value, Value::Object(o) if matches!(o.as_ref(), Object::Slice(..))),
                "range" => matches!(value, Value::Object(o) if matches!(o.as_ref(), Object::Range(..))),
                "type" => matches!(value, Value::Class(_)) || matches!(value, Value::Builtin(n) if super::builtins::is_type_name(n)),
                "object" => true,
                kind if EXCEPTION_KINDS.contains(&kind) => match value {
                    Value::Exception(e) => self.exception_is(kind, &e.kind),
                    Value::Instance(i) => self.exception_is(kind, &i.class.name),
                    _ => false,
                },
                // The other built-in types: function, generator, module and the like.
                other if super::builtins::is_type_name(other) => {
                    matches!(self.type_of(value), Value::Builtin(t) if t.as_ref() == other)
                }
                _ => false,
            },
            _ => false,
        }
    }

    /// `issubclass(sub, ty)`, for classes and the built-in types, asking a
    /// metaclass's `__subclasscheck__` when the plain answer is no.
    pub(crate) fn subclass_check(&mut self, sub: &Value, ty: &Value) -> R<bool> {
        if let Value::Tuple(t) = ty {
            for x in t.iter() {
                if self.subclass_check(sub, x)? {
                    return Ok(true);
                }
            }
            return Ok(false);
        }
        let plain = match sub {
            Value::Class(c) => match ty {
                Value::Builtin(n) if n.as_ref() == "type" => c.is_type,
                _ => self.class_matches(c, ty),
            },
            Value::Builtin(n) if super::builtins::is_type_name(n) => match ty {
                Value::Builtin(m) => {
                    n == m
                        || m.as_ref() == "object"
                        || (n.as_ref() == "bool" && m.as_ref() == "int")
                        || (EXCEPTION_KINDS.contains(&n.as_ref()) && EXCEPTION_KINDS.contains(&m.as_ref()) && self.exception_is(m, n))
                }
                _ => false,
            },
            _ => return Err(self.error("issubclass() arg 1 must be a class", "TypeError")),
        };
        if plain {
            return Ok(true);
        }
        if let Value::Class(c) = ty {
            if let Some(hook) = self.meta_method(c, "__subclasscheck__") {
                let r = self.call_value(&hook, vec![sub.clone()], Vec::new())?;
                return self.truth(&r);
            }
        }
        Ok(false)
    }

    /// `dir(obj)`: the names its attributes go by, sorted.
    pub(crate) fn dir_of(&mut self, v: &Value) -> R<Value> {
        if let Value::Instance(_) = v {
            if let Some(r) = self.dunder(v, "__dir__", Vec::new())? {
                let mut names = self.iterate(&r)?;
                names.sort_by_key(py_str);
                return Ok(Value::list(names));
            }
        }
        let mut names: Vec<String> = Vec::new();
        let from_class = |c: &Rc<Class>, names: &mut Vec<String>| {
            for k in std::iter::once(c).chain(c.mro.iter()) {
                names.extend(k.attrs.borrow().keys().map(|s| s.to_string()));
            }
        };
        match v {
            Value::Instance(i) => {
                names.extend(i.attrs.borrow().keys().map(|s| s.to_string()));
                from_class(&i.class, &mut names);
                names.extend(["__class__", "__dict__", "__repr__", "__str__"].map(String::from));
            }
            Value::Class(c) => {
                from_class(c, &mut names);
                names.extend(["__name__", "__qualname__", "__mro__", "__bases__", "__dict__", "__doc__"].map(String::from));
            }
            Value::Module(m) => {
                let m = m.clone();
                if let Some(env) = self.module_envs.get(m.as_ref()) {
                    names.extend(env.vars.borrow().pairs().into_iter().map(|(k, _)| k.to_string()));
                } else if let Some(h) = self.host_modules.get(m.as_ref()) {
                    names.extend(h.names());
                } else {
                    names.extend(super::modules::exports(&m).into_iter().map(String::from));
                }
            }
            other => {
                let table: &[&str] = match other {
                    Value::Str(_) => super::methods::STR_METHODS,
                    Value::List(_) => super::methods::LIST_METHODS,
                    Value::Tuple(_) => super::methods::TUPLE_METHODS,
                    Value::Set(_) => super::methods::SET_METHODS,
                    Value::Dict(_) => super::methods::DICT_METHODS,
                    Value::Bytes(_) => super::methods::BYTES_METHODS,
                    Value::Int(_) | Value::Bool(_) => super::methods::INT_METHODS,
                    Value::Float(_) => super::methods::FLOAT_METHODS,
                    _ => &[],
                };
                names.extend(table.iter().map(|s| s.to_string()));
                if matches!(other, Value::Int(_) | Value::Bool(_)) {
                    names.extend(["real", "imag", "numerator", "denominator"].map(String::from));
                }
                if let Value::Float(_) = other {
                    names.extend(["real", "imag"].map(String::from));
                }
            }
        }
        names.sort();
        names.dedup();
        Ok(Value::list(names.into_iter().map(Value::str).collect()))
    }

    /// `isinstance(v, ty)` once the plain answer was no: a metaclass's `__instancecheck__`.
    pub(crate) fn instance_hook(&mut self, v: &Value, ty: &Value) -> R<bool> {
        match ty {
            Value::Tuple(t) => {
                for x in t.iter() {
                    if self.instance_hook(v, x)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            Value::Class(c) => match self.meta_method(c, "__instancecheck__") {
                Some(h) => {
                    let r = self.call_value(&h, vec![v.clone()], Vec::new())?;
                    self.truth(&r)
                }
                None => Ok(false),
            },
            _ => Ok(false),
        }
    }

    fn class_matches(&self, c: &Rc<Class>, ty: &Value) -> bool {
        match ty {
            Value::Tuple(t) => t.iter().any(|x| self.class_matches(c, x)),
            Value::Class(other) => c.is_subclass_of(other),
            Value::Builtin(n) if n.as_ref() == "object" => true,
            Value::Builtin(n) => self.exception_is(n, &c.name),
            _ => false,
        }
    }
}

/// A property somewhere in the class or its bases: attribute reads and
/// writes on its instances ask the class first.
pub(crate) const HAS_PROPERTY: u8 = 1;
/// `__setattr__` somewhere in the class or its bases.
pub(crate) const HAS_SETATTR: u8 = 2;

/// Whether setting or deleting [name] (to [value]) on a class changes how
/// its instances' attributes are looked up: a property, or a dunder.
fn shapes_lookup(name: &str, value: Option<&Value>) -> bool {
    name.starts_with("__") || matches!(value, Some(Value::Object(o)) if matches!(o.as_ref(), Object::Property { .. }))
}

impl Interp {
    /// What about a class decides its instances' attribute lookups, worked
    /// out once and again only after some class gains or loses a property
    /// or a dunder.
    pub(crate) fn class_traits(&self, c: &Class) -> u8 {
        if let Some((epoch, bits)) = c.traits.get() {
            if epoch == self.class_epoch {
                return bits;
            }
        }
        let mut bits = 0;
        for k in std::iter::once(c).chain(c.mro.iter().map(|r| r.as_ref())) {
            for (name, v) in k.attrs.borrow().iter() {
                if let Value::Object(o) = v {
                    if matches!(o.as_ref(), Object::Property { .. }) {
                        bits |= HAS_PROPERTY;
                    }
                }
                if name.as_ref() == "__setattr__" {
                    bits |= HAS_SETATTR;
                }
            }
        }
        c.traits.set(Some((self.class_epoch, bits)));
        bits
    }
}

/// Mark every function [v] holds as defined in [class], for `super()`.
fn own(v: &Value, class: &Rc<Class>) {
    match v {
        Value::Function(f) => {
            if f.owner.borrow().is_none() {
                *f.owner.borrow_mut() = Some(Rc::downgrade(class));
            }
        }
        Value::Object(o) => match o.as_ref() {
            Object::StaticMethod(f) | Object::ClassMethod(f) => own(f, class),
            Object::Property { get, set } => {
                own(get, class);
                own(set, class);
            }
            _ => {}
        },
        _ => {}
    }
}

/// Python's C3 linearisation: the order attributes are looked for in, after
/// the class itself, for a class with these bases. None when the bases
/// cannot be put in one order consistent with each of theirs.
fn c3_order(bases: &[Rc<Class>]) -> Option<Vec<Rc<Class>>> {
    let mut seqs: Vec<Vec<Rc<Class>>> =
        bases.iter().map(|b| std::iter::once(b.clone()).chain(b.mro.iter().cloned()).collect()).collect();
    seqs.push(bases.to_vec());
    let mut out: Vec<Rc<Class>> = Vec::new();
    loop {
        seqs.retain(|s| !s.is_empty());
        if seqs.is_empty() {
            return Some(out);
        }
        // The first head that is in no other list's tail.
        let next = seqs.iter().map(|s| s[0].clone()).find(|c| !seqs.iter().any(|s| s[1..].iter().any(|x| Rc::ptr_eq(x, c))))?;
        for s in &mut seqs {
            if Rc::ptr_eq(&s[0], &next) {
                s.remove(0);
            }
        }
        out.push(next);
    }
}

fn identity(v: &Value) -> i64 {
    let p = match v {
        Value::List(l) => Rc::as_ptr(l) as *const u8 as usize,
        Value::Dict(d) => Rc::as_ptr(d) as *const u8 as usize,
        Value::Set(s) => Rc::as_ptr(s) as *const u8 as usize,
        Value::Class(c) => Rc::as_ptr(c) as *const u8 as usize,
        Value::Instance(i) => Rc::as_ptr(i) as *const u8 as usize,
        Value::Function(f) => Rc::as_ptr(f) as *const u8 as usize,
        Value::Object(o) => Rc::as_ptr(o) as *const u8 as usize,
        _ => 0,
    };
    (p >> 3) as i64
}

fn kwarg(kwargs: &[(Rc<str>, Value)], name: &str) -> Option<Value> {
    kwargs.iter().find(|(k, _)| k.as_ref() == name).map(|(_, v)| v.clone())
}
