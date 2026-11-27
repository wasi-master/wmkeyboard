//! `wmpy pip` without the network: `check` on a wheel and a folder made on
//! the spot, and `list`, `why` and `remove` on a hand-written wmpy.lock.

use std::io::Write;
use std::path::PathBuf;
use std::process::Command;

fn temp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-pip-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn cli(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(args).env("NO_COLOR", "1").output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

fn wheel(path: &std::path::Path, files: &[(&str, &str)]) {
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, text) in files {
        zip.start_file(*name, opts).unwrap();
        zip.write_all(text.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
}

#[test]
fn check_reads_a_wheel_and_names_what_will_not_run() {
    let dir = temp("wheel");
    let whl = dir.join("tidy-1.0-py3-none-any.whl");
    wheel(
        &whl,
        &[
            ("tidy/__init__.py", "from .core import shout\n"),
            ("tidy/core.py", "import re\nimport smtplib\n\ndef shout(t):\n    return re.sub('a', 'b', t)\n"),
            ("tidy/data/words.txt", "a b c"),
            ("tidy-1.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: tidy\nVersion: 1.0\nLicense-Expression: MIT\nRequires-Dist: six\n"),
            ("tidy-1.0.dist-info/licenses/LICENSE", "MIT"),
        ],
    );
    let (code, out, err) = cli(&["pip", "check", "--json", whl.to_str().unwrap()]);
    assert_eq!(code, 1, "{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    let p = &v["packages"][0];
    assert_eq!((p["name"].as_str(), p["version"].as_str(), p["verdict"].as_str(), p["license"].as_str()), (Some("tidy"), Some("1.0"), Some("incompatible"), Some("MIT")));
    let problem = &p["problems"][0];
    assert_eq!((problem["code"].as_str(), problem["file"].as_str(), problem["line"].as_u64()), (Some("sandbox-import"), Some("tidy/core.py"), Some(2)));
    for k in ["severity", "message", "col", "endCol", "traceback", "notes", "help"] {
        assert!(problem.get(k).is_some(), "{k} missing: {problem}");
    }
    assert!(p["notes"].as_array().unwrap().iter().any(|n| n.as_str().unwrap().contains("1 data files were left out")), "{p}");
    // The human form: a code frame and a table.
    let (code, out, err) = cli(&["pip", "check", whl.to_str().unwrap()]);
    assert_eq!(code, 1);
    assert!(err.contains("error[sandbox-import]") && err.contains("--> tidy/core.py:2:8"), "{err}");
    assert!(out.contains("tidy") && out.contains("incompatible"), "{out}");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn check_reads_a_folder_of_python() {
    let dir = temp("folder");
    let pkg = dir.join("calc");
    std::fs::create_dir_all(&pkg).unwrap();
    std::fs::write(pkg.join("__init__.py"), "from calc.ops import add\n").unwrap();
    std::fs::write(pkg.join("ops.py"), "import math\n\ndef add(a, b):\n    return math.fsum([a, b])\n").unwrap();
    let (code, out, err) = cli(&["pip", "check", "--json", pkg.to_str().unwrap()]);
    assert_eq!(code, 0, "{out}{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["packages"][0]["verdict"], "ok", "{out}");
    assert_eq!(v["packages"][0]["roots"][0], "calc");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn list_why_and_remove_follow_the_lock() {
    let dir = temp("lock");
    std::fs::write(dir.join("plugin.json"), r#"{"id": "com.example.lock", "version": "1.0", "apiVersion": 5}"#).unwrap();
    std::fs::write(dir.join("main.py"), "import dateutil\n").unwrap();
    std::fs::create_dir_all(dir.join("dateutil")).unwrap();
    std::fs::create_dir_all(dir.join("licenses/six")).unwrap();
    std::fs::write(dir.join("dateutil/__init__.py"), "import six\n").unwrap();
    std::fs::write(dir.join("six.py"), "X = 1\n").unwrap();
    std::fs::write(dir.join("licenses/six/LICENSE"), "MIT").unwrap();
    std::fs::write(
        dir.join("wmpy.lock"),
        r#"{"version": 1, "packages": {
  "python-dateutil": {"version": "2.9.0", "roots": ["dateutil"], "files": ["dateutil/__init__.py"], "source": "", "sha256": "", "license": "BSD", "requires": ["six"], "verdict": "ok", "problems": 0},
  "six": {"version": "1.17.0", "roots": ["six"], "files": ["six.py", "licenses/six/LICENSE"], "source": "", "sha256": "", "license": null, "requires": [], "verdict": "warnings", "problems": 2}
}}"#,
    )
    .unwrap();
    let d = dir.to_str().unwrap();
    let (code, out, _) = cli(&["pip", "list", "--dir", d]);
    assert_eq!(code, 0);
    assert!(out.contains("python-dateutil") && out.contains("six") && out.contains("none found"), "{out}");
    let (_, out, _) = cli(&["pip", "why", "six", "--dir", d]);
    assert!(out.contains("needed by python-dateutil 2.9.0") && out.contains("installed directly"), "{out}");
    // six is needed, so it stays without --force.
    let (code, _, err) = cli(&["pip", "remove", "six", "--dir", d]);
    assert_eq!(code, 1, "{err}");
    assert!(dir.join("six.py").exists());
    let (code, out, err) = cli(&["pip", "remove", "python-dateutil", "--dir", d]);
    assert_eq!(code, 0, "{err}");
    assert!(out.contains("nothing here needs six any more"), "{out}");
    assert!(!dir.join("dateutil").exists(), "the emptied folder goes too");
    let (code, _, _) = cli(&["pip", "remove", "six", "--dir", d]);
    assert_eq!(code, 0);
    assert!(!dir.join("six.py").exists() && !dir.join("licenses").exists() && !dir.join("wmpy.lock").exists());
    assert!(dir.join("main.py").exists(), "the plugin's own files stay");
    let _ = std::fs::remove_dir_all(&dir);
}
