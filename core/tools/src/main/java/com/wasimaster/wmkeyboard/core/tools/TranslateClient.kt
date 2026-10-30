package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A finished translation plus the language the source was detected as. */
data class Translation(
    val text: String,
    /** ISO 639-1 code of the detected source language, or "" when unknown. */
    val detectedSource: String,
    /** DeepL answered, rather than the tool's usual service. */
    val viaDeepL: Boolean = false,
    /** The user's own translation server answered (issue #435). */
    val viaServer: Boolean = false,
)

/**
 * Translation client with two providers behind one call:
 *  - no API key: Google's free public web endpoint (translate.googleapis.com),
 *    the same one the translate extensions use — fine for personal keyboard
 *    volumes, not an SLA;
 *  - with a key: the official Cloud Translation v2 API.
 * Source language is auto-detected unless the caller names one; both providers
 * report what they detected so the panel can show "Bengali → English".
 */
object TranslateClient {

    /** Longest text we send; keyboard fields beyond this get truncated. */
    const val MAX_CHARS = 2500

    /** The source code that means "work it out": every provider's own default. */
    const val AUTO = "auto"

    /**
     * The languages offered by the pickers: English and Bengali first, as they
     * always were, then by name. Every language ML Kit or Bergamot has a
     * model for is here, plus the ones NLLB-200 reads that Google's online
     * service also has (Amharic, Hausa, Yoruba and dozens more), so a
     * language the phone can translate is never missing from the list, and
     * one the online engines lack falls to the device.
     */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English",
        "bn" to "Bengali",
        "af" to "Afrikaans",
        "sq" to "Albanian",
        "am" to "Amharic",
        "ar" to "Arabic",
        "hy" to "Armenian",
        "as" to "Assamese",
        "ay" to "Aymara",
        "az" to "Azerbaijani",
        "bm" to "Bambara",
        "eu" to "Basque",
        "be" to "Belarusian",
        "bho" to "Bhojpuri",
        "bs" to "Bosnian",
        "bg" to "Bulgarian",
        "my" to "Burmese",
        "ca" to "Catalan",
        "ceb" to "Cebuano",
        "ny" to "Chichewa",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "dz" to "Dzongkha",
        "eo" to "Esperanto",
        "et" to "Estonian",
        "ee" to "Ewe",
        "fo" to "Faroese",
        "fj" to "Fijian",
        "tl" to "Filipino",
        "fi" to "Finnish",
        "fr" to "French",
        "gl" to "Galician",
        "ka" to "Georgian",
        "de" to "German",
        "el" to "Greek",
        "gn" to "Guarani",
        "gu" to "Gujarati",
        "ht" to "Haitian Creole",
        "ha" to "Hausa",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "is" to "Icelandic",
        "ig" to "Igbo",
        "ilo" to "Ilocano",
        "id" to "Indonesian",
        "ga" to "Irish",
        "it" to "Italian",
        "ja" to "Japanese",
        "kn" to "Kannada",
        "kk" to "Kazakh",
        "km" to "Khmer",
        "rw" to "Kinyarwanda",
        "ko" to "Korean",
        "ku" to "Kurdish (Kurmanji)",
        "ckb" to "Kurdish (Sorani)",
        "ky" to "Kyrgyz",
        "lo" to "Lao",
        "lv" to "Latvian",
        "ln" to "Lingala",
        "lt" to "Lithuanian",
        "lg" to "Luganda",
        "lb" to "Luxembourgish",
        "mk" to "Macedonian",
        "mai" to "Maithili",
        "mg" to "Malagasy",
        "ms" to "Malay",
        "ml" to "Malayalam",
        "mt" to "Maltese",
        "mi" to "Maori",
        "mr" to "Marathi",
        "lus" to "Mizo",
        "mn" to "Mongolian",
        "ne" to "Nepali",
        "no" to "Norwegian",
        "oc" to "Occitan",
        "or" to "Odia",
        "om" to "Oromo",
        "ps" to "Pashto",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "pa" to "Punjabi",
        "qu" to "Quechua",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sm" to "Samoan",
        "sa" to "Sanskrit",
        "gd" to "Scottish Gaelic",
        "nso" to "Sepedi",
        "sr" to "Serbian",
        "st" to "Sesotho",
        "sn" to "Shona",
        "sd" to "Sindhi",
        "si" to "Sinhala",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "so" to "Somali",
        "es" to "Spanish",
        "su" to "Sundanese",
        "sw" to "Swahili",
        "sv" to "Swedish",
        "tg" to "Tajik",
        "ta" to "Tamil",
        "tt" to "Tatar",
        "te" to "Telugu",
        "th" to "Thai",
        "bo" to "Tibetan",
        "ti" to "Tigrinya",
        "ts" to "Tsonga",
        "tr" to "Turkish",
        "tk" to "Turkmen",
        "ak" to "Twi",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "ug" to "Uyghur",
        "uz" to "Uzbek",
        "vi" to "Vietnamese",
        "cy" to "Welsh",
        "xh" to "Xhosa",
        "yi" to "Yiddish",
        "yo" to "Yoruba",
        "zu" to "Zulu",
    )

    /**
     * Codes the services report for a language the list spells another way:
     * the on-device engine's bare `zh`, and the legacy ISO codes Google's
     * endpoint still answers with.
     */
    private val NAME_ALIASES = mapOf(
        "zh" to "zh-CN",
        "zh-hans" to "zh-CN",
        "zh-hant" to "zh-TW",
        "iw" to "he",
        "in" to "id",
        "fil" to "tl",
        "nb" to "no",
    )

    /**
     * What a language command typed into the Translate box asks for: `/ja` is
     * the target, `/bn:ja` the source and the target, as `source to target`
     * with a null source. Null for anything else, a code no picker lists
     * included, so a path or a slash that starts a sentence is just text. The
     * slash is what keeps this from eating sentences: `no`, `it` and `to` are
     * language codes and words alike.
     */
    fun languageCommand(typed: String): Pair<String?, String>? {
        if (!typed.startsWith("/")) return null
        val codes = typed.drop(1).split(':')
        return when (codes.size) {
            1 -> pickerCode(codes[0].ifEmpty { return null })?.let { null to it }
            2 -> {
                val source = pickerCode(codes[0].ifEmpty { return null }) ?: return null
                val target = pickerCode(codes[1].ifEmpty { return null }) ?: return null
                source to target
            }
            else -> null
        }
    }

    /**
     * The picker's own code for whatever a service called a language, or null
     * when it is not a language the pickers list. What the swap button needs:
     * a detected `zh` has to become the `zh-CN` the target chip can hold.
     */
    fun pickerCode(code: String): String? {
        val wanted = NAME_ALIASES[code.lowercase()] ?: code
        return languages.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.first
            ?: languages.firstOrNull { it.first.equals(wanted.substringBefore('-'), ignoreCase = true) }?.first
    }

    fun languageName(code: String): String {
        val wanted = NAME_ALIASES[code.lowercase()] ?: code
        return languages.firstOrNull { it.first.equals(wanted, ignoreCase = true) }?.second
            ?: languages.firstOrNull { it.first.equals(wanted.substringBefore('-'), ignoreCase = true) }?.second
            ?: code
    }

    /** Blocking; call on an IO dispatcher. Throws on any failure. */
    fun translate(
        text: String,
        targetLang: String,
        apiKey: String,
        sourceLang: String = AUTO,
    ): Translation {
        val trimmed = text.take(MAX_CHARS)
        val source = sourceLang.ifBlank { AUTO }
        return if (apiKey.isBlank()) translateFree(trimmed, targetLang, source)
        else translateOfficial(trimmed, targetLang, apiKey, source)
    }

    private fun translateFree(text: String, targetLang: String, sourceLang: String): Translation {
        val url = ServiceEndpoints.base(ServiceEndpoint.TRANSLATE_GOOGLE) + "/translate_a/single" +
            "?client=gtx&sl=${ToolHttp.encode(sourceLang)}&tl=${ToolHttp.encode(targetLang)}" +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=${ToolHttp.encode(text)}"
        return parseFree(ToolHttp.get(url, source = NetSource.TRANSLATE, route = "/translate_a/single"))
            .withSourceIfUnknown(sourceLang)
    }

    private fun translateOfficial(
        text: String,
        targetLang: String,
        apiKey: String,
        sourceLang: String,
    ): Translation {
        val form = buildMap {
            put("q", text)
            put("target", targetLang)
            put("format", "text")
            // Cloud Translation detects when `source` is absent; "auto" is not
            // a language it knows.
            if (sourceLang != AUTO) put("source", sourceLang)
        }
        val body = ToolHttp.postForm(
            ServiceEndpoints.base(ServiceEndpoint.TRANSLATE_CLOUD) + "/language/translate/v2?key=${ToolHttp.encode(apiKey)}",
            form,
            source = NetSource.TRANSLATE,
            route = "/language/translate/v2",
        )
        return parseOfficial(body).withSourceIfUnknown(sourceLang)
    }

    /**
     * A service told the source reports no *detected* source, because it
     * detected nothing. The panel still wants a name to show.
     */
    private fun Translation.withSourceIfUnknown(sourceLang: String): Translation =
        if (detectedSource.isBlank() && sourceLang != AUTO) copy(detectedSource = sourceLang) else this

    /**
     * The free endpoint returns a bare nested array:
     * `[[["Hello","হ্যালো",…], ["…","…",…]], null, "bn", …]` — element 0 is
     * the list of translated segments, element 2 the detected language.
     */
    fun parseFree(body: String): Translation {
        val root = Json.parseToJsonElement(body).jsonArray
        val translated = root[0].jsonArray.joinToString("") { segment ->
            segment.jsonArray[0].jsonPrimitive.content
        }
        val detected = root.getOrNull(2)?.jsonPrimitive?.content.orEmpty()
        return Translation(translated, detected)
    }

    fun parseOfficial(body: String): Translation {
        val first = Json.parseToJsonElement(body).jsonObject
            .getValue("data").jsonObject
            .getValue("translations").jsonArray[0].jsonObject
        return Translation(
            text = first.getValue("translatedText").jsonPrimitive.content,
            detectedSource = first["detectedSourceLanguage"]?.jsonPrimitive?.content.orEmpty(),
        )
    }
}
