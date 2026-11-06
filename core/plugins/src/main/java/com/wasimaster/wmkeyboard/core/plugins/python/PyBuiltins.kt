package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyBuiltins.swift: the global functions a script can call without
 * importing anything. Anything that reaches outside the script (`open`,
 * `input`, `eval`, `__import__`) was never written, so those names don't resolve.
 */

internal val coreBuiltins: Set<String> = setOf(
    "len", "str", "repr", "print", "int", "float", "bool", "abs", "round",
    "min", "max", "sum", "range", "sorted", "reversed", "list", "dict", "set",
    "tuple", "enumerate", "zip", "ord", "chr", "any", "all", "type",
    "map", "filter", "divmod", "pow", "hex", "bin", "oct", "format",
    "isinstance", "callable",
)

/** Names that resolve to a builtin when nothing else is bound to them. */
internal val builtinNames: Set<String> = coreBuiltins + PyExceptionKind.all

/** The only builtins that take keyword arguments. */
internal val kwargBuiltins: Set<String> = setOf("sorted", "min", "max", "round")

internal fun PyInterpreter.callBuiltin(name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    if (PyExceptionKind.all.contains(name)) {
        val message = if (args.isEmpty()) "" else args.joinToString(" ") { pyStr(it) }
        return PyValue.Exception(PyExceptionValue(name, message))
    }
    when (name) {
        "len" -> { arity(name, args, 1); return int(length(args[0])) }
        "str" -> return str(if (args.isEmpty()) "" else pyStr(args[0]))
        "repr" -> { arity(name, args, 1); return str(pyRepr(args[0])) }
        "print" -> { emit(args.joinToString(" ") { pyStr(it) }); return PyValue.None }
        "int" -> return toInt(args)
        "float" -> return toFloat(args)
        "bool" -> return bool(args.firstOrNull()?.isTruthy ?: false)
        "abs" -> {
            arity(name, args, 1)
            val a = args[0]
            if (a is PyValue.Int) return int(Math.abs(a.value))
            val d = asDouble(a) ?: throw error("bad operand type for abs()", "TypeError")
            return float(Math.abs(d))
        }
        "round" -> return roundBuiltin(args, kwargs)
        "min" -> return minmax(args, kwargs, wantMax = false)
        "max" -> return minmax(args, kwargs, wantMax = true)
        "sum" -> return sumBuiltin(args)
        "range" -> return rangeBuiltin(args)
        "sorted" -> return sortedBuiltin(args, kwargs)
        "reversed" -> { arity(name, args, 1); return list(iterate(args[0]).reversed()) }
        "list" -> return list(if (args.isEmpty()) emptyList() else iterate(args[0]))
        "tuple" -> return PyValue.Tuple(if (args.isEmpty()) emptyList() else iterate(args[0]))
        "set" -> {
            val s = PySet()
            for (v in if (args.isEmpty()) emptyList() else iterate(args[0])) {
                s.insert(PyHashable.from(v) ?: throw error("unhashable type: '${v.typeName}'", "TypeError"))
            }
            return PyValue.Set(s)
        }
        "dict" -> return dictBuiltin(args)
        "enumerate" -> return enumerateBuiltin(args)
        "zip" -> return zipBuiltin(args)
        "map" -> return mapBuiltin(args)
        "filter" -> return filterBuiltin(args)
        "divmod" -> {
            arity(name, args, 2)
            val q = binaryOp(BinOp.FLOORDIV, args[0], args[1])
            val r = binaryOp(BinOp.MOD, args[0], args[1])
            return tuple(q, r)
        }
        "pow" -> {
            if (args.size != 2 && args.size != 3) throw error("pow() takes 2 or 3 arguments", "TypeError")
            val p = binaryOp(BinOp.POW, args[0], args[1])
            if (args.size != 3) return p
            return binaryOp(BinOp.MOD, p, args[2])
        }
        "hex" -> return str(radixString(args, 16, "0x"))
        "bin" -> return str(radixString(args, 2, "0b"))
        "oct" -> return str(radixString(args, 8, "0o"))
        "format" -> {
            val v = args.firstOrNull() ?: throw error("format() needs a value", "TypeError")
            val spec = if (args.size > 1) pyStr(args[1]) else ""
            return str(pyFormat(v, spec))
        }
        "isinstance" -> { arity(name, args, 2); return bool(isInstance(args[0], args[1])) }
        "callable" -> {
            arity(name, args, 1)
            val a = args[0]
            return bool(a is PyValue.Function || a is PyValue.Builtin || a is PyValue.ModuleFunction || a is PyValue.HostFunction)
        }
        "ord" -> {
            arity(name, args, 1)
            val s = (args[0] as? PyValue.Str)?.value
            if (s == null || graphemeCount(s) != 1) throw error("ord() expected a character", "TypeError")
            return int(s.codePointAt(0))
        }
        "chr" -> {
            arity(name, args, 1)
            val i = asInt(args[0])
            if (i == null || !isScalar(i)) throw error("chr() arg not in range", "ValueError")
            return str(String(Character.toChars(i.toInt())))
        }
        "any" -> { arity(name, args, 1); return bool(iterate(args[0]).any { it.isTruthy }) }
        "all" -> { arity(name, args, 1); return bool(iterate(args[0]).all { it.isTruthy }) }
        "type" -> { arity(name, args, 1); return str(args[0].typeName) }
        else -> throw error("name '$name' is not defined", "NameError")
    }
}

