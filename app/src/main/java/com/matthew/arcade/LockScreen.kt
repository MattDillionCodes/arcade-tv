package com.matthew.arcade

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * The idle screen, after Windows Phone's lock screen: the wallpaper, a big clock with the date
 * and weather beneath it, bottom-left. The text drifts a little every minute so no pixel stays lit
 * in one place (burn-in protection on OLED and plasma TVs).
 */
class LockScreen(private val activity: Activity) {
    private fun dp(v: Int) = activity.dp(v)

    private val backdrop = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val time = activity.text("", 120f)
    private val date = activity.text("", 30f)
    private val glyph = WeatherGlyph(activity)
    private val weather = activity.text("", 22f, Metro.regular)
    private val weatherRow = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(glyph, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(weather, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
    }
    private val block = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(time)
        addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2); marginStart = dp(4) })
        addView(weatherRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14); marginStart = dp(4) })
    }

    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
        addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Darker toward the bottom-left, where the text sits, so it reads on any wallpaper.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TR_BL,
                intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(190, 0, 0, 0)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(block, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(72)
            bottomMargin = dp(64)
        })
    }

    var isShowing = false
        private set

    private val drift = object : Runnable {
        override fun run() {
            block.animate().translationX(Random.nextInt(-dp(24), dp(24) + 1).toFloat())
                .translationY(Random.nextInt(-dp(18), dp(18) + 1).toFloat())
                .setDuration(2_000).start()
            view.postDelayed(this, 60_000)
        }
    }

    fun show(wallpaper: Bitmap?, forecast: Weather?, city: String) {
        if (isShowing) return
        isShowing = true
        backdrop.setImageBitmap(wallpaper)
        if (forecast == null) {
            weatherRow.visibility = View.GONE
        } else {
            weatherRow.visibility = View.VISIBLE
            glyph.sky = forecast.sky
            weather.text = "${forecast.temp}°  ${forecast.summary}  ·  $city"
        }
        tick()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(900).start()
        view.postDelayed(drift, 60_000)
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        view.removeCallbacks(drift)
        view.animate().alpha(0f).setDuration(250).withEndAction {
            view.visibility = View.GONE
            backdrop.setImageBitmap(null)
        }.start()
    }

    fun tick() {
        val now = LocalDateTime.now()
        time.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
        date.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
    }
}
