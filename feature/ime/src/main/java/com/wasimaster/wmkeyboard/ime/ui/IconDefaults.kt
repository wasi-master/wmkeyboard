package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Assignment
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FactCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardTab
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.LastPage
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.TextSnippet
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AspectRatio
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BarChart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatterySaver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Brush
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Calculate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Screenshot
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Blinds
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dialpad
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DirectionsCar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiFlags
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiNature
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiObjects
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiPeople
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiSymbols
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Explore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fastfood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditCalendar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FirstPage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatShapes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Functions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HighlightAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HomeIotDevice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ImageSearch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Interests
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardCapslock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pets
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phonelink
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPictureAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QueryStats
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Quickreply
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Repeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SentimentSatisfied
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NetworkCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SportsSoccer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TravelExplore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewHeadline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.EnterAction

/**
 * The glyph every [IconSlots] slot draws when the user has not replaced it.
 *
 * This is the single built-in icon table. Before it existed the tool icons
 * lived in two byte-identical `when` blocks — one on the keyboard side, one in
 * the settings app — which meant an icon change had to be made twice or the
 * two surfaces silently disagreed. `toolIcon`, `toolIconFor`, `enterActionIcon`
 * and `emojiTabIcon` are now one-line delegates onto this object, so their
 * existing call sites are untouched and the mapping exists once.
 *
 * The per-tool `when` stays exhaustive on purpose, matching `toolAccentColor`:
 * a newly added tool should be a compile error until someone picks its icon,
 * not a silent fallback.
 */
object IconDefaults {

