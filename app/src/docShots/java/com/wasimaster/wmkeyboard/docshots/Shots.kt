package com.wasimaster.wmkeyboard.docshots

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AutoThemeTrigger
import com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.PowerSavingTrigger
import com.wasimaster.wmkeyboard.core.settings.ScreenReaderMode

/*
 * Every settings-app shot the docs carry, by manifest id. `setting` names the
 * row or group a shot is about by its title's resource name; it is scrolled
 * into view and ringed. Shots that show one screen for two pages are written
 * once and reused under the second id with [same].
 */

private fun same(id: String, of: Shot) = of.copy(id = id)

private val TalkBackGestures: suspend Seed.() -> Unit = { repo.setScreenReaderMode(ScreenReaderMode.PASSTHROUGH) }

private val dictionaryWords: suspend Seed.() -> Unit = {
    lexicon("Wasi" to 200, "Dhaka" to 200, "tomorrow" to 41, "keyboard" to 27, "biryani" to 12, "lol" to 6)
}

// ---------------------------------------------------------------- accessibility
private val visionGroup = Shot("accessibility/vision-group", "accessibility", setting = "accessibility_vision_title")
private val touchGroup = Shot("accessibility/touch-group", "accessibility", setting = "accessibility_touch_title")
private val screenReaderGroup = Shot(
    "accessibility/gestures-service-required", "accessibility",
    setting = "accessibility_passthrough_service_title_required", seed = TalkBackGestures,
)

// ---------------------------------------------------------------- typing
private val keypressTop = Shot("typing/keypress-settings-overview", "keypress/haptics", setting = "keypress_haptics_group_title")
private val hapticCustom = Shot(
    "typing/haptic-style-custom-sliders", "keypress/haptics", setting = "keypress_haptic_strength_title",
    seed = { repo.setHapticStyle(HapticStyle.CUSTOM) },
)
private val personalDictionary = Shot("smart/personal-dictionary-list", "dictionary", seed = dictionaryWords)
private val privacyGroups = Shot("privacy/privacy-screen-groups", "privacy")
private val clearLearned = Shot("privacy/clear-learned-words-button", "privacy", setting = "privacy_data_group_title")
private val hwShortcuts = Shot("typing/hardware-shortcuts-list", "hwshortcuts")
private val grammar = Shot("smart/grammar-settings", "tool/GRAMMAR")
private val autocorrect = Shot("smart/autocorrect-options", "typing/corrections", setting = "typing_autocorrect_confidence_title")
private val suggestions = Shot("smart/suggestions-settings-group", "typing/suggestions")
private val smartChips = Shot("smart/options", "typing/chips", setting = "typing_smart_chips_title")
private val blacklist = Shot("smart/blacklist-editor", "blacklist", seed = { blacklist("idk", "whatever", "ugh") })
private val gesturesGroup = Shot("typing/glide-settings-gestures", "typing/gestures", setting = "typing_glide_typing_title")
private val sizeGroup = Shot("typing/size-position-settings-overview", "layout/size", setting = "layout_size_position_title")
private val perScreen = Shot(
    "typing/size-position-per-screen", "layout/size", setting = "layout_per_screen_title",
    steps = { tap("Landscape") },
)

// ---------------------------------------------------------------- languages
private val languagesTop = Shot("languages/list-overview", "languages", setting = "langemoji_lang_your_languages_title")
private val addLanguage = Shot("languages/add-language-search", "add_language", steps = { type("ban") })
private val bengali = Shot("languages/language-detail-screen", "language/bn", setting = "languages_layouts_title")
private val systemSwitcher = Shot(
    "languages/system-switcher-toggle", "languages", setting = "langemoji_lang_system_switcher_title",
    seed = { repo.setOsLanguageSwitcher(true) },
)
private val keyLayouts = Shot("languages/key-layouts-gallery", "keymaps")
private val layoutEditor = Shot("languages/layout-editor-grid", "keymap_edit/builtin_qwerty", steps = { tap("q") })
private val keyEditSheet = Shot("languages/key-edit-sheet", "keymap_edit/builtin_qwerty", steps = { tap("e"); tap("e") })
private val layoutJson = Shot("languages/layout-json-editor", "keymap_json/builtin_qwerty")
private val globeToggle = Shot("languages/globe-key-toggle", "layout", setting = "layout_globe_emoji_title")

