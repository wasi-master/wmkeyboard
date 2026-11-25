package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The NPU builds of the single LiteRT models: Whisper Tiny and search by
 * meaning's text encoder, compiled ahead of time for one chip and run by
 * LiteRT's CompiledModel on the same dispatch libraries (and on a Snapdragon
 * the same QNN libraries) Compute = NPU brings for on-device AI. The CPU file
 * stays the fallback; a build that fails is remembered per phone in
 * `AcceleratorVerdicts` by each feature.
 *
 * The chip and the runtime live in :core:intelligence (`NpuSupport`,
 * `NpuRuntime`), which :core:voice cannot see, so the app hands them in
 * through [attach] at startup, the way `EtNative.qnnRuntime` is.
 */
object LitertNpu {

    @Volatile
    var context: Context? = null
        private set

    @Volatile
    private var socOf: () -> String? = { null }

    @Volatile
    private var runtimeOf: () -> File? = { null }

    /**
     * [soc] is `NpuSupport.soc`; [runtime] is `NpuRuntime.directory` for it,
     * which asks for the runtime and answers null until it is on the phone.
     */
    fun attach(context: Context, soc: () -> String?, runtime: () -> File?) {
        this.context = context.applicationContext
        socOf = soc
        runtimeOf = runtime
    }

    /** This phone's NPU target as `NpuSupport` spells it ("SM8750", "MT6991", "TENSOR-G5"), or null. */
    fun soc(): String? = socOf()

    /** The dispatch library directory, ready to hand to CompiledModel, or null. Copies files the first time: off the main thread. */
    fun runtime(): File? = runtimeOf()
}

/**
 * One NPU build, pinned: [url] at a fixed commit, [bytes] long, with the
 * publisher's [sha256]. When the published file is a LiteRT-LM bundle and the
 * graph is one section of it, [sectionStart] and [sectionEnd] are that
 * section's byte range, read from the bundle's header, and only those bytes
 * are kept once the whole file has been checked.
 */
data class NpuModelBuild(
    val soc: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val sectionStart: Long = 0L,
    val sectionEnd: Long = 0L,
) {
    /** What stays on the phone. */
    val keptBytes: Long get() = if (sectionEnd > sectionStart) sectionEnd - sectionStart else bytes
}

/**
 * Downloads of [NpuModelBuild]s, keyed by the file each lands in, so the
 * settings row and the engine see the same state. Process-wide, like the
 * other model downloads.
 */
object NpuModelDownloads {

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status
        data class Failed(@StringRes val messageRes: Int) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    fun status(target: File): Status = _states.value[target.path]
        ?: if (target.isFile) Status.Downloaded else Status.NotDownloaded

    /** Whether [target] is on the phone or on its way. */
    fun wanted(target: File): Boolean = target.isFile || synchronized(jobs) { jobs[target.path]?.isActive == true }

    /** Fetches [build] into [target]. Idempotent while one is running. */
    fun start(build: NpuModelBuild, target: File, source: NetSource) {
        val key = target.path
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            set(key, Status.Downloading(0, build.bytes))
            jobs[key] = scope.launch {
                try {
                    fetch(build, target, source) { set(key, Status.Downloading(it, build.bytes)) }
                    set(key, Status.Downloaded)
                } catch (e: CancellationException) {
                    set(key, Status.NotDownloaded)
                    throw e
                } catch (e: DownloadException.Blocked) {
                    set(key, Status.Failed(R.string.common_error_download_held))
                } catch (_: Exception) {
                    set(key, Status.Failed(R.string.common_error_network))
                }
            }
        }
    }

    /** Stops a download and deletes [target] with whatever was fetched of it. */
    fun delete(target: File) {
        synchronized(jobs) { jobs.remove(target.path)?.cancel() }
        target.delete()
        FileDownloader.discard(bundleFile(target))
        bundleFile(target).delete()
        set(target.path, Status.NotDownloaded)
    }

    /** Bytes [target] and its partial download take now, for the storage screens. */
    fun bytesOnDisk(target: File): Long =
        (target.takeIf { it.isFile }?.length() ?: 0L) + FileDownloader.bytesDone(File(bundleFile(target).path + ".part"))

    private fun set(key: String, status: Status) {
        _states.update { it + (key to status) }
    }

    /** The whole published file, while it is checked and before its section is cut out. */
    private fun bundleFile(target: File): File = File(target.parentFile, "${target.name}.download")

    private suspend fun fetch(build: NpuModelBuild, target: File, source: NetSource, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val section = build.sectionEnd > build.sectionStart
        val whole = if (section) bundleFile(target) else target
        FileDownloader.fetchVerified(
            url = build.url,
            target = whole,
            size = build.bytes,
            sha256 = build.sha256,
            source = source,
            part = File(bundleFile(target).path + ".part"),
            what = target.name,
            onBytes = onBytes,
        )
        if (!section) return
        // Only the graph is kept: the bundle's other sections (its tokenizer,
        // its token table) are the CPU pack's own files already.
        val staging = File(target.parentFile, "${target.name}.cut")
        try {
            RandomAccessFile(whole, "r").use { input ->
                staging.outputStream().use { out ->
                    input.seek(build.sectionStart)
                    val buffer = ByteArray(1 shl 16)
                    var left = build.sectionEnd - build.sectionStart
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (read < 0) throw IOException("${whole.name} ends before its section does")
                        out.write(buffer, 0, read)
                        left -= read
                    }
                }
            }
            target.delete()
            if (!staging.renameTo(target)) throw IOException("could not move ${target.name} into place")
        } finally {
            staging.delete()
            whole.delete()
        }
    }
}
