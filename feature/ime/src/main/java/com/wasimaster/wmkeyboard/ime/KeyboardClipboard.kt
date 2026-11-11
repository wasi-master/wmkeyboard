package com.wasimaster.wmkeyboard.ime

import android.content.Context

/**
 * Where the keyboard's own copies go (#392).
 *
 * Normally that is the system clipboard. While incognito keeps the clipboard
 * private ([isPrivate], set by the service from `incognitoPrivateClipboard`,
 * or always with the clipboard's private copies on, #612), a copy stays here
 * instead, in memory, and the keyboard's paste keys paste it back. Nothing the
 * keyboard copies reaches the system clipboard, where every other app could
 * read it. Leaving the private mode forgets the clip. Outside incognito the
 * service also files it in the clipboard history, through [onPrivateCopy].
 *
 * An object rather than service state because panels copy too (the password
 * generator, the scanner), and they only have a [Context].
 */
object KeyboardClipboard {
    /** Whether copies stay inside the keyboard. Turning it off forgets [held]. */
    @Volatile
    var isPrivate: Boolean = false
        set(value) {
            field = value
            if (!value) held = null
        }

    /** The last private copy, or null. Never set while [isPrivate] is off. */
    @Volatile
    var held: String? = null
        private set

    /**
     * Told of every private copy, so the service can keep it in the history
     * the system clipboard's listener never hears about, and of whether the
     * copier called it a secret (#1215), the flag a system copy would carry.
     * Null outside a running service.
     */
    @Volatile
    var onPrivateCopy: ((String, Boolean) -> Unit)? = null

    /**
     * A copy made anywhere else (#612): the system clipboard now holds
     * something newer than [held], so the paste keys go back to it. Without
     * this a private copy outranked every later copy in every other app, and
     * the paste keys kept pasting it.
     */
    fun yieldToSystem() {
        held = null
    }

    /**
     * Makes [text] what the paste keys paste next while private (#1178),
     * without telling [onPrivateCopy]: the clip is already in the history and
     * stays where it is there. False outside the private mode.
     */
    fun holdForPaste(text: String): Boolean {
        if (!isPrivate) return false
        held = text
        return true
    }

    /** Forgets [held] when it is a clip [gone] says was deleted (#612). */
    fun forgetIf(gone: (String) -> Boolean) {
        val kept = held ?: return
        if (gone(kept)) held = null
    }

    /**
     * Copies [text]: into [held] while private, else onto the system clipboard
     * through [ClipboardWriteGate], marked as a secret when [sensitive] (#1215).
     */
    fun copy(context: Context, text: CharSequence, label: String = "", sensitive: Boolean = false) {
        if (isPrivate) {
            val kept = text.toString()
            held = kept
            onPrivateCopy?.invoke(kept, sensitive)
            return
        }
        ClipboardWriteGate.writeText(context, text, label, sensitive = sensitive)
    }
}
