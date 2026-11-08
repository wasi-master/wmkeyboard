package com.wasimaster.wmkeyboard.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * The phone's GPU, as far as the GPU builds of llama.cpp and whisper.cpp care:
 * an Adreno takes their OpenCL builds (Qualcomm's backend, which refuses every
 * other GPU), anything else with a Vulkan 1.2 driver takes their Vulkan builds
 * (Mali, Xclipse, PowerVR).
 */
object PhoneGpu {

    /** What the package manager reports for Vulkan 1.2, `VK_MAKE_API_VERSION(0, 1, 2, 0)`. */
    private const val VULKAN_1_2 = 0x402000

    /** And for Vulkan 1.1. */
    private const val VULKAN_1_1 = 0x401000

    /**
     * An Adreno GPU: the kgsl device node Qualcomm's driver makes, or the chip
     * maker Android reports.
     */
    val adreno: Boolean by lazy {
        File("/dev/kgsl-3d0").exists() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)) ||
            Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    /** The Vulkan version the system reports, or 0 until [attach] ran or where it reports none. */
    @Volatile
    private var vulkanVersion = 0

    /**
     * Reads the Vulkan version the system advertises. Called once per process
     * from `Application.onCreate`; until then [vulkan] is false, so no Vulkan
     * build is offered before the phone has said it can run one.
     */
    fun attach(context: Context) {
        vulkanVersion = runCatching {
            context.packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                ?.version ?: 0
        }.getOrDefault(0)
    }

    /**
     * A GPU other than an Adreno whose driver speaks Vulkan 1.2, the version
     * ggml's Vulkan backend needs: the phones the Vulkan builds are for.
     */
    val vulkan: Boolean get() = !adreno && vulkanVersion >= VULKAN_1_2

    /** Any GPU, Adreno included, whose driver speaks Vulkan 1.1: ExecuTorch's Vulkan backend. */
    val vulkan11: Boolean get() = vulkanVersion >= VULKAN_1_1
}
