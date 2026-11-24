package com.wasimaster.wmkeyboard.ime.wmtranslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.providers.ProviderCodec
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.providers.SiblingTranslate
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import com.wasimaster.wmkeyboard.core.tools.AiActionCodec
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiActionSurface
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.tools.orderedAiActions
import com.wasimaster.wmkeyboard.ime.suite.SuiteTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The keyboard's side of the link with WM Translate. Its translate list's WM Translate row asks
 * the app; the translate panel can open a translation there or save it to the app's history;
 * and, when the person shares it, the app reads this keyboard's translate setup.
 *
 * Every call checks WM Translate's signing certificate first. Nothing here works, and nothing is
 * sent, while the app is missing.
 */
object WmTranslateBridge {
    private val APP: Uri = Uri.parse("content://${TranslateWire.APP_AUTHORITY}")
    /** What WM Translate watches for a change of the shared setup. */
    val CONFIG_URI: Uri = Uri.parse("content://${TranslateWire.KEYBOARD_AUTHORITY}/${TranslateWire.CONFIG}")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = Executors.newCachedThreadPool()

    @Volatile
    private var snapshot: KeyboardSettings? = null

    /** Once a process, from the application: the list row's bridge and the copy of settings the share reads. */
    fun start(context: Context, repository: SettingsRepository) {
        val app = context.applicationContext
        SiblingTranslate.install(object : SiblingTranslate.Bridge {
            override fun translate(text: String, target: String, source: String, isActive: () -> Boolean): Translation =
                ask(app, text, target, source, isActive)
        })
        scope.launch {
            repository.settings.map { it to shared(it) }.distinctUntilChanged { a, b -> a.second == b.second }.collect { (s, _) ->
                val first = snapshot == null
                snapshot = s
                // Keeping in step: a changed translate setup tells WM Translate to read it again.
                if (!first && TranslateShare.mode(app) == TranslateWire.SHARE_FOLLOW) notifyConfig(app)
            }
        }
    }

    fun installed(context: Context): Boolean = SuiteTrust.trusted(context, TranslateWire.APP_PACKAGE) == true

