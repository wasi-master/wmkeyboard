package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the text on screen when the user asks the AI about it (#697), or
 * taps a word to look it up (#765).
 *
 * An input method sees its own field and nothing else, and the floating AI
 * button's whole point is a question about everything else: the article, the
 * chat, the error dialog. Window content is only handed to an accessibility
 * service, so this is one, with exactly that one power and nothing else:
 *
 *  * it reads nothing on its own. The service is idle until [ScreenReader.read] or
 *    [ScreenReader.textAt] is called: [ScreenReader.read] only from the floating button's Ask AI,
 *    [ScreenReader.textAt] only from its Look up, once the user has tapped a word;
 *  * what [ScreenReader.read] reads is the visible text of the app in front, once, handed
 *    to the AI chat as the message's attachment, where the user sees it
 *    before anything is sent. What [ScreenReader.textAt] reads is the one piece of text
 *    under the finger, with where its characters are, for the Look up card;
 *  * it performs no actions, injects no input and keeps no copy.
 *
 * It runs in the app's main process, beside the keyboard, so [ScreenReader.read] is a plain
 * in-process call through [current]. Separate from [TouchPassthroughService]
 * on purpose: that one promises it cannot read the screen, and turning on
 * TalkBack gestures must never be the same switch as letting the AI see.
 */
class ScreenContextService : AccessibilityService() {

    private val part = ScreenReader(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = part
    }

    override fun onDestroy() {
        if (current === part) current = null
        super.onDestroy()
    }

    /** Events are not used; the tree is read on demand in [ScreenReader.read]. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** What [ScreenReader.read] found: whose screen, and the text on it. */
    data class Screen(val packageName: String, val text: String)

    /**
     * What [ScreenReader.textAt] found. [text] is a window of the tapped text and
     * [offset] the tapped character in it (-1 when it could not be placed).
     * [rects] are screen boxes, four ints each, of the characters from
     * [rectsFrom] in [text] on; [bounds] is the text's own box.
     */
    class Tap(
        val packageName: String,
        val text: String,
        val offset: Int,
        val rects: IntArray?,
        val rectsFrom: Int,
        val bounds: IntArray,
    )

    companion object {
        /** The most text one read hands over; a long page is cut, not refused. */
        const val MAX_CHARS = 12_000

        /** Characters [ScreenReader.textAt] keeps on each side of the tap. */
        internal const val TAP_WINDOW = 400

        /** Character boxes kept on each side of the tap, for marking the word. */
        internal const val TAP_RECTS = 24

        /** Characters measured per request, and at most in all. */
        internal const val BOX_SLICE = 500
        internal const val MAX_MEASURED = 10_000

        /** Nodes a read or a tap looks through before it gives up. */
        internal const val MAX_NODES = 4_000

        /**
         * The connected reader, from this service or the combined
         * [WMAccessibilityService], or null while neither is on.
         */
        @Volatile
        var current: ScreenReader? = null
            internal set

        /**
         * Whether the user has turned the service on in Settings › Accessibility,
         * this one or the combined one standing in for it. Read from the secure
         * setting rather than [current], which is only set once the system has
         * bound it.
         */
        fun isEnabled(context: Context): Boolean = AccessibilityEntries.isOn(context, ScreenContextService::class.java)
    }
}

/** What [ScreenContextService] does, for whichever service hosts it. */
class ScreenReader internal constructor(private val host: AccessibilityService) {

    /**
     * The app in front, as its package and its visible text in reading order,
     * or null when there is no app window to read. The keyboard's own window
     * and its floating buttons are skipped: the question is about the app.
     *
     * Every node is a call into the app's process, and in a Chromium browser
     * the first one starts the page's accessibility engine (#1462), so this
     * blocks for as long as the app takes to answer: never call it on the
     * main thread. A page of empty boxes never reaches [ScreenContextService.MAX_CHARS], so the
     * walk also stops at [MAX_NODES].
     */
    fun read(): ScreenContextService.Screen? {
        val root = appRoot() ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val lines = ArrayList<String>()
        var length = 0
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && length < ScreenContextService.MAX_CHARS && visited < ScreenContextService.MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            // A password field's text is dots at best; never pass it on.
            if (text.isNotEmpty() && !node.isPassword && lines.lastOrNull() != text) {
                lines.add(text)
                length += text.length + 1
            }
            // Children pushed last-first so they pop in screen order.
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { stack.addLast(it) }
            }
        }
        val body = lines.joinToString("\n").take(ScreenContextService.MAX_CHARS)
        return ScreenContextService.Screen(packageName, body)
    }

    /**
     * The root of the app window the user is looking at. The active window is
     * normally it, since neither the keyboard nor the floating buttons take
     * focus; the window list is the fallback for the moments it is not.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        host.rootInActiveWindow?.takeIf { it.packageName?.toString() != host.packageName }?.let { return it }
        return runCatching {
            host.windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != host.packageName }
        }.getOrNull()
    }

    /**
     * The text under a tap at ([x], [y]) on screen (#765): the smallest
     * visible piece of text whose box holds the point, cut to a window round
     * the tapped character. Null when no text is there.
     *
     * Where the app reports its characters' boxes (a plain text view does;
     * Compose and web pages often do not), [Tap.offset] is the character
     * under the finger, or the nearest on its line, and [Tap.rects] the boxes
     * round it for the card to mark the word. Without them the offset is -1
     * and the card lets the user pick the word from the text.
     */
    fun textAt(x: Int, y: Int): ScreenContextService.Tap? {
        val root = appRoot() ?: return null
        val node = nodeAt(root, x, y) ?: return null
        val full = node.text?.toString() ?: node.contentDescription?.toString() ?: return null
        if (full.isBlank()) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        val boxes = if (node.text != null) characterBoxes(node, full.length, x, y) else null
        val offset = boxes?.let { indexAt(it, x, y) } ?: -1
        val start = if (offset >= 0) (offset - ScreenContextService.TAP_WINDOW).coerceAtLeast(0) else 0
        val end = if (offset >= 0) (offset + ScreenContextService.TAP_WINDOW).coerceAtMost(full.length) else full.length.coerceAtMost(ScreenContextService.TAP_WINDOW * 2)
        var rects: IntArray? = null
        var rectsFrom = 0
        if (boxes != null && offset >= 0) {
            val from = (offset - ScreenContextService.TAP_RECTS).coerceAtLeast(0)
            val until = (offset + ScreenContextService.TAP_RECTS).coerceAtMost(full.length)
            rectsFrom = from - start
            rects = IntArray((until - from) * 4)
            for (index in from until until) {
                val box = boxes.getOrNull(index) ?: continue
                val at = (index - from) * 4
                rects[at] = box.left.toInt()
                rects[at + 1] = box.top.toInt()
                rects[at + 2] = box.right.toInt()
                rects[at + 3] = box.bottom.toInt()
            }
        }
        return ScreenContextService.Tap(
            packageName = root.packageName?.toString().orEmpty(),
            text = full.substring(start, end),
            offset = if (offset >= 0) offset - start else -1,
            rects = rects,
            rectsFrom = rectsFrom,
            bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
    }

    /** The smallest visible, non-password node with text whose box holds the point. */
    private fun nodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val box = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < ScreenContextService.MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(box)
            if (!box.contains(x, y)) continue
            val hasText = !node.isPassword &&
                (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            val area = box.width().toLong() * box.height()
            if (hasText && area <= bestArea) {
                best = node
                bestArea = area
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { stack.addLast(it) }
        }
        return best
    }

    /**
     * The screen boxes of [node]'s characters, asked for a slice at a time
     * until one holds the point, so a long text is not measured whole. Null
     * when the app does not report them.
     */
    private fun characterBoxes(node: AccessibilityNodeInfo, length: Int, x: Int, y: Int): Array<RectF?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (key !in node.availableExtraData) return null
        val out = arrayOfNulls<RectF>(length)
        var start = 0
        while (start < length && start < ScreenContextService.MAX_MEASURED) {
            val count = minOf(ScreenContextService.BOX_SLICE, length - start)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count)
            }
            if (!node.refreshWithExtraData(key, args)) return null
            @Suppress("DEPRECATION")
            val slice = node.extras.getParcelableArray(key) ?: return null
            var hit = false
            slice.forEachIndexed { i, parcel ->
                val box = parcel as? RectF ?: return@forEachIndexed
                out[start + i] = box
                if (box.contains(x.toFloat(), y.toFloat())) hit = true
            }
            if (hit) {
                // The boxes after the tap, for a word that runs on past the slice.
                val next = start + count
                if (next < length) {
                    val tail = Bundle().apply {
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, next)
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, minOf(ScreenContextService.TAP_RECTS, length - next))
                    }
                    if (node.refreshWithExtraData(key, tail)) {
                        @Suppress("DEPRECATION")
                        node.extras.getParcelableArray(key)?.forEachIndexed { i, parcel -> out[next + i] = parcel as? RectF }
                    }
                }
                return out
            }
            start += count
        }
        return out
    }

    /** The character under the point, else the nearest one on the same line, else null. */
    private fun indexAt(boxes: Array<RectF?>, x: Int, y: Int): Int? {
        val px = x.toFloat()
        val py = y.toFloat()
        boxes.indexOfFirst { it != null && it.contains(px, py) }.takeIf { it >= 0 }?.let { return it }
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        boxes.forEachIndexed { index, box ->
            if (box == null || py < box.top || py > box.bottom || box.width() <= 0f) return@forEachIndexed
            val distance = if (px < box.left) box.left - px else px - box.right
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}
