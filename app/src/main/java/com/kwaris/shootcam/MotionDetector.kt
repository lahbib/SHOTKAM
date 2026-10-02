package com.kwaris.shootcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * - Recul : pic d'accélération (hors gravité) au-dessus du seuil.
 * - Mise en joue : rotation brusque (gyroscope) suivie d'une stabilisation (visée).
 * - Orientation de l'appareil (pour la rotation de la vidéo).
 */
class MotionDetector(
    ctx: Context,
    private val cfg: () -> Config,
    private val listener: Listener,
) : SensorEventListener {

    interface Listener {
        fun onRecoil(magnitude: Float)
        fun onAim()
    }

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null

    private val gravity = floatArrayOf(0f, 0f, SensorManager.GRAVITY_EARTH)
    private var lastAccelTs = 0L
    private var lastRecoilTs = 0L

    // Machine à états "mise en joue"
    private var swingStart = 0L
    private var swingDone = 0L
    private var stillSince = 0L
    private var lastAimTs = 0L

    /** Orientation de l'appareil arrondie à 0/90/180/270 (convention OrientationEventListener). */
    @Volatile var deviceOrientation = 270
        private set

    // Crêtes pour l'écran de calibrage (lues puis remises à zéro par l'UI)
    @Volatile private var peakAccel = 0f
    @Volatile private var peakGyro = 0f

    val hasGyro: Boolean get() = gyro != null

    fun start() {
        val t = HandlerThread("ShootCamSensors").apply { start() }
        thread = t
        val h = Handler(t.looper)
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    fun takePeaks(): Pair<Float, Float> {
        val r = peakAccel to peakGyro
        peakAccel = 0f
        peakGyro = 0f
        return r
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            Sensor.TYPE_GYROSCOPE -> onGyro(e)
        }
    }

    private fun onAccel(e: SensorEvent) {
        val v = e.values
        // Passe-bas (tau = 0,3 s) pour isoler la gravité
        val dt = if (lastAccelTs == 0L) 0.01f else ((e.timestamp - lastAccelTs) / 1e9f).coerceIn(0.0005f, 0.1f)
        lastAccelTs = e.timestamp
        val alpha = 0.3f / (0.3f + dt)
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * v[i]

        val lx = v[0] - gravity[0]
        val ly = v[1] - gravity[1]
        val lz = v[2] - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        peakAccel = max(peakAccel, mag)

        if (mag > cfg().recoilThreshold && e.timestamp - lastRecoilTs > 300_000_000L) {
            lastRecoilTs = e.timestamp
            listener.onRecoil(mag)
        }
        updateOrientation()
    }

    private fun updateOrientation() {
        val x = -gravity[0]
        val y = -gravity[1]
        val z = -gravity[2]
        if (4 * (x * x + y * y) >= z * z) {
            var o = 90 - Math.toDegrees(atan2(-y, x).toDouble()).roundToInt()
            while (o >= 360) o -= 360
            while (o < 0) o += 360
            deviceOrientation = ((o + 45) / 90 * 90) % 360
        }
    }

    private fun onGyro(e: SensorEvent) {
        val v = e.values
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        peakGyro = max(peakGyro, mag)
        val t = e.timestamp
        val thr = cfg().aimRate

        if (mag > thr) {
            if (swingStart == 0L) swingStart = t
            if (t - swingStart >= SWING_MIN_NS) swingDone = t
            stillSince = 0L
            return
        }
        swingStart = 0L
        if (swingDone == 0L) return

        if (t - swingDone > AIM_WINDOW_NS) {
            swingDone = 0L; stillSince = 0L
        } else if (mag < STILL_RATE) {
            if (stillSince == 0L) stillSince = t
            else if (t - stillSince >= STILL_MIN_NS) {
                swingDone = 0L; stillSince = 0L
                if (t - lastAimTs > 1_000_000_000L) {
                    lastAimTs = t
                    listener.onAim()
                }
            }
        } else {
            stillSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val SWING_MIN_NS = 60_000_000L      // rotation brusque >= 60 ms
        private const val AIM_WINDOW_NS = 2_500_000_000L  // stabilisation dans les 2,5 s
        private const val STILL_MIN_NS = 250_000_000L     // stable pendant 250 ms
        private const val STILL_RATE = 0.6f               // rad/s
    }
}
