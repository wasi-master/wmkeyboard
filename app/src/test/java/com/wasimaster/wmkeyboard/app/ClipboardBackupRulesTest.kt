package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The clipboard history stays out of Android's own backup and device
 * transfer (#1215): what was copied, passwords and codes included, never
 * leaves the phone that way. Read from the rule files themselves, both the
 * API 31+ one and the older one.
 */
class ClipboardBackupRulesTest {

    private fun xml(name: String): File =
        listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")).first { it.exists() }

    private fun excludes(root: Element, section: String? = null): List<String> {
        val scope = if (section == null) {
            root
        } else {
            root.getElementsByTagName(section).item(0) as Element
        }
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path").trimEnd('/') }
    }

    /** Whether excluding [paths] keeps `clipboard/history.json` out. */
    private fun coversHistory(paths: List<String>): Boolean =
        paths.any { it == "clipboard" || it == "clipboard/history.json" }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    @Test
    fun `cloud backup and device transfer leave the history out`() {
        val root = parse(xml("data_extraction_rules.xml"))
        assertTrue(coversHistory(excludes(root, "cloud-backup")))
        assertTrue(coversHistory(excludes(root, "device-transfer")))
    }

    @Test
    fun `the pre-Android-12 rules leave it out too`() {
        assertTrue(coversHistory(excludes(parse(xml("backup_rules.xml")))))
    }
}
