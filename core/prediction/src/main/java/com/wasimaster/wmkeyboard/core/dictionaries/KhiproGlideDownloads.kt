package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The Khipro spellings of the Bangla word list, worked out ahead of time and
 * published beside it in the data repo as `bn_khipro_glide.txt.gz` (#593),
 * and Khipro Dark's as `bn_khipro_dark_glide.txt.gz`: the two editions spell
 * most words differently (হবে is `hobe` in Classic, `hbe` in Dark), so each
 * has its own table, fetched for the edition that is glided on.
 *
 * Gliding on the Khipro grid needs every word's Khipro spelling, and working
 * them out means running Khipro backwards over the whole list: minutes of
 * every core on a phone, which ran it hot. The data repo does that once, on a
 * computer, and the phone reads the answers ([KhiproGlideSpellings] takes the
 * file and spells on the device only the words it does not list).
 *
 * Fetched the first time the Khipro grid is glided on, kept at
 * `dict/bn/khipro_glide.txt.gz` (`khipro_dark_glide.txt.gz`), and checked (it has to inflate and hold
 * spellings) before the `.part` is renamed into place, so presence means
 * valid. [failed] is set when the fetch could not happen (no connection, or a
 * build with no internet permission), which is when the phone falls back to
 * working the spellings out itself.
 */
class KhiproGlideDownloads private constructor(
    /** The name the data repo publishes it under, which an offline import is recognised by. */
    val publishedName: String,
    private val fileName: String,
) {

    private val repoPath = "data/bn/$publishedName"
    private var job: Job? = null

    /** The last fetch failed; cleared by the next [start] or [install]. */
    @Volatile
    var failed: Boolean = false
        private set

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once the file has landed, or a fetch has failed, and glide should be set up again. */
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    fun file(filesDir: File): File = File(File(File(filesDir, "dict"), "bn"), fileName)

    fun isDownloaded(filesDir: File): Boolean = file(filesDir).isFile

    val isBusy: Boolean get() = job?.isActive == true

    /** Fetches the file unless it is here or on its way. */
    fun start(filesDir: File) {
        synchronized(this) {
            if (job?.isActive == true || isDownloaded(filesDir)) return
            failed = false
            job = scope.launch {
                failed = runCatching { download(filesDir) }.isFailure
                _completions.tryEmit(Unit)
            }
        }
    }

    /**
     * Installs [source], a copy of the published file fetched elsewhere, with
     * the same check a download gets. Throws if it is not this file.
     */
    fun install(filesDir: File, source: File) {
        check(source.length() <= MAX_BYTES && holdsSpellings(source)) { "${source.name} is not a Khipro glide table" }
        val target = file(filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
        } finally {
            part.delete()
        }
        failed = false
        _completions.tryEmit(Unit)
    }

    private fun download(filesDir: File) {
        val target = file(filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val url = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(repoPath)
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            netCall.countIn(connection.inputStream).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("$url is larger than the Khipro glide table")
                        out.write(buffer, 0, read)
                    }
                }
            }
            check(holdsSpellings(part)) { "$url is not a Khipro glide table" }
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
        } catch (t: Throwable) {
            part.delete()
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }

    /** Whether [file] inflates and its first entry is a word, a tab and a spelling. */
    private fun holdsSpellings(file: File): Boolean = runCatching {
        GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") }.take(1).any { line ->
                val tab = line.indexOf('\t')
                tab > 0 && tab < line.length - 1
            }
        }
    }.getOrDefault(false)

    companion object {
        /** Khipro Classic's table. */
        val CLASSIC = KhiproGlideDownloads("bn_khipro_glide.txt.gz", "khipro_glide.txt.gz")

        /** Khipro Dark's table. */
        val DARK = KhiproGlideDownloads("bn_khipro_dark_glide.txt.gz", "khipro_dark_glide.txt.gz")

        /** Both editions' tables. */
        val ALL = listOf(CLASSIC, DARK)

        /** The table of Khipro Dark if [dark], Classic if not. */
        fun of(dark: Boolean): KhiproGlideDownloads = if (dark) DARK else CLASSIC

        /** The table published as [name], or null when that is neither's name. */
        fun byPublishedName(name: String): KhiproGlideDownloads? = ALL.firstOrNull { it.publishedName == name }

        private const val USER_AGENT = "WMKeyboard Khipro glide downloader"

        /** Far above the real file (about 4 MB); a response past it is not this file. */
        private const val MAX_BYTES = 32L * 1024 * 1024

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