    /**
     * WM Translate's AI actions, in this keyboard's own format, under ids that start
     * `wmtranslate_` so importing again replaces rather than adds. Null when the app is
     * missing, untrusted, too old to share them, or has the keyboard cut off. Blocks.
     */
    fun actions(context: Context): List<AiActionSpec>? {
        if (!installed(context)) return null
        val reply = call(context, TranslateWire.ACTIONS, null) ?: return null
        if (reply.getString(TranslateWire.STATE) != TranslateWire.OK) return null
        return AiActionCodec.decodeList(reply.getString(TranslateWire.VALUES).orEmpty())
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.task.isNotBlank() }
            .map { it.copy(surface = AiActionSurface.TRANSFORM) }
    }

    private fun call(context: Context, method: String, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(APP, method, null, extras)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun ask(context: Context, text: String, target: String, source: String, isActive: () -> Boolean): Translation {
        if (!installed(context)) throw IOException("WM Translate is not installed")
        val id = UUID.randomUUID().toString()
        val extras = Bundle().apply {
            putString(TranslateWire.ID, id)
            putString(TranslateWire.TEXT, text.take(TranslateWire.MAX_TEXT))
            putString(TranslateWire.TO, target)
            if (source.isNotBlank()) putString(TranslateWire.FROM, source)
            putString(TranslateWire.ORIGIN, "keyboard")
            putInt(TranslateWire.HOPS, 0)
            putString(TranslateWire.NET, TranslateWire.NET_ALLOW)
        }
        // The call blocks until the app answers; it runs aside so a panel that closes can stop it.
        val future = calls.submit<Bundle?> { call(context, TranslateWire.TRANSLATE, extras) }
        val stop = {
            future.cancel(true)
            call(context, TranslateWire.CANCEL, Bundle().apply { putString(TranslateWire.ID, id) })
        }
        var waited = 0L
        while (true) {
            if (!isActive()) {
                stop()
                // Not an IOException: the list must stop here, not hand stale text to the next row.
                throw CancellationException("Stopped")
            }
            try {
                val reply = future.get(POLL_MS, TimeUnit.MILLISECONDS) ?: throw IOException("WM Translate did not answer")
                if (reply.getString(TranslateWire.STATE) == TranslateWire.OFF) throw IOException("WM Translate does not translate for the keyboard: it is off there")
                reply.getString(TranslateWire.CODE)?.let { throw IOException(reply.getString(TranslateWire.ERROR) ?: it) }
                val result = reply.getString(TranslateWire.RESULT)?.takeIf { it.isNotBlank() } ?: throw IOException("WM Translate had no translation")
                // A language WM Translate does not list falls back to its own target there: not what was asked.
                reply.getString(TranslateWire.TO)?.takeIf { it.isNotBlank() }?.let { to ->
                    if (!sameLanguage(target, to)) throw IOException("WM Translate translated into another language")
                }
                return Translation(text = result, detectedSource = reply.getString(TranslateWire.DETECTED).orEmpty())
            } catch (_: TimeoutException) {
                waited += POLL_MS
                if (waited > LIMIT_MS) { stop(); throw IOException("WM Translate took too long") }
            }
        }
    }

    /** Opens the text and its translation in WM Translate, to work on it there. */
    fun open(context: Context, text: String, result: String, from: String, to: String): Boolean {
        if (!installed(context)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SEND).setClassName(TranslateWire.APP_PACKAGE, "${TranslateWire.APP_PACKAGE}.app.MainActivity")
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text.take(TranslateWire.MAX_TEXT))
                    .apply { if (result.isNotBlank()) putExtra(EXTRA_RESULT, result.take(TranslateWire.MAX_TEXT)) }
                    .apply { if (from.isNotBlank()) putExtra(EXTRA_SOURCE, from) }
                    .putExtra(EXTRA_TARGET, to)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /** Keeps the translation in WM Translate's history. Blocking; false when the app would not. */
    fun save(context: Context, text: String, result: String, from: String, to: String): Boolean {
        if (!installed(context)) return false
        val extras = Bundle().apply {
            putString(TranslateWire.TEXT, text.take(TranslateWire.MAX_TEXT))
            putString(TranslateWire.RESULT, result.take(TranslateWire.MAX_TEXT))
            if (from.isNotBlank()) putString(TranslateWire.FROM, from)
            putString(TranslateWire.TO, to)
        }
        val reply = call(context, TranslateWire.SAVE, extras) ?: return false
        return reply.getString(TranslateWire.STATE) == TranslateWire.OK && reply.getString(TranslateWire.CODE) == null
    }

    fun notifyConfig(context: Context) {
        runCatching { context.applicationContext.contentResolver.notifyChange(CONFIG_URI, null) }
    }

    /**
     * The setup WM Translate may read, as the keyboard's own settings values: the translate list
     * and the credentials it uses, the target language and recents. Only the Translate tool's
     * list: the AI list and its keys stay here. A row that sends text to WM Translate is left out,
     * or the app would send it back.
     */
    internal fun config(): String? {
        val s = snapshot ?: return null
        return shared(s)
    }

    private fun shared(s: KeyboardSettings): String {
        val chain = s.providerChain(ProviderTool.TRANSLATE).let { c -> c.copy(entries = c.entries.filter { it.typeId != ProviderTypes.WM_TRANSLATE.id }) }
        val chains = mapOf(ProviderTool.TRANSLATE to chain)
        val used = chain.entries.map { it.credentialKey }.toSet()
        val credentials = s.providerCredentials().filter { it.id in used && it.typeId != ProviderTypes.WM_TRANSLATE.id }
        return buildJsonObject {
            put("provider_credentials", ProviderCodec.encodeCredentials(credentials))
            put("provider_chains", ProviderCodec.encodeChains(chains))
            put("translate_target_lang", s.translateTargetLang)
            put("translate_recent_langs", JsonPrimitive(s.translateRecentLangs.joinToString(",")))
            // The AI tool's actions, shipped and the user's own, for WM Translate to import.
            // Prompts only: the AI list's providers and keys stay here.
            put("ai_actions", AiActionCodec.encodeList(orderedAiActions(s.ai.customActions, s.ai.actionOrder, AiActionSurface.TRANSFORM)))
        }.toString()
    }

    const val EXTRA_SOURCE = "com.wasimaster.suite.extra.SOURCE"
    const val EXTRA_TARGET = "com.wasimaster.suite.extra.TARGET"
    const val EXTRA_RESULT = "com.wasimaster.suite.extra.RESULT"
    private const val POLL_MS = 100L
    /** Above WM Translate's own wait: up to 10 s for it to start, then 30 s to translate. */
    private const val LIMIT_MS = 45_000L
}

/**
 * Whether WM Translate's [got] names the language [asked] for. The app spells some languages
 * its own way (`zh-CN` for `zh`, `pt` for `pt-BR`, `no` for `nb`), so only the base language
 * is compared, after the old codes Android and Google still use.
 */
internal fun sameLanguage(asked: String, got: String): Boolean {
    fun base(code: String): String {
        val lower = code.trim().replace('_', '-').lowercase().substringBefore('-')
        return LANGUAGE_ALIASES[lower] ?: lower
    }
    return asked.isBlank() || asked.equals("auto", ignoreCase = true) || base(asked) == base(got)
}

private val LANGUAGE_ALIASES = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "fil" to "tl", "nb" to "no", "nn" to "no")
