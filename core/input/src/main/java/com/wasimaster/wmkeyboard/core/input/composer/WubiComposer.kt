package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Wubi 86 (五笔字型). A character or a phrase is spelled by the root components it
 * is drawn from, at most four keys, typed on the letters a–y. The five stroke
 * zones are how those letters are laid out: 一 g f d s a, 丨 h j k l m, 丿 t r e
 * w q, 丶 y u i o p, 乙 n b v c x.
 *
 * The buffer stays in letters, since those are what a Wubi code is written in.
 * `z` is the method's own wildcard, standing in for a component you are not sure
 * of ([CodeTableDictionary] reads it as `.`).
 *
 * Where [CangjieComposer] takes the whole buffer on a commit, this one takes four
 * keys at most: `wythwy` offers what `wyth` spells, and picking one leaves `wy`
 * converting. So a run of full codes can be typed without stopping to pick each.
 */
object WubiComposer : Composer {

    /** The longest code there is; a key past it starts the next code. */
    private const val MAX_CODE = 4

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingWubi

    /** The keys as a lookup pattern, dropping anything off the alphabet. */
    private fun normalize(buffer: String): String = buildString {
        for (c in buffer.lowercase()) {
            when (c) {
                in 'a'..'y' -> append(c)
                'z' -> append('.')
                else -> Unit
            }
        }
    }

    override fun composeBuffer(buffer: String): String = buffer.lowercase().filter { it in 'a'..'z' }

    override fun candidates(buffer: String): List<String> {
        val code = normalize(buffer).take(MAX_CODE)
        // Wildcards alone match the whole table, which says nothing and costs a full sort.
        if (code.all { it == '.' }) return emptyList()
        return CjkDictionaries.wubi.candidates(code).map(HanVariant::toTraditional).distinct()
    }

    override fun consumedFor(buffer: String, chosen: String): Int = minOf(buffer.length, MAX_CODE)
}
