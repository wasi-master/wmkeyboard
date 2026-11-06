package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyRuntime.swift and PyVersion.swift: the public way in.
 *
 *     val py = PyRuntime()
 *     py.load("def transform(text):\n    return text.upper()")
 *     py.call("transform", "hi").stringValue   // "HI"
 *
 * PyMini 2.0.1 (https://github.com/anti-ltd/PyMini, MIT, Anti Limited), ported
 * to Kotlin. Globals live as long as the runtime, so functions defined by one
 * `load` can be called again and again. Every `load`, `run` and `call` starts
 * with a full step budget and empty output. Not thread safe: use a runtime
 * from one thread at a time.
 */

/**
 * How much a script may do before it's stopped. The container and string caps
 * are tighter than PyMini's own defaults (200,000 and 1,000,000), sized for a
 * keyboard action rather than a desktop script.
 */
data class PyLimits(
    /** Work allowed per `load`, `run` or `call`: every statement, expression, loop turn, regex step and sort comparison costs one. */
    val steps: Long = 2_000_000,
    /** The most items one list, set, dict, tuple or range may hold. */
    val containerItems: Int = 10_000,
    /** The longest string a script may build, in grapheme clusters. */
    val stringLength: Int = 100_000,
)

class PyRuntime(
    limits: PyLimits = PyLimits(),
    /**
     * Standard modules a script may import: by default all six, math, random,
     * time, json, re and sys. Leave out `time` and `random` when output has to
     * be reproducible; importing a left-out module raises a catchable
     * ImportError, PyMini's "no module named 'x' (available: …)".
     */
    modules: Set<String> = STANDARD_MODULES,
    /** Polled on every step; when true the run ends with an abort that `except` cannot catch. */
    cancelled: () -> Boolean = { false },
) {
    private val interpreter = PyInterpreter(limits, modules, cancelled)

    /** What `print` wrote during the latest `load`, `run` or `call`, one line per call, joined with newlines. */
    val output: String get() = interpreter.printed.joinToString("\n")

    /** The same output, line by line. */
    val outputLines: List<String> get() = interpreter.printed.toList()

    /** Gets each printed line as it's written, for hosts that stream output. */
    var onPrint: ((String) -> Unit)?
        get() = interpreter.onPrint
        set(value) { interpreter.onPrint = value }

    // MARK: - Giving scripts things

    /** Make a Kotlin function callable from scripts as a global; it shadows a builtin of the same name. */
    fun define(name: String, body: (PyCall) -> Any?) {
        interpreter.hostFunctions[name] = PyHostFunction.wrapping(name, body)
    }

    /** Build a module scripts can `import`. A module named like a standard one replaces it. */
    fun defineModule(name: String, build: (PyHostModule) -> Unit) {
        val module = PyHostModule(name)
        build(module)
        interpreter.hostModules[name] = module
    }

    /** Read a global. */
    operator fun get(name: String): PyValue? = interpreter.globals.get(name)

    /** Write a global; null removes it. Any value [PyValue.of] accepts. */
    operator fun set(name: String, value: Any?) {
        if (value == null) interpreter.globals.remove(name) else interpreter.globals.define(name, toPyValue(value))
    }

    // MARK: - Running scripts

    /** Run a module's top level: define its functions, set its globals. */
    fun load(source: String) {
        val program = PyParser.parse(source)
        perform { interpreter.run(program) }
    }

    /**
     * Run a chunk of code and return the value of its last line when that line
     * is an expression, the way an interactive prompt does. Otherwise `None`.
     */
    fun run(source: String): PyValue {
        val program = PyParser.parse(source)
        val last = program.lastOrNull()
        if (last !is Stmt.ExprStmt) {
            perform { interpreter.run(program) }
            return PyValue.None
        }
        return perform {
            interpreter.run(program.subList(0, program.size - 1))
            interpreter.currentLine = last.line
            interpreter.evaluate(last.expr)
        }
    }

    /** Call a global function. Arguments convert as [PyValue.of] describes. */
    fun call(name: String, vararg args: Any?): PyValue =
        perform { interpreter.call(name, args.map { toPyValue(it) }) }

    /** Call a global function with keyword arguments. */
    fun callWithKeywords(name: String, args: List<Any?>, keywords: Map<String, Any?>): PyValue = perform {
        interpreter.call(name, args.map { toPyValue(it) }, keywords.map { Pair(it.key, toPyValue(it.value)) })
    }

    /** Call a function value a script handed back, like a stored callback. */
    fun callValue(function: PyValue, vararg args: Any?): PyValue =
        perform { interpreter.invoke(function, args.map { toPyValue(it) }) }

    /** Call a hook the script may or may not have defined; null when it didn't. */
    fun callHook(name: String, vararg args: Any?): PyValue? {
        if (!hasFunction(name)) return null
        return perform { interpreter.call(name, args.map { toPyValue(it) }) }
    }

    /** Whether the script defined a function with this name. */
    fun hasFunction(name: String): Boolean = interpreter.hasFunction(name)

    /** A script function's parameter names, or null when there's no such function. */
    fun parameters(of: String): List<String>? = (interpreter.globals.get(of) as? PyValue.Function)?.function?.parameterNames

    /**
     * Fresh budget and output, then [body]. A `return`, `break` or `continue`
     * that escaped everything becomes a SyntaxError here. A JVM stack overflow
     * becomes an abort rather than a crash.
     */
    private fun <T> perform(body: () -> T): T {
        interpreter.resetBudget()
        interpreter.clearPrinted()
        try {
            return body()
        } catch (flow: PyFlow) {
            throw when (flow) {
                is PyFlow.ReturnValue -> PyError("'return' outside function", "SyntaxError", interpreter.currentLine)
                PyFlow.BreakLoop -> PyError("'break' outside loop", "SyntaxError", interpreter.currentLine)
                PyFlow.ContinueLoop -> PyError("'continue' not properly in loop", "SyntaxError", interpreter.currentLine)
            }
        } catch (e: StackOverflowError) {
            throw PyError.abort("maximum recursion depth exceeded", interpreter.currentLine)
        }
    }

    companion object {
        /** The PyMini version this port follows. */
        const val VERSION = "2.0.1"

        /** The version with the name in front, as `sys.version` holds it. */
        const val VERSION_LINE = "pymini $VERSION"

        /** math, random, time, json, re and sys. */
        val STANDARD_MODULES: Set<String> = PyModules.names

        /** Check that [source] parses, without running any of it. Throws PyError. */
        fun check(source: String) {
            PyParser.parse(source)
        }
    }
}
