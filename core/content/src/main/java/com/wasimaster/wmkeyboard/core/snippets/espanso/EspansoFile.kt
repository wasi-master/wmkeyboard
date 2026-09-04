package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle

/** What reading an Espanso file produced, and what it cost. */
data class EspansoImport(
    val snippets: List<Snippet>,
    /** What to call the folder these arrive in: a package title, else a file name. */
    val folderName: String,
    /** What was dropped or degraded, one line per kind. */
    val notes: List<ContentText>,
    /** Matches seen, including the ones that produced no snippet. */
    val total: Int,
)

/**
 * Reads an Espanso match file into snippets.
 *
 * Espanso is a cross-platform desktop text expander, and the Espanso Hub is a
 * few hundred community packages of ready-made snippets. Its match file is YAML
 * with a top-level `matches:` list; everything this reader understands about
 * that format comes from Espanso's published documentation and public schema,
 * and every test fixture is hand-written, in the same spirit as the FlorisBoard
 * and HeliBoard layout readers.
 *
 * The rule throughout is convert-and-report rather than refuse. A package whose
 * one odd match uses a form should still bring the other four hundred in, with a
 * line saying what the odd one lost. The one thing that is never a degradation
 * is a `shell` or `script` variable: those are dropped without being run, in
 * every direction, always.
 */
object EspansoFile {

    /** Largest file this reads, matching what an add-on payload may be. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Most snippets one file may bring in, matching [SnippetFile]'s own cap. */
    const val MAX_SNIPPETS = 500

    /** Longest snippet text kept, matching [SnippetFile]'s own cap. */
    const val MAX_TEXT_LENGTH = 20_000

    /** File extensions an Espanso match file is found under. */
    val EXTENSIONS = listOf("yml", "yaml")

    /** How deep a variable may refer to another before the reader gives up. */
    private const val MAX_VAR_DEPTH = 4

    /** Espanso's marker for where the caret should land. */
    private const val CURSOR_HINT = "\$|\$"

    /** `{{name}}` or `{{form.field}}` inside a replacement. */
    private val INJECTION = Regex("""\{\{\s*([A-Za-z0-9_.]{1,64})\s*\}\}""")

    /** `[[field]]` inside a form layout. */
    private val FORM_FIELD = Regex("""\[\[\s*([A-Za-z0-9_]{1,64})\s*\]\]""")

    /** Espanso's named capture groups, which Java spells without the P. */
    private val NAMED_GROUP = Regex("""\(\?P<([A-Za-z][A-Za-z0-9]*)>""")

    /**
     * True when [text] looks like an Espanso match file.
     *
     * Checked before parsing so a `.wmsnippets.json` picked from the wrong row
     * is not run through a YAML parser, and so the payload sniff in
     * [SnippetPayload] can be ordered tagged-format-first. A `matches:` key at
     * the start of a line is the whole signal: the format has no version tag of
     * its own, which is exactly why it must be tried last.
     */
    fun looksLikeEspanso(text: String): Boolean =
        Regex("""(?m)^\s{0,4}(matches|global_vars|imports)\s*:""").containsMatchIn(text)

    /**
     * Reads [text] as a match file, or returns null when it is not one.
     *
     * [name] names the folder the snippets land in, unless [folderName]
     * overrides it with something better, such as a package manifest's title.
     */
    fun read(text: String, name: String, folderName: String? = null): EspansoImport? {
        val root = EspansoYaml.asMap(EspansoYaml.load(text, MAX_BYTES)) ?: return null
        val matches = EspansoYaml.asList(root["matches"])
        if (matches.isEmpty() && root["matches"] == null) return null

        val notes = EspansoNotes()
        notes.addIf(EspansoYaml.asList(root["imports"]).isNotEmpty(), EspansoNote.IMPORTS)

        val globals = readVariables(EspansoYaml.asList(root["global_vars"]))
        val snippets = ArrayList<Snippet>(minOf(matches.size, MAX_SNIPPETS))
        var total = 0
        for (entry in matches) {
            total++
            if (snippets.size >= MAX_SNIPPETS) continue
            val map = EspansoYaml.asMap(entry) ?: continue
            snippets += convert(map, globals, notes) ?: continue
        }
        return EspansoImport(
            snippets = snippets,
            folderName = (folderName ?: fallbackFolderName(name)).trim().ifEmpty { name },
            notes = notes.build(),
            total = total,
        )
    }

    /** A file name with its extension and any version suffix taken off. */
    private fun fallbackFolderName(name: String): String =
        name.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()

