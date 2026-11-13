package com.wasimaster.wmkeyboard.core.settings

import android.os.Build
import com.wasimaster.wmkeyboard.config.BuildConfig

// Extracted from SettingsRepository.kt so the low-level modules (icons, tools,
// media) can reference these types without depending on the settings module —
// same package, different Gradle module (:core:common).

/**
 * How media is offered to the target app through commitContent.
 *
 * There is no "sticker" flag in the platform API — the only signal is the
 * MIME type, and the receiving app decides. [STICKER] means "prefer a
 * sticker MIME where the field advertises one"; apps that don't get a
 * normal image instead. See [MediaMime].
 */
enum class MediaSendMode { IMAGE, STICKER }

/**
 * Where the emoji, GIF and sticker panels draw the switch that jumps between
 * the three (issue #366): along the bottom beside abc and space, or up top
 * beside the search. Down here rather than with the emoji settings so the
 * shipped emoji panel layout, in the layout module, can be drawn for either.
 */
enum class MediaSwitcher { OFF, TOP, BOTTOM }


/**
 * A tool that can live on the top toolbar. Tools not in
 * [KeyboardSettings.toolbarTools] wait in the toolbox panel; the user drags
 * them between the two while the toolbox is open (Gboard style). Tools not
 * in [KeyboardSettings.enabledTools] are hidden everywhere.
 */
