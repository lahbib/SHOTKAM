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
 * Horloge commune caméra / audio / capteurs, en microsecondes.
 * Alignée sur la base de temps des images caméra (REALTIME ou MONOTONIC selon l'appareil).
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
 * Tampon circulaire d'images DÉJÀ encodées (H.264 + AAC).
 * On garde en permanence les N dernières secondes ; à la détection d'un tir on
 * extrait [tir - avant ; tir + après] sans ré-encodage.
 */
class ClipBuffer {
    private val samples = ArrayDeque<EncodedSample>()

    @Volatile var videoFormat: MediaFormat? = null
    @Volatile var audioFormat: MediaFormat? = null

    @Synchronized
    fun add(s: EncodedSample) {
        samples.addLast(s)
    }

    /** Supprime tout ce qui précède la dernière image clé <= keepFromUs. */
    @Synchronized
    fun trim(keepFromUs: Long) {
        val cut = keyIndexAtOrBefore(keepFromUs)
        if (cut > 0) repeat(cut) { samples.removeFirst() }
    }

    /** Copie des échantillons couvrant [fromUs ; toUs], en démarrant sur une image clé. */
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
