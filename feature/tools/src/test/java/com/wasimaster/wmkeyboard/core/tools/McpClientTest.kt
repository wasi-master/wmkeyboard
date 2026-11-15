package com.wasimaster.wmkeyboard.core.tools

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.feature.R
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The MCP client against three fake servers (#573), one per generation of the
 * protocol, plus the two failures a user is most likely to meet: a server that
 * wants a sign-in, and an address that is not an MCP server at all.
 */
class McpClientTest {

    private lateinit var server: HttpServer
    private lateinit var base: String

    /** Every method the servers saw, in order, with the session header it came with. */
    private val seen = java.util.Collections.synchronizedList(ArrayList<String>())

    private val tools = """[
        {"name":"echo","description":"Says it back","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}},
         "annotations":{"readOnlyHint":true}},
        {"name":"rm","inputSchema":{"type":"object"}}
    ]""".replace("\n", " ")

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // The old transport holds a GET open while POSTs arrive beside it.
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/modern", ::modern)
        server.createContext("/legacy", ::legacy)
        server.createContext("/sse", ::sse)
        server.createContext("/oauth") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add(
                "WWW-Authenticate",
                """Bearer resource_metadata="https://a.test/.well-known/oauth-protected-resource"""",
            )
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.createContext("/nothing") { exchange ->
            exchange.requestBody.readBytes()
            send(exchange, 404, "text/html", "<h1>Not found</h1>")
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        NetLog.clear()
    }

    @After
    fun stop() {
        sseStreams.values.forEach { it.offer(CLOSE) }
        server.stop(0)
    }

    private fun spec(path: String, transport: McpTransport = McpTransport.AUTO) =
        McpServerSpec(id = "mcp_$path", name = path, url = "$base/$path", transport = transport)

    // ---- the modern server ------------------------------------------------------

    private fun modern(exchange: HttpExchange) {
        val body = request(exchange)
        val method = body.method
        seen += "$method ${exchange.requestHeaders.getFirst("Mcp-Method")} ${exchange.requestHeaders.getFirst("MCP-Protocol-Version")}"
        val id = body.id
        when (method) {
            "server/discover" -> json(
                exchange,
                """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","supportedVersions":["${McpProtocol.MODERN_VERSION}"],
                   "capabilities":{"tools":{}},"_meta":{"io.modelcontextprotocol/serverInfo":{"name":"Modern","version":"9"}}}}""",
            )
            "tools/list" -> json(exchange, """{"jsonrpc":"2.0","id":$id,"result":{"tools":$tools}}""")
            "tools/call" -> {
                seen += "name=" + exchange.requestHeaders.getFirst("Mcp-Name")
                if (body.params["name"]?.jsonPrimitive?.content == "ask") return askTool(exchange, id, body.params)
                val text = body.params["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content
                // Answered down a stream, after a notification it has to skip.
                send(
                    exchange, 200, "text/event-stream",
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":" +
                        "[{\"type\":\"text\",\"text\":\"$text\"}],\"resultType\":\"complete\"}}\n\n",
                )
            }
            else -> json(exchange, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no"}}""")
        }
    }

    /**
     * A tool that needs the user's name first (MRTR): `input_required` with a
     * form, then the name back once the retry carries it with the state.
     */
    private fun askTool(exchange: HttpExchange, id: String, params: JsonObject) {
        val responses = params["inputResponses"] as? JsonObject
        if (responses == null) {
            json(
                exchange,
                """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"input_required","requestState":"S9","inputRequests":{
                   "login":{"method":"elicitation/create","params":{"mode":"form","message":"Name?",
                   "requestedSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}},
                   "where":{"method":"roots/list","params":{}}}}}""",
            )
            return
        }
        seen += "state=" + params["requestState"]?.jsonPrimitive?.content
        seen += "roots=" + responses["where"]
        val login = responses["login"]!!.jsonObject
        val name = login["content"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: login["action"]!!.jsonPrimitive.content
        json(exchange, """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","content":[{"type":"text","text":"hello $name"}]}}""")
    }

    @Test
    fun `a question from a modern server is answered and the call retried`() {
        val entry = McpClient.connect(spec("modern"), emptyList())
        val direct = McpInputHandler { request, _ ->
            when (request.method) {
                "roots/list" -> McpInputAnswer.Answer(McpProtocol.rootsResult())
                else -> McpInputAnswer.Answer(
                    McpProtocol.elicitResult("accept", Json.parseToJsonElement("""{"name":"Ann"}""").jsonObject),
                )
            }
        }
        assertEquals("hello Ann", McpClient.call(spec("modern"), emptyList(), entry, "ask", "{}", input = direct).text)
        assertTrue(seen.contains("state=S9"))
        assertTrue(seen.contains("""roots={"roots":[]}"""))
    }

    @Test
    fun `a form handed to the model comes back on the next call`() {
        val entry = McpClient.connect(spec("modern"), emptyList())
        val toModel = McpInputHandler { request, _ ->
            if (request.method == "roots/list") {
                McpInputAnswer.Answer(McpProtocol.rootsResult())
            } else {
                McpInputAnswer.Defer("ask for login")
            }
        }
        val asked = assertThrows(McpDeferredInput::class.java) {
            McpClient.call(spec("modern"), emptyList(), entry, "ask", "{}", input = toModel)
        }
        assertEquals("ask for login", asked.question)
        val outcome = McpClient.call(
            spec("modern"), emptyList(), entry, "ask", """{"_mcp_input":{"login":{"name":"Bo"}}}""", input = toModel,
        )
        assertEquals("hello Bo", outcome.text)
    }

    @Test
    fun `a sign-in's token is sent unless the user set their own`() {
        val auth = McpAuthState(resource = McpOAuth.canonicalResource("$base/modern"), accessToken = "tok")
        McpClient.connect(spec("modern"), emptyList(), auth)
        assertEquals("Bearer tok", lastAuth)
        McpClient.connect(spec("modern"), listOf(McpHeader("Authorization", "Bearer mine")), auth)
        assertEquals("Bearer mine", lastAuth)
        // A token for another address is not sent here.
        lastAuth = null
        McpClient.connect(spec("modern"), emptyList(), auth.copy(resource = "https://elsewhere.test/mcp"))
        assertEquals(null, lastAuth)
    }

    @Test
    fun `modern server is found by discover and called statelessly`() {
        val entry = McpClient.connect(spec("modern"), emptyList())
        assertEquals(McpEra.MODERN, entry.era)
        assertEquals("Modern", entry.serverName)
        assertEquals(listOf("echo", "rm"), entry.tools.map { it.name })
        assertTrue(entry.tools.first().readOnly)

        val outcome = McpClient.call(spec("modern"), emptyList(), entry, "echo", """{"text":"hi"}""")
        assertEquals("hi", outcome.text)
        assertFalse(outcome.isError)
        assertTrue(seen.contains("tools/call tools/call ${McpProtocol.MODERN_VERSION}"))
        assertTrue(seen.contains("name=echo"))
        assertTrue(NetLog.rows().all { it.source == NetSource.AI_MCP })
    }

    // ---- the legacy Streamable HTTP server ----------------------------------------

    @Volatile private var validSession: String? = null
    private var sessions = 0

    private fun legacy(exchange: HttpExchange) {
        val body = request(exchange)
        val session = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        seen += "${body.method} $session"
        val id = body.id
        when {
            body.method == "initialize" -> {
                sessions++
                validSession = "s$sessions"
                exchange.responseHeaders.add("Mcp-Session-Id", validSession)
                json(
                    exchange,
                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},
                       "serverInfo":{"name":"Legacy","version":"1"}}}""",
                )
            }
            // What the TypeScript SDK answers a request before initialize.
            session == null -> send(
                exchange, 400, "application/json",
                """{"jsonrpc":"2.0","error":{"code":-32000,"message":"Bad Request: No valid session ID provided"},"id":null}""",
            )
            session != validSession -> send(exchange, 404, "text/plain", "Session not found")
            body.method == "notifications/initialized" -> {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            body.method == "tools/list" -> json(exchange, """{"jsonrpc":"2.0","id":$id,"result":{"tools":$tools}}""")
            body.method == "tools/call" -> json(
                exchange,
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"done in $session"}],"isError":true}}""",
            )
            else -> json(exchange, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no"}}""")
        }
    }

    @Test
    fun `legacy server gets initialize, keeps its session, and renews it on 404`() {
        val entry = McpClient.connect(spec("legacy"), emptyList())
        assertEquals(McpEra.LEGACY_HTTP, entry.era)
        assertEquals("2025-06-18", entry.version)
        assertEquals("Legacy", entry.serverName)
        assertEquals(2, entry.tools.size)
        assertTrue(seen.contains("tools/list s1"))

        // The server forgets the session; the call starts a new one and goes through.
        validSession = "gone"
        val outcome = McpClient.call(spec("legacy"), emptyList(), entry, "rm", "{}")
        assertEquals("done in s2", outcome.text)
        assertTrue(outcome.isError)
    }

    // ---- the old HTTP+SSE server ------------------------------------------------

    /** One queue per open GET stream, by the session id its endpoint named. */
    private val sseStreams = java.util.concurrent.ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
    private val sseCount = java.util.concurrent.atomic.AtomicInteger()

    private fun sse(exchange: HttpExchange) {
        if (exchange.requestMethod == "GET") {
            val session = "s" + sseCount.incrementAndGet()
            val queue = LinkedBlockingQueue<String>()
            sseStreams[session] = queue
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("event: endpoint\ndata: /sse/messages?sessionId=$session\n\n".toByteArray())
            out.flush()
            runCatching {
                while (true) {
                    val next = queue.poll(5, TimeUnit.SECONDS) ?: break
                    if (next == CLOSE) break
                    out.write("event: message\ndata: $next\n\n".toByteArray())
                    out.flush()
                }
            }
            runCatching { exchange.close() }
            return
        }
        val body = request(exchange)
        seen += "sse ${body.method}"
        val sseQueue = sseStreams.getValue(exchange.requestURI.query.substringAfter("sessionId="))
        val id = body.id
        when (body.method) {
            "initialize" -> sseQueue.offer(
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"Old","version":"0"}}}""",
            )
            "tools/list" -> sseQueue.offer("""{"jsonrpc":"2.0","id":$id,"result":{"tools":$tools}}""")
            "tools/call" -> sseQueue.offer(
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"old"},{"type":"image","data":"x"}]}}""",
            )
            else -> Unit
        }
        exchange.sendResponseHeaders(202, -1)
        exchange.close()
    }

    @Test
    fun `sse server is reached through its endpoint event`() {
        val entry = McpClient.connect(spec("sse", McpTransport.SSE), emptyList())
        assertEquals(McpEra.SSE, entry.era)
        assertEquals("Old", entry.serverName)
        assertEquals(2, entry.tools.size)
        assertTrue(seen.contains("sse tools/list"))

        val outcome = McpClient.call(spec("sse", McpTransport.SSE), emptyList(), entry, "echo", "{}")
        assertEquals("old", outcome.text)
        assertEquals(listOf("image"), outcome.omitted)
    }

    // ---- failures ---------------------------------------------------------------

    @Test
    fun `a server that wants oauth says so`() {
        val failure = assertThrows(McpException::class.java) { McpClient.connect(spec("oauth"), emptyList()) }
        assertEquals(R.string.ftools_mcp_error_sign_in, failure.messageRes)
    }

    @Test
    fun `an address that is not mcp says so`() {
        val failure = assertThrows(McpException::class.java) { McpClient.connect(spec("nothing"), emptyList()) }
        assertEquals(R.string.ftools_mcp_error_not_mcp, failure.messageRes)
    }

    @Test
    fun `user headers are sent and protocol headers are not overridden`() {
        McpClient.connect(
            spec("modern"),
            listOf(McpHeader("Authorization", "Bearer t"), McpHeader("MCP-Protocol-Version", "1999-01-01")),
        )
        assertTrue(seen.first().endsWith(McpProtocol.MODERN_VERSION))
        assertEquals("Bearer t", lastAuth)
    }

    // ---- plumbing ---------------------------------------------------------------

    private class Request(val method: String, val id: String, val params: JsonObject)

    @Volatile private var lastAuth: String? = null

    private fun request(exchange: HttpExchange): Request {
        exchange.requestHeaders.getFirst("Authorization")?.let { lastAuth = it }
        val root = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
        return Request(
            method = root["method"]?.jsonPrimitive?.content.orEmpty(),
            id = root["id"]?.toString() ?: "null",
            params = root["params"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun json(exchange: HttpExchange, body: String) = send(exchange, 200, "application/json", body)

    private fun send(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val CLOSE = "\u0000close"
    }
}
