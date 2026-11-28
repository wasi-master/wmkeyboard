//! Keeping wmpy up to date.
//!
//! * A notice: once a day at most, a command run in a terminal asks GitHub
//!   which wmpy is newest (in the background, while the command runs, giving
//!   up after a few seconds) and keeps the answer in the cache folder. When
//!   the kept answer is newer than this wmpy, the command ends with one line
//!   saying so. Never in CI (`CI` set), with `--quiet`, for output scripts
//!   read, or with `WMPY_NO_UPDATE_CHECK=1`. The request carries nothing but
//!   `wmpy/<version>` as its User-Agent.
//! * `wmpy upgrade`: the newest release, by the way this wmpy was installed:
//!   `brew upgrade`, `scoop update`, `npm install -g`, `pipx upgrade`,
//!   `uv tool upgrade` or `cargo install`, and for the install scripts (or a
//!   wmpy copied by hand) the release archive for this computer, checked
//!   against its SHA-256 and put in place of this one.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Receiver};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value as Json};

use crate::output;

const API: &str = "https://api.github.com/repos/wasi-master/wmpy/releases/latest";
const DAY: u64 = 24 * 60 * 60;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// Only say whether a newer wmpy is out, and how to get it
    #[arg(long)]
    pub check: bool,
    /// Install this version instead of the newest, such as 0.2.0
    #[arg(long, value_name = "VERSION")]
    pub version: Option<String>,
    /// Print what was found as JSON (with --check)
    #[arg(long, requires = "check")]
    pub json: bool,
}

/// `0.10.2` as numbers, for comparing; None for anything else.
fn parse(v: &str) -> Option<Vec<u64>> {
    let v = v.trim().trim_start_matches('v');
    let core = v.split(['-', '+']).next()?;
    core.split('.').map(|p| p.parse().ok()).collect()
}

