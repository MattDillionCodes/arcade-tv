package com.matthew.arcade

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Weather": a full-screen page for choosing the weather city. Type with the TV's keyboard,
 * press Enter, pick from up to four matches (Open-Meteo place search).
 */
class CityPicker(
    private val activity: Activity,
    private val prefs: Prefs,
    private val accent: () -> Int,
    private val onPicked: () -> Unit,
) {
    private fun dp(v: Int) = activity.dp(v)

    private val current = activity.text("", 18f, Metro.regular, Metro.dimText)
    private val status = activity.text("", 18f, Metro.regular, Metro.dimText)
    private val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var searchId = 0

    private val field = EditText(activity).apply {
        textSize = 24f
        typeface = Metro.light
        setTextColor(Color.WHITE)
        setHintTextColor(Metro.dimText)
        hint = "Type A City, Then Press Enter"
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dp(14), dp(10), dp(14), dp(12))
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(Color.argb(if (focused) 60 else 30, 255, 255, 255))
                setStroke(dp(2), if (focused) accent() else Color.argb(90, 255, 255, 255))
            }
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                search(text.toString())
                true
            } else false
        }
    }

    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.argb(245, 0, 0, 0))
        visibility = View.GONE
        isClickable = true
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(48), dp(72), dp(36))
            addView(activity.text("Weather", 64f))
            addView(activity.text("Choose Your City", 22f, color = Metro.dimText))
            addView(current, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(field, LinearLayout.LayoutParams(dp(520), WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(results, LinearLayout.LayoutParams(dp(520), WRAP_CONTENT).apply { topMargin = dp(6) })
            // Open-Meteo's data is CC BY 4.0: credit it where the weather is set up.
            addView(activity.text("Weather Data By Open-Meteo.com", 13f, Metro.regular, Metro.dimText),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    var isOpen = false
        private set

    fun open() {
        if (isOpen) return
        isOpen = true
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        current.text = prefs.city?.let { "Now: $it" } ?: "No City Chosen Yet"
        status.text = ""
        results.removeAllViews()
        field.setText("")
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = dp(40).toFloat()
        view.animate().alpha(1f).translationY(0f).setInterpolator(Metro.easeOut).setDuration(380).start()
        field.requestFocus()
        field.post { activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        searchId++ // a search still in flight is now stale
        // Closing (or closed) overlays never take focus, even mid-animation while still on screen.
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        view.animate().alpha(0f).translationY(dp(40).toFloat()).setInterpolator(Metro.easeIn).setDuration(200)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val id = ++searchId
        status.text = "Searching…"
        results.removeAllViews()
        Net.io.execute {
            val found = runCatching { Place.search(query) }
            activity.runOnUiThread {
                if (id != searchId) return@runOnUiThread
                val places = found.getOrNull()
                status.text = when {
                    places == null -> "Couldn't Search — Check The TV's Connection"
                    places.isEmpty() -> "No Matches — Try Another Spelling"
                    else -> "Pick One"
                }
                places?.forEachIndexed { i, p ->
                    results.addView(choice(p), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (i > 0) topMargin = dp(6)
                    })
                }
                // Put the keyboard away so it doesn't cover the matches; OK on the field brings it back.
                if (results.childCount > 0) {
                    activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
                    results.getChildAt(0).requestFocus()
                }
            }
        }
    }

    /** One match: the city, then its region and country, white while focused. */
    private fun choice(place: Place) = TextView(activity).apply {
        text = if (place.detail.isEmpty()) place.name else "${place.name}  ·  ${place.detail}"
        textSize = 20f
        typeface = Metro.regular
        maxLines = 1
        isFocusable = true
        defaultFocusHighlightEnabled = false
        setPadding(dp(14), dp(9), dp(14), dp(10))
        fun paint(focused: Boolean) {
            setBackgroundColor(if (focused) Color.WHITE else Color.argb(30, 255, 255, 255))
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener {
            prefs.city = place.name
            prefs.cityLat = place.lat
            prefs.cityLon = place.lon
            onPicked()
            close()
        }
    }
}