    // ---- one match -------------------------------------------------------

    @Suppress("ReturnCount")
    private fun convert(
        match: Map<String, Any?>,
        globals: Map<String, EspansoVariable>,
        notes: EspansoNotes,
    ): Snippet? {
        notes.addIf(
            EspansoYaml.asList(match["apps"]).isNotEmpty() ||
                EspansoYaml.asList(match["exclude_apps"]).isNotEmpty(),
            EspansoNote.APPS,
        )

        val locals = readVariables(EspansoYaml.asList(match["vars"]))
        val body = readBody(match, locals, notes) ?: run {
            notes.add(EspansoNote.EMPTY)
            return null
        }

        val triggers = readTriggers(match, notes)
        val regex = EspansoYaml.asText(match["regex"])?.trim()?.takeIf { it.isNotEmpty() }
        // Espanso reads a `regex` match only when no plain trigger is given, and
        // so does this app: the specific rule wins over the general one.
        val pattern = if (triggers.isEmpty() && regex != null) convertRegex(regex, notes) else null

        // A replacement that is nothing but one `choice` variable is Espanso
        // asking the user to pick, and this app can now ask the same question:
        // the values become the snippet's expansions and the strip offers them.
        // A choice buried in a longer sentence still becomes a `{random}`,
        // since only part of the text would be up for choosing.
        val choices = wholeChoice(body, locals, globals)
        if (choices.size > 1) {
            return choiceSnippet(body, choices, match, notes, globals, locals)
        }
        var text = body.replace(CURSOR_HINT, SnippetVariable.CURSOR.token)
        // Dollars are escaped before anything is injected, not after. In a
        // pattern snippet `$1` means a capture, and Espanso's replacement meant
        // it literally, because Espanso spells captures `{{name}}` instead. The
        // references this reader puts in below are meant as references.
        if (pattern != null) text = escapeDollars(text)
        text = inject(text, globals + locals, groupNames(pattern), notes, depth = 0)
        if (text.isBlank()) {
            notes.add(EspansoNote.EMPTY)
            return null
        }
        if (text.length > MAX_TEXT_LENGTH) text = text.take(MAX_TEXT_LENGTH)
        notes.addIf(hasBareToken(body), EspansoNote.TOKEN)

        val label = EspansoYaml.asText(match["label"])?.trim()?.takeIf { it.isNotEmpty() }
            ?: text.lineSequence().first().trim().take(LABEL_MAX).ifEmpty { text.take(LABEL_MAX) }

        return snippet(label, text, triggers, pattern, match)
    }

    /**
     * Each named capture group in [pattern] and the number it answers to.
     *
     * Java numbers every capturing group left to right whether or not it has a
     * name, so an unnamed group in front of a named one shifts it along. The
     * shapes that open with `(?` and do *not* capture — `(?:`, `(?=`, `(?!`,
     * `(?<=`, `(?<!`, an inline flag group — are skipped, and `(?<` is only a
     * name when what follows it is a letter.
     */
    private fun groupNames(pattern: String?): Map<String, Int> {
        if (pattern == null || !pattern.contains("(?<")) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        var number = 0
        var i = 0
        while (i < pattern.length) {
            val named = namedGroupEnd(pattern, i)
            i = when {
                pattern[i] == '\\' -> i + 2
                pattern[i] != '(' -> i + 1
                named > 0 -> {
                    number++
                    out[pattern.substring(i + 3, named)] = number
                    named + 1
                }
                // "(?" opens something that does not capture: a non-capturing
                // group, a lookaround, or an inline flag group.
                pattern.getOrNull(i + 1) == '?' -> i + 2
                else -> {
                    number++
                    i + 1
                }
            }
        }
        return out
    }

    /** Index of the `>` closing a `(?<name>` that starts at [at], else -1. */
    private fun namedGroupEnd(pattern: String, at: Int): Int {
        if (pattern[at] != '(') return -1
        if (pattern.getOrNull(at + 1) != '?' || pattern.getOrNull(at + 2) != '<') return -1
        // "(?<=" and "(?<!" are lookbehind, not a name.
        if (pattern.getOrNull(at + 3)?.isLetter() != true) return -1
        return pattern.indexOf('>', startIndex = at + 3)
    }

    private const val LABEL_MAX = 60

