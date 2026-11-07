//! Port of PyHost.kt: Rust functions and modules scripts can call, and the
//! conversions between Rust values and [`Value`]. A host function is the only
//! way a script reaches beyond itself, so the sandbox is exactly as wide as
//! the functions a host defines.

use std::collections::HashMap;
use std::rc::Rc;

use indexmap::IndexMap;

use super::error::{Exc, PyError};
use super::eval::Interp;
use super::parser::parse;
use super::value::{HKey, Value};

/// What a host function is: it reads its [`Call`] and returns a value or a
/// catchable [`PyError`].
pub type HostFn = dyn Fn(&mut Call) -> Result<Value, PyError>;

/// A Rust function exposed to scripts, as a global or as a module member.
pub struct HostFunction {
    pub name: String,
    pub(crate) body: Box<HostFn>,
}

impl HostFunction {
    pub fn new(name: impl Into<String>, body: impl Fn(&mut Call) -> Result<Value, PyError> + 'static) -> Rc<HostFunction> {
        Rc::new(HostFunction { name: name.into(), body: Box::new(body) })
    }
}

/// A module a script can import (or reach through a global), filled in by the host.
/// A constant may be `Value::Module("wm.storage")` naming another host module,
/// which is how `wm.storage.get(...)` works.
pub struct HostModule {
    pub name: String,
    pub(crate) functions: HashMap<String, Rc<HostFunction>>,
    pub(crate) constants: HashMap<String, Value>,
}

impl HostModule {
    pub fn new(name: impl Into<String>) -> HostModule {
        HostModule { name: name.into(), functions: HashMap::new(), constants: HashMap::new() }
    }

    /// A function scripts call as `module.name(...)`.
    pub fn define(&mut self, name: &str, body: impl Fn(&mut Call) -> Result<Value, PyError> + 'static) {
        self.functions.insert(name.to_string(), HostFunction::new(name, body));
    }

    /// A value scripts read as `module.name`.
    pub fn set(&mut self, name: &str, value: impl Into<Value>) {
        self.constants.insert(name.to_string(), value.into());
    }

    pub(crate) fn member(&self, name: &str) -> Option<Value> {
        if let Some(c) = self.constants.get(name) {
            return Some(c.clone());
        }
        self.functions.get(name).map(|f| Value::HostFunction(f.clone()))
    }

    /// Every member name, sorted.
    pub fn names(&self) -> Vec<String> {
        let mut out: Vec<String> = self.functions.keys().chain(self.constants.keys()).cloned().collect();
        out.sort();
        out
    }
}

/// One call from a script into a host function.
pub struct Call<'a> {
    /// The name the function was registered under.
    pub name: &'a str,
    pub args: Vec<Value>,
    pub kwargs: Vec<(Rc<str>, Value)>,
    /// The script line that made the call.
    pub line: u32,
    pub(crate) interp: &'a mut Interp,
}

/// A Rust type a [`Value`] can be read as. Ints read as i64 and accept bools;
/// floats accept ints, as in PyMini.
pub trait FromPy: Sized {
    /// What error messages call this type, like `str` or `list`.
    fn type_name() -> String;
    fn from_py(v: &Value) -> Option<Self>;
}

impl FromPy for String {
    fn type_name() -> String {
        "str".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        v.as_str().map(String::from)
    }
}
impl FromPy for i64 {
    fn type_name() -> String {
        "int".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        v.as_int()
    }
}
impl FromPy for f64 {
    fn type_name() -> String {
        "float".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        v.as_float()
    }
}
impl FromPy for bool {
    fn type_name() -> String {
        "bool".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        v.as_bool()
    }
}
impl FromPy for Value {
    fn type_name() -> String {
        "object".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        Some(v.clone())
    }
}
/// A list or tuple whose every item converts.
impl<T: FromPy> FromPy for Vec<T> {
    fn type_name() -> String {
        "list".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        v.as_items()?.iter().map(T::from_py).collect()
    }
}
/// A dict whose keys are all strings and whose values all convert.
impl<T: FromPy> FromPy for IndexMap<String, T> {
    fn type_name() -> String {
        "dict".into()
    }
    fn from_py(v: &Value) -> Option<Self> {
        let Value::Dict(d) = v else { return None };
        let mut out = IndexMap::new();
        for (k, item) in d.borrow().iter() {
            let HKey::Str(k) = k else { return None };
            out.insert(k.to_string(), T::from_py(item)?);
        }
        Some(out)
    }
}
/// `None` reads as None; anything else has to convert.
impl<T: FromPy> FromPy for Option<T> {
    fn type_name() -> String {
        format!("{} or None", T::type_name())
    }
    fn from_py(v: &Value) -> Option<Self> {
        if v.is_none() {
            return Some(None);
        }
        T::from_py(v).map(Some)
    }
}

