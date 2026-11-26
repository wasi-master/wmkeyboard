//! `wmpy device`, `push`, `dev` and `logs` against a fake adb: a bash
//! script that writes down every call and answers like a phone running WM
//! Keyboard with plugin developer mode on or off. No real adb runs.

#![cfg(unix)]

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use base64::Engine;

const FAKE: &str = r#"#!/bin/bash
# A pretend adb for wmpy's tests. State lives in $FAKE_ADB_DIR:
#   devices.txt  what `adb devices -l` prints
#   sdk.txt      ro.build.version.sdk (34 when missing)
#   installed    present when WM Keyboard is installed
#   devmode      present when plugin developer mode is on
#   result.txt   the reply to `--method result` (an install by default)
#   logcat.txt   what `adb logcat` prints
#   slow         present to make `devices` hang
# It writes calls.log (one call a line), upload-N.bin (each `content write`)
# and parts.txt (each base64 piece, one a line).
dir="$FAKE_ADB_DIR"
printf '%s\n' "$*" >> "$dir/calls.log"
if [ "$1" = "-s" ]; then shift 2; fi
case "$1" in
  devices)
    [ -f "$dir/slow" ] && sleep 5
    cat "$dir/devices.txt"
    exit 0;;
  logcat)
    [ -f "$dir/logcat.txt" ] && cat "$dir/logcat.txt"
    exit 0;;
  shell)
    shift
    [ "$1" = "-T" ] && shift
    case "$1" in
      getprop) if [ -f "$dir/sdk.txt" ]; then cat "$dir/sdk.txt"; else echo 34; fi; exit 0;;
      pm) [ -f "$dir/installed" ] && echo "package:com.wasimaster.wmkeyboard"; exit 0;;
      date) echo 1764150000; exit 0;;
      content)
        op="$2"
        if [ ! -f "$dir/devmode" ]; then
          cat > /dev/null
          echo "Error while accessing provider:com.wasimaster.wmkeyboard.plugindev" >&2
          echo "java.lang.SecurityException: wmkb_dev_mode_off: plugin developer mode is off" >&2
          echo "	at android.os.Parcel.createExceptionOrNull(Parcel.java:3057)" >&2
          exit 1
        fi
        method=""; arg=""
        while [ $# -gt 0 ]; do
          case "$1" in --method) method="$2"; shift;; --arg) arg="$2"; shift;; esac
          shift
        done
        case "$op" in
          write)
            n=$(ls "$dir" | grep -c '^upload-')
            cat > "$dir/upload-$((n+1)).bin"
            exit 0;;
          call)
            case "$method" in
              status) echo 'Result: Bundle[{json={"ok":true,"protocol":1,"developerMode":true,"appVersion":"0.6.6","appVersionCode":606,"pluginApi":5,"pluginsOn":true,"allowRisky":false,"plugins":[{"id":"com.example.demo","name":"Demo","version":"1.0","enabled":true,"riskyOff":[]}]}}]';;
              part) echo "${arg#*:}" >> "$dir/parts.txt"; echo 'Result: Bundle[{json={"ok":true,"bytes":1}}]';;
              result)
                if [ -f "$dir/result.txt" ]; then cat "$dir/result.txt"
                else echo 'Result: Bundle[{json={"ok":true,"protocol":1,"action":"installed","id":"com.example.demo","name":"Demo","version":"1.0","enabled":true,"reloaded":false,"allowRisky":false,"riskyOff":["network"],"newSinceUpdate":[]}}]'
                fi;;
              log) echo 'Result: Bundle[{json={"ok":true,"id":"com.example.demo","lines":["saved one","error: saved two"]}}]';;
              *) echo 'Result: Bundle[{json={"ok":false,"error":"unknown_method","message":"no"}}]';;
            esac
            exit 0;;
        esac;;
    esac;;
esac
echo "fake adb: no answer for: $*" >&2
exit 1
"#;