// ---------------------------------------------------------------- emoji
private val emojiTop = Shot("reference/settings/emoji-overview", "emoji", setting = "langemoji_emoji_access_title")
private val emojiStyle = Shot("emoji/settings-overview", "emoji", setting = "langemoji_emoji_style_title")
private val emojiKeywords = Shot("emoji/keyword-packs", "emojikeywords")

// ---------------------------------------------------------------- tools
private val toolsList = Shot("reference/settings/tools-list", "tools", setting = "tools_colored_icons_title")
private val clipboardSettings = Shot("tools/clipboard-settings", "clipboard", setting = "clipboard_history_group")
private val passwordsGroup = Shot("privacy/clipboard-settings-passwords", "clipboard", setting = "clipboard_sensitive_group")
private val aiProviders = Shot("tools/ai-settings-providers", "tool/AI", setting = "toolai_ai_providers_title")

// ---------------------------------------------------------------- themes
private val themesTop = Shot("reference/settings/themes-gallery-overview", "themes", setting = "theme_mode_section_title")
private val themeEditor = Shot("themes/editor-live-preview", "themes", steps = { tapIcon("Create theme") })

// ---------------------------------------------------------------- backup
private val backupScreen = Shot("privacy/backup-restore-screen", "backup", setting = "backup_files_title")

// ---------------------------------------------------------------- about
private val diagnostics = Shot("development/diagnostics-screen", "debug_log", setting = "shell_debug_log_report_title")

private const val SAMPLE_REPO = "https://raw.githubusercontent.com/wasi-master/wmkeyboard-addon-repository/HEAD/wmkeyboard-repo.json"

/** An addon's own page in the sample repository, which the run fetches from GitHub. */
private fun addonDetail(id: String, addonId: String, setting: String? = null, steps: Steps.() -> Unit = {}) = Shot(
    id,
    "addon/" + java.net.URLEncoder.encode(SAMPLE_REPO, "UTF-8") + "/" + java.net.URLEncoder.encode(addonId, "UTF-8"),
    setting = setting,
    steps = steps,
)

private val importTheme = Shot(
    "reference/file-import-dialog", "",
    launch = { openFile(file("Aurora.wmtheme.json", java.io.File("../docs/src/assets/themes/aurora.wmtheme.json").readText())) },
)

private val backupImport = Shot(
    "privacy/backup-import-confirm", "",
    launch = {
        theme("aurora")
        theme("midnight")
        blacklist("idk", "whatever")
        val text = repo.exportConfig(
            setOf(
                com.wasimaster.wmkeyboard.core.settings.ConfigBackup.Section.SETTINGS,
                com.wasimaster.wmkeyboard.core.settings.ConfigBackup.Section.THEMES,
            ),
            includeSecrets = false,
            appVersion = 23,
            appVersionName = "0.5.11",
        )
        // Imported into a fresh install, not back into the one that wrote it.
        repo.clearAllPreferences()
        repo.setOnboardingDone(true)
        openFile(file("wmkeyboard-backup.wmconfig.json", text))
    },
)

private val themeDelete = Shot(
    "reference/settings/themes-delete-confirm", "themes",
    seed = { theme("aurora") },
    steps = { tapIcon("Delete Aurora") },
)

