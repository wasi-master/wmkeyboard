package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class CaseStepTest {

    @Test
    fun `up steps lower to Title to UPPER and stops`() {
        assertEquals("Hello", caseFormStep("hello", up = true))
        assertEquals("HELLO", caseFormStep("Hello", up = true))
        assertEquals("HELLO", caseFormStep("HELLO", up = true))
    }

    @Test
    fun `down steps UPPER to Title to lower and stops`() {
        assertEquals("Hello", caseFormStep("HELLO", up = false))
        assertEquals("hello", caseFormStep("Hello", up = false))
        assertEquals("hello", caseFormStep("hello", up = false))
    }

    @Test
    fun `mixed case goes to Title up and lower down`() {
        assertEquals("Hello", caseFormStep("hELlo", up = true))
        assertEquals("hello", caseFormStep("hELlo", up = false))
    }

    @Test
    fun `one letter skips between its two forms`() {
        assertEquals("A", caseFormStep("a", up = true))
        assertEquals("a", caseFormStep("A", up = false))
    }

    @Test
    fun `caseless text and sharp s keep their spelling`() {
        assertEquals("বাংলা", caseFormStep("বাংলা", up = true))
        assertEquals("SPAẞ", caseFormStep("Spaß", up = true))
        assertEquals("Spaß", caseFormStep("SPAẞ", up = false))
    }

    @Test
    fun `Title Case keeps contractions, quotes and words after digits (#1555)`() {
        assertEquals("Don't Doesn't", toTitleCase("don't doesn't"))
        assertEquals("Don\u2019t", toTitleCase("don\u2019t"))
        assertEquals("'Hello' He Said", toTitleCase("'hello' he said"))
        assertEquals("The 2nd And 3rd 4g", toTitleCase("the 2nd and 3rd 4g"))
    }

    @Test
    fun `Title Case keeps names and acronyms from the text the cycle started from`() {
        val origin = "my iPhone got an SMS from YouTube"
        val lower = "my iphone got an sms from youtube"
        assertEquals("My iPhone Got An SMS From YouTube", toTitleCase(lower, origin))
        // Without a reference nothing is kept.
        assertEquals("My Iphone Got An Sms From Youtube", toTitleCase(lower))
        // A shouted origin says nothing about which words are acronyms.
        assertEquals("Sms From Bob", toTitleCase("sms from bob", "SMS FROM BOB"))
        assertEquals("4G Lte", toTitleCase("4g lte", "4G LTE"))
    }

    @Test
    fun `Title Case keeps a capital written after an apostrophe or inside a name`() {
        // O'Neil is mixed case like iPhone: the capital past the apostrophe is
        // the user's, and the trip through lowercase gives it back.
        val origin = "ask O'Neil, D'Angelo and McDonald"
        val lower = "ask o'neil, d'angelo and mcdonald"
        assertEquals("Ask O'Neil, D'Angelo And McDonald", toTitleCase(lower, origin))
        assertEquals("O’Neil", toTitleCase("o’neil", "O’Neil"))
        var s = origin
        s = recaseStep(s, origin, firstPress = true, titleFirst = false)
        assertEquals(lower, s)
        s = recaseStep(s, origin, firstPress = false, titleFirst = false)
        assertEquals("Ask O'Neil, D'Angelo And McDonald", s)
        assertEquals(
            "Ask O'Neil, D'Angelo And McDonald",
            recaseStep(origin, origin, firstPress = true, titleFirst = true),
        )
    }

    @Test
    fun `shift walks lower Title UPPER and gives the names back`() {
        val origin = "hello SMS"
        var s = origin
        s = recaseStep(s, origin, firstPress = true, titleFirst = false)
        assertEquals("hello sms", s)
        s = recaseStep(s, origin, firstPress = false, titleFirst = false)
        assertEquals("Hello SMS", s)
        s = recaseStep(s, origin, firstPress = false, titleFirst = false)
        assertEquals("HELLO SMS", s)
        s = recaseStep(s, origin, firstPress = false, titleFirst = false)
        assertEquals("hello sms", s)
    }

    @Test
    fun `Title first sends any text to Title Case on the first press`() {
        assertEquals("Hello SMS", recaseStep("hello SMS", "hello SMS", firstPress = true, titleFirst = true))
        assertEquals("Hello World", recaseStep("HELLO WORLD", "HELLO WORLD", firstPress = true, titleFirst = true))
        // After the first press the cycle goes on as usual.
        assertEquals("HELLO WORLD", recaseStep("Hello World", "HELLO WORLD", firstPress = false, titleFirst = true))
        assertEquals("hello world", recaseStep("HELLO WORLD", "HELLO WORLD", firstPress = false, titleFirst = true))
    }

    @Test
    fun `a form that changes nothing is skipped`() {
        assertEquals("2ND", recaseStep("2nd", "2nd", firstPress = true, titleFirst = false))
        assertEquals("বাংলা", recaseStep("বাংলা", "বাংলা", firstPress = true, titleFirst = true))
    }
}
