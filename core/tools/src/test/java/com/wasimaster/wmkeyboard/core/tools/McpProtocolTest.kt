package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MCP wire format (#573): no network, only what goes in and comes out. */
class McpProtocolTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ---- requests ---------------------------------------------------------

    @Test
    fun `a modern request carries its version in meta`() {
        val body = obj(McpProtocol.request(7, "tools/list", modern = McpProtocol.MODERN_VERSION, clientVersion = "2.0"))
        assertEquals("7", body["id"]!!.jsonPrimitive.content)
        val meta = body["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(McpProtocol.MODERN_VERSION, meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("2.0", meta["io.modelcontextprotocol/clientInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a legacy request has no meta`() {
        val body = obj(McpProtocol.request(1, "tools/list", McpProtocol.listParams("c1")))
        val params = body["params"]!!.jsonObject
        assertFalse(params.containsKey("_meta"))
        assertEquals("c1", params["cursor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `call arguments that are not an object are sent as none`() {
        assertEquals(JsonObject(emptyMap()), McpProtocol.callParams("t", "[1,2]")["arguments"])
        assertEquals(JsonObject(emptyMap()), McpProtocol.callParams("t", "not json")["arguments"])
        assertEquals("x", McpProtocol.callParams("t", """{"q":"x"}""")["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content)
    }

    // ---- answers ----------------------------------------------------------

    @Test
    fun `reply finds its own id and skips the rest`() {
        assertNull(McpProtocol.reply("""{"jsonrpc":"2.0","method":"notifications/progress"}""", 3))
        assertNull(McpProtocol.reply("""{"jsonrpc":"2.0","id":2,"result":{}}""", 3))
        val found = McpProtocol.reply("""{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""", 3)
        assertTrue(found is McpReply.Result)
    }

    @Test
    fun `reply reads a batch and a string id`() {
        val batch = """[{"jsonrpc":"2.0","id":1,"result":{}},{"jsonrpc":"2.0","id":"4","error":{"code":-32602,"message":"bad"}}]"""
        val error = McpProtocol.reply(batch, 4) as McpReply.Error
        assertEquals(-32602, error.code)
        assertEquals("bad", error.message)
    }

    @Test
    fun `unsupported version error lists what the server speaks`() {
        val error = McpProtocol.reply(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"no","data":{"supported":["2025-11-25"],"requested":"x"}}}""",
            1,
        ) as McpReply.Error
        assertTrue(McpProtocol.isModernError(error))
        assertEquals(listOf("2025-11-25"), McpProtocol.supportedVersions(error))
        assertFalse(McpProtocol.isModernError(McpReply.Error(-32601, "", null)))
    }

    @Test
    fun `an error with no id is still read`() {
        val error = McpProtocol.anonymousError("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Not initialized"},"id":null}""")
        assertEquals(-32000, error?.code)
        assertNull(McpProtocol.anonymousError("<html>404</html>"))
    }

    @Test
    fun `server info comes from either generation`() {
        assertEquals("a" to "1", McpProtocol.serverInfo(obj("""{"serverInfo":{"name":"a","version":"1"}}""")))
        assertEquals(
            "b" to "2",
            McpProtocol.serverInfo(obj("""{"_meta":{"io.modelcontextprotocol/serverInfo":{"name":"b","version":"2"}}}""")),
        )
    }

    @Test
    fun `needs input is anything but complete`() {
        assertFalse(McpProtocol.needsInput(obj("""{"content":[]}""")))
        assertFalse(McpProtocol.needsInput(obj("""{"resultType":"complete"}""")))
        assertTrue(McpProtocol.needsInput(obj("""{"resultType":"input_required"}""")))
    }

    @Test
    fun `an input_required result reads its requests and retries with the answers`() {
        val result = obj(
            """{"resultType":"input_required","requestState":"S1","inputRequests":{
                "login":{"method":"elicitation/create","params":{"mode":"form","message":"Your name?",
                  "requestedSchema":{"type":"object","properties":{"name":{"type":"string","title":"Name"},
                  "plan":{"type":"string","enum":["free","pro"]}},"required":["name"]}}},
                "where":{"method":"roots/list"}}}""",
        )
        val requests = McpProtocol.inputRequests(result)
        assertEquals(listOf("login", "where"), requests.map { it.key })
        assertEquals("roots/list", requests[1].method)
        assertEquals("S1", McpProtocol.requestState(result))
        val asked = McpProtocol.elicitation(requests[0].params)
        assertEquals("Your name?", asked.message)
        assertNull(asked.url)
        assertEquals(listOf("name", "plan"), asked.fields.map { it.name })
        assertTrue(asked.fields[0].required)
        assertEquals(listOf("free", "pro"), asked.fields[1].options)
        val base = McpProtocol.callParams("t", """{"a":1}""")
        val retry = McpProtocol.retryParams(base, mapOf("where" to McpProtocol.rootsResult()), "S1")
        assertEquals("S1", retry["requestState"]!!.jsonPrimitive.content)
        assertEquals("""{"roots":[]}""", retry["inputResponses"]!!.jsonObject["where"].toString())
        assertEquals("""{"a":1}""", retry["arguments"].toString())
    }

    @Test
    fun `a url elicitation keeps its page, and the model's answers come back by key`() {
        val asked = McpProtocol.elicitation(obj("""{"mode":"url","message":"Pay","url":"https://pay.test/x"}"""))
        assertEquals("https://pay.test/x", asked.url)
        val args = """{"q":"x","_mcp_input":{"login":{"name":"Ann"},"other":"decline"}}"""
        assertTrue(McpProtocol.hasInput(args))
        assertEquals("""{"q":"x"}""", McpProtocol.withoutInput(args))
        val answers = McpProtocol.inputAnswers(args, listOf("login", "other", "missing"))
        assertEquals("""{"action":"accept","content":{"name":"Ann"}}""", answers["login"].toString())
        assertEquals("""{"action":"decline"}""", answers["other"].toString())
        assertEquals("""{"action":"cancel"}""", answers["missing"].toString())
    }

    @Test
    fun `an old server's request on the stream is read and answered`() {
        val (id, request) = McpProtocol.serverRequest(
            """{"jsonrpc":"2.0","id":"r1","method":"sampling/createMessage","params":{"systemPrompt":"Be short",
               "messages":[{"role":"user","content":{"type":"text","text":"Hi"}}],"maxTokens":50}}""",
        )!!
        assertEquals("sampling/createMessage", request.method)
        val (system, turns) = McpProtocol.samplingTurns(request.params)
        assertEquals("Be short", system)
        assertEquals(listOf(true to "Hi"), turns)
        assertEquals(50, McpProtocol.samplingMaxTokens(request.params))
        val answer = McpProtocol.response(id, McpProtocol.samplingResult("Hello", "m"))
        assertTrue(answer.startsWith("""{"jsonrpc":"2.0","id":"r1","result":{"role":"assistant""""))
        assertNull(McpProtocol.serverRequest("""{"jsonrpc":"2.0","method":"notifications/progress"}"""))
        assertNull(McpProtocol.serverRequest("""{"jsonrpc":"2.0","id":3,"result":{}}"""))
    }

    @Test
    fun `capabilities are declared only when the client answers`() {
        assertEquals("{}", McpProtocol.capabilities(false).toString())
        val caps = McpProtocol.capabilities(true)
        assertEquals(setOf("elicitation", "sampling", "roots"), caps.keys)
        val body = McpProtocol.request(1, "tools/call", modern = "2026-07-28", answersInput = true)
        assertTrue("\"io.modelcontextprotocol/clientCapabilities\":{\"elicitation\"" in body)
    }

    @Test
    fun `endpoint resolves against the stream address`() {
        assertEquals(
            "https://a.test/messages?sessionId=1",
            McpProtocol.endpointUrl("https://a.test/sse", "/messages?sessionId=1"),
        )
        assertNull(McpProtocol.endpointUrl("https://a.test/sse", "file:///etc/passwd"))
    }

    // ---- event streams -------------------------------------------------------

    @Test
    fun `sse reader joins data lines and skips comments`() {
        val reader = McpSseReader()
        val lines = listOf(": ping", "event: message", "data: {\"a\":", "data: 1}", "", "data: two", "")
        val events = lines.mapNotNull(reader::line)
        assertEquals(2, events.size)
        assertEquals(McpSseEvent("message", "{\"a\":\n1}"), events[0])
        assertEquals(McpSseEvent("message", "two"), events[1])
    }

    @Test
    fun `sse reader finishes an event cut by the end of the stream`() {
        val reader = McpSseReader()
        assertNull(reader.line("event: endpoint"))
        assertNull(reader.line("data: /m"))
        assertEquals(McpSseEvent("endpoint", "/m"), reader.end())
        assertNull(reader.end())
    }

    // ---- tools ------------------------------------------------------------

    @Test
    fun `tool page reads annotations and the cursor`() {
        val page = McpProtocol.toolPage(
            obj(
                """{"tools":[
                {"name":"read","title":"Read file","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},
                {"name":"rm","description":"Delete","annotations":{"destructiveHint":true}},
                {"description":"no name"}
                ],"nextCursor":"n2"}""",
            ),
        )
        assertEquals(listOf("read", "rm"), page.tools.map { it.name })
        assertTrue(page.tools[0].readOnly)
        assertEquals("Read file", page.tools[0].label)
        assertTrue(page.tools[1].destructive)
        assertEquals("rm", page.tools[1].label)
        assertEquals("n2", page.nextCursor)
    }

    @Test
    fun `default policy runs read only tools and asks for the rest`() {
        assertEquals(McpToolPolicy.ALLOW, McpToolPolicy.defaultFor(McpTool("a", readOnly = true)))
        assertEquals(McpToolPolicy.ASK, McpToolPolicy.defaultFor(McpTool("b")))
        assertEquals(McpToolPolicy.ASK, McpToolPolicy.defaultFor(McpTool("c", readOnly = true, destructive = true)))
        val server = McpServerSpec("s", toolPolicies = mapOf("b" to McpToolPolicy.OFF))
        assertEquals(McpToolPolicy.OFF, server.policyFor(McpTool("b")))
    }

    @Test
    fun `exposed names are safe, short and unique`() {
        val name = McpProtocol.exposedName("My Files!", "read.file", emptySet())
        assertEquals("My_Files__read_file", name)
        assertTrue(Regex("^[A-Za-z][A-Za-z0-9_-]*$").matches(name))
        val long = McpProtocol.exposedName("s".repeat(50), "t".repeat(80), emptySet())
        assertEquals(McpProtocol.MAX_NAME, long.length)
        val second = McpProtocol.exposedName("My Files!", "read.file", setOf(name))
        assertNotEquals(name, second)
        assertTrue(second.endsWith("_2"))
        assertTrue(McpProtocol.exposedName("9lives", "x", emptySet()).first().isLetter())
        assertTrue(McpProtocol.exposedName("", "", emptySet()).startsWith("mcp__"))
    }

    @Test
    fun `schema gains a type and properties`() {
        val patched = McpProtocol.patchSchema(JsonObject(emptyMap()))
        assertEquals("object", patched["type"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), patched["properties"])
        val kept = McpProtocol.patchSchema(obj("""{"type":"object","properties":{"q":{"type":"string"}},"additionalProperties":false}"""))
        assertTrue(kept.containsKey("additionalProperties"))
        assertTrue(kept["properties"]!!.jsonObject.containsKey("q"))
    }

    @Test
    fun `spec names the server and caps the description`() {
        val spec = McpProtocol.spec("s__t", "Files", McpTool("t", description = "x".repeat(2_000)))
        assertTrue(spec.description.length <= McpProtocol.MAX_DESCRIPTION)
        assertEquals("s__t", spec.name)
        val short = McpProtocol.spec("s__t", "Files", McpTool("t", description = "Reads."))
        assertTrue(short.description.contains("Files"))
    }

    // ---- results ------------------------------------------------------------

    @Test
    fun `outcome joins text and names what it left out`() {
        val outcome = McpProtocol.outcome(
            obj(
                """{"content":[
                {"type":"text","text":"one"},
                {"type":"image","data":"…","mimeType":"image/png"},
                {"type":"resource","resource":{"uri":"x","text":"two"}},
                {"type":"resource","resource":{"uri":"y","blob":"…"}}
                ]}""",
            ),
        )
        assertEquals("one\n\ntwo", outcome.text)
        assertEquals(listOf("image", "resource"), outcome.omitted)
        assertFalse(outcome.isError)
        assertFalse(outcome.truncated)
    }

    @Test
    fun `outcome falls back to structured content, cuts, and reads isError`() {
        val structured = McpProtocol.outcome(obj("""{"content":[],"structuredContent":{"t":21}}"""))
        assertEquals("""{"t":21}""", structured.text)
        val long = McpProtocol.outcome(obj("""{"content":[{"type":"text","text":"${"a".repeat(50)}"}],"isError":true}"""), maxChars = 10)
        assertEquals(10, long.text.length)
        assertTrue(long.truncated)
        assertTrue(long.isError)
    }

    @Test
    fun `preview is one short line and pretty is indented`() {
        assertEquals("q: cats, n: 3", McpProtocol.preview("""{"q":"cats","n":3}"""))
        assertEquals("", McpProtocol.preview("{}"))
        assertTrue(McpProtocol.preview("""{"q":"${"x".repeat(200)}"}""").length <= 80)
        assertTrue(McpProtocol.pretty("""{"q":"cats"}""").contains("\n"))
    }

    // ---- stored records -----------------------------------------------------

    @Test
    fun `a tool record stored before MCP still reads`() {
        val old = """{"tool":"web_search","target":"cats","sources":[{"title":"t","url":"https://a.test"}]}"""
        val record = Json.decodeFromString(AiToolActivity.serializer(), old)
        assertEquals("", record.server)
        assertFalse(record.isMcp)
        assertFalse(record.awaitingApproval)
    }

    @Test
    fun `settle clears a question the run ended on`() {
        val asking = AiToolActivity("t", running = true, server = "S", awaitingApproval = true, details = "{}")
        val settled = AiToolActivity.settle(listOf(asking), "stopped").single()
        assertFalse(settled.running)
        assertFalse(settled.awaitingApproval)
        assertEquals("", settled.details)
        assertEquals("stopped", settled.error)
    }

    @Test
    fun `a result keeps how its call was shown`() {
        val call = AiToolCall("1", "s__t", "{}")
        val shown = AiToolActivity("Tool", running = true, server = "S", awaitingApproval = true, details = "x")
        val activity = AiToolResult(call, "ok", display = shown).activity
        assertEquals("Tool", activity.tool)
        assertEquals("S", activity.server)
        assertFalse(activity.running)
        assertFalse(activity.awaitingApproval)
        assertEquals("", activity.details)
    }

    // ---- stored settings ----------------------------------------------------

    @Test
    fun `servers and headers round trip, and junk reads as nothing`() {
        val servers = listOf(
            McpServerSpec("mcp_1", "A", "https://a.test/mcp", McpTransport.SSE, toolPolicies = mapOf("t" to McpToolPolicy.OFF)),
        )
        assertEquals(servers, McpServerCodec.decodeServers(McpServerCodec.encodeServers(servers)))
        val headers = mapOf("mcp_1" to listOf(McpHeader("Authorization", "Bearer x")), "mcp_2" to emptyList())
        assertEquals(mapOf("mcp_1" to headers["mcp_1"]), McpServerCodec.decodeHeaders(McpServerCodec.encodeHeaders(headers)))
        assertEquals(emptyList<McpServerSpec>(), McpServerCodec.decodeServers(""))
        assertEquals(emptyList<McpServerSpec>(), McpServerCodec.decodeServers("{"))
    }

    @Test
    fun `a transport from a newer version falls back to auto`() {
        val read = McpServerCodec.decodeServers("""[{"id":"x","transport":"WEBSOCKET"}]""")
        assertEquals(McpTransport.AUTO, read.single().transport)
    }
}
