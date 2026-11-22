package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A half-open stretch of the source, in UTF-16 offsets. */
data class PySpan(val start: Int, val end: Int)

/** What kind of token, as `libwm_python_jni`'s lexer names them. */
enum class PyTokenKind {
    WHITESPACE, NEWLINE, COMMENT, STRING, FSTRING, NUMBER, NAME, KEYWORD, SOFT_KEYWORD, OPERATOR, UNKNOWN;

    /** Whether the token is code rather than space or a comment. */
    val isCode: Boolean get() = this != WHITESPACE && this != NEWLINE && this != COMMENT
}

/** How a name is coloured: decided by the lexer's neighbours and the API model, never by a parse. */
enum class PyNameStyle { PLAIN, BUILTIN, API, FUNCTION, PARAMETER, CONSTANT, ATTRIBUTE }

/**
 * The tokens of one source: lossless, so they cover every character once.
 * Packed as the library hands them over, three ints a token.
 */
class PyTokens(val source: String, private val packed: IntArray) {
    val size: Int get() = packed.size / 3

    fun kind(index: Int): PyTokenKind = KINDS.getOrElse(packed[index * 3] and KIND_MASK) { PyTokenKind.UNKNOWN }

    fun unterminated(index: Int): Boolean = packed[index * 3] and UNTERMINATED != 0

    fun nameStyle(index: Int): PyNameStyle = STYLES.getOrElse((packed[index * 3] ushr STYLE_SHIFT) and STYLE_MASK) { PyNameStyle.PLAIN }

    fun start(index: Int): Int = packed[index * 3 + 1]

    fun end(index: Int): Int = packed[index * 3 + 2]

    /** Whether token [index] is exactly [text]. */
    fun matches(index: Int, text: String): Boolean {
        val start = start(index)
        val end = end(index)
        return end - start == text.length && source.regionMatches(start, text, 0, text.length)
    }

    private companion object {
        const val KIND_MASK = 0xFF
        const val UNTERMINATED = 0x100
        const val STYLE_SHIFT = 12
        const val STYLE_MASK = 0xF
        val KINDS = PyTokenKind.entries
        val STYLES = PyNameStyle.entries
    }
}

enum class PySeverity { ERROR, WARNING, INFO }

/** One problem. [code] names its message (`py_diag_<code>` in the app); [args] fill it. */
data class PyDiagnostic(
    val code: String,
    val severity: PySeverity,
    val span: PySpan,
    val args: List<String>,
    /** What one tap does about it, when there is one obvious thing. */
    val fix: PyFix? = null,
)

/** A change that settles a diagnostic: to the script, or to plugin.json. */
sealed interface PyFix {
    /** Put [text] in place of the diagnostic's span. */
    data class Replace(val text: String) : PyFix

    /** Insert [text] at [at]. */
    data class Insert(val at: Int, val text: String) : PyFix

    /** Add the permission [wire] to plugin.json. */
    data class Permission(val wire: String) : PyFix

    /** Add [host] to plugin.json's hosts. */
    data class Host(val host: String) : PyFix

    /** Declare the provider [tool]/[id] in plugin.json. */
    data class Provider(val tool: String, val id: String) : PyFix

    /** List the selection action [id] in plugin.json. */
    data class Action(val id: String) : PyFix

    /** Set plugin.json's tool.keyword to [word]. */
    data class Keyword(val word: String) : PyFix

    /** Add a text setting [key] to plugin.json. */
    data class Setting(val key: String) : PyFix
}

/** The call around the caret, with the argument being typed. */
data class PySignature(
    /** `http.get(url, params=None, **options)`. */
    val label: String,
    /** Each parameter's place in [label]. */
    val params: List<PySpan>,
    val names: List<String>,
    /** The parameter the caret is in, or -1. */
    val active: Int,
    val doc: String,
    /** What the active parameter takes, in words, when the editor knows. */
    val paramDoc: String?,
    val values: List<String>,
)

/** What a name is: for a long press, or a pointer resting on it. */
data class PyHover(val span: PySpan, val title: String, val doc: String, val kind: String)

/** A provider list a plugin can answer, with a request as the keyboard sends one. */
data class PyProviderTool(
    val tool: String,
    val label: String,
    /** The request's keys, each with what it holds. */
    val request: List<Pair<String, String>>,
    /** A request to try the provider with, as JSON. */
    val sample: String,
    val reply: String,
)

