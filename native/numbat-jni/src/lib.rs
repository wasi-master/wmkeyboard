//! JNI bridge exposing the Numbat calculator language to WM Keyboard.
//!
//! Kotlin side: `com.wasimaster.wmkeyboard.core.tools.NumbatNative`, reached
//! through `com.wasimaster.wmkeyboard.core.tools.Numbat`. Three entry points:
//!
//! - `nativeEvaluate(expression, digits)` evaluates one expression and returns
//!   a small JSON object (see [`evaluate_json`]);
//! - `nativeSetRates(json)` replaces the exchange rates the currency units are
//!   built from, as units per US dollar, the table `CurrencyClient` keeps;
//! - `nativeWarmUp()` loads the prelude ahead of the first expression.
//!
//! Numbat's own exchange-rate fetching is compiled out (`default-features =
//! false`), and its rate cache can be filled only once per process, so the
//! currency units are not built from it. Instead [`RatesImporter`] serves
//! `units::currencies` with every `exchange_rate("XYZ")` call replaced by the
//! rate our table holds, and a fresh copy of the prelude context imports it
//! whenever the table changes. Currencies Numbat's module does not list (the
//! taka, the rupee's neighbours, coins) are added after it under their codes.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock, RwLock};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use numbat::module_importer::{BuiltinModuleImporter, ModuleImporter};
use numbat::resolver::{CodeSource, ModulePath, ResolverError};
use numbat::value::Value;
use numbat::{Context, FormatOptions, InterpreterResult, InterpreterSettings, NumbatError, TypeCheckError};
use serde_json::json;

/// Longest expression accepted. The suggestion strip reads 48 characters and
/// the calculator's display holds one line; anything past this is not a sum.
const MAX_EXPRESSION: usize = 200;

/// Recursive prelude functions, list builders and private helpers: inputs
/// that can run for as long as they like inside the keyboard's process, with
/// no way to stop them. Numbat has no loops, so these (and `fn`, refused
/// below) are the only ways to make it work without end.
const DENIED: &[&str] = &[
    "range", "linspace", "map", "map2", "filter", "foldl", "sort", "sort_by_key", "unique", "contains",
    "intersperse", "join", "split", "concat", "take", "drop", "element_at", "reverse", "cons", "cons_end",
    "str_replace", "str_repeat", "falling_factorial", "binom", "fibonacci", "lucas", "catalan", "rand_binom",
    "rand_poisson", "dsolve", "fixed_point", "root_bisect", "root_newton", "diff", "maximum", "minimum",
    "mean", "variance", "stdev", "median", "sum", "read", "print", "assert", "assert_eq", "error",
];

/// Words that begin a statement rather than an expression. The context
/// outlives every call, so a definition would stay behind in it.
const STATEMENT_KEYWORDS: &[&str] = &["let", "fn", "unit", "dimension", "use", "struct", "print", "assert", "assert_eq"];

/// Units of each currency per euro, the shape Numbat's currency module wants.
static RATES: RwLock<Option<HashMap<String, f64>>> = RwLock::new(None);

/// The prelude, loaded once; every currency table starts from a copy of it.
static BASE: OnceLock<Context> = OnceLock::new();

/// The context expressions run in: [BASE] plus the current currencies.
static CURRENT: Mutex<Option<Context>> = Mutex::new(None);

/// Expressions run in [CURRENT] since it was built. Numbat keeps the source of
/// every one (for its error messages) and sets `ans` after each, so the
/// context is rebuilt from [BASE] every [REBUILD_AFTER] of them rather than
/// growing for the life of the keyboard.
static RUN: Mutex<usize> = Mutex::new(0);
const REBUILD_AFTER: usize = 256;

/// Numbat's built-in modules, with `units::currencies` rewritten from [RATES].
struct RatesImporter {
    builtin: BuiltinModuleImporter,
}

impl ModuleImporter for RatesImporter {
    fn import(&self, path: &ModulePath) -> Option<(String, Option<PathBuf>)> {
        let (code, file) = self.builtin.import(path)?;
        if path.0.len() == 2 && path.0[0] == "units" && path.0[1] == "currencies" {
            let rates = RATES.read().ok()?;
            return Some((rewrite_currencies(&code, rates.as_ref()?), file));
        }
        Some((code, file))
    }

