package com.kwaris.shootcam

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun cardinalPoints() {
        assertEquals("N", Format.cardinal(0f))
        assertEquals("N", Format.cardinal(359f))
        assertEquals("NE", Format.cardinal(40f))
        assertEquals("E", Format.cardinal(95f))
        assertEquals("SO", Format.cardinal(225f))
        assertEquals("O", Format.cardinal(-90f))
        assertEquals("?", Format.cardinal(Float.NaN))
    }

    @Test
    fun coordinatesAndSigns() {
        assertEquals("47.21840° N", Format.coord(47.2184, 'N', 'S'))
        assertEquals("1.55360° O", Format.coord(-1.5536, 'E', 'O'))
        assertEquals("+2", Format.signed(2.2f))
        assertEquals("-3", Format.signed(-2.6f))
        assertEquals("0", Format.signed(0.2f))
    }

    @Test
    fun weatherLabels() {
        assertEquals("Ciel clair", Format.weatherLabel(0))
        assertEquals("Brouillard", Format.weatherLabel(45))
        assertEquals("Orage", Format.weatherLabel(95))
        assertEquals("—", Format.weatherLabel(1234))
    }

    @Test
    fun parsesOpenMeteoResponse() {
        val json = """
            {"latitude":47.2,"longitude":-1.55,
             "current":{"time":"2026-10-03T08:00","interval":900,"temperature_2m":12.4,
               "relative_humidity_2m":82,"weather_code":3,"surface_pressure":1013.2,
               "wind_speed_10m":14.3,"wind_direction_10m":315,"wind_gusts_10m":25.9}}
        """.trimIndent()
        val w = WeatherClient.parse(json, 47.2, -1.55, 1000L)
        assertEquals(12.4, w.tempC, 0.01)
        assertEquals(82, w.humidity)
        assertEquals(3, w.code)
        assertEquals(315, w.windDirDeg)
        assertEquals(25.9, w.gustKmh, 0.01)
        assertEquals(1000L, w.fetchedAtMs)
    }
}
