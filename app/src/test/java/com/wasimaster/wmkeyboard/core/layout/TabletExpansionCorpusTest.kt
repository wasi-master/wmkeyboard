package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet expansion, run over every layout the app ships — 22 built in plus
 * 1,259 assets, on both tablet forms with the digit row on and off.
 *
 * `TabletExpansionTest` pins the arithmetic on grids it builds by hand; this
 * pins it against reality. The transform relocates keys by role across 1,280
 * genuinely different grids — Bengali and Devanagari rows twelve wide, Arabic
 * and Hebrew right-to-left, five-row Khmer, layouts whose top row already
 * overflows their own grid weight — and the only honest way to know it survives
 * all of them is to run it on all of them.
 *
 * Read from disk rather than through an AssetManager, the same way
 * [AssetLayoutsTest] does, so this needs no device.
 */
class TabletExpansionCorpusTest {

    private data class Case(val id: String, val letters: KeyboardLayout)

    private val corpus: List<Case> = buildList {
        BuiltInLayouts.all.forEach { add(Case(it.id, it.compile(LayoutLayer.LETTERS))) }
        File("src/main/assets/layouts")
            .listFiles { f -> f.name.endsWith(".${LayoutFile.FILE_EXTENSION}") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val spec = LayoutFile.decode(file.readText())!!.layout
                add(Case(file.name.removeSuffix(".${LayoutFile.FILE_EXTENSION}"), spec.compile(LayoutLayer.LETTERS)))
            }
    }

    private val forms = listOf(DeviceForm.SMALL_TABLET, DeviceForm.LARGE_TABLET)

    /**
     * The hand-authored grids.
     *
     * The shape checks below run over the whole corpus, generated grids
     * included — a row that overflows on a tablet is a real defect wherever it
     * came from. But the decline set is a list of *our* grids, named one by
     * one so a transform that silently started rejecting Bengali would fail;
     * folding 862 converted Keyman grids into it would turn that precise
     * assertion into a number nobody can check.
     */
    private val handAuthored: List<Case>
        get() = corpus.filterNot { it.id.startsWith("hw_") || it.id.startsWith("kmn_") || it.id.startsWith("gb_") || it.id.startsWith("sam_") || it.id.startsWith("rom_") }

    private fun List<Key>.width() = sumOf { it.width.toDouble() }.toFloat()

    @Test
    fun `the corpus is the whole shipped set`() {
        assertEquals("built-ins plus hand-authored assets", 39 + 1_110, handAuthored.size)
        assertTrue(
            "converted Keyman grids are missing from the corpus",
            corpus.size - handAuthored.size > 800,
        )
    }

    /**
     * The exact set of layouts that decline, spelled out.
     *
     * An assertion on the count alone would pass while the transform silently
     * started rejecting Bengali and accepting braille. These are the
     * grids where a wide alphabetic layout is not what the user is looking at:
     * the T9 keypad is four columns of letter *groups* and widening it would
     * make it a keyboard with nothing to type on, kana flick pads and braille
     * are typed by position, morse by timing, and
     * the rest have no shift key to mirror.
     *
     * Inuktitut and Syriac used to be on this list and have earned their way
     * off it. Both declined because they had a shift key with nothing on it —
     * Inuktitut could not reach a single one of its twelve finals, and Syriac
     * could not reach seyame. Filling those planes in made them ordinary
     * alphabetic grids, which is what the transform is for.
     */
    @Test
    fun `exactly the non-alphabetic layouts decline`() {
        // The per-language T9 keypads (#332) decline for the reason builtin_t9 does.
        val keypads = handAuthored.map { it.id }.filter { it.endsWith("_t9") }.toSet()
        val declined = handAuthored
            .filter { tabletGridWidth(it.letters, DeviceForm.LARGE_TABLET) == null }
            .map { it.id }
            .toSet()

        assertEquals(
            setOf(
                // Khipro's shift key is its slicer, so there is none to mirror.
                "builtin_khipro", "builtin_khipro_dark",
                "builtin_t9",
                "braille_chord", "ja_flick", "ja_kana_jis", "morse", "zh_stroke",
                // Godan's letters all flick, and 50 keys has a 小゛゜ key and no shift.
                "ja_godan", "ja_50keys",
                // The English 3×3 flick board (#410): every letter key carries
                // its neighbours on flick arms, and the transform declines any
                // key with flicks.
                "en_flick",
                // Its Khipro and Avro twins (Telegram group), the same grid.
                "bn_khipro_flick", "bn_avro_flick",
                "ipa", "music", "nqo_nko",
                "zh_cangjie", "zh_cangjie_quick", "zh_pinyin_t9", "zh_wubi", "zh_zhuyin",
                // Pinyin's shift slot is the '词 syllable separator (#580,
                // 1969f7090): letters are typed lower case, so no shift to mirror.
                "zh_pinyin",
                // The 천지인 pad (#372): a 12-key keypad with no shift key.
                "ko_cheonjiin", "ko_10key",
                // The 나랏글 pad (#599), the same shape.
                "ko_naratgeul",
                // FUTO's ClearFlow and KASROZ: shift, delete and space are keys
                // two rows tall, and the transform declines any grid with a span.
                "en_clearflow", "en_kasroz", "zh_pinyin_clearflow",
                // IPA on QWERTY declines for the reason the IPA grid does.
                "ipa_qwerty", "ipa_qwerty_g",
                // Toki Pona's fourteen letters fit on two short rows.
                "tok_compact",
                // Arabic as Gboard and AOSP draw it (#427): no shift key to mirror.
                "ar_letters",
                // Persian as Gboard draws it, and the alphabetical Devanagari
                // pages: every letter on its own key, so no shift key either.
                "fa_gboard", "hi_devanagari",
                // Klingon in pIqaD, which has no case.
                "tlh_piqad",
                // Handwriting pads are a drawing surface, not a key grid.
                "ja_handwriting", "ko_handwriting", "zh_handwriting",
                // Gboard+ English puts flick arms on its bottom row; Gboard+
                // Hebrew has no shift key (its shifted page is English).
                "en_gboard_plus", "he_gboard_plus", "he_gboard_plus_swipe_safe",
                // Tatoeba languages in Arabic script, built from the Arabic
                // template, which has no shift key either.
                "acm", "afb", "ajp", "apc", "arq", "ayl", "chg", "klj", "ota", "qxq", "sdh",
                // The reporter's four Sorani layouts, which the transform declines.
                "ckb_alt", "ckb_four_rows", "ckb_phonetic", "ckb_sorani",
                // Samsung's alphabet-order Bangla grid has no shift key.
                "bn_samsung",
            ) + keypads,
            declined,
        )
    }

