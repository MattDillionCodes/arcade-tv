package com.matthew.arcade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
 * Start. Label bottom-left and the Windows Phone "tilt" when pressed. Focus is a spotlight: the
 * tile lifts, glows in its own colour, and a streak of light sweeps across it once, while the
 * other tiles on Start dim (see [dimmed]).
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
    /** The colour the tile *looks*, when that isn't [color] (e.g. the banner's own background). */
    var glowTint: Int? = null

    /** Set on every other Start tile while one has focus, so the focused one stands out. */
    var dimmed = false
        set(v) {
            if (v == field) return
            field = v
            fade(dimAnim, dim, if (v) 1f else 0f, 220) { dim = it }
        }

    private var glow = 0f // 0..1, fades in with focus
    private var dim = 0f // 0..1
    private var shine = -1f // 0..1 while the light streak crosses the tile, -1 otherwise
    private val glowAnim = ValueAnimator()
    private val dimAnim = ValueAnimator()
    private val shineAnim = ValueAnimator()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(dp(12).toFloat(), BlurMaskFilter.Blur.NORMAL) }
    private val dimPaint = Paint()
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineMatrix = Matrix()
    private var shineBand = 0f
    private val scrim = Paint().apply { color = Color.argb(80, 0, 0, 0) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val loc = IntArray(2)

    init {
        setWillNotDraw(false)
        isFocusable = true
        isClickable = true
        isLongClickable = true
        defaultFocusHighlightEnabled = false
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

    /** The glow goes behind the tile, so it's drawn before the tile itself (parents don't clip). */
    override fun draw(c: Canvas) {
        if (glow > 0f) {
            glowPaint.color = glowColor()
            glowPaint.alpha = (120 * glow).toInt()
            val g = dp(2).toFloat()
            c.drawRect(-g, -g, width + g, height + g, glowPaint)
        }
        super.draw(c)
    }

    /** The tile's visible colour; near-black tiles glow white instead, or the glow would vanish. */
    private fun glowColor(): Int {
        val base = if (photo != null) Color.WHITE else glowTint ?: color
        return if (Color.luminance(base) < 0.1f) Color.WHITE else base
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (dim > 0f) {
            dimPaint.color = Color.argb((130 * dim).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, w, h, dimPaint)
        }
        if (shine >= 0f) {
            // A soft diagonal band of light, from off the left edge to off the right. One gradient
            // per tile size, slid across with a matrix, so a sweep allocates nothing per frame.
            val band = maxOf(w, h) * 0.45f
            if (band != shineBand) {
                shineBand = band
                shinePaint.shader = LinearGradient(0f, 0f, band, band * 0.6f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(110, 255, 255, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
            }
            shineMatrix.setTranslate(-band * 2 + (w + band * 3) * shine, 0f)
            shinePaint.shader.setLocalMatrix(shineMatrix)
            c.save()
            c.clipRect(0f, 0f, w, h)
            c.drawRect(0f, 0f, w, h, shinePaint)
            c.restore()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        syncLabel()
        settle()
        fade(glowAnim, glow, if (gainFocus) 1f else 0f, if (gainFocus) 260 else 160) { glow = it }
        shineAnim.cancel()
        shine = -1f
        if (gainFocus) {
            shineAnim.setFloatValues(0f, 1f)
            shineAnim.duration = 650
            shineAnim.startDelay = 90
            shineAnim.interpolator = Metro.easeOut
            shineAnim.removeAllUpdateListeners()
            shineAnim.removeAllListeners()
            shineAnim.addUpdateListener { shine = it.animatedValue as Float; invalidate() }
            shineAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { shine = -1f; invalidate() }
            })
            shineAnim.start()
        }
        invalidate()
    }

    /** Animates one of the 0..1 effect amounts, redrawing as it goes. */
    private fun fade(anim: ValueAnimator, from: Float, to: Float, ms: Long, set: (Float) -> Unit) {
        anim.cancel()
        anim.setFloatValues(from, to)
        anim.duration = ms
        anim.interpolator = Metro.easeOut
        anim.removeAllUpdateListeners()
        anim.addUpdateListener { set(it.animatedValue as Float); invalidate() }
        anim.start()
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) settle()
    }

    /** Focused tiles grow 8% and rise above their neighbours; pressed ones tilt back into the screen. */
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
    private val temp = context.text("", 46f)
    private val summary = context.text("Loading…", 18f)
    private var loaded = false
    private val feels = context.text("", 13f, Metro.regular, Color.argb(200, 255, 255, 255)).apply { maxLines = 1 }
    private val dayRow = LinearLayout(context)

    init {
        setLabel("Weather · ${Weather.PLACE}")
        addFace(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(28))
            addView(glyph, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(temp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(summary)
                addView(feels, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
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
        if (!loaded) summary.text = "Waiting For Network…"
    }

    fun bind(w: Weather) {
        loaded = true
        glyph.visibility = VISIBLE
        temp.visibility = VISIBLE
        glyph.sky = w.sky
        temp.text = "${w.temp}°"
        summary.text = w.summary
        feels.text = "Feels Like ${w.feels}°"
        dayRow.removeAllViews()
        w.days.drop(1).take(3).forEach { d ->
            dayRow.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(context.text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), 15f))
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
        weekday.text = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        dayNumber.text = now.dayOfMonth.toString()
        month.text = now.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
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
