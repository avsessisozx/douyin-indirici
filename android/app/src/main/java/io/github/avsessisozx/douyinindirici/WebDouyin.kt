package io.github.avsessisozx.douyinindirici

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Douyin ekranını kullanıcıya gösterme (doğrulama ya da giriş için). Ana iş parçacığında çağrılır. */
interface OverlayHost {
    fun showOverlay(mode: String, onDone: (() -> Unit)?)
    fun hideOverlay()
}

/**
 * Gizli WebView ile Douyin sayfalarını açar ve sayfanın kendi yüklediği
 * gönderi listelerini yakalar. Windows sürümündeki browser.py'nin karşılığı.
 *
 * Yakalama yöntemi: sayfa yüklenmeden önce eklenen küçük bir betik, sayfanın
 * fetch/XMLHttpRequest isteklerinden gönderi API'lerine ait yanıtların bir
 * kopyasını uygulamaya iletir. Sayfanın davranışı değişmez.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebDouyin(private val webView: WebView, private val overlay: OverlayHost) : DouyinWeb {

    override var listener: WebListener? = null
    override var cancel: AtomicBoolean = AtomicBoolean(false)
    override val userAgent: String

    private var captureHandler: ((String, JSONObject) -> Unit)? = null
    private var pageWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var mainFrameError: Int? = null

    init {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = true   // gizli sayfada video sesli oynamasın
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        userAgent = desktopUserAgent(s.userAgentString)
        s.userAgentString = userAgent
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val origins = if (TestHooks.baseUrl != null) setOf("*") else ORIGINS
        val modern = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (modern) {
            WebViewCompat.addWebMessageListener(webView, "DouyinCapture", origins) { _, message, _, _, _ ->
                message.data?.let { onCaptured(it) }
            }
            WebViewCompat.addDocumentStartJavaScript(webView, captureScript("DouyinCapture.postMessage(s)"), origins)
        } else {
            // Eski WebView: arayüz nesnesiyle iletilir, betik sayfa başlarken eklenir.
            webView.addJavascriptInterface(object {
                @JavascriptInterface fun post(s: String) { webView.post { onCaptured(s) } }
            }, "DouyinCaptureLegacy")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return true
                return scheme != "http" && scheme != "https"   // uygulamayı açmaya çalışan linkleri engelle
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!modern) view.evaluateJavascript(captureScript("DouyinCaptureLegacy.post(s)"), null)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) { pageWaiter?.complete(Unit) }
            override fun onPageFinished(view: WebView, url: String?) { pageWaiter?.complete(Unit) }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameError = error.errorCode
                    pageWaiter?.complete(Unit)
                }
            }
        }
    }

    private fun onCaptured(raw: String) {
        val nl = raw.indexOf('\n')
        if (nl <= 0) return
        val url = raw.substring(0, nl)
        val data = try { JSONObject(raw.substring(nl + 1)) } catch (e: Exception) { return }
        captureHandler?.invoke(url, data)
    }

    // ------------------------------------------------------------ yardımcılar
    private fun checkCancel() { if (cancel.get()) throw CancelledException() }

    private fun rebase(url: String): String {
        val b = TestHooks.baseUrl ?: return url
        return url.replace(Regex("^https?://(www\\.)?douyin\\.com"), b.trimEnd('/'))
    }

    private suspend fun eval(js: String, timeoutMs: Long = 15_000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r) }
            }
        }
    }

    private suspend fun evalString(js: String): String? {
        val r = eval(js) ?: return null
        if (r == "null") return null
        return try { JSONTokener(r).nextValue() as? String } catch (e: Exception) { null }
    }

    private suspend fun goto(url: String) = withContext(Dispatchers.Main) {
        checkCancel()
        mainFrameError = null
        val waiter = CompletableDeferred<Unit>()
        pageWaiter = waiter
        webView.loadUrl(rebase(url))
        val ok = withTimeoutOrNull(60_000) { waiter.await() }
        pageWaiter = null
        val err = mainFrameError
        if (err != null && !cancel.get()) {
            when (err) {
                WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
                WebViewClient.ERROR_PROXY_AUTHENTICATION -> throw DouyinError(msg("browser.no_internet"))
            }
        }
        if (ok == null) listener?.log("warn", msg("browser.slow"))
    }

    private val now: Long get() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------ doğrulama
    /** Görünür doğrulama varsa sebebini, yoksa null döner. */
    private suspend fun blockReason(): String? = evalString(BLOCK_JS)?.takeIf { it.isNotEmpty() }

    /**
     * Doğrulama varsa kullanıcının çözmesini bekler. Doğrulama olduysa true döner.
     * satisfied: beklenen veri bu arada geldiyse (sayfa aslında engellenmemiş) beklemeyi bırakır.
     */
    private suspend fun waitIfBlocked(timeoutMs: Long = 900_000, satisfied: () -> Boolean = { false }): Boolean {
        if (satisfied()) return false
        val reason = blockReason() ?: return false
        if (satisfied()) return false
        listener?.captcha(true)
        listener?.log("warn", msg("browser.captcha_mobile"))
        listener?.log("info", msg("browser.captcha_reason", "reason" to reason))
        withContext(Dispatchers.Main) { overlay.showOverlay("captcha", null) }
        try {
            val start = now
            while (now - start < timeoutMs) {
                if (cancel.get()) return true
                delay(1500)
                if (satisfied()) return false
                if (blockReason() == null) {
                    listener?.log("ok", msg("browser.captcha_done"))
                    delay(1500)
                    return true
                }
            }
            throw DouyinError(msg("browser.captcha_timeout"))
        } finally {
            listener?.captcha(false)
            withContext(Dispatchers.Main) { overlay.hideOverlay() }
        }
    }

    // ------------------------------------------------------------ iş
    override suspend fun prepare() {
        listener?.log("info", msg("browser.opened_mobile"))
    }

    override suspend fun resolve(p: Links.Parsed): Links.Parsed = withContext(Dispatchers.Main) {
        if (p.kind != Links.SHORT) return@withContext p
        listener?.log("info", msg("browser.short_opening"))
        goto(p.url)
        repeat(30) {
            checkCancel()
            val r = Links.classifyFinalUrl(webView.url ?: "")
            if (r.ok) return@withContext r
            if (!waitIfBlocked(satisfied = { Links.classifyFinalUrl(webView.url ?: "").ok })) delay(500)
        }
        throw DouyinError(msg("browser.short_failed"))
    }

    override suspend fun getPost(awemeId: String, url: String, quiet: Boolean): JSONObject = withContext(Dispatchers.Main) {
        var found: JSONObject? = null
        var notFound = false
        captureHandler = { u, data ->
            if (DETAIL_API in u || POST_API in u) {
                val detail = data.optJSONObject("aweme_detail")
                if (detail != null && Media.awemeIdOf(detail) == awemeId) {
                    if (found == null) found = detail
                } else {
                    if (DETAIL_API in u && detail == null && data.opt("filter_detail") != null && !data.isNull("filter_detail")) notFound = true
                    val list = data.optJSONArray("aweme_list")
                    if (list != null) for (i in 0 until list.length()) {
                        val it = list.optJSONObject(i) ?: continue
                        if (Media.awemeIdOf(it) == awemeId && found == null) found = it
                    }
                }
            }
        }
        try {
            val target = url.ifEmpty { "https://www.douyin.com/video/$awemeId" }
            if (!quiet) listener?.log("info", msg("browser.post_opening"))
            goto(target)
            var deadline = now + 25_000
            var embeddedChecked = 0L
            while (now < deadline) {
                checkCancel()
                found?.let { return@withContext it }
                if (waitIfBlocked(satisfied = { found != null })) {
                    deadline = now + 25_000
                    goto(target)
                    continue
                }
                if (now - embeddedChecked > 2000) {
                    embeddedChecked = now
                    embeddedPosts(awemeId).firstOrNull()?.let { return@withContext it }
                }
                if (notFound) break
                delay(500)
            }
            found ?: throw DouyinError(msg("browser.post_failed"))
        } finally {
            captureHandler = null
        }
    }

    private suspend fun embeddedPosts(awemeId: String): List<JSONObject> {
        val raw = evalString(EMBEDDED_JS) ?: return emptyList()
        val blobs = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until blobs.length()) {
            val text = blobs.optString(i, "")
            if (text.isEmpty()) continue
            val data = try { JSONTokener(text).nextValue() } catch (e: Exception) { continue }
            out += Media.findPostDicts(data, awemeId, 5)
        }
        return out
    }

    private class ProfileState {
        val items = HashMap<String, JSONObject>()
        val order = ArrayList<String>()
        var hasMore: Int? = null
        var responses = 0
        var authorName = ""
        var reportedCount = -1
        var private = false
        var lastChange = SystemClock.elapsedRealtime()
    }

    override suspend fun collectProfile(secUid: String, limit: Int, onItems: (List<JSONObject>) -> Unit): ProfileResult =
        withContext(Dispatchers.Main) {
            val idleMs = ((TestHooks.idleSeconds ?: 25.0) * 1000).toLong()
            val st = ProfileState()

            fun accept(items: List<JSONObject>) {
                val fresh = mutableListOf<JSONObject>()
                for (item in items) {
                    val aid = Media.awemeIdOf(item)
                    if (aid.isEmpty() || aid in st.items) continue
                    if (limit > 0 && st.order.size >= limit) break
                    st.items[aid] = item
                    st.order += aid
                    fresh += item
                }
                if (fresh.isNotEmpty()) {
                    st.lastChange = now
                    if (st.authorName.isEmpty()) {
                        st.authorName = (Media.get(fresh[0].optJSONObject("author"), "nickname") as? String ?: "")
                    }
                    onItems(fresh)
                }
            }

            captureHandler = { u, data ->
                if (POST_API in u) {
                    st.responses++
                    if (data.has("has_more")) st.hasMore = Media.toInt(data.opt("has_more")).toInt()
                    val list = data.optJSONArray("aweme_list")
                    if (list != null) accept((0 until list.length()).mapNotNull { list.optJSONObject(it) })
                } else if (PROFILE_API in u) {
                    val user = data.optJSONObject("user")
                    if (user != null) {
                        (Media.get(user, "nickname") as? String)?.let { st.authorName = it }
                        if (user.has("aweme_count") && !user.isNull("aweme_count")) st.reportedCount = Media.toInt(user.opt("aweme_count")).toInt()
                        val secret = user.opt("secret"); val ps = user.opt("private_status")
                        if (secret == true || secret == 1 || ps == true || ps == 1) st.private = true
                    }
                }
            }

            val result = ProfileResult()
            try {
                listener?.log("info", msg("browser.profile_opening"))
                val target = "https://www.douyin.com/user/$secUid"
                goto(target)
                delay(2000)
                if (waitIfBlocked(satisfied = { st.order.isNotEmpty() })) { goto(target); delay(2000) }
                if (st.order.isEmpty()) accept(embeddedPosts(""))

                result.reason = scrollLoop(st, limit, idleMs)

                if (st.order.isEmpty() && result.reason !in setOf("cancel", "private", "empty")) {
                    val ids = idsFromDom()
                    if (ids.isNotEmpty()) {
                        listener?.log("info", msg("browser.dom_fallback", "n" to ids.size))
                        captureHandler = null
                        for (aid in ids) {
                            if (cancel.get() || (limit > 0 && st.order.size >= limit)) break
                            try { accept(listOf(getPost(aid, "", quiet = true))) } catch (e: DouyinError) {
                                listener?.log("warn", msg("engine.download_failed", "name" to aid, "error" to e.m))
                            }
                        }
                        result.reason = if (cancel.get()) "cancel" else "end"
                    }
                }
            } finally {
                captureHandler = null
            }
            if (cancel.get()) throw CancelledException()
            result.authorName = st.authorName
            result.reportedCount = st.reportedCount.coerceAtLeast(0)
            result.collected = st.order.size
            result.private = st.private
            if (st.private && st.order.isEmpty()) result.reason = "private"
            else if (st.reportedCount == 0 && st.order.isEmpty()) result.reason = "empty"
            result
        }

    private suspend fun scrollLoop(st: ProfileState, limit: Int, idleMs: Long): String {
        while (true) {
            if (cancel.get()) return "cancel"
            if (limit > 0 && st.order.size >= limit) return "limit"
            if (st.hasMore == 0 && st.responses > 0) return "end"
            if (st.private && st.order.isEmpty()) return "private"
            if (st.reportedCount == 0 && now - st.lastChange > 6000) return "empty"
            val before = st.order.size
            if (waitIfBlocked(satisfied = { st.order.size > before })) { st.lastChange = now; continue }
            eval(DISMISS_JS)
            eval(SCROLL_JS)
            delay(1300)
            if (now - st.lastChange > idleMs) {
                if (st.hasMore == 1) return "login_wall"
                return if (st.responses == 0) "stalled" else "end"
            }
        }
    }

    private suspend fun idsFromDom(): List<String> {
        val raw = evalString(LINKS_JS) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val ids = LinkedHashSet<String>()
        val re = Regex("/(?:video|note)/(\\d{15,22})")
        for (i in 0 until arr.length()) re.find(arr.optString(i, ""))?.let { ids += it.groupValues[1] }
        return ids.toList()
    }

    override suspend fun loginSession() {
        goto("https://www.douyin.com/")
        listener?.log("info", msg("browser.login_open_mobile"))
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) { overlay.showOverlay("login") { done.complete(Unit) } }
        try {
            while (!done.isCompleted && !cancel.get()) delay(500)
        } finally {
            withContext(Dispatchers.Main) { overlay.hideOverlay() }
            CookieManager.getInstance().flush()
        }
    }

    override fun cookiesFor(url: String): String? = try {
        val host = Uri.parse(url).host ?: ""
        if (host.endsWith("douyin.com")) CookieManager.getInstance().getCookie(url) else null
    } catch (e: Exception) { null }

    companion object {
        const val POST_API = "/aweme/v1/web/aweme/post/"
        const val DETAIL_API = "/aweme/v1/web/aweme/detail/"
        const val PROFILE_API = "/aweme/v1/web/user/profile/other/"

        private val ORIGINS = setOf("https://www.douyin.com", "https://douyin.com", "https://*.douyin.com", "https://*.iesdouyin.com")

        /** Telefonun kendi Chrome sürümünü koruyarak masaüstü tarayıcı kimliği üretir (Douyin mobil siteye yönlendirmesin). */
        fun desktopUserAgent(default: String): String {
            val ver = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
        }

        fun captureScript(send: String): String = """
(function(){
  if (window.__dyHooked) return; window.__dyHooked = true;
  try {
    var mp = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function(){ try { this.muted = true; } catch(e){} return mp.apply(this, arguments); };
  } catch(e){}
  var want = function(u){ return /\/aweme\/v1\/web\/(aweme\/post|aweme\/detail|user\/profile\/other)\//.test(String(u||'')); };
  var send = function(url, text){ try { var s = String(url) + '\n' + text; $send; } catch(e){} };
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var p = of.apply(this, arguments);
      if (want(url)) p.then(function(r){ r.clone().text().then(function(t){ send(r.url || url, t); }); }).catch(function(){});
      return p;
    };
  }
  var X = window.XMLHttpRequest && XMLHttpRequest.prototype;
  if (X) {
    var oo = X.open, os = X.send;
    X.open = function(m, u){ this.__dyUrl = u; return oo.apply(this, arguments); };
    X.send = function(){
      if (want(this.__dyUrl)) this.addEventListener('load', function(){
        try {
          var t = (this.responseType === '' || this.responseType === 'text') ? this.responseText
                : (this.responseType === 'json' ? JSON.stringify(this.response) : null);
          if (t) send(this.responseURL || this.__dyUrl, t);
        } catch(e){}
      });
      return os.apply(this, arguments);
    };
  }
})();
"""

        /**
         * Sayfada GÖRÜNÜR bir doğrulama (bulmaca) var mı? Varsa kısa bir sebep, yoksa boş metin döner.
         * Gerçek Douyin sayfalarında görünmez güvenlik çerçeveleri ve boş doğrulama kutuları hep
         * bulunur; bunlar doğrulama sayılmaz (1.1.0'daki yanlış alarmın sebebi buydu).
         */
        const val BLOCK_JS = """
(function(){
  function vis(el){
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 40 || r.height <= 40) return false;
    if (r.bottom <= 0 || r.right <= 0 || r.top >= innerHeight || r.left >= innerWidth) return false;
    var e = el;
    while (e && e.nodeType === 1) {
      var cs = getComputedStyle(e);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return false;
      e = e.parentElement;
    }
    return true;
  }
  var t = document.title || '';
  if (t.indexOf('验证码') >= 0 || t.indexOf('安全验证') >= 0) return 'title:' + t.slice(0, 40);
  var u = location.href.toLowerCase().split('?')[0];
  if (u.indexOf('verifycenter') >= 0 || u.indexOf('/captcha') >= 0) return 'url';
  var hints = ['verifycenter', 'captcha', '/verify'];
  var frames = document.querySelectorAll('iframe');
  for (var i = 0; i < frames.length; i++) {
    var s = (frames[i].getAttribute('src') || '').toLowerCase();
    for (var j = 0; j < hints.length; j++) if (s.indexOf(hints[j]) >= 0 && vis(frames[i])) return 'iframe:' + s.slice(0, 80);
  }
  var sel = "#captcha_container, #captcha-verify-image, .captcha_verify_container, .captcha-verify-container, [class*='captcha-verify'], [id^='captcha_']";
  var els = document.querySelectorAll(sel);
  for (var k = 0; k < els.length; k++) if (vis(els[k])) return 'el:' + String(els[k].id || els[k].className).slice(0, 60);
  return '';
})()
"""

        private const val EMBEDDED_JS = """
(function(){
  var out = [];
  var rd = document.getElementById('RENDER_DATA');
  if (rd) { try { out.push(decodeURIComponent(rd.textContent)); } catch(e) { out.push(rd.textContent); } }
  var keys = ['_ROUTER_DATA', 'SSR_HYDRATED_DATA', '__INITIAL_STATE__'];
  for (var i = 0; i < keys.length; i++) { try { if (window[keys[i]]) out.push(JSON.stringify(window[keys[i]])); } catch(e){} }
  return JSON.stringify(out);
})()
"""

        private const val SCROLL_JS = """
(function(){
  window.scrollBy(0, 2400);
  var els = document.querySelectorAll('[data-e2e="user-post-list"], [class*="scroll"], main');
  for (var i = 0; i < els.length; i++) { var el = els[i]; if (el.scrollHeight > el.clientHeight + 50) el.scrollTop += 2400; }
  window.dispatchEvent(new Event('scroll'));
  return true;
})()
"""

        private const val DISMISS_JS = """
(function(){
  var sel = ['.dy-account-close', '[class*="login"] [class*="close"]', '[aria-label="关闭"]'];
  for (var i = 0; i < sel.length; i++) {
    var els = document.querySelectorAll(sel[i]);
    for (var j = 0; j < els.length; j++) { var r = els[j].getBoundingClientRect(); if (r.width > 0 && r.height > 0) els[j].click(); }
  }
  return true;
})()
"""

        private const val LINKS_JS = """
(function(){
  var a = document.querySelectorAll('a[href]'), out = [];
  for (var i = 0; i < a.length; i++) out.push(a[i].getAttribute('href'));
  return JSON.stringify(out);
})()
"""
    }
}
