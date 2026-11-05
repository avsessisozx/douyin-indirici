package io.github.avsessisozx.douyinindirici

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * İndirme motorunu telefonsuz dener: sahte "Douyin tarayıcısı" gönderi verisi verir,
 * dosyalar bilgisayardaki küçük bir HTTP sunucusundan iner, düz klasöre yazılır.
 */
class EngineTest {
    private lateinit var server: TinyHttpServer
    private lateinit var base: String
    private lateinit var out: File
    val hits = ConcurrentHashMap<String, Int>()

    @Before
    fun setUp() {
        server = TinyHttpServer { path, headers ->
            val name = path.substringAfterLast('/')
            hits.merge(name, 1) { a, b -> a + b }
            when {
                headers["referer"] != "https://www.douyin.com/" -> Triple(403, "text/plain", "no referer".toByteArray())
                name.startsWith("forbidden") -> Triple(403, "text/plain", "x".toByteArray())
                name.startsWith("html") -> Triple(200, "text/html", "<html>".toByteArray())
                name.startsWith("slow") -> { Thread.sleep(400); Triple(200, "video/mp4", ByteArray(64 * 1024) { 1 }) }
                name.endsWith(".mp4") -> Triple(200, "video/mp4", ("mp4" + name).repeat(50).toByteArray())
                name.endsWith(".jpeg") -> Triple(200, "image/jpeg", ("jpg" + name).toByteArray())
                name.endsWith(".png") -> Triple(200, "image/png", ("png" + name).toByteArray())
                name.endsWith(".webp") -> Triple(200, "image/webp", ("webp" + name).toByteArray())
                else -> Triple(404, "text/plain", "nope".toByteArray())
            }
        }.start()
        base = "http://127.0.0.1:${server.port}"
        out = Files.createTempDirectory("di-test").toFile()
    }

    @After
    fun tearDown() {
        server.stop()
        out.deleteRecursively()
    }

    // ---------------------------------------------------------------- sahte veri
    private fun video(id: String, name: String = "Kişi", sec: String = "MS4wLjABAAAAtest_sec_uid_0001", urls: List<String>? = null): JSONObject {
        val list = JSONArray(urls ?: listOf("$base/v1080_$id.mp4", "$base/v1080b_$id.mp4"))
        return JSONObject()
            .put("aweme_id", id).put("desc", "Video $id #etiket").put("create_time", 1717200000)
            .put("author", JSONObject().put("nickname", name).put("sec_uid", sec))
            .put("video", JSONObject()
                .put("play_addr", JSONObject().put("url_list", JSONArray(listOf("$base/default_$id.mp4"))))
                .put("bit_rate", JSONArray()
                    .put(JSONObject().put("bit_rate", 3000000).put("is_h265", 0)
                        .put("play_addr", JSONObject().put("url_list", list).put("width", 1080).put("height", 1920)))
                    .put(JSONObject().put("bit_rate", 2000000).put("is_h265", 1)
                        .put("play_addr", JSONObject().put("url_list", JSONArray(listOf("$base/h265_$id.mp4"))).put("width", 1080).put("height", 1920)))))
    }

    private fun gallery(id: String): JSONObject = JSONObject()
        .put("aweme_id", id).put("desc", "#风景").put("create_time", 1717300000).put("aweme_type", 68)
        .put("author", JSONObject().put("nickname", "Kişi").put("sec_uid", "MS4wLjABAAAAtest_sec_uid_0001"))
        .put("images", JSONArray()
            .put(JSONObject().put("url_list", JSONArray(listOf("$base/img1.webp", "$base/img1.jpeg"))))
            .put(JSONObject().put("url_list", JSONArray(listOf("$base/img2.png")))
                .put("video", JSONObject().put("play_addr", JSONObject().put("url_list", JSONArray(listOf("$base/live2.mp4")))))))

