package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderRunner
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.providerCredential
import com.wasimaster.wmkeyboard.core.settings.resolveProviders

/**
 * The web and image search tools' providers: SearXNG instances, Tavily,
 * Brave, Mwmbl, Wiby, Marginalia, Wikipedia, the keyboard's own metasearch
 * over the keyless ones ([MetaSearchClient]), in the user's order, and the
 * browser, which takes the query away instead of answering it (#1383). The first that answers is the answer; one that
 * fails hands the query to the next. Shared by the panels and the AI's
 * search tools, so they never disagree about where a query goes.
 *
 * Blocking; call on an IO dispatcher. Throws the last provider's failure.
 */
object SearchProviders {

    /** [pick] is the panel's choice in pick mode; null, the AI's tools, walks the whole list. */
    fun rows(settings: KeyboardSettings, pick: String? = null): List<ResolvedProvider> =
        settings.resolveProviders(ProviderTool.SEARCH, pick = pick).filter { backend(it) != null }

    /**
     * [query] as a search page to open in the browser (#915): on the user's
     * SearXNG instance when that is the first provider, on Brave Search when
     * Brave is, and otherwise on DuckDuckGo, which needs no account.
     */
    fun browserUrl(settings: KeyboardSettings, query: String): String {
        val first = rows(settings).firstOrNull { backend(it) != SearchBackend.BROWSER }
        val q = ToolHttp.encode(query.trim())
        return when (first?.let(::backend)) {
            SearchBackend.SEARXNG -> runCatching { SearxClient.searchBase(first.url) + "?q=" + q }
                .getOrDefault("https://duckduckgo.com/?q=$q")
            SearchBackend.BRAVE -> "https://search.brave.com/search?q=$q"
            SearchBackend.MWMBL -> "https://mwmbl.org/?q=$q"
            SearchBackend.WIBY -> "https://wiby.me/?q=$q"
            SearchBackend.MARGINALIA -> "https://marginalia-search.com/search?query=$q"
            else -> "https://duckduckgo.com/?q=$q"
        }
    }

    /**
     * Whether a search from the panel goes to a SearXNG instance first, which
     * is when its category chips are offered (#934). [pick] as for [rows].
     */
    fun searxFirst(settings: KeyboardSettings, pick: String? = null): Boolean =
        rows(settings, pick).firstOrNull()?.let(::backend) == SearchBackend.SEARXNG

    /**
     * Whether a search from the panel is handed to the browser (#1383): the
     * browser row is the one picked, or the first in the list. [pick] as for
     * [rows]; the AI's tools pass null and never get here.
     */
    fun handsToBrowser(settings: KeyboardSettings, pick: String? = null): Boolean =
        rows(settings, pick).firstOrNull()?.let(::backend) == SearchBackend.BROWSER

    fun backend(row: ResolvedProvider): SearchBackend? = when (row.typeId) {
        ProviderTypes.SEARXNG.id -> SearchBackend.SEARXNG
        ProviderTypes.TAVILY.id -> SearchBackend.TAVILY
        ProviderTypes.BRAVE.id -> SearchBackend.BRAVE
        ProviderTypes.MWMBL.id -> SearchBackend.MWMBL
        ProviderTypes.WIBY.id -> SearchBackend.WIBY
        ProviderTypes.MARGINALIA.id -> SearchBackend.MARGINALIA
        ProviderTypes.WIKIPEDIA_SEARCH.id -> SearchBackend.WIKIPEDIA
        ProviderTypes.METASEARCH.id -> SearchBackend.METASEARCH
        ProviderTypes.PLUGIN.id -> SearchBackend.PLUGIN
        ProviderTypes.BROWSER.id -> SearchBackend.BROWSER
        else -> null
    }

