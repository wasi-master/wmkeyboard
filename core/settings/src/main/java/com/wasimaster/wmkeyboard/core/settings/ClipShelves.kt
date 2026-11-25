package com.wasimaster.wmkeyboard.core.settings

/**
 * One of the user's named clipboards (#1542): a place of its own for clips,
 * beside the main clipboard every copy lands in. [id] is what a clip's
 * `shelf` names, so a rename never touches the clips; [name] is what the
 * panel's switcher shows.
 */
data class NamedClipboard(val id: String, val name: String)

/** Most named clipboards one may have; the switcher is a menu, not a list to scroll for long. */
const val NAMED_CLIPBOARDS_MAX = 30

/** Longest name a clipboard may have. */
const val NAMED_CLIPBOARD_NAME_MAX = 40

/** [list] as stored: one clipboard a line, its id, a tab, its name. */
internal fun encodeNamedClipboards(list: List<NamedClipboard>): String =
    list.filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .joinToString("\n") { "${it.id}\t${cleanClipboardName(it.name)}" }

/** What [encodeNamedClipboards] wrote, lines that are not one dropped. */
internal fun decodeNamedClipboards(raw: String): List<NamedClipboard> =
    raw.split('\n').mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val id = line.substring(0, tab).trim()
        val name = cleanClipboardName(line.substring(tab + 1))
        if (id.isEmpty() || name.isEmpty()) null else NamedClipboard(id, name)
    }.distinctBy { it.id }.take(NAMED_CLIPBOARDS_MAX)

/** A name as kept: one line, no tabs, trimmed and cut to [NAMED_CLIPBOARD_NAME_MAX]. */
fun cleanClipboardName(name: String): String =
    name.replace('\n', ' ').replace('\t', ' ').trim().take(NAMED_CLIPBOARD_NAME_MAX).trim()

/** A new clipboard's id: short, and never "" (the main clipboard's). */
internal fun newClipboardId(taken: Collection<String>): String {
    while (true) {
        val id = "c" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        if (id !in taken) return id
    }
}
