package com.wasimaster.wmkeyboard.core.plugins.python

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Port of PyModules.swift: the standard namespaces a script can `import`:
 * math, random, time, json, re and sys. Each one only computes: `random` draws
 * from a seedable SplitMix64, `time` reads the clock, `re` spends the step
 * budget like any other loop. None can touch a file, the network or another
 * process. A host can switch any of them off with `PyRuntime(modules = …)`.
 */
internal object PyModules {
    val names: Set<String> = setOf("math", "random", "time", "json", "re", "sys")

    val functions: Map<String, Set<String>> = mapOf(
        "math" to setOf(
            "sqrt", "floor", "ceil", "trunc", "fabs", "pow", "exp", "log", "log2",
            "log10", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
            "degrees", "radians", "hypot", "gcd", "lcm", "factorial",
            "isnan", "isinf", "isfinite", "copysign", "fmod", "comb", "prod",
        ),
        "random" to setOf("random", "randint", "randrange", "choice", "choices", "shuffle", "sample", "uniform", "seed"),
        "time" to setOf("time", "monotonic", "parts", "format"),
        "json" to setOf("dumps", "loads"),
        "re" to setOf("search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split", "escape"),
    )

    val constantNames: Map<String, Set<String>> = mapOf(
        "math" to setOf("pi", "e", "tau", "inf", "nan"),
        "re" to setOf("I", "IGNORECASE", "M", "MULTILINE", "S", "DOTALL"),
        "sys" to setOf("version"),
    )

    fun constant(module: String, name: String): PyValue? = when (module to name) {
        "math" to "pi" -> PyValue.Float(Math.PI)
        "math" to "e" -> PyValue.Float(Math.E)
        "math" to "tau" -> PyValue.Float(Math.PI * 2)
        "math" to "inf" -> PyValue.Float(Double.POSITIVE_INFINITY)
        "math" to "nan" -> PyValue.Float(Double.NaN)
        "re" to "I", "re" to "IGNORECASE" -> PyValue.Int(PyRegexFlags.IGNORE_CASE.toLong())
        "re" to "M", "re" to "MULTILINE" -> PyValue.Int(PyRegexFlags.MULTILINE.toLong())
        "re" to "S", "re" to "DOTALL" -> PyValue.Int(PyRegexFlags.DOT_ALL.toLong())
        "sys" to "version" -> PyValue.Str(PyRuntime.VERSION_LINE)
        else -> null
    }

    /** Resolve `module.name`, or null when the namespace has no such member. */
    fun member(module: String, name: String): PyValue? {
        constant(module, name)?.let { return it }
        if (functions[module]?.contains(name) != true) return null
        return PyValue.ModuleFunction(module, name)
    }

    /** Every exported name, sorted. */
    fun exports(module: String): List<String> =
        ((functions[module] ?: emptySet()) + (constantNames[module] ?: emptySet())).sorted()
}

internal fun PyInterpreter.callModule(module: String, name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    tick()
    hostModules[module]?.let { host ->
        val function = host.functions[name] ?: throw error("module '$module' has no function '$name'", "AttributeError")
        return callHost(function, args, kwargs)
    }
    if (!enabledModules.contains(module)) throw error("no module named '$module'", "ImportError")
    return when (module) {
        "math" -> mathCall(name, args)
        "random" -> randomCall(name, args)
        "time" -> timeCall(name, args)
        "json" -> jsonCall(name, args, kwargs)
        "re" -> regexCall(name, args, kwargs)
        else -> throw error("no module named '$module'", "ImportError")
    }
}

// MARK: - math

