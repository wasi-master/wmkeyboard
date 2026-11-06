package com.wasimaster.wmkeyboard.core.gesture

/**
 * One language's own glide tolerances (issue #1136): the three radii of
 * [GlideBeam.Tuning] a user can move, each null where the language follows the
 * global setting.
 *
 * Per language because the right tolerance is partly a property of the layout
 * a language is typed on, not only of the hand: Japanese on Kana JIS puts
 * up to thirteen keys in a row, so a radius that forgives a sloppy English
 * stroke on QWERTY lets a Kana stroke reach keys it never went near. Only the radii, on purpose: the doubled-letter marks are
 * how a hand draws, and the hand is the same in every language.
 *
 * Applied under [GlideLanguageProfiles], which moves only the weights no
 * setting reaches, so the two never fight over a field.
 */
data class GlideTolerances(
    val startRadius: Float? = null,
    val endRadius: Float? = null,
    val nearRadius: Float? = null,
) {
    /** Nothing of its own: the language follows the global radii. */
    val isEmpty: Boolean get() = startRadius == null && endRadius == null && nearRadius == null

    /** [tuning] with this language's radii on it, or [tuning] itself when none differs. */
    fun applyTo(tuning: GlideBeam.Tuning): GlideBeam.Tuning {
        val start = startRadius ?: tuning.startRadius
        val end = endRadius ?: tuning.endRadius
        val near = nearRadius ?: tuning.nearRadius
        return if (start == tuning.startRadius && end == tuning.endRadius && near == tuning.nearRadius) {
            tuning
        } else {
            tuning.copy(startRadius = start, endRadius = end, nearRadius = near)
        }
    }

    companion object {
        val NONE = GlideTolerances()

        /**
         * [tuning] as [languageId] decodes it: its own radii from [byLanguage]
         * where it has any, the global ones otherwise. The id is matched
         * exactly, as the language page that writes it names it, so `de-CH`
         * never borrows what was set on German's page: that page would not
         * show it, and a value nothing on screen admits to is a bug report.
         */
        fun resolve(
            tuning: GlideBeam.Tuning,
            byLanguage: Map<String, GlideTolerances>,
            languageId: String?,
        ): GlideBeam.Tuning {
            if (languageId.isNullOrEmpty() || byLanguage.isEmpty()) return tuning
            return byLanguage[languageId]?.applyTo(tuning) ?: tuning
        }

        /**
         * The map as one preference string, `lang=start,end,near;…`, an
         * unset radius left empty (`ja=,,1.2`). Languages with nothing of
         * their own are dropped, so clearing the last radius removes the entry.
         */
        fun encode(byLanguage: Map<String, GlideTolerances>): String =
            byLanguage.entries
                .filter { (id, t) -> id.isNotBlank() && !t.isEmpty && ';' !in id && '=' !in id }
                .sortedBy { it.key }
                .joinToString(";") { (id, t) ->
                    "$id=" + listOf(t.startRadius, t.endRadius, t.nearRadius)
                        .joinToString(",") { it?.toString().orEmpty() }
                }

        /**
         * [encode]'s inverse. A malformed entry is skipped rather than failing
         * the whole map, and every radius is held to [range], the bounds the
         * sliders and setters share, so a hand-edited backup cannot set one
         * the decoder would refuse.
         */
        fun decode(raw: String?, range: ClosedFloatingPointRange<Float>): Map<String, GlideTolerances> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(';').mapNotNull { entry ->
                val eq = entry.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val parts = entry.substring(eq + 1).split(',')
                if (parts.size != 3) return@mapNotNull null
                fun radius(i: Int): Float? =
                    parts[i].trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(range)
                val t = GlideTolerances(radius(0), radius(1), radius(2))
                if (t.isEmpty) null else entry.substring(0, eq).trim() to t
            }.toMap()
        }
    }
}
