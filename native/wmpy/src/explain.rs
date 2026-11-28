//! `wmpy explain [code]`: what a problem code from `wmpy check`, the
//! editors, `wmpy pack` or `wmpy pip check` means, and what to do about it.
//! Without a code it lists them all.
//!
//! The codes come from three places: the language service's checks of the
//! Python ([wm_python_tooling::diagnostics::Code], with their messages),
//! plugin.json and the archive ([MANIFEST]), and the analysis of packages
//! from PyPI ([PACKAGES]). Each says how to silence it where that is
//! possible.

use serde_json::json;
use wm_python_tooling::diagnostics::{Code, Severity};
use wm_python_tooling::manifest::DOCS;

use crate::output;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// A code, such as name_typo or unknown_permission; every code when left out
    pub code: Option<String>,
    /// Print it as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

/// What each family of checks is about, and where the docs say more.
fn family(code: Code) -> (&'static str, &'static str) {
    use Code::*;
    match code {
        UnknownCharacter | UnclosedString | UnclosedTripleString | BadNumber | UnclosedBracket | StrayCloser | WrongCloser | UnexpectedIndent
        | InconsistentDedent | EmptyBlock | MixedIndentation | SyntaxExpected | SyntaxAtEnd | Unsupported | WillNotRun => (
            "Python the keyboard's interpreter cannot read. The keyboard refuses to load a file with one of these, so no comment silences them.",
            "/plugins/wm-package/#the-python-you-get",
        ),
        UndefinedName | NameTypo | ModuleNotImported | RemovedBuiltin | MissingBuiltin | ImportNotAvailable | UnusedLocal | UnusedParameter | AccidentalGlobal
        | ReplacesApi | ShadowsBuiltin => (
            "Names: what a name means where it is used. An undefined name raises NameError when that line runs; it counts as an error unless the line sits in a try that catches NameError, or the module makes names at run time (globals(), exec, setattr, __getattr__).",
            "/plugins/help/#error-messages",
        ),
        UnknownMember | MemberTypo | UseInstead | NeverInApi | StorageNotDeclared | RemindersNotDeclared | SelectionNotDeclared | PanelOnly => (
            "The wm package: names it does not have, and what needs a permission in plugin.json. A call that needs a permission the plugin did not ask for fails on the phone.",
            "/plugins/api-reference/",
        ),
        MissingRender | MissingTransform | RenderParameters | RenderReturnsNothing | OnEventSignature | TransformSignature | UnknownEventType | EventTypeTypo
        | UnknownEventId | UnknownAction | ActionTypo => ("The functions the keyboard calls, and the events and actions it sends them.", "/plugins/wm-package/"),
        UnknownUiArgument | UiArgumentTypo | UiMissingArgument | TooManyUiArguments | UnknownStyle | StyleTypo | DuplicateId | TooManyTabs => {
            ("Widgets: the arguments and styles each ui function takes.", "/plugins/guides/drawing/")
        }
        PermissionMissing | HostMissing => (
            "What plugin.json must list for a call to work: a permission, or the website a request goes to. Without it the call fails on the phone, so it counts as an error.",
            "/plugins/permissions/",
        ),
        UnknownProviderTool | ProviderToolTypo | ProviderNotDeclared | ProviderUnanswered | ActionUnanswered | UnknownPreview | PreviewTypo | PreviewUnanswered
        | ThumbnailNotDeclared | ThumbnailUnanswered | KeywordNotDeclared | UnknownSetting | SettingTypo | UnknownArgument | ArgumentTypo | UnknownValue | ValueTypo
        | WmNotImported => ("plugin.json and the code must agree: what one declares, the other answers, and the other way round.", "/plugins/wm-package/#the-manifest"),
        LoopNeverEnds | Unreachable => ("Flow: code that never runs, or never stops. The keyboard stops a plugin that works too long.", "/plugins/guides/testing/#reading-an-error"),
    }
}

/// Codes with a quick fix in the editors and `wmpy check --fix`, and what it does.
fn quick_fix(code: Code) -> Option<&'static str> {
    use Code::*;
    Some(match code {
        NameTypo | UiArgumentTypo | ArgumentTypo | MemberTypo | EventTypeTypo | StyleTypo | ActionTypo | ProviderToolTypo | ValueTypo | SettingTypo => {
            "changes it to the name it suggests"
        }
        ModuleNotImported => "adds the import at the top",
        WmNotImported => "adds it to the `from wm import` line",
        StorageNotDeclared | RemindersNotDeclared | SelectionNotDeclared => "adds the permission to plugin.json",
        PermissionMissing => "adds the permission to plugin.json (a risky one only with --fix-risky)",
        HostMissing => "adds the website to plugin.json's hosts (only with --fix-risky: users see the list before they install)",
        ProviderNotDeclared => "declares the provider in plugin.json",
        UnknownAction => "lists the action in plugin.json",
        KeywordNotDeclared => "sets plugin.json's tool.keyword",
        UnknownSetting => "adds the setting to plugin.json",
        ProviderUnanswered | PreviewUnanswered | ThumbnailUnanswered | ActionUnanswered => "adds a function that answers it, to fill in",
        _ => return None,
    })
}

