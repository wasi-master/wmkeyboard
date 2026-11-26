//! requests, httpx and urllib.request, which the host gives plugins beside
//! the `wm` package: modelled from their own Python, offered, checked
//! against plugin.json, and written out as stubs.

mod common;

use common::*;
use wm_python_tooling::api::{self, ApiKind};
use wm_python_tooling::api_json;
use wm_python_tooling::complete::complete;
use wm_python_tooling::diagnostics::{diagnose, Fix};
use wm_python_tooling::document::{Document, Host};
use wm_python_tooling::signature::{hover_at, signature_at};

fn manifest(permissions: &[&str], hosts: &[&str]) -> Host {
    api_json::host_of_manifest(&serde_json::json!({"apiVersion": 5, "permissions": permissions, "hosts": hosts}))
}

fn network() -> Host {
    manifest(&["network"], &["api.example.com"])
}

fn labels(marked: &str) -> Vec<String> {
    let (source, at) = caret(marked);
    complete(&Document::new(&source), at, true, &network()).map(|c| c.items.into_iter().map(|i| i.label).collect()).unwrap_or_default()
}

fn has(marked: &str, wanted: &[&str]) {
    let found = labels(marked);
    for w in wanted {
        assert!(found.iter().any(|f| f == w), "{marked:?}: no {w} in {found:?}");
    }
}

fn found(source: &str, host: &Host, code: &str) -> Vec<(Vec<String>, String)> {
    diagnostics(source, host).into_iter().filter(|d| d.code.name() == code).map(|d| (d.args.clone(), utf16_slice(source, d.span.start, d.span.end))).collect()
}

#[test]
fn the_model_reads_the_libraries_from_their_source() {
    let m = api::model();
    let kind = |p: &str| m.find(p).map(|e| e.kind).unwrap_or_else(|| panic!("no {p}"));
    assert_eq!(kind("requests"), ApiKind::Module);
    assert_eq!(kind("requests.get"), ApiKind::Function);
    assert_eq!(m.find("requests.get").unwrap().signature, Some("get(url, params=None, **kwargs)"));
    assert_eq!(m.find("requests.get").unwrap().permission, Some("network"));
    assert_eq!(kind("requests.Session"), ApiKind::Type);
    assert_eq!(m.find("requests.Session.post").unwrap().signature, Some("post(url, data=None, json=None, **kwargs)"));
    assert_eq!(kind("requests.Response.status_code"), ApiKind::Constant);
    assert_eq!(kind("requests.Response.json"), ApiKind::Function);
    assert_eq!(kind("requests.Response.ok"), ApiKind::Constant);
    assert_eq!(kind("requests.exceptions.HTTPError"), ApiKind::Exception);
    assert_eq!(kind("requests.exceptions.ConnectTimeout"), ApiKind::Exception);
    assert_eq!(kind("requests.HTTPError"), ApiKind::Exception);
    assert_eq!(kind("requests.codes"), ApiKind::Constant);
    assert_eq!(m.find("requests.auth.HTTPBasicAuth").unwrap().signature, Some("HTTPBasicAuth(username, password)"));
    assert_eq!(kind("httpx.Client"), ApiKind::Type);
    assert_eq!(kind("httpx.Client.get"), ApiKind::Function);
    assert_eq!(kind("httpx.HTTPStatusError"), ApiKind::Exception);
    assert_eq!(kind("httpx.Timeout"), ApiKind::Type);
    assert_eq!(kind("urllib.request.urlopen"), ApiKind::Function);
    assert_eq!(kind("urllib.error.HTTPError"), ApiKind::Exception);
    assert!(m.find("requests.exceptions.exceptions").is_none() && m.find("requests._net").is_none());
    assert!(m.find("requests.exceptions").unwrap().permission.is_none());
    assert!(m.find("requests.Response.status_code").unwrap().doc.contains("HTTP status"));
}

