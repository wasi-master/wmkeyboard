//! What the interpreter in this build actually does, asked of the interpreter
//! itself rather than written down: whether a builtin, a module or a module's
//! member exists, and how it takes Python that its parser accepts but might
//! not run the way CPython does. The interpreter is still growing, so a
//! hand-kept list would go stale the day it gains a feature.

use std::collections::HashMap;
use std::sync::OnceLock;

use wm_python::interp::{Runtime, STANDARD_MODULES};

/// How a feature fares in this build.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Support {
    Yes,
    /// Runs, but not as CPython does.
    Differs,
    /// Raises.
    Fails,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Features {
    pub multiple_inheritance: Support,
    pub metaclass: Support,
    pub big_ints: Support,
    /// `typing.TYPE_CHECKING` is there and false.
    pub type_checking_false: bool,
    /// A module can be loaded by a name made at run time (`__import__`, `importlib`).
    pub dynamic_import: bool,
    /// A module's own `__getattr__` answers for missing attributes.
    pub module_getattr: bool,
    /// `__init_subclass__` runs when a subclass is made.
    pub init_subclass: bool,
}

fn runtime() -> Runtime {
    let mut rt = Runtime::new(Default::default(), STANDARD_MODULES);
    rt.enable_python_stdlib();
    rt
}

fn runs(source: &str) -> bool {
    runtime().load(source).is_ok()
}

/// One check per feature, run once per process.
pub(crate) fn features() -> Features {
    static F: OnceLock<Features> = OnceLock::new();
    *F.get_or_init(|| {
        let two = "class A:\n    def f(self):\n        return 1\nclass B:\n    def g(self):\n        return 2\nclass C(A, B):\n    pass\n";
        let multiple_inheritance = if !runs(two) {
            Support::Fails
        } else if runs(&format!("{two}c = C()\nif c.f() != 1 or c.g() != 2:\n    raise ValueError('mro')\n")) {
            Support::Yes
        } else {
            Support::Differs
        };
        let meta = "class M(type):\n    pass\nclass C(metaclass=M):\n    pass\n";
        let metaclass = if !runs(meta) {
            Support::Fails
        } else if runs(&format!("{meta}if type(C) is not M:\n    raise ValueError('meta')\n")) {
            Support::Yes
        } else {
            Support::Differs
        };
        let big_ints = if !runs("x = 2 ** 64\n") {
            Support::Fails
        } else if runs("x = 2 ** 64\nif not isinstance(x, int) or x - 1 != 18446744073709551615:\n    raise ValueError('big')\n") {
            Support::Yes
        } else {
            Support::Differs
        };
        let type_checking_false = runs("from typing import TYPE_CHECKING\nif TYPE_CHECKING:\n    raise ValueError('typing')\n");
        let dynamic_import = runs("m = __import__('math')\n") || runs("import importlib\n");
        let module_getattr = {
            let mut rt = runtime();
            rt.add_source_module("_wmpy_lazy", "def __getattr__(name):\n    return 1\n", false);
            rt.load("import _wmpy_lazy\nif _wmpy_lazy.anything != 1:\n    raise ValueError('getattr')\n").is_ok()
        };
        let init_subclass = runs("class A:\n    seen = []\n    def __init_subclass__(cls):\n        A.seen.append(1)\nclass B(A):\n    pass\nif len(A.seen) != 1:\n    raise ValueError('hook')\n");
        Features { multiple_inheritance, metaclass, big_ints, type_checking_false, dynamic_import, module_getattr, init_subclass }
    })
}

/// Answers about names and modules, each asked once.
pub(crate) struct Probe {
    rt: Runtime,
    next: usize,
    names: HashMap<String, bool>,
    modules: HashMap<String, bool>,
    members: HashMap<(String, String), bool>,
    sources: HashMap<String, bool>,
}

impl Probe {
    pub fn new() -> Probe {
        Probe { rt: runtime(), next: 0, names: HashMap::new(), modules: HashMap::new(), members: HashMap::new(), sources: HashMap::new() }
    }

    /// Whether [name] means something at a module's top level with nothing
    /// defined: a builtin, or a name every module has, like `__name__`.
    pub fn name_exists(&mut self, name: &str) -> bool {
        if let Some(v) = self.names.get(name) {
            return *v;
        }
        self.next += 1;
        let module = format!("_wmpy_probe_{}", self.next);
        self.rt.add_source_module(&module, &format!("{name}\n"), false);
        let ok = match self.rt.import_module(&module) {
            Ok(()) => true,
            Err(e) => e.kind != "NameError",
        };
        self.names.insert(name.to_string(), ok);
        ok
    }

