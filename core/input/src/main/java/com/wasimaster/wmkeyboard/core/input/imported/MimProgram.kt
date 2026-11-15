package com.wasimaster.wmkeyboard.core.input.imported

/**
 * An m17n input method (`.mim`), read and compiled for [MimEngine].
 *
 * A `.mim` is a program, not a table: named maps of key sequences to actions,
 * and states that say which maps are live and what happens around them. The
 * compile step is m17n's own: each state's maps are merged into one tree of
 * keys, a sequence that ends on a node gives that node its actions, and the
 * first definition of a sequence wins over a later one.
 *
 * Built from m17n-db's documentation of the format and from reading what its
 * shipped files do, not from m17n-lib's code (LGPL; this app is MIT).
 */
class MimProgram private constructor(
    /** The language tag the file names, `hi` for `(input-method hi itrans)`; `t` for none. */
    val language: String,
    val name: String,
    val title: String?,
    val description: String?,
    internal val variables: Map<String, MimValue>,
    internal val macros: Map<String, List<MimValue>>,
    internal val states: List<State>,
    /** The printable characters some map reads, which decides the keys on the grid. */
    val keys: Set<Char>,
    /** Parts this app cannot run, by the word the file uses for them; see [MimFeature]. */
    val unsupported: Set<MimFeature>,
    /** `(include …)` targets that were not among the files given, as `lang name`. */
    val missingIncludes: List<String>,
    /** How many key sequences the states can reach, for the import screen to count. */
    val entries: Int,
    /**
     * Whether the method offers lists to choose from (a Pinyin or Kana method
     * written with `candidates`, `select` and `show`), which the keyboard puts
     * on the strip.
     */
    val listsCandidates: Boolean = false,
) {

    /** One node of a state's key tree. */
    internal class Node {
        val children = HashMap<String, Node>()

        /** What reaching this node does; on a state's root, the `t` branch (the state hook). */
        var mapActions: List<MimValue>? = null

        /** What follows once the node's sequence is complete; on a root, the `nil` branch. */
        var branchActions: List<MimValue>? = null
    }

    internal class State(val name: String, val title: String?, val root: Node)

    internal val initial: State get() = states.first()

    internal fun state(name: String): State? = states.firstOrNull { it.name == name }

    /** Whether the method has anything to type with at all. */
    val isUsable: Boolean get() = states.isNotEmpty() && keys.isNotEmpty()

    companion object {

        /**
         * Compiles [text], resolving `(include …)` against [library], the other
         * `.mim` files given at the same time, by their `(input-method …)` line.
         *
         * Throws [MimReader.SyntaxError] for a file that does not parse, and
         * [IllegalArgumentException] for one that is not an input method.
         */
        fun compile(text: String, library: List<String> = emptyList()): MimProgram {
            val forms = MimReader.read(text)
            val header = forms.firstOrNull { it.items?.firstOrNull()?.symbol == INPUT_METHOD }?.items
            require(header != null) { "No (input-method …) declaration" }
            val language = header.getOrNull(1)?.symbol ?: "t"
            val name = header.getOrNull(2)?.symbol ?: "nil"
            val libraryForms = LazyLibrary(library)
            return Builder(language, name, libraryForms).build(forms)
        }

        private const val INPUT_METHOD = "input-method"
    }

    /** The other files, parsed only if an include asks for one. */
    private class LazyLibrary(private val texts: List<String>) {
        private val parsed: List<Pair<String, List<MimValue>>> by lazy {
            texts.mapNotNull { text ->
                val forms = runCatching { MimReader.read(text) }.getOrNull() ?: return@mapNotNull null
                val header = forms.firstOrNull { it.items?.firstOrNull()?.symbol == "input-method" }?.items
                    ?: return@mapNotNull null
                val key = listOfNotNull(header.getOrNull(1)?.symbol, header.getOrNull(2)?.symbol, header.getOrNull(3)?.symbol)
                    .joinToString(" ")
                key to forms
            }
        }

        /** The forms of the method `lang name [extra]`, or null when it was not given. */
        fun find(target: List<MimValue>): List<MimValue>? {
            val key = target.mapNotNull { it.symbol }.joinToString(" ")
            return parsed.firstOrNull { it.first == key }?.second
        }
    }

    private class Builder(
        private val language: String,
        private val name: String,
        private val library: LazyLibrary,
    ) {
        private var title: String? = null
        private var description: String? = null
        private val variables = LinkedHashMap<String, MimValue>()
        private val macros = LinkedHashMap<String, List<MimValue>>()
        private val maps = LinkedHashMap<String, MutableList<MimValue>>()
        private val stateForms = ArrayList<List<MimValue>>()
        private val unsupported = LinkedHashSet<MimFeature>()
        private var listsCandidates = false
        private val missing = ArrayList<String>()
        private var includeDepth = 0
        private var entries = 0

        fun build(forms: List<MimValue>): MimProgram {
            for (form in forms) section(form, only = null, onlyName = null)
            val keys = HashSet<Char>()
            val states = stateForms.map { compileState(it, keys) }
            // A file with maps and no states has one implicit initial state that
            // uses every map, which is how m17n reads the short ones.
            val all = states.ifEmpty {
                listOf(compileState(listOf(MimValue.Sym("init")) + maps.keys.map { MimValue.Lst(listOf(MimValue.Sym(it))) }, keys))
            }
            noteUnsupported(forms)
            return MimProgram(
                language = language,
                name = name,
                title = title,
                description = description,
                variables = variables,
                macros = macros,
                states = all,
                keys = keys,
                unsupported = unsupported,
                missingIncludes = missing,
                entries = entries,
                listsCandidates = listsCandidates,
            )
        }

        /** One top-level form, or the forms an include pulls in. */
        private fun section(form: MimValue, only: String?, onlyName: String?) {
            val items = form.items ?: return
            val head = items.firstOrNull()?.symbol ?: return
            if (only != null && head != only) return
            val body = items.drop(1)
            when (head) {
                "title" -> title = title ?: (body.firstOrNull() as? MimValue.Text)?.value
                "description" -> description = description ?: textOf(body.firstOrNull())
                "variable" -> for (entry in body) {
                    val v = entry.items ?: continue
                    val varName = v.firstOrNull()?.symbol ?: continue
                    if (onlyName != null && varName != onlyName) continue
                    // (NAME DOC VALUE VALID…): the value is the third element.
                    variables.putIfAbsent(varName, v.getOrNull(2) ?: MimValue.Num(0))
                }
                "macro" -> for (entry in body) {
                    if (entry.items?.firstOrNull()?.symbol == INCLUDE) { include(entry.items!!, "macro"); continue }
                    val m = entry.items ?: continue
                    val macroName = m.firstOrNull()?.symbol ?: continue
                    if (onlyName != null && macroName != onlyName) continue
                    macros[macroName] = m.drop(1)
                }
                "map" -> for (entry in body) {
                    if (entry.items?.firstOrNull()?.symbol == INCLUDE) { include(entry.items!!, "map"); continue }
                    val m = entry.items ?: continue
                    val mapName = m.firstOrNull()?.symbol ?: continue
                    if (onlyName != null && mapName != onlyName) continue
                    maps.getOrPut(mapName) { ArrayList() }.addAll(m.drop(1))
                }
                "state" -> for (entry in body) {
                    if (entry.items?.firstOrNull()?.symbol == INCLUDE) { include(entry.items!!, "state"); continue }
                    val st = entry.items ?: continue
                    val stateName = st.firstOrNull()?.symbol ?: continue
                    if (onlyName != null && stateName != onlyName) continue
                    if (stateForms.none { it.first().symbol == stateName }) stateForms += st
                }
                INCLUDE -> include(items, null)
                else -> Unit
            }
        }

        /** `(include (LANG NAME [EXTRA]) [SECTION [NAME]])`. */
        private fun include(items: List<MimValue>, defaultSection: String?) {
            val target = items.getOrNull(1)?.items ?: return
            val section = items.getOrNull(2)?.symbol ?: defaultSection
            val sectionName = items.getOrNull(3)?.symbol
            val forms = library.find(target)
            if (forms == null) {
                val label = target.mapNotNull { it.symbol }.joinToString(" ")
                if (label !in missing) missing += label
                return
            }
            if (includeDepth >= MAX_INCLUDE_DEPTH) return
            includeDepth++
            for (form in forms) {
                if (form.items?.firstOrNull()?.symbol == "input-method") continue
                section(form, section, sectionName)
            }
            includeDepth--
        }

        private fun compileState(items: List<MimValue>, keys: MutableSet<Char>): State {
            val stateName = items.first().symbol.orEmpty()
            var rest = items.drop(1)
            val stateTitle = (rest.firstOrNull() as? MimValue.Text)?.value
            if (stateTitle != null) rest = rest.drop(1)
            val root = Node()
            for (branch in rest) {
                val b = branch.items ?: continue
                val mapName = b.firstOrNull()?.symbol ?: continue
                val actions = b.drop(1).takeIf { it.isNotEmpty() }
                when (mapName) {
                    "nil" -> root.branchActions = actions
                    "t" -> root.mapActions = actions
                    else -> for (entry in maps[mapName].orEmpty()) addEntry(root, entry, actions, keys)
                }
            }
            return State(stateName, stateTitle, root)
        }

        private fun addEntry(root: Node, entry: MimValue, branch: List<MimValue>?, keys: MutableSet<Char>) {
            val parts = entry.items ?: return
            val seq = keySequence(parts.firstOrNull() ?: return) ?: return
            if (seq.isEmpty()) return
            var node = root
            for (key in seq) {
                node = node.children.getOrPut(key) { Node() }
                if (key.length == 1) {
                    val c = key[0]
                    if (c > ' ' && c.code < DEL) keys += c
                }
            }
            // The first definition of a sequence stands.
            if (node.mapActions != null || node.branchActions != null) return
            val actions = parts.drop(1)
            if (actions.isNotEmpty()) node.mapActions = actions
            node.branchActions = branch
            entries++
        }

        /** A map entry's keys: a string of characters, or a list of characters and key names. */
        private fun keySequence(value: MimValue): List<String>? = when (value) {
            is MimValue.Text -> value.value.map { it.toString() }
            is MimValue.Lst -> value.items.map { item ->
                when (item) {
                    is MimValue.Num -> if (item.value in 0 until LATIN1) item.value.toChar().toString() else return null
                    is MimValue.Sym -> item.name
                    else -> return null
                }
            }
            else -> null
        }

        private fun textOf(value: MimValue?): String? = when (value) {
            is MimValue.Text -> value.value
            // (_ "text"): a string marked for translation.
            is MimValue.Lst -> value.items.firstNotNullOfOrNull { (it as? MimValue.Text)?.value }
            else -> null
        }

        private fun noteUnsupported(forms: List<MimValue>) {
            fun walk(v: MimValue, depth: Int) {
                if (depth > MAX_WALK_DEPTH) return
                val items = v.items ?: return
                when (items.firstOrNull()?.symbol) {
                    "module" -> unsupported += MimFeature.EXTERNAL_MODULE
                    "call" -> unsupported += MimFeature.EXTERNAL_MODULE
                    "switch-im", "push-im", "pop-im" -> unsupported += MimFeature.OTHER_METHOD
                    "select", "show", "hide" -> listsCandidates = true
                    "insert" -> if (items.getOrNull(1) is MimValue.Lst) listsCandidates = true
                    else -> Unit
                }
                for (item in items) walk(item, depth + 1)
            }
            /** An action written short as a list of strings (`("か" "カ")`) is a candidate list. */
            fun shortList(action: MimValue) {
                val head = action.items?.firstOrNull() ?: return
                if (head is MimValue.Text || head is MimValue.Lst) listsCandidates = true
            }
            for (form in forms) walk(form, 0)
            for (actions in macros.values) {
                for (a in actions) {
                    walk(a, 0)
                    shortList(a)
                }
            }
            for (entries in maps.values) {
                for (e in entries) {
                    walk(e, 0)
                    // An entry is its key sequence, then its actions.
                    e.items?.drop(1)?.forEach(::shortList)
                }
            }
        }

        private companion object {
            const val INCLUDE = "include"
            const val MAX_INCLUDE_DEPTH = 8
            const val MAX_WALK_DEPTH = 64
            const val DEL = 0x7F
            const val LATIN1 = 0x100
        }
    }
}

/** What an m17n file may use that this app does not run. */
enum class MimFeature {
    /** A compiled plug-in (`module`, `call`), such as the Anthy and ispell bridges. */
    EXTERNAL_MODULE,

    /** Handing the keys to another input method part way (`switch-im`, `push-im`). */
    OTHER_METHOD,

    /**
     * A list of choices the user moves through (`select`). No longer reported:
     * the strip shows the list. Kept so a stored note still reads.
     */
    CANDIDATE_LIST,
}
