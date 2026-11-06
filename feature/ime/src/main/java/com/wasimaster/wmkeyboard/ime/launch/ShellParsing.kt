package com.wasimaster.wmkeyboard.ime.launch

/**
 * Reading `dumpsys activity activities`, which the shell routes need for task
 * ids and split state. Pure, so each Android version's line format is tested.
 *
 * Task lines by version:
 *
 * - 12 and later: `* Task{3f2a1b #123 type=standard A=10234:com.foo U=0 visible=true … mode=fullscreen …}`
 * - 10 and 11: `* Task{… #123 visible=true type=standard mode=fullscreen translucent=false A=10234:com.foo U=0 …}`
 * - 9 and older: `* TaskRecord{… #123 A=com.foo U=0 StackId=1 sz=1}`
 * - A task with no affinity names its intent instead: `I=com.foo/.Main`.
 */
object ShellParsing {

    private val TASK = Regex("""\*\s*Task(?:Record)?\{[0-9a-f]+ #(\d+)([^}]*)}""")
    private val AFFINITY = Regex("""\bA=(?:\d+:)?([A-Za-z0-9_.]+)""")
    private val INTENT = Regex("""\bI=([A-Za-z0-9_.]+)/""")

    /** The topmost task of [packageName], dumpsys listing top tasks first. */
    fun topTaskOf(dump: String, packageName: String): Int? =
        TASK.findAll(dump).firstOrNull { match ->
            val body = match.groupValues[2]
            val pkg = AFFINITY.find(body)?.groupValues?.get(1) ?: INTENT.find(body)?.groupValues?.get(1)
            pkg == packageName
        }?.groupValues?.get(1)?.toIntOrNull()

    /** Two visible app tasks in multi-window mode: split screen is up. */
    fun splitVisible(dump: String): Boolean =
        TASK.findAll(dump).count { match ->
            val body = match.groupValues[2]
            "type=standard" in body && "visible=true" in body && "mode=multi-window" in body
        } >= 2
}
