package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyParser.swift: a recursive-descent parser from tokens to statements.
 * Python features PyMini doesn't have (class, with, yield, decorators, …) fail
 * with a message that says so, not a confusing syntax error.
 */
internal class PyParser(private val tokens: List<Token>) {
    private var i = 0

    companion object {
        /** Parse a complete module. */
        fun parse(source: String): List<Stmt> = PyParser(PyLexer(source).tokenize()).parseProgram()

        /** Parse a standalone expression (f-string placeholders). */
        fun parseExpression(source: String): Expr {
            val parser = PyParser(PyLexer(source).tokenize())
            parser.skipNewlines()
            return parser.parseExprOrTupleValue()
        }

        private val unsupported = setOf("class", "with", "nonlocal", "assert", "yield", "async", "await", "match")

        private val augOps = mapOf(
            "+=" to BinOp.ADD, "-=" to BinOp.SUB, "*=" to BinOp.MUL, "/=" to BinOp.DIV,
            "%=" to BinOp.MOD, "//=" to BinOp.FLOORDIV, "**=" to BinOp.POW,
        )

        private fun stringParts(parts: List<FRawPart>): List<FStringPart> = parts.map { part ->
            when (part) {
                is FRawPart.Lit -> FStringPart.Literal(part.text)
                is FRawPart.Expr -> {
                    val e = parseExpression(part.source)
                    val conversion = when (part.conv) {
                        "r", "a" -> FConversion.REPR
                        "s" -> FConversion.STR
                        else -> null
                    }
                    FStringPart.Expression(e, conversion, part.spec?.let { stringParts(it) })
                }
            }
        }
    }

    // MARK: - Token cursor

    private val kind: Tok get() = tokens[i].kind
    private val lineNo: Int get() = tokens[i].line
    private fun peekKind(n: Int): Tok = if (i + n < tokens.size) tokens[i + n].kind else Tok.Eof

    private fun advance(): Token {
        val t = tokens[i]
        if (i < tokens.size - 1) i += 1
        return t
    }

    private fun isOp(s: String): Boolean = kind == Tok.Op(s)
    private fun isKeyword(s: String): Boolean = kind == Tok.Keyword(s)
    private val isEOF: Boolean get() = kind == Tok.Eof

    private fun matchOp(s: String): Boolean {
        if (isOp(s)) { advance(); return true }
        return false
    }

    private fun matchKeyword(s: String): Boolean {
        if (isKeyword(s)) { advance(); return true }
        return false
    }

    private fun expectOp(s: String) {
        if (!matchOp(s)) throw perr("expected '$s'", lineNo)
    }

    private fun expectName(what: String): String {
        val k = kind as? Tok.Name ?: throw perr("expected $what", lineNo)
        advance()
        return k.name
    }

    private fun skipNewlines() {
        while (kind == Tok.Newline) advance()
    }

    private fun atStatementEnd(): Boolean = kind == Tok.Newline || kind == Tok.Eof || kind == Tok.Dedent

    private fun expectStatementEnd() {
        if (isOp(";")) {
            advance()
            if (kind == Tok.Newline) advance()
            return
        }
        if (atStatementEnd()) {
            if (kind == Tok.Newline) advance()
            return
        }
        throw perr("unexpected token after statement", lineNo)
    }

    // MARK: - Program and blocks

    fun parseProgram(): List<Stmt> {
        val stmts = ArrayList<Stmt>()
        skipNewlines()
        while (!isEOF) {
            stmts.addAll(parseStatementLine())
            skipNewlines()
        }
        return stmts
    }

    /** One source line, which may hold several `;`-separated simple statements. */
    private fun parseStatementLine(): List<Stmt> {
        val out = arrayListOf(parseStatement())
        while (isOp(";")) {
            advance()
            if (atStatementEnd()) break
            out.add(parseStatement())
        }
        return out
    }

