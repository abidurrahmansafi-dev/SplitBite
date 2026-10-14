package com.split.bite

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime: Long = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup status bar appearance
        setupStatusBar()

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // Load offline HTML asset
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        window.statusBarColor = android.graphics.Color.parseColor("#1a237e")
        window.navigationBarColor = android.graphics.Color.parseColor("#0f172a")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setAppCacheEnabled?.let { } // Handled natively in AndroidX
        

        // Native JavaScript Interface Bridge
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // Handle URL clicks & page load events
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Allow loading internal assets and web pages
                if (url.startsWith("file://") || url.startsWith("data:") || url.startsWith("blob:")) {
                    return false
                }

                // Handle external apps, tel:, mailto:, whatsapp:
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("geo:") || url.startsWith("whatsapp:")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No application found to handle this link", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }

                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        // WebChromeClient for dialogs, progress & file chooser
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("SplitBite")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("SplitBite")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }
        }

        // Support direct PDF / Blob / Data URL downloads
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            handleBlobOrDownload(url, mimetype)
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.isEnabled = true
        swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#1a237e"))
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && !false) {
                    webView.goBack()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressTime < 2000) {
                        finish()
                    } else {
                        lastBackPressTime = currentTime
                        Toast.makeText(this@MainActivity, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun handleBlobOrDownload(url: String, mimeType: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // Trigger JS to convert blob/data to base64 and send via Android bridge
            val script = """
                (function() {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$url', true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        var reader = new FileReader();
                        reader.readAsDataURL(xhr.response);
                        reader.onloadend = function() {
                            var base64data = reader.result;
                            if (window.Android && window.Android.saveBase64File) {
                                window.Android.saveBase64File(base64data, 'document.pdf');
                            }
                        }
                    };
                    xhr.send();
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open download link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Native JavaScript Interface available to web JavaScript as window.Android
     */
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun showToast(toast: String) {
            runOnUiThread {
                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun vibrate(milliseconds: Long) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(milliseconds)
                }
            } catch (e: Exception) {
                // Vibration failed or permission missing
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, "Share via")
                context.startActivity(shareIntent)
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return "1.0.0"
        }

        @JavascriptInterface
        fun saveBase64File(base64Data: String, defaultName: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetFile = File(downloadsDir, defaultName)
                    
                    val out: OutputStream = FileOutputStream(targetFile)
                    out.write(decodedBytes)
                    out.flush()
                    out.close()

                    Toast.makeText(context, "Saved to Downloads: ${targetFile.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Save failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
