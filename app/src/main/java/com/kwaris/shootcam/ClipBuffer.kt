package com.kwaris.shootcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock

const val TRACK_VIDEO = 0
const val TRACK_AUDIO = 1

/**
 * Shared camera / audio / sensor clock, in microseconds.
 * Aligned with the camera frame timebase (REALTIME or MONOTONIC depending on the device).
 */
object Clock {
    @Volatile
    var realtime = true

    fun init(ctx: Context) {
        try {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            val id = CameraUtil.backCameraId(mgr) ?: return
            val src = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            realtime = src == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        } catch (_: Exception) {
        }
    }

    fun nowUs(): Long =
        if (realtime) SystemClock.elapsedRealtimeNanos() / 1000 else System.nanoTime() / 1000
}

class EncodedSample(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int) {
    val isKey: Boolean get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
}

/**
 * Ring buffer of ALREADY encoded samples (H.264 + AAC).
 * Always keeps the last N seconds; when a shot is detected,
 * [shot - before ; shot + after] is extracted without re-encoding.
 */
class ClipBuffer {
    private val samples = ArrayDeque<EncodedSample>()

    @Volatile var videoFormat: MediaFormat? = null
    @Volatile var audioFormat: MediaFormat? = null

    @Synchronized
    fun add(s: EncodedSample) {
        samples.addLast(s)
    }

    /** Drops everything before the last keyframe <= keepFromUs. */
    @Synchronized
    fun trim(keepFromUs: Long) {
        val cut = keyIndexAtOrBefore(keepFromUs)
        if (cut > 0) repeat(cut) { samples.removeFirst() }
    }

    /** Copies the samples covering [fromUs ; toUs], starting on a keyframe. */
    @Synchronized
    fun snapshot(fromUs: Long, toUs: Long): List<EncodedSample> {
        var start = keyIndexAtOrBefore(fromUs)
        if (start < 0) start = samples.indexOfFirst { it.track == TRACK_VIDEO && it.isKey }
        if (start < 0) return emptyList()
        val out = ArrayList<EncodedSample>(samples.size - start)
        for (i in start until samples.size) {
            val s = samples[i]
            if (s.ptsUs <= toUs) out.add(s)
        }
        return out
    }

    @Synchronized
    fun durationSeconds(): Float {
        val first = samples.firstOrNull { it.track == TRACK_VIDEO } ?: return 0f
        val last = samples.lastOrNull { it.track == TRACK_VIDEO } ?: return 0f
        return (last.ptsUs - first.ptsUs) / 1_000_000f
    }

    @Synchronized
    fun sizeBytes(): Long = samples.sumOf { it.data.size.toLong() }

    @Synchronized
    fun clear() {
        samples.clear()
        videoFormat = null
        audioFormat = null
    }

    private fun keyIndexAtOrBefore(us: Long): Int {
        var idx = -1
        for ((i, s) in samples.withIndex()) {
            if (s.track != TRACK_VIDEO) continue
            if (s.ptsUs > us) break
            if (s.isKey) idx = i
        }
        return idx
    }
}
