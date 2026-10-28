package com.claudeshqip.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pageLoaded = false
    private var sharedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloader.prepare(this)

        // Template-i i web app-it ngarkohet nga assets/ me një domen https lokal.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(getColor(R.color.page_bg))
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = true
                    deliverSharedUrl()
                }
            }
            addJavascriptInterface(Bridge(), "AndroidBridge")
        }
        // Android 15+ e vizaton app-in nën shiritat e sistemit; këtu i lëmë hapësirë.
        val root = FrameLayout(this).apply { addView(webView) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Linku i ndarë nga app-i i YouTube ("Shpërndaj" → YT Downloader). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        sharedUrl = Regex("https?://\\S+").find(text)?.value ?: return
        deliverSharedUrl()
    }

    private fun deliverSharedUrl() {
        val url = sharedUrl ?: return
        if (!pageLoaded) return
        sharedUrl = null
        webView.evaluateJavascript(
            "document.getElementById('url').value = ${JSONObject.quote(url)};" +
                "document.getElementById('url-form').requestSubmit();",
            null,
        )
    }

    /** Ura mes JavaScript-it (static/app.js) dhe shkarkuesit në Kotlin. */
    inner class Bridge {
        @JavascriptInterface
        fun getInfo(callId: String, url: String) {
            Thread {
                val result = try {
                    Downloader.info(url.trim())
                } catch (e: Exception) {
                    JSONObject().put("error", Downloader.friendlyError(e))
                }
                webView.post {
                    webView.evaluateJavascript("window.__androidCallback(${JSONObject.quote(callId)}, $result)", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun startDownload(payload: String): String = try {
            JSONObject().put("job_id", Downloader.start(JSONObject(payload))).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Gabim i panjohur.").toString()
        }

        @JavascriptInterface
        fun progress(jobId: String): String = Downloader.progress(jobId).toString()

        @JavascriptInterface
        fun openFile(jobId: String) {
            val (uri, mime) = Downloader.fileOf(jobId) ?: return
            runOnUiThread {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "Nuk u gjet asnjë app për ta hapur këtë skedar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
