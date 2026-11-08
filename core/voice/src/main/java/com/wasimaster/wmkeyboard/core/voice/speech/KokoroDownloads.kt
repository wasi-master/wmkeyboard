package com.wasimaster.wmkeyboard.core.voice.speech

import android.os.StatFs
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.util.Bzip2InputStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.PinnedDownload
import com.wasimaster.wmkeyboard.voice.R
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Downloads Kokoro ([KokoroVoices]): sherpa-onnx's `.tar.bz2`, resumable and
 * checked against its pinned SHA-256 ([PinnedDownload]), then unpacked into
 * `filesDir/kokoro/<package>/`, keeping only [KokoroVoices.KEEP], and the
 * archive deleted. The model is Apache-2.0, so nothing asks first.
 */
object KokoroDownloads {

    /** The archive and what is unpacked from it (about 160 MB) at once, with room to spare. */
    private const val SPACE_NEEDED = KokoroVoices.ARCHIVE_BYTES + 170L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

    val status: StateFlow<Status> = _status.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    private fun archive(filesDir: File) = File(KokoroVoices.rootDir(filesDir), "${KokoroVoices.PACKAGE}.tar.bz2")

    /** Seeds [status] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        if (isBusy) return
        val partial = archive(filesDir).length()
        _status.value = when {
            KokoroVoices.isDownloaded(filesDir) -> Status.Downloaded
            partial > 0 -> Status.Paused(partial, KokoroVoices.ARCHIVE_BYTES)
            else -> Status.NotDownloaded
        }
    }

    fun start(filesDir: File) {
        if (isBusy || KokoroVoices.isDownloaded(filesDir)) return
        _status.value = Status.Downloading(archive(filesDir).length(), KokoroVoices.ARCHIVE_BYTES)
        job = scope.launch {
            try {
                download(filesDir)
                _status.value = Status.Downloaded
            } catch (e: CancellationException) {
                val partial = archive(filesDir).length()
                _status.value = if (partial > 0) Status.Paused(partial, KokoroVoices.ARCHIVE_BYTES) else Status.NotDownloaded
                throw e
            } catch (e: PinnedDownload.FailedException) {
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
        KokoroSpeech.forget()
        KokoroVoices.rootDir(filesDir).deleteRecursively()
        _status.value = Status.NotDownloaded
    }

    private suspend fun download(filesDir: File) {
        val root = KokoroVoices.rootDir(filesDir).apply { mkdirs() }
        val tarball = archive(filesDir)
        if (StatFs(root.path).availableBytes < SPACE_NEEDED - tarball.length()) {
            throw PinnedDownload.FailedException(
                R.string.core_voice_download_space_error,
                ((SPACE_NEEDED - tarball.length()) / 1e6).roundToInt().toString(),
            )
        }
        PinnedDownload.fetch(
            KokoroVoices.URL, tarball, KokoroVoices.ARCHIVE_BYTES, KokoroVoices.ARCHIVE_SHA256,
            "${KokoroVoices.PACKAGE}.tar.bz2", NetSource.DOWNLOAD_VOICE_OUT,
        ) { written -> _status.value = Status.Downloading(written, KokoroVoices.ARCHIVE_BYTES) }
        // Unpacked into a folder of its own and moved into place whole, so a
        // half-unpacked model is never taken for a downloaded one.
        val staging = File(root, "${KokoroVoices.PACKAGE}.part").apply { deleteRecursively(); mkdirs() }
        try {
            BufferedInputStream(tarball.inputStream(), 1 shl 16).use { raw ->
                Bzip2InputStream(raw).use { bz -> untar(bz, staging) }
            }
            val target = KokoroVoices.dir(filesDir)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("could not move the Kokoro files into place")
        } finally {
            staging.deleteRecursively()
        }
        tarball.delete()
        if (!KokoroVoices.isDownloaded(filesDir)) throw IOException("the Kokoro archive lacked a file")
    }

    /**
     * Writes the archive's [KokoroVoices.KEEP] files flat into [into]: ustar
     * headers, regular files only, names taken after their last slash, so no
     * entry can write outside [into].
     */
    private suspend fun untar(input: InputStream, into: File) {
        val header = ByteArray(512)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
            val prefix = String(header, 345, 155, Charsets.UTF_8).substringBefore('\u0000')
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8)
                ?: throw IOException("bad tar header")
            val type = header[156].toInt().toChar()
            val base = (if (prefix.isNotEmpty()) "$prefix/$name" else name).substringAfterLast('/')
            val keep = (type == '0' || type == '\u0000') && base in KokoroVoices.KEEP
            var left = size
            if (keep) {
                File(into, base).outputStream().use { out ->
                    while (left > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("tar cut off")
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
            } else {
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("tar cut off")
                    left -= n
                }
            }
            // Entries are padded to 512 bytes.
            val pad = ((512 - size % 512) % 512).toInt()
            if (pad > 0 && !readFully(input, ByteArray(pad))) throw IOException("tar cut off")
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) {
                if (at == 0) return false
                throw IOException("tar cut off")
            }
            at += n
        }
        return true
    }
}