    fun forTool(tool: ToolbarTool): ImageVector = when (tool) {
        ToolbarTool.EMOJI -> Icons.Outlined.EmojiEmotions
        // The clipboard with lines on it, not the empty one Paste draws: the
        // two sit side by side on a toolbar often enough that sharing a
        // glyph left only their position to tell them apart (#414).
        ToolbarTool.CLIPBOARD -> Icons.AutoMirrored.Outlined.Assignment
        ToolbarTool.CLIPBOARD_SEARCH -> Icons.Outlined.Search
        ToolbarTool.RECENT_COPIES -> Icons.Outlined.History
        ToolbarTool.LEAVE_FIELD -> Icons.Outlined.Close
        ToolbarTool.SCREENSHOT -> Icons.Outlined.Screenshot
        ToolbarTool.PRIVACY_SHADE -> Icons.Outlined.Blinds
        ToolbarTool.QUICK_NOTE -> Icons.Outlined.EditNote
        ToolbarTool.SNIPPETS -> Icons.AutoMirrored.Outlined.TextSnippet
        ToolbarTool.TEXT_EDIT -> Icons.Outlined.EditNote
        ToolbarTool.TRACKPAD -> SymbolIcons.TrackpadInput
        ToolbarTool.ONE_HANDED -> SymbolIcons.MobileHandLeft
        ToolbarTool.SPLIT -> Icons.Outlined.VerticalSplit
        ToolbarTool.FLOATING -> Icons.Outlined.PictureInPictureAlt
        ToolbarTool.PERSISTENT -> Icons.Outlined.PushPin
        ToolbarTool.RESIZE -> Icons.Outlined.AspectRatio
        ToolbarTool.SETTINGS -> Icons.Outlined.Settings
        ToolbarTool.FLASHLIGHT -> Icons.Outlined.FlashlightOn
        ToolbarTool.COMPASS -> Icons.Outlined.Explore
        ToolbarTool.LEVEL -> SymbolIcons.ToolsLevel
        ToolbarTool.UNDO -> Icons.AutoMirrored.Outlined.Undo
        ToolbarTool.REDO -> Icons.AutoMirrored.Outlined.Redo
        ToolbarTool.MOON_PHASE -> Icons.Outlined.DarkMode
        ToolbarTool.WEATHER -> SymbolIcons.PartlyCloudyDay
        ToolbarTool.CALENDAR -> Icons.Outlined.CalendarMonth
        ToolbarTool.INCOGNITO -> Icons.Outlined.VisibilityOff
        ToolbarTool.SELECTION_ACTIONS -> Icons.Outlined.SmartButton
        // The plain translate glyph, which Translate wears too. On an Indic
        // phonetic layout the keyboard draws SymbolIcons.TranslateIndic in its
        // place, so the two stop looking alike where both are offered (see
        // [LocalPhoneticIndic]).
        ToolbarTool.PHONETIC_ENGLISH -> Icons.Outlined.Translate
        ToolbarTool.POWER_SAVING -> Icons.Outlined.BatterySaver
        ToolbarTool.THEMES -> Icons.Outlined.Palette
        ToolbarTool.AUTOCORRECT -> Icons.Outlined.Spellcheck
        ToolbarTool.SOUND_HAPTICS -> Icons.Outlined.Vibration
        ToolbarTool.NUMPAD -> Icons.Outlined.Dialpad
        ToolbarTool.HANDWRITING -> Icons.Outlined.Draw
        ToolbarTool.CAMERA -> Icons.Outlined.PhotoCamera
        ToolbarTool.DICTIONARY -> SymbolIcons.Dictionary
        ToolbarTool.VOCABULARY -> Icons.Outlined.AutoStories
        ToolbarTool.LEARN_FROM_TEXT -> Icons.Outlined.School
        ToolbarTool.TRANSLATE -> Icons.Outlined.Translate
        ToolbarTool.GIF -> SymbolIcons.GifBox
        ToolbarTool.STICKER -> SymbolIcons.Sticker
        ToolbarTool.EMOJI_MIX -> Icons.Outlined.Interests
        ToolbarTool.WEB_SEARCH -> Icons.Outlined.TravelExplore
        ToolbarTool.IMAGE_SEARCH -> Icons.Outlined.ImageSearch
        ToolbarTool.OCR -> SymbolIcons.ConvertToText
        ToolbarTool.QR_SCAN -> Icons.Outlined.QrCodeScanner
        ToolbarTool.DOC_SCAN -> Icons.Outlined.DocumentScanner
        ToolbarTool.VOICE -> Icons.Outlined.Mic
        ToolbarTool.GRAMMAR -> Icons.AutoMirrored.Outlined.FactCheck
        ToolbarTool.WIKIPEDIA -> Icons.Outlined.Public
        ToolbarTool.SYMBOLS -> Icons.Outlined.Functions
        ToolbarTool.CALCULATOR -> Icons.Outlined.Calculate
        ToolbarTool.UNIT_CONVERT -> Icons.Outlined.SwapHoriz
        ToolbarTool.CURRENCY -> Icons.Outlined.CurrencyExchange
        ToolbarTool.QR_GEN -> Icons.Outlined.QrCode2
        ToolbarTool.PASSWORD_GEN -> Icons.Outlined.Password
        ToolbarTool.TEXT_REPEAT -> Icons.Outlined.Repeat
        ToolbarTool.READ_BACK -> Icons.AutoMirrored.Outlined.VolumeUp
        ToolbarTool.TYPING_TEST -> Icons.Outlined.Speed
        // The typing test has the plain speedometer; this is the one with the signal.
        ToolbarTool.SPEED_TEST -> Icons.Outlined.NetworkCheck
        // The settings app's own Statistics glyph, so the tool and the row it
        // opens look like the same thing.
        ToolbarTool.STATISTICS -> Icons.Outlined.QueryStats
        ToolbarTool.FIND_REPLACE -> Icons.Outlined.FindReplace
        ToolbarTool.NOTES -> Icons.AutoMirrored.Outlined.StickyNote2
        ToolbarTool.TODO -> Icons.Outlined.Checklist
        ToolbarTool.WM_PASS -> Icons.Outlined.Key
        ToolbarTool.LOCATION -> Icons.Outlined.LocationOn
        ToolbarTool.CLOCK -> Icons.Outlined.Schedule
        ToolbarTool.HOME_ASSISTANT -> Icons.Outlined.HomeIotDevice
        ToolbarTool.SEND_FILES -> Icons.AutoMirrored.Outlined.Send
        // The open folder: Folder alone is the gallery's albums and the picker's rows.
        ToolbarTool.FILES -> Icons.Outlined.FolderOpen
        ToolbarTool.TIMESTAMP -> Icons.Outlined.EditCalendar
        ToolbarTool.GALLERY -> Icons.Outlined.PhotoLibrary
        ToolbarTool.MEDIA_CONTROL -> Icons.Outlined.MusicNote
        // A waveform, not a note: the note is Media controls, and this one listens.
        ToolbarTool.MUSIC_ID -> Icons.Outlined.GraphicEq
        ToolbarTool.KDE_CONNECT -> Icons.Outlined.Phonelink
        ToolbarTool.PLUGINS -> Icons.Outlined.Extension
        ToolbarTool.APP_LAUNCHER -> Icons.Outlined.Apps
        ToolbarTool.AI -> Icons.Outlined.AutoAwesome
        ToolbarTool.FANCY -> Icons.Outlined.TextFormat
        ToolbarTool.CUSTOM_LAYOUT -> Icons.Outlined.GridView
        ToolbarTool.MODES -> Icons.Outlined.Tune
        ToolbarTool.CURSOR_LEFT -> Icons.AutoMirrored.Outlined.KeyboardArrowLeft
        ToolbarTool.CURSOR_RIGHT -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
        ToolbarTool.CURSOR_WORD_LEFT -> Icons.Outlined.KeyboardDoubleArrowLeft
        ToolbarTool.CURSOR_WORD_RIGHT -> Icons.Outlined.KeyboardDoubleArrowRight
        ToolbarTool.CURSOR_UP -> Icons.Outlined.KeyboardArrowUp
        ToolbarTool.CURSOR_DOWN -> Icons.Outlined.KeyboardArrowDown
        ToolbarTool.CURSOR_HOME -> Icons.Outlined.FirstPage
        ToolbarTool.CURSOR_END -> Icons.AutoMirrored.Outlined.LastPage
        ToolbarTool.HIDE_KEYBOARD -> Icons.Outlined.KeyboardHide
        ToolbarTool.LANGUAGE -> Icons.Outlined.Language
        ToolbarTool.PAGE_UP -> Icons.Outlined.KeyboardDoubleArrowUp
        ToolbarTool.PAGE_DOWN -> Icons.Outlined.KeyboardDoubleArrowDown
        ToolbarTool.SELECT_WORD -> Icons.Outlined.HighlightAlt
        ToolbarTool.SELECT_LINE -> Icons.Outlined.ViewHeadline
        // Not Outlined.SelectAll, which Selection mode already draws: the two
        // sit side by side in the same toolbox group, so they cannot share a
        // glyph. This is the text box with handles.
        ToolbarTool.SELECT_ALL -> Icons.Outlined.FormatShapes
        ToolbarTool.CLEAR_FIELD -> Icons.Outlined.DeleteSweep
        ToolbarTool.SELECT_MODE -> Icons.Outlined.SelectAll
        ToolbarTool.COPY -> Icons.Outlined.ContentCopy
        ToolbarTool.CUT -> Icons.Outlined.ContentCut
        ToolbarTool.PASTE -> Icons.Outlined.ContentPaste
        // Material's own "quick reply": a speech bubble with a flash in it,
        // which is exactly what this tool does.
        ToolbarTool.AI_REPLY -> Icons.Outlined.Quickreply
    }

