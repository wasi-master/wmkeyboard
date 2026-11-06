package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyMethods.swift: everything reachable as `value.name(...)`, meaning
 * the str, list, tuple, dict and set methods, the `re` match object, and module
 * calls like `math.sqrt(2)`. String methods count in grapheme clusters, as
 * PyMini's Swift `Character`s do.
 */

internal fun PyInterpreter.callMethod(obj: PyValue, name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    tick()
    return when (obj) {
        is PyValue.Str -> stringMethod(obj.value, name, args, kwargs)
        is PyValue.List -> listMethod(obj.list, name, args, kwargs)
        is PyValue.Tuple -> tupleMethod(obj.items, name, args)
        is PyValue.Set -> setMethod(obj.set, name, args)
        is PyValue.Dict -> dictMethod(obj.dict, name, args)
        is PyValue.Match -> matchMethod(obj.match, name, args)
        is PyValue.Module -> callModule(obj.name, name, args, kwargs)
        is PyValue.Exception -> throw error("'${obj.exception.type}' object has no method '$name'", "AttributeError")
        else -> throw error("'${obj.typeName}' object has no method '$name'", "AttributeError")
    }
}

private fun PyInterpreter.argStr(args: List<PyValue>, i: Int, method: String): String =
    (args.getOrNull(i) as? PyValue.Str)?.value ?: throw error("$method() expects a string argument", "TypeError")

// MARK: - str

private fun PyInterpreter.stringMethod(s: String, name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    when (name) {
        "upper" -> return str(swiftUpper(s))
        "lower" -> return str(swiftLower(s))
        "casefold" -> return str(foldCaseAndDiacritics(s))
        "title" -> return str(capitalized(s))
        "capitalize" -> {
            val cs = chars(s)
            if (cs.isEmpty()) return str("")
            return str(swiftUpper(cs[0]) + swiftLower(cs.drop(1).joinToString("")))
        }
        "swapcase" -> return str(chars(s).joinToString("") { if (charIsUppercase(it)) swiftLower(it) else swiftUpper(it) })
        "strip" -> return str(trim(s, args, left = true, right = true))
        "lstrip" -> return str(trim(s, args, left = true, right = false))
        "rstrip" -> return str(trim(s, args, left = false, right = true))
        "replace" -> {
            val from = argStr(args, 0, "replace")
            val to = argStr(args, 1, "replace")
            if (from.isEmpty()) return str(s)
            val limit = if (args.size > 2) asInt(args[2]) else null
            if (limit == null) return str(s.replace(from, to))
            return str(replaceLimited(s, from, to, limit))
        }
        "split" -> return splitMethod(s, args, fromEnd = false)
        "rsplit" -> return splitMethod(s, args, fromEnd = true)
        "splitlines" -> {
            // Swift splits on the "\n" Character, so a "\r\n" cluster stays whole.
            val out = ArrayList<PyValue>()
            val cur = StringBuilder()
            for (c in chars(s)) {
                if (c == "\n") { out.add(str(cur.toString())); cur.setLength(0) } else cur.append(c)
            }
            out.add(str(cur.toString()))
            return list(out)
        }
        "join" -> {
            val it = args.firstOrNull() ?: throw error("join() needs an iterable", "TypeError")
            val parts = iterate(it).map { v ->
                (v as? PyValue.Str)?.value ?: throw error("join() requires str items, found '${v.typeName}'", "TypeError")
            }
            return str(parts.joinToString(s))
        }
        "startswith" -> return bool(prefixTest(s, args, suffix = false))
        "endswith" -> return bool(prefixTest(s, args, suffix = true))
        "find" -> return int(search(s, args, last = false, method = "find"))
        "rfind" -> return int(search(s, args, last = true, method = "rfind"))
        "index", "rindex" -> {
            val i = search(s, args, last = name == "rindex", method = name)
            if (i < 0) throw error("substring not found", "ValueError")
            return int(i)
        }
        "count" -> {
            val sub = argStr(args, 0, "count")
            if (sub.isEmpty()) return int(chars(s).size + 1)
            return int(s.split(sub).size - 1)
        }
        "partition", "rpartition" -> {
            val sep = argStr(args, 0, name)
            val at = if (sep.isEmpty()) -1 else if (name == "partition") s.indexOf(sep) else s.lastIndexOf(sep)
            if (at < 0) {
                return if (name == "partition") tuple(str(s), str(""), str("")) else tuple(str(""), str(""), str(s))
            }
            return tuple(str(s.substring(0, at)), str(sep), str(s.substring(at + sep.length)))
        }
        "zfill" -> {
            val width = args.firstOrNull()?.let { asInt(it) } ?: 0L
            val cs = chars(s)
            if (cs.size >= width) return str(s)
            val pad = "0".repeat((width - cs.size).toInt())
            val f = cs.firstOrNull()
            if (f == "-" || f == "+") return str(f + pad + cs.drop(1).joinToString(""))
            return str(pad + s)
        }
        "ljust" -> return str(pad(s, args, '<'))
        "rjust" -> return str(pad(s, args, '>'))
        "center" -> return str(pad(s, args, '^'))
        "isdigit", "isnumeric" -> { val cs = chars(s); return bool(cs.isNotEmpty() && cs.all { charIsNumber(it) }) }
        "isalpha" -> { val cs = chars(s); return bool(cs.isNotEmpty() && cs.all { charIsLetter(it) }) }
        "isalnum" -> { val cs = chars(s); return bool(cs.isNotEmpty() && cs.all { charIsLetter(it) || charIsNumber(it) }) }
        "isspace" -> { val cs = chars(s); return bool(cs.isNotEmpty() && cs.all { charIsWhitespace(it) }) }
        "isupper" -> return bool(chars(s).any { charIsLetter(it) } && strEq(s, swiftUpper(s)))
        "islower" -> return bool(chars(s).any { charIsLetter(it) } && strEq(s, swiftLower(s)))
        "istitle" -> return bool(s.isNotEmpty() && strEq(s, capitalized(s)))
        "removeprefix" -> {
            val p = argStr(args, 0, "removeprefix")
            return str(if (s.startsWith(p)) s.substring(p.length) else s)
        }
        "removesuffix" -> {
            val p = argStr(args, 0, "removesuffix")
            return str(if (s.endsWith(p)) s.substring(0, s.length - p.length) else s)
        }
        "format" -> return str(formatMethod(s, args, kwargs))
        else -> throw error("'str' object has no method '$name'", "AttributeError")
    }
}

