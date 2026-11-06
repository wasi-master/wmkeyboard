package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyHost.swift and PyConvertible.swift: Kotlin functions and modules
 * that scripts can call, and the conversions between Kotlin values and
 * PyValue. A host function is the only way a script reaches beyond itself, so
 * the sandbox is exactly as wide as the functions defined here.
 */

/** A Kotlin function exposed to scripts, as a global or as a module member. */
class PyHostFunction internal constructor(val name: String, internal val body: (PyCall) -> PyValue) {
    internal companion object {
        fun wrapping(name: String, body: (PyCall) -> Any?): PyHostFunction =
            PyHostFunction(name) { call -> toPyValue(body(call)) }
    }
}

/** A module a script can import, filled in by the host. */
class PyHostModule internal constructor(val name: String) {
    internal val functions = HashMap<String, PyHostFunction>()
    internal val constants = HashMap<String, PyValue>()

    /** A function scripts call as `module.name(...)`. Returning Unit or null gives `None`. */
    fun define(name: String, body: (PyCall) -> Any?) {
        functions[name] = PyHostFunction.wrapping(name, body)
    }

    /** A value scripts read as `module.name`. */
    fun set(name: String, value: Any?) {
        constants[name] = toPyValue(value)
    }

    internal fun member(name: String): PyValue? =
        constants[name] ?: functions[name]?.let { PyValue.HostFunction(it) }
}

/** One call from a script into a host function. */
class PyCall internal constructor(
    /** The name the function was registered under. */
    val name: String,
    val arguments: List<PyValue>,
    val keywords: List<Pair<String, PyValue>>,
    /** The script line that made the call. */
    val line: Int,
    private val interpreter: PyInterpreter,
) {
    /** The argument at [position], or the keyword argument [keyword] when it was passed by name. */
    fun value(position: Int, keyword: String? = null): PyValue? {
        if (keyword != null) keywords.firstOrNull { it.first == keyword }?.let { return it.second }
        return arguments.getOrNull(position)
    }

    /** A required argument, converted. A missing or mistyped one is a TypeError the script can catch. */
    fun <T> argument(position: Int, type: PyType<T>, keyword: String? = null): T {
        val raw = value(position, keyword)
            ?: throw PyError("$name() missing required argument ${which(position, keyword)}", "TypeError", line)
        return convert(raw, type, position, keyword)
    }

    /** An optional argument, converted, or [fallback] when the script left it out. */
    fun <T> argumentOr(position: Int, type: PyType<T>, fallback: T, keyword: String? = null): T {
        val raw = value(position, keyword) ?: return fallback
        return convert(raw, type, position, keyword)
    }

    private fun which(position: Int, keyword: String?): String = keyword?.let { "'$it'" } ?: "${position + 1}"

    private fun <T> convert(raw: PyValue, type: PyType<T>, position: Int, keyword: String?): T {
        val converted = type.reader(raw)
            ?: throw PyError(
                "$name() argument ${which(position, keyword)} must be ${type.pyTypeName}, not ${raw.typeName}",
                "TypeError",
                line,
            )
        return converted.value
    }

    /** Call something the script passed in, like a callback. It spends the same step budget. */
    fun invoke(callable: PyValue, vararg arguments: PyValue): PyValue = interpreter.invoke(callable, arguments.toList())
}

/** A successful conversion; wraps the value so a converted `null` (from `None`) differs from a failure. */
class PyConverted<T> internal constructor(val value: T)

/**
 * A Kotlin type a PyValue can be read as, the port of Swift's `PyConvertible`.
 * Ints read as Long and accept bools; floats accept ints, as in PyMini.
 */
