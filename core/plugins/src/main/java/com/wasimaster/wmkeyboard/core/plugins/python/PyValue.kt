package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyValue.swift: the runtime value model, exceptions and errors.
 *
 * Lists, dicts and sets are reference types so Python's mutation semantics
 * work; tuples are plain values, so they hash and can key a dict. Everything is
 * single threaded: a value belongs to the runtime that made it.
 */

/**
 * A value inside a PyMini script.
 *
 * Strings follow PyMini exactly, which means Swift's `String`: `len`, indexing,
 * slicing and iteration count extended grapheme clusters (PyMini does
 * `Array(s)`, an array of `Character`, in `index`, `slice` and `iterate` of
 * PyInterpreter.swift), so `len("👍🏽") == 1` and `"héllo"[::-1]` keeps the
 * accent on its letter. Clusters come from `java.text.BreakIterator`. String
 * `==` and `<` use canonical equivalence (NFC), as Swift's do.
 *
 * Ints are 64-bit, but arithmetic runs through `Double` as PyMini's does, so a
 * result past 2^53 comes back as a float (`10**20` prints `1e+20`).
 */
sealed class PyValue {
    object None : PyValue()
    class Bool(val value: Boolean) : PyValue()
    class Int(val value: Long) : PyValue()
    class Float(val value: Double) : PyValue()
    class Str(val value: String) : PyValue()
    class List(val list: PyList) : PyValue()
    /** Immutable and hashable: `(1, 2)`, the pairs `enumerate` hands back, dict keys. */
    class Tuple(val items: kotlin.collections.List<PyValue>) : PyValue()
    class Set(val set: PySet) : PyValue()
    class Dict(val dict: PyDict) : PyValue()
    /** A `def` or a `lambda`. */
    class Function(val function: PyFunction) : PyValue()
    /** A named builtin like `len`, dispatched in PyBuiltins. */
    class Builtin(val name: String) : PyValue()
    /** A namespace bound by `import`. */
    class Module(val name: String) : PyValue()
    /** A namespace member referenced without calling it (`f = math.sqrt`). */
    class ModuleFunction(val module: String, val name: String) : PyValue()
    /** A Kotlin function the host handed to the runtime. */
    class HostFunction(val function: PyHostFunction) : PyValue()
    /** A raised (or caught) exception. */
    class Exception(val exception: PyExceptionValue) : PyValue()
    /** A successful `re` match. */
    class Match(val match: PyMatch) : PyValue()

    /** The text of a str, or null for anything else. */
    val stringValue: String? get() = (this as? Str)?.value

    /** An int, or a bool as 1/0. */
    val intValue: Long? get() = asInt(this)

    /** An int or float as a Double. */
    val doubleValue: Double? get() = asDouble(this)

    val boolValue: Boolean? get() = (this as? Bool)?.value

    /** The items of a list or tuple. */
    val arrayValue: kotlin.collections.List<PyValue>? get() = when (this) {
        is List -> list.items.toList()
        is Tuple -> items
        else -> null
    }

    val isNone: Boolean get() = this === None

    /** The Python type name, as `type()` and error messages say it. */
    val typeName: String get() = pyTypeName(this)

    val isTruthy: Boolean get() = pyTruthy(this)

    /** Python `str()`. */
    fun str(): String = pyStr(this)

    /** Python `repr()`. */
    fun repr(): String = pyRepr(this)

    override fun toString(): String = pyStr(this)

    /** Python `==`, so `Int(1) == Float(1.0)`. */
    final override fun equals(other: Any?): Boolean = other is PyValue && pyEquals(this, other)

    final override fun hashCode(): kotlin.Int = pyHash(this)

    companion object {
        /** Wrap a Kotlin value: String, Int/Long, Double/Float, Boolean, null, List, Array, Map, or a PyValue. */
        fun of(value: Any?): PyValue = toPyValue(value)
    }
}

/** A list. Mutations alias, like Python. */
class PyList(items: kotlin.collections.List<PyValue> = emptyList()) {
    val items: ArrayList<PyValue> = ArrayList(items)
}

/** An insertion-ordered dict. Keys must be hashable. */
class PyDict() {
    internal val map = LinkedHashMap<PyHashable, PyValue>()

    constructor(pairs: kotlin.collections.List<Pair<PyHashable, PyValue>>) : this() {
        for ((k, v) in pairs) map[k] = v
    }

    val count: Int get() = map.size
    val keys: List<PyHashable> get() = map.keys.toList()
    val orderedPairs: List<Pair<PyHashable, PyValue>> get() = map.entries.map { Pair(it.key, it.value) }

    operator fun get(key: PyHashable): PyValue? = map[key]

