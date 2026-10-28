package com.claudeshqip.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
    companion object {
        private const val REQUEST_FOLDER = 1001
    }

    private lateinit var webView: WebView
    private var pageLoaded = false
    private var sharedUrl: String? = null
    private var folderCallId: String? = null

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
            // Pa këtë, confirm() dhe alert() nuk shfaqen në WebView.
            webChromeClient = WebChromeClient()
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER) return
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) {
            resolveFolder(JSONObject().put("error", "cancelled"))
            return
        }
        contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        Downloader.setCustomFolder(this, tree)
        resolveFolder(Downloader.settingsJson(this))
    }

    private fun resolveFolder(result: JSONObject) {
        val callId = folderCallId ?: return
        folderCallId = null
        webView.evaluateJavascript("window.__androidCallback(${JSONObject.quote(callId)}, $result)", null)
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
        fun listDownloads(): String = History.list(this@MainActivity).toString()

        @JavascriptInterface
        fun openDownload(id: String) {
            val entry = History.find(this@MainActivity, id) ?: return
            val uri = Uri.parse(entry.optString("uri"))
            runOnUiThread {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, entry.optString("mime"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(view)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Skedari nuk u hap dot.", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun deleteDownload(id: String): String {
            History.remove(this@MainActivity, id)
            return "{}"
        }

        @JavascriptInterface
        fun getSettings(): String = Downloader.settingsJson(this@MainActivity).toString()

        @JavascriptInterface
        fun chooseFolder(callId: String) {
            runOnUiThread {
                folderCallId = callId
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(pick, REQUEST_FOLDER)
                } catch (e: Exception) {
                    resolveFolder(JSONObject().put("error", "Zgjedhja e dosjes nuk mbështetet në këtë telefon."))
                }
            }
        }

        @JavascriptInterface
        fun resetFolder(): String {
            Downloader.setCustomFolder(this@MainActivity, null)
            return Downloader.settingsJson(this@MainActivity).toString()
        }
    }
}
