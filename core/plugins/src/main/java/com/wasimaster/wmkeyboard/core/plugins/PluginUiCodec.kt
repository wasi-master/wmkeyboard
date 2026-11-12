package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads the widget tree `libwm_python_jni` hands back after a render into a
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

    /**
     * Nodes in one tree. A panel that needs more than this is not a keyboard
     * panel. The library allows 256 for API 4 and 1,024 for API 5; this is the
     * larger, re-applied.
     */
    const val MAX_NODES = 1024

    /** Nesting depth. */
    const val MAX_DEPTH = 12

    /** Characters in any one piece of text. */
    const val MAX_TEXT = 2048

    /** Characters across the whole tree (API 5's budget; API 4 trees arrive under 64K). */
    const val MAX_TOTAL_TEXT = 128 * 1024

    const val MAX_SHAPES = 2000
    const val MAX_SERIES = 8
    const val MAX_POINTS = 500
    const val MAX_OPTIONS = 64

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
        PluginRepair.BAD_IMAGE -> PluginText.of(R.string.core_plugins_ui_repair_bad_image)
        PluginRepair.TOO_MANY_OPTIONS -> PluginText.of(R.string.core_plugins_ui_repair_too_many_options, MAX_OPTIONS)
        PluginRepair.TOO_MANY_SERIES -> PluginText.of(R.string.core_plugins_ui_repair_too_many_series, MAX_SERIES)
        PluginRepair.TOO_MANY_POINTS -> PluginText.of(R.string.core_plugins_ui_repair_too_many_points, MAX_POINTS)
        PluginRepair.TOO_MANY_SHAPES -> PluginText.of(R.string.core_plugins_ui_repair_too_many_shapes, MAX_SHAPES)
        PluginRepair.BAD_SHAPE -> PluginText.of(R.string.core_plugins_ui_repair_bad_shape)
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
                "column" -> PluginWidget.Column(children(node["children"], depth + 1), node.int("gap")?.coerceIn(0, MAX_GAP))
                "row" -> PluginWidget.Row(
                    children(node["children"], depth + 1),
                    node.int("gap")?.coerceIn(0, MAX_GAP),
                    (node["weights"] as? JsonArray)?.map { ((it as? JsonPrimitive)?.floatOrNull ?: 1f).coerceIn(MIN_WEIGHT, MAX_WEIGHT) },
                )
                "label" -> PluginWidget.Label(text(node, "text"), style(node.string("style")), PluginColorRole.of(node.string("color")))
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
                    icon = icon(node, "icon"),
                    color = PluginColorRole.of(node.string("color")),
                    compact = node.bool("compact") ?: false,
                )

                "toggle" -> PluginWidget.Toggle(id(node), text(node, "label"), node.bool("checked") ?: false)
                "input" -> PluginWidget.Input(
                    id = id(node),
                    label = text(node, "label"),
                    placeholder = text(node, "placeholder"),
                    multiline = node.bool("multiline") ?: false,
                    kind = node.string("kind")?.takeIf { it in INPUT_KINDS } ?: "text",
                    live = node.bool("live") ?: true,
                    submit = node.bool("submit") ?: false,
                )
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
                "progress" -> node.float("value")?.let { PluginWidget.ProgressValue(it.coerceIn(0f, 1f)) } ?: PluginWidget.Progress
                "tabs" -> tabs(node, depth)
                "grid" -> PluginWidget.Grid((node.int("columns") ?: 3).coerceIn(1, MAX_COLUMNS), children(node["children"], depth + 1))
                "card" -> PluginWidget.Card(id(node), text(node, "title"), node.bool("clickable") ?: false, children(node["children"], depth + 1))
                "section" -> PluginWidget.Section(
                    id(node),
                    text(node, "title"),
                    node.bool("collapsible") ?: false,
                    node.bool("open") ?: true,
                    children(node["children"], depth + 1),
                )
                "scroll" -> PluginWidget.Scroll(node.int("height")?.coerceIn(MIN_HEIGHT, MAX_HEIGHT), children(node["children"], depth + 1))
                "markdown" -> PluginWidget.Markdown(text(node, "text"))
                "empty" -> PluginWidget.Empty(id(node), text(node, "text"), icon(node, "icon"), text(node, "action"))
                "checkbox" -> PluginWidget.Checkbox(id(node), text(node, "label"), node.bool("checked") ?: false)
                "search" -> PluginWidget.Search(id(node), text(node, "placeholder"), node.bool("live") ?: false)
                "slider", "stepper" -> slider(node, type == "stepper")
                "select" -> PluginWidget.Select(
                    id(node),
                    text(node, "label"),
                    options(node),
                    node.string("value")?.take(MAX_ID),
                    node.string("style") == "segmented",
                )
                "chips" -> PluginWidget.Chips(
                    id(node),
                    options(node),
                    strings(node["value"], MAX_OPTIONS, MAX_ID),
                    node.bool("multi") ?: false,
                )
                "keys" -> PluginWidget.Keys(
                    id(node),
                    (node["rows"] as? JsonArray).orEmpty().take(MAX_KEY_ROWS).map { strings(it, MAX_KEYS_PER_ROW, MAX_KEY_LABEL) },
                )
                "list" -> PluginWidget.ListView(
                    id(node),
                    (node["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { item -> widget(item, depth + 1) as? PluginWidget.Item } },
                    text(node, "empty"),
                    node.bool("selectable") ?: false,
                )
                "item" -> PluginWidget.Item(
                    id = id(node),
                    title = text(node, "title"),
                    subtitle = text(node, "subtitle"),
                    icon = icon(node, "icon"),
                    trailing = text(node, "trailing"),
                    clickable = node.bool("clickable") ?: false,
                    actions = strings(node["actions"], MAX_ITEM_ACTIONS, MAX_ACTION_LABEL),
                    selected = node.bool("selected") ?: false,
                )
                "image" -> image(node)
                "icon" -> icon(node, "name")?.let { PluginWidget.Icon(it, node.int("size")?.coerceIn(8, 96), PluginColorRole.of(node.string("color"))) }
                "qr" -> PluginWidget.Qr(text(node, "text"), node.int("size")?.coerceIn(48, 600))
                "stat" -> PluginWidget.Stat(id(node), text(node, "label"), text(node, "value"), text(node, "caption"))
                "ring" -> PluginWidget.Ring(id(node), (node.float("value") ?: 0f).coerceIn(0f, 1f), text(node, "label"), text(node, "caption"))
                "chart" -> chart(node)
                "canvas" -> canvas(node)
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

        private fun slider(node: JsonObject, stepper: Boolean): PluginWidget.Slider {
            val min = node.double("min") ?: 0.0
            val max = (node.double("max") ?: 100.0).coerceAtLeast(min)
            return PluginWidget.Slider(
                id = id(node),
                label = text(node, "label"),
                value = (node.double("value") ?: min).coerceIn(min, max),
                min = min,
                max = max,
                step = (node.double("step") ?: 1.0).let { if (it.isFinite() && it > 0) it else 1.0 },
                stepper = stepper,
            )
        }

        private fun options(node: JsonObject): List<PluginWidget.Option> {
            val all = (node["options"] as? JsonArray).orEmpty()
            if (all.size > MAX_OPTIONS) repair(PluginRepair.TOO_MANY_OPTIONS)
            return all.take(MAX_OPTIONS).mapNotNull { o ->
                val obj = o as? JsonObject ?: return@mapNotNull null
                PluginWidget.Option(obj.string("value").orEmpty().take(MAX_ID), text(obj, "label"))
            }
        }

        private fun strings(value: JsonElement?, max: Int, length: Int): List<String> =
            (value as? JsonArray).orEmpty().take(max).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(length) }

        private fun image(node: JsonObject): PluginWidget? {
            val src = node.string("src").orEmpty()
            val ok = when {
                src.startsWith("assets/") -> PluginFile.isAssetName(src.removePrefix("assets/"))
                src.startsWith("https://") -> src.length <= MAX_URL && src.none { it.isWhitespace() }
                src.startsWith("data:") -> src.length <= MAX_IMAGE_DATA
                else -> false
            }
            if (!ok) {
                repair(PluginRepair.BAD_IMAGE)
                return null
            }
            return PluginWidget.Image(
                id = id(node),
                src = src,
                alt = text(node, "alt"),
                height = node.int("height")?.coerceIn(16, MAX_HEIGHT),
                insertable = node.bool("insertable") ?: false,
                clickable = node.bool("clickable") ?: false,
            )
        }

        private fun chart(node: JsonObject): PluginWidget {
            val all = (node["series"] as? JsonArray).orEmpty()
            if (all.size > MAX_SERIES) repair(PluginRepair.TOO_MANY_SERIES)
            val series = all.take(MAX_SERIES).mapNotNull { s ->
                val obj = s as? JsonObject ?: return@mapNotNull null
                val values = (obj["values"] as? JsonArray).orEmpty()
                if (values.size > MAX_POINTS) repair(PluginRepair.TOO_MANY_POINTS)
                PluginWidget.Series(
                    text(obj, "name"),
                    values.take(MAX_POINTS).map { (it as? JsonPrimitive)?.floatOrNull?.takeIf { f -> f.isFinite() } ?: 0f },
                )
            }
            val kind = node.string("kind")?.takeIf { it == "bar" || it == "spark" } ?: "line"
            return PluginWidget.Chart(id(node), kind, series, strings(node["labels"], MAX_POINTS, MAX_CHART_LABEL), node.int("height")?.coerceIn(40, 600))
        }

        private fun canvas(node: JsonObject): PluginWidget {
            val all = (node["shapes"] as? JsonArray).orEmpty()
            if (all.size > MAX_SHAPES) repair(PluginRepair.TOO_MANY_SHAPES)
            val shapes = all.take(MAX_SHAPES).mapNotNull { element ->
                val s = element as? JsonObject ?: return@mapNotNull null
                fun n(key: String, default: Float = 0f) = s.float(key)?.takeIf { it.isFinite() } ?: default
                val color = PluginColorRole.of(s.string("color")) ?: PluginColorRole.PRIMARY
                val fill = s.bool("fill") ?: true
                when (s.string("shape")) {
                    "rect" -> PluginWidget.Canvas.Shape.Rect(n("x"), n("y"), n("w"), n("h"), n("radius"), fill, color)
                    "circle" -> PluginWidget.Canvas.Shape.Circle(n("x"), n("y"), n("r"), fill, color)
                    "line" -> PluginWidget.Canvas.Shape.Line(n("x"), n("y"), n("x2"), n("y2"), n("width", 1f), color)
                    "text" -> PluginWidget.Canvas.Shape.Text(n("x"), n("y"), text(s, "text"), n("size", 12f).coerceIn(4f, 96f), color)
                    else -> {
                        repair(PluginRepair.BAD_SHAPE)
                        null
                    }
                }
            }
            return PluginWidget.Canvas(
                id = id(node),
                width = (node.float("width") ?: 100f).coerceIn(1f, 10_000f),
                height = (node.float("height") ?: 100f).coerceIn(1f, 10_000f),
                shapes = shapes,
                tappable = node.bool("tappable") ?: false,
                draggable = node.bool("draggable") ?: false,
            )
        }

        /** A Material Symbols name, or null. */
        private fun icon(node: JsonObject, key: String): String? =
            node.string(key)?.takeIf { it.isNotEmpty() && it.length <= MAX_ICON && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' } }

        private fun style(value: String?): PluginLabelStyle = when (value) {
            "headline" -> PluginLabelStyle.HEADLINE
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
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    }

    private val INPUT_KINDS = setOf("text", "number", "email", "url", "password", "phone")
    private const val MAX_GAP = 64
    private const val MIN_WEIGHT = 0.05f
    private const val MAX_WEIGHT = 20f
    private const val MAX_COLUMNS = 6
    private const val MIN_HEIGHT = 40
    private const val MAX_HEIGHT = 2000
    private const val MAX_KEY_ROWS = 8
    private const val MAX_KEYS_PER_ROW = 12
    private const val MAX_KEY_LABEL = 16
    private const val MAX_ITEM_ACTIONS = 4
    private const val MAX_ACTION_LABEL = 32
    private const val MAX_URL = 2048
    private const val MAX_IMAGE_DATA = 700_000
    private const val MAX_ICON = 48
    private const val MAX_CHART_LABEL = 32
}
