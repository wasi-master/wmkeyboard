package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * DeepL, with the user's own key: Translate for the translate tool, and Write
 * (`/v2/write/rephrase`) for "polish this text" in the grammar tool and the
 * selection bar. Issue #331.
 *
 * Nothing here runs unless the user pasted a key or an address into the
 * Translate tool's settings; there is no built-in key and no default use.
 *
 * The host follows the key: DeepL marks API Free keys with a `:fx` suffix and
 * serves them from `api-free.deepl.com`, everything else from `api.deepl.com`.
 * A custom [endpoint] replaces both, for a proxy the user runs (DeepLX and the
 * like speak the same `/v2/translate` shape). Write is an API Pro feature, so
 * a Free key gets a 403 from it, which [rephrase] words as such.
 */
object DeepLClient {

    /** Longest text we send, matching [TranslateClient.MAX_CHARS]. */
    const val MAX_CHARS = TranslateClient.MAX_CHARS

    private const val FREE_HOST = "https://api-free.deepl.com"
    private const val PRO_HOST = "https://api.deepl.com"

    /** HTTP status DeepL answers with when the month's character allowance is spent. */
    private const val QUOTA_EXCEEDED = 456

    /** What Write gave back, with the language it read the text as. */
    data class Rephrase(val text: String, val detectedLanguage: String)

    /**
     * Blocking; call on an IO dispatcher. Throws [ToolHttpException] on an HTTP
     * failure. A 400 means DeepL does not take this language pair, which the
     * caller can answer by going to its other service.
     */
    fun translate(
        text: String,
        target: String,
        apiKey: String,
        endpoint: String = "",
        source: String = TranslateClient.AUTO,
    ): Translation {
        val payload = buildJsonObject {
            putJsonArray("text") { add(text.take(MAX_CHARS)) }
            put("target_lang", targetCode(target))
            sourceCode(source)?.let { put("source_lang", it) }
        }
        val body = post(apiKey, endpoint, "/v2/translate", payload.toString(), NetSource.TRANSLATE)
        val parsed = parseTranslation(body)
        return if (parsed.detectedSource.isBlank() && source.isNotBlank() && source != TranslateClient.AUTO) {
            parsed.copy(detectedSource = source)
        } else {
            parsed
        }
    }

    /**
     * DeepL Write: the same text in the same language, corrected and smoothed.
     * [writingStyle] and [tone] are DeepL's own values (`prefer_business`,
     * `prefer_friendly`); at most one is sent, since DeepL refuses both at once.
     */
    fun rephrase(
        text: String,
        apiKey: String,
        endpoint: String = "",
        writingStyle: String? = null,
        tone: String? = null,
    ): Rephrase {
        val payload = buildJsonObject {
            putJsonArray("text") { add(text.take(MAX_CHARS)) }
            when {
                !writingStyle.isNullOrBlank() -> put("writing_style", writingStyle)
                !tone.isNullOrBlank() -> put("tone", tone)
            }
        }
        val body = try {
            post(apiKey, endpoint, "/v2/write/rephrase", payload.toString(), NetSource.DEEPL_WRITE)
        } catch (e: ToolHttpException) {
            throw when (e.status) {
                // A Free key reaches Write and is turned away: say why, since
                // the same key translates fine.
                401, 403 -> ToolHttpException(R.string.core_tools_error_deepl_write_key, e.status)
                400 -> ToolHttpException(R.string.core_tools_error_deepl_write_language, e.status)
                else -> e
            }
        }
        return parseRephrase(body)
    }

