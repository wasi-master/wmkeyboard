package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StickerPackStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): StickerPackStore = StickerPackStore(temp.root)

    private fun webp(marker: Byte = 1) = ProcessedSticker(
        bytes = byteArrayOf(marker, 2, 3),
        mime = "image/webp",
        animated = false,
        aspectRatio = 1f,
    )

    @Test
    fun `adds a sticker and writes its file`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val added = store.addSticker(pack.id, webp(), name = "grumpy")
        assertTrue(added is StickerAddResult.Added)
        val sticker = (added as StickerAddResult.Added).sticker
        assertEquals("${sticker.id}.webp", sticker.fileName)
        assertTrue(store.fileFor(pack.id, sticker)!!.isFile)
        assertEquals(1, store.packs().single().stickers.size)
    }

    @Test
    fun `survives a reload`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        first.addSticker(pack.id, webp())

        val second = store()
        assertEquals("Cats", second.packs().single().name)
        assertEquals(1, second.totalStickers())
    }

    @Test
    fun `drops manifest entries whose file vanished`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        val sticker = (first.addSticker(pack.id, webp()) as StickerAddResult.Added).sticker
        assertTrue(first.fileFor(pack.id, sticker)!!.delete())

        assertEquals(0, store().totalStickers())
    }

    @Test
    fun `deletes files nothing references`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        val orphan = File(first.packDir(pack.id), "stray.webp").apply { writeBytes(byteArrayOf(9)) }
        val strayPack = File(temp.root, "pack_nobody").apply { mkdirs() }

        store()
        assertFalse(orphan.exists())
        assertFalse(strayPack.exists())
    }

    @Test
    fun `leaves an import staging directory alone`() {
        val store = store()
        val staging = store.stagingDir()!!
        File(staging, "e1.bin").writeBytes(byteArrayOf(1))

        store.reload()
        assertTrue(staging.isDirectory)
    }

    @Test
    fun `sweeps a staging directory a dead import left a day ago`() {
        val store = store()
        val staging = store.stagingDir()!!
        File(staging, "e1.bin").writeBytes(byteArrayOf(1))
        assertTrue(staging.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000))

        store.reload()
        assertFalse(staging.exists())
    }

    @Test
    fun `removes several stickers in one go`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val stickers = (1..4).map { (store.addSticker(pack.id, webp(it.toByte())) as StickerAddResult.Added).sticker }
        val gone = setOf(stickers[0].id, stickers[2].id, "s_not_here")

        assertEquals(2, store.removeStickers(pack.id, gone))
        val left = store.pack(pack.id)!!.stickers
        assertEquals(listOf(stickers[1].id, stickers[3].id), left.map { it.id })
        assertFalse(File(store.packDir(pack.id), stickers[0].fileName).exists())
        assertFalse(File(store.packDir(pack.id), stickers[2].fileName).exists())
        assertTrue(store.fileFor(pack.id, stickers[1])!!.isFile)
        // And the manifest on disk says the same.
        assertEquals(2, store().pack(pack.id)!!.stickers.size)
    }

    @Test
    fun `refuses a sticker past the per-pack cap`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        repeat(StickerPackStore.MAX_STICKERS_PER_PACK) { store.addSticker(pack.id, webp()) }
        assertEquals(StickerAddResult.PackFull, store.addSticker(pack.id, webp()))
    }

    @Test
    fun `refuses a pack past the cap`() {
        val store = store()
        repeat(StickerPackStore.MAX_PACKS) { store.createPack("Pack $it") }
        assertNull(store.createPack("One more"))
    }

    @Test
    fun `disambiguates a duplicate pack name`() {
        val store = store()
        store.createPack("Cats")
        assertEquals("Cats (2)", store.createPack("Cats")!!.name)
        assertEquals("Cats (3)", store.createPack("Cats")!!.name)
    }

    @Test
    fun `deleting a pack removes its files`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        store.addSticker(pack.id, webp())
        val dir = store.packDir(pack.id)!!

        store.deletePack(pack.id)
        assertFalse(dir.exists())
        assertTrue(store.isEmpty())
    }

    @Test
    fun `moving a sticker carries its file across`() {
        val store = store()
        val from = store.createPack("Cats")!!
        val to = store.createPack("Dogs")!!
        val sticker = (store.addSticker(from.id, webp()) as StickerAddResult.Added).sticker

        assertTrue(store.moveSticker(from.id, sticker.id, to.id))
        assertFalse(File(store.packDir(from.id), sticker.fileName).exists())
        assertTrue(File(store.packDir(to.id), sticker.fileName).isFile)
        assertEquals(0, store.pack(from.id)!!.stickers.size)
        assertEquals(1, store.pack(to.id)!!.stickers.size)
    }

    @Test
    fun `moving several takes them across in order, and stops at a full pack`() {
        val store = store()
        val from = store.createPack("Cats")!!
        val to = store.createPack("Dogs")!!
        val added = List(3) { (store.addSticker(from.id, webp()) as StickerAddResult.Added).sticker }

        assertEquals(2, store.moveStickers(from.id, setOf(added[0].id, added[2].id), to.id))
        assertEquals(listOf(added[1].id), store.pack(from.id)!!.stickers.map { it.id })
        assertEquals(listOf(added[0].id, added[2].id), store.pack(to.id)!!.stickers.map { it.id })
    }

    @Test
    fun `a copy has its own file, so deleting the first leaves it whole`() {
        val store = store()
        val from = store.createPack("Cats")!!
        val to = store.createPack("Dogs")!!
        val sticker = (store.addSticker(from.id, webp()) as StickerAddResult.Added).sticker

        assertEquals(1, store.copyStickers(from.id, setOf(sticker.id), to.id))
        val copy = store.pack(to.id)!!.stickers.single()
        assertTrue(copy.id != sticker.id)
        assertEquals(1, store.pack(from.id)!!.stickers.size)

        store.removeStickers(from.id, setOf(sticker.id))
        assertTrue(File(store.packDir(to.id), copy.fileName).isFile)
    }

    @Test
    fun `copying into its own pack does nothing`() {
        val store = store()
        val from = store.createPack("Cats")!!
        val sticker = (store.addSticker(from.id, webp()) as StickerAddResult.Added).sticker

        assertEquals(0, store.copyStickers(from.id, setOf(sticker.id), from.id))
        assertEquals(1, store.pack(from.id)!!.stickers.size)
    }

    @Test
    fun `reordering moves a sticker within its pack`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val first = (store.addSticker(pack.id, webp(1)) as StickerAddResult.Added).sticker
        store.addSticker(pack.id, webp(2))

        store.reorderSticker(pack.id, first.id, 1)
        assertEquals(first.id, store.pack(pack.id)!!.stickers[1].id)
        // Clamped at the end rather than wrapping.
        store.reorderSticker(pack.id, first.id, 5)
        assertEquals(first.id, store.pack(pack.id)!!.stickers[1].id)
    }

    @Test
    fun `search matches name, tags and pack`() {
        val store = store()
        val cats = store.createPack("Cats")!!
        val dogs = store.createPack("Dogs")!!
        store.addSticker(cats.id, webp(1), name = "grumpy")
        store.addSticker(dogs.id, webp(2), name = "happy", keywords = listOf("🐶"))

        assertEquals(2, store.searchAsGifItems("").size)
        assertEquals(1, store.searchAsGifItems("grump").size)
        assertEquals(1, store.searchAsGifItems("🐶").size)
        // Pack name matches every sticker inside it.
        assertEquals(1, store.searchAsGifItems("dogs").size)
        assertEquals(0, store.searchAsGifItems("nothing").size)
        assertEquals(1, store.searchAsGifItems("", packId = cats.id).size)
    }

    @Test
    fun `replacing an image keeps the sticker and renames its file`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        store.addSticker(pack.id, webp(1), name = "first")
        val target = (
            store.addSticker(pack.id, webp(2), name = "grumpy", keywords = listOf("cat"))
                as StickerAddResult.Added
            ).sticker
        val oldFile = store.fileFor(pack.id, target)!!

        val replaced = store.replaceStickerImage(pack.id, target.id, webp(9))
        assertTrue(replaced is StickerAddResult.Added)
        val sticker = (replaced as StickerAddResult.Added).sticker

        assertEquals(target.id, sticker.id)
        assertEquals("grumpy", sticker.name)
        assertEquals(listOf("cat"), sticker.keywords)
        assertEquals(target.addedAt, sticker.addedAt)
        // Still second in the pack: an edit is not a re-add.
        assertEquals(1, store.pack(pack.id)!!.stickers.indexOfFirst { it.id == target.id })
        // A new name, or two caches would keep serving the old picture.
        assertEquals("${target.id}_1.webp", sticker.fileName)
        assertFalse(oldFile.exists())
        assertEquals(9.toByte(), store.fileFor(pack.id, sticker)!!.readBytes()[0])
    }

    @Test
    fun `replacing an image leaves nothing for reconcile to sweep`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        val sticker = (first.addSticker(pack.id, webp()) as StickerAddResult.Added).sticker
        first.replaceStickerImage(pack.id, sticker.id, webp(2))
        first.replaceStickerImage(pack.id, sticker.id, webp(3))

        val dir = File(temp.root, pack.id)
        assertEquals(1, dir.listFiles()!!.size)
        // The second edit counts on from the first.
        assertEquals("${sticker.id}_2.webp", store().pack(pack.id)!!.stickers.single().fileName)
        assertEquals(1, store().totalStickers())
    }

    @Test
    fun `replacing an image on a missing sticker changes nothing`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        assertEquals(StickerAddResult.PackMissing, store.replaceStickerImage(pack.id, "nope", webp()))
        assertEquals(StickerAddResult.PackMissing, store.replaceStickerImage("nopack", "nope", webp()))
        assertEquals(0, store.totalStickers())
    }

    @Test
    fun `file names count generations up`() {
        assertEquals("s1_1.webp", StickerPackStore.nextFileName("s1.webp", "s1", "image/webp"))
        assertEquals("s1_2.webp", StickerPackStore.nextFileName("s1_1.webp", "s1", "image/webp"))
        assertEquals("s1_10.webp", StickerPackStore.nextFileName("s1_9.webp", "s1", "image/webp"))
        // An id with an underscore of its own is not mistaken for a counter.
        assertEquals("a_b_1.webp", StickerPackStore.nextFileName("a_b.webp", "a_b", "image/webp"))
    }

    @Test
    fun `search matches a phrase that spans the name and the tags`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        // What an imported "grumpy cat" looks like once the editor has split
        // it: first word in the name, the rest in the tags.
        store.addSticker(pack.id, webp(), name = "grumpy", keywords = listOf("cat"))

        assertEquals(1, store.searchAsGifItems("grumpy cat").size)
    }

    @Test
    fun `a sticker found by its triggers becomes the same item a search lists`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (store.addSticker(pack.id, webp(), name = "grumpy") as StickerAddResult.Added).sticker

        assertEquals(store.searchAsGifItems("").single(), store.asGifItem(pack.id, sticker))
        assertEquals(null, store.asGifItem("gone", sticker))
    }

    @Test
    fun `item ids round-trip back to their sticker`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (store.addSticker(pack.id, webp()) as StickerAddResult.Added).sticker

        val item = store.searchAsGifItems("").single()
        assertTrue(item.id.startsWith(StickerPackStore.ITEM_PREFIX))
        val found = store.findByItemId(item.id)
        assertNotNull(found)
        assertEquals(pack.id, found!!.first.id)
        assertEquals(sticker.id, found.second.id)
        assertNull(store.findByItemId("giphy_123"))
    }

    @Test
    fun `originals are keyed by sticker id, so a move carries them`() {
        val store = store()
        val from = store.createPack("Cats")!!
        val to = store.createPack("Dogs")!!
        val sticker = (
            store.addSticker(from.id, webp(1), original = byteArrayOf(4))
                as StickerAddResult.Added
            ).sticker

        assertTrue(store.moveSticker(from.id, sticker.id, to.id))
        assertEquals(4.toByte(), store.originalFor(sticker.id)!!.readBytes()[0])
        // And the reload after it does not read the moved sticker as an orphan.
        assertNotNull(store().originalFor(sticker.id))
    }

    @Test
    fun `keeps the picture a sticker was made from, outside the pack`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(7, 7))
                as StickerAddResult.Added
            ).sticker

        val original = store.originalFor(sticker.id)
        assertNotNull(original)
        assertEquals(7.toByte(), original!!.readBytes()[0])
        // Not in the pack directory: that is what an export walks.
        assertEquals(listOf(sticker.fileName), store.packDir(pack.id)!!.list()!!.toList())
    }

    @Test
    fun `the first edit keeps the picture it replaced, later ones do not`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (store.addSticker(pack.id, webp(1)) as StickerAddResult.Added).sticker

        store.replaceStickerImage(pack.id, sticker.id, webp(2))
        assertEquals(1.toByte(), store.originalFor(sticker.id)!!.readBytes()[0])
        // A second edit must not promote the first edit over the picture the
        // sticker actually came from.
        store.replaceStickerImage(pack.id, sticker.id, webp(3))
        assertEquals(1.toByte(), store.originalFor(sticker.id)!!.readBytes()[0])
    }

    @Test
    fun `deleting a sticker or a pack deletes the originals with it`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val one = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        val two = (
            store.addSticker(pack.id, webp(2), original = byteArrayOf(2))
                as StickerAddResult.Added
            ).sticker

        store.removeSticker(pack.id, one.id)
        assertNull(store.originalFor(one.id))
        assertNotNull(store.originalFor(two.id))

        store.deletePack(pack.id)
        assertNull(store.originalFor(two.id))
    }

    @Test
    fun `reconcile sweeps orphan originals and keeps live ones`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        val sticker = (
            first.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        val orphan = File(first.originalsDir(), "s_nobody.webp").apply { writeBytes(byteArrayOf(9)) }

        val second = store()
        assertFalse(orphan.exists())
        // The directory itself is not an unknown pack.
        assertTrue(second.originalsDir()!!.isDirectory)
        assertNotNull(second.originalFor(sticker.id))
    }

    @Test
    fun `keeps the edit beside the picture and reads it back`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        val edit = StickerEditState(
            cropLeft = 0.25f,
            cropTop = 0.1f,
            cropWidth = 0.5f,
            cropHeight = 0.5f,
            borderWidthPx = 6f,
            borderColor = -1,
            brushPx = 44f,
            hasMask = true,
        )

        store.writeEditState(sticker.id, edit, byteArrayOf(8, 9))
        assertEquals(edit, store().editStateFor(sticker.id))
        assertEquals(8.toByte(), store.maskFor(sticker.id)!!.readBytes()[0])

        // Erasing everything back to nothing takes the mask with it.
        store.writeEditState(sticker.id, edit.copy(hasMask = false), null)
        assertNull(store.maskFor(sticker.id))
        assertNotNull(store.editStateFor(sticker.id))
    }

    @Test
    fun `an unreadable edit reads as none, and loses no picture`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        store.writeEditState(sticker.id, StickerEditState(brushPx = 20f), null)
        File(store.originalsDir(), "${sticker.id}.json").writeText("{ not json")

        assertNull(store.editStateFor(sticker.id))
        assertNotNull(store.originalFor(sticker.id))
    }

    @Test
    fun `the mask and the edit follow the picture when a sticker goes`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        store.writeEditState(sticker.id, StickerEditState(hasMask = true), byteArrayOf(2))

        store.removeSticker(pack.id, sticker.id)
        assertNull(store.originalFor(sticker.id))
        assertNull(store.maskFor(sticker.id))
        assertNull(store.editStateFor(sticker.id))
        assertEquals(0, store.originalsDir()!!.listFiles()!!.size)
    }

    @Test
    fun `reconcile keeps a live sticker's mask and edit`() {
        val first = store()
        val pack = first.createPack("Cats")!!
        val sticker = (
            first.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker
        first.writeEditState(sticker.id, StickerEditState(hasMask = true), byteArrayOf(2))
        val orphanMask = File(first.originalsDir(), "s_nobody.mask.png")
            .apply { writeBytes(byteArrayOf(9)) }

        val second = store()
        assertFalse(orphanMask.exists())
        assertNotNull(second.maskFor(sticker.id))
        assertNotNull(second.editStateFor(sticker.id))
    }

    @Test
    fun `clearing the originals leaves the stickers alone`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        val sticker = (
            store.addSticker(pack.id, webp(1), original = byteArrayOf(1))
                as StickerAddResult.Added
            ).sticker

        store.clearOriginals()
        assertNull(store.originalFor(sticker.id))
        assertTrue(store.fileFor(pack.id, sticker)!!.isFile)
        assertEquals(1, store.totalStickers())
    }

    @Test
    fun `a corrupt manifest loses no images`() {
        val store = store()
        val pack = store.createPack("Cats")!!
        store.addSticker(pack.id, webp())
        val dir = store.packDir(pack.id)!!
        File(temp.root, "packs.json").writeText("{ not json")

        assertTrue(store().isEmpty())
        // The manifest is unreadable, so the directory is left for a repair,
        // not swept as an orphan.
        assertTrue(dir.isDirectory)
        assertEquals(1, dir.listFiles()!!.size)
    }
}
