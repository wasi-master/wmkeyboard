package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.plugins.PluginCall
import com.wasimaster.wmkeyboard.core.plugins.PluginCallException
import com.wasimaster.wmkeyboard.core.plugins.PluginHost
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginSecrets
import com.wasimaster.wmkeyboard.core.plugins.PluginServices
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What the keyboard does for API 5 plugins: the field, the clipboard, the
 * network, the AI model, the phone's small things and the share sheet.
 * [PluginHost] has already checked the manifest, the user's grants, the
 * risky switch, the field and Data saver before any of this runs.
 *
 * Runs on a plugin thread or the host's pool. Anything that touches the
 * input connection hops to the main thread and waits for it, which is safe
 * because the main thread never waits on a plugin.
 */
internal class PluginKeyboardServices(
    private val context: Context,
    /** The connection to the field in front, read on the main thread. */
    private val connection: () -> InputConnection?,
    /** A password field, incognito, or a field the keyboard keeps to itself. */
    private val protected: () -> Boolean,
    /** Data saving in effect right now. */
    private val dataSaver: () -> Boolean,
    private val aiSettings: () -> AiSettings,
    /** The newest clip the user may hand a plugin, or null (none, or private). */
    private val clipboardText: () -> String?,
    private val copy: (String) -> Unit,
    private val toast: (String) -> Unit,
    private val vibrate: (Int) -> Unit,
    private val closePanel: () -> Unit,
) : PluginServices {

    private val main = Handler(Looper.getMainLooper())

    override fun fieldProtected(): Boolean = onMain { protected() } ?: true

    override fun dataSaver(): Boolean = dataSaver.invoke()

    override fun call(call: PluginCall): JsonElement = when (call.op) {
        "http.request" -> http(call)
        "ai.ask", "ai.stream" -> ai(call)
        "ai.available" -> JsonPrimitive(AiClient.isConfigured(aiSettings()))
        "field.selection" -> field { ic -> ic.getSelectedText(0)?.toString().orEmpty() }
        "field.before" -> field { ic -> ic.getTextBeforeCursor(count(call), 0)?.toString().orEmpty() }
        "field.after" -> field { ic -> ic.getTextAfterCursor(count(call), 0)?.toString().orEmpty() }
        "field.info" -> fieldInfo()
        "field.insert", "field.replace" -> edit { ic -> ic.commitText(text(call).take(MAX_INSERT), 1) }
        "field.delete" -> edit { ic ->
            ic.deleteSurroundingText(
                ((call.args["before"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(0, MAX_DELETE),
                ((call.args["after"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, MAX_DELETE),
            )
        }
        "field.move" -> edit { ic -> moveCursor(ic, (call.args["by"] as? JsonPrimitive)?.intOrNull ?: 0) }
        "field.action" -> edit { ic -> ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) }
        "clipboard.get" -> JsonPrimitive(onMain { clipboardText() }.orEmpty())
        "clipboard.set" -> {
            val text = text(call).take(MAX_CLIP)
            onMain { copy(text) }
            JsonNull
        }
        "ui.toast" -> {
            val text = text(call).take(MAX_TOAST)
            onMain { toast("${call.plugin.name}: $text") }
            JsonNull
        }
        "plugin.close" -> {
            onMain { closePanel() }
            JsonNull
        }
        "plugin.refresh" -> JsonNull
        "device.vibrate" -> {
            val ms = (call.args["ms"] as? JsonPrimitive)?.intOrNull ?: 30
            onMain { vibrate(ms.coerceIn(1, 1000)) }
            JsonNull
        }
        "device.flashlight" -> flashlight((call.args["on"] as? JsonPrimitive)?.contentOrNull == "true")
        "device.speak" -> {
            val text = text(call).take(MAX_SPEAK)
            val language = call.string("language")
            onMain { speak(text, language) }
            JsonNull
        }
        "device.battery" -> battery()
        "share.text" -> share(call)
        "share.open" -> open(call)
        "secrets.get" -> PluginSecrets.of(context, call.plugin.id).get(name(call))?.let(::JsonPrimitive) ?: JsonNull
        "secrets.set" -> {
            PluginSecrets.of(context, call.plugin.id).set(name(call), call.string("value").orEmpty())
                ?.let { throw PluginCallException("ValueError", it) }
            JsonNull
        }
        "secrets.remove" -> {
            PluginSecrets.of(context, call.plugin.id).remove(name(call))
            JsonNull
        }
        else -> throw PluginCallException("NotImplementedError", "${call.op} is not available in this version of the keyboard yet")
    }

    // ---- the field -------------------------------------------------------

    private fun field(read: (InputConnection) -> String): JsonElement {
        val text = onMain { connection()?.let(read) }
            ?: throw PluginCallException("RuntimeError", "there is no text field in front right now")
        return JsonPrimitive(text)
    }

    private fun edit(body: (InputConnection) -> Unit): JsonElement {
        val done = onMain {
            val ic = connection() ?: return@onMain false
            ic.beginBatchEdit()
            try {
                body(ic)
            } finally {
                ic.endBatchEdit()
            }
            true
        }
        if (done != true) throw PluginCallException("RuntimeError", "there is no text field in front right now")
        return JsonNull
    }

    private fun moveCursor(ic: InputConnection, by: Int) {
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val at = (extracted.startOffset + extracted.selectionEnd + by).coerceAtLeast(0)
        ic.setSelection(at, at)
    }

    private fun fieldInfo(): JsonElement = onMain {
        val info = (context as? android.inputmethodservice.InputMethodService)?.currentInputEditorInfo
        buildJsonObject {
            put("app", info?.packageName.orEmpty())
            put("hint", info?.hintText?.toString().orEmpty().take(MAX_HINT))
            val variation = (info?.inputType ?: 0) and android.text.InputType.TYPE_MASK_VARIATION
            val klass = (info?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS
            put(
                "kind",
                when {
                    klass == android.text.InputType.TYPE_CLASS_NUMBER -> "number"
                    klass == android.text.InputType.TYPE_CLASS_PHONE -> "phone"
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "email"
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_URI -> "url"
                    else -> "text"
                },
            )
            put("multiline", ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0)
        }
    } ?: JsonObject(emptyMap())

    // ---- the network -----------------------------------------------------

    /**
     * One request, redirects followed by hand so each new host is checked
     * against the plugin's list again. No cookies, the keyboard's own user
     * agent, 1 MB at most, and a row in Network activity for each hop.
     */
    private fun http(call: PluginCall): JsonElement {
        val method = call.string("method")?.uppercase()?.takeIf { it in METHODS } ?: "GET"
        val timeout = (((call.args["timeout"] as? JsonPrimitive)?.doubleOrNull ?: 15.0).coerceIn(1.0, 15.0) * 1000).toInt()
        val binary = call.string("binary") == "true"
        var url = withParams(call.string("url").orEmpty(), call.args["params"] as? JsonObject)
        val headers = (call.args["headers"] as? JsonObject).orEmpty()
            .mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .filterKeys { it.lowercase() !in FORBIDDEN_HEADERS }
        val body = body(call)
        var currentMethod = method
        repeat(MAX_REDIRECTS + 1) {
            val host = PluginHost.requestHost(url) ?: throw PluginCallException("ValueError", "only https addresses can be requested: $url")
            if (!PluginManifestCodec.hostAllowed(host, call.plugin.allowedHosts)) {
                throw PluginCallException("PermissionError", "this plugin is not allowed to connect to $host")
            }
            val net = NetLog.call(NetSource.PLUGINS, currentMethod, url, route = "/" + call.plugin.id, background = call.surface == "action")
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.requestMethod = currentMethod
                connection.setRequestProperty("User-Agent", USER_AGENT)
                for ((k, v) in headers) connection.setRequestProperty(k, v)
                if (body != null && currentMethod !in setOf("GET", "DELETE")) {
                    connection.doOutput = true
                    body.second?.let { connection.setRequestProperty("Content-Type", it) }
                    connection.outputStream.use { it.write(body.first) }
                    net.sent(body.first.size.toLong())
                }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && location != null) {
                    url = URL(URL(url), location).toString()
                    if (status == 303 || ((status == 301 || status == 302) && currentMethod == "POST")) currentMethod = "GET"
                    net.end()
                    return@repeat
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
                net.received(bytes.size.toLong())
                net.end()
                return buildJsonObject {
                    put("status", status)
                    put("reason", connection.responseMessage.orEmpty())
                    put("url", url)
                    put(
                        "headers",
                        buildJsonObject {
                            for ((k, v) in connection.headerFields) if (k != null && v.isNotEmpty()) put(k, v.joinToString(", "))
                        },
                    )
                    put("text", if (binary) "" else bytes.decodeToString())
                    if (binary) put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } catch (timeoutError: SocketTimeoutException) {
                net.fail(timeoutError)
                throw PluginCallException("TimeoutError", "the request took longer than ${timeout / 1000} seconds")
            } catch (failure: IOException) {
                net.fail(failure)
                if (failure is PluginTooLarge) throw PluginCallException("ConnectionError", "the reply is larger than 1 MB")
                throw PluginCallException("ConnectionError", failure.message ?: failure.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
        throw PluginCallException("ConnectionError", "too many redirects")
    }

    private class PluginTooLarge : IOException("too large")

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_REPLY) throw PluginTooLarge()
        }
        return out.toByteArray()
    }

    private fun withParams(url: String, params: JsonObject?): String {
        if (params.isNullOrEmpty()) return url
        val query = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode((v as? JsonPrimitive)?.contentOrNull.orEmpty(), "UTF-8")
        }
        return url + (if ('?' in url) "&" else "?") + query
    }

    /** The request body and its content type, from `body`, `form` or `bodyBytes`. */
    private fun body(call: PluginCall): Pair<ByteArray, String?>? {
        call.string("body")?.let { return it.toByteArray() to null }
        (call.args["form"] as? JsonObject)?.let { form ->
            val text = form.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode((v as? JsonPrimitive)?.contentOrNull.orEmpty(), "UTF-8")
            }
            return text.toByteArray() to "application/x-www-form-urlencoded"
        }
        call.string("bodyBytes")?.let { return Base64.decode(it, Base64.DEFAULT) to "application/octet-stream" }
        return null
    }

    // ---- the AI model ----------------------------------------------------

    private fun ai(call: PluginCall): JsonElement {
        val settings = aiSettings()
        if (!AiClient.isConfigured(settings)) {
            throw PluginCallException("RuntimeError", "no AI model is set up in the keyboard's settings")
        }
        if (dataSaver()) throw PluginCallException("ConnectionError", "Data saver is on, so plugins cannot use the internet")
        val prompt = call.string("prompt").orEmpty().take(MAX_PROMPT)
        val system = call.string("system").orEmpty().take(MAX_PROMPT)
        val stream = call.op == "ai.stream"
        var sent = 0
        val answer = AiClient.completeStreaming(
            config = AiClient.config(settings),
            system = system,
            user = prompt,
            maxTokens = (call.args["maxTokens"] as? JsonPrimitive)?.intOrNull ?: AiClient.effectiveMaxTokens(settings),
            onPhase = {},
            onPartial = { partial ->
                if (stream && partial.length > sent) {
                    call.emit(JsonPrimitive(partial.substring(sent)))
                    sent = partial.length
                }
            },
            isActive = call.isActive,
        ).text
        return buildJsonObject { put("text", AiThinking.stripped(answer)) }
    }

    // ---- the phone -------------------------------------------------------

    private fun flashlight(on: Boolean): JsonElement {
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw PluginCallException("RuntimeError", "this phone has no flashlight")
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw PluginCallException("RuntimeError", "this phone has no flashlight")
        runCatching { cameras.setTorchMode(id, on) }.onFailure {
            throw PluginCallException("RuntimeError", "the flashlight is busy")
        }
        return JsonNull
    }

    private fun battery(): JsonElement {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return buildJsonObject {
            put("level", manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            put("charging", manager?.isCharging == true)
        }
    }

    /** The system share sheet, which names the text and lets the user pick or cancel. */
    private fun share(call: PluginCall): JsonElement {
        val text = text(call).take(MAX_SHARE)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, call.string("title") ?: call.plugin.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { context.startActivity(chooser) }
        return JsonNull
    }

    /** An https link, through the chooser so the user sees the address and picks the browser. */
    private fun open(call: PluginCall): JsonElement {
        val url = call.string("url").orEmpty()
        if (PluginHost.requestHost(url) == null) throw PluginCallException("ValueError", "only https links can be opened")
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val chooser = Intent.createChooser(view, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onMain { context.startActivity(chooser) }
        return JsonNull
    }

    /** The phone's own voice, made the first time a plugin speaks and kept for the next. Main thread. */
    private var voice: android.speech.tts.TextToSpeech? = null
    private var voiceReady = false
    private var waiting: Pair<String, String?>? = null

    private fun speak(text: String, language: String?) {
        val ready = voice
        if (ready != null && voiceReady) {
            language?.let { runCatching { ready.language = java.util.Locale.forLanguageTag(it) } }
            ready.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "plugin")
            return
        }
        waiting = text to language
        if (ready == null) {
            voice = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                voiceReady = status == android.speech.tts.TextToSpeech.SUCCESS
                waiting?.let { (t, l) ->
                    waiting = null
                    if (voiceReady) speak(t, l)
                }
            }
        }
    }

    /** Lets go of the voice. The service calls it when it is destroyed. */
    fun shutdown() {
        main.post {
            voice?.shutdown()
            voice = null
            voiceReady = false
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun text(call: PluginCall): String = call.string("text").orEmpty()

    private fun name(call: PluginCall): String = call.string("name").orEmpty()

    private fun count(call: PluginCall): Int = ((call.args["n"] as? JsonPrimitive)?.intOrNull ?: 1000).coerceIn(0, MAX_READ)

    /** Runs [body] on the main thread and waits for it, at most a few seconds. Null when it did not finish. */
    private fun <T> onMain(body: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return body()
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                result.set(body())
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_WAIT_SECONDS, TimeUnit.SECONDS)) return null
        error.get()?.let { throw PluginCallException("RuntimeError", it.message ?: it.javaClass.simpleName) }
        return result.get()
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    companion object {
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /** Headers a plugin may not set: who the request is from, and how it is routed. */
        private val FORBIDDEN_HEADERS = setOf("host", "cookie", "user-agent", "proxy-authorization", "connection", "content-length")
        private const val USER_AGENT = "WMKeyboard-Plugin"
        private const val MAX_REDIRECTS = 5
        private const val MAX_REPLY = 1024 * 1024
        private const val BUFFER = 8 * 1024
        private const val MAX_INSERT = 64 * 1024
        private const val MAX_DELETE = 10_000
        private const val MAX_READ = 10_000
        private const val MAX_CLIP = 64 * 1024
        private const val MAX_TOAST = 200
        private const val MAX_SPEAK = 4000
        private const val MAX_SHARE = 64 * 1024
        private const val MAX_HINT = 200
        private const val MAX_PROMPT = 32 * 1024
        private const val MAIN_WAIT_SECONDS = 5L
    }
}
