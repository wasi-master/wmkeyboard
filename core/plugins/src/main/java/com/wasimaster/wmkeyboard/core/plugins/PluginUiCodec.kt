package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads the widget tree `libwm_python` hands back after a render into a
 * [RenderedUi].
 *
 * The tree was validated and repaired in the library, which applies the caps
 * below while it walks what the script returned (`native/wm-python/src/host`):
 * a script's `render()` may return nonsense, cycles or a hundred megabytes of
 * nested lists, and the place to cut that down is where it is built. What
 * arrives here is already bounded. It is still read defensively, and the same
 * caps are applied again, so a library from a different build can at worst
 * draw less, never more.
 *
 * Repairs travel as codes, with an argument where the message needs one, and
 * become the words the panel shows here, where the resources are.
 */
object PluginUiCodec {

    /** Nodes in one tree. A panel that needs more than this is not a keyboard panel. */
    const val MAX_NODES = 256

    /** Nesting depth. */
    const val MAX_DEPTH = 12

    /** Characters in any one piece of text. */
    const val MAX_TEXT = 2048

    /** Characters across the whole tree. */
    const val MAX_TOTAL_TEXT = 64 * 1024

    const val MAX_TABS = 8

    private const val MAX_REPAIRS = 8
    private const val MAX_ID = 64
    private const val MAX_SPACER = 64
    private const val DEFAULT_SPACER = 8
    private const val DEFAULT_STEP = 15
    private const val MAX_STEP = 60

    /** 0001-01-01 and 9999-12-31, UTC, in seconds. */
    private const val MIN_SECONDS = -62_135_596_800L
    private const val MAX_SECONDS = 253_402_300_799L

    /** `{root: [...], repairs: [{code, arg}]}` as the library writes it. */
    fun fromJson(ui: JsonObject): RenderedUi {
        val walk = Walk()
        val root = walk.children(ui["root"], depth = 0)
        val repairs = (ui["repairs"] as? JsonArray).orEmpty()
        for (entry in repairs) {
            val repair = entry as? JsonObject ?: continue
            val code = (repair["code"] as? JsonPrimitive)?.contentOrNull
                ?.let { name -> PluginRepair.entries.firstOrNull { it.name == name } } ?: continue
            walk.repair(code, (repair["arg"] as? JsonPrimitive)?.contentOrNull)
        }
        return RenderedUi(root, walk.repairs.toList(), walk.codes.toList())
    }

    /** The words the panel shows for a repair. */
    fun message(code: PluginRepair, arg: String?): PluginText = when (code) {
        PluginRepair.NOT_A_WIDGET -> PluginText.of(R.string.core_plugins_ui_repair_not_a_widget)
        PluginRepair.TOO_DEEP -> PluginText.of(R.string.core_plugins_ui_repair_too_deep)
        PluginRepair.TOO_MANY_WIDGETS -> PluginText.of(R.string.core_plugins_ui_repair_too_many_widgets, MAX_NODES)
        PluginRepair.NO_TYPE -> PluginText.of(R.string.core_plugins_ui_repair_no_type)
        PluginRepair.UNKNOWN_TYPE -> PluginText.of(R.string.core_plugins_ui_repair_unknown_type, arg.orEmpty().take(24))
        PluginRepair.TABS_NO_PAGES -> PluginText.of(R.string.core_plugins_ui_repair_tabs_no_pages)
        PluginRepair.TOO_MANY_TABS -> PluginText.of(R.string.core_plugins_ui_repair_too_many_tabs, MAX_TABS)
        PluginRepair.TEXT_BUDGET -> PluginText.of(R.string.core_plugins_ui_repair_text_budget)
        PluginRepair.TEXT_SHORTENED -> PluginText.of(R.string.core_plugins_ui_repair_text_shortened)
    }

    private class Walk {
        val repairs = ArrayList<PluginText>()

        /** Parallel to [repairs]: the kind of each, for the plugin editor. */
        val codes = ArrayList<PluginRepair>()
        var nodes = 0
        var totalText = 0

