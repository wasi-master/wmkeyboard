package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
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
    /**
     * The pictures the image matches insert, by the [Snippet.image] name each
     * one was given. Written beside the snippets before they are added; see
     * [com.wasimaster.wmkeyboard.core.snippets.SnippetStore.writeImages].
     */
    val images: Map<String, ByteArray> = emptyMap(),
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
     *
     * [image] fetches the file an `image_path` match names, given the path as
     * written; null, or an answer of null, means the file is out of reach and
     * the match is skipped with a note. A lone match file has nothing beside
     * it to reach; a package archive has its own files ([EspansoPackage]).
     */
    fun read(
        text: String,
        name: String,
        folderName: String? = null,
        image: ((path: String) -> ByteArray?)? = null,
    ): EspansoImport? {
        val root = EspansoYaml.asMap(EspansoYaml.load(text, MAX_BYTES)) ?: return null
        val matches = EspansoYaml.asList(root["matches"])
        if (matches.isEmpty() && root["matches"] == null) return null

        val notes = EspansoNotes()
        notes.addIf(EspansoYaml.asList(root["imports"]).isNotEmpty(), EspansoNote.IMPORTS)

        val globals = readVariables(EspansoYaml.asList(root["global_vars"]))
        val snippets = ArrayList<Snippet>(minOf(matches.size, MAX_SNIPPETS))
        val images = LinkedHashMap<String, ByteArray>()
        var total = 0
        for (entry in matches) {
            total++
            if (snippets.size >= MAX_SNIPPETS) continue
            val map = EspansoYaml.asMap(entry) ?: continue
            imageSnippet(map, image, images, notes)?.let {
                snippets += it
                continue
            }
            snippets += convert(map, globals, notes) ?: continue
        }
        return EspansoImport(
            snippets = snippets,
            folderName = (folderName ?: fallbackFolderName(name)).trim().ifEmpty { name },
            notes = notes.build(),
            total = total,
            images = images,
        )
    }

    /** A file name with its extension and any version suffix taken off. */
    private fun fallbackFolderName(name: String): String =
        name.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()

    // ---- one match -------------------------------------------------------

    /**
     * An `image_path` match as a picture snippet, when the file can be had.
     *
     * Only a match whose whole replacement is the picture: one that also says
     * `replace`, `markdown`, `html` or `form` is text, as Espanso reads it too.
     * Null when the file is out of reach, too large, or not a picture, and
     * then [readBody] notes the match as skipped.
     */
    private fun imageSnippet(
        match: Map<String, Any?>,
        fetch: ((String) -> ByteArray?)?,
        images: MutableMap<String, ByteArray>,
        notes: EspansoNotes,
    ): Snippet? {
        if (fetch == null) return null
        if (TEXT_KEYS.any { match[it] != null }) return null
        val path = EspansoYaml.asText(match["image_path"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { fetch(path) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > SnippetStore.MAX_IMAGE_BYTES) return null
        // The bytes decide, not the path: `$CONFIG/cat` has no extension, and a
        // `.png` that is really a text file must not be offered to a chat app.
        SnippetStore.sniffExtension(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES))) ?: return null
        notes.addIf(
            EspansoYaml.asList(match["apps"]).isNotEmpty() ||
                EspansoYaml.asList(match["exclude_apps"]).isNotEmpty(),
            EspansoNote.APPS,
        )
        val triggers = readTriggers(match, notes)
        val regex = EspansoYaml.asText(match["regex"])?.trim()?.takeIf { it.isNotEmpty() }
        val pattern = if (triggers.isEmpty() && regex != null) convertRegex(regex, notes) else null
        if (triggers.isEmpty() && pattern == null) return null
        val stored = SnippetStore.newImageName(null, bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)))
        images[stored] = bytes
        val file = path.substringAfterLast('/').substringAfterLast('\\')
        val label = EspansoYaml.asText(match["label"])?.trim()?.takeIf { it.isNotEmpty() }
            ?: file.substringBeforeLast('.').ifEmpty { file }.take(LABEL_MAX)
        return snippet(label, "", triggers, pattern, match).copy(image = stored)
    }

    /** The keys that make a match text, whatever else it says. */
    private val TEXT_KEYS = listOf("replace", "markdown", "html", "form")

    /** Bytes a picture's type is read from. */
    private const val SNIFF_BYTES = 16

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
        // A regex is matched over whole words at the space, never early.
        val (immediate, insideWords) = if (pattern == null && triggers.isNotEmpty()) {
            readTiming(match)
        } else {
            false to false
        }
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
            immediate = immediate,
            insideWords = insideWords,
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
     * A trigger the keyboard could never watch for is dropped and noted; see
     * [SnippetMatcher.isMatchableTrigger]. One ending in a symbol (`->`, `x:`)
     * is kept: it fires off the field when that symbol lands (#471).
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
        // `right_word` alone waits for a separator after the trigger but lets
        // it start mid-word. This app fires at the separator only for a trigger
        // with a boundary in front, so a trigger that starts with a letter
        // loses its mid-word reach; one that starts with punctuation does not.
        // Every other shape maps across exactly — see [readTiming].
        val rightOnly = flag(match, "right_word") && !flag(match, "word") && !flag(match, "left_word")
        notes.addIf(rightOnly && out.any(::needsBoundary), EspansoNote.MID_WORD)
        return out
    }

    /**
     * When Espanso would fire this match's triggers, as [Snippet.immediate]
     * and [Snippet.insideWords].
     *
     * `word: true`, or `right_word`, waits for a separator after the trigger:
     * this app's ordinary trigger. With no word option at all Espanso fires the
     * moment the last character is typed, anywhere, even inside a word.
     * `left_word` alone fires at once too, but only at the start of a word.
     */
    private fun readTiming(match: Map<String, Any?>): Pair<Boolean, Boolean> = when {
        flag(match, "word") || flag(match, "right_word") -> false to false
        flag(match, "left_word") -> true to false
        else -> true to true
    }

    private fun flag(match: Map<String, Any?>, key: String): Boolean = EspansoYaml.asBoolean(match[key]) == true

    /**
     * True when this app could actually watch for [trigger].
     *
     * A plain word, a word behind a lead-in, or a trigger ending in a symbol
     * (`->`, `x:`), which is looked up by that symbol and confirmed off the
     * field.
     */
    private fun matchable(trigger: String): Boolean =
        // `js:`, `->` count too: fired off the field when the last symbol lands (#471).
        SnippetMatcher.isMatchableTrigger(trigger)

    /**
     * True when this app would insist on a word boundary in front of [trigger]
     * where Espanso would not.
     *
     * Punctuation is its own boundary, so only a trigger whose first character
     * is a word character loses anything to the stricter rule.
     */
    private fun needsBoundary(trigger: String): Boolean =
        trigger.firstOrNull()?.let(SnippetMatcher::isTriggerWordChar) == true

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
            return formText(it, EspansoYaml.asMap(match["form_fields"]).orEmpty(), notes)
        }
        if (match["image_path"] != null) {
            notes.add(EspansoNote.IMAGE)
            return null
        }
        // The verbose form: `replace` is absent and a `form` variable carries
        // the layout instead. Its fields are read the same way.
        val form = locals.values.firstOrNull { it.type == "form" } ?: return null
        val layout = EspansoYaml.asText(form.params["layout"]) ?: return null
        return formText(layout, EspansoYaml.asMap(form.params["fields"]).orEmpty(), notes)
    }

    /**
     * A form layout as snippet text whose fields are this app's fill-in
     * fields: `[[name]]` becomes `{ask:name}`, and the keyboard asks for each
     * one, in order, when the snippet expands.
     *
     * [fields] is the form's per-field settings. A `default` carries across.
     * A pick-one field has no list to pick from here, so it becomes a typed
     * field whose default is its first value, and that is noted.
     */
    private fun formText(layout: String, fields: Map<String, Any?>, notes: EspansoNotes): String =
        FORM_FIELD.replace(layout) { match -> askToken(match.groupValues[1], fields, notes) }

    /** `{ask:name}` for form field [name], with its default when it has one. */
    private fun askToken(name: String, fields: Map<String, Any?>, notes: EspansoNotes): String {
        val clean = name.trim().take(ASK_NAME_MAX)
        val field = EspansoYaml.asMap(fields[name]).orEmpty()
        val type = EspansoYaml.asText(field["type"])?.trim()?.lowercase()
        val choice = type == "choice" || type == "list"
        notes.addIf(choice, EspansoNote.FORM)
        val default = EspansoYaml.asText(field["default"])
            ?: if (choice) EspansoYaml.asList(field["values"]).firstNotNullOfOrNull { EspansoYaml.asText(choiceLabel(it)) } else null
        // A brace or a line break would end the token early; neither belongs
        // in a one-line answer anyway.
        val safe = default?.replace(Regex("[}\\r\\n]+"), " ")?.trim()?.take(ASK_DEFAULT_MAX).orEmpty()
        return if (safe.isEmpty()) "{ask:$clean}" else "{ask:$clean:$safe}"
    }

    /** Longest field name and default a token carries; see [SnippetStore.asks]. */
    private const val ASK_NAME_MAX = 40
    private const val ASK_DEFAULT_MAX = 200

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
            } else if (variable.type == "form" && reference.contains('.')) {
                // The verbose form's answer, `{{form1.name}}`: asked for when
                // the snippet expands, like the shorthand form's `[[name]]`.
                askToken(
                    reference.substringAfter('.'),
                    EspansoYaml.asMap(variable.params["fields"]).orEmpty(),
                    notes,
                )
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
            // Reached only for the bare `{{form1}}`; a field of it,
            // `{{form1.name}}`, is a fill-in field (see [inject]).
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
