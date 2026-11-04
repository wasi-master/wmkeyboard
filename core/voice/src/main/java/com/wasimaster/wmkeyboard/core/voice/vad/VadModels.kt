package com.wasimaster.wmkeyboard.core.voice.vad

import android.os.StatFs
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.PinnedDownload
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.InputStream
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
 * The phrase-end detectors' own files, under `files/vad/`: a Silero for the
 * engines whose models carry none (Asr4All), FireRedVAD, Smart Turn, and a
 * model the user imported. Each download is pinned by size and SHA-256, and
 * fetched only from the settings screen.
 */
object VadModels {

    private const val DIR = "vad"

    fun rootDir(filesDir: File): File = File(filesDir, DIR)

    fun bytesOnDisk(filesDir: File): Long = FileDownloader.bytesUnder(rootDir(filesDir))

    /** One file of a download: where it comes from and what it must be. */
    class Pinned(val url: String, val name: String, val bytes: Long, val sha256: String)

    /** Silero VAD v4 (MIT): the same file every Sherpa model carries, from the same pin. */
    val SILERO = EtCatalog.SILERO_VAD.let { f ->
        val from = checkNotNull(f.from)
        Download(
            "silero",
            listOf(Pinned("https://huggingface.co/${from.repo}/resolve/${from.revision}/${f.path}", f.name, f.bytes, f.sha256)),
        )
    }
    const val SILERO_FILE = "silero_vad.onnx"

    /**
     * FireRedVAD's streaming model and its CMVN statistics (Xiaohongshu's
     * FireRedTeam, Apache-2.0), upstream's own ONNX export from its GitHub
     * repository at a pinned commit.
     */
    private const val FIRERED_BASE =
        "https://raw.githubusercontent.com/FireRedTeam/FireRedVAD/c30ec49e8cc69642b0ee65362eba11b9d11c6e54/pretrained_models/onnx_models"
    const val FIRERED_FILE = "fireredvad_stream_vad_with_cache.onnx"
    const val FIRERED_CMVN = "cmvn.ark"
    val FIRERED = Download(
        "firered",
        listOf(
            // Small file first: a bad connection fails before the big one.
            Pinned("$FIRERED_BASE/$FIRERED_CMVN", FIRERED_CMVN, 1_311L, "c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473"),
            Pinned("$FIRERED_BASE/$FIRERED_FILE", FIRERED_FILE, 2_306_042L, "b3c97836130dc34fc32d56fab551e88cf9454511de2b3c250a5e6578dee74b93"),
        ),
    )

    /** Smart Turn v3.2, the int8 CPU build (pipecat-ai, BSD-2-Clause). */
    const val SMART_TURN_FILE = "smart-turn-v3.2-cpu.onnx"
    val SMART_TURN = Download(
        "smart_turn",
        listOf(
            Pinned(
                "https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/f766f81d3cfdf7737ac64aad813d91bbfd56bf93/$SMART_TURN_FILE",
                SMART_TURN_FILE,
                8_679_182L,
                "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f",
            ),
        ),
    )

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    /**
     * One detector's files, downloaded together into their own folder, with
     * the settings row's status.
     */
    class Download internal constructor(val id: String, private val files: List<Pinned>) {

        val bytes: Long = files.sumOf { it.bytes }

        fun dir(filesDir: File): File = File(rootDir(filesDir), id)

        fun file(filesDir: File, name: String): File = File(dir(filesDir), name)

        fun isDownloaded(filesDir: File): Boolean = files.all { file(filesDir, it.name).length() == it.bytes }

        private fun onDisk(filesDir: File): Long = FileDownloader.bytesUnder(dir(filesDir))

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

        /** Where the download stands, for the settings row. */
        val status: StateFlow<Status> = _status.asStateFlow()

        val isBusy: Boolean get() = job?.isActive == true

        /** Seeds [status] from disk; call when the screen appears. */
        fun refresh(filesDir: File) {
            if (isBusy) return
            val have = onDisk(filesDir)
            _status.value = when {
                isDownloaded(filesDir) -> Status.Downloaded
                have > 0 -> Status.Paused(have, bytes)
                else -> Status.NotDownloaded
            }
        }

