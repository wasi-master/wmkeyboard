package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import android.hardware.ConsumerIrManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The TV code sets that ship in `assets/remote/ir-tv.json`: for each brand, the
 * few sets most of its remotes share, built from Flipper-IRDB (CC0 1.0) by
 * `scripts/build_remote_ir_codes.py`. Read once, on first use.
 */
object IrCatalog {

    @Serializable
    data class CodeSet(val name: String, val models: Int = 1, val keys: Map<String, String> = emptyMap())

    @Serializable
    data class Brand(val brand: String, val sets: List<CodeSet> = emptyList())

    @Serializable
    private data class Asset(val source: String = "", val brands: List<Brand> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<Brand>? = null

    /** Every brand, the most common first, as the file lists them. */
    fun brands(context: Context): List<Brand> {
        cached?.let { return it }
        val loaded = runCatching {
            context.applicationContext.assets.open(ASSET).use { stream ->
                json.decodeFromString(Asset.serializer(), stream.readBytes().toString(Charsets.UTF_8)).brands
            }
        }.getOrDefault(emptyList())
        cached = loaded
        return loaded
    }

    fun brand(context: Context, name: String): Brand? = brands(context).firstOrNull { it.brand == name }

    /**
     * The code an infrared remote sends for [key]: a pasted or imported one
     * first, else its brand set's.
     */
    fun code(context: Context, device: RemoteDevice, key: RemoteKey): String? {
        device.irCodes[key.name]?.let { return it }
        if (device.irBrand.isEmpty()) return null
        val set = brand(context, device.irBrand)?.sets?.getOrNull(device.irSet) ?: return null
        return set.keys[key.name]
    }

    /** The pad keys [device] has a code for. */
    fun keys(context: Context, device: RemoteDevice): Set<RemoteKey> =
        RemoteKey.entries.filter { code(context, device, it) != null }.toSet()

    private const val ASSET = "remote/ir-tv.json"
}

/** The phone's infrared emitter, when it has one. */
object IrBlaster {

    private fun manager(context: Context): ConsumerIrManager? =
        runCatching { context.applicationContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager }.getOrNull()

    /** Whether this phone can send infrared at all. Most phones cannot; Xiaomi, some Honor, Huawei and older Samsung and LG ones can. */
    fun available(context: Context): Boolean = runCatching { manager(context)?.hasIrEmitter() == true }.getOrDefault(false)

    /**
     * Sends [signal], blocking until it has gone out. The carrier is moved into
     * the nearest range the emitter supports, which receivers tolerate far
     * better than a refused call. False when there is no emitter or it refused.
     */
    fun send(context: Context, signal: IrSignal): Boolean {
        val ir = manager(context) ?: return false
        return runCatching {
            if (!ir.hasIrEmitter()) return false
            ir.transmit(fit(ir, signal.carrierHz), signal.pattern)
            true
        }.getOrDefault(false)
    }

    private fun fit(ir: ConsumerIrManager, hz: Int): Int {
        val ranges = runCatching { ir.carrierFrequencies }.getOrNull()
        if (ranges.isNullOrEmpty()) return hz
        if (ranges.any { hz in it.minFrequency..it.maxFrequency }) return hz
        return ranges.map { r -> hz.coerceIn(r.minFrequency, r.maxFrequency) }.minBy { kotlin.math.abs(it - hz) }
    }
}
