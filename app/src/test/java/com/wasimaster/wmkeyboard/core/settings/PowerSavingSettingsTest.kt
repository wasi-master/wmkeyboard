package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSavingSettingsTest {

    private val full = DevicePowerState(batteryPercent = 90, charging = false, systemSaver = false)
    private val low = DevicePowerState(batteryPercent = 12, charging = false, systemSaver = false)

    @Test
    fun `manual wins over everything`() {
        val config = PowerSavingSettings(manual = true, trigger = PowerSavingTrigger.OFF)
        assertTrue(config.appliesTo(full))
        // Even on the charger: someone who flipped it by hand meant it.
        assertTrue(config.appliesTo(full.copy(charging = true)))
    }

    @Test
    fun `the default never turns on by itself`() {
        val config = PowerSavingSettings()
        assertEquals(PowerSavingTrigger.OFF, config.trigger)
        assertFalse(config.appliesTo(full))
        assertFalse("a low battery must not trigger the default", config.appliesTo(low))
        assertFalse(
            "the system battery saver must not trigger the default",
            config.appliesTo(low.copy(systemSaver = true)),
        )
    }

    @Test
    fun `the system saver trigger follows the system battery saver only`() {
        val config = PowerSavingSettings(trigger = PowerSavingTrigger.SYSTEM_SAVER)
        assertFalse(config.appliesTo(full))
        assertFalse("a low battery alone is a different trigger", config.appliesTo(low))
        assertTrue(config.appliesTo(full.copy(systemSaver = true)))
    }

    @Test
    fun `the low-battery trigger uses the user's percentage`() {
        val config = PowerSavingSettings(trigger = PowerSavingTrigger.LOW_BATTERY, batteryPercent = 20)
        assertTrue(config.appliesTo(low))
        assertTrue("the threshold itself counts as low", config.appliesTo(low.copy(batteryPercent = 20)))
        assertFalse(config.appliesTo(low.copy(batteryPercent = 21)))
        assertFalse("the system saver is a different trigger", config.appliesTo(full.copy(systemSaver = true)))
    }

    @Test
    fun `an unread battery level is never low`() {
        val config = PowerSavingSettings(trigger = PowerSavingTrigger.EITHER)
        assertFalse(config.appliesTo(DevicePowerState()))
    }

    @Test
    fun `charging stands the automatic triggers down`() {
        val config = PowerSavingSettings(trigger = PowerSavingTrigger.EITHER)
        assertTrue(config.appliesTo(low))
        assertFalse(config.appliesTo(low.copy(charging = true)))
        assertTrue(
            "opting out of the charging exception keeps it on",
            config.copy(offWhileCharging = false).appliesTo(low.copy(charging = true)),
        )
    }

    @Test
    fun `the defaults drop something`() {
        assertTrue(PowerSavingSettings().dropsAnything)
        assertFalse(
            PowerSavingSettings(
                dropHaptics = false, dropKeySound = false, dropAnimations = false,
                dropGlideTrail = false, dropKeyPopup = false, dropGestureTyping = false,
                dropEmojiPrediction = false, dropSmartChips = false,
                dropOctopus = false,
                dropBackgroundNetwork = false, dropScreenshotWatch = false,
                dropOnDeviceModels = false, dropMediaPin = false,
                dropGlassBlur = false,
                dropMovingThemes = false, dropFloatingButton = false,
            ).dropsAnything,
        )
    }

    @Test
    fun `power saving takes back the words on the keys`() {
        val on = KeyboardSettings(octopus = OctopusSettings(enabled = true, density = 12))
        val saving = on.underPowerSaving()
        assertFalse("the octopus is off", saving.octopus.enabled)
        assertEquals(
            "and nothing else about it is rewritten, so the charger brings it back as it was",
            12, saving.octopus.density,
        )
        assertTrue(
            "opting out keeps it",
            on.copy(powerSaving = PowerSavingSettings(dropOctopus = false))
                .underPowerSaving().octopus.enabled,
        )
    }

    @Test
    fun `the view switches off exactly what is asked for`() {
        val settings = KeyboardSettings(powerSaving = PowerSavingSettings())
        val saving = settings.underPowerSaving()
        assertFalse(saving.haptics.enabled)
        assertTrue(saving.reduceMotion)
        assertEquals(0f, saving.gesture.trailOpacity, 0f)
        assertFalse(saving.clipboard.linkPreviews)
        assertFalse(saving.clipboard.userScreenshots)
        assertEquals("system", saving.whisper.engine)
        // Off by default, so these survive.
        assertTrue(saving.gestureTyping)
        assertTrue(saving.popup.enabled)
        // The cheap typing helpers too: pattern matches on the current word
        // cost nothing worth a feature that looks broken when it goes.
        assertTrue(saving.emojiPrediction)
        assertTrue(saving.smartSuggestions)
    }

    @Test
    fun `a drop that is switched off leaves its feature alone`() {
        val settings = KeyboardSettings(
            powerSaving = PowerSavingSettings(dropHaptics = false, dropAnimations = false),
        )
        val saving = settings.underPowerSaving()
        assertTrue(saving.haptics.enabled)
        assertFalse(saving.reduceMotion)
    }

    @Test
    fun `typing statistics stop only when the opt-in drop asks`() {
        // Off by default: saving power must not silently stop the counters.
        assertTrue(KeyboardSettings().underPowerSaving().typingStatsEnabled)
        val opted = KeyboardSettings(powerSaving = PowerSavingSettings(dropTypingStats = true))
        assertFalse(opted.underPowerSaving().typingStatsEnabled)
        assertTrue("the stored setting is untouched", opted.typingStatsEnabled)
    }

    @Test
    fun `reduced motion the user chose survives power saving ending`() {
        // The view ORs into reduceMotion rather than setting it, so an
        // accessibility choice is never quietly turned off by the transform.
        val settings = KeyboardSettings(reduceMotion = true, powerSaving = PowerSavingSettings())
        assertTrue(settings.underPowerSaving().reduceMotion)
        assertTrue(settings.reduceMotion)
    }

    @Test
    fun `handwriting falls back only when it is the swipe action`() {
        val writing = KeyboardSettings(letterSwipeAction = LetterSwipeAction.HANDWRITE)
        assertEquals(LetterSwipeAction.TYPE_WORDS, writing.underPowerSaving().letterSwipeAction)
        val typing = KeyboardSettings(letterSwipeAction = LetterSwipeAction.TYPE_WORDS)
        assertEquals(LetterSwipeAction.TYPE_WORDS, typing.underPowerSaving().letterSwipeAction)
    }

    @Test
    fun `the media toolbar pin drops by default and comes straight back`() {
        // On by default here, unlike the typing counters: keeping the pin
        // accurate costs a live media-session listener for as long as the
        // keyboard is up, which is exactly the kind of background work a low
        // battery should not be paying for.
        val settings = KeyboardSettings()
        assertTrue(settings.mediaControl.pinWhilePlaying)
        assertFalse(settings.underPowerSaving().mediaControl.pinWhilePlaying)
        assertTrue("the stored setting is untouched", settings.mediaControl.pinWhilePlaying)
        val kept = KeyboardSettings(powerSaving = PowerSavingSettings(dropMediaPin = false))
        assertTrue(kept.underPowerSaving().mediaControl.pinWhilePlaying)
    }

    @Test
    fun `dropping the pin leaves the music-player list alone`() {
        // The allowlist is the user's answer to "what is music", not a cost.
        // Power saving stops the pin, and turning it off has to bring back the
        // same list rather than a reseeded one.
        val picked = KeyboardSettings(
            mediaControl = MediaControlSettings(musicApps = setOf("com.example.player")),
        )
        assertEquals(
            setOf("com.example.player"),
            picked.underPowerSaving().mediaControl.musicApps,
        )
    }

    @Test
    fun `the floating button hides only when asked and comes back where it was`() {
        val on = KeyboardSettings(summonButton = SummonButtonSettings(enabled = true, yFraction = 0.3f))
        assertTrue("off by default: some people open the keyboard with it", on.underPowerSaving().summonButton.enabled)
        val opted = on.copy(powerSaving = PowerSavingSettings(dropFloatingButton = true))
        val saving = opted.underPowerSaving()
        assertFalse(saving.summonButton.enabled)
        assertEquals("its place is kept", 0.3f, saving.summonButton.yFraction, 0f)
        assertTrue("the stored switch is untouched", opted.summonButton.enabled)
    }

    @Test
    fun `dropping animations stills the floating button's menu, which reduce motion cannot reach`() {
        val on = KeyboardSettings(summonButton = SummonButtonSettings(enabled = true))
        assertEquals(FloatingMenuAnimation.NONE, on.underPowerSaving().summonButton.menuAnimation)
        val kept = on.copy(powerSaving = PowerSavingSettings(dropAnimations = false))
        assertEquals(FloatingMenuAnimation.SPRING, kept.underPowerSaving().summonButton.menuAnimation)
    }

    @Test
    fun `moving themes stop by default without reducing every other motion`() {
        val settings = KeyboardSettings(powerSaving = PowerSavingSettings(dropAnimations = false))
        val saving = settings.underPowerSaving()
        assertFalse(saving.accessibility.themeMotion)
        assertFalse("panels keep their transitions", saving.reduceMotion)
        assertTrue("the stored settings never carry it", settings.accessibility.themeMotion)
        val kept = settings.copy(powerSaving = PowerSavingSettings(dropMovingThemes = false))
        assertTrue(kept.underPowerSaving().accessibility.themeMotion)
    }

    @Test
    fun `the transform never touches the stored configuration`() {
        val settings = KeyboardSettings()
        val saving = settings.underPowerSaving()
        assertEquals(settings.powerSaving, saving.powerSaving)
        assertTrue("the original is untouched", settings.haptics.enabled)
    }
}
