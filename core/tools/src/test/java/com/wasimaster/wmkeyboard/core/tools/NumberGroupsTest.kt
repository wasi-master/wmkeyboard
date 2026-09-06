package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberGroupsTest {

    private fun western(digits: String) = NumberGroups.group(digits, NumberGrouping.WESTERN)

    private fun southAsian(digits: String) = NumberGroups.group(digits, NumberGrouping.SOUTH_ASIAN)

    @Test
    fun thousandsGroupInThrees() {
        assertEquals("1,234", western("1234"))
        assertEquals("12,345", western("12345"))
        assertEquals("1,234,567", western("1234567"))
        assertEquals("1,000,000,000", western("1000000000"))
    }

    @Test
    fun lakhAndCroreKeepThreeThenTwos() {
        assertEquals("1,234", southAsian("1234"))
        assertEquals("12,345", southAsian("12345"))
        assertEquals("1,00,000", southAsian("100000"))
        assertEquals("12,34,567", southAsian("1234567"))
        assertEquals("10,00,000", southAsian("1000000"))
        assertEquals("1,00,00,000", southAsian("10000000"))
    }

    @Test
    fun shortRunsAreLeftAlone() {
        for (digits in listOf("", "7", "45", "999")) {
            assertEquals(digits, western(digits))
            assertEquals(digits, southAsian(digits))
        }
    }

    @Test
    fun leadingGroupCanBeShorterThanAFullStep() {
        // The leftmost group takes whatever is left over rather than being
        // padded — "1234567890" is one crore twenty-three lakh, not "01,23,…".
        assertEquals("1,23,45,67,890", southAsian("1234567890"))
        assertEquals("1,234,567,890", western("1234567890"))
    }

    @Test
    fun autoFollowsTheLanguageBeingTypedIn() {
        for (tag in listOf("bn-BD", "hi-IN", "ur-PK", "ne-NP", "ta-IN", "en-IN")) {
            assertEquals(tag, NumberGrouping.SOUTH_ASIAN, NumberGroups.styleFor(NumberGrouping.AUTO, tag))
        }
        for (tag in listOf("en-US", "fr-FR", "ar-SA", "ja-JP", "")) {
            assertEquals(tag, NumberGrouping.WESTERN, NumberGroups.styleFor(NumberGrouping.AUTO, tag))
        }
    }

    @Test
    fun anExplicitChoiceIgnoresTheLanguage() {
        assertEquals(
            NumberGrouping.WESTERN,
            NumberGroups.styleFor(NumberGrouping.WESTERN, "bn-BD"),
        )
        assertEquals(
            NumberGrouping.SOUTH_ASIAN,
            NumberGroups.styleFor(NumberGrouping.SOUTH_ASIAN, "en-US"),
        )
    }
}
