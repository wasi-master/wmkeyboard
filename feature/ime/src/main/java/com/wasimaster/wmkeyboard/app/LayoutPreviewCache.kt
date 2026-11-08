package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.FloatingHandlePosition
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.isTelevision
import com.wasimaster.wmkeyboard.ime.ui.layoutSwitchLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The pictures behind the layout cards (see [LayoutKeyboardPreview]).
 *
 * A card is the real keyboard drawn small, and composing the real keyboard is
 * the most expensive thing the settings app does: a language screen with eight
 * layouts composed eight keyboards in one frame, and did it again for every
 * card the carousel scrolled back into view. Instead each board is composed
 * once, captured as a bitmap, and every later showing of it draws the bitmap.
 *
 * Kept twice: in memory for the life of the process, and as a WebP under the
 * cache directory so the next visit — the next launch included — opens on
 * pictures rather than on skeletons. Both are keyed by [keyOf], a digest of
 * everything the board is drawn from, so a change of theme, font, key height or
 * layout is a new key and a fresh render, never a stale picture.
 */
internal object LayoutPreviewCache {

    /** Bumped when the key or the file format changes, to orphan older files. */
    private const val FORMAT = 1
    private const val DIR = "layout-previews"

    /**
     * Files kept on disk. A card is ~50 KB as lossless WebP, so this is a few
     * megabytes: every layout of a dozen languages, twice over for a theme
     * change.
     */
    private const val DISK_MAX_FILES = 200

    private val memory = object : LruCache<String, ImageBitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * The key of the last picture each layout was shown with. What a card
     * draws while the picture for new settings renders, so a toggle or a theme
     * change repaints the cards in place rather than flashing skeletons.
     */
    private val latest = HashMap<String, String>()

    /**
     * The inputs each layout's key was last worked out from, so a card that
     * scrolls back into view with none of them changed has its key, and its
     * picture, without computing either again.
     *
     * One slot per layout and width: the carousel and the layout gallery draw
     * the same layout at two widths, and a single slot per layout made each
     * screen throw the other's key away, so every visit to one worked the key
     * out again and swapped the picture under the card.
     */
    private val keys = HashMap<String, Pair<PreviewInputs, String>>()

    @Volatile
    private var television: Boolean? = null

    private var environmentOf: EnvironmentInputs? = null
    private var environment: String = ""

    /** Width over height of the last picture taken, for the skeleton's shape. */
    @Volatile
    var lastAspect: Float = FALLBACK_ASPECT
        private set

