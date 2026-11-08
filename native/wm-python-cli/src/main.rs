//! `wm-python-cli`: WM Keyboard's Python plugins, headless.
//!
//! ```text
//! wm-python-cli test <plugin-dir>   run <plugin-dir>/test.py; exit 1 on an uncaught exception
//! wm-python-cli run <plugin-dir>    load the plugin as a panel and print the first render's JSON
//! ```
//!
//! `test.py` runs with the plugin host's `wm` and `ui` globals (a panel, every
//! permission, storage and reminders in memory) and one extra module,
//! `plugin_test`, a global that `import plugin_test` also reaches:
//!
//! * `plugin_test.read(name)`: the UTF-8 text of a file inside the plugin folder
//! * `plugin_test.load(name, wm, ui)`: run that file as a module with the
//!   given `wm` and `ui` as its globals, and return its globals as a dict,
//!   whose functions stay callable (`m = plugin_test.load("main.py", wm, ui)`,
//!   then `m["transform"]("x")` or `m.transform("x")`)
//!
//! A test fails by raising. Its budget is generous (500M steps, 10 minutes)
//! because one run drives a whole corpus of cases.

use std::path::{Component, Path, PathBuf};
use std::process::ExitCode;
use std::rc::Rc;

use wm_python::host::api::{self, PluginInfo};
use wm_python::host::files::{Reminders, Storage};
use wm_python::host::Session;
use wm_python::interp::{Limits, PyError, Runtime, SystemClock, Value, STANDARD_MODULES};

const USAGE: &str = "usage: wm-python-cli test <plugin-dir>\n       wm-python-cli run <plugin-dir>";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    }
    let (command, dir) = (args[1].clone(), PathBuf::from(&args[2]));
    // Scripts recurse on the native stack; give them room.
    let worker = std::thread::Builder::new().stack_size(256 << 20).spawn(move || match command.as_str() {
        "test" => test(&dir),
        "run" => run(&dir),
        _ => {
            eprintln!("{USAGE}");
            2
        }
    });
    ExitCode::from(worker.expect("spawn").join().unwrap_or(1))
}

struct Manifest {
    id: String,
    version: String,
    entry: String,
    permissions: Vec<String>,
}

fn manifest(dir: &Path) -> Result<Manifest, String> {
    let path = dir.join("plugin.json");
    let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
    let entry = s("entry");
    Ok(Manifest {
        id: s("id"),
        version: s("version"),
        entry: if entry.is_empty() { "main.py".into() } else { entry },
        permissions: v
            .get("permissions")
            .and_then(|p| p.as_array())
            .map(|a| a.iter().filter_map(|x| x.as_str()).map(String::from).collect())
            .unwrap_or_default(),
    })
}

/// A file inside [dir], or why not: absolute paths and `..` are refused, and
/// so is anything a symlink leads out of the folder.
fn inside(dir: &Path, name: &str) -> Result<PathBuf, String> {
    let rel = Path::new(name);
    if name.is_empty() || rel.components().any(|c| !matches!(c, Component::Normal(_))) {
        return Err(format!("'{name}' is not a file inside the plugin folder"));
    }
    let root = dir.canonicalize().map_err(|e| e.to_string())?;
    let path = root.join(rel).canonicalize().map_err(|_| format!("no file '{name}' in the plugin folder"))?;
    if !path.starts_with(&root) {
        return Err(format!("'{name}' is not a file inside the plugin folder"));
    }
    Ok(path)
}

fn read_inside(dir: &Path, name: &str) -> Result<String, PyError> {
    let path = inside(dir, name).map_err(|m| PyError::new(m, "ValueError"))?;
    std::fs::read_to_string(&path).map_err(|e| PyError::new(format!("cannot read '{name}': {e}"), "ValueError"))
}

fn report(e: &PyError) {
    match e.line {
        Some(l) => eprintln!("{}: {} (line {l})", e.kind, e.message),
        None => eprintln!("{}: {}", e.kind, e.message),
    }
}

fn test(dir: &Path) -> u8 {
    let source = match std::fs::read_to_string(dir.join("test.py")) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{}: {e}", dir.join("test.py").display());
            return 2;
        }
    };
    let m = manifest(dir).unwrap_or(Manifest {
        id: String::new(),
        version: String::new(),
        entry: "main.py".into(),
        permissions: Vec::new(),
    });
    let mut rt = Runtime::new(Limits { steps: 500_000_000, container_items: 200_000, string_length: 1_000_000 }, STANDARD_MODULES);
    let info = PluginInfo {
        id: m.id,
        version: m.version,
        permissions: vec!["storage".into(), "reminders".into()],
        panel: true,
    };
    api::install(&mut rt, &info, Some(Storage::new(None)), Some(Reminders::new(None)), Rc::new(SystemClock::default()));
    // The test's output goes straight to stdout, wm.log and print alike.
    rt.set_on_print(Some(Box::new(|line| println!("{line}"))));
    rt.set_deadline(Some(std::time::Instant::now() + std::time::Duration::from_secs(600)));
    let (d1, d2) = (dir.to_path_buf(), dir.to_path_buf());
    rt.define_module("plugin_test", move |module| {
        module.define("read", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            read_inside(&d1, &name).map(Value::from)
        });
        module.define("load", move |call| {
            let name: String = call.arg(0, Some("name"))?;
            let wm = call.value(1, Some("wm")).cloned().unwrap_or(Value::None);
            let ui = call.value(2, Some("ui")).cloned().unwrap_or(Value::None);
            let source = read_inside(&d2, &name)?;
            call.run_module(&source, vec![("wm".into(), wm), ("ui".into(), ui)])
        });
    });
    rt.set("plugin_test", Some(Value::Module(Rc::from("plugin_test"))));
    match rt.load(&source) {
        Ok(()) => 0,
        Err(e) => {
            report(&e);
            1
        }
    }
}

fn run(dir: &Path) -> u8 {
    let m = match manifest(dir) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    let source = match inside(dir, &m.entry).and_then(|p| std::fs::read_to_string(p).map_err(|e| e.to_string())) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{}: {e}", m.entry);
            return 2;
        }
    };
    let config = serde_json::json!({"id": m.id, "version": m.version, "permissions": m.permissions, "mode": "panel"});
    let mut session = match Session::new(&config.to_string()) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    let result = session.load(&source);
    println!("{result}");
    let ok = serde_json::from_str::<serde_json::Value>(&result).ok().and_then(|v| v["ok"].as_bool()).unwrap_or(false);
    if ok {
        0
    } else {
        1
    }
}
