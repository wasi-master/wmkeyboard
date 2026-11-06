package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.fail

/* Port of PyMini's tests/Helpers.swift. */

/** `str()` of an expression. */
internal fun evaluate(expression: String): String = PyRuntime().run(expression).str()

/** Run a script and read back `transform(input)` when it defines one, otherwise its `result` global. */
internal fun output(source: String, input: String = ""): String {
    val py = PyRuntime()
    py.load(source)
    if (py.hasFunction("transform")) return py.call("transform", input).str()
    return py["result"]?.str() ?: "None"
}

/** The message a failing script ends with. */
internal fun failure(source: String): String {
    try {
        PyRuntime().load(source)
    } catch (e: PyError) {
        return e.toString()
    }
    fail("expected an error")
    return ""
}

/** The error [block] throws. */
internal fun pyError(block: () -> Unit): PyError {
    try {
        block()
    } catch (e: PyError) {
        return e
    }
    fail("expected a PyError")
    throw AssertionError()
}
