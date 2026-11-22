package com.wasimaster.wmkeyboard.core.plugins

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A line of text for the user that this module cannot put into words itself.
 *
 * Nothing here has a Context: the manifest reader, the importer and the runtime
 * are plain classes, and unit tests drive them with no Android around them. So a
 * message travels as a string resource with its arguments, and the screen that
 * draws it turns it into words with [resolve]. That also keeps the words in the
 * user's language at the moment they are read.
 */
sealed interface PluginText {

    /**
     * A line this build wrote. [arg1] and [arg2] fill `%1$s` and `%2$s`, in that
     * order. Two arguments is the whole budget; a message that needs more is a
     * message that is too long to read.
     */
    data class Resource(
        @get:StringRes val textRes: Int,
        val arg1: Any? = null,
        val arg2: Any? = null,
    ) : PluginText

    /**
     * Text from the Lua virtual machine, which the plugin author wrote. It is
     * shown as it arrives: the app has no translation for a stranger's error
     * message.
     */
    data class Script(val text: String) : PluginText

    companion object {
        /** A message with no arguments. */
        fun of(@StringRes textRes: Int): PluginText = Resource(textRes)

        /** A message with one argument. */
        fun of(@StringRes textRes: Int, arg1: Any): PluginText = Resource(textRes, arg1)

        /** A message with two arguments, in the order the string names them. */
        fun of(@StringRes textRes: Int, arg1: Any, arg2: Any): PluginText = Resource(textRes, arg1, arg2)
    }
}

/** Puts a [PluginText] into words. Call this where the text is drawn. */
fun PluginText.resolve(context: Context): String = when (this) {
    is PluginText.Script -> text
    is PluginText.Resource -> when {
        arg1 == null -> context.getString(textRes)
        arg2 == null -> context.getString(textRes, arg1)
        else -> context.getString(textRes, arg1, arg2)
    }
}

/**
 * `plugin.json`, the manifest inside a `.wmplugin` archive.
 *
 * Every field is defaulted so a manifest missing one decodes rather than
 * throws; what a manifest may actually get away with is decided by
 * [PluginManifestCodec.read], which is strict on purpose. Unlike the data
 * addon formats — where a bad entry is repaired and reported so one broken
 * sticker doesn't lose the pack — a plugin is code, and half-understood code
 * does not get to run. Every rejection here is total and carries a reason the
 * user can read.
 */
@Serializable
data class PluginManifest(
    /** Must be [PluginManifestCodec.FORMAT]; the one tag that identifies the file. */
    val format: String = "",
    /** Container format version. */
    val version: Int = PluginManifestCodec.VERSION,
    /** Reverse-DNS, lowercase. Doubles as the install key and the directory name. */
    val id: String = "",
    val name: String = "",
    /** The plugin's own version, shown to the user and compared for updates. */
    val pluginVersion: String = "",
    val author: String = "",
    val description: String = "",
    /** The `wm.*` API level this script needs. Higher than the host's is refused. */
    val apiVersion: Int = PluginManifestCodec.API_VERSION,
    /** The archive entry holding the Python source. A lookup key, never a path. */
    val entry: String = PluginManifestCodec.DEFAULT_ENTRY,
    val permissions: List<String> = emptyList(),
    /**
     * What the script is written in. Only [PluginManifestCodec.LANGUAGE_PYTHON]
     * runs. A file that leaves it out is Python from API level 4 on and Lua
     * before it, which [PluginManifestCodec.read] refuses with a reason: Lua
     * plugins were retired in API 4.
     */
    val language: String = PluginManifestCodec.LANGUAGE_PYTHON,
    /**
     * Whether the plugin draws a panel. False for a plugin that only adds
     * [actions]: it has no `render`, and the keyboard's plugin list leaves it out.
     */
    val panel: Boolean = true,
    /** Selection actions: chips on the bar over selected text. Needs the `selection` permission. */
    val actions: List<PluginActionSpec> = emptyList(),
    /**
     * API 5: the hosts `wm.http` may reach, `api.example.com` or
     * `*.example.com`. Required with the `network` permission, and shown to
     * the user before install and beside the grant.
     */
    val hosts: List<String> = emptyList(),
    /** API 5: where the plugin shows up besides the Plugins panel. */
    val tool: PluginToolSpec = PluginToolSpec(),
    /** API 5: the plugin's settings page, drawn by the app. */
    val settings: List<PluginSettingSpec> = emptyList(),
    /**
     * API 5: the provider lists the plugin can answer for, each a row the
     * user may add to that list. The script answers with `@plugin.provider`.
     */
    val providers: List<PluginProviderSpec> = emptyList(),
)

