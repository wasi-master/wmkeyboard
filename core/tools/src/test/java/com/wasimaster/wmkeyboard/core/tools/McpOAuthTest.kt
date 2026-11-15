package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text half of an MCP server sign-in: what is read, and what is sent. */
class McpOAuthTest {

    @Test
    fun `a bearer challenge gives its resource metadata and scope`() {
        val c = McpOAuth.challenge(
            """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource", scope="files:read files:write"""",
        )!!
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource", c.resourceMetadata)
        assertEquals("files:read files:write", c.scope)
        assertNull(c.error)
        assertNull(McpOAuth.challenge("Basic realm=\"x\""))
        assertNull(McpOAuth.challenge(null))
        val bare = McpOAuth.challenge("Bearer")!!
        assertNull(bare.resourceMetadata)
        val token = McpOAuth.challenge("Bearer error=invalid_token, error_description=\"expired\"")!!
        assertEquals("invalid_token", token.error)
    }

    @Test
    fun `the resource is the server address made canonical`() {
        assertEquals("https://mcp.example.com/mcp", McpOAuth.canonicalResource("HTTPS://MCP.Example.com/mcp/"))
        assertEquals("https://mcp.example.com", McpOAuth.canonicalResource("https://mcp.example.com/"))
        assertEquals("http://127.0.0.1:8811/mcp", McpOAuth.canonicalResource("http://127.0.0.1:8811/mcp#x"))
    }

    @Test
    fun `metadata addresses are tried in the spec's order`() {
        assertEquals(
            listOf(
                "https://a.test/meta",
                "https://a.test/.well-known/oauth-protected-resource/public/mcp",
                "https://a.test/.well-known/oauth-protected-resource",
            ),
            McpOAuth.resourceMetadataUrls("https://a.test/public/mcp", "https://a.test/meta"),
        )
        assertEquals(
            listOf(
                "https://auth.test/.well-known/oauth-authorization-server/tenant1",
                "https://auth.test/.well-known/openid-configuration/tenant1",
                "https://auth.test/tenant1/.well-known/openid-configuration",
            ),
            McpOAuth.serverMetadataUrls("https://auth.test/tenant1"),
        )
        assertEquals(
            listOf("https://auth.test/.well-known/oauth-authorization-server", "https://auth.test/.well-known/openid-configuration"),
            McpOAuth.serverMetadataUrls("https://auth.test"),
        )
    }

    @Test
    fun `server metadata reads endpoints, PKCE and client documents`() {
        val meta = McpOAuth.serverMeta(
            """{"issuer":"https://auth.test","authorization_endpoint":"https://auth.test/authorize",
                "token_endpoint":"https://auth.test/token","registration_endpoint":"https://auth.test/register",
                "code_challenge_methods_supported":["S256"],"client_id_metadata_document_supported":true}""",
        )!!
        assertTrue(meta.supportsS256)
        assertTrue(meta.takesClientMetadataDocument)
        assertEquals("https://auth.test/register", meta.registrationEndpoint)
        val noPkce = McpOAuth.serverMeta("""{"authorization_endpoint":"a","token_endpoint":"t"}""", "https://x")!!
        assertFalse(noPkce.supportsS256)
        assertEquals("https://x", noPkce.issuer)
        assertNull(McpOAuth.serverMeta("""{"token_endpoint":"t"}"""))
        val resource = McpOAuth.resourceMeta("""{"resource":"https://a.test/mcp","authorization_servers":["https://auth.test"],"scopes_supported":["a","b"]}""")!!
        assertEquals(listOf("https://auth.test"), resource.authorizationServers)
        assertEquals("a b", McpOAuth.scopeFor(null, resource))
        assertEquals("x", McpOAuth.scopeFor(McpOAuth.Challenge(null, "x", null), resource))
    }

    @Test
    fun `the authorize address carries PKCE, state and resource`() {
        val meta = McpOAuth.defaultServerMeta("https://a.test/mcp")!!
        assertEquals("https://a.test/authorize", meta.authorizationEndpoint)
        val url = McpOAuth.authorizeUrl(meta, "client 1", "verifier", "st", "files:read", "https://a.test/mcp")
        assertTrue(url.startsWith("https://a.test/authorize?response_type=code&client_id=client%201&"))
        assertTrue("redirect_uri=wmkeyboard%3A%2F%2Foauth%2Fmcp" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("scope=files%3Aread" in url)
        assertTrue(url.endsWith("resource=https%3A%2F%2Fa.test%2Fmcp"))
    }

    @Test
    fun `the S256 challenge matches RFC 7636's example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        assertEquals(64, McpOAuth.newVerifier().length)
        assertEquals("Zm9vYg", McpOAuth.base64Url("foob".toByteArray()))
        assertEquals("Zm9vYmE", McpOAuth.base64Url("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", McpOAuth.base64Url("foobar".toByteArray()))
    }

    @Test
    fun `token answers fill the state, and a refresh keeps the old refresh token`() {
        val start = McpAuthState(resource = "https://a.test/mcp", tokenEndpoint = "https://a.test/token", clientId = "c")
        val signed = McpOAuth.tokens("""{"access_token":"A","refresh_token":"R","expires_in":3600}""", start, 1_000L)!!
        assertEquals("A", signed.accessToken)
        assertEquals("R", signed.refreshToken)
        assertEquals(3_601_000L, signed.expiresAt)
        assertTrue(signed.usable(1_000L))
        assertFalse(signed.usable(3_560_001L))
        assertTrue(signed.canRefresh)
        val again = McpOAuth.tokens("""{"access_token":"B","expires_in":"60"}""", signed, 0L)!!
        assertEquals("R", again.refreshToken)
        assertEquals(60_000L, again.expiresAt)
        assertNull(McpOAuth.tokens("""{"error":"invalid_grant"}""", signed, 0L))
        val form = McpOAuth.refreshForm(signed)
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("https://a.test/mcp", form["resource"])
        assertFalse("client_secret" in form)
    }

    @Test
    fun `registration and redirect read back`() {
        assertEquals(McpOAuth.Client("id1", ""), McpOAuth.registration("""{"client_id":"id1"}"""))
        assertNull(McpOAuth.registration("{}"))
        assertTrue("\"redirect_uris\":[\"wmkeyboard://oauth/mcp\"]" in McpOAuth.registrationBody())
        val back = McpOAuth.redirect("wmkeyboard://oauth/mcp?code=a%2Bb&state=s1")
        assertEquals("a+b", back.code)
        assertEquals("s1", back.state)
        assertEquals("access_denied", McpOAuth.redirect("wmkeyboard://oauth/mcp?error=access_denied").error)
    }

    @Test
    fun `sign-ins round-trip through the codec`() {
        val auth = mapOf("mcp_1" to McpAuthState(resource = "r", accessToken = "a", expiresAt = 5L))
        assertEquals(auth, McpServerCodec.decodeAuth(McpServerCodec.encodeAuth(auth)))
        assertTrue(McpServerCodec.decodeAuth("not json").isEmpty())
    }
}