val SHOTS: List<Shot> = listOf(
    // accessibility
    visionGroup,
    same("reference/settings/accessibility-vision-group", visionGroup),
    Shot(
        "accessibility/screen-reader-modes", "accessibility", seed = TalkBackGestures,
        steps = { tap("TalkBack support") },
    ),
    screenReaderGroup,
    same("reference/settings/accessibility-screen-reader-group", screenReaderGroup.copy(setting = "accessibility_screen_reader_title")),
    touchGroup,
    same("reference/settings/accessibility-touch-group", touchGroup),
    Shot(
        "reference/settings/accessibility-color-vision-picker", "accessibility",
        seed = { repo.setColorVisionFilter(ColorVisionFilter.DEUTERANOPIA) },
        steps = { tap("Colour vision") },
    ),

    // addons (the seeded sample repository is fetched from GitHub)
    Shot("addons/addons-home", "addons"),
    same("reference/settings/addons-home", Shot("addons/addons-home", "addons")),
    // addons/add-repository-dialog stays a device shot: its focused field
    // never lets Compose idle under Robolectric, typed into or pre-filled.
    Shot("addons/repo-catalogue-grid", "addons", steps = { tap("WM Keyboard Official Addons") }),

    // tools
    aiProviders,
    Shot("tools/ai-settings-local-catalog", "tool/AI", seed = { repo.setAiProvider(AiProvider.ON_DEVICE) }),
    Shot(
        "privacy/ai-ollama-plain-http", "tool/AI", setting = "toolai_ai_ollama_group_title",
        seed = { repo.setAiProvider(AiProvider.OLLAMA) },
    ),
    Shot("tools/calendar-options", "tool/CALENDAR", setting = "tooldetail_calendar_hijri_title"),
    Shot("tools/camera-options", "tool/CAMERA", setting = "tooldetail_camera_feedback_group"),
    clipboardSettings,
    passwordsGroup,
    // A bare section header, not a group: scrolled to, not ringed.
    Shot("tools/handwriting-model-manager", "tool/HANDWRITING", steps = { scrollTo("Recognition models") }),
    Shot("tools/whisper-engine-choice", "voice", setting = "voice_engine_title"),
    Shot("tools/snippets-settings", "expander", steps = { tap("Template variables"); scrollTo("Template variables") }),
    Shot("tools/snippets-add-dialog", "expander", steps = {
        tapIcon("Add snippet"); type("On my way", 0); type("On my way, be there by {time}!", 1); type("omw", 2)
    }),
    Shot("tools/instruments-settings-utilities-group", "tools", setting = "tools_group_utilities_title"),
    Shot("tools/weather-options-location", "tool/WEATHER"),
    Shot("emoji/sticker-pack-manage", "sticker_packs"),
    Shot("reference/settings/sticker-packs-list", "sticker_packs"),
    toolsList,
    same("privacy/tools-enabled-switches", toolsList.copy(setting = null)),
    // Recommended / Everything now lives only in the setup wizard; the page
    // gets the Tools screen with every tool on.
    Shot("start/migrating-tools-recommended", "tools"),
    Shot("reference/settings/tool-detail-top", "tool/CALCULATOR", setting = "tooldetail_options_group"),
    Shot("reference/settings/plugins-screen", "plugins", setting = "plugins_allow_title"),
    Shot(
        "typing/power-saving-settings-triggers", "tool/POWER_SAVING", setting = "tooldetail_power_group",
        seed = { repo.setPowerSavingTrigger(PowerSavingTrigger.EITHER) },
    ),
    Shot("typing/power-saving-what-to-drop", "tool/POWER_SAVING", setting = "tooldetail_power_drop_haptics_title"),

    // emoji
    emojiStyle,
    same("reference/settings/emoji-style-group", emojiStyle),
    emojiTop,
    Shot("reference/settings/emoji-panel-group", "emoji/panel", setting = "langemoji_emoji_panel_title"),
    Shot(
        "reference/settings/emoji-row-group", "emoji", setting = "langemoji_emoji_row_title",
        seed = { repo.setEmojiBarMode(EmojiBarMode.ALWAYS) },
    ),
    emojiKeywords,
    same("reference/settings/emoji-keywords-screen", emojiKeywords),
    same("reference/settings/dictionary-emoji-keywords", emojiKeywords),

    // start
    Shot("start/editions-about-version", "about", setting = "about_version_title"),
    Shot("start/faq-privacy-summary", "privacy", setting = "privacy_data_group_title"),
    same("start/faq-personal-dictionary", personalDictionary),
    same("start/migrating-personal-dictionary", personalDictionary),
    Shot("start/setup-card-not-enabled", "home", steps = { ring("Setup") }),
    Shot("tour/settings-entry", "home"),

    // languages
    languagesTop,
    // Switch order is the pencil on the Your languages heading now: reorder mode.
    same("languages/languages-screen-order", languagesTop.copy(steps = { tapIcon("Switch order") })),
    same("reference/settings/languages-top", languagesTop.copy(setting = null)),
    addLanguage,
    same("reference/settings/add-language-screen", Shot("x", "add_language")),
    Shot("languages/notation-add-language-search", "add_language", steps = { type("morse") }),
    bengali,
    same("languages/bengali-language-screen", bengali),
    same("reference/settings/language-detail-bengali", bengali.copy(setting = null)),
    Shot(
        "languages/list-per-app-and-switcher", "languages", setting = "langemoji_lang_per_app_title",
        seed = { repo.setOsLanguageSwitcher(true) },
    ),
    systemSwitcher,
    // Per language now, in its Clusters group.
    Shot("languages/conjunct-backspace-toggle", "language/bn", setting = "languages_conjunct_backspace_title"),
    same("reference/settings/languages-bottom", systemSwitcher),
    globeToggle,
    same("reference/settings/layout-bottom-row-keys", globeToggle.copy(setting = "layout_comma_emoji_title")),
    keyLayouts,
    same("reference/settings/key-layouts-list", keyLayouts),
    layoutEditor,
    same("reference/settings/layout-editor-grid", layoutEditor),
    keyEditSheet,
    same("reference/settings/key-edit-sheet", keyEditSheet),
    layoutJson,
    same("reference/settings/layout-json", layoutJson),
    Shot("languages/dictionary-download-row", "language/fr", setting = "languages_dictionaries_title", seed = {
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "builtin_french"))
    }),
    Shot("reference/troubleshooting-empty-dictionary-state", "language/de", setting = "languages_dictionaries_title", seed = {
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "builtin_qwertz"))
    }),
    Shot("languages/fancy-text-layout-toggles", "language/fancy", setting = "languages_layouts_title"),
    Shot("reference/settings/cjk-options-chinese", "language/zh", setting = "languages_cjk_traditional_title", seed = {
        repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "asset_zh_pinyin")); repo.setCjkTraditionalOutput(true)
    }),

    // privacy
    Shot("privacy/on-device-learning-toggles", "privacy", setting = "privacy_learn_typing_title"),
    clearLearned,
    same("smart/personal-dictionary-clear", clearLearned),
    same("reference/settings/privacy-clear-learned-words", clearLearned),
    privacyGroups,
    same("reference/settings/privacy-screen-groups", privacyGroups),
    Shot("reference/settings/privacy-backup-info-dialog", "privacy", steps = { tapIcon("Back up with Android", substring = true) }),
    Shot("privacy/smart-chips-currency-toggle", "typing/chips", setting = "typing_smart_currency_title"),

    // typing
    keypressTop,
    same("reference/settings/keypress-overview", keypressTop),
    hapticCustom,
    same("reference/settings/keypress-haptic-custom-sliders", hapticCustom),
    Shot(
        "typing/keysound-style-chips", "keypress/haptics", setting = "hardware_sound_group_title",
        seed = { repo.setKeySound(true) },
    ),
    Shot("reference/settings/keypress-popup-timing", "keypress/popup", setting = "keypress_popup_font_size_title"),
    Shot("reference/settings/keypress-long-press-shortcuts", "keypress/shortcuts", setting = "keypress_shortcuts_group_title"),
    gesturesGroup,
    same("reference/gestures-typing-screen", gesturesGroup.copy(setting = "typing_space_short_swipe_title")),
    same("reference/settings/typing-gestures-group", gesturesGroup.copy(setting = "typing_space_short_swipe_title")),
    hwShortcuts,
    same("reference/settings/typing-hwshortcuts-screen", hwShortcuts),
    sizeGroup,
    perScreen,
    same("reference/settings/layout-per-screen-editor", perScreen),
    Shot(
        "reference/settings/layout-one-handed-split-floating", "layout/onehanded", setting = "layout_one_handed_title",
        seed = { repo.setOneHandedMode(OneHandedMode.RIGHT) },
    ),
    Shot("reference/settings/layout-overview", "layout", setting = "layout_number_row_shift_symbols_title"),
    Shot("reference/settings/typing-overview", "typing"),
    same("reference/settings/typing-suggestions-group", suggestions),
    same("reference/settings/typing-smart-chips-group", smartChips),
    Shot(
        "reference/settings/typing-volume-keys-group", "typing", setting = "typing_group_volume_title",
        seed = { repo.setVolumeCursor(true) },
    ),
    Shot("reference/settings/typing-physical-keyboard-group", "typing/hardware", setting = "typing_hw_shortcuts_title"),

    // smart
    autocorrect,
    blacklist,
    same("reference/settings/dictionary-blacklist", blacklist),
    Shot("smart/blacklist-add-dialog", "blacklist", steps = { tapIcon("Add word"); type("whatever") }),
    smartChips,
    grammar,
    Shot("smart/spell-checker-options", "tool/GRAMMAR", setting = "tooldetail_grammar_system_group"),
    personalDictionary,
    same("reference/settings/dictionary-personal", personalDictionary),
    Shot("smart/personal-dictionary-add", "dictionary", seed = dictionaryWords, steps = { tapIcon("Add word"); type("Rahim") }),
    suggestions,
    Shot("reference/settings/dictionary-custom", "customdictionaries"),

    // backup
    backupScreen,
    same("reference/settings/backup-screen", backupScreen),
    Shot("smart/personal-dictionary-backup-toggle", "backup/contents", setting = "backup_section_dictionary_label"),

    // about
    Shot("reference/settings/about-screen", "about"),
    Shot("reference/settings/licenses-screen", "licenses"),
    diagnostics,
    same("reference/troubleshooting-diagnostics-screen", diagnostics),
    same("reference/settings/diagnostics-screen", diagnostics.copy(setting = "shell_debug_log_app_log_title")),
    Shot("reference/settings/home-screen", "home"),
    Shot("reference/settings/search-results", "search", steps = { type("haptic") }),

    // appearance
    Shot("reference/settings/appearance-overview", "appearance", setting = "appearance_style_section_title"),
    Shot("reference/settings/appearance-keys", "appearance/keys", setting = "appearance_keys_section_title"),
    Shot("reference/settings/appearance-toolbar", "appearance/toolbar", steps = { tap("Show the toolbar") }),
    Shot("reference/settings/fonts-english-section", "fonts"),
    same("themes/font-picker-english", Shot("x", "fonts")),
    Shot("reference/settings/icons-overview", "icons", setting = "plugins_icons_pack_title"),
    same("themes/icons-screen-overview", Shot("x", "icons")),

    // rows & modes
    Shot("reference/settings/rows-overview", "rows"),
    Shot("reference/settings/rows-symbol-sets", "rows/symbol", setting = "rows_symbol_sets_title"),
    Shot("reference/settings/modes-list", "modes"),
    Shot("reference/settings/mode-editor-top", "mode_edit/chat", setting = "modes_changes_group_title"),
    Shot("reference/settings/mode-editor-custom-tools", "mode_edit/chat", setting = "modes_pinned_tools_title"),
    Shot("reference/settings/mode-editor-activation", "mode_edit/chat", steps = { scrollTo("Add app", substring = true) }),

    // themes
    themesTop,
    Shot(
        "reference/settings/themes-auto-schedule", "themes", setting = "theme_auto_section_title",
        seed = { repo.setAutoThemeEnabled(true); repo.setAutoThemeTrigger(AutoThemeTrigger.SCHEDULE) },
    ),
    Shot(
        "themes/auto-theme-sun", "themes", setting = "theme_auto_section_title",
        seed = { repo.setAutoThemeEnabled(true); repo.setAutoThemeTrigger(AutoThemeTrigger.SUN) },
    ),
    // Built-in is a bare header over the grid, not a group.
    Shot("themes/gallery-grid", "themes", seed = { theme("aurora"); theme("midnight") }, steps = { scrollTo("Your themes") }),
    Shot("themes/gallery-export-import", "themes", steps = { ringNode { onAllNodesWithContentDescription("Create theme").onFirst() } }),
    themeEditor,
    same("reference/settings/themes-editor-overview", themeEditor),
    Shot("themes/editor-seed-swatches", "themes", steps = { tapIcon("Create theme"); scrollTo("Dark theme") }),
    Shot("themes/editor-gradient", "themes", steps = { tapIcon("Create theme"); tap("Gradient background") }),
    Shot("themes/editor-corners-animation", "themes", steps = { tapIcon("Create theme"); scrollTo("Corners") }),
    Shot("reference/settings-deep-link-destination", "themes"),

    // development / reference
    Shot("reference/settings/plugin-detail-screen", "plugins"),

    // ------------------------------------------------ with files and fixtures
    importTheme,
    same("reference/import-file-confirm-dialog", importTheme),
    Shot("reference/plugin-import-gate", "", launch = { openFile(resourceFile("addons/hello.wmplugin")) }),
    backupImport,
    same("reference/settings/backup-import-confirm", backupImport),
    Shot(
        "reference/addon-link-prefilled-repo", "", freezeClock = true,
        launch = { link("wmkeyboard://repo?url=github.com/wasi-master/wmkeyboard-addon-repository") },
    ),
    themeDelete,
    same("themes/editor-delete-confirm", themeDelete),
    Shot(
        "start/wizard-gestures-page", "", onboarding = true,
        // The first page waits for the keyboard to be on, and the gestures page
        // is only in the wizard for a balanced or power-user setup.
        seed = { keyboardReady(); repo.setPersonaDepth(com.wasimaster.wmkeyboard.core.settings.PersonaDepth.BALANCED) },
        launch = { link("wmkeyboard://settings") },
        steps = { tapUntil("Next", "Spacebar and keys") },
    ),
    Shot("start/first-launch", "", onboarding = true, launch = { link("wmkeyboard://settings") }),
    Shot("addons/import-from-link-confirm", "", launch = { sharedLink("https://github.com/wasi-master/wmkeyboard-addon-repository") }),
    Shot(
        "reference/settings/app-lock-screen", "applock",
        seed = {
            repo.setAppLockEnabled(true)
            repo.setAppLockTargets(com.wasimaster.wmkeyboard.app.lock.AppLockTargets.defaultIds)
            // Opened already, the way it is right after the fingerprint was accepted.
            com.wasimaster.wmkeyboard.app.lock.AppLockSession.grantConfig()
            com.wasimaster.wmkeyboard.app.lock.AppLockSession.grant(System.currentTimeMillis())
        },
    ),
    Shot("reference/settings/emoji-categories-screen", "emojicategories", steps = { tapIcon("Hide the Smileys tab", substring = true) }),
    Shot("reference/settings/permissions-screen", "permissions"),
    Shot(
        "reference/settings/phone-formats-screen", "phoneformats",
        seed = { repo.addClipboardPhoneFormat("+880 XXXX-XXXXXX"); repo.addClipboardPhoneFormat("(XXX) XXX-XXXX") },
    ),
    Shot("reference/settings/statistics-screen", "statistics", seed = { typingStats() }),
    Shot("privacy/network-activity-screen", "network_activity", seed = { networkLog() }),
    Shot(
        "themes/random-picker-light", "themes",
        seed = { repo.setAutoThemeEnabled(true) },
        steps = { tap("Light theme"); tap("Random"); tap("Forest"); tap("Sunset"); tap("Berry"); tap("Crimson") },
    ),
    Shot(
        "reference/settings/themes-random-dark", "themes",
        seed = { repo.setAutoThemeEnabled(true) },
        steps = { tap("Dark theme"); tap("Random"); tapIcon("Select every look in this family", substring = true) },
    ),
    Shot("languages/download-language-data-prompt", "add_language", steps = { type("french"); tap("Français", substring = true) }),
    Shot(
        "languages/dictionary-download-progress", "language/fr", setting = "languages_dictionaries_title",
        seed = {
            repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "builtin_french"))
            dictionaryDownloading("fr", 1_310_000L, 3_464_732L)
        },
    ),
    Shot(
        "themes/font-picker-script", "fonts/DEVANAGARI",
        seed = { repo.setEnabledLayoutIds(listOf("builtin_qwerty", "builtin_avro", "builtin_hindi")) },
    ),
    Shot(
        "tools/whisper-catalog", "voice", setting = "voice_engine_title",
        seed = { repo.setVoiceEngine("whisper") },
    ),
    addonDetail("addons/detail-page-licence", "arcade-cabinet", setting = "addon_licence_title"),
    addonDetail("reference/settings/addon-detail-page", "arcade-cabinet"),
    addonDetail("addons/plugin-trust-preview", "todo-list"),
    addonDetail("addons/apply-prompt", "sakura-breeze", steps = { tap("Install"); settle() }),
    addonDetail("themes/icons-addon-apply-prompt", "lucide", steps = { tap("Install"); settle() }),
    Shot(
        "reference/settings/icons-delete-confirm", "icons",
        seed = {
            val store = com.wasimaster.wmkeyboard.core.icons.IconPackStore.get(context)
            resourceFile("addons/lucide.wmicons").inputStream().use {
                com.wasimaster.wmkeyboard.core.icons.IconPackFile.import(it, store, "Lucide")
            }
        },
        steps = { tapIcon("Delete ", substring = true) },
    ),
    Shot(
        "reference/settings/key-layouts-delete-confirm", "keymaps",
        // An edited copy of a shipped layout is what carries a Reset button.
        seed = {
            val qwerty = requireNotNull(com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.byId("builtin_qwerty"))
            repo.upsertCustomLayout(qwerty)
        },
        steps = { tapIcon("Reset ", substring = true) },
    ),
)

