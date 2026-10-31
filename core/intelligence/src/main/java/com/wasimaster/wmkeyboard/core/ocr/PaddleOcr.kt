package com.wasimaster.wmkeyboard.core.ocr

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Reads Chinese and Japanese with PaddleOCR's PP-OCRv5 models (#757), and in
 * F-Droid's build Latin script as well. The graphs run on the LiteRT
 * interpreter Whisper brings, or, in a build without LiteRT, on the MNN
 * library the sticker generator brings ([PaddleOcrModel.runtime]). The
 * models are [PaddleOcrModel.PACK] in [OcrPacks]; the graphs run behind
 * [PaddleRuntime] and everything around them is [PaddleOcrPipeline].
 *
 * One runtime is kept loaded between reads, since loading the graphs takes
 * longer than a read, on one thread of its own: an interpreter is not safe to
 * share. Fails as [TesseractOcr] does, with a [TesseractException], so the
 * scanner treats a missing or broken pack the same whichever engine had it.
 */
object PaddleOcr {

    /**
     * The gate of this build's runtime: LiteRT's (a Play module, or always
     * installed), or the sticker generator's, whose library carries MNN (a
     * download behind F-Droid's opt-in).
     */
    val module: ModuleGate
        get() = if (PaddleOcrModel.runtime == PaddleOcrModel.Runtime.MNN) FeatureModules.imagegen else FeatureModules.litert

    /** Where [module] stands. */
    val moduleState: StateFlow<ModuleState> get() = module.state

    private const val LETTER_FLOOR = 0.2f

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "paddle-ocr") }
    private val dispatcher = thread.asCoroutineDispatcher()

    // Touched only on [thread].
    private var runtime: PaddleRuntime? = null
    private var charset: List<String> = emptyList()

    /**
     * The text in [bitmap] as lines, regions on one row separated by spaces,
     * or "" when the photo holds none. Throws [TesseractException] when the
     * read cannot happen or fails.
     */
    suspend fun recognize(context: Context, bitmap: Bitmap): String {
        val filesDir = context.filesDir
        if (!module.installed) {
            throw TesseractException(TesseractException.Reason.UNAVAILABLE, "the ${PaddleOcrModel.runtime} runtime is not installed")
        }
        if (!PaddleOcrModel.isDownloaded(filesDir)) {
            throw TesseractException(TesseractException.Reason.PACK_MISSING, "${PaddleOcrModel.PACK} is not downloaded")
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        // getPixels cannot read a hardware bitmap.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        readable.getPixels(pixels, 0, width, 0, 0, width, height)
        if (readable !== bitmap) readable.recycle()
        return withContext(dispatcher) {
            val loaded = runtime ?: load(context)
            val job = currentCoroutineContext()[Job]
            val lines = PaddleOcrPipeline.read(loaded, pixels, width, height, charset) { job?.isActive != false }
            currentCoroutineContext().ensureActive()
            lines.joinToString("\n")
        }
    }

    /** Whether a read would run now, without downloading anything first. */
    fun ready(context: Context): Boolean = module.installed && PaddleOcrModel.isDownloaded(context.filesDir)

    /**
     * The single characters in [boxes] of [bitmap], or an empty list if this engine is
     * not [ready]. For the theme copier, which knows where label-sized things are and
     * needs to know which letters they are.
     */
    suspend fun readLetters(context: Context, bitmap: Bitmap, boxes: List<PaddleOcrPipeline.TextBox>): List<Pair<String, PaddleOcrPipeline.TextBox>> {
        if (!ready(context)) return emptyList()
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        readable.getPixels(pixels, 0, width, 0, 0, width, height)
        if (readable !== bitmap) readable.recycle()
        return withContext(dispatcher) {
            PaddleOcrPipeline.readAt(runtime ?: load(context), pixels, width, height, charset, boxes, LETTER_FLOOR)
        }
    }

    /** On [thread]: the runtime, with both graphs and the character list loaded. */
    private fun load(context: Context): PaddleRuntime {
        val filesDir = context.filesDir
        val bridge = if (PaddleOcrModel.runtime == PaddleOcrModel.Runtime.MNN) mnn() else litert(context)
        val keys = runCatching {
            bridge.open(
                PaddleOcrModel.file(filesDir, PaddleOcrModel.detector()),
                PaddleOcrModel.file(filesDir, PaddleOcrModel.recognizer()),
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            )
            PaddleOcrPipeline.charset(PaddleOcrModel.file(filesDir, PaddleOcrModel.Part.CHARACTERS).readText())
        }.getOrNull()
        // The graph's classes are the list plus blank and space; anything
        // else is not the pair of files this reader was written for.
        if (keys == null || keys.size != bridge.recognizerClasses) {
            runCatching { bridge.close() }
            OcrPacks.markBroken(filesDir, PaddleOcrModel.PACK)
            throw TesseractException(TesseractException.Reason.PACK_BROKEN, "the PaddleOCR models would not load")
        }
        runtime = bridge
        charset = keys
        return bridge
    }

    /** MNN's, once its library is loaded: a missing library is not a broken pack. */
    private fun mnn(): PaddleRuntime {
        if (!ImageGenNative.available) {
            throw TesseractException(TesseractException.Reason.UNAVAILABLE, "lib${ImageGenNative.LIBRARY}.so would not load")
        }
        return MnnPaddleRuntime()
    }

    /** LiteRT's bridge, by reflection: on Play it arrives in `:feature:litert`. */
    private fun litert(context: Context): PaddleRuntime {
        // LiteRT loads its library by name; a delivered one goes in first.
        if (!NativeDelivery.preload("tensorflowlite_jni")) {
            throw TesseractException(TesseractException.Reason.UNAVAILABLE, "libtensorflowlite_jni.so would not load")
        }
        return runCatching {
            FeatureModules.load<PaddleRuntime>(PaddleModule.BRIDGE_CLASS, context.classLoader)
        }.getOrElse {
            throw TesseractException(TesseractException.Reason.UNAVAILABLE, "the LiteRT bridge would not load: $it")
        }
    }

    /** Frees the loaded graphs, for when the panel closes. */
    fun release() {
        thread.execute {
            runCatching { runtime?.close() }
            runtime = null
            charset = emptyList()
        }
    }
}
