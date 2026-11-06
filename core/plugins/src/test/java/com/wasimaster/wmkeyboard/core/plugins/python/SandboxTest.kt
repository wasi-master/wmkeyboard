package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this port adds or pins beyond PyMini's own suite: aborts, cancellation,
 * module switches, seeded randomness and the Swift string model. Values marked
 * "PyMini:" were read from PyMini 2.0.1 built from source.
 */
class SandboxTest {

    @Test
    fun `an endless loop aborts`() {
        val error = pyError { PyRuntime(PyLimits(steps = 10_000)).load("while True:\n    pass") }
        assertTrue(error.isAbort)
        assertTrue(error.toString(), error.toString().contains("step budget"))
    }

    @Test
    fun `a bare except cannot swallow the budget`() {
        val error = pyError {
            PyRuntime(PyLimits(steps = 10_000)).load("try:\n    while True:\n        pass\nexcept:\n    pass")
        }
        assertTrue(error.isAbort)
    }

    @Test
    fun `cancellation aborts and cannot be caught`() {
        var calls = 0
        val py = PyRuntime(cancelled = { ++calls > 500 })
        val error = pyError { py.load("try:\n    while True:\n        pass\nexcept Exception:\n    pass") }
        assertTrue(error.isAbort)
        assertTrue(error.toString(), error.toString().contains("cancelled"))
    }

    @Test
    fun `catastrophic regex spends the budget instead of hanging`() {
        val py = PyRuntime(PyLimits(steps = 50_000))
        val error = pyError { py.run("import re\nre.match(r\"(a+)+b\", \"a\" * 30)") }
        assertTrue(error.isAbort)
        assertTrue(error.toString(), error.toString().contains("step budget"))
    }

    @Test
    fun `size caps are aborts`() {
        val small = PyLimits(containerItems = 100, stringLength = 50)
        assertTrue(pyError { PyRuntime(small).run("x = []\nwhile True:\n    x.append(1)") }.isAbort)
        assertTrue(pyError { PyRuntime(small).run("s = 'ab' * 30") }.isAbort)
        assertTrue(pyError { PyRuntime(small).run("try:\n    s = 'ab' * 30\nexcept:\n    pass") }.isAbort)
    }

    @Test
    fun `runaway recursion aborts instead of overflowing the stack`() {
        val error = pyError { PyRuntime().load("def f(n):\n    return f(n + 1)\nf(0)") }
        assertTrue(error.isAbort)
    }

    @Test
    fun `an excluded module is a catchable ImportError`() {
        val py = PyRuntime(modules = setOf("math", "json", "re", "sys"))
        assertEquals(
            "caught",
            py.run("try:\n    import random\n    r = 'imported'\nexcept ImportError:\n    r = 'caught'\nr").str(),
        )
        val error = pyError { py.run("import time") }
        assertEquals("ImportError", error.kind)
        assertFalse(error.isAbort)
    }

    @Test
    fun `every standard module is on by default`() {
        assertEquals(setOf("math", "random", "time", "json", "re", "sys"), PyRuntime.STANDARD_MODULES)
        PyRuntime().run("import math\nimport random\nimport time\nimport json\nimport re\nimport sys")
    }

    @Test
    fun `seeded random matches PyMini`() {
        val result = PyRuntime().run(
            """
            import random
            random.seed(7)
            a = [random.randint(1, 100) for _ in range(5)]
            random.seed(42)
            b = random.random()
            xs = [1, 2, 3, 4, 5, 6]
            random.shuffle(xs)
            c = random.choice("abcdef")
            d = random.sample([10, 20, 30, 40], 2)
            e = random.randrange(0, 100, 7)
            f = random.uniform(1.0, 2.0)
            g = random.choices([1, 2, 3], 4)
            [a, b, xs, c, d, e, f, g]
            """.trimIndent(),
        )
        // PyMini: the same script, same seeds.
        assertEquals(
            "[[88, 5, 47, 4, 75], 0.7281787732893573, [6, 5, 1, 4, 3, 2], 'a', [30, 10], 49, 1.7460986192516328, [2, 1, 3, 1]]",
            result.repr(),
        )
    }

    @Test
    fun `random stays in range`() {
        val result = PyRuntime().run(
            """
            import random
            ok = True
            for _ in range(200):
                n = random.randint(3, 5)
                u = random.random()
                ok = ok and 3 <= n <= 5 and 0 <= u < 1 and random.randrange(10) < 10
            ok
            """.trimIndent(),
        )
        assertEquals("True", result.str())
    }

    @Test
    fun `time parts and format`() {
        // 2023-11-14 12:00 UTC: the same year and month in every time zone.
        val py = PyRuntime()
        assertEquals("[2023, 11]", py.run("import time\np = time.parts(1699963200)\n[p['year'], p['month']]").repr())
        assertEquals("2023", py.run("import time\ntime.format('yyyy', 1699963200)").str())
        assertTrue(py.run("import time\ntime.time() > 1699963200").isTruthy)
    }

    @Test
    fun `strings are grapheme clusters as in Swift`() {
        // PyMini: 7, 'é', True, 1, False, ['a', 'b', 'z', 'é']
        val s = "\"héllo 👍🏽\""
        assertEquals("7", evaluate("len($s)"))
        assertEquals("é", evaluate("$s[1]"))
        assertEquals("True", evaluate("\"é\" == \"é\""))
        assertEquals("1", evaluate("{\"é\": 1}.get(\"é\")"))
        assertEquals("False", evaluate("\"é\" < \"f\""))
        assertEquals("['a', 'b', 'z', 'é']", evaluate("sorted([\"b\", \"é\", \"a\", \"z\"])"))
        assertEquals("1", evaluate("len(\"👍🏽\")"))
    }

    @Test
    fun `case mapping follows Swift and Foundation`() {
        // PyMini: no final sigma, Foundation's capitalized for title().
        assertEquals("οδοσ σα", evaluate("\"ΟΔΟΣ ΣΑ\".lower()"))
        assertEquals("Don’t Stop", evaluate("\"don’t stop\".title()"))
        assertEquals("It's O'neil", evaluate("\"it's o'neil\".title()"))
        assertEquals("X1Y Z.W", evaluate("\"x1y z.w\".title()"))
        assertEquals("Hello-World Word.Word A:B X_Y Abc1Def École Élève", evaluate("\"hello-world word.word a:b x_y abc1def ÉCOLE élève\".title()"))
        assertEquals("Ssa", evaluate("\"ßa\".title()"))
        assertEquals("aoøø fi ss", evaluate("\"ÄÖøØ ﬁ ß\".casefold()"))
        assertEquals("['a', 'b', 'c']", evaluate("\"a\\tb c\".split()"))
    }

    @Test
    fun `numbers print like PyMini`() {
        // PyMini: ints run through Double, so 10**20 is a float.
        assertEquals("1e+20", evaluate("10**20"))
        assertEquals("0.30000000000000004", evaluate("0.1+0.2"))
        assertEquals("1e+16", evaluate("1e16"))
        assertEquals("1e-05", evaluate("1e-5"))
        assertEquals("-0.0", evaluate("-0.0"))
        assertEquals("9007199254740992.0", evaluate("2**53+1"))
        assertEquals("3", evaluate("int(\"3.5\")"))
        assertEquals("100,000,000,000,000,000,000.00", evaluate("f\"{1e20:,.2f}\""))
        assertEquals("1.2e+04", evaluate("\"%5.1e\" % 12345.678"))
        assertEquals("str", evaluate("type('x')"))
        assertEquals("{3, 1, 2}", evaluate("{3, 1, 2, 1}"))
    }
}
