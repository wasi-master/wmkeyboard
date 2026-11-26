package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * One-tap background removal for the sticker editor.
 *
 * Two engines sit behind it and the editor never learns which one ran.
 * [GmsSubjectCutout] is ML Kit's subject segmentation, a Play services module.
 * [LocalSubjectCutout] is a small network the app downloads and runs itself,
 * for a device with no Play services, or with a stand-in for them that has no
 * such module (#278). Google's is the better model, so it goes first wherever
 * it can be had; the app's own is what makes the button work everywhere else.
 *
 * Neither model is in the APK, which is why this exposes [modelReady] and
 * [ensureModel] separately from [cutOut]: the editor asks first and offers the
 * download as a visible choice, so a user who never opens a
 * sticker pack pays nothing for either.
 *
 * Callers must have run `MlKitInit.ensure(context)` first — after a reboot
 * into the lock screen, ML Kit's own ContentProvider never ran, and every
 * ML Kit entry point throws for the life of the process without it.
 *
 * F-Droid's build has neither: no Play services library and no LiteRT, so
 * [supported] is false there and the sticker editor leaves its cutout
 * control out. So does a build whose Google libraries are switched off
 * ([GoogleLibraries]), since both engines are Google's. The eraser and
 * restore brushes work the same in every build.
 */
object SubjectCutout {

    val supported: Boolean get() = BuildConfig.GOOGLE_SDKS && GoogleLibraries.usable

    /** Below this share of the canvas, a mask is a speck and not a subject. */
    private const val MIN_COVERAGE = 0.01f

    /** Above this, it kept everything, which is the same as doing nothing. */
    private const val MAX_COVERAGE = 0.99f

    sealed interface Result {
        /** ALPHA_8 mask the size of the input; 255 keeps the pixel. */
        data class Ok(val alpha: Bitmap) : Result

        /** The model ran and found nothing worth keeping. */
        data object NoSubject : Result

        /** No engine could give us a model. */
        data object ModelUnavailable : Result

        data object Failed : Result

        /** This build has no segmenter at all. */
        data object Unsupported : Result
    }

    /**
     * Whether [ensureModel] would ask Play services for the model, so the
     * editor can say where the download comes from before it starts one.
     */
    fun downloadsFromPlayServices(context: Context): Boolean = PlayServices.isInstalled(context)

    /** Whether either engine's model is already on the device. Never downloads. */
    suspend fun modelReady(context: Context): Boolean =
        LocalSubjectCutout.modelReady(context) ||
            (PlayServices.isInstalled(context) && GmsSubjectCutout.modelReady(context))

    /**
     * Gets a model, reporting progress from 0 to 1: the Play services module
     * where there are Play services, and the app's own where there are none
     * or where they turn out not to have it. False when neither could be had —
     * no network, no room, device policy — in which case the editor keeps its
     * brushes and says so.
     */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        if (PlayServices.isInstalled(context) && GmsSubjectCutout.ensureModel(context, onProgress)) {
            return true
        }
        return LocalSubjectCutout.ensureModel(context, onProgress)
    }

    /**
     * The subject of [image] as an alpha mask.
     *
     * A mask that keeps almost nothing, or almost everything, comes back as
     * [Result.NoSubject]: applying the first would erase the sticker, and
     * within a session the edit is destructive.
     */
    suspend fun cutOut(context: Context, image: Bitmap): Result {
        val local = LocalSubjectCutout.modelReady(context)
        val fromGms = if (PlayServices.isInstalled(context) && GmsSubjectCutout.modelReady(context)) {
            GmsSubjectCutout.cutOut(image)
        } else {
            null
        }
        val result = when {
            fromGms is Result.Ok -> fromGms
            local -> LocalSubjectCutout.cutOut(context, image)
            else -> fromGms ?: Result.ModelUnavailable
        }
        return if (result is Result.Ok) judged(result) else result
    }

    private fun judged(result: Result.Ok): Result {
        val coverage = coverageOf(result.alpha)
        if (coverage >= MIN_COVERAGE && coverage <= MAX_COVERAGE) return result
        result.alpha.recycle()
        return Result.NoSubject
    }

    /** Share of [alpha]'s pixels the mask keeps, sampled on a coarse grid. */
    private fun coverageOf(alpha: Bitmap): Float {
        val step = maxOf(1, minOf(alpha.width, alpha.height) / 64)
        var kept = 0
        var seen = 0
        var y = 0
        while (y < alpha.height) {
            var x = 0
            while (x < alpha.width) {
                seen++
                if ((alpha.getPixel(x, y) ushr 24) > 127) kept++
                x += step
            }
            y += step
        }
        return if (seen == 0) 0f else kept.toFloat() / seen
    }
}
