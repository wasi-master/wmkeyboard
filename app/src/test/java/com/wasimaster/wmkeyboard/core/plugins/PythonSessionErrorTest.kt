package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where [PythonSession.decode] says an error is, from the library's file and traceback. */
class PythonSessionErrorTest {

    private fun error(json: String): PythonSession.Error =
        PythonSession.decode("""{"ok": false, "error": $json}""").error!!

    @Test
    fun `an error in a module names that file and keeps the plugin's calls`() {
        val e = error(
            """
            {"kind": "runtime", "message": "ValueError: negative fare", "line": 3, "file": "fares.py", "exception": "ValueError",
             "traceback": [{"file": null, "line": 47, "function": "render"}, {"file": "main.py", "line": 6, "function": "render"},
                           {"file": "fares.py", "line": 8, "function": "total"}, {"file": "fares.py", "line": 3, "function": "fare"}]}
            """.trimIndent(),
        )
        assertEquals("fares.py" to 3, e.file to e.line)
        assertEquals(listOf("main.py:6 render", "fares.py:8 total", "fares.py:3 fare"), e.traceback.map { "${it.file}:${it.line} ${it.function}" })
        assertEquals("fares.py:3: boom", PluginRuntime.locatedText("boom", e.line, e.file))
        assertEquals(listOf("  at main.py:6 in render", "  at fares.py:8 in total", "  at fares.py:3 in fare"), PluginRuntime.tracebackLines(e))
    }

    @Test
    fun `an error raised inside a bundled module points at the plugin's call`() {
        val e = error(
            """
            {"kind": "runtime", "message": "HTTPError: 404", "line": 120, "file": null,
             "traceback": [{"file": "main.py", "line": 5, "function": "render"}, {"file": null, "line": 120, "function": "Response.raise_for_status"}]}
            """.trimIndent(),
        )
        assertEquals("main.py" to 5, e.file to e.line)
        assertEquals(emptyList<String>(), PluginRuntime.tracebackLines(e))
    }

    @Test
    fun `a native function's error takes the line of the call to it`() {
        val e = error("""{"kind": "runtime", "message": "ValueError: expected a JSON string", "line": null, "file": "main.py", "traceback": [{"file": "main.py", "line": 10, "function": "render"}]}""")
        assertEquals("main.py" to 10, e.file to e.line)
    }

    @Test
    fun `an error from before tracebacks keeps its line in the entry`() {
        val e = error("""{"kind": "compile", "message": "SyntaxError: expected ':'", "line": 2}""")
        assertNull(e.file)
        assertEquals(2, e.line)
        assertEquals("main.py:2: boom", PluginRuntime.locatedText("boom", e.line, e.file))
    }
}
