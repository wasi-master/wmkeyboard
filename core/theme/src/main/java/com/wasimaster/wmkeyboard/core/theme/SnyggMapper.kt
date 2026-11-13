package com.wasimaster.wmkeyboard.core.theme

import kotlin.math.roundToInt

/**
 * A parsed stylesheet as a [ThemeSpec].
 *
 * The rule that shapes every decision here: **a field is written only when the
 * stylesheet says something unambiguous about the same surface.** Most of
 * [ThemeSpec]'s colours are nullable and derive something legible from their
 * neighbours when left null, and that derived value beats a colour scraped from
 * a selector that meant a different part of the keyboard. It beats it twice
 * over, because a user looking at a converted theme cannot tell a value that
 * came from their file from one that was defaulted, and a wrong-but-plausible
 * value sends them to fix the wrong control.
 *
 * What changed, and why there is now so much more of it: the first version of
 * this mapper read seven elements out of the ninety-odd a real stylesheet
 * names, so a converted theme kept its keys and lost its toolbar, its
 * suggestion chips, its panels and its emoji board. Every element below has a
 * genuine counterpart in [ThemeSpec] — the ones that still do not (the window
 * move handle, the clipboard's dialogs, the floating-window furniture) are
 * still reported in `dropped` rather than approximated.
 *
 * ### Everything is judged against what is behind it
 *
 * Borderless themes are built out of transparent surfaces. A colour taken from
 * one has to be composited onto the board before it can be asked whether it is
 * readable or visible, or a perfectly good theme gets "corrected" into an
 * unreadable one — see [composite].
 */
internal class SnyggMapper(private val style: Stylesheet) {

