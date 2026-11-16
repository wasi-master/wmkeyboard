package com.wasimaster.wmkeyboard.docshots

import com.wasimaster.wmkeyboard.core.settings.toolKeysOf
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.hardware.Sensor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.PanelMode
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/*
 * Keyboard shots that need something the JVM does not have on its own: a web
 * service, a sensor, a calendar, a contact, a recogniser. Each fake answers
 * with fixed data, so a shot reads the same on every run and nothing leaves
 * the machine. Everything here is torn down after each shot by [resetFakes].
 */

/** Which of light and dark to render: `-Pwmkb.docShots.modes=light` renders only light, for a quick look. */
internal fun shotModes(): List<Boolean> = when (System.getProperty("wmkb.docShots.modes").orEmpty()) {
    "light" -> listOf(false)
    "dark" -> listOf(true)
    else -> listOf(false, true)
}

/**
 * The app holds INTERNET on a phone; Robolectric grants nothing unasked, and
 * the network log refuses every request from an app without it.
 */
internal fun grantInternet(app: Application) {
    shadowOf(app).grantPermissions(android.Manifest.permission.INTERNET)
    com.wasimaster.wmkeyboard.core.netlog.InternetPermission.attach(app)
}

/** Puts back everything a fake changed that outlives one shot in this process. */
internal fun resetFakes() {
    FakeApi.stopAll()
    ServiceEndpoints.overridesEverywhere = false
    ServiceEndpoints.update(emptyMap(), emptyMap())
    com.wasimaster.wmkeyboard.core.otp.NotificationOtpBus.clear()
    FakeContacts.names.clear()
    FakeCalendar.events.clear()
    com.wasimaster.wmkeyboard.app.lock.AppLockSession.clear()
    com.wasimaster.wmkeyboard.app.lock.AppLockSession.clearConfig()
    runCatching {
        val manager = com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
        (manager::class.java.getDeclaredField("activeJob").apply { isAccessible = true }.get(manager) as? kotlinx.coroutines.Job)?.cancel()
    }
}

// ---------------------------------------------------------------- the web

private const val SEARCH_JSON = """{"results":[
  {"title":"Coroutines basics | Kotlin Documentation","url":"https://kotlinlang.org/docs/coroutines-basics.html","content":"A coroutine is an instance of a suspendable computation. It is conceptually similar to a thread."},
  {"title":"Kotlin coroutines on Android","url":"https://developer.android.com/kotlin/coroutines","content":"Coroutines simplify asynchronous code on Android: network calls, database work and more."},
  {"title":"kotlinx.coroutines on GitHub","url":"https://github.com/Kotlin/kotlinx.coroutines","content":"Library support for Kotlin coroutines, with multiplatform support."},
  {"title":"Structured concurrency explained","url":"https://example.org/structured-concurrency","content":"Why every coroutine belongs to a scope, and what that buys you when things fail."}
]}"""

private fun imageSearchJson(base: String) = buildString {
    append("""{"results":[""")
    append(
        (1..9).joinToString(",") { i ->
            """{"title":"Mountain lake $i","url":"https://example.org/photo/$i","img_src":"$base/img/full$i.png","thumbnail_src":"$base/img/thumb$i.png","img_format":"png"}"""
        },
    )
    append("]}")
}

private fun klipyJson(base: String, what: String) = buildString {
    append("""{"result":true,"data":{"data":[""")
    append(
        (1..12).joinToString(",") { i ->
            val h = listOf(160, 220, 180, 260)[i % 4]
            """{"id":$i,"title":"$what $i","file":{"sm":{"gif":{"url":"$base/img/k$what$i.gif","width":200,"height":$h}},"hd":{"gif":{"url":"$base/img/k${what}hd$i.gif","width":400,"height":${h * 2}}}}}"""
        },
    )
    append("""],"current_page":1,"has_next":false}}""")
}

private fun giphyJson(base: String) = buildString {
    append("""{"data":[""")
    append(
        (1..6).joinToString(",") { i ->
            """{"id":"g$i","title":"giphy $i","images":{"fixed_width_small":{"url":"$base/img/g$i.gif","width":"100","height":"${60 + i * 10}"},"original":{"url":"$base/img/go$i.gif","width":"400","height":"${240 + i * 40}"}}}"""
        },
    )
    append("]}")
}

private fun unsplashJson(base: String, wrapped: Boolean): String {
    val names = listOf("Lina Park", "Tomás Reyes", "Aiko Sato", "Nadia Karim", "Ben Ode", "Maya Lind")
    val items = (1..12).joinToString(",") { i ->
        """{"id":"u$i","width":4000,"height":${if (i % 3 == 0) 2600 else 6000},"color":"#4c8df6","alt_description":"mountain lake $i",
          "urls":{"raw":"$base/img/uraw$i.png","small":"$base/img/usmall$i.png"},
          "links":{"html":"https://unsplash.com/photos/u$i","download_location":"$base/photos/u$i/download"},
          "user":{"name":"${names[i % names.size]}","links":{"html":"https://unsplash.com/@u$i"}}}"""
    }
    return if (wrapped) """{"total":12,"total_pages":1,"results":[$items]}""" else "[$items]"
}

private fun pexelsJson(base: String): String {
    val items = (1..8).joinToString(",") { i ->
        """{"id":$i,"width":4000,"height":6000,"url":"https://www.pexels.com/photo/$i/","photographer":"Pexels $i",
          "src":{"original":"$base/img/porig$i.png","medium":"$base/img/pmed$i.png"}}"""
    }
    return """{"page":1,"per_page":8,"total_results":8,"photos":[$items]}"""
}

