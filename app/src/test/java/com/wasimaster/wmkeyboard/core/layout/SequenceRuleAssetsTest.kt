package com.wasimaster.wmkeyboard.core.layout

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped typing-rule sets (#1493) against the layouts that name them: every
 * set parses and compiles with no rule left out, and every id a shipped layout
 * names is a set that ships.
 */
class SequenceRuleAssetsTest {

    private val dir = File("src/main/assets/sequence_rules")

    private val sets: Map<String, SequenceRuleSet> by lazy {
        (dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }).associate { file ->
            val set = SequenceRuleSets.decode(file.readText())
            requireNotNull(set) { "${file.name} is not a rule set" }
            assertEquals("${file.name} id", file.name.removeSuffix(".json"), set.id)
            set.id to set
        }
    }

    @Test
    fun `every shipped set compiles whole`() {
        assertTrue("no sets found in $dir", sets.isNotEmpty())
        for ((id, set) in sets) {
            assertTrue("$id has a name", set.name.isNotBlank())
            assertTrue("$id has rules", set.sequenceRules.isNotEmpty())
            val problems = CompiledSequenceRules.compile(set.characterGroups, set.sequenceRules, id).problems
            assertEquals("$id problems", emptyList<SequenceRuleProblem>(), problems)
        }
    }

    @Test
    fun `every set a shipped layout names ships`() {
        val named = HashMap<String, MutableList<String>>()
        for (file in File("src/main/assets/layouts").listFiles().orEmpty()) {
            if (!file.name.endsWith(".wmlayout.json")) continue
            val layout = Json.parseToJsonElement(file.readText()) as JsonObject
            val ids = ((layout["layout"] as? JsonObject)?.get("ruleSets") as? JsonArray) ?: continue
            for (id in ids) named.getOrPut(id.jsonPrimitive.content) { mutableListOf() } += file.name
        }
        assertTrue("no layout names a set", named.isNotEmpty())
        for ((id, files) in named) assertTrue("$id named by ${files.take(3)} does not ship", id in sets)
        // The defaults the issue asked for are on.
        assertTrue("th_kedmanee" in named["thai"].orEmpty().joinToString())
        assertTrue("lo_lao" in named["lao"].orEmpty().joinToString())
    }
}