    @Suppress("LongMethod")
    fun convert(
        name: String,
        id: String,
        night: Boolean,
        files: Map<String, ByteArray>,
        dropped: MutableSet<FlexUnsupported>,
    ): ConvertedTheme? {
        val board = style.base(EL_BOARD)
        val key = style.base(EL_KEY)
        val boardBackground = color(board, PROP_BACKGROUND)
        val keyBackground = color(key, PROP_BACKGROUND)
        // A sheet that names neither surface has told us nothing worth calling
        // a theme, and an all-defaults ThemeSpec would look like the app lost
        // the file rather than like the file was empty.
        if (boardBackground == null && keyBackground == null) return null

        val resolvedBoard = boardBackground ?: ThemeSpec(id = "", name = "").boardBackground
        val resolvedKey = keyBackground ?: resolvedBoard
        // What a key label actually sits on: the key where it is opaque, the
        // board showing through where it is not.
        val keySurface = composite(resolvedKey, resolvedBoard)
        val (shape, radius) = snyggShape(key?.value(PROP_SHAPE), dropped) ?: (null to null)

        val modifier = modifierRule()
        val enter = enterRule()
        val enterBackground = color(enter, PROP_BACKGROUND) ?: resolvedKey
        // The emoji board's bubble is the same bubble the keys use, so it
        // answers where a sheet styles one and not the other.
        val popup = style.firstOf(EL_POPUP, EL_EMOJI_POPUP)
        val hint = style.base(EL_HINT)
        val tool = style.base(EL_TOOL)
        val toolToggle = style.base(EL_TOOL_TOGGLE)
        val candidate = style.base(EL_CANDIDATE)
        val chip = chipRule()
        val chipActive = style.withAttribute(EL_CHIP, ATTR_STATE, STATE_ACTIVE)
        val card = style.base(EL_CARD)
        val cardLifted = style.base(EL_CARD_LIFTED)
        val sheet = style.base(EL_SHEET)

        val images = buildMap {
            key?.value(PROP_IMAGE)?.let { path ->
                FlexTheme.imageBytes(files, path)?.let { put(ASSET_KEY_TEXTURE, it) }
            }
            board?.value(PROP_IMAGE)?.let { path ->
                FlexTheme.imageBytes(files, path)?.let { put(FlexTheme.IMAGE_BACKGROUND, it) }
            }
        }

        val theme = ThemeSpec(
            id = id,
            name = name,
            dark = night,
            boardBackground = resolvedBoard,
            keyBackground = resolvedKey,
            keyText = textColorOn(key?.value(PROP_FOREGROUND), keySurface, dropped),
            // Left to derive unless the sheet styles the pressed key itself.
            pressedKeyBackground = color(style.base(EL_KEY, PRESSED), PROP_BACKGROUND),
            modifierKeyBackground = color(modifier, PROP_BACKGROUND) ?: resolvedKey,
            modifierKeyText = modifier?.value(PROP_FOREGROUND)?.let {
                textColorOn(it, composite(color(modifier, PROP_BACKGROUND) ?: resolvedKey, resolvedBoard), dropped)
            },
            enterKeyBackground = enterBackground,
            enterKeyText = enter?.value(PROP_FOREGROUND)
                ?.let { textColorOn(it, composite(enterBackground, resolvedBoard), dropped) }
                ?: onColorFor(composite(enterBackground, resolvedBoard)),
            keyBorderColor = color(key, PROP_BORDER_COLOR),
            keyBorderWidthDp = snyggDp(key?.value(PROP_BORDER_WIDTH)) ?: 0f,
            keyElevationDp = elevationOf(key) ?: 0f,
            keyShadowColor = color(key, PROP_SHADOW_COLOR)?.takeIf { it.isVisible() },
            keyShape = shape ?: KeyShapeKind.ROUNDED,
            keyCornerRadiusDp = radius,
            boldKeyLabels = key?.value(PROP_FONT_WEIGHT)?.contains(BOLD, ignoreCase = true),
            // How the key's own picture fits it. Only meaningful beside one, so
            // it is read off the same rule the picture came from.
            keyTextureScale = snyggContentScale(key?.value(PROP_CONTENT_SCALE)),
            fontScale = scaleFrom(key?.value(PROP_FONT_SIZE), DEFAULT_KEY_SP, KeyFontScaleBounds),
            // The corner hint. A fully transparent hint colour is the sheet
            // saying "no hint tint", which is the derived default here, so it
            // is left null rather than written as an invisible colour.
            hintText = color(hint, PROP_FOREGROUND)?.takeIf { it.isVisible() },
            hintFontScale = scaleFrom(hint?.value(PROP_FONT_SIZE), DEFAULT_HINT_SP, HintFontScaleBounds),
            // Popups.
            // The bottom sheets and the language picker are drawn with the
            // popup's own colours here, so a sheet that styles only those still
            // dresses the bubble.
            popupBackground = color(popup, PROP_BACKGROUND)
                ?: color(sheet, PROP_BACKGROUND),
            popupText = color(popup, PROP_FOREGROUND)
                ?: color(style.base(EL_POPUP_MORE), PROP_FOREGROUND)
                ?: color(style.base(EL_MENU_ROW), PROP_FOREGROUND)
                ?: color(sheet, PROP_FOREGROUND),
            popupBorderColor = color(popup, PROP_BORDER_COLOR),
            popupBorderWidthDp = snyggDp(popup?.value(PROP_BORDER_WIDTH)) ?: 0f,
            popupElevationDp = elevationOf(popup),
            // The focus state of a popup's items: the highlight under the
            // alternate your finger is on, and under a selected menu row.
            popupSelectedBackground = color(style.firstOf(EL_POPUP_ITEM, state = FOCUS), PROP_BACKGROUND),
            popupSelectedText = color(style.firstOf(EL_POPUP_ITEM, state = FOCUS), PROP_FOREGROUND),
            popupShape = shapeName(popup, dropped),
            popupCornerRadiusDp = shapeRadius(popup, dropped),
            // The bar above the keys. The tool icons and the toggle behind them
            // are separate elements upstream and separate fields here, so they
            // map one to one instead of collapsing onto the key colours.
            suggestionBarBackground = color(style.base(EL_TOOLBAR), PROP_BACKGROUND),
            navigationBarBackground = color(style.base(EL_NAV_BAR), PROP_BACKGROUND),
            oneHandedPanelBackground = color(style.base(EL_ONE_HANDED), PROP_BACKGROUND),
            oneHandedPanelIcon = color(style.base(EL_ONE_HANDED), PROP_FOREGROUND),
            toolbarIcon = color(tool, PROP_FOREGROUND)
                ?: color(style.base(EL_PANEL_TOOL), PROP_FOREGROUND)
                ?: color(style.base(EL_INCOGNITO), PROP_FOREGROUND)
                ?: color(board, PROP_FOREGROUND),
            toolCircleBackground = color(tool, PROP_BACKGROUND)
                ?: color(style.base(EL_PANEL_TOOL), PROP_BACKGROUND),
            toolCircleActiveBackground = color(toolToggle, PROP_BACKGROUND),
            toolCircleActiveIcon = color(toolToggle, PROP_FOREGROUND),
            toolElevationDp = elevationOf(toolToggle ?: tool) ?: 0f,
            toolShape = shapeName(tool ?: toolToggle, dropped),
            toolCircleRadiusDp = shapeRadius(tool ?: toolToggle, dropped),
            // A panel heading draws with the strip's colour here, so it is the
            // fallback rather than a field of its own.
            suggestionText = color(candidate, PROP_FOREGROUND)
                ?: color(style.base(EL_PANEL_HEADER), PROP_FOREGROUND)
                ?: color(style.base(EL_EMOJI_KEY), PROP_FOREGROUND),
            secondaryText = color(style.base(EL_SECONDARY_TEXT), PROP_FOREGROUND)?.takeIf { it.isVisible() },
            dividerColor = color(style.base(EL_DIVIDER), PROP_FOREGROUND)?.takeIf { it.isVisible() },
            // Chips and panel cards.
            chipBackground = color(chip, PROP_BACKGROUND)
                ?: color(card, PROP_BACKGROUND)
                ?: color(cardLifted, PROP_BACKGROUND),
            chipText = color(chip, PROP_FOREGROUND)
                ?: color(card, PROP_FOREGROUND)
                ?: color(cardLifted, PROP_FOREGROUND),
            chipActiveBackground = color(chipActive, PROP_BACKGROUND),
            chipActiveText = color(chipActive, PROP_FOREGROUND),
            chipShape = shapeName(chip, dropped),
            chipCornerRadiusDp = shapeRadius(chip, dropped),
            cardElevationDp = elevationOf(card) ?: 0f,
            cardShape = shapeName(card, dropped),
            menuShape = shapeName(sheet, dropped),
            accent = accentColor(enter, resolvedBoard),
            gestureTrailColor = trailColor(),
            keyOverrides = keyOverrides(resolvedKey, resolvedBoard, dropped),
            // A sheet written in Material You roles is a theme that follows the
            // wallpaper in FlorisBoard, so it follows it here too. The switch
            // stays in the editor for anyone who wants today's colours kept.
            followWallpaper = style.wallpaperRoles.isNotEmpty(),
            wallpaperRoles = style.wallpaperRoles,
        )
        return ConvertedTheme(theme, images, fontOf(files, dropped))
    }

