//! `wmpy run`'s screen on ratatui's TestBackend: what each widget becomes,
//! and what keys do to a running plugin.

use std::time::{Duration, Instant};

use ratatui::backend::TestBackend;
use ratatui::buffer::Buffer;
use ratatui::crossterm::event::{Event, KeyCode, KeyEvent, KeyModifiers};
use ratatui::Terminal;
use serde_json::json;

use super::app::App;
use super::layout::{self, Action, Local};
use crate::devhost::{DevHost, Options};

fn text(buf: &Buffer) -> String {
    let mut out = String::new();
    for y in 0..buf.area.height {
        for x in 0..buf.area.width {
            out.push_str(buf[(x, y)].symbol());
        }
        out.push('\n');
    }
    out
}

fn tree(root: serde_json::Value, width: u16) -> (String, Vec<layout::Target>) {
    let local = Local::default();
    let (buf, targets, _) = layout::render(root.as_array().unwrap(), width, &local, None, false, false, true);
    (text(&buf), targets)
}

#[test]
fn widgets_become_text_boxes_and_targets() {
    let root = json!([
        {"type": "label", "text": "Fares", "style": "title"},
        {"type": "label", "text": "one two three four five six seven", "style": "body"},
        {"type": "row", "children": [
            {"type": "button", "id": "button:Add", "text": "Add", "primary": true, "enabled": true},
            {"type": "button", "id": "button:Off", "text": "Off", "primary": false, "enabled": false},
        ]},
        {"type": "toggle", "id": "toggle:Dark", "label": "Dark", "checked": true},
        {"type": "slider", "id": "slider:Size", "label": "Size", "value": 5, "min": 0, "max": 10, "step": 1},
        {"type": "card", "id": "card:1", "title": "Card", "clickable": true, "holdable": false, "children": [{"type": "label", "text": "inside", "style": "body"}]},
        {"type": "chips", "id": "chips:c", "options": [{"value": "a", "label": "A"}, {"value": "b", "label": "B"}], "value": ["b"], "multi": false},
        {"type": "output", "id": "out", "text": "result", "mono": false, "insertable": true, "copyable": true},
    ]);
    let (screen, targets) = tree(root, 20);
    assert!(screen.contains("Fares"), "{screen}");
    // Body text wraps at the panel's width.
    assert!(screen.contains("one two three four\n") || screen.contains("one two three four "), "{screen}");
    assert!(screen.contains("[ Add ] [ Off ]"), "{screen}");
    assert!(screen.contains("[ON ] Dark"), "{screen}");
    assert!(screen.contains("Size: 5"), "{screen}");
    assert!(screen.contains("╭") && screen.contains("inside"), "{screen}");
    assert!(screen.contains("( ✓ B )"), "{screen}");
    // Two buttons that do not fit side by side wrap.
    assert!(screen.contains("[ Insert ]") && screen.contains("[ Copy ]"), "{screen}");
    let actions: Vec<&Action> = targets.iter().map(|t| &t.action).collect();
    // The disabled button is no target.
    assert_eq!(actions[0], &Action::Click("button:Add".into()));
    assert!(matches!(actions[1], Action::Toggle { checked: true, .. }));
    assert!(matches!(actions[2], Action::Slider { value, .. } if *value == 5.0));
    assert_eq!(actions[3], &Action::Click("card:1".into()));
    assert!(matches!(actions[4], Action::Set { value, .. } if value == &json!("a")));
    assert_eq!(actions[6], &Action::Insert("result".into()));
    assert_eq!(targets.len(), 8);
}

#[test]
fn rows_too_wide_stack_and_weights_split_the_width() {
    let root = json!([
        {"type": "row", "weights": [1, 3], "children": [
            {"type": "label", "text": "L", "style": "body"},
            {"type": "label", "text": "R", "style": "body"},
        ]},
        {"type": "row", "children": [
            {"type": "input", "id": "a", "label": "", "placeholder": "first"},
            {"type": "input", "id": "b", "label": "", "placeholder": "second"},
        ]},
    ]);
    let (screen, _) = tree(root, 21);
    let first = screen.lines().next().unwrap();
    // 1:3 of the 20 columns after the gap: R starts at column 6.
    assert_eq!(first.find('R'), Some(6), "{screen}");
    // Two text boxes do not fit side by side in 21 columns: one under the other.
    let lines: Vec<&str> = screen.lines().collect();
    assert!(lines[1].contains("first") && lines[2].contains("second"), "{screen}");
}

