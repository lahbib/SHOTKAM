package com.kwaris.shootcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

enum class UiState { STOPPED, STANDBY, FILLING, ARMED, RECORDING, ERROR }

/** State shared with the UI (read by polling). */
object StatusBus {
    @Volatile var running = false
    @Volatile var state = UiState.STOPPED
    @Volatile var bufferedSeconds = 0f
    @Volatile var targetSeconds = 30
    @Volatile var postRemaining = 0
    @Volatile var bufferMb = 0f
    @Volatile var clipsSaved = 0
    @Volatile var lastClip: Uri? = null
    @Volatile var lastEvent = ""
    @Volatile var lastEventAtMs = 0L
    @Volatile var error = ""
    @Volatile var audioPeak = 0f
    @Volatile var maxZoom = 1f

    fun event(msg: String) {
        lastEvent = msg
        lastEventAtMs = System.currentTimeMillis()
    }
}

class ShootCamService : Service(), MotionDetector.Listener {

    companion object {
        private const val TAG = "ShootCamService"
        const val ACTION_START = "com.kwaris.shootcam.START"
        const val ACTION_STOP = "com.kwaris.shootcam.STOP"
        const val ACTION_SAVE = "com.kwaris.shootcam.SAVE"
        private const val CHANNEL = "shootcam"
        private const val NOTIF_ID = 42

        @Volatile var instance: ShootCamService? = null
            private set

        /** Preview surface provided by the main screen (null when the screen is closed). */
        @Volatile var previewSurface: Surface? = null

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ShootCamService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val buffer = ClipBuffer()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private lateinit var cfg: Config

    private var motion: MotionDetector? = null
    private var video: VideoPipeline? = null
    private var audio: AudioPipeline? = null
    private var location: LocationTracker? = null
    private val weather = WeatherClient()
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    private var armedUntilUs = 0L
    private var clipStartUs: Long? = null
    private var clipEndUs = 0L
    private var clipShotWallMs = 0L
    private var lastShotUs = Long.MIN_VALUE / 2
    private var lastRecoilUs = Long.MIN_VALUE / 2
    private var lastAudioUs = Long.MIN_VALUE / 4
    @Volatile private var lastAudioPostUs = 0L
    private var lastNotifText = ""
    private var tickCount = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val old = cfg
        cfg = Config.load(this)
        if (key == Config.K_ZOOM && old.zoom != cfg.zoom) video?.refreshRequest()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = Config.load(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAll()
            ACTION_SAVE -> if (running) onShot(Clock.nowUs(), manual = true)
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- lifecycle

    private fun startAll() {
        if (running) return
        cfg = Config.load(this)

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (cfg.recordAudio && granted(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Démarrage…"), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
            StatusBus.state = UiState.ERROR
            StatusBus.error = "Démarrage impossible : ${e.message}"
            stopSelf()
            return
        }
        running = true
        StatusBus.running = true
        StatusBus.error = ""
        StatusBus.clipsSaved = 0
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShootCam:rec").apply { acquire(8 * 60 * 60 * 1000L) }

        motion = MotionDetector(this, { cfg }, this).also { it.start() }
        if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) {
            location = LocationTracker(this).also { it.start() }
        }
        if (cfg.armMode == ArmMode.PERMANENT) {
            startPipelines()
            StatusBus.event("Armé — la vidéo tourne en boucle")
        } else {
            StatusBus.event("En veille — épaule le fusil pour armer")
        }
        main.post(tick)
    }

    private fun stopAll() {
        if (running) {
            main.removeCallbacks(tick)
            if (clipStartUs != null) {
                clipEndUs = Clock.nowUs()
                finalizeClip()
            }
            motion?.stop(); motion = null
            location?.stop(); location = null
            stopPipelines()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Config.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
            running = false
        }
        StatusBus.running = false
        if (StatusBus.state != UiState.ERROR) StatusBus.state = UiState.STOPPED
        StatusBus.bufferedSeconds = 0f
        StatusBus.bufferMb = 0f
        Telemetry.recording = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll()
        writer.shutdown()
        weather.shutdown()
        instance = null
        super.onDestroy()
    }

    private fun startPipelines() {
        if (video != null) return
        try {
            buffer.clear()
            val v = VideoPipeline(
                this, { cfg }, buffer,
                deviceOrientation = { motion?.deviceOrientation ?: 270 },
                errorSink = { msg -> main.post { onPipelineError(msg) } },
            )
            video = v
            v.start(previewSurface)
            StatusBus.maxZoom = v.maxZoom
            if (cfg.recordAudio && granted(Manifest.permission.RECORD_AUDIO)) {
                audio = AudioPipeline(this, buffer) { level -> onAudioLevel(level) }.also { it.start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPipelines", e)
            onPipelineError(e.message ?: e.toString())
        }
    }

    private fun stopPipelines() {
        audio?.stop(); audio = null
        video?.stop(); video = null
        buffer.clear()
    }

    private fun onPipelineError(msg: String) {
        StatusBus.state = UiState.ERROR
        StatusBus.error = msg
        StatusBus.event("⚠ $msg")
        updateNotification("Erreur : $msg")
        stopPipelines()
    }

    /** Called by the UI when the preview surface appears / disappears. */
    fun onPreviewChanged() {
        video?.setPreviewSurface(previewSurface)
    }

    fun takeSensorPeaks(): Pair<Float, Float> = motion?.takePeaks() ?: (0f to 0f)

    // ---------------------------------------------------------------- detection

    override fun onAim() {
        main.post {
            if (!running) return@post
            val now = Clock.nowUs()
            if (cfg.armMode == ArmMode.MOTION) {
                armedUntilUs = max(armedUntilUs, now + cfg.armTimeoutSeconds * 1_000_000L)
                if (video == null) {
                    startPipelines()
                    StatusBus.event("Mise en joue détectée — caméra armée")
                    vibrate(longArrayOf(0, 30))
                    return@post
                }
            }
            StatusBus.event("Mise en joue détectée")
        }
    }

    override fun onRecoil(magnitude: Float) {
        main.post {
            if (!running) return@post
            lastRecoilUs = Clock.nowUs()
            evaluateShot(fromRecoil = true, detail = "recul ${magnitude.toInt()} m/s²")
        }
    }

    private fun onAudioLevel(level: Float) {
        StatusBus.audioPeak = max(StatusBus.audioPeak, level)
        if (level < cfg.audioThreshold) return
        val now = Clock.nowUs()
        if (now - lastAudioPostUs < 150_000) return
        lastAudioPostUs = now
        main.post {
            if (!running) return@post
            lastAudioUs = now
            evaluateShot(fromRecoil = false, detail = "détonation ${(level * 100).toInt()} %")
        }
    }

    private fun evaluateShot(fromRecoil: Boolean, detail: String) {
        val isShot = when (cfg.shotMode) {
            ShotMode.ACCEL -> fromRecoil
            ShotMode.AUDIO -> !fromRecoil
            ShotMode.EITHER -> true
            ShotMode.BOTH -> abs(lastRecoilUs - lastAudioUs) <= 400_000
        }
        if (!isShot) return
        if (cfg.shotMode == ShotMode.BOTH) {
            lastRecoilUs = Long.MIN_VALUE / 2
            lastAudioUs = Long.MIN_VALUE / 4
        }
        onShot(Clock.nowUs(), manual = false, detail = detail)
    }

    private fun onShot(t: Long, manual: Boolean, detail: String = "") {
        if (!manual && t - lastShotUs < 500_000) return
        lastShotUs = t
        startPipelines() // aim mode not armed yet: record at least the post-shot window
        armedUntilUs = max(armedUntilUs, t + cfg.armTimeoutSeconds * 1_000_000L)
        if (clipStartUs == null) {
            clipStartUs = t - cfg.preSeconds * 1_000_000L
            clipEndUs = t + cfg.postSeconds * 1_000_000L
            clipShotWallMs = System.currentTimeMillis()
            Telemetry.shotsInClip = 1
        } else {
            // Shot during the post-shot window: extend the same clip
            clipEndUs = max(clipEndUs, t + cfg.postSeconds * 1_000_000L)
            Telemetry.shotsInClip++
        }
        Telemetry.lastShotUs = t
        Telemetry.recording = true
        StatusBus.event(
            if (manual) "Sauvegarde manuelle lancée"
            else "TIR #${Telemetry.shotsInClip} détecté ($detail)"
        )
        vibrate(longArrayOf(0, 80))
    }

    // ---------------------------------------------------------------- main loop

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = Clock.nowUs()
            if (clipStartUs != null && now >= clipEndUs) finalizeClip()

            val keepFrom = minOf(now - cfg.preSeconds * 1_000_000L, clipStartUs ?: Long.MAX_VALUE)
            buffer.trim(keepFrom)

            if (cfg.armMode == ArmMode.MOTION && video != null && clipStartUs == null && now > armedUntilUs) {
                stopPipelines()
                StatusBus.event("Désarmé après inactivité — épaule pour réarmer")
            }

            if (++tickCount % 10 == 0) weather.refreshIfNeeded()

            StatusBus.bufferedSeconds = buffer.durationSeconds()
            StatusBus.targetSeconds = cfg.preSeconds
            StatusBus.bufferMb = buffer.sizeBytes() / 1_048_576f
            StatusBus.postRemaining = if (clipStartUs != null) max(0L, (clipEndUs - now) / 1_000_000).toInt() else 0
            if (StatusBus.state != UiState.ERROR || video != null) {
                StatusBus.state = when {
                    clipStartUs != null -> UiState.RECORDING
                    video == null -> UiState.STANDBY
                    StatusBus.bufferedSeconds + 1f < cfg.preSeconds -> UiState.FILLING
                    else -> UiState.ARMED
                }
                if (video != null) StatusBus.error = ""
            }
            updateNotification(
                when (StatusBus.state) {
                    UiState.RECORDING -> "● Enregistrement après-tir (${StatusBus.postRemaining} s)"
                    UiState.FILLING -> "Armé — tampon ${StatusBus.bufferedSeconds.toInt()}/${cfg.preSeconds} s"
                    UiState.ARMED -> "Armé — prêt à tirer"
                    UiState.STANDBY -> "En veille — épaule pour armer"
                    UiState.ERROR -> "Erreur : ${StatusBus.error}"
                    UiState.STOPPED -> "Arrêté"
                }
            )
            main.postDelayed(this, 500)
        }
    }

    private fun finalizeClip() {
        val start = clipStartUs ?: return
        val end = clipEndUs
        val shots = Telemetry.shotsInClip
        clipStartUs = null
        Telemetry.recording = false
        val vf = buffer.videoFormat
        if (vf == null) {
            StatusBus.event("⚠ Rien à sauvegarder (caméra pas encore prête)")
            return
        }
        val samples = buffer.snapshot(start, end)
        val af = buffer.audioFormat
        val loc = Telemetry.location
        val wall = clipShotWallMs
        StatusBus.event("Écriture du clip…")
        writer.execute {
            val uri = ClipWriter.write(this, samples, vf, af, shots, loc, wall)
            main.post {
                if (uri != null) {
                    StatusBus.clipsSaved++
                    StatusBus.lastClip = uri
                    StatusBus.event("✔ Clip enregistré ($shots tir${if (shots > 1) "s" else ""}) — voir Clips")
                    vibrate(longArrayOf(0, 60, 120, 60))
                } else {
                    StatusBus.event("⚠ Échec de l'écriture du clip")
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun vibrate(pattern: LongArray) {
        if (!cfg.vibrate) return
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "ShootCam", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ShootCamService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("ShootCam")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Sauver maintenant", pi(ACTION_SAVE, 1))
            .addAction(0, "Désarmer", pi(ACTION_STOP, 2))
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText || !running) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
