package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.voice.sherpa.OmnilingualLanguages
import com.wasimaster.wmkeyboard.core.voice.sherpa.Qwen3Languages
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaLanguages
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaQnnNative
import com.wasimaster.wmkeyboard.core.voice.vosk.VoskNative
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dictation engines whose models are downloaded file by file from
 * Hugging Face: the ones that run on [EtNative], and Sherpa, which runs on
 * ONNX Runtime ([SherpaNative]) but keeps its models the same way. Vosk
 * ([VoskNative]) keeps its models here too, each one zip from alphacephei.com. [id] is the
 * value the Recognition engine setting stores; [dirName] is the folder under
 * `filesDir`. [languages] are the base language codes some model of the
 * engine transcribes; each [EtModel] can narrow that.
 */
enum class EtEngine(val id: String, val dirName: String, val languages: Set<String>) {
    ASR4ALL("asr4all", "asr4all", setOf("en")),

    /** Parakeet TDT v3: 25 European languages, which it tells apart itself. */
    PARAKEET(
        "parakeet", "parakeet",
        setOf(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
            "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
        ),
    ),
    MOONSHINE("moonshine", "moonshine", setOf("en")),

    /**
     * Sherpa: k2-fsa's sherpa-onnx. Streaming transducers (Zipformers, one
     * per language or a few, and NVIDIA's Nemotron for 28 languages at once),
     * decoded while the user speaks, with Silero to hear the end of a phrase;
     * and models decoded a phrase at a time ([SherpaKind]: Dolphin's 40
     * Eastern languages, Moonshine's other languages, SenseVoice and a few
     * single-language ones), with Silero cutting the phrases.
     */
    SHERPA("sherpa", "sherpa", SherpaLanguages.codes),

    /**
     * Meta's Omnilingual ASR, 300M CTC, on the same sherpa-onnx runtime: one
     * model for 1,600-odd languages, every keyboard language among them
     * listed in [OmnilingualLanguages]. It decodes a whole phrase at a time,
     * so Silero cuts the audio at each pause and the phrase before it goes in.
     */
    OMNILINGUAL("omnilingual", "omnilingual", OmnilingualLanguages.codes),

    /**
     * Alibaba's Qwen3-ASR (#1437) on the same sherpa-onnx runtime: an audio
     * encoder in front of a Qwen3 language model, 30 languages and 22 Chinese
     * dialects in one model, with punctuation of its own. Like Omnilingual it
     * decodes a whole phrase at a time, cut at each pause by Silero.
     */
    QWEN3("qwen3", "qwen3", Qwen3Languages.codes),

    /**
     * Vosk (#1165): Alpha Cephei's small Kaldi models on their own library
     * (the `vosk` gate), one per language, each downloaded as one zip from
     * alphacephei.com and decoded while the user speaks.
     */
    VOSK(
        "vosk", "vosk",
        setOf(
            "ar", "ca", "zh", "cs", "de", "en", "eo", "es", "fa", "fr", "gu", "hi", "it", "ja", "ka", "ko",
            "ky", "kk", "nl", "pl", "pt", "ru", "sv", "te", "tg", "tr", "uk", "uz", "vi",
        ),
    ),
    ;

    /** Whether the engine runs on ONNX Runtime (the `onnx` gate) rather than ExecuTorch. */
    val onnx: Boolean get() = this == SHERPA || this == OMNILINGUAL || this == QWEN3

    /** Whether the user can bring models of their own to it ([EtCustomModels]). */
    val takesCustomModels: Boolean get() = this == SHERPA || this == QWEN3

    /** Whether this build has the runtime the engine needs, counting a module not fetched yet. */
    val runtimeShipped: Boolean
        get() = when {
            onnx -> SherpaNative.shipped
            this == VOSK -> VoskNative.shipped
            else -> EtNative.shipped
        }

    /**
     * Whether the engine decodes while the user speaks rather than when the
     * clip ends. Omnilingual and Qwen3-ASR count: they decode each phrase at
     * the pause after it. So does Parakeet (#1168), which passes over the clip
     * as it grows.
     */
    val live: Boolean
        get() = this == ASR4ALL || this == SHERPA || this == OMNILINGUAL || this == QWEN3 || this == VOSK ||
            this == PARAKEET

    /** The one thread the engine's model calls run on. */
    val dispatcher: CoroutineDispatcher
        get() = when {
            onnx -> SherpaNative.dispatcher
            this == VOSK -> VoskNative.dispatcher
            else -> EtNative.dispatcher
        }

    /**
     * Whether [languageId] (a keyboard language id such as `en_GB` or `de`) is
     * one this engine transcribes on this phone: one of [languages], one a
     * custom model brings, or one only an NPU build offered on this phone's
     * chip brings (Parakeet's Japanese, on a Snapdragon with a build for it).
     */
    fun supports(languageId: String): Boolean =
        languageId.trim().lowercase().substringBefore('-').substringBefore('_') in languages ||
            (takesCustomModels && EtCustomModels.models.any { it.engine == this && it.supports(languageId) }) ||
            EtCatalog.forEngine(this).any { it.npu != null && it.supports(languageId) }

