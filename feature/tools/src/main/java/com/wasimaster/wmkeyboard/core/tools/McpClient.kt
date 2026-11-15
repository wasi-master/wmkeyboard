package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.feature.R
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One server's tools as they were last listed, and what it takes to call them:
 * which generation of the protocol it speaks and at which version. Kept by
 * [McpCatalogStore], so a run offers the tools and calls them without first
 * asking the server who it is.
 *
 * [url] is the address the list came from. When the user edits the server's
 * address the entry no longer describes it, and [McpCatalogStore.entryFor]
 * treats it as missing.
 */
@Serializable
data class McpCatalogEntry(
    val url: String,
    val era: McpEra,
    val version: String,
    val serverName: String = "",
    val serverVersion: String = "",
    val tools: List<McpTool> = emptyList(),
    val fetchedAt: Long = 0L,
)

/** An MCP failure with words of our own; see [McpClient.message]. */
class McpException(
    @StringRes val messageRes: Int,
    /** The server's own words, for the messages that quote them. */
    val detail: String = "",
    /** The server turned the request away for want of a sign-in. */
    val signIn: Boolean = false,
) : IOException(detail.ifEmpty { "MCP failure" })

/**
 * A call that stopped on a question for the user, which the model is to ask
 * ([McpInputAnswer.Defer]). Not a failure: [question] is what the model is
 * handed, and the server's state waits in [McpClient] for its next call.
 */
class McpDeferredInput(val question: String) : IOException(question)

/**
 * Talks to the MCP servers the user added (#573). Blocking; call on an IO
 * dispatcher.
 *
 * Only what calling tools needs: find out how a server speaks, list its tools,
 * call one. Three generations of the protocol are in use and a phone cannot
 * pick which server it is pointed at, so all three are spoken:
 *
 * - **Modern** (2026-07-28): stateless. Every POST names its version in `_meta`
 *   and in headers. Found by `server/discover`.
 * - **Legacy Streamable HTTP** (2025-03-26 to 2025-11-25): `initialize` first,
 *   then the session id the server hands back on every request.
 * - **HTTP+SSE** (2024-11-05): a GET event stream that names a POST address,
 *   with every answer coming back down the stream.
 *
 * No connection is kept open between calls. A keyboard is hidden and shown all
 * day and its process can be killed at any moment, so a held socket would
 * mostly be a dead one. An old HTTP server's session id is remembered in memory,
 * because asking for a new one costs two requests per call.
 *
 * A server that wants a sign-in gets the token of [McpAuthState], refreshed
 * here when it has run out ([McpAuth]). What a server asks for in the middle
 * of a call (a question, a completion, its roots) goes to the
 * [McpInputHandler] the caller passes: as an `input_required` result on a
 * modern server, as a request down the answer's stream on an old one.
 */
object McpClient {

    private const val ACCEPT_BOTH = "application/json, text/event-stream"
    private const val SESSION_HEADER = "Mcp-Session-Id"
    private const val VERSION_HEADER = "MCP-Protocol-Version"
    private const val AUTH_HEADER = "WWW-Authenticate"
    private val WANT = setOf(SESSION_HEADER, AUTH_HEADER)

    /** `tools/list` pages read at most; a server with more tools than this has more than any prompt can hold. */
    private const val MAX_PAGES = 10

    private const val TIMEOUT_MS = 60_000

    private val ids = AtomicInteger(1)

    /** Old HTTP servers' sessions, by server id. */
    private val sessions = ConcurrentHashMap<String, String>()

    /**
     * A call waiting on a question the model was asked to put to the user,
     * by server id and tool: the server's state, the answers already given,
     * and the keys still to answer.
     */
    private class Deferred(
        val state: String?,
        val answered: Map<String, JsonObject>,
        val asked: List<String>,
        val at: Long,
    )

    private val deferred = ConcurrentHashMap<String, Deferred>()

    /** How long a deferred question waits for the model's next call. */
    private const val DEFERRED_MS = 15 * 60_000L

