package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deep research's steps, bounds and citations, with a scripted model and web. */
class AiResearchTest {

    private class FakeWeb(private val failFetch: Set<String> = emptySet()) : AiResearch.Web {
        val searches = ArrayList<String>()
        val fetches = ArrayList<String>()

        override fun search(query: String): List<AiResearch.Hit> {
            searches += query
            val slug = query.replace(' ', '-')
            return (1..5).map { AiResearch.Hit("$query result $it", "https://site$it.example/$slug", "snippet $it for $query") }
        }

        override fun fetch(url: String, maxChars: Int): AiResearch.Page {
            fetches += url
            if (url in failFetch) error("HTTP 403")
            return AiResearch.Page("Page $url", ("Text of $url. ").repeat(40).take(maxChars))
        }
    }

    /** Answers the plan, the look-again and the report by which prompt it is handed. */
    private class FakeModel(
        private val followUps: String = "DONE",
        private val report: String = "Answer [1] and [3].",
        private val plan: String = "1. first query\n- \"second query\"\nthird query\nfourth query\nfifth query\nsixth\nseventh",
    ) : AiResearch.Model {
        val calls = ArrayList<String>()

        override fun complete(system: String, user: String, stream: Boolean, onPartial: (String) -> Unit): String {
            calls += system.take(20)
            return when {
                system.startsWith("You plan") -> plan
                system.startsWith("You check") -> followUps
                else -> report.also(onPartial)
            }
        }
    }

    private class Steps : AiResearch.Listener {
        val started = ArrayList<AiToolActivity>()
        val finished = ArrayList<AiToolActivity>()
        override fun started(step: AiToolActivity) { started += step }
        override fun finished(step: AiToolActivity) { finished += step }
    }

    @Test
    fun `a quick run plans, searches each query, reads the pages it may and cites what it read`() {
        val web = FakeWeb()
        val steps = Steps()
        val limits = AiResearch.limits(AiResearchDepth.QUICK)
        val report = AiResearch(limits, FakeModel(), web, steps).run("What is new?", AiAnswerFormat.AUTO, "Today is X.")

        assertEquals(listOf("first query", "second query", "third query"), web.searches)
        assertEquals(limits.pages, web.fetches.size)
        // One round only: no look-again on the quickest depth.
        assertTrue(steps.started.none { it.tool == AiTools.RESEARCH_REVIEW })
        assertEquals(steps.started.size, steps.finished.size)
        assertTrue(steps.finished.all { !it.running })
        assertTrue(report.startsWith("Answer [1] and [3]."))
        assertTrue(report.contains("## Sources"))
        assertTrue(report.contains("- [1] ["))
        assertTrue(report.contains("- [3] ["))
        assertFalse(report.contains("- [2] ["))
    }

