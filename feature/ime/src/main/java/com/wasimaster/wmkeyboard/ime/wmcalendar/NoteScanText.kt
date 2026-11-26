package com.wasimaster.wmkeyboard.ime.wmcalendar

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings

/**
 * A scanned page read into a note (#1586), with the reader the OCR tool
 * would use for the keyboard's first language that one can read: ML Kit,
 * PaddleOCR or a Tesseract pack already on the phone. Never an online
 * reader and nothing downloaded, the rule [ImageText] keeps for WM
 * Calendar; null when nothing on the phone can read the page now.
 */
object NoteScanText {
    suspend fun read(context: Context, page: Bitmap, settings: KeyboardSettings): String? =
        runCatching { ImageText.recognize(context, page, settings) }.getOrNull()
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n")?.takeIf { it.isNotBlank() }
}
