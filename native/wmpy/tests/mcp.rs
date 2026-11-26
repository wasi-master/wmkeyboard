//! `wmpy mcp` spoken to over stdio as an agent's client would, in both eras
//! of the protocol, and `wmpy mcp setup`.

use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

use serde_json::{json, Value};

const MANIFEST: &str = r#"{"format": "wmkeyboard-plugin", "id": "com.example.flip", "name": "Flip", "pluginVersion": "1.0.0", "apiVersion": 5, "permissions": []}"#;

const MAIN: &str = "from wm import plugin, ui, http\n\nstate = plugin.state(result=\"Press Flip\", count=0)\n\n\ndef flip():\n    state.count += 1\n    state.result = \"Heads\" if state.count % 2 else \"Tails\"\n\n\ndef fetch():\n    http.get(\"https://api.example.com/coin\")\n\n\ndef render():\n    with ui.column():\n        ui.title(state.result)\n        ui.button(\"Flip\", on_click=flip, primary=True)\n";

fn plugin(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-mcp-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("plugin.json"), MANIFEST).unwrap();
    std::fs::write(dir.join("main.py"), MAIN).unwrap();
    dir
}

struct Mcp {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next: i64,
}

impl Mcp {
    fn start() -> Mcp {
        let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy")).arg("mcp").stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit()).spawn().unwrap();
        let stdin = child.stdin.take().unwrap();
        let stdout = BufReader::new(child.stdout.take().unwrap());
        Mcp { child, stdin, stdout, next: 1 }
    }

    fn send(&mut self, msg: Value) {
        writeln!(self.stdin, "{msg}").unwrap();
        self.stdin.flush().unwrap();
    }

    /// The whole response to a request.
    fn raw(&mut self, method: &str, params: Value) -> Value {
        let id = self.next;
        self.next += 1;
        self.send(json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}));
        let mut line = String::new();
        self.stdout.read_line(&mut line).unwrap();
        let reply: Value = serde_json::from_str(&line).unwrap_or_else(|e| panic!("{e}: {line}"));
        assert_eq!(reply["id"], id);
        reply
    }

    fn request(&mut self, method: &str, params: Value) -> Value {
        let reply = self.raw(method, params);
        assert!(reply.get("error").is_none(), "{method}: {reply}");
        reply["result"].clone()
    }

    fn tool(&mut self, name: &str, arguments: Value) -> Value {
        let r = self.request("tools/call", json!({"name": name, "arguments": arguments}));
        assert_eq!(r["isError"], false, "{name}: {r}");
        let text = r["content"][0]["text"].as_str().unwrap();
        assert_eq!(serde_json::from_str::<Value>(text).unwrap(), r["structuredContent"], "the text block is the structured result");
        r["structuredContent"].clone()
    }
}