    fun web(
        settings: KeyboardSettings,
        query: String,
        count: Int,
        safe: Boolean,
        answer: Boolean,
        source: NetSource = NetSource.WEB_SEARCH,
        pick: String? = null,
        /** A further page of the same search (#879); null, the first. */
        more: SearchMore? = null,
        /**
         * A SearXNG category other than general (#934). Only SearXNG has
         * them, so with one the other providers are not asked: their general
         * results would pass for the category's.
         */
        category: String? = null,
    ): WebSearchPage = ask(settings, pick, searxOnly = category != null, skip = setOf(SearchBackend.BROWSER)) { row, backend ->
        val page = more?.page ?: 0
        when (backend) {
            SearchBackend.SEARXNG -> SearxClient.webSearch(
                query,
                row.url,
                count,
                safe,
                source = source,
                page = page,
                category = category ?: SearxClient.GENERAL,
            )
            SearchBackend.TAVILY -> TavilySearchClient.webSearch(
                query,
                row.apiKey,
                count,
                safe,
                depth = settings.webSearch.tavilyDepth.apiValue,
                // The answer came with the first page; a second would only repeat it.
                answer = answer && more == null,
                source = source,
                excludeDomains = more?.excludeDomains.orEmpty(),
            )
            SearchBackend.BRAVE ->
                WebSearchPage(BraveSearchClient.webSearch(query, row.apiKey, count, safe, source = source, page = page))
            SearchBackend.MWMBL -> WebSearchPage(MwmblSearchClient.webSearch(query, count, source = source, page = page))
            SearchBackend.WIBY -> WebSearchPage(WibySearchClient.webSearch(query, count, source = source, page = page))
            SearchBackend.MARGINALIA ->
                WebSearchPage(MarginaliaSearchClient.webSearch(query, row.apiKey, count, safe, source = source, page = page))
            SearchBackend.WIKIPEDIA -> WebSearchPage(
                WikipediaSearchClient.webSearch(query, settings.webSearch.wikiLanguage, count, source = source, page = page),
            )
            SearchBackend.METASEARCH ->
                WebSearchPage(MetaSearchClient.webSearch(query, count, safe, metaOptions(settings), source = source, page = page))
            SearchBackend.PLUGIN -> PluginReplies.webPage(
                PluginProviders.call(row.key, PluginReplies.webRequest(query, count, safe, page, category)),
                engine = row.customName,
            )
            // Skipped above: the browser takes the query away rather than answering it.
            SearchBackend.BROWSER -> WebSearchPage(emptyList())
        }
    }

    fun images(
        settings: KeyboardSettings,
        query: String,
        count: Int,
        safe: Boolean,
        pick: String? = null,
        more: SearchMore? = null,
    ): List<ImageResult> =
        // Only SearXNG, Tavily, Brave and the metasearch (through Commons)
        // search pictures: the Images tab asks the next of those on the list.
        ask(settings, pick, skip = NO_IMAGES) { row, backend ->
            val page = more?.page ?: 0
            when (backend) {
                SearchBackend.SEARXNG -> SearxClient.imageSearch(query, row.url, count, safe, page = page)
                SearchBackend.TAVILY -> TavilySearchClient.imageSearch(
                    query,
                    row.apiKey,
                    count,
                    safe,
                    settings.webSearch.tavilyDepth.apiValue,
                    excludeDomains = more?.excludeDomains.orEmpty(),
                )
                SearchBackend.BRAVE -> BraveSearchClient.imageSearch(query, row.apiKey, count, safe, page = page)
                SearchBackend.METASEARCH -> MetaSearchClient.imageSearch(query, count, metaOptions(settings), page = page)
                // A plugin that only searches the web hands the Images tab to the next row.
                SearchBackend.PLUGIN ->
                    if (!PluginReplies.answersImages(row.key)) throw NoSearchProviderException()
                    else PluginReplies.images(PluginProviders.call(row.key, PluginReplies.imagesRequest(query, count, safe, page)))
                else -> emptyList()
            }
        }

    /**
     * What the metasearch's engines read from the settings: a Marginalia key
     * the user set up on its own row, the Wikipedia tool's language, and the
     * MediaWiki the photo list points Commons at.
     */
    private fun metaOptions(settings: KeyboardSettings) = MetaSearchClient.Options(
        marginaliaKey = ToolApiKeys.key(settings, ProviderTypes.MARGINALIA),
        wikiLanguage = settings.webSearch.wikiLanguage.ifBlank { "en" },
        commonsEndpoint = settings.providerCredential(ProviderTypes.COMMONS.id)?.url?.trim().orEmpty(),
    )

    private inline fun <T> ask(
        settings: KeyboardSettings,
        pick: String?,
        searxOnly: Boolean = false,
        skip: Set<SearchBackend> = emptySet(),
        call: (ResolvedProvider, SearchBackend) -> T,
    ): T {
        val rows = rows(settings, pick).filter {
            val backend = backend(it)
            (!searxOnly || backend == SearchBackend.SEARXNG) && backend !in skip
        }
        if (rows.isEmpty()) throw NoSearchProviderException()
        val outcome = ProviderRunner.fallback(rows, key = { it.key }) { row -> call(row, requireNotNull(backend(row))) }
        return when (outcome) {
            is ProviderRunner.Outcome.Found -> outcome.value
            ProviderRunner.Outcome.NotFound -> throw NoSearchProviderException()
            is ProviderRunner.Outcome.Failed -> throw outcome.error ?: NoSearchProviderException()
        }
    }

    private val NO_IMAGES = setOf(
        SearchBackend.MWMBL, SearchBackend.WIBY, SearchBackend.MARGINALIA, SearchBackend.WIKIPEDIA, SearchBackend.BROWSER,
    )

    /** The search list has nothing on that can be asked. */
    class NoSearchProviderException : IllegalStateException("No search provider is on")
}
