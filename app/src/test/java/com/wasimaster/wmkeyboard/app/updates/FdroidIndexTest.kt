package com.wasimaster.wmkeyboard.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FdroidIndexTest {

    @Test
    fun readsFdroidOrgWhereVersionCodesAreNumbers() {
        val index = FdroidIndex.decode(
            """{"packageName":"com.wasimaster.wmkeyboard","suggestedVersionCode":362,""" +
                """"packages":[{"versionName":"0.6.3","versionCode":362},{"versionName":"0.6.2","versionCode":352}]}""",
        )
        assertNotNull(index)
        assertEquals(362, index!!.suggestedVersionCode)
        assertEquals("0.6.3", FdroidIndex.newerThan(index, 35)?.versionName)
    }

    @Test
    fun readsIzzyOnDroidWhereVersionCodesAreStrings() {
        // The same API shape, served by apt.izzysoft.de with quoted codes.
        val index = FdroidIndex.decode(
            """{"packageName":"com.aurora.store","suggestedVersionCode":"76",""" +
                """"packages":[{"versionCode":"76","versionName":"4.8.4"},{"versionCode":"75","versionName":"4.8.3"}]}""",
        )
        assertNotNull(index)
        assertEquals(76, index!!.suggestedVersionCode)
        assertEquals("4.8.4", FdroidIndex.newerThan(index, 75)?.versionName)
        assertNull(FdroidIndex.newerThan(index, 76))
    }

    @Test
    fun perAbiCodesCompareAsTheirRelease() {
        // A release's two F-Droid APKs: armeabi-v7a, arm64-v8a. F-Droid
        // suggests the highest, which is not the one a 32-bit phone runs.
        val index = FdroidIndex.decode(
            """{"packageName":"com.wasimaster.wmkeyboard","suggestedVersionCode":352,""" +
                """"packages":[{"versionName":"0.6.2","versionCode":352},{"versionName":"0.6.2","versionCode":351}]}""",
        )!!
        assertNull(FdroidIndex.newerThan(index, 35))
        val newer = FdroidIndex.newerThan(index, 34)
        assertEquals(35, newer?.versionCode)
        assertEquals("0.6.2", newer?.versionName)
    }

    @Test
    fun codesBeforeTheAbiDigitAreTheReleaseItself() {
        assertEquals(34, FdroidIndex.releaseCode(34))
        assertEquals(35, FdroidIndex.releaseCode(350))
        assertEquals(35, FdroidIndex.releaseCode(352))
    }

    @Test
    fun anUnreadableCodeIsNoUpdateRatherThanACrash() {
        val index = FdroidIndex.decode("""{"suggestedVersionCode":"soon","packages":[]}""")
        assertEquals(0, index?.suggestedVersionCode)
    }

    @Test
    fun theDefaultRepositoryIsFdroidOrg() {
        assertEquals("https://f-droid.org/api/v1/packages/a.b", FdroidIndex.apiUrl("a.b"))
        assertEquals("https://f-droid.org/packages/a.b/", FdroidIndex.packagePage("a.b"))
    }
}
