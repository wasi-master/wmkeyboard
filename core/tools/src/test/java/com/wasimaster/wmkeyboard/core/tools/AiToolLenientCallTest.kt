package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool calls as weak models really write them (#1281): every shape here must
 * run, and none of it may reach the answer the user reads.
 */
class AiToolLenientCallTest {

    private fun spec(name: String) = AiTools.builtIn(name)!!.spec

    private val tools = listOf(
        AiTools.webSearch,
        AiTools.webFetch,
        spec(AiTools.SET_ALARM),
        spec(AiTools.CONVERT_UNITS),
        spec(AiTools.TRANSLATE),
        spec(AiTools.OPEN_APP),
    )

    private fun one(text: String): AiToolCall = AiToolProtocol.parseAll(text, tools).single()

    private fun shown(text: String): String = AiToolProtocol.stripped(text, tools)

    // ---- the screenshot -----------------------------------------------------

    @Test
    fun `gemma's own call shape with no marker and bare keys runs`() {
        // Verbatim from the issue's screenshot: Gemma 4 E2B on device.
        val text = """call:set_alarm{time:"05:00",label:"Tomorrow's Alarm",days:1}"""
        val call = one(text)
        assertEquals(AiTools.SET_ALARM, call.name)
        assertEquals("05:00", call.argument("time"))
        assertEquals("Tomorrow's Alarm", call.argument("label"))
        assertEquals("1", call.argument("days"))
        assertEquals("", shown(text))
    }

    @Test
    fun `gemma's quote token and markers are read too`() {
        val text = """<|tool_call>call:set_alarm{time:<|"|>07:30<|"|>,label:<|"|>Gym<|"|>}<tool_call|>"""
        val call = one(text)
        assertEquals("07:30", call.argument("time"))
        assertEquals("Gym", call.argument("label"))
        assertEquals("", shown(text))
    }

    @Test
    fun `a call label with prose after it stays prose`() {
        val text = "Next call: Mum (later today), then the bank."
        assertTrue(AiToolProtocol.parseAll(text, tools).isEmpty())
        assertEquals(text, shown(text))
    }

    // ---- the shapes the issue asked for -------------------------------------

    @Test
    fun `json in a code fence runs and the fence is hidden`() {
        val text = "Let me check.\n```json\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"rain\"}}\n```"
        assertEquals("rain", one(text).argument("query"))
        assertEquals("Let me check.", shown(text))
    }

    @Test
    fun `a code fence holding anything else is left alone`() {
        val text = "Use this:\n```kotlin\nval x = mapOf(\"name\" to 1)\n```"
        assertTrue(AiToolProtocol.parseAll(text, tools).isEmpty())
        assertEquals(text, shown(text))
    }

    @Test
    fun `single quotes are read`() {
        val call = one("""<tool_call>{'name': 'web_search', 'arguments': {'query': "what's new"}}</tool_call>""")
        assertEquals(AiTools.WEB_SEARCH, call.name)
        assertEquals("what's new", call.argument("query"))
    }

    @Test
    fun `trailing commas are read`() {
        val call = one("""<tool_call>{"name":"web_search","arguments":{"query":"rain",},}</tool_call>""")
        assertEquals("rain", call.argument("query"))
    }

    @Test
    fun `a name and arguments pair as plain text runs`() {
        val text = """Sure. {"name": "web_search", "arguments": {"query": "rain"}}"""
        assertEquals("rain", one(text).argument("query"))
        assertEquals("Sure.", shown(text))
    }

    @Test
    fun `plain json that names no offered tool stays in the answer`() {
        val text = """Here it is: {"name": "Alice", "age": 30}"""
        assertTrue(AiToolProtocol.parseAll(text, tools).isEmpty())
        assertEquals(text, shown(text))
        // Without the run's tools, unmarked JSON is never a call.
        val call = """{"name": "web_search", "arguments": {"query": "rain"}}"""
        assertTrue(AiToolProtocol.parseAll(call).isEmpty())
    }

    @Test
    fun `a function-style call with keywords runs`() {
        val text = """I'll look. web_search(query="rain in Dhaka")"""
        assertEquals("rain in Dhaka", one(text).argument("query"))
        assertEquals("I'll look.", shown(text))
        val alarm = one("set_alarm(time='06:15', label='Run')")
        assertEquals("06:15", alarm.argument("time"))
        assertEquals("Run", alarm.argument("label"))
        assertEquals("https://a.test/x", one("web_fetch(url: https://a.test/x)").argument("url"))
    }

    @Test
    fun `a function-style call with positional arguments follows the tool's parameters`() {
        val call = one("""convert_units(5, "km", "mi")""")
        assertEquals(5.0, call.number("value")!!, 0.0)
        assertEquals("km", call.argument("from"))
        assertEquals("mi", call.argument("to"))
        assertEquals("https://b.test", one("""web_fetch("https://b.test")""").argument("url"))
    }

    @Test
    fun `a tool's name in prose is not a call`() {
        val text = "I can translate that (it is short), or web_search it."
        assertTrue(AiToolProtocol.parseAll(text, tools).isEmpty())
        assertEquals(text, shown(text))
    }

    @Test
    fun `tool_call tags around pretty-printed json run`() {
        val text = "<tool_call>\n{\n  \"name\": \"web_search\",\n  \"arguments\": {\"query\": \"rain\"}\n}\n</tool_call>"
        assertEquals("rain", one(text).argument("query"))
        assertEquals("", shown(text))
    }

    @Test
    fun `arguments as a string are read whatever the string holds`() {
        assertEquals("x", one("""<tool_call>{"name":"web_search","arguments":"{'query': 'x'}"}</tool_call>""").argument("query"))
        // Not JSON at all: the whole string is the tool's first argument.
        assertEquals(
            "rain tomorrow",
            one("""<tool_call>{"name":"web_search","arguments":"rain tomorrow"}</tool_call>""").argument("query"),
        )
    }

    @Test
    fun `arguments beside the name rather than under it are read`() {
        assertEquals("rain", one("""<tool_call>{"name":"web_search","query":"rain"}</tool_call>""").argument("query"))
    }

    @Test
    fun `an argument called name is not taken for the tool`() {
        val call = one("""<tool_call>{"name":"open_app","arguments":{"name":"Spotify"}}</tool_call>""")
        assertEquals(AiTools.OPEN_APP, call.name)
        assertEquals("Spotify", call.argument("name"))
        val bare = one("""call:open_app{name:"Spotify"}""")
        assertEquals(AiTools.OPEN_APP, bare.name)
        assertEquals("Spotify", bare.argument("name"))
    }

    // ---- other models' own wrappers -----------------------------------------

    @Test
    fun `openai's function wrapper is read`() {
        val call = one("""{"type":"function","function":{"name":"web_search","arguments":"{\"query\":\"x\"}"}}""")
        assertEquals("x", call.argument("query"))
    }

    @Test
    fun `mistral's list of calls is read whole`() {
        val text = """[TOOL_CALLS][{"name":"web_search","arguments":{"query":"a"}},""" +
            """{"name":"web_fetch","arguments":{"url":"https://b.test"}}]"""
        val calls = AiToolProtocol.parseAll(text, tools)
        assertEquals(listOf(AiTools.WEB_SEARCH, AiTools.WEB_FETCH), calls.map { it.name })
        assertEquals("", shown(text))
    }

    @Test
    fun `langchain's action and action_input are read`() {
        assertEquals("rain", one("""{"action": "web_search", "action_input": "rain"}""").argument("query"))
    }

    @Test
    fun `llama's function tag is read`() {
        val text = """<function=web_search>{"query": "rain"}</function>"""
        assertEquals("rain", one(text).argument("query"))
        assertEquals("", shown(text))
    }

    @Test
    fun `a namespaced or misspelt name finds its tool`() {
        assertEquals(AiTools.WEB_SEARCH, one("""<tool_call>{"name":"functions.web_search","arguments":{}}</tool_call>""").name)
        assertEquals(AiTools.WEB_SEARCH, one("""<tool_call>{"name":"Web-Search","arguments":{}}</tool_call>""").name)
    }

    @Test
    fun `an unescaped quote inside a value is kept`() {
        val call = one("""<tool_call>{"name":"web_search","arguments":{"query":"the "best" pizza"}}</tool_call>""")
        assertEquals("the \"best\" pizza", call.argument("query"))
    }

    // ---- what did not read ----------------------------------------------------

    @Test
    fun `a marked call that will not read is hidden and kept as unreadable`() {
        val reading = AiToolProtocol.read("""Done! <tool_call>{"name": "web_search", "arguments": {"query": }</tool_call>""", tools)
        assertTrue(reading.calls.isEmpty())
        assertEquals("Done!", reading.text)
        val bad = reading.unreadable.single()
        assertEquals(AiTools.WEB_SEARCH, bad.name)
        assertTrue("query" in bad.text)
    }

    @Test
    fun `a marker with prose after it is not unreadable`() {
        val reading = AiToolProtocol.read("<tool_call>I will look that up.", tools)
        assertTrue(reading.unreadable.isEmpty())
        assertEquals("I will look that up.", reading.text)
    }

    // ---- streaming ----------------------------------------------------------

    @Test
    fun `a bare gemma call cut across chunks never reaches the panel`() {
        val filter = AiToolProtocol.Filter(tools)
        val out = StringBuilder()
        for (chunk in listOf("Setting it. ca", "ll:set_al", "arm{time:\"05", ":00\"}", " Done")) {
            out.append(filter.feed(chunk))
        }
        out.append(filter.flush())
        assertEquals("Setting it.  Done", out.toString())
        assertEquals("05:00", filter.calls.single().argument("time"))
    }

    @Test
    fun `a function-style call cut across chunks never reaches the panel`() {
        val filter = AiToolProtocol.Filter(tools)
        val out = StringBuilder()
        for (chunk in listOf("Ok. web_sea", "rch(query='x", "')")) out.append(filter.feed(chunk))
        out.append(filter.flush())
        assertEquals("Ok. ", out.toString())
        assertEquals("x", filter.calls.single().argument("query"))
    }

    @Test
    fun `a word that starts like a tool is held one chunk, then released`() {
        val filter = AiToolProtocol.Filter(tools)
        assertEquals("I will ", filter.feed("I will web"))
        assertEquals("website it.", filter.feed("site it.") + filter.flush())
    }

    // ---- the json reader ----------------------------------------------------

    @Test
    fun `lenient json takes python literals, bare keys and stray commas`() {
        val obj = AiLenientJson.parse("{a: True, b: None, c: 'x', d: 2.5, e: 05,}") as JsonObject
        assertEquals(JsonPrimitive(true), obj["a"])
        assertEquals(JsonNull, obj["b"])
        assertEquals("x", (obj["c"] as JsonPrimitive).content)
        assertEquals(2.5, (obj["d"] as JsonPrimitive).content.toDouble(), 0.0)
        // Not JSON's number grammar, so it stays the string it was written as.
        assertEquals(JsonPrimitive("05"), obj["e"])
    }

    @Test
    fun `well-formed json comes back exactly as the real parser reads it`() {
        val text = """{"q":"a\nb","n":[1,2,{"x":null}]}"""
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(text), AiLenientJson.parse(text))
    }
}
