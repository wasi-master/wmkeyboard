package com.wasimaster.wmkeyboard.docshots

import com.wasimaster.wmkeyboard.core.clock.ClockConfig
import com.wasimaster.wmkeyboard.core.clock.ClockPrefs
import com.wasimaster.wmkeyboard.core.clock.WorldZone
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.notes.NoteSort
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.settings.AppSortOrder
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.core.settings.LanguagePickerStyle
import com.wasimaster.wmkeyboard.core.settings.NextWordAmount
import com.wasimaster.wmkeyboard.core.settings.NextWordSuggestions
import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.settings.NotesPanelLayout
import com.wasimaster.wmkeyboard.core.settings.NotesPanelStart
import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.layout.SymbolsPage
import com.wasimaster.wmkeyboard.core.settings.TransliterationHintMode
import com.wasimaster.wmkeyboard.core.script.NumeralSystem
import com.wasimaster.wmkeyboard.core.theme.KeyMaterialSpec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.TouchBloomSpec
import com.wasimaster.wmkeyboard.core.todo.TodoStore
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.settings.QrEccLevel
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.ToolboxSort
import com.wasimaster.wmkeyboard.core.settings.BoardCorner
import com.wasimaster.wmkeyboard.core.settings.GlideCommitColor
import com.wasimaster.wmkeyboard.core.settings.GlideCommitColorScope
import com.wasimaster.wmkeyboard.core.settings.GlideDrawnLetters
import java.io.File

/*
 * Help pictures for tools that keep their state outside the settings store:
 * the clock (its own preferences), notes and the to-do list (their own JSON
 * files). Each seed writes that state first, the way the user's phone would
 * hold it, and the frames then differ in one setting only.
 */

private val cities = listOf(
    WorldZone("Europe/London", "London"),
    WorldZone("Asia/Tokyo", "Tokyo"),
    WorldZone("America/New_York", "New York"),
    WorldZone("Asia/Dhaka", "Dhaka"),
)

/**
 * The clock's whole config, written fresh: [ClockPrefs] caches it for the
 * life of the process, so a seed that only changed one field would inherit
 * whatever the test before it left.
 */
private fun Seed.clock(change: ClockConfig.() -> ClockConfig = { this }) {
    ClockPrefs.update(context) { ClockConfig(zones = cities, hour24 = false).change() }
}

private fun clockOn(tab: String = "clocks") = typed { panel(PanelMode.CLOCK) }.seededWith { clock { copy(lastTab = tab) } }

private fun clockCompare(
    key: String,
    tab: String = "clocks",
    labels: Pair<String, String> = "common_off" to "common_on",
    off: ClockConfig.() -> ClockConfig,
    on: ClockConfig.() -> ClockConfig,
) = compare(
    key, typed { panel(PanelMode.CLOCK) },
    off = { clock { copy(lastTab = tab).off() } },
    on = { clock { copy(lastTab = tab).on() } },
    crop = HelpCrop.KEYBOARD, labels = labels,
)

/** A few notes, one pinned, one with a checklist, so every layout has something to lay out. */
private fun Seed.notes() {
    val file = File(context.filesDir, NoteStore.FILE_PATH)
    file.delete()
    file.parentFile?.mkdirs()
    val store = NoteStore(file)
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    store.add("Milk\nEggs\nBread\n- [x] Coffee\n- [ ] Apples", title = "Shopping", now = now - 30 * hour)
    val trip = store.add("# Trip to Sylhet\n\n**Train** at 7:40 from Kamalapur.\n\n- Tea garden\n- Ratargul", title = "Weekend plan", now = now - 5 * hour)
    store.setPinned(trip.id, true)
    store.add("Ask about the 3 pm meeting moving to Thursday.", title = "Work", now = now - 2 * hour)
    store.add("The book Rafi mentioned: *A Fine Balance*.", title = "Books", now = now - 50 * hour)
    store.save()
}

private val notesPanel = typed { panel(PanelMode.NOTES) }.seededWith { notes() }

/** Five tasks, two of them ticked, one due. */
private fun Seed.todos() {
    val file = File(context.filesDir, TodoStore.FILE_PATH)
    file.delete()
    file.parentFile?.mkdirs()
    val store = TodoStore(file)
    val now = System.currentTimeMillis()
    store.add("Pay the electricity bill", due = now - 3_600_000L)
    store.add("Call the dentist")
    store.add("Book train tickets")?.let { store.setDone(it.id, true) }
    store.add("Return library books")?.let { store.setDone(it.id, true) }
    store.add("Buy a birthday present")
    store.save()
}

private val todoPanel = typed { panel(PanelMode.TODO) }.seededWith { todos() }

private val emojiPanel = typed { panel(PanelMode.EMOJI) }
private val kaomojiTab = docs("emoji/kaomoji-tab")
/** A word half typed, so the strip has suggestions on it. */
private val strip = typed { type("hel") }

/** A plain theme for the theme editor's pictures: each option is this with one thing changed. */
private val plainTheme = ThemeSpec(id = "help-look-2", name = "Help")

/** Keys showing [theme], stored as a custom theme and put on. */
private fun dressed(theme: ThemeSpec, base: KbShot = KbShot("")) = base.seededWith {
    repo.upsertCustomTheme(theme)
    repo.setKeyboardThemeId(theme.id)
}

private fun capWith(change: KeyMaterialSpec.() -> KeyMaterialSpec) = dressed(plainTheme.copy(keyMaterial = KeyMaterialSpec(kind = "CAP").change()))

