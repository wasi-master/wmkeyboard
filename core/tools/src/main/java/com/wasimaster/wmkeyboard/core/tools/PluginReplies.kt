package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.items
import com.wasimaster.wmkeyboard.core.providers.number
import com.wasimaster.wmkeyboard.core.providers.obj
import com.wasimaster.wmkeyboard.core.providers.text
import com.wasimaster.wmkeyboard.core.providers.texts
import com.wasimaster.wmkeyboard.core.providers.webUrl
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymGroup
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What each provider list sends a plugin, and how it reads the answer back
 * into its own types. The shapes are the plugin guide's
 * (`docs/.../plugins/guides/providers.mdx`); change one, change both.
 *
 * Every reader is forgiving: a plugin may return a bare list where an object
 * with a list is described, a string where an object with a text is, and an
 * item missing what makes it an item is left out. Only a reply with nothing
 * usable in it reads as "found nothing", which moves the list to its next row.
 */
object PluginReplies {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ---- search ----

    fun webRequest(query: String, count: Int, safe: Boolean, page: Int, category: String?): JsonObject = buildJsonObject {
        put("kind", "web")
        put("query", query.trim())
        put("count", count)
        put("safe", safe)
        put("page", page)
        category?.let { put("category", it) }
    }

    fun imagesRequest(query: String, count: Int, safe: Boolean, page: Int): JsonObject = buildJsonObject {
        put("kind", "images")
        put("query", query.trim())
        put("count", count)
        put("safe", safe)
        put("page", page)
    }