    @Test
    fun `each sub-question reads its own best results, two pages a site at most`() {
        val web = FakeWeb()
        AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(), web).run("Q", AiAnswerFormat.AUTO, "")
        // Four pages over three sub-questions: two for the first, one each after,
        // and site1 is full by the third.
        assertEquals(
            listOf(
                "https://site1.example/first-query",
                "https://site2.example/first-query",
                "https://site1.example/second-query",
                "https://site2.example/third-query",
            ),
            web.fetches,
        )
    }

    @Test
    fun `a standard run looks again and searches only what is new`() {
        val web = FakeWeb()
        val model = FakeModel(followUps = "first query\nmissing piece")
        AiResearch(AiResearch.limits(AiResearchDepth.STANDARD), model, web).run("Q", AiAnswerFormat.AUTO, "")
        assertEquals(listOf("first query", "second query", "third query", "fourth query", "missing piece"), web.searches)
        assertEquals(AiResearchDepth.STANDARD.pages, web.fetches.size)
    }

    @Test
    fun `DONE ends the gathering early`() {
        val web = FakeWeb()
        AiResearch(AiResearch.limits(AiResearchDepth.DEEP), FakeModel(followUps = "DONE"), web).run("Q", AiAnswerFormat.AUTO, "")
        // Six sub-questions of one search each, from the seven lines written.
        assertEquals(AiResearchDepth.DEEP.questions, web.searches.size)
        // Only the first round's share of the pages.
        assertEquals(5, web.fetches.size)
    }

    @Test
    fun `a page that cannot be read fails its step and the run goes on`() {
        val web = FakeWeb(failFetch = setOf("https://site1.example/first-query"))
        val steps = Steps()
        val report = AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(report = "No cites."), web, steps)
            .run("Q", AiAnswerFormat.AUTO, "")
        assertEquals("HTTP 403", steps.finished.first { it.tool == AiTools.WEB_FETCH }.error)
        // Nothing cited: every page that was read is listed.
        assertEquals(3, Regex("""- \[\d+] \[""").findAll(report).count())
    }

    @Test
    fun `stop between steps ends the run with nothing`() {
        val web = FakeWeb()
        var steps = 0
        val listener = object : AiResearch.Listener {
            override fun started(step: AiToolActivity) { steps++ }
        }
        val report = AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(), web, listener) { steps < 2 }
            .run("Q", AiAnswerFormat.AUTO, "")
        assertEquals("", report)
        assertTrue(web.fetches.isEmpty())
    }

    @Test(expected = AiResearch.NothingFoundException::class)
    fun `no results at all is a failure, not an empty report`() {
        val web = object : AiResearch.Web {
            override fun search(query: String): List<AiResearch.Hit> = emptyList()
            override fun fetch(url: String, maxChars: Int) = error("never")
        }
        AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(), web).run("Q", AiAnswerFormat.AUTO, "")
    }

    @Test
    fun `data saver halves the pages`() {
        assertEquals(4, AiResearch.limits(AiResearchDepth.STANDARD, dataSaver = true).pages)
        assertEquals(2, AiResearch.limits(AiResearchDepth.QUICK, dataSaver = true).pages)
    }

    @Test
    fun `a phone model's window shrinks the page text to fit`() {
        val cloud = AiResearch.limits(AiResearchDepth.STANDARD)
        val phone = AiResearch.limits(AiResearchDepth.STANDARD, contextTokens = 4096)
        assertEquals(AiResearch.SOURCE_CHARS, cloud.sourceChars)
        assertTrue(phone.sourceChars < 4096 * 2)
        assertTrue(phone.pages in 2 until cloud.pages)
        assertTrue(phone.maxSources < cloud.maxSources)
        assertTrue(phone.pageChars <= phone.sourceChars / 2)
        // Plan and look-again keep their shape: only the reading shrinks.
        assertEquals(cloud.questions, phone.questions)
        assertEquals(cloud.rounds, phone.rounds)
        // A big window is treated like a server's.
        assertEquals(cloud.pages, AiResearch.limits(AiResearchDepth.STANDARD, contextTokens = 32_768).pages)
    }

    @Test
    fun `a phone model gets no more sources than its limits allow`() {
        val web = FakeWeb()
        var reportInput = ""
        val model = AiResearch.Model { system, user, stream, _ ->
            if (stream) reportInput = user
            FakeModel().complete(system, user, stream) {}
        }
        val limits = AiResearch.limits(AiResearchDepth.QUICK, contextTokens = 4096)
        AiResearch(limits, model, web).run("Q", AiAnswerFormat.AUTO, "")
        assertTrue(web.fetches.size <= limits.pages)
        assertFalse(reportInput.contains("[${limits.maxSources + 1}] "))
    }

    @Test
    fun `queries lose numbering, bullets, quotes, reasoning and DONE`() {
        assertEquals(
            listOf("one", "two", "three"),
            AiResearchText.queries("<think>hmm</think>1. one\n2) \"two\"\n\n* three\nDONE\nQueries:", 5),
        )
        assertEquals(listOf("one"), AiResearchText.queries("one\nOne\nONE", 5))
        assertTrue(AiResearchText.queries("DONE", 3).isEmpty())
    }

    @Test
    fun `citations are read in brackets, lists and ranges, within the sources`() {
        assertEquals(listOf(1, 2, 3, 5, 6, 7), AiResearchText.cited("a [3] b [1, 2] c [5-7] [99] [0]", 7))
        assertEquals(listOf(2, 4), AiResearchText.cited("x[4][2]", 4))
    }

    @Test
    fun `the source list fits the format`() {
        val sources = listOf(
            AiResearch.Source(1, "A [x]", "https://a.example/p(1)", "", "text"),
            AiResearch.Source(2, "B", "https://b.example/", "", ""),
        )
        assertEquals(
            "\n\n## Sources\n\n- [1] [A (x)](https://a.example/p%281%29)",
            AiResearchText.sourceList("see [1]", sources, AiAnswerFormat.MARKDOWN),
        )
        assertEquals(
            "\n\nSources:\n[2] B – https://b.example/",
            AiResearchText.sourceList("see [2]", sources, AiAnswerFormat.PLAIN),
        )
        assertEquals("", AiResearchText.sourceList("{\"sources\":[1]}", sources, AiAnswerFormat.JSON))
    }

    @Test
    fun `pdfs, videos and a third page from one site are not read`() {
        val hits = listOf(
            AiResearch.Hit("", "https://a.example/doc.pdf", ""),
            AiResearch.Hit("", "https://www.youtube.com/watch?v=1", ""),
            AiResearch.Hit("", "https://b.example/1", ""),
            AiResearch.Hit("", "https://b.example/2", ""),
            AiResearch.Hit("", "https://b.example/3", ""),
            AiResearch.Hit("", "file:///etc/passwd", ""),
        ).mapIndexed { rank, hit -> Triple(hit, 0, rank) }
        assertEquals(listOf("https://b.example/1"), AiResearchText.pickPages(hits, read = listOf("https://b.example/0"), count = 5))
    }

    @Test
    fun `the report input shares the budget between pages read`() {
        val sources = listOf(
            AiResearch.Source(1, "A", "https://a.example", "", "x".repeat(1000)),
            AiResearch.Source(2, "B", "https://b.example", "snip", ""),
        )
        val input = AiResearchText.reportInput("Q?", listOf("Sub?"), sources, budget = 100)
        assertTrue(input.contains("Sub-questions:\n1. Sub?"))
        assertTrue(input.contains("[1] A\nURL: https://a.example\n" + "x".repeat(100)))
        assertTrue(input.contains("[2] B\nURL: https://b.example\n(search result only) snip"))
    }

    @Test
    fun `a JSON plan becomes sub-questions, every one searched before any gets a second search`() {
        val raw = "```json\n{\"questions\": [" +
            "{\"question\": \"What is it?\", \"searches\": [\"a1\", \"a2\", \"a3\"]}," +
            "{\"question\": \"Why now?\", \"searches\": [\"b1\", \"A1\"]}," +
            "{\"question\": \"Who uses it?\", \"searches\": []}," +
            "{\"question\": \"\", \"searches\": [\"x\"]}]}\n```"
        val plan = AiResearchText.plan(raw, questions = 5, queries = 4)
        assertEquals(listOf("What is it?", "Why now?", "Who uses it?"), plan.map { it.question })
        assertEquals(listOf(listOf("a1", "a2"), listOf("b1"), listOf("Who uses it?")), plan.map { it.queries })
    }

    @Test
    fun `the checklist ticks each sub-question as it is covered`() {
        val changes = ArrayList<String>()
        val listener = object : AiResearch.Listener {
            override fun planChanged(step: AiToolActivity) { changes += step.output }
        }
        AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(), FakeWeb(), listener).run("Q", AiAnswerFormat.AUTO, "")
        assertEquals("[x] first query\n[ ] second query\n[ ] third query", changes.first())
        assertEquals("[x] first query\n[x] second query\n[x] third query", changes.last())
        assertEquals(listOf("first query" to true, "second query" to false), AiResearchText.checklistItems("[x] first query\n[ ] second query\nnoise"))
    }

    @Test
    fun `a declined plan searches nothing`() {
        val web = FakeWeb()
        val steps = ArrayList<AiToolActivity>()
        val listener = object : AiResearch.Listener {
            override fun approve(step: AiToolActivity): Boolean {
                assertTrue(step.awaitingApproval)
                assertTrue(step.details.startsWith("[ ] first query"))
                return false
            }
            override fun finished(step: AiToolActivity) { steps += step }
        }
        try {
            AiResearch(AiResearch.limits(AiResearchDepth.QUICK), FakeModel(), web, listener).run("Q", AiAnswerFormat.AUTO, "")
            error("not declined")
        } catch (_: AiResearch.DeclinedException) {
        }
        assertTrue(web.searches.isEmpty())
        assertEquals(AiResearch.DECLINED, steps.single().error)
    }

    @Test
    fun `follow-up questions come out of the answer, and are held back while it streams`() {
        val (answer, questions) = AiFollowUps.split("The answer.\n\n<followups>\n1. Why?\n- How?\n\n</followups>\n")
        assertEquals("The answer.", answer)
        assertEquals(listOf("Why?", "How?"), questions)
        assertEquals("Text" to emptyList<String>(), AiFollowUps.split("Text"))
        assertEquals("Text", AiFollowUps.visible("Text\n<follo"))
        assertEquals("Text", AiFollowUps.visible("Text\n<followups>\nWhy"))
        assertEquals("a < b", AiFollowUps.visible("a < b"))
        // Sources added after the block stay with the answer.
        assertEquals("R [1]\n\n## Sources", AiFollowUps.split("R [1]\n<followups>\nQ?\n</followups>\n\n## Sources").first)
    }
}
