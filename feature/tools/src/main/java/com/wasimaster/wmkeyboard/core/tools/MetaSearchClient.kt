package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The keyboard's own metasearch: the default web search, which needs nothing
 * set up. Every engine it asks publishes an API meant for apps (Mwmbl,
 * Marginalia, Wikipedia, Wiby; Wikimedia Commons for pictures), all are asked
 * at once from the phone, and their answers are merged here: the same page
 * found twice becomes one result that names both engines, and the order is
 * the engines' own ranks, fused.
 *
 * Written for this app, not taken from SearXNG or any other metasearch, and it
 * reads no engine's result pages: an engine with no API is not one of these.
 *
 * Ranking is reciprocal rank fusion: each engine gives a result
 * `weight / (K + rank)`, and a result's score is the sum over the engines
 * that found it. A page two engines agree on rises above one that only one
 * placed a little higher, which is the point of asking several. Wiby's small
 * web weighs less, so its hobby pages fill gaps rather than lead.
 */
object MetaSearchClient {

    /** One engine behind the metasearch. [label] is the engine's name, shown under its results. */
    enum class Engine(val id: String, val label: String, val weight: Double) {
        MWMBL("mwmbl", "Mwmbl", 1.0),
        MARGINALIA("marginalia", "Marginalia", 0.9),
        WIKIPEDIA("wikipedia", "Wikipedia", 1.0),
        WIBY("wiby", "Wiby", 0.5),
    }

    /** What one engine answered, best first. */
    data class EngineHits(val engine: Engine, val results: List<WebResult>)

    /** What the engines need from the settings: Marginalia's key (blank, the shared one) and Wikipedia's language. */
    data class Options(
        val marginaliaKey: String = "",
        val wikiLanguage: String = "en",
        val commonsEndpoint: String = "",
        val engines: List<Engine> = Engine.entries,
    )

    /**
     * Blocking; call on an IO dispatcher. Asks every engine at once and waits
     * up to [DEADLINE_MS] for them; one that is slower is left out of this
     * answer. Throws the first engine's failure only when every engine failed.
     */
    fun webSearch(
        query: String,
        count: Int,
        safe: Boolean,
        options: Options = Options(),
        source: NetSource = NetSource.WEB_SEARCH,
        page: Int = 0,
    ): List<WebResult> {
        val each = count.coerceIn(1, MAX_PER_ENGINE)
        val calls = options.engines.map { engine ->
            engine to Callable {
                when (engine) {
                    Engine.MWMBL -> MwmblSearchClient.webSearch(query, each, source = source, page = page)
                    Engine.MARGINALIA ->
                        MarginaliaSearchClient.webSearch(query, options.marginaliaKey, each, safe, source = source, page = page)
                    // An encyclopedia answers a few of a query's results, not all of them.
                    Engine.WIKIPEDIA ->
                        WikipediaSearchClient.webSearch(query, options.wikiLanguage, minOf(each, WIKIPEDIA_SHARE), source = source, page = page)
                    Engine.WIBY -> WibySearchClient.webSearch(query, each, source = source, page = page)
                }
            }
        }
        val answers = askAll(calls)
        val hits = answers.mapNotNull { (engine, outcome) -> outcome.getOrNull()?.let { EngineHits(engine, it) } }
        if (hits.isEmpty()) {
            throw answers.firstNotNullOfOrNull { it.second.exceptionOrNull() } ?: java.io.IOException("No engine answered")
        }
        return merge(hits, count)
    }

    /**
     * Pictures, from Wikimedia Commons: the one keyless picture library with
     * an API for apps. Free-licensed photos, so a search for a brand or a
     * meme finds little; it is what lets the Images tab work with nothing
     * set up. Blocking; throws on failure.
     */
    fun imageSearch(query: String, count: Int, options: Options = Options(), page: Int = 0): List<ImageResult> {
        val perPage = count.coerceIn(1, MAX_IMAGES)
        val response = CommonsClient.searchPhotos(
            PhotoQuery(text = query, page = page + 1, perPage = perPage, orientation = PhotoOrientation.ANY),
            options.commonsEndpoint,
        )
        return CommonsClient.parsePhotos(response.body).map { photo ->
            ImageResult(
                title = photo.altText,
                thumbUrl = photo.thumbUrl,
                imageUrl = photo.fullUrl,
                // Commons is searched for JPEGs only (CommonsClient.photoTerms).
                mime = "image/jpeg",
                contextUrl = photo.pageUrl,
            )
        }
    }