class PyType<T> internal constructor(
    /** What error messages call this type, like `str` or `list`. */
    val pyTypeName: String,
    internal val reader: (PyValue) -> PyConverted<T>?,
) {
    /** The converted value, or null when [value] has the wrong shape. */
    fun from(value: PyValue): T? = reader(value)?.value

    /** Whether [value] converts. Tells `None` read as an optional apart from a failure. */
    fun accepts(value: PyValue): Boolean = reader(value) != null

    companion object {
        val STR: PyType<String> = PyType("str") { v -> (v as? PyValue.Str)?.let { PyConverted(it.value) } }
        val INT: PyType<Long> = PyType("int") { v -> asInt(v)?.let { PyConverted(it) } }
        val FLOAT: PyType<Double> = PyType("float") { v -> asDouble(v)?.let { PyConverted(it) } }
        val BOOL: PyType<Boolean> = PyType("bool") { v -> (v as? PyValue.Bool)?.let { PyConverted(it.value) } }
        val VALUE: PyType<PyValue> = PyType("object") { v -> PyConverted(v) }

        /** A list or tuple whose every item converts. */
        fun <T> listOf(element: PyType<T>): PyType<List<T>> = PyType("list") { v ->
            val items = when (v) {
                is PyValue.List -> v.list.items
                is PyValue.Tuple -> v.items
                else -> null
            }
            if (items == null) {
                null
            } else {
                val out = ArrayList<T>(items.size)
                var ok = true
                for (item in items) {
                    val c = element.reader(item)
                    if (c == null) { ok = false; break }
                    out.add(c.value)
                }
                if (ok) PyConverted(out as List<T>) else null
            }
        }

        /** A dict whose keys are all strings and whose values all convert. */
        fun <T> dictOf(value: PyType<T>): PyType<Map<String, T>> = PyType("dict") { v ->
            if (v !is PyValue.Dict) {
                null
            } else {
                val out = LinkedHashMap<String, T>()
                var ok = true
                for ((k, item) in v.dict.orderedPairs) {
                    val c = value.reader(item)
                    if (k !is PyHashable.Str || c == null) { ok = false; break }
                    out[k.v] = c.value
                }
                if (ok) PyConverted(out as Map<String, T>) else null
            }
        }

        /** `None` reads as null; anything else has to convert to [wrapped]. */
        fun <T> optional(wrapped: PyType<T>): PyType<T?> = PyType("${wrapped.pyTypeName} or None") { v ->
            if (v.isNone) PyConverted(null) else wrapped.reader(v)?.let { PyConverted<T?>(it.value) }
        }
    }
}

/**
 * Wrap a Kotlin value: null and Unit become None; String, Char, Boolean, the
 * integer types, Double and Float become scalars; a Map with only String keys
 * becomes a dict with sorted keys (so the same map always builds the same
 * dict, as PyMini does with a Swift dictionary); other maps keep their order;
 * collections and arrays become lists. Anything else is rejected.
 */
internal fun toPyValue(value: Any?): PyValue = when (value) {
    null, Unit -> PyValue.None
    is PyValue -> value
    is String -> PyValue.Str(value)
    is Char -> PyValue.Str(value.toString())
    is Boolean -> PyValue.Bool(value)
    is Int -> PyValue.Int(value.toLong())
    is Long -> PyValue.Int(value)
    is Short -> PyValue.Int(value.toLong())
    is Byte -> PyValue.Int(value.toLong())
    is Double -> PyValue.Float(value)
    is Float -> PyValue.Float(value.toDouble())
    is Map<*, *> -> {
        val d = PyDict()
        val entries: List<Map.Entry<*, *>> = if (value.keys.all { it is String }) {
            value.entries.sortedWith { a, b -> strCompare(a.key as String, b.key as String) }
        } else {
            value.entries.toList()
        }
        for (e in entries) {
            val k = toPyValue(e.key)
            val h = PyHashable.from(k) ?: throw IllegalArgumentException("unhashable dict key: ${k.typeName}")
            d[h] = toPyValue(e.value)
        }
        PyValue.Dict(d)
    }
    is Iterable<*> -> PyValue.List(PyList(value.map { toPyValue(it) }))
    is Array<*> -> PyValue.List(PyList(value.map { toPyValue(it) }))
    else -> throw IllegalArgumentException("cannot pass a ${value.javaClass.name} to a script")
}
