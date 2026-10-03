package com.kwaris.shootcam

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Android sensor adapter: feeds [RecoilDetector] / [AimDetector] and keeps
 * device orientation, elevation, cant and heading up to date in [Telemetry].
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
    private val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var thread: HandlerThread? = null

    private val recoil = RecoilDetector { cfg().recoilThreshold }
    private val aim = AimDetector({ cfg().aimRate }, { cfg().aimRequireLevel })
    private val rot = FloatArray(9)
    private var declination = 0f
    private var declinationAt = 0L

    /** Device orientation 0/90/180/270 (OrientationEventListener convention), with hysteresis. */
    @Volatile var deviceOrientation = 270
        private set

    @Volatile private var peakAccel = 0f
    @Volatile private var peakGyro = 0f

    val hasGyro: Boolean get() = gyro != null

    fun start() {
        val t = HandlerThread("ShootCamSensors").apply { start() }
        thread = t
        val h = Handler(t.looper)
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        rotVec?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, h) }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    /** Peaks since the last call (accel m/s², gyro rad/s), for the live meters. */
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
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(e)
        }
    }

    private fun onAccel(e: SensorEvent) {
        val v = e.values
        recoil.onSample(v[0], v[1], v[2], e.timestamp)?.let { listener.onRecoil(it) }
        peakAccel = max(peakAccel, recoil.magnitude)

        val g = recoil.gravity()
        if (Attitude.rollValid(g[0], g[1], g[2])) {
            val roll = Attitude.rollDeg(g[0], g[1])
            deviceOrientation = Attitude.quantise(roll, deviceOrientation)
            Telemetry.cantDeg = Attitude.angleDiff(roll, deviceOrientation.toFloat())
        }
        Telemetry.elevationDeg = Attitude.elevationDeg(g[0], g[1], g[2])
    }

    private fun onGyro(e: SensorEvent) {
        val v = e.values
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        peakGyro = max(peakGyro, mag)
        if (aim.onGyro(mag, e.timestamp, Telemetry.elevationDeg)) listener.onAim()
    }

    private fun onRotation(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val h = Attitude.headingDeg(rot)
        if (h.isNaN()) {
            Telemetry.headingDeg = Float.NaN
            return
        }
        // Magnetic -> true north when the position is known (refreshed every minute).
        val loc = Telemetry.location
        val now = System.currentTimeMillis()
        if (loc != null && now - declinationAt > 60_000) {
            declinationAt = now
            declination = GeomagneticField(
                loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), now
            ).declination
        }
        Telemetry.headingDeg = ((h + declination) % 360f + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
