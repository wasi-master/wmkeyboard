//! Reading what adb and the phone print: the device list, the provider's
//! replies, and logcat lines. Plain functions over text, tested here.

use serde_json::Value as Json;

/// One line of `adb devices -l`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Device {
    pub serial: String,
    /// `device` when it can be used; `unauthorized`, `offline`, `no permissions`, ...
    pub state: String,
    /// `model:` with its underscores back to spaces, or empty.
    pub model: String,
    /// `usb:...` was listed (a cable) rather than a network address.
    pub usb: bool,
}

impl Device {
    pub fn ready(&self) -> bool {
        self.state == "device"
    }

    /// "CPH2481 (192.168.1.5:37000)", or the serial alone.
    pub fn label(&self) -> String {
        if self.model.is_empty() {
            self.serial.clone()
        } else {
            format!("{} ({})", self.model, self.serial)
        }
    }
}

/// The devices `adb devices -l` lists. Lines adb adds about its own server
/// (`* daemon started successfully`) and the heading are left out.
pub fn devices(text: &str) -> Vec<Device> {
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim_end();
        if line.is_empty() || line.starts_with('*') || line.starts_with("List of devices") || line.starts_with("adb ") {
            continue;
        }
        let mut parts = line.splitn(2, char::is_whitespace);
        let serial = parts.next().unwrap_or("").to_string();
        let rest = parts.next().unwrap_or("").trim();
        if serial.is_empty() || rest.is_empty() {
            continue;
        }
        // "no permissions (...); see [...]" is the one state with spaces in it.
        let (state, details) = if let Some(after) = rest.strip_prefix("no permissions") {
            ("no permissions".to_string(), after)
        } else {
            let mut words = rest.splitn(2, char::is_whitespace);
            (words.next().unwrap_or("").to_string(), words.next().unwrap_or(""))
        };
        let mut model = String::new();
        let mut usb = false;
        for word in details.split_whitespace() {
            if let Some(m) = word.strip_prefix("model:") {
                model = m.replace('_', " ");
            } else if word.starts_with("usb:") {
                usb = true;
            }
        }
        out.push(Device { serial, state, model, usb });
    }
    out
}

/// What a state other than `device` means, and what to do.
pub fn state_help(state: &str) -> String {
    match state {
        "unauthorized" => "the phone has not allowed this computer yet: unlock it and press Allow on the USB debugging question".into(),
        "offline" => "adb has lost touch with the phone: unplug it and plug it in again, or turn wireless debugging off and on".into(),
        "no permissions" => "this computer may not open the phone over USB: on Linux, add the udev rule from https://developer.android.com/studio/run/device".into(),
        "authorizing" | "connecting" => "the phone is still connecting: try again in a moment".into(),
        other => format!("adb says the phone is \"{other}\""),
    }
}

/// What a `content call` (or `content write`) printed, read.
#[derive(Debug, Clone, PartialEq)]
pub enum Reply {
    /// The provider's JSON reply.
    Json(Json),
    /// Plugin developer mode is off on the phone.
    DevModeOff,
    /// The provider refused the caller: something other than adb's shell.
    NotAdb,
    /// No provider with that name: WM Keyboard is missing or older than developer mode.
    NoProvider,
    /// Anything else, as it was printed.
    Other(String),
}

/// Reads [text], everything a `content` command printed. A reply is
/// `Result: Bundle[{json=<one line of JSON>}]`: one key, so the JSON is what
/// lies between `{json=` and the last `}]`, whatever it holds.
pub fn reply(text: &str) -> Reply {
    if let Some(line) = text.lines().find(|l| l.trim_start().starts_with("Result: Bundle[")) {
        if let (Some(start), Some(end)) = (line.find("{json="), line.rfind("}]")) {
            let body = &line[start + "{json=".len()..end];
            if let Ok(value) = serde_json::from_str::<Json>(body) {
                return Reply::Json(value);
            }
        }
        return Reply::Other(line.trim().to_string());
    }
    if text.contains("wmkb_dev_mode_off") {
        return Reply::DevModeOff;
    }
    if text.contains("wmkb_not_adb") {
        return Reply::NotAdb;
    }
    if text.contains("Could not find provider") || text.contains("Unknown authority") || text.contains("Unknown URL") {
        return Reply::NoProvider;
    }
    Reply::Other(first_lines(text, 6))
}

/// Whether a `content write` went through: it prints nothing when it does.
pub fn write_failed(text: &str) -> Option<Reply> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }
    match reply(trimmed) {
        Reply::Other(t) if !t.contains("Exception") && !t.contains("Error") && !t.contains("rror:") => None,
        other => Some(other),
    }
}

/// The first [n] non-empty lines of [text], for a message.
pub fn first_lines(text: &str, n: usize) -> String {
    text.lines().map(str::trim_end).filter(|l| !l.trim().is_empty()).take(n).collect::<Vec<_>>().join("\n")
}

/// `getprop ro.build.version.sdk`, as a number.
pub fn sdk(text: &str) -> Option<u32> {
    text.trim().parse().ok()
}

/// Whether `pm list packages <name>` lists exactly [package].
pub fn has_package(text: &str, package: &str) -> bool {
    text.lines().any(|l| l.trim() == format!("package:{package}"))
}

/// One `adb logcat -v time` line of the `WMPlugin` tag.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogLine {
    /// `11-26 14:03:22.123`
    pub time: String,
    /// `I`, `W`, `E`, ...
    pub level: char,
    pub plugin: String,
    pub text: String,
}

