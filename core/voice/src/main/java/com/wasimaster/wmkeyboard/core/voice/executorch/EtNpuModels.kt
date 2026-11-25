package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File

/**
 * Parakeet and Moonshine on an NPU through ExecuTorch's own delegates: the
 * Qualcomm build ([EtNative.qnn], `libwmexecutorch_qnn`) on a Snapdragon
 * with a QNN runtime, and the NeuroPilot build (`libwmexecutorch_neuron`) on
 * a Dimensity 9300 or 9400. Their models ([EtModel.etNpu]) come from
 * [EtNpuCatalog].
 *
 * A build that cannot run in this process right now (its library not the
 * one loaded, its runtime not here yet) gives way to the CPU model of the
 * same weights ([EtModel.cpuTwin]); each missing part is asked for, so a
 * later dictation has it. One that loaded and then failed, to open or to
 * run, is remembered for this phone and system build ([markFailed]) and
 * not tried again until the system is updated or the model downloaded again.
 */
object EtNpu {

    /** [EtModel.etNpu] of a program exported for Qualcomm's QNN delegate. */
    const val QNN = "qnn"

    /** [EtModel.etNpu] of a program exported for MediaTek's NeuroPilot delegate. */
    const val NEURON = "neuron"

    private const val VERDICT_FILE = "npu-failed.txt"

    /** Whether [model] belongs in this phone's model list: its chip, in a build that can have its library. */
    fun offered(model: EtModel): Boolean = when (model.etNpu) {
        QNN -> model.npu == EtNative.qnnSoc() && EtNative.qnnShipped
        NEURON -> model.npu == EtNative.neuronSoc() && EtNative.neuronShipped
        else -> false
    }

    /** Asks for the library [model] needs, ahead of its first dictation. Idempotent. */
    fun request(model: EtModel) {
        when (model.etNpu) {
            QNN -> EtNative.requestQnn()
            NEURON -> EtNative.requestNeuron()
        }
    }

    /**
     * Whether [model] can run in this process now: its delegate's build the
     * one loaded (it loads with the keyboard, so one fetched later waits for
     * the keyboard to restart), for QNN the chip's runtime here too, and no
     * failure remembered for it. Asks for whatever is missing. Loads the
     * ExecuTorch library and may unpack the QNN runtime, so never on the
     * main thread.
     */
    fun runsNow(filesDir: File, model: EtModel): Boolean {
        if (failed(filesDir, model)) return false
        val delegate = model.etNpu ?: return true
        if (EtNative.delegate != delegate) {
            request(model)
            return false
        }
        // A runtime still on its way is not a failure, so it is checked
        // here rather than left to throw where the programs open.
        return delegate != QNN || EtNative.qnnRuntime() != null
    }

    /**
     * The programs of [model] ([files], in order), opened with its delegate
     * readied first. On [EtNative.dispatcher] only. Throws when they will
     * not load.
     */
    fun programs(model: EtModel, files: List<File>): List<EtNative.Program> =
        EtNative.programs(files) {
            if (model.etNpu == QNN) check(EtNative.prepareQnn()) { "the QNN runtime for ${model.npu} is not loaded" }
        }

    /** Whether [model] failed on this phone and system build before. */
    fun failed(filesDir: File, model: EtModel): Boolean =
        runCatching { File(EtStore.dir(filesDir, model), VERDICT_FILE).readLines().firstOrNull() }
            .getOrNull() == stamp()

    /** Remembers that [model] failed here, with why, so the CPU build is used from now on. */
    fun markFailed(filesDir: File, model: EtModel, error: Throwable) {
        runCatching {
            File(EtStore.dir(filesDir, model), VERDICT_FILE)
                .writeText(stamp() + "\n" + error.javaClass.simpleName + ": " + error.message.orEmpty() + "\n")
        }
    }

    /** The system build the verdict holds for: a system update brings new NPU drivers, so it is tried again. */
    private fun stamp(): String = android.os.Build.FINGERPRINT.orEmpty()

