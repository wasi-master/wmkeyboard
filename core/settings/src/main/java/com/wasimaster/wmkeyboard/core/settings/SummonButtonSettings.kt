package com.wasimaster.wmkeyboard.core.settings

/**
 * The floating button (#755): one small round button drawn over every app,
 * whose tap, double tap and press and hold each run a [FloatingAction], and
 * whose quick menu fans more of them out around it.
 *
 * It began as two buttons, the keyboard button (#374) and the AI button
 * (#697), and someone who wanted both had two bubbles on screen. Now there is
 * one, and either of those is just a choice of what its tap does.
 *
 * Grouped for the reason every family here is (see [CameraSettings]): one
 * top-level slot for the whole feature. The DataStore keys stay flat
 * (`summon_button`, `summon_button_x`, `summon_button_y`, `floating_button_*`).
 * A user who had only the AI button on comes back with the button on and its
 * tap asking the AI, at the AI button's old place, until they change the tap.
 */
data class SummonButtonSettings(
    /**
     * Draw the button. Needs Android's "Display over other apps" grant as
     * well; with the switch on and no grant, nothing is drawn and the settings
     * page says what is missing.
     */
    val enabled: Boolean = false,
    /**
     * Where the button rests, as fractions of the room the screen leaves it
     * (0 = left edge, 1 = right edge). Only ever written by dragging it, and a
     * drag always ends against one side, so in practice this is 0 or 1.
     */
    val xFraction: Float = 1f,
    /** The same for the vertical axis (0 = top, 1 = bottom). */
    val yFraction: Float = 0.6f,
    /** What a tap does. The keyboard by default, which is what the button was first for. */
    val tap: FloatingAction = FloatingAction.Keyboard,
    /**
     * What a double tap does. Off by default: while it is bound, a single tap
     * waits out the double-tap window before it runs, and that wait is felt.
     */
    val doubleTap: FloatingAction = FloatingAction.None,
    /** What a press and hold does. The quick menu, which a slide can pick from without letting go. */
    val hold: FloatingAction = FloatingAction.Menu,
    /**
     * What a tap does while the keyboard is up, or null for the same as [tap]
     * (#626). Lets one tap open the keyboard and the next leave the field,
     * without spending the double tap or the hold on it.
     */
    val tapTyping: FloatingAction? = null,
    /**
     * What a double tap does while the keyboard is up, or null for the same as
     * [doubleTap]. Bound only here, a single tap with the keyboard down runs
     * at once instead of waiting out the double-tap window.
     */
    val doubleTapTyping: FloatingAction? = null,
    /** The quick menu's slots, in the order they fan out; at most [MAX_FLOATING_MENU_ITEMS]. */
    val menu: List<FloatingAction> = DefaultFloatingMenu,
    /**
     * The categories the quick menu's [FloatingAction.Category] slots open
     * (#923), each a ring of its own. Only ever the ones some slot opens.
     */
    val categories: List<FloatingCategory> = emptyList(),
    /** Under each quick menu slot, its name. */
    val menuLabels: Boolean = true,
    /**
     * How far a ring's slots stand off the button's edge, in dp (#923); see
     * [FloatingMenuGapRange]. The quick menu and every category share it.
     */
    val menuGapDp: Int = 22,
    /** How a ring's slots come out of the button (#923). */
    val menuAnimation: FloatingMenuAnimation = FloatingMenuAnimation.SPRING,
    /**
     * How fast the button's motion runs (#1138), as a multiple of its own
     * pace: the rings opening and closing, the button appearing and settling
     * against its edge. 2 is twice as fast. See [FloatingAnimationSpeedRange].
     */
    val animationSpeed: Float = 1f,
    /** The button's diameter in dp; see [FloatingButtonSizeRange]. */
    val sizeDp: Int = 52,
    val style: FloatingButtonStyle = FloatingButtonStyle.AUTO,
    /** The button's outline, and its slots' (#933). */
    val shape: FloatingButtonShape = FloatingButtonShape.CIRCLE,
    /**
     * Drawn as a thin pill flat against the side it rests on (#1137), in
     * place of the disc: a few pixels of the screen's edge rather than a
     * button over the app. Its gestures and its quick menu are the button's.
     */
    val edgePill: Boolean = false,
    /** How thick the pill is drawn, in dp; see [FloatingPillThicknessRange]. */
    val pillThicknessDp: Int = 6,
    /** How tall the pill is, in dp, which is also how tall its touch area is; see [FloatingPillLengthRange]. */
    val pillLengthDp: Int = 72,
    /**
     * How far in from the edge a touch still lands on the pill, in dp; see
     * [FloatingPillTouchRange]. Never less than the pill's own thickness.
     */
    val pillTouchDp: Int = 20,
    /** How solid the button is while it is being used, 0.3 to 1. */
    val opacity: Float = 0.92f,
    /**
     * Seconds untouched before the button fades to [idleOpacity] (and tucks
     * itself into the edge with [tuck]); 0 never fades. See [FloatingButtonIdleRange].
     */
    val idleFadeSeconds: Int = 3,
    /** How solid the button is once it has faded, 0.15 to 1. */
    val idleOpacity: Float = 0.45f,
    /** A faded button slides half off the side it rests on, out of the way of the app. */
    val tuck: Boolean = false,
    /**
     * Stay on screen while the keyboard is up, lifted clear of it. Off, the
     * button goes when the keyboard comes and returns when it leaves.
     */
    val whileTyping: Boolean = true,
    /**
     * With [whileTyping], stay where it was put even over the keys (#926),
     * rather than lifted clear of the keyboard. Only the button's own disc
     * takes a touch; a key beside it gets its own.
     */
    val overKeyboard: Boolean = false,
    /** Leave landscape alone: games and videos, mostly. */
    val hideInLandscape: Boolean = false,
    /** A tick on a tap, a firmer one when the menu opens or a slot is picked. */
    val haptics: Boolean = true,
)