/** Swift's `Unicode.Scalar(i)` succeeds for these: any code point but a surrogate. */
internal fun isScalar(i: Long): Boolean = i in 0..0x10FFFF && i !in 0xD800..0xDFFF

internal fun PyInterpreter.arity(name: String, args: List<PyValue>, n: Int) {
    if (args.size != n) throw error("$name() takes $n argument(s) but ${args.size} were given", "TypeError")
}

internal fun PyInterpreter.length(v: PyValue): Int = when (v) {
    is PyValue.Str -> chars(v.value).size
    is PyValue.List -> v.list.items.size
    is PyValue.Tuple -> v.items.size
    is PyValue.Set -> v.set.count
    is PyValue.Dict -> v.dict.count
    else -> throw error("object of type '${v.typeName}' has no len()", "TypeError")
}

/** `isinstance(x, str)`. No class objects, so this matches by builtin name. */
private fun isInstance(value: PyValue, type: PyValue): Boolean {
    if (type is PyValue.Tuple) return type.items.any { isInstance(value, it) }
    if (type !is PyValue.Builtin) return false
    return when (type.name) {
        "str" -> value is PyValue.Str
        "int" -> value is PyValue.Int || value is PyValue.Bool
        "float" -> value is PyValue.Float
        "bool" -> value is PyValue.Bool
        "list" -> value is PyValue.List
        "tuple" -> value is PyValue.Tuple
        "dict" -> value is PyValue.Dict
        "set" -> value is PyValue.Set
        else -> false
    }
}

private fun PyInterpreter.radixString(args: List<PyValue>, radix: Int, prefix: String): String {
    val i = args.firstOrNull()?.let { asInt(it) } ?: throw error("expected an integer", "TypeError")
    val body = java.lang.Long.toUnsignedString(if (i < 0) -i else i, radix)
    return (if (i < 0) "-" else "") + prefix + body
}

/** Swift's `Int(d)` traps past the Long range; here that's a catchable error instead. */
internal fun PyInterpreter.toLongExact(d: Double): Long {
    if (d.isNaN() || d >= 9.223372036854775807E18 || d < -9.223372036854775808E18) {
        throw error("cannot convert float ${formatDouble(d)} to integer", "OverflowError")
    }
    return d.toLong()
}

