package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The NPU runtime on disk, ready for `Backend.NPU(dir)` (#804): on a
 * Snapdragon LiteRT's Qualcomm dispatch library and the QNN libraries, on a
 * Dimensity or a Tensor only LiteRT's dispatch library for that vendor, which
 * drives the phone's own NeuroPilot or Tensor TPU drivers.
 *
 * The build packages them all as
 * compressed assets under [ASSET_DIR], each named `<library>.bin` so AGP
 * compresses it (it stores a `.so` uncompressed even in assets), not as
 * native libraries: there they
 * would be stored uncompressed, about 50 MB instead of 14, and the runtime
 * could not use them from inside the APK anyway. The DSP half (the skel) is
 * read off the filesystem through `ADSP_LIBRARY_PATH`, and LiteRT opens the
 * rest by path from the directory it is given. So they are copied out once
 * per install of the app into no-backup storage, and the copy is redone after
 * every app update.
 *
 * On Play the assets arrive in the on-demand `:feature:npu` split; [directory]
 * asks for it and answers null until it is there, which the engine treats as
 * "no NPU this time" and runs on the GPU or CPU instead.
 */
object NpuRuntime {

    /**
     * Where the build puts the files: `wmkeyboard.npu-runtime` in build-logic.
     * Named for the vendor it first held; the other vendors' dispatch
     * libraries sit beside Qualcomm's.
     */
    private const val ASSET_DIR = "npu/qualcomm"

    private const val STAMP_FILE = ".installed"

    /**
     * The directory holding [soc]'s runtime, with its ARM libraries already
     * loaded into this process, or null when there is no usable runtime: not
     * in this build, not yet downloaded from Play, or not loadable on this
     * phone. Copies up to ~50 MB the first time after an install or update, so
     * never on the main thread.
     */
    @Synchronized
    fun directory(context: Context, soc: String): File? {
        val names = NpuSupport.runtimeLibraries(soc).takeIf { it.isNotEmpty() } ?: return null
        if (NativeDelivery.gate(DELIVERED_GATE) != null) return delivered(soc, names)
        val gate = FeatureModules.npu
        if (!gate.installed) {
            gate.requestInstall()
            return null
        }
        val app = context.applicationContext
        val dir = File(app.noBackupFilesDir, "npu-runtime/$soc")
        val stamp = installStamp(app)
        val stampFile = File(dir, STAMP_FILE)
        val current = runCatching { stampFile.readText() }.getOrNull() == stamp &&
            names.all { File(dir, it).isFile }
        if (!current && !unpack(app, dir, names, stamp)) return null
        return dir.takeIf { preload(dir, names) }
    }

    /**
     * Copies [names] out of the app's assets into a fresh [dir]. Into a
     * sibling first and renamed into place, so a copy cut short by the process
     * dying never passes for a complete one.
     */
    private fun unpack(app: Context, dir: File, names: List<String>, stamp: String): Boolean {
        val staging = File(dir.parentFile, "${dir.name}.part")
        return runCatching {
            staging.deleteRecursively()
            check(staging.mkdirs()) { "could not create ${staging.path}" }
            for (name in names) {
                val source = openAsset(app, name) ?: error("$name is not in this install")
                source.use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
            }
            File(staging, STAMP_FILE).writeText(stamp)
            dir.deleteRecursively()
            check(staging.renameTo(dir)) { "could not move ${staging.path} into place" }
            true
        }.getOrElse {
            staging.deleteRecursively()
            false
        }
    }

    /**
     * One runtime file from the app's assets. The asset manager sees a Play
     * split once SplitCompat has added it; a split installed while this
     * process was running may not be there yet, so the installed APKs are
     * read directly as well.
     */
    private fun openAsset(app: Context, name: String): InputStream? {
        runCatching { return app.assets.open("$ASSET_DIR/$name.bin") }
        val info = runCatching { app.packageManager.getApplicationInfo(app.packageName, 0) }.getOrNull()
            ?: return null
        val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        for (apk in apks) {
            val zip = runCatching { ZipFile(apk) }.getOrNull() ?: continue
            val entry = zip.getEntry("assets/$ASSET_DIR/$name.bin")
            if (entry == null) {
                zip.close()
                continue
            }
            // Closing the stream closes the archive with it.
            return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                override fun close() {
                    super.close()
                    zip.close()
                }
            }
        }
        return null
    }

    /**
     * Loads the ARM libraries from [dir] into this process. The HTP library
     * opens its stub by name, and an app cannot add a directory to the
     * linker's search path; a library already loaded under that name is what
     * a by-name open finds. False when one does not load: the phone lacks
     * `libcdsprpc.so`, or the files do not match it.
     */
    private fun preload(dir: File, names: List<String>): Boolean = runCatching {
        for (name in names) {
            // The skel is Hexagon code for the DSP, and a dispatch library
            // is LiteRT's to open from the directory it is told about.
            if (name.endsWith("Skel.so") || name in DISPATCH_LIBRARIES) continue
            System.load(File(dir, name).absolutePath)
        }
        true
    }.getOrDefault(false)

    /**
     * The gate whose presence says this build has native delivery at all:
     * every build that pins NPU libraries pins this one.
     */
    private const val DELIVERED_GATE = NpuSupport.GATE

    private val DISPATCH_LIBRARIES = NpuVendor.entries.map { it.dispatchLibrary }.toSet()

    /**
     * [directory] on a build with native delivery: on a Snapdragon the shared
     * libraries and this chip's stub and skel are separate gates, fetched
     * from Qualcomm's and Google's Maven repositories once the user has
     * accepted Qualcomm's licence; on a Dimensity or a Tensor the vendor's
     * dispatch library is a gate of its own from Google's, with no licence to
     * accept. Either way already in app storage, so nothing is unpacked.
     */
    private fun delivered(soc: String, names: List<String>): File? {
        val gateNames = NpuSupport.deliveryGates(soc).takeIf { it.isNotEmpty() } ?: return null
        val gates = gateNames.map { NativeDelivery.gate(it) ?: return null }
        if (!gates.all { it.installed }) {
            gates.filterNot { it.installed }.forEach { it.requestInstall() }
            return null
        }
        val dir = NativeDelivery.directoryFor(gateNames) ?: return null
        return dir.takeIf { names.all { File(dir, it).exists() } && preload(dir, names) }
    }

    /** When this install was last updated: a new app version brings new libraries. */
    private fun installStamp(app: Context): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    }.getOrDefault("0")
}
