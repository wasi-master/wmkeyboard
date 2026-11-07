package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.modules.NativePin
import com.wasimaster.wmkeyboard.core.modules.NativeSource
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureLibraryListTest {

    private fun pin(gate: String, name: String, size: Long = 10, licence: String? = null) =
        NativePin(gate, "arm64-v8a", name, name, size, NativeSource.Data, licence)

    private val pins = listOf(
        pin("onnx", "libonnxruntime.so"),
        pin("onnx", "libwmsherpa.so"),
        pin("translate", "libtranslate_jni.so"),
        pin("llamagpu", "libwmllama_opencl.so"),
        pin("llamavulkan", "libwmllama_vulkan.so"),
        pin("whispervulkan", "libwmwhispercpp_vulkan.so"),
        pin("npu", "libQnnHtp.so", licence = "qualcomm"),
        pin("npu-V79", "libQnnHtpV79Stub.so", licence = "qualcomm"),
        pin("npu-V81", "libQnnHtpV81Stub.so", licence = "qualcomm"),
        pin("nobody-described-this", "libother.so"),
    )

    private fun ids(chip: List<String> = emptyList(), gpu: Set<String> = emptySet(), offline: Boolean = false) =
        FeatureLibraryList.fromPins(pins, chip, gpu, offline).map { it.id }

    @Test
    fun `a gate's libraries share one row and add up`() {
        val onnx = FeatureLibraryList.fromPins(pins, emptyList(), gpuGates = emptySet(), offline = false).first { it.id == "onnx" }
        assertEquals(listOf("libonnxruntime.so", "libwmsherpa.so"), onnx.files)
        assertEquals(20L, onnx.bytes)
    }

    @Test
    fun `a phone without an NPU or a GPU is not offered their libraries`() {
        assertEquals(listOf("onnx", "translate"), ids())
    }

    @Test
    fun `a GPU is offered its own builds and not the other GPU's`() {
        assertEquals(listOf("onnx", "translate", "llamagpu"), ids(gpu = setOf("llamagpu")))
        assertEquals(
            listOf("onnx", "translate", "llamavulkan", "whispervulkan"),
            ids(gpu = setOf("llamavulkan", "whispervulkan")),
        )
    }

    @Test
    fun `the NPU is one row with this chip's files and no other chip's`() {
        val npu = FeatureLibraryList.fromPins(pins, listOf("libQnnHtpV79Stub.so"), gpuGates = emptySet(), offline = false)
            .first { it.id == "npu" }
        assertEquals(listOf("npu", "npu-V79"), npu.gates)
        assertEquals(listOf("libQnnHtp.so", "libQnnHtpV79Stub.so"), npu.files)
        assertEquals(listOf("qualcomm"), npu.licences)
    }

    @Test
    fun `the offline build leaves out engines whose models it cannot get`() {
        assertEquals(listOf("onnx"), ids(offline = true))
    }

    @Test
    fun `Play modules follow the same phone checks and have no files or sizes`() {
        val rows = FeatureLibraryList.fromModules(listOf("speech", "npu", "llamagpu", "llamavulkan", "googlehome"), hasNpu = false, gpuGates = emptySet())
        assertEquals(listOf("speech"), rows.map { it.id })
        assertEquals(emptyList<String>(), rows.single().files)
        assertEquals(0L, rows.single().bytes)
    }
}
