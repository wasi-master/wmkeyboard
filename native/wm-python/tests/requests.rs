//! `requests`, `httpx` and `urllib.request` as plugins import them: each
//! request goes to the bridge as one `http.request`, and the reply, the
//! keyboard's refusals and `then=` answers come back the way those libraries
//! give them.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value as Json};
use wm_python::host::Session;
use wm_python::interp::Clock;

struct FixedClock;

impl Clock for FixedClock {
    fn now(&self) -> f64 {
        1_893_456_000.0
    }
    fn monotonic(&self) -> f64 {
        0.0
    }
    fn utc_offset(&self, _: i64) -> i32 {
        0
    }
}

type Calls = Rc<RefCell<Vec<Json>>>;

fn big<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

/// A session with [permissions] whose bridge answers every `http.request`
/// with [answer]`(args)`, recording the args.
fn session(permissions: &[&str], answer: impl Fn(&Json) -> Json + 'static) -> (Session, Calls) {
    let config = json!({"id": "com.example.req", "version": "1.0.0", "permissions": permissions, "mode": "panel", "apiVersion": 5});
    let mut s = Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap();
    let calls: Calls = Rc::new(RefCell::new(Vec::new()));
    let seen = calls.clone();
    s.set_bridge(Some(Box::new(move |op: &str, args: &str| {
        let args: Json = serde_json::from_str(args).unwrap();
        assert_eq!(op, "http.request");
        seen.borrow_mut().push(args.clone());
        answer(&args).to_string()
    })));
    (s, calls)
}

/// Runs [src] (which prints what it finds) and returns the result and its log.
fn run(s: &mut Session, src: &str) -> (Json, Vec<String>) {
    let full = format!("from wm import ui\n{src}\ndef render():\n    ui.label('x')\n");
    let r: Json = serde_json::from_str(&s.load(&full)).unwrap();
    let log = r["log"].as_array().map(|a| a.iter().map(|l| l.as_str().unwrap_or("").to_string()).collect()).unwrap_or_default();
    (r, log)
}

fn ok_reply(text: &str, headers: Json) -> Json {
    json!({"value": {"status": 200, "reason": "OK", "url": "https://api.example.com/w?q=Dhaka", "headers": headers, "text": text}})
}

fn assert_ok(r: &Json) {
    assert_eq!(r["ok"], true, "{r:#}");
}

#[test]
fn get_reads_like_requests() {
    big(|| {
        let (mut s, calls) = session(&["network"], |_| {
            json!({"value": {"status": 200, "reason": "", "url": "https://api.example.com/w?q=Dhaka",
                "headers": {"Content-Type": "application/json; charset=utf-8", "Link": "<https://api.example.com/w?page=2>; rel=\"next\""},
                "text": "{\"temp\": 21, \"city\": \"Dhaka\"}\nsecond line"}})
        });
        let src = r#"
import requests
r = requests.get("https://api.example.com/w", params={"q": "Dhaka", "skip": None, "tag": ["a", "b"]}, headers={"X-Key": "k"}, timeout=10)
print(r, r.status_code, r.ok, "yes" if r else "no", r.reason)
print(r.headers["content-type"], r.headers["CONTENT-TYPE"] == r.headers.get("Content-Type"), "content-type" in r.headers)
print(r.url, r.encoding, r.apparent_encoding, type(r.content).__name__)
print(r.text.splitlines()[0])
print(r.links["next"]["url"])
print(len(r.iter_lines()), r.iter_lines()[1], len(r.iter_content(5)))
print(r.request.method, r.request.url, r.history, r.elapsed.total_seconds())
print(requests.codes.ok, requests.codes["not_found"], requests.codes.NOT_FOUND, requests.codes.teapot)
print(dict(r.headers)["Content-Type"])
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "<Response [200]> 200 True yes OK");
        assert_eq!(log[1], "application/json; charset=utf-8 True True");
        assert_eq!(log[2], "https://api.example.com/w?q=Dhaka utf-8 utf-8 bytes");
        assert_eq!(log[3], "{\"temp\": 21, \"city\": \"Dhaka\"}");
        assert_eq!(log[4], "https://api.example.com/w?page=2");
        assert_eq!(log[5], "2 b'second line' 9");
        assert_eq!(log[6], "GET https://api.example.com/w?q=Dhaka&tag=a&tag=b [] 0.0");
        assert_eq!(log[7], "200 404 404 418");
        assert_eq!(log[8], "application/json; charset=utf-8");
        let args = calls.borrow()[0].clone();
        assert_eq!(args["method"], "GET");
        assert_eq!(args["url"], "https://api.example.com/w?q=Dhaka&tag=a&tag=b");
        assert_eq!(args["timeout"], 10.0);
        assert_eq!(args["binary"], true);
        assert_eq!(args["headers"]["X-Key"], "k");
        assert_eq!(args["headers"]["Accept"], "*/*");
        assert!(args.get("params").is_none() && args.get("body").is_none());
    });
}

