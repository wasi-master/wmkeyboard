package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import android.os.StatFs
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.PinnedDownload
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
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
 * The Strong level of Reduce background noise ([VoiceDenoise.STRONG]):
 * Ceva's DPDFNet (DeepFilterNet2 with dual-path RNN blocks, Apache-2.0), the
 * 16 kHz baseline model, on sherpa-onnx's streaming denoiser in
 * `libwmsherpa.so` over ONNX Runtime, so it rides the `onnx` gate with
 * Offline Sherpa. The model, about 9 MB, downloads on request from
 * sherpa-onnx's release of it, pinned by size and SHA-256.
 *
 * Measured on noisy LibriSpeech (street, café, fan at 0 to 10 dB), RNNoise
 * cost Whisper words while this, with 12 dB of the microphone kept under it,
 * won them back: see `native/sherpa-onnx-jni/README.md`. It takes about
 * 1.2 ms of one core per 10 ms of audio on a Mac, five times RNNoise, and
 * adds 40 ms of delay.
 */
object StrongDenoise {

    /** sherpa-onnx's conversion of Ceva-IP/DPDFNet `onnx/baseline.onnx`, with its streaming metadata. */
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/dpdfnet_baseline.onnx"
    const val BYTES = 8_791_035L
    private const val SHA256 = "debaf0e8893479fca91b8b4c1eae8db195aa8980ccc9012f5809fde2b738151a"

    /** Output trails input by this many samples: STFT overlap plus the model's lookahead. */
    private const val DELAY = 640

    /**
     * How much of the microphone is mixed back under the cleaned audio: 12 dB
     * down (10^(-12/20)). Fully cleaned audio lost Whisper about as many words
     * as it saved; with this much kept it saved a tenth of them.
     */
    private const val KEEP = 0.2512f

    private const val DIR = "denoise"
    private const val FILE = "dpdfnet_baseline.onnx"
    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

    fun rootDir(filesDir: File): File = File(filesDir, DIR)

    fun modelFile(filesDir: File): File = File(rootDir(filesDir), FILE)

    fun isDownloaded(filesDir: File): Boolean = modelFile(filesDir).length() == BYTES

    fun bytesOnDisk(filesDir: File): Long =
        FileDownloader.bytesUnder(rootDir(filesDir))

    /** Whether this build can run it at all, counting a runtime not fetched yet. */
    val runtimeShipped: Boolean get() = SherpaNative.shipped

    /** A `libwmsherpa.so` from before the denoiser was built into it. */
    @Volatile private var bridgeMissing = false

    /**
     * Whether a clip started now would get Strong rather than fall back to
     * Light: the model on disk and the runtime's module here. Loads nothing,
     * so it is cheap on the main thread; the libraries load on [loader].
     */
    fun ready(filesDir: File): Boolean =
        !bridgeMissing && isDownloaded(filesDir) && SherpaNative.shipped && SherpaNative.moduleReady

    // ---- Running ----------------------------------------------------------

