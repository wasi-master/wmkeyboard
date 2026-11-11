package com.wasimaster.wmkeyboard.app

import android.content.Context
import java.io.File

/**
 * Scanned pages on their way from a scanner activity to the keyboard. Both
 * scanners hand over the same way: Google's (DocScanActivity, through ML
 * Kit) and the built-in one ([PageScanActivity], on OpenCV) write the pages
 * as JPEGs into [dir] and park them here; the keyboard commits them in
 * onStartInputView, as the app behind the scanner gets its field back.
 */
object DocScanPages {
    private val pending = ArrayDeque<File>()

    /** Hands over (and clears) whatever the last scan produced. */
    fun consume(): List<File> = synchronized(pending) {
        val pages = pending.toList()
        pending.clear()
        pages
    }

    /** The pages of a finished scan, replacing any the keyboard never picked up. */
    fun offer(pages: List<File>) = synchronized(pending) {
        pending.clear()
        pending.addAll(pages)
    }

    fun dir(context: Context): File = File(context.filesDir, "docscan").apply { mkdirs() }

    /**
     * Old scans only pile up if the process died before the keyboard could
     * commit them; keeps the directory from growing unbounded.
     */
    fun prune(context: Context) {
        // Files only: the Paperless queue (#1198) keeps its own folder in here.
        dir(context).listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { it.delete() }
    }
}
