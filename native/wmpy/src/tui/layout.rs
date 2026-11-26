//! A plugin's widget tree drawn into a ratatui [Buffer], top to bottom at a
//! given width, with every place a person can press recorded as a [Target].
//!
//! One code path measures and draws: [draw] with no buffer only adds up
//! heights and targets, so a front end can size the buffer exactly, then
//! draw again into it. Charts, sparklines and canvases are ratatui's own
//! widgets; images and QR codes are boxes that say what they hold.

use std::collections::{HashMap, HashSet};

use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::style::{Color, Modifier, Style};
use ratatui::symbols::Marker;
use ratatui::widgets::canvas::{Canvas, Circle, Line as CanvasLine, Rectangle};
use ratatui::widgets::{Axis, Bar, BarChart, BarGroup, Block, BorderType, Borders, Chart, Dataset, GraphType, Sparkline, Widget};
use serde_json::{json, Value as Json};
use unicode_width::{UnicodeWidthChar, UnicodeWidthStr};

/// What pressing a target does.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// `{"type": "click", "id"}`.
    Click(String),
    Back,
    Command(String),
    /// A switch or checkbox showing [checked].
    Toggle { id: String, checked: bool },
    /// A text box: typing goes into it.
    Input { id: String, multiline: bool, live: bool },
    /// Left and Right move by [step].
    Slider { id: String, value: f64, min: f64, max: f64, step: f64 },
    /// A stepper's − or +: the value it sets.
    Set { id: String, value: Json },
    /// A dropdown: Enter and Right pick the next option, Left the one before.
    Select { id: String, options: Vec<String>, value: Option<String> },
    /// `{"type": "press", "id", "value": key}`.
    Key { id: String, key: String },
    /// `{"type": "select", "id", "value": index}`.
    Pick { id: String, index: usize },
    /// An item's ⋯: shows or hides its actions.
    Actions(String),
    ItemAction { id: String, action: String },
    /// A collapsible section's header, by its key, and whether it is open now.
    Section { key: String, open: bool },
    /// One tab: [key] remembers the choice, [id] is told.
    Tab { id: String, key: String, index: usize },
    Insert(String),
    Copy(String),
    /// A canvas, [w] by [h] in the plugin's units.
    Canvas { id: String, w: f64, h: f64, tappable: bool, draggable: bool },
    /// A date's button: the new value (seconds) or None to clear.
    Date { id: String, value: Option<i64> },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Target {
    /// In the content's own coordinates (top of the tree is y 0).
    pub rect: Rect,
    pub action: Action,
    /// Holdable: `h` sends a hold.
    pub hold: Option<String>,
}

/// What the panel remembers between draws that the plugin does not hold:
/// which tab is shown, which sections are folded, what is typed.
#[derive(Clone, Debug, Default)]
pub struct Local {
    pub tabs: HashMap<String, usize>,
    /// Sections opened or folded by the person, by key.
    pub sections: HashMap<String, bool>,
    pub actions_open: HashSet<String>,
    /// Text typed into each box, and the cursor, in characters.
    pub edits: HashMap<String, (String, usize)>,
}

pub struct Ctx<'a> {
    pub buf: Option<&'a mut Buffer>,
    pub targets: Vec<Target>,
    /// The index of the focused target, to draw it highlighted.
    pub focus: Option<usize>,
    /// Typing goes into the focused box: draw its cursor.
    pub editing: bool,
    pub local: &'a Local,
    pub dark: bool,
    pub v5: bool,
    /// Where in the tree the walk is, for keys of widgets without an id.
    path: Vec<usize>,
}

impl<'a> Ctx<'a> {
    pub fn new(buf: Option<&'a mut Buffer>, local: &'a Local, focus: Option<usize>, editing: bool, dark: bool, v5: bool) -> Ctx<'a> {
        Ctx { buf, targets: Vec::new(), focus, editing, local, dark, v5, path: Vec::new() }
    }

    fn accent(&self) -> Color {
        if self.dark {
            Color::LightBlue
        } else {
            Color::Blue
        }
    }

    fn muted(&self) -> Style {
        Style::new().fg(Color::DarkGray)
    }

    /// Records a target; true when it is the focused one.
    fn target(&mut self, rect: Rect, action: Action, hold: Option<String>) -> bool {
        let focused = self.focus == Some(self.targets.len());
        self.targets.push(Target { rect, action, hold });
        focused
    }

    fn focus_style(&self, focused: bool, base: Style) -> Style {
        if focused {
            base.add_modifier(Modifier::REVERSED)
        } else {
            base
        }
    }

    fn put(&mut self, x: u16, y: u16, text: &str, style: Style, max: u16) -> u16 {
        match self.buf.as_deref_mut() {
            Some(buf) if y < buf.area.bottom() && x < buf.area.right() => {
                let max = max.min(buf.area.right() - x);
                buf.set_stringn(x, y, text, max as usize, style).0 - x
            }
            _ => (text.width() as u16).min(max),
        }
    }

    fn put_runs(&mut self, x: u16, y: u16, line: &[(char, Style)], max: u16) {
        let mut at = x;
        let mut i = 0;
        while i < line.len() {
            let style = line[i].1;
            let mut run = String::new();
            while i < line.len() && line[i].1 == style {
                run.push(line[i].0);
                i += 1;
            }
            let used = self.put(at, y, &run, style, max.saturating_sub(at - x));
            at += used;
        }
    }

    fn key(&self, w: &Json) -> String {
        let id = s(w, "id");
        if id.is_empty() {
            self.path.iter().map(|p| p.to_string()).collect::<Vec<_>>().join("/")
        } else {
            id
        }
    }

    fn render(&mut self, area: Rect, widget: impl Widget) {
        if let Some(buf) = self.buf.as_deref_mut() {
            let area = area.intersection(buf.area);
            if !area.is_empty() {
                widget.render(area, buf);
            }
        }
    }
}

pub fn s(w: &Json, k: &str) -> String {
    match w.get(k) {
        Some(Json::String(t)) => t.clone(),
        Some(Json::Null) | None => String::new(),
        Some(other) => other.to_string(),
    }
}

fn b(w: &Json, k: &str) -> bool {
    w.get(k).and_then(Json::as_bool).unwrap_or(false)
}

fn n(w: &Json, k: &str, default: f64) -> f64 {
    w.get(k).and_then(Json::as_f64).unwrap_or(default)
}

fn kids(w: &Json) -> &[Json] {
    w.get("children").or_else(|| w.get("items")).and_then(Json::as_array).map(Vec::as_slice).unwrap_or(&[])
}

/// A colour role as a terminal colour.
pub fn role(r: &str, dark: bool) -> Option<Color> {
    Some(match r {
        "primary" | "accent" => {
            if dark {
                Color::LightBlue
            } else {
                Color::Blue
            }
        }
        "secondary" | "muted" => Color::DarkGray,
        "danger" => Color::Red,
        "success" => Color::Green,
        "warning" => Color::Yellow,
        _ => return None,
    })
}

