package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Movie
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayCircleOutline
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * Paste chips whose time ran out, tucked at the end of the suggestion strip
 * (#1565) instead of gone, while [ClipboardSettings.tuckExpiredChip] is on;
 * with [ClipboardSettings.tuckAfterSeconds] set, new chips tuck sooner, or
 * at once, and only open full size when tapped.
 *
 * Kept by the service, which tucks a chip when its timer fires and takes one
 * out when it is brought back; provided around the keyboard like
 * [LocalClipStripActions], so the strip reaches it without another parameter
 * through `KeyboardScreen`. The list lives in memory only: like the chip it
 * came from, a tucked clip does not outlive the keyboard's process.
 */
@Stable
class TuckedClips(
    /** A tucked clip tapped, or let go on after a slide: it goes back on the strip as the paste chip. */
    val onRestore: (ClipItem) -> Unit,
) {
    /** Newest first, at most [MAX]. */
    val items = mutableStateListOf<ClipItem>()

    /** Puts [clip] on top of the stack, out of wherever it was in it. */
    fun tuck(clip: ClipItem) {
        items.removeAll { it.id == clip.id }
        items.add(0, clip)
        while (items.size > MAX) items.removeAt(items.lastIndex)
    }

    /** Takes [id] off the stack, when it is there. */
    fun remove(id: Long) {
        items.removeAll { it.id == id }
    }

    companion object {
        /** How many tucked clips wait at once; the oldest goes when another comes. */
        const val MAX = 4
    }
}

val LocalTuckedClips = staticCompositionLocalOf<TuckedClips?> { null }

/** What a tucked clip is, for its icon and colour. */
private enum class TuckedKind { TEXT, LINK, VIDEO_LINK, PICTURE, VIDEO, FILE }

private fun tuckedKind(clip: ClipItem): TuckedKind = when (clip.kind) {
    ClipKind.IMAGE -> TuckedKind.PICTURE
    ClipKind.VIDEO -> TuckedKind.VIDEO
    ClipKind.FILE, ClipKind.FOLDER -> TuckedKind.FILE
    ClipKind.LINK -> if (isVideoLink(clip.text)) TuckedKind.VIDEO_LINK else TuckedKind.LINK
    ClipKind.TEXT, ClipKind.HTML -> {
        val text = clip.text.trim()
        when {
            !text.startsWith("http://") && !text.startsWith("https://") -> TuckedKind.TEXT
            text.any { it.isWhitespace() } -> TuckedKind.TEXT
            isVideoLink(text) -> TuckedKind.VIDEO_LINK
            else -> TuckedKind.LINK
        }
    }
}

/** A link to a video site the user is likely to want told apart from other links. */
private fun isVideoLink(text: String): Boolean {
    val host = text.trim().substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
    return VideoHosts.any { host == it || host.endsWith(".$it") }
}

private val VideoHosts = listOf("youtube.com", "youtu.be", "vimeo.com", "twitch.tv", "dailymotion.com")

private fun TuckedKind.icon(): ImageVector = when (this) {
    TuckedKind.TEXT -> Icons.Outlined.Notes
    TuckedKind.LINK -> Icons.Outlined.Link
    TuckedKind.VIDEO_LINK -> Icons.Outlined.PlayCircleOutline
    TuckedKind.PICTURE -> Icons.Outlined.Image
    TuckedKind.VIDEO -> Icons.Outlined.Movie
    TuckedKind.FILE -> Icons.Outlined.Description
}

/**
 * The colour a kind wears: the theme's own ink for text, its accent for a
 * link, and a fixed hue each for the rest, muted by being drawn over the
 * chip colour, so the kinds stay apart on any theme.
 */
private fun TuckedKind.tint(kb: KbTheme): Color = when (this) {
    TuckedKind.TEXT -> kb.toolbarIcon
    TuckedKind.LINK -> kb.accent
    TuckedKind.VIDEO_LINK -> Color(0xFFE5534B)
    TuckedKind.PICTURE -> Color(0xFF4CAF7A)
    TuckedKind.VIDEO -> Color(0xFFB37FEB)
    TuckedKind.FILE -> Color(0xFFE0A84A)
}

/** The size of one tucked clip's circle, and how much of the one under it each covers. */
private val TuckedSize = 26.dp
private val TuckedOverlap = 12.dp

/**
 * The tucked clips (#1565), at the strip's end: a small stack of circles, one
 * per clip, each with its kind's icon and colour, the newest on top at the
 * right. A tap brings back the one under the finger. Slid along, the stack
 * opens the clip under the finger into a pill beside it, showing what it
 * holds, and closes it again as the finger moves on to the next; letting go
 * brings back the one that is open. Nothing is drawn while the stack is empty
 * or the setting is off, and only clips still in the history are offered.
 */
@Composable
internal fun TuckedClipsStack(state: KeyboardUiState) {
    val tucked = LocalTuckedClips.current ?: return
    if (!state.settings.clipboard.tuckExpiredChip || !state.settings.clipboard.suggestRecent) return
    val inHistory = remember(state.clipboardItems) { state.clipboardItems.mapTo(HashSet()) { it.id } }
    val showing = state.clipboardSuggestion?.id
    val clips = tucked.items.filter { it.id in inHistory && it.id != showing && !it.sensitive }
    if (clips.isEmpty()) return
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // Oldest at the left, newest last: drawn last, so on top, and nearest the edge.
    val drawn = clips.asReversed()
    var open by remember { mutableStateOf<Long?>(null) }
    val drawnNow by rememberUpdatedState(drawn)
    val restore by rememberUpdatedState(tucked.onRestore)
    val stepPx = with(LocalDensity.current) { (TuckedSize - TuckedOverlap).toPx() }
    val sizePx = with(LocalDensity.current) { TuckedSize.toPx() }
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val opened = drawn.firstOrNull { it.id == open }
        if (opened != null) TuckedPreview(opened, kb)
        // One box as wide as the overlapped circles, each set in by a step,
        // so the finger's x names the circle under it by simple division.
        Box(
            modifier = Modifier
                .width(TuckedSize + (TuckedSize - TuckedOverlap) * (drawn.size - 1))
                .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun at(x: Float): ClipItem? {
                        val list = drawnNow
                        if (list.isEmpty()) return null
                        // The circles overlap, so the one on top at x is the
                        // last whose left edge x has passed.
                        val index = (x / stepPx).toInt().coerceIn(0, list.lastIndex)
                        return if (x > (list.lastIndex * stepPx) + sizePx) list.last() else list[index]
                    }
                    var current = at(down.position.x)
                    open = current?.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            // The service buzzes for the restore itself.
                            current?.let { clip -> restore(clip) }
                            break
                        }
                        val next = at(change.position.x)
                        if (next?.id != current?.id) {
                            current = next
                            open = next?.id
                            feedback()
                        }
                    }
                    open = null
                }
            },
        ) {
            for ((index, clip) in drawn.withIndex()) {
                TuckedCircle(
                    clip, kb, index, isOpen = clip.id == open,
                    modifier = Modifier.offset(x = (TuckedSize - TuckedOverlap) * index),
                ) { restore(clip) }
            }
        }
    }
}

