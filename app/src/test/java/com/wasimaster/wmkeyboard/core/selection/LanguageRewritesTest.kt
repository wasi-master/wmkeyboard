package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRewritesTest {

    @Test
    fun `Serbian goes to Latin and back, digraphs and capitals included`() {
        assertEquals("Ljubav i njiva, Džon, čaša, ćerka, đak", ScriptSwitch.convert("sr", "Љубав и њива, Џон, чаша, ћерка, ђак"))
        assertEquals("Љубав и њива, Џон, чаша, ћерка, ђак", ScriptSwitch.convert("sr", "Ljubav i njiva, Džon, čaša, ćerka, đak"))
        assertEquals("LJUBAV", ScriptSwitch.convert("sr", "ЉУБАВ"))
    }

    @Test
    fun `a Serbian digraph that is two letters stays two`() {
        assertEquals("инјекција", ScriptSwitch.convert("sr", "injekcija"))
        assertEquals("надживети", ScriptSwitch.convert("sr", "nadživeti"))
    }

    @Test
    fun `a foreign word or an address stays Latin`() {
        assertEquals("Шаљем WhatsApp на www.primer.rs", ScriptSwitch.convert("sr", "Šaljem WhatsApp na www.primer.rs"))
    }

    @Test
    fun `Uzbek follows the official rules for e, ts and the apostrophe letters`() {
        assertEquals("Yer va koʻcha", ScriptSwitch.convert("uz", "Ер ва кўча"))
        assertEquals("sentyabr, maktab, shaharcha, maʼno", ScriptSwitch.convert("uz", "сентябр, мактаб, шаҳарча, маъно"))
        assertEquals("Ўзбекистон", ScriptSwitch.convert("uz", "Oʻzbekiston"))
        assertEquals("шаҳар ва эшик", ScriptSwitch.convert("uz", "shahar va eshik"))
        assertEquals("ғоя, маъно", ScriptSwitch.convert("uz", "g'oya, ma'no"))
    }

    @Test
    fun `Persian letters and half-spaces come right and addresses are left alone`() {
        assertEquals("می‌روم", PersianCleanup.clean("می روم"))
        assertEquals("نمی‌کنم", PersianCleanup.clean("نمی کنم"))
        assertEquals("کتاب‌ها", PersianCleanup.clean("کتاب ها"))
        assertEquals("جمعه‌ها", PersianCleanup.clean("جمعهها"))
        assertEquals("خانه‌ام", PersianCleanup.clean("خانه ام"))
        assertEquals("کیف", PersianCleanup.clean("كيف"))
        assertEquals("سلام، خوبی؟", PersianCleanup.clean("سلام ،خوبی ؟"))
        assertEquals("example.com 3.14", PersianCleanup.clean("example.com 3.14"))
        assertEquals("سلام", PersianCleanup.clean("‌سلام‌"))
    }

    @Test
    fun `yo comes back only where the list is sure`() {
        val yo = RussianYo("ещё\nёж(а|у|ом)\n_киёв\nКолёс(а|ов)\n# нет\nзелён(ый|ая) # пример\n")
        assertEquals("Ещё зелёный. Ёжа нет", yo.restore("Еще зеленый. Ежа нет"))
        assertEquals("киёв Киев", yo.restore("киев Киев"))
        assertEquals("Колёса", yo.restore("Колеса"))
        assertEquals("мед. училище", RussianYo("мёд\n").restore("мед. училище"))
        assertEquals("мёд", RussianYo("мёд\n").restore("мед"))
    }
}
