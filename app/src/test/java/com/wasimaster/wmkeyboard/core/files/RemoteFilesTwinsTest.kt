package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two things of one name in one folder, as Drive and MEGA allow: each row of
 * the listing must be its own file, whatever is done to it, and a path that
 * cannot say which one is refused rather than guessed. Against an in-memory
 * service that keys everything by id, as those two do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteFilesTwinsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fs = TwinFs()
    private lateinit var top: String

    @Before
    fun setUp() {
        RemoteFiles.source = object : RemotePlaces {
            override fun places(context: Context) = listOf(RemotePlace("twins", "Twins", "drive"))
            override fun open(context: Context, id: String): RemoteFs = fs
        }
        top = RemoteFiles.pathOf(context, "twins", emptyList())
    }

    @After
    fun tearDown() {
        RemoteFiles.closeAll()
    }

    private fun twins(dir: String = top, name: String = "a.txt"): List<FileEntry> =
        RemoteFiles.list(context, dir)!!.filter { it.name == name }

    private fun read(path: String): String? = RemoteFiles.input(context, path)?.use { it.readBytes().decodeToString() }

    @Test
    fun eachTwinHasItsOwnPath() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        fs.file(fs.root, "b.txt", "solo")
        val listed = RemoteFiles.list(context, top)!!
        val pair = listed.filter { it.name == "a.txt" }
        assertEquals(2, pair.size)
        assertNotEquals(pair[0].path, pair[1].path)
        assertEquals(setOf("one", "two"), pair.map { read(it.path) }.toSet())
        // A name with no namesake keeps a plain path.
        assertEquals(RemoteFiles.pathOf(context, "twins", listOf("b.txt")), listed.single { it.name == "b.txt" }.path)
        pair.forEach {
            assertEquals("a.txt", RemoteFiles.nameOf(it.path))
            assertEquals("a.txt", RemoteFiles.entry(context, it.path)!!.name)
            assertEquals(it.path, RemoteFiles.entry(context, it.path)!!.path)
        }
    }

    @Test
    fun removingOneTwinLeavesTheOther() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        val second = twins().single { read(it.path) == "two" }
        assertTrue(RemoteFiles.remove(context, second.path))
        assertEquals(listOf("one"), twins().map { read(it.path) })
        // Gone means gone: its path does not fall through to its namesake.
        assertNull(RemoteFiles.entry(context, second.path))
        assertTrue(RemoteFiles.remove(context, second.path))
        assertEquals(listOf("one"), twins().map { read(it.path) })
    }

    @Test
    fun aPathThatCannotSayWhichIsRefused() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        val plain = RemoteFiles.pathOf(context, "twins", listOf("a.txt"))
        assertFalse(RemoteFiles.remove(context, plain))
        assertNull(RemoteFiles.rename(context, plain, "c.txt"))
        assertNull(RemoteFiles.input(context, plain))
        assertEquals(2, twins().size)
    }

    @Test
    fun renamingOneTwinRenamesOnlyIt() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        val first = twins().single { read(it.path) == "one" }
        val renamed = RemoteFiles.rename(context, first.path, "c.txt")
        assertEquals(RemoteFiles.pathOf(context, "twins", listOf("c.txt")), renamed)
        assertEquals("one", read(renamed!!))
        // The other is alone under its name now, at a plain path.
        assertEquals(listOf("two"), twins().map { read(it.path) })
        assertEquals(RemoteFiles.pathOf(context, "twins", listOf("a.txt")), twins().single().path)
    }

    @Test
    fun movingOneTwinMovesOnlyIt() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        val box = RemoteFiles.makeFolder(context, top, "box", unique = false)!!
        val second = twins().single { read(it.path) == "two" }
        val moved = RemoteFiles.moveWithin(context, second.path, box)
        assertNotNull(moved)
        assertEquals("two", read(moved!!))
        assertEquals(listOf("one"), twins().map { read(it.path) })
    }

    @Test
    fun insideATwinFolder() {
        val left = fs.folder(fs.root, "docs")
        val right = fs.folder(fs.root, "docs")
        fs.file(left, "x.txt", "left")
        fs.file(right, "x.txt", "right")
        val folders = twins(name = "docs")
        assertEquals(2, folders.size)
        val seen = folders.map { dir -> RemoteFiles.list(context, dir.path)!!.single().let { read(it.path) } }
        assertEquals(setOf("left", "right"), seen.toSet())
        // A new file goes into the folder whose row it was dropped on.
        val leftRow = folders.single { dir -> RemoteFiles.list(context, dir.path)!!.any { read(it.path) == "left" } }
        val made = RemoteFiles.write(context, leftRow.path, "y.txt", { it.write("new".toByteArray()) })!!
        assertEquals("new", read(made))
        assertEquals(2, fs.children(left).size)
        assertEquals(1, fs.children(right).size)
        // Removing one twin folder takes its own contents only.
        assertTrue(RemoteFiles.remove(context, leftRow.path))
        assertEquals(listOf("right"), twins(name = "docs").flatMap { d -> RemoteFiles.list(context, d.path)!!.map { read(it.path) } })
    }

    @Test
    fun twinsInsideARemovedFolderAllGo() {
        val dir = fs.folder(fs.root, "old")
        fs.file(dir, "a.txt", "one")
        fs.file(dir, "a.txt", "two")
        fs.file(fs.root, "keep.txt", "keep")
        assertTrue(RemoteFiles.remove(context, RemoteFiles.pathOf(context, "twins", listOf("old"))))
        assertEquals(listOf("keep.txt"), RemoteFiles.list(context, top)!!.map { it.name })
    }

    @Test
    fun aNewFileNeverJoinsTwins() {
        fs.file(fs.root, "a.txt", "one")
        fs.file(fs.root, "a.txt", "two")
        val made = RemoteFiles.write(context, top, "a.txt", { it.write("three".toByteArray()) })!!
        assertEquals("a (1).txt", RemoteFiles.nameOf(made))
        assertEquals(2, twins().size)
    }

    /** A service that keys by id and lets names repeat, resolving twins only through [pick]. */
    class TwinFs : RemoteFs {
        private class Node(val id: String, var parent: String?, var name: String, val folder: Boolean, var bytes: ByteArray = ByteArray(0))

        private val nodes = LinkedHashMap<String, Node>()
        private var next = 0
        private var picks: Map<List<String>, String> = emptyMap()
        val root: String = add(null, "", folder = true)

        private fun add(parent: String?, name: String, folder: Boolean, bytes: ByteArray = ByteArray(0)): String {
            val id = "id${next++}"
            nodes[id] = Node(id, parent, name, folder, bytes)
            return id
        }

        fun file(parent: String, name: String, text: String) = add(parent, name, false, text.toByteArray())

        fun folder(parent: String, name: String) = add(parent, name, true)

        fun children(id: String): List<String> = nodes.values.filter { it.parent == id }.map { it.id }

        override fun pick(picks: Map<List<String>, String>) {
            this.picks = picks
        }

        private fun resolve(path: List<String>): Node? {
            var at = nodes.getValue(root)
            for (i in path.indices) {
                val matches = nodes.values.filter { it.parent == at.id && it.name == path[i] }
                val picked = picks[path.subList(0, i + 1)]
                at = when {
                    picked != null -> matches.firstOrNull { it.id == picked } ?: return null
                    matches.isEmpty() -> return null
                    matches.size == 1 -> matches[0]
                    else -> throw IOException("two called ${path[i]}")
                }
            }
            return at
        }

        private fun entryOf(n: Node) = RemoteEntry(n.name, n.folder, if (n.folder) -1 else n.bytes.size.toLong(), 0, key = n.id)

        override fun list(dir: List<String>): List<RemoteEntry> {
            val at = resolve(dir)?.takeIf { it.folder } ?: throw IOException("no folder")
            return nodes.values.filter { it.parent == at.id }.map(::entryOf)
        }

        override fun stat(path: List<String>): RemoteEntry? = if (path.isEmpty()) RemoteEntry("", true) else resolve(path)?.let(::entryOf)

        override fun download(path: List<String>, to: OutputStream) {
            to.write((resolve(path)?.takeIf { !it.folder } ?: throw IOException("no file")).bytes)
        }

        override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
            val at = resolve(dir) ?: throw IOException("no folder")
            add(at.id, name, false, from.readBytes())
        }

        override fun mkdir(dir: List<String>, name: String) {
            add((resolve(dir) ?: throw IOException("no folder")).id, name, true)
        }

        override fun delete(path: List<String>, isDirectory: Boolean) {
            val n = resolve(path) ?: return
            if (n.folder && children(n.id).isNotEmpty()) throw IOException("not empty")
            nodes.remove(n.id)
        }

        override fun rename(path: List<String>, newName: String) {
            (resolve(path) ?: throw IOException("nothing")).name = newName
        }

        override fun move(path: List<String>, toDir: List<String>): Boolean {
            val n = resolve(path) ?: throw IOException("nothing")
            n.parent = (resolve(toDir) ?: throw IOException("no folder")).id
            return true
        }

        override val atomicUpload: Boolean get() = true
    }
}