/** One tucked clip's circle. */
@Composable
private fun TuckedCircle(
    clip: ClipItem,
    kb: KbTheme,
    index: Int,
    isOpen: Boolean,
    modifier: Modifier,
    onRestore: () -> Unit,
) {
    val kind = tuckedKind(clip)
    val tint = kind.tint(kb)
    val label = tuckedLabel(clip, kind)
    val description = stringResource(R.string.ime_tucked_clip_desc, label)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .zIndex(index.toFloat())
            .size(TuckedSize)
            .background(kb.chip, CircleShape)
            .background(tint.copy(alpha = if (isOpen) 0.38f else 0.2f), CircleShape)
            .border(1.dp, kb.board.copy(alpha = 0.6f), CircleShape)
            .semantics {
                contentDescription = description
                onClick {
                    onRestore()
                    true
                }
            },
    ) {
        Icon(kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** What the open clip holds, in a pill left of the stack. */
@Composable
private fun TuckedPreview(clip: ClipItem, kb: KbTheme) {
    val kind = tuckedKind(clip)
    val tint = kind.tint(kb)
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 6.dp)
            .widthIn(max = 180.dp)
            .background(kb.chip, shape)
            .background(tint.copy(alpha = 0.14f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            tuckedLabel(clip, kind),
            color = kb.clipChipInk(),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The words a tucked clip is shown by: its text on one line, or what it is when it has none. */
@Composable
private fun tuckedLabel(clip: ClipItem, kind: TuckedKind): String = when (kind) {
    TuckedKind.PICTURE -> stringResource(R.string.ime_tucked_clip_picture)
    TuckedKind.VIDEO, TuckedKind.FILE -> clip.fileName ?: stringResource(R.string.ime_tucked_clip_file)
    else -> clip.text.trim().replace(Whitespace, " ").take(80)
}

private val Whitespace = Regex("\\s+")