private fun bloom(motion: String) = dressed(plainTheme.copy(touchBloom = TouchBloomSpec(motion = motion)), typed { press("g") })

/** Two frames of [key], labelled Off and On unless [labels] says otherwise. */
private fun pair(key: String, off: KbShot, on: KbShot, crop: HelpCrop = HelpCrop.KEYBOARD, labels: Pair<String, String> = "common_off" to "common_on", aboveDp: Int = 0) =
    HelpShot(key, HelpShot.COMPARE, listOf(HelpFrameSpec(labels.first, off), HelpFrameSpec(labels.second, on)), crop, aboveDp)

private val toolboxOpen = typed { panel(PanelMode.TOOLBOX) }

/** Half way through a glide of "hello": the finger still down, the trail and the word above it showing. */
private val gliding = typed { glide("hello", lift = false) }.seededWith { repo.setGestureTyping(true) }

/** A clipboard history with a pinned clip, a formatted one and their source apps, open. */
private fun Seed.richClips() {
    val file = File(context.filesDir, "clipboard/history.json")
    file.delete()
    file.parentFile?.mkdirs()
    val store = ClipboardStore(file)
    val now = System.currentTimeMillis()
    store.add("Flat 4B, 27 Road 11, Banani, Dhaka", sourceApp = "Messages", now = now - 300_000)?.let { store.setPinned(it.id, true) }
    store.addHtml("Agenda: budget, hiring, launch date", html = "<b>Agenda:</b> budget, hiring, <i>launch date</i>", sourceApp = "Docs", now = now - 200_000)
    store.add("https://wmkeyboard.app/tools/clipboard/", sourceApp = "Browser", now = now - 100_000)
    store.add("Pick up milk and eggs on the way home\nand bread if they have it", sourceApp = "Notes", now = now - 50_000)
    store.save()
}

private val richClipboard = typed { panel(PanelMode.CLIPBOARD) }.seededWith { richClips() }

private fun on(layoutId: String, steps: KbSteps.() -> Unit = {}) = KbShot("", seed = { layout(layoutId) }, steps = steps)
private val avroTyping = on("builtin_avro") { type("prshn") }
private val pinyin = docs("languages/cjk-pinyin-candidate-strip")

/** The gallery tool open on [photoLibrary]'s made-up photos. */
private val galleryPanel = typed { panel(PanelMode.GALLERY); pause(1500); settle() }.seededWith { photoLibrary() }

private fun galleryCompare(key: String, crop: HelpCrop = HelpCrop.KEYBOARD, labels: Pair<String, String> = "common_off" to "common_on", set: (com.wasimaster.wmkeyboard.core.settings.GallerySettings, Boolean) -> com.wasimaster.wmkeyboard.core.settings.GallerySettings) =
    compare(key, galleryPanel, off = { repo.updateGallery { set(it, false) } }, on = { repo.updateGallery { set(it, true) } }, crop = crop, labels = labels)

