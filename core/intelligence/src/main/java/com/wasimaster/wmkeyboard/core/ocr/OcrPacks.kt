package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.download.DownloadException
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
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
 * The Tesseract language data on disk, and its downloads; and PaddleOCR's
 * models, one pack named [PaddleOcrModel.PACK] made of three files in a
 * directory of their own, the graphs in this build's runtime's format (see
 * [PaddleOcrModel]).
 *
 * Packs live in `filesDir/ocr/tessdata/<pack>.traineddata`, which is the
 * directory handed to Tesseract as its data path. A download goes through
 * [FileDownloader] into a `.part` and is renamed into place only when
 * complete, so a pack that exists is whole; an interrupted one continues
 * where it stopped, and Delete clears what it left.
 *
 * Process-wide like the Whisper downloader: the keyboard panel and the
 * settings page both collect [states], and a download started in one shows in
 * the other.
 */
object OcrPacks {

    sealed interface Status {
        data object NotDownloaded : Status

        /** [total] is the expected size, from the server or the pack table. */
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /**
         * [messageRes] is the text to show. [messageArg] is its one format
         * argument, or "" when it takes none.
         */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Pack name -> status, for every pack looked at so far. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    /** The directory Tesseract reads packs from. */
    fun dataDir(filesDir: File): File = File(filesDir, "ocr/tessdata")

    fun file(filesDir: File, pack: String): File = File(dataDir(filesDir), "$pack.traineddata")

    fun isDownloaded(filesDir: File, pack: String): Boolean =
        if (pack == PaddleOcrModel.PACK) PaddleOcrModel.isDownloaded(filesDir) else file(filesDir, pack).isFile

