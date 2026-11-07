package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import java.util.Locale

/** One checker the grammar tool may ask, in the order its list asks them. */
sealed interface GrammarStep {
    /** The bundled engine. */
    data object Harper : GrammarStep

    /**
     * A GiellaLT grammar checker on the phone ([DivvunGrammar]) for the
     * keyboard's language, [languageId] in the keyboard's ids.
     */
    data class Giella(val languageId: String) : GrammarStep

    /** A LanguageTool server; [name] is what the panel calls it. */
    data class LanguageTool(val key: String, val name: String, val server: LanguageToolServer) : GrammarStep
}

/** Why a LanguageTool row was left out of a check. */
enum class GrammarHeld {
    /** A password field, or incognito: the text is not sent anywhere. */
    PRIVATE,

    /** Data saver holds online grammar checks. */
    DATA_SAVER,

    /** Every checker on the list is off. */
    NOTHING_ON,
}

/** What one check asks, first to last, and why a row that would have been asked was not. */
data class GrammarPlan(val steps: List<GrammarStep>, val held: GrammarHeld? = null) {
    /** The first checker sends the text away. */
    val online: Boolean get() = steps.firstOrNull() is GrammarStep.LanguageTool
}

/**
 * How the grammar tool's provider list becomes one check.
 *
 * A check goes to the first row that knows the keyboard's language, and on
 * down the rows that know it when one cannot answer: Harper knows English,
 * LanguageTool every language it has (and the rest through `auto`). Before
 * every row comes the GiellaLT grammar checker the user downloaded for the
 * keyboard's language, if any: it runs on the phone, and it is the one
 * written for that language, so it needs no row of its own. When no
 * row on the list knows the language, every row is asked in order anyway,
 * which is what the grammar tool did before it had a list: Harper on whatever
 * was in the field.
 *
 * A LanguageTool row is never asked for a password or incognito field, nor
 * while data saver holds it; the plan says which, for the panel to explain
 * when nothing else was left to ask.
 */
object GrammarProviders {

    fun plan(
        rows: List<ResolvedProvider>,
        languageTag: String,
        privateField: Boolean,
        dataSaverAllows: Boolean,
        giellaInstalled: Set<String> = emptySet(),
    ): GrammarPlan {
        val giella = DivvunGrammarCatalog.forLocale(languageTag)?.languageId
            ?.takeIf { it in giellaInstalled }
            ?.let { GrammarStep.Giella(it) }
        var held: GrammarHeld? = if (rows.isEmpty()) GrammarHeld.NOTHING_ON else null
        val steps = rows.mapNotNull { row ->
            when (row.typeId) {
                ProviderTypes.HARPER.id -> GrammarStep.Harper
                ProviderTypes.LANGUAGETOOL.id -> when {
                    privateField -> null.also { held = GrammarHeld.PRIVATE }
                    !dataSaverAllows -> null.also { if (held == null) held = GrammarHeld.DATA_SAVER }
                    else -> GrammarStep.LanguageTool(row.key, row.customName.ifEmpty { NAME }, serverOf(row))
                }
                else -> null
            }
        }
        val knowing = steps.filter { knows(it, languageTag) }
        val asked = listOfNotNull(giella) + knowing.ifEmpty { steps }
        return GrammarPlan(asked, held.takeIf { asked.isEmpty() })
    }

    /** Whether [step] checks text in [languageTag] rather than guessing at it. */
    fun knows(step: GrammarStep, languageTag: String): Boolean = when (step) {
        GrammarStep.Harper -> languageTag.trim().lowercase(Locale.ROOT).let { it == "en" || it.startsWith("en-") || it.startsWith("en_") }
        is GrammarStep.Giella -> DivvunGrammarCatalog.forLocale(languageTag)?.languageId == step.languageId
        is GrammarStep.LanguageTool -> true
    }

    /** The server a LanguageTool row names: its address, its account, its two options. */
    fun serverOf(row: ResolvedProvider): LanguageToolServer {
        val extra = row.credential?.extra.orEmpty()
        return LanguageToolServer(
            url = row.url,
            username = extra[CredentialSpec.USERNAME]?.trim().orEmpty(),
            apiKey = row.apiKey,
            picky = extra[LanguageToolServer.PICKY] == "true",
            motherTongue = extra[LanguageToolServer.MOTHER_TONGUE]?.trim().orEmpty(),
        )
    }

    private const val NAME = "LanguageTool"
}
