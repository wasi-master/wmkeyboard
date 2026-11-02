package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * Curated special-character catalog for the symbols tool: the characters
 * people otherwise hunt down on the web (fractions, real math operators,
 * Greek letters, arrows, typographic marks…), grouped for a chip-per-category
 * picker. Everything is a plain Unicode string, committed like any key press.
 */
object SymbolCatalog {

    /**
     * [id] is the key the picker tracks its selection by. It is never shown and
     * is never translated. [nameRes] is the chip label; resolve it where you
     * draw the chip.
     */
    data class SymbolCategory(
        val id: String,
        @StringRes val nameRes: Int,
        val symbols: List<String>,
    )

    val categories: List<SymbolCategory> = listOf(
        SymbolCategory(
            "Fractions", R.string.core_tools_symbol_category_fractions_label,
            listOf(
                "½", "⅓", "⅔", "¼", "¾", "⅕", "⅖", "⅗", "⅘", "⅙", "⅚",
                "⅐", "⅛", "⅜", "⅝", "⅞", "⅑", "⅒", "⁄",
            ),
        ),
        SymbolCategory(
            "Math", R.string.core_tools_symbol_category_math_label,
            listOf(
                "±", "∓", "×", "÷", "≠", "≈", "≡", "≤", "≥", "≪", "≫",
                "∞", "√", "∛", "∜", "∝", "∴", "∵", "∈", "∉", "⊂", "⊃",
                "⊆", "⊇", "∪", "∩", "∅", "∀", "∃", "∄", "¬", "∧", "∨",
                "⊕", "⊗", "∑", "∏", "∫", "∬", "∮", "∂", "∇", "Δ", "∠",
                "∟", "⊥", "∥", "∦", "≅", "≃", "≜", "≐", "⌀", "%", "‰",
                "‱", "°", "′", "″", "‴", "ℵ", "ℏ", "℮",
            ),
        ),
        SymbolCategory(
            "Greek", R.string.core_tools_symbol_category_greek_label,
            listOf(
                "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ",
                "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "ς", "τ", "υ", "φ",
                "χ", "ψ", "ω",
                "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ",
                "Μ", "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ",
                "Ψ", "Ω",
            ),
        ),
        SymbolCategory(
            "Arrows", R.string.core_tools_symbol_category_arrows_label,
            listOf(
                "←", "→", "↑", "↓", "↔", "↕", "↖", "↗", "↘", "↙",
                "⇐", "⇒", "⇑", "⇓", "⇔", "⇕", "↩", "↪", "↰", "↱",
                "↺", "↻", "⟲", "⟳", "↶", "↷", "⇄", "⇅", "⇆", "⇋", "⇌",
                "➔", "➜", "➤", "⟵", "⟶", "⟷", "↦", "⇥", "⇤",
            ),
        ),
        SymbolCategory(
            "Currency", R.string.core_tools_symbol_category_currency_label,
            listOf(
                "$", "¢", "€", "£", "¥", "₹", "₽", "₩", "₺", "₴", "₦",
                "₱", "฿", "₫", "₪", "₨", "৳", "₡", "₭", "₮", "₲", "₵",
                "₸", "₼", "₿", "¤",
            ),
        ),
        SymbolCategory(
            "Superscript", R.string.core_tools_symbol_category_superscript_label,
            listOf(
                "⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹",
                "⁺", "⁻", "⁼", "⁽", "⁾", "ⁿ", "ⁱ",
                "₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉",
                "₊", "₋", "₌", "₍", "₎", "ₐ", "ₑ", "ₓ",
            ),
        ),
        SymbolCategory(
            "Punctuation", R.string.core_tools_symbol_category_punctuation_label,
            listOf(
                "–", "—", "―", "…", "•", "·", "‣", "§", "¶", "†", "‡",
                "‘", "’", "“", "”", "‚", "„", "‹", "›", "«", "»",
                "¡", "¿", "‽", "⁂", "№", "℗", "©", "®", "™", "℠",
                " ", "​", "‍",
            ),
        ),
        SymbolCategory(
            "Shapes & stars", R.string.core_tools_symbol_category_shapes_label,
            listOf(
                "★", "☆", "✦", "✧", "✪", "✯", "✶", "✳", "✴", "❋",
                "■", "□", "▪", "▫", "▲", "△", "▼", "▽", "◆", "◇",
                "●", "○", "◎", "◉", "◌", "◯", "▶", "◀", "▷", "◁",
                "♠", "♣", "♥", "♦", "♤", "♧", "♡", "♢",
                "✓", "✔", "✕", "✗", "✘", "☑", "☒", "☐",
            ),
        ),
        SymbolCategory(
            "Misc", R.string.core_tools_symbol_category_other_label,
            listOf(
                "♩", "♪", "♫", "♬", "♭", "♮", "♯",
                "☀", "☁", "☂", "☃", "☄", "☾", "☽", "☼",
                "☎", "✆", "✉", "✎", "✏", "✂", "⌛", "⌚", "⌘", "⌥",
                "⎋", "⏎", "⌫", "⇧", "␣", "♻", "⚠", "⚡", "☮", "☯",
                "⚕", "⚖", "⚛", "♾", "☢", "☣", "⚓", "⚔", "⚙", "⚗",
                "♔", "♕", "♖", "♗", "♘", "♙", "♚", "♛", "♜", "♝", "♞", "♟",
            ),
        ),
    )

