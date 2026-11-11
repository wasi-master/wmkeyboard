package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.capClipText
import com.wasimaster.wmkeyboard.core.clipboard.effectiveMaxTextChars

/**
 * What the system clipboard holds right now, reduced to what [holdsClip]
 * compares: [text] for a text clip, [uri] for a picture or a file.
 */
internal data class SystemClip(val text: String?, val uri: String?) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && uri == null
}

/**
 * Whether [primary], the system clipboard, still holds [item] (#442). A clip
 * deleted from the panel should leave the clipboard with it, but only when it
 * is really the one there: deleting an older entry must not wipe whatever the
 * user copied since.
 *
 * Text is compared the way the history stored it, trimmed and cut to
 * [maxTextChars] (or the store's hard cap, #1215), so a clip too long to
 * keep whole still matches. A picture's
 * bytes live in the history's own file, and the address it came from is not
 * kept, so the newest clip being a picture ([isNewest]) is what stands for the
 * picture on the clipboard.
 */
internal fun holdsClip(
    primary: SystemClip,
    item: ClipItem,
    isNewest: Boolean,
    maxTextChars: Int,
): Boolean = when {
    item.kind.isTextual -> primary.text?.let { capClipText(it.trim(), effectiveMaxTextChars(maxTextChars)).trimEnd() == item.text } == true
    item.uriString != null -> primary.uri == item.uriString
    item.kind == ClipKind.IMAGE -> isNewest && primary.uri != null
    else -> false
}

/**
 * Whether deleting [removed] from the history should clear the system
 * clipboard too (#442). [kept] is what stays in the history, for the Clear
 * button, which empties the clipboard whatever it holds unless that is one of
 * the pinned clips it leaves in place ([clearAll]). A single delete clears it
 * only when it holds the deleted clip.
 */
internal fun clearsSystemClip(
    primary: SystemClip,
    removed: List<ClipItem>,
    kept: List<ClipItem>,
    clearAll: Boolean,
    maxTextChars: Int,
): Boolean {
    if (primary.isEmpty) return false
    val newestTime = (removed + kept).maxOfOrNull { it.timestamp }
    fun holds(item: ClipItem) = holdsClip(primary, item, item.timestamp == newestTime, maxTextChars)
    if (clearAll) return kept.none(::holds)
    return removed.any(::holds)
}