enum class ToolbarTool {
    EMOJI, CLIPBOARD, SNIPPETS, TEXT_EDIT,
    // The key area as a pointing surface: drag to move the caret, hold and
    // drag to select. Tap toggles it; a hold on the toolbar keeps it open only
    // while the finger stays down (issue #39).
    TRACKPAD,
    ONE_HANDED, SPLIT, FLOATING, SETTINGS,
    FLASHLIGHT, COMPASS, LEVEL, UNDO, REDO, MOON_PHASE, WEATHER, CALENDAR,
    INCOGNITO, THEMES, AUTOCORRECT, SOUND_HAPTICS, NUMPAD, HANDWRITING, CAMERA,
    DICTIONARY,
    // Word cards, nudges and flashcards from downloadable vocabulary packs.
    VOCABULARY,
    TRANSLATE, GIF, STICKER, WEB_SEARCH, IMAGE_SEARCH,
    OCR, QR_SCAN, DOC_SCAN, VOICE, GRAMMAR,
    WIKIPEDIA, SYMBOLS, CALCULATOR, UNIT_CONVERT, CURRENCY, QR_GEN, PASSWORD_GEN, AI,
    MODES, TYPING_TEST, MEDIA_CONTROL, PLUGINS, POWER_SAVING, APP_LAUNCHER, FANCY,
    // One of the user's secondary layouts — a grid reached by a key or this
    // tool rather than by picking a language — shown over the letters and
    // taken off again (issue #62). Which one is a setting on the tool's page.
    CUSTOM_LAYOUT,
    // Inline drag-resize of the docked keyboard: height, bottom padding, position.
    RESIZE,
    // One-tap cursor moves. The text-edit panel already offers these, but on
    // the toolbar they cost a single tap instead of opening a panel first.
    CURSOR_LEFT, CURSOR_RIGHT, CURSOR_UP, CURSOR_DOWN,
    CURSOR_HOME, CURSOR_END, PAGE_UP, PAGE_DOWN,
    // Move by whole words (Ctrl+Arrow), and select the word or line at the cursor.
    CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT, SELECT_WORD, SELECT_LINE,
    // The whole field in one tap (issue #228). The text-edit panel's Select all
    // key and the selection bar's first chip run the same action; on the
    // toolbar it costs no panel and no selection to start from.
    SELECT_ALL,
    // Selection mode: while it is on, every caret move extends the selection
    // instead of collapsing it, the way a held shift does on a physical keyboard.
    SELECT_MODE,
    // The clipboard trio as one-tap toolbar buttons (issue #41). The text-edit
    // panel and a long press on C/X/V already reach them; on the toolbar they
    // cost a single tap with nothing to open first.
    COPY, CUT, PASTE,
    // Dismiss the keyboard in one tap. Grouped with the cursor moves in the
    // toolbox (it belongs beside the caret controls, not a panel it opens).
    HIDE_KEYBOARD,
    // Keep the keyboard on screen after its field is gone, and over windows
    // with no field at all, until the user hides it (issue #58). Toggles the
    // same setting as the Layout screen's row. Declared last: nothing stores
    // ordinals, but the toolbox ranks by name and this stays out of its way.
    PERSISTENT,
    // Turns the selection actions bar on and off (issue #177). The same
    // setting as Advanced → Selection actions, one tap from the keys.
    SELECTION_ACTIONS,
    // Reads the field (or the selection) and lists the words the keyboard does
    // not know yet, to add to the personal dictionary in one go (#174).
    LEARN_FROM_TEXT,
    // Whether Avro and Hindi phonetic keep an English word in Latin letters.
    // The same setting as Typing → Type English words as English: the switch is
    // reached for mid-word, when a Bengali word has just been read as English.
    PHONETIC_ENGLISH,
    // A paired computer over KDE Connect: its mouse, keyboard, media, clipboard
    // and files, and the computer typing back into the phone (issue #285).
    KDE_CONNECT,
    // A shortcut to the settings app's Statistics screen: the level, the
    // achievements and the tap heatmap, one tap from the keys (issue #390).
    STATISTICS,
    // Find and replace over the field (#627): the panel the selection bar's
    // Replace chip opens, reachable with nothing selected first.
    FIND_REPLACE,
    // A notebook inside the keyboard (#560): notes with Markdown, tags,
    // checklists, pin, archive and trash, kept in the app's files and shared
    // with the settings app's Notes screen. Reads filesDir, so not available
    // before the first unlock.
    NOTES,
    // Drafting an answer to somebody else's message (#234). Apart from the AI
    // tool because it works the other way round: that one rewrites what the
    // user has written, this one reads what they cannot select — a clip, a
    // screenshot, a pasted message — and offers replies to pick from.
    AI_REPLY,
    // Where the user is, or a place they saved, put into the field as a map
    // link, an address, coordinates or a Plus code (#350). The only tool that
    // reads the position, and only on a tap; saved places need no permission.
    LOCATION,
    // World clocks, alarms, timers, a stopwatch, a meeting planner, date maths
    // and a history. Its data is in device-protected storage so alarms ring
    // before the first unlock, but the panel itself waits for the unlock like
    // the other tools that keep files.
    CLOCK,
    // The user's Home Assistant from the keyboard: rooms of tiles for lights,
    // switches, covers, climate, media, locks, scenes and sensors, live over
    // its WebSocket, plus a typed Assist command bar. Talks only to the
    // server the user signed in to. Needs the token, which is in
    // credential-encrypted storage, so not before the first unlock.
    HOME_ASSISTANT,
    // The phone's photos and videos inside the keyboard, Telegram's attach
    // sheet: albums, multi-select, a viewer, an editor, and a send that hands
    // the app only a copy of what was picked (#708). Play builds open
    // Android's photo picker instead of the grid; see ChannelFeatures.
    GALLERY,
    // The next language, as the 🌐 key switches it (issue #667): for a bottom
    // row with no globe key, or one whose globe sits where a comma is meant.
    LANGUAGE,
    // The clipboard opened straight into its search (#612). Action-only: for
    // a flick, a key or a hold, never a toolbar tile; see [ActionOnlyTools].
    CLIPBOARD_SEARCH,
    // The row of the latest copies on the suggestion strip (#612), up until a
    // key is typed. Action-only, like [CLIPBOARD_SEARCH]: bound to the Copy
    // tool's hold, it is the last few copies one press away.
    RECENT_COPIES,
    // A piece of text written out many times: "ha" fifty times, a numbered
    // list of one line, "no" with its last letter stretched. Opened by its
    // chip when the same thing has just been typed a few times in a row.
    TEXT_REPEAT,
    // Files to other people (#729): encrypted on the phone and uploaded to a Send
    // instance as a link with an expiry, a download limit and a password, or
    // straight to a device nearby over Wi-Fi. Receives both kinds too.
    SEND_FILES,
    // Out of the field, not just the keyboard down (#626). Android gives a
    // keyboard no way to take another app's focus, but Esc does it in the
    // apps that answer it (a browser's address bar among them): one Esc,
    // a second if the field is still there, then the keyboard goes.
    // Action-only, like [CLIPBOARD_SEARCH]: meant for the floating button's
    // tap while the keyboard is up, a flick or a key.
    LEAVE_FIELD,
    // Reads the typed text aloud: the selection, or the field around the
    // caret, in the keyboard's language. A downloaded MMS voice reads it
    // offline when there is one, the phone's own voice otherwise. A second
    // tap stops it.
    READ_BACK,
    // A new note, the keys already typing into its body, in the Notes panel
    // with a row of the latest notes under it to switch to one and add to it
    // (#864). Action-only, like [CLIPBOARD_SEARCH].
    QUICK_NOTE,
    // The focused field's EditorInfo decoded, the keyboard's state, the screen
    // and memory, for a bug report (FUTO U3), shown in the settings app with a
    // Copy button. Never the field's text. Action-only, like [CLIPBOARD_SEARCH].
    DEBUG_INFO,
    // The characters that sound like the Chinese or Japanese character just
    // typed, on the strip, a pick replacing it (AnySoftKeyboard's homophone
    // search key). Action-only, like [CLIPBOARD_SEARCH]: meant for a key on
    // a CJK layout, a flick or a hold.
    HOMOPHONES,
    // A to-do list with steps under each task (#920), kept in the app's files
    // and shared with the settings app's list. Can sync with a CalDAV task
    // list through DAVx5's task app when the user sets that up. Reads
    // filesDir, so not available before the first unlock.
    TODO,
    // Logins from the separate WM Pass app, typed into the field. The vault and
    // its unlocking stay in that app, and nothing is kept here.
    WM_PASS,
    // Two emoji drawn as one sticker, from a source the user added under
    // Emoji › Emoji mixes: the panel beside emoji, GIFs and stickers on the
    // switch. Nothing ships for it, so it is blocked until a source is added.
    EMOJI_MIX,
    // What song is playing: ten seconds from the microphone, asked of the
    // recognition services on its list in turn, and the answer typed as text
    // or sent as a picture. Keeps its history under filesDir, so not before
    // the first unlock. Blocked until a recogniser is set up with a key.
    MUSIC_ID,
    // An internet speed test (download, upload, ping, jitter) whose result
    // goes into the field as a picture: a card drawn in one of several
    // styles, sizes and layouts. Against Cloudflare's test servers or a
    // LibreSpeed server, from the tool's own provider list.
    SPEED_TEST,
    // Android's own screenshot (#1148), the keyboard put away first so it is
    // not in the picture. Pressed through SystemButtonsService, an accessibility
    // service of its own; not in the Play build, and needs Android 9.
    SCREENSHOT,
    // A cover over the app above the keyboard, so the conversation cannot be
    // read over your shoulder while you type (#1242). Drag its edge up to see
    // the last lines. A switch over a setting; drawn in the keyboard's own
    // window, so it needs no permission to draw over other apps.
    PRIVACY_SHADE,
    // Everything in the field gone in one tap (#1194), for the fields that
    // have no clear button of their own. Undo brings it back.
    CLEAR_FIELD,
    // The keyboard's file manager as a tool of its own (#1267), not only the
    // picker other tools open: rename, copy, move and delete, one list or two
    // side by side, over the phone's storage (with All files access) and the
    // folders added through Android's folder picker, which a Play build can
    // use too. Not over the lock screen.
    FILES,
    // The date and time typed in one tap, in the format the Clock tool's
    // insert sheet is set to (competitor gap list item 6, HeliBoard's
    // timestamp key). The first Backspace after it takes it back whole.
    TIMESTAMP,
}

