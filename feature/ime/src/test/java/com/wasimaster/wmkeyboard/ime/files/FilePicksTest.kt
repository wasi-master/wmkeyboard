package com.wasimaster.wmkeyboard.ime.files

import com.wasimaster.wmkeyboard.core.files.FileVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePicksTest {

    @Test
    fun thePanelNeedsTheChannelTheSettingAndAnOpenPhone() {
        assertTrue(FilePicks.usesPanel(channelAllows = true, settingOn = true, userUnlocked = true, deviceLocked = false))
        assertFalse(FilePicks.usesPanel(channelAllows = false, settingOn = true, userUnlocked = true, deviceLocked = false))
        assertFalse(FilePicks.usesPanel(channelAllows = true, settingOn = false, userUnlocked = true, deviceLocked = false))
        assertFalse(FilePicks.usesPanel(channelAllows = true, settingOn = true, userUnlocked = false, deviceLocked = false))
        assertFalse(FilePicks.usesPanel(channelAllows = true, settingOn = true, userUnlocked = true, deviceLocked = true))
    }

    private val volumes = listOf(
        FileVolume("/storage/emulated/0", "Internal", primary = true),
        FileVolume("/storage/ABCD-1234", "SD card", primary = false),
    )

    @Test
    fun crumbsRunFromTheVolumeDown() {
        val crumbs = fileCrumbs("/storage/emulated/0/Download/work", volumes)
        assertEquals(listOf("Internal", "Download", "work"), crumbs.map { it.label })
        assertEquals("/storage/emulated/0/Download", crumbs[1].path)
    }

    @Test
    fun aVolumeRootIsOneCrumb() {
        assertEquals(listOf("SD card"), fileCrumbs("/storage/ABCD-1234", volumes).map { it.label })
    }

    @Test
    fun aPathOnNoKnownVolumeStillSaysWhereItIs() {
        assertEquals(listOf("somewhere"), fileCrumbs("/mnt/somewhere", volumes).map { it.label })
    }

    @Test
    fun aVolumeWhoseNameStartsTheSameIsNotConfused() {
        val crumbs = fileCrumbs("/storage/emulated/01/x", volumes)
        assertEquals(listOf("x"), crumbs.map { it.label })
    }

    @Test
    fun onlyFilesWithinTheLimitCanBeAdded() {
        val request = FilePickRequest.GalleryFiles(limit = 1)
        assertFalse(FileManagerUi(request = request).canPick)
        assertTrue(FileManagerUi(request = request, selection = setOf("/no/such/file")).canPick)
        assertFalse(FileManagerUi(request = request, selection = setOf("/a", "/b")).canPick)
    }
}
