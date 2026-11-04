package com.wasimaster.wmkeyboard.ime.wmpass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultWireTest {
    @Test
    fun readsThePageWmPassWrites() {
        // The same page WM Pass's VaultWireTest writes.
        val raw = """{"v":1,"id":"a1","kind":"login","title":"GitHub","subtitle":"me@example.com","fields":[""" +
            """{"key":"username","label":"Username","secret":false,"value":"me@example.com"},""" +
            """{"key":"password","label":"Password","secret":true},""" +
            """{"key":"site:0","label":"Website","secret":false,"value":"https://github.com"},""" +
            """{"key":"custom:0","label":"Recovery email","secret":false,"value":"backup@example.com"},""" +
            """{"key":"custom:1","label":"PIN","secret":true},""" +
            """{"key":"code","label":"One-time code","secret":true},""" +
            """{"key":"notes","label":"Notes","secret":true}],"changes":"password"}"""
        val page = VaultWire.page(raw)!!
        assertEquals("a1", page.id)
        assertEquals("GitHub", page.title)
        assertEquals("password", page.changes)
        assertEquals(
            listOf("username", "password", "site:0", "custom:0", "custom:1", VaultWire.CODE, VaultWire.NOTES),
            page.fields.map { it.key },
        )
        assertEquals(VaultWire.Field("username", "Username", secret = false, value = "me@example.com"), page.fields[0])
        assertEquals(VaultWire.Field("password", "Password", secret = true, value = null), page.fields[1])
    }

    @Test
    fun aSecretIsNeverHeldEvenIfSent() {
        val raw = """{"id":"c1","kind":"card","fields":[{"key":"number","label":"Card number","secret":true,"value":"4111"}]}"""
        val page = VaultWire.page(raw)!!
        assertNull(page.fields.single().value)
        assertNull(page.changes)
    }

    @Test
    fun readsTheRowsWmPassWrites() {
        // The same rows WM Pass's VaultWireTest writes.
        val raw = """[{"id":"a1","kind":"login","title":"GitHub","subtitle":"me@example.com","matches":true,"has_password":true,"has_code":true,"favourite":false},""" +
            """{"id":"w1","kind":"wifi","title":"Home","subtitle":"","matches":false,"has_password":false,"has_code":false,"favourite":true}]"""
        assertEquals(
            listOf(
                VaultWire.Row("a1", "login", "GitHub", "me@example.com", matches = true, hasPassword = true, hasCode = true, favourite = false),
                VaultWire.Row("w1", "wifi", "Home", "", matches = false, hasPassword = false, hasCode = false, favourite = true),
            ),
            VaultWire.rows(raw),
        )
    }

    @Test
    fun whatDoesNotParseReadsAsNothing() {
        assertTrue(VaultWire.rows(null).isEmpty())
        assertTrue(VaultWire.rows("not json").isEmpty())
        assertTrue(VaultWire.rows("""[{"title":"no id"}, 3]""").isEmpty())
        assertNull(VaultWire.page("{}"))
        assertNull(VaultWire.page(null))
        assertEquals("item:a1", VaultWire.openRoute("a1"))
    }
}
