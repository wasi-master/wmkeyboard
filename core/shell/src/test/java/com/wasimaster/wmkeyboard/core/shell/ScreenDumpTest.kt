package com.wasimaster.wmkeyboard.core.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenDumpTest {

    private fun node(text: String, cls: String, l: Int, t: Int, r: Int, b: Int) =
        "text=\"$text\" resource-id=\"\" class=\"$cls\" package=\"com.chat\" content-desc=\"\" " +
            "checkable=\"false\" bounds=\"[$l,$t][$r,$b]\""

    @Test
    fun parsesTextClassAndBounds() {
        val lines = ScreenDump.parse(node("Tom &amp; Jerry&#10;hi", "android.widget.TextView", 10, 20, 300, 60))
        assertEquals(1, lines.size)
        assertEquals("Tom & Jerry\nhi", lines[0].text)
        assertEquals("android.widget.TextView", lines[0].className)
        assertEquals(listOf(10, 20, 300, 60), listOf(lines[0].left, lines[0].top, lines[0].right, lines[0].bottom))
    }

    @Test
    fun guessesSidesAndDropsChrome() {
        val raw = listOf(
            node("Alex", "android.widget.TextView", 150, 60, 400, 120), // top bar
            node("are you coming tonight?", "android.widget.TextView", 40, 400, 700, 460),
            node("9:41 PM", "android.widget.TextView", 40, 470, 140, 500),
            node("yes, around 8", "android.widget.TextView", 600, 600, 1040, 660),
            node("my half-typed draft", "android.widget.EditText", 40, 2100, 900, 2180),
        ).joinToString("\n")
        val messages = ScreenDump.messages(ScreenDump.parse(raw), screenWidth = 1080, screenHeight = 2400)
        assertEquals(
            listOf(false to "are you coming tonight?", true to "yes, around 8"),
            messages,
        )
    }

    @Test
    fun keepsOnlyTheNewest() {
        val raw = (1..20).joinToString("\n") { node("message $it", "android.widget.TextView", 40, 300 + it * 50, 700, 340 + it * 50) }
        val messages = ScreenDump.messages(ScreenDump.parse(raw), 1080, 2400, max = 3)
        assertEquals(listOf("message 18", "message 19", "message 20"), messages.map { it.second })
    }
}
