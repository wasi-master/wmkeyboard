package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pieces a Thumb-Key board needs (issue #1409): an arm's out-and-back
 * action, the space bar's tap cycle and the Shift off action, read from a
 * layout file the way the shipped grids are.
 */
class FlickReturnTest {

    private val caseUp = KeyAlternate(KeyAction.Edit(TextEditAction.CASE_UP))

    @Test
    fun `a return action is the key the out-and-back presses`() {
        val key = Key(
            "e",
            flickActions = mapOf(FlickDirection.UP to KeyAlternate(KeyAction.Shift)),
            flickReturn = mapOf(FlickDirection.UP to caseUp, FlickDirection.LEFT to caseUp),
        )
        assertEquals(KeyAction.Edit(TextEditAction.CASE_UP), key.flickReturnKey(FlickDirection.UP)!!.action)
        // No arm that way, so nothing to come back from.
        assertNull(key.flickReturnKey(FlickDirection.LEFT))
        assertNull(key.flickReturnKey(FlickDirection.DOWN))
    }

    @Test
    fun `the file format carries the return, the cycle and shift off`() {
        val text = """
            {
              "format": "wmkeyboard-layout",
              "version": 1,
              "layout": {
                "id": "test_thumbkey",
                "name": "Test",
                "langId": "en",
                "layers": {
                  "letters": {
                    "rows": [
                      [
                        {
                          "label": "e",
                          "flick": { "left": "t" },
                          "flickActions": {
                            "up": { "action": { "type": "shift" } },
                            "down": { "action": { "type": "shift_off" } }
                          },
                          "flickReturn": {
                            "up": { "action": { "type": "edit", "op": "CASE_UP" } },
                            "down": { "action": { "type": "edit", "op": "CASE_DOWN" } }
                          }
                        },
                        { "label": "a", "spaceCycle": [", "] }
                      ],
                      [
                        { "label": " ", "action": { "type": "space" }, "spaceCycle": [", ", ". ", ""] }
                      ]
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val layout = LayoutFile.decode(text)!!.layout
        val rows = layout.layer(LayoutLayer.LETTERS)!!.rows
        val e = rows.flatten().first { it.label == "e" }
        assertEquals(KeyAction.ShiftOff, e.flickActions.getValue(FlickDirection.DOWN).action)
        assertEquals(KeyAction.Edit(TextEditAction.CASE_DOWN), e.flickReturn.getValue(FlickDirection.DOWN).action)
        // A cycle means nothing off the space bar, and an empty entry would
        // swallow the space it replaces: repair drops both.
        assertTrue(rows.flatten().first { it.label == "a" }.spaceCycle.isEmpty())
        assertEquals(listOf(", ", ". "), rows.flatten().first { it.action == KeyAction.Space }.spaceCycle)
    }
}
