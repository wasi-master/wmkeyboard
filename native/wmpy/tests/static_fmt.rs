//! `wmpy fmt` against plugin folders made on the fly.

use std::path::{Path, PathBuf};
use std::process::Command;

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-static-fmt-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = scratch(name);
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn cli_in(cwd: &Path, args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).current_dir(cwd).args(args).output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

fn read(dir: &Path, f: &str) -> String {
    std::fs::read_to_string(dir.join(f)).unwrap()
}

const MANIFEST: &str = r#"{"format": "wmkeyboard-plugin", "id": "com.example.fmt", "name": "Fmt", "pluginVersion": "1", "apiVersion": 5}
"#;
const UGLY: &str = "from wm import ui\ndef render():\n\tui.label('x' ,key='a')\nx=1+2\n";
const TIDY: &str = "from wm import ui\n\n\ndef render():\n    ui.label('x', key='a')\n\n\nx = 1 + 2\n";
const LOCK: &str = r#"{"version": 1, "packages": {"vendored": {"version": "1.0", "roots": ["vendored"], "files": ["vendored/__init__.py"], "source": "", "sha256": "", "license": null, "requires": [], "verdict": "ok", "problems": 0}}}"#;

fn folder(name: &str) -> PathBuf {
    plugin(
        name,
        &[
            ("plugin.json", MANIFEST),
            ("main.py", UGLY),
            ("pkg/util.py", "def f(a):\n  return a*2\n"),
            ("test.py", "x=1\n"),
            ("tests/test_a.py", "y=2\n"),
            ("vendored/__init__.py", "z=3\n"),
            ("wmpy.lock", LOCK),
        ],
    )
}

#[test]
fn formats_the_plugins_files_and_leaves_tests_and_vendored_packages() {
    let dir = folder("plain");
    let (code, out, err) = cli_in(&dir, &["fmt"]);
    assert_eq!(code, 0, "{out}{err}");
    assert_eq!(read(&dir, "main.py"), TIDY);
    assert_eq!(read(&dir, "pkg/util.py"), "def f(a):\n    return a * 2\n");
    assert_eq!(read(&dir, "test.py"), "x=1\n", "tests wait for --all");
    assert_eq!(read(&dir, "tests/test_a.py"), "y=2\n");
    assert_eq!(read(&dir, "vendored/__init__.py"), "z=3\n", "a vendored package is not the plugin's code");
    assert!(err.contains("formatted main.py") && err.contains("formatted 2 files, 0 already formatted"), "{err}");
    // Once is enough.
    let (code, _, err) = cli_in(&dir, &["fmt"]);
    assert_eq!(code, 0);
    assert!(err.contains("2 files already formatted"), "{err}");

    let (code, _, _) = cli_in(&dir, &["fmt", "--all"]);
    assert_eq!(code, 0);
    assert_eq!(read(&dir, "test.py"), "x = 1\n");
    assert_eq!(read(&dir, "tests/test_a.py"), "y = 2\n");
    assert_eq!(read(&dir, "vendored/__init__.py"), "z=3\n");
}

#[test]
fn check_and_diff_change_nothing_and_fail_when_needed() {
    let dir = folder("check");
    let (code, out, err) = cli_in(&dir, &["fmt", "--check"]);
    assert_eq!(code, 1, "{out}{err}");
    assert!(err.contains("would format main.py") && err.contains("would format pkg/util.py"), "{err}");
    assert!(err.contains("2 files would be formatted"), "{err}");
    assert_eq!(read(&dir, "main.py"), UGLY);

    let (code, out, _) = cli_in(&dir, &["fmt", "--diff"]);
    assert_eq!(code, 1);
    assert!(out.contains("--- a/main.py\n+++ b/main.py\n@@ -1,4 +1,8 @@\n from wm import ui\n+\n+\n def render():\n-\tui.label('x' ,key='a')\n-x=1+2\n+    ui.label('x', key='a')\n"), "{out}");
    assert!(out.contains("--- a/pkg/util.py"), "{out}");
    assert_eq!(read(&dir, "main.py"), UGLY);

    let (code, out, _) = cli_in(&dir, &["fmt", "--check", "--json"]);
    assert_eq!(code, 1);
    let v: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    assert_eq!(v["ok"], false);
    assert_eq!(v["checked"], 2);
    assert_eq!(v["changed"], serde_json::json!(["main.py", "pkg/util.py"]));

    cli_in(&dir, &["fmt"]);
    let (code, out, _) = cli_in(&dir, &["fmt", "--check", "--diff"]);
    assert_eq!((code, out.as_str()), (0, ""));
}

#[test]
fn named_files_and_folders() {
    let dir = folder("named");
    let root = dir.parent().unwrap();
    let name = dir.file_name().unwrap().to_str().unwrap();
    // From outside the folder: a test file named is formatted, nothing else is.
    let (code, _, err) = cli_in(root, &["fmt", &format!("{name}/test.py")]);
    assert_eq!(code, 0, "{err}");
    assert!(err.contains(&format!("formatted {name}/test.py")), "{err}");
    assert_eq!(read(&dir, "test.py"), "x = 1\n");
    assert_eq!(read(&dir, "main.py"), UGLY);
    // A folder inside the plugin: only what is under it.
    let (code, _, _) = cli_in(&dir, &["fmt", "tests"]);
    assert_eq!(code, 0);
    assert_eq!(read(&dir, "tests/test_a.py"), "y = 2\n");
    assert_eq!(read(&dir, "main.py"), UGLY);
    // The plugin folder by name.
    let (code, _, _) = cli_in(root, &["fmt", name]);
    assert_eq!(code, 0);
    assert_eq!(read(&dir, "main.py"), TIDY);
}

#[test]
fn files_outside_a_plugin_are_refused() {
    let dir = scratch("outside");
    std::fs::write(dir.join("loose.py"), "x=1\n").unwrap();
    let (code, _, err) = cli_in(&dir, &["fmt", "loose.py"]);
    assert_eq!(code, 2);
    assert!(err.contains("is not in a plugin folder"), "{err}");
    assert_eq!(read(&dir, "loose.py"), "x=1\n");
    let plugin_dir = folder("not-python");
    let (code, _, err) = cli_in(&plugin_dir, &["fmt", "plugin.json"]);
    assert_eq!(code, 2);
    assert!(err.contains("is not a Python file"), "{err}");
    let (code, _, _) = cli_in(&plugin_dir, &["fmt", "nothing.py"]);
    assert_eq!(code, 2);
}
