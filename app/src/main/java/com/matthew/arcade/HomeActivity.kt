package com.matthew.arcade

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Arcade: a Windows Phone-style Start screen for Android TV. A panorama with two sections, "start"
 * (live tiles, a hero tile, pinned apps) and "apps" (everything, A to Z), scrolling under a
 * greeting, date, weather and clock that stay put.
 */
class HomeActivity : Activity(), ControlPanel.Host {
    private lateinit var prefs: Prefs
    private var apps = emptyList<App>()
    override var weather: Weather? = null
    private var wallpaper: Bitmap? = null

    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var shade: View
    private lateinit var title: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var date: TextView
    private lateinit var clock: TextView
    private lateinit var statusGlyph: WeatherGlyph
    private lateinit var statusTemp: TextView
    private lateinit var panorama: Panorama
    private lateinit var startGrid: FrameLayout
    private lateinit var appsGrid: GridLayout
    private lateinit var appsScroll: ScrollView
    private lateinit var appsSection: View
    private lateinit var weatherTile: WeatherTile
    private lateinit var clockTile: ClockTile
    private lateinit var controls: ControlPanel
    private lateinit var menu: AppMenu
    private lateinit var drop: WallpaperDrop
    private lateinit var picker: CityPicker
    private lateinit var toast: TextView

