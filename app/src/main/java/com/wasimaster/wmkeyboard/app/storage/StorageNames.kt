package com.wasimaster.wmkeyboard.app.storage

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import java.io.File

/**
 * Puts a name to a file.
 *
 * A category's detail screen used to list what is on disk exactly as it is on
 * disk — `correction_stats.json`, `com.android.skia.shaders_cache`,
 * `a3f19c_tex_press.img` — which answers the question "how big is it" and none
 * of the question the user came with, which is "what is it and can it go". So
 * every file the app can recognise gets words, and its name moves down into the
 * subtitle where it is still there for anyone who wants it.
 *
 * Recognition is deliberately shallow: a lookup on the file's own name, or on
 * its name under its parent directory's when the same name means two things in
 * two places (`ai/history.json` and `clipboard/history.json`). A file this list
 * has never heard of keeps its name as its headline, which is exactly what it
 * had before — an unnamed file costs nothing, and a *wrongly* named one is
 * worse than a raw one on a screen whose whole job is to be trusted.
 */
internal object StorageNames {

    /** The words for [file], or 0 when its own name is the best there is. */
    @StringRes
    fun titleOf(file: File): Int {
        val name = file.name
        val parent = file.parentFile?.name.orEmpty()
        byPath["$parent/$name"]?.let { return it }
        byName[name]?.let { return it }
        anywhereOf(name)?.let { return it }
        when (parent) {
            THEME_IMAGES -> themeImageOf(name)?.let { return it }
            THEME_PHOTOS -> themePhotoOf(name)?.let { return it }
            CAMERA -> if (isImage(name)) return R.string.storage_file_camera_shot_label
            DOC_SCAN -> if (isImage(name)) return R.string.storage_file_scan_label
        }
        return 0
    }

    /** Whether a thumbnail of [name] is worth drawing beside its row. */
    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    // ---- named under one particular parent ----

    /**
     * Files whose own name is ambiguous. `history.json` is the clip history in
     * one directory and the AI transcript in another, so both are keyed on the
     * directory too rather than one of them silently winning.
     */
    private val byPath: Map<String, Int> = mapOf(
        "vocab/progress.json" to R.string.storage_file_vocab_progress_label,
        "addons/repos.json" to R.string.storage_file_repos_label,
        "addons/installed.json" to R.string.storage_file_installed_label,
        "addons/.seeded" to R.string.storage_file_seeded_label,

        "learning/user_lexicon.json" to R.string.storage_file_lexicon_label,
        "learning/pending_learn.json" to R.string.storage_file_pending_learn_label,
        "learning/correction_stats.json" to R.string.storage_file_corrections_label,
        "learning/emoji_usage.json" to R.string.storage_file_emoji_usage_label,
        "learning/cjk_history.json" to R.string.storage_file_cjk_history_label,
        "learning/language_mix.json" to R.string.storage_file_language_mix_label,

        "stats/typing_stats.json" to R.string.storage_file_typing_stats_label,
        "clipboard/history.json" to R.string.storage_file_clip_history_label,
        "clipboard/images" to R.string.storage_file_clip_images_label,
        "snippets/snippets.json" to R.string.storage_file_snippets_label,
        "ai/history.json" to R.string.storage_file_ai_history_label,
        "ai/chats.json" to R.string.storage_file_ai_chats_label,

        "datastore/keyboard_settings.preferences_pb" to R.string.storage_file_settings_store_label,
        "debug/crashes.log" to R.string.storage_file_crash_log_label,
        "diagnostics/wmkeyboard-log.txt" to R.string.storage_file_debug_log_label,
        "theme_photos/pool.json" to R.string.storage_file_photo_pool_label,
    )

    // ---- named wherever they turn up ----

    private val byName: Map<String, Int> = mapOf(
        "locked_settings.xml" to R.string.storage_file_locked_settings_label,

        // The system's own, dropped into our cache directory by the graphics
        // driver. Nothing in this app writes them, and they come straight back.
        "com.android.skia.shaders_cache" to R.string.storage_file_skia_cache_label,
        "com.android.opengl.shaders_cache" to R.string.storage_file_gl_cache_label,
        "WebView" to R.string.storage_file_webview_cache_label,
        "org.chromium.android_webview" to R.string.storage_file_webview_cache_label,
        "litertlm" to R.string.storage_file_llm_cache_label,
        "keysounds" to R.string.storage_file_keysound_cache_label,
        "diagnostics" to R.string.storage_file_diagnostics_label,

        "no_backup" to R.string.storage_file_no_backup_label,
        "shared_prefs" to R.string.storage_file_shared_prefs_label,
        "databases" to R.string.storage_file_databases_label,
        "code_cache" to R.string.storage_file_code_cache_label,
        "oat" to R.string.storage_file_code_cache_label,
        "cache" to R.string.storage_file_cache_label,
        "files" to R.string.storage_file_app_files_label,
        "profileInstalled" to R.string.storage_file_profile_installed_label,
        "lib" to R.string.storage_file_native_libs_label,
        "app_webview" to R.string.storage_file_webview_data_label,
    )

