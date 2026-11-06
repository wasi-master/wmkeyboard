package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyInterpreter.swift: the tree-walking evaluator.
 *
 * Sandbox guarantees: no file, network or system access (`import` reaches only
 * PyModules and host modules), a deterministic step budget over loops,
 * recursion and regular expressions alike, and size caps on containers and
 * strings. Those aborts are uncatchable. On top of PyMini this port also polls
 * a cancellation flag on every step and caps call depth, because a JVM thread
 * stack is smaller than what PyMini's unbounded recursion assumes.
 */

/** A lexical scope. Functions capture the one they were defined in. */
internal class PyEnv(val parent: PyEnv? = null) {
    val vars = HashMap<String, PyValue>()
    /** Names this scope declared `global`, so assigning them writes to the module. */
    val globalNames = HashSet<String>()

    val root: PyEnv get() = parent?.root ?: this

    fun get(name: String): PyValue? {
        if (globalNames.contains(name)) return root.vars[name]
        return vars[name] ?: parent?.get(name)
    }

    fun define(name: String, value: PyValue) {
        if (globalNames.contains(name)) { root.vars[name] = value; return }
        vars[name] = value
    }

    /** `del x`. Returns whether anything was bound. */
    fun remove(name: String): Boolean {
        if (globalNames.contains(name)) return root.vars.remove(name) != null
        if (vars.remove(name) != null) return true
        return parent?.remove(name) ?: false
    }
}