private fun braveWeb() = """{"web":{"results":[""" +
    Regex("""\{"title":"([^"]+)","url":"([^"]+)","content":"([^"]+)"\}""").findAll(SEARCH_JSON).joinToString(",") {
        """{"title":"${it.groupValues[1]}","url":"${it.groupValues[2]}","description":"${it.groupValues[3]}"}"""
    } + "]}}"

private fun braveImages(base: String) = """{"results":[""" + (1..9).joinToString(",") { i ->
    """{"title":"Mountain lake $i","url":"https://example.org/photo/$i","properties":{"url":"$base/img/full$i.png"},"thumbnail":{"src":"$base/img/thumb$i.png"}}"""
} + "]}"

private const val WEATHER_JSON = """{
  "latitude": 23.75, "longitude": 90.375,
  "current": {"temperature_2m": 31.4, "relative_humidity_2m": 78, "apparent_temperature": 38.2, "is_day": 1,
    "weather_code": 2, "wind_speed_10m": 11.5, "wind_direction_10m": 135, "surface_pressure": 1004.2,
    "cloud_cover": 45, "precipitation": 0.0},
  "daily": {"temperature_2m_max": [33.1], "temperature_2m_min": [27.0], "uv_index_max": [8.5],
    "precipitation_probability_max": [35], "sunrise": ["2026-07-19T05:16"], "sunset": ["2026-07-19T18:49"]}
}"""

private const val DICTIONARY_JSON = """[{
  "word": "serendipity", "phonetic": "/ˌsɛɹ.ənˈdɪp.ɪ.ti/",
  "phonetics": [{"text": "/ˌsɛɹ.ənˈdɪp.ɪ.ti/", "audio": "https://api.dictionaryapi.dev/media/pronunciations/en/serendipity-us.mp3"}],
  "meanings": [
    {"partOfSpeech": "noun", "definitions": [
      {"definition": "An unsought, unintended, and/or unexpected, but fortunate, discovery or learning experience that happens by accident.", "example": "Finding that café was pure serendipity.", "synonyms": [], "antonyms": []},
      {"definition": "The faculty of making such fortunate discoveries.", "synonyms": [], "antonyms": []}
    ], "synonyms": ["chance", "fluke", "luck", "fortune"], "antonyms": ["misfortune"]}
  ]
}]"""

private const val WIKI_SEARCH = """{"query":{"search":[
  {"title":"Kotlin (programming language)","snippet":"<span>Kotlin</span> is a cross-platform, statically typed, general-purpose programming language"},
  {"title":"Kotlin Island","snippet":"<span>Kotlin</span> is a Russian island, near the head of the Gulf of Finland"},
  {"title":"Kotlin-class destroyer","snippet":"The <span>Kotlin</span> class were a group of destroyers built for the Soviet Navy"}
]}}"""

private const val WIKI_SUMMARY = """{"title":"Kotlin (programming language)","description":"General-purpose programming language",
  "extract":"Kotlin is a cross-platform, statically typed, general-purpose high-level programming language with type inference. Kotlin is designed to interoperate fully with Java, and the JVM version of Kotlin's standard library depends on the Java Class Library, but type inference allows its syntax to be more concise. Kotlin mainly targets the JVM, but also compiles to JavaScript or native code via LLVM.",
  "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Kotlin_(programming_language)"}}}"""

private const val WIKI_LINKS = """{"query":{"pages":{"1":{"links":[{"title":"Android (operating system)"},{"title":"Java virtual machine"},{"title":"JetBrains"},{"title":"Type inference"}]}}}}"""

private const val WIKI_FULL = """{"query":{"pages":{"1":{"extract":"Kotlin is a cross-platform, statically typed, general-purpose high-level programming language with type inference."}}}}"""

/** An OpenAI-shaped answer, streamed when the request asks for a stream. */
private fun aiRoute(answer: String, chunkDelayMs: Long = 0) = FakeApi.Route("/chat/completions", type = "text/event-stream", chunkDelayMs = chunkDelayMs) { req ->
    if (req.body.contains("\"stream\":true") || req.body.contains("\"stream\": true")) {
        answer.split(Regex("(?<= )")).map { piece ->
            val escaped = piece.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            "data: {\"choices\":[{\"delta\":{\"content\":\"$escaped\"}}]}\n\n".toByteArray()
        } + "data: [DONE]\n\n".toByteArray()
    } else {
        val escaped = answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        listOf("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"$escaped\"}}]}".toByteArray())
    }
}

/**
 * One local server standing in for every web service the tools call, and
 * every tool pointed at it. Their paths do not overlap, so one server serves
 * them all.
 */
