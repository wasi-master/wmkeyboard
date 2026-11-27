//! PEP 508 requirements (`name[extra]>=1.0; python_version < "3.13"`) and
//! environment markers, evaluated for a generic CPython on Linux of the
//! version the keyboard's Python reports ([wm_python_pip::python_version]):
//! the keyboard's Python is neither, so the markers only decide which of a
//! package's dependencies that interpreter would install.

use std::sync::OnceLock;

use super::version::{normalize, Specifier, Version};

/// `("3.12", "3.12.0")`: the keyboard's Python version, short and full.
pub fn python_version() -> &'static (String, String) {
    static V: OnceLock<(String, String)> = OnceLock::new();
    V.get_or_init(|| {
        let (a, b, c) = wm_python_pip::python_version();
        (format!("{a}.{b}"), format!("{a}.{b}.{c}"))
    })
}

/// The environment markers are read against.
fn env(var: &str) -> Option<&'static str> {
    Some(match var {
        "python_version" => python_version().0.as_str(),
        "python_full_version" | "implementation_version" => python_version().1.as_str(),
        "os_name" => "posix",
        "sys_platform" => "linux",
        "platform_system" => "Linux",
        "platform_machine" => "aarch64",
        "platform_release" | "platform_version" => "",
        "platform_python_implementation" => "CPython",
        "implementation_name" => "cpython",
        _ => return None,
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Requirement {
    /// As written.
    pub name: String,
    pub extras: Vec<String>,
    pub specifier: Specifier,
    /// The text after `;`, if any.
    pub marker: Option<String>,
    /// `name @ url`: refused, nothing is fetched from elsewhere.
    pub url: Option<String>,
}

impl Requirement {
    pub fn key(&self) -> String {
        normalize(&self.name)
    }

    pub fn parse(text: &str) -> Result<Requirement, String> {
        let (main, marker) = match text.split_once(';') {
            Some((a, m)) => (a.trim(), Some(m.trim().to_string()).filter(|m| !m.is_empty())),
            None => (text.trim(), None),
        };
        let end = main.find(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))).unwrap_or(main.len());
        let name = main[..end].to_string();
        if name.is_empty() || !name.starts_with(|c: char| c.is_ascii_alphanumeric()) {
            return Err(format!("'{text}' does not start with a package name"));
        }
        let mut rest = main[end..].trim();
        let mut extras = Vec::new();
        if let Some(r) = rest.strip_prefix('[') {
            let Some((inside, after)) = r.split_once(']') else { return Err(format!("'{text}': an unclosed [")) };
            extras = inside.split(',').map(|e| normalize(e)).filter(|e| !e.is_empty()).collect();
            rest = after.trim();
        }
        if let Some(url) = rest.strip_prefix('@') {
            return Ok(Requirement { name, extras, specifier: Specifier::default(), marker, url: Some(url.trim().to_string()) });
        }
        let spec = rest.trim().trim_start_matches('(').trim_end_matches(')');
        let specifier = Specifier::parse(spec)?;
        Ok(Requirement { name, extras, specifier, marker, url: None })
    }

    /// Whether it applies to the keyboard's Python when the package was asked for with [extras].
    pub fn applies(&self, extras: &[String]) -> Result<bool, String> {
        let Some(m) = &self.marker else { return Ok(true) };
        let expr = parse_marker(m)?;
        if eval(&expr, "") {
            return Ok(true);
        }
        Ok(extras.iter().any(|e| eval(&expr, e)))
    }
}

impl std::fmt::Display for Requirement {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.name)?;
        if !self.extras.is_empty() {
            write!(f, "[{}]", self.extras.join(","))?;
        }
        write!(f, "{}", self.specifier)
    }
}

#[derive(Debug)]
enum Marker {
    And(Box<Marker>, Box<Marker>),
    Or(Box<Marker>, Box<Marker>),
    Cmp(Value, String, Value),
}

#[derive(Debug)]
enum Value {
    Var(String),
    Str(String),
}

fn tokens(text: &str) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
        } else if c == '(' || c == ')' {
            out.push(c.to_string());
            i += 1;
        } else if c == '"' || c == '\'' {
            let start = i;
            i += 1;
            while i < chars.len() && chars[i] != c {
                i += 1;
            }
            if i >= chars.len() {
                return Err(format!("an unclosed string in '{text}'"));
            }
            i += 1;
            out.push(chars[start..i].iter().collect());
        } else if "<>=!~".contains(c) {
            let start = i;
            while i < chars.len() && "<>=!~".contains(chars[i]) {
                i += 1;
            }
            out.push(chars[start..i].iter().collect());
        } else if c.is_alphanumeric() || c == '_' || c == '.' {
            let start = i;
            while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '_' || chars[i] == '.') {
                i += 1;
            }
            out.push(chars[start..i].iter().collect());
        } else {
            return Err(format!("'{c}' in the marker '{text}'"));
        }
    }
    Ok(out)
}