private fun PyInterpreter.mathCall(name: String, args: List<PyValue>): PyValue {
    fun number(i: Int): Double = args.getOrNull(i)?.let { asDouble(it) }
        ?: throw error("math.$name() needs a number", "TypeError")
    fun integer(i: Int): Long = args.getOrNull(i)?.let { asInt(it) }
        ?: throw error("math.$name() needs an integer", "TypeError")
    fun exact(f: () -> Long): Long = try {
        f()
    } catch (e: ArithmeticException) {
        throw error("integer overflow in math.$name()", "OverflowError")
    }
    return when (name) {
        "sqrt" -> {
            val x = number(0)
            if (!(x >= 0)) throw error("math domain error", "ValueError")
            float(Math.sqrt(x))
        }
        "floor" -> int(toLongExact(Math.floor(number(0))))
        "ceil" -> int(toLongExact(Math.ceil(number(0))))
        "trunc" -> { val x = number(0); int(toLongExact(if (x < 0) Math.ceil(x) else Math.floor(x))) }
        "fabs" -> float(Math.abs(number(0)))
        "pow" -> float(Math.pow(number(0), number(1)))
        "exp" -> float(Math.exp(number(0)))
        "log" -> {
            val x = number(0)
            if (!(x > 0)) throw error("math domain error", "ValueError")
            if (args.size > 1) float(Math.log(x) / Math.log(number(1))) else float(Math.log(x))
        }
        "log2" -> float(log2(number(0)))
        "log10" -> float(Math.log10(number(0)))
        "sin" -> float(Math.sin(number(0)))
        "cos" -> float(Math.cos(number(0)))
        "tan" -> float(Math.tan(number(0)))
        "asin" -> float(Math.asin(number(0)))
        "acos" -> float(Math.acos(number(0)))
        "atan" -> float(Math.atan(number(0)))
        "atan2" -> float(Math.atan2(number(0), number(1)))
        "degrees" -> float(number(0) * 180 / Math.PI)
        "radians" -> float(number(0) * Math.PI / 180)
        "hypot" -> {
            var total = 0.0
            for (i in args.indices) total += Math.pow(number(i), 2.0)
            float(Math.sqrt(total))
        }
        "gcd" -> {
            var g = Math.abs(integer(0))
            for (i in 1 until maxOf(1, args.size)) g = gcd(g, Math.abs(integer(i)))
            int(g)
        }
        "lcm" -> {
            var l = Math.abs(integer(0))
            for (i in 1 until maxOf(1, args.size)) {
                val b = Math.abs(integer(i))
                if (l == 0L || b == 0L) return int(0)
                val g = gcd(l, b)
                l = exact { Math.multiplyExact(l / g, b) }
            }
            int(l)
        }
        "factorial" -> {
            val n = integer(0)
            if (n < 0) throw error("factorial() not defined for negative values", "ValueError")
            if (n > 20) throw error("factorial() argument too large", "ValueError")
            var r = 1L
            for (i in 2..n) r *= i
            int(r)
        }
        "comb" -> {
            val n = integer(0)
            val k = integer(1)
            if (n < 0 || k < 0) throw error("comb() needs non-negative values", "ValueError")
            if (k > n) return int(0)
            var r = 1L
            for (i in 0 until minOf(k, n - k)) {
                tick()
                r = exact { Math.multiplyExact(r, n - i) } / (i + 1)
            }
            int(r)
        }
        "prod" -> {
            var acc: PyValue = int(1)
            for (v in iterate(args.firstOrNull() ?: list(emptyList()))) acc = binaryOp(BinOp.MUL, acc, v)
            acc
        }
        "isnan" -> bool(number(0).isNaN())
        "isinf" -> bool(number(0).isInfinite())
        "isfinite" -> bool(number(0).isFinite())
        "copysign" -> float(Math.copySign(number(0), number(1)))
        "fmod" -> float(number(0).rem(number(1)))
        else -> throw error("module 'math' has no attribute '$name'", "AttributeError")
    }
}

/** C's log2: exact on powers of two, where log(x)/log(2) can miss by an ulp. */
private fun log2(x: Double): Double {
    if (x > 0 && x.isFinite() && x == Math.scalb(1.0, Math.getExponent(x))) return Math.getExponent(x).toDouble()
    return Math.log(x) / Math.log(2.0)
}

private fun gcd(a: Long, b: Long): Long {
    var x = a
    var y = b
    while (y != 0L) {
        val t = x % y
        x = y
        y = t
    }
    return Math.abs(x)
}

