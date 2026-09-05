package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.icons.IconSlots
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
        ToolbarTool.CLIPBOARD -> Icons.Outlined.ContentPaste
        ToolbarTool.SNIPPETS -> Icons.AutoMirrored.Outlined.TextSnippet
        ToolbarTool.TEXT_EDIT -> Icons.Outlined.EditNote
        ToolbarTool.TRACKPAD -> Icons.Outlined.OpenWith
        ToolbarTool.ONE_HANDED -> Icons.Outlined.Smartphone
        ToolbarTool.SPLIT -> Icons.Outlined.VerticalSplit
        ToolbarTool.FLOATING -> Icons.Outlined.PictureInPictureAlt
        ToolbarTool.RESIZE -> Icons.Outlined.AspectRatio
        ToolbarTool.SETTINGS -> Icons.Outlined.Settings
        ToolbarTool.FLASHLIGHT -> Icons.Outlined.FlashlightOn
        ToolbarTool.COMPASS -> Icons.Outlined.Explore
        ToolbarTool.LEVEL -> Icons.Outlined.Straighten
        ToolbarTool.UNDO -> Icons.AutoMirrored.Outlined.Undo
        ToolbarTool.REDO -> Icons.AutoMirrored.Outlined.Redo
        ToolbarTool.MOON_PHASE -> Icons.Outlined.DarkMode
        ToolbarTool.WEATHER -> Icons.Outlined.WbSunny
        ToolbarTool.CALENDAR -> Icons.Outlined.CalendarMonth
        ToolbarTool.INCOGNITO -> Icons.Outlined.VisibilityOff
        ToolbarTool.POWER_SAVING -> Icons.Outlined.BatterySaver
        ToolbarTool.THEMES -> Icons.Outlined.Palette
        ToolbarTool.AUTOCORRECT -> Icons.Outlined.Spellcheck
        ToolbarTool.SOUND_HAPTICS -> Icons.Outlined.Vibration
        ToolbarTool.NUMPAD -> Icons.Outlined.Dialpad
        ToolbarTool.HANDWRITING -> Icons.Outlined.Draw
        ToolbarTool.CAMERA -> Icons.Outlined.PhotoCamera
        ToolbarTool.DICTIONARY -> Icons.AutoMirrored.Outlined.MenuBook
        ToolbarTool.TRANSLATE -> Icons.Outlined.Translate
        ToolbarTool.GIF -> Icons.Outlined.GifBox
        ToolbarTool.STICKER -> Icons.AutoMirrored.Outlined.StickyNote2
        ToolbarTool.WEB_SEARCH -> Icons.Outlined.TravelExplore
        ToolbarTool.IMAGE_SEARCH -> Icons.Outlined.ImageSearch
        ToolbarTool.OCR -> Icons.Outlined.TextFields
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
        ToolbarTool.TYPING_TEST -> Icons.Outlined.Speed
        ToolbarTool.MEDIA_CONTROL -> Icons.Outlined.MusicNote
        ToolbarTool.PLUGINS -> Icons.Outlined.Extension
        ToolbarTool.APP_LAUNCHER -> Icons.Outlined.Apps
        ToolbarTool.AI -> Icons.Outlined.AutoAwesome
        ToolbarTool.FANCY -> Icons.Outlined.TextFormat
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
        ToolbarTool.PAGE_UP -> Icons.Outlined.KeyboardDoubleArrowUp
        ToolbarTool.PAGE_DOWN -> Icons.Outlined.KeyboardDoubleArrowDown
        ToolbarTool.SELECT_WORD -> Icons.Outlined.HighlightAlt
        ToolbarTool.SELECT_LINE -> Icons.Outlined.ViewHeadline
        ToolbarTool.SELECT_MODE -> Icons.Outlined.SelectAll
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

        put(IconSlots.KEY_SHIFT, KeyboardIcons.Shift)
        put(IconSlots.KEY_SHIFT_ON, KeyboardIcons.ShiftFilled)
        put(IconSlots.KEY_SHIFT_LOCK, KeyboardIcons.ShiftLock)
        put(IconSlots.KEY_BACKSPACE, Icons.AutoMirrored.Outlined.Backspace)
        put(IconSlots.KEY_FORWARD_DELETE, KeyboardIcons.ForwardDelete)
        put(IconSlots.KEY_GLOBE, Icons.Outlined.Language)
        put(IconSlots.KEY_INPUT_METHOD_PICKER, Icons.Outlined.Keyboard)
        put(IconSlots.KEY_EMOJI, Icons.Outlined.EmojiEmotions)
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
}