/** The cursor tools, in the order they read on the toolbar. */
val CursorTools: List<ToolbarTool> = listOf(
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END,
    ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD, ToolbarTool.SELECT_LINE, ToolbarTool.SELECT_ALL,
    ToolbarTool.SELECT_MODE,
)

/**
 * The clipboard tools, in the order they read on the toolbar.
 *
 * Kept apart from [CursorTools] because they are not caret moves — they act on
 * the selection rather than on where it is — but they share every rule those
 * have: a tap acts on the spot, nothing opens, and a hold reaches the settings
 * page rather than repeating (a second paste is rarely what anyone means).
 */
val ClipboardTools: List<ToolbarTool> = listOf(
    ToolbarTool.COPY, ToolbarTool.CUT, ToolbarTool.PASTE,
)

/**
 * The cursor tools a hold repeats, rather than opening their settings page.
 *
 * Every caret move whose second press does something different from its first.
 * Home and End are left out because theirs does not: the caret is already at
 * the end of the line, so a hold would buzz away sending key events nothing
 * acts on. Select word and select line are out for the same reason, and are not
 * moves in the first place.
 */
val HoldRepeatCursorTools: Set<ToolbarTool> = setOf(
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
)

/**
 * Tools that still work during direct boot — before the user has ever unlocked
 * the device, when the keyboard has no credential-encrypted storage, no
 * personal data, and (usually) no network.
 *
 * The rule is what a tool *reads*, not what it looks like: anything backed by a
 * file under `filesDir` (clipboard, snippets, stickers, camera shots, the
 * downloaded speech and LLM models, ML Kit's models), anything that needs a
 * credential the mirror deliberately does not carry (translate, the searches,
 * AI), anything that queries a content provider behind the lock (calendar), and
 * anything that has to start an activity (the settings app, the document
 * scanner) is out. What is left is arithmetic, sensors, and the keyboard's own
 * controls — which is roughly what anyone wants from a lock screen anyway.
 */
