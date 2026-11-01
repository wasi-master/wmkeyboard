package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A number, phone, date or time field offers the snippets it can take, as chips (#1020). */
@RunWith(RobolectricTestRunner::class)
class KeypadSnippetOfferTest {

    private val keypads = listOf(FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATE, FieldKind.TIME)

    private fun keyboard(kind: FieldKind, text: String): Pair<GlideKeyboard, RecordingEditor> {
        val (service, editor, state) = glideKeyboard(
            state = glideReadyState(fieldKind = kind, fieldNoSuggestions = false),
        )
        val store = SnippetStore(null).apply { add("Code", text) }
        WMKeyboardService::class.java.getDeclaredField("snippetStore").apply {
            isAccessible = true
            set(service, store)
        }
        WMKeyboardService::class.java
            .getDeclaredMethod("refreshSnippetOffer", KeyboardUiState::class.java)
            .apply { isAccessible = true }
            .invoke(service, state)
        return service to editor
    }

    @Test
    fun `digits are offered, and a tap types them`() {
        for (kind in keypads) {
            val (service, editor) = keyboard(kind, "0123")
            assertEquals(kind.name, "0123", service.uiState.value.snippetOffers?.visibleChips()?.single()?.text)
            service.onSnippetOfferPick(0)
            assertEquals(kind.name, "0123", editor.text.toString())
        }
    }

    @Test
    fun `an expansion with letters in it is not offered`() {
        for (kind in keypads) {
            val (service, editor) = keyboard(kind, "call me")
            assertNull(kind.name, service.uiState.value.snippetOffers)
            service.onSnippetOfferPick(0)
            assertEquals(kind.name, "", editor.text.toString())
        }
    }
}
