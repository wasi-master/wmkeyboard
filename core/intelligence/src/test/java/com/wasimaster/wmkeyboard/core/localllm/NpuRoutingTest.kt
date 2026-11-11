package com.wasimaster.wmkeyboard.core.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The NPU as a Compute choice (#804): which chips get one, which file a
 * download fetches on them, and which file the engine runs or falls back to.
 */
class NpuRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gemma1b get() = LocalLlmCatalog.byId("gemma3-1b")!!
    private val gemma270m get() = LocalLlmCatalog.byId("gemma3-270m")!!
    private val qwen get() = LocalLlmCatalog.byId("qwen3-0.6b")!!

    private fun materialize(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("x")
        return file
    }

    // --- Which chips ---

    @Test
    fun `a vendor suffix or lowercase still names the chip`() {
        assertEquals("SM8750", NpuSupport.normalizeSoc("SM8750"))
        assertEquals("SM8750", NpuSupport.normalizeSoc("SM8750-AC"))
        assertEquals("SM8850", NpuSupport.normalizeSoc(" sm8850 "))
        assertEquals("MT6991", NpuSupport.normalizeSoc("MT6991"))
        assertEquals("MT6993", NpuSupport.normalizeSoc("MT6993V/CZA"))
        assertEquals("TENSOR-G5", NpuSupport.normalizeSoc("Tensor G5"))
        assertEquals("TENSOR-G4", NpuSupport.normalizeSoc("Google Tensor G4"))
        assertNull(NpuSupport.normalizeSoc("Exynos 2400"))
        assertNull(NpuSupport.normalizeSoc(""))
        assertNull(NpuSupport.normalizeSoc(null))
    }

    @Test
    fun `only listed chips on API 31 and arm64 get an NPU`() {
        assertEquals("SM8750", NpuSupport.supportedSoc("SM8750", 36, "arm64-v8a"))
        assertEquals("SM8850", NpuSupport.supportedSoc("SM8850", 31, "arm64-v8a"))
        assertEquals("SM8550", NpuSupport.supportedSoc("SM8550", 36, "arm64-v8a"))
        assertEquals("SM8650", NpuSupport.supportedSoc("SM8650", 36, "arm64-v8a"))
        // A chip whose QNN libraries this build does not carry.
        assertNull(NpuSupport.supportedSoc("SM8450", 36, "arm64-v8a"))
        assertEquals("MT6991", NpuSupport.supportedSoc("MT6991", 36, "arm64-v8a"))
        assertEquals("TENSOR-G5", NpuSupport.supportedSoc("Tensor G5", 36, "arm64-v8a"))
        // A Dimensity with no language model compiled for its APU.
        assertNull(NpuSupport.supportedSoc("MT6877", 36, "arm64-v8a"))
        assertNull(NpuSupport.supportedSoc("Tensor G2", 36, "arm64-v8a"))
        assertNull(NpuSupport.supportedSoc("SM8750", 30, "arm64-v8a"))
        assertNull(NpuSupport.supportedSoc("SM8750", 36, "armeabi-v7a"))
        assertNull(NpuSupport.supportedSoc("SM8750", 36, null))
    }

    @Test
    fun `each chip takes its own HTP generation and never the on-phone compiler`() {
        val sm8750 = NpuSupport.runtimeLibraries("SM8750")
        assertTrue("libQnnHtpV79Skel.so" in sm8750)
        assertTrue("libQnnHtpV79Stub.so" in sm8750)
        assertFalse(sm8750.any { "V81" in it })
        assertTrue("libQnnHtpV81Skel.so" in NpuSupport.runtimeLibraries("SM8850"))
        assertTrue((sm8750 + NpuSupport.runtimeLibraries("SM8850")).none { "Prepare" in it })
        assertEquals(NpuVendor.QUALCOMM.dispatchLibrary, sm8750.last())
        assertTrue("libQnnHtpV75Skel.so" in NpuSupport.runtimeLibraries("SM8650"))
        assertTrue("libQnnHtpV73Skel.so" in NpuSupport.runtimeLibraries("SM8550"))
        assertTrue(NpuSupport.runtimeLibraries("SM8450").isEmpty())
    }

    @Test
    fun `a Dimensity or a Tensor takes its vendor's dispatch library and nothing of Qualcomm's`() {
        assertEquals(listOf("libLiteRtDispatch_MediaTek.so"), NpuSupport.runtimeLibraries("MT6991"))
        assertEquals(listOf("libLiteRtDispatch_GoogleTensor.so"), NpuSupport.runtimeLibraries("TENSOR-G4"))
        assertEquals(listOf("npu-mediatek"), NpuSupport.deliveryGates("MT6989"))
        assertEquals(listOf("npu-tensor"), NpuSupport.deliveryGates("TENSOR-G6"))
        assertEquals(listOf("npu", "npu-V73"), NpuSupport.deliveryGates("SM8550"))
        assertEquals(NpuVendor.QUALCOMM, NpuSupport.vendor("SM8750"))
        assertEquals(NpuVendor.MEDIATEK, NpuSupport.vendor("MT6993"))
        assertEquals(NpuVendor.GOOGLE_TENSOR, NpuSupport.vendor("TENSOR-G5"))
        assertNull(NpuSupport.vendor("SM8450"))
    }

    @Test
    fun `a file names its chip as a word of its own`() {
        assertTrue(NpuSupport.namesChip("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", "SM8750"))
        assertTrue(NpuSupport.namesChip("gemma3-270m-it-q8.mediatek.mt6991.litertlm", "MT6991"))
        assertTrue(NpuSupport.namesChip("gemma-4-E2B-it_Google_Tensor_G5.litertlm", "TENSOR-G5"))
        assertTrue(NpuSupport.namesChip("Gemma3-1B-IT_int4hadamard_aot_ekv4096_G3.litertlm", "TENSOR-G3"))
        assertFalse(NpuSupport.namesChip("gemma-4-E2B-it_Google_Tensor_G6.litertlm", "TENSOR-G5"))
        assertFalse(NpuSupport.namesChip("gemma3-1b-it-int4.litertlm", "SM8750"))
    }

    @Test
    fun `every catalog NPU build is for a chip this build has a runtime for`() {
        val builds = LocalLlmCatalog.models.flatMap { it.npuBuilds }
        assertTrue(builds.isNotEmpty())
        for (build in builds) {
            assertTrue(build.soc, NpuSupport.runtimeLibraries(build.soc).isNotEmpty())
            assertTrue(build.fileName, NpuSupport.namesChip(build.fileName, build.soc))
            // A build pinned by hash is pinned to a commit, so a new upload cannot move it.
            assertEquals(build.fileName, build.sha256 != null, build.revision != null)
        }
    }

    // --- Which file a download fetches ---

    @Test
    fun `a download for a chip with a build fetches that build`() {
        assertEquals(
            "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm" to 689_291_264L,
            LocalLlmCatalog.downloadTarget(gemma1b, "SM8750"),
        )
        assertEquals(
            "gemma3-270m-it-q8.qualcomm.sm8850.litertlm" to 463_011_840L,
            LocalLlmCatalog.downloadTarget(gemma270m, "SM8850"),
        )
    }

    @Test
    fun `without an NPU, or for a model with no build, the standard file`() {
        assertEquals(gemma1b.fileName to gemma1b.sizeBytes, LocalLlmCatalog.downloadTarget(gemma1b, null))
        assertEquals(qwen.fileName to qwen.sizeBytes, LocalLlmCatalog.downloadTarget(qwen, "SM8750"))
        assertNull(LocalLlmCatalog.npuBuild(gemma1b, "SM8450"))
        assertEquals(
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm",
            LocalLlmCatalog.downloadUrl(gemma1b, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"),
        )
    }

    @Test
    fun `a build in a repository of its own is fetched from there, pinned and checked`() {
        val g4 = LocalLlmCatalog.npuBuild(gemma1b, "TENSOR-G4")!!
        assertEquals(
            "https://huggingface.co/litert-community/Gemma3-1B-IT-Tensor-NPU/resolve/${g4.revision}/${g4.fileName}",
            LocalLlmCatalog.downloadUrl(gemma1b, g4.fileName),
        )
        assertEquals(g4.sha256, LocalLlmDownloadManager.expectedSha256(gemma1b, g4.fileName))
        // An unpinned build in the model's own repository stays unchecked.
        assertNull(LocalLlmDownloadManager.expectedSha256(gemma1b, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
    }

    // --- What is on disk ---

    @Test
    fun `an NPU build alone counts as downloaded and is what selection hands out`() {
        val filesDir = tmp.root
        val npu = materialize(LocalLlmStore.modelFile(filesDir, gemma1b, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertTrue(LocalLlmStore.isDownloaded(filesDir, gemma1b))
        assertFalse(LocalLlmStore.hasStandard(filesDir, gemma1b))
        assertEquals(npu, LocalLlmStore.selectedModelFile(filesDir, gemma1b.id))
        assertEquals(npu, LocalLlmStore.npuFile(filesDir, gemma1b, "SM8750"))
        assertNull(LocalLlmStore.npuFile(filesDir, gemma1b, "SM8850"))
    }

    @Test
    fun `with both on disk selection hands out the standard file`() {
        val filesDir = tmp.root
        materialize(LocalLlmStore.modelFile(filesDir, gemma1b, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        val standard = materialize(LocalLlmStore.modelFile(filesDir, gemma1b))
        assertEquals(standard, LocalLlmStore.selectedModelFile(filesDir, gemma1b.id))
    }

    // --- What the engine runs ---

    @Test
    fun `both builds on disk, the NPU build first and the standard one behind it`() {
        val filesDir = tmp.root
        val npu = materialize(LocalLlmStore.modelFile(filesDir, gemma270m, "gemma3-270m-it-q8.qualcomm.sm8750.litertlm"))
        val standard = materialize(LocalLlmStore.modelFile(filesDir, gemma270m))
        assertEquals(NpuRouting.Plan(npu = npu, standard = standard), NpuRouting.plan(standard, "SM8750"))
        // Handed the NPU build, it still finds the standard file to fall back to.
        assertEquals(NpuRouting.Plan(npu = npu, standard = standard), NpuRouting.plan(npu, "SM8750"))
    }

    @Test
    fun `no NPU on this phone leaves only the standard file`() {
        val filesDir = tmp.root
        materialize(LocalLlmStore.modelFile(filesDir, gemma270m, "gemma3-270m-it-q8.qualcomm.sm8750.litertlm"))
        val standard = materialize(LocalLlmStore.modelFile(filesDir, gemma270m))
        assertEquals(NpuRouting.Plan(npu = null, standard = standard), NpuRouting.plan(standard, null))
    }

    @Test
    fun `a build for another chip is not this phone's`() {
        val filesDir = tmp.root
        val other = materialize(LocalLlmStore.modelFile(filesDir, gemma270m, "gemma3-270m-it-q8.qualcomm.sm8850.litertlm"))
        assertEquals(NpuRouting.Plan(npu = null, standard = null), NpuRouting.plan(other, "SM8750"))
    }

    @Test
    fun `only the NPU build on disk leaves nothing to fall back to`() {
        val filesDir = tmp.root
        val npu = materialize(LocalLlmStore.modelFile(filesDir, gemma1b, "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertEquals(NpuRouting.Plan(npu = npu, standard = null), NpuRouting.plan(npu, "SM8750"))
    }

    @Test
    fun `a model with no NPU build stays on its standard file`() {
        val filesDir = tmp.root
        val standard = materialize(LocalLlmStore.modelFile(filesDir, qwen))
        assertEquals(NpuRouting.Plan(npu = null, standard = standard), NpuRouting.plan(standard, "SM8750"))
    }

    @Test
    fun `an imported file is an NPU build only when its name says which chip`() {
        val custom = LocalLlmStore.customDir(tmp.root)
        val npuNamed = materialize(File(custom, "my-gemma.qualcomm.sm8750.litertlm"))
        val plain = materialize(File(custom, "my-gemma.litertlm"))
        assertEquals(NpuRouting.Plan(npu = npuNamed, standard = null), NpuRouting.plan(npuNamed, "SM8750"))
        assertEquals(NpuRouting.Plan(npu = null, standard = plain), NpuRouting.plan(plain, "SM8750"))
        // Without an NPU on the phone, a file is only ever a standard one.
        assertEquals(NpuRouting.Plan(npu = null, standard = npuNamed), NpuRouting.plan(npuNamed, null))
    }
}
