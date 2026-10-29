package com.matthew.arcade

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.abs

object Apps {
    /** Settings, the store and TCL's own tools: still listed, just not pinned to Start by default. */
    private val utilityPackages = setOf(
        "com.android.tv.settings", "com.android.vending",
        "com.tcl.suspension", "com.tcl.dashboard", "com.tcl.ui_mediaCenter", "com.tcl.tv",
    )

    /** Streaming apps most people open most, best first: Start's order until there's launch history. */
    private val likelyFavourites = listOf(
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.nuvio.tv",
        "com.google.android.youtube.tvunplugged", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
        "com.hulu.livingroomplus", "com.wbd.stream", "com.plexapp.android", "tv.twitch.android.app",
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic",
    )

    /** Every TV-launchable app except Arcade itself, A to Z. */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        // Other home screens (Glass, the stock launcher...) also count as utilities here.
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val icon = ri.loadIcon(pm)
                val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                App(ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString(), icon, banner,
                    Look.of(banner ?: icon, isBanner = banner != null),
                    isUtility = ai.packageName in utilityPackages || ai.packageName in homes)
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Apps in the order you're likely to want them: most launched from Arcade first; then, until
     * there's history to go on, well-known streaming apps; then everything else A to Z, with
     * utilities last. A launched utility counts like any other app.
     */
    fun favourites(apps: List<App>, launches: Map<String, Int>): List<App> =
        apps.sortedWith(
            compareByDescending<App> { launches[it.key] ?: 0 }
                .thenBy { likelyFavourites.indexOf(it.component.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.isUtility }
        ) // sortedWith is stable, so ties keep the A-to-Z order
}

/**
 * How an app's art sits on its tile, worked out once from its banner (or icon if it has none):
 *  - [background] set: the art's edges are one flat colour, so the tile takes that colour and the
 *    art blends into it, like the branded tiles third-party apps had on Windows Phone 8.1.
 *  - [backdrop] set: the edges vary (a photo, a gradient), so no one colour can extend them. The
 *    whole banner sits over a soft, stretched-out copy of itself instead: nothing gets cropped.
 *  - neither: the edges are see-through, so the art floats on the tile's accent colour.
 * [edge] is the banner's average edge colour whenever it's opaque: what the tile glows when focused.
 */
class Look(val art: Drawable, val isBanner: Boolean, val background: Int?, val backdrop: Bitmap?, val edge: Int? = background) {
    companion object {
        private const val W = 64
        private const val H = 36

        fun of(art: Drawable, isBanner: Boolean): Look {
            // Draw a copy small: the drawable instance itself is shared with the ImageView later.
            val copy = art.constantState?.newDrawable()?.mutate() ?: art
            val w = if (isBanner) W else H
            val bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
            copy.setBounds(0, 0, w, H)
            copy.draw(Canvas(bmp))
            val edge = IntArray(2 * (w + H)).also { px ->
                var i = 0
                for (x in 0 until w) { px[i++] = bmp.getPixel(x, 0); px[i++] = bmp.getPixel(x, H - 1) }
                for (y in 0 until H) { px[i++] = bmp.getPixel(0, y); px[i++] = bmp.getPixel(w - 1, y) }
            }
            // 12x7 pixels, stretched over a whole tile with filtering, reads as a heavy blur.
            val tiny = if (isBanner) Bitmap.createScaledBitmap(bmp, 12, 7, true) else null
            bmp.recycle()
            val opaque = edge.filter { Color.alpha(it) >= 200 }
            if (opaque.size < edge.size * 3 / 4) return Look(art, isBanner, background = null, backdrop = null)
            val r = opaque.sumOf { Color.red(it) } / opaque.size
            val g = opaque.sumOf { Color.green(it) } / opaque.size
            val b = opaque.sumOf { Color.blue(it) } / opaque.size
            // Mean distance from the average colour: near 0 for a flat background, large for a photo.
            val spread = opaque.sumOf { abs(Color.red(it) - r) + abs(Color.green(it) - g) + abs(Color.blue(it) - b) } / opaque.size
            return if (spread > 36) Look(art, isBanner, background = null, backdrop = tiny, edge = Color.rgb(r, g, b))
            else Look(art, isBanner, background = Color.rgb(r, g, b), backdrop = null)
        }
    }
}

/**
 * Which app gets the large hero tile at the start of Start.
 *
 * [pinned] is the key the user chose with "make hero" (null if they never did, or chose
 * "let arcade choose"); [launches] counts how many times Arcade has launched each app key.
 */
fun pickHero(apps: List<App>, pinned: String?, launches: Map<String, Int>): App? {
    // TODO(human): decide the hero when nothing (or a since-uninstalled app) is pinned.
    return apps.firstOrNull { it.key == pinned } ?: apps.firstOrNull()
}