// MARK: - random

/** SplitMix64: small, fast, and reproducible from a seed. */
private fun PyInterpreter.nextRandomBits(): Long {
    randomState += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    var z = randomState
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    return z xor (z ushr 31)
}

private fun PyInterpreter.nextUnit(): Double = (nextRandomBits() ushr 11).toDouble() * (1.0 / 9_007_199_254_740_992.0)

private fun PyInterpreter.randomInt(below: Long): Long {
    if (below <= 0) return 0
    return java.lang.Long.remainderUnsigned(nextRandomBits(), below)
}

private fun PyInterpreter.randomCall(name: String, args: List<PyValue>): PyValue {
    when (name) {
        "random" -> return float(nextUnit())
        "seed" -> {
            val v = args.firstOrNull()
            if (v == null) {
                var s = 0L
                while (s == 0L) s = java.util.Random().nextLong()
                randomState = s
                return PyValue.None
            }
            val i = asInt(v)
            randomState = if (i != null) i or 1L else Math.abs(pyStr(v).hashCode().toLong()) or 1L
            return PyValue.None
        }
        "uniform" -> {
            val a = args.firstOrNull()?.let { asDouble(it) }
            val b = args.getOrNull(1)?.let { asDouble(it) }
            if (a == null || b == null) throw error("random.uniform() needs two numbers", "TypeError")
            return float(a + (b - a) * nextUnit())
        }
        "randint" -> {
            val a = args.firstOrNull()?.let { asInt(it) }
            val b = args.getOrNull(1)?.let { asInt(it) }
            if (a == null || b == null || b < a) throw error("random.randint() needs a <= b", "ValueError")
            return int(a + randomInt(b - a + 1))
        }
        "randrange" -> {
            val values = args.mapNotNull { asInt(it) }
            if (values.isEmpty()) throw error("random.randrange() needs an integer", "TypeError")
            val start = if (values.size > 1) values[0] else 0L
            val stop = if (values.size > 1) values[1] else values[0]
            val step = if (values.size > 2) values[2] else 1L
            if (step == 0L || stop <= start) throw error("empty range for randrange()", "ValueError")
            val count = (stop - start + step - 1) / step
            return int(start + randomInt(count) * step)
        }
        "choice" -> {
            val items = iterate(args.firstOrNull() ?: list(emptyList()))
            if (items.isEmpty()) throw error("cannot choose from an empty sequence", "IndexError")
            return items[randomInt(items.size.toLong()).toInt()]
        }
        "choices" -> {
            val items = iterate(args.firstOrNull() ?: list(emptyList()))
            val k = if (args.size > 1) (asInt(args[1]) ?: 1L) else 1L
            if (items.isEmpty()) throw error("cannot choose from an empty sequence", "IndexError")
            if (k < 0 || k > maxContainer) throw abort("list too large")
            val out = ArrayList<PyValue>()
            for (n in 0 until k) { tick(); out.add(items[randomInt(items.size.toLong()).toInt()]) }
            return list(out)
        }
        "sample" -> {
            val items = ArrayList(iterate(args.firstOrNull() ?: list(emptyList())))
            val k = if (args.size > 1) (asInt(args[1]) ?: 0L) else 0L
            if (k < 0 || k > items.size) throw error("sample larger than population", "ValueError")
            val out = ArrayList<PyValue>()
            for (n in 0 until k) {
                tick()
                out.add(items.removeAt(randomInt(items.size.toLong()).toInt()))
            }
            return list(out)
        }
        "shuffle" -> {
            val l = (args.firstOrNull() as? PyValue.List)?.list ?: throw error("random.shuffle() needs a list", "TypeError")
            if (l.items.size <= 1) return PyValue.None
            for (i in l.items.size - 1 downTo 1) {
                tick()
                java.util.Collections.swap(l.items, i, randomInt((i + 1).toLong()).toInt())
            }
            return PyValue.None
        }
        else -> throw error("module 'random' has no attribute '$name'", "AttributeError")
    }
}