    /** Writes outlive the card that asked for them: a picture is worth keeping once taken. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appStamp: Long = -1L

    /**
     * Sized for a More layouts grid scrolled end to end and back, dozens of
     * cards of ~0.7 MB each: a picture evicted is a card that shows a skeleton
     * and decodes from disk again on the way back. Most of them are hardware
     * bitmaps and do not sit on the heap this is a share of.
     */
    private fun memoryBudget(): Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L shl 20).toInt()

    fun get(key: String): ImageBitmap? = memory.get(key)

    /** The key last worked out for [layoutId], if [inputs] are what it was worked out from. */
    @Synchronized
    fun knownKey(layoutId: String, inputs: PreviewInputs): String? =
        keys[slot(layoutId, inputs.widthPx)]?.takeIf { it.first.sameAs(inputs) }?.second

    @Synchronized
    fun noteKey(layoutId: String, inputs: PreviewInputs, key: String) {
        keys[slot(layoutId, inputs.widthPx)] = inputs to key
    }

    private fun slot(layoutId: String, widthPx: Int) = "$layoutId\u0000$widthPx"

    /** Whether this is a television, asked once: it is a binder call, and it never changes. */
    fun television(context: Context): Boolean =
        television ?: context.isTelevision().also { television = it }

    /** [previewEnvironment], worked out once per configuration rather than once per card. */
    @Synchronized
    fun environment(
        context: Context,
        configuration: Configuration,
        density: Density,
        systemDark: Boolean,
        darkSlot: Boolean,
    ): String {
        val inputs = EnvironmentInputs(Configuration(configuration), density, systemDark, darkSlot)
        if (inputs != environmentOf) {
            environment = previewEnvironment(context, configuration, density, systemDark, darkSlot)
            environmentOf = inputs
        }
        return environment
    }

    /** The last picture of [layoutId], whatever it was drawn for. */
    @Synchronized
    fun stale(layoutId: String): ImageBitmap? = latest[layoutId]?.let { memory.get(it) }

    @Synchronized
    fun put(layoutId: String, key: String, picture: ImageBitmap) {
        memory.put(key, picture)
        memory.remove(key + PROVISIONAL)
        latest[layoutId] = key
        lastAspect = picture.width.toFloat() / picture.height.coerceAtLeast(1)
    }

    /**
     * A card's first picture, taken before what the board loads for itself
     * (a photo, an icon pack) has surely arrived: shown by [stale] when the
     * card comes back, never found by [get].
     *
     * Without it a card scrolled away in the second between its first picture
     * and the one kept came back with nothing, and a fast fling back and forth
     * along a row turned pictures that had already loaded back into skeletons.
     * Kept out of [get] so the card still renders again and keeps a settled
     * picture, rather than holding a photo-less one for the life of the
     * process.
     */
    @Synchronized
    fun putProvisional(layoutId: String, key: String, picture: ImageBitmap) {
        if (memory.get(key) != null) return
        memory.put(key + PROVISIONAL, picture)
        latest[layoutId] = key + PROVISIONAL
        lastAspect = picture.width.toFloat() / picture.height.coerceAtLeast(1)
    }

    /** The picture saved for [key] on a past visit, decoded off the main thread; null if there is none. */
    suspend fun load(context: Context, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(dir(context), "$key.webp")
        if (!file.isFile) return@withContext null
        val options = BitmapFactory.Options().apply {
            // Straight to the GPU: the card only ever draws it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredConfig = Bitmap.Config.HARDWARE
            }
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
        if (bitmap == null) {
            file.delete()
            return@withContext null
        }
        // Touched, so the trim below keeps the pictures that are still wanted.
        file.setLastModified(System.currentTimeMillis())
        bitmap.asImageBitmap()
    }

    /** Saves [picture] for the next visit, in the background. */
    fun save(context: Context, key: String, picture: ImageBitmap) {
        // Before the shipped layouts have parsed, an asset layout resolves to
        // the default grid; a picture of that is fine for a moment but must
        // not outlive the process.
        if (AssetLayouts.generation == 0) return
        val app = context.applicationContext
        io.launch {
            runCatching {
                val dir = dir(app).apply { mkdirs() }
                val source = picture.asAndroidBitmap()
                // A hardware bitmap has no pixels on the CPU side to encode.
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    source.config == Bitmap.Config.HARDWARE
                ) {
                    source.copy(Bitmap.Config.ARGB_8888, false) ?: return@launch
                } else {
                    source
                }
                val temp = File(dir, "$key.tmp")
                temp.outputStream().use { out ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(format, 100, out)
                }
                if (bitmap !== source) bitmap.recycle()
                temp.renameTo(File(dir, "$key.webp"))
                trim(dir)
            }
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(DISK_MAX_FILES)
            .forEach { it.delete() }
    }

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /**
     * The key of the picture of [layoutId]'s board, drawn with [shown]: a digest of every input the
     * board is drawn from. Called off the main thread; the settings half is
     * the expensive part and is worked out once per settings instance.
     */
    fun keyOf(
        context: Context,
        settings: KeyboardSettings,
        layoutId: String,
        shown: KeyboardSettings,
        form: DeviceForm,
        television: Boolean,
        environment: String,
        widthPx: Int,
    ): String {
        return sha256(
            listOf(
                FORMAT.toString(),
                appStamp(context).toString(),
                AssetLayouts.generation.toString(),
                environment,
                widthPx.toString(),
                SettingsDigest.of(settings),
                layoutId,
                // A layout the user made or edited is drawn from its spec; a
                // shipped one is fixed by its id and the app version above.
                settings.customLayouts.lastOrNull { it.id == layoutId }?.toString().orEmpty(),
                form.name,
                television.toString(),
                // All the board reads from the enabled list: the spacebar's
                // name, and whether it draws the language arrows.
                layoutSwitchLabel(
                    layoutId,
                    shown.enabledLayoutIds,
                    shown.customLayouts,
                    shown.layoutBehavior.spacebarDisplay,
                    shown.layoutDisplayNames,
                ),
                (shown.enabledLayoutIds.size > 1).toString(),
            ).joinToString("\u0000"),
        )
    }

    /**
     * When the app was installed or updated: a new build can draw the same
     * settings differently, so its pictures start over.
     */
    private fun appStamp(context: Context): Long {
        if (appStamp == -1L) {
            appStamp = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
        }
        return appStamp
    }

    private const val FALLBACK_ASPECT = 1.45f

    /** Marks a provisional picture's memory key; a key is hex, so it never ends in this. */
    private const val PROVISIONAL = "~first"
}

