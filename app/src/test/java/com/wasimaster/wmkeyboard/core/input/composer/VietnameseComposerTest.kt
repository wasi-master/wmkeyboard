package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM Unit tests for Vietnamese Telex and VNI transliteration engine.
 * Fast local test execution without requiring ADB or Android devices.
 */
class VietnameseComposerTest {

    private val latinScript = ScriptRegistry[ScriptId.LATIN]

    @Test
    fun factoryMapsTelexAndVniComposers() {
        val telex = composerFor(latinScript, ComposerType.TELEX)
        val vni = composerFor(latinScript, ComposerType.VNI)

        assertNotNull(telex)
        assertTrue(telex is VietnameseTelexComposer)
        assertTrue(telex.isTransliterating)

        assertNotNull(vni)
        assertTrue(vni is VietnameseVniComposer)
        assertTrue(vni.isTransliterating)
        assertTrue(vni.bufferDigits)
    }

    // --- Telex Tests ---

    @Test
    fun telexBasicTones() {
        val c = VietnameseTelexComposer
        assertEquals("á", c.composeBuffer("as"))
        assertEquals("à", c.composeBuffer("af"))
        assertEquals("ả", c.composeBuffer("ar"))
        assertEquals("ã", c.composeBuffer("ax"))
        assertEquals("ạ", c.composeBuffer("aj"))
    }

    @Test
    fun telexLetterMarks() {
        val c = VietnameseTelexComposer
        assertEquals("â", c.composeBuffer("aa"))
        assertEquals("ă", c.composeBuffer("aw"))
        assertEquals("ê", c.composeBuffer("ee"))
        assertEquals("ô", c.composeBuffer("oo"))
        assertEquals("ơ", c.composeBuffer("ow"))
        assertEquals("ư", c.composeBuffer("uw"))
        assertEquals("ư", c.composeBuffer("w"))
        assertEquals("đ", c.composeBuffer("dd"))
    }

    @Test
    fun telexBareWUndoTakesTheLetterWithIt() {
        val c = VietnameseTelexComposer
        // A bare w is ư, but the u it is spelled with was never typed. Undoing
        // the mark has to take that letter away with it, or the u is stranded:
        // `ww` is a plain w, not the `uw` a leftover u would leave behind.
        assertEquals("w", c.composeBuffer("ww"))
        // A u the user did type is theirs to keep, so the two-key spelling of ư
        // still round-trips to the two letters it was typed as.
        assertEquals("uw", c.composeBuffer("uww"))
    }

    @Test
    fun telexFullSyllables() {
        val c = VietnameseTelexComposer
        assertEquals("việt", c.composeBuffer("vieejt"))
        assertEquals("tiếng", c.composeBuffer("tieengs"))
        assertEquals("đây", c.composeBuffer("ddaay"))
        assertEquals("nước", c.composeBuffer("nuocsw"))
        assertEquals("quả", c.composeBuffer("quar"))
    }

    @Test
    fun telexToneAndMarkCancellation() {
        val c = VietnameseTelexComposer
        assertEquals("as", c.composeBuffer("ass"))
        assertEquals("af", c.composeBuffer("aff"))
        assertEquals("dd", c.composeBuffer("ddd"))
        assertEquals("aa", c.composeBuffer("aaa"))
        assertEquals("ee", c.composeBuffer("eee"))
        assertEquals("oo", c.composeBuffer("ooo"))
    }

