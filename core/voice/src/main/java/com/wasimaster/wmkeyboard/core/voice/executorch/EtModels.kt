package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.voice.sherpa.OmnilingualLanguages
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
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
     * Sherpa: k2-fsa's sherpa-onnx running streaming Zipformer transducers,
     * one model per language (one covers eight), decoded while the user
     * speaks, with Silero to hear the end of a phrase.
     */
    SHERPA(
        "sherpa", "sherpa",
        setOf("en", "bn", "ru", "ko", "fr", "de", "es", "zh", "ar", "id", "ja", "th", "vi"),
    ),

    /**
     * Meta's Omnilingual ASR, 300M CTC, on the same sherpa-onnx runtime: one
     * model for 1,600-odd languages, every keyboard language among them
     * listed in [OmnilingualLanguages]. It decodes a whole phrase at a time,
     * so Silero cuts the audio at each pause and the phrase before it goes in.
     */
    OMNILINGUAL("omnilingual", "omnilingual", OmnilingualLanguages.codes),

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
    val onnx: Boolean get() = this == SHERPA || this == OMNILINGUAL

    /** Whether this build has the runtime the engine needs, counting a module not fetched yet. */
    val runtimeShipped: Boolean
        get() = when {
            onnx -> SherpaNative.shipped
            this == VOSK -> VoskNative.shipped
            else -> EtNative.shipped
        }

    /**
     * Whether the engine decodes while the user speaks rather than when the
     * clip ends. Omnilingual counts: it decodes each phrase at the pause after
     * it. So does Parakeet (#1168), which passes over the clip as it grows.
     */
    val live: Boolean
        get() = this == ASR4ALL || this == SHERPA || this == OMNILINGUAL || this == VOSK || this == PARAKEET

    /** The one thread the engine's model calls run on. */
    val dispatcher: CoroutineDispatcher
        get() = when {
            onnx -> SherpaNative.dispatcher
            this == VOSK -> VoskNative.dispatcher
            else -> EtNative.dispatcher
        }

    /** Whether [languageId] (a keyboard language id such as `en_GB` or `de`) is one this engine transcribes. */
    fun supports(languageId: String): Boolean =
        languageId.trim().lowercase().substringBefore('-').substringBefore('_') in languages ||
            (this == SHERPA && EtCustomModels.models.any { it.supports(languageId) })

    companion object {
        fun byId(id: String): EtEngine? = entries.firstOrNull { it.id == id }
    }
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
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    /** Whether an engine runs this rather than [other] when both are on the device. */
    fun outranks(other: EtModel): Boolean = ORDER.compare(this, other) > 0

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
        /** Smallest to largest; of two the same size, the one superseded first. */
        val ORDER: Comparator<EtModel> = compareBy<EtModel> { it.millions }.thenBy { it.supersedes != null }
    }
}

/** Every model the ExecuTorch engines can download. */
object EtCatalog {

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
    )

    fun byId(id: String): EtModel? = models.firstOrNull { it.id == id } ?: EtCustomModels.models.firstOrNull { it.id == id }

    /** Sherpa's English punctuation model: English dictation from the engines that write no marks uses it once it is downloaded. */
    val sherpaPunctuation: EtModel get() = models.first { it.engine == EtEngine.SHERPA && it.punctuation }

    fun forEngine(engine: EtEngine): List<EtModel> = models.filter { it.engine == engine }
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
        (EtCatalog.forEngine(engine) + if (engine == EtEngine.SHERPA) EtCustomModels.load(filesDir) else emptyList())
            .filter { !it.punctuation && isDownloaded(filesDir, it) }

    /**
     * The model an engine dictates with: the largest one on the device that
     * transcribes every one of [languageIds], since that is the one kept on
     * purpose ([EtModel.ORDER]). Any language when [languageIds] is empty.
     */
    fun active(filesDir: File, engine: EtEngine, languageIds: List<String> = emptyList()): EtModel? =
        downloaded(filesDir, engine)
            .filter { model -> languageIds.all(model::supports) }
            .maxWithOrNull(EtModel.ORDER)

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
