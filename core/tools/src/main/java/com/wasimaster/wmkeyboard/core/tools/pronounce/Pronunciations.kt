package com.wasimaster.wmkeyboard.core.tools.pronounce

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Locale
import java.util.MissingResourceException

/** Where a recorded pronunciation came from (#1146). */
enum class PronunciationSource { WIKTIONARY, FORVO }

/**
 * One person saying the word (#1146): an audio file on Wikimedia Commons that
 * Wiktionary's page for the word uses, or a Forvo recording. [label] tells the
 * voices apart on the card: the accent ("US", "UK") where the file name gives
 * one, else the speaker's user name.
 */
data class Recording(val url: String, val label: String, val source: PronunciationSource)

/**
 * Recorded pronunciations for the Dictionary tool and the Look up card
 * (#1146): real people saying the word, for the languages and accents the
 * phone's voice reads badly or not at all.
 *
 * Wiktionary needs no key. Its page for a word plays the files from Wikimedia
 * Commons that say it, and those files are named by a convention that gives
 * their language: `En-us-house.ogg` for the old uploads, and
 * `LL-Q1860 (eng)-Speaker-house.wav` for Lingua Libre's. Forvo has more
 * voices but needs the user's own API key, so it is only asked when one is set.
 */
object Pronunciations {

    private val AUDIO_EXTENSIONS = setOf("ogg", "oga", "opus", "wav", "mp3", "flac")

    /** `LL-Q1860 (eng)-Speaker-word.wav`: Lingua Libre's ISO 639-3 code and the speaker. */
    private val LINGUA_LIBRE = Regex("^LL-Q\\d+ \\(([a-z]{2,3})\\)-([^-]+)-(.+)$")

    /** `En-us-word.ogg` or `De-Wort.ogg`: a language code, maybe a two-letter accent, the word. */
    private val CLASSIC = Regex("^([A-Za-z]{2,3})-(?:([A-Za-z]{2})-)?(.+)$")

    /** What a Commons audio file's name says about it. */
    internal data class AudioFile(val language: String, val accent: String?, val speaker: String?, val word: String)

