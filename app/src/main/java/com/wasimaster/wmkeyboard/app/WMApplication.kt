package com.wasimaster.wmkeyboard.app

import android.app.Application
import android.content.Context
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.drive.installDriveAuth
import com.wasimaster.wmkeyboard.app.llm.installLlmDelivery
import com.wasimaster.wmkeyboard.app.translate.installTranslateDelivery
import com.wasimaster.wmkeyboard.app.llm.llmSplitCompat
import com.wasimaster.wmkeyboard.app.modules.installOnDemandDelivery
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sync.SyncWatcher

/**
 * Exists for one reason: to install the crash handler before anything else in
 * the process runs.
 *
 * [MainActivity] and `WMKeyboardService` both call [DebugLog.attach] on create,
 * which covers the keyboard and the settings app once they are up. What it
 * cannot cover is everything that runs *before* them — content-provider
 * initialisers (Compose, DataStore, the ML Kit init provider), static
 * initialisers, a library's own startup. A crash in that window is exactly the
 * kind that leaves an app that "does not open at all", and it was the one kind
 * the log never recorded. `Application.onCreate` is the first app code the
 * platform runs in any process, so the handler now covers all of it.
 *
 * Nothing else belongs here. Anything heavier would run on every process start
 * including the keyboard's, where startup latency is what the user feels as the
 * keyboard being slow to appear.
 */
open class WMApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Play builds only (no-op elsewhere): lets this process load the
        // on-demand AI-runtime split without a restart once it is installed.
        // Has to run this early — SplitCompat wires into the base context.
        llmSplitCompat(this)
    }

    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own process and must not get a handler:
        // a failure while showing a crash report would launch another crash
        // report, forever. See CrashReportActivity.
        if (DebugLog.isCrashProcess()) return
        // Which of the four flavours this is, said here because here is the
        // only place that knows. The log lives in :core:common and compiles
        // against :core:config, which carries `capabilities` alone, so left to
        // itself a crash record says `full` and cannot tell the `intl` build
        // from the `en` one. Those are separate R8 runs with mappings that do
        // not interchange, and the retrace bot picks the mapping off this
        // line. Before attach, so the first crash of the process already
        // carries it.
        DebugLog.setBuildTag(BuildConfig.FLAVOR)
        DebugLog.attach(this)
        // The network activity log, before anything in this process can make
        // a request. Cheap: it reads two small files, or waits for the unlock.
        NetLog.attach(this)
        // Publishes the Drive token provider, if this build has one at all, so
        // the backup job can find it. Here rather than in the activity or the
        // keyboard because either of those may be what started the process, and
        // the job runs in whichever one is alive. A no-op on a build without
        // Play services compiled in.
        installDriveAuth(this)
        // Points LocalLlmEngine at Play's module installer, if this build
        // delivers the AI runtime on demand at all. A no-op elsewhere: those
        // builds compile the runtime in, and the default gate already says so.
        installLlmDelivery(this)
        // The same for ML Kit's translator and OnDeviceTranslator.
        installTranslateDelivery(this)
        // And for the LiteRT interpreter (LocalSubjectCutout)
        // and ML Kit's ink recogniser (HandwritingModels).
        installOnDemandDelivery(this)
        // The Dropbox and OneDrive client ids, which live in BuildConfig and
        // so cannot be read from the library module that needs them.
        BackupClients.install(
            dropbox = BuildConfig.DROPBOX_APP_KEY,
            oneDrive = BuildConfig.ONEDRIVE_CLIENT_ID,
        )
        // Sync between devices: keeps its jobs in step with the settings and
        // pushes shortly after a synced setting changes. Here because the
        // keyboard and the settings app share this process, and either may be
        // the one running. Does nothing while sync is off.
        SyncWatcher.start(this, SettingsRepository(this))
    }
}