    fn list_modules(&self) -> Vec<ModulePath> {
        self.builtin.list_modules()
    }
}

/// Numbat's `units::currencies` with each rate written in: a unit whose rate
/// the table lacks is left out, rather than defined as NaN euros. The
/// `exchange_rate` declaration goes too, since nothing calls it any more.
fn rewrite_currencies(code: &str, rates: &HashMap<String, f64>) -> String {
    let mut out = String::with_capacity(code.len());
    for block in code.split("\n\n") {
        if block.trim_start().starts_with("fn exchange_rate") {
            continue;
        }
        let Some(start) = block.find("exchange_rate(\"") else {
            out.push_str(block);
            out.push_str("\n\n");
            continue;
        };
        let rest = &block[start + "exchange_rate(\"".len()..];
        let Some(end) = rest.find("\")") else { continue };
        let code_name = &rest[..end];
        let Some(rate) = rates.get(code_name) else { continue };
        out.push_str(&block[..start]);
        out.push_str(&number(*rate));
        out.push_str(&rest[end + 2..]);
        out.push_str("\n\n");
    }
    out
}

/// A rate as a Numbat literal: plain decimals, which Numbat reads exactly as
/// written, and an exponent only where f64's own formatting would need one.
fn number(value: f64) -> String {
    let text = format!("{value}");
    if text.contains('e') || text.contains("inf") || text.contains("NaN") {
        format!("{value:e}")
    } else {
        text
    }
}

/// An ISO-style code, the only names [RatesImporter] adds on its own.
fn is_code(name: &str) -> bool {
    (2..=6).contains(&name.len()) && name.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
        && name.chars().next().is_some_and(|c| c.is_ascii_uppercase())
}

fn quiet() -> InterpreterSettings {
    InterpreterSettings { print_fn: Box::new(|_| {}) }
}

fn base() -> &'static Context {
    BASE.get_or_init(|| {
        let mut context = Context::new(RatesImporter { builtin: BuiltinModuleImporter::default() });
        // The prelude is compiled into the library, so this cannot fail on a
        // build that passed its own tests.
        let _ = context.interpret_with_settings(&mut quiet(), "use prelude", CodeSource::Internal);
        context
    })
}

/// A copy of the prelude with the current currencies in it, or the prelude
/// alone before any rates arrived.
fn build_context() -> Context {
    let mut context = base().clone();
    let rates = RATES.read().ok().and_then(|guard| guard.clone());
    let Some(rates) = rates else { return context };
    if rates.get("EUR").is_none() {
        return context;
    }
    let _ = context.interpret_with_settings(&mut quiet(), "use units::currencies", CodeSource::Internal);
    let known: Vec<String> = context.unit_names().iter().flatten().map(|name| name.to_string()).collect();
    let mut codes: Vec<&String> = rates.keys().filter(|code| is_code(code) && !known.iter().any(|k| k == *code)).collect();
    codes.sort();
    for code in codes {
        let rate = rates[code];
        if !rate.is_finite() || rate <= 0.0 {
            continue;
        }
        // One statement each, so a code that clashes with an identifier the
        // prelude already has (a unit, a constant) is skipped on its own.
        let definition = format!("@aliases({code}: short)\nunit currency_{}: Money = EUR / {}", code.to_lowercase(), number(rate));
        let _ = context.interpret_with_settings(&mut quiet(), &definition, CodeSource::Internal);
    }
    context
}

/// Replaces the rates: [per_usd] is units of each code per US dollar.
fn set_rates(per_usd: HashMap<String, f64>) -> bool {
    let Some(eur) = per_usd.get("EUR").copied().filter(|r| r.is_finite() && *r > 0.0) else {
        return false;
    };
    let per_eur: HashMap<String, f64> = per_usd
        .into_iter()
        .filter(|(_, rate)| rate.is_finite() && *rate > 0.0)
        .map(|(code, rate)| (code, rate / eur))
        .collect();
    match RATES.write() {
        Ok(mut guard) => *guard = Some(per_eur),
        Err(_) => return false,
    }
    let fresh = build_context();
    match CURRENT.lock() {
        Ok(mut guard) => {
            *guard = Some(fresh);
            true
        }
        Err(_) => false,
    }
}

