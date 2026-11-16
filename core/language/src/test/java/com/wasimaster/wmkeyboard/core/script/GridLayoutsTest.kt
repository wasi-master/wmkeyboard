package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flick grids are on their languages' shelves (issue #1409). A shipped
 * layout no language lists is on no Languages screen, which is how the
 * English and Bangla flick boards went unreachable before this map existed.
 */
class GridLayoutsTest {

    @Test
    fun `every grid layout is listed under its language`() {
        for ((langId, ids) in GridLayouts.extraLayoutIds) {
            val language = LanguageRegistry.byId(langId)
            assertNotSame("$langId is not a registered language", LanguageRegistry.GENERIC, language)
            for (id in ids) assertTrue("$id is missing from $langId", id in language.layoutIds)
        }
    }

    @Test
    fun `portuguese has the thumb-key board the issue asked for`() {
        assertTrue("asset_pt_thumbkey" in LanguageRegistry.byId("pt").layoutIds)
    }
}
