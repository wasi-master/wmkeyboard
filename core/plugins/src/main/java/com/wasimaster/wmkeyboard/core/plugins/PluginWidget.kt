package com.wasimaster.wmkeyboard.core.plugins

/** How a [PluginWidget.Label] is drawn. [HEADLINE] is API 5. */
enum class PluginLabelStyle { HEADLINE, TITLE, BODY, CAPTION }

/**
 * A colour a plugin may ask for, by what it means rather than what it is.
 * The host maps each to the keyboard theme's own colour, so a plugin can mark
 * something as an error without being able to paint itself like a system
 * dialog.
 */
enum class PluginColorRole {
    PRIMARY, SECONDARY, MUTED, DANGER, SUCCESS, WARNING, ACCENT;

    companion object {
        fun of(wire: String?): PluginColorRole? = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
    }
}

/**
 * One node of the UI a plugin asked for.
 *
 * This is the entire vocabulary. A plugin describes what it wants using these
 * shapes and the host draws them with the keyboard's own theme, which is what
 * keeps a plugin from styling itself into something that looks like part of the
 * keyboard, or like a system prompt.
 *
 * Immutable and made only of strings, booleans and ints: this crosses from the
 * plugin thread to the UI thread on every render, and nothing that crosses is
 * allowed to be a reference back into the interpreter.
 */
sealed interface PluginWidget {

    /** Top-level only. The host owns which page is showing. */
    data class Tabs(val id: String, val pages: List<Page>) : PluginWidget {
        /** An empty [title] means the panel names the tab after its position. */
        data class Page(val title: String, val children: List<PluginWidget>)
    }

    data class Column(val children: List<PluginWidget>, val gap: Int? = null) : PluginWidget

    /** [weights], when given, share the width out; otherwise every child gets an equal part. */
    data class Row(val children: List<PluginWidget>, val gap: Int? = null, val weights: List<Float>? = null) : PluginWidget

    data class Label(val text: String, val style: PluginLabelStyle, val color: PluginColorRole? = null) : PluginWidget

    /**
     * A block of result text. [insertable] adds the host's own Insert button,
     * which is the only route from a plugin to the user's text field — there is
     * no API a script can call to type for them.
     */
    data class Output(
        val id: String,
        val text: String,
        val mono: Boolean,
        val insertable: Boolean,
        val copyable: Boolean,
    ) : PluginWidget

    data class Button(
        val id: String,
        val text: String,
        val primary: Boolean,
        val enabled: Boolean,
        /** API 5: a Material Symbols name drawn before [text], or alone when [compact]. */
        val icon: String? = null,
        val color: PluginColorRole? = null,
        val compact: Boolean = false,
    ) : PluginWidget

    data class Toggle(val id: String, val label: String, val checked: Boolean) : PluginWidget

    /**
     * A text field the user types into.
     *
     * Carries no value: the buffer belongs to the host, which feeds it from the
     * keyboard's own keystroke routing and passes the current text back to the
     * script as an event. A plugin never sees a keystroke, only the contents of
     * its own box.
     */
    data class Input(
        val id: String,
        val label: String,
        val placeholder: String,
        val multiline: Boolean = false,
        /** "text", "number", "email", "url", "password" or "phone": the keys offered, and dots for a password. */
        val kind: String = "text",
        /** The script hears every edit ([PluginEvent.InputChanged]); otherwise only Enter ([PluginEvent.Submitted]). */
        val live: Boolean = true,
        val submit: Boolean = false,
    ) : PluginWidget

    /**
     * A date, and with [time] a time of day, the user picks with the host's own
     * buttons: a day back or forward, earlier or later, today, clear.
     *
     * [value] is seconds since the epoch, the unit `wm.timestamp` gives, or null for
     * no date yet. Like [Input], the widget keeps nothing: each press sends the
     * new value to the script as `date_changed`, and the next render draws
     * whatever the script kept. Without [time] the value is the start of a day.
     * [step] is how many minutes the smaller time buttons move, 1 to 60.
     */
    data class DatePicker(
        val id: String,
        val label: String,
        val value: Long?,
        val time: Boolean,
        val step: Int,
    ) : PluginWidget

    data class Spacer(val height: Int) : PluginWidget

    data object Divider : PluginWidget

    /** A bar that only says "working". */
    data object Progress : PluginWidget

    // ---- API 5 ----------------------------------------------------------