internal suspend fun Seed.fakeWeb(ai: FakeApi.Route? = null): FakeApi {
    lateinit var api: FakeApi
    val routes = mutableListOf(
        FakeApi.Route("/search") { req ->
            listOf((if (req.query.contains("categories=images")) imageSearchJson(api.base) else SEARCH_JSON).toByteArray())
        },
        FakeApi.Route("/api/v1/") { req ->
            listOf(klipyJson(api.base, if (req.path.contains("/stickers/")) "sticker" else "gif").toByteArray())
        },
        FakeApi.Route("/v1/gifs/") { listOf(giphyJson(api.base).toByteArray()) },
        FakeApi.Route("/v1/stickers/") { listOf(giphyJson(api.base).toByteArray()) },
        FakeApi.json("/v1/forecast", WEATHER_JSON),
        FakeApi.Route("/res/v1/web/search") { listOf(braveWeb().toByteArray()) },
        FakeApi.Route("/res/v1/images/search") { listOf(braveImages(api.base).toByteArray()) },
        FakeApi.json("/api/v2/entries/en/", DICTIONARY_JSON),
        FakeApi.Route("/w/api.php") { req ->
            listOf(
                when {
                    req.query.contains("list=search") -> WIKI_SEARCH
                    req.query.contains("prop=links") -> WIKI_LINKS
                    else -> WIKI_FULL
                }.toByteArray(),
            )
        },
        FakeApi.json("/api/rest_v1/page/summary", WIKI_SUMMARY),
        FakeApi.json("/v6/latest/USD", """{"result":"success","base_code":"USD","rates":{"USD":1,"BDT":121.85,"EUR":0.92,"GBP":0.79,"INR":83.4,"JPY":149.2}}"""),
        FakeApi.json("/v1/latest", """{"base":"USD","rates":{"BDT":121.85,"EUR":0.92,"GBP":0.79,"INR":83.4,"JPY":149.2}}"""),
        FakeApi.json("/v2/exchange-rates", """{"data":{"currency":"USD","rates":{"BTC":"0.0000159","ETH":"0.00031"}}}"""),
        FakeApi.json("/v1/currencies/usd.min.json", """{"usd":{"btc":0.0000159,"eth":0.00031,"bdt":121.85}}"""),
        FakeApi.json("/api/v3/simple/price", """{"bitcoin":{"usd":62900},"ethereum":{"usd":3220}}"""),
        FakeApi.Route("/search/photos") { listOf(unsplashJson(api.base, wrapped = true).toByteArray()) },
        FakeApi.Route("/photos") { listOf(unsplashJson(api.base, wrapped = false).toByteArray()) },
        FakeApi.Route("/topics/") { listOf(unsplashJson(api.base, wrapped = false).toByteArray()) },
        FakeApi.Route("/v1/search") { listOf(pexelsJson(api.base).toByteArray()) },
        FakeApi.Route("/v1/curated") { listOf(pexelsJson(api.base).toByteArray()) },
        FakeApi.image("/img/"),
    )
    ai?.let { routes += it }
    api = FakeApi(routes)
    ServiceEndpoints.overridesEverywhere = true
    val bases = ServiceEndpoint.entries.associate { it.id to api.base }
    // Stored too: every settings read republishes the stored overrides, and
    // an unstored one would be wiped by the next.
    for (endpoint in ServiceEndpoint.entries) repo.setServiceEndpoint(endpoint, api.base)
    ServiceEndpoints.update(bases, emptyMap())
    return api
}

private suspend fun Seed.fakeAi(answer: String, chunkDelayMs: Long = 0) {
    val api = fakeWeb(aiRoute(answer, chunkDelayMs))
    repo.setAiProvider(AiProvider.OPENAI_COMPATIBLE)
    repo.setAiCompatibleUrl(api.base)
    repo.setAiCompatibleModel("gpt-4o-mini")
}

/** A few finished chats in the AI chat store, newest last. */
private fun Seed.aiChats(vararg chats: Pair<String, String>) {
    // The controller keeps the store it opened for the whole process.
    val controller = com.wasimaster.wmkeyboard.ime.aichat.AiChatController
    controller::class.java.getDeclaredField("storeInstance").apply { isAccessible = true }.set(controller, null)
    val file = File(context.filesDir, com.wasimaster.wmkeyboard.core.aichat.AiChatStore.FILE_PATH)
    file.delete()
    file.parentFile?.mkdirs()
    val store = com.wasimaster.wmkeyboard.core.aichat.AiChatStore(file)
    val start = System.currentTimeMillis() - chats.size * 3_600_000L
    chats.forEachIndexed { i, (question, answer) ->
        val at = start + i * 3_600_000L
        val chat = store.newConversation(at)
        store.appendMessage(chat.id, com.wasimaster.wmkeyboard.core.aichat.AiChatMessage(role = "USER", content = question, timestamp = at))
        store.appendMessage(
            chat.id,
            com.wasimaster.wmkeyboard.core.aichat.AiChatMessage(
                role = "ASSISTANT", content = answer, timestamp = at + 4_000,
                provider = "OPENAI_COMPATIBLE", model = "gpt-4o-mini",
            ),
        )
    }
    store.save()
}

// ---------------------------------------------------------------- stores

private fun Seed.snippets(vararg items: Pair<String, String>) {
    val file = File(context.filesDir, "snippets/snippets.json")
    file.delete()
    file.parentFile?.mkdirs()
    val store = com.wasimaster.wmkeyboard.core.snippets.SnippetStore(file)
    items.forEach { (label, text) -> store.add(label, text) }
    store.save()
}

private fun Seed.fileClips() {
    val file = File(context.filesDir, "clipboard/history.json")
    file.delete()
    file.parentFile?.mkdirs()
    val store = com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore(file)
    val now = System.currentTimeMillis()
    store.add("Meeting moved to 3pm, same room", now = now - 60_000)
    store.addUri("content://com.example.files/document/42", "Q3 report.pdf", "application/pdf", isDirectory = false, size = 2_400_000, now = now - 40_000)
    store.addUri("content://com.example.gallery/video/7", "Lake at dusk.mp4", "video/mp4", isDirectory = false, size = 18_200_000, durationMs = 63_000, now = now - 20_000)
    store.addUri("content://com.example.files/tree/Design%20assets", "Design assets", "vnd.android.document/directory", isDirectory = true, now = now)
    store.save()
}

