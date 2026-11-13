package com.wasimaster.wmkeyboard.core.settings.sink.imap

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A tagged `NO` or `BAD`. [text] is the server's words, for the log only. */
class ImapException(val kind: String, val text: String, cause: Throwable? = null) : IOException("$kind $text", cause)

/**
 * The client side of IMAP4rev1 (RFC 3501) that a backup needs: log in, open or
 * make a folder, append a message, fetch, flag and expunge. Over whatever
 * streams it is given, so the tests drive it with a scripted server and the
 * sink hands it a TLS socket.
 *
 * The only hard part of IMAP is the literal: a `{n}` at the end of a line
 * means n raw bytes follow, which is how the server sends anything with a line
 * break in it and how a client sends anything it cannot quote. [Reply] keeps
 * those bytes aside, in order, and the line text keeps its `{n}` marker.
 */
class ImapClient(input: InputStream, output: OutputStream) {

    private val input = input.buffered()
    private val output = output.buffered()
    private var tag = 0

    var capabilities: Set<String> = emptySet()
        private set

    /** One server line, with the bytes of any literals it carried. */
    class Reply(val text: String, val literals: List<ByteArray>)

    /** A command argument: an atom-safe string, or one that must be sent as a literal. */
    sealed class Arg {
        class Raw(val text: String) : Arg()
        class Str(val value: String) : Arg()
        class Bytes(val value: ByteArray) : Arg()
    }

    /** Reads the greeting. PREAUTH is fine; BYE is a refusal. */
    fun greeting() {
        val line = readReply().text
        if (line.startsWith("* BYE", ignoreCase = true)) throw ImapException("BYE", line)
        if (!line.startsWith("* OK", ignoreCase = true) && !line.startsWith("* PREAUTH", ignoreCase = true)) {
            throw IOException("IMAP: bad greeting")
        }
        parseCapabilities(line)
    }

    fun capability() {
        run(Arg.Raw("CAPABILITY")).forEach { parseCapabilities(it.text) }
    }

    fun startTls() {
        run(Arg.Raw("STARTTLS"))
    }

    fun login(user: String, password: String) {
        val replies = try {
            run(Arg.Raw("LOGIN"), Arg.Str(user), Arg.Str(password))
        } catch (refused: ImapException) {
            // Whatever the server's words, a refused LOGIN is the credentials.
            throw ImapException(KIND_AUTH, refused.text, refused)
        }
        replies.forEach { parseCapabilities(it.text) }
        // Servers often change their capabilities after login, and only some say so.
        capability()
    }

