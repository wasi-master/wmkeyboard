package com.wasimaster.wmkeyboard.core.voice.whisper

import android.content.Context
import android.os.Build
import android.os.UserManager
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.util.PhoneGpu
import java.io.File

/**
 * whisper.cpp on the GPU, for Compute = GPU or Auto: ggml's OpenCL backend on
 * an Adreno phone (`libwmwhispercpp_opencl.so`, in the speech gate), its
 * Vulkan backend on other GPUs with a Vulkan 1.2 driver, Mali, Xclipse and
 * PowerVR (`libwmwhispercpp_vulkan.so`, its own `whispervulkan` gate, fetched
 * the first time it is wanted). Both arm64 only. [WhisperCpp] is the one
 * caller: it opens a model here when the GPU is wanted and this says yes, and
 * on the CPU builds otherwise or when this fails.
 *
 * Either library binds its own JNI class (this one; a phone loads one of the
 * two, never both), so it loads beside the CPU build the catalog already
 * loaded, and Compute can change in a running keyboard. The OpenCL build links
 * to the vendor's `libOpenCL.so` by name: a phone whose app namespace offers
 * none (the manifest asks for it, not required) fails the load, and dictation
 * stays on the CPU. Qualcomm's backend refuses every GPU but Adreno and Intel,
 * so it is not even loaded elsewhere; the Vulkan build is what those take.
 *
 * A driver that takes the process down, or runs out of memory where ggml has
 * no way back but to abort, cannot be caught. So every step on the GPU first
 * writes what it is trying to [ATTEMPT_FILE]; the next process finds it there,
 * counts it as failed, and from then on that phone and install (and that model,
 * for a model's failure) dictates on the CPU. An app or system update forgets
 * the verdicts, and so does choosing GPU again on the engine's screen.
 */
object WhisperCppGpu {

    /** The failed keys, one a line. Not backed up: they are about this phone. */
    private const val FAILED_FILE = "whispercpp-gpu-failed"

    /** The key of the step under way, while it is under way. */
    private const val ATTEMPT_FILE = "whispercpp-gpu-attempt"

    /** The verdict key of the library and driver as such, model aside. */
    private const val LIBRARY_KEY = "library"

    @Volatile private var app: Context? = null
    @Volatile private var stateDir: File? = null
    @Volatile private var cacheDir: File? = null
    @Volatile private var installKey = ""
    private val failed = HashSet<String>()

    /**
     * Gives this the application, from which it finds where the verdicts and
     * the compiled kernels live. Called once per process, before anything
     * dictates; until then the GPU is never offered. Reads nothing yet.
     */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    /**
     * Reads the verdicts the first time it can: not before the phone is
     * unlocked after a boot, when the app's own storage cannot be read, and a
     * verdict lost to that would let a crashing GPU crash the keyboard again.
     */
    private fun loadState(): Boolean {
        if (stateDir != null) return true
        val context = app ?: return false
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) return false
        synchronized(failed) {
            if (stateDir != null) return true
            val dir = context.noBackupFilesDir
            val updated = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            // The backend is part of the key: a Vulkan failure says nothing
            // about OpenCL on another install of the same phone image.
            installKey = "${Build.FINGERPRINT}|$updated|${if (PhoneGpu.adreno) "opencl" else "vulkan"}|"
            cacheDir = File(context.codeCacheDir, "whispercpp-opencl")
            runCatching { File(dir, FAILED_FILE).readLines() }.getOrDefault(emptyList())
                .filterTo(failed) { it.startsWith(installKey) }
            // What the last process was doing on the GPU when it died.
            val attempt = File(dir, ATTEMPT_FILE)
            runCatching { attempt.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { failed += it }
            attempt.delete()
            stateDir = dir
            save()
        }
        return true
    }

    /** An Adreno GPU, the one Qualcomm's OpenCL backend is written for. */
    val adreno: Boolean get() = PhoneGpu.adreno

    /**
     * Whether this phone could run a GPU build at all: ARMv8.2 cores, and an
     * Adreno or another GPU with a Vulkan 1.2 driver.
     */
    val possible: Boolean get() = (PhoneGpu.adreno || PhoneGpu.vulkan) && armV82

    private val armV82: Boolean by lazy { WhisperCpp.hasArmV82Features() }

    /** The gate that carries this phone's GPU build: speech for OpenCL, whispervulkan for Vulkan. */
    private val gate: ModuleGate get() = if (PhoneGpu.adreno) FeatureModules.speech else FeatureModules.whisperVulkan

    /**
     * The id of the gate this phone's GPU build needs fetched on its own, for
     * the Feature libraries page: whispervulkan on a Vulkan phone. Null on an
     * Adreno, whose build rides in the speech gate, and where none can run.
     */
    val gateId: String? get() = "whispervulkan".takeIf { possible && !PhoneGpu.adreno }

    private val library = GatedLibrary({ gate }) {
        possible && runCatching {
            FeatureModules.loadLibrary(if (PhoneGpu.adreno) "wmwhispercpp_opencl" else "wmwhispercpp_vulkan")
        }.isSuccess
    }

