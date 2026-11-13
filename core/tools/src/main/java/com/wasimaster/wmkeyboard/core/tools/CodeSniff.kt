package com.wasimaster.wmkeyboard.core.tools

/**
 * What a copied text is, when it is not prose: Markdown, or code in a
 * language [CodeHighlight] can colour, or code in no language it can name.
 * The clipboard panel uses it to set a clip in a monospace face with its
 * syntax coloured, or a Markdown clip drawn formatted.
 *
 * Built to say no: a chat message, an address or a shopping list must stay
 * plain text, so every verdict needs several lines that only code writes, or
 * one line no sentence would. Only the first [SCAN_CHARS] are read, so a whole
 * document costs as much as a page.
 */
object CodeSniff {

    sealed interface Shape

    /** Markdown: headings, lists, emphasis, links, fences, tables. */
    data object Markdown : Shape

    /**
     * Code. [language] is a [CodeHighlight] language id ("json", "python",
     * "sh", "xml", "kotlin", "java", "javascript", "sql", "css"), or null for
     * code [CodeHighlight] should not guess at: set in monospace, uncoloured.
     */
    data class Code(val language: String?) : Shape

    /** The name a language is shown under, for [Code.language]. */
    fun displayName(language: String): String = NAMES[language] ?: language.replaceFirstChar { it.uppercase() }

    private val NAMES = mapOf(
        "json" to "JSON", "python" to "Python", "sh" to "Shell", "xml" to "XML", "html" to "HTML",
        "kotlin" to "Kotlin", "java" to "Java", "javascript" to "JavaScript", "sql" to "SQL", "css" to "CSS",
    )

    /** At most this much of a text is read. */
    const val SCAN_CHARS = 4_000

    private const val SCAN_LINES = 120

    /** [text]'s shape, or null for prose and anything too short to tell. */
    fun of(text: String): Shape? {
        val trimmed = text.trim()
        if (trimmed.length < 4) return null
        if (looksLikeJson(trimmed)) return Code("json")
        markupLanguage(trimmed)?.let { return Code(it) }

        val head = if (trimmed.length > SCAN_CHARS) trimmed.substring(0, SCAN_CHARS) else trimmed
        val lines = head.lineSequence().take(SCAN_LINES).filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null

        val codeLines = lines.count(::looksLikeCodeLine)
        val codeShare = codeLines.toFloat() / lines.size
        if (markdownFeatures(head, lines) >= 2 && codeShare < 0.5f) return Markdown

        language(lines, codeLines)?.let { return Code(it) }
        // Code of no language the scores name: enough lines that only code
        // writes, and most of the clip made of them.
        // "Price = 50" lines are assignments too, so one line has to carry a
        // mark only code uses.
        if (lines.size >= 3 && codeLines >= 2 && codeShare >= 0.6f && lines.any(::hasCodeMark)) return Code(null)
        return null
    }

    // ---- JSON and markup: decided by the whole text's shape -------------------

    private val JSON_KEY = Regex("""^[{\[]\s*(?:\{\s*)?"[^"\n]{1,200}"\s*:""")
    private val JSON_ARRAY = Regex("""^\[\s*(?:[\[{"]|-?\d|true\b|false\b|null\b)""")

    private fun looksLikeJson(t: String): Boolean {
        val first = t[0]
        val last = t[t.length - 1]
        if (!(first == '{' && last == '}' || first == '[' && last == ']')) return false
        if (first == '{') return t.length == 2 || JSON_KEY.containsMatchIn(t.take(400))
        // "[citation needed]" is not an array: it needs a value after the bracket,
        // and a comma or a lone value inside.
        return JSON_ARRAY.containsMatchIn(t.take(400)) && (',' in t.take(SCAN_CHARS) || t.length < 40)
    }

    private val HTML_TAG = Regex("""(?i)^<(?:!doctype html|html|head|body|div|span|p|a|table|ul|ol|li|section|article|script|style|template)\b""")

    private fun markupLanguage(t: String): String? {
        if (t[0] != '<' || t[t.length - 1] != '>') return null
        if (t.startsWith("<?xml")) return "xml"
        val head = t.take(SCAN_CHARS)
        // A closing tag or a self-closing one: "<3 you>" is neither.
        if (!head.contains("</") && !head.contains("/>")) return null
        if (!Regex("""^<[A-Za-z!][\w:.-]*""").containsMatchIn(head)) return null
        return if (HTML_TAG.containsMatchIn(head)) "html" else "xml"
    }

    // ---- Markdown -------------------------------------------------------------

