//! `wmpy push`, `wmpy dev`, `wmpy logs` and `wmpy device`: a plugin on a
//! phone over adb, without the file manager.
//!
//! The phone side is WM Keyboard's `PluginDevProvider`
//! (core/plugins), which answers only adb's shell, and only while the
//! user has switched on Plugin developer mode:
//!
//! ```text
//! adb shell content write --uri content://com.wasimaster.wmkeyboard.plugindev/install/<token> < x.wmplugin
//! adb shell content call  --uri content://com.wasimaster.wmkeyboard.plugindev --method result --arg <token>
//! adb shell content call  --uri ... --method status | reload <id> | log <id>
//! ```
//!
//! `content write` exists from Android 9; on 7 and 8 the archive goes as
//! base64 pieces (`--method part --arg <token>:<base64>`). Every reply is
//! `Result: Bundle[{json=...}]` ([parse::reply]).

mod adb;
mod parse;

use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};
use wm_python_tooling::manifest as codec;

use crate::diag::{color_stderr, Diag, Severity};
use crate::project;
use adb::Adb;
use parse::{Device, Reply};

/// WM Keyboard's application id: every build has the same one.
pub const PACKAGE: &str = "com.wasimaster.wmkeyboard";

/// The logcat tag plugin lines go out under in developer mode.
const TAG: &str = "WMPlugin";

/// The first Android version whose `content` tool has `write` (Android 9).
const WRITE_SDK: u32 = 28;

/// Base64 characters per piece on Android 7 and 8, so one `adb shell`
/// command line stays under the 4 KB an old adbd takes.
const PIECE: usize = 3000;

const QUICK: Duration = Duration::from_secs(15);
const SEND: Duration = Duration::from_secs(120);
const INSTALL: Duration = Duration::from_secs(60);

fn uri() -> String {
    format!("content://{PACKAGE}.plugindev")
}

/// Where on the phone the switch is, for every message that needs it.
const DEV_MODE_STEPS: &str = "on the phone, open WM Keyboard's settings, go to Tools / Plugins / Manage plugins, and switch on Plugin developer mode (under For plugin developers)";

const CONNECT_HELP: &str = "connect the phone with a USB cable and turn on USB debugging, or pair it with wireless debugging: https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi";

// ---- arguments ---------------------------------------------------------------

#[derive(clap::Args, Debug)]
pub struct DeviceArgs {
    /// Only this phone (a serial from `adb devices`)
    #[arg(short = 's', long)]
    pub serial: Option<String>,
    /// Print what was found as JSON
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct PushArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// The phone, when more than one is connected (a serial from `adb devices`)
    #[arg(short = 's', long)]
    pub serial: Option<String>,
    /// Push without checking the plugin first
    #[arg(long)]
    pub no_check: bool,
    /// Print the phone's reply as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct DevArgs {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// The phone, when more than one is connected (a serial from `adb devices`)
    #[arg(short = 's', long)]
    pub serial: Option<String>,
    /// Push without checking the plugin first
    #[arg(long)]
    pub no_check: bool,
    /// Stop after this many pushes (for tests)
    #[arg(long, hide = true)]
    pub exit_after: Option<u32>,
}

#[derive(clap::Args, Debug)]
pub struct LogsArgs {
    /// A plugin folder or a plugin id [default: the plugin in this folder, else every plugin]
    pub plugin: Option<String>,
    /// The phone, when more than one is connected (a serial from `adb devices`)
    #[arg(short = 's', long)]
    pub serial: Option<String>,
    /// Only lines since then: 30s, 10m, 2h, 1d, or a logcat time like '11-26 14:00:00.000'
    #[arg(long)]
    pub since: Option<String>,
}

// ---- the phone ------------------------------------------------------------------

/// A phone wmpy can talk to.
struct Phone {
    adb: Adb,
    device: Device,
}

/// A failure and its exit code: 1 when the plugin is at fault, 2 when wmpy
/// could not do the work.
struct Fail(Diag, u8);

impl Fail {
    fn adb(message: impl Into<String>) -> Fail {
        Fail(Diag::error(message), 2)
    }

