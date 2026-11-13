package com.wasimaster.wmkeyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.app.CalendarPermissionActivity
import com.wasimaster.wmkeyboard.app.CalendarWritePermissionActivity
import com.wasimaster.wmkeyboard.app.LocationPermissionActivity
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.location.LocationAccess
import com.wasimaster.wmkeyboard.core.location.LocationFix
import com.wasimaster.wmkeyboard.core.location.LocationFixer
import com.wasimaster.wmkeyboard.core.location.LocationGrant
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.plugins.PluginCall
import com.wasimaster.wmkeyboard.core.plugins.PluginCallException
import com.wasimaster.wmkeyboard.core.plugins.PluginHost
import com.wasimaster.wmkeyboard.core.plugins.PluginReminderAlarms
import com.wasimaster.wmkeyboard.core.plugins.PluginSecrets
import com.wasimaster.wmkeyboard.core.plugins.PluginServices
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.todo.TodoReminders
import com.wasimaster.wmkeyboard.core.todo.TodoStore
import com.wasimaster.wmkeyboard.core.todo.TodoTask
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * What the keyboard does for API 5 plugins: the field, the clipboard, the
 * network, the AI model, the phone's small things, the share sheet,
 * notifications, the location, files, sensors and the scanners.
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
    /**
     * Puts up the keyboard's own screen for [PluginHandoff] (a scanner, the
     * file manager, Android's picker) over the plugin that asked, or answers
     * false when that plugin is not the one on screen. Main thread.
     */
    private val startHandoff: (PluginHandoff) -> Boolean,
    /** The plugin's call stopped waiting on [PluginHandoff], answered or not. Main thread. */
    private val handoffDone: (PluginHandoff) -> Unit,
    /** Opens the plugin's own page in Settings, as `plugin.open_settings()` asks. Main thread. */
    private val openSettings: (pluginId: String) -> Unit,
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
            onMain { toast("${call.plugin.displayName}: $text") }
            JsonNull
        }
        "plugin.close" -> {
            onMain { closePanel() }
            JsonNull
        }
        "plugin.open_settings" -> {
            onMain { openSettings(call.plugin.id) }
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
        "notify.show" -> notify(call)
        "notify.cancel" -> {
            PluginReminderAlarms.cancelNotice(context, call.plugin.id, noticeId(call))
            JsonNull
        }
        "location.get" -> location(call)
        "clock.alarm" -> clockAlarm(call)
        "clock.timer" -> clockTimer(call)
        "time.zone" -> zoneRows(call)
        "files.pick" -> pick(call)
        "files.photo" -> photo(call)
        "files.save" -> save(call)
        "sensors.list" -> JsonArray(sensorKinds().map(::JsonPrimitive))
        "sensors.subscribe" -> subscribe(call)
        "sensors.unsubscribe" -> {
            call.string("token")?.let { subscriptions.remove(it)?.countDown() }
            JsonNull
        }
        "scan.qr" -> scan(call, PluginHandoff.Kind.QR)
        "scan.text" -> scan(call, PluginHandoff.Kind.TEXT)
        "calendar.events" -> calendarEvents(call)
        "calendar.calendars" -> calendars()
        "calendar.add" -> calendarAdd(call)
        "todo.list" -> todoList(call)
        "todo.add" -> todoAdd(call)
        "todo.update" -> todoUpdate(call)
        "todo.remove" -> todo(change = true) { store ->
            JsonPrimitive(store.delete(id(call)).isNotEmpty())
        }
        else -> throw PluginCallException("NotImplementedError", "${call.op} is not available in this version of the keyboard yet")
    }

    // ---- the Clock and time zones -----------------------------------------

    /** When each plugin last set an alarm or a timer, so one cannot flood the Clock. */
    private val lastClockSet = ConcurrentHashMap<String, Long>()

    private fun clockSeconds(call: PluginCall, key: String): Double =
        (call.args[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            ?: throw PluginCallException("ValueError", "$key must be a number of seconds")

    private fun clockGate(call: PluginCall) {
        val now = System.currentTimeMillis()
        val last = lastClockSet[call.plugin.id]
        if (last != null && now - last < CLOCK_GAP_MS) {
            throw PluginCallException("ValueError", "one alarm or timer every ${CLOCK_GAP_MS / 1000} seconds at most")
        }
        lastClockSet[call.plugin.id] = now
    }

    /**
     * A one-off alarm in the keyboard's Clock at the minute of `at`, which
     * must be one minute to 24 hours away: a Clock alarm is an hour and a
     * minute, ringing at the next one. Announced with a toast that names the
     * plugin, and from then on the user's own.
     */
    private fun clockAlarm(call: PluginCall): JsonElement {
        val at = (clockSeconds(call, "at") * 1000).toLong()
        val now = System.currentTimeMillis()
        if (at < now + MINUTE_MS) throw PluginCallException("ValueError", "an alarm must be at least a minute away")
        if (at > now + DAY_MS) throw PluginCallException("ValueError", "an alarm rings within the next 24 hours; use reminders for later")
        clockGate(call)
        val label = call.string("label").orEmpty().trim().take(MAX_CLOCK_LABEL)
        val time = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val template = com.wasimaster.wmkeyboard.core.clock.Alarm(
            id = 0,
            hour = time.get(java.util.Calendar.HOUR_OF_DAY),
            minute = time.get(java.util.Calendar.MINUTE),
            label = label,
        )
        com.wasimaster.wmkeyboard.core.clock.ClockEngine.update(context) { data ->
            com.wasimaster.wmkeyboard.core.clock.ClockActions.addAlarm(data, template).second
        }
        val shown = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
        onMain { toast(context.getString(R.string.ime_plugin_clock_alarm_set, call.plugin.displayName, shown)) }
        return JsonNull
    }

    /** A running timer in the keyboard's Clock, 1 second to 24 hours. */
    private fun clockTimer(call: PluginCall): JsonElement {
        val ms = (clockSeconds(call, "seconds") * 1000).toLong()
        if (ms < 1000 || ms > DAY_MS) throw PluginCallException("ValueError", "a timer runs from 1 second to 24 hours")
        clockGate(call)
        val label = call.string("label").orEmpty().trim().take(MAX_CLOCK_LABEL)
        val now = System.currentTimeMillis()
        com.wasimaster.wmkeyboard.core.clock.ClockEngine.update(context) { data ->
            com.wasimaster.wmkeyboard.core.clock.ClockActions.startTimer(data, label, ms, now).second
        }
        val shown = android.text.format.DateUtils.formatElapsedTime(ms / 1000)
        onMain { toast(context.getString(R.string.ime_plugin_clock_timer_set, call.plugin.displayName, shown)) }
        return JsonNull
    }

    /** Built zone tables, by zone id: twenty years of rows each, built once a process. */
    private val zoneTables = ConcurrentHashMap<String, JsonArray>()

    /**
     * `{"rows": [[from, offset], ...]}` for the IANA zone `zone` (the phone's
     * own when empty): the plugin looks up any instant in it, as the
     * interpreter does for the phone's zone. ValueError for a zone Android
     * does not know.
     */
    private fun zoneRows(call: PluginCall): JsonElement {
        val name = call.string("zone").orEmpty().trim()
        val zone = if (name.isEmpty()) {
            java.util.TimeZone.getDefault()
        } else {
            val id = try {
                java.time.ZoneId.of(name).id
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                throw PluginCallException("ValueError", "unknown time zone: ${name.take(MAX_ZONE_NAME)}")
            }
            java.util.TimeZone.getTimeZone(id)
        }
        val rows = zoneTables.getOrPut(zone.id) { com.wasimaster.wmkeyboard.core.plugins.ZoneTable.build(zone) }
        return buildJsonObject {
            put("zone", zone.id)
            put("rows", rows)
        }
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
            PluginHost.requestTarget(call.plugin, url)
            val net = NetLog.call(NetSource.PLUGINS, currentMethod, url, route = "/" + call.plugin.id, background = call.surface == "action")
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.requestMethod = currentMethod
                // Named after the plugin too, with a way to reach us: services
                // such as Open Food Facts, Nominatim, Wikipedia and Transitous
                // ask each app to say who it is and how to contact it.
                connection.setRequestProperty("User-Agent", "$USER_AGENT ${call.plugin.id}/${call.plugin.version} ($CONTACT)")
                for ((k, v) in headers) connection.setRequestProperty(k, v)
                if (body != null && currentMethod !in setOf("GET", "DELETE")) {
                    connection.doOutput = true
                    // The plugin's own type wins: a multipart form, a picture, XML.
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        body.second?.let { connection.setRequestProperty("Content-Type", it) }
                    }
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
                val bytes = stream?.use { readCapped(it, MAX_REPLY) } ?: ByteArray(0)
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

    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw PluginTooLarge()
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
        val chooser = Intent.createChooser(send, call.string("title") ?: call.plugin.displayName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    // ---- notifications ---------------------------------------------------

    /** Titled with the plugin's name, always; the plugin's own title goes in the header beside it. */
    private fun notify(call: PluginCall): JsonElement {
        val text = text(call).take(MAX_NOTICE)
        val title = call.string("title")?.take(MAX_NOTICE_TITLE)
        if (!PluginReminderAlarms.postNotice(context, call.plugin, noticeId(call), title, text)) {
            throw PluginCallException("PermissionError", "notifications are turned off for the keyboard in Android's settings")
        }
        return JsonNull
    }

    private fun noticeId(call: PluginCall): String = call.string("id").orEmpty().take(MAX_NOTICE_ID)

    // ---- the location ----------------------------------------------------

    /**
     * One reading, the way the Location tool takes one. The first call without
     * Android's permission asks for it and fails; the next one works. "coarse"
     * is rounded to about a kilometre even when the keyboard may read more,
     * so a plugin gets no more than it asked for.
     */
    private fun location(call: PluginCall): JsonElement {
        if (!LocationAccess.declared(context)) {
            throw PluginCallException("NotImplementedError", "this build of the keyboard does not read the location")
        }
        if (LocationAccess.grant(context) == LocationGrant.NONE) {
            onMain {
                context.startActivity(Intent(context, LocationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            throw PluginCallException("PermissionError", "Android has not let the keyboard read the location yet: allow it, then try again")
        }
        if (!LocationAccess.servicesOn(context)) throw PluginCallException("RuntimeError", "location is switched off on this phone")
        val fine = call.string("accuracy") == "fine"
        val fixer = LocationFixer(context)
        val fix = (if (fine) null else fixer.lastKnown(COARSE_MAX_AGE_MS)) ?: runBlocking {
            var best: LocationFix? = null
            withTimeoutOrNull(LocationFixer.TIMEOUT_MS + LOCATION_SLACK_MS) {
                fixer.fixes(goodEnoughM = if (fine) LocationFixer.GOOD_ENOUGH_M else COARSE_ENOUGH_M).collect { fix ->
                    best = fix
                    if (!call.isActive()) throw PluginCallException("RuntimeError", "the plugin closed")
                }
            }
            best
        } ?: throw PluginCallException("TimeoutError", "the phone could not tell where it is in time")
        val accuracy = fix.accuracyM?.toDouble() ?: if (fine) 0.0 else COARSE_ACCURACY_M
        return buildJsonObject {
            if (fine) {
                put("lat", fix.point.latitude)
                put("lon", fix.point.longitude)
                put("accuracy", accuracy)
            } else {
                put("lat", Math.round(fix.point.latitude * COARSE_GRID) / COARSE_GRID)
                put("lon", Math.round(fix.point.longitude * COARSE_GRID) / COARSE_GRID)
                put("accuracy", maxOf(accuracy, COARSE_ACCURACY_M))
            }
            put("time", fix.timeMillis / 1000.0)
        }
    }

    // ---- files -----------------------------------------------------------

    /** One file the user picks, in the keyboard's own file manager or Android's picker. Null when they back out. */
    private fun pick(call: PluginCall): JsonElement {
        val handoff = PluginHandoff(call.plugin.id, PluginHandoff.Kind.PICK, mimesFor(call.string("kind")))
        PluginFilePicks.begin(handoff)
        val uri = handOff(call, handoff) as? Uri ?: return JsonNull
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (size > MAX_FILE) throw PluginCallException("ValueError", "the file is larger than ${MAX_FILE / MB} MB")
        val bytes = (
            try {
                resolver.openInputStream(uri)?.use { readCapped(it, MAX_FILE) }
            } catch (tooLarge: PluginTooLarge) {
                throw PluginCallException("ValueError", "the file is larger than ${MAX_FILE / MB} MB")
            } catch (failure: IOException) {
                throw PluginCallException("OSError", failure.message ?: "the file could not be read")
            } catch (refused: SecurityException) {
                throw PluginCallException("OSError", refused.message ?: "the file could not be read")
            }
        ) ?: throw PluginCallException("OSError", "the file could not be read")
        return buildJsonObject {
            put("name", name)
            put("mime", resolver.getType(uri) ?: "application/octet-stream")
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    /**
     * A picture taken with the keyboard's camera, over the plugin: the user
     * shoots, looks, and keeps it with Send, or closes the camera (null). A
     * picture over [MAX_FILE] is made smaller until it fits.
     */
    private fun photo(call: PluginCall): JsonElement {
        val file = handOff(call, PluginHandoff(call.plugin.id, PluginHandoff.Kind.PHOTO)) as? java.io.File ?: return JsonNull
        val bytes = try {
            if (file.length() <= MAX_FILE) file.readBytes() else smallerJpeg(file)
        } catch (failure: IOException) {
            throw PluginCallException("OSError", failure.message ?: "the picture could not be read")
        }
        return buildJsonObject {
            put("name", file.name.takeIf { it.endsWith(".jpg", ignoreCase = true) } ?: "photo.jpg")
            put("mime", "image/jpeg")
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    /** [file] decoded at half size, then half again, until its JPEG fits in [MAX_FILE]. */
    private fun smallerJpeg(file: java.io.File): ByteArray {
        var sample = 2
        while (sample <= MAX_PHOTO_SAMPLE) {
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path, options) ?: throw IOException("not a picture")
            val out = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)
            bitmap.recycle()
            if (out.size() <= MAX_FILE) return out.toByteArray()
            sample *= 2
        }
        throw PluginCallException("ValueError", "the picture is larger than ${MAX_FILE / MB} MB")
    }

    /**
     * Android's save screen, where the user picks the folder and may change
     * the name. Its own screen writes the bytes, so the grant on the place
     * never leaves it. Answers the name saved under, or null.
     */
    private fun save(call: PluginCall): JsonElement {
        val data = call.string("data").orEmpty()
        if (data.length > MAX_FILE / 3 * 4 + 4) throw PluginCallException("ValueError", "a file to save may be at most ${MAX_FILE / MB} MB")
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            ?: throw PluginCallException("ValueError", "the data to save is not bytes")
        val name = name(call).replace('/', '_').replace('\\', '_').trim().take(MAX_FILE_NAME).ifEmpty { "file" }
        val mime = call.string("mime")?.takeIf { '/' in it } ?: "application/octet-stream"
        val handoff = PluginHandoff(call.plugin.id, PluginHandoff.Kind.SAVE, listOf(mime), name)
        PluginFilePicks.begin(handoff, bytes)
        val uri = handOff(call, handoff) as? Uri ?: return JsonNull
        val saved = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        return JsonPrimitive(saved ?: name)
    }

    private fun mimesFor(kind: String?): List<String> = when (val k = kind?.trim()?.lowercase().orEmpty()) {
        "", "any" -> emptyList()
        "image", "text", "audio", "video" -> listOf("$k/*")
        else -> if ('/' in k) listOf(k) else emptyList()
    }

    // ---- the calendar ----------------------------------------------------

    /**
     * Android's calendar permission, asked through the same screen the
     * Calendar tool uses the first time. Like the location, the call that
     * asks fails, and the next one works.
     */
    private fun needCalendar(write: Boolean) {
        val missing = when {
            !DeviceCalendar.canRead(context) -> CalendarPermissionActivity::class.java
            write && !DeviceCalendar.canWrite(context) -> CalendarWritePermissionActivity::class.java
            else -> return
        }
        onMain { context.startActivity(Intent(context, missing).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        throw PluginCallException(
            "PermissionError",
            if (write) "Android has not let the keyboard add calendar events yet: allow it, then try again"
            else "Android has not let the keyboard read your calendar yet: allow it, then try again",
        )
    }

    /** Events from `start` to `end`, seconds since 1970; an all-day one runs between local midnights. */
    private fun calendarEvents(call: PluginCall): JsonElement {
        val start = seconds(call, "start") ?: throw PluginCallException("ValueError", "calendar.events needs a start")
        val end = seconds(call, "end") ?: throw PluginCallException("ValueError", "calendar.events needs an end")
        if (end - start > MAX_CALENDAR_SPAN_S) throw PluginCallException("ValueError", "ask for at most 62 days of events at a time")
        needCalendar(write = false)
        if (end <= start) return JsonArray(emptyList())
        val events = DeviceCalendar.eventsBetween(context, (start * 1000).toLong(), (end * 1000).toLong())
            .sortedBy { if (it.allDay) localMidnightOf(it.begin) else it.begin }
            .take(MAX_EVENTS)
        return JsonArray(
            events.map { e ->
                buildJsonObject {
                    put("id", e.id)
                    put("title", e.title.take(MAX_EVENT_TEXT))
                    put("start", (if (e.allDay) localMidnightOf(e.begin) else e.begin) / 1000.0)
                    put("end", (if (e.allDay) localMidnightOf(e.end) else e.end) / 1000.0)
                    put("all_day", e.allDay)
                    put("location", e.location?.take(MAX_EVENT_TEXT))
                    put("calendar", e.calendarId)
                    put("recurring", e.recurring)
                }
            },
        )
    }

    private fun calendars(): JsonElement {
        needCalendar(write = false)
        return JsonArray(
            DeviceCalendar.writableCalendars(context).map { c ->
                buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("account", c.account)
                    put("primary", c.primary)
                }
            },
        )
    }

    /** One new event, in the calendar the plugin named or the first primary one. Answers its id. */
    private fun calendarAdd(call: PluginCall): JsonElement {
        val title = call.string("title").orEmpty().trim().take(MAX_EVENT_TEXT)
        if (title.isEmpty()) throw PluginCallException("ValueError", "an event needs a title")
        val start = seconds(call, "start") ?: throw PluginCallException("ValueError", "calendar.add needs a start")
        val end = seconds(call, "end") ?: throw PluginCallException("ValueError", "calendar.add needs an end")
        if (end < start) throw PluginCallException("ValueError", "an event cannot end before it starts")
        needCalendar(write = true)
        val writable = DeviceCalendar.writableCalendars(context)
        val asked = (call.args["calendar"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        val calendar = if (asked != null) {
            writable.firstOrNull { it.id == asked } ?: throw PluginCallException("ValueError", "there is no calendar $asked to add events to")
        } else {
            writable.firstOrNull() ?: throw PluginCallException("RuntimeError", "this phone has no calendar to add events to")
        }
        val allDay = call.string("all_day") == "true"
        val (begin, finish) = if (allDay) {
            val first = utcMidnightOfLocalDate((start * 1000).toLong())
            val last = utcMidnightOfLocalDate((end * 1000).toLong())
            first to if (last > first) last else first + DAY_MS
        } else {
            (start * 1000).toLong() to (end * 1000).toLong()
        }
        val id = DeviceCalendar.insertEvent(
            context, calendar.id, title, begin, finish, allDay,
            call.string("location")?.take(MAX_EVENT_TEXT), call.string("notes")?.take(MAX_EVENT_NOTES),
        ) ?: throw PluginCallException("RuntimeError", "the calendar did not take the event")
        return JsonPrimitive(id)
    }

    private fun seconds(call: PluginCall, key: String): Double? =
        (call.args[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    /** The local midnight of the date a UTC midnight names, as an all-day event stores it. */
    private fun localMidnightOf(utcMidnight: Long): Long {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return java.util.Calendar.getInstance().apply {
            clear()
            set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    /** The UTC midnight naming the local date [millis] falls on. */
    private fun utcMidnightOfLocalDate(millis: Long): Long {
        val local = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    // ---- the To-do list --------------------------------------------------

    private val todoLock = Any()

    /**
     * Runs [body] on the To-do tool's own list, read fresh from its file;
     * a [change] is saved and moves the list's reminders, as the To-do panel
     * does. The panel reads the file again the next time it opens.
     */
    private fun <T> todo(change: Boolean, body: (TodoStore) -> T): T {
        val app = context.applicationContext
        if (!DirectBoot.isUserUnlocked(app)) throw PluginCallException("RuntimeError", "the To-do list opens once the phone is unlocked")
        synchronized(todoLock) {
            val store = TodoStore(File(app.filesDir, TodoStore.FILE_PATH))
            val out = body(store)
            if (change) {
                store.save()
                TodoReminders.rearm(app)
            }
            return out
        }
    }

    private fun id(call: PluginCall): Long =
        (call.args["id"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
            ?: throw PluginCallException("ValueError", "which task? pass its id")

    /** A task as `wm.todo` gives it: seconds, and an all-day date as that day's local midnight. */
    private fun taskJson(task: TodoTask, steps: List<TodoTask>?): JsonObject = buildJsonObject {
        put("id", task.id)
        put("text", task.text)
        put("done", task.done)
        val due = task.due
        put("due", if (due == null) null else (if (task.dueAllDay) localMidnightOf(due) else due) / 1000.0)
        put("all_day", task.dueAllDay)
        if (steps != null) put("steps", JsonArray(steps.map { taskJson(it, null) }))
    }

    private fun treeJson(store: TodoStore, task: TodoTask): JsonObject =
        taskJson(task, if (task.parentId == null) store.subtasks(task.id) else null)

    private fun todoList(call: PluginCall): JsonElement = todo(change = false) { store ->
        val withDone = call.string("done") != "false"
        JsonArray(store.topLevel(hideDone = !withDone).take(MAX_TODO_LIST).map { treeJson(store, it) })
    }

    /** `due` from the script: millis, and for an all-day date the UTC midnight the list keeps. */
    private fun dueOf(call: PluginCall): Pair<Long?, Boolean> {
        val due = seconds(call, "due") ?: return null to false
        val allDay = call.string("all_day") == "true"
        val millis = (due * 1000).toLong()
        return (if (allDay) utcMidnightOfLocalDate(millis) else millis) to allDay
    }

    private fun todoAdd(call: PluginCall): JsonElement = todo(change = true) { store ->
        val parent = (call.args["parent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        val (due, allDay) = dueOf(call)
        val added = store.add(call.string("text").orEmpty(), parentId = parent, due = due, dueAllDay = allDay)
            ?: throw PluginCallException(
                "ValueError",
                when {
                    call.string("text").isNullOrBlank() -> "a task needs text"
                    parent != null && store.item(parent) == null -> "there is no task $parent"
                    else -> "the To-do list is full"
                },
            )
        treeJson(store, added)
    }

    private fun todoUpdate(call: PluginCall): JsonElement = todo(change = true) { store ->
        val id = id(call)
        if (store.item(id) == null) return@todo JsonNull
        call.string("text")?.let { store.setText(id, it) }
        call.string("done")?.let { store.setDone(id, it == "true") }
        if ("due" in call.args) {
            val (due, allDay) = dueOf(call)
            store.setDue(id, due, allDay)
        }
        store.item(id)?.let { treeJson(store, it) } ?: JsonNull
    }

    // ---- the scanners ----------------------------------------------------

    /** The keyboard's QR scanner or text reader, over the plugin. The text the user took, or null. */
    private fun scan(call: PluginCall, kind: PluginHandoff.Kind): JsonElement {
        val text = handOff(call, PluginHandoff(call.plugin.id, kind)) as? String ?: return JsonNull
        return JsonPrimitive(text.take(MAX_SCAN))
    }

    /**
     * Puts up the screen for [handoff] and waits for the user, on the host's
     * pool. Only for a plugin on screen: a selection action has no panel to
     * come back to.
     */
    private fun handOff(call: PluginCall, handoff: PluginHandoff): Any? {
        if (call.surface == "action") {
            PluginFilePicks.cancel(handoff)
            throw PluginCallException("RuntimeError", "${call.op} works only from the plugin's panel")
        }
        if (onMain { startHandoff(handoff) } != true) {
            PluginFilePicks.cancel(handoff)
            throw PluginCallException("RuntimeError", "${call.op} works only while the plugin is on screen")
        }
        try {
            return handoff.await(call.isActive, HANDOFF_TIMEOUT_MS)
        } finally {
            PluginFilePicks.cancel(handoff)
            main.post { handoffDone(handoff) }
        }
    }

    // ---- sensors ---------------------------------------------------------

    /** Open subscriptions, by the call's token: count one down to end it. */
    private val subscriptions = ConcurrentHashMap<String, CountDownLatch>()

    /** Where readings arrive, off the main thread. Made by the first subscription. */
    private val sensorHandler: Handler by lazy {
        Handler(HandlerThread("wm-plugin-sensors").apply { start() }.looper)
    }

    private fun sensorManager(): SensorManager? = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** The kinds this phone has, and steps only with Android's activity permission. */
    private fun sensorKinds(): List<String> {
        val manager = sensorManager() ?: return emptyList()
        return SENSOR_TYPES.filter { (kind, type) ->
            manager.getDefaultSensor(type) != null && (kind != "steps" || stepsAllowed())
        }.keys.toList()
    }

    private fun stepsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /**
     * Readings at most [hz] a second, each one a stream piece, until the
     * plugin unsubscribes or its session ends (the panel closing ends it).
     * Holds a pool thread the whole time, which is what the async call is for.
     */
    private fun subscribe(call: PluginCall): JsonElement {
        val kind = call.string("kind").orEmpty()
        val type = SENSOR_TYPES[kind]
            ?: throw PluginCallException("ValueError", "unknown sensor '$kind': use one of ${SENSOR_TYPES.keys.joinToString()}")
        val token = call.string("async")
            ?: throw PluginCallException("ValueError", "sensors.subscribe answers with on_reading, never by waiting")
        if (kind == "steps" && !stepsAllowed()) {
            throw PluginCallException("PermissionError", "Android has not let the keyboard count steps")
        }
        val manager = sensorManager() ?: throw PluginCallException("RuntimeError", "this phone has no sensors")
        val sensor: Sensor = manager.getDefaultSensor(type) ?: throw PluginCallException("RuntimeError", "this phone has no $kind sensor")
        if (subscriptions.size >= MAX_SUBSCRIPTIONS) {
            throw PluginCallException("RuntimeError", "too many sensors at once: at most $MAX_SUBSCRIPTIONS")
        }
        val hz = ((call.args["hz"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_HZ).coerceIn(1, MAX_HZ)
        val gapNanos = NANOS_PER_SECOND / hz
        val stop = CountDownLatch(1)
        subscriptions[token] = stop
        val listener = object : SensorEventListener {
            private var last = 0L

            override fun onSensorChanged(event: SensorEvent) {
                if (last != 0L && event.timestamp - last < gapNanos) return
                last = event.timestamp
                call.emit(reading(kind, event.values))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            if (!manager.registerListener(listener, sensor, (MICROS_PER_SECOND / hz).toInt(), sensorHandler)) {
                throw PluginCallException("RuntimeError", "the $kind sensor would not start")
            }
            while (!stop.await(SENSOR_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!call.isActive()) break
            }
        } finally {
            manager.unregisterListener(listener)
            subscriptions.remove(token)
        }
        return JsonNull
    }

    private fun reading(kind: String, values: FloatArray): JsonElement = buildJsonObject {
        if (kind in THREE_AXES) {
            put("x", values.getOrElse(0) { 0f }.toDouble())
            put("y", values.getOrElse(1) { 0f }.toDouble())
            put("z", values.getOrElse(2) { 0f }.toDouble())
        } else {
            put("value", values.getOrElse(0) { 0f }.toDouble())
        }
    }

    /** Lets go of the voice. The service calls it when it is destroyed. */
    fun shutdown() {
        subscriptions.values.forEach { it.countDown() }
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
        private const val CONTACT = "+https://wmkeyboard.pages.dev"
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
        private const val MAX_NOTICE = 1000
        private const val MAX_NOTICE_TITLE = 100
        private const val MAX_NOTICE_ID = 64
        private const val MAX_SCAN = 64 * 1024
        private const val MB = 1024 * 1024
        private const val MAX_FILE = 4 * MB
        private const val MAX_FILE_NAME = 120
        private const val MAX_CLOCK_LABEL = 100
        private const val MAX_ZONE_NAME = 64
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L

        /** Shortest time between two alarms or timers from one plugin. */
        private const val CLOCK_GAP_MS = 3_000L

        /** The longest span `calendar.events` reads at once, in seconds: 62 days. */
        private const val MAX_CALENDAR_SPAN_S = 62.0 * 24 * 60 * 60
        private const val MAX_EVENTS = 500
        private const val MAX_EVENT_TEXT = 500
        private const val MAX_EVENT_NOTES = 4000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_TODO_LIST = 1000
        private const val MAX_PHOTO_SAMPLE = 16
        private const val PHOTO_QUALITY = 90

        /** How long a scan or a pick may stay open before the plugin stops waiting. */
        private const val HANDOFF_TIMEOUT_MS = 10 * 60_000L

        /** A reading Android already holds is good enough for "coarse" while it is this young. */
        private const val COARSE_MAX_AGE_MS = 10 * 60_000L
        private const val COARSE_ENOUGH_M = 2000f
        private const val COARSE_ACCURACY_M = 1500.0

        /** Hundredths of a degree: about a kilometre. */
        private const val COARSE_GRID = 100.0
        private const val LOCATION_SLACK_MS = 2000L

        private val SENSOR_TYPES = linkedMapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "light" to Sensor.TYPE_LIGHT,
            "steps" to Sensor.TYPE_STEP_COUNTER,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "pressure" to Sensor.TYPE_PRESSURE,
        )
        private val THREE_AXES = setOf("accelerometer", "gyroscope", "magnetometer")
        private const val MAX_SUBSCRIPTIONS = 8
        private const val DEFAULT_HZ = 5
        private const val MAX_HZ = 30
        private const val SENSOR_POLL_MS = 200L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MICROS_PER_SECOND = 1_000_000L
    }
}