/**
 * What a card's key was worked out from, compared cheaply: the settings by
 * identity, since the settings flow hands every card the same instance until
 * something changes, and comparing two of them field by field is no cheaper
 * than the work this saves.
 */
internal class PreviewInputs(
    private val settings: KeyboardSettings,
    private val form: DeviceForm,
    private val television: Boolean,
    private val environment: String,
    val widthPx: Int,
) {
    // The shipped layouts parsing moves every asset layout's key along.
    private val generation = AssetLayouts.generation

    fun sameAs(other: PreviewInputs): Boolean =
        settings === other.settings &&
            form == other.form &&
            television == other.television &&
            widthPx == other.widthPx &&
            generation == other.generation &&
            environment == other.environment
}

private data class EnvironmentInputs(
    val configuration: Configuration,
    val density: Density,
    val systemDark: Boolean,
    val darkSlot: Boolean,
)

/**
 * The settings half of a picture's key, once per settings instance: every card
 * on a screen shares the same settings, and their text is long.
 *
 * Normalised to what a board looks like rather than what the user has
 * switched on. The enabled list and the active layout are what a card toggles,
 * and the preview overrides both; the custom layouts are keyed per card, only
 * the one being drawn. Everything else goes in whole, since nearly all of it —
 * theme, fonts, sizes, rows, hints, toolbar — reaches the board somewhere.
 */
internal object SettingsDigest {
    private var last: KeyboardSettings? = null
    private var digest: String = ""

    @Synchronized
    fun of(settings: KeyboardSettings): String {
        if (last === settings) return digest
        digest = compute(settings)
        last = settings
        return digest
    }

    fun compute(settings: KeyboardSettings): String = sha256(
        settings.copy(
            customLayouts = emptyList(),
            enabledLayoutIds = emptyList(),
            activeLayoutId = "",
            floatingKeyboard = false,
            floatingKeyboardLandscape = null,
            floatingHandle = FloatingHandlePosition.BOTTOM,
            oneHandedMode = OneHandedMode.OFF,
        ).toString() + "\u0000" +
            // Secondary grids ride along with every layout (the Fn and
            // symbol pages the set compiles), so they are in every key.
            settings.customLayouts.filter { it.secondary }.toString(),
    )
}

/**
 * The part of a board that comes from the screen rather than the settings:
 * its size and density, the text scale, dark mode and which half of an
 * auto-theme is due, and the wallpaper's colours a dynamic theme is built from.
 */
internal fun previewEnvironment(
    context: Context,
    configuration: Configuration,
    density: Density,
    systemDark: Boolean,
    darkSlot: Boolean,
): String = buildString {
    append(configuration.screenWidthDp).append('x').append(configuration.screenHeightDp)
    append(';').append(configuration.smallestScreenWidthDp)
    append(';').append(configuration.orientation)
    append(';').append(density.density).append('/').append(density.fontScale)
    append(';').append(configuration.uiMode)
    append(';').append(configuration.locales.toLanguageTags())
    append(';').append(configuration.layoutDirection)
    append(';').append(systemDark).append('/').append(darkSlot)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        for (id in DynamicColorProbes) {
            append(';').append(runCatching { context.getColor(id) }.getOrDefault(0))
        }
    }
}

/** A tone from each palette the dynamic theme draws on, enough to see the wallpaper change. */
private val DynamicColorProbes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    intArrayOf(
        android.R.color.system_accent1_500,
        android.R.color.system_accent2_500,
        android.R.color.system_accent3_500,
        android.R.color.system_neutral1_500,
        android.R.color.system_neutral2_500,
    )
} else {
    IntArray(0)
}

private fun sha256(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
