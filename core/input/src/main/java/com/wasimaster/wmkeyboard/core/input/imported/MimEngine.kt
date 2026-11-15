package com.wasimaster.wmkeyboard.core.input.imported

/**
 * Runs a [MimProgram] over a run of keys and says what text they wrote.
 *
 * The keyboard's composers are pure functions of the keys typed since the word
 * began, so this replays the whole run on every keystroke rather than keeping
 * a live input context. A word is a few keys; the replay costs microseconds,
 * and it means backspace is simply the same run one key shorter.
 *
 * The behaviour follows m17n's documented input-context model, which the
 * shipped `.mim` files are written against:
 *
 * - Within a state, keys walk that state's key tree. Each step first puts the
 *   text back the way it was when the state was entered, then runs the actions
 *   of the node reached, so `k` then `kh` shows ख्, not क्ख्.
 * - A step onto a node with no actions shows the keys themselves.
 * - When a sequence is complete (a leaf, or the actions moved to another
 *   state) the branch's own actions run, and the walk returns to the state's
 *   root.
 * - A key the current node cannot take runs the node's branch actions (at a
 *   root, the `nil` branch) and is tried again from the root. At the initial
 *   state's root it is not the method's key at all and is typed as itself.
 * - Entering the initial state commits the text. A `t` branch runs when the
 *   next key arrives in a newly entered state.
 *
 * Text before the composing part ([Result.committed]) is what m17n calls the
 * surrounding text, so `@-1` and `(delete @-1)` reach back into it the way
 * they reach into the app's text there. Text from before the word is out of
 * reach: a composer is not given it.
 */
class MimEngine(private val program: MimProgram) {

    /**
     * What a run of keys wrote: the settled part, and the part still
     * composing; [choices] when the run ended inside a candidate list.
     */
    data class Result(val committed: String, val preedit: String, val choices: Choices? = null) {
        val text: String get() = committed + preedit
    }

    /**
     * The candidate list the run ended on: the whole text is [before], one
     * of [list], then [after]; [index] is the one the rules chose.
     */
    data class Choices(val before: String, val list: List<String>, val index: Int, val after: String) {
        /** The whole text with choice [i] in place. */
        fun textWith(i: Int): String = before + list[i] + after
    }

