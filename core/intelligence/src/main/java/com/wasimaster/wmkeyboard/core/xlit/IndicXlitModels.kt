package com.wasimaster.wmkeyboard.core.xlit

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import java.io.File

/**
 * The files behind AI4Bharat's IndicXlit v1.0 (MIT) in both directions, each
 * exported to ONNX and quantised to int8 by `scripts/export_indicxlit.py` in
 * the data repository and published as assets of its `models` release under
 * `<sha256>-<name>`, like [com.wasimaster.wmkeyboard.core.semantic.SemanticModels]:
 *
 * - [NATIVE], English letters to 21 Indic scripts: neural suggestions on the
 *   Indic phonetic layouts;
 * - [LATIN], the same languages back to English letters: romanized dictation
 *   (Hinglish, Banglish...) for the words the spelling maps do not know.
 *
 * The two are the same network with the vocabularies swapped, so libwmxlit
 * runs either. One model serves every language of a direction, so each is
 * one pack of three files whichever languages use it. Sizes and digests are
 * the published files'; a download is checked against them before it gets
 * its name, so the address can be any mirror.
 */
object IndicXlitModels {

    /** The data repository's release the files are assets of. */
    const val RELEASE_TAG = "models"

    class Part(val fileName: String, val bytes: Long, val sha256: String)

    /**
     * One direction: its three files in a folder of its own under
     * `noBackupFilesDir` (not backed up: they come back with one download),
     * and which languages it reads and what it may write for each.
     */
    class Pack internal constructor(
        private val folder: String,
        val encoder: Part,
        val decoder: Part,
        val vocab: Part,
        /** Whether the source is English letters (the model reads a typed word) rather than a native word. */
        val latinSource: Boolean,
        /** The languages this pack may be asked about. */
        val languages: Set<String>,
        private val writes: (languageId: String, symbol: String) -> Boolean,
    ) {
        val parts: List<Part> = listOf(encoder, decoder, vocab)

        /** Every byte of the pack, for the settings row. */
        val bytes: Long = parts.sumOf { it.bytes }

        fun supports(languageId: String): Boolean = languageId in languages

        /** Whether target symbol [symbol] may be in an answer for [languageId]. */
        fun writes(languageId: String, symbol: String): Boolean = writes.invoke(languageId, symbol)

        fun dir(noBackupFilesDir: File): File = File(noBackupFilesDir, "$folder/models")

        fun file(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName)

        fun partFile(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName + ".part")

        /** Whole when every file is there at its pinned length; the digest was checked before it got its name. */
        fun isDownloaded(noBackupFilesDir: File): Boolean = parts.all {
            file(noBackupFilesDir, it).let { f -> f.isFile && f.length() == it.bytes }
        }

        /** Every byte of the pack on disk now, for the storage screen. */
        fun bytesOnDisk(noBackupFilesDir: File): Long =
            parts.sumOf { file(noBackupFilesDir, it).takeIf(File::isFile)?.length() ?: 0L }
    }

    /**
     * The keyboard's phonetic languages the English-to-Indic model writes,
     * with the Unicode block its readings are kept to. The model's output
     * vocabulary is every script's letters at once; a reading for Bengali may
     * only use Bengali's. Urdu also allows the Arabic presentation forms,
     * which its training text has.
     */
    private val BLOCKS: Map<String, List<IntRange>> = mapOf(
        "bn" to listOf(0x0980..0x09FF),
        "as" to listOf(0x0980..0x09FF),
        "hi" to listOf(0x0900..0x097F),
        "mr" to listOf(0x0900..0x097F),
        "pa" to listOf(0x0A00..0x0A7F),
        "gu" to listOf(0x0A80..0x0AFF),
        "or" to listOf(0x0B00..0x0B7F),
        "ta" to listOf(0x0B80..0x0BFF),
        "te" to listOf(0x0C00..0x0C7F),
        "kn" to listOf(0x0C80..0x0CFF),
        "ml" to listOf(0x0D00..0x0D7F),
        "ur" to listOf(0x0600..0x06FF, 0xFB50..0xFEFF),
    )

    /** Whether [c] may be in a reading for [languageId]: its block, or a joiner. */
    private fun nativeWrites(languageId: String, c: Char): Boolean =
        c == '‌' || c == '‍' || BLOCKS[languageId].orEmpty().any { c.code in it }

