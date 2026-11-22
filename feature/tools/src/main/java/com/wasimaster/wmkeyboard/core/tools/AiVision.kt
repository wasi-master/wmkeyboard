package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whether a cloud model reads pictures, per model rather than per provider.
 *
 * [AiClient.supportsImages] says whether a provider's request has anywhere to
 * put a picture. That is true of every OpenAI-compatible service, Ollama and
 * LM Studio, whatever model they run, so a text-only model behind one of them
 * used to be sent the picture and refuse the request. Three things are
 * asked, best first:
 *
 * 1. What the service says about the model, where it says: OpenRouter's
 *    model list names each model's inputs, Ollama's `/api/show` lists a
 *    `vision` capability, LM Studio's own list calls a seeing model `vlm`.
 *    [learn] asks, off the main thread, and keeps the answer for a day.
 * 2. What a request taught: a model that turned a picture down
 *    ([refusedPicture]) is marked with [markBlind] until the app restarts.
 * 3. The model's name ([fromName]): the families known to see, and the few
 *    known not to.
 *
 * With none of them sure, a model is taken to see, as before: the request
 * then says so itself, and the caller falls back on [refusedPicture].
 */
object AiVision {

    /** What one answer was, and when it was learned. */
    private class Known(val sees: Boolean, val at: Long)

    /** By [key]: what a service said, or a refused request taught. */
    private val known = ConcurrentHashMap<String, Known>()

    /** OpenRouter's whole list, by model id, and when it was read. */
    @Volatile
    private var openRouter: Pair<Long, Map<String, Boolean>>? = null

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Whether [config]'s model reads pictures: true or false when something
     * says, null when nothing does. Never touches the network, so it is safe
     * on the main thread; [learn] fills in what the services say.
     */
    fun knows(config: AiClient.Config): Boolean? {
        if (!AiClient.supportsImages(config.provider)) return false
        known[key(config)]?.takeIf { System.currentTimeMillis() - it.at < DAY_MS }?.let { return it.sees }
        if (isOpenRouter(config)) {
            openRouter?.takeIf { System.currentTimeMillis() - it.first < DAY_MS }?.second?.get(config.model.trim())?.let { return it }
        }
        return when (config.provider) {
            // Every model either service lists today takes pictures.
            AiProvider.ANTHROPIC, AiProvider.GEMINI -> fromName(config.model) ?: true
            else -> fromName(config.model)
        }
    }

    /** Whether to send [config]'s model a picture: it has somewhere to put one, and nothing says it cannot see. */
    fun sees(config: AiClient.Config): Boolean = knows(config) != false

    /**
     * Asks the service what [config]'s model takes, where it can say, and
     * keeps the answer. Blocking, and quiet on failure: what it could not
     * learn stays as [knows] guesses it. Cheap after the first call.
     */
    fun learn(config: AiClient.Config) {
        if (!AiClient.supportsImages(config.provider) || config.model.isBlank()) return
        val cached = known[key(config)]
        if (cached != null && System.currentTimeMillis() - cached.at < DAY_MS) return
        runCatching {
            when {
                isOpenRouter(config) -> learnOpenRouter(config)
                config.provider == AiProvider.OLLAMA -> learnOllama(config)
                config.provider == AiProvider.LM_STUDIO -> learnLmStudio(config)
                else -> Unit
            }
        }
    }

    /** A request with a picture was turned down because the model cannot read one: [config] is remembered as blind. */
    fun markBlind(config: AiClient.Config) {
        known[key(config)] = Known(false, System.currentTimeMillis())
    }

    /**
     * Whether [failure] is a service saying the model takes no pictures,
     * rather than any other refusal: a 4xx whose message is about images.
     */
    fun refusedPicture(failure: Throwable?): Boolean {
        val http = failure as? ToolHttpException ?: return false
        if (http.status !in REFUSAL_STATUSES) return false
        val message = (http.apiMessage ?: http.message).orEmpty().lowercase()
        return PICTURE_WORDS.any { it in message }
    }

    private val REFUSAL_STATUSES = setOf(400, 404, 415, 422)
    private val PICTURE_WORDS = listOf("image", "vision", "multimodal", "multi-modal", "modalit", "image_url", "picture")

    /**
     * What a model's name says: true for a family known to see, false for
     * one known not to, null when the name says neither. The text-only
     * families are asked first, so "deepseek-vl" is caught by `vl` and plain
     * "deepseek-chat" by its own entry.
     */
    fun fromName(model: String): Boolean? {
        val name = model.trim().lowercase()
        if (name.isEmpty()) return null
        if (SEEING_TOKEN.containsMatchIn(name)) return true
        if (BLIND.any { it.containsMatchIn(name) }) return false
        if (SEEING.any { it.containsMatchIn(name) }) return true
        return null
    }

