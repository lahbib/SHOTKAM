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
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** État partagé avec l'écran (lu par polling). */
object StatusBus {
    @Volatile var running = false
    @Volatile var state = "Arrêté"
    @Volatile var bufferedSeconds = 0f
    @Volatile var bufferMb = 0f
    @Volatile var clipsSaved = 0
    @Volatile var lastClip: Uri? = null
    @Volatile var lastEvent = ""
    @Volatile var audioPeak = 0f
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

        /** Surface d'aperçu fournie par l'écran principal (null si écran fermé). */
        @Volatile var previewSurface: Surface? = null

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ShootCamService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val buffer = ClipBuffer()
    private val writer = Executors.newSingleThreadExecutor()
    private lateinit var cfg: Config

    private var motion: MotionDetector? = null
    private var video: VideoPipeline? = null
    private var audio: AudioPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    private var armedUntilUs = 0L
    private var clipStartUs: Long? = null
    private var clipEndUs = 0L
    private var shotsInClip = 0
    private var lastShotUs = Long.MIN_VALUE / 2
    private var lastRecoilUs = Long.MIN_VALUE / 2
    private var lastAudioUs = Long.MIN_VALUE / 2
    @Volatile private var lastAudioPostUs = 0L
    private var lastNotifText = ""

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cfg = Config.load(this)
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

    // ---------------------------------------------------------------- cycle de vie

    private fun startAll() {
        if (running) return
        cfg = Config.load(this)
        Clock.init(this)

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (cfg.recordAudio && micGranted()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Démarrage…"), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
            StatusBus.state = "Erreur : ${e.message}"
            stopSelf()
            return
        }
        running = true
        StatusBus.running = true
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefListener)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShootCam:rec").apply { acquire(6 * 60 * 60 * 1000L) }

        motion = MotionDetector(this, { cfg }, this).also { it.start() }
        if (cfg.armMode == ArmMode.PERMANENT) startPipelines()
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
            stopPipelines()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefListener)
            running = false
        }
        StatusBus.running = false
        StatusBus.state = "Arrêté"
        StatusBus.bufferedSeconds = 0f
        StatusBus.bufferMb = 0f
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll()
        writer.shutdown()
        instance = null
        super.onDestroy()
    }

    private fun startPipelines() {
        if (video != null) return
        try {
            buffer.clear()
            video = VideoPipeline(this, cfg, buffer) { msg -> main.post { onPipelineError(msg) } }
                .also { it.start(previewSurface) }
            if (cfg.recordAudio && micGranted()) {
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
        StatusBus.lastEvent = "⚠ $msg"
        updateNotification("Erreur : $msg")
        stopPipelines()
    }

    /** Appelé par l'écran quand la surface d'aperçu apparaît / disparaît. */
    fun onPreviewChanged() {
        video?.setPreviewSurface(previewSurface)
    }

    fun takeSensorPeaks(): Pair<Float, Float> = motion?.takePeaks() ?: (0f to 0f)

    // ---------------------------------------------------------------- détection

    override fun onAim() {
        main.post {
            if (!running) return@post
            val now = Clock.nowUs()
            StatusBus.lastEvent = "Mise en joue détectée"
            if (cfg.armMode == ArmMode.MOTION) {
                armedUntilUs = max(armedUntilUs, now + cfg.armTimeoutSeconds * 1_000_000L)
                startPipelines()
            }
        }
    }

    override fun onRecoil(magnitude: Float) {
        main.post {
            if (!running) return@post
            lastRecoilUs = Clock.nowUs()
            StatusBus.lastEvent = "Recul ${"%.0f".format(magnitude)} m/s²"
            evaluateShot(fromRecoil = true)
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
            evaluateShot(fromRecoil = false)
        }
    }

    private fun evaluateShot(fromRecoil: Boolean) {
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
        onShot(Clock.nowUs(), manual = false)
    }

    private fun onShot(t: Long, manual: Boolean) {
        if (!manual && t - lastShotUs < 500_000) return
        lastShotUs = t
        startPipelines() // mode "mise en joue" pas encore armé : on filme au moins l'après-tir
        armedUntilUs = max(armedUntilUs, t + cfg.armTimeoutSeconds * 1_000_000L)
        val start = clipStartUs
        if (start == null) {
            clipStartUs = t - cfg.preSeconds * 1_000_000L
            clipEndUs = t + cfg.postSeconds * 1_000_000L
            shotsInClip = 1
        } else {
            // Tir pendant l'après-tir : on prolonge le même clip
            clipEndUs = max(clipEndUs, t + cfg.postSeconds * 1_000_000L)
            shotsInClip++
        }
        StatusBus.lastEvent = if (manual) "Sauvegarde manuelle" else "TIR détecté (#$shotsInClip)"
        vibrate(longArrayOf(0, 80))
    }

    // ---------------------------------------------------------------- boucle

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = Clock.nowUs()
            val start = clipStartUs
            if (start != null && now >= clipEndUs) finalizeClip()

            val keepFrom = minOf(now - cfg.preSeconds * 1_000_000L, clipStartUs ?: Long.MAX_VALUE)
            buffer.trim(keepFrom)

            if (cfg.armMode == ArmMode.MOTION && video != null && clipStartUs == null && now > armedUntilUs) {
                stopPipelines()
                StatusBus.lastEvent = "Désarmé (inactivité)"
            }

            StatusBus.bufferedSeconds = buffer.durationSeconds()
            StatusBus.bufferMb = buffer.sizeBytes() / 1_048_576f
            val s = when {
                clipStartUs != null -> "● ENREGISTREMENT après-tir (${max(0, (clipEndUs - now) / 1_000_000)} s)"
                video != null -> "Armé — tampon ${StatusBus.bufferedSeconds.toInt()}/${cfg.preSeconds} s"
                else -> "En veille — attente mise en joue"
            }
            StatusBus.state = s
            updateNotification(s)
            main.postDelayed(this, 500)
        }
    }

    private fun finalizeClip() {
        val start = clipStartUs ?: return
        val end = clipEndUs
        val shots = shotsInClip
        clipStartUs = null
        val vf = buffer.videoFormat
        if (vf == null) {
            StatusBus.lastEvent = "⚠ Rien à sauvegarder (vidéo pas encore prête)"
            return
        }
        val samples = buffer.snapshot(start, end)
        val af = buffer.audioFormat
        val orientation = ((video?.sensorOrientation ?: 90) + (motion?.deviceOrientation ?: 0)) % 360
        StatusBus.lastEvent = "Écriture du clip…"
        writer.execute {
            val uri = ClipWriter.write(this, samples, vf, af, orientation, shots)
            main.post {
                if (uri != null) {
                    StatusBus.clipsSaved++
                    StatusBus.lastClip = uri
                    StatusBus.lastEvent = "✔ Clip sauvegardé (Films/ShootCam)"
                    vibrate(longArrayOf(0, 60, 120, 60))
                } else {
                    StatusBus.lastEvent = "⚠ Échec écriture du clip"
                }
            }
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
            .setContentTitle("ShootCam actif")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Sauver maintenant", pi(ACTION_SAVE, 1))
            .addAction(0, "Arrêter", pi(ACTION_STOP, 2))
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText || !running) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