// MARK: - time

private fun PyInterpreter.timeCall(name: String, args: List<PyValue>): PyValue {
    fun dateArg(i: Int): Date = args.getOrNull(i)?.let { asDouble(it) }?.let { Date((it * 1000).toLong()) } ?: Date()
    return when (name) {
        "time" -> float(System.currentTimeMillis() / 1000.0)
        "monotonic" -> float(System.nanoTime() / 1e9)
        "parts" -> {
            val c = Calendar.getInstance()
            c.time = dateArg(0)
            val d = PyDict()
            d[PyHashable.Str("year")] = int(c.get(Calendar.YEAR))
            d[PyHashable.Str("month")] = int(c.get(Calendar.MONTH) + 1)
            d[PyHashable.Str("day")] = int(c.get(Calendar.DAY_OF_MONTH))
            d[PyHashable.Str("hour")] = int(c.get(Calendar.HOUR_OF_DAY))
            d[PyHashable.Str("minute")] = int(c.get(Calendar.MINUTE))
            d[PyHashable.Str("second")] = int(c.get(Calendar.SECOND))
            // Monday is 0, as in Python's `weekday()`; Calendar counts Sunday as 1.
            d[PyHashable.Str("weekday")] = int((c.get(Calendar.DAY_OF_WEEK) + 5) % 7)
            d[PyHashable.Str("yearday")] = int(c.get(Calendar.DAY_OF_YEAR))
            PyValue.Dict(d)
        }
        "format" -> {
            // A Unicode date pattern ("EEE d MMM yyyy"), not a C strftime string.
            val pattern = (args.firstOrNull() as? PyValue.Str)?.value
                ?: throw error("time.format() needs a pattern like \"yyyy-MM-dd\"", "TypeError")
            val formatter = try {
                SimpleDateFormat(pattern, Locale.getDefault())
            } catch (e: IllegalArgumentException) {
                throw error("invalid date pattern: ${e.message}", "ValueError")
            }
            str(formatter.format(if (args.size > 1) dateArg(1) else Date()))
        }
        else -> throw error("module 'time' has no attribute '$name'", "AttributeError")
    }
}

// MARK: - json

private fun PyInterpreter.jsonCall(name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    val charge = { tick() }
    return when (name) {
        "dumps" -> {
            val v = args.firstOrNull() ?: throw error("json.dumps() needs a value", "TypeError")
            val indentArg = if (args.size > 1) args[1] else kwargs.firstOrNull { it.first == "indent" }?.second
            val indent = indentArg?.let { asInt(it) }?.toInt()
            str(PyJson.encode(v, indent, maxStringLength, charge))
        }
        "loads" -> {
            val s = (args.firstOrNull() as? PyValue.Str)?.value ?: throw error("json.loads() needs a string", "TypeError")
            PyJson.decode(s, charge)
        }
        else -> throw error("module 'json' has no attribute '$name'", "AttributeError")
    }
}

// MARK: - re