/** A named thing for the outline, [depth] levels inside other functions. */
data class PyOutlineItem(val name: String, val kind: String, val depth: Int, val span: PySpan, val nameSpan: PySpan)

data class PyAnalysis(
    val diagnostics: List<PyDiagnostic>,
    val outline: List<PyOutlineItem>,
    val folds: List<PySpan>,
)

data class PyCompletionItem(
    val label: String,
    /** variable, parameter, function, method, module, constant, field, keyword, snippet or value. */
    val kind: String,
    val insert: String,
    /** Where the caret goes, as an offset into [insert]. */
    val caret: Int,
    val detail: String?,
    val docPath: String?,
    /** Its doc, one paragraph. */
    val doc: String? = null,
    /** Choosing it leaves a blank with suggestions of its own, so the list opens again. */
    val reopen: Boolean = false,
)

data class PyCompletions(val replace: PySpan, val items: List<PyCompletionItem>)

/** One name of the plugin API or of what PyMini offers, with its documentation. */
data class PyApiEntry(
    val path: String,
    val parent: String,
    val name: String,
    val kind: String,
    val signature: String?,
    val doc: String,
    /** The permission a call needs, as its manifest wire string, or null. */
    val permission: String?,
)

data class PyApiModel(val entries: List<PyApiEntry>, val eventTypes: List<String>, val hooks: List<String>)

/** Why a rename cannot take a name. */
enum class PyRenameProblem { NOT_A_NAME, KEYWORD, BUILTIN, TAKEN, API, HOOK }

data class PyRename(val spans: List<PySpan>, val problem: PyRenameProblem?)

/**
 * What the plugin's manifest says, for the checks that depend on it: a call
 * to `wm.storage` without the `storage` permission, a missing `render()` in
 * a plugin with a panel.
 */
data class PyHostShape(
    val storage: Boolean,
    val reminders: Boolean,
    val selection: Boolean,
    val panel: Boolean,
    val actions: List<String>,
    /**
     * The whole plugin.json, for the API 5 checks and suggestions: its
     * permissions, hosts, providers, settings and keyword.
     */
    val manifest: PluginManifest? = null,
    /** The plugin's own other modules, by dotted name. */
    val modules: List<String> = emptyList(),
) {
    companion object {
        fun of(manifest: PluginManifest, modules: List<String> = emptyList()): PyHostShape {
            val permissions = manifest.permissions.mapNotNull { PluginPermission.parse(it) }.toSet()
            return PyHostShape(
                storage = PluginPermission.Storage in permissions,
                reminders = PluginPermission.Reminders in permissions,
                selection = PluginPermission.SelectedText in permissions,
                panel = manifest.panel,
                actions = manifest.actions.map { it.id },
                manifest = manifest,
                modules = modules,
            )
        }
    }
}

/**
 * The plugin editor's language service: `libwm_python_jni`'s tooling half, over
 * [PythonNative]. Every function is total and fast enough for the editor to
 * call after a pause in typing, off the main thread; each answers the empty
 * thing when the library is not here yet, so the editor degrades to a plain
 * text field rather than failing.
 *
 * The "will it run" verdict comes from the interpreter's own parser
 * ([PythonNative.nativeCheck]), so a file the editor calls clean is a file
 * the keyboard can load.
 */
object PythonTooling {

    private val json = Json { ignoreUnknownKeys = true }

    val ready: Boolean get() = PythonNative.available

    fun tokens(source: String): PyTokens =
        PyTokens(source, if (ready) PythonNative.nativeTokens(source) else intArrayOf())

    /** Diagnostics, outline and folds. [host] null skips the checks that read the manifest. */
    fun analyse(source: String, host: PyHostShape?): PyAnalysis {
        if (!ready) return PyAnalysis(emptyList(), emptyList(), emptyList())
        val verdict = PythonNative.nativeCheck(source)
        val root = parse(PythonNative.nativeAnalyse(source, hostJson(host, verdict))) as? JsonObject
            ?: return PyAnalysis(emptyList(), emptyList(), emptyList())
        return PyAnalysis(
            diagnostics = root.array("diagnostics").mapNotNull { diagnostic(it) },
            outline = root.array("outline").mapNotNull { outline(it) },
            folds = root.array("folds").mapNotNull { pair(it) },
        )
    }