    /**
     * The typeface the sheet asks for, when the archive actually carries it.
     *
     * Reported as lost only when it does *not*: a theme that names a family it
     * does not ship is asking for something this keyboard has no way to find,
     * while one that ships the file has lost nothing once it is installed. The
     * two used to read the same, and the message claimed the file "does not
     * contain it" of archives that plainly did.
     */
    private fun fontOf(
        files: Map<String, ByteArray>,
        dropped: MutableSet<FlexUnsupported>,
    ): ConvertedFont? {
        val name = style.requestedFont ?: return null
        val path = style.fontSources[name]
        val bytes = path?.let { FlexTheme.lookUp(files, it) }
        if (bytes == null || bytes.isEmpty()) {
            dropped += FlexUnsupported.FONT
            return null
        }
        return ConvertedFont(name = name, fileName = path.substringAfterLast('/'), bytes = bytes)
    }

    // ---- colour helpers ----

    private fun color(rule: SnyggRule?, property: String): Long? = snyggColor(rule?.value(property))

    /**
     * A scraped text colour, unless it would be unreadable where it landed.
     *
     * Snygg styles more surfaces than this keyboard has, so a foreground can
     * arrive from a rule that meant a different background. Below the ratio
     * where text stops being legible, the derived colour is used instead and the
     * substitution is reported, because a theme whose keys cannot be read is the
     * one failure a user cannot work around in the editor.
     *
     * [background] must already be the opaque colour behind the text. Measuring
     * against a transparent key is what used to reject the labels of every
     * borderless theme and replace them with their opposite.
     */
    private fun textColorOn(raw: String?, background: Long, dropped: MutableSet<FlexUnsupported>): Long {
        val scraped = snyggColor(raw) ?: return onColorFor(background)
        // Text the sheet made transparent is not a colour to check, it is the
        // sheet declining to set one.
        if (!scraped.isVisible()) return onColorFor(background)
        val seen = composite(scraped, background)
        if (contrastRatio(seen, background) >= MIN_CONTRAST) return scraped
        dropped += FlexUnsupported.LOW_CONTRAST_FALLBACK
        return onColorFor(background)
    }

