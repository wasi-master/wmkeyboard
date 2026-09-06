package com.wasimaster.wmkeyboard.app.storage

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.vocab.VocabDownloadManager
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryStore
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.LockedSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.ime.ui.mediaImageLoader
import java.io.File

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
 * — without touching the files the store itself is tracking.
 */
private fun sweepLeftovers(dir: File) {
    childrenOf(dir)
        .filter { it.name.startsWith(".staging_") || it.name.endsWith(".part") }
        .forEach { it.deleteRecursively() }
}

internal object StorageCategories {

    const val APP_PACKAGE = "app_package"
    const val OTHER_CACHE = "other_cache"
    const val OTHER_DATA = "other_data"

    /**
     * The kept sticker sources, listed beside the packs in the sticker
     * category. Not a pack id — [StickerPackStore] mints those as
     * `pack_<millis>` — so deleting this row cannot delete a pack.
     */
    private const val STICKER_ORIGINALS_ITEM = "sticker_originals"

    fun byId(id: String): StorageCategory? = all.firstOrNull { it.id == id }

    /** In display order: the app, then downloads, looks, personal data, caches, the rest. */
    val all: List<StorageCategory> = buildList {
        add(appPackage())
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
            buildList {
                add(File(info.sourceDir) to 0)
                info.splitSourceDirs?.forEach { add(File(it) to 0) }
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
            }
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
            pathsOf = { listOf(File(it.files, "dict"), File(it.files, "cjk_dicts")) },
            itemsOf = { env ->
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
                item.files.forEach { it.deleteRecursively() }
                refreshWordlists(env)
            },
            clearOf = { env ->
                emptyOut(File(env.roots.files, "dict"))
                emptyOut(File(env.roots.files, "cjk_dicts"))
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
            pathsOf = { listOf(WhisperStore.rootDir(it.files)) },
            itemsOf = { env ->
                childrenOf(WhisperStore.rootDir(env.roots.files)).map { dir ->
                    StorageItem(
                        id = dir.name,
                        label = WhisperCatalog.models.firstOrNull { it.id == dir.name }?.displayName
                            ?: dir.name,
                        bytes = diskUsage(dir, env.roots.blockSize),
                        files = listOf(dir),
                    )
                }
            },
            deleteOne = { env, item ->
                // The selection goes first: the keyboard must never be pointed
                // at a model file that is about to disappear.
                env.repository.clearWhisperModelAssignments(item.id)
                item.files.forEach { it.deleteRecursively() }
                WhisperDownloadManager.refresh(env.roots.files)
            },
            clearOf = { env ->
                WhisperCatalog.models.forEach { env.repository.clearWhisperModelAssignments(it.id) }
                env.repository.setWhisperModelId("")
                emptyOut(WhisperStore.rootDir(env.roots.files))
                WhisperDownloadManager.refresh(env.roots.files)
            },
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
    )

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
            icon = Icons.Outlined.Mood,
            accent = Color(0xFFF06292),
            group = StorageGroup.LOOKS,
            danger = Danger.PERSONAL,
            manageRoute = "sticker_packs",
            pathsOf = { listOf(File(it.files, "stickers")) },
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
                if (keptBytes <= 0L) packs else packs + StorageItem(
                    id = STICKER_ORIGINALS_ITEM,
                    label = env.context.getString(R.string.storage_stickers_originals_label),
                    detail = env.context.getString(R.string.storage_stickers_originals_detail),
                    bytes = keptBytes,
                    files = listOfNotNull(originals),
                )
            },
            deleteOne = { env, item ->
                val store = StickerPackStore.get(env.context)
                if (item.id == STICKER_ORIGINALS_ITEM) store.clearOriginals()
                else store.deletePack(item.id)
            },
            clearOf = { env ->
                val store = StickerPackStore.get(env.context)
                store.packs().forEach { store.deletePack(it.id) }
                // Deleting the packs takes each sticker's kept picture with
                // it; this is for the ones a sticker deleted before this
                // version left behind.
                store.clearOriginals()
                sweepLeftovers(File(env.roots.files, "stickers"))
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
                    childrenOf(dir).map { file ->
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
            icon = Icons.Outlined.Gif,
            accent = Color(0xFF00ACC1),
            group = StorageGroup.CACHE,
            pathsOf = { listOf(File(it.cache, "media")) },
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
            // The loose files sitting straight in the cache root: shared log
            // exports, manifests, half-imported word lists. Naming them one
            // prefix at a time would go stale the first time one is added.
            pathsOf = { roots ->
                listOf(File(roots.cache, "keysounds"), File(roots.cache, "diagnostics")) +
                    childrenOf(roots.cache).filter { it.isFile }
            },
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
            .filterNot { it.absolutePath in claimed }
            .map { env.namedItem(it, detail = residualDetail(env, it), deletable = false) }
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
