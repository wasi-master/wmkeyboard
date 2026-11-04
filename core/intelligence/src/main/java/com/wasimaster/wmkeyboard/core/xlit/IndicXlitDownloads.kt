package com.wasimaster.wmkeyboard.core.xlit

import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.ocr.screenText
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The download of [IndicXlitModels.NATIVE], neural suggestions on the phonetic layouts. */
object IndicXlitDownloads : XlitDownloads(IndicXlitModels.NATIVE)

/** The download of [IndicXlitModels.LATIN], romanized dictation's model. */
object IndicXlitLatinDownloads : XlitDownloads(IndicXlitModels.LATIN)

/**
 * The download of one of [IndicXlitModels]' packs, ONNX Runtime first
 * wherever it is delivered on demand (Play's `:feature:onnx`, or the pinned
 * library on a GitHub or F-Droid build), so the settings row shows one bar
 * for both.
 *
 * Each file goes through [FileDownloader]: parallel ranges into a `.part`
 * that resumes piece by piece, and its name only after its SHA-256 matched.
 * Process-wide: the language screens and the keyboard read the same [status].
 */
sealed class XlitDownloads(private val pack: IndicXlitModels.Pack) {

    sealed interface Status {
        data object NotDownloaded : Status

        /** [total] is every byte still to fetch when it started, the runtime's included. */
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageRes] is the text to show; [messageArg] its one argument, or "". */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

    val status: StateFlow<Status> = _status.asStateFlow()

    /** Seeds [status] from disk; call when a screen that shows it appears. */
    fun refresh(context: Context) {
        val running = synchronized(this) { job?.isActive == true }
        if (running) return
        _status.value = when {
            pack.isDownloaded(context.noBackupFilesDir) -> Status.Downloaded
            else -> _status.value as? Status.Failed ?: Status.NotDownloaded
        }
    }

    /**
     * Every byte a download would fetch now: the model files still missing,
     * plus ONNX Runtime and its bridge when they are delivered on demand and
     * not here yet. On a first download the runtime is most of it, so this is
     * what the settings row and the mobile-data question quote, not the
     * model's own size. A Play split reports no size before it starts and
     * counts as nothing here.
     */
    fun bytesToFetch(context: Context): Long =
        missingModelBytes(context.applicationContext.noBackupFilesDir) + runtimeBytesToFetch()

    private fun missingModelBytes(dir: File): Long = pack.parts
        .filter { pack.file(dir, it).let { f -> !f.isFile || f.length() != it.bytes } }
        .sumOf { it.bytes }

    private fun runtimeBytesToFetch(): Long =
        if (FeatureModules.onnx.installed) 0L else (FeatureModules.onnx as? DownloadedModuleGate)?.missingBytes ?: 0L

    /** Whether the model and what runs it are both here. Never downloads anything. */
    fun ready(context: Context): Boolean =
        FeatureModules.onnx.installed && pack.isDownloaded(context.noBackupFilesDir)

    /** Downloads the pack and the runtime. Idempotent. */
    fun start(context: Context) {
        val dir = context.applicationContext.noBackupFilesDir
        synchronized(this) {
            if (job?.isActive == true) return
            val missing = pack.parts.filter {
                pack.file(dir, it).let { f -> !f.isFile || f.length() != it.bytes }
            }
            val modelBytes = missing.sumOf { it.bytes }
            // The runtime comes first and the model after it, under one bar:
            // the model's bytes are counted on top of the runtime's, so the
            // bar never runs back to the start when the second part begins.
            var runtimeBytes = runtimeBytesToFetch()
            _status.value = Status.Downloading(0, runtimeBytes + modelBytes)
            FeatureModules.onnx.requestInstall()
            job = scope.launch {
                try {
                    val moduleHere = FeatureModules.onnx.awaitInstalled { bytes, total ->
                        if (total > 0) {
                            runtimeBytes = total
                            _status.value = Status.Downloading(bytes, total + modelBytes)
                        }
                    }
                    if (!moduleHere) throw FailedException(OfflineBuild.library(R.string.core_intel_xlit_module_error))
                    download(dir, missing, alreadyFetched = runtimeBytes)
                    _status.value = Status.Downloaded
                } catch (e: CancellationException) {
                    _status.value = Status.NotDownloaded
                    throw e
                } catch (e: FailedException) {
                    _status.value = Status.Failed(e.messageRes, e.messageArg)
                } catch (_: Exception) {
                    _status.value = Status.Failed(OfflineBuild.download(CommonR.string.common_error_network))
                }
            }
        }
    }

    /**
     * Puts [source], fetched some other way (#998), where its download would
     * put it, after the same size and digest check. False when it is not
     * [part]'s bytes. Blocking.
     */
    fun install(context: Context, part: IndicXlitModels.Part, source: File): Boolean {
        if (source.length() != part.bytes || !FileDownloader.sha256(source).equals(part.sha256, ignoreCase = true)) {
            return false
        }
        val dir = context.noBackupFilesDir
        val target = pack.file(dir, part)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".import")
        try {
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            target.delete()
            if (!staged.renameTo(target)) throw IOException("could not move $target into place")
        } finally {
            staged.delete()
        }
        refresh(context)
        return true
    }

    /** Deletes the pack, for the storage screen; the keyboard drops what it read with it. */
    fun delete(context: Context) {
        synchronized(this) { job?.cancel() }
        val dir = context.noBackupFilesDir
        for (part in pack.parts) {
            pack.file(dir, part).delete()
            FileDownloader.discard(pack.partFile(dir, part))
        }
        _status.value = Status.NotDownloaded
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private companion object {
        const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    }

    /** Fetches [parts], reporting them after the [alreadyFetched] bytes of the runtime. */
    private suspend fun download(dir: File, parts: List<IndicXlitModels.Part>, alreadyFetched: Long) {
        val folder = pack.dir(dir).apply { mkdirs() }
        val total = parts.sumOf { it.bytes }
        val staged = parts.sumOf { FileDownloader.bytesDone(pack.partFile(dir, it)).coerceAtMost(it.bytes) }
        if (StatFs(folder.path).availableBytes < total - staged + SPACE_MARGIN_BYTES) {
            val neededMb = ((total - staged + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, neededMb.toString())
        }
        var done = alreadyFetched
        val grandTotal = alreadyFetched + total
        for (part in parts) {
            val url = IndicXlitModels.url(part) ?: throw FailedException(R.string.core_intel_semantic_no_source)
            try {
                FileDownloader.fetchVerified(
                    url = url,
                    target = pack.file(dir, part),
                    size = part.bytes,
                    sha256 = part.sha256,
                    source = NetSource.DOWNLOAD_XLIT,
                    part = pack.partFile(dir, part),
                    what = part.fileName,
                ) { written -> _status.value = Status.Downloading(done + written, grandTotal) }
            } catch (e: DownloadException) {
                val (res, arg) = e.screenText()
                throw FailedException(res, arg)
            }
            done += part.bytes
        }
    }
}