    private val MD_HEADING = Regex("""^#{1,6} \S""")
    private val MD_LIST = Regex("""^\s{0,6}(?:[-*+]|\d{1,3}[.)]) \S""")
    private val MD_TASK = Regex("""^\s{0,6}[-*+] \[[ xX]\] """)
    private val MD_QUOTE = Regex("""^> ?\S""")
    private val MD_TABLE_RULE = Regex("""^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$""")
    private val MD_BOLD = Regex("""(\*\*|__)(?=\S).+?(?<=\S)\1""")
    private val MD_LINK = Regex("""\[[^\]\n]+\]\((?:https?://|/|#|mailto:)[^)\s]*\)""")
    private val MD_CODE_SPAN = Regex("""`[^`\n]+`""")

    /** How many different kinds of Markdown [head] uses: one is chance, two are intent. */
    private fun markdownFeatures(head: String, lines: List<String>): Int {
        var n = 0
        if (lines.any { MD_HEADING.containsMatchIn(it) }) n++
        if (lines.count { MD_LIST.containsMatchIn(it) } >= 2) n++
        if (lines.any { MD_TASK.containsMatchIn(it) }) n++
        if (lines.any { MD_QUOTE.containsMatchIn(it) }) n++
        if (lines.any { MD_TABLE_RULE.matches(it) }) n += 2
        if (lines.any { it.trimStart().startsWith("```") }) n += 2
        if (MD_BOLD.containsMatchIn(head)) n++
        if (MD_LINK.containsMatchIn(head)) n++
        if (MD_CODE_SPAN.containsMatchIn(head)) n++
        return n
    }

    // ---- Languages by line ----------------------------------------------------

    private val CODE_ENDINGS = charArrayOf(';', '{', '}')
    private val CODE_MARKS = listOf("=>", "->", "==", "!=", "&&", "||", "::", "();", "//", "/*", "+=", "-=", "?.", ":=")
    private val ASSIGNMENT = Regex("""^\s*[A-Za-z_$][\w$.\[\]"']*\s*=\s*\S""")
    private val CALL_LINE = Regex("""^\s*[A-Za-z_$][\w$.]*\([^)]*\)\s*;?\s*$""")

    private fun hasCodeMark(line: String): Boolean {
        val t = line.trimEnd()
        return t.isNotEmpty() && (t.last() in CODE_ENDINGS || CODE_MARKS.any { it in t })
    }

    /** One line only code writes: a line no sentence ends or starts that way. */
    internal fun looksLikeCodeLine(line: String): Boolean {
        val t = line.trimEnd()
        if (t.isEmpty()) return false
        val s = t.trimStart()
        if (t.last() in CODE_ENDINGS || s[0] == '}' || s[0] == ')' || s[0] == ']') return true
        if (t.endsWith("):") || t.endsWith("),") || t.endsWith(")")) {
            if (CALL_LINE.matches(t) || s.startsWith("def ") || s.startsWith("if ") || s.startsWith("for ")) return true
        }
        if (CODE_MARKS.any { it in t }) return true
        return ASSIGNMENT.containsMatchIn(t) && !t.endsWith('.')
    }

    private class Rule(val language: String, val pattern: Regex, val weight: Int = 1)