#[test]
fn json_and_text_read_the_bytes_the_keyboard_sent() {
    big(|| {
        use base64::Engine;
        let latin = base64::engine::general_purpose::STANDARD.encode(b"caf\xe9");
        let utf8 = base64::engine::general_purpose::STANDARD.encode("{\"name\": \"caf\u{e9}\"}".as_bytes());
        let png = base64::engine::general_purpose::STANDARD.encode([0x89u8, b'P', b'N', b'G', 0, 0xff]);
        let (mut s, _) = session(&["network"], move |args| {
            let url = args["url"].as_str().unwrap();
            let (kind, content) = if url.ends_with("latin") {
                ("text/plain; charset=ISO-8859-1", latin.clone())
            } else if url.ends_with("plain") {
                ("text/plain", utf8.clone())
            } else if url.ends_with("png") {
                ("image/png", png.clone())
            } else {
                ("application/json", utf8.clone())
            };
            json!({"value": {"status": 200, "reason": "OK", "url": url, "headers": {"Content-Type": kind}, "text": "", "content": content}})
        });
        let src = r#"
import requests
print(requests.get("https://api.example.com/latin").text)
r = requests.get("https://api.example.com/plain")
print(r.encoding, r.text)
print(requests.get("https://api.example.com/json").json()["name"])
p = requests.get("https://api.example.com/png").content
print(len(p), p[0], p[-1])
r = requests.get("https://api.example.com/latin")
r.encoding = "utf-8"
print(r.text == "caf\ufffd")
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log, vec!["café", "None {\"name\": \"café\"}", "café", "6 137 255", "True"]);
    });
}

