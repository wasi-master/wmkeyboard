//! Finding adb and running it. Every adb call wmpy makes goes through
//! [Adb::run] or [Adb::stream], so a test can put a fake adb in its place
//! (`WMPY_ADB`), and every call has a time limit.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use crate::diag::Diag;

/// Where to get adb when it is missing.
pub const PLATFORM_TOOLS: &str = "https://developer.android.com/tools/releases/platform-tools";

/// Set by Ctrl-C once [watch_ctrl_c] has run: a call in flight stops and returns.
static STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();

/// Catches Ctrl-C (and SIGTERM) so a long command can stop its adb children
/// and say so, instead of dying mid-line. Call once, from a command that
/// runs until stopped.
pub fn watch_ctrl_c() -> Arc<AtomicBool> {
    let flag = STOP.get_or_init(|| Arc::new(AtomicBool::new(false))).clone();
    let _ = signal_hook::flag::register(signal_hook::consts::SIGINT, flag.clone());
    let _ = signal_hook::flag::register(signal_hook::consts::SIGTERM, flag.clone());
    flag
}

pub fn stopped() -> bool {
    STOP.get().is_some_and(|f| f.load(Ordering::Relaxed))
}

/// What one adb run printed.
#[derive(Debug, Default, Clone)]
pub struct Out {
    pub code: i32,
    pub stdout: String,
    pub stderr: String,
}

impl Out {
    /// Both streams, for looking for a phrase wherever adb put it.
    pub fn text(&self) -> String {
        if self.stderr.is_empty() {
            self.stdout.clone()
        } else {
            format!("{}\n{}", self.stdout, self.stderr)
        }
    }
}

/// adb, and the phone its calls go to.
#[derive(Debug, Clone)]
pub struct Adb {
    pub exe: PathBuf,
    pub serial: Option<String>,
}

/// The longest any one call may take, unless `WMPY_ADB_TIMEOUT` (seconds) says less.
fn limit(wanted: Duration) -> Duration {
    match std::env::var("WMPY_ADB_TIMEOUT").ok().and_then(|s| s.trim().parse::<f64>().ok()) {
        Some(s) if s > 0.0 => wanted.min(Duration::from_secs_f64(s)),
        _ => wanted,
    }
}

impl Adb {
    /// adb, found, with no phone chosen yet.
    pub fn find() -> Result<Adb, Diag> {
        find_exe().map(|exe| Adb { exe, serial: None })
    }

    /// This adb, talking to [serial].
    pub fn on(&self, serial: &str) -> Adb {
        Adb { exe: self.exe.clone(), serial: Some(serial.to_string()) }
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut c = Command::new(&self.exe);
        if let Some(s) = &self.serial {
            c.arg("-s").arg(s);
        }
        c.args(args);
        c
    }

    /// Runs adb with [args], [input] on its stdin, for at most [timeout].
    pub fn run(&self, args: &[&str], input: Option<&[u8]>, timeout: Duration) -> Result<Out, String> {
        let timeout = limit(timeout);
        let mut cmd = self.command(args);
        cmd.stdin(if input.is_some() { Stdio::piped() } else { Stdio::null() }).stdout(Stdio::piped()).stderr(Stdio::piped());
        let mut child = cmd.spawn().map_err(|e| format!("cannot run {}: {e}", self.exe.display()))?;
        let writer = input.map(|bytes| {
            let mut stdin = child.stdin.take();
            let bytes = bytes.to_vec();
            std::thread::spawn(move || {
                if let Some(s) = stdin.as_mut() {
                    let _ = s.write_all(&bytes);
                }
                // Dropped here: adb sees the end of the input.
            })
        });
        let out = reader(child.stdout.take());
        let err = reader(child.stderr.take());
        let start = Instant::now();
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) => {}
                Err(e) => return Err(format!("adb: {e}")),
            }
            if stopped() {
                let _ = child.kill();
                let _ = child.wait();
                return Err("stopped".into());
            }
            if start.elapsed() >= timeout {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("`adb {}` took longer than {} seconds", shown_args(args), timeout.as_secs().max(1)));
            }
            std::thread::sleep(Duration::from_millis(10));
        };
        if let Some(w) = writer {
            let _ = w.join();
        }
        Ok(Out {
            code: status.code().unwrap_or(-1),
            stdout: out.join().unwrap_or_default(),
            stderr: err.join().unwrap_or_default(),
        })
    }

    /// Starts adb with [args] and hands back the child, its stdout piped:
    /// `adb logcat`, which runs until it is stopped.
    pub fn stream(&self, args: &[&str]) -> Result<Child, String> {
        self.command(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("cannot run {}: {e}", self.exe.display()))
    }
}

