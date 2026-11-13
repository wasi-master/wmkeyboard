package com.wasimaster.wmkeyboard.core.snippets

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** `{greeting}`, `{bullets:N}`, `{list:N}`, `{battery}`, `{language}` and `{tab}`. */
class SnippetGreetingListTest {

    private fun at(hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 13, hour, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun expand(text: String, hour: Int = 9, context: SnippetStore.Companion.Context = SnippetStore.Companion.Context()) =
        SnippetStore.expand(text, now = at(hour), context = context)

    @Test fun greetingFollowsTheClock() {
        assertEquals("Good morning", expand("{greeting}", hour = 9))
        assertEquals("Good afternoon", expand("{greeting}", hour = 14))
        assertEquals("Good evening", expand("{greeting}", hour = 20))
        // Past midnight is still evening: "good night" is a goodbye.
        assertEquals("Good evening", expand("{greeting}", hour = 2))
        assertEquals("Good morning", expand("{greeting}", hour = 4))
        assertEquals("Good afternoon", expand("{greeting}", hour = 12))
        assertEquals("Good evening", expand("{greeting}", hour = 18))
    }

    @Test fun greetingSpeaksTheKeyboardLanguage() {
        val german = SnippetStore.Companion.Context(languageTag = "de-DE")
        assertEquals("Guten Abend", expand("{greeting}", hour = 19, context = german))
        val bengali = SnippetStore.Companion.Context(languageTag = "bn-BD")
        assertEquals("সুপ্রভাত", expand("{greeting}", hour = 8, context = bengali))
        val norwegian = SnippetStore.Companion.Context(languageTag = "no")
        assertEquals("God kveld", expand("{greeting}", hour = 21, context = norwegian))
    }

    @Test fun greetingFallsBackToEnglish() {
        val klingon = SnippetStore.Companion.Context(languageTag = "tlh")
        assertEquals("Good morning", expand("{greeting}", hour = 9, context = klingon))
        // A romanized keyboard does not type Devanagari.
        val hinglish = SnippetStore.Companion.Context(languageTag = "hi-Latn")
        assertEquals("Good morning", expand("{greeting}", hour = 9, context = hinglish))
        // A Latin-script language keeps its own words on a Latin tag.
        val spanish = SnippetStore.Companion.Context(languageTag = "es-Latn")
        assertEquals("Buenos días", expand("{greeting}", hour = 9, context = spanish))
    }

    @Test fun bulletsPutTheCaretInTheFirstLine() {
        val result = SnippetStore.expandWithCursor("Todo:\n{bullets:3}", now = at(9))
        assertEquals("Todo:\n• \n• \n• ", result.text)
        assertEquals("Todo:\n• ".length, result.cursorOffset)
    }

    @Test fun numberedListCountsUp() {
        val result = SnippetStore.expandWithCursor("{list:4}", now = at(9))
        assertEquals("1. \n2. \n3. \n4. ", result.text)
        assertEquals(3, result.cursorOffset)
    }

    @Test fun bareListWritesThree() {
        assertEquals("1. \n2. \n3. ", SnippetStore.expandWithCursor("{list}", now = at(9)).text)
        assertEquals("• \n• \n• ", SnippetStore.expandWithCursor("{bullets}", now = at(9)).text)
    }

    @Test fun anExplicitCursorWins() {
        val result = SnippetStore.expandWithCursor("{list:2}\nSigned {cursor}", now = at(9))
        assertEquals("1. \n2. \nSigned ", result.text)
        assertEquals(result.text.length, result.cursorOffset)
    }

    @Test fun onlyTheFirstListTakesTheCaret() {
        val result = SnippetStore.expandWithCursor("{bullets:1}\n{list:1}", now = at(9))
        assertEquals("• \n1. ", result.text)
        assertEquals(2, result.cursorOffset)
    }

    @Test fun listLengthIsCapped() {
        val text = SnippetStore.expandWithCursor("{bullets:999}", now = at(9)).text
        assertEquals(SnippetStore.MAX_LIST_LINES, text.lines().size)
        assertEquals("• ", SnippetStore.expandWithCursor("{bullets:0}", now = at(9)).text)
    }

    @Test fun listsStayClearOfModifiersAndFields() {
        val context = SnippetStore.Companion.Context(clipboard = "  a b  ")
        // {clip:…} still reshapes the clipboard, and an {ask:} field is left for the keyboard.
        assertEquals("a b {ask:Name}", expand("{clip:trim} {ask:Name}", context = context))
        // A word that only starts like a list keyword is not one.
        assertEquals("{listing:3}", expand("{listing:3}"))
    }

    @Test fun batteryLanguageAndTab() {
        val context = SnippetStore.Companion.Context(battery = { 73 }, language = "Deutsch")
        assertEquals("73% Deutsch", expand("{battery} {language}", context = context))
        assertEquals("a\tb", expand("a{tab}b"))
        // No reading, or a nonsense one, is nothing rather than "-1%".
        assertEquals("[]", expand("[{battery}]"))
        assertEquals("[]", expand("[{battery}]", context = SnippetStore.Companion.Context(battery = { -1 })))
    }

    @Test fun batteryIsReadOnlyWhenAsked() {
        var reads = 0
        val context = SnippetStore.Companion.Context(battery = { reads++; 50 })
        expand("no battery here", context = context)
        assertEquals(0, reads)
        expand("{battery}", context = context)
        assertEquals(1, reads)
    }
}