impl Call<'_> {
    /// The argument at [position], or the keyword argument [keyword] when it was passed by name.
    pub fn value(&self, position: usize, keyword: Option<&str>) -> Option<&Value> {
        if let Some(k) = keyword {
            if let Some((_, v)) = self.kwargs.iter().find(|(n, _)| n.as_ref() == k) {
                return Some(v);
            }
        }
        self.args.get(position)
    }

    fn which(position: usize, keyword: Option<&str>) -> String {
        match keyword {
            Some(k) => format!("'{k}'"),
            None => (position + 1).to_string(),
        }
    }

    /// A required argument, converted. A missing or mistyped one is a TypeError the script can catch.
    pub fn arg<T: FromPy>(&self, position: usize, keyword: Option<&str>) -> Result<T, PyError> {
        let raw = self.value(position, keyword).ok_or_else(|| {
            PyError::at(
                format!("{}() missing required argument {}", self.name, Self::which(position, keyword)),
                "TypeError",
                Some(self.line),
            )
        })?;
        self.convert(raw, position, keyword)
    }

    /// An optional argument, converted, or [fallback] when the script left it out.
    pub fn arg_or<T: FromPy>(&self, position: usize, keyword: Option<&str>, fallback: T) -> Result<T, PyError> {
        match self.value(position, keyword) {
            None => Ok(fallback),
            Some(raw) => self.convert(raw, position, keyword),
        }
    }

    fn convert<T: FromPy>(&self, raw: &Value, position: usize, keyword: Option<&str>) -> Result<T, PyError> {
        T::from_py(raw).ok_or_else(|| {
            PyError::at(
                format!(
                    "{}() argument {} must be {}, not {}",
                    self.name,
                    Self::which(position, keyword),
                    T::type_name(),
                    raw.type_name()
                ),
                "TypeError",
                Some(self.line),
            )
        })
    }

    /// Call something the script passed in, like a callback. It spends the same step budget.
    pub fn invoke(&mut self, callable: &Value, args: Vec<Value>) -> Result<Value, PyError> {
        let r = self.interp.invoke(callable, args);
        flow_to_error(r, self.interp.current_line)
    }

    /// Steps spent so far in this call.
    pub fn steps_used(&self) -> u64 {
        self.interp.steps
    }

    /// Run [source] as a module of its own in this interpreter, with [names]
    /// defined as its globals first, and return its globals as a dict. The
    /// functions it defines stay callable. Shares this call's budget.
    pub fn run_module(&mut self, source: &str, names: Vec<(String, Value)>) -> Result<Value, PyError> {
        let program = parse(source)?;
        let names = names.into_iter().map(|(k, v)| (Rc::from(k), v)).collect();
        let r = self.interp.exec_module(&program, names);
        let env = flow_to_error(r, self.interp.current_line)?;
        let mut pairs: Vec<(String, Value)> = env.vars.borrow().iter().map(|(k, v)| (k.to_string(), v.clone())).collect();
        pairs.sort_by(|a, b| a.0.cmp(&b.0));
        Ok(Value::dict(pairs))
    }
}

pub(crate) fn flow_to_error<T>(r: Result<T, Exc>, line: u32) -> Result<T, PyError> {
    match r {
        Ok(v) => Ok(v),
        Err(Exc::Error(e)) => Err(*e),
        Err(Exc::Return(_)) => Err(PyError::at("'return' outside function", "SyntaxError", Some(line))),
        Err(Exc::Break) => Err(PyError::at("'break' outside loop", "SyntaxError", Some(line))),
        Err(Exc::Continue) => Err(PyError::at("'continue' not properly in loop", "SyntaxError", Some(line))),
    }
}
