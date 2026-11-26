//! `wmpy repl` with a script on stdin: echoed values, blocks, the plugin's
//! modules, the panel commands, errors with their line, and Ctrl-C.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

fn folder(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-runtime-repl-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

/// Runs `wmpy repl` in [cwd] with [input] on stdin: (code, stdout, stderr).
fn repl(cwd: &Path, args: &[&str], input: &str) -> (i32, String, String) {
    let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy"))
        .arg("repl")
        .args(args)
        .current_dir(cwd)
        .env("NO_COLOR", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input.as_bytes()).unwrap();
    let out = child.wait_with_output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into_owned(), String::from_utf8_lossy(&out.stderr).into_owned())
}

const MANIFEST: &str = r#"{"id": "com.example.counter", "name": "Counter", "version": "1.0.0", "apiVersion": 5, "permissions": ["storage", "field.write"]}"#;
const MAIN: &str = "from wm import plugin, ui, field\nstate = plugin.state(count=0, persist=True)\n\ndef add():\n    state.count += 1\n    field.insert('+')\n\ndef render():\n    ui.label('count ' + str(state.count))\n    ui.button('Add', on_click=add)\n";
const HELPERS: &str = "def double(n):\n    return n * 2\n";

#[test]
fn a_script_on_stdin_echoes_values_runs_blocks_and_imports_the_plugin() {
    let dir = folder("plugin", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS)]);
    let input = "x = 1 + 2\nx\ndef f(n):\n    return n * 2\n\nf(21)\nfor i in range(2):\n    print(i)\nimport helpers\nhelpers.double(4)\n'text'\n_\nimport main\nmain.state.count\n";
    let (code, out, err) = repl(&dir, &[], input);
    assert_eq!(code, 0, "{out}\n{err}");
    let lines: Vec<&str> = out.lines().collect();
    assert_eq!(lines, vec!["3", "42", "0", "1", "8", "'text'", "'text'", "0"], "{out}\n{err}");
}

#[test]
fn errors_show_the_line_typed_and_the_session_goes_on() {
    let dir = folder("errors", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, err) = repl(&dir, &[], "y = 5\n1 / 0\ny + 1\nundefined_name\n");
    assert_eq!(code, 1, "a failed input fails a script: {out}\n{err}");
    assert_eq!(out.trim(), "6", "{out}\n{err}");
    assert!(err.contains("error[ZeroDivisionError]"), "{err}");
    assert!(err.contains("--> <input>:1") && err.contains("1 / 0"), "{err}");
    assert!(err.contains("NameError"), "{err}");
}

#[test]
fn the_panel_draws_takes_events_and_shows_storage_and_calls() {
    let dir = folder("panel", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, err) = repl(&dir, &[], ":render\n:event button:Add\n:event {\"type\": \"click\", \"id\": \"button:Add\"}\n:storage\n:calls\n:help\n:nope\n");
    assert_eq!(code, 1, "the unknown command fails the script: {out}\n{err}");
    assert!(out.contains("── Counter"), "{out}");
    assert!(out.contains("count 0") && out.contains("[ Add ]"), "{out}");
    assert!(out.contains("clicks: button:Add"), "{out}");
    assert!(out.contains("count 2"), "{out}\n{err}");
    assert!(out.contains("the panel's storage") && out.contains("count"), "{out}");
    assert!(out.contains("field.insert"), "{out}");
    assert!(out.contains(":event <json>"), "{out}");
    assert!(err.contains("no command :nope"), "{err}");
    assert!(err.contains("[field] insert"), "the panel's notes: {err}");
}

#[test]
fn outside_a_plugin_every_permission_is_on() {
    let dir = folder("loose", &[]);
    let input = "from wm import field, clipboard\nfield.insert('hi')\nfield.text_before(10)\nwm.storage['k'] = 1\nwm.storage['k']\n:render\n";
    let (code, out, err) = repl(&dir, &[], input);
    assert_eq!(out.lines().take(2).collect::<Vec<_>>(), vec!["'hi'", "1"], "{out}\n{err}");
    assert!(err.contains("no panel outside a plugin folder"), "{err}");
    assert_eq!(code, 1);
}

#[test]
fn a_permission_the_manifest_lacks_is_refused() {
    let dir = folder("perm", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (_, out, err) = repl(&dir, &[], "from wm import location\nlocation.get()\n");
    assert!(out.trim().is_empty(), "{out}");
    assert!(err.contains("PermissionError") && err.contains("'location' permission"), "{err}");
}

#[cfg(unix)]
#[test]
fn ctrl_c_stops_a_runaway_loop_and_the_session_goes_on() {
    let dir = folder("spin", &[]);
    let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy"))
        .arg("repl")
        .current_dir(&dir)
        .env("NO_COLOR", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(b"print('spinning')\nwhile True:\n    pass\n\n40 + 2\n").unwrap();
    // Once the loop runs, Ctrl-C.
    let mut stdout = BufReader::new(child.stdout.take().unwrap());
    let mut first = String::new();
    stdout.read_line(&mut first).unwrap();
    assert_eq!(first.trim(), "spinning");
    std::thread::sleep(Duration::from_millis(300));
    let pid = child.id().to_string();
    Command::new("kill").args(["-INT", &pid]).status().unwrap();
    let until = Instant::now() + Duration::from_secs(60);
    while child.try_wait().unwrap().is_none() {
        if Instant::now() > until {
            let _ = child.kill();
            panic!("the loop did not stop after Ctrl-C");
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let mut rest = String::new();
    stdout.read_to_string(&mut rest).unwrap();
    let mut stderr = String::new();
    child.stderr.take().unwrap().read_to_string(&mut stderr).unwrap();
    assert!(stderr.contains("KeyboardInterrupt"), "{stderr}");
    assert_eq!(rest.trim(), "42", "{rest}\n{stderr}");
}
