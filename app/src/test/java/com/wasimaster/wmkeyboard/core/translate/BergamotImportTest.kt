package com.wasimaster.wmkeyboard.core.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BergamotImportTest {

    @Test
    fun `a file is worth hashing at either of its published sizes`() {
        val file = BergamotCatalog.directions.first().files.first()
        assertTrue(BergamotTranslator.sizeMatches(file.size))
        assertTrue(BergamotTranslator.sizeMatches(file.rawSize))
    }

    @Test
    fun `an empty file or one no direction has is not`() {
        assertFalse(BergamotTranslator.sizeMatches(0))
        assertFalse(BergamotTranslator.sizeMatches(1))
    }
}