    /**
     * The theme's one accent: the shift tint, the glide trail, the active tool.
     *
     * Taken from the elements that genuinely carry a theme's highlight colour,
     * in the order they are worth trusting — the glide trail is only ever the
     * accent, the enter key is the accent on most themes, the focused emoji tab
     * and the active toolbar toggle are the next best. Composited onto the
     * board at the end: a transparent accent paints an invisible trail and an
     * invisible armed shift, which is the one value here that must never be
     * see-through.
     */
    private fun accentColor(enter: SnyggRule?, board: Long): Long {
        val candidates = listOfNotNull(
            color(style.base(EL_GLIDE), PROP_FOREGROUND),
            color(enter, PROP_BACKGROUND),
            color(style.base(EL_EMOJI_TAB, FOCUS), PROP_FOREGROUND),
            color(style.base(EL_TOOL_TOGGLE), PROP_BACKGROUND),
            color(style.base(EL_CANDIDATE), PROP_FOREGROUND),
            color(style.base(EL_PANEL_BUTTON), PROP_BACKGROUND),
            color(style.base(EL_PANEL_BUTTON), PROP_FOREGROUND),
        )
        val pick = candidates.firstOrNull { it.isVisible() }
            ?: return ThemeSpec(id = "", name = "").accent
        return composite(pick, board)
    }

    /**
     * The rule that says what a chip looks like here.
     *
     * One field covers several things that snygg styles separately: the clip
     * suggestion, the tool tiles, the clipboard's filter pills and its cards.
     * They rarely disagree about colour, but they do disagree about being
     * *there* — the clip suggestion sits on the bar and is usually transparent,
     * while the clipboard card is a real surface. Taking whichever came first
     * meant the transparent one won and every clipboard card went see-through.
     *
     * So the first rule that paints something visible wins, and a rule that
     * only declares transparency is the fallback rather than the answer.
     */
    private fun chipRule(): SnyggRule? {
        val candidates = listOfNotNull(
            style.base(EL_CHIP),
            style.base(EL_TILE),
            style.base(EL_CARD),
        )
        return candidates.firstOrNull { color(it, PROP_BACKGROUND)?.isVisible() == true }
            ?: candidates.firstOrNull()
    }

    /** The glide trail's own colour, when the sheet gives it one. */
    private fun trailColor(): Long? {
        val glide = style.base(EL_GLIDE) ?: return null
        return (color(glide, PROP_FOREGROUND) ?: color(glide, PROP_BACKGROUND))?.takeIf { it.isVisible() }
    }

    // ---- shape helpers ----

    /**
     * A surface's lift, in dp.
     *
     * `shadow-elevation: inherit` is a real value in the wild and means "keep
     * whatever the parent had", which here is the default, so it reads as
     * nothing rather than as zero.
     */
    private fun elevationOf(rule: SnyggRule?): Float? =
        snyggDp(rule?.value(PROP_ELEVATION))?.takeIf { it.isFinite() && it >= 0f }

    private fun shapeName(rule: SnyggRule?, dropped: MutableSet<FlexUnsupported>): String? =
        snyggShape(rule?.value(PROP_SHAPE), dropped)?.first?.name

    private fun shapeRadius(rule: SnyggRule?, dropped: MutableSet<FlexUnsupported>): Int? =
        snyggShape(rule?.value(PROP_SHAPE), dropped)?.second