    /** English letters to the Indic scripts: neural suggestions on the phonetic layouts. */
    val NATIVE = Pack(
        folder = "xlit",
        encoder = Part(
            "indicxlit-encoder.int8.onnx", 6_072_771L,
            "c94cd7a2e048da5d6d7f3f46e00dce13a654cb4db7afdeb03d068d730da48ce8",
        ),
        decoder = Part(
            "indicxlit-decoder.int8.onnx", 7_070_073L,
            "ba94caddf41ca54202f4a439677d7a3034f8ed9f32c3ae993d87c6cda3c3140d",
        ),
        vocab = Part(
            "indicxlit-vocab.txt", 5_243L,
            "313b400545b4b08341ad10c33e2d73baaefb194c18e2d74bcdf00392efcdbea5",
        ),
        latinSource = true,
        languages = BLOCKS.keys,
        writes = { languageId, symbol -> symbol.all { nativeWrites(languageId, it) } },
    )

    /**
     * The Indic scripts back to English letters, for romanized dictation:
     * how people spell a word the spelling maps have nothing for. Only the
     * 26 letters are written (the vocabulary's `madeupword` fillers are
     * letters too, and are not). It measured better than the keyboard's own
     * romanizers in every one of these languages on Aksharantar's test words
     * (native to Latin, top-1, 1,500 words each, the model from five
     * characters up as dictation uses it): Bengali 25.7% to 43.6%, Hindi
     * 38.9% to 60.1%, Urdu 16.2% to 35.9%, Tamil 34.6% to 51.7%, Telugu
     * 43.1% to 59.5%, all thirteen 29.4% to 54.5%.
     */
    val LATIN = Pack(
        folder = "xlit-latin",
        encoder = Part(
            "indicxlit-latin-encoder.int8.onnx", 6_842_820L,
            "a6f8a8642a3fe05c4619500e5f7aada2d691ba7b91be94d5d6c0902e95bd81c2",
        ),
        decoder = Part(
            "indicxlit-latin-decoder.int8.onnx", 6_107_507L,
            "2a4d5c65bb619397ed3e807d94afcdb7826ff0d42171d6ea1a694792a9cf7f57",
        ),
        vocab = Part(
            "indicxlit-latin-vocab.txt", 5_243L,
            "23ff975af338afc2a6223a53f3e1261eefa4dd3438342aee00a5a76b41f2dea7",
        ),
        latinSource = false,
        languages = setOf("as", "bn", "gu", "hi", "kn", "ml", "mr", "ne", "or", "pa", "ta", "te", "ur"),
        writes = { _, symbol -> symbol.length == 1 && symbol[0] in 'a'..'z' },
    )

    /** The languages a phonetic layout can ask the English-to-Indic model about. */
    val LANGUAGES: Set<String> = NATIVE.languages

    fun supports(languageId: String): Boolean = NATIVE.supports(languageId)

    /**
     * The languages where the model may also choose the space bar's word when
     * the rules' reading is not a known word. Measured with the spelling map on
     * (top-1, 4,000 Aksharantar test words each, beam 4, the default 300,000
     * word list): Bengali 25.3% to 53.3%, Kannada 44.0% to 62.0%, Telugu 50.0%
     * to 66.1%, Assamese 26.7% to 38.5%, Gujarati 46.9% to 57.0%, Malayalam
     * 25.6% to 35.0%, Marathi 49.2% to 58.1%, Punjabi 40.1% to 48.1%, Odia
     * 24.5% to 32.2%, Hindi 40.0% to 47.6%. Tamil (30.3% to 31.5%) and Urdu
     * (27.8% to 28.7%) gained about one point, not worth what the swap costs.
     */
    val LEADS: Set<String> = setOf("bn", "hi", "as", "gu", "kn", "ml", "mr", "or", "pa", "te")

    fun canLead(languageId: String): Boolean = languageId in LEADS

    /**
     * The pack and part a file named [name] is, for an import (#998): the
     * part's own name, or the `<sha256>-<name>` it is published under. Null
     * for any other file.
     */
    fun partOf(name: String): Pair<Pack, Part>? =
        listOf(NATIVE, LATIN).firstNotNullOfOrNull { pack ->
            pack.parts.firstOrNull { name == it.fileName || name == "${it.sha256}-${it.fileName}" }?.let { pack to it }
        }

    /** The release asset for [part], or null when the data repository is not on GitHub. */
    fun url(part: Part): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }
}
