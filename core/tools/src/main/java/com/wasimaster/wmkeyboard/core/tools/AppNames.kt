package com.wasimaster.wmkeyboard.core.tools

import java.text.Normalizer
import java.util.Locale

/**
 * The installed apps' names, for the chip that opens an app by name (#656):
 * "whatsapp" typed, and the strip offers to open WhatsApp. Built by the
 * keyboard from the launcher's app list; this side only matches.
 *
 * A name matches whole, ignoring case and accents, and only names of at
 * least [MIN_LENGTH] letters count, so "X" or "Go" never raise a chip. Two
 * apps with the same name match neither: the chip could only guess.
 */
class AppNames private constructor(private val byName: Map<String, App>) {

    /** One app as the chip opens it: its name as shown, its package and the activity the launcher starts. */
    data class App(val label: String, val packageName: String, val activityName: String)

    val isEmpty: Boolean get() = byName.isEmpty()

    /** The app [phrase] names, or null. [phrase] is the typed words joined by single spaces. */
    fun match(phrase: String): App? = byName[normalize(phrase)]

    companion object {
        /** The fewest letters a name needs before typing it raises a chip. */
        const val MIN_LENGTH = 3

        /** The most words an app's name may run to and still be matched. */
        const val MAX_WORDS = 3

        val EMPTY = AppNames(emptyMap())

        fun of(apps: List<App>): AppNames {
            val byName = HashMap<String, App>()
            val clashes = HashSet<String>()
            for (app in apps) {
                val key = normalize(app.label)
                if (key.count { it.isLetterOrDigit() } < MIN_LENGTH) continue
                if (key.count { it == ' ' } >= MAX_WORDS) continue
                val had = byName[key]
                if (had != null && had.packageName != app.packageName) clashes += key
                if (had == null) byName[key] = app
            }
            clashes.forEach(byName::remove)
            return AppNames(byName)
        }

        /** Lowercase, accents off, runs of spaces as one. */
        internal fun normalize(text: String): String =
            Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("\\s+"), " ")
    }
}