    @Test
    fun telexZTakesTheToneBackOff() {
        val c = VietnameseTelexComposer
        // `z` is Telex's tone-removal key — bamboo's `XoaDauThanh`. It takes
        // the tone off the word and types nothing of its own, so `toansz` is
        // `toan` and the word can be toned again straight after it.
        assertEquals("toan", c.composeBuffer("toansz"))
        assertEquals("a", c.composeBuffer("asz"))
        assertEquals("a", c.composeBuffer("afz"))
        assertEquals("qua", c.composeBuffer("quarz"))
        assertEquals("toán", c.composeBuffer("toanszs"))
        assertEquals("dong", c.composeBuffer("dongsz"))
        // The tone is all it takes. `â`, `ơ`, `ư` and `đ` are letters rather
        // than tones, so they stay where they are.
        assertEquals("nươc", c.composeBuffer("nuocswz"))
        assertEquals("viêt", c.composeBuffer("vieejtz"))
        assertEquals("tiêng", c.composeBuffer("tieengsz"))
        assertEquals("đuong", c.composeBuffer("dduongsz"))
        assertEquals("âz", c.composeBuffer("aaz"))
    }

    @Test
    fun telexZWithNoToneToTakeIsTheLetter() {
        val c = VietnameseTelexComposer
        // A word with no tone has nothing to take, and then the key is the
        // letter it is drawn as: `toanz` is `toanz`, `zz` is `zz`. Only the
        // first `z` after a tone spends itself on the tone.
        assertEquals("toanz", c.composeBuffer("toanz"))
        assertEquals("toanz", c.composeBuffer("toanszz"))
        assertEquals("điz", c.composeBuffer("ddiz"))
        assertEquals("z", c.composeBuffer("z"))
        assertEquals("zz", c.composeBuffer("zz"))
        assertEquals("zzz", c.composeBuffer("zzz"))
        // Typed on a word that carries a mark but no tone, it is a letter too.
        assertEquals("êsz", c.composeBuffer("eessz"))
    }

    @Test
    fun telexZKeepsTheCaseOfTheWordItClears() {
        val c = VietnameseTelexComposer
        // The key types nothing when it takes a tone, so the word keeps the
        // case it had; typed as a letter it takes its own, like every key.
        assertEquals("Toan", c.composeBuffer("Toansz"))
        assertEquals("TOAN", c.composeBuffer("TOANSZ"))
        assertEquals("toan", c.composeBuffer("toansZ"))
    }

    @Test
    fun telexCapitalization() {
        val c = VietnameseTelexComposer
        assertEquals("Việt", c.composeBuffer("Vieejt"))
        assertEquals("Tiếng", c.composeBuffer("Tieengs"))
        assertEquals("ĐÂY", c.composeBuffer("DDAAY"))
    }

    @Test
    fun telexToneNeedsOneUnbrokenVowelRun() {
        val c = VietnameseTelexComposer
        // A Vietnamese syllable has exactly one vowel nucleus, so a tone key
        // after a broken run is the letter it is drawn as. The examples have no
        // letter a mark key could reach: `bananas` is no longer one of them,
        // since its second `a` is marked (`bânnas`, which the strict rule hands
        // back as `bananas` — see the reach test).
        assertEquals("cactus", c.composeBuffer("cactus"))
        assertEquals("relax", c.composeBuffer("relax"))
        assertEquals("inbox", c.composeBuffer("inbox"))
        // The rule catches nothing real: every syllable keeps its vowels
        // together, however many of them there are.
        assertEquals("nguyễn", c.composeBuffer("nguyeenx"))
        assertEquals("khuỷu", c.composeBuffer("khuyur"))
        assertEquals("ngoèo", c.composeBuffer("ngoeof"))
    }

