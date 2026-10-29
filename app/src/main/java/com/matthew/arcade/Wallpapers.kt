package com.matthew.arcade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * A wallpaper is chosen by file name, looked up in two places:
 *  - your own photos in  /sdcard/Android/data/com.matthew.arcade/files/wallpapers/  (the phone
 *    upload in [WallpaperDrop] saves there; `adb push` works too), then
 *  - the six built into the app, in  assets/wallpapers/  (1920x1080 sRGB JPEG, the cheapest to draw).
 */
object Wallpapers {
    private const val BUILT_IN = "wallpapers"
    private val photoTypes = setOf("jpg", "jpeg", "png", "webp")

    fun dir(context: Context) = File(context.getExternalFilesDir(null), "wallpapers").apply { mkdirs() }

    private fun builtIn(context: Context): List<String> =
        context.assets.list(BUILT_IN)?.filter { it.substringAfterLast('.').lowercase() in photoTypes }?.sorted() ?: emptyList()

    private fun yours(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension.lowercase() in photoTypes }?.map { it.name }?.sorted() ?: emptyList()

    /** Every wallpaper "next" cycles through: the built-in set, then yours. */
    fun names(context: Context): List<String> = (builtIn(context) + yours(context)).distinct()

    /** The chosen wallpaper cropped to fill [w]x[h], or the generated Metro backdrop in [accent]. */
    fun load(context: Context, name: String?, w: Int, h: Int, accent: Int): Bitmap {
        if (name != null) {
            val file = File(dir(context), name)
            val open: (() -> InputStream)? = when {
                file.exists() -> { { file.inputStream() } }
                name in builtIn(context) -> { { context.assets.open("$BUILT_IN/$name") } }
                else -> null
            }
            open?.let { o -> runCatching { decode(o, w, h) }.getOrNull()?.let { return it } }
        }
        return generate(w, h, accent)
    }

    /** [open] is called twice: once to read the size, once to decode at the right scale. */
    private fun decode(open: () -> InputStream, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        // Always sRGB: wide-gamut photos otherwise cost a colour conversion on every frame.
        val src = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            })
        } ?: return null
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** Near-black with two soft glows of the accent colour, like the showcase mockup. */
    private fun generate(w: Int, h: Int, accent: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(6, 8, 10))
        fun glow(cx: Float, cy: Float, r: Float, alpha: Int) {
            val color = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
            c.drawCircle(cx * w, cy * h, r * w, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx * w, cy * h, r * w, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }
        glow(0.82f, 0.05f, 0.55f, 120)
        glow(0.1f, 1.0f, 0.4f, 60)
        return out
    }
}
