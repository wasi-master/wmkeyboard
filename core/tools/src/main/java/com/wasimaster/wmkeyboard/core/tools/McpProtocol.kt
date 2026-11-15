package com.wasimaster.wmkeyboard.core.tools

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool an MCP server offers (#573), as `tools/list` described it.
 *
 * [readOnly] and [destructive] are the server's own annotations
 * (`readOnlyHint`, `destructiveHint`). They are hints, and the spec says to
 * treat them as untrusted unless the server is. Here they decide only where a
 * tool *starts*: a read-only one runs without asking, until the user says
 * otherwise. The user's own choice always wins.
 */
@Serializable
data class McpTool(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
) {
    /** What a person reads: the server's title for the tool, or its name. */
    val label: String get() = title.trim().ifEmpty { name }
}

/**
 * Which generation of the protocol a server speaks. Found once, on Connect, and
 * kept with the server's tool list, so a run never spends a request finding out.
 */
@Serializable
enum class McpEra {
    /** 2026-07-28 and later: stateless, every request carries its version. */
    MODERN,

    /** 2025-03-26 to 2025-11-25: `initialize` first, then a session id on every request. */
    LEGACY_HTTP,

    /** 2024-11-05: a GET event stream, and POSTs to the address it names. */
    SSE,
}

/** A JSON-RPC answer to one request. */
sealed interface McpReply {
    data class Result(val result: JsonObject) : McpReply

    data class Error(val code: Int, val message: String, val data: JsonElement?) : McpReply
}

/**
 * One thing a server asked the client for in the middle of a call (MRTR):
 * `elicitation/create`, `sampling/createMessage` or `roots/list`, with [key]
 * the name its answer goes back under.
 */
data class McpInputRequest(val key: String, val method: String, val params: JsonObject)

/** What the client does about one [McpInputRequest]. */
sealed interface McpInputAnswer {
    /** The answer to send back. */
    data class Answer(val result: JsonObject) : McpInputAnswer

    /**
     * A question only the user can answer, handed to the model instead: it
     * asks the user in the chat and calls the tool again with the answers
     * in [McpProtocol.INPUT_ARGUMENT]. [question] is what the model is told.
     */
    data class Defer(val question: String) : McpInputAnswer

    /** Nothing to answer with: the call fails. */
    data object Refuse : McpInputAnswer
}

/**
 * Answers what a server asks for in the middle of a call. [canDefer] is
 * false where the server is waiting on an open stream (an old server's own
 * request), so the question cannot go to the model and come back later.
 */
fun interface McpInputHandler {
    fun answer(request: McpInputRequest, canDefer: Boolean): McpInputAnswer
}

/** One server-sent event: its type and its whole data, lines joined. */
data class McpSseEvent(val event: String, val data: String)

/**
 * Reads a `text/event-stream` one line at a time. Both MCP transports use it:
 * the current one may answer a POST with a stream, and the old one sends every
 * answer down a GET stream.
 *
 * Only the two fields MCP uses are read; `id:` and `retry:` matter to a client
 * that resumes a stream, and this one never does.
 */
class McpSseReader {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Takes one line without its line break; returns an event when the line ends one. */
    fun line(line: String): McpSseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        var value = if (':' in line) line.substringAfter(':') else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit
        }
        return null
    }

    /** The end of the stream: an event still being read counts as finished. */
    fun end(): McpSseEvent? = dispatch()

    private fun dispatch(): McpSseEvent? {
        val ready = if (hasData) McpSseEvent(event.ifEmpty { "message" }, data.toString()) else null
        event = ""
        data.setLength(0)
        hasData = false
        return ready
    }
}

/**
 * The wire format of the Model Context Protocol, as far as a client that only
 * calls tools needs it (#573). Pure: no network, no Android. `McpClient` in
 * `:feature:tools` moves the bytes.
 */
object McpProtocol {

    /** The version a modern request carries. */
    const val MODERN_VERSION = "2026-07-28"

