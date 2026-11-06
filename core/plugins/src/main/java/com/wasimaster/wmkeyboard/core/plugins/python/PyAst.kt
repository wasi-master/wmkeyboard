package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyAST.swift: `Expr` for expressions, `Stmt` for statements, and the
 * operator enums the parser produces and the interpreter walks.
 */

internal enum class BinOp { ADD, SUB, MUL, DIV, FLOORDIV, MOD, POW, BIT_AND, BIT_OR, BIT_XOR }
internal enum class CmpOp { EQ, NE, LT, LE, GT, GE, IN, NOT_IN, IS, IS_NOT }
internal enum class UnOp { NEG, POS, NOT }
internal enum class BoolKind { AND, OR }

/** How a value is converted before its format spec applies: f-string `!r` or `!s`. */
internal enum class FConversion { STR, REPR }

/** One segment of an f-string: a literal run, or an embedded expression. */
internal sealed class FStringPart {
    class Literal(val text: String) : FStringPart()
    class Expression(val expr: Expr, val conversion: FConversion?, val spec: List<FStringPart>?) : FStringPart()
}

/** What a comprehension builds. */
internal enum class ComprehensionKind { LIST, SET, DICT }

/** One `for … in … if …` clause of a comprehension; several nest left to right. */
internal class ComprehensionClause(val target: Expr, val iterable: Expr, val conditions: List<Expr>)

/** A parameter; its default is evaluated in the closure at call time. */
internal class Param(val name: String, val defaultValue: Expr?)

/** Expression nodes. */
internal sealed class Expr {
    object NoneLit : Expr()
    class BoolLit(val value: Boolean) : Expr()
    class IntLit(val value: Long) : Expr()
    class DoubleLit(val value: Double) : Expr()
    class StringLit(val value: String) : Expr()
    class FString(val parts: List<FStringPart>) : Expr()
    class Name(val name: String) : Expr()
    class ListLit(val items: List<Expr>) : Expr()
    class DictLit(val pairs: List<Pair<Expr, Expr>>) : Expr()
    class SetLit(val items: List<Expr>) : Expr()
    class Unary(val op: UnOp, val operand: Expr) : Expr()
    class Binary(val op: BinOp, val left: Expr, val right: Expr) : Expr()
    class BoolOp(val kind: BoolKind, val left: Expr, val right: Expr) : Expr()
    /** Chained comparison: `left op a op b …`. */
    class Compare(val left: Expr, val pairs: List<Pair<CmpOp, Expr>>) : Expr()
    class Ternary(val cond: Expr, val then: Expr, val orElse: Expr) : Expr()
    class Call(val callee: Expr, val args: List<Expr>, val kwargs: List<Pair<String, Expr>>) : Expr()
    class Index(val base: Expr, val index: Expr) : Expr()
    class Slice(val base: Expr, val lower: Expr?, val upper: Expr?, val step: Expr?) : Expr()
    /** Attribute access: a method callee, a module member, or `e.args` on an exception. */
    class Attribute(val obj: Expr, val name: String) : Expr()
    /** Comma-separated list: a tuple literal, or an unpacking target. */
    class TupleLit(val items: List<Expr>) : Expr()
    class Lambda(val params: List<Param>, val variadic: String?, val body: Expr) : Expr()
    /** `value` is the dict value expression; null for list and set comprehensions. */
    class Comprehension(
        val kind: ComprehensionKind,
        val element: Expr,
        val value: Expr?,
        val clauses: List<ComprehensionClause>,
    ) : Expr()
    /** `*seq` spread inside a call's argument list. */
    class Starred(val inner: Expr) : Expr()
}

/** One `except …:` clause. `types` empty means a bare `except:`. */
internal class ExceptHandler(val types: List<String>, val binding: String?, val body: List<Stmt>)

/** Statement nodes, each with the 1-based line it started on. */
internal sealed class Stmt(val line: Int) {
    class ExprStmt(val expr: Expr, line: Int) : Stmt(line)
    /** `a = b = value` → targets = [a, b]. */
    class Assign(val targets: List<Expr>, val value: Expr, line: Int) : Stmt(line)
    class AugAssign(val target: Expr, val op: BinOp, val value: Expr, line: Int) : Stmt(line)
    class If(val branches: List<Pair<Expr, List<Stmt>>>, val elseBody: List<Stmt>?, line: Int) : Stmt(line)
    class While(val cond: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?, line: Int) : Stmt(line)
    class For(val target: Expr, val iterable: Expr, val body: List<Stmt>, val elseBody: List<Stmt>?, line: Int) : Stmt(line)
    class FuncDef(val name: String, val params: List<Param>, val variadic: String?, val body: List<Stmt>, line: Int) : Stmt(line)
    class Return(val value: Expr?, line: Int) : Stmt(line)
    class Break(line: Int) : Stmt(line)
    class Continue(line: Int) : Stmt(line)
    class Pass(line: Int) : Stmt(line)
    class Try(
        val body: List<Stmt>,
        val handlers: List<ExceptHandler>,
        val elseBody: List<Stmt>?,
        val finallyBody: List<Stmt>?,
        line: Int,
    ) : Stmt(line)
    /** `raise Value("…")`, or a bare `raise` re-raising the active exception. */
    class Raise(val value: Expr?, line: Int) : Stmt(line)
    /** `import math [as m]` / `from math import sqrt [as root], pi`. */
    class Import(val module: String, val alias: String?, val names: List<Pair<String, String?>>?, line: Int) : Stmt(line)
    class Global(val names: List<String>, line: Int) : Stmt(line)
    class Del(val targets: List<Expr>, line: Int) : Stmt(line)
}
