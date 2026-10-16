package io.github.avsessisozx.douyinindirici

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Gizli Douyin tarayıcısı ekrana (Activity) değil uygulamaya aittir.
 *
 * Android ekranı birçok sebeple yeniden kurar (izin penceresi, tema değişimi, dil ...).
 * Tarayıcı ekranla birlikte yok olsaydı süren toplama işi yarıda kalırdı.
 * Burada tarayıcı bir kere oluşturulur; ekran her kurulduğunda ona takılır,
 * ekran kapanınca çıkarılır ama yaşamaya devam eder.
 */
class DouyinHolder private constructor(appContext: Context) : OverlayHost {
    private val ctx = MutableContextWrapper(appContext)
    val webView: WebView = WebView(ctx)
    val web: WebDouyin = WebDouyin(webView, this)

    private var ui: MainActivity? = null
    var overlayMode: String? = null; private set
    var overlayDone: (() -> Unit)? = null; private set
    /** Kullanıcı pencereyi "Gizle" ile kapattı mı? (iş hâlâ bekliyor olabilir) */
    var hiddenByUser = false; private set

    /** Ekran kuruldu: tarayıcıyı ona tak, açık bir doğrulama/giriş ekranı varsa göster. */
    fun attach(activity: MainActivity, parent: ViewGroup, params: ViewGroup.LayoutParams) {
        ctx.baseContext = activity
        (webView.parent as? ViewGroup)?.removeView(webView)
        parent.addView(webView, params)
        ui = activity
        if (!hiddenByUser) overlayMode?.let { activity.applyOverlay(it, overlayDone) }
    }

    /** Ekran kapandı: tarayıcıyı çıkar ama kapatma. */
    fun detach(activity: MainActivity) {
        if (ui !== activity) return
        ui = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        ctx.baseContext = activity.applicationContext
    }

    override fun showOverlay(mode: String, onDone: (() -> Unit)?) {
        overlayMode = mode
        overlayDone = onDone
        hiddenByUser = false
        ui?.applyOverlay(mode, onDone)
    }

    override fun hideOverlay() {
        overlayMode = null
        overlayDone = null
        hiddenByUser = false
        ui?.clearOverlay()
    }

    /** Kullanıcı gizledi: pencere kapanır ama bekleyen iş sürer. */
    fun userHide() {
        if (overlayMode == null) return
        hiddenByUser = true
        ui?.clearOverlay()
    }

    /** Arayüzdeki "Doğrulamayı aç" düğmesi. */
    fun reshow() {
        val mode = overlayMode ?: return
        hiddenByUser = false
        ui?.applyOverlay(mode, overlayDone)
    }

    companion object {
        @Volatile private var instance: DouyinHolder? = null

        /** Ana iş parçacığında çağrılır. */
        fun get(context: Context): DouyinHolder =
            instance ?: DouyinHolder(context.applicationContext).also {
                instance = it
                Core.web = it.web
            }
    }
}
