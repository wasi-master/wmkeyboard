//! `wmpy preview`: a plugin's panel in the browser, drawn by the web
//! editor's own renderer and run by [crate::devhost], live as you edit.
//!
//! ```text
//! wmpy preview [dir] [--port N] [--host 127.0.0.1] [--no-open] [--print-url]
//!              [--theme light|dark] [--mock file.json] [--set key=value]... [--no-watch]
//! ```
//!
//! The server listens on loopback only. Every request needs the random
//! token the page URL carries (`/?t=<token>`; the API takes `?t=` too), and
//! a `Host` of 127.0.0.1, localhost or [::1] with the right port, so neither
//! another site nor a DNS rebinding can drive the plugin. Nothing forbids
//! framing: editors show the page in a VS Code webview or a JetBrains JCEF
//! browser (`?embed=1` keeps only the panel and a console strip).
//!
//! * `GET /` the page; `/app.js`, `/app.css` its bundle (preview-ui/dist).
//! * `GET /api/state` the state as JSON; `GET /api/events` the same as
//!   server-sent events (`event: state`), sent again on every change.
//! * `POST /api/event` an event for the plugin (`{"type", "id", ...}`),
//!   answered with `{"ok", "error", "version"}` once it ran.
//! * `POST /api/reload` (`{"theme"?: "dark"|"light"}`), `/api/field`
//!   (`{"text", "cursor", "selection"?}`), `/api/insert` and `/api/copy`
//!   (`{"text"}`), `/api/clipboard` (`{"text"}`), `/api/dialog`
//!   (`{"button": index|null}`), `/api/storage` (`{"remove": key}` or
//!   `{"set": key, "value": text}`), `/api/console/clear`.
//!
//! `--print-url` prints one line, `{"url": ..., "port": ...}`, once the
//! server listens, and opens no browser; logs go to stderr.

use std::io::{Read, Write};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};

use crate::devhost::{parse_set, DevHost, Options};

const APP_JS: &[u8] = include_bytes!("../../preview-ui/dist/app.js");
const APP_CSS: &[u8] = include_bytes!("../../preview-ui/dist/app.css");
/// The largest request body the API reads.
const MAX_BODY: u64 = 2 << 20;
/// How long an API call waits for the plugin before giving up.
const API_WAIT: Duration = Duration::from_secs(30);
/// Fastest the page is sent a new state (a 30 Hz sensor would otherwise flood it).
const MIN_PUSH: Duration = Duration::from_millis(30);

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// The port to listen on; 0 picks a free one
    #[arg(long, default_value_t = 0)]
    pub port: u16,
    /// The address to listen on: 127.0.0.1, localhost or ::1 (loopback only)
    #[arg(long, default_value = "127.0.0.1")]
    pub host: String,
    /// Do not open a browser
    #[arg(long)]
    pub no_open: bool,
    /// Print {"url", "port"} as one JSON line once listening, for editors (opens no browser)
    #[arg(long)]
    pub print_url: bool,
    /// The page's theme and the one the plugin sees (plugin.dark)
    #[arg(long, value_parser = ["dark", "light"], default_value = "light")]
    pub theme: String,
    /// A JSON file answering host calls by op name, as `wmpy test` reads it
    #[arg(long)]
    pub mock: Option<PathBuf>,
    /// A setting's value for this run, over plugin.json's default (repeatable)
    #[arg(long = "set", value_name = "KEY=VALUE", value_parser = parse_set)]
    pub sets: Vec<(String, String)>,
    /// Do not reload when a file in the folder changes
    #[arg(long)]
    pub no_watch: bool,
}

/// What a request thread asks of the thread that owns the plugin.
enum Cmd {
    /// A new server-sent events stream: send it every state.
    Subscribe(Sender<String>),
    /// An API call: its path after `/api/`, its body, where the answer goes.
    Api(String, Json, Sender<Json>),
}