    /**
     * A font size as the multiplier this app stores, against the size
     * FlorisBoard's own themes use for that element.
     *
     * Clamped rather than dropped when it lands outside the range: a theme
     * asking for 40sp labels means "big", and the biggest this keyboard offers
     * is closer to that than the default is.
     */
    private fun scaleFrom(raw: String?, defaultSp: Float, bounds: ClosedFloatingPointRange<Float>): Float? {
        val size = snyggDp(raw)?.takeIf { it > 0f } ?: return null
        val scale = size / defaultSp
        // Within a rounding step of 1 is the default, and writing it would turn
        // the global slider off for no gain.
        if (kotlin.math.abs(scale - 1f) < SCALE_EPSILON) return null
        return scale.coerceIn(bounds)
    }

    // ---- per-key styles ----

    /**
     * Per-key styles, from the rules that name one key and nothing else.
     *
     * This is where snygg's `key[code=…]` and `key[output=…]` selectors belong:
     * without them they would all collapse onto the one modifier colour, and a
     * theme that paints six keys differently would come across painting one.
     *
     * Both spellings are read. A sheet that writes both for the same key is
     * agreeing with itself, and the code wins the tie — it is the older form
     * and the one every dialect has written.
     *
     * Only the resting style of a key is read. A rule carrying a state
     * (`key[code=10]:pressed`) or another attribute (`key[code=-11][shiftstate=
     * `caps_lock`]`) describes a moment, not a key — see [Stylesheet.byCode].
     */
    private fun keyOverrides(
        keyBackground: Long,
        board: Long,
        dropped: MutableSet<FlexUnsupported>,
    ): Map<String, KeyOverride> {
        val byCode = style.styledCodes(EL_KEY).map { code ->
            overrideIdFor(code) to style.forCode(EL_KEY, code)
        }
        // The v2 way of naming the same keys. Second, so a sheet that writes
        // both says the same thing twice rather than disagreeing with itself,
        // and the code — the older, more widely written form — wins a tie.
        val byOutput = style.styledOutputs(EL_KEY).map { output ->
            overrideIdForOutput(output) to style.forOutput(EL_KEY, output)
        }
        return (byOutput + byCode).mapNotNull { (id, rule) ->
            if (id == null || rule == null) return@mapNotNull null
            val fill = color(rule, PROP_BACKGROUND)
            val surface = composite(fill ?: keyBackground, board)
            val override = KeyOverride(
                background = fill,
                text = rule.value(PROP_FOREGROUND)?.let { textColorOn(it, surface, dropped) },
                border = color(rule, PROP_BORDER_COLOR),
                labelScale = scaleFrom(rule.value(PROP_FONT_SIZE), DEFAULT_KEY_SP, KEY_OVERRIDE_LABEL_SCALE_RANGE),
                bold = rule.value(PROP_FONT_WEIGHT)?.contains(BOLD, ignoreCase = true),
                // A round enter key among soft rectangles is a whole family of
                // themes' signature, and the one thing a per-key style could
                // not say until now.
                shape = snyggShape(rule.value(PROP_SHAPE), dropped)?.first?.name,
            )
            if (override.isEmpty) null else id to override
        }.toMap()
    }

    /**
     * The rule that styles the function keys, or null.
     *
     * Shift first, then delete, then the layout-switch keys: a sheet that gives
     * the function keys a colour of their own nearly always names shift in the
     * same rule, and the first one that carries a background is the one that
     * answers what a modifier key looks like.
     */
    private fun modifierRule(): SnyggRule? =
        MODIFIER_CODES.firstNotNullOfOrNull { code ->
            style.forCode(EL_KEY, code)?.takeIf {
                it.value(PROP_BACKGROUND) != null || it.value(PROP_FOREGROUND) != null
            }
        }

    /**
     * The rule that styles the enter key, by code or by output.
     *
     * Codes first because every dialect has written them and a sheet that uses
     * both agrees with itself. The output is what a sheet written against snygg
     * v2 alone names it by — FlorisBoard's own default themes carry
     * `key[output=`@k3:action/enter`]` beside `key[code=10]` — and reading only
     * the code left such a theme with no enter colour at all.
     */
    private fun enterRule(): SnyggRule? =
        ENTER_CODES.firstNotNullOfOrNull { style.forCode(EL_KEY, it) }
            ?: style.forOutput(EL_KEY, OUTPUT_ENTER)

