package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.tools.feature.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Signing in to an MCP server that asks for it, through OAuth in a browser
 * tab: the requests [McpOAuth] writes, sent, and the tokens kept with the
 * server's settings ([McpAuthState]).
 *
 * The same shape as the backup sign-in: PKCE, no client secret in the app,
 * the browser rather than a WebView (so the keyboard never sees a password),
 * and the redirect back to `wmkeyboard://oauth/mcp` caught by the app's
 * `OAuthRedirectActivity`, which calls [deliver].
 */
object McpAuth {

    enum class Outcome { SIGNED_IN, CANCELLED, FAILED }

    /** How the last sign-in ended, for the server editor to say. */
    data class Result(val serverId: String, val outcome: Outcome, val message: String = "")

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result

    private val _exchanging = MutableStateFlow<String?>(null)

    /** The server whose code is being traded for a token right now. */
    val exchanging: StateFlow<String?> = _exchanging

    fun consume() {
        _result.value = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PENDING_FILE = "mcp_oauth_pending"
    private const val TIMEOUT_MS = 20_000
    private const val AUTH_HEADER = "WWW-Authenticate"
    private const val HTTP_UNAUTHORIZED = 401

    private val json = Json { ignoreUnknownKeys = true }

    /** What the redirect needs to finish, kept on disk: the settings process may die while the browser is in front. */
    @Serializable
    private data class Pending(
        val serverId: String,
        val verifier: String,
        val state: String,
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String,
        val resource: String,
        val issuer: String,
    )

    private fun pendingFile(context: Context) = File(context.noBackupFilesDir, PENDING_FILE)

    private fun takePending(context: Context): Pending? {
        val file = pendingFile(context)
        val saved = runCatching { json.decodeFromString(Pending.serializer(), file.readText()) }.getOrNull()
        file.delete()
        return saved
    }

    /**
     * Finds out how [server] signs people in, makes the keyboard known to its
     * authorization server, and returns the page to open in a browser.
     * Blocking; call on an IO dispatcher. Fails with an [McpException] that
     * says why when the server cannot be signed in to from here.
     */
    fun prepare(context: Context, server: McpServerSpec, headers: List<McpHeader>): String {
        val url = server.url.trim()
        val resource = McpOAuth.canonicalResource(url)
        val challenge = challengeOf(url, headers)
        val resourceMeta = McpOAuth.resourceMetadataUrls(url, challenge?.resourceMetadata)
            .firstNotNullOfOrNull { address -> fetch(address)?.let(McpOAuth::resourceMeta) }
        if (challenge == null && resourceMeta == null) throw McpException(R.string.ftools_mcp_auth_not_needed)
        val meta = if (resourceMeta != null) {
            val issuer = resourceMeta.authorizationServers.first()
            McpOAuth.serverMetadataUrls(issuer).firstNotNullOfOrNull { address ->
                fetch(address)?.let { McpOAuth.serverMeta(it, issuer) }
            } ?: throw McpException(R.string.ftools_mcp_auth_no_server)
        } else {
            // A server from before resource metadata (2025-03-26): its own
            // origin is the authorization server, with or without metadata.
            val origin = originOf(url)
            McpOAuth.serverMetadataUrls(origin)
                .firstNotNullOfOrNull { address -> fetch(address)?.let { McpOAuth.serverMeta(it, origin) } }
                ?: McpOAuth.defaultServerMeta(url)
                ?: throw McpException(R.string.ftools_mcp_auth_no_server)
        }
        if (!meta.supportsS256) throw McpException(R.string.ftools_mcp_auth_no_pkce)
        val registration = meta.registrationEndpoint
        val client = when {
            meta.takesClientMetadataDocument -> McpOAuth.Client(McpOAuth.CLIENT_METADATA_URL, "")
            registration != null -> register(registration)
            else -> null
        } ?: throw McpException(R.string.ftools_mcp_auth_no_registration)
        val verifier = McpOAuth.newVerifier()
        val state = McpOAuth.newState()
        val pending = Pending(
            serverId = server.id,
            verifier = verifier,
            state = state,
            tokenEndpoint = meta.tokenEndpoint,
            clientId = client.id,
            clientSecret = client.secret,
            resource = resource,
            issuer = meta.issuer,
        )
        pendingFile(context).writeText(json.encodeToString(Pending.serializer(), pending))
        return McpOAuth.authorizeUrl(meta, client.id, verifier, state, McpOAuth.scopeFor(challenge, resourceMeta), resource)
    }

    private fun originOf(url: String): String {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return url
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
    }

    /**
     * The server's Bearer challenge, from a request it should turn away: the
     * old handshake's opening, sent with the user's headers but no token.
     */
    private fun challengeOf(url: String, headers: List<McpHeader>): McpOAuth.Challenge? = runCatching {
        val sent = headers.filter { it.name.isNotBlank() && !it.name.trim().equals("authorization", ignoreCase = true) }
            .associate { it.name.trim() to it.value.trim() }
        val body = McpProtocol.request(0, "initialize", McpProtocol.initializeParams(McpProtocol.LEGACY_VERSION, ""))
        ToolHttp.exchange(
            url, "POST", body,
            headers = sent + ("Accept" to "application/json, text/event-stream"),
            wantHeaders = setOf(AUTH_HEADER),
            timeoutMs = TIMEOUT_MS,
            source = NetSource.AI_MCP,
        ) { exchange ->
            if (exchange.status == HTTP_UNAUTHORIZED) McpOAuth.challenge(exchange.headers[AUTH_HEADER]) else null
        }
    }.getOrNull()

    private fun fetch(url: String): String? = runCatching {
        ToolHttp.get(url, TIMEOUT_MS, mapOf("Accept" to "application/json"), source = NetSource.AI_MCP)
    }.getOrNull()

    private fun register(endpoint: String): McpOAuth.Client? = runCatching {
        ToolHttp.postJson(
            endpoint,
            McpOAuth.registrationBody(),
            TIMEOUT_MS,
            mapOf("Accept" to "application/json"),
            source = NetSource.AI_MCP,
        )
    }.getOrNull()?.let(McpOAuth::registration)

    /** Called by the app's redirect activity with the address the browser came back to. */
    fun deliver(context: Context, uri: String) {
        val pending = takePending(context) ?: return
        val back = McpOAuth.redirect(uri)
        val code = back.code
        if (code == null || back.state != pending.state) {
            _result.value = if (back.error != null || code != null) {
                Result(pending.serverId, Outcome.FAILED, back.error.orEmpty())
            } else {
                Result(pending.serverId, Outcome.CANCELLED)
            }
            return
        }
        val appContext = context.applicationContext
        _exchanging.value = pending.serverId
        scope.launch {
            try {
                val start = McpAuthState(
                    resource = pending.resource,
                    issuer = pending.issuer,
                    tokenEndpoint = pending.tokenEndpoint,
                    clientId = pending.clientId,
                    clientSecret = pending.clientSecret,
                )
                val text = ToolHttp.postForm(
                    pending.tokenEndpoint,
                    McpOAuth.codeForm(code, pending.verifier, pending.clientId, pending.clientSecret, pending.resource),
                    TIMEOUT_MS,
                    source = NetSource.AI_MCP,
                    headers = mapOf("Accept" to "application/json"),
                )
                val signed = McpOAuth.tokens(text, start, System.currentTimeMillis())
                if (signed == null) {
                    _result.value = Result(pending.serverId, Outcome.FAILED)
                } else {
                    SettingsRepository(appContext).setMcpAuth(pending.serverId, signed)
                    McpClient.forget(pending.serverId)
                    _result.value = Result(pending.serverId, Outcome.SIGNED_IN)
                }
            } catch (e: Exception) {
                _result.value = Result(pending.serverId, Outcome.FAILED, ToolHttp.friendlyMessage(appContext, e))
            } finally {
                _exchanging.value = null
            }
        }
    }

    /**
     * [state] with a new access token, or null when the refresh token was
     * turned down (it ran out, or the user revoked it) and only signing in
     * again will do. Blocking.
     */
    fun refresh(state: McpAuthState): McpAuthState? {
        if (!state.canRefresh) return null
        val text = runCatching {
            ToolHttp.postForm(
                state.tokenEndpoint,
                McpOAuth.refreshForm(state),
                TIMEOUT_MS,
                source = NetSource.AI_MCP,
                headers = mapOf("Accept" to "application/json"),
            )
        }.getOrNull() ?: return null
        return McpOAuth.tokens(text, state, System.currentTimeMillis())
    }
}
