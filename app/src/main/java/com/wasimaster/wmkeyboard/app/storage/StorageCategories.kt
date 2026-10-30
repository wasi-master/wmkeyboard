package com.wasimaster.wmkeyboard.app.storage

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.STICKER_GENERATOR_ROUTE
import com.wasimaster.wmkeyboard.app.deleteNeuralPhonetic
import com.wasimaster.wmkeyboard.app.modules.PlayModules
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryStore
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Article
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.LibraryBooks
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ShortText
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Android
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cached
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Interests
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Inventory2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NetworkCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QueryStats
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SystemUpdate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenDownloads
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenImport
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenModels
import com.wasimaster.wmkeyboard.core.upscale.UpscaleModels
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.input.mozc.MozcRuntime
import com.wasimaster.wmkeyboard.core.input.rime.RimeRuntime
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NativePin
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.semantic.SemanticDownloads
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.settings.LockedSettings
import com.wasimaster.wmkeyboard.core.settings.SemanticSearchMode
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.vocab.VocabDownloadManager
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.voice.StrongDenoise
import com.wasimaster.wmkeyboard.core.voice.vad.VadModels
import com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import com.wasimaster.wmkeyboard.core.voice.speech.MmsVoices
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitLatinDownloads
import com.wasimaster.wmkeyboard.ime.ui.mediaImageLoader
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Everything the app keeps on disk, named in words a user recognises.
 *
 * One list, read by both the Storage screen and its detail screens: a category
 * knows where its bytes live, how to list them one by one, and how to delete
 * them. Adding a directory to the app means adding a row here, or its bytes
 * quietly land in the "Other data" residual instead.
 *
 * Deleting goes through the store that owns the data wherever one exists —
 * `deletePack`, `FontStore.delete`, and so on — rather than removing the files
 * underneath it. Those stores keep a manifest next to the files and a copy of
 * it in memory, shared with the running keyboard in the same process, so a raw
 * `rm` leaves the keyboard drawing a pack that is no longer there.
 */

internal enum class StorageGroup(@StringRes val title: Int) {
    APP(R.string.storage_group_app_title),
    DOWNLOADS(R.string.storage_group_downloads_title),
    LOOKS(R.string.storage_group_looks_title),
    PERSONAL(R.string.storage_group_personal_title),
    CACHE(R.string.storage_group_cache_title),
    SYSTEM(R.string.storage_group_system_title),
}

/** How much warning deleting a category deserves. */
internal enum class Danger {
    /** A cache. Deleting it costs a re-download at worst. */
    NONE,

    /** Downloaded content. Gone until the user fetches it again. */
    REDOWNLOAD,

    /** Made by the user, or learned from them. Not recoverable. */
    PERSONAL,

    /** Every setting at once. Confirmed twice. */
    RESET,
}

/**
 * The detail screen's route for one category.
 *
 * A route of its own, rather than every category sharing the Storage screen's:
 * a flight is keyed on where it is going, so twenty categories pointing at one
 * route would hang the same key on twenty rows, and each detail screen would
 * land wearing the Storage screen's own pie chart instead of the category's
 * icon.
 */
internal fun storageRoute(id: String): String = "storage/$id"

/** What the category lambdas are handed. */
internal class StorageEnv(
    val context: Context,
    val repository: SettingsRepository,
    val roots: StorageRoots,
)

/** One deletable thing inside a category. */
internal class StorageItem(
    val id: String,
    val label: String,
    val detail: String? = null,
    val bytes: Long,
    val files: List<File> = emptyList(),
    val deletable: Boolean = true,
    /** An image to draw beside the row, when the item is one. */
    val preview: File? = null,
    /** Whether the row stands for a directory, for the icon it falls back to. */
    val directory: Boolean = false,
)

internal class StorageCategory(
    val id: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val icon: ImageVector,
    val accent: Color,
    val group: StorageGroup,
    val danger: Danger = Danger.NONE,
    /** The screen that manages this data properly, when one exists. */
    val manageRoute: String? = null,
    /** True when the size is handed to us by the system rather than walked. */
    val synthetic: Boolean = false,
    /** One line drawn under the row; 0 for none. */
    @StringRes val note: Int = 0,
    private val pathsOf: (StorageRoots) -> List<File> = { emptyList() },
    private val countOf: ((StorageRoots) -> Int)? = null,
    private val itemsOf: (suspend (StorageEnv) -> List<StorageItem>)? = null,
    private val deleteOne: (suspend (StorageEnv, StorageItem) -> Unit)? = null,
    private val clearOf: (suspend (StorageEnv) -> Unit)? = null,
) {
    /** A residual row has no files of its own, so there is nothing to delete. */
    val clearable: Boolean get() = !synthetic

    fun paths(roots: StorageRoots): List<File> = pathsOf(roots)

    fun count(roots: StorageRoots): Int =
        countOf?.invoke(roots) ?: pathsOf(roots).sumOf { childrenOf(it).size }

    suspend fun items(env: StorageEnv): List<StorageItem> =
        itemsOf?.invoke(env) ?: defaultItems(env)

    suspend fun delete(env: StorageEnv, item: StorageItem) {
        val custom = deleteOne
        if (custom != null) custom(env, item) else item.files.forEach { it.deleteRecursively() }
    }

    suspend fun clear(env: StorageEnv) {
        val custom = clearOf
        if (custom != null) custom(env) else paths(env.roots).forEach { emptyOut(it) }
    }

    /** Direct children, largest first — right for a directory of packs or models. */
    private fun defaultItems(env: StorageEnv): List<StorageItem> =
        paths(env.roots).flatMap { childrenOf(it) }.map { env.namedItem(it) }
}

/**
 * Deletes what is inside [path] but leaves [path] itself, so anything holding
 * the directory open keeps a valid handle. A plain file is deleted outright.
 */
private fun emptyOut(path: File) {
    if (!path.exists()) return
    if (path.isDirectory) path.listFiles()?.forEach { it.deleteRecursively() } else path.delete()
}

/**
 * Deletes the sidecars an interrupted import or download leaves behind — every
 * store in the app stages into `.staging_<random>/` and downloads into `*.part`
 * with its `*.part.ranges` resume map — without touching the files the store itself is tracking.
 */
private fun sweepLeftovers(dir: File) {
    childrenOf(dir)
        .filter { it.name.startsWith(".staging_") || it.name.endsWith(".part") || it.name.endsWith(".part.ranges") }
        .forEach { it.deleteRecursively() }
}

internal object StorageCategories {

    const val APP_PACKAGE = "app_package"
    const val PLAY_MODULES = "play_modules"
    const val OTHER_CACHE = "other_cache"
    const val OTHER_DATA = "other_data"
    const val UPDATES = "updates"

    /**
     * The kept sticker sources, listed beside the packs in the sticker
     * category. Not a pack id — [StickerPackStore] mints those as
     * `pack_<millis>` — so deleting this row cannot delete a pack.
     */
    private const val STICKER_ORIGINALS_ITEM = "sticker_originals"

    /**
     * The background remover the sticker editor downloads for itself where
     * there are no Play services to supply one. It is listed with the stickers
     * because that is the only thing it is for.
     */
    private const val STICKER_CUTOUT_ITEM = "sticker_cutout_model"

    /** The upscaler's networks, listed with the sticker generator's models. */
    private const val UPSCALER_ITEM = "upscaler_models"

    /** Whistle's item among the Whisper model directories, whose ids are catalog ids. */
    private const val WHISTLE_ITEM_ID = "whistle"

    /** The ExecuTorch engines' items among them: one per model, the catalog id after this prefix. */
    private const val ET_ITEM_PREFIX = "et:"
    private const val MMS_ITEM_PREFIX = "mms:"
    private const val DENOISE_ITEM_ID = "denoise"
    private const val VAD_ITEM_ID = "vad"
    private const val PUNCTUATION_ITEM_ID = "punctuation"

