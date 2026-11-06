package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager.DownloadStatus
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.core.input.zinnia.ZinniaData
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The zinnia side of handwriting: which of the keyboard's languages it reads,
 * its model downloads, and its recognisers. [HandwritingModels] routes every
 * tag that starts with [PREFIX] here, so the panel, the settings screen and
 * the service treat a zinnia model exactly like one of ML Kit's.
 *
 * A zinnia model is ready when two things are on the device: libwmzinnia
 * (gate [FeatureModules.zinnia]: a Play split, or a download pinned by
 * NativeDelivery) and the language's model, a [CjkDictPack] from the data
 * repository ([ZinniaData]). One download fetches both, library first.
 */
object ZinniaModels {

    const val PREFIX = "zinnia-"
    const val JAPANESE = "zinnia-ja"
    const val SIMPLIFIED_CHINESE = "zinnia-zh-Hans"
    const val TRADITIONAL_CHINESE = "zinnia-zh-Hant"

    private val packs: Map<String, CjkDictPack> = mapOf(
        JAPANESE to ZinniaData.japanese,
        SIMPLIFIED_CHINESE to ZinniaData.simplifiedChinese,
        TRADITIONAL_CHINESE to ZinniaData.traditionalChinese,
    )

    /** The badge on the panel's language chip, as short as ML Kit's. */
    private val shortLabels = mapOf(
        JAPANESE to "JA",
        SIMPLIFIED_CHINESE to "简",
        TRADITIONAL_CHINESE to "繁",
    )

    /** Written the way the registry writes language names: own name, then English. */
    private val displayNames = mapOf(
        JAPANESE to "日本語 · Japanese",
        SIMPLIFIED_CHINESE to "简体中文 · Simplified Chinese",
        TRADITIONAL_CHINESE to "繁體中文 · Traditional Chinese",
    )

    @Volatile
    private var filesDir: File? = null

    /** Where the models live; set once at startup, before any status check. */
    fun attach(filesDir: File) {
        this.filesDir = filesDir
    }

    fun isTag(tag: String): Boolean = tag.startsWith(PREFIX)

    fun packFor(tag: String): CjkDictPack? = packs[tag]

    /**
     * The zinnia model one of the keyboard's languages writes with, or null
     * for a language zinnia cannot read. Chinese follows the keyboard's
     * Traditional output switch; Cantonese is written in Traditional.
     */
    fun tagFor(language: LanguageDef, traditional: Boolean): String? = when (language.id) {
        "ja" -> JAPANESE
        "zh" -> if (traditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
        "yue" -> TRADITIONAL_CHINESE
        else -> null
    }

    /** Every model a language can write with, for the settings list: both scripts for Chinese. */
    fun tagsFor(language: LanguageDef): List<String> = when (language.id) {
        "ja" -> listOf(JAPANESE)
        "zh" -> listOf(SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE)
        "yue" -> listOf(TRADITIONAL_CHINESE)
        else -> emptyList()
    }

    fun displayName(tag: String): String = displayNames[tag] ?: tag

    fun shortLabel(tag: String): String = shortLabels[tag] ?: tag.removePrefix(PREFIX).uppercase()

    /** The model's size on disk, which is also what the download brings in. */
    fun installedBytes(tag: String): Long = packFor(tag)?.sizeBytes ?: 0L

    /** Where the model is published, for the offline import's list of links. */
    fun packUrl(tag: String): String? = packFor(tag)?.takeIf { it.available }?.url

    private fun modelFile(tag: String): File? {
        val dir = filesDir ?: return null
        val pack = packFor(tag) ?: return null
        return ZinniaData.file(dir, pack)
    }

    /** The library is loadable and [tag]'s model is on disk. */
    suspend fun isDownloaded(tag: String): Boolean {
        if (packFor(tag) == null || !FeatureModules.zinnia.installed) return false
        return withContext(Dispatchers.IO) { modelFile(tag) != null }
    }

    /**
     * Fetches whatever [tag] still lacks: the library through its gate,
     * then the model through the CJK pack downloader, one progress readout
     * for both. Throws [HandwritingDownloadException] with a message for the
     * user when downloads of libraries are not allowed on this build yet, an
     * [IOException] on any other failure.
     */
    suspend fun download(context: Context, tag: String, onProgress: (HandwritingDownloadProgress) -> Unit) {
        val pack = packFor(tag) ?: throw IOException("No zinnia model for $tag")
        val gate = FeatureModules.zinnia
        if (!gate.installed) {
            // No InternetPermission.check() here: the offline build's gate
            // fails at once with the import it needs, which says more.
            val here = HandwritingModels.awaitLibrary(gate, onProgress)
            if (!here) {
                val state = gate.state.value
                if (state is ModuleState.Missing && state.consent != null) {
                    val message = if (state.consent == NativeConsent.IMPORT) {
                        R.string.core_intel_handwriting_zinnia_import_needed
                    } else {
                        R.string.core_intel_handwriting_zinnia_consent_needed
                    }
                    throw HandwritingDownloadException(context.getString(message))
                }
                throw IOException("The zinnia library did not arrive")
            }
        }
        val dir = context.filesDir
        if (CjkDictStore.isDownloaded(dir, pack)) return
        InternetPermission.check()
        CjkDictDownloadManager.startAll(dir, listOf(pack))
        try {
            val settled = CjkDictDownloadManager.states.first { states ->
                when (val status = states[pack.id]) {
                    is DownloadStatus.Downloading -> {
                        onProgress(HandwritingDownloadProgress(bytes = status.bytes, totalBytes = status.total.coerceAtLeast(0L)))
                        false
                    }
                    DownloadStatus.Downloaded, is DownloadStatus.Failed -> true
                    // Paused is what a cancel elsewhere leaves behind.
                    is DownloadStatus.Paused -> true
                    DownloadStatus.NotDownloaded, null -> false
                }
            }[pack.id]
            if (settled != DownloadStatus.Downloaded) throw IOException("The $tag model did not download: $settled")
        } catch (e: CancellationException) {
            // The panel's button doubles as cancel: stop the fetch it started,
            // leaving the partial file for the next attempt to resume.
            CjkDictDownloadManager.cancel(pack)
            throw e
        }
    }

    /** Removes [tag]'s model; the library stays, shared by the other two. */
    fun delete(tag: String) {
        val dir = filesDir ?: return
        val pack = packFor(tag) ?: return
        CjkDictDownloadManager.delete(dir, pack)
    }

    /** A recogniser over [tag]'s model; null while the library or the model is missing. */
    internal fun recognizer(tag: String): ZinniaRecognizer? {
        val file = modelFile(tag) ?: return null
        return ZinniaRecognizer.open(file)
    }
}
