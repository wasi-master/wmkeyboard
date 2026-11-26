//! The state of `wmpy run`'s screen and what each key and click does.
//!
//! Left (or on top, in a narrow terminal): the plugin's panel at a phone's
//! width, with its header (title, back, header buttons), toasts and
//! dialogs. Right (or below): a pane with tabs, Console, Events, Storage,
//! Field and Calls. Focus is either in the panel, on one of its targets, or
//! in the pane.

use std::collections::HashMap;

use ratatui::buffer::Buffer;
use ratatui::crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseButton, MouseEvent, MouseEventKind};
use ratatui::layout::{Constraint, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, BorderType, Borders, Clear, Paragraph, Tabs, Wrap};
use ratatui::Frame;
use serde_json::{json, Value as Json};

use super::layout::{self, Action, Local, Target};
use crate::devhost::{DevHost, Kind};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Pane {
    Console,
    Events,
    Storage,
    Field,
    Calls,
}

pub const PANES: [Pane; 5] = [Pane::Console, Pane::Events, Pane::Storage, Pane::Field, Pane::Calls];

impl Pane {
    fn title(self) -> &'static str {
        match self {
            Pane::Console => "Console",
            Pane::Events => "Events",
            Pane::Storage => "Storage",
            Pane::Field => "Field",
            Pane::Calls => "Calls",
        }
    }
}

/// A drawn tree, kept until something it depends on changes.
struct Drawn {
    key: (u64, u16, Option<usize>, bool, u64),
    buf: Buffer,
    targets: Vec<Target>,
    height: u16,
}

pub struct App {
    pub host: DevHost,
    /// The panel's inner width, in columns.
    pub panel_width: u16,
    /// The focused target: header targets first, then the tree's.
    pub focus: Option<usize>,
    /// Typing goes into the focused text box.
    pub editing: bool,
    /// Keys go to the pane rather than the panel.
    pub in_pane: bool,
    pub pane: Pane,
    pub local: Local,
    local_version: u64,
    pub scroll: u16,
    pane_scroll: HashMap<Pane, u16>,
    pub help: bool,
    pub quit: bool,
    /// The event typed in the Events pane, and its cursor.
    pub event_line: (String, usize),
    history_at: Option<usize>,
    storage_row: usize,
    dialog_button: usize,
    drawn: Option<Drawn>,
    header: Vec<Target>,
    /// Where the panel's content and the pane were last drawn, on screen.
    content_area: Rect,
    header_area: Rect,
    pane_area: Rect,
    pane_tabs_area: Rect,
    dialog_buttons: Vec<Rect>,
    /// A canvas drag in progress: its target and the last point.
    drag: Option<(usize, f64, f64, bool)>,
    /// The tree version focus was last checked against.
    seen_version: u64,
}

impl App {
    pub fn new(host: DevHost, panel_width: u16) -> App {
        App {
            host,
            panel_width: panel_width.clamp(24, 120),
            focus: None,
            editing: false,
            in_pane: false,
            pane: Pane::Console,
            local: Local::default(),
            local_version: 0,
            scroll: 0,
            pane_scroll: HashMap::new(),
            help: false,
            quit: false,
            event_line: (String::new(), 0),
            history_at: None,
            storage_row: 0,
            dialog_button: 0,
            drawn: None,
            header: Vec::new(),
            content_area: Rect::default(),
            header_area: Rect::default(),
            pane_area: Rect::default(),
            pane_tabs_area: Rect::default(),
            dialog_buttons: Vec::new(),
            drag: None,
            seen_version: 0,
        }
    }

    fn v5(&self) -> bool {
        self.host.facts.api >= 5
    }

    fn dark(&self) -> bool {
        self.host.opts.dark
    }

    // ---- targets --------------------------------------------------------

    /// The header's back button and up to three header buttons, at the right.
    fn header_targets(&self) -> Vec<(Target, String)> {
        let meta = &self.host.meta;
        let mut out = Vec::new();
        if meta["canBack"].as_bool() == Some(true) {
            out.push((Target { rect: Rect::new(0, 0, 3, 1), action: Action::Back, hold: None }, "‹".to_string()));
        }
        let commands: Vec<(String, String)> = meta["commands"].as_array().into_iter().flatten().take(3).map(|c| (layout::s(c, "id"), layout::s(c, "label"))).collect();
        let widths: u16 = commands.iter().map(|(_, l)| unicode_width::UnicodeWidthStr::width(l.as_str()) as u16 + 5).sum();
        let mut right = self.panel_width.saturating_sub(widths);
        for (id, label) in commands {
            let w = unicode_width::UnicodeWidthStr::width(label.as_str()) as u16 + 4;
            out.push((Target { rect: Rect::new(right, 0, w, 1), action: Action::Command(id), hold: None }, label));
            right += w + 1;
        }
        out
    }

    /// Draw the tree again if what it shows changed.
    fn layout(&mut self) {
        let focus_in_tree = self.focus.and_then(|f| f.checked_sub(self.header.len()));
        let key = (self.host.version(), self.panel_width, focus_in_tree, self.editing, self.local_version);
        if self.drawn.as_ref().is_some_and(|d| d.key == key) {
            return;
        }
        let root = self.host.ui["root"].as_array().cloned().unwrap_or_default();
        let (buf, targets, height) = layout::render(&root, self.panel_width, &self.local, focus_in_tree, self.editing, self.dark(), self.v5());
        self.drawn = Some(Drawn { key, buf, targets, height });
    }

    fn targets(&self) -> Vec<Target> {
        let mut all = self.header.clone();
        if let Some(d) = &self.drawn {
            all.extend(d.targets.iter().cloned());
        }
        all
    }

    /// Keep the same widget focused after a redraw, by its action.
    fn sync(&mut self) {
        let before: Option<Target> = self.focus.and_then(|f| self.targets().get(f).cloned());
        self.header = self.header_targets().into_iter().map(|(t, _)| t).collect();
        // Text the plugin put in a box (wm.ui.set_input) shows in it,
        // unless the person is typing in that box right now.
        let typing = if self.editing { self.focused_input().map(|(id, ..)| id) } else { None };
        for (id, text) in self.host.inputs.clone() {
            let text = text.as_str().unwrap_or("").to_string();
            if typing.as_deref() == Some(id.as_str()) {
                continue;
            }
            let entry = self.local.edits.entry(id).or_default();
            if entry.0 != text {
                entry.1 = text.chars().count();
                entry.0 = text;
                self.local_version += 1;
            }
        }
        self.layout();
        if self.seen_version != self.host.version() {
            self.seen_version = self.host.version();
            if let Some(b) = before {
                let all = self.targets();
                self.focus = all.iter().position(|t| t.action == b.action).or_else(|| all.iter().position(|t| same_widget(&t.action, &b.action))).or_else(|| self.focus.filter(|f| *f < all.len()));
                self.layout();
            }
        }
        let count = self.targets().len();
        if self.focus.is_some_and(|f| f >= count) {
            self.focus = if count == 0 { None } else { Some(count - 1) };
            self.layout();
        }
    }