/** What a tap does with the keyboard up or down. */
fun SummonButtonSettings.tapFor(keyboardUp: Boolean): FloatingAction =
    if (keyboardUp) tapTyping ?: tap else tap

/** What a double tap does with the keyboard up or down. */
fun SummonButtonSettings.doubleTapFor(keyboardUp: Boolean): FloatingAction =
    if (keyboardUp) doubleTapTyping ?: doubleTap else doubleTap

/** Every action a gesture is bound to, the keyboard-up ones included. */
fun SummonButtonSettings.gestureActions(): List<FloatingAction> =
    listOfNotNull(tap, doubleTap, hold, tapTyping, doubleTapTyping)

/** Whether any of the button's gestures, or its quick menu, asks the AI. */
fun SummonButtonSettings.usesAskAi(): Boolean =
    FloatingAction.AskAi in gestureActions() || FloatingAction.AskAi in opensMenuItems()

/** Whether any of the button's gestures, or its quick menu, reads the screen: Ask AI, or Look up (#765). */
fun SummonButtonSettings.readsScreen(): Boolean {
    val bound = gestureActions() + opensMenuItems()
    return FloatingAction.AskAi in bound || FloatingAction.LookUp in bound
}

/**
 * Every slot a gesture can reach: the quick menu's when some gesture opens
 * it, and the slots of each category reached from there or bound to a
 * gesture itself (#923). Else nothing.
 */
fun SummonButtonSettings.opensMenuItems(): List<FloatingAction> {
    val gestures = gestureActions()
    val top = if (FloatingAction.Menu in gestures) menu else emptyList()
    val opened = (gestures + top).filterIsInstance<FloatingAction.Category>()
        .mapNotNull { categories.categoryById(it.id) }
        .distinct()
    return top + opened.flatMap { it.items }
}

/** The category [action] opens, or null when it opens none this button still has. */
fun SummonButtonSettings.categoryOf(action: FloatingAction): FloatingCategory? =
    (action as? FloatingAction.Category)?.let { categories.categoryById(it.id) }

/** The slots of the ring [action] opens: the quick menu's, a category's, or null for an action that opens none. */
fun SummonButtonSettings.ringOf(action: FloatingAction): List<FloatingAction>? = when (action) {
    FloatingAction.Menu -> menu
    is FloatingAction.Category -> categoryOf(action)?.items
    else -> null
}

/** Whether the button sits over the keys while the keyboard is up (#926), as set. */
val SummonButtonSettings.floatsOverKeyboard: Boolean get() = whileTyping && overKeyboard

/** The pill's touch area across, in dp (#1137): as set, but never thinner than the pill. */
val SummonButtonSettings.pillTouchWidthDp: Int get() = maxOf(pillTouchDp, pillThicknessDp)

/** Tucking slides half the disc off the edge; a pill is already against it (#1137). */
val SummonButtonSettings.tucks: Boolean get() = tuck && !edgePill