    private fun parseBlock(): List<Stmt> {
        expectOp(":")
        if (kind == Tok.Newline) {
            advance()
            if (kind != Tok.Indent) throw perr("expected an indented block", lineNo)
            advance()
            val stmts = ArrayList<Stmt>()
            skipNewlines()
            while (kind != Tok.Dedent && !isEOF) {
                stmts.addAll(parseStatementLine())
                skipNewlines()
            }
            if (kind == Tok.Dedent) advance()
            if (stmts.isEmpty()) throw perr("expected an indented block", lineNo)
            return stmts
        }
        // Inline block, `if x: return 1`.
        return parseStatementLine()
    }

    /** An `else:` block, when the next line opens one. */
    private fun parseOptionalElse(): List<Stmt>? {
        skipNewlines()
        if (!isKeyword("else")) return null
        advance()
        return parseBlock()
    }

    private fun parseStatement(): Stmt {
        val k = kind
        if (k is Tok.Keyword) {
            when (k.word) {
                "if" -> return parseIf()
                "while" -> return parseWhile()
                "for" -> return parseFor()
                "def" -> return parseDef()
                "return" -> return parseReturn()
                "try" -> return parseTry()
                "raise" -> return parseRaise()
                "import" -> return parseImport()
                "from" -> return parseFromImport()
                "global" -> return parseGlobal()
                "del" -> return parseDel()
                "break" -> { val l = lineNo; advance(); expectStatementEnd(); return Stmt.Break(l) }
                "continue" -> { val l = lineNo; advance(); expectStatementEnd(); return Stmt.Continue(l) }
                "pass" -> { val l = lineNo; advance(); expectStatementEnd(); return Stmt.Pass(l) }
            }
        }
        if (k is Tok.Name && unsupported.contains(k.name)) {
            throw perr("'${k.name}' is not supported by PyMini", lineNo)
        }
        return parseSimpleStatement()
    }

    private fun parseIf(): Stmt {
        val l = lineNo
        advance()
        val branches = ArrayList<Pair<Expr, List<Stmt>>>()
        val cond = parseExpr()
        val body = parseBlock()
        branches.add(Pair(cond, body))
        skipNewlines()
        while (isKeyword("elif")) {
            advance()
            val c = parseExpr()
            val b = parseBlock()
            branches.add(Pair(c, b))
            skipNewlines()
        }
        return Stmt.If(branches, parseOptionalElse(), l)
    }

    private fun parseWhile(): Stmt {
        val l = lineNo
        advance()
        val cond = parseExpr()
        val body = parseBlock()
        return Stmt.While(cond, body, parseOptionalElse(), l)
    }

    private fun parseFor(): Stmt {
        val l = lineNo
        advance()
        val target = parseTargetList()
        if (!matchKeyword("in")) throw perr("expected 'in' in for-loop", lineNo)
        val iterable = parseExprOrTupleValue()
        val body = parseBlock()
        return Stmt.For(target, iterable, body, parseOptionalElse(), l)
    }

    /** A comma-separated assignment or loop target: a name or a tuple. */
    private fun parseTargetList(): Expr {
        val items = arrayListOf(parseTargetAtom())
        var sawComma = false
        while (isOp(",")) {
            advance()
            sawComma = true
            if (isKeyword("in") || isOp("=")) break
            items.add(parseTargetAtom())
        }
        return if (sawComma) Expr.TupleLit(items) else items[0]
    }

    /** A single target: a name, an index, or a bracketed nested target. */
    private fun parseTargetAtom(): Expr {
        if (isOp("(") || isOp("[")) {
            val close = if (isOp("(")) ")" else "]"
            advance()
            val items = ArrayList<Expr>()
            while (!isOp(close)) {
                items.add(parseTargetAtom())
                if (!matchOp(",")) break
            }
            expectOp(close)
            return Expr.TupleLit(items)
        }
        return parsePostfix()
    }

    private fun parseDef(): Stmt {
        val l = lineNo
        advance()
        val name = expectName("function name")
        val (params, variadic) = parseParameters()
        val body = parseBlock()
        return Stmt.FuncDef(name, params, variadic, body, l)
    }

