package com.wasimaster.wmkeyboard.core.ocr

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The JNI surface of `libwmtess.so`, built from `native/tesseract-jni` and
 * committed under this module's `src/tesseract/jniLibs`. Handles are native
 * pointers; see the C++ file for what each call guarantees. Play builds carry
 * the library in the on-demand `:feature:ocr` split instead, fetched with the
 * first language pack (see [OcrPacks.start]).
 */
internal object TesseractNative {

    /** Loaded on first read of [GatedLibrary.available], once the split (if any) is here. */
    val library = GatedLibrary({ FeatureModules.ocr }) {
        runCatching { FeatureModules.loadLibrary("wmtess") }.isSuccess
    }

    @JvmStatic external fun nativeCreate(dataDir: String, languages: String): Long

    /** [thresholding] is Tesseract's `thresholding_method`; see [OcrThresholding.Method]. */
    @JvmStatic external fun nativeRecognize(handle: Long, bitmap: Bitmap, thresholding: Int): String?

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeVersion(): String
}

/**
 * A Tesseract read that failed, as opposed to one that found no text: the
 * scanner shows these as errors, never as "no text found".
 */
class TesseractException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason {
        /** This build has no Tesseract library, or it would not load. */
        UNAVAILABLE,

        /** The pack is not on the device (deleted since the scanner looked). */
        PACK_MISSING,

        /**
         * The pack is on the device but Tesseract would not load it. It has
         * been deleted and reported failed ([OcrPacks.markBroken]).
         */
        PACK_BROKEN,

        /** The engine loaded but the read itself failed. */
        READ_FAILED,
    }
}

/**
 * Reads text with Tesseract. One engine is kept loaded for the last pack
 * used, since loading a pack takes longer than reading a photo; switching
 * packs closes it and loads the next.
 *
 * Every native call runs on one thread of its own: a Tesseract engine is not
 * safe to share, and this keeps create, read and destroy in order without a
 * lock. Cancelling the caller cancels the read inside Tesseract too.
 */
object TesseractOcr {

    /**
     * Whether this build has Tesseract, counting a Play split not fetched yet:
     * what decides that a language reads with it. Loads nothing on Play
     * before the split is here.
     */
    val available: Boolean get() = TesseractNative.library.shipped

    /** Where the library's Play module stands. Always installed outside Play. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.ocr.state

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "tesseract") }
    private val dispatcher = thread.asCoroutineDispatcher()

    // Touched only on [thread].
    private var handle = 0L
    private var loadedPack: String? = null

    /**
     * The text in [bitmap] as lines of space-separated words, or "" when the
     * photo holds none. Reads once per [OcrThresholding.passes] and keeps the
     * better read. Throws [TesseractException] when the read cannot happen
     * or fails, so a broken engine never looks like a photo without text.
     */
    suspend fun recognize(filesDir: File, pack: String, bitmap: Bitmap): String {
        if (!TesseractNative.library.available) {
            throw TesseractException(TesseractException.Reason.UNAVAILABLE, "libwmtess is not loaded")
        }
        if (!OcrPacks.isDownloaded(filesDir, pack)) {
            throw TesseractException(TesseractException.Reason.PACK_MISSING, "$pack is not downloaded")
        }
        // The native side reads ARGB_8888 pixels only; a hardware or F16
        // bitmap has to be copied out first.
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw TesseractException(TesseractException.Reason.READ_FAILED, "could not copy the photo to ARGB_8888")
        return withContext(dispatcher) {
            val engine = engineFor(filesDir, pack)
            if (engine == 0L) {
                OcrPacks.markBroken(filesDir, pack)
                throw TesseractException(TesseractException.Reason.PACK_BROKEN, "Tesseract would not load $pack")
            }
            OcrThresholding.bestRead { method -> readOnce(engine, argb, method) }
        }
    }

    /** One read on [thread]. */
    private suspend fun readOnce(engine: Long, bitmap: Bitmap, method: OcrThresholding.Method): String {
        // The native read clears the cancel flag as it starts, so a cancel
        // that landed between two passes has to be caught here.
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { TesseractNative.nativeCancel(engine) }
            val text = runCatching { TesseractNative.nativeRecognize(engine, bitmap, method.tesseractValue) }
            cont.resumeWith(
                text.mapCatching {
                    it ?: throw TesseractException(
                        TesseractException.Reason.READ_FAILED,
                        "Tesseract failed to read the photo (${method.name})",
                    )
                }
            )
        }
    }

    /** Frees the loaded engine, for when the panel closes. */
    /** The language pack loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedPack(): String? = loadedPack?.takeIf { handle != 0L }

    fun release() {
        thread.execute { close() }
    }

    private fun engineFor(filesDir: File, pack: String): Long {
        if (handle != 0L && loadedPack == pack) return handle
        close()
        handle = TesseractNative.nativeCreate(OcrPacks.dataDir(filesDir).path, pack)
        loadedPack = if (handle != 0L) pack else null
        return handle
    }

    private fun close() {
        if (handle != 0L) TesseractNative.nativeDestroy(handle)
        handle = 0L
        loadedPack = null
    }
}
