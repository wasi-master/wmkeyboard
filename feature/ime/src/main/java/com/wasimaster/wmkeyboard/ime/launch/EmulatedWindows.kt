package com.wasimaster.wmkeyboard.ime.launch

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Experimental floating windows for phones with none of their own (stock
 * Pixel, Nothing, most Motorolas, HyperOS 3's blocked mode 5): the app runs
 * on a virtual display drawn inside an overlay window, the way Mi-FreeForm
 * 2.5 does it. Opt-in only (**When a real floating window isn't possible**),
 * because it is a stand-in, not the real thing:
 *
 *  * the app is started on the display as the shell (`am start --display`),
 *    and touches are injected into it as the shell, so it needs Shizuku;
 *  * the window is an overlay, so it needs **Display over other apps**;
 *  * typing inside the window may raise the keyboard on the main screen or
 *    not at all, secure apps draw black, and some apps refuse a second
 *    display outright.
 *
 * Closing or expanding the window releases the display; Android then moves
 * the app's task back to the main screen, where expand brings it to the front.
 */
object EmulatedWindows {

    private const val SIZE_FRACTION = 0.62f
    private const val BAR_DP = 36
    private const val CORNER_DP = 14

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val open = mutableListOf<Window>()

    /** What the setting needs before it can be picked. */
    fun ready(context: Context): Boolean = Settings.canDrawOverlays(context) && ShellAccess.canInject

    suspend fun open(context: Context, component: ComponentName, userId: Int?): Boolean {
        if (!ready(context) || !ShellAccess.connect(context)) return false
        val app = context.applicationContext
        val window = Window(app, component, userId)
        return if (window.show()) {
            open += window
            true
        } else {
            false
        }
    }

    private class Window(val context: Context, val component: ComponentName, val userId: Int?) {
        private val wm = context.getSystemService(WindowManager::class.java)
        private val density = context.resources.displayMetrics.density
        private var display: VirtualDisplay? = null
        private var surface: Surface? = null
        private lateinit var root: LinearLayout
        private lateinit var params: WindowManager.LayoutParams

        @SuppressLint("ClickableViewAccessibility")
        fun show(): Boolean = runCatching {
            val screen = LaunchOptions.screenBounds(context)
            val width = (screen.width() * SIZE_FRACTION).roundToInt()
            val height = (screen.height() * SIZE_FRACTION).roundToInt()
            val texture = TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attach(st, w, h)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        display?.resize(w, h, context.resources.displayMetrics.densityDpi)
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                setOnTouchListener { _, event -> forward(event) }
            }
            root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = CORNER_DP * density
                    setColor(0xFF202124.toInt())
                }
                clipToOutline = true
                addView(titleBar(), LinearLayout.LayoutParams(-1, (BAR_DP * density).toInt()))
                addView(texture, LinearLayout.LayoutParams(width, height))
            }
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screen.width() - width) / 2
                y = (screen.height() - height) / 3
            }
            wm.addView(root, params)
            true
        }.getOrDefault(false)

        private fun attach(st: SurfaceTexture, w: Int, h: Int) {
            val dm = context.getSystemService(DisplayManager::class.java)
            val s = Surface(st).also { surface = it }
            val vd = dm.createVirtualDisplay(
                "wm-window-${component.packageName}",
                w, h, context.resources.displayMetrics.densityDpi, s,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            ) ?: return close(moveToMain = false)
            display = vd
            val id = vd.display.displayId
            scope.launch {
                val cmd = "am start -n ${component.flattenToShortString()} -f 0x10000000 --display $id" +
                    (userId?.let { " --user $it" } ?: "")
                if (ShellAccess.exec(context, cmd)?.ok != true) close(moveToMain = false)
            }
        }

        private fun titleBar(): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = runCatching {
                context.packageManager.getApplicationInfo(component.packageName, 0).loadLabel(context.packageManager)
            }.getOrNull() ?: component.packageName
            addView(button("‹", R.string.launch_emulated_back) { back() })
            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    isSingleLine = true
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(button("⤢", R.string.launch_emulated_expand) { close(moveToMain = true) })
            addView(button("✕", R.string.launch_emulated_close) { close(moveToMain = false) })
            setOnTouchListener(dragger())
        }

        private fun button(glyph: String, description: Int, onClick: () -> Unit) = TextView(context).apply {
            text = glyph
            contentDescription = context.getString(description)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val pad = (10 * density).toInt()
            setPadding(pad, 0, pad, 0)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

        /** Drags the window by its title bar. */
        private fun dragger() = object : View.OnTouchListener {
            var startX = 0f
            var startY = 0f
            var originX = 0
            var originY = 0

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX
                        startY = e.rawY
                        originX = params.x
                        originY = params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = originX + (e.rawX - startX).roundToInt()
                        params.y = originY + (e.rawY - startY).roundToInt()
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                }
                return true
            }
        }

        /** The touch, in the texture's own coordinates, re-sent to the virtual display. */
        private fun forward(event: MotionEvent): Boolean {
            val id = display?.display?.displayId ?: return false
            val copy = MotionEvent.obtain(event)
            copy.source = InputDevice.SOURCE_TOUCHSCREEN
            ShellAccess.injectMotionNow(copy, id)
            copy.recycle()
            return true
        }

        private fun back() {
            val id = display?.display?.displayId ?: return
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                ShellAccess.injectKeyNow(
                    KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD),
                    id,
                )
            }
        }

        fun close(moveToMain: Boolean) {
            runCatching { wm.removeView(root) }
            display?.release()
            display = null
            surface?.release()
            surface = null
            open.remove(this)
            if (moveToMain) {
                scope.launch {
                    ShellAccess.exec(
                        context,
                        "am start -n ${component.flattenToShortString()} -f 0x10000000" +
                            (userId?.let { " --user $it" } ?: ""),
                    )
                }
            }
        }
    }
}
