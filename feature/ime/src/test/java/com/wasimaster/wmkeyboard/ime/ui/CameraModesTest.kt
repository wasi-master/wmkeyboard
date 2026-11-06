package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.PanelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The camera's scanner modes (#1156). */
class CameraModesTest {

    private val everyBuild: (ToolbarTool) -> Boolean = { true }

    @Test
    fun `every mode whose tool is on is offered, in rail order`() {
        val enabled = listOf(ToolbarTool.DOC_SCAN, ToolbarTool.OCR, ToolbarTool.CAMERA, ToolbarTool.QR_SCAN)
        assertEquals(
            CameraMode.entries.toList(),
            CameraMode.available(enabled, CameraMode.PHOTO, everyBuild),
        )
    }

    @Test
    fun `a tool turned off leaves the rail, but the mode on screen stays`() {
        val enabled = listOf(ToolbarTool.QR_SCAN)
        assertEquals(
            listOf(CameraMode.PHOTO, CameraMode.QR),
            CameraMode.available(enabled, CameraMode.PHOTO, everyBuild),
        )
        assertEquals(listOf(CameraMode.TEXT), CameraMode.available(emptyList(), CameraMode.TEXT, everyBuild))
    }

    @Test
    fun `a tool this build lacks is never offered`() {
        val enabled = CameraMode.entries.map { it.tool }
        assertEquals(
            listOf(CameraMode.PHOTO, CameraMode.QR, CameraMode.DOCUMENT),
            CameraMode.available(enabled, CameraMode.PHOTO) { it != ToolbarTool.OCR },
        )
    }

    @Test
    fun `each mode opens its tool's panel, and the document scanner has none`() {
        assertEquals(PanelMode.CAMERA, CameraMode.PHOTO.panel)
        assertEquals(PanelMode.QR_SCAN, CameraMode.QR.panel)
        assertEquals(PanelMode.OCR, CameraMode.TEXT.panel)
        assertNull(CameraMode.DOCUMENT.panel)
    }
}