const ONE_PHONE: &str = "List of devices attached\nFAKE1                  device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1\nFAKE2                  unauthorized usb:1-2 transport_id:2\n\n";

const TWO_PHONES: &str = "List of devices attached\nFAKE1    device usb:1-1 model:Pixel_8\n192.168.1.5:37000    device model:CPH2481\n\n";

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.demo",
  "name": "Demo",
  "pluginVersion": "1.0",
  "author": "Me",
  "apiVersion": 5,
  "permissions": ["storage"]
}
"#;

const MAIN: &str = "from wm import plugin, ui\n\nstate = plugin.state(count=0)\n\n\ndef render():\n    ui.label('count ' + str(state.count))\n";

struct Phone {
    root: PathBuf,
    adb: PathBuf,
    state: PathBuf,
}

impl Phone {
    /// A fresh fake phone: [devices] listed, WM Keyboard installed, developer mode [on].
    fn new(name: &str, devices: &str, on: bool) -> Phone {
        let root = std::env::temp_dir().join(format!("wmpy-device-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let state = root.join("phone");
        std::fs::create_dir_all(&state).unwrap();
        let adb = root.join("adb");
        std::fs::write(&adb, FAKE).unwrap();
        std::fs::set_permissions(&adb, std::fs::Permissions::from_mode(0o755)).unwrap();
        std::fs::write(state.join("devices.txt"), devices).unwrap();
        std::fs::write(state.join("installed"), "").unwrap();
        if on {
            std::fs::write(state.join("devmode"), "").unwrap();
        }
        Phone { root, adb, state }
    }

    fn set(&self, file: &str, text: &str) {
        std::fs::write(self.state.join(file), text).unwrap();
    }

    fn plugin(&self, main: &str) -> PathBuf {
        let dir = self.root.join("demo");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("plugin.json"), MANIFEST).unwrap();
        std::fs::write(dir.join("main.py"), main).unwrap();
        dir
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut c = Command::new(env!("CARGO_BIN_EXE_wmpy"));
        c.args(args).env("WMPY_ADB", &self.adb).env("FAKE_ADB_DIR", &self.state).env("NO_COLOR", "1").env_remove("ANDROID_SERIAL");
        c
    }

    fn run(&self, args: &[&str]) -> (i32, String, String) {
        let out = self.command(args).output().unwrap();
        (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
    }

    fn calls(&self) -> Vec<String> {
        std::fs::read_to_string(self.state.join("calls.log")).unwrap_or_default().lines().map(String::from).collect()
    }

    fn upload(&self, n: u32) -> Option<Vec<u8>> {
        std::fs::read(self.state.join(format!("upload-{n}.bin"))).ok()
    }
}

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

/// What `wmpy pack` makes of [dir]: the bytes a push must send.
fn packed(dir: &Path) -> Vec<u8> {
    let out = dir.parent().unwrap().join("packed.wmplugin");
    let status = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["pack", s(dir), "-o", s(&out)]).output().unwrap();
    assert!(status.status.success(), "{}", String::from_utf8_lossy(&status.stderr));
    std::fs::read(out).unwrap()
}

#[test]
fn device_lists_phones_the_keyboard_and_developer_mode() {
    let phone = Phone::new("list", ONE_PHONE, true);
    let (code, out, err) = phone.run(&["device"]);
    assert_eq!(code, 0, "{err}");
    assert!(out.contains("Pixel 8 (FAKE1), Android API 34"), "{out}");
    assert!(out.contains("WM Keyboard 0.6.6 is installed"), "{out}");
    assert!(out.contains("plugin developer mode: on"), "{out}");
    assert!(out.contains("com.example.demo 1.0"), "{out}");
    assert!(out.contains("FAKE2") && out.contains("press Allow"), "{out}");
    // Only the ready phone is asked anything.
    assert!(phone.calls().iter().all(|c| !c.starts_with("-s FAKE2")), "{:?}", phone.calls());
    assert!(phone.calls().contains(&"-s FAKE1 shell content call --uri content://com.wasimaster.wmkeyboard.plugindev --method status".to_string()), "{:?}", phone.calls());

    let (code, out, _) = phone.run(&["device", "--json"]);
    assert_eq!(code, 0);
    let all: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(all[0]["serial"], "FAKE1");
    assert_eq!(all[0]["developerMode"], true);
    assert_eq!(all[0]["sdk"], 34);
    assert_eq!(all[1]["state"], "unauthorized");
}

#[test]
fn device_says_how_to_turn_developer_mode_on() {
    let phone = Phone::new("off", ONE_PHONE, false);
    let (code, out, _) = phone.run(&["device", "-s", "FAKE1"]);
    assert_eq!(code, 0);
    assert!(out.contains("plugin developer mode: off"), "{out}");
    assert!(out.contains("Tools / Plugins / Manage plugins") && out.contains("Plugin developer mode"), "{out}");
    std::fs::remove_file(phone.state.join("installed")).unwrap();
    let (_, out, _) = phone.run(&["device"]);
    assert!(out.contains("WM Keyboard is not installed"), "{out}");
}

#[test]
fn push_streams_the_packed_plugin_and_prints_the_reply() {
    let phone = Phone::new("push", ONE_PHONE, true);
    let dir = phone.plugin(MAIN);
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 0, "{err}");
    assert!(err.contains("installed Demo 1.0 on Pixel 8 (FAKE1)"), "{err}");
    assert!(err.contains("risky permissions left off: network"), "{err}");
    assert!(err.contains("Allow risky permissions"), "{err}");
    assert_eq!(phone.upload(1).unwrap(), packed(&dir), "the phone got other bytes than wmpy pack makes");
    let calls = phone.calls();
    let write = calls.iter().find(|c| c.contains("content write")).expect("no content write");
    let token = write.rsplit('/').next().unwrap();
    assert_eq!(token.len(), 16, "{write}");
    assert_eq!(write, &format!("-s FAKE1 shell -T content write --uri content://com.wasimaster.wmkeyboard.plugindev/install/{token}"));
    assert!(calls.iter().any(|c| c.ends_with(&format!("--method result --arg {token}"))), "{calls:?}");
    assert!(!dir.read_dir().unwrap().flatten().any(|e| e.path().extension().is_some_and(|x| x == "wmplugin")), "push left an archive in the folder");

