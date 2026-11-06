package com.wasimaster.wmkeyboard.ime.launch

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.core.accessibility.SplitScreenHelperService
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import kotlin.math.max

/** Reads [DeviceTraits] off the running device. Cheap enough to call per launch. */
object DeviceTraitsReader {

    /** AOSP's own split-screen floor: the display's longest side, in dp. */
    private const val SPLIT_MIN_LONGEST_DP = 440

    /** 13 QPR1's first security patch; the launch-adjacent source check came with it. */
    private const val QPR1_PATCH = "2022-12"

    fun read(context: Context): DeviceTraits {
        val pm = context.packageManager
        val sdk = Build.VERSION.SDK_INT
        val chromeOs = pm.hasSystemFeature("org.chromium.arc") || pm.hasSystemFeature("org.chromium.arc.device_management")
        return DeviceTraits(
            sdk = sdk,
            family = family,
            adjacentNeedsSource = sdk > Build.VERSION_CODES.TIRAMISU ||
                (sdk == Build.VERSION_CODES.TIRAMISU && Build.VERSION.SECURITY_PATCH >= QPR1_PATCH),
            splitFramework = splitFramework(context),
            freeform = chromeOs || pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
                runCatching {
                    Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
                }.getOrDefault(false),
            desktopDisplay = chromeOs || pm.hasSystemFeature(PackageManager.FEATURE_PC) || samsungDesktop(context),
            toggleSplitAction = helper()?.toggleSplitListed(),
            helperOn = helper() != null,
            shell = ShellAccess.available,
            secureSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    /** The skin, resolved once per process: it cannot change without a reboot. */
    val family: RomFamily by lazy {
        RomFamily.resolve(
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            prop = ::systemProperty,
            hasClass = { runCatching { Class.forName(it) }.isSuccess },
            // ARC++ builds name their device "<board>_cheets".
            chromeOs = Build.DEVICE.orEmpty().endsWith("_cheets"),
        )
    }

    /**
     * The helper may only be offered where its switch can exist: everywhere
     * but stock-like Android 12L and later, whose framework no longer handles
     * it (AOSP SystemActionPerformer lost the case in 12L). On every other
     * skin calibration decides, once the helper is on and can ask the system.
     */
    val helperApplies: Boolean
        get() = ChannelFeatures.SPLIT_SCREEN_HELPER &&
            !(family in STOCK_LIKE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2)

    private val STOCK_LIKE = setOf(RomFamily.AOSP, RomFamily.MOTOROLA, RomFamily.CHROMEOS)

    fun helper(): SplitScreenHelperService? = SplitScreenHelperService.current?.takeIf { helperApplies }

    /**
     * AOSP's `ActivityTaskManager.supportsSplitScreenMultiWindow`, rebuilt from
     * public pieces: the framework flags, no TV, not a low-RAM device, and a
     * screen at least 440 dp on its longest side.
     */
    fun splitFramework(context: Context): Boolean {
        val pm = context.packageManager
        val config = context.resources.configuration
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        return systemBool("config_supportsMultiWindow") &&
            systemBool("config_supportsSplitScreenMultiWindow") &&
            !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !lowRam &&
            max(config.screenWidthDp, config.screenHeightDp) >= SPLIT_MIN_LONGEST_DP
    }

    /** Samsung DeX: Samsung's own configuration field, read by name. */
    private fun samsungDesktop(context: Context): Boolean = family == RomFamily.SAMSUNG && runCatching {
        val config = context.resources.configuration
        val field = config.javaClass.getField("semDesktopModeEnabled")
        val enabled = config.javaClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(config)
        field.getInt(config) == enabled
    }.getOrDefault(false)

    private fun systemBool(name: String): Boolean = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "bool", "android")
        if (id == 0) true else res.getBoolean(id)
    }.getOrDefault(true)

    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as? String
    }.getOrNull()
}
