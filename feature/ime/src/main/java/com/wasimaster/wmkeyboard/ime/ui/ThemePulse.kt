package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.math.ln

/**
 * The typing a theme can answer: where the last few keys were pressed and
 * when. The key grid writes it on every press while the theme has a reactive
 * board effect or key lighting; the board effect turns it into energy, and the
 * lighting into light spreading from the keys.
 *
 * One per process, like the keyboard it serves. Positions are in root space,
 * so each layer reading it subtracts its own origin. A press is a few array
 * writes and one state bump, which is what restarts a lighting layer that went
 * idle when the typing stopped.
 */
internal object ThemePulse {

    private const val CAPACITY = 32

    /**
     * How much one press adds to the energy before it decays. A third, so a
     * single key already shows on a reactive board and three in quick
     * succession reach full; at 0.14 one press moved nothing visible (#1129).
     */
    internal const val KICK = 0.35f

    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private var head = 0

    /** Bumped on every press; read by a layer that sleeps between presses. */
    var revision by mutableIntStateOf(0)
        private set

    fun press(rootCenter: Offset, now: Long = SystemClock.uptimeMillis()) {
        xs[head] = rootCenter.x
        ys[head] = rootCenter.y
        times[head] = now
        head = (head + 1) % CAPACITY
        revision++
    }

    /** The newest press's time, 0 before any. */
    val lastPressAt: Long
        get() = times[(head - 1 + CAPACITY) % CAPACITY]

    /**
     * 0..1: how hard the keys are being typed on right now, every press adding
     * a kick that halves every [halfLifeMs].
     */
    fun energy(now: Long, halfLifeMs: Int): Float {
        val k = ln(2.0) / halfLifeMs.coerceAtLeast(1)
        var sum = 0.0
        for (i in 0 until CAPACITY) {
            val t = times[i]
            if (t == 0L) continue
            val age = now - t
            if (age < 0) continue
            sum += KICK * exp(-age * k)
        }
        return sum.toFloat().coerceAtMost(1f)
    }

    /**
     * Calls [each] with every press younger than [maxAgeMs], newest first: its
     * position in root space and its age in milliseconds.
     */
    inline fun forEachRecent(now: Long, maxAgeMs: Long, each: (x: Float, y: Float, ageMs: Long) -> Unit) {
        for (n in 1..CAPACITY) {
            val i = (headIndex() - n + CAPACITY) % CAPACITY
            val t = timeAt(i)
            if (t == 0L) break
            val age = now - t
            if (age > maxAgeMs) break
            if (age >= 0) each(xAt(i), yAt(i), age)
        }
    }

    @PublishedApi internal fun headIndex(): Int = head
    @PublishedApi internal fun timeAt(i: Int): Long = times[i]
    @PublishedApi internal fun xAt(i: Int): Float = xs[i]
    @PublishedApi internal fun yAt(i: Int): Float = ys[i]
}