    /**
     * Fetches the Vulkan build ahead of the first dictation on the GPU, when
     * this phone takes it; does nothing on an Adreno (its build comes with the
     * engines) or where no GPU build can run.
     */
    fun prepare() {
        if (possible && !gate.installed) gate.requestInstall()
    }

    @Volatile private var devices: List<String>? = null

    /** Why the GPU is or is not used, for the engine's screen. */
    enum class Support {
        /** Ready, or not tried yet on a phone that should manage. */
        READY,

        /** No Adreno and no Vulkan 1.2 GPU, or cores older than ARMv8.2. */
        NO_GPU,

        /**
         * It was tried and failed on this phone: no vendor OpenCL or Vulkan
         * library the app may open, a driver that offered no device or
         * crashed, no room.
         */
        FAILED,

        /** The Vulkan build is still being fetched; dictation runs on the CPU until it is here. */
        DOWNLOADING,
    }

    /** What the engine's screen says about the GPU. Loads no library. */
    val support: Support
        get() = when {
            !possible -> Support.NO_GPU
            loadState() && isFailed(LIBRARY_KEY) -> Support.FAILED
            !gate.installed -> Support.DOWNLOADING
            else -> Support.READY
        }

    /** The GPU's name once the backend started ("QUALCOMM Adreno(TM) 740", "Mali-G57"), else null. */
    val deviceName: String? get() = devices?.firstOrNull()

    /**
     * Whether a model at [modelKey] may be opened on the GPU now: the phone
     * can, nothing failed, the library loads and the driver offers a device.
     * The first call starts OpenCL or Vulkan, inside an attempt. A Vulkan
     * build not fetched yet is asked for, and this clip runs on the CPU.
     * Under WhisperCpp's lock.
     */
    internal fun ready(modelKey: String): Boolean {
        if (!possible || !loadState() || isFailed(LIBRARY_KEY) || isFailed(modelKey)) return false
        if (!gate.installed) {
            gate.requestInstall()
            return false
        }
        if (!library.available) {
            fail(LIBRARY_KEY)
            return false
        }
        val found = devices ?: run {
            val list = attempt(LIBRARY_KEY) {
                String(nativeGpuInit(cacheDir?.path), Charsets.UTF_8).lines().filter { it.isNotBlank() }
            }
            devices = list
            list
        }
        if (found.isEmpty()) fail(LIBRARY_KEY)
        return found.isNotEmpty()
    }

    /** The verdict key for a model file: it, its size and when it was written. */
    internal fun modelKey(file: File): String = "${file.path}|${file.length()}|${file.lastModified()}"

    /**
     * Runs [block] with [key] written down as the step under way, so a crash
     * inside it is remembered. A throw from it is a failure too.
     */
    internal fun <T> attempt(key: String, block: () -> T): T {
        val dir = stateDir
        val attempt = dir?.let { File(it, ATTEMPT_FILE) }
        runCatching { attempt?.writeText(installKey + key) }
        try {
            return block()
        } catch (e: Throwable) {
            fail(key)
            throw e
        } finally {
            attempt?.delete()
        }
    }

    /** Remembers that [key] failed on this phone and install. */
    internal fun fail(key: String) = synchronized(failed) {
        if (failed.add(installKey + key)) save()
    }

    private fun isFailed(key: String): Boolean = synchronized(failed) { (installKey + key) in failed }

    /** Forgets every failure, so the next dictation tries the GPU again. */
    fun forgetFailures() {
        if (!loadState()) return
        synchronized(failed) {
            failed.clear()
            save()
        }
        devices = null
    }

    private fun save() {
        val dir = stateDir ?: return
        runCatching {
            val file = File(dir, FAILED_FILE)
            if (failed.isEmpty()) file.delete() else file.writeText(failed.joinToString("\n"))
        }
    }

    // Kotlin mangles internal names, and JNI binds by name: the natives stay
    // private, and WhisperCpp calls these.
    internal fun open(path: String, useGpu: Boolean): Long = nativeOpen(path, useGpu)

    internal fun onGpu(handle: Long): Boolean = nativeOnGpu(handle)

    internal fun close(handle: Long) = nativeClose(handle)

    internal fun transcribe(
        handle: Long,
        pcm: FloatArray,
        languages: Array<String>,
        translate: Boolean,
        prompt: String?,
        beamSize: Int,
        threads: Int,
    ): ByteArray? = nativeTranscribe(handle, pcm, languages, translate, prompt, beamSize, threads)

    internal fun partial(handle: Long): ByteArray = nativePartial(handle)

    internal fun abort(handle: Long) = nativeAbort(handle)

    @JvmStatic private external fun nativeGpuInit(cacheDir: String?): ByteArray

    @JvmStatic private external fun nativeOpen(path: String, useGpu: Boolean): Long

    @JvmStatic private external fun nativeOnGpu(handle: Long): Boolean

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        languages: Array<String>,
        translate: Boolean,
        prompt: String?,
        beamSize: Int,
        threads: Int,
    ): ByteArray?

    @JvmStatic private external fun nativePartial(handle: Long): ByteArray

    @JvmStatic private external fun nativeAbort(handle: Long)
}
