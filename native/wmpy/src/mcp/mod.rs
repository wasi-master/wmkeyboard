//! `wmpy mcp`: a Model Context Protocol server over stdin and stdout, for AI
//! agents writing WM Keyboard plugins. `wmpy mcp setup <client>` prints (or
//! writes) the config that adds it to a client.
//!
//! JSON-RPC 2.0, one message a line, hand-rolled: the server is synchronous
//! and its plugin sessions are single-threaded (`Rc`), so an async SDK such
//! as rmcp (tokio, schemars, futures) would add a runtime and nothing else.
//!
//! It speaks both eras of the protocol. A request whose `_meta` names
//! `io.modelcontextprotocol/protocolVersion` is served statelessly as
//! revision 2026-07-28 (with `server/discover`); an `initialize` request
//! starts the handshake of 2025-11-25 and earlier for the rest of the process.

mod docs;
pub(crate) mod permissions;
mod sessions;
mod setup;
mod tools;

use std::io::{BufRead, Write};

use serde_json::{json, Value};

#[derive(clap::Args, Debug)]
#[command(args_conflicts_with_subcommands = true)]
pub struct Args {
    /// Accepted for clients that pass it; stdio is the only transport
    #[arg(long)]
    pub stdio: bool,
    #[command(subcommand)]
    pub command: Option<Sub>,
}

#[derive(clap::Subcommand, Debug)]
pub enum Sub {
    /// Print the config that adds this server to an AI client, or write it with --write
    Setup(setup::Args),
}

/// The modern revision this server speaks.
const MODERN: &[&str] = &["2026-07-28"];
/// The handshake revisions it answers `initialize` for, newest first.
const LEGACY: &[&str] = &["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

const VERSION_KEY: &str = "io.modelcontextprotocol/protocolVersion";
const CAPS_KEY: &str = "io.modelcontextprotocol/clientCapabilities";

const INSTRUCTIONS: &str = "Tools for WM Keyboard plugins: Python that runs inside the Android keyboard. \
A plugin is a folder with plugin.json and main.py (API 5: `from wm import plugin, ui`, other .py files are modules). \
Workflow: new_plugin (or open an existing folder), read_docs topic api or api_search to find what the keyboard offers, \
edit main.py and plugin.json, check_plugin after every edit and fix what it reports (its `help` names the fix), \
render_plugin to see the panel, session_start and session_event to click through it, run_tests for test.py, pack_plugin to build the .wmplugin. \
Host calls (http, ai, field, ...) never leave the machine here: answer them with a mock. \
Risky permissions (network, ai, field.read, clipboard.read, location, files, calendar, todo) start off on the phone; catch PermissionError.";

pub fn run(args: Args) -> u8 {
    if let Some(Sub::Setup(a)) = args.command {
        return setup::run(a);
    }
    let mut server = Server {
        legacy: None,
        tools: tools::Tools { exe: std::env::current_exe().unwrap_or_else(|_| "wmpy".into()), sessions: Default::default() },
    };
    let stdin = std::io::stdin();
    let mut stdout = std::io::stdout();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        let reply = match serde_json::from_str::<Value>(&line) {
            Err(e) => Some(error(Value::Null, -32700, &format!("not JSON: {e}"), None)),
            Ok(Value::Array(batch)) => {
                let replies: Vec<Value> = batch.into_iter().filter_map(|m| server.message(m)).collect();
                (!replies.is_empty()).then_some(Value::Array(replies))
            }
            Ok(m) => server.message(m),
        };
        if let Some(reply) = reply {
            let mut text = reply.to_string();
            text.push('\n');
            if stdout.write_all(text.as_bytes()).and_then(|_| stdout.flush()).is_err() {
                break;
            }
        }
    }
    0
}

fn error(id: Value, code: i64, message: &str, data: Option<Value>) -> Value {
    let mut e = json!({"code": code, "message": message});
    if let Some(d) = data {
        e["data"] = d;
    }
    json!({"jsonrpc": "2.0", "id": id, "error": e})
}

fn server_info() -> Value {
    json!({"name": "wmpy", "title": "WM Keyboard plugins", "version": env!("CARGO_PKG_VERSION")})
}

fn capabilities() -> Value {
    json!({"tools": {"listChanged": false}, "resources": {"listChanged": false}})
}

