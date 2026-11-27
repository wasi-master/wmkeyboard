//! The libraries the keyboard gives plugins under their usual names
//! (requests, httpx, urllib.request): a vendored package that imports them
//! is not missing anything.

use std::collections::BTreeMap;

use wm_python_pip::{analyse, built_in_projects, is_built_in, AnalyseOptions, Report, Verdict};

fn run(list: &[(&str, &str)]) -> Report {
    let f: BTreeMap<String, String> = list.iter().map(|(p, s)| (p.to_string(), s.to_string())).collect();
    let opts = AnalyseOptions { package: "test".into(), available: Vec::new(), ..Default::default() };
    std::thread::Builder::new().stack_size(64 << 20).spawn(move || analyse(&f, &opts)).unwrap().join().unwrap()
}

#[test]
fn the_built_in_projects_come_from_the_prelude() {
    assert_eq!(built_in_projects(), vec!["httpx", "requests"]);
    assert!(is_built_in("requests") && is_built_in("Requests") && is_built_in("httpx"));
    assert!(!is_built_in("urllib3") && !is_built_in("urllib") && !is_built_in("requests-toolbelt"));
}

#[test]
fn a_package_that_imports_requests_is_ok() {
    let r = run(&[
        (
            "weather/__init__.py",
            "import requests\nfrom requests.exceptions import HTTPError\nimport httpx\nfrom urllib.request import urlopen\nimport urllib.error\n\ndef now(city):\n    return requests.get('https://wttr.in/' + city).text\n",
        ),
    ]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", r.problems().collect::<Vec<_>>());
    assert!(r.imports.missing.is_empty(), "{:?}", r.imports.missing);
    for m in ["requests", "requests.exceptions", "httpx", "urllib.request", "urllib.error"] {
        assert!(r.imports.wm.contains(m), "{m}: {:?}", r.imports.wm);
    }
}

#[test]
fn a_part_of_requests_the_keyboard_lacks_is_named() {
    let r = run(&[("old.py", "from requests.packages.urllib3.util.retry import Retry\n")]);
    let p = r.problems().find(|p| p.code == "missing-import").expect("flagged");
    assert!(p.message.contains("the keyboard's built-in requests has no module"), "{}", p.message);
    assert!(p.help.is_none(), "{:?}", p.help);
}