#[test]
fn bodies_auth_and_timeouts_go_as_requests_sends_them() {
    big(|| {
        let (mut s, calls) = session(&["network"], |_| ok_reply("{}", json!({})));
        let src = r#"
import requests
from requests.auth import HTTPBasicAuth
requests.post("https://api.example.com/a", json={"x": [1, 2]})
requests.post("https://api.example.com/b", data={"a": "1 2", "b": ["x", "y"]}, auth=("user", "pass"))
requests.put("https://api.example.com/c", data=b"\x00\x01", headers={"Content-Type": "application/octet-stream"}, auth=HTTPBasicAuth("u", "p"))
requests.post("https://api.example.com/d", data={"code": "1"}, files={"img": ("a.jpg", b"\xff\xd8", "image/jpeg")})
requests.patch("https://api.example.com/e", data="plain text", timeout=60)
requests.delete("https://api.example.com/f", timeout=(3, 70))
requests.head("https://api.example.com/g")
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        let calls = calls.borrow();
        use base64::Engine;
        let bytes = |v: &Json| base64::engine::general_purpose::STANDARD.decode(v.as_str().unwrap()).unwrap();
        assert_eq!(calls[0]["method"], "POST");
        assert_eq!(calls[0]["headers"]["Content-Type"], "application/json");
        assert_eq!(bytes(&calls[0]["bodyBytes"]), br#"{"x": [1, 2]}"#.to_vec());
        assert_eq!(calls[1]["body"], "a=1+2&b=x&b=y");
        assert_eq!(calls[1]["headers"]["Content-Type"], "application/x-www-form-urlencoded");
        assert_eq!(calls[1]["headers"]["Authorization"], "Basic dXNlcjpwYXNz");
        assert_eq!(bytes(&calls[2]["bodyBytes"]), vec![0, 1]);
        assert_eq!(calls[2]["headers"]["Authorization"], "Basic dTpw");
        let kind = calls[3]["headers"]["Content-Type"].as_str().unwrap().to_string();
        assert!(kind.starts_with("multipart/form-data; boundary=wmform"), "{kind}");
        let body = String::from_utf8_lossy(&bytes(&calls[3]["bodyBytes"])).to_string();
        assert!(body.contains("name=\"code\"\r\n\r\n1\r\n") && body.contains("name=\"img\"; filename=\"a.jpg\"\r\nContent-Type: image/jpeg"), "{body}");
        assert_eq!(calls[4]["body"], "plain text");
        assert_eq!(calls[4]["timeout"], 15.0);
        assert_eq!(calls[5]["method"], "DELETE");
        assert_eq!(calls[5]["timeout"], 15.0);
        assert_eq!(calls[6]["method"], "HEAD");
        // The cap is said once, not on every request.
        assert_eq!(log.iter().filter(|l| l.contains("15 seconds at most")).count(), 1, "{log:?}");
    });
}

#[test]
fn raise_for_status_and_json_errors_read_as_in_requests() {
    big(|| {
        let (mut s, _) = session(&["network"], |args| {
            if args["url"].as_str().unwrap().ends_with("missing") {
                json!({"value": {"status": 404, "reason": "", "url": "https://api.example.com/missing", "headers": {"Content-Type": "text/html"}, "text": "<!DOCTYPE html>"}})
            } else {
                json!({"value": {"status": 503, "reason": "Service Unavailable", "url": "https://api.example.com/down", "headers": {}, "text": "  "}})
            }
        });
        let src = r#"
import requests
r = requests.get("https://api.example.com/missing")
print(r.ok, "yes" if r else "no")
try:
    r.raise_for_status()
except requests.exceptions.HTTPError as e:
    print(e, "|", e.response.status_code, isinstance(e, requests.RequestException), isinstance(e, OSError))
try:
    requests.get("https://api.example.com/down").raise_for_status()
except requests.RequestException as e:
    print(type(e).__name__, e)
try:
    r.json()
except requests.exceptions.JSONDecodeError as e:
    print(e, "|", e.pos, e.lineno, e.colno)
try:
    requests.get("https://api.example.com/down").json()
except ValueError as e:
    print(type(e).__name__, e)
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "False no");
        assert_eq!(log[1], "404 Client Error: Not Found for url: https://api.example.com/missing | 404 True True");
        assert_eq!(log[2], "HTTPError 503 Server Error: Service Unavailable for url: https://api.example.com/down");
        assert_eq!(log[3], "Expecting value: line 1 column 1 (char 0) | 0 1 1");
        assert_eq!(log[4], "JSONDecodeError Expecting value: line 1 column 3 (char 2)");
    });
}