/// Why an input is refused before Numbat sees it, or None when it may run.
fn refusal(expression: &str) -> Option<&'static str> {
    if expression.is_empty() {
        return Some("empty");
    }
    if expression.len() > MAX_EXPRESSION || expression.contains(['\n', '\r', ';', '[', '{', '#']) {
        return Some("refused");
    }
    // Numbat has no °C or °F unit (temperatures go through from_celsius and
    // friends), so "20 °C -> °F" reads as degrees times coulombs and answers
    // nonsense. The unit converter does temperatures; refuse them here.
    if ["°C", "°F", "℃", "℉"].iter().any(|t| expression.contains(t)) {
        return Some("temperature");
    }
    let first = expression.trim_start().split(|c: char| !c.is_alphanumeric() && c != '_').next().unwrap_or("");
    if STATEMENT_KEYWORDS.contains(&first) || expression.trim_start().starts_with('@') {
        return Some("refused");
    }
    let mut word = String::new();
    for c in expression.chars().chain(std::iter::once(' ')) {
        if c.is_alphanumeric() || c == '_' {
            word.push(c);
            continue;
        }
        if !word.is_empty() {
            if word.starts_with('_') || DENIED.contains(&word.as_str()) || STATEMENT_KEYWORDS[..5].contains(&word.as_str()) {
                return Some("refused");
            }
            word.clear();
        }
    }
    None
}

/// Evaluates [expression] and describes the outcome as JSON:
///
/// - `{"ok":true,"text":"5.5 km","kind":"quantity","value":5.5,"unit":"km"}`;
///   `value` and `unit` only for a quantity, `unit` "" when it is a number;
///   `kind` is also `datetime`, `string`, `boolean` or `other`;
/// - `{"ok":false,"code":"...","detail":"...","message":"..."}`, with `code`
///   one of `refused`, `temperature`, `empty`, `parse`, `unknown` (`detail` names the
///   identifier), `dimensions`, `type`, `runtime`; `message` is Numbat's
///   English wording, for logs.
fn evaluate_json(expression: &str, digits: usize) -> String {
    let expression = expression.trim();
    if let Some(code) = refusal(expression) {
        return json!({ "ok": false, "code": code }).to_string();
    }
    let mut guard = match CURRENT.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if let Ok(mut run) = RUN.lock() {
        *run += 1;
        if *run >= REBUILD_AFTER {
            *run = 0;
            *guard = None;
        }
    }
    let context = guard.get_or_insert_with(build_context);
    let result = context.interpret_with_settings(&mut quiet(), expression, CodeSource::Text);
    match result {
        Ok((_, InterpreterResult::Value(value))) => {
            let options = FormatOptions {
                digit_separator: String::new(),
                significant_digits: digits.clamp(3, 15),
                ..FormatOptions::default()
            };
            let text = value.pretty_print_with(&options).to_string().trim().to_string();
            match &value {
                Value::Quantity(q) => {
                    let unit = q.unit().to_string();
                    json!({
                        "ok": true, "text": text, "kind": "quantity",
                        "value": q.unsafe_value().to_f64(), "unit": unit.trim(),
                    })
                }
                Value::DateTime(_) => json!({ "ok": true, "text": text, "kind": "datetime" }),
                Value::String(s) => json!({ "ok": true, "text": s.to_string(), "kind": "string" }),
                Value::Boolean(_) => json!({ "ok": true, "text": text, "kind": "boolean" }),
                _ => json!({ "ok": true, "text": text, "kind": "other" }),
            }
            .to_string()
        }
        Ok((_, InterpreterResult::Continue)) => json!({ "ok": false, "code": "empty" }).to_string(),
        Err(error) => {
            let message = error.to_string();
            let (code, detail) = match error.as_ref() {
                NumbatError::ResolverError(ResolverError::ParseErrors(_)) => ("parse", String::new()),
                NumbatError::ResolverError(_) => ("unknown", String::new()),
                NumbatError::NameResolutionError(_) => ("parse", String::new()),
                NumbatError::TypeCheckError(TypeCheckError::UnknownIdentifier(_, name, _)) => ("unknown", name.clone()),
                NumbatError::TypeCheckError(TypeCheckError::IncompatibleDimensions(_)) => ("dimensions", String::new()),
                NumbatError::TypeCheckError(_) => ("type", String::new()),
                NumbatError::RuntimeError(_) => ("runtime", String::new()),
            };
            json!({ "ok": false, "code": code, "detail": detail, "message": message }).to_string()
        }
    }
}

