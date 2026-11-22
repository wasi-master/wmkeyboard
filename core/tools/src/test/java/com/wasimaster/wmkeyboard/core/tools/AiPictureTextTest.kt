package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPictureTextTest {

    @Test
    fun `a transcript becomes turns by who sent them`() {
        val turns = AiPictureText.chatTurns(
            """
            Them: are you coming tonight?
            Me: not sure yet
            **Them:** let me know by 6
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ReplyTurn(false, "are you coming tonight?"),
                ReplyTurn(true, "not sure yet"),
                ReplyTurn(false, "let me know by 6"),
            ),
            turns,
        )
    }

    @Test
    fun `an unmarked line continues the message above`() {
        val turns = AiPictureText.chatTurns("Me: first line\nsecond line\nThem: ok")
        assertEquals(listOf(ReplyTurn(true, "first line\nsecond line"), ReplyTurn(false, "ok")), turns)
    }

    @Test
    fun `an unmarked first line is the other person's`() {
        assertEquals(listOf(ReplyTurn(false, "A photo of a beach.")), AiPictureText.chatTurns("A photo of a beach."))
    }

    @Test
    fun `reasoning and blank lines are dropped`() {
        val turns = AiPictureText.chatTurns("<think>left is them</think>\n\nThem: hi\n\n- Me: hey")
        assertEquals(listOf(ReplyTurn(false, "hi"), ReplyTurn(true, "hey")), turns)
    }

    @Test
    fun `a question goes into the request`() {
        assertEquals("Describe this picture.", AiPictureText.describeRequest(" "))
        assertTrue("what colour" in AiPictureText.describeRequest("what colour is the car"))
    }
}