    @Test
    fun telexSecondWTakesTheMarkOffAndTypesTheLetter() {
        val c = VietnameseTelexComposer
        assertEquals("row", c.composeBuffer("roww"))
        assertEquals("draw", c.composeBuffer("draww"))
        assertEquals("show", c.composeBuffer("showw"))
        assertEquals("flow", c.composeBuffer("floww"))
        assertEquals("ow", c.composeBuffer("oww"))
        assertEquals("uw", c.composeBuffer("uww"))
        // A w typed on its own made the ư, so a second one gives the w back (#467).
        assertEquals("w", c.composeBuffer("ww"))
        assertEquals("W", c.composeBuffer("Ww"))
        assertEquals("why", c.composeBuffer("wwhy"))
        // English behind a leading w: nothing Vietnamese goes from ư to these.
        assertEquals("why", c.composeBuffer("why"))
        assertEquals("when", c.composeBuffer("when"))
        assertEquals("we", c.composeBuffer("we"))
        assertEquals("with", c.composeBuffer("with"))
        // Vietnamese that opens with ư is untouched.
        assertEquals("ưa", c.composeBuffer("wa"))
        assertEquals("ưng", c.composeBuffer("wng"))
        assertEquals("ước", c.composeBuffer("wowcs"))
        assertEquals("ức", c.composeBuffer("wcs"))
        // The uo cluster behaves the same way, both marks at once.
        assertEquals("dương", c.composeBuffer("duongw"))
        assertEquals("đương", c.composeBuffer("dduongw"))
        assertEquals("duongw", c.composeBuffer("duongww"))
    }

    @Test
    fun telexMarkKeyReachesALetterThatIsNotAdjacent() {
        val c = VietnameseTelexComposer
        // A Telex mark key names the letter it is spelled with, not the letter
        // in front of it: `dod` is `đo` and `ddono` is `đôn`, where the second
        // `d` and the second `o` are separated from their letter by a vowel and
        // a coda. The rule only ever looked at the letter in front, so these
        // came out as typed.
        assertEquals("đo", c.composeBuffer("dod"))
        assertEquals("đa", c.composeBuffer("dad"))
        assertEquals("đôn", c.composeBuffer("ddono"))
        assertEquals("tôn", c.composeBuffer("tono"))
        assertEquals("tôt", c.composeBuffer("toto"))
        assertEquals("nân", c.composeBuffer("nana"))
    }

    @Test
    fun telexMarkKeyLeavesTheWordsThatWereAlreadyRight() {
        val c = VietnameseTelexComposer
        // The letter in front still has the first say, and the words that were
        // right stay right: a second key with nothing left to mark is the
        // letter it is drawn as, and a word with no such letter at all is left
        // alone.
        assertEquals("â", c.composeBuffer("aa"))
        assertEquals("aa", c.composeBuffer("aaa"))
        assertEquals("aâ", c.composeBuffer("aaaa"))
        assertEquals("tông", c.composeBuffer("toong"))
        assertEquals("nghiêng", c.composeBuffer("nghieeng"))
        assertEquals("hello", c.composeBuffer("hello"))
        assertEquals("row", c.composeBuffer("roww"))
    }

    @Test
    fun telexMarkKeyReachUnderStrictTones() {
        // Under the strict rule the reached letters keep their mark, because
        // `đo`, `đôn`, `tôt` and `nân` are words the rules can still spell —
        // while the words that only look like them are given back as keys.
        val was = VietnameseConfig.strictTones
        VietnameseConfig.strictTones = true
        try {
            val c = VietnameseTelexComposer
            assertEquals("đo", c.composeBuffer("dod"))
            assertEquals("đôn", c.composeBuffer("ddono"))
            assertEquals("tôn", c.composeBuffer("tono"))
            assertEquals("tôt", c.composeBuffer("toto"))
            assertEquals("nân", c.composeBuffer("nana"))
            assertEquals("banana", c.composeBuffer("banana"))
            assertEquals("nanan", c.composeBuffer("nanan"))
            assertEquals("dodod", c.composeBuffer("dodod"))
        } finally {
            VietnameseConfig.strictTones = was
        }
    }

    @Test
    fun telexRunOfWTypesWAfterTheFirst() {
        val c = VietnameseTelexComposer
        // A bare `w` is ư, and the `w` after it takes that back and types the
        // letter. Every `w` after *that* is the letter too — holding the key
        // down types a run of `w`s, one shorter than the presses, rather than
        // ư coming back on every second key and leaving `wư`, `ww`, `wư`…
        assertEquals("ư", c.composeBuffer("w"))
        assertEquals("w", c.composeBuffer("ww"))
        assertEquals("ww", c.composeBuffer("www"))
        assertEquals("www", c.composeBuffer("wwww"))
        assertEquals("wwww", c.composeBuffer("wwwww"))
        assertEquals("wwwwwww", c.composeBuffer("wwwwwwww"))
        assertEquals("wwwwwwww", c.composeBuffer("wwwwwwwww"))
        // A `w` that horns a vowel is still that mark, whatever came before it.
        assertEquals("ă", c.composeBuffer("aw"))
        assertEquals("nước", c.composeBuffer("nuocsw"))
    }

