package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the NPU builds that need no NPU: which bucket a clip goes to,
 * where a long clip is cut, and how the catalog ranks and offers them. The
 * binaries themselves were never run here; see native/sherpa-onnx-jni.
 */
class QnnSpeechTest {

    private val v3 = EtCatalog.byId("parakeet-v3")!!
    private val ultra = EtCatalog.byId("parakeet-ultra")!!
    private val ten = EtCatalog.byId("parakeet-v3-npu-sm8750-10s")!!
    private val thirty = EtCatalog.byId("parakeet-v3-npu-sm8750-30s")!!

    @Test
    fun `a clip goes to the shortest bucket that holds it, the longest when none does`() {
        val buckets = listOf(ten, thirty)
        assertEquals(ten, QnnSpeech.pick(buckets, null, 16_000 * 4))
        assertEquals(ten, QnnSpeech.pick(buckets, null, ten.npuSamples))
        assertEquals(thirty, QnnSpeech.pick(buckets, null, ten.npuSamples + 1))
        assertEquals(thirty, QnnSpeech.pick(buckets, null, 16_000 * 45))
        assertEquals(ten, QnnSpeech.pick(listOf(ten), null, 16_000 * 45))
    }

    @Test
    fun `the open bucket is kept while it holds the clip, so a dictation does not reload`() {
        val buckets = listOf(ten, thirty)
        assertEquals(thirty, QnnSpeech.pick(buckets, thirty, 16_000 * 4))
        assertEquals(thirty, QnnSpeech.pick(buckets, ten, 16_000 * 12))
        // Another model's bucket is never kept.
        val moonshine = EtCatalog.byId("moonshine-tiny-npu-sm8750-10s")!!
        assertEquals(ten, QnnSpeech.pick(buckets, moonshine, 16_000 * 4))
    }

    @Test
    fun `a clip that fits is one piece, a longer one is cut in its quietest stretch`() {
        val max = 16_000 * 10
        assertEquals(listOf(0 until 5_000), QnnSpeech.cuts(FloatArray(5_000) { 0.5f }, 5_000, max))
        assertEquals(listOf(0 until max), QnnSpeech.cuts(FloatArray(max) { 0.5f }, max, max))

        // 25 s of loud audio with a pause from 8.5 s to 8.6 s.
        val pcm = FloatArray(16_000 * 25) { 0.5f }
        for (i in 136_000 until 137_600) pcm[i] = 0f
        val pieces = QnnSpeech.cuts(pcm, pcm.size, max)
        assertTrue(pieces.all { it.last - it.first + 1 <= max })
        assertEquals(0, pieces.first().first)
        assertEquals(pcm.size - 1, pieces.last().last)
        for ((a, b) in pieces.zipWithNext()) assertEquals(a.last + 1, b.first)
        val firstCut = pieces[0].last + 1
        assertTrue("cut at $firstCut", firstCut in 136_000..137_600)
    }

    @Test
    fun `cuts never pass the samples asked for`() {
        val pieces = QnnSpeech.cuts(FloatArray(100_000), 40_000, 16_000)
        assertEquals(39_999, pieces.last().last)
        assertTrue(pieces.all { it.last - it.first + 1 <= 16_000 })
    }

    @Test
    fun `an NPU build outranks every CPU build the same size and names its twin`() {
        assertTrue(ten.outranks(v3))
        assertTrue(ten.outranks(ultra))
        assertEquals(ten, listOf(ultra, ten, v3).maxWithOrNull(EtModel.ORDER))
        // Of two buckets, the shorter one is what a stray pick lands on.
        assertEquals(ten, listOf(thirty, ten).maxWithOrNull(EtModel.ORDER))
        assertEquals(v3.id, ten.cpuTwin)
        assertEquals("moonshine-tiny", EtCatalog.byId("moonshine-tiny-npu-sm8650-10s")!!.cpuTwin)
    }

    @Test
    fun `every NPU build is one pinned tar from k2-fsa's releases, in a folder of its own`() {
        val npu = EtCatalog.models.filter { it.npu != null }
        assertEquals(9, npu.size)
        for (m in npu) {
            val f = m.files.single()
            assertTrue(m.id, f.archive && f.name.endsWith(".tar.bz2") && f.sha256.length == 64)
            assertTrue(m.id, m.url(f).startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary-"))
            assertTrue(m.id, m.npuSeconds == 10 || m.npuSeconds == 30)
            assertNotNull(m.cpuTwin?.let(EtCatalog::byId))
        }
        for (engine in listOf(EtEngine.PARAKEET, EtEngine.MOONSHINE)) {
            val dirs = EtCatalog.models.filter { it.engine == engine }.map { it.dirName }
            assertEquals(dirs.size, dirs.toSet().size)
        }
    }

    @Test
    fun `no chip in a unit test, so no NPU build is offered`() {
        assertTrue(EtCatalog.forEngine(EtEngine.PARAKEET).none { it.npu != null })
        assertTrue(EtCatalog.forEngine(EtEngine.MOONSHINE).none { it.npu != null })
    }
}
