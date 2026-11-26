//! The few things `wmpy publish` asks of the world outside: a GitHub token,
//! one REST call to file the issue, a browser and a clipboard.
//!
//! The browser and clipboard are best effort: when neither works, the link
//! or the text is printed, which is always enough to finish by hand. Tests
//! switch both off with `WMPY_BROWSER=none` and `WMPY_CLIPBOARD=none`, and
//! keep `gh` out with `WMPY_NO_GH=1`.

use std::io::Write;
use std::process::{Command, Stdio};
use std::time::Duration;

use serde_json::{json, Value as Json};

/// A token from GITHUB_TOKEN, GH_TOKEN, or the GitHub CLI's own login.
pub fn token() -> Option<String> {
    for var in ["GITHUB_TOKEN", "GH_TOKEN"] {
        if let Ok(t) = std::env::var(var) {
            if !t.trim().is_empty() {
                return Some(t.trim().to_string());
            }
        }
    }
    if std::env::var_os("WMPY_NO_GH").is_some() {
        return None;
    }
    let out = Command::new("gh").args(["auth", "token"]).stdin(Stdio::null()).stderr(Stdio::null()).output().ok()?;
    let t = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (out.status.success() && !t.is_empty()).then_some(t)
}

/// A filed issue.
pub struct Filed {
    pub url: String,
    pub number: u64,
}

/// `POST /repos/{repo}/issues` with [title], [body] and [labels]. GitHub
/// drops the labels when the token's owner has no push access, which is why
/// the body carries the bot's marker as well.
pub fn create_issue(api: &str, repo: &str, token: &str, title: &str, body: &str, labels: &[&str]) -> Result<Filed, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(60)))
        .user_agent(concat!("wmpy/", env!("CARGO_PKG_VERSION")))
        .build()
        .into();
    let url = format!("{}/repos/{repo}/issues", api.trim_end_matches('/'));
    let payload = json!({"title": title, "body": body, "labels": labels}).to_string();
    let mut response = agent
        .post(&url)
        .header("Authorization", &format!("Bearer {token}"))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Content-Type", "application/json")
        .send(payload)
        .map_err(|e| format!("could not reach {api}: {e}"))?;
    let status = response.status().as_u16();
    let text = response.body_mut().read_to_string().unwrap_or_default();
    let reply: Json = serde_json::from_str(&text).unwrap_or(Json::Null);
    if status != 201 {
        let message = reply.get("message").and_then(|m| m.as_str()).unwrap_or("no message");
        return Err(match status {
            401 => format!("GitHub did not accept the token (HTTP 401: {message})"),
            403 | 404 => format!("the token may not open issues on {repo} (HTTP {status}: {message})"),
            _ => format!("GitHub answered HTTP {status}: {message}"),
        });
    }
    let url = reply.get("html_url").and_then(|u| u.as_str()).ok_or("GitHub filed the issue but sent no link to it")?;
    Ok(Filed { url: url.to_string(), number: reply.get("number").and_then(|n| n.as_u64()).unwrap_or(0) })
}

/// Opens [url] in the default browser. False when none could be started.
pub fn open_browser(url: &str) -> bool {
    match std::env::var("WMPY_BROWSER") {
        Ok(b) if b == "none" => return false,
        Ok(b) if !b.is_empty() => return Command::new(b).arg(url).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success()),
        _ => {}
    }
    let tries: &[(&str, &[&str])] = if cfg!(target_os = "macos") {
        &[("open", &[])]
    } else if cfg!(windows) {
        // `start` would read the & in the link as a command separator.
        &[("rundll32", &["url.dll,FileProtocolHandler"])]
    } else {
        &[("xdg-open", &[]), ("gio", &["open"]), ("wslview", &[])]
    };
    tries.iter().any(|(cmd, args)| Command::new(cmd).args(*args).arg(url).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success()))
}

/// Puts [text] on the clipboard. False when no clipboard tool worked.
pub fn copy_to_clipboard(text: &str) -> bool {
    if std::env::var("WMPY_CLIPBOARD").is_ok_and(|c| c == "none") {
        return false;
    }
    let tries: &[(&str, &[&str])] = if cfg!(target_os = "macos") {
        &[("pbcopy", &[])]
    } else if cfg!(windows) {
        &[("clip", &[])]
    } else {
        &[("wl-copy", &[]), ("xclip", &["-selection", "clipboard"]), ("xsel", &["--clipboard", "--input"])]
    };
    tries.iter().any(|(cmd, args)| {
        let Ok(mut child) = Command::new(cmd).args(*args).stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn() else { return false };
        let wrote = child.stdin.take().is_some_and(|mut s| s.write_all(text.as_bytes()).is_ok());
        child.wait().is_ok_and(|s| s.success()) && wrote
    })
}
