package io.github.avsessisozx.douyinindirici

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gerçek uygulama, gerçek WebView, sahte Douyin sunucusu (tests/fake_douyin.py,
 * CI'da bilgisayarda çalışır; emülatör ona 10.0.2.2 adresinden ulaşır).
 * Testler arayüzdeki düğmelere basar, kullanıcı gibi.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val base = InstrumentationRegistry.getArguments().getString("fakeBase") ?: "http://10.0.2.2:8765"
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var out: File

    private val okProfile = "MS4wLjABAAAAprofile_ok_000000000001"
    private val wallProfile = "MS4wLjABAAAAprofile_wall_0000000001"
    private val captchaProfile = "MS4wLjABAAAAprofile_captcha_000001"

    @Before
    fun setUp() {
        TestHooks.baseUrl = base
        TestHooks.idleSeconds = 5.0
        out = File(ctx.getExternalFilesDir(null), "e2e/${System.nanoTime()}")
        TestHooks.sinkFactory = { FileSink(out) }
        Core.init(ctx)
        Core.saveSettings(SettingsValues(lang = "tr"))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun tearDown() {
        if (Core.engine.running) {
            Core.engine.stop()
            waitUntil(20_000) { !Core.engine.running }
        }
        scenario?.close()
        TestHooks.sinkFactory = null
    }

    // ---------------------------------------------------------------- yardımcılar
    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun launch(intent: Intent? = null): ActivityScenario<MainActivity> {
        val s = if (intent != null) ActivityScenario.launch<MainActivity>(intent) else ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        assertTrue("arayüz açılmadı", waitUntil(30_000) { activity(s)?.uiReady == true })
        assertTrue("arayüz yüklenmedi", waitUntil(15_000) { js("document.body.classList.contains('loading') === false") == "true" })
        return s
    }

    private fun activity(s: ActivityScenario<MainActivity>? = scenario): MainActivity? {
        val ref = AtomicReference<MainActivity?>()
        try { s?.onActivity { ref.set(it) } } catch (e: Exception) {}
        return ref.get()
    }

    /** Arayüzde JavaScript çalıştırır, sonucu (JSON metni) döndürür. */
    private fun js(code: String): String? {
        val a = activity() ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        inst.runOnMainSync { a.runUiScript(code) { result.set(it); latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    private fun jsText(code: String): String =
        js(code)?.let { try { JSONTokener(it).nextValue() as? String } catch (e: Exception) { null } } ?: ""

    private fun setLink(link: String) {
        js("(function(){var l=document.getElementById('link'); l.value=${JSONObject.quote(link)}; l.dispatchEvent(new Event('input')); return true;})()")
        assertTrue("link algılanmadı", waitUntil(5_000) { js("document.getElementById('step1').classList.contains('done')") == "true" })
    }

    private fun clickId(id: String) = js("document.getElementById('$id').click(), true")

    private fun setSelect(id: String, value: String) =
        js("(function(){var s=document.getElementById('$id'); s.value='$value'; s.dispatchEvent(new Event('change')); return true;})()")

    private fun startAndWait(timeoutMs: Long = 120_000, during: (() -> Unit)? = null): JSONObject {
        clickId("start")
        assertTrue("indirme başlamadı: " + jsText("document.getElementById('form-error').textContent"),
            waitUntil(10_000) { Core.engine.running || Core.engine.phase != "idle" })
        val end = System.currentTimeMillis() + timeoutMs
        while (Core.engine.running && System.currentTimeMillis() < end) {
            during?.invoke()
            Thread.sleep(300)
        }
        assertFalse("iş zamanında bitmedi", Core.engine.running)
        Thread.sleep(1500)  // arayüz son durumu çizsin
        return Core.engine.snapshot(0)
    }

    /** Sahte sunucunun doğrulama ekranını yeniden göstermesini sağlar. */
    private fun resetCaptcha() {
        val c = java.net.URL("$base/captcha-reset").openConnection() as java.net.HttpURLConnection
        try { c.responseCode } finally { c.disconnect() }
    }

    private fun files(): List<String> =
        out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path }.sorted().toList()

    /** Ekran görüntüsünü İndirilenler/di-screens klasörüne kaydeder (CI oradan alır). */
    private fun screenshot(name: String) {
        inst.waitForIdleSync()
        Thread.sleep(1500)   // emülatörde WebView'in yeni kareyi çizmesi zaman alıyor
        val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/di-screens/")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun logs(snap: JSONObject): String {
        val arr = snap.getJSONArray("logs")
        return (0 until arr.length()).joinToString("\n") { i ->
            val l = arr.getJSONObject(i)
            l.optJSONObject("msg")?.let { Core.tr.render(Msg(it.getString("key"), toMap(it.optJSONObject("params"))), "tr") } ?: l.optString("text")
        }
    }

    private fun toMap(o: JSONObject?): Map<String, Any?> {
        if (o == null) return emptyMap()
        val m = HashMap<String, Any?>()
        for (k in o.keys()) {
            val v = o.opt(k)
            m[k] = if (v is JSONObject && v.has("key")) Msg(v.getString("key"), toMap(v.optJSONObject("params"))) else v
        }
        return m
    }

    // ---------------------------------------------------------------- testler
    @Test
    fun a_profileDownloadThroughUi() {
        launch()
        screenshot("01_bos_ekran")
        assertEquals("İndirmeyi başlat", jsText("document.getElementById('start').textContent"))
        setLink("https://www.douyin.com/user/$okProfile")
        assertEquals("Profil linki algılandı.", jsText("document.getElementById('detect').textContent"))
        assertEquals("true", js("document.getElementById('mode-profile').checked"))
        screenshot("02_link_yapistirildi")

        var midShot = false
        var openButtonWhileRunning = false
        val snap = startAndWait {
            if (!midShot && Core.engine.filesDone.get() > 2) {
                if (js("document.getElementById('done-actions').offsetParent !== null") == "true" && Core.engine.running) openButtonWhileRunning = true
                screenshot("03_indiriliyor"); midShot = true
            }
        }
        assertFalse("'Galeride aç' indirme sürerken görünmemeli", openButtonWhileRunning)
        assertEquals("true", js("document.getElementById('done-actions').offsetParent !== null"))
        screenshot("04_bitti")
        val l = logs(snap)
        assertEquals(l, "done", snap.getString("phase"))
        assertEquals(l, 14, snap.getInt("found_posts"))
        val f = files()
        assertEquals(f.toString(), 13, f.count { it.endsWith(".mp4") && "hareketli" !in it })
        assertEquals(f.toString(), 3, f.count { it.endsWith(".jpg") || it.endsWith(".png") })
        assertTrue(f.toString(), f.all { it.startsWith("Sahte Kişi/") })
        assertEquals("İndirme tamamlandı", jsText("document.getElementById('status').textContent"))
        assertTrue(jsText("document.getElementById('sub').textContent").startsWith("Bitti: 17 dosya indirildi"))
    }

    @Test
    fun b_captchaShowsOverlayAndContinues() {
        resetCaptcha()
        launch()
        setLink("https://www.douyin.com/user/$captchaProfile")
        setSelect("limit", "10")
        var sawOverlay = false
        var shot = false
        val snap = startAndWait {
            val a = activity()
            if (a?.overlayMode == "captcha") {
                sawOverlay = true
                if (!shot) { Thread.sleep(700); screenshot("05_dogrulama_ekrani"); shot = true }
            }
        }
        assertTrue("doğrulama ekranı gösterilmedi\n" + logs(snap), sawOverlay)
        assertEquals(logs(snap), "done", snap.getString("phase"))
        assertEquals(10, snap.getInt("found_posts"))
        assertEquals(null, activity()?.overlayMode)
        assertEquals("true", js("getComputedStyle(document.body).visibility === 'visible'"))
    }

    @Test
    fun c_loginWallIsExplained() {
        launch()
        setLink("https://www.douyin.com/user/$wallProfile")
        val snap = startAndWait()
        screenshot("06_giris_uyarisi")
        assertTrue(logs(snap), snap.getBoolean("login_wall"))
        assertEquals(6, snap.getInt("found_posts"))
        assertTrue(jsText("document.getElementById('notes').textContent").contains("giriş yapılmadan"))
    }

    @Test
    fun d_singleVideoToGallery() {
        TestHooks.sinkFactory = null   // gerçek galeri (MediaStore)
        val resolver = ctx.contentResolver
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("DCIM/Douyin/SSR作者/", "%_7400000000000000003.mp4")
        resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)

        launch()
        setLink("https://www.douyin.com/video/7400000000000000003")
        assertEquals("true", js("document.getElementById('mode-single').checked"))
        assertEquals("profil ayarları tek gönderide gizli olmalı", "true",
            js("document.getElementById('row-content').offsetParent === null && document.getElementById('row-limit').offsetParent === null"))
        val snap = startAndWait()
        assertEquals(logs(snap), "done", snap.getString("phase"))
        assertEquals(1, snap.getInt("files_done"))
        var count = 0
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), where, args, null)?.use { c ->
            while (c.moveToNext()) { count++; assertTrue(c.getLong(1) > 0) }
        }
        assertEquals("galeride bulunamadı", 1, count)
        screenshot("07_tek_video")

        // İkinci kez: zaten var
        clickId("start")
        waitUntil(10_000) { Core.engine.running }
        waitUntil(60_000) { !Core.engine.running }
        assertEquals(1, Core.engine.filesSkipped.get())
        resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
    }

    @Test
    fun e_noteViaDetailApi() {
        launch()
        setLink("https://www.douyin.com/note/7400000000000000002")
        val snap = startAndWait()
        assertEquals(logs(snap), "done", snap.getString("phase"))
        val f = files()
        assertTrue(f.toString(), f.any { it.endsWith("_01.jpg") || it.endsWith("/01.jpg") })
        assertEquals(f.toString(), 4, f.size)
    }

    @Test
    fun f_shareMenuFillsLink() {
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "7.94 复制打开抖音，看看【作品】 https://www.douyin.com/video/7400000000000000003 复制此链接")
        launch(intent)
        assertTrue(waitUntil(5_000) { jsText("document.getElementById('link').value").contains("douyin.com/video/7400000000000000003") })
        assertTrue(waitUntil(5_000) { jsText("document.getElementById('detect').textContent") == "Tek gönderi linki algılandı." })
        screenshot("08_paylas_menusu")
    }

    @Test
    fun g_englishInterface() {
        launch()
        setSelect("lang", "en")
        assertTrue(waitUntil(5_000) { jsText("document.getElementById('start').textContent") == "Start download" })
        assertEquals("Paste the link", jsText("document.querySelector('#step1 h2').textContent"))
        screenshot("09_ingilizce")
        setSelect("lang", "auto")
    }

    @Test
    fun h_stopButton() {
        launch()
        setLink("https://www.douyin.com/user/$wallProfile")
        clickId("start")
        assertTrue(waitUntil(15_000) { Core.engine.running && Core.engine.phase == "collecting" })
        Thread.sleep(1500)
        clickId("start")   // aynı düğme "Durdur" olur
        assertTrue(waitUntil(20_000) { !Core.engine.running })
        assertEquals("stopped", Core.engine.phase)
        Thread.sleep(1200)
        assertEquals("Durduruldu", jsText("document.getElementById('status').textContent"))
    }

    @Test
    fun i_screenRebuiltDuringCollection() {
        // Android ekranı yeniden kurduğunda (izin penceresi, tema değişimi...) iş yarıda kalmamalı.
        val s = launch()
        setLink("https://www.douyin.com/user/$okProfile")
        clickId("start")
        assertTrue(waitUntil(20_000) { Core.engine.running && Core.engine.phase == "collecting" && Core.engine.foundPosts.get() > 0 })
        s.recreate()
        assertTrue("ekran geri gelmedi", waitUntil(30_000) { activity(s)?.uiReady == true })
        assertTrue(waitUntil(120_000) { !Core.engine.running })
        val snap = Core.engine.snapshot(0)
        assertEquals(logs(snap), "done", snap.getString("phase"))
        assertEquals(logs(snap), 14, snap.getInt("found_posts"))
        Thread.sleep(1500)
        assertEquals("İndirme tamamlandı", jsText("document.getElementById('status').textContent"))
        screenshot("10_ekran_yeniden_kuruldu")
    }

    @Test
    fun j_captchaWhileScreenRebuilt() {
        // Doğrulama ekranı açıkken ekran yeniden kurulursa bulmaca yine görünmeli.
        resetCaptcha()
        val s = launch()
        setLink("https://www.douyin.com/user/$captchaProfile")
        setSelect("limit", "10")
        clickId("start")
        assertTrue("doğrulama ekranı gelmedi", waitUntil(20_000) { activity(s)?.overlayMode == "captcha" })
        s.recreate()
        assertTrue(waitUntil(30_000) { activity(s)?.uiReady == true })
        val shown = activity(s)?.overlayMode == "captcha" || !Core.engine.captcha
        assertTrue("yeniden kurulan ekranda doğrulama gösterilmedi", shown)
        assertTrue(waitUntil(120_000) { !Core.engine.running })
        assertEquals("done", Core.engine.phase)
        assertEquals(null, activity(s)?.overlayMode)
    }
}
