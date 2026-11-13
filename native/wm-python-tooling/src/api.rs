//! Every name a plugin can reach, with what it is and one paragraph about it:
//! the `wm` and `ui` host API of plugin API 4, the six PyMini modules, the
//! builtins, and the methods of str, list, dict, bytes, set, tuple and match
//! objects. Completion, the API doc strip, hover and diagnostics all read this.
//!
//! The `wm` and `ui` part mirrors §1 of the plugin plan exactly; a test pins it.

use std::collections::HashMap;
use std::sync::OnceLock;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ApiKind {
    /// A namespace: `wm`, `wm.storage`, `math`.
    Module,
    Function,
    /// A method of a value type: `str.upper`.
    Method,
    Constant,
    /// A builtin function or type: `len`, `str`.
    Builtin,
    Exception,
    /// A value type with methods but no name of its own: `Match`.
    Type,
}

impl ApiKind {
    pub fn name(self) -> &'static str {
        match self {
            ApiKind::Module => "module",
            ApiKind::Function => "function",
            ApiKind::Method => "method",
            ApiKind::Constant => "constant",
            ApiKind::Builtin => "builtin",
            ApiKind::Exception => "exception",
            ApiKind::Type => "type",
        }
    }
}

#[derive(Clone, Debug)]
pub struct ApiEntry {
    pub path: &'static str,
    pub kind: ApiKind,
    /// How it is called, `get(key, default=None)`, or None for a namespace or constant.
    pub signature: Option<&'static str>,
    /// The manifest permission it needs: `storage` or `reminders`.
    pub permission: Option<&'static str>,
    pub doc: &'static str,
}

impl ApiEntry {
    pub fn name(&self) -> &'static str {
        self.path.rsplit('.').next().unwrap_or(self.path)
    }

    pub fn parent(&self) -> &'static str {
        match self.path.rfind('.') {
            Some(at) => &self.path[..at],
            None => "",
        }
    }
}

/// One parameter of a `ui` builder.
#[derive(Clone, Debug)]
pub struct UiParam {
    pub name: &'static str,
    pub required: bool,
    /// The literal values it takes, when it takes only a few.
    pub values: &'static [&'static str],
}

#[derive(Clone, Debug)]
pub struct UiShape {
    pub name: &'static str,
    pub params: Vec<UiParam>,
    /// `*children`: widgets for a container, pages for `tabs`.
    pub children: bool,
    /// Whether its first parameter is the id `on_event` hears.
    pub has_id: bool,
    /// The `type` of the dict it returns, or None for `page`.
    pub widget_type: Option<&'static str>,
}

impl UiShape {
    pub fn param(&self, name: &str) -> Option<&UiParam> {
        self.params.iter().find(|p| p.name == name)
    }

    pub fn required(&self) -> usize {
        self.params.iter().filter(|p| p.required).count()
    }
}

pub const API_VERSION: i64 = 4;
pub const EVENT_TYPES: &[&str] = &["click", "toggle", "input_changed", "tab_selected", "date_changed"];
pub const EVENT_FIELDS: &[(&str, &str)] =
    &[("type", "str"), ("id", "str"), ("value", "str, bool, int or None"), ("index", "int")];
pub const LABEL_STYLES: &[&str] = &["title", "body", "caption"];
pub const BUTTON_STYLES: &[&str] = &["primary"];
pub const MAX_TABS: usize = 8;
pub const API_GLOBALS: &[&str] = &["wm", "ui"];
pub const MODULES: &[&str] = &["math", "random", "time", "json", "re", "sys"];

/// Standard library modules written in Python that API 5 plugins can import.
/// Their members are not modelled, so code using them is not checked name by name.
pub const LIBRARY: &[&str] = &[
    "abc", "base64", "collections", "copy", "dataclasses", "datetime", "enum", "functools", "hashlib", "hmac",
    "html", "itertools", "statistics", "string", "textwrap", "typing", "urllib", "urllib.parse", "uuid",
];
pub const CONTRACT: &[&str] = &["render", "on_event", "transform"];

/// The value types whose methods are listed, in the order a guess prefers them.
pub const VALUE_TYPES: &[&str] = &["str", "list", "dict", "bytes", "set", "tuple", "Match"];

/// Exception names PyMini raises or matches, usable in `except` and `raise`.
pub const EXCEPTIONS: &[&str] = &[
    "Exception", "ValueError", "TypeError", "KeyError", "IndexError", "NameError", "ZeroDivisionError",
    "AttributeError", "RuntimeError", "StopIteration", "LookupError", "ArithmeticError", "OverflowError",
    "ImportError", "AssertionError", "NotImplementedError", "UnicodeError", "UnicodeDecodeError", "OSError",
    "PermissionError", "TimeoutError", "ConnectionError", "FileNotFoundError",
];

/// Builtins taken out because they reach outside the script.
pub const REMOVED_BUILTINS: &[&str] = &[
    "open", "input", "eval", "exec", "compile", "__import__", "globals", "locals", "breakpoint", "exit", "quit",
    "help",
];

