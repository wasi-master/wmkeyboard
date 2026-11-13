package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A finished Espanso file and what writing it could not carry across. */
data class EspansoExport(val text: String, val notes: List<ContentText>)

/** What goes in a package's `_manifest.yml`. */
data class EspansoManifest(
    val name: String,
    val title: String,
    val description: String,
    val version: String = "0.1.0",
    val author: String = "",
    val homepage: String = "",
    val tags: List<String> = emptyList(),
) {

    companion object {

        /**
         * [raw] as a package name Espanso will accept.
         *
         * The specification is strict about this one field: lowercase letters,
         * digits and hyphens only, and it has to equal the directory name.
         */
        fun sanitizeName(raw: String): String {
            val out = StringBuilder(raw.length)
            for (c in raw.lowercase()) {
                when {
                    c.isDigit() || c in 'a'..'z' -> out.append(c)
                    out.isNotEmpty() && out.last() != '-' -> out.append('-')
                }
            }
            return out.toString().trim('-').ifEmpty { "wmkeyboard-snippets" }
        }
    }
}

/**
 * Writes snippets out as Espanso reads them.
 *
 * Two shapes, from one converter. A bare match file drops into Espanso's config
 * directory and works; a package archive adds the manifest and README that
 * `espanso install --path` and the Espanso Hub both want.
 *
 * The YAML is written by hand rather than by a library emitter. That keeps the
 * output readable — block scalars for anything multi-line, a comment marking
 * each folder — and it means the emitter half of the YAML library is never
 * referenced, so it shrinks away at build time.
 */
object EspansoWriter {

    /** MIME type a match file is saved under. */
    const val MIME_TYPE = "text/yaml"

    /** MIME type a package archive is saved under. */
    const val PACKAGE_MIME_TYPE = "application/zip"

    /** Default file name for a match file export. */
    const val FILE_NAME = "wmkeyboard.yml"

    /** Longest a generated variable name may need to be unique within a match. */
    private const val VAR_PREFIX = "wm"

    /**
     * Every snippet as one `matches:` list.
     *
     * Folders become comment headings, since Espanso has no folder of its own.
     * A snippet in a folder that is switched off is still written, because the
     * alternative is quietly losing it; the note says what that means.
     */
    fun encodeMatchFile(snippets: List<Snippet>, folders: List<SnippetFolder> = emptyList()): EspansoExport {
        val notes = EspansoNotes()
        val out = StringBuilder()
        out.append("# Exported from WM Keyboard.\n")
        out.append("# Drop this file in Espanso's match directory (espanso path config).\n\n")
        out.append("matches:\n")

        val byFolder = snippets.groupBy { it.folderId }
        val order = folders.filter { byFolder.containsKey(it.id) }
        val loose = byFolder[0L].orEmpty()

        for (folder in order) {
            out.append("\n  # ").append(comment(folder.name)).append('\n')
            val members = byFolder[folder.id].orEmpty()
            if (!folder.enabled && members.isNotEmpty()) {
                repeat(members.size) { notes.add(EspansoNote.DISABLED_FOLDER) }
            }
            for (snippet in members) out.append(match(snippet, notes))
        }
        if (loose.isNotEmpty()) {
            if (order.isNotEmpty()) out.append("\n  # ").append(comment(UNFILED)).append('\n')
            for (snippet in loose) out.append(match(snippet, notes))
        }
        return EspansoExport(out.toString(), notes.build())
    }

    private const val UNFILED = "Not in a folder"

