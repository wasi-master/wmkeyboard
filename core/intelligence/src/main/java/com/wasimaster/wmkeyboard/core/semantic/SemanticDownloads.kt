package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.ocr.screenText
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.download.DownloadException
import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.netlog.NetSource
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The downloads of [SemanticModels]' two packs, the LiteRT interpreter
 * first wherever it is delivered on demand (Play's `:feature:litert`, or the
 * pinned library on a GitHub or F-Droid build), so the settings row shows one
 * bar for both, as the sticker editor's background remover does.
 *
 * Each file goes through [FileDownloader]: parallel ranges into a `.part`
 * that resumes piece by piece, and its name only after its SHA-256 matched.
 * Process-wide, like [com.wasimaster.wmkeyboard.core.ocr.OcrPacks]: the
 * settings row and the keyboard collect the same [states].
 */
object SemanticDownloads {

    sealed interface Status {
        data object NotDownloaded : Status

        /** [total] is every byte this pack still had to fetch when it started, the module's included. */
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageRes] is the text to show; [messageArg] its one argument, or "". */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<SemanticModels.Pack, Job>()

    private val _states = MutableStateFlow<Map<SemanticModels.Pack, Status>>(emptyMap())

    val states: StateFlow<Map<SemanticModels.Pack, Status>> = _states.asStateFlow()

    /** Seeds [states] from disk; call when a screen that shows them appears. */
    fun refresh(context: Context) {
        val dir = context.noBackupFilesDir
        _states.update { current ->
            SemanticModels.Pack.entries.associateWith { pack ->
                val running = synchronized(jobs) { jobs[pack]?.isActive == true }
                when {
                    running && current[pack] != null -> current.getValue(pack)
                    SemanticModels.isDownloaded(dir, pack) -> Status.Downloaded
                    else -> current[pack] as? Status.Failed ?: Status.NotDownloaded
                }
            }
        }
    }

    /** Whether [pack] and what runs it are both here. Never downloads anything. */
    fun ready(context: Context, pack: SemanticModels.Pack): Boolean =
        SemanticModels.SUPPORTED && FeatureModules.litert.installed && SemanticModels.isDownloaded(context.noBackupFilesDir, pack) &&
            (pack == SemanticModels.Pack.TEXT || SemanticModels.isDownloaded(context.noBackupFilesDir, SemanticModels.Pack.TEXT))

    /**
     * Downloads [pack], and the text pack first when [pack] is the pictures
     * one, since a picture is only ever found by a typed query. Idempotent.
     */
    fun start(context: Context, pack: SemanticModels.Pack) {
        if (!SemanticModels.SUPPORTED) return
        val dir = context.applicationContext.noBackupFilesDir
        if (pack == SemanticModels.Pack.PICTURES) start(context, SemanticModels.Pack.TEXT)
        synchronized(jobs) {
            if (jobs[pack]?.isActive == true) return
            val missing = SemanticModels.parts(pack).filter { !SemanticModels.isDownloaded(dir, it) }
            set(pack, Status.Downloading(0, missing.sumOf { it.bytes }))
            FeatureModules.litert.requestInstall()
            jobs[pack] = scope.launch {
                try {
                    val module = FeatureModules.litert
                    val moduleHere = module.awaitInstalled { bytes, total ->
                        if (total > 0) set(pack, Status.Downloading(bytes, total + missing.sumOf { it.bytes }))
                    }
                    if (!moduleHere) throw FailedException(OfflineBuild.library(R.string.core_intel_semantic_module_error))
                    download(dir, pack, missing)
                    set(pack, Status.Downloaded)
                } catch (e: CancellationException) {
                    set(pack, Status.NotDownloaded)
                    throw e
                } catch (e: FailedException) {
                    set(pack, Status.Failed(e.messageRes, e.messageArg))
                } catch (_: Exception) {
                    set(pack, Status.Failed(OfflineBuild.download(CommonR.string.common_error_network)))
                }
            }
        }
    }

    fun cancel(pack: SemanticModels.Pack) {
        synchronized(jobs) { jobs[pack]?.cancel() }
    }

    /** Deletes [pack]'s files; the text pack takes the pictures with it, which cannot work alone. */
    fun delete(context: Context, pack: SemanticModels.Pack) {
        val dir = context.noBackupFilesDir
        val packs = if (pack == SemanticModels.Pack.TEXT) SemanticModels.Pack.entries else listOf(pack)
        for (p in packs) {
            cancel(p)
            for (part in SemanticModels.parts(p)) {
                SemanticModels.file(dir, part).delete()
                FileDownloader.discard(SemanticModels.partFile(dir, part))
            }
            set(p, Status.NotDownloaded)
        }
    }

    /**
     * Installs one model file the user fetched by hand: its release asset or
     * its plain name, checked against the same digest a download is.
     * Returns the part it was, or null for any other file, which is left out.
     */
    fun install(context: Context, name: String, source: File): SemanticModels.Part? {
        val part = SemanticModels.partOf(name) ?: return null
        if (source.length() != part.bytes || FileDownloader.sha256(source) != part.sha256) return null
        val dir = context.noBackupFilesDir
        val staged = SemanticModels.partFile(dir, part)
        staged.parentFile?.mkdirs()
        source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
        val target = SemanticModels.file(dir, part)
        target.delete()
        if (!staged.renameTo(target)) {
            staged.delete()
            return null
        }
        refresh(context)
        return part
    }

    private fun set(pack: SemanticModels.Pack, status: Status) {
        _states.update { it + (pack to status) }
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private suspend fun download(dir: File, pack: SemanticModels.Pack, parts: List<SemanticModels.Part>) {
        val folder = SemanticModels.dir(dir).apply { mkdirs() }
        val total = parts.sumOf { it.bytes }
        val alreadyStaged = parts.sumOf { FileDownloader.bytesDone(SemanticModels.partFile(dir, it)).coerceAtMost(it.bytes) }
        if (StatFs(folder.path).availableBytes < total - alreadyStaged + SPACE_MARGIN_BYTES) {
            val neededMb = ((total - alreadyStaged + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, neededMb.toString())
        }
        var done = 0L
        for (part in parts) {
            val url = SemanticModels.url(part) ?: throw FailedException(R.string.core_intel_semantic_no_source)
            try {
                FileDownloader.fetchVerified(
                    url = url,
                    target = SemanticModels.file(dir, part),
                    size = part.bytes,
                    sha256 = part.sha256,
                    source = NetSource.DOWNLOAD_SEMANTIC,
                    part = SemanticModels.partFile(dir, part),
                    what = part.fileName,
                ) { written -> set(pack, Status.Downloading(done + written, total)) }
            } catch (e: DownloadException) {
                val (res, arg) = e.screenText()
                throw FailedException(res, arg)
            }
            done += part.bytes
        }
    }
}
