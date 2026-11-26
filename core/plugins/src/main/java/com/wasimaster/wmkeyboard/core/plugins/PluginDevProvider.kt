package com.wasimaster.wmkeyboard.core.plugins

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * The way in for `wmpy push`, `wmpy dev` and `wmpy logs`: a computer
 * connected over adb installs and updates plugins, and reads their logs,
 * without the confirmation screen.
 *
 * ```
 * adb shell content write --uri content://<package>.plugindev/install/<token> < plugin.wmplugin
 * adb shell content call --uri content://<package>.plugindev --method result --arg <token>
 * adb shell content call --uri content://<package>.plugindev --method status
 * adb shell content call --uri content://<package>.plugindev --method reload --arg <id>
 * adb shell content call --uri content://<package>.plugindev --method log --arg <id>
 * ```
 *
 * `content write` (Android 9 and later) streams the archive into the pipe
 * [openFile] returns and prints nothing, so `result` waits for the pipe to
 * close and answers what the install did. Android 7 and 8 have no `content
 * write`: `wmpy` sends the archive as base64 pieces, `--method part --arg
 * <token>:<base64>`, then asks for `result` the same way.
 *
 * ## Who gets in
 *
 * Every entry point asks [PluginDevGate]: the calling uid must be the adb
 * shell's (2000) or root's (0), **and** plugin developer mode must be on.
 * Anything else is a SecurityException. The manifest also puts the provider
 * behind `android.permission.DUMP`, which the shell holds and an app from a
 * store cannot (it is signature, privileged or development only), so another
 * app on the phone is refused by the system before it reaches this code.
 *
 * ## What an install may do
 *
 * The same as the confirmation screen's Install: [PluginFile.import], so the
 * archive is read and checked by the same rules and adopted by
 * [PluginStore.adopt]. An update keeps the grants the user gave the version
 * it replaces, and a risky permission never comes on here: one the user has
 * not granted stays off, and the reply names it. Plugins must be allowed
 * (the master switch) as for any install.
 *
 * Not added to the toolbox on a first install, unlike the confirmation
 * screen: that list lives in the app's settings, which this module cannot
 * reach. The plugin is in the Plugins tool's list either way.
 */
class PluginDevProvider : ContentProvider() {

    private val uploads = PluginDevUploads()

    override fun onCreate(): Boolean {
        // At process start, on the main thread: remember the context, read nothing.
        context?.let(PluginDevMode::attach)
        return true
    }

    /** Throws unless the caller is adb (or root) and developer mode is on. */
    private fun gate() {
        val context = context ?: throw SecurityException(PluginDevGate.refusal(PluginDevGate.Verdict.NOT_ADB))
        val uid = Binder.getCallingUid()
        // The uid first, before the preferences are read: an app learns
        // nothing, not even whether the mode is on.
        if (!PluginDevGate.isAdb(uid)) throw SecurityException(PluginDevGate.refusal(PluginDevGate.Verdict.NOT_ADB))
        val verdict = PluginDevGate.check(uid, PluginDevMode.isOn(context))
        if (verdict != PluginDevGate.Verdict.ALLOWED) throw SecurityException(PluginDevGate.refusal(verdict))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        gate()
        val segments = uri.pathSegments
        // `content write` asks for "w"; "wt" and the like are writes too.
        if (segments.size != 2 || segments[0] != INSTALL || !mode.startsWith("w")) {
            throw FileNotFoundException("only install/<token> opens, for writing")
        }
        val upload = uploads.begin(segments[1], viaPipe = true)
            ?: throw FileNotFoundException("the token is 8 to 32 lowercase letters and digits")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        // A reliable pipe, so a `content` process that dies part way is an
        // error here rather than an archive cut short. Caught: a throw on
        // this thread would take the keyboard's process with it.
        val drain = Runnable { runCatching { uploads.fromPipe(upload, ParcelFileDescriptor.AutoCloseInputStream(read)) } }
        Thread(drain, "wm-plugin-dev-upload").apply {
            isDaemon = true
            start()
        }
        return write
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        gate()
        val context = requireNotNull(context)
        val store = PluginStore.get(context)
        val json = when (method) {
            METHOD_STATUS -> status(store)
            METHOD_PART -> arg?.let(uploads::part)?.let { size ->
                if (size < 0) tooLarge() else PluginDevReply.part(size)
            } ?: PluginDevReply.error("bad_part", "a piece is <token>:<base64>")
            METHOD_RESULT -> arg?.let { install(store, it) } ?: PluginDevReply.error("no_token", "name the upload's token")
            METHOD_RELOAD -> withPlugin(store, arg) { id -> PluginDevReply.reloaded(id, store.announceUpdate(id)) }
            METHOD_LOG -> withPlugin(store, arg) { id ->
                PluginDevReply.log(id, PluginLog(store.logFile(id)).also { it.restore() }.lines())
            }
            else -> PluginDevReply.error("unknown_method", "no method '$method'")
        }
        // Never a null result: Android 7's `content call` reads the reply's size before printing it.
        return Bundle().apply { putString(PluginDevReply.KEY, json) }
    }

