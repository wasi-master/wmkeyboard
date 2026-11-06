package com.wasimaster.wmkeyboard.core.voice.futoswipe

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.IOException
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
 * FUTO Swipe's neural glide models (encoder `honorable_sturgeon` and English
 * QWERTY decoder `magic_macaw`, fp32 ExecuTorch programs, 3.9 MB together),
 * downloaded from Hugging Face at a pinned revision, each file checked
 * against its SHA-256, and run on the app's ExecuTorch runtime.
 *
 * The weights are under the FUTO Model Weights License 1.0
 * ([NativeConsent.FUTO]): redistribution allowed, provided the product shows
 * its users that it is powered by "FUTO Swipe". The settings row that turns
 * the engine on and the About screen say so. Nothing is fetched until the
 * user has agreed to that licence.
 *
 * The search over the models' output, `CtcGlideBeam` in :core:prediction, is
 * this project's own; FUTO's GPL decoder library is not used. This module
 * cannot see that one, so the model speaks plain arrays and the keyboard
 * service adapts it to `SwipeModel`.
 */
object FutoSwipe {

    private const val REPO = "futo-org/futo-swipe"
    private const val REVISION = "18328c3042b066952c0936b3771d492fe2ec289a"

    /** The licence the weights come under, as published beside them. */
    const val LICENCE_URL = "https://huggingface.co/$REPO/blob/$REVISION/LICENSE.md"

    /** One file of the model repository, at [REVISION]. */
    class ModelFile(val path: String, val bytes: Long, val sha256: String) {
        val name: String get() = path.replace('/', '_')
        val url: String get() = "https://huggingface.co/$REPO/resolve/$REVISION/$path"
    }

    private val ENCODER = ModelFile(
        "honorable_sturgeon/model_fp32.pte", 2_649_856L,
        "725242bab5d14345e96ff214e8de2bfbc1f962c232d320df9c24cb82ffd1fbaf",
    )
    private val DECODER = ModelFile(
        "magic_macaw/model_fp32.pte", 1_247_468L,
        "01eaf16ac4bc0f1ed0698c240807f0e95e6d427bcf6de04983ffc50736744d85",
    )
    val files: List<ModelFile> = listOf(DECODER, ENCODER)
    val bytes: Long = files.sumOf { it.bytes }

    /** Everything this engine keeps, for Storage. */
    fun rootDir(filesDir: File): File = File(filesDir, "futo-swipe")

    /** Under [rootDir], by revision, so a newer pin never reads an older file. */
    fun dir(filesDir: File): File = File(rootDir(filesDir), REVISION.take(12))

    fun isDownloaded(filesDir: File): Boolean =
        files.all { File(dir(filesDir), it.name).let { f -> f.isFile && f.length() == it.bytes } }

    // ---- the model ----

    /**
     * Where the encoder expects the 26 letters a..z, in its unit-square QWERTY
     * frame. Must equal `SwipeFrame.KEY_X`/`KEY_Y` in :core:prediction, which
     * maps strokes into the same frame; a test holds the two together.
     */
    val KEY_X = floatArrayOf(
        0.10f, 0.60f, 0.40f, 0.30f, 0.25f, 0.40f, 0.50f, 0.60f, 0.75f, 0.70f, 0.80f, 0.90f, 0.80f,
        0.70f, 0.85f, 0.95f, 0.05f, 0.35f, 0.20f, 0.45f, 0.65f, 0.50f, 0.15f, 0.30f, 0.55f, 0.20f,
    )
    val KEY_Y = floatArrayOf(
        0.500f, 0.833f, 0.833f, 0.500f, 0.167f, 0.500f, 0.500f, 0.500f, 0.167f, 0.500f, 0.500f, 0.500f, 0.833f,
        0.833f, 0.167f, 0.167f, 0.167f, 0.167f, 0.500f, 0.167f, 0.167f, 0.833f, 0.167f, 0.833f, 0.167f, 0.833f,
    )

