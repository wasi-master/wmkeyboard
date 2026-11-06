package com.wasimaster.wmkeyboard.ime.launch

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.wasimaster.wmkeyboard.ime.R

/**
 * The stand-in app calibration launches ([RouteProbe]): a small card saying
 * what is going on, which reports its own window bounds a few times while the
 * window manager settles it, then is closed by the prober. Resizeable, so it
 * may float and split like any well-behaved app. Not exported; no task of its
 * own survives it (`finishAndRemoveTask`).
 */
class RouteProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val card = TextView(this).apply {
            text = getString(R.string.launch_probe_message)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(0xE0202124.toInt())
            }
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0x99000000.toInt())
                addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            },
        )
        RouteProbe.attach(this)
    }

    override fun onResume() {
        super.onResume()
        reportSoon()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        reportSoon()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reportSoon()
    }

    /** Now, and twice more while an animation may still be resizing the window. */
    private fun reportSoon() {
        report()
        window.decorView.postDelayed(::report, REPORT_GAP_MS)
        window.decorView.postDelayed(::report, REPORT_GAP_MS * 2)
    }

    private fun report() {
        if (isFinishing) return
        val window: Rect
        val screen: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window = windowManager.currentWindowMetrics.bounds
            screen = windowManager.maximumWindowMetrics.bounds
        } else {
            val metrics = resources.displayMetrics
            val config = resources.configuration
            window = Rect(
                0, 0,
                (config.screenWidthDp * metrics.density).toInt(),
                (config.screenHeightDp * metrics.density).toInt(),
            )
            @Suppress("DEPRECATION")
            val real = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            screen = Rect(0, 0, real.widthPixels, real.heightPixels)
        }
        RouteProbe.report(ProbeReport(window.width(), window.height(), screen.width(), screen.height()))
    }

    private companion object {
        const val REPORT_GAP_MS = 300L
    }
}