/// [text] in [style], as styled characters.
fn styled(text: &str, style: Style) -> Vec<(char, Style)> {
    text.chars().map(|c| (c, style)).collect()
}

/// Greedy word wrap of styled characters at [width] columns; `\n` breaks.
pub fn wrap(chars: &[(char, Style)], width: usize) -> Vec<Vec<(char, Style)>> {
    let width = width.max(1);
    let mut lines = Vec::new();
    let mut line: Vec<(char, Style)> = Vec::new();
    let mut used = 0;
    let mut last_space: Option<usize> = None;
    for &(c, st) in chars {
        if c == '\n' {
            lines.push(std::mem::take(&mut line));
            used = 0;
            last_space = None;
            continue;
        }
        let cw = c.width().unwrap_or(0);
        if used + cw > width && !line.is_empty() {
            match last_space {
                Some(sp) => {
                    let rest = line.split_off(sp + 1);
                    line.truncate(sp);
                    lines.push(std::mem::take(&mut line));
                    line = rest;
                }
                None => lines.push(std::mem::take(&mut line)),
            }
            used = line.iter().map(|(c, _)| c.width().unwrap_or(0)).sum();
            last_space = None;
            if c == ' ' && line.is_empty() {
                continue;
            }
        }
        if c == ' ' {
            last_space = Some(line.len());
        }
        line.push((c, st));
        used += cw;
    }
    lines.push(line);
    lines
}

fn text_block(ctx: &mut Ctx, chars: &[(char, Style)], x: u16, y: u16, width: u16) -> u16 {
    let lines = wrap(chars, width as usize);
    for (i, l) in lines.iter().enumerate() {
        ctx.put_runs(x, y + i as u16, l, width);
    }
    lines.len() as u16
}

/// Draw a list of widgets as a column. Returns the height used.
pub fn draw(ctx: &mut Ctx, list: &[Json], x: u16, y: u16, width: u16) -> u16 {
    column(ctx, list, x, y, width, 0)
}

fn column(ctx: &mut Ctx, list: &[Json], x: u16, y: u16, width: u16, gap: u16) -> u16 {
    let mut h = 0;
    for (i, w) in list.iter().enumerate() {
        if i > 0 && gap > 0 {
            h += gap;
        }
        ctx.path.push(i);
        h += widget(ctx, w, x, y + h, width);
        ctx.path.pop();
    }
    h
}

/// The width a widget takes in a row when it has one of its own.
fn natural(w: &Json) -> Option<u16> {
    let t = s(w, "type");
    let width = |text: &str| text.width() as u16;
    Some(match t.as_str() {
        "label" => {
            let text = s(w, "text");
            if text.contains('\n') {
                return None;
            }
            width(&text)
        }
        "button" => width(&s(w, "text")) + 4 + if s(w, "icon").is_empty() { 0 } else { width(&s(w, "icon")) + 1 },
        "icon" => width(&s(w, "name")) + 2,
        "toggle" | "checkbox" => width(&s(w, "label")) + 6,
        "stat" => [s(w, "label"), s(w, "value"), s(w, "caption")].iter().map(|t| width(t)).max().unwrap_or(0),
        "spacer" => 1,
        "progress" | "ring" => 12,
        _ => return None,
    })
}

fn row(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let children = kids(w);
    if children.is_empty() {
        return 0;
    }
    let gap = 1u16;
    let count = children.len() as u16;
    let weights: Option<Vec<f64>> = w.get("weights").and_then(Json::as_array).map(|a| a.iter().map(|x| x.as_f64().unwrap_or(1.0).max(0.0)).collect());
    let free = width.saturating_sub(gap * (count - 1));
    let widths: Vec<u16> = if let Some(weights) = weights {
        let total: f64 = (0..children.len()).map(|i| weights.get(i).copied().unwrap_or(1.0)).sum::<f64>().max(0.0001);
        let mut out: Vec<u16> = (0..children.len()).map(|i| ((weights.get(i).copied().unwrap_or(1.0) / total) * free as f64).floor().max(1.0) as u16).collect();
        let used: u16 = out.iter().sum();
        if let Some(last) = out.last_mut() {
            *last += free.saturating_sub(used);
        }
        out
    } else {
        let naturals: Vec<Option<u16>> = children.iter().map(natural).collect();
        let fixed: u16 = naturals.iter().flatten().sum();
        let flex = naturals.iter().filter(|n| n.is_none()).count() as u16;
        if flex == 0 && fixed <= free {
            naturals.iter().map(|n| n.unwrap_or(1)).collect()
        } else if flex == 0 {
            // Too wide for one line: wrap, as a phone row of chips would.
            return flow(ctx, children, &naturals, x, y, width);
        } else if fixed + flex * 12 <= free {
            let share = (free - fixed) / flex;
            let mut out: Vec<u16> = naturals.iter().map(|n| n.unwrap_or(share)).collect();
            let used: u16 = out.iter().sum();
            if let Some(i) = naturals.iter().rposition(|n| n.is_none()) {
                out[i] += free.saturating_sub(used);
            }
            out
        } else {
            // No room side by side: one under the other.
            return column(ctx, children, x, y, width, 0);
        }
    };
    let mut at = x;
    let mut h = 0;
    for (i, (child, cw)) in children.iter().zip(widths).enumerate() {
        ctx.path.push(i);
        h = h.max(widget(ctx, child, at, y, cw));
        ctx.path.pop();
        at += cw + gap;
    }
    h
}

fn flow(ctx: &mut Ctx, children: &[Json], naturals: &[Option<u16>], x: u16, y: u16, width: u16) -> u16 {
    let (mut at, mut line_y, mut line_h) = (x, y, 0u16);
    for (i, (child, nat)) in children.iter().zip(naturals).enumerate() {
        let cw = nat.unwrap_or(width).min(width);
        if at > x && at + cw > x + width {
            line_y += line_h.max(1);
            at = x;
            line_h = 0;
        }
        ctx.path.push(i);
        line_h = line_h.max(widget(ctx, child, at, line_y, cw));
        ctx.path.pop();
        at += cw + 1;
    }
    line_y + line_h - y
}

fn button(ctx: &mut Ctx, x: u16, y: u16, text: &str, primary: bool, enabled: bool, color: Option<Color>, action: Option<Action>, hold: Option<String>) -> u16 {
    let label = format!("[ {text} ]");
    let w = label.width() as u16;
    let mut style = Style::new();
    if primary {
        style = style.fg(ctx.accent()).add_modifier(Modifier::BOLD);
    } else if let Some(c) = color {
        style = style.fg(c);
    }
    if !enabled {
        style = ctx.muted().add_modifier(Modifier::DIM);
    }
    let focused = match action.filter(|_| enabled) {
        Some(a) => ctx.target(Rect::new(x, y, w, 1), a, hold),
        None => false,
    };
    let style = ctx.focus_style(focused, style);
    ctx.put(x, y, &label, style, w)
}