private fun Seed.vocabulary() {
    val abhor = com.wasimaster.wmkeyboard.core.vocab.VocabWord(
        word = "abhor",
        pos = listOf("verb"),
        ipa = mapOf("us" to "/əbˈhɔɹ/", "uk" to "/əbˈhɔː/"),
        respelling = "ab-HOR",
        senses = listOf(
            com.wasimaster.wmkeyboard.core.vocab.VocabSense(
                pos = "verb",
                definition = "To regard with horror or loathing; to hate deeply.",
                example = "She abhors cruelty in any form.",
                synonyms = listOf("detest", "loathe"),
            ),
        ),
        synonyms = listOf("hate", "detest", "loathe", "despise"),
        antonyms = listOf("love", "admire"),
        family = com.wasimaster.wmkeyboard.core.vocab.VocabFamily(derived = listOf("abhorrence", "abhorrent"), related = listOf("horror")),
        etymology = "From Latin abhorrēre, \"to shrink back from\".",
        origin = listOf(
            com.wasimaster.wmkeyboard.core.vocab.VocabOrigin("Middle English", "abhorren"),
            com.wasimaster.wmkeyboard.core.vocab.VocabOrigin("Latin", "abhorreō"),
        ),
    )
    val pack = com.wasimaster.wmkeyboard.core.vocab.VocabPack(
        meta = com.wasimaster.wmkeyboard.core.vocab.VocabPackMeta(id = "ws1", name = "Word Smart 1", langId = "en"),
        words = listOf(abhor),
    )
    val file = com.wasimaster.wmkeyboard.core.vocab.VocabPacks.packFile(context.filesDir, "en", "ws1")
    file.parentFile?.mkdirs()
    file.writeText(com.wasimaster.wmkeyboard.core.vocab.VocabPackFile.encode(pack, appVersion = 1, appVersionName = "docs"))
}

/** A downloaded CJK conversion pack, as small as the shot needs. */
private fun Seed.cjkPack(id: String, vararg rows: String) {
    val pack = requireNotNull(com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog.byId(id)) { "no CJK pack $id" }
    val file = com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore.packFile(context.filesDir, pack)
    file.parentFile?.mkdirs()
    file.writeText(rows.joinToString("\n", postfix = "\n"))
}

/** A downloaded dictionary for [langId]. */
private fun Seed.dictionary(langId: String, vararg words: Pair<String, Int>) {
    val trie = com.wasimaster.wmkeyboard.core.prediction.PackedTrie.of(words.toList())
    val file = com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore.downloadedFile(context.filesDir, langId)
    file.parentFile?.mkdirs()
    file.outputStream().use { com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec.write(trie, it) }
}

// ---------------------------------------------------------------- the device

/** A contacts provider holding [FakeContacts.names]. */
class FakeContacts : ContentProvider() {
    companion object {
        val names = mutableListOf<String>()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor =
        MatrixCursor(projection ?: arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)).apply {
            for (name in names) addRow(Array<Any?>(columnCount) { name })
        }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

private fun Seed.contacts(vararg names: String) {
    grant(android.Manifest.permission.READ_CONTACTS)
    FakeContacts.names.clear()
    FakeContacts.names += names
    Robolectric.buildContentProvider(FakeContacts::class.java).create(ContactsContract.AUTHORITY)
}

/** A calendar provider answering Instances queries with [FakeCalendar.events]. */
class FakeCalendar : ContentProvider() {
    data class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?, val color: Int)

    companion object {
        val events = mutableListOf<Event>()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val cursor = MatrixCursor(columns)
        for (e in events.sortedBy { it.begin }) {
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        CalendarContract.Instances.EVENT_ID, "_id" -> e.id
                        CalendarContract.Instances.TITLE -> e.title
                        CalendarContract.Instances.BEGIN, CalendarContract.Instances.DTSTART -> e.begin
                        CalendarContract.Instances.END, CalendarContract.Instances.DTEND -> e.end
                        CalendarContract.Instances.ALL_DAY -> if (e.allDay) 1 else 0
                        CalendarContract.Instances.EVENT_LOCATION -> e.location
                        CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.CALENDAR_COLOR -> e.color
                        else -> null
                    }
                }.toTypedArray<Any?>(),
            )
        }
        return cursor
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

private fun Seed.calendarToday() {
    grant(android.Manifest.permission.READ_CALENDAR)
    val day = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val utcMidnight = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L
    val hour = 3_600_000L
    FakeCalendar.events.clear()
    FakeCalendar.events += listOf(
        FakeCalendar.Event(1, "Ayesha's birthday", utcMidnight, utcMidnight + 86_400_000L, true, null, 0xFFE67C73.toInt()),
        FakeCalendar.Event(2, "Standup", day + 9 * hour + hour / 2, day + 9 * hour + 3 * hour / 4, false, "Meet", 0xFF4285F4.toInt()),
        FakeCalendar.Event(3, "Lunch with Rafi", day + 13 * hour, day + 14 * hour, false, "Café Mango, Gulshan", 0xFF33B679.toInt()),
        FakeCalendar.Event(4, "Dentist", day + 17 * hour + hour / 2, day + 18 * hour + hour / 2, false, "Green Road Dental", 0xFFF6BF26.toInt()),
    )
    Robolectric.buildContentProvider(FakeCalendar::class.java).create(CalendarContract.AUTHORITY)
}

