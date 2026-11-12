package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentinel protocol, and the loop that drives rounds to an answer. */
class AiToolLoopTest {

    private val tools = listOf(AiTools.webSearch, AiTools.webFetch)

    // ---- the sentinel ----------------------------------------------------

    @Test
    fun `a sentinel is read and taken out of the text`() {
        val text = "Sure. <tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"tides\"}}</tool_call>"
        val call = AiToolProtocol.parseAll(text).single()
        assertEquals(AiTools.WEB_SEARCH, call.name)
        assertEquals("tides", call.argument("query"))
        assertEquals("Sure.", AiToolProtocol.stripped(text))
    }

    @Test
    fun `a sentinel cut across chunks never reaches the panel`() {
        val filter = AiToolProtocol.Filter()
        val shown = StringBuilder()
        for (chunk in listOf("Looking", " that up. <too", "l_call>{\"name\":\"web_", "search\"}</tool_", "call> done")) {
            shown.append(filter.feed(chunk))
        }
        shown.append(filter.flush())
        // Not one angle bracket of the sentinel was ever handed out.
        assertFalse('<' in shown.toString())
        assertEquals("Looking that up.  done", shown.toString())
        assertEquals(AiTools.WEB_SEARCH, filter.calls.single().name)
    }

    @Test
    fun `a half-written sentinel is held back until it is settled`() {
        val filter = AiToolProtocol.Filter()
        assertEquals("hi ", filter.feed("hi <tool_c"))
        // Not a sentinel after all: released as the text it turned out to be.
        assertEquals("<tool_crab", filter.feed("rab"))
    }

    @Test
    fun `a sentinel the model ran out of room for is still read`() {
        val filter = AiToolProtocol.Filter()
        filter.feed("<tool_call>{\"name\":\"web_fetch\",\"arguments\":{\"url\":\"https://a.test\"}}")
        filter.flush()
        assertEquals("https://a.test", filter.calls.single().argument("url"))
    }

    @Test
    fun `arguments written as a quoted string are read too`() {
        val call = AiToolProtocol.parseAll(
            """<tool_call>{"name":"web_search","arguments":"{\"query\":\"x\"}"}</tool_call>""",
        ).single()
        assertEquals("x", call.argument("query"))
    }

    // The shapes a real local model produced. Gemma 4 E2B wrote both of these
    // verbatim on device when asked to search: `tool_call` is a special token
    // in its chat template, so the template mangles the exact string the
    // instructions ask for. Matching only that string meant neither parsed and
    // neither was hidden, which put raw JSON in the answer bubble.

    @Test
    fun `a marker with no brackets at all is read`() {
        val text = """TOOL_CALL{{"name":"web_search","arguments":{"query":"latest tech news"}}}<tool_call|>"""
        val call = AiToolProtocol.parseAll(text).single()
        assertEquals(AiTools.WEB_SEARCH, call.name)
        assertEquals("latest tech news", call.argument("query"))
        assertEquals("", AiToolProtocol.stripped(text))
    }

    @Test
    fun `pipes on either side, a repeated marker and a label are all skipped`() {
        val text = """<|tool_call>call:tool_call{{"name":"web_fetch","arguments":""" +
            """{"url":"https://a.test/x"}}}<tool_call|>"""
        val call = AiToolProtocol.parseAll(text).single()
        assertEquals(AiTools.WEB_FETCH, call.name)
        assertEquals("https://a.test/x", call.argument("url"))
        assertEquals("", AiToolProtocol.stripped(text))
    }

    @Test
    fun `a marker that does not parse is still hidden`() {
        // The worst outcome is not a missed call, it is a user reading JSON.
        val text = """Here you go. <|tool_call|>{"nmae":"web_search"} and that is all."""
        assertTrue(AiToolProtocol.parseAll(text).isEmpty())
        val shown = AiToolProtocol.stripped(text)
        assertFalse("tool_call" in shown)
        assertFalse("nmae" in shown)
        assertTrue(shown.startsWith("Here you go."))
        assertTrue(shown.endsWith("and that is all."))
    }

    @Test
    fun `a bare marker with prose after it loses only the marker`() {
        assertEquals(
            "I will look that up.",
            AiToolProtocol.stripped("<tool_call>I will look that up."),
        )
    }

    @Test
    fun `a brace inside a query cannot end the object early`() {
        val call = AiToolProtocol.parseAll(
            """<tool_call>{"name":"web_search","arguments":{"query":"what does {x} mean"}}</tool_call>""",
        ).single()
        assertEquals("what does {x} mean", call.argument("query"))
    }

    @Test
    fun `the words tool call in a sentence are left alone`() {
        // The pattern ignores case, so matching the spaced form unbracketed
        // would delete these words out of an ordinary answer.
        val text = "You can make a tool call here, and TOOL CALL is the same thing."
        assertTrue(AiToolProtocol.parseAll(text).isEmpty())
        assertEquals(text, AiToolProtocol.stripped(text))
    }

