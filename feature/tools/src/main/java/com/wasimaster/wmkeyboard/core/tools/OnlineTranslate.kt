package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderRunner
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.settings.providerMode
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import java.util.Collections

/**
 * The translate list as the engine switch cuts it. On device takes only the
 * rows that run on the phone, Online only the rest, and Automatic the whole
 * list in its order.
 *
 * In pick mode, with the panel's [pick], the chosen row is the answer
 * whatever the engine says: the panel's chooser lists the device beside the
 * services, and choosing it is choosing the engine.
 */
object TranslateProviders {

    fun rows(
        settings: KeyboardSettings,
        engine: TranslateEngine,
        env: ProviderEnv = ProviderEnvironment.current(),
        pick: String? = null,
    ): List<ResolvedProvider> {
        val rows = settings.resolveProviders(ProviderTool.TRANSLATE, env, pick = pick)
        if (pick != null && settings.providerMode(ProviderTool.TRANSLATE, env) == ChainMode.PICK) return rows
        return rows.filter { row ->
            val device = row.type?.onDevice == true
            when (engine) {
                TranslateEngine.ON_DEVICE -> device
                TranslateEngine.ONLINE -> !device
                TranslateEngine.AUTO -> true
            }
        }
    }
}

/**
 * The translate tool's online providers in the user's order, shared by the
 * tool's panel and the AI's `translate` tool (#698) so the two never disagree
 * about where a sentence goes. The first that answers is the answer; one that
 * fails hands the text to the next row that is on. A row the user switched
 * off is never asked, which is how "my own server and nothing else" is said.
 *
 * Blocking; call on an IO dispatcher. Throws the last provider's failure
 * when none answers.
 */
object OnlineTranslate {

    /**
     * Language pairs DeepL turned down this process, as "row>source>target",
     * so a language it does not have costs one refused request rather than one
     * per keystroke. Forgotten with the process, since DeepL keeps adding languages.
     */
    private val deeplRefusedPairs: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The online rows of the user's list, in order. */
    fun translate(
        settings: KeyboardSettings,
        text: String,
        target: String,
        source: String = TranslateClient.AUTO,
        isActive: () -> Boolean = { true },
    ): Translation = translate(TranslateProviders.rows(settings, TranslateEngine.ONLINE), text, target, source, isActive)

    fun translate(
        rows: List<ResolvedProvider>,
        text: String,
        target: String,
        source: String = TranslateClient.AUTO,
        isActive: () -> Boolean = { true },
    ): Translation {
        val from = source.ifBlank { TranslateClient.AUTO }
        val online = rows.filter { it.type?.onDevice != true }
        if (online.isEmpty()) throw NoProviderException()
        return when (val outcome = ProviderRunner.fallback(online, key = { it.key }) { row -> ask(row, text, target, from, isActive) }) {
            is ProviderRunner.Outcome.Found -> outcome.value
            ProviderRunner.Outcome.NotFound -> throw NoProviderException()
            is ProviderRunner.Outcome.Failed -> throw outcome.error ?: NoProviderException()
        }
    }

    private fun ask(row: ResolvedProvider, text: String, target: String, from: String, isActive: () -> Boolean): Translation =
        when (row.typeId) {
            ProviderTypes.TRANSLATE_SERVER.id -> TranslateServerClient.translate(
                text = text,
                target = target,
                url = row.url,
                model = row.model,
                apiKey = row.apiKey,
                source = from,
                isActive = isActive,
            )
            // A 400 is DeepL not having the language: that pair skips DeepL
            // from then on and goes to the next row.
            ProviderTypes.DEEPL.id -> {
                val pair = "${row.key}>$from>$target"
                if (pair in deeplRefusedPairs) throw DeepLRefusedException()
                try {
                    DeepLClient.translate(text = text, target = target, apiKey = row.apiKey, endpoint = row.url, source = from)
                } catch (failure: ToolHttpException) {
                    if (failure.status == HTTP_BAD_REQUEST) deeplRefusedPairs += pair
                    throw failure
                }
            }
            ProviderTypes.LIBRETRANSLATE.id -> LibreTranslateClient.translate(
                text = text,
                target = target,
                endpoint = row.url,
                apiKey = row.apiKey,
                source = from,
            )
            // Front ends (#726). An engine without the language, or one that
            // cannot detect the source, throws and the next row is asked.
            ProviderTypes.MOZHI.id -> MozhiClient.translate(
                text = text,
                target = target,
                endpoint = row.url,
                engine = row.model,
                source = from,
            )
            ProviderTypes.SIMPLYTRANSLATE.id -> SimplyTranslateClient.translate(
                text = text,
                target = target,
                endpoint = row.url,
                engine = row.model,
                source = from,
            )
            ProviderTypes.LINGVA.id -> LingvaClient.translate(text = text, target = target, endpoint = row.url, source = from)
            ProviderTypes.TRANSLITE.id -> TransliteClient.translate(text = text, target = target, endpoint = row.url, source = from)
            // The keyless public endpoint, or Cloud Translation with a key.
            ProviderTypes.GOOGLE_TRANSLATE.id -> TranslateClient.translate(
                text = text,
                targetLang = target,
                apiKey = row.apiKey,
                sourceLang = from,
            )
            // A plugin that answers nothing for this text hands it to the next row.
            ProviderTypes.PLUGIN.id -> PluginReplies.translation(
                PluginProviders.call(row.key, PluginReplies.translateRequest(text, target, from), isActive),
            ) ?: throw NoProviderException()
            else -> throw NoProviderException()
        }

    /** No online provider is on, or none of those that are on knows how to translate. */
    class NoProviderException : IllegalStateException("No translate provider is on")

    private class DeepLRefusedException : IllegalStateException("DeepL has no model for this pair")

    private const val HTTP_BAD_REQUEST = 400
}
