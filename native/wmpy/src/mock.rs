//! Host calls answered from a mock file, as `wmpy test`, `wmpy render` and
//! the test module's `plugin_test.session(mock=)` answer them.

use std::cell::RefCell;
use std::rc::Rc;

/// The mock behind a session: its answers, every call the plugin made, and
/// the `then=` calls still waiting for [settle](session_value).
pub struct Mock {
    pub answers: serde_json::Value,
    pub calls: RefCell<Vec<serde_json::Value>>,
    /// `(token, op, args)` of each async call not yet answered.
    pub waiting: RefCell<Vec<(String, String, serde_json::Value)>>,
}

impl Mock {
    pub fn new(answers: serde_json::Value) -> Rc<Mock> {
        Rc::new(Mock { answers, calls: RefCell::new(Vec::new()), waiting: RefCell::new(Vec::new()) })
    }

    /// The reply JSON for one call: `{"value": v}` or `{"error": {...}}`.
    ///
    /// An op's answer is the reply itself, or a list of routes
    /// `{"when": {arg: pattern}, "reply": v}` picked by the call's args (the
    /// first whose `when` matches; no `when` matches anything). A reply that
    /// is only `{"error": {"kind", "message"}}` raises that error instead.
    pub fn reply(&self, op: &str, args: &serde_json::Value) -> serde_json::Value {
        let missing = |what: String| serde_json::json!({"error": {"kind": "NotImplementedError", "message": what}});
        let answer = match self.answers.get(op) {
            // The keyboard answers a plugin's own setting write with the value it saved.
            None if op == "settings.set" => return serde_json::json!({"value": args.get("value").cloned().unwrap_or(serde_json::Value::Null)}),
            None => return missing(format!("no mock reply for {op}")),
            Some(v) if is_routes(v) => {
                let routes = v.as_array().map(Vec::as_slice).unwrap_or(&[]);
                match routes.iter().find(|r| r.get("when").is_none_or(|w| matches(w, Some(args)))) {
                    Some(route) => &route["reply"],
                    None => return missing(format!("no mock route for {op} {}", summary(args))),
                }
            }
            Some(v) => v,
        };
        if answer.as_object().is_some_and(|o| o.len() == 1 && o.get("error").is_some_and(|e| e.is_object())) {
            answer.clone()
        } else {
            serde_json::json!({"value": answer})
        }
    }

    pub fn bridge(self: &Rc<Self>) -> wm_python::host::Bridge {
        let mock = self.clone();
        Box::new(move |op: &str, args: &str| {
            let args: serde_json::Value = serde_json::from_str(args).unwrap_or(serde_json::Value::Null);
            mock.calls.borrow_mut().push(serde_json::json!({"op": op, "args": args}));
            if let Some(token) = args.get("async").and_then(|t| t.as_str()) {
                // Answered by settle(), or by the test's own `result` event.
                mock.waiting.borrow_mut().push((token.to_string(), op.to_string(), args.clone()));
                return serde_json::json!({"value": null}).to_string();
            }
            mock.reply(op, &args).to_string()
        })
    }
}

/// Whether an op's mock answer is a list of routes rather than a list reply
/// (such as `sensors.list`'s): every item a dict with a `reply`.
pub fn is_routes(v: &serde_json::Value) -> bool {
    v.as_array().is_some_and(|a| !a.is_empty() && a.iter().all(|r| r.get("reply").is_some()))
}

/// Whether [arg] fits [pattern]: a string exactly, or as a prefix when the
/// pattern ends in `*`; a dict when each of its keys fits; anything else by
/// equality, with `null` also matching an absent arg.
pub fn matches(pattern: &serde_json::Value, arg: Option<&serde_json::Value>) -> bool {
    use serde_json::Value as J;
    match (pattern, arg) {
        (J::Null, None) => true,
        (J::String(p), Some(J::String(a))) => match p.strip_suffix('*') {
            Some(prefix) => a.starts_with(prefix),
            None => a == p,
        },
        (J::Object(p), Some(J::Object(_))) => p.iter().all(|(k, v)| matches(v, arg.and_then(|a| a.get(k)))),
        (p, Some(a)) => p == a,
        _ => false,
    }
}

/// A call's args in a line, for a missing-route message: `GET https://...`
/// for a request, else the JSON.
pub fn summary(args: &serde_json::Value) -> String {
    match (args.get("method").and_then(|m| m.as_str()), args.get("url").and_then(|u| u.as_str())) {
        (Some(m), Some(u)) => format!("{m} {u}"),
        _ => {
            let mut text = args.to_string();
            if text.len() > 200 {
                let mut cut = 200;
                while !text.is_char_boundary(cut) {
                    cut -= 1;
                }
                text.truncate(cut);
                text.push('…');
            }
            text
        }
    }
}