    /**
     * `CUSTOM` returns the plain return arrow so the map has an entry for
     * every action, but the enter key never draws it — an app-supplied
     * actionLabel is rendered as its own text.
     */
    fun forEnterAction(action: EnterAction): ImageVector = when (action) {
        EnterAction.SEARCH -> Icons.Outlined.Search
        EnterAction.SEND -> Icons.AutoMirrored.Outlined.Send
        EnterAction.GO -> Icons.AutoMirrored.Outlined.ArrowForward
        EnterAction.NEXT -> Icons.AutoMirrored.Outlined.KeyboardTab
        EnterAction.PREVIOUS -> Icons.AutoMirrored.Outlined.ArrowBack
        EnterAction.DONE -> Icons.Outlined.Check
        EnterAction.DEFAULT, EnterAction.CUSTOM -> Icons.AutoMirrored.Outlined.KeyboardReturn
    }

    /** The slot an enter action draws, or null for `CUSTOM` (which draws text). */
    fun enterActionSlot(action: EnterAction): String? = when (action) {
        EnterAction.SEARCH -> IconSlots.KEY_ENTER_SEARCH
        EnterAction.SEND -> IconSlots.KEY_ENTER_SEND
        EnterAction.GO -> IconSlots.KEY_ENTER_GO
        EnterAction.NEXT -> IconSlots.KEY_ENTER_NEXT
        EnterAction.PREVIOUS -> IconSlots.KEY_ENTER_PREVIOUS
        EnterAction.DONE -> IconSlots.KEY_ENTER_DONE
        EnterAction.DEFAULT -> IconSlots.KEY_ENTER
        EnterAction.CUSTOM -> null
    }

    /** Category → tab icon; an unknown category falls back to the smiley. */
    fun forEmojiCategory(category: String): ImageVector = when (category) {
        "smileys" -> Icons.Outlined.EmojiEmotions
        "people" -> Icons.Outlined.EmojiPeople
        "animals" -> Icons.Outlined.Pets
        "nature" -> Icons.Outlined.EmojiNature
        "food" -> Icons.Outlined.Fastfood
        "travel" -> Icons.Outlined.DirectionsCar
        "activities" -> Icons.Outlined.SportsSoccer
        "objects" -> Icons.Outlined.EmojiObjects
        "symbols" -> Icons.Outlined.EmojiSymbols
        "flags" -> Icons.Outlined.EmojiFlags
        else -> Icons.Outlined.EmojiEmotions
    }