pub fn run(args: Args) -> u8 {
    let ip: std::net::IpAddr = match args.host.as_str() {
        "127.0.0.1" | "localhost" => [127, 0, 0, 1].into(),
        "::1" | "[::1]" => std::net::Ipv6Addr::LOCALHOST.into(),
        other => {
            eprintln!("error: --host {other}: wmpy preview listens on this computer only (127.0.0.1, localhost or ::1)");
            return 2;
        }
    };
    let opts = Options { dir: args.dir.clone(), mock: args.mock.clone(), sets: args.sets.clone(), dark: args.theme == "dark", watch: !args.no_watch };
    let mut host = match DevHost::open(opts) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let server = match tiny_http::Server::http(SocketAddr::new(ip, args.port)) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("error: cannot listen on {}:{}: {e}", args.host, args.port);
            return 2;
        }
    };
    let Some(port) = server.server_addr().to_ip().map(|a| a.port()) else {
        eprintln!("error: the server has no port");
        return 2;
    };
    let token = match token() {
        Ok(t) => t,
        Err(e) => {
            eprintln!("error: no random token: {e}");
            return 2;
        }
    };
    let shown_host = if ip.is_ipv6() { "[::1]".to_string() } else { "127.0.0.1".to_string() };
    let url = format!("http://{shown_host}:{port}/?t={token}");
    if args.print_url {
        println!("{}", json!({"url": url, "port": port}));
        let _ = std::io::stdout().flush();
    } else {
        eprintln!("wmpy preview: {} at {url}", host.facts.name);
        eprintln!("Saving a file in {} reloads it. Ctrl-C stops.", args.dir.display());
    }
    if !args.no_open && !args.print_url {
        open_browser(&url);
    }
    let (tx, rx) = channel::<Cmd>();
    let site = Site { token, port, theme: args.theme.clone() };
    std::thread::spawn(move || {
        for request in server.incoming_requests() {
            let (tx, site) = (tx.clone(), site.clone());
            std::thread::spawn(move || handle(request, &site, &tx));
        }
    });
    serve(&mut host, rx);
    0
}

/// The plugin's thread: commands in, states out, ticks in between.
fn serve(host: &mut DevHost, rx: Receiver<Cmd>) {
    let mut streams: Vec<Sender<String>> = Vec::new();
    let mut sent_version = 0;
    let mut last_push = Instant::now() - MIN_PUSH;
    loop {
        let wait = host.next_wake().min(Duration::from_millis(100));
        match rx.recv_timeout(wait) {
            Ok(Cmd::Subscribe(s)) => {
                let _ = s.send(host.snapshot().to_string());
                streams.push(s);
            }
            Ok(Cmd::Api(path, body, reply)) => {
                let answer = api(host, &path, &body);
                let _ = reply.send(answer);
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
        host.tick();
        if host.version() != sent_version && last_push.elapsed() >= MIN_PUSH && !streams.is_empty() {
            let state = host.snapshot().to_string();
            streams.retain(|s| s.send(state.clone()).is_ok());
            sent_version = host.version();
            last_push = Instant::now();
        }
    }
}

fn api(host: &mut DevHost, path: &str, body: &Json) -> Json {
    let text = |k: &str| body.get(k).and_then(Json::as_str).unwrap_or("").to_string();
    match path {
        "state" => return host.snapshot(),
        "event" => {
            let ok = host.send(body.clone());
            return json!({"ok": ok, "error": host.error.as_ref().map(crate::devhost::diag_json), "version": host.version()});
        }
        "reload" => match body.get("theme").and_then(Json::as_str) {
            Some(t @ ("dark" | "light")) if (t == "dark") != host.opts.dark => host.set_dark(t == "dark"),
            _ => host.reload("the page asked"),
        },
        "field" => {
            let selection = body.get("selection").and_then(Json::as_array).and_then(|a| Some((a.first()?.as_u64()? as usize, a.get(1)?.as_u64()? as usize)));
            let t = text("text");
            let cursor = body.get("cursor").and_then(Json::as_u64).map_or(t.chars().count(), |c| c as usize);
            host.set_field(&t, cursor, selection);
        }
        "insert" => host.insert(&text("text")),
        "copy" => host.copy(&text("text")),
        "clipboard" => host.set_clipboard(&text("text")),
        "dialog" => host.answer_dialog(body.get("button").and_then(Json::as_u64).map(|b| b as usize)),
        "storage" => {
            if let Some(key) = body.get("remove").and_then(Json::as_str) {
                host.storage_remove(key);
            } else if let Some(key) = body.get("set").and_then(Json::as_str) {
                if let Err(e) = host.storage_set(key, &text("value")) {
                    return json!({"ok": false, "message": e});
                }
            }
        }
        "console/clear" => host.clear_console(),
        _ => return json!({"ok": false, "message": format!("no /api/{path}")}),
    }
    json!({"ok": true, "version": host.version()})
}

#[derive(Clone)]
struct Site {
    token: String,
    port: u16,
    theme: String,
}

/// 128 random bits, as hex.
fn token() -> Result<String, String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn header(name: &str, value: &str) -> tiny_http::Header {
    tiny_http::Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("ASCII header")
}

/// What every response carries. No X-Frame-Options and no frame-ancestors:
/// the page is meant to be shown inside editors.
fn respond(request: tiny_http::Request, status: u16, kind: &str, body: Vec<u8>) {
    let mut r = tiny_http::Response::from_data(body)
        .with_status_code(status)
        .with_header(header("Content-Type", kind))
        .with_header(header("Cache-Control", "no-store"))
        .with_header(header("X-Content-Type-Options", "nosniff"))
        .with_header(header("Referrer-Policy", "no-referrer"));
    if kind.starts_with("text/html") {
        r = r.with_header(header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; font-src 'self' data:; base-uri 'none'; form-action 'none'",
        ));
    }
    let _ = request.respond(r);
}

fn json_response(request: tiny_http::Request, status: u16, v: &Json) {
    respond(request, status, "application/json; charset=utf-8", v.to_string().into_bytes());
}

/// Whether [value] is a Host header this server answers: loopback by name
/// or number, on its own port.
fn host_ok(value: &str, port: u16) -> bool {
    let v = value.trim().to_ascii_lowercase();
    ["127.0.0.1", "localhost", "[::1]"].iter().any(|h| v == format!("{h}:{port}") || (port == 80 && v == *h))
}

/// The value of query parameter [key] in [url].
fn query<'a>(url: &'a str, key: &str) -> Option<&'a str> {
    url.split_once('?')?.1.split('&').find_map(|pair| {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        (k == key).then_some(v)
    })
}