    private fun status(store: PluginStore): String {
        val context = requireNotNull(context)
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return PluginDevReply.status(
            developerMode = true,
            appVersion = info?.versionName.orEmpty(),
            appVersionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            pluginsOn = store.subsystemEnabled(),
            allowRisky = store.allowRisky(),
            plugins = store.plugins(),
        )
    }

    private fun withPlugin(store: PluginStore, arg: String?, body: (String) -> String): String {
        val id = arg?.trim().orEmpty()
        if (store.plugin(id) == null) return PluginDevReply.error("not_installed", "no plugin '$id' is installed")
        return body(id)
    }

    private fun tooLarge(): String = PluginDevReply.error(
        "too_large",
        "the archive is over ${PluginDevUploads.MAX_BYTES / 1024} KB",
    )

    /** Installs the archive [token] names, the way the confirmation screen's Install does. */
    private fun install(store: PluginStore, token: String): String {
        val context = requireNotNull(context)
        if (!store.subsystemEnabled()) {
            return PluginDevReply.error("plugins_off", "Allow plugins is off: turn it on in Tools / Plugins / Manage plugins")
        }
        val bytes = when (val taken = uploads.take(token)) {
            is PluginDevUploads.Taken.Ok -> taken.bytes
            PluginDevUploads.Taken.Unknown -> return PluginDevReply.error("unknown_token", "no upload '$token'")
            PluginDevUploads.Taken.StillArriving -> return PluginDevReply.error("timeout", "the archive did not finish arriving")
            PluginDevUploads.Taken.TooLarge -> return tooLarge()
            PluginDevUploads.Taken.Empty -> return PluginDevReply.error("empty", "the archive was empty")
            PluginDevUploads.Taken.Broken -> return PluginDevReply.error("broken", "the archive stopped part way: send it again")
        }
        val result = runCatching { PluginFile.import(ByteArrayInputStream(bytes), store) }.getOrDefault(PluginImportResult.Failed)
        return when (result) {
            is PluginImportResult.Imported ->
                PluginDevReply.installed(
                    store.plugin(result.plugin.id) ?: result.plugin,
                    result.replaced,
                    result.reloaded,
                    store.allowRisky(),
                )
            is PluginImportResult.Rejected -> PluginDevReply.error("rejected", result.reasonText.resolve(context))
            // These read on a computer, in wmpy's own English, like the rest of its output.
            PluginImportResult.NotAPlugin -> PluginDevReply.error("not_a_plugin", "the archive has no plugin.json the keyboard can read")
            PluginImportResult.TooManyPlugins -> PluginDevReply.error(
                "too_many",
                "${PluginStore.MAX_PLUGINS} plugins are installed, the most there can be: uninstall one first",
            )
            PluginImportResult.Failed -> PluginDevReply.error(
                "failed",
                "the keyboard could not read the archive or write the plugin (is the phone unlocked?)",
            )
        }
    }

    // Nothing here is a table. Each still asks the gate, so a caller that is
    // not adb hears the same refusal from every door.

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        gate()
        return null
    }

    /** Asked by the system as much as by callers, and answers nothing, so it needs no gate. */
    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        gate()
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        gate()
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        gate()
        return 0
    }

    companion object {
        /** `<applicationId>` + this, as the manifest declares it. */
        const val AUTHORITY_SUFFIX = ".plugindev"
        const val INSTALL = "install"
        const val METHOD_STATUS = "status"
        const val METHOD_PART = "part"
        const val METHOD_RESULT = "result"
        const val METHOD_RELOAD = "reload"
        const val METHOD_LOG = "log"
    }
}
