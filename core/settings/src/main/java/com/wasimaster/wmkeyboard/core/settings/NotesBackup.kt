package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.notes.NoteAttachments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Notes section of a backup (#560), as raw JSON: what leaves the phone
 * and what comes back.
 *
 * Pure JSON on purpose, like `SyncStatistics`: the file is the store's own
 * snapshot embedded verbatim, and this never decodes it into notes, so a
 * field a newer build added rides through untouched.
 */
object NotesBackup {

    private const val NOTES = "notes"

    /**
     * The key the notes' pictures ride under beside `notes` (#1586), name to
     * base64; see `SettingsRepository.withNoteAttachments`.
     */
    const val ATTACHMENTS = "attachments"
    private const val BODY = "body"
    private const val LOCKED = "locked"
    private const val DELETED_AT = "deletedAt"
    private const val ID = "id"

    /**
     * The file as it travels. Locked notes are dropped unless [includeSecrets]:
     * the user marked them as the ones not to be read, and a backup file is
     * the one place that would outlive every lock on the phone. The tags and
     * everything else pass through as they are.
     */
    fun portable(element: JsonElement, includeSecrets: Boolean): JsonElement {
        if (includeSecrets) return element
        val root = element as? JsonObject ?: return element
        val notes = root[NOTES] as? JsonArray ?: return element
        val kept = JsonArray(notes.filterNot { it.isLocked() })
        return JsonObject(root + (NOTES to kept))
    }

    /**
     * [incoming] with this phone's locked notes that it does not carry added
     * back. A bundle written without secrets left them out; restoring it, or
     * a sync pass made from it, must not read that absence as "delete them".
     * A locked note the bundle does carry (made with secrets) wins over the
     * local copy, as every other note does.
     */
    fun keepingLocalLocked(incoming: JsonObject, local: JsonObject?): JsonObject {
        val localNotes = local?.get(NOTES) as? JsonArray ?: return incoming
        val incomingNotes = incoming[NOTES] as? JsonArray ?: JsonArray(emptyList())
        val knownIds = incomingNotes.mapNotNullTo(HashSet()) { it.id() }
        val missingLocked = localNotes.filter { it.isLocked() && it.id()?.let { id -> id !in knownIds } == true }
        if (missingLocked.isEmpty()) return incoming
        return JsonObject(incoming + (NOTES to JsonArray(incomingNotes + missingLocked)))
    }

    /** The attachment names the notes in [root] show, trash included. */
    fun attachmentNames(root: JsonObject): Set<String> {
        val notes = root[NOTES] as? JsonArray ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (note in notes) {
            val body = runCatching { (note.jsonObject[BODY] as? JsonPrimitive)?.content }.getOrNull() ?: continue
            out += NoteAttachments.referenced(body)
        }
        return out
    }

    /** Notes outside the trash, for the import dialog's count line. */
    fun count(element: JsonElement): Int =
        (element as? JsonObject)?.get(NOTES)?.let { it as? JsonArray }
            ?.count { (it as? JsonObject)?.get(DELETED_AT) == null } ?: 0

    /** Whether the section carries a locked note, which the import warning treats as a secret. */
    fun hasLocked(element: JsonElement): Boolean =
        (element as? JsonObject)?.get(NOTES)?.let { it as? JsonArray }?.any { it.isLocked() } == true

    private fun JsonElement.isLocked(): Boolean =
        runCatching { jsonObject[LOCKED]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true

    private fun JsonElement.id(): Long? =
        runCatching { (jsonObject[ID] as? JsonPrimitive)?.content?.toLongOrNull() }.getOrNull()
}