    /** Setting null removes the key, as in Swift. */
    operator fun set(key: PyHashable, value: PyValue?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun removeAll() = map.clear()
}

/**
 * A set, kept in insertion order so iteration and `repr` are stable between
 * runs. Python makes no order promise; a stable one is easier to test.
 */
class PySet() {
    internal val members = LinkedHashSet<PyHashable>()

    constructor(values: Collection<PyHashable>) : this() {
        members.addAll(values)
    }

    val count: Int get() = members.size
    val order: List<PyHashable> get() = members.toList()
    val values: List<PyValue> get() = members.map { it.value }

    fun contains(v: PyHashable): Boolean = members.contains(v)
    fun insert(v: PyHashable): Boolean = members.add(v)
    fun remove(v: PyHashable): Boolean = members.remove(v)
    fun removeAll() = members.clear()
}

/**
 * The values that can be dict keys and set members. Like Swift's synthesized
 * Hashable, `1`, `1.0` and `True` are three different keys.
 */
sealed class PyHashable {
    object None : PyHashable()
    data class Bool(val v: Boolean) : PyHashable()
    data class Int(val v: Long) : PyHashable()
    class Float(val v: Double) : PyHashable() {
        override fun equals(other: Any?): Boolean = other is Float && other.v == v
        override fun hashCode(): kotlin.Int = if (v == 0.0) 0 else v.hashCode()
    }
    class Str(val v: String) : PyHashable() {
        override fun equals(other: Any?): Boolean = other is Str && strEq(other.v, v)
        override fun hashCode(): kotlin.Int = strHash(v)
    }
    data class Tuple(val items: kotlin.collections.List<PyHashable>) : PyHashable()

    val value: PyValue get() = when (this) {
        None -> PyValue.None
        is Bool -> PyValue.Bool(v)
        is Int -> PyValue.Int(v)
        is Float -> PyValue.Float(v)
        is Str -> PyValue.Str(v)
        is Tuple -> PyValue.Tuple(items.map { it.value })
    }

    companion object {
        /** The hashable form of [v], or null for lists, dicts, sets and functions. */
        fun from(v: PyValue): PyHashable? = when (v) {
            PyValue.None -> None
            is PyValue.Bool -> Bool(v.value)
            is PyValue.Int -> Int(v.value)
            is PyValue.Float -> Float(v.value)
            is PyValue.Str -> Str(v.value)
            is PyValue.Tuple -> {
                val parts = ArrayList<PyHashable>(v.items.size)
                var ok = true
                for (item in v.items) {
                    val h = from(item)
                    if (h == null) { ok = false; break }
                    parts.add(h)
                }
                if (ok) Tuple(parts) else null
            }
            else -> null
        }
    }
}

/** A function defined in a script, with the scope it closed over. */
class PyFunction internal constructor(
    val name: String,
    internal val params: List<Param>,
    /** The name that collects leftover positional arguments (`*rest`), if any. */
    internal val variadic: String?,
    internal val body: List<Stmt>,
    internal val closure: PyEnv,
) {
    /** Parameter names in declaration order, not counting `*rest`. */
    val parameterNames: List<String> get() = params.map { it.name }
}

/**
 * A raised exception. There are no classes, so an exception is a type name and
 * a message; that covers `except ValueError as e:` and `str(e)`.
 */
data class PyExceptionValue(val type: String, val message: String)

/** The exception names scripts can raise and catch, and the parents an `except` may name. */
internal object PyExceptionKind {
    val all: List<String> = listOf(
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
        "NameError", "ZeroDivisionError", "AttributeError", "RuntimeError", "StopIteration",
    )