    internal companion object {

        const val PRESSED = "pressed"
        const val FOCUS = "focus"
        const val BOLD = "bold"
        const val ATTR_STATE = "state"
        const val STATE_ACTIVE = "active"

        /**
         * Below this a label stops being readable on its key.
         *
         * [Readability.POOR_CONTRAST], which is this app's own line for "a user
         * genuinely cannot read that", and deliberately below the WCAG
         * large-text floor of 3:1. FlorisBoard does no contrast correction at
         * all, so every colour this replaces is a colour the theme's author saw
         * and shipped; holding them to 3:1 rewrote the enter-key labels of
         * themes that read perfectly well, and reported the rewrite each time.
         * The guard is here for a file that is actually broken, not to second-
         * guess a designer.
         */
        const val MIN_CONTRAST = Readability.POOR_CONTRAST

        /** The sizes FlorisBoard's own themes give these elements. */
        const val DEFAULT_KEY_SP = 22f
        const val DEFAULT_HINT_SP = 12f
        const val SCALE_EPSILON = 0.03f

        /** Mirrors `KeyFontScaleRange` in `:core:settings`, which this module cannot see. */
        val KeyFontScaleBounds = 0.7f..2.0f
        val HintFontScaleBounds = 0.5f..2.0f

        /** In the order a sheet is worth asking what a function key looks like. */
        val MODIFIER_CODES = listOf(-11, -7, -201, -202, -203, -13)
        val ENTER_CODES = listOf(10, 13)

        /** The one output id read outside the override table; see [enterRule]. */
        const val OUTPUT_ENTER = "action/enter"

        /**
         * A snygg v2 `output=` id as a [ThemeSpec.keyOverrides] key.
         *
         * The ids are FlorisBoard's own (`ImeActions`), with the extension
         * namespace already off — see [snyggOutputId]. Only the ones with a
         * genuine counterpart here are listed; an action this keyboard has no
         * key for costs its rule, which is the same rule [overrideIdFor]
         * follows for codes.
         *
         * `action/` is not the only shape an output takes: a character key's
         * output is the character, so a one-character value styles that letter
         * across every layout, exactly as a printable code does.
         */
        fun overrideIdForOutput(output: String): String? = when (output) {
            "action/enter" -> "ENTER"
            "action/backspace", "action/delete", "action/backspace_word", "action/delete_word" ->
                "DELETE"
            "action/show_input_method_picker" -> "INPUTMETHODPICKER"
            "action/switch_to_next_subtype", "action/switch_to_prev_subtype",
            "action/switch_to_next_input_method", "action/switch_to_prev_input_method",
            -> "LANGUAGESWITCH"
            "action/show_media_panel" -> "EMOJI"
            " " -> "SPACE"
            else -> when {
                output.startsWith("action/") || output.isEmpty() -> null
                // One character, which is the only literal output a per-key
                // style can follow across layouts.
                output.codePointCount(0, output.length) == 1 -> output.lowercase()
                else -> null
            }
        }

        /**
         * The name a [ThemeSpec.keyOverrides] entry uses, for a foreign key code.
         *
         * A small table rather than a shared one: the layout converter's code
         * table lives in `:core:language`, which this module cannot see, and the
         * two answer different questions anyway — that one asks what a key
         * *does*, this one asks what a style rule is *called*. The names are the
         * ones `keyOverrideId` produces, which is the action class's name in
         * uppercase.
         */
        @Suppress("CyclomaticComplexMethod")
        fun overrideIdFor(code: Int): String? = when (code) {
            -11 -> "SHIFT"
            -13 -> "CAPSLOCK"
            -7, -8 -> "DELETE"
            -201 -> "LETTERS"
            -202, -203 -> "SYMBOLS"
            -204, -205 -> "NUMPAD"
            -212 -> "EMOJI"
            -221 -> "INPUTMETHODPICKER"
            -227 -> "LANGUAGESWITCH"
            32 -> "SPACE"
            10, 13, -10005 -> "ENTER"
            // A printable character styles the letter it types, so the override
            // follows that letter across every layout.
            in 0x21..0x10FFFF -> String(Character.toChars(code)).lowercase()
            else -> null
        }
    }
}

