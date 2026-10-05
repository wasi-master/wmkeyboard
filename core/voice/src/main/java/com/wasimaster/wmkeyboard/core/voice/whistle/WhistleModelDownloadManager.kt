package com.wasimaster.wmkeyboard.core.voice.whistle

import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resumable downloader for the public, Apache-2.0 Whistle model. */
object WhistleModelDownloadManager {
    sealed interface Status {
        data object Missing : Status
        data class Downloading(val bytes: Long, val total: Long) : Status
        data class Paused(val bytes: Long, val total: Long) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private const val MODEL_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/b358ddadd89b7a713b5aa131f23032d3cca1b251/whistle.cact"
    private const val USER_AGENT = "WMKeyboard Whistle model downloader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val mutableStatus = MutableStateFlow<Status>(Status.Missing)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    val isBusy: Boolean get() = job?.isActive == true

    fun refresh(filesDir: File) {
        if (isBusy) return
        mutableStatus.value = when {
            WhistleModelStore.isDownloaded(filesDir) -> Status.Ready
            WhistleModelStore.partialFile(filesDir).length() > 0 -> Status.Paused(
                WhistleModelStore.partialFile(filesDir).length(), WhistleModelStore.MODEL_BYTES,
            )
            else -> Status.Missing
        }
    }

    fun start(filesDir: File) {
        if (isBusy || WhistleModelStore.isDownloaded(filesDir)) return
        job = scope.launch {
            val target = WhistleModelStore.modelFile(filesDir)
            val part = WhistleModelStore.partialFile(filesDir)
            target.parentFile?.mkdirs()
            try {
                val needed = WhistleModelStore.MODEL_BYTES - part.length()
                if (StatFs(target.parentFile!!.path).availableBytes < needed + 8L * 1024 * 1024) {
                    throw IllegalStateException("Not enough free storage for the Whistle model")
                }
                var offset = part.length()
                var lastUpdate = 0L
                val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                    val code = connection.responseCode
                    if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                        part.delete()
                        offset = 0
                    } else if (code !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                        throw IllegalStateException("Whistle download returned HTTP $code")
                    }
                    RandomAccessFile(part, "rw").use { output ->
                        if (code == HttpURLConnection.HTTP_OK) output.setLength(0) else output.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        connection.inputStream.use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                val total = output.filePointer
                                if (System.currentTimeMillis() - lastUpdate >= 250) {
                                    mutableStatus.value = Status.Downloading(total, WhistleModelStore.MODEL_BYTES)
                                    lastUpdate = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (!WhistleModelStore.hasValidChecksum(part)) {
                    throw IllegalStateException("Downloaded Whistle model failed its SHA-256 check")
                }
                if (!part.renameTo(target)) throw IllegalStateException("Could not install the Whistle model")
                mutableStatus.value = Status.Ready
            } catch (cancelled: CancellationException) {
                mutableStatus.value = if (part.length() > 0) {
                    Status.Paused(part.length(), WhistleModelStore.MODEL_BYTES)
                } else Status.Missing
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = Status.Failed(error.message ?: "Whistle download failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(filesDir: File) {
        cancel()
        WhistleModelStore.delete(filesDir)
        mutableStatus.value = Status.Missing
    }
}