    /**
     * Each call on its own thread, all at once, each answer kept as success
     * or failure. One still running at the deadline counts as failed; its
     * request ends on its own timeout, and nothing waits for it.
     */
    private fun <T> askAll(calls: List<Pair<Engine, Callable<T>>>): List<Pair<Engine, Result<T>>> {
        val running: List<Pair<Engine, Future<T>>> = calls.map { (engine, call) -> engine to POOL.submit(call) }
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS)
        return running.map { (engine, future) ->
            val left = (until - System.nanoTime()).coerceAtLeast(0)
            engine to try {
                Result.success(future.get(left, TimeUnit.NANOSECONDS))
            } catch (e: ExecutionException) {
                Result.failure(e.cause ?: e)
            } catch (e: TimeoutException) {
                future.cancel(true)
                Result.failure(e)
            } catch (e: InterruptedException) {
                running.forEach { it.second.cancel(true) }
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }

    /**
     * The engines' lists as one, best first, [count] long. A page found by
     * several is one result: the first engine's address, the title of the
     * engine that ranked it best, the longest snippet, and every engine's
     * name. Ties keep the order the engines were asked in.
     */
    fun merge(hits: List<EngineHits>, count: Int): List<WebResult> {
        class Merged(val first: WebResult, val order: Int) {
            var score = 0.0
            var best = Double.NEGATIVE_INFINITY
            var title = first.title
            var snippet = first.snippet
            val engines = ArrayList<String>()
        }
        val byKey = LinkedHashMap<String, Merged>()
        for (engineHits in hits) {
            val seen = HashSet<String>()
            engineHits.results.forEachIndexed { rank, result ->
                val key = key(result.url) ?: return@forEachIndexed
                // An engine that lists one page twice is counted once.
                if (!seen.add(key)) return@forEachIndexed
                val share = engineHits.engine.weight / (RRF_K + rank)
                val merged = byKey.getOrPut(key) { Merged(result, byKey.size) }
                merged.score += share
                if (share > merged.best && result.title.isNotBlank() && result.title != result.url) {
                    merged.best = share
                    merged.title = result.title
                }
                if (result.snippet.length > merged.snippet.length) merged.snippet = result.snippet
                merged.engines += engineHits.engine.label
            }
        }
        return byKey.values
            .sortedWith(compareByDescending<Merged> { it.score }.thenBy { it.order })
            .take(count.coerceAtLeast(0))
            .map { it.first.copy(title = it.title, snippet = it.snippet, engines = it.engines) }
    }

    /**
     * The page an address names, as far as merging goes: the same with or
     * without `www.`, a mobile Wikipedia's `m.`, https or http, a trailing
     * slash, a `#part`, or the tracking parameters links pick up. Null for
     * something that is not a web address.
     */
    fun key(url: String): String? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = trimmed.substringAfter("://").substringBefore('#')
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        var host = rest.substring(0, hostEnd).lowercase().substringAfter('@').removeSuffix(".")
        if (host.isEmpty()) return null
        host = host.removePrefix("www.")
        host = MOBILE_WIKI.replace(host, "$1.")
        val after = rest.substring(hostEnd)
        val path = after.substringBefore('?').trimEnd('/')
        val query = after.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() && TRACKING.none { prefix -> it.lowercase().startsWith(prefix) } }
            .sorted()
            .joinToString("&")
        return host + path + if (query.isEmpty()) "" else "?$query"
    }

    /** `en.m.wikipedia.org` is `en.wikipedia.org`; the same for its sister sites. */
    private val MOBILE_WIKI = Regex("""^([a-z\-]+)\.m\.(?=(wikipedia|wiktionary|wikivoyage|wikibooks|wikiquote|wikinews|wikisource|wikiversity)\.org$)""")

    private val TRACKING = listOf("utm_", "fbclid=", "gclid=", "mc_cid=", "mc_eid=", "ref=", "ref_src=")

    /** Fusion's constant: small, since every list here is short and its first places matter most. */
    private const val RRF_K = 10.0
    private const val MAX_PER_ENGINE = 30
    private const val WIKIPEDIA_SHARE = 4
    private const val MAX_IMAGES = 50

    /** How long a search waits for its slowest engine. */
    private const val DEADLINE_MS = 8_000L

    /** Daemon threads, so a request still running when the app goes does not keep it alive. */
    private val POOL = Executors.newCachedThreadPool { task -> Thread(task, "metasearch").apply { isDaemon = true } }
}
