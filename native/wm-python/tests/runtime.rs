//! Port of RuntimeTest.kt (PyMini's tests/RuntimeTests.swift); expected values unchanged.

mod common;
use common::*;

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use indexmap::IndexMap;
use wm_python::interp::{FromPy, Limits, PyError, Runtime, Value, STANDARD_MODULES, VERSION, VERSION_LINE};

#[test]
fn calling_into_a_script() {
    let mut py = Runtime::default();
    py.load("def greet(name, punctuation=\"!\"):\n    return \"hi \" + name + punctuation").unwrap();
    assert_eq!(Some("hi sam!"), py.call("greet", vec![Value::str("sam")]).unwrap().as_str());
    assert_eq!(
        Some("hi sam?"),
        py.call_kw("greet", vec![Value::str("sam")], vec![("punctuation".into(), Value::str("?"))]).unwrap().as_str()
    );
    assert!(py.has_function("greet"));
    assert_eq!(Some(vec!["name".to_string(), "punctuation".to_string()]), py.parameters("greet"));
    assert_eq!(None, py.parameters("missing"));
}

#[test]
fn host_functions() {
    let mut py = Runtime::default();
    let logged = Rc::new(RefCell::new(Vec::<String>::new()));
    py.define("shout", |call| Ok(Value::str(call.arg::<String>(0, None)?.to_uppercase())));
    let sink = logged.clone();
    py.define("log", move |call| {
        sink.borrow_mut().push(call.arg::<String>(0, Some("message"))?);
        Ok(Value::None)
    });
    let result = py.run("log(message=\"starting\")\nshout(\"quiet\") + \"!\"").unwrap();
    assert_eq!(Some("QUIET!"), result.as_str());
    assert_eq!(vec!["starting".to_string()], *logged.borrow());
}

#[test]
fn host_argument_errors_are_catchable() {
    let mut py = Runtime::default();
    py.define("double", |call| Ok(Value::Int(call.arg::<i64>(0, None)? * 2)));
    let result = py
        .run("try:\n    double(\"two\")\n    message = \"no error\"\nexcept TypeError as e:\n    message = str(e)\nmessage")
        .unwrap();
    assert_eq!(Some("double() argument 1 must be int, not str"), result.as_str());
}

#[test]
fn host_errors_keep_their_kind() {
    let mut py = Runtime::default();
    py.define("fetch", |call| {
        Err(PyError::new(format!("nothing stored under {}", call.arg::<String>(0, None)?), "KeyError"))
    });
    let result = py.run("try:\n    fetch(\"colour\")\nexcept LookupError as e:\n    found = str(e)\nfound").unwrap();
    assert_eq!(Some("nothing stored under colour"), result.as_str());
    let error = py_error(py.run("\n\nfetch('size')"));
    assert_eq!("Line 3: KeyError: nothing stored under size", error.to_string());
}

#[test]
fn optional_hooks() {
    let mut py = Runtime::default();
    py.load("def on_word(word, count):\n    return f\"{word}:{count}\"").unwrap();
    assert_eq!(Some("hi:2".to_string()), py.call_hook("on_word", vec![Value::str("hi"), Value::Int(2)]).unwrap().map(|v| v.to_str()));
    assert!(py.call_hook("on_open", vec![]).unwrap().is_none());
}

#[test]
fn host_callbacks() {
    let mut py = Runtime::default();
    py.define("apply_twice", |call| {
        let function = call.arg::<Value>(0, None)?;
        let start = call.arg::<Value>(1, None)?;
        let once = call.invoke(&function, vec![start])?;
        call.invoke(&function, vec![once])
    });
    assert_eq!(Some(18), py.run("apply_twice(lambda n: n * 3, 2)").unwrap().as_int());
}

#[test]
fn host_modules() {
    let mut py = Runtime::default();
    let store = Rc::new(RefCell::new(HashMap::from([("theme".to_string(), "dark".to_string())])));
    let (s1, s2) = (store.clone(), store.clone());
    py.define_module("settings", move |module| {
        module.set("version", 2);
        module.define("get", move |call| Ok(Value::from(s1.borrow().get(&call.arg::<String>(0, None)?).cloned())));
        module.define("put", move |call| {
            s2.borrow_mut().insert(call.arg(0, None)?, call.arg(1, None)?);
            Ok(Value::None)
        });
    });
    let result = py
        .run("import settings\nfrom settings import get\nsettings.put(\"font\", \"mono\")\n[settings.version, get(\"theme\"), get(\"missing\")]")
        .unwrap();
    assert_eq!("[2, 'dark', None]", result.repr());
    assert_eq!(Some("mono".to_string()), store.borrow().get("font").cloned());
}

