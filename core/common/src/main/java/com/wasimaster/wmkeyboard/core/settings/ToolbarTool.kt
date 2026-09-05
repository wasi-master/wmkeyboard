package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.util.PlayServices

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
    DICTIONARY, TRANSLATE, GIF, STICKER, WEB_SEARCH, IMAGE_SEARCH,
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
}

/** The cursor tools, in the order they read on the toolbar. */
val CursorTools: List<ToolbarTool> = listOf(
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END,
    ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD, ToolbarTool.SELECT_LINE, ToolbarTool.SELECT_MODE,
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
    ToolbarTool.HIDE_KEYBOARD,
    ToolbarTool.THEMES, ToolbarTool.AUTOCORRECT, ToolbarTool.SOUND_HAPTICS, ToolbarTool.INCOGNITO,
    ToolbarTool.MODES, ToolbarTool.UNDO, ToolbarTool.REDO, ToolbarTool.POWER_SAVING,
    // The fancy layout ships in the assets and its styles are code, so it
    // needs nothing the locked half of the device holds.
    ToolbarTool.FANCY,
    // Custom layouts are settings, and settings are mirrored into the
    // pre-unlock store.
    ToolbarTool.CUSTOM_LAYOUT,
    ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT, ToolbarTool.PASSWORD_GEN, ToolbarTool.QR_GEN,
    ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL, ToolbarTool.MOON_PHASE,
    // Copy and cut only touch the field the user is already typing in. Paste is
    // left out: the clipboard is credential-encrypted, so before the first
    // unlock the button would be there and do nothing.
    ToolbarTool.COPY, ToolbarTool.CUT,
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
    ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT, ToolbarTool.FLOATING, ToolbarTool.RESIZE,
    ToolbarTool.FLASHLIGHT, ToolbarTool.UNDO, ToolbarTool.REDO,
    ToolbarTool.INCOGNITO, ToolbarTool.POWER_SAVING, ToolbarTool.AUTOCORRECT,
    ToolbarTool.FANCY, ToolbarTool.CUSTOM_LAYOUT, ToolbarTool.HIDE_KEYBOARD,
    -> false
    // The cursor moves nudge the caret and nothing else, and the clipboard trio
    // acts on the selection in place.
    else -> tool !in CursorTools && tool !in ClipboardTools
}

fun isSupportedTool(tool: ToolbarTool): Boolean = when {
    !BuildConfig.ENABLE_ML_KIT_HANDWRITING && tool == ToolbarTool.HANDWRITING -> false
    !BuildConfig.ENABLE_ML_KIT_SCANNERS && tool in setOf(
        ToolbarTool.OCR, ToolbarTool.QR_SCAN, ToolbarTool.DOC_SCAN
    ) -> false
    !BuildConfig.ENABLE_GRAMMAR && tool == ToolbarTool.GRAMMAR -> false
    // The only tool that is not this app's own code: the scanner is an
    // activity inside Play services, so on a device without it the tool can
    // do nothing but apologise. OCR and QR stay — their models are in the
    // APK and run with no Play services at all.
    !PlayServices.available && tool == ToolbarTool.DOC_SCAN -> false
    else -> true
}


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
    ToolbarTool.WIKIPEDIA, ToolbarTool.POWER_SAVING, ToolbarTool.MODES, ToolbarTool.NUMPAD,
    ToolbarTool.SYMBOLS, ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT, ToolbarTool.CURRENCY,
    ToolbarTool.PASSWORD_GEN, ToolbarTool.CALENDAR, ToolbarTool.WEATHER,
    ToolbarTool.MEDIA_CONTROL, ToolbarTool.APP_LAUNCHER, ToolbarTool.HIDE_KEYBOARD,
)

/**
 * What a starter set gains when part of it cannot run on this device.
 *
 * Handwriting and text scanning are the pair that goes missing — the lite
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
    ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH,
    ToolbarTool.TYPING_TEST, ToolbarTool.PLUGINS, ToolbarTool.CUSTOM_LAYOUT,
    ToolbarTool.FLOATING, ToolbarTool.RESIZE, ToolbarTool.INCOGNITO, ToolbarTool.SOUND_HAPTICS,
    ToolbarTool.QR_SCAN, ToolbarTool.QR_GEN, ToolbarTool.DOC_SCAN, ToolbarTool.CAMERA,
    ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL, ToolbarTool.MOON_PHASE,
    // The one-tap cursor moves last: useful, but they would otherwise push
    // every other tool a full row down in the toolbox.
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END, ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD, ToolbarTool.SELECT_LINE, ToolbarTool.SELECT_MODE,
    ToolbarTool.COPY, ToolbarTool.CUT, ToolbarTool.PASTE,
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