#[test]
fn the_keyboard_refusals_say_what_to_change() {
    big(|| {
        // No network permission: refused before the bridge.
        let (mut s, calls) = session(&[], |_| ok_reply("", json!({})));
        let (r, log) = run(&mut s, "import requests\ntry:\n    requests.get('https://api.example.com/x')\nexcept PermissionError as e:\n    print(e)\n");
        assert_ok(&r);
        assert!(log[0].starts_with("requests needs the \"network\" permission") && log[0].contains("wmpy check --fix"), "{log:?}");
        assert!(calls.borrow().is_empty());

        let (mut s, _) = session(&["network"], |args| {
            let url = args["url"].as_str().unwrap_or("");
            let (kind, message) = if url.contains("other.example") {
                ("PermissionError", "this plugin is not allowed to connect to other.example")
            } else if url.contains("saver") {
                ("ConnectionError", "Data saver is on, so plugins cannot use the internet")
            } else if url.contains("big") {
                ("ConnectionError", "the reply is larger than 1 MB")
            } else if url.contains("slow") {
                ("TimeoutError", "the request took longer than 15 seconds")
            } else if url.contains("loop") {
                ("ConnectionError", "too many redirects")
            } else if url.starts_with("http://") {
                ("ValueError", "only https addresses can be requested (plain http only on the local network): http://api.example.com/")
            } else {
                ("ConnectionError", "Unable to resolve host \"offline.example\": No address associated with hostname")
            };
            json!({"error": {"kind": kind, "message": message}})
        });
        let src = r#"
import requests
from requests.exceptions import ConnectionError, InvalidSchema, MissingSchema, Timeout, TooManyRedirects
def show(url, method="GET"):
    try:
        requests.request(method, url)
        print("sent")
    except PermissionError as e:
        print("PermissionError", e)
    except TooManyRedirects as e:
        print("TooManyRedirects", e)
    except Timeout as e:
        print(type(e).__name__, e)
    except ConnectionError as e:
        print("ConnectionError", e)
    except InvalidSchema as e:
        print("InvalidSchema", e)
    except MissingSchema as e:
        print("MissingSchema", e)
    except ValueError as e:
        print("ValueError", e)
show("https://other.example/x")
show("https://saver.example/x")
show("https://big.example/x")
show("https://slow.example/x")
show("https://loop.example/x")
show("http://api.example.com/")
show("https://offline.example/x")
show("api.example.com/x")
show("ftp://api.example.com/x")
show("https://api.example.com/x", "OPTIONS")
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert!(log[0].starts_with("PermissionError other.example is not in plugin.json's \"hosts\"") && log[0].contains("Add \"other.example\" to \"hosts\""), "{}", log[0]);
        assert_eq!(log[1], "ConnectionError Data saver is on, so plugins cannot use the internet");
        assert_eq!(log[2], "ConnectionError the reply from big.example is larger than 1 MB, the most a plugin can receive");
        assert!(log[3].starts_with("ReadTimeout the request took longer than 15 seconds (https://slow.example/x)"), "{}", log[3]);
        assert!(log[4].starts_with("TooManyRedirects Exceeded 5 redirects"), "{}", log[4]);
        assert!(log[5].starts_with("InvalidSchema plugins can request https addresses only, not plain http"), "{}", log[5]);
        assert!(log[6].starts_with("ConnectionError could not reach offline.example:") && log[6].ends_with("Check that the phone is online."), "{}", log[6]);
        assert_eq!(log[7], "MissingSchema Invalid URL 'api.example.com/x': No scheme supplied. Perhaps you meant https://api.example.com/x?");
        assert!(log[8].starts_with("InvalidSchema No connection adapters were found for 'ftp://api.example.com/x'"), "{}", log[8]);
        assert_eq!(log[9], "ValueError the keyboard sends GET, POST, PUT, PATCH, DELETE and HEAD requests, not OPTIONS");
    });
}

#[test]
fn a_session_keeps_headers_params_auth_and_cookies() {
    big(|| {
        let (mut s, calls) = session(&["network"], |args| {
            json!({"value": {"status": 200, "reason": "OK", "url": args["url"], "text": "{}",
                "headers": {"Set-Cookie": "sid=abc123; Path=/; Expires=Wed, 21 Oct 2099 07:28:00 GMT; HttpOnly, theme=dark; Max-Age=3600, gone=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT"}}})
        });
        let src = r#"
import requests
with requests.Session() as s:
    s.headers.update({"Authorization": "Bearer t0k"})
    s.params = {"lang": "bn"}
    r = s.get("https://api.example.com/me", params={"page": 2}, headers={"X-One": "1"})
    print(r.cookies["sid"], r.cookies.get("theme"), "gone" in r.cookies)
    print(sorted(s.cookies.get_dict().items()))
    s.post("https://api.example.com/again", json={"a": 1}, cookies={"x": "y"})
s2 = requests.session()
s2.auth = ("me", "pw")
s2.get("https://api.example.com/basic")
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "abc123 dark False");
        assert_eq!(log[1], "[('sid', 'abc123'), ('theme', 'dark')]");
        assert!(log.iter().any(|l| l.contains("sends no cookies")), "{log:?}");
        let calls = calls.borrow();
        assert_eq!(calls[0]["url"], "https://api.example.com/me?lang=bn&page=2");
        assert_eq!(calls[0]["headers"]["Authorization"], "Bearer t0k");
        assert_eq!(calls[0]["headers"]["X-One"], "1");
        assert_eq!(calls[1]["headers"]["Authorization"], "Bearer t0k");
        assert!(calls[1]["headers"].get("X-One").is_none());
        assert!(calls[1]["headers"].as_object().unwrap().keys().all(|k| k.to_lowercase() != "cookie"), "{}", calls[1]);
        assert_eq!(calls[2]["headers"]["Authorization"], "Basic bWU6cHc=");
    });
}