    fn print(&self) -> u8 {
        eprint!("{}", self.0.render(&|_| None, color_stderr()));
        self.1
    }
}

fn list(adb: &Adb) -> Result<Vec<Device>, Fail> {
    let out = adb.run(&["devices", "-l"], None, QUICK).map_err(Fail::adb)?;
    Ok(parse::devices(&out.stdout))
}

/// The phone to use: [serial], or `$ANDROID_SERIAL`, or the one phone connected.
fn connect(serial: Option<&str>) -> Result<Phone, Fail> {
    let adb = Adb::find().map_err(|d| Fail(d, 2))?;
    let devices = list(&adb)?;
    let wanted = serial.map(String::from).or_else(|| std::env::var("ANDROID_SERIAL").ok().filter(|s| !s.trim().is_empty()));
    let listing = || devices.iter().map(|d| format!("{}  {}", d.serial, if d.ready() { d.model.clone() } else { d.state.clone() })).collect::<Vec<_>>();
    if let Some(wanted) = wanted {
        let Some(device) = devices.iter().find(|d| d.serial == wanted) else {
            let mut d = Diag::error(format!("no phone {wanted} is connected"));
            if devices.is_empty() {
                d.help = Some(CONNECT_HELP.into());
            } else {
                d.notes.push(format!("adb lists:\n    {}", listing().join("\n    ")));
            }
            return Err(Fail(d, 2));
        };
        if !device.ready() {
            let mut d = Diag::error(format!("{} is {}", device.serial, device.state));
            d.help = Some(parse::state_help(&device.state));
            return Err(Fail(d, 2));
        }
        return Ok(Phone { adb: adb.on(&device.serial), device: device.clone() });
    }
    let ready: Vec<&Device> = devices.iter().filter(|d| d.ready()).collect();
    match ready.len() {
        1 => Ok(Phone { adb: adb.on(&ready[0].serial), device: ready[0].clone() }),
        0 => {
            let mut d = Diag::error("no phone is connected over adb");
            if let Some(other) = devices.first() {
                d.message = format!("{} is {}, so wmpy cannot use it", other.label(), other.state);
                d.help = Some(parse::state_help(&other.state));
            } else {
                d.help = Some(CONNECT_HELP.into());
            }
            Err(Fail(d, 2))
        }
        n => {
            let mut d = Diag::error(format!("{n} phones are connected: say which with -s"));
            d.notes.push(format!(
                "connected:\n    {}",
                ready.iter().map(|d| format!("{}  {}", d.serial, d.model)).collect::<Vec<_>>().join("\n    ")
            ));
            d.help = Some(format!("for example: wmpy push -s {}", ready[0].serial));
            Err(Fail(d, 2))
        }
    }
}

impl Phone {
    fn shell(&self, args: &[&str], timeout: Duration) -> Result<adb::Out, Fail> {
        let mut all = vec!["shell"];
        all.extend_from_slice(args);
        self.adb.run(&all, None, timeout).map_err(Fail::adb)
    }

    fn call(&self, method: &str, arg: Option<&str>, timeout: Duration) -> Result<Reply, Fail> {
        let uri = uri();
        let mut args = vec!["content", "call", "--uri", uri.as_str(), "--method", method];
        if let Some(arg) = arg {
            args.extend_from_slice(&["--arg", arg]);
        }
        Ok(parse::reply(&self.shell(&args, timeout)?.text()))
    }

    fn sdk(&self) -> Option<u32> {
        self.shell(&["getprop", "ro.build.version.sdk"], QUICK).ok().and_then(|o| parse::sdk(&o.stdout))
    }

    fn installed(&self) -> Result<bool, Fail> {
        Ok(parse::has_package(&self.shell(&["pm", "list", "packages", PACKAGE], QUICK)?.stdout, PACKAGE))
    }

    /// The phone's clock, in seconds since 1970, for `logcat -T`.
    fn epoch(&self) -> Option<u64> {
        self.shell(&["date", "+%s"], QUICK).ok().and_then(|o| o.stdout.trim().parse().ok())
    }

