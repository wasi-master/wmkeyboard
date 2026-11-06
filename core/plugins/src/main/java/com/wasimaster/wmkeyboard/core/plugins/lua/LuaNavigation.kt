package com.wasimaster.wmkeyboard.core.plugins.lua

/** One named function in the outline, and how deep inside other named functions it is written. */
data class LuaOutlineItem(
    val name: String,
    val kind: LuaFunctionKind,
    val nameSpan: LuaSpan,
    val span: LuaSpan,
    val depth: Int,
)

/** Why a rename cannot go ahead. */
enum class LuaRenameProblem {
    /** The new name is not a Lua name. */
    NOT_A_NAME,

    /** The new name is a Lua keyword. */
    KEYWORD,

    /** Another name of that spelling is already seen where a use sits, so the rename would change what the use means. */
    TAKEN,
}

/**
 * Going from a name to the places that mean it, and changing them together.
 *
 * All of it needs an analysis, so none of it is offered on a script that does
 * not parse: a rename that guesses at scope rewrites the wrong name, and that
 * breaks a file more quietly than leaving it alone.
 */
object LuaNavigation {

    private val KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
        "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val CONTRACT = setOf("render", "on_event", "transform")

    /** Where the name at [caret] is declared: a local's declaration, or the first assignment of a global. */
    fun definitionAt(document: LuaDocument, caret: Int): LuaSpan? {
        val analysis = document.analysis ?: return null
        val token = nameAt(document.tokens, caret)
        if (token < 0) return null
        analysis.symbolAt(token)?.let { return it.declaration }
        val use = analysis.globalAt(token) ?: return null
        return analysis.globals.filter { it.name == use.name && it.write }.minByOrNull { it.span.start }?.span
    }

    /** Every place the name at [caret] means the same thing, its declaration included, in document order. */
    fun referencesAt(document: LuaDocument, caret: Int): List<LuaSpan> {
        val analysis = document.analysis ?: return emptyList()
        val token = nameAt(document.tokens, caret)
        if (token < 0) return emptyList()
        analysis.symbolAt(token)?.let { symbol ->
            return (listOfNotNull(symbol.declaration) + symbol.reads + symbol.writes).sortedBy { it.start }
        }
        val use = analysis.globalAt(token) ?: return emptyList()
        return analysis.globals.filter { it.name == use.name }.map { it.span }
    }

    /**
     * The places a rename of the name at [caret] rewrites, or null where renaming
     * is not offered: no analysis, no name, `self`, an API name, or `render`,
     * `on_event` and `transform`, which the keyboard calls by name.
     */
    fun renameSpans(document: LuaDocument, caret: Int): List<LuaSpan>? {
        val analysis = document.analysis ?: return null
        val token = nameAt(document.tokens, caret)
        if (token < 0) return null
        analysis.symbolAt(token)?.let { symbol ->
            if (symbol.kind == LuaSymbolKind.SELF) return null
            return referencesAt(document, caret)
        }
        val use = analysis.globalAt(token) ?: return null
        if (use.name in CONTRACT || LuaApi.find(use.name) != null || use.name in LuaApi.nilled) return null
        return referencesAt(document, caret)
    }

    /** What stops [newName] replacing the name at [caret], or null when nothing does. */
    fun renameProblem(document: LuaDocument, caret: Int, newName: String): LuaRenameProblem? {
        if (!NAME.matches(newName)) return LuaRenameProblem.NOT_A_NAME
        if (newName in KEYWORDS) return LuaRenameProblem.KEYWORD
        val analysis = document.analysis ?: return null
        val token = nameAt(document.tokens, caret)
        if (token < 0) return null
        val symbol = analysis.symbolAt(token)
        val spans = renameSpans(document, caret) ?: return null
        val oldName = symbol?.name ?: analysis.globalAt(token)?.name ?: return null
        if (newName == oldName) return null
        // Another local of the new name, seen where any use sits, would take that use.
        for (span in spans) {
            if (analysis.visibleAt(span.start).any { it.name == newName && it !== symbol }) return LuaRenameProblem.TAKEN
        }
        if (symbol != null) {
            // A global of the new name used inside this local's scope would become this local.
            val captured = analysis.globals.any { it.name == newName && it.span.start >= symbol.scope.start && it.span.start <= symbol.scope.end }
            if (captured) return LuaRenameProblem.TAKEN
        } else if (newName in analysis.globalNames || LuaApi.find(newName) != null || newName in LuaApi.nilled) {
            return LuaRenameProblem.TAKEN
        }
        return null
    }

