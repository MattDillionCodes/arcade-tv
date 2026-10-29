package com.matthew.arcade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/** Tile sizes in grid units, as on Windows Phone: small 1x1, medium 2x2, wide 4x2, large 4x4. */
enum class TileSize(val cols: Int, val rows: Int) { SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2), LARGE(4, 4) }

/**
 * A Metro tile: a flat square of colour, or (photo mode) a window onto the wallpaper behind
 * Start. Label bottom-left, a focus ring around it, and the Windows Phone "tilt" when pressed.
 */
open class Tile(context: Context, val size: TileSize) : FrameLayout(context) {
    var color = Metro.defaultAccent
        set(v) { field = v; restyle() }

    /** The app this tile launches, if any (its custom colour is stored under this key). */
    var app: App? = null

    /** Photo-through mode (WP 8.1): the full-screen wallpaper, of which this tile shows its own slice. */
    var photo: Bitmap? = null
        set(v) { field = v; restyle() }

    val label = context.text("", 13f, Metro.regular).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(4).toFloat() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(140, 0, 0, 0); strokeWidth = dp(2).toFloat() }
    private val scrim = Paint().apply { color = Color.argb(80, 0, 0, 0) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val loc = IntArray(2)

    init {
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true
        isLongClickable = true
        defaultFocusHighlightEnabled = false
        outlineProvider = ViewOutlineProvider.BOUNDS // so the lift casts a real shadow
        addView(label, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, dp(8), dp(7))
        })
    }

    /** White label, or a near-black one on a light tile (e.g. Play Store's white banner). */
    private fun restyle() {
        val light = photo == null && Color.luminance(color) > 0.6f
        label.setTextColor(if (light) Color.argb(220, 0, 0, 0) else Color.WHITE)
        invalidate()
    }

    /** For tiles whose art already shows the name: the label appears only while focused. */
    var labelOnFocus = false
        set(v) { field = v; syncLabel() }

    fun setLabel(s: String?) {
        label.text = s ?: ""
        syncLabel()
    }

    private fun syncLabel() {
        label.visibility = when {
            label.text.isEmpty() -> GONE
            labelOnFocus && !isFocused -> INVISIBLE
            else -> VISIBLE
        }
    }

    override fun onDraw(c: Canvas) {
        // Parents don't clip (so focused tiles can lift over their neighbours): clip ourselves.
        c.save()
        c.clipRect(0, 0, width, height)
        val p = photo
        if (p == null) {
            c.drawColor(color)
        } else {
            // Where this tile sits on screen decides which part of the wallpaper shows through it.
            getLocationInWindow(loc)
            c.drawBitmap(p, -loc[0].toFloat(), -loc[1].toFloat(), bitmapPaint)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        }
        c.restore()
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (isFocused) {
            // A white ring just outside the tile (parents don't clip), edged with a dark halo so
            // it reads on any tile colour and any wallpaper, rather than a line inside the art.
            val r = dp(3) + ring.strokeWidth / 2
            c.drawRect(-r, -r, width + r, height + r, ring)
            val h = r + ring.strokeWidth / 2 + halo.strokeWidth / 2
            c.drawRect(-h, -h, width + h, height + h, halo)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        syncLabel()
        settle()
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) settle()
    }

    /** Focused tiles grow 8% and lift onto a shadow; pressed ones tilt back into the screen. */
    private fun settle() {
        val scale = when {
            isPressed -> 0.95f
            isFocused -> 1.08f
            else -> 1f
        }
        translationZ = if (isFocused) dp(10).toFloat() else 0f
        animate().scaleX(scale).scaleY(scale).setInterpolator(Metro.easeOut).setDuration(if (isPressed) 90 else 220).start()
    }
}

/** A live tile: cycles through [faces], turning over on its horizontal axis like Windows Phone's flip tiles. */
open class LiveTile(context: Context, size: TileSize) : Tile(context, size) {
    private val faces = mutableListOf<View>()
    private var current = 0

    init { cameraDistance = 9000 * resources.displayMetrics.density }

    fun addFace(face: View) {
        faces += face
        face.visibility = if (faces.size == 1) VISIBLE else INVISIBLE
        addView(face, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT)) // under the label
    }

    open fun flip() {
        if (faces.size < 2) return
        val from = faces[current]
        current = (current + 1) % faces.size
        val to = faces[current]
        animate().rotationX(90f).setDuration(220).setInterpolator(Metro.easeIn).withEndAction {
            from.visibility = INVISIBLE
            to.visibility = VISIBLE
            rotationX = -90f
            animate().rotationX(0f).setDuration(480).setInterpolator(Metro.easeOut).start()
        }.start()
    }
}