    fun byId(id: String): StorageCategory? = all.firstOrNull { it.id == id }

    /** In display order: the app, then downloads, looks, personal data, caches, the rest. */
    val all: List<StorageCategory> = buildList {
        add(appPackage())
        // Only Play installs modules; anywhere else the row could only be empty.
        if (BuildConfig.ENABLE_PLAY_STORE) add(playModules())
        addAll(downloads())
        addAll(looks())
        addAll(personal())
        addAll(caches())
        addAll(rest())
    }

    // ---- the app ----

    private fun appPackage() = StorageCategory(
        id = APP_PACKAGE,
        title = R.string.storage_app_package_title,
        subtitle = R.string.storage_app_package_subtitle,
        icon = Icons.Outlined.Android,
        accent = Color(0xFF78909C),
        group = StorageGroup.APP,
        synthetic = true,
        itemsOf = { env ->
            val info = env.context.applicationInfo
            // Play's on-demand modules have a row of their own, by name.
            val modules = installedModuleSplits(env.roots).toSet()
            buildList {
                add(File(info.sourceDir) to 0)
                env.roots.appSplits.filterNot { it in modules }.forEach { add(it to 0) }
                // The only piece whose own name says nothing: the directory is
                // called after the ABI it holds, `arm64` and the like.
                info.nativeLibraryDir?.let { add(File(it) to R.string.storage_file_native_libs_label) }
            }.map { (file, forced) ->
                if (forced == 0) env.namedItem(file, deletable = false)
                else StorageItem(
                    id = file.absolutePath,
                    label = env.context.getString(forced),
                    detail = file.name,
                    bytes = diskUsage(file, env.roots.blockSize),
                    deletable = false,
                    directory = true,
                )
            }.let { files ->
                // The system's figure also counts what Android compiled from
                // the APK, kept beside it where the app cannot list it: the
                // difference, so the rows add up to the row's own size.
                val moduleBytes = modules.sumOf { diskUsage(it, env.roots.blockSize) }
                val compiled = (systemAppBytes(env.context) ?: 0L) - moduleBytes - files.sumOf { it.bytes }
                if (compiled <= 0L) {
                    files
                } else {
                    files + StorageItem(
                        id = "compiled",
                        label = env.context.getString(R.string.storage_app_compiled_label),
                        detail = env.context.getString(R.string.storage_app_compiled_detail),
                        bytes = compiled,
                        deletable = false,
                    )
                }
            }
        },
    )

    /**
     * Play's on-demand modules (the Play build only): the features the app
     * fetched from the Play Store when they were first used, one row each, by
     * the name Play gave it while it downloaded. Removing one asks Play to
     * uninstall it, which Play does later, usually with the app in the
     * background; until then it stays listed, marked as on its way out.
     */
    private fun playModules() = StorageCategory(
        id = PLAY_MODULES,
        title = R.string.storage_play_modules_title,
        subtitle = R.string.storage_play_modules_subtitle,
        icon = Icons.Outlined.Extension,
        accent = Color(0xFF4DB6AC),
        group = StorageGroup.APP,
        danger = Danger.REDOWNLOAD,
        pathsOf = { roots -> installedPlayModules().flatMap { moduleFiles(roots, it) } },
        countOf = { _ -> installedPlayModules().size },
        itemsOf = { env ->
            installedPlayModules().map { module ->
                val leaving = module in PlayModules.removing
                StorageItem(
                    id = module,
                    label = playModuleTitle(env.context, module),
                    detail = if (leaving) env.context.getString(R.string.storage_play_module_removing) else module,
                    bytes = moduleFiles(env.roots, module).sumOf { diskUsage(it, env.roots.blockSize) },
                    deletable = !leaving,
                )
            }
        },
        deleteOne = { _, item -> PlayModules.uninstall(listOf(item.id)) },
        clearOf = { _ ->
            val staying = installedPlayModules().filterNot { it in PlayModules.removing }
            if (staying.isNotEmpty()) PlayModules.uninstall(staying)
        },
    )

    // ---- downloads ----

