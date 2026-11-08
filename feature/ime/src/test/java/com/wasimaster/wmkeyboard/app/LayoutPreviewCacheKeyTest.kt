package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The settings half of a layout card's picture key. The pictures are kept on
 * disk for the next launch, so the key must come out the same from equal
 * settings built separately — an object's default `toString`, with its
 * identity hash, would make every saved picture a miss — and must not move
 * when a card is toggled, or every toggle would re-render every card.
 */
class LayoutPreviewCacheKeyTest {

    @Test
    fun equalSettingsBuiltApartShareADigest() {
        assertEquals(
            SettingsDigest.compute(KeyboardSettings()),
            SettingsDigest.compute(KeyboardSettings()),
        )
    }

    @Test
    fun settingsTextCarriesNoIdentityHash() {
        val identityHash = Regex("""[A-Za-z0-9_$.]+@[0-9a-f]{5,8}\b""")
        val found = identityHash.find(KeyboardSettings().toString())
        assertFalse("identity toString in settings: ${found?.value}", found != null)
    }

    @Test
    fun switchingLayoutsLeavesTheDigestAlone() {
        val base = KeyboardSettings()
        val toggled = base.copy(
            enabledLayoutIds = base.enabledLayoutIds + "bn_avro",
            activeLayoutId = "bn_avro",
        )
        assertEquals(SettingsDigest.compute(base), SettingsDigest.compute(toggled))
    }

    @Test
    fun stateWrittenWhileTypingLeavesTheDigestAlone() {
        val base = KeyboardSettings()
        val used = base.copy(
            recentLayoutIds = listOf("en_qwerty", "bn_avro"),
            statsVersion = base.statsVersion + 1,
            unitConvertLast = "km>mi",
        )
        assertEquals(SettingsDigest.compute(base), SettingsDigest.compute(used))
    }

    @Test
    fun aVisibleSettingMovesTheDigest() {
        val base = KeyboardSettings()
        val taller = base.copy(keyHeightDp = base.keyHeightDp + 4)
        assertNotEquals(SettingsDigest.compute(base), SettingsDigest.compute(taller))
    }
}
