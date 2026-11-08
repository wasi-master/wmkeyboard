package com.wasimaster.wmkeyboard.core.util

import java.io.IOException
import java.io.InputStream

/**
 * A bzip2 decoder, since nothing on Android has one: StarDict books (`:core:tools`)
 * and Kokoro voices (`:core:voice`) mostly come as `.tar.bz2`. Written from the
 * format's description. Handles several concatenated streams; checks no CRC, as the
 * formats above it fail on damage anyway (and the Kokoro archive's SHA-256 is pinned).
 */
class Bzip2InputStream(private val input: InputStream) : InputStream() {

    private var bitBuffer = 0L
    private var bitCount = 0
    private var blockSize = 0
    private var started = false
    private var ended = false

    private var tt = IntArray(0)
    private var origLength = 0
    private var pos = 0
    private var produced = 0
    private var runLeft = 0
    private var runByte = 0
    private var sameCount = 0
    private var lastByte = -1

    private fun bit(): Int = bits(1)

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = input.read()
            if (b < 0) throw IOException("bzip2 stream cut off")
            bitBuffer = (bitBuffer shl 8) or b.toLong()
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuffer ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    private fun bits48(): Long = (bits(24).toLong() shl 24) or bits(24).toLong()

    private fun streamHeader(): Boolean {
        val first = input.read()
        if (first < 0) return false
        if (first != 'B'.code || input.read() != 'Z'.code || input.read() != 'h'.code) throw IOException("not bzip2")
        val level = input.read() - '0'.code
        if (level !in 1..9) throw IOException("not bzip2")
        blockSize = level * BLOCK_UNIT
        if (tt.size < blockSize) tt = IntArray(blockSize)
        bitCount = 0
        bitBuffer = 0
        return true
    }

    /** Reads the next block. False once the last stream has ended. */
    private fun nextBlock(): Boolean {
        while (true) {
            if (!started) {
                if (!streamHeader()) return false
                started = true
            }
            val magic = bits48()
            if (magic == BLOCK_MAGIC) {
                bits(32) // Block CRC.
                readBlock()
                return true
            }
            if (magic != END_MAGIC) throw IOException("bad bzip2 block")
            bits(32) // Stream CRC.
            bitCount -= bitCount % 8 // To the byte boundary.
            started = false
        }
    }