#[test]
fn then_answers_later_with_a_response_or_an_error() {
    big(|| {
        let (mut s, calls) = session(&["network"], |_| json!({"value": null}));
        let src = r#"
import requests
from wm import plugin
st = plugin.state(seen="")
def got(r):
    st.seen = st.seen + f"got {r.status_code} {r.json()['n']} {r.headers['x-a']};"
def failed(e):
    st.seen = st.seen + f"failed {type(e).__name__} {e};"
requests.get("https://api.example.com/later", then=got, on_error=failed)
requests.get("https://api.example.com/fails", then=got, on_error=failed)
print("asked")
"#;
        let full = format!("from wm import ui\n{src}\ndef render():\n    ui.label(st.seen)\n");
        let r: Json = serde_json::from_str(&s.load(&full)).unwrap();
        assert_ok(&r);
        assert_eq!(r["host"]["pending"], 2, "{r:#}");
        let tokens: Vec<String> = calls.borrow().iter().map(|a| a["async"].as_str().unwrap().to_string()).collect();
        let reply = json!({"status": 200, "reason": "OK", "url": "https://api.example.com/later", "headers": {"X-A": "1"}, "text": "{\"n\": 7}"});
        let r: Json = serde_json::from_str(&s.event(&json!({"type": "result", "token": tokens[0], "value": reply}).to_string())).unwrap();
        assert_ok(&r);
        let failure = json!({"error": {"kind": "ConnectionError", "message": "Data saver is on, so plugins cannot use the internet"}});
        let r: Json = serde_json::from_str(&s.event(&json!({"type": "result", "token": tokens[1], "value": failure}).to_string())).unwrap();
        assert_ok(&r);
        let label = r["ui"]["root"][0]["text"].as_str().unwrap().to_string();
        assert_eq!(label, "got 200 7 1;failed ConnectionError Data saver is on, so plugins cannot use the internet;");
        assert_eq!(r["host"]["pending"], 0);
    });
}

#[test]
fn httpx_reads_like_httpx() {
    big(|| {
        let (mut s, calls) = session(&["network"], |args| {
            let url = args["url"].as_str().unwrap();
            let status = if url.contains("missing") { 404 } else { 200 };
            json!({"value": {"status": status, "reason": "", "url": url, "headers": {"content-type": "application/json"}, "text": "{\"ok\": true}"}})
        });
        let src = r#"
import httpx
with httpx.Client(base_url="https://api.example.com/v1", headers={"X-Key": "k"}, params={"lang": "bn"}, timeout=8, auth=("a", "b")) as client:
    r = client.get("/items", params={"q": "milk"})
    print(r, r.status_code, r.reason_phrase, r.is_success, r.is_error, r.json()["ok"], r.headers["Content-Type"])
    print(str(r.url), r.url.host, r.url.path, r.raise_for_status() is r)
    m = client.get("missing")
    try:
        m.raise_for_status()
    except httpx.HTTPStatusError as e:
        print(e.response.status_code, str(e).splitlines()[0])
    except httpx.HTTPError as e:
        print("wrong", e)
r = httpx.post("https://api.example.com/v1/new", json={"a": 1})
print(r.status_code, httpx.codes.OK, httpx.codes.NOT_FOUND)
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "<Response [200 OK]> 200 OK True False True application/json");
        assert_eq!(log[1], "https://api.example.com/v1/items?lang=bn&q=milk api.example.com /v1/items True");
        assert_eq!(log[2], "404 Client error '404 Not Found' for url 'https://api.example.com/v1/missing?lang=bn'");
        assert_eq!(log[3], "200 200 404");
        let calls = calls.borrow();
        assert_eq!(calls[0]["url"], "https://api.example.com/v1/items?lang=bn&q=milk");
        assert_eq!(calls[0]["headers"]["X-Key"], "k");
        assert_eq!(calls[0]["headers"]["Authorization"], "Basic YTpi");
        assert_eq!(calls[0]["timeout"], 8.0);
        assert_eq!(calls[2]["method"], "POST");
        assert_eq!(calls[2]["headers"]["Content-Type"], "application/json");
        assert_eq!(calls[2]["timeout"], 5.0);
    });
}