    /**
     * [npu] for a model of this kind, and when it throws, the failure is
     * remembered ([markFailed]), the programs closed, and [cpu] runs with
     * [model]'s CPU twin when that is downloaded; without one, the failure
     * is thrown.
     */
    @Suppress("detekt.TooGenericExceptionCaught")
    inline fun <T> orCpuTwin(filesDir: File, model: EtModel, npu: () -> T, cpu: (EtModel) -> T): T {
        val failure = try {
            return npu()
        } catch (e: Exception) {
            e
        }
        markFailed(filesDir, model, failure)
        EtNative.release()
        val twin = model.cpuTwin?.let(EtCatalog::byId)?.takeIf { EtStore.isDownloaded(filesDir, it) } ?: throw failure
        return cpu(twin)
    }
}

/**
 * The ExecuTorch NPU builds of Parakeet TDT 0.6B v3 and Moonshine Tiny,
 * made by `tools/npu-speech/export_npu.py` (Actions › NPU speech export)
 * with the ExecuTorch release the app's runtime is built from. Only the
 * encoder is compiled for the NPU, for one clip length; the small parts run
 * on the CPU beside it:
 * - Parakeet: the encoder for the chip, plus one CPU program shared by every
 *   chip ([PARAKEET_AUX]: preprocessor, decoder step, joint) and v3's
 *   tokenizer.
 * - Moonshine: the encoder for the chip, plus the XNNPACK decoder and
 *   tokenizer of the CPU build, Software Mansion's.
 *
 * Published as assets of the data repository's `models` release, named
 * `<sha256>-<name>`. Nothing is published yet: an export whose size or
 * hash is still missing is in [PENDING_EXPORTS] and stays out of [models],
 * so the list never offers a file that is not there.
 */
object EtNpuCatalog {

    /** One file of the release, with its size and SHA-256 once it is uploaded. */
    class Export(
        val name: String,
        val bytes: Long?,
        val sha256: String?,
    ) {
        val ready: Boolean get() = bytes != null && sha256 != null

        /** The file as a model holds it, under [localName]; only for one that is [ready]. */
        fun file(localName: String): EtFile {
            val size = checkNotNull(bytes) { "$name is not uploaded yet" }
            val hash = checkNotNull(sha256) { "$name is not uploaded yet" }
            return EtFile(localName, size, hash, url = "$RELEASE/$hash-$name")
        }
    }

    /** One encoder: [engine]'s, for [soc]'s NPU through [delegate], compiled for clips of [seconds]. */
    class Encoder(
        val engine: EtEngine,
        val delegate: String,
        val soc: String,
        val seconds: Int,
        val export: Export,
    )

    private const val RELEASE = "https://github.com/wasi-master/wmkeyboard-data/releases/download/models"

    /** Parakeet's CPU parts, the same for every chip. */
    val PARAKEET_AUX = Export("parakeet-tdt-0.6b-v3-aux-xnnpack.pte", null, null)

    private fun parakeet(delegate: String, soc: String, seconds: Int, bytes: Long? = null, sha256: String? = null) =
        Encoder(
            EtEngine.PARAKEET, delegate, soc, seconds,
            Export("parakeet-tdt-0.6b-v3-encoder-$delegate-${soc.lowercase()}-${seconds}s.pte", bytes, sha256),
        )

    private fun moonshine(delegate: String, soc: String, seconds: Int, bytes: Long? = null, sha256: String? = null) =
        Encoder(
            EtEngine.MOONSHINE, delegate, soc, seconds,
            Export("moonshine-tiny-encoder-$delegate-${soc.lowercase()}-${seconds}s.pte", bytes, sha256),
        )

    /**
     * Every export the list will offer once uploaded. Fill `bytes` and
     * `sha256` from the export's manifest when its file is on the release.
     */
    val EXPORTS: List<Encoder> = listOf(
        // Dimensity 9300 and 9400: the NPU dictation these phones have no other way to.
        parakeet(EtNpu.NEURON, "MT6989", 10),
        parakeet(EtNpu.NEURON, "MT6989", 30),
        parakeet(EtNpu.NEURON, "MT6991", 10),
        parakeet(EtNpu.NEURON, "MT6991", 30),
        moonshine(EtNpu.NEURON, "MT6989", 10),
        moonshine(EtNpu.NEURON, "MT6991", 10),
        // Snapdragon 8 Gen 2 to 8 Elite Gen 5, beside sherpa-onnx's QNN builds of the same weights.
        parakeet(EtNpu.QNN, "SM8550", 10),
        parakeet(EtNpu.QNN, "SM8550", 30),
        parakeet(EtNpu.QNN, "SM8650", 10),
        parakeet(EtNpu.QNN, "SM8650", 30),
        parakeet(EtNpu.QNN, "SM8750", 10),
        parakeet(EtNpu.QNN, "SM8750", 30),
        parakeet(EtNpu.QNN, "SM8850", 10),
        parakeet(EtNpu.QNN, "SM8850", 30),
        moonshine(EtNpu.QNN, "SM8550", 10),
        moonshine(EtNpu.QNN, "SM8650", 10),
        moonshine(EtNpu.QNN, "SM8750", 10),
        moonshine(EtNpu.QNN, "SM8850", 10),
    )