#[test]
fn the_libraries_are_built_in() {
    let host = network();
    for source in [
        "import requests\n",
        "import httpx\n",
        "import urllib.request\n",
        "from urllib.request import urlopen, Request\n",
        "from urllib.error import HTTPError\n",
        "import requests.exceptions\n",
        "from requests.exceptions import HTTPError, RequestException\n",
        "from requests import Session, codes\n",
        "from requests.auth import HTTPBasicAuth\n",
    ] {
        assert!(found(source, &host, "import_not_available").is_empty(), "{source}");
        assert!(found(source, &host, "unknown_member").is_empty() && found(source, &host, "member_typo").is_empty(), "{source}: {:?}", serious(source, &host));
    }
    assert_eq!(found("from requests import gett\n", &host, "member_typo")[0].0, vec!["requests.gett".to_string(), "requests.get".to_string()]);
    assert_eq!(found("import requests\nrequests.pst('https://api.example.com/')\n", &host, "member_typo")[0].0[1], "requests.post");
    assert!(!found("import httpx\nhttpx.AsyncClient()\n", &host, "unknown_member").is_empty());
    assert!(!found("import requests.packages\n", &host, "import_not_available").is_empty());
    // urllib.parse stays the standard library's, unchecked name by name.
    let both = "from wm import ui\nimport urllib.request\nimport urllib.parse\nx = urllib.parse.quote('a b')\ny = urllib.request.urlopen\n\n\ndef render():\n    ui.label(x)\n";
    assert!(serious(both, &host).is_empty(), "{:?}", serious(both, &host));
    // Used without an import: the fix adds it.
    let doc = Document::new("r = requests.get('https://api.example.com/')\n");
    let d = diagnose(&doc, &host).diagnostics.into_iter().find(|d| d.code.name() == "module_not_imported").expect("module_not_imported");
    assert!(matches!(d.fix, Some(Fix::Insert(0, ref t)) if t == "import requests\n"), "{:?}", d.fix);
}

#[test]
fn using_them_needs_the_network_permission() {
    let none = manifest(&[], &[]);
    for (source, name) in [
        ("import requests\n", "requests"),
        ("import httpx\n", "httpx"),
        ("import urllib.request\n", "urllib.request"),
        ("from requests import get\n", "requests"),
        ("from urllib.request import urlopen\n", "urllib.request"),
    ] {
        let doc = Document::new(source);
        let d = diagnose(&doc, &none).diagnostics.into_iter().find(|d| d.code.name() == "permission_missing").unwrap_or_else(|| panic!("{source}"));
        assert_eq!(d.args, vec![name.to_string(), "network".to_string()]);
        assert_eq!(d.fix, Some(Fix::Permission("network".into())));
    }
    for source in ["from requests.exceptions import HTTPError\n", "from urllib.error import URLError\n", "import urllib.parse\n"] {
        assert!(found(source, &none, "permission_missing").is_empty(), "{source}");
    }
    assert!(found("import requests\n", &network(), "permission_missing").is_empty());
}