    @Test
    fun telexRunOfWKeepsTheCaseOfTheKeyItTookBack() {
        // The `w` that takes back the `ư` a bare `w` made *is* that letter's
        // replacement, so it keeps the case that press had. At the start of a
        // sentence the engine capitalises the first key and not the ones after
        // it, so reading the case off the second key turned `WW` into `W`.
        // The keyboard capitalises the first key of a sentence and not the ones
        // after it, so the buffer really is `Ww` — not `WW`.
        val c = VietnameseTelexComposer
        assertEquals("Ư", c.composeBuffer("W"))
        assertEquals("W", c.composeBuffer("Ww"))
        // Only the first key of the sentence is capitalised, so the third `w`
        // — a lower-case key — types a lower-case letter: `Ww`, not `WW`.
        assertEquals("Ww", c.composeBuffer("Www"))
        assertEquals("We", c.composeBuffer("Wwe"))
        assertEquals("Web", c.composeBuffer("Wweb"))
        // Nothing changes for a word typed in lower case, or for a `ư` the user
        // horned himself with `uw`.
        assertEquals("ư", c.composeBuffer("w"))
        assertEquals("w", c.composeBuffer("ww"))
        assertEquals("ww", c.composeBuffer("www"))
        assertEquals("web", c.composeBuffer("wweb"))
        assertEquals("uw", c.composeBuffer("uww"))
    }

    @Test
    fun telexRunOfWReadsAsWUnderStrictTones() {
        // The same run, with the strict rule on. `ww` carries no Vietnamese
        // mark, so the strict pass leaves it alone — but `wư`, which the old
        // rule produced on every odd press, does, and the strict pass would
        // hand back the whole run of keys instead.
        val was = VietnameseConfig.strictTones
        VietnameseConfig.strictTones = true
        try {
            val c = VietnameseTelexComposer
            assertEquals("ư", c.composeBuffer("w"))
            assertEquals("w", c.composeBuffer("ww"))
            assertEquals("ww", c.composeBuffer("www"))
            assertEquals("www", c.composeBuffer("wwww"))
            assertEquals("wwww", c.composeBuffer("wwwww"))
            assertEquals("wwwwwww", c.composeBuffer("wwwwwwww"))
        } finally {
            VietnameseConfig.strictTones = was
        }
    }

    @Test
    fun telexHornsBothVowelsOfUoWhenACodaFollows() {
        val c = VietnameseTelexComposer
        // A `uo` pair takes the horn on both letters when a coda follows — the
        // coda being what tells `hương` (h-ư-ơ-ng) from `huơ` (h-u-ơ), since
        // the two are the same two keys up to that point. With nothing coming
        // after, only the `o` is horned, which is the word `huơ`, `quơ`, `thuở`
        // are spelled with. The coda is one half of the question, not the whole
        // of it: an onset that will not spell `uơ` horns the `u` too, coda or
        // none — see telexHornsTheUTooUnlessTheOnsetCanSpellTheOpenUo.
        assertEquals("huơ", c.composeBuffer("huow"))
        assertEquals("quơ", c.composeBuffer("quow"))
        assertEquals("thuở", c.composeBuffer("thuowr"))
        // The coda may arrive after the `w`, and then it counts.
        assertEquals("hươn", c.composeBuffer("huown"))
        assertEquals("hương", c.composeBuffer("huowng"))
        assertEquals("hướng", c.composeBuffer("huowngs"))
        // A tone key is not a coda: it rides the word without changing which
        // vowel the horn landed on.
        assertEquals("huờ", c.composeBuffer("huowf"))
        // A second `w` horns the `u` the first one left plain.
        assertEquals("hươ", c.composeBuffer("huoww"))
        // A `u` the user horned himself is not un-horned by the open reading:
        // `uwow` spells `ươ`, not the `ưo` a lone `o` horn would leave.
        assertEquals("ươ", c.composeBuffer("uwow"))
        // Unchanged: a coda already in the buffer counts the same as one ahead,
        // and the pair still toggles off on a second w when there is one.
        assertEquals("nước", c.composeBuffer("nuocsw"))
        assertEquals("dương", c.composeBuffer("duongw"))
        assertEquals("duongw", c.composeBuffer("duongww"))
        assertEquals("tương", c.composeBuffer("tuongw"))
    }