/// Whether [latest] is a newer release than [current].
pub fn newer(latest: &str, current: &str) -> bool {
    match (parse(latest), parse(current)) {
        (Some(a), Some(b)) => a > b,
        _ => false,
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn cache_file() -> Option<PathBuf> {
    crate::pip::index_cache_dir().map(|d| d.join("latest-release.json"))
}

/// The newest release's version, as GitHub says. `WMPY_LATEST_VERSION`
/// stands in for GitHub in the tests.
fn latest(timeout: Duration) -> Result<String, String> {
    if let Ok(v) = std::env::var("WMPY_LATEST_VERSION") {
        if !v.is_empty() {
            return Ok(v.trim_start_matches('v').to_string());
        }
    }
    let agent = ureq::Agent::new_with_config(
        ureq::Agent::config_builder()
            .timeout_global(Some(timeout))
            .https_only(true)
            .max_redirects(3)
            .http_status_as_error(false)
            .user_agent(concat!("wmpy/", env!("CARGO_PKG_VERSION")))
            .build(),
    );
    let mut res = agent.get(API).header("Accept", "application/vnd.github+json").call().map_err(|e| format!("could not reach GitHub: {e}"))?;
    let status = res.status().as_u16();
    let body = res.body_mut().with_config().limit(1 << 20).read_to_vec().map_err(|e| format!("could not read GitHub's answer: {e}"))?;
    if status == 404 {
        return Err("wmpy has no release yet".into());
    }
    if status != 200 {
        return Err(format!("GitHub answered HTTP {status}"));
    }
    let v: Json = serde_json::from_slice(&body).map_err(|e| format!("GitHub's answer is not JSON: {e}"))?;
    let tag = v.get("tag_name").and_then(Json::as_str).ok_or("GitHub's answer names no release")?;
    Ok(tag.trim_start_matches('v').to_string())
}

/// The line saying a newer wmpy is out, from what the cache holds.
pub struct Notice {
    shown: Option<String>,
    done: Option<Receiver<()>>,
}

impl Notice {
    /// Reads the cache, and asks GitHub in the background when the cache is a
    /// day old. Does nothing when [wanted] is false or the run is not one a
    /// person watches.
    pub fn start(wanted: bool) -> Notice {
        let off = Notice { shown: None, done: None };
        let opted_out = std::env::var("WMPY_NO_UPDATE_CHECK").is_ok_and(|v| !v.is_empty() && v != "0");
        let ci = std::env::var_os("CI").is_some_and(|v| !v.is_empty());
        if !wanted || opted_out || ci || output::quiet() || !std::io::IsTerminal::is_terminal(&std::io::stderr()) {
            return off;
        }
        let Some(file) = cache_file() else { return off };
        let kept: Json = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Json::Null);
        let checked = kept.get("checked").and_then(Json::as_u64).unwrap_or(0);
        let current = env!("CARGO_PKG_VERSION");
        let shown = kept.get("latest").and_then(Json::as_str).filter(|l| newer(l, current)).map(|l| {
            format!("wmpy {l} is out (this is {current}): `wmpy upgrade` installs it")
        });
        let mut done = None;
        if now().saturating_sub(checked) >= DAY {
            let (tx, rx) = channel();
            std::thread::spawn(move || {
                let found = latest(Duration::from_secs(4));
                // Kept even when it failed, so a missing network costs one try a day.
                let entry = match &found {
                    Ok(v) => json!({"checked": now(), "latest": v}),
                    Err(_) => json!({"checked": now(), "latest": kept.get("latest").cloned().unwrap_or(Json::Null)}),
                };
                if let Some(parent) = file.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let _ = std::fs::write(&file, entry.to_string());
                let _ = tx.send(());
            });
            done = Some(rx);
        }
        Notice { shown, done }
    }

    /// Prints the line, if there is one, after the command's own output.
    pub fn finish(self) {
        if let Some(line) = &self.shown {
            let color = output::stderr();
            eprintln!("{}", output::paint(color, "2", line));
        }
        // Give a slow answer a moment to land in the cache; never hold the exit up longer.
        if let Some(rx) = self.done {
            let _ = rx.recv_timeout(Duration::from_millis(250));
        }
    }
}

/// How this wmpy was installed, from where it lives.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Channel {
    Homebrew,
    Scoop,
    Npm,
    Pipx,
    Uv,
    Pip,
    Cargo,
    /// install.sh, install.ps1, or a copy put somewhere by hand: wmpy replaces itself.
    Standalone,
}

pub fn channel_of(exe: &Path) -> Channel {
    let p = exe.to_string_lossy().replace('\\', "/").to_lowercase();
    if p.contains("/cellar/") || p.contains("/homebrew/") || p.contains("/linuxbrew/") {
        Channel::Homebrew
    } else if p.contains("/scoop/") {
        Channel::Scoop
    } else if p.contains("/node_modules/") {
        Channel::Npm
    } else if p.contains("/pipx/") {
        Channel::Pipx
    } else if p.contains("/uv/tools/") {
        Channel::Uv
    } else if p.contains("/site-packages/") || p.contains("/dist-packages/") {
        Channel::Pip
    } else if p.contains("/.cargo/bin/") {
        Channel::Cargo
    } else {
        Channel::Standalone
    }
}