#[test]
fn httpx_errors_are_httpx_errors() {
    big(|| {
        let (mut s, _) = session(&["network"], |args| {
            let url = args["url"].as_str().unwrap();
            let (kind, message) = if url.contains("slow") { ("TimeoutError", "the request took longer than 5 seconds") } else { ("ConnectionError", "Data saver is on, so plugins cannot use the internet") };
            json!({"error": {"kind": kind, "message": message}})
        });
        let src = r#"
import httpx
for url in ["https://slow.example/", "https://saver.example/"]:
    try:
        httpx.get(url)
    except httpx.TimeoutException as e:
        print("timeout", type(e).__name__, isinstance(e, httpx.RequestError))
    except httpx.ConnectError as e:
        print("connect", e)
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "timeout ReadTimeout True");
        assert_eq!(log[1], "connect Data saver is on, so plugins cannot use the internet");
    });
}

#[test]
fn urlopen_reads_like_urllib() {
    big(|| {
        let (mut s, calls) = session(&["network"], |args| {
            let url = args["url"].as_str().unwrap();
            if url.contains("missing") {
                json!({"value": {"status": 404, "reason": "Not Found", "url": url, "headers": {"Content-Type": "text/plain"}, "text": "nope"}})
            } else if url.contains("offline") {
                json!({"error": {"kind": "ConnectionError", "message": "Unable to resolve host"}})
            } else {
                json!({"value": {"status": 200, "reason": "OK", "url": url, "headers": {"Content-Type": "application/json; charset=UTF-8"}, "text": "{\"n\": 1}"}})
            }
        });
        let src = r#"
import json
import urllib.request
import urllib.parse
from urllib.error import HTTPError, URLError
with urllib.request.urlopen("https://api.example.com/a?x=1", timeout=5) as reply:
    print(reply.status, reply.getcode(), reply.reason, reply.geturl(), reply.headers["content-type"], reply.headers.get_content_charset())
    print(json.loads(reply.read().decode())["n"])
req = urllib.request.Request("https://api.example.com/b", data=urllib.parse.urlencode({"a": "1"}).encode(), headers={"x-token": "t"})
print(req.get_method(), req.get_header("X-token"))
print(urllib.request.urlopen(req).read())
try:
    urllib.request.urlopen("https://api.example.com/missing")
except HTTPError as e:
    print(e, e.code, e.reason, e.read(), isinstance(e, URLError))
try:
    urllib.request.urlopen("https://offline.example/")
except URLError as e:
    print(type(e).__name__, str(e).startswith("<urlopen error could not reach offline.example"))
"#;
        let (r, log) = run(&mut s, src);
        assert_ok(&r);
        assert_eq!(log[0], "200 200 OK https://api.example.com/a?x=1 application/json; charset=UTF-8 utf-8");
        assert_eq!(log[1], "1");
        assert_eq!(log[2], "POST t");
        assert_eq!(log[3], "b'{\"n\": 1}'");
        assert_eq!(log[4], "HTTP Error 404: Not Found 404 Not Found b'nope' True");
        assert_eq!(log[5], "URLError True");
        let calls = calls.borrow();
        assert_eq!(calls[0]["timeout"], 5.0);
        assert_eq!(calls[1]["method"], "POST");
        assert_eq!(calls[1]["body"], json!(null));
        assert_eq!(calls[1]["headers"]["X-token"], "t");
        assert_eq!(calls[1]["headers"]["Content-Type"], "application/x-www-form-urlencoded");
    });
}

#[test]
fn wm_http_is_unchanged() {
    big(|| {
        let (mut s, calls) = session(&["network"], |_| ok_reply("{\"a\": 1}", json!({"Content-Type": "application/json"})));
        let (r, log) = run(&mut s, "from wm import http\nr = http.get('https://api.example.com/w', params={'q': 'x'})\nprint(type(r.headers).__name__, r.headers['content-type'], r.json()['a'])\n");
        assert_ok(&r);
        assert_eq!(log[0], "dict application/json 1");
        let calls = calls.borrow();
        assert_eq!(calls[0]["params"], json!({"q": "x"}));
        assert_eq!(calls[0]["binary"], false);
    });
}
