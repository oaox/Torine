package no.fibula.torine

import android.os.Bundle
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Full-screen, zoomable, pannable WebView.
 * The URL is supplied by the caller via Intent extra [EXTRA_URL].
 * A back arrow in the toolbar returns to the previous activity
 * (in practice, MainActivity).
 */
class RemoteWebActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_web)

        // targetSdk 35+ forces edge-to-edge, so keep content clear of the
        // status bar, navigation bar, display cutout and keyboard on every side.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.webRoot)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e("Torine_k", "RemoteWebActivity: no URL provided")
            finish()
            return
        }
        val online= isOnline(this)
        val webView = findViewById<WebView>(R.id.webView)
        configureZoomingWebView(webView, url, online)
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
internal fun configureZoomingWebView(webView: WebView, url: String, isOnline: Boolean) {
    webView.settings.apply {
        javaScriptEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        domStorageEnabled = true
        cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }
    //WebView.setWebContentsDebuggingEnabled(true)
    if (isOnline) { // is online, check if server file is newer than cached version
        webView.loadUrl(url, mapOf("Cache-Control" to "max-age=0"))
    } else {  // use the cached page
        webView.loadUrl(url)
    }
}