    let (code, out, _) = phone.run(&["push", s(&dir), "--json"]);
    assert_eq!(code, 0);
    let reply: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(reply["riskyOff"][0], "network");
}

#[test]
fn push_on_android_8_sends_base64_pieces() {
    let phone = Phone::new("pieces", ONE_PHONE, true);
    phone.set("sdk.txt", "26\n");
    // Big enough for several pieces even after compression.
    let noise: String = (0..6000u32).map(|i| format!("# {:08x}\n", i.wrapping_mul(2_654_435_761))).collect();
    let dir = phone.plugin(&format!("{MAIN}{noise}"));
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 0, "{err}");
    assert!(phone.upload(1).is_none(), "Android 8 has no content write");
    let parts = std::fs::read_to_string(phone.state.join("parts.txt")).unwrap();
    assert!(parts.lines().count() > 3, "{} pieces", parts.lines().count());
    let joined: String = parts.lines().collect();
    let bytes = base64::engine::general_purpose::STANDARD.decode(joined).unwrap();
    assert_eq!(bytes, packed(&dir));
}

#[test]
fn push_refuses_a_plugin_that_does_not_check() {
    let phone = Phone::new("broken", ONE_PHONE, true);
    let dir = phone.plugin("def render(:\n    pass\n");
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 1, "{err}");
    assert!(err.contains("not pushed"), "{err}");
    assert!(phone.calls().iter().all(|c| !c.contains("content")), "{:?}", phone.calls());
}