    fn focused(&self) -> Option<Target> {
        self.focus.and_then(|f| self.targets().get(f).cloned())
    }

    fn focused_input(&self) -> Option<(String, bool, bool)> {
        match self.focused()?.action {
            Action::Input { id, multiline, live } => Some((id, multiline, live)),
            _ => None,
        }
    }

    fn set_focus(&mut self, f: Option<usize>) {
        self.focus = f;
        self.editing = self.focused_input().is_some();
        self.layout();
        self.reveal();
    }

    /// Scroll so the focused target shows.
    fn reveal(&mut self) {
        let Some(f) = self.focus else { return };
        if f < self.header.len() {
            return;
        }
        let Some(t) = self.focused() else { return };
        let view = self.content_area.height.max(1);
        if t.rect.y < self.scroll {
            self.scroll = t.rect.y;
        } else if t.rect.y + t.rect.height.min(view) > self.scroll + view {
            self.scroll = t.rect.y + t.rect.height.min(view) - view;
        }
    }

    fn move_focus(&mut self, forward: bool) {
        let count = self.targets().len();
        if count == 0 {
            return;
        }
        let next = match self.focus {
            None => if forward { 0 } else { count - 1 },
            Some(f) if forward => (f + 1) % count,
            Some(f) => (f + count - 1) % count,
        };
        self.set_focus(Some(next));
    }

    /// Up and Down: the nearest target on another line.
    fn move_vertical(&mut self, down: bool) {
        let all = self.targets();
        let Some(cur) = self.focused() else { return self.move_focus(down) };
        let header = self.header.len();
        let place = |i: usize, t: &Target| -> (i32, i32) {
            if i < header {
                (t.rect.x as i32, -1)
            } else {
                (t.rect.x as i32, t.rect.y as i32)
            }
        };
        let me = self.focus.unwrap_or(0);
        let (cx, cy) = place(me, &cur);
        let best = all
            .iter()
            .enumerate()
            .filter(|(i, t)| {
                let (_, y) = place(*i, t);
                if down {
                    y > cy
                } else {
                    y < cy
                }
            })
            .min_by_key(|(i, t)| {
                let (x, y) = place(*i, t);
                ((y - cy).abs() * 1000) + (x - cx).abs()
            })
            .map(|(i, _)| i);
        if let Some(b) = best {
            self.set_focus(Some(b));
        } else if !down {
            self.scroll = 0;
        }
    }

    // ---- events to the plugin -------------------------------------------

    fn send(&mut self, event: Json) {
        self.host.send(event);
    }

    /// Enter or Space on the focused target.
    fn press(&mut self) {
        let Some(t) = self.focused() else { return };
        match t.action {
            Action::Click(id) => self.send(json!({"type": "click", "id": id})),
            Action::Back => self.send(json!({"type": "back"})),
            Action::Command(id) => self.send(json!({"type": "command", "id": id})),
            Action::Toggle { id, checked } => {
                let kind = if self.v5() { "change" } else { "toggle" };
                self.send(json!({"type": kind, "id": id, "value": !checked}));
            }
            Action::Input { id, multiline, .. } => {
                if !self.editing {
                    self.editing = true;
                    self.layout();
                } else if !multiline {
                    let text = self.local.edits.get(&id).map(|e| e.0.clone()).unwrap_or_default();
                    self.send(json!({"type": "submit", "id": id, "value": text}));
                }
            }
            Action::Slider { .. } => {
                self.adjust(1);
            }
            Action::Set { id, value } => self.send(json!({"type": "change", "id": id, "value": value})),
            Action::Select { .. } => {
                self.adjust(1);
            }
            Action::Key { id, key } => self.send(json!({"type": "press", "id": id, "value": key})),
            Action::Pick { id, index } => self.send(json!({"type": "select", "id": id, "value": index})),
            Action::Actions(id) => {
                if !self.local.actions_open.remove(&id) {
                    self.local.actions_open.insert(id);
                }
                self.local_version += 1;
            }
            Action::ItemAction { id, action } => {
                self.local.actions_open.remove(&id);
                self.local_version += 1;
                self.send(json!({"type": "item_action", "id": id, "value": action}));
            }
            Action::Section { key, open } => {
                self.local.sections.insert(key, !open);
                self.local_version += 1;
            }
            Action::Tab { id, key, index } => {
                self.local.tabs.insert(key, index);
                self.local_version += 1;
                if self.v5() {
                    self.send(json!({"type": "change", "id": id, "value": index}));
                } else {
                    self.send(json!({"type": "tab_selected", "id": id, "index": index}));
                }
            }
            Action::Insert(text) => self.host.insert(&text),
            Action::Copy(text) => self.host.copy(&text),
            Action::Canvas { id, w, h, tappable, draggable } => {
                if tappable {
                    self.send(json!({"type": "tap", "id": id, "x": w / 2.0, "y": h / 2.0}));
                } else if draggable {
                    self.host.log(Kind::Note, "drag a canvas with the mouse; Enter taps it when it takes taps");
                }
            }
            Action::Date { id, value } => {
                let kind = if self.v5() { "change" } else { "date_changed" };
                self.send(json!({"type": kind, "id": id, "value": value}));
            }
        }
        self.sync();
    }

    /// Left and Right on a slider, a dropdown or a stepper.
    fn adjust(&mut self, by: i32) -> bool {
        let Some(t) = self.focused() else { return false };
        match t.action {
            Action::Slider { id, value, min, max, step } => {
                let step = if step > 0.0 { step } else { 1.0 };
                let next = (value + step * by as f64).clamp(min, max);
                if next != value {
                    self.send(json!({"type": "change", "id": id, "value": number_json(next)}));
                }
                true
            }
            Action::Select { id, options, value } => {
                if options.is_empty() {
                    return true;
                }
                let at = value.and_then(|v| options.iter().position(|o| *o == v));
                let next = match at {
                    None => 0,
                    Some(i) => (i as i32 + by).rem_euclid(options.len() as i32) as usize,
                };
                self.send(json!({"type": "change", "id": id, "value": options[next]}));
                true
            }
            _ => false,
        }
    }

    fn hold(&mut self) {
        let Some(t) = self.focused() else { return };
        if let Some(id) = t.hold {
            let (x, y) = match t.action {
                Action::Canvas { w, h, .. } => (w / 2.0, h / 2.0),
                _ => (0.0, 0.0),
            };
            self.send(json!({"type": "hold", "id": id, "x": x, "y": y}));
            self.sync();
        }
    }

