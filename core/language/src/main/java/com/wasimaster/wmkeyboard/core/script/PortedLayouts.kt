package com.wasimaster.wmkeyboard.core.script

import com.wasimaster.wmkeyboard.core.layout.AssetLayouts

/**
 * Layouts placed by hand on languages that only a generated registry defines.
 *
 * [KeymanLanguages] and [GboardLanguages] are rewritten wholesale by their
 * pipelines, so a layout added to one of their languages by hand would vanish
 * on the next run. It is listed here instead and joins the language the way
 * those registries' own extras do, whichever list the language ends up in.
 */
internal object PortedLayouts {

    val extraLayoutIds: Map<String, List<String>> = mapOf(
        // smc/Indic-Keyboard's phonetic boards for scripts whose only other
        // layouts are InScript or Gboard's (#1340).
        "bho-Kthi" to listOf(AssetLayouts.BHO_KAITHI_PHONETIC_ID),
        "ks-Shrd" to listOf(AssetLayouts.KS_SHARADA_PHONETIC_ID),
        "saz" to listOf(AssetLayouts.SAZ_PHONETIC_ID),
        "syl-Sylo" to listOf(AssetLayouts.SYL_SYLOTI_NAGRI_PHONETIC_ID),
    )
}