/**
 * One provider a plugin offers: a row for one of the keyboard's provider
 * lists (web search, translate, dictionary, …), answered by the script's
 * `@plugin.provider(tool, id)` function with a JSON request and reply.
 */
@Serializable
data class PluginProviderSpec(
    /** The list it answers for: one of [TOOLS], the provider list's own id. */
    val tool: String = "",
    /** Unique for its tool within the plugin; defaults to the tool's name. */
    val id: String = "",
    /** The row's name in the list. Blank is the plugin's name. */
    val name: String = "",
    /** One line under the name. */
    val summary: String = "",
    /** For [TOOL_SEARCH]: it answers the Images tab too. */
    val images: Boolean = false,
) {
    /** [id], or the tool's name when blank. */
    val providerId: String get() = id.trim().ifEmpty { tool.trim() }

    /** How the host and the script name this provider: `tool/id`. */
    val key: String get() = tool.trim() + "/" + providerId

    companion object {
        const val TOOL_SEARCH = "search"

        /**
         * The lists a plugin may answer for, by `ProviderTool` id. Each one's
         * request and reply are a JSON shape the plugin guide gives. Left out
         * on purpose: AI (streams and tool calls; an OpenAI-compatible server
         * covers it), smart home (a live connection), the image generator and
         * photo translate (files), the speed test, and background photos
         * (every source has its own credit, sizes and download rules).
         */
        val TOOLS = setOf(
            TOOL_SEARCH, "translate", "dictionary", "synonyms", "vocab_autofill",
            "currency_fiat", "currency_crypto", "grammar",
            "gif", "sticker",
            "ocr", "music", "voice",
        )
    }
}

/**
 * Where an API 5 plugin appears in the keyboard. Every placement is the
 * host's own button, drawn in the keyboard's theme, so a plugin cannot put
 * anything on screen outside a panel the user opened.
 */
