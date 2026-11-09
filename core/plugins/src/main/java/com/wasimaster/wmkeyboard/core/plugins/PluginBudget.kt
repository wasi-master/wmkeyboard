package com.wasimaster.wmkeyboard.core.plugins

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.plugins.R

/**
 * Why a plugin was stopped part-way through a run.
 *
 * [messageRes] is what the panel says about it. A resource id rather than the
 * words: this module runs with no Context, so the host resolves the text where
 * it draws it.
 */
enum class PluginAbortReason(@StringRes val messageRes: Int) {
    /** Burned through its step allowance: almost always an accidental infinite loop. */
    INSTRUCTIONS(R.string.core_plugins_stopped_instructions),

    /** Ran past its wall-clock deadline. */
    DEADLINE(R.string.core_plugins_stopped_deadline),

    /** The host pulled the plug: panel closed, plugin switched, keyboard hidden. */
    CANCELLED(R.string.core_plugins_stopped_cancelled),

    /** Recursed past the interpreter's depth limit. */
    RECURSION(R.string.core_plugins_stopped_recursion),

    /** Built a list, dict or string past the interpreter's size caps. */
    MEMORY(R.string.core_plugins_stopped_memory),
    ;

    companion object {
        /** The reason for the library's `error.abort` value; null for one this build does not know. */
        fun ofWire(wire: String?): PluginAbortReason? = when (wire) {
            "steps" -> INSTRUCTIONS
            "deadline" -> DEADLINE
            "cancelled" -> CANCELLED
            "recursion" -> RECURSION
            "container", "string" -> MEMORY
            else -> null
        }
    }
}

/**
 * How much work one call into a plugin may do: [instructions] interpreter
 * steps (one per statement and expression the interpreter evaluates, and one
 * per regex backtrack) and [wallMillis] of wall-clock time. The interpreter
 * checks both on every step and stops the script itself, so a call can never
 * outrun them by more than one step.
 *
 * The step counts are three times what LuaJ was given. A Python step is one
 * expression node, a much smaller unit than a Lua VM instruction, and at about
 * 30 million steps a second (an M-series Mac, release build) the old 2 million
 * per event was 70 ms against a 2-second wall limit: the steps stopped an
 * honest 8 KB paste into Math Mode long before time was any concern. Tripled,
 * a call still reaches its step limit well inside its wall limit on a phone.
 */
data class PluginLimit(val instructions: Long, val wallMillis: Long) {
    companion object {
        /** Running the script top to bottom. The most expensive entry. */
        val LOAD = PluginLimit(instructions = 9_000_000, wallMillis = 3_000)

        /** Handling one tap, toggle or keystroke. */
        val EVENT = PluginLimit(instructions = 6_000_000, wallMillis = 2_000)

        /**
         * One tap on a selection action: a fresh interpreter, the script, and
         * the `transform` call, all on one allowance. As much as [LOAD], since a
         * load is part of every action call.
         */
        val ACTION = PluginLimit(instructions = 9_000_000, wallMillis = 3_000)

        /** Building the widget tree. Runs after every event, so it is kept tight. */
        val RENDER = PluginLimit(instructions = 1_200_000, wallMillis = 500)
    }
}

/**
 * What one call into a plugin spent, for the plugin editor's meter. The
 * interpreter counts it and reports it with each result
 * ([PythonSession.Result.phases]); nothing here counts.
 */
object PluginBudget {

    /**
     * [instructions] are interpreter steps against [instructionLimit];
     * [elapsedNanos] is wall time against [wallLimitMillis].
     */
    data class Usage(
        val instructions: Long,
        val instructionLimit: Long,
        val elapsedNanos: Long,
        val wallLimitMillis: Long,
        val running: Boolean,
    ) {
        companion object {
            val NONE = Usage(0L, 0L, 0L, 0L, running = false)
        }
    }
}