    /** A bar filled to [value], 0 to 1. */
    data class ProgressValue(val value: Float) : PluginWidget

    data class Grid(val columns: Int, val children: List<PluginWidget>) : PluginWidget

    data class Card(val id: String, val title: String, val clickable: Boolean, val children: List<PluginWidget>) : PluginWidget

    data class Section(
        val id: String,
        val title: String,
        val collapsible: Boolean,
        val open: Boolean,
        val children: List<PluginWidget>,
    ) : PluginWidget

    /** A part that scrolls on its own, [height] dp tall, or the panel's height when null. */
    data class Scroll(val height: Int?, val children: List<PluginWidget>) : PluginWidget

    /** Text with **bold**, *italic*, `code`, headings, lists and links the user taps to open after a confirmation. */
    data class Markdown(val text: String) : PluginWidget

    /** What an empty screen says, with an optional button ([action] empty for none). */
    data class Empty(val id: String, val text: String, val icon: String?, val action: String) : PluginWidget

    data class Checkbox(val id: String, val label: String, val checked: Boolean) : PluginWidget

    /** A search box. Like [Input], the host owns its text. */
    data class Search(val id: String, val placeholder: String, val live: Boolean) : PluginWidget

    /** A slider, or with [stepper] a number with − and + buttons. */
    data class Slider(
        val id: String,
        val label: String,
        val value: Double,
        val min: Double,
        val max: Double,
        val step: Double,
        val stepper: Boolean,
    ) : PluginWidget

    data class Option(val value: String, val label: String)

    /** One of [options]: a dropdown, or side by side when [segmented]. */
    data class Select(
        val id: String,
        val label: String,
        val options: List<Option>,
        val value: String?,
        val segmented: Boolean,
    ) : PluginWidget

    data class Chips(val id: String, val options: List<Option>, val value: List<String>, val multi: Boolean) : PluginWidget

    /** A grid of keys drawn like the keyboard's own. */
    data class Keys(val id: String, val rows: List<List<String>>) : PluginWidget

    data class Item(
        val id: String,
        val title: String,
        val subtitle: String,
        val icon: String?,
        val trailing: String,
        val clickable: Boolean,
        /** Labels offered when the row is swiped or held; each sends [PluginEvent.ItemAction]. */
        val actions: List<String>,
        val selected: Boolean,
    ) : PluginWidget

    data class ListView(val id: String, val items: List<Item>, val empty: String, val selectable: Boolean) : PluginWidget

    /**
     * A picture: `assets/<name>` from the plugin, an `https://` address the
     * plugin's hosts cover (fetched by the host, under the same rules as
     * `wm.http`), or `data:<base64>`.
     */
    data class Image(
        val id: String,
        val src: String,
        val alt: String,
        val height: Int?,
        val insertable: Boolean,
        val clickable: Boolean,
    ) : PluginWidget

    data class Icon(val name: String, val size: Int?, val color: PluginColorRole?) : PluginWidget

    data class Qr(val text: String, val size: Int?) : PluginWidget

    data class Stat(val id: String, val label: String, val value: String, val caption: String) : PluginWidget

    /** A ring filled to [value], 0 to 1, with a label in the middle. */
    data class Ring(val id: String, val value: Float, val label: String, val caption: String) : PluginWidget

    data class Series(val name: String, val values: List<Float>)

    data class Chart(
        val id: String,
        /** "line", "bar" or "spark". */
        val kind: String,
        val series: List<Series>,
        val labels: List<String>,
        val height: Int?,
    ) : PluginWidget

    /** A drawing [width] by [height] units, scaled to the panel's width. */
    data class Canvas(
        val id: String,
        val width: Float,
        val height: Float,
        val shapes: List<Shape>,
        val tappable: Boolean,
        val draggable: Boolean,
    ) : PluginWidget {
        sealed interface Shape {
            val color: PluginColorRole

            data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val fill: Boolean, override val color: PluginColorRole) : Shape

            data class Circle(val x: Float, val y: Float, val r: Float, val fill: Boolean, override val color: PluginColorRole) : Shape

            data class Line(val x: Float, val y: Float, val x2: Float, val y2: Float, val width: Float, override val color: PluginColorRole) : Shape

            data class Text(val x: Float, val y: Float, val text: String, val size: Float, override val color: PluginColorRole) : Shape
        }
    }
}

