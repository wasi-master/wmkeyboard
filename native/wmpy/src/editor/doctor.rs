//! `wmpy editor doctor`'s language server check: start `wmpy lsp` the way an
//! editor does, send `initialize`, and wait for the answer.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;

fn frame(body: &str) -> String {
    format!("Content-Length: {}\r\n\r\n{body}", body.len())
}

/// Reads one LSP message from [r].
fn read_message(r: &mut impl BufRead) -> Option<serde_json::Value> {
    let mut length = None;
    loop {
        let mut line = String::new();
        if r.read_line(&mut line).ok()? == 0 {
            return None;
        }
        let line = line.trim_end();
        if line.is_empty() {
            break;
        }
        if let Some(v) = line.to_ascii_lowercase().strip_prefix("content-length:") {
            length = v.trim().parse::<usize>().ok();
        }
    }
    let mut body = vec![0; length?];
    r.read_exact(&mut body).ok()?;
    serde_json::from_slice(&body).ok()
}

/// The server's name and version when `wmpy lsp` answers `initialize`, or why not.
pub fn probe(exe: &Path) -> Result<String, String> {
    let mut child = Command::new(exe)
        .arg("lsp")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("could not start {}: {e}", exe.display()))?;
    let mut stdin = child.stdin.take().expect("stdin");
    let stdout = child.stdout.take().expect("stdout");
    let mut stderr = child.stderr.take().expect("stderr");
    let init = serde_json::json!({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"processId": std::process::id(), "rootUri": null, "capabilities": {}, "initializationOptions": {"preview": "none"}},
    });
    let _ = stdin.write_all(frame(&init.to_string()).as_bytes());
    let _ = stdin.flush();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut r = BufReader::new(stdout);
        while let Some(msg) = read_message(&mut r) {
            if msg.get("id").and_then(|i| i.as_i64()) == Some(1) {
                let _ = tx.send(msg);
                return;
            }
        }
    });
    let answer = rx.recv_timeout(Duration::from_secs(10));
    let result = match answer {
        Ok(msg) => match msg.get("result") {
            Some(r) if r.get("capabilities").is_some() => {
                let name = r.pointer("/serverInfo/name").and_then(|v| v.as_str()).unwrap_or("wmpy");
                let version = r.pointer("/serverInfo/version").and_then(|v| v.as_str()).unwrap_or("");
                Ok(format!("{name} {version}").trim().to_string())
            }
            _ => Err(format!("answered initialize without capabilities: {msg}")),
        },
        Err(_) => {
            let _ = child.kill();
            let mut err = String::new();
            let _ = stderr.read_to_string(&mut err);
            let status = child.wait().ok().and_then(|s| s.code()).map(|c| format!(", exit {c}")).unwrap_or_default();
            return Err(format!("no answer to initialize in 10 s{status}{}", if err.trim().is_empty() { String::new() } else { format!(": {}", err.trim()) }));
        }
    };
    let shutdown = serde_json::json!({"jsonrpc": "2.0", "id": 2, "method": "shutdown"});
    let exit = serde_json::json!({"jsonrpc": "2.0", "method": "exit"});
    let _ = stdin.write_all(frame(&shutdown.to_string()).as_bytes());
    let _ = stdin.write_all(frame(&exit.to_string()).as_bytes());
    drop(stdin);
    let deadline = std::time::Instant::now() + Duration::from_secs(3);
    while std::time::Instant::now() < deadline {
        if child.try_wait().ok().flatten().is_some() {
            return result;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let _ = child.kill();
    let _ = child.wait();
    result
}
