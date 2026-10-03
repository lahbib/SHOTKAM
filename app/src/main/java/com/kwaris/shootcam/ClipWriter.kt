package com.kwaris.shootcam

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes an MP4 to Movies/ShootCam from the encoded samples (no re-encoding). */
object ClipWriter {
    private const val TAG = "ClipWriter"
    const val RELATIVE_DIR = "Movies/ShootCam"

    fun write(
        ctx: Context,
        samples: List<EncodedSample>,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
        shots: Int,
        location: Location?,
        shotWallTimeMs: Long,
    ): Uri? {
        val timeline = ClipTimeline.build(samples)
        if (timeline.isEmpty()) return null
        val hasAudio = audioFormat != null && timeline.any { it.sample.track == TRACK_AUDIO }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date(shotWallTimeMs))
        val name = "ShootCam_${stamp}_${shots}tir${if (shots > 1) "s" else ""}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShootCam")
            put(MediaStore.Video.Media.DATE_TAKEN, shotWallTimeMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: return null

        try {
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                // Frames are already rotated upright by the GL pipeline.
                location?.let { muxer.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
                val vTrack = muxer.addTrack(videoFormat)
                val aTrack = if (hasAudio) muxer.addTrack(audioFormat!!) else -1
                muxer.start()
                val info = MediaCodec.BufferInfo()
                for (e in timeline) {
                    val s = e.sample
                    val track = if (s.track == TRACK_VIDEO) vTrack else aTrack
                    if (track < 0) continue
                    val flags = if (s.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, s.data.size, e.outUs, flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
                }
                muxer.stop()
                muxer.release()
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "écriture clip", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}