#[test]
fn charts_and_canvases_draw_with_ratatui() {
    let root = json!([
        {"type": "chart", "id": "c", "kind": "spark", "series": [{"name": "a", "values": [1, 5, 2, 8, 3]}], "labels": [], "height": 48},
        {"type": "chart", "id": "b", "kind": "bar", "series": [{"name": "a", "values": [3, 6]}], "labels": ["x", "y"], "height": 64},
        {"type": "canvas", "id": "cv", "width": 100, "height": 50, "tappable": true, "draggable": false, "holdable": false, "dragAfterHold": false,
         "shapes": [{"shape": "line", "x": 0, "y": 0, "x2": 100, "y2": 50, "color": "primary"}, {"shape": "circle", "x": 50, "y": 25, "r": 10, "fill": false, "color": "danger"}]},
        {"type": "image", "id": "i", "src": "https://x.example.com/a.png", "alt": "A cat", "height": 32, "insertable": false, "clickable": false},
        {"type": "table", "id": "t", "header": ["Name", "Qty"], "rows": [["rice", "2"], ["dal", "10"]], "align": ["start", "end"]},
    ]);
    let (screen, targets) = tree(root, 30);
    assert!(screen.chars().any(|c| ('▁'..='█').contains(&c)), "no sparkline or bars:\n{screen}");
    assert!(screen.chars().any(|c| ('\u{2801}'..='\u{28ff}').contains(&c)), "no braille canvas:\n{screen}");
    assert!(screen.contains("▣ A cat"), "{screen}");
    assert!(screen.contains("Name  Qty") && screen.contains("dal    10"), "{screen}");
    assert!(targets.iter().any(|t| matches!(&t.action, Action::Canvas { id, tappable: true, .. } if id == "cv")));
}

const MANIFEST: &str = r#"{"id": "com.example.tui", "name": "Counter", "version": "1.0.0", "apiVersion": 5, "permissions": ["storage", "field.write"]}"#;
const MAIN: &str = r#"from wm import plugin, ui, field
state = plugin.state(count=0, said="", persist=True)

@plugin.command("Reset")
def reset():
    state.count = 0

def add():
    state.count += 1

def said(text):
    state.said = text

def render():
    ui.label("count " + str(state.count))
    ui.button("Add", on_click=add)
    ui.input("Name", on_submit=said, id="name")
    ui.label("said " + state.said)
    ui.output("hello", id="out")
    ui.button("Ask", on_click=lambda: ui.dialog("Sure?", buttons=["Yes", "No"], on_result=said))
"#;

fn app(name: &str) -> App {
    let dir = crate::devhost::tests::plugin(name, &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    App::new(DevHost::open(Options { dir, ..Options::default() }).unwrap(), 40)
}

fn screen(app: &mut App) -> String {
    let mut term = Terminal::new(TestBackend::new(100, 30)).unwrap();
    term.draw(|f| app.draw(f)).unwrap();
    text(term.backend().buffer())
}

fn key(app: &mut App, code: KeyCode) {
    app.handle(Event::Key(KeyEvent::new(code, KeyModifiers::NONE)));
}

#[test]
fn keys_press_buttons_type_into_boxes_and_answer_dialogs() {
    let mut app = app("keys");
    let s = screen(&mut app);
    assert!(s.contains("count 0") && s.contains("[ Reset ]") && s.contains("Console"), "{s}");
    // Header buttons come first: Tab reaches Reset, then Add.
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Enter);
    assert!(screen(&mut app).contains("count 1"));
    key(&mut app, KeyCode::BackTab);
    key(&mut app, KeyCode::Enter);
    assert!(screen(&mut app).contains("count 0"));
    // Down to the text box, type, Enter submits.
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Tab);
    assert!(app.editing);
    for c in "Ada q".chars() {
        key(&mut app, KeyCode::Char(c));
    }
    key(&mut app, KeyCode::Enter);
    let s = screen(&mut app);
    assert!(s.contains("said Ada q"), "{s}");
    assert!(!app.quit, "q typed into the box, not a quit");
    key(&mut app, KeyCode::Esc);
    // Insert puts the output's text into the pretend field.
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Enter);
    assert_eq!(app.host.world.borrow().field.string(), "hello");
    // A dialog takes the keys until it is answered.
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Enter);
    let s = screen(&mut app);
    assert!(s.contains("Sure?") && s.contains("[ Yes ]") && s.contains("[ No ]"), "{s}");
    key(&mut app, KeyCode::Right);
    key(&mut app, KeyCode::Enter);
    let until = Instant::now() + Duration::from_secs(2);
    while !screen(&mut app).contains("said No") && Instant::now() < until {
        app.host.tick();
    }
    assert!(screen(&mut app).contains("said No"));
    key(&mut app, KeyCode::Char('q'));
    assert!(app.quit);
}

#[test]
fn the_panes_show_the_console_storage_field_and_calls() {
    let mut app = app("panes");
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Tab);
    key(&mut app, KeyCode::Enter);
    key(&mut app, KeyCode::Char('4'));
    app.host.insert("typed");
    let s = screen(&mut app);
    assert!(s.contains("typed▏"), "{s}");
    key(&mut app, KeyCode::Char('3'));
    let s = screen(&mut app);
    assert!(s.contains("__state__:state"), "{s}");
    // An event typed as JSON in the Events pane.
    key(&mut app, KeyCode::Char('2'));
    app.handle(Event::Key(KeyEvent::new(KeyCode::Char('o'), KeyModifiers::CONTROL)));
    for c in r#"{"type": "click", "id": "button:Add"}"#.chars() {
        key(&mut app, KeyCode::Char(c));
    }
    key(&mut app, KeyCode::Enter);
    let s = screen(&mut app);
    assert!(s.contains("count 2"), "{s}");
    app.handle(Event::Key(KeyEvent::new(KeyCode::Char('o'), KeyModifiers::CONTROL)));
    key(&mut app, KeyCode::Char('1'));
    assert!(screen(&mut app).contains("sent {"));
}