fun isDirectBootSafeTool(tool: ToolbarTool): Boolean = when (tool) {
    ToolbarTool.EMOJI, ToolbarTool.TEXT_EDIT, ToolbarTool.TRACKPAD, ToolbarTool.NUMPAD,
    ToolbarTool.SYMBOLS, ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT, ToolbarTool.FLOATING, ToolbarTool.RESIZE,
    ToolbarTool.HIDE_KEYBOARD, ToolbarTool.LEAVE_FIELD, ToolbarTool.PERSISTENT,
    ToolbarTool.THEMES, ToolbarTool.AUTOCORRECT, ToolbarTool.SOUND_HAPTICS, ToolbarTool.INCOGNITO,
    ToolbarTool.MODES, ToolbarTool.UNDO, ToolbarTool.REDO, ToolbarTool.POWER_SAVING,
    // The fancy layout ships in the assets and its styles are code, so it
    // needs nothing the locked half of the device holds.
    ToolbarTool.FANCY,
    // Custom layouts are settings, and settings are mirrored into the
    // pre-unlock store.
    ToolbarTool.CUSTOM_LAYOUT,
    // A switch over a setting, and settings are mirrored into the pre-unlock
    // store. The bar it controls already drops every action that leaves the app.
    ToolbarTool.SELECTION_ACTIONS,
    // A switch over a setting too.
    ToolbarTool.PHONETIC_ENGLISH,
    // Reads and edits only the field. Its search history is credential-
    // protected and simply reads empty before the first unlock.
    ToolbarTool.FIND_REPLACE,
    // Writes into the field only; its recent texts are credential-protected
    // and read empty before the first unlock, like Find and replace's history.
    ToolbarTool.TEXT_REPEAT,
    ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT, ToolbarTool.PASSWORD_GEN, ToolbarTool.QR_GEN,
    ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL, ToolbarTool.MOON_PHASE,
    // Copy and cut only touch the field the user is already typing in. Paste is
    // left out: the clipboard is credential-encrypted, so before the first
    // unlock the button would be there and do nothing.
    ToolbarTool.COPY, ToolbarTool.CUT,
    // Languages are settings, mirrored into the pre-unlock store.
    ToolbarTool.LANGUAGE,
    // A switch over a setting, drawn in the keyboard's window.
    ToolbarTool.PRIVACY_SHADE,
    // Only touches the field the user is already typing in.
    ToolbarTool.CLEAR_FIELD,
    // Reads the clock's format from device-protected preferences.
    ToolbarTool.TIMESTAMP,
    -> true
    // The cursor moves only touch the input connection.
    else -> tool in CursorTools
}

/**
 * Whether tapping the tool takes the user somewhere — a keyboard panel, or an
 * activity of its own — rather than acting on the spot and leaving the keys
 * where they are.
 *
 * The pill toolbox draws a chevron on the ones that do, so "Flashlight" (a
 * torch toggle) reads differently from "Themes" (a panel) before you tap
 * either. Written as a short deny-list because opening something is the
 * common case: a new tool is a panel unless it says otherwise. Keep it in
 * step with the keyboard service's tool-tap handler, which is the thing that
 * actually decides.
 */