    fun complete(source: String, caret: Int, explicit: Boolean, host: PyHostShape?): PyCompletions? {
        if (!ready) return null
        val root = parse(PythonNative.nativeComplete(source, caret, explicit, hostJson(host, null))) as? JsonObject
            ?: return null
        val start = root.int("replaceStart") ?: return null
        val end = root.int("replaceEnd") ?: return null
        val items = root.array("items").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            PyCompletionItem(
                label = item.string("label") ?: return@mapNotNull null,
                kind = item.string("kind") ?: "value",
                insert = item.string("insert") ?: item.string("label") ?: "",
                caret = item.int("caret") ?: -1,
                detail = item.string("detail"),
                docPath = item.string("docPath"),
                doc = item.string("doc"),
                reopen = (item["reopen"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        return PyCompletions(PySpan(start, end), items)
    }

    /** [source] tidied: indentation, spacing and blank lines. Unchanged when the library is missing. */
    fun format(source: String): String = if (ready) PythonNative.nativeFormat(source) else source

    fun definition(source: String, caret: Int): PySpan? {
        if (!ready) return null
        val root = parse(PythonNative.nativeDefinition(source, caret)) as? JsonObject ?: return null
        return PySpan(root.int("start") ?: return null, root.int("end") ?: return null)
    }

    fun references(source: String, caret: Int): List<PySpan> {
        if (!ready) return emptyList()
        return (parse(PythonNative.nativeReferences(source, caret)) as? JsonArray).orEmpty().mapNotNull { pair(it) }
    }

    /** Every place the name at [caret] is written, and whether [newName] can replace it; null when nothing there renames. */
    fun rename(source: String, caret: Int, newName: String): PyRename? {
        if (!ready) return null
        val root = parse(PythonNative.nativeRename(source, caret, newName)) as? JsonObject ?: return null
        val problem = root.string("problem")?.let { wire -> PyRenameProblem.entries.firstOrNull { it.name.lowercase() == wire } }
        return PyRename(root.array("spans").mapNotNull { pair(it) }, problem)
    }

    fun apiAt(source: String, caret: Int): PyApiEntry? {
        if (!ready) return null
        return (parse(PythonNative.nativeApiAt(source, caret)) as? JsonObject)?.let { apiEntry(it) }
    }

    /**
     * The call around [caret] and the argument being typed, or null outside a
     * call. Null too on a library older than signature help.
     */
    fun signature(source: String, caret: Int, host: PyHostShape?): PySignature? {
        if (!ready) return null
        val text = newer { PythonNative.nativeSignature(source, caret, hostJson(host, null)) } ?: return null
        val root = parse(text) as? JsonObject ?: return null
        return PySignature(
            label = root.string("label") ?: return null,
            params = root.array("params").mapNotNull { pair(it) },
            names = root.array("names").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            active = root.int("active") ?: -1,
            doc = root.string("doc").orEmpty(),
            paramDoc = root.string("paramDoc"),
            values = root.array("values").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** What the name at [offset] is, or null. */
    fun hover(source: String, offset: Int, host: PyHostShape?): PyHover? {
        if (!ready) return null
        val text = newer { PythonNative.nativeHover(source, offset, hostJson(host, null)) } ?: return null
        val root = parse(text) as? JsonObject ?: return null
        return PyHover(
            span = PySpan(root.int("start") ?: return null, root.int("end") ?: return null),
            title = root.string("title") ?: return null,
            doc = root.string("doc").orEmpty(),
            kind = root.string("kind").orEmpty(),
        )
    }

    /** The provider lists a plugin can answer, read once. */
    val providerTools: List<PyProviderTool>
        get() = cachedTools ?: run {
            if (!ready) return emptyList()
            val text = newer { PythonNative.nativeProviderTools() } ?: return emptyList()
            (parse(text) as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                PyProviderTool(
                    tool = item.string("tool") ?: return@mapNotNull null,
                    label = item.string("label").orEmpty(),
                    request = item.array("request").mapNotNull { pair ->
                        val array = pair as? JsonArray ?: return@mapNotNull null
                        val key = (array.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        key to ((array.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty())
                    },
                    sample = item.string("sample") ?: "{}",
                    reply = item.string("reply").orEmpty(),
                )
            }.also { cachedTools = it }
        }

    @Volatile
    private var cachedTools: List<PyProviderTool>? = null

    /**
     * A call into a part of the library added after the first release: the
     * library is downloaded on its own, so an older copy may lack it.
     */
    private inline fun newer(call: () -> String): String? = try {
        call()
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    /** The whole API, read once. Empty while the library is missing, and then asked again next time. */
    val api: PyApiModel
        get() = cachedApi ?: run {
            if (!ready) return EMPTY_API
            val root = parse(PythonNative.nativeApiEntries()) as? JsonObject ?: return EMPTY_API
            PyApiModel(
                entries = root.array("entries").mapNotNull { (it as? JsonObject)?.let { entry -> apiEntry(entry) } },
                eventTypes = root.array("eventTypes").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                hooks = root.array("hooks").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ).also { cachedApi = it }
        }

    @Volatile
    private var cachedApi: PyApiModel? = null

    private val EMPTY_API = PyApiModel(emptyList(), emptyList(), emptyList())

    private fun hostJson(host: PyHostShape?, verdict: String?): String = buildJsonObject {
        if (host != null) {
            host.manifest?.let { manifest -> parse(PluginManifestCodec.encode(manifest))?.let { put("manifest", it) } }
            put("storage", host.storage)
            put("reminders", host.reminders)
            put("selection", host.selection)
            put("panel", host.panel)
            put("actions", buildJsonArray { host.actions.forEach { add(JsonPrimitive(it)) } })
            if (host.modules.isNotEmpty()) put("modules", buildJsonArray { host.modules.forEach { add(JsonPrimitive(it)) } })
        }
        verdict?.let { parse(it) }?.let { put("verdict", it) }
    }.toString()

    private fun diagnostic(element: JsonElement): PyDiagnostic? {
        val item = element as? JsonObject ?: return null
        return PyDiagnostic(
            code = item.string("code") ?: return null,
            severity = when (item.string("severity")) {
                "error" -> PySeverity.ERROR
                "warning" -> PySeverity.WARNING
                else -> PySeverity.INFO
            },
            span = PySpan(item.int("start") ?: return null, item.int("end") ?: return null),
            args = item.array("args").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            fix = (item["fix"] as? JsonObject)?.let { fix(it) },
        )
    }

    private fun fix(item: JsonObject): PyFix? {
        val value = item.string("value")
        return when (item.string("kind")) {
            "replace" -> PyFix.Replace(item.string("text") ?: return null)
            "insert" -> PyFix.Insert(item.int("at") ?: return null, item.string("text") ?: return null)
            "permission" -> PyFix.Permission(value ?: return null)
            "host" -> PyFix.Host(value ?: return null)
            "provider" -> PyFix.Provider(item.string("tool") ?: return null, item.string("id") ?: return null)
            "action" -> PyFix.Action(value ?: return null)
            "keyword" -> PyFix.Keyword(value ?: return null)
            "setting" -> PyFix.Setting(value ?: return null)
            else -> null
        }
    }

    private fun outline(element: JsonElement): PyOutlineItem? {
        val item = element as? JsonObject ?: return null
        return PyOutlineItem(
            name = item.string("name") ?: return null,
            kind = item.string("kind") ?: "function",
            depth = item.int("depth") ?: 0,
            span = PySpan(item.int("start") ?: return null, item.int("end") ?: return null),
            nameSpan = PySpan(item.int("nameStart") ?: return null, item.int("nameEnd") ?: return null),
        )
    }

    private fun apiEntry(item: JsonObject): PyApiEntry? = PyApiEntry(
        path = item.string("path") ?: return null,
        parent = item.string("parent").orEmpty(),
        name = item.string("name") ?: item.string("path").orEmpty().substringAfterLast('.'),
        kind = item.string("kind").orEmpty(),
        signature = item.string("signature"),
        doc = item.string("doc").orEmpty(),
        permission = item.string("permission"),
    )

    private fun pair(element: JsonElement): PySpan? {
        val array = element as? JsonArray ?: return null
        val start = (array.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return null
        val end = (array.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: return null
        return PySpan(start, end)
    }

    private fun parse(text: String?): JsonElement? =
        text?.takeIf { it.isNotEmpty() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
