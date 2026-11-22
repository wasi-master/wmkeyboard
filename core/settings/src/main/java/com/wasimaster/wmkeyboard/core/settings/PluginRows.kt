package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.tools.DictionaryRow
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymRow
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSource

/**
 * A dictionary provider row as the look-up asks it: its built-in source, or
 * the plugin provider it names. Null for a row of neither kind.
 */
fun ResolvedProvider.dictionaryRow(): DictionaryRow? = when {
    type === ProviderTypes.PLUGIN -> DictionaryRow.plugin(key, customName)
    else -> DictionarySource.of(typeId)?.takeIf { it != DictionarySource.PLUGIN }?.let(::DictionaryRow)
}

/** A synonym provider row as the look-up asks it; see [dictionaryRow]. */
fun ResolvedProvider.synonymRow(): SynonymRow? = when {
    type === ProviderTypes.PLUGIN -> SynonymRow.plugin(key, customName)
    else -> SynonymSource.of(typeId)?.takeIf { it != SynonymSource.PLUGIN }?.let(::SynonymRow)
}