private fun Seed.sensor(type: Int) {
    val manager = context.getSystemService(android.hardware.SensorManager::class.java)
    if (manager.getDefaultSensor(type) == null) {
        shadowOf(manager).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(type))
    }
}

private fun Seed.flashCamera() {
    val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    val characteristics = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
    shadowOf(characteristics).set(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
    shadowOf(characteristics).set(android.hardware.camera2.CameraCharacteristics.LENS_FACING, android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
    shadowOf(manager).addCamera("0", characteristics)
}

/** Apps on the launcher, with a plain coloured icon each. */
private suspend fun Seed.launcherApps(vararg apps: Pair<String, String>, pinned: List<String> = emptyList()) {
    val pm = shadowOf(context.packageManager)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    apps.forEachIndexed { i, (label, pkg) ->
        val app = android.content.pm.ApplicationInfo().apply {
            packageName = pkg; name = label; nonLocalizedLabel = label
        }
        val activity = android.content.pm.ActivityInfo().apply {
            packageName = pkg; name = "$pkg.Main"; applicationInfo = app; nonLocalizedLabel = label; exported = true
        }
        val info = android.content.pm.ResolveInfo().apply { activityInfo = activity; nonLocalizedLabel = label }
        pm.addResolveInfoForIntent(launcher, info)
        pm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = app; activities = arrayOf(activity) })
        val colors = intArrayOf(0xFF4C8DF6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFF84CC16.toInt())
        val icon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = colors[i % colors.size]; intrinsicWidth = 144; intrinsicHeight = 144
        }
        pm.setApplicationIcon(pkg, icon)
        runCatching { pm.addActivityIcon(android.content.ComponentName(pkg, "$pkg.Main"), icon) }
    }
    for (pkg in pinned) repo.toggleLauncherPin(pkg)
}

private fun Seed.whatsapp() {
    val pm = shadowOf(context.packageManager)
    val component = android.content.ComponentName("com.whatsapp", "com.whatsapp.Main")
    pm.installPackage(android.content.pm.PackageInfo().apply {
        packageName = "com.whatsapp"
        applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.whatsapp"; nonLocalizedLabel = "WhatsApp" }
    })
    pm.addActivityIfNotPresent(component)
    pm.addIntentFilterForActivity(
        component,
        android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
    )
}

/** A session playing in another app, the way the media panel finds one. */
private fun KbSteps.mediaSession() {
    val listener = android.content.ComponentName(service, com.wasimaster.wmkeyboard.core.media.MediaNotificationListener::class.java)
    android.provider.Settings.Secure.putString(service.contentResolver, "enabled_notification_listeners", listener.flattenToString())
    val art = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).apply {
        val canvas = android.graphics.Canvas(this)
        canvas.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, 0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), android.graphics.Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(150f, 170f, 70f, android.graphics.Paint().apply { color = 0x66FFFFFF })
    }
    val session = android.media.session.MediaSession(service, "docs")
    session.setMetadata(
        android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Sunset Drive")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "The Aurora Keys")
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Nightfall")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 214_000L)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build(),
    )
    session.setPlaybackState(
        android.media.session.PlaybackState.Builder()
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 63_000L, 1f)
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or android.media.session.PlaybackState.ACTION_SEEK_TO,
            )
            .build(),
    )
    session.isActive = true
    val controller = session.controller
    // Robolectric's controller does not hear its session; tell it directly.
    shadowOf(controller).setMetadata(session.controller.metadata ?: android.media.MediaMetadata.Builder()
        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Sunset Drive")
        .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "The Aurora Keys")
        .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Nightfall")
        .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 214_000L)
        .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        .build())
    shadowOf(controller).setPlaybackState(
        android.media.session.PlaybackState.Builder()
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 63_000L, 1f)
            .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or android.media.session.PlaybackState.ACTION_SEEK_TO)
            .build(),
    )
    for (context in listOf(service, service.applicationContext)) {
        shadowOf(context.getSystemService(android.media.session.MediaSessionManager::class.java)).addController(controller)
    }
}

private fun KbSteps.ctrlTwice() {
    hardwareKey(KeyEvent.KEYCODE_CTRL_LEFT)
    hardwareKey(KeyEvent.KEYCODE_CTRL_LEFT)
}

private fun KbSteps.hardwareType(text: String) {
    for (c in text) {
        val code = when (c) {
            ' ' -> KeyEvent.KEYCODE_SPACE
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            else -> error("no key for $c")
        }
        hardwareKey(code)
    }
    settle()
}

// ---------------------------------------------------------------- the shots