#[test]
fn an_agent_writes_checks_and_runs_a_plugin() {
    let dir = plugin("agent");
    let d = dir.to_str().unwrap();
    let mut mcp = Mcp::start();
    let init = mcp.request("initialize", json!({"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}));
    assert_eq!(init["protocolVersion"], "2025-11-25");
    assert!(init["capabilities"]["tools"].is_object());
    assert!(init["instructions"].as_str().unwrap().contains("check_plugin"));
    mcp.send(json!({"jsonrpc": "2.0", "method": "notifications/initialized"}));

    let tools = mcp.request("tools/list", json!({}));
    let names: Vec<&str> = tools["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
    for want in ["check_plugin", "run_tests", "render_plugin", "session_start", "session_event", "pack_plugin", "api_lookup", "list_permissions", "new_plugin", "pip_check"] {
        assert!(names.contains(&want), "{want} missing from {names:?}");
    }
    for t in tools["tools"].as_array().unwrap() {
        assert_eq!(t["inputSchema"]["type"], "object", "{}", t["name"]);
        assert!(t["description"].as_str().unwrap().len() > 40, "{} needs a real description", t["name"]);
    }

    // The API model answers.
    let button = mcp.tool("api_lookup", json!({"name": "ui.button"}));
    assert_eq!(button["found"], true, "{button}");
    let network = mcp.tool("list_permissions", json!({"name": "network"}));
    assert_eq!(network["permissions"][0]["tier"], "risky");

    // The checks find the missing permission and say how to fix it.
    let check = mcp.tool("check_plugin", json!({"dir": d}));
    let problem = check["problems"].as_array().unwrap().iter().find(|p| p["code"] == "permission_missing").unwrap_or_else(|| panic!("{check}"));
    assert_eq!(problem["file"], "main.py");
    assert_eq!(problem["line"], 1, "the import of http is what needs the permission");
    assert!(problem["help"].as_str().unwrap().contains("network"), "{problem}");

    // Draw the panel after a tap.
    let drawn = mcp.tool("render_plugin", json!({"dir": d, "events": [{"type": "click", "id": "button:Flip"}]}));
    assert_eq!(drawn["result"]["ok"], true, "{drawn}");
    assert!(drawn["result"]["ui"].to_string().contains("Heads"), "{drawn}");

    // A session, event by event.
    let started = mcp.tool("session_start", json!({"dir": d}));
    let id = started["session"].as_str().unwrap().to_string();
    assert!(started["result"]["ui"].to_string().contains("Press Flip"));
    let once = mcp.tool("session_event", json!({"session": id, "event": {"type": "click", "id": "button:Flip"}}));
    assert!(once["ui"].to_string().contains("Heads"), "{once}");
    let twice = mcp.tool("session_event", json!({"session": id, "event": {"type": "click", "id": "button:Flip"}}));
    assert!(twice["ui"].to_string().contains("Tails"), "{twice}");
    mcp.tool("session_stop", json!({"session": id}));
    let gone = mcp.request("tools/call", json!({"name": "session_render", "arguments": {"session": id}}));
    assert_eq!(gone["isError"], true);

    // Resources.
    let resources = mcp.request("resources/list", json!({}));
    assert!(resources["resources"].as_array().unwrap().iter().any(|r| r["uri"] == "wmpy://docs/api"));
    let api = mcp.request("resources/read", json!({"uri": "wmpy://docs/api"}));
    assert!(api["contents"][0]["text"].as_str().unwrap().contains("plugin.json"));
    let missing = mcp.raw("resources/read", json!({"uri": "wmpy://docs/nope"}));
    assert_eq!(missing["error"]["code"], -32002);

    let unknown = mcp.raw("tools/call", json!({"name": "no_such_tool", "arguments": {}}));
    assert_eq!(unknown["error"]["code"], -32602);
    drop(mcp.stdin);
    assert!(mcp.child.wait().unwrap().success());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_modern_client_needs_no_handshake() {
    let mut mcp = Mcp::start();
    let meta = json!({"io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {}});
    let found = mcp.request("server/discover", json!({"_meta": meta}));
    assert_eq!(found["resultType"], "complete");
    assert!(found["supportedVersions"].as_array().unwrap().iter().any(|v| v == "2026-07-28"));
    assert_eq!(found["_meta"]["io.modelcontextprotocol/serverInfo"]["name"], "wmpy");
    let tools = mcp.request("tools/list", json!({"_meta": meta}));
    assert_eq!(tools["resultType"], "complete");
    let r = mcp.request("tools/call", json!({"_meta": meta, "name": "api_search", "arguments": {"query": "vibrate"}}));
    assert_eq!(r["resultType"], "complete");
    assert!(!r["structuredContent"]["entries"].as_array().unwrap().is_empty(), "{r}");

    let old = mcp.raw("tools/list", json!({"_meta": {"io.modelcontextprotocol/protocolVersion": "1900-01-01", "io.modelcontextprotocol/clientCapabilities": {}}}));
    assert_eq!(old["error"]["code"], -32022);
    assert!(old["error"]["data"]["supported"].as_array().unwrap().iter().any(|v| v == "2026-07-28"));
    let bare = mcp.raw("tools/list", json!({"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}));
    assert_eq!(bare["error"]["code"], -32602);
    let missing = mcp.raw("resources/read", json!({"_meta": meta, "uri": "wmpy://docs/nope"}));
    assert_eq!(missing["error"]["code"], -32602);
}

#[test]
fn setup_prints_and_writes_client_configs() {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["mcp", "setup", "claude", "--command", "wmpy"]).output().unwrap();
    let text = String::from_utf8_lossy(&out.stdout);
    assert!(text.contains("claude mcp add wmpy -- wmpy mcp"), "{text}");

    let home = std::env::temp_dir().join(format!("wmpy-mcp-home-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    let cursor = home.join(".cursor").join("mcp.json");
    std::fs::create_dir_all(cursor.parent().unwrap()).unwrap();
    std::fs::write(&cursor, r#"{"mcpServers": {"other": {"command": "x"}}}"#).unwrap();
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["mcp", "setup", "cursor", "--write", "--command", "wmpy"]).env("HOME", &home).env("USERPROFILE", &home).output().unwrap();
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    let v: Value = serde_json::from_str(&std::fs::read_to_string(&cursor).unwrap()).unwrap();
    assert_eq!(v["mcpServers"]["wmpy"], json!({"command": "wmpy", "args": ["mcp"]}));
    assert_eq!(v["mcpServers"]["other"]["command"], "x");
    assert!(home.join(".cursor").join("mcp.json.wmpy-backup").is_file());

    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["mcp", "setup", "zed", "--command", "wmpy"]).env("HOME", &home).output().unwrap();
    let text = String::from_utf8_lossy(&out.stdout);
    assert!(text.contains("context_servers"), "{text}");
    let _ = std::fs::remove_dir_all(&home);
}
