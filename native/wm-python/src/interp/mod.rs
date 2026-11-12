//! The interpreter: a Rust port of PyMini 2.0.1 by way of the Kotlin port
//! (`core/plugins/.../python/*.kt`), keeping PyMini's semantics rather than
//! CPython's.
//!
//! ```
//! use wm_python::interp::{Runtime, Value};
//! let mut py = Runtime::default();
//! py.load("def transform(text):\n    return text.upper()").unwrap();
//! assert_eq!(py.call("transform", vec![Value::str("hi")]).unwrap().as_str(), Some("HI"));
//! ```
//!
//! Globals live as long as the runtime, so functions defined by one `load` can
//! be called again and again. Every `load`, `run` and `call` starts with a full
//! step budget and empty output. A runtime is single-threaded (`!Send`); the
//! cancel flag is the one piece another thread may touch.
//!
//! Additions to PyMini: a dict answers its string keys as attributes
//! (`e.type`), a minimal `bytes` type, `time.format` with English names, a
//! cancel flag and deadline polled on the step path, a call-depth cap of 300,
//! and a parser nesting cap of 100.
//!
//! Native stack: deep recursion (300 calls) needs about 1 MB in release builds
//! and several MB in debug builds. Hosts should run scripts on a thread with
//! at least 8 MB of stack.

mod ast;
mod builtins;
mod bytes;
mod classes;
mod datefmt;
mod error;
mod eval;
mod format;
mod host;
mod json;
mod lexer;
mod methods;
mod modules;
mod parser;
mod regex;
mod stdlib;
mod text;
mod value;

use std::rc::Rc;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

pub use error::{AbortKind, PyError};
pub use eval::{Limits, MAX_CALL_DEPTH};
pub use host::{Call, FromPy, HostFunction, HostModule};
pub use modules::{exports as module_exports, Clock, SystemClock, NAMES as STANDARD_MODULES, VERSION, VERSION_LINE};
pub use regex::Match;
pub use value::{Class, ExceptionValue, Function, HKey, Instance, Object, Value};

pub(crate) use datefmt::local_to_unix;
pub(crate) use text::{display_text, take_utf16, utf16_len};

use error::Exc;
use eval::Interp;

/// Why a script will not run, as the interpreter's own parser sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CheckError {
    /// The message PyMini would raise, without kind or line.
    pub message: String,
    /// The Python exception kind, e.g. "SyntaxError". PyMini's parser raises "RuntimeError".
    pub kind: String,
    /// 1-based line, when the parser knows it.
    pub line: Option<u32>,
}

/// Parses [source] the way `load` would, without running anything.
/// The tooling's "will it run" verdict. Signature is fixed: other crates call it.
pub fn check(source: &str) -> Result<(), CheckError> {
    parser::parse(source).map(|_| ()).map_err(|e| CheckError { message: e.message, kind: e.kind, line: e.line })
}

/// A script's world: its globals, the host functions and modules it can
/// reach, and the budget each call runs under.
pub struct Runtime {
    interp: Interp,
}

impl Default for Runtime {
    fn default() -> Self {
        Runtime::new(Limits::default(), STANDARD_MODULES)
    }
}

impl Runtime {
    /// [modules]: the standard modules a script may import (by default all
    /// six). Importing a left-out one raises a catchable ImportError.
    pub fn new(limits: Limits, modules: &[&str]) -> Runtime {
        Runtime::with_clock(limits, modules, Rc::new(SystemClock::default()))
    }

    pub fn with_clock(limits: Limits, modules: &[&str], clock: Rc<dyn Clock>) -> Runtime {
        Runtime { interp: Interp::new(limits, modules, clock) }
    }

    // MARK: - Giving scripts things

    /// A Rust function callable from scripts as a global; it shadows a builtin of the same name.
    pub fn define(&mut self, name: &str, body: impl Fn(&mut Call) -> Result<Value, PyError> + 'static) {
        self.interp.host_functions.insert(name.to_string(), HostFunction::new(name, body));
    }

    /// A module scripts can `import` (or reach through a global set to
    /// `Value::Module(name)`). A module named like a standard one replaces it.
    pub fn define_module(&mut self, name: &str, build: impl FnOnce(&mut HostModule)) {
        let mut m = HostModule::new(name);
        build(&mut m);
        self.interp.host_modules.insert(name.to_string(), Rc::new(m));
    }

    /// Read a global.
    pub fn get(&self, name: &str) -> Option<Value> {
        self.interp.globals.get(name)
    }

    /// Write a global; None removes it.
    pub fn set(&mut self, name: &str, value: Option<Value>) {
        match value {
            Some(v) => self.interp.globals.define(&Rc::from(name), v),
            None => {
                self.interp.globals.remove(name);
            }
        }
    }

