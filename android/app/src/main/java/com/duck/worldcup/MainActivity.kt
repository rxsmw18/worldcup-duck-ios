package com.duck.worldcup

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView

    // Background videos bundled in assets/ so they play instantly offline.
    private val bundledVideos = setOf("fifa26-hero.mp4", "fifa26-analysis.mp4")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-Q devices need an explicit grant to write into the shared gallery.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }

        // Android 13+ needs a runtime grant to post notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }

        // FCM: create the channel + send our device token to the backend.
        // Guarded so the app still runs when google-services.json isn't bundled (FCM disabled).
        DuckMessagingService.ensureChannel(getSystemService(NotificationManager::class.java))
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> DuckMessagingService.registerToken(this, token) }
        } catch (e: Exception) {
            /* FCM not configured -> in-app reminders only */
        }

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        // Dark base + no overscroll stretch so the top/bottom overscroll never flashes white.
        webView.setBackgroundColor(0xFF02040A.toInt())
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(DuckBridge(), "DuckNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val name = request.url.lastPathSegment ?: return null
                if (request.url.path?.contains("/assets/") == true && bundledVideos.contains(name)) {
                    return serveBundledVideo(name, request)
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(BRIDGE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(BRIDGE_JS, null)
            }
        }
        webView.webChromeClient = WebChromeClient()

        if (savedInstanceState == null) {
            webView.loadUrl(resolveStartUrl(intent))
        }
    }

    // Open the URL carried by a tapped notification, defaulting to the home page.
    private fun resolveStartUrl(launchIntent: Intent?): String {
        val open = launchIntent?.getStringExtra("open_url")
        return when {
            open.isNullOrBlank() -> "$BASE_URL/"
            open.startsWith("http") -> open
            open.startsWith("/") -> BASE_URL + open
            else -> "$BASE_URL/$open"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getStringExtra("open_url") != null && ::webView.isInitialized) {
            webView.loadUrl(resolveStartUrl(intent))
        }
    }

    // Stream a bundled mp4 from assets/, honouring byte-range requests so the
    // WebView media player can start and loop the background video reliably.
    private fun serveBundledVideo(name: String, request: WebResourceRequest): WebResourceResponse? {
        return try {
            val total = assets.openFd(name).use { it.length.toInt() }
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            headers["Access-Control-Allow-Origin"] = "*"

            val rangeHeader = request.requestHeaders["Range"]
            var start = 0
            var end = total - 1
            var status = 200
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").split("-")
                val s = spec.getOrNull(0)?.toIntOrNull() ?: 0
                val e = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (s in 0..e && s < total) {
                    start = s; end = e; status = 206
                    headers["Content-Range"] = "bytes $start-$end/$total"
                }
            }

            val length = end - start + 1
            val buffer = ByteArray(length)
            assets.open(name).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip((start - skipped))
                    if (n <= 0) break
                    skipped += n
                }
                var off = 0
                while (off < length) {
                    val r = input.read(buffer, off, length - off)
                    if (r < 0) break
                    off += r
                }
            }
            headers["Content-Length"] = length.toString()

            WebResourceResponse(
                "video/mp4", null, status, if (status == 206) "Partial Content" else "OK",
                headers, ByteArrayInputStream(buffer)
            )
        } catch (e: Exception) {
            null
        }
    }

    inner class DuckBridge {
        // Called from window.DuckNativeSaveImage(base64, filename) in the web app.
        @JavascriptInterface
        fun saveImage(base64Png: String, filename: String) {
            runOnUiThread { doSaveImage(base64Png, filename) }
        }

        // Called from window.DuckNativeCapture(filename) -> native screenshot of the WebView.
        @JavascriptInterface
        fun captureScreen(filename: String) {
            runOnUiThread { doCaptureScreen(filename) }
        }
    }

    // Pixel-perfect screenshot of the visible WebView, saved to the gallery.
    private fun doCaptureScreen(filename: String) {
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            webView.draw(android.graphics.Canvas(bmp))
            saveBitmap(bmp, filename)
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doSaveImage(base64Png: String, filename: String) {
        try {
            val bytes = Base64.decode(base64Png, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            saveBitmap(bmp, filename)
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmap(bmp: Bitmap, filename: String) {
        try {
            val name = if (filename.endsWith(".png", true)) filename else "$filename.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WorldCupDuck")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            resolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val BASE_URL = "https://duck.gobet365.win"

        // Alias the @JavascriptInterface object to the function the web app calls.
        private const val BRIDGE_JS = """
            (function(){
              if (window.__duckBridgeReady) return;
              window.__duckBridgeReady = true;
              window.DuckNativeSaveImage = function(b64, name){
                try { DuckNative.saveImage(b64, name || 'image.png'); } catch(e){}
              };
              window.DuckNativeCapture = function(name){
                try { DuckNative.captureScreen(name || 'image.png'); } catch(e){}
              };
            })();
        """
    }
}
