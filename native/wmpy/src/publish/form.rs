//! The community repository's plugin form, as wmpy fills it: the `wmkb:1:`
//! payload, the link that opens the form filled in, and the issue body the
//! bot reads back. Every rule here is a contract with someone else:
//!
//! * the payload encoding with the app (`CommunitySubmission.encodePayload`)
//!   and the bot (`bot/src/payload.ts`), who share the test vectors in
//!   `app/src/test/resources/addons/community-submission-vectors.json`:
//!   `wmkb:1:` + base64url without padding of the zlib-wrapped DEFLATE of
//!   the file's bytes;
//! * the link with `CommunitySubmission.issueUrl`: one query parameter per
//!   form field id, percent-encoded as Java's `URLEncoder` does with `%20`
//!   for a space, since GitHub's prefill does not read `+` as one;
//! * the body with the form, `.github/ISSUE_TEMPLATE/submit-plugin.yml` in
//!   the community repository: one `### <label>` heading per field, as
//!   GitHub renders a submitted form, after a first line that tells the bot
//!   an API-filed issue apart (the API drops the form's labels).

use base64::Engine;

pub const PREFIX: &str = "wmkb:1:";
pub const TEMPLATE: &str = "submit-plugin.yml";
pub const TITLE_PREFIX: &str = "[Plugin]";
pub const MARKER: &str = "<!-- wmkb-submit:plugin -->";

/// The app's `LINK_LIMIT`: past about 8 KB GitHub answers with an error page instead of the form.
pub const LINK_LIMIT: usize = 7_500;
/// The app's `PASTE_LIMIT`: an issue body holds 65,536 characters, and the form has other fields.
pub const PASTE_LIMIT: usize = 60_000;
/// The most an issue body may hold.
pub const BODY_LIMIT: usize = 65_536;

/// The bot's limits on the form (bot/src/constants.ts LIMITS).
pub const MAX_NAME: usize = 60;
pub const MAX_DESCRIPTION: usize = 500;
pub const MAX_TAGS: usize = 8;
pub const MAX_TAG: usize = 24;

/// The licences the plugin form offers, in its order (CODE_LICENSES in the bot).
pub const LICENCES: &[&str] = &[
    "MIT",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "MPL-2.0",
    "LGPL-3.0-or-later",
    "GPL-3.0-or-later",
    "AGPL-3.0-or-later",
    "Unlicense",
    "CC0-1.0",
];

/// The rights box's one option, word for word as the form has it.
pub const RIGHTS: &str = "I wrote this code, or I have the right to publish it under the licence above. It holds no passwords, keys or personal data, and does only what its description says.";

/// An addon id in the community repository: lowercase, 2-48 characters.
pub fn is_addon_id(s: &str) -> bool {
    let b = s.as_bytes();
    (2..=48).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

/// The id the bot makes from a name, for plain ASCII names (`Midnight Garden` → `midnight-garden`).
pub fn slugify(name: &str) -> String {
    let mut out = String::new();
    for c in name.to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else if !out.is_empty() && !out.ends_with('-') {
            out.push('-');
        }
    }
    let mut out: String = out.trim_end_matches('-').chars().take(48).collect();
    while out.ends_with('-') {
        out.pop();
    }
    out
}

/// What the form's fields hold; each is a form field id of the same name.
#[derive(Clone, Debug, Default)]
pub struct Fields {
    pub name: String,
    pub description: String,
    pub tags: Vec<String>,
    pub license: String,
    /// The addon id of a plugin published before, to send a new version of it.
    pub updates: String,
    /// `repo-id/addon-id` of the plugin this one is a fork of.
    pub based_on: String,
}

impl Fields {
    /// Tags trimmed, lowercased and deduplicated, as the bot reads them.
    pub fn clean_tags(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for t in &self.tags {
            let t = t.trim().trim_start_matches('#').to_lowercase();
            if !t.is_empty() && !out.contains(&t) {
                out.push(t);
            }
        }
        out
    }

    /// What the bot would refuse about the fields, before anything is sent.
    pub fn problems(&self) -> Vec<String> {
        let mut out = Vec::new();
        let name = self.name.trim();
        if name.is_empty() {
            out.push("the plugin has no name: set \"name\" in plugin.json".to_string());
        } else if name.chars().count() > MAX_NAME {
            out.push(format!("the name is {} characters; the community repository takes {MAX_NAME}", name.chars().count()));
        }
        let description = self.description.trim();
        if description.is_empty() {
            out.push("there is no description: set \"description\" in plugin.json or pass --description".to_string());
        } else if description.chars().count() > MAX_DESCRIPTION {
            out.push(format!("the description is {} characters; the community repository takes {MAX_DESCRIPTION}", description.chars().count()));
        }
        let tags = self.clean_tags();
        if tags.len() > MAX_TAGS {
            out.push(format!("{} tags; the community repository takes {MAX_TAGS}", tags.len()));
        }
        if let Some(t) = tags.iter().find(|t| t.chars().count() > MAX_TAG) {
            out.push(format!("the tag \"{t}\" is longer than {MAX_TAG} characters"));
        }
        if !LICENCES.contains(&self.license.as_str()) {
            out.push(format!("\"{}\" is not a licence the plugin form offers: pick one of {}", self.license, LICENCES.join(", ")));
        }
        if !self.updates.is_empty() && !is_addon_id(&self.updates) {
            out.push(format!("--update takes the plugin's id in the community repository (the folder under addons/plugins), not \"{}\"", self.updates));
        }
        out
    }

