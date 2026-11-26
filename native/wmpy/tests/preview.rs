//! `wmpy preview --print-url` against a plugin folder made on the fly: the
//! one JSON line, the token and Host checks, the page, the state, the
//! server-sent events and an event round trip.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::Duration;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-preview-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (f, text) in files {
        std::fs::write(dir.join(f), text).unwrap();
    }
    dir
}

struct Preview {
    child: Child,
    url: String,
    port: u16,
}

impl Drop for Preview {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn start(dir: &PathBuf, extra: &[&str]) -> Preview {
    let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy"))
        .arg("preview")
        .arg(dir)
        .arg("--print-url")
        .args(extra)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut line = String::new();
    BufReader::new(child.stdout.take().unwrap()).read_line(&mut line).unwrap();
    let v: serde_json::Value = serde_json::from_str(line.trim()).unwrap_or_else(|e| panic!("not one JSON line: {line:?}: {e}"));
    let url = v["url"].as_str().unwrap().to_string();
    let port = v["port"].as_u64().unwrap() as u16;
    assert!(url.starts_with(&format!("http://127.0.0.1:{port}/?t=")), "{url}");
    Preview { child, url, port }
}

/// One raw HTTP/1.1 request; the status line and the whole answer.
fn raw(port: u16, request: &str) -> (u16, String) {
    let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
    s.write_all(request.as_bytes()).unwrap();
    let mut out = String::new();
    let _ = s.read_to_string(&mut out);
    let status = out.split_whitespace().nth(1).and_then(|c| c.parse().ok()).unwrap_or(0);
    (status, out)
}

fn get(port: u16, path: &str) -> (u16, String) {
    raw(port, &format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"))
}

fn post(port: u16, path: &str, body: &str) -> (u16, String) {
    raw(port, &format!("POST {path} HTTP/1.1\r\nHost: localhost:{port}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()))
}

fn body(answer: &str) -> serde_json::Value {
    let text = answer.split_once("\r\n\r\n").map_or("", |(_, b)| b);
    serde_json::from_str(text).unwrap_or_else(|e| panic!("not JSON: {text:?}: {e}"))
}

const MANIFEST: &str = r#"{"id": "com.example.preview", "name": "Counter", "version": "1.0.0", "apiVersion": 5, "permissions": ["storage"]}"#;
const MAIN: &str = "from wm import plugin, ui\nstate = plugin.state(count=0, persist=True)\ndef add():\n    state.count += 1\n    print('added', state.count)\ndef render():\n    ui.label('count ' + str(state.count))\n    ui.button('Add', on_click=add)\n";

#[test]
fn the_page_state_events_and_stream_answer_with_the_token() {
    let dir = plugin("roundtrip", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let p = start(&dir, &[]);
    let token = p.url.split("?t=").nth(1).unwrap().to_string();
    assert_eq!(token.len(), 32);

    // No token, a wrong token, a foreign Host: refused.
    assert_eq!(get(p.port, "/").0, 403);
    assert_eq!(get(p.port, "/api/state?t=0123456789abcdef0123456789abcdef").0, 403);
    let (status, _) = raw(p.port, &format!("GET /?t={token} HTTP/1.1\r\nHost: attacker.example.com:{}\r\nConnection: close\r\n\r\n", p.port));
    assert_eq!(status, 421);

    // The page loads its bundle with the token, and nothing forbids framing it.
    let (status, page) = get(p.port, &format!("/?t={token}&embed=1"));
    assert_eq!(status, 200);
    assert!(page.contains(&format!("/app.js?t={token}")), "{page}");
    let head = page.split("\r\n\r\n").next().unwrap().to_ascii_lowercase();
    assert!(!head.contains("x-frame-options") && !head.contains("frame-ancestors"), "{head}");
    let (status, js) = get(p.port, &format!("/app.js?t={token}"));
    assert_eq!(status, 200);
    assert!(js.len() > 1000);
    assert_eq!(get(p.port, &format!("/app.css?t={token}")).0, 200);

    let (_, state) = get(p.port, &format!("/api/state?t={token}"));
    let state = body(&state);
    assert_eq!(state["plugin"]["name"], "Counter");
    assert_eq!(state["ui"]["root"][0]["text"], "count 0");

    // The stream sends the state at once, then again after an event.
    let mut sse = TcpStream::connect(("127.0.0.1", p.port)).unwrap();
    sse.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
    write!(sse, "GET /api/events?t={token} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\n\r\n", p.port).unwrap();
    let mut reader = BufReader::new(sse);
    let mut next_state = || -> serde_json::Value {
        let mut line = String::new();
        loop {
            line.clear();
            reader.read_line(&mut line).unwrap();
            assert!(!line.is_empty(), "the stream ended");
            if let Some(data) = line.strip_prefix("data: ") {
                return serde_json::from_str(data.trim()).unwrap();
            }
        }
    };
    assert_eq!(next_state()["ui"]["root"][0]["text"], "count 0");

    let (status, answer) = post(p.port, &format!("/api/event?t={token}"), r#"{"type": "click", "id": "button:Add"}"#);
    assert_eq!(status, 200);
    assert_eq!(body(&answer)["ok"], true);
    let mut seen = next_state();
    while seen["ui"]["root"][0]["text"] != "count 1" {
        seen = next_state();
    }
    assert!(seen["console"].as_array().unwrap().iter().any(|c| c["text"] == "added 1"), "{seen}");
    assert!(seen["storage"].as_object().unwrap().keys().any(|k| k.contains("state")));

    // An error comes back in the shared Diag shape.
    let (_, answer) = post(p.port, &format!("/api/event?t={token}"), r#"{"id": "no type"}"#);
    assert_eq!(body(&answer)["ok"], false);
    let (status, _) = post(p.port, &format!("/api/nothing?t={token}"), "{}");
    assert_eq!(status, 404);
    let (status, _) = post(p.port, &format!("/api/event?t={token}"), "not json");
    assert_eq!(status, 400);
}

#[test]
fn a_saved_file_reloads_and_a_failing_plugin_still_serves_its_error() {
    let dir = plugin("reload", &[("plugin.json", MANIFEST), ("main.py", "from wm import ui\ndef render():\n    ui.label('one')\n    1 / 0\n")]);
    let p = start(&dir, &["--theme", "dark"]);
    let token = p.url.split("?t=").nth(1).unwrap().to_string();
    let state = body(&get(p.port, &format!("/api/state?t={token}")).1);
    assert_eq!(state["theme"], "dark");
    assert_eq!(state["status"], "failed");
    assert_eq!(state["error"]["code"], "ZeroDivisionError", "{}", state["error"]);
    assert_eq!(state["error"]["line"], 4);
    assert_eq!(state["error"]["file"], "main.py");
    std::thread::sleep(Duration::from_millis(50));
    std::fs::write(dir.join("main.py"), "from wm import ui\ndef render():\n    ui.label('two')\n").unwrap();
    let mut state = state;
    for _ in 0..100 {
        std::thread::sleep(Duration::from_millis(50));
        state = body(&get(p.port, &format!("/api/state?t={token}")).1);
        if state["status"] == "ready" {
            break;
        }
    }
    assert_eq!(state["ui"]["root"][0]["text"], "two", "{state}");
    assert!(state["console"].as_array().unwrap().iter().any(|c| c["kind"] == "reload"));
}

#[test]
fn only_loopback_addresses_are_accepted() {
    let dir = plugin("host", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["preview", dir.to_str().unwrap(), "--print-url", "--host", "0.0.0.0"]).output().unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("this computer only"));
    assert!(out.stdout.is_empty());
}
