package com.kwaris.shootcam

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

enum class ArmMode { PERMANENT, MOTION }
enum class ShotMode { ACCEL, AUDIO, EITHER, BOTH }

/** User settings (Settings screen), read once per change. */
data class Config(
    val preSeconds: Int,
    val postSeconds: Int,
    val height: Int,
    val fps: Int,
    val recordAudio: Boolean,
    val armMode: ArmMode,
    val armTimeoutSeconds: Int,
    val shotMode: ShotMode,
    /** Recoil threshold in m/s² (acceleration without gravity). */
    val recoilThreshold: Float,
    /** Angular rate (rad/s) characterising the sudden aiming motion. */
    val aimRate: Float,
    /** Only accept an aim when the barrel is roughly level (|elevation| < 35°). */
    val aimRequireLevel: Boolean,
    /** Peak audio level (0..1) treated as a gunshot. */
    val audioThreshold: Float,
    val vibrate: Boolean,
    val keepScreenOn: Boolean,
    val volumeKeySave: Boolean,
    val overlay: OverlayConfig,
    val zoom: Float,
) {
    val bitrate: Int
        get() = when {
            height >= 2160 -> 40_000_000
            height >= 1080 -> if (fps > 30) 16_000_000 else 12_000_000
            else -> if (fps > 30) 8_000_000 else 6_000_000
        }

    companion object {
        fun prefs(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

        fun load(ctx: Context): Config {
            val p = prefs(ctx)
            return Config(
                preSeconds = p.getInt(K_PRE, 30),
                postSeconds = p.getInt(K_POST, 20),
                height = p.getString("resolution", "1080")!!.toInt(),
                fps = p.getString("fps", "30")!!.toInt(),
                recordAudio = p.getBoolean("record_audio", true),
                armMode = enumOr(p.getString("arm_mode", null), ArmMode.PERMANENT),
                armTimeoutSeconds = p.getInt("arm_timeout", 180),
                shotMode = enumOr(p.getString("shot_mode", null), ShotMode.ACCEL),
                recoilThreshold = p.getInt(K_RECOIL, 35).toFloat(),
                aimRate = p.getInt(K_AIM, 25) / 10f,
                aimRequireLevel = p.getBoolean("aim_level", true),
                audioThreshold = p.getInt("audio_threshold", 85) / 100f,
                vibrate = p.getBoolean("vibrate", true),
                keepScreenOn = p.getBoolean("keep_screen_on", true),
                volumeKeySave = p.getBoolean("volume_key_save", true),
                overlay = OverlayConfig(
                    enabled = p.getBoolean("ov_enabled", true),
                    dateTime = p.getBoolean("ov_datetime", true),
                    gps = p.getBoolean("ov_gps", true),
                    weather = p.getBoolean("ov_weather", true),
                    attitude = p.getBoolean("ov_attitude", true),
                    redDot = p.getBoolean("ov_reddot", true),
                    redDotX = p.getFloat(K_DOT_X, 0.5f),
                    redDotY = p.getFloat(K_DOT_Y, 0.5f),
                    redDotSize = p.getInt("ov_reddot_size", 3) ,
                    shotMarker = p.getBoolean("ov_shot", true),
                ),
                zoom = p.getFloat(K_ZOOM, 1f),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(v: String?, def: T): T =
            try { if (v == null) def else enumValueOf<T>(v) } catch (_: IllegalArgumentException) { def }

        const val K_PRE = "pre_seconds"
        const val K_POST = "post_seconds"
        const val K_RECOIL = "recoil_threshold"
        const val K_AIM = "aim_rate"
        const val K_DOT_X = "ov_reddot_x"
        const val K_DOT_Y = "ov_reddot_y"
        const val K_ZOOM = "zoom"
        const val K_ONBOARDED = "onboarded_v2"
    }
}

data class OverlayConfig(
    val enabled: Boolean,
    val dateTime: Boolean,
    val gps: Boolean,
    val weather: Boolean,
    val attitude: Boolean,
    val redDot: Boolean,
    /** Red dot position in the video frame, normalised 0..1 (0,0 = top-left). */
    val redDotX: Float,
    val redDotY: Float,
    /** 1..10, in thousandths of the frame height x 4. */
    val redDotSize: Int,
    val shotMarker: Boolean,
)
