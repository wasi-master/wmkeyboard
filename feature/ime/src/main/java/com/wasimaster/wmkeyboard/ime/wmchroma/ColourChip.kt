package com.wasimaster.wmkeyboard.ime.wmchroma

import android.content.Context
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest

/**
 * The colour chip's answer. SmartSuggest only recognises a code; WM Chroma names it and writes it
 * in the formats the person shows there. The chip offers those other than the one typed, the
 * [step]th first, so a hold steps through them as it does a date chip's formats.
 */
internal object ColourChip {
    /** [hit] with WM Chroma's answer, or null when WM Chroma is missing, off or did not read a colour. Blocks. */
    fun fill(context: Context, hit: SmartSuggest.SmartHit, step: Int): SmartSuggest.SmartHit? {
        val described = (WmChromaBridge.describe(context, hit.query) as? ChromaAnswer.Ok)?.value ?: return null
        val others = otherFormats(hit.query, described.formats)
        if (others.isEmpty()) return null
        val shown = others[Math.floorMod(step, others.size)]
        val label = described.colour.name.ifBlank { hit.query }
        return hit.copy(
            result = shown.text,
            insert = shown.text,
            swatch = described.colour.argb,
            moreFormats = others.size > 1,
            tiers = listOf(SmartSuggest.ChipTier(label, shown.text)),
        )
    }

    /** The formats that would change what was typed: spacing and case do not count as a change. */
    fun otherFormats(typed: String, formats: List<ChromaFormat>): List<ChromaFormat> {
        val mine = plain(typed)
        return formats.filter { plain(it.text) != mine }.distinctBy { plain(it.text) }
    }

    private fun plain(s: String) = s.lowercase().filterNot { it.isWhitespace() }
}