    private fun post(apiKey: String, endpoint: String, path: String, body: String, source: NetSource): String {
        val headers = buildMap {
            put("Accept", "application/json")
            if (apiKey.isNotBlank()) put("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
        }
        return try {
            ToolHttp.postJson(
                url = baseUrl(apiKey, endpoint) + path,
                body = body,
                timeoutMs = 20_000,
                headers = headers,
                source = source,
                route = path,
            ).also { if (apiKey.isNotBlank()) ServiceUsage.record(USAGE_KEY) }
        } catch (e: ToolHttpException) {
            if (e.status == QUOTA_EXCEEDED) throw ToolHttpException(R.string.core_tools_error_deepl_quota, e.status)
            throw e
        }
    }

    /** Where DeepL says how much of the billing period's characters are used (#1382). */
    fun usageUrl(apiKey: String, endpoint: String): String = baseUrl(apiKey, endpoint) + "/v2/usage"

    /** The [ServiceUsage] key DeepL's requests count under. */
    const val USAGE_KEY = "deepl"

    /**
     * The root every path hangs off, without a trailing slash: the user's
     * [endpoint] when they gave one, else the host their key belongs to.
     *
     * An endpoint may be pasted as a bare host, with a trailing slash, or with
     * the `/v2` or a full `/v2/translate` path on the end, as people copy it
     * out of a README; all of those reduce to the same root. A scheme other
     * than http/https is refused rather than guessed at.
     */
    internal fun baseUrl(apiKey: String, endpoint: String): String {
        val raw = endpoint.trim()
        if (raw.isEmpty()) return if (apiKey.trim().endsWith(":fx")) FREE_HOST else PRO_HOST
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            // A bare host: assume TLS rather than falling back to cleartext.
            !raw.contains("://") -> "https://$raw"
            else -> throw IllegalArgumentException("unsupported scheme in DeepL URL")
        }
        var base = withScheme.trimEnd('/')
        for (suffix in listOf("/v2/write/rephrase", "/v2/translate", "/v2")) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
                break
            }
        }
        return base.trimEnd('/')
    }

    /**
     * DeepL's code for a picker language as a target. DeepL wants the regional
     * form for English and Portuguese (the bare codes are deprecated there),
     * and names the Chinese scripts and Norwegian its own way.
     */
    internal fun targetCode(picker: String): String = when (picker.lowercase()) {
        "en" -> "EN-US"
        // Google's "pt" is Brazilian, and the panel promises the same language
        // whichever service answers.
        "pt" -> "PT-BR"
        "zh-cn", "zh" -> "ZH-HANS"
        "zh-tw" -> "ZH-HANT"
        "no", "nb" -> "NB"
        else -> picker.uppercase()
    }

    /** DeepL's code for a picker language as a source, or null to detect it. */
    internal fun sourceCode(picker: String): String? {
        val code = picker.trim().lowercase()
        if (code.isEmpty() || code == TranslateClient.AUTO) return null
        return when {
            code.startsWith("zh") -> "ZH"
            code == "no" -> "NB"
            else -> code.substringBefore('-').uppercase()
        }
    }

    /** `{"translations":[{"detected_source_language":"EN","text":"…"}]}` */
    internal fun parseTranslation(body: String): Translation {
        val first = Json.parseToJsonElement(body).jsonObject["translations"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ToolHttpException(R.string.core_tools_error_deepl_empty)
        return Translation(
            text = first["text"]?.jsonPrimitive?.content.orEmpty(),
            // The panel's names are keyed by lower-case codes ("zh", "nb" and
            // the rest resolve through TranslateClient's aliases).
            detectedSource = first["detected_source_language"]?.jsonPrimitive?.content.orEmpty().lowercase(),
            viaDeepL = true,
        )
    }

    /** `{"improvements":[{"text":"…","detected_source_language":"en","target_language":"en-US"}]}` */
    internal fun parseRephrase(body: String): Rephrase {
        val improvements = Json.parseToJsonElement(body).jsonObject["improvements"] as? JsonArray
        val first = improvements?.firstOrNull()?.jsonObject
            ?: throw ToolHttpException(R.string.core_tools_error_deepl_empty)
        return Rephrase(
            text = first["text"]?.jsonPrimitive?.content.orEmpty(),
            detectedLanguage = first["detected_source_language"]?.jsonPrimitive?.content.orEmpty().lowercase(),
        )
    }
}
