package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How the keyboard talks to one MCP server (#573).
 *
 * [AUTO] is what a pasted address gets: the client tries the current
 * Streamable HTTP transport first and falls back to the old HTTP+SSE one when
 * the server answers like an old server. [SSE] skips straight to the old one,
 * for a server whose POST endpoint answers something misleading.
 */
@Serializable
enum class McpTransport { AUTO, STREAMABLE_HTTP, SSE }

/**
 * What a model may do with one MCP tool.
 *
 * A tool can send mail or delete a file, and a prompt hidden in the text the
 * user is editing could steer a model into calling it. So a tool the server
 * does not mark read-only starts on [ASK], which stops the run and shows the
 * user the call before it goes anywhere. [OFF] keeps the tool out of the
 * prompt altogether, which also saves the tokens its description costs.
 */
@Serializable
enum class McpToolPolicy {
    ALLOW,
    ASK,
    OFF,
    ;

    companion object {
        /** The policy of a tool the user has not set: read-only runs, the rest ask. */
        fun defaultFor(tool: McpTool): McpToolPolicy = if (tool.readOnly && !tool.destructive) ALLOW else ASK
    }
}

/** One header sent with every request to a server: `Authorization: Bearer …`, an API key. */
@Serializable
data class McpHeader(
    val name: String = "",
    val value: String = "",
)

/**
 * One MCP server the user added.
 *
 * Its headers are not here. They usually carry a token, so they are stored
 * under their own preference, which a settings export leaves out unless asked
 * (see `SettingsBackup.SECRET_KEYS`). A backup without secrets then still
 * brings the servers back, and only the tokens have to be typed in again.
 *
 * [toolPolicies] holds only what the user changed. A tool with no entry gets
 * [McpToolPolicy.defaultFor], decided from what the server says about it, so a
 * server that later marks a tool read-only is believed without a migration.
 */
@Serializable
data class McpServerSpec(
    val id: String,
    val name: String = "",
    val url: String = "",
    val transport: McpTransport = McpTransport.AUTO,
    val enabled: Boolean = true,
    val toolPolicies: Map<String, McpToolPolicy> = emptyMap(),
    /**
     * For a server that starts as a local program (`"command": "npx"`): the
     * line that runs it behind a small HTTP bridge, which [url] then points
     * at. Android has no way to start the program itself, but Termux on the
     * same phone, or a computer on the network, does. Empty for an ordinary
     * server.
     */
    val bridgeCommand: String = "",
) {
    fun policyFor(tool: McpTool): McpToolPolicy = toolPolicies[tool.name] ?: McpToolPolicy.defaultFor(tool)

    /** What the user sees: the name they gave it, or the server's host. */
    val label: String get() = name.trim().ifEmpty { AiToolActivity.domainOf(url) }

    companion object {
        /** Prefix of a server's id, followed by the time it was added. */
        const val ID_PREFIX = "mcp_"
    }
}

/**
 * A sign-in to one MCP server through OAuth: the client this keyboard is
 * known as there, and the tokens it was given. Secret like the headers, and
 * kept the same way, by server id under a preference of its own.
 *
 * [resource] is the server address the tokens are for. A changed address
 * makes them useless there, and the client signs in again rather than sending
 * one server's token to another.
 */
@Serializable
data class McpAuthState(
    val resource: String = "",
    val issuer: String = "",
    val tokenEndpoint: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Epoch millis the access token stops working at; 0 when the server did not say. */
    val expiresAt: Long = 0L,
    val scope: String = "",
) {
    /** Whether the access token can still be sent: a margin before it runs out, so it does not run out on the way. */
    fun usable(now: Long): Boolean =
        accessToken.isNotEmpty() && (expiresAt == 0L || expiresAt - EXPIRY_MARGIN_MS > now)

    val canRefresh: Boolean get() = refreshToken.isNotEmpty() && tokenEndpoint.isNotEmpty()

    companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

/** The server list and the header map as preference strings. */
object McpServerCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        // An enum value a newer version wrote falls back to the field's
        // default rather than dropping the whole list.
        coerceInputValues = true
        encodeDefaults = false
    }

    private val headersSerializer =
        MapSerializer(String.serializer(), ListSerializer(McpHeader.serializer()))

    fun encodeServers(servers: List<McpServerSpec>): String = json.encodeToString(servers)

    fun decodeServers(value: String): List<McpServerSpec> {
        // Blank on almost every device: nothing is stored until a server is added.
        if (value.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<McpServerSpec>>(value) }.getOrDefault(emptyList())
    }

    fun encodeHeaders(headers: Map<String, List<McpHeader>>): String =
        json.encodeToString(headersSerializer, headers.filterValues { it.isNotEmpty() })

    fun decodeHeaders(value: String): Map<String, List<McpHeader>> {
        if (value.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(headersSerializer, value) }.getOrDefault(emptyMap())
    }

    /**
     * The policies the user set on the keyboard's own AI tools (#698), by tool
     * name. Same shape and same enum as a server's [McpServerSpec.toolPolicies],
     * so one choice row and one approval path serve both. A name no longer in
     * the catalogue is kept rather than dropped: a downgrade and an upgrade
     * again should not forget it.
     */
    fun encodePolicies(policies: Map<String, McpToolPolicy>): String =
        json.encodeToString(policiesSerializer, policies)

    fun decodePolicies(value: String): Map<String, McpToolPolicy> {
        if (value.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(policiesSerializer, value) }.getOrDefault(emptyMap())
    }

    private val policiesSerializer = MapSerializer(String.serializer(), McpToolPolicy.serializer())

    private val authSerializer = MapSerializer(String.serializer(), McpAuthState.serializer())

    fun encodeAuth(auth: Map<String, McpAuthState>): String = json.encodeToString(authSerializer, auth)

    fun decodeAuth(value: String): Map<String, McpAuthState> {
        if (value.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(authSerializer, value) }.getOrDefault(emptyMap())
    }
}