    /** Opens [mailbox], making it first when it does not exist. Returns the message count. */
    fun selectOrCreate(mailbox: String): Int {
        val name = Arg.Str(ModifiedUtf7.encode(mailbox))
        val replies = try {
            run(Arg.Raw("SELECT"), name)
        } catch (missing: ImapException) {
            if (missing.kind != "NO") throw missing
            run(Arg.Raw("CREATE"), name)
            run(Arg.Raw("SELECT"), name)
        }
        return replies.firstNotNullOfOrNull { EXISTS.find(it.text)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    }

    /** Appends [message] and returns its UID when the server says (UIDPLUS), else null. */
    fun append(mailbox: String, message: ByteArray): Long? {
        run(Arg.Raw("APPEND"), Arg.Str(ModifiedUtf7.encode(mailbox)), Arg.Raw("(\\Seen)"), Arg.Bytes(message))
        return APPENDUID.find(lastTagged)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** The tagged answer to the last command, for response codes like APPENDUID. */
    private var lastTagged: String = ""

    /** `UID FETCH <set> <items>`, returning the FETCH lines. */
    fun uidFetch(set: String, items: String): List<Reply> =
        run(Arg.Raw("UID FETCH $set $items")).filter { FETCH.containsMatchIn(it.text) }

    fun deleteUid(uid: Long) {
        run(Arg.Raw("UID STORE $uid +FLAGS.SILENT (\\Deleted)"))
        if ("UIDPLUS" in capabilities) {
            run(Arg.Raw("UID EXPUNGE $uid"))
        } else {
            // The folder is the app's own, so expunging everything flagged in
            // it only ever removes backups rotation already chose.
            run(Arg.Raw("EXPUNGE"))
        }
    }

    fun logout() {
        runCatching { run(Arg.Raw("LOGOUT")) }
    }

    /**
     * Sends one command and reads to its tagged answer. Every literal waits for
     * the server's `+` first, unless the server takes `LITERAL+`.
     */
    fun run(vararg args: Arg): List<Reply> {
        val me = "w${++tag}"
        output.write("$me ".toByteArray(Charsets.US_ASCII))
        val nonSync = "LITERAL+" in capabilities
        args.forEachIndexed { i, arg ->
            if (i > 0) output.write(' '.code)
            when (arg) {
                is Arg.Raw -> output.write(arg.text.toByteArray(Charsets.US_ASCII))
                is Arg.Str -> {
                    val quoted = quote(arg.value)
                    if (quoted != null) {
                        output.write(quoted.toByteArray(Charsets.US_ASCII))
                    } else {
                        literal(arg.value.toByteArray(Charsets.UTF_8), nonSync)
                    }
                }
                is Arg.Bytes -> literal(arg.value, nonSync)
            }
        }
        output.write(CRLF)
        output.flush()

        val untagged = ArrayList<Reply>()
        while (true) {
            val reply = readReply()
            val text = reply.text
            if (text.startsWith("$me ")) {
                val rest = text.substring(me.length + 1)
                val kind = rest.substringBefore(' ').uppercase()
                if (kind != "OK") throw ImapException(kind, rest)
                lastTagged = rest
                return untagged
            }
            if (text.startsWith("* BYE", ignoreCase = true) && args.firstOrNull().let { it !is Arg.Raw || it.text != "LOGOUT" }) {
                throw ImapException("BYE", text)
            }
            untagged += reply
        }
    }

    private fun literal(bytes: ByteArray, nonSync: Boolean) {
        // The line break after the marker is there either way; LITERAL+ only
        // skips waiting for the server's go-ahead.
        output.write("{${bytes.size}${if (nonSync) "+" else ""}}".toByteArray(Charsets.US_ASCII))
        output.write(CRLF)
        if (!nonSync) {
            output.flush()
            val go = readReply().text
            if (!go.startsWith("+")) throw ImapException("NO", go)
        }
        output.write(bytes)
    }

    /** One logical line, with each `{n}` literal's bytes read and kept aside. */
    fun readReply(): Reply {
        val text = StringBuilder()
        val literals = ArrayList<ByteArray>()
        while (true) {
            val line = readLine() ?: throw IOException("IMAP: connection closed")
            text.append(line)
            val m = LITERAL_AT_END.find(line) ?: break
            val n = m.groupValues[1].toInt()
            if (n > MAX_LITERAL) throw IOException("IMAP: literal of $n bytes")
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val k = input.read(bytes, read, n - read)
                if (k < 0) throw IOException("IMAP: connection closed in a literal")
                read += k
            }
            literals += bytes
        }
        return Reply(text.toString(), literals)
    }

    private fun readLine(): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            out.write(b)
            if (out.size() > MAX_LINE) throw IOException("IMAP: line too long")
        }
    }

    private fun parseCapabilities(line: String) {
        val m = CAPABILITY.find(line) ?: return
        capabilities = m.groupValues[1].trim().split(' ').map { it.uppercase() }.toSet()
    }

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val LITERAL_AT_END = Regex("\\{(\\d+)\\+?\\}$")
        private val CAPABILITY = Regex("CAPABILITY ([^\\]]+)", RegexOption.IGNORE_CASE)
        private val EXISTS = Regex("^\\* (\\d+) EXISTS", RegexOption.IGNORE_CASE)
        private val APPENDUID = Regex("\\[APPENDUID \\d+ (\\d+)]", RegexOption.IGNORE_CASE)
        const val KIND_AUTH = "AUTH"
        private val FETCH = Regex("^\\* \\d+ FETCH", RegexOption.IGNORE_CASE)
        private const val MAX_LITERAL = 64 * 1024 * 1024
        private const val MAX_LINE = 1024 * 1024

        /** A quoted string, or null when [s] needs a literal: a line break, a NUL, or anything past ASCII. */
        fun quote(s: String): String? {
            if (s.any { it == '\r' || it == '\n' || it == '\u0000' || it.code > 0x7E }) return null
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}

/**
 * Modified UTF-7 (RFC 3501 section 5.1.3), what IMAP folder names are written
 * in. Plain ASCII stays itself and `&` becomes `&-`; anything else goes into
 * `&...-` as base64 of UTF-16 with `,` for `/`.
 */
object ModifiedUtf7 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

    fun encode(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> {
                    out.append("&-")
                    i++
                }
                c.code in 0x20..0x7E -> {
                    out.append(c)
                    i++
                }
                else -> {
                    var j = i
                    while (j < s.length && s[j].code !in 0x20..0x7E) j++
                    val bytes = s.substring(i, j).toByteArray(Charsets.UTF_16BE)
                    out.append('&')
                    var buffer = 0
                    var bits = 0
                    for (b in bytes) {
                        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                        bits += 8
                        while (bits >= 6) {
                            bits -= 6
                            out.append(ALPHABET[(buffer shr bits) and 0x3F])
                        }
                    }
                    if (bits > 0) out.append(ALPHABET[(buffer shl (6 - bits)) and 0x3F])
                    out.append('-')
                    i = j
                }
            }
        }
        return out.toString()
    }
}