    /** Rounds of questions one call may go through before it is given up. */
    private const val MAX_INPUT_ROUNDS = 4

    /** How one server is spoken to, once found out. */
    private data class Link(val era: McpEra, val version: String)

    /** A status outside 2xx with no JSON-RPC answer in its body. */
    private class HttpStatus(val status: Int, val apiMessage: String?) : IOException("HTTP $status")

    // ---- public -----------------------------------------------------------

    /**
     * Finds out how [server] speaks and lists its tools. What the settings
     * screen's Connect button runs, and the only place a server is probed.
     */
    fun connect(
        server: McpServerSpec,
        headers: List<McpHeader>,
        auth: McpAuthState? = null,
        onAuth: (McpAuthState) -> Unit = {},
    ): McpCatalogEntry = guarded {
        authorized(server, headers, auth, onAuth) { sent -> connectWith(server, sent) }
    }

    private fun connectWith(server: McpServerSpec, sent: Map<String, String>): McpCatalogEntry {
        sessions.remove(server.id)
        val url = server.url.trim()
        if (server.transport == McpTransport.SSE) return sseConnect(url, sent)
        val found = try {
            probe(server.id, url, sent)
        } catch (e: HttpStatus) {
            if (server.transport == McpTransport.AUTO && e.status in OLD_TRANSPORT_STATUSES) {
                return sseConnect(url, sent)
            }
            throw e
        }
        val tools = listTools { method, params -> httpCall(server.id, url, sent, found.link, method, params) }
        return McpCatalogEntry(
            url = url,
            era = found.link.era,
            version = found.link.version,
            serverName = found.info.first,
            serverVersion = found.info.second,
            tools = tools,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Calls one tool. [entry] says how the server speaks; when that turns out
     * to be out of date (the server was upgraded, its session expired), the
     * call finds out again and is tried once more.
     */
    fun call(
        server: McpServerSpec,
        headers: List<McpHeader>,
        entry: McpCatalogEntry,
        tool: String,
        argumentsJson: String,
        auth: McpAuthState? = null,
        onAuth: (McpAuthState) -> Unit = {},
        input: McpInputHandler? = null,
    ): McpProtocol.Outcome = guarded {
        authorized(server, headers, auth, onAuth) { sent -> callWith(server, sent, entry, tool, argumentsJson, input) }
    }

    private fun callWith(
        server: McpServerSpec,
        sent: Map<String, String>,
        entry: McpCatalogEntry,
        tool: String,
        argumentsJson: String,
        input: McpInputHandler?,
    ): McpProtocol.Outcome {
        val url = server.url.trim()
        val key = server.id + "\u0000" + tool
        var params = McpProtocol.callParams(tool, McpProtocol.withoutInput(argumentsJson))
        // The model's answers to a question it was asked to put to the user:
        // they go back with the state the server left, as the same call's
        // next round.
        val waiting = deferred.remove(key)
        if (waiting != null && McpProtocol.hasInput(argumentsJson) &&
            System.currentTimeMillis() - waiting.at < DEFERRED_MS
        ) {
            val answers = waiting.answered + McpProtocol.inputAnswers(argumentsJson, waiting.asked)
            params = McpProtocol.retryParams(params, answers, waiting.state)
        }
        if (entry.era == McpEra.SSE) {
            return outcomeOf(sseSession(url, sent, input) { rpc -> rpc("tools/call", params) })
        }
        var link = Link(entry.era, entry.version)
        var reply = try {
            httpCall(server.id, url, sent, link, "tools/call", params, input)
        } catch (e: HttpStatus) {
            if (e.status !in OLD_TRANSPORT_STATUSES) throw e
            // The server no longer speaks the way it did when its tools were
            // listed. Find out how it speaks now, once.
            sessions.remove(server.id)
            link = try {
                probe(server.id, url, sent).link
            } catch (again: HttpStatus) {
                if (server.transport == McpTransport.STREAMABLE_HTTP) throw again
                return outcomeOf(sseSession(url, sent, input) { rpc -> rpc("tools/call", params) })
            }
            httpCall(server.id, url, sent, link, "tools/call", params, input)
        }
        repeat(MAX_INPUT_ROUNDS) {
            val result = (reply as? McpReply.Result)?.result ?: return outcomeOf(reply)
            if (!McpProtocol.needsInput(result)) return outcomeOf(reply)
            val requests = McpProtocol.inputRequests(result)
            if (input == null || requests.isEmpty()) throw McpException(R.string.ftools_mcp_error_needs_input)
            val answers = LinkedHashMap<String, JsonObject>()
            val questions = ArrayList<String>()
            for (request in requests) {
                when (val answer = input.answer(request, canDefer = true)) {
                    is McpInputAnswer.Answer -> answers[request.key] = answer.result
                    is McpInputAnswer.Defer -> questions += answer.question
                    McpInputAnswer.Refuse -> throw McpException(R.string.ftools_mcp_error_needs_input)
                }
            }
            val state = McpProtocol.requestState(result)
            if (questions.isNotEmpty()) {
                deferred[key] = Deferred(
                    state,
                    answers,
                    requests.map { it.key }.filter { it !in answers },
                    System.currentTimeMillis(),
                )
                throw McpDeferredInput(questions.joinToString("\n\n"))
            }
            params = McpProtocol.retryParams(params, answers, state)
            reply = httpCall(server.id, url, sent, link, "tools/call", params, input)
        }
        throw McpException(R.string.ftools_mcp_error_needs_input)
    }

    /** Runs [block], turning a bare status failure into one with words. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: HttpStatus) {
        throw statusFailure(e)
    }

    /**
     * Runs [block] with the headers to send: the user's, and the sign-in's
     * token unless the user set an `Authorization` header of their own. A
     * token that has run out is refreshed first, and one the server turns
     * down is refreshed once and the request tried again; each new token
     * goes to [onAuth] to be kept.
     */
    private inline fun <T> authorized(
        server: McpServerSpec,
        headers: List<McpHeader>,
        auth: McpAuthState?,
        onAuth: (McpAuthState) -> Unit,
        block: (Map<String, String>) -> T,
    ): T {
        // Tokens for another address are not sent: the user changed the
        // server's address, and those tokens belong to the old one.
        val given = auth?.takeIf { it.resource == McpOAuth.canonicalResource(server.url) }
        var state = given
        if (given != null && !given.usable(System.currentTimeMillis()) && given.canRefresh) {
            val renewed = McpAuth.refresh(given)
            if (renewed != null) {
                state = renewed
                onAuth(renewed)
            }
        }
        return try {
            block(headerMap(headers, state))
        } catch (e: McpException) {
            val held = state
            if (!e.signIn || held == null || !held.canRefresh) throw e
            val fresh = McpAuth.refresh(held) ?: throw e
            onAuth(fresh)
            block(headerMap(headers, fresh))
        }
    }

    /** A line for the user about [failure]: ours for an MCP failure, [ToolHttp]'s for the rest. */
    fun message(context: Context, failure: Throwable): String = when (failure) {
        is McpException -> if (failure.detail.isEmpty()) {
            context.getString(failure.messageRes)
        } else {
            context.getString(failure.messageRes, failure.detail)
        }
        else -> ToolHttp.friendlyMessage(context, failure)
    }

    /** Forgets every remembered session, for when servers are edited or removed. */
    fun forget(serverId: String) {
        sessions.remove(serverId)
    }

    // ---- finding out how a server speaks ----------------------------------

    private class Probe(val link: Link, val info: Pair<String, String>)

    /**
     * The modern probe first, as the spec asks. A modern server answers it;
     * an old one answers with an error of its own invention, or not as
     * JSON-RPC at all, and is then asked to `initialize`.
     */
    private fun probe(serverId: String, url: String, sent: Map<String, String>): Probe {
        val modern = Link(McpEra.MODERN, McpProtocol.MODERN_VERSION)
        val discover = try {
            post(url, sent, modern, null, "server/discover", JsonObject(emptyMap()))
        } catch (e: HttpStatus) {
            // A plain 404 or 405 can be an old HTTP server too, which is why
            // this asks `initialize` before giving up on the address.
            if (e.status !in OLD_TRANSPORT_STATUSES) throw e
            null
        }
        when (discover) {
            is McpReply.Result -> {
                val versions = McpProtocol.discoveredVersions(discover.result)
                if (versions.isEmpty() || McpProtocol.MODERN_VERSION in versions) {
                    return Probe(modern, McpProtocol.serverInfo(discover.result))
                }
            }
            is McpReply.Error -> if (McpProtocol.isModernError(discover)) {
                val versions = McpProtocol.supportedVersions(discover)
                if (versions.isNotEmpty() && versions.none { it < McpProtocol.MODERN_VERSION }) {
                    throw McpException(R.string.ftools_mcp_error_no_version)
                }
            }
            null -> Unit
        }
        return initialize(serverId, url, sent)
    }

    /** `initialize` and `notifications/initialized`, the old handshake. */
    private fun initialize(serverId: String, url: String, sent: Map<String, String>): Probe {
        val id = ids.getAndIncrement()
        val body = McpProtocol.request(
            id,
            "initialize",
            // Every call after this one shares the session, so it says once
            // what the client can answer; a call made without a handler
            // still answers what it can (no roots, a declined question).
            McpProtocol.initializeParams(McpProtocol.LEGACY_VERSION, BuildConfig.VERSION_NAME, answersInput = true),
        )
        var session: String? = null
        val reply = ToolHttp.exchange(
            url, "POST", body,
            headers = sent + ("Accept" to ACCEPT_BOTH),
            wantHeaders = WANT,
            timeoutMs = TIMEOUT_MS,
            source = NetSource.AI_MCP,
        ) { exchange ->
            session = exchange.headers[SESSION_HEADER]
            answer(exchange, id)
        }
        val result = when (reply) {
            is McpReply.Result -> reply.result
            is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
        }
        val version = McpProtocol.negotiatedVersion(result) ?: McpProtocol.LEGACY_VERSION
        val given = session
        if (given != null) sessions[serverId] = given else sessions.remove(serverId)
        val link = Link(McpEra.LEGACY_HTTP, version)
        // Answered with 202 and no body; a server that says anything else
        // has still been told, and the next request is the real test.
        runCatching {
            ToolHttp.exchange(
                url, "POST", McpProtocol.notification("notifications/initialized"),
                headers = legacyHeaders(serverId, sent, link),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { }
        }
        return Probe(link, McpProtocol.serverInfo(result))
    }

    // ---- requests over HTTP ------------------------------------------------

    /**
     * One request over the current transport, re-doing the old handshake once
     * when an old server says the session it gave out is gone (a 404).
     */
    private fun httpCall(
        serverId: String,
        url: String,
        sent: Map<String, String>,
        link: Link,
        method: String,
        params: JsonObject,
        input: McpInputHandler? = null,
    ): McpReply {
        if (link.era == McpEra.MODERN) return post(url, sent, link, serverId, method, params, input)
        if (sessions[serverId] == null) initialize(serverId, url, sent)
        return try {
            post(url, sent, link, serverId, method, params, input)
        } catch (e: HttpStatus) {
            if (e.status != HTTP_NOT_FOUND || sessions[serverId] == null) throw e
            sessions.remove(serverId)
            initialize(serverId, url, sent)
            post(url, sent, link, serverId, method, params, input)
        }
    }

    private fun post(
        url: String,
        sent: Map<String, String>,
        link: Link,
        serverId: String?,
        method: String,
        params: JsonObject,
        input: McpInputHandler? = null,
    ): McpReply {
        val id = ids.getAndIncrement()
        val modern = link.era == McpEra.MODERN
        val body = McpProtocol.request(
            id, method, params,
            modern = if (modern) link.version else null,
            clientVersion = BuildConfig.VERSION_NAME,
            answersInput = input != null,
        )
        val headers = if (modern) {
            buildMap {
                putAll(sent)
                put("Accept", ACCEPT_BOTH)
                put(VERSION_HEADER, link.version)
                put("Mcp-Method", method)
                // Required for a call. A name that is not plain ASCII cannot
                // go in a header as it is; the spec's encoded form is left to
                // a server that names its tools that way, which none does yet.
                (params["name"] as? JsonPrimitive)?.content
                    ?.takeIf { name -> name.all { it.code in 0x20..0x7e } }
                    ?.let { put("Mcp-Name", it) }
            }
        } else {
            legacyHeaders(serverId.orEmpty(), sent, link)
        }
        // An old server asks its questions down the answer's stream, and
        // waits there for a POST with the answer.
        val respond: ((String) -> Unit)? = if (modern) {
            null
        } else {
            { message -> postAnswer(url, legacyHeaders(serverId.orEmpty(), sent, link), message) }
        }
        return ToolHttp.exchange(
            url, "POST", body,
            headers = headers,
            wantHeaders = WANT,
            timeoutMs = TIMEOUT_MS,
            source = NetSource.AI_MCP,
        ) { exchange -> answer(exchange, id, input, respond) }
    }

    /** Sends the client's answer to a request a server made. A 202 and no body is the usual reply. */
    private fun postAnswer(url: String, headers: Map<String, String>, message: String) {
        runCatching {
            ToolHttp.exchange(
                url, "POST", message,
                headers = headers + ("Accept" to ACCEPT_BOTH),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { }
        }
    }

    /**
     * The message that answers a server's own request [message], or null when
     * it is not one. Never asks the model: the server is waiting on an open
     * stream. `ping` is answered whatever the handler.
     */
    private fun serverRequestAnswer(message: String, input: McpInputHandler?): String? {
        val (id, request) = McpProtocol.serverRequest(message) ?: return null
        if (request.method == "ping") return McpProtocol.response(id, JsonObject(emptyMap()))
        val answer = (input ?: REFUSING).answer(request, canDefer = false)
        return when (answer) {
            is McpInputAnswer.Answer -> McpProtocol.response(id, answer.result)
            // A question for a model cannot wait on this stream: the user
            // has not seen it, so it is declined rather than guessed at.
            is McpInputAnswer.Defer -> McpProtocol.response(id, McpProtocol.elicitResult("decline"))
            McpInputAnswer.Refuse -> McpProtocol.errorResponse(id, McpProtocol.METHOD_NOT_FOUND, request.method)
        }
    }

    /** What a call with no handler answers: no roots, and every question declined. */
    private val REFUSING = McpInputHandler { request, _ ->
        when (request.method) {
            "roots/list" -> McpInputAnswer.Answer(McpProtocol.rootsResult())
            "elicitation/create" -> McpInputAnswer.Answer(McpProtocol.elicitResult("decline"))
            else -> McpInputAnswer.Refuse
        }
    }

    private fun legacyHeaders(serverId: String, sent: Map<String, String>, link: Link): Map<String, String> =
        buildMap {
            putAll(sent)
            put("Accept", ACCEPT_BOTH)
            put(VERSION_HEADER, link.version)
            sessions[serverId]?.let { put(SESSION_HEADER, it) }
        }

    /**
     * The answer to request [id] in one response, whether the server sent it
     * as one JSON object or down an event stream after some notifications.
     */
    private fun answer(
        exchange: ToolHttp.Exchange,
        id: Int,
        input: McpInputHandler? = null,
        respond: ((String) -> Unit)? = null,
    ): McpReply {
        val status = exchange.status
        if (status == HTTP_UNAUTHORIZED) throw unauthorized(exchange)
        if (status !in 200..299) {
            val text = exchange.body?.readText().orEmpty()
            McpProtocol.reply(text, id)?.let { return it }
            McpProtocol.anonymousError(text)?.let { return it }
            throw HttpStatus(status, ToolHttp.apiErrorText(text))
        }
        val reader = exchange.body ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        if (exchange.contentType == "text/event-stream") {
            return streamed(reader, id, input, respond) ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        }
        val text = reader.readText()
        return McpProtocol.reply(text, id)
            ?: McpProtocol.anonymousError(text)
            ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
    }

    /**
     * Reads events off [reader] until one carries the answer to [id]. A
     * request the server sends on the way is answered through [respond].
     */
    private fun streamed(
        reader: BufferedReader,
        id: Int,
        input: McpInputHandler?,
        respond: ((String) -> Unit)?,
    ): McpReply? {
        val sse = McpSseReader()
        while (true) {
            val line = reader.readLine()
            val event = if (line == null) sse.end() else sse.line(line)
            if (event != null && event.event == "message") {
                McpProtocol.reply(event.data, id)?.let { return it }
                if (respond != null) serverRequestAnswer(event.data, input)?.let(respond)
            }
            if (line == null) return null
        }
    }

    /**
     * A 401. A Bearer challenge means the server wants a sign-in (or a new
     * token), which deserves saying plainly rather than as "your key was
     * rejected" to someone who never gave a key.
     */
    private fun unauthorized(exchange: ToolHttp.Exchange): IOException {
        val challenge = McpOAuth.challenge(exchange.headers[AUTH_HEADER])
        return if (challenge != null) {
            McpException(R.string.ftools_mcp_error_sign_in, signIn = true)
        } else {
            ToolHttp.httpFailure(HTTP_UNAUTHORIZED, exchange.body?.readText())
        }
    }

    private fun statusFailure(e: HttpStatus): IOException =
        if (e.status == HTTP_NOT_FOUND || e.status == HTTP_BAD_METHOD) {
            McpException(R.string.ftools_mcp_error_not_mcp)
        } else {
            ToolHttpException(ToolHttp.statusMessageRes(e.status), e.status, e.apiMessage)
        }

    // ---- the old HTTP+SSE transport ----------------------------------------

    private fun sseConnect(url: String, sent: Map<String, String>): McpCatalogEntry {
        var info = "" to ""
        var version = McpProtocol.SSE_VERSION
        val tools = sseSession(url, sent, null, onReady = { result ->
            info = McpProtocol.serverInfo(result)
            McpProtocol.negotiatedVersion(result)?.let { version = it }
        }) { rpc -> listTools(rpc) }
        return McpCatalogEntry(
            url = url,
            era = McpEra.SSE,
            version = version,
            serverName = info.first,
            serverVersion = info.second,
            tools = tools,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Opens the event stream, waits for the address to POST to, runs the
     * handshake, then hands [work] a way to make requests whose answers come
     * back down the same stream. The stream closes when [work] returns.
     */
    private fun <T> sseSession(
        url: String,
        sent: Map<String, String>,
        input: McpInputHandler?,
        onReady: (JsonObject) -> Unit = {},
        work: ((String, JsonObject) -> McpReply) -> T,
    ): T = ToolHttp.exchange(
        url, "GET",
        headers = sent + ("Accept" to "text/event-stream"),
        wantHeaders = WANT,
        timeoutMs = TIMEOUT_MS,
        source = NetSource.AI_MCP,
    ) { stream ->
        if (stream.status == HTTP_UNAUTHORIZED) throw unauthorized(stream)
        if (stream.status !in 200..299) {
            val text = stream.body?.readText()
            throw statusFailure(HttpStatus(stream.status, ToolHttp.apiErrorText(text)))
        }
        val reader = stream.body ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        val events = McpSseReader()
        fun nextEvent(): McpSseEvent? {
            while (true) {
                val line = reader.readLine() ?: return events.end()
                events.line(line)?.let { return it }
            }
        }
        var endpoint: String? = null
        fun awaitReply(id: Int): McpReply {
            while (true) {
                val event = nextEvent() ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
                if (event.event != "message") continue
                McpProtocol.reply(event.data, id)?.let { return it }
                val to = endpoint ?: continue
                serverRequestAnswer(event.data, input)?.let { postAnswer(to, sent, it) }
            }
        }
        while (endpoint == null) {
            val event = nextEvent() ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
            if (event.event == "endpoint") endpoint = McpProtocol.endpointUrl(url, event.data)
        }
        val target: String = endpoint ?: throw McpException(R.string.ftools_mcp_error_not_mcp)
        val rpc: (String, JsonObject) -> McpReply = { method, params ->
            val id = ids.getAndIncrement()
            val direct = ToolHttp.exchange(
                target, "POST", McpProtocol.request(id, method, params),
                headers = sent + ("Accept" to ACCEPT_BOTH),
                wantHeaders = WANT,
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { exchange ->
                if (exchange.status == HTTP_UNAUTHORIZED) throw unauthorized(exchange)
                // Normally 202 and nothing else; the answer is on the stream.
                // A server that answers in the POST itself is believed too.
                val text = exchange.body?.readText().orEmpty()
                McpProtocol.reply(text, id) ?: if (exchange.status !in 200..299) {
                    throw statusFailure(HttpStatus(exchange.status, ToolHttp.apiErrorText(text)))
                } else {
                    null
                }
            }
            direct ?: awaitReply(id)
        }
        val init = rpc(
            "initialize",
            McpProtocol.initializeParams(McpProtocol.SSE_VERSION, BuildConfig.VERSION_NAME, answersInput = true),
        )
        when (init) {
            is McpReply.Result -> onReady(init.result)
            is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, init.message)
        }
        runCatching {
            ToolHttp.exchange(
                target, "POST", McpProtocol.notification("notifications/initialized"),
                headers = sent,
                timeoutMs = TIMEOUT_MS,
                source = NetSource.AI_MCP,
            ) { }
        }
        work(rpc)
    }

    // ---- shared --------------------------------------------------------------

    private fun listTools(rpc: (String, JsonObject) -> McpReply): List<McpTool> {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val reply = rpc("tools/list", McpProtocol.listParams(cursor))) {
                is McpReply.Result -> McpProtocol.toolPage(reply.result)
                is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
            }
            tools += page.tools
            cursor = page.nextCursor ?: return tools.distinctBy { it.name }
        }
        return tools.distinctBy { it.name }
    }

    private fun outcomeOf(reply: McpReply): McpProtocol.Outcome = when (reply) {
        is McpReply.Error -> throw McpException(R.string.ftools_mcp_error_server, reply.message)
        is McpReply.Result -> {
            if (McpProtocol.needsInput(reply.result)) throw McpException(R.string.ftools_mcp_error_needs_input)
            McpProtocol.outcome(reply.result)
        }
    }

    /**
     * The user's headers, cleaned: no blank names, and none of the ones the
     * protocol itself sets, which a pasted config sometimes carries. Then the
     * sign-in's token, unless the user gave an `Authorization` of their own.
     */
    private fun headerMap(headers: List<McpHeader>, auth: McpAuthState? = null): Map<String, String> {
        val own = headers.filter { it.name.isNotBlank() && it.name.trim().lowercase() !in RESERVED }
            .associate { it.name.trim() to it.value.trim() }
        val token = auth?.accessToken?.takeIf { it.isNotEmpty() } ?: return own
        if (own.keys.any { it.equals("authorization", ignoreCase = true) }) return own
        return own + ("Authorization" to "Bearer $token")
    }

    private val RESERVED = setOf(
        "accept", "content-type", "content-length", "host",
        "mcp-session-id", "mcp-protocol-version", "mcp-method", "mcp-name",
    )

    /** What an old server answers a POST it does not understand with. */
    private val OLD_TRANSPORT_STATUSES = setOf(400, 404, 405)

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_BAD_METHOD = 405
}
