package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class CurlyApostropheTest {
    @Test fun curlsOnlyBetweenLetters() {
        assertEquals("I’m", CurlyApostropheConnection.curl("I'm"))
        assertEquals("rock’n’roll", CurlyApostropheConnection.curl("rock'n'roll"))
        assertEquals("'", CurlyApostropheConnection.curl("'"))
        assertEquals("'hi' dogs'", CurlyApostropheConnection.curl("'hi' dogs'"))
        assertEquals("l’albero", CurlyApostropheConnection.curl("l'albero"))
    }
}
