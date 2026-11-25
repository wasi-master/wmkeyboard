package com.wasimaster.wmkeyboard.core.input.composer

import java.text.Normalizer

/**
 * Vietnamese input by the two dominant methods, Telex and VNI. Both are
 * transliterating composers: the roman keystrokes accumulate in the service
 * buffer and [composeBuffer] folds them into fully-toned Vietnamese, shown as
 * the composing region and committed as a unit — the same shape as Avro/Hangul,
 * so no dictionary and no candidate list are involved.
 *
 * Telex spells the diacritics with letters (`as`→á, `aa`→â, `aw`→ă, `ow`→ơ,
 * `w`→ư, `dd`→đ, tones s/f/r/x/j) and takes a tone back off with `z` (`toansz`
 * → `toan`); VNI spells them with digits (1..5 tones, 6 circumflex, 7 horn,
 * 8 breve, 9 đ, 0 clears the tone). The engine is shared; only the
 * keystroke→intent mapping differs.
 *
 * The tone lands on the syllable's main vowel by the standard rule: a vowel that
 * already carries a mark (â ê ô ă ơ ư) wins; otherwise a single vowel takes it,
 * a closed cluster puts it on the last vowel, and an open cluster on the first
 * (except oa/oe/uy, which take the second). `qu`/`gi` onsets are not counted as
 * the nucleus.
 */
internal enum class VMark { NONE, CIRCUMFLEX, BREVE, HORN, STROKE }

internal enum class VTone(val combining: Char?) {
    NONE(null), ACUTE('́'), GRAVE('̀'), HOOK('̉'), TILDE('̃'), DOT('̣')
}

/**
 * [synthesized] marks a letter the engine made for a keystroke rather than one
 * the user typed: a bare `w` stands for ư, and that ư is spelled with a u no
 * finger ever pressed. Undoing the mark on such a letter has to take the letter
 * with it, or the u is left behind spelling a word the user never wrote.
 */
private class VLetter(
    var base: Char,
    var mark: VMark,
    val upper: Boolean,
    val synthesized: Boolean = false,
)

internal object VietnameseEngine {

    /**
     * The face a tone key's ring is drawn on: a placeholder circle carrying the
     * mark. Typed along with the mark, and swallowed by the transducer.
     */
    internal const val DOTTED_CIRCLE = '\u25CC'

    /** Telex keys that spell a tone: they ride a word without joining it. */
    private const val TONE_KEYS = "sfrxj"

    /** The letters a coda can begin with: c, ch, m, n, ng, nh, p, t. */
    private const val CODA_HEADS = "cmnpth"

    /** The letters a syllable that opens with `ư` can go on to. */
    private const val AFTER_BARE_U = "aoiucnmtp"

    /** The tone [c] *is*, when it is a combining mark rather than a letter. */
    internal fun directTone(c: Char): VTone? = when (c) {
        '\u0301' -> VTone.ACUTE
        '\u0300' -> VTone.GRAVE
        '\u0309' -> VTone.HOOK
        '\u0303' -> VTone.TILDE
        '\u0323' -> VTone.DOT
        else -> null
    }

    /** Whether [c] is one of the characters a tone key sends. */
    internal fun isToneChar(c: Char): Boolean = c == DOTTED_CIRCLE || directTone(c) != null

    private fun isVowel(c: Char) = c in "aeiouy"