/**
 * Exactly which property of which element reaches a [ThemeSpec] field.
 *
 * This is the contract [SnyggMapper] above implements, written out so that the
 * count the import puts in front of the user — "the app can use N of the M
 * style rules in this file" — can be computed from it rather than guessed.
 *
 * The earlier count was every rule whose *element name* was recognised, which
 * flattered the conversion twice over: a rule setting only `text-overflow`
 * counted, and so did an element that was recognised but never read. A number
 * a user is shown has to mean what it says, so a rule counts here only when it
 * sets something that genuinely lands.
 *
 * Adding a field to the mapper means adding it here. The parity test asserts
 * the two agree on a sheet that exercises every element, so a mapping added
 * without a line here fails rather than silently under-reporting.
 */
internal val SNYGG_CONSUMED: Map<String, Set<String>> = mapOf(
    EL_BOARD to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_IMAGE),
    EL_NAV_BAR to setOf(PROP_BACKGROUND),
    EL_ONE_HANDED to setOf(PROP_BACKGROUND, PROP_FOREGROUND),
    EL_KEY to setOf(
        PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE, PROP_BORDER_COLOR,
        PROP_BORDER_WIDTH, PROP_FONT_WEIGHT, PROP_FONT_SIZE, PROP_IMAGE, PROP_ELEVATION,
        PROP_CONTENT_SCALE,
    ),
    EL_HINT to setOf(PROP_FOREGROUND, PROP_FONT_SIZE),
    EL_POPUP to setOf(
        PROP_BACKGROUND, PROP_FOREGROUND, PROP_BORDER_COLOR, PROP_BORDER_WIDTH, PROP_SHAPE,
        PROP_ELEVATION,
    ),
    EL_EMOJI_POPUP to setOf(
        PROP_BACKGROUND, PROP_FOREGROUND, PROP_BORDER_COLOR, PROP_BORDER_WIDTH, PROP_SHAPE,
        PROP_ELEVATION,
    ),
    EL_TOOLBAR to setOf(PROP_BACKGROUND),
    EL_TOOL to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE),
    EL_TOOL_TOGGLE to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE, PROP_ELEVATION),
    EL_CANDIDATE to setOf(PROP_FOREGROUND),
    EL_SECONDARY_TEXT to setOf(PROP_FOREGROUND),
    EL_DIVIDER to setOf(PROP_FOREGROUND),
    EL_CHIP to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE),
    EL_TILE to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE),
    EL_CARD to setOf(PROP_BACKGROUND, PROP_FOREGROUND, PROP_SHAPE, PROP_ELEVATION),
    EL_CARD_LIFTED to setOf(PROP_BACKGROUND, PROP_FOREGROUND),
    EL_INCOGNITO to setOf(PROP_FOREGROUND),
    EL_POPUP_MORE to setOf(PROP_FOREGROUND),
    EL_SHEET to setOf(PROP_SHAPE, PROP_BACKGROUND, PROP_FOREGROUND),
    EL_POPUP_ITEM to setOf(PROP_BACKGROUND, PROP_FOREGROUND),
    EL_PANEL_HEADER to setOf(PROP_FOREGROUND),
    EL_PANEL_BUTTON to setOf(PROP_BACKGROUND, PROP_FOREGROUND),
    EL_PANEL_TOOL to setOf(PROP_BACKGROUND, PROP_FOREGROUND),
    EL_MENU_ROW to setOf(PROP_FOREGROUND),
    EL_EMOJI_KEY to setOf(PROP_FOREGROUND),
    EL_EMOJI_TAB to setOf(PROP_FOREGROUND),
    EL_GLIDE to setOf(PROP_FOREGROUND, PROP_BACKGROUND),
)

/** Whether this rule sets anything the mapper will actually read. */
internal fun SnyggRule.lands(): Boolean =
    SNYGG_CONSUMED[element].orEmpty().any { it in properties }

/** Whether a colour will actually show, rather than being fully transparent. */
internal fun Long.isVisible(): Boolean = ((this ushr 24) and 0xFFL) > 0L

/** Rounds a float dp to the whole number the theme fields store. */
internal fun Float.toDpInt(): Int = roundToInt()
