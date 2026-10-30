package com.wasimaster.wmkeyboard.core.handwriting

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** A handwriting pad is only worth listing for a language some recogniser reads. */
class HandwritingPadsTest {

    @Test
    fun `every language with a pad has a recogniser`() {
        val subtags = InkModelTags.ALL.map { it.substringBefore('-') }.toSet() + setOf("nb", "tl")
        val pads = LanguageRegistry.all.filter { language -> language.layoutIds.any { it.startsWith("asset_hw_") } }
        assertTrue("no pads at all", pads.size > 200)
        for (language in pads) {
            assertTrue(
                "${language.id} has a pad and no recogniser",
                language.id in subtags ||
                    InkOcrModels.packFor(language) != null ||
                    ZinniaModels.tagFor(language, false) != null,
            )
        }
    }
}
