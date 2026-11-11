package com.wasimaster.wmkeyboard.ime

import java.util.concurrent.ConcurrentHashMap

/**
 * Tokens for clips the keyboard writes and must not record twice: a tidied
 * copy written back to the system clipboard, or an Undo putting a clip back
 * (#1215, from saen1a's prototype). A token is good for a short window, used
 * once, and carries no content.
 *
 * It replaces comparing the text: the listener used to let one copy go by
 * when the clipboard's text matched what the keyboard had just written, which
 * also let a real copy of the same text go by when the keyboard's own write
 * never produced a callback, and left nothing to compare for a picture.
 *
 * [ClipboardWriteGate] puts the token in `ClipData.description.extras`; this
 * object owns its expiry and the one-shot lookup.
 */
internal object SelfWriteLedger {
    /** Long enough for the listener's callback, short enough that a stale token never hides a copy. */
    const val TTL_MILLIS = 5_000L

    /** The most tokens kept; a burst of writes past it drops the oldest. */
    private const val MAX_TOKENS = 64

    private val tokens = ConcurrentHashMap<String, Long>()

    fun record(token: String, now: Long = System.currentTimeMillis()) {
        prune(now)
        if (tokens.size >= MAX_TOKENS) tokens.entries.minByOrNull { it.value }?.let { tokens.remove(it.key) }
        tokens[token] = now + TTL_MILLIS
    }

    /** True once for a token [record] took within its window; false for anything else. */
    fun consume(token: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isNullOrEmpty()) return false
        prune(now)
        val expiresAt = tokens.remove(token) ?: return false
        return expiresAt >= now
    }

    fun clear() = tokens.clear()

    private fun prune(now: Long) {
        tokens.entries.removeAll { it.value < now }
    }
}