    companion object {
        fun byId(id: String): EtEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How sherpa-onnx runs a Sherpa model. [STREAMING] decodes while the user
 * speaks (a transducer, or a Paraformer when there is no joiner); the others
 * are its offline families, each phrase decoded whole once Silero hears the
 * pause after it. [native] is the family's number for [SherpaNative.nativeOffline].
 */
enum class SherpaKind(val native: Int) {
    STREAMING(-1),

    /** DataoceanAI's Dolphin: model.int8.onnx. */
    DOLPHIN(SherpaNative.OFFLINE_DOLPHIN),

    /** Moonshine v2: encoder_model.ort and decoder_model_merged.ort. */
    MOONSHINE(SherpaNative.OFFLINE_MOONSHINE),

    /** SenseVoice: one model, told the language. */
    SENSE_VOICE(SherpaNative.OFFLINE_SENSE_VOICE),
    ZIPFORMER_CTC(SherpaNative.OFFLINE_ZIPFORMER_CTC),
    WENET_CTC(SherpaNative.OFFLINE_WENET_CTC),

    /** NeMo CTC, GigaAM among them. */
    NEMO_CTC(SherpaNative.OFFLINE_NEMO_CTC),

    /** An offline transducer: encoder, decoder and joiner. */
    TRANSDUCER(SherpaNative.OFFLINE_TRANSDUCER),
    ;

    val offline: Boolean get() = this != STREAMING
}

/** A Hugging Face repo at a pinned revision. */
class EtSource(val repo: String, val revision: String)

/**
 * One file of a model: where it lives in the repo, and what it must be.
 * [from] names another repo for a file the model's own repo does not have,
 * such as the Silero VAD every Sherpa model carries. [url] is a whole address
 * off Hugging Face (Vosk's models, on alphacephei.com). An [archive] is a zip
 * of the model, unpacked into its folder once it is checked ([EtArchive]).
 */
class EtFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val from: EtSource? = null,
    val url: String? = null,
    val archive: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * One downloadable model, pinned to a repo revision so no file can change
 * under its hash. [label] is the model's own name, never translated.
 */
class EtModel(
    val id: String,
    val engine: EtEngine,
    /** Folder under the engine's folder. Asr4All's were "s", "m" and "l" before there was this class. */
    val dirName: String,
    val label: String,
    /** Parameters, in millions; the largest one downloaded is the one used. */
    val millions: Int,
    val repo: String,
    val revision: String,
    val files: List<EtFile>,
    /** The languages this model transcribes, when narrower than its engine's. */
    val languages: Set<String>? = null,
    /**
     * A helper rather than a dictation model: Sherpa's punctuation model.
     * Never a choice of its own; kept on the device, it punctuates.
     */
    val punctuation: Boolean = false,
    /**
     * Whether what it writes carries marks and capitals of its own. Sherpa's
     * transducers mostly spell bare words, and Omnilingual always does;
     * those get [com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation]'s.
     * Read from each model's tokens.txt: a model whose vocabulary has no
     * full stop cannot write one.
     */
    val writesPunctuation: Boolean = engine != EtEngine.SHERPA && engine != EtEngine.OMNILINGUAL && engine != EtEngine.VOSK,
    /**
     * The id of a model of the same size this one improves on, such as a
     * post-trained copy of it: kept side by side, this one is used.
     */
    val supersedes: String? = null,
    /**
     * A program exported for ExecuTorch's Vulkan delegate: it runs on the
     * GPU, and only once `libwmexecutorch_vulkan` is the build loaded
     * ([EtNative.delegate]). Offered only on a phone with Vulkan 1.1, and
     * outranked by a CPU model of the same size, so it is used only when the
     * user keeps it alone or picks it: on a Mali-G57 it ran 2.2-2.7 times
     * slower than the CPU build.
     */
    val gpu: Boolean = false,
    /**
     * For a [gpu] or [npu] model, the id of the CPU model of the same weights,
     * used while the GPU or NPU build cannot run.
     */
    val cpuTwin: String? = null,
    /**
     * The Snapdragon chip (`SM8750`) a set of QNN context binaries was
     * compiled for: k2-fsa's ready-made builds, which run on that chip's NPU
     * through sherpa-onnx's QNN library ([SherpaQnnNative], the `sherpaqnn`
     * gate) and nowhere else. Offered only on that chip
     * ([EtNative.qnnSoc]), and it outranks a CPU model of the same size.
     */
    val npu: String? = null,
    /**
     * For an [npu] model, the clip length in seconds its binaries were
     * compiled for: sherpa-onnx pads a shorter clip to it, and
     * [com.wasimaster.wmkeyboard.core.voice.sherpa.QnnSpeech] cuts a longer
     * one into pieces no longer than it.
     */
    val npuSeconds: Int = 0,
    /**
     * For an ExecuTorch program set exported for an NPU delegate rather than
     * k2-fsa's sherpa-onnx binaries: [EtNpu.QNN] (a Snapdragon, through
     * `libwmexecutorch_qnn`) or [EtNpu.NEURON] (a Dimensity 9300 or 9400,
     * through `libwmexecutorch_neuron`), with [npu] naming the chip and
     * [npuSeconds] the clip length its encoder was compiled for. Null for
     * every other model. See [EtNpuCatalog].
     */
    val etNpu: String? = null,
    /**
     * For a Sherpa model, how sherpa-onnx runs it: a streaming model, or one
     * of the offline families it decodes a phrase at a time. Every other
     * engine ignores it.
     */
    val sherpaKind: SherpaKind = SherpaKind.STREAMING,
    /**
     * For a model told the language of each dictation (Nemotron 3.5's
     * prompts, SenseVoice): keyboard base code to the model's own tag
     * ([SherpaLanguages.tagFor]). Null for a model that takes none.
     */
    val languageTags: Map<String, String>? = null,
    /**
     * A model fine-tuned for its [languages]: used for them over any general
     * model on the device, whatever the sizes, since a fine-tune of a small
     * model beats a large one that was never trained hard on the language.
     */
    val specialist: Boolean = false,
    /**
     * Weights under a licence that allows personal use but not every
     * commercial one (Moonshine's Community License for its languages other
     * than English): the model's row says so before it is downloaded.
     */
    val nonCommercial: Boolean = false,
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    /** An NPU build that runs on sherpa-onnx's QNN library ([com.wasimaster.wmkeyboard.core.voice.sherpa.QnnSpeech]). */
    val sherpaNpu: Boolean get() = npu != null && etNpu == null

    /** Whether an engine runs this rather than [other] when both are on the device. */
    fun outranks(other: EtModel): Boolean = ORDER.compare(this, other) > 0

    /** For an [npu] model, the longest piece of audio one decode takes, in 16 kHz samples. */
    val npuSamples: Int get() = npuSeconds * 16_000

    /**
     * For an [npu] model, what every bucket of the same model for the same
     * chip shares: its id without the clip length. Null for any other model.
     */
    val npuFamily: String? get() = npu?.let { id.removeSuffix("-${npuSeconds}s") }

    /** A Sherpa transducer has one; a Paraformer does not, and hears no hotwords. */
    val hasJoiner: Boolean get() = files.any { it.name.startsWith("joiner") }

    fun url(file: EtFile): String {
        file.url?.let { return it }
        val source = file.from
        return if (source != null) {
            "https://huggingface.co/${source.repo}/resolve/${source.revision}/${file.path}"
        } else {
            "https://huggingface.co/$repo/resolve/$revision/${file.path}"
        }
    }

    fun file(name: String): EtFile = files.first { it.name == name }

    /** Whether [languageId] (`en_GB`, `bn`) is one this model transcribes. */
    fun supports(languageId: String): Boolean =
        !punctuation && languageId.trim().lowercase().substringBefore('-').substringBefore('_') in
            (languages ?: engine.languages)

    companion object {
        /**
         * Smallest to largest; of two the same size, a GPU build first (the
         * CPU one wins unless the user picks the GPU one), then a CPU build
         * before an NPU one (a downloaded NPU build is used, being several
         * times faster, even over a post-trained CPU model such as Parakeet
         * Ultra), then of two NPU builds an ExecuTorch one before sherpa-onnx's
         * (which runs the same weights and was there first), then the one
         * superseded first, then the NPU build for the longer clip first.
         */
        val ORDER: Comparator<EtModel> = compareBy<EtModel> { it.millions }
            .thenBy { !it.gpu }
            .thenBy { it.npu != null }
            .thenBy { it.etNpu == null }
            .thenBy { it.supersedes != null }
            .thenByDescending { it.npuSeconds }
    }
}

/** Every model the ExecuTorch engines can download. */
object EtCatalog {

    /** Where k2-fsa publishes sherpa-onnx's ready-made QNN context binaries, each release a folder. */
    private const val QNN_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    /**
     * One set of k2-fsa's QNN context binaries for [soc]'s NPU, compiled for
     * clips of [seconds]: the tar.bz2 `sherpa-onnx-qnn-<soc>-binary-<archive>`
     * from [release], pinned by the size and SHA-256 GitHub lists for it.
     * [id] and [dirName] get the chip and the clip length appended, so every
     * bucket of a model has a folder of its own and they share [EtModel.npuFamily].
     */
    @Suppress("detekt.LongParameterList")
    private fun qnnBuild(
        engine: EtEngine,
        release: String,
        archive: String,
        id: String,
        dirName: String,
        label: String,
        millions: Int,
        soc: String,
        seconds: Int,
        bytes: Long,
        sha256: String,
        cpuTwin: String? = null,
        languages: Set<String>? = null,
        supersedes: String? = null,
    ): EtModel {
        val name = "sherpa-onnx-qnn-$soc-binary-$archive"
        val tag = soc.lowercase()
        return EtModel(
            "$id-npu-$tag-${seconds}s", engine, "$dirName-npu-$tag-${seconds}s",
            "$label (NPU, $seconds s)", millions,
            "github.com/k2-fsa/sherpa-onnx", release,
            listOf(EtFile("$name.tar.bz2", bytes, sha256, url = "$QNN_RELEASES/$release/$name.tar.bz2", archive = true)),
            languages = languages,
            supersedes = supersedes,
            cpuTwin = cpuTwin,
            npu = soc,
            npuSeconds = seconds,
        )
    }

    /**
     * Parakeet TDT 0.6B v3 (release asr-models-qnn-binary-2, built with
     * QAIRT 2.40.0.251030): encoder.bin, decoder.bin, joiner.bin and tokens.txt.
     */
    private fun parakeetNpu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.PARAKEET, "asr-models-qnn-binary-2", "parakeet-tdt-0.6b-v3-${seconds}s-transducer",
        "parakeet-v3", "tdt-0.6b-v3", "Parakeet TDT 0.6B v3", 600, soc, seconds, bytes, sha256,
        cpuTwin = "parakeet-v3",
    )

    /**
     * NVIDIA's Parakeet TDT 0.6B v2 (CC-BY-4.0), English only, the same files
     * from the same release. No CPU build of it here, so no twin. For English
     * it is the more accurate of the two, so it is used over v3's NPU build
     * when both are on the phone; v3 keeps every other language.
     */
    private fun parakeetV2Npu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.PARAKEET, "asr-models-qnn-binary-2", "parakeet-tdt-0.6b-v2-${seconds}s-transducer",
        "parakeet-v2", "tdt-0.6b-v2", "Parakeet TDT 0.6B v2", 600, soc, seconds, bytes, sha256,
        languages = setOf("en"),
        supersedes = "parakeet-v3",
    )

