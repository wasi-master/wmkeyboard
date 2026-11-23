package com.wasimaster.wmkeyboard.core.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HandwritingOnyxSettingsTest {
    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `Onyx raw ink is off until explicitly enabled`() = runBlocking {
        assertFalse(repository().settings.first().handwritingInk.onyxPen)
    }

    @Test
    fun `the Onyx choice survives storage`() = runBlocking {
        val repository = repository()
        repository.setHandwritingOnyxPen(true)
        assertTrue(repository.settings.first().handwritingInk.onyxPen)
        repository.setHandwritingOnyxPen(false)
        assertFalse(repository.settings.first().handwritingInk.onyxPen)
    }
}
