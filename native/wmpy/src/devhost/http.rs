//! `http.request` for real, the way PluginKeyboardServices.http sends it:
//! https to a host the manifest's `hosts` (or a typed host setting) covers,
//! redirects followed by hand so each hop is checked again, no cookies, the
//! keyboard's own user agent, 1 MB at most and 15 seconds at most.

use std::time::Duration;

use base64::Engine;
use serde_json::{json, Map, Value as Json};
use ureq::http::Uri;

use super::plugin::{host_allowed, is_local_host, is_valid_host, PluginFacts};

pub const MAX_REQUESTS_PER_MINUTE: usize = 60;
pub const MAX_REPLY: usize = 1024 * 1024;
const MAX_REDIRECTS: usize = 5;
const METHODS: &[&str] = &["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"];
/// Who the request is from, and how it is routed: the plugin may not set these.
const FORBIDDEN_HEADERS: &[&str] = &["host", "cookie", "user-agent", "proxy-authorization", "connection", "content-length"];

/// A refusal or failure, as the error kind the script raises and its message.
pub type Failure = (&'static str, String);

/// Where a plugin may connect: the manifest's hosts and the public hosts
/// typed into host settings, and the local servers typed there.
#[derive(Clone, Debug, Default)]
pub struct Reach {
    pub allowed: Vec<String>,
    pub local: Vec<String>,
    pub network_local: bool,
}

/// One request, ready to send from any thread.
#[derive(Clone, Debug)]
pub struct Prepared {
    pub method: String,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<(Vec<u8>, Option<String>)>,
    pub timeout: Duration,
    pub binary: bool,
    pub user_agent: String,
    pub reach: Reach,
}

/// The request [args] describe, or why the keyboard would refuse it before sending.
pub fn prepare(args: &Json, facts: &PluginFacts, reach: &Reach) -> Result<Prepared, Failure> {
    let s = |k: &str| args.get(k).and_then(Json::as_str).map(String::from);
    let method = s("method").map(|m| m.to_ascii_uppercase()).filter(|m| METHODS.contains(&m.as_str())).unwrap_or_else(|| "GET".into());
    let timeout = args.get("timeout").and_then(Json::as_f64).unwrap_or(15.0).clamp(1.0, 15.0);
    let binary = args.get("binary").is_some_and(|b| b.as_bool() == Some(true) || b.as_str() == Some("true"));
    let url = with_params(&s("url").unwrap_or_default(), args.get("params").and_then(Json::as_object));
    let headers: Vec<(String, String)> = args
        .get("headers")
        .and_then(Json::as_object)
        .map(|h| {
            h.iter()
                .filter(|(k, _)| !FORBIDDEN_HEADERS.contains(&k.to_ascii_lowercase().as_str()))
                .map(|(k, v)| (k.clone(), v.as_str().map(String::from).unwrap_or_else(|| v.to_string())))
                .collect()
        })
        .unwrap_or_default();
    let body = if let Some(text) = s("body") {
        Some((text.into_bytes(), None))
    } else if let Some(form) = args.get("form").and_then(Json::as_object) {
        Some((form_encode(form).into_bytes(), Some("application/x-www-form-urlencoded".to_string())))
    } else if let Some(b64) = s("bodyBytes") {
        let bytes = base64::engine::general_purpose::STANDARD.decode(b64.as_bytes()).map_err(|_| ("ValueError", "bodyBytes is not base64".to_string()))?;
        Some((bytes, Some("application/octet-stream".to_string())))
    } else {
        None
    };
    target(&url, reach)?;
    Ok(Prepared {
        method,
        url,
        headers,
        body,
        timeout: Duration::from_secs_f64(timeout),
        binary,
        user_agent: format!("WMKeyboard-Plugin {}/{} (wmpy; +https://wmkeyboard.pages.dev)", facts.id, facts.version),
        reach: reach.clone(),
    })
}

/// PluginHost.requestTarget: the host [url] may be requested from, or the refusal.
pub fn target(url: &str, reach: &Reach) -> Result<String, Failure> {
    let bad = || ("ValueError", format!("only https addresses with a host name can be requested: {url}"));
    let uri: Uri = url.trim().parse().map_err(|_| bad())?;
    let scheme = uri.scheme_str().map(str::to_ascii_lowercase).ok_or_else(bad)?;
    if scheme != "https" && scheme != "http" {
        return Err(bad());
    }
    let authority = uri.authority().ok_or_else(bad)?;
    if authority.as_str().contains('@') {
        return Err(bad());
    }
    let host = authority.host().to_ascii_lowercase().trim_end_matches('.').to_string();
    if host.is_empty() {
        return Err(bad());
    }
    if is_local_host(&host) {
        if !reach.local.contains(&host) {
            return Err(("PermissionError", format!("{host} is on the local network: a plugin can reach only the server the user typed into its settings there")));
        }
        if !reach.network_local {
            return Err(("PermissionError", format!("{host} is on the local network, which needs the 'network.local' permission in the manifest")));
        }
        return Ok(host);
    }
    if scheme != "https" {
        return Err(("ValueError", format!("only https addresses can be requested (plain http only on the local network): {url}")));
    }
    if !is_valid_host(&host) {
        return Err(bad());
    }
    if !host_allowed(&host, &reach.allowed) {
        return Err(("PermissionError", format!("this plugin is not allowed to connect to {host}")));
    }
    Ok(host)
}

/// Send [req], following redirects, and answer as the keyboard does:
/// `{"status", "reason", "url", "headers", "text", "content"?}`.
pub fn send(req: &Prepared) -> Result<Json, Failure> {
    let mut url = req.url.clone();
    let mut method = req.method.clone();
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .max_redirects(0)
        .max_redirects_will_error(false)
        .http_status_as_error(false)
        .timeout_global(Some(req.timeout))
        .user_agent(req.user_agent.as_str())
        .build()
        .into();
    for _ in 0..=MAX_REDIRECTS {
        target(&url, &req.reach)?;
        let mut builder = ureq::http::Request::builder().method(method.as_str()).uri(url.as_str());
        let own_type = req.headers.iter().any(|(k, _)| k.eq_ignore_ascii_case("content-type"));
        for (k, v) in &req.headers {
            builder = builder.header(k.as_str(), v.as_str());
        }
        let with_body = req.body.as_ref().filter(|_| method != "GET" && method != "DELETE" && method != "HEAD");
        if let Some((_, Some(kind))) = with_body {
            if !own_type {
                builder = builder.header("Content-Type", kind.as_str());
            }
        }
        let sent = match with_body {
            Some((bytes, _)) => builder.body(bytes.clone()).map_err(|e| ("ValueError", e.to_string())).and_then(|r| agent.run(r).map_err(|e| failure(e, req.timeout))),
            None => builder.body(()).map_err(|e| ("ValueError", e.to_string())).and_then(|r| agent.run(r).map_err(|e| failure(e, req.timeout))),
        };
        let mut response = sent?;
        let status = response.status().as_u16();
        let location = response.headers().get("location").and_then(|l| l.to_str().ok()).map(String::from);
        if (300..400).contains(&status) {
            if let Some(location) = location {
                url = resolve(&url, &location);
                if status == 303 || ((status == 301 || status == 302) && method == "POST") {
                    method = "GET".into();
                }
                continue;
            }
        }
        let mut headers = Map::new();
        for name in response.headers().keys() {
            let values: Vec<&str> = response.headers().get_all(name).iter().filter_map(|v| v.to_str().ok()).collect();
            if !values.is_empty() {
                headers.insert(name.as_str().to_string(), Json::String(values.join(", ")));
            }
        }
        let reason = response.status().canonical_reason().unwrap_or("").to_string();
        let bytes = response.body_mut().with_config().limit(MAX_REPLY as u64).read_to_vec().map_err(|e| failure(e, req.timeout))?;
        let mut out = json!({"status": status, "reason": reason, "url": url, "headers": headers});
        if req.binary {
            out["text"] = Json::String(String::new());
            out["content"] = Json::String(base64::engine::general_purpose::STANDARD.encode(&bytes));
        } else {
            out["text"] = Json::String(String::from_utf8_lossy(&bytes).into_owned());
        }
        return Ok(out);
    }
    Err(("ConnectionError", "too many redirects".into()))
}

fn failure(e: ureq::Error, timeout: Duration) -> Failure {
    let slow = || ("TimeoutError", format!("the request took longer than {} seconds", timeout.as_secs().max(1)));
    match e {
        ureq::Error::Timeout(_) => slow(),
        ureq::Error::Io(io) if io.kind() == std::io::ErrorKind::TimedOut => slow(),
        ureq::Error::BodyExceedsLimit(_) => ("ConnectionError", "the reply is larger than 1 MB".into()),
        other => ("ConnectionError", other.to_string()),
    }
}

/// A redirect's Location against the address it came from.
fn resolve(base: &str, location: &str) -> String {
    if location.contains("://") {
        return location.to_string();
    }
    let (scheme, rest) = base.split_once("://").unwrap_or(("https", base));
    if let Some(net) = location.strip_prefix("//") {
        return format!("{scheme}://{net}");
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    if location.starts_with('/') {
        return format!("{scheme}://{authority}{location}");
    }
    let path = rest[authority.len()..].split(['?', '#']).next().unwrap_or("");
    let dir = match path.rfind('/') {
        Some(i) => &path[..=i],
        None => "/",
    };
    format!("{scheme}://{authority}{dir}{location}")
}

/// URLEncoder.encode, as the keyboard encodes params and forms.
pub fn encode(text: &str) -> String {
    let mut out = String::new();
    for b in text.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn text_of(v: &Json) -> String {
    v.as_str().map(String::from).unwrap_or_else(|| if v.is_null() { String::new() } else { v.to_string() })
}

fn form_encode(form: &Map<String, Json>) -> String {
    form.iter().map(|(k, v)| format!("{}={}", encode(k), encode(&text_of(v)))).collect::<Vec<_>>().join("&")
}

fn with_params(url: &str, params: Option<&Map<String, Json>>) -> String {
    match params {
        Some(p) if !p.is_empty() => format!("{url}{}{}", if url.contains('?') { '&' } else { '?' }, form_encode(p)),
        _ => url.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reach() -> Reach {
        Reach { allowed: vec!["api.example.com".into()], local: vec!["192.168.1.9".into()], network_local: true }
    }

    #[test]
    fn requests_are_refused_as_the_keyboard_refuses_them() {
        assert_eq!(target("https://api.example.com/v1", &reach()).unwrap(), "api.example.com");
        assert_eq!(target("https://other.example.com/", &reach()).unwrap_err().0, "PermissionError");
        assert_eq!(target("http://api.example.com/", &reach()).unwrap_err().0, "ValueError");
        assert_eq!(target("https://user@api.example.com/", &reach()).unwrap_err().0, "ValueError");
        assert_eq!(target("https://8.8.8.8/", &reach()).unwrap_err().0, "ValueError");
        assert_eq!(target("http://192.168.1.9:8123/api", &reach()).unwrap(), "192.168.1.9");
        assert_eq!(target("http://192.168.1.10/", &reach()).unwrap_err().0, "PermissionError");
        let no_local = Reach { network_local: false, ..reach() };
        assert_eq!(target("http://192.168.1.9/", &no_local).unwrap_err().0, "PermissionError");
    }

    #[test]
    fn params_and_redirects_are_written_as_the_keyboard_writes_them() {
        let p = serde_json::json!({"q": "milk & eggs", "n": 2});
        assert_eq!(with_params("https://a.example.com/s", p.as_object()), "https://a.example.com/s?q=milk+%26+eggs&n=2");
        assert_eq!(resolve("https://a.example.com/x/y?z", "/top"), "https://a.example.com/top");
        assert_eq!(resolve("https://a.example.com/x/y?z", "next"), "https://a.example.com/x/next");
        assert_eq!(resolve("https://a.example.com/x", "https://b.example.com/"), "https://b.example.com/");
    }
}
