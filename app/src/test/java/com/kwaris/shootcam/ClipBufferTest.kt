package com.kwaris.shootcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipBufferTest {

    private fun video(tUs: Long, key: Boolean, ptsUs: Long = tUs) =
        EncodedSample(TRACK_VIDEO, ByteArray(10), ptsUs, if (key) FLAG_KEY_FRAME else 0, tUs)

    private fun audio(tUs: Long, ptsUs: Long = tUs) =
        EncodedSample(TRACK_AUDIO, ByteArray(4), ptsUs, 0, tUs)

    /** 30 fps video with a keyframe every second, from 0 to [seconds] s. */
    private fun filled(seconds: Int): ClipBuffer {
        val b = ClipBuffer()
        for (i in 0 until seconds * 30) {
            val t = i * 33_333L
            b.add(video(t, key = i % 30 == 0))
            b.add(audio(t + 10_000))
        }
        return b
    }

    @Test
    fun trimKeepsKeyframeBeforeCutoff() {
        val b = filled(10)
        b.trim(5_500_000)
        val s = b.snapshot(0, Long.MAX_VALUE)
        assertTrue(s.first().isKey)
        assertTrue(kotlin.math.abs(s.first().tUs - 5_000_000L) < 40_000)
    }

    @Test
    fun snapshotStartsOnKeyframeAndStopsAtEnd() {
        val b = filled(10)
        val s = b.snapshot(3_200_000, 6_000_000)
        assertTrue(s.first().isKey)
        assertTrue(s.first().tUs <= 3_200_000)
        assertTrue(s.last().tUs <= 6_000_000)
    }

    @Test
    fun snapshotBeforeBufferStartUsesFirstKeyframe() {
        val b = filled(3)
        val s = b.snapshot(-10_000_000, 1_000_000)
        assertEquals(0L, s.first().tUs)
    }

    @Test
    fun emptyBufferGivesEmptySnapshot() {
        assertTrue(ClipBuffer().snapshot(0, 1).isEmpty())
    }

    @Test
    fun durationAndSize() {
        val b = filled(4)
        assertEquals(4f, b.durationSeconds(), 0.1f)
        assertEquals(4 * 30 * 14L, b.sizeBytes())
        b.clear()
        assertEquals(0L, b.sizeBytes())
    }

    @Test
    fun timelineAlignsTracksWithDifferentTimebases() {
        // Video pts in a camera timebase far away from the Clock, audio pts close to it.
        val samples = mutableListOf<EncodedSample>()
        for (i in 0 until 60) {
            val t = 1_000_000L + i * 33_333L
            samples += video(t + 50_000, key = i % 30 == 0, ptsUs = 9_000_000_000L + i * 33_333L)
            samples += audio(t + 20_000, ptsUs = t)
        }
        val tl = ClipTimeline.build(samples)
        assertEquals(0L, tl.first().outUs)
        val firstAudio = tl.first { it.sample.track == TRACK_AUDIO }
        // Audio sample captured at the same moment as frame 0 lands near 0, not seconds away.
        assertTrue(firstAudio.outUs < 50_000)
        // Strictly increasing per track
        for (track in listOf(TRACK_VIDEO, TRACK_AUDIO)) {
            val times = tl.filter { it.sample.track == track }.map { it.outUs }
            assertEquals(times.sorted().distinct(), times)
        }
    }

    @Test
    fun timelineDropsSamplesBeforeFirstKeyframe() {
        val samples = listOf(video(0, false), audio(5), video(33_333, true), video(66_666, false))
        val tl = ClipTimeline.build(samples)
        assertTrue(tl.first().sample.isKey)
        assertEquals(2, tl.count { it.sample.track == TRACK_VIDEO })
    }
}
