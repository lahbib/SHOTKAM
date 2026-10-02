package com.kwaris.shootcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Back camera (Camera2) -> H.264 encoder (MediaCodec, input surface) -> ClipBuffer.
 * An optional preview can be attached/detached at runtime (the session is recreated).
 */
class VideoPipeline(
    private val ctx: Context,
    private val cfg: Config,
    private val buffer: ClipBuffer,
    private val errorSink: (String) -> Unit,
) {
    private val thread = HandlerThread("ShootCamVideo").apply { start() }
    private val handler = Handler(thread.looper)

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var fpsRange: Range<Int>? = null
    private var stabilization = false

    @Volatile private var running = false

    var sensorOrientation = 90
        private set
    var videoSize = Size(1920, 1080)
        private set

    @SuppressLint("MissingPermission")
    fun start(initialPreview: Surface?) {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val camId = CameraUtil.backCameraId(mgr) ?: throw IllegalStateException("Aucune caméra arrière")
        val ch = mgr.getCameraCharacteristics(camId)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        videoSize = CameraUtil.chooseSize(map?.getOutputSizes(MediaCodec::class.java), cfg.height)

        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        fpsRange = ranges.firstOrNull { it.lower == cfg.fps && it.upper == cfg.fps }
            ?: ranges.filter { it.upper == cfg.fps }.maxByOrNull { it.lower }
        val stabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        stabilization = stabModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

        setupEncoder()
        previewSurface = initialPreview
        running = true
        mgr.openCamera(camId, cameraCallback, handler)
    }

    private fun setupEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            // 1 keyframe per second: buffer cut precision = 1 s
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        out.get(bytes)
                        buffer.add(EncodedSample(TRACK_VIDEO, bytes, info.presentationTimeUs, info.flags))
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "encoder output after stop", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                errorSink("Encodeur vidéo : ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                buffer.videoFormat = format
            }
        }, handler)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (!running) {
                device.close(); return
            }
            camera = device
            createSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            camera = null
            if (running) errorSink("Caméra déconnectée (utilisée par une autre appli ?)")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            camera = null
            if (running) errorSink("Erreur caméra ($error)")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val cam = camera ?: return
        val enc = inputSurface ?: return
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        val targets = listOfNotNull(enc, previewSurface?.takeIf { it.isValid })
        try {
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null || !running) {
                        s.close(); return
                    }
                    session = s
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            targets.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            if (stabilization) {
                                set(
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                )
                            }
                        }
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (e: Exception) {
                        errorSink("Requête caméra : ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (previewSurface != null) {
                        // Retry without preview: recording takes priority.
                        previewSurface = null
                        createSession()
                    } else {
                        errorSink("Configuration caméra impossible")
                    }
                }
            }, handler)
        } catch (e: CameraAccessException) {
            errorSink("Accès caméra : ${e.message}")
        } catch (e: IllegalArgumentException) {
            if (previewSurface != null) {
                previewSurface = null
                createSession()
            } else errorSink("Session caméra : ${e.message}")
        }
    }

    /** Attaches/detaches the preview. Blocks (max 1 s) so the surface can be released safely. */
    fun setPreviewSurface(surface: Surface?) {
        val latch = CountDownLatch(1)
        handler.post {
            if (previewSurface !== surface) {
                previewSurface = surface
                if (camera != null) createSession()
            }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        val latch = CountDownLatch(1)
        handler.post {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            session = null
            camera = null
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            encoder = null
            inputSurface?.release()
            inputSurface = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "VideoPipeline"
    }
}