    /**
     * Loads the model off the capture thread, which must never stall: an
     * ONNX Runtime session takes a moment to open, and the microphone's
     * buffer would overrun meanwhile. One thread, so a load and a [release]
     * never cross.
     */
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "denoise").apply { isDaemon = true } }

    /** A loaded denoiser no clip is using, kept so the next clip starts at once; 0 when none. */
    private val idle = AtomicLong(0L)

    /**
     * A denoiser for one clip, or null when the model or the runtime is not
     * here (the caller falls back to Light). The model may still be loading
     * when the first audio comes; that audio waits inside the stream and goes
     * through as soon as it is ready.
     */
    fun newStream(filesDir: File): VoiceDenoise.Stream? {
        if (!ready(filesDir)) return null
        val path = modelFile(filesDir).path
        val handle = loader.submit(Callable { idle.getAndSet(0L).takeIf { it != 0L } ?: open(path) })
        return Stream(handle)
    }

    private fun open(path: String): Long = try {
        if (SherpaNative.available) SherpaNative.nativeDenoiser(path, 1, DELAY, KEEP) else 0L
    } catch (_: UnsatisfiedLinkError) {
        bridgeMissing = true
        0L
    }

    /** A finished clip's denoiser, reset, back for the next clip; freed if one is already waiting. */
    private fun giveBack(handle: Long) {
        if (!idle.compareAndSet(0L, handle)) SherpaNative.nativeDenoiserFree(handle)
    }

    /** Frees the waiting denoiser, its model and runtime session; the next clip loads it again. */
    fun release() {
        loader.execute {
            val h = idle.getAndSet(0L)
            if (h != 0L) SherpaNative.nativeDenoiserFree(h)
        }
    }

    private class Stream(private val pending: Future<Long>) : VoiceDenoise.Stream {
        private var resolved = false
        private var handle = 0L

        /** RNNoise, when the model would not load after all. */
        private var fallback: VoiceDenoise.Stream? = null

        /** Audio that came while the model was still loading. */
        private var backlog = FloatArray(0)
        private var backlogSize = 0
        private var finished = false
        private var closed = false

        override fun process(chunk: FloatArray, count: Int): FloatArray {
            if (closed || finished || count <= 0) return EMPTY
            if (!resolved && !pending.isDone) {
                hold(chunk, count)
                return EMPTY
            }
            resolve()
            if (backlogSize == 0) return run(chunk, count)
            hold(chunk, count)
            return drainBacklog()
        }

        override fun finish(): FloatArray {
            if (closed || finished) return EMPTY
            finished = true
            resolve()
            val head = drainBacklog()
            val tail = when {
                handle != 0L -> SherpaNative.nativeDenoiseFinish(handle)
                else -> fallback?.finish() ?: EMPTY
            }
            return if (head.isEmpty()) tail else head + tail
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!resolved) {
                // Still loading: hand the denoiser back once it is. The loader
                // runs one task at a time, so the load is over by then.
                loader.execute { runCatching { pending.get() }.getOrDefault(0L).takeIf { it != 0L }?.let(::recycle) }
                return
            }
            fallback?.close()
            val h = handle
            handle = 0L
            if (h == 0L) return
            if (finished) giveBack(h) else recycle(h)
        }

        /** Takes the loaded denoiser, or RNNoise when it would not load; waits for the load if need be. */
        private fun resolve() {
            if (resolved) return
            resolved = true
            handle = runCatching { pending.get() }.getOrDefault(0L)
            if (handle == 0L) fallback = VoiceDenoise.light()
        }

        private fun run(samples: FloatArray, count: Int): FloatArray = when {
            handle != 0L -> SherpaNative.nativeDenoise(handle, samples, count)
            fallback != null -> fallback!!.process(samples, count)
            // Neither would load: the clip goes through as recorded.
            else -> samples.copyOf(count)
        }

        private fun hold(chunk: FloatArray, count: Int) {
            if (backlogSize + count > backlog.size) {
                backlog = backlog.copyOf(maxOf(backlog.size * 2, backlogSize + count, BACKLOG_START))
            }
            chunk.copyInto(backlog, backlogSize, 0, count)
            backlogSize += count
        }

        private fun drainBacklog(): FloatArray {
            if (backlogSize == 0) return EMPTY
            val n = backlogSize
            backlogSize = 0
            return run(backlog, n)
        }
    }

    /** A clip abandoned part-way: its leftovers dropped, the denoiser reset and kept. */
    private fun recycle(handle: Long) {
        runCatching { SherpaNative.nativeDenoiseFinish(handle) }
        giveBack(handle)
    }

    private val EMPTY = FloatArray(0)

    /** Half a second; grows if the model is slower than that to load. */
    private const val BACKLOG_START = 8000

    // ---- Downloading ------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

    /** Where the model download stands, for the settings row. */
    val status: StateFlow<Status> = _status.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [status] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        if (isBusy) return
        val onDisk = bytesOnDisk(filesDir)
        _status.value = when {
            isDownloaded(filesDir) -> Status.Downloaded
            onDisk > 0 -> Status.Paused(onDisk, BYTES)
            else -> Status.NotDownloaded
        }
    }

    fun download(filesDir: File) {
        if (isBusy || isDownloaded(filesDir)) return
        _status.value = Status.Downloading(bytesOnDisk(filesDir), BYTES)
        job = scope.launch {
            try {
                val dir = rootDir(filesDir).apply { mkdirs() }
                val needed = BYTES - bytesOnDisk(filesDir) + SPACE_MARGIN_BYTES
                if (StatFs(dir.path).availableBytes < needed) {
                    throw PinnedDownload.FailedException(
                        R.string.core_voice_download_space_error,
                        (needed / 1e6).roundToInt().toString(),
                    )
                }
                PinnedDownload.fetch(URL, modelFile(filesDir), BYTES, SHA256, "$DIR/$FILE", NetSource.DOWNLOAD_WHISPER) {
                    _status.value = Status.Downloading(it, BYTES)
                }
                _status.value = Status.Downloaded
            } catch (e: CancellationException) {
                val onDisk = bytesOnDisk(filesDir)
                _status.value = if (onDisk > 0) Status.Paused(onDisk, BYTES) else Status.NotDownloaded
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

    /** Deletes the model; a clip already running keeps its loaded copy. */
    fun delete(filesDir: File) {
        cancel()
        release()
        rootDir(filesDir).deleteRecursively()
        _status.value = Status.NotDownloaded
    }
}
