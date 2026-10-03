package com.kwaris.shootcam

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Guided calibration: the user hits the rifle (or fires) 3 times, then shoulders it 3 times.
 * The thresholds are set from the weakest of the 3 measured peaks, with a safety margin.
 */
class CalibrationActivity : AppCompatActivity(), SensorEventListener {

    private enum class Step { RECOIL, AIM, DONE }

    private lateinit var sm: SensorManager
    private val main = Handler(Looper.getMainLooper())
    private var step = Step.RECOIL

    private val recoil = RecoilDetector { 12f }
    private var impulsePeak = 0f
    private var impulseEndNs = 0L
    private val recoilPeaks = mutableListOf<Float>()

    private var swingPeak = 0f
    private var inSwing = false
    private val aimPeaks = mutableListOf<Float>()

    @Volatile private var liveAccel = 0f
    @Volatile private var liveGyro = 0f

    private lateinit var stepTitle: TextView
    private lateinit var instructions: TextView
    private lateinit var live: TextView
    private lateinit var results: TextView
    private lateinit var btnNext: MaterialButton
    private lateinit var btnRetry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        setTitle(R.string.calib_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        sm = getSystemService(SensorManager::class.java)
        stepTitle = findViewById(R.id.calib_step)
        instructions = findViewById(R.id.calib_instructions)
        live = findViewById(R.id.calib_live)
        results = findViewById(R.id.calib_results)
        btnNext = findViewById(R.id.calib_next)
        btnRetry = findViewById(R.id.calib_retry)
        btnRetry.setOnClickListener { resetStep() }
        btnNext.setOnClickListener { next() }
        showStep()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() {
        super.onResume()
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        main.post(refresh)
    }

    override fun onPause() {
        sm.unregisterListener(this)
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val trig = recoil.onSample(v[0], v[1], v[2], e.timestamp)
                liveAccel = max(liveAccel, recoil.magnitude)
                if (step != Step.RECOIL) return
                if (trig != null && impulseEndNs == 0L) {
                    impulsePeak = trig
                    impulseEndNs = e.timestamp + 120_000_000L
                } else if (impulseEndNs != 0L) {
                    impulsePeak = max(impulsePeak, recoil.magnitude)
                    if (e.timestamp > impulseEndNs) {
                        val p = impulsePeak
                        impulseEndNs = 0L
                        main.post { addRecoil(p) }
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                liveGyro = max(liveGyro, mag)
                if (step != Step.AIM) return
                if (mag > 1.2f) {
                    inSwing = true
                    swingPeak = max(swingPeak, mag)
                } else if (inSwing && mag < 0.6f) {
                    inSwing = false
                    val p = swingPeak
                    swingPeak = 0f
                    if (p > 1.5f) main.post { addAim(p) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun addRecoil(p: Float) {
        if (step != Step.RECOIL || recoilPeaks.size >= 3) return
        recoilPeaks += p
        vibrate()
        showStep()
    }

    private fun addAim(p: Float) {
        if (step != Step.AIM || aimPeaks.size >= 3) return
        aimPeaks += p
        vibrate()
        showStep()
    }

    private fun vibrate() {
        try {
            getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createOneShot(40, 120))
        } catch (_: Exception) {
        }
    }

    private fun recoilSuggestion(): Int = (recoilPeaks.min() * 0.7f).roundToInt().coerceIn(10, 150)
    private fun aimSuggestion(): Int = (aimPeaks.min() * 0.6f * 10).roundToInt().coerceIn(10, 80)

    private fun showStep() {
        when (step) {
            Step.RECOIL -> {
                stepTitle.text = "Étape 1/2 — Recul"
                instructions.text =
                    "Téléphone fixé sur le fusil, donne 3 coups secs sur la crosse avec la paume " +
                    "(ou tire 3 fois au stand, c'est encore mieux).\n" +
                    "Chaque coup détecté fait vibrer le téléphone."
                results.text = if (recoilPeaks.isEmpty()) "Coups détectés : 0 / 3"
                else "Coups détectés : ${recoilPeaks.size} / 3  →  " +
                    recoilPeaks.joinToString("  ") { "${it.roundToInt()} m/s²" } +
                    if (recoilPeaks.size == 3) "\nSeuil proposé : ${recoilSuggestion()} m/s²" else ""
                btnNext.text = if (recoilPeaks.size == 3) "Appliquer et continuer" else "Passer"
            }
            Step.AIM -> {
                stepTitle.text = "Étape 2/2 — Mise en joue"
                instructions.text =
                    "Fusil (vide !) à la bretelle ou en bas, épaule 3 fois comme pour viser, " +
                    "en marquant un temps d'arrêt en visée."
                results.text = if (aimPeaks.isEmpty()) "Mises en joue détectées : 0 / 3"
                else "Mises en joue détectées : ${aimPeaks.size} / 3  →  " +
                    aimPeaks.joinToString("  ") { String.format(Locale.FRANCE, "%.1f rad/s", it) } +
                    if (aimPeaks.size == 3) String.format(Locale.FRANCE, "\nSeuil proposé : %.1f rad/s", aimSuggestion() / 10f) else ""
                btnNext.text = if (aimPeaks.size == 3) "Appliquer et terminer" else "Passer"
            }
            Step.DONE -> {
                val c = Config.load(this)
                stepTitle.text = "Calibrage terminé"
                instructions.text = String.format(
                    Locale.FRANCE,
                    "Seuil de recul : %d m/s²\nSeuil de mise en joue : %.1f rad/s\n\n" +
                        "Tu peux les ajuster à tout moment dans Réglages.",
                    c.recoilThreshold.toInt(), c.aimRate
                )
                results.text = ""
                btnNext.text = "Fermer"
            }
        }
        btnRetry.visibility = if (step == Step.DONE) View.GONE else View.VISIBLE
    }

    private fun resetStep() {
        if (step == Step.RECOIL) recoilPeaks.clear() else aimPeaks.clear()
        showStep()
    }

    private fun next() {
        val e = Config.prefs(this).edit()
        when (step) {
            Step.RECOIL -> {
                if (recoilPeaks.size == 3) e.putInt(Config.K_RECOIL, recoilSuggestion()).apply()
                step = Step.AIM
            }
            Step.AIM -> {
                if (aimPeaks.size == 3) e.putInt(Config.K_AIM, aimSuggestion()).apply()
                step = Step.DONE
                Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
            }
            Step.DONE -> {
                finish(); return
            }
        }
        showStep()
    }

    private val refresh = object : Runnable {
        override fun run() {
            live.text = String.format(
                Locale.FRANCE, "En direct — choc : %3.0f m/s²   rotation : %.1f rad/s", liveAccel, liveGyro
            )
            liveAccel *= 0.8f
            liveGyro *= 0.8f
            main.postDelayed(this, 150)
        }
    }
}
