//! The formatter changes whitespace only, is idempotent, and leaves the demos alone.

mod common;

use common::*;
use wm_python_tooling::format::format;

fn no_space(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).collect()
}

fn check(input: &str, expected: &str) {
    let out = format(input);
    assert_eq!(out, expected, "\ninput:\n{input}");
    assert_eq!(format(&out), out, "not idempotent:\n{out}");
}

#[test]
fn the_demos_come_out_unchanged() {
    for name in FIXTURES {
        let source = fixture(name);
        assert_eq!(format(&source), source, "{name} changed");
    }
}

#[test]
fn indentation_becomes_four_spaces_a_level() {
    check("if x:\n\ty = 1\n\tif y:\n\t\tz = 2\n", "if x:\n    y = 1\n    if y:\n        z = 2\n");
    check("def f():\n  return 1\n", "def f():\n    return 1\n");
    check("def f():\n  x = g(1,\n        2)\n  return x\n", "def f():\n    x = g(1,\n          2)\n    return x\n");
}

#[test]
fn comment_lines_sit_with_their_code() {
    check("def f():\n  # inside\n  return 1\n# after\n", "def f():\n    # inside\n    return 1\n\n\n# after\n");
    check("if x:\n  y = 1\n  # still in the block\nz = 2\n", "if x:\n    y = 1\n    # still in the block\nz = 2\n");
}

#[test]
fn trailing_whitespace_goes_and_one_line_break_ends_the_file() {
    check("x = 1   \ny = 2\t", "x = 1\ny = 2\n");
    check("x = 1\n\n\n\n", "x = 1\n");
    check("\n\nx = 1\n", "x = 1\n");
    check("", "");
}

#[test]
fn blank_lines_around_definitions() {
    check("x = 1\ndef f():\n    pass\ny = 2\n", "x = 1\n\n\ndef f():\n    pass\n\n\ny = 2\n");
    check("x = 1\n\n\n\n\ny = 2\n", "x = 1\n\n\ny = 2\n");
    check("def f():\n    a = 1\n\n\n\n    return a\n", "def f():\n    a = 1\n\n    return a\n");
    check("x = 1\n# about f\ndef f():\n    pass\n", "x = 1\n\n\n# about f\ndef f():\n    pass\n");
    check(
        "def outer():\n    x = 1\n    def inner():\n        pass\n    return inner\n",
        "def outer():\n    x = 1\n\n    def inner():\n        pass\n    return inner\n",
    );
    check("def outer():\n    def inner():\n        pass\n    return inner\n", "def outer():\n    def inner():\n        pass\n    return inner\n");
}

#[test]
fn spaces_around_operators_where_they_are_plain_to_see() {
    check("x=1+2\nf(a,b, c=1)\nif a==b: pass\n", "x = 1 + 2\nf(a, b, c=1)\nif a == b: pass\n");
    check("f(a = 1)\n", "f(a=1)\n");
    check("def f(a = 1, *args):\n    return a\n", "def f(a=1, *args):\n    return a\n");
    check("g = lambda x=1: x\n", "g = lambda x=1: x\n");
    check("y = a*-b\nz = -1\nn += 1\n", "y = a * -b\nz = -1\nn += 1\n");
    check("x = s[a+1:b]\n", "x = s[a+1:b]\n");
    check("x = 'a=b,c'  # a=b,c\n", "x = 'a=b,c'  # a=b,c\n");
    check("x = a**2\nf(*args)\n", "x = a**2\nf(*args)\n");
}

#[test]
fn triple_quoted_strings_are_copied_exactly() {
    let source = "def f():\n  s = '''\n   keep  \n\tthis\n\n\n\n'''\n  return s\n";
    let out = format(source);
    assert!(out.contains("'''\n   keep  \n\tthis\n\n\n\n'''"), "{out}");
    assert_eq!(format(&out), out);
}

#[test]
fn a_file_with_a_syntax_error_is_reindented_but_not_respaced() {
    check("def f():\n  x = = 1\n  y=2\n", "def f():\n    x = = 1\n    y=2\n");
}

#[test]
fn a_file_with_broken_indentation_or_brackets_only_loses_trailing_spaces() {
    check("x = 1\n  y=2  \n", "x = 1\n  y=2\n");
    check("x = (1,\n  2   \n", "x = (1,\n  2\n");
}

#[test]
fn only_whitespace_ever_changes_and_twice_is_once() {
    let mut inputs: Vec<String> = FIXTURES.iter().map(|f| fixture(f)).collect();
    inputs.push("if a:\n\tb=c+d*e  \n\n\n\n\tdef g( x ,y ):\n\t\treturn x==y\nz=[1,2 ,3]\n".into());
    inputs.push("class A:\n  def m(self):\n      return {**a}\n".into());
    inputs.push("x = (1,\n   2,\n      3)\ny = '''\n  text\n'''\n".into());
    let mut rng = Lcg(7);
    let fixtures: Vec<String> = FIXTURES.iter().map(|f| fixture(f)).collect();
    for _ in 0..60 {
        let base = &fixtures[rng.below(fixtures.len())];
        let lines: Vec<&str> = base.lines().collect();
        let a = rng.below(lines.len());
        let b = (a + 1 + rng.below(40)).min(lines.len());
        let mut chunk = lines[a..b].join("\n");
        // Re-indent with tabs and twos, and add trailing spaces, to give it work.
        if rng.below(2) == 0 {
            chunk = chunk.replace("    ", "\t");
        } else {
            chunk = chunk.replace("    ", "  ");
        }
        chunk = chunk.replace(" = ", "=").replace(", ", ",");
        inputs.push(chunk);
    }
    for input in inputs {
        let once = format(&input);
        assert_eq!(no_space(&once), no_space(&input), "changed more than whitespace:\n{input}\n---\n{once}");
        assert_eq!(format(&once), once, "not idempotent:\n{input}\n---\n{once}");
    }
}

#[test]
fn line_endings_are_kept() {
    check("x=1\r\ny=2\r\n", "x = 1\r\ny = 2\r\n");
}
