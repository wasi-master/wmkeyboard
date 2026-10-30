package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Korean on Samsung's 베가 (Vega) pad, where every key carries a cycle, vowels
 * included: ㅏㅑ, ㅁㅅㅆ, ㅣㅡㅢ. The spelling is [NaratgeulComposer]'s (ㅏ then ㅣ
 * is ㅐ), and what differs is the one thing a pad of cycles needs: as on
 * Cheonjiin, ㄱ ㄱ is ㅋ, so a space straight after a tap ends the run and
 * ㄱ, space, ㄱ is how two of the same letter are typed.
 */
object VegaComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val offersHanja: Boolean get() = true

    override val spaceEndsMultitap: Boolean get() = true

    override fun composeBuffer(buffer: String): String = NaratgeulComposer.composeBuffer(buffer)
}
