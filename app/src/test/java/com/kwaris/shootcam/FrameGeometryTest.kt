package com.kwaris.shootcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGeometryTest {

    // Column-major 4x4 matrices as returned by SurfaceTexture.getTransformMatrix
    private val flipY = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private val rot90 = floatArrayOf(0f, -1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    @Test
    fun rawBufferUsesSensorPlusDeviceOrientation() {
        val (rot, aspect) = VideoPipeline.frameGeometry(flipY, 90, 270, 16f / 9f)
        assertEquals(0, rot)
        assertEquals(16f / 9f, aspect, 1e-4f)
        assertEquals(180, VideoPipeline.frameGeometry(flipY, 90, 90, 16f / 9f).first)
    }

    @Test
    fun alreadyRotatedBufferOnlyNeedsDeviceOrientation() {
        val (rot, aspect) = VideoPipeline.frameGeometry(rot90, 90, 270, 16f / 9f)
        assertEquals(270, rot)
        assertEquals(9f / 16f, aspect, 1e-4f)
    }

    @Test
    fun letterboxViewport() {
        assertArrayEquals(intArrayOf(120, 0, 1920, 1080), VideoPipeline.fitViewport(2160, 1080, 16f / 9f))
        assertArrayEquals(intArrayOf(0, 60, 1600, 900), VideoPipeline.fitViewport(1600, 1020, 16f / 9f))
    }
}