private fun PyInterpreter.regexCall(name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
    if (name == "escape") {
        val s = (args.firstOrNull() as? PyValue.Str)?.value ?: throw error("re.escape() needs a string", "TypeError")
        return str(PyRegex.escape(s))
    }
    val pattern = (args.firstOrNull() as? PyValue.Str)?.value ?: throw error("re.$name() needs a pattern string", "TypeError")
    // `sub`/`subn` take (pattern, repl, string); everything else (pattern, string).
    val isSub = name == "sub" || name == "subn"
    val subjectIndex = if (isSub) 2 else 1
    val subjectText = (args.getOrNull(subjectIndex) as? PyValue.Str)?.value
        ?: throw error("re.$name() needs a string to search", "TypeError")
    val flagArgIndex = subjectIndex + (if (isSub || name == "split") 2 else 1)
    var flags = 0
    if (flagArgIndex < args.size) asInt(args[flagArgIndex])?.let { flags = it.toInt() }
    kwargs.firstOrNull { it.first == "flags" }?.second?.let { f -> asInt(f)?.let { flags = it.toInt() } }

    val regex = PyRegex.compile(pattern, flags)
    val subject = chars(subjectText)
    val charge = { tick() }

    fun matchValue(m: PyMatch?): PyValue = m?.let { PyValue.Match(it) } ?: PyValue.None
    fun join(from: Int, to: Int): String = subject.subList(from, to).joinToString("")

    return when (name) {
        "search" -> matchValue(regex.firstMatch(subject, 0, tick = charge))
        "match" -> matchValue(regex.firstMatch(subject, 0, anchored = true, tick = charge))
        "fullmatch" -> matchValue(regex.firstMatch(subject, 0, anchored = true, whole = true, tick = charge))
        "findall" ->
            // Python's shape: no groups → the whole match; one group → that group; several → a tuple.
            list(regex.allMatches(subject, tick = charge).map { m ->
                when (regex.groupCount) {
                    0 -> str(m.value)
                    1 -> str(m.text(1) ?: "")
                    else -> PyValue.Tuple((1..regex.groupCount).map { str(m.text(it) ?: "") })
                }
            })
        "finditer" -> list(regex.allMatches(subject, tick = charge).map { PyValue.Match(it) })
        "split" -> {
            val limit = if (args.size > 2) (asInt(args[2]) ?: 0L) else 0L
            val out = ArrayList<PyValue>()
            var at = 0
            for (m in regex.allMatches(subject, if (limit > 0) limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else Int.MAX_VALUE, charge)) {
                val span = m.spans[0] ?: continue
                if (span.start == span.end) continue
                out.add(str(join(at, span.start)))
                for (g in 1..regex.groupCount) out.add(m.text(g)?.let { str(it) } ?: PyValue.None)
                at = span.end
            }
            out.add(str(join(at, subject.size)))
            list(out)
        }
        "sub", "subn" -> {
            val replacement = args[1]
            val limit = if (args.size > 3) (asInt(args[3]) ?: 0L) else 0L
            val out = StringBuilder()
            var at = 0
            var count = 0L
            for (m in regex.allMatches(subject, if (limit > 0) limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else Int.MAX_VALUE, charge)) {
                val span = m.spans[0] ?: continue
                out.append(join(at, span.start))
                if (replacement is PyValue.Str) out.append(expandTemplate(replacement.value, m))
                else out.append(pyStr(invoke(replacement, listOf(PyValue.Match(m)))))
                at = span.end
                count += 1
                if (out.length > maxStringLength && graphemeCount(out.toString()) > maxStringLength) throw abort("string too large")
            }
            out.append(join(at, subject.size))
            if (name == "sub") str(out.toString()) else tuple(str(out.toString()), int(count))
        }
        else -> throw error("module 're' has no attribute '$name'", "AttributeError")
    }
}

/** `\1`, `\g<1>` and `\g<name>` in a replacement string. */
private fun expandTemplate(template: String, m: PyMatch): String {
    val out = StringBuilder()
    val chars = graphemes(template)
    var i = 0
    while (i < chars.size) {
        if (chars[i] != "\\" || i + 1 >= chars.size) { out.append(chars[i]); i += 1; continue }
        val next = chars[i + 1]
        if (charIsNumber(next)) {
            out.append(m.text(parseSwiftInt(next)?.toInt() ?: 0) ?: "")
            i += 2
            continue
        }
        if (next == "g" && i + 2 < chars.size && chars[i + 2] == "<") {
            val name = StringBuilder()
            var j = i + 3
            while (j < chars.size && chars[j] != ">") { name.append(chars[j]); j += 1 }
            if (j < chars.size) {
                val n = name.toString()
                val index = parseSwiftInt(n)?.toInt() ?: m.index(n) ?: -1
                out.append(m.text(index) ?: "")
                i = j + 1
                continue
            }
        }
        when (next) {
            "n" -> out.append("\n")
            "t" -> out.append("\t")
            "\\" -> out.append("\\")
            else -> out.append("\\").append(next)
        }
        i += 2
    }
    return out.toString()
}
