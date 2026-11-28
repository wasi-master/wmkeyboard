//! A plugin kept running between tool calls: loaded once as the keyboard
//! loads it, then driven event by event, as `plugin_test.session()` drives it
//! in a test, host calls answered from a mock.

use std::path::Path;
use std::rc::Rc;

use serde_json::{json, Value};
use wm_python::host::Session;

use crate::mock::Mock;
use crate::project::{inside, manifest, session_config_in, Context};

/// Most sessions alive at once; the oldest is stopped for a new one.
pub const MAX_SESSIONS: usize = 16;
/// Most rounds [Live::settle] answers before giving up, as `settle()` in a test.
const MAX_SETTLE_ROUNDS: usize = 64;

pub struct Live {
    session: Session,
    mock: Rc<Mock>,
}

fn parse(text: &str) -> Value {
    serde_json::from_str(text).unwrap_or_else(|_| json!({"ok": false, "error": {"message": text}}))
}

impl Live {
    /// The plugin in [dir], loaded in [ctx]. Returns it and the first draw.
    pub fn start(dir: &Path, mock: Value, settings: Value, ctx: &Context) -> Result<(Live, Value), String> {
        let m = manifest(dir)?;
        let path = inside(dir, &m.entry)?;
        let source = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", m.entry))?;
        let config = session_config_in(dir, &m, settings, ctx);
        let mut session = Session::new(&config.to_string())?;
        let mock = Mock::new(if mock.is_object() { mock } else { json!({}) });
        session.set_bridge(Some(mock.bridge()));
        let first = parse(&session.load(&source));
        Ok((Live { session, mock }, first))
    }

    pub fn render(&mut self) -> Value {
        parse(&self.session.render())
    }

    pub fn event(&mut self, event: &Value) -> Value {
        parse(&self.session.event(&event.to_string()))
    }

    pub fn transform(&mut self, text: &str, action: &str) -> Value {
        parse(&self.session.transform(text, action))
    }

    pub fn storage(&self) -> Value {
        parse(&self.session.storage_json())
    }

    /// Every host call so far, `{"op", "args"}`, from [since] on.
    pub fn calls(&self, since: usize) -> Value {
        Value::Array(self.mock.calls.borrow().iter().skip(since).cloned().collect())
    }

    pub fn call_count(&self) -> usize {
        self.mock.calls.borrow().len()
    }

    /// How many `then=` calls wait for an answer.
    pub fn waiting(&self) -> usize {
        self.mock.waiting.borrow().len()
    }

    /// Answer every waiting `then=` call from the mock as its `result` event,
    /// round after round, since an answer may start the next call. The last
    /// result, or the first that failed; None when nothing waited.
    pub fn settle(&mut self) -> Result<Option<Value>, String> {
        let mut last = None;
        let mut first_failure = None;
        for _ in 0..MAX_SETTLE_ROUNDS {
            let waiting = std::mem::take(&mut *self.mock.waiting.borrow_mut());
            if waiting.is_empty() {
                return Ok(first_failure.or(last));
            }
            for (token, op, args) in waiting {
                let reply = self.mock.reply(&op, &args);
                let value = match reply.get("error") {
                    Some(_) => reply,
                    None => reply.get("value").cloned().unwrap_or(Value::Null),
                };
                let result = self.event(&json!({"type": "result", "token": token, "value": value}));
                if result["ok"] == json!(false) && first_failure.is_none() {
                    first_failure = Some(result.clone());
                }
                last = Some(result);
            }
        }
        Err(format!("calls still waiting after {MAX_SETTLE_ROUNDS} rounds of answers"))
    }
}

/// The sessions an MCP server holds, by id, oldest first.
#[derive(Default)]
pub struct Sessions {
    live: Vec<(String, Live)>,
    count: u64,
}

impl Sessions {
    pub fn add(&mut self, live: Live) -> String {
        self.count += 1;
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0);
        let id = format!("s{}-{:06x}", self.count, (nanos ^ (std::process::id() << 8)) & 0xffffff);
        if self.live.len() >= MAX_SESSIONS {
            self.live.remove(0);
        }
        self.live.push((id.clone(), live));
        id
    }

    pub fn get(&mut self, id: &str) -> Result<&mut Live, String> {
        let ids: Vec<&str> = self.live.iter().map(|(i, _)| i.as_str()).collect();
        let known = if ids.is_empty() { "none is running".to_string() } else { format!("running: {}", ids.join(", ")) };
        match self.live.iter_mut().find(|(i, _)| i == id) {
            Some((_, live)) => Ok(live),
            None => Err(format!("no session '{id}' ({known}); start one with session_start")),
        }
    }

    pub fn stop(&mut self, id: &str) -> bool {
        let before = self.live.len();
        self.live.retain(|(i, _)| i != id);
        self.live.len() != before
    }
}
