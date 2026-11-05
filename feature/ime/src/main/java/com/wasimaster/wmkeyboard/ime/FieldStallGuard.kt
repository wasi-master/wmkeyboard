package com.wasimaster.wmkeyboard.ime

import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi

/**
 * Stops a field that has stopped answering from freezing the keyboard.
 *
 * Every read of the field (text before or after the caret, the selection,
 * the caps mode) is a binder call the keyboard's main thread waits on, and
 * the platform gives up on one only after two seconds, returning null. One
 * keystroke makes several such reads: a delete reads the text before the
 * caret, then the word before it again to restart suggestions. When the app
 * behind the field is busy (a chat app laying out a long thread, a browser
 * mid-load), the keystroke waits out one timeout after another, and past five
 * seconds the system reports the keyboard itself as not responding. Most of
 * the keyboard's ANRs on Play were that, in a dozen different callers.
 *
 * So the first read that takes [SLOW_READ_MS] marks the field as stalled,
 * and for [HOLD_MS] after it reads answer at once with what a timed-out read
 * would have returned: null, or no caps. Callers already handle those,
 * because the platform hands them the same thing on a timeout. Edits are not
 * touched: they are one-way calls that never wait. The hold ends early once
 * the field shows it is alive again ([noteResponsive], on its next selection
 * report). Off the clock nothing changes, so a healthy field costs two clock
 * reads per read.
 */
internal class FieldStallGuard(
    base: InputConnection,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : InputConnectionWrapper(base, true) {

    /** Reads answer without asking the field until this time; 0 when not stalled. */
    @Volatile
    private var stalledUntil = 0L

    /** Whether reads are being skipped right now. */
    val stalled: Boolean get() = clock() < stalledUntil

    /** The field answered on its own (a selection report): ask it again. */
    fun noteResponsive() {
        stalledUntil = 0L
    }

    private inline fun <T> read(skipped: T, call: () -> T): T {
        val start = clock()
        if (start < stalledUntil) return skipped
        val result = call()
        val end = clock()
        if (end - start >= SLOW_READ_MS) stalledUntil = end + HOLD_MS
        return result
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        read(null) { super.getTextBeforeCursor(n, flags) }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        read(null) { super.getTextAfterCursor(n, flags) }

    override fun getSelectedText(flags: Int): CharSequence? =
        read(null) { super.getSelectedText(flags) }

    override fun getCursorCapsMode(reqModes: Int): Int =
        read(0) { super.getCursorCapsMode(reqModes) }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        read(null) { super.getExtractedText(request, flags) }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        read(null) { super.getSurroundingText(beforeLength, afterLength, flags) }

    companion object {
        /**
         * A read this slow means the app's main thread is far behind. Half
         * the platform's own two-second timeout, so a read that times out
         * always counts, and so does one that only just made it.
         */
        const val SLOW_READ_MS = 1_000L

        /** How long reads are skipped after a slow one, unless the field speaks first. */
        const val HOLD_MS = 3_000L
    }
}