    @Test
    fun `a word that starts like the marker is held one chunk, then released`() {
        val filter = AiToolProtocol.Filter()
        // "to" could still grow into "tool_call", so it waits rather than
        // being shown and then taken back.
        assertEquals("I am going ", filter.feed("I am going to"))
        assertEquals("to town.", filter.feed(" town.") + filter.flush())
    }

    @Test
    fun `an ordinary answer has no calls and is left alone`() {
        val text = "Use a < b to compare them."
        assertTrue(AiToolProtocol.parseAll(text).isEmpty())
        assertEquals(text, AiToolProtocol.stripped(text))
    }

    @Test
    fun `the instructions name every tool and its arguments`() {
        val text = AiToolProtocol.instructions(tools)
        assertTrue("web_search(query)" in text)
        assertTrue("web_fetch(url)" in text)
        assertEquals("", AiToolProtocol.instructions(emptyList()))
    }

    // ---- the loop --------------------------------------------------------

    /** A round that answers from a script, recording what it was asked. */
    private class Script(vararg replies: AiClient.Completion) : AiToolLoop.Round {
        private val replies = replies.toList()
        val systems = ArrayList<String>()
        val offered = ArrayList<Int>()
        var calls = 0
            private set

        override fun run(
            system: String,
            turns: List<AiClient.ChatTurn>,
            tools: List<AiToolSpec>,
            onPartial: (String) -> Unit,
        ): AiClient.Completion {
            systems.add(system)
            offered.add(tools.size)
            return replies[calls++.coerceAtMost(replies.lastIndex)]
        }
    }

    private val echo = AiToolLoop.Executor { call -> AiToolResult(call, "result for ${call.name}") }

    @Test
    fun `a tool the round did not offer never reaches the executor`() {
        // #825: an on-device run offers no web tool, and a sentinel naming one
        // must not run it.
        val ran = ArrayList<String>()
        val executor = AiToolLoop.Executor { call -> ran.add(call.name); AiToolResult(call, "ok") }
        val results = ArrayList<AiToolResult>()
        for (native in listOf(false, true)) {
            val fetch = AiToolCall("c", AiTools.WEB_FETCH, """{"url":"https://a.test"}""")
            val script = Script(
                if (native) {
                    AiClient.Completion("", toolCalls = listOf(fetch))
                } else {
                    AiClient.Completion("""<tool_call>{"name":"web_fetch","arguments":{"url":"https://a.test"}}</tool_call>""")
                },
                AiClient.Completion("done"),
            )
            AiToolLoop.run(
                system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
                tools = listOf(AiTools.webSearch), native = native, executor = executor,
                onToolResult = results::add, round = script,
            )
        }
        assertTrue(ran.isEmpty())
        assertEquals(2, results.size)
        assertTrue(results.all { it.error.isNotEmpty() })
    }

