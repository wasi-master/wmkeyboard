package com.wasimaster.wmkeyboard.app

import android.os.Build
import android.os.UserManager
import androidx.work.Configuration
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit

/**
 * The application of every build with ML Kit (all but F-Droid's):
 * [WMApplication], plus WorkManager started on
 * demand instead of at every process start.
 *
 * Nothing in this app schedules work; WorkManager is here only because ML
 * Kit's ink recogniser downloads its models through it. Its androidx.startup
 * initializer still ran in every process, the keyboard's included, opening
 * WorkManager's database machinery before the first key could be drawn. The
 * src/google manifest removes that initializer, and this provider is what
 * `WorkManager.getInstance` falls back to instead, so the first ink model
 * download starts it and nothing before then pays for it.
 *
 * ML Kit is initialised here too, in place of its own init provider, which the
 * src/google manifest removes: a provider skipped on the lock screen is
 * installed after unlock, and if anything here had initialised ML Kit first the
 * provider threw and crashed the process. One initialiser, idempotent, run now
 * when storage is up and by the keyboard's unlock handling otherwise.
 */
class WMFullApplication : WMApplication(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        // The main process only, where the provider used to run: the crash
        // screen's and the model services' processes have no use for ML Kit.
        val main = Build.VERSION.SDK_INT < Build.VERSION_CODES.P || getProcessName() == packageName
        if (main && getSystemService(UserManager::class.java)?.isUserUnlocked != false) MlKitInit.ensure(this)
    }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()
}
