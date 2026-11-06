package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.annotation.RequiresApi

/**
 * Takes the system keyboard bar's own view away where the phone leaves it
 * drawn after the keyboard asked for it to go (#891).
 *
 * From Android 16 the bar under the keyboard (hide chevron, switch button) is
 * a `NavigationBarFrame` the platform adds to the keyboard's own window, and
 * AOSP hides it from an insets listener on that frame: caption bar not
 * visible, frame gone. A Moto G86 Power on Android 16 reports the caption bar
 * hidden once the keyboard asks (`caption 0px (shown false)` in Diagnostics)
 * and still draws the frame, so that listener never runs there or never
 * hides it. This applies the same rule from the keyboard's side: while the
 * keyboard wants the bar gone and the window says the caption bar is not
 * visible, the frame is made GONE, checked before every frame in case the
 * build shows it again. A phone whose window still reports the caption bar
 * visible is left alone, since the space for the bar is still reserved there
 * and hiding the frame would only leave an empty band.
 *
 * The frame is found by class name, its own or a superclass's, among the
 * window's views outside the keyboard's input area; nothing in it is called
 * but [View.setVisibility].
 */
@RequiresApi(Build.VERSION_CODES.R)
class KeyboardBarFrameGuard(private val log: (String) -> Unit) {
    private var frame: View? = null
    private var observer: ViewTreeObserver? = null
    private var hide = false
    private var hiddenByGuard = false
    private var lastLogged: String? = null
    private val onPreDraw = ViewTreeObserver.OnPreDrawListener {
        enforce()
        true
    }

    /** Called with the keyboard window's decor view whenever the keyboard asks the bar to show or go. */
    fun update(decorView: View?, hide: Boolean) {
        this.hide = hide
        val decor = decorView as? ViewGroup ?: return
        val found = findFrame(decor, depth = 0)
        if (found !== frame) {
            detach()
            frame = found
            hiddenByGuard = false
            if (found != null) {
                observer = decor.viewTreeObserver.also { it.addOnPreDrawListener(onPreDraw) }
            }
        }
        if (hide) {
            enforce()
        } else if (hiddenByGuard) {
            // The platform's own listener decides again from the insets.
            hiddenByGuard = false
            frame?.visibility = View.VISIBLE
            decor.requestApplyInsets()
        }
        report(decor, found)
    }

    /** Drops the frame and its listener, as when the keyboard's window goes. */
    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(onPreDraw)
        observer = null
        frame = null
    }

    private fun enforce() {
        val view = frame ?: return
        if (!hide || view.visibility == View.GONE) return
        val insets = view.rootWindowInsets ?: return
        if (insets.isVisible(WindowInsets.Type.captionBar())) return
        view.visibility = View.GONE
        if (!hiddenByGuard) {
            hiddenByGuard = true
            log("frame hidden by the keyboard (${view.javaClass.name}, the phone left it drawn)")
        }
    }

    /** One Diagnostics line per change in what was found, so the next report says what the window holds. */
    private fun report(decor: ViewGroup, found: View?) {
        val line = if (found != null) {
            "frame ${found.javaClass.name}, ${visibilityName(found.visibility)}, ${found.height}px, " +
                "caption shown ${found.rootWindowInsets?.isVisible(WindowInsets.Type.captionBar())}"
        } else {
            "no frame in the window (${childNames(decor)})"
        }
        if (line == lastLogged) return
        lastLogged = line
        log(line)
    }

    private fun findFrame(group: ViewGroup, depth: Int): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (isKeyboardBarFrame(child)) return child
        }
        if (depth >= MAX_DEPTH) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? ViewGroup ?: continue
            if (child.id == android.R.id.inputArea || child.id == android.R.id.extractArea) continue
            findFrame(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isKeyboardBarFrame(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null && type != View::class.java) {
            if (type.simpleName == FRAME_CLASS) return true
            type = type.superclass
        }
        return false
    }

    private fun childNames(decor: ViewGroup): String =
        (0 until decor.childCount).joinToString { decor.getChildAt(it).javaClass.simpleName }

    private fun visibilityName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "visible"
        View.INVISIBLE -> "invisible"
        else -> "gone"
    }

    private companion object {
        const val FRAME_CLASS = "NavigationBarFrame"
        const val MAX_DEPTH = 2
    }
}