    private fun precompose(base: Char, mark: VMark): Char = when (mark) {
        VMark.NONE -> base
        VMark.CIRCUMFLEX -> when (base) { 'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; else -> base }
        VMark.BREVE -> if (base == 'a') 'ă' else base
        VMark.HORN -> when (base) { 'o' -> 'ơ'; 'u' -> 'ư'; else -> base }
        VMark.STROKE -> if (base == 'd') 'đ' else base
    }

    /**
     * Whether the `u` at [uIdx] is the one a `qu` onset is spelled with rather
     * than a letter of the nucleus — the glide [nucleus] sets aside.
     *
     * It is what keeps `quơ` (`qu` + `ơ`) out of the `ươ` business: `quơng`,
     * `quơi` and `quời` horn the `o` alone, however the word ends.
     *
     * A `q` in front is the whole of the test — where a `qu` onset keeps its
     * glide — and it is enough, `q` being an onset no other consonant may
     * precede, so a `q` with a `u` behind it is one however far into the buffer
     * it sits. The mark on that `u` is not read here: a `u` the user horned
     * himself is a different letter from a glide, but telling the two apart is
     * the caller's, which has the branch that wants it.
     */
    private fun isQuGlide(letters: List<VLetter>, uIdx: Int): Boolean =
        uIdx > 0 && letters[uIdx - 1].base == 'q'

    /** The index of the tone-bearing vowel, or -1 if the syllable has no vowel. */
    private fun nucleus(letters: List<VLetter>): Int {
        val vowels = letters.indices.filter { isVowel(letters[it].base) }.toMutableList()
        // qu- and gi- onsets: the u / i is a glide, not the nucleus, unless it is
        // the syllable's only vowel.
        if (letters.size >= 2 && letters[0].base == 'q' && letters[1].base == 'u' &&
            vowels.any { it > 1 }
        ) vowels.remove(1)
        if (letters.size >= 2 && letters[0].base == 'g' && letters[1].base == 'i' &&
            vowels.any { it > 1 }
        ) vowels.remove(1)
        if (vowels.isEmpty()) return -1
        vowels.lastOrNull { letters[it].mark != VMark.NONE }?.let { return it }
        if (vowels.size == 1) return vowels[0]
        val last = vowels.last()
        val hasCoda = (last + 1..letters.lastIndex).any { !isVowel(letters[it].base) }
        if (hasCoda) return last
        if (vowels.size >= 3) return vowels[vowels.size - 2]
        val a = letters[vowels[0]].base
        val b = letters[vowels[1]].base
        return if ((a == 'o' && b == 'a') || (a == 'o' && b == 'e') || (a == 'u' && b == 'y')) {
            vowels[1]
        } else {
            vowels[0]
        }
    }

    private fun render(letters: List<VLetter>, tone: VTone): String {
        if (letters.isEmpty()) return ""
        val nuc = if (tone == VTone.NONE) -1 else nucleus(letters)
        val sb = StringBuilder()
        letters.forEachIndexed { i, l ->
            var c = precompose(l.base, l.mark)
            if (l.upper) c = c.uppercaseChar()
            sb.append(c)
            if (i == nuc) tone.combining?.let { sb.append(it) }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /**
     * The keystrokes that spell [text], or null when this engine cannot read it
     * back — the inverse of [transduce], for a word the user has gone back into.
     *
     * The spelling is mechanical: a marked letter is written as its base and
     * then the key that marks it (`ô` → `oo`/`o6`, `ư` → `uw`/`u7`, `đ` →
     * `dd`/`d9`), and the tone key goes last, which is where both methods put
     * it. Mechanical is not the same as faithful — a spelling is one of many
     * that compose a word, and `ô` written as `oo` is also how `oo` is
     * written — so the answer is only given when it survives the round trip:
     * [transduce] of the result has to be [text] itself. Everything else comes
     * back null and the caller leaves the word alone, which is what keeps this
     * from rewriting a word the engine would not have produced: `as` would
     * compose `á`, `new` would compose `neư`, and neither is a reading of the
     * word in the field.
     *
     * Two tones on one word, a mark on a letter that cannot carry it, and a
     * word carrying marks only the input method knows are the other ways back
     * to null.
     */
    internal fun toKeystrokes(text: String, vni: Boolean): String? {
        val word = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (word.isEmpty()) return null
        val keys = StringBuilder(word.length)
        var tone = VTone.NONE
        // NFD splits a marked letter into its base and a combining mark, so a
        // mark arrives after the letter it belongs to and the key for it is
        // inserted right behind that letter rather than appended.
        var lastLetter = -1
        for (ch in Normalizer.normalize(word, Normalizer.Form.NFD)) {
            val direct = directTone(ch)
            if (direct != null) {
                if (tone != VTone.NONE) return null
                tone = direct
                continue
            }
            val mark = when (ch) {
                '̂' -> VMark.CIRCUMFLEX
                '̆' -> VMark.BREVE
                '̛' -> VMark.HORN
                else -> null
            }
            if (mark != null) {
                if (lastLetter < 0) return null
                val key = markKey(keys[lastLetter].lowercaseChar(), mark, vni) ?: return null
                keys.insert(lastLetter + 1, key)
                continue
            }
            // đ is a letter of its own rather than a d with a mark, so NFD
            // leaves it whole and it is spelled like any other marked letter:
            // the base, then the key.
            val stroked = ch == 'đ' || ch == 'Đ'
            keys.append(if (stroked) (if (ch == 'Đ') 'D' else 'd') else ch)
            lastLetter = keys.length - 1
            if (stroked) keys.append(markKey('d', VMark.STROKE, vni) ?: return null)
        }
        toneKey(tone, vni)?.let { keys.append(it) }
        val raw = keys.toString()
        // Checked against `compose`, not `transduce`. The strict rule answers a
        // word it cannot spell by handing the keys straight back, so it says
        // `banana` for the word `banana` — nothing about that spelling composed
        // it, and a resume armed on one holds the field's text where its keys
        // belong. `compose` is the question actually being asked: does this
        // spelling make that word?
        if (compose(raw, vni).first != word) return null
        // The word's own first key has to spell its first letter as well, or
        // the resume arms a region whose first backspace is not the letter it
        // looks like. `Web` is spelled by its own keys now that a leading `w`
        // no ư-syllable continues is the letter (#467) — but that reading is
        // for a word still being typed, and the `W` on its own is ư: backspacing
        // the resumed word would show `Ư` where the field held `W`, which is
        // the harm this guard is here for. No Vietnamese word opens with `w`,
        // so nothing the language spells is turned away by it.
        val head = word.first().toString()
        return if (compose(head, vni).first == head) raw else null
    }

    /**
     * [buffer] with the last letter of its *output* taken off — what one
     * backspace has to remove so that a press takes back a letter rather than a
     * key.
     *
     * The keys and the letters do not shrink together: `hướng` is five letters
     * on seven keys. `hif` is `hì`, and a press has to take the whole `ì` —
     * tone and all — rather than spending itself on the `f` and leaving `hi`.
     * `huowngs` is `hướng`, where the same press takes the `g` and leaves the
     * tone riding the `ơ`: what comes off is the letter, and a tone key goes
     * only when the letter it marks is the one going.
     *
     * So the letter is taken off the output and the keys are spelled back for
     * the shorter word ([toKeystrokes]). When that word has no spelling — a
     * Latin one the engine would rewrite, or an output the keys cannot produce
     * — the keys are cut back until the output matches, and failing that one
     * key goes, which is what every other composer does.
     */
    internal fun backspace(buffer: String, vni: Boolean): String {
        if (buffer.isEmpty()) return buffer
        val text = transduce(buffer, vni)
        if (text.isNotEmpty()) {
            val shorter = text.dropLast(1)
            if (shorter.isEmpty()) return ""
            toKeystrokes(shorter, vni)?.let { return it }
            for (cut in 1 until buffer.length) {
                val candidate = buffer.dropLast(cut)
                if (transduce(candidate, vni) == shorter) return candidate
            }
        }
        return buffer.dropLast(1)
    }

    /**
     * The key [mark] is spelled with on [base] in the given method, or null when
     * that letter cannot carry it — `o6` is a circumflex, `e6` is not a letter
     * at all. Telex spells a letter mark with a letter (`oo`, `aw`, `uw`) and
     * VNI with a digit, which is the same key its own transducer reads.
     */
    private fun markKey(base: Char, mark: VMark, vni: Boolean): String? = when (mark) {
        VMark.CIRCUMFLEX -> when (base) {
            'a', 'e', 'o' -> if (vni) "6" else base.toString()
            else -> null
        }
        VMark.BREVE -> if (base == 'a') (if (vni) "8" else "w") else null
        VMark.HORN -> if (base == 'o' || base == 'u') (if (vni) "7" else "w") else null
        VMark.STROKE -> if (base == 'd') (if (vni) "9" else "d") else null
        VMark.NONE -> null
    }

    /** The key [tone] is spelled with, in the method that reads it as a tone. */
    private fun toneKey(tone: VTone, vni: Boolean): Char? = when (tone) {
        VTone.NONE -> null
        VTone.ACUTE -> if (vni) '1' else 's'
        VTone.GRAVE -> if (vni) '2' else 'f'
        VTone.HOOK -> if (vni) '3' else 'r'
        VTone.TILDE -> if (vni) '4' else 'x'
        VTone.DOT -> if (vni) '5' else 'j'
    }

    /**
     * Whether a coda follows the `u`,`o` pair ending at [oIdx] of [letters] —
     * one already behind the pair in the buffer, or the next one [raw] still
     * has to type after the `w` at [wIndex].
     *
     * A coda is what a `w` can see of the syllable still to come, and `huown` is
     * `hươn` where `huow` is `huơ`. It is not the whole of the question, only
     * the part answerable at the key: an onset the pair cannot follow (`nguow`)
     * and a vowel after it (`huowif`) settle the same way, and both are read
     * once the letters are all in — see [compose]. A tone key is not a coda —
     * `thuowr` is `thuở`, whose `u` stays plain — so the lookahead steps over
     * [TONE_KEYS]; a `w` is not one either, which is what leaves `huoww` free
     * to horn the `u` on its second press.
     */
    private fun codaFollows(
        letters: List<VLetter>,
        oIdx: Int,
        raw: String,
        wIndex: Int,
    ): Boolean {
        if (letters.drop(oIdx + 1).any { !isVowel(it.base) }) return true
        for (i in wIndex + 1 until raw.length) {
            val c = raw[i].lowercaseChar()
            if (c in TONE_KEYS || isToneChar(c)) continue
            return c in CODA_HEADS
        }
        return false
    }

    /**
     * The index of a letter [mark] may go on even though it is not the one in
     * front of the key, or -1 when there is none.
     *
     * A Telex mark key names the letter it is spelled with rather than the
     * letter before it: `dod` is `đo` and `tono` is `tôn`, where the key and
     * its letter are separated by a vowel and a coda. The search runs backwards
     * so the nearest letter has the first say, and a letter already carrying
     * that mark is passed over — a second key with nothing left to mark is the
     * letter it is drawn as.
     *
     * Only a letter that leaves the word one Vietnamese could still spell
     * counts ([VietnameseOrthography.isSyllablePrefix]), which is what keeps
     * `hello` out of it: it has no letter this key could mark and still be a
     * word's beginning. `banana` does (`bân` begins a syllable), so its second
     * `a` is reached and the word reads `bânna`; only the strict rule
     * ([VietnameseConfig.strictTones]) hands it back as typed.
     */
    private fun distantMarkTarget(letters: List<VLetter>, base: Char, mark: VMark): Int {
        for (i in letters.indices.reversed()) {
            val letter = letters[i]
            if (letter.base != base || letter.mark == mark) continue
            val was = letter.mark
            letter.mark = mark
            val valid = VietnameseOrthography.isSyllablePrefix(render(letters, VTone.NONE))
            letter.mark = was
            if (valid) return i
        }
        return -1
    }

    /** Apply [mark] to the last letter whose base is in [targets]; returns success. */
    /**
     * Takes [mark] off the last of [targets] when that letter carries it, for
     * VNI's repeated mark digit (#1490); false, changing nothing, otherwise.
     */
    private fun unmarkLast(letters: List<VLetter>, targets: String, mark: VMark): Boolean {
        val last = letters.lastOrNull { it.base in targets } ?: return false
        if (last.mark != mark) return false
        last.mark = VMark.NONE
        return true
    }

    /**
     * Whether the word ends in a bare `ua` whose `u` is a letter of the
     * nucleus rather than a `qu` glide: the pair a Telex `w` horns into `ưa`
     * (#1561). Bare on both letters, so a `uâ` or a `ưa` already spelled is
     * not this case.
     */
    private fun openUaPair(letters: List<VLetter>): Boolean {
        if (letters.size < 2) return false
        val a = letters[letters.lastIndex]
        val u = letters[letters.lastIndex - 1]
        return a.base == 'a' && a.mark == VMark.NONE &&
            u.base == 'u' && u.mark == VMark.NONE &&
            !isQuGlide(letters, letters.lastIndex - 1)
    }

    private fun applyMark(letters: List<VLetter>, targets: String, mark: VMark): Boolean {
        for (i in letters.indices.reversed()) {
            if (letters[i].base in targets) {
                // A second press of the same mark key cancels it (Telex `aa` then
                // `a`, VNI double 6): toggle back to plain.
                letters[i].mark = if (letters[i].mark == mark) VMark.NONE else mark
                return true
            }
        }
        return false
    }

    /**
     * The composed word for a buffer, tone keys read as tones.
     *
     * With [VietnameseConfig.strictTones] on, two things change. A tone key
     * marks the word only while the word in hand could still become one
     * Vietnamese spells, and a word that carries a Vietnamese mark but is
     * neither a syllable nor the start of one is given back as the keys that
     * were typed. `fas` is `fá` by the loose rule and `fas` by this one, because
     * `f` is not an onset and there was never a word there to tone.
     *
     * Both are asked of the word in hand at each key, never of the finished
     * word, and that is the whole design. A rule that waited for the end could
     * not answer for `nuocsw`: at the `s` the word is `nuoc`, whose nucleus is
     * only finished two keys later by the `w`, and `nuoc` is a prefix of `nước`
     * even though it is not a syllable. See
     * [VietnameseOrthography.isSyllablePrefix].
     *
     * The last resort is the raw keys rather than the letters with their marks
     * taken off, because the two are not the same word: `rhees` was never `rhês`
     * with a mark removed, and giving back `rhees` is what leaves the user with
     * what they typed. A word with no Vietnamese mark in it is left alone
     * entirely, which is what keeps the repeated-key cancellation: `hass` is
     * `has` and stays `has`, because there is no mark there for the keyboard to
     * have made.
     */
    fun transduce(raw: String, vni: Boolean): String {
        val composed = compose(raw, vni).first
        if (!VietnameseConfig.strictTones) return composed
        if (!VietnameseOrthography.hasVietnameseMark(composed)) return composed
        if (VietnameseOrthography.isSyllable(composed)) return composed
        if (VietnameseOrthography.isSyllablePrefix(composed)) return composed
        // `dd` is how `đc`, `đt` and `đh` are typed: abbreviations, not words,
        // and no one of them a syllable, so every one of them was spelled back
        // as its keys. bamboo leaves a `đ` standing when the word has no vowel
        // in it at all, for exactly this reason — `IBddFreeStyle`, on by
        // default there — and this is that exception. A vowel puts the word
        // back under the rule, so `đi`, `đo` and `đường` are answered as they
        // were.
        if (!hasVowel(composed) &&
            (composed.contains('đ') || composed.contains('Đ') ||
                composed.endsWith('d') || composed.endsWith('D'))
        ) {
            return composed
        }
        return raw
    }

    /**
     * Whether [word] has a vowel in it — the test bamboo's `dd` exception turns
     * on. Read off the decomposed form, so `đ` and the consonants around it say
     * no however they are written.
     */
    private fun hasVowel(word: String): Boolean =
        Normalizer.normalize(word, Normalizer.Form.NFD).any { it.lowercaseChar() in "aeiouy" }

    /**
     * One pass of the rules. The letter marks (`aa`→`â`, `dd`→`đ`, `w`→`ư`) are
     * applied whatever the strict rule says: a word that is not Vietnamese is
     * answered for whole by [transduce], not key by key.
     */
    private fun compose(raw: String, vni: Boolean): Pair<String, VTone> {
        val letters = ArrayList<VLetter>()
        var tone = VTone.NONE

        fun toggleTone(t: VTone) { tone = if (tone == t) VTone.NONE else t }

        /**
         * Whether the vowels typed so far form one unbroken run.
         *
         * A Vietnamese syllable has exactly one vowel nucleus, so a tone key
         * after a broken run is not a tone at all — it is a letter, in a word
         * this composer has no business toning. "banana" + s stays `bananas`
         * rather than becoming `bánána`, and no real syllable is caught by it:
         * nguyễn, khuỷu and ngoèo all keep their vowels together.
         *
         * Counted rather than collected: this runs on every tone keystroke.
         */
        fun hasVowelCluster(): Boolean {
            var first = -1
            var last = -1
            var count = 0
            for (i in letters.indices) {
                if (isVowel(letters[i].base)) {
                    if (first < 0) first = i
                    last = i
                    count++
                }
            }
            return count > 0 && last - first + 1 == count
        }

        /**
         * Whether a tone key may mark the letters so far: the vowel-run rule the
         * keyboard has always had, and — with [VietnameseConfig.strictTones] on
         * — the word in hand being one Vietnamese could still spell.
         *
         * Asked of the letters with no tone on them, because a tone is a
         * property of the whole syllable and says nothing about whether the
         * letters are one yet.
         */
        fun toneAllowed(): Boolean =
            hasVowelCluster() &&
                (!VietnameseConfig.strictTones ||
                    VietnameseOrthography.isSyllablePrefix(render(letters, VTone.NONE)))

        for ((index, ch) in raw.withIndex()) {
            val upper = ch.isUpperCase()
            val lc = ch.lowercaseChar()
            // A tone typed as itself, from the tone key's own ring rather than
            // spelled with a letter or a digit. Shared by both methods: the key
            // is on both layouts, and a mark means the same thing on each.
            //
            // The ring's faces are written on a dotted circle, so a press sends
            // U+25CC and then the mark; the circle is swallowed here, which also
            // makes the bare circle the ring's "no tone" entry. Named outright,
            // a tone does not toggle — pressing acute twice means acute.
            if (lc == DOTTED_CIRCLE) { tone = VTone.NONE; continue }
            val direct = directTone(lc)
            if (direct != null) {
                if (toneAllowed()) tone = direct
                continue
            }
            if (vni) {
                // A digit that cannot do its job is a digit. Every branch here
                // falls through to the literal append when there is nothing to
                // tone or nothing to mark — otherwise a number typed inside a
                // word (`banana1`, an address, a model name) would silently
                // lose its digits to a tone that had nowhere to land.
                // The same digit again takes its mark back off *and* types the
                // digit (#1490), as a repeated Telex key types its letter: `ha22`
                // is `ha2`, the way Samsung's and Laban Key's VNI answer it, so a
                // user name or an address with a digit in it can be typed.
                val again = when (lc) {
                    '1' -> VTone.ACUTE
                    '2' -> VTone.GRAVE
                    '3' -> VTone.HOOK
                    '4' -> VTone.TILDE
                    '5' -> VTone.DOT
                    else -> null
                }
                if (again != null && tone == again) {
                    tone = VTone.NONE
                    letters.add(VLetter(lc, VMark.NONE, upper))
                    continue
                }
                val markAgain = when (lc) {
                    '6' -> unmarkLast(letters, "aeo", VMark.CIRCUMFLEX)
                    '7' -> unmarkLast(letters, "ou", VMark.HORN)
                    '8' -> unmarkLast(letters, "a", VMark.BREVE)
                    '9' -> unmarkLast(letters, "d", VMark.STROKE)
                    else -> false
                }
                if (markAgain) {
                    letters.add(VLetter(lc, VMark.NONE, upper))
                    continue
                }
                when (lc) {
                    '1', '2', '3', '4', '5' -> if (toneAllowed()) {
                        toggleTone(requireNotNull(again))
                        continue
                    }
                    // `0` is VNI's XoaDauThanh, the rule Telex spells with `z`:
                    // the digit takes the tone off and types nothing of its own,
                    // and the tone is all it takes — `viet650` is `viêt`, whose
                    // `ê` is a letter. A word with no tone has nothing to take,
                    // and then the key is the digit it is drawn as: `a0` is
                    // `a0`, `toan0` is `toan0`, the way a digit that has no
                    // vowel to tone is left alone two branches up. Clearing a
                    // tone is not marking one, so the strict rule has nothing to
                    // say about it either way.
                    '0' -> if (tone != VTone.NONE) { tone = VTone.NONE; continue }
                    '6' -> { if (applyMark(letters, "aeo", VMark.CIRCUMFLEX)) continue }
                    '7' -> { if (applyMark(letters, "ou", VMark.HORN)) continue }
                    '8' -> { if (applyMark(letters, "a", VMark.BREVE)) continue }
                    '9' -> { if (applyMark(letters, "d", VMark.STROKE)) continue }
                }
                letters.add(VLetter(lc, VMark.NONE, upper))
                continue
            }
            // Telex
            when (lc) {
                's', 'f', 'r', 'x', 'j' -> {
                    val t = when (lc) {
                        's' -> VTone.ACUTE; 'f' -> VTone.GRAVE; 'r' -> VTone.HOOK
                        'x' -> VTone.TILDE; else -> VTone.DOT
                    }
                    if (hasVowelCluster()) {
                        if (tone == t) {
                            // Repeating the tone key takes the mark back off and
                            // types the letter. That is the key's own effect and
                            // not a mark being placed, so the strict rule has no
                            // say in it — which is what leaves `has` for `hass`.
                            tone = VTone.NONE
                            letters.add(VLetter(lc, VMark.NONE, upper))
                        } else if (toneAllowed()) {
                            tone = t
                        } else {
                            letters.add(VLetter(lc, VMark.NONE, upper))
                        }
                    } else {
                        letters.add(VLetter(lc, VMark.NONE, upper))
                    }
                }
                'z' -> {
                    // Telex's tone-removal key, bamboo's `XoaDauThanh`: it takes
                    // the tone off the word and types nothing of its own —
                    // `toansz` is `toan`, `nuocswz` is `nươc`. The tone is all
                    // it takes: `â`, `ơ`, `ư` and `đ` are letters rather than
                    // tones, so `aaz` keeps its circumflex and the key is typed
                    // after it.
                    //
                    // A word with no tone has nothing to take, and then the key
                    // is the letter it is drawn as: `toanz` is `toanz`, `zz` is
                    // `zz`. That is what keeps `z` typable at all, and it is
                    // what bamboo does with a key that finds no tone to take.
                    //
                    // The tone is the buffer's tone, wherever in the buffer it
                    // landed, and that is the one place this differs from
                    // bamboo: `afdz` is `ad` here and `àdz` there, the key
                    // typed rather than spent because bamboo works on one
                    // syllable at a time — `newComposition` keeps
                    // `extractLastSyllable`, so a tone behind a tail it cannot
                    // read is out of the key's reach and the key finds no tone
                    // to take. Across the whole buffer is what every other key
                    // in this engine reads, and the strict pass is no help
                    // either: it hands a word back as its keys only while the
                    // word still carries a mark, and this read leaves none.
                    if (tone != VTone.NONE) tone = VTone.NONE
                    else letters.add(VLetter(lc, VMark.NONE, upper))
                }
                'w' -> {
                    // Horn on a uo pair -> ươ (nuocsw -> nước, huowng -> hương),
                    // and the `o` takes it first: `huơ` and `hương` are the same
                    // keys until the coda lands, and `huơ`, `quơ`, `thuở` are
                    // words too. With no coda behind the pair and none coming —
                    // nothing after the w but tone keys — the open `uơ` is
                    // meant, so only the `o` is horned here and the `u` is left
                    // for [compose] to decide on the finished letters, which is
                    // where an onset the language will not spell `uơ` after
                    // (`nguow`) and a vowel still to come (`huowif`) have their
                    // say. Otherwise horn/breve on the last a/o/u; a bare w
                    // types ư.
                    //
                    // A second w takes the mark back off *and* types the letter,
                    // which is what makes an English word survive the Telex
                    // layout: row, draw, show and flow are all a marked vowel
                    // plus a w that has nowhere else to go. Undoing the mark
                    // without typing the w left `ro` for `roww`.
                    val uIdx = letters.indexOfLast { it.base == 'u' }
                    val oIdx = letters.indexOfLast { it.base == 'o' }
                    if (uIdx != -1 && oIdx != -1 && oIdx == uIdx + 1) {
                        // A `u` that is a `qu` glide ([isQuGlide]) is not a
                        // letter of the pair's nucleus, so the pair has no `ươ`
                        // in it to spell and the open reading holds whatever the
                        // coda. A `u` already carrying a horn is a pair the user
                        // spelled himself and is not this case at all: the
                        // branches below ask for a bare `u` and send `quwow` to
                        // the last one, which horns both.
                        val glide = isQuGlide(letters, uIdx)
                        if (letters[uIdx].mark == VMark.HORN && letters[oIdx].mark == VMark.HORN) {
                            letters[uIdx].mark = VMark.NONE
                            letters[oIdx].mark = VMark.NONE
                            letters.add(VLetter('w', VMark.NONE, upper))
                        } else if (letters[oIdx].mark == VMark.HORN) {
                            // The pair's first w horned the `o` alone, there
                            // being no coda in sight then; this one follows
                            // through on the `u`. The glide takes no horn, so
                            // there the first w simply comes back off: `quoww`
                            // is `quow`.
                            if (glide) {
                                letters[oIdx].mark = VMark.NONE
                                letters.add(VLetter('w', VMark.NONE, upper))
                            } else {
                                letters[uIdx].mark = VMark.HORN
                            }
                        } else if (letters[uIdx].mark == VMark.NONE &&
                            (glide || !codaFollows(letters, oIdx, raw, index))
                        ) {
                            // Nothing says `ươ` yet: the coda that would, is
                            // not there and is not coming, and the `u` was not
                            // horned by a key of its own. The open `uơ` is what
                            // the keys spell — and the glide is always this
                            // case, a coda or not.
                            letters[oIdx].mark = VMark.HORN
                        } else {
                            letters[uIdx].mark = VMark.HORN
                            letters[oIdx].mark = VMark.HORN
                        }
                    } else {
                        val marked = letters.indexOfLast {
                            (it.base == 'a' && it.mark == VMark.BREVE) ||
                                ((it.base == 'o' || it.base == 'u') && it.mark == VMark.HORN)
                        }
                        if (marked != -1) {
                            // A ư the engine spelled from a bare w was never
                            // typed, so its letter goes with the mark: `ww` is
                            // w, not the `uw` a stranded u would leave. A mark
                            // on a vowel the user typed keeps its letter, which
                            // is what leaves `row` for `roww`.
                            // A ư the engine spelled from a bare w stands for
                            // that w, so the letter taking it back *is* that
                            // letter and keeps its case: at the start of a
                            // sentence `Ww` is `W`, not the lower-case `w` the
                            // second key is drawn as — the keyboard capitalised
                            // the first key and not the ones after it.
                            val taken = letters[marked].synthesized
                            val replacementUpper = if (taken) letters[marked].upper else upper
                            if (taken) letters.removeAt(marked)
                            else letters[marked].mark = VMark.NONE
                            letters.add(VLetter('w', VMark.NONE, replacementUpper))
                        } else if (openUaPair(letters)) {
                            // `muaw` is `mưa` (#1561), the way Unikey and
                            // bamboo read it: an open `ua` has no `ă` in it to
                            // spell, `ă` never closing a syllable, and `ưa` is
                            // the open pair (mưa, cửa, lửa, thưa). The breve
                            // the plain branch below puts on the `a` is only
                            // right behind a `qu` glide (`quaw` is `quă`, on
                            // its way to `quăng`) or with a coda already
                            // typed. The second w takes the horn back off in
                            // the `marked` branch above, so `muaww` is `muaw`.
                            letters[letters.lastIndex - 1].mark = VMark.HORN
                        } else {
                            val applied = applyMark(letters, "a", VMark.BREVE) ||
                                applyMark(letters, "ou", VMark.HORN)
                            // A bare w is ư, which is Telex as it is written —
                            // but only the first of a run. The w after it takes
                            // that ư back and types the letter (above), and every
                            // w after *that* is the letter too: holding the key
                            // types a run of `w`s, rather than ư returning on
                            // every second press and leaving `wư`, `ww`, `wư`…
                            if (!applied) {
                                // A w after a w is that key's own letter, and
                                // takes its own case: only the first key of a
                                // sentence is capitalised, so `Www` is `Ww` —
                                // the capital on the first `w` and nowhere else.
                                if (letters.lastOrNull()?.base == 'w') {
                                    letters.add(VLetter('w', VMark.NONE, upper))
                                } else {
                                    letters.add(VLetter('u', VMark.HORN, upper, synthesized = true))
                                }
                            }
                        }
                    }
                }
                'a', 'e', 'o' -> {
                    val last = letters.lastOrNull()
                    if (last != null && last.base == lc) {
                        if (last.mark == VMark.CIRCUMFLEX) {
                            last.mark = VMark.NONE
                            letters.add(VLetter(lc, VMark.NONE, upper))
                        } else {
                            last.mark = VMark.CIRCUMFLEX
                        }
                    } else {
                        // Not the letter in front — the key may still name one
                        // further back, as it does in `tono` (`tôn`) and
                        // `nana` (`nân`).
                        val target = distantMarkTarget(letters, lc, VMark.CIRCUMFLEX)
                        if (target >= 0) letters[target].mark = VMark.CIRCUMFLEX
                        else letters.add(VLetter(lc, VMark.NONE, upper))
                    }
                }
                'd' -> {
                    val last = letters.lastOrNull()
                    if (last != null && last.base == 'd') {
                        if (last.mark == VMark.STROKE) {
                            last.mark = VMark.NONE
                            letters.add(VLetter('d', VMark.NONE, upper))
                        } else {
                            last.mark = VMark.STROKE
                        }
                    } else {
                        // `dod` is `đo`: the stroke lands on the `d` the key is
                        // spelled with, not on the vowel in front of it.
                        val target = distantMarkTarget(letters, 'd', VMark.STROKE)
                        if (target >= 0) letters[target].mark = VMark.STROKE
                        else letters.add(VLetter('d', VMark.NONE, upper))
                    }
                }
                else -> letters.add(VLetter(lc, VMark.NONE, upper))
            }
        }

        // `uơ` or `ươ`: the horn goes on the `o` first, and the `u` only takes it
        // too when the open reading is not one the language spells. `nguơ` is
        // not — `uơ` is spelled after `c h k kh qu th` and nowhere else — so the
        // pair in `nguowif` is `ươ` and the word is `người`, not `nguời`.
        //
        // Asked of the finished letters rather than at the `w`, because what
        // condemns the open reading may be typed after the key: `huow` alone is
        // `huơ`, and the `ng` in `huowng` is what makes that one `hương`. The
        // `w` can only look ahead for a coda ([codaFollows]); an onset it has
        // already seen it cannot weigh, and a vowel coming after it (`huowif`)
        // it does not read as one.
        //
        // A `u` the user horned himself is left alone: `uwow` is `ươ` on
        // purpose, and the tone — if the word is carrying one — says nothing
        // about how the pair is spelled, so it is read off the letters bare.
        // A `qu` glide is left alone too: it is the onset's, not the nucleus's,
        // and a pair it leaves is `ơ` alone however little that spells —
        // reading it as a hornable `u` is what would turn `quơu` into `qươu`.
        val hornedO = letters.indexOfLast { it.base == 'o' && it.mark == VMark.HORN }
        if (hornedO > 0 && letters[hornedO - 1].base == 'u' &&
            letters[hornedO - 1].mark == VMark.NONE &&
            !isQuGlide(letters, hornedO - 1) &&
            !VietnameseOrthography.isSyllable(render(letters, VTone.NONE))
        ) {
            letters[hornedO - 1].mark = VMark.HORN
        }

        // The same pair the other way round. A `w` that horned the `u` before the
        // `o` was typed leaves `ưo`, which is not a nucleus either — the horned
        // pair is `ươ`, and `uwong`, `uwoc` and `uwoi` are `ương`, `ươc` and
        // `ươi`. A letter after the pair is what says so, the way it is for the
        // `uơ` above; with nothing after it the `ưo` stands (`uwo` is `ưo`),
        // which is the one reading where the two letters are left as typed.
        //
        // No onset can refuse this one — `ưo` is `ươ` wherever it occurs — so
        // there is nothing here to ask [VietnameseOrthography] about, only the
        // `qu` glide, whose `u` is the onset's and whose pair is `ơ` alone.
        val hornedU = letters.indexOfLast { it.base == 'u' && it.mark == VMark.HORN }
        if (hornedU >= 0 && hornedU + 2 <= letters.lastIndex &&
            letters[hornedU + 1].base == 'o' &&
            letters[hornedU + 1].mark == VMark.NONE &&
            !isQuGlide(letters, hornedU)
        ) {
            letters[hornedU + 1].mark = VMark.HORN
        }

        // A `w` that opened a word is a `ư` only while the word can still be
        // one the language spells.
        keepEnglishW(letters, tone)
        return render(letters, tone) to tone
    }

    /**
     * Gives back the `w` a word opened with when what follows cannot continue a
     * `ư` (#467): `why`, `when`, `we` and `with` are English typed on the Telex
     * layout, and `ưhy` is nothing.
     *
     * Only a leading `w` is asked about, and only one the engine spelled for
     * the key ([VLetter.synthesized]) — a `u` the user typed is his own letter
     * whatever follows it, and `uw` is ư on purpose. A leading `w` is where
     * English puts the letter and where Vietnamese has the least to lose: `ưa`,
     * `ưng`, `ưu` and `ước` all go on to a letter from [AFTER_BARE_U].
     *
     * The letter behind it is only half the question. `ưo` is `ươ` one keystroke
     * before its horn, so an `o` is never read as English — but `ưo` is not a
     * syllable either, and neither is `ưi`, which opens `gửi` and `chửi`. A
     * word of three letters or more is therefore asked of
     * [VietnameseOrthography.isSyllable] whole, which is what `with` needs and
     * `ưng` must survive.
     */
    private fun keepEnglishW(letters: List<VLetter>, tone: VTone) {
        val first = letters.firstOrNull() ?: return
        if (!first.synthesized || first.mark != VMark.HORN || letters.size < 2) return
        val next = letters[1].base
        val english = next !in AFTER_BARE_U ||
            (letters.size >= 3 && next != 'o' &&
                !VietnameseOrthography.isSyllable(render(letters, tone)))
        if (english) {
            first.base = 'w'
            first.mark = VMark.NONE
        }
    }
}

/** Vietnamese Telex: letters spell the diacritics (`as`→á, `aw`→ă, `dd`→đ). */
object VietnameseTelexComposer : Composer {
    override val isTransliterating: Boolean get() = true
    override val resumesComposedText: Boolean get() = true
    override fun resumeBuffer(text: String): String? = VietnameseEngine.toKeystrokes(text, vni = false)
    override fun backspaceBuffer(buffer: String): String = VietnameseEngine.backspace(buffer, vni = false)
    // The tone key sends combining marks, which are not letters: without this
    // the key would commit the syllable and type a stray mark after it.
    override fun buffersChar(c: Char): Boolean = VietnameseEngine.isToneChar(c)
    override fun isPlausibleWord(word: String): Boolean = VietnameseOrthography.isSyllable(word)
    override fun composeBuffer(buffer: String): String = VietnameseEngine.transduce(buffer, vni = false)
}

/** Vietnamese VNI: digits spell the diacritics (`a8`→ă, `a1`→á, `d9`→đ). */
object VietnameseVniComposer : Composer {
    override val isTransliterating: Boolean get() = true
    override val resumesComposedText: Boolean get() = true
    override fun resumeBuffer(text: String): String? = VietnameseEngine.toKeystrokes(text, vni = true)
    override fun backspaceBuffer(buffer: String): String = VietnameseEngine.backspace(buffer, vni = true)
    override val bufferDigits: Boolean get() = true
    override fun buffersChar(c: Char): Boolean = VietnameseEngine.isToneChar(c)
    override fun isPlausibleWord(word: String): Boolean = VietnameseOrthography.isSyllable(word)
    override fun composeBuffer(buffer: String): String = VietnameseEngine.transduce(buffer, vni = true)
}