/// plugin.json and archive problems: `(code, what it means)`.
const MANIFEST: &[(&str, &str)] = &[
    ("bad_json", "plugin.json is not JSON the app can read. The message names the line."),
    ("not_an_object", "plugin.json is JSON but not an object ({...})."),
    ("not_a_plugin", "plugin.json's format is not \"wmkeyboard-plugin\", so the app reads it as something else."),
    ("bad_id", "The id is not 3 to 64 characters of a-z 0-9 . _ -, such as com.yourname.weather."),
    ("id_case", "The id has capital letters: the app keeps ids in lower case."),
    ("no_name", "plugin.json has no name, the plugin's title in the keyboard."),
    ("no_version", "plugin.json has no pluginVersion, which updates are compared by."),
    ("wrong_type", "A field has the wrong kind of value: a list where text is wanted, or the other way round."),
    ("old_api", "apiVersion is older than the app runs; the plugin loads in the old way, or not at all."),
    ("newer_api", "apiVersion is newer than this wmpy knows: update wmpy, or the app may refuse it."),
    ("needs_api_5", "This field or feature needs \"apiVersion\": 5."),
    ("permission_needs_api_5", "This permission exists only for API 5 plugins."),
    ("unknown_permission", "The app has no permission by this name. The message suggests the nearest one."),
    ("duplicate_permission", "A permission is listed twice."),
    ("too_many_permissions", "More permissions than the app takes."),
    ("network_without_hosts", "The network permission without any hosts: every request would be refused. List the websites the plugin talks to in \"hosts\"."),
    ("network_local_unused", "network.local is asked for, but no setting lets the user type a local server."),
    ("bad_host", "A host is not a website name such as api.example.com (no scheme, path or port)."),
    ("host_written_long", "A host is written with https:// or a path: plugin.json wants the name alone."),
    ("too_many_hosts", "More hosts than the app takes."),
    ("entry_not_python", "The entry is not a .py file."),
    ("missing_script", "There is no file by the entry's name beside plugin.json."),
    ("empty_script", "The entry script is empty."),
    ("script_too_large", "A .py file is over the size the keyboard loads."),
    ("bad_encoding", "A text file is not UTF-8."),
    ("bad_module", "A module's path is one the keyboard cannot import (a name Python cannot use, or too deep)."),
    ("shadowed_module", "A module of the plugin has the name of a standard module, which it hides from every import."),
    ("lua", "This is a Lua plugin's manifest: Lua plugins were replaced by Python ones."),
    ("too_many_files", "The archive would have more files than the app takes."),
    ("archive_too_large", "The archive would be bigger than the app takes."),
    ("asset_too_large", "A file under assets/ is bigger than the app takes."),
    ("bad_asset", "A file under assets/ has a name or kind the app does not take."),
    ("bad_locale", "A file under locales/ is not a translation file: its name is not a language tag, or it is not an object of strings."),
    ("cut_short", "Text in plugin.json is longer than the app shows, and is cut."),
    ("bad_icon", "The icon is not one the app has, or not an image it can show."),
    ("missing_icon", "plugin.json names an icon file that is not in the folder."),
    ("bad_color", "A colour is not #RRGGBB."),
    ("tool_height", "tool.height is outside what the panel can be."),
    ("bad_keyword", "tool.keyword is not one word of letters and digits."),
    ("nothing_to_show", "\"panel\": false with no actions, providers or previews: nothing would ever run."),
    ("action_no_name", "An action has no name to show on the bar over selected text."),
    ("action_input", "An action's input is not one the app knows."),
    ("bad_action_id", "An action id is not letters, digits, _ and -."),
    ("duplicate_action_id", "Two actions have one id."),
    ("too_many_actions", "More actions than the app shows."),
    ("actions_need_selection", "Actions run on selected text, which needs the selection permission."),
    ("selection_without_actions", "The selection permission without any actions to use it."),
    ("bad_provider_id", "A provider id is not letters, digits, _ and -."),
    ("duplicate_provider", "Two providers of one list have one id."),
    ("too_many_providers", "More providers than the app takes."),
    ("unknown_provider_tool", "A provider names a list the keyboard does not have."),
    ("provider_images", "This provider list does not take pictures."),
    ("previews_need_permission", "Previews need the previews permission."),
    ("previews_permission_unused", "The previews permission without any previews."),
    ("too_many_previews", "More previews than the app takes."),
    ("max_bytes", "A preview's maxBytes is outside what the file manager hands a plugin."),
    ("unknown_language", "A syntax entry names a language the editor does not have."),
    ("missing_grammar", "A syntax entry names a grammar file that is not in the folder."),
    ("too_many_grammars", "More grammars than the app takes."),
    ("too_many_settings", "More settings than the settings page shows."),
    ("setting_default", "A setting's default is not one of its options, or not of its type."),
    ("setting_default_not_text", "A text setting's default is not text."),
    ("secret_default", "A secret setting has a default: secrets have none, as anyone could read it."),
];