    /** `(a, b=1, *rest)`, shared by `def` and `lambda` (which has no brackets). */
    private fun parseParameters(parenthesised: Boolean = true): Pair<List<Param>, String?> {
        if (parenthesised) expectOp("(")
        val params = ArrayList<Param>()
        var variadic: String? = null
        while (!(if (parenthesised) isOp(")") else isOp(":"))) {
            if (matchOp("*")) {
                variadic = expectName("parameter name")
                if (!matchOp(",")) break
                continue
            }
            if (isOp("**")) throw perr("**kwargs is not supported by PyMini", lineNo)
            val p = expectName("parameter name")
            var def: Expr? = null
            if (matchOp("=")) def = parseExpr()
            params.add(Param(p, def))
            if (!matchOp(",")) break
        }
        if (parenthesised) expectOp(")")
        return Pair(params, variadic)
    }

    private fun parseReturn(): Stmt {
        val l = lineNo
        advance()
        if (atStatementEnd() || isOp(";")) {
            expectStatementEnd()
            return Stmt.Return(null, l)
        }
        val e = parseExprOrTupleValue()
        expectStatementEnd()
        return Stmt.Return(e, l)
    }

    private fun parseTry(): Stmt {
        val l = lineNo
        advance()
        val body = parseBlock()
        val handlers = ArrayList<ExceptHandler>()
        skipNewlines()
        while (isKeyword("except")) {
            advance()
            val types = ArrayList<String>()
            var binding: String? = null
            if (!isOp(":")) {
                if (matchOp("(")) {
                    while (!isOp(")")) {
                        types.add(expectName("exception type"))
                        if (!matchOp(",")) break
                    }
                    expectOp(")")
                } else {
                    types.add(expectName("exception type"))
                }
                if (matchKeyword("as")) binding = expectName("name")
            }
            handlers.add(ExceptHandler(types, binding, parseBlock()))
            skipNewlines()
        }
        val elseBody = parseOptionalElse()
        skipNewlines()
        var finallyBody: List<Stmt>? = null
        if (isKeyword("finally")) {
            advance()
            finallyBody = parseBlock()
        }
        if (handlers.isEmpty() && finallyBody == null) {
            throw perr("try needs an 'except' or 'finally' block", l)
        }
        return Stmt.Try(body, handlers, elseBody, finallyBody, l)
    }

    private fun parseRaise(): Stmt {
        val l = lineNo
        advance()
        if (atStatementEnd() || isOp(";")) {
            expectStatementEnd()
            return Stmt.Raise(null, l)
        }
        val e = parseExpr()
        expectStatementEnd()
        return Stmt.Raise(e, l)
    }

    private fun parseImport(): Stmt {
        val l = lineNo
        advance()
        val module = expectName("module name")
        var alias: String? = null
        if (matchKeyword("as")) alias = expectName("name")
        expectStatementEnd()
        return Stmt.Import(module, alias, null, l)
    }

    private fun parseFromImport(): Stmt {
        val l = lineNo
        advance()
        val module = expectName("module name")
        if (!matchKeyword("import")) throw perr("expected 'import'", lineNo)
        if (matchOp("*")) throw perr("'from ... import *' is not supported by PyMini", l)
        val names = ArrayList<Pair<String, String?>>()
        val bracketed = matchOp("(")
        do {
            val n = expectName("name")
            var alias: String? = null
            if (matchKeyword("as")) alias = expectName("name")
            names.add(Pair(n, alias))
        } while (matchOp(",") && !(bracketed && isOp(")")))
        if (bracketed) expectOp(")")
        expectStatementEnd()
        return Stmt.Import(module, null, names, l)
    }

    private fun parseGlobal(): Stmt {
        val l = lineNo
        advance()
        val names = ArrayList<String>()
        do { names.add(expectName("name")) } while (matchOp(","))
        expectStatementEnd()
        return Stmt.Global(names, l)
    }

    private fun parseDel(): Stmt {
        val l = lineNo
        advance()
        val targets = ArrayList<Expr>()
        do { targets.add(parsePostfix()) } while (matchOp(","))
        expectStatementEnd()
        return Stmt.Del(targets, l)
    }

