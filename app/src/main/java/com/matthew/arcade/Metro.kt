package com.matthew.arcade

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/** The Windows Phone design language, reduced to what a TV launcher needs. */
object Metro {
    /** The Windows Phone 8 accent colours, in the order the phone's settings listed them. */
    val accents = listOf(
        0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF,
        0xFFAA00FF, 0xFFF472D0, 0xFFD80073, 0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A,
        0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
    ).map { it.toInt() }
    val defaultAccent = accents[1] // green, like the showcase page

    // Segoe UI isn't on Android; Roboto Light is the closest stand-in for Segoe's light weights.
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val semibold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Windows Phone's signature motion: fast start, long soft landing ("exponential out"). */
    val easeOut = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)
    val easeIn = PathInterpolator(0.7f, 0f, 1f, 0.5f)

    val dimText = Color.argb(150, 255, 255, 255)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun View.dp(v: Int) = context.dp(v)

fun Context.text(s: CharSequence, sp: Float, face: Typeface = Metro.light, color: Int = Color.WHITE) =
    TextView(this).apply {
        text = s
        textSize = sp
        typeface = face
        setTextColor(color)
        includeFontPadding = false
    }

/** Keeps a label on one line, shrinking it between [minSp] and [maxSp] to fit its width. */
fun TextView.fitOneLine(minSp: Int, maxSp: Int) {
    maxLines = 1
    setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
}

/**
 * One TV-launchable app. [key] is its flattened component name, used everywhere in prefs.
 * [isUtility] marks settings, stores, TV-maker tools and other home screens: fine in Apps, poor
 * default tiles on Start.
 */
class App(
    val component: ComponentName,
    val label: String,
    val icon: Drawable,
    val banner: Drawable?,
    val look: Look,
    val isUtility: Boolean,
) {
    val key: String = component.flattenToShortString()
}

/** Everything the user can change, in one SharedPreferences file. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("arcade", Context.MODE_PRIVATE)

    var accent: Int
        get() = sp.getInt("accent", Metro.defaultAccent)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** Windows Phone 8.1's "show background through tiles". */
    var photoTiles: Boolean
        get() = sp.getBoolean("photoTiles", false)
        set(v) = sp.edit().putBoolean("photoTiles", v).apply()

    /** The app the user pinned to the hero tile, or null to let [pickHero] decide. */
    var hero: String?
        get() = sp.getString("hero", null)
        set(v) = sp.edit().putString("hero", v).apply()

    /** Apps pinned to Start, in order; null until the user first changes them. */
    var pins: List<String>?
        get() = sp.getString("pins", null)?.split(',')?.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("pins", v?.joinToString(",")).apply()

    /** Where the weather is for. Los Angeles until you pick another city. */
    var city: String
        get() = sp.getString("city", "Los Angeles")!!
        set(v) = sp.edit().putString("city", v).apply()
    var cityLat: Double
        get() = sp.getFloat("cityLat", 34.0522f).toDouble()
        set(v) = sp.edit().putFloat("cityLat", v.toFloat()).apply()
    var cityLon: Double
        get() = sp.getFloat("cityLon", -118.2437f).toDouble()
        set(v) = sp.edit().putFloat("cityLon", v.toFloat()).apply()

    /** File name inside the wallpapers folder, or null for the generated one. */
    var wallpaper: String?
        get() = sp.getString("wallpaper", null)
        set(v) = sp.edit().putString("wallpaper", v).apply()

    fun tileColor(key: String): Int? = if (sp.contains("color.$key")) sp.getInt("color.$key", 0) else null
    fun setTileColor(key: String, color: Int?) =
        sp.edit().apply { if (color == null) remove("color.$key") else putInt("color.$key", color) }.apply()

    /** Idle minutes before the lock-screen screensaver; 0 turns it off. */
    var screensaverMinutes: Int
        get() = sp.getInt("screensaverMinutes", 5)
        set(v) = sp.edit().putInt("screensaverMinutes", v).apply()

    /** A pinned app's tile size; medium until resized. */
    fun tileSize(key: String): TileSize =
        sp.getString("size.$key", null)?.let { runCatching { TileSize.valueOf(it) }.getOrNull() } ?: TileSize.MEDIUM
    fun setTileSize(key: String, size: TileSize) = sp.edit().putString("size.$key", size.name).apply()

    fun countLaunch(key: String) = sp.edit().putInt("launches.$key", sp.getInt("launches.$key", 0) + 1).apply()
    fun launches(): Map<String, Int> =
        sp.all.filterKeys { it.startsWith("launches.") }.entries
            .associate { (k, v) -> k.removePrefix("launches.") to (v as Int) }
}
