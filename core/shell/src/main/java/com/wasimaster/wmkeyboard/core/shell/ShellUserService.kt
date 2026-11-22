package com.wasimaster.wmkeyboard.core.shell

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The process Shizuku starts from this APK as the shell user (uid 2000), or as
 * root under Sui or a root-started Shizuku. It never touches the keyboard's
 * own state: it runs the commands [ShellAccess] sends and injects the touches
 * of the experimental emulated window and the Trackpad tool's mouse events,
 * nothing else.
 *
 * Shizuku calls the no-argument constructor by reflection, so it must stay.
 */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String = runCommand(listOf("sh", "-c", command))

    override fun execStream(command: String): ParcelFileDescriptor? = runCatching {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val process = ProcessBuilder(listOf("sh", "-c", command)).redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null"))).start()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> process.inputStream.use { it.copyTo(out) } }
            }
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
        }.start()
        read
    }.getOrNull()

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean = inject(event, displayId)

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean = inject(event, displayId)

    /**
     * One event of the Trackpad tool's mouse (#1411), from a real-looking
     * mouse: tool type mouse, source mouse, with its button state. Apps read
     * it as they read a USB or Bluetooth mouse, so a secondary press opens a
     * context menu and a scroll is a wheel notch, not a swipe.
     */
    override fun injectMouse(
        action: Int,
        x: Float,
        y: Float,
        buttons: Int,
        actionButton: Int,
        hScroll: Float,
        vScroll: Float,
        downTime: Long,
        displayId: Int,
    ): Boolean = runCatching {
        exempt()
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
        val event = MotionEvent.obtain(
            if (downTime > 0) downTime else now, now, action, 1,
            arrayOf(properties), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            if (actionButton != 0) HiddenApiBypass.invoke(MotionEvent::class.java, event, "setActionButton", actionButton)
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }.getOrDefault(false)

    /**
     * `InputEvent.setDisplayId` and `injectInputEvent` are hidden; the shell
     * user holds INJECT_EVENTS, so the call itself is allowed once reflection
     * may see them. Mode 0 is INJECT_INPUT_EVENT_MODE_ASYNC: a touch stream
     * must not wait for each event's dispatch.
     */
    private fun inject(event: InputEvent, displayId: Int): Boolean = runCatching {
        exempt()
        HiddenApiBypass.invoke(InputEvent::class.java, event, "setDisplayId", displayId)
        val manager: Any = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HiddenApiBypass.invoke(Class.forName("android.hardware.input.InputManagerGlobal"), null, "getInstance")
        } else {
            HiddenApiBypass.invoke(Class.forName("android.hardware.input.InputManager"), null, "getInstance")
        }
        HiddenApiBypass.invoke(manager.javaClass, manager, "injectInputEvent", event, 0) as Boolean
    }.getOrDefault(false)

    @Volatile
    private var exempted = false

    private fun exempt() {
        if (exempted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("L")
        exempted = true
    }

    companion object {
        private const val TIMEOUT_S = 10L
        private const val MAX_OUTPUT = 64 * 1024

        /** Runs [argv], returning "exit code\noutput" like [IShellService.exec]. */
        fun runCommand(argv: List<String>): String {
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { r ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = r.read(buffer)
                            if (n < 0) break
                            if (output.length < MAX_OUTPUT) output.append(buffer, 0, minOf(n, MAX_OUTPUT - output.length))
                        }
                    }
                }
            }.apply { start() }
            val finished = process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            reader.join(1_000)
            val code = if (finished) process.exitValue() else -1
            return "$code\n$output"
        }
    }
}
