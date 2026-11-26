//! Messages name the package `wm`, as plugins import it, never the model's
//! internal `wm5` root.

use wm_python_tooling::{Document, Host};

#[test]
fn a_member_typo_names_wm_not_wm5() {
    let src = "from wm import ui\n\ndef render():\n    ui.lable(\"x\")\n";
    let host = Host { api: Some(5), ..Host::default() };
    let doc = Document::new(src);
    let found = wm_python_tooling::diagnostics::diagnose(&doc, &host).diagnostics;
    let typo = found.iter().find(|d| d.code.name() == "member_typo").expect("a member_typo for ui.lable");
    assert_eq!(typo.args, vec!["wm.ui.lable".to_string(), "wm.ui.label".to_string()]);
    assert!(found.iter().all(|d| d.args.iter().all(|a| !a.starts_with("wm5"))), "{found:?}");
}
