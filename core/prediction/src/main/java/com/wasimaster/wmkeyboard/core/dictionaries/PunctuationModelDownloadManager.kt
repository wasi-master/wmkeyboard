package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.PunctuationModel
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one published smart punctuation model (`tools/wmpunct`, see its
 * README): a `.wmpt` in the data repo, pinned by exact size and SHA-256 so a
 * new model is a new file, never one replaced in place.
 */
object PunctuationModelCatalog {
    /** Where the file lives in the data repo. */
    const val PATH = "models/punct/smart_punct_v1.wmpt"

    /** Exact file size, checked after download. */
    const val BYTES = 2_077_464L

    /** SHA-256 of the file, checked after download. */
    const val SHA256 = "2cdac4e7ff15b902a3cedc6ebd8f61ec579af8de0c9b8b04728fa5b8c288163d"

    /**
     * The languages the model was trained on, for the settings row. v1 is the
     * first model: English and German (e64 d160, three layers, 1.8M
     * parameters), 97.3% of held-out sentence ends right; the sixty-language
     * model from `tools/wmpunct/remote` replaces it as v2.
     */
    val languages: List<String> = listOf("en", "de")

    fun url(): String = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(PATH)
}

/**
 * Fetches [PunctuationModelCatalog]'s model into `models/punct/smart_punct.wmpt`
 * under the app's files, the same shape as [ContextModelDownloadManager]
 * with one model rather than one per language: [ensure] is the silent
 * automatic pass while the setting is on, a failure remembered for the
 * process; [start] is the user asking; [delete] leaves a marker so the
 * automatic pass does not fetch it straight back; [completions] tells the
 * IME it landed. Written to `.part` and renamed, so the model is either
 * whole or absent.
 */
object PunctuationModelDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus
        data class Downloading(val fraction: Float) : DownloadStatus
        data class Downloaded(val sizeBytes: Long) : DownloadStatus
        data object Failed : DownloadStatus
    }

    private const val DIR = "models/punct"
    private const val FILE_NAME = "smart_punct.wmpt"
    private const val DECLINED_NAME = "smart_punct.declined"
    private const val USER_AGENT = "WMKeyboard punctuation model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var givenUp = false

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits once the model is on disk. */
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    private val _state = MutableStateFlow<DownloadStatus>(DownloadStatus.NotDownloaded)
    val state: StateFlow<DownloadStatus> = _state.asStateFlow()

    fun modelFile(filesDir: File): File = File(File(filesDir, DIR), FILE_NAME)

    private fun declinedFile(filesDir: File) = File(File(filesDir, DIR), DECLINED_NAME)

    /** Whether the catalogued model is on disk, by its exact size. */
    fun isDownloaded(filesDir: File): Boolean = modelFile(filesDir).length() == PunctuationModelCatalog.BYTES

    fun bytesOnDisk(filesDir: File): Long = modelFile(filesDir).length()

    fun refresh(filesDir: File) {
        val live = _state.value
        _state.value = when {
            live is DownloadStatus.Downloading -> live
            isDownloaded(filesDir) -> DownloadStatus.Downloaded(modelFile(filesDir).length())
            live == DownloadStatus.Failed -> live
            else -> DownloadStatus.NotDownloaded
        }
    }

    /** Fetches the model if it is not here and the user has not declined it. Silent. */
    fun ensure(filesDir: File) {
        synchronized(this) {
            if (givenUp || job?.isActive == true) return
            if (isDownloaded(filesDir) || declinedFile(filesDir).exists()) return
            _state.value = DownloadStatus.Downloading(0f)
            job = scope.launch {
                try {
                    download(filesDir)
                } finally {
                    if (_state.value is DownloadStatus.Downloading) _state.value = DownloadStatus.NotDownloaded
                }
            }
        }
    }

    /** The user asked: retries a failed transfer and undoes a Delete. */
    fun start(filesDir: File) {
        synchronized(this) { givenUp = false }
        declinedFile(filesDir).delete()
        ensure(filesDir)
    }

    fun cancel() {
        synchronized(this) { job?.cancel() }
    }

    /** Removes the model and remembers the user did not want it. */
    fun delete(filesDir: File) {
        cancel()
        _state.value = DownloadStatus.NotDownloaded
        scope.launch {
            val dir = File(filesDir, DIR)
            modelFile(filesDir).delete()
            File(dir, "$FILE_NAME.part").delete()
            runCatching {
                dir.mkdirs()
                declinedFile(filesDir).createNewFile()
            }
        }
    }

    /**
     * Installs a model the user fetched some other way (an import): copied
     * into place when the reader accepts it. Throws when it is not one.
     */
    fun install(filesDir: File, source: File) {
        cancel()
        val target = modelFile(filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$FILE_NAME.part")
        source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val probe = PunctuationModel.open(part) ?: run {
            part.delete()
            throw IOException("not a punctuation model this keyboard reads: ${source.name}")
        }
        probe.close()
        if (!part.renameTo(target)) throw IOException("rename failed for $target")
        declinedFile(filesDir).delete()
        synchronized(this) { givenUp = false }
        _state.value = DownloadStatus.Downloaded(target.length())
        _completions.tryEmit(Unit)
    }

    private fun download(filesDir: File) {
        if (isDownloaded(filesDir)) {
            _state.value = DownloadStatus.Downloaded(modelFile(filesDir).length())
            return
        }
        val ok = runCatching { fetch(filesDir) }.isSuccess
        if (ok) {
            _state.value = DownloadStatus.Downloaded(modelFile(filesDir).length())
            _completions.tryEmit(Unit)
        } else {
            _state.value = DownloadStatus.Failed
            synchronized(this) { givenUp = true }
        }
    }

    private fun fetch(filesDir: File) {
        val target = modelFile(filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$FILE_NAME.part")
        val url = PunctuationModelCatalog.url()
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_NGRAM, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} for $url")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: PunctuationModelCatalog.BYTES
            var read = 0L
            netCall.countIn(connection.inputStream).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        _state.value = DownloadStatus.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (part.length() != PunctuationModelCatalog.BYTES) {
                throw IOException("size mismatch for $url: ${part.length()} != ${PunctuationModelCatalog.BYTES}")
            }
            if (!MessageDigest.isEqual(hex(PunctuationModelCatalog.SHA256), hex(ContextModelIntegrity.sha256(part)))) {
                throw IOException("SHA-256 mismatch for $url")
            }
            val probe = PunctuationModel.open(part) ?: throw IOException("not a model this keyboard reads: $url")
            probe.close()
            if (!part.renameTo(target)) throw IOException("rename failed for $target")
        } catch (t: Throwable) {
            part.delete()
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