internal class PyInterpreter(
    limits: PyLimits = PyLimits(),
    modules: Set<String> = PyModules.names,
    private val cancelled: () -> Boolean = { false },
) {
    val globals = PyEnv()
    private val maxSteps: Long = limits.steps
    private var steps = 0L
    var currentLine = 0
    /** Everything `print(...)` wrote since the last `clearPrinted()`. */
    val printed = ArrayList<String>()
    /** Called for each printed line as it happens. */
    var onPrint: ((String) -> Unit)? = null
    /** The exception a bare `raise` re-raises while an `except` body runs. */
    private var activeException: PyExceptionValue? = null
    /** State behind the `random` namespace; `random.seed(n)` makes it repeatable. */
    var randomState: Long = randomSeed()

    /** The standard namespaces this interpreter lets a script import. */
    val enabledModules: Set<String> = modules.intersect(PyModules.names)
    /** Global functions the host defined. They shadow builtins of the same name. */
    val hostFunctions = HashMap<String, PyHostFunction>()
    /** Modules the host defined, importable like the standard ones. */
    val hostModules = HashMap<String, PyHostModule>()

    val maxContainer: Int = limits.containerItems
    val maxStringLength: Int = limits.stringLength

    private var callDepth = 0

    /** A one-entry cache: loops index the same string over and over. */
    private var clusterKey: String? = null
    private var clusterValue: List<String> = emptyList()

    /** The grapheme clusters of [s], which is what PyMini's `Array(s)` gives. */
    fun chars(s: String): List<String> {
        if (clusterKey === s) return clusterValue
        val c = graphemes(s)
        // Short strings are cheap to split, and caching them would evict the
        // long text a loop like `text[i - 1].isalpha()` keeps coming back to.
        if (s.length < 64) return c
        clusterKey = s
        clusterValue = c
        return c
    }

    // MARK: - Entry points

    /** Run a whole module: define its functions and execute its top-level code. */
    fun run(program: List<Stmt>) = execBlock(program, globals)

    /** Evaluate one expression against the module's globals. */
    fun evaluate(expr: Expr): PyValue = eval(expr, globals)

    fun hasFunction(name: String): Boolean = globals.get(name) is PyValue.Function

    /** Call a top-level function by name. */
    fun call(name: String, args: List<PyValue>, kwargs: List<Pair<String, PyValue>> = emptyList()): PyValue {
        val v = globals.get(name) ?: hostFunctions[name]?.let { PyValue.HostFunction(it) }
            ?: throw perr("name '$name' is not defined", kind = "NameError")
        return callValue(v, args, kwargs)
    }

    /** Call something already resolved: `sorted(key=...)`, `map`, `re.sub` with a replacer. */
    fun invoke(callee: PyValue, args: List<PyValue>): PyValue = callValue(callee, args, emptyList())

    fun resetBudget() {
        steps = 0
        callDepth = 0
    }

    fun clearPrinted() = printed.clear()

    fun emit(line: String) {
        printed.add(line)
        onPrint?.invoke(line)
    }

    // MARK: - Step budget

    fun tick() {
        steps += 1
        if (steps > maxSteps) {
            throw PyError.abort("script exceeded its step budget (possible infinite loop)", currentLine)
        }
        if (cancelled()) throw PyError.abort("script was cancelled", currentLine)
    }

    fun abort(message: String): PyError = PyError.abort(message, currentLine)

    fun error(message: String, kind: String = "RuntimeError"): PyError = PyError(message, kind, currentLine)

    // MARK: - Statements

    private fun execBlock(stmts: List<Stmt>, env: PyEnv) {
        for (s in stmts) exec(s, env)
    }

    private fun exec(stmt: Stmt, env: PyEnv) {
        tick()
        currentLine = stmt.line
        when (stmt) {
            is Stmt.ExprStmt -> eval(stmt.expr, env)

            is Stmt.Assign -> {
                val value = eval(stmt.value, env)
                for (t in stmt.targets) assign(t, value, env)
            }

            is Stmt.AugAssign -> {
                val current = eval(stmt.target, env)
                val rhs = eval(stmt.value, env)
                // `list += iterable` extends in place, as Python does.
                val items = sequenceItems(rhs)
                if (stmt.op == BinOp.ADD && current is PyValue.List && items != null) {
                    if (current.list.items.size + items.size > maxContainer) throw abort("list too large")
                    current.list.items.addAll(ArrayList(items))
                    return
                }
                assign(stmt.target, binaryOp(stmt.op, current, rhs), env)
            }

            is Stmt.If -> {
                for ((cond, body) in stmt.branches) {
                    if (eval(cond, env).isTruthy) { execBlock(body, env); return }
                }
                stmt.elseBody?.let { execBlock(it, env) }
            }

            is Stmt.While -> {
                var broke = false
                while (eval(stmt.cond, env).isTruthy) {
                    tick()
                    try {
                        execBlock(stmt.body, env)
                    } catch (f: PyFlow.BreakLoop) {
                        broke = true; break
                    } catch (f: PyFlow.ContinueLoop) {
                        continue
                    }
                }
                if (!broke) stmt.elseBody?.let { execBlock(it, env) }
            }

            is Stmt.For -> {
                val seq = iterate(eval(stmt.iterable, env))
                var broke = false
                for (item in seq) {
                    tick()
                    assign(stmt.target, item, env)
                    try {
                        execBlock(stmt.body, env)
                    } catch (f: PyFlow.BreakLoop) {
                        broke = true; break
                    } catch (f: PyFlow.ContinueLoop) {
                        continue
                    }
                }
                if (!broke) stmt.elseBody?.let { execBlock(it, env) }
            }

            is Stmt.FuncDef ->
                env.define(stmt.name, PyValue.Function(PyFunction(stmt.name, stmt.params, stmt.variadic, stmt.body, env)))

            is Stmt.Return -> throw PyFlow.ReturnValue(stmt.value?.let { eval(it, env) } ?: PyValue.None)
            is Stmt.Break -> throw PyFlow.BreakLoop
            is Stmt.Continue -> throw PyFlow.ContinueLoop
            is Stmt.Pass -> {}
            is Stmt.Try -> execTry(stmt, env)
            is Stmt.Raise -> execRaise(stmt.value, env)
            is Stmt.Import -> execImport(stmt.module, stmt.alias, stmt.names, env)
            is Stmt.Global -> env.globalNames.addAll(stmt.names)
            is Stmt.Del -> for (t in stmt.targets) execDelete(t, env)
        }
    }

    private fun execTry(stmt: Stmt.Try, env: PyEnv) {
        var pending: Throwable? = null
        try {
            execBlock(stmt.body, env)
            stmt.elseBody?.let { execBlock(it, env) }
        } catch (error: PyError) {
            if (error.isAbort) {
                pending = error
            } else {
                val handler = stmt.handlers.firstOrNull { h ->
                    h.types.isEmpty() || h.types.any { PyExceptionKind.matches(it, error.kind) }
                }
                if (handler != null) {
                    handler.binding?.let { env.define(it, PyValue.Exception(error.exceptionValue)) }
                    val saved = activeException
                    activeException = error.exceptionValue
                    try {
                        execBlock(handler.body, env)
                    } catch (e: PyError) {
                        pending = e
                    } catch (e: PyFlow) {
                        pending = e
                    }
                    activeException = saved
                } else {
                    pending = error
                }
            }
        } catch (flow: PyFlow) {
            // return, break and continue pass straight through, but `finally` runs first.
            pending = flow
        }
        stmt.finallyBody?.let { execBlock(it, env) }
        pending?.let { throw it }
    }

    private fun execRaise(e: Expr?, env: PyEnv) {
        if (e == null) {
            val active = activeException ?: throw error("no active exception to re-raise")
            throw PyError.of(active, currentLine)
        }
        when (val v = eval(e, env)) {
            is PyValue.Exception -> throw PyError.of(v.exception, currentLine)
            is PyValue.Str -> throw PyError.of(PyExceptionValue("Exception", v.value), currentLine)
            else -> throw error("exceptions must be an exception type, not '${v.typeName}'", "TypeError")
        }
    }

    /** Every module name a script could import right now. */
    val importableModules: List<String> get() = (enabledModules + hostModules.keys).sorted()

    private fun execImport(module: String, alias: String?, names: List<Pair<String, String?>>?, env: PyEnv) {
        if (!enabledModules.contains(module) && hostModules[module] == null) {
            throw error("no module named '$module' (available: " + importableModules.joinToString(", ") + ")", "ImportError")
        }
        if (names == null) {
            env.define(alias ?: module, PyValue.Module(module))
            return
        }
        for ((name, rename) in names) {
            val value = member(module, name) ?: throw error("cannot import name '$name' from '$module'", "ImportError")
            env.define(rename ?: name, value)
        }
    }

    /** `module.name`, looking at the host's modules before the standard ones. */
    fun member(module: String, name: String): PyValue? {
        hostModules[module]?.let { return it.member(name) }
        return PyModules.member(module, name)
    }

    private fun execDelete(target: Expr, env: PyEnv) {
        when (target) {
            is Expr.Name -> if (!env.remove(target.name)) throw error("name '${target.name}' is not defined", "NameError")
            is Expr.Index -> {
                val base = eval(target.base, env)
                val idx = eval(target.index, env)
                when (base) {
                    is PyValue.List -> base.list.items.removeAt(listIndex(idx, base.list.items.size))
                    is PyValue.Dict -> {
                        val key = PyHashable.from(idx) ?: throw error("unhashable type: '${idx.typeName}'", "TypeError")
                        if (base.dict[key] == null) throw error(pyRepr(idx), "KeyError")
                        base.dict[key] = null
                    }
                    else -> throw error("cannot delete from '${base.typeName}'", "TypeError")
                }
            }
            else -> throw error("cannot delete this expression", "SyntaxError")
        }
    }

    // MARK: - Assignment

    private fun assign(target: Expr, value: PyValue, env: PyEnv) {
        when (target) {
            is Expr.Name -> env.define(target.name, value)
            is Expr.TupleLit -> {
                val parts = iterate(value)
                if (parts.size != target.items.size) {
                    throw error("cannot unpack ${parts.size} values into ${target.items.size} targets", "ValueError")
                }
                for (i in parts.indices) assign(target.items[i], parts[i], env)
            }
            is Expr.Index -> {
                val base = eval(target.base, env)
                val idx = eval(target.index, env)
                when (base) {
                    is PyValue.List -> base.list.items[listIndex(idx, base.list.items.size)] = value
                    is PyValue.Dict -> {
                        val key = PyHashable.from(idx) ?: throw error("unhashable type: '${idx.typeName}'", "TypeError")
                        base.dict[key] = value
                    }
                    else -> throw error("'${base.typeName}' object does not support item assignment", "TypeError")
                }
            }
            else -> throw error("cannot assign to this expression", "SyntaxError")
        }
    }

    // MARK: - Expressions

    fun eval(expr: Expr, env: PyEnv): PyValue {
        tick()
        return when (expr) {
            Expr.NoneLit -> PyValue.None
            is Expr.BoolLit -> PyValue.Bool(expr.value)
            is Expr.IntLit -> PyValue.Int(expr.value)
            is Expr.DoubleLit -> PyValue.Float(expr.value)
            is Expr.StringLit -> PyValue.Str(expr.value)
            is Expr.FString -> PyValue.Str(interpolate(expr.parts, env))

            is Expr.Name -> env.get(expr.name)
                ?: hostFunctions[expr.name]?.let { PyValue.HostFunction(it) }
                ?: if (builtinNames.contains(expr.name)) PyValue.Builtin(expr.name)
                else throw error("name '${expr.name}' is not defined", "NameError")

            is Expr.ListLit -> PyValue.List(PyList(expr.items.map { eval(it, env) }))

            is Expr.DictLit -> {
                val d = PyDict()
                for ((k, v) in expr.pairs) {
                    val key = eval(k, env)
                    val hk = PyHashable.from(key) ?: throw error("unhashable type: '${key.typeName}'", "TypeError")
                    d[hk] = eval(v, env)
                }
                PyValue.Dict(d)
            }

            is Expr.SetLit -> {
                val s = PySet()
                for (item in expr.items) {
                    val v = eval(item, env)
                    s.insert(PyHashable.from(v) ?: throw error("unhashable type: '${v.typeName}'", "TypeError"))
                }
                PyValue.Set(s)
            }

            is Expr.TupleLit -> PyValue.Tuple(expr.items.map { eval(it, env) })
            is Expr.Unary -> unaryOp(expr.op, eval(expr.operand, env))
            is Expr.Binary -> {
                val l = eval(expr.left, env)
                binaryOp(expr.op, l, eval(expr.right, env))
            }

            is Expr.BoolOp -> {
                val lv = eval(expr.left, env)
                when (expr.kind) {
                    BoolKind.AND -> if (lv.isTruthy) eval(expr.right, env) else lv
                    BoolKind.OR -> if (lv.isTruthy) lv else eval(expr.right, env)
                }
            }

            is Expr.Compare -> {
                var left = eval(expr.left, env)
                var result: PyValue = PyValue.Bool(true)
                for ((op, rExpr) in expr.pairs) {
                    val right = eval(rExpr, env)
                    if (!compareOp(op, left, right)) { result = PyValue.Bool(false); break }
                    left = right
                }
                result
            }

            is Expr.Ternary -> if (eval(expr.cond, env).isTruthy) eval(expr.then, env) else eval(expr.orElse, env)
            is Expr.Index -> {
                val base = eval(expr.base, env)
                index(base, eval(expr.index, env))
            }

            is Expr.Slice -> {
                val base = eval(expr.base, env)
                val lower = expr.lower?.let { eval(it, env) }
                val upper = expr.upper?.let { eval(it, env) }
                val step = expr.step?.let { eval(it, env) }
                slice(base, lower, upper, step)
            }

            is Expr.Attribute -> attribute(eval(expr.obj, env), expr.name)

            is Expr.Lambda -> PyValue.Function(
                PyFunction("<lambda>", expr.params, expr.variadic, listOf(Stmt.Return(expr.body, currentLine)), env),
            )

            is Expr.Comprehension -> runComprehension(expr, env)

            is Expr.Starred -> throw error("'*' can only be used in a call's arguments", "SyntaxError")

            is Expr.Call -> {
                val args = ArrayList<PyValue>()
                for (a in expr.args) {
                    if (a is Expr.Starred) args.addAll(iterate(eval(a.inner, env))) else args.add(eval(a, env))
                }
                val kwargs = expr.kwargs.map { Pair(it.first, eval(it.second, env)) }
                // `obj.method(...)` dispatches directly, with no bound-method value in between.
                val callee = expr.callee
                if (callee is Expr.Attribute) {
                    callMethod(eval(callee.obj, env), callee.name, args, kwargs)
                } else {
                    callValue(eval(callee, env), args, kwargs)
                }
            }
        }
    }

    /** Build an f-string: convert (`!r`), then format with a spec that can interpolate too. */
    private fun interpolate(parts: List<FStringPart>, env: PyEnv): String {
        val out = StringBuilder()
        for (p in parts) {
            when (p) {
                is FStringPart.Literal -> out.append(p.text)
                is FStringPart.Expression -> {
                    val value = eval(p.expr, env)
                    val converted = if (p.conversion == FConversion.REPR) PyValue.Str(pyRepr(value)) else value
                    if (p.spec != null) out.append(pyFormat(converted, interpolate(p.spec, env)))
                    else out.append(pyStr(converted))
                }
            }
            if (out.length > maxStringLength && graphemeCount(out.toString()) > maxStringLength) throw abort("string too large")
        }
        return out.toString()
    }

    /** Comprehensions get their own scope, so the loop variable doesn't leak (the Python 3 rule). */
    private fun runComprehension(c: Expr.Comprehension, env: PyEnv): PyValue {
        val scope = PyEnv(env)
        val list = ArrayList<PyValue>()
        val dict = PyDict()
        val set = PySet()

        fun step(i: Int) {
            tick()
            if (i >= c.clauses.size) {
                when (c.kind) {
                    ComprehensionKind.LIST -> {
                        list.add(eval(c.element, scope))
                        if (list.size > maxContainer) throw abort("list too large")
                    }
                    ComprehensionKind.SET -> {
                        val v = eval(c.element, scope)
                        set.insert(PyHashable.from(v) ?: throw error("unhashable type: '${v.typeName}'", "TypeError"))
                        if (set.count > maxContainer) throw abort("set too large")
                    }
                    ComprehensionKind.DICT -> {
                        val k = eval(c.element, scope)
                        val h = PyHashable.from(k) ?: throw error("unhashable type: '${k.typeName}'", "TypeError")
                        dict[h] = eval(c.value ?: c.element, scope)
                        if (dict.count > maxContainer) throw abort("dict too large")
                    }
                }
                return
            }
            val clause = c.clauses[i]
            for (item in iterate(eval(clause.iterable, scope))) {
                tick()
                assign(clause.target, item, scope)
                var keep = true
                for (cond in clause.conditions) {
                    if (!keep) break
                    keep = eval(cond, scope).isTruthy
                }
                if (keep) step(i + 1)
            }
        }
        step(0)

        return when (c.kind) {
            ComprehensionKind.LIST -> PyValue.List(PyList(list))
            ComprehensionKind.SET -> PyValue.Set(set)
            ComprehensionKind.DICT -> PyValue.Dict(dict)
        }
    }

    // MARK: - Attributes

    /** Attribute access outside a call: module members, and the few data attributes exceptions and matches have. */
    fun attribute(obj: PyValue, name: String): PyValue = when (obj) {
        is PyValue.Module -> member(obj.name, name)
            ?: throw error("module '${obj.name}' has no attribute '$name'", "AttributeError")
        is PyValue.Exception -> when (name) {
            "args" -> PyValue.Tuple(listOf(PyValue.Str(obj.exception.message)))
            "message" -> PyValue.Str(obj.exception.message)
            else -> throw error("exception has no attribute '$name'", "AttributeError")
        }
        is PyValue.Match -> when (name) {
            "string" -> PyValue.Str(obj.match.subject.joinToString(""))
            "lastindex" -> PyValue.Int(maxOf(0, obj.match.spans.size - 1).toLong())
            else -> throw error("Match has no attribute '$name'", "AttributeError")
        }
        else -> throw error("'${obj.typeName}' object has no attribute '$name'", "AttributeError")
    }

    // MARK: - Calls

    fun callValue(callee: PyValue, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue = when (callee) {
        is PyValue.Function -> callFunction(callee.function, args, kwargs)
        is PyValue.Builtin -> {
            if (kwargs.isNotEmpty() && !kwargBuiltins.contains(callee.name)) {
                throw error("${callee.name}() takes no keyword arguments", "TypeError")
            }
            callBuiltin(callee.name, args, kwargs)
        }
        is PyValue.ModuleFunction -> callModule(callee.module, callee.name, args, kwargs)
        is PyValue.HostFunction -> callHost(callee.function, args, kwargs)
        else -> throw error("'${callee.typeName}' object is not callable", "TypeError")
    }

    /** Hand a call to Kotlin. A PyError keeps its kind; anything else becomes a RuntimeError. */
    fun callHost(f: PyHostFunction, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
        tick()
        val line = currentLine
        val call = PyCall(f.name, args, kwargs, line, this)
        try {
            val result = f.body(call)
            currentLine = line
            return result
        } catch (e: PyError) {
            throw if (e.line == null) e.withLine(line) else e
        } catch (e: PyFlow) {
            throw e
        } catch (e: StackOverflowError) {
            throw e
        } catch (e: Exception) {
            throw PyError(e.message ?: e.toString(), "RuntimeError", line)
        }
    }

    private fun callFunction(f: PyFunction, args: List<PyValue>, kwargs: List<Pair<String, PyValue>>): PyValue {
        val env = PyEnv(f.closure)
        val bound = HashSet<String>()
        if (f.variadic == null && args.size > f.params.size) {
            throw error("${f.name}() takes ${f.params.size} arguments but ${args.size} were given", "TypeError")
        }
        for (i in args.indices) {
            if (i >= f.params.size) break
            env.define(f.params[i].name, args[i])
            bound.add(f.params[i].name)
        }
        f.variadic?.let {
            env.define(it, PyValue.Tuple(if (args.size > f.params.size) args.subList(f.params.size, args.size).toList() else emptyList()))
        }
        val names = f.params.map { it.name }.toSet()
        for ((k, v) in kwargs) {
            if (!names.contains(k)) throw error("${f.name}() got an unexpected keyword argument '$k'", "TypeError")
            env.define(k, v)
            bound.add(k)
        }
        for (p in f.params) {
            if (bound.contains(p.name)) continue
            val def = p.defaultValue ?: throw error("${f.name}() missing required argument '${p.name}'", "TypeError")
            env.define(p.name, eval(def, f.closure))
        }
        if (callDepth >= MAX_CALL_DEPTH) throw abort("maximum recursion depth exceeded")
        callDepth += 1
        try {
            execBlock(f.body, env)
        } catch (r: PyFlow.ReturnValue) {
            return r.value
        } finally {
            callDepth -= 1
        }
        return PyValue.None
    }

    // MARK: - Operators

    private fun unaryOp(op: UnOp, v: PyValue): PyValue = when (op) {
        UnOp.NOT -> PyValue.Bool(!v.isTruthy)
        UnOp.POS -> if (asInt(v) != null || asDouble(v) != null) v
        else throw error("bad operand type for unary +: '${v.typeName}'", "TypeError")
        UnOp.NEG -> when (v) {
            is PyValue.Int -> PyValue.Int(-v.value)
            is PyValue.Bool -> PyValue.Int(if (v.value) -1 else 0)
            is PyValue.Float -> PyValue.Float(-v.value)
            else -> throw error("bad operand type for unary -: '${v.typeName}'", "TypeError")
        }
    }

    fun binaryOp(op: BinOp, a: PyValue, b: PyValue): PyValue {
        // Sequences and sets first, then numbers.
        if (op == BinOp.ADD) {
            if (a is PyValue.Str && b is PyValue.Str) {
                val r = a.value + b.value
                if (longerThan(r, maxStringLength)) throw abort("string too large")
                return PyValue.Str(r)
            }
            if (a is PyValue.List && b is PyValue.List) {
                if (a.list.items.size + b.list.items.size > maxContainer) throw abort("list too large")
                return PyValue.List(PyList(a.list.items + b.list.items))
            }
            if (a is PyValue.Tuple && b is PyValue.Tuple) return PyValue.Tuple(a.items + b.items)
        }
        if (op == BinOp.MUL) {
            (repeatSeq(a, b) ?: repeatSeq(b, a))?.let { return it }
        }
        if (a is PyValue.Set && b is PyValue.Set) setAlgebra(op, a.set, b.set)?.let { return it }
        if (op == BinOp.SUB && a is PyValue.Set) {
            throw error("unsupported operand type(s) for -: 'set' and '${b.typeName}'", "TypeError")
        }
        if (op == BinOp.MOD && a is PyValue.Str) return PyValue.Str(printfStyle(a.value, b))

        if (op == BinOp.BIT_AND || op == BinOp.BIT_OR || op == BinOp.BIT_XOR) {
            val x = asInt(a)
            val y = asInt(b)
            if (x == null || y == null) {
                throw error("unsupported operand type(s) for ${opSymbol(op)}: '${a.typeName}' and '${b.typeName}'", "TypeError")
            }
            return PyValue.Int(
                when (op) {
                    BinOp.BIT_AND -> x and y
                    BinOp.BIT_OR -> x or y
                    else -> x xor y
                },
            )
        }

        val x = asDouble(a)
        val y = asDouble(b)
        if (x == null || y == null) {
            throw error("unsupported operand type(s) for ${opSymbol(op)}: '${a.typeName}' and '${b.typeName}'", "TypeError")
        }
        val bothInt = asInt(a) != null && asInt(b) != null
        return when (op) {
            BinOp.ADD -> numeric(x + y, bothInt)
            BinOp.SUB -> numeric(x - y, bothInt)
            BinOp.MUL -> numeric(x * y, bothInt)
            BinOp.DIV -> {
                if (y == 0.0) throw error("division by zero", "ZeroDivisionError")
                PyValue.Float(x / y)
            }
            BinOp.FLOORDIV -> {
                if (y == 0.0) throw error("integer division or modulo by zero", "ZeroDivisionError")
                numeric(Math.floor(x / y), bothInt)
            }
            BinOp.MOD -> {
                if (y == 0.0) throw error("integer division or modulo by zero", "ZeroDivisionError")
                // Python's modulo takes the sign of the divisor.
                numeric(x - Math.floor(x / y) * y, bothInt)
            }
            BinOp.POW -> numeric(Math.pow(x, y), bothInt && y >= 0)
            BinOp.BIT_AND, BinOp.BIT_OR, BinOp.BIT_XOR -> throw error("unsupported operand type(s) for ${opSymbol(op)}", "TypeError")
        }
    }

    /** `|` union, `&` intersection, `-` difference, `^` symmetric difference. */
    private fun setAlgebra(op: BinOp, a: PySet, b: PySet): PyValue? {
        val out = PySet()
        when (op) {
            BinOp.BIT_OR -> { out.members.addAll(a.members); out.members.addAll(b.members) }
            BinOp.BIT_AND -> for (v in a.members) if (b.contains(v)) out.insert(v)
            BinOp.SUB -> for (v in a.members) if (!b.contains(v)) out.insert(v)
            BinOp.BIT_XOR -> {
                for (v in a.members) if (!b.contains(v)) out.insert(v)
                for (v in b.members) if (!a.contains(v)) out.insert(v)
            }
            else -> return null
        }
        return PyValue.Set(out)
    }

    /** `"%s scored %d" % (name, score)`. */
    private fun printfStyle(format: String, rhs: PyValue): String {
        val args = if (rhs is PyValue.Tuple) rhs.items else listOf(rhs)
        val out = StringBuilder()
        var next = 0
        val chars = chars(format)
        var i = 0
        while (i < chars.size) {
            if (chars[i] != "%") { out.append(chars[i]); i += 1; continue }
            i += 1
            if (i >= chars.size) throw error("incomplete format", "ValueError")
            if (chars[i] == "%") { out.append("%"); i += 1; continue }
            val spec = StringBuilder()
            while (i < chars.size && !PRINTF_TYPES.contains(chars[i])) { spec.append(chars[i]); i += 1 }
            if (i >= chars.size) throw error("incomplete format", "ValueError")
            val type = chars[i]
            i += 1
            if (next >= args.size) throw error("not enough arguments for format string", "TypeError")
            val value = args[next]
            next += 1
            out.append(pyFormat(value, spec.toString() + (if (type == "i") "d" else type)))
        }
        return out.toString()
    }

    /** `seq * n` for str, list and tuple when the other side is an int or bool. */
    private fun repeatSeq(seq: PyValue, n: PyValue): PyValue? {
        val count = asInt(n) ?: return null
        val times = maxOf(0L, count)
        return when (seq) {
            is PyValue.Str -> {
                if (seq.value.isEmpty() || times == 0L) return PyValue.Str("")
                if (graphemeCount(seq.value).toLong() * times > maxStringLength) throw abort("string too large")
                PyValue.Str(seq.value.repeat(times.toInt()))
            }
            is PyValue.List -> {
                if (seq.list.items.isEmpty()) return PyValue.List(PyList())
                if (seq.list.items.size.toLong() * times > maxContainer) throw abort("list too large")
                val out = ArrayList<PyValue>()
                repeat(times.toInt()) { out.addAll(seq.list.items) }
                PyValue.List(PyList(out))
            }
            is PyValue.Tuple -> {
                if (seq.items.isEmpty()) return PyValue.Tuple(emptyList())
                if (seq.items.size.toLong() * times > maxContainer) throw abort("tuple too large")
                val out = ArrayList<PyValue>()
                repeat(times.toInt()) { out.addAll(seq.items) }
                PyValue.Tuple(out)
            }
            else -> null
        }
    }

    private fun isSequence(v: PyValue): Boolean = v is PyValue.Str || v is PyValue.List || v is PyValue.Tuple

    private fun numeric(d: Double, asInt: Boolean): PyValue {
        if (asInt && d == Math.rint(d) && Math.abs(d) < 9.007e15) return PyValue.Int(d.toLong())
        return PyValue.Float(d)
    }

    private fun opSymbol(op: BinOp): String = when (op) {
        BinOp.ADD -> "+"
        BinOp.SUB -> "-"
        BinOp.MUL -> "*"
        BinOp.DIV -> "/"
        BinOp.FLOORDIV -> "//"
        BinOp.MOD -> "%"
        BinOp.POW -> "**"
        BinOp.BIT_AND -> "&"
        BinOp.BIT_OR -> "|"
        BinOp.BIT_XOR -> "^"
    }

    private fun compareOp(op: CmpOp, a: PyValue, b: PyValue): Boolean = when (op) {
        CmpOp.EQ -> pyEquals(a, b)
        CmpOp.NE -> !pyEquals(a, b)
        CmpOp.IS -> pyIdentical(a, b)
        CmpOp.IS_NOT -> !pyIdentical(a, b)
        CmpOp.IN -> contains(b, a)
        CmpOp.NOT_IN -> !contains(b, a)
        CmpOp.LT -> order(a, b) < 0
        CmpOp.LE -> order(a, b) <= 0
        CmpOp.GT -> order(a, b) > 0
        CmpOp.GE -> order(a, b) >= 0
    }

    /** Three-way ordering for numbers, strings and sequences (lexicographic). */
    fun order(a: PyValue, b: PyValue): Int {
        val x = asDouble(a)
        val y = asDouble(b)
        if (x != null && y != null) return if (x < y) -1 else if (x > y) 1 else 0
        if (a is PyValue.Str && b is PyValue.Str) return Integer.signum(strCompare(a.value, b.value))
        val xs = sequenceItems(a)
        val ys = sequenceItems(b)
        if (xs != null && ys != null && isSequence(a) && isSequence(b)) {
            for (i in 0 until minOf(xs.size, ys.size)) {
                val c = order(xs[i], ys[i])
                if (c != 0) return c
            }
            return if (xs.size < ys.size) -1 else if (xs.size > ys.size) 1 else 0
        }
        throw error("'<' not supported between '${a.typeName}' and '${b.typeName}'", "TypeError")
    }

    private fun contains(container: PyValue, item: PyValue): Boolean = when (container) {
        is PyValue.Str -> {
            if (item !is PyValue.Str) throw error("'in <string>' requires string as left operand", "TypeError")
            item.value.isEmpty() || container.value.contains(item.value)
        }
        is PyValue.List -> container.list.items.any { pyEquals(it, item) }
        is PyValue.Tuple -> container.items.any { pyEquals(it, item) }
        is PyValue.Set -> PyHashable.from(item)?.let { container.set.contains(it) } ?: false
        is PyValue.Dict -> PyHashable.from(item)?.let { container.dict[it] != null } ?: false
        else -> throw error("argument of type '${container.typeName}' is not iterable", "TypeError")
    }

    // MARK: - Indexing, slicing, iteration

    fun iterate(value: PyValue): List<PyValue> = when (value) {
        is PyValue.Str -> chars(value.value).map { PyValue.Str(it) }
        is PyValue.List -> ArrayList(value.list.items)
        is PyValue.Tuple -> value.items
        is PyValue.Set -> value.set.values
        is PyValue.Dict -> value.dict.keys.map { it.value }
        else -> throw error("'${value.typeName}' object is not iterable", "TypeError")
    }

    fun listIndex(idx: PyValue, count: Int): Int {
        val raw = asInt(idx) ?: throw error("indices must be integers", "TypeError")
        val i = if (raw < 0) raw + count else raw
        if (i < 0 || i >= count) throw error("index out of range", "IndexError")
        return i.toInt()
    }

    private fun index(base: PyValue, idx: PyValue): PyValue = when (base) {
        is PyValue.Str -> {
            val arr = chars(base.value)
            PyValue.Str(arr[listIndex(idx, arr.size)])
        }
        is PyValue.List -> base.list.items[listIndex(idx, base.list.items.size)]
        is PyValue.Tuple -> base.items[listIndex(idx, base.items.size)]
        is PyValue.Dict -> {
            val key = PyHashable.from(idx) ?: throw error("unhashable type: '${idx.typeName}'", "TypeError")
            base.dict[key] ?: throw error(pyRepr(idx), "KeyError")
        }
        is PyValue.Match -> {
            val i = asInt(idx)
            val text = if (i != null && i >= Int.MIN_VALUE && i <= Int.MAX_VALUE) base.match.text(i.toInt()) else null
            PyValue.Str(text ?: throw error("no such group", "IndexError"))
        }
        else -> throw error("'${base.typeName}' object is not subscriptable", "TypeError")
    }

    private fun slice(base: PyValue, lo: PyValue?, hi: PyValue?, st: PyValue?): PyValue {
        val step = st?.let { asInt(it) } ?: 1L
        if (step == 0L) throw error("slice step cannot be zero", "ValueError")
        fun <T> sliceArray(arr: List<T>): List<T> {
            val n = arr.size.toLong()
            val start: Long
            val stop: Long
            val l = lo?.let { asInt(it) }
            val h = hi?.let { asInt(it) }
            if (step > 0) {
                start = l?.let { if (it < 0) maxOf(0, it + n) else minOf(it, n) } ?: 0
                stop = h?.let { if (it < 0) maxOf(0, it + n) else minOf(it, n) } ?: n
            } else {
                start = l?.let { if (it < 0) it + n else minOf(it, n - 1) } ?: (n - 1)
                stop = h?.let { if (it < 0) it + n else it } ?: -1
            }
            val out = ArrayList<T>()
            var i = start
            if (step > 0) {
                while (i < stop) { if (i >= 0 && i < n) out.add(arr[i.toInt()]); i += step }
            } else {
                while (i > stop) { if (i >= 0 && i < n) out.add(arr[i.toInt()]); i += step }
            }
            return out
        }
        return when (base) {
            is PyValue.Str -> PyValue.Str(sliceArray(chars(base.value)).joinToString(""))
            is PyValue.List -> PyValue.List(PyList(sliceArray(base.list.items)))
            is PyValue.Tuple -> PyValue.Tuple(sliceArray(base.items))
            else -> throw error("'${base.typeName}' object is not subscriptable", "TypeError")
        }
    }

    companion object {
        /** Deeper than this and a JVM thread stack is at risk; PyMini has no limit. */
        const val MAX_CALL_DEPTH = 300

        private val PRINTF_TYPES = "sdifgeExXoc".map { it.toString() }.toSet()

        private fun randomSeed(): Long {
            var s = 0L
            while (s == 0L) s = java.util.Random().nextLong()
            return s
        }
    }
}
