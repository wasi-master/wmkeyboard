package com.wasimaster.wmkeyboard.core.fonts

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import java.io.File
import java.io.InputStream

/**
 * The outcome of reading a font file.
 *
 * The two failure cases carry a string resource rather than a finished
 * sentence: the importer runs off the main thread and has no context of its
 * own, so the screen that shows the message is the one that resolves it, and a
 * user who changes the app language sees the new wording.
 */
sealed interface FontImportResult {
    data class Imported(val font: InstalledFont) : FontImportResult

    /** Not a font at all, or a format Android's text stack can't load. */
    data class NotAFont(@StringRes val messageRes: Int) : FontImportResult

    /**
     * Over [FontStore.maxBytes], unreadable, or nowhere to write.
     *
     * [messageArgs] fills the format arguments of [messageRes], in order.
     */
    data class Failed(
        @StringRes val messageRes: Int,
        val messageArgs: List<Any> = emptyList(),
    ) : FontImportResult

    /** [FontStore.MAX_FONTS] reached. */
    data object TooManyFonts : FontImportResult
}

/**
 * Reading a `.ttf`/`.otf` into the [FontStore].
 *
 * Two checks, in order. The sfnt magic in the first four bytes rejects the
 * common mistakes — a zip, a WOFF downloaded from a web font page, an HTML
 * error page saved with a `.ttf` name — cheaply and with a message that says
 * which mistake it was. Then the file is handed to the platform to actually
 * load, because a file can carry a valid header and still have a table the
 * renderer chokes on; finding that out now means the picker can say so,
 * instead of the keyboard silently falling back to the system face later.
 *
 * That second check is injected rather than called directly so the logic is
 * testable off-device — the same reason `StickerPackFile.import` takes its
 * image normaliser as a parameter.
 */
object FontFile {

    const val FILE_EXTENSION = "ttf"

    /** Permissive: providers report a font as `application/octet-stream` as often as not. */
    val IMPORT_MIME_TYPES = arrayOf(
        "font/ttf",
        "font/otf",
        "application/x-font-ttf",
        "application/x-font-opentype",
        "application/font-sfnt",
        "application/octet-stream",
    )

    /**
     * Confirms the platform can build a typeface from the file. Defaults to
     * `Typeface.createFromFile`, which throws on a font it can't parse.
     */
    val PLATFORM_LOADER: (File) -> Boolean = { file ->
        runCatching { android.graphics.Typeface.createFromFile(file) != null }.getOrDefault(false)
    }

    /**
     * Reads [input] in as a font named [name], registering it under a fresh id
     * so importing the same file twice can never collide with itself.
     */
    fun import(
        input: InputStream,
        store: FontStore,
        name: String,
        author: String = "",
        version: String = "",
        langIds: List<String> = emptyList(),
        emoji: Boolean = false,
        now: Long = System.currentTimeMillis(),
        loader: (File) -> Boolean = PLATFORM_LOADER,
        readVariations: (File) -> FontVariations? = FontAxesReader::read,
    ): FontImportResult {
        if (store.fonts().size >= FontStore.MAX_FONTS) return FontImportResult.TooManyFonts
        val staging = store.stagingDir()
            ?: return FontImportResult.Failed(R.string.core_content_font_error_no_storage)
        val staged = File(staging, "font.ttf")

        try {
            var written = 0L
            staged.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > FontStore.maxBytes(emoji)) {
                        return FontImportResult.Failed(
                            R.string.core_content_font_error_too_large,
                            listOf(FontStore.maxBytes(emoji) / (1024 * 1024)),
                        )
                    }
                    sink.write(buffer, 0, read)
                }
            }
            if (written == 0L) {
                return FontImportResult.NotAFont(R.string.core_content_font_error_empty)
            }

            magicErrorRes(staged)?.let { return FontImportResult.NotAFont(it) }
            if (!loader(staged)) {
                return FontImportResult.NotAFont(R.string.core_content_font_error_load)
            }

            // Read now, from the staged copy, so the picker knows whether to
            // offer the axes the moment the font lands.
            val variations = readVariations(staged) ?: FontVariations.STATIC
            val id = store.freeId(now)
            if (!store.adoptFile(id, staged)) {
                return FontImportResult.Failed(R.string.core_content_font_error_save)
            }
            val font = store.adopt(
                InstalledFont(
                    id = id,
                    name = name.trim().ifBlank { "Font" },
                    author = author.trim(),
                    version = version.trim(),
                    fileName = FontStore.fileNameFor(id),
                    addedAt = now,
                    langIds = langIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    emoji = emoji,
                    variations = variations,
                ),
            )
            if (font == null) {
                store.fileFor(id)?.delete()
                return FontImportResult.TooManyFonts
            }
            return FontImportResult.Imported(font)
        } catch (_: Exception) {
            // The exception's own message is developer text in whatever
            // language the platform wrote it, so the user gets ours instead.
            return FontImportResult.Failed(R.string.core_content_font_error_read)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Null when the first four bytes look like a font this platform loads;
     * otherwise the string resource that names what the file actually is.
     *
     * WOFF and WOFF2 get their own message because they are the likely mistake:
     * they are what a browser's network tab hands you for a web font, and
     * nothing on Android reads them.
     */
    @StringRes
    private fun magicErrorRes(file: File): Int? {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) return R.string.core_content_font_error_too_short

        val tag = String(header, Charsets.ISO_8859_1)
        val asInt = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)

        return when {
            // 0x00010000 TrueType · OTTO CFF/OpenType · "true"/"ttcf" Apple.
            asInt == 0x00010000 -> null
            tag == "OTTO" || tag == "true" || tag == "ttcf" -> null
            tag == "wOFF" -> R.string.core_content_font_error_woff
            tag == "wOF2" -> R.string.core_content_font_error_woff2
            tag.startsWith("PK") -> R.string.core_content_font_error_zip
            tag.startsWith("<") -> R.string.core_content_font_error_markup
            else -> R.string.core_content_font_error_not_a_font
        }
    }
}
