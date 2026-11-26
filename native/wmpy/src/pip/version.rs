//! PEP 440 versions and version specifiers, as far as choosing a release
//! from PyPI needs: parsing, ordering, `==`, `!=`, `<`, `<=`, `>`, `>=`,
//! `~=`, `===` and `.*` wildcards. Names are compared by PEP 503's rule.

use std::cmp::Ordering;

/// A project name in PEP 503 form: lowercase, runs of `-`, `_` and `.` as one `-`.
pub fn normalize(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    let mut dash = false;
    for c in name.trim().chars() {
        if matches!(c, '-' | '_' | '.') {
            dash = true;
        } else {
            if dash && !out.is_empty() {
                out.push('-');
            }
            dash = false;
            out.extend(c.to_lowercase());
        }
    }
    out
}

#[derive(Clone, Debug)]
pub struct Version {
    pub epoch: u64,
    pub release: Vec<u64>,
    /// `a` = 0, `b` = 1, `rc` = 2.
    pub pre: Option<(u8, u64)>,
    pub post: Option<u64>,
    pub dev: Option<u64>,
    pub local: Vec<String>,
}

impl Version {
    pub fn parse(text: &str) -> Option<Version> {
        let s = text.trim().to_ascii_lowercase();
        let s = s.strip_prefix('v').unwrap_or(&s);
        let (s, local) = match s.split_once('+') {
            Some((a, l)) => (a, l.split(['.', '-', '_']).map(String::from).collect::<Vec<_>>()),
            None => (s, Vec::new()),
        };
        if local.iter().any(|p| p.is_empty() || !p.chars().all(|c| c.is_ascii_alphanumeric())) {
            return None;
        }
        let (epoch, s) = match s.split_once('!') {
            Some((e, rest)) => (e.parse().ok()?, rest),
            None => (0, s),
        };
        let b = s.as_bytes();
        let mut i = 0;
        let number = |i: &mut usize| -> Option<u64> {
            let start = *i;
            while *i < b.len() && b[*i].is_ascii_digit() {
                *i += 1;
            }
            if start == *i {
                None
            } else {
                s[start..*i].parse().ok()
            }
        };
        let mut release = vec![number(&mut i)?];
        while i + 1 < b.len() && b[i] == b'.' && b[i + 1].is_ascii_digit() {
            i += 1;
            release.push(number(&mut i)?);
        }
        let sep = |i: &mut usize| {
            if *i < b.len() && matches!(b[*i], b'.' | b'-' | b'_') {
                *i += 1;
            }
        };
        let word = |i: &mut usize, words: &[&str]| -> Option<String> {
            for w in words {
                if s[*i..].starts_with(w) {
                    *i += w.len();
                    return Some(w.to_string());
                }
            }
            None
        };
        let mut pre = None;
        let save = i;
        sep(&mut i);
        if let Some(w) = word(&mut i, &["alpha", "beta", "preview", "pre", "rc", "a", "b", "c"]) {
            sep(&mut i);
            let n = number(&mut i).unwrap_or(0);
            let kind = match w.as_str() {
                "a" | "alpha" => 0,
                "b" | "beta" => 1,
                _ => 2,
            };
            pre = Some((kind, n));
        } else {
            i = save;
        }
        let mut post = None;
        let save = i;
        if i < b.len() && b[i] == b'-' && b.get(i + 1).is_some_and(|c| c.is_ascii_digit()) {
            i += 1;
            post = number(&mut i);
        } else {
            sep(&mut i);
            if word(&mut i, &["post", "rev", "r"]).is_some() {
                sep(&mut i);
                post = Some(number(&mut i).unwrap_or(0));
            } else {
                i = save;
            }
        }
        let mut dev = None;
        let save = i;
        sep(&mut i);
        if word(&mut i, &["dev"]).is_some() {
            sep(&mut i);
            dev = Some(number(&mut i).unwrap_or(0));
        } else {
            i = save;
        }
        (i == b.len()).then_some(Version { epoch, release, pre, post, dev, local })
    }

    /// A pre-release or a development release.
    pub fn is_pre(&self) -> bool {
        self.pre.is_some() || self.dev.is_some()
    }

    fn release_at(&self, i: usize) -> u64 {
        self.release.get(i).copied().unwrap_or(0)
    }

    /// The key packaging sorts by: dev-only releases before pre-releases,
    /// pre-releases before the release, post-releases after it.
    fn key(&self) -> (u64, Vec<u64>, (u8, u8, u64), (u8, u64), (u8, u64)) {
        let mut release = self.release.clone();
        while release.len() > 1 && release.last() == Some(&0) {
            release.pop();
        }
        let pre = match (self.pre, self.post, self.dev) {
            (None, None, Some(_)) => (0, 0, 0),
            (Some((k, n)), _, _) => (1, k, n),
            (None, _, _) => (2, 0, 0),
        };
        let post = match self.post {
            None => (0, 0),
            Some(n) => (1, n),
        };
        let dev = match self.dev {
            Some(n) => (0, n),
            None => (1, 0),
        };
        (self.epoch, release, pre, post, dev)
    }

    fn base(&self) -> (u64, Vec<u64>) {
        let mut release = self.release.clone();
        while release.len() > 1 && release.last() == Some(&0) {
            release.pop();
        }
        (self.epoch, release)
    }
}

impl PartialEq for Version {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}

impl Eq for Version {}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> Ordering {
        let max = self.release.len().max(other.release.len());
        self.epoch
            .cmp(&other.epoch)
            .then_with(|| (0..max).map(|i| self.release_at(i).cmp(&other.release_at(i))).find(|o| o.is_ne()).unwrap_or(Ordering::Equal))
            .then_with(|| {
                let (a, b) = (self.key(), other.key());
                (a.2, a.3, a.4).cmp(&(b.2, b.3, b.4))
            })
            .then_with(|| self.local.cmp(&other.local))
    }
}

