package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * A shake of the phone, for the keyboard's shake action (#1429).
 *
 * Two jolts above [SHAKE_G] within [SHAKE_WINDOW_MS] make a shake, the same
 * reading the settings app's shake-to-send-feedback uses, so one shake means
 * the same thing in both places. After a shake the next is ignored for
 * [SHAKE_COOLDOWN_MS], so a phone still settling in the hand runs the action
 * once.
 *
 * The accelerometer is only listened to between [setListening] true and false,
 * which the service ties to the keyboard being on screen with an action set:
 * a sensor kept running behind a hidden keyboard costs battery for a shake
 * nobody can see the answer to.
 */
internal class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var registered = false
    private var lastJolt = 0L
    private var lastShake = 0L

    /** Starts or stops listening; repeating the current state does nothing. */
    fun setListening(on: Boolean) {
        if (on == registered) return
        if (on) {
            val sensor = accel ?: return
            registered = sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
        } else {
            sensors?.unregisterListener(this)
            registered = false
            lastJolt = 0L
        }
        KeyboardControls.setShakeClaimed(registered)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < SHAKE_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShake < SHAKE_COOLDOWN_MS) return
        if (now - lastJolt <= SHAKE_WINDOW_MS) {
            lastShake = now
            lastJolt = 0L
            onShake()
        } else {
            lastJolt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Above this many g, a jolt counts towards a shake. */
        const val SHAKE_G = 2.6f

        /** Two jolts this close together make a shake. */
        const val SHAKE_WINDOW_MS = 700L

        /** After a shake, the next is ignored for this long. */
        const val SHAKE_COOLDOWN_MS = 1_500L
    }
}
