package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of PyMini's tests/LanguageTests.swift; expected values unchanged. */
class LanguageTest {

    @Test
    fun `comprehensions`() {
        assertEquals("[0, 2, 4, 6]", evaluate("[x * 2 for x in range(4)]"))
        assertEquals("[0, 3, 6, 9]", evaluate("[x for x in range(10) if x % 3 == 0]"))
        assertEquals("['ax', 'ay', 'bx', 'by']", evaluate("""[a + b for a in "ab" for b in "xy"]"""))
        assertEquals("{'hi': 2, 'there': 5}", evaluate("""{w: len(w) for w in ["hi", "there"]}"""))
        assertEquals("{'b', 'a', 'n'}", evaluate("""{c for c in "banana"}"""))
        assertEquals("14", evaluate("sum(x * x for x in range(4))"))
        assertEquals(
            "scoped",
            output(
                """
                squares = [x * x for x in range(3)]
                try:
                    x
                    result = "leaked"
                except NameError:
                    result = "scoped"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `lambdas and functional builtins`() {
        assertEquals("10", evaluate("(lambda a, b=2: a * b)(5)"))
        assertEquals("['a', 'cc', 'bbb']", evaluate("""sorted(["bbb", "a", "cc"], key=lambda w: len(w))"""))
        assertEquals("[3, 2, 1]", evaluate("sorted([3, 1, 2], reverse=True)"))
        assertEquals("[2, 3]", evaluate("map(lambda x: x + 1, [1, 2])"))
        assertEquals("[2, 3]", evaluate("filter(lambda x: x > 1, [1, 2, 3])"))
        assertEquals("[1, 'a']", evaluate("""filter(None, [0, 1, "", "a"])"""))
        assertEquals("aa", evaluate("""max(["aa", "b"], key=len)"""))
        assertEquals("none", evaluate("""min([], default="none")"""))
    }

    @Test
    fun `tuples and sets`() {
        assertEquals("(1, 2, 3)", evaluate("(1, 2, 3)"))
        assertEquals("(1,)", evaluate("(1,)"))
        assertEquals("2", evaluate("(1, 2)[1]"))
        assertEquals("a", evaluate("""{(1, 2): "a"}[(1, 2)]"""))
        assertEquals("[(0, 'a'), (1, 'b')]", evaluate("""list(enumerate("ab"))"""))
        assertEquals("[(1, 'a'), (2, 'b')]", evaluate("""list(zip([1, 2], "ab"))"""))
        assertEquals("[2, 3]", evaluate("sorted(set([1, 2, 3]) & set([2, 3, 4]))"))
        assertEquals("[1, 2]", evaluate("sorted(set([1]) | set([2]))"))
        assertEquals("[1]", evaluate("sorted(set([1, 2]) - set([2]))"))
        assertEquals("3", evaluate("""len(set("banana"))"""))
        assertEquals("[('a', 1)]", evaluate("""list({"a": 1}.items())"""))
    }

    @Test
    fun `unpacking`() {
        assertEquals("21", output("a, b = 1, 2\na, b = b, a\nresult = f\"{a}{b}\""))
        assertEquals("6", output("(a, (b, c)) = (1, (2, 3))\nresult = a + b + c"))
        assertEquals(
            "6",
            output(
                """
                def add(a, b, c):
                    return a + b + c
                xs = [1, 2, 3]
                result = add(*xs)
                """.trimIndent(),
            ),
        )
        assertEquals(
            "6",
            output(
                """
                def total(*rest):
                    n = 0
                    for r in rest:
                        n += r
                    return n
                result = total(1, 2, 3)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `formatting`() {
        assertEquals("007", evaluate("""f"{7:03d}""""))
        assertEquals("3.14", evaluate("""f"{3.14159:.2f}""""))
        assertEquals("1,234,567", evaluate("""f"{1234567:,}""""))
        assertEquals("[   hi]", evaluate("""f"[{'hi':>5}]""""))
        assertEquals("[  hi  ]", evaluate("""f"[{'hi':^6}]""""))
        assertEquals("'hi'", evaluate("""f"{'hi'!r}""""))
        assertEquals("   x", evaluate("""f"{'x':>{2 + 2}}""""))
        assertEquals("25.6%", evaluate("""f"{0.256:.1%}""""))
        assertEquals("ff", evaluate("""f"{255:x}""""))
        assertEquals("0005", evaluate("format(5, '04d')"))
        assertEquals("1-2", evaluate(""""{a}-{b}".format(a=1, b=2)"""))
        assertEquals("   7", evaluate(""""{:>4}".format(7)"""))
        assertEquals("a has 2", evaluate(""""%s has %d" % ("a", 2)"""))
    }

    @Test
    fun `exceptions`() {
        assertEquals(
            "caught",
            output(
                """
                def transform(text):
                    try:
                        return str(1 / 0)
                    except ZeroDivisionError:
                        return "caught"
                """.trimIndent(),
            ),
        )
        assertEquals(
            "bad thing",
            output(
                """
                def transform(text):
                    try:
                        raise ValueError("bad thing")
                    except ValueError as e:
                        return str(e)
                """.trimIndent(),
            ),
        )
        assertEquals(
            "abc",
            output(
                """
                out = []
                try:
                    out.append("a")
                    raise ValueError("x")
                except Exception:
                    out.append("b")
                finally:
                    out.append("c")
                result = "".join(out)
                """.trimIndent(),
            ),
        )
        assertEquals(
            "to",
            output(
                """
                out = []
                try:
                    out.append("t")
                except Exception:
                    out.append("e")
                else:
                    out.append("o")
                result = "".join(out)
                """.trimIndent(),
            ),
        )
        assertEquals(
            "yes",
            output(
                """
                d = {}
                try:
                    x = d["nope"]
                    result = "no"
                except KeyError:
                    result = "yes"
                """.trimIndent(),
            ),
        )
        assertTrue(failure("""raise ValueError("boom")""").contains("ValueError: boom"))
    }

    @Test
    fun `step budget cannot be caught`() {
        val message = failure(
            """
            try:
                while True:
                    pass
            except Exception:
                result = "swallowed"
            """.trimIndent(),
        )
        assertTrue(message, message.contains("step budget"))
    }

    @Test
    fun `standard modules`() {
        assertEquals("3", output("import math\nresult = math.floor(math.sqrt(10))"))
        assertEquals("3.142", output("from math import pi\nresult = f\"{pi:.3f}\""))
        assertEquals("6", output("import math as m\nresult = m.gcd(12, 18)"))
        assertEquals(
            """{"b": 1, "a": [2, 3]}""",
            output("import json\nresult = json.dumps(json.loads('{\"b\": 1, \"a\": [2, 3]}'))"),
        )
        assertEquals("[\n  1\n]", output("import json\nresult = json.dumps([1], indent=2)"))
        assertEquals(
            "True",
            output(
                """
                import random
                random.seed(7)
                a = random.randint(1, 100)
                random.seed(7)
                b = random.randint(1, 100)
                result = str(a == b)
                """.trimIndent(),
            ),
        )
        assertEquals("True", output("import time\np = time.parts()\nresult = str(p[\"year\"] > 2000)"))
        assertTrue(failure("import os").contains("no module named 'os'"))
    }

    @Test
    fun `regular expressions`() {
        assertEquals(
            "555/1234",
            output("import re\nm = re.search(r\"(\\d+)-(\\d+)\", \"tel 555-1234 x\")\nresult = m.group(1) + \"/\" + m.group(2)"),
        )
        assertEquals("one,three", output("import re\nresult = \",\".join(re.findall(r\"[a-z]+\", \"one 2 three\"))"))
        assertEquals("a b c", output("import re\nresult = re.sub(r\"\\s+\", \" \", \"a   b\\tc\")"))
        assertEquals("AB CD", output("import re\nresult = re.sub(r\"[a-z]+\", lambda m: m.group(0).upper(), \"ab cd\")"))
        assertEquals("a|b|c", output("import re\nresult = \"|\".join(re.split(r\",\\s*\", \"a, b,c\"))"))
        assertEquals("True", output("import re\nresult = str(re.match(r\"hi\", \"HI there\", re.I) != None)"))
        assertEquals(
            "sam|0",
            output("import re\nm = re.search(r\"(?P<who>\\w+)@(\\w+)\", \"sam@example\")\nresult = m.group(\"who\") + \"|\" + str(m.start())"),
        )
        assertEquals("None", output("import re\nresult = str(re.search(r\"z+\", \"abc\"))"))
        assertEquals("a X X Xa", output("import re\nresult = re.sub(r\"a{2,3}\", \"X\", \"a aa aaa aaaa\")"))
    }

    @Test
    fun `catastrophic pattern runs out of steps`() {
        val message = failure("import re\nre.match(r\"(a+)+b\", \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaac\")")
        assertTrue(message, message.contains("step budget"))
    }

    @Test
    fun `statements`() {
        assertEquals(
            "13",
            output(
                """
                i = 0
                while i < 3:
                    i += 1
                else:
                    i += 10
                result = i
                """.trimIndent(),
            ),
        )
        assertEquals(
            "yes",
            output(
                """
                found = "no"
                for x in [1, 2]:
                    if x == 1:
                        found = "yes"
                        break
                else:
                    found = "never"
                result = found
                """.trimIndent(),
            ),
        )
        assertEquals(
            "2",
            output(
                """
                count = 0
                def bump():
                    global count
                    count += 1
                bump()
                bump()
                result = count
                """.trimIndent(),
            ),
        )
        assertEquals("['b']", output("d = {\"a\": 1, \"b\": 2}\ndel d[\"a\"]\nresult = str(list(d.keys()))"))
        assertEquals("3", output("a = 1; b = 2; result = a + b"))
        assertEquals("2", output("x = 10\nx //= 3\nx **= 2\nx %= 7\nresult = x"))
        assertEquals("True", evaluate("1 is 1"))
        assertEquals("True", evaluate("[] is not []"))
        assertEquals("True", evaluate("1 < 2 < 3"))
    }

    @Test
    fun `strings`() {
        assertEquals("a\\db", evaluate("r\"a\\db\""))
        assertEquals("abcd", evaluate("\"ab\" \"cd\""))
        assertEquals("('a', '=', 'b')", evaluate("\"a=b\".partition(\"=\")"))
        assertEquals("4", evaluate("\"abcabc\".rfind(\"b\")"))
        assertEquals("--x--", evaluate("\"x\".center(5, \"-\")"))
        assertEquals("bba", evaluate("\"aaa\".replace(\"a\", \"b\", 2)"))
        assertEquals("['a', 'b,c']", evaluate("\"a,b,c\".split(\",\", 1)"))
        assertEquals("True", evaluate("\"hello\".startswith((\"x\", \"he\"))"))
        assertEquals("ao", evaluate("\"ÄÖ\".casefold()"))
        assertEquals("True", evaluate("isinstance(\"a\", str)"))
        assertEquals("True", evaluate("isinstance(1, (str, int))"))
        assertEquals("(3, 1)", evaluate("divmod(7, 2)"))
        assertEquals("0xff", evaluate("hex(255)"))
        assertEquals("255", evaluate("int(\"ff\", 16)"))
    }

    @Test
    fun `small programs`() {
        assertEquals("cba", output("def transform(text):\n    return text[::-1]", input = "abc"))
        assertEquals(
            "Hello There",
            output(
                """
                def transform(text):
                    words = text.split(" ")
                    out = []
                    for w in words:
                        if len(w) > 0:
                            out.append(w[0].upper() + w[1:].lower())
                        else:
                            out.append(w)
                    return " ".join(out)
                """.trimIndent(),
                input = "hello there",
            ),
        )
        assertEquals("3 words", output("def transform(text):\n    n = len(text.split())\n    return f\"{n} words\"", input = "a b c"))
    }

    @Test
    fun `rejected syntax says why`() {
        assertTrue(failure("class Thing:\n    pass").contains("'class' is not supported"))
        assertTrue(failure("def f(**options):\n    pass").contains("**kwargs is not supported"))
    }
}
