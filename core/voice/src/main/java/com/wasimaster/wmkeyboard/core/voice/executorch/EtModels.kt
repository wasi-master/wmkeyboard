package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.voice.sherpa.OmnilingualLanguages
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File
import java.security.MessageDigest

/**
 * The dictation engines whose models are downloaded file by file from
 * Hugging Face: the ones that run on [EtNative], and Sherpa, which runs on
 * ONNX Runtime ([SherpaNative]) but keeps its models the same way. [id] is the
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
    ;

    /** Whether the engine runs on ONNX Runtime (the `onnx` gate) rather than ExecuTorch. */
    val onnx: Boolean get() = this == SHERPA || this == OMNILINGUAL

    /** Whether this build has the runtime the engine needs, counting a module not fetched yet. */
    val runtimeShipped: Boolean
        get() = if (onnx) SherpaNative.shipped else EtNative.shipped

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
 * such as the Silero VAD every Sherpa model carries.
 */
class EtFile(val path: String, val bytes: Long, val sha256: String, val from: EtSource? = null) {
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
    val writesPunctuation: Boolean = engine != EtEngine.SHERPA && engine != EtEngine.OMNILINGUAL,
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    fun url(file: EtFile): String {
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
}

/** Every model the ExecuTorch engines can download. */
object EtCatalog {

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
        model.files.all { f -> file(filesDir, model, f).let { it.isFile && it.length() == f.bytes } }

    /** The dictation models of [engine] on the device; never a helper such as punctuation. */
    fun downloaded(filesDir: File, engine: EtEngine): List<EtModel> =
        (EtCatalog.forEngine(engine) + if (engine == EtEngine.SHERPA) EtCustomModels.load(filesDir) else emptyList())
            .filter { !it.punctuation && isDownloaded(filesDir, it) }

    /**
     * The model an engine dictates with: the largest one on the device that
     * transcribes every one of [languageIds], since that is the one kept on
     * purpose. Any language when [languageIds] is empty.
     */
    fun active(filesDir: File, engine: EtEngine, languageIds: List<String> = emptyList()): EtModel? =
        downloaded(filesDir, engine)
            .filter { model -> languageIds.all(model::supports) }
            .maxByOrNull { it.millions }

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