private fun replaceLimited(s: String, from: String, to: String, limit: Long): String {
    if (limit <= 0) return s
    val out = StringBuilder()
    var at = 0
    var done = 0L
    while (done < limit) {
        val r = s.indexOf(from, at)
        if (r < 0) break
        out.append(s, at, r).append(to)
        at = r + from.length
        done += 1
    }
    return out.append(s, at, s.length).toString()
}

private fun PyInterpreter.prefixTest(s: String, args: List<PyValue>, suffix: Boolean): Boolean {
    val first = args.firstOrNull() ?: throw error("expected a string argument", "TypeError")
    // `startswith` accepts a tuple of prefixes as well as one string.
    val candidates = if (first is PyValue.Tuple) first.items else listOf(first)
    for (c in candidates) {
        val p = (c as? PyValue.Str)?.value ?: throw error("expected a string argument", "TypeError")
        if (if (suffix) s.endsWith(p) else s.startsWith(p)) return true
    }
    return false
}

private fun PyInterpreter.search(s: String, args: List<PyValue>, last: Boolean, method: String): Int {
    val sub = argStr(args, 0, method)
    val cs = chars(s)
    val lower = if (args.size > 1) normalise(asInt(args[1]) ?: 0, cs.size) else 0
    val upper = if (args.size > 2) normalise(asInt(args[2]) ?: cs.size.toLong(), cs.size) else cs.size
    if (lower > upper) return -1
    val window = cs.subList(lower, upper).joinToString("")
    if (sub.isEmpty()) return lower
    val r = if (last) window.lastIndexOf(sub) else window.indexOf(sub)
    if (r < 0) return -1
    return lower + graphemeCount(window.substring(0, r))
}

private fun normalise(i: Long, count: Int): Int {
    val v = if (i < 0) i + count else i
    return maxOf(0L, minOf(count.toLong(), v)).toInt()
}

private fun PyInterpreter.trim(s: String, args: List<PyValue>, left: Boolean, right: Boolean): String {
    val first = args.firstOrNull()
    val cut: Set<String> = if (first is PyValue.Str) graphemes(first.value).toSet() else setOf(" ", "\t", "\n", "\r")
    val cs = chars(s)
    var from = 0
    var to = cs.size
    if (left) while (from < to && cs[from] in cut) from += 1
    if (right) while (to > from && cs[to - 1] in cut) to -= 1
    return cs.subList(from, to).joinToString("")
}