    /// The flag polled on every step: set it from any thread and the running
    /// call ends with an uncatchable `Cancelled` abort. The runtime never
    /// clears it.
    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        self.interp.cancel.clone()
    }

    /// Use [flag] as the cancel flag.
    pub fn set_cancel_handle(&mut self, flag: Arc<AtomicBool>) {
        self.interp.cancel = flag;
    }

    /// When the running call must stop. Polled every 32 steps; host calls push it out by their own duration.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn set_deadline(&mut self, deadline: Option<std::time::Instant>) {
        self.interp.deadline = deadline;
    }

    /// The step budget for each following call.
    pub fn set_step_limit(&mut self, steps: u64) {
        self.interp.max_steps = steps;
    }

    pub fn step_limit(&self) -> u64 {
        self.interp.max_steps
    }

    /// Steps the latest call spent.
    pub fn steps_used(&self) -> u64 {
        self.interp.steps
    }

    /// What `print` wrote during the latest `load`, `run` or `call`, one line per call.
    pub fn output(&self) -> &[String] {
        &self.interp.printed
    }

    /// Hear each printed line as it is written.
    pub fn set_on_print(&mut self, f: Option<Box<dyn FnMut(&str)>>) {
        self.interp.on_print = f;
    }

    /// Every module name a script could import right now, sorted.
    pub fn importable_modules(&self) -> Vec<String> {
        self.interp.importable_modules()
    }

    /// A module written in Python that scripts can import as [name] (dotted
    /// for a module inside a package). Its top level runs on first import, in
    /// this runtime and on the importing call's budget. A [package] is the
    /// source of `pkg/__init__.py`, which relative imports resolve against.
    pub fn add_source_module(&mut self, name: &str, source: &str, package: bool) {
        self.interp.source_modules.insert(name.to_string(), Rc::from(source));
        if package {
            self.interp.packages.insert(name.to_string());
        }
    }

    /// Turn on the standard library modules written for this crate:
    /// collections, dataclasses, datetime, enum, functools, itertools,
    /// typing, string, textwrap, html, base64, hashlib, hmac, uuid,
    /// urllib.parse, statistics, abc and copy. Each loads on first import.
    pub fn enable_python_stdlib(&mut self) {
        if !self.interp.enabled_modules.contains(&"_native") {
            self.interp.enabled_modules.push("_native");
        }
        for (name, source, package) in stdlib::PYTHON_MODULES {
            if !self.interp.source_modules.contains_key(*name) {
                self.add_source_module(name, source, *package);
            }
        }
    }

    /// The names of the standard library modules [enable_python_stdlib] adds.
    pub fn python_stdlib_names() -> Vec<&'static str> {
        stdlib::PYTHON_MODULES.iter().map(|(n, _, _)| *n).collect()
    }

    // MARK: - Running scripts

    /// Run a module's top level: define its functions, set its globals.
    pub fn load(&mut self, source: &str) -> Result<(), PyError> {
        let program = parser::parse(source)?;
        self.perform(|i| i.run(&program))
    }

    /// Run a chunk of code and return the value of its last line when that line
    /// is an expression, the way an interactive prompt does. Otherwise None.
    pub fn run(&mut self, source: &str) -> Result<Value, PyError> {
        let program = parser::parse(source)?;
        match program.last() {
            Some(ast::Stmt { line, kind: ast::StmtKind::Expr(e) }) => {
                let line = *line;
                self.perform(|i| {
                    i.run(&program[..program.len() - 1])?;
                    i.current_line = line;
                    let g = i.globals.clone();
                    i.eval(e, &g)
                })
            }
            _ => {
                self.perform(|i| i.run(&program))?;
                Ok(Value::None)
            }
        }
    }

    /// Call a global function.
    pub fn call(&mut self, name: &str, args: Vec<Value>) -> Result<Value, PyError> {
        self.perform(|i| i.call(name, args, Vec::new()))
    }

    /// Call a global function with keyword arguments.
    pub fn call_kw(&mut self, name: &str, args: Vec<Value>, kwargs: Vec<(String, Value)>) -> Result<Value, PyError> {
        let kwargs = kwargs.into_iter().map(|(k, v)| (Rc::from(k), v)).collect();
        self.perform(|i| i.call(name, args, kwargs))
    }

    /// Call a function value a script handed back, like a stored callback.
    pub fn call_value(&mut self, function: &Value, args: Vec<Value>) -> Result<Value, PyError> {
        self.perform(|i| i.invoke(function, args))
    }

    /// Call a hook the script may or may not have defined; Ok(None) when it didn't.
    pub fn call_hook(&mut self, name: &str, args: Vec<Value>) -> Result<Option<Value>, PyError> {
        if !self.has_function(name) {
            return Ok(None);
        }
        self.call(name, args).map(Some)
    }

    /// Whether the script defined a function with this name.
    pub fn has_function(&self, name: &str) -> bool {
        matches!(self.get(name), Some(Value::Function(_)))
    }

    /// A script function's parameter names, or None when there's no such function.
    pub fn parameters(&self, name: &str) -> Option<Vec<String>> {
        match self.get(name) {
            Some(Value::Function(f)) => Some(f.parameter_names()),
            _ => None,
        }
    }

    /// Fresh budget and output, then [body]. A `return`, `break` or `continue`
    /// that escaped everything becomes a SyntaxError here.
    fn perform<T>(&mut self, body: impl FnOnce(&mut Interp) -> Result<T, Exc>) -> Result<T, PyError> {
        self.interp.reset_budget();
        self.interp.printed.clear();
        let r = body(&mut self.interp);
        host::flow_to_error(r, self.interp.current_line)
    }
}
