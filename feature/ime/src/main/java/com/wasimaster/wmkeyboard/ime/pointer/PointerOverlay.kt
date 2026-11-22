package com.wasimaster.wmkeyboard.ime.pointer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The mouse pointer of the Trackpad tool's mouse mode (#1411), drawn on the
 * keyboard's own window.
 *
 * The docked keyboard's window is the whole screen tall (#1123), the band
 * above the board see-through and passing every touch to the app. The
 * pointer is a drawable in that window's view overlay: it takes no touch,
 * joins no layout and recomposes nothing, so moving it is a bounds change and
 * one redraw of the overlay. No permission is needed to draw it, and it is
 * gone the moment the keyboard is.
 */
internal class PointerOverlay(private val density: Float) {

    private val arrow = ArrowDrawable(density)
    private var host: View? = null

    /** Puts the pointer up on [decor]'s overlay at ([x], [y]) in window pixels. */
    fun show(decor: View, x: Float, y: Float) {
        if (host !== decor) {
            hide()
            decor.overlay.add(arrow)
            host = decor
        }
        moveTo(x, y)
    }

    /** Moves the tip to ([x], [y]), window pixels. */
    fun moveTo(x: Float, y: Float) {
        val left = x.toInt()
        val top = y.toInt()
        arrow.setBounds(left, top, left + arrow.intrinsicWidth, top + arrow.intrinsicHeight)
        arrow.invalidateSelf()
    }

    /** Shows the pointer pressed (a button held for a drag) or not. */
    fun setPressed(pressed: Boolean) {
        if (arrow.pressed == pressed) return
        arrow.pressed = pressed
        arrow.invalidateSelf()
    }

    fun hide() {
        host?.overlay?.remove(arrow)
        host = null
    }

    val showing: Boolean get() = host != null
}

/**
 * The classic arrow, white with a dark edge so it reads over any app, tip at
 * the drawable's top-left corner. Pressed, it fills with a light grey.
 */
private class ArrowDrawable(density: Float) : Drawable() {

    private val size = (22 * density).toInt()
    private val edge = 1.5f * density
    var pressed = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = edge
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(230, 20, 20, 20)
    }
    private val path = Path()

    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = size.toFloat()
        val x = b.left + edge
        val y = b.top + edge
        path.reset()
        // The outline of the usual pointer, on a 16-unit grid.
        val u = (s - 2 * edge) / 16f
        path.moveTo(x, y)
        path.lineTo(x, y + 14 * u)
        path.lineTo(x + 3.6f * u, y + 10.6f * u)
        path.lineTo(x + 6.2f * u, y + 15.6f * u)
        path.lineTo(x + 8.4f * u, y + 14.6f * u)
        path.lineTo(x + 5.9f * u, y + 9.7f * u)
        path.lineTo(x + 10.6f * u, y + 9.7f * u)
        path.close()
        fill.color = if (pressed) Color.rgb(200, 200, 200) else Color.WHITE
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
