package com.wasimaster.wmkeyboard.ime.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * The scanner panel's way to ML Kit's text recognizers, so the panel itself
 * (in `src/main`) names nothing of ML Kit's and F-Droid's build, which has
 * none, compiles it against the stand-in in `src/nogoogle`. There the panel
 * reads with PaddleOCR or online only, and never asks this for anything.
 */
internal object MlKitOcr {

    /**
     * Whether ML Kit's recognizers can be used: this build has them, and
     * Google's libraries are not switched off ([GoogleLibraries]).
     */
    val IN_BUILD: Boolean get() = GoogleLibraries.usable

    /** Whether [script]'s reader can run now; see [MlKitRecognizers.ready]. */
    fun ready(script: MlKitScript): Boolean = MlKitRecognizers.ready(script)

    /** True when [failure] means Play services is still downloading the model. */
    fun isDownloading(failure: Throwable?): Boolean = MlKitRecognizers.isDownloading(failure)

    /** One recognizer per script read so far, made on first use, closed together. */
    class Readers : AutoCloseable {
        private val recognizers = mutableMapOf<MlKitScript, TextRecognizer>()

        /** [script]'s recognizer, made now, so a read later finds it ready. */
        fun prepare(script: MlKitScript) {
            synchronized(recognizers) { recognizers.getOrPut(script) { MlKitRecognizers.client(script) } }
        }

        /** The words in [bitmap], line by line, as [script]'s recognizer reads them. */
        suspend fun read(script: MlKitScript, bitmap: Bitmap): List<List<String>> {
            val recognizer = synchronized(recognizers) { recognizers.getOrPut(script) { MlKitRecognizers.client(script) } }
            return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                .textBlocks
                .flatMap { block -> block.lines.map { line -> line.elements.map { it.text } } }
        }

        override fun close() {
            synchronized(recognizers) {
                recognizers.values.forEach { runCatching { it.close() } }
                recognizers.clear()
            }
        }
    }
}

/**
 * Suspends over a Play Services [Task]. A failed task throws its exception:
 * cancelling the continuation instead would unwind the capture as if the
 * panel had closed, and leave it on the reading spinner.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