        fun download(filesDir: File) {
            if (isBusy || isDownloaded(filesDir)) return
            _status.value = Status.Downloading(onDisk(filesDir), bytes)
            job = scope.launch {
                try {
                    val dir = dir(filesDir).apply { mkdirs() }
                    val needed = bytes - onDisk(filesDir) + SPACE_MARGIN_BYTES
                    if (StatFs(dir.path).availableBytes < needed) {
                        throw PinnedDownload.FailedException(
                            R.string.core_voice_download_space_error,
                            (needed / 1e6).roundToInt().toString(),
                        )
                    }
                    var done = 0L
                    for (f in files) {
                        val target = file(filesDir, f.name)
                        if (target.length() != f.bytes) {
                            PinnedDownload.fetch(f.url, target, f.bytes, f.sha256, "$DIR/$id/${f.name}", NetSource.DOWNLOAD_WHISPER) {
                                _status.value = Status.Downloading(done + it, bytes)
                            }
                        }
                        done += f.bytes
                    }
                    _status.value = Status.Downloaded
                } catch (e: CancellationException) {
                    val have = onDisk(filesDir)
                    _status.value = if (have > 0) Status.Paused(have, bytes) else Status.NotDownloaded
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

        /** Deletes the files; a dictation already running keeps what it loaded. */
        fun delete(filesDir: File) {
            cancel()
            if (this === SMART_TURN) SmartTurn.release()
            dir(filesDir).deleteRecursively()
            _status.value = Status.NotDownloaded
        }
    }

    // ---- A model of the user's own ----------------------------------------

    private const val CUSTOM_DIR = "custom"
    private const val CUSTOM_FILE = "model.onnx"

    /** Which family the imported model is, written beside it once it has passed the probe. */
    private const val CUSTOM_KIND = "kind"

    /** Larger than any VAD model there is; a file past it is not one. */
    private const val CUSTOM_MAX_BYTES = 64L * 1024 * 1024

    fun customFile(filesDir: File): File = File(File(rootDir(filesDir), CUSTOM_DIR), CUSTOM_FILE)

    /** The imported model's family, [SherpaNative.VAD_SILERO] or [SherpaNative.VAD_TEN]; null when there is none. */
    fun customKind(filesDir: File): Int? {
        if (!customFile(filesDir).isFile) return null
        return File(customFile(filesDir).parentFile, CUSTOM_KIND).takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull()
            ?.takeIf { it == SherpaNative.VAD_SILERO || it == SherpaNative.VAD_TEN }
    }

    /** How an import went. */
    enum class ImportResult { SILERO, TEN, NOT_A_VAD, TOO_LARGE, NO_RUNTIME, FAILED }

    /**
     * Copies a model the user picked into place, once ONNX Runtime has opened
     * it and found it to be a Silero or TEN model sherpa-onnx takes
     * ([SherpaNative.nativeVadProbe]); anything else is turned away before
     * sherpa-onnx, which would exit the process on it, ever sees it. Replaces
     * the one imported before. Blocking: call it off the main thread.
     */
    fun import(filesDir: File, input: InputStream): ImportResult {
        if (!SherpaNative.available) return ImportResult.NO_RUNTIME
        val dir = File(rootDir(filesDir), CUSTOM_DIR).apply { mkdirs() }
        val part = File(dir, "$CUSTOM_FILE.part")
        try {
            var size = 0L
            part.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > CUSTOM_MAX_BYTES) return ImportResult.TOO_LARGE
                    out.write(buffer, 0, read)
                }
            }
            val kind = try {
                SherpaNative.nativeVadProbe(part.path)
            } catch (_: UnsatisfiedLinkError) {
                // A bridge from before imports: nothing can vouch for the file.
                return ImportResult.NO_RUNTIME
            }
            if (kind != SherpaNative.VAD_SILERO && kind != SherpaNative.VAD_TEN) return ImportResult.NOT_A_VAD
            val target = customFile(filesDir)
            val kindFile = File(dir, CUSTOM_KIND)
            kindFile.delete()
            if (!part.renameTo(target)) return ImportResult.FAILED
            kindFile.writeText(kind.toString())
            return if (kind == SherpaNative.VAD_TEN) ImportResult.TEN else ImportResult.SILERO
        } catch (_: Exception) {
            return ImportResult.FAILED
        } finally {
            part.delete()
        }
    }

    fun deleteCustom(filesDir: File) {
        File(rootDir(filesDir), CUSTOM_DIR).deleteRecursively()
    }

    /** Deletes every download and the imported model. */
    fun deleteAll(filesDir: File) {
        listOf(SILERO, FIRERED, SMART_TURN).forEach { it.delete(filesDir) }
        rootDir(filesDir).deleteRecursively()
    }
}