    /// A key typed into the focused text box.
    fn type_key(&mut self, key: KeyEvent) -> bool {
        let Some((id, multiline, live)) = self.focused_input() else { return false };
        let entry = self.local.edits.entry(id.clone()).or_default();
        let mut chars: Vec<char> = entry.0.chars().collect();
        let mut cursor = entry.1.min(chars.len());
        let mut changed = false;
        match key.code {
            KeyCode::Char(c) if !key.modifiers.intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) => {
                chars.insert(cursor, c);
                cursor += 1;
                changed = true;
            }
            KeyCode::Enter if multiline && !key.modifiers.contains(KeyModifiers::ALT) => {
                chars.insert(cursor, '\n');
                cursor += 1;
                changed = true;
            }
            KeyCode::Char('s') if multiline && key.modifiers.contains(KeyModifiers::CONTROL) => {
                let text: String = chars.iter().collect();
                self.send(json!({"type": "submit", "id": id, "value": text}));
                self.sync();
                return true;
            }
            KeyCode::Enter => {
                let text: String = chars.iter().collect();
                self.send(json!({"type": "submit", "id": id, "value": text}));
                self.sync();
                return true;
            }
            KeyCode::Backspace if cursor > 0 => {
                chars.remove(cursor - 1);
                cursor -= 1;
                changed = true;
            }
            KeyCode::Delete if cursor < chars.len() => {
                chars.remove(cursor);
                changed = true;
            }
            KeyCode::Left => cursor = cursor.saturating_sub(1),
            KeyCode::Right => cursor = (cursor + 1).min(chars.len()),
            KeyCode::Home => cursor = 0,
            KeyCode::End => cursor = chars.len(),
            KeyCode::Char('u') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                chars.clear();
                cursor = 0;
                changed = true;
            }
            _ => return false,
        }
        let text: String = chars.iter().collect();
        *entry = (text.clone(), cursor);
        self.local_version += 1;
        if changed {
            // Live boxes tell the plugin as the user types; API 4 boxes always do.
            if !self.v5() {
                self.send(json!({"type": "input_changed", "id": id, "value": text}));
            } else if live {
                self.send(json!({"type": "change", "id": id, "value": text}));
            }
            self.sync();
        }
        self.layout();
        true
    }

    // ---- the pane -------------------------------------------------------

    fn pane_key(&mut self, key: KeyEvent) -> bool {
        match self.pane {
            Pane::Events => self.events_key(key),
            Pane::Field => self.field_key(key),
            Pane::Storage => {
                let keys: Vec<String> = self.host.storage.as_object().map(|o| o.keys().cloned().collect()).unwrap_or_default();
                match key.code {
                    KeyCode::Up => self.storage_row = self.storage_row.saturating_sub(1),
                    KeyCode::Down => self.storage_row = (self.storage_row + 1).min(keys.len().saturating_sub(1)),
                    KeyCode::Delete | KeyCode::Backspace | KeyCode::Char('d') => {
                        if let Some(k) = keys.get(self.storage_row) {
                            let k = k.clone();
                            self.host.storage_remove(&k);
                            self.host.log(Kind::Note, format!("[storage] removed {k}"));
                        }
                    }
                    _ => return false,
                }
                true
            }
            Pane::Console | Pane::Calls => {
                let s = self.pane_scroll.entry(self.pane).or_insert(0);
                match key.code {
                    KeyCode::Up => *s = s.saturating_add(1),
                    KeyCode::Down => *s = s.saturating_sub(1),
                    KeyCode::PageUp => *s = s.saturating_add(10),
                    KeyCode::PageDown => *s = s.saturating_sub(10),
                    KeyCode::End => *s = 0,
                    KeyCode::Char('c') if self.pane == Pane::Console => self.host.clear_console(),
                    _ => return false,
                }
                true
            }
        }
    }

    fn events_key(&mut self, key: KeyEvent) -> bool {
        let (text, cursor) = &mut self.event_line;
        let mut chars: Vec<char> = text.chars().collect();
        let mut c = (*cursor).min(chars.len());
        match key.code {
            KeyCode::Char(ch) if !key.modifiers.intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) => {
                chars.insert(c, ch);
                c += 1;
            }
            KeyCode::Backspace if c > 0 => {
                chars.remove(c - 1);
                c -= 1;
            }
            KeyCode::Delete if c < chars.len() => {
                chars.remove(c);
            }
            KeyCode::Left => c = c.saturating_sub(1),
            KeyCode::Right => c = (c + 1).min(chars.len()),
            KeyCode::Home => c = 0,
            KeyCode::End => c = chars.len(),
            KeyCode::Up | KeyCode::Down => {
                let sent: Vec<Json> = self.host.sent.iter().cloned().collect();
                if sent.is_empty() {
                    return true;
                }
                let at = match (self.history_at, key.code) {
                    (None, KeyCode::Up) => sent.len() - 1,
                    (Some(i), KeyCode::Up) => i.saturating_sub(1),
                    (Some(i), _) => (i + 1).min(sent.len() - 1),
                    (None, _) => return true,
                };
                self.history_at = Some(at);
                let t = sent[at].to_string();
                self.event_line = (t.clone(), t.chars().count());
                return true;
            }
            KeyCode::Enter => {
                let t: String = chars.iter().collect();
                match serde_json::from_str::<Json>(&t) {
                    Ok(v) if v.is_object() => {
                        self.host.log(Kind::Sent, format!("sent {v}"));
                        self.send(v);
                        self.event_line = (String::new(), 0);
                        self.history_at = None;
                        self.sync();
                    }
                    _ => self.host.log(Kind::Error, format!("not a JSON object: {t}")),
                }
                return true;
            }
            _ => return false,
        }
        self.event_line = (chars.iter().collect(), c);
        true
    }

    fn field_key(&mut self, key: KeyEvent) -> bool {
        let (text, cursor, selection) = {
            let w = self.host.world.borrow();
            (w.field.text.clone(), w.field.cursor, w.field.selection)
        };
        let mut chars = text;
        let mut c = cursor.min(chars.len());
        let mut sel = selection;
        let shift = key.modifiers.contains(KeyModifiers::SHIFT);
        let extend = |sel: Option<(usize, usize)>, from: usize, to: usize| -> Option<(usize, usize)> {
            let anchor = match sel {
                Some((a, b)) if b == from => a,
                Some((a, b)) if a == from => b,
                _ => from,
            };
            let (a, b) = (anchor.min(to), anchor.max(to));
            (a < b).then_some((a, b))
        };
        match key.code {
            KeyCode::Char('a') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                sel = (!chars.is_empty()).then_some((0, chars.len()));
                c = chars.len();
            }
            KeyCode::Char('l') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                chars.clear();
                c = 0;
                sel = None;
            }
            KeyCode::Char(ch) if !key.modifiers.intersects(KeyModifiers::CONTROL | KeyModifiers::ALT) => {
                if let Some((a, b)) = sel.take() {
                    chars.drain(a..b);
                    c = a;
                }
                chars.insert(c, ch);
                c += 1;
            }
            KeyCode::Enter => {
                if let Some((a, b)) = sel.take() {
                    chars.drain(a..b);
                    c = a;
                }
                chars.insert(c, '\n');
                c += 1;
            }
            KeyCode::Backspace => {
                if let Some((a, b)) = sel.take() {
                    chars.drain(a..b);
                    c = a;
                } else if c > 0 {
                    chars.remove(c - 1);
                    c -= 1;
                }
            }
            KeyCode::Delete => {
                if let Some((a, b)) = sel.take() {
                    chars.drain(a..b);
                    c = a;
                } else if c < chars.len() {
                    chars.remove(c);
                }
            }
            KeyCode::Left => {
                let to = c.saturating_sub(1);
                sel = if shift { extend(sel, c, to) } else { None };
                c = to;
            }
            KeyCode::Right => {
                let to = (c + 1).min(chars.len());
                sel = if shift { extend(sel, c, to) } else { None };
                c = to;
            }
            KeyCode::Home => {
                sel = if shift { extend(sel, c, 0) } else { None };
                c = 0;
            }
            KeyCode::End => {
                sel = if shift { extend(sel, c, chars.len()) } else { None };
                c = chars.len();
            }
            _ => return false,
        }
        let t: String = chars.iter().collect();
        self.host.set_field(&t, c, sel);
        true
    }

    // ---- input ----------------------------------------------------------

    pub fn handle(&mut self, event: Event) {
        // Targets must be current even for an event that comes before the first draw.
        self.sync();
        match event {
            Event::Key(k) if k.kind != KeyEventKind::Release => self.key(k),
            Event::Mouse(m) => self.mouse(m),
            Event::Paste(text) => {
                for ch in text.chars() {
                    self.key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
                }
            }
            _ => {}
        }
        self.sync();
    }

    fn key(&mut self, k: KeyEvent) {
        let ctrl = k.modifiers.contains(KeyModifiers::CONTROL);
        // Always: quit, reload, switch between the panel and the pane.
        match k.code {
            KeyCode::Char('c') if ctrl => return self.quit = true,
            KeyCode::Char('r') if ctrl => return self.host.reload("Ctrl-R"),
            KeyCode::Char('o') if ctrl => {
                self.in_pane = !self.in_pane;
                return;
            }
            KeyCode::F(6) => {
                self.in_pane = !self.in_pane;
                return;
            }
            KeyCode::F(n @ 1..=5) => {
                self.pane = PANES[n as usize - 1];
                return;
            }
            _ => {}
        }
        if self.help {
            self.help = false;
            return;
        }
        if self.host.dialog().is_some() {
            return self.dialog_key(k);
        }
        if self.in_pane {
            if k.code == KeyCode::Esc {
                self.in_pane = false;
                return;
            }
            if k.code == KeyCode::Tab && !matches!(self.pane, Pane::Events | Pane::Field) {
                self.in_pane = false;
                return;
            }
            if self.pane_key(k) {
                return;
            }
        } else if self.editing {
            if k.code == KeyCode::Esc {
                self.editing = false;
                self.layout();
                return;
            }
            if self.type_key(k) {
                return;
            }
        }
        match k.code {
            KeyCode::Char('q') => self.quit = true,
            KeyCode::Char('r') => self.host.reload("r"),
            KeyCode::Char('?') => self.help = true,
            KeyCode::Char('h') => self.hold(),
            KeyCode::Char('[') => self.cycle_pane(false),
            KeyCode::Char(']') => self.cycle_pane(true),
            KeyCode::Char(c @ '1'..='5') => self.pane = PANES[c as usize - '1' as usize],
            KeyCode::Tab => self.move_focus(true),
            KeyCode::BackTab => self.move_focus(false),
            KeyCode::Down => self.move_vertical(true),
            KeyCode::Up => self.move_vertical(false),
            KeyCode::Right => {
                if !self.adjust(1) {
                    self.move_focus(true);
                }
            }
            KeyCode::Left => {
                if !self.adjust(-1) {
                    self.move_focus(false);
                }
            }
            KeyCode::Enter | KeyCode::Char(' ') => self.press(),
            KeyCode::PageDown => self.scroll = self.scroll.saturating_add(self.content_area.height.max(2) - 1),
            KeyCode::PageUp => self.scroll = self.scroll.saturating_sub(self.content_area.height.max(2) - 1),
            KeyCode::Esc => self.set_focus(None),
            _ => {}
        }
        self.clamp_scroll();
    }

    fn cycle_pane(&mut self, forward: bool) {
        let i = PANES.iter().position(|p| *p == self.pane).unwrap_or(0);
        self.pane = PANES[if forward { (i + 1) % PANES.len() } else { (i + PANES.len() - 1) % PANES.len() }];
    }

    fn dialog_key(&mut self, k: KeyEvent) {
        let count = self.host.dialog().map_or(1, |d| d.buttons.len()).max(1);
        match k.code {
            KeyCode::Left | KeyCode::BackTab => self.dialog_button = (self.dialog_button + count - 1) % count,
            KeyCode::Right | KeyCode::Tab => self.dialog_button = (self.dialog_button + 1) % count,
            KeyCode::Enter | KeyCode::Char(' ') => {
                let i = self.dialog_button.min(count - 1);
                self.dialog_button = 0;
                self.host.answer_dialog(Some(i));
            }
            KeyCode::Esc => {
                self.dialog_button = 0;
                self.host.answer_dialog(None);
            }
            _ => {}
        }
    }

    fn clamp_scroll(&mut self) {
        let height = self.drawn.as_ref().map_or(0, |d| d.height);
        self.scroll = self.scroll.min(height.saturating_sub(self.content_area.height.max(1)));
    }

    fn mouse(&mut self, m: MouseEvent) {
        let at = Position::new(m.column, m.row);
        match m.kind {
            MouseEventKind::ScrollDown if self.content_area.contains(at) => {
                self.scroll = self.scroll.saturating_add(3);
                self.clamp_scroll();
            }
            MouseEventKind::ScrollUp if self.content_area.contains(at) => self.scroll = self.scroll.saturating_sub(3),
            MouseEventKind::ScrollUp if self.pane_area.contains(at) => {
                let s = self.pane_scroll.entry(self.pane).or_insert(0);
                *s = s.saturating_add(3);
            }
            MouseEventKind::ScrollDown if self.pane_area.contains(at) => {
                let s = self.pane_scroll.entry(self.pane).or_insert(0);
                *s = s.saturating_sub(3);
            }
            MouseEventKind::Down(MouseButton::Left) => {
                if let Some(i) = self.dialog_buttons.iter().position(|r| r.contains(at)) {
                    self.dialog_button = 0;
                    return self.host.answer_dialog(Some(i));
                }
                if self.host.dialog().is_some() {
                    return;
                }
                if self.pane_tabs_area.contains(at) {
                    self.in_pane = true;
                    let mut x = self.pane_tabs_area.x + 1;
                    for p in PANES {
                        let w = p.title().len() as u16 + 3;
                        if m.column >= x && m.column < x + w {
                            self.pane = p;
                        }
                        x += w;
                    }
                    return;
                }
                if self.pane_area.contains(at) {
                    self.in_pane = true;
                    return;
                }
                self.in_pane = false;
                let hit = self.hit(at);
                if let Some((i, point)) = hit {
                    self.set_focus(Some(i));
                    let t = self.targets()[i].clone();
                    if let Action::Canvas { id, w, h, draggable, .. } = &t.action {
                        let (px, py) = canvas_point(&t, point, *w, *h);
                        if *draggable {
                            self.send(json!({"type": "drag", "id": id, "x": px, "y": py, "dx": 0, "dy": 0, "phase": "start"}));
                        }
                        self.drag = Some((i, px, py, false));
                        return;
                    }
                    if !matches!(t.action, Action::Input { .. } | Action::Slider { .. }) {
                        self.press();
                    } else if let Action::Slider { id, min, max, step, .. } = t.action {
                        // A click on the bar sets the value there.
                        let share = (point.0 as f64 / t.rect.width.max(1) as f64).clamp(0.0, 1.0);
                        let step = if step > 0.0 { step } else { 1.0 };
                        let v = (min + (max - min) * share) / step;
                        self.send(json!({"type": "change", "id": id, "value": number_json((v.round() * step).clamp(min, max))}));
                    }
                }
            }
            MouseEventKind::Drag(MouseButton::Left) => {
                if let Some((i, lx, ly, _)) = self.drag {
                    let Some(t) = self.targets().get(i).cloned() else { return };
                    if let Action::Canvas { id, w, h, draggable, .. } = &t.action {
                        let point = self.local_point(&t, at);
                        let (px, py) = canvas_point(&t, point, *w, *h);
                        if *draggable && (px != lx || py != ly) {
                            self.send(json!({"type": "drag", "id": id, "x": px, "y": py, "dx": px - lx, "dy": py - ly, "phase": "move"}));
                        }
                        self.drag = Some((i, px, py, true));
                    }
                }
            }
            MouseEventKind::Up(MouseButton::Left) => {
                if let Some((i, lx, ly, moved)) = self.drag.take() {
                    let Some(t) = self.targets().get(i).cloned() else { return };
                    if let Action::Canvas { id, draggable, tappable, .. } = &t.action {
                        if *draggable {
                            self.send(json!({"type": "drag", "id": id, "x": lx, "y": ly, "dx": 0, "dy": 0, "phase": "end"}));
                        }
                        if *tappable && !moved {
                            self.send(json!({"type": "tap", "id": id, "x": lx, "y": ly}));
                        }
                    }
                }
            }
            _ => {}
        }
    }

    /// The target under a screen position, and the position inside it.
    fn hit(&self, at: Position) -> Option<(usize, (u16, u16))> {
        if self.header_area.contains(at) {
            let x = at.x - self.header_area.x;
            return self.header.iter().position(|t| x >= t.rect.x && x < t.rect.right()).map(|i| (i, (x - self.header[i].rect.x, 0)));
        }
        if !self.content_area.contains(at) {
            return None;
        }
        let (x, y) = (at.x - self.content_area.x, at.y - self.content_area.y + self.scroll);
        let p = Position::new(x, y);
        let targets = self.drawn.as_ref()?.targets.iter();
        // The innermost (last drawn) target under the point wins.
        let found = targets.enumerate().filter(|(_, t)| t.rect.contains(p)).last()?;
        Some((self.header.len() + found.0, (x - found.1.rect.x, y - found.1.rect.y)))
    }

    fn local_point(&self, t: &Target, at: Position) -> (u16, u16) {
        let x = at.x.saturating_sub(self.content_area.x);
        let y = (at.y.saturating_sub(self.content_area.y) + self.scroll).saturating_sub(t.rect.y);
        (x.saturating_sub(t.rect.x).min(t.rect.width.saturating_sub(1)), y.min(t.rect.height.saturating_sub(1)))
    }

    // ---- drawing --------------------------------------------------------

    pub fn draw(&mut self, f: &mut Frame) {
        self.sync();
        let area = f.area();
        let [main, status] = Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(area);
        let panel_outer = self.panel_width + 2;
        let (panel_rect, pane_rect) = if main.width >= panel_outer + 34 {
            let [a, b] = Layout::horizontal([Constraint::Length(panel_outer), Constraint::Fill(1)]).areas(main);
            (a, b)
        } else {
            let [a, b] = Layout::vertical([Constraint::Percentage(62), Constraint::Fill(1)]).areas(main);
            (Rect::new(a.x, a.y, panel_outer.min(a.width), a.height), b)
        };
        // A terminal narrower than the panel draws it narrower.
        let inner_width = panel_rect.width.saturating_sub(2);
        if inner_width != self.panel_width && inner_width > 0 && panel_rect.width < panel_outer {
            self.panel_width = inner_width.max(24);
            self.drawn = None;
            self.sync();
        }
        self.draw_panel(f, panel_rect);
        self.draw_pane(f, pane_rect);
        self.draw_status(f, status);
        if self.help {
            self.draw_help(f, area);
        }
    }

    fn draw_panel(&mut self, f: &mut Frame, area: Rect) {
        let dark = self.dark();
        let accent = if dark { Color::LightBlue } else { Color::Blue };
        let focused_panel = !self.in_pane;
        let block = Block::new()
            .borders(Borders::ALL)
            .border_type(BorderType::Rounded)
            .border_style(if focused_panel { Style::new().fg(accent) } else { Style::new().fg(Color::DarkGray) })
            .title(Line::from(vec![Span::raw(" "), Span::styled(self.host.facts.name.clone(), Style::new().add_modifier(Modifier::BOLD)), Span::raw(" ")]))
            .title_bottom(Line::from(Span::styled(format!(" {} ", if self.host.opts.dark { "dark" } else { "light" }), Style::new().fg(Color::DarkGray))).right_aligned());
        let inner = block.inner(area);
        f.render_widget(block, area);
        if inner.height < 3 || inner.width < 4 {
            return;
        }
        // The header: back, title, header buttons.
        let header_area = Rect::new(inner.x, inner.y, inner.width, 1);
        self.header_area = header_area;
        let meta = self.host.meta.clone();
        let title = meta["title"].as_str().map(String::from).unwrap_or_else(|| self.host.facts.name.clone());
        let header = self.header_targets();
        let buf = f.buffer_mut();
        let mut title_x = header_area.x;
        for (i, (t, label)) in header.iter().enumerate() {
            let focused = self.focus == Some(i) && focused_panel;
            let style = if focused { Style::new().add_modifier(Modifier::REVERSED) } else { Style::new().fg(accent) };
            let text = if t.action == Action::Back { "[‹]".to_string() } else { format!("[ {label} ]") };
            buf.set_stringn(header_area.x + t.rect.x, header_area.y, &text, t.rect.width as usize, style);
            if t.action == Action::Back {
                title_x = header_area.x + t.rect.right() + 1;
            }
        }
        let first_command = header.iter().find(|(t, _)| matches!(t.action, Action::Command(_))).map_or(header_area.right(), |(t, _)| header_area.x + t.rect.x);
        buf.set_stringn(title_x, header_area.y, &title, first_command.saturating_sub(title_x + 1) as usize, Style::new().add_modifier(Modifier::BOLD));
        let mut y = inner.y + 1;
        buf.set_stringn(inner.x, y, "─".repeat(inner.width as usize), inner.width as usize, Style::new().fg(Color::DarkGray));
        y += 1;
        // The last error, in a line or two, as the phone's panel shows it.
        if let Some(e) = &self.host.error {
            let head = format!("✗ {}", e.message);
            let place = match (&e.file, e.line) {
                (Some(file), Some(l)) => format!("  {file}:{l}"),
                _ => String::new(),
            };
            let text = format!("{head}{place}");
            let wrapped: Vec<Line> = wrap_lines(vec![Line::styled(text, Style::new().fg(Color::Red))], inner.width).into_iter().take(3).collect();
            let lines = wrapped.len().max(1) as u16;
            f.render_widget(Paragraph::new(wrapped), Rect::new(inner.x, y, inner.width, lines));
            y += lines;
        }
        let content = Rect::new(inner.x, y, inner.width, inner.bottom().saturating_sub(y));
        self.content_area = content;
        if self.host.facts.panel && self.host.ui["root"].as_array().is_some_and(Vec::is_empty) && self.host.error.is_none() {
            f.render_widget(Paragraph::new(Line::styled("The panel is empty: render() drew nothing.", Style::new().fg(Color::DarkGray))).wrap(Wrap { trim: true }), content);
        }
        if !self.host.facts.panel {
            f.render_widget(Paragraph::new(Line::styled("This plugin has no panel. Its selection actions run in `wmpy test`.", Style::new().fg(Color::DarkGray))).wrap(Wrap { trim: true }), content);
        }
        self.clamp_scroll();
        if let Some(d) = &self.drawn {
            let buf = f.buffer_mut();
            for row in 0..content.height {
                let sy = self.scroll + row;
                if sy >= d.height {
                    break;
                }
                for col in 0..content.width.min(d.buf.area.width) {
                    let cell = &d.buf[(col, sy)];
                    if let Some(out) = buf.cell_mut((content.x + col, content.y + row)) {
                        *out = cell.clone();
                    }
                }
            }
            if d.height > content.height && content.height > 0 {
                let track = content.height;
                let pos = (self.scroll as u32 * track as u32 / d.height.max(1) as u32) as u16;
                let len = ((content.height as u32 * track as u32) / d.height as u32).max(1) as u16;
                for r in 0..track {
                    let glyph = if r >= pos && r < pos + len { "┃" } else { "│" };
                    buf.set_string(area.right() - 1, content.y + r, glyph, Style::new().fg(Color::DarkGray));
                }
            }
        }
        // Toasts float at the bottom of the panel, as the phone's do.
        let toasts = self.host.toasts();
        if let Some(last) = toasts.last() {
            let w = (unicode_width::UnicodeWidthStr::width(last.as_str()) as u16 + 4).min(inner.width);
            let r = Rect::new(inner.x + (inner.width - w) / 2, inner.bottom().saturating_sub(2), w, 1);
            f.render_widget(Clear, r);
            f.render_widget(Paragraph::new(Line::styled(format!("  {last}  "), Style::new().add_modifier(Modifier::REVERSED))), r);
        }
        self.dialog_buttons.clear();
        if let Some(d) = self.host.dialog() {
            let w = inner.width.saturating_sub(4).max(20).min(inner.width);
            let body = d.text.clone().unwrap_or_default();
            let wrapped = if body.is_empty() { Vec::new() } else { wrap_lines(body.split('\n').map(|l| Line::raw(l.to_string())).collect(), w.saturating_sub(4)) };
            let body_lines = wrapped.len() as u16;
            let p = Paragraph::new(wrapped);
            let h = (body_lines + 5).min(inner.height);
            let r = Rect::new(inner.x + (inner.width - w) / 2, inner.y + inner.height.saturating_sub(h) / 2, w, h);
            f.render_widget(Clear, r);
            let block = Block::new().borders(Borders::ALL).border_type(BorderType::Rounded).border_style(Style::new().fg(accent)).title(Line::styled(format!(" {} ", self.host.facts.name), Style::new().fg(Color::DarkGray)));
            let ir = block.inner(r);
            f.render_widget(block, r);
            f.render_widget(Paragraph::new(Line::styled(d.title.clone(), Style::new().add_modifier(Modifier::BOLD))), Rect::new(ir.x + 1, ir.y, ir.width.saturating_sub(2), 1));
            if body_lines > 0 {
                f.render_widget(p, Rect::new(ir.x + 1, ir.y + 1, ir.width.saturating_sub(2), body_lines.min(ir.height.saturating_sub(2))));
            }
            let mut bx = ir.right();
            let by = ir.bottom().saturating_sub(1);
            let mut rects = Vec::new();
            for (i, label) in d.buttons.iter().enumerate().rev() {
                let text = format!("[ {label} ]");
                let bw = unicode_width::UnicodeWidthStr::width(text.as_str()) as u16;
                bx = bx.saturating_sub(bw + 1);
                let style = if i == self.dialog_button { Style::new().add_modifier(Modifier::REVERSED) } else { Style::new().fg(accent) };
                f.buffer_mut().set_stringn(bx, by, &text, bw as usize, style);
                rects.push((i, Rect::new(bx, by, bw, 1)));
            }
            rects.sort_by_key(|(i, _)| *i);
            self.dialog_buttons = rects.into_iter().map(|(_, r)| r).collect();
        }
    }

    fn draw_pane(&mut self, f: &mut Frame, area: Rect) {
        let focused = self.in_pane;
        let block = Block::new().borders(Borders::ALL).border_type(BorderType::Rounded).border_style(if focused { Style::new().fg(Color::LightBlue) } else { Style::new().fg(Color::DarkGray) });
        let inner = block.inner(area);
        f.render_widget(block, area);
        if inner.height < 2 {
            return;
        }
        let tabs_area = Rect::new(inner.x, inner.y, inner.width, 1);
        self.pane_tabs_area = tabs_area;
        self.pane_area = area;
        let index = PANES.iter().position(|p| *p == self.pane).unwrap_or(0);
        let titles: Vec<Line> = PANES.iter().enumerate().map(|(i, p)| Line::from(format!("{} {}", i + 1, p.title()))).collect();
        f.render_widget(Tabs::new(titles).select(index).highlight_style(Style::new().add_modifier(Modifier::BOLD | Modifier::REVERSED)).divider("│").padding(" ", " "), tabs_area);
        let body = Rect::new(inner.x, inner.y + 1, inner.width, inner.height - 1);
        let lines = wrap_lines(self.pane_lines(body.width), body.width);
        let scroll_back = *self.pane_scroll.get(&self.pane).unwrap_or(&0);
        let total = lines.len() as u16;
        let p = Paragraph::new(lines);
        // Console and Calls follow the newest line unless scrolled back.
        let top = match self.pane {
            Pane::Console | Pane::Calls => total.saturating_sub(body.height).saturating_sub(scroll_back),
            _ => 0,
        };
        f.render_widget(p.scroll((top, 0)), body);
    }

    fn pane_lines(&self, width: u16) -> Vec<Line<'static>> {
        let muted = Style::new().fg(Color::DarkGray);
        match self.pane {
            Pane::Console => {
                let mut out = Vec::new();
                if self.host.console.is_empty() {
                    out.push(Line::styled("Nothing printed yet. print() and wm.log lines, errors and what the host did show here.", muted));
                }
                for c in &self.host.console {
                    let style = match c.kind {
                        Kind::Print => Style::new(),
                        Kind::Error => Style::new().fg(Color::Red),
                        Kind::Note => muted,
                        Kind::Repair => Style::new().fg(Color::Yellow),
                        Kind::Reload => Style::new().fg(Color::Cyan),
                        Kind::Sent => Style::new().fg(Color::Magenta),
                    };
                    if c.kind == Kind::Reload {
                        let text = format!("── {} ", c.text);
                        let fill = (width as usize).saturating_sub(unicode_width::UnicodeWidthStr::width(text.as_str()));
                        out.push(Line::styled(format!("{text}{}", "─".repeat(fill)), style));
                        continue;
                    }
                    for (i, l) in c.text.split('\n').enumerate() {
                        let stamp = if i == 0 { format!("{:>6.1}s ", c.at as f64 / 1000.0) } else { "        ".to_string() };
                        out.push(Line::from(vec![Span::styled(stamp, muted), Span::styled(l.to_string(), style)]));
                    }
                }
                out
            }
            Pane::Events => {
                let (text, cursor) = &self.event_line;
                let mut spans = vec![Span::styled("event> ", Style::new().fg(Color::Magenta))];
                let chars: Vec<char> = text.chars().collect();
                let c = (*cursor).min(chars.len());
                spans.push(Span::raw(chars[..c].iter().collect::<String>()));
                if self.in_pane {
                    spans.push(Span::styled(chars.get(c).map_or(" ".to_string(), |ch| ch.to_string()), Style::new().add_modifier(Modifier::REVERSED)));
                    spans.push(Span::raw(chars.get(c + 1..).map(|r| r.iter().collect::<String>()).unwrap_or_default()));
                } else {
                    spans.push(Span::raw(chars[c..].iter().collect::<String>()));
                }
                let mut out = vec![Line::from(spans)];
                out.push(Line::styled("Type an event as JSON and press Enter; Up and Down recall the ones sent.", muted));
                let ids = self.event_examples();
                if !ids.is_empty() {
                    out.push(Line::raw(""));
                    out.push(Line::styled("This draw takes:", Style::new().add_modifier(Modifier::BOLD)));
                    for e in ids.iter().take(12) {
                        out.push(Line::styled(format!("  {e}"), muted));
                    }
                }
                out.push(Line::raw(""));
                out.push(Line::styled("Also: {\"type\": \"timer\", \"id\": ...}, {\"type\": \"back\"}, {\"type\": \"setting\", \"key\": ..., \"value\": ...}", muted));
                if !self.host.sent.is_empty() {
                    out.push(Line::raw(""));
                    out.push(Line::styled("Sent:", Style::new().add_modifier(Modifier::BOLD)));
                    for e in self.host.sent.iter().rev().take(20) {
                        out.push(Line::raw(format!("  {e}")));
                    }
                }
                out
            }
            Pane::Storage => {
                let mut out = Vec::new();
                let entries: Vec<(String, String)> = self.host.storage.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_string())).collect()).unwrap_or_default();
                if entries.is_empty() {
                    out.push(Line::styled("Nothing stored. wm.storage and plugin.state(persist=True) write here.", muted));
                }
                for (i, (k, v)) in entries.iter().enumerate() {
                    let selected = self.in_pane && i == self.storage_row;
                    let style = if selected { Style::new().add_modifier(Modifier::REVERSED) } else { Style::new().add_modifier(Modifier::BOLD) };
                    let shown: String = v.chars().take(400).collect();
                    out.push(Line::from(vec![Span::styled(k.clone(), style), Span::styled(" = ", muted), Span::raw(shown)]));
                }
                let secrets: Vec<Json> = self.host.snapshot()["secrets"].as_array().cloned().unwrap_or_default();
                if !secrets.is_empty() {
                    out.push(Line::raw(""));
                    out.push(Line::styled("Secrets (values hidden):", Style::new().add_modifier(Modifier::BOLD)));
                    for s in secrets {
                        out.push(Line::styled(format!("  {}", s.as_str().unwrap_or("")), muted));
                    }
                }
                out.push(Line::raw(""));
                out.push(Line::styled(format!("Kept in {}/.wmpy/ across reloads. In this pane: Up/Down pick a key, d deletes it.", self.host.dir().display()), muted));
                out
            }
            Pane::Field => {
                let w = self.host.world.borrow();
                let mut out = vec![Line::styled("The text field the plugin types into (field.*). Ctrl-O, then type to edit it; Shift-arrows select, Ctrl-A selects all, Ctrl-L clears.", muted), Line::raw("")];
                let mut spans = Vec::new();
                let sel = w.field.selection;
                for (i, ch) in w.field.text.iter().enumerate() {
                    if i == w.field.cursor && sel.is_none() {
                        spans.push(Span::styled("▏", Style::new().fg(Color::LightBlue).add_modifier(Modifier::BOLD)));
                    }
                    let in_sel = sel.is_some_and(|(a, b)| i >= a && i < b);
                    if *ch == '\n' {
                        out.push(Line::from(std::mem::take(&mut spans)));
                        continue;
                    }
                    spans.push(Span::styled(ch.to_string(), if in_sel { Style::new().add_modifier(Modifier::REVERSED) } else { Style::new() }));
                }
                if w.field.cursor >= w.field.text.len() && sel.is_none() {
                    spans.push(Span::styled("▏", Style::new().fg(Color::LightBlue).add_modifier(Modifier::BOLD)));
                }
                out.push(Line::from(spans));
                out.push(Line::raw(""));
                out.push(Line::styled("Clipboard:", Style::new().add_modifier(Modifier::BOLD)));
                for l in w.clipboard.split('\n').take(8) {
                    out.push(Line::raw(format!("  {l}")));
                }
                out
            }
            Pane::Calls => {
                let mut out = Vec::new();
                if self.host.calls.is_empty() {
                    out.push(Line::styled("No host calls yet. Each http, field, ai, location (and the rest) call shows here with its answer.", muted));
                }
                for c in &self.host.calls {
                    let source_style = match c.source {
                        "real" => Style::new().fg(Color::Green),
                        "mock" => Style::new().fg(Color::Magenta),
                        "refused" => Style::new().fg(Color::Red),
                        "result" => Style::new().fg(Color::Cyan),
                        _ => Style::new().fg(Color::Yellow),
                    };
                    out.push(Line::from(vec![
                        Span::styled(c.op.clone(), Style::new().add_modifier(Modifier::BOLD)),
                        Span::raw(" "),
                        Span::styled(c.source.to_string(), source_style),
                        Span::styled(format!(" {} ms", c.millis), muted),
                    ]));
                    out.push(Line::styled(format!("  {}", crate::mock::summary(&c.args)), muted));
                    out.push(Line::raw(format!("  → {}", c.reply)));
                }
                out
            }
        }
    }

    /// The events this draw's widgets send, as JSON to copy into the Events pane.
    fn event_examples(&self) -> Vec<String> {
        let mut out = Vec::new();
        for t in self.targets() {
            let e = match t.action {
                Action::Click(id) => json!({"type": "click", "id": id}),
                Action::Command(id) => json!({"type": "command", "id": id}),
                Action::Toggle { id, checked } => json!({"type": "change", "id": id, "value": !checked}),
                Action::Input { id, .. } => json!({"type": "submit", "id": id, "value": "text"}),
                Action::Slider { id, value, .. } => json!({"type": "change", "id": id, "value": value}),
                Action::Key { id, key } => json!({"type": "press", "id": id, "value": key}),
                Action::Pick { id, index } => json!({"type": "select", "id": id, "value": index}),
                _ => continue,
            };
            let text = e.to_string();
            if !out.contains(&text) {
                out.push(text);
            }
        }
        for id in self.host.meta["timers"].as_array().into_iter().flatten().filter_map(|t| t["id"].as_str()) {
            out.push(json!({"type": "timer", "id": id}).to_string());
        }
        out
    }

    fn draw_status(&self, f: &mut Frame, area: Rect) {
        let muted = Style::new().fg(Color::DarkGray);
        let state = match self.host.status {
            crate::devhost::Status::Ready => Span::styled(" ready ", Style::new().fg(Color::Black).bg(Color::Green)),
            crate::devhost::Status::Failed => Span::styled(" failed ", Style::new().fg(Color::White).bg(Color::Red)),
            crate::devhost::Status::NoPanel => Span::styled(" no panel ", Style::new().fg(Color::Black).bg(Color::Yellow)),
        };
        let usage = self
            .host
            .usage
            .iter()
            .map(|p| format!("{} {}/{}", p["phase"].as_str().unwrap_or(""), short_number(p["steps"].as_u64().unwrap_or(0)), short_number(p["stepsLimit"].as_u64().unwrap_or(0))))
            .collect::<Vec<_>>()
            .join(" · ");
        let keys = if self.editing && !self.in_pane {
            "typing: Esc stops · Enter submits"
        } else if self.in_pane {
            "pane: Esc back to the panel · [ ] other tabs"
        } else {
            "Tab/arrows move · Enter press · h hold · r reload · Ctrl-O pane · ? help · q quit"
        };
        let watch = if self.host.opts.watch { "watching" } else { "not watching" };
        f.render_widget(Paragraph::new(Line::from(vec![state, Span::styled(format!(" {usage} "), muted), Span::styled(format!(" {watch} · "), muted), Span::raw(keys)])), area);
    }

    fn draw_help(&self, f: &mut Frame, area: Rect) {
        let lines = vec![
            Line::styled("wmpy run", Style::new().add_modifier(Modifier::BOLD)),
            Line::raw(""),
            Line::raw("Tab, Shift-Tab    next and previous control"),
            Line::raw("Up, Down          the control above or below"),
            Line::raw("Left, Right       move a slider, change a dropdown"),
            Line::raw("Enter, Space      press; in a text box, submit"),
            Line::raw("h                 hold (on a holdable button, card or canvas)"),
            Line::raw("Esc               stop typing in a text box"),
            Line::raw("PageUp, PageDown  scroll the panel (the mouse wheel too)"),
            Line::raw("Ctrl-O, F6        switch between the panel and the pane"),
            Line::raw("1-5, F1-F5, [ ]   Console, Events, Storage, Field, Calls"),
            Line::raw("r, Ctrl-R         reload the plugin (storage stays)"),
            Line::raw("q, Ctrl-C         quit"),
            Line::raw(""),
            Line::raw("Click any control, drag on a canvas. Saving a file reloads."),
            Line::styled("Press any key to close this.", Style::new().fg(Color::DarkGray)),
        ];
        let w = 66.min(area.width);
        let h = (lines.len() as u16 + 2).min(area.height);
        let r = Rect::new(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h);
        f.render_widget(Clear, r);
        f.render_widget(Paragraph::new(lines).block(Block::new().borders(Borders::ALL).border_type(BorderType::Rounded).title(" Help ")), r);
    }
}

