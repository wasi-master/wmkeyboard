//! `--locale`, `--theme` and `--surface`: what a plugin is told about where
//! it runs, for every command that runs it. The flag wins, then wmpy.toml's
//! `[run]`, then English, light, panel.

use std::path::Path;

use crate::config::Config;
use crate::project::Context;

#[derive(clap::Args, Debug, Clone, Default)]
pub struct Look {
    /// The user's language, as the plugin sees it (plugin.locale), and the locales/<tag>.json it is translated with [default: en]
    #[arg(long, value_name = "TAG", value_parser = parse_locale)]
    pub locale: Option<String>,
    /// The theme the plugin sees (plugin.theme, plugin.dark) [default: light]
    #[arg(long, value_parser = ["dark", "light"])]
    pub theme: Option<String>,
    /// Where the plugin draws (plugin.surface): the panel, or the floating box [default: panel]
    #[arg(long, value_parser = ["panel", "float"])]
    pub surface: Option<String>,
}

fn parse_locale(s: &str) -> Result<String, String> {
    if crate::project::is_locale_tag(s) {
        Ok(s.to_string())
    } else {
        Err(format!("'{s}' is not a language tag: give one such as en, bn, pt-BR or zh-Hant"))
    }
}

impl Look {
    /// The context these flags and [config] ask for.
    pub fn context(&self, config: &Config) -> Context {
        let d = Context::default();
        Context {
            locale: self.locale.clone().or_else(|| config.run.locale.clone()).unwrap_or(d.locale),
            theme: self.theme.clone().or_else(|| config.run.theme.clone()).unwrap_or(d.theme),
            surface: self.surface.clone().or_else(|| config.run.surface.clone()).unwrap_or(d.surface),
        }
    }

    /// Makes [Look::context] the context of every session this run opens,
    /// and returns it. A note says when the plugin has no translation for the locale.
    pub fn apply(&self, dir: &Path, config: &Config) -> Context {
        let ctx = self.context(config);
        if ctx.locale != "en" {
            let tags = crate::project::locale_tags(dir);
            if crate::project::pick_locale(&tags, &ctx.locale).is_none() {
                let have = if tags.is_empty() { "none yet".to_string() } else { tags.join(", ") };
                crate::note!("note: no locales/ file fits {}, so the plugin shows its English (translations: {have})", ctx.locale);
            }
        }
        crate::project::set_context(ctx.clone());
        ctx
    }
}