    /// A reply that is not JSON, as the failure it is.
    fn refused(&self, reply: Reply) -> Fail {
        match reply {
            Reply::Json(v) => reply_failure(&v),
            Reply::DevModeOff => {
                let mut d = Diag::error(format!("plugin developer mode is off on {}", self.device.label()));
                d.help = Some(format!("turn it on: {DEV_MODE_STEPS}"));
                Fail(d, 2)
            }
            Reply::NoProvider => match self.installed() {
                Ok(false) => not_installed(&self.device),
                _ => {
                    let mut d = Diag::error(format!("WM Keyboard on {} has no plugin developer mode yet", self.device.label()));
                    d.help = Some("update WM Keyboard to the newest version".into());
                    Fail(d, 2)
                }
            },
            Reply::NotAdb => Fail::adb("the keyboard refused the call: it answers only adb's shell"),
            Reply::Other(text) => {
                let mut d = Diag::error("the phone answered something wmpy cannot read");
                if !text.is_empty() {
                    d.notes.push(text);
                }
                Fail(d, 2)
            }
        }
    }
}

fn not_installed(device: &Device) -> Fail {
    let mut d = Diag::error(format!("WM Keyboard is not installed on {}", device.label()));
    d.help = Some("install it from Google Play, F-Droid or https://github.com/wasi-master/wmkeyboard/releases".into());
    Fail(d, 2)
}

/// A JSON reply with `"ok": false`, as a failure.
fn reply_failure(v: &Json) -> Fail {
    let code = v["error"].as_str().unwrap_or("error");
    let message = v["message"].as_str().unwrap_or("the keyboard refused it").to_string();
    let mut d = Diag::error(message);
    d.code = Some(code.to_string());
    let exit = match code {
        "plugins_off" => {
            d.help = Some("on the phone, switch on Allow plugins on Tools / Plugins / Manage plugins".into());
            2
        }
        "rejected" | "not_a_plugin" | "too_large" | "too_many" => 1,
        "timeout" | "broken" | "empty" => {
            d.help = Some("push again; if it keeps happening, check the cable or the wireless debugging connection".into());
            2
        }
        _ => 2,
    };
    Fail(d, exit)
}

// ---- wmpy device -----------------------------------------------------------

pub fn devices(a: DeviceArgs) -> u8 {
    let adb = match Adb::find() {
        Ok(adb) => adb,
        Err(d) => return Fail(d, 2).print(),
    };
    let mut all = match list(&adb) {
        Ok(all) => all,
        Err(f) => return f.print(),
    };
    if let Some(s) = &a.serial {
        all.retain(|d| &d.serial == s);
        if all.is_empty() {
            return Fail::adb(format!("no phone {s} is connected")).print();
        }
    }
    if all.is_empty() {
        if a.json {
            println!("[]");
            return 0;
        }
        let mut d = Diag::error("no phone is connected over adb");
        d.help = Some(CONNECT_HELP.into());
        return Fail(d, 2).print();
    }
    let mut found = Vec::new();
    for device in &all {
        found.push(inspect(&adb, device));
    }
    if a.json {
        println!("{}", serde_json::to_string_pretty(&Json::Array(found)).unwrap_or_default());
        return 0;
    }
    for (device, facts) in all.iter().zip(&found) {
        print_device(device, facts);
    }
    0
}