fn widget(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    if width == 0 {
        return 0;
    }
    let t = s(w, "type");
    let id = s(w, "id");
    let dark = ctx.dark;
    match t.as_str() {
        "column" => column(ctx, kids(w), x, y, width, (n(w, "gap", 0.0) / 12.0).floor() as u16),
        "row" => row(ctx, w, x, y, width),
        "grid" => {
            let cols = (n(w, "columns", 3.0) as u16).clamp(1, 6);
            let cell = (width.saturating_sub(cols - 1) / cols).max(1);
            let mut h = 0;
            for (r, chunk) in kids(w).chunks(cols as usize).enumerate() {
                let mut row_h = 0;
                for (c, child) in chunk.iter().enumerate() {
                    ctx.path.push(r * cols as usize + c);
                    row_h = row_h.max(widget(ctx, child, x + c as u16 * (cell + 1), y + h, cell));
                    ctx.path.pop();
                }
                h += row_h;
            }
            h
        }
        "card" => {
            let clickable = b(w, "clickable");
            let hold = b(w, "holdable").then(|| id.clone());
            let inner = width.saturating_sub(4);
            let mut body = if s(w, "title").is_empty() { 0 } else { 1 };
            // The card's own target first, so focus reaches it before its contents.
            let index = ctx.targets.len();
            let focused = if clickable || hold.is_some() { ctx.target(Rect::new(x, y, width, 1), Action::Click(id.clone()), hold.clone()) } else { false };
            if !s(w, "title").is_empty() {
                let title = s(w, "title");
                ctx.put(x + 2, y + 1, &title, Style::new().add_modifier(Modifier::BOLD), inner);
            }
            body += column(ctx, kids(w), x + 2, y + 1 + body, inner, 0);
            let h = body + 2;
            if clickable || ctx.targets.get(index).is_some_and(|t| t.hold.is_some()) {
                if let Some(t) = ctx.targets.get_mut(index) {
                    t.rect.height = h;
                }
            }
            let border = if focused { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { ctx.muted() };
            ctx.render(Rect::new(x, y, width, h), Block::new().borders(Borders::ALL).border_type(BorderType::Rounded).border_style(border));
            h
        }
        "section" => {
            let key = ctx.key(w);
            let collapsible = b(w, "collapsible");
            let default = !collapsible || w.get("open").and_then(Json::as_bool).unwrap_or(true);
            let open = if collapsible { ctx.local.sections.get(&key).copied().unwrap_or(default) } else { true };
            let title = format!("{}{}", s(w, "title"), if collapsible { if open { " ▾" } else { " ▸" } } else { "" });
            let tw = (title.width() as u16).min(width);
            let focused = collapsible && ctx.target(Rect::new(x, y, tw, 1), Action::Section { key, open }, None);
            let style = ctx.focus_style(focused, ctx.muted().add_modifier(Modifier::BOLD));
            ctx.put(x, y, &title, style, width);
            1 + if open { column(ctx, kids(w), x, y + 1, width, 0) } else { 0 }
        }
        "scroll" => {
            let h = column(ctx, kids(w), x + 2, y, width.saturating_sub(2), 0);
            let muted = ctx.muted();
            for r in 0..h {
                ctx.put(x, y + r, "│", muted, 1);
            }
            h
        }
        "label" => {
            let style_name = s(w, "style");
            let mut style = Style::new();
            match style_name.as_str() {
                "title" | "headline" => style = style.add_modifier(Modifier::BOLD),
                "caption" => style = ctx.muted(),
                _ => {}
            }
            if let Some(c) = role(&s(w, "color"), dark) {
                style = style.fg(c);
            }
            let text = if style_name == "headline" { s(w, "text").to_uppercase() } else { s(w, "text") };
            text_block(ctx, &styled(&text, style), x, y, width)
        }
        "markdown" => markdown(ctx, &s(w, "text"), x, y, width),
        "output" => {
            let text = s(w, "text");
            let style = if b(w, "mono") { Style::new().fg(Color::Cyan) } else { Style::new() };
            let mut h = text_block(ctx, &styled(&text, style), x + 2, y, width.saturating_sub(2));
            let muted = ctx.muted();
            for r in 0..h {
                ctx.put(x, y + r, "▌", muted, 1);
            }
            let mut actions = Vec::new();
            if b(w, "insertable") {
                actions.push(("Insert", Action::Insert(text.clone())));
            }
            if b(w, "copyable") {
                actions.push(("Copy", Action::Copy(text.clone())));
            }
            let mut at = x + 2;
            for (i, (label, action)) in actions.into_iter().enumerate() {
                let bw = label.width() as u16 + 4;
                if i > 0 && at + bw > x + width {
                    h += 1;
                    at = x + 2;
                }
                at += button(ctx, at, y + h, label, false, true, None, Some(action), None) + 1;
            }
            if b(w, "insertable") || b(w, "copyable") {
                h += 1;
            }
            h
        }
        "button" => {
            let text = if s(w, "icon").is_empty() { s(w, "text") } else { format!("‹{}› {}", s(w, "icon"), s(w, "text")).trim().to_string() };
            let hold = b(w, "holdable").then(|| id.clone());
            button(ctx, x, y, &text, b(w, "primary"), w.get("enabled").and_then(Json::as_bool).unwrap_or(true), role(&s(w, "color"), dark), Some(Action::Click(id)), hold);
            1
        }
        "empty" => {
            let mut h = 0;
            let muted = ctx.muted();
            if !s(w, "icon").is_empty() {
                let icon = format!("‹{}›", s(w, "icon"));
                let iw = icon.width() as u16;
                ctx.put(x + width.saturating_sub(iw) / 2, y, &icon, muted, width);
                h += 1;
            }
            let lines = wrap(&styled(&s(w, "text"), muted), width as usize);
            for l in &lines {
                let lw: u16 = l.iter().map(|(c, _)| c.width().unwrap_or(0) as u16).sum();
                ctx.put_runs(x + width.saturating_sub(lw) / 2, y + h, l, width);
                h += 1;
            }
            if !s(w, "action").is_empty() {
                let label = s(w, "action");
                let bw = label.width() as u16 + 4;
                button(ctx, x + width.saturating_sub(bw) / 2, y + h, &label, false, true, None, Some(Action::Click(id)), None);
                h += 1;
            }
            h
        }
        "toggle" | "checkbox" => {
            let checked = b(w, "checked");
            let mark = match (t.as_str(), checked) {
                ("toggle", true) => "[ON ]",
                ("toggle", false) => "[OFF]",
                (_, true) => "[x]",
                _ => "[ ]",
            };
            let mw = mark.width() as u16;
            let focused = ctx.target(Rect::new(x, y, mw, 1), Action::Toggle { id, checked }, None);
            let mark_style = ctx.focus_style(focused, if checked { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { Style::new() });
            ctx.put(x, y, mark, mark_style, width);
            let label = s(w, "label");
            1.max(text_block(ctx, &styled(&label, Style::new()), x + mw + 1, y, width.saturating_sub(mw + 1)))
        }
        "input" | "search" => input(ctx, w, &t, x, y, width),
        "slider" => {
            let (value, min, max, step) = (n(w, "value", 0.0), n(w, "min", 0.0), n(w, "max", 100.0), n(w, "step", 1.0));
            let label = if s(w, "label").is_empty() { number(value) } else { format!("{}: {}", s(w, "label"), number(value)) };
            ctx.put(x, y, &label, ctx.muted(), width);
            let bar = width.saturating_sub(2).max(3);
            let share = if max > min { ((value - min) / (max - min)).clamp(0.0, 1.0) } else { 0.0 };
            let knob = ((bar - 1) as f64 * share).round() as u16;
            let focused = ctx.target(Rect::new(x, y + 1, width, 1), Action::Slider { id, value, min, max, step }, None);
            let accent = ctx.accent();
            let style = ctx.focus_style(focused, Style::new());
            ctx.put(x, y + 1, "◀", style, 1);
            for i in 0..bar {
                let (c, st) = if i == knob { ("●", Style::new().fg(accent).add_modifier(Modifier::BOLD)) } else if i < knob { ("━", Style::new().fg(accent)) } else { ("─", ctx.muted()) };
                ctx.put(x + 1 + i, y + 1, c, st, 1);
            }
            ctx.put(x + 1 + bar, y + 1, "▶", style, 1);
            2
        }
        "stepper" => {
            let (value, step) = (n(w, "value", 0.0), n(w, "step", 1.0));
            let (min, max) = (n(w, "min", -1e9), n(w, "max", 1e9));
            let mut at = x;
            if !s(w, "label").is_empty() {
                let label = s(w, "label");
                at += ctx.put(at, y, &label, Style::new(), width) + 2;
            }
            let down = Action::Set { id: id.clone(), value: json!((value - step).clamp(min, max)) };
            let up = Action::Set { id, value: json!((value + step).clamp(min, max)) };
            at += button(ctx, at, y, "−", false, value - step >= min, None, Some(down), None) + 1;
            let shown = number(value);
            at += ctx.put(at, y, &shown, Style::new().add_modifier(Modifier::BOLD), 12) + 1;
            button(ctx, at, y, "+", false, value + step <= max, None, Some(up), None);
            1
        }
        "select" => {
            let options: Vec<(String, String)> = w.get("options").and_then(Json::as_array).into_iter().flatten().map(|o| (s(o, "value"), s(o, "label"))).collect();
            let value = w.get("value").and_then(Json::as_str).map(String::from);
            let mut h = 0;
            if !s(w, "label").is_empty() {
                let label = s(w, "label");
                ctx.put(x, y, &label, ctx.muted(), width);
                h += 1;
            }
            if s(w, "style") == "segmented" {
                let mut at = x;
                for (v, l) in &options {
                    let on = value.as_deref() == Some(v.as_str());
                    let text = if on { format!("● {l}") } else { l.clone() };
                    let cw = text.width() as u16 + 4;
                    if at > x && at + cw > x + width {
                        h += 1;
                        at = x;
                    }
                    let style = if on { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { Style::new() };
                    let focused = ctx.target(Rect::new(at, y + h, cw, 1), Action::Set { id: id.clone(), value: json!(v) }, None);
                    let st = ctx.focus_style(focused, style);
                    at += ctx.put(at, y + h, &format!("( {text} )"), st, cw) + 1;
                }
                return h + 1;
            }
            let shown = options.iter().find(|(v, _)| Some(v) == value.as_ref()).map(|(_, l)| l.clone()).unwrap_or_else(|| "—".into());
            let text = format!("[ {shown} ▾ ]");
            let tw = (text.width() as u16).min(width);
            let focused = ctx.target(Rect::new(x, y + h, tw, 1), Action::Select { id, options: options.into_iter().map(|(v, _)| v).collect(), value }, None);
            let st = ctx.focus_style(focused, Style::new());
            ctx.put(x, y + h, &text, st, width);
            h + 1
        }
        "chips" => {
            let multi = b(w, "multi");
            let chosen: Vec<String> = w.get("value").and_then(Json::as_array).into_iter().flatten().filter_map(Json::as_str).map(String::from).collect();
            let options: Vec<(String, String)> = w.get("options").and_then(Json::as_array).into_iter().flatten().map(|o| (s(o, "value"), s(o, "label"))).collect();
            let (mut at, mut h) = (x, 0u16);
            for (v, l) in &options {
                let on = chosen.contains(v);
                let text = format!("( {}{l} )", if on { "✓ " } else { "" });
                let cw = text.width() as u16;
                if at > x && at + cw > x + width {
                    h += 1;
                    at = x;
                }
                let value = if multi {
                    let mut next: Vec<String> = options.iter().map(|(o, _)| o.clone()).filter(|o| if o == v { !on } else { chosen.contains(o) }).collect();
                    next.dedup();
                    json!(next)
                } else {
                    json!(v)
                };
                let focused = ctx.target(Rect::new(at, y + h, cw, 1), Action::Set { id: id.clone(), value }, None);
                let style = if on { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { Style::new() };
                let st = ctx.focus_style(focused, style);
                at += ctx.put(at, y + h, &text, st, cw) + 1;
            }
            h + 1
        }
        "keys" => {
            let rows: Vec<Vec<String>> = w.get("rows").and_then(Json::as_array).into_iter().flatten().map(|r| r.as_array().into_iter().flatten().map(|k| k.as_str().unwrap_or("").to_string()).collect()).collect();
            for (r, keys) in rows.iter().enumerate() {
                let count = keys.len().max(1) as u16;
                let kw = (width.saturating_sub(count - 1) / count).max(3);
                for (i, k) in keys.iter().enumerate() {
                    let kx = x + i as u16 * (kw + 1);
                    let focused = ctx.target(Rect::new(kx, y + r as u16, kw, 1), Action::Key { id: id.clone(), key: k.clone() }, None);
                    let st = ctx.focus_style(focused, Style::new().add_modifier(Modifier::BOLD));
                    let inner = kw.saturating_sub(2);
                    let label: String = k.chars().take(inner as usize).collect();
                    let pad = inner.saturating_sub(label.width() as u16);
                    let text = format!("[{}{label}{}]", " ".repeat((pad / 2) as usize), " ".repeat((pad - pad / 2) as usize));
                    ctx.put(kx, y + r as u16, &text, st, kw);
                }
            }
            rows.len() as u16
        }
        "list" => {
            let items = kids(w);
            if items.is_empty() {
                let empty = s(w, "empty");
                return if empty.is_empty() { 0 } else { text_block(ctx, &styled(&empty, ctx.muted()), x, y, width) };
            }
            let selectable = b(w, "selectable");
            let mut h = 0;
            for (i, item) in items.iter().enumerate() {
                ctx.path.push(i);
                let index = ctx.targets.len();
                if selectable {
                    ctx.target(Rect::new(x, y + h, width, 1), Action::Pick { id: id.clone(), index: i }, None);
                }
                let ih = widget(ctx, item, x, y + h, width);
                if selectable {
                    if let Some(t) = ctx.targets.get_mut(index) {
                        t.rect.height = ih.max(1);
                    }
                    if ctx.focus == Some(index) {
                        let accent = ctx.accent();
                        for r in 0..ih.max(1) {
                            ctx.put(x, y + h + r, "▌", Style::new().fg(accent), 1);
                        }
                    }
                }
                ctx.path.pop();
                h += ih;
            }
            h
        }
        "item" => item(ctx, w, x, y, width),
        "image" => {
            let alt = s(w, "alt");
            let src = s(w, "src");
            let what = if !alt.is_empty() {
                alt
            } else if src.starts_with("data:") {
                "picture".into()
            } else {
                src.clone()
            };
            let rows = ((n(w, "height", 48.0) / 16.0).round() as u16).clamp(1, 6);
            let h = rows + 2;
            let clickable = b(w, "clickable");
            let focused = clickable && ctx.target(Rect::new(x, y, width, h), Action::Click(id), None);
            let border = if focused { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { ctx.muted() };
            ctx.render(Rect::new(x, y, width, h), Block::new().borders(Borders::ALL).border_type(BorderType::Plain).border_style(border).title(" image "));
            ctx.put(x + 2, y + 1 + rows / 2, &format!("▣ {what}"), ctx.muted(), width.saturating_sub(4));
            if b(w, "insertable") {
                button(ctx, x, y + h, "Insert", false, true, None, Some(Action::Insert(src)), None);
                return h + 1;
            }
            h
        }
        "icon" => {
            let mut style = ctx.muted();
            if let Some(c) = role(&s(w, "color"), dark) {
                style = Style::new().fg(c);
            }
            ctx.put(x, y, &format!("‹{}›", s(w, "name")), style, width);
            1
        }
        "qr" => {
            let h = 3;
            ctx.render(Rect::new(x, y, width.min(40), h), Block::new().borders(Borders::ALL).border_style(ctx.muted()).title(" QR code "));
            ctx.put(x + 2, y + 1, &format!("▦ {}", s(w, "text")), Style::new(), width.min(40).saturating_sub(4));
            h
        }
        "stat" => {
            let mut h = 0;
            let muted = ctx.muted();
            h += text_block(ctx, &styled(&s(w, "label"), muted), x, y, width);
            h += text_block(ctx, &styled(&s(w, "value"), Style::new().add_modifier(Modifier::BOLD)), x, y + h, width);
            if !s(w, "caption").is_empty() {
                h += text_block(ctx, &styled(&s(w, "caption"), muted), x, y + h, width);
            }
            h
        }
        "ring" => {
            let v = n(w, "value", 0.0).clamp(0.0, 1.0);
            let glyph = ["○", "◔", "◑", "◕", "●"][(v * 4.0).round() as usize];
            let label = if s(w, "label").is_empty() { format!("{}%", (v * 100.0).round()) } else { s(w, "label") };
            let accent = ctx.accent();
            let used = ctx.put(x, y, glyph, Style::new().fg(accent).add_modifier(Modifier::BOLD), width);
            ctx.put(x + used + 1, y, &label, Style::new().add_modifier(Modifier::BOLD), width.saturating_sub(used + 1));
            if !s(w, "caption").is_empty() {
                let caption = s(w, "caption");
                return 1 + text_block(ctx, &styled(&caption, ctx.muted()), x, y + 1, width);
            }
            1
        }
        "progress" => {
            let accent = ctx.accent();
            match w.get("value").and_then(Json::as_f64) {
                Some(v) => {
                    let v = v.clamp(0.0, 1.0);
                    let label = format!(" {}%", (v * 100.0).round());
                    let bar = width.saturating_sub(label.width() as u16).max(1);
                    let filled = (bar as f64 * v).round() as u16;
                    ctx.put(x, y, &"█".repeat(filled as usize), Style::new().fg(accent), bar);
                    ctx.put(x + filled, y, &"░".repeat((bar - filled) as usize), ctx.muted(), bar - filled);
                    ctx.put(x + bar, y, &label, ctx.muted(), width);
                }
                None => {
                    let pattern: String = (0..width).map(|i| if (i / 3) % 3 == 0 { '▰' } else { '▱' }).collect();
                    ctx.put(x, y, &pattern, Style::new().fg(accent), width);
                }
            }
            1
        }
        "chart" => chart(ctx, w, x, y, width),
        "canvas" => canvas(ctx, w, x, y, width),
        "table" => table(ctx, w, x, y, width),
        "code" => {
            let text = s(w, "text");
            let numbers = b(w, "lineNumbers");
            let lines: Vec<&str> = text.split('\n').collect();
            let gutter = if numbers { lines.len().to_string().len() as u16 + 1 } else { 0 };
            let muted = ctx.muted();
            for (i, l) in lines.iter().enumerate() {
                if numbers {
                    ctx.put(x, y + i as u16, &format!("{:>w$}", i + 1, w = (gutter - 1) as usize), muted, gutter);
                }
                let shown: String = if l.width() as u16 > width.saturating_sub(gutter + 1) { format!("{}…", l.chars().take(width.saturating_sub(gutter + 2) as usize).collect::<String>()) } else { l.to_string() };
                ctx.put(x + gutter + if numbers { 1 } else { 0 }, y + i as u16, &shown, Style::new().fg(Color::Cyan), width.saturating_sub(gutter + 1));
            }
            lines.len() as u16
        }
        "font_sample" => {
            let text = if s(w, "text").is_empty() { "The quick brown fox jumps over the lazy dog 0123456789".to_string() } else { s(w, "text") };
            let count = w.get("sizes").and_then(Json::as_array).map_or(3, Vec::len).max(1);
            let mut h = 0;
            for _ in 0..count {
                h += text_block(ctx, &styled(&text, Style::new()), x, y + h, width);
            }
            h
        }
        "spacer" => (n(w, "height", 8.0) / 12.0).floor() as u16,
        "divider" => {
            ctx.put(x, y, &"─".repeat(width as usize), ctx.muted(), width);
            1
        }
        "tabs" => {
            let key = ctx.key(w);
            let pages: Vec<Json> = w.get("pages").and_then(Json::as_array).cloned().unwrap_or_default();
            if pages.is_empty() {
                return 0;
            }
            let current = ctx.local.tabs.get(&key).copied().unwrap_or(0).min(pages.len() - 1);
            let mut at = x;
            let mut h = 0;
            for (i, p) in pages.iter().enumerate() {
                let title = if s(p, "title").is_empty() { format!("Tab {}", i + 1) } else { s(p, "title") };
                let text = format!(" {title} ");
                let tw = text.width() as u16;
                if at > x && at + tw > x + width {
                    h += 1;
                    at = x;
                }
                let focused = ctx.target(Rect::new(at, y + h, tw, 1), Action::Tab { id: id.clone(), key: key.clone(), index: i }, None);
                let style = if i == current { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD | Modifier::UNDERLINED) } else { ctx.muted() };
                let st = ctx.focus_style(focused, style);
                at += ctx.put(at, y + h, &text, st, tw) + 1;
            }
            h += 1;
            ctx.path.push(current);
            let page = pages[current].get("children").and_then(Json::as_array).cloned().unwrap_or_default();
            h += column(ctx, &page, x, y + h, width, 0);
            ctx.path.pop();
            h
        }
        "date" => date(ctx, w, x, y, width),
        other => {
            let muted = ctx.muted();
            ctx.put(x, y, &format!("‹{other}›"), muted, width);
            1
        }
    }
}

fn number(v: f64) -> String {
    if v == v.floor() && v.abs() < 1e15 {
        format!("{}", v as i64)
    } else {
        let t = format!("{v:.4}");
        t.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

fn input(ctx: &mut Ctx, w: &Json, t: &str, x: u16, y: u16, width: u16) -> u16 {
    let id = s(w, "id");
    let multiline = b(w, "multiline");
    let live = b(w, "live") || !ctx.v5;
    let mut h = 0;
    if !s(w, "label").is_empty() {
        let label = s(w, "label");
        ctx.put(x, y, &label, ctx.muted(), width);
        h += 1;
    }
    let (text, cursor) = ctx.local.edits.get(&id).cloned().unwrap_or_default();
    let password = s(w, "kind") == "password";
    let shown: String = if password { "•".repeat(text.chars().count()) } else { text.clone() };
    let prefix = if t == "search" { "⌕ " } else { "" };
    let rows = if multiline { 3 } else { 1 };
    let focused = ctx.target(Rect::new(x, y + h, width, rows), Action::Input { id: id.clone(), multiline, live }, None);
    let editing = focused && ctx.editing;
    let accent = ctx.accent();
    let edge = if focused { Style::new().fg(accent).add_modifier(Modifier::BOLD) } else { ctx.muted() };
    let inner = width.saturating_sub(2 + prefix.width() as u16);
    let chars: Vec<char> = shown.chars().collect();
    let placeholder = s(w, "placeholder");
    let lines: Vec<Vec<(char, Style)>> = if chars.is_empty() && !editing {
        vec![styled(&placeholder, ctx.muted())]
    } else if multiline {
        let mut all = styled(&shown, Style::new());
        if editing {
            all.insert(cursor.min(all.len()), ('\u{2038}', Style::new()));
        }
        let mut l = wrap(&all, inner as usize);
        // The cursor mark stands for a reversed cell.
        for line in l.iter_mut() {
            for c in line.iter_mut() {
                if c.0 == '\u{2038}' {
                    *c = ('▏', Style::new().fg(accent).add_modifier(Modifier::BOLD));
                }
            }
        }
        let keep = l.len().saturating_sub(rows as usize);
        l.drain(..keep);
        l
    } else {
        // One line, scrolled so the cursor shows.
        let cur = cursor.min(chars.len());
        let start = cur.saturating_sub(inner.saturating_sub(1) as usize);
        let visible: String = chars[start..].iter().take(inner as usize).collect();
        let mut line = styled(&visible, Style::new());
        if editing {
            let at = cur - start;
            if at < line.len() {
                line[at].1 = Style::new().add_modifier(Modifier::REVERSED);
            } else {
                line.push((' ', Style::new().add_modifier(Modifier::REVERSED)));
            }
        }
        vec![line]
    };
    for r in 0..rows {
        ctx.put(x, y + h + r, "▏", edge, 1);
        if r == 0 && !prefix.is_empty() {
            ctx.put(x + 1, y + h, prefix, ctx.muted(), 2);
        }
        if let Some(l) = lines.get(r as usize) {
            ctx.put_runs(x + 1 + prefix.width() as u16, y + h + r, l, inner);
        }
    }
    h + rows
}

fn item(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let id = s(w, "id");
    let actions: Vec<String> = w.get("actions").and_then(Json::as_array).into_iter().flatten().filter_map(Json::as_str).map(String::from).collect();
    let clickable = b(w, "clickable");
    let selected = b(w, "selected");
    let more = if actions.is_empty() { 0 } else { 4 };
    let trailing = s(w, "trailing");
    let tw = trailing.width() as u16;
    let mut at = x;
    let icon = s(w, "icon");
    if !icon.is_empty() {
        at += ctx.put(at, y, &format!("‹{icon}› "), ctx.muted(), width);
    }
    let title_w = width.saturating_sub(at - x + more + if tw > 0 { tw + 1 } else { 0 });
    let focused = clickable && ctx.target(Rect::new(x, y, width.saturating_sub(more), 1), Action::Click(id.clone()), None);
    let mut style = if selected { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { Style::new() };
    style = ctx.focus_style(focused, style);
    let title = s(w, "title");
    ctx.put(at, y, &title, style, title_w);
    if tw > 0 {
        ctx.put(x + width.saturating_sub(more + tw), y, &trailing, ctx.muted(), tw);
    }
    if !actions.is_empty() {
        button(ctx, x + width.saturating_sub(3), y, "⋯", false, true, None, Some(Action::Actions(id.clone())), None);
    }
    let mut h = 1;
    if !s(w, "subtitle").is_empty() {
        let sub = s(w, "subtitle");
        h += text_block(ctx, &styled(&sub, ctx.muted()), at, y + 1, width.saturating_sub(at - x));
    }
    if ctx.local.actions_open.contains(&id) {
        let mut ax = at;
        for a in &actions {
            let aw = a.width() as u16 + 4;
            if ax > at && ax + aw > x + width {
                h += 1;
                ax = at;
            }
            ax += button(ctx, ax, y + h, a, false, true, None, Some(Action::ItemAction { id: id.clone(), action: a.clone() }), None) + 1;
        }
        h += 1;
    }
    h
}

fn markdown(ctx: &mut Ctx, text: &str, x: u16, y: u16, width: u16) -> u16 {
    let mut h = 0;
    for line in text.split('\n') {
        let heading = line.starts_with("# ") || line.starts_with("## ") || line.starts_with("### ");
        let bullet = line.trim_start().starts_with("- ") || line.trim_start().starts_with("* ");
        let body = if heading {
            line.trim_start_matches('#').trim_start()
        } else if bullet {
            &line.trim_start()[2..]
        } else {
            line
        };
        let base = if heading { Style::new().add_modifier(Modifier::BOLD) } else { Style::new() };
        let mut chars = Vec::new();
        if bullet {
            chars.extend(styled("• ", base));
        }
        chars.extend(inline_markdown(body, base, ctx.accent()));
        h += text_block(ctx, &chars, x, y + h, width);
    }
    h
}

/// **bold**, *italics*, `code` and [links](https://...), as styles.
fn inline_markdown(text: &str, base: Style, accent: Color) -> Vec<(char, Style)> {
    let mut out = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        if let Some(inner) = rest.strip_prefix("**").and_then(|r| r.split_once("**")) {
            out.extend(styled(inner.0, base.add_modifier(Modifier::BOLD)));
            rest = inner.1;
        } else if let Some(inner) = rest.strip_prefix('`').and_then(|r| r.split_once('`')) {
            out.extend(styled(inner.0, base.fg(Color::Cyan)));
            rest = inner.1;
        } else if let Some(inner) = rest.strip_prefix('*').and_then(|r| r.split_once('*')).filter(|(t, _)| !t.is_empty()) {
            out.extend(styled(inner.0, base.add_modifier(Modifier::ITALIC)));
            rest = inner.1;
        } else if let Some((label, after)) = rest.strip_prefix('[').and_then(|r| r.split_once("](")).filter(|(_, a)| a.starts_with("https://")) {
            match after.split_once(')') {
                Some((_, tail)) => {
                    out.extend(styled(label, base.fg(accent).add_modifier(Modifier::UNDERLINED)));
                    rest = tail;
                }
                None => {
                    out.push(('[', base));
                    rest = &rest[1..];
                }
            }
        } else {
            let c = rest.chars().next().unwrap();
            out.push((c, base));
            rest = &rest[c.len_utf8()..];
        }
    }
    out
}

fn chart(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let series: Vec<(String, Vec<f64>)> = w
        .get("series")
        .and_then(Json::as_array)
        .into_iter()
        .flatten()
        .map(|s| (s.get("name").and_then(Json::as_str).unwrap_or("").to_string(), s.get("values").and_then(Json::as_array).into_iter().flatten().filter_map(Json::as_f64).collect()))
        .collect();
    let all: Vec<f64> = series.iter().flat_map(|(_, v)| v.iter().copied()).collect();
    if all.is_empty() {
        return 0;
    }
    let kind = s(w, "kind");
    let rows = ((n(w, "height", 96.0) / 16.0).round() as u16).clamp(3, 12);
    let labels: Vec<String> = w.get("labels").and_then(Json::as_array).into_iter().flatten().map(|l| l.as_str().unwrap_or("").to_string()).collect();
    let area = Rect::new(x, y, width, rows);
    let colors = [ctx.accent(), Color::Magenta, Color::Green, Color::Yellow];
    let min = all.iter().copied().fold(f64::INFINITY, f64::min).min(0.0);
    let max = all.iter().copied().fold(f64::NEG_INFINITY, f64::max).max(0.0);
    let span = if max > min { max - min } else { 1.0 };
    match kind.as_str() {
        "spark" => {
            let data: Vec<u64> = series[0].1.iter().map(|v| (((v - min) / span) * 100.0).round() as u64).collect();
            ctx.render(area, Sparkline::default().data(data).max(100).style(Style::new().fg(colors[0])));
            rows
        }
        "bar" => {
            let values = &series[0].1;
            let count = values.len().max(1) as u16;
            let bar_w = (width.saturating_sub(count - 1) / count).clamp(1, 9);
            let bars: Vec<Bar> = values
                .iter()
                .enumerate()
                .map(|(i, v)| {
                    let b = Bar::new(((v - min) / span * 100.0).round() as u64).text_value(number(*v));
                    match labels.get(i) {
                        Some(l) => b.label(l.clone()),
                        None => b,
                    }
                })
                .collect();
            ctx.render(area, BarChart::default().data(BarGroup::default().bars(&bars)).bar_width(bar_w).bar_gap(1).max(100).bar_style(Style::new().fg(colors[0])));
            rows
        }
        _ => {
            let points: Vec<Vec<(f64, f64)>> = series.iter().map(|(_, v)| v.iter().enumerate().map(|(i, y)| (i as f64, *y)).collect()).collect();
            let count = series.iter().map(|(_, v)| v.len()).max().unwrap_or(1).max(2);
            let datasets: Vec<Dataset> = points
                .iter()
                .enumerate()
                .map(|(k, p)| Dataset::default().name(series[k].0.clone()).data(p).marker(Marker::Braille).graph_type(GraphType::Line).style(Style::new().fg(colors[k % colors.len()])))
                .collect();
            let x_labels: Vec<String> = match (labels.first(), labels.last()) {
                (Some(a), Some(b)) if labels.len() > 1 => vec![a.clone(), b.clone()],
                _ => Vec::new(),
            };
            let chart = Chart::new(datasets)
                .x_axis(Axis::default().bounds([0.0, (count - 1) as f64]).labels(x_labels).style(ctx.muted()))
                .y_axis(Axis::default().bounds([min, max]).labels(vec![number(min), number(max)]).style(ctx.muted()))
                .legend_position(None);
            ctx.render(area, chart);
            rows
        }
    }
}

fn canvas(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let id = s(w, "id");
    let (cw, ch) = (n(w, "width", 100.0).max(1.0), n(w, "height", 100.0).max(1.0));
    // Terminal cells are about twice as tall as they are wide.
    let rows = (((width as f64) * ch / cw / 2.0).round() as u16).clamp(3, 24);
    let tappable = b(w, "tappable");
    let draggable = b(w, "draggable");
    let hold = b(w, "holdable").then(|| id.clone());
    let area = Rect::new(x, y, width, rows);
    let focused = (tappable || draggable || hold.is_some()) && ctx.target(area, Action::Canvas { id, w: cw, h: ch, tappable, draggable }, hold);
    let shapes: Vec<Json> = w.get("shapes").and_then(Json::as_array).cloned().unwrap_or_default();
    let dark = ctx.dark;
    let border = if focused { Style::new().fg(ctx.accent()).add_modifier(Modifier::BOLD) } else { ctx.muted() };
    let paint = move |p: &mut ratatui::widgets::canvas::Context| {
        for sh in &shapes {
            let color = role(&s(sh, "color"), dark).unwrap_or(Color::Reset);
            let fill = sh.get("fill").and_then(Json::as_bool).unwrap_or(true);
            // The plugin's y grows down; the canvas's grows up.
            let flip = |v: f64| ch - v;
            match s(sh, "shape").as_str() {
                "rect" => {
                    let (rx, ry, rw, rh) = (n(sh, "x", 0.0), n(sh, "y", 0.0), n(sh, "w", 0.0), n(sh, "h", 0.0));
                    if fill {
                        let steps = (rh / ch * rows as f64 * 4.0).ceil().max(1.0) as usize;
                        for i in 0..=steps {
                            let yy = flip(ry + rh * i as f64 / steps as f64);
                            p.draw(&CanvasLine { x1: rx, y1: yy, x2: rx + rw, y2: yy, color });
                        }
                    } else {
                        p.draw(&Rectangle { x: rx, y: flip(ry + rh), width: rw, height: rh, color });
                    }
                }
                "circle" => {
                    let (cx, cy, r) = (n(sh, "x", 0.0), flip(n(sh, "y", 0.0)), n(sh, "r", 0.0));
                    if fill {
                        let steps = (2.0 * r / ch * rows as f64 * 4.0).ceil().max(1.0) as usize;
                        for i in 0..=steps {
                            let dy = -r + 2.0 * r * i as f64 / steps as f64;
                            let dx = (r * r - dy * dy).max(0.0).sqrt();
                            p.draw(&CanvasLine { x1: cx - dx, y1: cy + dy, x2: cx + dx, y2: cy + dy, color });
                        }
                    } else {
                        p.draw(&Circle { x: cx, y: cy, radius: r, color });
                    }
                }
                "line" => p.draw(&CanvasLine { x1: n(sh, "x", 0.0), y1: flip(n(sh, "y", 0.0)), x2: n(sh, "x2", 0.0), y2: flip(n(sh, "y2", 0.0)), color }),
                "text" => p.print(n(sh, "x", 0.0), flip(n(sh, "y", 0.0)), ratatui::text::Line::styled(s(sh, "text"), Style::new().fg(color))),
                _ => {}
            }
        }
    };
    ctx.render(area, Canvas::default().block(Block::new().borders(Borders::LEFT | Borders::RIGHT).border_style(border)).marker(Marker::Braille).x_bounds([0.0, cw]).y_bounds([0.0, ch]).paint(paint));
    rows
}

fn table(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let header: Option<Vec<String>> = w.get("header").and_then(Json::as_array).map(|h| h.iter().map(|c| c.as_str().unwrap_or("").to_string()).collect());
    let rows: Vec<Vec<String>> = w.get("rows").and_then(Json::as_array).into_iter().flatten().map(|r| r.as_array().into_iter().flatten().map(|c| c.as_str().map(String::from).unwrap_or_else(|| c.to_string())).collect()).collect();
    let align: Vec<String> = w.get("align").and_then(Json::as_array).into_iter().flatten().map(|a| a.as_str().unwrap_or("start").to_string()).collect();
    let cols = header.as_ref().map_or(0, Vec::len).max(rows.iter().map(Vec::len).max().unwrap_or(0));
    if cols == 0 {
        return 0;
    }
    let mut widths = vec![1u16; cols];
    for r in header.iter().chain(rows.iter()) {
        for (i, c) in r.iter().enumerate() {
            widths[i] = widths[i].max((c.width() as u16).min(40));
        }
    }
    let gaps = (cols as u16 - 1) * 2;
    let total: u16 = widths.iter().sum::<u16>() + gaps;
    if total > width {
        let room = width.saturating_sub(gaps).max(cols as u16);
        let sum: u16 = widths.iter().sum();
        for wd in widths.iter_mut() {
            *wd = ((*wd as u32 * room as u32) / sum.max(1) as u32).max(1) as u16;
        }
    }
    let mut h = 0;
    if let Some(hd) = &header {
        table_line(ctx, hd, &widths, &align, x, y + h, Style::new().add_modifier(Modifier::BOLD | Modifier::UNDERLINED));
        h += 1;
    }
    for r in &rows {
        table_line(ctx, r, &widths, &align, x, y + h, Style::new());
        h += 1;
    }
    h
}

fn table_line(ctx: &mut Ctx, cells: &[String], widths: &[u16], align: &[String], x: u16, y: u16, style: Style) {
    let mut at = x;
    for (i, wd) in widths.iter().enumerate() {
        let cell = cells.get(i).cloned().unwrap_or_default();
        let cell = if cell.width() as u16 > *wd { format!("{}…", cell.chars().take(wd.saturating_sub(1) as usize).collect::<String>()) } else { cell };
        let pad = wd.saturating_sub(cell.width() as u16);
        let shown = match align.get(i).map(String::as_str) {
            Some("end") => format!("{}{cell}", " ".repeat(pad as usize)),
            Some("center") => format!("{}{cell}{}", " ".repeat((pad / 2) as usize), " ".repeat((pad - pad / 2) as usize)),
            _ => cell,
        };
        ctx.put(at, y, &shown, style, *wd);
        at += wd + 2;
    }
}

/// Seconds since 1970 as `Sat 28 Nov 2026` and `14:00`, in UTC.
pub fn civil(seconds: i64) -> (String, String) {
    let days = seconds.div_euclid(86400);
    let secs = seconds.rem_euclid(86400);
    // Howard Hinnant's days-to-civil.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    let weekday = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"][days.rem_euclid(7) as usize];
    let month = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][(m - 1) as usize];
    (format!("{weekday} {d} {month} {y}"), format!("{:02}:{:02}", secs / 3600, secs % 3600 / 60))
}

fn date(ctx: &mut Ctx, w: &Json, x: u16, y: u16, width: u16) -> u16 {
    let id = s(w, "id");
    let mut h = 0;
    if !s(w, "label").is_empty() {
        let label = s(w, "label");
        ctx.put(x, y, &label, ctx.muted(), width);
        h += 1;
    }
    let value = w.get("value").and_then(Json::as_f64).map(|v| v as i64);
    let step = (n(w, "step", 15.0) as i64).clamp(1, 60);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
    let Some(v) = value else {
        let at = x + ctx.put(x, y + h, "No date ", ctx.muted(), width);
        let time = b(w, "time");
        let initial = if time { (now / 3600 + 1) * 3600 } else { now - now.rem_euclid(86400) };
        button(ctx, at, y + h, "Set", false, true, None, Some(Action::Date { id, value: Some(initial) }), None);
        return h + 1;
    };
    let (day, clock) = civil(v);
    let mut at = x;
    at += button(ctx, at, y + h, "‹", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v - 86400) }), None) + 1;
    at += ctx.put(at, y + h, &day, Style::new().add_modifier(Modifier::BOLD), width) + 1;
    button(ctx, at, y + h, "›", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v + 86400) }), None);
    h += 1;
    if b(w, "time") {
        let mut at = x;
        at += button(ctx, at, y + h, "−1h", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v - 3600) }), None) + 1;
        at += button(ctx, at, y + h, "‹", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v - step * 60) }), None) + 1;
        at += ctx.put(at, y + h, &format!("{clock} UTC"), Style::new().add_modifier(Modifier::BOLD), width) + 1;
        at += button(ctx, at, y + h, "›", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v + step * 60) }), None) + 1;
        button(ctx, at, y + h, "+1h", false, true, None, Some(Action::Date { id: id.clone(), value: Some(v + 3600) }), None);
        h += 1;
    }
    let today = now - now.rem_euclid(86400) + v.rem_euclid(86400);
    let at = x + button(ctx, x, y + h, "Today", false, true, None, Some(Action::Date { id: id.clone(), value: Some(today) }), None) + 1;
    button(ctx, at, y + h, "Clear", false, true, None, Some(Action::Date { id, value: None }), None);
    h + 1
}

/// A whole tree drawn at [width]: the buffer, its targets and its height.
pub fn render(root: &[Json], width: u16, local: &Local, focus: Option<usize>, editing: bool, dark: bool, v5: bool) -> (Buffer, Vec<Target>, u16) {
    let mut measure = Ctx::new(None, local, focus, editing, dark, v5);
    let height = draw(&mut measure, root, 0, 0, width).max(1);
    // Very long trees are cut, as a phone panel would need scrolling anyway.
    let height = height.min(4000);
    let mut buf = Buffer::empty(Rect::new(0, 0, width, height));
    let mut ctx = Ctx::new(Some(&mut buf), local, focus, editing, dark, v5);
    draw(&mut ctx, root, 0, 0, width);
    let targets = ctx.targets;
    (buf, targets, height)
}
