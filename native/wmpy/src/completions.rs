//! `wmpy completions <shell>`: shell completion scripts.

use clap::CommandFactory;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// bash, zsh, fish, powershell or elvish
    pub shell: clap_complete::Shell,
}

pub fn run(args: Args) -> u8 {
    let mut cmd = crate::Cli::command();
    clap_complete::generate(args.shell, &mut cmd, "wmpy", &mut std::io::stdout());
    0
}
