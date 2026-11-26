//! `wmpy preview`. Not built yet.

use std::path::PathBuf;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
}

pub fn run(_args: Args) -> u8 {
    eprintln!("wmpy preview: not built yet");
    2
}
