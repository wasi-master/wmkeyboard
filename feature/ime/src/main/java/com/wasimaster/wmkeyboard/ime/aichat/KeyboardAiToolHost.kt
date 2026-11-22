package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wasimaster.wmkeyboard.core.reply.ReplyShots
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaDomains
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaStateFormat
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeRef
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.settings.ScannerSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.tools.OnlineOcr
import com.wasimaster.wmkeyboard.core.tools.AiToolHost
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.homeassistant.SmartHomeHub
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.notes.NoteFilter
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.semantic.Embeddings
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.settings.SemanticSearchSettings
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.tools.AiMeaningHit
import com.wasimaster.wmkeyboard.ime.semantic.MeaningSearch
import com.wasimaster.wmkeyboard.ime.semantic.MeaningLookup
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * What the keyboard module lends the AI's tools (#698), for the keyboard's AI
 * panel and for both chats alike.
 *
 * [hints] are the languages the user types in, the active one first: what the
 * on-device translator falls back on when a few words are not enough to tell
 * the language by. [images] are the chat's pictures in order, by the names
 * [AiChatImages] keeps them under, and [seesImages] says the model is sent
 * them itself. [scanner] carries the scan text tool's settings and
 * [ocrReaders] its online readers, when Online is its engine.
 */
class KeyboardAiToolHost(
    private val context: Context,
    private val hints: List<String> = emptyList(),
    private val kdeConnect: KdeConnectSettings? = null,
    /** The smart home tool's preferences and its listed homes; both empty when the tool is off. */
    private val homePrefs: HomeAssistantSettings? = null,
    private val homes: List<HomeAccount> = emptyList(),
    private val images: List<String> = emptyList(),
    private val seesImages: Boolean = false,
    private val scanner: ScannerSettings? = null,
    private val ocrReaders: List<ResolvedProvider> = emptyList(),
    /** Search by meaning's settings, for search_clipboard, search_notes and search_snippets; null where the surface does not lend it. */
    private val semantic: SemanticSearchSettings? = null,
    /**
     * Names the chat this run answers in, once the user accepted the name
     * (#882), giving back the name it got or null when the chat is gone.
     * Null where there is no chat: the AI panel's actions.
     */
    private val renamer: ((String) -> String?)? = null,
    /**
     * Describes a picture's JPEG with the model that sees, the question
     * beside it; null when no describer is set up. [describerName] is that
     * model's name, which the model in use is told.
     */
    private val describer: ((ByteArray, String) -> String)? = null,
    private val describerName: String = "",
) : AiToolHost {

    override val canRenameChat: Boolean get() = renamer != null

    override fun renameChat(title: String): Result<String> {
        val rename = renamer ?: return super.renameChat(title)
        return rename(title)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("the chat is gone"))
    }

    override val canTranslateOnDevice: Boolean get() = OfflineTranslation.available

    /** ML Kit, then Bergamot, then NLLB, where the phone has them: the first that has the pair answers. */
    override fun translateOnDevice(text: String, target: String, source: String): Result<Translation> {
        val result = runBlocking {
            OfflineTranslation.translateAny(
                context = context.applicationContext,
                text = text,
                target = target,
                sourceOverride = source.takeUnless { it == TranslateClient.AUTO }.orEmpty(),
                hints = hints,
            )
        }
        // The words are the model's to read, so they say what the user would
        // have to do, in plain terms, rather than naming a result type.
        return when (result) {
            is OfflineTranslateResult.Success -> Result.success(Translation(text = result.text, detectedSource = result.source))
            is OfflineTranslateResult.NeedsModels -> failure(
                "the on-device language models for ${
                    result.missing.joinToString(", ") {
                        OfflineTranslation.label(it, TranslateClient::languageName, "the Firefox Translations engine")
                    }
                } are not downloaded",
            )
            is OfflineTranslateResult.Unsupported -> failure("the on-device translator has no model for ${result.language}")
            OfflineTranslateResult.Undetermined -> failure("the on-device translator could not tell the text's language")
            OfflineTranslateResult.ModuleMissing -> failure("the on-device translator is not installed")
            is OfflineTranslateResult.Failed -> Result.failure(result.cause)
        }
    }

    private fun failure(message: String): Result<Translation> = Result.failure(IllegalStateException(message))

    override val canOpenApps: Boolean get() = true

    /**
     * The launcher tool's list of apps, best match first: a label equal to
     * [name], then one that starts with it, then any that contains it.
     */
    override fun openApp(name: String): Result<String> = runCatching {
        val pm = context.packageManager
        val wanted = name.trim()
        val matches = AppCatalog.filterApps(runBlocking { AppCatalog.loadApps(pm) }, wanted)
        val app = matches.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: matches.firstOrNull { it.label.startsWith(wanted, ignoreCase = true) }
            ?: matches.firstOrNull()
            ?: throw NoSuchElementException(wanted)
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: Intent().setComponent(app.component)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        app.label
    }

    override val canSendToPc: Boolean get() = kdeConnect?.enabled == true

    /**
     * Holds the link up the way a share from another app does, sends to the
     * first computer that connects, and lets the link go a little later,
     * once the text is on its way.
     */
    override fun sendToPc(text: String): Result<String> = runCatching {
        val settings = requireNotNull(kdeConnect) { "KDE Connect is off" }
        runBlocking(Dispatchers.Main) {
            KdeConnectHub.attach(context)
            KdeConnectHub.applySettings(settings)
            KdeConnectHub.hold(KdeConnectHub.Reason.SHARE)
            try {
                val device = KdeConnectHub.awaitConnected(CONNECT_TIMEOUT_MS).firstOrNull()
                    ?: error("no paired computer is connected")
                KdeConnectHub.send(context.applicationContext, device.id, emptyList(), text)
                device.name
            } finally {
                Handler(Looper.getMainLooper()).postDelayed(
                    { KdeConnectHub.release(KdeConnectHub.Reason.SHARE) },
                    RELEASE_DELAY_MS,
                )
            }
        }
    }

    override val canControlHome: Boolean get() = homePrefs?.enabled == true && homes.isNotEmpty()

    /** Holds the hub up under the AI's reason, asks the first home with an assistant, and lets go a little later. */
    override fun homeControl(request: String): Result<String> = runCatching {
        val settings = requireNotNull(homePrefs) { "The smart home tool is off" }
        runBlocking {
            holdHome(settings)
            try {
                // Google Home, SmartThings and the rest have no Assist to read
                // a sentence. Say so in words the model can act on, rather
                // than the hub's bare "no_assist".
                check(SmartHomeHub.state.value.homes.any { HomeFeature.ASSIST in it.features }) {
                    "None of the user's homes understands sentences. Call home_assistant_states to find the device's id, " +
                        "then call home_assistant again with entity_id and action."
                }
                val reply = SmartHomeHub.assist(request, hints.firstOrNull()?.substringBefore('-'), null)
                if (reply.isError) error(reply.speech.ifBlank { "Home Assistant could not do that" })
                reply.speech.ifBlank { "Done." }
            } finally {
                releaseHomeLater()
            }
        }
    }

    /**
     * One service call on the home that has [entityId]: the same call a tile
     * makes, so it reaches every kind of home. [entityId] is the home's own id
     * as [homeStates] lists it, or a `home|id` ref; [action] is a service of
     * the entity's own domain, or a `domain.service`.
     */
    override fun homeAction(entityId: String, action: String, data: JsonObject): Result<String> = runCatching {
        val settings = requireNotNull(homePrefs) { "The smart home tool is off" }
        runBlocking {
            holdHome(settings)
            try {
                val states = SmartHomeHub.ensureStates().ifEmpty { SmartHomeHub.entitiesWithCache() }
                val wanted = entityId.trim()
                val ref = wanted.takeIf { it in states }
                    ?: states.keys.firstOrNull { HomeRef.native(it) == wanted }
                    ?: states.keys.firstOrNull { HomeRef.native(it).equals(wanted, ignoreCase = true) }
                    ?: error("No device has the id $wanted. Call home_assistant_states for the ids.")
                val native = HomeRef.native(ref)
                val service = action.trim()
                val asked = if ('.' in service) {
                    HaServiceCall(service.substringBefore('.'), service.substringAfter('.'), native, data)
                } else {
                    HaServiceCall(native.substringBefore('.'), service, native, data)
                }
                val call = fanSpeed(asked, states[ref])
                SmartHomeHub.callService(HomeRef.home(ref), call)
                val hub = SmartHomeHub.state.value
                val name = states[ref]?.let { hub.registryOf(it).nameOf(it) } ?: native
                "Done: ${call.domain}.${call.service} on $name."
            } finally {
                releaseHomeLater()
            }
        }
    }

    /** Every home's states, each line with the home's name first when there are several. */
    override fun homeStates(query: String?): Result<String> = runCatching {
        val settings = requireNotNull(homePrefs) { "The smart home tool is off" }
        runBlocking {
            holdHome(settings)
            try {
                val states = SmartHomeHub.ensureStates()
                val hub = SmartHomeHub.state.value
                val several = hub.homes.size > 1
                val q = query?.trim()?.lowercase().orEmpty()
                val format = HaStateFormat()
                val rows = states.values.asSequence()
                    .filter { HaDomains.isListed(it.domain) && !hub.registryOf(it).isHidden(it.entityId) }
                    .filter { e ->
                        val registry = hub.registryOf(e)
                        if (q.isEmpty()) {
                            e.state !in QUIET_STATES
                        } else {
                            registry.nameOf(e).lowercase().contains(q) || e.entityId.contains(q) ||
                                registry.areaOf(e.entityId)?.name?.lowercase()?.contains(q) == true ||
                                hub.home(e.home)?.name?.lowercase()?.contains(q) == true
                        }
                    }
                    .sortedBy { hub.registryOf(it).nameOf(it) }
                    .take(MAX_STATE_ROWS)
                    .map { e ->
                        val registry = hub.registryOf(e)
                        val home = hub.home(e.home)?.name?.takeIf { several && it.isNotBlank() }
                        val speeds = speedCount(e)?.let { n ->
                            if (e.isOn) ", speed ${speedOf(e, n)} of $n" else ", $n speeds"
                        }.orEmpty()
                        listOfNotNull(home, registry.areaOf(e.entityId)?.name, registry.nameOf(e)).joinToString(" / ") + ": " + format.state(e) + speeds + " (" + e.entityId + ")"
                    }
                    .toList()
                if (rows.isEmpty()) "Nothing matched." else rows.joinToString("\n")
            } finally {
                releaseHomeLater()
            }
        }
    }

    /**
     * A fan's speed the way a model says it. "Speed 1" arrives as
     * `{"speed": 1}`, often with a `set_speed` action, but every home takes a
     * percentage; a fan with a few speeds gets that speed's own percentage,
     * which each backend turns back into the speed.
     */
    private fun fanSpeed(call: HaServiceCall, entity: HaEntity?): HaServiceCall {
        if (call.domain != "fan") return call
        val service = if (call.service in SPEED_SERVICES) "set_percentage" else call.service
        val speed = SPEED_KEYS.firstNotNullOfOrNull { (call.data[it] as? JsonPrimitive)?.doubleOrNull }
            ?: return call.copy(service = service)
        val count = entity?.let(::speedCount)
            ?: error("This fan has no numbered speeds. Set it with a percentage from 0 to 100 instead.")
        require(speed in 0.0..count.toDouble()) { "This fan's speeds run from 1 to $count, and 0 turns it off." }
        val data = buildJsonObject {
            call.data.forEach { (k, v) -> if (k !in SPEED_KEYS) put(k, v) }
            // Rounded down: Home Assistant rounds a percentage up to a speed,
            // so 67 % on a three-speed fan would be its third.
            put("percentage", (speed * PERCENT / count).toInt())
        }
        return call.copy(service = service, data = data)
    }

    /** How many speeds a fan has, from its percentage step; null for one that takes any percentage. */
    private fun speedCount(e: HaEntity): Int? {
        if (e.domain != "fan" || e.percentageStep <= 1.0) return null
        return (PERCENT / e.percentageStep).roundToInt().takeIf { it in 1..MAX_SPEEDS }
    }

    private fun speedOf(e: HaEntity, count: Int): Int =
        ((e.percentage ?: 0) * count / PERCENT).roundToInt().coerceIn(0, count)

    private suspend fun holdHome(settings: HomeAssistantSettings) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        SmartHomeHub.attach(context)
        SmartHomeHub.batch {
            SmartHomeHub.apply(settings, homes)
            SmartHomeHub.hold(SmartHomeHub.Reason.AI)
        }
    }

    private fun releaseHomeLater() {
        Handler(Looper.getMainLooper()).postDelayed({ SmartHomeHub.release(SmartHomeHub.Reason.AI) }, RELEASE_DELAY_MS)
    }

    override val canSearchByMeaning: Boolean
        get() = semantic?.enabled == true && SemanticModels.SUPPORTED && Embeddings.available(context)

    /**
     * The clipboard as its file holds it now, against the index the keyboard's
     * panel keeps. Opened the way the runner opens the notes: read from its
     * file, never written.
     */
    override fun searchClipboard(query: String): Result<List<AiMeaningHit>> = runCatching {
        // No limits: a store opened with its defaults would prune a "never
        // expire" history the first time it listed it.
        val clips = ClipboardStore(
            File(context.filesDir, "clipboard/history.json"),
            expiryMillis = 0L,
            maxItems = 0,
            sensitiveExpiryMillis = 0L,
        ).items()
        lookUp(query, MeaningLookup.Sources(clips = clips))
    }

    /** The notes as their file holds them now, locked ones left out. */
    override fun notesByMeaning(query: String): Result<List<AiMeaningHit>> = runCatching {
        val notes = NoteStore(File(context.filesDir, NoteStore.FILE_PATH)).items(NoteFilter(includeLocked = false))
        lookUp(query, MeaningLookup.Sources(notes = notes))
    }

    /** The snippets as their file holds them now. */
    override fun snippetsByMeaning(query: String): Result<List<AiMeaningHit>> = runCatching {
        val snippets = SnippetStore(File(context.filesDir, SnippetStore.FILE_PATH)).items()
        lookUp(query, MeaningLookup.Sources(snippets = snippets))
    }

    private fun lookUp(query: String, sources: MeaningLookup.Sources): List<AiMeaningHit> {
        check(canSearchByMeaning) { "search by meaning is off" }
        val lookup = MeaningLookup(
            embedQuery = { Embeddings.query(context, it) },
            indexOf = { space -> VectorIndex.read(MeaningSearch.indexFile(context, space)) },
        )
        return runBlocking { lookup.search(query, sources) }
            ?: error("the search-by-meaning model did not answer")
    }

    override val imageCount: Int get() = images.size

    override val modelSeesImages: Boolean get() = seesImages

    override fun imageJpeg(number: Int): ByteArray? =
        images.getOrNull(number - 1)?.let { AiChatImages.jpeg(context, it) }

    /** The scan text tool's online readers, when that is its engine and one is set up. */
    private val onlineReaders: List<ResolvedProvider>
        get() = ocrReaders.takeIf { scanner?.ocrEngine == OcrEngine.ONLINE }.orEmpty()

    override val canReadImageText: Boolean get() = onlineReaders.isNotEmpty() || ChatImageOcr.available

    /**
     * The scan text tool's online engine when that is what the user picked,
     * else ML Kit on the phone.
     */
    override fun readImageText(number: Int): Result<String> = runCatching {
        val name = images.getOrNull(number - 1) ?: throw NoSuchElementException("picture $number")
        val online = onlineReaders
        if (online.isNotEmpty()) {
            val jpeg = AiChatImages.jpeg(context, name) ?: error("the picture is gone")
            return@runCatching OnlineOcr.read(online, jpeg).trim()
        }
        val file = AiChatImages.file(context, name) ?: error("the picture is gone")
        val bitmap = ReplyShots.bitmap(file) ?: error("the picture could not be read")
        // The language the user types in, active first, picks the recognizer.
        val script = hints.firstOrNull()?.let { OcrLanguages.mlKitRecognizer(LanguageRegistry.byId(it)) }
        runBlocking { ChatImageOcr.read(bitmap, script ?: MlKitScript.LATIN) }
    }

    override val canDescribeImage: Boolean get() = describer != null

    override fun describeImage(number: Int, question: String): Result<String> = runCatching {
        val describe = describer ?: throw UnsupportedOperationException("no picture describer here")
        val jpeg = imageJpeg(number) ?: error("the picture is gone")
        com.wasimaster.wmkeyboard.core.tools.AiPictureText.described(number, describerName, describe(jpeg, question))
    }

    private companion object {
        /** How long a send waits for a computer to connect, as a share does. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** How long the link stays held after a send, for the text to go out. */
        const val RELEASE_DELAY_MS = 5_000L
        private const val MAX_STATE_ROWS = 60
        private const val PERCENT = 100.0
        private const val MAX_SPEEDS = 20
        private val SPEED_KEYS = listOf("speed", "fan_speed")
        private val SPEED_SERVICES = setOf("set_speed", "set_fan_speed")
        private val QUIET_STATES = setOf("off", "unavailable", "unknown", "idle", "closed", "locked", "standby", "not_home", "docked", "disarmed")
    }
}