    /** NVIDIA's Parakeet TDT-CTC 110M (CC-BY-4.0), English only, its transducer half; the same files. */
    private fun parakeet110mNpu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.PARAKEET, "asr-models-qnn-binary-2", "parakeet-tdt_ctc-110m-${seconds}s-transducer",
        "parakeet-110m", "tdt-ctc-110m", "Parakeet TDT-CTC 110M", 110, soc, seconds, bytes, sha256,
        languages = setOf("en"),
    )

    /**
     * NVIDIA's Parakeet TDT-CTC 0.6B Japanese (CC-BY-4.0), its transducer
     * half; the same files. Japanese is a Parakeet language only on a phone
     * offered one of these ([EtEngine.supports]).
     */
    private fun parakeetJaNpu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.PARAKEET, "asr-models-qnn-binary-2", "parakeet-tdt_ctc-0.6b-ja-${seconds}s-transducer",
        "parakeet-ja", "tdt-ctc-0.6b-ja", "Parakeet TDT-CTC 0.6B Japanese", 600, soc, seconds, bytes, sha256,
        languages = setOf("ja"),
    )

    /** Moonshine Tiny English, from asr-models-qnn-binary-3: encoder.bin, decoder.bin and tokens.txt. */
    private fun moonshineNpu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.MOONSHINE, "asr-models-qnn-binary-3", "moonshine-tiny-en-${seconds}s",
        "moonshine-tiny", "tiny", "Moonshine Tiny", 27, soc, seconds, bytes, sha256,
        cpuTwin = "moonshine-tiny",
    )

    /** Useful Sensors' Moonshine Base (MIT), English, the same files from the same release. No CPU build of it here. */
    private fun moonshineBaseNpu(soc: String, seconds: Int, bytes: Long, sha256: String): EtModel = qnnBuild(
        EtEngine.MOONSHINE, "asr-models-qnn-binary-3", "moonshine-base-${seconds}s",
        "moonshine-base", "base", "Moonshine Base", 61, soc, seconds, bytes, sha256,
    )

    /** Where Alpha Cephei keeps the Vosk models, each `<name>.zip`. */
    private const val VOSK_MODELS = "https://alphacephei.com/vosk/models"

    /**
     * One of Alpha Cephei's small Vosk models for [language] (#1165): the zip
     * [name] from alphacephei.com, pinned by its SHA-256 (the site's own list
     * gives only MD5, which matched when these were taken). Vosk publishes no
     * parameter counts, so [rank] is the zip's size in MB, except where a
     * regional model must not outrank the general one.
     */
    @Suppress("detekt.LongParameterList")
    private fun vosk(
        dirName: String,
        label: String,
        rank: Int,
        name: String,
        bytes: Long,
        sha256: String,
        language: String,
    ) = EtModel(
        "vosk-$dirName", EtEngine.VOSK, dirName, label, rank, "alphacephei.com/vosk/models", name,
        listOf(EtFile("$name.zip", bytes, sha256, url = "$VOSK_MODELS/$name.zip", archive = true)),
        languages = setOf(language),
    )

    /** Where Addis AI keeps its sherpa-onnx export of the Amharic model, 320 ms chunk, int8. */
    private const val ADDIS_AM_ONNX = "xbec3714bf3b2/asr/onnx/320ms/int8"

    /** The tokens.txt every Moonshine language model but the Spanish one shares. */
    private val MOONSHINE_TOKENS = EtFile("tokens.txt", 549_350L, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049")

    /**
     * One of Moonshine's models for a language other than English, from k2-fsa's
     * `sherpa-onnx-moonshine-<size>-<code>-quantized-2026-02-27` at [revision]:
     * its encoder and merged decoder in ONNX Runtime's own format, decoded by
     * Sherpa a phrase at a time. [nonCommercial], since the Moonshine AI
     * Community License is not an open-source one.
     */
    @Suppress("detekt.LongParameterList")
    private fun moonshineLanguage(
        code: String,
        name: String,
        size: String,
        millions: Int,
        revision: String,
        encoderBytes: Long,
        encoderSha: String,
        decoderBytes: Long,
        decoderSha: String,
        tokens: EtFile = MOONSHINE_TOKENS,
    ) = EtModel(
        "sherpa-moonshine-$size-$code", EtEngine.SHERPA, "moonshine-$size-$code",
        "Moonshine ${size.replaceFirstChar(Char::uppercaseChar)} $name", millions,
        "csukuangfj2/sherpa-onnx-moonshine-$size-$code-quantized-2026-02-27", revision,
        listOf(
            tokens,
            SILERO_VAD,
            EtFile("encoder_model.ort", encoderBytes, encoderSha),
            EtFile("decoder_model_merged.ort", decoderBytes, decoderSha),
        ),
        languages = setOf(code),
        writesPunctuation = true,
        sherpaKind = SherpaKind.MOONSHINE,
        nonCommercial = true,
    )

    private const val ASR4ALL_PTE = "executorch/xnnpack_int8/asr_encoder.pte"
    private const val ASR4ALL_META = "executorch/xnnpack_int8/metadata.json"

    /**
     * Silero VAD v4 (MIT), which hears where a Sherpa phrase ends and cuts
     * Omnilingual's audio into phrases. Kept beside each model rather than
     * once, so deleting a model never breaks another.
     */
    internal val SILERO_VAD = EtFile(
        "silero_vad.onnx", 1_807_522L, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28",
        from = EtSource("csukuangfj/vad", "fba88cd2e921609e7675c3aaf51e0b9b295da4bc"),
    )

    val models: List<EtModel> = listOf(
        // Small files first in each list: a bad connection fails before the big one.
        EtModel(
            "asr4all-s", EtEngine.ASR4ALL, "s", "Asr4All Small", 28,
            "futo-org/asr4all-s", "735bdd8ae688094926068990ccf9b26a80cc43c3",
            listOf(
                EtFile(ASR4ALL_META, 90_364L, "025de70620e2e90fd0e3de947fea4783e0e637ec54d877a5d25c8ad3866e6f48"),
                EtFile(ASR4ALL_PTE, 38_489_024L, "a027754d46abdc7ffcdcc621a1430da7896e4504484657228a22d6eccf283381"),
            ),
        ),
        EtModel(
            "asr4all-m", EtEngine.ASR4ALL, "m", "Asr4All Medium", 55,
            "futo-org/asr4all-m", "86f67fdc60fecb4f9662ccd1d99948291db4d905",
            listOf(
                EtFile(ASR4ALL_META, 90_363L, "321501d89e6249a76b8c0f896f9c3e90089f0f9d6135ab82802e5752990cab41"),
                EtFile(ASR4ALL_PTE, 72_395_968L, "f50318acb5de7bfc75229f73edcc9276b3553efc9c3e34a7f3f1cf66f76067b4"),
            ),
        ),
        EtModel(
            "asr4all-l", EtEngine.ASR4ALL, "l", "Asr4All Large", 97,
            "futo-org/asr4all-l", "2da6534551f007201368514ec08b0b9a60ba39dc",
            listOf(
                EtFile(ASR4ALL_META, 90_362L, "05c6fd156f1fa99ea44457ab0d0a8d23b954c272fb614a034476123874e6d125"),
                EtFile(ASR4ALL_PTE, 123_044_416L, "50113d5aa74c916cce33828a3ec69c86aa64b58513a437ecf84fecfae098d351"),
            ),
        ),
        // Moondream's Parakeet Ultra (CC-BY-4.0), v3 post-trained with the same
        // architecture and tokenizer, exported the way v3 below is by
        // tools/parakeet-ultra (.github/workflows/parakeet-ultra.yml).
        EtModel(
            "parakeet-ultra", EtEngine.PARAKEET, "ultra", "Parakeet Ultra", 600,
            "wasi-master/parakeet-ultra-executorch", "a587594c991731cc8139443080f3209062fc0559",
            listOf(
                EtFile(
                    "xnnpack/int4/tokenizer.model", 360_916L,
                    "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
                ),
                EtFile(
                    "xnnpack/int4/model.pte", 719_159_960L,
                    "ce6f2fc9aa874151c5398a4da1e936d635a2b5907b243e82096c1070287fda48",
                ),
            ),
            supersedes = "parakeet-v3",
        ),
        // NVIDIA's parakeet-tdt-0.6b-v3 (CC-BY-4.0), exported by an ExecuTorch
        // maintainer with 8-bit activations and 4-bit weights for XNNPACK.
        EtModel(
            "parakeet-v3", EtEngine.PARAKEET, "tdt-0.6b-v3", "Parakeet TDT 0.6B v3", 600,
            "larryliu0820/parakeet-tdt-0.6b-v3-executorch", "17c1a8fd222fcab46c4053fc230363d20d6ee572",
            listOf(
                EtFile(
                    "xnnpack/int4/tokenizer.model", 360_916L,
                    "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
                ),
                EtFile(
                    "xnnpack/int4/model.pte", 719_223_704L,
                    "cd9c75c84b49c4bf2b20a383742e413fe49cd1eb9c01c3fd84c5ac7fd496453e",
                ),
            ),
        ),
        // The two Parakeets above exported for ExecuTorch's Vulkan delegate by
        // tools/parakeet-ultra (same 8da4w quantization, every method but the
        // preprocessor on the GPU). Opt-in: see [EtModel.gpu].
        EtModel(
            "parakeet-ultra-gpu", EtEngine.PARAKEET, "ultra-gpu", "Parakeet Ultra (GPU)", 600,
            "wasi-master/parakeet-ultra-executorch", "c3ca7a8d62377b995a49ffda12af50a0cf2b67ca",
            listOf(
                EtFile(
                    "vulkan/int4/tokenizer.model", 360_916L,
                    "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
                ),
                EtFile(
                    "vulkan/int4/model.pte", 818_608_408L,
                    "4cf5e3b78e6bcfc2d9285f6ccd03e356ee653bdd3cd45c2e4707f3ec1b25b390",
                ),
            ),
            supersedes = "parakeet-v3-gpu",
            gpu = true,
            cpuTwin = "parakeet-ultra",
        ),
        EtModel(
            "parakeet-v3-gpu", EtEngine.PARAKEET, "tdt-0.6b-v3-gpu", "Parakeet TDT 0.6B v3 (GPU)", 600,
            "wasi-master/parakeet-tdt-0.6b-v3-executorch", "efff6424e13521cddb2ce8965be9b424c57379db",
            listOf(
                EtFile(
                    "vulkan/int4/tokenizer.model", 360_916L,
                    "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
                ),
                EtFile(
                    "vulkan/int4/model.pte", 818_608_408L,
                    "114f05b427f28d82ecbc8e87d5478cb71d42a089e6b5bfb61f81998daa168967",
                ),
            ),
            gpu = true,
            cpuTwin = "parakeet-v3",
        ),
        // Useful Sensors' Moonshine Tiny (MIT), exported by Software Mansion
        // for react-native-executorch.
        EtModel(
            "moonshine-tiny", EtEngine.MOONSHINE, "tiny", "Moonshine Tiny", 27,
            "software-mansion/react-native-executorch-moonshine-tiny", "6af58e7ab467f655d034eedc7acc756c1ff9b2c2",
            listOf(
                EtFile(
                    "moonshine_tiny_tokenizer.json", 1_985_530L,
                    "6579793438bc4fbafffacf699169ff53e3769c5a0a0f5e71cdee8853e8130deb",
                ),
                EtFile(
                    "xnnpack/moonshine_tiny_xnnpack_encoder.pte", 30_870_016L,
                    "737f46a5c683ba0edaa0aaf638e02ea2e14a799906213b6c6dfd3ed8ea4d5f24",
                ),
                EtFile(
                    "xnnpack/moonshine_tiny_xnnpack_decoder.pte", 118_008_224L,
                    "89481cfa1391e9962d8ff710684a80d0900d8d39cbf34764e5f28ed8c0f9b9b0",
                ),
            ),
        ),
        // k2-fsa's QNN context binaries of the two models above, of three
        // English models with no CPU build here (Parakeet TDT 0.6B v2,
        // Parakeet TDT-CTC 110M, Moonshine Base) and of Parakeet TDT-CTC 0.6B
        // Japanese, for the Snapdragon chips
        // the `npu` gate has a runtime for (8 Gen 2, 8 Gen 3, 8 Elite, 8 Elite
        // Gen 5), each offered only on its own chip. Parakeet for 10 s and
        // 30 s clips (k2-fsa compiles 3 to 30 s), Moonshine for 10 s, the
        // longest they compile it for. Their other builds take engines this
        // app does not have (SenseVoice, Paraformer, Zipformer CTC, Whisper,
        // Nemotron streaming), or are Moonshine's other languages, which are
        // not MIT-licensed.
        parakeetNpu("SM8550", 10, 441_099_774L, "439843adb24406bfc620381750a48be9aee6cf0d253f8870a0c46f04aa174814"),
        parakeetNpu("SM8550", 30, 451_431_213L, "925a05f17d090e4f7ed2023531617dd2e322b2d0988d8235ba05b4d11b0c4d91"),
        parakeetNpu("SM8650", 10, 441_106_642L, "78342bad3ca5d380d5a7d34b730fefd58e1bd8fa55d133fec0e3256eaf056263"),
        parakeetNpu("SM8650", 30, 449_334_315L, "8ad2bb5902cbde3b922c53f4a119fcac47730b53e90ad1dff726b3ff0f7607df"),
        parakeetNpu("SM8750", 10, 440_771_028L, "48a8b6db3f5749697a2a6e5331aecd314ed092d3621b7e5b3c17bc49c78cafeb"),
        parakeetNpu("SM8750", 30, 449_456_731L, "2efa5ccd76b1b21d0b41310a21114eabbd8ed62d573333b74f7598bdc90c1fbb"),
        parakeetNpu("SM8850", 10, 440_901_766L, "ff072c4ef523d4af64766e75b6ad1f1b6a02315ead681b0f506f46edbe0704dc"),
        parakeetNpu("SM8850", 30, 449_680_863L, "604c9a55d66e0355cd475ecccbc581d5432b19e3d419eadafdd89d3664b60c36"),
        parakeetV2Npu("SM8550", 10, 432_620_882L, "f53e36f3ed93f3e70d1305091d6a4e2221677521f5f9c8c9e561b1a96ab400ec"),
        parakeetV2Npu("SM8550", 30, 444_187_280L, "68b0810d051377bb1f29ff4a5521c012f4b1d4e3988ec79636d003eaada35679"),
        parakeetV2Npu("SM8650", 10, 432_629_307L, "4d2164e1a062f1921417f25875680fbff357c6db457c2fc7f46f4e7ddc353b36"),
        parakeetV2Npu("SM8650", 30, 442_257_541L, "1ef5d147a00410ca8fbafae0daf24e2f4c0d61e551ea4a24bf6845f12a150bbb"),
        parakeetV2Npu("SM8750", 10, 432_313_918L, "b68468a46beedb4557c7c68068c84992bfba0531b69e015c182ab9fb1e92cc29"),
        parakeetV2Npu("SM8750", 30, 444_483_302L, "5c25c28f6aa8c63a3da1b3b87bf752ccdae52095bcadd4d81fe4f4096ec2b9b4"),
        parakeetV2Npu("SM8850", 10, 432_453_011L, "554d2b2fd361f7e000870dfaffd93b43ce36d6bfde8f3705a8b198e75549b9c5"),
        parakeetV2Npu("SM8850", 30, 442_868_556L, "b761763d824685c31eba0245f5e2d3c4b01258d37fae7f007a0ab06c4012146f"),
        parakeet110mNpu("SM8550", 10, 92_452_121L, "358faae9f72908194069ce04fcd2075d73049083ec159291f29240106218408d"),
        parakeet110mNpu("SM8550", 30, 95_542_938L, "9278ffec4174f8ec527019583e6b91e6b5edd753e2fc158463897e502258270b"),
        parakeet110mNpu("SM8650", 10, 92_421_816L, "54a27a7bef3787d2c962b162afef60952c881d136777b8dcce7b1448f2de7581"),
        parakeet110mNpu("SM8650", 30, 95_550_804L, "0fc230be7a9d05d747869d8961223b52374ce8228111e11960877e6dd8e5213d"),
        parakeet110mNpu("SM8750", 10, 92_464_623L, "0105a65822e952416f0032da56a6b8e1346c045e5e08c809ed55f2fa98d5a498"),
        parakeet110mNpu("SM8750", 30, 95_513_715L, "77f319e45646cd63d1948925dad6532815c8ec8fd29b4205d5b35d3eb6c9f939"),
        parakeet110mNpu("SM8850", 10, 92_421_720L, "9f54b0eb0dfc3416f6e4705a9cda671988a038ab19a5ab4099d48267ce71fb90"),
        parakeet110mNpu("SM8850", 30, 96_017_730L, "cd399fdb530fada8afb94d935b7458a314b9b7cd2c063e5b46d97b399b7881d4"),
        parakeetJaNpu("SM8550", 10, 452_599_839L, "c4f603a439242cf47cf2a72db3791afc209159906db0831ea7bcb1b18f47e2f2"),
        parakeetJaNpu("SM8550", 30, 460_030_026L, "3aa3ca43680f9ed1e6c9f6408298ec7c8b1c4e8cdad421760455c6bacfa51fca"),
        parakeetJaNpu("SM8650", 10, 452_389_543L, "9080dc6d4e263542cdebcb886c81df4d017eaa348fb5aa26c01cb41ef10dcbb3"),
        parakeetJaNpu("SM8650", 30, 460_098_007L, "74845a9e6f71856588104c1f97c23a63e4e11480bf38ced6439bb716aa172e6f"),
        parakeetJaNpu("SM8750", 10, 452_098_262L, "6f5b8e1784242ac0ce8e04727f3fc04c71d259084fd9588c764c5cde2a81d0e5"),
        parakeetJaNpu("SM8750", 30, 460_301_371L, "2051a97119900d841f1b8a4e00267c13e94bb018b3f3d212c97d363ad3841636"),
        parakeetJaNpu("SM8850", 10, 452_316_824L, "d33a0358c4ee66717792606a848a5ccb44306e8f072a611ddc675b4136bd4ddd"),
        parakeetJaNpu("SM8850", 30, 460_424_832L, "2f4edcd3a8c5206059ac0f77c99cfa1afa3aedc53cc49085f35b0bb638657287"),
        moonshineNpu("SM8550", 10, 57_745_688L, "514d2c9317f47494ad8f629b771490c3aeb97b87342d0d79ba822e050bbffd41"),
        moonshineNpu("SM8650", 10, 57_759_597L, "f092d5c95eeaf8b06866a4e124a39956aa311ec585dec1ecd64cc342a244786f"),
        moonshineNpu("SM8750", 10, 57_726_276L, "8657f68fab42978040b5979ffa560699cac427645b1dcbc573457362d2943524"),
        moonshineNpu("SM8850", 10, 57_758_480L, "e40bd70eea0b7bc798c6565ce7fd9b8498c625bf948aba7b4047c5562022d7ae"),
        moonshineBaseNpu("SM8550", 10, 112_110_655L, "8c95949235923773903f6b14682a591cf91cc1c81cf31dfe0a2e6991999e11bb"),
        moonshineBaseNpu("SM8650", 10, 112_110_577L, "299d0de22ac5c862c7695dee42d4c99a7c7c04c73de98d193f323754cdf3b0a2"),
        moonshineBaseNpu("SM8750", 10, 112_083_814L, "d49a2bfb6a1f35a9d04b700d498262ef3acd2fa3ad514eb4f0afad7ce748d723"),
        moonshineBaseNpu("SM8850", 10, 112_165_690L, "1a750b0489608bf2b72dbbc18bd77e57b32faa74f1cc5f7b55a8744a5d7b4015"),
        // Sherpa (sherpa-onnx on ONNX Runtime): streaming Zipformer
        // transducers, one per language unless said otherwise. Each carries
        // its own copy of Silero VAD (2 MB), so a model is whole on its own.
        // k2-fsa's 20M-parameter English Zipformer (Apache-2.0), int8.
        EtModel(
            "sherpa-en-small", EtEngine.SHERPA, "en-20m", "Zipformer English Small", 20,
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            "d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
            listOf(
                EtFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
                EtFile("joiner-epoch-99-avg-1.int8.onnx", 259_572L, "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"),
                EtFile("decoder-epoch-99-avg-1.int8.onnx", 539_499L, "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c"),
                SILERO_VAD,
                EtFile("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L, "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"),
            ),
            languages = setOf("en"),
        ),
        // k2-fsa's English Zipformer trained on LibriSpeech and GigaSpeech (Apache-2.0), int8.
        EtModel(
            "sherpa-en", EtEngine.SHERPA, "en", "Zipformer English", 70,
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            "672fbf1b30579d6585301139bb363f42a0ad4a24",
            listOf(
                EtFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
                EtFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
                EtFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
                SILERO_VAD,
                EtFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
            ),
            languages = setOf("en"),
        ),
        // Alpha Cephei's small streaming Bengali model (Apache-2.0), converted by k2-fsa.
        EtModel(
            "sherpa-bn", EtEngine.SHERPA, "bn", "Vosk Bengali", 23,
            "csukuangfj2/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09",
            "a7c3c1547450a7c546c876be9ca8a6ab54464423",
            listOf(
                EtFile("tokens.txt", 6_252L, "6f3a4d64d7da07e7dd7f3fd679e07607be3ec149d1b807726dbb8bbcc80a9fe1"),
                EtFile("joiner.onnx", 1_026_462L, "1df7319f4f9c36d7130cacaf629e1d52e904070e7a14bca9d4f209edeb162da6"),
                EtFile("decoder.onnx", 2_093_080L, "fc03e8ca154593b95748198a56de115d4e6048d97c41692f93dfefb05f3693b4"),
                SILERO_VAD,
                EtFile("encoder.onnx", 90_994_145L, "779f2412d6fa56f76be9fe71349cdf3edd10434ae9b1fc5e49834fb904320516"),
            ),
            languages = setOf("bn"),
        ),
        // Alpha Cephei's small streaming Russian model (Apache-2.0), int8.
        EtModel(
            "sherpa-ru", EtEngine.SHERPA, "ru", "Vosk Russian Small", 26,
            "csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
            "31fa603e4f31279c6e1f7600fed13dc4312663ab",
            listOf(
                EtFile("tokens.txt", 6_388L, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
                EtFile("joiner.int8.onnx", 259_417L, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
                EtFile("decoder.onnx", 2_093_080L, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
                SILERO_VAD,
                EtFile("encoder.int8.onnx", 26_214_060L, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
            ),
            languages = setOf("ru"),
        ),
        // k2-fsa's Korean Zipformer (Apache-2.0), int8.
        EtModel(
            "sherpa-ko", EtEngine.SHERPA, "ko", "Zipformer Korean", 73,
            "k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16",
            "ba6078bca4daf3f0dd37f79d0ab505af71df14a6",
            listOf(
                EtFile("tokens.txt", 60_246L, "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668"),
                EtFile("joiner-epoch-99-avg-1.int8.onnx", 2_581_421L, "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced"),
                EtFile("decoder-epoch-99-avg-1.int8.onnx", 2_844_692L, "68ea197936aabd249f38b53a87c775422bca64428ad4427d0e6e8092593e71fb"),
                SILERO_VAD,
                EtFile("encoder-epoch-99-avg-1.int8.onnx", 126_968_852L, "8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697"),
            ),
            languages = setOf("ko"),
            writesPunctuation = true,
        ),
        // A French Zipformer trained on Common Voice and others (Apache-2.0), int8.
        EtModel(
            "sherpa-fr", EtEngine.SHERPA, "fr", "Zipformer French", 73,
            "shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            "3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73",
            listOf(
                EtFile("tokens.txt", 4_819L, "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31"),
                EtFile("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572L, "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711"),
                EtFile("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157L, "e72b2b9ed36355bd0dd43433f7dd258e7226ab54c9ef42b28c73ebb785805623"),
                SILERO_VAD,
                EtFile("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903L, "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e"),
            ),
            languages = setOf("fr"),
        ),
        // Banafo's Kroko community model for German (CC-BY-SA-4.0).
        EtModel(
            "sherpa-de", EtEngine.SHERPA, "de", "Kroko German", 18,
            "csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
            "887db3d083240198c2d2b99fb66cfcfe6948ced8",
            listOf(
                EtFile("tokens.txt", 5_606L, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb"),
                EtFile("joiner.onnx", 336_817L, "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a"),
                EtFile("decoder.onnx", 617_489L, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562"),
                SILERO_VAD,
                EtFile("encoder.onnx", 70_091_557L, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5"),
            ),
            languages = setOf("de"),
            writesPunctuation = true,
        ),
        // Banafo's Kroko community model for Spanish (CC-BY-SA-4.0).
        EtModel(
            "sherpa-es", EtEngine.SHERPA, "es", "Kroko Spanish", 39,
            "csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
            "20cf7a4921613397841d31168796cade5b866585",
            listOf(
                EtFile("tokens.txt", 6_385L, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f"),
                EtFile("joiner.onnx", 336_817L, "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5"),
                EtFile("decoder.onnx", 617_488L, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee"),
                SILERO_VAD,
                EtFile("encoder.onnx", 154_878_102L, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b"),
            ),
            languages = setOf("es"),
            writesPunctuation = true,
        ),
        // k2-fsa's Mandarin Zipformer (Apache-2.0), int8.
        EtModel(
            "sherpa-zh", EtEngine.SHERPA, "zh", "Zipformer Chinese", 160,
            "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            "ad658fa0201659a09ea3c176129a191c77ecae8f",
            listOf(
                EtFile("tokens.txt", 20_628L, "6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652"),
                EtFile("joiner.int8.onnx", 1_033_416L, "b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b"),
                EtFile("decoder.onnx", 5_165_083L, "06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e"),
                SILERO_VAD,
                EtFile("encoder.int8.onnx", 161_141_793L, "5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f"),
            ),
            languages = setOf("zh"),
        ),
        // k2-fsa's small Mandarin Zipformer (Apache-2.0), int8 (#1005).
        EtModel(
            "sherpa-zh-small", EtEngine.SHERPA, "zh-14m", "Zipformer Chinese Small", 14,
            "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            "204ad334e2e683fd295359930cc16fc0432a23ac",
            listOf(
                EtFile("tokens.txt", 48_697L, "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac"),
                EtFile("joiner-epoch-99-avg-1.int8.onnx", 1_795_562L, "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169"),
                EtFile("decoder-epoch-99-avg-1.int8.onnx", 1_888_682L, "22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf"),
                SILERO_VAD,
                EtFile("encoder-epoch-99-avg-1.int8.onnx", 21_621_684L, "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1"),
            ),
            languages = setOf("zh"),
        ),
        // k2-fsa's Mandarin and English Zipformer (Apache-2.0), int8 (#1005).
        EtModel(
            "sherpa-zh-en", EtEngine.SHERPA, "zh-en", "Zipformer Chinese + English", 190,
            "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            "98590b7ed6443e77b714204da2757d75e1a642f4",
            listOf(
                EtFile("tokens.txt", 56_317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
                EtFile("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
                EtFile("decoder-epoch-99-avg-1.int8.onnx", 13_091_040L, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa"),
                SILERO_VAD,
                EtFile("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
            ),
            languages = setOf("zh", "en"),
        ),
        // Alibaba DAMO's streaming Paraformer for Mandarin and English (Apache-2.0), int8 (#1005).
        EtModel(
            "sherpa-zh-en-paraformer", EtEngine.SHERPA, "zh-en-paraformer", "Paraformer Chinese + English", 220,
            "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            "8e40c43232a1c5c66c82111efc5820d3accca11b",
            listOf(
                EtFile("tokens.txt", 75_756L, "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"),
                SILERO_VAD,
                EtFile("decoder.int8.onnx", 71_664_561L, "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f"),
                EtFile("encoder.int8.onnx", 165_462_184L, "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a"),
            ),
            languages = setOf("zh", "en"),
        ),
        // k2-fsa's eight-language Zipformer (Apache-2.0), int8 encoder and joiner.
        EtModel(
            "sherpa-multi", EtEngine.SHERPA, "multi", "Zipformer ar en id ja ru th vi zh", 300,
            "csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
            "c6726c1147387ad2a11148b33973135d92a55e6c",
            listOf(
                EtFile("tokens.txt", 195_244L, "784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab"),
                EtFile("joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", 8_257_421L, "db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917"),
                EtFile("decoder-epoch-75-avg-11-chunk-16-left-128.onnx", 33_837_085L, "7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48"),
                SILERO_VAD,
                EtFile("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", 296_583_597L, "f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e"),
            ),
            languages = setOf("ar", "en", "id", "ja", "ru", "th", "vi", "zh"),
            writesPunctuation = true,
        ),
        // NVIDIA's Nemotron 3.5 ASR Streaming 0.6B (OpenMDW-1.1), int8,
        // exported for sherpa-onnx by k2-fsa at the 560 ms chunk: 28 keyboard
        // languages in one streaming model, told which one by a prompt, with
        // punctuation and capitals of its own.
        EtModel(
            "sherpa-nemotron", EtEngine.SHERPA, "nemotron-3.5", "Nemotron 3.5 ASR Streaming 0.6B", 600,
            "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
            "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
            listOf(
                EtFile("tokens.txt", 131_440L, "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a"),
                EtFile("joiner.int8.onnx", 9_504_438L, "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce"),
                EtFile("decoder.int8.onnx", 14_978_075L, "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0"),
                SILERO_VAD,
                EtFile("encoder.int8.onnx", 657_601_403L, "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68"),
            ),
            languages = SherpaLanguages.NEMOTRON.keys,
            writesPunctuation = true,
            languageTags = SherpaLanguages.NEMOTRON,
        ),
        // SCB 10X's Typhoon ASR Streaming, NVIDIA's English Nemotron
        // fine-tuned for Thai (OpenMDW-1.1), int8, exported for sherpa-onnx by
        // ohmeet-com at the 560 ms chunk.
        EtModel(
            "sherpa-th-typhoon", EtEngine.SHERPA, "th-typhoon", "Typhoon ASR Thai Streaming", 600,
            "ohmeet-com/typhoon-asr-streaming-nemotron-0.6b-sherpa-onnx-560ms-int8",
            "e25c2ea82cd1c87e610c243d62a2786811ae00e8",
            listOf(
                EtFile("tokens.txt", 169_681L, "70300c69129e0e67469d25fd8c39a486f342439be3a56eb62f656c32a26e21c4"),
                EtFile("joiner.int8.onnx", 10_823_352L, "969393fcebe70a41cab9606388d5ec28733a05cf6ee6456573e267a5c26577e9"),
                EtFile("decoder.int8.onnx", 16_288_794L, "2cb9826010b98d99adc201eb69646c12bc0f6020a53ab3b70e9335ed2262da78"),
                SILERO_VAD,
                EtFile("encoder.int8.onnx", 657_573_660L, "b4289839619253d60b758105fa1e38025829c80439a41019ee396f65651b3717"),
            ),
            languages = setOf("th"),
            writesPunctuation = true,
            specialist = true,
        ),
        // NVIDIA's English Nemotron fine-tuned for Farsi by mehdi-hf
        // (OpenMDW-1.1), int8, exported for sherpa-onnx by mentisialog at the
        // 1,120 ms chunk. Bare words: the punctuation model adds the marks.
        EtModel(
            "sherpa-fa-nemotron", EtEngine.SHERPA, "fa-nemotron", "Nemotron Farsi Streaming", 600,
            "mentisialog/sherpa-onnx-nemotron-asr-streaming-farsi-1120ms-int8",
            "773e3d8f6ce043100a5dff6083271aa7811299bd",
            listOf(
                EtFile("tokens.txt", 15_037L, "7ace7d33f3784d50f9a46156cce7c411f39faab64b43df17bd5796abc56a3057"),
                EtFile("joiner.int8.onnx", 1_735_861L, "e5e365bd22a7f678899674ecad00a9fae23323973de73b508e54ea79eb56b3cc"),
                EtFile("decoder.int8.onnx", 7_257_753L, "548a4763366e3ba4ba5272f3d68465425f5c62b207922ee2d617be920a921b06"),
                SILERO_VAD,
                EtFile("encoder.int8.onnx", 657_573_705L, "13c1d630f1c82a3534c76ff36a38b4823bdc2413254d5cf7489927b000d57aa2"),
            ),
            languages = setOf("fa"),
            specialist = true,
        ),
        // Addis AI's Addis Scribe Streaming for Amharic, Nemotron 3.5 trained on
        // 5,000 hours of Amharic (OpenMDW-1.1), int8 at the 320 ms chunk, as
        // Addis AI exported it for sherpa-onnx: its prompt (am-ET) is the
        // model's default, so it is told nothing. Bare words; the encoder's
        // weights sit in encoder.onnx.data beside it, which has to come after
        // encoder.onnx here (the engine takes the first encoder file).
        EtModel(
            "sherpa-am-addis", EtEngine.SHERPA, "am-addis", "Addis Scribe Streaming Amharic", 600,
            "addisai/addis-scribe-streaming",
            "1acb8b93c58155709c5831523432a1cb2a0e0ac6",
            listOf(
                EtFile("$ADDIS_AM_ONNX/tokens.txt", 25_807L, "2b673612995f890122f5e2ca237a91d87ff4038db643a1fcbe971d81b44c16c7"),
                EtFile("$ADDIS_AM_ONNX/joiner.onnx", 2_395_318L, "ed19f12dcac0bee2e7e454e3ee495a5b931d291dd89a0d5ca37a52913a8d670a"),
                EtFile("$ADDIS_AM_ONNX/decoder.onnx", 7_913_112L, "92bbe2fc616b562914b8f03d90b0cfeb76b937cfd967af9feb442584d848bb53"),
                SILERO_VAD,
                EtFile("$ADDIS_AM_ONNX/encoder.onnx", 2_036_663L, "7b6f5ae89d3f89c5e2d9a6e4b64abf42b5f66b5bd02aea5564b9eb00f9a47966"),
                EtFile("$ADDIS_AM_ONNX/encoder.onnx.data", 655_605_504L, "50038e6420fe9071ff444c88c2960562e1d52481fc032955f8e158e02e95c0f6"),
            ),
            languages = setOf("am"),
            specialist = true,
        ),
        // The models below are not streaming ones: Silero cuts the audio at
        // each pause and the phrase before it is decoded whole ([SherpaKind]).
        // DataoceanAI's Dolphin (Apache-2.0), CTC, int8, exported by k2-fsa:
        // 40 Eastern languages and 22 Chinese dialects, punctuated.
        EtModel(
            "sherpa-dolphin-base", EtEngine.SHERPA, "dolphin-base", "Dolphin Base", 140,
            "csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02",
            "1f3a53d0ecf658f8b0974e2cfde368eee40732fa",
            listOf(
                EtFile("tokens.txt", 504_662L, "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 103_729_802L, "a3aa46c97f3f60f135ff949793cb05fabe7a0b3c484dc2e3cc699d354ee11b76"),
            ),
            languages = SherpaLanguages.DOLPHIN,
            writesPunctuation = true,
            sherpaKind = SherpaKind.DOLPHIN,
        ),
        EtModel(
            "sherpa-dolphin-small", EtEngine.SHERPA, "dolphin-small", "Dolphin Small", 372,
            "csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02",
            "c8b6689509acfcd744c04e5e169164f9ac4cae32",
            listOf(
                EtFile("tokens.txt", 504_662L, "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 249_658_954L, "c1afcb9265de0ebd853eb8f570b371f399a6f9b2b9af9a3cb17c2e509171e697"),
            ),
            languages = SherpaLanguages.DOLPHIN,
            writesPunctuation = true,
            sherpaKind = SherpaKind.DOLPHIN,
        ),
        // Alibaba's SenseVoice Small (FunASR model licence, free to use with
        // attribution), int8, exported by k2-fsa: Chinese, English, Japanese,
        // Korean and Cantonese, punctuated through its own text normalisation.
        EtModel(
            "sherpa-sense-voice", EtEngine.SHERPA, "sense-voice", "SenseVoice Small", 234,
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            "355f4d4884d8afd08aef04b9007a8556d7b463b2",
            listOf(
                EtFile("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 237_115_547L, "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"),
            ),
            languages = SherpaLanguages.SENSE_VOICE.keys,
            writesPunctuation = true,
            sherpaKind = SherpaKind.SENSE_VOICE,
            languageTags = SherpaLanguages.SENSE_VOICE,
        ),
        // WenetSpeech-Yue's U2++ Conformer for Cantonese (Apache-2.0), CTC,
        // int8, exported by k2-fsa. Bare characters.
        EtModel(
            "sherpa-yue-wenet", EtEngine.SHERPA, "yue-wenet", "WenetSpeech-Yue Cantonese", 120,
            "csukuangfj/sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10",
            "c911764e8227cf372ee60adc75f7f407e5b8c905",
            listOf(
                EtFile("tokens.txt", 85_361L, "c7750677a1183606d2fd6f16d792e06e70d9843dba8a0c6e23a9dec78e06977a"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 134_698_500L, "201bfd9e12ec4ac9ee3b23c5e071d9fa2381a8b21df317e2e08a170d6f1f55d3"),
            ),
            languages = setOf("yue"),
            sherpaKind = SherpaKind.WENET_CTC,
            specialist = true,
        ),
        // GigaAM-Multilingual large fine-tuned for Uzbek by rustam1221 (MIT),
        // NeMo CTC, int8, exported by Alex13: 13.3% WER on FLEURS, lower case
        // with punctuation.
        EtModel(
            "sherpa-uz-gigaam", EtEngine.SHERPA, "uz-gigaam", "GigaAM Uzbek 600M", 600,
            "Alex13/sherpa-onnx-gigaam-uzbek-600m",
            "b53434a0b06418831dbc1cec73d6b851054f593d",
            listOf(
                EtFile("tokens.txt", 411L, "2022129a0c61784d4a1838da59e5875f9b29d2c6ad12413cbedc98632f98710f"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 591_649_160L, "8bff17ef8d7da04369a559b99b71c1155a90fc604d35d84c6d4e44159c1b0873"),
            ),
            languages = setOf("uz"),
            writesPunctuation = true,
            sherpaKind = SherpaKind.NEMO_CTC,
            specialist = true,
        ),
        // Moonshine's models for languages other than English, quantised and
        // converted for sherpa-onnx by k2-fsa. The Moonshine AI Community
        // License covers them: free for personal and research use and for
        // organisations under US $1 million a year in revenue.
        moonshineLanguage("ar", "Arabic", "base", 61, "00c1062f1b70e4da96658486f4fd79552d8bc22b",
            31_326_824L, "68e50ebe0317ce909f098044a5dda2a76e6b86dc882829a317771fbafc5826ae",
            109_424_552L, "8f272cb50818e28ad86bbffc21e1450a4d57155e95f099ca6a236f38f4d9eafb"),
        moonshineLanguage("es", "Spanish", "base", 61, "59e235a505b7372eb7f896471a74c776f6f6e061",
            20_964_320L, "331aafa2fc7f7e55ba28376eef08eaf919ae105bb719cd64ba6875505dca72b3",
            43_612_200L, "8e6513ad66a3a71ca86824746a09051eeb60468940eaf8201ce758e8919e2b5d",
            tokens = EtFile("tokens.txt", 532_090L, "c6e35883ba038f70ceea8b4a21cf79742adbda7e6730cbff27460857e0667a02")),
        moonshineLanguage("ja", "Japanese", "base", 61, "cf95fc9c3a55a240892c80c501e80b35603d20bc",
            31_326_816L, "3230cafb84d5f08800e60de6f932aad7c69c649a37fa6997fe04fe25f808b56b",
            109_424_424L, "35a522052d2d8695d0dd2870666f088d633111e08cfbed0418f61c4122b4ba25"),
        moonshineLanguage("zh", "Chinese", "base", 61, "19042e30b8680b101d0db3b0b8cabc84ae954b7f",
            31_326_816L, "c725a24b58595905921ea2a47e2bcf0f18c78f4d171d96136f2dcbc8c77a58a6",
            109_424_520L, "bf79fce626e123739ec37eceb2b2a010a93d720da266dd5d8ef9a47ef9a7dc36"),
        moonshineLanguage("uk", "Ukrainian", "base", 61, "336649c4c1cd81d066c586163381fe8bb65d9145",
            31_326_816L, "ea37ff7a2c308b566def1fd6e6860e23db4968590f192d5cc0fbc494666e30f9",
            109_424_424L, "2c0c1ebc20ba75a21ff5315fd039e53d2fc9ba77a9ca9ad3bc4c7c1c1de93cbc"),
        moonshineLanguage("vi", "Vietnamese", "base", 61, "2946c5272fcb2d92f5cd99edb6e828c66be94f2e",
            31_326_816L, "5a63cf0e248ef463776fbad50784813b4185eb73136f1fb062c5722e667130dc",
            109_424_520L, "0a4f007e9d585348d94124d8de47f7aefbc3e2d3fa44152646af7b94c639770d"),
        moonshineLanguage("ja", "Japanese", "tiny", 27, "550e2eb0a8b33092f3b394f64649ee1b3d9eb506",
            13_238_184L, "86ece73812604b9b5f1274b4d1e6eec0d783b96088ff46d49e30a53f881cad73",
            58_327_272L, "9fcd9b71323a496b307e20dd305c4e9a1b533c7bedd6e4f660e974967dd60bb6"),
        moonshineLanguage("ko", "Korean", "tiny", 27, "3f2e79d6fe04ae0009fcb150a035586c12ac742f",
            13_238_176L, "947260d46252f48eada86a34986b3f70c01d68a343959949a77375b94debd055",
            58_327_336L, "95aa9f2e764b80625d2889d6ec9f05c965808e540ac50c16abd10c7ea33fe44b"),
        // The sherpa-onnx project's online punctuation model for English:
        // punctuation and capitals in one pass. Not on k2-fsa's own Hugging
        // Face; this copy is the same bytes as their GitHub release, pinned by
        // hash like everything else.
        EtModel(
            "sherpa-punct-en", EtEngine.SHERPA, "punct-en", "Punctuation (English)", 7,
            "brady-pplx/sherpa-onnx-online-punct-en-2024-08-06",
            "29a49a07620ef067e6fa40580da620c11db07b83",
            listOf(
                EtFile("bpe.vocab", 149_430L, "e118b7ad88c54db562517df49e1cffd4836d166c34fb190fd311d7f34eb238f5"),
                EtFile("model.int8.onnx", 7_490_500L, "9d611f445fe4a46186080fe161be6059d87d72eb88d3a8cb00c1a06e83a6067e"),
            ),
            languages = setOf("en"),
            punctuation = true,
        ),
        // Meta's Omnilingual ASR 300M CTC (Apache-2.0), int8, exported for
        // sherpa-onnx by k2-fsa. No language to choose: it tells them apart.
        EtModel(
            "omnilingual-300m", EtEngine.OMNILINGUAL, "300m-ctc", "Omnilingual ASR 300M", 300,
            "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094",
            listOf(
                EtFile("tokens.txt", 86_423L, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 365_352_120L, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"),
            ),
        ),
        // The 1B CTC model of Omnilingual ASR's v2 release (Apache-2.0), int8,
        // exported by k2-fsa: average CER 8.6 on FLEURS's 81 languages to the
        // 300M's 11.8, for about three times the memory (some 3 GB open).
        EtModel(
            "omnilingual-1b", EtEngine.OMNILINGUAL, "1b-ctc-v2", "Omnilingual ASR 1B v2", 1000,
            "csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05",
            "5243e02858d1428b8fbeeb5f7f1cc6fccf4d9433",
            listOf(
                EtFile("tokens.txt", 90_630L, "7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6"),
                SILERO_VAD,
                EtFile("model.int8.onnx", 1_032_239_439L, "8af72da192fc2c8567c328d4f8059bdf47f182a0369077893c295ef39740c637"),
            ),
        ),
        // Alibaba's Qwen3-ASR 0.6B (Apache-2.0), int8 encoder and decoder,
        // exported for sherpa-onnx by Wasser1462 and published by k2-fsa
        // (#1437). The tokenizer's three files sit beside the graphs, which
        // is where the engine points sherpa-onnx's tokenizer folder.
        EtModel(
            "qwen3-asr-0.6b", EtEngine.QWEN3, "0.6b", "Qwen3-ASR 0.6B", 600,
            "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
            listOf(
                EtFile("tokenizer/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                EtFile("tokenizer/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                EtFile("tokenizer/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                SILERO_VAD,
                EtFile("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                EtFile("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                EtFile("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            ),
        ),
        // Alpha Cephei's small Vosk models (#1165), every one their list did
        // not mark obsolete: one zip each, unpacked on the phone. Licences
        // as Alpha Cephei's model page gives them.
        // Apache-2.0
        vosk(
            "en-us", "Vosk English (US)", 45, "vosk-model-small-en-us-0.15", 41_205_931L,
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", "en",
        ),
        // Apache-2.0
        vosk(
            "en-gb", "Vosk English (UK)", 43, "vosk-model-small-en-gb-0.15", 42_757_500L,
            "eab539cf040c1dc907c9f10102cadd3011f17eb24c1c5f74b0460cc3e7e2804a", "en",
        ),
        // Apache-2.0
        vosk(
            "en-in", "Vosk English (India)", 38, "vosk-model-small-en-in-0.4", 37_573_330L,
            "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7", "en",
        ),
        // Apache-2.0
        vosk(
            "ar", "Vosk Arabic", 104, "vosk-model-small-ar-0.3", 104_351_896L,
            "8696c91f39ec67a08c513845cfac26dbd4e81937be4fba25233e6c5435c18ca9", "ar",
        ),
        // Apache-2.0
        vosk(
            "ar-tn", "Vosk Arabic (Tunisian)", 50, "vosk-model-small-ar-tn-0.1-linto", 165_703_754L,
            "dec314da301a22ceee91a9c7121f469e17f6be9dbb6fce8c3d37739a20d16b38", "ar",
        ),
        // Apache-2.0
        vosk(
            "ca", "Vosk Catalan", 43, "vosk-model-small-ca-0.4", 43_405_881L,
            "99f90bfff5c2b187c705ddbc20aaa2600eddc359466f404404f1db6029fba5d4", "ca",
        ),
        // Apache-2.0
        vosk(
            "zh", "Vosk Chinese", 44, "vosk-model-small-cn-0.22", 43_898_754L,
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba", "zh",
        ),
        // MIT, from Rhasspy
        vosk(
            "cs", "Vosk Czech", 46, "vosk-model-small-cs-0.4-rhasspy", 46_088_666L,
            "287c3bbefc8ad67b4ab9636eecef3d62acc3719990777d03e226db5a7f19fbda", "cs",
        ),
        // Apache-2.0
        vosk(
            "de", "Vosk German", 46, "vosk-model-small-de-0.15", 46_499_967L,
            "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43", "de",
        ),
        // Apache-2.0
        vosk(
            "eo", "Vosk Esperanto", 44, "vosk-model-small-eo-0.42", 43_839_401L,
            "4459ca908f7eae862a5975305f2a720735f5205a9884d8f559dc46fe70034636", "eo",
        ),
        // Apache-2.0
        vosk(
            "es", "Vosk Spanish", 40, "vosk-model-small-es-0.42", 39_817_833L,
            "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f", "es",
        ),
        // Apache-2.0
        vosk(
            "fa", "Vosk Persian", 53, "vosk-model-small-fa-0.42", 53_431_220L,
            "977cb5faa538f3a835ccfd35f5f6d8284b5c450b89c700b9bd4736b66536ad46", "fa",
        ),
        // Apache-2.0
        vosk(
            "fr", "Vosk French", 42, "vosk-model-small-fr-0.22", 42_233_323L,
            "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad", "fr",
        ),
        // Apache-2.0
        vosk(
            "gu", "Vosk Gujarati", 108, "vosk-model-small-gu-0.42", 108_054_987L,
            "e87b7055e52b38a493a8308cf815b6fc05b9a89ef8aee18b4cbc2867cfc5f42a", "gu",
        ),
        // Apache-2.0
        vosk(
            "hi", "Vosk Hindi", 44, "vosk-model-small-hi-0.22", 44_458_845L,
            "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48", "hi",
        ),
        // Apache-2.0
        vosk(
            "it", "Vosk Italian", 50, "vosk-model-small-it-0.22", 49_665_141L,
            "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267", "it",
        ),
        // Apache-2.0
        vosk(
            "ja", "Vosk Japanese", 50, "vosk-model-small-ja-0.22", 49_704_573L,
            "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac", "ja",
        ),
        // Apache-2.0
        vosk(
            "ka", "Vosk Georgian", 46, "vosk-model-small-ka-0.42", 45_682_310L,
            "a4aa25c879c479428694d6839bbdfcaf1e879361f34f6a9301d750e95cc63b6d", "ka",
        ),
        // Apache-2.0
        vosk(
            "ko", "Vosk Korean", 87, "vosk-model-small-ko-0.22", 86_914_329L,
            "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989", "ko",
        ),
        // Apache-2.0
        vosk(
            "ky", "Vosk Kyrgyz", 51, "vosk-model-small-ky-0.42", 51_041_096L,
            "7a3595e6a6bd138e1f82689e7b13bdc838d84d96a83cbb1848658ee5094307b2", "ky",
        ),
        // Apache-2.0
        vosk(
            "kk", "Vosk Kazakh", 60, "vosk-model-small-kz-0.42", 59_697_294L,
            "b6d6bfe6195c866805bd18b1f56723e4d2ba4866e81df1481006d0c5704fda4f", "kk",
        ),
        // Apache-2.0
        vosk(
            "nl", "Vosk Dutch", 40, "vosk-model-small-nl-0.22", 40_441_176L,
            "039811c3b829de64e4f123a9f684a53784005b212a346ac0b899dc7efce2ed0a", "nl",
        ),
        // Apache-2.0
        vosk(
            "pl", "Vosk Polish", 53, "vosk-model-small-pl-0.22", 52_979_372L,
            "c4cd16498ea544f446f9e9a55cbd602b71cfe5a2b6f2b0834d81e1b6fce15f0d", "pl",
        ),
        // Apache-2.0
        vosk(
            "pt", "Vosk Portuguese", 32, "vosk-model-small-pt-0.3", 32_453_112L,
            "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7", "pt",
        ),
        // Apache-2.0
        vosk(
            "ru", "Vosk Russian", 46, "vosk-model-small-ru-0.22", 46_236_750L,
            "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4", "ru",
        ),
        // MIT, from Rhasspy
        vosk(
            "sv", "Vosk Swedish", 304, "vosk-model-small-sv-rhasspy-0.15", 303_504_931L,
            "5b54f2ca8cacf9766588d05f6c8ca7e8921f054f050726e29d3516e78aefe054", "sv",
        ),
        // Apache-2.0
        vosk(
            "te", "Vosk Telugu", 61, "vosk-model-small-te-0.42", 60_544_249L,
            "3e77b05becc7599a596ccebd8b22cb95eb4eca0cfca524827cdfbfd66af09497", "te",
        ),
        // Apache-2.0
        vosk(
            "tg", "Vosk Tajik", 52, "vosk-model-small-tg-0.22", 51_879_043L,
            "870a7e0051e0cdc019e8d5f03acd89b44ef32f8d3935e2cf4e834d61921f0175", "tg",
        ),
        // Apache-2.0
        vosk(
            "tr", "Vosk Turkish", 37, "vosk-model-small-tr-0.3", 36_855_784L,
            "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5", "tr",
        ),
        // Apache-2.0
        vosk(
            "uk", "Vosk Ukrainian", 144, "vosk-model-small-uk-v3-small", 143_914_407L,
            "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247", "uk",
        ),
        // Apache-2.0
        vosk(
            "uz", "Vosk Uzbek", 51, "vosk-model-small-uz-0.22", 51_061_189L,
            "ffb77328df64f222a3fac6e368e0baff9dc1258f11b6b5ca5deaa82e5f688e6a", "uz",
        ),
        // Apache-2.0
        vosk(
            "vi", "Vosk Vietnamese", 34, "vosk-model-small-vn-0.4", 33_656_337L,
            "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5", "vi",
        ),
    ) + EtNpuCatalog.models

    fun byId(id: String): EtModel? = models.firstOrNull { it.id == id } ?: EtCustomModels.models.firstOrNull { it.id == id }

    /** Sherpa's English punctuation model: English dictation from the engines that write no marks uses it once it is downloaded. */
    val sherpaPunctuation: EtModel get() = models.first { it.engine == EtEngine.SHERPA && it.punctuation }

    /**
     * [engine]'s models this phone can run: a GPU build only where the GPU
     * speaks Vulkan 1.1, an NPU build only on its own chip in a build that
     * has the library that runs it (sherpa-onnx's QNN library, or the
     * ExecuTorch build with that NPU's delegate).
     */
    fun forEngine(engine: EtEngine): List<EtModel> =
        models.filter {
            it.engine == engine && (!it.gpu || EtNative.vulkanPossible()) && (it.npu == null || npuOffered(it))
        }

    private fun npuOffered(model: EtModel): Boolean =
        if (model.etNpu != null) EtNpu.offered(model) else model.npu == EtNative.qnnSoc() && SherpaQnnNative.shipped
}

/**
 * Where the ExecuTorch models live: `filesDir/<engine>/<model>/`, apart from
 * the other engines' folders so no engine's cleanup can touch another's. A
 * file is only ever moved into place whole and hash-checked, so a model is
 * downloaded exactly when all of its files are there at their sizes.
 */
object EtStore {

    fun rootDir(filesDir: File, engine: EtEngine): File = File(filesDir, engine.dirName)
    fun dir(filesDir: File, model: EtModel): File = File(rootDir(filesDir, model.engine), model.dirName)
    fun file(filesDir: File, model: EtModel, file: EtFile): File = File(dir(filesDir, model), file.name)
    fun file(filesDir: File, model: EtModel, name: String): File = file(filesDir, model, model.file(name))

    fun isDownloaded(filesDir: File, model: EtModel): Boolean =
        model.files.all { f ->
            if (f.archive) {
                EtArchive.mark(dir(filesDir, model), f).isFile
            } else {
                file(filesDir, model, f).let { it.isFile && it.length() == f.bytes }
            }
        }

    /**
     * Unpacks each of [model]'s archives that is on the device whole and not
     * yet unpacked; its hash was checked on the way in, by the download or
     * the import. Blocking. Throws [java.io.IOException] for an archive that
     * cannot be laid out safely.
     */
    fun unpackArchives(filesDir: File, model: EtModel) {
        val dir = dir(filesDir, model)
        for (f in model.files) {
            if (!f.archive || EtArchive.mark(dir, f).isFile) continue
            val zip = file(filesDir, model, f)
            if (zip.isFile && zip.length() == f.bytes) EtArchive.unpack(zip, dir, f)
        }
    }

    /** The dictation models of [engine] on the device; never a helper such as punctuation. */
    fun downloaded(filesDir: File, engine: EtEngine): List<EtModel> =
        (
            EtCatalog.forEngine(engine) +
                if (engine.takesCustomModels) EtCustomModels.load(filesDir).filter { it.engine == engine } else emptyList()
            )
            .filter { !it.punctuation && isDownloaded(filesDir, it) }

    /**
     * The model an engine dictates with: the largest one on the device that
     * transcribes every one of [languageIds], since that is the one kept on
     * purpose ([EtModel.ORDER]), except that a [EtModel.specialist] for them
     * comes first. Any language when [languageIds] is empty, and then a
     * general model before a specialist.
     */
    fun active(filesDir: File, engine: EtEngine, languageIds: List<String> = emptyList()): EtModel? =
        pick(downloaded(filesDir, engine), languageIds)

    /** [active]'s choice among [models]. */
    fun pick(models: List<EtModel>, languageIds: List<String>): EtModel? {
        val fits = models.filter { model -> languageIds.all(model::supports) }
        val (specialists, general) = fits.partition { it.specialist }
        val first = if (languageIds.isEmpty()) general.ifEmpty { specialists } else specialists.ifEmpty { general }
        return first.maxWithOrNull(EtModel.ORDER)
    }

    /**
     * Whether [model] dictates for at least one of its languages while
     * [downloaded] are on the device, as [pick] would decide for each.
     */
    fun inUse(model: EtModel, downloaded: List<EtModel>): Boolean =
        (model.languages ?: model.engine.languages).any { pick(downloaded, listOf(it)) === model }

    fun bytesOnDisk(filesDir: File, model: EtModel): Long =
        FileDownloader.bytesUnder(dir(filesDir, model))

    fun delete(filesDir: File, model: EtModel) {
        dir(filesDir, model).deleteRecursively()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