    @Test
    fun telexHornsTheUTooUnlessTheOnsetCanSpellTheOpenUo() {
        val c = VietnameseTelexComposer
        // A coda is not the only thing that tells `ươ` from the open `uơ`: the
        // onset is the other, and it is the one that decides `người`. `uơ` is
        // spelled after `c h k kh qu th` and nowhere else — there is no `nguơ`
        // — so the `u` takes the horn with the `o` however the syllable ends.
        assertEquals("ngươ", c.composeBuffer("nguow"))
        assertEquals("người", c.composeBuffer("nguowif"))
        assertEquals("ngươi", c.composeBuffer("nguowi"))
        assertEquals("ngương", c.composeBuffer("nguowng"))
        assertEquals("ngườ", c.composeBuffer("nguowf"))
        assertEquals("ngướ", c.composeBuffer("nguows"))
        assertEquals("bướng", c.composeBuffer("buowngs"))
        assertEquals("bười", c.composeBuffer("buowif"))
        assertEquals("dương", c.composeBuffer("duowng"))
        assertEquals("dường", c.composeBuffer("duowngf"))
        assertEquals("cường", c.composeBuffer("cuowngf"))
        assertEquals("xười", c.composeBuffer("xuowif"))
        // A vowel after the pair condemns the open reading just as a coda does,
        // and after an onset that *can* spell `uơ` as well: `huơ` is a word,
        // `hươi` is not.
        assertEquals("hười", c.composeBuffer("huowif"))
        assertEquals("tười", c.composeBuffer("tuowif"))
        assertEquals("hươu", c.composeBuffer("huowu"))
        assertEquals("ười", c.composeBuffer("uowif"))
        assertEquals("ường", c.composeBuffer("uowngf"))
        // And the onsets that can keep it, keep it.
        assertEquals("huơ", c.composeBuffer("huow"))
        assertEquals("quơ", c.composeBuffer("quow"))
        assertEquals("kuơ", c.composeBuffer("kuow"))
        assertEquals("khuơ", c.composeBuffer("khuow"))
        assertEquals("thuơ", c.composeBuffer("thuow"))
        assertEquals("thuở", c.composeBuffer("thuowr"))
        assertEquals("uơ", c.composeBuffer("uow"))
        // `qu` is the one onset whose `u` is a glide rather than the nucleus:
        // `quơ` is `qu` + `ơ`, and `ơ` takes any coda.
        assertEquals("quơng", c.composeBuffer("quowng"))
        assertEquals("quơi", c.composeBuffer("quowi"))
        assertEquals("quời", c.composeBuffer("quowif"))
        assertEquals("quow", c.composeBuffer("quoww"))
    }

