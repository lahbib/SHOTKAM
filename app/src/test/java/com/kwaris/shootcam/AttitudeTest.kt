package com.kwaris.shootcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttitudeTest {

    @Test
    fun rollMatchesOrientationEventListenerConvention() {
        assertEquals(0f, Attitude.rollDeg(0f, 9.81f), 0.5f)    // portrait
        assertEquals(270f, Attitude.rollDeg(9.81f, 0f), 0.5f)  // landscape, top to the left
        assertEquals(90f, Attitude.rollDeg(-9.81f, 0f), 0.5f)  // landscape, top to the right
        assertEquals(180f, Attitude.rollDeg(0f, -9.81f), 0.5f) // upside down
    }

    @Test
    fun quantiseHasHysteresis() {
        assertEquals(270, Attitude.quantise(310f, 270)) // 40° away: stays
        assertEquals(270, Attitude.quantise(325f, 270)) // 55° away: still inside the margin
        assertEquals(0, Attitude.quantise(340f, 270))   // 70° away: switches
        assertEquals(90, Attitude.quantise(100f, 270))
    }

    @Test
    fun angleDiffWrapsAround() {
        assertEquals(20f, Attitude.angleDiff(10f, 350f), 0.01f)
        assertEquals(-20f, Attitude.angleDiff(350f, 10f), 0.01f)
    }

    @Test
    fun elevation() {
        assertEquals(0f, Attitude.elevationDeg(9.81f, 0f, 0f), 0.5f)    // camera horizontal
        assertEquals(90f, Attitude.elevationDeg(0f, 0f, -9.81f), 0.5f)  // camera pointing up (screen down)
        assertEquals(-90f, Attitude.elevationDeg(0f, 0f, 9.81f), 0.5f)  // camera pointing down
    }

    @Test
    fun frameRotationForLandscapeMount() {
        // Typical back sensor (90°), phone in landscape with its top to the left.
        assertEquals(0, Attitude.frameRotation(90, 270))
        assertEquals(180, Attitude.frameRotation(90, 90))
    }

    @Test
    fun headingFromRotationMatrix() {
        // Phone upright in portrait facing north: device -Z points north.
        // device X = east, Y = up, Z = south  ->  rows of R map device axes to world (E, N, Up).
        val facingNorth = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertEquals(0f, Attitude.headingDeg(facingNorth), 0.5f)
        // Rotate 90° to face east: device -Z points east, device Z = west.
        val facingEast = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f,
        )
        assertEquals(90f, Attitude.headingDeg(facingEast), 0.5f)
        // Pointing straight up: undefined
        val up = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertTrue(Attitude.headingDeg(up).isNaN())
    }
}
