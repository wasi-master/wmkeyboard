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
}