    /** Tag of whatever had focus before an overlay opened, to put focus back afterwards. */
    private var returnTag: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        shade = View(this).apply { setBackgroundColor(Color.argb(90, 0, 0, 0)) }
        greeting = text("", 64f)
        date = text("", 20f, Metro.regular, Color.argb(200, 255, 255, 255))
        title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(greeting, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6); marginStart = dp(4) })
        }
        clock = text("", 26f)
        statusGlyph = WeatherGlyph(this).apply { visibility = View.GONE } // shown with the first forecast
        statusTemp = text("", 20f)
        weatherTile = WeatherTile(this).apply {
            tag = "weather"
            setPlace(prefs.city)
            setOnClickListener { openPicker() }
        }
        clockTile = ClockTile(this).apply { tag = "clock"; setOnClickListener { openControls() } }

        startGrid = FrameLayout(this).apply { clipChildren = false }
        appsGrid = GridLayout(this).apply { columnCount = 3; clipChildren = false }
        appsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(appsGrid)
        }
        // The section doesn't clip (focused tiles grow past their edges), which would also let the
        // scrolled list draw outside its box; a plain frame around it clips it again.
        appsSection = section("Apps", FrameLayout(this).apply { addView(appsScroll) }, LinearLayout.LayoutParams(dp(APP_W * 3 + 48), dp(300)))
        val content = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(48), dp(8), dp(48), 0)
            addView(section("Start", startGrid, LinearLayout.LayoutParams(0, span(ROWS))))
            addView(appsSection, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(72) })
        }
        panorama = Panorama(this, appsSection).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            addView(content)
            // The greeting stays put like the clock (no parallax); photo tiles redraw as they move.
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (prefs.photoTiles) invalidateTiles()
            }
        }

        controls = ControlPanel(this, prefs, this)
        menu = AppMenu(this) { restoreFocus() }
        drop = WallpaperDrop(this, { prefs.accent }) { file -> useWallpaper(file.name) }
        // The picker closes itself after a pick; focus goes back once it has.
        picker = CityPicker(this, prefs, { prefs.accent }) { weatherCityChanged(); root.post { restoreFocus() } }
        toast = text("", 18f, Metro.regular).apply {
            setPadding(dp(48), dp(14), dp(48), dp(14))
            visibility = View.GONE
        }

        root = FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(title, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(44); topMargin = dp(14) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(statusGlyph, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(statusTemp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6); marginEnd = dp(20) })
                addView(clock)
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dp(34); rightMargin = dp(48) })
            addView(panorama, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(118) })
            addView(text("▲ Action Center     Hold OK For Options     ☰ Menu", 13f, Metro.regular, Color.argb(110, 255, 255, 255)),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(48); bottomMargin = dp(22) })
            addView(controls.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(menu.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(drop.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(picker.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(toast, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) loadWallpaper()
            }
        }
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> spotlight(now) }
        loadApps()
        tick()
    }

    // --- Start ---------------------------------------------------------------------------------

    private fun loadApps() {
        apps = Apps.load(this)
        buildStart()
        buildAppList()
    }

    private fun buildStart() {
        val focusedTag = currentFocus?.takeIf { it.parent === startGrid }?.tag
        startGrid.removeAllViews()
        val hero = pickHero(apps, prefs.hero, prefs.launches())
        val tiles = mutableListOf<Tile>()
        hero?.let { tiles += appTile(it, TileSize.LARGE).apply { tag = "hero" } }
        tiles += weatherTile
        tiles += clockTile
        tiles += utilityTile(Icon.SLIDERS, "u:controls") { openControls() }
        tiles += utilityTile(Icon.PICTURE, "u:wallpaper") { openDrop() }
        tiles += utilityTile(Icon.INPUT, "u:inputs") { openControls() }
        tiles += utilityTile(Icon.LIST, "u:apps") { firstAppRow()?.requestFocus() }
        pinned(hero).forEach { tiles += appTile(it, TileSize.MEDIUM) }
        place(tiles)
        applyTileLook()
        val target = focusedTag?.let { startGrid.findViewWithTag<View>(it) } ?: startGrid.getChildAt(0)
        if (currentFocus == null || focusedTag != null) target?.requestFocus()
    }

    /**
     * While a Start tile has focus, the other app and utility tiles dim slightly. The live weather
     * and clock tiles never dim: they're there to be read, not chosen.
     */
    private fun spotlight(focused: View?) {
        val inStart = focused?.parent === startGrid
        for (i in 0 until startGrid.childCount) {
            val tile = startGrid.getChildAt(i) as Tile
            tile.dimmed = inStart && tile !== focused && tile !is LiveTile
        }
    }

    // Focus set before the window had it (first launch, returning Home) never fires the listener.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) spotlight(currentFocus)
    }

    /** First fit, column by column, into a [ROWS]-tall grid: how Windows Phone flows its tiles. */
    private fun place(tiles: List<Tile>) {
        val taken = HashSet<Long>()
        fun cell(c: Int, r: Int) = c.toLong() shl 32 or r.toLong()
        fun fits(c: Int, r: Int, s: TileSize) = r + s.rows <= ROWS &&
            (0 until s.cols).all { dc -> (0 until s.rows).all { dr -> cell(c + dc, r + dr) !in taken } }
        var width = 0
        for (tile in tiles) {
            val s = tile.size
            var col = 0
            var row = -1
            while (row < 0) {
                row = (0..ROWS - s.rows).firstOrNull { fits(col, it, s) } ?: -1
                if (row < 0) col++
            }
            for (dc in 0 until s.cols) for (dr in 0 until s.rows) taken += cell(col + dc, row + dr)
            (tile.parent as? ViewGroup)?.removeView(tile)
            startGrid.addView(tile, FrameLayout.LayoutParams(span(s.cols), span(s.rows)).apply {
                leftMargin = dp(col * (UNIT + GAP))
                topMargin = dp(row * (UNIT + GAP))
            })
            width = max(width, col + s.cols)
        }
        startGrid.layoutParams = startGrid.layoutParams.apply { this.width = span(width) }
    }

    private fun span(units: Int) = dp(units * UNIT + (units - 1) * GAP)

    /** Pinned apps; until the user pins or moves anything, your eight most likely favourites. */
    private fun pinned(hero: App?): List<App> {
        val keys = prefs.pins ?: Apps.favourites(apps, prefs.launches()).filter { it.key != hero?.key }.take(8).map { it.key }
        return keys.mapNotNull { k -> apps.firstOrNull { it.key == k } }.filter { it.key != hero?.key }
    }

    private fun appTile(app: App, size: TileSize) = Tile(this, size).apply {
        this.app = app
        tag = "app:${app.key}"
        setLabel(app.label)
        val look = app.look
        val art = ImageView(context).apply { setImageDrawable(look.art) }
        val backdrop = look.backdrop
        // Opaque banners carry the app's own name or logo, so a permanent label would repeat it
        // (or sit on top of it): theirs only appears while the tile is focused.
        labelOnFocus = look.background != null || backdrop != null
        art.scaleType = ImageView.ScaleType.FIT_CENTER
        when {
            backdrop != null -> {
                // The whole banner, never cropped, over a blurred stretch of itself.
                addView(ImageView(context).apply {
                    setImageBitmap(backdrop)
                    // Undimmed, so it meets the banner's own edges without a visible seam.
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(art, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                // A dark fade under the name for when it shows.
                addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0)))
                }, 2, FrameLayout.LayoutParams(MATCH_PARENT, dp(40), Gravity.BOTTOM))
            }
            // Blended: the tile is the banner's own background colour, so it runs edge to edge.
            look.background != null -> addView(art, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            look.isBanner -> {
                // See-through banners sit on the accent like a card, above their (always shown) name.
                val inset = dp(if (size == TileSize.LARGE) 20 else 8)
                addView(art, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                    setMargins(inset, inset, inset, dp(if (size == TileSize.LARGE) 36 else 26))
                })
            }
            else -> {
                val icon = when (size) { TileSize.LARGE -> 104; TileSize.MEDIUM -> 56; else -> 32 }
                addView(art, 0, FrameLayout.LayoutParams(dp(icon), dp(icon), Gravity.CENTER).apply { bottomMargin = dp(10) })
            }
        }
        setOnClickListener { launch(app) }
        setOnLongClickListener { tileMenu(app, this); true }
    }

    private fun utilityTile(icon: Icon, tag: String, onClick: () -> Unit) = Tile(this, TileSize.SMALL).apply {
        this.tag = tag
        setLabel(null)
        addView(IconView(context, icon), 0, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /**
     * Colours (accent or per-app) and, in photo mode, the wallpaper each tile shows through. Photo
     * mode also darkens the backdrop heavily, as WP 8.1 did, so the tiles read as windows onto it.
     */
    private fun applyTileLook() {
        val hasPhoto = prefs.wallpaper != null
        shade.setBackgroundColor(Color.argb(if (prefs.photoTiles) 215 else if (hasPhoto) 90 else 0, 0, 0, 0))
        val photo = if (prefs.photoTiles) wallpaper else null
        for (i in 0 until startGrid.childCount) {
            val tile = startGrid.getChildAt(i) as Tile
            val app = tile.app
            val own = app?.let { prefs.tileColor(it.key) ?: it.look.background }
            tile.color = own ?: prefs.accent
            tile.glowTint = app?.let { prefs.tileColor(it.key) ?: it.look.edge }
            // As on WP 8.1, only accent tiles become windows onto the photo; branded ones keep their colour.
            tile.photo = if (own == null && app?.look?.backdrop == null) photo else null
        }
    }

    private fun invalidateTiles() {
        for (i in 0 until startGrid.childCount) startGrid.getChildAt(i).invalidate()
    }

    // --- apps list -----------------------------------------------------------------------------

    /** Windows Phone's letter groups: a heading across the whole row, then that letter's apps. */
    private fun buildAppList() {
        appsGrid.removeAllViews()
        apps.groupBy { letterOf(it.label) }.forEach { (letter, group) ->
            val first = appsGrid.childCount == 0
            appsGrid.addView(letterHeading(letter),
                GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, appsGrid.columnCount)).apply {
                    setMargins(0, dp(if (first) 0 else 12), 0, dp(8))
                })
            group.forEach { app ->
                appsGrid.addView(appRow(app), GridLayout.LayoutParams().apply {
                    width = dp(APP_W)
                    setMargins(0, 0, dp(16), dp(6))
                })
            }
        }
    }

    private fun letterOf(label: String) =
        label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

    /** An accent square with the letter in its corner, like Windows Phone's (not focusable). */
    private fun letterHeading(letter: String) = FrameLayout(this).apply {
        addView(text(letter, 22f).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(6), 0, 0, dp(2))
            setBackgroundColor(prefs.accent)
        }, FrameLayout.LayoutParams(dp(42), dp(42)))
    }

    /** The first app in the list (index 0 is a letter heading, which can't take focus). */
    private fun firstAppRow(): View? =
        (0 until appsGrid.childCount).map { appsGrid.getChildAt(it) }.firstOrNull { it.isFocusable }

    /** Windows Phone's app list: an accent square with the icon, then the name. */
    private fun appRow(app: App) = LinearLayout(this).apply {
        tag = "row:${app.key}"
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        defaultFocusHighlightEnabled = false
        setPadding(dp(4), dp(4), dp(10), dp(4))
        addView(FrameLayout(context).apply {
            setBackgroundColor(prefs.tileColor(app.key) ?: app.look.background ?: prefs.accent)
            addView(ImageView(context).apply { setImageDrawable(app.icon) }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val name = text(app.label, 19f, color = Color.argb(220, 255, 255, 255)).apply { maxLines = 1 }
        addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        setOnFocusChangeListener { _, f ->
            setBackgroundColor(if (f) Color.argb(70, 255, 255, 255) else Color.TRANSPARENT)
            name.setTextColor(if (f) Color.WHITE else Color.argb(220, 255, 255, 255))
        }
        setOnClickListener { launch(app) }
        setOnLongClickListener { tileMenu(app, this); true }
    }

    private fun launch(app: App) {
        prefs.countLaunch(app.key)
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    // --- long-press menu ------------------------------------------------------------------------

    private fun tileMenu(app: App, from: View) {
        blockHome()
        val hero = pickHero(apps, prefs.hero, prefs.launches())
        val isHero = hero?.key == app.key
        val pins = pinned(hero).map { it.key }.toMutableList()
        val index = pins.indexOf(app.key)
        val items = mutableListOf<MenuItem>()
        if (!isHero) items += MenuItem("Make Hero") {
            // The old hero keeps a place on Start rather than vanishing.
            hero?.let { if (it.key !in pins) pins.add(0, it.key) }
            pins.remove(app.key)
            prefs.pins = pins
            prefs.hero = app.key
            returnTag = "hero"
            buildStart()
        }
        if (isHero && prefs.hero != null) items += MenuItem("Let Arcade Choose") {
            prefs.hero = null
            buildStart()
        }
        items += MenuItem("Colour") { colourMenu(app) }
        if (index > 0) items += MenuItem("Move Left") { move(pins, index, -1) }
        if (index in 0 until pins.size - 1) items += MenuItem("Move Right") { move(pins, index, 1) }
        when {
            index >= 0 -> items += MenuItem("Unpin") {
                pins.removeAt(index)
                prefs.pins = pins
                buildStart()
            }
            !isHero -> items += MenuItem("Pin To Start") {
                pins += app.key
                prefs.pins = pins
                returnTag = "app:${app.key}"
                buildStart()
            }
        }
        returnTag = from.tag
        menu.show(app.label, items)
    }

    private fun move(pins: MutableList<String>, index: Int, by: Int) {
        pins.add(index + by, pins.removeAt(index))
        prefs.pins = pins
        buildStart()
    }

    private fun colourMenu(app: App) {
        blockHome()
        val pick = { c: Int? ->
            prefs.setTileColor(app.key, c)
            applyTileLook()
            buildAppList()
        }
        menu.show("${app.label} · Colour",
            listOf(MenuItem("Original") { pick(null) }) + Metro.accents.map { c -> MenuItem("", c) { pick(c) } })
    }

    // --- overlays ------------------------------------------------------------------------------

    private fun anyOverlay() = controls.isOpen || menu.isOpen || drop.isOpen || picker.isOpen

    /** While an overlay is up, D-pad focus can't wander back onto the tiles behind it. */
    private fun blockHome() {
        if (!anyOverlay()) currentFocus?.tag?.let { returnTag = it }
        panorama.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun restoreFocus() {
        if (anyOverlay()) return
        panorama.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val target = returnTag?.let { panorama.findViewWithTag<View>(it) } ?: startGrid.getChildAt(0)
        target?.requestFocus()
    }

    private fun openControls() {
        if (anyOverlay()) return
        blockHome()
        controls.open()
    }

    private fun closeControls() {
        controls.close()
        restoreFocus()
    }

    private fun openDrop() {
        controls.close()
        menu.close()
        blockHome()
        drop.open()
    }

    private fun closeDrop() {
        drop.close()
        restoreFocus()
    }

    private fun openPicker() {
        controls.close()
        menu.close()
        blockHome()
        picker.open()
    }

    private fun closePicker() {
        picker.close()
        restoreFocus()
    }

    private fun showToast(message: String) {
        toast.text = message
        toast.setBackgroundColor(prefs.accent)
        toast.visibility = View.VISIBLE
        toast.translationY = -dp(60).toFloat()
        toast.animate().cancel()
        toast.animate().translationY(0f).setInterpolator(Metro.easeOut).setDuration(300).start()
        root.removeCallbacks(hideToast)
        root.postDelayed(hideToast, 2600)
    }

    private val hideToast = Runnable {
        toast.animate().translationY(-toast.height.toFloat()).setInterpolator(Metro.easeIn).setDuration(200)
            .withEndAction { toast.visibility = View.GONE }.start()
    }

    // --- ControlPanel.Host ---------------------------------------------------------------------

    override fun accentChanged() {
        applyTileLook()
        buildAppList()
        if (prefs.wallpaper == null) loadWallpaper() // the generated wallpaper glows in the accent
    }

    override fun photoTilesChanged() = applyTileLook()

    override fun wallpaperFromPhone() = openDrop()

    override fun chooseWeatherCity() = openPicker()

    /** New city: relabel the tile and fetch its forecast now, not at the next 15-minute refresh. */
    private fun weatherCityChanged() {
        weatherTile.setPlace(prefs.city)
        root.removeCallbacks(refreshWeather)
        root.post(refreshWeather)
    }

    override fun nextWallpaper() {
        val names = Wallpapers.names(this)
        if (names.isEmpty()) {
            showToast("No Photos Yet — Send Some From Your Phone")
            return
        }
        useWallpaper(names[(names.indexOf(prefs.wallpaper) + 1) % names.size])
    }

    override fun defaultWallpaper() = useWallpaper(null)

    private fun useWallpaper(name: String?) {
        prefs.wallpaper = name
        loadWallpaper()
    }

    private fun loadWallpaper() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val name = prefs.wallpaper
        val accent = prefs.accent
        Net.io.execute {
            val bitmap = Wallpapers.load(this, name, w, h, accent)
            runOnUiThread {
                wallpaper = bitmap
                backdrop.setImageBitmap(bitmap)
                applyTileLook()
            }
        }
    }

    // --- live data -----------------------------------------------------------------------------

    private fun tick() {
        val now = LocalDateTime.now()
        clock.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
        greeting.text = when (now.hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            else -> "Good Evening"
        }
        date.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        clockTile.tick()
    }

    private val refreshWeather = object : Runnable {
        override fun run() {
            val city = prefs.city
            val lat = prefs.cityLat
            val lon = prefs.cityLon
            Net.io.execute {
                val w = runCatching { Weather.fetch(lat, lon) }.getOrNull()
                runOnUiThread {
                    if (city != prefs.city) return@runOnUiThread // the city changed while this was in flight
                    if (w != null) {
                        weather = w
                        weatherTile.bind(w)
                        statusGlyph.sky = w.sky
                        statusGlyph.visibility = View.VISIBLE
                        statusTemp.text = "${w.temp}°"
                    } else {
                        weatherTile.failed()
                    }
                }
            }
            // Right after boot the network is often not up yet: retry soon until the first forecast.
            root.postDelayed(this, if (weather == null) 15_000L else 15 * 60_000L)
        }
    }

    /** One live tile turns over every few seconds, alternating, like a Windows Phone Start screen. */
    private val flipper = object : Runnable {
        private var n = 0
        override fun run() {
            if (!anyOverlay()) listOf(weatherTile, clockTile)[n++ % 2].flip()
            root.postDelayed(this, 4_000)
        }
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }

    override fun onStart() {
        super.onStart()
        tick()
        // Until Start is customised it follows your launches, so refresh it on every return Home.
        if (prefs.pins == null && ::startGrid.isInitialized) buildStart()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        root.post(refreshWeather)
        root.postDelayed(flipper, 4_000)
    }

    override fun onStop() {
        root.removeCallbacks(refreshWeather)
        root.removeCallbacks(flipper)
        unregisterReceiver(clockReceiver)
        unregisterReceiver(packageReceiver)
        if (drop.isOpen) closeDrop() // never leave the upload server running behind an app
        super.onStop()
    }

    // --- keys ----------------------------------------------------------------------------------

    /** Home pressed while home: close everything and go back to the hero tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        drop.close()
        picker.close()
        menu.close()
        controls.close()
        returnTag = "hero"
        restoreFocus()
        panorama.smoothScrollTo(0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With nothing open, focus belongs on the home screen. If it's anywhere else (or nowhere),
        // this press only brings the focus ring back, so you never press into the invisible.
        if (event.action == KeyEvent.ACTION_DOWN && !anyOverlay() && !inPanorama(currentFocus)) {
            restoreFocus()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (controls.isOpen) closeControls() else openControls()
                return true
            }
            // A gamepad's X is the long-press: options for the focused tile.
            KeyEvent.KEYCODE_BUTTON_X -> if (!anyOverlay()) {
                currentFocus?.performLongClick()
                return true
            }
            // Up from the top row pulls down Action Center, as a swipe from the top did on the phone.
            KeyEvent.KEYCODE_DPAD_UP -> if (!anyOverlay() && atTop(currentFocus)) {
                openControls()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun inPanorama(v: View?): Boolean {
        var node: Any? = v
        while (node is View) {
            if (node === panorama) return true
            node = node.parent
        }
        return false
    }

    private fun atTop(v: View?): Boolean = when {
        v == null -> false
        v.parent === startGrid -> (v.layoutParams as FrameLayout.LayoutParams).topMargin == 0
        v.parent === appsGrid -> v.top == firstAppRow()?.top
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                drop.isOpen -> closeDrop()
                picker.isOpen -> closePicker()
                menu.isOpen -> menu.close()
                controls.isOpen -> closeControls()
                panorama.scrollX > 0 -> {
                    returnTag = "hero"
                    restoreFocus()
                    panorama.smoothScrollTo(0, 0)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun section(name: String, body: View, lp: LinearLayout.LayoutParams) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(text(name, 30f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
        addView(body, lp)
    }

    companion object {
        const val ROWS = 4
        const val UNIT = 60 // dp: one small tile
        const val GAP = 8
        const val APP_W = 270
    }
}

/**
 * The panorama's horizontal scroller. Moving into "apps" snaps that whole section into view, as
 * a panorama does on the phone; within "start" it scrolls just enough to keep a 48dp margin.
 */
class Panorama(context: Context, private val snapSection: View) : HorizontalScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val margin = dp(48)
        if (rect.left >= snapSection.left) return snapSection.left - margin - scrollX
        val min = rect.left - margin
        val max = rect.right + margin - width
        return when {
            scrollX > min -> min - scrollX
            scrollX < max -> max - scrollX
            else -> 0
        }
    }
}