    private fun parseSimpleStatement(): Stmt {
        val l = lineNo
        val first = parseExprOrTupleValue()
        // Augmented assignment.
        val k = kind
        if (k is Tok.Op) {
            val binop = augOps[k.op]
            if (binop != null) {
                advance()
                val value = parseExprOrTupleValue()
                expectStatementEnd()
                return Stmt.AugAssign(first, binop, value, l)
            }
        }
        // Plain or chained assignment: every part before the final `=` is a target.
        if (isOp("=")) {
            val parts = arrayListOf(first)
            while (matchOp("=")) parts.add(parseExprOrTupleValue())
            val value = parts.removeAt(parts.size - 1)
            expectStatementEnd()
            return Stmt.Assign(parts, value, l)
        }
        expectStatementEnd()
        return Stmt.ExprStmt(first, l)
    }

    /** An expression that may be a bare comma tuple (`a, b = …`, `return a, b`). */
    private fun parseExprOrTupleValue(): Expr {
        val first = parseExpr()
        if (isOp(",")) {
            val items = arrayListOf(first)
            while (matchOp(",")) {
                if (isOp("=") || kind == Tok.Newline || kind == Tok.Eof || isOp(";") || kind == Tok.Dedent) break
                items.add(parseExpr())
            }
            return Expr.TupleLit(items)
        }
        return first
    }

    // MARK: - Expressions, by precedence

    private fun parseExpr(): Expr {
        if (isKeyword("lambda")) return parseLambda()
        return parseTernary()
    }

    private fun parseLambda(): Expr {
        advance()
        val (params, variadic) = parseParameters(parenthesised = false)
        expectOp(":")
        val body = parseExpr()
        return Expr.Lambda(params, variadic, body)
    }

    private fun parseTernary(): Expr {
        val value = parseOr()
        if (isKeyword("if")) {
            advance()
            val cond = parseOr()
            if (!matchKeyword("else")) throw perr("expected 'else' in conditional expression", lineNo)
            val orElse = parseExpr()
            return Expr.Ternary(cond, value, orElse)
        }
        return value
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (matchKeyword("or")) left = Expr.BoolOp(BoolKind.OR, left, parseAnd())
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (matchKeyword("and")) left = Expr.BoolOp(BoolKind.AND, left, parseNot())
        return left
    }

    private fun parseNot(): Expr {
        if (matchKeyword("not")) return Expr.Unary(UnOp.NOT, parseNot())
        return parseComparison()
    }

    private fun parseComparison(): Expr {
        val left = parseBitOr()
        val pairs = ArrayList<Pair<CmpOp, Expr>>()
        while (true) {
            var op: CmpOp? = null
            val k = kind
            if (k is Tok.Op) {
                op = when (k.op) {
                    "==" -> CmpOp.EQ
                    "!=" -> CmpOp.NE
                    "<" -> CmpOp.LT
                    "<=" -> CmpOp.LE
                    ">" -> CmpOp.GT
                    ">=" -> CmpOp.GE
                    else -> null
                }
                if (op != null) advance()
            } else if (isKeyword("in")) {
                advance(); op = CmpOp.IN
            } else if (isKeyword("is")) {
                advance()
                op = if (matchKeyword("not")) CmpOp.IS_NOT else CmpOp.IS
            } else if (isKeyword("not") && peekKind(1) == Tok.Keyword("in")) {
                advance(); advance(); op = CmpOp.NOT_IN
            }
            if (op == null) break
            pairs.add(Pair(op, parseBitOr()))
        }
        if (pairs.isEmpty()) return left
        return Expr.Compare(left, pairs)
    }

    // `|` then `^` then `&`, at Python's precedence.
    private fun parseBitOr(): Expr {
        var left = parseBitXor()
        while (matchOp("|")) left = Expr.Binary(BinOp.BIT_OR, left, parseBitXor())
        return left
    }

    private fun parseBitXor(): Expr {
        var left = parseBitAnd()
        while (matchOp("^")) left = Expr.Binary(BinOp.BIT_XOR, left, parseBitAnd())
        return left
    }