private fun PyInterpreter.pad(s: String, args: List<PyValue>, align: Char): String {
    val width = args.firstOrNull()?.let { asInt(it) } ?: 0L
    var fill = " "
    if (args.size > 1) {
        val f = args[1]
        if (f is PyValue.Str) graphemes(f.value).firstOrNull()?.let { fill = it }
    }
    val count = chars(s).size
    if (count >= width) return s
    val padding = (width - count).toInt()
    return when (align) {
        '>' -> fill.repeat(padding) + s
        '^' -> {
            val l = padding / 2
            fill.repeat(l) + s + fill.repeat(padding - l)
        }
        else -> s + fill.repeat(padding)
    }
}

private fun PyInterpreter.splitMethod(s: String, args: List<PyValue>, fromEnd: Boolean): PyValue {
    val limit = if (args.size > 1) (asInt(args[1]) ?: -1L) else -1L
    val first = args.firstOrNull()
    if (first is PyValue.Str) {
        val sep = first.value
        if (sep.isEmpty()) throw error("empty separator", "ValueError")
        var parts = s.split(sep)
        if (limit >= 0 && parts.size > limit + 1) {
            // Re-join the parts beyond the limit, from whichever end asked.
            val n = limit.toInt()
            parts = if (fromEnd) {
                listOf(parts.subList(0, parts.size - n).joinToString(sep)) + parts.subList(parts.size - n, parts.size)
            } else {
                parts.subList(0, n) + listOf(parts.subList(n, parts.size).joinToString(sep))
            }
        }
        return list(parts.map { str(it) })
    }
    // No separator: split on runs of whitespace, dropping empties.
    val parts = ArrayList<String>()
    val cur = StringBuilder()
    for (c in chars(s)) {
        if (charIsWhitespace(c)) {
            if (cur.isNotEmpty()) { parts.add(cur.toString()); cur.setLength(0) }
        } else {
            cur.append(c)
        }
    }
    if (cur.isNotEmpty()) parts.add(cur.toString())
    val kept = if (limit >= 0 && parts.size > limit + 1) parts.subList(0, (limit + 1).toInt()) else parts
    return list(kept.map { str(it) })
}

/** `str.format`: positional `{}` and `{0}`, named `{name}`, and the f-string spec grammar. */
private fun PyInterpreter.formatMethod(s: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): String {
    val out = StringBuilder()
    var auto = 0
    val cs = chars(s)
    var i = 0
    while (i < cs.size) {
        val c = cs[i]
        if (c == "{") {
            if (i + 1 < cs.size && cs[i + 1] == "{") { out.append("{"); i += 2; continue }
            val inner = StringBuilder()
            i += 1
            var depth = 1
            while (i < cs.size) {
                if (cs[i] == "{") depth += 1
                if (cs[i] == "}") { depth -= 1; if (depth == 0) break }
                inner.append(cs[i]); i += 1
            }
            if (i >= cs.size) throw error("unmatched '{' in format string", "ValueError")
            i += 1
            val text = inner.toString()
            val colon = text.indexOf(':')
            val field = if (colon >= 0) text.substring(0, colon) else text
            val spec = if (colon >= 0) text.substring(colon + 1) else ""
            val key = trimWhitespaces(field)
            val n = parseSwiftInt(key)
            val value: PyValue = if (key.isEmpty()) {
                if (auto >= args.size) throw error("format index out of range", "IndexError")
                args[auto].also { auto += 1 }
            } else if (n != null) {
                if (n >= args.size) throw error("format index out of range", "IndexError")
                args[n.toInt()]
            } else {
                kwargs.firstOrNull { it.first == key }?.second ?: throw error("'$key'", "KeyError")
            }
            out.append(pyFormat(value, spec))
        } else if (c == "}") {
            if (i + 1 < cs.size && cs[i + 1] == "}") { out.append("}"); i += 2; continue }
            out.append("}"); i += 1
        } else {
            out.append(c); i += 1
        }
    }
    return out.toString()
}

// MARK: - list