    fun matches(clause: String, type: String): Boolean {
        if (clause == "Exception") return true
        if (clause == type) return true
        // The two subclass relationships scripts actually lean on.
        if (clause == "LookupError") return type == "KeyError" || type == "IndexError"
        if (clause == "ArithmeticError") return type == "ZeroDivisionError"
        return false
    }
}

/**
 * A syntax or runtime error, with a 1-based line when one is known.
 *
 * [kind] is the exception name an `except` clause matches. [isAbort] marks the
 * sandbox's own stops (step budget, cancellation, size caps): a script can't
 * catch those, because catching them is how a runaway script would keep going.
 * Host functions can throw this too; a `PyError("need a URL", "ValueError")`
 * arrives in the script as a ValueError it can handle.
 */
class PyError(
    message: String,
    val kind: String = "Exception",
    val line: Int? = null,
    val isAbort: Boolean = false,
) : Exception(message, null, false, false) {

    /** The message alone, without kind or line. */
    val text: String get() = super.message ?: ""

    /** One line for a person, e.g. `Line 3: NameError: name 'foo' is not defined`. */
    val description: String
        get() {
            val body = if (isAbort || kind == "RuntimeError") text else "$kind: $text"
            return if (line != null) "Line $line: $body" else body
        }

    internal val exceptionValue: PyExceptionValue get() = PyExceptionValue(kind, text)

    internal fun withLine(line: Int): PyError = PyError(text, kind, line, isAbort)

    override fun toString(): String = description

    companion object {
        /** The sandbox's own abort. Uncatchable, and reported without a type prefix. */
        internal fun abort(message: String, line: Int? = null): PyError = PyError(message, "SystemExit", line, true)

        internal fun of(value: PyExceptionValue, line: Int?): PyError = PyError(value.message, value.type, line)
    }
}

/** A PyError with PyMini's default kind, RuntimeError, which prints without a prefix. */
internal fun perr(message: String, line: Int? = null, kind: String = "RuntimeError"): PyError =
    PyError(message, kind, line)

/** Control flow that isn't an error, thrown and caught inside the evaluator. */
internal sealed class PyFlow : Throwable(null, null, false, false) {
    object BreakLoop : PyFlow()
    object ContinueLoop : PyFlow()
    class ReturnValue(val value: PyValue) : PyFlow()
}

// MARK: - Value helpers

internal fun pyTypeName(v: PyValue): String = when (v) {
    PyValue.None -> "NoneType"
    is PyValue.Bool -> "bool"
    is PyValue.Int -> "int"
    is PyValue.Float -> "float"
    is PyValue.Str -> "str"
    is PyValue.List -> "list"
    is PyValue.Tuple -> "tuple"
    is PyValue.Set -> "set"
    is PyValue.Dict -> "dict"
    is PyValue.Function, is PyValue.Builtin, is PyValue.ModuleFunction, is PyValue.HostFunction -> "function"
    is PyValue.Module -> "module"
    is PyValue.Exception -> v.exception.type
    is PyValue.Match -> "Match"
}

internal fun pyTruthy(v: PyValue): Boolean = when (v) {
    PyValue.None -> false
    is PyValue.Bool -> v.value
    is PyValue.Int -> v.value != 0L
    is PyValue.Float -> v.value != 0.0
    is PyValue.Str -> v.value.isNotEmpty()
    is PyValue.List -> v.list.items.isNotEmpty()
    is PyValue.Tuple -> v.items.isNotEmpty()
    is PyValue.Set -> v.set.count != 0
    is PyValue.Dict -> v.dict.count != 0
    else -> true
}

/** The number as a Double, or null. `True` counts as 1, like Python. */
internal fun asDouble(v: PyValue): Double? = when (v) {
    is PyValue.Bool -> if (v.value) 1.0 else 0.0
    is PyValue.Int -> v.value.toDouble()
    is PyValue.Float -> v.value
    else -> null
}

/** An int or bool as Long. Floats are null. */
internal fun asInt(v: PyValue): Long? = when (v) {
    is PyValue.Bool -> if (v.value) 1L else 0L
    is PyValue.Int -> v.value
    else -> null
}

/** The items of a list, tuple or set, without charging the step budget. */
internal fun sequenceItems(v: PyValue): List<PyValue>? = when (v) {
    is PyValue.List -> v.list.items
    is PyValue.Tuple -> v.items
    is PyValue.Set -> v.set.values
    else -> null
}

internal fun str(s: String): PyValue = PyValue.Str(s)
internal fun int(i: Long): PyValue = PyValue.Int(i)
internal fun int(i: Int): PyValue = PyValue.Int(i.toLong())
internal fun bool(b: Boolean): PyValue = PyValue.Bool(b)
internal fun float(d: Double): PyValue = PyValue.Float(d)
internal fun tuple(vararg items: PyValue): PyValue = PyValue.Tuple(items.toList())
internal fun list(items: List<PyValue>): PyValue = PyValue.List(PyList(items))

/** `str(value)` */
internal fun pyStr(v: PyValue): String = when (v) {
    PyValue.None -> "None"
    is PyValue.Bool -> if (v.value) "True" else "False"
    is PyValue.Int -> v.value.toString()
    is PyValue.Float -> formatDouble(v.value)
    is PyValue.Str -> v.value
    is PyValue.List, is PyValue.Dict, is PyValue.Tuple, is PyValue.Set -> pyRepr(v)
    is PyValue.Function -> "<function ${v.function.name}>"
    is PyValue.Builtin -> "<builtin ${v.name}>"
    is PyValue.HostFunction -> "<builtin ${v.function.name}>"
    is PyValue.Module -> "<module '${v.name}'>"
    is PyValue.ModuleFunction -> "<builtin ${v.module}.${v.name}>"
    is PyValue.Exception -> v.exception.message
    is PyValue.Match -> "<Match ${pyRepr(PyValue.Str(v.match.value))}>"
}

/** `repr(value)`: strings get quotes, containers repr their items. */
internal fun pyRepr(v: PyValue): String = when (v) {
    is PyValue.Str -> {
        val escaped = v.value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        "'$escaped'"
    }
    is PyValue.List -> "[" + v.list.items.joinToString(", ") { pyRepr(it) } + "]"
    is PyValue.Tuple ->
        // Keep Python's trailing comma on a one-item tuple so the repr round-trips.
        if (v.items.size == 1) "(" + pyRepr(v.items[0]) + ",)"
        else "(" + v.items.joinToString(", ") { pyRepr(it) } + ")"
    is PyValue.Set ->
        if (v.set.count == 0) "set()" else "{" + v.set.values.joinToString(", ") { pyRepr(it) } + "}"
    is PyValue.Dict ->
        "{" + v.dict.orderedPairs.joinToString(", ") { "${pyRepr(it.first.value)}: ${pyRepr(it.second)}" } + "}"
    is PyValue.Exception -> "${v.exception.type}(${pyRepr(PyValue.Str(v.exception.message))})"
    else -> pyStr(v)
}

/**
 * Print a Double the way Python does: whole numbers keep their `.0`, and
 * everything else gets the shortest `%g` form that reads back as the same value.
 */
internal fun formatDouble(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d < 0) "-inf" else "inf"
    if (d == Math.rint(d) && Math.abs(d) < 1e16) return cFixed(d, 1)
    for (p in 1..17) {
        val s = cGeneral(d, p)
        if (s.toDouble() == d) return s
    }
    return cGeneral(d, 17)
}