    @Test
    fun `a declining layout is handed back untouched, on every form`() {
        for (case in corpus) {
            for (form in forms) {
                if (tabletGridWidth(case.letters, form) != null) continue
                for (numberRow in listOf(true, false)) {
                    assertSame(
                        "${case.id} $form",
                        case.letters,
                        case.letters.expandForTablet(form, numberRow),
                    )
                }
            }
        }
    }

    @Test
    fun `every expansion stays inside the layout shape limits`() {
        forEachExpansion { where, _, after ->
            after.rows.forEach { row ->
                assertTrue("$where: ${row.size} keys in a row", row.size <= MaxKeysPerRow)
                assertTrue("$where: row ${row.width()} wide", row.width() <= MaxRowWidth)
                row.forEach { key ->
                    assertTrue("$where: key width ${key.width}", key.width > 0f)
                    assertTrue("$where: key width ${key.width}", key.width <= MaxKeyWidth)
                }
            }
        }
    }

    /**
     * The constraint the whole feature rests on: `LayoutSet.rowSpan` and
     * `keyRowsHeight` size the IME window from layer row *counts*, and neither
     * sees this transform. A row added here draws a keyboard taller than the
     * window that was measured for it.
     */
    @Test
    fun `no expansion changes the row count or the row heights`() {
        forEachExpansion { where, before, after ->
            assertEquals("$where: row count", before.rows.size, after.rows.size)
            assertEquals("$where: row heights", before.rowHeights, after.rowHeights)
        }
    }

    @Test
    fun `every expansion keeps exactly one of each essential key`() {
        forEachExpansion { where, _, after, numberRow ->
            fun count(pred: (Key) -> Boolean) = after.rows.sumOf { it.count(pred) }
            assertEquals("$where: space", 1, count { it.action == KeyAction.Space })
            assertEquals("$where: enter", 1, count { it.action == KeyAction.Enter })
            assertEquals("$where: emoji", 1, count { it.action == KeyAction.Emoji })
            // Backspace moves onto the digit row when that row is drawn, and
            // expandNumberRowForTablet puts it there. The two answers have to
            // agree, or the board has no backspace at all.
            assertEquals(
                "$where: delete",
                if (numberRow) 0 else 1,
                count { it.action == KeyAction.Delete },
            )
            assertEquals(
                "$where: shift",
                if (numberRow) 2 else 1,
                count { it.action == KeyAction.Shift },
            )
        }
    }

    @Test
    fun `no expansion loses a character key`() {
        forEachExpansion { where, before, after ->
            val text = { grid: KeyboardLayout ->
                grid.rows.flatten()
                    .filter { it.action == KeyAction.Text }
                    .map { it.output ?: it.label }
            }
            val lost = text(before) - text(after).toSet()
            assertTrue("$where: lost $lost", lost.isEmpty())
        }
    }

    /**
     * `roleIn` only infers a role positionally on the *bottom* row. Once the
     * comma moves up to the shift row, a layout that relied on that inference
     * would silently lose its email and URI adaptation — the `@` key would stop
     * appearing — with nothing on screen to explain why.
     */
    @Test
    fun `relocated punctuation carries its role explicitly`() {
        forEachExpansion { where, _, after ->
            val shiftRow = after.rows[after.rows.lastIndex - 1]
            assertTrue(
                "$where: comma lost its role",
                shiftRow.any { it.role == KeyRole.Comma },
            )
        }
    }

    /** Every eligible layout, both forms, digit row on and off. */
    private fun forEachExpansion(
        check: (where: String, before: KeyboardLayout, after: KeyboardLayout, numberRow: Boolean) -> Unit,
    ) {
        var ran = 0
        for (case in corpus) {
            for (form in forms) {
                if (tabletGridWidth(case.letters, form) == null) continue
                for (numberRow in listOf(true, false)) {
                    val after = case.letters.expandForTablet(form, numberRow)
                    check("${case.id} $form numberRow=$numberRow", case.letters, after, numberRow)
                    ran++
                }
            }
        }
        assertTrue("no expansions ran", ran > 1000)
    }

    private fun forEachExpansion(
        check: (where: String, before: KeyboardLayout, after: KeyboardLayout) -> Unit,
    ) = forEachExpansion { where, before, after, _ -> check(where, before, after) }
}
