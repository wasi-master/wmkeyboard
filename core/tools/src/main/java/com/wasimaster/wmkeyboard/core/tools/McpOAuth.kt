package com.wasimaster.wmkeyboard.core.tools

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The text half of signing in to an MCP server through OAuth: reading what
 * the server and its authorization server publish, and writing the requests
 * the sign-in sends. No network here; `McpAuth` in `:feature:tools` makes
 * the requests.
 *
 * Follows the MCP authorization spec (2025-06-18 and later):
 *
 * 1. A 401 names the server's protected-resource metadata in its
 *    `WWW-Authenticate` header (RFC 9728); without one, the well-known
 *    addresses are tried, the server's own path first.
 * 2. That metadata names the authorization server, whose own metadata
 *    (RFC 8414, or OpenID Connect discovery) gives the endpoints.
 * 3. The keyboard is known to it by its client metadata document (a URL as
 *    the client id) where the server takes one, else by dynamic client
 *    registration (RFC 7591).
 * 4. Authorization code with PKCE (S256), the `resource` parameter
 *    (RFC 8707) on every request, and a refresh token when one is given.
 *
 * A server from before the spec named any metadata (2025-03-26) gets the
 * defaults that version set: the endpoints at `/authorize`, `/token` and
 * `/register` on the server's own origin.
 */
object McpOAuth {

    /** Where the browser sends the user back; matches `OAuthRedirectActivity`'s filter. */
    const val REDIRECT_URI = "wmkeyboard://oauth/mcp"

    /**
     * The keyboard's client metadata document, on the docs site. An
     * authorization server that takes these reads it to learn the client's
     * name and its redirect address, so nothing has to be registered first.
     */
    const val CLIENT_METADATA_URL = "https://wmkeyboard.pages.dev/oauth/mcp-client.json"

    const val CLIENT_NAME = "WM Keyboard"

    private const val VERIFIER_BYTES = 48
    private const val STATE_BYTES = 24
    private const val MS_PER_SECOND = 1000L

    private val json = Json { ignoreUnknownKeys = true }

    // ---- the 401 -------------------------------------------------------------

    /** What a `WWW-Authenticate: Bearer …` header says. */
    data class Challenge(val resourceMetadata: String?, val scope: String?, val error: String?)