    /**
     * A whole package archive: `package.yml`, `_manifest.yml` and a `README.md`.
     *
     * Everything sits at the archive root, which is what Espanso expects from a
     * package directory it is pointed at.
     */
    fun encodePackage(
        snippets: List<Snippet>,
        folders: List<SnippetFolder>,
        manifest: EspansoManifest,
    ): Pair<ByteArray, List<ContentText>> {
        val matches = encodeMatchFile(snippets, folders)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.write("package.yml", matches.text)
            zip.write("_manifest.yml", manifestYaml(manifest))
            zip.write("README.md", readme(manifest, snippets.size))
        }
        return bytes.toByteArray() to matches.notes
    }

    private fun ZipOutputStream.write(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private fun manifestYaml(manifest: EspansoManifest): String = buildString {
        append("name: ").append(scalar(EspansoManifest.sanitizeName(manifest.name))).append('\n')
        append("title: ").append(scalar(manifest.title.ifBlank { manifest.name })).append('\n')
        append("description: ").append(scalar(manifest.description)).append('\n')
        append("version: ").append(scalar(manifest.version.ifBlank { "0.1.0" })).append('\n')
        append("author: ").append(scalar(manifest.author)).append('\n')
        append("homepage: ").append(scalar(manifest.homepage)).append('\n')
        append("tags: [")
        append(manifest.tags.joinToString(", ") { scalar(it) })
        append("]\n")
    }

    private fun readme(manifest: EspansoManifest, count: Int): String = buildString {
        append("# ").append(manifest.title.ifBlank { manifest.name }).append("\n\n")
        if (manifest.description.isNotBlank()) append(manifest.description).append("\n\n")
        append(count).append(if (count == 1) " snippet" else " snippets")
        append(", exported from WM Keyboard.\n")
    }

    // ---- one match -------------------------------------------------------

    private fun match(snippet: Snippet, notes: EspansoNotes): String {
        val out = StringBuilder()
        val spellings = snippet.spellings()
        val pattern = snippet.triggerPattern?.takeIf { spellings.isEmpty() }

        // Several expansions travel as a `choice` when every one of them is
        // plain text: that is the only shape Espanso has for "pick one", and
        // it can hold nothing but literals. Anything else — a date, the
        // clipboard, a capture reference — and only the default goes out,
        // which is said rather than done quietly.
        val expansions = snippet.expansions()
        val choices = expansions.takeIf { it.size > 1 && pattern == null && it.all(::isPlainText) }
        val converted = convertText(snippet.text, pattern, notes)
        notes.addIf(choices == null && expansions.size > 1, EspansoNote.ALTERNATES)
        notes.addIf(snippet.children.isNotEmpty(), EspansoNote.LINKS)
        notes.addIf(snippet.confirm, EspansoNote.CONFIRM)
        notes.addIf(snippet.image != null, EspansoNote.PICTURE_EXPORT)

        out.append("  - ")
        when {
            pattern != null -> out.append("regex: ").append(scalar(toEspansoRegex(pattern, converted.groups)))
            spellings.size == 1 -> out.append("trigger: ").append(scalar(spellings.first()))
            spellings.size > 1 -> out.append("triggers: [")
                .append(spellings.joinToString(", ") { scalar(it) })
                .append(']')
            // Nothing fires it. Espanso needs *something* to key on, and its
            // search window is the natural home for a snippet you pick by hand.
            else -> out.append("trigger: ").append(scalar(fallbackTrigger(snippet)))
        }
        out.append('\n')

        if (snippet.label.isNotBlank()) {
            out.append("    label: ").append(scalar(snippet.label)).append('\n')
        }
        if (choices != null) {
            out.append("    replace: ").append(scalar("{{$CHOICE_VAR}}")).append('\n')
        } else {
            out.append("    replace: ").append(block(converted.text, indent = 4)).append('\n')
        }
        if (spellings.isNotEmpty() && pattern == null) {
            // Espanso's default is this app's "expand immediately, inside words
            // too", so only the other two shapes need saying: waiting for the
            // space is `word`, and firing at once at a word start is `left_word`.
            val early = snippet.immediate && !snippet.confirm
            when {
                !early -> out.append("    word: true\n")
                !snippet.insideWords -> out.append("    left_word: true\n")
            }
        }
        if (snippet.propagateCase) {
            out.append("    propagate_case: true\n")
            if (snippet.uppercaseStyle != UppercaseStyle.CAPITALIZE) {
                out.append("    uppercase_style: ").append(styleName(snippet.uppercaseStyle)).append('\n')
            }
        }
        if (choices != null) {
            out.append("    vars:\n")
            out.append(variable(CHOICE_VAR, "choice", "values", choices))
        } else if (converted.vars.isNotEmpty()) {
            out.append("    vars:\n")
            for (variable in converted.vars) out.append(variable)
        }
        return out.toString()
    }

    /** The name the choice variable an exported list of expansions goes under. */
    private const val CHOICE_VAR = "wmchoice"

    /**
     * True when [text] is text and nothing else: no template token, no capture
     * reference, no cursor marker.
     *
     * A `choice` entry is a literal Espanso types as it stands, so anything
     * that has to be resolved at insertion time cannot ride in one.
     */
    private fun isPlainText(text: String): Boolean =
        SnippetVariable.entries.none { text.contains(it.token) } &&
            !CUSTOM_DATE.containsMatchIn(text) &&
            !RANDOM.containsMatchIn(text) &&
            !REFERENCE.containsMatchIn(text)

    private fun styleName(style: UppercaseStyle): String = when (style) {
        UppercaseStyle.UPPERCASE -> "uppercase"
        UppercaseStyle.CAPITALIZE_WORDS -> "capitalize_words"
        UppercaseStyle.CAPITALIZE -> "capitalize"
    }

    /** A key for a snippet that has no trigger at all, from its label. */
    private fun fallbackTrigger(snippet: Snippet): String {
        val stem = snippet.label.filter { it.isLetterOrDigit() }.take(20).lowercase()
        return ":" + stem.ifEmpty { "wm${snippet.id}" }
    }

    // ---- the replacement -------------------------------------------------

    private class Converted(
        val text: String,
        val vars: List<String>,
        /** Capture number to the group name it was given, for the regex. */
        val groups: Map<Int, String>,
    )

    /** Tokens that become a `date` variable, and the strftime each needs. */
    private val DATE_TOKENS = mapOf(
        SnippetVariable.DATE to "%e %b %Y",
        SnippetVariable.TIME to "%H:%M",
        SnippetVariable.TIME12 to "%l:%M %p",
        SnippetVariable.DATETIME to "%e %b %Y %H:%M",
        SnippetVariable.ISODATE to "%Y-%m-%d",
        SnippetVariable.ISOTIME to "%Y-%m-%dT%H:%M:%S%z",
        SnippetVariable.WEEKDAY to "%A",
        SnippetVariable.DAY to "%e",
        SnippetVariable.MONTH to "%B",
        SnippetVariable.YEAR to "%Y",
        SnippetVariable.TIMEZONE to "%Z",
        SnippetVariable.TIMESTAMP to "%s",
    )

    /** Tokens Espanso has nothing to say. Written out as they stand. */
    private val UNTRANSLATABLE = setOf(
        SnippetVariable.UUID,
        SnippetVariable.APP,
        SnippetVariable.PACKAGE,
        SnippetVariable.SELECTION,
        SnippetVariable.GREETING,
        SnippetVariable.BATTERY,
        SnippetVariable.LANGUAGE,
    )

    private val CUSTOM_DATE = Regex("""\{date([+-]\d{1,9})?(?::([^}\n]{1,40}))?\}""")
    private val RANDOM = Regex("""\{random:([^}\n]{1,400})\}""")
    private val REFERENCE = Regex("""\$(\d)|\$\{(\d):([a-z]{1,8})\}""")

    /**
     * Snippet text as an Espanso replacement, and the variables it needs.
     *
     * Every `{token}` this app knows becomes a `{{name}}` reference plus a `vars`
     * entry, except the ones that describe the phone rather than the moment:
     * there is no Espanso equivalent for the app being typed into, so those are
     * written out literally and noted.
     */
    private fun convertText(text: String, pattern: String?, notes: EspansoNotes): Converted {
        val vars = ArrayList<String>()
        var counter = 0
        fun nextName(): String = "$VAR_PREFIX${++counter}"

        var out = text

        // Random first, and dates after it, in the same order the expander
        // resolves them, so a date inside a random alternative still converts.
        out = RANDOM.replace(out) { match ->
            val name = nextName()
            vars += variable(name, "random", "choices", splitAlternatives(match.groupValues[1]))
            "{{$name}}"
        }
        out = CUSTOM_DATE.replace(out) { match ->
            val name = nextName()
            val offset = match.groupValues[1].toLongOrNull()
            val pattern2 = match.groupValues[2].ifEmpty { "d MMM yyyy" }
            val strftime = EspansoDate.toStrftime(pattern2)
            notes.addIf(strftime.dropped.isNotEmpty(), EspansoNote.DATE)
            vars += dateVariable(name, strftime.value, offset)
            "{{$name}}"
        }
        for (variable in SnippetVariable.entries) {
            if (!out.contains(variable.token)) continue
            when {
                variable == SnippetVariable.CURSOR -> out = out.replace(variable.token, "\$|\$")
                variable == SnippetVariable.TAB -> out = out.replace(variable.token, "\t")
                variable == SnippetVariable.CLIP -> {
                    val name = nextName()
                    vars += variable(name, "clipboard")
                    out = out.replace(variable.token, "{{$name}}")
                }
                variable in UNTRANSLATABLE -> notes.add(EspansoNote.NO_ESPANSO_VARIABLE)
                DATE_TOKENS.containsKey(variable) -> {
                    val name = nextName()
                    vars += dateVariable(name, DATE_TOKENS.getValue(variable), null)
                    out = out.replace(variable.token, "{{$name}}")
                }
            }
        }

        // Fill-in fields become one Espanso form, which asks for all of them
        // in one window: `{ask:Name}` reads `{{wmform.Name}}`, and the form's
        // layout lists each field once with its default.
        val asks = SnippetStore.asks(out)
        if (asks.isNotEmpty()) {
            val fields = LinkedHashMap<String, Pair<String, String>>()
            for (ask in asks) fields[ask.name.lowercase()] = formFieldName(ask.name, fields.size) to ask.default
            out = ASK.replace(out) { match ->
                val field = fields[match.groupValues[1].trim().lowercase()] ?: return@replace match.value
                "{{$FORM_VAR.${field.first}}}"
            }
            vars += formVariable(asks.map { it.name to fields.getValue(it.name.lowercase()) })
        }

        val groups = LinkedHashMap<Int, String>()
        if (pattern != null) {
            out = REFERENCE.replace(out) { match ->
                val index = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
                    ?: return@replace match.value
                notes.addIf(match.groupValues[3].isNotEmpty(), EspansoNote.TRANSFORM)
                // Group 0 is the whole match, which Espanso cannot name.
                if (index == 0) return@replace match.value
                val name = groups.getOrPut(index) { "g$index" }
                "{{$name}}"
            }
            // What is left of `$$` was an escaped dollar here and is an ordinary
            // one there, since Espanso gives `$` no meaning in a replacement.
            out = out.replace("$$", "$")
        }
        return Converted(out, vars, groups)
    }

    private fun splitAlternatives(body: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && body.getOrNull(i + 1) == '|' -> {
                    current.append('|'); i += 2
                }
                c == '|' -> {
                    parts.add(current.toString()); current.setLength(0); i++
                }
                else -> {
                    current.append(c); i++
                }
            }
        }
        parts.add(current.toString())
        return parts
    }

    /** `{ask:Name}` or `{ask:Name:default}`, as [SnippetStore.asks] reads them. */
    private val ASK = Regex("""\{(?i:ask):([^}:\n]{1,40})(?::([^}\n]{0,200}))?\}""")

    /** The name the form an exported snippet's fill-in fields share goes under. */
    private const val FORM_VAR = "wmform"

    /**
     * [name] as an Espanso form field, which takes letters, digits and
     * underscores only; `f` and its position when nothing of it survives.
     */
    private fun formFieldName(name: String, index: Int): String =
        name.trim().replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').take(64).ifEmpty { "f${index + 1}" }

    /** The `form` variable for [fields]: label, then Espanso field name and default. */
    private fun formVariable(fields: List<Pair<String, Pair<String, String>>>): String = buildString {
        append("      - name: ").append(FORM_VAR).append('\n')
        append("        type: form\n")
        append("        params:\n")
        val layout = fields.joinToString("\n") { (label, field) -> "$label: [[${field.first}]]" }
        append("          layout: ").append(scalar(layout)).append('\n')
        val defaults = fields.filter { it.second.second.isNotEmpty() }
        if (defaults.isNotEmpty()) {
            append("          fields:\n")
            for ((_, field) in defaults) {
                append("            ").append(field.first).append(":\n")
                append("              default: ").append(scalar(field.second)).append('\n')
            }
        }
    }

    private fun variable(name: String, type: String): String =
        "      - name: $name\n        type: $type\n"

    private fun variable(name: String, type: String, key: String, values: List<String>): String =
        buildString {
            append("      - name: ").append(name).append('\n')
            append("        type: ").append(type).append('\n')
            append("        params:\n")
            append("          ").append(key).append(":\n")
            for (value in values) append("            - ").append(scalar(value)).append('\n')
        }

    private fun dateVariable(name: String, format: String, offset: Long?): String = buildString {
        append("      - name: ").append(name).append('\n')
        append("        type: date\n")
        append("        params:\n")
        append("          format: ").append(scalar(format)).append('\n')
        if (offset != null && offset != 0L) append("          offset: ").append(offset).append('\n')
    }

    /**
     * This app's pattern as one Espanso will run.
     *
     * Each capture the text refers to gets a name, because Espanso reads
     * captures back by name and has no numbered form. A group the text never
     * refers to is left as it is.
     */
    private fun toEspansoRegex(pattern: String, groups: Map<Int, String>): String {
        if (groups.isEmpty()) return pattern
        val out = StringBuilder(pattern.length + groups.size * 8)
        var number = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                out.append(c).append(pattern.getOrNull(i + 1) ?: ' ')
                i += 2
                continue
            }
            if (c != '(') {
                out.append(c)
                i++
                continue
            }
            if (pattern.getOrNull(i + 1) == '?') {
                out.append(c)
                i++
                continue
            }
            number++
            val name = groups[number]
            out.append(if (name == null) "(" else "(?P<$name>")
            i++
        }
        return out.toString()
    }

    // ---- YAML scalars ----------------------------------------------------

    /** A comment body, with anything that would end the line taken out. */
    private fun comment(text: String): String = text.replace('\n', ' ').replace('\r', ' ')

    /**
     * A single-line YAML scalar, always double-quoted.
     *
     * Quoting unconditionally rather than only when needed: the values here are
     * triggers and labels, and the list of bare words YAML reads as something
     * else (`yes`, `no`, `null`, `on`, a number, a date) is long enough that
     * deciding case by case would be the bug rather than the noise.
     */
    private fun scalar(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\x").append("%02x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * A value that may run to several lines: a block scalar where one is safe,
     * and a quoted single line where it is not.
     *
     * A block scalar cannot carry a line with trailing whitespace or a tab, and
     * cannot begin with a space, since indentation is what delimits it. Rather
     * than mangle such a value to fit, those fall back to the quoted form, which
     * is uglier to read and exactly right.
     */
    private fun block(text: String, indent: Int): String {
        if (!text.contains('\n')) return scalar(text)
        val lines = text.split("\n")
        val safe = lines.none { it.endsWith(" ") || it.contains('\t') || it.contains('\r') } &&
            !lines.first().startsWith(" ")
        if (!safe) return scalar(text)
        val pad = " ".repeat(indent + 2)
        // "|-" keeps the newlines inside and drops the one a block would
        // otherwise add at the end, which no snippet asked for.
        val out = StringBuilder("|-")
        for (line in lines) out.append('\n').append(pad).append(line)
        return out.toString()
    }
}