    /** `[{title, url, snippet, thumbnail?}]`, or `{"results": [...], "answer": "..."}`. */
    fun webPage(reply: JsonElement, engine: String): WebSearchPage {
        val results = reply.items("results").mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val url = webUrl(o.text("url", "link")) ?: return@mapNotNull null
            WebResult(
                title = o.text("title").ifEmpty { url },
                snippet = o.text("snippet", "content", "description"),
                url = url,
                displayUrl = url.substringAfter("://").substringBefore('/'),
                engines = o.texts("engines").ifEmpty { listOf(engine) },
                thumbnail = webUrl(o.text("thumbnail")),
            )
        }
        val answer = reply.obj()?.text("answer")?.ifEmpty { null }
        return WebSearchPage(results, answer = answer)
    }

    /** `[{title, thumb, image, page, mime?}]`, or `{"results": [...]}`. */
    fun images(reply: JsonElement): List<ImageResult> = reply.items("results").mapNotNull { item ->
        val o = item.obj() ?: return@mapNotNull null
        val image = webUrl(o.text("image", "url")) ?: return@mapNotNull null
        ImageResult(
            title = o.text("title"),
            thumbUrl = webUrl(o.text("thumb", "thumbnail")) ?: image,
            imageUrl = image,
            mime = o.text("mime").ifEmpty { mimeOf(image) },
            contextUrl = webUrl(o.text("page", "source")).orEmpty(),
        )
    }

    // ---- translate ----

    fun translateRequest(text: String, target: String, source: String): JsonObject = buildJsonObject {
        put("text", text)
        put("to", target)
        put("from", source)
    }

    /** `"text"`, or `{"text": "...", "from": "de"}`. Null for nothing. */
    fun translation(reply: JsonElement): Translation? {
        val text = (reply as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reply.obj()?.text("text").orEmpty()
        if (text.isBlank()) return null
        return Translation(text = text, detectedSource = reply.obj()?.text("from", "detected").orEmpty())
    }

    // ---- dictionary, synonyms, vocabulary ----

    fun wordRequest(word: String, language: String = ""): JsonObject = buildJsonObject {
        put("word", word)
        if (language.isNotBlank()) put("language", language)
    }

    /**
     * `[{word, phonetic?, audio?, language?, meanings: [{pos, definitions:
     * [{definition, example?, synonyms?}], synonyms?, antonyms?}]}]`, or one
     * entry, or `{"entries": [...]}`.
     */
    fun dictionary(reply: JsonElement, asked: String): List<DictEntry> {
        val list = if (reply.obj()?.containsKey("meanings") == true) listOf(reply) else reply.items("entries")
        return list.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val meanings = o.items("meanings").mapNotNull { m ->
                val mo = m.obj() ?: return@mapNotNull null
                val definitions = mo.items("definitions").mapNotNull { d ->
                    when (d) {
                        is JsonPrimitive -> d.content.trim().takeIf { d.isString && it.isNotEmpty() }?.let { DictDefinition(it, null, emptyList()) }
                        else -> d.obj()?.let { dd ->
                            dd.text("definition", "text").ifEmpty { null }
                                ?.let { DictDefinition(it, dd.text("example").ifEmpty { null }, dd.texts("synonyms")) }
                        }
                    }
                }
                if (definitions.isEmpty()) null else DictMeaning(mo.text("pos", "partOfSpeech"), definitions, mo.texts("synonyms"), mo.texts("antonyms"))
            }
            if (meanings.isEmpty()) {
                null
            } else {
                DictEntry(
                    word = o.text("word").ifEmpty { asked },
                    phonetic = o.text("phonetic", "ipa"),
                    audioUrl = webUrl(o.text("audio")),
                    meanings = meanings,
                    language = o.text("language"),
                )
            }
        }
    }

    /** `["calm", "still"]`, or `{"groups": [{pos?, sense?, words: [...]}]}`. */
    fun synonymGroups(reply: JsonElement): List<SynonymGroup> {
        val obj = reply.obj()
        if (obj == null || !obj.containsKey("groups")) {
            val words = reply.items("words").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.ifEmpty { null } }
            return if (words.isEmpty()) emptyList() else listOf(SynonymGroup(words = words))
        }
        return obj.items("groups").mapNotNull { g ->
            val o = g.obj() ?: return@mapNotNull null
            val words = o.texts("words")
            if (words.isEmpty()) null else SynonymGroup(pos = o.text("pos"), sense = o.text("sense").ifEmpty { null }, words = words)
        }
    }

    /**
     * A vocabulary record, as a pack stores it (`{"word", "senses": [...]}`),
     * or dictionary entries, folded the way the dictionary API's are.
     */
    fun vocabWord(reply: JsonElement, lemma: String): VocabWord? {
        val o = reply.obj()
        if (o != null && o.containsKey("senses")) {
            return runCatching { json.decodeFromJsonElement(VocabWord.serializer(), o) }.getOrNull()
                ?.let { if (it.word.isBlank()) it.copy(word = lemma) else it }
                ?.takeIf { it.senses.isNotEmpty() }
        }
        return com.wasimaster.wmkeyboard.core.vocab.VocabAutofill.fromDictionary(dictionary(reply, lemma), lemma)
    }

    // ---- currency ----

    fun fiatRequest(): JsonObject = buildJsonObject { put("base", "USD") }

    fun cryptoRequest(codes: Set<String>): JsonObject = buildJsonObject {
        put("base", "USD")
        putJsonArray("codes") { codes.sorted().forEach { add(JsonPrimitive(it)) } }
    }

    /**
     * `{"base": "EUR", "rates": {"USD": 1.08, ...}}` or a bare `{"USD": 1.08}`
     * against USD: units of each currency for one of the base. Turned into
     * units per US dollar, which is what every rate source gives.
     */
    fun fiatRates(reply: JsonElement): CurrencyClient.Rates? {
        val o = reply.obj() ?: return null
        val table = o["rates"].obj() ?: o
        val base = o.text("base").uppercase().ifEmpty { "USD" }
        val raw = table.keys.mapNotNull { code -> table.number(code)?.takeIf { it > 0 }?.let { code.uppercase() to it } }.toMap()
        val perUsd = if (base == "USD") raw else raw["USD"]?.let { usd -> raw.mapValues { it.value / usd } + (base to 1.0 / usd) } ?: return null
        if (perUsd.isEmpty()) return null
        return CurrencyClient.Rates("USD", perUsd + ("USD" to 1.0))
    }

    /**
     * `{"BTC": 65000.0, ...}`, or `{"prices": {...}}`: each coin's price in US
     * dollars, turned into coins per dollar as the coin sources give it.
     */
    fun cryptoRates(reply: JsonElement, codes: Set<String>): Map<String, Double> {
        val o = reply.obj() ?: return emptyMap()
        val table = o["prices"].obj() ?: o
        return table.keys.mapNotNull { code ->
            val upper = code.uppercase()
            table.number(code)?.takeIf { it > 0 && upper in codes }?.let { upper to 1.0 / it }
        }.toMap()
    }

    // ---- GIFs and stickers ----

    fun mediaRequest(query: String, sticker: Boolean, limit: Int, safe: Boolean): JsonObject = buildJsonObject {
        put("query", query.trim())
        put("kind", if (sticker) "sticker" else "gif")
        put("limit", limit)
        put("safe", safe)
    }

    /** `[{url, preview?, mime?, width?, height?, title?, creator?, id?}]`, or `{"results": [...]}`. */
    fun media(reply: JsonElement, rowKey: String): List<GifItem> = reply.items("results").mapIndexedNotNull { index, item ->
        val o = item.obj() ?: return@mapIndexedNotNull null
        val url = webUrl(o.text("url", "full")) ?: return@mapIndexedNotNull null
        val width = o.number("width") ?: 0.0
        val height = o.number("height") ?: 0.0
        GifItem(
            id = rowKey + "#" + o.text("id").ifEmpty { index.toString() },
            previewUrl = webUrl(o.text("preview", "thumb")) ?: url,
            fullUrl = url,
            mime = o.text("mime").ifEmpty { mimeOf(url) },
            aspectRatio = if (width > 0 && height > 0) (width / height).toFloat() else 1f,
            source = GifSource.PLUGIN,
            title = o.text("title"),
            creator = o.text("creator"),
        )
    }

    // ---- pictures and sound in, text out ----

    /** OCR: the photo as base64 JPEG. */
    fun imageRequest(jpeg: ByteArray): JsonObject = buildJsonObject {
        put("image", java.util.Base64.getEncoder().encodeToString(jpeg))
        put("mime", "image/jpeg")
    }

    /**
     * Music and voice: the clip as base64 16-bit little-endian PCM, mono, at
     * [sampleRate], the shape every recogniser takes without a codec.
     */
    fun audioRequest(samples: FloatArray, sampleRate: Int, language: String = ""): JsonObject = buildJsonObject {
        put("audio", java.util.Base64.getEncoder().encodeToString(pcm16(samples)))
        put("encoding", "pcm16le")
        put("sampleRate", sampleRate)
        put("channels", 1)
        if (language.isNotBlank()) put("language", language)
    }

    /** `"text"` or `{"text": "..."}`; null for nothing. */
    fun text(reply: JsonElement): String? =
        ((reply as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reply.obj()?.text("text"))?.takeIf { it.isNotBlank() }

    /** Whether a search provider answers the Images tab: its manifest says so. */
    fun answersImages(key: String): Boolean = PluginProviders.row(key)?.images == true

    /** Samples in -1..1 as 16-bit little-endian PCM. */
    fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for ((i, sample) in samples.withIndex()) {
            val v = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun mimeOf(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        else -> "image/jpeg"
    }
}