/// The command that updates wmpy for [channel]; None for a standalone wmpy.
pub fn command_for(channel: Channel, version: Option<&str>) -> Option<Vec<String>> {
    let v = |s: &str| s.split_whitespace().map(String::from).collect::<Vec<String>>();
    Some(match (channel, version) {
        (Channel::Homebrew, _) => v("brew upgrade wmpy"),
        (Channel::Scoop, _) => v("scoop update wmpy"),
        (Channel::Npm, Some(x)) => v(&format!("npm install -g wmpy@{x}")),
        (Channel::Npm, None) => v("npm install -g wmpy@latest"),
        (Channel::Pipx, Some(x)) => v(&format!("pipx install --force wmpy=={x}")),
        (Channel::Pipx, None) => v("pipx upgrade wmpy"),
        (Channel::Uv, Some(x)) => v(&format!("uv tool install --force wmpy=={x}")),
        (Channel::Uv, None) => v("uv tool upgrade wmpy"),
        (Channel::Pip, Some(x)) => v(&format!("python -m pip install wmpy=={x}")),
        (Channel::Pip, None) => v("python -m pip install --upgrade wmpy"),
        (Channel::Cargo, _) => v("cargo install --git https://github.com/wasi-master/wmkeyboard wmpy --force"),
        (Channel::Standalone, _) => return None,
    })
}

/// The release archive for the computer wmpy runs on.
pub fn asset_for_this_computer() -> Option<&'static str> {
    Some(match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => "wmpy-x86_64-unknown-linux-musl.tar.gz",
        ("linux", "aarch64") => "wmpy-aarch64-unknown-linux-musl.tar.gz",
        ("macos", "aarch64") => "wmpy-aarch64-apple-darwin.tar.gz",
        ("macos", "x86_64") => "wmpy-x86_64-apple-darwin.tar.gz",
        ("windows", "x86_64") => "wmpy-x86_64-pc-windows-msvc.zip",
        ("windows", "aarch64") => "wmpy-aarch64-pc-windows-msvc.zip",
        _ => return None,
    })
}

/// The `wmpy` program inside a release archive.
fn program_in(asset: &str, bytes: &[u8]) -> Result<Vec<u8>, String> {
    let name = if asset.ends_with(".zip") { "wmpy.exe" } else { "wmpy" };
    if asset.ends_with(".zip") {
        let mut zip = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|e| format!("{asset} is not a zip: {e}"))?;
        let mut f = zip.by_name(name).map_err(|_| format!("{asset} holds no {name}"))?;
        let mut out = Vec::new();
        f.read_to_end(&mut out).map_err(|e| format!("could not read {name}: {e}"))?;
        return Ok(out);
    }
    let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(std::io::Cursor::new(bytes)));
    for entry in tar.entries().map_err(|e| format!("{asset} is not a .tar.gz: {e}"))? {
        let mut e = entry.map_err(|e| format!("{asset} is broken: {e}"))?;
        let is_it = e.path().ok().is_some_and(|p| p.file_name().is_some_and(|f| f == name));
        if is_it && e.header().entry_type().is_file() {
            let mut out = Vec::new();
            e.read_to_end(&mut out).map_err(|e| format!("could not read {name}: {e}"))?;
            return Ok(out);
        }
    }
    Err(format!("{asset} holds no {name}"))
}

