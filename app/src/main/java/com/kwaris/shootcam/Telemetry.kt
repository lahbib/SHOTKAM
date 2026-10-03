package com.kwaris.shootcam

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

data class Weather(
    val tempC: Double,
    val humidity: Int,
    val windKmh: Double,
    val windDirDeg: Int,
    val gustKmh: Double,
    val pressureHpa: Double,
    val code: Int,
    val fetchedAtMs: Long,
    val lat: Double,
    val lon: Double,
)

/** Latest location / weather / attitude, shared by the service, the overlay and the UI. */
object Telemetry {
    @Volatile var location: Location? = null
    @Volatile var weather: Weather? = null

    /** Compass bearing of the camera axis (0..360, 0 = north), NaN if unknown. */
    @Volatile var headingDeg = Float.NaN
    /** Elevation of the camera axis above the horizon, degrees. */
    @Volatile var elevationDeg = 0f
    /** Rotation of the frame around the camera axis away from level, degrees. */
    @Volatile var cantDeg = 0f

    @Volatile var shotsInClip = 0
    @Volatile var lastShotUs = Long.MIN_VALUE / 2
    @Volatile var recording = false
}

object Format {
    private val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun cardinal(deg: Float): String {
        if (deg.isNaN()) return "?"
        val d = ((deg % 360f) + 360f) % 360f
        return dirs[((d + 22.5f) / 45f).toInt() % 8]
    }

    fun coord(v: Double, pos: Char, neg: Char): String =
        String.format(Locale.US, "%.5f° %c", abs(v), if (v >= 0) pos else neg)

    fun signed(v: Float): String {
        val r = v.roundToInt()
        return if (r > 0) "+$r" else "$r"
    }

    /** WMO weather code -> short French label. */
    fun weatherLabel(code: Int): String = when (code) {
        0 -> "Ciel clair"
        1 -> "Peu nuageux"
        2 -> "Nuageux"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grains de neige"
        80, 81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage, grêle"
        else -> "—"
    }
}

/** GPS + network location updates through the platform LocationManager (no Play Services). */
class LocationTracker(ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
            for (p in providers) {
                lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
                lm.requestLocationUpdates(p, 2000L, 0f, this, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w("LocationTracker", "start", e)
        }
    }

    fun stop() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        val cur = Telemetry.location
        // Keep the better fix: newer than 10 s or more accurate.
        if (cur == null ||
            location.elapsedRealtimeNanos - cur.elapsedRealtimeNanos > 10_000_000_000L ||
            location.accuracy <= cur.accuracy
        ) {
            Telemetry.location = location
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** Current conditions from Open-Meteo (free, no API key). Refreshed every 15 min or after 2 km. */
class WeatherClient {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var inFlight = false

    fun refreshIfNeeded() {
        val loc = Telemetry.location ?: return
        val w = Telemetry.weather
        val now = System.currentTimeMillis()
        if (w != null) {
            val moved = FloatArray(1)
            Location.distanceBetween(w.lat, w.lon, loc.latitude, loc.longitude, moved)
            if (now - w.fetchedAtMs < 15 * 60_000L && moved[0] < 2000f) return
            if (now - w.fetchedAtMs < 60_000L) return
        }
        if (inFlight) return
        inFlight = true
        val lat = loc.latitude
        val lon = loc.longitude
        exec.execute {
            try {
                Telemetry.weather = fetch(lat, lon)
            } catch (e: Exception) {
                Log.w("WeatherClient", "fetch failed: ${e.message}")
            } finally {
                inFlight = false
            }
        }
    }

    fun shutdown() = exec.shutdownNow()

    private fun fetch(lat: Double, lon: Double): Weather {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,surface_pressure," +
                    "wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=kmh",
                lat, lon
            )
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return parse(body, lat, lon, System.currentTimeMillis())
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun parse(json: String, lat: Double, lon: Double, now: Long): Weather {
            val cur = JSONObject(json).getJSONObject("current")
            return Weather(
                tempC = cur.optDouble("temperature_2m", Double.NaN),
                humidity = cur.optInt("relative_humidity_2m", -1),
                windKmh = cur.optDouble("wind_speed_10m", Double.NaN),
                windDirDeg = cur.optInt("wind_direction_10m", 0),
                gustKmh = cur.optDouble("wind_gusts_10m", Double.NaN),
                pressureHpa = cur.optDouble("surface_pressure", Double.NaN),
                code = cur.optInt("weather_code", -1),
                fetchedAtMs = now,
                lat = lat,
                lon = lon,
            )
        }
    }
}
