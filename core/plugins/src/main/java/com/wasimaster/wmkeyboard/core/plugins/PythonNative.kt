package com.wasimaster.wmkeyboard.core.plugins

import androidx.annotation.Keep
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * JNI surface of `libwm_python_jni` (`native/wm-python-jni`): the PyMini-based
 * interpreter every plugin runs in, and the plugin editor's language tooling.
 * One library for both, behind the `plugins` gate (`:feature:plugins` on
 * Play), fetched when the user turns plugins on or opens the editor.
 *
 * Everything crosses as JSON strings. A session is a handle from
 * [nativeCreate], used on one thread at a time and freed with [nativeClose];
 * only [nativeCancel] may be called from another thread, which is how the
 * runtime's watchdog stops a script that overran its deadline. The shapes are
 * documented in `native/wm-python/src/host/` and decoded by [PythonSession].
 *
 * On a device the library comes through the gate. In a JVM unit test it is
 * the host build `native/wm-python-jni/build-host.sh` makes, found by walking
 * up from the working directory to `settings.gradle.kts`, or the file
 * `-Dwmkeyboard.python.lib` names.
 */
object PythonNative {
    const val LIBRARY = "wm_python_jni"

    private val library = GatedLibrary({ FeatureModules.plugins }) {
        runCatching { load() }.isSuccess
    }

    /** Whether the library is loaded; tries to load it when the module is here. */
    val available: Boolean get() = if (onJvm) jvmLoaded else library.available

    /** Whether this build has it, counting a module not fetched yet. */
    val shipped: Boolean get() = onJvm || library.shipped

    /** Where the `plugins` module stands, for the screens that wait on it. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.plugins.state

    /** Asks for the module. Idempotent. */
    fun requestModule() = FeatureModules.plugins.requestInstall()

    // ART reports itself as Dalvik; Robolectric and plain JUnit run on a desktop JVM.
    private val onJvm: Boolean = System.getProperty("java.vm.name") != "Dalvik"

    private val jvmLoaded: Boolean by lazy { runCatching { load() }.isSuccess }

    private fun load() {
        val explicit = System.getProperty("wmkeyboard.python.lib") ?: System.getenv("WM_PYTHON_LIB")
        when {
            !explicit.isNullOrEmpty() -> System.load(explicit)
            !onJvm -> FeatureModules.loadLibrary(LIBRARY)
            else -> {
                val host = hostLibrary() ?: error(
                    "libwm_python_jni is not built for this machine: run native/wm-python-jni/build-host.sh",
                )
                System.load(privateCopy(host).absolutePath)
            }
        }
    }

    /**
     * A copy for this class loader alone: a JVM binds a native library to the
     * first loader that loads its path, and Robolectric gives every sandbox
     * its own (the same fix as `NativeEngine`).
     */
    private fun privateCopy(library: File): File {
        val copy = File.createTempFile(LIBRARY, "." + library.extension)
        copy.deleteOnExit()
        library.copyTo(copy, overwrite = true)
        return copy
    }

    private fun hostLibrary(): File? {
        val names = listOf("libwm_python_jni.dylib", "libwm_python_jni.so", "wm_python_jni.dll")
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                for (profile in listOf("release", "debug")) {
                    for (name in names) {
                        val file = File(dir, "native/wm-python-jni/target/$profile/$name")
                        if (file.isFile) return file
                    }
                }
                return null
            }
            dir = dir.parentFile
        }
        return null
    }

    // ---- sessions -------------------------------------------------------

    /** A session for the config JSON; 0 when the config is unreadable. */
    @JvmStatic external fun nativeCreate(config: String): Long

    /** Runs [source] as the plugin's script: its top level, once. Result JSON. */
    @JvmStatic external fun nativeLoad(handle: Long, source: String): String

    /** Calls `render()`. Result JSON with `ui`. */
    @JvmStatic external fun nativeRender(handle: Long): String

    /** Calls `on_event(e)` with the event JSON, then `render()`. Result JSON with `ui`. */
    @JvmStatic external fun nativeEvent(handle: Long, event: String): String

    /** Calls `transform(text, action)`. Result JSON with `value`. */
    @JvmStatic external fun nativeTransform(handle: Long, text: String, actionId: String): String

    /** Steps and time the last call used, against its limits. JSON. */
    @JvmStatic external fun nativeUsage(handle: Long): String

    /** Asks the running call to stop at its next step. Any thread. */
    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeClose(handle: Long)

    /**
     * Called by the library, on the plugin thread, for each host call an API 5
     * plugin makes while one of the calls above runs. Kept by name: nothing
     * in Kotlin calls it, so R8 would otherwise drop it.
     */
    @Keep
    @JvmStatic
    fun hostCall(handle: Long, op: String, args: String): String = PluginHost.call(handle, op, args)

    // ---- the editor's tooling --------------------------------------------

    /** Whether [source] parses: null, or `{message, kind, line}` JSON. */
    @JvmStatic external fun nativeCheck(source: String): String?

    /** Highlighting tokens: (kind, start, end) triples in UTF-16 offsets. */
    @JvmStatic external fun nativeTokens(source: String): IntArray

    /** Diagnostics, outline and folds for [source] under the host JSON. */
    @JvmStatic external fun nativeAnalyse(source: String, host: String): String

    @JvmStatic external fun nativeComplete(source: String, caret: Int, explicit: Boolean, host: String): String

    @JvmStatic external fun nativeFormat(source: String): String

    @JvmStatic external fun nativeDefinition(source: String, caret: Int): String

    @JvmStatic external fun nativeReferences(source: String, caret: Int): String

    @JvmStatic external fun nativeRename(source: String, caret: Int, newName: String): String

    @JvmStatic external fun nativeApiAt(source: String, caret: Int): String

    /** The whole API model with docs and event types. */
    @JvmStatic external fun nativeApiEntries(): String
}
