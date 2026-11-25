package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SubjectCutout] with nothing of Google's on the device: the [CutoutModel]
 * network, fetched over plain HTTPS from the data repository and run on the
 * LiteRT interpreter the app carries for Whisper, so it adds a five
 * megabyte download and not one byte of APK.
 *
 * Nothing here imports LiteRT. The interpreter call sits behind
 * [CutoutRuntime], reached by reflection, because on Play the library ships
 * in the on-demand `:feature:litert` module the base APK is built without;
 * [ensureModel] fetches that module before the network, so to the editor the
 * two are one download with one bar.
 */
internal object LocalSubjectCutout {

    /** Two editors cannot be open at once, but two taps on one row can land. */
    private val downloading = Mutex()

    /**
     * The share of the bar the interpreter's module takes when it has to be
     * fetched first: it is about a third of the network's size.
     */
    private const val MODULE_SHARE = 0.25f

    @Volatile
    private var runtime: CutoutRuntime? = null

    /** The model is here and so is what runs it. Never downloads either. */
    fun modelReady(context: Context): Boolean =
        CutoutModel.isDownloaded(context.filesDir) && FeatureModules.litert.installed

    /**
     * Downloads the model, reporting progress from 0 to 1, and resumes a
     * download that was cut short. On Play the interpreter's module comes
     * first and takes the start of the bar. False when either could not be
     * had: no network, no room, or a file at the address that is not the model.
     */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        val filesDir = context.filesDir
        return downloading.withLock {
            val moduleShare = if (FeatureModules.litert.installed) 0f else MODULE_SHARE
            val moduleHere = FeatureModules.litert.awaitInstalled { bytes, total ->
                if (total > 0L) onProgress((bytes.toFloat() / total * moduleShare).coerceIn(0f, moduleShare))
            }
            if (!moduleHere) return@withLock false
            if (CutoutModel.isDownloaded(filesDir)) return@withLock true
            withContext(Dispatchers.IO) {
                runCancellable {
                    download(filesDir) { onProgress(moduleShare + it * (1f - moduleShare)) }
                }.getOrDefault(false)
            }
        }
    }

    /**
     * The interpreter side, or null while its module is not on this install
     * (Play only) or will not load yet. No restart is needed after the module
     * arrives: SplitCompat (installed at startup in Play builds) lets this
     * process load the split's classes and native libraries as soon as the
     * install completes.
     */
    private fun runtime(context: Context): CutoutRuntime? {
        runtime?.let { return it }
        if (!FeatureModules.litert.installed) return null
        // LiteRT loads its library by name; a delivered one goes in first.
        if (!NativeDelivery.preloadLitert()) return null
        return runCancellable {
            FeatureModules.load<CutoutRuntime>(CutoutModule.BRIDGE_CLASS, context.classLoader)
        }.onSuccess { runtime = it }.getOrNull()
    }

    private suspend fun download(filesDir: File, onProgress: (Float) -> Unit): Boolean {
        CutoutModel.dir(filesDir).mkdirs()
        val part = CutoutModel.partFile(filesDir)
        return try {
            FileDownloader.fetchVerified(
                url = CutoutModel.url,
                target = CutoutModel.file(filesDir),
                size = CutoutModel.SIZE_BYTES,
                sha256 = CutoutModel.SHA256,
                source = NetSource.DOWNLOAD_CUTOUT,
                part = part,
                what = CutoutModel.FILE_NAME,
            ) { written -> onProgress((written.toFloat() / CutoutModel.SIZE_BYTES).coerceIn(0f, 1f)) }
            true
        } catch (_: DownloadException) {
            // Short or held keeps the part for the next try; a wrong file was deleted.
            false
        }
    }

    /**
     * The subject of [image] as an alpha mask the size of [image], unjudged:
     * whether it is worth applying is [SubjectCutout]'s call.
     */
    suspend fun cutOut(context: Context, image: Bitmap): SubjectCutout.Result {
        val model = CutoutModel.file(context.filesDir)
        if (!CutoutModel.isDownloaded(context.filesDir)) return SubjectCutout.Result.ModelUnavailable
        val engine = runtime(context) ?: return SubjectCutout.Result.ModelUnavailable
        return withContext(Dispatchers.Default) {
            runCancellable { SubjectCutout.Result.Ok(engine.segment(model, image)) }
                .getOrDefault(SubjectCutout.Result.Failed)
        }
    }
}