    /** Points per stroke the encoder reads, x row then y row. */
    const val POINTS = 64

    /** Classes per step in [FutoSwipeModel.emissions]: a..z, then the blank. */
    const val CLASSES = 27

    @Volatile private var loaded: FutoSwipeModel? = null

    /**
     * The model, when the files are here, the licence is agreed to and the
     * ExecuTorch runtime is loadable; null otherwise, and the caller glides
     * with the built-in decoder. Opens the programs once and keeps them.
     */
    fun model(filesDir: File): FutoSwipeModel? {
        loaded?.let { return it }
        if (!NativeConsent.has(NativeConsent.FUTO) || !isDownloaded(filesDir) || !EtNative.available) return null
        synchronized(this) {
            loaded?.let { return it }
            val dir = dir(filesDir)
            val encoder = runCatching { EtNative.open(File(dir, ENCODER.name)) }.getOrNull() ?: return null
            val decoder = runCatching { EtNative.open(File(dir, DECODER.name)) }.getOrNull()
            if (decoder == null) {
                encoder.close()
                return null
            }
            return FutoSwipeModel(encoder, decoder).also { loaded = it }
        }
    }

    /** Closes the programs; the next [model] opens them again. */
    /** Whether the decoder is loaded; for the Resources screen (#1163). Any thread. */
    val isLoaded: Boolean get() = loaded != null

    fun release() {
        synchronized(this) {
            loaded?.close()
            loaded = null
        }
    }

    // ---- downloads ----

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageArg] is the one argument [messageRes] takes, or "" for none. */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun refresh(filesDir: File) {
        if (job?.isActive == true) return
        _status.value = if (isDownloaded(filesDir)) Status.Downloaded else Status.NotDownloaded
    }

    /** Fetches whatever is missing, once the licence has been agreed to. */
    fun start(filesDir: File) {
        if (job?.isActive == true || !NativeConsent.has(NativeConsent.FUTO)) return
        if (isDownloaded(filesDir)) {
            _status.value = Status.Downloaded
            return
        }
        _status.value = Status.Downloading(0L, bytes)
        job = scope.launch {
            _status.value = try {
                download(filesDir)
                Status.Downloaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Failed) {
                Status.Failed(e.messageRes, e.messageArg)
            } catch (_: Exception) {
                Status.Failed(OfflineBuild.download(CommonR.string.common_error_network))
            }
        }
    }

    /**
     * Whether a file of [size] bytes may be one of [files], for an import
     * (#1002). Both are served as `model_fp32.pte`, so the name says nothing;
     * [install] checks the digest.
     */
    fun sizeMatches(size: Long): Boolean = files.any { it.bytes == size }

    /**
     * Puts [source], fetched some other way (#1002), where its download would
     * put it: the file whose size and SHA-256 it has. False when it is
     * neither. The licence is still agreed to on the glide screen before the
     * model runs. Blocking.
     */
    fun install(filesDir: File, source: File): Boolean {
        val size = source.length()
        val candidates = files.filter { it.bytes == size }
        if (candidates.isEmpty()) return false
        val digest = FileDownloader.sha256(source)
        val file = candidates.firstOrNull { it.sha256.equals(digest, ignoreCase = true) } ?: return false
        val dir = dir(filesDir).apply { mkdirs() }
        val target = File(dir, file.name)
        val staged = File(dir, file.name + ".import")
        try {
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            target.delete()
            if (!staged.renameTo(target)) throw IOException("could not move $target into place")
        } finally {
            staged.delete()
        }
        release()
        refresh(filesDir)
        return true
    }

    fun delete(filesDir: File) {
        job?.cancel()
        release()
        rootDir(filesDir).deleteRecursively()
        _status.value = Status.NotDownloaded
    }

    private class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private suspend fun download(filesDir: File) {
        val dir = dir(filesDir).apply { mkdirs() }
        // An older revision's folder is dead weight once this one is complete.
        dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
        var done = 0L
        for (f in files) {
            val target = File(dir, f.name)
            if (!(target.isFile && target.length() == f.bytes)) fetch(f, target, done)
            done += f.bytes
        }
    }

    private suspend fun fetch(f: ModelFile, target: File, base: Long) {
        try {
            FileDownloader.fetchVerified(f.url, target, f.bytes, f.sha256, NetSource.DOWNLOAD_GLIDE, what = f.path) { written ->
                _status.value = Status.Downloading(base + written, bytes)
            }
        } catch (e: DownloadException) {
            throw when (e) {
                is DownloadException.Http -> Failed(R.string.core_voice_download_http_error, e.status.toString())
                is DownloadException.Interrupted -> Failed(R.string.core_voice_download_interrupted_error)
                is DownloadException.Blocked -> Failed(CommonR.string.common_error_download_held)
                is DownloadException.WrongSize, is DownloadException.Mismatch -> Failed(R.string.core_voice_whistle_checksum_error)
            }
        }
    }
}