/// What wmpy can learn about [device]: Android's level, the keyboard, developer mode.
fn inspect(adb: &Adb, device: &Device) -> Json {
    let mut facts = json!({"serial": device.serial, "state": device.state, "model": device.model, "usb": device.usb});
    if !device.ready() {
        facts["help"] = json!(parse::state_help(&device.state));
        return facts;
    }
    let phone = Phone { adb: adb.on(&device.serial), device: device.clone() };
    facts["sdk"] = json!(phone.sdk());
    let installed = phone.installed().unwrap_or(false);
    facts["installed"] = json!(installed);
    if !installed {
        return facts;
    }
    match phone.call("status", None, QUICK) {
        Ok(Reply::Json(v)) if v["ok"] == true => {
            facts["developerMode"] = json!(true);
            for key in ["appVersion", "pluginApi", "pluginsOn", "allowRisky", "plugins"] {
                facts[key] = v[key].clone();
            }
        }
        Ok(Reply::DevModeOff) => {
            facts["developerMode"] = json!(false);
            facts["help"] = json!(format!("turn it on: {DEV_MODE_STEPS}"));
        }
        Ok(Reply::NoProvider) => {
            facts["developerMode"] = Json::Null;
            facts["help"] = json!("this WM Keyboard is older than plugin developer mode: update it");
        }
        Ok(other) => facts["problem"] = json!(format!("{other:?}")),
        Err(f) => facts["problem"] = json!(f.0.message),
    }
    facts
}

fn print_device(device: &Device, facts: &Json) {
    let color = color_stdout();
    let bold = |t: &str| paint(color, "1", t);
    let mut head = device.label();
    if let Some(sdk) = facts["sdk"].as_u64() {
        head.push_str(&format!(", Android API {sdk}"));
    }
    println!("{}", bold(&head));
    if !device.ready() {
        println!("  {}: {}", device.state, parse::state_help(&device.state));
        return;
    }
    if facts["installed"] != true {
        println!("  WM Keyboard is not installed");
        return;
    }
    let version = facts["appVersion"].as_str().filter(|v| !v.is_empty()).map(|v| format!(" {v}")).unwrap_or_default();
    println!("  WM Keyboard{version} is installed");
    match facts["developerMode"].as_bool() {
        Some(true) => {
            println!("  plugin developer mode: {}", paint(color, "32", "on"));
            let plugins = facts["plugins"].as_array().cloned().unwrap_or_default();
            let allowed = if facts["pluginsOn"] == true { "allowed" } else { "not allowed (switch on Allow plugins)" };
            println!("  plugins: {allowed}, {} installed", plugins.len());
            for p in plugins {
                println!("    {} {}", p["id"].as_str().unwrap_or(""), p["version"].as_str().unwrap_or(""));
            }
        }
        Some(false) => {
            println!("  plugin developer mode: {}", paint(color, "33", "off"));
            println!("  turn it on: {DEV_MODE_STEPS}");
        }
        None => {
            if let Some(help) = facts["help"].as_str() {
                println!("  {help}");
            } else if let Some(problem) = facts["problem"].as_str() {
                println!("  could not ask the keyboard: {problem}");
            }
        }
    }
}

// ---- wmpy push --------------------------------------------------------------

pub fn push(a: PushArgs) -> u8 {
    let dir = match project::plugin_dir(&a.dir) {
        Ok(d) => d,
        Err(d) => return Fail(d, 2).print(),
    };
    let phone = match connect(a.serial.as_deref()) {
        Ok(p) => p,
        Err(f) => return f.print(),
    };
    let sdk = phone.sdk();
    match push_once(&dir, &phone, !a.no_check, sdk) {
        Ok(reply) => {
            if a.json {
                println!("{reply}");
            } else {
                print_installed(&reply, &phone.device);
            }
            0
        }
        Err(f) => f.print(),
    }
}

