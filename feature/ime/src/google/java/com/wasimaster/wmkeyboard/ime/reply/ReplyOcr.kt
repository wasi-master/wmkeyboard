package com.wasimaster.wmkeyboard.ime.reply

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.google.android.gms.tasks.Task
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.ime.ocr.MlKitRecognizers
import com.wasimaster.wmkeyboard.core.tools.ReplyOcrLines
import com.wasimaster.wmkeyboard.core.tools.ReplyTurn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * Reads a chat screenshot into messages, for a model that cannot see (#234).
 *
 * The provider the user has chosen may take pictures, in which case it reads
 * the screenshot itself and this is never called. When it cannot — an
 * on-device model, Brave, DeepSeek, or a user who asked for it to stay on the
 * device — the words still have to get there somehow, and this is that route.
 *
 * ## Who said what
 *
 * OCR gives back lines and boxes, and the thing that actually says who wrote a
 * message is which side of the screen its bubble sits on. So the side is the
 * guess: a line whose box sits past [ReplyOcrLines.MINE_FROM] of the width is
 * the user's own.
 * It is only a guess — a centred system notice, a wide bubble, a tablet layout
 * all defeat it — so the panel draws the label on every line and a tap flips
 * it. Being able to fix it is the feature; being right most of the time is the
 * convenience.
 */
object ReplyOcr {

    /** This build carries ML Kit's text recognizers. */
    val available: Boolean get() = BuildConfig.ENABLE_ML_KIT_SCANNERS && GoogleLibraries.usable

    /**
     * [bitmap] read as a conversation, oldest first, from the part of it
     * [frame] says is the chat, with the recognizer for [script]: the
     * keyboard's language, as the best guess at the chat's (#757).
     *
     * Returns an empty list when nothing legible was found, which the caller
     * reports as "no text found" rather than as a failure — a photo of a sunset
     * is not an error, it is just not a conversation. Which lines are chrome
     * and which belong to one bubble is [ReplyOcrLines]'s, where it is tested.
     */
    suspend fun read(
        bitmap: Bitmap,
        frame: ReplyOcrLines.ReplyOcrFrame = ReplyOcrLines.ReplyOcrFrame(),
        script: MlKitScript = MlKitScript.LATIN,
    ): List<ReplyTurn> {
        if (!available) return emptyList()
        // A build with native delivery may still have to fetch the reader's library.
        check(MlKitRecognizers.awaitReady(script)) { "the text reader's library is not available" }
        val recognizer = MlKitRecognizers.client(script)
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val lines = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    ReplyOcrLines.Line(line.text, box.left, box.top, box.right, box.bottom)
                }
            ReplyOcrLines.messages(lines, bitmap.width, bitmap.height, frame)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * Suspends over a Play Services [Task], the bridge `kotlinx-coroutines-
     * play-services` would give: that artifact is kept out of the keyboard
     * (see the note in `gradle/libs.versions.toml`), so the house writes the
     * few lines itself, as the scanner panel does. A failed task throws its
     * exception; a cancelled one cancels the caller.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel(CancellationException("ML Kit task cancelled")) }
    }
}
