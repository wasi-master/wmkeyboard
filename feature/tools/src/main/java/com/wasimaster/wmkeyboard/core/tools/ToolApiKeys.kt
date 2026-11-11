package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.providerCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.resolveProviders

/**
 * The effective key of a provider, and the search and photo lists as their
 * clients take them. A key the user set up in a tool's provider list (shared
 * by every tool that lists the provider) wins; otherwise the key baked into
 * the build applies; blank means the provider's row says it needs a key.
 * GIFs, stickers and translate read their lists through [MediaProviders] and
 * [TranslateProviders].
 */
object ToolApiKeys {

    /**
     * The key in force for a single-instance provider: the one the user set
     * up (shared by every tool that lists it), else the one this build carries.
     */
    fun key(settings: KeyboardSettings, type: ProviderType): String =
        settings.providerCredential(type.id)?.apiKey?.trim().orEmpty().ifBlank { type.builtInKey }

    fun brave(settings: KeyboardSettings): String = key(settings, ProviderTypes.BRAVE)

    /** The user's Tavily key; there is no built-in one (#439). */
    fun tavily(settings: KeyboardSettings): String = key(settings, ProviderTypes.TAVILY)

    /**
     * The service the web and image search tools ask first, or null when none
     * is set up: the first usable row of the search list (see [SearchProviders]).
     */
    fun searchBackend(settings: KeyboardSettings): SearchBackend? =
        SearchProviders.rows(settings).firstNotNullOfOrNull { SearchProviders.backend(it) }

    /**
     * Whether the web/image search tools have a usable provider. Delegates to
     * `hasSearchKey`, which is where the toolbar and the settings screens ask
     * the same question — one answer, so a tool can never be on the bar while
     * its panel says the key is missing.
     */
    fun hasSearchProvider(settings: KeyboardSettings): Boolean = hasSearchKey(settings)

    fun unsplash(settings: KeyboardSettings): String = key(settings, ProviderTypes.UNSPLASH)

    fun pexels(settings: KeyboardSettings): String = key(settings, ProviderTypes.PEXELS)

    /** The background-photo providers, in the user's order: the photo list's usable rows. */
    fun photoSources(settings: KeyboardSettings): List<PhotoSource> =
        photoRows(settings).mapNotNull { photoSourceOf(it.typeId) }.distinct()

    /** Resolves each provider's key for the photo client's dispatch. */
    fun photoKeys(settings: KeyboardSettings): PhotoSearchClient.Keys {
        val rows = photoRows(settings)
        return PhotoSearchClient.Keys { source ->
            val row = rows.firstOrNull { photoSourceOf(it.typeId) == source }
            when (source) {
                // Commons is anonymous, so this slot carries the endpoint
                // instead of a key: it is the per-source string the dispatch
                // already threads through, and a second parallel channel for
                // one provider would be worse than one documented reuse.
                PhotoSource.COMMONS -> row?.url.orEmpty()
                else -> row?.apiKey.orEmpty()
            }
        }
    }

    private fun photoRows(settings: KeyboardSettings) = settings.resolveProviders(ProviderTool.PHOTOS)

    private fun photoSourceOf(typeId: String): PhotoSource? = when (typeId) {
        ProviderTypes.UNSPLASH.id -> PhotoSource.UNSPLASH
        ProviderTypes.PEXELS.id -> PhotoSource.PEXELS
        ProviderTypes.COMMONS.id -> PhotoSource.COMMONS
        else -> null
    }
}

/** The services behind the web and image search tools; see [ToolApiKeys.searchBackend]. */
enum class SearchBackend { SEARXNG, TAVILY, BRAVE, MWMBL }