    /** Every pack on disk, whether or not an enabled language still uses it. */
    fun downloaded(filesDir: File): List<String> =
        dataDir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".traineddata") }
            ?.map { it.name.removeSuffix(".traineddata") }
            ?.sorted()
            .orEmpty() + listOfNotNull(PaddleOcrModel.PACK.takeIf { PaddleOcrModel.isDownloaded(filesDir) })

    /**
     * The gate whose library reads [pack]: Tesseract's own, or Paddle's
     * runtime ([PaddleOcr.module]: LiteRT, or MNN in the sticker generator's
     * library). A Play module or a native download; installed wherever the
     * library is in the APK.
     */
    fun moduleFor(pack: String) =
        if (pack == PaddleOcrModel.PACK) PaddleOcr.module else FeatureModules.ocr

    /** Seeds [states] from disk for [packs]; call when a list of packs appears. */
    fun refresh(filesDir: File, packs: Collection<String>) {
        _states.update { current ->
            current + packs.associateWith { pack ->
                val active = current[pack]
                when {
                    synchronized(jobs) { jobs[pack]?.isActive == true } && active != null -> active
                    isDownloaded(filesDir, pack) -> Status.Downloaded
                    else -> Status.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, pack: String) {
        synchronized(jobs) {
            if (jobs[pack]?.isActive == true) return
            set(pack, Status.Downloading(0, OcrLanguages.sizeOf(pack)))
            // Tesseract itself is the on-demand :feature:ocr split on Play (and
            // Paddle's runtime :feature:litert, or the imagegen gate's MNN),
            // asked for alongside the first pack so both arrive together. A
            // no-op wherever the library is in the APK.
            val module = moduleFor(pack)
            module.requestInstall()
            jobs[pack] = scope.launch {
                try {
                    // A pack already on disk only waits for the library.
                    if (!isDownloaded(filesDir, pack)) {
                        if (pack == PaddleOcrModel.PACK) downloadPaddle(filesDir) else download(filesDir, pack)
                    }
                    if (!module.awaitInstalled()) throw FailedException(OfflineBuild.library(R.string.core_intel_ocr_module_error))
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

    /** The pack a `<pack>.traineddata` file named [name] is, or null for any other file. */
    fun packOf(name: String): String? =
        name.removeSuffix(".traineddata").takeIf { name.endsWith(".traineddata") && it in OcrLanguages.allPacks }

    /**
     * Installs [pack] from a copy the user fetched some other way (the same
     * `tessdata_fast` file the download fetches). Nothing can check it before
     * Tesseract opens it; a file Tesseract will not load is caught there and
     * [markBroken], as a bad download would be. Runs on the caller's thread.
     */
    fun install(filesDir: File, pack: String, source: File) {
        cancel(pack)
        val part = partFile(filesDir, pack)
        part.parentFile?.mkdirs()
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val target = file(filesDir, pack)
            target.delete()
            check(part.renameTo(target)) { "could not move the pack into place" }
            set(pack, Status.Downloaded)
        } finally {
            part.delete()
        }
    }

    /**
     * Installs one of Paddle's three files from a copy the user fetched by
     * hand. False when it is not the published file, which is then left out.
     * The pack counts as downloaded once all three are in. Runs on the
     * caller's thread.
     */
    fun installPaddle(filesDir: File, part: PaddleOcrModel.Part, source: File): Boolean {
        if (FileDownloader.sha256(source) != part.sha256) return false
        cancel(PaddleOcrModel.PACK)
        val staged = paddlePartFile(filesDir, part)
        staged.parentFile?.mkdirs()
        try {
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            val target = PaddleOcrModel.file(filesDir, part)
            target.delete()
            check(staged.renameTo(target)) { "could not move the file into place" }
        } finally {
            staged.delete()
        }
        set(
            PaddleOcrModel.PACK,
            if (PaddleOcrModel.isDownloaded(filesDir)) Status.Downloaded else Status.NotDownloaded,
        )
        return true
    }

    fun cancel(pack: String) {
        synchronized(jobs) { jobs[pack]?.cancel() }
    }

    fun delete(filesDir: File, pack: String) {
        cancel(pack)
        deleteFiles(filesDir, pack)
        set(pack, Status.NotDownloaded)
    }

    private fun deleteFiles(filesDir: File, pack: String) {
        if (pack == PaddleOcrModel.PACK) {
            // Both runtimes' files: a backup restored from another build may hold the other pair.
            PaddleOcrModel.Part.entries.forEach {
                PaddleOcrModel.file(filesDir, it).delete()
                FileDownloader.discard(paddlePartFile(filesDir, it))
            }
        } else {
            file(filesDir, pack).delete()
            FileDownloader.discard(partFile(filesDir, pack))
        }
    }

    /**
     * For a pack that is on disk but that Tesseract would not load: deletes
     * it and reports it failed, so the scanner and the settings page offer
     * the download again instead of reading nothing with it.
     */
    fun markBroken(filesDir: File, pack: String) {
        deleteFiles(filesDir, pack)
        set(pack, Status.Failed(R.string.core_intel_ocr_pack_error_broken))
    }

    private fun set(pack: String, status: Status) {
        _states.update { it + (pack to status) }
    }

    private fun partFile(filesDir: File, pack: String): File =
        File(dataDir(filesDir), "$pack.traineddata.part")

    private fun paddlePartFile(filesDir: File, part: PaddleOcrModel.Part): File =
        File(PaddleOcrModel.dir(filesDir), "${part.fileName}.part")

    /** A failure that already knows its message; see [Status.Failed]. */
    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") :
        IOException()

    private fun checkSpace(dir: File, expected: Long) {
        if (StatFs(dir.path).availableBytes < expected + SPACE_MARGIN_BYTES) {
            val neededMb = ((expected + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, neededMb.toString())
        }
    }

    private suspend fun download(filesDir: File, pack: String) {
        val dir = dataDir(filesDir).apply { mkdirs() }
        val expected = OcrLanguages.sizeOf(pack)
        checkSpace(dir, expected)
        val part = partFile(filesDir, pack)
        // Tesseract's files carry no pin here: the server's length is the
        // file's, and a pack Tesseract cannot load is caught by markBroken.
        fetch(OcrLanguages.downloadUrl(pack), part, null) { written ->
            set(pack, Status.Downloading(written, expected))
        }
        check(part.renameTo(file(filesDir, pack))) { "could not move the finished download into place" }
    }

    /**
     * Paddle's three files, one after another, each checked against its
     * digest before it is moved into place. A file already there from an
     * earlier, interrupted run is kept.
     */
    private suspend fun downloadPaddle(filesDir: File) {
        val dir = PaddleOcrModel.dir(filesDir).apply { mkdirs() }
        val missing = PaddleOcrModel.parts().filter { !PaddleOcrModel.file(filesDir, it).isFile }
        checkSpace(dir, missing.sumOf { it.bytes })
        val total = PaddleOcrModel.totalBytes
        var done = total - missing.sumOf { it.bytes }
        for (part in missing) {
            val url = PaddleOcrModel.url(part) ?: throw FailedException(R.string.core_intel_semantic_no_source)
            try {
                FileDownloader.fetchVerified(
                    url = url,
                    target = PaddleOcrModel.file(filesDir, part),
                    size = part.bytes,
                    sha256 = part.sha256,
                    source = NetSource.DOWNLOAD_OCR,
                    part = paddlePartFile(filesDir, part),
                    what = part.fileName,
                ) { written -> set(PaddleOcrModel.PACK, Status.Downloading(done + written, total)) }
            } catch (e: DownloadException) {
                val (res, arg) = e.screenText()
                throw FailedException(res, arg)
            }
            done += part.bytes
        }
    }

    /** Fetches [url] into [target], [size] bytes long or as long as the server says. */
    private suspend fun fetch(url: String, target: File, size: Long?, onProgress: (Long) -> Unit) {
        try {
            FileDownloader.fetch(url, target, size, NetSource.DOWNLOAD_OCR, onBytes = onProgress)
        } catch (e: DownloadException) {
            val (res, arg) = e.screenText()
            throw FailedException(res, arg)
        }
    }
}