/// Puts [program] in place of [exe]: beside it first, then renamed over it,
/// so a failure halfway leaves the old one working.
fn replace(exe: &Path, program: &[u8]) -> Result<(), String> {
    let dir = exe.parent().ok_or("wmpy's folder is unknown")?;
    let fresh = dir.join(format!(".wmpy-new-{}", std::process::id()));
    std::fs::write(&fresh, program).map_err(|e| format!("cannot write into {}: {e}", dir.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&fresh, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
    }
    if cfg!(windows) {
        // A running program cannot be written over on Windows, but it can be moved aside.
        let old = exe.with_extension("old.exe");
        let _ = std::fs::remove_file(&old);
        std::fs::rename(exe, &old).map_err(|e| format!("cannot move {} aside: {e}", exe.display()))?;
        if let Err(e) = std::fs::rename(&fresh, exe) {
            let _ = std::fs::rename(&old, exe);
            return Err(format!("cannot put the new wmpy in place: {e}"));
        }
    } else if let Err(e) = std::fs::rename(&fresh, exe) {
        let _ = std::fs::remove_file(&fresh);
        return Err(format!("cannot put the new wmpy in place of {}: {e}", exe.display()));
    }
    Ok(())
}

pub fn run(args: Args) -> u8 {
    let current = env!("CARGO_PKG_VERSION");
    let exe = std::env::current_exe().ok().and_then(|p| p.canonicalize().ok()).unwrap_or_else(|| PathBuf::from("wmpy"));
    let channel = channel_of(&exe);
    let target = match &args.version {
        Some(v) if parse(v).is_some() => v.trim_start_matches('v').to_string(),
        Some(v) => {
            eprintln!("error: --version {v}: give a version such as 0.2.0");
            return 2;
        }
        None => match latest(Duration::from_secs(30)) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("error: {e}");
                return 2;
            }
        },
    };
    let update = args.version.is_some() || newer(&target, current);
    let command = command_for(channel, args.version.as_deref());
    if args.check {
        if args.json {
            println!(
                "{}",
                json!({"current": current, "latest": target, "newer": newer(&target, current), "channel": format!("{channel:?}").to_lowercase(), "command": command.as_ref().map(|c| c.join(" "))})
            );
        } else if newer(&target, current) {
            println!("wmpy {target} is out; this is {current}.");
            match &command {
                Some(c) => println!("  update: {} (or `wmpy upgrade`)", c.join(" ")),
                None => println!("  update: wmpy upgrade"),
            }
        } else {
            println!("wmpy {current} is the newest.");
        }
        return 0;
    }
    if !update {
        println!("wmpy {current} is the newest.");
        return 0;
    }
    if let Some(cmd) = command {
        eprintln!("$ {}", cmd.join(" "));
        return match std::process::Command::new(&cmd[0]).args(&cmd[1..]).status() {
            Ok(s) if s.success() => 0,
            Ok(s) => {
                eprintln!("error: {} exited with {s}", cmd[0]);
                1
            }
            Err(e) => {
                eprintln!("error: cannot run {}: {e}", cmd[0]);
                2
            }
        };
    }
    let Some(asset) = asset_for_this_computer() else {
        eprintln!("error: wmpy has no release for this computer ({} {}); build it with cargo", std::env::consts::OS, std::env::consts::ARCH);
        return 2;
    };
    crate::note!("downloading {asset} from wmpy {target}");
    let bytes = match crate::editor::release::fetch_version(&target, asset) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let program = match program_in(asset, &bytes) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    if let Err(e) = replace(&exe, &program) {
        eprintln!("error: {e}");
        return 2;
    }
    println!("wmpy {current} -> {target} ({})", exe.display());
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_as_numbers() {
        assert!(newer("0.10.0", "0.9.9"));
        assert!(newer("v1.0.0", "0.99.0"));
        assert!(!newer("0.1.0", "0.1.0"));
        assert!(!newer("garbage", "0.1.0"));
    }

    #[test]
    fn the_channel_comes_from_where_wmpy_lives() {
        assert_eq!(channel_of(Path::new("/opt/homebrew/Cellar/wmpy/0.1.0/bin/wmpy")), Channel::Homebrew);
        assert_eq!(channel_of(Path::new("C:\\Users\\a\\scoop\\apps\\wmpy\\current\\wmpy.exe")), Channel::Scoop);
        assert_eq!(channel_of(Path::new("/usr/lib/node_modules/@wmpy/darwin-arm64/wmpy")), Channel::Npm);
        assert_eq!(channel_of(Path::new("/home/a/.local/share/pipx/venvs/wmpy/lib/python3.12/site-packages/wmpy/bin/wmpy")), Channel::Pipx);
        assert_eq!(channel_of(Path::new("/home/a/.cargo/bin/wmpy")), Channel::Cargo);
        assert_eq!(channel_of(Path::new("/home/a/.local/bin/wmpy")), Channel::Standalone);
        assert_eq!(command_for(Channel::Homebrew, None).unwrap().join(" "), "brew upgrade wmpy");
        assert!(command_for(Channel::Standalone, None).is_none());
    }
}