/**
 * The encoder and the English QWERTY decoder, run back to back: the encoder
 * reads the stroke against the 26 key centres of [FutoSwipe.KEY_X] and emits,
 * for each of 32 steps, a distribution over 64 key slots and the blank plus
 * the features the decoder refines; the decoder turns those into 27-class
 * log-probabilities.
 *
 * One stroke at a time: ExecuTorch programs are not re-entrant, and a glide
 * decode is never concurrent with another anyway.
 */
class FutoSwipeModel internal constructor(
    private val encoder: EtNative.Program,
    private val decoder: EtNative.Program,
) : AutoCloseable {

    private val keys = FloatArray(MAX_KEYS * 2).also { k ->
        for (i in 0 until LETTERS) {
            k[2 * i] = FutoSwipe.KEY_X[i]
            k[2 * i + 1] = FutoSwipe.KEY_Y[i]
        }
    }
    private val mask = ByteArray(MAX_KEYS) { if (it < LETTERS) 1 else 0 }

    /**
     * Log-probabilities over a..z and the blank, [FutoSwipe.CLASSES] per step,
     * for [features]: the stroke in the unit square, [FutoSwipe.POINTS] x
     * values then as many y values. Null when the runtime refused it.
     */
    @Synchronized
    fun emissions(features: FloatArray): FloatArray? = runCatching {
        require(features.size == 2 * FutoSwipe.POINTS)
        val out = encoder.run(
            "forward",
            listOf(
                Tensor(longArrayOf(1, 2, FutoSwipe.POINTS.toLong()), features),
                Tensor(longArrayOf(1, MAX_KEYS.toLong(), 2), keys),
                Tensor(longArrayOf(1, MAX_KEYS.toLong()), mask),
            ),
        )
        val logits = out[0].floats
        val coefficients = out[1].floats
        val gate = out[2].floats
        val steps = out[0].shape[1].toInt()
        val width = out[0].shape[2].toInt()
        val coeffDim = out[1].shape[2].toInt()
        val inputDim = FutoSwipe.CLASSES + coeffDim + 1
        // The decoder's input per step: the letters' and blank's emissions,
        // the spectral coefficients, then the intention gate.
        val input = FloatArray(steps * inputDim)
        for (t in 0 until steps) {
            val at = t * inputDim
            System.arraycopy(logits, t * width, input, at, LETTERS)
            input[at + LETTERS] = logits[t * width + width - 1]
            System.arraycopy(coefficients, t * coeffDim, input, at + FutoSwipe.CLASSES, coeffDim)
            input[at + inputDim - 1] = gate[t]
        }
        val decoded = decoder.run(
            "forward",
            listOf(Tensor(longArrayOf(1, steps.toLong(), inputDim.toLong()), input)),
        )[0]
        decoded.floats.copyOf(steps * FutoSwipe.CLASSES)
    }.getOrNull()

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        /** The encoder's key slots, a fixed dimension of its export. */
        const val MAX_KEYS = 64
        const val LETTERS = 26
    }
}