/// One clause of a specifier set: `>=1.2`, `==2.*`, `~=1.4.2`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Clause {
    pub op: String,
    pub version: String,
}

/// A comma-separated specifier set; empty allows every version.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Specifier {
    pub clauses: Vec<Clause>,
}

impl Specifier {
    pub fn parse(text: &str) -> Result<Specifier, String> {
        let mut clauses = Vec::new();
        for part in text.split(',') {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }
            let op_len = ["===", "~=", "==", "!=", "<=", ">=", "<", ">"].iter().find(|o| part.starts_with(**o)).map(|o| o.len());
            let Some(n) = op_len else { return Err(format!("'{part}' is not a version specifier")) };
            let version = part[n..].trim().to_string();
            let op = part[..n].to_string();
            let check = version.strip_suffix(".*").unwrap_or(&version);
            if op != "===" && Version::parse(check).is_none() {
                return Err(format!("'{version}' is not a version"));
            }
            clauses.push(Clause { op, version });
        }
        Ok(Specifier { clauses })
    }

    pub fn is_empty(&self) -> bool {
        self.clauses.is_empty()
    }

    /// Whether any clause names a pre-release, which lets pre-releases match.
    pub fn mentions_pre(&self) -> bool {
        self.clauses.iter().any(|c| Version::parse(c.version.trim_end_matches(".*")).is_some_and(|v| v.is_pre()))
    }

    pub fn contains(&self, v: &Version) -> bool {
        self.clauses.iter().all(|c| clause_contains(c, v))
    }
}

impl std::fmt::Display for Specifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let parts: Vec<String> = self.clauses.iter().map(|c| format!("{}{}", c.op, c.version)).collect();
        write!(f, "{}", parts.join(","))
    }
}

fn prefix_match(v: &Version, prefix: &Version) -> bool {
    if v.epoch != prefix.epoch {
        return false;
    }
    (0..prefix.release.len()).all(|i| v.release_at(i) == prefix.release[i])
}

fn clause_contains(c: &Clause, v: &Version) -> bool {
    if c.op == "===" {
        return false;
    }
    if let Some(prefix) = c.version.strip_suffix(".*") {
        let Some(p) = Version::parse(prefix) else { return false };
        return match c.op.as_str() {
            "==" => prefix_match(v, &p),
            "!=" => !prefix_match(v, &p),
            _ => false,
        };
    }
    let Some(spec) = Version::parse(&c.version) else { return false };
    // Without a local part in the specifier, a candidate's local part is ignored.
    let mut cand = v.clone();
    if spec.local.is_empty() {
        cand.local.clear();
    }
    match c.op.as_str() {
        "==" => cand == spec,
        "!=" => cand != spec,
        "<=" => cand <= spec,
        ">=" => cand >= spec,
        "<" => cand < spec && !(cand.is_pre() && !spec.is_pre() && cand.base() == spec.base()),
        ">" => cand > spec && !(cand.post.is_some() && spec.post.is_none() && cand.base() == spec.base() && cand.pre == spec.pre),
        "~=" => {
            if spec.release.len() < 2 {
                return false;
            }
            let mut prefix = spec.clone();
            prefix.release.pop();
            prefix.pre = None;
            prefix.post = None;
            prefix.dev = None;
            cand >= spec && prefix_match(&cand, &prefix)
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &str) -> Version {
        Version::parse(s).unwrap_or_else(|| panic!("{s}"))
    }

    #[test]
    fn versions_sort_as_pep_440_says() {
        let order = ["1.0.dev1", "1.0a1", "1.0a2.dev3", "1.0a2", "1.0b1", "1.0rc1", "1.0", "1.0.post1.dev1", "1.0.post1", "1.1", "2!0.1"];
        for w in order.windows(2) {
            assert!(v(w[0]) < v(w[1]), "{} < {}", w[0], w[1]);
        }
        assert_eq!(v("1.0").cmp(&v("1.0.0")), Ordering::Equal);
        assert_eq!(v("1.0-1"), v("1.0.post1"));
        assert_eq!(v("1.0RC1"), v("1.0rc1"));
        assert!(Version::parse("not a version").is_none());
    }

    #[test]
    fn specifiers() {
        let s = |t: &str| Specifier::parse(t).unwrap();
        assert!(s(">=1.2,<2").contains(&v("1.9.9")));
        assert!(!s(">=1.2,<2").contains(&v("2.0")));
        assert!(!s("<2").contains(&v("2.0a1")));
        assert!(s("==1.4.*").contains(&v("1.4.7")));
        assert!(!s("==1.4.*").contains(&v("1.5")));
        assert!(s("~=2.2").contains(&v("2.9")) && !s("~=2.2").contains(&v("3.0")));
        assert!(s("~=1.4.5").contains(&v("1.4.9")) && !s("~=1.4.5").contains(&v("1.5.0")));
        assert!(s("!=1.0").contains(&v("1.1")));
        assert!(!s(">1.7").contains(&v("1.7.post2")));
        assert!(s("==1.0").contains(&v("1.0+local.7")));
        assert!(Specifier::parse(">=x").is_err());
    }

    #[test]
    fn names_normalize() {
        assert_eq!(normalize("Python_Dateutil"), "python-dateutil");
        assert_eq!(normalize("zope.interface"), "zope-interface");
        assert_eq!(normalize("a--b__c"), "a-b-c");
    }
}