/** Python `==`. Numbers compare across types, so 1 == 1.0 == True. */
internal fun pyEquals(a: PyValue, b: PyValue): Boolean {
    if (a === PyValue.None && b === PyValue.None) return true
    if (a is PyValue.Str && b is PyValue.Str) return strEq(a.value, b.value)
    if (a is PyValue.List && b is PyValue.List) {
        val x = a.list.items
        val y = b.list.items
        if (x.size != y.size) return false
        for (i in x.indices) if (!pyEquals(x[i], y[i])) return false
        return true
    }
    if (a is PyValue.Tuple && b is PyValue.Tuple) {
        if (a.items.size != b.items.size) return false
        for (i in a.items.indices) if (!pyEquals(a.items[i], b.items[i])) return false
        return true
    }
    if (a is PyValue.Set && b is PyValue.Set) {
        if (a.set.count != b.set.count) return false
        return a.set.members.all { b.set.contains(it) }
    }
    if (a is PyValue.Dict && b is PyValue.Dict) {
        if (a.dict.count != b.dict.count) return false
        for ((k, v) in a.dict.map) {
            val yv = b.dict[k] ?: return false
            if (!pyEquals(v, yv)) return false
        }
        return true
    }
    if (a is PyValue.Exception && b is PyValue.Exception) return a.exception == b.exception
    if (a is PyValue.HostFunction && b is PyValue.HostFunction) return a.function === b.function
    val x = asDouble(a) ?: return false
    val y = asDouble(b) ?: return false
    return x == y
}

/** A hash consistent with [pyEquals]. Mutable containers all share one bucket. */
internal fun pyHash(v: PyValue): Int = when (v) {
    PyValue.None -> 0
    is PyValue.Str -> strHash(v.value)
    is PyValue.Tuple -> v.items.fold(17) { acc, item -> acc * 31 + pyHash(item) }
    is PyValue.Exception -> v.exception.hashCode()
    else -> asDouble(v)?.let { if (it == 0.0) 0 else it.hashCode() } ?: 1
}

/**
 * `is`. Containers compare by identity. Scalars fall back to equality, which
 * is what CPython's small-value interning makes `is` look like anyway.
 */
internal fun pyIdentical(a: PyValue, b: PyValue): Boolean {
    if (a is PyValue.List && b is PyValue.List) return a.list === b.list
    if (a is PyValue.Dict && b is PyValue.Dict) return a.dict === b.dict
    if (a is PyValue.Set && b is PyValue.Set) return a.set === b.set
    if (a is PyValue.Function && b is PyValue.Function) return a.function === b.function
    if (a is PyValue.List || b is PyValue.List || a is PyValue.Dict || b is PyValue.Dict ||
        a is PyValue.Set || b is PyValue.Set
    ) return false
    return pyEquals(a, b)
}
