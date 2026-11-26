package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.sink.ProfileLink

/**
 * "Add to WM Keyboard clipboard" in the share sheet (#1556): shared text goes
 * into the keyboard's clipboard history, labelled as shared, and nowhere else.
 *
 * Its point is the other profile. The share sheet of the personal profile has
 * a tab for the work profile or the private space, and picking this there
 * starts the copy of the app in that profile, keyboard running or not, so one
 * chosen clip crosses over while nothing else does, and no app in that
 * profile sees it on the system clipboard. A clip's Send to other profile
 * opens the sheet on the keyboard's own targets for this.
 *
 * Reached only through the manifest's `ClipShareAlias`, off until Settings ›
 * Clipboard › History turns it on. No screen of its own: a toast says it
 * arrived. While the keyboard is not running the clip waits for it, see
 * [com.wasimaster.wmkeyboard.core.settings.sink.ProfileClipInbox].
 */
class ClipShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        finish()
        if (text.isEmpty()) return
        ProfileLink.deliverClip(applicationContext, text, shared = true)
        Toast.makeText(applicationContext, R.string.clip_share_added, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** The manifest's alias; never rename it, share targets are remembered by component name. */
        private fun alias(context: Context) = ComponentName(context, "com.wasimaster.wmkeyboard.app.ClipShareAlias")

        /** Whether "Add to WM Keyboard clipboard" is in the share sheet. Off unless turned on. */
        fun inShareSheet(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(alias(context)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        fun setInShareSheet(context: Context, on: Boolean) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    alias(context),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
