package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import com.wasimaster.wmkeyboard.core.script.LanguageDef

/** One sampled point of a handwriting stroke, in canvas pixels. */
data class HwPoint(val x: Float, val y: Float, val t: Long)

/** A finished stroke: the points between one touch-down and its release. */
data class HwStroke(val points: List<HwPoint>)

data class HandwritingLanguage(val tag: String, val displayName: String)

/** Lite-flavor stand-in: nothing downloads, so nothing ever progresses. */
data class HandwritingDownloadProgress(
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val stalledForMs: Long = 0,
) {
    val fraction: Float? get() = null
}

/** Lite-flavor stand-in: no models ship, so none has a size. */
object HandwritingModelSizes {
    suspend fun installedBytes(context: Context, tag: String): Long = 0
}

/**
 * Lite-flavor stand-in: no ML Kit, so no models exist and nothing can be
 * downloaded. The handwriting tool is hidden in lite builds; these members
 * only keep the shared callers (IME, panel, settings) compiling.
 */
object HandwritingModels {

    /** No downloadable models in lite builds, whatever the user types in. */
    fun modelsFor(languages: List<LanguageDef>): List<HandwritingLanguage> = emptyList()

    fun tagFor(language: LanguageDef): String? = null

    /** Same tag mapping as the full flavor, so UI state stays coherent. */
    fun tagForLangId(langId: String): String = if (langId == "en") "en-US" else langId

    fun shortLabel(tag: String): String = when (tag) {
        "en-US" -> "EN"
        "bn" -> "বাং"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        else -> tag.uppercase()
    }

    fun displayName(tag: String): String = tag

    suspend fun isDownloaded(tag: String): Boolean = false

    suspend fun download(
        context: Context,
        tag: String,
        onProgress: (HandwritingDownloadProgress) -> Unit = {},
    ) {
        error("Handwriting is not available in the lite build")
    }

    suspend fun delete(tag: String) = Unit
}

/** Lite-flavor stand-in; recognition is never reachable without ML Kit. */
class HandwritingRecognizerCache {

    suspend fun recognize(
        tag: String,
        strokes: List<HwStroke>,
        preContext: String,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
        maxCandidates: Int = 4,
    ): List<String> =
        error("Handwriting is not available in the lite build")

    fun close() = Unit
}
