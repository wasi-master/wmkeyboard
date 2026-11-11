package com.wasimaster.wmkeyboard.ime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Content-free counters for the clipboard capture path (#1215, from saen1a's
 * prototype).
 *
 * "My copy is not in the history" is almost always a capture that never
 * reached the store: the keyboard was not allowed to read the clipboard, a
 * gate held it back, the copy was the keyboard's own, or a cap dropped it.
 * None of those needs the clip to diagnose, and none of them should put one
 * in a log. This object counts events only; [summary] is safe for the debug
 * log and a support report, and it is what the service writes there.
 */
object ClipboardDiagnostics {
    private enum class Counter(val key: String) {
        CAPTURE_ATTEMPTS("capture_attempts"),
        SELF_WRITE_SKIPS("self_write_skips"),
        GATE_DROPS("gate_drops"),
        SENSITIVE_DROPS("sensitive_drops"),
        STORED_TEXT("stored_text"),
        STORED_IMAGE("stored_image"),
        STORED_FILES("stored_file"),
        STORED_PRIVATE("stored_private"),
        ABORTED_IMAGE("aborted_image"),
        OVERSIZED_IMAGE("oversized_image"),
    }

    /** One entry per counter, so adding one cannot leave [reset] or [summary] behind. */
    private val counters = ConcurrentHashMap<Counter, AtomicLong>().apply {
        Counter.entries.forEach { put(it, AtomicLong()) }
    }

    private fun add(counter: Counter, delta: Long = 1L): Long = counters.getValue(counter).addAndGet(delta)

    fun onCaptureAttempt() = add(Counter.CAPTURE_ATTEMPTS)

    fun onSelfWriteSkip() = add(Counter.SELF_WRITE_SKIPS)

    /** Held back by history being off, incognito, a password field or a locked phone. */
    fun onGateDrop() = add(Counter.GATE_DROPS)

    /** Left out under "Never save it". */
    fun onSensitiveDrop() = add(Counter.SENSITIVE_DROPS)

    fun onStoredText() = add(Counter.STORED_TEXT)

    fun onStoredImage() = add(Counter.STORED_IMAGE)

    fun onStoredFiles(count: Int = 1) = add(Counter.STORED_FILES, count.toLong())

    fun onStoredPrivate() = add(Counter.STORED_PRIVATE)

    /** A picture that was not kept: unreadable in the copying app, or past the cap. */
    fun onAbortedImageCapture() = add(Counter.ABORTED_IMAGE)

    /** A picture past [com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore.HARD_MAX_IMAGE_BYTES]. */
    fun onOversizedImage() = add(Counter.OVERSIZED_IMAGE)

    /** Clears every counter. Tests only. */
    fun reset() {
        counters.values.forEach { it.set(0) }
    }

    fun summary(): String =
        Counter.entries.joinToString(separator = " ") { "${it.key}=${counters.getValue(it).get()}" }
}
