package com.kwaris.shootcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var empty: LinearLayout
    private lateinit var stateView: TextView
    private lateinit var hint: TextView
    private lateinit var bufferBar: ProgressBar
    private lateinit var eventView: TextView
    private lateinit var meters: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var alignBanner: LinearLayout
    private lateinit var btnArm: TextView
    private lateinit var btnSave: MaterialButton

    private var surface: Surface? = null
    private val ui = Handler(Looper.getMainLooper())
    private var accelShown = 0f
    private var gyroShown = 0f
    private var audioShown = 0f
    private var aligning = false
    private var lastZoomShownMs = 0L
    private lateinit var cfg: Config

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.CAMERA)) {
            ShootCamService.send(this, ShootCamService.ACTION_START)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Caméra refusée")
                .setMessage("ShootCam ne peut pas filmer sans l'accès à la caméra. Autorise-la dans les paramètres Android de l'appli.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep the controls clear of the camera cutout in landscape.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val c = insets.displayCutout
            v.setPadding(c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0, c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
            insets
        }

        textureView = findViewById(R.id.preview)
        empty = findViewById(R.id.empty)
        stateView = findViewById(R.id.state)
        hint = findViewById(R.id.hint)
        bufferBar = findViewById(R.id.buffer_bar)
        eventView = findViewById(R.id.event)
        meters = findViewById(R.id.meters)
        zoomLabel = findViewById(R.id.zoom_label)
        alignBanner = findViewById(R.id.align_banner)
        btnArm = findViewById(R.id.btn_arm)
        btnSave = findViewById(R.id.btn_save)
        cfg = Config.load(this)

        findViewById<TextView>(R.id.empty_steps).text = steps()

        btnArm.setOnClickListener {
            if (StatusBus.running) ShootCamService.send(this, ShootCamService.ACTION_STOP) else requestAndStart()
        }
        btnSave.setOnClickListener { ShootCamService.send(this, ShootCamService.ACTION_SAVE) }
        findViewById<View>(R.id.btn_clips).setOnClickListener { startActivity(Intent(this, ClipsActivity::class.java)) }
        findViewById<View>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btn_align).setOnClickListener { setAligning(!aligning) }
        findViewById<View>(R.id.btn_align_done).setOnClickListener { setAligning(false) }
        findViewById<View>(R.id.btn_align_center).setOnClickListener { saveDot(0.5f, 0.5f) }

        textureView.surfaceTextureListener = textureListener
        setupGestures()

        if (!Config.prefs(this).getBoolean(Config.K_ONBOARDED, false)) showOnboarding()
    }

    override fun onStart() {
        super.onStart()
        cfg = Config.load(this)
        if (cfg.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (textureView.isAvailable) attachPreview(textureView.surfaceTexture!!)
        ui.post(refresh)
    }

    override fun onStop() {
        ui.removeCallbacks(refresh)
        detachPreview()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (aligning) {
            setAligning(false)
            return
        }
        if (StatusBus.running) {
            Toast.makeText(this, "ShootCam reste armé en arrière-plan (notification)", Toast.LENGTH_LONG).show()
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (StatusBus.running && cfg.volumeKeySave &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event?.repeatCount == 0) {
                ShootCamService.send(this, ShootCamService.ACTION_SAVE)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ start / permissions

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { !granted(it) }
        if (missing.isEmpty()) ShootCamService.send(this, ShootCamService.ACTION_START)
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun showOnboarding() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Bienvenue dans ShootCam")
            .setMessage(
                steps() + "\n\nLes autorisations demandées :\n" +
                    "• Caméra et micro : filmer et entendre le tir\n" +
                    "• Position : coordonnées GPS et météo incrustées\n" +
                    "• Notifications : garder ShootCam actif écran éteint"
            )
            .setPositiveButton("Compris") { _, _ ->
                Config.prefs(this).edit().putBoolean(Config.K_ONBOARDED, true).apply()
            }
            .setNeutralButton("Calibrer d'abord") { _, _ ->
                Config.prefs(this).edit().putBoolean(Config.K_ONBOARDED, true).apply()
                startActivity(Intent(this, CalibrationActivity::class.java))
            }
            .setCancelable(false)
            .show()
    }

    private fun steps(): String {
        val c = Config.load(this)
        return "1. Fixe le téléphone sur le fusil, objectif vers la cible.\n" +
            "2. Appuie sur ARMER : la vidéo tourne en boucle.\n" +
            "3. Tire : les ${c.preSeconds} s avant et ${c.postSeconds} s après sont enregistrées.\n" +
            "4. Retrouve tes vidéos dans Clips.\n" +
            "Astuce : bouton Volume = sauvegarde manuelle."
    }

    // ------------------------------------------------------------ preview

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attachPreview(st)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            detachPreview(); return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun attachPreview(st: SurfaceTexture) {
        if (surface != null) return
        st.setDefaultBufferSize(textureView.width.coerceAtLeast(1), textureView.height.coerceAtLeast(1))
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

    // ------------------------------------------------------------ gestures: zoom + red dot

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val maxZ = max(1f, StatusBus.maxZoom)
                val z = (Config.prefs(this@MainActivity).getFloat(Config.K_ZOOM, 1f) * d.scaleFactor).coerceIn(1f, maxZ)
                Config.prefs(this@MainActivity).edit().putFloat(Config.K_ZOOM, z).apply()
                lastZoomShownMs = System.currentTimeMillis()
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!aligning) return false
                val vp = VideoPipeline.fitViewport(textureView.width, textureView.height, 16f / 9f)
                // GL viewport y is from the bottom; the view's is from the top (symmetric when centred).
                val x = ((e.x - vp[0]) / vp[2]).coerceIn(0f, 1f)
                val y = ((e.y - (textureView.height - vp[1] - vp[3])) / vp[3]).coerceIn(0f, 1f)
                saveDot(x, y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Config.prefs(this@MainActivity).edit().putFloat(Config.K_ZOOM, 1f).apply()
                lastZoomShownMs = System.currentTimeMillis()
                return true
            }
        })
        textureView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            taps.onTouchEvent(ev)
            true
        }
    }

    private fun setAligning(on: Boolean) {
        aligning = on
        alignBanner.visibility = if (on) View.VISIBLE else View.GONE
        if (on && !StatusBus.running) {
            Toast.makeText(this, "Arme d'abord la caméra pour voir l'image", Toast.LENGTH_SHORT).show()
        }
        if (on && !cfg.overlay.redDot) {
            Config.prefs(this).edit().putBoolean("ov_reddot", true).apply()
        }
    }

    private fun saveDot(x: Float, y: Float) {
        Config.prefs(this).edit().putFloat(Config.K_DOT_X, x).putFloat(Config.K_DOT_Y, y).apply()
    }

    // ------------------------------------------------------------ UI refresh

    private val refresh = object : Runnable {
        override fun run() {
            cfg = Config.load(this@MainActivity)
            render()
            ui.postDelayed(this, 200)
        }
    }

    private fun render() {
        val running = StatusBus.running
        val state = if (running) StatusBus.state else if (StatusBus.state == UiState.ERROR) UiState.ERROR else UiState.STOPPED

        val (label, color, text) = when (state) {
            UiState.STOPPED -> Triple("ARRÊTÉ", R.color.state_stopped, "Fixe le téléphone puis appuie sur ARMER.")
            UiState.STANDBY -> Triple("EN VEILLE", R.color.state_standby, "Épaule le fusil : la caméra s'arme toute seule.")
            UiState.FILLING -> Triple(
                "ARMEMENT", R.color.state_filling,
                "Mémoire vidéo : ${StatusBus.bufferedSeconds.toInt()} / ${StatusBus.targetSeconds} s. Tu peux déjà tirer."
            )
            UiState.ARMED -> Triple(
                "PRÊT", R.color.state_armed,
                "Tire : ${cfg.preSeconds} s avant + ${cfg.postSeconds} s après seront enregistrées."
            )
            UiState.RECORDING -> Triple(
                "● ENREGISTREMENT", R.color.state_recording,
                "Encore ${StatusBus.postRemaining} s d'après-tir. Un nouveau tir prolonge le clip."
            )
            UiState.ERROR -> Triple("ERREUR", R.color.state_error, "${StatusBus.error}\nAppuie sur STOP puis ARMER pour relancer.")
        }
        stateView.text = label
        stateView.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        hint.text = text

        bufferBar.visibility = if (state == UiState.FILLING || state == UiState.ARMED) View.VISIBLE else View.GONE
        if (StatusBus.targetSeconds > 0) {
            bufferBar.progress = (100 * StatusBus.bufferedSeconds / StatusBus.targetSeconds).toInt().coerceIn(0, 100)
        }

        val ev = StatusBus.lastEvent
        val fresh = System.currentTimeMillis() - StatusBus.lastEventAtMs < 8000
        eventView.visibility = if (ev.isNotEmpty() && fresh && running) View.VISIBLE else View.GONE
        eventView.text = ev

        btnArm.text = if (running) "STOP" else "ARMER"
        btnArm.backgroundTintList = ColorStateList.valueOf(
            if (running) 0xFFECEFF1.toInt() else ContextCompat.getColor(this, R.color.accent)
        )
        btnSave.isEnabled = running
        empty.visibility = if (running) View.GONE else View.VISIBLE

        val z = cfg.zoom
        zoomLabel.visibility = if (running && (z > 1.05f || System.currentTimeMillis() - lastZoomShownMs < 1500)) View.VISIBLE else View.GONE
        zoomLabel.text = String.format(Locale.FRANCE, "%.1f×", z)

        val (a, g) = ShootCamService.instance?.takeSensorPeaks() ?: (0f to 0f)
        accelShown = max(a, accelShown * 0.85f)
        gyroShown = max(g, gyroShown * 0.85f)
        audioShown = max(StatusBus.audioPeak, audioShown * 0.85f)
        StatusBus.audioPeak = 0f
        val showMeters = running && Config.prefs(this).getBoolean("show_meters", false)
        meters.visibility = if (showMeters) View.VISIBLE else View.GONE
        if (showMeters) {
            meters.text = String.format(
                Locale.FRANCE, "Recul %3.0f/%d   Rotation %.1f/%.1f   Son %d/%d %%",
                accelShown, cfg.recoilThreshold.toInt(), gyroShown, cfg.aimRate,
                (audioShown * 100).toInt(), (cfg.audioThreshold * 100).toInt()
            )
        }
    }
}