    private fun usable(encoder: Encoder): Boolean =
        encoder.export.ready && (encoder.engine != EtEngine.PARAKEET || PARAKEET_AUX.ready)

    /** What is still to be exported and uploaded: hidden from the model list until filled in. */
    val PENDING_EXPORTS: List<Export>
        get() = (listOf(PARAKEET_AUX) + EXPORTS.map { it.export }).filter { !it.ready }

    /** v3's tokenizer, the bytes the CPU build downloads, from the same repo. */
    private val PARAKEET_TOKENIZER = EtFile(
        "xnnpack/int4/tokenizer.model", 360_916L,
        "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
        from = EtSource("larryliu0820/parakeet-tdt-0.6b-v3-executorch", "17c1a8fd222fcab46c4053fc230363d20d6ee572"),
    )

    private val MOONSHINE_CPU = EtSource(
        "software-mansion/react-native-executorch-moonshine-tiny", "6af58e7ab467f655d034eedc7acc756c1ff9b2c2",
    )

    /** Moonshine's tokenizer and XNNPACK decoder, the CPU build's own files. */
    private val MOONSHINE_SHARED = listOf(
        EtFile(
            "moonshine_tiny_tokenizer.json", 1_985_530L,
            "6579793438bc4fbafffacf699169ff53e3769c5a0a0f5e71cdee8853e8130deb", from = MOONSHINE_CPU,
        ),
        EtFile(
            "xnnpack/moonshine_tiny_xnnpack_decoder.pte", 118_008_224L,
            "89481cfa1391e9962d8ff710684a80d0900d8d39cbf34764e5f28ed8c0f9b9b0", from = MOONSHINE_CPU,
        ),
    )

    /** The local name of Parakeet's CPU program and of every NPU encoder. */
    const val AUX_FILE = "parakeet_aux.pte"
    const val ENCODER_FILE = "npu_encoder.pte"

    private fun model(encoder: Encoder): EtModel {
        val tag = "et-${encoder.delegate}-${encoder.soc.lowercase()}-${encoder.seconds}s"
        val label = "(NPU, ExecuTorch, ${encoder.seconds} s)"
        return when (encoder.engine) {
            EtEngine.PARAKEET -> EtModel(
                "parakeet-v3-$tag", EtEngine.PARAKEET, "tdt-0.6b-v3-$tag", "Parakeet TDT 0.6B v3 $label", 600,
                "github.com/wasi-master/wmkeyboard-data", "models",
                // Small files first: a bad connection fails before the big one.
                listOf(PARAKEET_TOKENIZER, PARAKEET_AUX.file(AUX_FILE), encoder.export.file(ENCODER_FILE)),
                cpuTwin = "parakeet-v3",
                npu = encoder.soc,
                npuSeconds = encoder.seconds,
                etNpu = encoder.delegate,
            )
            else -> EtModel(
                "moonshine-tiny-$tag", EtEngine.MOONSHINE, "tiny-$tag", "Moonshine Tiny $label", 27,
                "github.com/wasi-master/wmkeyboard-data", "models",
                listOf(encoder.export.file(ENCODER_FILE)) + MOONSHINE_SHARED,
                cpuTwin = "moonshine-tiny",
                npu = encoder.soc,
                npuSeconds = encoder.seconds,
                etNpu = encoder.delegate,
            )
        }
    }

    /** The exports that are published, as catalog models; [EtCatalog.models] holds them too. */
    val models: List<EtModel> = EXPORTS.filter(::usable).map(::model)
}
