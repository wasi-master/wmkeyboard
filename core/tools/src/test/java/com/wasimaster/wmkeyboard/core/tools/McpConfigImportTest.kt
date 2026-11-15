package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pasting another app's MCP configuration (#573). */
class McpConfigImportTest {

    @Test
    fun `claude desktop shape, with a local command bridged`() {
        val result = McpConfigImport.parse(
            """
            {"mcpServers": {
              "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/my files"],
                        "env": {"TOKEN": "it's"}},
              "remote": {"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer abc"}},
              "git": {"command": "uvx", "args": ["mcp-server-git"]}
            }}
            """,
        )!!
        assertTrue(result.skipped.isEmpty())
        val (files, server, git) = result.servers
        assertEquals("remote", server.name)
        assertEquals("https://mcp.example.com/mcp", server.url)
        assertEquals(McpTransport.AUTO, server.transport)
        assertEquals(listOf(McpHeader("Authorization", "Bearer abc")), server.headers)
        assertEquals("", server.bridgeCommand)
        assertEquals("http://127.0.0.1:8811/mcp", files.url)
        assertEquals(McpTransport.STREAMABLE_HTTP, files.transport)
        assertEquals(
            "npx -y supergateway --stdio 'TOKEN=\"it'\\''s\" npx -y @modelcontextprotocol/server-filesystem \"/my files\"' " +
                "--outputTransport streamableHttp --port 8811",
            files.bridgeCommand,
        )
        assertEquals("http://127.0.0.1:8812/mcp", git.url)
        assertTrue(git.bridgeCommand.endsWith("--stdio 'uvx mcp-server-git' --outputTransport streamableHttp --port 8812"))
    }

    @Test
    fun `vs code shape reads its type`() {
        val result = McpConfigImport.parse(
            """
            {"servers": {
              "a": {"type": "http", "url": "https://a.test/mcp"},
              "b": {"type": "sse", "url": "https://b.test/sse"}
            }}
            """,
        )!!
        assertEquals(listOf(McpTransport.STREAMABLE_HTTP, McpTransport.SSE), result.servers.map { it.transport })
    }

    @Test
    fun `a bare map and a single server both read`() {
        val bare = McpConfigImport.parse("""{"one": {"serverUrl": "https://one.test/sse"}}""")!!
        assertEquals("one", bare.servers.single().name)
        // No type given, but an address ending in /sse is the old transport.
        assertEquals(McpTransport.SSE, bare.servers.single().transport)
        val single = McpConfigImport.parse("""{"name": "solo", "url": "http://192.168.1.2:3000/mcp", "transport": "streamable-http"}""")!!
        assertEquals("solo", single.servers.single().name)
        assertEquals(McpTransport.STREAMABLE_HTTP, single.servers.single().transport)
    }

    @Test
    fun `addresses that are not http are refused`() {
        val result = McpConfigImport.parse(
            """{"mcpServers": {"x": {"url": "ws://x.test"}, "y": {"note": "nothing"}}}""",
        )!!
        assertTrue(result.servers.isEmpty())
        assertEquals(
            listOf(
                McpConfigImport.Skipped("x", McpConfigImport.SkipReason.BAD_URL),
                McpConfigImport.Skipped("y", McpConfigImport.SkipReason.NO_URL),
            ),
            result.skipped,
        )
    }

    @Test
    fun `not json, or not an object, is null`() {
        assertNull(McpConfigImport.parse("mcpServers"))
        assertNull(McpConfigImport.parse("[1, 2]"))
        assertTrue(McpConfigImport.parse("{}")!!.isEmpty)
    }
}
