//! `wmpy mock`. Not built yet.

#[derive(clap::Args, Debug)]
pub struct Args {
    /// Arguments, until this command is built
    #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
    pub rest: Vec<String>,
}

pub fn run(_args: Args) -> u8 {
    eprintln!("wmpy mock: not built yet");
    2
}
