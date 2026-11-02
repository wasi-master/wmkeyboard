package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSaverSettingsTest {

    private val wifi = DeviceNetworkState()
    private val mobile = DeviceNetworkState(metered = true)
    private val abroad = DeviceNetworkState(metered = true, roaming = true)

    @Test
    fun `manual holds on any connection`() {
        val config = DataSaverSettings(manual = true, trigger = DataSaverTrigger.OFF)
        assertTrue(config.appliesTo(wifi))
        assertTrue(config.appliesTo(mobile))
    }

    @Test
    fun `the default never turns on by itself`() {
        val config = DataSaverSettings()
        assertEquals(DataSaverTrigger.OFF, config.trigger)
        assertFalse(config.appliesTo(wifi))
        assertFalse(config.appliesTo(abroad))
        assertFalse(config.appliesTo(abroad.copy(systemDataSaver = true)))
    }

    @Test
    fun `the metered trigger follows the meter`() {
        val config = DataSaverSettings(trigger = DataSaverTrigger.METERED)
        assertFalse(config.appliesTo(wifi))
        assertTrue(config.appliesTo(mobile))
        assertFalse(
            "Android's own Data Saver is a different trigger",
            config.appliesTo(wifi.copy(systemDataSaver = true)),
        )
    }

    @Test
    fun `roaming is the strictest trigger`() {
        val config = DataSaverSettings(trigger = DataSaverTrigger.ROAMING)
        assertFalse("plain mobile data is not roaming", config.appliesTo(mobile))
        assertTrue(config.appliesTo(abroad))
    }

    @Test
    fun `either takes any of the three`() {
        val config = DataSaverSettings(trigger = DataSaverTrigger.EITHER)
        assertFalse(config.appliesTo(wifi))
        assertTrue(config.appliesTo(mobile))
        assertTrue(config.appliesTo(abroad))
        assertTrue(config.appliesTo(wifi.copy(systemDataSaver = true)))
    }

    @Test
    fun `an offline device restricts nothing by itself`() {
        val offline = DeviceNetworkState(online = false)
        assertFalse(DataSaverSettings().appliesTo(offline))
    }

    @Test
    fun `what you tap for is allowed by default, and only real data asks`() {
        val stock = DataSaverStatus(active = true, settings = DataSaverSettings())
        assertEquals(MeteredDecision.ALLOWED, stock.decide(MeteredFeature.MEDIA_SEARCH))
        assertEquals(MeteredDecision.ALLOWED, stock.decide(MeteredFeature.WEB_SEARCH))
        assertEquals(MeteredDecision.ALLOWED, stock.decide(MeteredFeature.CLOUD_AI))
        assertEquals(MeteredDecision.ASK, stock.decide(MeteredFeature.DOWNLOADS))
        assertEquals(MeteredDecision.ASK, stock.decide(MeteredFeature.ANIMATED_EMOJI))
    }

    @Test
    fun `the defaults hold something back`() {
        assertTrue(DataSaverSettings().restrictsAnything)
        assertFalse(
            DataSaverSettings(
                linkPreviews = MeteredPolicy.ALLOW,
                dictionaryLookup = MeteredPolicy.ALLOW,
                photoBackgrounds = MeteredPolicy.ALLOW,
                weatherChip = MeteredPolicy.ALLOW,
                currencyRates = MeteredPolicy.ALLOW,
                addonRefresh = MeteredPolicy.ALLOW,
                mediaSearch = MeteredPolicy.ALLOW,
                webSearch = MeteredPolicy.ALLOW,
                animatedEmoji = MeteredPolicy.ALLOW,
                downloads = MeteredPolicy.ALLOW,
                cloudAi = MeteredPolicy.ALLOW,
                cloudVoice = MeteredPolicy.ALLOW,
                fileSend = MeteredPolicy.ALLOW,
                cloudGrammar = MeteredPolicy.ALLOW,
                cloudMusic = MeteredPolicy.ALLOW,
                vocabAudio = MeteredPolicy.ALLOW,
                speedTest = MeteredPolicy.ALLOW,
            ).restrictsAnything,
        )
    }

    @Test
    fun `ask collapses to blocked for background work`() {
        assertTrue(MeteredPolicy.BLOCK.stopsBackgroundWork)
        assertTrue(MeteredPolicy.ASK.stopsBackgroundWork)
        assertFalse(MeteredPolicy.ALLOW.stopsBackgroundWork)
    }

    @Test
    fun `an inactive saver decides nothing`() {
        val status = DataSaverStatus(
            active = false,
            settings = DataSaverSettings(mediaSearch = MeteredPolicy.BLOCK),
        )
        assertEquals(MeteredDecision.ALLOWED, status.decide(MeteredFeature.MEDIA_SEARCH))
    }

    @Test
    fun `a grant lasts for the session`() {
        // Asked for explicitly: the stock answer for a search is allow now.
        val status = DataSaverStatus(
            active = true,
            settings = DataSaverSettings(mediaSearch = MeteredPolicy.ASK),
        )
        assertEquals(MeteredDecision.ASK, status.decide(MeteredFeature.MEDIA_SEARCH))
        val granted = status.granting(MeteredFeature.MEDIA_SEARCH)
        assertTrue(granted.allows(MeteredFeature.MEDIA_SEARCH))
        assertFalse(
            "one yes answers for one feature only",
            granted.allows(MeteredFeature.DOWNLOADS),
        )
    }

    @Test
    fun `a blocked feature is never offered`() {
        val status = DataSaverStatus(
            active = true,
            settings = DataSaverSettings(cloudAi = MeteredPolicy.BLOCK),
        )
        assertEquals(MeteredDecision.BLOCKED, status.decide(MeteredFeature.CLOUD_AI))
    }

    @Test
    fun `the view takes the background fetches out of the settings`() {
        val settings = KeyboardSettings(
            scanner = ScannerSettings(qrScanLinkPreviews = true),
            dictionaryAutoLookup = true,
            clipboard = ClipboardSettings(linkPreviews = true),
            photoBackground = PhotoBackgroundSettings(fetchOnMetered = true),
            smartChips = SmartChipSettings(weather = true),
        ).onMeteredNetwork()
        assertFalse(settings.clipboard.linkPreviews)
        assertFalse(settings.scanner.qrScanLinkPreviews)
        assertFalse(settings.dictionaryAutoLookup)
        assertFalse(settings.photoBackground.fetchOnMetered)
        assertFalse(settings.smartChips.weather)
    }

    @Test
    fun `the view leaves the on-demand features alone`() {
        // They are decided at the moment they happen, through DataSaverStatus,
        // because a settings field cannot hold "ask".
        val stock = KeyboardSettings()
        val metered = stock.onMeteredNetwork()
        assertEquals(stock.emoji.animated, metered.emoji.animated)
        assertEquals(stock.gif.resultLimit, metered.gif.resultLimit)
        assertEquals(stock.ai.provider, metered.ai.provider)
    }

    @Test
    fun `allowing a feature keeps its setting`() {
        val settings = KeyboardSettings(
            dictionaryAutoLookup = true,
            smartChips = SmartChipSettings(weather = true),
            dataSaver = DataSaverSettings(
                dictionaryLookup = MeteredPolicy.ALLOW,
                weatherChip = MeteredPolicy.ALLOW,
            ),
        ).onMeteredNetwork()
        assertTrue(settings.dictionaryAutoLookup)
        assertTrue(settings.smartChips.weather)
    }
}