    @Test
    fun `a tool the round offered still runs`() {
        val ran = ArrayList<String>()
        val executor = AiToolLoop.Executor { call -> ran.add(call.name); AiToolResult(call, "ok") }
        val script = Script(
            AiClient.Completion("""<tool_call>{"name":"web_search","arguments":{"query":"q"}}</tool_call>"""),
            AiClient.Completion("done"),
        )
        AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = listOf(AiTools.webSearch), native = false, executor = executor, round = script,
        )
        assertEquals(listOf(AiTools.WEB_SEARCH), ran)
    }

    @Test
    fun `no tools runs exactly one round and changes nothing`() {
        val script = Script(AiClient.Completion("plain"))
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = emptyList(), native = true, executor = echo, round = script,
        )
        assertEquals("plain", out.text)
        assertEquals(1, script.calls)
        assertEquals(listOf("S"), script.systems)
    }

    @Test
    fun `a native call runs the tool and the next round answers`() {
        val call = AiToolCall("c1", AiTools.WEB_SEARCH, """{"query":"q"}""")
        val script = Script(
            AiClient.Completion("Looking.", toolCalls = listOf(call)),
            AiClient.Completion("It rained."),
        )
        val seen = ArrayList<AiToolCall>()
        val phases = ArrayList<AiPhase>()
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true, executor = echo,
            onPhase = phases::add, onToolCall = seen::add, round = script,
        )
        assertEquals("Looking.\n\nIt rained.", out.text)
        assertEquals(2, script.calls)
        assertEquals(listOf(call), seen)
        assertTrue(AiPhase.USING_TOOL in phases)
        // The run's own calls are not handed on: they were already answered.
        assertTrue(out.toolCalls.isEmpty())
    }

    @Test
    fun `the sentinel path is told the protocol and the text is cleaned`() {
        val script = Script(
            AiClient.Completion("""<tool_call>{"name":"web_search","arguments":{"query":"q"}}</tool_call>"""),
            AiClient.Completion("It rained."),
        )
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = false, executor = echo, round = script,
        )
        assertEquals("It rained.", out.text)
        assertTrue(script.systems.first().startsWith("S"))
        assertTrue("web_search(query)" in script.systems.first())
    }

    @Test
    fun `the last round is offered no tools, so a loop still answers`() {
        val call = AiToolCall("c", AiTools.WEB_SEARCH, "{}")
        // A model that would ask forever.
        val script = Script(AiClient.Completion("hm", toolCalls = listOf(call)))
        AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true, executor = echo, maxRounds = 2, round = script,
        )
        // Two rounds with tools, then one without — and then it stops.
        assertEquals(listOf(2, 2, 0), script.offered)
    }

    @Test
    fun `the sentinel path drops the protocol on its final round`() {
        val call = """<tool_call>{"name":"web_search","arguments":{}}</tool_call>"""
        val script = Script(AiClient.Completion(call))
        AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = false, executor = echo, maxRounds = 1, round = script,
        )
        assertTrue("web_search(query)" in script.systems.first())
        assertEquals("S", script.systems.last())
    }

    @Test
    fun `a retired run stops after the round it was retired in`() {
        val call = AiToolCall("c", AiTools.WEB_SEARCH, "{}")
        val script = Script(AiClient.Completion("x", toolCalls = listOf(call)))
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true, executor = echo,
            isActive = { false }, round = script,
        )
        assertEquals(1, script.calls)
        assertEquals("x", out.text)
    }

    @Test
    fun `partials from a later round carry the earlier ones with them`() {
        val call = AiToolCall("c", AiTools.WEB_SEARCH, "{}")
        val rounds = object : AiToolLoop.Round {
            var n = 0
            override fun run(
                system: String,
                turns: List<AiClient.ChatTurn>,
                tools: List<AiToolSpec>,
                onPartial: (String) -> Unit,
            ): AiClient.Completion {
                n++
                return if (n == 1) {
                    onPartial("Looking.")
                    AiClient.Completion("Looking.", toolCalls = listOf(call))
                } else {
                    onPartial("It rained.")
                    AiClient.Completion("It rained.")
                }
            }
        }
        val partials = ArrayList<String>()
        AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true, executor = echo,
            onPartial = partials::add, round = rounds,
        )
        // The panel must never appear to restart part-way through a run.
        assertEquals(listOf("Looking.", "Looking.\n\nIt rained."), partials)
    }

    // ---- #1281: calls a weak model wrote wrong ---------------------------

    @Test
    fun `a call that would not read is reported, and the model gets another go`() {
        val turnsSeen = ArrayList<List<AiClient.ChatTurn>>()
        val replies = listOf(
            AiClient.Completion("""Done! <tool_call>{"name":"web_search","arguments":{"query": }</tool_call>"""),
            AiClient.Completion("""<tool_call>{"name":"web_search","arguments":{"query":"rain"}}</tool_call>"""),
            AiClient.Completion("It rained."),
        )
        val round = AiToolLoop.Round { _, turns, _, _ ->
            turnsSeen.add(turns.toList())
            replies[turnsSeen.size - 1]
        }
        val ran = ArrayList<AiToolCall>()
        val results = ArrayList<AiToolResult>()
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = false,
            executor = { call -> ran.add(call); AiToolResult(call, "ok") },
            onToolResult = results::add, round = round,
        )
        // Nothing ran for the bad call; the good one on the next round did.
        assertEquals(listOf("rain"), ran.map { it.argument("query") })
        assertEquals(AiToolLoop.NOT_RUN_UNREADABLE, results.first().error)
        assertEquals(AiTools.WEB_SEARCH, results.first().call.name)
        // The model was told, in the turn after its bad call.
        assertTrue(AiToolProtocol.UNREADABLE_REPLY in turnsSeen[1].last().text)
        assertEquals("Done!\n\nIt rained.", out.text)
    }

    @Test
    fun `a call written on the last round is recorded as not run`() {
        val script = Script(
            AiClient.Completion("""<tool_call>{"name":"web_search","arguments":{"query":"a"}}</tool_call>"""),
            AiClient.Completion("""Set! call:web_search{query:"b"}"""),
        )
        val ran = ArrayList<String>()
        val results = ArrayList<AiToolResult>()
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = false, maxRounds = 1,
            executor = { call -> ran.add(call.argument("query")!!); AiToolResult(call, "ok") },
            onToolResult = results::add, round = script,
        )
        assertEquals(listOf("a"), ran)
        assertEquals(AiToolLoop.NOT_RUN_OUT_OF_ROUNDS, results.last().error)
        assertEquals("Set!", out.text)
    }

    @Test
    fun `a native model that writes its call as text still runs it`() {
        val script = Script(
            AiClient.Completion("""{"name": "web_search", "arguments": {"query": "rain"}}"""),
            AiClient.Completion("It rained."),
        )
        val ran = ArrayList<AiToolCall>()
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true,
            executor = { call -> ran.add(call); AiToolResult(call, "ok") }, round = script,
        )
        val call = ran.single()
        assertEquals("rain", call.argument("query"))
        // An id of its own, so the tool turn has something to answer.
        assertTrue(call.id.isNotEmpty())
        assertEquals("It rained.", out.text)
    }

    @Test
    fun `a native answer that only shows json keeps it`() {
        val text = """Here: {"name": "Alice"}"""
        val script = Script(AiClient.Completion(text))
        val out = AiToolLoop.run(
            system = "S", turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "q")),
            tools = tools, native = true, executor = echo, round = script,
        )
        assertEquals(text, out.text)
        assertEquals(1, script.calls)
    }
}