/// Checks, packs and sends the plugin in [dir], and returns the phone's reply.
fn push_once(dir: &Path, phone: &Phone, check: bool, sdk: Option<u32>) -> Result<Json, Fail> {
    if check {
        let outcome = crate::check::check_dir(dir);
        if outcome.errors() > 0 {
            let source = crate::diag::folder_source(dir);
            let color = color_stderr();
            for d in outcome.problems.iter().filter(|d| d.severity == Severity::Error) {
                eprint!("{}", d.render(&source, color));
                eprintln!();
            }
            let n = outcome.errors();
            let d = Diag::error(format!(
                "not pushed: {n} {} (run `wmpy check` for all of it, or push anyway with --no-check)",
                if n == 1 { "error" } else { "errors" }
            ));
            return Err(Fail(d, 1));
        }
    }
    let text = std::fs::read_to_string(dir.join(codec::MANIFEST)).map_err(|e| Fail::adb(format!("cannot read plugin.json: {e}")))?;
    let Some(m) = codec::check(&text).manifest else {
        return Err(Fail(Diag::error("plugin.json is not a JSON object, so there is nothing to push"), 1));
    };
    let packed = crate::pack::pack(dir, &m, true, false).map_err(|d| Fail(d, 1))?;
    let token = token().map_err(Fail::adb)?;
    // An unknown level is a new phone more often than an old one.
    if sdk.is_none_or(|s| s >= WRITE_SDK) {
        send_write(phone, &token, &packed.bytes)?;
    } else {
        send_pieces(phone, &token, &packed.bytes)?;
    }
    match phone.call("result", Some(&token), INSTALL)? {
        Reply::Json(v) if v["ok"] == true => Ok(v),
        other => Err(phone.refused(other)),
    }
}

/// `content write`: the archive on adb's stdin, into the provider's pipe.
fn send_write(phone: &Phone, token: &str, bytes: &[u8]) -> Result<(), Fail> {
    let target = format!("{}/install/{token}", uri());
    let out = phone.adb.run(&["shell", "-T", "content", "write", "--uri", &target], Some(bytes), SEND).map_err(Fail::adb)?;
    match parse::write_failed(&out.text()) {
        None if out.code == 0 => Ok(()),
        None => Err(Fail::adb(format!("`adb shell content write` stopped with exit code {}", out.code))),
        Some(reply) => Err(phone.refused(reply)),
    }
}

/// Android 7 and 8: base64 pieces through `content call`.
fn send_pieces(phone: &Phone, token: &str, bytes: &[u8]) -> Result<(), Fail> {
    use base64::Engine;
    let text = base64::engine::general_purpose::STANDARD.encode(bytes);
    for piece in text.as_bytes().chunks(PIECE) {
        let arg = format!("{token}:{}", std::str::from_utf8(piece).unwrap_or(""));
        match phone.call("part", Some(&arg), QUICK)? {
            Reply::Json(v) if v["ok"] == true => {}
            other => return Err(phone.refused(other)),
        }
    }
    Ok(())
}