    inner class FakeWeb(
        val posts: List<JSONObject>,
        val result: ProfileResult = ProfileResult(reason = "end"),
        val slow: Boolean = false,
    ) : DouyinWeb {
        override var listener: WebListener? = null
        override var cancel: AtomicBoolean = AtomicBoolean(false)
        override var isPaused: () -> Boolean = { false }
        override val userAgent = "test-agent"
        override suspend fun prepare() {}
        override suspend fun resolve(p: Links.Parsed) = p
        override suspend fun getPost(awemeId: String, url: String, quiet: Boolean): JSONObject =
            posts.firstOrNull { Media.awemeIdOf(it) == awemeId } ?: throw DouyinError(msg("browser.post_failed"))
        var knownSeen: Set<String> = emptySet()
        var stopSeen = 0
        override suspend fun collectProfile(
            secUid: String, limit: Int, onItems: (List<JSONObject>) -> Unit,
            knownIds: Set<String>, stopAfterKnown: Int,
        ): ProfileResult {
            knownSeen = knownIds; stopSeen = stopAfterKnown
            val take = if (limit > 0) posts.take(limit) else posts
            var run = 0
            var upToDate = false
            val given = ArrayList<JSONObject>()
            for (chunk in take.chunked(3)) {
                if (cancel.get()) throw CancelledException()
                while (isPaused() && !cancel.get()) delay(50)
                onItems(chunk)
                given += chunk
                for (c in chunk) {
                    if (Media.awemeIdOf(c) in knownIds) { run++; if (stopAfterKnown > 0 && run >= stopAfterKnown) upToDate = true } else run = 0
                }
                if (upToDate) break
                if (slow) delay(300)
            }
            val reason = when {
                upToDate -> "up_to_date"
                limit > 0 && posts.size > limit -> "limit"
                else -> result.reason
            }
            return result.copy(collected = given.size, reason = reason, secUid = secUid)
        }
        override suspend fun loginSession() {}
        override fun cookiesFor(url: String): String? = null
    }

    private fun engine(web: DouyinWeb, history: HistoryStore? = null, sound: () -> Unit = {}) =
        Engine({ web }, { FileSink(out) }, CoroutineScope(SupervisorJob() + Dispatchers.Default), historyProvider = { history }, alertSound = sound)

    private fun runToEnd(e: Engine, job: Engine.Job, timeoutMs: Long = 20_000): JSONObject {
        assertNull(e.start(job))
        val end = System.currentTimeMillis() + timeoutMs
        while (e.running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertFalse("iş zamanında bitmedi", e.running)
        return e.snapshot(0)
    }

    private fun files(): List<String> = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path.replace('\\', '/') }.sorted().toList()

    private val s = SettingsValues(chunks = 1)      // parçalı indirme ayrı denenir (ChunkedFetchTest)
    private val profileLink = "https://www.douyin.com/user/MS4wLjABAAAAtest_sec_uid_0001"

