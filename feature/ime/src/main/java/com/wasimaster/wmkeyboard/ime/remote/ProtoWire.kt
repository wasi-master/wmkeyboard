package com.wasimaster.wmkeyboard.ime.remote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough of the protocol buffers wire format for Android TV's remote
 * service: varints, length-delimited bytes and nested messages, written and
 * read by field number. The schema lives in [AndroidTvRemote], beside the
 * messages it builds.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeVarint(out, (field.toLong() shl 3) or WIRE_VARINT)
        writeVarint(out, value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeVarint(out, (field.toLong() shl 3) or WIRE_BYTES)
        writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val WIRE_VARINT = 0L
        const val WIRE_BYTES = 2L
    }
}

/** A decoded message: every field by number, varints as longs and the rest as bytes. */
internal class ProtoMessage(private val varints: Map<Int, Long>, private val blobs: Map<Int, ByteArray>) {
    fun has(field: Int): Boolean = field in varints || field in blobs

    fun long(field: Int): Long? = varints[field]

    fun bytes(field: Int): ByteArray? = blobs[field]

    fun message(field: Int): ProtoMessage? = blobs[field]?.let { parseProto(it) }

    fun string(field: Int): String? = blobs[field]?.toString(Charsets.UTF_8)
}

/** Reads one message; unknown wire types end the read rather than misparse what follows. */
internal fun parseProto(data: ByteArray): ProtoMessage {
    val varints = HashMap<Int, Long>()
    val blobs = HashMap<Int, ByteArray>()
    var pos = 0
    fun next(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= data.size) throw EOFException("truncated varint")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw EOFException("varint too long")
        }
    }
    while (pos < data.size) {
        val tag = next()
        val field = (tag ushr 3).toInt()
        when ((tag and 7L).toInt()) {
            0 -> varints[field] = next()
            2 -> {
                val length = next().toInt()
                if (length < 0 || pos + length > data.size) throw EOFException("truncated field $field")
                blobs[field] = data.copyOfRange(pos, pos + length)
                pos += length
            }
            1 -> pos += 8
            5 -> pos += 4
            else -> break
        }
    }
    return ProtoMessage(varints, blobs)
}

internal fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

/** One length-prefixed message off [input], as the TV frames them. Null at the end of the stream. */
internal fun readFrame(input: InputStream): ByteArray? {
    var shift = 0
    var length = 0L
    while (true) {
        val b = input.read()
        if (b < 0) return null
        length = length or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw EOFException("frame length too long")
    }
    if (length > MAX_FRAME) throw EOFException("frame too large")
    val buffer = ByteArray(length.toInt())
    var read = 0
    while (read < buffer.size) {
        val n = input.read(buffer, read, buffer.size - read)
        if (n < 0) throw EOFException("frame cut short")
        read += n
    }
    return buffer
}

/** [message], prefixed with its length. */
internal fun writeFrame(output: OutputStream, message: ByteArray) {
    val framed = ByteArrayOutputStream(message.size + 5)
    writeVarint(framed, message.size.toLong())
    framed.write(message)
    output.write(framed.toByteArray())
    output.flush()
}

private const val MAX_FRAME = 1 shl 20
