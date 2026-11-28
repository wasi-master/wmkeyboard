//! `wmpy budget`: how close each part of a run comes to the keyboard's limits.
//!
//! ```text
//! wmpy budget [dir] [--scenario file.json] [--tests] [-k text] [--mock file] [--locale tag] [--theme t] [--device-speed F] [--json]
//! ```
//!
//! The plugin loads on the dev host ([crate::devhost]) and draws, then runs
//! a scenario, its tests, or by default a click on each button of its first
//! draw (each click draws again) and each timer once. Every session call
//! reports its phases; the report gives, for each kind of phase (load,
//! event, render, and action or preview when tests run them), the most and
//! the average steps and time, as a share of the limits (9M steps and 3 s to
//! load, 6M and 2 s an event, 1.2M and 0.5 s a draw), and the worst call.
//! 50% of either limit is a warning, 80% is danger, and the run exits with
//! 1 when anything went over, so CI can use it.
//!
//! Time is the script's own: time spent in host calls (http, ai, a pretend
//! answer) is taken off, as the keyboard's deadline leaves it out. Measured
//! time is divided by `--device-speed` (0.3 for a phone three times slower
//! than this computer); it defaults to 1.0, and the report says which. On a
//! slow phone the time limit, not the steps, is what stops a plugin.

pub mod drive;
pub mod suite;

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{json, Value as Json};

use crate::diag::{color_stderr, folder_source, Diag};
use drive::{Outcome, Plan, Setup, Step};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// A JSON list of events to run instead of the default clicks, or
    /// {"events": [...], "mock": {...}, "settings": {...}}
    #[arg(long, value_name = "FILE", conflicts_with = "tests")]
    pub scenario: Option<PathBuf>,
    /// Run the plugin's tests instead, measuring every session they make
    #[arg(long)]
    pub tests: bool,
    /// Run only the tests whose file::name contains this text (implies --tests)
    #[arg(short = 'k', value_name = "TEXT")]
    pub filter: Option<String>,
    /// A JSON file answering host calls by op name before the dev host does [default: wmpy.toml's run.mock]
    #[arg(long, value_name = "FILE")]
    pub mock: Option<PathBuf>,
    #[command(flatten)]
    pub look: crate::look::Look,
    /// How fast the phone is next to this computer: 0.3 for one three times slower
    #[arg(long, value_name = "F")]
    pub device_speed: Option<f64>,
    /// Print the report as JSON
    #[arg(long)]
    pub json: bool,
}

