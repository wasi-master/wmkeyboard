package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of PyMini's tests/RuntimeTests.swift; expected values unchanged. */
class RuntimeTest {

    @Test
    fun `calling into a script`() {
        val py = PyRuntime()
        py.load("def greet(name, punctuation=\"!\"):\n    return \"hi \" + name + punctuation")
        assertEquals("hi sam!", py.call("greet", "sam").stringValue)
        assertEquals("hi sam?", py.callWithKeywords("greet", listOf("sam"), mapOf("punctuation" to "?")).stringValue)
        assertTrue(py.hasFunction("greet"))
        assertEquals(listOf("name", "punctuation"), py.parameters(of = "greet"))
        assertNull(py.parameters(of = "missing"))
    }

    @Test
    fun `host functions`() {
        val py = PyRuntime()
        val logged = ArrayList<String>()
        py.define("shout") { call -> call.argument(0, PyType.STR).uppercase() }
        py.define("log") { call -> logged.add(call.argument(0, PyType.STR, "message")); Unit }
        val result = py.run("log(message=\"starting\")\nshout(\"quiet\") + \"!\"")
        assertEquals("QUIET!", result.stringValue)
        assertEquals(listOf("starting"), logged)
    }

    @Test
    fun `host argument errors are catchable`() {
        val py = PyRuntime()
        py.define("double") { call -> call.argument(0, PyType.INT) * 2 }
        val result = py.run(
            """
            try:
                double("two")
                message = "no error"
            except TypeError as e:
                message = str(e)
            message
            """.trimIndent(),
        )
        assertEquals("double() argument 1 must be int, not str", result.stringValue)
    }

    @Test
    fun `host errors keep their kind`() {
        val py = PyRuntime()
        py.define("fetch") { call ->
            throw PyError("nothing stored under ${call.argument(0, PyType.STR)}", "KeyError")
        }
        val result = py.run(
            """
            try:
                fetch("colour")
            except LookupError as e:
                found = str(e)
            found
            """.trimIndent(),
        )
        assertEquals("nothing stored under colour", result.stringValue)
        val error = pyError { py.run("\n\nfetch('size')") }
        assertEquals("Line 3: KeyError: nothing stored under size", error.toString())
    }

    @Test
    fun `optional hooks`() {
        val py = PyRuntime()
        py.load("def on_word(word, count):\n    return f\"{word}:{count}\"")
        assertEquals("hi:2", py.callHook("on_word", "hi", 2)?.stringValue)
        assertNull(py.callHook("on_open"))
    }

    @Test
    fun `host callbacks`() {
        val py = PyRuntime()
        py.define("apply_twice") { call ->
            val function = call.argument(0, PyType.VALUE)
            val once = call.invoke(function, call.argument(1, PyType.VALUE))
            call.invoke(function, once)
        }
        assertEquals(18L, py.run("apply_twice(lambda n: n * 3, 2)").intValue)
    }

    @Test
    fun `host modules`() {
        val py = PyRuntime()
        val store = hashMapOf("theme" to "dark")
        py.defineModule("settings") { module ->
            module.set("version", 2)
            module.define("get") { call -> store[call.argument(0, PyType.STR)] }
            module.define("put") { call -> store[call.argument(0, PyType.STR)] = call.argument(1, PyType.STR) }
        }
        val result = py.run(
            """
            import settings
            from settings import get
            settings.put("font", "mono")
            [settings.version, get("theme"), get("missing")]
            """.trimIndent(),
        )
        assertEquals("[2, 'dark', None]", result.repr())
        assertEquals("mono", store["font"])
    }

    @Test
    fun `modules can be turned off`() {
        val py = PyRuntime(modules = setOf("math"))
        py.run("import math")
        val error = pyError { py.run("import time") }
        assertTrue(error.toString(), error.toString().contains("no module named 'time' (available: math)"))
    }

    @Test
    fun `globals`() {
        val py = PyRuntime()
        py["words"] = listOf("one", "two", "three")
        py["limit"] = 2
        assertEquals("['one', 'two']", py.run("words[:limit]").repr())
        py.run("total = len(words)")
        assertEquals(3L, py["total"]?.intValue)
        py["total"] = null
        assertNull(py["total"])
    }

    @Test
    fun `conversions`() {
        assertEquals(listOf(1L, 2L, 3L), PyType.listOf(PyType.INT).from(PyValue.of(listOf(1, 2, 3))))
        assertNull(PyType.listOf(PyType.INT).from(PyValue.of(listOf(1, "two"))))
        assertEquals(mapOf("a" to 1L), PyType.dictOf(PyType.INT).from(PyValue.of(mapOf("a" to 1))))
        val optionalString = PyType.optional(PyType.STR)
        assertTrue(optionalString.accepts(PyValue.None))
        assertNull(optionalString.from(PyValue.None))
        assertFalse(optionalString.accepts(PyValue.of(5)))
        assertEquals(3.0, PyType.FLOAT.from(PyValue.of(3))!!, 0.0)
        assertEquals("{'a': 1, 'b': 2}", PyValue.of(mapOf("b" to 2, "a" to 1)).repr())
        assertEquals(PyValue.Int(1), PyValue.Float(1.0))
    }

    @Test
    fun `each call gets its own budget and output`() {
        // PyMini's container default is 200,000; this port's is 10,000, which
        // would stop range(100_000) on size before the budget, so restore it.
        val py = PyRuntime(limits = PyLimits(steps = 5_000, containerItems = 200_000))
        py.load(
            """
            def count(n):
                print("counting", n)
                total = 0
                for i in range(n):
                    total += i
                return total
            """.trimIndent(),
        )
        repeat(5) {
            assertEquals(19_900L, py.call("count", 200).intValue)
            assertEquals(listOf("counting 200"), py.outputLines)
            assertEquals("counting 200", py.output)
        }
        val error = pyError { py.call("count", 100_000) }
        assertTrue(error.toString(), error.toString().contains("step budget"))
    }

    @Test
    fun `container limit`() {
        val py = PyRuntime(limits = PyLimits(containerItems = 10))
        val error = pyError { py.run("list(range(100))") }
        assertTrue(error.toString(), error.toString().contains("range too large"))
    }

    @Test
    fun `print is streamed`() {
        val py = PyRuntime()
        val lines = ArrayList<String>()
        py.onPrint = { lines.add(it) }
        py.run("for n in range(3):\n    print('line', n)")
        assertEquals(listOf("line 0", "line 1", "line 2"), lines)
    }

    @Test
    fun `stray control flow is a syntax error`() {
        assertEquals("SyntaxError", pyError { PyRuntime().run("break") }.kind)
    }

    @Test
    fun `version is readable from Kotlin and from a script`() {
        assertEquals("pymini " + PyRuntime.VERSION, PyRuntime.VERSION_LINE)
        // x.y.z, so a host comparing versions has something to split on.
        assertEquals(3, PyRuntime.VERSION.split(".").size)
        assertEquals(PyRuntime.VERSION_LINE, PyRuntime().run("import sys\nsys.version").str())
    }

    @Test
    fun `sys can be switched off like any other module`() {
        val py = PyRuntime(modules = setOf("math"))
        assertEquals("ImportError", pyError { py.run("import sys") }.kind)
    }
}