    /**
     * The sidecars every store in the app leaves behind, plus the pieces of the
     * installed package. None of them care which directory they are in.
     */
    private fun anywhereOf(name: String): Int? = when {
        name.endsWith(".part") -> R.string.storage_item_partial_label
        name.startsWith(".staging_") -> R.string.storage_file_staging_label
        name.endsWith(".tmp") -> R.string.storage_file_temp_label
        name == ".version" -> R.string.storage_file_version_marker_label
        name == ".nomedia" -> R.string.storage_file_nomedia_label
        name.startsWith("split_") && name.endsWith(".apk") -> R.string.storage_file_apk_split_label
        name.endsWith(".apk") -> R.string.storage_file_apk_base_label
        else -> null
    }

    // ---- theme images ----

    /**
     * Which slot of a theme an image fills, read back out of the name the
     * editor gave it: `<theme>_tex_enter_<millis>.img` and its siblings.
     *
     * Longest tag first, because `_tex_press` contains `_tex`, and a pressed-key
     * texture labelled "Key texture" would send someone to delete the wrong one.
     */
    private val themeRoles: List<Pair<String, Int>> = listOf(
        "_tex_mod" to R.string.storage_file_texture_modifier_label,
        "_tex_enter" to R.string.storage_file_texture_enter_label,
        "_tex_space" to R.string.storage_file_texture_space_label,
        "_tex_press" to R.string.storage_file_texture_pressed_label,
        "_tex_popup" to R.string.storage_file_texture_popup_label,
        "_tex" to R.string.storage_file_texture_key_label,
        "_decal_" to R.string.storage_file_decal_label,
        "_fx_" to R.string.storage_file_effect_label,
        "_land" to R.string.storage_file_background_land_label,
    )

    private fun themeImageOf(name: String): Int? {
        if (!name.endsWith(IMG)) return null
        if (name.startsWith("crop_")) return R.string.storage_file_crop_label
        val base = name.removeSuffix(IMG)
        themeRoles.firstOrNull { base.contains(it.first) }?.let { return it.second }
        // Everything else in here is `<theme>_<millis>.img`, which is the
        // background — the one slot with no tag of its own.
        return R.string.storage_file_background_label
    }

    /** Where a saved photo came from, from the prefix `PhotoBackgroundManager` gave it. */
    private fun themePhotoOf(name: String): Int? = when {
        !name.endsWith(IMG) -> null
        name.startsWith("p_") -> R.string.storage_file_photo_stock_label
        name.startsWith("d_") -> R.string.storage_file_photo_device_label
        name.startsWith("c_") -> R.string.storage_file_photo_source_label
        else -> null
    }

    private const val IMG = ".img"
    private const val THEME_IMAGES = "theme_images"
    private const val THEME_PHOTOS = "theme_photos"
    private const val CAMERA = "camera"
    private const val DOC_SCAN = "docscan"

    /**
     * `.img` is this app's own: every image it saves for a theme wears it, so
     * that the media scanner and the gallery leave them alone. The rest are the
     * ordinary ones, for the camera, the scanner and imported packs.
     */
    private val IMAGE_EXTENSIONS = setOf(
        "img", "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif",
    )
}

/**
 * One row of a detail screen, built from a file: named by [StorageNames] where
 * it can be, and carrying its own name underneath either way.
 *
 * [detail] overrides the subtitle for a caller that has something better to say
 * than the bare name — the residual rows pass a path, because two directories
 * called `shared_prefs` are otherwise one row printed twice.
 */
internal fun StorageEnv.namedItem(
    file: File,
    id: String = file.absolutePath,
    detail: String = file.name,
    deletable: Boolean = true,
    files: List<File> = listOf(file),
): StorageItem {
    val title = StorageNames.titleOf(file)
    val named = title != 0
    return StorageItem(
        id = id,
        label = if (named) context.getString(title) else file.name,
        // A file this list cannot name keeps its name as the headline, so the
        // subtitle would only repeat it — unless the caller passed something
        // the name does not already say.
        detail = detail.takeIf { named || it != file.name },
        bytes = diskUsage(file, roots.blockSize),
        files = files,
        deletable = deletable,
        preview = file.takeIf { it.isFile && StorageNames.isImage(it.name) },
        directory = file.isDirectory,
    )
}