val HELP_SHOTS_2: List<HelpShot> = listOf(
    // ------------------------------------------------------------ clock
    gallery(
        "clock_hour_format_title",
        "clock_hour_format_12" to clockOn().seededWith { clock { copy(hour24 = false) } },
        "clock_hour_format_24" to clockOn().seededWith { clock { copy(hour24 = true) } },
    ),
    clockCompare("clock_show_seconds_title", off = { copy(showSeconds = false) }, on = { copy(showSeconds = true) }),
    clockCompare("clock_sort_zones_title", off = { copy(sortZonesByTime = false) }, on = { copy(sortZonesByTime = true) }),
    clockCompare("clock_pin_local_title", off = { copy(pinLocalZone = false) }, on = { copy(pinLocalZone = true) }),
    clockCompare("clock_pin_utc_title", off = { copy(pinUtc = false) }, on = { copy(pinUtc = true) }),
    clockCompare("clock_zones_grid_title", off = { copy(zonesGrid = false) }, on = { copy(zonesGrid = true) }),
    clockCompare(
        "clock_work_start_title", tab = "planner", labels = "=9:00" to "=7:00",
        off = { copy(workStartMinutes = 9 * 60) }, on = { copy(workStartMinutes = 7 * 60) },
    ),
    clockCompare(
        "clock_work_end_title", tab = "planner", labels = "=17:00" to "=20:00",
        off = { copy(workEndMinutes = 17 * 60) }, on = { copy(workEndMinutes = 20 * 60) },
    ),
    clockCompare(
        "clock_focus_title", tab = "timers", labels = "=25 min" to "=50 min",
        off = { copy(focusMinutes = 25) }, on = { copy(focusMinutes = 50) },
    ),

    // ------------------------------------------------------------ notes
    toggle("tooldetail_notes_markdown_title", notesPanel.copy(steps = { panel(PanelMode.NOTES); tap("Weekend plan") }), crop = HelpCrop.KEYBOARD) { setNotesRenderMarkdown(it) },
    toggle("tooldetail_notes_keep_toolbar_title", notesPanel, crop = HelpCrop.KEYBOARD) { setNotesKeepToolbar(it) },
    gallery(
        "tooldetail_notes_panel_layout_title",
        "tooldetail_notes_panel_layout_shelf_label" to notesPanel.seededWith { repo.setNotesPanelLayout(NotesPanelLayout.SHELF) },
        "tooldetail_notes_panel_layout_timeline_label" to notesPanel.seededWith { repo.setNotesPanelLayout(NotesPanelLayout.TIMELINE) },
    ),
    gallery(
        "tooldetail_notes_panel_start_title",
        "tooldetail_notes_panel_start_list_label" to notesPanel.seededWith { repo.setNotesPanelOpensOn(NotesPanelStart.LIST) },
        "tooldetail_notes_panel_start_last_label" to notesPanel.seededWith { repo.setNotesPanelOpensOn(NotesPanelStart.LAST_NOTE) },
    ),
    compare(
        "tooldetail_notes_sort_title", notesPanel,
        off = { repo.setNotesSort(NoteSort.UPDATED) }, on = { repo.setNotesSort(NoteSort.TITLE) },
        crop = HelpCrop.KEYBOARD, labels = "tooldetail_notes_sort_updated_label" to "tooldetail_notes_sort_title_label",
    ),
    toggle("tooldetail_notes_tap_inserts_title", notesPanel.copy(steps = { panel(PanelMode.NOTES); tap("Work") }), crop = HelpCrop.SCREEN) { setNotesTapInserts(it) },

    // ------------------------------------------------------------ to-do
    compare(
        "tooldetail_todo_hide_done_title", todoPanel,
        off = { repo.updateTodo { it.copy(hideDone = false) } }, on = { repo.updateTodo { it.copy(hideDone = true) } },
        crop = HelpCrop.KEYBOARD,
    ),
    compare(
        "tooldetail_todo_due_chip_title", typed { }.seededWith { todos() },
        off = { repo.updateTodo { it.copy(dueChip = false) } }, on = { repo.updateTodo { it.copy(dueChip = true) } },
    ),

    // ------------------------------------------------------------ emoji and kaomoji
    gallery(
        "langemoji_emoji_tab_mode_title",
        "langemoji_emoji_recent_label" to emojiPanel.seededWith { repo.setEmojiTabMode(EmojiTabMode.RECENTS) },
        "langemoji_emoji_most_used_label" to emojiPanel.seededWith { repo.setEmojiTabMode(EmojiTabMode.MOST_USED) },
        "langemoji_emoji_both_label" to emojiPanel.seededWith { repo.setEmojiTabMode(EmojiTabMode.BOTH) },
        "langemoji_emoji_tab_off_label" to emojiPanel.seededWith { repo.setEmojiTabMode(EmojiTabMode.OFF) },
    ),
    toggle("langemoji_kaomoji_suggest_title", typed { type("shrug") }) { setKaomojiSuggest(it) },

    // ------------------------------------------------------------ typing, on what the field and the strip show
    toggle("typing_auto_capitalize_words_title", typed { type("hello world") }.copy(seed = { repo.setAutoCapitalizeAnyField(true) }), crop = HelpCrop.TEXT) { setAutoCapitalizeWords(it) },
    toggle("typing_autocorrect_on_punctuation_title", typed { type("teh.") }, crop = HelpCrop.TEXT) { setAutocorrectOnPunctuation(it) },
    toggle("typing_autocorrect_on_enter_title", KbShot("", mode = "chat", steps = { type("teh\n") }), crop = HelpCrop.TEXT) { setAutocorrectOnEnter(it) },
    toggle("typing_smart_calc_title", typed { type("12*4") }) { setSmartCalc(it) },
    toggle("typing_smart_currency_title", docs("smart/currency-chip")) { setSmartCurrency(it) },
    gallery(
        "typing_smart_number_grouping_title",
        "typing_smart_number_grouping_western" to typed { type("1234567") }.seededWith { repo.setSmartChipNumberGrouping(NumberGrouping.WESTERN) },
        "typing_smart_number_grouping_south_asian" to typed { type("1234567") }.seededWith { repo.setSmartChipNumberGrouping(NumberGrouping.SOUTH_ASIAN) },
        crop = HelpCrop.DIFF,
    ),
    compare("typing_spacebar_text_label", KbShot(""), off = { }, on = { repo.setSpacebarLabel("WM Keyboard") }, crop = HelpCrop.KEYBOARD, labels = "common_default" to "=WM Keyboard"),
    toggle("typing_spacebar_live_wpm_title", typed { type("hello there how are you doing ") }, crop = HelpCrop.KEYBOARD) { setSpacebarLiveWpm(it) },
    compare("typing_rest_words_title", KbShot(""), off = { }, on = { repo.setRestWords("Hi\nThanks\nOn my way") }),
    compare("typing_punctuation_marks_title", typed { type("hello ") }, off = { }, on = { repo.setPunctuationChips("! ? … :)") }),
    toggle("typing_smart_hit_detection_title", strip, crop = HelpCrop.KEYBOARD) { setSmartHitDetection(it) },
    compare(
        "typing_autopilot_size_title", strip.seededWith { repo.setSmartHitDetection(true) },
        off = { repo.setAutopilotVisualScale(0.2f) }, on = { repo.setAutopilotVisualScale(1f) },
        crop = HelpCrop.KEYBOARD, labels = "=20%" to "=100%",
    ),
    compare(
        "typing_octopus_stack_title", strip.seededWith { repo.setOctopusEnabled(true) },
        off = { repo.setOctopusWordsPerKey(1) }, on = { repo.setOctopusWordsPerKey(3) },
        crop = HelpCrop.KEYBOARD, labels = "=1" to "=3",
    ),
    gallery(
        "typing_next_word_amount_title",
        "typing_next_word_amount_fewer" to typed { type("see you ") }.seededWith { repo.setNextWordAmount(NextWordAmount.FEWER) },
        "typing_next_word_amount_more" to typed { type("see you ") }.seededWith { repo.setNextWordAmount(NextWordAmount.MORE) },
        crop = HelpCrop.DIFF,
    ),
    toggle("typing_hw_modifier_words_title", docs("typing/hardware-tool-picker"), crop = HelpCrop.KEYBOARD) { setHwHintModifierWords(it) },
    toggle("typing_hw_dpad_keys_title", docs("typing/hardware-focus-ring"), crop = HelpCrop.KEYBOARD) { setHwDpadKeyNavigation(it) },
    // ------------------------------------------------------------ key popups, colours, the strip's buttons
    compare("keypress_popup_background_title", typed { press("g") }, off = { }, on = { repo.setKeyPopupBackgroundColor(0xFF1565C0) }, crop = HelpCrop.KEYBOARD, aboveDp = 72, labels = "common_default" to "common_custom"),
    compare("keypress_popup_text_color_title", typed { press("g") }, off = { }, on = { repo.setKeyPopupTextColor(0xFFFFB300) }, crop = HelpCrop.KEYBOARD, aboveDp = 72, labels = "common_default" to "common_custom"),
    compare("appearance_suggestion_primary_color_title", typed { type("teh") }, off = { }, on = { repo.setSuggestionPrimaryColor(0xFFE53935) }, labels = "common_default" to "common_custom"),
    toggle("keypress_symbols_numpad_title", typed { hold("?123") }, crop = HelpCrop.KEYBOARD) { setSymbolsLongPressNumpad(it) },
    toggle("keypress_popup_window_band_title", KbShot(""), crop = HelpCrop.SCREEN) { setKeyPopupWindowBand(it) },
    toggle("appearance_toolbar_show_strip_title", strip) { setToolbarShowStrip(it) },
    toggle("langemoji_strip_toolbox_title", strip) { setStripToolbox(it) },
    compare("langemoji_strip_tool_width_title", strip.seededWith { repo.setStripOwnSize(true) }, off = { repo.setStripToolWidthDp(36) }, on = { repo.setStripToolWidthDp(56) }, labels = "=36 dp" to "=56 dp"),
    compare("langemoji_strip_icon_size_title", strip.seededWith { repo.setStripOwnSize(true) }, off = { repo.setStripIconSizeDp(16) }, on = { repo.setStripIconSizeDp(28) }, labels = "=16 dp" to "=28 dp"),
    toggle("appearance_toolbar_fit_title", KbShot("")) { setToolbarGreedy(it) },
    compare("appearance_toolbox_page_size_title", toolboxOpen, off = { repo.setToolboxPageSize(8) }, on = { repo.setToolboxPageSize(16) }, crop = HelpCrop.KEYBOARD, labels = "=8" to "=16"),
    gallery(
        "layout_symbols_page_title",
        "layout_symbols_page_standard_label" to docs("typing/symbols-page-open").seededWith { repo.setSymbolsPage(SymbolsPage.STANDARD) },
        "layout_symbols_page_number_pad_label" to docs("typing/symbols-page-open").seededWith { repo.setSymbolsPage(SymbolsPage.NUMBER_PAD) },
    ),
    compare("layout_one_handed_width_title", docs("typing/size-position-one-handed"), off = { repo.setOneHandedWidthPercent(false, 70) }, on = { repo.setOneHandedWidthPercent(false, 90) }, crop = HelpCrop.KEYBOARD, labels = "=70%" to "=90%"),
    compare("layout_one_handed_height_title", docs("typing/size-position-one-handed"), off = { repo.setOneHandedHeightScale(false, 100) }, on = { repo.setOneHandedHeightScale(false, 80) }, crop = HelpCrop.KEYBOARD, labels = "=100%" to "=80%"),
    compare("layout_one_handed_side_title", docs("typing/size-position-one-handed"), off = { repo.setOneHandedSide(false, OneHandedSide.LEFT) }, on = { repo.setOneHandedSide(false, OneHandedSide.RIGHT) }, crop = HelpCrop.KEYBOARD, labels = "langemoji_kaomoji_rail_left" to "langemoji_kaomoji_rail_right"),

    // ------------------------------------------------------------ the theme editor, more of it
    pair("theme_bold_labels_title", dressed(plainTheme), dressed(plainTheme.copy(boldKeyLabels = true))),
    gallery(
        "theme_material_texture_title",
        "theme_material_texture_none_label" to capWith { this },
        "theme_material_texture_noise_label" to capWith { copy(texture = "NOISE", textureOpacity = 0.6f) },
        "theme_material_texture_linen_label" to capWith { copy(texture = "LINEN", textureOpacity = 0.6f) },
        "theme_material_texture_brushed_label" to capWith { copy(texture = "BRUSHED", textureOpacity = 0.6f) },
        "theme_material_texture_wood_label" to capWith { copy(texture = "WOOD", textureOpacity = 0.6f) },
        "theme_material_texture_stone_label" to capWith { copy(texture = "STONE", textureOpacity = 0.6f) },
        "theme_material_texture_carbon_label" to capWith { copy(texture = "CARBON", textureOpacity = 0.6f) },
        "theme_material_texture_pixels_label" to capWith { copy(texture = "PIXELS", textureOpacity = 0.6f) },
    ),
    gallery(
        "theme_material_edge_title",
        "theme_material_edge_none_label" to capWith { this },
        "theme_material_edge_stitch_label" to capWith { copy(edge = "STITCH") },
        "theme_material_edge_glow_label" to capWith { copy(edge = "GLOW") },
        "theme_material_edge_ink_label" to capWith { copy(edge = "INK") },
        "theme_material_edge_rim_label" to capWith { copy(edge = "RIM") },
        "theme_material_edge_double_label" to capWith { copy(edge = "DOUBLE") },
        "theme_material_edge_dotted_label" to capWith { copy(edge = "DOTTED") },
        "theme_material_edge_bevel_label" to capWith { copy(edge = "BEVEL") },
    ),
    gallery(
        "theme_touch_bloom_motion_title",
        "theme_touch_bloom_motion_sink_label" to bloom("SINK"),
        "theme_touch_bloom_motion_grow_label" to bloom("GROW"),
        "theme_touch_bloom_motion_rise_label" to bloom("RISE"),
        "theme_touch_bloom_motion_tilt_label" to bloom("TILT"),
        "theme_touch_bloom_motion_jelly_label" to bloom("JELLY"),
        "theme_touch_bloom_motion_fade_label" to bloom("FADE"),
        aboveDp = 72,
    ),
    // ------------------------------------------------------------ clipboard, on a history with a pinned and a formatted clip
    toggle("clipboard_outline_pinned_title", richClipboard, crop = HelpCrop.KEYBOARD) { setClipboardOutlinePinned(it) },
    toggle("clipboard_pinned_last_title", richClipboard, crop = HelpCrop.KEYBOARD) { setClipboardPinnedLast(it) },
    toggle("clipboard_type_tags_title", richClipboard, crop = HelpCrop.KEYBOARD) { setClipboardTypeTags(it) },
    toggle("clipboard_track_source_title", richClipboard, crop = HelpCrop.KEYBOARD) { setClipboardTrackSource(it) },
    compare("clipboard_line_spacing_title", richClipboard, off = { repo.setClipboardLineSpacing(0) }, on = { repo.setClipboardLineSpacing(8) }, crop = HelpCrop.KEYBOARD, labels = "=0" to "=8"),

    // ------------------------------------------------------------ more tools
    gallery(
        "tooldetail_launcher_sort_title",
        "tooldetail_launcher_sort_alpha_label" to docs("tools/app-launcher-grid").seededWith { repo.setLauncherSortOrder(AppSortOrder.ALPHABETICAL) },
        "tooldetail_launcher_sort_recent_label" to docs("tools/app-launcher-grid").seededWith { repo.setLauncherSortOrder(AppSortOrder.RECENT_FIRST) },
    ),
    compare("tooldetail_launcher_recents_count_title", docs("tools/app-launcher-grid"), off = { repo.setLauncherMaxRecents(2) }, on = { repo.setLauncherMaxRecents(6) }, crop = HelpCrop.KEYBOARD, labels = "=2" to "=6"),
    compare("toolai_typing_seconds_label", docs("tools/typing-test-panel"), off = { repo.setTypingTestDuration(15) }, on = { repo.setTypingTestDuration(60) }, crop = HelpCrop.KEYBOARD, labels = "=15 s" to "=60 s"),
    toggle("tooldetail_gif_recent_title", docs("emoji/gif-panel-trending"), crop = HelpCrop.KEYBOARD) { setRecentGifs(it) },
    compare("tooldetail_search_count_title", docs("tools/web-search-results"), off = { repo.setSearchResultCount(3) }, on = { repo.setSearchResultCount(10) }, crop = HelpCrop.KEYBOARD, labels = "=3" to "=10"),
    compare("tooldetail_icon_colour_start_title", KbShot(""), off = { }, on = { repo.setToolColor(ToolbarTool.CLIPBOARD, 0xFFE53935); repo.setToolColor(ToolbarTool.EMOJI, 0xFF43A047) }, labels = "common_default" to "common_custom"),
    gallery(
        "voice_ui_title",
        "voice_ui_panel" to docs("tools/voice-panel-listening").seededWith { repo.setVoiceUiMode(VoiceBarSettings.MODE_PANEL) },
        "voice_ui_strip" to docs("tools/voice-panel-listening").seededWith { repo.setVoiceUiMode(VoiceBarSettings.MODE_STRIP) },
        "voice_ui_bar" to docs("tools/voice-panel-listening").seededWith { repo.setVoiceUiMode(VoiceBarSettings.MODE_BAR) },
    ),
    // ------------------------------------------------------------ languages, on their own layouts
    gallery(
        "languages_translit_hints_row_title",
        "languages_translit_hints_off_label" to on("builtin_probhat").seededWith { repo.setTransliterationHints(TransliterationHintMode.OFF) },
        "languages_translit_hints_added_label" to on("builtin_probhat").seededWith { repo.setTransliterationHints(TransliterationHintMode.ADDED) },
        "languages_translit_hints_cluster_label" to on("builtin_probhat").seededWith { repo.setTransliterationHints(TransliterationHintMode.CLUSTER) },
    ),
    compare("languages_raw_keys_chip_title", avroTyping, off = { repo.setRawKeysChip("bn", false) }, on = { repo.setRawKeysChip("bn", true) }),
    toggle("languages_phonetic_english_switch_title", avroTyping) { setPhoneticEnglishSwitch(it) },
    compare("languages_phonetic_strip_fixed_title", avroTyping, off = { repo.setPhoneticFixedStrip("bn", false) }, on = { repo.setPhoneticFixedStrip("bn", true) }),
    compare(
        "languages_numeral_system_title", on("builtin_probhat").seededWith { repo.setNumberRow(true) },
        off = { repo.setNumeralSystemForLanguage("bn", NumeralSystem.LATIN) }, on = { repo.setNumeralSystemForLanguage("bn", NumeralSystem.BENGALI) },
        crop = HelpCrop.KEYBOARD, labels = "core_lang_numeral_system_latin" to "core_lang_numeral_system_bengali",
    ),
    toggle("languages_cjk_jianpin_title", pinyin.copy(steps = { type("nh"); runCatching { tap("Dismiss") }; settle() })) { setPinyinJianpin(it) },
    toggle("languages_cjk_fuzzy_title", pinyin.copy(steps = { type("lihao"); runCatching { tap("Dismiss") }; settle() })) { setPinyinFuzzy(it) },
    toggle("languages_cjk_shortcuts_title", pinyin.copy(steps = { type("rq"); runCatching { tap("Dismiss") }; settle() })) { setPinyinShortcuts(it) },
    // ------------------------------------------------------------ AI, on the docs harness's fake model
    toggle("toolai_ai_diff_title", docs("tools/ai-result-ready"), crop = HelpCrop.KEYBOARD) { setAiDiffView(it) },
    toggle("toolai_ai_model_picker_title", docs("tools/ai-panel-idle"), crop = HelpCrop.KEYBOARD) { setAiPanelModelPicker(it) },
    toggle("toolai_chat_follow_ups_title", docs("tools/ai-chat-conversation"), crop = HelpCrop.KEYBOARD) { setAiChatFollowUps(it) },
    toggle("toolai_chat_enter_sends_title", docs("tools/ai-chat-keyboard"), crop = HelpCrop.KEYBOARD) { setAiChatEnterSends(it) },
    toggle("selection_macros_every_title", typed { type("hello world"); select("world") }) { setSelectionMacrosInEverySelection(it) },
    // ------------------------------------------------------------ gallery, on a made-up photo library
    galleryCompare("gallery_columns_title", labels = "=3" to "=5") { g, on -> g.copy(columns = if (on) 5 else 3) },
    galleryCompare("gallery_camera_tile_title") { g, on -> g.copy(cameraTile = on) },
    galleryCompare("gallery_show_videos_title") { g, on -> g.copy(showVideos = on) },
    galleryCompare("gallery_scrubber_title") { g, on -> g.copy(dateScrubber = on) },
    galleryCompare("gallery_start_expanded_title", crop = HelpCrop.SCREEN) { g, on -> g.copy(startExpanded = on) },
    toggle("clipboard_plain_letters_title", typed { copy("𝐇𝐞𝐥𝐥𝐨 𝐰𝐨𝐫𝐥𝐝"); panel(PanelMode.CLIPBOARD) }, crop = HelpCrop.KEYBOARD) { setClipboardPlainLetters(it) },
    toggle("clipboard_clean_links_title", typed { copy("https://shop.example.com/lamp?id=42&utm_source=newsletter&utm_medium=email&fbclid=IwAR0x"); panel(PanelMode.CLIPBOARD) }, crop = HelpCrop.KEYBOARD) { setClipboardCleanCopiedLinks(it) },
    toggle("tools_split_panels_title", toolboxOpen, crop = HelpCrop.KEYBOARD) { setSplitToolPanels(it) },
    // ------------------------------------------------------------ batch 5: popups, strip, field text, generators
    toggle("keypress_alternates_balanced_title", typed { hold("a") }, aboveDp = 140, crop = HelpCrop.KEYBOARD) { setAlternatesBalanced(it) },
    toggle("keypress_alternates_centred_title", typed { hold("e") }, aboveDp = 140, crop = HelpCrop.KEYBOARD) { setAlternatesCentred(it) },
    compare("layout_board_corner_top_title", KbShot(""), off = { repo.setBoardCornerTopDp(0) }, on = { repo.setBoardCornerTopDp(28) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=28 dp"),
    toggle("langemoji_emoji_kaomoji_title", emojiPanel, crop = HelpCrop.KEYBOARD) { setEmojiKaomojiTabs(it) },
    toggle("typing_suggestion_more_arrow_title", strip) { setSuggestionMoreArrow(it) },
    toggle("typing_typed_word_first_title", typed { type("teh") }) { setTypedWordFirst(it) },
    gallery(
        "typing_next_words_title",
        "typing_next_words_off" to typed { type("good ") }.seededWith { repo.setNextWordSuggestions(NextWordSuggestions.OFF) },
        "typing_next_words_words" to typed { type("good ") }.seededWith { repo.setNextWordSuggestions(NextWordSuggestions.WORDS) },
        "typing_next_words_punct" to typed { type("good ") }.seededWith { repo.setNextWordSuggestions(NextWordSuggestions.WORDS_AND_PUNCTUATION) },
    ),
    toggle("typing_hide_composing_underline_title", typed { type("hello") }, crop = HelpCrop.TEXT) { setHideComposingUnderline(it) },
    toggle("typing_auto_space_punctuation_title", typed { type("hi,there") }, crop = HelpCrop.TEXT) { setAutoSpaceAfterPunctuation(it) },
    toggle("tooldetail_numpad_calc_title", typed { panel(PanelMode.NUMPAD) }, crop = HelpCrop.KEYBOARD) { setNumpadCalculatorLayout(it) },
    compare("tooldetail_passphrase_words_title", typed { panel(PanelMode.PASSWORD_GEN) }.seededWith { repo.setPwPassphraseMode(true) }, off = { repo.setPpWordCount(3) }, on = { repo.setPpWordCount(6) }, crop = HelpCrop.KEYBOARD, labels = "=3" to "=6"),
    toggle("tooldetail_passphrase_capitalize_title", typed { panel(PanelMode.PASSWORD_GEN) }.seededWith { repo.setPwPassphraseMode(true) }, crop = HelpCrop.KEYBOARD) { setPpCapitalize(it) },
    toggle("tooldetail_passphrase_digit_title", typed { panel(PanelMode.PASSWORD_GEN) }.seededWith { repo.setPwPassphraseMode(true) }, crop = HelpCrop.KEYBOARD) { setPpIncludeDigit(it) },
    compare("tooldetail_pin_length_title", typed { panel(PanelMode.PASSWORD_GEN) }.seededWith { repo.setPinMode(true) }, off = { repo.setPinLength(4) }, on = { repo.setPinLength(8) }, crop = HelpCrop.KEYBOARD, labels = "=4" to "=8"),
    compare("tooldetail_show_in_toolbox_title", toolboxOpen, off = { repo.setToolHiddenInToolbox(ToolKey.Builtin(ToolbarTool.GIF), true) }, on = { repo.setToolHiddenInToolbox(ToolKey.Builtin(ToolbarTool.GIF), false) }, crop = HelpCrop.KEYBOARD, labels = "common_off" to "common_on"),
    compare("tooldetail_icon_colour_end_title", KbShot(""), off = { repo.setToolColor(ToolbarTool.CLIPBOARD, 0xFFE53935) }, on = { repo.setToolColor(ToolbarTool.CLIPBOARD, 0xFFE53935); repo.setToolColorEnd(ToolbarTool.CLIPBOARD, 0xFF1E88E5) }, labels = "common_off" to "common_on"),
    toggle("clipboard_grid_rows_title", richClipboard, crop = HelpCrop.KEYBOARD) { setClipboardGridRows(it) },
    toggle("keypress_enter_emoji_title", typed { holdDescription("Done") }, aboveDp = 120, crop = HelpCrop.KEYBOARD) { setEnterLongPressEmoji(it) },
    // ------------------------------------------------------------ batch 6: glide, symbols page, emoji tones, scale
    compare("typing_trail_style_title", gliding, off = { repo.setGestureTrailOpacity(0.35f) }, on = { repo.setGestureTrailOpacity(1f) }, crop = HelpCrop.KEYBOARD, labels = "=35%" to "=100%"),
    compare("typing_trail_length_title", gliding, off = { repo.setGestureTrailDurationMs(150) }, on = { repo.setGestureTrailDurationMs(1200) }, crop = HelpCrop.KEYBOARD, labels = "=150 ms" to "=1200 ms"),
    compare("typing_glide_preview_size_title", gliding, off = { repo.setGestureWordPreviewFontSp(14) }, on = { repo.setGestureWordPreviewFontSp(28) }, crop = HelpCrop.KEYBOARD, labels = "=14 sp" to "=28 sp"),
    compare("typing_glide_preview_height_title", gliding, off = { repo.setGestureWordPreviewOffsetYDp(0) }, on = { repo.setGestureWordPreviewOffsetYDp(90) }, crop = HelpCrop.KEYBOARD, labels = "=0 dp" to "=90 dp"),
    compare("typing_glide_preview_shift_title", gliding, off = { repo.setGestureWordPreviewOffsetXDp(-60) }, on = { repo.setGestureWordPreviewOffsetXDp(60) }, crop = HelpCrop.KEYBOARD, labels = "=-60 dp" to "=60 dp"),
    compare("typing_glide_preview_color_title", gliding, off = { repo.setGestureWordPreviewBackground(null) }, on = { repo.setGestureWordPreviewBackground(0xFFFFD54F) }, crop = HelpCrop.KEYBOARD),
    compare("typing_glide_preview_text_color_title", gliding, off = { repo.setGestureWordPreviewTextColor(null) }, on = { repo.setGestureWordPreviewTextColor(0xFFD32F2F) }, crop = HelpCrop.KEYBOARD),
    toggle("typing_space_after_glide_title", typed { glide("hello") }.seededWith { repo.setGestureTyping(true) }, crop = HelpCrop.TEXT) { setGestureAutoSpace(it) },
    toggle("typing_shift_recase_caret_title", typed { type("hello"); key(Key(label = "", action = KeyAction.Shift)); settle() }, crop = HelpCrop.TEXT) { setShiftRecasesWordAtCaret(it) },
    toggle("typing_autocorrect_mark_title", typed { type("teh ") }.seededWith { repo.setAutocorrect(true) }, crop = HelpCrop.TEXT) { setAutocorrectMarkInField(it) },
    toggle("layout_symbols_return_title", typed { key(Key(label = "", action = KeyAction.Symbols)); type("'") }, crop = HelpCrop.KEYBOARD) { setSymbolsReturnToLetters(it) },
    toggle("layout_symbols_return_space_title", typed { key(Key(label = "", action = KeyAction.Symbols)); type("1 ") }, crop = HelpCrop.KEYBOARD) { setSymbolsReturnOnSpace(it) },
    toggle("layout_number_row_shift_symbols_title", typed { holdDescription("Shift") }.seededWith { repo.setNumberRow(true) }, crop = HelpCrop.KEYBOARD) { setNumberRowShiftSymbols(it) },
    compare("layout_keyboard_scale_title", KbShot(""), off = { repo.setVariantKeyboardScale(ScreenVariant.PORTRAIT, 0.8f) }, on = { repo.setVariantKeyboardScale(ScreenVariant.PORTRAIT, 1.15f) }, crop = HelpCrop.SCREEN, labels = "=80%" to "=115%"),
    compare("layout_font_size_title", KbShot(""), off = { repo.setVariantFontScale(ScreenVariant.PORTRAIT, 0.8f) }, on = { repo.setVariantFontScale(ScreenVariant.PORTRAIT, 1.3f) }, crop = HelpCrop.KEYBOARD, labels = "=80%" to "=130%"),
    gallery(
        "appearance_toolbox_sort_title",
        "appearance_toolbox_sort_custom_label" to toolboxOpen.seededWith { repo.setToolboxSort(ToolboxSort.CUSTOM) },
        "appearance_toolbox_sort_alphabetical_label" to toolboxOpen.seededWith { repo.setToolboxSort(ToolboxSort.ALPHABETICAL) },
    ),
    toggle("typing_password_manager_chip_title", KbShot("", kind = "password", hint = "Password"), crop = HelpCrop.KEYBOARD) { setPasswordManagerChip(it) },
    toggle("typing_space_cursor_magnifier_title", typed { type("hello world"); drag("Space", -220f) }, aboveDp = 90, crop = HelpCrop.KEYBOARD) { setSpaceCursorMagnifier(it) },
    toggle("tooldetail_password_ambiguous_title", typed { panel(PanelMode.PASSWORD_GEN) }, crop = HelpCrop.KEYBOARD) { setPwExcludeAmbiguous(it) },
    compare("tooldetail_qr_gen_ecc_header", typed { type("https://wmkeyboard.app"); panel(PanelMode.QR_GEN) }, off = { repo.setQrEcc(QrEccLevel.L) }, on = { repo.setQrEcc(QrEccLevel.H) }, crop = HelpCrop.KEYBOARD, labels = "=L" to "=H"),
    toggle("expander_panel_stays_title", docs("tools/snippets-panel").let { s -> s.copy(steps = { s.steps(this); tap("Thanks") }) }, crop = HelpCrop.SCREEN) { setSnippetPanelStays(it) },
    // ------------------------------------------------------------ batch 7: glide colour, corners, currency, flicks, fancy
    compare("typing_glide_commit_color_title", gliding, off = { repo.setGestureCommitColor(GlideCommitColor.OFF) }, on = { repo.setGestureCommitColor(GlideCommitColor.ALWAYS) }, crop = HelpCrop.KEYBOARD),
    compare("typing_glide_commit_scope_title", gliding.seededWith { repo.setGestureCommitColor(GlideCommitColor.ALWAYS) }, off = { repo.setGestureCommitColorScope(GlideCommitColorScope.STRIP) }, on = { repo.setGestureCommitColorScope(GlideCommitColorScope.STRIP_AND_PILL) }, crop = HelpCrop.KEYBOARD),
    compare("typing_glide_drawn_letters_title", typed { glide("qzxv") }.seededWith { repo.setGestureTyping(true) }, off = { repo.setGestureDrawnLetters(GlideDrawnLetters.OFF) }, on = { repo.setGestureDrawnLetters(GlideDrawnLetters.WHEN_UNSURE) }, crop = HelpCrop.KEYBOARD),
    compare("layout_board_corners_title", KbShot("", mode = "blank").seededWith { repo.setBoardCornerTopDp(28); repo.setBoardCornerBottomDp(28) }, off = { repo.setBoardCorners(BoardCorner.entries.toSet()) }, on = { repo.setBoardCorners(setOf(BoardCorner.TOP_LEFT, BoardCorner.TOP_RIGHT)) }, crop = HelpCrop.SCREEN, labels = "=All four" to "=Top two"),
    toggle("typing_flick_hints_title", on("asset_en_flick"), crop = HelpCrop.KEYBOARD) { setFlickHints(it) },
    toggle("rows_recent_words_steady_title", KbShot("", mode = "blank").seededWith { repo.setRecentWordBar(true) }, crop = HelpCrop.SCREEN) { setRecentWordSteady(it) },
    toggle("typing_shift_recase_inside_title", typed { type("hello"); hardwareKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT); hardwareKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT); key(Key(label = "", action = KeyAction.Shift)); settle() }, crop = HelpCrop.TEXT) { setShiftRecasesWordAroundCaret(it) },
    compare("tooldetail_fancy_style_title", typed { tool(ToolbarTool.FANCY); type("hello") }, off = { repo.setFancyToolStyle("bold") }, on = { repo.setFancyToolStyle("double_struck") }, crop = HelpCrop.TEXT, labels = "=Bold" to "=Double-struck"),
    compare("languages_fancy_style_row_title", typed { tool(ToolbarTool.FANCY); type("hello") }.seededWith { repo.setFancyToolStyle(null) }, off = { repo.setFancyStyle("bold_script") }, on = { repo.setFancyStyle("bold_fraktur") }, crop = HelpCrop.TEXT, labels = "=Bold script" to "=Bold fraktur"),
    compare("appearance_suggestion_new_word_color_title", typed { type("zelkodyne") }, off = { }, on = { repo.setSuggestionNewWordColor(0xFF43A047) }, labels = "common_default" to "common_custom"),
    // ------------------------------------------------------------ batch 8: delete swipe, alternates, RTL toolbar, pinyin next words
    toggle("typing_backspace_preview_title", typed { type("see you at the station"); drag("Delete", -260f) }.seededWith { repo.setBackspaceSwipeDelete(true) }, crop = HelpCrop.SCREEN) { setBackspaceSwipePreview(it) },
    toggle("appearance_toolbar_rtl_title", on("builtin_arabic"), crop = HelpCrop.KEYBOARD) { setReverseToolbarForRtl(it) },
    toggle("typing_suggestion_pages_title", typed { type("th") }, crop = HelpCrop.KEYBOARD) { setSuggestionsSwipeForMore(it) },
    toggle("languages_cjk_prediction_title", pinyin.copy(steps = { type("nihao"); runCatching { tap("Dismiss") }; key(Key(label = "", action = KeyAction.Space)); settle() })) { setPinyinPrediction(it) },
)
