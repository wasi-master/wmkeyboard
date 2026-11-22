package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which Google Fonts faces are variable, and their axes, as probed from the
 * provider's own files ([KeyboardFonts.probeGoogleAxes]).
 *
 * Kept on disk because finding out takes a provider round trip per face, and
 * the answer only changes when Google ships a new build of the face, which is
 * rare enough that a stale answer costs at most one face showing sliders a
 * release late. A static face is stored too, as [FontVariations.STATIC], so it
 * is asked about once.
 */
internal object GoogleFontAxes {

    private val serializer = MapSerializer(String.serializer(), FontVariations.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded: Map<String, FontVariations>? = null

    /** The face's axes if it was probed and is variable; never probes. */
    fun cached(context: Context, name: String): FontVariations? =
        known(context, name)?.takeIf { it.isVariable }

    /** What the probe found, static included; null if it was never probed. */
    fun known(context: Context, name: String): FontVariations? = all(context)[name]

    fun remember(context: Context, name: String, variations: FontVariations) {
        synchronized(this) {
            val next = all(context) + (name to variations)
            loaded = next
            val file = file(context) ?: return
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.parentFile, file.name + ".part")
                part.writeText(json.encodeToString(serializer, next))
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
            }
        }
    }

    private fun all(context: Context): Map<String, FontVariations> {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val read = file(context)?.takeIf { it.isFile }
                ?.let { runCatching { json.decodeFromString(serializer, it.readText()) }.getOrNull() }
                .orEmpty()
            // Before the first unlock there is no file to read; asked again after.
            if (file(context) != null) loaded = read
            return read
        }
    }

    private fun file(context: Context): File? =
        if (DirectBoot.isUserUnlocked(context)) {
            File(context.applicationContext.filesDir, "fonts/google_axes.json")
        } else {
            null
        }
}