private fun PyInterpreter.listMethod(l: PyList, name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    val items = l.items
    when (name) {
        "append" -> {
            arity("append", args, 1)
            if (items.size + 1 > maxContainer) throw abort("list too large")
            items.add(args[0]); return PyValue.None
        }
        "extend" -> {
            val it = args.firstOrNull() ?: throw error("extend() needs an iterable", "TypeError")
            items.addAll(ArrayList(iterate(it)))
            if (items.size > maxContainer) throw abort("list too large")
            return PyValue.None
        }
        "pop" -> {
            if (items.isEmpty()) throw error("pop from empty list", "IndexError")
            val idx = args.firstOrNull()?.let { asInt(it) } ?: (items.size - 1).toLong()
            val i = if (idx < 0) idx + items.size else idx
            if (i < 0 || i >= items.size) throw error("pop index out of range", "IndexError")
            return items.removeAt(i.toInt())
        }
        "insert" -> {
            arity("insert", args, 2)
            val raw = asInt(args[0]) ?: 0L
            val i = maxOf(0L, minOf(items.size.toLong(), if (raw < 0) raw + items.size else raw))
            items.add(i.toInt(), args[1]); return PyValue.None
        }
        "remove" -> {
            arity("remove", args, 1)
            val i = items.indexOfFirst { pyEquals(it, args[0]) }
            if (i < 0) throw error("list.remove(x): x not in list", "ValueError")
            items.removeAt(i); return PyValue.None
        }
        "index" -> {
            val needle = args.firstOrNull() ?: throw error("index() needs a value", "TypeError")
            val from = if (args.size > 1) normalise(asInt(args[1]) ?: 0, items.size) else 0
            for (i in from until items.size) if (pyEquals(items[i], needle)) return int(i)
            throw error("${pyRepr(needle)} is not in list", "ValueError")
        }
        "count" -> { arity("count", args, 1); return int(items.count { pyEquals(it, args[0]) }) }
        "sort" -> {
            insertionSort(items, kwargs.firstOrNull { it.first == "key" }?.second)
            if (kwargs.firstOrNull { it.first == "reverse" }?.second?.isTruthy == true) items.reverse()
            return PyValue.None
        }
        "reverse" -> { items.reverse(); return PyValue.None }
        "clear" -> { items.clear(); return PyValue.None }
        "copy" -> return list(items)
        else -> throw error("'list' object has no method '$name'", "AttributeError")
    }
}

private fun PyInterpreter.tupleMethod(t: List<PyValue>, name: String, args: List<PyValue>): PyValue = when (name) {
    "count" -> { arity("count", args, 1); int(t.count { pyEquals(it, args[0]) }) }
    "index" -> {
        arity("index", args, 1)
        val i = t.indexOfFirst { pyEquals(it, args[0]) }
        if (i < 0) throw error("${pyRepr(args[0])} is not in tuple", "ValueError")
        int(i)
    }
    else -> throw error("'tuple' object has no method '$name'", "AttributeError")
}

// MARK: - set

private fun PyInterpreter.setMethod(s: PySet, name: String, args: List<PyValue>): PyValue {
    fun hashable(v: PyValue): PyHashable = PyHashable.from(v) ?: throw error("unhashable type: '${v.typeName}'", "TypeError")
    fun other(i: Int): PySet {
        if (i >= args.size) throw error("$name() needs a set", "TypeError")
        val a = args[i]
        if (a is PyValue.Set) return a.set
        val built = PySet()
        for (v in iterate(a)) built.insert(hashable(v))
        return built
    }
    return when (name) {
        "add" -> {
            arity("add", args, 1)
            if (s.count + 1 > maxContainer) throw abort("set too large")
            s.insert(hashable(args[0])); PyValue.None
        }
        "discard" -> { arity("discard", args, 1); s.remove(hashable(args[0])); PyValue.None }
        "remove" -> {
            arity("remove", args, 1)
            if (!s.remove(hashable(args[0]))) throw error(pyRepr(args[0]), "KeyError")
            PyValue.None
        }
        "pop" -> {
            val first = s.members.firstOrNull() ?: throw error("pop from an empty set", "KeyError")
            s.remove(first)
            first.value
        }
        "clear" -> { s.removeAll(); PyValue.None }
        "copy" -> PyValue.Set(PySet(s.members))
        "update" -> { s.members.addAll(other(0).members); PyValue.None }
        "union" -> binaryOp(BinOp.BIT_OR, PyValue.Set(s), PyValue.Set(other(0)))
        "intersection" -> binaryOp(BinOp.BIT_AND, PyValue.Set(s), PyValue.Set(other(0)))
        "difference" -> binaryOp(BinOp.SUB, PyValue.Set(s), PyValue.Set(other(0)))
        "symmetric_difference" -> binaryOp(BinOp.BIT_XOR, PyValue.Set(s), PyValue.Set(other(0)))
        "issubset" -> { val o = other(0); bool(s.members.all { o.contains(it) }) }
        "issuperset" -> { val o = other(0); bool(o.members.all { s.contains(it) }) }
        "isdisjoint" -> { val o = other(0); bool(s.members.none { o.contains(it) }) }
        else -> throw error("'set' object has no method '$name'", "AttributeError")
    }
}

