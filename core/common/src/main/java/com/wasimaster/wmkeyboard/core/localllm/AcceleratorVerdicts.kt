package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Models that failed on one accelerator of this phone, remembered across
 * processes in [failedFileName] (one key per line) in the app's no-backup
 * dir. Before the native code runs, [begin] writes the key to
 * [attemptFileName]: a native crash in a driver kills the process before any
 * catch runs, so the only trace it leaves is that file still being there when
 * the next process [load]s the verdicts.
 *
 * Shared by both runtimes, each with files of its own: LiteRT-LM's GPU and
 * NPU, llama.cpp's GPU. Not thread-safe: every call with the owning
 * runtime's lock held.
 */
class AcceleratorVerdicts(
    private val failedFileName: String,
    private val attemptFileName: String,
) {
    private val failed = mutableSetOf<String>()

    /** Whether [load] has read the file back in this process. */
    private var loaded = false

    /** The key whose engine has not answered yet, mirrored in the attempt file. */
    private var unproven: String? = null

    /** The attempt file, once a context has been seen. */
    private var attemptFile: File? = null

    operator fun contains(key: String): Boolean = key in failed

    /**
     * Reads the verdicts back once per process, turning an attempt the last
     * process never finished into a failure. Verdicts from before the last
     * system or app update are dropped, which is what gives the accelerator
     * its fresh try.
     */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val dir = context.noBackupFilesDir
        val current = keyPrefix(context)
        val stored = runCatching { File(dir, failedFileName).readLines() }.getOrNull()
            ?.filter { it.isNotBlank() }
            .orEmpty()
        failed += stored.filter { it.startsWith(current) }
        val attempt = File(dir, attemptFileName)
        val crashed = runCatching { attempt.readText().trim() }.getOrNull()
        if (!crashed.isNullOrEmpty() && crashed.startsWith(current)) failed += crashed
        if (failed.size != stored.size || !stored.containsAll(failed)) save(context)
        runCatching { attempt.delete() }
    }

    /** Written before the native code runs: if it takes the process down, the file is all the next one has. */
    fun begin(context: Context, key: String) {
        val attempt = File(context.noBackupFilesDir, attemptFileName)
        attemptFile = attempt
        unproven = key
        runCatching { attempt.writeText(key) }
    }

    /** This model does not run on this accelerator; on disk too unless [persist] is false. */
    fun markFailed(context: Context, key: String, persist: Boolean = true) {
        failed += key
        if (persist) save(context)
        unproven = null
        runCatching { File(context.noBackupFilesDir, attemptFileName).delete() }
    }

    /** The engine [begin] was for has answered or was closed by this code. */
    fun confirm() {
        if (unproven == null) return
        unproven = null
        attemptFile?.let { runCatching { it.delete() } }
    }

    private fun save(context: Context) {
        runCatching {
            File(context.noBackupFilesDir, failedFileName).writeText(failed.joinToString("\n"))
        }
    }

    companion object {
        /** When this install was last updated; fixed for the life of the process. */
        @Volatile
        private var installStamp: Long? = null

        /**
         * Identifies a model file on this phone as it is now. A system update
         * (a new GPU driver), an app update (a new runtime) or a redownloaded
         * file each deserve a fresh try on the accelerator, so they are part
         * of the key.
         */
        fun key(context: Context, modelFile: File): String =
            "${keyPrefix(context)}${modelFile.length()}|${modelFile.path}"

        /** The part of a [key] that is the same for every model until an update. */
        fun keyPrefix(context: Context): String {
            val installed = installStamp ?: runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L).also { installStamp = it }
            return "${Build.FINGERPRINT}|$installed|"
        }

        /**
         * This process's graphics memory in KB, as the system counts it
         * (smaps and memtrack): what a GPU engine leaves behind shows up here.
         * Tens of ms: off the main thread.
         */
        fun graphicsKb(): Long = runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.getMemoryStat("summary.graphics")?.toLongOrNull()
        }.getOrNull() ?: 0L
    }
}
