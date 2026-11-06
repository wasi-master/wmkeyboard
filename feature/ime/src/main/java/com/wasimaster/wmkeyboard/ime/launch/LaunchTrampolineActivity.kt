package com.wasimaster.wmkeyboard.ime.launch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * The source activity a split-screen start needs. From Android 13 QPR1 the
 * system drops `FLAG_ACTIVITY_LAUNCH_ADJACENT` from any start with no source
 * activity behind it (ActivityStarter, 13.0.0_r16 on: "missing source
 * activity"), and a keyboard is a service, so its own split launch opens full
 * screen everywhere. This invisible activity makes the two starts itself and
 * closes. The keyboard may start it while the user is in another app: the
 * current input method is exempt from the background-start rules.
 *
 * Orders ([Order]): the sources disagree on which one lands two apps side by
 * side, so both exist and calibration keeps the one that works here.
 */
class LaunchTrampolineActivity : Activity() {

    enum class Order {
        /** New app adjacent first, then the other one into the main half (AOSP reading). */
        ADJACENT_FIRST,

        /** The other app first, then the new one adjacent (Split Screen 1.3.8). */
        PLAIN_FIRST,
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val extras = intent.extras
        val target = extras?.parcelable<Intent>(EXTRA_TARGET)
        if (target == null) {
            finish()
            return
        }
        val other = extras.parcelable<Intent>(EXTRA_OTHER)
        val order = extras.getString(EXTRA_ORDER)?.let { n -> Order.entries.firstOrNull { it.name == n } }
            ?: Order.ADJACENT_FIRST
        val gap = extras.getLong(EXTRA_GAP_MS, DEFAULT_GAP_MS)
        val adjacent = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        if (extras.getBoolean(EXTRA_SAME_APP)) adjacent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val plain = other?.let { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        when {
            // No launch intent for the app behind the keyboard (a system
            // screen, a search bar): step aside so that app is the top task
            // the split pulls in, then start the new one beside it.
            plain == null -> {
                moveTaskToBack(true)
                handler.postDelayed({ start(adjacent) }, gap)
            }
            order == Order.ADJACENT_FIRST -> {
                start(adjacent)
                handler.postDelayed({ start(plain) }, gap)
            }
            else -> {
                start(plain)
                handler.postDelayed({ start(adjacent) }, gap)
            }
        }
        handler.postDelayed(::finish, gap + FINISH_AFTER_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_OTHER = "other"
        private const val EXTRA_ORDER = "order"
        private const val EXTRA_SAME_APP = "same_app"
        private const val EXTRA_GAP_MS = "gap_ms"
        private const val DEFAULT_GAP_MS = 350L
        private const val FINISH_AFTER_MS = 400L

        /**
         * Starts [target] beside [other] (the app the user was in, or a pair's
         * first app). [other] null puts it beside whatever is on screen.
         */
        fun start(context: Context, target: Intent, other: Intent?, order: Order, gapMs: Long = DEFAULT_GAP_MS) {
            val samePackage = other != null && packageOf(target) != null && packageOf(target) == packageOf(other)
            val intent = Intent(context, LaunchTrampolineActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_OTHER, other)
                .putExtra(EXTRA_ORDER, order.name)
                .putExtra(EXTRA_SAME_APP, samePackage)
                .putExtra(EXTRA_GAP_MS, gapMs)
            context.startActivity(intent)
        }

        private fun packageOf(intent: Intent): String? = intent.component?.packageName ?: intent.`package`

        @Suppress("DEPRECATION")
        private inline fun <reified T : android.os.Parcelable> Bundle.parcelable(key: String): T? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(key, T::class.java) else getParcelable(key)
    }
}