fun toolOpensScreen(tool: ToolbarTool): Boolean = when (tool) {
    // Toggles and one-shot actions: the keyboard stays exactly as it is.
    ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT, ToolbarTool.FLOATING, ToolbarTool.PERSISTENT,
    ToolbarTool.RESIZE,
    ToolbarTool.FLASHLIGHT, ToolbarTool.UNDO, ToolbarTool.REDO,
    ToolbarTool.INCOGNITO, ToolbarTool.POWER_SAVING, ToolbarTool.AUTOCORRECT,
    ToolbarTool.FANCY, ToolbarTool.CUSTOM_LAYOUT, ToolbarTool.HIDE_KEYBOARD, ToolbarTool.LEAVE_FIELD,
    ToolbarTool.SELECTION_ACTIONS, ToolbarTool.PHONETIC_ENGLISH, ToolbarTool.LANGUAGE,
    ToolbarTool.RECENT_COPIES, ToolbarTool.READ_BACK, ToolbarTool.SCREENSHOT, ToolbarTool.PRIVACY_SHADE,
    ToolbarTool.CLEAR_FIELD, ToolbarTool.TIMESTAMP, ToolbarTool.HOMOPHONES,
    -> false
    // The cursor moves nudge the caret and nothing else, and the clipboard trio
    // acts on the selection in place.
    else -> tool !in CursorTools && tool !in ClipboardTools
}

// Handwriting is in every build: where ML Kit's recogniser is not
// (F-Droid's), zinnia still reads Chinese and Japanese, and the panel says
// so for any other language. So is the QR scanner, which reads with
// zxing-cpp where ML Kit's barcode reader is not, and text scan, which reads
// with PaddleOCR on MNN where neither ML Kit nor LiteRT is.
fun isSupportedTool(tool: ToolbarTool): Boolean = when {
    !BuildConfig.ENABLE_GRAMMAR && tool == ToolbarTool.GRAMMAR -> false
    // An accessibility service's action (#1148): not in the Play build, and
    // Android has no screenshot action to press before 9.
    tool == ToolbarTool.SCREENSHOT ->
        com.wasimaster.wmkeyboard.app.ChannelFeatures.SYSTEM_BUTTONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    // The document scanner is offered everywhere: Google's (an activity
    // inside Play services) where it can run, the built-in OpenCV one
    // everywhere else, F-Droid's build and phones without Play services.
    else -> true
}


/**
 * Tools no preset turns on, not even "Everything" or the all-on default a
 * fresh install starts from: the user switches them on by hand or not at all.
 * Learn from text reads a whole pasted document into the dictionary, which is
 * a thing to go looking for, not a tile to trip over.
 */
val OptInOnlyTools: Set<ToolbarTool> = setOf(ToolbarTool.LEARN_FROM_TEXT)

/**
 * Tools that are only ever something a gesture does: a flick arm, a key or its
 * long press, a toolbar button's hold, the floating button. They never sit on
 * the toolbar or in the toolbox, have no page on the Tools screen and are
 * never "enabled": the pickers for those bindings are where they live. The
 * bindings run through paths that skip the enabled-tools test, so they fire
 * regardless.
 */
val ActionOnlyTools: Set<ToolbarTool> =
    setOf(
        ToolbarTool.CLIPBOARD_SEARCH, ToolbarTool.RECENT_COPIES, ToolbarTool.LEAVE_FIELD, ToolbarTool.QUICK_NOTE,
        ToolbarTool.DEBUG_INFO, ToolbarTool.HOMOPHONES,
    )

/**
 * The "Everything" preset, and the enabled-tools default before setup swaps
 * it for a persona's set: every tool except [OptInOnlyTools] and [ActionOnlyTools]
 * (declared above it, since top-level initialisation runs in file order). Onboarding
 * compares the stored set against this one to tell an untouched default from
 * a user's own choice.
 */
val EverythingTools: List<ToolbarTool> = ToolbarTool.entries.filterNot { it in OptInOnlyTools || it in ActionOnlyTools }

/**
 * The onboarding starting selection for the "keep it simple" persona: what a
 * keyboard is expected to have plus the two that sell this one, and nothing
 * else until it is asked for.
 *
 * Every set here is ordered, and the order is load-bearing — [RankedToolOrder]
 * is built from it, so the toolbox reads in the sequence the wizard turned
 * these on. They are declared above that list rather than beside the other
 * defaults because top-level initialisation runs in file order.
 */