    // ---------------------------------------------------------------- testler
    @Test
    fun profileDownloadsEverythingAndSkipsOnSecondRun() {
        val posts = (0 until 7).map { if (it == 3) gallery("74000000000000000$it") else video("74000000000000000$it") }
        val e = engine(FakeWeb(posts))
        val snap = runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(snap.toString(), "done", snap.getString("phase"))
        assertEquals(7, snap.getInt("found_posts"))
        val f = files()
        assertEquals(f.toString(), 6, f.count { it.endsWith(".mp4") && "hareketli" !in it })
        assertTrue(f.toString(), f.any { it.endsWith("/01.jpg") })            // jpeg, webp'den önce seçildi
        assertTrue(f.toString(), f.any { it.endsWith("/02.png") })
        assertTrue(f.toString(), f.any { it.endsWith("/02_hareketli.mp4") })
        assertTrue(f.all { it.startsWith("Kişi/") })
        assertFalse(f.any { it.endsWith(".part") })
        assertFalse("H.265 istenmemeli", hits.keys.any { it.startsWith("h265_") })
        assertEquals("summary", snap.getJSONObject("summary").getString("key"))

        val e2 = engine(FakeWeb(posts))
        val snap2 = runToEnd(e2, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(0, snap2.getInt("files_done"))
        assertEquals(snap.getInt("files_done"), snap2.getInt("files_skipped"))
        assertTrue(e2.logKeys().contains("engine.already_had"))
    }

    @Test
    fun mirrorFallbackAndFailures() {
        val posts = listOf(
            video("7400000000000000011", urls = listOf("$base/forbidden_a.mp4", "$base/ok_a.mp4")),
            video("7400000000000000012", urls = listOf("$base/html_b.mp4")).also {
                it.getJSONObject("video").remove("play_addr")
                it.getJSONObject("video").getJSONArray("bit_rate").remove(1)
            },
        )
        val e = engine(FakeWeb(posts))
        val snap = runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(1, snap.getInt("files_done"))
        assertEquals(1, snap.getInt("files_failed"))
        assertEquals(1, hits["forbidden_a.mp4"])          // 403: tekrar denenmeden sonrakine geçildi
        assertTrue(e.logKeys().contains("engine.download_failed"))
        assertEquals("done", snap.getString("phase"))
    }

    @Test
    fun contentFilterAndLimit() {
        val posts = (0 until 6).map { if (it % 2 == 0) gallery("74000000000000002$it") else video("74000000000000002$it") }
        val e = engine(FakeWeb(posts))
        val snap = runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s.copy(content = Media.CONTENT_IMAGE, limit = 4)))
        assertEquals(4, snap.getInt("found_posts"))
        assertEquals(2, snap.getInt("posts_skipped"))
        assertFalse(files().any { it.endsWith(".mp4") && "hareketli" !in it })
        assertTrue(e.logKeys().contains("engine.limit_reached"))
    }

    @Test
    fun singlePostIgnoresProfileFilters() {
        val posts = listOf(gallery("7400000000000000031"))
        val e = engine(FakeWeb(posts))
        val snap = runToEnd(e, Engine.Job("https://www.douyin.com/note/7400000000000000031", Engine.MODE_SINGLE,
            s.copy(content = Media.CONTENT_VIDEO)))
        assertEquals("done", snap.getString("phase"))
        assertEquals(3, snap.getInt("files_done"))
    }

    @Test
    fun loginWallPrivateEmptyAndErrors() {
        val wall = engine(FakeWeb(listOf(video("7400000000000000041")), ProfileResult(reason = "login_wall", reportedCount = 30)))
        val s1 = runToEnd(wall, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertTrue(s1.getBoolean("login_wall"))
        assertTrue(wall.logKeys().contains("engine.login_wall_total"))

        val priv = engine(FakeWeb(emptyList(), ProfileResult(reason = "private")))
        val s2 = runToEnd(priv, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals("error", s2.getString("phase"))
        assertEquals("engine.private", s2.getJSONObject("summary").getString("key"))

        val stalled = engine(FakeWeb(emptyList(), ProfileResult(reason = "stalled")))
        val s3 = runToEnd(stalled, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals("engine.no_list", s3.getJSONObject("summary").getString("key"))

        val missing = engine(FakeWeb(emptyList()))
        val s4 = runToEnd(missing, Engine.Job("https://www.douyin.com/video/7400000000000000049", Engine.MODE_SINGLE, s))
        assertEquals("browser.post_failed", s4.getJSONObject("summary").getString("key"))

        val noWindow = Engine({ null }, { FileSink(out) }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val s5 = runToEnd(noWindow, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals("android.no_window", s5.getJSONObject("summary").getString("key"))
    }

    @Test
    fun startValidation() {
        val e = engine(FakeWeb(emptyList()))
        assertEquals("link.not_found", e.start(Engine.Job("merhaba", Engine.MODE_PROFILE, s))?.key)
        assertEquals("engine.single_needs_post", e.start(Engine.Job(profileLink, Engine.MODE_SINGLE, s))?.key)
        assertFalse(e.running)
    }

    @Test
    fun stopMidway() {
        val posts = (0 until 30).map { video("7400000000000001${it.toString().padStart(3, '0')}", urls = listOf("$base/slow_$it.mp4")) }
        val e = engine(FakeWeb(posts, slow = true))
        assertNull(e.start(Engine.Job(profileLink, Engine.MODE_PROFILE, s)))
        Thread.sleep(900)
        e.stop()
        val end = System.currentTimeMillis() + 15_000
        while (e.running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertFalse(e.running)
        val snap = e.snapshot(0)
        assertEquals("stopped", snap.getString("phase"))
        assertTrue(snap.getInt("files_done") < 30)
        assertFalse(files().any { it.endsWith(".part") })
    }

    @Test
    fun snapshotLogsArePaged() {
        val e = engine(FakeWeb(listOf(video("7400000000000000051"))))
        val snap = runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        val next = snap.getInt("log_next")
        assertTrue(snap.getJSONArray("logs").length() > 0)
        assertEquals(0, e.snapshot(next).getJSONArray("logs").length())
    }

    // ---------------------------------------------------------------- yeni özellikler
    private val sec = "MS4wLjABAAAAtest_sec_uid_0001"

    @Test
    fun historySkipsKnownPostsAndStopsEarly() {
        val posts = (0 until 30).map { video("74000000000010${it.toString().padStart(3, '0')}") }
        val hist = MemoryHistory()
        val first = engine(FakeWeb(posts), hist)
        val s1 = runToEnd(first, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(s1.toString(), "done", s1.getString("phase"))
        assertEquals(30, hist.count())
        assertTrue("tam taramadan sonra işaretlenmeli", hist.wasScanned(sec))

        // 3 yeni gönderi + eskiler: yeniler iner, eskiler atlanır, 10 bilinen üst üste görülünce tarama biter
        val newer = (0 until 3).map { video("74000000000099$it") }
        val web = FakeWeb(newer + posts)
        val second = engine(web, hist)
        val s2 = runToEnd(second, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(3, s2.getInt("files_done"))
        assertTrue(s2.getInt("posts_known") >= 10)
        assertEquals(Engine.KNOWN_STOP, web.stopSeen)
        assertEquals(30, web.knownSeen.size)
        assertTrue(second.logKeys().contains("engine.up_to_date"))
        assertEquals(33, hist.count())
    }

    @Test
    fun historyOffDownloadsAgain() {
        val posts = (0 until 4).map { video("74000000000020$it") }
        val hist = MemoryHistory()
        runToEnd(engine(FakeWeb(posts), hist), Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        val snap = runToEnd(engine(FakeWeb(posts), hist), Engine.Job(profileLink, Engine.MODE_PROFILE, s.copy(history = false)))
        assertEquals(0, snap.getInt("posts_known"))
        assertEquals(4, snap.getInt("files_skipped"))   // dosyalar zaten var
    }

    @Test
    fun failedPostsAreRetriedAutomaticallyAndOnDemand() {
        // ilk istekte 403, sonra düzelen adres
        var calls = 0
        val flaky = TinyHttpServer { path, headers ->
            val name = path.substringAfterLast('/')
            if (headers["referer"] != "https://www.douyin.com/") Triple(403, "text/plain", "x".toByteArray())
            else if (name.startsWith("flaky") && calls++ < 2) Triple(500, "text/plain", "x".toByteArray())
            else Triple(200, "video/mp4", ("mp4" + name).repeat(20).toByteArray())
        }.start()
        try {
            val fb = "http://127.0.0.1:${flaky.port}"
            val post = video("7400000000003001", urls = listOf("$fb/flaky_1.mp4"))
            post.getJSONObject("video").remove("play_addr")
            post.getJSONObject("video").getJSONArray("bit_rate").remove(1)
            val e = engine(FakeWeb(listOf(post)), MemoryHistory())
            val snap = runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
            assertEquals(snap.toString(), 1, snap.getInt("files_done"))
            assertEquals(0, snap.getInt("files_failed"))
            assertTrue(e.logKeys().contains("engine.retrying"))
        } finally {
            flaky.stop()
        }
    }

    @Test
    fun retryModeNeedsFailedPosts() {
        val e = engine(FakeWeb(emptyList()))
        assertEquals("engine.retry_none", e.start(Engine.Job("", Engine.MODE_RETRY, s))?.key)
    }

    @Test
    fun pauseHoldsNewFilesUntilResume() {
        val posts = (0 until 12).map { video("74000000000040${it.toString().padStart(2, '0')}", urls = listOf("$base/slow_$it.mp4")) }
        val web = FakeWeb(posts)
        val e = engine(web)
        assertNull(e.start(Engine.Job(profileLink, Engine.MODE_PROFILE, s.copy(parallel = 1))))
        Thread.sleep(700)
        e.pause()
        assertTrue(e.snapshot(0).getBoolean("paused"))
        Thread.sleep(900)                                  // o an inen dosya biter
        val frozen = e.snapshot(0).getInt("files_done")
        Thread.sleep(1200)
        assertEquals("duraklatmada yeni dosya inmemeli", frozen, e.snapshot(0).getInt("files_done"))
        e.resume()
        val end = System.currentTimeMillis() + 30_000
        while (e.running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertFalse(e.running)
        assertEquals(12, e.snapshot(0).getInt("files_done"))
        assertFalse(e.snapshot(0).getBoolean("paused"))
    }

    @Test
    fun captchaPlaysSoundOnlyWhenEnabled() {
        var beeps = 0
        runToEnd(engine(CaptchaWebAdapter(FakeWeb(emptyList())), sound = { beeps++ }), Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        assertEquals(1, beeps)                              // art arda iki bildirim tek ses
        runToEnd(engine(CaptchaWebAdapter(FakeWeb(emptyList())), sound = { beeps++ }), Engine.Job(profileLink, Engine.MODE_PROFILE, s.copy(sound = false)))
        assertEquals(1, beeps)
    }

    inner class CaptchaWebAdapter(delegate: FakeWeb) : DouyinWeb by delegate {
        override var listener: WebListener? = null
        override suspend fun collectProfile(secUid: String, limit: Int, onItems: (List<JSONObject>) -> Unit, knownIds: Set<String>, stopAfterKnown: Int): ProfileResult {
            listener?.captcha(true); listener?.captcha(true); listener?.captcha(false)
            onItems(listOf(video("7400000000005001")))
            return ProfileResult(reason = "end", collected = 1, secUid = secUid)
        }
    }

    @Test
    fun reportHasNoSecretsAndKeepsLogs() {
        val posts = listOf(video("7400000000006001"))
        val e = engine(FakeWeb(posts))
        runToEnd(e, Engine.Job(profileLink, Engine.MODE_PROFILE, s))
        e.logText("debug", "Cookie: sessionid=ABC123SECRET; ttwid=XYZ; token=tkn")
        val text = Report.build(listOf("Sürüm" to "1.2.0"), s.toJson(), e.snapshot(0)) { k, _ -> k }
        assertTrue(text.contains("Sürüm: 1.2.0"))
        assertFalse(text.contains("ABC123SECRET"))
        assertFalse(text.contains("XYZ"))
        assertFalse(text.contains("tkn"))
        assertTrue(text.contains("[silindi]"))
        assertFalse("indirme klasörü rapora girmemeli", text.contains("out_dir"))
    }

    @Test
    fun settingsClamp() {
        val v = SettingsValues().merge(JSONObject("{\"parallel\":5,\"chunks\":99,\"history\":false,\"sound\":0}"), listOf("tr", "en"))
        assertEquals(3, v.parallel)                         // 5 geçerli seçenek değil: varsayılan kalır
        assertEquals(4, v.chunks)
        assertFalse(v.history)
        assertFalse(v.sound)
        val ok = v.merge(JSONObject("{\"parallel\":8,\"chunks\":1}"), listOf("tr", "en"))
        assertEquals(8, ok.parallel)
        assertEquals(1, ok.chunks)
    }

    @Test
    fun historyStoreBasics() {
        val h = MemoryHistory()
        h.mark("A", "1"); h.mark("A", "2"); h.mark("B", "1")
        assertEquals(setOf("1", "2"), h.known("A"))
        assertEquals(3, h.count())
        h.setScanned("A")
        assertTrue(h.wasScanned("A"))
        assertFalse(h.wasScanned("B"))
        assertEquals(3, h.clear())
        assertEquals(0, h.count())
        assertFalse(h.wasScanned("A"))
    }
}