    private val RULES = listOf(
        Rule("python", Regex("""^\s*def \w+\(.*\)\s*(?:->.*)?:\s*$"""), 2),
        Rule("python", Regex("""^\s*class \w+(?:\(.*\))?:\s*$"""), 2),
        Rule("python", Regex("""^\s*(?:from [\w.]+ import [\w*, ]+|import [\w.]+(?: as \w+)?(?:, [\w.]+)*)\s*$""")),
        Rule("python", Regex("""^\s*(?:if|elif|else|for|while|with|try|except|finally)\b[^;{]*:\s*$""")),
        Rule("python", Regex("""\bself\.\w|^\s*print\(|\bif __name__ ==|\bis not None\b|^\s*return\b""")),

        Rule("kotlin", Regex("""^\s*(?:(?:private|internal|override|suspend|inline|public)\s+)*fun\s+[\w<>.,\s]+\(""")),
        Rule("kotlin", Regex("""^\s*(?:val|var)\s+\w+\s*(?::\s*[\w<>?,. ]+)?\s*=""")),
        Rule("kotlin", Regex("""^\s*(?:data class|object|companion object|sealed (?:class|interface)|when\s*\(|package [a-z][\w.]*\s*$)""")),
        Rule("kotlin", Regex("""\w!!|\?\.let\b|\bit\.\w|\blistOf\(|\bmutableListOf\(""")),

        Rule("java", Regex("""^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\]]+\s+\w+\s*\(""")),
        Rule("java", Regex("""^\s*(?:public\s+)?(?:final\s+)?class\s+\w+.*\{\s*$|^\s*@Override\b|System\.out\.print|^\s*import java\.""")),
        Rule("java", Regex("""^\s*(?:String|int|long|boolean|double|List<.*>|Map<.*>)\s+\w+\s*=.*;\s*$""")),

        Rule("javascript", Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+[\w{}\[\], ]+\s*=""")),
        Rule("javascript", Regex("""^\s*(?:export\s+(?:default\s+)?)?(?:async\s+)?function\s*\*?\s*\w*\s*\(""")),
        Rule("javascript", Regex("""console\.\w+\(|document\.\w|\brequire\(['"]|^\s*import .+ from ['"]|=>\s*[{(]|\bawait fetch\(""")),

        Rule("sql", Regex("""(?i)^\s*(?:select\s+.+\s+from\s+\w|insert\s+into\s+\w|update\s+\w+\s+set\s|delete\s+from\s+\w|create\s+(?:table|index|view)\s|alter\s+table\s|drop\s+table\s)"""), 2),
        Rule("sql", Regex("""(?i)^\s*(?:where|from|join|left join|inner join|group by|order by|having|limit|values)\b""")),

        Rule("css", Regex("""^\s*[\w.#:\-\[\]="'*>+~ ,()]+\{\s*$""")),
        Rule("css", Regex("""^\s*-{0,2}[a-z][a-z-]*\s*:\s*[^;{}]+;\s*$""")),
        Rule("css", Regex("""^\s*@(?:media|import|font-face|keyframes)\b""")),

        Rule("sh", Regex("""^#!/(?:usr/)?bin/(?:env )?(?:ba|z)?sh"""), 3),
        Rule("sh", Regex("""^\s*\$ \S""")),
    )

    /**
     * Commands a shell line starts with, unambiguous enough that one line is
     * a verdict when the rest of it looks like arguments.
     */
    private val COMMANDS = (
        "adb fastboot sudo su apt apt-get pkg pip pip3 pipx npm npx yarn pnpm git curl wget chmod chown mkdir rm " +
            "cp mv ls cd cat grep sed awk tar unzip ssh scp rsync docker brew python python3 node cargo go " +
            "./gradlew gradle termux-setup-storage termux-change-repo proot-distro winget choco systemctl " +
            "journalctl export echo am pm cmd settings setprop getprop logcat dumpsys flatpak snap dnf pacman yay"
        ).split(' ').toHashSet()

    private val SHELL_ARGS = Regex("""\s(?:-{1,2}[\w-]+|[\w.~-]*/[\w./~-]*|\||&&|>|\$\w|[\w.-]+=[\w./-]+)""")
    private val SHELL_SUBCOMMANDS = setOf(
        "install", "uninstall", "clone", "commit", "push", "pull", "checkout", "shell", "devices", "reboot",
        "update", "upgrade", "run", "add", "remove", "list", "status", "log", "flash", "sideload", "init", "build",
    )

    private fun shellLine(line: String): Boolean {
        val t = line.trim().removePrefix("$ ").removePrefix("# ")
        val first = t.substringBefore(' ')
        if (first !in COMMANDS && !first.startsWith("./") && !first.startsWith("termux-")) return false
        if (t.endsWith('.') || t.endsWith('?') || t.endsWith('!')) return false
        val second = t.substringAfter(' ', "").substringBefore(' ')
        return SHELL_ARGS.containsMatchIn(t) || second in SHELL_SUBCOMMANDS
    }

    private fun language(lines: List<String>, codeLines: Int): String? {
        val scores = HashMap<String, Int>()
        for (line in lines) {
            for (rule in RULES) if (rule.pattern.containsMatchIn(line)) scores.merge(rule.language, rule.weight, Int::plus)
            if (shellLine(line)) scores.merge("sh", 2, Int::plus)
        }
        // Java's lines are Kotlin's too in places; Kotlin wins only on its own words.
        val best = scores.maxByOrNull { it.value } ?: return null
        val need = if (lines.size == 1) 1 else 2
        if (best.value < need) return null
        // Prose has lines ending in a colon and starting with "for" or "from";
        // code has lines nothing else has, so a verdict on a few lines needs one.
        if (lines.size > 1 && codeLines == 0 && best.value < 4) return null
        // One line scoring once must at least be code-shaped, or a command.
        if (lines.size == 1 && best.value < 2 && !looksLikeCodeLine(lines[0])) return null
        return best.key
    }
}