    /**
     * The values of the one `choice` variable [body] consists of, or an empty
     * list when it is anything else.
     *
     * Deliberately narrow: the whole replacement has to be the reference and
     * nothing besides, so what the user picks is the whole of what gets typed.
     * Anything looser and half a sentence would silently become a menu.
     */
    private fun wholeChoice(
        body: String,
        locals: Map<String, EspansoVariable>,
        globals: Map<String, EspansoVariable>,
    ): List<String> {
        val whole = INJECTION.matchEntire(body.trim()) ?: return emptyList()
        val reference = whole.groupValues[1]
        val variable = locals[reference] ?: globals[reference] ?: return emptyList()
        if (variable.type != "choice" && variable.type != "list") return emptyList()
        return EspansoYaml.asList(variable.params["values"])
            .map(::choiceLabel)
            .mapNotNull { EspansoYaml.asText(it)?.takeIf(String::isNotBlank) }
            .distinct()
    }

    /** A choice-only match as a snippet whose expansions are the choices. */
    private fun choiceSnippet(
        body: String,
        choices: List<String>,
        match: Map<String, Any?>,
        notes: EspansoNotes,
        globals: Map<String, EspansoVariable>,
        locals: Map<String, EspansoVariable>,
    ): Snippet? {
        val triggers = readTriggers(match, notes)
        val regex = EspansoYaml.asText(match["regex"])?.trim()?.takeIf { it.isNotEmpty() }
        val pattern = if (triggers.isEmpty() && regex != null) convertRegex(regex, notes) else null
        val expansions = choices.map { choice ->
            var text = choice.replace(CURSOR_HINT, SnippetVariable.CURSOR.token)
            if (pattern != null) text = escapeDollars(text)
            inject(text, globals + locals, groupNames(pattern), notes, depth = 0).take(MAX_TEXT_LENGTH)
        }.filter { it.isNotBlank() }
        if (expansions.size < 2) return null
        val label = EspansoYaml.asText(match["label"])?.trim()?.takeIf { it.isNotEmpty() }
            ?: expansions.first().lineSequence().first().trim().take(LABEL_MAX)
        notes.addIf(hasBareToken(body), EspansoNote.TOKEN)
        return snippet(label, expansions.first(), triggers, pattern, match).copy(
            alternates = expansions.drop(1),
            // Espanso's choice is a picker, so nothing is chosen for the user.
            multiExpand = MultiExpand.CHIPS_ONLY,
        )
    }

    private fun snippet(
        label: String,
        text: String,
        triggers: List<String>,
        pattern: String?,
        match: Map<String, Any?>,
    ): Snippet {
        val propagate = EspansoYaml.asBoolean(match["propagate_case"]) ?: false
        return Snippet(
            id = 0,
            label = label,
            text = text,
            trigger = triggers.firstOrNull(),
            aliases = triggers.drop(1),
            propagateCase = propagate,
            uppercaseStyle = readUppercaseStyle(match["uppercase_style"]),
            triggerPattern = pattern,
            triggerWords = if (pattern == null) 0 else SnippetMatcher.DEFAULT_WORDS,
        )
    }

    private fun readUppercaseStyle(value: Any?): UppercaseStyle =
        when (EspansoYaml.asText(value)?.trim()?.lowercase()) {
            "uppercase" -> UppercaseStyle.UPPERCASE
            "capitalize_words" -> UppercaseStyle.CAPITALIZE_WORDS
            else -> UppercaseStyle.CAPITALIZE
        }

    /**
     * The match's triggers, in the order Espanso would try them.
     *
     * A trigger that is all punctuation (`->`) is dropped and noted: it never
     * produces a composing word, so there would be nothing to look it up by.
     * See [SnippetMatcher.splitPrefix].
     */
    private fun readTriggers(match: Map<String, Any?>, notes: EspansoNotes): List<String> {
        val raw = buildList {
            EspansoYaml.asText(match["trigger"])?.let(::add)
            EspansoYaml.asList(match["triggers"]).forEach { value ->
                EspansoYaml.asText(value)?.let(::add)
            }
        }
        val out = ArrayList<String>(raw.size)
        for (trigger in raw) {
            val clean = trigger.trim()
            if (clean.isEmpty()) continue
            if (matchable(clean)) out.add(clean) else notes.add(EspansoNote.SYMBOL_TRIGGER)
        }
        // Espanso defaults `word` to false, meaning a trigger may fire in the
        // middle of a word. This app always requires a boundary, and a trigger
        // that leads with punctuation does not care either way.
        val wordless = EspansoYaml.asBoolean(match["word"]) == false ||
            (match["word"] == null && match["left_word"] == null && match["right_word"] == null)
        notes.addIf(
            wordless && out.any { SnippetMatcher.splitPrefix(it) == null },
            EspansoNote.MID_WORD,
        )
        return out
    }

