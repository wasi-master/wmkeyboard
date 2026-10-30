package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, text: String = "hello"): File {
        val f = File(temp.root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun dir(path: String): File = File(temp.root, path).apply { mkdirs() }

    @Test
    fun namesWithAPathInThemAreRefused() {
        assertTrue(FileOps.validName("report (final).pdf"))
        assertFalse(FileOps.validName(""))
        assertFalse(FileOps.validName("   "))
        assertFalse(FileOps.validName("."))
        assertFalse(FileOps.validName(".."))
        assertFalse(FileOps.validName("../escape"))
        assertFalse(FileOps.validName("a/b"))
        assertFalse(FileOps.validName("x".repeat(256)))
    }

    @Test
    fun aTakenNameGetsANumberBeforeItsExtension() {
        val folder = dir("d")
        File(folder, "a.txt").writeText("")
        assertEquals("a (1).txt", FileOps.uniqueTarget(folder, "a.txt").name)
        File(folder, "a (1).txt").writeText("")
        assertEquals("a (2).txt", FileOps.uniqueTarget(folder, "a.txt").name)
        File(folder, "plain").writeText("")
        assertEquals("plain (1)", FileOps.uniqueTarget(folder, "plain").name)
        File(folder, ".hidden").writeText("")
        assertEquals(".hidden (1)", FileOps.uniqueTarget(folder, ".hidden").name)
    }

    @Test
    fun renameRefusesATakenNameAndABadOne() {
        val a = file("a.txt")
        file("b.txt")
        assertNull(FileOps.rename(a, "b.txt"))
        assertNull(FileOps.rename(a, "../c.txt"))
        assertEquals("c.txt", FileOps.rename(a, "c.txt")?.name)
    }

    @Test
    fun newFolderRefusesATakenNameAndABadOne() {
        val root = dir("root")
        assertNotNull(FileOps.newFolder(root, "made"))
        assertNull(FileOps.newFolder(root, "made"))
        assertNull(FileOps.newFolder(root, "a/b"))
    }

    @Test
    fun copyLeavesTheSourceAndCarriesFolders() = runBlocking {
        val tree = dir("tree")
        File(tree, "inner.txt").writeText("inner")
        dir("tree/sub")
        File(tree, "sub/deep.txt").writeText("deep")
        val target = dir("target")

        val result = FileOps.copy(listOf(tree), target)

        assertEquals(1, result.done)
        assertEquals(0, result.failed)
        assertTrue(File(tree, "inner.txt").exists())
        assertEquals("deep", File(target, "tree/sub/deep.txt").readText())
        assertTrue(result.touched.any { it.endsWith("deep.txt") })
    }

    @Test
    fun copyIntoItsOwnFolderIsRefused() = runBlocking {
        val tree = dir("tree")
        val inside = dir("tree/sub")
        val result = FileOps.copy(listOf(tree), inside)
        assertEquals(1, result.failed)
        assertTrue(inside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun moveIntoItsOwnFolderIsRefusedAndNothingGoes() = runBlocking {
        val tree = dir("tree")
        file("tree/keep.txt")
        val inside = dir("tree/sub")
        val result = FileOps.move(listOf(tree), inside)
        assertEquals(1, result.failed)
        assertTrue(File(tree, "keep.txt").exists())
    }

    @Test
    fun moveGoesAcrossFoldersAndLeavesNothingBehind() = runBlocking {
        val a = file("from/a.txt", "payload")
        val target = dir("to")
        val result = FileOps.move(listOf(a), target)
        assertEquals(1, result.done)
        assertFalse(a.exists())
        assertEquals("payload", File(target, "a.txt").readText())
    }

    @Test
    fun movingSomethingToWhereItIsChangesNothing() = runBlocking {
        val a = file("d/a.txt")
        val result = FileOps.move(listOf(a), a.parentFile!!)
        assertEquals(1, result.done)
        assertTrue(a.exists())
        assertEquals(listOf("a.txt"), a.parentFile!!.list()?.toList())
    }

    @Test
    fun aTakenNameInTheTargetKeepsBoth() = runBlocking {
        val a = file("from/a.txt", "new")
        val target = dir("to")
        File(target, "a.txt").writeText("old")
        FileOps.copy(listOf(a), target)
        assertEquals("old", File(target, "a.txt").readText())
        assertEquals("new", File(target, "a (1).txt").readText())
    }

    @Test
    fun aMissingSourceFailsWithoutStoppingTheRest() = runBlocking {
        val a = file("a.txt")
        val target = dir("to")
        val result = FileOps.copy(listOf(File(temp.root, "gone"), a), target)
        assertEquals(1, result.done)
        assertEquals(1, result.failed)
        assertTrue(File(target, "a.txt").exists())
    }

    @Test
    fun aFailedCopyLeavesNoPartOfItself() = runBlocking {
        val a = file("a.txt")
        // A target folder that is really a file: nothing can be made inside it.
        val notAFolder = file("blocker")
        val result = FileOps.copy(listOf(a), notAFolder)
        assertEquals(1, result.failed)
        assertTrue(notAFolder.isFile)
        assertTrue(a.exists())
    }

    @Test
    fun deleteTakesFoldersWhole() = runBlocking {
        val tree = dir("tree")
        file("tree/a.txt")
        file("tree/sub/b.txt")
        val result = FileOps.delete(listOf(tree))
        assertEquals(1, result.done)
        assertFalse(tree.exists())
    }

    @Test
    fun deletingALinkLeavesWhatItPointsAt() = runBlocking {
        val kept = dir("kept")
        file("kept/precious.txt")
        val holder = dir("holder")
        Files.createSymbolicLink(File(holder, "link").toPath(), kept.toPath())
        val result = FileOps.delete(listOf(holder))
        assertEquals(1, result.done)
        assertFalse(holder.exists())
        assertTrue(File(kept, "precious.txt").exists())
    }

    @Test
    fun copyDoesNotFollowALinkOutOfTheFolder() = runBlocking {
        val outside = dir("outside")
        file("outside/secret.txt")
        val tree = dir("tree")
        file("tree/mine.txt")
        Files.createSymbolicLink(File(tree, "link").toPath(), outside.toPath())
        val target = dir("to")
        FileOps.copy(listOf(tree), target)
        assertTrue(File(target, "tree/mine.txt").exists())
        assertFalse(File(target, "tree/link").exists())
    }

    @Test
    fun providerPathsStayInsideTheVolumes() {
        val volume = dir("volume")
        val inside = file("volume/ok.txt")
        val outside = file("private/key.txt")
        val roots = listOf(volume)

        assertEquals(inside.canonicalFile, LocalFilePaths.resolve(inside.path, roots))
        assertNull(LocalFilePaths.resolve(outside.path, roots))
        assertNull(LocalFilePaths.resolve(volume.path + "/../private/key.txt", roots))
        assertNull(LocalFilePaths.resolve(volume.path + "/not-there.txt", roots))
        // A folder is not something to open.
        assertNull(LocalFilePaths.resolve(volume.path, roots))
    }

    @Test
    fun aLinkInsideTheVolumeCannotReachOutOfIt() {
        val volume = dir("volume")
        val outside = file("private/key.txt")
        val link = File(volume, "link.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(LocalFilePaths.resolve(link.path, listOf(volume)))
    }

    @Test
    fun aSiblingWithTheSamePrefixIsNotInside() {
        val volume = dir("volume")
        val sibling = file("volume2/x.txt")
        assertNull(LocalFilePaths.resolve(sibling.path, listOf(volume)))
        assertFalse(FileOps.isInside(volume, sibling))
    }
}