    /**
     * Reads a file name (with or without `File:`) for its language, accent,
     * speaker and word. Null for a file that is not audio, or whose name
     * follows neither convention, since its language cannot be told.
     */
    internal fun describeFile(title: String): AudioFile? {
        val name = title.removePrefix("File:").trim()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in AUDIO_EXTENSIONS) return null
        val stem = name.substringBeforeLast('.')
        LINGUA_LIBRE.matchEntire(stem)?.let { match ->
            val (language, speaker, word) = match.destructured
            return AudioFile(language, null, speaker, word)
        }
        CLASSIC.matchEntire(stem)?.let { match ->
            val (language, accent, word) = match.destructured
            return AudioFile(language.lowercase(Locale.ROOT), accent.uppercase(Locale.ROOT).ifEmpty { null }, null, word)
        }
        return null
    }

    /** Whether a two- or three-letter [code] from a file name is [language] (an ISO 639-1 code). */
    internal fun sameLanguage(code: String, language: String): Boolean {
        val wanted = language.lowercase(Locale.ROOT)
        if (code == wanted) return true
        val iso3 = try {
            Locale(wanted).isO3Language
        } catch (_: MissingResourceException) {
            return false
        }
        return code == iso3
    }

    /**
     * The audio files [body] (an answer to [wiktionaryUrl]) lists in
     * [language], files whose name is the word itself first, then by accent
     * and speaker. Two voices with the same label get a number each.
     */
    internal fun parseWiktionary(body: String, word: String, language: String): List<Recording> {
        val pages = (Json.parseToJsonElement(body) as? JsonObject)
            ?.obj("query")?.get("pages") as? JsonArray ?: return emptyList()
        val wanted = normalWord(word)
        val found = pages.mapNotNull { element ->
            val page = element as? JsonObject ?: return@mapNotNull null
            val title = page.str("title") ?: return@mapNotNull null
            val file = describeFile(title)?.takeIf { sameLanguage(it.language, language) } ?: return@mapNotNull null
            val info = (page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject
            val url = info?.str("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Triple(file, url, normalWord(file.word) == wanted)
        }.sortedWith(
            compareByDescending<Triple<AudioFile, String, Boolean>> { it.third }
                .thenBy { it.first.speaker != null }
                .thenBy { it.first.accent.orEmpty() },
        )
        return numbered(
            found.map { (file, url, _) ->
                Recording(url, file.accent ?: file.speaker ?: "Wiktionary", PronunciationSource.WIKTIONARY)
            },
        )
    }

    /** Forvo's answer: its order (best rated first), each voice named by user and country. */
    internal fun parseForvo(body: String): List<Recording> {
        val items = (Json.parseToJsonElement(body) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return numbered(
            items.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = (item.str("pathmp3") ?: item.str("pathogg"))?.takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val user = item.str("username").orEmpty()
                val country = item.str("country")
                val label = listOfNotNull(user.ifEmpty { null }, country?.let { "($it)" }).joinToString(" ").ifEmpty { "Forvo" }
                Recording(url, label, PronunciationSource.FORVO)
            },
        )
    }

    /** Wiktionary's page for [word], asked for every file it uses, with each file's address. */
    fun wiktionaryUrl(word: String): String =
        ServiceEndpoints.base(ServiceEndpoint.WIKTIONARY) + "/w/api.php?action=query&format=json&formatversion=2" +
            "&redirects=1&generator=images&gimlimit=100&prop=imageinfo&iiprop=url&titles=" + ToolHttp.encode(word.trim())

    /** Forvo's pronunciations of [word] in [language], best rated first. */
    fun forvoUrl(key: String, word: String, language: String): String =
        "https://apifree.forvo.com/key/${ToolHttp.encode(key.trim())}/format/json/action/word-pronunciations/word/" +
            ToolHttp.encode(word.trim()).replace("+", "%20") + "/language/" + ToolHttp.encode(language) + "/order/rate-desc"

    /**
     * Every recording of [word] in [language]: Forvo's first when [forvoKey]
     * is set (they are rated, and the user asked for them by adding a key),
     * then Wiktionary's. Blocking; call on an IO dispatcher. A source that
     * fails is skipped; it throws only when every source asked failed, so
     * the caller can fall back to what it kept from an earlier look-up.
     */
    fun fetch(word: String, language: String, forvoKey: String): List<Recording> {
        val failures = ArrayList<Exception>()
        fun asked(fetch: () -> List<Recording>): List<Recording> = try {
            fetch()
        } catch (e: IOException) {
            failures += e
            emptyList()
        } catch (e: SerializationException) {
            failures += e
            emptyList()
        }
        val forvo = if (forvoKey.isBlank()) {
            emptyList()
        } else {
            asked {
                parseForvo(ToolHttp.get(forvoUrl(forvoKey, word, language), source = NetSource.DICTIONARY, route = "/word-pronunciations"))
            }
        }
        val wiktionary = asked {
            parseWiktionary(ToolHttp.get(wiktionaryUrl(word), source = NetSource.DICTIONARY, route = "/w/api.php"), word, language)
        }
        val all = forvo + wiktionary
        if (all.isEmpty() && failures.isNotEmpty() && (forvoKey.isBlank() || failures.size == 2)) throw failures.first()
        return all
    }

    /** The second "US" becomes "US 2", so every chip on the card reads differently. */
    private fun numbered(recordings: List<Recording>): List<Recording> {
        val counts = recordings.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return recordings.map { recording ->
            if ((counts[recording.label] ?: 0) < 2) {
                recording
            } else {
                val n = (seen[recording.label] ?: 0) + 1
                seen[recording.label] = n
                recording.copy(label = "${recording.label} $n")
            }
        }
    }

    private fun normalWord(word: String): String =
        word.replace('_', ' ').trim().trimEnd { it.isDigit() }.trim().lowercase(Locale.ROOT)

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
}