/// Builtins Python has and PyMini does not.
pub const MISSING_BUILTINS: &[&str] = &[
    "frozenset", "bytearray", "memoryview", "complex", "slice", "ascii", "dir", "aiter", "anext",
];

/// `wm` members that do not exist and never will: plugins have no way to read
/// typed text or reach the file system. (The network, the clipboard and the
/// field are API 5 modules: see [WM_PACKAGE].)
pub const NEVER: &[&str] = &[
    "text", "net", "network", "insert", "type", "input", "contacts", "fs", "exec", "keys", "send", "fetch",
    "request",
];

/// The modules of the API 5 `wm` package (`from wm import plugin, ui, http`).
/// Their members are not modelled here, so code using them is not checked
/// name by name; the interpreter and the host check them when they run.
pub const WM_PACKAGE: &[&str] = &[
    "plugin", "ui", "http", "ai", "field", "clipboard", "location", "files", "sensors", "scan", "device", "share",
    "notify", "secrets", "reminders", "storage", "log", "timestamp", "i18n", "_", "gettext", "ngettext",
];

/// Names that do not exist here but have a counterpart that does.
pub const USE_INSTEAD: &[(&str, &str)] = &[
    ("wm.json", "json"),
    ("wm.json.decode", "json.loads"),
    ("wm.json.encode", "json.dumps"),
    ("json.decode", "json.loads"),
    ("json.encode", "json.dumps"),
    ("json.parse", "json.loads"),
    ("json.stringify", "json.dumps"),
    ("json.load", "json.loads"),
    ("json.dump", "json.dumps"),
    ("time.strftime", "time.format"),
    ("time.localtime", "time.parts"),
    ("time.mktime", "wm.timestamp"),
    ("wm.print", "print"),
    ("wm.time", "time.time"),
];

pub struct Model {
    pub entries: Vec<ApiEntry>,
    by_path: HashMap<&'static str, usize>,
    by_parent: HashMap<&'static str, Vec<usize>>,
    pub ui: Vec<UiShape>,
}

impl Model {
    pub fn find(&self, path: &str) -> Option<&ApiEntry> {
        self.by_path.get(path).map(|i| &self.entries[*i])
    }

    /// The names directly inside [parent]; the global names for an empty one.
    pub fn children(&self, parent: &str) -> Vec<&ApiEntry> {
        self.by_parent.get(parent).map(|v| v.iter().map(|i| &self.entries[*i]).collect()).unwrap_or_default()
    }

    pub fn ui_shape(&self, name: &str) -> Option<&UiShape> {
        self.ui.iter().find(|s| s.name == name)
    }
}

pub fn model() -> &'static Model {
    static MODEL: OnceLock<Model> = OnceLock::new();
    MODEL.get_or_init(build)
}

pub fn is_builtin(name: &str) -> bool {
    model().find(name).is_some_and(|e| matches!(e.kind, ApiKind::Builtin | ApiKind::Exception))
}

pub fn use_instead(path: &str) -> Option<&'static str> {
    USE_INSTEAD.iter().find(|(from, _)| *from == path).map(|(_, to)| *to)
}

