package com.wasimaster.wmkeyboard.core.debug

/**
 * The Debug info report (FUTO U3): what the focused field asked the keyboard
 * for, the keyboard's own state, the screen and memory, as plain text for a
 * bug report.
 *
 * Pure: the decoders take the raw integers an `EditorInfo` carries, with
 * Android's constant values written out here (they are part of the platform
 * API and never change), so the report can be tested on the JVM. Nothing in
 * it ever reads the field's text. The field is described by what the app
 * declared about it: its type, options, package, hint and label.
 */
object FieldDebugInfo {

    /** One titled block of `name: value` lines. */
    data class Section(val title: String, val lines: List<Pair<String, String>>)

    /** The whole report as text, one section after another. */
    fun render(sections: List<Section>): String = buildString {
        for ((i, section) in sections.withIndex()) {
            if (i > 0) append('\n')
            append("## ").append(section.title).append('\n')
            for ((name, value) in section.lines) {
                append(name).append(": ").append(oneLine(value)).append('\n')
            }
        }
    }.trimEnd('\n')

    /**
     * App-provided text (a hint, a label, private options) as one short
     * line: newlines folded, cut at [max] characters. These are what the app
     * declared about the field, never what was typed in it.
     */
    fun oneLine(value: CharSequence?, max: Int = MAX_DECLARED): String {
        val text = value?.toString().orEmpty().replace(NEWLINES, " ").trim()
        return if (text.length > max) text.take(max) + "…" else text
    }

    /** `EditorInfo.inputType` in words: class, variation and every flag, with the raw value. */
    fun decodeInputType(inputType: Int): String {
        if (inputType == TYPE_NULL) return "null (0x0): no editor, raw key events"
        val klass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        val flags = inputType and TYPE_MASK_FLAGS
        val parts = ArrayList<String>()
        when (klass) {
            TYPE_CLASS_TEXT -> {
                parts += "text"
                parts += TEXT_VARIATIONS[variation] ?: "variation 0x${variation.toString(16)}"
                for ((bit, name) in TEXT_FLAGS) if (flags and bit != 0) parts += name
                val known = TEXT_FLAGS.fold(0) { acc, (bit, _) -> acc or bit }
                (flags and known.inv()).takeIf { it != 0 }?.let { parts += "flags 0x${it.toString(16)}" }
            }
            TYPE_CLASS_NUMBER -> {
                parts += "number"
                parts += when (variation) {
                    0x00 -> "normal"
                    0x10 -> "password"
                    else -> "variation 0x${variation.toString(16)}"
                }
                if (flags and 0x1000 != 0) parts += "signed"
                if (flags and 0x2000 != 0) parts += "decimal"
                (flags and 0x3000.inv()).takeIf { it != 0 }?.let { parts += "flags 0x${it.toString(16)}" }
            }
            TYPE_CLASS_PHONE -> parts += "phone"
            TYPE_CLASS_DATETIME -> {
                parts += "datetime"
                parts += when (variation) {
                    0x00 -> "normal"
                    0x10 -> "date"
                    0x20 -> "time"
                    else -> "variation 0x${variation.toString(16)}"
                }
            }
            else -> parts += "class 0x${klass.toString(16)}"
        }
        return parts.joinToString(", ") + " (${hex(inputType)})"
    }

    /** `EditorInfo.imeOptions` in words: the Enter action and every flag, with the raw value. */
    fun decodeImeOptions(imeOptions: Int): String {
        val action = imeOptions and IME_MASK_ACTION
        val parts = ArrayList<String>()
        parts += "action " + (ACTIONS[action] ?: "0x${action.toString(16)}")
        var rest = imeOptions and IME_MASK_ACTION.inv()
        for ((bit, name) in IME_FLAGS) {
            if (imeOptions and bit != 0) {
                parts += name
                rest = rest and bit.inv()
            }
        }
        if (rest != 0) parts += "flags 0x${rest.toUInt().toString(16)}"
        return parts.joinToString(", ") + " (${hex(imeOptions)})"
    }

    /** `EditorInfo.initialCapsMode` (TextUtils cap modes) in words. */
    fun decodeCapsMode(capsMode: Int): String {
        if (capsMode == 0) return "none"
        val parts = ArrayList<String>()
        if (capsMode and 0x1000 != 0) parts += "characters"
        if (capsMode and 0x2000 != 0) parts += "words"
        if (capsMode and 0x4000 != 0) parts += "sentences"
        return parts.ifEmpty { listOf(hex(capsMode)) }.joinToString(", ")
    }

    private fun hex(value: Int) = "0x" + value.toUInt().toString(16)

    private const val MAX_DECLARED = 80
    private val NEWLINES = Regex("[\\r\\n\\t]+")

    private const val TYPE_NULL = 0
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_MASK_FLAGS = 0x00fff000
    private const val TYPE_CLASS_TEXT = 1
    private const val TYPE_CLASS_NUMBER = 2
    private const val TYPE_CLASS_PHONE = 3
    private const val TYPE_CLASS_DATETIME = 4

    private val TEXT_VARIATIONS = mapOf(
        0x00 to "normal",
        0x10 to "uri",
        0x20 to "email address",
        0x30 to "email subject",
        0x40 to "short message",
        0x50 to "long message",
        0x60 to "person name",
        0x70 to "postal address",
        0x80 to "password",
        0x90 to "visible password",
        0xa0 to "web edit text",
        0xb0 to "filter",
        0xc0 to "phonetic",
        0xd0 to "web email address",
        0xe0 to "web password",
    )

    private val TEXT_FLAGS = listOf(
        0x1000 to "caps characters",
        0x2000 to "caps words",
        0x4000 to "caps sentences",
        0x8000 to "auto correct",
        0x10000 to "auto complete",
        0x20000 to "multi line",
        0x40000 to "IME multi line",
        0x80000 to "no suggestions",
        0x100000 to "text conversion suggestions",
    )

    private const val IME_MASK_ACTION = 0x000000ff
    private val ACTIONS = mapOf(
        0 to "unspecified",
        1 to "none",
        2 to "go",
        3 to "search",
        4 to "send",
        5 to "next",
        6 to "done",
        7 to "previous",
    )

    private val IME_FLAGS = listOf(
        0x1000000 to "no personalized learning",
        0x2000000 to "no fullscreen",
        0x4000000 to "navigate previous",
        0x8000000 to "navigate next",
        0x10000000 to "no extract UI",
        0x20000000 to "no accessory action",
        0x40000000 to "no enter action",
        0x80000000.toInt() to "force ASCII",
    )
}

/**
 * The last report the keyboard took, for the settings app's Debug info
 * screen, which runs in the same process. Memory only: a report can name the
 * app the user was in, so it is never written anywhere until the user copies
 * or shares it.
 */
object KeyboardDebugSnapshot {
    /** One report and when it was taken (`System.currentTimeMillis`). */
    data class Taken(val text: String, val atMillis: Long)

    @Volatile
    var last: Taken? = null
}
