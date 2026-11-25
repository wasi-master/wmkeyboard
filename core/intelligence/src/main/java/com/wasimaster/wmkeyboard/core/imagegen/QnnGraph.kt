package com.wasimaster.wmkeyboard.core.imagegen

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * The QNN runtime in this process, shared by everything that runs a context
 * binary on the NPU: the sticker generator's Stable Diffusion and the
 * upscaler's photo network both live in the `:imagegen` process and may hold
 * graphs at the same time. The runtime (and the HTP's performance vote) is
 * let go when the last user releases it, never under a graph someone still
 * holds. Calls come from the generator service's one worker thread; the lock
 * is for safety, not for speed.
 */
internal object QnnRuntime {

    private var users = 0

    /** Opens the runtime in [runtimeDir] (NpuRuntime's directory for this chip), once per [release]. */
    @Synchronized
    fun acquire(runtimeDir: File) {
        if (!QnnImageNative.available) throw IOException("the NPU runner is not here")
        if (!QnnImageNative.nativeInit(runtimeDir.absolutePath)) throw IOException("the QNN runtime did not start")
        users++
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users == 0) QnnImageNative.nativeShutdown()
    }
}

/**
 * One graph of a QNN context binary on the Hexagon NPU, as
 * libwmimagegen_qnn.so loads it: the binary's first graph, its inputs and
 * outputs as the binary declares them (names, shapes, types, and the scale and
 * offset of quantised ones). Values cross as floats: quantisation into the
 * graph's own types and back happens here. Needs [QnnRuntime] acquired for as
 * long as it is open. Calls must not overlap.
 */
internal class QnnGraph private constructor(
    private val handle: Long,
    val inputs: List<Tensor>,
    val outputs: List<Tensor>,
) : Closeable {

    class Tensor(val name: String, val dims: IntArray, val type: Int, val bytes: Int, val scale: Float, val offset: Int) {
        /** How many values the tensor holds. */
        val count: Int get() = bytes / QnnGraph.width(type)
    }

    private var closed = false

    /** Raw bytes in, one array per input in [inputs]' order; the outputs' raw bytes back. */
    fun execute(input: List<ByteArray>): List<ByteArray> {
        check(!closed) { "the graph is closed" }
        val out = Array(outputs.size) { ByteArray(outputs[it].bytes) }
        if (!QnnImageNative.nativeRun(handle, input.toTypedArray(), out)) throw IOException("the NPU refused the run")
        return out.toList()
    }

    /** [values] (one array per input) quantised into each input's type, and every output back as real values. */
    fun run(vararg values: FloatArray): List<FloatArray> {
        require(values.size == inputs.size) { "${inputs.size} inputs, ${values.size} given" }
        val raw = execute(inputs.mapIndexed { i, t -> encode(t, values[i]) })
        return outputs.mapIndexed { i, t -> decode(t, raw[i]) }
    }

    override fun close() {
        if (closed) return
        closed = true
        QnnImageNative.nativeFree(handle)
    }

    companion object {
        /** The first graph of the context binary [file]. The runtime must be acquired. */
        fun open(file: File): QnnGraph {
            if (!file.isFile) throw IOException("${file.name} is missing")
            val handle = QnnImageNative.nativeLoad(file.absolutePath)
            if (handle == 0L) throw IOException("the NPU could not load ${file.name}")
            val json = QnnImageNative.nativeDescribe(handle)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json == null) {
                QnnImageNative.nativeFree(handle)
                throw IOException("${file.name} declares no graph")
            }
            fun list(key: String) = json.getJSONArray(key).let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val d = o.getJSONArray("dims")
                    Tensor(
                        o.getString("name"), IntArray(d.length()) { d.getInt(it) }, o.getInt("type"),
                        o.getInt("bytes"), o.getDouble("scale").toFloat(), o.getInt("offset"),
                    )
                }
            }
            return QnnGraph(handle, list("inputs"), list("outputs"))
        }

        // Qnn_DataType_t values (QnnTypes.h).
        const val INT_32 = 0x0032
        const val UINT_16 = 0x0116
        const val FLOAT_16 = 0x0216
        const val FLOAT_32 = 0x0232
        const val SFIXED_8 = 0x0308
        const val SFIXED_16 = 0x0316
        const val UFIXED_8 = 0x0408
        const val UFIXED_16 = 0x0416

        /** Real values into a tensor's own type: q = round(v / scale) - offset for fixed point. */
        fun encode(t: Tensor, values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(t.bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(values.size, t.count)
            for (i in 0 until n) {
                val v = values[i]
                when (t.type) {
                    UFIXED_16, UINT_16 -> buffer.putShort(quant(t, v, 0, 65_535).toShort())
                    SFIXED_16 -> buffer.putShort(quant(t, v, -32_768, 32_767).toShort())
                    UFIXED_8 -> buffer.put(quant(t, v, 0, 255).toByte())
                    SFIXED_8 -> buffer.put(quant(t, v, -128, 127).toByte())
                    INT_32 -> buffer.putInt(v.toInt())
                    FLOAT_32 -> buffer.putFloat(v)
                    FLOAT_16 -> buffer.putShort(Half.fromFloat(v))
                    else -> throw IOException("unsupported tensor type ${t.type}")
                }
            }
            return buffer.array()
        }

        private fun quant(t: Tensor, v: Float, lo: Int, hi: Int): Int {
            if (t.scale == 0f) return v.toInt().coerceIn(lo, hi)
            return ((v / t.scale).roundToLong() - t.offset).coerceIn(lo.toLong(), hi.toLong()).toInt()
        }

        /** A tensor's bytes as real values: (q + offset) × scale for fixed point. */
        fun decode(t: Tensor, bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes.size / width(t.type)
            val scale = if (t.scale == 0f) 1f else t.scale
            return FloatArray(n) {
                when (t.type) {
                    UFIXED_16, UINT_16 -> ((buffer.short.toInt() and 0xffff) + t.offset) * scale
                    SFIXED_16 -> (buffer.short + t.offset) * scale
                    UFIXED_8 -> ((buffer.get().toInt() and 0xff) + t.offset) * scale
                    SFIXED_8 -> (buffer.get() + t.offset) * scale
                    INT_32 -> buffer.int.toFloat()
                    FLOAT_32 -> buffer.float
                    FLOAT_16 -> Half.toFloat(buffer.short)
                    else -> throw IOException("unsupported tensor type ${t.type}")
                }
            }
        }

        fun width(type: Int): Int = when (type) {
            UFIXED_8, SFIXED_8 -> 1
            UFIXED_16, SFIXED_16, UINT_16, FLOAT_16 -> 2
            else -> 4
        }
    }
}