private fun PyInterpreter.toInt(args: List<PyValue>): PyValue {
    val a = args.firstOrNull() ?: return int(0)
    if (args.size > 1) {
        val s = (a as? PyValue.Str)?.value
        val base = asInt(args[1])
        val i = if (s != null && base != null && base in 2..36) parseSwiftInt(trimWhitespaces(s), base.toInt()) else null
        return int(i ?: throw error("invalid literal for int() with that base", "ValueError"))
    }
    return when (a) {
        is PyValue.Int, is PyValue.Bool -> int(asInt(a)!!)
        is PyValue.Float -> int(toLongExact(a.value))
        is PyValue.Str -> {
            val t = trimWhitespaces(a.value)
            val i = parseSwiftInt(t)
            if (i != null) return int(i)
            // CPython refuses "3.5" here. Scripts mostly call int() on text a
            // person typed, so the obvious reading wins over the strict one.
            val d = parseSwiftDouble(t) ?: throw error("invalid literal for int(): ${pyRepr(a)}", "ValueError")
            int(toLongExact(d))
        }
        else -> throw error("int() argument must be a string or a number", "TypeError")
    }
}

private fun PyInterpreter.toFloat(args: List<PyValue>): PyValue {
    val a = args.firstOrNull() ?: return float(0.0)
    asDouble(a)?.let { return float(it) }
    if (a is PyValue.Str) parseSwiftDouble(trimWhitespaces(a.value))?.let { return float(it) }
    throw error("could not convert to float: ${pyRepr(a)}", "ValueError")
}

private fun PyInterpreter.roundBuiltin(args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    val d = args.firstOrNull()?.let { asDouble(it) } ?: throw error("round() needs a number", "TypeError")
    val digitsArg = if (args.size > 1) args[1] else kwargs.firstOrNull { it.first == "ndigits" }?.second
        ?: return int(toLongExact(Math.rint(d)))
    val ndigits = asInt(digitsArg) ?: 0
    val m = Math.pow(10.0, ndigits.toDouble())
    // Only the one-argument form returns an int. `round(x, 0)` is still a float.
    return float(Math.rint(d * m) / m)
}

private fun PyInterpreter.minmax(args: List<PyValue>, kwargs: List<Pair<String, PyValue>>, wantMax: Boolean): PyValue {
    val items = if (args.size == 1) iterate(args[0]) else args
    val key = kwargs.firstOrNull { it.first == "key" }?.second
    if (items.isEmpty()) {
        kwargs.firstOrNull { it.first == "default" }?.let { return it.second }
        throw error("${if (wantMax) "max" else "min"}() arg is an empty sequence", "ValueError")
    }
    var best = items[0]
    var bestKey = if (key == null) best else invoke(key, listOf(best))
    for (v in items.drop(1)) {
        tick()
        val k = if (key == null) v else invoke(key, listOf(v))
        val c = order(k, bestKey)
        if ((wantMax && c > 0) || (!wantMax && c < 0)) { best = v; bestKey = k }
    }
    return best
}

private fun PyInterpreter.sumBuiltin(args: List<PyValue>): PyValue {
    val first = args.firstOrNull() ?: throw error("sum() needs an iterable", "TypeError")
    var acc = if (args.size > 1) args[1] else int(0)
    for (v in iterate(first)) acc = binaryOp(BinOp.ADD, acc, v)
    return acc
}

private fun PyInterpreter.rangeBuiltin(args: List<PyValue>): PyValue {
    val nums = args.map { asInt(it) ?: throw error("range() argument must be an integer", "TypeError") }
    var start = 0L
    var stop = 0L
    var step = 1L
    when (nums.size) {
        1 -> stop = nums[0]
        2 -> { start = nums[0]; stop = nums[1] }
        3 -> { start = nums[0]; stop = nums[1]; step = nums[2] }
        else -> throw error("range() takes 1 to 3 arguments", "TypeError")
    }
    if (step == 0L) throw error("range() step must not be zero", "ValueError")
    val out = ArrayList<PyValue>()
    var i = start
    while ((step > 0 && i < stop) || (step < 0 && i > stop)) {
        out.add(int(i))
        if (out.size > maxContainer) throw abort("range too large")
        i += step
    }
    return list(out)
}