/** The network log with a morning of requests in it, and one still under way. */
private fun Seed.networkLog() {
    val log = com.wasimaster.wmkeyboard.core.netlog.NetLog
    log.attach(context)
    log.clear()
    log.enabled = true
    listOf(
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.WEATHER, "api.open-meteo.com", "/v1/forecast"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.TRANSLATE, "translate.googleapis.com", "/translate_a/single"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.GIF, "api.klipy.com", "/api/v1/{key}/gifs/trending"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.GIF, "api.klipy.com", "/api/v1/{key}/gifs/search"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.DICTIONARY, "api.dictionaryapi.dev", "/api/v2/entries"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.CURRENCY, "open.er-api.com", "/v6/latest/USD"),
        Triple(com.wasimaster.wmkeyboard.core.netlog.NetSource.WEB_SEARCH, "api.search.brave.com", "/res/v1/web/search"),
    ).forEachIndexed { i, (source, host, route) ->
        log.callTo(source, "GET", "https", host, route = route).apply {
            status = 200
            sent(420L + i * 30)
            received(18_000L + i * 7_400)
            end()
        }
    }
    // Left open, so the live line reads as talking to it.
    log.callTo(com.wasimaster.wmkeyboard.core.netlog.NetSource.AI, "POST", "https", "api.anthropic.com", route = "/v1/messages")
}

/** A dictionary download partway through, held there for the shot. */
private fun Seed.dictionaryDownloading(id: String, bytes: Long, total: Long) {
    val manager = com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
    val type = manager::class.java
    // The manager keeps a download's status across a refresh only while its
    // job runs; a job that never finishes holds this one in place.
    type.getDeclaredField("activeId").apply { isAccessible = true }.set(manager, id)
    type.getDeclaredField("activeJob").apply { isAccessible = true }.set(manager, kotlinx.coroutines.Job())
    @Suppress("UNCHECKED_CAST")
    val states = type.getDeclaredField("_states").apply { isAccessible = true }.get(manager)
        as kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager.DownloadStatus>>
    states.value = states.value + (id to com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager.DownloadStatus.Downloading(bytes, total))
}
