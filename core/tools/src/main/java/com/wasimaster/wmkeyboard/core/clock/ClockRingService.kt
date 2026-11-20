package com.wasimaster.wmkeyboard.core.clock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Starts the ringing, by the service when Android allows, by a loud notification when not. */
object ClockRinger {

    fun start(context: Context) {
        val app = context.applicationContext
        if (!ChannelFeatures.CLOCK_RING_SERVICE) {
            // The Play build has no ring service at all; see ChannelFeatures.
            ClockNotifications.postFallbackRing(app, ClockStore.get(app).data.ringing)
            return
        }
        val ok = runCatching {
            ContextCompat.startForegroundService(app, Intent(app, ClockRingService::class.java))
        }.isSuccess
        if (!ok) {
            // Android 12+ refuses a foreground service started from the
            // background unless the app holds the exact-alarm grant; the
            // notification channel then has to make the noise.
            DebugLog.w("Clock", "ring service refused; ringing by notification")
            ClockNotifications.postFallbackRing(app, ClockStore.get(app).data.ringing)
        }
    }
}

/**
 * Rings: plays the sound, vibrates, keeps the ring notification up, and stops
 * the moment nothing is left ringing.
 *
 * The one piece of this app that runs as a foreground service, and only while
 * something rings: an alarm whose sound depends on the process staying alive
 * would fall silent the first time Android tidied memory at 6 a.m. It gives up
 * by itself after the auto-silence time, logging the ring as missed, so a
 * phone left at home does not ring all day.
 */
class ClockRingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var fadeStep = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = ClockStore.get(this)
        val ringing = store.data.ringing
        val notification = ClockNotifications.ringNotification(this, ringing, loud = false)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationIds.CLOCK_RING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(NotificationIds.CLOCK_RING, notification)
            }
        }
        if (foreground.isFailure) {
            DebugLog.w("Clock", "startForeground refused: ${foreground.exceptionOrNull()?.javaClass?.simpleName}")
            ClockNotifications.postFallbackRing(this, ringing)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ringing.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            begin(ringing)
            scope.launch {
                store.state.map { it.ringing }.distinctUntilChanged().collect { now ->
                    if (now.isEmpty()) {
                        stopSelf()
                    } else {
                        runCatching {
                            androidx.core.app.NotificationManagerCompat.from(this@ClockRingService)
                                .notify(NotificationIds.CLOCK_RING, ClockNotifications.ringNotification(this@ClockRingService, now, loud = false))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(ringing: List<Ringing>) {
        val config = ClockPrefs.get(this)
        val data = ClockStore.get(this).data
        val first = ringing.first()
        val alarm = if (first.kind == RingKind.ALARM) data.alarm(first.ref) else null
        val sound = when (first.kind) {
            RingKind.ALARM -> alarm?.sound ?: config.alarmSound
            RingKind.TIMER -> config.timerSound ?: config.alarmSound
        }
        // The tool's switch is the master one (#1448): an alarm's own is on
        // unless turned off in its editor, so one made from the keyboard or by
        // the AI vibrated with the tool's switch off.
        val vibrate = config.vibrate && alarm?.vibrate != false
        val fade = alarm?.fadeInSeconds?.takeIf { it >= 0 } ?: config.fadeInSeconds
        // Timers do not creep up: someone waiting for the pasta wants it now.
        val fadeSeconds = if (first.kind == RingKind.TIMER) 0 else fade

        val silenceMs = config.autoSilenceMinutes.coerceAtLeast(1) * 60_000L
        wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmkeyboard:clock-ring")
            ?.apply { runCatching { acquire(silenceMs + 60_000L) } }

        if (sound != "") play(sound, fadeSeconds)
        if (vibrate) vibrate()
        handler.postDelayed({ ClockEngine.unansweredAll(this) }, silenceMs)
    }

    private fun play(sound: String?, fadeSeconds: Int) {
        val candidates = listOfNotNull(
            sound?.let { runCatching { Uri.parse(it) }.getOrNull() },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val p = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(this@ClockRingService, uri)
                    isLooping = true
                    prepare()
                }
            }.getOrNull() ?: continue
            player = p
            if (fadeSeconds > 0) {
                p.setVolume(MIN_VOLUME, MIN_VOLUME)
                fadeStep = 0
                val steps = fadeSeconds * 1000 / FADE_TICK_MS
                handler.post(object : Runnable {
                    override fun run() {
                        val pl = player ?: return
                        fadeStep++
                        val v = (MIN_VOLUME + (1f - MIN_VOLUME) * fadeStep / steps).coerceAtMost(1f)
                        runCatching { pl.setVolume(v, v) }
                        if (v < 1f) handler.postDelayed(this, FADE_TICK_MS.toLong())
                    }
                })
            }
            runCatching { p.start() }
            return
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(VIBRATION, 0), attrs)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATION, 0, attrs)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        ClockNotifications.cancelRing(this)
        super.onDestroy()
    }

    companion object {
        /** On 0.6 s, off 0.4 s, from the start. */
        val VIBRATION = longArrayOf(0, 600, 400)

        private const val MIN_VOLUME = 0.05f
        private const val FADE_TICK_MS = 250
    }
}
