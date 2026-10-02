package com.kwaris.shootcam

import android.content.Context
import androidx.preference.PreferenceManager

enum class ArmMode { PERMANENT, MOTION }
enum class ShotMode { ACCEL, AUDIO, EITHER, BOTH }

/** Réglages utilisateur (écran Réglages). */
data class Config(
    val preSeconds: Int,
    val postSeconds: Int,
    val height: Int,
    val fps: Int,
    val recordAudio: Boolean,
    val armMode: ArmMode,
    val armTimeoutSeconds: Int,
    val shotMode: ShotMode,
    /** Seuil de recul en m/s² (accélération hors gravité). */
    val recoilThreshold: Float,
    /** Vitesse angulaire (rad/s) qui caractérise le mouvement brusque de mise en joue. */
    val aimRate: Float,
    /** Niveau audio crête 0..1 considéré comme une détonation. */
    val audioThreshold: Float,
    val vibrate: Boolean,
    val keepScreenOn: Boolean,
) {
    val bitrate: Int
        get() = when {
            height >= 2160 -> 40_000_000
            height >= 1080 -> if (fps > 30) 16_000_000 else 10_000_000
            else -> if (fps > 30) 8_000_000 else 5_000_000
        }

    companion object {
        fun load(ctx: Context): Config {
            val p = PreferenceManager.getDefaultSharedPreferences(ctx)
            return Config(
                preSeconds = p.getInt("pre_seconds", 30),
                postSeconds = p.getInt("post_seconds", 20),
                height = p.getString("resolution", "1080")!!.toInt(),
                fps = p.getString("fps", "30")!!.toInt(),
                recordAudio = p.getBoolean("record_audio", true),
                armMode = ArmMode.valueOf(p.getString("arm_mode", "MOTION")!!),
                armTimeoutSeconds = p.getInt("arm_timeout", 120),
                shotMode = ShotMode.valueOf(p.getString("shot_mode", "ACCEL")!!),
                recoilThreshold = p.getInt("recoil_threshold", 35).toFloat(),
                aimRate = p.getInt("aim_rate", 25) / 10f,
                audioThreshold = p.getInt("audio_threshold", 85) / 100f,
                vibrate = p.getBoolean("vibrate", true),
                keepScreenOn = p.getBoolean("keep_screen_on", true),
            )
        }
    }
}
