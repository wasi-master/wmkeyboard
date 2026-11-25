package com.wasimaster.wmkeyboard.core.stickers.dynamic

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.stickers.StickerKeywords
import java.io.File

/**
 * The typefaces dynamic stickers are drawn in.
 *
 * A template names its fonts by family (`DynaPuff`) and by font name
 * (`DynaPuff-Black`); the user's override names a font by the id the app's
 * font pickers use (`installed:<id>`, `google:<Name>`). Each is looked for,
 * in order:
 *
 * 1. in the template's own pack (fonts a pack ships);
 * 2. among the fonts the user installed, by name (DynaPuff and Anton,
 *    downloaded from the Dynamic stickers screen, land there);
 * 3. through [external], which the keyboard backs with the downloadable
 *    Google fonts it already resolves for key labels;
 * 4. among the platform's own families (`sans-serif-black`, `serif`, …);
 * 5. else a bold sans-serif, so the words are always drawn.
 *
 * Weights come from the style ("Black", "Bold"…): a variable font gets the
 * weight as an axis setting, a static one the nearest the platform can do.
 */
class DynFonts(
    context: Context,
    private val store: DynStickerStore,
    private val external: (String) -> Typeface? = { null },
) {
    private val app = context.applicationContext
    private val cache = HashMap<String, Typeface>()

    /** The typeface for [family] (or a font name, or a picker id) in [packId]'s templates. */
    @Synchronized
    fun typeface(packId: String?, family: String, style: String = ""): Typeface {
        val key = "$packId|$family|$style"
        cache[key]?.let { return it }
        val weight = weightOf("$family $style")
        val found = packFont(packId, family)?.let { load(it, weight) }
            ?: installed(family)?.let { load(it, weight) }
            ?: external(family)
            ?: system(family, weight)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        cache[key] = found
        return found
    }

    /** Whether [family] resolves to a real face rather than the bold sans-serif stand-in. */
    fun available(packId: String?, family: String): Boolean =
        packFont(packId, family) != null || installed(family) != null || external(family) != null || isSystemFamily(family)

    /** Forgets every loaded face: after a font is installed or removed. */
    @Synchronized
    fun clear() = cache.clear()

    /** A measurer over [typeface], for [TextFit]. */
    /**
     * Measures text in [packId]'s fonts. [perCharacter] sums each
     * character's own advance, the way Lottie lays text out (it draws one
     * character at a time, so kerned pairs come out wider than the platform
     * measures the whole word): fitting for Lottie with the word's width would
     * let a word through that Lottie then breaks in the middle.
     */
    fun measurer(packId: String?, perCharacter: Boolean = false): TextFit.TextMeasurer {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = android.graphics.Rect()
        return object : TextFit.TextMeasurer {
            override fun width(text: String, font: String, sizePx: Float, tracking: Float): Float = synchronized(paint) {
                paint.typeface = typeface(packId, font)
                paint.textSize = sizePx
                paint.letterSpacing = tracking / TRACKING_UNITS
                if (perCharacter) {
                    // Lottie measures at the screen's scale, where hinting rounds
                    // each advance differently: a word that fits to the pixel here
                    // can come out a little wider there and be broken.
                    StickerKeywords.clusters(text).sumOf { paint.measureText(it).toDouble() }.toFloat() * LOTTIE_WIDTH_MARGIN
                } else {
                    paint.measureText(text)
                }
            }

            override fun capHeight(font: String, sizePx: Float): Float = synchronized(paint) {
                paint.typeface = typeface(packId, font)
                paint.textSize = sizePx
                paint.letterSpacing = 0f
                paint.getTextBounds(CAP_SAMPLE, 0, CAP_SAMPLE.length, bounds)
                (-bounds.top).toFloat().takeIf { it > 0f } ?: (sizePx * TextFit.CAP_SHARE)
            }
        }
    }

    private fun packFont(packId: String?, family: String): File? {
        if (packId == null) return null
        return store.fontFile(packId, family) ?: store.fontFile(packId, family.substringBefore('-'))
    }

    /**
     * An installed font for [family]: by picker id, else by name, matching
     * "DynaPuff" with "DynaPuff-Black" and the other way round.
     */
    private fun installed(family: String): File? {
        val fonts = FontStore.get(app)
        FontStore.storeIdOf(family)?.let { id -> return fonts.existingFileFor(id) }
        val wanted = family.substringBefore('-').trim().lowercase()
        if (wanted.isEmpty()) return null
        val match = fonts.textFonts().firstOrNull { it.name.equals(family, ignoreCase = true) }
            ?: fonts.textFonts().firstOrNull { it.name.substringBefore(' ').substringBefore('-').lowercase() == wanted }
            ?: return null
        return fonts.existingFileFor(match.id)
    }

    private fun load(file: File, weight: Int): Typeface? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A variable font takes the weight as an axis; a static one ignores it.
            Typeface.Builder(file).setFontVariationSettings("'wght' $weight").setWeight(weight).build()
        } else {
            Typeface.createFromFile(file)
        }
    }.getOrNull()

    private fun system(family: String, weight: Int): Typeface? {
        val name = family.removePrefix("system:")
        if (!isSystemFamily(name)) return null
        val base = Typeface.create(name, Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false) else base
    }

    private fun isSystemFamily(family: String): Boolean = family.removePrefix("system:").lowercase() in SYSTEM_FAMILIES

    private fun weightOf(text: String): Int {
        val lower = text.lowercase()
        return when {
            "black" in lower || "heavy" in lower -> BLACK
            "extrabold" in lower || "extra bold" in lower -> EXTRA_BOLD
            "semibold" in lower || "semi bold" in lower -> SEMI_BOLD
            "bold" in lower -> BOLD
            "medium" in lower -> MEDIUM
            "light" in lower -> LIGHT
            "thin" in lower -> THIN
            else -> REGULAR
        }
    }

    companion object {
        /** Lottie's tracking is in thousandths of an em; Paint's letter spacing in ems. */
        const val TRACKING_UNITS = 1000f
        private const val CAP_SAMPLE = "H"
        private const val LOTTIE_WIDTH_MARGIN = 1.03f

        private const val THIN = 100
        private const val LIGHT = 300
        private const val REGULAR = 400
        private const val MEDIUM = 500
        private const val SEMI_BOLD = 600
        private const val BOLD = 700
        private const val EXTRA_BOLD = 800
        private const val BLACK = 900

        private val SYSTEM_FAMILIES = setOf(
            "sans-serif", "sans-serif-black", "sans-serif-condensed", "sans-serif-medium", "sans-serif-light",
            "sans-serif-thin", "serif", "monospace", "cursive", "casual", "serif-monospace", "sans-serif-smallcaps",
        )
    }
}