struct Server {
    /// The revision `initialize` agreed on, once a legacy client sent one.
    legacy: Option<String>,
    tools: tools::Tools,
}

impl Server {
    /// The reply to one message; None for a notification.
    fn message(&mut self, m: Value) -> Option<Value> {
        let method = m.get("method").and_then(Value::as_str).map(str::to_string);
        let id = m.get("id").cloned();
        let params = m.get("params").cloned().unwrap_or(json!({}));
        let (Some(method), Some(id)) = (method, id) else {
            // Notifications (initialized, cancelled) and stray responses need no answer.
            return None;
        };
        if id.is_null() {
            return Some(error(Value::Null, -32600, "a request id must not be null", None));
        }
        let meta = params.get("_meta");
        let modern = match meta.and_then(|m| m.get(VERSION_KEY)) {
            Some(v) => {
                let v = v.as_str().unwrap_or("");
                if !MODERN.contains(&v) {
                    let supported: Vec<&str> = MODERN.iter().chain(LEGACY).copied().collect();
                    return Some(error(id, -32022, "Unsupported protocol version", Some(json!({"supported": supported, "requested": v}))));
                }
                if meta.and_then(|m| m.get(CAPS_KEY)).is_none() {
                    return Some(error(id, -32602, &format!("_meta needs {CAPS_KEY}"), None));
                }
                true
            }
            None => false,
        };
        let result = match method.as_str() {
            "initialize" if !modern => {
                let asked = params.get("protocolVersion").and_then(Value::as_str).unwrap_or("");
                let agreed = if LEGACY.contains(&asked) { asked } else { LEGACY[0] };
                self.legacy = Some(agreed.to_string());
                Ok(json!({"protocolVersion": agreed, "capabilities": capabilities(), "serverInfo": server_info(), "instructions": INSTRUCTIONS}))
            }
            "server/discover" => Ok(json!({
                "supportedVersions": MODERN.iter().chain(LEGACY).collect::<Vec<_>>(),
                "capabilities": capabilities(),
                "instructions": INSTRUCTIONS,
            })),
            "ping" => Ok(json!({})),
            "tools/list" => Ok(json!({"tools": tools::list()})),
            "tools/call" => self.call(&params),
            "resources/list" => Ok(json!({"resources": docs::RESOURCES.iter().map(|r| json!({
                "uri": r.uri, "name": r.name, "title": r.title, "description": r.description, "mimeType": "text/markdown",
            })).collect::<Vec<_>>()})),
            "resources/templates/list" => Ok(json!({"resourceTemplates": []})),
            "resources/read" => {
                let uri = params.get("uri").and_then(Value::as_str).unwrap_or("");
                match docs::read(uri) {
                    Some(text) => Ok(json!({"contents": [{"uri": uri, "mimeType": "text/markdown", "text": text}]})),
                    None => {
                        let code = if modern { -32602 } else { -32002 };
                        return Some(error(id, code, "Resource not found", Some(json!({"uri": uri}))));
                    }
                }
            }
            other => Err((-32601, format!("Method not found: {other}"))),
        };
        Some(match result {
            Ok(mut r) => {
                if modern {
                    r["resultType"] = json!("complete");
                    r["_meta"] = json!({"io.modelcontextprotocol/serverInfo": server_info()});
                }
                json!({"jsonrpc": "2.0", "id": id, "result": r})
            }
            Err((code, message)) => error(id, code, &message, None),
        })
    }

    fn call(&mut self, params: &Value) -> Result<Value, (i64, String)> {
        let name = params.get("name").and_then(Value::as_str).ok_or((-32602, "tools/call needs a tool name".to_string()))?;
        let args = params.get("arguments").cloned().unwrap_or(json!({}));
        if !args.is_object() {
            return Err((-32602, "tool arguments must be an object".into()));
        }
        let outcome = self.tools.call(name, &args).ok_or((-32602, format!("Unknown tool: {name}")))?;
        let text = serde_json::to_string_pretty(&outcome.value).unwrap_or_default();
        let structured = if outcome.value.is_object() { outcome.value } else { json!({"value": outcome.value}) };
        Ok(json!({"content": [{"type": "text", "text": text}], "structuredContent": structured, "isError": outcome.error}))
    }
}
