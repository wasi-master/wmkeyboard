package com.wasimaster.wmkeyboard.core.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wasimaster.wmkeyboard.core.imagegen.ImageGen
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenDownloads
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenEvent
import com.wasimaster.wmkeyboard.core.localllm.NpuRuntime
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one way the app enlarges a picture: fetches what is missing (the MNN
 * library where it is delivered on demand, then the kind's network), and runs
 * [UpscaleEngine] in the sticker generator's process through [ImageGen], so a
 * large picture's memory is never the keyboard's or the settings app's.
 *
 * Used by the sticker editor, the gallery's photo editor, and the keyboard's
 * send path for small stickers going to an app that wants a 512 square.
 */
object Upscaler {

    sealed interface Event {
        /** The runtime or the network is downloading: [bytes] of [total], total 0 while unknown. */
        data class Downloading(val bytes: Long, val total: Long) : Event

        /** [done] of [total] tiles drawn. */
        data class Working(val done: Int, val total: Int) : Event

        data class Done(val file: File) : Event

        data class Failed(val message: String) : Event
    }

    private val fetching = Mutex()

    /** Whether [kind] runs here without downloading anything first. */
    fun ready(context: Context, kind: UpscaleKind): Boolean =
        FeatureModules.imagegen.installed && UpscaleModels.isDownloaded(context.noBackupFilesDir, UpscaleModels.Model.of(kind))

    /** What a first run of [kind] downloads, in bytes, not counting the runtime library. */
    fun downloadBytes(context: Context, kind: UpscaleKind): Long {
        val noBackup = context.noBackupFilesDir
        val model = UpscaleModels.Model.of(kind)
        val cpu = if (UpscaleModels.isDownloaded(noBackup, model)) 0L else model.part.bytes
        val npu = npuToFetch(context, kind)?.part?.bytes ?: 0L
        return cpu + npu
    }

    /**
     * [kind]'s NPU build when it should be fetched now: there is one for this
     * phone's Snapdragon, Qualcomm's runtime is already here (the upscaler
     * never fetches that on its own), and the build is not on disk yet. The
     * CPU network is fetched as well, which is what runs if the NPU fails.
     */
    private fun npuToFetch(context: Context, kind: UpscaleKind): UpscaleModels.NpuBuild? {
        val soc = NpuSupport.qnnSoc ?: return null
        val build = UpscaleModels.npuBuild(kind, soc) ?: return null
        if (UpscaleModels.isDownloaded(context.noBackupFilesDir, build) || !NpuRuntime.installed(soc)) return null
        return build
    }

    /**
     * Enlarges [input] by [factor] into [output]: [Event.Downloading] while
     * anything is fetched, [Event.Working] per tile, then one [Event.Done] or
     * [Event.Failed]. A collection cancelled before it ends stops the run.
     */
    fun upscale(context: Context, input: File, output: File, kind: UpscaleKind, factor: Int): Flow<Event> = flow {
        try {
            ensure(context.applicationContext, kind) { bytes, total -> emit(Event.Downloading(bytes, total)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageGenDownloads.FailedException) {
            emit(Event.Failed(context.getString(e.messageRes, e.messageArg)))
            return@flow
        } catch (e: IOException) {
            emit(Event.Failed(e.message ?: e.javaClass.simpleName))
            return@flow
        }
        var finished = false
        ImageGen.upscale(context, UpscaleRequest(input.path, output.path, kind, factor)).collect { event ->
            when (event) {
                is ImageGenEvent.Progress -> emit(Event.Working(event.step, event.steps))
                is ImageGenEvent.Image -> {
                    finished = true
                    emit(Event.Done(event.file))
                }
                is ImageGenEvent.Failed -> {
                    finished = true
                    emit(Event.Failed(event.message))
                }
                else -> Unit
            }
        }
        if (!finished) emit(Event.Failed("stopped"))
    }

    /**
     * [bitmap] enlarged by [factor], or null when it failed; [onEvent] sees
     * every event on the way. The bitmap goes through a PNG in the cache,
     * which is how pictures cross into the upscaler's process.
     */
    suspend fun upscale(
        context: Context,
        bitmap: Bitmap,
        kind: UpscaleKind,
        factor: Int,
        onEvent: (Event) -> Unit,
    ): Bitmap? {
        val dir = workDir(context)
        val stamp = System.nanoTime()
        val input = File(dir, "in_$stamp.png")
        val output = File(dir, "out_$stamp.png")
        try {
            withContext(Dispatchers.IO) {
                input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            }
            var result: File? = null
            upscale(context, input, output, kind, factor).collect { event ->
                onEvent(event)
                if (event is Event.Done) result = event.file
            }
            val file = result ?: return null
            return withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                })
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    /** Where pictures wait for the upscaler and come back from it. */
    fun workDir(context: Context): File = File(context.cacheDir, WORK_DIR).apply { mkdirs() }

    /** Fetches the runtime library and [kind]'s network when either is missing, and its NPU build where it would run. */
    private suspend fun ensure(context: Context, kind: UpscaleKind, onBytes: suspend (Long, Long) -> Unit) {
        val model = UpscaleModels.Model.of(kind)
        val noBackup = context.noBackupFilesDir
        fetching.withLock {
            if (!FeatureModules.imagegen.installed) {
                var last = 0L to 0L
                val here = FeatureModules.imagegen.awaitInstalled { bytes, all -> last = bytes to all }
                onBytes(last.first, last.second)
                if (!here) throw IOException("the upscaler's runtime could not be installed")
            }
            val parts = buildList {
                if (!UpscaleModels.isDownloaded(noBackup, model)) add(model.part to UpscaleModels.file(noBackup, model))
                npuToFetch(context, kind)?.let { add(it.part to UpscaleModels.npuFile(noBackup, it)) }
            }
            if (parts.isEmpty()) return
            val total = parts.sumOf { it.first.bytes }
            var before = 0L
            onBytes(0L, total)
            for ((part, target) in parts) {
                target.parentFile?.mkdirs()
                var written = 0L
                withContext(Dispatchers.IO) {
                    ImageGenDownloads.fetchVerified(part, target, NetSource.DOWNLOAD_UPSCALE) { written = it }
                }
                before += written.coerceAtLeast(part.bytes)
                onBytes(before, total)
            }
        }
    }

    private const val WORK_DIR = "upscale"
    private const val PNG_QUALITY = 100
}