/** Wide weather tile: now, then turns over to the next three days. */
class WeatherTile(context: Context) : LiveTile(context, TileSize.WIDE) {
    private val glyph = WeatherGlyph(context)
    private val temp = context.text("", 52f)
    private val summary = context.text("loading…", 18f)
    private var loaded = false
    private val feels = context.text("", 13f, Metro.regular, Color.argb(200, 255, 255, 255)).apply { maxLines = 1 }
    private val dayRow = LinearLayout(context)

    init {
        setLabel("weather · ${Weather.PLACE}")
        addFace(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(28))
            addView(glyph, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(temp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(summary)
                addView(feels, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        })
        addFace(dayRow.apply { setPadding(dp(10), dp(12), dp(10), dp(28)) })
        // Until the first forecast: just the caption, no made-up sun or "--°".
        glyph.visibility = GONE
        temp.visibility = GONE
    }

    /** Nothing to turn over to until there's a forecast on the back. */
    override fun flip() {
        if (loaded) super.flip()
    }

    /** A fetch failed before we ever had data (no network yet, e.g. right after boot). */
    fun failed() {
        if (!loaded) summary.text = "waiting for network…"
    }

    fun bind(w: Weather) {
        loaded = true
        glyph.visibility = VISIBLE
        temp.visibility = VISIBLE
        glyph.sky = w.sky
        temp.text = "${w.temp}°"
        summary.text = w.summary
        feels.text = "feels like ${w.feels}°"
        dayRow.removeAllViews()
        w.days.drop(1).take(3).forEach { d ->
            dayRow.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(context.text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase(), 15f))
                addView(WeatherGlyph(context).apply { sky = d.sky },
                    LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(0, dp(5), 0, dp(5)) })
                addView(context.text("${d.hi}°  ${d.lo}°", 14f, Metro.regular))
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
    }
}

/** Medium clock tile: the time and weekday, turning over to the date. */
class ClockTile(context: Context) : LiveTile(context, TileSize.MEDIUM) {
    private val time = context.text("", 40f)
    private val weekday = context.text("", 15f, Metro.regular)
    private val dayNumber = context.text("", 56f)
    private val month = context.text("", 15f, Metro.regular)

    init {
        setLabel(null)
        addFace(column(time, weekday))
        addFace(column(dayNumber, month))
        tick()
    }

    private fun column(big: View, small: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        setPadding(dp(12), dp(10), dp(10), dp(12))
        addView(big)
        addView(small, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    fun tick() {
        val now = LocalDateTime.now()
        time.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
        weekday.text = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).lowercase()
        dayNumber.text = now.dayOfMonth.toString()
        month.text = now.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).lowercase()
    }
}

enum class Icon { SLIDERS, PICTURE, INPUT, LIST }

/** Monochrome line icons for the small utility tiles. */
class IconView(context: Context, private val icon: Icon) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        p.strokeWidth = s * 0.07f
        c.translate((width - s) / 2, (height - s) / 2)
        when (icon) {
            Icon.SLIDERS -> for (i in 0..2) {
                val y = s * (0.25f + i * 0.25f)
                c.drawLine(s * 0.15f, y, s * 0.85f, y, p)
                c.drawCircle(s * floatArrayOf(0.65f, 0.32f, 0.55f)[i], y, s * 0.085f, fill)
            }
            Icon.PICTURE -> {
                c.drawRect(RectF(s * 0.14f, s * 0.22f, s * 0.86f, s * 0.78f), p)
                c.drawPath(android.graphics.Path().apply {
                    moveTo(s * 0.2f, s * 0.72f); lineTo(s * 0.42f, s * 0.46f); lineTo(s * 0.58f, s * 0.62f)
                    lineTo(s * 0.68f, s * 0.52f); lineTo(s * 0.8f, s * 0.72f); close()
                }, fill)
                c.drawCircle(s * 0.66f, s * 0.36f, s * 0.06f, fill)
            }
            Icon.INPUT -> {
                c.drawRect(RectF(s * 0.12f, s * 0.2f, s * 0.88f, s * 0.68f), p)
                c.drawLine(s * 0.36f, s * 0.82f, s * 0.64f, s * 0.82f, p)
                c.drawLine(s * 0.5f, s * 0.68f, s * 0.5f, s * 0.82f, p)
            }
            Icon.LIST -> for (i in 0..2) {
                val y = s * (0.28f + i * 0.22f)
                c.drawRect(s * 0.14f, y - s * 0.07f, s * 0.28f, y + s * 0.07f, fill)
                c.drawLine(s * 0.4f, y, s * 0.86f, y, p)
            }
        }
    }
}
