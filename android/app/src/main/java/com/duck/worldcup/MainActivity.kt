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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var webView: WebView

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

        // Notification channel.
        DuckMessagingService.ensureChannel(getSystemService(NotificationManager::class.java))

        // FCM (only works on devices with Google Play Services) — best-effort token registration.
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> DuckMessagingService.registerToken(this, token) }
        } catch (e: Exception) {
            /* no GMS -> rely on the poll worker below */
        }

        // Background poll -> local notifications. Works on GMS-less (Chinese) phones.
        val pollWork = PeriodicWorkRequestBuilder<NotifyPollWorker>(15L, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "duck_notify_poll", ExistingPeriodicWorkPolicy.KEEP, pollWork
        )

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

        // NOTE: we do NOT intercept the /assets/*.mp4 video requests. Android WebView's media
        // player can't reliably play video served via shouldInterceptRequest, so the background
        // video stayed on its poster. Letting it load from the network plays it normally.
        webView.webViewClient = object : WebViewClient() {
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

    // Screenshot of the visible screen, saved to the gallery. (Android WebView can't reliably
    // capture the full scrollable page in-app — both scroll-stitch and software-layer attempts
    // came out black / top-only — so we capture the visible viewport. For a full-page long
    // screenshot, use the phone's built-in 滚动截图/长截图 system feature.)
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
