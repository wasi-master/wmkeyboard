package com.wasimaster.wmkeyboard.core.plugins

/** How a [PluginWidget.Label] is drawn. */
enum class PluginLabelStyle { TITLE, BODY, CAPTION }

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

    data class Column(val children: List<PluginWidget>) : PluginWidget

    data class Row(val children: List<PluginWidget>) : PluginWidget

    data class Label(val text: String, val style: PluginLabelStyle) : PluginWidget

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
    data class Input(val id: String, val label: String, val placeholder: String) : PluginWidget

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

    data object Progress : PluginWidget
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
                is PluginWidget.Column -> walk(widget.children)
                is PluginWidget.Row -> walk(widget.children)
                is PluginWidget.Tabs -> widget.pages.forEach { walk(it.children) }
                is PluginWidget.Label,
                is PluginWidget.Output,
                is PluginWidget.Button,
                is PluginWidget.Toggle,
                is PluginWidget.DatePicker,
                is PluginWidget.Spacer,
                PluginWidget.Divider,
                PluginWidget.Progress,
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

                is PluginWidget.Column -> walk(widget.children)
                is PluginWidget.Row -> walk(widget.children)
                is PluginWidget.Label,
                is PluginWidget.Output,
                is PluginWidget.Spacer,
                PluginWidget.Divider,
                PluginWidget.Progress,
                -> Unit
            }
        }
    }
    walk(root)
    return PluginTargets(buttons, toggles, inputs, tabs, dates)
}
