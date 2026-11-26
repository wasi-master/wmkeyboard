package com.wasimaster.wmkeyboard.ime.aichat

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.ime.ocr.MlKitRecognizers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * Reads the text in a picture sent in the AI chat (#698), for a model that
 * cannot see it. ML Kit's recognizer on the phone, the same ones the scan
 * text tool and the reply tool use, for the script of the language the user
 * types in (#757).
 */
object ChatImageOcr {

    /** This build carries ML Kit's text recognizers, and Google's libraries are not switched off. */
    val available: Boolean get() = BuildConfig.ENABLE_ML_KIT_SCANNERS && GoogleLibraries.usable

    /** The text in [bitmap], line by line; empty when there is none. Throws when the read fails. */
    suspend fun read(bitmap: Bitmap, script: MlKitScript = MlKitScript.LATIN): String {
        if (!available) return ""
        // A build with native delivery may still have to fetch the reader's library.
        check(MlKitRecognizers.awaitReady(script)) { "the text reader's library is not available" }
        val recognizer = MlKitRecognizers.client(script)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /** A Play Services task as a suspension, as [com.wasimaster.wmkeyboard.ime.reply.ReplyOcr] does. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel(CancellationException("ML Kit task cancelled")) }
    }
}