    /** The text [keys] type, each key a character the method reads. */
    fun run(keys: CharSequence): Result {
        val context = Context()
        var i = 0
        while (i < keys.length) {
            val cp = Character.codePointAt(keys, i)
            context.filter(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        val committed = codePoints(context.committed)
        return Result(committed, codePoints(context.preedit), context.openChoices(committed))
    }

    /**
     * The text [keys] leave once the word ends: the run followed by a space,
     * which is what ends a word on the keyboard m17n's files are written for.
     * Rules that tidy a word up when it closes (ITRANS dropping a final
     * virama before a space) run here, and the space itself is not returned.
     */
    fun finish(keys: CharSequence): String {
        val text = run("$keys ").text
        return if (text.endsWith(' ')) text.dropLast(1) else text
    }

    private fun codePoints(list: List<Int>): String {
        val out = StringBuilder(list.size)
        for (cp in list) if (cp >= 0) out.appendCodePoint(cp)
        return out.toString()
    }

    /** One input context, from the start of a word. */
    private inner class Context {
        val keys = ArrayList<String>()
        var keyHead = 0
        var stateKeyHead = 0
        var commitKeyHead = 0

        var state: MimProgram.State = program.initial
        var prevState: MimProgram.State? = null
        var map: MimProgram.Node = state.root
        var stateHook: List<MimValue>? = null

        val committed = ArrayList<Int>()
        var committedBase = 0
        var preedit = ArrayList<Int>()
        var preeditSaved = ArrayList<Int>()
        var cursor = 0
        var statePos = 0
        val markers = HashMap<String, Int>()

        var vars = HashMap(program.variables)
        var varsSaved = HashMap(vars)

        /** The candidates the last insert came from, for `select`; see [select]. */
        var choices: List<String>? = null
        var choiceIndex = 0
        var choiceStart = 0
        var choiceEnd = 0

        private var depth = 0

        init {
            // Starting up is a shift to the initial state from no state at all,
            // which arms that state's hook and commits nothing.
            stateHook = state.root.mapActions
        }

        /** One key, as m17n's `filter` takes it. */
        fun filter(key: String) {
            committedBase = committed.size
            keys += key
            var result: Int
            var count = 0
            do {
                result = handleKey()
                if (++count > MAX_STEPS) {
                    // A file whose rules loop. m17n gives up the same way.
                    reset()
                    result = UNHANDLED
                    break
                }
            } while (result == 0 && keyHead < keys.size)

            if (map === program.initial.root) commit()
            if (commitKeyHead > 0) {
                val n = commitKeyHead.coerceAtMost(keys.size)
                repeat(n) { keys.removeAt(0) }
                keyHead = (keyHead - n).coerceAtLeast(0)
                stateKeyHead = (stateKeyHead - n).coerceAtLeast(0)
                commitKeyHead = 0
            }
            if (result != 0) {
                val n = keyHead.coerceAtMost(keys.size)
                repeat(n) { keys.removeAt(0) }
                keyHead = 0
                stateKeyHead = 0
                commitKeyHead = 0
                if (result < 0) {
                    keys.clear()
                    // Not this method's key: it is typed as itself, after
                    // whatever the method had composed.
                    commit()
                    key.codePoints().forEach { committed += it }
                }
            }
        }

        private fun reset() {
            keys.clear()
            keyHead = 0
            stateKeyHead = 0
            commitKeyHead = 0
            preedit = ArrayList()
            preeditSaved = ArrayList()
            cursor = 0
            statePos = 0
            markers.clear()
            state = program.initial
            map = state.root
            prevState = null
            stateHook = null
            choices = null
        }

        /** The candidate list still open in the preedit, after [committed]; null when there is none. */
        fun openChoices(committed: String): Choices? {
            val list = choices ?: return null
            if (list.size < 2 || choiceStart < 0 || choiceEnd > preedit.size || choiceStart > choiceEnd) return null
            val index = choiceIndex.coerceIn(0, list.size - 1)
            // Rules that edited the text since may have moved it; then the list no longer describes it.
            if (codePoints(preedit.subList(choiceStart, choiceEnd)) != list[index]) return null
            val before = committed + codePoints(preedit.subList(0, choiceStart))
            val after = codePoints(preedit.subList(choiceEnd, preedit.size))
            return Choices(before, list, index, after)
        }

        private fun handleKey(): Int {
            val current = map
            stateHook?.let { hook ->
                val r = actions(hook)
                preeditSaved = ArrayList(preedit)
                statePos = cursor
                stateHook = null
                if (r != 0) return r
            }
            if (keyHead >= keys.size) return 0
            val key = keys[keyHead]
            val next = current.children[key]
            if (next != null) {
                preedit = ArrayList(preeditSaved)
                cursor = statePos.coerceIn(0, preedit.size)
                keyHead++
                map = next
                val mapActions = next.mapActions
                if (mapActions != null) {
                    val r = actions(mapActions)
                    if (r != 0) return r
                } else if (next.children.isNotEmpty()) {
                    // Part way along a longer sequence: show the keys so far.
                    for (i in stateKeyHead until keyHead.coerceAtMost(keys.size)) {
                        val k = keys[i]
                        if (k.length == 1) preedit.add(cursor++, k[0].code)
                    }
                }
                if (next.children.isEmpty() || next !== map) {
                    next.branchActions?.let { branch ->
                        val r = actions(branch)
                        if (r != 0) return r
                    }
                    if (map !== state.root) shift(state.name)
                }
            } else {
                current.branchActions?.let { branch ->
                    val r = actions(branch)
                    if (r != 0) return r
                }
                if (current === map) {
                    if (current === program.initial.root && keyHead < keys.size) {
                        stateHook = current.mapActions
                        return UNHANDLED
                    }
                    if (current !== state.root) {
                        shift(state.name)
                    } else if (current.branchActions == null) {
                        shift(null)
                    } else {
                        // A root whose `nil` branch did not move anywhere would
                        // take the same key forever; m17n's step limit is the
                        // only thing that stops it there. Going home is what
                        // that limit ends up doing, minus the lost word.
                        shift(null)
                    }
                }
            }
            return 0
        }

        /** m17n's `shift`: null is the initial state, `t` the previous one. */
        private fun shift(name: String?) {
            val orig = state
            val target = when (name) {
                null, "nil" -> program.initial
                "t" -> prevState ?: return
                else -> program.state(name) ?: program.initial
            }
            state = target
            map = target.root
            stateKeyHead = keyHead
            if (target === program.initial) commit()
            preeditSaved = ArrayList(preedit)
            statePos = cursor
            if (target !== orig || name == null || name == "nil") {
                if (target === program.initial) {
                    prevState = null
                    varsSaved = HashMap(vars)
                } else {
                    prevState = orig
                }
                stateHook = target.root.mapActions
            }
        }

        private fun commit() {
            if (preedit.isNotEmpty()) {
                committed.addAll(preedit)
                preedit = ArrayList()
                preeditSaved = ArrayList()
                for (k in markers.keys) markers[k] = 0
                cursor = 0
                statePos = 0
                commitKeyHead = keyHead
            }
            choices = null
        }

        // ---- actions ----

        private fun actions(list: List<MimValue>): Int {
            if (++depth > MAX_DEPTH) {
                depth--
                return 0
            }
            try {
                for (raw in list) {
                    val r = action(raw)
                    if (r == STOP) return 0
                    if (r != 0) return r
                }
                return 0
            } finally {
                depth--
            }
        }

        private fun action(raw: MimValue): Int {
            val value = if (raw is MimValue.Sym) vars[raw.name] ?: return 0 else raw
            when (value) {
                is MimValue.Text -> insert(value.value)
                is MimValue.Num -> insert(value.value)
                is MimValue.Sym -> return named(value.name, emptyList())
                is MimValue.Lst -> {
                    val head = value.items.firstOrNull() ?: return 0
                    return when (head) {
                        is MimValue.Sym -> {
                            val args = value.items.drop(1)
                            if (head.name == "insert" && args.firstOrNull() is MimValue.Lst) {
                                candidates(args.first().items.orEmpty())
                                0
                            } else {
                                named(head.name, args)
                            }
                        }
                        // ("a" "b") or (("ab" "cd")): a candidate list, written short.
                        is MimValue.Text, is MimValue.Lst -> {
                            candidates(value.items)
                            0
                        }
                        else -> 0
                    }
                }
            }
            return 0
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        private fun named(name: String, args: List<MimValue>): Int {
            val arg = args.firstOrNull()
            when (name) {
                "insert" -> when (val v = if (arg is MimValue.Sym) vars[arg.name] else arg) {
                    is MimValue.Text -> insert(v.value)
                    is MimValue.Num -> insert(v.value)
                    else -> Unit
                }
                "candidates" -> candidates(arg?.items.orEmpty())
                "select" -> select(arg)
                "show", "hide" -> Unit
                "delete" -> delete(arg)
                "move" -> {
                    val len = preedit.size
                    val pos = if (arg is MimValue.Sym) newIndex(arg.name, cursor, len) else (arg as? MimValue.Num)?.value ?: cursor
                    cursor = pos.coerceIn(0, len)
                }
                "mark" -> {
                    val sym = arg?.symbol ?: return 0
                    if (markerCode(sym, surrounding = false) < 0) markers[sym] = cursor
                }
                "pushback" -> pushback(arg)
                "pop" -> if (keyHead < keys.size) keys.removeAt(keyHead)
                "shift" -> shift(arg?.symbol)
                "undo" -> return undo(arg)
                "set", "add", "sub", "mul", "div" -> {
                    val sym = arg?.symbol ?: return 0
                    val a = (vars[sym] as? MimValue.Num)?.value ?: 0
                    val b = expression(args.getOrNull(1))
                    val v = when (name) {
                        "set" -> b
                        "add" -> a + b
                        "sub" -> a - b
                        "mul" -> a * b
                        else -> if (b == 0) a else a / b
                    }
                    vars[sym] = MimValue.Num(v)
                }
                "=", "<", ">", "<=", ">=" -> {
                    val a = expression(args.getOrNull(0))
                    val b = expression(args.getOrNull(1))
                    val ok = when (name) {
                        "=" -> a == b
                        "<" -> a < b
                        ">" -> a > b
                        "<=" -> a <= b
                        else -> a >= b
                    }
                    val chosen = if (ok) args.getOrNull(2) else args.getOrNull(3)
                    chosen?.items?.let { return actions(it) }
                }
                "cond" -> for (clause in args) {
                    val items = clause.items ?: continue
                    if (items.isEmpty()) continue
                    if (expression(items[0]) != 0) return actions(items.drop(1))
                }
                "commit" -> commit()
                "unhandle" -> {
                    commit()
                    return UNHANDLED
                }
                // Plug-ins and other input methods are not available here; the
                // import screen says so before the file is taken.
                "call", "switch-im", "push-im", "pop-im" -> Unit
                else -> program.macros[name]?.let { return actions(it) }
            }
            return 0
        }

        private fun insert(text: String) {
            val cps = text.codePoints().toArray()
            insertAt(cursor, cps)
        }

        private fun insert(cp: Int) {
            if (cp < 0) return
            insertAt(cursor, intArrayOf(cp))
        }

        private fun insertAt(pos: Int, cps: IntArray) {
            if (cps.isEmpty()) return
            val at = pos.coerceIn(0, preedit.size)
            preedit.addAll(at, cps.toList())
            adjust(at, at, cps.size)
        }

        private fun deleteRange(from: Int, to: Int) {
            if (from >= to) return
            for (i in from until to) preedit.removeAt(from)
            adjust(from, to, 0)
        }

        /** m17n's marker bookkeeping for a change of [from, to) to [ins] characters. */
        private fun adjust(from: Int, to: Int, ins: Int) {
            if (from == to) {
                for ((k, v) in markers) if (v > from) markers[k] = v + ins
                if (cursor >= from) cursor += ins
            } else {
                for ((k, v) in markers) {
                    if (v >= to) markers[k] = v + ins - (to - from) else if (v > from) markers[k] = from
                }
                if (cursor >= to) cursor += ins - (to - from) else if (cursor > from) cursor = from
            }
        }

        /**
         * A candidate list: the first choice goes in. A list is either strings
         * whose characters are the choices, or lists of strings that are.
         */
        private fun candidates(groups: List<MimValue>) {
            val flat = ArrayList<String>()
            for (g in groups) {
                when (g) {
                    is MimValue.Text -> g.value.codePoints().forEach { flat += String(Character.toChars(it)) }
                    is MimValue.Lst -> g.items.forEach { (it as? MimValue.Text)?.let { t -> flat += t.value } }
                    else -> Unit
                }
            }
            val first = flat.firstOrNull() ?: return
            val start = cursor
            insert(first)
            choices = flat
            choiceIndex = 0
            choiceStart = start
            choiceEnd = cursor
        }

        /** Moves the last candidate list to another choice, by number or by step. */
        private fun select(arg: MimValue?) {
            val list = choices ?: return
            if (cursor != choiceEnd) return
            val index = when (arg) {
                is MimValue.Num -> arg.value
                is MimValue.Sym -> when (arg.name) {
                    "@-" -> choiceIndex - 1
                    "@+" -> choiceIndex + 1
                    "@<" -> 0
                    "@>" -> list.size - 1
                    else -> (vars[arg.name] as? MimValue.Num)?.value ?: return
                }
                else -> return
            }
            val i = ((index % list.size) + list.size) % list.size
            deleteRange(choiceStart, choiceEnd)
            cursor = choiceStart
            insert(list[i])
            choiceIndex = i
            choiceEnd = cursor
            choices = list
        }

        private fun delete(arg: MimValue?) {
            val len = preedit.size
            val sym = arg?.symbol
            val surrounding = sym?.let(::surroundingOffset)
            var to: Int
            if (surrounding != null) {
                to = cursor + surrounding
                if (to < 0) {
                    // Reaches into what this word already committed.
                    val n = (-to).coerceAtMost(committed.size)
                    repeat(n) { committed.removeAt(committed.size - 1) }
                    if (committed.size < committedBase) committedBase = committed.size
                    to = 0
                } else if (to > len) {
                    to = len
                }
            } else {
                to = if (sym != null) newIndex(sym, cursor, len) else (arg as? MimValue.Num)?.value ?: cursor
                to = to.coerceIn(0, len)
            }
            if (to < cursor) deleteRange(to, cursor) else if (to > cursor) deleteRange(cursor, to)
        }

        private fun pushback(arg: MimValue?) {
            when (arg) {
                is MimValue.Num, is MimValue.Sym -> {
                    val n = when (arg) {
                        is MimValue.Num -> arg.value
                        else -> (vars[(arg as MimValue.Sym).name] as? MimValue.Num)?.value ?: 0
                    }
                    keyHead = when {
                        n > 0 -> keyHead - n
                        n == 0 -> 0
                        else -> -n
                    }.coerceIn(0, keys.size)
                }
                is MimValue.Text -> replaceKeys(arg.value.map { it.toString() })
                is MimValue.Lst -> replaceKeys(
                    arg.items.mapNotNull {
                        when (it) {
                            is MimValue.Sym -> it.name
                            is MimValue.Num -> it.value.toChar().toString()
                            else -> null
                        }
                    },
                )
                null -> Unit
            }
        }

        /** The key just taken, replaced by [replacement], to be read next. */
        private fun replaceKeys(replacement: List<String>) {
            if (keyHead == 0) return
            keyHead--
            for ((i, k) in replacement.withIndex()) {
                if (keyHead + i < keys.size) keys[keyHead + i] = k else keys += k
            }
        }

        private fun undo(arg: MimValue?): Int {
            val target = if (arg == null) keys.size - 2 else integer(arg, surrounding = false)
            preedit = ArrayList()
            preeditSaved = ArrayList()
            while (committed.size > committedBase) committed.removeAt(committed.size - 1)
            vars = HashMap(varsSaved)
            cursor = 0
            statePos = 0
            keyHead = 0
            stateKeyHead = 0
            commitKeyHead = 0
            shift(null)
            if (target < 0) {
                if (arg == null) {
                    keys.clear()
                    return UNHANDLED
                }
                val keep = (keys.size + target).coerceAtLeast(0)
                while (keys.size > keep) keys.removeAt(keys.size - 1)
            } else {
                while (keys.size > target) keys.removeAt(keys.size - 1)
            }
            return STOP
        }

        // ---- values ----

        private fun expression(value: MimValue?): Int = when (value) {
            null -> 0
            is MimValue.Num -> value.value
            is MimValue.Sym -> integer(value, surrounding = true)
            is MimValue.Text -> 0
            is MimValue.Lst -> {
                val items = value.items
                val op = items.firstOrNull()?.symbol
                if (op == null) {
                    0
                } else {
                    val rest = items.drop(1)
                    var v = expression(rest.firstOrNull())
                    val others = rest.drop(1)
                    when (op) {
                        "+" -> for (o in others) v += expression(o)
                        "-" -> for (o in others) v -= expression(o)
                        "*" -> for (o in others) v *= expression(o)
                        "/" -> for (o in others) expression(o).let { d -> if (d != 0) v /= d }
                        "&" -> for (o in others) v = v and expression(o)
                        "|" -> for (o in others) v = v or expression(o)
                        "!" -> v = if (v == 0) 1 else 0
                        "<" -> v = if (v < expression(others.firstOrNull())) 1 else 0
                        "=" -> v = if (v == expression(others.firstOrNull())) 1 else 0
                        ">" -> v = if (v > expression(others.firstOrNull())) 1 else 0
                        "<=" -> v = if (v <= expression(others.firstOrNull())) 1 else 0
                        ">=" -> v = if (v >= expression(others.firstOrNull())) 1 else 0
                        else -> Unit
                    }
                    v
                }
            }
        }

        /** A symbol's value: a variable, a marker's character, or a character near the cursor. */
        private fun integer(value: MimValue, surrounding: Boolean): Int {
            if (value is MimValue.Num) return value.value
            val name = value.symbol ?: return 0
            val code = markerCode(name, surrounding)
            if (code < 0) return (vars[name] as? MimValue.Num)?.value ?: 0
            val len = preedit.size
            val pos: Int = when (code) {
                '@'.code -> return keyHead
                '-'.code, '+'.code -> if (name.length > 2) {
                    val offset = name.substring(1).toIntOrNull() ?: return -1
                    if (offset == 0 && code == '-'.code) return -1
                    val p = cursor + offset
                    when {
                        p < 0 -> return committed.getOrElse(committed.size + p) { -1 }
                        p >= len -> return -1
                        else -> p
                    }
                } else {
                    cursor + if (code == '+'.code) 1 else -1
                }
                in '0'.code..'9'.code -> code - '0'.code
                '='.code -> cursor
                '['.code -> cursor - 1
                ']'.code -> cursor + 1
                '<'.code -> 0
                '>'.code -> len - 1
                else -> return -1
            }
            return if (pos in 0 until len) preedit[pos] else -1
        }

        /** Where a marker points, for `delete` and `move`. */
        private fun newIndex(name: String, current: Int, limit: Int): Int {
            val code = markerCode(name, surrounding = false)
            if (code < 0) return markers[name] ?: 0
            return when (code) {
                '<'.code -> 0
                '>'.code -> limit
                '-'.code -> current - 1
                '+'.code -> current + 1
                '='.code, '['.code, ']'.code -> current
                else -> (code - '0'.code).coerceAtMost(limit)
            }
        }
    }

    private companion object {
        const val UNHANDLED = -1

        /** An `undo` ends its action list without failing it. */
        const val STOP = -2

        const val MAX_STEPS = 100
        const val MAX_DEPTH = 64

        /** m17n's marker names: `@0`…`@9`, `@<` `@>` `@=` `@[` `@]` `@@`, `@-`/`@+`, and with digits the surrounding ones. */
        fun markerCode(name: String, surrounding: Boolean): Int {
            if (name.length < 2 || name[0] != '@') return -1
            val c = name[1]
            if (name.length == 2 && (c in '0'..'9' || c in "<>=[]@")) return c.code
            if (c != '+' && c != '-') return -1
            return if (name.length == 2 || surrounding) c.code else -1
        }

        /** `@-N` (N ≥ 1) and `@+N` (N ≥ 0) as an offset from the cursor; null for any other name. */
        fun surroundingOffset(name: String): Int? {
            if (name.length < 3 || name[0] != '@') return null
            val sign = name[1]
            val digit = name[2]
            val ok = when (sign) {
                '-' -> digit in '1'..'9'
                '+' -> digit in '0'..'9'
                else -> false
            }
            if (!ok) return null
            val n = name.substring(2).toIntOrNull() ?: return null
            return if (sign == '-') -n else n
        }
    }
}