/** The widgets [widget] holds directly, for walks that look inside containers. */
fun PluginWidget.childWidgets(): List<PluginWidget> = when (this) {
    is PluginWidget.Column -> children
    is PluginWidget.Row -> children
    is PluginWidget.Grid -> children
    is PluginWidget.Card -> children
    is PluginWidget.Section -> children
    is PluginWidget.Scroll -> children
    is PluginWidget.Tabs -> pages.flatMap { it.children }
    is PluginWidget.ListView -> items
    else -> emptyList()
}

/**
 * A whole screen as the plugin asked for it, plus anything that had to be
 * dropped on the way.
 *
 * [repairs] is shown to the user as a caption rather than swallowed — a widget
 * that silently vanishes is a plugin bug the author will never hear about.
 * [repairCodes] runs parallel to it, one code per message, for the plugin editor.
 */
data class RenderedUi(
    val root: List<PluginWidget>,
    val repairs: List<PluginText> = emptyList(),
    val repairCodes: List<PluginRepair> = emptyList(),
) {
    companion object {
        val EMPTY = RenderedUi(emptyList())
    }
}

/**
 * What kind of repair [PluginUiCodec] made, as a value rather than words.
 *
 * The plugin editor turns these into advice, and turns [UNKNOWN_TYPE] into a
 * search of the source for the type it names. None carries a line number, and
 * none can: the tree is built from values, and a value does not know where in
 * the file it was made.
 */
enum class PluginRepair {
    NOT_A_WIDGET,
    TOO_DEEP,
    TOO_MANY_WIDGETS,
    NO_TYPE,
    UNKNOWN_TYPE,
    TABS_NO_PAGES,
    TOO_MANY_TABS,
    TEXT_BUDGET,
    TEXT_SHORTENED,
    BAD_IMAGE,
    TOO_MANY_OPTIONS,
    TOO_MANY_SERIES,
    TOO_MANY_POINTS,
    TOO_MANY_SHAPES,
    BAD_SHAPE,
}

/** Everything that can happen to a plugin's UI. All of it user-initiated. */
sealed interface PluginEvent {
    val id: String

    data class Click(override val id: String) : PluginEvent

    data class ToggleChanged(override val id: String, val value: Boolean) : PluginEvent

    data class InputChanged(override val id: String, val value: String) : PluginEvent

    data class TabSelected(override val id: String, val index: Int) : PluginEvent

    /** A date widget moved to [value], seconds since the epoch, or was cleared (null). */
    data class DateChanged(override val id: String, val value: Long?) : PluginEvent

    // ---- API 5 ----------------------------------------------------------

    /** Enter in a text or search box. */
    data class Submitted(override val id: String, val value: String) : PluginEvent

    /** A select, a search box or a checkbox changed. [value] is the option's value, the text, or "true"/"false". */
    data class ValueChanged(override val id: String, val value: String) : PluginEvent

    data class NumberChanged(override val id: String, val value: Double) : PluginEvent

    data class ChipsChanged(override val id: String, val values: List<String>, val multi: Boolean) : PluginEvent

    /** A row of a selectable list. */
    data class Selected(override val id: String, val index: Int) : PluginEvent

    /** One of a list row's swipe or hold actions. */
    data class ItemAction(override val id: String, val label: String) : PluginEvent

    data class Tapped(override val id: String, val x: Float, val y: Float) : PluginEvent

    /** [phase] is "start", "move" or "end". */
    data class Dragged(override val id: String, val x: Float, val y: Float, val dx: Float, val dy: Float, val phase: String) : PluginEvent

    data class KeyPressed(override val id: String, val key: String) : PluginEvent

    /**
     * An event the host makes rather than the user: a timer, a host call's
     * answer, the back button, a header command, a setting, a keyword. [json]
     * is the whole event object as `wm._host` reads it.
     */
    data class Host(val type: String, val json: String, override val id: String = "") : PluginEvent
}

