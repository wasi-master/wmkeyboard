package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.regex.GuardedRegex
import com.wasimaster.wmkeyboard.core.settings.SuggestionSourceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pattern entries on the suggestion blacklist and its own file (gap list L8). */
class SuggestionBlacklistTest {

    /** Optional `a`s then the same count required: fine on paper, exponential on a run of `a`s. */
    private val runaway = "/" + "a?".repeat(16) + "a{16}b/"

    // ---- entries ----

    @Test fun onlyAnEntryBetweenTwoSlashesIsAPattern() {
        assertTrue(BlacklistPatterns.isPattern("/lo+l/"))
        assertFalse(BlacklistPatterns.isPattern("lol"))
        assertFalse(BlacklistPatterns.isPattern("/lol"))
        assertFalse(BlacklistPatterns.isPattern("//"))
        assertFalse(BlacklistPatterns.isPattern("/"))
    }

    @Test fun aWordIsLowercasedAndAPatternKeptAsWritten() {
        assertEquals("hello", BlacklistPatterns.normalize("  Hello "))
        // \S and \s differ only in case, so a pattern is never lowercased.
        assertEquals("/\\S+X/", BlacklistPatterns.normalize(" /\\S+X/ "))
        assertNull(BlacklistPatterns.normalize("   "))
    }

    // ---- guards ----

    @Test fun aPlainWordAlwaysPasses() {
        assertNull(BlacklistPatterns.check("whatever"))
        assertNull(BlacklistPatterns.check("(a+)+"))
    }

    @Test fun anHonestPatternPasses() {
        assertNull(BlacklistPatterns.check("/lo+l/"))
        assertNull(BlacklistPatterns.check("/.*ing/"))
        assertNull(BlacklistPatterns.check("/(ha)+/"))
    }

    @Test fun eachGuardSaysWhy() {
        assertEquals(BlacklistPatterns.Fault.EMPTY, BlacklistPatterns.check("//")?.fault)
        assertEquals(BlacklistPatterns.Fault.EMPTY, BlacklistPatterns.check("/  /")?.fault)
        val syntax = BlacklistPatterns.check("/(lol/")
        assertEquals(BlacklistPatterns.Fault.SYNTAX, syntax?.fault)
        assertNotNull(syntax?.description)
        assertEquals(BlacklistPatterns.Fault.BACKREFERENCE, BlacklistPatterns.check("/(l)o\\1/")?.fault)
        assertEquals(BlacklistPatterns.Fault.NESTED_QUANTIFIER, BlacklistPatterns.check("/(a+)+/")?.fault)
        assertEquals(
            BlacklistPatterns.Fault.TOO_LONG,
            BlacklistPatterns.check("/" + "a".repeat(BlacklistPatterns.MAX_PATTERN_LENGTH + 1) + "/")?.fault,
        )
    }

    @Test fun aPatternThatRunsAwayOnAProbeIsRefused() {
        assertEquals(BlacklistPatterns.Fault.RUNAWAY, BlacklistPatterns.check(runaway)?.fault)
    }

    @Test fun aPatternThatHidesEveryWordIsRefused() {
        assertEquals(BlacklistPatterns.Fault.MATCHES_EVERYTHING, BlacklistPatterns.check("/.*/")?.fault)
        assertEquals(BlacklistPatterns.Fault.MATCHES_EVERYTHING, BlacklistPatterns.check("/.+/")?.fault)
        assertEquals(BlacklistPatterns.Fault.MATCHES_EVERYTHING, BlacklistPatterns.check("/\\w+/")?.fault)
    }

    @Test fun theStepBudgetStopsARunawayMatch() {
        val pattern = GuardedRegex.compile(BlacklistPatterns.bodyOf(runaway))
        assertNull(GuardedRegex.matchesWithin(pattern, "a".repeat(32), BlacklistPatterns.MAX_STEPS))
        assertEquals(false, GuardedRegex.matchesWithin(pattern, "zzz", BlacklistPatterns.MAX_STEPS))
    }

    // ---- matching ----

    @Test fun aPatternMatchesTheWholeWordIgnoringCase() {
        val matcher = BlacklistMatcher.of(setOf("idk", "/LO+L/"))
        assertTrue(matcher.hasPatterns)
        assertTrue(matcher.matches("idk"))
        assertTrue(matcher.matches("lol"))
        assertTrue(matcher.matches("looooool"))
        assertFalse(matcher.matches("lolly"))
        assertFalse(matcher.matches("hello"))
    }

    @Test fun aListWithoutPatternsIsOnlyASetLookup() {
        val matcher = BlacklistMatcher.of(setOf("idk", "ugh"))
        assertFalse(matcher.hasPatterns)
        assertTrue(matcher.matches("ugh"))
        assertFalse(matcher.matchesPattern("ugh"))
        assertFalse(BlacklistMatcher.NONE.matches("anything"))
    }