    /** The version offered to a server that wants `initialize`. */
    const val LEGACY_VERSION = "2025-11-25"

    /** What the old HTTP+SSE transport speaks. */
    const val SSE_VERSION = "2024-11-05"

    const val CLIENT_NAME = "WM Keyboard"

    const val UNSUPPORTED_VERSION = -32022

    /** JSON-RPC's "method not found", for a server request this client does not know. */
    const val METHOD_NOT_FOUND = -32601

    /** The error codes only a modern server sends; any other error means an old server. */
    private val MODERN_ERRORS = -32022..-32020

    /** Longest name every provider accepts for a function. */
    const val MAX_NAME = 64

    /** A tool description longer than this is cut: it is paid for in every prompt. */
    const val MAX_DESCRIPTION = 1_000

    /** Characters of one call's answer handed to the model. */
    const val MAX_RESULT = 8_000

    private val json = Json { ignoreUnknownKeys = true }

    // ---- requests ---------------------------------------------------------

    /**
     * One request. [modern] is the version a modern request names in its
     * `_meta`; an old server gets no `_meta` at all, since it never asked for one.
     */
    fun request(
        id: Int,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        modern: String? = null,
        clientVersion: String = "",
        answersInput: Boolean = false,
    ): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", if (modern == null) params else withMeta(params, modern, clientVersion, answersInput))
    }.toString()

    /** One notification: no id, and nothing comes back. */
    fun notification(method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }.toString()

    private fun withMeta(params: JsonObject, version: String, clientVersion: String, answersInput: Boolean): JsonObject {
        val meta = buildJsonObject {
            (params["_meta"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("io.modelcontextprotocol/protocolVersion", version)
            putJsonObject("io.modelcontextprotocol/clientInfo") {
                put("name", CLIENT_NAME)
                put("version", clientVersion.ifBlank { "1" })
            }
            put("io.modelcontextprotocol/clientCapabilities", capabilities(answersInput))
        }
        return JsonObject(params + ("_meta" to meta))
    }

    /**
     * What the client says it can answer. With [answersInput], everything a
     * server may ask for in the middle of a call: a question for the user
     * (a form, or a page to open), a completion from the user's own model,
     * and the folders it may look at, of which a phone shares none. Without,
     * nothing, so a server does not ask what no one will answer.
     */
    fun capabilities(answersInput: Boolean): JsonObject = buildJsonObject {
        if (!answersInput) return@buildJsonObject
        putJsonObject("elicitation") {
            putJsonObject("form") {}
            putJsonObject("url") {}
        }
        putJsonObject("sampling") {}
        putJsonObject("roots") {}
    }

    /** What an old server's `initialize` takes. */
    fun initializeParams(version: String, clientVersion: String, answersInput: Boolean = false): JsonObject = buildJsonObject {
        put("protocolVersion", version)
        put("capabilities", capabilities(answersInput))
        putJsonObject("clientInfo") {
            put("name", CLIENT_NAME)
            put("version", clientVersion.ifBlank { "1" })
        }
    }

    fun listParams(cursor: String?): JsonObject = buildJsonObject {
        if (!cursor.isNullOrEmpty()) put("cursor", cursor)
    }

    /**
     * What `tools/call` takes. [argumentsJson] came from a model, so anything
     * that is not a JSON object is sent as no arguments rather than refused
     * here: the server's own validation error says more than ours would.
     */
    fun callParams(tool: String, argumentsJson: String): JsonObject = buildJsonObject {
        put("name", tool)
        put("arguments", callArguments(argumentsJson))
    }

    internal fun callArguments(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    // ---- answers ----------------------------------------------------------

    /**
     * The answer to request [id] inside one message, or null when the message
     * is something else: a notification, a request from the server, or the
     * answer to another request. A JSON array is read as a batch, which old
     * servers were allowed to send.
     */
    fun reply(message: String, id: Int): McpReply? {
        val root = runCatching { json.parseToJsonElement(message) }.getOrNull() ?: return null
        val candidates = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        for (candidate in candidates) {
            if ((candidate["id"] as? JsonPrimitive)?.contentOrNull != id.toString()) continue
            (candidate["error"] as? JsonObject)?.let { return error(it) }
            (candidate["result"] as? JsonObject)?.let { return McpReply.Result(it) }
        }
        return null
    }

    /** An error with no `id` at all, which a server sends when it could not read the request. */
    fun anonymousError(message: String): McpReply.Error? {
        val root = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
        if (root["jsonrpc"] == null) return null
        return (root["error"] as? JsonObject)?.let(::error)
    }

    private fun error(obj: JsonObject) = McpReply.Error(
        code = (obj["code"] as? JsonPrimitive)?.intOrNull ?: 0,
        message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        data = obj["data"],
    )

    /** Whether only a modern server would send [error]. */
    fun isModernError(error: McpReply.Error): Boolean = error.code in MODERN_ERRORS

    /** The versions a server listed when it refused ours, or empty. */
    fun supportedVersions(error: McpReply.Error): List<String> =
        ((error.data as? JsonObject)?.get("supported") as? JsonArray).strings()

    /** The versions a `server/discover` result lists. */
    fun discoveredVersions(result: JsonObject): List<String> =
        (result["supportedVersions"] as? JsonArray).strings()

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /**
     * Whether a modern result is asking the client for something before it
     * can finish (a question for the user, a model completion): the call is
     * then sent again with the answers ([inputRequests], [retryParams]).
     */
    fun needsInput(result: JsonObject): Boolean {
        val type = (result["resultType"] as? JsonPrimitive)?.contentOrNull ?: return false
        return type != "complete"
    }

    // ---- input a server asks for (MRTR, and the old servers' requests) ----

    /** The requests an `input_required` result carries, by the key the answer goes back under. */
    fun inputRequests(result: JsonObject): List<McpInputRequest> =
        (result["inputRequests"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            McpInputRequest(key, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
        }

    /** The opaque state an `input_required` result asks to have sent back. */
    fun requestState(result: JsonObject): String? = (result["requestState"] as? JsonPrimitive)?.contentOrNull

    /** [params] again, with the answers and the server's state, for the retry. */
    fun retryParams(params: JsonObject, responses: Map<String, JsonObject>, state: String?): JsonObject =
        JsonObject(
            buildMap {
                putAll(params - "inputResponses" - "requestState")
                put("inputResponses", JsonObject(responses))
                if (state != null) put("requestState", JsonPrimitive(state))
            },
        )

    /**
     * A request an old server sent down the stream of a call, in place of an
     * `input_required` result: the id to answer, and what it asks. Null for
     * anything that is not a request (an answer, a notification).
     */
    fun serverRequest(message: String): Pair<JsonElement, McpInputRequest>? {
        val obj = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.takeIf { it !is kotlinx.serialization.json.JsonNull } ?: return null
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return id to McpInputRequest(method, method, params)
    }

    /** The answer to a server's own request [id]. */
    fun response(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    /** A refusal of a server's own request [id], for a method this client does not know. */
    fun errorResponse(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    /** `elicitation/create`'s answer. [content] only with [action] `accept`. */
    fun elicitResult(action: String, content: JsonObject? = null): JsonObject = buildJsonObject {
        put("action", action)
        if (content != null && action == "accept") put("content", content)
    }

    /** `roots/list`'s answer: a phone shares no folders with a server. */
    fun rootsResult(): JsonObject = buildJsonObject { put("roots", JsonArray(emptyList())) }

    /** `sampling/createMessage`'s answer. */
    fun samplingResult(text: String, model: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        putJsonObject("content") {
            put("type", "text")
            put("text", text)
        }
        put("model", model.ifBlank { "unknown" })
        put("stopReason", "endTurn")
    }

    /** What an `elicitation/create` asks, read for a person or a model. */
    data class Elicitation(
        val message: String,
        /** Set for URL mode: the page the user is asked to open. */
        val url: String?,
        val fields: List<ElicitField>,
    )

    data class ElicitField(
        val name: String,
        val title: String,
        val type: String,
        val required: Boolean,
        val options: List<String>,
        val description: String,
    )

    fun elicitation(params: JsonObject): Elicitation {
        val message = (params["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val mode = (params["mode"] as? JsonPrimitive)?.contentOrNull
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { mode == "url" && (it.startsWith("https://") || it.startsWith("http://")) }
        val schema = params["requestedSchema"] as? JsonObject
        val required = ((schema?.get("required")) as? JsonArray).strings().toSet()
        val fields = (schema?.get("properties") as? JsonObject).orEmpty().map { (name, value) ->
            val obj = value as? JsonObject ?: JsonObject(emptyMap())
            val options = (obj["enum"] as? JsonArray).strings().ifEmpty {
                (obj["oneOf"] as? JsonArray).orEmpty().mapNotNull { option ->
                    ((option as? JsonObject)?.get("const") as? JsonPrimitive)?.contentOrNull
                }
            }
            ElicitField(
                name = name,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "string",
                required = name in required,
                options = options,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        return Elicitation(message, url, fields)
    }

    /**
     * A sampling request as plain text: its system prompt, and its messages
     * oldest first with whether each is the user's. Only text is read; a
     * picture or sound in it is left out.
     */
    fun samplingTurns(params: JsonObject): Pair<String, List<Pair<Boolean, String>>> {
        val system = (params["systemPrompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val turns = (params["messages"] as? JsonArray).orEmpty().mapNotNull { message ->
            val obj = message as? JsonObject ?: return@mapNotNull null
            val user = (obj["role"] as? JsonPrimitive)?.contentOrNull != "assistant"
            val parts = when (val content = obj["content"]) {
                is JsonArray -> content.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(content)
                else -> emptyList()
            }
            val text = parts.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                .joinToString("\n")
            text.takeIf { it.isNotBlank() }?.let { user to it }
        }
        return system to turns
    }

    /** The cap a sampling request puts on its answer, or null. */
    fun samplingMaxTokens(params: JsonObject): Int? = (params["maxTokens"] as? JsonPrimitive)?.intOrNull

    /**
     * Answers the model gave for a deferred question: the `_mcp_input` object
     * in its arguments, by request key. A key the model set to the string
     * `decline` is declined; a key it left out is cancelled.
     */
    fun inputAnswers(argumentsJson: String, keys: Collection<String>): Map<String, JsonObject> {
        val given = callArguments(argumentsJson)[INPUT_ARGUMENT] as? JsonObject ?: JsonObject(emptyMap())
        return keys.associateWith { key ->
            when (val value = given[key]) {
                is JsonObject -> elicitResult("accept", value)
                is JsonPrimitive -> if (value.contentOrNull.equals("decline", ignoreCase = true)) {
                    elicitResult("decline")
                } else {
                    elicitResult("cancel")
                }
                else -> elicitResult("cancel")
            }
        }
    }

    /** [argumentsJson] without the answers to a deferred question, as the server should see it. */
    fun withoutInput(argumentsJson: String): String {
        val args = callArguments(argumentsJson)
        if (INPUT_ARGUMENT !in args) return argumentsJson
        return JsonObject(args - INPUT_ARGUMENT).toString()
    }

    fun hasInput(argumentsJson: String): Boolean = INPUT_ARGUMENT in callArguments(argumentsJson)

    /** The argument a model puts a deferred question's answers in. */
    const val INPUT_ARGUMENT = "_mcp_input"

    /** The server's own name and version, from either generation's answer. */
    fun serverInfo(result: JsonObject): Pair<String, String> {
        val info = (result["serverInfo"] as? JsonObject)
            ?: ((result["_meta"] as? JsonObject)?.get("io.modelcontextprotocol/serverInfo") as? JsonObject)
        val name = (info?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val version = (info?.get("version") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return name to version
    }

    /** The version an old server agreed to in its `initialize` answer. */
    fun negotiatedVersion(result: JsonObject): String? =
        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull

    /**
     * The address the old transport's `endpoint` event names. Usually a path
     * with a session query, relative to the stream's own address.
     */
    fun endpointUrl(base: String, data: String): String? {
        val target = data.trim()
        if (target.isEmpty()) return null
        return runCatching { URI(base).resolve(target).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // ---- tools ------------------------------------------------------------

    /** One page of `tools/list`. */
    data class ToolPage(val tools: List<McpTool>, val nextCursor: String?)

    fun toolPage(result: JsonObject): ToolPage {
        val tools = (result["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::tool) }
        val cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        return ToolPage(tools, cursor)
    }

    private fun tool(obj: JsonObject): McpTool? {
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return null
        val annotations = obj["annotations"] as? JsonObject
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            ?: (annotations?.get("title") as? JsonPrimitive)?.contentOrNull
        return McpTool(
            name = name,
            title = title.orEmpty().trim(),
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
            inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            readOnly = annotations.flag("readOnlyHint") == true,
            // The spec's default for an unmarked tool is destructive, but
            // that matters only for a tool that is not read-only, which asks
            // first anyway. So only an explicit true counts here.
            destructive = annotations.flag("destructiveHint") == true,
        )
    }

    private fun JsonObject?.flag(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /**
     * The name a model is shown for [tool] on [server]: both, so two servers
     * can each have a `search`, and so no MCP tool can take the name of one of
     * ours. Only letters, digits, `_` and `-`, starting with a letter, and at
     * most [MAX_NAME] characters, which is the strictest of what the providers
     * accept. [taken] holds the names already handed out in this run.
     */
    fun exposedName(server: String, tool: String, taken: Set<String>): String {
        val prefix = slug(server).take(SERVER_SLUG).ifEmpty { "mcp" }.let {
            if (it.first().isLetter()) it else "m$it"
        }
        val base = (prefix + "__" + slug(tool).ifEmpty { "tool" }).take(MAX_NAME)
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(MAX_NAME - suffix.length) + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun slug(text: String): String =
        text.trim().map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '_' }
            .joinToString("")
            .replace(UNDERSCORES, "_")
            .trim('_')

    /**
     * [schema] in the shape every provider accepts for a function's
     * parameters: an object, with properties. A server may send `{}` for a
     * tool that takes nothing, which OpenAI refuses.
     */
    fun patchSchema(schema: JsonObject): JsonObject {
        var out = schema
        if (out["type"] == null) out = JsonObject(out + ("type" to JsonPrimitive("object")))
        if (out["properties"] !is JsonObject) out = JsonObject(out + ("properties" to JsonObject(emptyMap())))
        return out
    }

    /** [tool] as the spec a model is offered, under [exposedName]. */
    fun spec(exposedName: String, serverLabel: String, tool: McpTool): AiToolSpec {
        val about = tool.description.ifEmpty { tool.label }
        val description = "$about (from the MCP server \"$serverLabel\")"
        return AiToolSpec(
            name = exposedName,
            description = if (description.length > MAX_DESCRIPTION) {
                description.take(MAX_DESCRIPTION - 1).trimEnd() + "…"
            } else {
                description
            },
            parameters = patchSchema(tool.inputSchema),
        )
    }

    // ---- call results ------------------------------------------------------

    /**
     * What one call gave back, ready for the model. [omitted] names the parts
     * that are not text (`image`, `audio`, `resource_link`), which the runner
     * mentions so the model knows something was there.
     */
    data class Outcome(
        val text: String,
        val isError: Boolean,
        val omitted: List<String>,
        val truncated: Boolean,
    )

    fun outcome(result: JsonObject, maxChars: Int = MAX_RESULT): Outcome {
        val parts = (result["content"] as? JsonArray).orEmpty()
        val texts = ArrayList<String>()
        val omitted = ArrayList<String>()
        for (part in parts) {
            val obj = part as? JsonObject ?: continue
            when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                "resource" -> {
                    // An embedded resource may carry text of its own, which
                    // is as readable as a text part.
                    val text = ((obj["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (text != null) texts.add(text) else omitted.add(type)
                }
                else -> omitted.add(type.ifEmpty { "unknown" })
            }
        }
        var text = texts.joinToString("\n\n").trim()
        if (text.isEmpty()) {
            // A tool that answers only with structured data is still an answer.
            result["structuredContent"]?.let { text = it.toString() }
        }
        val cut = text.length > maxChars
        return Outcome(
            text = if (cut) text.take(maxChars) else text,
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true,
            omitted = omitted.distinct(),
            truncated = cut,
        )
    }

    /**
     * [argumentsJson] as one short line, for the record under an answer. The
     * whole of it is shown only while the user is being asked to allow it.
     */
    fun preview(argumentsJson: String, max: Int = PREVIEW_CHARS): String {
        val args = callArguments(argumentsJson)
        if (args.isEmpty()) return ""
        val line = args.entries.joinToString(", ") { (key, value) ->
            val shown = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
            "$key: $shown"
        }.replace('\n', ' ')
        return if (line.length > max) line.take(max - 1).trimEnd() + "…" else line
    }

    /** [argumentsJson] indented, for the approval card. */
    fun pretty(argumentsJson: String): String {
        val args = callArguments(argumentsJson)
        if (args.isEmpty()) return ""
        return PRETTY.encodeToString(JsonObject.serializer(), args)
    }

    private val PRETTY = Json { prettyPrint = true }
    private val UNDERSCORES = Regex("_+")
    private const val SERVER_SLUG = 20
    private const val PREVIEW_CHARS = 80
}

/**
 * Reads an MCP configuration someone copied from another app's settings
 * (#573), so a server can be added by pasting rather than typed field by field.
 *
 * Takes the shapes in use: Claude Desktop and Cursor's
 * `{"mcpServers": {name: {...}}}`, VS Code's `{"servers": {name: {...}}}`,
 * the inner map on its own, and one server object.
 *
 * A server that is started as a local command (`"command": "npx"`) cannot be
 * started by an Android app: the system lets no app run a program it brought
 * along. It comes in pointed at a bridge on the same phone instead,
 * `http://127.0.0.1:<port>/mcp`, with the line that starts the bridge
 * ([Server.bridgeCommand]): supergateway runs the program and speaks
 * Streamable HTTP for it, in Termux or on a computer.
 */
object McpConfigImport {

    data class Server(
        val name: String,
        val url: String,
        val transport: McpTransport,
        val headers: List<McpHeader>,
        /** For a local program: the line that runs it behind a bridge at [url]. */
        val bridgeCommand: String = "",
    )

    /** The port the first bridged server listens on; each next one takes the next port. */
    const val FIRST_BRIDGE_PORT = 8811

    enum class SkipReason {
        /** Started as a local program with no command to bridge. Kept for configs read before bridging. */
        COMMAND,

        /** No address at all. */
        NO_URL,

        /** An address that is not http or https. */
        BAD_URL,
    }

    data class Skipped(val name: String, val reason: SkipReason)

    data class Result(val servers: List<Server>, val skipped: List<Skipped>) {
        val isEmpty: Boolean get() = servers.isEmpty() && skipped.isEmpty()
    }

    /** Null when [text] is not JSON at all, or not an object. */
    fun parse(text: String): Result? {
        val root = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null
        val map = (root["mcpServers"] as? JsonObject)
            ?: (root["servers"] as? JsonObject)
            ?: (root["mcp"] as? JsonObject)?.let { it["servers"] as? JsonObject }
        val entries: List<Pair<String, JsonObject>> = when {
            map != null -> map.entries.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }
            looksLikeServer(root) -> listOf(stringOf(root, "name").orEmpty() to root)
            // A bare map of servers, with no wrapper.
            else -> root.entries.mapNotNull { (name, value) ->
                (value as? JsonObject)?.takeIf(::looksLikeServer)?.let { name to it }
            }
        }
        val servers = ArrayList<Server>()
        val skipped = ArrayList<Skipped>()
        var port = FIRST_BRIDGE_PORT
        for ((name, body) in entries) {
            val url = stringOf(body, "url") ?: stringOf(body, "serverUrl") ?: stringOf(body, "endpoint")
            val command = stringOf(body, "command")
            when {
                url == null && command != null -> {
                    servers.add(
                        Server(
                            name.trim(),
                            "http://127.0.0.1:$port/mcp",
                            McpTransport.STREAMABLE_HTTP,
                            emptyList(),
                            bridgeCommand(command, argsOf(body), envOf(body), port),
                        ),
                    )
                    port++
                }
                url == null && body["command"] != null -> skipped.add(Skipped(name, SkipReason.COMMAND))
                url == null -> skipped.add(Skipped(name, SkipReason.NO_URL))
                !isHttp(url) -> skipped.add(Skipped(name, SkipReason.BAD_URL))
                else -> servers.add(Server(name.trim(), url.trim(), transportOf(body, url), headersOf(body)))
            }
        }
        return Result(servers, skipped)
    }

    /**
     * The line that starts [command] behind supergateway on [port]. The
     * program's own line goes in single quotes, so nothing in it is read by
     * the shell the bridge is started from; the bridge hands it to a shell of
     * its own, where each argument is quoted again if it needs to be.
     */
    fun bridgeCommand(command: String, args: List<String>, env: Map<String, String>, port: Int): String {
        val inner = buildList {
            for ((key, value) in env) add("$key=${shellWord(value)}")
            add(shellWord(command))
            args.forEach { add(shellWord(it)) }
        }.joinToString(" ")
        val outer = "'" + inner.replace("'", "'\\''") + "'"
        return "npx -y supergateway --stdio $outer --outputTransport streamableHttp --port $port"
    }

    private val PLAIN_WORD = Regex("[A-Za-z0-9_@%+=:,./-]+")

    /** [word] as one shell word: as it is when it is plain, else in double quotes. */
    private fun shellWord(word: String): String =
        if (word.matches(PLAIN_WORD)) {
            word
        } else {
            "\"" + word.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\""
        }

    private fun argsOf(body: JsonObject): List<String> =
        (body["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun envOf(body: JsonObject): Map<String, String> =
        (body["env"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.orEmpty().filter { (key, _) -> key.matches(ENV_NAME) }.toMap()

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun looksLikeServer(obj: JsonObject): Boolean =
        obj["url"] is JsonPrimitive || obj["serverUrl"] is JsonPrimitive || obj["command"] is JsonPrimitive

    private fun stringOf(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun isHttp(url: String): Boolean {
        val lower = url.trim().lowercase()
        val rest = when {
            lower.startsWith("https://") -> lower.removePrefix("https://")
            lower.startsWith("http://") -> lower.removePrefix("http://")
            else -> return false
        }
        return rest.substringBefore('/').isNotEmpty()
    }

    private fun transportOf(body: JsonObject, url: String): McpTransport {
        val type = (stringOf(body, "type") ?: stringOf(body, "transport"))?.lowercase()
        return when {
            type == null -> if (url.trimEnd('/').endsWith("/sse")) McpTransport.SSE else McpTransport.AUTO
            "sse" in type -> McpTransport.SSE
            "http" in type || "stream" in type -> McpTransport.STREAMABLE_HTTP
            else -> McpTransport.AUTO
        }
    }

    private fun headersOf(body: JsonObject): List<McpHeader> =
        (body["headers"] as? JsonObject)?.entries?.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { McpHeader(name, it) }
        }.orEmpty()
}