    /**
     * True when this app could actually watch for [trigger].
     *
     * Three shapes cannot be watched for, all for the same reason: the keyboard
     * looks a trigger up by the word in its composing buffer, and that buffer
     * only ever holds letters, digits and apostrophes. So a multi-word trigger
     * has no single word to look up, a trigger that is all punctuation has no
     * word at all, and one with punctuation anywhere but the front would have
     * been broken apart before the lookup happened.
     */
    private fun matchable(trigger: String): Boolean = when {
        trigger.any(Char::isWhitespace) -> false
        trigger.all(SnippetMatcher::isTriggerWordChar) -> true
        else -> SnippetMatcher.splitPrefix(trigger) != null
    }

    /**
     * Espanso's regular expression as one this app will run, or null.
     *
     * Two things change. Espanso spells a named group `(?P<name>…)` after Rust;
     * `java.util.regex` spells it `(?<name>…)`. And Espanso reads those captures
     * back as `{{name}}` while this app reads them as `$1`, which [inject] has
     * already rewritten by the time this runs.
     *
     * Whatever comes out still has to pass [SnippetMatcher.validate], which is
     * what keeps a downloaded pattern off the typing path if it is the kind that
     * can stall one.
     */
    private fun convertRegex(regex: String, notes: EspansoNotes): String? {
        val java = NAMED_GROUP.replace(regex) { "(?<${it.groupValues[1]}>" }
        if (SnippetMatcher.validate(java) != null) {
            notes.add(EspansoNote.REGEX)
            return null
        }
        return java
    }

    /**
     * Doubles every `$` in [text].
     *
     * Only for a match that became a pattern snippet. There, `$1` means a
     * capture, and Espanso's replacement text meant it literally — a dollar and
     * a one — because Espanso spells captures `{{name}}` instead.
     */
    private fun escapeDollars(text: String): String = text.replace("$", "$$")

    /** True when [text] already holds something this app expands. */
    private fun hasBareToken(text: String): Boolean =
        SnippetVariable.entries.any { text.contains(it.token) } ||
            Regex("""\{date[+\-:}]""").containsMatchIn(text) ||
            text.contains("{random:")

    // ---- the replacement -------------------------------------------------

    /**
     * The text a match inserts, whichever of Espanso's five ways it says it.
     *
     * The order is Espanso's own: plain text, then rich text, then a form, then
     * an image. Rich text is flattened, since this app commits plain text to the
     * field it is typing into. A form keeps its layout with the first field
     * turned into a cursor marker and the rest left visible, which is the most
     * useful thing to do without a fill-in panel.
     */
    private fun readBody(
        match: Map<String, Any?>,
        locals: Map<String, EspansoVariable>,
        notes: EspansoNotes,
    ): String? {
        EspansoYaml.asText(match["replace"])?.let { return it }
        EspansoYaml.asText(match["markdown"])?.let {
            notes.add(EspansoNote.RICH_TEXT)
            return EspansoRichText.fromMarkdown(it)
        }
        EspansoYaml.asText(match["html"])?.let {
            notes.add(EspansoNote.RICH_TEXT)
            return EspansoRichText.fromHtml(it)
        }
        EspansoYaml.asText(match["form"])?.let {
            notes.add(EspansoNote.FORM)
            return flattenForm(it)
        }
        if (match["image_path"] != null) {
            notes.add(EspansoNote.IMAGE)
            return null
        }
        // The verbose form: `replace` is absent and a `form` variable carries
        // the layout instead. Its fields are read the same way.
        val form = locals.values.firstOrNull { it.type == "form" } ?: return null
        val layout = EspansoYaml.asText(form.params["layout"]) ?: return null
        notes.add(EspansoNote.FORM)
        return flattenForm(layout)
    }

    /**
     * A form layout as ordinary snippet text.
     *
     * The first `[[field]]` becomes the cursor marker, so the caret lands where
     * the first answer goes. The rest stay as they are written: a visible
     * `[[email]]` is a person's cue to type an address there, and blanking them
     * would leave a sentence with holes in it and nothing to say what belongs
     * in each.
     */
    private fun flattenForm(layout: String): String {
        var first = true
        return FORM_FIELD.replace(layout) { match ->
            if (first) {
                first = false
                SnippetVariable.CURSOR.token
            } else {
                match.value
            }
        }
    }

