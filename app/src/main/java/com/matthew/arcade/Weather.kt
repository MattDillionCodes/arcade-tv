package com.matthew.arcade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Net {
    /** Network calls (forecast, city search): a slow request queues only behind other requests. */
    val io = Executors.newSingleThreadExecutor()
    /** Disk and decoding (app list, wallpapers), so an offline TV never delays them. */
    val disk = Executors.newSingleThreadExecutor()

    fun json(url: String): JSONObject = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Arcade/0.1")
        try { JSONObject(inputStream.use { String(it.readBytes()) }) } finally { disconnect() }
    }
}

/** A city from Open-Meteo's place search (free, no key, like the forecast). */
class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
    companion object {
        fun search(query: String): List<Place> {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val results = Net.json("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=4&language=en&format=json")
                .optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                Place(
                    r.getString("name"),
                    listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotEmpty() }.distinct().joinToString(", "),
                    r.getDouble("latitude"),
                    r.getDouble("longitude"),
                )
            }
        }
    }
}

enum class Sky { CLEAR, NIGHT, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

class Day(val date: LocalDate, val hi: Int, val lo: Int, val sky: Sky)

/** Current conditions and the next few days from Open-Meteo (free, no API key). */
class Weather(val temp: Int, val feels: Int, val sky: Sky, val summary: String, val days: List<Day>) {
    companion object {
        fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): Weather {
            val root = Net.json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4" +
                "&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}&timezone=auto")
            val now = root.getJSONObject("current")
            val code = now.getInt("weather_code")
            val day = now.getInt("is_day") == 1
            val daily = root.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            // A day with a missing value (JSON null) is left out rather than failing the forecast.
            val days = (0 until dates.length()).mapNotNull { i ->
                val hi = daily.getJSONArray("temperature_2m_max").optDouble(i)
                val lo = daily.getJSONArray("temperature_2m_min").optDouble(i)
                if (hi.isNaN() || lo.isNaN()) return@mapNotNull null
                Day(LocalDate.parse(dates.getString(i)), hi.roundToInt(), lo.roundToInt(),
                    sky(daily.getJSONArray("weather_code").optInt(i, 3), true))
            }
            return Weather(
                now.getDouble("temperature_2m").roundToInt(),
                now.getDouble("apparent_temperature").roundToInt(),
                sky(code, day), summary(code), days,
            )
        }

        /** WMO weather code -> one of our glyphs. */
        private fun sky(code: Int, day: Boolean) = when (code) {
            0 -> if (day) Sky.CLEAR else Sky.NIGHT
            1, 2 -> if (day) Sky.PARTLY else Sky.NIGHT
            3 -> Sky.CLOUD
            45, 48 -> Sky.FOG
            in 51..67, in 80..82 -> Sky.RAIN
            in 71..77, 85, 86 -> Sky.SNOW
            in 95..99 -> Sky.STORM
            else -> Sky.CLOUD
        }

        /** Title Case, like every other caption in Arcade. */
        private fun summary(code: Int) = when (code) {
            0 -> "Clear"
            1 -> "Mostly Clear"
            2 -> "Partly Cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Showers"
            85, 86 -> "Snow Showers"
            in 95..99 -> "Thunderstorms"
            else -> "—"
        }
    }
}

/**
 * Flat, single-colour weather glyphs drawn in code (emoji would break the Metro look: Windows
 * Phone's weather tiles used monochrome Segoe glyphs).
 */
class WeatherGlyph(context: Context) : View(context) {
    var sky = Sky.CLEAR
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bolt = Path()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        stroke.strokeWidth = s * 0.06f
        c.save()
        c.translate((width - s) / 2, (height - s) / 2)
        when (sky) {
            Sky.CLEAR -> sun(c, s * 0.5f, s * 0.5f, s * 0.2f)
            Sky.NIGHT -> moon(c, s * 0.5f, s * 0.5f, s * 0.3f)
            Sky.PARTLY -> { sun(c, s * 0.36f, s * 0.36f, s * 0.13f); cloud(c, s * 0.58f, s * 0.6f, s * 0.6f) }
            Sky.CLOUD -> cloud(c, s * 0.5f, s * 0.52f, s * 0.8f)
            Sky.FOG -> for (i in 0..2) {
                val y = s * (0.34f + i * 0.16f)
                c.drawLine(s * (0.18f + i * 0.05f), y, s * (0.82f - i * 0.05f), y, stroke)
            }
            Sky.RAIN -> {
                cloud(c, s * 0.5f, s * 0.38f, s * 0.74f)
                for (i in 0..2) {
                    val x = s * (0.32f + i * 0.18f)
                    c.drawLine(x, s * 0.66f, x - s * 0.06f, s * 0.84f, stroke)
                }
            }
            Sky.SNOW -> {
                cloud(c, s * 0.5f, s * 0.38f, s * 0.74f)
                for (i in 0..2) c.drawCircle(s * (0.3f + i * 0.2f), s * (0.74f + (i % 2) * 0.08f), s * 0.045f, fill)
            }
            Sky.STORM -> {
                cloud(c, s * 0.5f, s * 0.36f, s * 0.74f)
                c.drawPath(bolt.apply {
                    reset()
                    moveTo(s * 0.54f, s * 0.58f); lineTo(s * 0.42f, s * 0.76f); lineTo(s * 0.52f, s * 0.76f)
                    lineTo(s * 0.44f, s * 0.94f); lineTo(s * 0.64f, s * 0.7f); lineTo(s * 0.54f, s * 0.7f); close()
                }, fill)
            }
        }
        c.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r, fill)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(cx + (r * 1.45f * cos(a)).toFloat(), cy + (r * 1.45f * sin(a)).toFloat(),
                cx + (r * 1.95f * cos(a)).toFloat(), cy + (r * 1.95f * sin(a)).toFloat(), stroke)
        }
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val disc = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        disc.op(bite, Path.Op.DIFFERENCE)
        c.drawPath(disc, fill)
    }

    /** A filled cloud [w] wide, its body centred on (cx, cy). */
    private fun cloud(c: Canvas, cx: Float, cy: Float, w: Float) {
        val path = Path().apply {
            addRoundRect(cx - w / 2, cy, cx + w / 2, cy + w * 0.3f, w * 0.15f, w * 0.15f, Path.Direction.CW)
            addCircle(cx - w * 0.17f, cy + w * 0.04f, w * 0.19f, Path.Direction.CW)
            addCircle(cx + w * 0.08f, cy - w * 0.04f, w * 0.26f, Path.Direction.CW)
        }
        c.drawPath(path, fill)
    }
}