    /**
     * Slot id → built-in vector, for every slot [IconSlots] knows.
     *
     * Lazy, and deliberately so: filling it forces every one of ~90 Material
     * vectors to be built, and the first thing to ask for an icon is the
     * keyboard's first frame. [warm] pulls that work onto a background thread
     * before then; if a frame still beats it, the map just builds on demand.
     */
    val bySlot: Map<String, ImageVector> by lazy { buildDefaults() }

    /** Builds [bySlot]. Call from a background thread; cheap and idempotent after. */
    fun warm() {
        bySlot
    }

    private fun buildDefaults(): Map<String, ImageVector> = buildMap {
        for (tool in ToolbarTool.entries) put(IconSlots.forTool(tool), forTool(tool))

        // Outline when off, filled when armed, filled over a bar when locked:
        // Gboard's three faces, drawn from Material Symbols.
        put(IconSlots.KEY_SHIFT, SymbolIcons.Shift)
        put(IconSlots.KEY_SHIFT_ON, SymbolIcons.ShiftFilled)
        put(IconSlots.KEY_SHIFT_LOCK, SymbolIcons.ShiftLockFilled)
        put(IconSlots.KEY_CAPS_LOCK, Icons.Outlined.KeyboardCapslock)
        put(IconSlots.KEY_BACKSPACE, Icons.AutoMirrored.Outlined.Backspace)
        put(IconSlots.KEY_FORWARD_DELETE, KeyboardIcons.ForwardDelete)
        put(IconSlots.KEY_GLOBE, Icons.Outlined.Language)
        put(IconSlots.KEY_INPUT_METHOD_PICKER, Icons.Outlined.Keyboard)
        put(IconSlots.KEY_EMOJI, Icons.Outlined.EmojiEmotions)
        // The same glyphs the cursor tools wear, so the row and the toolbox agree.
        put(IconSlots.KEY_ARROW_LEFT, Icons.AutoMirrored.Outlined.KeyboardArrowLeft)
        put(IconSlots.KEY_ARROW_UP, Icons.Outlined.KeyboardArrowUp)
        put(IconSlots.KEY_ARROW_DOWN, Icons.Outlined.KeyboardArrowDown)
        put(IconSlots.KEY_ARROW_RIGHT, Icons.AutoMirrored.Outlined.KeyboardArrowRight)
        put(IconSlots.KEY_TAB, Icons.AutoMirrored.Outlined.KeyboardTab)
        for (action in EnterAction.entries) {
            val slot = enterActionSlot(action) ?: continue
            put(slot, forEnterAction(action))
        }

        put(IconSlots.CHROME_TOOLBOX, Icons.Outlined.GridView)
        put(IconSlots.CHROME_PANEL_BACK, Icons.Outlined.ChevronLeft)
        put(IconSlots.CHROME_SUGGESTIONS_EXPAND, Icons.Outlined.ChevronRight)
        put(IconSlots.CHROME_EMOJI_SHORTCUT, Icons.Outlined.EmojiEmotions)
        put(IconSlots.CHROME_SEARCH_CLOSE, Icons.Outlined.Close)
        put(IconSlots.CHROME_INCOGNITO, KeyboardIcons.Incognito)
        put(IconSlots.CHROME_POWER_SAVING, Icons.Outlined.BatterySaver)
        put(IconSlots.CHROME_SHOW_KEYS, Icons.Outlined.Keyboard)
        put(IconSlots.CHROME_HIDE_KEYS, Icons.Outlined.KeyboardHide)
        put(IconSlots.CHROME_TOOLBOX_SEARCH, Icons.Outlined.Search)
        // The user's own buttons (#1213, #1236): a slot per switch and page.
        putAll(customButtonSlotIcons())

        put(IconSlots.EMOJI_TAB_SEARCH, Icons.Outlined.Search)
        put(IconSlots.EMOJI_TAB_RECENT, Icons.Outlined.Schedule)
        put(IconSlots.EMOJI_TAB_MOST_USED, Icons.Outlined.BarChart)
        for (category in IconSlots.EMOJI_CATEGORIES) {
            put(IconSlots.forEmojiCategory(category), forEmojiCategory(category))
        }
    }