    @Test
    fun telexHornsTheOTooWhenTheUWasHornedBeforeIt() {
        val c = VietnameseTelexComposer
        // `ưo` is no nucleus either, and the same pair read the other way: a `w`
        // pressed while the `u` stood alone horns it, and the `o` typed after it
        // is the half still missing. A letter behind the pair is what says the
        // two are one nucleus, exactly as it does for `uơ`.
        assertEquals("ương", c.composeBuffer("uwong"))
        assertEquals("ướng", c.composeBuffer("uwongs"))
        assertEquals("ươc", c.composeBuffer("uwoc"))
        assertEquals("ươi", c.composeBuffer("uwoi"))
        assertEquals("ười", c.composeBuffer("uwoif"))
        assertEquals("nươc", c.composeBuffer("nuwoc"))
        assertEquals("nước", c.composeBuffer("nuwocs"))
        assertEquals("hương", c.composeBuffer("huwong"))
        assertEquals("tưới", c.composeBuffer("tuwois"))
        // With nothing behind it, the `ưo` the keys spell stands: `ưo` is what
        // the user's own two letters make, and nothing says they are one. What
        // the tone does on that pair is `nucleus`'s business, not this one's.
        assertEquals("ưo", c.composeBuffer("uwo"))
        // The glide is not a `u` this rule horns; here a `w` of its own horned
        // it, and the pair is `ươ` from then on.
        assertEquals("qươ", c.composeBuffer("quwow"))
    }

    @Test
    fun vniHornsTheOTooWhenTheUWasHornedBeforeIt() {
        val c = VietnameseVniComposer
        assertEquals("ương", c.composeBuffer("u7ong"))
        assertEquals("ướng", c.composeBuffer("u7ong1"))
        assertEquals("ươi", c.composeBuffer("u7oi"))
        assertEquals("ười", c.composeBuffer("u7oi2"))
        assertEquals("nươc", c.composeBuffer("nu7oc"))
        assertEquals("hương", c.composeBuffer("hu7ong"))
        assertEquals("ưo", c.composeBuffer("u7o"))
    }

    @Test
    fun vniHornsTheUTooUnlessTheOnsetCanSpellTheOpenUo() {
        val c = VietnameseVniComposer
        assertEquals("ngươ", c.composeBuffer("nguo7"))
        assertEquals("người", c.composeBuffer("nguo7i2"))
        assertEquals("ngươi", c.composeBuffer("nguo7i"))
        assertEquals("ngương", c.composeBuffer("nguo7ng"))
        assertEquals("bướng", c.composeBuffer("buo7ng1"))
        assertEquals("dương", c.composeBuffer("duo7ng"))
        assertEquals("cường", c.composeBuffer("cuo7ng2"))
        assertEquals("khường", c.composeBuffer("khuo7ng2"))
        assertEquals("hương", c.composeBuffer("huo7ng"))
        assertEquals("tười", c.composeBuffer("tuo7i2"))
        assertEquals("ười", c.composeBuffer("uo7i2"))
        // The open reading survives where the language spells it.
        assertEquals("huơ", c.composeBuffer("huo7"))
        assertEquals("quơ", c.composeBuffer("quo7"))
        assertEquals("thuở", c.composeBuffer("thuo73"))
        assertEquals("huớ", c.composeBuffer("huo71"))
        assertEquals("quơng", c.composeBuffer("quo7ng"))
        assertEquals("quời", c.composeBuffer("quo7i2"))
    }

    @Test
    fun telexTakesToneMarksTypedAsThemselves() {
        val c = VietnameseTelexComposer
        assertEquals("cháo", c.composeBuffer("chao\u0301"))
        assertEquals("chào", c.composeBuffer("chao\u0300"))
        assertEquals("chảo", c.composeBuffer("chao\u0309"))
        assertEquals("chão", c.composeBuffer("chao\u0303"))
        assertEquals("chạo", c.composeBuffer("chao\u0323"))
        // The key's faces are drawn on a dotted circle, which is swallowed —
        // so the ring's bare circle is its "no tone" entry.
        assertEquals("cháo", c.composeBuffer("chao\u25CC\u0301"))
        assertEquals("chao", c.composeBuffer("chaos\u25CC"))
        // Named outright, a tone does not toggle the way a letter key does.
        assertEquals("cháo", c.composeBuffer("chao\u0301\u0301"))
        // And it still needs a nucleus to land on.
        assertEquals("bcd", c.composeBuffer("bcd\u0301"))
    }

