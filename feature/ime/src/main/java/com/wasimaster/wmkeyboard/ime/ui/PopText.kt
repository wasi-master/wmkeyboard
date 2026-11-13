package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.PopTextMode

/**
 * "Pop text" (gap list item 45, AnySoftKeyboard's): what was typed floats up
 * out of the keyboard and fades. A decoration and nothing more, so it is built
 * to cost the typing path nothing:
 *
 *  - The service and the key grid hand it text through [key] and [word], which
 *    are plain calls on the main thread. Nothing here is keyboard state, and
 *    the keys never read it, so a pop recomposes no key.
 *  - The overlay is composed only while a pop is alive, the way the press
 *    particles are, and each frame only redraws it.
 *
 * [mode] and [still] are written by the grid from the settings; with the mode
 * at [PopTextMode.NEVER] or motion stilled (reduce motion, or power saving's
 * animation drop, which sets the same flag) every call returns at once.
 */
internal class PopTextField(private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() }) {

    /** One floating text: where it rose from, in root space, and when it appeared. */
    class Pop(val text: String, val x: Float, val y: Float, val word: Boolean, val correction: Boolean) {
        /** Uptime of its first frame; -1 until the overlay has drawn it once. */
        var bornAt = -1L
    }

    var mode: PopTextMode = PopTextMode.NEVER
    var still: Boolean = false

    /** Where a finished word rises from, in root space: the space bar's centre. Null for nowhere. */
    var wordAnchor: () -> Offset? = { null }

    internal val pops = ArrayList<Pop>()

    /** Any pop alive; the overlay is composed only while this holds. */
    var active by mutableStateOf(false)
        private set

    /** Bumped every frame, read by the draw alone. */
    var revision by mutableIntStateOf(0)
        private set

    /** The frame time the draw measures each pop's age against. */
    var nowMs = 0L
        private set

    /** A key typed [text]; it rises from [center], the key's centre in root space. */
    fun key(text: String, center: Offset) {
        if (!popTextWanted(mode, still, word = false, corrected = false) || text.isBlank()) return
        add(Pop(text, center.x, center.y, word = false, correction = false))
    }

    /** A word was finished, [corrected] when autocorrect changed it. */
    fun word(text: String, corrected: Boolean) {
        if (!popTextWanted(mode, still, word = true, corrected = corrected) || text.isBlank()) return
        val at = wordAnchor() ?: return
        add(Pop(text, at.x, at.y, word = true, correction = corrected))
    }

    private fun add(pop: Pop) {
        // A burst of fast typing must not pile up text the eye cannot follow.
        if (pops.size >= MAX_POPS) pops.removeAt(0)
        pops += pop
        nowMs = clock()
        active = true
    }

    /** One frame at [now]: dates new pops, drops spent ones, and says whether any are left. */
    fun frame(now: Long): Boolean {
        nowMs = now
        val iterator = pops.iterator()
        while (iterator.hasNext()) {
            val pop = iterator.next()
            if (pop.bornAt < 0) pop.bornAt = now
            if (now - pop.bornAt >= lifeOf(pop)) iterator.remove()
        }
        revision++
        active = pops.isNotEmpty()
        return active
    }

    companion object {
        const val MAX_POPS = 8
        const val KEY_LIFE_MS = 600L
        const val WORD_LIFE_MS = 900L

        fun lifeOf(pop: Pop): Long = if (pop.word) WORD_LIFE_MS else KEY_LIFE_MS
    }
}

/**
 * Whether [mode] shows a pop: never while [still]; a key's only on
 * [PopTextMode.EVERY_KEY]; a word on [PopTextMode.WORDS] and up; a corrected
 * word on everything but [PopTextMode.NEVER].
 */
internal fun popTextWanted(mode: PopTextMode, still: Boolean, word: Boolean, corrected: Boolean): Boolean {
    if (still) return false
    return when (mode) {
        PopTextMode.NEVER -> false
        PopTextMode.CORRECTIONS -> word && corrected
        PopTextMode.WORDS -> word
        PopTextMode.EVERY_KEY -> true
    }
}

/**
 * Where a pop is at [fraction] of its life: how far it has risen, as a share of
 * its full rise, and its alpha. It eases out as it rises, and stays opaque for
 * the first third before fading, so a short word can be read.
 */
internal fun popTextFrame(fraction: Float): Pair<Float, Float> {
    val t = fraction.coerceIn(0f, 1f)
    val rise = 1f - (1f - t) * (1f - t)
    // Opaque for the first third, then a straight fade to nothing at the end.
    val alpha = ((1f - t) * 1.5f).coerceAtMost(1f)
    return rise to alpha
}

/** The grid's pop field, provided by the service; null in a grid on its own, which then keeps its own. */
internal val LocalPopText = staticCompositionLocalOf<PopTextField?> { null }

/**
 * Draws the live pops over the key grid, [origin] being the grid's place in
 * root space. Composed only while one is alive; its frame loop ends with them.
 */
@Composable
internal fun BoxScope.PopTextOverlay(
    field: PopTextField,
    origin: () -> Offset,
    keyColor: Color,
    wordColor: Color,
    correctionColor: Color,
) {
    if (!field.active) return
    val measurer = rememberTextMeasurer(cacheSize = PopTextField.MAX_POPS * 2)
    LaunchedEffect(field) {
        while (field.active) {
            withFrameMillis { field.frame(it) }
        }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
        field.revision
        val now = field.nowMs
        val at = origin()
        for (pop in field.pops) {
            val born = pop.bornAt
            if (born < 0) continue
            val (rise, alpha) = popTextFrame((now - born) / PopTextField.lifeOf(pop).toFloat())
            val layout = measurer.measure(
                pop.text,
                TextStyle(
                    fontSize = if (pop.word) 16.sp else 22.sp,
                    fontWeight = if (pop.word) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            val risePx = (if (pop.word) WORD_RISE_DP else KEY_RISE_DP) * density * rise
            drawText(
                layout,
                color = when {
                    pop.correction -> correctionColor
                    pop.word -> wordColor
                    else -> keyColor
                },
                topLeft = Offset(
                    pop.x - at.x - layout.size.width / 2f,
                    pop.y - at.y - layout.size.height / 2f - risePx,
                ),
                alpha = alpha,
            )
        }
    }
}

private const val KEY_RISE_DP = 56f
private const val WORD_RISE_DP = 72f