    /**
     * The built-in vector for [slot]. Emoji categories the catalog gained
     * after this table was written have no entry, so they fall back to the
     * smiley rather than to nothing.
     */
    fun forSlot(slot: String): ImageVector? =
        bySlot[slot] ?: if (slot.startsWith("emoji_tab.")) Icons.Outlined.EmojiEmotions else null

    /**
     * The app's own alternative looks for a slot, picked per slot from the
     * Icons screen and stored as a `v:<name>` override (see
     * `IconOverrides.VARIANT_PREFIX`).
     *
     * Only where Material Symbols has a real second drawing of the same idea —
     * the GIF tool's lettering without its box, a filled gear, a waveform for
     * voice — not a different idea that happens to fit. A slot with nothing
     * here offers no variants, and the picker hides the row for it.
     *
     * The names are part of the stored setting and of the pack format (a pack's
     * `tool.gif@text` is offered under the same name), so they never change
     * once shipped. Lambdas, so building the table builds no vectors: only the
     * picker and a slot actually set to a variant ever draw one.
     */
    private val variants: Map<String, Map<String, () -> ImageVector>> by lazy {
        val emoji: Map<String, () -> ImageVector> = linkedMapOf(
            VARIANT_SIMPLE to { Icons.Outlined.SentimentSatisfied },
        )
        mapOf(
            IconSlots.forTool(ToolbarTool.GIF) to linkedMapOf(
                // gif_2: the lettering at full width, the look most keyboards use.
                VARIANT_TEXT to { SymbolIcons.Gif2 },
                // gif: the box's own letters with the box taken away.
                VARIANT_TEXT_SMALL to { SymbolIcons.Gif },
            ),
            IconSlots.forTool(ToolbarTool.EMOJI) to emoji,
            IconSlots.KEY_EMOJI to emoji,
            IconSlots.CHROME_EMOJI_SHORTCUT to emoji,
            // The bare clipboard, which Paste also wears — see forTool for why
            // that is not the default.
            IconSlots.forTool(ToolbarTool.CLIPBOARD) to linkedMapOf(
                VARIANT_PLAIN to { Icons.Outlined.ContentPaste },
            ),
            IconSlots.forTool(ToolbarTool.SETTINGS) to linkedMapOf(
                VARIANT_FILLED to { SymbolIcons.SettingsFilled },
            ),
            IconSlots.forTool(ToolbarTool.VOICE) to linkedMapOf(
                VARIANT_FILLED to { SymbolIcons.MicFilled },
                VARIANT_WAVE to { Icons.Outlined.GraphicEq },
            ),
            IconSlots.forTool(ToolbarTool.WEB_SEARCH) to linkedMapOf(
                VARIANT_MAGNIFIER to { Icons.Outlined.Search },
            ),
            IconSlots.forTool(ToolbarTool.THEMES) to linkedMapOf(
                VARIANT_BRUSH to { Icons.Outlined.Brush },
            ),
            IconSlots.forTool(ToolbarTool.SOUND_HAPTICS) to linkedMapOf(
                VARIANT_SPEAKER to { Icons.AutoMirrored.Outlined.VolumeUp },
            ),
            IconSlots.forTool(ToolbarTool.AI) to linkedMapOf(
                VARIANT_ROBOT to { SymbolIcons.SmartToy },
            ),
            IconSlots.forTool(ToolbarTool.HIDE_KEYBOARD) to linkedMapOf(
                VARIANT_CHEVRON to { Icons.Outlined.KeyboardArrowDown },
            ),
            // The hat and glasses the toolbar's incognito badge already wears.
            IconSlots.forTool(ToolbarTool.INCOGNITO) to linkedMapOf(
                VARIANT_MASK to { KeyboardIcons.Incognito },
            ),
        )
    }

    /** The names of [slot]'s built-in variants, in the order the picker shows them. */
    fun variantNames(slot: String): List<String> = variants[slot]?.keys?.toList().orEmpty()

    /** [slot]'s built-in variant called [name], or null when it has none by that name. */
    fun variant(slot: String, name: String): ImageVector? = variants[slot]?.get(name)?.invoke()

    const val VARIANT_TEXT = "text"
    const val VARIANT_TEXT_SMALL = "text_small"
    const val VARIANT_SIMPLE = "simple"
    const val VARIANT_PLAIN = "plain"
    const val VARIANT_FILLED = "filled"
    const val VARIANT_WAVE = "wave"
    const val VARIANT_MAGNIFIER = "magnifier"
    const val VARIANT_BRUSH = "brush"
    const val VARIANT_SPEAKER = "speaker"
    const val VARIANT_ROBOT = "robot"
    const val VARIANT_CHEVRON = "chevron"
    const val VARIANT_MASK = "mask"
}
