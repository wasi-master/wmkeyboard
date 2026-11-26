package com.wasimaster.wmkeyboard.core.tools

/**
 * The numbers behind the calculator tool's chart page (#1574), read from what
 * was typed or pasted: a plain list (`3, 5, 2.5`, or one a line), or a table
 * copied out of a spreadsheet or a message, one row a line, a label and a
 * value (`Apples 12`, `Pears,7`, `Plums<tab>3`). A line with no number in it
 * is a header and is skipped.
 */
object ChartData {

    class Point(val label: String, val value: Double)

    class Stats(
        val count: Int,
        val sum: Double,
        val mean: Double,
        val median: Double,
        val min: Double,
        val max: Double,
    )

    private val CELL_SEPARATORS = Regex("[\t;,]")
    private val SPACES = Regex("\\s+")

    fun parse(text: String): List<Point> {
        val points = ArrayList<Point>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val cells = (if (CELL_SEPARATORS.containsMatchIn(line)) line.split(CELL_SEPARATORS) else line.split(SPACES))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val numbers = cells.map(::number)
            when {
                numbers.isEmpty() || numbers.all { it == null } -> continue
                // A list of numbers: each is a point of its own.
                numbers.all { it != null } -> numbers.forEach { points.add(Point((points.size + 1).toString(), it!!)) }
                // A row: the words are its label, the last number its value.
                else -> {
                    val label = cells.filterIndexed { i, _ -> numbers[i] == null }.joinToString(" ")
                    points.add(Point(label, numbers.last { it != null }!!))
                }
            }
        }
        return points
    }

    /** A cell as a number: `−3`, `1.5e3`, `12%` (the percent sign dropped). */
    private fun number(cell: String): Double? =
        cell.replace('−', '-').removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    fun stats(values: List<Double>): Stats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        val sum = values.sum()
        return Stats(values.size, sum, sum / values.size, median, sorted.first(), sorted.last())
    }
}