/// Compare without stopping at the first difference.
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn handle(mut request: tiny_http::Request, site: &Site, tx: &Sender<Cmd>) {
    let host = request.headers().iter().find(|h| h.field.equiv("Host")).map(|h| h.value.as_str().to_string()).unwrap_or_default();
    if !host_ok(&host, site.port) {
        return respond(request, 421, "text/plain; charset=utf-8", b"wmpy preview answers only to 127.0.0.1 and localhost\n".to_vec());
    }
    let url = request.url().to_string();
    let path = url.split('?').next().unwrap_or("/").to_string();
    if path == "/favicon.ico" {
        return respond(request, 204, "image/x-icon", Vec::new());
    }
    if !query(&url, "t").is_some_and(|t| same(t, &site.token)) {
        return respond(request, 403, "text/plain; charset=utf-8", b"wmpy preview: this address needs the token from the URL wmpy printed (?t=...)\n".to_vec());
    }
    let method = request.method().clone();
    match (method, path.as_str()) {
        (tiny_http::Method::Get, "/" | "/index.html") => {
            let theme = match query(&url, "theme") {
                Some(t @ ("dark" | "light")) => t.to_string(),
                _ => site.theme.clone(),
            };
            respond(request, 200, "text/html; charset=utf-8", page(&site.token, &theme).into_bytes());
        }
        (tiny_http::Method::Get, "/app.js") => respond(request, 200, "text/javascript; charset=utf-8", APP_JS.to_vec()),
        (tiny_http::Method::Get, "/app.css") => respond(request, 200, "text/css; charset=utf-8", APP_CSS.to_vec()),
        (tiny_http::Method::Get, "/api/events") => stream(request, tx),
        (tiny_http::Method::Get, "/api/state") => call(request, tx, "state", Json::Null),
        (tiny_http::Method::Post, p) if p.starts_with("/api/") => {
            let mut body = String::new();
            if request.as_reader().take(MAX_BODY).read_to_string(&mut body).is_err() {
                return json_response(request, 400, &json!({"ok": false, "message": "the body is not UTF-8"}));
            }
            let v: Json = if body.trim().is_empty() {
                json!({})
            } else {
                match serde_json::from_str(&body) {
                    Ok(v) => v,
                    Err(e) => return json_response(request, 400, &json!({"ok": false, "message": format!("the body is not JSON: {e}")})),
                }
            };
            let name = p.trim_start_matches("/api/").to_string();
            call(request, tx, &name, v);
        }
        _ => respond(request, 404, "text/plain; charset=utf-8", b"not here\n".to_vec()),
    }
}

