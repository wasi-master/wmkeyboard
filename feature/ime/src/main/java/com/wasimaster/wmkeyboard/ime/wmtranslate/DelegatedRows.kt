package com.wasimaster.wmkeyboard.ime.wmtranslate

import android.os.Bundle
import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.tools.PluginReplies
import org.json.JSONArray
import org.json.JSONObject

/**
 * The keyboard's translate rows WM Translate cannot run itself: plugin providers, which need the
 * keyboard's plugin host. WM Translate lists them ([TranslateWire.ROWS]) and asks one to translate
 * ([TranslateWire.RUN]); the plugin runs here exactly as it does for the keyboard's own list.
 */
object DelegatedRows {
    /** `[{"row","name"}]` for every plugin translate provider that can run now. */
    fun list(): String {
        val out = JSONArray()
        PluginProviders.rows(ProviderTool.TRANSLATE).filter { it.runnable }.forEach { r ->
            out.put(JSONObject().put("row", r.key).put("name", r.name))
        }
        return out.toString()
    }

    /** Runs [extras]' text through the plugin row it names; the reply carries the result or an error code. Blocking. */
    fun run(extras: Bundle?, reply: Bundle): Bundle {
        val key = extras?.getString(TranslateWire.ROW).orEmpty()
        val text = extras?.getString(TranslateWire.TEXT).orEmpty()
        val to = extras?.getString(TranslateWire.TO).orEmpty()
        val from = extras?.getString(TranslateWire.FROM).orEmpty().ifBlank { "auto" }
        val row = PluginProviders.row(key)?.takeIf { it.runnable && it.tool == ProviderTool.TRANSLATE }
        return when {
            row == null -> reply.apply { putString(TranslateWire.CODE, TranslateWire.NO_ROW); putString(TranslateWire.ERROR, "That plugin is not installed or is off in WM Keyboard") }
            text.isBlank() -> reply.apply { putString(TranslateWire.CODE, TranslateWire.NO_TEXT); putString(TranslateWire.ERROR, "Nothing to translate") }
            text.length > TranslateWire.MAX_TEXT -> reply.apply { putString(TranslateWire.CODE, TranslateWire.TOO_LONG); putString(TranslateWire.ERROR, "Too long") }
            else -> try {
                val t = PluginReplies.translation(PluginProviders.call(row.key, PluginReplies.translateRequest(text, to, from)))
                if (t == null) {
                    reply.apply { putString(TranslateWire.CODE, TranslateWire.FAILED); putString(TranslateWire.ERROR, "${row.name} gave back nothing") }
                } else {
                    reply.apply {
                        putString(TranslateWire.RESULT, t.text)
                        if (t.detectedSource.isNotBlank()) putString(TranslateWire.DETECTED, t.detectedSource)
                        putString(TranslateWire.PROVIDER, row.name)
                    }
                }
            } catch (e: Exception) {
                reply.apply { putString(TranslateWire.CODE, TranslateWire.FAILED); putString(TranslateWire.ERROR, e.message ?: "The plugin failed") }
            }
        }
    }
}