        fun repair(code: PluginRepair, arg: String? = null) {
            val message = message(code, arg)
            if (repairs.size < MAX_REPAIRS && message !in repairs) {
                repairs.add(message)
                codes.add(code)
            }
        }

        fun children(value: JsonElement?, depth: Int): List<PluginWidget> {
            val array = value as? JsonArray ?: return emptyList()
            return array.mapNotNull { (it as? JsonObject)?.let { node -> widget(node, depth) } }
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun widget(node: JsonObject, depth: Int): PluginWidget? {
            if (depth > MAX_DEPTH) {
                repair(PluginRepair.TOO_DEEP)
                return null
            }
            if (++nodes > MAX_NODES) {
                repair(PluginRepair.TOO_MANY_WIDGETS)
                return null
            }
            return when (val type = node.string("type")) {
                "column" -> PluginWidget.Column(children(node["children"], depth + 1))
                "row" -> PluginWidget.Row(children(node["children"], depth + 1))
                "label" -> PluginWidget.Label(text(node, "text"), style(node.string("style")))
                "output" -> PluginWidget.Output(
                    id = id(node),
                    text = text(node, "text"),
                    mono = node.bool("mono") ?: false,
                    insertable = node.bool("insertable") ?: true,
                    copyable = node.bool("copyable") ?: true,
                )

                "button" -> PluginWidget.Button(
                    id = id(node),
                    text = text(node, "text"),
                    primary = node.bool("primary") ?: false,
                    enabled = node.bool("enabled") ?: true,
                )

                "toggle" -> PluginWidget.Toggle(id(node), text(node, "label"), node.bool("checked") ?: false)
                "input" -> PluginWidget.Input(id(node), text(node, "label"), text(node, "placeholder"))
                "date" -> PluginWidget.DatePicker(
                    id = id(node),
                    label = text(node, "label"),
                    value = (node["value"] as? JsonPrimitive)?.longOrNull?.takeIf { it in MIN_SECONDS..MAX_SECONDS },
                    time = node.bool("time") ?: false,
                    step = ((node["step"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_STEP).coerceIn(1, MAX_STEP),
                )

                "spacer" -> PluginWidget.Spacer(
                    ((node["height"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_SPACER).coerceIn(0, MAX_SPACER),
                )

                "divider" -> PluginWidget.Divider
                "progress" -> PluginWidget.Progress
                "tabs" -> tabs(node, depth)
                null, "" -> {
                    repair(PluginRepair.NO_TYPE)
                    null
                }

                else -> {
                    repair(PluginRepair.UNKNOWN_TYPE, type)
                    null
                }
            }
        }

        private fun tabs(node: JsonObject, depth: Int): PluginWidget? {
            val pages = (node["pages"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .take(MAX_TABS)
                .map { page -> PluginWidget.Tabs.Page(text(page, "title"), children(page["children"], depth + 1)) }
            if (pages.isEmpty()) {
                repair(PluginRepair.TABS_NO_PAGES)
                return null
            }
            return PluginWidget.Tabs(id(node), pages)
        }

        private fun style(value: String?): PluginLabelStyle = when (value) {
            "title" -> PluginLabelStyle.TITLE
            "caption" -> PluginLabelStyle.CAPTION
            else -> PluginLabelStyle.BODY
        }

        private fun id(node: JsonObject): String = node.string("id").orEmpty().take(MAX_ID)

        /** One string field, capped per node and against the whole tree. */
        private fun text(node: JsonObject, key: String): String {
            val value = node.string(key) ?: return ""
            val remaining = (MAX_TOTAL_TEXT - totalText).coerceAtLeast(0)
            if (remaining == 0 && value.isNotEmpty()) {
                repair(PluginRepair.TEXT_BUDGET)
                return ""
            }
            val capped = value.take(minOf(MAX_TEXT, remaining))
            if (capped.length < value.length) repair(PluginRepair.TEXT_SHORTENED)
            totalText += capped.length
            return capped
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