val MinimalTools: Set<ToolbarTool> = setOf(
    ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.CLIPBOARD,
    ToolbarTool.VOICE, ToolbarTool.AI, ToolbarTool.GRAMMAR, ToolbarTool.SETTINGS,
)

/**
 * The middle persona's set, and the wizard's "Recommended" button: the
 * everyday tools most people actually use, leaving the specialty ones
 * (sensors, scanners, generators) off until asked for.
 */
val RecommendedTools: Set<ToolbarTool> = MinimalTools + setOf(
    ToolbarTool.THEMES, ToolbarTool.DICTIONARY, ToolbarTool.FANCY, ToolbarTool.TRANSLATE,
    ToolbarTool.AUTOCORRECT, ToolbarTool.SNIPPETS, ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT,
    ToolbarTool.TEXT_EDIT, ToolbarTool.TRACKPAD, ToolbarTool.HANDWRITING, ToolbarTool.OCR,
    ToolbarTool.UNDO, ToolbarTool.REDO,
)

/**
 * What "show me everything" starts with. Not the whole enum — the tools page
 * that follows that answer exists precisely so the rest can be picked by hand
 * — but everything with an everyday use, so a power user finds their keypad,
 * their converters and their calendar already on.
 */
val PowerTools: Set<ToolbarTool> = RecommendedTools + setOf(
    ToolbarTool.WIKIPEDIA, ToolbarTool.VOCABULARY, ToolbarTool.NOTES, ToolbarTool.TODO, ToolbarTool.GALLERY, ToolbarTool.POWER_SAVING,
    ToolbarTool.MODES,
    ToolbarTool.NUMPAD,
    ToolbarTool.SYMBOLS, ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT, ToolbarTool.CURRENCY,
    ToolbarTool.PASSWORD_GEN, ToolbarTool.TEXT_REPEAT, ToolbarTool.CALENDAR, ToolbarTool.CLOCK, ToolbarTool.WEATHER,
    ToolbarTool.LOCATION,
    ToolbarTool.MEDIA_CONTROL, ToolbarTool.KDE_CONNECT, ToolbarTool.HOME_ASSISTANT, ToolbarTool.SEND_FILES,
    ToolbarTool.APP_LAUNCHER, ToolbarTool.HIDE_KEYBOARD,
)

/**
 * What a starter set gains when part of it cannot run on this device.
 *
 * Handwriting and text scanning are the pair that goes missing — the F-Droid
 * build ships neither, and the document scanner beside them needs Google
 * services — and a "recommended" set that quietly arrives two tools short is
 * a worse set rather than a smaller one. Both stand-ins run anywhere: an
 * offline reference lookup, and the battery saver.
 */
val ToolTopUps: Set<ToolbarTool> = setOf(ToolbarTool.WIKIPEDIA, ToolbarTool.POWER_SAVING)

/**
 * Everything no starter set turns on, in the order it lands after them:
 * the other searches, the developer-ish tools, then the scanners, the camera
 * and the sensors.
 */
private val RestOfToolOrder: List<ToolbarTool> = listOf(
    ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH, ToolbarTool.AI_REPLY,
    ToolbarTool.LEARN_FROM_TEXT, ToolbarTool.FIND_REPLACE, ToolbarTool.TYPING_TEST, ToolbarTool.SPEED_TEST, ToolbarTool.STATISTICS, ToolbarTool.PLUGINS,
    ToolbarTool.CUSTOM_LAYOUT, ToolbarTool.LANGUAGE,
    ToolbarTool.FLOATING, ToolbarTool.PERSISTENT, ToolbarTool.RESIZE, ToolbarTool.INCOGNITO, ToolbarTool.PRIVACY_SHADE,
    ToolbarTool.SELECTION_ACTIONS, ToolbarTool.PHONETIC_ENGLISH, ToolbarTool.SOUND_HAPTICS,
    ToolbarTool.EMOJI_MIX, ToolbarTool.MUSIC_ID, ToolbarTool.FILES, ToolbarTool.TIMESTAMP,
    ToolbarTool.QR_SCAN, ToolbarTool.QR_GEN, ToolbarTool.TEXT_REPEAT, ToolbarTool.READ_BACK, ToolbarTool.DOC_SCAN, ToolbarTool.CAMERA,
    ToolbarTool.FLASHLIGHT, ToolbarTool.SCREENSHOT, ToolbarTool.COMPASS, ToolbarTool.LEVEL, ToolbarTool.MOON_PHASE,
    // The one-tap cursor moves last: useful, but they would otherwise push
    // every other tool a full row down in the toolbox.
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END, ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD, ToolbarTool.SELECT_LINE, ToolbarTool.SELECT_ALL,
    ToolbarTool.SELECT_MODE,
    ToolbarTool.COPY, ToolbarTool.CUT, ToolbarTool.PASTE, ToolbarTool.CLEAR_FIELD,
    ToolbarTool.CLIPBOARD_SEARCH, ToolbarTool.RECENT_COPIES, ToolbarTool.LEAVE_FIELD,
    ToolbarTool.QUICK_NOTE, ToolbarTool.DEBUG_INFO, ToolbarTool.HOMOPHONES,
)

