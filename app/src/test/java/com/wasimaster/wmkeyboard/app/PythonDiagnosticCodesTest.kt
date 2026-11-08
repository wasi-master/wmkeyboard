package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The editor has a sentence for every diagnostic the Python tooling can raise,
 * and none for a code it no longer has. Read straight from the code table in
 * `native/wm-python-tooling/src/diagnostics.rs`, so a code added there without
 * its string fails here rather than showing "Something here is not right".
 */
class PythonDiagnosticCodesTest {

    private val root: File = listOf(File(".."), File("."))
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun everyToolingCodeHasItsString() {
        val table = File(root, "native/wm-python-tooling/src/diagnostics.rs").readText()
        val codes = CODE.findAll(table).map { it.groupValues[1] }.toSortedSet()
        assertEquals(codes, PY_DIAGNOSTICS.keys.toSortedSet())
    }

    private companion object {
        /** `Name = "code", Severity, "message";`, one line per code. */
        val CODE = Regex("""^\s*\w+ = "([a-z_]+)", (?:Error|Warning|Info), """, RegexOption.MULTILINE)
    }
}