    private fun parseBitAnd(): Expr {
        var left = parseAdd()
        while (matchOp("&")) left = Expr.Binary(BinOp.BIT_AND, left, parseAdd())
        return left
    }

    private fun parseAdd(): Expr {
        var left = parseMul()
        while (true) {
            left = if (matchOp("+")) Expr.Binary(BinOp.ADD, left, parseMul())
            else if (matchOp("-")) Expr.Binary(BinOp.SUB, left, parseMul())
            else break
        }
        return left
    }

    private fun parseMul(): Expr {
        var left = parseFactor()
        while (true) {
            left = if (matchOp("*")) Expr.Binary(BinOp.MUL, left, parseFactor())
            else if (matchOp("/")) Expr.Binary(BinOp.DIV, left, parseFactor())
            else if (matchOp("//")) Expr.Binary(BinOp.FLOORDIV, left, parseFactor())
            else if (matchOp("%")) Expr.Binary(BinOp.MOD, left, parseFactor())
            else break
        }
        return left
    }

    private fun parseFactor(): Expr {
        if (matchOp("-")) return Expr.Unary(UnOp.NEG, parseFactor())
        if (matchOp("+")) return Expr.Unary(UnOp.POS, parseFactor())
        return parsePower()
    }

    private fun parsePower(): Expr {
        val base = parsePostfix()
        // Right-associative; the exponent may carry its own unary sign.
        if (matchOp("**")) return Expr.Binary(BinOp.POW, base, parseFactor())
        return base
    }

    private fun parsePostfix(): Expr {
        var e = parseAtom()
        while (true) {
            e = if (isOp("(")) parseCall(e)
            else if (isOp("[")) parseSubscript(e)
            else if (matchOp(".")) Expr.Attribute(e, expectName("attribute name"))
            else break
        }
        return e
    }

    private fun parseCall(callee: Expr): Expr {
        advance()
        val args = ArrayList<Expr>()
        val kwargs = ArrayList<Pair<String, Expr>>()
        while (!isOp(")")) {
            val k = kind
            if (matchOp("*")) {
                args.add(Expr.Starred(parseExpr()))
            } else if (k is Tok.Name && peekKind(1) == Tok.Op("=")) {
                // kwarg: name = expr (not ==, which lexes as one token)
                advance(); advance()
                kwargs.add(Pair(k.name, parseExpr()))
            } else {
                val first = parseExpr()
                // A bare generator expression argument: `sum(x * 2 for x in xs)`.
                if (isKeyword("for")) {
                    args.add(Expr.Comprehension(ComprehensionKind.LIST, first, null, parseComprehensionClauses()))
                } else {
                    args.add(first)
                }
            }
            if (!matchOp(",")) break
        }
        expectOp(")")
        return Expr.Call(callee, args, kwargs)
    }

    private fun parseSubscript(base: Expr): Expr {
        advance()
        var lower: Expr? = null
        var isSlice = false
        if (!isOp(":")) lower = parseExprOrTupleValue()
        var upper: Expr? = null
        var step: Expr? = null
        if (isOp(":")) {
            isSlice = true
            advance()
            if (!isOp(":") && !isOp("]")) upper = parseExpr()
            if (isOp(":")) {
                advance()
                if (!isOp("]")) step = parseExpr()
            }
        }
        expectOp("]")
        if (isSlice) return Expr.Slice(base, lower, upper, step)
        return Expr.Index(base, lower ?: throw perr("invalid subscript", lineNo))
    }

    /** The `for … in … [if …]` chain shared by every comprehension form. */
    private fun parseComprehensionClauses(): List<ComprehensionClause> {
        val clauses = ArrayList<ComprehensionClause>()
        while (matchKeyword("for")) {
            val target = parseTargetList()
            if (!matchKeyword("in")) throw perr("expected 'in' in comprehension", lineNo)
            // The iterable stops before a trailing `if`, which filters here.
            val iterable = parseOr()
            val conditions = ArrayList<Expr>()
            while (isKeyword("if")) {
                advance()
                conditions.add(parseOr())
            }
            clauses.add(ComprehensionClause(target, iterable, conditions))
        }
        if (clauses.isEmpty()) throw perr("expected 'for' in comprehension", lineNo)
        return clauses
    }