    /**
     * What holding a symbol offers. Close relatives only: a long arrow by the
     * short one, not every arrow, since the category has those a swipe away.
     */
    private val related: Map<String, List<String>> = listOf(
        "← ⇐ ⟵ ⟸ ↤", "→ ⇒ ⟶ ⟹ ↦ ➔", "↑ ⇑ ↥", "↓ ⇓ ↧", "↔ ⇔ ⟷ ⟺", "↕ ⇕",
        "± ∓", "× ⋅ ∗", "≠ ≢", "≈ ≃ ≅ ∼", "≤ ⩽ ≪", "≥ ⩾ ≫", "√ ∛ ∜", "∑ ∏", "∫ ∬ ∭ ∮", "% ‰ ‱",
        "∈ ∉ ∋", "⊂ ⊄ ⊆", "⊃ ⊅ ⊇", "∃ ∄", "′ ″ ‴",
        "– — ―", "… ⋯ ⋮", "• · ‣ ◦", "† ‡", "“ ” „ « »", "‘ ’ ‚ ‹ ›",
        "★ ☆ ✦ ✧ ✪", "■ □ ▪ ▫", "▲ △ ▴ ▵", "▼ ▽ ▾ ▿", "◆ ◇ ◈", "● ○ ◎ ◉",
        "♠ ♤", "♣ ♧", "♥ ♡ ❤", "♦ ♢", "✓ ✔ ☑", "✗ ✘ ✕ ☒",
        "⁰ ₀", "¹ ₁", "² ₂", "³ ₃", "⁴ ₄", "⁵ ₅", "⁶ ₆", "⁷ ₇", "⁸ ₈", "⁹ ₉",
        "⁺ ₊", "⁻ ₋", "⁼ ₌", "⁽ ₍", "⁾ ₎",
        "$ ¢", "♩ ♪ ♫ ♬",
    ).associate { line ->
        val parts = line.split(' ')
        parts.first() to parts.drop(1)
    }

    /**
     * What a cell of the picker types on a tap. A token from the user's own
     * list (#1038) may carry its alternates after a `|`, as `→|⇒|⟶`; a lone
     * `|` is the character itself.
     */
    fun face(token: String): String {
        val bar = token.indexOf('|')
        return if (bar > 0) token.substring(0, bar) else token
    }

    /**
     * What holding [token] offers besides its [face]: the ones it spells out,
     * or else the built-in relatives and the letter in its other case.
     */
    fun alternates(token: String): List<String> {
        val bar = token.indexOf('|')
        if (bar > 0) return token.substring(bar + 1).split('|').filter(String::isNotEmpty)
        val flipped = token.uppercase().takeIf { it != token } ?: token.lowercase().takeIf { it != token }
        return related[token].orEmpty() + listOfNotNull(flipped)
    }

    /** Display label for symbols that render invisibly in a picker cell. */
    fun label(symbol: String): String = when (symbol) {
        " " -> "NBSP"
        "​" -> "ZWSP"
        "‍" -> "ZWJ"
        else -> symbol
    }
}