/// 16 lowercase hex digits, naming one upload.
fn token() -> Result<String, String> {
    let mut bytes = [0u8; 8];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

/// "installed Demo 1.2 on CPH2481", and what stayed off.
fn print_installed(v: &Json, device: &Device) {
    let color = color_stderr();
    let name = v["name"].as_str().filter(|n| !n.is_empty()).or(v["id"].as_str()).unwrap_or("the plugin");
    let action = v["action"].as_str().unwrap_or("installed");
    let mut line = format!("{} {name} {} on {}", paint(color, "1;32", action), v["version"].as_str().unwrap_or(""), device.label());
    if v["reloaded"] == true {
        line.push_str("; its open panel loaded the new version");
    }
    eprintln!("{line}");
    if v["enabled"] == false {
        eprintln!("  it is turned off: turn it on on its page in Tools / Plugins / Manage plugins");
    }
    let off: Vec<&str> = v["riskyOff"].as_array().map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
    if !off.is_empty() {
        eprintln!("  {}: {}", paint(color, "1;33", "risky permissions left off"), off.join(", "));
        eprintln!("    turn them on on the phone: Tools / Plugins / Manage plugins / {name} / Permissions");
        if v["allowRisky"] == false {
            eprintln!("    first switch on Allow risky permissions, on Manage plugins");
        }
    }
    let hosts: Vec<&str> = v["newSinceUpdate"]
        .as_array()
        .map(|a| a.iter().filter_map(|x| x.as_str()).filter(|w| codec::permission(w).is_none()).collect())
        .unwrap_or_default();
    if !hosts.is_empty() {
        eprintln!("  new websites since the last version: {} (the network permission waits for you again)", hosts.join(", "));
    }
}

// ---- wmpy dev ---------------------------------------------------------------

pub fn dev(a: DevArgs) -> u8 {
    let dir = match project::plugin_dir(&a.dir) {
        Ok(d) => d,
        Err(d) => return Fail(d, 2).print(),
    };
    let id = project::manifest(&dir).map(|m| m.id).unwrap_or_default();
    let phone = match connect(a.serial.as_deref()) {
        Ok(p) => p,
        Err(f) => return f.print(),
    };
    let stop = adb::watch_ctrl_c();
    let sdk = phone.sdk();
    let pushes = std::cell::Cell::new(0u32);
    let push = |why: &str| -> Result<(), u8> {
        if !why.is_empty() {
            eprintln!("{why}: pushing again");
        }
        pushes.set(pushes.get() + 1);
        match push_once(&dir, &phone, !a.no_check, sdk) {
            Ok(reply) => {
                print_installed(&reply, &phone.device);
                Ok(())
            }
            // A plugin that does not check or install waits for the next save;
            // a phone that cannot be reached ends the session.
            Err(f) if f.1 == 1 => {
                f.print();
                Ok(())
            }
            Err(f) => Err(f.print()),
        }
    };
    if let Err(code) = push("") {
        return code;
    }
    // The log from now on, beside the pushes.
    let since = phone.epoch().map(|t| format!("{t}.000"));
    let mut args = vec!["logcat", "-v", "time"];
    if let Some(t) = since.as_deref() {
        args.extend_from_slice(&["-T", t]);
    }
    args.extend_from_slice(&["-s", TAG]);
    let mut logcat = phone.adb.stream(&args).ok();
    let lines = logcat.as_mut().and_then(|c| c.stdout.take()).map(line_channel);
    let filter = Some(id.clone()).filter(|i| !i.is_empty());
    eprintln!("watching {} for changes; the plugin's log follows. Ctrl-C stops.", project::shown(&dir));
    let mut seen = signature(&dir);
    let mut changed_at: Option<(Instant, String)> = None;
    let mut code = 0;
    while !stop.load(std::sync::atomic::Ordering::Relaxed) {
        if a.exit_after.is_some_and(|n| pushes.get() >= n) {
            break;
        }
        if let Some(rx) = &lines {
            while let Ok(line) = rx.try_recv() {
                print_log(&line, filter.as_deref());
            }
        }
        let now = signature(&dir);
        if now != seen {
            let what = changed_file(&seen, &now);
            seen = now;
            changed_at = Some((Instant::now(), what));
        } else if let Some((at, what)) = &changed_at {
            // Pushed once the folder has been quiet for a moment: an editor
            // that saves several files, or writes a file twice, pushes once.
            if at.elapsed() >= DEBOUNCE {
                let why = format!("{what} changed");
                changed_at = None;
                if let Err(c) = push(&why) {
                    code = c;
                    break;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    if let Some(mut child) = logcat {
        let _ = child.kill();
        let _ = child.wait();
    }
    if stop.load(std::sync::atomic::Ordering::Relaxed) {
        eprintln!("stopped");
    }
    code
}

/// How long the folder must be still after a change before it is pushed.
const DEBOUNCE: Duration = Duration::from_millis(400);

/// Every file the archive could take, with its time and size.
fn signature(dir: &Path) -> Vec<(String, u128, u64)> {
    project::files(dir)
        .into_iter()
        .filter(|f| !f.ends_with(".wmplugin"))
        .take(4000)
        .filter_map(|f| {
            let meta = std::fs::metadata(dir.join(&f)).ok()?;
            let mtime = meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_nanos());
            Some((f, mtime, meta.len()))
        })
        .collect()
}

fn changed_file(old: &[(String, u128, u64)], new: &[(String, u128, u64)]) -> String {
    new.iter()
        .find(|n| !old.contains(n))
        .or_else(|| old.iter().find(|o| !new.iter().any(|n| n.0 == o.0)))
        .map(|f| f.0.clone())
        .unwrap_or_else(|| "a file".into())
}

/// Lines of [pipe], one at a time, on a channel that closes with it.
fn line_channel<R: std::io::Read + Send + 'static>(pipe: R) -> mpsc::Receiver<String> {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        use std::io::BufRead;
        let reader = std::io::BufReader::new(pipe);
        for line in reader.split(b'\n').map_while(Result::ok) {
            if tx.send(String::from_utf8_lossy(&line).into_owned()).is_err() {
                break;
            }
        }
    });
    rx
}

// ---- wmpy logs --------------------------------------------------------------

pub fn logs(a: LogsArgs) -> u8 {
    let id = match plugin_id(a.plugin.as_deref()) {
        Ok(id) => id,
        Err(f) => return f.print(),
    };
    let phone = match connect(a.serial.as_deref()) {
        Ok(p) => p,
        Err(f) => return f.print(),
    };
    let stop = adb::watch_ctrl_c();
    let since = match a.since.as_deref() {
        None => None,
        Some(s) => match parse::since_seconds(s) {
            Some(secs) => match phone.epoch() {
                Some(now) => Some(format!("{}.000", now.saturating_sub(secs))),
                None => return Fail::adb("cannot read the phone's clock for --since: give a logcat time like '11-26 14:00:00.000'").print(),
            },
            None => Some(s.to_string()),
        },
    };
    // Is anything there yet? The mode may have just been turned on, and then
    // the plugin's own saved log is all there is.
    let mut dump = vec!["logcat", "-v", "time"];
    match since.as_deref() {
        Some(t) => dump.extend_from_slice(&["-t", t]),
        None => dump.push("-d"),
    }
    dump.extend_from_slice(&["-s", TAG]);
    let backlog = phone.adb.run(&dump, None, QUICK).map(|o| o.stdout).unwrap_or_default();
    let any = backlog.lines().filter_map(|l| parse::log_line(l, TAG)).any(|l| id.as_deref().is_none_or(|i| l.plugin == i));
    if !any {
        fallback(&phone, id.as_deref());
    }
    let mut args = vec!["logcat", "-v", "time"];
    if let Some(t) = since.as_deref() {
        args.extend_from_slice(&["-T", t]);
    }
    args.extend_from_slice(&["-s", TAG]);
    let mut child = match phone.adb.stream(&args) {
        Ok(c) => c,
        Err(e) => return Fail::adb(e).print(),
    };
    let Some(rx) = child.stdout.take().map(line_channel) else {
        return Fail::adb("adb logcat printed nothing").print();
    };
    loop {
        if stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(line) => print_log(&line, id.as_deref()),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    let _ = child.kill();
    let _ = child.wait();
    0
}

/// The plugin [given] names: a folder's plugin, or an id as typed. With
/// nothing given, the plugin in this folder, or none (every plugin's lines).
fn plugin_id(given: Option<&str>) -> Result<Option<String>, Fail> {
    let Some(given) = given else {
        return Ok(project::plugin_dir(Path::new(".")).ok().and_then(|d| project::manifest(&d).ok()).map(|m| m.id).filter(|i| !i.is_empty()));
    };
    let path = Path::new(given);
    if path.exists() {
        let dir = project::plugin_dir(path).map_err(|d| Fail(d, 2))?;
        let id = project::manifest(&dir).map(|m| m.id).unwrap_or_default();
        if id.is_empty() {
            return Err(Fail(Diag::error(format!("{}'s plugin.json has no id", project::shown(&dir))), 1));
        }
        return Ok(Some(id));
    }
    if codec::is_valid_id(given) {
        return Ok(Some(given.to_string()));
    }
    let mut d = Diag::error(format!("'{given}' is neither a plugin folder nor a plugin id"));
    d.help = Some("give the plugin's folder, or the id from its plugin.json".into());
    Err(Fail(d, 2))
}

/// Nothing in the system log: say why, and print the plugin's saved log.
fn fallback(phone: &Phone, id: Option<&str>) {
    let color = color_stderr();
    let Some(id) = id else {
        eprintln!("{}", paint(color, "2", "nothing from plugins in the system log yet"));
        return;
    };
    match phone.call("log", Some(id), QUICK) {
        Ok(Reply::Json(v)) if v["ok"] == true => {
            let lines: Vec<&str> = v["lines"].as_array().map(|a| a.iter().filter_map(|x| x.as_str()).collect()).unwrap_or_default();
            if lines.is_empty() {
                eprintln!("{}", paint(color, "2", &format!("nothing from {id} yet: its lines show here as it prints")));
                return;
            }
            eprintln!("{}", paint(color, "2", &format!("nothing from {id} in the system log yet; its saved log, from when its panel last closed:")));
            for line in lines {
                print_text(color_stdout(), saved_level(line), "", line);
            }
            eprintln!("{}", paint(color, "2", "new lines follow as it prints:"));
        }
        Ok(Reply::Json(v)) => {
            eprintln!("{}", paint(color, "2", v["message"].as_str().unwrap_or("the keyboard has no log for it")));
        }
        Ok(Reply::DevModeOff) => {
            eprintln!("{}", paint(color, "33", "plugin developer mode is off, so plugin lines do not reach the system log"));
            eprintln!("  turn it on: {DEV_MODE_STEPS}");
        }
        Ok(_) | Err(_) => {}
    }
}

/// One logcat line, if it is [id]'s (or any plugin's with no [id]).
fn print_log(raw: &str, id: Option<&str>) {
    let Some(line) = parse::log_line(raw, TAG) else { return };
    if id.is_some_and(|i| i != line.plugin) {
        return;
    }
    let color = color_stdout();
    let time = line.time.rsplit(' ').next().unwrap_or(&line.time);
    let who = if id.is_none() { format!("{}: ", line.plugin) } else { String::new() };
    print_text(color, line.level, time, &format!("{who}{}", line.text));
}

/// The level the keyboard would have logged a saved line at, by its prefix (as `PluginDevMode.priorityOf`).
fn saved_level(line: &str) -> char {
    if line.starts_with("error:") {
        'E'
    } else if line.starts_with("stopped:") || line.starts_with("abandoned:") {
        'W'
    } else {
        'I'
    }
}

fn print_text(color: bool, level: char, time: &str, text: &str) {
    let body = match level {
        'E' | 'F' => paint(color, "1;31", text),
        'W' => paint(color, "33", text),
        _ => text.to_string(),
    };
    if time.is_empty() {
        println!("{body}");
    } else {
        println!("{} {body}", paint(color, "2", time));
    }
}

fn paint(color: bool, code: &str, text: &str) -> String {
    if color {
        format!("\x1b[{code}m{text}\x1b[0m")
    } else {
        text.to_string()
    }
}

/// Whether stdout is a terminal that takes colour (and NO_COLOR is unset).
fn color_stdout() -> bool {
    use std::io::IsTerminal;
    std::env::var_os("NO_COLOR").is_none() && std::io::stdout().is_terminal()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_token_is_sixteen_hex_digits() {
        let t = token().unwrap();
        assert_eq!(t.len(), 16);
        assert!(t.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
        assert_ne!(t, token().unwrap());
    }

    #[test]
    fn a_piece_fits_an_old_adb_command_line() {
        let line = format!("shell content call --uri {} --method part --arg {}:{}", uri(), "0".repeat(16), "A".repeat(PIECE));
        assert!(line.len() < 4000, "{}", line.len());
    }

    #[test]
    fn the_changed_file_is_named() {
        let old = vec![("main.py".to_string(), 1, 10), ("plugin.json".to_string(), 1, 5)];
        let new = vec![("main.py".to_string(), 2, 11), ("plugin.json".to_string(), 1, 5)];
        assert_eq!(changed_file(&old, &new), "main.py");
        assert_eq!(changed_file(&old, &old[1..]), "main.py");
    }

    #[test]
    fn refusals_have_the_right_exit_codes() {
        assert_eq!(reply_failure(&json!({"ok": false, "error": "rejected", "message": "bad"})).1, 1);
        assert_eq!(reply_failure(&json!({"ok": false, "error": "plugins_off", "message": "off"})).1, 2);
        assert_eq!(reply_failure(&json!({"ok": false, "error": "timeout"})).1, 2);
    }
}