#[test]
fn push_with_developer_mode_off_says_how_to_turn_it_on() {
    let phone = Phone::new("pushoff", ONE_PHONE, false);
    let dir = phone.plugin(MAIN);
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("plugin developer mode is off on Pixel 8 (FAKE1)"), "{err}");
    assert!(err.contains("switch on Plugin developer mode"), "{err}");
}

#[test]
fn a_rejected_plugin_fails_with_the_keyboards_reason() {
    let phone = Phone::new("rejected", ONE_PHONE, true);
    phone.set("result.txt", "Result: Bundle[{json={\"ok\":false,\"error\":\"rejected\",\"message\":\"Demo asks for a permission this keyboard does not know\"}}]\n");
    let dir = phone.plugin(MAIN);
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 1, "{err}");
    assert!(err.contains("[rejected]") && err.contains("does not know"), "{err}");
}

#[test]
fn two_phones_need_a_serial() {
    let phone = Phone::new("two", TWO_PHONES, true);
    let dir = phone.plugin(MAIN);
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("2 phones are connected: say which with -s"), "{err}");
    assert!(err.contains("FAKE1") && err.contains("192.168.1.5:37000"), "{err}");
    let (code, _, err) = phone.run(&["push", s(&dir), "-s", "192.168.1.5:37000"]);
    assert_eq!(code, 0, "{err}");
    assert!(phone.calls().iter().any(|c| c.starts_with("-s 192.168.1.5:37000 shell -T content write")));
    let (code, _, err) = phone.run(&["push", s(&dir), "-s", "NOPE"]);
    assert_eq!(code, 2);
    assert!(err.contains("no phone NOPE is connected"), "{err}");
}