    private fun readBlock() {
        if (bit() != 0) throw IOException("randomised bzip2")
        val origPtr = bits(24)
        // Which bytes occur, as a two-level bitmap.
        val symbols = IntArray(256)
        var used = 0
        val groups = bits(16)
        for (i in 0 until 16) {
            if (groups and (0x8000 shr i) == 0) continue
            val row = bits(16)
            for (j in 0 until 16) if (row and (0x8000 shr j) != 0) symbols[used++] = i * 16 + j
        }
        if (used == 0) throw IOException("bad bzip2 block")
        val alphabet = used + 2
        val tables = bits(3)
        if (tables !in 2..6) throw IOException("bad bzip2 block")
        val selectorCount = bits(15)
        if (selectorCount < 1) throw IOException("bad bzip2 block")
        // Selectors, move-to-front coded.
        val order = IntArray(tables) { it }
        val selectors = ByteArray(selectorCount)
        for (i in 0 until selectorCount) {
            var j = 0
            while (bit() == 1) {
                j++
                if (j >= tables) throw IOException("bad bzip2 block")
            }
            val chosen = order[j]
            System.arraycopy(order, 0, order, 1, j)
            order[0] = chosen
            selectors[i] = chosen.toByte()
        }
        val limits = Array(tables) { IntArray(MAX_CODE_LEN + 2) }
        val bases = Array(tables) { IntArray(MAX_CODE_LEN + 2) }
        val perms = Array(tables) { IntArray(alphabet) }
        val minLens = IntArray(tables)
        for (t in 0 until tables) {
            val lengths = IntArray(alphabet)
            var length = bits(5)
            for (i in 0 until alphabet) {
                while (true) {
                    if (length !in 1..MAX_CODE_LEN) throw IOException("bad bzip2 block")
                    if (bit() == 0) break
                    length += if (bit() == 0) 1 else -1
                }
                lengths[i] = length
            }
            minLens[t] = buildTable(lengths, alphabet, limits[t], bases[t], perms[t])
        }
        // The Huffman coded, run-length coded, move-to-front coded data.
        val mtf = IntArray(256) { it }
        val counts = IntArray(256)
        val end = used + 1
        var n = 0
        var selector = 0
        var left = 0
        var limit = limits[0]
        var base = bases[0]
        var perm = perms[0]
        var minLen = minLens[0]
        var runLength = 0
        var runWeight = 1
        while (true) {
            if (left == 0) {
                if (selector >= selectorCount) throw IOException("bad bzip2 block")
                val t = selectors[selector++].toInt()
                limit = limits[t]
                base = bases[t]
                perm = perms[t]
                minLen = minLens[t]
                left = GROUP_SIZE
            }
            left--
            var length = minLen
            var code = bits(length)
            while (true) {
                if (length > MAX_CODE_LEN) throw IOException("bad bzip2 block")
                if (code <= limit[length]) break
                length++
                code = (code shl 1) or bit()
            }
            val index = code - base[length]
            if (index !in 0 until alphabet) throw IOException("bad bzip2 block")
            val symbol = perm[index]
            if (symbol == 0 || symbol == 1) {
                runLength += runWeight shl symbol
                runWeight = runWeight shl 1
                continue
            }
            if (runLength > 0) {
                val byte = symbols[mtf[0]]
                if (n + runLength > blockSize) throw IOException("bad bzip2 block")
                counts[byte] += runLength
                java.util.Arrays.fill(tt, n, n + runLength, byte)
                n += runLength
                runLength = 0
                runWeight = 1
            }
            if (symbol == end) break
            if (n >= blockSize) throw IOException("bad bzip2 block")
            val at = symbol - 1
            val moved = mtf[at]
            System.arraycopy(mtf, 0, mtf, 1, at)
            mtf[0] = moved
            val byte = symbols[moved]
            counts[byte]++
            tt[n++] = byte
        }
        if (origPtr >= n) throw IOException("bad bzip2 block")
        // Undo the Burrows-Wheeler transform: each entry's high bits link to the next.
        var sum = 0
        for (i in 0 until 256) {
            val c = counts[i]
            counts[i] = sum
            sum += c
        }
        for (i in 0 until n) {
            val byte = tt[i] and 0xFF
            tt[counts[byte]++] = tt[counts[byte] - 1] or (i shl 8)
        }
        pos = tt[origPtr] ushr 8
        origLength = n
        produced = 0
        sameCount = 0
        lastByte = -1
        runLeft = 0
    }

    private fun buildTable(lengths: IntArray, alphabet: Int, limit: IntArray, base: IntArray, perm: IntArray): Int {
        var minLen = MAX_CODE_LEN
        var maxLen = 1
        for (i in 0 until alphabet) {
            if (lengths[i] < minLen) minLen = lengths[i]
            if (lengths[i] > maxLen) maxLen = lengths[i]
        }
        var p = 0
        for (length in minLen..maxLen) for (i in 0 until alphabet) if (lengths[i] == length) perm[p++] = i
        val countOf = IntArray(MAX_CODE_LEN + 2)
        for (i in 0 until alphabet) countOf[lengths[i]]++
        var code = 0
        var seen = 0
        for (length in minLen..maxLen) {
            base[length] = code - seen
            code += countOf[length]
            limit[length] = code - 1
            seen += countOf[length]
            code = code shl 1
        }
        for (length in 0 until minLen) limit[length] = -1
        for (length in maxLen + 1 until limit.size) limit[length] = Int.MAX_VALUE
        return minLen
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            if (runLeft > 0) {
                b[off + written++] = runByte.toByte()
                runLeft--
                continue
            }
            if (produced == origLength) {
                if (ended) break
                if (!nextBlock()) {
                    ended = true
                    break
                }
                continue
            }
            val entry = tt[pos]
            val byte = entry and 0xFF
            pos = entry ushr 8
            produced++
            // The first run-length layer: four equal bytes, then a count of more.
            if (sameCount == RUN_TRIGGER) {
                runByte = lastByte
                runLeft = byte
                sameCount = 0
                continue
            }
            sameCount = if (byte == lastByte) sameCount + 1 else 1
            lastByte = byte
            b[off + written++] = byte.toByte()
        }
        return if (written == 0) -1 else written
    }

    override fun close() = input.close()

    private companion object {
        const val BLOCK_UNIT = 100_000
        const val BLOCK_MAGIC = 0x314159265359L
        const val END_MAGIC = 0x177245385090L
        const val MAX_CODE_LEN = 20
        const val GROUP_SIZE = 50
        const val RUN_TRIGGER = 4
    }
}
