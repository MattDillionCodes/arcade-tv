package com.matthew.arcade

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** One choice in the menu: a text option, or (with [swatch]) a colour square. */
class MenuItem(val label: String, val swatch: Int? = null, val action: () -> Unit)

/**
 * The long-press menu, styled as Windows Phone's expanded app bar: a dark grey band across the
 * bottom with the app's name and its options in a row.
 */
class AppMenu(private val activity: Activity, private val onClosed: () -> Unit) {
    private fun dp(v: Int) = activity.dp(v)
    private val title = activity.text("", 28f)
    private val row = LinearLayout(activity).apply { clipChildren = false }
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(31, 31, 31))
        setPadding(dp(48), dp(18), dp(48), dp(28))
        visibility = View.GONE
        isClickable = true
        clipChildren = false
        addView(title)
        addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }
    var isOpen = false
        private set

    fun show(name: String, items: List<MenuItem>) {
        generation++
        title.text = name.lowercase()
        row.removeAllViews()
        items.forEachIndexed { i, item ->
            row.addView(option(item), LinearLayout.LayoutParams(
                if (item.swatch != null) dp(34) else WRAP_CONTENT,
                if (item.swatch != null) dp(34) else WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (i > 0) marginStart = dp(if (item.swatch != null) 6 else 10)
            })
        }
        view.animate().cancel()
        if (!isOpen) {
            isOpen = true
            view.visibility = View.VISIBLE
            view.translationY = dp(160).toFloat()
            view.animate().translationY(0f).setInterpolator(Metro.easeOut).setDuration(320).start()
        } else {
            view.translationY = 0f
        }
        row.getChildAt(0)?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        view.animate().translationY(view.height.toFloat()).setInterpolator(Metro.easeIn).setDuration(180)
            .withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
        onClosed()
    }

    /** Bumped by every [show], so a click can tell whether its action opened a sub-menu (colours). */
    private var generation = 0

    private fun option(item: MenuItem) = TextView(activity).apply {
        text = if (item.swatch == null) item.label else ""
        textSize = 20f
        typeface = Metro.light
        gravity = Gravity.CENTER
        isFocusable = true
        defaultFocusHighlightEnabled = false
        if (item.swatch == null) setPadding(dp(14), dp(6), dp(14), dp(8))
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(item.swatch ?: if (focused) Color.WHITE else Color.TRANSPARENT)
                if (focused) setStroke(dp(3), Color.WHITE)
            }
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener {
            val shown = generation
            item.action()
            if (generation == shown) close()
        }
    }
}