fn new_string(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_tools_NumbatNative_nativeEvaluate(
    mut env: JNIEnv,
    _class: JClass,
    expression: JString,
    digits: jint,
) -> jstring {
    let expression: String = match env.get_string(&expression) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let out = evaluate_json(&expression, digits.max(0) as usize);
    new_string(&mut env, &out)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_tools_NumbatNative_nativeSetRates(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jboolean {
    let text: String = match env.get_string(&json) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let Ok(table) = serde_json::from_str::<HashMap<String, f64>>(&text) else { return JNI_FALSE };
    if set_rates(table) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_tools_NumbatNative_nativeWarmUp(_env: JNIEnv, _class: JClass) {
    let mut guard = match CURRENT.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.get_or_insert_with(build_context);
}

/// The rate tests swap the process-wide table, so they take turns.
#[cfg(test)]
static RATES_TESTS: Mutex<()> = Mutex::new(());

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(expression: &str) -> serde_json::Value {
        let out: serde_json::Value = serde_json::from_str(&evaluate_json(expression, 6)).unwrap();
        assert_eq!(out["ok"], true, "{expression}: {out}");
        out
    }

    fn failed(expression: &str) -> String {
        let out: serde_json::Value = serde_json::from_str(&evaluate_json(expression, 6)).unwrap();
        assert_eq!(out["ok"], false, "{expression}: {out}");
        out["code"].as_str().unwrap().to_string()
    }

    #[test]
    fn units_inside_expressions() {
        assert_eq!(ok("5 ft + 30 cm -> m")["text"], "1.824 m");
        assert_eq!(ok("3 km/h * 20 min")["unit"], "km");
        let walked = ok("3 km/h * 20 min -> m");
        assert!((walked["value"].as_f64().unwrap() - 1000.0).abs() < 1e-6, "{walked}");
        assert_eq!(ok("2 + 3")["unit"], "");
    }

    #[test]
    fn errors_are_classified() {
        assert_eq!(failed("3 m + 2 s"), "dimensions");
        assert_eq!(failed("3 flurbs"), "unknown");
        assert_eq!(failed("let x = 3"), "refused");
        assert_eq!(failed("range(1, 100000000)"), "refused");
        assert_eq!(failed("fn f(x) = f(x)"), "refused");
        assert_eq!(failed("_merge([], [], id)"), "refused");
        assert_eq!(failed("20 °C -> °F"), "temperature");
    }

    #[test]
    fn currencies_come_from_our_table() {
        let _turn = RATES_TESTS.lock().unwrap_or_else(|p| p.into_inner());
        let mut table = HashMap::new();
        table.insert("USD".to_string(), 1.0);
        table.insert("EUR".to_string(), 0.5);
        table.insert("BDT".to_string(), 100.0);
        table.insert("GBP".to_string(), 0.25);
        assert!(set_rates(table));
        let usd = ok("10 USD -> EUR");
        assert!((usd["value"].as_f64().unwrap() - 5.0).abs() < 1e-9, "{usd}");
        let taka = ok("2 USD -> BDT");
        assert!((taka["value"].as_f64().unwrap() - 200.0).abs() < 1e-9, "{taka}");
        // A currency Numbat lists but our table lacks is simply not there.
        assert_eq!(failed("1 JPY"), "unknown");
        // New rates replace the old ones.
        let mut table = HashMap::new();
        table.insert("USD".to_string(), 1.0);
        table.insert("EUR".to_string(), 0.8);
        assert!(set_rates(table));
        let usd = ok("10 USD -> EUR");
        assert!((usd["value"].as_f64().unwrap() - 8.0).abs() < 1e-9, "{usd}");
    }
}

#[cfg(test)]
mod timing {
    use super::*;
    use std::time::Instant;

    /// `cargo test --release -- --ignored --nocapture timing`: what the first
    /// expression, a rate change and a warm expression cost on this machine.
    #[test]
    #[ignore]
    fn timing() {
        let t = Instant::now();
        let _ = base();
        println!("prelude: {:?}", t.elapsed());
        let t = Instant::now();
        let _ = base().clone();
        println!("clone: {:?}", t.elapsed());
        let mut table = HashMap::new();
        for (i, code) in ["USD", "EUR", "BDT", "GBP", "INR", "JPY", "AED", "SAR", "BTC"].iter().enumerate() {
            table.insert(code.to_string(), 1.0 + i as f64);
        }
        let t = Instant::now();
        set_rates(table);
        println!("rates: {:?}", t.elapsed());
        let t = Instant::now();
        for _ in 0..1000 {
            evaluate_json("5 ft + 30 cm -> m", 8);
        }
        println!("eval: {:?} each", t.elapsed() / 1000);
    }
}

#[cfg(test)]
mod samples {
    /// `cargo test -- --ignored --nocapture samples`: what a few inputs read as.
    #[test]
    #[ignore]
    fn samples() {
        for e in [
            "5 ft + 30 cm -> m", "3 km/h * 20 min", "100 km/h to mph", "1 lightyear / c -> years", "c * 2 s -> km",
            "now() + 3 weeks", "date(\"2026-12-25\") - now() -> days", "60 mph * 2.5 h", "2 kg * 9.81 m/s^2",
            "500 W * 3 h -> kWh", "1 GiB / (10 Mbit/s) -> s", "70 kg * g0 * 3 m -> kcal", "20 °C -> °F", "5 ft 3 in",
            "5 feet + 3 inches", "1 mile in cm", "3 apples + 2", "hello world", "10 min to go", "I ran 5 km - it was hard",
            "2 days in a row", "3 days in 2 weeks", "3 m × 2", "6 m ÷ 2", "6 m − 2 m", "5 ft + 30 cm → m", "√(4 m²)",
            "5 ft + 30 cm to m", "1 h + 30 min", "2 h 30 min", "10 USD * 3", "1 lb -> kg", "1,000 m + 2 km", "1.5 cups to mL", "3 tbsp to tsp", "12 * 4", "2 pi", "5e", "50+10%",
        ] {
            println!("{e:40} {}", super::evaluate_json(e, 6));
        }
    }
}

#[cfg(test)]
mod doc_claims {
    use super::*;

    /// The examples the docs pages quote (tools/utilities.mdx, smart/chips.mdx).
    #[test]
    fn doc_claims() {
        let _turn = RATES_TESTS.lock().unwrap_or_else(|p| p.into_inner());
        let mut table = HashMap::new();
        table.insert("USD".to_string(), 1.0);
        table.insert("EUR".to_string(), 0.9);
        table.insert("BDT".to_string(), 120.0);
        assert!(set_rates(table));
        let taka = evaluate_json("10 USD * 3 -> BDT", 6);
        assert!(taka.contains("\"text\":\"3600 BDT\""), "{taka}");
        for e in ["3 feet", "3 foot", "3 ft"] {
            assert!(evaluate_json(&format!("{e} -> ft"), 6).contains("\"text\":\"3 ft\""), "{e}");
        }
        assert!(evaluate_json("1 h + 30 min", 6).contains("\"text\":\"90 min\""));
        assert!(evaluate_json("3 km/h * 20 min", 6).contains("\"unit\":\"km\""));
        assert!(evaluate_json("3 m + 2 s", 6).contains("dimensions"));
    }
}