    /// Whether [source] loads as a module of its own. For statements the
    /// interpreter handles itself rather than as an import, such as
    /// `from __future__ import annotations`.
    pub fn source_runs(&mut self, source: &str) -> bool {
        if let Some(v) = self.sources.get(source) {
            return *v;
        }
        self.next += 1;
        let module = format!("_wmpy_probe_{}", self.next);
        self.rt.add_source_module(&module, source, false);
        let ok = self.rt.import_module(&module).is_ok();
        self.sources.insert(source.to_string(), ok);
        ok
    }

    /// Whether the builtin [name], which exists, raises when called the way
    /// plugins would call it: `open` with a path, `input` with a prompt.
    pub fn refuses(&mut self, name: &str) -> bool {
        let call = match name {
            "open" => "open('x.txt')",
            "input" => "input('? ')",
            "breakpoint" | "help" | "copyright" | "credits" | "license" => &format!("{name}()"),
            _ => return false,
        };
        let src = format!("try:\n    {call}\nexcept (OSError, EOFError, RuntimeError, NotImplementedError):\n    pass\nelse:\n    raise ValueError('ran')\n");
        self.source_runs(&src)
    }

    /// Whether `import [name]` works in a plugin, as far as the standard
    /// library goes (the `wm` package and the plugin's own files aside).
    pub fn module_exists(&mut self, name: &str) -> bool {
        if let Some(v) = self.modules.get(name) {
            return *v;
        }
        let top = name.split('.').next().unwrap_or(name);
        // Names the probe itself made, and the interpreter's private halves, are not the plugin's to import.
        let ok = !top.starts_with("_wmpy_probe_") && top != "_native" && top != "wm" && top != "_wm" && self.rt.import_module(name).is_ok();
        self.modules.insert(name.to_string(), ok);
        ok
    }

    /// Whether `module.name` exists, as a member or a submodule. [module] must exist.
    pub fn member_exists(&mut self, module: &str, name: &str) -> bool {
        let key = (module.to_string(), name.to_string());
        if let Some(v) = self.members.get(&key) {
            return *v;
        }
        let ok = (self.module_exists(module) && self.rt.module_member(module, name).is_some())
            || self.module_exists(&format!("{module}.{name}"))
            // A module's own `__getattr__` (PEP 562) answers names it makes on
            // demand, such as typing.Pattern: ask the import itself.
            || (self.module_exists(module) && self.source_runs(&format!("from {module} import {name}\n")));
        self.members.insert(key, ok);
        ok
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Prints what this build's interpreter does, for the record (`--nocapture`).
    #[test]
    fn features_answer() {
        let handle = std::thread::Builder::new()
            .stack_size(64 << 20)
            .spawn(|| {
                let f = features();
                eprintln!("{f:?}");
                let mut p = Probe::new();
                for name in ["print", "open", "eval", "dir", "memoryview", "__file__", "__name__", "UserWarning", "NotImplemented"] {
                    eprintln!("name {name}: {}", p.name_exists(name));
                }
                for m in ["math", "collections", "typing", "__future__", "os", "urllib.parse", "string", "operator", "types", "warnings"] {
                    eprintln!("module {m}: {}", p.module_exists(m));
                }
                for (m, n) in [("sys", "version"), ("sys", "version_info"), ("collections", "OrderedDict"), ("typing", "TYPE_CHECKING"), ("functools", "wraps"), ("re", "compile")] {
                    eprintln!("member {m}.{n}: {}", p.member_exists(m, n));
                }
                for src in ["from __future__ import annotations\n", "import __future__\n", "x: int = 1\n", "def f(*, a=1, **kw):\n    return kw\n", "@property\ndef g(self):\n    return 1\n", "with open('x') as f:\n    pass\n", "def gen():\n    yield 1\n", "async def a():\n    pass\n", "class A:\n    __slots__ = ('x',)\n", "class E(Exception):\n    pass\ntry:\n    raise ValueError('x')\nexcept (E, ValueError):\n    pass\n", "try:\n    pass\nexcept UndefinedThing:\n    pass\n", "try:\n    raise ValueError('x')\nexcept UndefinedThing:\n    pass\nexcept ValueError:\n    pass\n"] {
                    eprintln!("runs {src:?}: {:?}", runtime().load(src).err().map(|e| format!("{}: {}", e.kind, e.message)));
                }
                assert!(p.name_exists("print"));
                assert!(p.module_exists("math"));
                // Sockets stay out of plugins whatever else the interpreter gains.
                assert!(!p.module_exists("socket"));
                assert!(p.member_exists("sys", "version"));
            })
            .unwrap();
        handle.join().unwrap();
    }
}