#[test]
fn no_phone_and_no_adb_are_explained() {
    let phone = Phone::new("none", "List of devices attached\n\n", true);
    let dir = phone.plugin(MAIN);
    let (code, _, err) = phone.run(&["push", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("no phone is connected over adb") && err.contains("wireless debugging"), "{err}");

    let empty = phone.root.join("empty-home");
    std::fs::create_dir_all(&empty).unwrap();
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy"))
        .args(["device"])
        .env_remove("WMPY_ADB")
        .env_remove("ANDROID_HOME")
        .env_remove("ANDROID_SDK_ROOT")
        .env_remove("LOCALAPPDATA")
        .env("PATH", &empty)
        .env("HOME", &empty)
        .env("NO_COLOR", "1")
        .output()
        .unwrap();
    let err = String::from_utf8_lossy(&out.stderr);
    assert_eq!(out.status.code(), Some(2));
    assert!(err.contains("https://developer.android.com/tools/releases/platform-tools"), "{err}");

    let out = phone.command(&["device"]).env("WMPY_ADB", phone.root.join("missing")).output().unwrap();
    assert!(String::from_utf8_lossy(&out.stderr).contains("WMPY_ADB names"));
}

#[test]
fn a_hung_adb_is_given_up_on() {
    let phone = Phone::new("slow", ONE_PHONE, true);
    phone.set("slow", "");
    let started = Instant::now();
    let mut child = phone.command(&["device"]).env("WMPY_ADB_TIMEOUT", "1").stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    // Timed by the exit, not by the pipes closing: on macOS a process another
    // test starts at the same moment can inherit them for a while.
    let status = loop {
        if let Some(status) = child.try_wait().unwrap() {
            break status;
        }
        assert!(started.elapsed() < Duration::from_secs(4), "wmpy waited on the hung adb");
        std::thread::sleep(Duration::from_millis(20));
    };
    assert!(started.elapsed() < Duration::from_secs(4), "{:?}", started.elapsed());
    assert_eq!(status.code(), Some(2));
    let out = child.wait_with_output().unwrap();
    assert!(String::from_utf8_lossy(&out.stderr).contains("took longer than 1 seconds"), "{}", String::from_utf8_lossy(&out.stderr));
}

#[test]
fn logs_show_one_plugin_and_mark_errors() {
    let phone = Phone::new("logs", ONE_PHONE, true);
    phone.set(
        "logcat.txt",
        "--------- beginning of main\n\
11-26 14:03:22.123 I/WMPlugin( 4242): com.example.demo: count is 3\n\
11-26 14:03:22.200 I/WMPlugin( 4242): com.example.other: not mine\n\
11-26 14:03:23.001 E/WMPlugin( 4242): com.example.demo: error: main.py:3: boom\n",
    );
    let (code, out, err) = phone.run(&["logs", "com.example.demo"]);
    assert_eq!(code, 0, "{err}");
    assert_eq!(out, "14:03:22.123 count is 3\n14:03:23.001 error: main.py:3: boom\n");
    // No plugin named: every plugin's lines, each with its id.
    let (_, out, _) = phone.run(&["logs", "-s", "FAKE1"]);
    assert!(out.contains("com.example.other: not mine"), "{out}");
    let calls = phone.calls();
    assert!(calls.iter().any(|c| c == "-s FAKE1 logcat -v time -s WMPlugin"), "{calls:?}");
    assert!(calls.iter().any(|c| c == "-s FAKE1 logcat -v time -d -s WMPlugin"), "{calls:?}");
}

#[test]
fn logs_fall_back_to_the_saved_log_and_take_since() {
    let phone = Phone::new("saved", ONE_PHONE, true);
    let dir = phone.plugin(MAIN);
    let (code, out, err) = phone.run(&["logs", s(&dir), "--since", "10m"]);
    assert_eq!(code, 0, "{err}");
    assert_eq!(out, "saved one\nerror: saved two\n");
    assert!(err.contains("its saved log"), "{err}");
    let calls = phone.calls();
    // 1764150000 is the fake phone's clock; ten minutes before it.
    assert!(calls.iter().any(|c| c == "-s FAKE1 logcat -v time -t 1764149400.000 -s WMPlugin"), "{calls:?}");
    assert!(calls.iter().any(|c| c == "-s FAKE1 logcat -v time -T 1764149400.000 -s WMPlugin"), "{calls:?}");
    assert!(calls.iter().any(|c| c.ends_with("--method log --arg com.example.demo")), "{calls:?}");
    let (code, _, err) = phone.run(&["logs", "Not An Id"]);
    assert_eq!(code, 2);
    assert!(err.contains("neither a plugin folder nor a plugin id"), "{err}");
}

#[test]
fn dev_pushes_again_when_a_file_is_saved() {
    let phone = Phone::new("dev", ONE_PHONE, true);
    let dir = phone.plugin(MAIN);
    let mut child = phone.command(&["dev", s(&dir), "--exit-after", "2"]).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    let wait_for = |what: &dyn Fn() -> bool| {
        let start = Instant::now();
        while !what() {
            assert!(start.elapsed() < Duration::from_secs(30), "timed out");
            std::thread::sleep(Duration::from_millis(50));
        }
    };
    wait_for(&|| phone.upload(1).is_some());
    // A second after the first push, so the file's time moves on every file system.
    std::thread::sleep(Duration::from_millis(1100));
    std::fs::write(dir.join("main.py"), format!("{MAIN}\n# changed\n")).unwrap();
    wait_for(&|| phone.upload(2).is_some());
    let start = Instant::now();
    let status = loop {
        if let Some(status) = child.try_wait().unwrap() {
            break status;
        }
        assert!(start.elapsed() < Duration::from_secs(30), "wmpy dev did not stop after two pushes");
        std::thread::sleep(Duration::from_millis(50));
    };
    let out = child.wait_with_output().unwrap();
    let err = String::from_utf8_lossy(&out.stderr);
    assert!(status.success(), "{err}");
    assert!(err.contains("main.py changed: pushing again"), "{err}");
    assert_ne!(phone.upload(1), phone.upload(2));
    assert_eq!(phone.upload(2).unwrap(), packed(&dir));
    // The log was asked for from the phone's own now.
    assert!(phone.calls().iter().any(|c| c == "-s FAKE1 logcat -v time -T 1764150000.000 -s WMPlugin"), "{:?}", phone.calls());
}
