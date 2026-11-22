package com.wasimaster.wmkeyboard.ime.pointer

import android.content.Context
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wasimaster.wmkeyboard.core.accessibility.TouchMouseService
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.core.shell.ShellFeatures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which door the mouse's clicks go through right now. */
enum class PointerDoor {
    /** Shizuku or Sui: real mouse events. */
    SHELL,

    /** The touch mouse accessibility service: touches at the pointer. */
    GESTURES,

    /** Neither is set up: the pointer moves, a click does nothing. */
    NONE,
}

/**
 * The Trackpad tool's mouse mode (#1411), service side: the pointer over the
 * app and the door its clicks go through.
 *
 * The trackpad surface drives it with moves, clicks and scrolls in its own
 * pixels; this turns them into the pointer's place in the band above the
 * board, and into screen coordinates for the door. [begin] when the surface
 * shows in mouse mode, [end] when it goes, the mode changes or the keyboard
 * does: nothing held outlives the finger that held it.
 *
 * The pointer lives in the window's band above the board, so it needs a
 * docked keyboard with that band ([offered]). Mouse mode is off on the lock
 * screen: a keyboard there must not click around behind it.
 */
class PointerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decor: () -> View?,
    /** The board's top inside the window, 0 when unknown. */
    private val boardTop: () -> Int,
    /** Whether mouse mode may run now: the channel, the lock screen, a docked board. */
    private val allowed: () -> Boolean,
) {
    private val density = context.resources.displayMetrics.density
    private val overlay = PointerOverlay(density)
    private var x = Float.NaN
    private var y = Float.NaN
    private var pressed = false
    private val location = IntArray(2)

    /** Finger travel per wheel notch, in pixels, at 100 % scroll speed. */
    var notchPx: Float = NOTCH_DP * density

    private val shell = ShellRoute(displayId = { decor()?.display?.displayId ?: 0 }, notchPx = { notchPx })
    private val gestures = GestureRoute(band = ::bandOnScreen)

    /** The door in use, for the surface's hint. Compose state: the surface reads it. */
    var door by mutableStateOf(PointerDoor.NONE)
        private set

    /** Whether the pointer is up. */
    var active by mutableStateOf(false)
        private set

    /** Whether mouse mode can be offered on this field now. */
    fun offered(): Boolean = allowed() && bandHeight() >= MIN_BAND_DP * density

    private fun bandHeight(): Int {
        val top = boardTop()
        return if (top > 0) top else 0
    }

    /** The Shizuku switch and grant, read once per [begin]: a preference read per move is waste. */
    private var shellAllowed = false

    private fun shellWanted(): Boolean = ShellFeatures.isEnabled(context) && ShellAccess.canInject

    private fun pickDoor(): PointerDoor = when {
        shellAllowed && ShellAccess.connected -> PointerDoor.SHELL
        TouchMouseService.current != null -> PointerDoor.GESTURES
        else -> PointerDoor.NONE
    }

    /**
     * The door for the next event, picked live: Shizuku's service can bind or
     * die, and the accessibility service can be turned on, while the panel is
     * up. A door changing mid-drag lets go of what the old one held.
     */
    private val route: PointerRoute?
        get() {
            val now = pickDoor()
            if (now != door) {
                when (door) {
                    PointerDoor.SHELL -> shell.cancel()
                    PointerDoor.GESTURES -> gestures.cancel()
                    PointerDoor.NONE -> Unit
                }
                door = now
            }
            return when (now) {
                PointerDoor.SHELL -> shell
                PointerDoor.GESTURES -> gestures
                PointerDoor.NONE -> null
            }
        }

    fun begin() {
        if (!offered()) {
            end()
            return
        }
        val view = decor() ?: return
        if (x.isNaN() || y.isNaN() || y >= bandHeight()) {
            x = view.width / 2f
            y = bandHeight() / 2f
        }
        clamp(view)
        overlay.show(view, x, y)
        active = true
        shellAllowed = shellWanted()
        door = pickDoor()
        if (shellAllowed && !ShellAccess.connected) {
            scope.launch {
                if (ShellAccess.connect(context) && active) door = pickDoor()
            }
        }
    }

    fun end() {
        if (pressed) release()
        shell.cancel()
        gestures.cancel()
        overlay.hide()
        active = false
    }

    /** Moves the pointer by ([dx], [dy]) window pixels, already scaled. */
    fun moveBy(dx: Float, dy: Float) {
        val view = decor() ?: return
        if (!active) return
        x += dx
        y += dy
        clamp(view)
        overlay.moveTo(x, y)
        val sx = screenX(view)
        val sy = screenY(view)
        if (pressed) route?.dragTo(sx, sy) else route?.hover(sx, sy)
    }

    fun click(times: Int = 1) = at { sx, sy -> route?.click(sx, sy, times) }

    fun rightClick() = at { sx, sy -> route?.rightClick(sx, sy) }

    /** The button goes down for a drag and stays down until [release]. */
    fun press() {
        if (pressed) return
        pressed = true
        overlay.setPressed(true)
        at { sx, sy -> route?.press(sx, sy) }
    }

    fun release() {
        if (!pressed) return
        pressed = false
        overlay.setPressed(false)
        at { sx, sy -> route?.release(sx, sy) }
    }

    /** Two fingers travelled ([dx], [dy]) pixels on the surface, already scaled and signed for the user's direction. */
    fun scrollBy(dx: Float, dy: Float) = at { sx, sy -> route?.scroll(sx, sy, dx, dy) }

    fun scrollEnd() = at { sx, sy -> route?.scrollEnd(sx, sy) }

    private inline fun at(block: (Float, Float) -> Unit) {
        val view = decor() ?: return
        if (!active) return
        block(screenX(view), screenY(view))
    }

    private fun clamp(view: View) {
        x = com.wasimaster.wmkeyboard.ime.clampToBand(x, 0f, view.width.toFloat())
        y = com.wasimaster.wmkeyboard.ime.clampToBand(y, 0f, bandHeight().toFloat())
    }

    private fun screenX(view: View): Float {
        view.getLocationOnScreen(location)
        return location[0] + x
    }

    private fun screenY(view: View): Float {
        view.getLocationOnScreen(location)
        return location[1] + y
    }

    /** The band above the board, in screen pixels: where a virtual finger may go. */
    private fun bandOnScreen(): RectF {
        val view = decor() ?: return RectF()
        view.getLocationOnScreen(location)
        return RectF(
            location[0].toFloat(), location[1].toFloat(),
            (location[0] + view.width).toFloat(), (location[1] + bandHeight()).toFloat(),
        )
    }

    companion object {
        /** Less band than this above the board and there is nothing worth pointing at. */
        private const val MIN_BAND_DP = 96

        /** Finger travel per wheel notch at 100 % scroll speed. */
        const val NOTCH_DP = 18f
    }
}