fn ui_shapes() -> Vec<UiShape> {
    let p = |name: &'static str, required: bool, values: &'static [&'static str]| UiParam { name, required, values };
    vec![
        UiShape { name: "column", params: vec![], children: true, has_id: false, widget_type: Some("column") },
        UiShape { name: "row", params: vec![], children: true, has_id: false, widget_type: Some("row") },
        UiShape {
            name: "label",
            params: vec![p("text", true, &[]), p("style", false, LABEL_STYLES)],
            children: false,
            has_id: false,
            widget_type: Some("label"),
        },
        UiShape {
            name: "output",
            params: vec![
                p("text", true, &[]),
                p("id", false, &[]),
                p("mono", false, &[]),
                p("insertable", false, &[]),
                p("copyable", false, &[]),
            ],
            children: false,
            has_id: false,
            widget_type: Some("output"),
        },
        UiShape {
            name: "button",
            params: vec![p("id", true, &[]), p("text", true, &[]), p("style", false, BUTTON_STYLES), p("enabled", false, &[])],
            children: false,
            has_id: true,
            widget_type: Some("button"),
        },
        UiShape {
            name: "toggle",
            params: vec![p("id", true, &[]), p("label", true, &[]), p("checked", false, &[])],
            children: false,
            has_id: true,
            widget_type: Some("toggle"),
        },
        UiShape {
            name: "input",
            params: vec![p("id", true, &[]), p("label", false, &[]), p("placeholder", false, &[])],
            children: false,
            has_id: true,
            widget_type: Some("input"),
        },
        UiShape {
            name: "date",
            params: vec![
                p("id", true, &[]),
                p("label", false, &[]),
                p("value", false, &[]),
                p("time", false, &[]),
                p("step", false, &[]),
            ],
            children: false,
            has_id: true,
            widget_type: Some("date"),
        },
        UiShape { name: "spacer", params: vec![p("height", false, &[])], children: false, has_id: false, widget_type: Some("spacer") },
        UiShape { name: "divider", params: vec![], children: false, has_id: false, widget_type: Some("divider") },
        UiShape { name: "progress", params: vec![], children: false, has_id: false, widget_type: Some("progress") },
        UiShape { name: "tabs", params: vec![p("id", true, &[])], children: true, has_id: true, widget_type: Some("tabs") },
        UiShape { name: "page", params: vec![p("title", true, &[])], children: true, has_id: false, widget_type: None },
    ]
}

fn build() -> Model {
    let mut entries: Vec<ApiEntry> = Vec::new();
    let mut add = |path: &'static str, kind: ApiKind, signature: Option<&'static str>, permission: Option<&'static str>, doc: &'static str| {
        entries.push(ApiEntry { path, kind, signature, permission, doc });
    };
    use ApiKind::*;

    // ---- wm ---------------------------------------------------------------
    add("wm", Module, None, None, "The host API, there in every plugin without an import: log, plugin_id, ui.set_input, timestamp and, with their permissions, storage and reminders. API 5 plugins import from it instead: from wm import plugin, ui, http.");
    add("wm.api_version", Constant, None, None, "The plugin API level this keyboard runs: 4.");
    add("wm.plugin_id", Constant, None, None, "The id from your plugin.json.");
    add("wm.plugin_version", Constant, None, None, "The pluginVersion from your plugin.json.");
    add("wm.log", Function, Some("log(*values)"), None, "Writes the values, separated by spaces, as one line of your plugin's log, readable in Settings and in the editor's console. print() writes to the same log. Lines are cut at 512 characters.");
    add("wm.timestamp", Function, Some("timestamp(year, month, day, hour=12, minute=0, second=0)"), None, "Seconds since the epoch for a moment in the phone's time zone. A field past its range rolls over, so day=32 is in the next month. Use it for wm.reminders.set and ui.date values.");
    add("wm.ui", Module, None, None, "Writes to your own widgets while the panel is open.");
    add("wm.ui.set_input", Function, Some("set_input(id, text)"), None, "Puts text in your ui.input box id, after your handler returns. Panel only; up to 8 KB.");
    add("wm.storage", Module, None, Some("storage"), "Strings saved on this device for your plugin, kept across sessions and deleted on uninstall. Only there when plugin.json declares the storage permission.");
    add("wm.storage.get", Function, Some("get(key, default=None)"), Some("storage"), "The string saved under key, or default when there is none.");
    add("wm.storage.set", Function, Some("set(key, value)"), Some("storage"), "Saves the string value under key. Raises ValueError when over the quota: 128 keys, 64 characters a key, 8 KB a value, 64 KB in all.");
    add("wm.storage.remove", Function, Some("remove(key)"), Some("storage"), "Deletes key, if it is there.");
    add("wm.storage.keys", Function, Some("keys()"), Some("storage"), "A list of every saved key.");
    add("wm.reminders", Module, None, Some("reminders"), "Notifications the app shows at a time you set, titled with your plugin's name. Nothing of yours runs then. Only there when plugin.json declares the reminders permission.");
    add("wm.reminders.set", Function, Some("set(id, seconds, text)"), Some("reminders"), "Shows text at seconds (as time.time() or wm.timestamp() give), setting or replacing the reminder id. Raises ValueError for a time already past, empty text, or more than 64 reminders. Text up to 200 characters.");
    add("wm.reminders.cancel", Function, Some("cancel(id)"), Some("reminders"), "Cancels the reminder id. Returns True when there was one.");
    add("wm.reminders.list", Function, Some("list()"), Some("reminders"), "Every reminder not shown yet, soonest first, as dicts with id, time and text.");

    // ---- ui ---------------------------------------------------------------
    add("ui", Module, None, None, "Builds the widgets render() returns. Each builder returns a plain dict, so a dict you write yourself works too.");
    add("ui.column", Function, Some("column(*children)"), None, "Widgets stacked top to bottom. Takes the widgets as arguments, or one list of them.");
    add("ui.row", Function, Some("row(*children)"), None, "Widgets side by side, sharing the width equally. Takes the widgets as arguments, or one list of them.");
    add("ui.label", Function, Some("label(text, style=None)"), None, "Text. style is \"title\", \"body\" or \"caption\"; body when not given.");
    add("ui.output", Function, Some("output(text, id=None, mono=False, insertable=True, copyable=True)"), None, "A result, with the keyboard's own Insert and Copy buttons under it. Insert is the only way your result reaches the user's text. mono draws it monospaced.");
    add("ui.button", Function, Some("button(id, text, style=None, enabled=True)"), None, "A button. on_event hears {\"type\": \"click\", \"id\": id}. style=\"primary\" highlights it; enabled=False greys it out.");
    add("ui.toggle", Function, Some("toggle(id, label, checked=False)"), None, "A switch. on_event hears {\"type\": \"toggle\", \"id\": id, \"value\": True or False}.");
    add("ui.input", Function, Some("input(id, label=None, placeholder=None)"), None, "A text box the keyboard owns. on_event hears {\"type\": \"input_changed\", \"id\": id, \"value\": text} after each change. Write to it with wm.ui.set_input.");
    add("ui.date", Function, Some("date(id, label=None, value=None, time=False, step=15)"), None, "A date picked with the keyboard's own buttons. value is seconds, or None for none yet. time=True picks a time of day too, step minutes at a time. on_event hears {\"type\": \"date_changed\", \"id\": id, \"value\": seconds or None}.");
    add("ui.spacer", Function, Some("spacer(height=8)"), None, "Empty space, height from 0 to 64.");
    add("ui.divider", Function, Some("divider()"), None, "A line across the panel.");
    add("ui.progress", Function, Some("progress()"), None, "An indeterminate bar that shows something is happening.");
    add("ui.tabs", Function, Some("tabs(id, *pages)"), None, "A tab strip of ui.page pages, top level only, up to 8. on_event hears {\"type\": \"tab_selected\", \"id\": id, \"index\": n}, counting from 0.");
    add("ui.page", Function, Some("page(title, *children)"), None, "One page of ui.tabs: its title, then its widgets.");

    // ---- builtins ---------------------------------------------------------
    add("print", Builtin, Some("print(*values)"), None, "Writes the values, separated by spaces, as one line of the plugin log, the same log wm.log writes.");
    add("len", Builtin, Some("len(value)"), None, "How many items a list, tuple, dict or set holds, or how many characters a string has. Characters are what a person sees, so an emoji or a conjunct counts once.");
    add("str", Builtin, Some("str(value=\"\")"), None, "value as text.");
    add("repr", Builtin, Some("repr(value)"), None, "value as Python would write it: strings in quotes.");
    add("int", Builtin, Some("int(value=0, base=10)"), None, "value as a whole number. Text like \"3.5\" reads as 3; a base reads text in base 2 to 36.");
    add("float", Builtin, Some("float(value=0.0)"), None, "value as a floating-point number.");
    add("bool", Builtin, Some("bool(value=False)"), None, "Whether value is true: not zero, not empty, not None.");
    add("bytes", Builtin, Some("bytes(values)"), None, "Bytes from a list of numbers from 0 to 255. str.encode() gives the UTF-8 bytes of text, and bytes.fromhex() reads hex.");
    add("abs", Builtin, Some("abs(x)"), None, "The absolute value of x.");
    add("round", Builtin, Some("round(x, ndigits=None)"), None, "x rounded to the nearest whole number, an int; with ndigits, to that many decimals, a float. Halves round to even.");
    add("min", Builtin, Some("min(*values, key=None, default=None)"), None, "The smallest of the values, or of one list of them. key picks what to compare; default is returned for an empty list.");
    add("max", Builtin, Some("max(*values, key=None, default=None)"), None, "The largest of the values, or of one list of them. key picks what to compare; default is returned for an empty list.");
    add("sum", Builtin, Some("sum(values, start=0)"), None, "start plus every item of values.");
    add("range", Builtin, Some("range(start, stop, step=1)"), None, "A list of whole numbers from start up to but not including stop. range(n) counts from 0. At most 10,000 items.");
    add("sorted", Builtin, Some("sorted(values, key=None, reverse=False)"), None, "A new sorted list of the items. key picks what to sort by.");
    add("reversed", Builtin, Some("reversed(values)"), None, "A new list of the items in reverse order.");
    add("list", Builtin, Some("list(values=())"), None, "A new list of the items of values, or an empty list.");
    add("dict", Builtin, Some("dict(pairs=())"), None, "A new dict, empty, copied from another dict, or built from (key, value) pairs.");
    add("set", Builtin, Some("set(values=())"), None, "A new set of the items of values, each once.");
    add("tuple", Builtin, Some("tuple(values=())"), None, "A new tuple of the items of values.");
    add("enumerate", Builtin, Some("enumerate(values, start=0)"), None, "(index, item) pairs: for i, item in enumerate(items).");
    add("zip", Builtin, Some("zip(*lists)"), None, "Tuples of the items at the same position in each list, as long as the shortest.");
    add("ord", Builtin, Some("ord(character)"), None, "The code point of a one-code-point character.");
    add("chr", Builtin, Some("chr(code)"), None, "The character with that code point.");
    add("any", Builtin, Some("any(values)"), None, "Whether any item is true.");
    add("all", Builtin, Some("all(values)"), None, "Whether every item is true.");
    add("type", Builtin, Some("type(value)"), None, "The type of value as text: \"int\", \"str\", \"list\" and so on.");
    add("map", Builtin, Some("map(function, *lists)"), None, "A list of function applied to each item.");
    add("filter", Builtin, Some("filter(function, values)"), None, "A list of the items function returns true for. With None, the true items.");
    add("divmod", Builtin, Some("divmod(a, b)"), None, "(a // b, a % b).");
    add("pow", Builtin, Some("pow(base, exp, mod=None)"), None, "base to the power exp, and modulo mod when given.");
    add("hex", Builtin, Some("hex(x)"), None, "A whole number as hexadecimal text: \"0xff\".");
    add("bin", Builtin, Some("bin(x)"), None, "A whole number as binary text: \"0b101\".");
    add("oct", Builtin, Some("oct(x)"), None, "A whole number as octal text: \"0o17\".");
    add("format", Builtin, Some("format(value, spec=\"\")"), None, "value formatted with a format spec, as in f\"{value:spec}\".");
    add("isinstance", Builtin, Some("isinstance(value, types)"), None, "Whether value is of a type: str, int, float, bool, list, tuple, dict or set, or one of a tuple of them.");
    add("callable", Builtin, Some("callable(value)"), None, "Whether value can be called.");
    add("getattr", Builtin, Some("getattr(obj, name, default)"), None, "The attribute called name, or default when there is none.");
    add("setattr", Builtin, Some("setattr(obj, name, value)"), None, "Sets the attribute called name, as obj.name = value does.");
    add("hasattr", Builtin, Some("hasattr(obj, name)"), None, "Whether obj has an attribute called name.");
    add("delattr", Builtin, Some("delattr(obj, name)"), None, "Removes the attribute called name.");
    add("vars", Builtin, Some("vars(obj)"), None, "An object's attributes as a dict.");
    add("iter", Builtin, Some("iter(values)"), None, "An iterator over values, which next() takes items from.");
    add("next", Builtin, Some("next(iterator, default)"), None, "The next item, or default when there is none left; without a default, StopIteration.");
    add("property", Builtin, Some("property(fget, fset=None)"), None, "A computed attribute. Usually written as @property above a method, and @name.setter for the setter.");
    add("staticmethod", Builtin, Some("staticmethod(function)"), None, "A method that takes neither self nor the class. Usually written as @staticmethod.");
    add("classmethod", Builtin, Some("classmethod(function)"), None, "A method that takes the class as its first argument. Usually written as @classmethod.");
    add("super", Builtin, Some("super()"), None, "Inside a method, the class above this one: super().__init__(...).");
    add("object", Builtin, Some("object()"), None, "The plainest object, often a unique sentinel value.");
    add("issubclass", Builtin, Some("issubclass(cls, classinfo)"), None, "Whether a class is classinfo or descends from it.");
    add("id", Builtin, Some("id(value)"), None, "A number that is the same for the same object and different for another one alive at the same time.");
    add("hash", Builtin, Some("hash(value)"), None, "The hash of a value that can be a dict key.");
    add("NotImplemented", Constant, None, None, "Returned by an operator method such as __eq__ to say it does not handle the other operand.");
    add("Exception", Exception, Some("Exception(message)"), None, "The base of every error. except Exception catches them all.");
    add("ValueError", Exception, Some("ValueError(message)"), None, "A value of the right type but the wrong content. wm.storage.set and wm.reminders.set raise it.");
    add("TypeError", Exception, Some("TypeError(message)"), None, "A value of the wrong type.");
    add("KeyError", Exception, Some("KeyError(message)"), None, "A dict has no such key.");
    add("IndexError", Exception, Some("IndexError(message)"), None, "A position past the end of a list or string.");
    add("NameError", Exception, Some("NameError(message)"), None, "A name nothing defines.");
    add("ZeroDivisionError", Exception, Some("ZeroDivisionError(message)"), None, "Division or modulo by zero.");
    add("AttributeError", Exception, Some("AttributeError(message)"), None, "A value has no such attribute or method.");
    add("RuntimeError", Exception, Some("RuntimeError(message)"), None, "Any other error.");
    add("StopIteration", Exception, Some("StopIteration(message)"), None, "Raised to stop an iteration.");
    add("LookupError", Exception, None, None, "Catches KeyError and IndexError in an except clause.");
    add("ArithmeticError", Exception, None, None, "Catches ZeroDivisionError in an except clause.");
    add("OverflowError", Exception, None, None, "A number too large to convert. Catch it in an except clause.");
    add("ImportError", Exception, None, None, "A module that is not there. Catch it in an except clause.");
    add("AssertionError", Exception, Some("AssertionError(message)"), None, "An assert statement whose test was false.");
    add("NotImplementedError", Exception, Some("NotImplementedError(message)"), None, "A method a subclass should have written.");
    add("UnicodeError", Exception, Some("UnicodeError(message)"), None, "Text that could not be encoded or decoded.");
    add("UnicodeDecodeError", Exception, Some("UnicodeDecodeError(message)"), None, "Bytes that are not valid in the encoding asked for.");
    add("OSError", Exception, Some("OSError(message)"), None, "Catches PermissionError, TimeoutError and ConnectionError.");
    add("PermissionError", Exception, Some("PermissionError(message)"), None, "Something the plugin has not been allowed to do, or not here: a field the user is typing a password in.");
    add("TimeoutError", Exception, Some("TimeoutError(message)"), None, "A request that took too long.");
    add("ConnectionError", Exception, Some("ConnectionError(message)"), None, "A request that could not reach its server.");
    add("FileNotFoundError", Exception, Some("FileNotFoundError(message)"), None, "A file that is not there.");

    // ---- modules ----------------------------------------------------------
    add("math", Module, None, None, "Mathematical functions and constants. import math.");
    for (name, sig, doc) in [
        ("sqrt", "sqrt(x)", "The square root of x."),
        ("floor", "floor(x)", "The largest whole number not greater than x, an int."),
        ("ceil", "ceil(x)", "The smallest whole number not less than x, an int."),
        ("trunc", "trunc(x)", "x with its fraction dropped, an int."),
        ("fabs", "fabs(x)", "The absolute value of x, a float."),
        ("pow", "pow(x, y)", "x to the power y, a float."),
        ("exp", "exp(x)", "e to the power x."),
        ("log", "log(x, base=e)", "The logarithm of x, natural or in base."),
        ("log2", "log2(x)", "The base-2 logarithm of x."),
        ("log10", "log10(x)", "The base-10 logarithm of x."),
        ("sin", "sin(x)", "The sine of x radians."),
        ("cos", "cos(x)", "The cosine of x radians."),
        ("tan", "tan(x)", "The tangent of x radians."),
        ("asin", "asin(x)", "The arc sine of x, in radians."),
        ("acos", "acos(x)", "The arc cosine of x, in radians."),
        ("atan", "atan(x)", "The arc tangent of x, in radians."),
        ("atan2", "atan2(y, x)", "The arc tangent of y / x, in radians, in the right quadrant."),
        ("degrees", "degrees(x)", "x radians in degrees."),
        ("radians", "radians(x)", "x degrees in radians."),
        ("hypot", "hypot(*xs)", "The length of a vector: the square root of the sum of squares."),
        ("gcd", "gcd(*xs)", "The greatest common divisor."),
        ("lcm", "lcm(*xs)", "The least common multiple."),
        ("factorial", "factorial(n)", "n!, for n from 0 to 20."),
        ("isnan", "isnan(x)", "Whether x is not a number."),
        ("isinf", "isinf(x)", "Whether x is infinite."),
        ("isfinite", "isfinite(x)", "Whether x is neither infinite nor not a number."),
        ("copysign", "copysign(x, y)", "x with the sign of y."),
        ("fmod", "fmod(x, y)", "The remainder of x / y, with the sign of x."),
        ("comb", "comb(n, k)", "How many ways to choose k of n."),
        ("prod", "prod(values)", "The product of the items."),
    ] {
        add(leak(format!("math.{name}")), Function, Some(leak(sig.to_string())), None, doc);
    }
    add("math.pi", Constant, None, None, "The number pi.");
    add("math.e", Constant, None, None, "The number e.");
    add("math.tau", Constant, None, None, "Two pi: a full turn, in radians.");
    add("math.inf", Constant, None, None, "Positive infinity, larger than any number.");
    add("math.nan", Constant, None, None, "Not a number.");

    add("random", Module, None, None, "Random numbers from a seedable generator. import random.");
    for (name, sig, doc) in [
        ("random", "random()", "A float from 0 up to but not including 1."),
        ("randint", "randint(a, b)", "A whole number from a to b, both included."),
        ("randrange", "randrange(start, stop, step=1)", "A whole number from range(start, stop, step)."),
        ("choice", "choice(values)", "One item, picked at random."),
        ("choices", "choices(values, k=1)", "k items picked at random, with repeats."),
        ("shuffle", "shuffle(items)", "Shuffles a list in place."),
        ("sample", "sample(values, k)", "k different items picked at random."),
        ("uniform", "uniform(a, b)", "A float between a and b."),
        ("seed", "seed(value=None)", "Seeds the generator, so the same seed gives the same numbers."),
    ] {
        add(leak(format!("random.{name}")), Function, Some(leak(sig.to_string())), None, doc);
    }

    add("time", Module, None, None, "The clock. import time.");
    add("time.time", Function, Some("time()"), None, "Seconds since the epoch, now, as a float.");
    add("time.monotonic", Function, Some("monotonic()"), None, "Seconds from a clock that only moves forward, for measuring how long something takes.");
    add("time.parts", Function, Some("parts(seconds=None)"), None, "A dict of year, month, day, hour, minute, second, weekday (Monday is 0) and yearday for seconds, or for now, in the phone's time zone.");
    add("time.format", Function, Some("format(pattern, seconds=None)"), None, "seconds, or now, as text in a Unicode date pattern such as \"yyyy-MM-dd HH:mm\" or \"EEE d MMM\", with English names.");

    add("json", Module, None, None, "JSON text to and from Python values. import json.");
    add("json.dumps", Function, Some("dumps(value, indent=None)"), None, "value written as JSON text. Dicts keep their order.");
    add("json.loads", Function, Some("loads(text)"), None, "JSON text read as Python values. Raises ValueError on bad JSON.");

    add("re", Module, None, None, "Regular expressions, on a budgeted matcher. import re.");
    for (name, sig, doc) in [
        ("search", "search(pattern, text, flags=0)", "The first match of pattern anywhere in text, or None."),
        ("match", "match(pattern, text, flags=0)", "A match of pattern at the start of text, or None."),
        ("fullmatch", "fullmatch(pattern, text, flags=0)", "A match of pattern covering all of text, or None."),
        ("findall", "findall(pattern, text, flags=0)", "Every match: the whole match with no groups, the group with one, tuples with several."),
        ("finditer", "finditer(pattern, text, flags=0)", "A list of every match object."),
        ("sub", "sub(pattern, replacement, text, count=0, flags=0)", "text with matches replaced. replacement is text with \\1 or \\g<name>, or a function of the match."),
        ("subn", "subn(pattern, replacement, text, count=0, flags=0)", "Like sub, and how many were replaced, as a tuple."),
        ("split", "split(pattern, text, maxsplit=0, flags=0)", "text split at each match."),
        ("escape", "escape(text)", "text with every special character escaped."),
    ] {
        add(leak(format!("re.{name}")), Function, Some(leak(sig.to_string())), None, doc);
    }
    add("re.I", Constant, None, None, "Flag: ignore case.");
    add("re.IGNORECASE", Constant, None, None, "Flag: ignore case.");
    add("re.M", Constant, None, None, "Flag: ^ and $ match at each line.");
    add("re.MULTILINE", Constant, None, None, "Flag: ^ and $ match at each line.");
    add("re.S", Constant, None, None, "Flag: . matches a line break too.");
    add("re.DOTALL", Constant, None, None, "Flag: . matches a line break too.");

    add("sys", Module, None, None, "About the interpreter. import sys.");
    add("sys.version", Constant, None, None, "The interpreter's version line.");

    // ---- methods ----------------------------------------------------------
    add("Match", Type, None, None, "What re.search, re.match and re.fullmatch return when they match.");
    for (name, sig, doc) in [
        ("group", "group(*groups)", "The whole match, or the text of a group by number or name."),
        ("groups", "groups(default=None)", "Every group's text, as a tuple."),
        ("groupdict", "groupdict()", "The named groups' text, as a dict."),
        ("start", "start(group=0)", "Where the match, or a group, starts."),
        ("end", "end(group=0)", "Where the match, or a group, ends."),
        ("span", "span(group=0)", "(start, end) of the match or a group."),
    ] {
        add(leak(format!("Match.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }

    for (name, sig, doc) in [
        ("upper", "upper()", "The text in capital letters."),
        ("lower", "lower()", "The text in small letters."),
        ("casefold", "casefold()", "The text folded for comparing: small letters, accents dropped."),
        ("title", "title()", "Each word with a capital first letter."),
        ("capitalize", "capitalize()", "The first character capital, the rest small."),
        ("swapcase", "swapcase()", "Capitals small and small letters capital."),
        ("strip", "strip(chars=None)", "The text without leading and trailing whitespace, or chars."),
        ("lstrip", "lstrip(chars=None)", "The text without leading whitespace, or chars."),
        ("rstrip", "rstrip(chars=None)", "The text without trailing whitespace, or chars."),
        ("replace", "replace(old, new, count=-1)", "The text with old replaced by new, count times or everywhere."),
        ("split", "split(sep=None, maxsplit=-1)", "A list of the parts between sep; with no sep, the words."),
        ("rsplit", "rsplit(sep=None, maxsplit=-1)", "Like split, counting maxsplit from the end."),
        ("splitlines", "splitlines()", "A list of the lines."),
        ("join", "join(items)", "The strings of items joined with this text between them."),
        ("startswith", "startswith(prefix)", "Whether the text starts with prefix, or any of a tuple of them."),
        ("endswith", "endswith(suffix)", "Whether the text ends with suffix, or any of a tuple of them."),
        ("find", "find(sub, start=0, end=None)", "Where sub first is, or -1."),
        ("rfind", "rfind(sub, start=0, end=None)", "Where sub last is, or -1."),
        ("index", "index(sub, start=0, end=None)", "Where sub first is. Raises ValueError when it is not there."),
        ("rindex", "rindex(sub, start=0, end=None)", "Where sub last is. Raises ValueError when it is not there."),
        ("count", "count(sub)", "How many times sub is in the text."),
        ("partition", "partition(sep)", "(before, sep, after) at the first sep."),
        ("rpartition", "rpartition(sep)", "(before, sep, after) at the last sep."),
        ("zfill", "zfill(width)", "The text padded with zeros on the left to width."),
        ("ljust", "ljust(width, fill=\" \")", "The text padded on the right to width."),
        ("rjust", "rjust(width, fill=\" \")", "The text padded on the left to width."),
        ("center", "center(width, fill=\" \")", "The text padded on both sides to width."),
        ("isdigit", "isdigit()", "Whether every character is a digit."),
        ("isnumeric", "isnumeric()", "Whether every character is numeric."),
        ("isalpha", "isalpha()", "Whether every character is a letter."),
        ("isalnum", "isalnum()", "Whether every character is a letter or a digit."),
        ("isspace", "isspace()", "Whether every character is whitespace."),
        ("isupper", "isupper()", "Whether the letters are all capitals."),
        ("islower", "islower()", "Whether the letters are all small."),
        ("istitle", "istitle()", "Whether each word starts with a capital."),
        ("removeprefix", "removeprefix(prefix)", "The text without prefix at its start."),
        ("removesuffix", "removesuffix(suffix)", "The text without suffix at its end."),
        ("format", "format(*args, **named)", "The text with {} fields filled, as an f-string would."),
        ("encode", "encode(encoding=\"utf-8\")", "The UTF-8 bytes of the text."),
    ] {
        add(leak(format!("str.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }
    for (name, sig, doc) in [
        ("append", "append(item)", "Adds item at the end."),
        ("extend", "extend(items)", "Adds every item of items at the end."),
        ("pop", "pop(index=-1)", "Removes and returns the item at index, the last one by default."),
        ("insert", "insert(index, item)", "Puts item before index."),
        ("remove", "remove(item)", "Removes the first item equal to item. Raises ValueError when there is none."),
        ("index", "index(item, start=0)", "Where the first item equal to item is. Raises ValueError when there is none."),
        ("count", "count(item)", "How many items equal item."),
        ("sort", "sort(key=None, reverse=False)", "Sorts the list in place."),
        ("reverse", "reverse()", "Reverses the list in place."),
        ("clear", "clear()", "Removes every item."),
        ("copy", "copy()", "A new list with the same items."),
    ] {
        add(leak(format!("list.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }
    for (name, sig, doc) in [
        ("keys", "keys()", "A list of the keys, in the order they were added."),
        ("values", "values()", "A list of the values."),
        ("items", "items()", "A list of (key, value) pairs: for key, value in d.items()."),
        ("get", "get(key, default=None)", "The value under key, or default when there is none."),
        ("pop", "pop(key, default)", "Removes key and returns its value, or default. Raises KeyError when there is neither."),
        ("popitem", "popitem()", "Removes and returns the last (key, value) pair."),
        ("setdefault", "setdefault(key, default=None)", "The value under key, first setting it to default when there is none."),
        ("update", "update(other)", "Copies every key and value of other in."),
        ("clear", "clear()", "Removes every key."),
        ("copy", "copy()", "A new dict with the same keys and values."),
    ] {
        add(leak(format!("dict.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }
    for (name, sig, doc) in [
        ("hex", "hex()", "The bytes as hexadecimal text."),
        ("decode", "decode(encoding=\"utf-8\")", "The bytes read as UTF-8 text."),
        ("fromhex", "fromhex(text)", "bytes.fromhex(\"cafe\"): bytes read from hexadecimal text."),
    ] {
        add(leak(format!("bytes.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }
    for (name, sig, doc) in [
        ("add", "add(item)", "Adds item."),
        ("discard", "discard(item)", "Removes item if it is there."),
        ("remove", "remove(item)", "Removes item. Raises KeyError when it is not there."),
        ("pop", "pop()", "Removes and returns some item."),
        ("clear", "clear()", "Removes every item."),
        ("copy", "copy()", "A new set with the same items."),
        ("update", "update(other)", "Adds every item of other."),
        ("union", "union(other)", "A new set of the items in either."),
        ("intersection", "intersection(other)", "A new set of the items in both."),
        ("difference", "difference(other)", "A new set of the items not in other."),
        ("symmetric_difference", "symmetric_difference(other)", "A new set of the items in exactly one of them."),
        ("issubset", "issubset(other)", "Whether every item is in other."),
        ("issuperset", "issuperset(other)", "Whether every item of other is in this set."),
        ("isdisjoint", "isdisjoint(other)", "Whether no item is in both."),
    ] {
        add(leak(format!("set.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }
    for (name, sig, doc) in [
        ("count", "count(item)", "How many items equal item."),
        ("index", "index(item)", "Where the first item equal to item is. Raises ValueError when there is none."),
    ] {
        add(leak(format!("tuple.{name}")), Method, Some(leak(sig.to_string())), None, doc);
    }

    let mut by_path = HashMap::new();
    let mut by_parent: HashMap<&'static str, Vec<usize>> = HashMap::new();
    for (i, e) in entries.iter().enumerate() {
        by_path.insert(e.path, i);
        by_parent.entry(e.parent()).or_default().push(i);
    }
    Model { entries, by_path, by_parent, ui: ui_shapes() }
}

/// Paths built in a loop live as long as the model, which is the whole process.
fn leak(s: String) -> &'static str {
    Box::leak(s.into_boxed_str())
}

/// The entry for a method name on a value of [type_name], or the first value
/// type that has a method of that name when the type is not known.
pub fn method(type_name: Option<&str>, name: &str) -> Option<&'static ApiEntry> {
    let m = model();
    if let Some(t) = type_name {
        return m.find(&format!("{t}.{name}"));
    }
    VALUE_TYPES.iter().find_map(|t| m.find(&format!("{t}.{name}")))
}
