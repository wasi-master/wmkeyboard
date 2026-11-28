//! `wmpy mock record`: real replies written down as a mock file for tests.
//!
//! ```text
//! wmpy mock record [dir] --out mocks/name.json [--scenario file] [--tests [-k text]] [--redact NAME]...
//! ```
//!
//! The plugin runs on the dev host, with real https to its manifest's hosts
//! and pretend answers for what only a phone has, through a scenario, its
//! tests (each session answered by the dev host, a test's own `mock=` left
//! aside) or a click on each button of its first draw. Every host call and
//! its reply goes into the file in the format `plugin_test.session(mock=)`
//! and `--mock` read:
//!
//! * `http.request` as routes, `{"when": {"method", "url"}, "reply": ...}`,
//!   one per address; the same call made again is kept once. The address
//!   is cut to a `*` pattern where a secret or a key-like query value starts,
//!   so the route still fits when the test's key differs.
//! * every other op as its reply, or as routes by its arguments when the
//!   replies differ; `settings.set` is left out, as the mock answers it.
//!
//! Secrets are redacted, as `<redacted>`: Authorization, Cookie,
//! Set-Cookie and X-Api-Key-like headers, query values and JSON fields named
//! key, token, api_key, apikey, access_token, secret, password or sig (and
//! each `--redact` name), the plugin's secrets and what `secrets.get`
//! answered, wherever they appear.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{json, Map, Value as Json};

use crate::budget::drive::{self, HostCall, Setup};
use crate::budget::{plan_of, suite};
use crate::diag::color_stderr;

#[derive(clap::Args, Debug)]
pub struct Args {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(clap::Subcommand, Debug)]
pub enum Command {
    /// Run the plugin with real https and write every host call's reply to a mock file
    Record(RecordArgs),
}

#[derive(clap::Args, Debug)]
pub struct RecordArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Where to write the mock file, such as mocks/weather.json
    #[arg(long, short = 'o', value_name = "FILE")]
    pub out: PathBuf,
    /// A JSON list of events to run instead of the default clicks, or
    /// {"events": [...], "mock": {...}, "settings": {...}}
    #[arg(long, value_name = "FILE", conflicts_with = "tests")]
    pub scenario: Option<PathBuf>,
    /// Run the plugin's tests instead (their own mocks are not used)
    #[arg(long)]
    pub tests: bool,
    /// Run only the tests whose file::name contains this text (implies --tests)
    #[arg(short = 'k', value_name = "TEXT")]
    pub filter: Option<String>,
    #[command(flatten)]
    pub look: crate::look::Look,
    /// Also redact headers, query values and JSON fields with this name (repeatable)
    #[arg(long, value_name = "NAME")]
    pub redact: Vec<String>,
}

/// Query values and JSON fields redacted by default.
pub const SECRET_NAMES: &[&str] = &["key", "token", "api_key", "apikey", "access_token", "secret", "password", "sig"];
/// Query values that change on every call, cut to `*` in a route.
const VOLATILE_NAMES: &[&str] = &["_", "t", "ts", "timestamp", "nonce", "cb", "cachebust", "rand", "random"];
pub const REDACTED: &str = "<redacted>";

pub fn run(args: Args) -> u8 {
    match args.command {
        Command::Record(a) => record(a),
    }
}