/// `wmpy pip` analysis problems: `(code, what it means)`.
const PACKAGES: &[(&str, &str)] = &[
    ("syntax", "Python the keyboard's interpreter cannot read: the package will not import."),
    ("missing-import", "The package imports a module that is neither in the keyboard nor installed."),
    ("sandbox-import", "The package imports a module plugins may not use (the file system, processes, sockets)."),
    ("sandbox-builtin", "The package calls a builtin plugins do not have, such as open()."),
    ("missing-builtin", "The package uses a builtin the interpreter does not have yet."),
    ("missing-name", "The package imports a name that its module does not have in the keyboard."),
    ("missing-attribute", "The package uses an attribute that the keyboard's module does not have."),
    ("c-extension", "The package needs compiled code, which plugins cannot load."),
    ("c-source", "The package ships C source to build, which plugins cannot."),
    ("module-name", "A file's name is one Python cannot import."),
    ("reserved-name", "A module's name is taken by the keyboard's own modules."),
    ("too-deep", "A file sits deeper in folders than the keyboard loads."),
    ("file-too-big", "A .py file is over the size the keyboard loads; `wmpy pack` may shake it smaller."),
    ("loaded-later", "An import only runs when a function is called, so a missing module fails then, not on import."),
    ("not-patched", "wmpy keeps a fix for this package, but this version did not take it."),
    ("multiple-inheritance", "A class inherits from built-in types in a way the interpreter does not support."),
    ("big-int", "Arithmetic on numbers bigger than the interpreter handled before."),
    ("needs-build", "The package has no pure-Python files: only a source archive that needs building, or compiled wheels."),
];

const SILENCE: &str = "Silence it on one line with a comment `# wmpy: ignore[{code}]` at the end of the line, in a whole file with `# wmpy: ignore-file[{code}]`, or in the whole plugin in wmpy.toml: [check] ignore = [\"{code}\"].";

struct Entry {
    code: String,
    severity: Option<Severity>,
    kind: &'static str,
    message: Option<&'static str>,
    meaning: String,
    fix: Option<&'static str>,
    silence: Option<String>,
    docs: String,
}

fn of_tooling(c: Code) -> Entry {
    let (meaning, page) = family(c);
    let probe = wm_python_tooling::diagnostics::Diagnostic::new(c, Default::default(), &[]);
    Entry {
        code: c.name().to_string(),
        severity: Some(c.severity()),
        kind: "python",
        message: Some(c.message()),
        meaning: meaning.to_string(),
        fix: quick_fix(c),
        silence: probe.can_be_ignored().then(|| SILENCE.replace("{code}", c.name())),
        docs: format!("{DOCS}{page}"),
    }
}

fn find(code: &str) -> Option<Entry> {
    if let Some(c) = Code::ALL.iter().find(|c| c.name() == code) {
        return Some(of_tooling(*c));
    }
    if let Some((c, m)) = MANIFEST.iter().find(|(c, _)| *c == code) {
        return Some(Entry {
            code: c.to_string(),
            severity: None,
            kind: "manifest",
            message: None,
            meaning: m.to_string(),
            fix: None,
            silence: Some(format!("plugin.json problems are not silenced: the app reads plugin.json the same way. Pack anyway with `wmpy pack --no-check`, or leave the code out with wmpy.toml's [check] ignore = [\"{c}\"] if wmpy is wrong about it.")),
            docs: format!("{DOCS}/plugins/wm-package/#the-manifest"),
        });
    }
    if let Some((c, m)) = PACKAGES.iter().find(|(c, _)| *c == code) {
        return Some(Entry {
            code: c.to_string(),
            severity: None,
            kind: "package",
            message: None,
            meaning: m.to_string(),
            fix: None,
            silence: Some("`wmpy pip install --allow-warnings` installs a package with warnings, and `--force` one with errors.".into()),
            docs: format!("{DOCS}/plugins/guides/package-compatibility/"),
        });
    }
    None
}