/// `11-26 14:03:22.123 I/WMPlugin( 1234): com.example.demo: hello`, read.
/// Null for logcat's own lines (`--------- beginning of main`) and anything
/// of another tag.
pub fn log_line(line: &str, tag: &str) -> Option<LogLine> {
    let marker = format!("/{tag}(");
    let at = line.find(&marker)?;
    let level = line[..at].chars().last()?;
    let time = line[..at - level.len_utf8()].trim().to_string();
    let rest = &line[at + marker.len()..];
    let close = rest.find("): ")?;
    let message = &rest[close + 3..];
    let (plugin, text) = message.split_once(": ").unwrap_or(("", message));
    Some(LogLine { time, level, plugin: plugin.to_string(), text: text.trim_end_matches(['\r', '\n']).to_string() })
}

/// `--since`: `30s`, `10m`, `2h` or `1d` as seconds; null for anything else,
/// which goes to logcat as a time of its own (`'11-26 14:00:00.000'`).
pub fn since_seconds(text: &str) -> Option<u64> {
    let text = text.trim();
    let (number, unit) = text.split_at(text.find(|c: char| !c.is_ascii_digit())?);
    let n: u64 = number.parse().ok()?;
    let scale = match unit {
        "s" => 1,
        "m" => 60,
        "h" => 3600,
        "d" => 86_400,
        _ => return None,
    };
    Some(n * scale)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_device_list_reads_every_state() {
        let text = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n\
R58M12ABCDE            device usb:1-1 product:beyond1lteeea model:SM_G973F device:beyond1 transport_id:1\n\
192.168.1.5:37000      device product:CPH2481 model:CPH2481 device:OP594DL1 transport_id:3\n\
emulator-5554          offline transport_id:2\n\
0123456789ABCDEF       no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html]\n\
XYZ                    unauthorized usb:1-2 transport_id:4\n\n";
        let all = devices(text);
        assert_eq!(all.len(), 5);
        assert_eq!(all[0], Device { serial: "R58M12ABCDE".into(), state: "device".into(), model: "SM G973F".into(), usb: true });
        assert_eq!(all[1].label(), "CPH2481 (192.168.1.5:37000)");
        assert!(!all[1].usb);
        assert_eq!(all[2].state, "offline");
        assert_eq!(all[3].state, "no permissions");
        assert_eq!(all[4].state, "unauthorized");
        assert_eq!(all.iter().filter(|d| d.ready()).count(), 2);
        assert!(devices("List of devices attached\n\n").is_empty());
    }

    #[test]
    fn a_reply_is_cut_out_of_the_bundle_whatever_it_holds() {
        let text = "Result: Bundle[{json={\"ok\":true,\"lines\":[\"}] {json= tricky\"]}}]\n";
        let Reply::Json(v) = reply(text) else { panic!("{:?}", reply(text)) };
        assert_eq!(v["ok"], true);
        assert_eq!(v["lines"][0], "}] {json= tricky");
    }

    #[test]
    fn refusals_and_missing_providers_are_told_apart() {
        let off = "Error while accessing provider:com.wasimaster.wmkeyboard.plugindev\njava.lang.SecurityException: wmkb_dev_mode_off: plugin developer mode is off\n\tat android.os.Parcel.createException";
        assert_eq!(reply(off), Reply::DevModeOff);
        assert_eq!(reply("java.lang.SecurityException: wmkb_not_adb: only adb can use this"), Reply::NotAdb);
        let missing = "Error while accessing provider:com.wasimaster.wmkeyboard.plugindev\njava.lang.IllegalStateException: Could not find provider: com.wasimaster.wmkeyboard.plugindev";
        assert_eq!(reply(missing), Reply::NoProvider);
        assert!(matches!(reply("Result: Bundle[mParcelledData.dataSize=120]"), Reply::Other(_)));
        assert_eq!(write_failed(""), None);
        assert_eq!(write_failed("\n"), None);
        assert_eq!(write_failed(off), Some(Reply::DevModeOff));
    }

    #[test]
    fn logcat_lines_read_back() {
        let line = "11-26 14:03:22.123 E/WMPlugin( 1234): com.example.demo: error: main.py:3: boom";
        let l = log_line(line, "WMPlugin").unwrap();
        assert_eq!(l.time, "11-26 14:03:22.123");
        assert_eq!(l.level, 'E');
        assert_eq!(l.plugin, "com.example.demo");
        assert_eq!(l.text, "error: main.py:3: boom");
        let padded = log_line("11-26 14:03:22.123 I/WMPlugin(12345): com.example.demo: count: 3\r", "WMPlugin").unwrap();
        assert_eq!((padded.level, padded.text.as_str()), ('I', "count: 3"));
        assert_eq!(log_line("--------- beginning of main", "WMPlugin"), None);
        assert_eq!(log_line("11-26 14:03:22.123 I/Other( 1): x: y", "WMPlugin"), None);
    }

    #[test]
    fn since_takes_a_duration_or_leaves_the_time_to_logcat() {
        assert_eq!(since_seconds("30s"), Some(30));
        assert_eq!(since_seconds("10m"), Some(600));
        assert_eq!(since_seconds("2h"), Some(7200));
        assert_eq!(since_seconds("1d"), Some(86_400));
        assert_eq!(since_seconds("11-26 14:00:00.000"), None);
        assert_eq!(since_seconds("10"), None);
        assert_eq!(since_seconds("m"), None);
    }

    #[test]
    fn small_answers() {
        assert_eq!(sdk("34\r\n"), Some(34));
        assert_eq!(sdk(""), None);
        assert!(has_package("package:com.wasimaster.wmkeyboard\n", "com.wasimaster.wmkeyboard"));
        assert!(!has_package("package:com.wasimaster.wmkeyboard.debug\n", "com.wasimaster.wmkeyboard"));
    }
}