    // ---- variables -------------------------------------------------------

    private class EspansoVariable(
        val name: String,
        val type: String,
        val params: Map<String, Any?>,
    )

    private fun readVariables(list: List<Any?>): Map<String, EspansoVariable> {
        if (list.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, EspansoVariable>(list.size)
        for (entry in list) {
            val map = EspansoYaml.asMap(entry) ?: continue
            val name = EspansoYaml.asText(map["name"])?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val type = EspansoYaml.asText(map["type"])?.trim()?.lowercase().orEmpty()
            out[name] = EspansoVariable(name, type, EspansoYaml.asMap(map["params"]).orEmpty())
        }
        return out
    }

    /**
     * Replaces every `{{name}}` in [text] with what this app can say instead.
     *
     * A variable may refer to another — Espanso's own examples build a full name
     * out of two `echo`s — so this recurses, bounded by [MAX_VAR_DEPTH]. A
     * reference to a variable that is not there resolves to nothing, which is
     * what Espanso does too.
     */
    private fun inject(
        text: String,
        vars: Map<String, EspansoVariable>,
        groups: Map<String, Int>,
        notes: EspansoNotes,
        depth: Int,
    ): String {
        if (depth > MAX_VAR_DEPTH || !text.contains("{{")) return text
        return INJECTION.replace(text) { match ->
            val reference = match.groupValues[1]
            // In a regex match, `{{name}}` is a capture group rather than a
            // variable. Espanso spells both the same way, so the groups the
            // pattern actually declares are checked first.
            val group = groups[reference]
            if (group != null && group <= SnippetMatcher.MAX_GROUPS) {
                return@replace "$$group"
            }
            // A form field reads as `form1.field`; the value is the field, and
            // the form itself has already been flattened into the layout.
            val variable = vars[reference] ?: vars[reference.substringBefore('.')]
            if (variable == null) {
                ""
            } else {
                inject(valueOf(variable, notes), vars, groups, notes, depth + 1)
            }
        }
    }

    /** One Espanso variable as this app's own template text. */
    @Suppress("ReturnCount")
    private fun valueOf(variable: EspansoVariable, notes: EspansoNotes): String {
        val params = variable.params
        when (variable.type) {
            "echo", "dummy" -> return EspansoYaml.asText(params["echo"]).orEmpty()
            "clipboard" -> return SnippetVariable.CLIP.token
            "date" -> return dateToken(params, notes)
            "random" -> return randomToken(EspansoYaml.asList(params["choices"]))
            "choice", "list" -> {
                notes.add(EspansoNote.CHOICE)
                return randomToken(EspansoYaml.asList(params["values"]).map(::choiceLabel))
            }
            // Never run, in any direction. An imported file is somebody else's
            // text, and nothing in this app turns it into a command.
            "shell", "script" -> {
                notes.add(EspansoNote.SHELL)
                return ""
            }
            "form" -> return ""
            else -> {
                notes.add(EspansoNote.VARIABLE)
                return ""
            }
        }
    }

    /** A choice entry's visible text, whether it is a bare string or a map. */
    private fun choiceLabel(value: Any?): Any? {
        val map = EspansoYaml.asMap(value) ?: return value
        return map["label"] ?: map["id"]
    }

    private fun randomToken(choices: List<Any?>): String {
        val parts = choices.mapNotNull { EspansoYaml.asText(it)?.replace("|", "\\|") }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts.first()
        return "{random:${parts.joinToString("|")}}"
    }

    /** Espanso's `date` extension as a `{date}` token, offset and all. */
    private fun dateToken(params: Map<String, Any?>, notes: EspansoNotes): String {
        val strftime = EspansoYaml.asText(params["format"])
        val offset = EspansoYaml.asLong(params["offset"]) ?: 0L
        val shift = when {
            offset > 0 -> "+$offset"
            offset < 0 -> offset.toString()
            else -> ""
        }
        if (strftime.isNullOrBlank()) return "{date$shift}"
        val converted = EspansoDate.toPattern(strftime)
        notes.addIf(converted.dropped.isNotEmpty(), EspansoNote.DATE)
        if (converted.value.isBlank()) return "{date$shift}"
        // A pattern holding a brace or a newline could not be read back out of
        // the token, so it falls back to the default rather than corrupting it.
        if (converted.value.any { it == '}' || it == '\n' }) return "{date$shift}"
        return "{date$shift:${converted.value}}"
    }
}
