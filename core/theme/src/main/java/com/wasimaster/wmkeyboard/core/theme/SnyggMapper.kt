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
 * So the toolbar, the panels and the chips are mapped only from the elements
 * that genuinely correspond, and everything else is reported in `dropped`
 * rather than approximated.
 */
internal class SnyggMapper(private val style: Stylesheet) {

    fun convert(
        name: String,
        id: String,
        night: Boolean,
        files: Map<String, ByteArray>,
        dropped: MutableSet<FlexUnsupported>,
    ): ConvertedTheme? {
        val board = style.first(EL_BOARD)
        val key = style.first(EL_KEY)
        val boardBackground = snyggColor(board?.value(PROP_BACKGROUND))
        val keyBackground = snyggColor(key?.value(PROP_BACKGROUND))
        // A sheet that names neither surface has told us nothing worth calling
        // a theme, and an all-defaults ThemeSpec would look like the app lost
        // the file rather than like the file was empty.
        if (boardBackground == null && keyBackground == null) return null

        val resolvedBoard = boardBackground ?: ThemeSpec(id = "", name = "").boardBackground
        val resolvedKey = keyBackground ?: resolvedBoard
        val (shape, radius) = snyggShape(key?.value(PROP_SHAPE), dropped) ?: (null to null)

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
            keyText = textColorOn(key?.value(PROP_FOREGROUND), resolvedKey, dropped),
            // Left to derive unless the sheet styles the pressed key itself.
            pressedKeyBackground = snyggColor(style.first(EL_KEY, PRESSED)?.value(PROP_BACKGROUND)),
            modifierKeyBackground = snyggColor(modifierRule()?.value(PROP_BACKGROUND)) ?: resolvedKey,
            modifierKeyText = null,
            enterKeyBackground = snyggColor(enterRule()?.value(PROP_BACKGROUND)) ?: resolvedKey,
            enterKeyText = snyggColor(enterRule()?.value(PROP_FOREGROUND))
                ?: onColorFor(snyggColor(enterRule()?.value(PROP_BACKGROUND)) ?: resolvedKey),
            keyBorderColor = snyggColor(key?.value(PROP_BORDER_COLOR)),
            keyBorderWidthDp = snyggDp(key?.value(PROP_BORDER_WIDTH)) ?: 0f,
            keyShape = shape ?: KeyShapeKind.ROUNDED,
            keyCornerRadiusDp = radius,
            boldKeyLabels = key?.value(PROP_FONT_WEIGHT)?.contains(BOLD, ignoreCase = true),
            // Popups and the glide trail have real counterparts, so they map.
            popupBackground = snyggColor(style.first(EL_POPUP)?.value(PROP_BACKGROUND)),
            popupText = snyggColor(style.first(EL_POPUP)?.value(PROP_FOREGROUND)),
            hintText = snyggColor(style.first(EL_HINT)?.value(PROP_FOREGROUND)),
            popupBorderColor = snyggColor(style.first(EL_POPUP)?.value(PROP_BORDER_COLOR)),
            popupBorderWidthDp = snyggDp(style.first(EL_POPUP)?.value(PROP_BORDER_WIDTH)) ?: 0f,
            gestureTrailColor = snyggColor(style.first(EL_GLIDE)?.value(PROP_BACKGROUND))
                ?: snyggColor(style.first(EL_GLIDE)?.value(PROP_FOREGROUND)),
            // The candidate row is the one smartbar element with an exact
            // counterpart. The rest of that bar — the action toggles, the
            // overflow, the incognito indicator — has none, so the toolbar and
            // panel colours are left to derive rather than guessed at.
            suggestionText = snyggColor(style.first(EL_CANDIDATE)?.value(PROP_FOREGROUND)),
            chipBackground = snyggColor(style.first(EL_CANDIDATE)?.value(PROP_BACKGROUND)),
            accent = snyggColor(enterRule()?.value(PROP_BACKGROUND))
                ?: snyggColor(style.first(EL_CANDIDATE)?.value(PROP_FOREGROUND))
                ?: ThemeSpec(id = "", name = "").accent,
            keyOverrides = keyOverrides(resolvedKey, dropped),
        )
        return ConvertedTheme(theme, images)
    }

    /**
     * A scraped text colour, unless it would be unreadable where it landed.
     *
     * Snygg styles more surfaces than this keyboard has, so a foreground can
     * arrive from a rule that meant a different background. Below the ratio
     * where text stops being legible, the derived colour is used instead and the
     * substitution is reported, because a theme whose keys cannot be read is the
     * one failure a user cannot work around in the editor.
     */
    private fun textColorOn(raw: String?, background: Long, dropped: MutableSet<FlexUnsupported>): Long {
        val scraped = snyggColor(raw) ?: return onColorFor(background)
        if (contrastRatio(scraped, background) >= MIN_CONTRAST) return scraped
        dropped += FlexUnsupported.LOW_CONTRAST_FALLBACK
        return onColorFor(background)
    }

    /**
     * Per-key styles, from the rules that name one key by its code.
     *
     * This is where snygg's `key[code=…]` selectors belong: without it they
     * would all collapse onto the one modifier colour, and a theme that paints
     * six keys differently would come across painting one.
     */
    private fun keyOverrides(background: Long, dropped: MutableSet<FlexUnsupported>): Map<String, KeyOverride> =
        style.attributed(EL_KEY).mapNotNull { rule ->
            val code = codeOf(rule.attribute) ?: return@mapNotNull null
            val id = overrideIdFor(code) ?: return@mapNotNull null
            val fill = snyggColor(rule.value(PROP_BACKGROUND))
            val text = rule.value(PROP_FOREGROUND)?.let { textColorOn(it, fill ?: background, dropped) }
            val override = KeyOverride(
                background = fill,
                text = text,
                border = snyggColor(rule.value(PROP_BORDER_COLOR)),
            )
            if (override.isEmpty) null else id to override
        }.toMap()

    /** `code=32` out of `[code=32]`, and nothing else. */
    private fun codeOf(attribute: String?): Int? =
        attribute?.split(',')
            ?.firstOrNull { it.substringBefore('=').trim() == CODE }
            ?.substringAfter('=')
            ?.trim()
            ?.toIntOrNull()

    private fun modifierRule(): SnyggRule? =
        style.attributed(EL_KEY).firstOrNull { codeOf(it.attribute) in MODIFIER_CODES }

    private fun enterRule(): SnyggRule? =
        style.attributed(EL_KEY).firstOrNull { codeOf(it.attribute) in ENTER_CODES }

    private companion object {

        const val PRESSED = "pressed"
        const val BOLD = "bold"
        const val CODE = "code"

        /**
         * Below this a label stops being readable on its key. The WCAG large-text
         * floor: key labels are large and bold-ish, and holding them to the
         * body-text ratio would reject themes that are perfectly legible.
         */
        const val MIN_CONTRAST = 3f

        val MODIFIER_CODES = setOf(-11, -13, -7, -202, -201)
        val ENTER_CODES = setOf(10, 13)

        /**
         * The name a [ThemeSpec.keyOverrides] entry uses, for a foreign key code.
         *
         * A small table rather than a shared one: the layout converter's code
         * table lives in `:core:language`, which this module cannot see, and the
         * two answer different questions anyway — that one asks what a key
         * *does*, this one asks what a style rule is *called*. Only the keys
         * worth styling individually are here.
         */
        fun overrideIdFor(code: Int): String? = when (code) {
            -11 -> "SHIFT"
            -13 -> "CAPSLOCK"
            -7 -> "DELETE"
            -201 -> "LETTERS"
            -202, -203 -> "SYMBOLS"
            -212 -> "EMOJI"
            -227 -> "LANGUAGESWITCH"
            32 -> "SPACE"
            10, 13 -> "ENTER"
            // A printable character styles the letter it types, so the override
            // follows that letter across every layout.
            in 0x21..0x10FFFF -> String(Character.toChars(code)).lowercase()
            else -> null
        }
    }
}

/** Rounds a float dp to the whole number the theme fields store. */
internal fun Float.toDpInt(): Int = roundToInt()