fn reader<R: Read + Send + 'static>(pipe: Option<R>) -> std::thread::JoinHandle<String> {
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        if let Some(mut p) = pipe {
            let _ = p.read_to_end(&mut bytes);
        }
        String::from_utf8_lossy(&bytes).into_owned()
    })
}

/// [args] as a person would type them, cut short: a base64 piece is long.
fn shown_args(args: &[&str]) -> String {
    let joined = args.join(" ");
    if joined.chars().count() > 120 {
        format!("{}…", joined.chars().take(120).collect::<String>())
    } else {
        joined
    }
}

/// adb's file name on this system.
fn exe_name() -> &'static str {
    if cfg!(windows) {
        "adb.exe"
    } else {
        "adb"
    }
}

/// Where adb is looked for, in order: `WMPY_ADB`, the PATH, `$ANDROID_HOME`
/// and `$ANDROID_SDK_ROOT`, then the folder Android Studio installs the SDK in.
pub fn candidates() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            out.push(dir.join(exe_name()));
        }
    }
    for var in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = std::env::var_os(var).filter(|v| !v.is_empty()) {
            out.push(Path::new(&root).join("platform-tools").join(exe_name()));
        }
    }
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
    if let Some(home) = home {
        let home = PathBuf::from(home);
        out.push(home.join("Library/Android/sdk/platform-tools").join(exe_name()));
        out.push(home.join("Android/Sdk/platform-tools").join(exe_name()));
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        out.push(PathBuf::from(local).join("Android").join("Sdk").join("platform-tools").join(exe_name()));
    }
    out
}

fn find_exe() -> Result<PathBuf, Diag> {
    if let Some(given) = std::env::var_os("WMPY_ADB").filter(|v| !v.is_empty()) {
        let path = PathBuf::from(given);
        if path.is_file() {
            return Ok(path);
        }
        let mut d = Diag::error(format!("WMPY_ADB names {}, and there is no file there", path.display()));
        d.help = Some("set WMPY_ADB to the adb file, or unset it to let wmpy look for adb".into());
        return Err(d);
    }
    if let Some(found) = candidates().into_iter().find(|p| p.is_file()) {
        return Ok(found);
    }
    let mut d = Diag::error("adb is not installed, or wmpy cannot find it");
    d.notes.push("wmpy looked on the PATH, in $ANDROID_HOME and $ANDROID_SDK_ROOT, and in the Android SDK's usual folder".into());
    d.help = Some(format!(
        "install Android's SDK Platform-Tools from {PLATFORM_TOOLS} and put its platform-tools folder on your PATH, or set WMPY_ADB to the adb file"
    ));
    Err(d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_arguments_are_cut_short_in_messages() {
        let long = "x".repeat(500);
        let shown = shown_args(&["shell", "content", "call", &long]);
        assert!(shown.chars().count() <= 121, "{shown}");
        assert!(shown.ends_with('…'));
        assert_eq!(shown_args(&["devices", "-l"]), "devices -l");
    }

    #[test]
    fn the_sdk_folders_are_among_the_places_looked() {
        let all = candidates();
        assert!(all.iter().all(|p| p.file_name().is_some_and(|n| n == exe_name())));
    }
}