private fun PyInterpreter.dictBuiltin(args: List<PyValue>): PyValue {
    val d = PyDict()
    val first = args.firstOrNull() ?: return PyValue.Dict(d)
    if (first is PyValue.Dict) {
        for ((k, v) in first.dict.orderedPairs) d[k] = v
        return PyValue.Dict(d)
    }
    // dict([(k, v), ...]), the shape zip() and items() hand back.
    for (pair in iterate(first)) {
        val items = sequenceItems(pair)
        val key = if (items != null && items.size == 2) PyHashable.from(items[0]) else null
        if (items == null || key == null) throw error("dict() needs (key, value) pairs", "ValueError")
        d[key] = items[1]
    }
    return PyValue.Dict(d)
}

internal fun PyInterpreter.sortedBuiltin(args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    arity("sorted", args, 1)
    val items = ArrayList(iterate(args[0]))
    val reverse = kwargs.firstOrNull { it.first == "reverse" }?.second?.isTruthy ?: false
    insertionSort(items, kwargs.firstOrNull { it.first == "key" }?.second)
    if (reverse) items.reverse()
    return list(items)
}

/**
 * A stable insertion sort on the three-way order, which throws on mixed types.
 * Quadratic, but every comparison is charged to the budget.
 */
internal fun PyInterpreter.insertionSort(items: MutableList<PyValue>, key: PyValue? = null) {
    if (items.size <= 1) return
    val keys = ArrayList<PyValue>()
    if (key != null) {
        for (v in items) { tick(); keys.add(invoke(key, listOf(v))) }
    } else {
        keys.addAll(items)
    }
    for (i in 1 until items.size) {
        var j = i
        while (j > 0 && order(keys[j], keys[j - 1]) < 0) {
            java.util.Collections.swap(items, j, j - 1)
            java.util.Collections.swap(keys, j, j - 1)
            j -= 1
            tick()
        }
    }
}

private fun PyInterpreter.enumerateBuiltin(args: List<PyValue>): PyValue {
    val first = args.firstOrNull() ?: throw error("enumerate() needs an iterable", "TypeError")
    val startIdx = if (args.size > 1) (asInt(args[1]) ?: 0L) else 0L
    return list(iterate(first).mapIndexed { offset, v -> tuple(int(startIdx + offset), v) })
}

private fun PyInterpreter.zipBuiltin(args: List<PyValue>): PyValue {
    val seqs = args.map { iterate(it) }
    val shortest = seqs.minOfOrNull { it.size } ?: return list(emptyList())
    return list((0 until shortest).map { i -> PyValue.Tuple(seqs.map { it[i] }) })
}

private fun PyInterpreter.mapBuiltin(args: List<PyValue>): PyValue {
    if (args.size < 2) throw error("map() needs a function and an iterable", "TypeError")
    val seqs = args.drop(1).map { iterate(it) }
    val count = seqs.minOfOrNull { it.size } ?: 0
    val out = ArrayList<PyValue>()
    for (i in 0 until count) {
        tick()
        out.add(invoke(args[0], seqs.map { it[i] }))
    }
    return list(out)
}

private fun PyInterpreter.filterBuiltin(args: List<PyValue>): PyValue {
    if (args.size != 2) throw error("filter() needs a function and an iterable", "TypeError")
    val out = ArrayList<PyValue>()
    for (v in iterate(args[1])) {
        tick()
        // filter(None, xs) keeps the truthy items, as in Python.
        val keep = if (args[0].isNone) v.isTruthy else invoke(args[0], listOf(v)).isTruthy
        if (keep) out.add(v)
    }
    return list(out)
}
