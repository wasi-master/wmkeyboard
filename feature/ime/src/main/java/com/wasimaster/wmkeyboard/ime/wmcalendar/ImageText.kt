package com.wasimaster.wmkeyboard.ime.wmcalendar

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcr
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.ocr.TesseractOcr
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.ime.ocr.MlKitOcr

/**
 * A picture WM Calendar hands over, read into text with the keyboard's own text reader on the
 * phone: ML Kit, PaddleOCR or a Tesseract pack, as the OCR tool would read the keyboard's first
 * language that one of them can read. Never an online reader, and nothing is downloaded: with
 * no engine and pack ready on the phone the read fails.
 */
internal object ImageText {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object NoText : Result
        data object Failed : Result
    }

    /** WM Calendar's FileProvider, which serves the picture it copied for the read. */
    const val FILES_AUTHORITY = "com.wasimaster.wmcalendar.files"

    /** At most this large on the longest side, as the OCR panel reads a picked picture. */
    private const val MAX_SIDE = 4096

    /** A file past this is refused before it is decoded; WM Calendar sends at most 12 MB. */
    private const val MAX_BYTES = 16L * 1024 * 1024

    suspend fun read(context: Context, uri: Uri, settings: KeyboardSettings): Result {
        // Only WM Calendar's own files: never a URI the keyboard could open but WM Calendar could not.
        if (uri.scheme != "content" || uri.authority != FILES_AUTHORITY) return Result.Failed
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: return Result.Failed
        if (size > MAX_BYTES) return Result.Failed
        val bitmap = MediaPrep.decode(context, uri, MAX_SIDE) ?: return Result.Failed
        return try {
            val text = recognize(context, bitmap, settings) ?: return Result.Failed
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) Result.NoText else Result.Text(lines.joinToString("\n"))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Failed
        } finally {
            bitmap.recycle()
        }
    }

    /** The text, or null when nothing on the phone can read it now. */
    private suspend fun recognize(context: Context, bitmap: Bitmap, s: KeyboardSettings): String? {
        // An online choice still reads on the phone here: the picture never leaves it.
        val engine = OcrLanguages.usable(s.scanner.ocrEngine, TesseractOcr.available).let { if (it == OcrEngine.ONLINE) OcrEngine.AUTO else it }
        val filesDir = context.filesDir
        val onDisk: (String) -> Boolean = { OcrPacks.isDownloaded(filesDir, it) }
        val language = s.enabledLanguages.firstOrNull {
            OcrLanguages.packFor(it) != null && OcrLanguages.readable(engine, it, TesseractOcr.available, onDisk)
        } ?: return null
        val pack = OcrLanguages.offlinePack(engine, language, TesseractOcr.available, onDisk)
        return when {
            pack == PaddleOcrModel.PACK -> {
                if (PaddleOcr.moduleState.value != ModuleState.Installed || !onDisk(pack)) return null
                // Freed again unless the OCR panel had it loaded; a later read loads it again anyway.
                val loaded = PaddleOcr.isLoaded
                PaddleOcr.recognize(context, bitmap).also { if (!loaded) PaddleOcr.release() }
            }
            pack != null -> {
                if (TesseractOcr.moduleState.value != ModuleState.Installed || !onDisk(pack)) return null
                val loaded = TesseractOcr.loadedPack() != null
                TesseractOcr.recognize(filesDir, pack, bitmap).also { if (!loaded) TesseractOcr.release() }
            }
            MlKitOcr.IN_BUILD -> {
                val script = OcrLanguages.mlKitRecognizer(language)
                // Latin's library may still be waiting to download; it is not fetched for this.
                if (script == MlKitScript.LATIN && !MlKitOcr.ready(script)) return null
                MlKitOcr.Readers().use { readers ->
                    readers.prepare(script)
                    readers.read(script, bitmap).joinToString("\n") { it.joinToString(" ") }
                }
            }
            else -> null
        }
    }
}
