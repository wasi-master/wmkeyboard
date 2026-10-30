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
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sync.SyncWatcher
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator

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
        // Search by meaning's own process runs the model and nothing else: no
        // sync, no alarms, no second copy of anything the keyboard's process
        // owns. It needs the log, the network log, and the gate that says
        // where the LiteRT interpreter is.
        // The sticker generator's process is the same kind: the model, its
        // runtime's gate, and the NPU runtime's.
        if (com.wasimaster.wmkeyboard.core.semantic.EmbeddingService.isServiceProcess() ||
            com.wasimaster.wmkeyboard.core.imagegen.ImageGenService.isServiceProcess()
        ) {
            DebugLog.setBuildTag(BuildConfig.FLAVOR)
            DebugLog.attach(this)
            NetLog.attach(this)
            com.wasimaster.wmkeyboard.core.net.DataSaverDownloadGate.attach(this)
            installOnDemandDelivery(this)
            return
        }
        // Which of the two flavours this is, said here because here is the
        // only place that knows. The log lives in :core:common and compiles
        // against :core:config, which has no flavours, so left to itself a
        // crash record cannot tell the `intl` build from the `en` one. Those are separate R8 runs with mappings that do
        // not interchange, and the retrace bot picks the mapping off this
        // line. Before attach, so the first crash of the process already
        // carries it.
        DebugLog.setBuildTag(BuildConfig.FLAVOR)
        DebugLog.attach(this)
        // The network activity log, before anything in this process can make
        // a request. Cheap: it reads two small files, or waits for the unlock.
        NetLog.attach(this)
        // Data saving's say over a download the connection changes under.
        com.wasimaster.wmkeyboard.core.net.DataSaverDownloadGate.attach(this)
        // Where a Khipro spec set newer than the bundled one is kept (#909);
        // loaded the first time Khipro types, not here.
        com.wasimaster.wmkeyboard.core.transliteration.KhiproSpecs.attach(this)
        // Lets the keyboard tell whether a screen of this app is up before it
        // recycles the process the two share. One callback registration.
        com.wasimaster.wmkeyboard.core.util.StartedActivities.install(this)
        // Interface language packs (the standard GitHub build, Android 11+):
        // loaded once here and added to every screen's resources as it is
        // created, before it inflates a string. A no-op on every other build,
        // which has no packs on disk.
        com.wasimaster.wmkeyboard.core.modules.LanguagePackResources.install(this)
        com.wasimaster.wmkeyboard.core.modules.LanguagePackResources.attach(resources)
        registerActivityLifecycleCallbacks(LanguagePackAttacher)
        // The phone's language (or the one picked) arrives by itself, and a
        // settings screen already open restarts in it.
        LanguagePackDownloads.fetchWanted(this) { LanguagePackAttacher.recreateStarted() }
        // The Play gates of the LiteRT interpreter (WhisperEngine,
        // LocalSubjectCutout), ML Kit's ink recogniser (HandwritingModels) and
        // the whisper.cpp, ExecuTorch, Whistle, Harper and Tesseract libraries.
        installOnDemandDelivery(this)
        // Builds without Harper used to switch the spell checker off, and that
        // state outlives the update; the merged checker answers everywhere.
        com.wasimaster.wmkeyboard.spell.SpellCheckerComponent.restore(this)
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
        // The Dropbox, OneDrive and Box client ids, which live in BuildConfig and
        // so cannot be read from the library module that needs them.
        BackupClients.install(
            dropbox = BuildConfig.DROPBOX_APP_KEY,
            oneDrive = BuildConfig.ONEDRIVE_CLIENT_ID,
            box = BuildConfig.BOX_CLIENT_ID,
            boxSecret = BuildConfig.BOX_CLIENT_SECRET,
        )
        // Whether the translate list can offer the on-device engines: ML Kit,
        // Bergamot and NLLB sit above the module that keeps the provider lists.
        NllbTranslator.checkMemory(this)
        ProviderEnvironment.install(
            onDeviceTranslate = OnDeviceTranslator.offered,
            googleHome = com.wasimaster.wmkeyboard.config.BuildConfig.ENABLE_GOOGLE_HOME,
            bergamot = BergamotTranslator.available,
            nllb = NllbTranslator.available,
        )
        // Where the Dictionary tool's offline dictionaries live, for a
        // look-up from the keyboard or the AI tool alike. Only a path: before
        // the unlock nothing is read, and a look-up then finds nothing.
        com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaries.attach(filesDir)
        // The same for the dictionaries the user imported (#765).
        com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries.attach(filesDir)
        // Where zinnia's and PaddleOCR's handwriting models are downloaded
        // to, for the keyboard's panel and the Handwriting settings alike.
        com.wasimaster.wmkeyboard.core.handwriting.ZinniaModels.attach(filesDir)
        com.wasimaster.wmkeyboard.core.handwriting.InkOcrModels.attach(filesDir)
        // Where offline Whisper keeps its GPU verdicts and the kernels the
        // driver compiled, before the keyboard can dictate. One small read.
        com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCppGpu.attach(this)
        // Sync between devices: keeps its jobs in step with the settings and
        // pushes shortly after a synced setting changes. Here because the
        // keyboard and the settings app share this process, and either may be
        // the one running. Does nothing while sync is off.
        com.wasimaster.wmkeyboard.core.layout.KeymanLayoutPack.attach(this)
        val repository = SettingsRepository(this)
        SyncWatcher.start(this, repository)
        // A Keyman layout that is on, however it got on, brings its grids down.
        KeymanLayoutPackWatcher.start(this, repository)
        // "Ask AI" in other apps' selection menus follows its switch and the
        // AI tool's, wherever either is changed from (#897).
        AskAiSelectionMenu.start(this, repository)
        // The same for "WM Keyboard" and "WM Translate" there (#916).
        TextActionsSelectionMenu.start(this, repository)
        // The clock tool's alarms and timers. Android drops every registered
        // wake-up at a reboot, and the app has no boot receiver on purpose:
        // the keyboard starts at boot anyway, and this puts them back then.
        // Free for anyone who never set one (one file-exists check), and off
        // the main thread for those who did.
        com.wasimaster.wmkeyboard.core.clock.ClockEngine.rearmOnStart(this)
        // The same for the reminders plugins set, for the same reason. Free
        // for anyone with no plugin that declares reminders.
        com.wasimaster.wmkeyboard.core.plugins.PluginReminderAlarms.rearmOnStart(this)
        // And the to-do list's (#995). Free for anyone with no list.
        com.wasimaster.wmkeyboard.core.todo.TodoReminders.rearmOnStart(this)
    }
}

/** Adds the language packs to each activity's resources before it creates anything. */
private object LanguagePackAttacher : android.app.Application.ActivityLifecycleCallbacks {
    private val started = java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.app.Activity, Boolean>())

    /** Restarts the screens on show, after a pack arrived in the background, so they draw in it. */
    fun recreateStarted() {
        started.toList().forEach { if (!it.isFinishing && !it.isDestroyed) it.recreate() }
    }

    override fun onActivityPreCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
        com.wasimaster.wmkeyboard.core.modules.LanguagePackResources.attach(activity.resources)
    }

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityStarted(activity: android.app.Activity) { started += activity }
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivityStopped(activity: android.app.Activity) { started -= activity }
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}