/** The ids of every input widget in this tree, so the host knows which buffers to keep. */
fun RenderedUi.inputIds(): Set<String> {
    val ids = LinkedHashSet<String>()
    fun walk(widgets: List<PluginWidget>) {
        for (widget in widgets) {
            // Exhaustive on purpose: a widget type added later that can hold
            // children must not be able to hide an input from the host, so the
            // compiler is made to ask about it here.
            when (widget) {
                is PluginWidget.Input -> ids.add(widget.id)
                is PluginWidget.Search -> ids.add(widget.id)
                is PluginWidget.Column,
                is PluginWidget.Row,
                is PluginWidget.Tabs,
                is PluginWidget.Grid,
                is PluginWidget.Card,
                is PluginWidget.Section,
                is PluginWidget.Scroll,
                is PluginWidget.ListView,
                -> walk(widget.childWidgets())
                is PluginWidget.Label,
                is PluginWidget.Output,
                is PluginWidget.Button,
                is PluginWidget.Toggle,
                is PluginWidget.DatePicker,
                is PluginWidget.Spacer,
                PluginWidget.Divider,
                PluginWidget.Progress,
                is PluginWidget.ProgressValue,
                is PluginWidget.Markdown,
                is PluginWidget.Empty,
                is PluginWidget.Checkbox,
                is PluginWidget.Slider,
                is PluginWidget.Select,
                is PluginWidget.Chips,
                is PluginWidget.Keys,
                is PluginWidget.Item,
                is PluginWidget.Image,
                is PluginWidget.Icon,
                is PluginWidget.Qr,
                is PluginWidget.Stat,
                is PluginWidget.Ring,
                is PluginWidget.Chart,
                is PluginWidget.Canvas,
                -> Unit
            }
        }
    }
    walk(root)
    return ids
}

/** The widgets in a tree that a user can act on, by kind. */
data class PluginTargets(
    val buttons: List<PluginWidget.Button>,
    val toggles: List<PluginWidget.Toggle>,
    val inputs: List<PluginWidget.Input>,
    val tabs: List<PluginWidget.Tabs>,
    val dates: List<PluginWidget.DatePicker> = emptyList(),
) {
    val isEmpty: Boolean
        get() = buttons.isEmpty() && toggles.isEmpty() && inputs.isEmpty() && tabs.isEmpty() && dates.isEmpty()

    companion object {
        val EMPTY = PluginTargets(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Every button, toggle, input, tab strip and date in this tree, in document order. What
 * the plugin editor's event injector offers to fire.
 *
 * Disabled buttons are included. The keyboard can never fire one, and that is the
 * reason an author wants to: a handler should cope with an event it did not expect.
 *
 * Not built on [inputIds], and [inputIds] not built on this. That one runs on the
 * keyboard's path after every render and this one only when the editor asks, so
 * each stays exhaustive over [PluginWidget] on its own, and a widget type added
 * later has to be answered for in both.
 */
fun RenderedUi.targets(): PluginTargets {
    val buttons = ArrayList<PluginWidget.Button>()
    val toggles = ArrayList<PluginWidget.Toggle>()
    val inputs = ArrayList<PluginWidget.Input>()
    val tabs = ArrayList<PluginWidget.Tabs>()
    val dates = ArrayList<PluginWidget.DatePicker>()
    fun walk(widgets: List<PluginWidget>) {
        for (widget in widgets) {
            when (widget) {
                is PluginWidget.Button -> buttons.add(widget)
                is PluginWidget.DatePicker -> dates.add(widget)
                is PluginWidget.Toggle -> toggles.add(widget)
                is PluginWidget.Input -> inputs.add(widget)
                is PluginWidget.Tabs -> {
                    tabs.add(widget)
                    widget.pages.forEach { walk(it.children) }
                }

                is PluginWidget.Search -> inputs.add(PluginWidget.Input(widget.id, "", widget.placeholder))
                is PluginWidget.Column,
                is PluginWidget.Row,
                is PluginWidget.Grid,
                is PluginWidget.Card,
                is PluginWidget.Section,
                is PluginWidget.Scroll,
                is PluginWidget.ListView,
                -> walk(widget.childWidgets())
                is PluginWidget.Label,
                is PluginWidget.Output,
                is PluginWidget.Spacer,
                PluginWidget.Divider,
                PluginWidget.Progress,
                is PluginWidget.ProgressValue,
                is PluginWidget.Markdown,
                is PluginWidget.Empty,
                is PluginWidget.Checkbox,
                is PluginWidget.Slider,
                is PluginWidget.Select,
                is PluginWidget.Chips,
                is PluginWidget.Keys,
                is PluginWidget.Item,
                is PluginWidget.Image,
                is PluginWidget.Icon,
                is PluginWidget.Qr,
                is PluginWidget.Stat,
                is PluginWidget.Ring,
                is PluginWidget.Chart,
                is PluginWidget.Canvas,
                -> Unit
            }
        }
    }
    walk(root)
    return PluginTargets(buttons, toggles, inputs, tabs, dates)
}