    /** The named functions in document order, with anonymous ones left out and not counted in depth. */
    fun outline(analysis: LuaAnalysis): List<LuaOutlineItem> {
        val named = analysis.functions.filter { it.kind != LuaFunctionKind.ANONYMOUS && it.name != null }
        return named.sortedBy { it.span.start }.map { function ->
            var depth = 0
            var parent = analysis.function(function.parent)
            while (parent != null) {
                if (parent.kind != LuaFunctionKind.ANONYMOUS) depth++
                parent = analysis.function(parent.parent)
            }
            LuaOutlineItem(function.name.orEmpty(), function.kind, function.nameSpan, function.span, depth)
        }
    }

    /**
     * The stretches the editor can step over, each from its opening token to the
     * end of its closing one, in document order: blocks, tables and long comments
     * or strings that run over more than one line. From tokens, so they are there
     * while the file is broken too.
     */
    fun foldRegions(document: LuaDocument): List<LuaSpan> = foldRegions(document.tokens, document.blocks)

    /** [foldRegions] from tokens alone, for an editor that has lexed the text and must not wait for a parse. */
    fun foldRegions(tokens: LuaTokens): List<LuaSpan> = foldRegions(tokens, LuaBlocks.of(tokens))

    private fun foldRegions(tokens: LuaTokens, blocks: LuaBlocks): List<LuaSpan> {
        val source = tokens.source
        val regions = ArrayList<LuaSpan>()
        val braces = ArrayList<Int>()
        for (index in 0 until tokens.size) {
            val kind = tokens.kind(index)
            when {
                kind == LuaTokenKind.KEYWORD -> {
                    val closer = blocks.closerOf(index)
                    if (closer > index) {
                        // `else` and `elseif` end a region at their own start, so branches do not overlap.
                        val end = if (tokens.matches(closer, "end") || tokens.matches(closer, "until")) tokens.end(closer) else tokens.start(closer)
                        val start = regionStart(tokens, index)
                        if (spansLines(source, start, end)) regions += LuaSpan(start, end)
                    }
                }
                kind == LuaTokenKind.OPERATOR && tokens.matches(index, "{") -> braces += index
                kind == LuaTokenKind.OPERATOR && tokens.matches(index, "}") && braces.isNotEmpty() -> {
                    val open = braces.removeAt(braces.size - 1)
                    if (spansLines(source, tokens.start(open), tokens.end(index))) regions += LuaSpan(tokens.start(open), tokens.end(index))
                }
                kind == LuaTokenKind.LONG_COMMENT || kind == LuaTokenKind.LONG_STRING ->
                    if (spansLines(source, tokens.start(index), tokens.end(index))) regions += tokens.span(index)
            }
        }
        return regions.sortedWith(compareBy({ it.start }, { -it.end }))
    }

    /** A block's region starts at the keyword its statement starts with: `if` for `then`, `while` or `for` for `do`. */
    private fun regionStart(tokens: LuaTokens, opener: Int): Int {
        val heads = when {
            tokens.matches(opener, "then") -> setOf("if", "elseif")
            tokens.matches(opener, "do") -> setOf("while", "for")
            else -> return tokens.start(opener)
        }
        var at = tokens.prevCode(opener)
        var steps = 0
        while (at >= 0 && steps++ < MAX_HEAD_TOKENS) {
            if (tokens.kind(at) == LuaTokenKind.KEYWORD) {
                if (tokens.text(at) in heads) return tokens.start(at)
                if (tokens.text(at) !in setOf("and", "or", "not", "nil", "true", "false", "in")) break
            }
            at = tokens.prevCode(at)
        }
        return tokens.start(opener)
    }

    private const val MAX_HEAD_TOKENS = 256

    private fun spansLines(source: String, start: Int, end: Int): Boolean = source.indexOf('\n', start).let { it in start until end }

    /** The name token under the caret, or the one just before it when the caret sits at a name's end. */
    private fun nameAt(tokens: LuaTokens, caret: Int): Int {
        if (caret < tokens.source.length) {
            val here = tokens.indexAt(caret)
            if (here >= 0 && tokens.kind(here) == LuaTokenKind.NAME) return here
        }
        val before = if (caret > 0) tokens.indexAt(caret - 1) else -1
        return if (before >= 0 && tokens.kind(before) == LuaTokenKind.NAME) before else -1
    }
}
