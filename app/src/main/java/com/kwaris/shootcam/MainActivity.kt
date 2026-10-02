package com.kwaris.shootcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.max

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var status: TextView
    private lateinit var meters: TextView
    private lateinit var btnStart: Button
    private lateinit var btnSave: Button
    private lateinit var btnLast: Button

    private var surface: Surface? = null
    private var previewSize = Size(1920, 1080)
    private val ui = Handler(Looper.getMainLooper())
    private var accelShown = 0f
    private var gyroShown = 0f
    private var audioShown = 0f
    private var lastRotation = -1

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCamera()) startService() else
            Toast.makeText(this, "La caméra est indispensable", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textureView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        meters = findViewById(R.id.meters)
        btnStart = findViewById(R.id.btn_start)
        btnSave = findViewById(R.id.btn_save)
        btnLast = findViewById(R.id.btn_last)

        btnStart.setOnClickListener {
            if (StatusBus.running) ShootCamService.send(this, ShootCamService.ACTION_STOP) else requestAndStart()
        }
        btnSave.setOnClickListener { ShootCamService.send(this, ShootCamService.ACTION_SAVE) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnLast.setOnClickListener {
            StatusBus.lastClip?.let { uri ->
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
        textureView.surfaceTextureListener = textureListener
    }

    override fun onStart() {
        super.onStart()
        val cfg = Config.load(this)
        if (cfg.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (textureView.isAvailable) attachPreview(textureView.surfaceTexture!!, textureView.width, textureView.height)
        ui.post(refresh)
    }

    override fun onStop() {
        ui.removeCallbacks(refresh)
        detachPreview()
        super.onStop()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        ShootCamService.send(this, ShootCamService.ACTION_START)
    }

    // ------------------------------------------------------------ aperçu

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attachPreview(st, w, h)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = configureTransform(w, h)
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            detachPreview(); return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun attachPreview(st: SurfaceTexture, w: Int, h: Int) {
        if (surface != null) return
        val cfg = Config.load(this)
        val mgr = getSystemService(CameraManager::class.java)
        // Aperçu plafonné à 1080p (combinaison de flux garantie avec l'enregistrement)
        previewSize = CameraUtil.sizeFor(mgr, SurfaceTexture::class.java, minOf(cfg.height, 1080))
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(w, h)
        val s = Surface(st)
        surface = s
        ShootCamService.previewSurface = s
        ShootCamService.instance?.onPreviewChanged()
    }

    private fun detachPreview() {
        val s = surface ?: return
        ShootCamService.previewSurface = null
        ShootCamService.instance?.onPreviewChanged()
        s.release()
        surface = null
    }

    private fun configureTransform(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val rotation = windowManager.defaultDisplay.rotation
        lastRotation = rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewH.toFloat() / previewSize.height, viewW.toFloat() / previewSize.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    // ------------------------------------------------------------ rafraîchissement UI

    private val refresh = object : Runnable {
        override fun run() {
            val running = StatusBus.running
            // Paysage 90° <-> 270° ne recrée pas l'activité : on recale l'aperçu.
            if (surface != null && windowManager.defaultDisplay.rotation != lastRotation) {
                configureTransform(textureView.width, textureView.height)
            }
            btnStart.text = if (running) "■ Arrêter" else "▶ Démarrer"
            btnSave.isEnabled = running
            btnLast.isEnabled = StatusBus.lastClip != null

            status.text = buildString {
                append(StatusBus.state)
                if (StatusBus.bufferMb > 0) append("  (${"%.0f".format(StatusBus.bufferMb)} Mo)")
                append("\nClips : ${StatusBus.clipsSaved}")
                if (StatusBus.lastEvent.isNotEmpty()) append("   •   ${StatusBus.lastEvent}")
            }

            val (a, g) = ShootCamService.instance?.takeSensorPeaks() ?: (0f to 0f)
            accelShown = max(a, accelShown * 0.85f)
            gyroShown = max(g, gyroShown * 0.85f)
            audioShown = max(StatusBus.audioPeak, audioShown * 0.85f)
            StatusBus.audioPeak = 0f
            val cfg = Config.load(this@MainActivity)
            meters.text = if (running) {
                "Accél ${"%.0f".format(accelShown)}/${cfg.recoilThreshold.toInt()} m/s²   " +
                    "Gyro ${"%.1f".format(gyroShown)}/${cfg.aimRate} rad/s   " +
                    "Son ${(audioShown * 100).toInt()}/${(cfg.audioThreshold * 100).toInt()} %"
            } else ""
            ui.postDelayed(this, 200)
        }
    }
}
