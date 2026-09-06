package com.wasimaster.wmkeyboard.core.settings

// Same package, different Gradle module (:core:common) — see ToolbarTool.kt.

/**
 * How a long run of digits is punctuated when the number chip offers to group
 * it.
 *
 * [WESTERN] is the three-digit grouping used almost everywhere — 1,234,567.
 * [SOUTH_ASIAN] is the lakh/crore grouping of India, Bangladesh, Pakistan,
 * Nepal and Sri Lanka, where only the rightmost group holds three digits and
 * the rest hold two — 12,34,567, one lakh as 1,00,000. [AUTO] reads the
 * language being typed in, so the same keyboard groups a Bangla message one
 * way and an English one the other.
 */
enum class NumberGrouping { AUTO, WESTERN, SOUTH_ASIAN }
