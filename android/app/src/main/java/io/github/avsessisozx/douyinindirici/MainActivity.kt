package io.github.avsessisozx.douyinindirici

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Tek ekran: üstte Türkçe arayüz (index.html), arkasında gizli Douyin tarayıcısı.
 * Douyin doğrulama ya da giriş istediğinde arka plandaki tarayıcı öne gelir.
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity(), ApiHost {

    lateinit var uiWeb: WebView; private set
    private lateinit var holder: DouyinHolder
    private lateinit var dyPane: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var doneButton: Button

    @Volatile var uiReady = false; private set
    @Volatile var overlayMode: String? = null; private set
    private var overlayDone: (() -> Unit)? = null
    private var pendingLink: String? = null

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        val bg = if (isDark) Color.parseColor("#12161e") else Color.parseColor("#e9edf2")
        val surface = if (isDark) Color.parseColor("#1b212c") else Color.WHITE
        val ink = if (isDark) Color.parseColor("#e6eaf1") else Color.parseColor("#182033")

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(bg)
        }

        // --- Douyin tarayıcısı (arkada)
        dyPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setBackgroundColor(surface)
        }
        headerTitle = TextView(this).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        doneButton = Button(this).apply {
            setOnClickListener { overlayDone?.invoke() }
            visibility = View.GONE
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dyPane.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(dyPane, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- Arayüz (önde)
        uiWeb = WebView(this).apply { setBackgroundColor(bg) }
        root.addView(uiWeb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Gizli Douyin tarayıcısı uygulamaya ait; ekran yeniden kurulsa da yaşar.
        holder = DouyinHolder.get(this)
        holder.attach(this, dyPane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setupUi()
        handleIntent(intent)
        uiWeb.loadUrl(UI_URL)
        askNotificationPermissionOnce()
    }

    /** Bildirim iznini indirme sırasında değil, ilk açılışta bir kez sor. */
    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("durum", MODE_PRIVATE)
        if (prefs.getBoolean("bildirim_soruldu", false)) return
        prefs.edit().putBoolean("bildirim_soruldu", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun setupUi() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        uiWeb.settings.javaScriptEnabled = true
        uiWeb.settings.domStorageEnabled = true
        uiWeb.settings.allowFileAccess = false
        uiWeb.settings.allowContentAccess = false
        uiWeb.addJavascriptInterface(Api(this), "DouyinApp")
        uiWeb.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != Uri.parse(UI_URL).host   // arayüz dışına çıkılmaz

            override fun onPageFinished(view: WebView, url: String?) {
                uiReady = true
                pendingLink?.let { deliverLink(it) }
                pendingLink = null
            }
        }
    }

    // ------------------------------------------------------------ paylaş menüsü
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (uiReady) deliverLink(text) else pendingLink = text
    }

    private fun deliverLink(text: String) {
        if (Core.engine.running) return   // sürmekte olan işi bozma
        uiWeb.evaluateJavascript("window.douyinSetLink && window.douyinSetLink(${JSONObject.quote(text.trim())})", null)
    }

    /** Testler ve paylaş menüsü için: arayüzde JavaScript çalıştırır. */
    fun runUiScript(js: String, callback: ((String?) -> Unit)? = null) {
        uiWeb.evaluateJavascript(js) { callback?.invoke(it) }
    }

    // ------------------------------------------------------------ Douyin ekranını öne alma
    fun applyOverlay(mode: String, onDone: (() -> Unit)?) {
        overlayMode = mode
        overlayDone = onDone
        headerTitle.text = Core.text(if (mode == "login") "ui.overlay_login_title" else "ui.overlay_captcha_title")
        doneButton.text = Core.text("ui.overlay_done")
        doneButton.visibility = if (onDone != null) View.VISIBLE else View.GONE
        uiWeb.visibility = View.INVISIBLE
        holder.webView.requestFocus()
    }

    fun clearOverlay() {
        overlayMode = null
        overlayDone = null
        uiWeb.visibility = View.VISIBLE
        uiWeb.evaluateJavascript("window.douyinRefresh && window.douyinRefresh()", null)
    }

    // ------------------------------------------------------------ ApiHost
    override fun startDownloadService() {
        runOnUiThread {
            try {
                ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
            } catch (e: Exception) {
                // Bildirim servisi başlamasa da indirme uygulama açıkken sürer.
            }
        }
    }

    override fun requestNotificationPermission() {
        // İzin ilk açılışta sorulur; indirme sırasında pencere açıp işi bölmeyiz.
    }

    override fun openResult(location: String?) {
        runOnUiThread {
            val intents = mutableListOf<Intent>()
            if (location != null && location.startsWith("content://")) {
                val uri = Uri.parse(location)
                intents += Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intents += Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intents += Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
            for (i in intents) {
                try { startActivity(i); return@runOnUiThread } catch (e: Exception) {}
            }
            Toast.makeText(this, Core.text("android.no_gallery_app"), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ yaşam döngüsü
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayMode == "login" -> overlayDone?.invoke()
            overlayMode == "captcha" -> {}                         // bulmaca çözülmeden geri dönülmez
            Core.engine.running -> moveTaskToBack(true)             // indirme sürsün
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        holder.detach(this)   // tarayıcı yaşamaya devam eder; iş sürer
        uiWeb.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val UI_URL = "https://appassets.androidplatform.net/assets/index.html"
    }
}
