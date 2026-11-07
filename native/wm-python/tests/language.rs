//! Port of LanguageTest.kt (PyMini's tests/LanguageTests.swift); expected values unchanged.

mod common;
use common::*;

#[test]
fn comprehensions() {
    assert_eq!("[0, 2, 4, 6]", evaluate("[x * 2 for x in range(4)]"));
    assert_eq!("[0, 3, 6, 9]", evaluate("[x for x in range(10) if x % 3 == 0]"));
    assert_eq!("['ax', 'ay', 'bx', 'by']", evaluate(r#"[a + b for a in "ab" for b in "xy"]"#));
    assert_eq!("{'hi': 2, 'there': 5}", evaluate(r#"{w: len(w) for w in ["hi", "there"]}"#));
    assert_eq!("{'b', 'a', 'n'}", evaluate(r#"{c for c in "banana"}"#));
    assert_eq!("14", evaluate("sum(x * x for x in range(4))"));
    assert_eq!(
        "scoped",
        output("squares = [x * x for x in range(3)]\ntry:\n    x\n    result = \"leaked\"\nexcept NameError:\n    result = \"scoped\"")
    );
}

#[test]
fn lambdas_and_functional_builtins() {
    assert_eq!("10", evaluate("(lambda a, b=2: a * b)(5)"));
    assert_eq!("['a', 'cc', 'bbb']", evaluate(r#"sorted(["bbb", "a", "cc"], key=lambda w: len(w))"#));
    assert_eq!("[3, 2, 1]", evaluate("sorted([3, 1, 2], reverse=True)"));
    assert_eq!("[2, 3]", evaluate("map(lambda x: x + 1, [1, 2])"));
    assert_eq!("[2, 3]", evaluate("filter(lambda x: x > 1, [1, 2, 3])"));
    assert_eq!("[1, 'a']", evaluate(r#"filter(None, [0, 1, "", "a"])"#));
    assert_eq!("aa", evaluate(r#"max(["aa", "b"], key=len)"#));
    assert_eq!("none", evaluate(r#"min([], default="none")"#));
}

#[test]
fn tuples_and_sets() {
    assert_eq!("(1, 2, 3)", evaluate("(1, 2, 3)"));
    assert_eq!("(1,)", evaluate("(1,)"));
    assert_eq!("2", evaluate("(1, 2)[1]"));
    assert_eq!("a", evaluate(r#"{(1, 2): "a"}[(1, 2)]"#));
    assert_eq!("[(0, 'a'), (1, 'b')]", evaluate(r#"list(enumerate("ab"))"#));
    assert_eq!("[(1, 'a'), (2, 'b')]", evaluate(r#"list(zip([1, 2], "ab"))"#));
    assert_eq!("[2, 3]", evaluate("sorted(set([1, 2, 3]) & set([2, 3, 4]))"));
    assert_eq!("[1, 2]", evaluate("sorted(set([1]) | set([2]))"));
    assert_eq!("[1]", evaluate("sorted(set([1, 2]) - set([2]))"));
    assert_eq!("3", evaluate(r#"len(set("banana"))"#));
    assert_eq!("[('a', 1)]", evaluate(r#"list({"a": 1}.items())"#));
}

#[test]
fn unpacking() {
    assert_eq!("21", output("a, b = 1, 2\na, b = b, a\nresult = f\"{a}{b}\""));
    assert_eq!("6", output("(a, (b, c)) = (1, (2, 3))\nresult = a + b + c"));
    assert_eq!("6", output("def add(a, b, c):\n    return a + b + c\nxs = [1, 2, 3]\nresult = add(*xs)"));
    assert_eq!("6", output("def total(*rest):\n    n = 0\n    for r in rest:\n        n += r\n    return n\nresult = total(1, 2, 3)"));
}

#[test]
fn formatting() {
    assert_eq!("007", evaluate(r#"f"{7:03d}""#));
    assert_eq!("3.14", evaluate(r#"f"{3.14159:.2f}""#));
    assert_eq!("1,234,567", evaluate(r#"f"{1234567:,}""#));
    assert_eq!("[   hi]", evaluate(r#"f"[{'hi':>5}]""#));
    assert_eq!("[  hi  ]", evaluate(r#"f"[{'hi':^6}]""#));
    assert_eq!("'hi'", evaluate(r#"f"{'hi'!r}""#));
    assert_eq!("   x", evaluate(r#"f"{'x':>{2 + 2}}""#));
    assert_eq!("25.6%", evaluate(r#"f"{0.256:.1%}""#));
    assert_eq!("ff", evaluate(r#"f"{255:x}""#));
    assert_eq!("0005", evaluate("format(5, '04d')"));
    assert_eq!("1-2", evaluate(r#""{a}-{b}".format(a=1, b=2)"#));
    assert_eq!("   7", evaluate(r#""{:>4}".format(7)"#));
    assert_eq!("a has 2", evaluate(r#""%s has %d" % ("a", 2)"#));
}

#[test]
fn exceptions() {
    assert_eq!("caught", output("def transform(text):\n    try:\n        return str(1 / 0)\n    except ZeroDivisionError:\n        return \"caught\""));
    assert_eq!(
        "bad thing",
        output("def transform(text):\n    try:\n        raise ValueError(\"bad thing\")\n    except ValueError as e:\n        return str(e)")
    );
    assert_eq!(
        "abc",
        output("out = []\ntry:\n    out.append(\"a\")\n    raise ValueError(\"x\")\nexcept Exception:\n    out.append(\"b\")\nfinally:\n    out.append(\"c\")\nresult = \"\".join(out)")
    );
    assert_eq!(
        "to",
        output("out = []\ntry:\n    out.append(\"t\")\nexcept Exception:\n    out.append(\"e\")\nelse:\n    out.append(\"o\")\nresult = \"\".join(out)")
    );
    assert_eq!("yes", output("d = {}\ntry:\n    x = d[\"nope\"]\n    result = \"no\"\nexcept KeyError:\n    result = \"yes\""));
    assert!(failure(r#"raise ValueError("boom")"#).contains("ValueError: boom"));
}

#[test]
fn step_budget_cannot_be_caught() {
    let message = failure("try:\n    while True:\n        pass\nexcept Exception:\n    result = \"swallowed\"");
    assert!(message.contains("step budget"), "{message}");
}

#[test]
fn standard_modules() {
    assert_eq!("3", output("import math\nresult = math.floor(math.sqrt(10))"));
    assert_eq!("3.142", output("from math import pi\nresult = f\"{pi:.3f}\""));
    assert_eq!("6", output("import math as m\nresult = m.gcd(12, 18)"));
    assert_eq!(r#"{"b": 1, "a": [2, 3]}"#, output("import json\nresult = json.dumps(json.loads('{\"b\": 1, \"a\": [2, 3]}'))"));
    assert_eq!("[\n  1\n]", output("import json\nresult = json.dumps([1], indent=2)"));
    assert_eq!(
        "True",
        output("import random\nrandom.seed(7)\na = random.randint(1, 100)\nrandom.seed(7)\nb = random.randint(1, 100)\nresult = str(a == b)")
    );
    assert_eq!("True", output("import time\np = time.parts()\nresult = str(p[\"year\"] > 2000)"));
    assert!(failure("import os").contains("no module named 'os'"));
}

#[test]
fn regular_expressions() {
    assert_eq!("555/1234", output("import re\nm = re.search(r\"(\\d+)-(\\d+)\", \"tel 555-1234 x\")\nresult = m.group(1) + \"/\" + m.group(2)"));
    assert_eq!("one,three", output("import re\nresult = \",\".join(re.findall(r\"[a-z]+\", \"one 2 three\"))"));
    assert_eq!("a b c", output("import re\nresult = re.sub(r\"\\s+\", \" \", \"a   b\\tc\")"));
    assert_eq!("AB CD", output("import re\nresult = re.sub(r\"[a-z]+\", lambda m: m.group(0).upper(), \"ab cd\")"));
    assert_eq!("a|b|c", output("import re\nresult = \"|\".join(re.split(r\",\\s*\", \"a, b,c\"))"));
    assert_eq!("True", output("import re\nresult = str(re.match(r\"hi\", \"HI there\", re.I) != None)"));
    assert_eq!(
        "sam|0",
        output("import re\nm = re.search(r\"(?P<who>\\w+)@(\\w+)\", \"sam@example\")\nresult = m.group(\"who\") + \"|\" + str(m.start())")
    );
    assert_eq!("None", output("import re\nresult = str(re.search(r\"z+\", \"abc\"))"));
    assert_eq!("a X X Xa", output("import re\nresult = re.sub(r\"a{2,3}\", \"X\", \"a aa aaa aaaa\")"));
}

#[test]
fn catastrophic_pattern_runs_out_of_steps() {
    let message = big_stack(|| failure("import re\nre.match(r\"(a+)+b\", \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaac\")"));
    assert!(message.contains("step budget"), "{message}");
}

#[test]
fn statements() {
    assert_eq!("13", output("i = 0\nwhile i < 3:\n    i += 1\nelse:\n    i += 10\nresult = i"));
    assert_eq!(
        "yes",
        output("found = \"no\"\nfor x in [1, 2]:\n    if x == 1:\n        found = \"yes\"\n        break\nelse:\n    found = \"never\"\nresult = found")
    );
    assert_eq!("2", output("count = 0\ndef bump():\n    global count\n    count += 1\nbump()\nbump()\nresult = count"));
    assert_eq!("['b']", output("d = {\"a\": 1, \"b\": 2}\ndel d[\"a\"]\nresult = str(list(d.keys()))"));
    assert_eq!("3", output("a = 1; b = 2; result = a + b"));
    assert_eq!("2", output("x = 10\nx //= 3\nx **= 2\nx %= 7\nresult = x"));
    assert_eq!("True", evaluate("1 is 1"));
    assert_eq!("True", evaluate("[] is not []"));
    assert_eq!("True", evaluate("1 < 2 < 3"));
}

#[test]
fn strings() {
    assert_eq!("a\\db", evaluate("r\"a\\db\""));
    assert_eq!("abcd", evaluate("\"ab\" \"cd\""));
    assert_eq!("('a', '=', 'b')", evaluate("\"a=b\".partition(\"=\")"));
    assert_eq!("4", evaluate("\"abcabc\".rfind(\"b\")"));
    assert_eq!("--x--", evaluate("\"x\".center(5, \"-\")"));
    assert_eq!("bba", evaluate("\"aaa\".replace(\"a\", \"b\", 2)"));
    assert_eq!("['a', 'b,c']", evaluate("\"a,b,c\".split(\",\", 1)"));
    assert_eq!("True", evaluate("\"hello\".startswith((\"x\", \"he\"))"));
    assert_eq!("ao", evaluate("\"ÄÖ\".casefold()"));
    assert_eq!("True", evaluate("isinstance(\"a\", str)"));
    assert_eq!("True", evaluate("isinstance(1, (str, int))"));
    assert_eq!("(3, 1)", evaluate("divmod(7, 2)"));
    assert_eq!("0xff", evaluate("hex(255)"));
    assert_eq!("255", evaluate("int(\"ff\", 16)"));
}

#[test]
fn small_programs() {
    assert_eq!("cba", output_with("def transform(text):\n    return text[::-1]", "abc"));
    assert_eq!(
        "Hello There",
        output_with(
            "def transform(text):\n    words = text.split(\" \")\n    out = []\n    for w in words:\n        if len(w) > 0:\n            out.append(w[0].upper() + w[1:].lower())\n        else:\n            out.append(w)\n    return \" \".join(out)",
            "hello there"
        )
    );
    assert_eq!("3 words", output_with("def transform(text):\n    n = len(text.split())\n    return f\"{n} words\"", "a b c"));
}

#[test]
fn rejected_syntax_says_why() {
    assert!(failure("class Thing:\n    pass").contains("'class' is not supported"));
    assert!(failure("def f(**options):\n    pass").contains("**kwargs is not supported"));
}
