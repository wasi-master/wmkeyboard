package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import android.os.Build
import android.system.Os
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import java.io.File
import java.util.zip.ZipFile

/**
 * llama.cpp on the Snapdragon NPU: [LlamaNative.LIBRARY_HEXAGON], ggml's
 * Hexagon backend, with Compute = NPU and a GGUF model (gate `llamanpu`).
 *
 * The ARM library talks to the NPU's DSP over FastRPC (the phone's
 * `libcdsprpc.so`), and the DSP loads its half, the skel
 * `libggml-htp-v<arch>.so`, itself, by name, off the directories in
 * `ADSP_LIBRARY_PATH`: a real file, not one inside an APK. The build ships
 * a skel per Hexagon generation beside the library; [prepare] finds this
 * chip's on disk (the native-delivery store, an extracted library directory,
 * or a copy out of the installed APKs) and points the variable at it before
 * the library starts its backend. A phone whose DSP refuses it offers no
 * device, and the model runs on the CPU, as with a GPU driver that offers none.
 */
internal object LlamaHexagon {

    /**
     * Chip -> its Hexagon tensor processor generation, the skel's suffix.
     * The chips the skels are built for (native/llamacpp-jni/build.sh) that
     * this build can name: Snapdragon 8 Gen 2, 8 Gen 3, 8 Elite, 8 Elite Gen 5.
     */
    private val ARCH = mapOf(
        "SM8550" to "v73",
        "SM8650" to "v75",
        "SM8750" to "v79",
        "SM8850" to "v81",
    )

    /** This phone's skel generation, or null where there is none to load. */
    val arch: String? by lazy {
        if (!BuildConfig.ENABLE_NPU || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@lazy null
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return@lazy null
        NpuSupport.normalizeSoc(Build.SOC_MODEL)?.let(ARCH::get)
    }

    /**
     * Whether Compute = NPU can put a GGUF model on this phone's NPU: a listed
     * chip with ARMv8.2 cores, in a build with an NPU (never F-Droid's, whose
     * build has no Hexagon SDK).
     */
    val usable: Boolean get() = arch != null && LlamaNative.gpuLibraryPossible

    private fun skelName(arch: String) = "libggml-htp-$arch.so"

    /**
     * Sets `ADSP_LIBRARY_PATH` to a directory holding this chip's skel,
     * ahead of the system's own DSP directories. False when there is no
     * skel to point at; the library then runs the model on the CPU, so the
     * caller loads a CPU build instead. Before the library is loaded.
     */
    fun prepare(context: Context): Boolean {
        val arch = arch ?: return false
        val dir = skelDirectory(context.applicationContext, skelName(arch)) ?: return false
        return runCatching {
            Os.setenv("ADSP_LIBRARY_PATH", (listOf(dir.path) + SYSTEM_DSP_DIRS).joinToString(";"), true)
        }.isSuccess
    }

    private fun skelDirectory(app: Context, name: String): File? {
        // A build with native delivery keeps the gate's files in its store.
        if (NativeDelivery.gate(GATE) != null) {
            return NativeDelivery.directoryFor(listOf(GATE))?.takeIf { File(it, name).isFile }
        }
        // An install that extracts its libraries has it in place.
        app.applicationInfo.nativeLibraryDir?.let(::File)?.takeIf { File(it, name).isFile }?.let { return it }
        // Play stores libraries uncompressed inside the APKs: copied out
        // once per install of the app, renamed into place whole.
        val dir = File(app.noBackupFilesDir, "llama-htp/${installStamp(app)}")
        if (File(dir, name).isFile) return dir
        File(app.noBackupFilesDir, "llama-htp").deleteRecursively()
        val info = runCatching { app.packageManager.getApplicationInfo(app.packageName, 0) }.getOrNull() ?: return null
        val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        for (apk in apks) {
            val copied = runCatching {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/$name") ?: return@use false
                    dir.mkdirs()
                    val part = File(dir, "$name.part")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    part.renameTo(File(dir, name))
                }
            }.getOrDefault(false)
            if (copied) return dir
        }
        return null
    }

    private fun installStamp(app: Context): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    }.getOrDefault("0")

    const val GATE = "llamanpu"

    /** Where FastRPC looks by default; kept after ours so the system's own skels still load. */
    private val SYSTEM_DSP_DIRS = listOf("/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp", "/vendor/dsp", "/dsp")
}
