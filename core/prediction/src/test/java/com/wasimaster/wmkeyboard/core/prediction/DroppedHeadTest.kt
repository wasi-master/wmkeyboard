package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroppedHeadTest {

    @Test fun aPrefixDroppedAsTwoEditsIsCaught() {
        for ((typed, stem) in listOf(
            "unintereste" to "interested",
            "uninterested" to "interested",
            "неинтересно" to "интересно",
            "небольшой" to "большой",
            "rereading" to "reading",
            "disagre" to "agree",
            "Uninterested" to "Interested",
        )) {
            assertTrue("$typed -> $stem", DroppedHead.drops(typed, stem))
        }
    }

    @Test fun slipsAndAStrayFirstTapStayFixes() {
        for ((typed, fixed) in listOf(
            "babana" to "banana",
            "jjust" to "just",
            "wwhat" to "what",
            "athe" to "the",
            "unhapy" to "unhappy",
            "unknwn" to "unknown",
            "thsi" to "this",
            "uninterested" to "un interested",
        )) {
            assertFalse("$typed -> $fixed", DroppedHead.drops(typed, fixed))
        }
    }
}