fn call(request: tiny_http::Request, tx: &Sender<Cmd>, name: &str, body: Json) {
    let (reply_tx, reply_rx) = channel();
    if tx.send(Cmd::Api(name.to_string(), body, reply_tx)).is_err() {
        return json_response(request, 503, &json!({"ok": false, "message": "wmpy is stopping"}));
    }
    match reply_rx.recv_timeout(API_WAIT) {
        Ok(v) => {
            let status = if v.get("message").is_some_and(|m| m.as_str().is_some_and(|m| m.starts_with("no /api/"))) { 404 } else { 200 };
            json_response(request, status, &v)
        }
        Err(_) => json_response(request, 504, &json!({"ok": false, "message": "the plugin did not answer in time"})),
    }
}

/// Server-sent events: every state the plugin's thread sends, until the page goes.
fn stream(request: tiny_http::Request, tx: &Sender<Cmd>) {
    let (state_tx, state_rx) = channel::<String>();
    if tx.send(Cmd::Subscribe(state_tx)).is_err() {
        return;
    }
    let mut w = request.into_writer();
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Accel-Buffering: no\r\nConnection: close\r\n\r\nretry: 1000\n\n";
    if w.write_all(head.as_bytes()).and_then(|_| w.flush()).is_err() {
        return;
    }
    loop {
        let piece = match state_rx.recv_timeout(Duration::from_secs(15)) {
            Ok(state) => format!("event: state\ndata: {state}\n\n"),
            // A comment now and then, so a dropped page is noticed.
            Err(RecvTimeoutError::Timeout) => ": still here\n\n".to_string(),
            Err(RecvTimeoutError::Disconnected) => return,
        };
        if w.write_all(piece.as_bytes()).and_then(|_| w.flush()).is_err() {
            return;
        }
    }
}

fn page(token: &str, theme: &str) -> String {
    format!(
        "<!doctype html>\n<html lang=\"en\" data-theme=\"{theme}\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<meta name=\"referrer\" content=\"no-referrer\">\n<title>wmpy preview</title>\n<link rel=\"stylesheet\" href=\"/app.css?t={token}\">\n</head>\n<body>\n<div id=\"app\"></div>\n<script src=\"/app.js?t={token}\"></script>\n</body>\n</html>\n"
    )
}

fn open_browser(url: &str) {
    use std::process::{Command, Stdio};
    let mut cmd = if cfg!(target_os = "macos") {
        let mut c = Command::new("open");
        c.arg(url);
        c
    } else if cfg!(windows) {
        let mut c = Command::new("cmd");
        c.args(["/C", "start", "", url]);
        c
    } else {
        let mut c = Command::new("xdg-open");
        c.arg(url);
        c
    };
    if cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().is_err() {
        eprintln!("Open {url} in a browser.");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_loopback_hosts_on_the_port_and_the_token_get_in() {
        assert!(host_ok("127.0.0.1:4100", 4100));
        assert!(host_ok("localhost:4100", 4100));
        assert!(host_ok("[::1]:4100", 4100));
        assert!(!host_ok("evil.example.com:4100", 4100));
        assert!(!host_ok("127.0.0.1:4101", 4100));
        assert!(!host_ok("", 4100));
        assert_eq!(query("/api/state?x=1&t=abc", "t"), Some("abc"));
        assert_eq!(query("/api/state", "t"), None);
        assert!(same("abc", "abc"));
        assert!(!same("abc", "abd"));
        assert!(!same("abc", "abcd"));
        let t = token().unwrap();
        assert_eq!(t.len(), 32);
        assert_ne!(t, token().unwrap());
    }
}