fn every_code() -> Vec<(String, &'static str)> {
    let mut all: Vec<(String, &'static str)> = Code::ALL.iter().map(|c| (c.name().to_string(), "python")).collect();
    all.extend(MANIFEST.iter().map(|(c, _)| (c.to_string(), "manifest")));
    all.extend(PACKAGES.iter().map(|(c, _)| (c.to_string(), "package")));
    all
}

pub fn run(args: Args) -> u8 {
    let Some(code) = args.code.as_deref().map(|c| c.trim().trim_start_matches('[').trim_end_matches(']').to_string()) else {
        return list(args.json);
    };
    // `error[name_typo]` as wmpy prints it works too.
    let code = code.rsplit('[').next().unwrap_or(&code).to_string();
    let Some(e) = find(&code) else {
        let near = wm_python_tooling::text::closest(&code, every_code().iter().map(|(c, _)| c.as_str()));
        let mut d = crate::diag::Diag::error(format!("wmpy has no problem code “{code}”"));
        d.help = Some(match near {
            Some(n) => format!("did you mean {n}? `wmpy explain` lists every code"),
            None => "`wmpy explain` lists every code".into(),
        });
        if args.json {
            println!("{}", json!({"ok": false, "error": d.to_json()}));
        } else {
            eprint!("{}", d.render(&|_| None, output::stderr()));
        }
        return 1;
    };
    if args.json {
        println!(
            "{}",
            json!({
                "code": e.code, "kind": e.kind, "severity": e.severity.map(|s| s.name()), "message": e.message,
                "meaning": e.meaning, "fix": e.fix, "silence": e.silence, "docs": e.docs,
            })
        );
        return 0;
    }
    let color = output::stdout();
    let head = match e.severity {
        Some(s) => format!("{} ({}, {})", e.code, s.name(), e.kind),
        None => format!("{} ({})", e.code, e.kind),
    };
    println!("{}", output::paint(color, "1", &head));
    if let Some(m) = e.message {
        println!("  {}", m.replace("{0}", "…").replace("{1}", "…"));
    }
    println!();
    println!("{}", wrap(&e.meaning, 78));
    if let Some(f) = e.fix {
        println!();
        println!("{}", wrap(&format!("Quick fix: the editors and `wmpy check --fix` {f}."), 78));
    }
    if let Some(s) = &e.silence {
        println!();
        println!("{}", wrap(s, 78));
    }
    println!();
    println!("More: {}", e.docs);
    0
}

fn list(json_out: bool) -> u8 {
    if json_out {
        let all: Vec<serde_json::Value> = every_code()
            .iter()
            .filter_map(|(c, _)| find(c))
            .map(|e| json!({"code": e.code, "kind": e.kind, "severity": e.severity.map(|s| s.name()), "message": e.message.map(String::from).unwrap_or(e.meaning)}))
            .collect();
        println!("{}", serde_json::Value::Array(all));
        return 0;
    }
    let color = output::stdout();
    for (title, kind) in [("Python (wmpy check, the editors)", "python"), ("plugin.json and the archive", "manifest"), ("Packages (wmpy pip)", "package")] {
        println!("{}", output::paint(color, "1", title));
        for (code, k) in every_code() {
            if k != kind {
                continue;
            }
            let e = find(&code).expect("listed");
            let what = e.message.map(|m| m.replace("{0}", "…").replace("{1}", "…")).unwrap_or(e.meaning);
            let sev = e.severity.map(|s| format!("{:<7} ", s.name())).unwrap_or_default();
            println!("  {code:<26} {sev}{what}");
        }
        println!();
    }
    println!("wmpy explain <code> says more about one.");
    0
}

/// [text] in lines of at most [width] characters.
fn wrap(text: &str, width: usize) -> String {
    let mut out = String::new();
    let mut line = String::new();
    for word in text.split_whitespace() {
        if !line.is_empty() && line.chars().count() + 1 + word.chars().count() > width {
            out.push_str(&line);
            out.push('\n');
            line.clear();
        }
        if !line.is_empty() {
            line.push(' ');
        }
        line.push_str(word);
    }
    out.push_str(&line);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_code_has_an_entry_and_syntax_cannot_be_silenced() {
        for c in Code::ALL {
            let e = find(c.name()).expect("a tooling code");
            assert!(!e.meaning.is_empty());
        }
        assert!(find("unclosed_string").unwrap().silence.is_none());
        assert!(find("name_typo").unwrap().silence.unwrap().contains("# wmpy: ignore[name_typo]"));
        assert_eq!(find("unknown_permission").unwrap().kind, "manifest");
        assert_eq!(find("c-extension").unwrap().kind, "package");
        assert!(find("no_such_code").is_none());
    }
}