    @Test fun aRefusedPatternIsLeftOffTheMatcher() {
        val matcher = BlacklistMatcher.of(setOf("/(a+)+/", "/(l)o\\1/"))
        assertFalse(matcher.hasPatterns)
        assertFalse(matcher.matches("aaa"))
    }

    @Test fun aPatternThatRunsAwayOnARealWordIsStoppedAndTheRestStillWork() {
        // The hot path screens but does not probe, so the runaway shape gets on.
        val matcher = BlacklistMatcher.of(linkedSetOf(runaway, "/lo+l/"))
        assertFalse(matcher.matches("a".repeat(32)))
        // Stopped for good: the same word again costs nothing and still says no.
        assertFalse(matcher.matches("a".repeat(32)))
        assertTrue(matcher.matches("lool"))
    }

    @Test fun theSettingsListsAnswerForPatternsToo() {
        val sources = SuggestionSourceSettings(
            blacklist = setOf("idk", "/lo+l/"),
            blacklistByLanguage = mapOf("en" to setOf("/ugh+/")),
        )
        assertTrue(sources.blacklisted("Loool", "en"))
        assertTrue(sources.blacklisted("ughhh", "en"))
        assertFalse(sources.blacklisted("ughhh", "fr"))
        assertTrue(sources.blacklistedAnywhere("ughhh"))
        assertFalse(sources.blacklistedAnywhere("hello"))
        // Only a pattern holds it, so taking the word off would change nothing.
        assertTrue(sources.blacklistedByPatternOnly("lool", "en"))
        assertFalse(sources.blacklistedByPatternOnly("idk", "en"))
        assertFalse(sources.blacklistedByPatternOnly("hello", "en"))
    }

    // ---- the file ----

    @Test fun parseSkipsCommentsAndBlanksAndReadsTags() {
        val parsed = BlacklistFile.parse(
            "﻿# a comment\n\nIDK\n/lo+l/\nwhatever @en\n  ugh  @bn_rom \r\n",
        )
        assertEquals(
            listOf(
                BlacklistFile.Entry("idk", null),
                BlacklistFile.Entry("/lo+l/", null),
                BlacklistFile.Entry("whatever", "en"),
                BlacklistFile.Entry("ugh", "bn_rom"),
            ),
            parsed.entries,
        )
        assertEquals(0, parsed.duplicates)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test fun parseDropsRepeatsAndCountsThem() {
        val parsed = BlacklistFile.parse("idk\nIDK\nidk @en\nidk @en\n/lo+l/\n/lo+l/\n")
        assertEquals(
            listOf(
                BlacklistFile.Entry("idk", null),
                BlacklistFile.Entry("idk", "en"),
                BlacklistFile.Entry("/lo+l/", null),
            ),
            parsed.entries,
        )
        assertEquals(3, parsed.duplicates)
    }

    @Test fun parseRefusesBadPatternsByLine() {
        val parsed = BlacklistFile.parse("ok\n/(a+)+/\n# skip\n/.*/ @en\n")
        assertEquals(listOf(BlacklistFile.Entry("ok", null)), parsed.entries)
        assertEquals(listOf(2, 4), parsed.rejected.map { it.line })
        assertEquals(BlacklistPatterns.Fault.NESTED_QUANTIFIER, parsed.rejected[0].problem.fault)
        assertEquals(BlacklistPatterns.Fault.MATCHES_EVERYTHING, parsed.rejected[1].problem.fault)
    }

    @Test fun aTagIsOnlyTheLastWordAndOnlyWithAnAt() {
        assertEquals("/a b/" to "en", BlacklistFile.splitTag("/a b/ @en"))
        assertEquals("me@home" to null, BlacklistFile.splitTag("me@home"))
        assertEquals("ask @" to null, BlacklistFile.splitTag("ask @"))
        // Inside the slashes it is part of the pattern.
        assertEquals("/x @en/" to null, BlacklistFile.splitTag("/x @en/"))
    }

    @Test fun writeReadsBackTheSameLists() {
        val global = setOf("idk", "/lo+l/")
        val byLanguage = mapOf("en" to setOf("whatever"), "bn" to setOf("/\\S+x/"))
        val text = BlacklistFile.write(global, byLanguage)
        assertTrue(text.startsWith("#"))
        val parsed = BlacklistFile.parse(text)
        assertEquals(global, parsed.entries.filter { it.languageId == null }.map { it.entry }.toSet())
        assertEquals(setOf("whatever"), parsed.entries.filter { it.languageId == "en" }.map { it.entry }.toSet())
        assertEquals(setOf("/\\S+x/"), parsed.entries.filter { it.languageId == "bn" }.map { it.entry }.toSet())
        assertTrue(parsed.rejected.isEmpty())
    }
}