    private fun downloads() = listOf(
        StorageCategory(
            id = "wordlists",
            title = R.string.storage_wordlists_title,
            subtitle = R.string.storage_wordlists_subtitle,
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            accent = Color(0xFF26A69A),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "languages",
            note = R.string.storage_note_mapped,
            pathsOf = { listOf(File(it.files, "dict"), File(it.files, "cjk_dicts"), File(it.files, "rime"), File(it.files, "mozc")) },
            itemsOf = { env ->
                // RIME's unpacked and compiled schemas, with the words it learned:
                // one item, since they only make sense together.
                val rime = File(env.roots.files, "rime").takeIf(File::isDirectory)?.let { dir ->
                    StorageItem(
                        id = dir.absolutePath,
                        label = env.context.getString(R.string.storage_rime_label),
                        detail = dir.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
                // What Mozc learned from the user's picks; its dictionary is a
                // CJK pack below.
                val mozc = File(env.roots.files, "mozc").takeIf(File::isDirectory)?.let { dir ->
                    StorageItem(
                        id = dir.absolutePath,
                        label = env.context.getString(R.string.storage_mozc_label),
                        detail = dir.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
                listOfNotNull(rime, mozc) +
                    languageItems(env, File(env.roots.files, "dict")) +
                    childrenOf(File(env.roots.files, "cjk_dicts")).map { dir ->
                        // These are named after the input method rather than a
                        // language: `pinyin`, `jyutping`, `cangjie`.
                        val pack = CjkDictCatalog.byId(dir.name)
                        StorageItem(
                            id = dir.absolutePath,
                            label = pack?.let { env.context.getString(it.displayNameRes) } ?: dir.name,
                            detail = pack?.let { dir.name },
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                            directory = true,
                        )
                    }
            },
            deleteOne = { env, item ->
                // A running Mozc keeps its history in memory and would write
                // it back, so it forgets it as well.
                if (item.id == File(env.roots.files, "mozc").absolutePath) MozcRuntime.clearHistory(env.context)
                item.files.forEach { it.deleteRecursively() }
                refreshWordlists(env)
            },
            clearOf = { env ->
                emptyOut(File(env.roots.files, "dict"))
                emptyOut(File(env.roots.files, "cjk_dicts"))
                File(env.roots.files, "rime").deleteRecursively()
                MozcRuntime.clearHistory(env.context)
                File(env.roots.files, "mozc").deleteRecursively()
                refreshWordlists(env)
            },
        ),
        StorageCategory(
            id = "bundled_dicts",
            title = R.string.storage_bundled_dicts_title,
            subtitle = R.string.storage_bundled_dicts_subtitle,
            icon = Icons.Outlined.Inventory2,
            accent = Color(0xFF00897B),
            group = StorageGroup.DOWNLOADS,
            note = R.string.storage_note_rebuilds,
            // Device-protected: the keyboard needs a dictionary before the user
            // has unlocked the phone, so this copy lives where it can read it.
            pathsOf = { listOf(File(it.deFiles, "dict")) },
            itemsOf = { env -> languageItems(env, File(env.roots.deFiles, "dict")) },
        ),
        StorageCategory(
            id = "emoji_dicts",
            title = R.string.storage_emoji_dicts_title,
            subtitle = R.string.storage_emoji_dicts_subtitle,
            icon = Icons.Outlined.EmojiEmotions,
            accent = Color(0xFFFFB300),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "emojikeywords",
            pathsOf = { listOf(File(it.files, "emoji_dict"), File(it.files, "emoji_keywords")) },
            itemsOf = { env ->
                languageItems(env, File(env.roots.files, "emoji_dict")) +
                    languageItems(env, File(env.roots.files, "emoji_keywords"))
            },
            deleteOne = { env, item ->
                item.files.forEach { it.deleteRecursively() }
                EmojiDictDownloadManager.refresh(env.roots.files)
            },
        ),
        StorageCategory(
            id = "vocab",
            title = R.string.storage_vocab_title,
            subtitle = R.string.storage_vocab_subtitle,
            icon = Icons.Outlined.AutoStories,
            accent = Color(0xFF8E24AA),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.PERSONAL,
            manageRoute = "vocab/packs",
            pathsOf = { listOf(File(it.files, VocabPacks.DIR_NAME)) },
            itemsOf = { env -> languageItems(env, File(env.roots.files, VocabPacks.DIR_NAME)) },
            deleteOne = { env, item ->
                item.files.forEach { it.deleteRecursively() }
                VocabDownloadManager.refresh(env.roots.files)
            },
        ),
        StorageCategory(
            id = "voice_models",
            title = R.string.storage_voice_models_title,
            subtitle = R.string.storage_voice_models_subtitle,
            icon = Icons.Outlined.Mic,
            accent = Color(0xFF7E57C2),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "voice",
            note = R.string.storage_note_mapped,
            pathsOf = {
                listOf(
                    WhisperStore.rootDir(it.files),
                    WhistleModelStore.rootDir(it.files),
                ) + EtEngine.entries.map { engine -> EtStore.rootDir(it.files, engine) } +
                    MmsVoices.rootDir(it.files) + StrongDenoise.rootDir(it.files) +
                    DictationPunctuation.rootDir(it.files) + VadModels.rootDir(it.files)
            },
            itemsOf = { env ->
                childrenOf(WhisperStore.rootDir(env.roots.files)).map { dir ->
                    StorageItem(
                        id = dir.name,
                        label = WhisperCatalog.models.firstOrNull { it.id == dir.name }?.displayName
                            ?: dir.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                    )
                } + listOfNotNull(
                    // Whistle's one model (#548), downloaded or part-way.
                    WhistleModelStore.rootDir(env.roots.files).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = WHISTLE_ITEM_ID,
                            label = "Whistle",
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    },
                ) + EtCatalog.models.mapNotNull { model ->
                    // Asr4All, Parakeet and Moonshine models, downloaded or part-way.
                    EtStore.dir(env.roots.files, model).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = ET_ITEM_PREFIX + model.id,
                            label = model.label,
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    }
                } + listOfNotNull(
                    // Reduce background noise's Strong model, downloaded or part-way.
                    StrongDenoise.rootDir(env.roots.files).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = DENOISE_ITEM_ID,
                            label = "DPDFNet",
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    },
                    // Add punctuation and capitals' model, downloaded or part-way.
                    DictationPunctuation.rootDir(env.roots.files).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = PUNCTUATION_ITEM_ID,
                            label = "Punctuation (mmBERT)",
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    },
                    // The phrase end detectors' models (#976) and an imported one.
                    VadModels.rootDir(env.roots.files).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = VAD_ITEM_ID,
                            label = "Phrase end detectors (Silero, FireRedVAD, Smart Turn)",
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    },
                ) + childrenOf(MmsVoices.rootDir(env.roots.files)).map { dir ->
                    // Read back's offline voices, one folder per language code.
                    StorageItem(
                        id = MMS_ITEM_PREFIX + dir.name,
                        label = "MMS voice (${dir.name})",
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                    )
                }
            },
            deleteOne = { env, item ->
                if (item.id == WHISTLE_ITEM_ID) {
                    WhistleModelDownloadManager.delete(env.roots.files)
                } else if (item.id == DENOISE_ITEM_ID) {
                    StrongDenoise.delete(env.roots.files)
                } else if (item.id == PUNCTUATION_ITEM_ID) {
                    DictationPunctuation.deleteAll(env.roots.files)
                } else if (item.id == VAD_ITEM_ID) {
                    VadModels.deleteAll(env.roots.files)
                } else if (item.id.startsWith(ET_ITEM_PREFIX)) {
                    EtCatalog.byId(item.id.removePrefix(ET_ITEM_PREFIX))
                        ?.let { EtDownloadManager.delete(env.roots.files, it) }
                } else if (item.id.startsWith(MMS_ITEM_PREFIX)) {
                    item.files.forEach { it.deleteRecursively() }
                } else {
                    // The selection goes first: the keyboard must never be pointed
                    // at a model file that is about to disappear.
                    env.repository.clearWhisperModelAssignments(item.id)
                    item.files.forEach { it.deleteRecursively() }
                    WhisperDownloadManager.refresh(env.roots.files)
                }
            },
            clearOf = { env ->
                WhisperCatalog.models.forEach { env.repository.clearWhisperModelAssignments(it.id) }
                env.repository.setWhisperModelId("")
                emptyOut(WhisperStore.rootDir(env.roots.files))
                WhisperDownloadManager.refresh(env.roots.files)
                WhistleModelDownloadManager.delete(env.roots.files)
                EtCatalog.models.forEach { EtDownloadManager.delete(env.roots.files, it) }
                emptyOut(MmsVoices.rootDir(env.roots.files))
                StrongDenoise.delete(env.roots.files)
                DictationPunctuation.deleteAll(env.roots.files)
                VadModels.deleteAll(env.roots.files)
            },
        ),
        StorageCategory(
            id = "glide_models",
            title = R.string.storage_glide_models_title,
            subtitle = R.string.storage_glide_models_subtitle,
            icon = Icons.Outlined.Gesture,
            accent = Color(0xFF5C6BC0),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "typing/gestures",
            note = R.string.storage_note_mapped,
            pathsOf = { listOf(FutoSwipe.rootDir(it.files)) },
            itemsOf = { env ->
                // FUTO Swipe's encoder and decoder, downloaded or part-way.
                listOfNotNull(
                    FutoSwipe.rootDir(env.roots.files).takeIf { it.isDirectory }?.let { dir ->
                        StorageItem(
                            id = "futo-swipe",
                            label = "FUTO Swipe",
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                        )
                    },
                )
            },
            deleteOne = { env, _ -> FutoSwipe.delete(env.roots.files) },
            clearOf = { env -> FutoSwipe.delete(env.roots.files) },
        ),
        StorageCategory(
            id = "ai_models",
            title = R.string.storage_ai_models_title,
            subtitle = R.string.storage_ai_models_subtitle,
            icon = Icons.Outlined.AutoAwesome,
            accent = Color(0xFF9575CD),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "tool/AI",
            note = R.string.storage_note_mapped,
            pathsOf = { listOf(LocalLlmStore.modelsDir(it.files)) },
            itemsOf = { env ->
                childrenOf(LocalLlmStore.modelsDir(env.roots.files)).map { dir ->
                    StorageItem(
                        id = dir.name,
                        label = LocalLlmCatalog.models.firstOrNull { it.id == dir.name }?.displayName
                            ?: dir.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                    )
                }
            },
            deleteOne = { env, item ->
                item.files.forEach { it.deleteRecursively() }
                LocalLlmDownloadManager.refresh(env.roots.files)
            },
            clearOf = { env ->
                env.repository.setAiLocalModelId("")
                emptyOut(LocalLlmStore.modelsDir(env.roots.files))
                LocalLlmDownloadManager.refresh(env.roots.files)
            },
        ),
        // Search by meaning: EmbeddingGemma's two packs, and the indexes built
        // from the user's own things with it. The indexes are what make the
        // models useful and nothing without them, so they go together, and
        // deleting the text pack turns the feature off: a mode left on with
        // no model behind it would offer a download nobody asked for.
        // One model for every Indic phonetic language, so it is one row; the
        // switches go off with it, as on the language page.
        StorageCategory(
            id = "neural_phonetic",
            title = R.string.storage_neural_phonetic_title,
            subtitle = R.string.storage_neural_phonetic_subtitle,
            icon = Icons.Outlined.AutoAwesome,
            accent = Color(0xFF5C6BC0),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "languages",
            pathsOf = { listOf(File(it.noBackupFiles, "xlit")) },
            itemsOf = { env ->
                val dir = File(env.roots.noBackupFiles, "xlit")
                if (childrenOf(dir).isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        StorageItem(
                            id = "xlit",
                            label = env.context.getString(R.string.storage_neural_phonetic_title),
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                            directory = true,
                        ),
                    )
                }
            },
            deleteOne = { env, _ -> deleteNeuralPhonetic(env.context, env.repository, env.repository.settings.first()) },
            clearOf = { env -> deleteNeuralPhonetic(env.context, env.repository, env.repository.settings.first()) },
        ),
        // Its mirror image, which romanized dictation spells with. No switch
        // to turn off: without it dictation goes back to the rules.
        StorageCategory(
            id = "romanized_dictation",
            title = R.string.storage_romanized_dictation_title,
            subtitle = R.string.storage_romanized_dictation_subtitle,
            icon = Icons.Outlined.AutoAwesome,
            accent = Color(0xFF5C6BC0),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "languages",
            pathsOf = { listOf(File(it.noBackupFiles, "xlit-latin")) },
            itemsOf = { env ->
                val dir = File(env.roots.noBackupFiles, "xlit-latin")
                if (childrenOf(dir).isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        StorageItem(
                            id = "xlit-latin",
                            label = env.context.getString(R.string.storage_romanized_dictation_title),
                            bytes = diskUsage(dir, env.roots.blockSize),
                            files = listOf(dir),
                            directory = true,
                        ),
                    )
                }
            },
            deleteOne = { env, _ -> IndicXlitLatinDownloads.delete(env.context) },
            clearOf = { env -> IndicXlitLatinDownloads.delete(env.context) },
        ),
        StorageCategory(
            id = "meaning_models",
            title = R.string.storage_meaning_title,
            subtitle = R.string.storage_meaning_subtitle,
            icon = Icons.Outlined.Search,
            accent = Color(0xFF7E57C2),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "ai",
            pathsOf = { listOf(File(it.noBackupFiles, "semantic")) },
            itemsOf = { env ->
                val models = SemanticModels.Pack.entries.mapNotNull { pack ->
                    val files = SemanticModels.parts(pack).flatMap { part ->
                        listOf(SemanticModels.file(env.roots.noBackupFiles, part), SemanticModels.partFile(env.roots.noBackupFiles, part))
                    }.filter { it.isFile }
                    if (files.isEmpty()) return@mapNotNull null
                    StorageItem(
                        id = pack.name,
                        label = env.context.getString(
                            if (pack == SemanticModels.Pack.TEXT) R.string.storage_meaning_text else R.string.storage_meaning_pictures,
                        ),
                        bytes = files.sumOf { diskUsage(it, env.roots.blockSize) },
                        files = files,
                    )
                }
                val indexDir = File(env.roots.noBackupFiles, "semantic/index")
                val index = if (childrenOf(indexDir).isEmpty()) emptyList() else listOf(
                    StorageItem(
                        id = MEANING_INDEX_ITEM,
                        label = env.context.getString(R.string.storage_meaning_index),
                        detail = env.context.getString(R.string.storage_meaning_index_detail),
                        bytes = diskUsage(indexDir, env.roots.blockSize),
                        files = listOf(indexDir),
                        directory = true,
                    ),
                )
                models + index
            },
            deleteOne = { env, item ->
                when (item.id) {
                    // Copies of private text, rebuilt from the stores; the
                    // keyboard holds them in memory too, so only turning the
                    // feature off makes them go for good.
                    MEANING_INDEX_ITEM -> turnMeaningOff(env)
                    SemanticModels.Pack.TEXT.name -> turnMeaningOff(env)
                    else -> {
                        SemanticDownloads.delete(env.context, SemanticModels.Pack.PICTURES)
                        env.repository.updateSemanticSearch { s ->
                            if (s.mode == SemanticSearchMode.TEXT_AND_PICTURES) s.copy(mode = SemanticSearchMode.TEXT) else s
                        }
                    }
                }
            },
            clearOf = { env -> turnMeaningOff(env) },
        ),
        // The sticker generator's models, catalog and imported, the picture
        // filter, and the upscaler's two small networks that share its
        // runtime; all of it comes back with one download.
        StorageCategory(
            id = "sticker_models",
            title = R.string.storage_stickergen_title,
            subtitle = R.string.storage_stickergen_subtitle,
            icon = Icons.Outlined.AutoAwesome,
            accent = Color(0xFFEC407A),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = STICKER_GENERATOR_ROUTE,
            pathsOf = { listOf(ImageGenModels.root(it.noBackupFiles)) },
            itemsOf = { env ->
                val catalog = ImageGenModels.Catalog.entries.mapNotNull { model ->
                    val dir = ImageGenModels.dir(env.roots.noBackupFiles, model)
                    if (childrenOf(dir).isEmpty()) return@mapNotNull null
                    StorageItem(
                        id = model.id,
                        label = model.displayName,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
                val imported = ImageGenImport.installed(env.context).map { model ->
                    val dir = File(ImageGenModels.importedRoot(env.roots.noBackupFiles), model.id)
                    StorageItem(
                        id = model.id,
                        label = model.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
                val upscaleDir = UpscaleModels.dir(env.roots.noBackupFiles)
                val upscaler = if (childrenOf(upscaleDir).isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        StorageItem(
                            id = UPSCALER_ITEM,
                            label = env.context.getString(R.string.storage_upscaler_item),
                            bytes = diskUsage(upscaleDir, env.roots.blockSize),
                            files = listOf(upscaleDir),
                            directory = true,
                        ),
                    )
                }
                catalog + imported + upscaler
            },
            deleteOne = { env, item ->
                val model = ImageGenModels.Catalog.byId(item.id)
                when {
                    item.id == UPSCALER_ITEM -> UpscaleModels.dir(env.roots.noBackupFiles).deleteRecursively()
                    model != null -> ImageGenDownloads.delete(env.context, model)
                    else -> ImageGenImport.delete(env.context, item.id)
                }
            },
            clearOf = { env ->
                ImageGenModels.Catalog.entries.forEach { ImageGenDownloads.cancel(it) }
                ImageGenModels.root(env.roots.noBackupFiles).deleteRecursively()
                ImageGenDownloads.refresh(env.context)
            },
        ),
        StorageCategory(
            id = "ocr_packs",
            title = R.string.storage_ocr_packs_title,
            subtitle = R.string.storage_ocr_packs_subtitle,
            icon = Icons.Outlined.DocumentScanner,
            accent = Color(0xFF5C6BC0),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "tool/OCR",
            pathsOf = { listOf(OcrPacks.dataDir(it.files), PaddleOcrModel.dir(it.files)) },
            itemsOf = { env ->
                val dir = OcrPacks.dataDir(env.roots.files)
                sweepLeftovers(dir)
                val paddleDir = PaddleOcrModel.dir(env.roots.files)
                sweepLeftovers(paddleDir)
                val tesseract = childrenOf(dir).map { file ->
                    val pack = file.name.removeSuffix(".traineddata")
                    StorageItem(
                        id = pack,
                        label = OcrLanguages.nameOf(pack),
                        detail = file.name,
                        bytes = diskUsage(file, env.roots.blockSize),
                        files = listOf(file),
                    )
                }
                // PaddleOCR's three files are one pack, and one row.
                val paddleFiles = childrenOf(paddleDir)
                val paddle = if (paddleFiles.isEmpty()) emptyList() else listOf(
                    StorageItem(
                        id = PaddleOcrModel.PACK,
                        label = OcrLanguages.nameOf(PaddleOcrModel.PACK),
                        detail = paddleFiles.joinToString(", ") { it.name },
                        bytes = paddleFiles.sumOf { diskUsage(it, env.roots.blockSize) },
                        files = paddleFiles,
                    ),
                )
                tesseract + paddle
            },
            deleteOne = { env, item -> OcrPacks.delete(env.roots.files, item.id) },
            clearOf = { env ->
                OcrPacks.downloaded(env.roots.files).forEach { OcrPacks.delete(env.roots.files, it) }
                // Also a Paddle download cut short, whose files are not yet a pack.
                OcrPacks.delete(env.roots.files, PaddleOcrModel.PACK)
            },
        ),
        StorageCategory(
            id = "gif_packs",
            title = R.string.storage_gif_packs_title,
            subtitle = R.string.storage_gif_packs_subtitle,
            icon = SymbolIcons.GifBox,
            accent = Color(0xFF7E57C2),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "tool/GIF",
            pathsOf = { listOf(File(it.files, "gif_packs")) },
            itemsOf = { env ->
                OfflineGifPacks.attach(env.roots.files)
                OfflineGifPacks.packs().map { pack ->
                    val dir = File(File(env.roots.files, "gif_packs"), pack.id)
                    StorageItem(
                        id = pack.id,
                        label = pack.name,
                        detail = pack.id,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
            },
            deleteOne = { env, item ->
                OfflineGifPacks.attach(env.roots.files)
                OfflineGifPacks.delete(item.id)
            },
            clearOf = { env ->
                OfflineGifPacks.attach(env.roots.files)
                OfflineGifPacks.packs().forEach { OfflineGifPacks.delete(it.id) }
                emptyOut(File(env.roots.files, "gif_packs"))
            },
        ),
        StorageCategory(
            id = "yomichan",
            title = R.string.storage_yomichan_title,
            subtitle = R.string.storage_yomichan_subtitle,
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            accent = Color(0xFF8D6E63),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.REDOWNLOAD,
            manageRoute = "tool/DICTIONARY",
            pathsOf = { listOf(File(it.files, YomichanDictionaries.DIR_NAME)) },
            itemsOf = { env ->
                YomichanDictionaries.attach(env.roots.files)
                YomichanDictionaries.installed().map { dictionary ->
                    val dir = File(File(env.roots.files, YomichanDictionaries.DIR_NAME), dictionary.info.id)
                    StorageItem(
                        id = dictionary.info.id,
                        label = dictionary.info.title,
                        detail = dictionary.info.revision,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                        directory = true,
                    )
                }
            },
            deleteOne = { env, item ->
                YomichanDictionaries.attach(env.roots.files)
                YomichanDictionaries.delete(item.id)
            },
            clearOf = { env ->
                YomichanDictionaries.attach(env.roots.files)
                YomichanDictionaries.installed().forEach { YomichanDictionaries.delete(it.info.id) }
                emptyOut(File(env.roots.files, YomichanDictionaries.DIR_NAME))
            },
        ),
        StorageCategory(
            id = "addon_index",
            title = R.string.storage_addon_index_title,
            subtitle = R.string.storage_addon_index_subtitle,
            icon = Icons.Outlined.Extension,
            accent = Color(0xFF7986CB),
            group = StorageGroup.DOWNLOADS,
            danger = Danger.PERSONAL,
            manageRoute = "addons",
            pathsOf = { listOf(File(it.files, "addons")) },
            clearOf = { env ->
                emptyOut(File(env.roots.files, "addons"))
                AddonStore.get(env.context).reload()
            },
        ),
        // The standard GitHub build's interface languages, one pack per
        // language for this version; a deleted one downloads again when the
        // language is picked.
        StorageCategory(
            id = "language_packs",
            title = R.string.storage_language_packs_title,
            subtitle = R.string.storage_language_packs_subtitle,
            icon = Icons.Outlined.Extension,
            accent = Color(0xFF81C784),
            group = StorageGroup.DOWNLOADS,
            pathsOf = { roots -> listOf(File(roots.noBackupFiles, "langpacks")) },
            clearOf = { env ->
                emptyOut(File(env.roots.noBackupFiles, "langpacks"))
                com.wasimaster.wmkeyboard.core.modules.LanguagePackResources.reload(env.context)
            },
        ),
        // Native delivery (GitHub and F-Droid builds): the libraries features
        // fetched the first time they were used. One row per library, named
        // after it rather than its hash directory; a deleted one downloads
        // again the next time its feature asks.
        StorageCategory(
            id = "feature_libraries",
            title = R.string.storage_feature_libraries_title,
            subtitle = R.string.storage_feature_libraries_subtitle,
            icon = Icons.Outlined.Extension,
            accent = Color(0xFF90A4AE),
            group = StorageGroup.DOWNLOADS,
            pathsOf = { roots -> listOfNotNull(NativeDelivery.store()?.root?.takeIf { it.startsWith(roots.noBackupFiles) }) },
            countOf = { _ -> installedLibraries().size },
            itemsOf = { env ->
                installedLibraries().map { (pin, file) ->
                    StorageItem(id = pin.sha256, label = pin.name, detail = pin.gate, bytes = file.length(), files = listOf(file.parentFile ?: file))
                }
            },
        ),
    )

    /** The storage row's name for search by meaning's indexes, beside its packs. */
    private const val MEANING_INDEX_ITEM = "meaning_index"

    /**
     * Search by meaning off, its models and indexes gone. The setting goes
     * first, so the keyboard drops the indexes it holds in memory rather than
     * saving them back; the files are deleted here too, for a keyboard that
     * is not running.
     */
    private suspend fun turnMeaningOff(env: StorageEnv) {
        env.repository.updateSemanticSearch { it.copy(mode = SemanticSearchMode.OFF) }
        SemanticDownloads.delete(env.context, SemanticModels.Pack.TEXT)
        File(env.roots.noBackupFiles, "semantic").deleteRecursively()
    }

    /** The delivered libraries on disk, with the pin each one answers. */
    // (Interface language packs are listed by their own category below.)
    private fun installedLibraries(): List<Pair<NativePin, File>> {
        val store = NativeDelivery.store() ?: return emptyList()
        return NativeDelivery.pins().filter(store::installed).map { it to store.fileOf(it) }
    }

    // ---- looks and add-ons ----

    private fun looks() = listOf(
        StorageCategory(
            id = "themes",
            title = R.string.storage_themes_title,
            subtitle = R.string.storage_themes_subtitle,
            icon = Icons.Outlined.Palette,
            accent = Color(0xFFEC407A),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "themes",
            pathsOf = { listOf(File(it.files, "theme_images"), File(it.files, "theme_photos")) },
            deleteOne = { env, item ->
                // A photo in the rotation pool is listed in pool.json as well as
                // being a file, so it has to leave through the pool's own door.
                val pool = PhotoBackgroundManager.poolDir(env.context)
                item.files.forEach {
                    if (it.parentFile == pool) PhotoBackgroundManager.removeFromPool(env.context, it.name)
                    else it.deleteRecursively()
                }
            },
        ),
        StorageCategory(
            id = "stickers",
            title = R.string.storage_stickers_title,
            subtitle = R.string.storage_stickers_subtitle,
            icon = SymbolIcons.Sticker,
            accent = Color(0xFFF06292),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "sticker_packs",
            pathsOf = { listOf(File(it.files, "stickers"), CutoutModel.dir(it.files)) },
            itemsOf = { env ->
                val store = StickerPackStore.get(env.context)
                val packs = store.packs().map { pack ->
                    StorageItem(
                        id = pack.id,
                        label = pack.name,
                        bytes = store.packDir(pack.id)?.let { diskUsage(it, env.roots.blockSize) } ?: 0L,
                        files = listOfNotNull(store.packDir(pack.id)),
                    )
                }
                // The pictures the stickers were made from. They sit beside
                // the packs rather than inside them, so a row of their own is
                // also the only way the sum of the rows matches the category.
                val originals = store.originalsDir()?.takeIf { it.isDirectory }
                val keptBytes = originals?.let { diskUsage(it, env.roots.blockSize) } ?: 0L
                val kept = if (keptBytes <= 0L) emptyList() else listOf(
                    StorageItem(
                        id = STICKER_ORIGINALS_ITEM,
                        label = env.context.getString(R.string.storage_stickers_originals_label),
                        detail = env.context.getString(R.string.storage_stickers_originals_detail),
                        bytes = keptBytes,
                        files = listOfNotNull(originals),
                    ),
                )
                val cutoutDir = CutoutModel.dir(env.roots.files)
                val cutoutBytes = diskUsage(cutoutDir, env.roots.blockSize)
                val cutout = if (cutoutBytes <= 0L) emptyList() else listOf(
                    StorageItem(
                        id = STICKER_CUTOUT_ITEM,
                        label = env.context.getString(R.string.storage_stickers_cutout_label),
                        detail = env.context.getString(R.string.storage_stickers_cutout_detail),
                        bytes = cutoutBytes,
                        files = listOf(cutoutDir),
                    ),
                )
                packs + kept + cutout
            },
            deleteOne = { env, item ->
                val store = StickerPackStore.get(env.context)
                when (item.id) {
                    STICKER_ORIGINALS_ITEM -> store.clearOriginals()
                    STICKER_CUTOUT_ITEM -> emptyOut(CutoutModel.dir(env.roots.files))
                    else -> store.deletePack(item.id)
                }
            },
            clearOf = { env ->
                val store = StickerPackStore.get(env.context)
                store.packs().forEach { store.deletePack(it.id) }
                // Deleting the packs takes each sticker's kept picture with
                // it; this is for the ones a sticker deleted before this
                // version left behind.
                store.clearOriginals()
                sweepLeftovers(File(env.roots.files, "stickers"))
                emptyOut(CutoutModel.dir(env.roots.files))
            },
        ),
        StorageCategory(
            id = "icon_packs",
            title = R.string.storage_icon_packs_title,
            subtitle = R.string.storage_icon_packs_subtitle,
            icon = Icons.Outlined.Interests,
            accent = Color(0xFFFF7043),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "icons",
            pathsOf = { listOf(File(it.files, "iconpacks")) },
            itemsOf = { env ->
                val store = IconPackStore.get(env.context)
                store.packs().map { pack ->
                    StorageItem(
                        id = pack.id,
                        label = pack.name,
                        bytes = store.packDir(pack.id)?.let { diskUsage(it, env.roots.blockSize) } ?: 0L,
                        files = listOfNotNull(store.packDir(pack.id)),
                    )
                }
            },
            deleteOne = { env, item -> IconPackStore.get(env.context).deletePack(item.id) },
            clearOf = { env ->
                val store = IconPackStore.get(env.context)
                store.packs().forEach { store.deletePack(it.id) }
                sweepLeftovers(File(env.roots.files, "iconpacks"))
            },
        ),
        StorageCategory(
            id = "fonts",
            title = R.string.storage_fonts_title,
            subtitle = R.string.storage_fonts_subtitle,
            icon = Icons.Outlined.TextFields,
            accent = Color(0xFFAB47BC),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "fonts",
            pathsOf = { listOf(File(it.files, "fonts")) },
            itemsOf = { env ->
                val store = FontStore.get(env.context)
                store.fonts().map { font ->
                    StorageItem(
                        id = font.id,
                        label = font.name,
                        bytes = store.fileFor(font.id)?.let { diskUsage(it, env.roots.blockSize) } ?: 0L,
                        files = listOfNotNull(store.fileFor(font.id)),
                    )
                }
            },
            deleteOne = { env, item ->
                env.repository.forgetInstalledFont(FontStore.fontIdFor(item.id))
                FontStore.get(env.context).delete(item.id)
            },
            clearOf = { env ->
                val store = FontStore.get(env.context)
                store.fonts().forEach {
                    env.repository.forgetInstalledFont(FontStore.fontIdFor(it.id))
                    store.delete(it.id)
                }
                // The store owns `fonts/installed`, so leave that directory
                // alone and take only what it does not know about: the
                // pre-registry single-font files, and abandoned staging.
                val dir = File(env.roots.files, "fonts")
                childrenOf(dir).filter { it.isFile }.forEach { it.delete() }
                sweepLeftovers(dir)
            },
        ),
        StorageCategory(
            id = "key_sounds",
            title = R.string.storage_key_sounds_title,
            subtitle = R.string.storage_key_sounds_subtitle,
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            accent = Color(0xFF26C6DA),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "keypress/haptics",
            pathsOf = { listOf(File(it.files, "keysounds")) },
            itemsOf = { env ->
                val store = SoundStore.get(env.context)
                store.sounds().map { sound ->
                    StorageItem(
                        id = sound.id,
                        label = sound.name,
                        bytes = store.fileFor(sound.id)?.let { diskUsage(it, env.roots.blockSize) } ?: 0L,
                        files = listOfNotNull(store.fileFor(sound.id)),
                    )
                }
            },
            deleteOne = { env, item -> SoundStore.get(env.context).delete(item.id) },
            clearOf = { env ->
                val store = SoundStore.get(env.context)
                store.sounds().forEach { store.delete(it.id) }
                sweepLeftovers(File(env.roots.files, "keysounds"))
            },
        ),
        StorageCategory(
            id = "plugins",
            title = R.string.storage_plugins_title,
            subtitle = R.string.storage_plugins_subtitle,
            icon = Icons.Outlined.Terminal,
            accent = Color(0xFF06B6D4),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "plugins",
            pathsOf = { listOf(File(it.files, "plugins")) },
            itemsOf = { env ->
                val store = PluginStore.get(env.context)
                store.plugins().map { plugin ->
                    StorageItem(
                        id = plugin.id,
                        label = plugin.name,
                        bytes = store.dirFor(plugin.id)?.let { diskUsage(it, env.roots.blockSize) } ?: 0L,
                        files = listOfNotNull(store.dirFor(plugin.id)),
                    )
                }
            },
            deleteOne = { env, item -> PluginStore.get(env.context).delete(item.id) },
            clearOf = { env ->
                val store = PluginStore.get(env.context)
                store.plugins().forEach { store.delete(it.id) }
            },
        ),
    )

    // ---- the user's own data ----

    private fun personal() = listOf(
        StorageCategory(
            id = "learned",
            title = R.string.storage_learned_title,
            subtitle = R.string.storage_learned_subtitle,
            icon = Icons.Outlined.Psychology,
            accent = Color(0xFF66BB6A),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "privacy",
            pathsOf = { listOf(File(it.files, "learning")) },
            clearOf = { env ->
                emptyOut(File(env.roots.files, "learning"))
                // The Chinese, Japanese and Cantonese picks are also held in a
                // process-wide object, which would write them back.
                CjkLearning.store?.clear()
                // The keyboard holds the Latin lexicon in memory and saves it
                // on every field it leaves, so the file alone is not enough:
                // this is the counter it watches to know to re-read.
                env.repository.bumpLexiconVersion()
            },
        ),
        StorageCategory(
            id = "typing_stats",
            title = R.string.storage_typing_stats_title,
            subtitle = R.string.storage_typing_stats_subtitle,
            icon = Icons.Outlined.QueryStats,
            accent = Color(0xFF9CCC65),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "statistics",
            pathsOf = { listOf(File(it.files, "stats")) },
            clearOf = { env ->
                emptyOut(File(env.roots.files, "stats"))
                // The keyboard holds the counters in memory and saves them on
                // every field it leaves; this is the signal to re-read the
                // emptied file instead of writing the old numbers back.
                env.repository.bumpStatsVersion()
            },
        ),
        StorageCategory(
            id = "netlog",
            title = R.string.storage_netlog_title,
            subtitle = R.string.storage_netlog_subtitle,
            icon = Icons.Outlined.NetworkCheck,
            accent = Color(0xFFEF5350),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "network_activity",
            pathsOf = { listOf(File(it.files, NetLog.DIR)) },
            // Through the log, not a raw delete: it holds the rows in memory
            // and would write them straight back.
            clearOf = { NetLog.clear() },
        ),
        StorageCategory(
            id = "clipboard",
            title = R.string.storage_clipboard_title,
            subtitle = R.string.storage_clipboard_subtitle,
            icon = Icons.Outlined.ContentPaste,
            accent = Color(0xFF42A5F5),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "clipboard",
            pathsOf = { listOf(File(it.files, "clipboard")) },
        ),
        StorageCategory(
            id = "snippets",
            title = R.string.storage_snippets_title,
            subtitle = R.string.storage_snippets_subtitle,
            icon = Icons.AutoMirrored.Outlined.ShortText,
            accent = Color(0xFF5C6BC0),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "expander",
            pathsOf = { listOf(File(it.files, "snippets")) },
        ),
        StorageCategory(
            id = "ai_history",
            title = R.string.storage_ai_history_title,
            subtitle = R.string.storage_ai_history_subtitle,
            icon = Icons.Outlined.History,
            accent = Color(0xFF7E57C2),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "ai_history",
            pathsOf = { listOf(File(it.files, "ai")) },
            clearOf = { env ->
                // Its own instance, like the history screen's: each one re-reads
                // before it writes, so emptying this one is enough.
                AiHistoryStore(File(env.roots.files, AiHistoryStore.FILE_PATH)).deleteStorage()
            },
        ),
        StorageCategory(
            id = "custom_wordlists",
            title = R.string.storage_custom_wordlists_title,
            subtitle = R.string.storage_custom_wordlists_subtitle,
            icon = Icons.Outlined.Spellcheck,
            accent = Color(0xFF26A69A),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            manageRoute = "customdictionaries",
            pathsOf = { listOf(CustomDictionaries.root(it.files)) },
            itemsOf = { env ->
                childrenOf(CustomDictionaries.root(env.roots.files)).flatMap { dir ->
                    // A list and the word pairs and shortcuts beside it are one
                    // item: deleting the list alone would strand them.
                    val lists = CustomDictionaries.allLists(env.roots.files, dir.name)
                    val owned = lists.associateWith { CustomDictionaries.filesOf(it) }
                    val claimed = owned.values.flatten().toSet()
                    val grouped = owned.map { (list, files) ->
                        StorageItem(
                            id = list.absolutePath,
                            label = CustomDictionaries.displayName(list).substringBeforeLast('.'),
                            detail = LanguageRegistry.byId(dir.name).displayName,
                            bytes = files.sumOf { diskUsage(it, env.roots.blockSize) },
                            files = files,
                        )
                    }
                    grouped + childrenOf(dir).filter { it !in claimed }.map { file ->
                        StorageItem(
                            id = file.absolutePath,
                            label = file.nameWithoutExtension,
                            detail = LanguageRegistry.byId(dir.name).displayName,
                            bytes = diskUsage(file, env.roots.blockSize),
                            files = listOf(file),
                        )
                    }
                }
            },
            deleteOne = { env, item ->
                item.files.forEach { CustomDictionaries.remove(it) }
                env.repository.bumpCustomDictVersion()
            },
            clearOf = { env ->
                emptyOut(CustomDictionaries.root(env.roots.files))
                env.repository.bumpCustomDictVersion()
            },
        ),
        StorageCategory(
            id = "captures",
            title = R.string.storage_captures_title,
            subtitle = R.string.storage_captures_subtitle,
            icon = Icons.Outlined.PhotoCamera,
            accent = Color(0xFF00ACC1),
            group = StorageGroup.PERSONAL,
            danger = Danger.PERSONAL,
            pathsOf = { listOf(File(it.files, "camera"), File(it.files, "docscan")) },
        ),
        StorageCategory(
            id = "settings_data",
            title = R.string.storage_settings_data_title,
            subtitle = R.string.storage_settings_data_subtitle,
            icon = Icons.Outlined.Tune,
            accent = Color(0xFFEF5350),
            group = StorageGroup.PERSONAL,
            danger = Danger.RESET,
            manageRoute = "backup",
            // The store and its direct-boot mirror, and nothing else under
            // `shared_prefs`: the row's size has to be what clearing it frees,
            // and clearing goes through the repository rather than deleting
            // files that live SharedPreferences instances still have open.
            pathsOf = { settingsPaths(it) },
            itemsOf = { env ->
                // Listed but never individually deletable: half a settings store
                // is a worse state than none of one.
                settingsPaths(env.roots)
                    .flatMap { filesUnder(it) }
                    .map { env.namedItem(it, deletable = false) }
            },
            clearOf = { env -> env.repository.clearAllPreferences() },
        ),
    )

    // ---- caches ----

    private fun caches() = listOf(
        StorageCategory(
            id = "cache_images",
            title = R.string.storage_cache_images_title,
            subtitle = R.string.storage_cache_images_subtitle,
            icon = Icons.Outlined.Image,
            accent = Color(0xFF29B6F6),
            group = StorageGroup.CACHE,
            pathsOf = { listOf(File(it.cache, "image_cache")) },
            clearOf = { env ->
                // Coil keeps its own index of this directory in memory, shared
                // with the keyboard: emptying it behind Coil's back leaves the
                // index pointing at files that are gone.
                runCatching { mediaImageLoader(env.context).diskCache?.clear() }
                emptyOut(File(env.roots.cache, "image_cache"))
            },
        ),
        StorageCategory(
            id = "cache_media",
            title = R.string.storage_cache_media_title,
            subtitle = R.string.storage_cache_media_subtitle,
            icon = SymbolIcons.AnimatedImages,
            accent = Color(0xFF00ACC1),
            group = StorageGroup.CACHE,
            pathsOf = { listOf(File(it.cache, "media")) },
        ),
        StorageCategory(
            id = "cache_llm",
            title = R.string.storage_cache_llm_title,
            subtitle = R.string.storage_cache_llm_subtitle,
            icon = Icons.Outlined.Psychology,
            accent = Color(0xFF9575CD),
            group = StorageGroup.CACHE,
            pathsOf = { listOf(LocalLlmStore.engineCacheDir(it.cache)) },
            itemsOf = { env -> childrenOf(LocalLlmStore.engineCacheDir(env.roots.cache)).map { engineCacheItem(env, it) } },
        ),
        StorageCategory(
            id = "cache_addons",
            title = R.string.storage_cache_addons_title,
            subtitle = R.string.storage_cache_addons_subtitle,
            icon = Icons.Outlined.CloudDownload,
            accent = Color(0xFF7986CB),
            group = StorageGroup.CACHE,
            pathsOf = {
                listOf(File(it.cache, "addon_downloads"), File(it.cache, "addon_previews"))
            },
        ),
        StorageCategory(
            id = "cache_temp",
            title = R.string.storage_cache_temp_title,
            subtitle = R.string.storage_cache_temp_subtitle,
            icon = Icons.Outlined.Cached,
            accent = Color(0xFF90A4AE),
            group = StorageGroup.CACHE,
            pathsOf = ::tempPaths,
            // One row per directory: what is inside one is a pack's pieces or
            // a share's copies, named by nothing but a number.
            itemsOf = { env -> tempPaths(env.roots).filter(File::exists).map { env.namedItem(it) } },
        ),
        StorageCategory(
            id = OTHER_CACHE,
            title = R.string.storage_other_cache_title,
            subtitle = R.string.storage_other_cache_subtitle,
            icon = Icons.Outlined.MoreHoriz,
            accent = Color(0xFFB0BEC5),
            group = StorageGroup.CACHE,
            synthetic = true,
            itemsOf = { env -> unclaimedItems(env, cacheSide = true) },
        ),
    )

    /**
     * One of LiteRT-LM's files, named for its model and what it holds:
     * `gemma-4-E2B-it.litertlm_<mtime>_<bytes>_mldrift_program_cache.bin` is
     * "gemma-4-E2B-it: GPU programs".
     */
    private fun engineCacheItem(env: StorageEnv, file: File): StorageItem {
        val name = file.name
        val kind = when {
            "mldrift_program" in name -> R.string.storage_file_llm_gpu_programs_label
            "mldrift_weight" in name -> R.string.storage_file_llm_gpu_weights_label
            "xnnpack" in name -> R.string.storage_file_llm_cpu_weights_label
            else -> 0
        }
        val model = name.substringBefore(".litertlm").substringBefore(".task")
        return StorageItem(
            id = file.absolutePath,
            label = if (kind == 0) name else env.context.getString(kind, model),
            detail = name.takeIf { kind != 0 },
            bytes = diskUsage(file, env.roots.blockSize),
            files = listOf(file),
        )
    }

    /**
     * The app's own scratch directories in the cache root, each refilled the
     * next time its screen needs it, and the loose files sitting straight in
     * the root: shared log exports, manifests, half-imported word lists.
     * Naming those one prefix at a time would go stale the first time one is
     * added.
     */
    private fun tempPaths(roots: StorageRoots): List<File> = listOf(
        "keysounds", "diagnostics", "layout-previews", "rboard", "gboard_preview",
        "telegram_stickers", "signal_stickers", "notes", "send", "remote-restore",
        "keyman-rules", "import_preview", "link-import", "offline_import", "emotic-import",
        "upscale", "imagegen",
    ).map { File(roots.cache, it) } + childrenOf(roots.cache).filter { it.isFile }

    // ---- the rest ----

    private fun rest() = listOf(
        StorageCategory(
            id = "logs",
            title = R.string.storage_logs_title,
            subtitle = R.string.storage_logs_subtitle,
            icon = Icons.AutoMirrored.Outlined.Article,
            accent = Color(0xFF607D8B),
            group = StorageGroup.SYSTEM,
            manageRoute = "debug_log",
            // Device-protected, so a crash during a locked boot still gets
            // somewhere to write itself down.
            pathsOf = { listOf(File(it.deFiles, "debug")) },
            clearOf = { DebugLog.clearCrashes() },
        ),
        StorageCategory(
            id = UPDATES,
            title = R.string.storage_updates_title,
            subtitle = R.string.storage_updates_subtitle,
            icon = Icons.Outlined.SystemUpdate,
            accent = Color(0xFF4DB6AC),
            group = StorageGroup.SYSTEM,
            // Only ever holds a downloaded update waiting to be installed, and
            // the updater deletes it once Android has taken it. Deleting it by
            // hand costs the download again and nothing else, which is why
            // this row exists rather than the file being invisible.
            pathsOf = { listOf(File(it.noBackupFiles, "updates")) },
        ),
        StorageCategory(
            id = OTHER_DATA,
            title = R.string.storage_other_data_title,
            subtitle = R.string.storage_other_data_subtitle,
            icon = Icons.Outlined.MoreHoriz,
            accent = Color(0xFF90A4AE),
            group = StorageGroup.SYSTEM,
            synthetic = true,
            itemsOf = { env -> unclaimedItems(env, cacheSide = false) },
        ),
    )

    // ---- shared helpers ----

    private fun settingsPaths(roots: StorageRoots): List<File> = listOf(
        File(roots.files, "datastore"),
        File(roots.dePrefs, "${LockedSettings.FILE_NAME}.xml"),
    )

    /** Sub-directories named by a language id, labelled with the language's name. */
    private fun languageItems(env: StorageEnv, root: File): List<StorageItem> =
        childrenOf(root).map { dir ->
            StorageItem(
                id = dir.absolutePath,
                label = LanguageRegistry.byId(dir.name).displayName,
                detail = dir.name,
                bytes = diskUsage(dir, env.roots.blockSize),
                files = listOf(dir),
                directory = true,
            )
        }

    /**
     * Re-reads what is on disk into the two download managers, so their state
     * flows stop advertising a word list that is no longer there. The keyboard
     * itself picks the change up on the next field focus, guarded by
     * `DictionaryStore.stateToken`.
     */
    private fun refreshWordlists(env: StorageEnv) {
        WordlistDownloadManager.refresh(env.roots.files)
        CjkDictDownloadManager.refresh(env.roots.files)
        RimeRuntime.recheck(env.context)
        MozcRuntime.recheck(env.context)
    }

    /**
     * Everything under the app's data directories that no category above
     * claims. This is what a residual row's size is made of, so listing it
     * turns an unexplained number into something the user can look at.
     *
     * Split down the same line the system splits its own figures: the cache
     * directories on one side, everything else on the other. Reporting a
     * directory whose bytes the system counts as cache under "Other data"
     * would be a second, quieter version of the problem this screen exists
     * to solve.
     */
    private fun unclaimedItems(env: StorageEnv, cacheSide: Boolean): List<StorageItem> {
        val roots = env.roots
        val claimed = all.filterNot { it.synthetic }
            .flatMap { it.paths(roots) }
            .mapTo(HashSet()) { it.absolutePath }
        val cacheRoots = listOf(roots.cache, roots.codeCache, roots.deCache)
        val candidates = if (cacheSide) {
            cacheRoots.flatMap { childrenOf(it) }
        } else {
            val cachePaths = cacheRoots.mapTo(HashSet()) { it.absolutePath }
            listOf(roots.dataDir, roots.deDataDir)
                .distinctBy { it.absolutePath }
                .flatMap { childrenOf(it) }
                .filterNot { it.absolutePath in cachePaths }
                // files/ is nearly all claimed already, so descend one more
                // level there rather than reporting the whole tree as one row.
                .flatMap { top -> if (top.name == "files") childrenOf(top) else listOf(top) }
        }
        return candidates
            .flatMap { unclaimedUnder(it, claimed) }
            .map { env.namedItem(it, detail = residualDetail(env, it), deletable = false) }
    }

    /**
     * [file] when no category counts it, or the parts of it none does when a
     * category counts something inside it: `files/ocr` holds two packs that
     * the text scanner's row already counts, and listing the directory whole
     * would count them twice under a name that says nothing.
     */
    private fun unclaimedUnder(file: File, claimed: Set<String>): List<File> {
        val path = file.absolutePath
        if (path in claimed) return emptyList()
        val prefix = path + File.separator
        if (claimed.none { it.startsWith(prefix) }) return listOf(file)
        return childrenOf(file).flatMap { unclaimedUnder(it, claimed) }
    }

    /**
     * What a residual row says under its name.
     *
     * The bare file name is not enough here, and only here: the app's storage
     * comes in two halves, and both hold a `shared_prefs`, a `cache` and a
     * `code_cache`. Named from this list they would be the same row printed
     * twice, so the device-protected half says so.
     */
    private fun residualDetail(env: StorageEnv, file: File): String {
        val name = file.name
        val protected = file.absolutePath.startsWith(env.roots.deDataDir.absolutePath) &&
            env.roots.deDataDir.absolutePath != env.roots.dataDir.absolutePath
        return if (protected) {
            env.context.getString(R.string.storage_file_direct_boot_detail, name)
        } else {
            name
        }
    }
}
