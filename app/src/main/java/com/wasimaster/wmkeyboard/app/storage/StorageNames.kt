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
        "updates/releases.json" to R.string.storage_file_releases_label,
        "addons/repos.json" to R.string.storage_file_repos_label,
        "addons/installed.json" to R.string.storage_file_installed_label,
        "addons/.seeded" to R.string.storage_file_seeded_label,

        "learning/user_lexicon.json" to R.string.storage_file_lexicon_label,
        "learning/pending_learn.json" to R.string.storage_file_pending_learn_label,
        "learning/context_adapter.json" to R.string.storage_file_context_adapter_label,
        "learning/correction_stats.json" to R.string.storage_file_corrections_label,
        "learning/emoji_usage.json" to R.string.storage_file_emoji_usage_label,
        "learning/cjk_history.json" to R.string.storage_file_cjk_history_label,
        "learning/language_mix.json" to R.string.storage_file_language_mix_label,
        "learning/word_ranks.json" to R.string.storage_file_word_ranks_label,
        "learning/key_offsets.json" to R.string.storage_file_key_offsets_label,
        "learning/learned_corrections.json" to R.string.storage_file_learned_corrections_label,
        "learning/tap_offsets.json" to R.string.storage_file_tap_offsets_label,
        "learning/app_language_mix.json" to R.string.storage_file_app_language_mix_label,
        "learning/typed_emails.json" to R.string.storage_file_typed_emails_label,
        "learning/typed_numbers.json" to R.string.storage_file_typed_numbers_label,
        "learning/phonetic_script_choices.json" to R.string.storage_file_phonetic_script_choices_label,
        "learning/phonetic_picks.tsv" to R.string.storage_file_phonetic_picks_label,
        "learning/glide_outcomes.json" to R.string.storage_file_glide_outcomes_label,
        "learning/glide_shapes.bin" to R.string.storage_file_glide_shapes_label,
        // Up to 0.6.5, and until the first save after the update.
        "learning/glide_shapes.json" to R.string.storage_file_glide_shapes_label,

        "stats/typing_stats.json" to R.string.storage_file_typing_stats_label,
        "stats/typing_stats_devices.json" to R.string.storage_file_typing_stats_devices_label,
        "netlog/rows.jsonl" to R.string.storage_file_netlog_rows_label,
        "netlog/days.json" to R.string.storage_file_netlog_days_label,
        "clipboard/history.json" to R.string.storage_file_clip_history_label,
        "clipboard/images" to R.string.storage_file_clip_images_label,
        "snippets/snippets.json" to R.string.storage_file_snippets_label,
        "ai/history.json" to R.string.storage_file_ai_history_label,
        "ai/chats.json" to R.string.storage_file_ai_chats_label,

        "datastore/keyboard_settings.preferences_pb" to R.string.storage_file_settings_store_label,
        "debug/crashes.log" to R.string.storage_file_crash_log_label,
        "diagnostics/wmkeyboard-log.txt" to R.string.storage_file_debug_log_label,
        "theme_photos/pool.json" to R.string.storage_file_photo_pool_label,

        // The same name in the cache and in files/ means two things.
        "cache/notes" to R.string.storage_file_shared_notes_label,
        "files/notes" to R.string.storage_file_notes_label,
        "cache/send" to R.string.storage_file_send_queue_label,
        "files/send" to R.string.storage_file_send_history_label,
        "files/backup" to R.string.storage_file_backup_staging_label,
        "files/clock" to R.string.storage_file_clock_label,
        "files/kaomoji" to R.string.storage_file_kaomoji_label,
        "files/keyman" to R.string.storage_file_keyman_label,
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
        "layout-previews" to R.string.storage_file_layout_previews_label,
        "rboard" to R.string.storage_file_rboard_label,
        "gboard_preview" to R.string.storage_file_gboard_preview_label,
        "telegram_stickers" to R.string.storage_file_telegram_stickers_label,
        "signal_stickers" to R.string.storage_file_signal_stickers_label,
        "remote-restore" to R.string.storage_file_remote_restore_label,
        "keyman-rules" to R.string.storage_file_keyman_downloads_label,
        "import_preview" to R.string.storage_file_import_preview_label,
        "link-import" to R.string.storage_file_link_import_label,
        "offline_import" to R.string.storage_file_offline_import_label,
        "emotic-import" to R.string.storage_file_kaomoji_import_label,
        "upscale" to R.string.storage_file_upscale_label,
        "imagegen" to R.string.storage_file_imagegen_label,

        "homeassistant" to R.string.storage_file_smart_home_label,
        "kdeconnect" to R.string.storage_file_kdeconnect_label,
        "gif_collections" to R.string.storage_file_gif_collections_label,
        "handwriting_imports" to R.string.storage_file_ink_imports_label,
        "gallery_sent.txt" to R.string.storage_file_gallery_sent_label,
        "bergamot" to R.string.storage_file_bergamot_label,
        "xlit" to R.string.storage_file_xlit_label,
        "xlit-latin" to R.string.storage_file_xlit_latin_label,
        "backup_install_id" to R.string.storage_file_backup_id_label,
        "otp_capture.xml" to R.string.storage_file_otp_capture_label,
        "androidx.work.workdb" to R.string.storage_file_work_db_label,
        "androidx.work.workdb-wal" to R.string.storage_file_work_db_label,
        "androidx.work.workdb-shm" to R.string.storage_file_work_db_label,

        // Written by Google's libraries (MediaPipe, ML Kit, Play services'
        // flag and download services), not by this app.
        "drishti_asset_cache" to R.string.storage_file_mediapipe_label,
        "mlkit_digital_ink_recognition" to R.string.storage_file_ink_models_label,
        "com.google.mlkit.translate.models" to R.string.storage_file_mlkit_translate_label,
        "com.google.mlkit.RemoteConfig" to R.string.storage_file_gms_config_label,
        "com.google.mlkit.InstallationId" to R.string.storage_file_gms_config_label,
        "mdd_pds_config" to R.string.storage_file_gms_config_label,
        "phenotype_storage_info" to R.string.storage_file_gms_config_label,
        "gms_icing_mdd_garbage_file" to R.string.storage_file_gms_config_label,

        "no_backup" to R.string.storage_file_no_backup_label,
        "shared_prefs" to R.string.storage_file_shared_prefs_label,
        "databases" to R.string.storage_file_databases_label,
        "code_cache" to R.string.storage_file_code_cache_label,
        "oat" to R.string.storage_file_code_cache_label,
        "oat_primary" to R.string.storage_file_code_cache_label,
        "cache" to R.string.storage_file_cache_label,
        "files" to R.string.storage_file_app_files_label,
        "profileInstalled" to R.string.storage_file_profile_installed_label,
        "profileinstaller_profileWrittenFor_lastUpdateTime.dat" to R.string.storage_file_profile_installed_label,
        "lib" to R.string.storage_file_native_libs_label,
        "app_webview" to R.string.storage_file_webview_data_label,
    )

    /**
     * The sidecars every store in the app leaves behind, plus the pieces of the
     * installed package. None of them care which directory they are in.
     */
    private fun anywhereOf(name: String): Int? = when {
        name.endsWith(".part") || name.endsWith(".part.ranges") -> R.string.storage_item_partial_label
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
