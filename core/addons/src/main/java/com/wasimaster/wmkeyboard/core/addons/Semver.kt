package com.wasimaster.wmkeyboard.core.addons

/**
 * Just enough semver to answer one question: is the version in the manifest
 * newer than the one the user has installed?
 *
 * Deliberately lenient. The schema constrains publishers to `MAJOR.MINOR.PATCH`
 * with an optional suffix, but a manifest can be hand-written and reach us with
 * `1.2`, `v1.2.3` or `2026.07` in it. Rather than refuse to compare, missing
 * components read as zero and anything non-numeric sorts before anything
 * numeric — the worst case is that an update isn't offered, never that a
 * downgrade is.
 */
object Semver {

    /** Negative if [a] is older than [b], zero if equal, positive if newer. */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)

        val numbersA = numbers(coreA)
        val numbersB = numbers(coreB)
        for (i in 0 until maxOf(numbersA.size, numbersB.size)) {
            val result = numbersA.getOrElse(i) { 0 }.compareTo(numbersB.getOrElse(i) { 0 })
            if (result != 0) return result
        }

        // 1.0.0-rc1 precedes 1.0.0; two prereleases compare part by part.
        if (preA.isEmpty() && preB.isEmpty()) return 0
        if (preA.isEmpty()) return 1
        if (preB.isEmpty()) return -1
        return comparePrerelease(preA, preB)
    }

    /**
     * Marks a version that is a content hash rather than a number: `#` and the
     * start of the file's SHA-256. A Clink repository has no per-entry version
     * (see [ClinkRepo]), and its file hash is the only thing that says an entry
     * changed. Two hashes have no order, so any difference is an update.
     */
    const val CONTENT_PREFIX = "#"

    /** Whether [version] is a content hash; see [CONTENT_PREFIX]. */
    fun isContentVersion(version: String): Boolean = version.startsWith(CONTENT_PREFIX)

    /**
     * True when [candidate] is strictly newer than [installed]. Two content
     * hashes count as newer whenever they differ.
     */
    fun isNewer(candidate: String, installed: String): Boolean =
        if (isContentVersion(candidate) && isContentVersion(installed)) {
            candidate != installed
        } else {
            compare(candidate, installed) > 0
        }

    /** Splits off the prerelease, discarding build metadata, which never orders. */
    private fun split(version: String): Pair<String, String> {
        val trimmed = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val dash = trimmed.indexOf('-')
        return if (dash < 0) trimmed to "" else trimmed.take(dash) to trimmed.substring(dash + 1)
    }

    private fun numbers(core: String): List<Long> =
        core.split('.').map { part ->
            // takeWhile rather than toLongOrNull so "3beta" still reads as 3
            // instead of collapsing the whole component to zero.
            part.trim().takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
        }

    private fun comparePrerelease(a: String, b: String): Int {
        val partsA = a.split('.')
        val partsB = b.split('.')
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val left = partsA.getOrNull(i)
            val right = partsB.getOrNull(i)
            // A shorter prerelease precedes a longer one with the same prefix.
            if (left == null) return -1
            if (right == null) return 1
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                // Numeric identifiers always have lower precedence than alphanumeric.
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return 0
    }
}