#[test]
fn modules_can_be_turned_off() {
    let mut py = Runtime::new(Limits::default(), &["math"]);
    py.run("import math").unwrap();
    let error = py_error(py.run("import time"));
    assert!(error.to_string().contains("no module named 'time' (available: math)"), "{error}");
}

#[test]
fn globals() {
    let mut py = Runtime::default();
    py.set("words", Some(Value::from(vec!["one", "two", "three"])));
    py.set("limit", Some(Value::Int(2)));
    assert_eq!("['one', 'two']", py.run("words[:limit]").unwrap().repr());
    py.run("total = len(words)").unwrap();
    assert_eq!(Some(3), py.get("total").and_then(|v| v.as_int()));
    py.set("total", None);
    assert!(py.get("total").is_none());
}

#[test]
fn conversions() {
    assert_eq!(Some(vec![1i64, 2, 3]), Vec::<i64>::from_py(&Value::from(vec![1i64, 2, 3])));
    assert_eq!(None, Vec::<i64>::from_py(&Value::list(vec![Value::Int(1), Value::str("two")])));
    let mut expected = IndexMap::new();
    expected.insert("a".to_string(), 1i64);
    assert_eq!(Some(expected), IndexMap::<String, i64>::from_py(&Value::dict(vec![("a", Value::Int(1))])));
    assert_eq!(Some(None), Option::<String>::from_py(&Value::None));
    assert_eq!(None, Option::<String>::from_py(&Value::Int(5)));
    assert_eq!(Some(3.0), f64::from_py(&Value::Int(3)));
    let map: HashMap<String, Value> = HashMap::from([("b".to_string(), Value::Int(2)), ("a".to_string(), Value::Int(1))]);
    assert_eq!("{'a': 1, 'b': 2}", Value::from(map).repr());
    assert_eq!(Value::Int(1), Value::Float(1.0));
}

#[test]
fn each_call_gets_its_own_budget_and_output() {
    // PyMini's container default is 200,000; this port's is 10,000, which
    // would stop range(100_000) on size before the budget, so restore it.
    let mut py = Runtime::new(Limits { steps: 5_000, container_items: 200_000, ..Limits::default() }, STANDARD_MODULES);
    py.load("def count(n):\n    print(\"counting\", n)\n    total = 0\n    for i in range(n):\n        total += i\n    return total").unwrap();
    for _ in 0..5 {
        assert_eq!(Some(19_900), py.call("count", vec![Value::Int(200)]).unwrap().as_int());
        assert_eq!(&["counting 200".to_string()], py.output());
    }
    let error = py_error(py.call("count", vec![Value::Int(100_000)]));
    assert!(error.to_string().contains("step budget"), "{error}");
}

#[test]
fn container_limit() {
    let mut py = Runtime::new(Limits { container_items: 10, ..Limits::default() }, STANDARD_MODULES);
    let error = py_error(py.run("list(range(100))"));
    assert!(error.to_string().contains("range too large"), "{error}");
}

#[test]
fn print_is_streamed() {
    let mut py = Runtime::default();
    let lines = Rc::new(RefCell::new(Vec::<String>::new()));
    let sink = lines.clone();
    py.set_on_print(Some(Box::new(move |l| sink.borrow_mut().push(l.to_string()))));
    py.run("for n in range(3):\n    print('line', n)").unwrap();
    assert_eq!(vec!["line 0", "line 1", "line 2"], *lines.borrow());
}

#[test]
fn stray_control_flow_is_a_syntax_error() {
    assert_eq!("SyntaxError", py_error(Runtime::default().run("break")).kind);
}

#[test]
fn version_is_readable_from_rust_and_from_a_script() {
    assert_eq!(format!("pymini {VERSION}"), VERSION_LINE);
    assert_eq!(3, VERSION.split('.').count());
    assert_eq!(VERSION_LINE, Runtime::default().run("import sys\nsys.version").unwrap().to_str());
}

#[test]
fn sys_can_be_switched_off_like_any_other_module() {
    let mut py = Runtime::new(Limits::default(), &["math"]);
    assert_eq!("ImportError", py_error(py.run("import sys")).kind);
}
