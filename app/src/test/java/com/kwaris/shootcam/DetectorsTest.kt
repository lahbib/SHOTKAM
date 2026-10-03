package com.kwaris.shootcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorsTest {

    private val ms = 1_000_000L

    @Test
    fun recoilImpulseTriggersOnce() {
        val d = RecoilDetector { 35f }
        var t = 0L
        repeat(400) { assertNull(d.onSample(0f, 0f, 9.81f, t)); t += 2 * ms }
        assertNotNull(d.onSample(60f, 0f, 9.81f, t)); t += 2 * ms
        // Ringing right after the shot stays inside the refractory window
        assertNull(d.onSample(-50f, 0f, 9.81f, t)); t += 2 * ms
        assertNull(d.onSample(55f, 0f, 9.81f, t))
    }

    @Test
    fun slowlyTurningThePhoneIsNotARecoil() {
        val d = RecoilDetector { 35f }
        var t = 0L
        for (i in 0..1000) {
            val a = Math.toRadians(i * 0.18).toFloat() // 180° over 2 s
            assertNull(d.onSample(9.81f * kotlin.math.sin(a), 0f, 9.81f * kotlin.math.cos(a), t))
            t += 2 * ms
        }
    }

    @Test
    fun gravityRecoversAfterFlip() {
        val d = RecoilDetector { 35f }
        var t = 0L
        repeat(200) { d.onSample(0f, 0f, 9.81f, t); t += 2 * ms }
        repeat(2000) { d.onSample(0f, 0f, -9.81f, t); t += 2 * ms }
        assertEquals(-9.81f, d.gravity()[2], 0.5f)
    }

    private fun aim(level: Boolean = true) = AimDetector({ 2.5f }, { level })

    private fun feed(d: AimDetector, rate: Float, durMs: Long, t0: Long, elev: Float = 0f): Pair<Boolean, Long> {
        var t = t0
        var hit = false
        while (t < t0 + durMs * ms) {
            if (d.onGyro(rate, t, elev)) hit = true
            t += 10 * ms
        }
        return hit to t
    }

    @Test
    fun swingThenStillIsAnAim() {
        val d = aim()
        val (_, t1) = feed(d, 0.1f, 500, 0)
        val (a, t2) = feed(d, 4f, 150, t1)
        assertFalse(a)
        val (b, _) = feed(d, 0.2f, 400, t2)
        assertTrue(b)
    }

    @Test
    fun swingWithoutSettlingIsNotAnAim() {
        val d = aim()
        val (_, t1) = feed(d, 4f, 150, 0)
        val (b, _) = feed(d, 1.5f, 3000, t1)
        assertFalse(b)
    }

    @Test
    fun tooShortJerkIsIgnored() {
        val d = aim()
        val (_, t1) = feed(d, 4f, 30, 0)
        val (b, _) = feed(d, 0.2f, 400, t1)
        assertFalse(b)
    }

    @Test
    fun barrelPointingDownIsRejectedWhenLevelRequired() {
        val d = aim(level = true)
        val (_, t1) = feed(d, 4f, 150, 0, elev = -70f)
        val (b, _) = feed(d, 0.2f, 400, t1, elev = -70f)
        assertFalse(b)

        val d2 = aim(level = false)
        val (_, t2) = feed(d2, 4f, 150, 0, elev = -70f)
        val (c, _) = feed(d2, 0.2f, 400, t2, elev = -70f)
        assertTrue(c)
    }
}
