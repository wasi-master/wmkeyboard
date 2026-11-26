package com.wasimaster.wmkeyboard.ime.remote

/**
 * One infrared burst as Android's `ConsumerIrManager.transmit` takes it: a
 * carrier frequency and alternating on and off times in microseconds, starting
 * with on.
 */
class IrSignal(val carrierHz: Int, val pattern: IntArray) {
    val durationUs: Long get() = pattern.fold(0L) { total, us -> total + us }

    override fun equals(other: Any?): Boolean =
        other is IrSignal && other.carrierHz == carrierHz && other.pattern.contentEquals(pattern)

    override fun hashCode(): Int = 31 * carrierHz + pattern.contentHashCode()

    override fun toString(): String = "IrSignal($carrierHz Hz, ${pattern.joinToString(",")})"
}

/**
 * Infrared codes as text, and the protocols that turn a code into timings.
 *
 * A code is one of three shapes:
 * - `protocol:address:command`, hex, with the address and command packed the
 *   way Flipper Zero's `.ir` files write them (their bytes read little-endian);
 *   the shipped TV set and imported `.ir` files use it;
 * - `raw:carrierHz:on,off,on,…` in microseconds, for a capture;
 * - Pronto hex (`0000 006D 0022 0002 …`), the form most code lists are shared in.
 *
 * The encoders are written from the protocols' published timings: NEC and its
 * 16-bit form, Samsung's 32-bit variant, Sony SIRC (12, 15 and 20 bits),
 * Philips RC5, RC5X and RC6 mode 0, Panasonic's Kaseikyo and RCA.
 */
object IrCodes {

    /** The protocol names a parsed code may carry, as Flipper's files spell them. */
    val Protocols: Set<String> = setOf(
        "NEC", "NECext", "Samsung32", "SIRC", "SIRC15", "SIRC20", "RC5", "RC5X", "RC6", "Kaseikyo", "RCA",
    )

    /** The longest burst Android accepts in one transmit call. */
    private const val MAX_TOTAL_US = 2_000_000L