// MARK: - dict

private fun PyInterpreter.dictMethod(d: PyDict, name: String, args: List<PyValue>): PyValue {
    fun key(): PyHashable = args.firstOrNull()?.let { PyHashable.from(it) } ?: throw error("unhashable key", "TypeError")
    return when (name) {
        "keys" -> list(d.keys.map { it.value })
        "values" -> list(d.map.values.toList())
        "items" -> list(d.orderedPairs.map { tuple(it.first.value, it.second) })
        "get" -> d[key()] ?: (if (args.size > 1) args[1] else PyValue.None)
        "pop" -> {
            val k = key()
            val v = d[k]
            if (v != null) { d[k] = null; v }
            else if (args.size > 1) args[1]
            else throw error(pyRepr(args[0]), "KeyError")
        }
        "popitem" -> {
            val last = d.keys.lastOrNull() ?: throw error("popitem(): dictionary is empty", "KeyError")
            val v = d[last]!!
            d[last] = null
            tuple(last.value, v)
        }
        "setdefault" -> {
            val k = key()
            d[k] ?: run {
                val def = if (args.size > 1) args[1] else PyValue.None
                d[k] = def
                def
            }
        }
        "update" -> {
            val first = args.firstOrNull() ?: throw error("update() expects a dict", "TypeError")
            if (first is PyValue.Dict) {
                for ((k, v) in first.dict.orderedPairs) d[k] = v
            } else {
                for (pair in iterate(first)) {
                    val items = sequenceItems(pair)
                    val k = if (items != null && items.size == 2) PyHashable.from(items[0]) else null
                    if (items == null || k == null) throw error("update() needs (key, value) pairs", "ValueError")
                    d[k] = items[1]
                }
            }
            PyValue.None
        }
        "clear" -> { d.removeAll(); PyValue.None }
        "copy" -> PyValue.Dict(PyDict(d.orderedPairs))
        else -> throw error("'dict' object has no method '$name'", "AttributeError")
    }
}

// MARK: - re match

private fun PyInterpreter.matchMethod(m: PyMatch, name: String, args: List<PyValue>): PyValue {
    fun groupIndex(v: PyValue): Int {
        asInt(v)?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else -1 }
        if (v is PyValue.Str) m.index(v.value)?.let { return it }
        throw error("no such group", "IndexError")
    }
    fun groupValue(i: Int): PyValue = m.text(i)?.let { str(it) } ?: PyValue.None
    return when (name) {
        "group" -> {
            if (args.isEmpty()) return str(m.value)
            if (args.size == 1) {
                val i = groupIndex(args[0])
                if (i < 0 || i >= m.spans.size) throw error("no such group", "IndexError")
                return groupValue(i)
            }
            PyValue.Tuple(args.map { groupValue(groupIndex(it)) })
        }
        "groups" -> {
            val fallback = args.firstOrNull() ?: PyValue.None
            PyValue.Tuple((1 until maxOf(1, m.spans.size)).map { i -> m.text(i)?.let { str(it) } ?: fallback })
        }
        "groupdict" -> {
            val d = PyDict()
            for ((n, index) in m.names.entries.sortedBy { it.value }) d[PyHashable.Str(n)] = groupValue(index)
            PyValue.Dict(d)
        }
        "start", "end", "span" -> {
            val i = if (args.isEmpty()) 0 else groupIndex(args[0])
            val span = if (i >= 0 && i < m.spans.size) m.spans[i] else null
            if (span == null) {
                return if (name == "span") tuple(int(-1), int(-1)) else int(-1)
            }
            when (name) {
                "start" -> int(span.start)
                "end" -> int(span.end)
                else -> tuple(int(span.start), int(span.end))
            }
        }
        else -> throw error("'Match' object has no method '$name'", "AttributeError")
    }
}

// MARK: - Foundation case helpers

/**
 * Foundation's `String.capitalized`, which PyMini uses for `title()` and
 * `istitle()`. Not Python's rule: see the port notes in the report.
 */
internal fun capitalized(s: String): String = foundationCapitalized(s)

/**
 * Foundation's `folding(options: [.caseInsensitive, .diacriticInsensitive])`,
 * PyMini's `casefold()`: marks stripped, then case folded, so "ÄÖ" becomes "ao".
 */
internal fun foldCaseAndDiacritics(s: String): String {
    val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    var i = 0
    while (i < decomposed.length) {
        val cp = decomposed.codePointAt(i)
        if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    val folded = sb.toString().uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT)
    return java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFC)
}