pub fn run(args: Args) -> u8 {
    let dir = match crate::project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            eprint!("{}", d.render(&|_| None, color_stderr()));
            return 2;
        }
    };
    let config = match crate::config::load_or_report(&dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    args.look.apply(&dir, &config);
    let mock = args.mock.clone().or_else(|| config.run.mock.as_ref().map(|m| crate::config::in_dir(&dir, m)));
    let speed = match args.device_speed {
        Some(s) if !(s.is_finite() && s > 0.0) => {
            eprintln!("error: --device-speed is a number above 0, such as 0.3 for a phone three times slower than this computer");
            return 2;
        }
        Some(s) => Speed { factor: s, given: true },
        None => Speed { factor: 1.0, given: false },
    };
    let plan = match plan_of(args.scenario.as_deref(), args.tests || args.filter.is_some(), args.filter.clone()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let setup = Setup {
        dir: &dir,
        home: &dir,
        plan: &plan,
        mock: mock.as_deref(),
        settle_for: Duration::from_secs(20),
        backend: suite::Backend::Mock,
        prepare: None,
    };
    let outcome = match drive::run(&setup) {
        Ok(o) => o,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let report = Report::of(&outcome, speed);
    if args.json {
        println!("{}", report.json(&outcome));
    } else {
        print!("{}", report.text(&outcome, &dir, color_stderr() && std::io::IsTerminal::is_terminal(&std::io::stdout())));
    }
    u8::from(report.over || !outcome.loaded)
}

/// The plan the flags ask for.
pub fn plan_of(scenario: Option<&Path>, tests: bool, filter: Option<String>) -> Result<Plan, String> {
    match (scenario, tests) {
        (Some(path), _) => drive::read_scenario(path).map(Plan::Scenario),
        (None, true) => Ok(Plan::Tests { filter }),
        (None, false) => Ok(Plan::Clicks),
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Speed {
    pub factor: f64,
    /// Given with --device-speed, rather than the default.
    pub given: bool,
}

impl Speed {
    /// The words saying where the times come from.
    pub fn words(self) -> String {
        if self.given {
            format!("time is what this computer measured divided by --device-speed {}", trim_float(self.factor))
        } else {
            "time is as this computer measured it (--device-speed 1.0, the default): pass --device-speed 0.3 for a phone three times slower".into()
        }
    }
}

fn trim_float(f: f64) -> String {
    let s = format!("{f:.3}");
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

/// How full a phase came: below half, from half, from 80%, past the limit.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Level {
    Ok,
    Warning,
    Danger,
    Over,
}

impl Level {
    fn of(percent: f64) -> Level {
        if percent > 100.0 {
            Level::Over
        } else if percent >= 80.0 {
            Level::Danger
        } else if percent >= 50.0 {
            Level::Warning
        } else {
            Level::Ok
        }
    }

    fn word(self) -> &'static str {
        match self {
            Level::Ok => "ok",
            Level::Warning => "warning",
            Level::Danger => "danger",
            Level::Over => "over",
        }
    }
}

/// One phase of one step, scaled.
#[derive(Clone, Debug)]
struct Use {
    step: usize,
    /// Its place among the step's phases: a draw after an event is not the first.
    index: usize,
    steps: u64,
    steps_limit: u64,
    /// Scaled by the device speed.
    micros: f64,
    millis_limit: u64,
    /// The keyboard stopped it (steps or time).
    stopped: bool,
}

impl Use {
    fn step_pct(&self) -> f64 {
        if self.steps_limit == 0 { 0.0 } else { self.steps as f64 * 100.0 / self.steps_limit as f64 }
    }
    fn time_pct(&self) -> f64 {
        if self.millis_limit == 0 { 0.0 } else { self.micros / 10.0 / self.millis_limit as f64 }
    }
    fn level(&self) -> Level {
        if self.stopped {
            return Level::Over;
        }
        Level::of(self.step_pct().max(self.time_pct()))
    }
}

/// The numbers for one kind of phase.
struct Kind {
    name: String,
    uses: Vec<Use>,
}

impl Kind {
    fn max_steps(&self) -> u64 {
        self.uses.iter().map(|u| u.steps).max().unwrap_or(0)
    }
    fn avg_steps(&self) -> f64 {
        self.uses.iter().map(|u| u.steps as f64).sum::<f64>() / self.uses.len().max(1) as f64
    }
    fn max_micros(&self) -> f64 {
        self.uses.iter().map(|u| u.micros).fold(0.0, f64::max)
    }
    fn avg_micros(&self) -> f64 {
        self.uses.iter().map(|u| u.micros).sum::<f64>() / self.uses.len().max(1) as f64
    }
    fn steps_limit(&self) -> u64 {
        self.uses.iter().map(|u| u.steps_limit).max().unwrap_or(0)
    }
    fn millis_limit(&self) -> u64 {
        self.uses.iter().map(|u| u.millis_limit).max().unwrap_or(0)
    }
    fn worst(&self) -> Option<&Use> {
        self.uses.iter().max_by(|a, b| a.step_pct().max(a.time_pct()).total_cmp(&b.step_pct().max(b.time_pct())))
    }
    fn level(&self) -> Level {
        self.uses.iter().map(Use::level).max().unwrap_or(Level::Ok)
    }
}

/// The order phase kinds are shown in.
const ORDER: &[&str] = &["load", "event", "render", "action", "preview", "thumbnail"];

pub struct Report {
    kinds: Vec<Kind>,
    speed: Speed,
    /// Something went over a limit, or would on the phone.
    pub over: bool,
}

impl Report {
    pub fn of(o: &Outcome, speed: Speed) -> Report {
        let mut kinds: Vec<Kind> = Vec::new();
        for (i, step) in o.steps.iter().enumerate() {
            let n = step.phases.len();
            for (j, p) in step.phases.iter().enumerate() {
                // The phase the keyboard stopped is the last one the result lists.
                let stopped = j + 1 == n && matches!(step.abort.as_deref(), Some("steps" | "deadline"));
                let u = Use { step: i, index: j, steps: p.steps, steps_limit: p.steps_limit, micros: p.micros as f64 / speed.factor, millis_limit: p.millis_limit, stopped };
                match kinds.iter_mut().find(|k| k.name == p.kind) {
                    Some(k) => k.uses.push(u),
                    None => kinds.push(Kind { name: p.kind.clone(), uses: vec![u] }),
                }
            }
        }
        kinds.sort_by_key(|k| ORDER.iter().position(|o| *o == k.name).unwrap_or(ORDER.len()));
        let over = kinds.iter().any(|k| k.level() == Level::Over);
        Report { kinds, speed, over }
    }

    /// What a phase was part of: `click button:Add`, or `the draw after click button:Add`.
    fn label(&self, o: &Outcome, u: &Use) -> String {
        let s = &o.steps[u.step];
        if u.index == 0 {
            s.label.clone()
        } else {
            format!("the draw after {}", s.label)
        }
    }

    pub fn text(&self, o: &Outcome, dir: &Path, color: bool) -> String {
        let paint = |code: &str, t: &str| if color { format!("\x1b[{code}m{t}\x1b[0m") } else { t.to_string() };
        let mut out = String::new();
        out.push_str(&format!("wmpy budget: {}, driven by {}\n", crate::project::shown(dir), o.what));
        out.push_str(&format!("{}\n", self.speed.words()));
        out.push_str("host calls (http, ai, pretend answers) are left out of the time, as the keyboard leaves them out\n");
        if cfg!(debug_assertions) {
            out.push_str("this is a debug build of wmpy, whose interpreter is several times slower than a release build's\n");
        }
        out.push('\n');
        let header = format!("{:<10} {:>5}  {:>10} {:>10} {:>7}  {:>9} {:>9} {:>7}  {}", "phase", "runs", "steps max", "avg", "% limit", "time max", "avg", "% limit", "");
        out.push_str(&paint("1", header.trim_end()));
        out.push('\n');
        for k in &self.kinds {
            let level = k.level();
            let mark = match level {
                Level::Ok => String::new(),
                Level::Warning => paint("1;33", "warning"),
                Level::Danger => paint("1;31", "danger"),
                Level::Over => paint("1;31", "OVER"),
            };
            let line = format!(
                "{:<10} {:>5}  {:>10} {:>10} {:>7}  {:>9} {:>9} {:>7}  {mark}",
                k.name,
                k.uses.len(),
                count(k.max_steps()),
                count(k.avg_steps() as u64),
                pct(k.max_steps() as f64 * 100.0 / k.steps_limit().max(1) as f64),
                time(k.max_micros()),
                time(k.avg_micros()),
                pct(k.max_micros() / 10.0 / k.millis_limit().max(1) as f64),
            );
            out.push_str(line.trim_end());
            out.push('\n');
        }
        out.push_str(&format!(
            "\nlimits: {}\n",
            self.kinds.iter().map(|k| format!("{} {} steps and {} s", k.name, count(k.steps_limit()), trim_float(k.millis_limit() as f64 / 1000.0))).collect::<Vec<_>>().join("; ")
        ));
        for k in &self.kinds {
            if let Some(u) = k.worst() {
                if k.uses.len() > 1 || k.name == "event" {
                    out.push_str(&format!(
                        "worst {}: {} ({} steps, {}; {} and {} of the limits)\n",
                        k.name,
                        self.label(o, u),
                        count(u.steps),
                        time(u.micros),
                        pct(u.step_pct()),
                        pct(u.time_pct())
                    ));
                }
            }
        }
        // Each call at 50% or more, worst first.
        let mut flagged: Vec<(Level, f64, String)> = Vec::new();
        for k in &self.kinds {
            for u in &k.uses {
                let level = u.level();
                if level >= Level::Warning {
                    let s = &o.steps[u.step];
                    let label = self.label(o, u);
                    let what = if u.stopped {
                        let why = if s.abort.as_deref() == Some("steps") { "out of steps" } else { "out of time" };
                        format!("the keyboard stopped {label} in its {} phase: {why}", k.name)
                    } else if u.step_pct() >= u.time_pct() {
                        format!("{label} used {} of the {} step limit", pct(u.step_pct()), k.name)
                    } else {
                        format!("{label} took {} of the {} time limit", pct(u.time_pct()), k.name)
                    };
                    flagged.push((level, u.step_pct().max(u.time_pct()), what));
                }
            }
        }
        flagged.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.total_cmp(&a.1)));
        if !flagged.is_empty() {
            out.push('\n');
        }
        for (level, _, what) in flagged.iter().take(10) {
            let word = match level {
                Level::Over => paint("1;31", "over"),
                Level::Danger => paint("1;31", "danger"),
                _ => paint("1;33", "warning"),
            };
            out.push_str(&format!("{word}: {what}\n"));
        }
        if flagged.len() > 10 {
            out.push_str(&format!("... and {} more at 50% or over\n", flagged.len() - 10));
        }
        // A stop's own error says this; a phone's slower time does not.
        if self.over && !o.steps.iter().any(|s| matches!(s.abort.as_deref(), Some("steps" | "deadline"))) {
            out.push_str("  = note: the keyboard switches off a plugin that is stopped twice, until the user turns it on again\n");
        }
        let errors: Vec<&Step> = o.steps.iter().filter(|s| s.error.is_some() && !matches!(s.abort.as_deref(), Some("steps" | "deadline"))).collect();
        if !o.loaded {
            out.push_str("\nthe plugin did not load, so nothing past the load was measured:\n");
        } else if !errors.is_empty() {
            out.push_str(&format!("\n{} call{} failed; their numbers count up to where they stopped:\n", errors.len(), if errors.len() == 1 { "" } else { "s" }));
        }
        let source = folder_source(dir);
        let mut shown = 0;
        for s in o.steps.iter().filter(|s| s.error.is_some()) {
            if shown == 3 {
                break;
            }
            if let Some(d) = &s.error {
                out.push_str(&format!("\nin {}:\n{}", s.label, d.render(&source, color)));
                shown += 1;
            }
        }
        if !o.failures.is_empty() {
            out.push_str(&format!("\n{} passed, {} failed (the failures' numbers count up to where they stopped)\n", o.passed, o.failures.len()));
            for (id, d) in o.failures.iter().take(3) {
                out.push_str(&format!("FAIL {id}\n{}", d.render(&source, color)));
            }
        }
        for n in &o.notes {
            out.push_str(&format!("  = note: {n}\n"));
        }
        out
    }

    pub fn json(&self, o: &Outcome) -> Json {
        let phases: serde_json::Map<String, Json> = self
            .kinds
            .iter()
            .map(|k| {
                let worst = k.worst().map(|u| {
                    let s = &o.steps[u.step];
                    json!({"label": self.label(o, u), "widget": s.widget, "steps": u.steps, "micros": u.micros.round() as u64, "stepsPercent": round1(u.step_pct()), "timePercent": round1(u.time_pct())})
                });
                let v = json!({
                    "runs": k.uses.len(),
                    "stepsMax": k.max_steps(), "stepsAvg": k.avg_steps().round() as u64, "stepsLimit": k.steps_limit(),
                    "microsMax": k.max_micros().round() as u64, "microsAvg": k.avg_micros().round() as u64, "millisLimit": k.millis_limit(),
                    "stepsPercent": round1(k.max_steps() as f64 * 100.0 / k.steps_limit().max(1) as f64),
                    "timePercent": round1(k.max_micros() / 10.0 / k.millis_limit().max(1) as f64),
                    "level": k.level().word(), "worst": worst,
                });
                (k.name.clone(), v)
            })
            .collect();
        let calls: Vec<Json> = o
            .steps
            .iter()
            .map(|s| {
                json!({
                    "label": s.label, "widget": s.widget, "abort": s.abort, "error": s.error.as_ref().map(Diag::to_json),
                    "phases": s.phases.iter().map(|p| json!({
                        "phase": p.kind, "steps": p.steps, "stepsLimit": p.steps_limit,
                        "micros": (p.micros as f64 / self.speed.factor).round() as u64, "hostMicros": p.host_micros, "millisLimit": p.millis_limit,
                    })).collect::<Vec<_>>(),
                })
            })
            .collect();
        json!({
            "ok": !self.over && o.loaded, "loaded": o.loaded, "over": self.over, "drivenBy": o.what,
            "deviceSpeed": self.speed.factor, "deviceSpeedFrom": if self.speed.given { "flag" } else { "default" },
            "phases": phases, "calls": calls, "notes": o.notes,
            "tests": if o.passed + o.failures.len() > 0 { json!({"passed": o.passed, "failed": o.failures.len()}) } else { Json::Null },
        })
    }
}

fn round1(f: f64) -> f64 {
    (f * 10.0).round() / 10.0
}

/// `12,345,678` as `12.3M`, `45,000` as `45k`.
pub fn count(n: u64) -> String {
    match n {
        0..=9_999 => n.to_string(),
        10_000..=999_999 => format!("{:.1}k", n as f64 / 1e3).replace(".0k", "k"),
        _ => format!("{:.2}M", n as f64 / 1e6).replace(".00M", "M"),
    }
}

/// Microseconds in the unit that reads best.
pub fn time(micros: f64) -> String {
    if micros < 1000.0 {
        format!("{} µs", micros.round())
    } else if micros < 1_000_000.0 {
        format!("{:.1} ms", micros / 1000.0)
    } else {
        format!("{:.2} s", micros / 1e6)
    }
}

pub fn pct(p: f64) -> String {
    if p > 0.0 && p < 1.0 {
        "<1%".into()
    } else {
        format!("{}%", p.round())
    }
}

#[cfg(test)]
mod tests {
    use super::drive::Phase;
    use super::*;

    fn step(label: &str, kind: &str, steps: u64, limit: u64, micros: u64, millis: u64, abort: Option<&str>) -> Step {
        Step {
            label: label.into(),
            widget: None,
            phases: vec![Phase { kind: kind.into(), steps, steps_limit: limit, micros, host_micros: 0, millis_limit: millis }],
            error: None,
            abort: abort.map(String::from),
        }
    }

    #[test]
    fn levels_come_from_the_fuller_limit_and_the_device_speed() {
        let o = Outcome {
            steps: vec![
                step("load", "load", 100, 9_000_000, 1_000, 3000, None),
                step("click button:A", "event", 3_000_000, 6_000_000, 100_000, 2000, None),
                step("click button:B", "event", 10, 6_000_000, 700_000, 2000, None),
            ],
            loaded: true,
            ..Outcome::default()
        };
        let r = Report::of(&o, Speed { factor: 1.0, given: false });
        assert!(!r.over);
        let event = r.kinds.iter().find(|k| k.name == "event").unwrap();
        assert_eq!(event.level(), Level::Warning);
        // A phone three times slower: B's 0.7 s becomes 2.3 s, over the 2 s.
        let r = Report::of(&o, Speed { factor: 0.3, given: true });
        assert!(r.over);
        let text = r.text(&o, Path::new("."), false);
        assert!(text.contains("divided by --device-speed 0.3"), "{text}");
        assert!(text.contains("click button:B took 117% of the event time limit"), "{text}");
    }

    #[test]
    fn a_stopped_phase_is_over() {
        let o = Outcome { steps: vec![step("click button:Spin", "event", 6_000_000, 6_000_000, 50_000, 2000, Some("steps"))], loaded: true, ..Outcome::default() };
        let r = Report::of(&o, Speed { factor: 1.0, given: false });
        assert!(r.over);
        assert_eq!(r.json(&o)["phases"]["event"]["level"], "over");
        assert_eq!(count(6_000_000), "6M");
        assert_eq!(count(45_000), "45k");
        assert_eq!(time(1500.0), "1.5 ms");
    }
}
