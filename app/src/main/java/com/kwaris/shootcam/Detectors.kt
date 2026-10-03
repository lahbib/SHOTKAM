package com.kwaris.shootcam

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/*
 * Pure detection logic (no Android types) so it can be unit-tested on the JVM.
 * Timestamps are sensor timestamps in nanoseconds.
 */

/**
 * Recoil = short acceleration impulse once gravity is removed.
 * Gravity is tracked with a slow low-pass filter (tau = 0.3 s).
 */
class RecoilDetector(private val threshold: () -> Float) {
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var lastTs = 0L
    private var lastTrigger = Long.MIN_VALUE / 2
    private var initialised = false
    private var impulseStart = 0L

    /** Last computed magnitude (m/s²), for the live meter. */
    var magnitude = 0f
        private set

    fun gravity(): FloatArray = gravity

    /** Returns the impulse magnitude if this sample is a recoil, null otherwise. */
    fun onSample(x: Float, y: Float, z: Float, tNs: Long): Float? {
        if (!initialised) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            initialised = true
            lastTs = tNs
            return null
        }
        val dt = ((tNs - lastTs) / 1e9f).coerceIn(0.0005f, 0.1f)
        lastTs = tNs
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        // Don't let a short impulse drag the gravity estimate (but never freeze it > 80 ms,
        // otherwise flipping the phone over would lock it forever).
        if (magnitude > 15f) {
            if (impulseStart == 0L) impulseStart = tNs
        } else {
            impulseStart = 0L
        }
        val frozen = impulseStart != 0L && tNs - impulseStart < 80_000_000L
        val alpha = if (frozen) 1f else 0.3f / (0.3f + dt)
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        if (magnitude > threshold() && tNs - lastTrigger > REFRACTORY_NS) {
            lastTrigger = tNs
            return magnitude
        }
        return null
    }

    companion object {
        const val REFRACTORY_NS = 400_000_000L
    }
}

/**
 * Aiming = sudden rotation (>= [SWING_MIN_NS] above the rate threshold), then the
 * rifle settles (below [STILL_RATE] for [STILL_MIN_NS]) within [AIM_WINDOW_NS],
 * optionally with the barrel roughly level.
 */
class AimDetector(
    private val rate: () -> Float,
    private val requireLevel: () -> Boolean,
) {
    private var swingStart = 0L
    private var swingDone = 0L
    private var stillSince = 0L
    private var lastAim = Long.MIN_VALUE / 2

    fun onGyro(mag: Float, tNs: Long, elevationDeg: Float): Boolean {
        if (mag > rate()) {
            if (swingStart == 0L) swingStart = tNs
            if (tNs - swingStart >= SWING_MIN_NS) swingDone = tNs
            stillSince = 0L
            return false
        }
        swingStart = 0L
        if (swingDone == 0L) return false
        if (tNs - swingDone > AIM_WINDOW_NS) {
            swingDone = 0L; stillSince = 0L
            return false
        }
        if (mag >= STILL_RATE) {
            stillSince = 0L
            return false
        }
        if (stillSince == 0L) {
            stillSince = tNs
            return false
        }
        if (tNs - stillSince < STILL_MIN_NS) return false
        swingDone = 0L; stillSince = 0L
        if (requireLevel() && abs(elevationDeg) > MAX_ELEVATION) return false
        if (tNs - lastAim < 1_000_000_000L) return false
        lastAim = tNs
        return true
    }

    companion object {
        const val SWING_MIN_NS = 60_000_000L
        const val AIM_WINDOW_NS = 2_500_000_000L
        const val STILL_MIN_NS = 250_000_000L
        const val STILL_RATE = 0.6f
        const val MAX_ELEVATION = 35f
    }
}

object Attitude {
    private const val RAD = 57.29578f

    /** Continuous device roll angle, OrientationEventListener convention (0 = portrait, clockwise). */
    fun rollDeg(gx: Float, gy: Float): Float {
        var o = 90f - atan2(gy, -gx) * RAD
        while (o >= 360f) o -= 360f
        while (o < 0f) o += 360f
        return o
    }

    /** True when the screen is not facing up/down (roll angle meaningful). */
    fun rollValid(gx: Float, gy: Float, gz: Float): Boolean = 4 * (gx * gx + gy * gy) >= gz * gz

    /**
     * Quantises the roll to 0/90/180/270 with hysteresis: only switches once the
     * device is more than [margin] degrees past the 45° boundary.
     */
    fun quantise(roll: Float, current: Int, margin: Float = 15f): Int {
        val d = angleDiff(roll, current.toFloat())
        if (abs(d) <= 45f + margin) return current
        return (((roll + 45f) / 90f).toInt() * 90) % 360
    }

    /** Signed difference a - b in (-180, 180]. */
    fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Elevation of the back-camera axis (device -Z) above the horizon. */
    fun elevationDeg(gx: Float, gy: Float, gz: Float): Float {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-3f) return 0f
        return asin((-gz / n).coerceIn(-1f, 1f)) * RAD
    }

    /** Clockwise rotation to apply to the back-camera image so it is upright. */
    fun frameRotation(sensorOrientation: Int, deviceOrientation: Int): Int =
        (sensorOrientation + deviceOrientation) % 360

    /** Compass bearing of the camera axis from a device->world rotation matrix (row-major 3x3). */
    fun headingDeg(r: FloatArray): Float {
        val dx = -r[2]
        val dy = -r[5]
        if (dx * dx + dy * dy < 1e-4f) return Float.NaN // pointing straight up/down
        var h = atan2(dx, dy) * RAD
        if (h < 0) h += 360f
        return h
    }
}