    fun parse(code: String): IrSignal? {
        val text = code.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("raw:")) return raw(text)
        if (PRONTO_START.containsMatchIn(text)) return pronto(text)
        val parts = text.split(':')
        if (parts.size != 3) return null
        val address = parts[1].toLongOrNull(16) ?: return null
        val command = parts[2].toLongOrNull(16) ?: return null
        return encode(parts[0], address, command)
    }

    fun isValid(code: String): Boolean = parse(code) != null

    /** `raw:carrier:t,t,t` as written by [rawCode]. */
    private fun raw(text: String): IrSignal? {
        val parts = text.split(':', limit = 3)
        if (parts.size != 3) return null
        val carrier = parts[1].toIntOrNull() ?: return null
        val times = parts[2].split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
        return fromTimes(carrier, times)
    }

    fun rawCode(carrierHz: Int, times: List<Int>): String = "raw:$carrierHz:" + times.joinToString(",")

    /** A signal from timings that may start with a gap or run long: the gap goes, the tail is cut at Android's limit. */
    fun fromTimes(carrierHz: Int, times: List<Int>): IrSignal? {
        if (carrierHz !in MIN_CARRIER..MAX_CARRIER) return null
        val pulses = Pulses()
        times.forEachIndexed { i, us -> if (i % 2 == 0) pulses.mark(us) else pulses.space(us) }
        return pulses.build(carrierHz)
    }

    /**
     * Pronto hex: a `0000` (learned, modulated) code. The second word is the
     * carrier as a count of 0.241246 µs units; then the lengths of the once
     * and repeat sequences in pairs, then the pairs in carrier periods. The
     * once sequence is sent, or the repeat sequence when there is none.
     */
    fun pronto(text: String): IrSignal? {
        val words = text.trim().split(WHITESPACE).mapNotNull { it.toIntOrNull(16) }
        if (words.size < 6 || words[0] != 0) return null
        val freqWord = words[1]
        if (freqWord <= 0) return null
        val periodUs = freqWord * PRONTO_UNIT_US
        val carrier = (1_000_000.0 / periodUs).toInt()
        val once = words[2] * 2
        val repeat = words[3] * 2
        if (words.size < 4 + once + repeat) return null
        val body = if (once > 0) words.subList(4, 4 + once) else words.subList(4 + once, 4 + once + repeat)
        val times = body.map { (it * periodUs).toInt() }
        return fromTimes(carrier, times)
    }

    fun encode(protocol: String, address: Long, command: Long): IrSignal? {
        val a = address
        val c = command
        return when (protocol) {
            "NEC" -> pulseDistance(
                NEC_CARRIER, 9000, 4500, 560, 560, 1690, 560,
                bytes(a and 0xFF, a.inv() and 0xFF, c and 0xFF, c.inv() and 0xFF), 32,
            )
            "NECext" -> pulseDistance(
                NEC_CARRIER, 9000, 4500, 560, 560, 1690, 560,
                bytes(a and 0xFF, (a shr 8) and 0xFF, c and 0xFF, (c shr 8) and 0xFF), 32,
            )
            "Samsung32" -> pulseDistance(
                NEC_CARRIER, 4500, 4500, 550, 550, 1650, 550,
                bytes(a and 0xFF, a and 0xFF, c and 0xFF, c.inv() and 0xFF), 32,
            )
            "SIRC" -> sirc((c and 0x7F) or ((a and 0x1F) shl 7), 12)
            "SIRC15" -> sirc((c and 0x7F) or ((a and 0xFF) shl 7), 15)
            "SIRC20" -> sirc((c and 0x7F) or ((a and 0x1FFF) shl 7), 20)
            "RC5", "RC5X" -> rc5(protocol == "RC5X", a, c)
            "RC6" -> rc6(a, c)
            "Kaseikyo" -> kaseikyo(a, c)
            "RCA" -> {
                val value = (a and 0xF) or ((c and 0xFF) shl 4) or ((a.inv() and 0xF) shl 12) or ((c.inv() and 0xFF) shl 16)
                pulseDistance(
                    NEC_CARRIER, 4000, 4000, 500, 1000, 2000, 500,
                    bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF), 24,
                )
            }
            else -> null
        }
    }

    private fun bytes(vararg values: Long): IntArray = IntArray(values.size) { values[it].toInt() and 0xFF }

    /** A header, then each bit as a mark and a short or long space, least significant bit of each byte first, then a stop mark. */
    private fun pulseDistance(
        carrier: Int,
        headerMark: Int,
        headerSpace: Int,
        bitMark: Int,
        zeroSpace: Int,
        oneSpace: Int,
        stopMark: Int,
        data: IntArray,
        bits: Int,
    ): IrSignal? {
        val p = Pulses()
        p.mark(headerMark)
        p.space(headerSpace)
        for (i in 0 until bits) {
            val bit = (data[i / 8] shr (i % 8)) and 1
            p.mark(bitMark)
            p.space(if (bit == 1) oneSpace else zeroSpace)
        }
        p.mark(stopMark)
        return p.build(carrier)
    }

    /** Sony: the bit is in the mark's length, sent three times on a 45 ms beat, as Sony sets do. */
    private fun sirc(value: Long, bits: Int): IrSignal? {
        val frame = Pulses()
        frame.mark(2400)
        frame.space(600)
        for (i in 0 until bits) {
            frame.mark(if ((value shr i) and 1L == 1L) 1200 else 600)
            frame.space(600)
        }
        val once = frame.times()
        val length = once.sum()
        val p = Pulses()
        repeat(SIRC_FRAMES) { n ->
            once.forEachIndexed { i, us -> if (i % 2 == 0) p.mark(us) else p.space(us) }
            if (n < SIRC_FRAMES - 1) p.space((SIRC_PERIOD_US - length).coerceAtLeast(600))
        }
        return p.build(SIRC_CARRIER)
    }

    /** Philips RC5: 14 bits, most significant first, a one as space-then-mark, a zero as mark-then-space. */
    private fun rc5(extended: Boolean, address: Long, command: Long): IrSignal? {
        val bits = ArrayList<Int>(14)
        bits += 1
        bits += if (extended) 0 else 1
        bits += 0 // toggle
        for (i in 4 downTo 0) bits += ((address shr i) and 1L).toInt()
        for (i in 5 downTo 0) bits += ((command shr i) and 1L).toInt()
        val p = Pulses()
        for (bit in bits) {
            if (bit == 1) {
                p.space(RC5_HALF)
                p.mark(RC5_HALF)
            } else {
                p.mark(RC5_HALF)
                p.space(RC5_HALF)
            }
        }
        return p.build(RC_CARRIER)
    }

    /** Philips RC6 mode 0: a leader, then a one as mark-then-space; the toggle bit is twice as long. */
    private fun rc6(address: Long, command: Long): IrSignal? {
        val p = Pulses()
        p.mark(2666)
        p.space(889)
        fun bit(value: Int, half: Int) {
            if (value == 1) {
                p.mark(half)
                p.space(half)
            } else {
                p.space(half)
                p.mark(half)
            }
        }
        bit(1, RC6_HALF) // start
        repeat(3) { bit(0, RC6_HALF) } // mode 0
        bit(0, RC6_HALF * 2) // toggle
        for (i in 7 downTo 0) bit(((address shr i) and 1L).toInt(), RC6_HALF)
        for (i in 7 downTo 0) bit(((command shr i) and 1L).toInt(), RC6_HALF)
        return p.build(RC_CARRIER)
    }

    /**
     * Panasonic's Kaseikyo, 48 bits: the vendor (two bytes), a parity nibble with
     * the first genre, the second genre with the command's low nibble, the
     * command's high bits with the id, and a check byte. The address packs id,
     * vendor and genres the way Flipper's files do.
     */
    private fun kaseikyo(address: Long, command: Long): IrSignal? {
        val id = ((address shr 24) and 3).toInt()
        val vendor = ((address shr 8) and 0xFFFF).toInt()
        val genre1 = ((address shr 4) and 0xF).toInt()
        val genre2 = (address and 0xF).toInt()
        val cmd = (command and 0x3FF).toInt()
        val d = IntArray(6)
        d[0] = vendor and 0xFF
        d[1] = (vendor shr 8) and 0xFF
        var parity = d[0] xor d[1]
        parity = (parity and 0xF) xor (parity shr 4)
        d[2] = (parity and 0xF) or (genre1 shl 4)
        d[3] = (genre2 and 0xF) or ((cmd and 0xF) shl 4)
        d[4] = (id shl 6) or ((cmd shr 4) and 0x3F)
        d[5] = d[2] xor d[3] xor d[4]
        for (i in d.indices) d[i] = d[i] and 0xFF
        return pulseDistance(NEC_CARRIER, 3456, 1728, 432, 432, 1296, 432, d, 48)
    }

    /**
     * The usual names of a remote's buttons, as Flipper's files and most code
     * lists write them, matched without case or punctuation.
     */
    private val Aliases: Map<RemoteKey, List<String>> = mapOf(
        RemoteKey.POWER to listOf("power", "powertoggle", "pwr", "onoff"),
        RemoteKey.VOL_UP to listOf("volup", "volumeup", "volplus"),
        RemoteKey.VOL_DOWN to listOf("voldn", "voldown", "volumedown", "volminus"),
        RemoteKey.MUTE to listOf("mute"),
        RemoteKey.CH_UP to listOf("chnext", "chup", "channelup", "chplus"),
        RemoteKey.CH_DOWN to listOf("chprev", "chdn", "chdown", "channeldown", "chminus"),
        RemoteKey.INPUT to listOf("source", "input", "inputs", "av", "tvav", "avtv", "inputsource"),
        RemoteKey.UP to listOf("up"),
        RemoteKey.DOWN to listOf("down"),
        RemoteKey.LEFT to listOf("left"),
        RemoteKey.RIGHT to listOf("right"),
        RemoteKey.OK to listOf("ok", "enter", "select", "center"),
        RemoteKey.BACK to listOf("return", "back", "exit"),
        RemoteKey.HOME to listOf("home", "smarthub", "smart", "apps"),
        RemoteKey.MENU to listOf("menu", "settings", "tools"),
    )

    /** Which pad button an imported button's name means, if any. */
    fun keyFor(name: String): RemoteKey? {
        val n = name.lowercase().filter { it.isLetterOrDigit() }
        if (n.isEmpty()) return null
        return Aliases.entries.firstOrNull { (_, names) -> n in names }?.key
    }

    /** An imported file's buttons, sorted onto the pad and the extras. */
    class Imported(val codes: Map<String, String>, val extra: List<IrButton>) {
        val isEmpty: Boolean get() = codes.isEmpty() && extra.isEmpty()
    }

    /**
     * A Flipper Zero `.ir` file: blocks of `name:`, `type: parsed` with
     * protocol/address/command, or `type: raw` with frequency/data. The first
     * button that means a pad key takes it (a later alias keeps its own chip);
     * blocks this build cannot send are dropped.
     */
    fun parseFlipper(text: String): Imported {
        val blocks = ArrayList<Map<String, String>>()
        var current: MutableMap<String, String>? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                current?.let { blocks += it }
                current = null
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key == "name") {
                current?.let { blocks += it }
                current = mutableMapOf("name" to value)
            } else {
                current?.put(key, value)
            }
        }
        current?.let { blocks += it }
        val codes = LinkedHashMap<String, String>()
        val extra = ArrayList<IrButton>()
        for (b in blocks) {
            val name = b["name"]?.takeIf { it.isNotBlank() } ?: continue
            val code = flipperCode(b) ?: continue
            val key = keyFor(name)
            if (key != null && key.name !in codes) codes[key.name] = code else extra += IrButton(name.replace('_', ' '), code)
        }
        return Imported(codes, extra.distinctBy { it.name.lowercase() }.take(MAX_EXTRA))
    }

    private fun flipperCode(b: Map<String, String>): String? = when (b["type"]?.lowercase()) {
        "parsed" -> {
            val protocol = b["protocol"]
            val address = b["address"]?.let(::littleEndian)
            val command = b["command"]?.let(::littleEndian)
            if (protocol in Protocols && address != null && command != null) {
                "$protocol:${address.toString(16)}:${command.toString(16)}"
            } else {
                null
            }
        }
        "raw" -> {
            val carrier = b["frequency"]?.toDoubleOrNull()?.toInt() ?: 38000
            val times = b["data"]?.split(WHITESPACE)?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (times.isNotEmpty() && fromTimes(carrier, times) != null) rawCode(carrier, times) else null
        }
        else -> null
    }

    /** `07 00 00 00` → 7: Flipper writes its fields as little-endian bytes. */
    private fun littleEndian(hex: String): Long? {
        val parts = hex.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 8) return null
        var value = 0L
        parts.forEachIndexed { i, part ->
            val byte = part.toIntOrNull(16) ?: return null
            value = value or (byte.toLong() and 0xFF shl (8 * i))
        }
        return value
    }

    /**
     * Builds a pattern from marks and spaces, joining two of a kind, dropping a
     * leading space (the line is idle anyway) and a trailing one (Android needs
     * none), and cutting at the two seconds Android allows.
     */
    private class Pulses {
        private val out = ArrayList<Int>()
        private var lastOn = false
        private var total = 0L

        fun mark(us: Int) = add(true, us)

        fun space(us: Int) = add(false, us)

        private fun add(on: Boolean, us: Int) {
            if (us <= 0 || total + us > MAX_TOTAL_US) return
            if (out.isEmpty()) {
                if (!on) return
                out += us
                lastOn = true
            } else if (on == lastOn) {
                out[out.size - 1] = out[out.size - 1] + us
            } else {
                out += us
                lastOn = on
            }
            total += us
        }

        fun times(): List<Int> = out.toList()

        fun build(carrier: Int): IrSignal? {
            val list = if (out.isNotEmpty() && out.size % 2 == 0) out.dropLast(1) else out
            if (list.isEmpty()) return null
            return IrSignal(carrier, list.toIntArray())
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val PRONTO_START = Regex("^0000\\s+[0-9A-Fa-f]{4}\\s")
    private const val PRONTO_UNIT_US = 0.241246
    private const val NEC_CARRIER = 38_000
    private const val SIRC_CARRIER = 40_000
    private const val RC_CARRIER = 36_000
    private const val RC5_HALF = 889
    private const val RC6_HALF = 444
    private const val SIRC_FRAMES = 3
    private const val SIRC_PERIOD_US = 45_000
    private const val MIN_CARRIER = 15_000
    private const val MAX_CARRIER = 60_000
    private const val MAX_EXTRA = 48
}