#[test]
fn addresses_on_unlisted_hosts_are_marked() {
    let host = network();
    let marks = |source: &str| found(source, &host, "host_missing").into_iter().map(|(a, text)| (a[0].clone(), text)).collect::<Vec<_>>();
    assert_eq!(marks("import requests\nr = requests.get(\"https://other.org/x\")\n"), vec![("other.org".to_string(), "\"https://other.org/x\"".to_string())]);
    assert!(marks("import requests\nr = requests.get(\"https://api.example.com/x\")\n").is_empty());
    assert_eq!(marks("import requests\nrequests.request(\"POST\", url=\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("import requests\nrequests.request(\"POST\", \"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("from requests import post\npost(\"https://other.org/\", json={})\n")[0].0, "other.org");
    assert_eq!(marks("import requests\nURL = \"https://other.org/api\"\nr = requests.get(URL)\n")[0].0, "other.org");
    assert_eq!(marks("import requests\ndef f(owner):\n    return requests.get(f\"https://other.org/repos/{owner}\")\n")[0].0, "other.org");
    // A session's and a client's own methods.
    assert_eq!(marks("import requests\ns = requests.Session()\nr = s.get(\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("import requests\nwith requests.Session() as s:\n    s.post(\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("import requests\nrequests.Session().get(\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("import httpx\nwith httpx.Client(base_url=\"https://other.org\") as c:\n    c.get(\"/x\")\n")[0].0, "other.org");
    assert_eq!(marks("import httpx\nr = httpx.get(\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("import urllib.request\nr = urllib.request.urlopen(\"https://other.org/\")\n")[0].0, "other.org");
    assert_eq!(marks("from urllib.request import Request\nr = Request(\"https://other.org/\")\n")[0].0, "other.org");
    // Not a request: a dict's get, a relative address.
    assert!(marks("import requests\nd = {}\nx = d.get(\"https://other.org/\")\n").is_empty());
    assert!(marks("import httpx\nc = httpx.Client(base_url=\"https://api.example.com\")\nc.get(\"/x\")\n").is_empty());
    let doc = Document::new("import requests\nr = requests.get(\"https://other.org/x\")\n");
    let d = diagnose(&doc, &host).diagnostics.into_iter().find(|d| d.code.name() == "host_missing").unwrap();
    assert_eq!(d.fix, Some(Fix::Host("other.org".into())));
}

#[test]
fn completion_hover_and_signatures_know_them() {
    has("import |", &["requests", "httpx", "urllib.request"]);
    has("import requests\nrequests.|", &["get", "post", "Session", "codes", "exceptions", "HTTPError"]);
    has("import requests\nr = requests.get('https://api.example.com/')\nr.|", &["status_code", "json", "raise_for_status", "headers", "text", "ok"]);
    has("import requests\ns = requests.Session()\ns.|", &["get", "post", "headers", "auth", "cookies"]);
    has("import requests\nwith requests.Session() as s:\n    r = s.get('https://api.example.com/')\n    r.|", &["json", "status_code"]);
    has("import httpx\nwith httpx.Client() as c:\n    c.|", &["get", "post", "base_url"]);
    has("import httpx\nr = httpx.get('https://api.example.com/')\nr.|", &["is_success", "raise_for_status", "json"]);
    has("from requests.exceptions import |", &["HTTPError", "ConnectionError", "JSONDecodeError"]);
    has("import urllib.request\nurllib.request.|", &["urlopen", "Request"]);
    has("from urllib.request import urlopen\nr = urlopen('https://api.example.com/')\nr.|", &["read", "status", "getcode"]);

    let (source, at) = caret("import requests\nrequests.get('https://api.example.com/', |");
    let s = signature_at(&Document::new(&source), at, &network()).expect("a signature");
    assert!(s.label.ends_with("get(url, params=None, **kwargs)"), "{}", s.label);
    let source = "import requests\nr = requests.get('https://api.example.com/')\n";
    let at = source.find("get(").unwrap() as u32 + 1;
    let h = hover_at(&Document::new(source), at, &network()).expect("a hover");
    assert!(h.doc.contains("GET request"), "{}", h.doc);
}

#[test]
fn the_stubs_cover_them() {
    let files = wm_python_tooling::stubs::wm_package();
    let get = |p: &str| files.iter().find(|(f, _)| f == p).map(|(_, t)| t.clone()).unwrap_or_else(|| panic!("no {p}"));
    let init = get("requests/__init__.pyi");
    assert!(init.contains("def get(url, params=None, **kwargs) -> Response:"), "{init}");
    assert!(init.contains("class Session:") && init.contains("    def __enter__(self) -> Session: ..."), "{init}");
    assert!(init.contains("class HTTPError(RequestException):"), "{init}");
    assert!(init.find("class RequestException(OSError):").unwrap() < init.find("class HTTPError(RequestException):").unwrap());
    let exceptions = get("requests/exceptions.pyi");
    assert!(exceptions.contains("class JSONDecodeError(ValueError):"), "{exceptions}");
    let httpx = get("httpx.pyi");
    assert!(httpx.contains("class Client:") && httpx.contains("def get(url, params=None"), "{httpx}");
    assert!(!files.iter().any(|(f, _)| f.starts_with("urllib")));
    for (path, text) in &files {
        let doc = Document::new(text);
        assert!(doc.module.errors.is_empty(), "{path} does not parse: {:?}\n{text}", doc.module.errors.iter().map(|e| doc.lines.line_of(e.span.start) + 1).collect::<Vec<_>>());
    }
}
