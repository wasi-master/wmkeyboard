package com.wasimaster.wmkeyboard.core.voice.whistle

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.voice.R
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

/**
 * Downloads the Whistle model into [WhistleModelStore]. One file, so one
 * status rather than [com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager]'s
 * map, otherwise the same spine: its own IO scope so progress outlives the
 * screen, [FileDownloader]'s resumable `.part`, and the file is only moved
 * into place once its SHA-256 matches.
 */
object WhistleModelDownloadManager {

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status

        /** Cancelled or interrupted with a `.part` on disk: resumable. */
        data class Paused(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageArg] is the one argument [messageRes] takes, or "" for none. */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [status] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        if (isBusy) return
        val partial = FileDownloader.bytesDone(WhistleModelStore.partialFile(filesDir))
        _status.value = when {
            WhistleModelStore.isDownloaded(filesDir) -> Status.Downloaded
            partial > 0 -> Status.Paused(partial, WhistleModelStore.MODEL_BYTES)
            else -> Status.NotDownloaded
        }
    }

    fun start(filesDir: File) {
        if (isBusy || WhistleModelStore.isDownloaded(filesDir)) return
        val part = WhistleModelStore.partialFile(filesDir)
        _status.value = Status.Downloading(FileDownloader.bytesDone(part), WhistleModelStore.MODEL_BYTES)
        job = scope.launch {
            try {
                download(filesDir)
                _status.value = Status.Downloaded
            } catch (e: CancellationException) {
                // delete() cancels and then wipes; the unwinding job must not
                // bring a Paused back for a file that is gone.
                _status.value = if (FileDownloader.bytesDone(part) > 0) {
                    Status.Paused(FileDownloader.bytesDone(part), WhistleModelStore.MODEL_BYTES)
                } else {
                    Status.NotDownloaded
                }
                throw e
            } catch (e: FailedException) {
                _status.value = Status.Failed(e.messageRes, e.messageArg)
            } catch (_: Exception) {
                _status.value = Status.Failed(OfflineBuild.download(CommonR.string.common_error_network))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(filesDir: File) {
        cancel()
        WhistleModelStore.delete(filesDir)
        _status.value = Status.NotDownloaded
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private suspend fun download(filesDir: File) {
        val target = WhistleModelStore.modelFile(filesDir)
        val part = WhistleModelStore.partialFile(filesDir)
        target.parentFile?.mkdirs()
        val needed = WhistleModelStore.MODEL_BYTES - FileDownloader.bytesDone(part)
        if (StatFs(target.parentFile!!.path).availableBytes < needed + SPACE_MARGIN_BYTES) {
            throw FailedException(
                R.string.core_voice_download_space_error,
                ((needed + SPACE_MARGIN_BYTES) / 1e6).roundToInt().toString(),
            )
        }
        try {
            FileDownloader.fetch(WhistleModelStore.MODEL_URL, part, WhistleModelStore.MODEL_BYTES, NetSource.DOWNLOAD_WHISPER) {
                _status.value = Status.Downloading(it, WhistleModelStore.MODEL_BYTES)
            }
        } catch (e: DownloadException) {
            throw when (e) {
                is DownloadException.Http -> FailedException(R.string.core_voice_download_http_error, e.status.toString())
                is DownloadException.Interrupted -> FailedException(R.string.core_voice_download_interrupted_error)
                is DownloadException.Blocked -> FailedException(CommonR.string.common_error_download_held)
                is DownloadException.WrongSize, is DownloadException.Mismatch ->
                    FailedException(R.string.core_voice_whistle_checksum_error)
            }
        }
        if (part.length() < WhistleModelStore.MODEL_BYTES) {
            throw FailedException(R.string.core_voice_download_interrupted_error)
        }
        if (!WhistleModelStore.hasValidChecksum(part)) {
            // Wrong bytes cannot be resumed into right ones: start over next time.
            FileDownloader.discard(part)
            throw FailedException(R.string.core_voice_whistle_checksum_error)
        }
        target.delete()
        check(part.renameTo(target)) { "could not move the finished download into place" }
    }
}
