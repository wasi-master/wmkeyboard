package com.wasimaster.wmkeyboard.ime

import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One of the keyboard's own screens answering an API 5 plugin: the QR
 * scanner, the text reader, a file pick, a place to save a file.
 *
 * The plugin's call waits in [await] on the host's pool. The service puts the
 * screen up on the main thread and calls [answer] with what the user chose, or
 * with null when they backed out. The plugin's session is kept through the
 * panel change (and through the keyboard hiding, for Android's own pickers)
 * only while one of these is open; see `WMKeyboardService.pluginHandoffHolds`.
 */
internal class PluginHandoff(
    val pluginId: String,
    val kind: Kind,
    /** [Kind.PICK]: the types the plugin takes, wildcards allowed. [Kind.SAVE]: the file's one type. */
    val mimes: List<String> = emptyList(),
    /** [Kind.SAVE]: the name offered in the picker. */
    val name: String = "",
) {
    enum class Kind { QR, TEXT, PICK, SAVE }

    /** The panel the plugin was in when it asked, to put back afterwards. Main thread. */
    var shown: PluginPanelUi.Running? = null

    /** What the plugin's boxes held, put back with [shown]. Main thread. */
    var inputs: Map<String, String> = emptyMap()

    /** The panel that answers, or [PanelMode.PLUGINS] when Android's own screen does. Main thread. */
    var panel: PanelMode = PanelMode.PLUGINS

    /** Asked from the floating box, which keeps the plugin on screen by itself. */
    var float: Boolean = false

    private val latch = CountDownLatch(1)

    @Volatile
    private var value: Any? = null

    val done: Boolean get() = latch.count == 0L

    /** A [String] for a scan, a [Uri] for a pick or a save, null for "backed out". Only the first answer counts. */
    @Synchronized
    fun answer(result: Any?) {
        if (done) return
        value = result
        latch.countDown()
    }

    /**
     * The answer, once there is one. Null when the user backed out, the
     * plugin's session ended, or [timeoutMs] passed first.
     */
    fun await(active: () -> Boolean, timeoutMs: Long): Any? {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!active() || System.nanoTime() > end) answer(null)
        }
        return value
    }

    private companion object {
        const val POLL_MS = 200L
    }
}

/**
 * Where a file the user picked or saved for a plugin comes back, from the
 * keyboard's own file manager or from Android's pickers
 * (`GalleryPickActivity`'s plugin modes). One at a time: a plugin's pick is
 * always the only one open.
 */
internal object PluginFilePicks {

    @Volatile
    private var waiting: PluginHandoff? = null

    /** For [PluginHandoff.Kind.SAVE]: the bytes Android's save screen writes, held here so they never ride an intent. */
    @Volatile
    var saving: ByteArray? = null
        private set

    @Synchronized
    fun begin(handoff: PluginHandoff, bytes: ByteArray? = null) {
        waiting?.answer(null)
        waiting = handoff
        saving = bytes
    }

    /** The file picked, or the place saved to, or null for "backed out". */
    @Synchronized
    fun deliver(uri: Uri?) {
        val handoff = waiting ?: return
        waiting = null
        saving = null
        handoff.answer(uri)
    }

    /** Drops [handoff] if it is still the one waiting, answering it null. */
    @Synchronized
    fun cancel(handoff: PluginHandoff) {
        if (waiting !== handoff) return
        waiting = null
        saving = null
        handoff.answer(null)
    }

    /** The open pick's types, for the screen that offers it. */
    fun mimes(): List<String> = waiting?.mimes.orEmpty()

    /** The open save's name and type, for Android's save screen. */
    fun saveAs(): Pair<String, String>? = waiting?.takeIf { it.kind == PluginHandoff.Kind.SAVE }
        ?.let { it.name to (it.mimes.firstOrNull() ?: "application/octet-stream") }

    fun pending(): Boolean = waiting != null
}