    pub fn title(&self) -> String {
        format!("{TITLE_PREFIX} {}", self.name.trim())
    }
}

/// `wmkb:1:` + base64url, no padding, of the zlib-wrapped DEFLATE of [file].
pub fn encode_payload(file: &[u8]) -> String {
    let deflated = miniz_oxide::deflate::compress_to_vec_zlib(file, 9);
    format!("{PREFIX}{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(deflated))
}

/// The file in a `wmkb:1:` string, or None. At most [max] bytes are inflated.
pub fn decode_payload(text: &str, max: usize) -> Option<Vec<u8>> {
    let body = text.trim().strip_prefix(PREFIX)?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(body.trim_end_matches('=')).ok()?;
    miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(&bytes, max).ok()
}

/// Percent-encoding as Java's `URLEncoder.encode(s, "UTF-8")`, then `+` as `%20`:
/// letters, digits and `.-*_` stay, everything else is `%XX` of its UTF-8 bytes.
pub fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'*' | b'_') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// The link that opens the form with [fields] filled in, and the payload too when given.
pub fn issue_url(repo_url: &str, fields: &Fields, payload: Option<&str>) -> String {
    let mut params: Vec<(&str, String)> = vec![("template", TEMPLATE.into()), ("title", fields.title()), ("name", fields.name.trim().into())];
    if !fields.description.trim().is_empty() {
        params.push(("description", fields.description.trim().into()));
    }
    let tags = fields.clean_tags();
    if !tags.is_empty() {
        params.push(("tags", tags.join(", ")));
    }
    params.push(("license", fields.license.clone()));
    if !fields.updates.trim().is_empty() {
        params.push(("updates", fields.updates.trim().into()));
    }
    if !fields.based_on.trim().is_empty() {
        params.push(("based_on", fields.based_on.trim().into()));
    }
    if let Some(p) = payload {
        params.push(("payload", p.into()));
    }
    let query: Vec<String> = params.iter().map(|(k, v)| format!("{k}={}", url_encode(v))).collect();
    format!("{}/issues/new?{}", repo_url.trim_end_matches('/'), query.join("&"))
}

/// One line, for a heading's answer: a newline would end the answer early.
fn one_line(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The issue body as GitHub renders the submitted form, after [MARKER], with
/// [note] (what wmpy checked) above the first heading, where the bot does not read.
pub fn issue_body(fields: &Fields, payload: &str, note: &str) -> String {
    let answer = |s: String| if s.trim().is_empty() { "_No response_".to_string() } else { s };
    let sections = [
        ("Name", one_line(&fields.name)),
        ("Description", one_line(&fields.description)),
        ("Tags", fields.clean_tags().join(", ")),
        ("License", fields.license.clone()),
        ("Updates", fields.updates.trim().to_string()),
        ("Based on", fields.based_on.trim().to_string()),
        ("Plugin", payload.to_string()),
        ("Rights", format!("- [X] {RIGHTS}")),
    ];
    let mut out = String::from(MARKER);
    out.push('\n');
    if !note.is_empty() {
        out.push_str(note.trim());
        out.push_str("\n\n");
    }
    for (i, (heading, value)) in sections.into_iter().enumerate() {
        if i > 0 {
            out.push_str("\n\n");
        }
        out.push_str(&format!("### {heading}\n\n{}", answer(value)));
    }
    out.push('\n');
    out
}

/// How the payload reaches the form.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tier {
    /// In the link that opens the form.
    Link,
    /// Copied, for pasting into the Plugin box.
    Paste,
    /// The .wmplugin renamed .zip, dragged into the Plugin box.
    Attach,
}

