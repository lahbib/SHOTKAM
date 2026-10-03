package com.kwaris.shootcam

import android.media.MediaFormat
import android.os.SystemClock

const val TRACK_VIDEO = 0
const val TRACK_AUDIO = 1

/** MediaCodec.BUFFER_FLAG_KEY_FRAME, duplicated so the buffer logic stays JVM-testable. */
const val FLAG_KEY_FRAME = 1

/**
 * Monotonic app clock in microseconds (includes deep sleep).
 * Every sample is stamped with this clock when it leaves its encoder, so trimming
 * and clip selection never depend on the camera / audio timebases.
 */
object Clock {
    fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
}

/**
 * One encoded access unit.
 * @param ptsUs presentation time in the encoder's own timebase (used for muxing).
 * @param tUs   [Clock] time at which the sample was produced (used for selection).
 */
class EncodedSample(
    val track: Int,
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int,
    val tUs: Long,
) {
    val isKey: Boolean get() = track == TRACK_VIDEO && flags and FLAG_KEY_FRAME != 0
}

/**
 * Ring buffer of ALREADY encoded samples (H.264 + AAC).
 * Always keeps the last N seconds; when a shot is detected,
 * [shot - before ; shot + after] is extracted without re-encoding.
 */
class ClipBuffer {
    private val samples = ArrayDeque<EncodedSample>()
    private var bytes = 0L

    @Volatile var videoFormat: MediaFormat? = null
    @Volatile var audioFormat: MediaFormat? = null

    @Synchronized
    fun add(s: EncodedSample) {
        samples.addLast(s)
        bytes += s.data.size
    }

    /** Drops everything before the last keyframe produced at or before [keepFromUs]. */
    @Synchronized
    fun trim(keepFromUs: Long) {
        val cut = keyIndexAtOrBefore(keepFromUs)
        if (cut > 0) repeat(cut) { bytes -= samples.removeFirst().data.size }
    }

    /** Samples covering [fromUs ; toUs] (Clock time), starting on a keyframe. */
    @Synchronized
    fun snapshot(fromUs: Long, toUs: Long): List<EncodedSample> {
        var start = keyIndexAtOrBefore(fromUs)
        if (start < 0) start = samples.indexOfFirst { it.isKey }
        if (start < 0) return emptyList()
        val out = ArrayList<EncodedSample>(samples.size - start)
        for (i in start until samples.size) {
            val s = samples[i]
            if (s.tUs <= toUs) out.add(s)
        }
        return out
    }

    /** Seconds of video currently held. */
    @Synchronized
    fun durationSeconds(): Float {
        val first = samples.firstOrNull { it.track == TRACK_VIDEO } ?: return 0f
        val last = samples.lastOrNull { it.track == TRACK_VIDEO } ?: return 0f
        return (last.tUs - first.tUs) / 1_000_000f
    }

    @Synchronized
    fun sizeBytes(): Long = bytes

    @Synchronized
    fun clear() {
        samples.clear()
        bytes = 0
        videoFormat = null
        audioFormat = null
    }

    private fun keyIndexAtOrBefore(us: Long): Int {
        var idx = -1
        for ((i, s) in samples.withIndex()) {
            if (s.track != TRACK_VIDEO) continue
            if (s.tUs > us) break
            if (s.isKey) idx = i
        }
        return idx
    }
}

/**
 * Maps every sample of a clip onto one output timeline starting at 0.
 * Video and audio come from different timebases: each track's offset to the
 * Clock is estimated as the smallest (tUs - ptsUs) seen, i.e. the lowest latency.
 */
object ClipTimeline {
    class Entry(val sample: EncodedSample, val outUs: Long)

    fun build(samples: List<EncodedSample>): List<Entry> {
        val firstKey = samples.indexOfFirst { it.isKey }
        if (firstKey < 0) return emptyList()
        val offset = LongArray(2) { Long.MAX_VALUE }
        for (s in samples) offset[s.track] = minOf(offset[s.track], s.tUs - s.ptsUs)
        val key = samples[firstKey]
        val t0 = key.ptsUs + offset[TRACK_VIDEO]
        val out = ArrayList<Entry>(samples.size)
        val last = longArrayOf(-1, -1)
        for (i in firstKey until samples.size) {
            val s = samples[i]
            val t = s.ptsUs + offset[s.track] - t0
            if (t < 0) continue
            if (t <= last[s.track]) continue // muxer needs strictly increasing time per track
            last[s.track] = t
            out.add(Entry(s, t))
        }
        return out
    }
}
