package com.wasimaster.wmkeyboard.ime.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The infrared encoders, Pronto and raw codes, and Flipper `.ir` files. */
class IrCodesTest {

    /** Reads a pulse-distance frame back: the bytes its spaces spell, least significant bit first. */
    private fun bytesOf(signal: IrSignal, bits: Int): IntArray {
        val out = IntArray(bits / 8)
        for (i in 0 until bits) {
            val space = signal.pattern[3 + 2 * i]
            if (space > 1000) out[i / 8] = out[i / 8] or (1 shl (i % 8))
        }
        return out
    }

    @Test fun `NEC sends the address, its inverse, the command and its inverse`() {
        // LG's power button, 0x20DF10EF as LIRC writes it.
        val signal = IrCodes.parse("NEC:4:8")!!
        assertEquals(38_000, signal.carrierHz)
        assertEquals(9000, signal.pattern[0])
        assertEquals(4500, signal.pattern[1])
        assertEquals(2 + 64 + 1, signal.pattern.size)
        assertArrayEquals(intArrayOf(0x04, 0xFB, 0x08, 0xF7), bytesOf(signal, 32))
    }

    @Test fun `NECext sends both bytes of address and command as written`() {
        val signal = IrCodes.parse("NECext:7f00:ea15")!!
        assertArrayEquals(intArrayOf(0x00, 0x7F, 0x15, 0xEA), bytesOf(signal, 32))
    }

    @Test fun `Samsung32 repeats the address and inverts the command`() {
        val signal = IrCodes.parse("Samsung32:7:2")!!
        assertEquals(4500, signal.pattern[0])
        assertEquals(4500, signal.pattern[1])
        assertArrayEquals(intArrayOf(0x07, 0x07, 0x02, 0xFD), bytesOf(signal, 32))
    }

    @Test fun `SIRC goes out three times at 40 kHz`() {
        val signal = IrCodes.parse("SIRC:1:15")!!
        assertEquals(40_000, signal.carrierHz)
        assertEquals(3, signal.pattern.count { it == 2400 })
        // Each frame is 45 ms from start to start.
        assertTrue(signal.durationUs in 90_000L..135_000L)
    }

    @Test fun `RC5, RC6 and Kaseikyo encode`() {
        assertEquals(36_000, IrCodes.parse("RC5:0:c")!!.carrierHz)
        assertEquals(36_000, IrCodes.parse("RC6:0:c")!!.carrierHz)
        assertEquals(2666, IrCodes.parse("RC6:0:c")!!.pattern[0])
        val panasonic = IrCodes.parse("Kaseikyo:802002:3d")!!
        assertEquals(2 + 96 + 1, panasonic.pattern.size)
        assertEquals(3456, panasonic.pattern[0])
    }

    @Test fun `every pattern starts with a mark and ends with one`() {
        for (code in listOf("NEC:4:8", "RC5:0:c", "RC5X:0:c", "RC6:0:c", "SIRC20:1a:2", "RCA:f:54", "Kaseikyo:802002:3d")) {
            val signal = IrCodes.parse(code)!!
            assertTrue(code, signal.pattern.size % 2 == 1)
            assertTrue(code, signal.pattern.all { it > 0 })
        }
    }

    @Test fun `an unknown protocol or bad hex is no code`() {
        assertNull(IrCodes.parse("Bogus:1:2"))
        assertNull(IrCodes.parse("NEC:zz:2"))
        assertNull(IrCodes.parse(""))
    }

    @Test fun `Pronto hex becomes timings at its carrier`() {
        val signal = IrCodes.pronto("0000 006D 0002 0000 0010 0020 0010 0010")!!
        assertTrue(signal.carrierHz in 37_900..38_100)
        // The once sequence; its last space is dropped.
        assertEquals(3, signal.pattern.size)
        assertTrue(signal.pattern[1] in 830..850)
        assertEquals(signal, IrCodes.parse("0000 006D 0002 0000 0010 0020 0010 0010"))
    }

    @Test fun `raw codes round trip`() {
        val code = IrCodes.rawCode(38_000, listOf(9000, 4500, 560, 560, 560))
        assertEquals(IrSignal(38_000, intArrayOf(9000, 4500, 560, 560, 560)), IrCodes.parse(code))
    }

    @Test fun `a Flipper file sorts buttons onto the pad and the extras`() {
        val file = """
            Filetype: IR signals file
            Version: 1
            #
            name: Power
            type: parsed
            protocol: NEC
            address: 04 00 00 00
            command: 08 00 00 00
            #
            name: Vol_up
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: 9000 4500 560 560 560
            #
            name: Netflix
            type: parsed
            protocol: NECext
            address: 00 7F 00 00
            command: 15 EA 00 00
            #
            name: Broken
            type: parsed
            protocol: Unknown
            address: 00 00 00 00
            command: 00 00 00 00
        """.trimIndent()
        val imported = IrCodes.parseFlipper(file)
        assertEquals("NEC:4:8", imported.codes[RemoteKey.POWER.name])
        assertNotNull(imported.codes[RemoteKey.VOL_UP.name])
        assertEquals(listOf("Netflix"), imported.extra.map { it.name })
        assertEquals("NECext:7f00:ea15", imported.extra.single().code)
    }

    @Test fun `button names match without case or punctuation`() {
        assertEquals(RemoteKey.VOL_DOWN, IrCodes.keyFor("Vol_dn"))
        assertEquals(RemoteKey.CH_UP, IrCodes.keyFor("CH+".replace("+", "_up")))
        assertEquals(RemoteKey.BACK, IrCodes.keyFor("RETURN"))
        assertEquals(RemoteKey.OK, IrCodes.keyFor("Enter"))
        assertNull(IrCodes.keyFor("Netflix"))
    }
}
