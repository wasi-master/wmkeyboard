package com.wasimaster.wmkeyboard.app.storage

import com.wasimaster.wmkeyboard.app.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureLibraryInfoTest {

    /** Each gate block in the table, with whether it delivers a library at all. */
    private val libraryGates: List<String> = run {
        val json = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "native/gates.json") }
            .first { it.exists() }
            .readText()
        val gates = json.substring(json.indexOf("\"gates\""))
        val starts = Regex("\"id\":\\s*\"([a-z0-9_]+)\"").findAll(gates).toList()
        starts.filterIndexed { i, start ->
            val end = starts.getOrNull(i + 1)?.range?.first ?: gates.length
            gates.substring(start.range.last, end).contains(".so\"")
        }.map { it.groupValues[1] }
    }

    @Test
    fun `every gate that delivers a library is described`() {
        assertEquals(emptyList<String>(), libraryGates.filterNot(FeatureLibraryInfo.byGate::containsKey))
    }

    @Test
    fun `every library opens a screen a link can reach`() {
        val infos = FeatureLibraryInfo.byGate.values + FeatureLibraryInfo.byLibrary.values
        for (info in infos) {
            assertTrue(info.route, SettingsRoutes.all.any { it == info.route || it == "tool/{toolName}" && info.route.startsWith("tool/") })
        }
    }

    @Test
    fun `a library on its own wins over its gate`() {
        assertEquals(
            FeatureLibraryInfo.byLibrary.getValue("libwmxlit.so"),
            FeatureLibraryInfo.of("libwmxlit.so", "onnx"),
        )
        assertEquals(FeatureLibraryInfo.byGate.getValue("onnx"), FeatureLibraryInfo.of("libonnxruntime.so", "onnx"))
    }

    @Test
    fun `a chip's NPU libraries read as the NPU`() {
        assertEquals(FeatureLibraryInfo.byGate.getValue("npu"), FeatureLibraryInfo.of("libQnnHtpV79Skel.so", "npu_v79"))
    }
}
