package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.layout.SequenceRuleSets
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry

/** One value worth offering, with the name a person knows it by. */
data class JsonValueHint(val value: String, val label: String? = null)

/**
 * Where the editor learns the values that live on the device rather than in
 * the model: the themes installed, the fonts, the icon names, the user's own
 * secondary layouts, and the words for an enum value. The app answers from its
 * settings and resources; this module answers only what it owns itself, the
 * languages.
 */
interface JsonValueSource {

    /** Values worth offering for [domain], in the order to offer them. */
    fun values(domain: JsonValueDomain): List<JsonValueHint> = when (domain) {
        JsonValueDomain.LANGUAGE -> languages
        // The shipped sets, by id and name: this module ships them.
        JsonValueDomain.RULE_SET -> SequenceRuleSets.ids().map { id ->
            JsonValueHint(id, SequenceRuleSets.byId(id)?.name?.takeIf { it.isNotEmpty() })
        }
        else -> emptyList()
    }

    /**
     * Whether [value] names something in [domain], or null when this source
     * cannot tell: a check never calls a value wrong on a guess.
     */
    fun knows(domain: JsonValueDomain, value: String): Boolean? {
        if (domain == JsonValueDomain.FONT) return null
        val known = values(domain)
        return if (known.isEmpty()) null else known.any { it.value == value }
    }

    /** What the enum [typeName]'s [value] is called, such as a tool's name, or null. */
    fun enumLabel(typeName: String, value: String): String? = null

    companion object {
        /** The languages, which this module knows without the app. */
        val Default: JsonValueSource = object : JsonValueSource {}

        private val languages: List<JsonValueHint> by lazy {
            LanguageRegistry.all.map { JsonValueHint(it.id, it.englishName) }
        }
    }
}
