package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Names an input method imported from another IME's own file (a `.cin` table,
 * a Rime schema and dictionary, an m17n `.mim`) whose data decides what this
 * layout's keys type.
 *
 * The same shape as [KeymanBinding], for the same reason: a layout is data and
 * has to decode on a device that does not have the method's files, so only the
 * name travels. An exported layout stays small, and where the files are missing
 * the grid types its own key caps. `:core:input` looks the binding up; nothing
 * here depends on it.
 *
 * [kind] is stored beside the id so the keyboard knows how to treat the keys
 * (choose from a strip, or compose in place) before the files have been read,
 * which happens off the main thread.
 */
@Serializable
data class InputMethodBinding(
    /** The method's folder under the app's files, e.g. `im_1730000000000`. */
    val id: String,
    val kind: InputMethodKind = InputMethodKind.TABLE,
)

/** How an imported method turns keys into text. */
@Serializable
enum class InputMethodKind {
    /**
     * Each key run is a complete code for one entry, and the strip offers the
     * entries whose code starts with it: Cangjie, Array, Boshiamy, Wubi, Dayi.
     */
    @SerialName("table")
    TABLE,

    /**
     * Readings spelled syllable by syllable and converted as a phrase, the way
     * pinyin is: a Rime `script_translator` schema.
     */
    @SerialName("reading")
    READING,

    /** A program of rules that writes the text in place: an m17n `.mim`. */
    @SerialName("rules")
    RULES,

    /**
     * Regular-expression rules over the text before the cursor that write it
     * in place: a jquery.ime method (Wikimedia's, and Indic Keyboard's copy).
     * The ones that ship are bound by an id starting `jq:` and read from the
     * app's assets; an imported one lives under the app's files like the rest.
     */
    @SerialName("regex")
    REGEX,
}

/**
 * The grid an imported input method is typed on.
 *
 * None of the formats carries a keyboard. A `.cin` names a cap for each key
 * (`%keyname`), a Rime schema sometimes does (`preedit_format`), and an m17n
 * file only says which keys its rules read. All three assume a US keyboard, so
 * the grid is the US arrangement cut down to the keys the method uses, with
 * this app's frame put round it the way an imported foreign layout gets one.
 */
object InputMethodLayouts {

    /**
     * A finished, repaired layout for [binding].
     *
     * [keys] are the characters the method reads. Every letter is on the grid
     * whatever it says, since a missing letter would leave a hole in a row
     * people type by position; punctuation and digit keys appear only when the
     * method reads them. [labels] puts the method's own caps on the keys (a
     * Cangjie radical on `a`), and [shifted] adds a shift key whose capitals are
     * keys of their own, which ITRANS and InScript need and a code table does
     * not. [langId] is blank until the import screen has asked.
     */
    fun build(
        id: String,
        name: String,
        langId: String,
        binding: InputMethodBinding,
        keys: Set<Char>,
        labels: Map<Char, String> = emptyMap(),
        shifted: Boolean = false,
    ): LayoutSpec {
        fun wanted(c: Char): Boolean =
            c.isLetter() || c in keys || (shifted && UsShift[c]?.let { it in keys } == true)

        fun key(c: Char): Key {
            val label = labels[c]?.takeIf { it.isNotBlank() } ?: c.toString()
            return Key(
                label = label,
                output = c.toString(),
                shiftLabel = if (shifted) (UsShift[c] ?: c.uppercaseChar()).toString() else null,
            )
        }

        val rows = UsRows
            .mapIndexed { index, row ->
                // The digit row only when the method reads a digit or one of
                // the signs over them; everywhere else it is the number row's
                // job, which the settings already offer.
                if (index == 0 && row.none { it.isDigit() && (it in keys || UsShift[it] in keys) }) {
                    emptyList()
                } else {
                    row.filter(::wanted).map(::key)
                }
            }
            .filter { it.isNotEmpty() }
            .toMutableList()
        // A method with no capitals of its own places its delete key itself,
        // which is how the house frame is told not to add a shift key either
        // (see [withHouseStructure]).
        if (!shifted && rows.isNotEmpty()) {
            rows[rows.lastIndex] = rows.last() + Key("⌫", action = KeyAction.Delete, width = DELETE_WIDTH)
        }
        val layout = LayoutSpec(
            id = id,
            name = name,
            langId = langId,
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(rows = withHouseStructure(rows))),
            inputMethod = binding,
        )
        return layout.repair().spec
    }

    /**
     * The language a method most likely writes, for the import screen's
     * picker to start from: the one its file names ([declaredTag], m17n's
     * `(input-method hi itrans)`), else the one whose script most of [sample]
     * is in. Han text is Chinese; the caller knows when it is Cantonese.
     */
    fun guessLanguage(declaredTag: String?, sample: CharSequence): String {
        declaredTag
            ?.takeIf { it.isNotBlank() && it != "t" && it != "nil" }
            ?.let { LanguageRegistry.byLocale(it) }
            ?.let { return it.id }
        val counts = HashMap<ScriptId, Int>()
        sample.codePoints().forEach { cp ->
            val script = scriptDefs.firstOrNull { it.contains(cp) } ?: return@forEach
            counts[script.id] = (counts[script.id] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return DEFAULT_LANGUAGE
        if (dominant == ScriptId.HAN) return CHINESE
        return LanguageRegistry.all.firstOrNull { it.script == dominant }?.id ?: DEFAULT_LANGUAGE
    }

    /** Built once: `ScriptRegistry.all` copies its map on every read. */
    private val scriptDefs: List<ScriptDef> by lazy {
        ScriptRegistry.all.filter { !it.unicodeRange.isEmpty() }
    }

    private const val DEFAULT_LANGUAGE = "en"
    private const val CHINESE = "zh"

    /** The US keyboard's character rows, top to bottom. */
    private val UsRows: List<String> = listOf(
        "1234567890-=",
        "qwertyuiop[]\\",
        "asdfghjkl;'",
        "zxcvbnm,./",
    )

    /** What shift types on each US key that is not a letter. */
    private val UsShift: Map<Char, Char> = mapOf(
        '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
        '-' to '_', '=' to '+', '[' to '{', ']' to '}', '\\' to '|',
        ';' to ':', '\'' to '"', ',' to '<', '.' to '>', '/' to '?',
    )

    private const val DELETE_WIDTH = 1.5f
}
