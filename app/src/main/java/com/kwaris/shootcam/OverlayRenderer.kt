package com.kwaris.shootcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the HUD burned into the video: date/time, GPS, weather, attitude,
 * red dot and shot marker. Redrawn only when its text changes (about once per second).
 */
class OverlayRenderer(val width: Int, val height: Int) {
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val unit = height / 1080f
    private var lastKey = ""

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f * unit
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * unit, 0f, 0f, Color.BLACK)
    }
    private val dim = Paint(text).apply { color = 0xFFE0E0E0.toInt(); typeface = Typeface.DEFAULT }
    private val band = Paint()
    private val marker = Paint(text).apply { color = 0xFFFF3B30.toInt(); textSize = 44f * unit }
    private val markerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1A1A.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF1A1A }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeWidth = 2f * unit
    }
    private val dateFmt = SimpleDateFormat("EEE dd/MM/yyyy  HH:mm:ss", Locale.FRANCE)

    /** Returns the bitmap if it changed since the last call, null otherwise. */
    fun render(cfg: OverlayConfig, showShot: Boolean): Bitmap? {
        if (!cfg.enabled) {
            if (lastKey == "off") return null
            lastKey = "off"
            bitmap.eraseColor(Color.TRANSPARENT)
            return bitmap
        }
        val left = mutableListOf<String>()
        val right = mutableListOf<String>()
        if (cfg.dateTime) left += dateFmt.format(Date())
        if (cfg.gps) left += gpsLine()
        if (cfg.weather) weatherLine()?.let { right += it }
        if (cfg.attitude) right += attitudeLine()
        val shot = cfg.shotMarker && showShot
        val key = left.joinToString("|") + "#" + right.joinToString("|") + "#$shot#${cfg.redDot}" +
            "${cfg.redDotX},${cfg.redDotY},${cfg.redDotSize}"
        if (key == lastKey) return null
        lastKey = key

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // All lines left-aligned, stacked at the bottom: never overlap whatever their length.
        val all = left + right
        if (all.isNotEmpty()) {
            val lh = text.textSize * 1.32f
            val top = height - all.size * lh - 20f * unit
            band.shader = LinearGradient(0f, top - 40f * unit, 0f, height.toFloat(),
                0x00000000, 0xB4000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top - 40f * unit, width.toFloat(), height.toFloat(), band)
            val margin = 28f * unit
            all.forEachIndexed { i, s ->
                canvas.drawText(s, margin, top + (i + 1) * lh - lh * 0.25f, if (i == 0) text else dim)
            }
        }

        if (shot) {
            val label = "● TIR #${Telemetry.shotsInClip}"
            val w = marker.measureText(label)
            val r = RectF(24f * unit, 24f * unit, 24f * unit + w + 36f * unit, 24f * unit + marker.textSize * 1.5f)
            canvas.drawRoundRect(r, 12f * unit, 12f * unit, markerBg)
            canvas.drawText(label, r.left + 18f * unit, r.bottom - marker.textSize * 0.4f, marker)
        }

        if (cfg.redDot) {
            val cx = cfg.redDotX * width
            val cy = cfg.redDotY * height
            val rad = (2f + cfg.redDotSize * 2f) * unit
            canvas.drawCircle(cx, cy, rad * 2.2f, glow)
            canvas.drawCircle(cx, cy, rad, dot)
            canvas.drawCircle(cx, cy, rad, outline)
        }
        return bitmap
    }

    private fun gpsLine(): String {
        val l = Telemetry.location ?: return "GPS : recherche…"
        val sb = StringBuilder()
        sb.append(Format.coord(l.latitude, 'N', 'S')).append("  ").append(Format.coord(l.longitude, 'E', 'O'))
        if (l.hasAccuracy()) sb.append("  ±").append(l.accuracy.roundToInt()).append(" m")
        if (l.hasAltitude()) sb.append("  Alt ").append(l.altitude.roundToInt()).append(" m")
        return sb.toString()
    }

    private fun weatherLine(): String? {
        val w = Telemetry.weather ?: return null
        val sb = StringBuilder()
        if (!w.tempC.isNaN()) sb.append(String.format(Locale.FRANCE, "%.1f °C", w.tempC))
        sb.append("  ").append(Format.weatherLabel(w.code))
        if (!w.windKmh.isNaN()) {
            sb.append("  Vent ").append(w.windKmh.roundToInt()).append(" km/h ")
                .append(Format.cardinal(w.windDirDeg.toFloat()))
            if (!w.gustKmh.isNaN() && w.gustKmh > w.windKmh + 5) sb.append(" (raf. ").append(w.gustKmh.roundToInt()).append(')')
        }
        if (w.humidity >= 0) sb.append("  ").append(w.humidity).append(" %")
        if (!w.pressureHpa.isNaN()) sb.append("  ").append(w.pressureHpa.roundToInt()).append(" hPa")
        return sb.toString()
    }

    private fun attitudeLine(): String {
        val h = Telemetry.headingDeg
        val cap = if (h.isNaN()) "Cap ?" else "Cap ${h.roundToInt()}° ${Format.cardinal(h)}"
        return "$cap  Site ${Format.signed(Telemetry.elevationDeg)}°  Dévers ${Format.signed(Telemetry.cantDeg)}°"
    }

    fun release() = bitmap.recycle()
}
