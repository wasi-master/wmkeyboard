package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.voice.moonshine.MoonshineEngine
import com.wasimaster.wmkeyboard.core.voice.parakeet.ParakeetNpu
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ExecuTorch NPU builds: the catalog's pending gate, and the padding the fixed-shape encoders need. */
class EtNpuModelsTest {

    @Test
    fun `an export without its size and hash is never in the catalog`() {
        val listed = EtCatalog.models.map { it.id }.toSet()
        for (encoder in EtNpuCatalog.EXPORTS) {
            val pending = encoder.export in EtNpuCatalog.PENDING_EXPORTS ||
                (encoder.engine == EtEngine.PARAKEET && EtNpuCatalog.PARAKEET_AUX in EtNpuCatalog.PENDING_EXPORTS)
            val id = (if (encoder.engine == EtEngine.PARAKEET) "parakeet-v3" else "moonshine-tiny") +
                "-et-${encoder.delegate}-${encoder.soc.lowercase()}-${encoder.seconds}s"
            assertEquals(id, !pending, id in listed)
        }
        assertEquals(EtNpuCatalog.models.size, EtCatalog.models.count { it.etNpu != null })
    }

    @Test
    fun `every export has a name of its own and a chip its delegate serves`() {
        val names = EtNpuCatalog.EXPORTS.map { it.export.name } + EtNpuCatalog.PARAKEET_AUX.name
        assertEquals(names.size, names.toSet().size)
        for (e in EtNpuCatalog.EXPORTS) {
            when (e.delegate) {
                EtNpu.QNN -> assertTrue(e.soc, e.soc.startsWith("SM"))
                EtNpu.NEURON -> assertTrue(e.soc, e.soc in setOf("MT6989", "MT6991"))
                else -> error("unknown delegate ${e.delegate}")
            }
        }
    }

    @Test
    fun `a listed NPU build has a CPU twin and outranks it, below sherpa-onnx's build`() {
        for (m in EtNpuCatalog.models) {
            val twin = EtCatalog.byId(m.cpuTwin!!)!!
            assertTrue(m.outranks(twin))
            assertFalse(m.sherpaNpu)
        }
        val sherpa = EtCatalog.models.first { it.sherpaNpu && it.cpuTwin == "parakeet-v3" }
        val et = EtModel(
            "x", EtEngine.PARAKEET, "x", "x", 600, "r", "v", emptyList(),
            cpuTwin = "parakeet-v3", npu = sherpa.npu, npuSeconds = sherpa.npuSeconds, etNpu = EtNpu.QNN,
        )
        assertTrue(sherpa.outranks(et))
        assertTrue(et.outranks(EtCatalog.byId("parakeet-v3")!!))
    }

    @Test
    fun `a mel a frame short or long is fitted to the encoder's frames`() {
        // Two bins of three frames: [1 2 3] and [4 5 6].
        val mel = Tensor(longArrayOf(1, 2, 3), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 0f, 4f, 5f, 6f, 0f), ParakeetNpu.fit(mel, 4).floats, 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f, 5f), ParakeetNpu.fit(mel, 2).floats, 0f)
        assertEquals(mel, ParakeetNpu.fit(mel, 3))
    }

    /** Records what the preprocessor and encoder were given; the joint says blank, so nothing is written. */
    private class FakeNpuParakeet : EtRunner {
        var audio = 0
        var encoderFrames = 0L
        private val state = TensorSpec(DType.FLOAT, longArrayOf(2, 1, 4))

        override fun shape(method: String): MethodShape? = when (method) {
            "encoder" -> MethodShape(listOf(TensorSpec(DType.FLOAT, longArrayOf(1, 128, 1001))), emptyList())
            "decoder_step" -> MethodShape(listOf(TensorSpec(DType.LONG, longArrayOf(1, 1)), state, state), emptyList())
            else -> null
        }

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> = when (method) {
            "preprocessor" -> {
                audio = inputs[0].floats.size
                listOf(Tensor(longArrayOf(1, 128, 1000), FloatArray(128 * 1000)), Tensor.long(1000))
            }
            "encoder" -> {
                encoderFrames = inputs[0].shape[2]
                listOf(Tensor(longArrayOf(1, 125, 4), FloatArray(500)))
            }
            "decoder_step" -> listOf(Tensor(longArrayOf(1, 1, 4), FloatArray(4)), Tensor.zeros(state), Tensor.zeros(state))
            else -> listOf(Tensor.long(8192), Tensor.long(1))
        }
    }

    @Test
    fun `parakeet's npu decode pads the clip to the bucket and the mel to the encoder`() {
        val runner = FakeNpuParakeet()
        assertEquals(emptyList<Int>(), ParakeetNpu.decode(runner, FloatArray(16_000), 160_000, 8192))
        assertEquals(160_000, runner.audio)
        assertEquals(1001L, runner.encoderFrames)
    }

    @Test
    fun `moonshine's npu decode pads the audio but budgets tokens by the clip`() {
        var given = 0
        val encoder = object : EtRunner {
            override fun shape(method: String) =
                MethodShape(listOf(TensorSpec(DType.FLOAT, longArrayOf(1, 160_000))), emptyList())

            override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
                given = inputs[0].floats.size
                return listOf(Tensor(longArrayOf(1, 10, 288), FloatArray(2880)))
            }
        }
        // A decoder that never ends: the budget for one second is ceil(6.5) + 2 = 9 tokens, 8 after the start.
        val decoder = object : EtRunner {
            override fun shape(method: String) = MethodShape(
                listOf(TensorSpec(DType.LONG, longArrayOf(1, 178)), TensorSpec(DType.FLOAT, longArrayOf(1, 1248, 288))),
                emptyList(),
            )

            override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
                val tokens = inputs[0].longs
                return listOf(Tensor(longArrayOf(1, tokens.size + 1L), tokens + 10L))
            }
        }
        assertEquals(8, MoonshineEngine.decode(encoder, decoder, FloatArray(16_000), padded = true).size)
        assertEquals(160_000, given)
    }
}