fn record(args: RecordArgs) -> u8 {
    let dir = match crate::project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            eprint!("{}", d.render(&|_| None, color_stderr()));
            return 2;
        }
    };
    let config = match crate::config::load_or_report(&dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    args.look.apply(&dir, &config);
    let plan = match plan_of(args.scenario.as_deref(), args.tests || args.filter.is_some(), args.filter.clone()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let setup = Setup { dir: &dir, home: &dir, plan: &plan, mock: None, settle_for: Duration::from_secs(20), backend: suite::Backend::Host, prepare: None };
    let outcome = match drive::run(&setup) {
        Ok(o) => o,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let mut r = Redactor::new(&args.redact, &outcome.secrets);
    let mock = build(&outcome.calls, &mut r);
    let text = serde_json::to_string_pretty(&mock).unwrap_or_default() + "\n";
    if let Some(parent) = args.out.parent().filter(|p| !p.as_os_str().is_empty()) {
        if let Err(e) = std::fs::create_dir_all(parent) {
            eprintln!("error: cannot make {}: {e}", parent.display());
            return 2;
        }
    }
    if let Err(e) = std::fs::write(&args.out, &text) {
        eprintln!("error: cannot write {}: {e}", args.out.display());
        return 2;
    }
    let routes = mock.get("http.request").and_then(Json::as_array).map_or(0, Vec::len);
    let from = |want: &str| outcome.calls.iter().filter(|c| matches!(c, HostCall::Answered { source, .. } | HostCall::Started { source, .. } if *source == want)).count();
    let (real, pretend) = (from("real"), from("pretend"));
    let others = mock.as_object().map_or(0, |o| o.len()) - usize::from(mock.get("http.request").is_some());
    eprintln!(
        "wrote {}: {} http route{}, {} other op{}, {} value{} redacted, from {} ({real} calls answered over the network, {pretend} by the dev host's pretend phone)",
        args.out.display(),
        routes,
        if routes == 1 { "" } else { "s" },
        others,
        if others == 1 { "" } else { "s" },
        r.count,
        if r.count == 1 { "" } else { "s" },
        outcome.what
    );
    if !outcome.loaded {
        eprintln!("the plugin did not load, so the file holds only what its load asked for");
    }
    for s in outcome.steps.iter().filter(|s| s.error.is_some()).take(3) {
        if let Some(d) = &s.error {
            eprint!("in {}:\n{}", s.label, d.render(&crate::diag::folder_source(&dir), color_stderr()));
        }
    }
    for (id, _) in &outcome.failures {
        eprintln!("  = note: {id} failed while recording; the replies it got are in the file");
    }
    for n in &outcome.notes {
        eprintln!("  = note: {n}");
    }
    let shown = relative_to(&args.out, &dir);
    eprintln!("use it in a test:\n    import json\n    s = plugin_test.session(mock=json.loads(plugin_test.read(\"{shown}\")))");
    if relative(&args.out, &dir).is_none() {
        eprintln!("  = note: plugin_test.read reads files inside the plugin folder: move the file there");
    }
    u8::from(!outcome.loaded)
}

fn relative(path: &Path, dir: &Path) -> Option<String> {
    let abs = |p: &Path| std::fs::canonicalize(p).ok();
    let (p, d) = (abs(path)?, abs(dir)?);
    p.strip_prefix(&d).ok().map(|r| r.to_string_lossy().replace('\\', "/"))
}

fn relative_to(path: &Path, dir: &Path) -> String {
    relative(path, dir).unwrap_or_else(|| path.to_string_lossy().into_owned())
}

/// What gets redacted, and how many values were.
pub struct Redactor {
    names: Vec<String>,
    secrets: Vec<String>,
    pub count: usize,
}

impl Redactor {
    pub fn new(extra: &[String], secrets: &[String]) -> Redactor {
        let mut names: Vec<String> = SECRET_NAMES.iter().map(|s| s.to_string()).collect();
        names.extend(extra.iter().map(|s| s.trim().to_ascii_lowercase()).filter(|s| !s.is_empty()));
        let mut r = Redactor { names, secrets: Vec::new(), count: 0 };
        for s in secrets {
            r.add_secret(s);
        }
        r
    }

    /// A value to take out wherever it appears; short ones would take out too much.
    pub fn add_secret(&mut self, s: &str) {
        if s.chars().count() >= 4 && s != REDACTED && !self.secrets.iter().any(|x| x == s) {
            self.secrets.push(s.to_string());
            // Longest first, so a secret inside another goes with it.
            self.secrets.sort_by_key(|s| std::cmp::Reverse(s.len()));
        }
    }

    fn secret_name(&self, name: &str) -> bool {
        let n = name.to_ascii_lowercase();
        self.names.iter().any(|x| *x == n)
    }

    fn secret_header(&self, name: &str) -> bool {
        let n = name.to_ascii_lowercase();
        matches!(n.as_str(), "authorization" | "proxy-authorization" | "cookie" | "set-cookie")
            || ["api-key", "apikey", "api_key", "token", "secret", "password", "session", "signature"].iter().any(|x| n.contains(x))
            || self.secret_name(&n)
    }

    /// [text] with every secret value replaced.
    pub fn scrub(&mut self, text: &str) -> String {
        let mut out = text.to_string();
        for s in &self.secrets {
            if out.contains(s.as_str()) {
                self.count += out.matches(s.as_str()).count();
                out = out.replace(s.as_str(), REDACTED);
            }
        }
        out
    }

    /// A URL with secret query values and secret text redacted.
    pub fn url(&mut self, url: &str) -> String {
        let (base, query) = match url.split_once('?') {
            Some((b, q)) => (b, Some(q)),
            None => (url, None),
        };
        let mut out = self.scrub(base);
        if let Some(q) = query {
            let parts: Vec<String> = q
                .split('&')
                .map(|pair| match pair.split_once('=') {
                    Some((k, v)) if self.secret_name(&decode(k)) && !v.is_empty() => {
                        self.count += 1;
                        format!("{k}={REDACTED}")
                    }
                    _ => self.scrub(pair),
                })
                .collect();
            out.push('?');
            out.push_str(&parts.join("&"));
        }
        out
    }

    /// A route's address: [url] up to where a secret or a changing query
    /// value starts, as a `*` pattern; the whole of it when none does.
    pub fn pattern(&mut self, url: &str) -> String {
        let mut cut: Option<(usize, bool)> = None;
        for s in &self.secrets {
            if let Some(i) = url.find(s.as_str()) {
                if cut.is_none_or(|(c, _)| i < c) {
                    cut = Some((i, true));
                }
            }
        }
        if let Some(q) = url.find('?') {
            let mut at = q + 1;
            for pair in url[q + 1..].split('&') {
                if let Some((k, _)) = pair.split_once('=') {
                    let name = decode(k).to_ascii_lowercase();
                    let secret = self.secret_name(&name);
                    if secret || VOLATILE_NAMES.contains(&name.as_str()) {
                        let c = at + k.len() + 1;
                        if cut.is_none_or(|(x, _)| c < x) {
                            cut = Some((c, secret));
                        }
                        break;
                    }
                }
                at += pair.len() + 1;
            }
        }
        match cut {
            Some((c, secret)) => {
                if secret {
                    self.count += 1;
                }
                format!("{}*", &url[..c])
            }
            None => url.to_string(),
        }
    }

    /// A request's `params` as a route checks them: secret and changing
    /// ones left out, so they fit whatever the test sends.
    pub fn params(&mut self, params: &Map<String, Json>) -> Map<String, Json> {
        let mut out = Map::new();
        for (k, v) in params {
            let name = k.to_ascii_lowercase();
            let text = v.as_str().map(String::from).unwrap_or_else(|| v.to_string());
            if self.secret_name(&name) || self.secrets.iter().any(|s| text.contains(s.as_str())) {
                self.count += 1;
            } else if !VOLATILE_NAMES.contains(&name.as_str()) {
                out.insert(k.clone(), v.clone());
            }
        }
        out
    }

    /// Any JSON with secret fields and secret text redacted.
    pub fn json(&mut self, v: &Json) -> Json {
        match v {
            Json::String(s) => Json::String(self.scrub(s)),
            Json::Array(a) => Json::Array(a.iter().map(|x| self.json(x)).collect()),
            Json::Object(o) => {
                let mut out = Map::new();
                for (k, x) in o {
                    let redact = self.secret_name(k) && (x.is_string() || x.is_number()) && x.as_str() != Some("");
                    if redact {
                        self.count += 1;
                        out.insert(k.clone(), Json::String(REDACTED.into()));
                    } else {
                        out.insert(k.clone(), self.json(x));
                    }
                }
                Json::Object(out)
            }
            other => other.clone(),
        }
    }

    /// An http reply: headers, its address and its body (as JSON when it is).
    pub fn http_reply(&mut self, reply: &Json) -> Json {
        let Json::Object(o) = reply else { return self.json(reply) };
        let mut out = Map::new();
        for (k, v) in o {
            let v = match (k.as_str(), v) {
                ("headers", Json::Object(h)) => {
                    let mut headers = Map::new();
                    for (name, value) in h {
                        if self.secret_header(name) {
                            self.count += 1;
                            headers.insert(name.clone(), Json::String(REDACTED.into()));
                        } else {
                            headers.insert(name.clone(), self.json(value));
                        }
                    }
                    Json::Object(headers)
                }
                ("url", Json::String(u)) => Json::String(self.url(u)),
                ("text", Json::String(t)) => Json::String(self.body(t)),
                _ => self.json(v),
            };
            out.insert(k.clone(), v);
        }
        Json::Object(out)
    }

    /// A body: its JSON fields redacted when it is JSON, its secrets either way.
    fn body(&mut self, text: &str) -> String {
        if let Ok(v @ (Json::Object(_) | Json::Array(_))) = serde_json::from_str::<Json>(text) {
            let before = self.count;
            let redacted = self.json(&v);
            if self.count > before {
                return redacted.to_string();
            }
        }
        self.scrub(text)
    }
}

fn decode(s: &str) -> String {
    // Enough for parameter names: `+` and `%XX`.
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < bytes.len() => match std::str::from_utf8(&bytes[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok()) {
                Some(b) => {
                    out.push(b);
                    i += 2;
                }
                None => out.push(b'%'),
            },
            b => out.push(b),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Each call with its reply in mock form: the value, or `{"error": {...}}`.
fn replies(calls: &[HostCall]) -> Vec<(String, Json, Json)> {
    let mut out: Vec<(String, Json, Option<Json>, Option<String>)> = Vec::new();
    let mut early: Vec<(String, Json)> = Vec::new();
    for c in calls {
        match c {
            HostCall::Answered { op, args, reply, .. } => {
                let answer = match reply.get("error") {
                    Some(e) => json!({"error": e}),
                    None => reply.get("value").cloned().unwrap_or(Json::Null),
                };
                out.push((op.clone(), args.clone(), Some(answer), None));
            }
            HostCall::Started { op, args, token, .. } => {
                // An answer known at once is seen just before its call.
                let known = early.iter().position(|(t, _)| t == token).map(|i| early.remove(i).1);
                out.push((op.clone(), args.clone(), known, Some(token.clone())));
            }
            HostCall::Later { token, value } => match out.iter_mut().rev().find(|e| e.3.as_deref() == Some(token) && e.2.is_none()) {
                Some(e) => e.2 = Some(value.clone()),
                None => early.push((token.clone(), value.clone())),
            },
        }
    }
    out.into_iter().map(|(op, args, answer, _)| (op, args, answer.unwrap_or(Json::Null))).collect()
}

/// The mock file for [calls].
pub fn build(calls: &[HostCall], r: &mut Redactor) -> Json {
    let all = replies(calls);
    // What secrets.get answered is a secret wherever else it shows.
    for (op, args, answer) in &all {
        if let ("secrets.get", Some(s)) = (op.as_str(), answer.as_str()) {
            r.add_secret(s);
        }
        if op == "secrets.set" {
            if let Some(s) = args.get("value").and_then(Json::as_str) {
                r.add_secret(s);
            }
        }
    }
    let mut mock = Map::new();
    let mut routes: Vec<Json> = Vec::new();
    let mut seen_routes: HashSet<String> = HashSet::new();
    // Per op: (when, answer) in the order first seen.
    let mut others: Vec<(String, Vec<(Json, Json)>)> = Vec::new();
    for (op, args, answer) in all {
        match op.as_str() {
            "http.request" => {
                let method = args.get("method").and_then(Json::as_str).unwrap_or("GET").to_ascii_uppercase();
                let url = args.get("url").and_then(Json::as_str).unwrap_or("");
                let mut when = json!({"method": method, "url": r.pattern(url)});
                if let Some(Json::Object(p)) = args.get("params") {
                    let kept = r.params(p);
                    if !kept.is_empty() {
                        when["params"] = Json::Object(kept);
                    }
                }
                if !seen_routes.insert(when.to_string()) {
                    continue;
                }
                let reply = match answer.get("error") {
                    Some(_) if answer.as_object().is_some_and(|o| o.len() == 1) => r.json(&answer),
                    _ => r.http_reply(&answer),
                };
                routes.push(json!({"when": when, "reply": reply}));
            }
            // The mock answers a setting write itself, with the value written.
            "settings.set" | "plugin.refresh" => {}
            _ => {
                let answer = if op == "secrets.get" && answer.is_string() {
                    r.count += 1;
                    Json::String(REDACTED.into())
                } else {
                    r.json(&answer)
                };
                let when = when_of(&args, r);
                match others.iter_mut().find(|(o, _)| *o == op) {
                    Some((_, list)) => {
                        if !list.iter().any(|(w, _)| *w == when) {
                            list.push((when, answer));
                        }
                    }
                    None => others.push((op, vec![(when, answer)])),
                }
            }
        }
    }
    if !routes.is_empty() {
        mock.insert("http.request".into(), Json::Array(routes));
    }
    for (op, list) in others {
        let first = list[0].1.clone();
        let same = list.iter().all(|(_, a)| *a == first);
        let value = if same && !crate::mock::is_routes(&first) {
            first
        } else {
            Json::Array(list.into_iter().map(|(when, reply)| json!({"when": when, "reply": reply})).collect())
        };
        mock.insert(op, value);
    }
    Json::Object(mock)
}

/// A route's `when` for a call that is not a request: its args without the
/// callback's token, long values left out, secret ones as `*`.
fn when_of(args: &Json, r: &mut Redactor) -> Json {
    let Json::Object(o) = args else { return json!({}) };
    let mut out = Map::new();
    for (k, v) in o {
        if k == "async" || k == "stream" {
            continue;
        }
        if v.as_str().is_some_and(|s| s.chars().count() > 200) || v.to_string().len() > 400 {
            continue;
        }
        let scrubbed = r.json(v);
        out.insert(k.clone(), if scrubbed != *v { Json::String("*".into()) } else { v.clone() });
    }
    Json::Object(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    fn answered(op: &str, args: Json, value: Json) -> HostCall {
        HostCall::Answered { op: op.into(), args, reply: json!({"value": value}), source: "real", at: Instant::now(), took: Duration::ZERO }
    }

    #[test]
    fn requests_become_routes_with_secrets_out() {
        let reply = json!({"status": 200, "url": "https://api.example.com/v1/w?q=Dhaka&appid=S3CRETKEY", "headers": {"Set-Cookie": "sid=1", "Content-Type": "application/json"},
            "text": "{\"temp\": 21, \"token\": \"abc123\", \"echo\": \"S3CRETKEY\"}"});
        let calls = vec![
            answered("secrets.get", json!({"name": "api_key"}), json!("S3CRETKEY")),
            answered("http.request", json!({"method": "GET", "url": "https://api.example.com/v1/w", "params": {"q": "Dhaka", "appid": "S3CRETKEY"}}), reply.clone()),
            answered("http.request", json!({"method": "GET", "url": "https://api.example.com/v1/w", "params": {"q": "Dhaka", "appid": "S3CRETKEY"}}), reply),
            answered("location.get", json!({"accuracy": "coarse"}), json!({"lat": 1, "lon": 2})),
            HostCall::Started { op: "ai.ask".into(), args: json!({"prompt": "hi", "async": "ai:1"}), token: "ai:1".into(), source: "pretend", at: Instant::now(), took: Duration::ZERO },
            HostCall::Later { token: "ai:1".into(), value: json!({"text": "hello"}) },
        ];
        let mut r = Redactor::new(&[], &[]);
        let mock = build(&calls, &mut r);
        let routes = mock["http.request"].as_array().unwrap();
        assert_eq!(routes.len(), 1, "{mock:#}");
        assert_eq!(routes[0]["when"], json!({"method": "GET", "url": "https://api.example.com/v1/w", "params": {"q": "Dhaka"}}));
        let reply = &routes[0]["reply"];
        assert_eq!(reply["headers"]["Set-Cookie"], REDACTED);
        assert_eq!(reply["headers"]["Content-Type"], "application/json");
        assert_eq!(reply["url"], "https://api.example.com/v1/w?q=Dhaka&appid=<redacted>");
        let body: Json = serde_json::from_str(reply["text"].as_str().unwrap()).unwrap();
        assert_eq!(body, json!({"temp": 21, "token": REDACTED, "echo": REDACTED}));
        assert_eq!(mock["secrets.get"], REDACTED);
        assert_eq!(mock["location.get"], json!({"lat": 1, "lon": 2}));
        assert_eq!(mock["ai.ask"], json!({"text": "hello"}));
        assert!(!mock.to_string().contains("S3CRETKEY"), "{mock:#}");
        // The file replays: the route fits the real call.
        let m = crate::mock::Mock::new(mock.clone());
        let answer = m.reply("http.request", &json!({"method": "GET", "url": "https://api.example.com/v1/w", "params": {"q": "Dhaka", "appid": "other"}}));
        assert_eq!(answer["value"]["status"], 200);
    }

    #[test]
    fn differing_replies_become_routes_and_extra_names_are_redacted() {
        let calls = vec![
            answered("todo.list", json!({"done": true}), json!([1])),
            answered("todo.list", json!({"done": false}), json!([])),
            answered("clipboard.get", json!({}), json!("x")),
        ];
        let mut r = Redactor::new(&[], &[]);
        let mock = build(&calls, &mut r);
        assert_eq!(mock["todo.list"], json!([{"when": {"done": true}, "reply": [1]}, {"when": {"done": false}, "reply": []}]));
        assert_eq!(mock["clipboard.get"], "x");
        let mut r = Redactor::new(&["X-Debug".to_string()], &[]);
        let reply = r.http_reply(&json!({"headers": {"x-debug": "1"}, "text": "plain"}));
        assert_eq!(reply["headers"]["x-debug"], REDACTED);
        assert_eq!(r.pattern("https://h.example/p?t=17000&q=1"), "https://h.example/p?t=*");
        assert_eq!(r.pattern("https://h.example/p?q=1&api_key=k1"), "https://h.example/p?q=1&api_key=*");
        let mut r = Redactor::new(&[], &["bot-token-123".to_string()]);
        assert_eq!(r.pattern("https://api.telegram.org/botbot-token-123/getMe"), "https://api.telegram.org/bot*");
        assert_eq!(decode("api%5Fkey"), "api_key");
    }
}
