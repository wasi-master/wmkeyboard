package com.wasimaster.wmkeyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The one way the keyboard writes the system clipboard (#1215): the sensitive
 * flag where asked, the self-write token, and the keyboard's own mark.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardWriteGateTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val system = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @After
    fun reset() {
        KeyboardClipboard.isPrivate = false
        KeyboardClipboard.onPrivateCopy = null
        SelfWriteLedger.clear()
    }

    private fun primary(): ClipData? = system.primaryClip

    @Test
    fun `a sensitive write carries Android's flag, an ordinary one does not`() {
        assertTrue(ClipboardWriteGate.writeText(context, "hunter2", sensitive = true))
        assertTrue(ClipboardWriteGate.isMarkedSensitive(primary()))
        ClipboardWriteGate.writeText(context, "hello")
        assertFalse(ClipboardWriteGate.isMarkedSensitive(primary()))
        assertEquals("hello", primary()?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `every write is the keyboard's own, and a self-write is let go by once`() {
        ClipboardWriteGate.writeText(context, "tidied", selfWrite = true)
        val clip = primary()
        assertTrue(ClipboardWriteGate.isOwnWrite(clip))
        assertTrue(ClipboardWriteGate.consumeSelfWrite(clip))
        assertFalse(ClipboardWriteGate.consumeSelfWrite(clip))
    }

    @Test
    fun `a write that did not ask to be skipped is not`() {
        ClipboardWriteGate.writeText(context, "copied")
        assertFalse(ClipboardWriteGate.consumeSelfWrite(primary()))
    }

    @Test
    fun `another app's clip is neither the keyboard's nor a self-write`() {
        val clip = ClipData.newPlainText("", "from an app")
        assertFalse(ClipboardWriteGate.isOwnWrite(clip))
        assertFalse(ClipboardWriteGate.consumeSelfWrite(clip))
        assertFalse(ClipboardWriteGate.consumeSelfWrite(null))
    }

    @Test
    fun `putting a clip back keeps the extras it came with`() {
        val clip = ClipData.newPlainText("", "secret")
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipboardWriteGate.EXTRA_IS_SENSITIVE, true)
            putString("app.extra", "kept")
        }
        ClipboardWriteGate.writeClip(context, clip, selfWrite = true)
        val back = primary()
        assertTrue(ClipboardWriteGate.isMarkedSensitive(back))
        assertEquals("kept", back?.description?.extras?.getString("app.extra"))
    }

    @Test
    fun `the generator's kind of copy is flagged through KeyboardClipboard`() {
        KeyboardClipboard.copy(context, "Tr0ub4dor&3", "Password", sensitive = true)
        assertTrue(ClipboardWriteGate.isMarkedSensitive(primary()))
    }

    @Test
    fun `a private copy tells the service whether it is a secret`() {
        var told: Pair<String, Boolean>? = null
        KeyboardClipboard.onPrivateCopy = { text, secret -> told = text to secret }
        KeyboardClipboard.isPrivate = true
        KeyboardClipboard.copy(context, "Tr0ub4dor&3", sensitive = true)
        assertEquals("Tr0ub4dor&3" to true, told)
        KeyboardClipboard.copy(context, "note")
        assertEquals("note" to false, told)
    }

    @Test
    fun `clear empties the clipboard`() {
        ClipboardWriteGate.writeText(context, "gone soon")
        assertTrue(ClipboardWriteGate.clear(context))
        val first = primary()?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        assertTrue(first == null || first.text.isNullOrEmpty())
    }
}