/**
 * Toolbox order until the user rearranges it: the starter sets first, in their
 * own order — the tools a new install actually has are the ones its first page
 * shows — then everything setup left off. A tool missing from the ranked list
 * still shows, appended at the end, so forgetting to rank a new tool is
 * cosmetic rather than a disappearance.
 */
private val RankedToolOrder: List<ToolbarTool> = (PowerTools + RestOfToolOrder).toList()

val DefaultToolOrder: List<ToolbarTool> =
    RankedToolOrder + (ToolbarTool.entries - RankedToolOrder.toSet())

/**
 * How many pages [count] tools fill at [pageSize] each. Always at least one:
 * an empty toolbox is one empty page, not zero pages, and a pager with no
 * pages at all is a crash waiting for the first swipe.
 */
fun toolboxPageCount(count: Int, pageSize: Int): Int =
    if (pageSize <= 0) 1 else ((count + pageSize - 1) / pageSize).coerceAtLeast(1)

/**
 * The slice of [tools] on page [page]. Out-of-range pages come back empty
 * rather than throwing — the page count follows a live list, so a page can
 * outlive its contents by a frame when a tool is pinned away.
 */
fun <T> toolboxPage(tools: List<T>, page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0) return tools
    val start = (page.coerceAtLeast(0) * pageSize).coerceAtMost(tools.size)
    return tools.subList(start, (start + pageSize).coerceAtMost(tools.size))
}

/**
 * The tools pinned to the toolbar out of the box, and what "Reset pinned
 * tools" restores — global or, when a mode owns the tool order, for that mode.
 */
val DefaultToolbarTools: List<ToolbarTool> =
    listOf(ToolbarTool.EMOJI, ToolbarTool.CLIPBOARD, ToolbarTool.SETTINGS)

/**
 * What a tablet pins instead, because three tools spread across a twelve-inch
 * bar is mostly empty space.
 *
 * Applied by `applyDeviceForm` only while the pinned set is still exactly
 * [DefaultToolbarTools] — the free test for "the user has never rearranged
 * this". Its one false positive is someone who deliberately dragged their bar
 * back to precisely the shipped three, who then gets their screen's default;
 * that also keeps "Reset pinned tools" honest, since reset writes the default
 * and this immediately re-expands it.
 *
 * Every entry is in [RecommendedTools], so no onboarding persona leaves one
 * pinned but disabled — `visibleToolbarTools` would silently drop it. The
 * five here are in [MinimalTools] too; the large-tablet pair below adds
 * Translate and Text edit, which "keep it simple" does not turn on, so that
 * persona on a large tablet pins five of seven.
 */
val SmallTabletToolbarTools: List<ToolbarTool> = listOf(
    ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.CLIPBOARD,
    ToolbarTool.VOICE, ToolbarTool.SETTINGS,
)

/** …and two more again on a board wide enough to hold them. */
val LargeTabletToolbarTools: List<ToolbarTool> = listOf(
    ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.CLIPBOARD, ToolbarTool.VOICE,
    ToolbarTool.TRANSLATE, ToolbarTool.TEXT_EDIT, ToolbarTool.SETTINGS,
)

/**
 * Colour-vision correction applied to the whole resolved keyboard palette.
 * The three dichromacies daltonize (redistribute the hues that eye cannot
 * separate); [GRAYSCALE] strips colour entirely, which both helps monochromacy
 * and makes any luminance-contrast failure in a theme immediately visible.
 */
enum class ColorVisionFilter { NONE, DEUTERANOPIA, PROTANOPIA, TRITANOPIA, GRAYSCALE }