    /** "vl", "vision", "omni" as words of their own: Qwen2.5-VL, llama-3.2-11b-vision, qwen3-omni. */
    private val SEEING_TOKEN = Regex("(?<![a-z])(vl|vlm|vision|omni|ocr)(?![a-z])")

    private val BLIND = listOf(
        Regex("deepseek"),
        Regex("gpt-3\\.5"),
        Regex("(?<![a-z0-9])o1-mini"),
        Regex("(?<![a-z0-9])o3-mini"),
        Regex("gpt-oss"),
        Regex("(?<![a-z])qwq"),
        Regex("coder"),
        Regex("codestral"),
        Regex("llama-?3\\.[13](?![0-9])"),
        Regex("grok-3"),
    )

    private val SEEING = listOf(
        Regex("gpt-4o"),
        Regex("gpt-4\\.1"),
        Regex("gpt-5"),
        Regex("(?<![a-z0-9])o3(?![a-z0-9-]*mini)"),
        Regex("(?<![a-z0-9])o4"),
        Regex("claude"),
        Regex("gemini"),
        Regex("gemma-?[34](?![0-9])"),
        Regex("llama-?4"),
        Regex("llava"),
        Regex("pixtral"),
        Regex("grok-[4-9]"),
        Regex("minicpm-?v"),
        Regex("moondream"),
        Regex("mistral-(small|medium)-3"),
    )

    private fun key(config: AiClient.Config): String =
        config.provider.name + "|" + hostOf(config.baseUrl) + "|" + config.model.trim()

    private fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase().orEmpty()

    private fun isOpenRouter(config: AiClient.Config): Boolean =
        config.provider == AiProvider.OPENAI_COMPATIBLE && hostOf(config.baseUrl).let { it == "openrouter.ai" || it.endsWith(".openrouter.ai") }

    /** OpenRouter's public model list: `data[].architecture.input_modalities`. No key needed. */
    private fun learnOpenRouter(config: AiClient.Config) {
        val list = openRouter?.takeIf { System.currentTimeMillis() - it.first < DAY_MS }?.second ?: run {
            val url = "https://openrouter.ai/api/v1/models"
            val body = ToolHttp.get(url, timeoutMs = 15_000, source = config.netSource, route = NetLog.pathOf(url))
            parseOpenRouter(body).also { openRouter = System.currentTimeMillis() to it }
        }
        list[config.model.trim()]?.let { known[key(config)] = Known(it, System.currentTimeMillis()) }
    }

    /** Model id → whether its inputs include images, from OpenRouter's list. */
    internal fun parseOpenRouter(body: String): Map<String, Boolean> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            val model = item as? JsonObject ?: return@mapNotNull null
            val id = (model["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val inputs = ((model["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
            id to ("image" in inputs)
        }.toMap()
    }

    /** Ollama's `/api/show`: a `capabilities` list that has `vision` for a model that sees. */
    private fun learnOllama(config: AiClient.Config) {
        val url = config.baseUrl.trim().trimEnd('/') + "/api/show"
        val body = ToolHttp.postJson(
            url,
            buildJsonObject { put("model", config.model.trim()) }.toString(),
            timeoutMs = 10_000,
            source = config.netSource,
            route = NetLog.pathOf(url),
        )
        parseOllamaShow(body)?.let { known[key(config)] = Known(it, System.currentTimeMillis()) }
    }

    /** Whether an `/api/show` answer lists `vision`; null for an Ollama too old to list capabilities. */
    internal fun parseOllamaShow(body: String): Boolean? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val capabilities = (root["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return null
        return "vision" in capabilities
    }

    /** LM Studio's own REST list, `/api/v0/models`: `type` is `vlm` for a model that sees, `llm` for one that does not. */
    private fun learnLmStudio(config: AiClient.Config) {
        val url = config.baseUrl.trim().trimEnd('/').removeSuffix("/v1") + "/api/v0/models"
        val body = ToolHttp.get(url, timeoutMs = 10_000, source = config.netSource, route = NetLog.pathOf(url))
        parseLmStudio(body, config.model.trim())?.let { known[key(config)] = Known(it, System.currentTimeMillis()) }
    }

    internal fun parseLmStudio(body: String, model: String): Boolean? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val entry = (root["data"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.content == model } ?: return null
        return when ((entry["type"] as? JsonPrimitive)?.content) {
            "vlm" -> true
            "llm" -> false
            else -> null
        }
    }
}