    @Test
    fun toneKeyCharactersStayInTheBuffer() {
        for (c in listOf(VietnameseTelexComposer, VietnameseVniComposer)) {
            for (mark in "\u25CC\u0301\u0300\u0309\u0303\u0323") {
                assertTrue(c.toString(), c.buffersChar(mark))
            }
            assertTrue(c.toString(), !c.buffersChar('z'))
        }
    }

    // --- VNI Tests ---

    @Test
    fun vniBasicTones() {
        val c = VietnameseVniComposer
        assertEquals("á", c.composeBuffer("a1"))
        assertEquals("à", c.composeBuffer("a2"))
        assertEquals("ả", c.composeBuffer("a3"))
        assertEquals("ã", c.composeBuffer("a4"))
        assertEquals("ạ", c.composeBuffer("a5"))
        assertEquals("a", c.composeBuffer("a10")) // 0 clears tone
    }

    @Test
    fun vniZeroWithNoToneIsTheDigit() {
        val c = VietnameseVniComposer
        // `0` is VNI's XoaDauThanh — the rule Telex spells with `z` — so it
        // takes the tone off and types nothing of its own, and a word with no
        // tone keeps its digit: `a0` is `a0`, `toan0` is `toan0`. Marks that
        // are letters rather than tones stay where they are.
        assertEquals("a0", c.composeBuffer("a0"))
        assertEquals("toan0", c.composeBuffer("toan0"))
        assertEquals("toans0", c.composeBuffer("toans0"))
        assertEquals("banana0", c.composeBuffer("banana0"))
        assertEquals("â0", c.composeBuffer("a60"))
        assertEquals("ă0", c.composeBuffer("a80"))
        assertEquals("ư0", c.composeBuffer("u70"))
        assertEquals("đay0", c.composeBuffer("d9ay0"))
        // A tone is what the digit is for, and taking one off keeps the marks.
        assertEquals("a", c.composeBuffer("a10"))
        assertEquals("toan", c.composeBuffer("toan10"))
        assertEquals("chao", c.composeBuffer("chao10"))
        assertEquals("viêt", c.composeBuffer("viet650"))
    }

    @Test
    fun vniSameDigitAgainTypesTheDigit() {
        val c = VietnameseVniComposer
        // #1490: the second press takes the mark off and types the digit.
        assertEquals("ha2", c.composeBuffer("ha22"))
        assertEquals("a1", c.composeBuffer("a11"))
        assertEquals("a6", c.composeBuffer("a66"))
        assertEquals("d9", c.composeBuffer("d99"))
        assertEquals("hà", c.composeBuffer("ha2"))
        // A different tone still swaps the mark.
        assertEquals("há", c.composeBuffer("ha21"))
    }

    @Test
    fun vniLetterMarks() {
        val c = VietnameseVniComposer
        assertEquals("â", c.composeBuffer("a6"))
        assertEquals("ơ", c.composeBuffer("o7"))
        assertEquals("ư", c.composeBuffer("u7"))
        assertEquals("ă", c.composeBuffer("a8"))
        assertEquals("đ", c.composeBuffer("d9"))
    }

    @Test
    fun vniFullSyllables() {
        val c = VietnameseVniComposer
        assertEquals("việt", c.composeBuffer("viet65"))
        assertEquals("tiếng", c.composeBuffer("tieng61"))
        assertEquals("đây", c.composeBuffer("d9ay6"))
    }

    @Test
    fun vniToneMarksAndVowelRun() {
        val c = VietnameseVniComposer
        assertEquals("cháo", c.composeBuffer("chao\u0301"))
        assertEquals("chao", c.composeBuffer("chao1\u25CC"))
        assertEquals("banana1", c.composeBuffer("banana1"))
    }

    @Test
    fun vniCapitalization() {
        val c = VietnameseVniComposer
        assertEquals("Việt", c.composeBuffer("Viet65"))
        assertEquals("Tiếng", c.composeBuffer("Tieng61"))
    }
}