/// [lines] word-wrapped at [width], so a pane knows its exact height.
fn wrap_lines(lines: Vec<Line<'static>>, width: u16) -> Vec<Line<'static>> {
    let mut out = Vec::new();
    for line in lines {
        let chars: Vec<(char, Style)> = line.spans.iter().flat_map(|sp| {
            let st = line.style.patch(sp.style);
            sp.content.chars().map(move |c| (c, st)).collect::<Vec<_>>()
        }).collect();
        for l in layout::wrap(&chars, width.max(1) as usize) {
            let mut spans: Vec<Span<'static>> = Vec::new();
            let mut i = 0;
            while i < l.len() {
                let st = l[i].1;
                let mut run = String::new();
                while i < l.len() && l[i].1 == st {
                    run.push(l[i].0);
                    i += 1;
                }
                spans.push(Span::styled(run, st));
            }
            out.push(Line::from(spans));
        }
    }
    out
}

fn same_widget(a: &Action, b: &Action) -> bool {
    use Action::*;
    match (a, b) {
        (Toggle { id: x, .. }, Toggle { id: y, .. }) | (Slider { id: x, .. }, Slider { id: y, .. }) | (Select { id: x, .. }, Select { id: y, .. }) | (Input { id: x, .. }, Input { id: y, .. }) => x == y,
        (Canvas { id: x, .. }, Canvas { id: y, .. }) => x == y,
        _ => false,
    }
}

fn canvas_point(t: &Target, point: (u16, u16), w: f64, h: f64) -> (f64, f64) {
    // The canvas has a one-column edge on each side.
    let inner_w = t.rect.width.saturating_sub(2).max(1) as f64;
    let x = ((point.0.saturating_sub(1) as f64 + 0.5) / inner_w * w).clamp(0.0, w);
    let y = ((point.1 as f64 + 0.5) / t.rect.height.max(1) as f64 * h).clamp(0.0, h);
    ((x * 10.0).round() / 10.0, (y * 10.0).round() / 10.0)
}

fn number_json(v: f64) -> Json {
    if v == v.floor() && v.abs() < 1e15 {
        json!(v as i64)
    } else {
        json!(v)
    }
}

fn short_number(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1e6)
    } else if n >= 1000 {
        format!("{:.0}k", n as f64 / 1e3)
    } else {
        n.to_string()
    }
}