internal val MORE_KB_SHOTS: List<KbShot> = listOf(
    // ------------------------------------------------------------ stores
    KbShot("tools/snippets-panel", hint = "Message", seed = {
        snippets(
            "Thanks" to "Thanks so much, I really appreciate it!",
            "Out of office" to "I'm out of the office until Monday and will reply when I'm back. Thanks for your patience!",
            "Address" to "House 12, Road 5, Dhanmondi, Dhaka 1205",
            "Sign-off" to "Best,\nWasi",
            "Sent on" to "Sent on {date} at {time}",
            "Zoom link" to "https://zoom.us/j/5550123456",
        )
    }, steps = { panel(PanelMode.SNIPPETS) }),
    KbShot("tools/clipboard-rich-media", seed = { fileClips() }, steps = { panel(PanelMode.CLIPBOARD) }),
    KbShot("tools/clipboard-info-popup", seed = { repo.setClipboardTrackSource(true) }, steps = {
        copy("Pick up milk and eggs on the way home")
        panel(PanelMode.CLIPBOARD)
        hold("Pick up milk and eggs on the way home")
    }),
    KbShot(
        "tools/learn-from-text", kind = "multiline", hint = "Note",
        text = "Ask Zelkodyne about the Glimmerpatch launch before the Voskbridge meeting, and check whether Thranilo signed off the Quorvexta budget.",
        // The dictionary loads after the keyboard shows; until it has, every word reads as new.
        steps = { pause(8000); panel(PanelMode.LEARN_FROM_TEXT); settle() },
    ),
    KbShot("tools/vocabulary-card", hint = "Message", text = "abhor", seed = { vocabulary() }, steps = { panel(PanelMode.VOCABULARY); settle() }),

    // ------------------------------------------------------------ panels with no outside world
    KbShot("tools/trackpad-panel", steps = { panel(PanelMode.TRACKPAD); drag("Drag to move the cursor", 120f, 60f) }),
    KbShot("tools/resize-overlay", steps = { tool(ToolbarTool.RESIZE) }),
    KbShot("tools/typing-test-results", seed = {
        repo.setTypingTestMode(com.wasimaster.wmkeyboard.core.tools.TypingTestMode.WORDS)
        repo.setTypingTestWordCount(10)
    }, steps = {
        panel(PanelMode.TYPING_TEST)
        val words = (service as ShotKeyboard).uiState.value.typingTest.words
        words.forEachIndexed { i, word ->
            // The test times itself on the wall clock; a steady pace reads as one.
            pause(420)
            type(if (i == words.lastIndex) word else "$word ")
        }
        settle()
    }),
    KbShot("tools/flashlight-toggled-on", seed = { flashCamera() }, steps = { panel(PanelMode.TOOLBOX); tap("Flashlight") }),
    KbShot("typing/power-saving-toolbar-toggle", seed = {
        repo.setToolbarTools(toolKeysOf(ToolbarTool.POWER_SAVING, ToolbarTool.CLIPBOARD, ToolbarTool.EMOJI, ToolbarTool.VOICE))
    }, steps = { tool(ToolbarTool.POWER_SAVING) }),

    // ------------------------------------------------------------ layouts
    KbShot("languages/ambiguous-t9-field", seed = { layout("builtin_t9") }),
    KbShot("languages/notation-morse-strip", seed = { layout("asset_morse") }, steps = {
        key(Key(label = "·", action = KeyAction.MorseDot))
        key(Key(label = "·", action = KeyAction.MorseDot))
    }),
    KbShot("languages/cjk-pinyin-candidate-strip", seed = {
        layout("asset_zh_pinyin")
        cjkPack("pinyin", "ni\t你\t900", "ni\t呢\t700", "ni\t泥\t300", "hao\t好\t900", "hao\t号\t600", "nihao\t你好\t800", "nihao\t您好\t400")
    }, steps = { type("nihao") }),
    KbShot("languages/cjk-cangjie-keys", seed = {
        layout("asset_zh_cangjie")
        cjkPack("cangjie", "a\t日\t900", "ab\t明\t800", "ab\t昍\t100", "abu\t暭\t50")
    }, steps = { type("ab") }),
    KbShot("languages/cjk-candidate-grid-expanded", seed = {
        layout("asset_zh_pinyin")
        cjkPack(
            "pinyin",
            *listOf("你", "呢", "泥", "尼", "妮", "逆", "拟", "腻", "倪", "匿", "溺", "霓", "昵", "睨", "伲", "坭", "铌", "旎", "怩", "鲵")
                .mapIndexed { i, c -> "ni\t$c\t${1000 - i * 40}" }.toTypedArray(),
        )
    }, steps = { type("ni"); tap("More candidates") }),

    // ------------------------------------------------------------ the strip
    KbShot("smart/contact-name-suggestion", seed = { repo.setContactSuggestions(true); contacts("Wasi Mollik", "Wasif Rahman", "Warda Chowdhury") }, steps = { type("Wasi") }),
    KbShot("smart/language-detection-switch", seed = {
        repo.setSecondaryLanguages(mapOf("en" to listOf("bn_rom")))
        dictionary("bn_rom", "ami" to 250, "tomake" to 220, "bhalobashi" to 200, "kemon" to 240, "acho" to 230, "tumi" to 245, "valo" to 235)
    }, steps = { type("how are you tumi kemon ac") }),
    KbShot("typing/octopus-keys", seed = { repo.setOctopusEnabled(true) }, steps = { type("th") }),

    // ------------------------------------------------------------ selection
    KbShot("smart/selection-actions-phone", text = "Call me on +880 1712-345678 after six", seed = {
        repo.setSelectionMacrosEnabled(true); whatsapp()
    }, steps = { select("+880 1712-345678") }),
    KbShot("smart/selection-actions-case", text = "the quick brown fox", seed = { repo.setSelectionMacrosEnabled(true) }, steps = {
        select("the quick brown fox"); tap("Format")
    }),
    KbShot(
        "smart/selection-actions-replace", kind = "multiline",
        text = "The cat sat on the mat. The cat ran after the other cat.",
        seed = { repo.setSelectionMacrosEnabled(true) },
        steps = { panel(PanelMode.FIND_REPLACE); type("cat"); tap("Replace with"); type("dog") },
    ),

    // ------------------------------------------------------------ gestures
    KbShot("typing/spacebar-cursor-swipe", kind = "multiline", text = "See you at the station at six", steps = { drag("Space", -140f, holdMillis = 700) }),
    KbShot("typing/backspace-word-swipe", kind = "multiline", text = "See you at the station at six tomorrow", steps = { drag("Delete", -260f) }),
    KbShot("typing/volume-cursor-control", kind = "multiline", text = "Meet me at the usual place around six tonight.", seed = {
        repo.setVolumeCursor(true); repo.setVolumeCursorMediaAware(false)
    }, steps = { repeat(8) { hardwareKey(KeyEvent.KEYCODE_VOLUME_DOWN) }; settle() }),

    // ------------------------------------------------------------ a physical keyboard
    KbShot("typing/hardware-typing-live", hardwareKeyboard = true, steps = { hardwareType("see you tomorow") }),
    KbShot("typing/hardware-tool-picker", hardwareKeyboard = true, steps = { ctrlTwice(); settle() }),
    KbShot("typing/hardware-focus-ring", hardwareKeyboard = true, seed = {
        snippets("Thanks" to "Thanks so much!", "Address" to "House 12, Road 5, Dhanmondi", "Sign-off" to "Best,\nWasi", "Zoom link" to "https://zoom.us/j/5550123456")
    }, steps = {
        ctrlTwice(); hardwareKey(KeyEvent.KEYCODE_S); settle()
        hardwareKey(KeyEvent.KEYCODE_DPAD_DOWN); hardwareKey(KeyEvent.KEYCODE_DPAD_RIGHT); settle()
    }),
    KbShot("reference/shortcuts-cheat-sheet", hardwareKeyboard = true, steps = {
        ctrlTwice(); hardwareKey(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON); settle()
    }),

    // ------------------------------------------------------------ sensors, calendar, apps, media
    KbShot("tools/compass-panel-qibla", mode = "blank", seed = {
        sensor(Sensor.TYPE_ROTATION_VECTOR)
        repo.setWeatherLocation(23.8103f, 90.4125f, "Dhaka")
        repo.setCompassShowQibla(true)
    }, steps = {
        panel(PanelMode.COMPASS)
        sensorEvent(Sensor.TYPE_ROTATION_VECTOR, 0f, 0f, 0.42f, 0.907f)
        settle()
    }),
    KbShot("tools/level-panel", mode = "blank", seed = { sensor(Sensor.TYPE_GRAVITY); sensor(Sensor.TYPE_ACCELEROMETER) }, steps = {
        panel(PanelMode.LEVEL)
        sensorEvent(Sensor.TYPE_GRAVITY, 0.9f, 0.5f, 9.74f)
        sensorEvent(Sensor.TYPE_ACCELEROMETER, 0.9f, 0.5f, 9.74f)
        settle()
    }),
    KbShot("tools/calendar-day-events", seed = { calendarToday() }, steps = { panel(PanelMode.CALENDAR); settle() }),
    KbShot("tools/app-launcher-grid", seed = {
        launcherApps(
            "Messages" to "com.example.messages", "Camera" to "com.example.camera", "Maps" to "com.example.maps",
            "Notes" to "com.example.notes", "Music" to "com.example.music", "Photos" to "com.example.photos",
            "Calendar" to "com.example.calendar", "Clock" to "com.example.clock", "Files" to "com.example.files",
            "Weather" to "com.example.weather", "Wallet" to "com.example.wallet", "Podcasts" to "com.example.podcasts",
            pinned = listOf("com.example.messages", "com.example.camera", "com.example.maps"),
        )
    }, steps = { panel(PanelMode.APP_LAUNCHER); settle() }),
    KbShot("tools/media-now-playing", mode = "blank", steps = { mediaSession(); panel(PanelMode.MEDIA_CONTROL); settle() }),
    KbShot("tools/voice-panel-listening", seed = { grant(android.Manifest.permission.RECORD_AUDIO) }, steps = {
        // The panel starts listening as it opens.
        panel(PanelMode.VOICE)
        speech { triggerOnReadyForSpeech(android.os.Bundle()); triggerOnRmsChanged(8f) }
        speech {
            triggerOnPartialResults(android.os.Bundle().apply {
                putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("see you at the station"))
            })
        }
    }),

    // ------------------------------------------------------------ the web, faked
    KbShot("tools/web-search-results", seed = { repo.setSearxUrl(fakeWeb().base) }, steps = {
        panel(PanelMode.WEB_SEARCH); type("kotlin coroutines"); key(Key(label = "", action = KeyAction.Enter)); pause(1500); settle()
    }),
    KbShot("tools/image-search-grid", seed = { repo.setSearxUrl(fakeWeb().base) }, steps = {
        panel(PanelMode.IMAGE_SEARCH); type("mountain lake"); key(Key(label = "", action = KeyAction.Enter)); pause(2500); settle()
    }),
    KbShot("tools/wikipedia-article", seed = { fakeWeb() }, steps = {
        panel(PanelMode.WIKIPEDIA); type("kotlin"); key(Key(label = "", action = KeyAction.Enter)); pause(1500); settle()
        tap("Kotlin (programming language)"); pause(1000); settle()
    }),
    KbShot("tools/dictionary-entry", text = "serendipity", seed = { fakeWeb() }, steps = { panel(PanelMode.DICTIONARY); pause(1500); settle() }),
    KbShot("tools/weather-panel-ready", mode = "blank", seed = {
        fakeWeb(); repo.setWeatherLocation(23.8103f, 90.4125f, "Dhaka")
    }, steps = { panel(PanelMode.WEATHER); pause(1500); settle() }),
    KbShot("tools/currency-converter-panel", seed = { fakeWeb() }, steps = { panel(PanelMode.CURRENCY); pause(1500); settle(); key(Key(label = "", action = KeyAction.Delete)); type("150"); settle() }),
    KbShot("smart/currency-chip", seed = { fakeWeb(); repo.setSmartCurrency(true) }, steps = { type("150 usd"); pause(1500); settle() }),
    KbShot("emoji/gif-panel-trending", mode = "chat", seed = { fakeWeb(); repo.setKlipyApiKey("docs"); repo.setGiphyApiKey("docs") }, steps = {
        panel(PanelMode.GIF); pause(2000); settle()
    }),
    KbShot("emoji/gif-long-press", mode = "chat", seed = { fakeWeb(); repo.setKlipyApiKey("docs"); repo.setGiphyApiKey("docs") }, steps = {
        panel(PanelMode.GIF); pause(2000); settle(); holdDescription("gif 2")
    }),
    KbShot("emoji/gif-no-rich-media", seed = { fakeWeb(); repo.setKlipyApiKey("docs"); repo.setGiphyApiKey("docs") }, steps = {
        panel(PanelMode.GIF); pause(2000); settle()
    }),
    KbShot("emoji/sticker-long-press-save", mode = "chat", seed = { fakeWeb(); repo.setKlipyApiKey("docs"); repo.setGiphyApiKey("docs") }, steps = {
        panel(PanelMode.STICKER); pause(2000); settle(); holdDescription("sticker 2")
    }),
    KbShot("emoji/sticker-panel-unsupported-field", seed = { fakeWeb(); repo.setKlipyApiKey("docs"); repo.setGiphyApiKey("docs") }, steps = {
        panel(PanelMode.STICKER); pause(2000); settle()
    }),

    // ------------------------------------------------------------ AI, faked
    KbShot("tools/ai-panel-idle", kind = "multiline", text = "Reviewing the Q3 roadmap doc before I send it to the team.", seed = {
        fakeAi("")
    }, steps = { panel(PanelMode.AI) }),
    KbShot("tools/ai-result-ready", kind = "multiline", text = "hey, cant make it tmrw, somethin came up at work. can we do thursday instead?", seed = {
        fakeAi("Hi! I'm sorry, but I can't make it tomorrow — something came up at work. Would **Thursday** work for you instead?")
    }, steps = { panel(PanelMode.AI); tap("Rewrite"); pause(2000); settle() }),
    KbShot("tools/ai-result-changes", kind = "multiline", text = "Their going to the store tomorow, but there not sure when they will be back.", seed = {
        fakeAi("They're going to the store tomorrow, but they're not sure when they will be back.")
    }, steps = { panel(PanelMode.AI); tap("Fix grammar"); pause(2000); settle(); tap("Changes") }),
    KbShot("tools/ai-chat-list", seed = {
        fakeAi("")
        aiChats(
            "What's a polite way to decline a meeting?" to "Try: \"Thanks for the invite! I can't make this one, but please share the notes.\"",
            "Explain recursion like I'm five" to "Recursion is when something is defined using a smaller copy of itself, like Russian dolls.",
            "Plan a 3-day trip to Sylhet" to "Day 1: Jaflong and the Dawki river. Day 2: Ratargul swamp forest. Day 3: tea gardens in Sreemangal.",
        )
    }, steps = {
        panel(PanelMode.AI)
        service.onAiChatAction(AiChatAction.SetMode(true)); settle()
        service.onAiChatAction(AiChatAction.ToggleSessions); settle()
    }),
    KbShot("tools/ai-chat-keyboard", seed = {
        fakeAi("")
        aiChats(
            "Plan a 3-day trip to Sylhet" to "Here's a relaxed plan:\n\n**Day 1** — Jaflong and the Dawki river; boat ride at sunset.\n**Day 2** — Ratargul swamp forest in the morning, Shah Jalal shrine after.\n**Day 3** — Tea gardens and the seven-layer tea in Sreemangal.",
        )
    }, steps = {
        panel(PanelMode.AI)
        service.onAiChatAction(AiChatAction.SetMode(true)); settle()
        service.onAiChatAction(AiChatAction.Open(1L)); settle()
    }),
    KbShot("tools/ai-chat-conversation", seed = {
        fakeAi(
            "Sure! Start with Jaflong on the first day: the river is clearest in the morning, and the boats leave from the main ghat. " +
                "On day two, take an early trip to Ratargul before the heat, then visit the shrine in the afternoon.",
            chunkDelayMs = 250,
        )
        aiChats("Plan a 3-day trip to Sylhet" to "Day 1: Jaflong. Day 2: Ratargul. Day 3: Sreemangal.")
    }, steps = {
        panel(PanelMode.AI)
        service.onAiChatAction(AiChatAction.SetMode(true)); settle()
        service.onAiChatAction(AiChatAction.Open(1L)); settle()
        service.onAiChatAction(AiChatAction.FocusComposer); settle()
        type("Tell me more about day one and two")
        service.onAiChatAction(AiChatAction.Send)
        pause(2200)
    }),
)
