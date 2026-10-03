package com.kwaris.shootcam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Microphone -> AAC -> ClipBuffer. Also computes the peak level of each block
 * (0..1) for acoustic gunshot detection.
 */
class AudioPipeline(
    private val ctx: Context,
    private val buffer: ClipBuffer,
    private val onPeak: (Float) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val am = ctx.getSystemService(AudioManager::class.java)
        // UNPROCESSED = no AGC, so the gunshot keeps its true peak level.
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.CAMCORDER
        val rec = AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 4, 16384)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Micro indisponible")
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        running = true
        thread = Thread({ loop(rec, enc) }, "ShootCamAudio").apply { start() }
    }

    private fun loop(rec: AudioRecord, enc: MediaCodec) {
        val pcm = ShortArray(1024)
        val info = MediaCodec.BufferInfo()
        var startUs = -1L
        var total = 0L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (startUs < 0) startUs = Clock.nowUs() - n * 1_000_000L / RATE
                var peak = 0
                for (i in 0 until n) {
                    val v = abs(pcm[i].toInt())
                    if (v > peak) peak = v
                }
                onPeak(peak / 32768f)

                val pts = startUs + total * 1_000_000L / RATE
                total += n
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
                    enc.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                }
                drain(enc, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
        }
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> buffer.audioFormat = enc.outputFormat
                idx >= 0 -> {
                    val out = enc.getOutputBuffer(idx)
                    if (out != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        out.get(bytes)
                        buffer.add(EncodedSample(TRACK_AUDIO, bytes, info.presentationTimeUs, 0, Clock.nowUs()))
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    companion object {
        private const val TAG = "AudioPipeline"
        const val RATE = 48_000
    }
}
