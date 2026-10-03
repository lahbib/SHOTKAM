package com.kwaris.shootcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.kwaris.shootcam.gl.EglCore
import com.kwaris.shootcam.gl.FrameRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Back camera (Camera2) -> SurfaceTexture -> OpenGL (rotation + HUD overlay)
 *   -> H.264 encoder input surface -> ClipBuffer
 *   -> optional preview surface (exactly what is recorded).
 *
 * The camera feeds a single stream, so preview never changes the capture session.
 */
class VideoPipeline(
    private val ctx: Context,
    private val cfg: () -> Config,
    private val buffer: ClipBuffer,
    /** Device orientation 0/90/180/270 (OrientationEventListener convention). */
    private val deviceOrientation: () -> Int,
    private val errorSink: (String) -> Unit,
) {
    private val camThread = HandlerThread("ShootCamCamera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val glThread = HandlerThread("ShootCamGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val encThread = HandlerThread("ShootCamEncoder").apply { start() }
    private val encHandler = Handler(encThread.looper)

    // Camera thread
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var fpsRange: Range<Int>? = null
    private var stabilization = false
    private var activeArray: Rect? = null
    private var zoomRatioRange: Range<Float>? = null

    // GL thread
    private var egl: EglCore? = null
    private var renderer: FrameRenderer? = null
    private var encoderEgl: EGLSurface? = null
    private var previewEgl: EGLSurface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var overlay: OverlayRenderer? = null
    private val stMatrix = FloatArray(16)
    private var lastOverlayMs = 0L
    private var glFailed = false

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    @Volatile private var running = false

    private var sensorOrientation = 90

    var videoSize = Size(1920, 1080)
        private set
    var maxZoom = 1f
        private set

    @SuppressLint("MissingPermission")
    fun start(initialPreview: Surface?) {
        val c = cfg()
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val camId = CameraUtil.backCameraId(mgr) ?: throw IllegalStateException("Aucune caméra arrière")
        val ch = mgr.getCameraCharacteristics(camId)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val s = CameraUtil.chooseSize(map?.getOutputSizes(SurfaceTexture::class.java), c.height)
        videoSize = Size(s.width and 1.inv(), s.height and 1.inv())

        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        fpsRange = ranges.firstOrNull { it.lower == c.fps && it.upper == c.fps }
            ?: ranges.filter { it.upper == c.fps }.maxByOrNull { it.lower }
        stabilization = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (Build.VERSION.SDK_INT >= 30) zoomRatioRange = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        maxZoom = zoomRatioRange?.upper
            ?: ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        setupEncoder(c)
        running = true

        val ready = CountDownLatch(1)
        var glError: Throwable? = null
        glHandler.post {
            try {
                setupGl(initialPreview)
            } catch (t: Throwable) {
                glError = t
            }
            ready.countDown()
        }
        ready.await(3, TimeUnit.SECONDS)
        glError?.let { stop(); throw IllegalStateException("OpenGL : ${it.message}", it) }
        mgr.openCamera(camId, cameraCallback, camHandler)
    }

    private fun setupEncoder(c: Config) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, c.fps)
            // 1 keyframe per second: buffer cut precision = 1 s
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // No B-frames: decode order == presentation order, simpler muxing
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
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
                        buffer.add(EncodedSample(TRACK_VIDEO, bytes, info.presentationTimeUs, info.flags, Clock.nowUs()))
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "encoder output after stop", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (running) errorSink("Encodeur vidéo : ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                buffer.videoFormat = format
            }
        }, encHandler)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
    }

    // ------------------------------------------------------------------ GL thread

    private fun setupGl(preview: Surface?) {
        val e = EglCore()
        egl = e
        val encS = e.createWindowSurface(encoderSurface!!)
        encoderEgl = encS
        e.makeCurrent(encS)
        val r = FrameRenderer().also { it.init() }
        renderer = r
        overlay = OverlayRenderer(videoSize.width, videoSize.height)
        val st = SurfaceTexture(r.cameraTexture)
        st.setDefaultBufferSize(videoSize.width, videoSize.height)
        st.setOnFrameAvailableListener({ onFrame() }, glHandler)
        surfaceTexture = st
        cameraSurface = Surface(st)
        if (preview != null) attachPreview(preview)
    }

    private fun attachPreview(surface: Surface) {
        val e = egl ?: return
        try {
            if (surface.isValid) previewEgl = e.createWindowSurface(surface)
        } catch (t: Throwable) {
            Log.w(TAG, "preview surface", t)
            previewEgl = null
        }
    }

    private fun detachPreview() {
        val e = egl ?: return
        previewEgl?.let {
            encoderEgl?.let { enc -> e.makeCurrent(enc) }
            e.releaseSurface(it)
        }
        previewEgl = null
    }

    private fun onFrame() {
        if (!running || glFailed) return
        val e = egl ?: return
        val r = renderer ?: return
        val st = surfaceTexture ?: return
        val encS = encoderEgl ?: return
        try {
            e.makeCurrent(encS)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val ts = st.timestamp

            val now = System.currentTimeMillis()
            if (now - lastOverlayMs >= 200) {
                lastOverlayMs = now
                val showShot = Clock.nowUs() - Telemetry.lastShotUs < 2_500_000
                overlay?.render(cfg().overlay, showShot)?.let { r.setOverlay(it) }
            }

            val outAspect = videoSize.width.toFloat() / videoSize.height
            val (rot, srcAspect) = frameGeometry(stMatrix, sensorOrientation, deviceOrientation(), outAspect)
            r.draw(stMatrix, rot, srcAspect, outAspect, 0, 0, videoSize.width, videoSize.height)
            e.setPresentationTime(encS, ts)
            e.swap(encS)

            previewEgl?.let { p ->
                if (e.makeCurrent(p)) {
                    val (pw, ph) = e.surfaceSize(p)
                    val vp = fitViewport(pw, ph, outAspect)
                    r.clear()
                    r.draw(stMatrix, rot, srcAspect, outAspect, vp[0], vp[1], vp[2], vp[3])
                    if (!e.swap(p)) detachPreview()
                } else {
                    detachPreview()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame", t)
            glFailed = true
            errorSink("Rendu vidéo : ${t.message}")
        }
    }

    /** Attaches/detaches the preview. Blocks (max 1 s) so the surface can be released safely. */
    fun setPreviewSurface(surface: Surface?) {
        val latch = CountDownLatch(1)
        glHandler.post {
            detachPreview()
            if (surface != null) attachPreview(surface)
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ camera thread

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
        val target = cameraSurface ?: return
        try {
            cam.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null || !running) {
                        s.close(); return
                    }
                    session = s
                    applyRequest()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    errorSink("Configuration caméra impossible")
                }
            }, camHandler)
        } catch (e: Exception) {
            errorSink("Session caméra : ${e.message}")
        }
    }

    private fun applyRequest() {
        val cam = camera ?: return
        val s = session ?: return
        val target = cameraSurface ?: return
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(target)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (stabilization) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
                applyZoom(this, cfg().zoom)
            }
            s.setRepeatingRequest(req.build(), null, camHandler)
        } catch (e: Exception) {
            if (running) errorSink("Requête caméra : ${e.message}")
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, wanted: Float) {
        val z = wanted.coerceIn(1f, maxZoom)
        val zr = zoomRatioRange
        if (Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z.coerceIn(zr.lower, zr.upper))
        } else {
            val a = activeArray ?: return
            val w = (a.width() / z).toInt()
            val h = (a.height() / z).toInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
    }

    /** Re-applies settings that can change live (zoom). */
    fun refreshRequest() {
        camHandler.post { applyRequest() }
    }

    fun stop() {
        running = false
        val camDone = CountDownLatch(1)
        camHandler.post {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            session = null
            camera = null
            camDone.countDown()
        }
        camDone.await(2, TimeUnit.SECONDS)

        val glDone = CountDownLatch(1)
        glHandler.post {
            try {
                detachPreview()
                encoderEgl?.let { egl?.releaseSurface(it) }
                encoderEgl = null
                renderer?.release()
                renderer = null
                overlay?.release()
                overlay = null
                cameraSurface?.release()
                cameraSurface = null
                surfaceTexture?.release()
                surfaceTexture = null
                egl?.release()
                egl = null
            } catch (t: Throwable) {
                Log.w(TAG, "gl release", t)
            }
            glDone.countDown()
        }
        glDone.await(2, TimeUnit.SECONDS)

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderSurface?.release()
        encoderSurface = null

        camThread.quitSafely()
        glThread.quitSafely()
        encThread.quitSafely()
    }

    companion object {
        private const val TAG = "VideoPipeline"

        /**
         * Rotation still to apply and aspect of the camera image as sampled through [st].
         * Depending on the device, the camera service may already rotate SurfaceTexture
         * buffers by the sensor orientation (the transform then swaps x/y); detect it
         * from the matrix instead of assuming it.
         */
        fun frameGeometry(st: FloatArray, sensorOrientation: Int, deviceOrientation: Int, bufferAspect: Float): Pair<Int, Float> {
            val swapsAxes = abs(st[0]) < 0.5f && abs(st[1]) > 0.5f
            return if (swapsAxes) {
                deviceOrientation % 360 to 1f / bufferAspect
            } else {
                Attitude.frameRotation(sensorOrientation, deviceOrientation) to bufferAspect
            }
        }

        /** Letterboxed viewport [x, y, w, h] of aspect [aspect] inside a w x h surface. */
        fun fitViewport(w: Int, h: Int, aspect: Float): IntArray {
            return if (w.toFloat() / h > aspect) {
                val vw = (h * aspect).toInt()
                intArrayOf((w - vw) / 2, 0, vw, h)
            } else {
                val vh = (w / aspect).toInt()
                intArrayOf(0, (h - vh) / 2, w, vh)
            }
        }
    }
}