@Serializable
data class PluginToolSpec(
    /**
     * The tool's icon, on the toolbar, in the toolbox, on the floating button
     * and on the plugin's rows: a Material Symbols name the app carries
     * (`kitchen`, `directions_transit`), or `assets/<file>.svg`, a one-colour
     * 24×24 drawing in the archive that is tinted like every tool icon. Blank,
     * or a name the app does not carry, is the jigsaw.
     */
    val icon: String = "",
    /**
     * Put the plugin in the toolbox as a tool when it is installed. The user
     * can pin it, file it in a folder, or take it out like any tool, and any
     * plugin with a panel can be made a tool from its page whatever this says.
     */
    val tile: Boolean = false,
    /** The panel may open in a floating box. */
    val float: Boolean = false,
    /** `@keyword` while typing offers this plugin's chip; empty for none. */
    val keyword: String = "",
    /** Offer a chip in the suggestion strip when the keyword is typed. */
    val chip: Boolean = false,
    /** The panel's preferred height in dp, 0 for the keyboard's own. */
    val height: Int = 0,
    /**
     * The icon's colour, `#RRGGBB`: on the plugin's rows, in the toolbox and
     * on the toolbar, as each built-in tool wears its own. Blank, or anything
     * else, is the theme's icon colour.
     */
    val color: String = "",
    /**
     * The panel takes the whole keyboard, the toolbar's rows included, as
     * Notes and the scanners do: for a plugin taller than it is wide, such as
     * a planner or a form. Off, the toolbar stays above the panel.
     */
    val fullBleed: Boolean = false,
) {
    /** [color] as ARGB, or null when it is blank or not `#RRGGBB`. */
    val colorArgb: Long?
        get() = color.trim().takeIf { TOOL_COLOR.matches(it) }?.let { 0xFF000000L or it.drop(1).toLong(16) }

    private companion object {
        val TOOL_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

/**
 * One row of a plugin's settings page. The app draws it and stores the value;
 * the script reads `plugin.settings.<key>`.
 */
@Serializable
data class PluginSettingSpec(
    val key: String = "",
    /** [TYPES]: a switch, a text box, a number, one of [options], a website the user types, or a hidden key. */
    val type: String = TYPE_TEXT,
    val label: String = "",
    val summary: String = "",
    /**
     * As text: "true", "12", "metric". Ignored for [TYPE_SECRET]. A bare
     * `true` or `12` is read as its text: written either way, a manifest
     * installs, where a typed default used to fail the whole decode and the
     * file read as "not a plugin".
     */
    @Serializable(with = SettingDefaultSerializer::class)
    val default: String = "",
    /** For [TYPE_CHOICE]: the values, shown as written. */
    val options: List<String> = emptyList(),
) {
    /**
     * What the page stores for [value], a value the script passed to
     * `plugin.settings.set`, or null when it does not fit this row: a switch
     * takes a bool, a number a number, a choice one of [options], text and a
     * secret a string. A [TYPE_HOST] row is never written by the script.
     */
    fun storedText(value: JsonElement): String? {
        val p = value as? JsonPrimitive ?: return null
        return when (type) {
            TYPE_SWITCH -> p.booleanOrNull?.takeIf { !p.isString }?.toString()
            TYPE_NUMBER -> p.doubleOrNull?.takeIf { !p.isString && it.isFinite() }?.let { n ->
                if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()
            }
            TYPE_CHOICE -> p.contentOrNull?.takeIf { p.isString && it in options }
            TYPE_TEXT, TYPE_SECRET -> p.contentOrNull?.takeIf { p.isString }
            else -> null
        }
    }

    companion object {
        const val TYPE_SWITCH = "switch"
        const val TYPE_TEXT = "text"
        const val TYPE_NUMBER = "number"
        const val TYPE_CHOICE = "choice"

        /**
         * A website the user adds to the plugin's hosts: their own server, say.
         * The value is read as a server address (`https://money.example.com:8443/app`),
         * and only its host joins the list; see [PluginManifestCodec.serverHost].
         * Only the user writes it: a plugin setting one would widen its own reach.
         */
        const val TYPE_HOST = "host"

        /**
         * An API key or token. The page shows whether one is set, never the
         * value, and keeps it in the plugin's [PluginSecrets] under the setting's
         * key, so `secrets.get(key)` reads it too. Needs `storage`.
         */
        const val TYPE_SECRET = "secret"
        val TYPES = setOf(TYPE_SWITCH, TYPE_TEXT, TYPE_NUMBER, TYPE_CHOICE, TYPE_HOST, TYPE_SECRET)

        /** What the session sees for a secret row; the prelude reads the value through `secrets.get`. */
        val SECRET_MARKER: JsonObject = JsonObject(mapOf("\$secret" to JsonPrimitive(true)))
    }
}

/**
 * One selection action a plugin adds.
 *
 * When the user selects text and taps the chip, the keyboard calls the script's
 * `transform(text, action_id)` with the selection and puts the string it returns
 * in place of the selection, or after it when [replaces] is false.
 */
@Serializable
data class PluginActionSpec(
    /** Unique within the plugin. Lowercase letters, digits, `-` and `_`. */
    val id: String = "",
    /** The chip's label. */
    val name: String = "",
    /** One line for the settings list. */
    val summary: String = "",
    /** A Material Symbols name, or an SF Symbols name from a Clink action. Blank for the default. */
    val icon: String = "",
    /** False keeps the selection and adds the result after it. */
    val replaces: Boolean = true,
    /** [INPUT_SELECTION] passes the selection; [INPUT_NONE] passes an empty string. */
    val input: String = INPUT_SELECTION,
) {
    companion object {
        const val INPUT_SELECTION = "selection"
        const val INPUT_NONE = "none"
        val INPUTS = setOf(INPUT_SELECTION, INPUT_NONE)
    }
}

/** What reading a manifest produced. */
sealed interface PluginManifestResult {

    data class Ok(
        val manifest: PluginManifest,
        val permissions: List<PluginPermission>,
    ) : PluginManifestResult

    /** No manifest, or a manifest for something else entirely. */
    data object NotAPlugin : PluginManifestResult

    /** A plugin manifest this build will not accept. [reasonText] is user-facing. */
    data class Rejected(val reasonText: PluginText) : PluginManifestResult
}

/**
 * Reads and validates `plugin.json`.
 *
 * The validation exists for two audiences. The user gets a manifest whose
 * displayed strings cannot lie about their length or smuggle direction-override
 * characters into a name that will later appear above a permission list. The
 * filesystem gets an id that is provably a single safe path segment, since the
 * id becomes a directory under `filesDir/plugins/`.
 */
object PluginManifestCodec {

    const val FORMAT = "wmkeyboard-plugin"

    /** Container format version this build writes and understands. */
    const val VERSION = 1

    /**
     * The `wm.*` API level this build provides.
     *
     * 2 added `wm.reminders` (behind the `reminders` permission), the `ui.date`
     * widget and `os.time(table)`. A plugin that uses any of them says 2, so an
     * older build refuses it with "needs a newer version" rather than drawing a
     * panel with a widget missing.
     *
     * 3 added selection actions: the manifest's `actions`, `language` and
     * `panel`, the `selection` permission and the `transform` hook.
     *
     * 4 made Python the only plugin language, on the Rust port of PyMini
     * (`native/wm-python`), with `wm` and `ui` as globals. A manifest below 4
     * that does not say `"language": "python"` is a Lua plugin, and refused.
     *
     * 5 is the `wm` package (`from wm import plugin, ui, http`): callbacks,
     * decorators, classes, several modules per plugin, the new widgets, and
     * the host calls behind the API 5 permissions, with `hosts`, `tool` and
     * `settings` in the manifest. A plugin at 4 or below keeps the old globals.
     */
    const val API_VERSION = 5

    /** The level of the `wm` package and the permissions that came with it. */
    const val PACKAGE_API = 5

    /** The first level whose plugins are Python unless they say otherwise. */
    const val PYTHON_ONLY_API = 4

    const val DEFAULT_ENTRY = "main.py"

    /** Lua plugins, retired in API 4: recognised only so they can be refused, and migrated away. */
    const val LANGUAGE_LUA = "lua"

    /** A Python subset matching PyMini, run by `native/wm-python`. The only language that runs. */
    const val LANGUAGE_PYTHON = "python"

    /** More chips than this is a toolbar, not a plugin. */
    const val MAX_ACTIONS = 16

    /** Providers one plugin may offer. */
    const val MAX_PROVIDERS = 8
    private const val MAX_PROVIDER_SUMMARY = 80
    const val MAX_ACTION_NAME = 24
    private const val MAX_ACTION_SUMMARY = 80
    private const val MAX_ACTION_ICON = 48
    private val ACTION_ID = Regex("^[a-z0-9_-]{1,32}$")

    /**
     * A single safe path segment, lowercase so two ids cannot collide into one
     * directory on a case-insensitive filesystem. Leading character is
     * alphanumeric, which is also what stops `.` and `..` from ever matching.
     */
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

    /** Shown above a permission list, so it is kept short and inert. */
    const val MAX_NAME = 40
    private const val MAX_VERSION = 32
    private const val MAX_AUTHOR = 64
    private const val MAX_DESCRIPTION = 280
    private const val MAX_ENTRY = 64
    private const val MAX_PERMISSIONS = 24
    const val MAX_HOSTS = 16
    const val MAX_SETTINGS = 24
    private const val MAX_SETTING_OPTIONS = 24
    private const val MAX_SETTING_TEXT = 64
    private val SETTING_KEY = Regex("^[a-z_][a-z0-9_]{0,31}$")
    private val KEYWORD = Regex("^[a-z0-9]{1,16}$")
    /** A Material Symbols name, or an SVG in the archive's `assets/` folder; see [PluginToolSpec.icon]. */
    private val ICON = Regex("^([a-z0-9_]{0,48}|assets/[a-z0-9][a-z0-9_.-]{0,59}\\.svg)$")

    /**
     * A host name, or `*.` and one: letters, digits and dashes in dot-separated
     * labels, at least two of them, the last one not all digits. No IP
     * addresses, no ports, no `localhost`.
     */
    private val HOST = Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** Enough to echo an unknown permission back without pasting an essay into the UI. */
    private const val MAX_ECHO = 32

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(manifest: PluginManifest): String = json.encodeToString(manifest)

    /** The manifest fields a form edits, in the order [read] checks them. */
    enum class Field { API_VERSION, ID, NAME, VERSION, AUTHOR, DESCRIPTION, ENTRY, PERMISSIONS, ACTIONS, HOSTS, TOOL, SETTINGS, PROVIDERS }

    /** One thing wrong with one field, in the same words [read] refuses with. */
    data class Problem(val field: Field, val text: PluginText)

    @Suppress("ReturnCount")
    fun read(text: String): PluginManifestResult {
        val element = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return PluginManifestResult.NotAPlugin
        val decoded = runCatching { json.decodeFromJsonElement(PluginManifest.serializer(), element) }.getOrNull()
            ?: return PluginManifestResult.NotAPlugin
        // The class defaults to this build's level, which is right for a
        // manifest built in code. A file that leaves the field out was written
        // against the first level, before the field mattered, so it says 1.
        val leveled = if ("apiVersion" in element) decoded else decoded.copy(apiVersion = 1)
        // Likewise the language: unsaid, it is what plugins at that level were
        // written in, so an old Lua manifest reads as Lua and is refused below.
        val raw = if ("language" in element) leveled else leveled.copy(language = languageFor(apiLevel(leveled)))
        if (raw.format != FORMAT) return PluginManifestResult.NotAPlugin
        problems(raw).firstOrNull()?.let { return PluginManifestResult.Rejected(it.text) }

        val permissions = raw.permissions.take(MAX_PERMISSIONS)
            .mapNotNull { PluginPermission.parse(it) }
            .distinct()
        val manifest = raw.copy(
            version = if (raw.version <= 0) VERSION else raw.version,
            id = sanitise(raw.id, Field.ID),
            name = sanitise(raw.name, Field.NAME),
            pluginVersion = sanitise(raw.pluginVersion, Field.VERSION),
            author = sanitise(raw.author, Field.AUTHOR),
            description = sanitise(raw.description, Field.DESCRIPTION),
            apiVersion = apiLevel(raw),
            entry = entryOf(raw),
            permissions = permissions.map { it.wire },
            language = raw.language.trim(),
            panel = raw.panel,
            actions = raw.actions.map { sanitiseAction(it) },
            hosts = raw.hosts.map { normaliseHost(it) }.distinct(),
            tool = raw.tool.copy(
                icon = raw.tool.icon.trim(),
                keyword = raw.tool.keyword.trim(),
                height = raw.tool.height.coerceIn(0, MAX_TOOL_HEIGHT),
                color = raw.tool.color.trim().takeIf { raw.tool.colorArgb != null }.orEmpty(),
            ),
            settings = raw.settings.map { sanitiseSetting(it) },
            providers = raw.providers.map { sanitiseProvider(it) },
        )
        return PluginManifestResult.Ok(manifest, permissions)
    }

    /**
     * Everything wrong with [manifest], in the order [read] looks. Empty means
     * [read] accepts it, given the [FORMAT] tag, which a form always writes.
     *
     * [read] refuses with the first of these, and the plugin editor's manifest
     * form shows all of them as the user types. The rules exist once, so a
     * manifest the form calls valid is a manifest the importer installs.
     *
     * Author, description and entry never appear here: one that is too long is
     * shortened, not refused. [sanitise] shows what will be kept.
     */
    fun problems(manifest: PluginManifest): List<Problem> {
        val out = ArrayList<Problem>()
        if (apiLevel(manifest) > API_VERSION) {
            out += Problem(Field.API_VERSION, PluginText.of(R.string.core_plugins_reject_newer_version))
        }
        if (!ID_PATTERN.matches(sanitise(manifest.id, Field.ID))) {
            out += Problem(Field.ID, PluginText.of(R.string.core_plugins_reject_bad_id))
        }
        val name = sanitise(manifest.name, Field.NAME)
        if (name.isEmpty()) {
            out += Problem(Field.NAME, PluginText.of(R.string.core_plugins_reject_no_name))
        }
        if (sanitise(manifest.pluginVersion, Field.VERSION).isEmpty()) {
            out += Problem(Field.VERSION, PluginText.of(R.string.core_plugins_reject_no_version, name))
        }
        manifest.permissions.take(MAX_PERMISSIONS)
            .firstOrNull { PluginPermission.parse(it) == null }
            ?.let { unknown ->
                out += Problem(
                    Field.PERMISSIONS,
                    PluginText.of(
                        R.string.core_plugins_reject_unknown_permission,
                        displayText(unknown, MAX_ECHO),
                        name,
                    ),
                )
            }
        out += actionProblems(manifest, name)
        out += packageProblems(manifest, name)
        return out
    }

    /** The API 5 rules: permissions new in 5, hosts, the tool block and settings. */
    private fun packageProblems(manifest: PluginManifest, name: String): List<Problem> {
        val out = ArrayList<Problem>()
        val level = apiLevel(manifest)
        val permissions = manifest.permissions.mapNotNull { PluginPermission.parse(it) }
        permissions.firstOrNull { it.sinceApi > level }?.let {
            out += Problem(Field.PERMISSIONS, PluginText.of(R.string.core_plugins_reject_permission_needs_api_5, it.wire, name))
        }
        val hosts = manifest.hosts.map { normaliseHost(it) }
        if (PluginPermission.Network in permissions && hosts.isEmpty() && manifest.settings.none { it.type.trim() == PluginSettingSpec.TYPE_HOST }) {
            out += Problem(Field.HOSTS, PluginText.of(R.string.core_plugins_reject_network_without_hosts, name))
        }
        (hosts.firstOrNull { !isValidHost(it) } ?: hosts.drop(MAX_HOSTS).firstOrNull())?.let {
            out += Problem(Field.HOSTS, PluginText.of(R.string.core_plugins_reject_bad_host, displayText(it, MAX_ECHO), name))
        }
        val tool = manifest.tool
        if (!KEYWORD.matches(tool.keyword.trim()) && tool.keyword.isNotBlank() || !ICON.matches(tool.icon.trim())) {
            out += Problem(Field.TOOL, PluginText.of(R.string.core_plugins_reject_bad_tool, name))
        }
        val settings = manifest.settings
        val keys = settings.map { it.key.trim() }
        val badSetting = settings.size > MAX_SETTINGS ||
            keys.toSet().size != keys.size ||
            settings.any { s ->
                !SETTING_KEY.matches(s.key.trim()) ||
                    displayText(s.label, MAX_SETTING_TEXT).isEmpty() ||
                    s.type.trim() !in PluginSettingSpec.TYPES ||
                    (s.type.trim() == PluginSettingSpec.TYPE_CHOICE && (s.options.isEmpty() || s.options.size > MAX_SETTING_OPTIONS)) ||
                    (s.type.trim() == PluginSettingSpec.TYPE_SECRET && PluginPermission.Storage !in permissions)
            }
        if (badSetting) out += Problem(Field.SETTINGS, PluginText.of(R.string.core_plugins_reject_bad_setting, name))
        val providers = manifest.providers.map { sanitiseProvider(it) }
        val badProvider = providers.size > MAX_PROVIDERS ||
            providers.map { it.key }.toSet().size != providers.size ||
            providers.any { it.tool !in PluginProviderSpec.TOOLS || !ACTION_ID.matches(it.providerId) }
        if (badProvider) out += Problem(Field.PROVIDERS, PluginText.of(R.string.core_plugins_reject_bad_provider, name))
        // A symbol name for the icon, its colour and full-bleed are the tool
        // fields older plugins may carry: they only change how the keyboard
        // draws them, and need nothing from the wm package. An SVG icon is a
        // file in `assets/`, which only API 5 archives ship.
        val iconOnly = tool.copy(icon = "", color = "", fullBleed = false) == PluginToolSpec() && !tool.icon.trim().startsWith("assets/")
        val usesPackageFields = hosts.isNotEmpty() || settings.isNotEmpty() || providers.isNotEmpty() ||
            (tool != PluginToolSpec() && !iconOnly)
        if (usesPackageFields && level < PACKAGE_API) {
            out += Problem(Field.API_VERSION, PluginText.of(R.string.core_plugins_reject_permission_needs_api_5, "hosts/tool/settings/providers", name))
        }
        return out
    }

    /** Lowercase, trimmed, without a scheme or a trailing dot or slash. */
    fun normaliseHost(raw: String): String =
        raw.trim().lowercase().removePrefix("https://").trimEnd('/', '.')

    /** Whether [host] (already normalised) is a name `wm.http` may be allowed to reach. */
    fun isValidHost(host: String): Boolean = host.length <= MAX_HOST_LENGTH && HOST.matches(host)

    /** Whether [host] is covered by [allowed]: equal, or under a `*.` entry. */
    fun hostAllowed(host: String, allowed: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return allowed.any { entry ->
            if (entry.startsWith("*.")) {
                val base = entry.removePrefix("*.")
                h == base || h.endsWith(".$base")
            } else {
                h == entry
            }
        }
    }

    /**
     * The host of a server address the user typed into a `host` setting:
     * `money.example.com`, `https://money.example.com:8443/firefly`,
     * `http://192.168.1.20:8080` or `nas`. Scheme, port and path are dropped;
     * the plugin reads the whole value itself. Null when there is no usable
     * host: a user name in it, a scheme other than http(s), loopback, or an
     * IP address that is not on a local network.
     */
    fun serverHost(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { java.net.URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null) return null
        val host = addressHost(uri.host ?: return null) ?: return null
        return host.takeIf { isLocalHost(it) || isValidHost(it) }
    }

    /** [raw] as a host name to compare: lowercase ASCII, no trailing dot. Null if it cannot be one. */
    fun addressHost(raw: String): String? =
        runCatching { java.net.IDN.toASCII(raw.trim()).lowercase().trimEnd('.') }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Whether [host] is a server on the local network, which `wm.http` reaches
     * only with `network.local` and only when the user typed it: an IPv4
     * address in 10/8, 172.16/12, 192.168/16, 100.64/10 (Tailscale and other
     * carrier-grade NAT) or 169.254/16; a one-word name (`nas`); or a name
     * under one of [LOCAL_SUFFIXES]. Loopback (`localhost`, 127/8, `::1`) is
     * never local here: it is the phone itself, and stays refused.
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h.startsWith("[") || ':' in h) return false
        if (h == "localhost" || h.endsWith(".localhost")) return false
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) }) {
            val n = parts.map { it.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 ||
                (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168) ||
                (n[0] == 100 && n[1] in 64..127) ||
                (n[0] == 169 && n[1] == 254)
        }
        if (!parts.all { LABEL.matches(it) }) return false
        if (parts.size == 1) return !h.all(Char::isDigit)
        return LOCAL_SUFFIXES.any { suffix -> h.endsWith(".$suffix") }
    }

    /** Name endings that never resolve on the public internet. */
    val LOCAL_SUFFIXES = listOf("local", "lan", "home", "home.arpa", "internal", "localdomain")

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun sanitiseSetting(setting: PluginSettingSpec): PluginSettingSpec = setting.copy(
        key = setting.key.trim(),
        type = setting.type.trim(),
        label = displayText(setting.label, MAX_SETTING_TEXT),
        summary = displayText(setting.summary, MAX_DESCRIPTION),
        default = displayText(setting.default, MAX_SETTING_DEFAULT),
        options = setting.options.take(MAX_SETTING_OPTIONS).map { displayText(it, MAX_SETTING_TEXT) },
    )

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_SETTING_DEFAULT = 256
    private const val MAX_TOOL_HEIGHT = 600

    /**
     * The rules for [PluginManifest.actions], [PluginManifest.language] and
     * [PluginManifest.panel]. As strict as the permission check: a plugin this
     * build cannot fully describe to the user does not install.
     */
    private fun actionProblems(manifest: PluginManifest, name: String): List<Problem> {
        val out = ArrayList<Problem>()
        fun problem(field: Field, @StringRes res: Int) {
            out += Problem(field, PluginText.of(res, name))
        }
        val language = manifest.language.trim()
        val actions = manifest.actions
        val declaresSelection = manifest.permissions.any { it.trim() == PluginPermission.SelectedText.wire }
        when (language) {
            LANGUAGE_PYTHON -> Unit
            LANGUAGE_LUA -> problem(Field.API_VERSION, R.string.core_plugins_reject_lua)
            else -> problem(Field.ENTRY, R.string.core_plugins_reject_unknown_language)
        }
        when {
            actions.isNotEmpty() && !declaresSelection ->
                problem(Field.PERMISSIONS, R.string.core_plugins_reject_actions_need_selection)
            actions.isEmpty() && declaresSelection ->
                problem(Field.ACTIONS, R.string.core_plugins_reject_selection_without_actions)
            actions.isEmpty() && manifest.providers.isEmpty() && !manifest.panel ->
                problem(Field.ACTIONS, R.string.core_plugins_reject_nothing_to_show)
            actions.size > MAX_ACTIONS -> problem(Field.ACTIONS, R.string.core_plugins_reject_too_many_actions)
        }
        val ids = actions.map { it.id.trim() }
        if (ids.any { !ACTION_ID.matches(it) } || ids.toSet().size != ids.size) {
            problem(Field.ACTIONS, R.string.core_plugins_reject_bad_action_id)
        } else if (actions.any { displayText(it.name, MAX_ACTION_NAME).isEmpty() }) {
            problem(Field.ACTIONS, R.string.core_plugins_reject_action_no_name)
        } else if (actions.any { it.input.trim() !in PluginActionSpec.INPUTS }) {
            problem(Field.ACTIONS, R.string.core_plugins_reject_action_input)
        }
        return out
    }

    /** [provider] as [read] stores it: trimmed, its name and summary cut to their lengths. */
    fun sanitiseProvider(provider: PluginProviderSpec): PluginProviderSpec = provider.copy(
        tool = provider.tool.trim(),
        id = provider.id.trim(),
        name = displayText(provider.name, MAX_NAME),
        summary = displayText(provider.summary, MAX_PROVIDER_SUMMARY),
        images = provider.images && provider.tool.trim() == PluginProviderSpec.TOOL_SEARCH,
    )

    /** [action] as [read] stores it: trimmed, and every displayed string cut to its length. */
    fun sanitiseAction(action: PluginActionSpec): PluginActionSpec = action.copy(
        id = action.id.trim(),
        name = displayText(action.name, MAX_ACTION_NAME),
        summary = displayText(action.summary, MAX_ACTION_SUMMARY),
        icon = displayText(action.icon, MAX_ACTION_ICON),
        input = action.input.trim(),
    )

    /** The entry the manifest names, or `main.py` when it names none. */
    private fun entryOf(manifest: PluginManifest): String =
        displayText(manifest.entry, MAX_ENTRY).ifEmpty { DEFAULT_ENTRY }

    /** The language a manifest that does not name one was written in, by its API level. */
    fun languageFor(apiLevel: Int): String = if (apiLevel >= PYTHON_ONLY_API) LANGUAGE_PYTHON else LANGUAGE_LUA

    /**
     * What [read] stores for [raw] in [field]. The id is trimmed and lowercased;
     * every displayed string loses its control, format and private-use characters
     * and is cut to its length. A form shows this beside the field, so a name that
     * loses a zero-width joiner visibly loses it before the plugin is installed.
     */
    fun sanitise(raw: String, field: Field): String = when (field) {
        Field.ID -> raw.trim().lowercase()
        Field.NAME -> displayText(raw, MAX_NAME)
        Field.VERSION -> displayText(raw, MAX_VERSION)
        Field.AUTHOR -> displayText(raw, MAX_AUTHOR)
        Field.DESCRIPTION -> displayText(raw, MAX_DESCRIPTION)
        Field.ENTRY -> displayText(raw, MAX_ENTRY).ifEmpty { DEFAULT_ENTRY }
        Field.API_VERSION, Field.PERMISSIONS, Field.ACTIONS, Field.HOSTS, Field.TOOL, Field.SETTINGS, Field.PROVIDERS -> raw.trim()
    }

    /**
     * Zero or less counts as 1, the level every plugin written before the
     * field mattered was written against. [read] treats a missing field the
     * same way.
     */
    private fun apiLevel(manifest: PluginManifest): Int =
        if (manifest.apiVersion <= 0) 1 else manifest.apiVersion

    /**
     * Trims [raw] to something safe to draw: no control characters, no
     * Unicode format characters, and no longer than [max].
     *
     * Format characters go because the bidirectional overrides are among them,
     * and a plugin name is rendered directly above the list of what that plugin
     * may do — a name that can reorder the text around it is a name that can lie
     * about it. The cost is that a zero-width joiner in an emoji sequence is
     * dropped too, which is a fair trade for a 40-character label.
     *
     * Walks code points rather than chars: a `Char` in the middle of a surrogate
     * pair reports itself as a surrogate, so filtering per-char would quietly
     * delete every emoji and every non-BMP script from a name. Whole pairs are
     * kept and only *lone* surrogates — which are malformed anyway — are dropped.
     */
    internal fun displayText(raw: String, max: Int): String {
        val trimmed = raw.trim()
        val out = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            val width = Character.charCount(cp)
            i += width
            if (!isDisplayable(cp)) continue
            if (out.length + width > max) break
            out.appendCodePoint(cp)
        }
        return out.toString().trim()
    }

    private fun isDisplayable(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        -> false

        else -> true
    }
}

/** A setting's default as text, whether the manifest wrote `"true"` or `true`. */
internal object SettingDefaultSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonNull -> JsonPrimitive("")
        element is JsonPrimitive && !element.isString -> JsonPrimitive(element.content)
        else -> element
    }
}