    private fun parseAtom(): Expr {
        val k = kind
        when {
            k is Tok.IntTok -> { advance(); return Expr.IntLit(k.value) }
            k is Tok.DoubleTok -> { advance(); return Expr.DoubleLit(k.value) }
            k is Tok.Str -> {
                // Adjacent string literals concatenate, as in Python.
                val joined = StringBuilder()
                while (true) {
                    val s = kind as? Tok.Str ?: break
                    joined.append(s.value)
                    advance()
                }
                return Expr.StringLit(joined.toString())
            }
            k is Tok.FString -> { advance(); return Expr.FString(stringParts(k.parts)) }
            k == Tok.Keyword("True") -> { advance(); return Expr.BoolLit(true) }
            k == Tok.Keyword("False") -> { advance(); return Expr.BoolLit(false) }
            k == Tok.Keyword("None") -> { advance(); return Expr.NoneLit }
            k == Tok.Keyword("lambda") -> return parseLambda()
            k is Tok.Name -> { advance(); return Expr.Name(k.name) }
            k == Tok.Op("(") -> return parseGroupOrTuple()
            k == Tok.Op("[") -> return parseListLiteral()
            k == Tok.Op("{") -> return parseBraceLiteral()
            else -> throw perr("unexpected token in expression", lineNo)
        }
    }

    private fun parseGroupOrTuple(): Expr {
        advance()
        if (isOp(")")) { advance(); return Expr.TupleLit(emptyList()) }
        val first = parseExpr()
        if (isKeyword("for")) {
            val clauses = parseComprehensionClauses()
            expectOp(")")
            return Expr.Comprehension(ComprehensionKind.LIST, first, null, clauses)
        }
        if (isOp(",")) {
            val items = arrayListOf(first)
            while (matchOp(",")) {
                if (isOp(")")) break
                items.add(parseExpr())
            }
            expectOp(")")
            return Expr.TupleLit(items)
        }
        expectOp(")")
        return first
    }

    private fun parseListLiteral(): Expr {
        advance()
        if (isOp("]")) { advance(); return Expr.ListLit(emptyList()) }
        val first = parseExpr()
        if (isKeyword("for")) {
            val clauses = parseComprehensionClauses()
            expectOp("]")
            return Expr.Comprehension(ComprehensionKind.LIST, first, null, clauses)
        }
        val items = arrayListOf(first)
        while (matchOp(",")) {
            if (isOp("]")) break
            items.add(parseExpr())
        }
        expectOp("]")
        return Expr.ListLit(items)
    }

    /** `{}` and `{a: b}` are dicts, `{a, b}` is a set, and either can be a comprehension. */
    private fun parseBraceLiteral(): Expr {
        advance()
        if (isOp("}")) { advance(); return Expr.DictLit(emptyList()) }
        val first = parseExpr()
        if (matchOp(":")) {
            val firstValue = parseExpr()
            if (isKeyword("for")) {
                val clauses = parseComprehensionClauses()
                expectOp("}")
                return Expr.Comprehension(ComprehensionKind.DICT, first, firstValue, clauses)
            }
            val pairs = arrayListOf(Pair(first, firstValue))
            while (matchOp(",")) {
                if (isOp("}")) break
                val k = parseExpr()
                expectOp(":")
                pairs.add(Pair(k, parseExpr()))
            }
            expectOp("}")
            return Expr.DictLit(pairs)
        }
        if (isKeyword("for")) {
            val clauses = parseComprehensionClauses()
            expectOp("}")
            return Expr.Comprehension(ComprehensionKind.SET, first, null, clauses)
        }
        val items = arrayListOf(first)
        while (matchOp(",")) {
            if (isOp("}")) break
            items.add(parseExpr())
        }
        expectOp("}")
        return Expr.SetLit(items)
    }
}
