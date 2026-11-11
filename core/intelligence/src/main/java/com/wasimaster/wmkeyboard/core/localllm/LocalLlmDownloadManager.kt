package com.wasimaster.wmkeyboard.core.localllm

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Downloads catalog models from Hugging Face into [LocalLlmStore]'s layout.
 *
 * Process-level singleton with its own IO scope, so progress survives
 * recomposition, rotation, and screen navigation; the settings UI just
 * collects [states]. Deliberately no WorkManager or foreground service
 * (the app uses neither): leaving the app can stop an in-flight download,
 * but [FileDownloader]'s `.part` and its resume map make Resume pick up
 * exactly where it stopped — including after process death.
 *
 * One download at a time: these are multi-GB files and parallel downloads
 * just split the same bandwidth while doubling failure modes.
 */
object LocalLlmDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** [total] is -1 while unknown (no Content-Length yet). */
        data class Downloading(val bytes: Long, val total: Long) : DownloadStatus

        /** Cancelled or interrupted with a `.part` on disk — resumable. */
        data class Paused(val bytes: Long, val total: Long) : DownloadStatus
        data object Downloaded : DownloadStatus

        /**
         * [messageRes] is the text to show the user. [messageArg] is the one
         * format argument that text takes, or "" when it takes none. The UI
         * resolves the pair together:
         * `if (messageArg.isEmpty()) stringResource(messageRes)`
         * `else stringResource(messageRes, messageArg)`.
         */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String = "",
        ) : DownloadStatus
    }

    enum class FailReason { GATED_NO_TOKEN, LICENSE_NOT_ACCEPTED, NETWORK, NO_SPACE, OTHER }

    /**
     * A byte count for a download readout. Decimal units, because that is what
     * the model pages the sizes come from quote.
     *
     * Lives here rather than in either screen: the settings app and the AI panel
     * both draw the same progress line, and two copies would drift.
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    /**
     * The download in flight right now, as `model id to status`, or null when
     * nothing is downloading. The AI panel shows this so a download started in
     * the settings app is visible from the keyboard.
     */
    fun activeDownload(states: Map<String, DownloadStatus>): Pair<String, DownloadStatus>? {
        val id = activeId ?: return null
        val status = states[id] ?: return null
        return if (status is DownloadStatus.Downloading) id to status else null
    }

    /** Free space to leave untouched after a download completes. */
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeId: String? = null

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Model id → status for every catalog entry touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    val isBusy: Boolean get() = activeJob?.isActive == true

    /**
     * Seeds [states] from disk; call when the model manager UI appears. A
     * model counts as downloaded when either of its builds is there (#804);
     * a half-fetched one of either is the Paused one.
     */
    fun refresh(filesDir: File) {
        val soc = NpuSupport.soc
        _states.update { current ->
            LocalLlmCatalog.models.associate { model ->
                val active = current[model.id]
                val part = listOfNotNull(
                    LocalLlmStore.partFile(filesDir, model) to model.downloadBytes,
                    model.projector?.let { LocalLlmStore.partFile(filesDir, model, it.fileName) to model.downloadBytes },
                    LocalLlmCatalog.npuBuild(model, soc)
                        ?.let { LocalLlmStore.partFile(filesDir, model, it.fileName) to it.sizeBytes },
                ).firstOrNull { it.first.isFile }
                model.id to when {
                    // Never clobber a live download's progress state.
                    model.id == activeId && isBusy && active != null -> active
                    LocalLlmStore.isDownloaded(filesDir, model) -> DownloadStatus.Downloaded
                    part != null -> DownloadStatus.Paused(FileDownloader.bytesDone(part.first), part.second)
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    /**
     * Fetches [model]: its NPU build for [npuSoc] when one is given and the
     * model has it, the standard file otherwise. Progress is reported under
     * the model's id either way, one download at a time as ever. A GGUF
     * vision model's projector comes first, in the same progress: the model
     * file is what makes the row Downloaded, so it lands last.
     */
    fun start(filesDir: File, model: LocalLlmModel, hfToken: String, npuSoc: String? = null) {
        if (isBusy) return
        activeId = model.id
        val (fileName, sizeBytes) = LocalLlmCatalog.downloadTarget(model, npuSoc)
        val part = LocalLlmStore.partFile(filesDir, model, fileName)
        // The projector goes with the standard file only: no NPU build sees.
        val projector = model.projector?.takeIf { fileName == model.fileName }
            ?.takeIf { LocalLlmStore.projectorFile(filesDir, model)?.isFile != true }
        val projectorBytes = projector?.sizeBytes ?: 0L
        val total = sizeBytes + projectorBytes
        val projectorPart = projector?.let { LocalLlmStore.partFile(filesDir, model, it.fileName) }
        set(
            model.id,
            DownloadStatus.Downloading(
                (projectorPart?.let(FileDownloader::bytesDone) ?: projectorBytes) + FileDownloader.bytesDone(part),
                total,
            ),
        )
        activeJob = scope.launch {
            try {
                if (projector != null && projectorPart != null) {
                    fetchChecked(filesDir, model, projector.fileName, projector.sizeBytes, projectorPart, hfToken, 0L, total)
                }
                fetchChecked(filesDir, model, fileName, sizeBytes, part, hfToken, projectorBytes, total)
                set(model.id, DownloadStatus.Downloaded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Only advertise a resumable Paused state when the .part still
                // exists. delete() cancels, removes the file, and sets
                // NotDownloaded; without this guard the unwinding coroutine
                // overwrites that with Paused(0, size), resurrecting a model
                // the user just deleted.
                if (part.isFile) {
                    set(model.id, DownloadStatus.Paused(projectorBytes + FileDownloader.bytesDone(part), total))
                } else if (projectorPart?.isFile == true) {
                    set(model.id, DownloadStatus.Paused(FileDownloader.bytesDone(projectorPart), total))
                }
                throw e
            } catch (e: FailedException) {
                set(model.id, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
            } catch (_: Exception) {
                // IOException mid-stream leaves a resumable .part behind. The
                // system writes its own message in English only, so the shared
                // network line goes out instead of the text of the exception.
                set(
                    model.id,
                    DownloadStatus.Failed(FailReason.NETWORK, OfflineBuild.download(CommonR.string.common_error_network)),
                )
            } finally {
                activeId = null
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    /**
     * Downloads [fileName] of [model] into [part], checks it against its pin
     * and moves it into place. Progress counts from [progressBase] of
     * [progressTotal], for a download of two files.
     */
    private suspend fun fetchChecked(
        filesDir: File,
        model: LocalLlmModel,
        fileName: String,
        sizeBytes: Long,
        part: File,
        hfToken: String,
        progressBase: Long,
        progressTotal: Long,
    ) {
        downloadInto(model, fileName, sizeBytes, part, hfToken, progressBase, progressTotal)
        // A pinned file (the GGUF rows) counts only when its bytes
        // are the ones the catalog names; a resumed download that
        // spliced two uploads together, or a wrong file, never does.
        expectedSha256(model, fileName)?.let { expected ->
            if (!sha256Of(part).equals(expected, ignoreCase = true)) {
                FileDownloader.discard(part)
                throw FailedException(FailReason.OTHER, R.string.core_intel_llm_download_error_checksum)
            }
        }
        check(part.renameTo(LocalLlmStore.modelFile(filesDir, model, fileName))) {
            "could not move the finished download into place"
        }
    }

    /** The SHA-256 [fileName] of [model] must have, when the catalog pins one: the model's, its projector's or an NPU build's. */
    fun expectedSha256(model: LocalLlmModel, fileName: String = model.fileName): String? =
        model.sha256?.takeIf { fileName == model.fileName }
            ?: model.projector?.takeIf { it.fileName == fileName }?.sha256
            ?: model.npuBuilds.firstOrNull { it.fileName == fileName }?.sha256

    /** Hex SHA-256 of [file]'s contents. Reads the whole file: IO dispatcher only. */
    fun sha256Of(file: File): String = FileDownloader.sha256(file)

    fun delete(filesDir: File, model: LocalLlmModel) {
        if (model.id == activeId) cancel()
        LocalLlmStore.delete(filesDir, model)
        set(model.id, DownloadStatus.NotDownloaded)
    }

    private fun set(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
    }

    /**
     * A download failure that already knows which message the UI should show.
     * The exception's own `message` stays null: the text lives in [messageRes]
     * so it follows the app language.
     */
    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String = "",
    ) : IOException()

    private suspend fun downloadInto(
        model: LocalLlmModel,
        fileName: String,
        sizeBytes: Long,
        part: File,
        hfToken: String,
        progressBase: Long = 0L,
        /** The whole download's size when it is more than this file; -1 for this file's. */
        progressTotal: Long = -1L,
    ) {
        part.parentFile?.mkdirs()
        val resumeFrom = FileDownloader.bytesDone(part)

        val free = StatFs(part.parentFile!!.path).availableBytes
        if (free < sizeBytes - resumeFrom + SPACE_MARGIN_BYTES) {
            val neededGb = (sizeBytes - resumeFrom + SPACE_MARGIN_BYTES) / 1e9
            throw FailedException(
                FailReason.NO_SPACE,
                R.string.core_intel_llm_download_error_no_space,
                "%.1f".format(neededGb),
            )
        }

        val url = LocalLlmCatalog.downloadUrl(model, fileName)
        // The catalog's sizes are approximate, so the server's length is the
        // file's; the pinned rows' hash decides after it lands.
        val headers = if (hfToken.isNotBlank()) mapOf("Authorization" to "Bearer $hfToken") else emptyMap()
        try {
            FileDownloader.fetch(url, part, null, NetSource.DOWNLOAD_LLM, headers = headers) { written ->
                set(
                    model.id,
                    DownloadStatus.Downloading(progressBase + written, if (progressTotal >= 0) progressTotal else sizeBytes),
                )
            }
        } catch (e: DownloadException) {
            throw when (e) {
                is DownloadException.Http -> when (e.status) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> FailedException(
                        FailReason.GATED_NO_TOKEN,
                        if (hfToken.isBlank()) {
                            R.string.core_intel_llm_download_error_gated_no_token
                        } else {
                            R.string.core_intel_llm_download_error_token_rejected
                        },
                    )
                    HttpURLConnection.HTTP_FORBIDDEN -> FailedException(
                        FailReason.LICENSE_NOT_ACCEPTED,
                        R.string.core_intel_llm_download_error_licence,
                    )
                    else -> FailedException(FailReason.OTHER, R.string.core_intel_llm_download_error_http, e.status.toString())
                }
                is DownloadException.Interrupted ->
                    FailedException(FailReason.NETWORK, R.string.core_intel_llm_download_error_truncated)
                is DownloadException.Blocked ->
                    FailedException(FailReason.NETWORK, CommonR.string.common_error_download_held)
                is DownloadException.WrongSize, is DownloadException.Mismatch ->
                    FailedException(FailReason.OTHER, R.string.core_intel_llm_download_error_checksum)
            }
        }
    }
}
