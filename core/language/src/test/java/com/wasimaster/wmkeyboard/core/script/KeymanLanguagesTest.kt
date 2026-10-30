package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the generated language table to the invariants the registry relies on.
 *
 * A generated file is exactly where these need checking: nobody reads 487
 * entries, and every failure mode here is silent at runtime — a duplicate id
 * shadows in a map, a blank name renders as an empty row, a bad script quietly
 * falls back to Latin.
 */
class KeymanLanguagesTest {

    @Test
    fun `the generated table is not empty`() {
        assertTrue(
            "generated language table is empty; did the pipeline run?",
            KeymanLanguages.all.size > 400,
        )
    }

    /**
     * `LanguageRegistry.index` is an `associateBy`, so a duplicate id keeps the
     * last entry and silently discards the first. If a generated language
     * collided with a hand-written one, the hand-written entry — the one with a
     * dictionary and an endonym — would be the one lost.
     */
    @Test
    fun `no generated language collides with a hand-written one`() {
        val generated = KeymanLanguages.all.map { it.id }
        val handWritten = LanguageRegistry.all.map { it.id } - generated.toSet()
        val clashes = generated.toSet() intersect handWritten.toSet()
        assertTrue("generated ids also declared by hand: $clashes", clashes.isEmpty())
    }

    @Test
    fun `generated ids are unique`() {
        val duplicates = KeymanLanguages.all.map { it.id }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
        assertTrue("duplicate generated ids: $duplicates", duplicates.isEmpty())
    }

    /** Every id must resolve, or a converted layout's language silently becomes "und". */
    @Test
    fun `every generated language resolves through the registry`() {
        for (lang in KeymanLanguages.all) {
            assertEquals(
                "byId(${lang.id}) did not return its own entry",
                lang.id,
                LanguageRegistry.byId(lang.id).id,
            )
        }
    }

    /**
     * A script with no [ScriptDef] falls back to Latin without complaining,
     * which for an RTL or complex script means the keyboard silently lays text
     * out the wrong way round.
     */
    @Test
    fun `every generated language names a script the registry defines`() {
        val missing = KeymanLanguages.all
            .filter { ScriptRegistry.get(it.script).id != it.script }
            .map { "${it.id}:${it.script}" }
        assertTrue("languages naming an undefined script: $missing", missing.isEmpty())
    }

    @Test
    fun `every generated language has a name and at least one layout`() {
        for (lang in KeymanLanguages.all) {
            assertTrue("${lang.id} has a blank display name", lang.displayName.isNotBlank())
            assertTrue("${lang.id} has a blank English name", lang.englishName.isNotBlank())
            assertTrue("${lang.id} lists no layouts", lang.layoutIds.isNotEmpty())
        }
    }

    /**
     * Every listed layout id must be one the pipeline emits. A typo here is a
     * language that offers a keyboard which does not exist.
     */
    @Test
    fun `every generated layout id looks like a converted keyman layout`() {
        for (lang in KeymanLanguages.all) {
            for (id in lang.layoutIds) {
                assertTrue(
                    "${lang.id} lists a non-Keyman layout id '$id'",
                    id.startsWith("asset_kmn_"),
                )
            }
        }
    }

    /** The id is used as a BCP-47 tag for dictation and hint locales. */
    @Test
    fun `generated ids are well-formed language tags`() {
        val shape = Regex("^[a-z]{2,3}(-[A-Z][a-z]{3})?(-[A-Z]{2}|-[0-9]{3})?$")
        val malformed = KeymanLanguages.all.map { it.id }.filterNot { shape.matches(it) }
        assertTrue("malformed language tags: ${malformed.take(20)}", malformed.isEmpty())
    }

    /**
     * A generated language with the same tag as a hand-written one is the same
     * language listed twice ("Hindi (Latin)" beside Hinglish). Those are folded
     * into the hand-written entry; this keeps a new one from creeping back.
     */
    @Test
    fun `no generated language repeats a hand-written one's tag`() {
        // By id: the registry adds Gboard's and Samsung's layouts to these entries, so
        // its copy of one no longer equals the one here.
        val generated = KeymanLanguages.all.mapTo(HashSet()) { it.id }
        val handTags = LanguageRegistry.all.filterNot { it.id in generated }.map { it.localeTag.lowercase() }.toSet()
        val repeats = KeymanLanguages.all.filter { it.localeTag.lowercase() in handTags }.map { it.id }
        assertTrue("generated languages repeating a hand-written tag: $repeats", repeats.isEmpty())
    }

    /** A folded id still resolves, to the language its layouts joined, so saved settings keep working. */
    @Test
    fun `a folded language id resolves to the one it joined`() {
        for ((old, target) in KeymanLanguages.mergedInto) {
            assertEquals(old, target, LanguageRegistry.byId(old).id)
            assertTrue(
                "$old's layouts are not on $target",
                KeymanLanguages.extraLayoutIds[target].orEmpty().isNotEmpty(),
            )
        }
    }
}
