package com.matthew.arcade

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Windows Phone 8.1's Action Center, for a TV: pulled down from the top (Up from the top row of
 * tiles, or the Menu button) with quick-action squares, volume, HDMI inputs, accent colour, tile
 * style and wallpaper.
 */
class ControlPanel(private val activity: Activity, private val prefs: Prefs, private val host: Host) {
    private fun dp(v: Int) = activity.dp(v)

    interface Host {
        val weather: Weather?
        fun accentChanged()
        fun photoTilesChanged()
        fun wallpaperFromPhone()
        fun nextWallpaper()
        fun defaultWallpaper()
        fun chooseWeatherCity()
    }

    private val audio = activity.getSystemService(AudioManager::class.java)
    private val tvInputs = activity.getSystemService(TvInputManager::class.java)
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(48), dp(24), dp(48), dp(28))
        clipChildren = false
        clipToPadding = false
    }
    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.argb(248, 12, 13, 15))
        visibility = View.GONE
        isClickable = true
        clipChildren = false
        addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    var isOpen = false
        private set

    fun open() {
        if (isOpen) return
        isOpen = true
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        build()
        view.visibility = View.VISIBLE
        view.translationY = -dp(420).toFloat()
        view.animate().translationY(0f).setInterpolator(Metro.easeOut).setDuration(420).start()
        view.post { content.findViewWithTag<View>("q:0")?.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        // Closing (or closed) overlays never take focus, even mid-animation while still on screen.
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.animate().translationY(-view.height.toFloat()).setInterpolator(Metro.easeIn).setDuration(220)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    /** Rebuilds everything (cheap: ~40 views) and puts focus back on the control that was focused. */
    private fun build() {
        val focusedTag = view.findFocus()?.tag
        content.removeAllViews()
        val accent = prefs.accent

        val now = LocalDateTime.now()
        content.addView(LinearLayout(activity).apply {
            addView(activity.text(now.format(DateTimeFormatter.ofPattern("h:mm")), 54f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.text(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), 18f))
                host.weather?.let { addView(activity.text("${it.temp}° ${it.summary} · ${prefs.city}", 15f, Metro.regular, Metro.dimText)) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(18); topMargin = dp(10) })
        })

        val columns = LinearLayout(activity).apply { clipChildren = false }
        content.addView(columns, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val left = column()
        val right = column()
        columns.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.1f))
        columns.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(40) })

        // Left: what you'd reach for most.
        section(left, "Quick Actions")
        left.addView(row(listOf(
            "Network" to Settings.ACTION_WIFI_SETTINGS,
            "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Display" to Settings.ACTION_DISPLAY_SETTINGS,
            "Sound" to Settings.ACTION_SOUND_SETTINGS,
            "Settings" to Settings.ACTION_SETTINGS,
        ).mapIndexed { i, (name, action) ->
            Tile(activity, TileSize.SMALL).apply {
                tag = "q:$i"
                color = accent
                setLabel(name)
                label.textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(60))
                setOnClickListener { runCatching { activity.startActivity(Intent(action)) } }
            }
        }, gap = 8))

        section(left, "Volume")
        left.addView(VolumeBar(accent).apply { tag = "volume" }, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))

        val inputs = inputChips()
        if (inputs.isNotEmpty()) {
            section(left, "Inputs")
            left.addView(row(inputs))
        }

        // Right: how Arcade looks.
        section(right, "Accent Colour")
        right.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            Metro.accents.chunked(10).forEachIndexed { r, line ->
                addView(row(line.mapIndexed { i, c ->
                    Swatch(c, selected = c == accent).apply {
                        tag = "swatch:${r * 10 + i}"
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                        setOnClickListener { prefs.accent = c; host.accentChanged(); build() }
                    }
                }, gap = 6), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (r > 0) topMargin = dp(6) })
            }
        })

        section(right, "Tiles")
        right.addView(row(listOf(false, true).map { photo ->
            chip(if (photo) "Photo" else "Solid", "tiles:$photo", selected = prefs.photoTiles == photo, accent) {
                prefs.photoTiles = photo
                host.photoTilesChanged()
                build()
            }
        }))

        section(right, "Wallpaper")
        right.addView(row(listOf(
            chip("From Phone", "wp:phone", false, accent) { host.wallpaperFromPhone() },
            chip("Next", "wp:next", false, accent) { host.nextWallpaper() },
            chip("Default", "wp:default", prefs.wallpaper == null, accent) { host.defaultWallpaper(); build() },
        )))

        section(right, "Weather")
        right.addView(row(listOf(chip(prefs.city, "wx:city", false, accent) { host.chooseWeatherCity() })))

        focusedTag?.let { tag -> content.post { content.findViewWithTag<View>(tag)?.requestFocus() } }
    }

    /** One chip per physical HDMI port (none on the emulator, so the section hides itself). */
    private fun inputChips(): List<View> {
        val all = runCatching { tvInputs?.tvInputList.orEmpty() }.getOrDefault(emptyList())
        return all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.loadLabel(activity).toString() }
            .map { port ->
                val name = (port.loadCustomLabel(activity) ?: port.loadLabel(activity)).toString()
                chip(name, "in:${port.id}", false, prefs.accent) {
                    // TCL's player only matches VIEW with the channel MIME type attached.
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW)
                            .setDataAndType(TvContract.buildChannelUriForPassthroughInput(port.id), "vnd.android.cursor.item/channel")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
    }

    // --- building blocks ----------------------------------------------------------------------

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
    }

    private fun section(parent: ViewGroup, title: String) {
        parent.addView(activity.text(title, 15f, Metro.regular, Metro.dimText),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(8) })
    }

    private fun row(items: List<View>, gap: Int = 10) = LinearLayout(activity).apply {
        clipChildren = false
        items.forEachIndexed { i, v ->
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (i > 0) lp.marginStart = dp(gap)
            addView(v, lp)
        }
    }

    /** Square-cornered toggle: accent when selected, white while focused. */
    private fun chip(label: String, tag: String, selected: Boolean, accent: Int, onClick: () -> Unit) = TextView(activity).apply {
        this.tag = tag
        text = label
        textSize = 16f
        typeface = Metro.regular
        isFocusable = true
        defaultFocusHighlightEnabled = false
        setPadding(dp(14), dp(7), dp(14), dp(8))
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(when { focused -> Color.WHITE; selected -> accent; else -> Color.TRANSPARENT })
                setStroke(dp(2), if (focused) Color.WHITE else if (selected) accent else Color.argb(110, 255, 255, 255))
            }
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    private inner class Swatch(private val c: Int, private val selected: Boolean) : View(activity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = true
            defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.style = Paint.Style.FILL
            p.color = c
            canvas.drawRect(0f, 0f, w, h, p)
            if (isFocused || selected) {
                p.style = Paint.Style.STROKE
                p.color = Color.WHITE
                p.strokeWidth = dp(if (isFocused) 3 else 2).toFloat()
                val inset = if (isFocused) p.strokeWidth / 2 else dp(5).toFloat()
                canvas.drawRect(inset, inset, w - inset, h - inset, p)
            }
        }
    }

    /** Metro slider: a flat track, accent fill and a white block thumb; Left/Right change it while focused. */
    private inner class VolumeBar(private val accent: Int) : View(activity) {
        private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        private var value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15).toFloat(); typeface = Metro.regular }

        init {
            isFocusable = true
            defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return super.onKeyDown(keyCode, event)
            }
            value = (value + delta).coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val textW = dp(64).toFloat()
            val track = width - textW
            val cy = height / 2f
            label.color = if (isFocused) Color.WHITE else Metro.dimText
            canvas.drawText(if (isFocused) "◀ $value ▶" else "$value", 0f, cy - (label.descent() + label.ascent()) / 2, label)
            val frac = if (max == 0) 0f else value / max.toFloat()
            p.color = Color.argb(if (isFocused) 110 else 60, 255, 255, 255)
            canvas.drawRect(textW, cy - dp(3), width.toFloat(), cy + dp(3), p)
            p.color = accent
            canvas.drawRect(textW, cy - dp(3), textW + track * frac, cy + dp(3), p)
            p.color = Color.WHITE
            val x = textW + track * frac
            canvas.drawRect(x - dp(5), cy - dp(12), x + dp(5), cy + dp(12), p)
        }
    }
}