fn parse_marker(text: &str) -> Result<Marker, String> {
    let t = tokens(text)?;
    let mut pos = 0;
    let m = or(&t, &mut pos)?;
    if pos != t.len() {
        return Err(format!("could not read the marker '{text}'"));
    }
    Ok(m)
}

fn or(t: &[String], pos: &mut usize) -> Result<Marker, String> {
    let mut left = and(t, pos)?;
    while t.get(*pos).map(String::as_str) == Some("or") {
        *pos += 1;
        left = Marker::Or(Box::new(left), Box::new(and(t, pos)?));
    }
    Ok(left)
}

fn and(t: &[String], pos: &mut usize) -> Result<Marker, String> {
    let mut left = atom(t, pos)?;
    while t.get(*pos).map(String::as_str) == Some("and") {
        *pos += 1;
        left = Marker::And(Box::new(left), Box::new(atom(t, pos)?));
    }
    Ok(left)
}

fn value(t: &[String], pos: &mut usize) -> Result<Value, String> {
    let tok = t.get(*pos).ok_or("a marker ends too soon")?;
    *pos += 1;
    if tok.starts_with('"') || tok.starts_with('\'') {
        Ok(Value::Str(tok[1..tok.len() - 1].to_string()))
    } else {
        Ok(Value::Var(tok.clone()))
    }
}

fn atom(t: &[String], pos: &mut usize) -> Result<Marker, String> {
    if t.get(*pos).map(String::as_str) == Some("(") {
        *pos += 1;
        let m = or(t, pos)?;
        if t.get(*pos).map(String::as_str) != Some(")") {
            return Err("an unclosed ( in a marker".into());
        }
        *pos += 1;
        return Ok(m);
    }
    let left = value(t, pos)?;
    let mut op = t.get(*pos).ok_or("a marker ends too soon")?.clone();
    *pos += 1;
    if op == "not" {
        if t.get(*pos).map(String::as_str) != Some("in") {
            return Err("'not' without 'in' in a marker".into());
        }
        *pos += 1;
        op = "not in".into();
    }
    let right = value(t, pos)?;
    Ok(Marker::Cmp(left, op, right))
}

fn eval(m: &Marker, extra: &str) -> bool {
    match m {
        Marker::And(a, b) => eval(a, extra) && eval(b, extra),
        Marker::Or(a, b) => eval(a, extra) || eval(b, extra),
        Marker::Cmp(l, op, r) => {
            let is_extra = matches!(l, Value::Var(v) if v == "extra") || matches!(r, Value::Var(v) if v == "extra");
            let get = |v: &Value| match v {
                Value::Str(s) => s.clone(),
                Value::Var(name) if name == "extra" => extra.to_string(),
                Value::Var(name) => env(name).unwrap_or("").to_string(),
            };
            let (mut a, mut b) = (get(l), get(r));
            if is_extra {
                a = normalize(&a);
                b = normalize(&b);
            }
            compare(&a, op, &b)
        }
    }
}

fn compare(a: &str, op: &str, b: &str) -> bool {
    match op {
        "in" => b.contains(a),
        "not in" => !b.contains(a),
        _ => {
            if let (Some(va), Some(_)) = (Version::parse(a), Version::parse(b)) {
                if let Ok(spec) = Specifier::parse(&format!("{op}{b}")) {
                    return spec.contains(&va);
                }
            }
            match op {
                "==" | "===" => a == b,
                "!=" => a != b,
                _ => false,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requirements_and_markers() {
        let r = Requirement::parse("Requests[socks, security] (>=2.0,<3) ; python_version >= '3.8'").unwrap();
        assert_eq!((r.key().as_str(), r.extras.len(), r.specifier.to_string().as_str()), ("requests", 2, ">=2.0,<3"));
        assert!(r.applies(&[]).unwrap());
        assert!(!Requirement::parse("colorama; sys_platform == 'win32'").unwrap().applies(&[]).unwrap());
        assert!(!Requirement::parse("typing-extensions; python_version < \"3.8\"").unwrap().applies(&[]).unwrap());
        let tz = Requirement::parse("tzdata; extra == 'TZ' or python_version < '3.9'").unwrap();
        assert!(!tz.applies(&[]).unwrap() && tz.applies(&["tz".into()]).unwrap());
        assert!(Requirement::parse("six>=1.5").unwrap().applies(&[]).unwrap());
        assert!(Requirement::parse("x @ https://example.com/x.whl").unwrap().url.is_some());
        let both = Requirement::parse("a; (os_name == 'posix' and implementation_name == 'cpython') and platform_python_implementation != 'PyPy'").unwrap();
        assert!(both.applies(&[]).unwrap());
    }
}