/// The smallest way [encoded] fits, as `CommunitySubmission.plan` picks it.
pub fn tier(repo_url: &str, fields: &Fields, encoded: &str) -> Tier {
    if issue_url(repo_url, fields, Some(encoded)).len() <= LINK_LIMIT {
        Tier::Link
    } else if encoded.len() <= PASTE_LIMIT {
        Tier::Paste
    } else {
        Tier::Attach
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value as Json;

    #[test]
    fn url_encoding_matches_javas_with_spaces_as_percent_20() {
        assert_eq!(url_encode("Night Owl & co: ü/+*.-_~"), "Night%20Owl%20%26%20co%3A%20%C3%BC%2F%2B*.-_%7E");
    }

    #[test]
    fn slugs_follow_the_bot() {
        assert_eq!(slugify("Midnight Garden"), "midnight-garden");
        assert_eq!(slugify("  Cipher -- Tool! "), "cipher-tool");
        assert_eq!(slugify(&"a".repeat(60)).len(), 48);
        assert!(is_addon_id("cipher-tool") && !is_addon_id("Cipher") && !is_addon_id("x") && !is_addon_id("com.example"));
    }

    #[test]
    fn a_payload_round_trips_and_a_bad_one_is_none() {
        let encoded = encode_payload(b"PK\x03\x04 not really a zip");
        assert!(encoded.starts_with(PREFIX) && !encoded.contains('='));
        assert_eq!(decode_payload(&format!("\n {encoded} \n"), 1 << 20).unwrap(), b"PK\x03\x04 not really a zip");
        assert!(decode_payload("wmkb:2:abc", 100).is_none());
        assert!(decode_payload("wmkb:1:!!!", 100).is_none());
        assert!(decode_payload(&encode_payload(&[0u8; 1000]), 10).is_none());
    }

    #[test]
    fn the_body_has_every_heading_once_and_answers_on_one_line() {
        let fields = Fields { name: "Echo".into(), description: "Types back\n### License\nwhat you write.".into(), license: "MIT".into(), ..Default::default() };
        let body = issue_body(&fields, "wmkb:1:abc", "");
        assert!(body.starts_with("<!-- wmkb-submit:plugin -->\n### Name\n\nEcho\n\n### Description\n\nTypes back ### License what you write.\n\n### Tags\n\n_No response_\n\n### License\n\nMIT\n"));
        assert_eq!(body.matches("\n### License\n").count(), 1);
        assert!(body.ends_with("### Plugin\n\nwmkb:1:abc\n\n### Rights\n\n- [X] I wrote this code, or I have the right to publish it under the licence above. It holds no passwords, keys or personal data, and does only what its description says.\n"));
    }

    #[test]
    fn tiers_follow_the_apps_limits() {
        let fields = Fields { name: "Echo".into(), description: "d".into(), license: "MIT".into(), ..Default::default() };
        assert_eq!(tier("https://github.com/o/r", &fields, "wmkb:1:short"), Tier::Link);
        assert_eq!(tier("https://github.com/o/r", &fields, &"x".repeat(20_000)), Tier::Paste);
        assert_eq!(tier("https://github.com/o/r", &fields, &"x".repeat(70_000)), Tier::Attach);
    }

    /// The vectors the app and the bot share: a different encoder (Python's
    /// zlib) wrote them, so decoding them here keeps the three from drifting.
    fn vectors() -> Json {
        let path = std::env::var_os("WMKB_SUBMISSION_VECTORS").map(std::path::PathBuf::from).unwrap_or_else(|| {
            let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
            loop {
                let candidate = dir.join("app/src/test/resources/addons/community-submission-vectors.json");
                if candidate.is_file() || !dir.pop() {
                    return candidate;
                }
            }
        });
        let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e} (set WMKB_SUBMISSION_VECTORS to the shared vectors file)", path.display()));
        serde_json::from_str(&text).unwrap()
    }

    #[test]
    fn every_shared_vector_decodes_and_round_trips() {
        let all = vectors();
        let list = all["vectors"].as_array().unwrap();
        assert!(list.len() >= 4);
        let mut binary = 0;
        for v in list {
            let name = v["name"].as_str().unwrap();
            let want: Vec<u8> = match (v.get("json").and_then(|j| j.as_str()), v.get("bytes").and_then(|b| b.as_str())) {
                (Some(json), _) => json.as_bytes().to_vec(),
                (None, Some(b64)) => {
                    binary += 1;
                    base64::engine::general_purpose::STANDARD.decode(b64).unwrap()
                }
                _ => panic!("{name}: neither json nor bytes"),
            };
            let encoded = v["encoded"].as_str().unwrap();
            assert!(!encoded.contains('='), "{name}: padding");
            assert_eq!(decode_payload(encoded, 64 << 20).as_deref(), Some(want.as_slice()), "{name}: the shared string");
            assert_eq!(decode_payload(&encode_payload(&want), 64 << 20).as_deref(), Some(want.as_slice()), "{name}: wmpy's own string");
        }
        assert!(binary >= 1, "a .wmplugin vector");
    }

    #[test]
    fn the_problems_are_the_bots() {
        let ok = Fields { name: "Echo".into(), description: "d".into(), license: "MIT".into(), ..Default::default() };
        assert!(ok.problems().is_empty());
        let bad = Fields { name: "".into(), description: "".into(), license: "CC-BY-4.0".into(), updates: "Echo Tool".into(), tags: (0..9).map(|i| format!("t{i}")).collect(), ..Default::default() };
        assert_eq!(bad.problems().len(), 5);
    }
}
