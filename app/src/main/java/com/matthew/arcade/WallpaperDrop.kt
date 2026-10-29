package com.matthew.arcade

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Send a wallpaper from your phone": a full-screen QR code pointing at a tiny HTTP server on the
 * TV. The phone's browser crops each photo to 1920x1080 JPEG *before* uploading (the fastest
 * format for the TV to draw), then POSTs it here.
 *
 * The server only runs while this screen is open, and every URL carries a fresh random token, so
 * nothing else on the network can push images to the TV.
 */
class WallpaperDrop(
    private val activity: Activity,
    private val accent: () -> Int,
    private val onPhoto: (File) -> Unit,
) {
    private fun dp(v: Int) = activity.dp(v)
    private var server: ServerSocket? = null
    private var token = ""
    private var received = 0

    private val qr = ImageView(activity)
    private val url = activity.text("", 14f, Metro.regular, Metro.dimText)
    private val hint = activity.text("", 13f, Metro.regular, Metro.dimText)
    private val status = activity.text("", 20f)
    private val done = button("Done")

    val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.argb(245, 0, 0, 0))
        visibility = View.GONE
        isClickable = true
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(48), dp(72), dp(48))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(activity.text("Wallpaper", 64f))
                addView(activity.text("From Your Phone", 22f, color = Metro.dimText))
                listOf(
                    "Point Your Phone's Camera At The Code",
                    "Open The Link And Choose Photos",
                    "They Appear Here, Cropped For Your TV",
                ).forEachIndexed { i, step ->
                    addView(LinearLayout(activity).apply {
                        addView(activity.text("${i + 1}", 20f).also { it.minWidth = dp(28) })
                        addView(activity.text(step, 18f, Metro.regular))
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(if (i == 0) 28 else 12) })
                }
                addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
                addView(url, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(hint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
                addView(done, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(qr.apply { setBackgroundColor(Color.WHITE) }, LinearLayout.LayoutParams(dp(280), dp(280)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    var isOpen = false
        private set

    fun open() {
        if (isOpen) return
        isOpen = true
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        token = newToken()
        received = 0
        val port = startServer()
        val ip = lanAddress()
        val link = if (port == null || ip == null) null else "http://$ip:$port/$token"
        status.setTextColor(Color.WHITE)
        status.text = when {
            port == null -> "Couldn't Start The Upload Server"
            ip == null -> "Connect The TV To Wi-Fi Or Ethernet First"
            else -> "Waiting For Photos…"
        }
        url.text = link ?: ""
        // Emulators sit behind NAT (10.0.2.15): a phone can't reach them, the host can via adb.
        hint.text = if (port != null && isEmulator()) "Emulator: adb forward tcp:$port tcp:$port, Then Open localhost:$port/$token" else ""
        qr.setImageBitmap(link?.let { qrBitmap(it, dp(280)) })
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = dp(40).toFloat()
        view.animate().alpha(1f).translationY(0f).setInterpolator(Metro.easeOut).setDuration(380).start()
        done.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        // Closing (or closed) overlays never take focus, even mid-animation while still on screen.
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        runCatching { server?.close() }
        server = null
        view.animate().alpha(0f).translationY(dp(40).toFloat()).setInterpolator(Metro.easeIn).setDuration(200)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    // --- server --------------------------------------------------------------------------------

    private fun startServer(): Int? {
        for (port in PORT..PORT + 5) {
            val socket = runCatching { ServerSocket(port) }.getOrNull() ?: continue
            server = socket
            thread(isDaemon = true, name = "wallpaper-drop") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching { client.use { handle(it, socket) } }
                    }
                }
            }
            return port
        }
        return null
    }

    private fun handle(client: Socket, owner: ServerSocket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val request = lines.first().split(" ")
        val method = request.getOrNull(0)
        val path = request.getOrNull(1)
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        // A closed server (screen dismissed) or a stale token gets nothing.
        if (owner.isClosed || owner !== server) return respond(out, 410, "text/plain", "closed".toByteArray())

        when {
            method == "GET" && path == "/$token" ->
                respond(out, 200, "text/html; charset=utf-8", page(accent()).toByteArray())
            method == "POST" && path == "/$token/upload" -> {
                val length = headers["content-length"]?.toIntOrNull()
                    ?: return respond(out, 411, "text/plain", "length required".toByteArray())
                if (length <= 0 || length > MAX_BYTES) return respond(out, 413, "text/plain", "too big".toByteArray())
                val file = File(Wallpapers.dir(activity), "phone-${System.currentTimeMillis()}.jpg")
                file.outputStream().use { copy(input, it, length) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0) {
                    file.delete()
                    return respond(out, 415, "text/plain", "not an image".toByteArray())
                }
                respond(out, 200, "text/plain", "ok".toByteArray())
                activity.runOnUiThread {
                    received++
                    status.setTextColor(accent())
                    status.text = if (received == 1) "Got It — That's Your Wallpaper Now" else "Got $received Photos"
                    onPhoto(file)
                }
            }
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    /** Request line + headers, up to the blank line. */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) return buf.substring(0, buf.length - 4)
        }
        return null
    }

    private fun copy(input: InputStream, out: OutputStream, length: Int) {
        val chunk = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, left))
            if (n < 0) throw java.io.EOFException()
            out.write(chunk, 0, n)
            left -= n
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun lanAddress(): String? {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun isEmulator() = Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk")

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    // --- view helpers --------------------------------------------------------------------------

    /** Crisp QR: each module drawn as a whole-pixel square (an ImageView upscale would blur it). */
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ))
        val cell = size / matrix.width
        val px = cell * matrix.width
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val black = Paint().apply { color = Color.BLACK }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) c.drawRect((x * cell).toFloat(), (y * cell).toFloat(), ((x + 1) * cell).toFloat(), ((y + 1) * cell).toFloat(), black)
        }
        return out
    }

    /** A Windows Phone button: white outline, filled white while focused. */
    private fun button(label: String) = TextView(activity).apply {
        text = label
        textSize = 18f
        typeface = Metro.regular
        isFocusable = true
        defaultFocusHighlightEnabled = false
        setPadding(dp(28), dp(10), dp(28), dp(12))
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(if (focused) Color.WHITE else Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { close() }
    }

    companion object {
        const val PORT = 8765
        private const val MAX_BYTES = 25 * 1024 * 1024

        private fun page(accent: Int): String {
            val hex = String.format("#%06X", accent and 0xFFFFFF)
            return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Arcade · Wallpaper</title>
<style>
body{margin:0;background:#000;color:#fff;font-family:"Segoe UI",-apple-system,Roboto,sans-serif;padding:32px 22px}
h1{font-weight:200;font-size:58px;margin:0;letter-spacing:-1px}
p{color:#999;font-size:17px;margin:2px 0 30px}
label{display:block;background:$hex;height:120px;position:relative;font-size:26px;font-weight:300;padding:16px 18px;box-sizing:border-box}
label small{position:absolute;left:14px;bottom:12px;font-size:14px;font-weight:400}
label:active{transform:scale(.97)}
input{display:none}
#list div{display:flex;justify-content:space-between;gap:12px;padding:15px 0;border-bottom:1px solid #222;font-size:16px}
#list span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#ccc}
.ok{color:$hex}.err{color:#E51400}
</style></head><body>
<h1>Wallpaper</h1><p>For Arcade On Your TV</p>
<label><input id="f" type="file" accept="image/*" multiple>Choose Photos<small>Cropped To 1920×1080 On This Phone</small></label>
<div id="list"></div>
<script>
const W=1920,H=1080,f=document.getElementById('f'),list=document.getElementById('list');
f.onchange=async()=>{
  for(const file of f.files){
    const row=document.createElement('div');row.innerHTML='<span></span><b>Sizing…</b>';
    row.firstChild.textContent=file.name;list.prepend(row);const st=row.lastChild;
    try{
      const img=await createImageBitmap(file);
      const c=document.createElement('canvas');c.width=W;c.height=H;
      const s=Math.max(W/img.width,H/img.height),w=img.width*s,h=img.height*s,g=c.getContext('2d');
      g.imageSmoothingQuality='high';g.drawImage(img,(W-w)/2,(H-h)/2,w,h);
      const blob=await new Promise(r=>c.toBlob(r,'image/jpeg',0.85));
      st.textContent='Sending…';
      const res=await fetch(location.pathname+'/upload',{method:'POST',body:blob,headers:{'Content-Type':'image/jpeg'}});
      if(!res.ok)throw new Error(res.status);
      st.textContent='On Your TV';st.className='ok';
    }catch(e){st.textContent='Failed';st.className='err';}
  }
  f.value='';
};
</script></body></html>"""
        }
    }
}