    /**
     * The `Bearer` challenge in [header], or null when there is none. Several
     * schemes may share one header; only the Bearer one's parameters are read.
     */
    fun challenge(header: String?): Challenge? {
        if (header.isNullOrBlank()) return null
        val at = Regex("(?i)(^|[\\s,])bearer(\\s|$)").find(header) ?: return null
        val rest = header.substring(at.range.last + 1)
        val params = HashMap<String, String>()
        val pair = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^\\s,]+)")
        for (match in pair.findAll(rest)) {
            val key = match.groupValues[1].lowercase()
            // A word with no "=" after it is the next scheme's name: stop there.
            if (key in params) break
            val quoted = match.groups[3]?.value
            params[key] = quoted?.replace(Regex("\\\\(.)"), "$1") ?: match.groupValues[2]
        }
        return Challenge(params["resource_metadata"], params["scope"], params["error"])
    }

    // ---- discovery -----------------------------------------------------------

    /**
     * The address the tokens are for: the server's, lower-case scheme and
     * host, no fragment, no trailing slash. Sent as `resource` so a token
     * meant for this server is not accepted by another.
     */
    fun canonicalResource(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val scheme = uri.scheme?.lowercase() ?: return url.trim()
        val host = uri.host?.lowercase() ?: return url.trim()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$path$query"
    }

    private fun origin(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty().trimEnd('/')

    /**
     * Where the server's protected-resource metadata may be, in the order to
     * try: the address its 401 named, then the well-known address with the
     * server's path, then without.
     */
    fun resourceMetadataUrls(serverUrl: String, fromChallenge: String?): List<String> {
        val origin = origin(serverUrl) ?: return listOfNotNull(fromChallenge)
        val path = pathOf(serverUrl)
        return buildList {
            fromChallenge?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(::add)
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()
    }

    /** What a server says about itself as a protected resource. */
    data class ResourceMeta(val resource: String, val authorizationServers: List<String>, val scopes: List<String>)

    fun resourceMeta(text: String): ResourceMeta? {
        val obj = parse(text) ?: return null
        val servers = strings(obj["authorization_servers"])
        if (servers.isEmpty()) return null
        return ResourceMeta(string(obj, "resource").orEmpty(), servers, strings(obj["scopes_supported"]))
    }

    /**
     * Where an authorization server's metadata may be, in the order the spec
     * gives: OAuth's well-known address, then OpenID Connect's two, with the
     * issuer's path inserted or appended.
     */
    fun serverMetadataUrls(issuer: String): List<String> {
        val origin = origin(issuer) ?: return emptyList()
        val path = pathOf(issuer)
        return if (path.isEmpty()) {
            listOf(
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$origin/.well-known/openid-configuration$path",
                "$origin$path/.well-known/openid-configuration",
            )
        }
    }

    /** What an authorization server publishes about itself, as far as a sign-in needs. */
    data class ServerMeta(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        /** Null when the metadata left it out, which means no PKCE; empty is the same. */
        val challengeMethods: List<String>?,
        val takesClientMetadataDocument: Boolean,
        val tokenAuthMethods: List<String>,
        val scopes: List<String>,
    ) {
        val supportsS256: Boolean get() = challengeMethods?.contains("S256") == true
    }

    fun serverMeta(text: String, issuerHint: String = ""): ServerMeta? {
        val obj = parse(text) ?: return null
        val authorize = string(obj, "authorization_endpoint") ?: return null
        val token = string(obj, "token_endpoint") ?: return null
        return ServerMeta(
            issuer = string(obj, "issuer") ?: issuerHint,
            authorizationEndpoint = authorize,
            tokenEndpoint = token,
            registrationEndpoint = string(obj, "registration_endpoint"),
            challengeMethods = (obj["code_challenge_methods_supported"] as? JsonArray)?.let { strings(it) },
            takesClientMetadataDocument =
                (obj["client_id_metadata_document_supported"] as? JsonPrimitive)?.booleanOrNull == true,
            tokenAuthMethods = strings(obj["token_endpoint_auth_methods_supported"]),
            scopes = strings(obj["scopes_supported"]),
        )
    }

    /**
     * The 2025-03-26 defaults, for a server that publishes no metadata at
     * all: the authorization server is the MCP server's own origin. PKCE is
     * assumed, as that version required it of every server.
     */
    fun defaultServerMeta(serverUrl: String): ServerMeta? {
        val origin = origin(serverUrl) ?: return null
        return ServerMeta(
            issuer = origin,
            authorizationEndpoint = "$origin/authorize",
            tokenEndpoint = "$origin/token",
            registrationEndpoint = "$origin/register",
            challengeMethods = listOf("S256"),
            takesClientMetadataDocument = false,
            tokenAuthMethods = emptyList(),
            scopes = emptyList(),
        )
    }

    /**
     * The scope to ask for: what the 401 asked for, else what the server
     * lists as its own, else nothing (the authorization server's default).
     */
    fun scopeFor(challenge: Challenge?, resource: ResourceMeta?): String? =
        challenge?.scope?.takeIf { it.isNotBlank() }
            ?: resource?.scopes?.takeIf { it.isNotEmpty() }?.joinToString(" ")

    // ---- the client ------------------------------------------------------------

    /** The keyboard's registration request: a public client, a code and a refresh token. */
    fun registrationBody(): String = buildJsonObject {
        put("client_name", CLIENT_NAME)
        putJsonArray("redirect_uris") { add(JsonPrimitive(REDIRECT_URI)) }
        putJsonArray("grant_types") {
            add(JsonPrimitive("authorization_code"))
            add(JsonPrimitive("refresh_token"))
        }
        putJsonArray("response_types") { add(JsonPrimitive("code")) }
        put("token_endpoint_auth_method", "none")
        put("client_uri", "https://wmkeyboard.pages.dev/")
    }.toString()

    data class Client(val id: String, val secret: String)

    fun registration(text: String): Client? {
        val obj = parse(text) ?: return null
        val id = string(obj, "client_id") ?: return null
        return Client(id, string(obj, "client_secret").orEmpty())
    }

    // ---- the sign-in ------------------------------------------------------------

    /** A fresh PKCE verifier: 64 characters of base64url. */
    fun newVerifier(): String = base64Url(ByteArray(VERIFIER_BYTES).also { SecureRandom().nextBytes(it) })

    fun newState(): String = base64Url(ByteArray(STATE_BYTES).also { SecureRandom().nextBytes(it) })

    fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(
        meta: ServerMeta,
        clientId: String,
        verifier: String,
        state: String,
        scope: String?,
        resource: String,
    ): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to clientId)
            add("redirect_uri" to REDIRECT_URI)
            add("code_challenge" to challengeFor(verifier))
            add("code_challenge_method" to "S256")
            add("state" to state)
            if (!scope.isNullOrBlank()) add("scope" to scope)
            add("resource" to resource)
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val sep = if ('?' in meta.authorizationEndpoint) "&" else "?"
        return meta.authorizationEndpoint + sep + query
    }

    /** The form that trades the code for tokens. */
    fun codeForm(code: String, verifier: String, clientId: String, clientSecret: String, resource: String): Map<String, String> =
        buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", REDIRECT_URI)
            put("client_id", clientId)
            put("code_verifier", verifier)
            put("resource", resource)
            if (clientSecret.isNotEmpty()) put("client_secret", clientSecret)
        }

    /** The form that trades a refresh token for a new access token. */
    fun refreshForm(state: McpAuthState): Map<String, String> = buildMap {
        put("grant_type", "refresh_token")
        put("refresh_token", state.refreshToken)
        put("client_id", state.clientId)
        if (state.resource.isNotEmpty()) put("resource", state.resource)
        if (state.clientSecret.isNotEmpty()) put("client_secret", state.clientSecret)
    }

    /**
     * [previous] with the tokens of a token response, or null when it holds
     * none. A refresh that hands back no new refresh token keeps the old one,
     * as RFC 6749 allows.
     */
    fun tokens(text: String, previous: McpAuthState, now: Long): McpAuthState? {
        val obj = parse(text) ?: return null
        val access = string(obj, "access_token") ?: return null
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        return previous.copy(
            accessToken = access,
            refreshToken = string(obj, "refresh_token") ?: previous.refreshToken,
            expiresAt = expiresIn?.takeIf { it > 0 }?.let { now + it * MS_PER_SECOND } ?: 0L,
            scope = string(obj, "scope") ?: previous.scope,
        )
    }

    /** The `code` and `state` a redirect brought back, or its `error`. */
    data class Redirect(val code: String?, val state: String?, val error: String?)

    fun redirect(uri: String): Redirect {
        val query = uri.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(key) to decode(value)
        }
        return Redirect(params["code"], params["state"], params["error_description"] ?: params["error"])
    }

    // ---- shared ------------------------------------------------------------------

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

    private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val SIX_BITS = 0x3f
    private const val BYTE = 0xff

    /**
     * Base64url with no padding, written out here because the JDK's encoder
     * needs Android 8 and the keyboard runs on 7. A server that compares
     * strings refuses a challenge with `=` on the end.
     */
    internal fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and BYTE shl 16) or (bytes[i + 1].toInt() and BYTE shl 8) or (bytes[i + 2].toInt() and BYTE)
            out.append(BASE64URL[n shr 18 and SIX_BITS]).append(BASE64URL[n shr 12 and SIX_BITS])
                .append(BASE64URL[n shr 6 and SIX_BITS]).append(BASE64URL[n and SIX_BITS])
            i += 3
        }
        val left = bytes.size - i
        if (left == 1) {
            val n = bytes[i].toInt() and BYTE shl 16
            out.append(BASE64URL[n shr 18 and SIX_BITS]).append(BASE64URL[n shr 12 and SIX_BITS])
        } else if (left == 2) {
            val n = (bytes[i].toInt() and BYTE shl 16) or (bytes[i + 1].toInt() and BYTE shl 8)
            out.append(BASE64URL[n shr 18 and SIX_BITS]).append(BASE64URL[n shr 12 and SIX_BITS])
                .append(BASE64URL[n shr 6 and SIX_BITS])
        }
        return out.toString()
    }
}
