package com.wasimaster.wmkeyboard.core.addons

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clink's "Open in Clink" links and the public list behind its community page. */
class ClinkCommunityTest {

    private val id = "ed95bfbf-b1e0-4477-a576-b3202423e717"

    @Test
    fun `a community link names its item`() {
        assertEquals(id, ClinkCommunity.itemIdFrom("clink://community/$id"))
        assertEquals(id, ClinkCommunity.itemIdFrom("CLINK://community/${id.uppercase()}/"))
        assertEquals(id, ClinkCommunity.itemIdFrom("clink:community/$id"))
        assertEquals(id, ClinkCommunity.itemIdFrom("clink://community/$id?ref=web"))
    }

    @Test
    fun `anything else is not one`() {
        assertNull(ClinkCommunity.itemIdFrom("clink://store/$id"))
        assertNull(ClinkCommunity.itemIdFrom("clink://community/not-an-id"))
        assertNull(ClinkCommunity.itemIdFrom("clink://community/$id/extra"))
        assertNull(ClinkCommunity.itemIdFrom("https://www.anti.ltd/store/clink/community"))
        assertNull(ClinkCommunity.itemIdFrom("wmkeyboard://addons"))
        assertNull(ClinkCommunity.itemIdFrom(null))
    }

    @Test
    fun `a page of the public list reads`() {
        val text = """{"items": [
            {"id": "$id", "app_id": "clink", "kind": "theme", "title": " Spider-Man Mech ", "description": "",
             "author": "AMS", "preview": "data:image/webp;base64,AAEC", "status": "published", "version": "4", "votes": "4"},
            {"id": "84d722c0-0f9b-4278-b173-ad0fd8cbba44", "kind": "plugin", "title": "Keyboard2", "author": "Nelson",
             "votes": 0, "preview": "https://example.com/x.png"},
            {"id": "broken", "kind": "theme"}
        ], "hasMore": true}"""
        val page = ClinkCommunity.parsePage(text)!!
        assertTrue(page.hasMore)
        assertEquals(2, page.items.size)
        val theme = page.items[0]
        assertEquals("Spider-Man Mech", theme.title)
        assertEquals(4, theme.votes)
        assertTrue(theme.importable)
        // Only an inline picture is shown; a link to one elsewhere is not followed.
        assertNull(page.items[1].preview)
        assertFalse(page.items[1].importable)
        // An action's exported .clinkext installs as a plugin.
        assertTrue(page.items[0].copy(kind = "action").importable)
        assertNull(ClinkCommunity.parsePage("""{"error": "Sign in to continue."}"""))
    }

    @Test
    fun `the preview picture comes out of its data URI`() {
        assertArrayEquals(byteArrayOf(0, 1, 2), ClinkCommunity.previewBytes("data:image/webp;base64,AAEC"))
        assertNull(ClinkCommunity.previewBytes("data:image/png,raw"))
        assertNull(ClinkCommunity.previewBytes("https://example.com/x.png"))
        assertNull(ClinkCommunity.previewBytes(null))
    }

    @Test
    fun `pages step by their size`() {
        assertTrue(ClinkCommunity.listUrl(48).contains("offset=48"))
    }
}
