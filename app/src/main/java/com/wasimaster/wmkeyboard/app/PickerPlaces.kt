package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit

/**
 * Where each kind of file pick last went, so the system file picker opens
 * there again (#970).
 *
 * Android's picker remembers one place per app, so saving a backup to one
 * folder and importing a layout from another meant walking back across the
 * phone every time. Each [key] here is one purpose (backup export, layout
 * import, …) with its own remembered file, handed to the picker as
 * [DocumentsContract.EXTRA_INITIAL_URI] (Android 8 and up), which opens the
 * folder holding it. Only a hint: a provider that refuses it, or a file since
 * deleted, opens wherever the picker would have anyway.
 */
internal object PickerPlaces {
    const val BACKUP_EXPORT = "backup_export"
    const val BACKUP_IMPORT = "backup_import"
    const val BACKUP_FOREIGN = "backup_foreign"
    const val LAYOUT_EXPORT = "layout_export"
    const val LAYOUT_IMPORT = "layout_import"
    const val OFFLINE_IMPORT = "offline_import"
    const val DOWNLOADS_EXPORT = "downloads_export"
    const val DOWNLOADS_IMPORT = "downloads_import"
    const val BACKUP_FOLDER = "backup_folder"
    const val BLACKLIST_EXPORT = "blacklist_export"
    const val BLACKLIST_IMPORT = "blacklist_import"

    private const val PREFS = "picker_places"

    /** Remembers [uri] as where [key]'s picks go now. A cancelled pick (null) changes nothing. */
    fun remember(context: Context, key: String, uri: Uri?) {
        if (uri == null) return
        runCatching { prefs(context).edit { putString(key, uri.toString()) } }
    }

    /**
     * Where [key]'s picker should open: the folder of the file last picked
     * for it, or that file itself where its folder cannot be named.
     */
    fun initial(context: Context, key: String): Uri? =
        runCatching { prefs(context).getString(key, null) }.getOrNull()?.let(Uri::parse)?.let(::folderOf)

    /** Sets the picker in [intent] to open at [key]'s last place. */
    fun apply(context: Context, key: String, intent: Intent): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            initial(context, key)?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        return intent
    }

    /**
     * The folder holding [uri], as a document the picker can open (#1131).
     *
     * Handed a file, some pickers (Samsung's among them) ignore the hint and
     * open wherever the app's last pick of any kind went, which undid the whole
     * point of a place per purpose. A folder is what the hint is documented to
     * take. A folder pick's tree opens at its own top. A file on the phone's
     * storage names its folder in its document id ("primary:Download/a.json"
     * sits in "primary:Download"). Anything else (Downloads' numbered ids, a
     * cloud provider) has no folder to read off the id and stays as it was.
     */
    internal fun folderOf(uri: Uri): Uri = runCatching {
        val segments = uri.pathSegments
        val tree = segments.firstOrNull() == "tree"
        if (tree && segments.size == 2) {
            return@runCatching DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching uri
        val id = DocumentsContract.getDocumentId(uri)
        val volume = id.substringBefore(':', missingDelimiterValue = "")
        if (volume.isEmpty()) return@runCatching uri
        val parent = if ('/' in id) id.substringBeforeLast('/') else "$volume:"
        if (tree) {
            DocumentsContract.buildDocumentUriUsingTree(uri, parent)
        } else {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, parent)
        }
    }.getOrDefault(uri)

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** [ActivityResultContracts.CreateDocument] that opens at [key]'s last place; see [PickerPlaces]. */
internal class CreateDocumentAt(mimeType: String, private val key: String) :
    ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        PickerPlaces.apply(context, key, super.createIntent(context, input))
}

/** [ActivityResultContracts.OpenMultipleDocuments] that opens at [key]'s last place; see [PickerPlaces]. */
internal class OpenMultipleDocumentsAt(private val key: String) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        PickerPlaces.apply(context, key, super.createIntent(context, input))
}

/** [ActivityResultContracts.OpenDocument] that opens at [key]'s last place; see [PickerPlaces]. */
internal class OpenDocumentAt(private val key: String) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        PickerPlaces.apply(context, key, super.createIntent(context, input))
}
